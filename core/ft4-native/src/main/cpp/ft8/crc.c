#include "crc.h"
#include "constants.h"

#define TOPBIT (1u << (FT8_CRC_WIDTH - 1))

// 为给定位数的序列计算 14 位 CRC
// 改编自 https://barrgroup.com/Embedded-Systems/How-To/CRC-Calculation-C-Code
// [IN] message  - 字节序列（最高有效位在前）
// [IN] num_bits - 序列中的位数
uint16_t ftx_compute_crc(const uint8_t message[], int num_bits)
{
    uint16_t remainder = 0;
    int idx_byte = 0;

    // 每次执行一位模 2 除法。
    for (int idx_bit = 0; idx_bit < num_bits; ++idx_bit)
    {
        if (idx_bit % 8 == 0)
        {
            // 将下一个字节带入余数中。
            remainder ^= (message[idx_byte] << (FT8_CRC_WIDTH - 8));
            ++idx_byte;
        }

        // 尝试除以当前数据位。
        if (remainder & TOPBIT)
        {
            remainder = (remainder << 1) ^ FT8_CRC_POLYNOMIAL;
        }
        else
        {
            remainder = (remainder << 1);
        }
    }

    return remainder & ((TOPBIT << 1) - 1u);
}

uint16_t ftx_extract_crc(const uint8_t a91[])
{
    uint16_t chksum = ((a91[9] & 0x07) << 11) | (a91[10] << 3) | (a91[11] >> 5);
    return chksum;
}

void ftx_add_crc(const uint8_t payload[], uint8_t a91[])
{
    // 复制 77 位有效载荷数据
    for (int i = 0; i < 10; i++)
        a91[i] = payload[i];

    // 清除有效载荷后的 3 位，使其变为 82 位
    a91[9] &= 0xF8u;
    a91[10] = 0;

    // 计算 82 位（77 + 5 个零）的 CRC
    // “CRC 是在源编码消息上计算的，从 77 位零扩展到 82 位”
    uint16_t checksum = ftx_compute_crc(a91, 96 - 14);

    // 将 CRC 存储在 77 位消息的末尾
    a91[9] |= (uint8_t)(checksum >> 11);
    a91[10] = (uint8_t)(checksum >> 3);
    a91[11] = (uint8_t)(checksum << 5);
}
