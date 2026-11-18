# Terminal client (blocking java.net.Socket). With no host it finds the server via UDP broadcast.
#   ./scripts/run-client.ps1
#   ./scripts/run-client.ps1 192.168.1.20 8080 Amal
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root
if (-not (Test-Path build\classes)) { & "$PSScriptRoot\build.ps1" }
chcp 65001 > $null
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
java "-Dstdout.encoding=UTF-8" -cp build\classes arena.client.ConsoleClient @args
