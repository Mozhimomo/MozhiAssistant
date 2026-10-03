$ErrorActionPreference='Stop'
$artifact=Join-Path $PSScriptRoot 'target/mozhi-llm-client-0.1.1-all.jar'
if(!(Test-Path -LiteralPath $artifact)){throw 'Build llm-client with mvn package first'}
$output=Join-Path $PSScriptRoot 'target/regression-classes'
[IO.Directory]::CreateDirectory($output) | Out-Null
& javac -encoding UTF-8 --release 17 -cp $artifact -d $output (Join-Path $PSScriptRoot 'src/test/java/com/mozhi/llm/LlmAiServiceChecks.java')
if($LASTEXITCODE -ne 0){throw 'LLM regression compilation failed'}
& java -cp ($output+';'+$artifact) com.mozhi.llm.LlmAiServiceChecks
if($LASTEXITCODE -ne 0){throw 'LLM regression failed'}
