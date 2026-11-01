"""Offline recovery invariants using synthetic data and the shipped ONNX only.

Run: .venv-impact/Scripts/python.exe -B -m unittest discover -s tests -p test_impact_recovery.py -v
"""

import copy
from dataclasses import asdict
import os
from pathlib import Path
import pickle
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

for _variable in ("OMP_NUM_THREADS", "MKL_NUM_THREADS", "OPENBLAS_NUM_THREADS"):
    os.environ[_variable] = "2"

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper
import torch
from torch import nn

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import recover_impact as recovery


def setUpModule():
    torch.set_num_threads(2)
    if torch.get_num_interop_threads() != 2:
        torch.set_num_interop_threads(2)


def synthetic_proto():
    arrays = {
        "left_weight": np.arange(1, 7, dtype=np.float32).reshape(2, 3, 1, 1) / 16,
        "left_bias": np.array([.125, -.25], np.float32),
        "right_weight": np.arange(11, 17, dtype=np.float32).reshape(2, 3, 1, 1) / 16,
        "right_bias": np.array([.375, -.5], np.float32),
        "center": np.array([.125, .25, .5], np.float32).reshape(1, 3, 1, 1),
        "scale": np.array([1, 2, 4], np.float32).reshape(1, 3, 1, 1),
    }
    initializers = [numpy_helper.from_array(value, name=name) for name, value in arrays.items()]
    for tensor in initializers:
        tensor.doc_string = "Synthetic provenance for " + tensor.name
    nodes = [
        helper.make_node("Sub", ["rgb", "center"], ["centered"], name="center_rgb"),
        helper.make_node("Div", ["centered", "scale"], ["normalized"], name="scale_rgb"),
        helper.make_node("Conv", ["normalized", "left_weight", "left_bias"],
                         ["left_output"], name="left_conv", kernel_shape=[1, 1]),
        helper.make_node("Conv", ["normalized", "right_weight", "right_bias"],
                         ["right_output"], name="right_conv", kernel_shape=[1, 1]),
        helper.make_node("Add", ["left_output", "right_output"], ["combined"], name="combine"),
        helper.make_node("Constant", [], ["output_shape"], name="fixed_output_shape",
                         value=numpy_helper.from_array(np.array(recovery.OUTPUT_SHAPE, np.int64))),
        helper.make_node("Reshape", ["combined", "output_shape"], ["heatmaps"], name="reshape"),
    ]
    graph = helper.make_graph(
        nodes, "synthetic_recovery",
        [helper.make_tensor_value_info("rgb", TensorProto.FLOAT, recovery.INPUT_SHAPE)],
        [helper.make_tensor_value_info("heatmaps", TensorProto.FLOAT, recovery.OUTPUT_SHAPE)],
        initializer=initializers,
        value_info=[helper.make_tensor_value_info("normalized", TensorProto.FLOAT, recovery.INPUT_SHAPE)],
    )
    graph.doc_string = "Synthetic normalization, two same-shaped Conv tensors, and a shape constant."
    proto = helper.make_model(graph, producer_name="recovery-tests", producer_version="1",
                              domain="tests.redwood", model_version=7,
                              opset_imports=[helper.make_opsetid("", 13)])
    proto.doc_string = "No private images or labels."
    helper.set_model_props(proto, {"fixture": "exact-binding", "purpose": "offline-tests"})
    onnx.checker.check_model(proto)
    return proto


def binding_fixture():
    proto = synthetic_proto()
    arrays = {tensor.name: numpy_helper.to_array(tensor) for tensor in proto.graph.initializer}
    graph = nn.Module()
    # Reverse registration order: binding must follow tensor identities, not iteration order.
    for branch in ("right", "left"):
        module = nn.Conv2d(3, 2, 1)
        with torch.no_grad():
            module.weight.copy_(torch.from_numpy(arrays[branch + "_weight"].copy()))
            module.bias.copy_(torch.from_numpy(arrays[branch + "_bias"].copy()))
        module.onnx_mapping = SimpleNamespace(inputs=("normalized",), outputs=(branch + "_output",))
        graph.add_module(branch, module)
    return proto, graph


