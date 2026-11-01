"""Recover the shipped impact detector as a trainable PyTorch graph.

This recovers the exported, batch-normalization-folded ensemble, not the original
training program. It does not recover labels, losses, optimizer state, or running
batch-normalization statistics. Nothing in this module changes the APK assets.
"""

from __future__ import annotations

from collections import Counter
from dataclasses import asdict, dataclass
import hashlib
import importlib.metadata
from pathlib import Path

import numpy as np
import onnx
from onnx import numpy_helper
from onnx2torch import convert
import torch
from torch import nn


ROOT = Path(__file__).resolve().parents[1]
MODEL_PATH = ROOT / "assets/impact/impact-center.onnx"
MODEL_SHA256 = "dd6b558f936e89b427330ea7bcbada2d2149b048befaf732000215d2417451d8"
SCHEMA = "redwood-impact-recovery-v1"
INPUT_SHAPE = (1, 3, 384, 384)
OUTPUT_SHAPE = (1, 2, 384, 384)


def file_hash(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def local_output(path: Path) -> Path:
    path = path.resolve()
    if not any(path.is_relative_to((ROOT / folder).resolve()) for folder in ("build", "private")):
        raise ValueError("Recovery outputs must stay under this repository's build/ or private/ directory")
    path.parent.mkdir(parents=True, exist_ok=True)
    return path


def environment() -> dict:
    return {name: importlib.metadata.version(name) for name in
            ("torch", "torchvision", "onnx", "onnx2torch", "onnxruntime", "numpy", "opencv-python-headless")}


@dataclass(frozen=True)
class ConvBinding:
    module: str
    weight: str
    bias: str
    output: str


def convolution_bindings(proto: onnx.ModelProto, graph: nn.Module) -> list[ConvBinding]:
    """Use ONNX tensor identities, never positional/shape-only weight matching."""
    nodes = {node.output[0]: node for node in proto.graph.node if node.op_type == "Conv"}
    initializers = {item.name: numpy_helper.to_array(item) for item in proto.graph.initializer}
    bindings = []
    bound_outputs = set()
    bound_initializers = set()
    parameter_names = set()
    for name, module in graph.named_modules():
        if not isinstance(module, nn.Conv2d):
            continue
        mapping = getattr(module, "onnx_mapping", None)
        if mapping is None or len(mapping.outputs) != 1 or mapping.outputs[0] not in nodes:
            raise ValueError(f"Missing unambiguous ONNX mapping for convolution {name}")
        output = mapping.outputs[0]
        node = nodes[output]
        if output in bound_outputs or len(node.input) != 3 or module.bias is None:
            raise ValueError("Unsupported convolution mapping or missing bias")
        attrs = {a.name: onnx.helper.get_attribute_value(a) for a in node.attribute}
        pads = tuple(attrs.get("pads", [0, 0, 0, 0]))
        if (len(pads) != 4 or pads[:2] != pads[2:] or tuple(module.padding) != pads[:2]
                or tuple(module.stride) != tuple(attrs.get("strides", [1, 1]))
                or tuple(module.dilation) != tuple(attrs.get("dilations", [1, 1]))
                or module.groups != attrs.get("group", 1)
                or attrs.get("auto_pad", b"NOTSET") not in (b"NOTSET", b"")):
            raise ValueError(f"Convolution geometry mismatch: {name}")
        for suffix, initializer in zip(("weight", "bias"), node.input[1:]):
            tensor = getattr(module, suffix).detach().cpu().numpy()
            original = initializers.get(initializer)
            if (original is None or tensor.dtype != np.float32 or original.dtype != np.float32
                    or not np.array_equal(tensor, original) or initializer in bound_initializers):
                raise ValueError(f"Convolution weights differ or are shared unexpectedly: {name}.{suffix}")
            bound_initializers.add(initializer)
            parameter_names.add(f"{name}.{suffix}")
        bound_outputs.add(output)
        bindings.append(ConvBinding(name, node.input[1], node.input[2], output))
    if bound_outputs != set(nodes) or parameter_names != set(dict(graph.named_parameters())):
        raise ValueError("Not all trainable parameters map to exported convolutions")
    return bindings


class RecoveredImpact(nn.Module):
    def __init__(self, model_path: Path = MODEL_PATH):
        super().__init__()
        self.source_hash = file_hash(model_path)
        if self.source_hash != MODEL_SHA256:
            raise ValueError("Unsupported source model; this recovery is pinned to the shipped detector")
        self.original = onnx.load_model_from_string(model_path.read_bytes())
        onnx.checker.check_model(self.original)
        if self.original.training_info:
            raise ValueError("Unexpected training graph in the source export")
        self.graph = convert(self.original, save_input_names=True, attach_onnx_mapping=True)
        self.bindings = convolution_bindings(self.original, self.graph)
        self._fixed_buffers = {name: value.detach().clone()
                               for name, value in self.graph.named_buffers(remove_duplicate=False)}
        self._parameter_shapes = {name: tuple(value.shape)
                                  for name, value in self.graph.named_parameters(remove_duplicate=False)}
        self.eval()

    def forward(self, rgb: torch.Tensor) -> torch.Tensor:
        if tuple(rgb.shape) != INPUT_SHAPE or rgb.dtype != torch.float32:
            raise ValueError(f"Expected RGB float32 {INPUT_SHAPE}; the recovered export has fixed batch size one")
        return self.graph(rgb)

    def validate_state(self) -> None:
        parameters = dict(self.graph.named_parameters(remove_duplicate=False))
        if parameters.keys() != self._parameter_shapes.keys():
            raise ValueError("Trainable parameter names changed")
        for name, parameter in parameters.items():
            if (parameter.dtype != torch.float32 or tuple(parameter.shape) != self._parameter_shapes[name]
                    or not torch.isfinite(parameter).all()):
                raise ValueError(f"Invalid float32 model parameter: {name}")
        buffers = dict(self.graph.named_buffers(remove_duplicate=False))
        if buffers.keys() != self._fixed_buffers.keys():
            raise ValueError("Frozen normalization/shape constant names changed")
        for name, buffer in buffers.items():
            fixed = self._fixed_buffers[name]
            if (buffer.dtype != fixed.dtype or buffer.shape != fixed.shape
                    or not torch.equal(buffer.detach().cpu(), fixed.cpu())):
                raise ValueError(f"Frozen normalization/shape constant changed: {name}")

    def save_checkpoint(self, path: Path, purpose: str = "recovered-original") -> Path:
        self.validate_state()
        path = local_output(path)
        torch.save({"schema": SCHEMA, "source_sha256": self.source_hash, "purpose": str(purpose),
                    "environment": environment(), "bindings": [asdict(item) for item in self.bindings],
                    "state_dict": {name: value.detach().cpu().clone() for name, value in self.state_dict().items()}}, path)
        return path

    def load_checkpoint(self, path: Path) -> None:
        saved = torch.load(path, map_location="cpu", weights_only=True)
        if (saved.get("schema") != SCHEMA or saved.get("source_sha256") != self.source_hash
                or saved.get("bindings") != [asdict(item) for item in self.bindings]):
            raise ValueError("Checkpoint does not belong to this recovered model")
        expected = self.state_dict()
        actual = saved.get("state_dict", {})
        if actual.keys() != expected.keys():
            raise ValueError("Checkpoint tensor names differ from the recovered graph")
        # Validate the complete state before modifying the live module.
        for name, value in actual.items():
            if (not isinstance(value, torch.Tensor) or value.dtype != expected[name].dtype
                    or value.shape != expected[name].shape or not torch.isfinite(value).all()):
                raise ValueError(f"Invalid checkpoint tensor: {name}")
            fixed = self._fixed_buffers.get(name.removeprefix("graph."))
            if fixed is not None and not torch.equal(value, fixed.cpu()):
                raise ValueError(f"Checkpoint changes a frozen constant: {name}")
        self.load_state_dict(actual, strict=True)
        self.validate_state()

    def export_onnx(self, path: Path) -> Path:
        """Keep the original ops/tiling contract; replace only mapped Conv tensors."""
        self.validate_state()
        path = local_output(path)
        exported = onnx.ModelProto()
        exported.CopyFrom(self.original)
        tensors = {item.name: item for item in exported.graph.initializer}
        for binding in self.bindings:
            module = self.graph.get_submodule(binding.module)
            for suffix, target in (("weight", binding.weight), ("bias", binding.bias)):
                array = getattr(module, suffix).detach().cpu().numpy().copy()
                if not np.array_equal(numpy_helper.to_array(tensors[target]), array):
                    # Retain TensorProto provenance and unknown metadata fields.
                    tensors[target].ClearField("float_data")
                    tensors[target].raw_data = numpy_helper.from_array(array).raw_data
        onnx.checker.check_model(exported)
        onnx.save_model(exported, str(path))
        return path

    def describe(self) -> dict:
        return {"schema": SCHEMA, "source_sha256": self.source_hash,
                "operators": dict(Counter(n.op_type for n in self.original.graph.node)),
                "convolutions": len(self.bindings), "parameter_tensors": len(list(self.parameters())),
                "trainable_parameters": sum(p.numel() for p in self.parameters() if p.requires_grad),
                "input": list(INPUT_SHAPE), "output": list(OUTPUT_SHAPE),
                "batch_norm": "folded into convolution weights; original running statistics unavailable",
                "training_recipe_recovered": False, "environment": environment()}


class TorchNetAdapter:
    """Reuse evaluate_impact's tested OpenCV-style tiling without duplicating it."""
    def __init__(self, model: RecoveredImpact):
        self.model = model
        self.blob = None

    def setInput(self, blob: np.ndarray) -> None:
        self.blob = np.ascontiguousarray(blob, dtype=np.float32)

    def forward(self) -> np.ndarray:
        if self.blob is None:
            raise ValueError("setInput must be called before forward")
        with torch.no_grad():
            return self.model(torch.from_numpy(self.blob)).detach().cpu().numpy().copy()
