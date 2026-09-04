#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#if FT4_OFFICIAL_CORE
#include "ft4_bridge.h"
#include "ftx_core/include/ftx_decoder.h"
#include "ftx_core/include/ftx_encoder.h"
#endif

#ifndef FT4_UNAVAILABLE_REASON
#define FT4_UNAVAILABLE_REASON ""
#endif

namespace {

std::mutex native_mutex;

class UtfChars final {
public:
    UtfChars(JNIEnv *environment, jstring value) : env(environment), string(value) {
        chars = value == nullptr ? nullptr : env->GetStringUTFChars(value, nullptr);
    }

    ~UtfChars() {
        if (chars != nullptr) env->ReleaseStringUTFChars(string, chars);
    }

    const char *get() const { return chars == nullptr ? "" : chars; }

private:
    JNIEnv *env;
    jstring string;
    const char *chars = nullptr;
};

jobjectArray string_array(JNIEnv *env, const std::vector<const char *> &values) {
    jclass string_class = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(values.size()), string_class, nullptr);
    for (jsize index = 0; index < static_cast<jsize>(values.size()); ++index) {
        jstring value = env->NewStringUTF(values[index]);
        env->SetObjectArrayElement(result, index, value);
        env->DeleteLocalRef(value);
    }
    env->DeleteLocalRef(string_class);
    return result;
}

jobjectArray native_capabilities(JNIEnv *env, jclass) {
#if FT4_OFFICIAL_CORE
    return string_array(env, {FT4_ANDROID_ABI, "1", "1", "1", "", FT4_UPSTREAM_SHA});
#else
    return string_array(env, {FT4_ANDROID_ABI, "0", "0", "0", FT4_UNAVAILABLE_REASON, FT4_UPSTREAM_SHA});
#endif
}

jlong native_create(JNIEnv *, jclass, jlong utc_millis) {
#if FT4_OFFICIAL_CORE
    std::lock_guard<std::mutex> lock(native_mutex);
    ftx_decoder_t *decoder = ftx_decoder_create(FTX_MODE_FT4, 12000, 90000, utc_millis);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(decoder));
#else
    (void) utc_millis;
    return 0;
#endif
}

void native_destroy(JNIEnv *, jclass, jlong handle) {
#if FT4_OFFICIAL_CORE
    std::lock_guard<std::mutex> lock(native_mutex);
    ftx_decoder_destroy(reinterpret_cast<ftx_decoder_t *>(static_cast<intptr_t>(handle)));
#else
    (void) handle;
#endif
}

jobjectArray native_decode(JNIEnv *env,
                           jclass,
                           jlong handle,
                           jfloatArray samples,
                           jlong utc_millis,
                           jint pass_count,
                           jint round_count,
                           jboolean early_decode,
                           jboolean wideband,
                           jint qso_frequency_hz,
                           jstring my_call,
                           jobjectArray hint_calls) {
    jclass result_class = env->FindClass("com/rtbishop/look4sat/core/ft4/Ft4NativeDecodeResult");
#if FT4_OFFICIAL_CORE
    if (handle == 0 || samples == nullptr || env->GetArrayLength(samples) != 90000) {
        return env->NewObjectArray(0, result_class, nullptr);
    }
    UtfChars own_call(env, my_call);
    std::vector<std::string> hint_strings;
    std::vector<const char *> hints;
    const jsize hint_count = hint_calls == nullptr ? 0 : env->GetArrayLength(hint_calls);
    for (jsize index = 0; index < hint_count && index < FTX_MAX_HINT_CALLS; ++index) {
        auto hint = static_cast<jstring>(env->GetObjectArrayElement(hint_calls, index));
        UtfChars utf(env, hint);
        hint_strings.emplace_back(utf.get());
        env->DeleteLocalRef(hint);
    }
    for (const std::string &hint : hint_strings) hints.push_back(hint.c_str());
    jfloat *sample_data = env->GetFloatArrayElements(samples, nullptr);
    auto *decoder = reinterpret_cast<ftx_decoder_t *>(static_cast<intptr_t>(handle));
    ftx_decoder_options_t options{};
    options.decode_pass_count = pass_count;
    options.multi_decode_round_count = round_count;
    options.qso_freq_sensitivity = 1;
    options.decode_sensitivity = 1;
    options.enable_early_decode = early_decode ? 1 : 0;
    options.enable_wideband_dx_search = wideband ? 1 : 0;
    options.ldpc_iterations = 40;
    ftx_decoder_input_context_t input_context{};
    input_context.input_is_live = 1;
    input_context.qso_frequency_hz = qso_frequency_hz;
    input_context.tx_frequency_hz = qso_frequency_hz;
    input_context.source_sample_rate = 12000;

    std::lock_guard<std::mutex> lock(native_mutex);
    ftx_decoder_set_options(decoder, &options);
    ftx_decoder_set_input_context(decoder, &input_context);
    ftx_decoder_set_ap_hints(decoder, own_call.get(),
                             hints.empty() ? nullptr : hints.data(), nullptr,
                             static_cast<int>(hints.size()));
    const int count = ftx_decoder_process_float_slot(decoder, sample_data, 90000, utc_millis);
    env->ReleaseFloatArrayElements(samples, sample_data, JNI_ABORT);
    if (count < 0) return env->NewObjectArray(0, result_class, nullptr);

    jmethodID constructor = env->GetMethodID(
            result_class, "<init>", "(JIFFLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;J)V");
    jobjectArray results = env->NewObjectArray(count, result_class, nullptr);
    for (int index = 0; index < count; ++index) {
        ftx_decode_result_t decoded{};
        if (ftx_decoder_get_result(decoder, index, &decoded) != 0) continue;
        jstring text = env->NewStringUTF(decoded.text);
        jstring source = env->NewStringUTF(decoded.call_de);
        jstring target = env->NewStringUTF(decoded.call_to);
        const char *detail_text = decoded.grid[0] != '\0' ? decoded.grid : decoded.extra;
        jstring detail = env->NewStringUTF(detail_text);
        jobject result = env->NewObject(result_class, constructor,
                                        static_cast<jlong>(decoded.utc_time), decoded.snr,
                                        decoded.time_sec, decoded.freq_hz, text, source, target,
                                        detail, static_cast<jlong>(decoded.message_hash));
        env->SetObjectArrayElement(results, index, result);
        env->DeleteLocalRef(result);
        env->DeleteLocalRef(text);
        env->DeleteLocalRef(source);
        env->DeleteLocalRef(target);
        env->DeleteLocalRef(detail);
    }
    env->DeleteLocalRef(result_class);
    return results;
#else
    (void) handle;
    (void) samples;
    (void) utc_millis;
    (void) pass_count;
    (void) round_count;
    (void) early_decode;
    (void) wideband;
    (void) qso_frequency_hz;
    (void) my_call;
    (void) hint_calls;
    jobjectArray results = env->NewObjectArray(0, result_class, nullptr);
    env->DeleteLocalRef(result_class);
    return results;
#endif
}

