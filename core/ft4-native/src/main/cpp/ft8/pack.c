#include "pack.h"
#include "text.h"
#include "hash22.h"

#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include "../common/debug.h"

#define NTOKENS  ((uint32_t)2063592L)
#define MAX22    ((uint32_t)4194304L)
#define MAXGRID4 ((uint16_t)32400)
#define INVALID_GRID ((uint16_t)0xFFFFu)

const char A0[] = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ+-./?";
const char A1[] = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
const char A2[] = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
const char A3[] = "0123456789";
const char A4[] = " ABCDEFGHIJKLMNOPQRSTUVWXYZ";
const char A5[] = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ/";

typedef enum
{
    SUFFIX_NONE = 0,
    SUFFIX_R,
    SUFFIX_P
} suffix_kind_t;

static const char *const kStatesProvinces[] = {
        "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "FL", "GA",
        "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME", "MD",
        "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ",
        "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC",
        "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY",
        "NB", "NS", "QC", "ON", "MB", "SK", "AB", "BC", "NWT", "NF",
        "LB", "NU", "YT", "PEI", "DC"
};

static const char *const kArrlSections[] = {
        "AB", "AK", "AL", "AR", "AZ", "BC", "CO", "CT", "DE", "EB",
        "EMA", "ENY", "EPA", "EWA", "GA", "GTA", "IA", "ID", "IL", "IN",
        "KS", "KY", "LA", "LAX", "MAR", "MB", "MDC", "ME", "MI", "MN",
        "MO", "MS", "MT", "NC", "ND", "NE", "NFL", "NH", "NL", "NLI",
        "NM", "NNJ", "NNY", "NT", "NTX", "NV", "OH", "OK", "ONE", "ONN",
        "ONS", "OR", "ORG", "PAC", "PR", "QC", "RI", "SB", "SC", "SCV",
        "SD", "SDG", "SF", "SFL", "SJV", "SK", "SNJ", "STX", "SV", "TN",
        "UT", "VA", "VI", "VT", "WCF", "WI", "WMA", "WNY", "WPA", "WTX",
        "WV", "WWA", "WY", "DX"
};

bool chkcall(const char* call, char* bc);
int32_t pack28(const char* callsign);

static void write_bits_be(uint8_t *data, int start_bit, int bit_count, uint32_t value)
{
    for (int i = 0; i < bit_count; ++i)
    {
        int bit_index = start_bit + i;
        int byte_index = bit_index / 8;
        int bit_in_byte = 7 - (bit_index % 8);
        uint8_t bit = (uint8_t)((value >> (bit_count - 1 - i)) & 0x01u);
        data[byte_index] = (uint8_t)((data[byte_index] & ~(1u << bit_in_byte)) | (bit << bit_in_byte));
    }
}

static int token_length(const char* token)
{
    int length = 0;
    while (token[length] != 0 && token[length] != ' ')
    {
        ++length;
    }
    return length;
}

static void copy_token(char* dst, size_t dst_size, const char* src)
{
    if (dst_size == 0)
        return;

    int length = token_length(src);
    if ((size_t)length >= dst_size)
        length = (int)dst_size - 1;
    memcpy(dst, src, length);
    dst[length] = '\0';
}

static bool token_equals(const char* token, const char* literal)
{
    size_t literal_len = strlen(literal);
    return token_length(token) == (int)literal_len && 0 == memcmp(token, literal, literal_len);
}

static int lookup_literal_index(const char *token, const char *const *table, size_t count)
{
    for (size_t i = 0; i < count; ++i)
    {
        if (0 == strcmp(token, table[i]))
            return (int)i;
    }
    return -1;
}

static bool parse_unsigned_token(const char *token, int *value)
{
    if (token == 0 || token[0] == '\0')
        return false;

    int parsed = 0;
    for (int i = 0; token[i] != '\0'; ++i)
    {
        if (!is_digit(token[i]))
            return false;
        parsed = (parsed * 10) + (token[i] - '0');
    }
    *value = parsed;
    return true;
}

static bool parse_signed_token(const char *token, int *value)
{
    if (token == 0 || token[0] == '\0')
        return false;

    char *end_ptr = 0;
    long parsed = strtol(token, &end_ptr, 10);
    if (end_ptr == token || *end_ptr != '\0')
        return false;

    if (parsed < -99L || parsed > 99L)
        return false;

    *value = (int)parsed;
    return true;
}

static bool is_all_digits(const char* text)
{
    if (text[0] == '\0')
        return false;

    for (int i = 0; text[i] != '\0'; ++i)
    {
        if (!is_digit(text[i]))
            return false;
    }
    return true;
}

static bool is_all_letters(const char* text)
{
    if (text[0] == '\0')
        return false;

    for (int i = 0; text[i] != '\0'; ++i)
    {
        if (!is_letter(text[i]))
            return false;
    }
    return true;
}

