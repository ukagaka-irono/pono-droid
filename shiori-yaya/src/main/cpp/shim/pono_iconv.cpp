#include "iconv.h"

#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <strings.h>

#include "cp932_table.inc"

namespace {

enum Direction : intptr_t { kCp932ToUtf8 = 1, kUtf8ToCp932 = 2 };

bool IsCp932(const char *name) {
    return strcasecmp(name, "CP932") == 0 || strcasecmp(name, "WINDOWS-31J") == 0 ||
           strcasecmp(name, "SHIFT_JIS") == 0 || strcasecmp(name, "SJIS") == 0;
}

bool IsUtf8(const char *name) {
    return strcasecmp(name, "UTF-8") == 0 || strcasecmp(name, "UTF8") == 0;
}

// テーブルは先頭の値で昇順に並んでいる
int Lookup(const uint16_t (*table)[2], size_t n, uint16_t key) {
    size_t lo = 0, hi = n;
    while (lo < hi) {
        size_t mid = (lo + hi) / 2;
        if (table[mid][0] < key) {
            lo = mid + 1;
        } else {
            hi = mid;
        }
    }
    return (lo < n && table[lo][0] == key) ? table[lo][1] : -1;
}

size_t PutUtf8(uint32_t c, char *out) {
    if (c < 0x80) {
        out[0] = (char)c;
        return 1;
    }
    if (c < 0x800) {
        out[0] = (char)(0xC0 | (c >> 6));
        out[1] = (char)(0x80 | (c & 0x3F));
        return 2;
    }
    if (c < 0x10000) {
        out[0] = (char)(0xE0 | (c >> 12));
        out[1] = (char)(0x80 | ((c >> 6) & 0x3F));
        out[2] = (char)(0x80 | (c & 0x3F));
        return 3;
    }
    out[0] = (char)(0xF0 | (c >> 18));
    out[1] = (char)(0x80 | ((c >> 12) & 0x3F));
    out[2] = (char)(0x80 | ((c >> 6) & 0x3F));
    out[3] = (char)(0x80 | (c & 0x3F));
    return 4;
}

// 戻り値は消費したバイト数。0 なら途中で切れている、-1 なら不正
int GetUtf8(const unsigned char *in, size_t len, uint32_t *c) {
    unsigned char b = in[0];
    int n;
    if (b < 0x80) {
        *c = b;
        return 1;
    } else if ((b & 0xE0) == 0xC0) {
        n = 2;
        *c = b & 0x1F;
    } else if ((b & 0xF0) == 0xE0) {
        n = 3;
        *c = b & 0x0F;
    } else if ((b & 0xF8) == 0xF0) {
        n = 4;
        *c = b & 0x07;
    } else {
        return -1;
    }
    if (len < (size_t)n) {
        return 0;
    }
    for (int i = 1; i < n; ++i) {
        if ((in[i] & 0xC0) != 0x80) {
            return -1;
        }
        *c = (*c << 6) | (in[i] & 0x3F);
    }
    return n;
}

bool Emit(const char *bytes, size_t n, char **outbuf, size_t *outleft) {
    if (*outleft < n) {
        errno = E2BIG;
        return false;
    }
    for (size_t i = 0; i < n; ++i) {
        (*outbuf)[i] = bytes[i];
    }
    *outbuf += n;
    *outleft -= n;
    return true;
}

size_t Cp932ToUtf8(char **inbuf, size_t *inleft, char **outbuf, size_t *outleft) {
    while (*inleft > 0) {
        const unsigned char *in = (const unsigned char *)*inbuf;
        uint32_t c;
        size_t used;
        if (in[0] < 0x80) {
            c = in[0];
            used = 1;
        } else if (in[0] >= 0xA1 && in[0] <= 0xDF) {
            c = 0xFF61 + (in[0] - 0xA1);  // 半角カナ
            used = 1;
        } else {
            if (*inleft < 2) {
                errno = EINVAL;
                return (size_t)-1;
            }
            int u = Lookup(kCp932Decode, sizeof(kCp932Decode) / sizeof(kCp932Decode[0]),
                           (uint16_t)((in[0] << 8) | in[1]));
            if (u < 0) {
                errno = EILSEQ;
                return (size_t)-1;
            }
            c = (uint32_t)u;
            used = 2;
        }
        char tmp[4];
        if (!Emit(tmp, PutUtf8(c, tmp), outbuf, outleft)) {
            return (size_t)-1;
        }
        *inbuf += used;
        *inleft -= used;
    }
    return 0;
}

size_t Utf8ToCp932(char **inbuf, size_t *inleft, char **outbuf, size_t *outleft) {
    while (*inleft > 0) {
        uint32_t c;
        int used = GetUtf8((const unsigned char *)*inbuf, *inleft, &c);
        if (used <= 0) {
            errno = used == 0 ? EINVAL : EILSEQ;
            return (size_t)-1;
        }
        char tmp[2];
        size_t n;
        if (c < 0x80) {
            tmp[0] = (char)c;
            n = 1;
        } else if (c >= 0xFF61 && c <= 0xFF9F) {
            tmp[0] = (char)(0xA1 + (c - 0xFF61));
            n = 1;
        } else {
            int s = c > 0xFFFF ? -1
                               : Lookup(kCp932Encode, sizeof(kCp932Encode) / sizeof(kCp932Encode[0]),
                                        (uint16_t)c);
            if (s < 0) {
                errno = EILSEQ;
                return (size_t)-1;
            }
            tmp[0] = (char)(s >> 8);
            tmp[1] = (char)(s & 0xFF);
            n = 2;
        }
        if (!Emit(tmp, n, outbuf, outleft)) {
            return (size_t)-1;
        }
        *inbuf += used;
        *inleft -= (size_t)used;
    }
    return 0;
}

}  // namespace

extern "C" iconv_t pono_iconv_open(const char *tocode, const char *fromcode) {
    if (IsUtf8(tocode) && IsCp932(fromcode)) {
        return (iconv_t)kCp932ToUtf8;
    }
    if (IsCp932(tocode) && IsUtf8(fromcode)) {
        return (iconv_t)kUtf8ToCp932;
    }
    errno = EINVAL;
    return (iconv_t)-1;
}

extern "C" size_t pono_iconv(iconv_t cd, char **inbuf, size_t *inbytesleft, char **outbuf,
                             size_t *outbytesleft) {
    // シフト状態を持つ文字コードは扱わないので、リセットは何もしない
    if (inbuf == NULL || *inbuf == NULL) {
        return 0;
    }
    switch ((intptr_t)cd) {
        case kCp932ToUtf8:
            return Cp932ToUtf8(inbuf, inbytesleft, outbuf, outbytesleft);
        case kUtf8ToCp932:
            return Utf8ToCp932(inbuf, inbytesleft, outbuf, outbytesleft);
        default:
            errno = EBADF;
            return (size_t)-1;
    }
}

extern "C" int pono_iconv_close(iconv_t) { return 0; }
