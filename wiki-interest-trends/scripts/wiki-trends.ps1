# Launcher for Windows PowerShell / pwsh.
# Builds the jar from source on first use (or after source changes), then runs it.
# Arguments are passed base64(UTF-8)-encoded so non-Latin topics survive the Windows JVM argv decoding.
$ErrorActionPreference = 'Stop'
$SkillDir = Split-Path -Parent $PSScriptRoot
$Jar = Join-Path $SkillDir 'target\wiki-interest-trends.jar'

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-Error "Java 25+ is required but 'java' is not on PATH."
    exit 3
}

$needsBuild = -not (Test-Path $Jar)
if (-not $needsBuild) {
    $jarTime = (Get-Item $Jar).LastWriteTime
    $changed = Get-ChildItem -Recurse -File (Join-Path $SkillDir 'src\main'), (Join-Path $SkillDir 'pom.xml') |
        Where-Object { $_.LastWriteTime -gt $jarTime } | Select-Object -First 1
    $needsBuild = $null -ne $changed
}
if ($needsBuild) {
    [Console]::Error.WriteLine('[wiki-trends] building from source (first run or after code changes, about a minute)...')
    # always the Maven Wrapper: pins the Maven version (.mvn/wrapper) for reproducible builds
    $mvn = Join-Path $SkillDir 'mvnw.cmd'
    Push-Location $SkillDir
    # Windows PowerShell 5.1 turns any native stderr line into a terminating error under 'Stop'
    $ErrorActionPreference = 'Continue'
    try { & $mvn -q -DskipTests package 2>&1 | ForEach-Object { [Console]::Error.WriteLine("$_") } } finally { Pop-Location }
    $ErrorActionPreference = 'Stop'
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $Jar)) { Write-Error "Build failed. Run '$mvn -DskipTests package' in $SkillDir for details."; exit 3 }
}

$encoded = @('--b64')
foreach ($a in $args) {
    # PowerShell turns unquoted uk,pl,cs into an array: re-join it with commas
    $s = if ($a -is [array]) { $a -join ',' } else { [string]$a }
    $encoded += [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($s))
}
[Console]::OutputEncoding = [Text.Encoding]::UTF8
& java '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -jar $Jar @encoded
exit $LASTEXITCODE
