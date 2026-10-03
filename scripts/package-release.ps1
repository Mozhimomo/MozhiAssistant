param([switch]$Offline)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskVersion = (Get-Content -Raw -LiteralPath (Join-Path $taskRoot 'mod_info.json') | ConvertFrom-Json).version
if ($taskVersion -notmatch '^\d+\.\d+\.\d+$') { throw 'Expected a numeric major.minor.patch release version' }

# Only the public template is ever read. Never package the local agent.properties.
$taskTemplatePath = Join-Path $taskRoot 'data/config/agent.properties.example'
$taskPublicValues = @{}
foreach ($taskLine in [IO.File]::ReadAllLines($taskTemplatePath)) {
    if ($taskLine -match '^\s*([^#!\s=]+)\s*=\s*(.*)$') {
        if ($taskPublicValues.ContainsKey($matches[1])) { throw 'Duplicate public configuration key' }
        $taskPublicValues[$matches[1]] = $matches[2].Trim()
    }
}
if (!$taskPublicValues.ContainsKey('apiKey') -or $taskPublicValues['apiKey'] -ne '' -or
    $taskPublicValues['modelName'] -ne 'YOUR_TOOL_CAPABLE_MODEL' -or
    $taskPublicValues['baseUrl'] -ne 'https://api.openai.com/v1/') {
    throw 'Public defaults must use a blank API key, placeholder model and public endpoint; no private values were printed'
}

Push-Location -LiteralPath $taskRoot
try {
    $taskMavenArgs = @('-Dmozhi.skipDeployment=true', '-Dmaven.test.skip=true', 'clean', 'package')
    if ($Offline) { $taskMavenArgs = @('-o') + $taskMavenArgs }
    & mvn @taskMavenArgs
    if ($LASTEXITCODE -ne 0) { throw 'Release build failed' }
} finally { Pop-Location }

$taskFiles = [ordered]@{
    'mod_info.json' = 'mod_info.json'
    'README.md' = 'README.md'
    'fleet-agent/README.md' = 'fleet-agent/README.md'
    'llm-client/README.md' = 'llm-client/README.md'
    'data/config/agent.properties' = 'data/config/agent.properties.example'
    'data/config/agent.properties.example' = 'data/config/agent.properties.example'
    'data/console/commands.csv' = 'data/console/commands.csv'
    'graphics/portraits/SOD_portrait_mozhi.png' = 'graphics/portraits/SOD_portrait_mozhi.png'
    'jars/mozhi-bootstrap.jar' = 'bootstrap/target/mozhi-bootstrap.jar'
    'jars/agent-runtime.jar' = 'agent-runtime/target/agent-runtime.jar'
    'jars/fleet-agent.jar' = 'fleet-agent/target/fleet-agent.jar'
    'jars/mozhi-llm-client.jar' = "llm-client/target/mozhi-llm-client-$taskVersion-all.jar"
}
$taskOutputDir = Join-Path $taskRoot 'dist'
[IO.Directory]::CreateDirectory($taskOutputDir) | Out-Null
$taskArchive = Join-Path $taskOutputDir "MozhiAssistant-$taskVersion.zip"
$taskTempArchive = Join-Path $taskOutputDir ('.release-' + [Guid]::NewGuid().ToString('N') + '.zip')

function Get-ReleaseStreamHash($taskStream) {
    $taskHasher = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($taskHasher.ComputeHash($taskStream)).Replace('-', '') }
    finally { $taskHasher.Dispose() }
}

try {
    $taskZip = [IO.Compression.ZipFile]::Open($taskTempArchive, [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($taskItem in $taskFiles.GetEnumerator()) {
            [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($taskZip, (Join-Path $taskRoot $taskItem.Value),
                ('MozhiAssistant/' + $taskItem.Key), [IO.Compression.CompressionLevel]::Optimal) | Out-Null
        }
    } finally { $taskZip.Dispose() }

    $taskZip = [IO.Compression.ZipFile]::OpenRead($taskTempArchive)
    try {
        if ($taskZip.Entries.Count -ne $taskFiles.Count) { throw 'Unexpected release entries' }
        foreach ($taskItem in $taskFiles.GetEnumerator()) {
            $taskEntry = $taskZip.GetEntry('MozhiAssistant/' + $taskItem.Key)
            if ($null -eq $taskEntry) { throw ('Missing release entry: ' + $taskItem.Key) }
            $taskStream = $taskEntry.Open()
            try { $taskHash = Get-ReleaseStreamHash $taskStream } finally { $taskStream.Dispose() }
            if ($taskHash -ne (Get-FileHash -LiteralPath (Join-Path $taskRoot $taskItem.Value) -Algorithm SHA256).Hash) {
                throw ('Release content mismatch: ' + $taskItem.Key)
            }
        }
    } finally { $taskZip.Dispose() }

    # Check the fresh runtime artifact, including Maven metadata and removal of the test command.
    foreach ($taskItem in $taskFiles.GetEnumerator() | Where-Object { $_.Key.EndsWith('.jar') }) {
        $taskJar = [IO.Compression.ZipFile]::OpenRead((Join-Path $taskRoot $taskItem.Value))
        try {
            if ($taskJar.GetEntry('com/mozhi/assistant/bootstrap/MozhiFleetFollowCommand.class')) { throw 'Retired follow test remains in runtime JAR' }
            $taskMetadata = @($taskJar.Entries | Where-Object { $_.FullName -like 'META-INF/maven/com.mozhi/*/pom.properties' })
            if ($taskMetadata.Count -ne 1) { throw 'Missing artifact version metadata' }
            $taskReader = [IO.StreamReader]::new($taskMetadata[0].Open())
            try {
                if ($taskReader.ReadToEnd() -notmatch ('(?m)^version=' + [regex]::Escape($taskVersion) + '\r?$')) { throw 'Artifact version mismatch' }
            } finally { $taskReader.Dispose() }
        } finally { $taskJar.Dispose() }
    }
    if ([IO.File]::ReadAllText((Join-Path $taskRoot 'data/console/commands.csv')).Contains('MozhiFleetFollow')) { throw 'Retired follow command remains registered' }

    # Both paths are fixed files directly inside this workspace's dist directory.
    Move-Item -LiteralPath $taskTempArchive -Destination $taskArchive -Force
    $taskDigest = (Get-FileHash -LiteralPath $taskArchive -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText(($taskArchive + '.sha256'), ($taskDigest + '  ' + [IO.Path]::GetFileName($taskArchive) + [Environment]::NewLine), [Text.UTF8Encoding]::new($false))
    Get-Item -LiteralPath $taskArchive, ($taskArchive + '.sha256') | Select-Object FullName, Length
    Write-Output 'Release verified: public default configuration, matching hashes and versions, no native follow test.'
} finally {
    if (Test-Path -LiteralPath $taskTempArchive) { Remove-Item -LiteralPath $taskTempArchive }
}
