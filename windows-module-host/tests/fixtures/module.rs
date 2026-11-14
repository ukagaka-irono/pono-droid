//! A DLL built from this source for tests only; no downloaded ghost code is executed.
use std::ffi::c_void;
#[cfg(not(missing_request))]
use std::ptr;
type Handle = *mut c_void;
#[cfg(not(missing_request))]
#[link(name = "ucrt")]
extern "C" {
    fn _write(fd: i32, buffer: *const u8, length: u32) -> i32;
}
#[link(name = "kernel32")]
extern "system" {
    #[cfg(not(missing_request))]
    fn GlobalAlloc(flags: u32, len: usize) -> Handle;
    fn GlobalFree(memory: Handle) -> Handle;
}

#[cfg(not(legacy))]
#[no_mangle]
pub unsafe extern "C" fn loadu(input: Handle, len: i32) -> i32 {
    let bytes = std::slice::from_raw_parts(input.cast::<u8>(), len as usize);
    let valid = std::str::from_utf8(bytes).is_ok() && bytes.last() == Some(&b'\\');
    GlobalFree(input);
    i32::from(valid)
}

#[no_mangle]
pub unsafe extern "C" fn load(input: Handle, len: i32) -> i32 {
    let valid = len > 0 && *input.cast::<u8>().add(len as usize - 1) == b'\\';
    GlobalFree(input);
    // Modern fixtures deliberately reject load: the host must prefer loadu.
    i32::from(cfg!(legacy) && valid)
}

#[no_mangle]
pub extern "C" fn unload() -> i32 { 1 }

#[cfg(not(missing_request))]
#[no_mangle]
pub unsafe extern "C" fn request(input: Handle, len: *mut i32) -> Handle {
    let bytes = std::slice::from_raw_parts(input.cast::<u8>(), *len as usize).to_vec();
    GlobalFree(input);
    if bytes == b"null" { *len = 0; return ptr::null_mut(); }
    if bytes == b"log" {
        let message = b"fixture DLL diagnostic\n";
        _write(1, message.as_ptr(), message.len() as u32);
        println!("fixture Rust diagnostic");
    }
    if bytes == b"crash" { std::process::exit(42); }
    if bytes == b"hang" || bytes.windows(b"ID: OnHang\r\n".len()).any(|w| w == b"ID: OnHang\r\n") {
        loop { std::thread::sleep(std::time::Duration::from_secs(60)); }
    }
    let result = GlobalAlloc(0, bytes.len() + 1);
    ptr::copy_nonoverlapping(bytes.as_ptr(), result.cast::<u8>(), bytes.len());
    *len = if bytes == b"oversize" { 32 * 1024 * 1024 } else { bytes.len() as i32 };
    result
}
