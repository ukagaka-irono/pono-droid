// curl の lib/vtls/openssl.c を BoringSSL で作るための見出し。ビルドのときにこのファイルだけ -include で先に読ませる。
// BoringSSL は OpenSSL 1.1.1 を名乗りながら ASN1_STRING_get_length（OpenSSL 4.1 の関数）を持つので、curl が自前で作る
// static の同じ名前の関数とぶつかる。BoringSSL の宣言を先に読ませてから、curl の側の名前だけを変える
#pragma once

#include <openssl/asn1.h>

#define ASN1_STRING_get_length pono_curl_asn1_string_get_length