def make_recovered(directory, with_alias=False):
    path = directory / "synthetic.onnx"
    onnx.save_model(synthetic_proto(), path)
    real_convert = recovery.convert

    def convert_with_alias(*args, **kwargs):
        graph = real_convert(*args, **kwargs)
        value = next(value for _, value in graph.named_buffers() if value.is_floating_point())
        graph.register_buffer("normalization_alias", value)
        return graph

    # Permit only this fixture's actual bytes; source-hash rejection is tested separately.
    with patch.object(recovery, "MODEL_SHA256", recovery.file_hash(path)):
        if with_alias:
            with patch.object(recovery, "convert", side_effect=convert_with_alias):
                return recovery.RecoveredImpact(path)
        return recovery.RecoveredImpact(path)


def cloned_state(model):
    return {name: value.detach().cpu().clone() for name, value in model.state_dict().items()}


def graph_without_initializers(proto):
    result = onnx.ModelProto()
    result.CopyFrom(proto)
    result.graph.ClearField("initializer")
    return result.SerializeToString()


def unsafe_unpickle_callback():
    raise AssertionError("Checkpoint loading executed an arbitrary pickle global")


class UnsafePicklePayload:
    def __reduce__(self):
        return unsafe_unpickle_callback, ()


class RecoveryTestCase(unittest.TestCase):
    def setUp(self):
        (ROOT / "build").mkdir(exist_ok=True)
        temporary = tempfile.TemporaryDirectory(dir=ROOT / "build", prefix="impact-recovery-tests-")
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)

    def assertStateEqual(self, expected, model):
        actual = model.state_dict()
        self.assertEqual(set(actual), set(expected))
        for name, value in expected.items():
            with self.subTest(state_tensor=name):
                self.assertEqual(actual[name].dtype, value.dtype)
                self.assertEqual(actual[name].shape, value.shape)
                self.assertTrue(torch.equal(actual[name].cpu(), value), name)

    def assertRejectedWithoutMutation(self, model, saved, error=ValueError):
        path = self.directory / "rejected.pt"
        torch.save(saved, path)
        before = cloned_state(model)
        try:
            with self.assertRaises(error):
                model.load_checkpoint(path)
        finally:
            self.assertStateEqual(before, model)


