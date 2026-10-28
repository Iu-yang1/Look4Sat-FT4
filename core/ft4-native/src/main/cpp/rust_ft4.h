#pragma once
#include <cstddef>
#include <cstdint>
extern "C" {
struct RustFt4Row {
    int32_t snr; float dt; float frequency; uint64_t hash;
    char text[128]; char source[32]; char target[32]; char detail[64];
};
static_assert(sizeof(RustFt4Row)==280 && offsetof(RustFt4Row,hash)==16,"Rust/C++ FT4 row ABI mismatch");
uint64_t l4s_ft4_create();
void l4s_ft4_cancel(uint64_t);
void l4s_ft4_close(uint64_t);
int32_t l4s_ft4_reset(uint64_t);
bool l4s_ft4_budget_exhausted(uint64_t);
int32_t l4s_ft4_validate(const char *, char *, size_t);
int32_t l4s_ft4_generate(const char *, uint32_t, float, float *, size_t);
int32_t l4s_ft4_decode(uint64_t, const float *, size_t, int64_t, uint32_t, size_t, bool, float, const char *, const char *, uint64_t);
int32_t l4s_ft4_result(uint64_t, size_t, RustFt4Row *);
}
