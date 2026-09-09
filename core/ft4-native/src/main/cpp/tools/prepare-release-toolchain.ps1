param(
    [Parameter(Mandatory = $true)]
    [string]$ToolchainRoot,
    [int]$ParallelJobs = 2
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$llvmVersion = '22.1.5'
$llvmArchiveName = "llvm-project-$llvmVersion.src.tar.xz"
$llvmArchiveUrl = "https://github.com/llvm/llvm-project/releases/download/llvmorg-$llvmVersion/$llvmArchiveName"
$llvmArchiveSha256 = '7972b87b705a003ce70ab55f9f0fb495d156887cba0eb296d284731139118e2c'
$llvmHostInstallerName = "LLVM-$llvmVersion-win64.exe"
$llvmHostInstallerUrl = "https://github.com/llvm/llvm-project/releases/download/llvmorg-$llvmVersion/$llvmHostInstallerName"
$llvmHostInstallerSha256 = 'faf0e0795ea91913d29856b3efcb178f2349e5137ae06eec4c96af8eda4565d8'
$boostVersion = '1.91.0-1'
$boostArchiveName = "boost-$boostVersion-b2-nodocs.zip"
$boostArchiveUrl = "https://github.com/boostorg/boost/releases/download/boost-$boostVersion/$boostArchiveName"
$boostArchiveSha256 = '4b9a29e7bfc44a43f0215c7e20684deeccd8c55ce8133bec3563ed18670421f0'

function Get-RequiredCommand([string]$Name) {
    $command = Get-Command $Name -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $command -or -not (Test-Path -LiteralPath $command.Source)) {
        throw "Required command is unavailable: $Name"
    }
    return $command.Source
}

function Get-PinnedArchive(
    [string]$Url,
    [string]$Destination,
    [string]$ExpectedSha256
) {
    if (Test-Path -LiteralPath $Destination) {
        $actual = (Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actual -eq $ExpectedSha256) { return }
        Remove-Item -LiteralPath $Destination -Force
    }
    Invoke-WebRequest -Uri $Url -OutFile $Destination
    $actual = (Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $ExpectedSha256) {
        Remove-Item -LiteralPath $Destination -Force
        throw "Downloaded archive failed SHA-256 verification: $Url"
    }
}

function Test-Arm32Flang([string]$FlangPath, [string]$ProbeRoot) {
    if (-not (Test-Path -LiteralPath $FlangPath)) { return $false }
    New-Item -ItemType Directory -Path $ProbeRoot -Force | Out-Null
    $source = Join-Path $ProbeRoot 'arm32-probe.f90'
    $object = Join-Path $ProbeRoot 'arm32-probe.o'
    Set-Content -LiteralPath $source -Encoding ASCII -Value @'
subroutine look4sat_flang_arm32_probe(values, result)
  character*1 values(*)
  integer result
  result = iachar(values(1))
end subroutine look4sat_flang_arm32_probe
'@
    & $FlangPath -target armv7a-linux-androideabi24 -fPIC -c $source -o $object
    return $LASTEXITCODE -eq 0 -and (Test-Path -LiteralPath $object)
}

$ToolchainRoot = [System.IO.Path]::GetFullPath($ToolchainRoot)
$downloadRoot = Join-Path $ToolchainRoot 'downloads'
$sourceParent = Join-Path $ToolchainRoot 'source'
$llvmSourceRoot = Join-Path $sourceParent "llvm-project-$llvmVersion.src"
$llvmBuildRoot = Join-Path $ToolchainRoot "llvm-flang-$llvmVersion"
$llvmHostRoot = Join-Path $ToolchainRoot "llvm-host-$llvmVersion"
$boostExtractRoot = Join-Path $ToolchainRoot 'boost'
$probeRoot = Join-Path $ToolchainRoot 'probe'
New-Item -ItemType Directory -Path $downloadRoot, $sourceParent, $boostExtractRoot -Force | Out-Null

$llvmArchive = Join-Path $downloadRoot $llvmArchiveName
Get-PinnedArchive $llvmArchiveUrl $llvmArchive $llvmArchiveSha256
if (-not (Test-Path -LiteralPath (Join-Path $llvmSourceRoot 'llvm\CMakeLists.txt'))) {
    $tar = Get-RequiredCommand 'tar.exe'
    & $tar -xf $llvmArchive -C $sourceParent
    if ($LASTEXITCODE -ne 0) { throw "Unable to extract $llvmArchive" }
}
if (-not (Test-Path -LiteralPath (Join-Path $llvmSourceRoot 'llvm\CMakeLists.txt'))) {
    throw "LLVM source archive has an unexpected layout: $llvmArchive"
}

$clangCl = Join-Path $llvmHostRoot 'bin\clang-cl.exe'
$lldLink = Join-Path $llvmHostRoot 'bin\lld-link.exe'
if (-not (Test-Path -LiteralPath $clangCl) -or -not (Test-Path -LiteralPath $lldLink)) {
    $llvmHostInstaller = Join-Path $downloadRoot $llvmHostInstallerName
    Get-PinnedArchive $llvmHostInstallerUrl $llvmHostInstaller $llvmHostInstallerSha256
    & $llvmHostInstaller /S "/D=$llvmHostRoot"
    if ($LASTEXITCODE -ne 0) { throw 'Unable to install the pinned LLVM host compiler' }
}
if (-not (Test-Path -LiteralPath $clangCl) -or -not (Test-Path -LiteralPath $lldLink)) {
    throw "Pinned LLVM host compiler has an unexpected layout: $llvmHostRoot"
}

