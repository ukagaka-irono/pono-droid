// tukinami さんの Rust の SAORI の chars.rs（Windows の MultiByteToWideChar・WideCharToMultiByte）の代わり。
// ビルドのときに写したソースの chars.rs をこれで置き換える（:shiori-yaya:prepareRustSaoriSources）。
// UTF-8 は Rust でそのまま扱い、CP932 と EUC-JP は SHIORI と同じ自前の iconv（shim/pono_iconv.cpp）を通す。
// ISO-2022-JP は扱わない（変換に失敗したことにする）。
//
// 元と同じく、変換できないバイトや文字は Windows と同じように置き換える（読むときは U+FFFD、書くときは '?'）。
// 返す列の末尾には、元と同じく 0 を 1 つ付ける

use std::ffi::{c_char, c_int, c_void};

extern "C" {
    fn pono_iconv_open(tocode: *const c_char, fromcode: *const c_char) -> *mut c_void;
    fn pono_iconv(
        cd: *mut c_void,
        inbuf: *mut *mut c_char,
        inbytesleft: *mut usize,
        outbuf: *mut *mut c_char,
        outbytesleft: *mut usize,
    ) -> usize;
    fn pono_iconv_close(cd: *mut c_void) -> c_int;
}

/// Windows のコードページの番号を、pono_iconv の文字コード名にする。UTF-8 は None
fn iconv_name(codepage: u32) -> Result<Option<&'static [u8]>, ()> {
    match codepage {
        65001 => Ok(None),
        932 => Ok(Some(b"CP932\0")),
        20932 | 51932 => Ok(Some(b"EUC-JP\0")),
        _ => Err(()),
    }
}

struct Iconv(*mut c_void);

impl Iconv {
    fn open(to: &[u8], from: &[u8]) -> Result<Iconv, ()> {
        let cd = unsafe { pono_iconv_open(to.as_ptr() as *const c_char, from.as_ptr() as *const c_char) };
        if cd as isize == -1 {
            Err(())
        } else {
            Ok(Iconv(cd))
        }
    }

    /// [input] を変換して [out] に足す。全部変換できれば None、途中で止まればそこまでに読んだバイト数
    fn convert(&self, input: &[u8], out: &mut Vec<u8>) -> Option<usize> {
        let mut buf = vec![0u8; input.len() * 4 + 4];
        let mut in_ptr = input.as_ptr() as *mut c_char;
        let mut in_left = input.len();
        let mut out_ptr = buf.as_mut_ptr() as *mut c_char;
        let mut out_left = buf.len();
        let r = unsafe { pono_iconv(self.0, &mut in_ptr, &mut in_left, &mut out_ptr, &mut out_left) };
        out.extend_from_slice(&buf[..buf.len() - out_left]);
        if r == usize::MAX {
            Some(input.len() - in_left)
        } else {
            None
        }
    }
}

impl Drop for Iconv {
    fn drop(&mut self) {
        unsafe { pono_iconv_close(self.0) };
    }
}

pub(crate) fn multi_byte_to_wide_char(from: &[u8], codepage: u32) -> Result<Vec<u16>, ()> {
    // 元は NUL までを変換する
    let from = &from[..from.iter().position(|v| *v == 0).unwrap_or(from.len())];
    let text = match iconv_name(codepage)? {
        None => String::from_utf8_lossy(from).into_owned(),
        Some(name) => {
            let cd = Iconv::open(b"UTF-8\0", name)?;
            let mut utf8 = Vec::new();
            let mut rest = from;
            while let Some(used) = cd.convert(rest, &mut utf8) {
                // 読めないバイトは 1 つずつ U+FFFD にして続ける
                utf8.extend_from_slice("\u{FFFD}".as_bytes());
                rest = &rest[used + 1..];
            }
            String::from_utf8_lossy(&utf8).into_owned()
        }
    };
    let mut wide: Vec<u16> = text.encode_utf16().collect();
    wide.push(0);
    Ok(wide)
}

pub(crate) fn wide_char_to_multi_byte(from: &mut Vec<u16>, codepage: u32) -> Result<Vec<i8>, ()> {
    let end = from.iter().position(|v| *v == 0).unwrap_or(from.len());
    let text = String::from_utf16_lossy(&from[..end]);
    let mut bytes = match iconv_name(codepage)? {
        None => text.into_bytes(),
        Some(name) => {
            let cd = Iconv::open(name, b"UTF-8\0")?;
            let mut out = Vec::new();
            if cd.convert(text.as_bytes(), &mut out).is_some() {
                // 書けない文字があれば、1 文字ずつ変換して書けない文字を '?' にする
                out.clear();
                let mut one = [0u8; 4];
                for c in text.chars() {
                    let mark = out.len();
                    if cd.convert(c.encode_utf8(&mut one).as_bytes(), &mut out).is_some() {
                        out.truncate(mark);
                        out.push(b'?');
                    }
                }
            }
            out
        }
    };
    bytes.push(0);
    Ok(bytes.into_iter().map(|v| v as i8).collect())
}
