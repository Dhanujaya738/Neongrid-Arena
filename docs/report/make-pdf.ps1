# Renders report.html and team-guide.html to PDF with headless Microsoft Edge (or Chrome).
# Edit the member names / index numbers in report.html first, then run:  ./docs/report/make-pdf.ps1
Set-Location $PSScriptRoot
$browsers = @(
  "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe",
  "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe",
  "$env:ProgramFiles\Google\Chrome\Application\chrome.exe"
)
$exe = $browsers | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $exe) { Write-Error "Edge/Chrome not found"; exit 1 }
$profileDir = Join-Path $env:TEMP "neongrid-pdf-profile"
foreach ($pair in @(@("report.html", "NeonGrid-Arena-Report.pdf"), @("team-guide.html", "NeonGrid-Team-Guide.pdf"))) {
  $html = (Resolve-Path $pair[0]).Path
  $pdf = Join-Path $PSScriptRoot $pair[1]
  & $exe --headless=new --disable-gpu --no-pdf-header-footer "--user-data-dir=$profileDir" "--print-to-pdf=$pdf" "file:///$($html -replace '\\','/')" 2>$null | Out-Null
  Start-Sleep -Seconds 1
  if (Test-Path $pdf) { Write-Host "Wrote $pdf" -ForegroundColor Cyan } else { Write-Error "Could not create $pdf" }
}
