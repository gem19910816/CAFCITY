[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$CandidateProjectPath,
    [Parameter(Mandatory=$true)][string]$ClassPathFile,
    [Parameter(Mandatory=$true)][string]$LostCitiesJar,
    [Parameter(Mandatory=$true)][string]$CityConfigPath,
    [string]$JdkHome,
    [string]$CandidateArtifact,
    [string]$CataloguePath,
    [string]$OutputPath,
    [string]$Seed
)

# This script compiles probes only. It never builds/edits the production project,
# increments its version, installs a JAR, launches Minecraft, or opens a saved world.
$ErrorActionPreference = 'Stop'
if (-not $CataloguePath) { $CataloguePath = Join-Path $PSScriptRoot 'fixtures/t4-catalogue.json' }
if (-not $OutputPath) { $OutputPath = Join-Path $PSScriptRoot '.work' }
function Existing-Path([string]$value) {
    if (-not (Test-Path -LiteralPath $value)) { throw "Required input not found: $value" }
    return (Resolve-Path -LiteralPath $value).Path
}
function Invoke-Logged([string]$command, [string[]]$commandArguments, [string]$log) {
    # Windows PowerShell 5 treats native stderr as ErrorRecords. Preserve it as
    # diagnostic output and use the exit code to determine success.
    $ErrorActionPreference = 'Continue'
    & $command @commandArguments *> $log
    $nativeExit = $LASTEXITCODE
    if ($nativeExit -ne 0) { throw "Native command failed ($nativeExit); inspect $log" }
}
$project = Existing-Path $CandidateProjectPath
$dependencyFile = Existing-Path $ClassPathFile
$lostCities = Existing-Path $LostCitiesJar
$cityConfig = Existing-Path $CityConfigPath
$catalogue = Existing-Path $CataloguePath
if (-not $CandidateArtifact) { $CandidateArtifact = Join-Path $project 'build/classes' }
$artifact = Existing-Path $CandidateArtifact
if ($JdkHome) {
    $java = Existing-Path (Join-Path $JdkHome 'bin/java.exe')
    $javac = Existing-Path (Join-Path $JdkHome 'bin/javac.exe')
} else {
    $java = (Get-Command java -ErrorAction Stop).Source
    $javac = (Get-Command javac -ErrorAction Stop).Source
}
$entries = @((Get-Content -LiteralPath $dependencyFile -Raw -Encoding UTF8) -split '[;\r\n]+' |
    ForEach-Object { $_.Trim() } | Where-Object { $_ })
if ($entries.Count -eq 0) { throw 'ClassPathFile contains no dependency entries.' }
$classpath = (@($artifact, $lostCities) + $entries) -join [IO.Path]::PathSeparator
New-Item -ItemType Directory -Force -Path $OutputPath | Out-Null
$output = (Resolve-Path -LiteralPath $OutputPath).Path
$classes = Join-Path $output 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources = @('StationSafetyProbe.java', 'StationLegacyRegressionProbe.java',
    'StationRepairProbe.java', 'ActualRailwayPackingProbe.java', 'RailwayModel.java') |
    ForEach-Object { Join-Path $PSScriptRoot $_ }
$compileLog = Join-Path $output 'compile.log'
Invoke-Logged $javac (@('-encoding', 'UTF-8', '-proc:none', '-cp', $classpath, '-d', $classes) + $sources) $compileLog
$runtimeClasspath = $classes + [IO.Path]::PathSeparator + $classpath
$runs = @()
foreach ($probe in @('StationSafetyProbe', 'StationLegacyRegressionProbe',
                      'StationRepairProbe', 'ActualRailwayPackingProbe')) {
    $log = Join-Path $output ($probe + '.log')
    if ($probe -eq 'StationSafetyProbe') {
        $arguments = @($lostCities)
    } else {
        $reportFolder = Join-Path $output $probe
        $arguments = @($reportFolder, $catalogue, $cityConfig)
        if ($Seed) { $arguments += $Seed }
    }
    Invoke-Logged $java (@('-cp', $runtimeClasspath, $probe) + $arguments) $log
    $runs += [pscustomobject]@{ probe = $probe; passed = $true; log = $log }
    Write-Host "PASS $probe"
}
# ActualRailwayPackingProbe is a report-producing model. Make its expected result
# an explicit runner failure, so a changed catalogue/config cannot silently pass.
$actualReports = @(Get-ChildItem -LiteralPath (Join-Path $output 'ActualRailwayPackingProbe') -Filter '*.json' -File)
if ($actualReports.Count -ne 1) { throw 'Expected exactly one actual railway model JSON report.' }
$actualReport = $actualReports[0].FullName
$actual = Get-Content -LiteralPath $actualReport -Raw -Encoding UTF8 | ConvertFrom-Json
foreach ($run in $actual.runs) {
    if ($run.candidate211_soft_station_fix_enabled) {
        if (@($run.missing_required.PSObject.Properties).Count -ne 0) {
            throw 'Railway model failed: repaired layout does not meet all configured minima.'
        }
        foreach ($count in $run.new_five_counts.PSObject.Properties) {
            if ($count.Value -ne 1) { throw "Railway model failed: $($count.Name) count is $($count.Value)." }
        }
    }
}
$summary = [ordered]@{
    passed = $true
    probes = $runs
    production_compiled_by_this_script = $false
    runtime_mixin_injection_tested = $false
    minecraft_worldgen_tested = $false
    game_or_saved_world_written = $false
    railway_model_city_level = 0
    railway_model_highways_or_neighbor_ownership = $false
}
$summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $output 'summary.json') -Encoding UTF8
Write-Host "All offline probes passed. Reports: $output"
