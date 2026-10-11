# CAF Lost Cities Custom City Addon - build script
#
# This mod deliberately depends only on FML classes (no Minecraft classes), so
# building it needs just JDK 21 plus a few jars already present in the instance.
# No Gradle / NeoForge toolchain required, and it finishes in a couple of seconds.
#
# NOTE: this file is intentionally ASCII-only. Windows PowerShell 5.1 reads
#       BOM-less files as ANSI/GBK, which would corrupt non-ASCII text.
#
# Usage: double-click build.bat, or run .\build.ps1 from PowerShell.

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
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
    Write-Host "BUILD FAILED: $msg" -ForegroundColor Red
    exit 1
}

$local = Read-Props (Join-Path $root 'local.properties')

# ---- JDK ----
$jdk = $local['java_home']
if (-not $jdk) { $jdk = $env:JAVA_HOME }
if (-not $jdk -or -not (Test-Path -LiteralPath (Join-Path $jdk 'bin\javac.exe'))) {
    Fail "JDK 21 not found. Set 'java_home' in local.properties."
}
$javac  = Join-Path $jdk 'bin\javac.exe'
$jarExe = Join-Path $jdk 'bin\jar.exe'

# ---- libraries ----
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
Write-Host "JDK       : $jdk" -ForegroundColor DarkGray
Write-Host "Libraries : $libs" -ForegroundColor DarkGray

# ---- version (auto increment) ----
$bnFile = Join-Path $root 'build_number.properties'
$bn = 0
if (Test-Path -LiteralPath $bnFile) {
    $t = (Get-Content -LiteralPath $bnFile -Raw).Trim()
    if ($t -match '(\d+)') { $bn = [int]$Matches[1] }
}
$bn++
Set-Content -LiteralPath $bnFile -Value "buildNumber=$bn" -Encoding ASCII

$modVersion = "1.0.$bn"
$jarName = "caf_lc_customcity-1.21.1-$modVersion.jar"
Write-Host "Version   : $modVersion" -ForegroundColor DarkGray

# ---- compile ----
$classes = Join-Path $root 'build\classes'
if (Test-Path -LiteralPath $classes) { Remove-Item -LiteralPath $classes -Recurse -Force }
New-Item -ItemType Directory -Force -Path $classes | Out-Null

