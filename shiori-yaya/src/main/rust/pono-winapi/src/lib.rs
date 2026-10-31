//! winapi crate の代わり（Android 用）。minato が使う型と関数だけを、同じ名前で libc の上に作る。
//!
//! - GlobalAlloc / GlobalFree / GlobalSize は malloc / free / malloc_usable_size。SHIORI と SAORI の受け渡しの
//!   バッファは、POSIX 版の約束どおり malloc したもの（jni_common.h・shim/pono_saori_guest.cpp と同じ）
//! - LoadLibraryW / GetProcAddress / FreeLibrary は dlopen / dlsym / dlclose。この呼び出しは SHIORI の .so の
//!   --wrap（shim/pono_sandbox.cpp）を通るので、YAYA などと同じく、ソースから作った SAORI と内蔵 SAORI だけが開ける

#![allow(non_snake_case, non_camel_case_types, clippy::missing_safety_doc)]

use core::ffi::{c_char, c_int, c_void};

extern "C" {
    fn malloc(size: usize) -> *mut c_void;
    fn free(ptr: *mut c_void);
    fn malloc_usable_size(ptr: *const c_void) -> usize;
    fn dlopen(filename: *const c_char, flags: c_int) -> *mut c_void;
    fn dlsym(handle: *mut c_void, symbol: *const c_char) -> *mut c_void;
    fn dlclose(handle: *mut c_void) -> c_int;
}

pub mod shared {
    pub mod minwindef {
        pub type HGLOBAL = *mut core::ffi::c_void;
        pub type HMODULE = *mut core::ffi::c_void;
        pub type FARPROC = *mut core::ffi::c_void;
    }
}

pub mod um {
    pub mod winbase {
        use crate::shared::minwindef::HGLOBAL;

        pub const GMEM_FIXED: u32 = 0;

        pub unsafe fn GlobalAlloc(_flags: u32, bytes: usize) -> HGLOBAL {
            // 0 バイトでも有効なポインタを返す（Windows と同じく、失敗のときだけ null）
            unsafe { crate::malloc(bytes.max(1)) }
        }

        /// 成功なら null を返す（Windows と同じ）
        pub unsafe fn GlobalFree(mem: HGLOBAL) -> HGLOBAL {
            unsafe { crate::free(mem) };
            core::ptr::null_mut()
        }

        pub unsafe fn GlobalSize(mem: HGLOBAL) -> usize {
            if mem.is_null() {
                0
            } else {
                unsafe { crate::malloc_usable_size(mem) }
            }
        }
    }

    pub mod libloaderapi {
        use crate::shared::minwindef::{FARPROC, HMODULE};

        const RTLD_NOW: core::ffi::c_int = 2;

        /// [name] は NUL で終わる UTF-16 のパス。区切りの \ は / にする
        pub unsafe fn LoadLibraryW(name: *const u16) -> HMODULE {
            let mut len = 0;
            while unsafe { *name.add(len) } != 0 {
                len += 1;
            }
            let path = String::from_utf16_lossy(unsafe { core::slice::from_raw_parts(name, len) }).replace('\\', "/");
            let Ok(path) = std::ffi::CString::new(path) else {
                return core::ptr::null_mut();
            };
            unsafe { crate::dlopen(path.as_ptr(), RTLD_NOW) }
        }

        pub unsafe fn GetProcAddress(module: HMODULE, name: *const i8) -> FARPROC {
            unsafe { crate::dlsym(module, name as *const core::ffi::c_char) }
        }

        pub unsafe fn FreeLibrary(module: HMODULE) -> i32 {
            (unsafe { crate::dlclose(module) } == 0) as i32
        }
    }
}
