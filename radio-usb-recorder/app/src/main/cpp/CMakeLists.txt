cmake_minimum_required(VERSION 3.22.1)
project(radio_usb_recorder LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(VENDOR_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/vendor")
set(LIBUSB_DIR "${VENDOR_ROOT}/libusb")
set(LIBUAC_DIR "${VENDOR_ROOT}/libuac")
if(NOT EXISTS "${LIBUSB_DIR}/libusb/libusb.h" OR NOT EXISTS "${LIBUAC_DIR}/include/libuac.h")
  message(FATAL_ERROR "Missing vendor source checkouts")
endif()
add_library(usb STATIC
  "${LIBUSB_DIR}/libusb/core.c"
  "${LIBUSB_DIR}/libusb/descriptor.c"
  "${LIBUSB_DIR}/libusb/hotplug.c"
  "${LIBUSB_DIR}/libusb/io.c"
  "${LIBUSB_DIR}/libusb/sync.c"
  "${LIBUSB_DIR}/libusb/strerror.c"
  "${LIBUSB_DIR}/libusb/os/linux_usbfs.c"
  "${LIBUSB_DIR}/libusb/os/events_posix.c"
  "${LIBUSB_DIR}/libusb/os/threads_posix.c"
  "${LIBUSB_DIR}/libusb/os/linux_netlink.c")
target_include_directories(usb PRIVATE "${LIBUSB_DIR}/android" "${LIBUSB_DIR}/libusb/os")
target_include_directories(usb PUBLIC "${LIBUSB_DIR}/libusb")
target_compile_options(usb PRIVATE -pthread)
target_link_libraries(usb PUBLIC log)
add_library(uac STATIC
  "${LIBUAC_DIR}/src/uac_context.cpp"
  "${LIBUAC_DIR}/src/uac_device.cpp"
  "${LIBUAC_DIR}/src/uac_parser.cpp"
  "${LIBUAC_DIR}/src/uac_streaming.cpp"
  "${LIBUAC_DIR}/src/uac_exceptions.cpp")
target_include_directories(uac PRIVATE "${LIBUAC_DIR}/src" "${LIBUAC_DIR}/android" PUBLIC "${LIBUAC_DIR}/include")
target_compile_options(uac PRIVATE -fexceptions)
target_link_libraries(uac PUBLIC usb log)
add_library(radio_usb_recorder SHARED native_recorder.cpp)
target_compile_options(radio_usb_recorder PRIVATE -Wall -Wextra -pthread -fexceptions)
target_link_libraries(radio_usb_recorder PRIVATE uac usb log)
