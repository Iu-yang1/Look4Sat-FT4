#include <stdint.h>
#include <string.h>

enum {
    kOsdRows = 91,
    kOsdColumns = 174,
    kPackedWords = 3
};

/*
 * Fortran 的 matrix(k, n) 按列连续存储。这里仅改变临时数据布局，
 * pivot 搜索、列交换和 GF(2) 行消元顺序与原实现保持一致。
 */
int wsjtx3_osd_gaussian_eliminate(int8_t *matrix,
                                   int k,
                                   int n,
                                   int *indices) {
    uint64_t rows[kOsdRows][kPackedWords] = {{0}};
    int row;
    int column;
    int diagonal;

    if (matrix == 0 || indices == 0 || k != kOsdRows || n != kOsdColumns) {
        return 0;
    }

    for (column = 0; column < n; ++column) {
        const uint64_t mask = UINT64_C(1) << (column & 63);
        const int word = column >> 6;
        for (row = 0; row < k; ++row) {
            if (matrix[row + column * k] != 0) {
                rows[row][word] |= mask;
            }
        }
    }

    for (diagonal = 0; diagonal < k; ++diagonal) {
        const int search_end = k + 19;
        int pivot_column;
        for (pivot_column = diagonal; pivot_column <= search_end; ++pivot_column) {
            const uint64_t pivot_mask = UINT64_C(1) << (pivot_column & 63);
            if ((rows[diagonal][pivot_column >> 6] & pivot_mask) == 0) {
                continue;
            }

            if (pivot_column != diagonal) {
                const uint64_t diagonal_mask = UINT64_C(1) << (diagonal & 63);
                const int diagonal_word = diagonal >> 6;
                const int pivot_word = pivot_column >> 6;
                for (row = 0; row < k; ++row) {
                    const int diagonal_bit =
                            (rows[row][diagonal_word] & diagonal_mask) != 0;
                    const int pivot_bit =
                            (rows[row][pivot_word] & pivot_mask) != 0;
                    if (diagonal_bit != pivot_bit) {
                        rows[row][diagonal_word] ^= diagonal_mask;
                        rows[row][pivot_word] ^= pivot_mask;
                    }
                }
                {
                    const int saved_index = indices[diagonal];
                    indices[diagonal] = indices[pivot_column];
                    indices[pivot_column] = saved_index;
                }
            }

            {
                const uint64_t diagonal_mask = UINT64_C(1) << (diagonal & 63);
                const int diagonal_word = diagonal >> 6;
                for (row = 0; row < k; ++row) {
                    if (row != diagonal
                            && (rows[row][diagonal_word] & diagonal_mask) != 0) {
                        rows[row][0] ^= rows[diagonal][0];
                        rows[row][1] ^= rows[diagonal][1];
                        rows[row][2] ^= rows[diagonal][2];
                    }
                }
            }
            break;
        }
    }

    for (column = 0; column < n; ++column) {
        const uint64_t mask = UINT64_C(1) << (column & 63);
        const int word = column >> 6;
        for (row = 0; row < k; ++row) {
            matrix[row + column * k] = (rows[row][word] & mask) != 0 ? 1 : 0;
        }
    }
    return 1;
}

static int packed_bit_count_range(const uint64_t words[kPackedWords],
                                  int begin,
                                  int end) {
    const int bit_count = end - begin;
    const int first_word = begin >> 6;
    const int shift = begin & 63;
    uint64_t value;
    if (bit_count <= 0 || bit_count > 64) {
        int count = 0;
        int index;
        for (index = begin; index < end; ++index) {
            count += (int) ((words[index >> 6] >> (index & 63)) & UINT64_C(1));
        }
        return count;
    }
    value = words[first_word] >> shift;
    if (shift != 0 && first_word + 1 < kPackedWords) {
        value |= words[first_word + 1] << (64 - shift);
    }
    if (bit_count < 64) {
        value &= (UINT64_C(1) << bit_count) - 1;
    }
    return __builtin_popcountll(value);
}

static unsigned int packed_extract_byte(const uint64_t words[kPackedWords], int begin) {
    const int word_index = begin >> 6;
    const int shift = begin & 63;
    uint64_t value = words[word_index] >> shift;
    if (shift > 56 && word_index + 1 < kPackedWords) {
        value |= words[word_index + 1] << (64 - shift);
    }
    return (unsigned int) (value & UINT64_C(0xff));
}

static float packed_weighted_sum_table(const uint64_t words[kPackedWords],
                                       const float table[11][256]) {
    float sum = 0.0f;
    int chunk;
    for (chunk = 0; chunk < 11; ++chunk) {
        sum += table[chunk][packed_extract_byte(words, kOsdRows + chunk * 8)];
    }
    return sum;
}

