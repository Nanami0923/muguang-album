#include <jni.h>
#include <vector>
#include "facedetectcnn.h"

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_muguang_album_analysis_NativeFaces_detect(JNIEnv* env, jobject, jbyteArray bytes, jint width, jint height) {
    if(width <= 0 || height <= 0 || width > 1024 || height > 1024 || env->GetArrayLength(bytes) != width*height*3) return env->NewFloatArray(0);
    std::vector<unsigned char> image(width*height*3);
    env->GetByteArrayRegion(bytes, 0, image.size(), reinterpret_cast<jbyte*>(image.data()));
    std::vector<unsigned char> buffer(FACEDETECTION_RESULT_BUFFER_SIZE);
    int* result = facedetect_cnn(buffer.data(), image.data(), width, height, width*3);
    std::vector<float> boxes;
    if(result) for(int i=0; i<result[0]; ++i) {
        short* p = reinterpret_cast<short*>(buffer.data()+4) + FACEDETECTION_RESULT_STRIDE_SHORTS*i;
        if(p[0] < 70) continue;
        boxes.insert(boxes.end(), {p[1]/float(width),p[2]/float(height),p[3]/float(width),p[4]/float(height),p[0]/100.f});
    }
    jfloatArray output = env->NewFloatArray(boxes.size());
    if(!boxes.empty()) env->SetFloatArrayRegion(output, 0, boxes.size(), boxes.data());
    return output;
}
