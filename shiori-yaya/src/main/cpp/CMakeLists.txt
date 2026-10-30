cmake_minimum_required(VERSION 3.22.1)
project(shiori LANGUAGES C CXX)

# SHIORI（ゴーストの辞書を動かすもの）を、エンジンごとに別の .so にする:
#   libyaya.so   YAYA（サブモジュール yaya）
#   libsatori.so 里々（サブモジュール satori）
#   libkawari.so 華和梨（サブモジュール kawari）
#   libkagari.so Kagari（サブモジュール kagari と、Lua 5.4・sol2）
# どれも shim/pono_sandbox.cpp でゴーストにできることを絞り、jni_<名前>.cpp で Kotlin とつなぐ。
#
# YAYA_DIR: サブモジュールの yaya-shiori
# YAYA_GENERATED_DIR: Gradle が作る UTF-8 化した YAYA のソース (src/) と CP932 変換表 (include/)
# SATORI_GENERATED_DIR: Gradle が作る UTF-8 化した里々のソース (satori/ _/ uka_regexp/)
# KAWARI_GENERATED_DIR: Gradle が写して 64bit 向けに直した華和梨のソース

# エンジンが呼ぶ libc の関数を shim/pono_sandbox.cpp の __wrap_ 版に差し替える（ゴーストにできることを絞る）。
# エンジンが使っていない関数を並べても害は無い
set(PONO_WRAPPED_SYMBOLS
    dlopen dlsym dlclose fork vfork chdir system popen execv execve execvp execl execlp execle posix_spawn posix_spawnp
    open __open_2 openat __openat_2 fopen freopen opendir stat lstat access readlink
    unlink remove rename mkdir rmdir utimes truncate mkstemp tmpfile)
list(TRANSFORM PONO_WRAPPED_SYMBOLS PREPEND "-Wl,--wrap=")

