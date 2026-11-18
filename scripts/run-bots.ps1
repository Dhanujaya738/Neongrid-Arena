# Load test: N bots on ONE client-side Selector thread.
#   ./scripts/run-bots.ps1                 -> 100 bots, server discovered via UDP
#   ./scripts/run-bots.ps1 --bots 300 --host localhost --mode ultimate --think 300
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root
if (-not (Test-Path build\classes)) { & "$PSScriptRoot\build.ps1" }
$a = $args
if ($a.Count -eq 0) { $a = @("--bots", "100") }
java -cp build\classes arena.client.BotSwarm @a