static bool is_cq_modifier_token(const char* token)
{
    int length = strlen(token);
    if (length == 3 && is_all_digits(token))
        return true;
    return length >= 1 && length <= 4 && is_all_letters(token);
}

static int32_t pack_cq_modifier(const char* token)
{
    if (strlen(token) == 3 && is_all_digits(token))
    {
        return 3 + atoi(token);
    }

    uint32_t n = 0;
    for (int i = 0; token[i] != '\0'; ++i)
    {
        n = n * 27u + (uint32_t)nchar(token[i], 4);
    }
    return (int32_t)(1003u + n);
}

static bool extract_bracketed_callsign(const char* callsign, char* inner, size_t inner_size)
{
    int length = token_length(callsign);
    if (length < 3 || callsign[0] != '<' || callsign[length - 1] != '>')
        return false;

    int inner_len = length - 2;
    if ((size_t)inner_len >= inner_size)
        inner_len = (int)inner_size - 1;

    memcpy(inner, callsign + 1, inner_len);
    inner[inner_len] = '\0';
    return inner[0] != '\0';
}

static suffix_kind_t get_suffix_kind(const char* callsign)
{
    int length = strlen(callsign);
    if (length < 2)
        return SUFFIX_NONE;
    if (callsign[length - 2] != '/')
        return SUFFIX_NONE;

    char suffix = callsign[length - 1];
    if (suffix == 'R')
        return SUFFIX_R;
    if (suffix == 'P')
        return SUFFIX_P;
    return SUFFIX_NONE;
}

static void strip_supported_suffix(char* dst, size_t dst_size, const char* src)
{
    if (dst_size == 0)
        return;

    int length = strlen(src);
    if (get_suffix_kind(src) != SUFFIX_NONE)
    {
        length -= 2;
    }

    if ((size_t)length >= dst_size)
        length = (int)dst_size - 1;
    memcpy(dst, src, length);
    dst[length] = '\0';
}

static int32_t pack_standard_callsign(const char* callsign)
{
    char c6[6] = { ' ', ' ', ' ', ' ', ' ', ' ' };
    int length = (int)strlen(callsign);

    if (length == 0 || length > 11)
        return -1;

    if (starts_with(callsign, "3DA0") && length <= 7)
    {
        // Work-around for Swaziland prefix: 3DA0XYZ -> 3D0XYZ
        memcpy(c6, "3D0", 3);
        memcpy(c6 + 3, callsign + 4, length - 4);
    }
    else if (starts_with(callsign, "3X") && is_letter(callsign[2]) && length <= 7)
    {
        // Work-around for Guinea prefixes: 3XA0XYZ -> QA0XYZ
        memcpy(c6, "Q", 1);
        memcpy(c6 + 1, callsign + 2, length - 2);
    }
    else
    {
        if (length >= 3 && length <= 6 && is_digit(callsign[2]))
        {
            memcpy(c6, callsign, length);
        }
        else if (length >= 2 && length <= 5 && is_digit(callsign[1]))
        {
            memcpy(c6 + 1, callsign, length);
        }
        else
        {
            return -1;
        }
    }

    int i0, i1, i2, i3, i4, i5;
    if ((i0 = char_index(A1, c6[0])) < 0 || (i1 = char_index(A2, c6[1])) < 0 ||
        (i2 = char_index(A3, c6[2])) < 0 || (i3 = char_index(A4, c6[3])) < 0 ||
        (i4 = char_index(A4, c6[4])) < 0 || (i5 = char_index(A4, c6[5])) < 0)
    {
        return -1;
    }

    int32_t n28 = i0;
    n28 = n28 * 36 + i1;
    n28 = n28 * 10 + i2;
    n28 = n28 * 27 + i3;
    n28 = n28 * 27 + i4;
    n28 = n28 * 27 + i5;
    return NTOKENS + MAX22 + n28;
}

static int split_message_tokens(const char* msg, char tokens[][32], int max_tokens)
{
    int count = 0;
    while (*msg != '\0')
    {
        while (*msg == ' ')
            ++msg;
        if (*msg == '\0')
            break;

        if (count >= max_tokens)
            return max_tokens + 1;

        int length = token_length(msg);
        if (length >= 31)
            length = 31;
        memcpy(tokens[count], msg, length);
        tokens[count][length] = '\0';
        ++count;
        msg += token_length(msg);
    }
    return count;
}