class ConvolutionBindingTests(unittest.TestCase):
    def test_bindings_use_output_and_initializer_names_despite_reordered_modules(self):
        proto, graph = binding_fixture()
        initializers = list(proto.graph.initializer)
        proto.graph.ClearField("initializer")
        proto.graph.initializer.extend(reversed(initializers))
        self.assertEqual(recovery.convolution_bindings(proto, graph), [
            recovery.ConvBinding("right", "right_weight", "right_bias", "right_output"),
            recovery.ConvBinding("left", "left_weight", "left_bias", "left_output"),
        ])

    def test_same_shaped_weight_or_bias_swaps_are_rejected(self):
        for suffix in ("weight", "bias"):
            with self.subTest(suffix=suffix):
                proto, graph = binding_fixture()
                with torch.no_grad():
                    first = getattr(graph.left, suffix).clone()
                    getattr(graph.left, suffix).copy_(getattr(graph.right, suffix))
                    getattr(graph.right, suffix).copy_(first)
                with self.assertRaises(ValueError):
                    recovery.convolution_bindings(proto, graph)

    def test_even_one_float32_ulp_difference_is_rejected(self):
        proto, graph = binding_fixture()
        with torch.no_grad():
            value = graph.left.weight.flatten()[0]
            value.copy_(torch.nextafter(value, torch.tensor(float("inf"))))
        with self.assertRaises(ValueError):
            recovery.convolution_bindings(proto, graph)

    def test_swapped_output_mappings_are_rejected(self):
        proto, graph = binding_fixture()
        graph.left.onnx_mapping, graph.right.onnx_mapping = graph.right.onnx_mapping, graph.left.onnx_mapping
        with self.assertRaises(ValueError):
            recovery.convolution_bindings(proto, graph)

    def test_missing_ambiguous_and_nonconv_output_mappings_are_rejected(self):
        for outputs in (None, (), ("left_output", "right_output"), ("combined",)):
            with self.subTest(outputs=outputs):
                proto, graph = binding_fixture()
                if outputs is None:
                    del graph.left.onnx_mapping
                else:
                    graph.left.onnx_mapping.outputs = outputs
                with self.assertRaises(ValueError):
                    recovery.convolution_bindings(proto, graph)

    def test_duplicate_and_missing_conv_bindings_are_rejected(self):
        for mutation in ("duplicate", "missing"):
            with self.subTest(mutation=mutation):
                proto, graph = binding_fixture()
                if mutation == "duplicate":
                    graph.left.onnx_mapping = graph.right.onnx_mapping
                    graph.left.load_state_dict(graph.right.state_dict())
                else:
                    del graph.left
                with self.assertRaises(ValueError):
                    recovery.convolution_bindings(proto, graph)

    def test_extra_trainable_parameter_is_rejected(self):
        proto, graph = binding_fixture()
        graph.register_parameter("unmapped", nn.Parameter(torch.ones(1)))
        with self.assertRaises(ValueError):
            recovery.convolution_bindings(proto, graph)

    def test_shared_initializer_is_rejected_even_when_values_match(self):
        proto, graph = binding_fixture()
        left = next(node for node in proto.graph.node if node.output[0] == "left_output")
        left.input[1] = "right_weight"
        with torch.no_grad():
            graph.left.weight.copy_(graph.right.weight)
        with self.assertRaises(ValueError):
            recovery.convolution_bindings(proto, graph)

    def test_missing_initializer_and_missing_bias_are_rejected(self):
        for mutation in ("initializer", "onnx_bias", "torch_bias"):
            with self.subTest(mutation=mutation):
                proto, graph = binding_fixture()
                left = next(node for node in proto.graph.node if node.output[0] == "left_output")
                if mutation == "initializer":
                    left.input[1] = "nonexistent_weight"
                elif mutation == "onnx_bias":
                    del left.input[2]
                else:
                    graph.left.bias = None
                with self.assertRaises(ValueError):
                    recovery.convolution_bindings(proto, graph)

    def test_non_float32_weights_are_rejected_on_either_side(self):
        for side in ("onnx", "torch"):
            with self.subTest(side=side):
                proto, graph = binding_fixture()
                if side == "torch":
                    graph.left.double()
                else:
                    tensor = next(item for item in proto.graph.initializer if item.name == "left_weight")
                    tensor.CopyFrom(numpy_helper.from_array(numpy_helper.to_array(tensor).astype(np.float64),
                                                            name=tensor.name))
                with self.assertRaises(ValueError):
                    recovery.convolution_bindings(proto, graph)

    def test_convolution_geometry_mismatches_are_rejected(self):
        for name, value in (("padding", (1, 1)), ("stride", (2, 2)),
                            ("dilation", (2, 2)), ("groups", 3)):
            with self.subTest(attribute=name):
                proto, graph = binding_fixture()
                setattr(graph.left, name, value)
                with self.assertRaises(ValueError):
                    recovery.convolution_bindings(proto, graph)
        for name, value in (("pads", [1, 0, 0, 0]), ("auto_pad", "SAME_UPPER")):
            with self.subTest(onnx_attribute=name):
                proto, graph = binding_fixture()
                left = next(node for node in proto.graph.node if node.output[0] == "left_output")
                left.attribute.append(helper.make_attribute(name, value))
                with self.assertRaises(ValueError):
                    recovery.convolution_bindings(proto, graph)


