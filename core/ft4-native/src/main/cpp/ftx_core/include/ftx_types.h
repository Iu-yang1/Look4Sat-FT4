#ifndef FT8CN_FTX_TYPES_H
#define FT8CN_FTX_TYPES_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define FTX_PAYLOAD_BYTES 12
#define FTX_CODEWORD_BYTES 22
#define FTX_FT8_TONE_COUNT 79
#define FTX_FT4_TONE_COUNT 105
#define FTX_MAX_DECODE_RESULTS 100
#define FTX_MAX_HINT_CALLS 4
#define FTX_MAX_TEXT_LENGTH 96
#define FTX_MAX_CALLSIGN_LENGTH 32
#define FTX_MAX_GRID_LENGTH 8
#define FTX_MAX_EXTRA_LENGTH 24
#define FTX_MAX_STATE_LENGTH 8
#define FTX_MAX_RAC_LENGTH 8
#define FTX_MAX_CLASS_LENGTH 4

typedef enum {
    FTX_MODE_FT4 = 1
} ftx_mode_t;

#ifdef __cplusplus
}
#endif

#endif
