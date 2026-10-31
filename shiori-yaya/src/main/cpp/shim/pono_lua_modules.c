// Kagari（Lua）に入れる C のモジュール。Kagari のゴーストやプラグイン（kotori・kagari_as_plugin の corelib）は
// Windows の DLL や Linux の .so を require するが、Android では読めない（サンドボックスも止める）ので、
// ソースから作ったものを package.preload に置く。package ライブラリを開くとき（luaopen_package。-Wl,--wrap）に足すので、
// sol2 が開く VM にも、lanes が作る VM にも入る
#include "lauxlib.h"
#include "lua.h"

int pono_luaopen_lua_utf8(lua_State *L);   // luautf8（名前は Lua 本体の utf8 と重ならないように変えてある）
int luaopen_iconv(lua_State *L);           // lua-iconv（中身は shim/pono_iconv.cpp）
int luaopen_luachild(lua_State *L);        // luachild（プロセスの起動はサンドボックスが止める）
int luaopen_fh(lua_State *L);              // shim/pono_lua_fh.c
int luaopen_saori_universal_native(lua_State *L);  // shim/pono_lua_saori.c
int luaopen_socket_core(lua_State *L);     // luasocket
int luaopen_mime_core(lua_State *L);
int luaopen_lanes_core(lua_State *L);      // lanes
int luaopen_ssl_core(lua_State *L);        // luasec（TLS は BoringSSL）
int luaopen_ssl_context(lua_State *L);
int luaopen_ssl_x509(lua_State *L);
int luaopen_ssl_config(lua_State *L);

static const luaL_Reg kModules[] = {
    {"lua-utf8", pono_luaopen_lua_utf8},
    {"iconv", luaopen_iconv},
    {"luachild", luaopen_luachild},
    {"fh", luaopen_fh},
    {"saori_universal.native", luaopen_saori_universal_native},
    {"socket.core", luaopen_socket_core},
    {"mime.core", luaopen_mime_core},
    {"lanes.core", luaopen_lanes_core},
    {"ssl.core", luaopen_ssl_core},
    {"ssl.context", luaopen_ssl_context},
    {"ssl.x509", luaopen_ssl_x509},
    {"ssl.config", luaopen_ssl_config},
    {NULL, NULL},
};

int __real_luaopen_package(lua_State *L);

int __wrap_luaopen_package(lua_State *L) {
    int n = __real_luaopen_package(L);
    lua_getfield(L, -1, "preload");
    for (const luaL_Reg *m = kModules; m->name != NULL; ++m) {
        lua_pushcfunction(L, m->func);
        lua_setfield(L, -2, m->name);
    }
    lua_pop(L, 1);
    return n;
}
