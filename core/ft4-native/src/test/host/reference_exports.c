/* Offline comparison only. Never compiled into Android. */
#include "../../main/cpp/ft4_bridge.h"
#include <stdlib.h>
#ifdef _WIN32
#define API __declspec(dllexport)
#else
#define API __attribute__((visibility("default")))
#endif
API int reference_ft4_wave(const char *text, int rate, float hz, float *out, int n) {
    return ft4_bridge_generate_wave(text,rate,hz,out,n);
}
API int reference_ft4_tones(const char *text, int *out, int n) {
    return ft4_bridge_generate_tones(text,out,n);
}
extern void gen_ft4wave_(int *, int *, int *, float *, float *, float *, float *, int *, int *);
API int reference_ft4_wave_tones(int *tones, int rate, float hz, float *out, int n) {
    int symbols=103, nsps=rate*48/1000, complex_output=0;
    float sample_rate=(float)rate;
    if(n!=nsps*105) return -1;
    float *scratch=(float *)calloc((size_t)n*2,sizeof(float));
    if(!scratch) return -1;
    gen_ft4wave_(tones,&symbols,&nsps,&sample_rate,&hz,scratch,out,&complex_output,&n);
    free(scratch);return n;
}
