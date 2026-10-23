package net.pono.shiori.yaya;

// 別プロセス（:shiori）で動く SHIORI（YAYA・里々・華和梨・Kagari）。SHIORI の load / request / unload をそのまま渡す。
interface IShioriService {
    /** [engine] は ShioriEngine の名前（YAYA など）、[dir] は ghost/master のパス。戻り値はこのゴーストの番号（0 以下なら失敗） */
    long load(String engine, String dir);

    /** 生の SHIORI リクエストを渡し、生のレスポンスを返す。応答なしは null */
    byte[] request(long handle, in byte[] request);

    void unload(long handle);

    /** :shiori のプロセス ID。辞書が無限ループして request が戻らないとき、プロセスごと止めるのに使う */
    int getPid();
}
