# Vaelin startup-core native 构建脚本
#
# 来源：ZalithLauncher2（GPL-3.0）的 src/main/jni/Android.mk + Application.mk。
# 原始 ndk-build 脚本保留在同目录，仅作参数对照参考，不参与构建。
#
# 为什么不用 ndk-build：GNU Make 的 $(wildcard) 按空格切分参数，
# NDK 的 build/core/add-application.mk:85 用它校验 APP_BUILD_SCRIPT，
# 工作区路径 "Vaelin Launcher" 含空格时必然报 "points to an unknown file"。
# 这是 ndk-build 的硬限制，与 NDK 版本无关，因此改用 CMake。
#
# 与 Android.mk 的逐目标对应关系见文件末尾注释。

cmake_minimum_required(VERSION 3.22)
project(vaelin_startup_core LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# 对应 Application.mk 的 APP_STL := c++_shared
if(NOT ANDROID_STL)
    set(ANDROID_STL c++_shared)
endif()

set(JNI_DIR ${CMAKE_CURRENT_SOURCE_DIR})
set(IS_ARM64 FALSE)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(IS_ARM64 TRUE)
endif()

# 对应 Android.mk:11  $(call import-module,prefab/bytehook)
find_package(bytehook REQUIRED CONFIG)

# ---------------------------------------------------------------- driver_helper
# Android.mk:68-80
add_library(driver_helper SHARED
    driver_helper/driver_helper.c
    driver_helper/nsbypass.c)
target_include_directories(driver_helper PRIVATE
    ${JNI_DIR}
    ${JNI_DIR}/driver_helper)
target_link_libraries(driver_helper log android dl)
target_link_options(driver_helper PRIVATE "-rdynamic")
target_compile_options(driver_helper PRIVATE -g)
if(IS_ARM64)
    target_compile_definitions(driver_helper PRIVATE ADRENO_POSSIBLE)
    target_link_libraries(driver_helper EGL GLESv2)
endif()

# -------------------------------------------------------------------- pojavexec
# Android.mk:15-43
add_library(pojavexec SHARED
    bigcoreaffinity.c
    egl_bridge.c
    ctxbridges/br_loader.c
    ctxbridges/gl_bridge.c
    ctxbridges/osm_bridge.c
    ctxbridges/egl_loader.c
    ctxbridges/osmesa_loader.c
    ctxbridges/swap_interval_no_egl.c
    ctxbridges/virgl_bridge.c
    environ/environ.c
    logger/logger.c
    input_bridge_v3.c
    jre_launcher.c
    utils.c
    stdio_is.c
    java_exec_hooks.c
    lwjgl_dlopen_hook.c)
target_include_directories(pojavexec PRIVATE
    ${JNI_DIR}
    ${JNI_DIR}/ctxbridges
    ${JNI_DIR}/environ
    ${JNI_DIR}/logger)
target_link_libraries(pojavexec driver_helper log android dl)
target_link_options(pojavexec PRIVATE "-rdynamic")
if(IS_ARM64)
    target_compile_definitions(pojavexec PRIVATE ADRENO_POSSIBLE)
    target_link_libraries(pojavexec EGL GLESv2)
endif()

# ----------------------------------------------------------------- vulkan_check
# Android.mk:46-51。注意 :53-55 的 arm64 块落在 include $(BUILD_SHARED_LIBRARY)
# 之后，随后被 :58 的 $(CLEAR_VARS) 清空，对任何模块都不生效，是死代码，
# 这里按 Android.mk 的实际行为处理：vulkan_check 不带 ADRENO_POSSIBLE。
add_library(vulkan_check SHARED vulkan_checker.c)
target_include_directories(vulkan_check PRIVATE ${JNI_DIR})
target_link_libraries(vulkan_check driver_helper log dl)

# --------------------------------------------------------------------- exithook
# Android.mk:58-65
add_library(exithook SHARED
    exit_hook.c
    sdl_hook.c
    sdl_dlopen_hook.c)
target_include_directories(exithook PRIVATE ${JNI_DIR})
target_link_libraries(exithook bytehook::bytehook pojavexec log dl)

# ------------------------------------------------------------------- linkerhook
# Android.mk:83-89
add_library(linkerhook SHARED
    linkerhook/linkerhook.cpp
    linkerhook/linkerns.c)
target_include_directories(linkerhook PRIVATE ${JNI_DIR}/linkerhook)
target_link_options(linkerhook PRIVATE "-Wl,-z,global")

# ---------------------------------------------------------------- pojavexec_awt
# Android.mk:92-96
add_library(pojavexec_awt SHARED awt_bridge.c)
target_include_directories(pojavexec_awt PRIVATE ${JNI_DIR})

# --------------------------------------------------------------------- awt_xawt
# Android.mk:99-110。原脚本里 awt_headless 是零源文件的空壳占位模块，
# 只为给 awt_xawt 提供链接期依赖，随后在 Android.mk:114 被 rm 删掉。
# CMake 不接受零源目标，这里直接省略该空壳，awt_xawt 独立成库。
add_library(awt_xawt SHARED awt_xawt/xawt_fake.c)
target_include_directories(awt_xawt PRIVATE
    ${JNI_DIR}
    ${JNI_DIR}/awt_xawt)

# ----------------------------------------------------------------------- jsound
# Android.mk:117-150（OpenJDK libjsound 移植 + OpenAL 后端）
add_library(jsound SHARED
    jsound/Utilities.c
    jsound/Platform.c
    jsound/DirectAudioDevice.c
    jsound/DirectAudioDeviceProvider.c
    jsound/PortMixer.c
    jsound/PortMixerProvider.c
    jsound/MidiOutDevice.c
    jsound/MidiOutDeviceProvider.c
    jsound/MidiInDevice.c
    jsound/MidiInDeviceProvider.c
    jsound/PlatformMidi.c
    jsound/jsound_openal.c
    jsound/jdk8_compat.c)
target_include_directories(jsound PRIVATE ${JNI_DIR}/jsound)
target_compile_definitions(jsound PRIVATE
    X_PLATFORM=X_LINUX
    _LITTLE_ENDIAN
    USE_DAUDIO=TRUE
    USE_PORTS=FALSE
    USE_PLATFORM_MIDI_OUT=FALSE
    USE_PLATFORM_MIDI_IN=FALSE)
target_link_libraries(jsound log dl)

# ------------------------------------------------------------------------ flite
# Android.mk:153-170（TTS，非启动必需，保留以维持与 ZL2 的等价性）
add_library(flite SHARED flite/flite_bridge.c)
target_include_directories(flite PRIVATE ${JNI_DIR}/flite)
target_link_libraries(flite log)

add_library(fliteWrapper SHARED flite/flite_wrapper.c)
target_include_directories(fliteWrapper PRIVATE ${JNI_DIR}/flite)
target_link_libraries(fliteWrapper flite)

add_library(flite_cmu_us_kal16 SHARED flite/flite_cmu_us_kal16.c)
target_include_directories(flite_cmu_us_kal16 PRIVATE ${JNI_DIR}/flite)

# ---------------------------------------------------------------------------
# Android.mk 的 LOCAL_MODULE 与本文件的 add_library 目标一一对应：
#   pojavexec / vulkan_check / exithook / driver_helper / linkerhook /
#   pojavexec_awt / awt_headless(省略) / awt_xawt / jsound /
#   flite / fliteWrapper / flite_cmu_us_kal16