# 自前のコード（サンドボックスと JNI）。NDK 既定の警告（-Werror=format-security など）のまま
add_library(pono_sandbox OBJECT shim/pono_sandbox.cpp shim/pono_saori.cpp)
set_target_properties(pono_sandbox PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_compile_options(pono_sandbox PRIVATE -fvisibility=hidden)

# Android の libc の iconv は CP932 を持たないので、CP932 ⇄ UTF-8 だけの自前の iconv（YAYA と里々が使う）
add_library(pono_iconv OBJECT shim/pono_iconv.cpp)
set_target_properties(pono_iconv PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_include_directories(pono_iconv PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/shim ${YAYA_GENERATED_DIR}/include)
target_compile_options(pono_iconv PRIVATE -fvisibility=hidden)

function(pono_shiori_library target)
    target_sources(${target} PRIVATE $<TARGET_OBJECTS:pono_sandbox>)
    target_include_directories(${target} PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
    target_compile_options(${target} PRIVATE -fvisibility=hidden $<$<COMPILE_LANGUAGE:CXX>:-fvisibility-inlines-hidden>)
    # 同じプロセスに複数のエンジンを読んでも、それぞれ自分の load / request を呼ぶように（-Bsymbolic）
    target_link_options(${target} PRIVATE -Wl,-z,max-page-size=16384 -Wl,-Bsymbolic ${PONO_WRAPPED_SYMBOLS})
    target_link_libraries(${target} PRIVATE log)
endfunction()

# ---- YAYA ----

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
    $<TARGET_OBJECTS:pono_iconv>
    jni_shiori.cpp)

# サブモジュールの警告は手を入れないので黙らせる
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
    $<$<COMPILE_LANGUAGE:CXX>:-std=c++11 -include ${CMAKE_CURRENT_SOURCE_DIR}/shim/android_compat.h>)
pono_shiori_library(yaya)

# ---- 里々 ----

# makefile.linux と同じ並び
set(SATORI_SOURCES
    _/Sender.cpp _/charset.cpp _/Utilities.cpp _/calc.cpp _/calc_float.cpp _/stltool.cpp _/mt19937ar.cpp _/random.cpp
    satori/SakuraCS.cpp satori/SakuraClient.cpp satori/SakuraDLLClient.cpp satori/SakuraDLLHost.cpp
    satori/SaoriClient.cpp satori/SaoriHost.cpp satori/satori.cpp satori/satoriTranslate.cpp
    satori/satori_AnalyzeRequest.cpp satori/satori_builtin.cpp satori/satori_CreateResponce.cpp
    satori/satori_EventOperation.cpp satori/satori_Kakko.cpp satori/satori_load_dict.cpp
    satori/satori_load_unload.cpp satori/satori_sentence.cpp satori/satori_tool.cpp
    satori/shiori_plugin.cpp satori/ssu.cpp uka_regexp/uka_regexp.cpp)
list(TRANSFORM SATORI_SOURCES PREPEND ${SATORI_GENERATED_DIR}/)
set_source_files_properties(${SATORI_SOURCES} PROPERTIES GENERATED TRUE COMPILE_OPTIONS -w)

add_library(satori SHARED ${SATORI_SOURCES} $<TARGET_OBJECTS:pono_iconv> jni_satori.cpp)
# shim を先に置き、<iconv.h> を CP932 対応の自前実装に差し替える
target_include_directories(satori PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/shim ${SATORI_GENERATED_DIR}/satori ${SATORI_GENERATED_DIR}/_)
# PONO_SAORI_DIRECT: 本体の内蔵モジュール（SAORI over DirectSSTP）を呼べるようにする（build.gradle.kts のパッチ）
target_compile_definitions(satori PRIVATE POSIX SATORI_DLL PONO_SAORI_DIRECT _FILE_OFFSET_BITS=64)
# YAYA と同じく、Android に無い shm_open などを補うヘッダを先に読ませる
target_compile_options(satori PRIVATE $<$<COMPILE_LANGUAGE:CXX>:-std=c++11 -include ${CMAKE_CURRENT_SOURCE_DIR}/shim/android_compat.h>)
pono_shiori_library(satori)

# ---- 華和梨 ----

# files.mak の CORESRC と SHIOSRC（SAORI は native だけ。Java / Python の SAORI と shiori.cpp の入口は使わない）
# Gradle が写して 64bit 向けに直したもの（build.gradle.kts の prepareKawariSources）
set(KAWARI_SRC ${KAWARI_GENERATED_DIR})
set(KAWARI_SOURCES
    kis/kis_echo.cpp kis/kis_dict.cpp kis/kis_date.cpp kis/kis_counter.cpp kis/kis_file.cpp kis/kis_escape.cpp
    kis/kis_urllist.cpp kis/kis_substitute.cpp kis/kis_split.cpp kis/kis_communicate.cpp kis/kis_xargs.cpp
    kis/kis_string.cpp kis/kis_help.cpp kis/kis_saori.cpp kis/kis_system.cpp
    libkawari/kawari_crypt.cpp misc/base64.cpp
    libkawari/kawari_engine.cpp libkawari/kawari_ns.cpp libkawari/kawari_dict.cpp libkawari/kawari_code.cpp
    libkawari/kawari_codeset.cpp libkawari/kawari_codeexpr.cpp libkawari/kawari_codekis.cpp libkawari/kawari_vm.cpp
    libkawari/kawari_lexer.cpp libkawari/kawari_compiler.cpp libkawari/kawari_log.cpp libkawari/kawari_rc.cpp
    misc/misc.cpp misc/mt19937ar.cpp misc/l10n.cpp misc/phttp.cpp
    saori/saori.cpp saori/saori_module.cpp saori/saori_unique.cpp saori/saori_native.cpp
    shiori/kawari_shiori.cpp shiori/shiori_object.cpp)
list(TRANSFORM KAWARI_SOURCES PREPEND ${KAWARI_SRC}/)
# 華和梨は C++11 より前の書き方（"SHIORI/"SHIORIVER のような文字列の連結）なので C++98 でコンパイルする
set_source_files_properties(${KAWARI_SOURCES} PROPERTIES GENERATED TRUE COMPILE_OPTIONS "-w;-std=gnu++98")

add_library(kawari SHARED ${KAWARI_SOURCES} jni_kawari.cpp)
target_include_directories(kawari PRIVATE ${KAWARI_SRC})
target_compile_options(kawari PRIVATE $<$<COMPILE_LANGUAGE:CXX>:-std=c++11>)
pono_shiori_library(kawari)

# ---- Kagari（Lua 5.4 と sol2 を一緒に入れる）----

set(LUA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/lua)
set(LUA_SOURCES
    lapi.c lcode.c lctype.c ldebug.c ldo.c ldump.c lfunc.c lgc.c llex.c lmem.c lobject.c lopcodes.c lparser.c
    lstate.c lstring.c ltable.c ltm.c lundump.c lvm.c lzio.c
    lauxlib.c lbaselib.c lcorolib.c ldblib.c liolib.c lmathlib.c loadlib.c loslib.c lstrlib.c ltablib.c lutf8lib.c linit.c)
list(TRANSFORM LUA_SOURCES PREPEND ${LUA_DIR}/)
set_source_files_properties(${LUA_SOURCES} PROPERTIES COMPILE_OPTIONS -w)

add_library(kagari SHARED
    ${LUA_SOURCES}
    kagari/kagari.cpp
    kagari/kagari_unix.cpp
    shim/pono_lua.c
    jni_kagari.cpp)
set_source_files_properties(kagari/kagari.cpp kagari/kagari_unix.cpp PROPERTIES COMPILE_OPTIONS -w)
target_include_directories(kagari PRIVATE ${LUA_DIR} ${CMAKE_CURRENT_SOURCE_DIR}/sol2/include ${CMAKE_CURRENT_SOURCE_DIR}/kagari)
# LUA_USE_LINUX は dlopen 版の C モジュール読み込みと popen を使う（どちらもサンドボックスで止まる）
target_compile_definitions(kagari PRIVATE LUA_USE_LINUX)
target_compile_options(kagari PRIVATE $<$<COMPILE_LANGUAGE:CXX>:-std=c++17>)
# バイトコードを読ませず、debug ライブラリを絞る（shim/pono_lua.c）
target_link_options(kagari PRIVATE -Wl,--wrap=lua_load -Wl,--wrap=luaopen_debug)
pono_shiori_library(kagari)