class SourceAndInputGuardTests(RecoveryTestCase):
    def test_unsupported_source_hash_is_rejected_before_parse_or_conversion(self):
        path = self.directory / "unsupported.onnx"
        onnx.save_model(synthetic_proto(), path)
        with patch.object(recovery.onnx, "load_model_from_string") as parse:
            with patch.object(recovery, "convert") as convert:
                with self.assertRaisesRegex(ValueError, "Unsupported source"):
                    recovery.RecoveredImpact(path)
        parse.assert_not_called()
        convert.assert_not_called()

    def test_changed_shipped_model_metadata_still_fails_source_hash(self):
        proto = onnx.load_model(recovery.MODEL_PATH)
        proto.doc_string += " altered"
        path = self.directory / "changed-shipped.onnx"
        onnx.save_model(proto, path)
        with self.assertRaisesRegex(ValueError, "Unsupported source"):
            recovery.RecoveredImpact(path)

    def test_wrong_input_shape_or_dtype_is_rejected_before_graph_runs(self):
        model = make_recovered(self.directory)
        shapes = ((3, 384, 384), (2, 3, 384, 384), (1, 4, 384, 384),
                  (1, 3, 383, 384), (1, 3, 384, 383), (1, 384, 384, 3), (0, 3, 384, 384))
        inputs = [torch.zeros(shape, dtype=torch.float32) for shape in shapes]
        inputs += [torch.zeros(recovery.INPUT_SHAPE, dtype=dtype)
                   for dtype in (torch.float64, torch.float16, torch.uint8, torch.int64)]
        with patch.object(model.graph, "forward", side_effect=AssertionError("guard must run first")):
            for value in inputs:
                with self.subTest(shape=tuple(value.shape), dtype=value.dtype), self.assertRaises(ValueError):
                    model(value)

    def test_valid_input_reaches_graph_unchanged(self):
        model = make_recovered(self.directory)
        value = torch.zeros(recovery.INPUT_SHAPE)
        sentinel = torch.ones(recovery.OUTPUT_SHAPE)
        with patch.object(model.graph, "forward", return_value=sentinel) as forward:
            self.assertIs(model(value), sentinel)
        self.assertIs(forward.call_args.args[0], value)

    def test_outputs_cannot_escape_build_or_private(self):
        model = make_recovered(self.directory)
        invalid = (ROOT / "tools" / "recovery-test-do-not-create.pt",
                   ROOT / "build" / ".." / "recovery-test-do-not-create.pt",
                   ROOT / "build-lookalike" / "recovery-test-do-not-create.pt")
        for path in invalid:
            for method in (model.save_checkpoint, model.export_onnx):
                with self.subTest(path=str(path), method=method.__name__), self.assertRaises(ValueError):
                    method(path)
        self.assertEqual(recovery.local_output(self.directory / "nested" / "model.pt"),
                         (self.directory / "nested" / "model.pt").resolve())


