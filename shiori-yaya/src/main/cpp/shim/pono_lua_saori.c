// kotori の corelib の saori_universal.native（native.dll・native.so の代わり。元のソースは公開されていない）。
// SAORI の DLL を読んで load / request / unload を呼ぶ:
//   local s = Native(path)   -- path は DLL のパス
//   s:load(dir)              -- dir は DLL のディレクトリ。成功なら true
//   s:request(str)           -- SAORI/1.0 の要求の全文を渡し、答えの全文を返す
//   s:unload()
// dlopen・dlsym は SHIORI と同じく shim/pono_sandbox.cpp の横取りを通るので、開けるのはソースから作った SAORI と
// 内蔵 SAORI だけ（YAYA・里々などと同じ）。受け渡しのバッファは malloc したもの（POSIX 版の SAORI の約束）
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>

#include "lauxlib.h"
#include "lua.h"

#define SAORI_META "pono.saori_universal.native"

typedef struct {
    void *lib;
    int (*load)(char *, long);
    char *(*request)(char *, long *);
    int (*unload)(void);
} Saori;

static Saori *Check(lua_State *L) {
    Saori *s = (Saori *)luaL_checkudata(L, 1, SAORI_META);
    if (s->lib == NULL) luaL_error(L, "the SAORI is already unloaded");
    return s;
}

static char *CopyToMalloc(const char *p, size_t len) {
    char *buf = (char *)malloc(len + 1);
    if (buf == NULL) return NULL;
    memcpy(buf, p, len);
    buf[len] = '\0';
    return buf;
}

static void Close(Saori *s) {
    if (s->lib == NULL) return;
    s->unload();
    dlclose(s->lib);
    s->lib = NULL;
}

static int Load(lua_State *L) {
    Saori *s = Check(L);
    size_t len;
    const char *dir = luaL_checklstring(L, 2, &len);
    char *buf = CopyToMalloc(dir, len);
    lua_pushboolean(L, buf != NULL && s->load(buf, (long)len) != 0);
    return 1;
}

static int Request(lua_State *L) {
    Saori *s = Check(L);
    size_t len;
    const char *req = luaL_checklstring(L, 2, &len);
    char *buf = CopyToMalloc(req, len);
    if (buf == NULL) return 0;
    long n = (long)len;
    char *res = s->request(buf, &n);
    if (res == NULL) return 0;
    lua_pushlstring(L, res, n > 0 ? (size_t)n : 0);
    free(res);
    return 1;
}

static int Unload(lua_State *L) {
    Close(Check(L));
    lua_pushboolean(L, 1);
    return 1;
}

static int Gc(lua_State *L) {
    Close((Saori *)luaL_checkudata(L, 1, SAORI_META));
    return 0;
}

// Native(path)。開けなければ nil とわけ
static int New(lua_State *L) {
    const char *path = luaL_checkstring(L, 1);
    void *lib = dlopen(path, RTLD_NOW);
    if (lib == NULL) {
        lua_pushnil(L);
        lua_pushfstring(L, "cannot load SAORI '%s'", path);
        return 2;
    }
    Saori *s = (Saori *)lua_newuserdatauv(L, sizeof(Saori), 0);
    s->lib = lib;
    s->load = (int (*)(char *, long))dlsym(lib, "load");
    s->request = (char *(*)(char *, long *))dlsym(lib, "request");
    s->unload = (int (*)(void))dlsym(lib, "unload");
    if (s->load == NULL || s->request == NULL || s->unload == NULL) {
        dlclose(lib);
        s->lib = NULL;
        lua_pushnil(L);
        lua_pushfstring(L, "'%s' is not a SAORI", path);
        return 2;
    }
    luaL_setmetatable(L, SAORI_META);
    return 1;
}

int luaopen_saori_universal_native(lua_State *L) {
    static const luaL_Reg kMethods[] = {{"load", Load}, {"request", Request}, {"unload", Unload}, {"__gc", Gc}, {NULL, NULL}};
    if (luaL_newmetatable(L, SAORI_META)) {
        luaL_setfuncs(L, kMethods, 0);
        lua_pushvalue(L, -1);
        lua_setfield(L, -2, "__index");
    }
    lua_pop(L, 1);
    // require の値は Native(path) として呼べるもの
    lua_pushcfunction(L, New);
    return 1;
}
