#include "encode.h"
#include "constants.h"
#include "crc.h"

#include <stdio.h>
#include "../common/debug.h"
// 如果 x 中有奇数个比特被置位，则返回 1，否则返回 0
static uint8_t parity8(uint8_t x)
{
    x ^= x >> 4;  // a b c d ae bf cg dh
    x ^= x >> 2;  // a b ac bd cae dbf aecg bfdh
    x ^= x >> 1;  // a ab bac acbd bdcae caedbf aecgbfdh
    return x % 2; // 模 2
}

// 通过 LDPC 对 91 位消息进行编码，并返回 174 位的码字。
// 生成矩阵的维度为 (87,87)。
// 该码是一个 (174,91) 正规 LDPC 码，列权重为 3。
// 参数：
// [IN] message   - 存储为 12 字节的 91 位消息（最高有效位在前）
// [OUT] codeword - 存储为 22 字节的 174 位码字（最高有效位在前）
void ftx_encode_174(const uint8_t* message, uint8_t* codeword)
{
    // AP-lite 重用相同的 LDPC 编码器，而不是维护第二个码字路径。
    // 此实现直接从 kFTXLDPCGenerator 中的打包二进制表示访问生成位。

    // 用消息和零填充码字，因为我们稍后只会更新二进制的“1”
    for (int j = 0; j < FTX_LDPC_N_BYTES; ++j)
    {
        codeword[j] = (j < FTX_LDPC_K_BYTES) ? message[j] : 0;
    }

    // 计算第一个校验位的字节索引和位掩码
    uint8_t col_mask = (0x80u >> (FTX_LDPC_K % 8u)); // 当前字节的位掩码
    uint8_t col_idx = FTX_LDPC_K_BYTES - 1;          // 字节数组中的索引

    // 计算 LDPC 校验位并将其存储在 codeword 中
    for (int i = 0; i < FTX_LDPC_M; ++i)
    {
        // 位乘法和奇偶校验的快速实现
        // 通常 nsum 将包含消息与 kFTXLDPCGenerator[i] 之间点积的结果，
        // 但我们只计算模 2 的和。
        uint8_t nsum = 0;
        for (int j = 0; j < FTX_LDPC_K_BYTES; ++j)
        {
            uint8_t bits = message[j] & kFTXLDPCGenerator[i][j]; // 按位与（按位乘法）
            nsum ^= parity8(bits);                                 // 按位异或（模 2 加法）
        }

        // 如果 nsum 为奇数，则在 codeword 中设置当前校验位
        if (nsum % 2)
        {
            codeword[col_idx] |= col_mask;
        }

        // 更新下一个校验位的字节索引和位掩码
        col_mask >>= 1;
        if (col_mask == 0)
        {
            col_mask = 0x80u;
            ++col_idx;
        }
    }
}

void ft8_encode(const uint8_t* payload, uint8_t* tones)
{
    uint8_t a91[FTX_LDPC_K_BYTES]; // 存储 77 位有效载荷 + 14 位 CRC

    // 在消息末尾计算并添加 CRC
    // a91 包含 77 位有效载荷 + 14 位 CRC
    ftx_add_crc(payload, a91);

    uint8_t codeword[FTX_LDPC_N_BYTES];
    ftx_encode_174(a91, codeword);



    // 消息结构：S7 D29 S7 D29 S7
    // 总符号数：79 (FT8_NN)

    uint8_t mask = 0x80u; // 用于从码字中提取 1 位的掩码
    int i_byte = 0;       // 码字当前字节的索引
    for (int i_tone = 0; i_tone < FT8_NN; ++i_tone)
    {
        if ((i_tone >= 0) && (i_tone < 7))
        {
            tones[i_tone] = kFT8CostasPattern[i_tone];
        }
        else if ((i_tone >= 36) && (i_tone < 43))
        {
            tones[i_tone] = kFT8CostasPattern[i_tone - 36];
        }
        else if ((i_tone >= 72) && (i_tone < 79))
        {
            tones[i_tone] = kFT8CostasPattern[i_tone - 72];
        }
        else
        {
            // 在第 i 个位置从码字中提取 3 位
            uint8_t bits3 = 0;

            if (codeword[i_byte] & mask)
                bits3 |= 4;
            if (0 == (mask >>= 1))
            {
                mask = 0x80u;
                i_byte++;
            }
            if (codeword[i_byte] & mask)
                bits3 |= 2;
            if (0 == (mask >>= 1))
            {
                mask = 0x80u;
                i_byte++;
            }
            if (codeword[i_byte] & mask)
                bits3 |= 1;
            if (0 == (mask >>= 1))
            {
                mask = 0x80u;
                i_byte++;
            }

            tones[i_tone] = kFT8GrayMap[bits3];
        }
    }
}

void ft4_encode(const uint8_t* payload, uint8_t* tones)
{
    uint8_t a91[FTX_LDPC_K_BYTES]; // 存储 77 位有效载荷 + 14 位 CRC
    uint8_t payload_xor[10];       // 编码后的有效载荷数据

    // “[..] 仅对于 FT4，为了避免在发送 CQ 消息时传输一长串零，
    // 在计算 CRC 和 FEC 奇偶校验位之前，将组装好的 77 位消息与 [一个] 伪随机序列进行按位异或运算”
    for (int i = 0; i < 10; ++i)
    {
        payload_xor[i] = payload[i] ^ kFT4XORSequence[i];
    }

    // 在消息末尾计算并添加 CRC
    // a91 包含 77 位有效载荷 + 14 位 CRC
    ftx_add_crc(payload_xor, a91);

    uint8_t codeword[FTX_LDPC_N_BYTES];
    ftx_encode_174(a91, codeword); // 91 位 -> 174 位

    // 消息结构：R S4_1 D29 S4_2 D29 S4_3 D29 S4_4 R
    // 总符号数：105 (FT4_NN)

    uint8_t mask = 0x80u; // 用于从码字中提取 1 位的掩码
    int i_byte = 0;       // 码字当前字节的索引
    for (int i_tone = 0; i_tone < FT4_NN; ++i_tone)
    {
        if ((i_tone == 0) || (i_tone == 104))
        {
            tones[i_tone] = 0; // R (渐变) 符号
        }
        else if ((i_tone >= 1) && (i_tone < 5))
        {
            tones[i_tone] = kFT4CostasPattern[0][i_tone - 1];
        }
        else if ((i_tone >= 34) && (i_tone < 38))
        {
            tones[i_tone] = kFT4CostasPattern[1][i_tone - 34];
        }
        else if ((i_tone >= 67) && (i_tone < 71))
        {
            tones[i_tone] = kFT4CostasPattern[2][i_tone - 67];
        }
        else if ((i_tone >= 100) && (i_tone < 104))
        {
            tones[i_tone] = kFT4CostasPattern[3][i_tone - 100];
        }
        else
        {
            // 在第 i 个位置从码字中提取 2 位
            uint8_t bits2 = 0;

            if (codeword[i_byte] & mask)
                bits2 |= 2;
            if (0 == (mask >>= 1))
            {
                mask = 0x80u;
                i_byte++;
            }
            if (codeword[i_byte] & mask)
                bits2 |= 1;
            if (0 == (mask >>= 1))
            {
                mask = 0x80u;
                i_byte++;
            }
            tones[i_tone] = kFT4GrayMap[bits2];
        }
    }
}
