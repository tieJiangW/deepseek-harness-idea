[CmdletBinding()]
param(
    [string]$OutputDir = "build/runtime",
    [string]$NodeVersion = "22.23.2",
    [string]$NodeSha256 = "1177B4137BA5ADAA56354AE40F1080C7450E8AE09CECB47DA459D1C52AC99F97",
    [string]$DshVersion = "0.2.1-rc.2",
    [string]$Mirror = "https://registry.npmmirror.com/-/binary/node",
    [string]$NpmRegistry = "",
    [string]$CacheDir = "",
    [string]$BootstrapNode = "node",
    [switch]$Bundle,
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

if (-not $NpmRegistry) {
    $NpmRegistry = if ($env:npm_config_registry) { $env:npm_config_registry } else { "https://registry.npmmirror.com/" }
}
if (-not $CacheDir) {
    $CacheDir = Join-Path $OutputDir ".npm-cache"
}
# 相对路径按仓库根解析成绝对路径：npm 以 <output>/dsh 为 cwd 运行，相对 --cache 会被解析到该 cwd 下。
if (-not [System.IO.Path]::IsPathRooted($OutputDir)) { $OutputDir = Join-Path $root $OutputDir }
if (-not [System.IO.Path]::IsPathRooted($CacheDir)) { $CacheDir = Join-Path $root $CacheDir }

$nodeZipName  = "node-v$NodeVersion-win-x64.zip"
$nodeUrl      = "$Mirror/v$NodeVersion/$nodeZipName"
$nodeZip      = Join-Path $OutputDir $nodeZipName
$nodeDir      = Join-Path $OutputDir "node"
$dshDir       = Join-Path $OutputDir "dsh"
$dshBin       = Join-Path $dshDir "node_modules/@deepseek-ai/dsh/lib/bin.js"
$nodeExe      = Join-Path $nodeDir "node.exe"
$bundleZip    = Join-Path (Split-Path -Parent $OutputDir) "runtime-bundle.zip"

function Write-Step([string]$msg) {
    Write-Host "==> $msg" -ForegroundColor Cyan
}

Write-Step "DSH runtime build: node v$NodeVersion + @deepseek-ai/dsh@$DshVersion -> $OutputDir"

# ---------- 1. Node.js ----------
if (Test-Path $nodeExe) {
    if ($Force) {
        Write-Step "Node exists, -Force rebuild"
        Remove-Item $nodeDir -Recurse -Force
    } else {
        Write-Step "Node exists, skip download"
        & $nodeExe -v | ForEach-Object { Write-Host "   node $_" }
    }
}

if (-not (Test-Path $nodeExe)) {
    New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
    if (-not (Test-Path $nodeZip)) {
        Write-Step "Download $nodeZipName"
        Write-Host "   $nodeUrl"
        & $BootstrapNode (Join-Path $PSScriptRoot "download-node.mjs") $nodeUrl $nodeZip
        if ($LASTEXITCODE -ne 0) { throw "download failed: $nodeUrl" }
    } else {
        Write-Step "Reuse existing $nodeZipName"
    }

    Write-Step "Verify SHA-256"
    $actual = (& $BootstrapNode (Join-Path $PSScriptRoot "sha256.mjs") $nodeZip)
    $actual = "$actual".Trim()
    if ($actual -ne $NodeSha256) {
        throw "SHA-256 mismatch: expected $NodeSha256, got $actual ($nodeZip)"
    }
    Write-Host "   ok: $actual"

    Write-Step "Extract to node/"
    tar -xf $nodeZip -C $OutputDir
    if ($LASTEXITCODE -ne 0) {
        Expand-Archive -Path $nodeZip -DestinationPath $OutputDir -Force
    }
    $extracted = Join-Path $OutputDir "node-v$NodeVersion-win-x64"
    if (Test-Path $extracted) {
        Move-Item $extracted $nodeDir -Force
    }
    if (-not (Test-Path $nodeExe)) { throw "node.exe not found: $nodeExe" }
    & $nodeExe -v | ForEach-Object { Write-Host "   node $_" }
}

# ---------- 2. @deepseek-ai/dsh ----------
# 版本漂移防护（v0.2.6 实测踩坑）：目录里是旧版 dsh 时，只判 `Test-Path $dshBin` 会直接复用并重新打包，
# 产出"看着成功、实际是旧运行时"的资产（旧树的传递依赖还可能骗过 bridge 断言）。故比对版本。
if (Test-Path $dshBin) {
    $dshPkgJson = Join-Path $dshDir "node_modules/@deepseek-ai/dsh/package.json"
    $installed = if (Test-Path $dshPkgJson) { (Get-Content $dshPkgJson -Raw | ConvertFrom-Json).version } else { $null }
    if ($Force -or $installed -ne $DshVersion) {
        Write-Step "dsh tree carries '$installed' but '$DshVersion' was requested; reinstalling the dsh subtree"
        Remove-Item $dshDir -Recurse -Force
    } else {
        Write-Step "dsh $installed exists, skip install"
    }
}

if (-not (Test-Path $dshBin)) {
    Write-Step "Install @deepseek-ai/dsh@$DshVersion + MCP bridge deps (npm --ignore-scripts)"
    New-Item -ItemType Directory -Force -Path $dshDir | Out-Null
    # bridge 依赖：dsh 0.2.1 起安装树不再含 @modelcontextprotocol/sdk / express，
    # 而插件自带的 mcp-ide-server.mjs 需要它们（缺失 → ide_* 工具全失效）。
    $pkgJson = '{"name":"dsh-runtime","private":true,"dependencies":{"@deepseek-ai/dsh":"' + $DshVersion + '","@modelcontextprotocol/sdk":"^1.30.0","express":"^5.2.1"}}'
    [System.IO.File]::WriteAllText((Join-Path $dshDir "package.json"), $pkgJson, [System.Text.UTF8Encoding]::new($false))

    $npmCli = Join-Path $nodeDir "node_modules/npm/bin/npm-cli.js"
    if (-not (Test-Path $npmCli)) { throw "bundled npm-cli.js missing: $npmCli" }

    Push-Location $dshDir
    try {
        & $nodeExe $npmCli install --ignore-scripts --no-audit --no-fund --cache $CacheDir --registry $NpmRegistry
        if ($LASTEXITCODE -ne 0) { throw "npm install failed with exit $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
    if (-not (Test-Path $dshBin)) { throw "dsh bin missing after install: $dshBin" }
    Write-Host "   ok: $dshBin"
}

# ---------- 3. Smoke verify ----------
Write-Step "Smoke verification"
$resolvedDshDir = if ([System.IO.Path]::IsPathRooted($dshDir)) { $dshDir } else { Join-Path $root $dshDir }
$dshPkgDir = (Join-Path $resolvedDshDir "node_modules/@deepseek-ai/dsh") -replace '\\','/'
# 版本硬断言：宁可构建失败，也不要打出一个"版本不对但看着成功"的运行时资产
$builtVersion = (Get-Content (Join-Path $resolvedDshDir "node_modules/@deepseek-ai/dsh/package.json") -Raw | ConvertFrom-Json).version
if ($builtVersion -ne $DshVersion) {
    throw "runtime carries dsh $builtVersion but $DshVersion was requested (delete $dshDir or pass -Force)"
}
Write-Host "   dsh $builtVersion"

# MCP bridge 依赖断言：必须能从 <output>/dsh/node_modules 解析（ESM eval 以 cwd 为解析基准）
Write-Step "Verify MCP bridge dependencies"
$bridgeModules = @(
    "@modelcontextprotocol/sdk/server/mcp.js",
    "@modelcontextprotocol/sdk/server/streamableHttp.js",
    "@modelcontextprotocol/sdk/server/express.js",
    "zod/v4"
)
Push-Location $resolvedDshDir
try {
    foreach ($m in $bridgeModules) {
        # 注意：字符串里的 ${m} 必须带花括号 —— `$m:` 会被 PowerShell 当作"作用域限定变量名"而报语法错误
        $probe = "import('${m}').then(()=>console.log('   ok ${m}')).catch(e=>{console.error('   MISSING ${m}:'+e.code);process.exit(1)})"
        & $nodeExe --input-type=module -e $probe
        if ($LASTEXITCODE -ne 0) { throw "MCP bridge dependency missing: $m" }
    }
} finally {
    Pop-Location
}

# ---------- 4. Bundle ----------
if ($Bundle) {
    Write-Step "Bundle runtime-bundle.zip"
    if (Test-Path $bundleZip) { Remove-Item $bundleZip -Force }
    $excludes = @("*.zip", ".npm-cache")
    $tarArgs = @("-a", "-c", "-f", $bundleZip)
    foreach ($ex in $excludes) { $tarArgs += @("--exclude", $ex) }
    $tarArgs += @("-C", $OutputDir, "node", "dsh")
    tar @tarArgs
    if ($LASTEXITCODE -ne 0) {
        Compress-Archive -Path (Join-Path $OutputDir "node"), (Join-Path $OutputDir "dsh") -DestinationPath $bundleZip -Force
    }
    $sizeMb = [math]::Round(((Get-Item $bundleZip).Length / 1MB), 1)
    Write-Host "   -> $bundleZip ($sizeMb MB)"
}

Write-Step "Done: $OutputDir"
Write-Host "   node: $nodeExe"
Write-Host "   dsh : $dshBin"
