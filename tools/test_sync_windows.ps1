param(
    [Parameter(Mandatory=$true)][string]$Adb,
    [Parameter(Mandatory=$true)][string]$Device,
    [string]$Python = '.venv-impact/Scripts/python.exe',
    [string]$PhpDirectory = 'deps/php/runtime'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Push-Location $root
try {
    $php = (Resolve-Path -LiteralPath $PhpDirectory).Path
    $env:PATH = "$php;$env:PATH"
    $env:REDWOOD_TEST_ADB = (Resolve-Path -LiteralPath $Adb).Path
    $env:REDWOOD_TEST_DEVICE = $Device
    & $Python tests/test_sync_server.py
    if ($LASTEXITCODE -ne 0) { throw 'Isolated sync integration tests failed' }
} finally { Pop-Location }
