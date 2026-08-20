#include "../ft4_bridge.h"

/* 正式版本不启用原生 phase tracing；这些入口只满足 FT8CN 的诊断钩子。 */
int wsjtx3_ldpc_trace_is_enabled(void) {
    return 0;
}

int wsjtx3_osd_trace_is_enabled(void) {
    return 0;
}

void wsjtx3_ldpc_trace_add(int bp_iterations,
                           int osd_calls,
                           int bp_success,
                           int osd_success,
                           long long total_us,
                           long long setup_us,
                           long long bp_llr_syndrome_us,
                           long long bp_bit_to_check_us,
                           long long bp_check_to_var_us,
                           long long osd_us) {
    (void) bp_iterations;
    (void) osd_calls;
    (void) bp_success;
    (void) osd_success;
    (void) total_us;
    (void) setup_us;
    (void) bp_llr_syndrome_us;
    (void) bp_bit_to_check_us;
    (void) bp_check_to_var_us;
    (void) osd_us;
}

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
                          long long validation_us) {
    (void) success;
    (void) total_us;
    (void) allocation_init_us;
    (void) generator_init_us;
    (void) input_prepare_us;
    (void) sort_us;
    (void) matrix_copy_us;
    (void) gaussian_elim_us;
    (void) matrix_permute_us;
    (void) order0_us;
    (void) order1_search_us;
    (void) higher_order_search_us;
    (void) second_preprocess_us;
    (void) validation_us;
}
