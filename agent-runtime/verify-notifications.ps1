$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskCore = [IO.Path]::GetFullPath((Join-Path $taskRoot '../../starsector-core'))
$taskClasspath = @((Join-Path $PSScriptRoot 'target/classes'), (Join-Path $taskRoot 'bootstrap/target/classes'), (Join-Path $taskRoot 'llm-client/target/mozhi-llm-client-0.1.4-all.jar'), (Join-Path $taskCore '*')) -join [IO.Path]::PathSeparator
$taskOutput = Join-Path $PSScriptRoot 'target/notification-checks'
[IO.Directory]::CreateDirectory($taskOutput) | Out-Null
$taskSources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src/test/java') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
& javac '-J-Dfile.encoding=UTF-8' -parameters -encoding UTF-8 --release 17 -cp $taskClasspath -d $taskOutput $taskSources
if ($LASTEXITCODE -ne 0) { throw '通知检查编译失败；请先构建项目' }
& java '-Dfile.encoding=UTF-8' -cp ($taskOutput + [IO.Path]::PathSeparator + $taskClasspath) com.mozhi.assistant.runtime.NotificationChecks $taskOutput
if ($LASTEXITCODE -ne 0) { throw '通知运行检查失败' }
& java '-Dfile.encoding=UTF-8' -cp ($taskOutput + [IO.Path]::PathSeparator + $taskClasspath) com.mozhi.assistant.runtime.EfficiencyChecks $taskOutput
if ($LASTEXITCODE -ne 0) { throw '上下文优化运行检查失败' }
