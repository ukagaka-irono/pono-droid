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
    unlink remove rename mkdir rmdir utimes truncate mkstemp tmpfile
    # Rust の std（pasta など）が使うもの
    unlinkat fstatat symlink)
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

# Kagari のゴースト・プラグイン（kotori・kagari_as_plugin の corelib）が require する C のモジュール。Windows の DLL・Linux の
# .so の代わりにソースから作って libkagari.so に入れ、package.preload に置く（shim/pono_lua_modules.c）:
#   lua-utf8（luautf8）・iconv（lua-iconv。中身は shim/pono_iconv.cpp）・luachild・fh・saori_universal.native（自前）・
#   socket.core・mime.core（luasocket）・lanes.core（lanes）・ssl.core など（luasec。TLS は BoringSSL）
set(LUAMOD_DIR ${CMAKE_CURRENT_SOURCE_DIR}/luamod)
set(LUAMOD_SOCKET ${LUAMOD_DIR}/luasocket/src)
set(LUAMOD_LANES ${LUAMOD_DIR}/lanes/src)
set(LUAMOD_LSEC ${LUAMOD_DIR}/luasec/src)
set(LUAMOD_SOURCES
    ${LUAMOD_DIR}/luautf8/lutf8lib.c
    ${LUAMOD_DIR}/lua-iconv/luaiconv.c
    ${LUAMOD_DIR}/luachild/luachild_common.c
    ${LUAMOD_DIR}/luachild/luachild_posix.c
    ${LUAMOD_DIR}/luachild/luachild_lua_5_3.c
    ${LUAMOD_SOCKET}/auxiliar.c ${LUAMOD_SOCKET}/buffer.c ${LUAMOD_SOCKET}/compat.c ${LUAMOD_SOCKET}/except.c
    ${LUAMOD_SOCKET}/inet.c ${LUAMOD_SOCKET}/io.c ${LUAMOD_SOCKET}/luasocket.c ${LUAMOD_SOCKET}/options.c
    ${LUAMOD_SOCKET}/select.c ${LUAMOD_SOCKET}/tcp.c ${LUAMOD_SOCKET}/timeout.c ${LUAMOD_SOCKET}/udp.c
    ${LUAMOD_SOCKET}/usocket.c ${LUAMOD_SOCKET}/mime.c
    ${LUAMOD_LANES}/lanes.c ${LUAMOD_LANES}/cancel.c ${LUAMOD_LANES}/compat.c ${LUAMOD_LANES}/threading.c
    ${LUAMOD_LANES}/tools.c ${LUAMOD_LANES}/state.c ${LUAMOD_LANES}/linda.c ${LUAMOD_LANES}/deep.c
    ${LUAMOD_LANES}/keeper.c ${LUAMOD_LANES}/universe.c)
set(LUAMOD_LSEC_SOURCES
    ${LUAMOD_LSEC}/options.c ${LUAMOD_LSEC}/x509.c ${LUAMOD_LSEC}/context.c ${LUAMOD_LSEC}/ssl.c
    ${LUAMOD_LSEC}/config.c ${LUAMOD_LSEC}/ec.c
    ${LUAMOD_LSEC}/luasocket/buffer.c ${LUAMOD_LSEC}/luasocket/io.c ${LUAMOD_LSEC}/luasocket/timeout.c
    ${LUAMOD_LSEC}/luasocket/usocket.c)
# サブモジュールの警告は直さない
set_source_files_properties(${LUAMOD_SOURCES} ${LUAMOD_LSEC_SOURCES} PROPERTIES COMPILE_OPTIONS -w)
# Lua 本体にも luaopen_utf8 があるので、luautf8 の入口の名前を変える
set_source_files_properties(${LUAMOD_DIR}/luautf8/lutf8lib.c PROPERTIES COMPILE_DEFINITIONS luaopen_utf8=pono_luaopen_lua_utf8)
# <iconv.h> を自前の CP932 対応のもの（shim/iconv.h）にする
set_source_files_properties(${LUAMOD_DIR}/lua-iconv/luaiconv.c PROPERTIES INCLUDE_DIRECTORIES ${CMAKE_CURRENT_SOURCE_DIR}/shim)
# luachild は POSIX 版を使う（元の rockspec と同じ）
set_source_files_properties(
    ${LUAMOD_DIR}/luachild/luachild_common.c ${LUAMOD_DIR}/luachild/luachild_posix.c ${LUAMOD_DIR}/luachild/luachild_lua_5_3.c
    PROPERTIES COMPILE_DEFINITIONS USE_POSIX
    COMPILE_OPTIONS "-w;-include;${CMAKE_CURRENT_SOURCE_DIR}/shim/compat/pono_luachild_spawn.h")
