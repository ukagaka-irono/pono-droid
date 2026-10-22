cmake_minimum_required(VERSION 3.22.1)
project(yaya LANGUAGES C CXX)

# YAYA_DIR: サブモジュールの yaya-shiori
# YAYA_GENERATED_DIR: Gradle が作る UTF-8 化したソース (src/) と CP932 変換表 (include/)
file(GLOB YAYA_SOURCES CONFIGURE_DEPENDS ${YAYA_GENERATED_DIR}/src/*.cpp)
list(FILTER YAYA_SOURCES EXCLUDE REGEX ".*/(stdafx|aya_profile)\\.cpp$")

set(GUMBO_SOURCES
    attribute.c char_ref.c char_ref_gperf.c error.c parser.c string_buffer.c
    string_piece.c tag.c tokenizer.c utf8.c util.c vector.c)
list(TRANSFORM GUMBO_SOURCES PREPEND ${YAYA_DIR}/gumbo/src/)

set(VENDOR_CXX_SOURCES
    ${YAYA_DIR}/parson/parson.c
    ${YAYA_DIR}/tinyxml2/tinyxml2.cpp
    ${YAYA_DIR}/uka_regexp/uka_regexp.cpp)
# makefile と同じく parson は C++ としてコンパイルする
set_source_files_properties(${YAYA_DIR}/parson/parson.c PROPERTIES LANGUAGE CXX)

add_library(yaya SHARED
    ${YAYA_SOURCES}
    ${GUMBO_SOURCES}
    ${VENDOR_CXX_SOURCES}
    ${YAYA_DIR}/sqlite3_yaya.c
    shim/pono_iconv.cpp
    shim/pono_sandbox.cpp
    jni_shiori.cpp)

# サブモジュールの警告は手を入れないので黙らせる。自前のコードは NDK 既定の警告（-Werror=format-security など）のまま
set_source_files_properties(
    ${YAYA_SOURCES} ${GUMBO_SOURCES} ${VENDOR_CXX_SOURCES} ${YAYA_DIR}/sqlite3_yaya.c
    PROPERTIES COMPILE_OPTIONS -w)

# shim を先に置き、<iconv.h> を CP932 対応の自前実装に差し替える
target_include_directories(yaya PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/shim
    ${YAYA_GENERATED_DIR}/include
    ${YAYA_DIR})

target_compile_definitions(yaya PRIVATE POSIX _FILE_OFFSET_BITS=64)
target_compile_options(yaya PRIVATE
    -fvisibility=hidden
    $<$<COMPILE_LANGUAGE:CXX>:-std=c++11 -fvisibility-inlines-hidden -include ${CMAKE_CURRENT_SOURCE_DIR}/shim/android_compat.h>)
# YAYA が呼ぶ libc の関数を shim/pono_sandbox.cpp の __wrap_ 版に差し替える（ゴーストにできることを絞る）
set(PONO_WRAPPED_SYMBOLS
    dlopen dlsym fork chdir
    open __open_2 fopen opendir stat lstat access readlink
    unlink remove rename mkdir rmdir utimes)
list(TRANSFORM PONO_WRAPPED_SYMBOLS PREPEND "-Wl,--wrap=")
target_link_options(yaya PRIVATE -Wl,-z,max-page-size=16384 ${PONO_WRAPPED_SYMBOLS})
target_link_libraries(yaya PRIVATE log)
