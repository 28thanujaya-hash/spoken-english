// Paradee voice on the phone: misaki-g2p (words -> sounds) + ONNX Runtime (sounds -> audio).
// ONNX Runtime is NOT inside the APK: libonnxruntime.so is downloaded with the voice files and
// opened here with dlopen, through its plain C API. This keeps every app update small.
#include <jni.h>
#include <dlfcn.h>
#include <mutex>
#include <string>
#include <vector>
#include "misaki-g2p.h"
#include "onnxruntime_c_api.h"

namespace {
std::mutex g_lock;
std::string g_err;
misaki_g2p * g_g2p = nullptr;
const OrtApi * g_api = nullptr;
OrtEnv * g_env = nullptr;

struct Session { OrtSession * s = nullptr; OrtMemoryInfo * mem = nullptr; };

std::string str(JNIEnv * e, jstring s) {
    const char * c = e->GetStringUTFChars(s, nullptr);
    std::string r(c ? c : "");
    if (c) e->ReleaseStringUTFChars(s, c);
    return r;
}
// Java's GetStringUTFChars gives "modified UTF-8"; go through bytes so phonemes stay real UTF-8.
std::string utf8(JNIEnv * e, jbyteArray b) {
    jsize n = e->GetArrayLength(b);
    std::string r(n, '\0');
    e->GetByteArrayRegion(b, 0, n, reinterpret_cast<jbyte *>(&r[0]));
    return r;
}
bool ok(OrtStatus * st) {
    if (!st) return true;
    g_err = g_api->GetErrorMessage(st);
    g_api->ReleaseStatus(st);
    return false;
}
}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL Java_lk_spokenenglish_app_Paradee_lastError(JNIEnv * e, jclass) {
    std::lock_guard<std::mutex> l(g_lock);
    return e->NewStringUTF(g_err.c_str());
}

// ---- words -> sounds
JNIEXPORT jboolean JNICALL Java_lk_spokenenglish_app_Paradee_g2pOpen(JNIEnv * e, jclass, jstring lex) {
    std::lock_guard<std::mutex> l(g_lock);
    if (g_g2p) return JNI_TRUE;
    char * err = nullptr;
    g_g2p = misaki_g2p_open(str(e, lex).c_str(), nullptr, &err);
    if (!g_g2p) { g_err = err ? err : "misaki_g2p_open failed"; misaki_g2p_free(err); return JNI_FALSE; }
    return JNI_TRUE;
}

// UTF-8 bytes in, UTF-8 bytes out (sentence chunks joined by '\n'); null on error.
JNIEXPORT jbyteArray JNICALL Java_lk_spokenenglish_app_Paradee_g2p(JNIEnv * e, jclass, jbyteArray text) {
    std::lock_guard<std::mutex> l(g_lock);
    if (!g_g2p) { g_err = "g2p not open"; return nullptr; }
    std::string t = utf8(e, text);
    char * out = nullptr; char * err = nullptr;
    if (misaki_g2p_phonemize(g_g2p, t.data(), t.size(), &out, &err) != 0) {
        g_err = err ? err : "phonemize failed"; misaki_g2p_free(err); return nullptr;
    }
    std::string r(out ? out : "");
    misaki_g2p_free(out);
    jbyteArray a = e->NewByteArray((jsize) r.size());
    e->SetByteArrayRegion(a, 0, (jsize) r.size(), reinterpret_cast<const jbyte *>(r.data()));
    return a;
}

// ---- the engine (downloaded libonnxruntime.so)
JNIEXPORT jboolean JNICALL Java_lk_spokenenglish_app_Paradee_ortLoad(JNIEnv * e, jclass, jstring so) {
    std::lock_guard<std::mutex> l(g_lock);
    if (g_api) return JNI_TRUE;
    void * h = dlopen(str(e, so).c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!h) { const char * d = dlerror(); g_err = std::string("dlopen: ") + (d ? d : "?"); return JNI_FALSE; }
    using GetBase = const OrtApiBase * (*)();
    auto gb = reinterpret_cast<GetBase>(dlsym(h, "OrtGetApiBase"));
    if (!gb) { g_err = "OrtGetApiBase not found"; return JNI_FALSE; }
    g_api = gb()->GetApi(ORT_API_VERSION);
    if (!g_api) { g_err = std::string("ORT API ") + std::to_string(ORT_API_VERSION) + " not available in " + gb()->GetVersionString(); return JNI_FALSE; }
    if (!ok(g_api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "paradee", &g_env))) { g_api = nullptr; return JNI_FALSE; }
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL Java_lk_spokenenglish_app_Paradee_ortVersion(JNIEnv * e, jclass, jstring so) {
    void * h = dlopen(str(e, so).c_str(), RTLD_NOW | RTLD_LOCAL | RTLD_NOLOAD);
    if (!h) return e->NewStringUTF("?");
    using GetBase = const OrtApiBase * (*)();
    auto gb = reinterpret_cast<GetBase>(dlsym(h, "OrtGetApiBase"));
    return e->NewStringUTF(gb ? gb()->GetVersionString() : "?");
}