static bool parse_report_token(const char* token, int* report, bool* has_r)
{
    int idx = 0;
    *has_r = false;

    if (token[idx] == 'R')
    {
        *has_r = true;
        ++idx;
    }

    if (token[idx] == '+' || token[idx] == '-')
    {
        ++idx;
    }

    int digits = 0;
    while (token[idx + digits] != '\0')
    {
        if (!is_digit(token[idx + digits]))
            return false;
        ++digits;
    }

    if (digits == 0 || digits > 2)
        return false;

    *report = dd_to_int(token + ((*has_r) ? 1 : 0), (int)strlen(token) - ((*has_r) ? 1 : 0));
    return *report >= -30 && *report <= 32;
}

static bool pack_nonstandard_call(const char* callsign, uint64_t* n58)
{
    int length = strlen(callsign);
    if (length == 0 || length > 11)
        return false;

    *n58 = 0;
    for (int i = 0; i < length; ++i)
    {
        int idx = nchar(callsign[i], 5);
        if (idx < 0)
            return false;
        *n58 = (*n58 * 38u) + (uint64_t)idx;
    }
    return true;
}

static int pack77_telemetry(const char* msg, uint8_t* c77)
{
    int length = strlen(msg);
    if (length != 18)
        return -1;

    uint8_t payload71[9];
    for (int i = 0; i < 9; ++i)
    {
        int hi = nchar(msg[i * 2], 0);
        int lo = nchar(msg[i * 2 + 1], 0);
        if (hi < 0 || hi > 15 || lo < 0 || lo > 15)
            return -1;
        payload71[i] = (uint8_t)((hi << 4) | lo);
    }

    uint8_t carry = 0;
    for (int i = 8; i >= 0; --i)
    {
        uint8_t next_carry = (payload71[i] & 0x80) ? 1 : 0;
        c77[i] = (uint8_t)((payload71[i] << 1) | carry);
        carry = next_carry;
    }

    c77[8] = (uint8_t)((c77[8] & 0xFE) | 0x01); // n3 bit2
    c77[9] = 0x40;                              // n3 bit1..0 = 01, i3 = 0
    return 0;
}

static int pack77_4(const char* msg, uint8_t* c77)
{
    char tokens[3][32];
    int count = split_message_tokens(msg, tokens, 3);
    if (count < 2 || count > 3)
        return -1;

    const char* call_to = tokens[0];
    const char* call_de = tokens[1];
    const char* extra = (count == 3) ? tokens[2] : "";

    char call_to_inner[32];
    if (extract_bracketed_callsign(call_to, call_to_inner, sizeof(call_to_inner)))
    {
        call_to = call_to_inner;
    }

    char call_de_inner[32];
    if (extract_bracketed_callsign(call_de, call_de_inner, sizeof(call_de_inner)))
    {
        call_de = call_de_inner;
    }

    char call_de_base[32];
    strip_supported_suffix(call_de_base, sizeof(call_de_base), call_de);
    if (pack_standard_callsign(call_de_base) >= 0)
        return -1;

    uint64_t n58;
    if (!pack_nonstandard_call(call_de_base, &n58))
        return -1;

    uint16_t hash12 = 0;
    bool is_cq = token_equals(call_to, "CQ");
    if (is_cq)
    {
        hash12 = (uint16_t)hashcall_12(call_de_base);
    }
    else
    {
        char bc[16];
        if (!chkcall(call_to, bc))
            return -1;
        hash12 = (uint16_t)hashcall_12((char*)call_to);
    }

    uint8_t nrpt = 0;
    if (extra[0] != '\0')
    {
        if (equals(extra, "RRR"))
            nrpt = 1;
        else if (equals(extra, "RR73"))
            nrpt = 2;
        else if (equals(extra, "73"))
            nrpt = 3;
        else
            return -1;
    }

    memset(c77, 0, 10);
    c77[0] = (uint8_t)((hash12 & 0x0FFF) >> 4);
    c77[1] = (uint8_t)(((hash12 & 0x000F) << 4) | ((n58 >> 54) & 0x0F));
    c77[2] = (uint8_t)((n58 >> 46) & 0xFF);
    c77[3] = (uint8_t)((n58 >> 38) & 0xFF);
    c77[4] = (uint8_t)((n58 >> 30) & 0xFF);
    c77[5] = (uint8_t)((n58 >> 22) & 0xFF);
    c77[6] = (uint8_t)((n58 >> 14) & 0xFF);
    c77[7] = (uint8_t)((n58 >> 6) & 0xFF);
    c77[8] = (uint8_t)(((n58 & 0x3F) << 2));
    c77[8] = (uint8_t)(c77[8] | ((0u & 0x01u) << 1) | ((nrpt >> 1) & 0x01u));
    c77[9] = (uint8_t)(((nrpt & 0x01u) << 7) | ((is_cq ? 1u : 0u) << 6) | (4u << 3));
    return 0;
}

