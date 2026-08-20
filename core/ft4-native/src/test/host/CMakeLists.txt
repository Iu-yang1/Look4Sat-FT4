cmake_minimum_required(VERSION 3.22)
project(look4sat_ft4_host_tests LANGUAGES C CXX Fortran)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_Fortran_MODULE_DIRECTORY "${CMAKE_BINARY_DIR}/fortran-modules")

set(FT4_CPP_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../main/cpp")
set(FT4_MANIFEST "${FT4_CPP_ROOT}/ft4-core-sources.manifest")
set(FT4_CORE_SOURCES)
file(STRINGS "${FT4_MANIFEST}" FT4_MANIFEST_LINES)
foreach(manifest_line IN LISTS FT4_MANIFEST_LINES)
    string(STRIP "${manifest_line}" manifest_line)
    if(manifest_line STREQUAL "" OR manifest_line MATCHES "^#")
        continue()
    endif()
    string(REPLACE "|" ";" manifest_fields "${manifest_line}")
    list(LENGTH manifest_fields manifest_field_count)
    if(NOT manifest_field_count EQUAL 2)
        continue()
    endif()
    list(GET manifest_fields 1 relative_path)
    # Windows 主机已有复数数学符号；仅 Android 目标需要该平台兼容层。
    if(NOT relative_path STREQUAL "android/complex_math_shim.c")
        list(APPEND FT4_CORE_SOURCES "${FT4_CPP_ROOT}/${relative_path}")
    endif()
endforeach()

add_library(look4sat_ft4_host_core STATIC ${FT4_CORE_SOURCES})
target_include_directories(look4sat_ft4_host_core PUBLIC
    "${FT4_CPP_ROOT}"
    "${FT4_CPP_ROOT}/vendor/wsjtx-3.0.0/lib"
    "${FT4_CPP_ROOT}/vendor/wsjtx-3.0.0/lib/77bit"
    "${FT4_CPP_ROOT}/vendor/wsjtx-3.0.0/lib/ft8"
    "${FT4_CPP_ROOT}/vendor/wsjtx-3.0.0/lib/ft4"
)
target_compile_options(look4sat_ft4_host_core PRIVATE -O2 -DNDEBUG -fno-fast-math)
set_source_files_properties(
    "${FT4_CPP_ROOT}/vendor/wsjtx-3.0.0/lib/crc14.cpp"
    PROPERTIES COMPILE_OPTIONS -fpermissive
)

find_package(Threads REQUIRED)
add_executable(look4sat_ft4_host_test
    ft4_host_test.c
    "${FT4_CPP_ROOT}/ftx_core/ftx_decoder_api.c"
    "${FT4_CPP_ROOT}/ftx_core/ftx_encoder_api.c"
    "${FT4_CPP_ROOT}/ft8/constants.c"
    "${FT4_CPP_ROOT}/ft8/crc.c"
    "${FT4_CPP_ROOT}/ft8/encode.c"
    "${FT4_CPP_ROOT}/ft8/hash22.c"
    "${FT4_CPP_ROOT}/ft8/pack.c"
    "${FT4_CPP_ROOT}/ft8/text.c"
    "${FT4_CPP_ROOT}/ft8/unpack.c"
)
target_include_directories(look4sat_ft4_host_test PRIVATE
    "${FT4_CPP_ROOT}"
    "${FT4_CPP_ROOT}/ftx_core/include"
)
target_compile_options(look4sat_ft4_host_test PRIVATE
    -O2 -DNDEBUG -fno-fast-math -Wall -Wextra -Werror
)
target_compile_definitions(look4sat_ft4_host_test PRIVATE LOG_LEVEL=2)
target_link_libraries(look4sat_ft4_host_test PRIVATE
    look4sat_ft4_host_core
    Threads::Threads
)

enable_testing()
add_test(NAME look4sat_ft4_codec_and_waveform COMMAND look4sat_ft4_host_test)
