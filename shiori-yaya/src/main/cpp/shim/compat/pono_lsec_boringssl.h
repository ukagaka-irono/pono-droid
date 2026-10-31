// luasec（OpenSSL 向け）を BoringSSL で作るための見出し。ビルドのときに -include で先に読ませる（サブモジュールは書き換えない）。
// BoringSSL の版の番号は OpenSSL 1.1.1 を名乗るので、luasec はそれにある関数を使おうとする。無いものを埋める:
//   - DANE（SSL_dane_enable・SSL_dane_tlsa_add）は無いので、いつも失敗にする（conn:setdane / settlsa が false を返す）
//   - SSL_up_ref は luaossl の SSL を受け取るときだけ使う。luaossl は入れないので、そこには来ない
//   - SSL_X509_LOOKUP（SSL_want の値）は BoringSSL では返らないので、OpenSSL と同じ値を置くだけ
#pragma once

#include <stddef.h>
#include <stdint.h>

struct ssl_st;

static inline int pono_lsec_dane_enable(struct ssl_st *ssl, const char *basedomain) {
    (void)ssl;
    (void)basedomain;
    return 0;
}

static inline int pono_lsec_dane_tlsa_add(struct ssl_st *ssl, uint8_t usage, uint8_t selector, uint8_t mtype,
                                          const unsigned char *data, size_t len) {
    (void)ssl;
    (void)usage;
    (void)selector;
    (void)mtype;
    (void)data;
    (void)len;
    return 0;
}

static inline int pono_lsec_up_ref(struct ssl_st *ssl) {
    (void)ssl;
    return 1;
}

struct ssl_ctx_st;

static inline int pono_lsec_ctx_dane_enable(struct ssl_ctx_st *ctx) {
    (void)ctx;
    return 0;
}

static inline unsigned long pono_lsec_ctx_dane_set_flags(struct ssl_ctx_st *ctx, unsigned long flags) {
    (void)ctx;
    (void)flags;
    return 0;
}

// BoringSSL は TLS 1.3 の暗号の組を選ばせない（いつも決まった組を使う）。指定は受け取って何もしない
static inline int pono_lsec_ctx_set_ciphersuites(struct ssl_ctx_st *ctx, const char *list) {
    (void)ctx;
    (void)list;
    return 1;
}

#define SSL_dane_enable pono_lsec_dane_enable
#define SSL_dane_tlsa_add pono_lsec_dane_tlsa_add
#define SSL_CTX_dane_enable pono_lsec_ctx_dane_enable
#define SSL_CTX_dane_set_flags pono_lsec_ctx_dane_set_flags
#define SSL_CTX_set_ciphersuites pono_lsec_ctx_set_ciphersuites
#define SSL_up_ref pono_lsec_up_ref
#define SSL_X509_LOOKUP 4