static void pack_type0_payload71(const uint8_t *payload71, uint8_t n3, uint8_t *c77)
{
    uint8_t carry = 0;
    memset(c77, 0, 10);
    for (int i = 8; i >= 0; --i)
    {
        uint8_t next_carry = (payload71[i] & 0x80) ? 1 : 0;
        c77[i] = (uint8_t)((payload71[i] << 1) | carry);
        carry = next_carry;
    }

    c77[8] = (uint8_t)((c77[8] & 0xFEu) | ((n3 >> 2) & 0x01u));
    c77[9] = (uint8_t)(((n3 & 0x03u) << 6) | (0u << 3));
}

static bool parse_field_day_exchange(const char *token, int *serial, uint8_t *klass)
{
    int length = (int)strlen(token);
    if (length < 2 || length > 3)
        return false;

    char class_char = token[length - 1];
    if (!in_range(class_char, 'A', 'F'))
        return false;

    char digits[4];
    memcpy(digits, token, (size_t)length - 1u);
    digits[length - 1] = '\0';

    int parsed_serial = 0;
    if (!parse_unsigned_token(digits, &parsed_serial))
        return false;
    if (parsed_serial < 1 || parsed_serial > 32)
        return false;

    *serial = parsed_serial;
    *klass = (uint8_t)(class_char - 'A');
    return true;
}

static bool parse_rtty_report_token(const char *token, uint8_t *r3)
{
    int report = 0;
    if (!parse_unsigned_token(token, &report))
        return false;
    if (report < 529 || report > 599 || ((report - 529) % 10) != 0)
        return false;

    *r3 = (uint8_t)((report - 529) / 10);
    return true;
}

static bool parse_euvhf_report_serial_token(const char *token, uint8_t *r3, uint16_t *serial)
{
    int length = (int)strlen(token);
    if (length < 3 || length > 6)
        return false;

    char report_text[3];
    report_text[0] = token[0];
    report_text[1] = token[1];
    report_text[2] = '\0';

    int report = 0;
    if (!parse_unsigned_token(report_text, &report))
        return false;
    if (report < 52 || report > 59)
        return false;

    int parsed_serial = 0;
    if (!parse_unsigned_token(token + 2, &parsed_serial))
        return false;
    if (parsed_serial < 0 || parsed_serial > 2047)
        return false;

    *r3 = (uint8_t)(report - 52);
    *serial = (uint16_t)parsed_serial;
    return true;
}

static bool pack_grid6(const char *grid6, uint32_t *g25)
{
    if (strlen(grid6) != 6)
        return false;
    if (!in_range(grid6[0], 'A', 'R') || !in_range(grid6[1], 'A', 'R'))
        return false;
    if (!is_digit(grid6[2]) || !is_digit(grid6[3]))
        return false;
    if (!in_range(grid6[4], 'A', 'X') || !in_range(grid6[5], 'A', 'X'))
        return false;

    uint32_t value = (uint32_t)(grid6[0] - 'A');
    value = value * 18u + (uint32_t)(grid6[1] - 'A');
    value = value * 10u + (uint32_t)(grid6[2] - '0');
    value = value * 10u + (uint32_t)(grid6[3] - '0');
    value = value * 24u + (uint32_t)(grid6[4] - 'A');
    value = value * 24u + (uint32_t)(grid6[5] - 'A');
    *g25 = value;
    return true;
}

static void normalize_hash_callsign(const char *src, char *dst, size_t dst_size)
{
    char inner[32];
    if (extract_bracketed_callsign(src, inner, sizeof(inner)))
    {
        copy_token(dst, dst_size, inner);
        return;
    }
    copy_token(dst, dst_size, src);
}

static int pack77_dxpedition(const char *msg, uint8_t *c77)
{
    char tokens[5][32];
    int count = split_message_tokens(msg, tokens, 5);
    if (count != 5)
        return -1;

    if (!token_equals(tokens[1], "RR73;"))
        return -1;

    char hashed_call[32];
    if (!extract_bracketed_callsign(tokens[3], hashed_call, sizeof(hashed_call)))
        return -1;

    int report = 0;
    if (!parse_signed_token(tokens[4], &report))
        return -1;

    char base_call_1[16];
    char base_call_2[16];
    if (!chkcall(tokens[0], base_call_1) || !chkcall(tokens[2], base_call_2))
        return -1;
    if (pack_standard_callsign(base_call_1) < 0 || pack_standard_callsign(base_call_2) < 0)
        return -1;

    uint32_t n28a = (uint32_t)pack28(base_call_1);
    uint32_t n28b = (uint32_t)pack28(base_call_2);
    if (((int32_t)n28a < 0) || ((int32_t)n28b < 0))
        return -1;

    int n5 = (report + 30) / 2;
    if (n5 < 0)
        n5 = 0;
    if (n5 > 31)
        n5 = 31;

    uint8_t payload71[9] = {0};
    write_bits_be(payload71, 1, 28, n28a);
    write_bits_be(payload71, 29, 28, n28b);
    write_bits_be(payload71, 57, 10, hashcall_10(hashed_call));
    write_bits_be(payload71, 67, 5, (uint32_t)n5);
    pack_type0_payload71(payload71, 1u, c77);
    return 0;
}

