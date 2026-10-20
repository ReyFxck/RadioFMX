#include <jni.h>
#include <android/log.h>
#include <cerrno>
#include <cmath>
#include <cstring>
#include <fcntl.h>
#include <dlfcn.h>
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

// Keep the Binder NDK dependency optional at load time so RadioFMX still starts
// on Android releases/devices where this API is unavailable.
struct BinderNdkApi {
    void* handle = nullptr;
    using CheckServiceFn = void* (*)(const char*);
    using IsDeclaredFn = bool (*)(const char*);
    using ToJavaBinderFn = jobject (*)(JNIEnv*, void*);
    using FromJavaBinderFn = void* (*)(JNIEnv*, jobject);
    using DecStrongFn = void (*)(void*);
    using MarkVintfFn = void (*)(void*);

    CheckServiceFn checkService = nullptr;
    IsDeclaredFn isDeclared = nullptr;
    ToJavaBinderFn toJavaBinder = nullptr;
    FromJavaBinderFn fromJavaBinder = nullptr;
    DecStrongFn decStrong = nullptr;
    MarkVintfFn markVintf = nullptr;

    bool readyForLookup() const {
        return handle && checkService && toJavaBinder && decStrong;
    }

    bool readyForMarking() const {
        return handle && fromJavaBinder && decStrong && markVintf;
    }
};

BinderNdkApi& binderNdk() {
    static BinderNdkApi api = [] {
        BinderNdkApi result;
        result.handle = dlopen("libbinder_ndk.so", RTLD_NOW | RTLD_LOCAL);
        if (!result.handle) {
            LOGE("dlopen(libbinder_ndk.so) failed: %s", dlerror());
            return result;
        }

        result.checkService = reinterpret_cast<BinderNdkApi::CheckServiceFn>(
            dlsym(result.handle, "AServiceManager_checkService"));
        result.isDeclared = reinterpret_cast<BinderNdkApi::IsDeclaredFn>(
            dlsym(result.handle, "AServiceManager_isDeclared"));
        result.toJavaBinder = reinterpret_cast<BinderNdkApi::ToJavaBinderFn>(
            dlsym(result.handle, "AIBinder_toJavaBinder"));
        result.fromJavaBinder = reinterpret_cast<BinderNdkApi::FromJavaBinderFn>(
            dlsym(result.handle, "AIBinder_fromJavaBinder"));
        result.decStrong = reinterpret_cast<BinderNdkApi::DecStrongFn>(
            dlsym(result.handle, "AIBinder_decStrong"));
        result.markVintf = reinterpret_cast<BinderNdkApi::MarkVintfFn>(
            dlsym(result.handle, "AIBinder_markVintfStability"));

        return result;
    }();
    return api;
}

constexpr const char* kQcomFmAidlService =
    "vendor.qti.hardware.fm.IFmHci/default";

