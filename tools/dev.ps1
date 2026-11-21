# MultiSector task runner. Call it through dev.cmd in the repo root:
#
#   dev build      compile src/ into mod/jars/MultiSector.jar
#   dev install    build, then copy mod/ into <game>\mods\MultiSector
#   dev run        install, then start Starsector
#   dev log        show MultiSector lines from the game log
#   dev package    build, then zip the mod into dist/ to send to friends
#   dev clean      delete build/, dist/ and mod/jars/
#
#   dev ip                       show this PC's addresses (finds your Hamachi IP)
#   dev nettest host [port]      run a connection test host
#   dev nettest join <ip> [port] test the connection to a host
#   dev firewall [port]          open the port in Windows Firewall (asks for admin)
#   dev firewall remove          remove those firewall rules again
#
# The game folder defaults to D:\Starsector. Override it with the
# STARSECTOR_DIR environment variable, or -GameDir <path>.
# The JDK bundled with the game is used, so no separate Java install is needed.

param(
    [Parameter(Position = 0)][string]$Task = "help",
    [string]$GameDir = $(if ($env:STARSECTOR_DIR) { $env:STARSECTOR_DIR } else { "D:\Starsector" }),
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$Rest = @()
)

$DefaultPort = 7777   # keep in sync with NetConfig.DEFAULT_PORT

$ErrorActionPreference = "Stop"
trap { Write-Host "error: $($_.Exception.Message)" -ForegroundColor Red; exit 1 }
$root = Split-Path -Parent $PSScriptRoot
$modDir = Join-Path $root "mod"
$core = Join-Path $GameDir "starsector-core"
$jdkBin = Join-Path $GameDir "jre\bin"

function Assert-Game {
    if (-not (Test-Path (Join-Path $jdkBin "javac.exe"))) {
        throw "No javac at $jdkBin. Set STARSECTOR_DIR to your Starsector folder."
    }
}

