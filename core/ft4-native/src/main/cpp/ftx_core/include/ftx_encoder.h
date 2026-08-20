#ifndef FT8CN_FTX_ENCODER_H
#define FT8CN_FTX_ENCODER_H

#include "ftx_types.h"

#ifdef __cplusplus
extern "C" {
#endif

int ftx_pack_message(const char *message, uint8_t payload[FTX_PAYLOAD_BYTES]);
int ftx_unpack_message(const uint8_t payload[FTX_PAYLOAD_BYTES], char *message, int message_capacity);
int ftx_get_tone_count(ftx_mode_t mode);
int ftx_encode_tones(ftx_mode_t mode,
                     const uint8_t payload[FTX_PAYLOAD_BYTES],
                     uint8_t *tones,
                     int tone_capacity);
int ftx_build_a91(const uint8_t payload[FTX_PAYLOAD_BYTES], uint8_t a91[FTX_PAYLOAD_BYTES]);
int ftx_check_crc(const uint8_t a91[FTX_PAYLOAD_BYTES]);
int ftx_encode_codeword(const uint8_t a91[FTX_PAYLOAD_BYTES], uint8_t codeword[FTX_CODEWORD_BYTES]);

#ifdef __cplusplus
}
#endif

#endif
