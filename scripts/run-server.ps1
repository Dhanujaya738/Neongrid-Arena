# Starts the NeonGrid server (builds first if needed). Any options are passed to the server, e.g.
#   ./scripts/run-server.ps1 --admin-pass MySecret --port 9090 --udp 9999
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root
if (-not (Test-Path build\neongrid-arena.jar)) { & "$PSScriptRoot\build.ps1" }
chcp 65001 > $null
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
java "-Dstdout.encoding=UTF-8" "-Dstderr.encoding=UTF-8" -jar build\neongrid-arena.jar @args
