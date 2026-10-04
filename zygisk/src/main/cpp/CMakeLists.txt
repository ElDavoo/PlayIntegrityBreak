cmake_minimum_required(VERSION 3.22.1)
project(pib_zygisk C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(dobby REQUIRED CONFIG)
# Only the header is used: liblsplant.so is loaded from the module directory at runtime.
find_package(lsplant REQUIRED CONFIG)

# XZ decoder for libart's .gnu_debugdata (MiniDebugInfo).
add_library(xz STATIC
        external/xz-embedded/xz_crc32.c
        external/xz-embedded/xz_dec_lzma2.c
        external/xz-embedded/xz_dec_stream.c)
target_include_directories(xz PUBLIC external/xz-embedded)
target_compile_definitions(xz PRIVATE XZ_DEC_ANY_CHECK)

add_library(pib_zygisk SHARED main.cpp elf_util.cpp)
target_compile_definitions(pib_zygisk PRIVATE PIB_ABI="${ANDROID_ABI}")
target_compile_options(pib_zygisk PRIVATE -Wall -Wextra -fno-exceptions -fvisibility=hidden)
target_include_directories(pib_zygisk PRIVATE
        $<TARGET_PROPERTY:lsplant::lsplant,INTERFACE_INCLUDE_DIRECTORIES>)
target_link_libraries(pib_zygisk PRIVATE dobby::dobby xz log)
target_link_options(pib_zygisk PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