std::string probeQcomAidl() {
    BinderNdkApi& api = binderNdk();
    if (!api.readyForLookup()) {
        return "ERROR|libbinder_ndk indisponível ou sem símbolos necessários";
    }

    bool declared = false;
    if (api.isDeclared) {
        declared = api.isDeclared(kQcomFmAidlService);
    }

    void* binder = api.checkService(kQcomFmAidlService);
    if (binder) {
        api.decStrong(binder);
        return std::string("OK|service=") + kQcomFmAidlService +
            "|declared=" + (declared ? "true" : "unknown");
    }

    if (declared) {
        return std::string("BLOCKED|service=") + kQcomFmAidlService +
            "|declarado no VINTF, mas o Binder não ficou acessível ao APK";
    }

    return std::string("MISSING|service=") + kQcomFmAidlService +
        "|AIDL não declarado/acessível; o aparelho pode usar o HAL HIDL legado";
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

extern "C" JNIEXPORT jstring JNICALL
Java_com_reyfxck_radiofmx_fm_nativebridge_NativeFmBridge_nativeQcomAidlProbe(
    JNIEnv* env,
    jobject
) {
    const std::string result = probeQcomAidl();
    LOGI("QCOM AIDL probe: %s", result.c_str());
    return toJString(env, result);
}

std::string throwableText(JNIEnv* env, jthrowable throwable) {
    if (!throwable) return "exceção desconhecida";

    jclass throwableClass = env->FindClass("java/lang/Throwable");
    if (!throwableClass) {
        env->ExceptionClear();
        return "Throwable";
    }
    jmethodID toString = env->GetMethodID(
        throwableClass, "toString", "()Ljava/lang/String;");
    if (!toString) {
        env->ExceptionClear();
        return "Throwable";
    }
    auto text = static_cast<jstring>(env->CallObjectMethod(throwable, toString));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return "Throwable";
    }
    if (!text) return "Throwable";

    const char* utf = env->GetStringUTFChars(text, nullptr);
    if (!utf) return "Throwable";
    std::string result(utf);
    env->ReleaseStringUTFChars(text, utf);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_reyfxck_radiofmx_fm_nativebridge_NativeFmBridge_nativeQcomHidlProbe(
    JNIEnv* env,
    jobject
) {
    constexpr const char* kIface = "vendor.qti.hardware.fm@1.0::IFmHci";

    jclass hwBinder = env->FindClass("android/os/HwBinder");
    if (env->ExceptionCheck() || !hwBinder) {
        jthrowable error = env->ExceptionOccurred();
        env->ExceptionClear();
        return toJString(
            env,
            std::string("BLOCKED|android.os.HwBinder indisponível: ") +
                throwableText(env, error));
    }

    jmethodID getService = env->GetStaticMethodID(
        hwBinder,
        "getService",
        "(Ljava/lang/String;Ljava/lang/String;Z)Landroid/os/IHwBinder;");
    if (env->ExceptionCheck() || !getService) {
        jthrowable error = env->ExceptionOccurred();
        env->ExceptionClear();
        return toJString(
            env,
            std::string("BLOCKED|HwBinder.getService oculto/bloqueado: ") +
                throwableText(env, error));
    }

    jstring iface = env->NewStringUTF(kIface);
    jstring instance = env->NewStringUTF("default");
    jobject service = env->CallStaticObjectMethod(
        hwBinder, getService, iface, instance, JNI_FALSE);

    if (env->ExceptionCheck()) {
        jthrowable error = env->ExceptionOccurred();
        env->ExceptionClear();
        return toJString(
            env,
            std::string("BLOCKED|HIDL FM não acessível ao APK: ") +
                throwableText(env, error));
    }

    if (!service) {
        return toJString(
            env,
            std::string("MISSING|") + kIface + "/default não encontrado");
    }

    return toJString(
        env,
        std::string("OK|") + kIface +
            "/default encontrado via HwBinder");
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_reyfxck_radiofmx_fm_nativebridge_NativeFmBridge_nativeQcomAidlBinder(
    JNIEnv* env,
    jobject
) {
    BinderNdkApi& api = binderNdk();
    if (!api.readyForLookup()) {
        LOGE("Binder NDK API not available");
        return nullptr;
    }

    void* binder = api.checkService(kQcomFmAidlService);
    if (!binder) {
        LOGE("QCOM FM AIDL service binder unavailable");
        return nullptr;
    }

    jobject javaBinder = api.toJavaBinder(env, binder);
    api.decStrong(binder);
    return javaBinder;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reyfxck_radiofmx_fm_nativebridge_NativeFmBridge_nativeMarkVintfStability(
    JNIEnv* env,
    jobject,
    jobject javaBinder
) {
    BinderNdkApi& api = binderNdk();
    if (!api.readyForMarking() || javaBinder == nullptr) {
        return JNI_FALSE;
    }

    void* binder = api.fromJavaBinder(env, javaBinder);
    if (!binder) {
        return JNI_FALSE;
    }

    api.markVintf(binder);
    api.decStrong(binder);
    return JNI_TRUE;
}
