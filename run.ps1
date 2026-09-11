# Boots this project's emulator, installs the app, and opens it.
#
#   .\run.ps1            build, install and launch
#   .\run.ps1 -Fresh     clear the app's data first, so you see first-run state
#   .\run.ps1 -BootOnly  just bring the emulator up
#
# Each of the three apps has its own AVD, and this refuses to leave two running:
# if another project's emulator is up it gets shut down first. One emulator at a
# time is the whole point — the other apps hold alarms, a widget and a periodic
# sync, and those keep waking a device you're trying to measure something on.

param(
    [switch]$Fresh,
    [switch]$BootOnly
)

$ErrorActionPreference = 'Stop'

$Avd      = 'tasks_test'
$Package  = 'dev.mahourigan.tasks'

# A fixed console port per app, so the serial is known before it boots. Asking a
# still-booting emulator which AVD it is doesn't work — the console isn't up yet,
# so the name comes back empty and you wait forever for something already there.
# Moon is 5554, Tasks 5556, Food 5558.
$Port     = 5556

# ---- Locate the SDK --------------------------------------------------------
# local.properties is the project's own answer to where the SDK is, so it wins
# over an environment variable that may point somewhere else.
$root = $PSScriptRoot
$sdk = $null
$localProps = Join-Path $root 'local.properties'
if (Test-Path $localProps) {
    $line = Select-String -Path $localProps -Pattern '^\s*sdk\.dir\s*=' | Select-Object -First 1
    if ($line) {
        # A .properties file may be written either plainly (C:/Users/...) or
        # Java-escaped (C\:\\Users\\...), and the three projects use both. Undo
        # the escaping in order: doubled backslashes first, then escaped colons.
        $sdk = ($line.Line -split '=', 2)[1].Trim()
        $sdk = $sdk -replace '\\\\', '\'
        $sdk = $sdk -replace '\\:', ':'
    }
}
if (-not $sdk) { $sdk = $env:ANDROID_HOME }
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk -or -not (Test-Path $sdk)) { throw "Can't find the Android SDK. Set sdk.dir in local.properties." }

$adb = Join-Path $sdk 'platform-tools\adb.exe'
$emulator = Join-Path $sdk 'emulator\emulator.exe'
foreach ($tool in @($adb, $emulator)) {
    if (-not (Test-Path $tool)) { throw "Missing $tool" }
}

# ---- What's already running -------------------------------------------------
$serial = "emulator-$Port"

function Get-EmulatorSerials {
    & $adb devices | Select-String '^emulator-\d+' | ForEach-Object { ($_.Line -split '\s+')[0] }
}

# A lock left behind by a killed emulator makes the next start fail silently, so
# clear our own before doing anything else.
$avdDir = Join-Path $env:USERPROFILE ".android\avd\$Avd.avd"
foreach ($lock in @('hardware-qemu.ini.lock', 'multiinstance.lock')) {
    $path = Join-Path $avdDir $lock
    if (Test-Path $path) { Remove-Item -Recurse -Force $path -ErrorAction SilentlyContinue }
}

$running = Get-EmulatorSerials
$mine = $running -contains $serial
$others = $running | Where-Object { $_ -ne $serial }

foreach ($other in $others) {
    Write-Output "Stopping $other - one emulator at a time."
    & $adb -s $other emu kill 2>$null | Out-Null
}
if ($others) {
    # Give QEMU a moment to let go of its files before another one starts.
    $deadline = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $deadline -and (Get-EmulatorSerials | Where-Object { $_ -ne $serial })) {
        Start-Sleep -Milliseconds 500
    }
}

# ---- Boot ------------------------------------------------------------------
if ($mine) {
    Write-Output "$Avd is already running on $serial."
} else {
    Write-Output "Starting $Avd on port $Port..."
    # -no-metrics matters more than it looks. Without it, a previous hard kill
    # leaves a crash record, and the next launch puts up a modal "send a crash
    # report?" dialog *before* starting the device. It never registers with adb,
    # the window looks like it's still loading, and nothing explains why.
    Start-Process -FilePath $emulator `
        -ArgumentList @(
            '-avd', $Avd, '-port', $Port,
            '-no-metrics', '-no-boot-anim',
            '-netdelay', 'none', '-netspeed', 'full'
        ) `
        -WindowStyle Normal | Out-Null

    $deadline = (Get-Date).AddMinutes(5)
    while ((Get-Date) -lt $deadline -and -not ((Get-EmulatorSerials) -contains $serial)) {
        Start-Sleep -Seconds 2
    }
    if (-not ((Get-EmulatorSerials) -contains $serial)) {
        throw "$Avd never showed up on $serial. Try running it once from Android Studio's Device Manager."
    }
}

Write-Output "Waiting for boot..."
& $adb -s $serial wait-for-device
$deadline = (Get-Date).AddMinutes(5)
$booted = ''
while ((Get-Date) -lt $deadline) {
    $booted = (& $adb -s $serial shell getprop sys.boot_completed 2>$null) -replace '\s', ''
    if ($booted -eq '1') { break }
    Start-Sleep -Seconds 2
}
if ($booted -ne '1') { throw "$Avd came up but never finished booting." }

# A freshly booted emulator often comes up with the shade or a dialog over the
# launcher, which then eats the first tap or screenshot.
& $adb -s $serial shell cmd statusbar collapse 2>$null | Out-Null
& $adb -s $serial shell wm dismiss-keyguard 2>$null | Out-Null

if ($BootOnly) {
    Write-Output "$Avd is up on $serial."
    exit 0
}

# ---- Install and launch ----------------------------------------------------
Write-Output "Installing..."
$env:ANDROID_SERIAL = $serial

# Gradle writes progress to stderr, and with ErrorActionPreference = Stop that
# makes PowerShell treat a perfectly successful build as a terminating error.
# Folding stderr into stdout keeps it as text, and the exit code is the only
# thing worth trusting for whether it worked.
$ErrorActionPreference = 'Continue'
# -p is not optional. Gradle picks its project from the *current* directory, not
# from where gradlew lives, so running this from another folder cheerfully builds
# and installs whichever project you happen to be standing in.
& (Join-Path $root 'gradlew.bat') -p $root installDebug --console=plain 2>&1 | Out-Host
$built = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($built -ne 0) { throw "Build failed (exit $built)." }

if ($Fresh) {
    Write-Output "Clearing $Package data."
    & $adb -s $serial shell pm clear $Package | Out-Null
}

# monkey finds the launcher activity itself, so this keeps working if the
# activity is ever renamed or moved.
$ErrorActionPreference = 'Continue'
& $adb -s $serial shell monkey -p $Package -c android.intent.category.LAUNCHER 1 2>&1 | Out-Null
$ErrorActionPreference = 'Stop'

$installed = (& $adb -s $serial shell pm list packages $Package 2>$null) -match [regex]::Escape($Package)
if (-not $installed) { throw "$Package isn't installed on $serial - the build went somewhere else." }

Write-Output "$Package is running on $serial ($Avd)."
