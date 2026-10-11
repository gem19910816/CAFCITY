# CAF Lost Cities Custom City Addon - COMPILE-ONLY check
#
# Purpose: verify that the sources still compile, WITHOUT packaging a jar and
#          WITHOUT touching the game instance (no mods folder, no saves).
#
# It deliberately uses exactly the same JDK, the same classpath jars and the
# same javac flags as build.ps1, so "COMPILE OK" here means the real build will
# also get past the compiler. It never reads or writes build_number.properties.
#
# Usage: .\tools\compile-check.ps1
#
# NOTE: this file is intentionally ASCII-only. Windows PowerShell 5.1 reads
#       BOM-less files as ANSI/GBK, which would corrupt non-ASCII text.

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $root

function Read-Props([string]$file) {
    $h = @{}
    if (Test-Path -LiteralPath $file) {
        foreach ($line in (Get-Content -LiteralPath $file -Encoding UTF8)) {
            $t = $line.Trim()
            if ($t -and -not $t.StartsWith('#') -and $t.Contains('=')) {
                $i = $t.IndexOf('=')
                $h[$t.Substring(0, $i).Trim()] = $t.Substring($i + 1).Trim()
            }
        }
    }
    return $h
}

function Find-Newest([string]$dir, [string]$pattern) {
    if (-not (Test-Path -LiteralPath $dir)) { return $null }
    $f = Get-ChildItem -LiteralPath $dir -Recurse -Filter $pattern -File -ErrorAction SilentlyContinue |
         Sort-Object FullName | Select-Object -Last 1
    if ($f) { return $f.FullName }
    return $null
}

function Fail([string]$msg) {
    Write-Host ""
    Write-Host "COMPILE CHECK FAILED: $msg" -ForegroundColor Red
    exit 1
}

$local = Read-Props (Join-Path $root 'local.properties')

$jdk = $local['java_home']
if (-not $jdk) { $jdk = $env:JAVA_HOME }
if (-not $jdk -or -not (Test-Path -LiteralPath (Join-Path $jdk 'bin\javac.exe'))) {
    Fail "JDK 21 not found. Set 'java_home' in local.properties."
}
$javac = Join-Path $jdk 'bin\javac.exe'

$libs = $local['minecraft_libraries']
if (-not $libs) { Fail "Set 'minecraft_libraries' in local.properties." }
if (-not (Test-Path -LiteralPath $libs)) { Fail "Library folder not found: $libs" }

$loader = Find-Newest (Join-Path $libs 'net\neoforged\fancymodloader\loader') 'loader-*.jar'
$bus    = Find-Newest (Join-Path $libs 'net\neoforged\bus') 'bus-*.jar'
$gson   = Find-Newest (Join-Path $libs 'com\google\code\gson\gson') 'gson-*.jar'
$slf4j  = Find-Newest (Join-Path $libs 'org\slf4j\slf4j-api') 'slf4j-api-*.jar'

foreach ($pair in @(@('FML loader', $loader), @('event bus', $bus), @('gson', $gson), @('slf4j-api', $slf4j))) {
    if (-not $pair[1]) { Fail "Cannot find $($pair[0]) jar under $libs (is NeoForge installed?)" }
}
$cp = @($loader, $bus, $gson, $slf4j) -join ';'

# English javac messages, otherwise they come back as GBK mojibake.
$env:JAVA_TOOL_OPTIONS = '-Duser.language=en -Duser.country=US'

Write-Host "JDK       : $jdk" -ForegroundColor DarkGray
Write-Host "Libraries : $libs" -ForegroundColor DarkGray
Write-Host "Classpath : $($cp.Split(';').Count) jar(s)" -ForegroundColor DarkGray
Write-Host "Output    : build\compile-check   (build\classes is left untouched)" -ForegroundColor DarkGray

# Separate output dir: never disturb build\classes / build\libs.
$classes = Join-Path $root 'build\compile-check'
if (Test-Path -LiteralPath $classes) { Remove-Item -LiteralPath $classes -Recurse -Force }
New-Item -ItemType Directory -Force -Path $classes | Out-Null

$srcDir = Join-Path $root 'src\main\java'
$files = @(Get-ChildItem -LiteralPath $srcDir -Recurse -Filter *.java -File | ForEach-Object { $_.FullName })
if ($files.Count -eq 0) { Fail "No .java files found under $srcDir" }

Write-Host ""
Write-Host "Compiling $($files.Count) java file(s) with the same flags as build.ps1 ..." -ForegroundColor Cyan

# Match build.ps1's two-stage compile: impl also depends on Minecraft and LC.
$mixinDir = Join-Path $srcDir 'com\caf\lccustomcity\mixin'
$implDir = Join-Path $srcDir 'com\caf\lccustomcity\impl'
$ordinary = @($files | Where-Object { -not $_.StartsWith("$mixinDir\") -and -not $_.StartsWith("$implDir\") })
$runtime = @($files | Where-Object { $_.StartsWith("$mixinDir\") -or $_.StartsWith("$implDir\") })
& $javac -encoding UTF-8 -nowarn -d $classes -cp $cp $ordinary
$rc = $LASTEXITCODE
if ($rc -eq 0 -and $runtime.Count -gt 0) {
    $realFile = Join-Path $root 'build\real-cp.txt'
    if (-not (Test-Path -LiteralPath $realFile)) { Fail 'build\real-cp.txt is required' }
    $real = (Get-Content -LiteralPath $realFile -Raw).Trim()
    $mixinJar = Find-Newest (Join-Path $libs 'org\spongepowered\mixin') 'mixin-*.jar'
    $lcJar = (Get-ChildItem -LiteralPath $local['deploy_mods'] -Filter '*lostcities*.jar' -File | Select-Object -First 1).FullName
    if (-not $mixinJar -or -not $lcJar) { Fail 'Mixin / Lost Cities jar missing' }
    & $javac -encoding UTF-8 -nowarn -proc:none -d $classes -cp "$classes;$cp;$real;$mixinJar;$lcJar" $runtime
    $rc = $LASTEXITCODE
}

Write-Host ""
if ($rc -ne 0) {
    Write-Host "COMPILE FAILED (javac exit code $rc) - build.ps1 would fail here too." -ForegroundColor Red
    exit 1
}

$n = @(Get-ChildItem -LiteralPath $classes -Recurse -Filter *.class -File).Count
Write-Host "COMPILE OK: $n class file(s) produced." -ForegroundColor Green
Write-Host "Nothing was packaged and nothing was deployed." -ForegroundColor Green
exit 0
