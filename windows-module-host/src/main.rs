//! Process boundary for Windows UKAGAKA modules. No SHIORI/SAORI text is decoded here.
//! Wire format: command/status byte, little-endian u32 length, then exactly length bytes.
//! Commands: 1 load (UTF-8 absolute DLL path), 2 request (opaque bytes), 3 unload, 4 exit.
//! Status: 0 success, 1 module returned FALSE, 2 host error (UTF-8), 3 null response.

use std::io::{self, Read, Write};

const MAX_FRAME: usize = 16 * 1024 * 1024;

fn read_frame(input: &mut impl Read) -> io::Result<Option<(u8, Vec<u8>)>> {
    let mut command = [0];
    if input.read(&mut command)? == 0 { return Ok(None); }
    let mut size = [0; 4];
    input.read_exact(&mut size)?;
    let size = u32::from_le_bytes(size) as usize;
    if size > MAX_FRAME { return Err(io::Error::new(io::ErrorKind::InvalidData, "frame too large")); }
    let mut data = vec![0; size];
    input.read_exact(&mut data)?;
    Ok(Some((command[0], data)))
}

fn write_frame(output: &mut impl Write, status: u8, data: &[u8]) -> io::Result<()> {
    if data.len() > MAX_FRAME { return Err(io::Error::new(io::ErrorKind::InvalidData, "frame too large")); }
    output.write_all(&[status])?;
    output.write_all(&(data.len() as u32).to_le_bytes())?;
    output.write_all(data)?;
    output.flush()
}

#[cfg(windows)]
mod windows;

#[cfg(windows)]
fn main() -> io::Result<()> {
    // Preserve the protocol handles and redirect DLL stdout/stderr to stderr. Modules sometimes print diagnostics.
    let (mut input, mut output) = windows::protocol_streams()?;
    write_frame(&mut output, 0, b"PONO-MODULE/1")?;
    let mut module: Option<windows::Module> = None;
    while let Some((command, data)) = read_frame(&mut input)? {
        let result: Result<(u8, Vec<u8>), String> = match command {
            1 if module.is_some() => Err("a module is already loaded".into()),
            1 => windows::Module::load(&data).map(|loaded| {
                let status = if loaded.is_some() { 0 } else { 1 };
                module = loaded;
                (status, Vec::new())
            }),
            2 => match module.as_mut() {
                Some(m) => m.request(&data).map(|reply| match reply {
                    Some(bytes) => (0, bytes),
                    None => (3, Vec::new()),
                }),
                None => Err("no module loaded".into()),
            },
            3 | 4 if !data.is_empty() => Err("unexpected command payload".into()),
            3 => { module = None; Ok((0, Vec::new())) },
            4 => { drop(module); write_frame(&mut output, 0, &[])?; return Ok(()); },
            _ => Err("unknown command".into()),
        };
        match result {
            Ok((status, bytes)) => write_frame(&mut output, status, &bytes)?,
            Err(message) => write_frame(&mut output, 2, message.as_bytes())?,
        }
    }
    Ok(())
}

#[cfg(not(windows))]
fn main() { eprintln!("Build this host for Windows (x86 or x64), then run it on Windows or Wine."); std::process::exit(1); }

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn frames_keep_binary_data_and_reject_truncation_and_excessive_lengths() {
        let mut bytes = Vec::new();
        write_frame(&mut bytes, 2, &[0, 0x81, 0xff, 13, 10]).unwrap();
        assert_eq!(read_frame(&mut bytes.as_slice()).unwrap(), Some((2, vec![0, 0x81, 0xff, 13, 10])));
        assert!(read_frame(&mut &bytes[..bytes.len()-1]).is_err());
        assert!(read_frame(&mut &[1, 0xff, 0xff, 0xff, 0xff][..]).is_err());
        assert_eq!(read_frame(&mut &[][..]).unwrap(), None);
    }
}
