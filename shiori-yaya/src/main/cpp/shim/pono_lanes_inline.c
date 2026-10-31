// lanes の lanes_private.h の lua_toLane は static の付かない inline で、C99 の決まりでは外から呼べる本体がどこにも
// 作られない（元の Makefile の gcc は最適化で全部埋め込むので困らない）。ここで 1 つだけ作る（読む順は lanes の cancel.c と同じ）
#include <assert.h>
#include <string.h>

#include "threading.h"
#include "cancel.h"
#include "tools.h"
#include "lanes_private.h"

extern inline Lane *lua_toLane(lua_State *L, int i_);