JNIEXPORT jlong JNICALL Java_lk_spokenenglish_app_Paradee_open(JNIEnv * e, jclass, jstring model, jint threads, jboolean spin) {
    std::lock_guard<std::mutex> l(g_lock);
    if (!g_api) { g_err = "engine not loaded"; return 0; }
    OrtSessionOptions * so = nullptr;
    if (!ok(g_api->CreateSessionOptions(&so))) return 0;
    ok(g_api->SetIntraOpNumThreads(so, threads));
    ok(g_api->SetInterOpNumThreads(so, 1));
    ok(g_api->SetSessionGraphOptimizationLevel(so, ORT_ENABLE_ALL));
    ok(g_api->AddSessionConfigEntry(so, "session.intra_op.allow_spinning", spin ? "1" : "0"));
    auto * s = new Session();
    bool good = ok(g_api->CreateSession(g_env, str(e, model).c_str(), so, &s->s)) &&
                ok(g_api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &s->mem));
    g_api->ReleaseSessionOptions(so);
    if (!good) { if (s->s) g_api->ReleaseSession(s->s); delete s; return 0; }
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL Java_lk_spokenenglish_app_Paradee_close(JNIEnv *, jclass, jlong h) {
    std::lock_guard<std::mutex> l(g_lock);
    auto * s = reinterpret_cast<Session *>(h);
    if (!s || !g_api) return;
    if (s->mem) g_api->ReleaseMemoryInfo(s->mem);
    if (s->s) g_api->ReleaseSession(s->s);
    delete s;
}

// Phoneme ids (with the 0 pads already added) -> 24 kHz float audio; null on error.
JNIEXPORT jfloatArray JNICALL Java_lk_spokenenglish_app_Paradee_synth(JNIEnv * e, jclass, jlong h, jlongArray idsA, jlong voice, jfloat speed) {
    auto * s = reinterpret_cast<Session *>(h);
    if (!s || !g_api) return nullptr;
    jsize n = e->GetArrayLength(idsA);
    std::vector<int64_t> ids(n);
    e->GetLongArrayRegion(idsA, 0, n, reinterpret_cast<jlong *>(ids.data()));
    int64_t v = voice; float sp = speed;
    int64_t shapeIds[2] = {1, (int64_t) n}, shapeOne[1] = {1};
    OrtValue * in[3] = {nullptr, nullptr, nullptr}; OrtValue * out = nullptr;
    jfloatArray result = nullptr;
    std::string err;
    {
        // Run itself is thread-safe in ORT; the lock only guards g_err.
        OrtStatus * st = nullptr;
        if ((st = g_api->CreateTensorWithDataAsOrtValue(s->mem, ids.data(), n * sizeof(int64_t), shapeIds, 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &in[0])) ||
            (st = g_api->CreateTensorWithDataAsOrtValue(s->mem, &sp, sizeof(float), shapeOne, 1, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &in[1])) ||
            (st = g_api->CreateTensorWithDataAsOrtValue(s->mem, &v, sizeof(int64_t), shapeOne, 1, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &in[2]))) {
            err = g_api->GetErrorMessage(st); g_api->ReleaseStatus(st);
        } else {
            const char * inNames[3] = {"input_ids", "speed", "voice"};
            const char * outNames[1] = {"waveform"};
            st = g_api->Run(s->s, nullptr, inNames, in, 3, outNames, 1, &out);
            if (st) { err = g_api->GetErrorMessage(st); g_api->ReleaseStatus(st); }
            else {
                float * p = nullptr; OrtTensorTypeAndShapeInfo * info = nullptr; size_t cnt = 0;
                OrtStatus * s1 = g_api->GetTensorMutableData(out, reinterpret_cast<void **>(&p));
                OrtStatus * s2 = s1 ? nullptr : g_api->GetTensorTypeAndShape(out, &info);
                OrtStatus * s3 = (s1 || s2) ? nullptr : g_api->GetTensorShapeElementCount(info, &cnt);
                if (info) g_api->ReleaseTensorTypeAndShapeInfo(info);
                OrtStatus * bad = s1 ? s1 : s2 ? s2 : s3;
                if (bad) { err = g_api->GetErrorMessage(bad); g_api->ReleaseStatus(bad); }
                else {
                    result = e->NewFloatArray((jsize) cnt);
                    e->SetFloatArrayRegion(result, 0, (jsize) cnt, p);
                }
            }
        }
    }
    for (auto * x : in) if (x) g_api->ReleaseValue(x);
    if (out) g_api->ReleaseValue(out);
    if (!err.empty()) { std::lock_guard<std::mutex> l(g_lock); g_err = err; }
    return result;
}

}  // extern "C"