class FrozenStateTests(RecoveryTestCase):
    def test_only_convolutions_are_trainable_and_training_keeps_constants(self):
        model = make_recovered(self.directory)
        expected = {f"graph.{binding.module}.{suffix}" for binding in model.bindings
                    for suffix in ("weight", "bias")}
        self.assertEqual(set(dict(model.named_parameters())), expected)
        self.assertTrue(all(value.requires_grad for value in model.parameters()))
        before = {name: value.clone() for name, value in model.graph.named_buffers(remove_duplicate=False)}
        self.assertTrue(before)
        self.assertTrue(all(not value.requires_grad for value in before.values()))
        model.train()
        output = model(torch.full(recovery.INPUT_SHAPE, .75))
        output.mean().backward()
        for name, parameter in model.named_parameters():
            with self.subTest(parameter=name):
                self.assertIsNotNone(parameter.grad)
                self.assertTrue(torch.isfinite(parameter.grad).all())
        optimizer = torch.optim.SGD(model.parameters(), lr=.01)
        optimizer.step()
        for name, value in model.graph.named_buffers(remove_duplicate=False):
            self.assertTrue(torch.equal(value, before[name]), name)
        model.validate_state()

    def test_mutated_float_and_integer_buffers_block_validation_save_and_export(self):
        for floating in (True, False):
            with self.subTest(floating=floating):
                model = make_recovered(self.directory)
                value = next(value for _, value in model.graph.named_buffers()
                             if value.is_floating_point() == floating)
                value.add_(1)
                for action in (model.validate_state,
                               lambda: model.save_checkpoint(self.directory / "invalid.pt"),
                               lambda: model.export_onnx(self.directory / "invalid.onnx")):
                    with self.assertRaises(ValueError):
                        action()
                self.assertFalse((self.directory / "invalid.pt").exists())
                self.assertFalse((self.directory / "invalid.onnx").exists())

    def test_buffer_dtype_changes_are_rejected_even_with_identical_values(self):
        for floating in (True, False):
            with self.subTest(floating=floating):
                model = make_recovered(self.directory)
                name, value = next((name, value) for name, value in model.graph.named_buffers()
                                   if value.is_floating_point() == floating)
                parent, _, leaf = name.rpartition(".")
                owner = model.graph.get_submodule(parent) if parent else model.graph
                setattr(owner, leaf, value.to(torch.float64))
                with self.assertRaises(ValueError):
                    model.validate_state()

    def test_removed_frozen_buffer_is_rejected(self):
        model = make_recovered(self.directory)
        name, _ = next(model.graph.named_buffers())
        parent, _, leaf = name.rpartition(".")
        owner = model.graph.get_submodule(parent) if parent else model.graph
        delattr(owner, leaf)
        with self.assertRaises(ValueError):
            model.validate_state()

    def test_nonfinite_and_nonfloat32_parameters_block_save_and_export(self):
        for bad in (float("nan"), float("inf"), -float("inf"), "double"):
            with self.subTest(bad=bad):
                model = make_recovered(self.directory)
                parameter = next(model.parameters())
                if bad == "double":
                    parameter.data = parameter.data.double()
                else:
                    with torch.no_grad():
                        parameter.flatten()[0] = bad
                for action in (model.validate_state,
                               lambda: model.save_checkpoint(self.directory / "invalid.pt"),
                               lambda: model.export_onnx(self.directory / "invalid.onnx")):
                    with self.assertRaises(ValueError):
                        action()