static void unpack_codeword(const uint64_t words[kPackedWords], int8_t *codeword) {
    int index;
    for (index = 0; index < kOsdColumns; ++index) {
        codeword[index] = (int8_t) ((words[index >> 6] >> (index & 63)) & UINT64_C(1));
    }
}

/*
 * 对官方 order-1 + pre-1 枚举做等价位打包。候选顺序、预筛选常量和
 * 浮点权重累加顺序均保持不变，不减少任何 test pattern。
 */
int wsjtx3_osd_order1_search(const int8_t *c0,
                             const int8_t *g2,
                             const int8_t *hdec,
                             const int8_t *m0,
                             const int8_t *apmask,
                             const float *absrx,
                             int k,
                             int n,
                             int nt,
                             int ntheta,
                             int include_pre1,
                             int8_t *best_codeword,
                             int *best_hard_distance,
                             float *best_weighted_distance) {
    uint64_t generators[kOsdRows][kPackedWords] = {{0}};
    uint64_t hard_words[kPackedWords] = {0};
    uint64_t order0_words[kPackedWords] = {0};
    float parity_weight_table[11][256];
    int base_index;
    int index;

    if (c0 == 0 || g2 == 0 || hdec == 0 || m0 == 0 || apmask == 0
            || absrx == 0 || best_codeword == 0 || best_hard_distance == 0
            || best_weighted_distance == 0 || k != kOsdRows || n != kOsdColumns
            || nt < 1 || nt > n - k || (include_pre1 != 0 && include_pre1 != 1)) {
        return 0;
    }

    for (index = 0; index < n; ++index) {
        const uint64_t mask = UINT64_C(1) << (index & 63);
        const int word = index >> 6;
        int generator_index;
        if (c0[index] != 0) {
            order0_words[word] |= mask;
        }
        if (hdec[index] != 0) {
            hard_words[word] |= mask;
        }
        for (generator_index = 0; generator_index < k; ++generator_index) {
            if (g2[index + generator_index * n] != 0) {
                generators[generator_index][word] |= mask;
            }
        }
    }
    {
        int chunk;
        for (chunk = 0; chunk < 11; ++chunk) {
            int pattern;
            for (pattern = 0; pattern < 256; ++pattern) {
                float sum = 0.0f;
                int bit;
                for (bit = 0; bit < 8; ++bit) {
                    const int weight_index = k + chunk * 8 + bit;
                    if (weight_index < n && (pattern & (1 << bit)) != 0) {
                        sum += absrx[weight_index];
                    }
                }
                parity_weight_table[chunk][pattern] = sum;
            }
        }
    }

    for (base_index = k - 1; base_index >= 0; --base_index) {
        uint64_t base_words[kPackedWords];
        float base_message_distance = 0.0f;
        int extra_index;

        if (apmask[base_index] != 0) {
            continue;
        }
        for (index = 0; index < kPackedWords; ++index) {
            base_words[index] = order0_words[index] ^ generators[base_index][index];
        }
        for (index = 0; index < k; ++index) {
            const int message_bit = m0[index] ^ (index == base_index ? 1 : 0);
            if (message_bit != hdec[index]) {
                base_message_distance += absrx[index];
            }
        }

        for (extra_index = base_index;
             extra_index >= (include_pre1 != 0 ? 0 : base_index);
             --extra_index) {
            uint64_t candidate_words[kPackedWords];
            uint64_t mismatch_words[kPackedWords];
            float distance;
            int parity_errors;

            if (extra_index != base_index && apmask[extra_index] != 0) {
                continue;
            }
            for (index = 0; index < kPackedWords; ++index) {
                candidate_words[index] = extra_index == base_index
                        ? base_words[index]
                        : base_words[index] ^ generators[extra_index][index];
                mismatch_words[index] = candidate_words[index] ^ hard_words[index];
            }

            parity_errors = packed_bit_count_range(mismatch_words, k, k + nt)
                    + (extra_index == base_index ? 1 : 2);
            if (parity_errors > ntheta) {
                continue;
            }

            distance = base_message_distance
                    + packed_weighted_sum_table(mismatch_words, parity_weight_table);
            if (extra_index != base_index
                    && ((candidate_words[extra_index >> 6] >> (extra_index & 63))
                        & UINT64_C(1)) != (uint64_t) hdec[extra_index]) {
                distance += absrx[extra_index];
            }
            if (distance < *best_weighted_distance) {
                *best_weighted_distance = distance;
                *best_hard_distance = packed_bit_count_range(mismatch_words, 0, n);
                unpack_codeword(candidate_words, best_codeword);
            }
        }
    }
    return 1;
}
