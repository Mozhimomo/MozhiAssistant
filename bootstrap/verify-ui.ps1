$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskCore = [IO.Path]::GetFullPath((Join-Path $taskRoot '../../starsector-core'))
$taskClasspath = (Join-Path $PSScriptRoot 'target/classes') + [IO.Path]::PathSeparator + (Join-Path $taskCore '*')
$taskOutput = Join-Path $PSScriptRoot 'target/ui-checks'
[IO.Directory]::CreateDirectory($taskOutput) | Out-Null
$taskSources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src/test/java') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
& javac -encoding UTF-8 --release 17 -cp $taskClasspath -d $taskOutput $taskSources
if ($LASTEXITCODE -ne 0) { throw 'UI check compilation failed; build bootstrap first' }
& java '-Djava.awt.headless=true' -cp ($taskOutput + [IO.Path]::PathSeparator + $taskClasspath) com.mozhi.assistant.bootstrap.ui.FleetUiChecks (Join-Path $PSScriptRoot 'target/ui-preview.png')
if ($LASTEXITCODE -ne 0) { throw 'UI checks failed' }
& java -cp ($taskOutput + [IO.Path]::PathSeparator + $taskClasspath) com.mozhi.assistant.bootstrap.InterventionChecks
if ($LASTEXITCODE -ne 0) { throw 'Intervention notification checks failed' }
