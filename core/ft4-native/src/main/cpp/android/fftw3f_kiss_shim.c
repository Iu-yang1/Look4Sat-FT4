#include "../vendor/wsjtx-3.0.0/lib/wsprd/fftw3.h"
#include "../fft/kiss_fft.h"
#include "../fft/kiss_fftr.h"

#include <stdatomic.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

typedef enum {
    SHIM_PLAN_C2C_FORWARD = 0,
    SHIM_PLAN_C2C_BACKWARD = 1,
    SHIM_PLAN_R2C = 2,
    SHIM_PLAN_C2R = 3
} shim_plan_kind_t;

typedef struct {
    int nfft;
    shim_plan_kind_t kind;
    const void *base_in;
    void *base_out;
    kiss_fft_cfg c2c_cfg;
    kiss_fftr_cfg real_cfg;
    kiss_fft_cpx *complex_out;
    kiss_fft_scalar *real_buffer;
} sfftw_plan_shim_t;

enum {
    SFFTW_MAX_PLAN_HANDLES = 8192
};

static atomic_flag g_plan_lock = ATOMIC_FLAG_INIT;
static sfftw_plan_shim_t *g_plan_registry[SFFTW_MAX_PLAN_HANDLES];

static void plan_lock_acquire(void) {
    while (atomic_flag_test_and_set_explicit(&g_plan_lock, memory_order_acquire)) {
    }
}

static void plan_lock_release(void) {
    atomic_flag_clear_explicit(&g_plan_lock, memory_order_release);
}

static sfftw_plan_shim_t *shim_from_handle_value(intptr_t handle_value) {
    if (handle_value <= 0 || handle_value > SFFTW_MAX_PLAN_HANDLES) {
        return NULL;
    }
    return g_plan_registry[handle_value - 1];
}

static sfftw_plan_shim_t *shim_from_handle(const intptr_t *handle) {
    if (handle == NULL) {
        return NULL;
    }
    return shim_from_handle_value(*handle);
}

static intptr_t register_plan(sfftw_plan_shim_t *plan) {
    intptr_t handle_value;

    if (plan == NULL) {
        return 0;
    }

    for (handle_value = 1; handle_value <= SFFTW_MAX_PLAN_HANDLES; ++handle_value) {
        if (g_plan_registry[handle_value - 1] == NULL) {
            g_plan_registry[handle_value - 1] = plan;
            return handle_value;
        }
    }

    return 0;
}

static void free_plan(sfftw_plan_shim_t *plan) {
    if (plan == NULL) {
        return;
    }
    kiss_fft_free(plan->c2c_cfg);
    kiss_fftr_free(plan->real_cfg);
    free(plan->complex_out);
    free(plan->real_buffer);
    free(plan);
}

static sfftw_plan_shim_t *allocate_plan(int nfft,
                                        shim_plan_kind_t kind,
                                        void *base_in,
                                        void *base_out) {
    sfftw_plan_shim_t *plan = (sfftw_plan_shim_t *) calloc(1, sizeof(*plan));
    if (plan == NULL) {
        return NULL;
    }

    plan->nfft = nfft;
    plan->kind = kind;
    plan->base_in = base_in;
    plan->base_out = base_out;

    switch (kind) {
        case SHIM_PLAN_C2C_FORWARD:
        case SHIM_PLAN_C2C_BACKWARD:
            plan->c2c_cfg = kiss_fft_alloc(nfft,
                                           kind == SHIM_PLAN_C2C_BACKWARD,
                                           NULL,
                                           NULL);
            plan->complex_out = (kiss_fft_cpx *) calloc((size_t) nfft, sizeof(kiss_fft_cpx));
            if (plan->c2c_cfg == NULL || plan->complex_out == NULL) {
                free_plan(plan);
                return NULL;
            }
            break;

        case SHIM_PLAN_R2C:
            plan->real_cfg = kiss_fftr_alloc(nfft, 0, NULL, NULL);
            plan->complex_out = (kiss_fft_cpx *) calloc((size_t) (nfft / 2 + 1), sizeof(kiss_fft_cpx));
            if (plan->real_cfg == NULL || plan->complex_out == NULL) {
                free_plan(plan);
                return NULL;
            }
            break;

        case SHIM_PLAN_C2R:
            plan->real_cfg = kiss_fftr_alloc(nfft, 1, NULL, NULL);
            plan->real_buffer = (kiss_fft_scalar *) calloc((size_t) nfft, sizeof(kiss_fft_scalar));
            plan->complex_out = (kiss_fft_cpx *) calloc((size_t) (nfft / 2 + 1), sizeof(kiss_fft_cpx));
            if (plan->real_cfg == NULL || plan->real_buffer == NULL || plan->complex_out == NULL) {
                free_plan(plan);
                return NULL;
            }
            break;
    }

    return plan;
}

