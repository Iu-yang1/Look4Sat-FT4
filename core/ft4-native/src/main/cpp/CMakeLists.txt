cmake_minimum_required(VERSION 3.22.1)
project(look4sat_ft4 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(FT4_CORE_OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/ft4-core")
set(FT4_CORE_ARCHIVE "${FT4_CORE_OUTPUT}/liblook4sat_ft4_core.a")
set(FT4_RUNTIME_ARCHIVE "${FT4_CORE_OUTPUT}/libflang_rt.runtime.a")
set(FT4_BUILD_LOG "${FT4_CORE_OUTPUT}/configure-build.log")
set(FT4_SOURCE_MANIFEST "${CMAKE_CURRENT_SOURCE_DIR}/ft4-core-sources.manifest")
file(MAKE_DIRECTORY "${FT4_CORE_OUTPUT}")

set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
    "${FT4_SOURCE_MANIFEST}"
    "${CMAKE_CURRENT_SOURCE_DIR}/tools/build-ft4-core.ps1"
    "${CMAKE_CURRENT_SOURCE_DIR}/tools/build-flang-runtime.ps1"
    "${CMAKE_CURRENT_SOURCE_DIR}/tools/toolchain-common.ps1"
    "${CMAKE_CURRENT_SOURCE_DIR}/tools/patches/flang-rt-android-time.patch"
    "${CMAKE_CURRENT_SOURCE_DIR}/tools/patches/flang-22.1-arm32-codegen.patch"
)
file(GLOB_RECURSE FT4_CORE_INPUTS CONFIGURE_DEPENDS
    "${CMAKE_CURRENT_SOURCE_DIR}/vendor/*.f90"
    "${CMAKE_CURRENT_SOURCE_DIR}/vendor/*.c"
    "${CMAKE_CURRENT_SOURCE_DIR}/vendor/*.cpp"
    "${CMAKE_CURRENT_SOURCE_DIR}/host/*.f90"
    "${CMAKE_CURRENT_SOURCE_DIR}/host/*.c"
    "${CMAKE_CURRENT_SOURCE_DIR}/android/*.c"
    "${CMAKE_CURRENT_SOURCE_DIR}/fft/*.c"
)
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${FT4_CORE_INPUTS})

set(FT4_BUILD_PROFILE Release)
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    set(FT4_BUILD_PROFILE Debug)
endif()

find_program(POWERSHELL_EXECUTABLE NAMES powershell.exe powershell REQUIRED)
set(FT4_BUILD_COMMAND
    "${POWERSHELL_EXECUTABLE}" -NoProfile -ExecutionPolicy Bypass
    -File "${CMAKE_CURRENT_SOURCE_DIR}/tools/build-ft4-core.ps1"
    -OutputDir "${FT4_CORE_OUTPUT}"
    -Abi "${ANDROID_ABI}"
    -CMakePath "${CMAKE_COMMAND}"
    -NinjaPath "${CMAKE_MAKE_PROGRAM}"
    -NdkRoot "${CMAKE_ANDROID_NDK}"
    -BuildProfile "${FT4_BUILD_PROFILE}"
)
if(DEFINED FT4_FLANG_PATH AND NOT FT4_FLANG_PATH STREQUAL "")
    list(APPEND FT4_BUILD_COMMAND -FlangPath "${FT4_FLANG_PATH}")
endif()
if(DEFINED FT4_BOOST_HEADERS AND NOT FT4_BOOST_HEADERS STREQUAL "")
    list(APPEND FT4_BUILD_COMMAND -BoostHeaders "${FT4_BOOST_HEADERS}")
endif()
if(DEFINED FT4_LLVM_SOURCE_ROOT AND NOT FT4_LLVM_SOURCE_ROOT STREQUAL "")
    list(APPEND FT4_BUILD_COMMAND -LlvmSourceRoot "${FT4_LLVM_SOURCE_ROOT}")
endif()

execute_process(
    COMMAND ${FT4_BUILD_COMMAND}
    WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}"
    RESULT_VARIABLE FT4_BUILD_RESULT
    OUTPUT_VARIABLE FT4_BUILD_STDOUT
    ERROR_VARIABLE FT4_BUILD_STDERR
)
file(WRITE "${FT4_BUILD_LOG}"
    "ABI=${ANDROID_ABI}\nCOMMAND=${FT4_BUILD_COMMAND}\n\n${FT4_BUILD_STDOUT}\n${FT4_BUILD_STDERR}\n")

set(FT4_OFFICIAL_CORE FALSE)
if(FT4_BUILD_RESULT EQUAL 0 AND EXISTS "${FT4_CORE_ARCHIVE}" AND EXISTS "${FT4_RUNTIME_ARCHIVE}")
    set(FT4_OFFICIAL_CORE TRUE)
    message(STATUS "Look4Sat FT4 official core enabled for ${ANDROID_ABI}")
else()
    message(WARNING
        "Look4Sat FT4 official core unavailable for ${ANDROID_ABI}; building capability stub. "
        "See ${FT4_BUILD_LOG}")
endif()

add_library(look4sat_ft4 SHARED ft4_jni.cpp)
target_include_directories(look4sat_ft4 PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}"
    "${CMAKE_CURRENT_SOURCE_DIR}/ftx_core/include"
)
target_compile_options(look4sat_ft4 PRIVATE
    $<$<CONFIG:Debug>:-O0;-g>
    $<$<NOT:$<CONFIG:Debug>>:-O2;-DNDEBUG>
    -fno-fast-math
)
target_compile_definitions(look4sat_ft4 PRIVATE
    FT4_ANDROID_ABI="${ANDROID_ABI}"
    FT4_UPSTREAM_SHA="6f69c7281a99d0243824ac02ce5706e8e897776f"
)

if(FT4_OFFICIAL_CORE)
    target_sources(look4sat_ft4 PRIVATE
        ftx_core/ftx_decoder_api.c
        ftx_core/ftx_encoder_api.c
        ft8/constants.c
        ft8/crc.c
        ft8/encode.c
        ft8/hash22.c
        ft8/pack.c
        ft8/text.c
        ft8/unpack.c
    )
    target_compile_definitions(look4sat_ft4 PRIVATE FT4_OFFICIAL_CORE=1)
    target_link_libraries(look4sat_ft4 PRIVATE
        "-Wl,--whole-archive" "${FT4_CORE_ARCHIVE}" "-Wl,--no-whole-archive"
        "${FT4_RUNTIME_ARCHIVE}" android log m dl
    )
else()
    target_compile_definitions(look4sat_ft4 PRIVATE
        FT4_OFFICIAL_CORE=0
        FT4_UNAVAILABLE_REASON="official_core_unavailable_for_this_abi"
    )
    target_link_libraries(look4sat_ft4 PRIVATE android log)
endif()
