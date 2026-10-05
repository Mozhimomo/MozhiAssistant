param([switch]$Offline, [switch]$IncludeLocalConfig)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskVersion = (Get-Content -Raw -LiteralPath (Join-Path $taskRoot 'mod_info.json') | ConvertFrom-Json).version
if ($taskVersion -notmatch '^\d+\.\d+\.\d+$') { throw '发行版本必须采用数字格式：主版本.次版本.修订版本' }

# 默认使用公开模板；明确指定 IncludeLocalConfig 时原样包含本地配置，不解析环境变量。
$taskTemplatePath = Join-Path $taskRoot 'data/config/agent.properties.example'
$taskPublicValues = @{}
foreach ($taskLine in [IO.File]::ReadAllLines($taskTemplatePath)) {
    if ($taskLine -match '^\s*([^#!\s=]+)\s*=\s*(.*)$') {
        if ($taskPublicValues.ContainsKey($matches[1])) { throw '公开配置中存在重复的配置项' }
        $taskPublicValues[$matches[1]] = $matches[2].Trim()
    }
}
if (!$taskPublicValues.ContainsKey('apiKey') -or $taskPublicValues['apiKey'] -ne '' -or
    ($taskPublicValues.ContainsKey('cheapApiKey') -and $taskPublicValues['cheapApiKey'] -ne '') -or
    [string]::IsNullOrWhiteSpace($taskPublicValues['modelName']) -or
    $taskPublicValues['baseUrl'] -notin @('https://api.openai.com/v1/', 'https://api.deepseek.com/v1/') -or
    ($taskPublicValues['cheapBaseUrl'] -and $taskPublicValues['cheapBaseUrl'] -notin @('https://api.openai.com/v1/', 'https://api.deepseek.com/v1/'))) {
    throw '公开默认配置必须使用空 API 密钥、模型名称和公开服务地址；未输出任何私有配置'
}
$taskConfigSource = if ($IncludeLocalConfig) { 'data/config/agent.properties' } else { 'data/config/agent.properties.example' }
if (!(Test-Path -LiteralPath (Join-Path $taskRoot $taskConfigSource) -PathType Leaf)) { throw '待打包的配置文件不存在' }

Push-Location -LiteralPath $taskRoot
try {
    $taskMavenArgs = @('-Dmozhi.skipDeployment=true', '-Dmaven.test.skip=true', 'clean', 'package')
    if ($Offline) { $taskMavenArgs = @('-o') + $taskMavenArgs }
    & mvn @taskMavenArgs
    if ($LASTEXITCODE -ne 0) { throw '发行构建失败' }
} finally { Pop-Location }

$taskFiles = [ordered]@{
    'mod_info.json' = 'mod_info.json'
    'README.md' = 'README.md'
    'fleet-agent/README.md' = 'fleet-agent/README.md'
    'llm-client/README.md' = 'llm-client/README.md'
    'data/config/agent.properties' = $taskConfigSource
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
        if ($taskZip.Entries.Count -ne $taskFiles.Count) { throw '发行包条目与预期不符' }
        foreach ($taskItem in $taskFiles.GetEnumerator()) {
            $taskEntry = $taskZip.GetEntry('MozhiAssistant/' + $taskItem.Key)
            if ($null -eq $taskEntry) { throw ('发行包缺少条目：' + $taskItem.Key) }
            $taskStream = $taskEntry.Open()
            try { $taskHash = Get-ReleaseStreamHash $taskStream } finally { $taskStream.Dispose() }
            if ($taskHash -ne (Get-FileHash -LiteralPath (Join-Path $taskRoot $taskItem.Value) -Algorithm SHA256).Hash) {
                throw ('发行包内容不一致：' + $taskItem.Key)
            }
        }
    } finally { $taskZip.Dispose() }

    # 检查新构建的运行包，包括 Maven 元数据以及测试命令是否已移除。
    foreach ($taskItem in $taskFiles.GetEnumerator() | Where-Object { $_.Key.EndsWith('.jar') }) {
        $taskJar = [IO.Compression.ZipFile]::OpenRead((Join-Path $taskRoot $taskItem.Value))
        try {
            if ($taskJar.GetEntry('com/mozhi/assistant/bootstrap/MozhiFleetFollowCommand.class')) { throw '运行 JAR 中仍包含已废弃的跟随测试' }
            $taskMetadata = @($taskJar.Entries | Where-Object { $_.FullName -like 'META-INF/maven/com.mozhi/*/pom.properties' })
            if ($taskMetadata.Count -ne 1) { throw '缺少构建产物的版本元数据' }
            $taskReader = [IO.StreamReader]::new($taskMetadata[0].Open())
            try {
                if ($taskReader.ReadToEnd() -notmatch ('(?m)^version=' + [regex]::Escape($taskVersion) + '\r?$')) { throw '构建产物版本不一致' }
            } finally { $taskReader.Dispose() }
        } finally { $taskJar.Dispose() }
    }
    if ([IO.File]::ReadAllText((Join-Path $taskRoot 'data/console/commands.csv')).Contains('MozhiFleetFollow')) { throw '已废弃的跟随命令仍在注册列表中' }

    # 两个路径都是当前工作区 dist 目录内的固定文件。
    Move-Item -LiteralPath $taskTempArchive -Destination $taskArchive -Force
    $taskDigest = (Get-FileHash -LiteralPath $taskArchive -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText(($taskArchive + '.sha256'), ($taskDigest + '  ' + [IO.Path]::GetFileName($taskArchive) + [Environment]::NewLine), [Text.UTF8Encoding]::new($false))
    Get-Item -LiteralPath $taskArchive, ($taskArchive + '.sha256') | Select-Object FullName, Length
    Write-Output ('发行包验证通过：配置来源 ' + $taskConfigSource + '，哈希和版本一致，未包含原生跟随测试。')
} finally {
    if (Test-Path -LiteralPath $taskTempArchive) { Remove-Item -LiteralPath $taskTempArchive }
}
