#ifndef FT8CN_FTX_MESSAGE_H
#define FT8CN_FTX_MESSAGE_H

#include "ftx_types.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    long long utc_time;
    int is_valid;
    int snr;
    int score;
    float time_sec;
    float freq_hz;
    int i3;
    int n3;
    char text[FTX_MAX_TEXT_LENGTH];
    char call_to[FTX_MAX_CALLSIGN_LENGTH];
    char call_de[FTX_MAX_CALLSIGN_LENGTH];
    char dx_call_to2[FTX_MAX_CALLSIGN_LENGTH];
    char extra[FTX_MAX_EXTRA_LENGTH];
    char grid[FTX_MAX_GRID_LENGTH];
    int report;
    int r_flag;
    int rtty_tu;
    int eu_serial;
    char rtty_state[FTX_MAX_STATE_LENGTH];
    char arrl_rac[FTX_MAX_RAC_LENGTH];
    char arrl_class[FTX_MAX_CLASS_LENGTH];
    unsigned int call_to_hash10;
    unsigned int call_to_hash12;
    unsigned int call_to_hash22;
    unsigned int call_de_hash10;
    unsigned int call_de_hash12;
    unsigned int call_de_hash22;
    unsigned int message_hash;
} ftx_decode_result_t;

#ifdef __cplusplus
}
#endif

#endif
