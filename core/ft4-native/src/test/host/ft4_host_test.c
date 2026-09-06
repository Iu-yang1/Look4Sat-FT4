#include "ft4_bridge.h"
#include "ftx_core/include/ftx_decoder.h"
#include "ftx_core/include/ftx_encoder.h"

#include <math.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

enum {
    FT4_SLOT_SAMPLES = 90000,
    FT4_WAVE_SAMPLES_12K = 60480,
    FT4_TONE_COUNT = 105,
    CONCURRENT_DECODERS = 4,
    NOISE_SLOT_COUNT = 200
};

static int failures = 0;

static void expect_true(int condition, const char *label) {
    printf("[%s] %s\n", condition ? "PASS" : "FAIL", label);
    if (!condition) failures++;
}

static uint32_t read_u32_le(const uint8_t value[4]) {
    return (uint32_t) value[0]
            | ((uint32_t) value[1] << 8u)
            | ((uint32_t) value[2] << 16u)
            | ((uint32_t) value[3] << 24u);
}

static uint16_t read_u16_le(const uint8_t value[2]) {
    return (uint16_t) (value[0] | ((uint16_t) value[1] << 8u));
}

static int load_pcm16_wav(const char *path, float *samples, int capacity, int *sample_rate) {
    FILE *file = fopen(path, "rb");
    uint8_t riff[12];
    uint16_t format = 0;
    uint16_t channels = 0;
    uint16_t bits = 0;
    int loaded = 0;
    if (file == NULL || fread(riff, 1, sizeof(riff), file) != sizeof(riff)
            || memcmp(riff, "RIFF", 4) != 0 || memcmp(riff + 8, "WAVE", 4) != 0) {
        if (file != NULL) fclose(file);
        return -1;
    }
    while (!feof(file)) {
        uint8_t chunk[8];
        uint32_t size;
        if (fread(chunk, 1, sizeof(chunk), file) != sizeof(chunk)) break;
        size = read_u32_le(chunk + 4);
        if (memcmp(chunk, "fmt ", 4) == 0) {
            uint8_t fmt[16];
            if (size < sizeof(fmt) || fread(fmt, 1, sizeof(fmt), file) != sizeof(fmt)) break;
            format = read_u16_le(fmt);
            channels = read_u16_le(fmt + 2);
            *sample_rate = (int) read_u32_le(fmt + 4);
            bits = read_u16_le(fmt + 14);
            if (size > sizeof(fmt)) fseek(file, (long) (size - sizeof(fmt)), SEEK_CUR);
        } else if (memcmp(chunk, "data", 4) == 0) {
            int frame_count;
            if (format != 1 || channels != 1 || bits != 16) break;
            frame_count = (int) (size / 2u);
            loaded = frame_count < capacity ? frame_count : capacity;
            for (int index = 0; index < loaded; ++index) {
                uint8_t pcm[2];
                int16_t value;
                if (fread(pcm, 1, sizeof(pcm), file) != sizeof(pcm)) {
                    loaded = -1;
                    break;
                }
                value = (int16_t) read_u16_le(pcm);
                samples[index] = (float) value / 32768.0f;
            }
            break;
        } else {
            fseek(file, (long) size, SEEK_CUR);
        }
        if ((size & 1u) != 0) fseek(file, 1, SEEK_CUR);
    }
    fclose(file);
    return loaded;
}

static int configure_decoder(ftx_decoder_t *decoder) {
    ftx_decoder_options_t options = {0};
    ftx_decoder_input_context_t input = {0};
    options.decode_pass_count = 3;
    options.multi_decode_round_count = 3;
    options.qso_freq_sensitivity = 1;
    options.decode_sensitivity = 1;
    options.enable_early_decode = 1;
    options.enable_wideband_dx_search = 1;
    options.ldpc_iterations = 40;
    input.input_is_live = 0;
    input.qso_frequency_hz = 1500;
    input.tx_frequency_hz = 1500;
    input.source_sample_rate = 12000;
    return ftx_decoder_set_options(decoder, &options) == 0
            && ftx_decoder_set_input_context(decoder, &input) == 0;
}