jstring native_validate(JNIEnv *env, jclass, jstring message) {
#if FT4_OFFICIAL_CORE
    UtfChars text(env, message);
    uint8_t payload[FTX_PAYLOAD_BYTES]{};
    char normalized[FTX_MAX_TEXT_LENGTH]{};
    std::lock_guard<std::mutex> lock(native_mutex);
    if (ftx_pack_message(text.get(), payload) != 0
            || ftx_unpack_message(payload, normalized, sizeof(normalized)) != 0) return nullptr;
    return env->NewStringUTF(normalized);
#else
    (void) message;
    return nullptr;
#endif
}

jfloatArray native_generate(JNIEnv *env, jclass, jstring message, jfloat frequency, jint sample_rate) {
#if FT4_OFFICIAL_CORE
    if (sample_rate != 12000 && sample_rate != 24000 && sample_rate != 48000) return nullptr;
    UtfChars text(env, message);
    const int capacity = sample_rate * 504 / 100;
    std::vector<float> wave(static_cast<size_t>(capacity));
    std::lock_guard<std::mutex> lock(native_mutex);
    const int count = ft4_bridge_generate_wave(text.get(), sample_rate, frequency, wave.data(), capacity);
    if (count != capacity) return nullptr;
    jfloatArray result = env->NewFloatArray(count);
    env->SetFloatArrayRegion(result, 0, count, wave.data());
    return result;
#else
    (void) message;
    (void) frequency;
    (void) sample_rate;
    return nullptr;
#endif
}

