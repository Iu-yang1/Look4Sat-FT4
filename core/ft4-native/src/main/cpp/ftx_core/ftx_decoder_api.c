#include "include/ftx_decoder.h"

#include "../ft4_bridge.h"
#include "../ft8/constants.h"
#include "../ft8/crc.h"
#include "../ft8/decode.h"
#include "../ft8/pack.h"
#include "../ft8/unpack.h"

#include <pthread.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

struct ftx_decoder {
    int bridge_handle;
    int sample_rate;
    int num_samples;
    long long utc_time;
    ftx_decoder_options_t options;
    ftx_decoder_input_context_t input_context;
    ap_hints_t hints;
    ftx_decode_result_t results[FTX_MAX_DECODE_RESULTS];
    int result_count;
};

/* WSJT-X Fortran 共享全局状态；任何入口都必须经过同一条原生执行通道。 */
static pthread_mutex_t g_ft4_core_mutex = PTHREAD_MUTEX_INITIALIZER;

static int clamp_int(int value, int minimum, int maximum) {
    if (value < minimum) return minimum;
    if (value > maximum) return maximum;
    return value;
}

static void copy_text(char *destination, size_t capacity, const char *source) {
    if (destination == NULL || capacity == 0) return;
    destination[0] = '\0';
    if (source != NULL) snprintf(destination, capacity, "%s", source);
}

static unsigned int message_hash(const char *text) {
    uint8_t payload[FTX_PAYLOAD_BYTES] = {0};
    uint8_t encoded[FTX_PAYLOAD_BYTES] = {0};
    if (text == NULL || pack77(text, payload) != 0) {
        unsigned int hash = 2166136261u;
        const unsigned char *cursor = (const unsigned char *) text;
        while (cursor != NULL && *cursor != 0) {
            hash = (hash ^ *cursor++) * 16777619u;
        }
        return hash;
    }
    for (int index = 0; index < 10; ++index) {
        payload[index] ^= kFT4XORSequence[index];
    }
    ftx_add_crc(payload, encoded);
    return ftx_extract_crc(encoded);
}

static void load_default_options(ftx_decoder_options_t *options) {
    memset(options, 0, sizeof(*options));
    options->decode_pass_count = 3;
    options->multi_decode_round_count = 3;
    options->qso_freq_sensitivity = 1;
    options->decode_sensitivity = 1;
    options->enable_early_decode = 1;
    options->enable_wideband_dx_search = 1;
    options->ldpc_iterations = 40;
}

static void load_default_input_context(ftx_decoder_input_context_t *context) {
    memset(context, 0, sizeof(*context));
    context->input_is_live = 1;
    context->qso_frequency_hz = 1500;
    context->tx_frequency_hz = 1500;
    context->source_sample_rate = 12000;
}

static int same_result(const ftx_decode_result_t *left, const ftx_decode_result_t *right) {
    return left->message_hash == right->message_hash
            && left->utc_time == right->utc_time
            && (int) (left->freq_hz + 0.5f) == (int) (right->freq_hz + 0.5f);
}

static void fill_result(ftx_decoder_t *decoder, const ft4_bridge_result_t *bridge_result) {
    ftx_decode_result_t candidate;
    message_t message;
    uint8_t payload[FTX_PAYLOAD_BYTES] = {0};
    int index;

    memset(&candidate, 0, sizeof(candidate));
    memset(&message, 0, sizeof(message));
    candidate.utc_time = decoder->utc_time;
    candidate.is_valid = 1;
    candidate.snr = bridge_result->snr;
    candidate.score = (int) (bridge_result->sync * 10.0f);
    candidate.sync = bridge_result->sync;
    candidate.time_sec = bridge_result->dt;
    candidate.freq_hz = bridge_result->freq;
    copy_text(candidate.text, sizeof(candidate.text), bridge_result->decoded);
    candidate.message_hash = message_hash(bridge_result->decoded);

    if (pack77(bridge_result->decoded, payload) == 0
            && unpackToMessage_t(payload, &message) == 0) {
        candidate.i3 = message.i3;
        candidate.n3 = message.n3;
        candidate.report = message.report;
        candidate.r_flag = message.r_flag;
        copy_text(candidate.call_to, sizeof(candidate.call_to), message.call_to);
        copy_text(candidate.call_de, sizeof(candidate.call_de), message.call_de);
        copy_text(candidate.dx_call_to2, sizeof(candidate.dx_call_to2), message.dx_call_to2);
        copy_text(candidate.extra, sizeof(candidate.extra), message.extra);
        copy_text(candidate.grid, sizeof(candidate.grid), message.maidenGrid);
        candidate.call_to_hash10 = message.call_to_hash.hash10;
        candidate.call_to_hash12 = message.call_to_hash.hash12;
        candidate.call_to_hash22 = message.call_to_hash.hash22;
        candidate.call_de_hash10 = message.call_de_hash.hash10;
        candidate.call_de_hash12 = message.call_de_hash.hash12;
        candidate.call_de_hash22 = message.call_de_hash.hash22;
    }

    for (index = 0; index < decoder->result_count; ++index) {
        if (same_result(&candidate, &decoder->results[index])) {
            if (candidate.snr > decoder->results[index].snr) decoder->results[index] = candidate;
            return;
        }
    }
    if (decoder->result_count < FTX_MAX_DECODE_RESULTS) {
        decoder->results[decoder->result_count++] = candidate;
    }
}