void sfftw_plan_dft_1d_(intptr_t *plan_handle,
                        const int *nfft,
                        void *in,
                        void *out,
                        const int *sign,
                        const int *flags) {
    const shim_plan_kind_t kind =
            (sign != NULL && *sign >= 0) ? SHIM_PLAN_C2C_BACKWARD : SHIM_PLAN_C2C_FORWARD;
    (void) flags;

    if (plan_handle == NULL || nfft == NULL || *nfft <= 0) {
        return;
    }

    sfftw_plan_shim_t *plan = allocate_plan(*nfft, kind, in, out);
    intptr_t handle_value = 0;
    if (plan == NULL) {
        *plan_handle = 0;
        return;
    }

    plan_lock_acquire();
    handle_value = register_plan(plan);
    plan_lock_release();
    if (handle_value == 0) {
        free_plan(plan);
        *plan_handle = 0;
        return;
    }

    *plan_handle = handle_value;
}

void sfftw_plan_dft_r2c_1d_(intptr_t *plan_handle,
                            const int *nfft,
                            void *in,
                            void *out,
                            const int *flags) {
    (void) flags;
    if (plan_handle == NULL || nfft == NULL || *nfft <= 0) {
        return;
    }

    sfftw_plan_shim_t *plan = allocate_plan(*nfft, SHIM_PLAN_R2C, in, out);
    intptr_t handle_value = 0;
    if (plan == NULL) {
        *plan_handle = 0;
        return;
    }

    plan_lock_acquire();
    handle_value = register_plan(plan);
    plan_lock_release();
    if (handle_value == 0) {
        free_plan(plan);
        *plan_handle = 0;
        return;
    }

    *plan_handle = handle_value;
}

void sfftw_plan_dft_c2r_1d_(intptr_t *plan_handle,
                            const int *nfft,
                            void *in,
                            void *out,
                            const int *flags) {
    (void) flags;
    if (plan_handle == NULL || nfft == NULL || *nfft <= 0) {
        return;
    }

    sfftw_plan_shim_t *plan = allocate_plan(*nfft, SHIM_PLAN_C2R, in, out);
    intptr_t handle_value = 0;
    if (plan == NULL) {
        *plan_handle = 0;
        return;
    }

    plan_lock_acquire();
    handle_value = register_plan(plan);
    plan_lock_release();
    if (handle_value == 0) {
        free_plan(plan);
        *plan_handle = 0;
        return;
    }

    *plan_handle = handle_value;
}

void sfftw_execute_(intptr_t *plan_handle) {
    sfftw_plan_shim_t *plan;

    plan_lock_acquire();
    plan = shim_from_handle(plan_handle);
    if (plan == NULL) {
        plan_lock_release();
        return;
    }

    switch (plan->kind) {
        case SHIM_PLAN_C2C_FORWARD:
        case SHIM_PLAN_C2C_BACKWARD:
            // KISS FFT 不修改输入；直接读取 Fortran 缓冲区，避免每次大 FFT 前的完整复制。
            kiss_fft(plan->c2c_cfg,
                     (const kiss_fft_cpx *) plan->base_in,
                     plan->complex_out);
            memcpy(plan->base_out,
                   plan->complex_out,
                   (size_t) plan->nfft * sizeof(kiss_fft_cpx));
            break;

        case SHIM_PLAN_R2C:
            kiss_fftr(plan->real_cfg,
                      (const kiss_fft_scalar *) plan->base_in,
                      plan->complex_out);
            memcpy(plan->base_out,
                   plan->complex_out,
                   (size_t) (plan->nfft / 2 + 1) * sizeof(kiss_fft_cpx));
            break;

        case SHIM_PLAN_C2R:
            kiss_fftri(plan->real_cfg,
                       (const kiss_fft_cpx *) plan->base_in,
                       plan->real_buffer);
            memcpy(plan->base_out,
                   plan->real_buffer,
                   (size_t) plan->nfft * sizeof(kiss_fft_scalar));
            break;
    }
    plan_lock_release();
}

void sfftw_destroy_plan_(intptr_t *plan_handle) {
    intptr_t handle_value;
    sfftw_plan_shim_t *plan;

    if (plan_handle == NULL) {
        return;
    }

    handle_value = *plan_handle;
    plan_lock_acquire();
    plan = shim_from_handle_value(handle_value);
    if (plan == NULL) {
        plan_lock_release();
        return;
    }
    g_plan_registry[handle_value - 1] = NULL;
    free_plan(plan);
    plan_lock_release();
    *plan_handle = 0;
}