# lanes は pthread_mutexattr_settype に _GNU_SOURCE が要る（元の Makefile と同じ）
set_source_files_properties(${LUAMOD_LANES}/threading.c ${LUAMOD_LANES}/lanes.c PROPERTIES COMPILE_DEFINITIONS _GNU_SOURCE)
# luasec の中の luasocket の写しは、luasocket と名前がぶつかるので lsec_ を付ける。BoringSSL に無い関数は埋める
set_source_files_properties(${LUAMOD_LSEC_SOURCES} PROPERTIES
    COMPILE_OPTIONS "-w;-include;${CMAKE_CURRENT_SOURCE_DIR}/shim/compat/pono_lsec_rename.h;-include;${CMAKE_CURRENT_SOURCE_DIR}/shim/compat/pono_lsec_boringssl.h"
    INCLUDE_DIRECTORIES "${LUAMOD_LSEC};${LUAMOD_LSEC}/luasocket")

# BoringSSL（luasec の TLS）。アセンブラは使わない。テストや道具は作らない（EXCLUDE_FROM_ALL で、リンクする ssl・crypto だけ）
set(OPENSSL_NO_ASM ON CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
add_subdirectory(${LUAMOD_DIR}/boringssl ${CMAKE_CURRENT_BINARY_DIR}/boringssl EXCLUDE_FROM_ALL)

add_library(kagari SHARED
    ${LUA_SOURCES}
    ${LUAMOD_SOURCES}
    ${LUAMOD_LSEC_SOURCES}
    $<TARGET_OBJECTS:pono_iconv>
    kagari/kagari.cpp
    kagari/kagari_unix.cpp
    shim/pono_lua.c
    shim/pono_lua_modules.c
    shim/pono_lua_fh.c
    shim/pono_lua_saori.c
    shim/pono_lanes_inline.c
    jni_kagari.cpp)
target_link_libraries(kagari PRIVATE ssl crypto)
set_source_files_properties(shim/pono_lanes_inline.c PROPERTIES INCLUDE_DIRECTORIES ${LUAMOD_LANES})
set_source_files_properties(kagari/kagari.cpp kagari/kagari_unix.cpp PROPERTIES COMPILE_OPTIONS -w)
target_include_directories(kagari PRIVATE ${LUA_DIR} ${CMAKE_CURRENT_SOURCE_DIR}/sol2/include ${CMAKE_CURRENT_SOURCE_DIR}/kagari)
# LUA_USE_LINUX は dlopen 版の C モジュール読み込みと popen を使う（どちらもサンドボックスで止まる）
target_compile_definitions(kagari PRIVATE LUA_USE_LINUX)
target_compile_options(kagari PRIVATE $<$<COMPILE_LANGUAGE:CXX>:-std=c++17>)
# バイトコードを読ませず、debug ライブラリを絞る（shim/pono_lua.c）。package を開くときに C のモジュールを足す（shim/pono_lua_modules.c）
target_link_options(kagari PRIVATE -Wl,--wrap=lua_load -Wl,--wrap=luaopen_debug -Wl,--wrap=luaopen_package)
pono_shiori_library(kagari)

# ---- ソースから作る SAORI ----
# ゴーストに同梱の Windows の DLL（nodoka.dll など）の代わりに、元のソースから libsaori_<名前>.so を作る。
# SHIORI の dlopen（shim/pono_sandbox.cpp）が DLL の名前で見分けて開き、サンドボックスの決まりを渡してから使う。
# どれも shim/pono_saori_guest.cpp（入口）と、SHIORI と同じ shim/pono_sandbox.cpp（--wrap）を入れる
set(SAORI_DIR ${CMAKE_CURRENT_SOURCE_DIR}/saori)

function(pono_saori_library target)
    target_sources(${target} PRIVATE shim/pono_saori_guest.cpp $<TARGET_OBJECTS:pono_iconv>)
    target_include_directories(${target} PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/shim)
    # 元のソースの警告は直さない（サブモジュールは書き換えない）
    target_compile_options(${target} PRIVATE -w $<$<COMPILE_LANGUAGE:CXX>:-std=c++17>)
    pono_shiori_library(${target})
endfunction()

# Tatakinov さんの SAORI。Windows だけの util.h（conv）は shim/compat/saori_util.h を先に読ませて差し替える
set(PONO_SAORI_UTIL -include ${CMAKE_CURRENT_SOURCE_DIR}/shim/compat/saori_util.h)

add_library(saori_nodoka SHARED
    ${SAORI_DIR}/mahjong_nodoka/nodoka.cc
    ${SAORI_DIR}/mahjong_nodoka/lib.cc
    ${SAORI_DIR}/mahjong_nodoka/base/header.cc
    shim/pono_saori_tatakinov.cpp)
target_include_directories(saori_nodoka PRIVATE ${SAORI_DIR}/mahjong_nodoka)
target_compile_options(saori_nodoka PRIVATE ${PONO_SAORI_UTIL})
pono_saori_library(saori_nodoka)

add_library(saori_texasholdem SHARED
    ${SAORI_DIR}/TexasHoldemUtil/saori.cc
    ${SAORI_DIR}/TexasHoldemUtil/header.cc
    ${SAORI_DIR}/TexasHoldemUtil/request.cc
    ${SAORI_DIR}/TexasHoldemUtil/response.cc
    ${SAORI_DIR}/TexasHoldemUtil/estimate.cc
    shim/pono_saori_tatakinov.cpp)
target_include_directories(saori_texasholdem PRIVATE ${SAORI_DIR}/TexasHoldemUtil)
pono_saori_library(saori_texasholdem)

# budoux はゴーストの SAORI のフォルダの model.json（Google の budoux の ja.json）を読む。JSON は nlohmann/json
add_library(saori_budoux SHARED
    ${SAORI_DIR}/saori_budoux/saori.cc
    ${SAORI_DIR}/saori_budoux/budoux.cc
    ${SAORI_DIR}/saori_budoux/sstp/header.cc
    ${SAORI_DIR}/saori_budoux/sstp/request.cc
    ${SAORI_DIR}/saori_budoux/sstp/response.cc
    shim/pono_saori_tatakinov.cpp)
target_include_directories(saori_budoux PRIVATE ${SAORI_DIR}/saori_budoux ${CMAKE_CURRENT_SOURCE_DIR}/third_party)
target_compile_options(saori_budoux PRIVATE ${PONO_SAORI_UTIL})
pono_saori_library(saori_budoux)

# MahjongUtil は csaori（Windows の SAORI の土台）の上にある。麻雀の計算（MahjongUtil.cpp・mj_*）と csaori.h だけを元のソースから使い、
# Windows の csaori_base.h・csaori_util.h は shim/compat/csaori の Android 用に差し替える（実装は csaori.cpp の分も shim/pono_csaori.cpp）。
# 元のソースは同じフォルダの csaori_base.h を先に読むので、使うものをビルドのフォルダに写して、差し替えの見出しと並べる
set(MAHJONGUTIL_SRC ${SAORI_DIR}/MahjongUtil/MahjongUtil)
set(MAHJONGUTIL_GEN ${CMAKE_CURRENT_BINARY_DIR}/mahjongutil)
set(MAHJONGUTIL_FILES
    MahjongUtil.cpp MahjongUtil.h csaori.h
    mj_common.cpp mj_common.h mj_machi.cpp mj_machi.h mj_score.cpp mj_score.h mj_shanten.cpp mj_shanten.h)
foreach(f ${MAHJONGUTIL_FILES})
    configure_file(${MAHJONGUTIL_SRC}/${f} ${MAHJONGUTIL_GEN}/${f} COPYONLY)
endforeach()
foreach(f csaori_base.h csaori_util.h)
    configure_file(${CMAKE_CURRENT_SOURCE_DIR}/shim/compat/csaori/${f} ${MAHJONGUTIL_GEN}/${f} COPYONLY)
endforeach()
add_library(saori_mahjongutil SHARED
    ${MAHJONGUTIL_GEN}/MahjongUtil.cpp
    ${MAHJONGUTIL_GEN}/mj_common.cpp
    ${MAHJONGUTIL_GEN}/mj_machi.cpp
    ${MAHJONGUTIL_GEN}/mj_score.cpp
    ${MAHJONGUTIL_GEN}/mj_shanten.cpp
    shim/pono_csaori.cpp)
target_include_directories(saori_mahjongutil PRIVATE ${MAHJONGUTIL_GEN})
# MahjongUtil.cpp が CreateInstance を持つ（Windows の vcxproj と同じ）
target_compile_definitions(saori_mahjongutil PRIVATE CUSTOM_CREATE_INSTANCE)
# 見出しの中の「クラス名::関数名」（MSVC だけが通す書き方）を通す
target_compile_options(saori_mahjongutil PRIVATE -fms-extensions)
pono_saori_library(saori_mahjongutil)

# ninix-saori の saori_curl（URL のテキストを取る。saori_curl.dll）。libcurl（サブモジュール saori/curl）を HTTP / HTTPS だけで作り、
# TLS は luamod/boringssl（Kagari と同じ）。証明書は Android の /system/etc/security/cacerts を見る。
# curl の CMake は find_package(OpenSSL) で探して試しにリンクするが、configure のときには BoringSSL はまだできていないので、
# OpenSSL::SSL・OpenSSL::Crypto を BoringSSL の ssl・crypto として先に置き、調べた結果も先に入れておく
add_library(OpenSSL::Crypto INTERFACE IMPORTED)
set_target_properties(OpenSSL::Crypto PROPERTIES
    INTERFACE_LINK_LIBRARIES crypto INTERFACE_INCLUDE_DIRECTORIES ${LUAMOD_DIR}/boringssl/include)
add_library(OpenSSL::SSL INTERFACE IMPORTED)
set_target_properties(OpenSSL::SSL PROPERTIES
    INTERFACE_LINK_LIBRARIES "ssl;OpenSSL::Crypto" INTERFACE_INCLUDE_DIRECTORIES ${LUAMOD_DIR}/boringssl/include)
set(OPENSSL_INCLUDE_DIR ${LUAMOD_DIR}/boringssl/include CACHE PATH "" FORCE)
set(OPENSSL_SSL_LIBRARY ssl CACHE STRING "" FORCE)
set(OPENSSL_CRYPTO_LIBRARY crypto CACHE STRING "" FORCE)
set(HAVE_BORINGSSL 1 CACHE INTERNAL "")
set(HAVE_AWSLC 0 CACHE INTERNAL "")
set(HAVE_LIBRESSL 0 CACHE INTERNAL "")
set(HAVE_DES_ECB_ENCRYPT 1 CACHE INTERNAL "")
set(HAVE_SSL_SET0_WBIO 1 CACHE INTERNAL "")
foreach(off BUILD_CURL_EXE BUILD_SHARED_LIBS BUILD_LIBCURL_DOCS BUILD_MISC_DOCS ENABLE_CURL_MANUAL BUILD_EXAMPLES
        CURL_ZLIB CURL_BROTLI CURL_ZSTD USE_NGHTTP2 USE_LIBIDN2 CURL_USE_LIBPSL CURL_USE_LIBSSH2 CURL_USE_LIBSSH
        USE_LIBRTMP CURL_USE_GSSAPI CURL_ENABLE_EXPORT_TARGET)
    set(${off} OFF CACHE BOOL "" FORCE)
endforeach()
foreach(on CURL_USE_OPENSSL HTTP_ONLY CURL_DISABLE_LDAP CURL_DISABLE_INSTALL)
    set(${on} ON CACHE BOOL "" FORCE)
endforeach()
set(CURL_CA_PATH /system/etc/security/cacerts CACHE STRING "" FORCE)
set(CURL_CA_BUNDLE none CACHE STRING "" FORCE)
add_subdirectory(${SAORI_DIR}/curl ${CMAKE_CURRENT_BINARY_DIR}/curl EXCLUDE_FROM_ALL)
# curl の自前の ASN1_STRING_get_length が BoringSSL のものとぶつかるので、curl の側の名前を変える
set_source_files_properties(${SAORI_DIR}/curl/lib/vtls/openssl.c DIRECTORY ${SAORI_DIR}/curl/lib PROPERTIES
    COMPILE_OPTIONS "-include;${CMAKE_CURRENT_SOURCE_DIR}/shim/compat/pono_curl_boringssl.h")

set(NINIX_SAORI_DIR ${SAORI_DIR}/ninix-saori)
add_library(saori_curl SHARED
    ${NINIX_SAORI_DIR}/implementation/saori_curl/lib.cc
    ${NINIX_SAORI_DIR}/skeleton/base/header.cc
    shim/pono_saori_ninix.cpp)
target_include_directories(saori_curl PRIVATE ${NINIX_SAORI_DIR} ${NINIX_SAORI_DIR}/skeleton)
target_compile_definitions(saori_curl PRIVATE PONO_NINIX_SAORI=saori_curl)
target_link_libraries(saori_curl PRIVATE CURL::libcurl)
pono_saori_library(saori_curl)
# 元の Makefile と同じく C++20
target_compile_options(saori_curl PRIVATE $<$<COMPILE_LANGUAGE:CXX>:-std=c++20>)

# ---- Rust の SAORI ----
# tukinami さんたちの Rust の SAORI（サブモジュール saori/saori-*）。Gradle（prepareRustSaoriSources）が RUST_SAORI_DIR に
# Android 用の lib.rs・chars.rs と並べて写したものを、cargo で staticlib にしてから libsaori_<名前>.so に入れる。
# 入口は shim/pono_saori_rust.cpp。cargo の出力は RUST_TARGET_DIR に、Rust の target ごとに分かれる
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(RUST_TARGET aarch64-linux-android)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(RUST_TARGET armv7-linux-androideabi)
elseif(ANDROID_ABI STREQUAL "x86")
    set(RUST_TARGET i686-linux-android)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(RUST_TARGET x86_64-linux-android)
else()
    message(FATAL_ERROR "Rust SAORI: unknown ABI ${ANDROID_ABI}")
endif()

# cargo に NDK の clang を渡す（C のソースを持つ crate の cc と、cdylib を持つ crate のリンク）
string(REPLACE "-" "_" RUST_TARGET_ENV ${RUST_TARGET})
string(TOUPPER ${RUST_TARGET_ENV} RUST_TARGET_ENV_UPPER)
set(PONO_CARGO_ENV
    CARGO_TARGET_DIR=${RUST_TARGET_DIR}
    CC_${RUST_TARGET_ENV}=${CMAKE_C_COMPILER}
    # LUA_UCID: pasta の Lua（mlua の vendored）で、名前に UTF-8 を許す（pasta は辞書を日本語の名前の Lua に変える）
    "CFLAGS_${RUST_TARGET_ENV}=--target=${CMAKE_C_COMPILER_TARGET} -DLUA_UCID"
    AR_${RUST_TARGET_ENV}=${CMAKE_AR}
    CARGO_TARGET_${RUST_TARGET_ENV_UPPER}_LINKER=${CMAKE_C_COMPILER}
    CARGO_TARGET_${RUST_TARGET_ENV_UPPER}_RUSTFLAGS=-Clink-arg=--target=${CMAKE_C_COMPILER_TARGET})

# [dir] の Cargo.toml を cargo で作り、lib<[lib]>.a を [target]_cargo の出力にする。[ARGN] は cargo build に足す引数（-p など）
function(pono_cargo_archive target dir lib)
    set(archive ${RUST_TARGET_DIR}/${RUST_TARGET}/release/lib${lib}.a)
    file(GLOB_RECURSE sources CONFIGURE_DEPENDS ${dir}/*)
    add_custom_command(
        OUTPUT ${archive}
        COMMAND ${CMAKE_COMMAND} -E env ${PONO_CARGO_ENV}
            ${CARGO} build --release --locked --quiet --target ${RUST_TARGET} --manifest-path ${dir}/Cargo.toml ${ARGN}
        DEPENDS ${sources}
        COMMENT "cargo build ${dir} (${RUST_TARGET})"
        VERBATIM)
    add_custom_target(${target}_cargo DEPENDS ${archive})
    add_dependencies(${target} ${target}_cargo)
    # Rust の std が使うもの（cargo rustc -- --print native-static-libs の -ldl -llog -lunwind -lm -lc）
    target_link_libraries(${target} PRIVATE ${archive} dl unwind m)
endfunction()

# [crate] は Cargo.toml の package の名前、[lib] は lib の名前（lib<名前>.a になる）
function(pono_rust_saori target crate lib)
    add_library(${target} SHARED shim/pono_saori_rust.cpp)
    pono_cargo_archive(${target} ${RUST_SAORI_DIR}/${crate} ${lib})
    pono_saori_library(${target})
endfunction()

pono_rust_saori(saori_calcs saori-calcs calcs)
pono_rust_saori(saori_japanesegengou saori-japanese-gengou japanesegengou)
pono_rust_saori(saori_resizedpngmini saori-resized-png-mini resizedpngmini)
pono_rust_saori(saori_qrcode saori-qrcode saori_qrcode)
pono_rust_saori(saori_resizedpng saori-resized-png resizedpng)
pono_rust_saori(saori_piechart saori-pie-chart piechart)

# ---- Rust の SHIORI ----
# Gradle（prepareRustShioriSources）が RUST_SHIORI_DIR に写したものを、cargo で staticlib にしてから lib<名前>.so に入れる。
# 入口は jni_<名前>.cpp
#   libminato.so 湊（minato。winapi は src/main/rust/pono-winapi に替える）
#   libpasta.so  pasta（Lua は PUC Lua 5.4。shiori4 の版 3 の口を使う）
add_library(minato SHARED jni_minato.cpp)
pono_cargo_archive(minato ${RUST_SHIORI_DIR}/minato minato)
pono_shiori_library(minato)

add_library(pasta SHARED jni_pasta.cpp)
pono_cargo_archive(pasta ${RUST_SHIORI_DIR}/pasta pasta -p pasta_shiori)
pono_shiori_library(pasta)

# ---- quickshiori（JavaScript の SHIORI。サブモジュール quickshiori と、その中の QuickJS-ng・qjs-ng-sqlite3）----
# Windows の部分（<windows.h>）は shim/compat/quickshiori/windows.h に替える（実装は shim/pono_quickshiori.cpp）。
# JS から import する ukadll.dll（SAORI を呼ぶ）と sqlite3.dll / .so は、Windows では別の DLL だが、ここでは
# libquickshiori.so に入れて js_module_loader（-Wl,--wrap）から返す。sqlite は YAYA の sqlite3_yaya.c（同じ amalgamation）。
# sqlite3-cmake（ビルドのときに sqlite を落としてくる）と WinRT・FMO の部品は使わない
set(QS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/quickshiori)
set(QJS_DIR ${QS_DIR}/3rd/quickjs)
set(QS_QJS_SOURCES
    ${QJS_DIR}/dtoa.c ${QJS_DIR}/libregexp.c ${QJS_DIR}/libunicode.c ${QJS_DIR}/quickjs.c ${QJS_DIR}/quickjs-libc.c)
set(QS_MODULE_SOURCES ${QS_DIR}/module/ukadll/quickjs_ukadll.cpp ${QS_DIR}/3rd/qjs-ng-sqlite3/sqlite3.c ${YAYA_DIR}/sqlite3_yaya.c)
set_source_files_properties(${QS_QJS_SOURCES} ${QS_MODULE_SOURCES} PROPERTIES COMPILE_OPTIONS -w COMPILE_DEFINITIONS _GNU_SOURCE)
# ukadll の入口（Windows の DLL の js_init_module）の名前を変えて、同じ .so に入れる
set_source_files_properties(${QS_DIR}/module/ukadll/quickjs_ukadll.cpp PROPERTIES
    COMPILE_OPTIONS "-w;-std=c++20" COMPILE_DEFINITIONS js_init_module=pono_qs_init_ukadll)
set_source_files_properties(${YAYA_DIR}/sqlite3_yaya.c PROPERTIES INCLUDE_DIRECTORIES ${YAYA_DIR})
# 元の CMakeLists.txt と同じく version.h を作る（版は元の project() と同じ 0.0.3）
set(PROJECT_VERSION_MAJOR 0)
set(PROJECT_VERSION_MINOR 0)
set(PROJECT_VERSION_PATCH 3)
set(PROJECT_VERSION 0.0.3)
configure_file(${QS_DIR}/src/version.h.in ${CMAKE_CURRENT_BINARY_DIR}/quickshiori/version.h)
add_library(quickshiori SHARED
    ${QS_QJS_SOURCES}
    ${QS_MODULE_SOURCES}
    ${QS_DIR}/src/quickshiori.cpp
    ${QS_DIR}/src/quickshiori_module.cpp
    $<TARGET_OBJECTS:pono_iconv>
    shim/pono_quickshiori.cpp
    jni_quickshiori.cpp)
set_source_files_properties(${QS_DIR}/src/quickshiori.cpp ${QS_DIR}/src/quickshiori_module.cpp PROPERTIES
    COMPILE_OPTIONS "-w;-std=c++20")
# <windows.h> を差し替えの見出しにする。<sqlite3.h> は YAYA の sqlite
target_include_directories(quickshiori PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/shim/compat/quickshiori
    ${CMAKE_CURRENT_SOURCE_DIR}/shim
    ${QJS_DIR}
    ${YAYA_DIR}/sqlite
    ${CMAKE_CURRENT_BINARY_DIR}/quickshiori)
target_compile_options(quickshiori PRIVATE $<$<COMPILE_LANGUAGE:CXX>:-std=c++20>)
target_link_options(quickshiori PRIVATE -Wl,--wrap=js_module_loader)
target_link_libraries(quickshiori PRIVATE m)
pono_shiori_library(quickshiori)
