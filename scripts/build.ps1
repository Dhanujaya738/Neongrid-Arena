# Builds NeonGrid with plain javac (JDK 17+, no Maven needed).
# Output: build/classes and build/neongrid-arena.jar
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root
if (Test-Path build) { Remove-Item -Recurse -Force build }
$sources = Get-ChildItem -Recurse -Filter *.java src | ForEach-Object { $_.FullName }
javac --release 17 -encoding UTF-8 -d build\classes $sources
if ($LASTEXITCODE -ne 0) { exit 1 }
jar --create --file build\neongrid-arena.jar --main-class arena.server.ArenaServer -C build\classes .
Write-Host "Built build\neongrid-arena.jar" -ForegroundColor Cyan
