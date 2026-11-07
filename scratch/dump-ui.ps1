param(
  [string]$Label = "dump",
  [switch]$Top,
  [switch]$Switches
)
$adb = "D:\android-ad-cleaner\tools\platform-tools\adb.exe"
$xml = Join-Path $env:TEMP "ui_$Label.xml"
& $adb shell uiautomator dump /sdcard/ui_dump.xml 2>$null | Out-Null
$null = & $adb pull /sdcard/ui_dump.xml $xml 2>$null
if (-not (Test-Path $xml)) { Write-Host "dump failed"; return }
[xml]$doc = Get-Content $xml -Encoding UTF8
$dmH = 2720
foreach ($n in $doc.SelectNodes("//node")) {
  $b = ($n.bounds -replace '[\[\]]','') -split ','
  if ($b.Count -ne 4) { continue }
  $l=[int]$b[0]; $t=[int]$b[1]; $r=[int]$b[2]; $bt=[int]$b[3]
  if ($Top -and $t -gt $dmH * 0.30) { continue }
  if ($Switches -and $n.checkable -ne "true") { continue }
  $txt = $n.text; $cd = $n.'content-desc'
  if (-not $txt -and -not $cd -and $n.clickable -ne "true") { continue }
  $flags = @()
  if ($n.clickable -eq "true") { $flags += "C" }
  if ($n.checkable -eq "true") { $flags += ("K" + $n.checked) }
  if ($n.scrollable -eq "true") { $flags += "S" }
  $cls = $n.class -replace '^.*\.',''
  Write-Host ("[{0,4},{1,4}][{2,4},{3,4}] {4,-5} {5,-22} text='{6}' cd='{7}'" -f $l,$t,$r,$bt, ($flags -join ''), $cls, $txt, $cd)
}
