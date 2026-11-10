param(
    [string]$GameDir = "",
    [switch]$SkipBuild
)

# Build the addon and drop the jar into the test instance's mods folder.
# The instance path lives in deploy.config.json (kept out of this script so the
# file stays pure ASCII and safe for Windows PowerShell 5.1).

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

if (-not $GameDir) {
    $configPath = Join-Path $PSScriptRoot 'deploy.config.json'
    if (-not (Test-Path $configPath)) {
        throw "deploy.config.json not found next to this script."
    }
    $GameDir = (Get-Content $configPath -Raw -Encoding UTF8 | ConvertFrom-Json).gameDir
}

if (-not (Test-Path $GameDir)) {
    throw "Game directory does not exist: $GameDir"
}

if (-not $SkipBuild) {
    Push-Location $projectRoot
    try {
        & .\gradlew.bat --console=plain build
        if ($LASTEXITCODE -ne 0) { throw "gradlew build failed with exit code $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
}

$jar = Get-ChildItem (Join-Path $projectRoot 'build\libs') -Filter '*.jar' |
    Where-Object { $_.Name -notlike '*-sources.jar' } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if (-not $jar) { throw "No jar in build\libs - run the build first." }

$modsDir = Join-Path $GameDir 'mods'
if (-not (Test-Path $modsDir)) { New-Item -ItemType Directory -Path $modsDir | Out-Null }

Get-ChildItem $modsDir -Filter 'snowempress-*.jar' | Remove-Item -Force -ErrorAction SilentlyContinue
$target = Join-Path $modsDir $jar.Name
Copy-Item $jar.FullName $target -Force

Write-Host "deployed : $($jar.Name)"
Write-Host "target   : $target"
Write-Host "next     : launch the instance and look for '[snowempress] Twilight Forest ... detected'"