static int pack77_field_day(const char *msg, uint8_t *c77)
{
    char tokens[5][32];
    int count = split_message_tokens(msg, tokens, 5);
    if (count != 4 && count != 5)
        return -1;

    const char *call_to = tokens[0];
    const char *call_de = tokens[1];
    int token_index = 2;
    uint8_t ir = 0;
    if (token_equals(tokens[token_index], "R"))
    {
        ir = 1;
        ++token_index;
    }
    if (count - token_index != 2)
        return -1;

    int serial = 0;
    uint8_t klass = 0;
    if (!parse_field_day_exchange(tokens[token_index], &serial, &klass))
        return -1;

    int section_index = lookup_literal_index(tokens[token_index + 1], kArrlSections,
                                             sizeof(kArrlSections) / sizeof(kArrlSections[0]));
    if (section_index < 0)
        return -1;

    uint32_t n28a = (uint32_t)pack28(call_to);
    uint32_t n28b = (uint32_t)pack28(call_de);
    if (((int32_t)n28a < 0) || ((int32_t)n28b < 0))
        return -1;

    uint8_t n3 = (serial <= 16) ? 3u : 4u;
    uint8_t n4 = (uint8_t)((serial <= 16) ? (serial - 1) : (serial - 17));

    uint8_t payload71[9] = {0};
    write_bits_be(payload71, 1, 28, n28a);
    write_bits_be(payload71, 29, 28, n28b);
    write_bits_be(payload71, 57, 1, ir);
    write_bits_be(payload71, 58, 4, n4);
    write_bits_be(payload71, 62, 3, klass);
    write_bits_be(payload71, 65, 7, (uint32_t)section_index);
    pack_type0_payload71(payload71, n3, c77);
    return 0;
}

static int pack77_rtty(const char *msg, uint8_t *c77)
{
    char tokens[6][32];
    int count = split_message_tokens(msg, tokens, 6);
    if (count < 4 || count > 6)
        return -1;

    int token_index = 0;
    uint8_t tu = 0;
    if (token_equals(tokens[token_index], "TU;"))
    {
        tu = 1;
        ++token_index;
    }

    if (count - token_index < 4 || count - token_index > 5)
        return -1;

    const char *call_to = tokens[token_index++];
    const char *call_de = tokens[token_index++];

    uint8_t ir = 0;
    if (token_equals(tokens[token_index], "R"))
    {
        ir = 1;
        ++token_index;
    }

    if (count - token_index != 2)
        return -1;

    uint8_t r3 = 0;
    if (!parse_rtty_report_token(tokens[token_index], &r3))
        return -1;

    uint16_t s13 = 0;
    int serial = 0;
    if (parse_unsigned_token(tokens[token_index + 1], &serial))
    {
        if (serial < 0 || serial > 7999)
            return -1;
        s13 = (uint16_t)serial;
    }
    else
    {
        int state_index = lookup_literal_index(tokens[token_index + 1], kStatesProvinces,
                                               sizeof(kStatesProvinces) / sizeof(kStatesProvinces[0]));
        if (state_index < 0)
            return -1;
        s13 = (uint16_t)(8001 + state_index);
    }

    uint32_t n28a = (uint32_t)pack28(call_to);
    uint32_t n28b = (uint32_t)pack28(call_de);
    if (((int32_t)n28a < 0) || ((int32_t)n28b < 0))
        return -1;

    memset(c77, 0, 10);
    write_bits_be(c77, 0, 1, tu);
    write_bits_be(c77, 1, 28, n28a);
    write_bits_be(c77, 29, 28, n28b);
    write_bits_be(c77, 57, 1, ir);
    write_bits_be(c77, 58, 3, r3);
    write_bits_be(c77, 61, 13, s13);
    write_bits_be(c77, 74, 3, 3u);
    return 0;
}

