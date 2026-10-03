$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$core=Join-Path $root '../../starsector-core'
$output=Join-Path $PSScriptRoot 'target/regression-classes'
[IO.Directory]::CreateDirectory($output) | Out-Null
$classpath=(Join-Path $PSScriptRoot 'target/classes')+';'+(Join-Path $root 'bootstrap/target/classes')+';'+(Join-Path $root 'llm-client/target/mozhi-llm-client-0.1.0-all.jar')+';'+(Join-Path $core '*')
$sources=Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src/test/java/com/mozhi/fleet') -Filter '*.java' | ForEach-Object {$_.FullName}
& javac -encoding UTF-8 -cp $classpath -d $output $sources
if($LASTEXITCODE -ne 0){throw 'Regression compilation failed'}
& java -cp ($output+';'+$classpath) com.mozhi.fleet.FleetRegressionChecks
if($LASTEXITCODE -ne 0){throw 'Fleet regression failed'}
& java -cp ($output+';'+$classpath) com.mozhi.fleet.FleetTradingChecks
if($LASTEXITCODE -ne 0){throw 'Fleet trading regression failed'}
& java -cp ($output+';'+$classpath) com.mozhi.fleet.FleetMissionChecks
if($LASTEXITCODE -ne 0){throw 'Fleet mission regression failed'}
