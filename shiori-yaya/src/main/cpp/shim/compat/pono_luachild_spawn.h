// luachild（posix_spawnp でプロセスを起こす）を Android の API 26 で作るための見出し。ビルドのときに -include で先に読ませる。
// posix_spawn は API 28 からなので見出しに出てこないが、どのみちサンドボックス（shim/pono_sandbox.cpp の
// __wrap_posix_spawnp）が止めるので、ここでは宣言と、何もしない file_actions を用意するだけにする
#pragma once

#include <errno.h>
#include <spawn.h>

#if __ANDROID_API__ < 28
int posix_spawnp(pid_t *pid, const char *file, const posix_spawn_file_actions_t *actions,
                 const posix_spawnattr_t *attr, char *const argv[], char *const envp[]);

static inline int pono_spawn_actions_noop(void) { return 0; }
#define posix_spawn_file_actions_init(actions) ((void)(actions), pono_spawn_actions_noop())
#define posix_spawn_file_actions_destroy(actions) ((void)(actions), pono_spawn_actions_noop())
#define posix_spawn_file_actions_adddup2(actions, fd, newfd) ((void)(actions), (void)(fd), (void)(newfd), pono_spawn_actions_noop())
#define posix_spawn_file_actions_addclose(actions, fd) ((void)(actions), (void)(fd), pono_spawn_actions_noop())
#endif