$boostArchive = Join-Path $downloadRoot $boostArchiveName
Get-PinnedArchive $boostArchiveUrl $boostArchive $boostArchiveSha256
$boostVersionHeader = Get-ChildItem -LiteralPath $boostExtractRoot -Filter version.hpp -File -Recurse |
    Where-Object { $_.FullName -match '[\\/]boost[\\/]version\.hpp$' } |
    Select-Object -First 1
if ($null -eq $boostVersionHeader) {
    Expand-Archive -LiteralPath $boostArchive -DestinationPath $boostExtractRoot -Force
    $boostVersionHeader = Get-ChildItem -LiteralPath $boostExtractRoot -Filter version.hpp -File -Recurse |
        Where-Object { $_.FullName -match '[\\/]boost[\\/]version\.hpp$' } |
        Select-Object -First 1
}
if ($null -eq $boostVersionHeader) { throw "Boost archive has an unexpected layout: $boostArchive" }
$boostHeaders = Split-Path -Parent (Split-Path -Parent $boostVersionHeader.FullName)

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
& (Join-Path $scriptRoot 'apply-flang-arm32-patch.ps1') -LlvmSourceRoot $llvmSourceRoot

$flangPath = Join-Path $llvmBuildRoot 'bin\flang-new.exe'
if (-not (Test-Arm32Flang $flangPath $probeRoot)) {
    $androidCmakeRoot = if ($env:ANDROID_HOME) {
        Join-Path $env:ANDROID_HOME 'cmake\3.22.1\bin'
    } else {
        ''
    }
    $cmake = if ($androidCmakeRoot) { Join-Path $androidCmakeRoot 'cmake.exe' } else { '' }
    $ninja = if ($androidCmakeRoot) { Join-Path $androidCmakeRoot 'ninja.exe' } else { '' }
    if (-not $cmake -or -not (Test-Path -LiteralPath $cmake)) {
        $cmake = Get-RequiredCommand 'cmake.exe'
    }
    if (-not $ninja -or -not (Test-Path -LiteralPath $ninja)) {
        $ninja = Get-RequiredCommand 'ninja.exe'
    }
    $configureArgs = @(
        '-G', 'Ninja',
        '-S', (Join-Path $llvmSourceRoot 'llvm'),
        '-B', $llvmBuildRoot,
        "-DCMAKE_MAKE_PROGRAM=$ninja",
        '-DCMAKE_BUILD_TYPE=Release',
        "-DCMAKE_C_COMPILER=$clangCl",
        "-DCMAKE_CXX_COMPILER=$clangCl",
        "-DCMAKE_LINKER=$lldLink",
        '-DLLVM_ENABLE_PROJECTS=clang;mlir;flang',
        '-DLLVM_TARGETS_TO_BUILD=AArch64;ARM;X86',
        '-DLLVM_ENABLE_ASSERTIONS=OFF',
        '-DLLVM_ENABLE_TERMINFO=OFF',
        '-DLLVM_ENABLE_ZLIB=OFF',
        '-DLLVM_ENABLE_ZSTD=OFF',
        '-DLLVM_ENABLE_BINDINGS=OFF',
        '-DLLVM_INCLUDE_BENCHMARKS=OFF',
        '-DLLVM_INCLUDE_DOCS=OFF',
        '-DLLVM_INCLUDE_EXAMPLES=OFF',
        '-DLLVM_INCLUDE_TESTS=OFF',
        '-DCLANG_INCLUDE_TESTS=OFF',
        '-DMLIR_INCLUDE_TESTS=OFF',
        '-DFLANG_INCLUDE_TESTS=OFF',
        '-DCLANG_ENABLE_ARCMT=OFF',
        '-DCLANG_ENABLE_STATIC_ANALYZER=OFF'
    )
    & $cmake @configureArgs
    if ($LASTEXITCODE -ne 0) { throw 'Unable to configure the pinned Flang toolchain' }
    & $cmake --build $llvmBuildRoot --target flang-new --parallel $ParallelJobs
    if ($LASTEXITCODE -ne 0) { throw 'Unable to build the pinned Flang toolchain' }
}
if (-not (Test-Arm32Flang $flangPath $probeRoot)) {
    throw "The built Flang compiler failed the Android ARM32 preflight: $flangPath"
}
$intrinsicModules = Join-Path $llvmBuildRoot 'include\flang'
if (-not (Test-Path -LiteralPath $intrinsicModules)) {
    throw "The built Flang compiler is missing intrinsic modules: $intrinsicModules"
}

$runtimeWorkspace = Join-Path $ToolchainRoot 'flang-runtime'
if ($env:GITHUB_ENV) {
    "FT4_FLANG_PATH=$flangPath" | Out-File -LiteralPath $env:GITHUB_ENV -Encoding utf8 -Append
    "FT4_BOOST_HEADERS=$boostHeaders" | Out-File -LiteralPath $env:GITHUB_ENV -Encoding utf8 -Append
    "FT4_LLVM_SOURCE_ROOT=$llvmSourceRoot" | Out-File -LiteralPath $env:GITHUB_ENV -Encoding utf8 -Append
    "LOOK4SAT_FLANG_RT_WORKSPACE=$runtimeWorkspace" | Out-File -LiteralPath $env:GITHUB_ENV -Encoding utf8 -Append
}

Write-Host "Pinned FT4 release toolchain is ready: LLVM/Flang $llvmVersion, Boost $boostVersion"
