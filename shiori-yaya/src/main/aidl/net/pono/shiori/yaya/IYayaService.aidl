package net.pono.shiori.yaya;

// 別プロセス（:yaya）で動く YAYA。SHIORI の load / request / unload をそのまま渡す。
interface IYayaService {
    /** ghost/master のパスを渡す。戻り値はこのゴーストの番号（0 以下なら失敗） */
    long load(String dir);

    /** 生の SHIORI リクエストを渡し、生のレスポンスを返す。応答なしは null */
    byte[] request(long handle, in byte[] request);

    void unload(long handle);
}
