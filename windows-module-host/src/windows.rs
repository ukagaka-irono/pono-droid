use std::ffi::{c_char, c_void};
use std::fs::File;
use std::io;
use std::os::windows::io::FromRawHandle;
use std::os::windows::ffi::OsStrExt;
use std::path::Path;
use std::ptr;

type Handle = *mut c_void;
type Load = unsafe extern "C" fn(Handle, i32) -> i32;
type Request = unsafe extern "C" fn(Handle, *mut i32) -> Handle;
type Unload = unsafe extern "C" fn() -> i32;

#[link(name = "ucrt")]
extern "C" {
    fn _dup2(source: i32, target: i32) -> i32;
}

#[link(name = "kernel32")]
extern "system" {
    fn LoadLibraryExW(path: *const u16, file: Handle, flags: u32) -> Handle;
    fn FreeLibrary(module: Handle) -> i32;
    fn GetProcAddress(module: Handle, name: *const c_char) -> *mut c_void;
    fn GlobalAlloc(flags: u32, bytes: usize) -> Handle;
    fn GlobalFree(memory: Handle) -> Handle;
    fn GlobalSize(memory: Handle) -> usize;
    fn SetCurrentDirectoryW(path: *const u16) -> i32;
    fn SetDllDirectoryW(path: *const u16) -> i32;
    fn WideCharToMultiByte(cp: u32, flags: u32, wide: *const u16, wide_len: i32, bytes: *mut u8, len: i32, default: *const u8, used: *mut i32) -> i32;
    fn GetACP() -> u32;
    fn GetCurrentProcess() -> Handle;
    fn GetStdHandle(id: u32) -> Handle;
    fn SetStdHandle(id: u32, handle: Handle) -> i32;
    fn DuplicateHandle(source: Handle, handle: Handle, target: Handle, result: *mut Handle, access: u32, inherit: i32, options: u32) -> i32;
    fn SetErrorMode(mode: u32) -> u32;
}

fn error(context: &str) -> String { format!("{context}: {}", io::Error::last_os_error()) }
fn wide(path: &Path) -> Vec<u16> { path.as_os_str().encode_wide().chain(Some(0)).collect() }

/// The DLL owns each input buffer after the call; the host owns and frees the returned buffer.
unsafe fn allocate(bytes: &[u8]) -> Result<Handle, String> {
    let memory = GlobalAlloc(0, bytes.len() + 1);
    if memory.is_null() { return Err(error("GlobalAlloc")); }
    ptr::copy_nonoverlapping(bytes.as_ptr(), memory.cast::<u8>(), bytes.len());
    *memory.cast::<u8>().add(bytes.len()) = 0;
    Ok(memory)
}

struct Library(Handle);
impl Drop for Library { fn drop(&mut self) { unsafe { FreeLibrary(self.0); } } }

pub struct Module {
    library: Library,
    request: Request,
    unload: Unload,
}

impl Module {
    pub fn load(bytes: &[u8]) -> Result<Option<Self>, String> {
        let text = std::str::from_utf8(bytes).map_err(|_| "DLL path is not UTF-8")?;
        if text.contains('\0') { return Err("DLL path contains NUL".into()); }
        let path = Path::new(text);
        if !path.is_absolute() || !path.is_file() { return Err("DLL path must name an existing absolute file".into()); }
        let parent = path.parent().ok_or("DLL path has no parent")?;
        let directory = wide(parent);
        unsafe {
            if SetCurrentDirectoryW(directory.as_ptr()) == 0 || SetDllDirectoryW(directory.as_ptr()) == 0 {
                return Err(error("setting DLL directory"));
            }
            let library = Library(LoadLibraryExW(wide(path).as_ptr(), ptr::null_mut(), 8));
            if library.0.is_null() { return Err(error("LoadLibraryExW")); }
            let request = GetProcAddress(library.0, b"request\0".as_ptr().cast());
            let unload = GetProcAddress(library.0, b"unload\0".as_ptr().cast());
            let loadu = GetProcAddress(library.0, b"loadu\0".as_ptr().cast());
            let load = if loadu.is_null() { GetProcAddress(library.0, b"load\0".as_ptr().cast()) } else { loadu };
            if request.is_null() || unload.is_null() || load.is_null() {
                return Err("DLL must export request, unload, and loadu or load (cdecl)".into());
            }
            let mut module_dir = parent.as_os_str().to_os_string();
            module_dir.push("\\");
            let input = if !loadu.is_null() {
                module_dir.to_str().ok_or("DLL directory is not Unicode")?.as_bytes().to_vec()
            } else {
                let utf16: Vec<u16> = module_dir.encode_wide().collect();
                let cp = GetACP();
                // UTF-8 ACP does not accept lpUsedDefaultChar; legacy code pages must not silently replace a path.
                let mut substituted = 0;
                let used = if cp == 65001 { ptr::null_mut() } else { &mut substituted };
                let len = WideCharToMultiByte(cp, 0, utf16.as_ptr(), utf16.len() as i32, ptr::null_mut(), 0, ptr::null(), used);
                if len <= 0 { return Err(error("encoding DLL directory")); }
                let mut encoded = vec![0; len as usize];
                let written = WideCharToMultiByte(cp, 0, utf16.as_ptr(), utf16.len() as i32, encoded.as_mut_ptr(), len, ptr::null(), used);
                if written != len || substituted != 0 { return Err("DLL directory cannot be encoded in the Windows ANSI code page".into()); }
                encoded
            };
            let load: Load = std::mem::transmute(load);
            if load(allocate(&input)?, input.len() as i32) == 0 { return Ok(None); }
            Ok(Some(Self { library, request: std::mem::transmute(request), unload: std::mem::transmute(unload) }))
        }
    }

    pub fn request(&mut self, input: &[u8]) -> Result<Option<Vec<u8>>, String> {
        unsafe {
            let mut length = input.len() as i32;
            let memory = (self.request)(allocate(input)?, &mut length);
            if memory.is_null() { return Ok(None); }
            let result = if length < 0 || length as usize > super::MAX_FRAME || length as usize > GlobalSize(memory) {
                Err("DLL returned an invalid response length".into())
            } else {
                Ok(Some(std::slice::from_raw_parts(memory.cast::<u8>(), length as usize).to_vec()))
            };
            GlobalFree(memory);
            result
        }
    }
}

impl Drop for Module {
    fn drop(&mut self) {
        unsafe { (self.unload)(); }
        // Keep the library loaded until unload has returned. The containing process handles a crash or hang.
        let _ = &self.library;
    }
}

pub fn protocol_streams() -> io::Result<(File, File)> {
    unsafe {
        // Avoid a modal Windows error dialog on crash/missing dependencies; the client monitors process exit.
        SetErrorMode(0x0001 | 0x0002 | 0x8000);
        let process = GetCurrentProcess();
        let duplicate = |id| -> io::Result<File> {
            let mut handle = ptr::null_mut();
            if DuplicateHandle(process, GetStdHandle(id), process, &mut handle, 0, 0, 2) == 0 { return Err(io::Error::last_os_error()); }
            Ok(File::from_raw_handle(handle))
        };
        let input = duplicate(-10i32 as u32)?;
        let output = duplicate(-11i32 as u32)?;
        // The C runtime caches stdout before main. Redirect it too, so DLL printf/_write
        // cannot mix diagnostic bytes into the framed protocol.
        if _dup2(2, 1) != 0 { return Err(io::Error::other("redirecting CRT stdout")); }
        if SetStdHandle(-11i32 as u32, GetStdHandle(-12i32 as u32)) == 0 { return Err(io::Error::last_os_error()); }
        Ok((input, output))
    }
}
