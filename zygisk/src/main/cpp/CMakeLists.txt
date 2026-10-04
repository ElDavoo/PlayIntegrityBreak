# LSPlant is built from source with C++ modules, which needs CMake 3.28+.
cmake_minimum_required(VERSION 3.28)
project(pib_zygisk C CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(dobby REQUIRED CONFIG)

# JingMatrix's LSPlant fork (also used by Vector): unlike the last Maven release (6.4), it
# initialises on Android 16+/17, where ClassLinker::FixupStaticTrampolines is inlined.
set(LSPLANT_BUILD_SHARED OFF CACHE INTERNAL "" FORCE)
add_subdirectory(external/lsplant/lsplant/src/main/jni lsplant)
target_compile_options(lsplant_static PUBLIC
        -Wno-gnu-anonymous-struct -Wno-gnu-string-literal-operator-template)

# XZ decoder for libart's .gnu_debugdata (MiniDebugInfo).
add_library(xz STATIC
        external/xz-embedded/xz_crc32.c
        external/xz-embedded/xz_dec_lzma2.c
        external/xz-embedded/xz_dec_stream.c)
target_include_directories(xz PUBLIC external/xz-embedded)
target_compile_definitions(xz PRIVATE XZ_DEC_ANY_CHECK)

add_library(pib_zygisk SHARED main.cpp elf_util.cpp)
target_compile_options(pib_zygisk PRIVATE -Wall -Wextra -fno-exceptions -fvisibility=hidden)
target_link_libraries(pib_zygisk PRIVATE lsplant_static dobby::dobby xz log)
target_link_options(pib_zygisk PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
