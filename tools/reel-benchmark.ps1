param(
    [string]$OutputDirectory = "target/reels-smoke"
)

$ErrorActionPreference = "Stop"
$artifactDirectory = Join-Path (Get-Location) $OutputDirectory
New-Item -ItemType Directory -Force -Path $artifactDirectory | Out-Null
$stdout = Join-Path $artifactDirectory "maven.stdout.log"
$stderr = Join-Path $artifactDirectory "maven.stderr.log"
$maven = (Get-Command mvn.cmd).Source
$arguments = @("-Dtest=FfmpegReelRendererIntegrationTest", "test")

$watch = [System.Diagnostics.Stopwatch]::StartNew()
$process = Start-Process -FilePath $maven -ArgumentList $arguments -WorkingDirectory (Get-Location) `
    -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru

$peakBytes = 0L
$cpuByPid = @{}
while (-not $process.HasExited) {
    Get-Process -Name ffmpeg -ErrorAction SilentlyContinue | ForEach-Object {
        if ($_.WorkingSet64 -gt $peakBytes) { $peakBytes = $_.WorkingSet64 }
        $cpuByPid[$_.Id] = $_.CPU
    }
    Start-Sleep -Milliseconds 100
    $process.Refresh()
}
$process.WaitForExit()
$process.Refresh()
$watch.Stop()

$exitCode = $process.ExitCode
if ($null -eq $exitCode) {
    $succeeded = Select-String -LiteralPath $stdout -SimpleMatch "BUILD SUCCESS" -Quiet
    if (-not $succeeded) {
        throw "No se pudo leer el código de salida de Maven ni confirmar BUILD SUCCESS."
    }
    $exitCode = 0
}
if ($exitCode -ne 0) {
    Get-Content -Raw -LiteralPath $stdout
    Get-Content -Raw -LiteralPath $stderr
    throw "La prueba de benchmark terminó con código $($process.ExitCode)."
}

$cpuSeconds = ($cpuByPid.Values | Measure-Object -Sum).Sum
$benchmark = Join-Path $artifactDirectory "benchmark.txt"
Add-Content -LiteralPath $benchmark -Encoding utf8 -Value (
    "observed_ffmpeg_peak_working_set_bytes=$peakBytes`n" +
    "observed_ffmpeg_cpu_seconds=$([math]::Round($cpuSeconds, 3))`n" +
    "maven_wall_millis=$($watch.ElapsedMilliseconds)"
)
Get-Content -Raw -LiteralPath $benchmark