$srcDir = Join-Path $root 'src\main\java'
# Split sources into two groups: Mixin classes (com\caf\lccustomcity\mixin) and their
# helper classes (com\caf\lccustomcity\impl) both need the wide classpath (they touch
# Minecraft/LostCities classes); everything else stays on the 4 basic jars.
$mixinDir = Join-Path $srcDir 'com\caf\lccustomcity\mixin'
$implDir = Join-Path $srcDir 'com\caf\lccustomcity\impl'
$all = @(Get-ChildItem -LiteralPath $srcDir -Recurse -Filter *.java -File | ForEach-Object { $_.FullName })
if ($all.Count -eq 0) { Fail "No .java files found under $srcDir" }
$mixinFiles = @($all | Where-Object { $_.StartsWith("$mixinDir\") -or $_.StartsWith("$implDir\") })
$files = @($all | Where-Object { -not $_.StartsWith("$mixinDir\") -and -not $_.StartsWith("$implDir\") })

if ($files.Count -gt 0) {
    Write-Host "Compiling $($files.Count) java file(s)..." -ForegroundColor Cyan
    # Do not pipe or capture javac output - let it print straight to the console.
    & $javac -encoding UTF-8 -nowarn -d $classes -cp $cp $files
    if ($LASTEXITCODE -ne 0) { Fail "javac reported errors (see above)." }
}

# ---- mixin sources (only when the mixin package exists) ----
if ($mixinFiles.Count -gt 0) {
    $mixinJar = Find-Newest (Join-Path $libs 'org\spongepowered\mixin') 'mixin-*.jar'
    if (-not $mixinJar) { Fail "Mixin sources found but no mixin-*.jar under $libs\org\spongepowered\mixin" }

    # Lost Cities jar: scan the instance mods folder (derived from deploy_mods).
    $lcJar = $null
    $instance = $null
    if ($local['deploy_mods']) { $instance = Split-Path $local['deploy_mods'] -Parent }
    if ($instance -and (Test-Path -LiteralPath (Join-Path $instance 'mods'))) {
        $lcJar = (Get-ChildItem -LiteralPath (Join-Path $instance 'mods') -Filter '*lostcities*.jar' -File -ErrorAction SilentlyContinue |
                  Select-Object -First 1).FullName
    }
    if (-not $lcJar) { Fail "Mixin sources found but no *lostcities*.jar under $instance\mods (set deploy_mods in local.properties)" }

    $realCpFile = Join-Path $root 'build\real-cp.txt'
    if (-not (Test-Path -LiteralPath $realCpFile)) {
        Fail "Mixin sources found but build\real-cp.txt is missing (see tools\run-offline-tests.ps1 for how it is generated)."
    }
    $realCp = (Get-Content -LiteralPath $realCpFile -Raw).Trim()
    $cpMixin = @($cp, $mixinJar, $lcJar, $realCp, $classes) -join ';'

    Write-Host "Compiling $($mixinFiles.Count) mixin file(s)..." -ForegroundColor Cyan
    # -proc:none disables Mixin's annotation processor. That processor only
    # generates the obfuscation refmap, which NeoForge 1.21 (mojmap) does not
    # need, and it would crash here on a missing Gson dependency.
    & $javac -encoding UTF-8 -nowarn -proc:none -d $classes -cp $cpMixin $mixinFiles
    if ($LASTEXITCODE -ne 0) { Fail "javac reported errors in mixin sources (see above)." }
}

# ---- resources ----
$resDir = Join-Path $root 'src\main\resources'
if (Test-Path -LiteralPath $resDir) {
    Get-ChildItem -LiteralPath $resDir -Recurse -File | ForEach-Object {
        $rel = $_.FullName.Substring($resDir.Length + 1)
        $dst = Join-Path $classes $rel
        New-Item -ItemType Directory -Force -Path (Split-Path $dst) | Out-Null
        Copy-Item -LiteralPath $_.FullName -Destination $dst -Force
    }
}

# ---- package ----
$libsOut = Join-Path $root 'build\libs'
New-Item -ItemType Directory -Force -Path $libsOut | Out-Null
$manifest = Join-Path $root 'build\MANIFEST.MF'
$mfLines = @(
    'Manifest-Version: 1.0',
    'Implementation-Title: CAF Lost Cities Custom City Addon',
    "Implementation-Version: $modVersion",
    '',
    ''
) -join "`r`n"
Set-Content -LiteralPath $manifest -Value $mfLines -Encoding ASCII -NoNewline

$outJar = Join-Path $libsOut $jarName
if (Test-Path -LiteralPath $outJar) { Remove-Item -LiteralPath $outJar -Force }
& $jarExe --create --file $outJar --manifest $manifest -C $classes .
if ($LASTEXITCODE -ne 0) { Fail "jar packaging failed." }

$sizeKB = [math]::Round((Get-Item -LiteralPath $outJar).Length / 1KB, 1)
Write-Host ""
Write-Host "BUILD OK: build\libs\$jarName  ($sizeKB KB)" -ForegroundColor Green

# ---- deploy ----
$deploy = $local['deploy_mods']
if ($deploy) {
    if (Test-Path -LiteralPath $deploy) {
        Get-ChildItem -LiteralPath $deploy -Filter 'caf_lc_customcity-*.jar' -File -ErrorAction SilentlyContinue |
            ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force }
        Copy-Item -LiteralPath $outJar -Destination (Join-Path $deploy $jarName) -Force
        Write-Host "Installed to: $deploy" -ForegroundColor Green
    } else {
        Write-Host "NOTE: deploy folder does not exist, skipped -> $deploy" -ForegroundColor DarkYellow
    }
}

Write-Host ""
Write-Host "Next: put building files into <instance>\config\<buildings folder>, then start the game." -ForegroundColor Cyan
exit 0
