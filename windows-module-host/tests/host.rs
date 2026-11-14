#![cfg(windows)]
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};

struct Host(Child);
impl Drop for Host { fn drop(&mut self) { let _ = self.0.kill(); let _ = self.0.wait(); } }
impl Host {
    fn start() -> Self {
        let mut host = Self(Command::new(env!("CARGO_BIN_EXE_pono-module-host"))
            .stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::inherit()).spawn().unwrap());
        assert_eq!(host.receive(), (0, b"PONO-MODULE/1".to_vec()));
        host
    }
    fn receive(&mut self) -> (u8, Vec<u8>) {
        let output = self.0.stdout.as_mut().unwrap();
        let mut header = [0; 5]; output.read_exact(&mut header).unwrap();
        let size = u32::from_le_bytes(header[1..].try_into().unwrap()) as usize;
        assert!(size <= 16 * 1024 * 1024);
        let mut bytes = vec![0; size]; output.read_exact(&mut bytes).unwrap();
        (header[0], bytes)
    }
    fn send(&mut self, command: u8, bytes: &[u8]) {
        let input = self.0.stdin.as_mut().unwrap();
        input.write_all(&[command]).unwrap();
        input.write_all(&(bytes.len() as u32).to_le_bytes()).unwrap();
        input.write_all(bytes).unwrap(); input.flush().unwrap();
    }
    fn call(&mut self, command: u8, bytes: &[u8]) -> (u8, Vec<u8>) { self.send(command, bytes); self.receive() }
    fn load(&mut self, path: &Path) -> (u8, Vec<u8>) { self.call(1, path.to_str().unwrap().as_bytes()) }
}

fn fixture(name: &str, cfg: Option<&str>, unicode: bool) -> PathBuf {
    let root = Path::new(env!("CARGO_MANIFEST_DIR"));
    let directory = root.join("target/test-fixtures").join(std::process::id().to_string()).join(name)
        .join(if unicode { "日本語の辞書" } else { "dictionary with spaces" });
    std::fs::create_dir_all(&directory).unwrap();
    let path = directory.join("shiori.dll");
    let mut compiler = Command::new(std::env::var_os("RUSTC").unwrap_or_else(|| "rustc".into()));
    compiler.arg(root.join("tests/fixtures/module.rs")).args(["--crate-type", "cdylib", "--edition", "2021", "--target"])
        .arg(if cfg!(target_arch = "x86") { "i686-pc-windows-msvc" } else { "x86_64-pc-windows-msvc" })
        .arg("-o").arg(&path);
    if let Some(value) = cfg { compiler.args(["--cfg", value]); }
    assert!(compiler.status().unwrap().success());
    path
}

#[test]
fn unicode_loadu_raw_bytes_null_and_lifecycle() {
    let dll = fixture("modern", None, true);
    let mut host = Host::start();
    assert_eq!(host.load(&dll), (0, vec![]));
    assert_eq!(host.load(&dll).0, 2);
    let bytes = b"GET SHIORI/3.0\r\nCharset: Shift_JIS\r\nReference0: \x82\xa0\0\xff\r\n\r\n";
    assert_eq!(host.call(2, bytes), (0, bytes.to_vec()));
    assert_eq!(host.call(2, b"log"), (0, b"log".to_vec()));
    assert_eq!(host.call(2, b"null"), (3, vec![]));
    assert_eq!(host.call(2, b"oversize").0, 2);
    assert_eq!(host.call(3, &[]), (0, vec![]));
    assert_eq!(host.call(2, b"after unload").0, 2);
    assert_eq!(host.load(&dll).0, 0);
    assert_eq!(host.call(4, &[]), (0, vec![]));
    assert!(host.0.wait().unwrap().success());
}

#[test]
fn legacy_load_and_missing_exports() {
    let legacy = fixture("legacy", Some("legacy"), false);
    let missing = fixture("missing", Some("missing_request"), false);
    let mut host = Host::start();
    assert_eq!(host.load(&missing).0, 2);
    assert_eq!(host.load(&legacy).0, 0);
    assert_eq!(host.call(2, b"legacy"), (0, b"legacy".to_vec()));
}

#[test]
fn dll_crash_ends_only_the_worker() {
    let dll = fixture("crash", None, false);
    let mut host = Host::start();
    assert_eq!(host.load(&dll).0, 0);
    host.send(2, b"crash");
    assert_eq!(host.0.wait().unwrap().code(), Some(42));
}
