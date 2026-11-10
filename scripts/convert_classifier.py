"""Export the pinned timm EfficientNet-B0 checkpoint as built-in-ops FP32 TFLite.

Windows-compatible conversion: reproduce the eval graph with TensorFlow ops,
preserving PyTorch's symmetric convolution padding. No retraining/quantization.
Install torch (CPU), timm==1.0.22, tensorflow==2.15.1, numpy==1.26.4, safetensors.
Input: model-work/model.safetensors and revision.txt (from the upstream repo).
"""
import json
import hashlib
from pathlib import Path
import numpy as np
import torch
import timm
import tensorflow as tf
from safetensors.torch import load_file

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / 'model-work'
DEST = ROOT / 'app/src/main/assets/models'
model = timm.create_model('efficientnet_b0', pretrained=False, num_classes=3).eval()
model.load_state_dict(load_file(str(WORK / 'model.safetensors')))

def array(t):
    return t.detach().cpu().numpy().astype(np.float32)

def conv(x, layer):
    ph, pw = layer.padding
    if ph or pw:
        x = tf.pad(x, [[0, 0], [ph, ph], [pw, pw], [0, 0]])
    weight = array(layer.weight)
    if layer.groups == layer.in_channels and layer.groups > 1:
        weight = weight.transpose(2, 3, 0, 1)
        x = tf.nn.depthwise_conv2d(x, tf.constant(weight), [1, *layer.stride, 1], 'VALID')
    else:
        assert layer.groups == 1
        x = tf.nn.conv2d(x, tf.constant(weight.transpose(2, 3, 1, 0)), [1, *layer.stride, 1], 'VALID')
    if layer.bias is not None:
        x = tf.nn.bias_add(x, tf.constant(array(layer.bias)))
    return x

def bn(x, layer):
    x = tf.nn.batch_normalization(x, tf.constant(array(layer.running_mean)), tf.constant(array(layer.running_var)),
                                 tf.constant(array(layer.bias)), tf.constant(array(layer.weight)), layer.eps)
    if hasattr(layer, 'act'):
        if isinstance(layer.act, torch.nn.SiLU):
            x = tf.nn.silu(x)
        else:
            assert isinstance(layer.act, torch.nn.Identity), type(layer.act)
    return x

def se(x, layer):
    y = tf.reduce_mean(x, [1, 2], keepdims=True)
    y = tf.nn.silu(conv(y, layer.conv_reduce))
    return x * tf.nn.sigmoid(conv(y, layer.conv_expand))

class Export(tf.Module):
    @tf.function(input_signature=[tf.TensorSpec([1, 224, 224, 3], tf.float32, name='normalized_rgb')])
    def __call__(self, x):
        x = bn(conv(x, model.conv_stem), model.bn1)
        for stage in model.blocks:
            for block in stage:
                residual = x
                if hasattr(block, 'conv_pwl'):
                    x = bn(conv(x, block.conv_pw), block.bn1)
                    x = bn(conv(x, block.conv_dw), block.bn2)
                    x = se(x, block.se)
                    x = bn(conv(x, block.conv_pwl), block.bn3)
                else:
                    x = bn(conv(x, block.conv_dw), block.bn1)
                    x = se(x, block.se)
                    x = bn(conv(x, block.conv_pw), block.bn2)
                if block.has_skip:
                    x = x + residual
        x = bn(conv(x, model.conv_head), model.bn2)
        x = tf.reduce_mean(x, [1, 2])
        return tf.matmul(x, tf.constant(array(model.classifier.weight).T)) + tf.constant(array(model.classifier.bias))

module = Export()
converter = tf.lite.TFLiteConverter.from_concrete_functions([module.__call__.get_concrete_function()], module)
converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
converter.optimizations = []
blob = converter.convert()
interpreter = tf.lite.Interpreter(model_content=blob, num_threads=2)
interpreter.allocate_tensors()
inp = interpreter.get_input_details()[0]
out = interpreter.get_output_details()[0]
assert inp['dtype'] == np.float32 and out['dtype'] == np.float32
rng = np.random.default_rng(42)
errors = []
probability_errors = []
for i in range(8):
    x = rng.normal(0, 1, (1, 224, 224, 3)).astype(np.float32) if i < 4 else np.full((1, 224, 224, 3), (i-5)*0.5, np.float32)
    with torch.no_grad():
        expected = model(torch.from_numpy(x.transpose(0, 3, 1, 2).copy())).numpy()
    interpreter.set_tensor(inp['index'], x)
    interpreter.invoke()
    actual = interpreter.get_tensor(out['index'])
    # Fused FP32 kernels accumulate in a different order from PyTorch.
    np.testing.assert_allclose(actual, expected, rtol=3e-4, atol=2e-3)
    def softmax(v):
        p = np.exp(v-v.max(axis=1, keepdims=True)); return p/p.sum(axis=1, keepdims=True)
    probability_error = float(np.max(np.abs(softmax(actual)-softmax(expected))))
    assert probability_error < 3e-4, probability_error
    probability_errors.append(probability_error)
    assert actual.argmax() == expected.argmax()
    errors.append(float(np.max(np.abs(actual - expected))))
DEST.mkdir(parents=True, exist_ok=True)
(DEST / 'image_medium_efficientnet_b0_fp32.tflite').write_bytes(blob)
report = dict(source='https://huggingface.co/Mitchins/image-medium-classifier-efficientnet-b0-v1',
              revision=(WORK/'revision.txt').read_text(encoding='utf-8-sig').strip(),
              labels=['anime','real','rendered'], input=[1,224,224,3], input_dtype='float32', output='3 float32 logits',
              normalization=dict(mean=[0.485,0.456,0.406], std=[0.229,0.224,0.225]),
              sha256=hashlib.sha256(blob).hexdigest(), bytes=len(blob), parity_max_abs_errors=errors, parity_max_probability_errors=probability_errors,
              torch=torch.__version__, timm=timm.__version__, tensorflow=tf.__version__)
(DEST/'classifier_manifest.json').write_text(json.dumps(report, indent=2)+'\n', encoding='utf-8')
print(json.dumps(report, indent=2))
