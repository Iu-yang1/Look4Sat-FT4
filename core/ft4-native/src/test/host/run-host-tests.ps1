param(
    [Parameter(Mandatory = $true)]
    [string]$SamplePath,
    [string]$BuildDir = '',
    [string]$MsysRoot = $env:MSYS2_ROOT
)

$ErrorActionPreference = 'Stop'
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $BuildDir) {
    $BuildDir = Join-Path ([System.IO.Path]::GetTempPath()) 'look4sat-ft4-host-test'
}
$sample = (Resolve-Path -LiteralPath $SamplePath).ProviderPath
$sampleHash = (Get-FileHash -LiteralPath $sample -Algorithm SHA256).Hash.ToLowerInvariant()
if ($sampleHash -ne 'd9e91fa04ba138a7b9f41b4103823c77ca1c3a9775101f6b14d60935bcd3813b') {
    throw "FT4 固定语料 SHA-256 不匹配：$sampleHash"
}

$resolvedMsysRoot = $MsysRoot
if (-not $resolvedMsysRoot) {
    $gfortranCommand = Get-Command gfortran.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $gfortranCommand) {
        throw 'Provide the MSYS2 UCRT64 toolchain through MSYS2_ROOT, -MsysRoot, or PATH.'
    }
    $ucrtRoot = Split-Path -Parent (Split-Path -Parent $gfortranCommand.Source)
    $resolvedMsysRoot = Split-Path -Parent $ucrtRoot
}
$ucrtBin = Join-Path $resolvedMsysRoot 'ucrt64\bin'
$msysBin = Join-Path $resolvedMsysRoot 'usr\bin'
$cmake = Join-Path $ucrtBin 'cmake.exe'
$ninja = Join-Path $ucrtBin 'ninja.exe'
$ctest = Join-Path $ucrtBin 'ctest.exe'
foreach ($required in @($cmake, $ninja, (Join-Path $ucrtBin 'gfortran.exe'))) {
    if (-not (Test-Path -LiteralPath $required)) { throw "缺少 FT4 主机测试工具：$required" }
}

$oldPath = $env:PATH
try {
    $env:PATH = "$ucrtBin;$msysBin;$oldPath"
    & $cmake -S $scriptDir -B $BuildDir -G Ninja `
        "-DCMAKE_MAKE_PROGRAM=$ninja" -DCMAKE_BUILD_TYPE=Release
    if ($LASTEXITCODE -ne 0) { throw 'FT4 主机测试配置失败' }
    & $cmake --build $BuildDir
    if ($LASTEXITCODE -ne 0) { throw 'FT4 主机测试构建失败' }
    & $ctest --test-dir $BuildDir --output-on-failure
    if ($LASTEXITCODE -ne 0) { throw 'FT4 无语料主机自检失败' }
    $output = @(& (Join-Path $BuildDir 'look4sat_ft4_host_test.exe') $sample)
    $testExitCode = $LASTEXITCODE
    $output | Write-Output
    if ($testExitCode -ne 0) { throw 'FT4 固定语料/噪声/串行主机自检失败' }
    $resultLines = @($output | Where-Object { $_ -match '^\s+#\d+\s' })
    $normalized = $resultLines -join "`n"
    $hashAlgorithm = [Security.Cryptography.SHA256]::Create()
    try {
        $resultHashText = [BitConverter]::ToString(
            $hashAlgorithm.ComputeHash([Text.Encoding]::UTF8.GetBytes($normalized)))
        $resultHash = $resultHashText.Replace('-', '').ToLowerInvariant()
    } finally {
        $hashAlgorithm.Dispose()
    }
    $expectedResultHash = '877dd38b0d05c754d31c7dd3b0610e61489f86d1cb316123012b9b8c148d1d14'
    if ($resultHash -ne $expectedResultHash) {
        throw "FT4 16 条完整结果哈希不匹配：expected=$expectedResultHash actual=$resultHash"
    }
    Write-Output "FT4_RESULT_SHA256=$resultHash"
} finally {
    $env:PATH = $oldPath
}
