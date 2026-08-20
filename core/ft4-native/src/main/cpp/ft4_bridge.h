#ifndef LOOK4SAT_FT4_BRIDGE_H
#define LOOK4SAT_FT4_BRIDGE_H

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    int snr;
    int nap;
    float sync;
    float dt;
    float freq;
    float qual;
    char decoded[38];
} ft4_bridge_result_t;

int ft4_bridge_create(int sample_rate, int expected_samples, long long utc_millis);
void ft4_bridge_destroy(int handle);
void ft4_bridge_reset(int handle, long long utc_millis, int expected_samples);
void ft4_bridge_set_options(int handle, int decode_depth, int qso_frequency_hz);
void ft4_bridge_set_hints(int handle, const char *my_call, const char *his_call);
int ft4_bridge_process_float(int handle, const float *samples, int sample_count);
int ft4_bridge_get_result_count(int handle);
int ft4_bridge_get_result(int handle, int index, ft4_bridge_result_t *result);
int ft4_bridge_generate_tones(const char *message, int *tones, int capacity);
int ft4_bridge_generate_wave(const char *message,
                             int sample_rate,
                             float audio_frequency_hz,
                             float *wave,
                             int capacity);

/* FT8CN 对 WSJT-X LDPC/OSD 路径加入的诊断钩子，正式版本固定关闭。 */
int wsjtx3_ldpc_trace_is_enabled(void);
int wsjtx3_osd_trace_is_enabled(void);
void wsjtx3_ldpc_trace_add(int bp_iterations,
                           int osd_calls,
                           int bp_success,
                           int osd_success,
                           long long total_us,
                           long long setup_us,
                           long long bp_llr_syndrome_us,
                           long long bp_bit_to_check_us,
                           long long bp_check_to_var_us,
                           long long osd_us);
void wsjtx3_osd_trace_add(int success,
                          long long total_us,
                          long long allocation_init_us,
                          long long generator_init_us,
                          long long input_prepare_us,
                          long long sort_us,
                          long long matrix_copy_us,
                          long long gaussian_elim_us,
                          long long matrix_permute_us,
                          long long order0_us,
                          long long order1_search_us,
                          long long higher_order_search_us,
                          long long second_preprocess_us,
                          long long validation_us);

#ifdef __cplusplus
}
#endif

#endif
