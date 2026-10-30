// Kagari（Lua）だけに入れる横取り（-Wl,--wrap）。サブモジュールは書き換えない。
//   - lua_load: いつもテキストとして読む。Lua のバイトコードは検査されないので、細工したもので
//     メモリを壊せる（load・loadfile・dofile・require と、Kagari が index.lua を読むのも全部ここを通る）
//   - luaopen_debug: debug ライブラリは traceback と getinfo だけにする（getregistry・setupvalue・
//     upvaluejoin などで Lua の中身を書き換えられないように）
#include "lua.h"
#include "lauxlib.h"

int __real_lua_load(lua_State *L, lua_Reader reader, void *data, const char *chunkname, const char *mode);
int __real_luaopen_debug(lua_State *L);

int __wrap_lua_load(lua_State *L, lua_Reader reader, void *data, const char *chunkname, const char *mode) {
    (void)mode;
    return __real_lua_load(L, reader, data, chunkname, "t");
}

int __wrap_luaopen_debug(lua_State *L) {
    static const char *const kKept[] = {"traceback", "getinfo", NULL};
    __real_luaopen_debug(L);
    lua_newtable(L);
    for (int i = 0; kKept[i] != NULL; ++i) {
        lua_getfield(L, -2, kKept[i]);
        lua_setfield(L, -2, kKept[i]);
    }
    lua_remove(L, -2);
    return 1;
}