function Invoke-Build {
    Assert-Game
    # Same jars the game puts on its own classpath.
    $libs = @(
        "starfarer.api.jar", "starfarer_obf.jar", "fs.common_obf.jar", "fs.sound_obf.jar",
        "json.jar", "log4j-1.2.9.jar", "lwjgl.jar", "lwjgl_util.jar", "xstream-1.4.10.jar"
    ) | ForEach-Object { Join-Path $core $_ }
    $missing = $libs | Where-Object { -not (Test-Path $_) }
    if ($missing) { throw "Missing game jars: $($missing -join ', ')" }

    $classes = Join-Path $root "build\classes"
    if (Test-Path $classes) { Remove-Item -Recurse -Force $classes }
    New-Item -ItemType Directory -Force $classes | Out-Null

    $sources = @(Get-ChildItem (Join-Path $root "src") -Recurse -Filter *.java | ForEach-Object FullName)
    $argFile = Join-Path $root "build\sources.txt"
    $sources | ForEach-Object { '"' + ($_ -replace '\\', '/') + '"' } | Set-Content -Encoding ascii $argFile

    Write-Host "Compiling $($sources.Count) source files..."
    & (Join-Path $jdkBin "javac.exe") --release 17 -encoding UTF-8 -Xlint:all,-options `
        -cp ($libs -join ";") -d $classes "@$argFile"
    if ($LASTEXITCODE -ne 0) { throw "javac failed" }

    $jarDir = Join-Path $modDir "jars"
    New-Item -ItemType Directory -Force $jarDir | Out-Null
    $jar = Join-Path $jarDir "MultiSector.jar"
    & (Join-Path $jdkBin "jar.exe") --create --file $jar -C $classes .
    if ($LASTEXITCODE -ne 0) { throw "jar failed" }
    Write-Host "Built $jar"
}

function Invoke-Install {
    Invoke-Build
    # /MIR only touches <game>\mods\MultiSector.
    $target = Join-Path $GameDir "mods\MultiSector"
    robocopy $modDir $target /MIR /NFL /NDL /NJH /NJS /NP | Out-Null
    if ($LASTEXITCODE -ge 8) { throw "robocopy failed with code $LASTEXITCODE" }
    $global:LASTEXITCODE = 0
    Write-Host "Installed to $target"
}

function Invoke-Run {
    Invoke-Install
    Write-Host "Starting Starsector..."
    Start-Process -FilePath (Join-Path $GameDir "starsector.exe") -WorkingDirectory $GameDir
}

function Show-Log {
    $log = Join-Path $core "starsector.log"
    if (-not (Test-Path $log)) { throw "No log yet at $log. Launch the game first." }
    $lines = Select-String -LiteralPath $log -Pattern "MultiSector|multisector" | ForEach-Object Line
    if (-not $lines) { Write-Host "No MultiSector lines in $log"; return }
    $lines | Select-Object -Last 80
}

function Invoke-Package {
    Invoke-Build
    $info = Get-Content (Join-Path $modDir "mod_info.json") -Raw | ConvertFrom-Json
    $dist = Join-Path $root "dist"
    New-Item -ItemType Directory -Force $dist | Out-Null
    $stage = Join-Path $root "build\package\MultiSector"
    if (Test-Path (Split-Path $stage)) { Remove-Item -Recurse -Force (Split-Path $stage) }
    New-Item -ItemType Directory -Force $stage | Out-Null
    Copy-Item -Recurse (Join-Path $modDir "*") $stage
    $zip = Join-Path $dist "MultiSector-$($info.version).zip"
    if (Test-Path $zip) { Remove-Item -Force $zip }
    # The JDK's jar tool writes a standard zip. Compress-Archive and .NET's
    # ZipFile on Windows PowerShell 5.1 write backslash paths, which breaks
    # extraction in some tools.
    & (Join-Path $jdkBin "jar.exe") --create --no-manifest --file $zip -C (Split-Path $stage) "MultiSector"
    if ($LASTEXITCODE -ne 0) { throw "zip failed" }
    Write-Host "Packaged $zip (unzip into Starsector\mods)"
}

function Invoke-NetTest([string[]]$NetArgs) {
    Invoke-Build | Out-Null
    # Runs outside the game, so the mod sandbox doesn't apply here.
    & (Join-Path $jdkBin "java.exe") -cp (Join-Path $root "build\classes") multisector.net.NetTest @NetArgs
    exit $LASTEXITCODE
}

function Invoke-Firewall([string[]]$FwArgs) {
    $remove = $FwArgs.Count -gt 0 -and $FwArgs[0] -eq "remove"
    $port = $DefaultPort
    if (-not $remove -and $FwArgs.Count -gt 0) { $port = [int]$FwArgs[0] }
    $java = Join-Path $jdkBin "java.exe"
    # Same script players get in the release zip (mod/tools/firewall.ps1).
    $fwScript = Join-Path $modDir "tools\firewall.ps1"
    $action = if ($remove) { "remove" } else { "open" }

    Write-Host "Asking Windows for admin rights to change firewall rules..."
    $p = Start-Process powershell -Verb RunAs -Wait -PassThru -ArgumentList "-NoProfile", "-ExecutionPolicy", "Bypass",
        "-File", "`"$fwScript`"", "-Action", $action, "-Port", $port, "-Java", "`"$java`""
    if ($p.ExitCode -ne 0) { throw "firewall update failed (exit $($p.ExitCode))" }
    Write-Host "Done."
}

function Invoke-Clean {
    foreach ($p in @("build", "dist", "mod\jars")) {
        $full = Join-Path $root $p
        if (Test-Path $full) { Remove-Item -Recurse -Force $full; Write-Host "Removed $p" }
    }
}

switch ($Task.ToLower()) {
    "build"   { Invoke-Build }
    "install" { Invoke-Install }
    "run"     { Invoke-Run }
    "log"     { Show-Log }
    "package" { Invoke-Package }
    "clean"   { Invoke-Clean }
    "ip"      { Invoke-NetTest @("ip") }
    "nettest" { Invoke-NetTest $Rest }
    "firewall" { Invoke-Firewall $Rest }
    default {
        Write-Host @"
Usage: dev <task>

  build      compile src/ into mod/jars/MultiSector.jar
  install    build, then copy the mod into $GameDir\mods\MultiSector
  run        install, then start Starsector
  log        show MultiSector lines from the game log
  package    build, then zip the mod into dist/ for friends
  clean      delete build/, dist/ and mod/jars/

  ip                        show this PC's addresses (finds your Hamachi IP)
  nettest host [port]       run a connection test host
  nettest join <ip> [port]  test the connection to a host
  firewall [port]           open port $DefaultPort in Windows Firewall (asks for admin)
  firewall remove           remove those firewall rules

Game folder: $GameDir  (set STARSECTOR_DIR to change it)
"@
        if ($Task.ToLower() -notin @("help", "-h", "--help", "/?")) { exit 1 }
    }
}
