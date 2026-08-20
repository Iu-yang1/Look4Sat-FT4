#include "text.h"

#include <string.h>

const char* trim_front(const char* str)
{
    // 跳过前导空格
    while (*str == ' ')
    {
        str++;
    }
    return str;
}

void trim_back(char* str)
{
    // 通过将尾部空格替换为 '\0' 字符来跳过它们
    int idx = strlen(str) - 1;
    while (idx >= 0 && str[idx] == ' ')
    {
        str[idx--] = '\0';
    }
}

// 1) 通过将空格更改为 '\0' 从尾部修剪字符串
// 2) 通过跳过空格从头部修剪字符串
char* trim(char* str)
{
    str = (char*)trim_front(str);
    trim_back(str);
    // 返回指向第一个非空格字符的指针
    return str;
}

char to_upper(char c)
{
    return (c >= 'a' && c <= 'z') ? (c - 'a' + 'A') : c;
}

bool is_digit(char c)
{
    return (c >= '0') && (c <= '9');
}

bool is_letter(char c)
{
    return ((c >= 'A') && (c <= 'Z')) || ((c >= 'a') && (c <= 'z'));
}

bool is_space(char c)
{
    return (c == ' ');
}

bool in_range(char c, char min, char max)
{
    return (c >= min) && (c <= max);
}

bool starts_with(const char* string, const char* prefix)
{
    return 0 == memcmp(string, prefix, strlen(prefix));
}

bool equals(const char* string1, const char* string2)
{
    return 0 == strcmp(string1, string2);
}

int char_index(const char* string, char c)
{
    for (int i = 0; *string; ++i, ++string)
    {
        if (c == *string)
        {
            return i;
        }
    }
    return -1; // 未找到
}

// 文本消息格式化：
//   - 将小写字母替换为大写字母
//   - 将连续空格合并为单个空格
void fmtmsg(char* msg_out, const char* msg_in)
{
    char c;
    char last_out = 0;
    while ((c = *msg_in))
    {
        if (c != ' ' || last_out != ' ')
        {
            last_out = to_upper(c);
            *msg_out = last_out;
            ++msg_out;
        }
        ++msg_in;
    }
    *msg_out = 0; // 添加零终止符
}

// 从字符串解析 2 位整数
int dd_to_int(const char* str, int length)
{
    int result = 0;
    bool negative;
    int i;
    if (str[0] == '-')
    {
        negative = true;
        i = 1; // 消耗 - 符号
    }
    else
    {
        negative = false;
        i = (str[0] == '+') ? 1 : 0; // 如果发现 + 符号则消耗它
    }

    while (i < length)
    {
        if (str[i] == 0)
            break;
        if (!is_digit(str[i]))
            break;
        result *= 10;
        result += (str[i] - '0');
        ++i;
    }

    return negative ? -result : result;
}

// 将 2 位整数转换为字符串
void int_to_dd(char* str, int value, int width, bool full_sign)
{
    if (value < 0)
    {
        *str = '-';
        ++str;
        value = -value;
    }
    else if (full_sign)
    {
        *str = '+';
        ++str;
    }

    int divisor = 1;
    for (int i = 0; i < width - 1; ++i)
    {
        divisor *= 10;
    }

    while (divisor >= 1)
    {
        int digit = value / divisor;

        *str = '0' + digit;
        ++str;

        value -= digit * divisor;
        divisor /= 10;
    }
    *str = 0; // 添加零终止符
}

// 根据 6 个表之一将整数索引转换为 ASCII 字符：
// 表 0: " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ+-./?"
// 表 1: " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
// 表 2: "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
// 表 3: "0123456789"
// 表 4: " ABCDEFGHIJKLMNOPQRSTUVWXYZ"
// 表 5: " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ/"
char charn(int c, int table_idx)
{
    if (table_idx != 2 && table_idx != 3)
    {
        if (c == 0)
            return ' ';
        c -= 1;
    }
    if (table_idx != 4)
    {
        if (c < 10)
            return '0' + c;
        c -= 10;
    }
    if (table_idx != 3)
    {
        if (c < 26)
            return 'A' + c;
        c -= 26;
    }

    if (table_idx == 0)
    {
        if (c < 5)
            return "+-./?"[c];
    }
    else if (table_idx == 5)
    {
        if (c == 0)
            return '/';
    }

    return '_'; // 未知字符，永远不应到达这里
}

// 将字符转换为其索引（charn 的反向操作）
int nchar(char c, int table_idx)
{
    int n = 0;
    if (table_idx != 2 && table_idx != 3)
    {
        if (c == ' ')
            return n + 0;
        n += 1;
    }
    if (table_idx != 4)
    {
        if (c >= '0' && c <= '9')
            return n + (c - '0');
        n += 10;
    }
    if (table_idx != 3)
    {
        if (c >= 'A' && c <= 'Z')
            return n + (c - 'A');
        n += 26;
    }

    if (table_idx == 0)
    {
        if (c == '+')
            return n + 0;
        if (c == '-')
            return n + 1;
        if (c == '.')
            return n + 2;
        if (c == '/')
            return n + 3;
        if (c == '?')
            return n + 4;
    }
    else if (table_idx == 5)
    {
        if (c == '/')
            return n + 0;
    }

    // Character not found
    return -1;
}