static int pack77_euvhf(const char *msg, uint8_t *c77)
{
    char tokens[5][32];
    int count = split_message_tokens(msg, tokens, 5);
    if (count != 4 && count != 5)
        return -1;

    const char *call_to = tokens[0];
    const char *call_de = tokens[1];
    int token_index = 2;
    uint8_t ir = 0;
    if (token_equals(tokens[token_index], "R"))
    {
        ir = 1;
        ++token_index;
    }
    if (count - token_index != 2)
        return -1;

    uint8_t r3 = 0;
    uint16_t serial = 0;
    if (!parse_euvhf_report_serial_token(tokens[token_index], &r3, &serial))
        return -1;

    uint32_t g25 = 0;
    if (!pack_grid6(tokens[token_index + 1], &g25))
        return -1;

    char hash12_call[32];
    char hash22_call[32];
    normalize_hash_callsign(call_to, hash12_call, sizeof(hash12_call));
    normalize_hash_callsign(call_de, hash22_call, sizeof(hash22_call));
    if (hash12_call[0] == '\0' || hash22_call[0] == '\0')
        return -1;

    memset(c77, 0, 10);
    write_bits_be(c77, 0, 12, hashcall_12(hash12_call));
    write_bits_be(c77, 12, 22, hashcall_22(hash22_call));
    write_bits_be(c77, 34, 1, ir);
    write_bits_be(c77, 35, 3, r3);
    write_bits_be(c77, 38, 11, serial);
    write_bits_be(c77, 49, 25, g25);
    write_bits_be(c77, 74, 3, 5u);
    return 0;
}

// Pack a special token, a 22-bit hash code, or a valid base call
// into a 28-bit integer.
int32_t pack28(const char* callsign)
{
    // Check for CQ modifiers before the plain "CQ" token. token_equals()
    // intentionally compares only the first blank-delimited token, so
    // pack28("CQ DX") would otherwise be encoded as ordinary CQ and lose DX.
    if (starts_with(callsign, "CQ_") || starts_with(callsign, "CQ "))
    {
        const char* modifier = callsign + 3;
        char token[8];
        int length = 0;
        while (modifier[length] != '\0' && modifier[length] != ' ' && length < (int)sizeof(token) - 1)
        {
            token[length] = modifier[length];
            ++length;
        }
        token[length] = '\0';
        if (is_cq_modifier_token(token))
        {
            return pack_cq_modifier(token);
        }
    }

    // Check for special tokens.
    if (token_equals(callsign, "DE"))
        return 0;
    if (token_equals(callsign, "QRZ"))
        return 1;
    if (token_equals(callsign, "CQ"))
        return 2;
    if (starts_with(callsign, "DE "))
        return 0;
    if (starts_with(callsign, "QRZ "))
        return 1;

    char bracketed[16];
    if (extract_bracketed_callsign(callsign, bracketed, sizeof(bracketed)))
    {
        return NTOKENS + (int32_t)hashcall_22(bracketed);
    }

    char token[16];
    copy_token(token, sizeof(token), callsign);

    int32_t standard = pack_standard_callsign(token);
    if (standard >= 0)
    {
        return standard;
    }

    char bc[16];
    if (!chkcall(token, bc))
    {
        return -1;
    }

    // Treat any remaining valid compound / nonstandard call as a 22-bit hash token.
    return NTOKENS + (int32_t)hashcall_22(token);
}

// Check if a string could be a valid standard callsign or a valid
// compound callsign.
// Return base call "bc" and a logical "cok" indicator.
// This mirrors the gating rules in WSJT-X chkcall.f90: a valid base call
// must be at most 6 characters, have a digit in position 2 or 3, and have
// a 1-3 letter suffix after that digit. Ordinary words such as "FREE" and
// "TEXT" must fail here so that pack77() can fall back to true free text.
bool chkcall(const char* call, char* bc)
{
    int length = strlen(call); // n1=len_trim(w)
    if (length == 0 || length > 11)
        return false;
    if (0 != strchr(call, '.'))
        return false;
    if (0 != strchr(call, '+'))
        return false;
    if (0 != strchr(call, '-'))
        return false;
    if (0 != strchr(call, '?'))
        return false;
    if (length > 6 && 0 == strchr(call, '/'))
        return false;

    const char* slash = strchr(call, '/');
    const char* base_start = call;
    int base_len = length;

    if (slash != 0)
    {
        int before_len = (int)(slash - call);
        int after_len = length - before_len - 1;
        if (before_len <= 0 || after_len <= 0)
            return false;
        if (before_len > 6 || after_len > 6)
            return false;

        if (before_len <= after_len)
        {
            base_start = slash + 1;
            base_len = after_len;
        }
        else
        {
            base_start = call;
            base_len = before_len;
        }
    }

    if (base_len <= 0 || base_len > 6)
        return false;

    char base[7] = { 0 };
    memcpy(base, base_start, base_len);
    base[base_len] = '\0';

    // One of the first two characters must be a letter.
    if (!is_letter(base[0]) && (base_len < 2 || !is_letter(base[1])))
        return false;

    // Callsigns do not start with Q in WSJT-X chkcall.f90.
    if (base[0] == 'Q')
        return false;

    // Must have a call-area digit in the 2nd or 3rd position.
    int area_index = -1;
    if (base_len >= 2 && is_digit(base[1]))
        area_index = 1;
    if (base_len >= 3 && is_digit(base[2]))
        area_index = 2;
    if (area_index < 0)
        return false;

    // Callsign must have a suffix of 1-3 letters after the area digit.
    if (area_index == base_len - 1)
        return false;

    int suffix_letters = 0;
    for (int i = area_index + 1; i < base_len; ++i)
    {
        if (!is_letter(base[i]))
            return false;
        ++suffix_letters;
    }
    if (suffix_letters < 1 || suffix_letters > 3)
        return false;

    strcpy(bc, base);
    return true;
}

