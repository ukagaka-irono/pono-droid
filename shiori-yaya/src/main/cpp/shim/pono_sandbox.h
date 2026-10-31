// YAYA（ゴーストの辞書）にできることを絞る。pono_sandbox.cpp を参照。
#pragma once

#ifdef __cplusplus
extern "C" {
#endif

// このディレクトリの下は読み書きしてよい（ゴーストのディレクトリ）。シンボリックリンクは解決してから覚える
void pono_sandbox_allow(const char *dir);
void pono_sandbox_forget(const char *dir);

// このディレクトリの下は、allow したところ以外は読むのもだめ（アプリの領域）
void pono_sandbox_protect(const char *dir);

// このディレクトリの下は、protect の中でも読むだけならよい（プラグインに見せるゴーストとバルーンの置き場）
void pono_sandbox_allow_read(const char *dir);

#ifdef __cplusplus
}
#endif