static int run_round(ftx_decoder_t *decoder, const float *samples, int count, const char *his_call) {
    int bridge_count;
    ft4_bridge_result_t bridge_result;
    ft4_bridge_reset(decoder->bridge_handle, decoder->utc_time, count);
    ft4_bridge_set_options(decoder->bridge_handle,
                           clamp_int(decoder->options.decode_pass_count, 1, 3),
                           decoder->input_context.qso_frequency_hz);
    ft4_bridge_set_hints(decoder->bridge_handle, decoder->hints.my_call, his_call);
    bridge_count = ft4_bridge_process_float(decoder->bridge_handle, samples, count);
    if (bridge_count < 0) return -1;
    for (int index = 0; index < bridge_count; ++index) {
        if (ft4_bridge_get_result(decoder->bridge_handle, index, &bridge_result) != 0) {
            fill_result(decoder, &bridge_result);
        }
    }
    return bridge_count;
}

ftx_decoder_t *ftx_decoder_create(ftx_mode_t mode,
                                  int sample_rate,
                                  int num_samples,
                                  long long utc_time) {
    ftx_decoder_t *decoder;
    if (mode != FTX_MODE_FT4 || sample_rate != 12000 || num_samples != 90000) return NULL;
    decoder = calloc(1, sizeof(*decoder));
    if (decoder == NULL) return NULL;
    decoder->sample_rate = sample_rate;
    decoder->num_samples = num_samples;
    decoder->utc_time = utc_time;
    load_default_options(&decoder->options);
    load_default_input_context(&decoder->input_context);
    pthread_mutex_lock(&g_ft4_core_mutex);
    decoder->bridge_handle = ft4_bridge_create(sample_rate, num_samples, utc_time);
    pthread_mutex_unlock(&g_ft4_core_mutex);
    if (decoder->bridge_handle <= 0) {
        free(decoder);
        return NULL;
    }
    return decoder;
}

void ftx_decoder_destroy(ftx_decoder_t *decoder) {
    if (decoder == NULL) return;
    pthread_mutex_lock(&g_ft4_core_mutex);
    ft4_bridge_destroy(decoder->bridge_handle);
    pthread_mutex_unlock(&g_ft4_core_mutex);
    free(decoder);
}

int ftx_decoder_set_options(ftx_decoder_t *decoder, const ftx_decoder_options_t *options) {
    if (decoder == NULL || options == NULL) return -1;
    decoder->options = *options;
    decoder->options.decode_pass_count = clamp_int(options->decode_pass_count, 1, 3);
    decoder->options.multi_decode_round_count = clamp_int(options->multi_decode_round_count, 1, 3);
    return 0;
}

int ftx_decoder_set_input_context(ftx_decoder_t *decoder,
                                  const ftx_decoder_input_context_t *input_context) {
    if (decoder == NULL || input_context == NULL || input_context->source_sample_rate != 12000) return -1;
    decoder->input_context = *input_context;
    decoder->input_context.qso_frequency_hz = clamp_int(input_context->qso_frequency_hz, 0, 3000);
    return 0;
}

int ftx_decoder_set_ap_hints(ftx_decoder_t *decoder,
                             const char *my_call,
                             const char **hint_calls,
                             const char **hint_grids,
                             int hint_count) {
    (void) hint_grids;
    if (decoder == NULL) return -1;
    memset(&decoder->hints, 0, sizeof(decoder->hints));
    copy_text(decoder->hints.my_call, sizeof(decoder->hints.my_call), my_call);
    hint_count = clamp_int(hint_count, 0, FTX_MAX_HINT_CALLS);
    for (int index = 0; index < hint_count; ++index) {
        if (hint_calls != NULL && hint_calls[index] != NULL && hint_calls[index][0] != '\0') {
            copy_text(decoder->hints.hint_calls[decoder->hints.hint_call_count],
                      sizeof(decoder->hints.hint_calls[0]), hint_calls[index]);
            decoder->hints.hint_call_count++;
        }
    }
    return 0;
}

int ftx_decoder_process_float(ftx_decoder_t *decoder, const float *samples, int sample_count) {
    if (decoder == NULL) return -1;
    return ftx_decoder_process_float_slot(decoder, samples, sample_count, decoder->utc_time);
}

int ftx_decoder_process_float_slot(ftx_decoder_t *decoder,
                                   const float *samples,
                                   int sample_count,
                                   long long utc_time) {
    int round_count;
    if (decoder == NULL || samples == NULL || sample_count != 90000) return -1;
    decoder->utc_time = utc_time;
    decoder->result_count = 0;
    round_count = clamp_int(decoder->options.multi_decode_round_count, 1, 3);
    pthread_mutex_lock(&g_ft4_core_mutex);
    if (run_round(decoder, samples, sample_count, "") < 0) {
        pthread_mutex_unlock(&g_ft4_core_mutex);
        return -1;
    }
    for (int round = 1; round < round_count && round <= decoder->hints.hint_call_count; ++round) {
        if (run_round(decoder, samples, sample_count, decoder->hints.hint_calls[round - 1]) < 0) {
            pthread_mutex_unlock(&g_ft4_core_mutex);
            return -1;
        }
    }
    pthread_mutex_unlock(&g_ft4_core_mutex);
    return decoder->result_count;
}

int ftx_decoder_get_result_count(const ftx_decoder_t *decoder) {
    return decoder == NULL ? -1 : decoder->result_count;
}

int ftx_decoder_get_result(const ftx_decoder_t *decoder, int index, ftx_decode_result_t *out) {
    if (decoder == NULL || out == NULL || index < 0 || index >= decoder->result_count) return -1;
    *out = decoder->results[index];
    return 0;
}
