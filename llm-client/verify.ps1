$ErrorActionPreference='Stop'
$artifact=Join-Path $PSScriptRoot 'target/mozhi-llm-client-0.1.4-all.jar'
if(!(Test-Path -LiteralPath $artifact)){throw '请先在 llm-client 中运行 mvn package 完成构建'}
$output=Join-Path $PSScriptRoot 'target/regression-classes'
[IO.Directory]::CreateDirectory($output) | Out-Null
& javac '-J-Dfile.encoding=UTF-8' -encoding UTF-8 --release 17 -cp $artifact -d $output (Join-Path $PSScriptRoot 'src/test/java/com/mozhi/llm/LlmAiServiceChecks.java')
if($LASTEXITCODE -ne 0){throw '大模型客户端回归检查编译失败'}
& java '-Dfile.encoding=UTF-8' -cp ($output+';'+$artifact) com.mozhi.llm.LlmAiServiceChecks
if($LASTEXITCODE -ne 0){throw '大模型客户端回归检查失败'}
