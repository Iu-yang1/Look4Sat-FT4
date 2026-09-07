param(
    [string]$OutputDir = '',
    [string]$BuildDir = '',
    [string]$CMakePath = '',
    [string]$NinjaPath = '',
    [string]$NdkRoot = '',
    [string]$FlangPath = '',
    [string]$LlvmSourceRoot = '',
    [ValidateSet('Debug', 'Profile', 'Release')]
    [string]$BuildProfile = 'Release',
    [ValidateSet('O2')]
    [string]$Optimization = 'O2',
    [ValidateSet('arm64-v8a', 'armeabi-v7a', 'x86_64')]
    [string]$Abi = 'arm64-v8a',
    [string]$TargetTriple = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Assert-ExistingPath([string]$Path, [string]$Label) {
    if ([string]::IsNullOrWhiteSpace($Path) -or -not (Test-Path $Path)) {
        throw "Missing $Label. Provide the corresponding script parameter or environment variable: $Path"
    }
}

function Copy-DirectoryContents([string]$Source, [string]$Destination) {
    New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    Get-ChildItem -LiteralPath $Source -Force |
        Copy-Item -Destination $Destination -Recurse -Force
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$cppRoot = (Resolve-Path (Join-Path $scriptDir '..')).ProviderPath
$repoRoot = (Resolve-Path (Join-Path $scriptDir '..\..\..\..\..\..')).ProviderPath
$toolchainCommon = Join-Path $scriptDir 'toolchain-common.ps1'
. $toolchainCommon
$roots = @(Get-Ft8cnCandidateRoots -RepoRoot $repoRoot)

$abiConfiguration = @{
    'arm64-v8a' = @{ Triple = 'aarch64-linux-android24'; Platform = '24' }
    'armeabi-v7a' = @{ Triple = 'armv7a-linux-androideabi24'; Platform = '24' }
    'x86_64' = @{ Triple = 'x86_64-linux-android24'; Platform = '24' }
}[$Abi]
if (-not $TargetTriple) { $TargetTriple = $abiConfiguration.Triple }

$CMakePath = Find-Ft8cnExecutable -ExplicitPath $CMakePath -CommandNames @('cmake.exe', 'cmake') `
    -CandidateRoots $roots -RelativePatterns @('msys64\ucrt64\bin\cmake.exe', 'cmake\*\bin\cmake.exe')
$NinjaPath = Find-Ft8cnExecutable -ExplicitPath $NinjaPath -CommandNames @('ninja.exe', 'ninja') `
    -CandidateRoots $roots -RelativePatterns @('msys64\ucrt64\bin\ninja.exe', 'cmake\*\bin\ninja.exe')
$FlangPath = Find-Ft8cnExecutable -ExplicitPath $FlangPath -CommandNames @('flang.exe', 'flang-new.exe') `
    -CandidateRoots $roots -RelativePatterns @('build\llvm-flang-*\bin\flang.exe', 'llvm*\bin\flang*.exe')
$PatchPath = Find-Ft8cnExecutable -CommandNames @('patch.exe', 'patch') `
    -CandidateRoots $roots -RelativePatterns @('msys64\usr\bin\patch.exe', 'usr\bin\patch.exe')
$NdkRoot = Find-Ft8cnDirectory -ExplicitPath $NdkRoot -CandidateRoots $roots `
    -RelativePatterns @('ndk\*', 'AndroidSDKLIB\ndk\*') -RequiredChild 'build\cmake\android.toolchain.cmake'
$LlvmSourceRoot = Find-Ft8cnDirectory -ExplicitPath $LlvmSourceRoot -CandidateRoots $roots `
    -RelativePatterns @('src\llvm-project-*.src', 'llvm-project-*.src') -RequiredChild 'runtimes\CMakeLists.txt'

if (-not $OutputDir) { $OutputDir = Join-Path $cppRoot "out\$Abi" }
if (-not $BuildDir) {
    # flang-rt embeds absolute source paths in its object tree. Keep the default
    # workspace beside LLVM to avoid MAX_PATH failures under Gradle's deep .cxx tree.
    $workspaceBase = if ($env:LOOK4SAT_FLANG_RT_WORKSPACE) {
        $env:LOOK4SAT_FLANG_RT_WORKSPACE
    } else {
        $llvmSourceParent = Split-Path -Parent $LlvmSourceRoot
        $toolsRoot = if ((Split-Path -Leaf $llvmSourceParent) -eq 'src') {
            Split-Path -Parent $llvmSourceParent
        } else {
            $llvmSourceParent
        }
        Join-Path $toolsRoot 'build\look4sat-flang-rt'
    }
    $BuildDir = $workspaceBase
}
$OutputDir = [System.IO.Path]::GetFullPath($OutputDir)
$BuildDir = [System.IO.Path]::GetFullPath($BuildDir)
$patchFile = Join-Path $scriptDir 'patches\flang-rt-android-time.patch'
$toolchainFile = if ($NdkRoot) { Join-Path $NdkRoot 'build\cmake\android.toolchain.cmake' } else { '' }

Assert-ExistingPath $CMakePath 'CMake'
Assert-ExistingPath $NinjaPath 'Ninja'
Assert-ExistingPath $NdkRoot 'Android NDK'
Assert-ExistingPath $FlangPath 'Flang'
Assert-ExistingPath $PatchPath 'patch'
Assert-ExistingPath $LlvmSourceRoot 'LLVM source root'
Assert-ExistingPath $patchFile 'versioned flang-rt Android patch'
Assert-ExistingPath $toolchainFile 'Android CMake toolchain'

$sourceFiles = @(
    Get-ChildItem (Join-Path $LlvmSourceRoot 'cmake') -File -Recurse
    Get-ChildItem (Join-Path $LlvmSourceRoot 'runtimes') -File -Recurse
    Get-ChildItem (Join-Path $LlvmSourceRoot 'flang-rt') -File -Recurse
    Get-ChildItem (Join-Path $LlvmSourceRoot 'flang\cmake') -File -Recurse
    Get-ChildItem (Join-Path $LlvmSourceRoot 'flang\include') -File -Recurse
    Get-ChildItem (Join-Path $LlvmSourceRoot 'flang\lib\Decimal') -File -Recurse
    Get-ChildItem (Join-Path $LlvmSourceRoot 'flang\module') -File -Recurse
    Get-ChildItem (Join-Path $LlvmSourceRoot 'llvm\cmake') -File -Recurse
    Get-ChildItem (Join-Path $LlvmSourceRoot 'llvm\utils\llvm-lit') -File -Recurse
    Get-Item (Join-Path $LlvmSourceRoot 'llvm\utils\merge-json.py')
) | Sort-Object FullName
$fingerprintLines = New-Object System.Collections.Generic.List[string]
$fingerprintLines.Add("profile=$BuildProfile")
$fingerprintLines.Add("optimization=$Optimization")
$fingerprintLines.Add("target=$TargetTriple")
$fingerprintLines.Add("abi=$Abi")
$fingerprintLines.Add('cmake=' + (Get-Ft8cnCommandVersion $CMakePath @('--version')))
$fingerprintLines.Add('ninja=' + (Get-Ft8cnCommandVersion $NinjaPath @('--version')))
$fingerprintLines.Add('flang=' + (Get-Ft8cnCommandVersion $FlangPath @('--version')))
$fingerprintLines.Add('flang-binary=' + (Get-Ft8cnFileSha256 $FlangPath))
$fingerprintLines.Add('patch-tool=' + (Get-Ft8cnCommandVersion $PatchPath @('--version')))
$fingerprintLines.Add('patch=' + (Get-Ft8cnFileSha256 $patchFile))
$fingerprintLines.Add('script=' + (Get-Ft8cnFileSha256 $PSCommandPath))
$fingerprintLines.Add('toolchain-common=' + (Get-Ft8cnFileSha256 $toolchainCommon))
foreach ($file in $sourceFiles) {
    $relative = Get-Ft8cnRelativePath -BasePath $LlvmSourceRoot -Path $file.FullName
    $fingerprintLines.Add("source=$relative|$(Get-Ft8cnFileSha256 $file.FullName)")
}
$fingerprint = Get-Ft8cnStringSha256 ($fingerprintLines -join "`n")
$runtimeArchive = Join-Path $OutputDir 'libflang_rt.runtime.a'
$fingerprintFile = Join-Path $OutputDir 'flang-rt.fingerprint'
if ((Test-Path $runtimeArchive) -and (Test-Path $fingerprintFile) -and
        ((Get-Content $fingerprintFile -Raw).Trim() -eq $fingerprint)) {
    Write-Host "flang-rt Android archive is current: $runtimeArchive"
    exit 0
}

$workspace = Join-Path $BuildDir $fingerprint.Substring(0, 16)
$sourceWorkspace = Join-Path $workspace 'source'
$cmakeBuildDir = Join-Path $workspace 'build'
New-Item -ItemType Directory -Force -Path $OutputDir, $workspace | Out-Null
$workspaceSourcesComplete =
    (Test-Path (Join-Path $sourceWorkspace 'runtimes\CMakeLists.txt')) -and
    (Test-Path (Join-Path $sourceWorkspace 'cmake\Modules\CMakePolicy.cmake'))
if (-not $workspaceSourcesComplete) {
    # An interrupted build can leave a partial fingerprint workspace. Copy its
    # contents into the explicit target so a later build can safely resume it.
    New-Item -ItemType Directory -Force -Path $sourceWorkspace | Out-Null
    Copy-DirectoryContents (Join-Path $LlvmSourceRoot 'cmake') (Join-Path $sourceWorkspace 'cmake')
    Copy-DirectoryContents (Join-Path $LlvmSourceRoot 'runtimes') (Join-Path $sourceWorkspace 'runtimes')
    Copy-DirectoryContents (Join-Path $LlvmSourceRoot 'flang-rt') (Join-Path $sourceWorkspace 'flang-rt')
    Copy-DirectoryContents (Join-Path $LlvmSourceRoot 'flang\cmake') (Join-Path $sourceWorkspace 'flang\cmake')
    Copy-DirectoryContents (Join-Path $LlvmSourceRoot 'flang\include') (Join-Path $sourceWorkspace 'flang\include')
    Copy-DirectoryContents (Join-Path $LlvmSourceRoot 'flang\lib\Decimal') (Join-Path $sourceWorkspace 'flang\lib\Decimal')
    Copy-DirectoryContents (Join-Path $LlvmSourceRoot 'flang\module') (Join-Path $sourceWorkspace 'flang\module')
    Copy-DirectoryContents (Join-Path $LlvmSourceRoot 'llvm\cmake') (Join-Path $sourceWorkspace 'llvm\cmake')
    Copy-DirectoryContents (Join-Path $LlvmSourceRoot 'llvm\utils\llvm-lit') (Join-Path $sourceWorkspace 'llvm\utils\llvm-lit')
    Copy-Item (Join-Path $LlvmSourceRoot 'llvm\utils\merge-json.py') (Join-Path $sourceWorkspace 'llvm\utils\merge-json.py')

    # Normalize only the isolated copy, then apply the repository-owned patch.
    $timeSource = Join-Path $sourceWorkspace 'flang-rt\lib\runtime\time-intrinsic.cpp'
    $content = Get-Content $timeSource -Raw
    $androidBlock = '(?ms)#if defined\(__ANDROID__\)\s*\r?\n\s*if \(clock_gettime\(CLOCK_REALTIME, &tspec\) != 0\) \{\s*\r?\n#else\s*\r?\n\s*if \(timespec_get\(&tspec, TIME_UTC\) < 0\) \{\s*\r?\n#endif'
    $content = [regex]::Replace($content, $androidBlock, '  if (timespec_get(&tspec, TIME_UTC) < 0) {')
    Set-Content -LiteralPath $timeSource -Value $content -Encoding UTF8

    # Use patch directly: git -C may discover the parent FT8CN worktree instead of
    # treating this copied source tree as an isolated patch root.
    $patchResult = Invoke-Ft8cnNativeCapture -Path $PatchPath `
        -Arguments @('-d', $sourceWorkspace, '-p1', '--forward', '--batch', '-i', $patchFile)
    if ($patchResult.ExitCode -ne 0) {
        throw "Unable to apply flang-rt Android patch in isolated workspace:`n$($patchResult.Output)"
    }
    $patchedTimeSource = Get-Content $timeSource -Raw
    if ($patchedTimeSource -notmatch 'clock_gettime\(CLOCK_REALTIME,\s*&tspec\)') {
        throw "The flang-rt Android patch did not update the isolated time intrinsic source: $timeSource"
    }
}

$cmakeBuildType = switch ($BuildProfile) {
    'Debug' { 'Debug' }
    'Profile' { 'RelWithDebInfo' }
    default { 'Release' }
}
$optFlag = if ($BuildProfile -eq 'Debug') { '-O0 -g' } elseif ($BuildProfile -eq 'Profile') { '-O2 -g -DNDEBUG' } else { "-$Optimization -DNDEBUG" }
$cmakeSourceWorkspace = $sourceWorkspace.Replace('\', '/')
$cmakeBuildDirectory = $cmakeBuildDir.Replace('\', '/')
$cmakeNinjaPath = $NinjaPath.Replace('\', '/')
$cmakeToolchainFile = $toolchainFile.Replace('\', '/')
$cmakeFlangPath = $FlangPath.Replace('\', '/')
$configureArgs = @(
    '-G', 'Ninja',
    '-S', "$cmakeSourceWorkspace/runtimes",
    '-B', $cmakeBuildDirectory,
    "-DCMAKE_MAKE_PROGRAM=$cmakeNinjaPath",
    '-DLLVM_ENABLE_RUNTIMES=flang-rt',
    "-DLLVM_DEFAULT_TARGET_TRIPLE=$TargetTriple",
    "-DCMAKE_TOOLCHAIN_FILE=$cmakeToolchainFile",
    "-DANDROID_ABI=$Abi",
    "-DANDROID_PLATFORM=$($abiConfiguration.Platform)",
    "-DCMAKE_BUILD_TYPE=$cmakeBuildType",
    "-DCMAKE_C_FLAGS_$($cmakeBuildType.ToUpperInvariant())=$optFlag",
    "-DCMAKE_CXX_FLAGS_$($cmakeBuildType.ToUpperInvariant())=$optFlag",
    "-DCMAKE_Fortran_FLAGS_$($cmakeBuildType.ToUpperInvariant())=$optFlag",
    "-DCMAKE_C_COMPILER_TARGET=$TargetTriple",
    "-DCMAKE_CXX_COMPILER_TARGET=$TargetTriple",
    "-DCMAKE_Fortran_COMPILER=$cmakeFlangPath",
    "-DCMAKE_Fortran_COMPILER_TARGET=$TargetTriple",
    '-DCMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY',
    '-DCMAKE_Fortran_COMPILER_WORKS=ON',
    '-DCMAKE_Fortran_COMPILER_FORCED=ON',
    '-DFLANG_RT_INCLUDE_TESTS=OFF',
    '-DFLANG_RT_ENABLE_SHARED=OFF',
    '-DFLANG_RT_ENABLE_STATIC=ON'
)
$configure = Invoke-Ft8cnNativeCapture -Path $CMakePath -Arguments $configureArgs
$configureLog = Join-Path $workspace 'configure.log'
Set-Content -LiteralPath $configureLog -Value $configure.Output -Encoding UTF8
if ($configure.ExitCode -ne 0) { throw "flang-rt configure failed; see $configureLog" }
$build = Invoke-Ft8cnNativeCapture -Path $CMakePath -Arguments @('--build', $cmakeBuildDir, '--target', 'flang_rt.runtime.static', '--config', $cmakeBuildType)
$buildLog = Join-Path $workspace 'build.log'
Set-Content -LiteralPath $buildLog -Value $build.Output -Encoding UTF8
if ($build.ExitCode -ne 0) { throw "flang-rt build failed; see $buildLog" }

$builtRuntimeArchive = Join-Path $cmakeBuildDir 'flang-rt\lib\libflang_rt.runtime.a'
Assert-ExistingPath $builtRuntimeArchive 'built flang-rt static archive'
Copy-Item $builtRuntimeArchive $runtimeArchive -Force
Set-Content -LiteralPath $fingerprintFile -Value $fingerprint -Encoding ASCII
Write-Host "flang-rt Android archive ready: $runtimeArchive"
