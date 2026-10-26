param([switch]$CrossResource, [switch]$Unified)
$ErrorActionPreference = "Stop"
# Optional developer regression. APKs must already be installed. App data is never cleared.
$runner = "com.fantadrom.jjreader.test/androidx.test.runner.AndroidJUnitRunner"
$test = "com.fantadrom.jjreader.ReaderRuntimeTest#processRestartProbe"
$phaseArgument = "p02ProcessPhase"
if ($CrossResource) {
    $test = "com.fantadrom.jjreader.ReaderRuntimeTest#crossResourceProcessRestartProbe"
    $phaseArgument = "p05ProcessPhase"
}
if ($Unified) {
    $test = "com.fantadrom.jjreader.UnifiedReadingTest#unifiedProcessRestartProbe"
    $phaseArgument = "p07ProcessPhase"
}
function Run-Probe($phase) {
    $result = adb shell am instrument -w -e class $test -e $phaseArgument $phase $runner
    $result | Write-Output
    if ($LASTEXITCODE -ne 0 -or ($result -join "`n") -notmatch "OK \(1 test\)") { throw "Instrumentation phase $phase failed" }
}
Run-Probe "prepare"
adb shell am force-stop com.fantadrom.jjreader
if ($LASTEXITCODE -ne 0) { throw "Force-stop failed" }
Run-Probe "verify"