class CheckpointTests(RecoveryTestCase):
    def setUp(self):
        super().setUp()
        self.model = make_recovered(self.directory)
        self.path = self.model.save_checkpoint(self.directory / "original.pt")
        self.saved = torch.load(self.path, map_location="cpu", weights_only=True)

    def test_saved_checkpoint_has_safe_metadata_exact_tensors_and_detached_cpu_state(self):
        self.assertEqual(self.saved["schema"], recovery.SCHEMA)
        self.assertEqual(self.saved["source_sha256"], self.model.source_hash)
        self.assertEqual(self.saved["bindings"], [asdict(binding) for binding in self.model.bindings])
        self.assertEqual(self.saved["purpose"], "recovered-original")
        self.assertTrue(self.saved["environment"])
        self.assertStateEqual(self.saved["state_dict"], self.model)
        for value in self.saved["state_dict"].values():
            self.assertEqual(value.device.type, "cpu")
            self.assertFalse(value.requires_grad)

    def test_roundtrip_restores_changed_parameters_without_replacing_parameter_objects(self):
        ids = {name: id(value) for name, value in self.model.named_parameters()}
        with torch.no_grad():
            for parameter in self.model.parameters():
                parameter.add_(.25)
        changed = cloned_state(self.model)
        checkpoint = self.model.save_checkpoint(self.directory / "changed.pt", purpose="synthetic-step")
        with torch.no_grad():
            for parameter in self.model.parameters():
                parameter.zero_()
        with patch.object(recovery.torch, "load", wraps=torch.load) as load:
            self.model.load_checkpoint(checkpoint)
        self.assertTrue(load.call_args.kwargs["weights_only"])
        self.assertEqual(load.call_args.kwargs["map_location"], "cpu")
        self.assertStateEqual(changed, self.model)
        self.assertEqual({name: id(value) for name, value in self.model.named_parameters()}, ids)

    def test_rejected_identity_metadata_never_mutates_state(self):
        for field, value in (("schema", "unsupported"), ("source_sha256", "0" * 64),
                             ("bindings", list(reversed(self.saved["bindings"]))),
                             ("bindings", [dict(self.saved["bindings"][0], weight="wrong_weight")])):
            with self.subTest(field=field, value=value):
                saved = copy.deepcopy(self.saved)
                saved[field] = value
                first = next(iter(dict(self.model.named_parameters())))
                saved["state_dict"][first].add_(.5)
                self.assertRejectedWithoutMutation(self.model, saved)

    def test_missing_and_extra_tensor_names_never_mutate_state(self):
        for mutation in ("missing", "extra", "no_state"):
            with self.subTest(mutation=mutation):
                saved = copy.deepcopy(self.saved)
                if mutation == "missing":
                    saved["state_dict"].pop(next(iter(saved["state_dict"])))
                elif mutation == "extra":
                    saved["state_dict"]["graph.unmapped"] = torch.ones(1)
                else:
                    del saved["state_dict"]
                self.assertRejectedWithoutMutation(self.model, saved)

    def test_invalid_late_parameter_never_partially_applies_earlier_valid_parameters(self):
        names = list(dict(self.model.named_parameters()))
        for kind in ("dtype", "shape", "nan", "inf", "negative_inf", "not_tensor"):
            with self.subTest(kind=kind):
                saved = copy.deepcopy(self.saved)
                state = saved["state_dict"]
                state[names[0]].add_(.5)
                value = state.pop(names[-1])
                if kind == "dtype":
                    value = value.double()
                elif kind == "shape":
                    value = value.reshape(-1, 1)
                elif kind == "not_tensor":
                    value = value.tolist()
                else:
                    value.flatten()[0] = {"nan": float("nan"), "inf": float("inf"),
                                          "negative_inf": -float("inf")}[kind]
                state[names[-1]] = value
                self.assertRejectedWithoutMutation(self.model, saved)

    def test_changed_float_and_integer_constants_never_partially_apply_state(self):
        for floating in (True, False):
            with self.subTest(floating=floating):
                saved = copy.deepcopy(self.saved)
                state = saved["state_dict"]
                first = next(iter(dict(self.model.named_parameters())))
                state[first].add_(.5)
                name = next(name for name, value in self.model.named_buffers()
                            if value.is_floating_point() == floating)
                value = state.pop(name)
                value.add_(1)
                state[name] = value
                self.assertRejectedWithoutMutation(self.model, saved)

    def test_aliased_buffer_checkpoint_is_rejected_before_any_live_mutation(self):
        model = make_recovered(self.directory, with_alias=True)
        names = set(dict(model.graph.named_buffers()))
        all_names = set(dict(model.graph.named_buffers(remove_duplicate=False)))
        aliases = sorted(all_names - names)
        self.assertTrue(aliases, "fixture must include a state_dict buffer hidden by deduplication")
        path = model.save_checkpoint(self.directory / "aliased.pt")
        saved = torch.load(path, map_location="cpu", weights_only=True)
        state = saved["state_dict"]
        first = next(iter(dict(model.named_parameters())))
        state[first].add_(.5)
        alias = "graph." + aliases[0]
        value = state.pop(alias)
        value.add_(1)
        state[alias] = value
        self.assertRejectedWithoutMutation(model, saved)

    def test_unsafe_pickle_global_is_rejected_without_execution_or_state_changes(self):
        self.assertRejectedWithoutMutation(self.model, UnsafePicklePayload(), pickle.UnpicklingError)

    def test_invalid_model_save_preserves_existing_checkpoint_bytes(self):
        before = self.path.read_bytes()
        buffer = next(self.model.graph.buffers())
        buffer.add_(1)
        with self.assertRaises(ValueError):
            self.model.save_checkpoint(self.path)
        self.assertEqual(self.path.read_bytes(), before)


