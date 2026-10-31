// kotori の corelib の fh（fh/init.dll・fh/init.so の代わり。元のソースは公開されていない）。
// process（luachild のパイプ）が使う 2 つだけ:
//   fh.readable(file)        待たずに読めるものがあるか
//   fh.blocking(file, bool)  読み書きを待つ（true）か待たない（false）か
#include <fcntl.h>
#include <poll.h>
#include <stdio.h>

#include "lauxlib.h"
#include "lua.h"
#include "lualib.h"

static int FileDescriptor(lua_State *L) {
    luaL_Stream *s = (luaL_Stream *)luaL_checkudata(L, 1, LUA_FILEHANDLE);
    if (s->closef == NULL) return luaL_error(L, "attempt to use a closed file");
    return fileno(s->f);
}

static int Readable(lua_State *L) {
    struct pollfd p = {FileDescriptor(L), POLLIN, 0};
    lua_pushboolean(L, poll(&p, 1, 0) > 0 && (p.revents & (POLLIN | POLLHUP)) != 0);
    return 1;
}

static int Blocking(lua_State *L) {
    int fd = FileDescriptor(L);
    int flags = fcntl(fd, F_GETFL);
    if (flags < 0) {
        lua_pushboolean(L, 0);
        return 1;
    }
    flags = lua_toboolean(L, 2) ? (flags & ~O_NONBLOCK) : (flags | O_NONBLOCK);
    lua_pushboolean(L, fcntl(fd, F_SETFL, flags) == 0);
    return 1;
}

int luaopen_fh(lua_State *L) {
    static const luaL_Reg kFuncs[] = {{"readable", Readable}, {"blocking", Blocking}, {NULL, NULL}};
    luaL_newlib(L, kFuncs);
    return 1;
}