static void test_codec(void) {
    static const char *messages[] = {
        "CQ BG5JSU OL87",
        "BG5JSU JA6RJK PM53",
        "BG5JSU JA6RJK -10",
        "BG5JSU JA6RJK R-08",
        "BG5JSU JA6RJK RR73",
        "BG5JSU JA6RJK 73",
        "CQ TEST BG5JSU OL87"
    };
    for (size_t index = 0; index < sizeof(messages) / sizeof(messages[0]); ++index) {
        uint8_t payload[FTX_PAYLOAD_BYTES] = {0};
        char unpacked[FTX_MAX_TEXT_LENGTH] = {0};
        expect_true(ftx_pack_message(messages[index], payload) == 0
                        && ftx_unpack_message(payload, unpacked, sizeof(unpacked)) == 0
                        && strcmp(messages[index], unpacked) == 0,
                    messages[index]);
    }
}

static void test_tones_and_waveform(void) {
    const char *message = "CQ K1ABC FN42";
    uint8_t payload[FTX_PAYLOAD_BYTES] = {0};
    uint8_t encoded_tones[FT4_TONE_COUNT] = {0};
    int bridge_tones[FT4_TONE_COUNT] = {0};
    expect_true(ftx_pack_message(message, payload) == 0, "waveform message pack");
    expect_true(ftx_encode_tones(FTX_MODE_FT4, payload, encoded_tones, FT4_TONE_COUNT)
                        == FT4_TONE_COUNT,
                "105 FT4 tones");
    expect_true(ft4_bridge_generate_tones(message, bridge_tones, FT4_TONE_COUNT)
                        == FT4_TONE_COUNT,
                "official bridge tone count");
    for (int index = 0; index < FT4_TONE_COUNT; ++index) {
        if (bridge_tones[index] != encoded_tones[index]
                || bridge_tones[index] < 0 || bridge_tones[index] > 3) {
            expect_true(0, "FT4 C and WSJT-X tone equivalence");
            return;
        }
    }
    expect_true(1, "FT4 C and WSJT-X tone equivalence");

    for (int rate_index = 0; rate_index < 3; ++rate_index) {
        const int rate = (int[]) {48000, 24000, 12000}[rate_index];
        const int count = FT4_WAVE_SAMPLES_12K * (rate / 12000);
        float *wave = (float *) calloc((size_t) count + 16u, sizeof(float));
        float *repeat = (float *) calloc((size_t) count + 16u, sizeof(float));
        int wave_count = wave == NULL ? 0
                : ft4_bridge_generate_wave(message, rate, 1500.0f, wave, count);
        int repeat_count = repeat == NULL ? 0
                : ft4_bridge_generate_wave(message, rate, 1500.0f, repeat, count);
        int valid = wave != NULL && repeat != NULL
                && wave_count == count && repeat_count == count;
        float maximum_step = 0.0f;
        if (valid) {
            valid = memcmp(wave, repeat, (size_t) count * sizeof(float)) == 0;
            for (int index = 0; valid && index < count; ++index) {
                valid = isfinite(wave[index]) && fabsf(wave[index]) <= 1.001f;
                if (index > 0) maximum_step = fmaxf(maximum_step, fabsf(wave[index] - wave[index - 1]));
            }
            valid = valid && fabsf(wave[0]) < 0.01f && fabsf(wave[count - 1]) < 0.01f
                    && maximum_step < 1.1f;
        }
        if (!valid) {
            printf("wave diagnostic rate=%d returned=%d/%d first=%g last=%g max_step=%g\n",
                   rate, wave_count, repeat_count, wave == NULL ? 0.0f : wave[0],
                   wave == NULL ? 0.0f : wave[count - 1], maximum_step);
        }
        char label[96];
        snprintf(label, sizeof(label), "%d Hz waveform length/finite/shape/determinism", rate);
        expect_true(valid, label);
        free(repeat);
        free(wave);
    }
}

