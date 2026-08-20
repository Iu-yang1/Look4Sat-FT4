#ifndef FT8CN_FTX_DECODER_H
#define FT8CN_FTX_DECODER_H

#include "ftx_message.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct ftx_decoder ftx_decoder_t;

typedef struct {
    int decode_pass_count;
    int multi_decode_round_count;
    int qso_freq_sensitivity;
    int decode_sensitivity;
    int enable_early_decode;
    int enable_wideband_dx_search;
    int ldpc_iterations;
    int deep_decode_enabled;
} ftx_decoder_options_t;

typedef struct {
    int input_is_live;
    int qso_frequency_hz;
    int tx_frequency_hz;
    int source_sample_rate;
    int decode_stage;
} ftx_decoder_input_context_t;

ftx_decoder_t *ftx_decoder_create(
        ftx_mode_t mode,
        int sample_rate,
        int num_samples,
        long long utc_time
);

void ftx_decoder_destroy(ftx_decoder_t *decoder);

int ftx_decoder_set_options(
        ftx_decoder_t *decoder,
        const ftx_decoder_options_t *options
);

int ftx_decoder_set_input_context(
        ftx_decoder_t *decoder,
        const ftx_decoder_input_context_t *input_context
);

int ftx_decoder_set_ap_hints(
        ftx_decoder_t *decoder,
        const char *my_call,
        const char **hint_calls,
        const char **hint_grids,
        int hint_count
);

int ftx_decoder_process_float(
        ftx_decoder_t *decoder,
        const float *samples,
        int sample_count
);

int ftx_decoder_process_float_slot(
        ftx_decoder_t *decoder,
        const float *samples,
        int sample_count,
        long long utc_time
);

int ftx_decoder_get_result_count(
        const ftx_decoder_t *decoder
);

int ftx_decoder_get_result(
        const ftx_decoder_t *decoder,
        int index,
        ftx_decode_result_t *out
);

#ifdef __cplusplus
}
#endif

#endif
