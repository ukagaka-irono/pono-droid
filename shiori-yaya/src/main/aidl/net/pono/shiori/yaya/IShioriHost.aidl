package net.pono.shiori.yaya;

// 本体（アプリのプロセス）の窓口。:shiori の SHIORI が、request などの途中で本体に頼むときに使う。
interface IShioriHost {
    /**
     * SAORI over DirectSSTP（ゴーストが内蔵モジュール internal_* を呼んだ）。SAORI/1.0 の要求を渡して応答を受け取る。
     * [module] は Module ヘッダが無いときのモジュール名（null でよい）。答えられなければ null
     */
    byte[] saori(in byte[] request, String module);
}