uint16_t packgrid(const char* grid4)
{
    if (grid4 == 0)
    {
        // Two callsigns only, no report/grid
        return MAXGRID4 + 1;
    }

    // Take care of special cases
    if (equals(grid4, "RRR"))
        return MAXGRID4 + 2;
    if (equals(grid4, "RR73"))
        return MAXGRID4 + 3;
    if (equals(grid4, "73"))
        return MAXGRID4 + 4;

    // Check for standard 4 letter grid
    if (in_range(grid4[0], 'A', 'R') && in_range(grid4[1], 'A', 'R') && is_digit(grid4[2]) && is_digit(grid4[3]))
    {
        uint16_t igrid4 = (grid4[0] - 'A');
        igrid4 = igrid4 * 18 + (grid4[1] - 'A');
        igrid4 = igrid4 * 10 + (grid4[2] - '0');
        igrid4 = igrid4 * 10 + (grid4[3] - '0');
        return igrid4;
    }

    int dd = 0;
    bool has_r = false;
    if (parse_report_token(grid4, &dd, &has_r))
    {
        uint16_t irpt = (uint16_t)(35 + dd);
        return has_r ? (uint16_t)((MAXGRID4 + irpt) | 0x8000u) : (uint16_t)(MAXGRID4 + irpt);
    }

    return INVALID_GRID;
}

// Pack Type 1 (Standard 77-bit message) and Type 2 (ditto, with a "/P" call)
int pack77_1(const char* msg, uint8_t* b77)
{
    char tokens[4][32];
    int count = split_message_tokens(msg, tokens, 4);
    if (count < 2 || count > 4)
        return -1;

    const char* call1 = tokens[0];
    const char* call2 = tokens[1];
    const char* extra = 0;
    char call1_with_modifier[32];

    if (equals(tokens[0], "CQ") && count >= 3 && is_cq_modifier_token(tokens[1]))
    {
        const size_t modifier_length = strlen(tokens[1]);
        if (modifier_length > sizeof(call1_with_modifier) - 4)
            return -1;
        memcpy(call1_with_modifier, "CQ_", 3);
        memcpy(call1_with_modifier + 3, tokens[1], modifier_length + 1);
        call1 = call1_with_modifier;
        call2 = tokens[2];
        if (count == 4)
            extra = tokens[3];
    }
    else
    {
        if (count >= 3)
            extra = tokens[2];
        if (count > 3)
            return -1;
    }

    char call1_base[32];
    char call2_base[32];
    strip_supported_suffix(call1_base, sizeof(call1_base), call1);
    strip_supported_suffix(call2_base, sizeof(call2_base), call2);

    LOG(LOG_DEBUG,"call1 :%s", call1);
    LOG(LOG_DEBUG,"call2 :%s", call2);

    int32_t n28a = pack28(call1_base);
    int32_t n28b = pack28(call2_base);
    LOG(LOG_DEBUG,"n28a %2X",n28a);
    LOG(LOG_DEBUG,"n28b %2X",n28b);

    if (n28a < 0 || n28b < 0)
        return -1;

    uint16_t igrid4;

    if (extra != 0)
    {
        LOG(LOG_DEBUG,"GRID: %s", extra);
        igrid4 = packgrid(extra);
        if (igrid4 == INVALID_GRID)
            return -1;
    }
    else
    {
        // Two callsigns, no grid/report
        igrid4 = packgrid(0);
    }
    LOG(LOG_DEBUG,"G15: %x",igrid4);

    suffix_kind_t suffix1 = get_suffix_kind(call1);
    suffix_kind_t suffix2 = get_suffix_kind(call2);

    uint8_t ipa = (suffix1 != SUFFIX_NONE) ? 1 : 0;
    uint8_t ipb = (suffix2 != SUFFIX_NONE) ? 1 : 0;
    if (suffix1 != SUFFIX_NONE && suffix2 != SUFFIX_NONE && suffix1 != suffix2)
    {
        // FT8 Type 1 can only encode one suffix kind globally. Keep sender-side suffix.
        ipb = 0;
    }

    uint8_t i3 = 1; // No suffix or /R
    if (suffix1 == SUFFIX_P || suffix2 == SUFFIX_P)
        i3 = 2;

    // Shift in ipa and ipb bits into n28a and n28b
    n28a = (n28a << 1) | ipa;
    n28b = (n28b << 1) | ipb;

    // Pack into (28 + 1) + (28 + 1) + (1 + 15) + 3 bits
    b77[0] = (n28a >> 21);
    b77[1] = (n28a >> 13);
    b77[2] = (n28a >> 5);
    b77[3] = (uint8_t)(n28a << 3) | (uint8_t)(n28b >> 26);
    b77[4] = (n28b >> 18);
    b77[5] = (n28b >> 10);
    b77[6] = (n28b >> 2);
    b77[7] = (uint8_t)(n28b << 6) | (uint8_t)(igrid4 >> 10);
    b77[8] = (igrid4 >> 2);
    b77[9] = (uint8_t)(igrid4 << 6) | (uint8_t)(i3 << 3);

    return 0;
}

