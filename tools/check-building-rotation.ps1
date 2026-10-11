param([string[]]$Tests=@('BuildingRotationSmokeTest','CityLayoutSmokeTest','CreateKineticSmokeTest','ChaosRoadSmokeTest'))
$ErrorActionPreference='Stop'
Set-Location -LiteralPath (Split-Path $PSScriptRoot -Parent)
$props=@{}
foreach($line in Get-Content local.properties -Encoding UTF8){
    if($line -match '^([^#=]+)=(.*)$'){$props[$matches[1].Trim()]=$matches[2].Trim()}
}
$libs=$props['minecraft_libraries'];$mods=$props['deploy_mods']
$jdk=Join-Path $props['java_home'] 'bin'
$lc=Get-ChildItem -LiteralPath $mods -Filter '*lostcities*.jar' | Select-Object -First 1
$extra=Get-ChildItem -LiteralPath (Join-Path $libs 'net/minecraft/client') -Recurse -Filter '*extra.jar' | Select-Object -Last 1
$neo=Get-ChildItem -LiteralPath (Join-Path $libs 'net/neoforged/neoforge') -Recurse -Filter '*universal.jar' | Select-Object -Last 1
$cp='build/rt/roadFixture;build/compile-check;build/rt/classesB;build/rt/classesA;src/main/resources;'+(Get-Content build/real-cp.txt -Raw).Trim()+";$($lc.FullName);$($extra.FullName);$($neo.FullName)"
$env:JAVA_TOOL_OPTIONS='-Duser.language=en -Duser.country=US -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8'
$sources=@($Tests | ForEach-Object {"tools/smoke/$_.java"})
& (Join-Path $jdk 'javac.exe') -encoding UTF-8 -proc:none -cp $cp -d build/rt/classesB $sources
if($LASTEXITCODE -ne 0){exit 1}
$pack=Get-ChildItem build/rt/startup -Recurse -Directory | Where-Object {Test-Path -LiteralPath (Join-Path $_.FullName 'data/caf_lc_customcity/lostcities/parts')} | Select-Object -First 1
# Use the config root containing the actual NBT sources.
$blds=Get-ChildItem -LiteralPath (Join-Path (Split-Path $mods -Parent) 'config') -Directory | Where-Object { @(Get-ChildItem -LiteralPath $_.FullName -Recurse -Filter '*.nbt' -File).Count -gt 20 } | Select-Object -First 1
foreach($test in $Tests){
    $testArgs=switch($test){
        'BuildingRotationSmokeTest' {@($pack.FullName,$blds.FullName)}
        'CityLayoutSmokeTest' {@('build/layout-audit',$blds.FullName,$pack.FullName)}
        'CreateKineticSmokeTest' {@($blds.FullName,'build/create-kinetic-audit')}
        'ChaosRoadSmokeTest' {@('build/chaos-road-audit',$pack.FullName)}
        'VacantSurfaceSmokeTest' {@('tools/fixtures/vacant_surface_sample.nbt','build/vacant-audit')}
    }
    & (Join-Path $jdk 'java.exe') -Xmx3G -cp $cp $test @testArgs
    if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
}