static int decode_slot(ftx_decoder_t *decoder, const float *samples, long long utc_millis) {
    return ftx_decoder_process_float_slot(decoder, samples, FT4_SLOT_SAMPLES, utc_millis);
}

static void test_generated_decode_loop(void) {
    const char *message = "CQ K1ABC FN42";
    float *waveform = (float *) calloc(FT4_WAVE_SAMPLES_12K, sizeof(float));
    float *slot = (float *) calloc(FT4_SLOT_SAMPLES, sizeof(float));
    ftx_decoder_t *decoder = ftx_decoder_create(FTX_MODE_FT4, 12000, FT4_SLOT_SAMPLES, 0);
    int found = 0;
    if (waveform != NULL && slot != NULL && decoder != NULL
            && ft4_bridge_generate_wave(message, 12000, 1500.0f, waveform,
                                        FT4_WAVE_SAMPLES_12K) == FT4_WAVE_SAMPLES_12K
            && configure_decoder(decoder)) {
        memcpy(slot + 6000, waveform, FT4_WAVE_SAMPLES_12K * sizeof(float));
        const int count = decode_slot(decoder, slot, 0);
        for (int index = 0; index < count; ++index) {
            ftx_decode_result_t result = {0};
            if (ftx_decoder_get_result(decoder, index, &result) == 0
                    && strcmp(result.text, message) == 0) {
                found = 1;
                break;
            }
        }
    }
    expect_true(found, "generated waveform completes real decode loop");
    ftx_decoder_destroy(decoder);
    free(slot);
    free(waveform);
}

static void test_noise(void) {
    float *noise = (float *) calloc(FT4_SLOT_SAMPLES, sizeof(float));
    ftx_decoder_t *decoder = ftx_decoder_create(FTX_MODE_FT4, 12000, FT4_SLOT_SAMPLES, 0);
    uint32_t random_state = UINT32_C(0x4f54a1c3);
    int decoded = 0;
    expect_true(noise != NULL && decoder != NULL, "noise decoder allocation");
    if (noise == NULL || decoder == NULL) {
        free(noise);
        ftx_decoder_destroy(decoder);
        return;
    }
    expect_true(configure_decoder(decoder), "noise decoder configuration");
    for (int slot = 0; slot < NOISE_SLOT_COUNT; ++slot) {
        for (int index = 0; index < FT4_SLOT_SAMPLES; ++index) {
            random_state ^= random_state << 13u;
            random_state ^= random_state >> 17u;
            random_state ^= random_state << 5u;
            noise[index] = ((float) (random_state & UINT32_C(0xffff)) / 32767.5f - 1.0f) * 0.1f;
        }
        decoded += decode_slot(decoder, noise, (long long) slot * 7500LL);
    }
    expect_true(decoded == 0, "200 pure-noise slots have zero CRC-valid decodes");
    ftx_decoder_destroy(decoder);
    free(noise);
}

typedef struct {
    const float *samples;
    int expected_count;
    int actual_count;
} concurrent_case_t;

static void *decode_concurrently(void *opaque) {
    concurrent_case_t *test = (concurrent_case_t *) opaque;
    ftx_decoder_t *decoder = ftx_decoder_create(FTX_MODE_FT4, 12000, FT4_SLOT_SAMPLES, 2000);
    if (decoder == NULL) {
        test->actual_count = -1;
        return NULL;
    }
    if (!configure_decoder(decoder)) {
        ftx_decoder_destroy(decoder);
        test->actual_count = -1;
        return NULL;
    }
    test->actual_count = decode_slot(decoder, test->samples, 2000);
    ftx_decoder_destroy(decoder);
    return NULL;
}

