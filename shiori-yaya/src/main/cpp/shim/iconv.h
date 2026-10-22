// YAYA の ccct.cpp が使う <iconv.h> の代わり。
// Android の libc の iconv は API 28 からで、しかも CP932 を持たないため、
// UTF-8 <-> CP932 だけを持つ自前の実装に置き換える（名前は libc と衝突しないよう変える）。
#pragma once

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef void *iconv_t;

#define iconv_open pono_iconv_open
#define iconv pono_iconv
#define iconv_close pono_iconv_close

iconv_t pono_iconv_open(const char *tocode, const char *fromcode);
size_t pono_iconv(iconv_t cd, char **inbuf, size_t *inbytesleft, char **outbuf, size_t *outbytesleft);
int pono_iconv_close(iconv_t cd);

#ifdef __cplusplus
}
#endif
