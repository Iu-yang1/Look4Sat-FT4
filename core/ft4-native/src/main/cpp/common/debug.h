#pragma once

#define LOG_DEBUG 0
#define LOG_INFO 1
#define LOG_WARN 2
#define LOG_ERROR 3
#define LOG_FATAL 4

#ifndef LOG_LEVEL
#define LOG_LEVEL LOG_DEBUG
#endif

#ifndef TAG
#define TAG "FT8_DECODER"
#endif

#if defined(ANDROID)
#include <android/log.h>

#define LOG(level, ...) \
    do { \
        if ((level) >= LOG_LEVEL) { \
            __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__); \
        } \
    } while (0)
#define LOG_PRINTF(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGF(...) __android_log_print(ANDROID_LOG_FATAL, TAG, __VA_ARGS__)
#else
#include <stdio.h>

/* Host 自测不能依赖 Android log，错误信息直接写入 stderr。 */
#define LOG(level, ...) \
    do { \
        if ((level) >= LOG_LEVEL) { \
            fprintf(stderr, __VA_ARGS__); \
        } \
    } while (0)
#define LOG_PRINTF(...) fprintf(stderr, __VA_ARGS__)
#define LOGD(...) fprintf(stderr, __VA_ARGS__)
#define LOGI(...) fprintf(stderr, __VA_ARGS__)
#define LOGW(...) fprintf(stderr, __VA_ARGS__)
#define LOGE(...) fprintf(stderr, __VA_ARGS__)
#define LOGF(...) fprintf(stderr, __VA_ARGS__)
#endif