static void test_corpus(const char *path) {
    static const char *expected_messages[] = {
        "N1TRK N4FKH 569 VA",
        "CQ RU N9OY EN43",
        "CQ RU W0FRC DM79"
    };
    float *samples = (float *) calloc(FT4_SLOT_SAMPLES, sizeof(float));
    int sample_rate = 0;
    int source_count = samples == NULL ? -1
            : load_pcm16_wav(path, samples, FT4_SLOT_SAMPLES, &sample_rate);
    ftx_decoder_t *decoder = NULL;
    ftx_decode_result_t first[FTX_MAX_DECODE_RESULTS] = {0};
    int count = -1;
    expect_true(source_count == 72576 && sample_rate == 12000,
                "official FT4 corpus format and sample count");
    if (source_count <= 0 || sample_rate != 12000) {
        free(samples);
        return;
    }
    decoder = ftx_decoder_create(FTX_MODE_FT4, 12000, FT4_SLOT_SAMPLES, 2000);
    expect_true(decoder != NULL, "corpus decoder allocation");
    if (decoder == NULL) {
        free(samples);
        return;
    }
    expect_true(configure_decoder(decoder), "corpus decoder configuration");
    count = decode_slot(decoder, samples, 2000);
    printf("[FT4] rate=%d samples=%d results=%d\n", sample_rate, FT4_SLOT_SAMPLES, count);
    for (int index = 0; index < count; ++index) {
        if (ftx_decoder_get_result(decoder, index, &first[index]) != 0) continue;
        printf("  #%d sync=%.2f snr=%d dt=%.2f freq=%.1f nap=%d text=%s\n",
               index, first[index].sync, first[index].snr,
               first[index].time_sec, first[index].freq_hz, 0, first[index].text);
    }
    expect_true(count == 16, "official FT4 corpus decodes 16/16");
    for (size_t expected = 0;
         expected < sizeof(expected_messages) / sizeof(expected_messages[0]);
         ++expected) {
        int found = 0;
        for (int index = 0; index < count; ++index) {
            if (strcmp(first[index].text, expected_messages[expected]) == 0) found = 1;
        }
        expect_true(found, expected_messages[expected]);
    }
    expect_true(decode_slot(decoder, samples, 2000) == count, "reused decoder result count is deterministic");
    for (int index = 0; index < count; ++index) {
        ftx_decode_result_t repeated = {0};
        ftx_decoder_get_result(decoder, index, &repeated);
        expect_true(first[index].message_hash == repeated.message_hash
                            && strcmp(first[index].text, repeated.text) == 0,
                    "reused decoder message hash is deterministic");
    }
    ftx_decoder_destroy(decoder);

    pthread_t threads[CONCURRENT_DECODERS];
    concurrent_case_t cases[CONCURRENT_DECODERS];
    int concurrency_ok = 1;
    int created_threads = 0;
    for (int index = 0; index < CONCURRENT_DECODERS; ++index) {
        cases[index].samples = samples;
        cases[index].expected_count = count;
        cases[index].actual_count = -1;
        if (pthread_create(&threads[index], NULL, decode_concurrently, &cases[index]) != 0) {
            concurrency_ok = 0;
            break;
        }
        created_threads++;
    }
    for (int index = 0; index < created_threads; ++index) {
        pthread_join(threads[index], NULL);
        concurrency_ok = concurrency_ok && cases[index].actual_count == cases[index].expected_count;
    }
    concurrency_ok = concurrency_ok && created_threads == CONCURRENT_DECODERS;
    expect_true(concurrency_ok, "concurrent requests are serialized by the native core mutex");
    free(samples);
}

int main(int argc, char **argv) {
    test_codec();
    test_tones_and_waveform();
    test_generated_decode_loop();
    if (argc > 1) {
        test_corpus(argv[1]);
        test_noise();
    }
    printf("SUMMARY failures=%d\n", failures);
    return failures == 0 ? 0 : 1;
}
