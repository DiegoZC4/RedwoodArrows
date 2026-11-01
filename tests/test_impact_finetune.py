import sys
from pathlib import Path
import random
import unittest

import numpy as np
import torch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from finetune_impact import anchored_point_loss, crop_example, point_loss, supervision


class FineTuneTest(unittest.TestCase):
    def test_contact_peak_and_uncertainty_mask(self):
        target, valid = supervision((800,600), [[100,200]], [[300,400]])
        self.assertEqual(target[200,100], 1)
        self.assertEqual(valid[400,300], 0)
        self.assertEqual(valid[400,309], 1)
        self.assertEqual(valid[200,100], 1)

    def test_uncertainty_cannot_be_a_negative(self):
        output = torch.zeros(1,2,384,384)
        target = torch.zeros(384,384)
        valid = torch.ones_like(target)
        valid[100,100] = 0
        output[0,0,100,100] = .9
        self.assertEqual(float(point_loss(output,target,valid,target)), 0.)

    def test_crop_preserves_correspondence(self):
        target, valid = supervision((800,600), [[300,400]], [])
        rgb = np.repeat(target[:,:,None], 3, axis=2)
        result = crop_example((rgb,target,valid,target,[[300,400]]), random.Random(2))
        image, contact, mask, shaft = result
        self.assertEqual(tuple(image.shape), (1,3,384,384))
        self.assertEqual(image.dtype, torch.float32)
        self.assertTrue(torch.equal(contact,shaft))
        self.assertTrue(torch.all(mask == 1))
        self.assertEqual(torch.argmax(image[0,0]).item(), torch.argmax(contact).item())

    def test_gradient_is_finite(self):
        output = torch.full((1,2,384,384),.1,requires_grad=True)
        target = torch.zeros(384,384)
        target[100,200] = 1
        loss = point_loss(output,target,torch.ones_like(target),torch.zeros_like(target))
        loss.backward()
        self.assertTrue(torch.isfinite(output.grad).all())
        self.assertLess(output.grad[0,0,100,200].item(),0)

    def test_anchor_penalizes_new_background_peaks(self):
        target=torch.zeros(384,384)
        teacher=torch.full_like(target,.05)
        unchanged=torch.stack([teacher,target])[None]
        extra=unchanged.clone()
        extra[0,0,100:110,100:110]=.7
        self.assertEqual(float(anchored_point_loss(unchanged,target,torch.ones_like(target),target,teacher)),0.)
        self.assertGreater(float(anchored_point_loss(extra,target,torch.ones_like(target),target,teacher)),.01)

    def test_anchor_still_ignores_uncertain_contacts(self):
        target=torch.zeros(384,384)
        valid=torch.ones_like(target)
        valid[100:110,100:110]=0
        output=torch.zeros(1,2,384,384)
        output[0,0,100:110,100:110]=.9
        self.assertEqual(float(anchored_point_loss(output,target,valid,target,target)),0.)


if __name__ == "__main__":
    unittest.main()
