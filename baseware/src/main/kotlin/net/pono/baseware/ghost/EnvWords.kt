package net.pono.baseware.ghost

import kotlin.random.Random

/**
 * 環境変数 %ms・%mz などで選ぶ単語（SSP は本体に単語表を持っている）。pono-droid の小さな単語表で、どれも思いつきの言葉。
 * %dms は「（場所）で（技）する（人）」のように組み合わせる
 */
object EnvWords {
    private val tables = mapOf(
        // 人
        "ms" to listOf("郵便屋さん", "となりのおばあさん", "旅の吟遊詩人", "小学生", "名探偵", "魔法使い", "パン屋の店長", "宇宙飛行士", "漁師", "図書館の司書"),
        // 無機物
        "mz" to listOf("消しゴム", "目覚まし時計", "電柱", "ガラス玉", "鉛筆削り", "自動販売機", "古いラジオ", "ネジ", "傘", "レンガ"),
        // 集合
        "ml" to listOf("町内会", "合唱団", "将棋部", "探検隊", "編集部", "吹奏楽部", "商店街", "天文同好会", "料理教室", "ご近所さん"),
        // 社名
        "mc" to listOf("ぽの商事", "まるいち工業", "ほしぞら製作所", "こもれび出版", "あおば運輸", "はなまる食品", "つきみ電機", "やまびこ通信"),
        // 店名
        "mh" to listOf("喫茶ぽの", "ひだまり書店", "まんまる食堂", "ことり雑貨店", "夜明けベーカリー", "みどり青果店", "かざぐるま文具", "ゆらぎ湯"),
        // 技
        "mt" to listOf("三段跳び", "早口言葉", "逆立ち", "あやとり", "けん玉", "口笛", "片手腕立て", "そろばん", "折り紙", "手品"),
        // 食物
        "me" to listOf("おにぎり", "たい焼き", "肉じゃが", "カレーライス", "ところてん", "みかん", "ホットケーキ", "焼きそば", "おでん", "プリン"),
        // 地名
        "mp" to listOf("裏山", "駅前", "海辺の町", "北の果て", "川向こう", "峠の茶屋", "港", "雲の上", "砂漠", "図書館の地下"),
    )

    /** [name]（ms / mz / ml / mc / mh / mt / me / mp / m? / dms）の単語。知らない名前なら null */
    fun pick(name: String, random: Random = Random.Default): String? = when (name) {
        // 非限定はどの表からでも
        "m?" -> tables.values.random(random).random(random)
        "dms" -> "${pick("mp", random)}で${pick("mt", random)}する${pick("ms", random)}"
        else -> tables[name]?.random(random)
    }
}
