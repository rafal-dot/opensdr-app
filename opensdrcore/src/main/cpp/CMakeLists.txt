cmake_minimum_required(VERSION 3.22.1)
project(opensdr_sdrcore)

option(OPENSDR_DIAGNOSTIC_CAPTURE "Internal APK-only IQ/audio recorder" OFF)

set(OPENSDR_DSP_SOURCES am_pipeline.cpp am_demod.cpp am_carrier_tracker.cpp am_speech_filter.cpp ssb_pipeline.cpp nfm_pipeline.cpp wfm_pipeline.cpp)

set(LIBUSB_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/libusb)
set(RTLSDR_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/rtl-sdr-android)

# Portable regression tests exercise the real DSP without Android or USB hardware.
if(NOT ANDROID)
    enable_testing()
    find_package(Threads REQUIRED)
    set(OPENSDR_SANITIZER "" CACHE STRING "Optional host sanitizer, e.g. thread or address,undefined")
    foreach(test_name am_pipeline_test am_carrier_noise_test am_carrier_selection_test am_carrier_context_test am_speech_filter_test stream_support_test manual_squelch_test spectrum_history_test)
        add_executable(${test_name} ../../test/cpp/${test_name}.cpp)
        if(test_name STREQUAL "spectrum_history_test")
            target_sources(${test_name} PRIVATE spectrum_history.cpp)
        elseif(test_name STREQUAL "am_carrier_context_test")
            target_sources(${test_name} PRIVATE am_carrier_tracker.cpp)
        elseif(NOT test_name STREQUAL "stream_support_test")
            target_sources(${test_name} PRIVATE ${OPENSDR_DSP_SOURCES})
        endif()
        target_compile_features(${test_name} PRIVATE cxx_std_17)
        target_include_directories(${test_name} PRIVATE ${CMAKE_CURRENT_SOURCE_DIR} ${LIBUSB_ROOT}/libusb)
        target_link_libraries(${test_name} PRIVATE Threads::Threads)
        if(OPENSDR_SANITIZER)
            target_compile_options(${test_name} PRIVATE -fsanitize=${OPENSDR_SANITIZER} -fno-omit-frame-pointer -g)
            target_link_options(${test_name} PRIVATE -fsanitize=${OPENSDR_SANITIZER})
        endif()
        add_test(NAME ${test_name} COMMAND ${test_name})
    endforeach()
    add_executable(diagnostic_capture_test ../../test/cpp/diagnostic_capture_test.cpp diagnostic_capture.cpp)
    target_compile_features(diagnostic_capture_test PRIVATE cxx_std_17)
    target_include_directories(diagnostic_capture_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
    target_link_libraries(diagnostic_capture_test PRIVATE Threads::Threads)
    if(OPENSDR_SANITIZER)
        target_compile_options(diagnostic_capture_test PRIVATE -fsanitize=${OPENSDR_SANITIZER} -fno-omit-frame-pointer -g)
        target_link_options(diagnostic_capture_test PRIVATE -fsanitize=${OPENSDR_SANITIZER})
    endif()
    add_test(NAME diagnostic_capture_test COMMAND diagnostic_capture_test)
    return()
endif()

function(enable_android_16kb_pages target_name)
    if(ANDROID)
        target_link_options(
            ${target_name}
            PRIVATE
            "-Wl,-z,max-page-size=16384"
            "-Wl,-z,common-page-size=16384"
        )
    endif()
endfunction()

function(strip_android_debug_info target_name)
    if(ANDROID)
        target_compile_options(
            ${target_name}
            PRIVATE
            -g0
        )
        target_link_options(
            ${target_name}
            PRIVATE
            "-Wl,--strip-all"
        )
    endif()
endfunction()

add_library(
    libusb_android
    STATIC
    ${LIBUSB_ROOT}/libusb/core.c
    ${LIBUSB_ROOT}/libusb/descriptor.c
    ${LIBUSB_ROOT}/libusb/hotplug.c
    ${LIBUSB_ROOT}/libusb/io.c
    ${LIBUSB_ROOT}/libusb/sync.c
    ${LIBUSB_ROOT}/libusb/strerror.c
    ${LIBUSB_ROOT}/libusb/os/linux_usbfs.c
    ${LIBUSB_ROOT}/libusb/os/events_posix.c
    ${LIBUSB_ROOT}/libusb/os/threads_posix.c
    ${LIBUSB_ROOT}/libusb/os/linux_netlink.c
)

target_include_directories(
    libusb_android
    PRIVATE
    ${LIBUSB_ROOT}
    ${LIBUSB_ROOT}/android
    ${LIBUSB_ROOT}/libusb
    ${LIBUSB_ROOT}/libusb/os
)

target_compile_definitions(
    libusb_android
    PRIVATE
    OS_LINUX=1
)

target_compile_options(
    libusb_android
    PRIVATE
    -fvisibility=hidden
    -pthread
)
strip_android_debug_info(libusb_android)

add_library(
    rtlsdr_android
    STATIC
    rtl_usb_transport.c
    ${RTLSDR_ROOT}/src/tuner_e4k.c
    ${RTLSDR_ROOT}/src/tuner_fc0012.c
    ${RTLSDR_ROOT}/src/tuner_fc0013.c
    ${RTLSDR_ROOT}/src/tuner_fc2580.c
    ${RTLSDR_ROOT}/src/tuner_r82xx.c
)

target_include_directories(
    rtlsdr_android
    PRIVATE
    ${RTLSDR_ROOT}/include
    ${RTLSDR_ROOT}/src
    ${LIBUSB_ROOT}
    ${LIBUSB_ROOT}/libusb
)

target_link_libraries(
    rtlsdr_android
    libusb_android
)
strip_android_debug_info(rtlsdr_android)

add_library(
    opensdr_rtlsdr
    SHARED
    opensdr_rtlsdr.cpp
    spectrum_history.cpp
    ${OPENSDR_DSP_SOURCES}
)

if(OPENSDR_DIAGNOSTIC_CAPTURE)
    target_sources(opensdr_rtlsdr PRIVATE diagnostic_capture.cpp diagnostic_capture_jni.cpp)
    target_compile_definitions(opensdr_rtlsdr PRIVATE OPENSDR_DIAGNOSTIC_CAPTURE=1)
endif()

find_library(log-lib log)
find_library(android-lib android)

target_include_directories(
    opensdr_rtlsdr
    PRIVATE
    ${RTLSDR_ROOT}/include
    ${LIBUSB_ROOT}/libusb
)

target_link_libraries(
    opensdr_rtlsdr
    rtlsdr_android
    libusb_android
    ${log-lib}
    ${android-lib}
)

enable_android_16kb_pages(opensdr_rtlsdr)
strip_android_debug_info(opensdr_rtlsdr)