void packtext77(const char* text, uint8_t* b77)
{
    int length = strlen(text);

    // Skip leading and trailing spaces
    while (*text == ' ' && *text != 0)
    {
        ++text;
        --length;
    }
    while (length > 0 && text[length - 1] == ' ')
    {
        --length;
    }

    // Clear the first 72 bits representing a long number
    for (int i = 0; i < 9; ++i)
    {
        b77[i] = 0;
    }

    // Now express the text as base-42 number stored
    // in the first 72 bits of b77
    for (int j = 0; j < 13; ++j)
    {
        // Multiply the long integer in b77 by 42
        uint16_t x = 0;
        for (int i = 8; i >= 0; --i)
        {
            x += b77[i] * (uint16_t)42;
            b77[i] = (x & 0xFF);
            x >>= 8;
        }

        // Get the index of the current char
        if (j < length)
        {
            int q = char_index(A0, text[j]);
            x = (q > 0) ? q : 0;
        }
        else
        {
            x = 0;
        }
        // Here we double each added number in order to have the result multiplied
        // by two as well, so that it's a 71 bit number left-aligned in 72 bits (9 bytes)
        x <<= 1;

        // Now add the number to our long number
        for (int i = 8; i >= 0; --i)
        {
            if (x == 0)
                break;
            x += b77[i];
            b77[i] = (x & 0xFF);
            x >>= 8;
        }
    }

    // Set n3=0 (bits 71..73) and i3=0 (bits 74..76)
    b77[8] &= 0xFE;
    b77[9] &= 0x00;
}

int pack77(const char* msg, uint8_t* c77)
{
    char msgbuf[64];
    fmtmsg(msgbuf, msg);

    // Check Type 1 (Standard 77-bit message) or Type 2, with optional "/P"
    if (0 == pack77_1(msgbuf, c77))
    {
        return 0;
    }

    // Check 0.1 (DXpedition mode)
    if (0 == pack77_dxpedition(msgbuf, c77))
    {
        return 0;
    }

    // Check 0.5 (telemetry)
    if (0 == pack77_telemetry(msgbuf, c77))
    {
        return 0;
    }

    // Check 0.3/0.4 (ARRL Field Day)
    if (0 == pack77_field_day(msgbuf, c77))
    {
        return 0;
    }

    // Check Type 3 (ARRL RTTY Roundup)
    if (0 == pack77_rtty(msgbuf, c77))
    {
        return 0;
    }

    // Check Type 5 (EU VHF contest)
    if (0 == pack77_euvhf(msgbuf, c77))
    {
        return 0;
    }

    // Check Type 4 (One nonstandard call and one hashed call)
    if (0 == pack77_4(msgbuf, c77))
    {
        return 0;
    }

    // Default to free text
    // i3=0 n3=0
    packtext77(msgbuf, c77);
    return 0;
}

#ifdef UNIT_TEST

#include <iostream>

bool test1()
{
    const char* inputs[] = {
        "",
        " ",
        "ABC",
        "A9",
        "L9A",
        "L7BC",
        "L0ABC",
        "LL3JG",
        "LL3AJG",
        "CQ ",
        0
    };

    for (int i = 0; inputs[i]; ++i)
    {
        int32_t result = ft8_v2::pack28(inputs[i]);
        printf("pack28(\"%s\") = %d\n", inputs[i], result);
    }

    return true;
}

bool test2()
{
    const char* inputs[] = {
        "CQ LL3JG",
        "CQ LL3JG KO26",
        "L0UAA LL3JG KO26",
        "L0UAA LL3JG +02",
        "L0UAA LL3JG RRR",
        "L0UAA LL3JG 73",
        0
    };

    for (int i = 0; inputs[i]; ++i)
    {
        uint8_t result[10];
        int rc = ft8_v2::pack77_1(inputs[i], result);
        printf("pack77_1(\"%s\") = %d\t[", inputs[i], rc);
        for (int j = 0; j < 10; ++j)
        {
            printf("%02x ", result[j]);
        }
        printf("]\n");
    }

    return true;
}

int main()
{
    test1();
    test2();
    return 0;
}

#endif
