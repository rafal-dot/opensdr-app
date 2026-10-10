#include "diagnostic_capture.h"
#include <jni.h>
#include <array>
#include <algorithm>

extern "C" JNIEXPORT jboolean JNICALL
Java_org_opensdr_sdrcore_DiagnosticCapture_nativeStart(JNIEnv*, jobject) {
    try {
        return diagnostic_capture().start(24 * 1024 * 1024);
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_opensdr_sdrcore_DiagnosticCapture_nativeStop(JNIEnv*, jobject) {
    diagnostic_capture().stop();
}

extern "C" JNIEXPORT jint JNICALL
Java_org_opensdr_sdrcore_DiagnosticCapture_nativeStatus(JNIEnv*, jobject) {
    return diagnostic_capture().status();
}

extern "C" JNIEXPORT jint JNICALL
Java_org_opensdr_sdrcore_DiagnosticCapture_nativeDrain(JNIEnv* env, jobject, jobject buffer) {
    auto* data = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    const auto capacity = env->GetDirectBufferCapacity(buffer);
    if (data == nullptr || capacity <= 0) return 0;
    return static_cast<jint>(diagnostic_capture().drain(data, std::min<jlong>(capacity, 262144)));
}

extern "C" JNIEXPORT void JNICALL
Java_org_opensdr_sdrcore_DiagnosticCapture_nativeEvent(JNIEnv* env, jobject, jbyteArray data) {
    if (diagnostic_capture().status() != DiagnosticCapture::active || data == nullptr) return;
    const auto size = env->GetArrayLength(data);
    if (size > 65536) { diagnostic_capture().stop(); return; }
    auto* bytes = env->GetByteArrayElements(data, nullptr);
    if (bytes == nullptr) return;
    diagnostic_capture().offer(DiagnosticCapture::event, bytes, size, diagnostic_time_ns());
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_org_opensdr_sdrcore_DiagnosticCapture_nativeAudio(JNIEnv* env, jobject, jshortArray samples,
                                                     jint offset, jint count, jlong queued) {
    if (diagnostic_capture().status() != DiagnosticCapture::active || samples == nullptr) return;
    if (offset < 0 || count < 0 || offset > env->GetArrayLength(samples) - count) return;
    std::array<jshort, 1200> pcm{};
    std::array<uint8_t, 2400> bytes{};
    while (count > 0) {
        const int take = std::min<int>(count, pcm.size());
        env->GetShortArrayRegion(samples, offset, take, pcm.data());
        if (env->ExceptionCheck()) return;
        for (int i = 0; i < take; ++i) {
            bytes[2 * i] = static_cast<uint16_t>(pcm[i]) & 0xff;
            bytes[2 * i + 1] = static_cast<uint16_t>(pcm[i]) >> 8;
        }
        if (!diagnostic_capture().offer(DiagnosticCapture::audio, bytes.data(), take * 2,
                                       diagnostic_time_ns(), std::max<jlong>(0, queued))) return;
        offset += take;
        count -= take;
    }
}
