#include <jni.h>
#include <android/log.h>
#include <cerrno>
#include <cmath>
#include <cstring>
#include <fcntl.h>
#include <linux/videodev2.h>
#include <sstream>
#include <string>
#include <sys/ioctl.h>
#include <unistd.h>

#define LOG_TAG "RadioFMX-Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr const char* kCandidates[] = {
    "/dev/radio0",
    "/dev/radio1",
    "/dev/fm",
    "/dev/fmradio",
    "/dev/radio",
};

int fdFromHandle(jlong handle) {
    if (handle <= 0) return -1;
    return static_cast<int>(handle - 1);
}

jlong handleFromFd(int fd) {
    return fd < 0 ? 0 : static_cast<jlong>(fd + 1);
}

bool queryTuner(int fd, v4l2_tuner* tuner) {
    std::memset(tuner, 0, sizeof(*tuner));
    tuner->index = 0;
    return ioctl(fd, VIDIOC_G_TUNER, tuner) == 0;
}

double unitHz(const v4l2_tuner& tuner) {
    return (tuner.capability & V4L2_TUNER_CAP_LOW) ? 62.5 : 62500.0;
}

bool isRadioDevice(int fd) {
    v4l2_capability cap{};
    if (ioctl(fd, VIDIOC_QUERYCAP, &cap) != 0) {
        return false;
    }
    const uint32_t caps =
        (cap.capabilities & V4L2_CAP_DEVICE_CAPS) ? cap.device_caps : cap.capabilities;
    return (caps & V4L2_CAP_TUNER) != 0;
}

std::string probeDevices() {
    std::string firstDenied;
    int firstDeniedErrno = 0;

    for (const char* path : kCandidates) {
        errno = 0;
        int fd = open(path, O_RDWR | O_NONBLOCK | O_CLOEXEC);
        if (fd < 0) {
            if ((errno == EACCES || errno == EPERM) && firstDenied.empty()) {
                firstDenied = path;
                firstDeniedErrno = errno;
            }
            continue;
        }

        const bool radio = isRadioDevice(fd);
        close(fd);
        if (radio) {
            return std::string("OK|") + path + "|0|V4L2 tuner acessível";
        }
    }

    if (!firstDenied.empty()) {
        std::ostringstream out;
        out << "DENIED|" << firstDenied << "|" << firstDeniedErrno
            << "|Dispositivo FM existe, mas kernel/SELinux bloqueou o APK";
        return out.str();
    }

    return "MISSING||0|Nenhum dispositivo V4L2 FM conhecido ficou acessível";
}

jstring toJString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

}

extern "C" JNIEXPORT jstring JNICALL
Java_com_reyfxck_radiofmx_fm_nativebridge_NativeFmBridge_nativeProbe(
    JNIEnv* env,
    jobject
) {
    const std::string result = probeDevices();
    LOGI("probe: %s", result.c_str());
    return toJString(env, result);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_reyfxck_radiofmx_fm_nativebridge_NativeFmBridge_nativeOpen(
    JNIEnv* env,
    jobject,
    jstring jPath
) {
    const char* path = env->GetStringUTFChars(jPath, nullptr);
    int fd = open(path, O_RDWR | O_NONBLOCK | O_CLOEXEC);
    const int openErrno = errno;
    env->ReleaseStringUTFChars(jPath, path);

    if (fd < 0) {
        LOGE("open failed errno=%d (%s)", openErrno, strerror(openErrno));
        return 0;
    }

    if (!isRadioDevice(fd)) {
        LOGE("opened node is not a V4L2 tuner");
        close(fd);
        return 0;
    }

    return handleFromFd(fd);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reyfxck_radiofmx_fm_nativebridge_NativeFmBridge_nativeClose(
    JNIEnv*,
    jobject,
    jlong handle
) {
    const int fd = fdFromHandle(handle);
    if (fd >= 0) close(fd);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reyfxck_radiofmx_fm_nativebridge_NativeFmBridge_nativeTune(
    JNIEnv*,
    jobject,
    jlong handle,
    jfloat mhz
) {
    const int fd = fdFromHandle(handle);
    if (fd < 0) return JNI_FALSE;

    v4l2_tuner tuner{};
    if (!queryTuner(fd, &tuner)) return JNI_FALSE;

    v4l2_frequency frequency{};
    frequency.tuner = 0;
    frequency.type = V4L2_TUNER_RADIO;
    frequency.frequency = static_cast<__u32>(
        std::llround((static_cast<double>(mhz) * 1000000.0) / unitHz(tuner))
    );

    const int rc = ioctl(fd, VIDIOC_S_FREQUENCY, &frequency);
    if (rc != 0) {
        LOGE("VIDIOC_S_FREQUENCY failed errno=%d (%s)", errno, strerror(errno));
    }
    return rc == 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_reyfxck_radiofmx_fm_nativebridge_NativeFmBridge_nativeGetFrequency(
    JNIEnv*,
    jobject,
    jlong handle
) {
    const int fd = fdFromHandle(handle);
    if (fd < 0) return -1.0f;

    v4l2_tuner tuner{};
    if (!queryTuner(fd, &tuner)) return -1.0f;

    v4l2_frequency frequency{};
    frequency.tuner = 0;
    frequency.type = V4L2_TUNER_RADIO;
    if (ioctl(fd, VIDIOC_G_FREQUENCY, &frequency) != 0) {
        return -1.0f;
    }

    return static_cast<jfloat>(
        (static_cast<double>(frequency.frequency) * unitHz(tuner)) / 1000000.0
    );
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_reyfxck_radiofmx_fm_nativebridge_NativeFmBridge_nativeSeek(
    JNIEnv*,
    jobject,
    jlong handle,
    jboolean up
) {
    const int fd = fdFromHandle(handle);
    if (fd < 0) return -1.0f;

    v4l2_hw_freq_seek seek{};
    seek.tuner = 0;
    seek.type = V4L2_TUNER_RADIO;
    seek.seek_upward = up ? 1 : 0;
    seek.wrap_around = 1;

    if (ioctl(fd, VIDIOC_S_HW_FREQ_SEEK, &seek) != 0) {
        LOGE("VIDIOC_S_HW_FREQ_SEEK failed errno=%d (%s)", errno, strerror(errno));
        return -1.0f;
    }

    v4l2_tuner tuner{};
    if (!queryTuner(fd, &tuner)) return -1.0f;

    v4l2_frequency frequency{};
    frequency.tuner = 0;
    frequency.type = V4L2_TUNER_RADIO;
    if (ioctl(fd, VIDIOC_G_FREQUENCY, &frequency) != 0) {
        return -1.0f;
    }

    return static_cast<jfloat>(
        (static_cast<double>(frequency.frequency) * unitHz(tuner)) / 1000000.0
    );
}

extern "C" JNIEXPORT jint JNICALL
Java_com_reyfxck_radiofmx_fm_nativebridge_NativeFmBridge_nativeGetSignal(
    JNIEnv*,
    jobject,
    jlong handle
) {
    const int fd = fdFromHandle(handle);
    if (fd < 0) return -1;

    v4l2_tuner tuner{};
    if (!queryTuner(fd, &tuner)) return -1;
    return static_cast<jint>(tuner.signal);
}
