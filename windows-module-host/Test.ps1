param([switch]$Offline)

$ErrorActionPreference = 'Stop'
$repository = Split-Path -Parent $PSScriptRoot
Push-Location $repository
try {
    $cargoOptions = @('--locked')
    if ($Offline) { $cargoOptions += '--offline' }
    foreach ($entry in @(
        @{ Target = 'i686-pc-windows-msvc'; Name = 'X86' },
        @{ Target = 'x86_64-pc-windows-msvc'; Name = 'X64' }
    )) {
        cargo test --manifest-path windows-module-host/Cargo.toml --target $entry.Target @cargoOptions
        if ($LASTEXITCODE -ne 0) { throw "Rust tests failed for $($entry.Target)" }
        $fixtureDir = Join-Path $PSScriptRoot "target/fixtures/$($entry.Name.ToLowerInvariant())"
        New-Item -ItemType Directory -Path $fixtureDir -Force | Out-Null
        $fixture = Join-Path $fixtureDir 'shiori.dll'
        rustc windows-module-host/tests/fixtures/module.rs --crate-type cdylib --edition 2021 --target $entry.Target -o $fixture
        if ($LASTEXITCODE -ne 0) { throw "Fixture compilation failed for $($entry.Target)" }
        $hostFile = Join-Path $PSScriptRoot "target/$($entry.Target)/debug/pono-module-host.exe"
        Set-Item -LiteralPath "Env:PONO_WINDOWS_HOST_$($entry.Name)" -Value $hostFile
        Set-Item -LiteralPath "Env:PONO_WINDOWS_DLL_$($entry.Name)" -Value $fixture
    }
    $env:PONO_REQUIRE_WINDOWS_HOST = '1'
    $gradleOptions = @(':baseware:test', '--tests', 'net.pono.baseware.shiori.ProcessShioriIntegrationTest', '--console=plain', '--rerun-tasks')
    if ($Offline) { $gradleOptions += '--offline' }
    & .\gradlew.bat @gradleOptions
    if ($LASTEXITCODE -ne 0) { throw 'JVM / Windows host integration tests failed' }
} finally {
    Pop-Location
}
