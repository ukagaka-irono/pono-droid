// mahjong_nodoka と saori_budoux の util.h（Windows のときだけ中身があり、ほかは #error）の代わり。
// ビルドのときに -include で先に読ませ、元の util.h の二重読み込み防止の印を立てて中身を飛ばさせる。
// conv は pono_saori_tatakinov.cpp にある（CP932 ⇄ UTF-8）
#pragma once

#include <string>

#define UTIL_H_
#define SAORI_BUDOUX_UTIL_H_

enum Charset {
    Utf8 = 65001,
    Ansi = 932,
};

std::string conv(std::string input, int to, int from);