class ExportTests(RecoveryTestCase):
    def setUp(self):
        super().setUp()
        self.model = make_recovered(self.directory)

    def test_unchanged_synthetic_export_is_byte_identical(self):
        path = self.model.export_onnx(self.directory / "unchanged.onnx")
        self.assertEqual(path.read_bytes(), (self.directory / "synthetic.onnx").read_bytes())

    def test_changed_export_updates_only_exact_trainable_initializers_and_keeps_graph(self):
        before = self.model.original.SerializeToString()
        originals = {item.name: item.SerializeToString() for item in self.model.original.graph.initializer}
        expected = {}
        with torch.no_grad():
            for index, binding in enumerate(self.model.bindings):
                module = self.model.graph.get_submodule(binding.module)
                module.weight.add_((index + 1) / 8)
                module.bias.sub_((index + 1) / 16)
                expected[binding.weight] = module.weight.detach().numpy().copy()
                expected[binding.bias] = module.bias.detach().numpy().copy()
        exported = onnx.load_model(self.model.export_onnx(self.directory / "changed.onnx"))
        onnx.checker.check_model(exported)
        self.assertEqual(graph_without_initializers(exported), graph_without_initializers(self.model.original))
        self.assertEqual([item.name for item in exported.graph.initializer], list(originals))
        changed = set()
        for tensor in exported.graph.initializer:
            if tensor.SerializeToString() != originals[tensor.name]:
                changed.add(tensor.name)
            if tensor.name in expected:
                np.testing.assert_array_equal(numpy_helper.to_array(tensor), expected[tensor.name])
            else:
                self.assertEqual(tensor.SerializeToString(), originals[tensor.name])
        self.assertEqual(changed, set(expected))
        self.assertEqual(self.model.original.SerializeToString(), before)
        self.assertEqual((self.directory / "synthetic.onnx").read_bytes(), before)

    def test_changed_initializer_preserves_its_metadata(self):
        binding = self.model.bindings[0]
        with torch.no_grad():
            self.model.graph.get_submodule(binding.module).weight.add_(.25)
        exported = onnx.load_model(self.model.export_onnx(self.directory / "metadata.onnx"))
        original = next(item for item in self.model.original.graph.initializer if item.name == binding.weight)
        actual = next(item for item in exported.graph.initializer if item.name == binding.weight)
        self.assertTrue(original.doc_string)
        self.assertEqual(actual.doc_string, original.doc_string)

    def test_invalid_state_export_preserves_existing_destination_and_source(self):
        path = self.model.export_onnx(self.directory / "preserved.onnx")
        before = path.read_bytes()
        source = self.model.original.SerializeToString()
        next(self.model.graph.buffers()).add_(1)
        with self.assertRaises(ValueError):
            self.model.export_onnx(path)
        self.assertEqual(path.read_bytes(), before)
        self.assertEqual(self.model.original.SerializeToString(), source)


