// YAYA の C++ ソースすべての先頭に -include される。サブモジュールを書き換えずに Android との差を埋める。
#pragma once

#include <errno.h>
#include <sys/stat.h>
#include <sys/types.h>

// statx は API 30 から。YAYA は STATX_BTIME があれば statx を使うので、無かったことにする
// （作成時刻は変更時刻などで代用される）
#if __ANDROID_API__ < 30
#undef STATX_BTIME
#endif

// Android には POSIX 共有メモリが無い。ninix の FMO を探す処理でしか使わないので、常に失敗させる
static inline int shm_open(const char *, int, mode_t) {
    errno = ENOSYS;
    return -1;
}
