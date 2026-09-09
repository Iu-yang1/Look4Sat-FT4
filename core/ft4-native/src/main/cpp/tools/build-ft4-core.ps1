param(
    [Parameter(Mandatory = $true)]
    [string]$OutputDir,
    [ValidateSet('arm64-v8a', 'armeabi-v7a', 'x86_64')]
    [string]$Abi = 'arm64-v8a',
    [string]$CMakePath = '',
    [string]$NinjaPath = '',
    [string]$NdkRoot = '',
    [string]$FlangPath = '',
    [string]$BoostHeaders = '',
    [string]$LlvmSourceRoot = '',
    [ValidateSet('Debug', 'Release')]
    [string]$BuildProfile = 'Release'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Assert-ExistingPath([string]$Path, [string]$Label) {
    if ([string]::IsNullOrWhiteSpace($Path) -or -not (Test-Path $Path)) {
        throw "Missing $Label`: $Path"
    }
}

function Get-ObjectPath([string]$Source, [string]$ObjectDir, [string]$BasePath) {
    $relative = Get-Ft8cnRelativePath -BasePath $BasePath -Path $Source
    $hash = (Get-Ft8cnStringSha256 $relative.ToLowerInvariant()).Substring(0, 16)
    $name = [System.IO.Path]::GetFileNameWithoutExtension($Source)
    return Join-Path $ObjectDir "$name-$hash.o"
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$cppRoot = (Resolve-Path (Join-Path $scriptDir '..')).ProviderPath
$repoRoot = (Resolve-Path (Join-Path $scriptDir '..\..\..\..\..\..')).ProviderPath
. (Join-Path $scriptDir 'toolchain-common.ps1')
$roots = @(Get-Ft8cnCandidateRoots -RepoRoot $repoRoot)

$configuration = @{
    'arm64-v8a' = @{ Triple = 'aarch64-linux-android24' }
    'armeabi-v7a' = @{ Triple = 'armv7a-linux-androideabi24' }
    'x86_64' = @{ Triple = 'x86_64-linux-android24' }
}[$Abi]
$targetTriple = $configuration.Triple

$CMakePath = Find-Ft8cnExecutable -ExplicitPath $CMakePath -CommandNames @('cmake.exe', 'cmake') `
    -CandidateRoots $roots -RelativePatterns @('cmake\*\bin\cmake.exe')
$NinjaPath = Find-Ft8cnExecutable -ExplicitPath $NinjaPath -CommandNames @('ninja.exe', 'ninja') `
    -CandidateRoots $roots -RelativePatterns @('cmake\*\bin\ninja.exe')
$FlangPath = Find-Ft8cnExecutable -ExplicitPath $FlangPath -CommandNames @('flang.exe', 'flang-new.exe') `
    -CandidateRoots $roots -RelativePatterns @(
        'build\llvm-flang-*\bin\flang.exe',
        'build\llvm-flang-*\bin\flang-new.exe',
        'llvm*\bin\flang*.exe'
    )
$NdkRoot = Find-Ft8cnDirectory -ExplicitPath $NdkRoot -CandidateRoots $roots `
    -RelativePatterns @('ndk\*', 'AndroidSDKLIB\ndk\*') -RequiredChild 'build\cmake\android.toolchain.cmake'
$BoostHeaders = Find-Ft8cnDirectory -ExplicitPath $BoostHeaders -CandidateRoots $roots `
    -RelativePatterns @('boost_headers', 'boost*') -RequiredChild 'boost\version.hpp'
$LlvmSourceRoot = Find-Ft8cnDirectory -ExplicitPath $LlvmSourceRoot -CandidateRoots $roots `
    -RelativePatterns @('src\llvm-project-*.src', 'llvm-project-*.src') -RequiredChild 'runtimes\CMakeLists.txt'

$intrinsicModuleDir = Join-Path (Split-Path -Parent (Split-Path -Parent $FlangPath)) 'include\flang'
$ndkBin = Join-Path $NdkRoot 'toolchains\llvm\prebuilt\windows-x86_64\bin'
$clang = Join-Path $ndkBin 'clang.exe'
$clangxx = Join-Path $ndkBin 'clang++.exe'
$llvmAr = Join-Path $ndkBin 'llvm-ar.exe'
$manifest = Join-Path $cppRoot 'ft4-core-sources.manifest'
$runtimeScript = Join-Path $scriptDir 'build-flang-runtime.ps1'
$arm32Patch = Join-Path $scriptDir 'patches\flang-22.1-arm32-codegen.patch'

Assert-ExistingPath $CMakePath 'CMake'
Assert-ExistingPath $NinjaPath 'Ninja'
Assert-ExistingPath $NdkRoot 'Android NDK'
Assert-ExistingPath $FlangPath 'Flang'
Assert-ExistingPath $BoostHeaders 'Boost headers'
Assert-ExistingPath $LlvmSourceRoot 'LLVM source'
Assert-ExistingPath $intrinsicModuleDir 'Flang intrinsic modules'
Assert-ExistingPath $clang 'Android clang'
Assert-ExistingPath $clangxx 'Android clang++'
Assert-ExistingPath $llvmAr 'Android llvm-ar'
Assert-ExistingPath $manifest 'FT4 source manifest'

$fortranSources = New-Object System.Collections.Generic.List[string]
$cSources = New-Object System.Collections.Generic.List[string]
$cxxSources = New-Object System.Collections.Generic.List[string]
foreach ($line in Get-Content $manifest -Encoding UTF8) {
    $trimmed = $line.Trim()
    if (-not $trimmed -or $trimmed.StartsWith('#')) { continue }
    $fields = $trimmed -split '\|', 2
    if ($fields.Count -ne 2) { throw "Invalid source manifest entry: $line" }
    $source = Join-Path $cppRoot $fields[1].Replace('/', [System.IO.Path]::DirectorySeparatorChar)
    Assert-ExistingPath $source 'manifest source'
    switch ($fields[0]) {
        'fortran' { $fortranSources.Add($source) }
        'c' { $cSources.Add($source) }
        'cxx' { $cxxSources.Add($source) }
        default { throw "Unknown source type: $($fields[0])" }
    }
}

$OutputDir = [System.IO.Path]::GetFullPath($OutputDir)
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
if ($Abi -eq 'armeabi-v7a') {
    Assert-ExistingPath $arm32Patch 'Flang ARM32 patch'
    $preflightDir = Join-Path $OutputDir 'preflight'
    $preflightSource = Join-Path $preflightDir 'flang-arm32.f90'
    $preflightObject = Join-Path $preflightDir 'flang-arm32.o'
    New-Item -ItemType Directory -Force -Path $preflightDir | Out-Null
    Set-Content -LiteralPath $preflightSource -Encoding ASCII -Value @'
subroutine look4sat_flang_arm32_probe(values, result)
  character*1 values(*)
  integer result
  result = iachar(values(1))
end subroutine look4sat_flang_arm32_probe
'@
    $preflight = Invoke-Ft8cnNativeCapture -Path $FlangPath -Arguments @(
        '-target', $targetTriple, '-fPIC', '-c', $preflightSource, '-o', $preflightObject
    )
    if ($preflight.ExitCode -ne 0) {
        $applyPatchScript = Join-Path $scriptDir 'apply-flang-arm32-patch.ps1'
        throw "Flang ARM32 preflight failed. Command=$FlangPath -target $targetTriple; " +
            "apply=$applyPatchScript; output=$($preflight.Output)"
    }
}
& $runtimeScript -OutputDir $OutputDir -CMakePath $CMakePath -NinjaPath $NinjaPath `
    -NdkRoot $NdkRoot -FlangPath $FlangPath -LlvmSourceRoot $LlvmSourceRoot `
    -BuildProfile $BuildProfile -Optimization O2 -Abi $Abi -TargetTriple $targetTriple
if (-not $?) { throw "Flang runtime build failed: ABI=$Abi" }

$runtimeArchive = Join-Path $OutputDir 'libflang_rt.runtime.a'
Assert-ExistingPath $runtimeArchive 'Flang runtime archive'
$profileFlags = if ($BuildProfile -eq 'Debug') {
    # Flang 22 emits 64-bit static addresses for ARM32 type descriptors at low
    # optimization, so the official Fortran core uses the verified release flags.
    if ($Abi -eq 'armeabi-v7a') { @('-O2', '-DNDEBUG') } else { @('-O0', '-g') }
} else {
    @('-O2', '-DNDEBUG')
}
$allSources = @($fortranSources) + @($cSources) + @($cxxSources)
$fingerprintLines = New-Object System.Collections.Generic.List[string]
$fingerprintLines.Add("abi=$Abi")
$fingerprintLines.Add("target=$targetTriple")
$fingerprintLines.Add("profile=$BuildProfile")
$fingerprintLines.Add('upstream=6f69c7281a99d0243824ac02ce5706e8e897776f')
$fingerprintLines.Add('flang=' + (Get-Ft8cnCommandVersion $FlangPath @('--version')))
$fingerprintLines.Add('flang-binary=' + (Get-Ft8cnFileSha256 $FlangPath))
$fingerprintLines.Add('clang=' + (Get-Ft8cnCommandVersion $clang @('--version')))
if ($Abi -eq 'armeabi-v7a') {
    $fingerprintLines.Add('flang-arm32-patch=' + (Get-Ft8cnFileSha256 $arm32Patch))
}
foreach ($file in @($allSources + @($manifest, $runtimeScript, $PSCommandPath))) {
    $fingerprintLines.Add("file=$(Get-Ft8cnRelativePath $cppRoot $file)|$(Get-Ft8cnFileSha256 $file)")
}
$fingerprint = Get-Ft8cnStringSha256 ($fingerprintLines -join "`n")
$coreArchive = Join-Path $OutputDir 'liblook4sat_ft4_core.a'
$fingerprintFile = Join-Path $OutputDir 'ft4-core.fingerprint'
if ((Test-Path $coreArchive) -and (Test-Path $fingerprintFile) -and
        ((Get-Content $fingerprintFile -Raw).Trim() -eq $fingerprint)) {
    Write-Host "FT4 official core is current: $coreArchive"
    exit 0
}

$workDir = Join-Path $OutputDir (Join-Path 'work' $fingerprint.Substring(0, 16))
$objectDir = Join-Path $workDir 'obj'
$moduleDir = Join-Path $workDir 'mod'
$logDir = Join-Path $workDir 'logs'
New-Item -ItemType Directory -Force -Path $objectDir, $moduleDir, $logDir | Out-Null
$compileLog = Join-Path $logDir 'compile.log'
$includeDirs = @(
    (Join-Path $cppRoot 'vendor\wsjtx-3.0.0\lib'),
    (Join-Path $cppRoot 'vendor\wsjtx-3.0.0\lib\77bit'),
    (Join-Path $cppRoot 'vendor\wsjtx-3.0.0\lib\ft8'),
    (Join-Path $cppRoot 'vendor\wsjtx-3.0.0\lib\ft4'),
    $cppRoot
)
$nativeIncludes = @($cppRoot, (Join-Path $cppRoot 'vendor\wsjtx-3.0.0\lib'), $BoostHeaders)

$pending = New-Object System.Collections.Generic.List[string]
$fortranSources | ForEach-Object { $pending.Add($_) }
$objects = New-Object System.Collections.Generic.List[string]
$pass = 0
while ($pending.Count -gt 0) {
    $pass++
    $progress = 0
    $next = New-Object System.Collections.Generic.List[string]
    foreach ($source in $pending) {
        $object = Get-ObjectPath $source $objectDir $cppRoot
        $arguments = @('-target', $targetTriple, '-fPIC') + $profileFlags + @(
            '-fintrinsic-modules-path', $intrinsicModuleDir, '-module-dir', $moduleDir
        )
        foreach ($include in $includeDirs) { $arguments += @('-I', $include) }
        $arguments += @('-c', $source, '-o', $object)
        $result = Invoke-Ft8cnNativeCapture -Path $FlangPath -Arguments $arguments
        if ($result.ExitCode -eq 0) {
            $objects.Add($object)
            $progress++
        } else {
            $next.Add($source)
            Add-Content -LiteralPath $compileLog -Value "Waiting for $source`n$($result.Output)" -Encoding UTF8
        }
    }
    if ($progress -eq 0) {
        throw "FT4 Fortran core cannot make compilation progress: ABI=$Abi, log=$compileLog"
    }
    $pending = $next
}

foreach ($source in $cxxSources) {
    $object = Get-ObjectPath $source $objectDir $cppRoot
    $arguments = @('-target', $targetTriple, '-fPIC', '-std=c++17') + $profileFlags
    foreach ($include in $nativeIncludes) { $arguments += @('-I', $include) }
    $arguments += @('-c', $source, '-o', $object)
    $result = Invoke-Ft8cnNativeCapture -Path $clangxx -Arguments $arguments
    if ($result.ExitCode -ne 0) { throw "C++ compilation failed: $source`n$($result.Output)" }
    $objects.Add($object)
}
foreach ($source in $cSources) {
    $object = Get-ObjectPath $source $objectDir $cppRoot
    $arguments = @('-target', $targetTriple, '-fPIC', '-std=c11') + $profileFlags
    foreach ($include in $nativeIncludes) { $arguments += @('-I', $include) }
    $arguments += @('-c', $source, '-o', $object)
    $result = Invoke-Ft8cnNativeCapture -Path $clang -Arguments $arguments
    if ($result.ExitCode -ne 0) { throw "C compilation failed: $source`n$($result.Output)" }
    $objects.Add($object)
}

$candidateArchive = Join-Path $workDir 'liblook4sat_ft4_core.candidate.a'
if (Test-Path $candidateArchive) { Remove-Item -LiteralPath $candidateArchive -Force }
$archive = Invoke-Ft8cnNativeCapture -Path $llvmAr -Arguments (@('rcs', $candidateArchive) + @($objects))
if ($archive.ExitCode -ne 0) { throw "FT4 archive creation failed:`n$($archive.Output)" }

$probeSource = Join-Path $workDir 'link-probe.c'
$probeObject = Join-Path $workDir 'link-probe.o'
$probeLibrary = Join-Path $workDir 'libft4-probe.so'
Set-Content -LiteralPath $probeSource -Encoding ASCII -Value @'
#include "ft4_bridge.h"
int look4sat_ft4_probe(void) {
    int handle = ft4_bridge_create(12000, 90000, 0);
    if (handle <= 0) return -1;
    ft4_bridge_destroy(handle);
    return 0;
}
'@
$probeCompile = Invoke-Ft8cnNativeCapture -Path $clang -Arguments @(
    '-target', $targetTriple, '-fPIC', '-std=c11', '-I', $cppRoot,
    '-c', $probeSource, '-o', $probeObject
)
if ($probeCompile.ExitCode -ne 0) { throw "Link probe compilation failed:`n$($probeCompile.Output)" }
$link = Invoke-Ft8cnNativeCapture -Path $clangxx -Arguments @(
    '-target', $targetTriple, '-shared', '-fPIC', '-Wl,--no-undefined', '-o', $probeLibrary,
    $probeObject, '-Wl,--whole-archive', $candidateArchive, '-Wl,--no-whole-archive',
    $runtimeArchive, '-lm', '-lc', '-ldl'
)
if ($link.ExitCode -ne 0) { throw "FT4 official core link verification failed: ABI=$Abi`n$($link.Output)" }

Move-Item -LiteralPath $candidateArchive -Destination $coreArchive -Force
Set-Content -LiteralPath $fingerprintFile -Value $fingerprint -Encoding ASCII
Write-Host "FT4 official core build completed: ABI=$Abi archive=$coreArchive"
