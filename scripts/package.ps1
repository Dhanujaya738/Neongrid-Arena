# Creates the Moodle submission ZIP next to the repository folder:
#   complete source + web client + scripts + docs + report PDF.
# Run docs/report/make-pdf.ps1 first if you edited the report (e.g. to add member names).
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root
$zip = Join-Path (Split-Path $root -Parent) "NeonGridArena-Submission.zip"
$stageRoot = Join-Path $env:TEMP "neongrid-package"
$stage = Join-Path $stageRoot "NeonGridArena"
if (Test-Path $stageRoot) { Remove-Item -Recurse -Force $stageRoot }
New-Item -ItemType Directory -Force $stage | Out-Null
foreach ($item in @("src", "web", "docs", "scripts", "README.md", "CONTRIBUTING.md", ".gitignore", ".gitattributes")) {
  if (Test-Path $item) { Copy-Item -Recurse -Force $item $stage }
}
Copy-Item docs\report\NeonGrid-Arena-Report.pdf (Join-Path $stageRoot "NeonGrid-Arena-Report.pdf")
if (Test-Path $zip) { Remove-Item $zip }
Compress-Archive -Path (Join-Path $stageRoot "*") -DestinationPath $zip
Write-Host "Created $zip" -ForegroundColor Cyan
