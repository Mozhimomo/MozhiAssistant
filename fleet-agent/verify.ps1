param([switch]$CheckPackaged)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskClasspath = Join-Path $taskRoot 'llm-client/target/mozhi-llm-client-0.1.1-all.jar'
if (!(Test-Path -LiteralPath $taskClasspath)) {
    throw 'Build llm-client with mvn -pl llm-client -am package first'
}
$taskOutput = Join-Path $PSScriptRoot 'target/fleet-checks'
$taskCore = [IO.Path]::GetFullPath((Join-Path $taskRoot '../../starsector-core'))
$taskClasspath = $taskClasspath + [IO.Path]::PathSeparator + (Join-Path $taskCore '*')
$taskClasspath = $taskClasspath + [IO.Path]::PathSeparator + (Join-Path $taskRoot 'bootstrap/target/classes')
[IO.Directory]::CreateDirectory($taskOutput) | Out-Null
$taskSources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src/main/java'), (Join-Path $PSScriptRoot 'src/test/java') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
& javac -encoding UTF-8 --release 17 -cp $taskClasspath -d $taskOutput $taskSources
if ($LASTEXITCODE -ne 0) { throw 'Fleet check compilation failed' }
& java -cp ($taskOutput + [IO.Path]::PathSeparator + $taskClasspath) com.mozhi.fleet.model.ModelChecks
if ($LASTEXITCODE -ne 0) { throw 'Model checks failed' }
& java -cp ($taskOutput + [IO.Path]::PathSeparator + $taskClasspath) com.mozhi.fleet.planning.PlannerChecks
if ($LASTEXITCODE -ne 0) { throw 'Planner checks failed' }
$taskPackageArgs = @()
if ($CheckPackaged) { $taskPackageArgs = @($taskRoot) }
& java -cp ($taskOutput + [IO.Path]::PathSeparator + $taskClasspath) com.mozhi.fleet.execution.ExecutorChecks @taskPackageArgs
if ($LASTEXITCODE -ne 0) { throw 'Executor checks failed' }
& java -cp ($taskOutput + [IO.Path]::PathSeparator + $taskClasspath) com.mozhi.fleet.AgentChecks
if ($LASTEXITCODE -ne 0) { throw 'Agent checks failed' }
