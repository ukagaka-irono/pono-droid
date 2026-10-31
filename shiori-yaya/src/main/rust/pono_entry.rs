// tukinami さんの Rust の SAORI の lib.rs（Windows の DllMain と HGLOBAL の load / request / unload）の代わり。
// ビルドのときに写したソースへ置き、lib.rs から mod pono_entry; で読む（:shiori-yaya:prepareRustSaoriSources）。
// 中身の処理（procedure・request・response）は元のソースのまま使い、ここは shim/pono_saori_rust.cpp からの呼び出しを受ける。
//
// 元の DLL_PATH（DLL のフルパス）の代わりに、DLL のあったディレクトリを持つ。元の procedure は DLL_PATH が
// ディレクトリでなければ 1 つ上を使うので、ディレクトリをそのまま渡せば同じ所を指す

use std::panic::{self, AssertUnwindSafe};
use std::slice;
use std::sync::Mutex;

use crate::procedure;
use crate::request::{SaoriCommand, SaoriRequest};
use crate::response::{self, SaoriResponse};

static DLL_PATH: Mutex<Option<String>> = Mutex::new(None);

fn dll_path() -> Option<String> {
    DLL_PATH.lock().unwrap_or_else(|e| e.into_inner()).clone()
}

/// SAORI の引数の Windows の区切り（\）を / にする。元の procedure が DLL のディレクトリに足すパスに使う
#[allow(dead_code)]
pub(crate) fn native_path(path: &str) -> String {
    path.replace('\\', "/")
}

/// SAORI の load。[dir] は DLL のあったディレクトリ（UTF-8、末尾は /）
///
/// # Safety
/// [dir] は [len] バイト読めること
#[no_mangle]
pub unsafe extern "C" fn pono_rust_saori_load(dir: *const u8, len: usize) -> bool {
    let bytes = unsafe { slice::from_raw_parts(dir, len) };
    let Ok(dir) = std::str::from_utf8(bytes) else {
        return false;
    };
    let dir = dir.trim_end_matches('/').to_string();
    *DLL_PATH.lock().unwrap_or_else(|e| e.into_inner()) = Some(dir.clone());
    panic::catch_unwind(|| procedure::load(&dir)).is_ok()
}

/// SAORI の unload
#[no_mangle]
pub extern "C" fn pono_rust_saori_unload() -> bool {
    let Some(path) = dll_path() else {
        return false;
    };
    let ok = panic::catch_unwind(|| procedure::unload(&path)).is_ok();
    *DLL_PATH.lock().unwrap_or_else(|e| e.into_inner()) = None;
    ok
}

/// SAORI の request。答えを [out_len] バイトの列で返す（pono_rust_saori_free で放す）。
/// 元のソースの中で panic したら 500 を返す
///
/// # Safety
/// [request] は [len] バイト読めること、[out_len] は書けること
#[no_mangle]
pub unsafe extern "C" fn pono_rust_saori_request(request: *const u8, len: usize, out_len: *mut usize) -> *mut u8 {
    // 元と同じく末尾に NUL を足して渡す
    let mut s = unsafe { slice::from_raw_parts(request, len) }.to_vec();
    s.push(0);

    let bytes = panic::catch_unwind(AssertUnwindSafe(|| respond(&s)))
        .unwrap_or_else(|_| b"SAORI/1.0 500 Internal Server Error\r\n\r\n".to_vec());
    let boxed = bytes.into_boxed_slice();
    unsafe { *out_len = boxed.len() };
    Box::into_raw(boxed) as *mut u8
}

/// pono_rust_saori_request の答えを放す
///
/// # Safety
/// [data] と [len] は pono_rust_saori_request が返したものであること
#[no_mangle]
pub unsafe extern "C" fn pono_rust_saori_free(data: *mut u8, len: usize) {
    drop(unsafe { Box::from_raw(slice::from_raw_parts_mut(data, len)) });
}

// 元の lib.rs の request と同じ組み立て
fn respond(s: &[u8]) -> Vec<u8> {
    let request = SaoriRequest::from_u8(s);

    let mut response = match &request {
        Ok(r) => SaoriResponse::from_request(r),
        Err(_e) => SaoriResponse::new_bad_request(),
    };

    match (dll_path(), request) {
        (None, _) => {
            response.set_status(response::SaoriStatus::InternalServerError);
        }
        (Some(path), Ok(r)) => match r.command() {
            SaoriCommand::GetVersion => {
                procedure::get_version(&path, &r, &mut response);
            }
            SaoriCommand::Execute => {
                procedure::execute(&path, &r, &mut response);
            }
        },
        _ => {}
    }

    // 元は変換した列（末尾の NUL を含む）をそのまま返し、ベースウェアは NUL までを読む
    let bytes: Vec<u8> = response.to_encoded_bytes().unwrap_or(Vec::new()).into_iter().map(|v| v as u8).collect();
    let end = bytes.iter().position(|v| *v == 0).unwrap_or(bytes.len());
    bytes[..end].to_vec()
}
