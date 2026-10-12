# CAF Lost Cities Custom City Addon - build script
#
# This mod deliberately depends only on FML classes (no Minecraft classes), so
# building it needs just JDK 21 plus a few jars already present in the instance.
# No Gradle / NeoForge toolchain required, and it finishes in a couple of seconds.
#
# NOTE: this file is intentionally ASCII-only. Windows PowerShell 5.1 reads
#       BOM-less files as ANSI/GBK, which would corrupt non-ASCII text.
#
# Usage: run .\build.ps1. The script only creates build\libs\ artifacts.

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

# Standard Minecraft library rule evaluation: default deny, last matching rule wins.
function Test-LibraryRules($rules) {
    if (-not $rules) { return $true }
    $allowed = $false
    foreach ($r in $rules) {
        $match = $true
        if ($r.os) {
            if ($r.os.name -and $r.os.name -ne 'windows') { $match = $false }
            if ($r.os.arch -and $r.os.arch -ne 'x86_64')  { $match = $false }
        }
        if ($match) { $allowed = ($r.action -eq 'allow') }
    }
    return $allowed
}

# Locate the instance version JSON (the file the launcher uses to assemble the
# runtime classpath). Tried in order: explicit override in local.properties,
# the instance derived from lostcities_jar, then a scan for a NeoForge profile.
function Find-VersionJson([string]$Libs, $Local, [string]$InstanceDir) {
    $explicit = $Local['minecraft_version_json']
    if ($explicit -and (Test-Path -LiteralPath $explicit)) { return $explicit }

    # Prefer the instance of the resolved Lost Cities jar, else the configured path.
    $inst = $InstanceDir
    if (-not $inst) {
        $lc = $Local['lostcities_jar']
        if ($lc) { $inst = [System.IO.Path]::GetDirectoryName([System.IO.Path]::GetDirectoryName($lc)) }
    }
    if ($inst -and (Test-Path -LiteralPath $inst)) {
        $named = Join-Path $inst ([System.IO.Path]::GetFileName($inst) + '.json')
        if (Test-Path -LiteralPath $named) { return $named }
        $any = Get-ChildItem -LiteralPath $inst -Filter *.json -File -ErrorAction SilentlyContinue |
               Select-Object -First 1
        if ($any) { return $any.FullName }
    }

    $vers = Join-Path ([System.IO.Path]::GetDirectoryName($Libs)) 'versions'
    if (Test-Path -LiteralPath $vers) {
        $cands = Get-ChildItem -LiteralPath $vers -Recurse -Filter *.json -File -ErrorAction SilentlyContinue |
                 Sort-Object LastWriteTime -Descending
        foreach ($c in $cands) {
            $raw = Get-Content -LiteralPath $c.FullName -Raw -ErrorAction SilentlyContinue
            if ($raw -and $raw -match 'net\.neoforged' -and $raw -match 'net\.minecraft') { return $c.FullName }
        }
    }
    return $null
}