class ShippedModelIntegrationTests(RecoveryTestCase):
    @classmethod
    def setUpClass(cls):
        cls.model = recovery.RecoveredImpact()
        cls.original_state = cloned_state(cls.model)

    def setUp(self):
        super().setUp()
        self.model.load_state_dict(self.original_state, strict=True)
        self.model.eval()
        self.addCleanup(self.model.load_state_dict, self.original_state, strict=True)

    def test_all_128_convolutions_match_exact_named_initializer_values(self):
        self.assertEqual(recovery.file_hash(recovery.MODEL_PATH), recovery.MODEL_SHA256)
        nodes = {node.output[0]: node for node in self.model.original.graph.node if node.op_type == "Conv"}
        arrays = {item.name: numpy_helper.to_array(item) for item in self.model.original.graph.initializer}
        self.assertEqual(len(nodes), 128)
        self.assertEqual(len(self.model.bindings), 128)
        self.assertEqual(len(list(self.model.parameters())), 256)
        self.assertEqual(sum(value.numel() for value in self.model.parameters() if value.requires_grad), 4145860)
        self.assertEqual({binding.output for binding in self.model.bindings}, set(nodes))
        mapped = set()
        for binding in self.model.bindings:
            node = nodes[binding.output]
            self.assertEqual((binding.weight, binding.bias), tuple(node.input[1:]))
            module = self.model.graph.get_submodule(binding.module)
            for suffix, initializer in (("weight", binding.weight), ("bias", binding.bias)):
                with self.subTest(module=binding.module, suffix=suffix):
                    parameter = getattr(module, suffix)
                    self.assertTrue(parameter.requires_grad)
                    np.testing.assert_array_equal(parameter.detach().numpy(), arrays[initializer])
                    mapped.add(f"graph.{binding.module}.{suffix}")
        self.assertEqual(mapped, set(dict(self.model.named_parameters())))
        self.assertTrue(all(not value.requires_grad for value in self.model.buffers()))

    def test_shipped_fixed_buffers_cover_every_state_dict_buffer_path(self):
        all_buffers = dict(self.model.graph.named_buffers(remove_duplicate=False))
        state_names = set(self.model.state_dict()) - set(dict(self.model.named_parameters()))
        self.assertEqual(state_names, {"graph." + name for name in all_buffers})
        self.assertEqual(set(self.model._fixed_buffers), set(all_buffers))
        for name, value in all_buffers.items():
            self.assertTrue(torch.equal(self.model._fixed_buffers[name], value), name)

    def test_shipped_checkpoint_roundtrip_and_unchanged_export_identity(self):
        source_hash = recovery.file_hash(recovery.MODEL_PATH)
        checkpoint = self.model.save_checkpoint(self.directory / "shipped.pt")
        with torch.no_grad():
            next(self.model.parameters()).add_(.25)
        self.model.load_checkpoint(checkpoint)
        self.assertStateEqual(self.original_state, self.model)
        exported = self.model.export_onnx(self.directory / "shipped-unchanged.onnx")
        self.assertEqual(recovery.file_hash(exported), source_hash)
        self.assertEqual(exported.read_bytes(), recovery.MODEL_PATH.read_bytes())
        self.assertEqual(recovery.file_hash(recovery.MODEL_PATH), source_hash)

    def test_shipped_changed_head_export_updates_exactly_weight_and_bias(self):
        binding = next(binding for binding in self.model.bindings if binding.module == "models/0/head/Conv")
        module = self.model.graph.get_submodule(binding.module)
        source = self.model.original.SerializeToString()
        with torch.no_grad():
            module.weight.add_(.125)
            module.bias.sub_(.25)
        exported = onnx.load_model(self.model.export_onnx(self.directory / "shipped-head.onnx"))
        originals = {item.name: item.SerializeToString() for item in self.model.original.graph.initializer}
        changed = {item.name for item in exported.graph.initializer
                   if item.SerializeToString() != originals[item.name]}
        self.assertEqual(changed, {binding.weight, binding.bias})
        arrays = {item.name: numpy_helper.to_array(item) for item in exported.graph.initializer}
        np.testing.assert_array_equal(arrays[binding.weight], module.weight.detach().numpy())
        np.testing.assert_array_equal(arrays[binding.bias], module.bias.detach().numpy())
        self.assertEqual(graph_without_initializers(exported), graph_without_initializers(self.model.original))
        self.assertEqual(self.model.original.SerializeToString(), source)
        self.assertEqual(recovery.MODEL_PATH.read_bytes(), source)


if __name__ == "__main__":
    unittest.main()
