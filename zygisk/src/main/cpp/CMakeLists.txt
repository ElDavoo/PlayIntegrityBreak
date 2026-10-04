# LSPlant is built from source with C++ modules, which needs CMake 3.28+.
cmake_minimum_required(VERSION 3.28)
project(pib_zygisk C CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Debug builds compile at -O0, which nobody runs LSPlant and Dobby with (Vector ships optimised
# builds). Optimise every variant; -g from the debug flags is kept.
add_compile_options(-O2)

# JingMatrix's Dobby fork (also used by Vector), with ARM fixes newer than the Maven 1.2 release.
set(Plugin.SymbolResolver OFF CACHE INTERNAL "" FORCE)
add_subdirectory(external/dobby dobby EXCLUDE_FROM_ALL)

# JingMatrix's LSPlant fork (also used by Vector): unlike the last Maven release (6.4), it
# initialises on Android 16+/17, where ClassLinker::FixupStaticTrampolines is inlined.
set(LSPLANT_BUILD_SHARED OFF CACHE INTERNAL "" FORCE)
add_subdirectory(external/lsplant/lsplant/src/main/jni lsplant EXCLUDE_FROM_ALL)
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
target_link_libraries(pib_zygisk PRIVATE lsplant_static dobby_static xz log)
target_link_options(pib_zygisk PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
