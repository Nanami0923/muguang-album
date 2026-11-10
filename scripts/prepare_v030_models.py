"""Fetch pinned inference assets and record their actual ONNX contracts."""
import hashlib
import json
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / 'app/src/main/assets/models'
MODELS = {
    'anime_person_v1_1_n.onnx': ('https://huggingface.co/deepghs/anime_person_detection/resolve/e39c744c22432ad01f91dd254fe2b02c8d878b8c/person_detect_v1.1_n/model.onnx', 'MIT'),
    'anime_face_v1_4_n.onnx': ('https://huggingface.co/deepghs/anime_face_detection/resolve/784dc4c0bb692351ddcdbe6131a050b17d3025d5/face_detect_v1.4_n/model.onnx', 'MIT'),
    'u2netp.onnx': ('https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2netp.onnx', 'Apache-2.0 / upstream U-2-Net'),
}
EXPECTED = {
    'anime_person_v1_1_n.onnx': '6fcdfc6af18b3a26aca62aad0e00faffbeea23413abf868808d2d98d399999c9',
    'anime_face_v1_4_n.onnx': 'fd860b650a4377046842c3cd80d01b0b408bdfbdb4acee5759630f82c6ef04a9',
    'u2netp.onnx': '309c8469258dda742793dce0ebea8e6dd393174f89934733ecc8b14c76f4ddd8',
}

def main():
    import numpy as np
    import onnxruntime as ort
    DEST.mkdir(parents=True, exist_ok=True)
    for name, (url, license_name) in MODELS.items():
        path = DEST / name
        if not path.exists():
            print('Downloading', name, flush=True)
            with urllib.request.urlopen(url, timeout=120) as response:
                data = response.read()
            path.write_bytes(data)
        assert hashlib.sha256(path.read_bytes()).hexdigest() == EXPECTED[name], f'Checksum mismatch: {name}'
        options = ort.SessionOptions()
        options.intra_op_num_threads = 1
        session = ort.InferenceSession(str(path), sess_options=options, providers=['CPUExecutionProvider'])
        inputs = [dict(name=x.name, shape=x.shape, type=x.type) for x in session.get_inputs()]
        outputs = [dict(name=x.name, shape=x.shape, type=x.type) for x in session.get_outputs()]
        shape = [x if isinstance(x, int) else (1 if i == 0 else 640) for i, x in enumerate(session.get_inputs()[0].shape)]
        sample = np.zeros(shape, dtype=np.float32)
        result = session.run(None, {inputs[0]['name']: sample})
        assert all(np.isfinite(x).all() for x in result)
        report = dict(source=url, license=license_name, sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                      bytes=path.stat().st_size, inputs=inputs, outputs=outputs,
                      metadata=session.get_modelmeta().custom_metadata_map, smoke_output_shapes=[list(x.shape) for x in result],
                      onnxruntime=ort.__version__, validation='CPU zero-input smoke test; not an accuracy evaluation')
        path.with_suffix('.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
        print(name, inputs, outputs, flush=True)
    metadata = {
        'anime_person_labels.json': 'https://huggingface.co/deepghs/anime_person_detection/resolve/e39c744c22432ad01f91dd254fe2b02c8d878b8c/person_detect_v1.1_n/labels.json',
        'anime_face_labels.json': 'https://huggingface.co/deepghs/anime_face_detection/resolve/784dc4c0bb692351ddcdbe6131a050b17d3025d5/face_detect_v1.4_n/labels.json',
        'U2NET_LICENSE.txt': 'https://raw.githubusercontent.com/xuebinqin/U-2-Net/master/LICENSE',
    }
    for name,url in metadata.items():
        with urllib.request.urlopen(url,timeout=30) as response:
            (DEST/name).write_bytes(response.read())

if __name__ == '__main__':
    main()
