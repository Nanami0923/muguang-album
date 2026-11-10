# 离线模型与第三方组件

本地模型随 APK 捆绑；0.3.0 增加用户自选的视觉 API，默认关闭。启用后待 AI 图片的压缩副本会发往用户配置的服务；没有人物身份识别。

## 0.3.0 新增

| 功能 | 来源与版本 | 本项目采用方式 |
| --- | --- | --- |
| 动漫人体 | [DeepGHS](https://huggingface.co/deepghs/anime_person_detection)，revision `e39c744c22432ad01f91dd254fe2b02c8d878b8c`，模型卡 MIT | `person_detect_v1.1_n/model.onnx`，FP32，640 方形补边，RGB/255、NCHW；YOLOv8 单类输出解码及 NMS |
| 动漫脸 | [DeepGHS](https://huggingface.co/deepghs/anime_face_detection)，revision `784dc4c0bb692351ddcdbe6131a050b17d3025d5`，模型卡 MIT | `face_detect_v1.4_n/model.onnx`，同上，独立脸部阈值 |
| 显著主体 | [U²-Net](https://github.com/xuebinqin/U-2-Net)，Apache-2.0；[rembg 导出的 ONNX](https://github.com/danielgatis/rembg/blob/main/rembg/sessions/u2netp.py) | 使用固定 rembg release 的 U2NETP 导出，320×320、图像最大值归一化及通道标准化；取第一个融合概率图，不重复 sigmoid |
| ONNX 推理 | [ONNX Runtime](https://github.com/microsoft/onnxruntime)，MIT | Android 1.22.0，CPU 单线程；保留原有 LiteRT |

新模型 URL、实际 SHA-256、张量契约及桌面冒烟记录见 assets/models 下三个新增 JSON；`scripts/prepare_v030_models.py` 校验固定哈希并可复现获取。U²-Net 许可随资产保存。

动漫预处理明确使用 640×640、底色 114 的等比例居中补边，与上游 imgutils 默认直接缩放策略不同；解码撤销补边。此策略需观察实际图库效果，不把上游指标当作项目准确率。

| 功能 | 来源 | 项目采用方式 |
| --- | --- | --- |
| 图像媒介分类 | [Mitchins/image-medium-classifier-efficientnet-b0-v1](https://huggingface.co/Mitchins/image-medium-classifier-efficientnet-b0-v1) | 上游 FP32 safetensors → 本地 FP32 TFLite，revision `21d5541d53d05b12b050265e10962db61d38c3e9`；模型卡标注 OpenRAIL |
| 人体目标检测 | [TensorFlow 官方示例](https://github.com/tensorflow/examples/blob/master/lite/examples/object_detection/raspberry_pi/setup.sh) | EfficientDet-Lite0 量化模型，uint8 输入、INT8 量化计算，含 `TFLite_Detection_PostProcess` 后处理；具体文件及 SHA-256 见 detector_manifest.json |
| 人脸位置检测 | [ShiqiYu/libfacedetection](https://github.com/ShiqiYu/libfacedetection) | revision `acf7b254121927e7dced30e233a2e03119f28ea2`，源码和静态模型随应用编译；保留上游 BSD 3-Clause LICENSE 和源码版权头 |
| 通用主体定位 | [OpenCV](https://opencv.org/)、[OpenCV contrib saliency](https://github.com/opencv/opencv_contrib/tree/4.12.0/modules/saliency) | Maven `org.opencv:opencv:4.12.0`，Apache-2.0；Android 核心包不带 contrib saliency Java 接口，因此用 Core/Imgproc 实现同类频谱残差计算，不依赖不存在的 Java 包 |
| Android 模型解释器 | [LiteRT](https://github.com/google-ai-edge/LiteRT) | `com.google.ai.edge.litert:litert:2.2.0`，Apache-2.0；使用可离线运行的 CPU Interpreter，无需 GMS；该 AAR 实际要求 API 24，构建遵守其要求 |

## 分类输入与映射

- 输入：224 × 224 RGB，拉伸到输入尺寸；像素除以 255，再按 mean `[0.485, 0.456, 0.406]`、std `[0.229, 0.224, 0.225]` 标准化。TFLite 输入为 NHWC FP32；转换后的图与上游 NCHW 图保持相同计算含义。
- 输出：`anime / real / rendered` 三个 logits，softmax 后最高概率至少 0.85 且与第二名至少相差 0.20 才接受。`anime/rendered` 合并为 ART，`real` 再交给人体检测区分 REAL_PERSON/REAL_OTHER。
- 这不是经过全部“绘画/几何设计”样例验证的二分类器。写实插画、摄影作品里的画作、几何设计、游戏截图等需通过真实图库核对，允许 UNKNOWN 和手动覆盖。
- 上游模型卡的验证准确率、GPU 推理时长不作为本项目或华为 M5 的测试成绩。

## 转换与校验

`scripts/convert_classifier.py` 在 Windows 上用 TensorFlow 内置运算重建 timm 的推理图，保留 PyTorch 对称 padding、BN 参数、SiLU 和 SE；不重新训练，不量化，不依赖 Linux 专用转换器。脚本会先检查 PyTorch 与 TFLite 输出，再写入最终模型。

原权重放入 `model-work/model.safetensors`，将上游 revision 写入 `model-work/revision.txt`。本次转换环境版本记录在 `app/src/main/assets/models/classifier_manifest.json`。核心依赖为 CPU torch、timm 1.0.22、TensorFlow 2.15.1、NumPy 1.26.4、safetensors。

校验使用 8 组随机/常量输入，8 组最高类别一致；最大 logits 绝对差约 0.00189，最大概率绝对差约 0.000000835。该检查用于验证转换一致性，不代表真实图库准确率。

模型文件、输入输出形状、来源、revision、SHA-256 和逐组误差记录在 `app/src/main/assets/models/*_manifest.json`。人脸源码许可证位于 `app/src/main/cpp/vendor/libfacedetection/LICENSE`。