jstring native_self_test(JNIEnv *env, jclass) {
#if FT4_OFFICIAL_CORE
    constexpr const char *message = "CQ K1ABC FN42";
    uint8_t payload[FTX_PAYLOAD_BYTES]{};
    uint8_t encoded_tones[FTX_FT4_TONE_COUNT]{};
    char unpacked[FTX_MAX_TEXT_LENGTH]{};
    int bridge_tones[FTX_FT4_TONE_COUNT]{};
    std::lock_guard<std::mutex> lock(native_mutex);
    if (ftx_pack_message(message, payload) != 0) return env->NewStringUTF("pack failed");
    if (ftx_unpack_message(payload, unpacked, sizeof(unpacked)) != 0) return env->NewStringUTF("unpack failed");
    if (std::strcmp(message, unpacked) != 0) return env->NewStringUTF("pack/unpack mismatch");
    if (ftx_encode_tones(FTX_MODE_FT4, payload, encoded_tones, FTX_FT4_TONE_COUNT)
            != FTX_FT4_TONE_COUNT
            || ft4_bridge_generate_tones(message, bridge_tones, FTX_FT4_TONE_COUNT)
            != FTX_FT4_TONE_COUNT) {
        return env->NewStringUTF("tone count mismatch");
    }
    for (int index = 0; index < FTX_FT4_TONE_COUNT; ++index) {
        if (bridge_tones[index] < 0 || bridge_tones[index] > 3
                || bridge_tones[index] != encoded_tones[index]) {
            return env->NewStringUTF("tone mismatch");
        }
    }
    for (const int sample_rate : {12000, 24000, 48000}) {
        const int count = sample_rate * 504 / 100;
        std::vector<float> wave(static_cast<size_t>(count));
        std::vector<float> repeat(static_cast<size_t>(count));
        if (ft4_bridge_generate_wave(message, sample_rate, 1500.0f, wave.data(), count) != count
                || ft4_bridge_generate_wave(message, sample_rate, 1500.0f, repeat.data(), count) != count) {
            return env->NewStringUTF("wave length mismatch");
        }
        if (wave != repeat || std::fabs(wave.front()) >= 0.01f
                || std::fabs(wave.back()) >= 0.01f) {
            return env->NewStringUTF("wave determinism mismatch");
        }
        for (float sample : wave) {
            if (!std::isfinite(sample) || std::fabs(sample) > 1.001f) {
                return env->NewStringUTF("invalid waveform");
            }
        }
    }
    constexpr int waveform_samples = 12000 * 504 / 100;
    constexpr int waveform_offset = 12000 / 2;
    std::vector<float> waveform(static_cast<size_t>(waveform_samples));
    std::vector<float> decode_slot(90000, 0.0f);
    if (ft4_bridge_generate_wave(message, 12000, 1500.0f, waveform.data(), waveform_samples)
            != waveform_samples) {
        return env->NewStringUTF("decode-loop waveform failed");
    }
    std::copy(waveform.begin(), waveform.end(), decode_slot.begin() + waveform_offset);
    ftx_decoder_t *decoder = ftx_decoder_create(FTX_MODE_FT4, 12000, 90000, 0);
    if (decoder == nullptr) return env->NewStringUTF("decode-loop create failed");
    ftx_decoder_options_t options{};
    options.decode_pass_count = 3;
    options.multi_decode_round_count = 3;
    options.qso_freq_sensitivity = 1;
    options.decode_sensitivity = 1;
    options.enable_early_decode = 1;
    options.enable_wideband_dx_search = 1;
    options.ldpc_iterations = 40;
    ftx_decoder_input_context_t input{};
    input.input_is_live = 0;
    input.qso_frequency_hz = 1500;
    input.tx_frequency_hz = 1500;
    input.source_sample_rate = 12000;
    const bool configured = ftx_decoder_set_options(decoder, &options) == 0
            && ftx_decoder_set_input_context(decoder, &input) == 0;
    const int decoded_count = configured
            ? ftx_decoder_process_float_slot(decoder, decode_slot.data(), 90000, 0)
            : -1;
    bool found_message = false;
    for (int index = 0; index < decoded_count; ++index) {
        ftx_decode_result_t decoded{};
        if (ftx_decoder_get_result(decoder, index, &decoded) == 0
                && std::strcmp(decoded.text, message) == 0) {
            found_message = true;
            break;
        }
    }
    ftx_decoder_destroy(decoder);
    if (!found_message) return env->NewStringUTF("decode-loop failed");
    return env->NewStringUTF("");
#else
    return env->NewStringUTF(FT4_UNAVAILABLE_REASON);
#endif
}

JNINativeMethod methods[] = {
        {const_cast<char *>("nativeCapabilities"), const_cast<char *>("()[Ljava/lang/String;"),
         reinterpret_cast<void *>(native_capabilities)},
        {const_cast<char *>("nativeCreate"), const_cast<char *>("(J)J"),
         reinterpret_cast<void *>(native_create)},
        {const_cast<char *>("nativeDestroy"), const_cast<char *>("(J)V"),
         reinterpret_cast<void *>(native_destroy)},
        {const_cast<char *>("nativeDecode"),
         const_cast<char *>("(J[FJIIZZILjava/lang/String;[Ljava/lang/String;)[Lcom/rtbishop/look4sat/core/ft4/Ft4NativeDecodeResult;"),
         reinterpret_cast<void *>(native_decode)},
        {const_cast<char *>("nativeValidate"), const_cast<char *>("(Ljava/lang/String;)Ljava/lang/String;"),
         reinterpret_cast<void *>(native_validate)},
        {const_cast<char *>("nativeGenerate"), const_cast<char *>("(Ljava/lang/String;FI)[F"),
         reinterpret_cast<void *>(native_generate)},
        {const_cast<char *>("nativeSelfTest"), const_cast<char *>("()Ljava/lang/String;"),
         reinterpret_cast<void *>(native_self_test)},
};

} // namespace

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass binding_class = env->FindClass("com/rtbishop/look4sat/core/ft4/Ft4NativeBindings");
    if (binding_class == nullptr) return JNI_ERR;
    const jint result = env->RegisterNatives(binding_class, methods,
                                             static_cast<jint>(sizeof(methods) / sizeof(methods[0])));
    env->DeleteLocalRef(binding_class);
    return result == JNI_OK ? JNI_VERSION_1_6 : JNI_ERR;
}