# Build build\real-cp.txt: the real Minecraft/NeoForge classpath that the mixin
# sources compile against. It holds absolute jar paths, so it is machine
# specific and never committed - it is regenerated here when missing.
function New-RealClasspathFile([string]$OutFile, [string]$Libs, $Local, [string]$McVersion, [string]$InstanceDir) {
    $json = Find-VersionJson -Libs $Libs -Local $Local -InstanceDir $InstanceDir
    if (-not $json) {
        Fail "Cannot locate the instance version JSON. Set 'minecraft_version_json' in local.properties."
    }
    Write-Host "Version JSON : $json" -ForegroundColor DarkGray

    $o = Get-Content -LiteralPath $json -Raw -Encoding UTF8 | ConvertFrom-Json
    $items = New-Object System.Collections.Generic.List[string]
    $seen = @{}
    foreach ($l in $o.libraries) {
        if (-not (Test-LibraryRules $l.rules)) { continue }
        $rel = $null
        if ($l.downloads -and $l.downloads.artifact -and $l.downloads.artifact.path) {
            $rel = $l.downloads.artifact.path
        } elseif ($l.name) {
            $p = $l.name -split ':'
            if ($p.Count -ge 3) {
                $rel = (($p[0] -replace '\.', '/') + '/' + $p[1] + '/' + $p[2] + '/' + $p[1] + '-' + $p[2] + '.jar')
            }
        }
        if (-not $rel) { continue }
        $full = Join-Path $Libs ($rel -replace '/', '\')
        if (-not (Test-Path -LiteralPath $full)) { continue }
        if ($seen.ContainsKey($full)) { continue }
        $seen[$full] = $true
        $items.Add($full) | Out-Null
    }

    # The SRG-named client jar supplies net.minecraft.* to the mixin sources.
    $clientDir = Join-Path $Libs 'net\minecraft\client'
    if (Test-Path -LiteralPath $clientDir) {
        $jars = @(Get-ChildItem -LiteralPath $clientDir -Recurse -Filter '*-srg.jar' -File -ErrorAction SilentlyContinue)
        if ($McVersion) {
            $pref = @($jars | Where-Object { $_.Name -like "client-$McVersion-*" })
            if ($pref.Count -gt 0) { $jars = $pref }
        }
        $pick = $jars | Sort-Object FullName | Select-Object -Last 1
        if ($pick -and -not $seen.ContainsKey($pick.FullName)) { $items.Add($pick.FullName) | Out-Null }
    }

    if ($items.Count -eq 0) {
        Fail "Generated classpath is empty - check 'minecraft_libraries' and the version JSON."
    }

    New-Item -ItemType Directory -Force -Path ([System.IO.Path]::GetDirectoryName($OutFile)) | Out-Null
    [System.IO.File]::WriteAllText($OutFile, (($items -join ';') + ';'), (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "Generated build\real-cp.txt with $($items.Count) jar(s)." -ForegroundColor Green
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

    # Lost Cities jar is a local compile-time input, never a deployment target.
    # If the configured path is stale, fall back to the newest *lostcities*.jar
    # sitting in the same folder (launchers often rename jars with a CN prefix).
    $lcJar = $local['lostcities_jar']
    if (-not $lcJar -or -not (Test-Path -LiteralPath $lcJar)) {
        if ($lcJar) {
            $modsDir = [System.IO.Path]::GetDirectoryName($lcJar)
            if ($modsDir -and (Test-Path -LiteralPath $modsDir)) {
                $found = Get-ChildItem -LiteralPath $modsDir -Filter '*lostcities*.jar' -File -ErrorAction SilentlyContinue |
                         Sort-Object LastWriteTime -Descending | Select-Object -First 1
                if ($found) {
                    Write-Host "lostcities_jar not found - using $($found.Name)" -ForegroundColor Yellow
                    $lcJar = $found.FullName
                }
            }
        }
        if (-not $lcJar -or -not (Test-Path -LiteralPath $lcJar)) {
            Fail "Mixin sources found but lostcities_jar is missing in local.properties"
        }
    }

    $realCpFile = Join-Path $root 'build\real-cp.txt'
    if (-not (Test-Path -LiteralPath $realCpFile)) {
        Write-Host "build\real-cp.txt not found - generating it from the instance ..." -ForegroundColor Yellow
        $gradleProps = Read-Props (Join-Path $root 'gradle.properties')
        $instanceDir = [System.IO.Path]::GetDirectoryName([System.IO.Path]::GetDirectoryName($lcJar))
        New-RealClasspathFile -OutFile $realCpFile -Libs $libs -Local $local -McVersion $gradleProps['minecraft_version'] -InstanceDir $instanceDir
        if (-not (Test-Path -LiteralPath $realCpFile)) {
            Fail "Could not generate build\real-cp.txt."
        }
    }
    $realCp = (Get-Content -LiteralPath $realCpFile -Raw -Encoding UTF8).Trim()
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

Write-Host ""
Write-Host "Next: put building files into <instance>\config\CAF失落城市配置, then start the game." -ForegroundColor Cyan
exit 0
