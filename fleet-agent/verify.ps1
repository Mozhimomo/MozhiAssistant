param([switch]$CheckPackaged)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskClasspath = Join-Path $taskRoot 'llm-client/target/mozhi-llm-client-0.1.4-all.jar'
if (!(Test-Path -LiteralPath $taskClasspath)) {
    throw '请先运行 mvn -pl llm-client -am package 构建 llm-client'
}
$taskOutput = Join-Path $PSScriptRoot 'target/fleet-checks'
$taskCore = [IO.Path]::GetFullPath((Join-Path $taskRoot '../../starsector-core'))
$taskClasspath = $taskClasspath + [IO.Path]::PathSeparator + (Join-Path $taskCore '*')
$taskClasspath = $taskClasspath + [IO.Path]::PathSeparator + (Join-Path $taskRoot 'bootstrap/target/classes')
[IO.Directory]::CreateDirectory($taskOutput) | Out-Null
$taskSources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src/main/java'), (Join-Path $PSScriptRoot 'src/test/java') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
& javac '-J-Dfile.encoding=UTF-8' -encoding UTF-8 --release 17 -cp $taskClasspath -d $taskOutput $taskSources
if ($LASTEXITCODE -ne 0) { throw '舰队检查编译失败' }
& java '-Dfile.encoding=UTF-8' -cp ($taskOutput + [IO.Path]::PathSeparator + $taskClasspath) com.mozhi.fleet.model.ModelChecks
if ($LASTEXITCODE -ne 0) { throw '模型层检查失败' }
& java '-Dfile.encoding=UTF-8' -cp ($taskOutput + [IO.Path]::PathSeparator + $taskClasspath) com.mozhi.fleet.tools.FleetToolChecks
if ($LASTEXITCODE -ne 0) { throw '工具注册与反射调用检查失败' }
& java '-Dfile.encoding=UTF-8' -cp ($taskOutput + [IO.Path]::PathSeparator + $taskClasspath) com.mozhi.fleet.execution.ResourceChecks
if ($LASTEXITCODE -ne 0) { throw '资源监视器检查失败' }
& java '-Dfile.encoding=UTF-8' -cp ($taskOutput + [IO.Path]::PathSeparator + $taskClasspath) com.mozhi.fleet.planning.PlannerChecks
if ($LASTEXITCODE -ne 0) { throw '规划器检查失败' }
& java '-Dfile.encoding=UTF-8' -cp ($taskOutput + [IO.Path]::PathSeparator + $taskClasspath) com.mozhi.fleet.planning.PromptChecks
if ($LASTEXITCODE -ne 0) { throw '提示词检查失败' }
& java '-Dfile.encoding=UTF-8' -cp ($taskOutput + [IO.Path]::PathSeparator + $taskClasspath) com.mozhi.fleet.game.FleetQueryChecks
if ($LASTEXITCODE -ne 0) { throw '舰队查询检查失败' }
$taskPackageArgs = @()
if ($CheckPackaged) { $taskPackageArgs = @($taskRoot) }
& java '-Dfile.encoding=UTF-8' -cp ($taskOutput + [IO.Path]::PathSeparator + $taskClasspath) com.mozhi.fleet.execution.ExecutorChecks @taskPackageArgs
if ($LASTEXITCODE -ne 0) { throw '执行器检查失败' }
& java '-Dfile.encoding=UTF-8' -cp ($taskOutput + [IO.Path]::PathSeparator + $taskClasspath) com.mozhi.fleet.AgentChecks
if ($LASTEXITCODE -ne 0) { throw '智能体检查失败' }
& java '-Dfile.encoding=UTF-8' -cp ($taskOutput + [IO.Path]::PathSeparator + $taskClasspath) com.mozhi.fleet.trading.TradeRouteChecks
if ($LASTEXITCODE -ne 0) { throw '跑商路线检查失败' }
& java '-Dfile.encoding=UTF-8' -cp ($taskOutput + [IO.Path]::PathSeparator + $taskClasspath) com.mozhi.fleet.trading.TradeSnapshotChecks
if ($LASTEXITCODE -ne 0) { throw '跑商快照检查失败' }
