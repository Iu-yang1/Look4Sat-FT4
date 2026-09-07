Set-StrictMode -Version Latest

function Get-Ft8cnCandidateRoots {
    param([string]$RepoRoot)

    $roots = New-Object System.Collections.Generic.List[string]
    foreach ($value in @(
            $env:FT8CN_TOOLS_ROOT,
            $env:MSYS2_ROOT,
            $env:ANDROID_HOME,
            $env:ANDROID_SDK_ROOT,
            $env:ANDROID_NDK_HOME,
            $env:LLVM_ROOT,
            $env:FLANG_ROOT,
            $env:BOOST_ROOT,
            $env:JAVA_HOME)) {
        if (-not [string]::IsNullOrWhiteSpace($value)) {
            $roots.Add($value)
        }
    }

    if (-not [string]::IsNullOrWhiteSpace($RepoRoot)) {
        $cursor = Get-Item $RepoRoot
        while ($null -ne $cursor) {
            foreach ($name in @('tools', 'AndroidSDKLIB', 'Android', 'Sdk')) {
                $candidate = Join-Path $cursor.FullName $name
                if (Test-Path $candidate) {
                    $roots.Add($candidate)
                }
            }
            $cursor = $cursor.Parent
        }
    }

    if ($env:LOCALAPPDATA) {
        $roots.Add((Join-Path $env:LOCALAPPDATA 'Android\Sdk'))
    }
    $gitCommand = Get-Command git -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -ne $gitCommand -and (Test-Path -LiteralPath $gitCommand.Source)) {
        $gitParent = Split-Path -Parent $gitCommand.Source
        if ((Split-Path -Leaf $gitParent) -in @('bin', 'cmd')) {
            $roots.Add((Split-Path -Parent $gitParent))
        }
    }
    foreach ($drive in Get-PSDrive -PSProvider FileSystem) {
        foreach ($name in @('tools', 'AndroidSDKLIB')) {
            $candidate = Join-Path $drive.Root $name
            if (Test-Path $candidate) {
                $roots.Add($candidate)
            }
        }
    }

    $seen = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
    foreach ($root in $roots) {
        if ([string]::IsNullOrWhiteSpace($root) -or -not (Test-Path $root)) {
            continue
        }
        $resolved = (Resolve-Path $root).ProviderPath
        if ($seen.Add($resolved)) {
            $resolved
        }
    }
}

function Get-Ft8cnLocalProperties {
    param([string]$RepoRoot)

    $result = @{}
    $path = Join-Path $RepoRoot 'local.properties'
    if (-not (Test-Path $path)) {
        return $result
    }
    foreach ($line in Get-Content $path) {
        if ($line -match '^\s*([^#!][^=]*)=(.*)$') {
            $key = $matches[1].Trim()
            $value = $matches[2].Trim().Replace('\:', ':').Replace('\\', '\')
            $result[$key] = $value
        }
    }
    return $result
}

function Find-Ft8cnExecutable {
    param(
        [string]$ExplicitPath,
        [string[]]$CommandNames,
        [string[]]$CandidateRoots,
        [string[]]$RelativePatterns
    )

    if (-not [string]::IsNullOrWhiteSpace($ExplicitPath) -and (Test-Path $ExplicitPath)) {
        return (Resolve-Path $ExplicitPath).ProviderPath
    }
    foreach ($name in $CommandNames) {
        $command = Get-Command $name -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($null -ne $command -and (Test-Path $command.Source)) {
            return $command.Source
        }
    }
    foreach ($root in $CandidateRoots) {
        foreach ($pattern in $RelativePatterns) {
            $matches = @(Get-ChildItem -Path (Join-Path $root $pattern) -File -ErrorAction SilentlyContinue |
                Sort-Object FullName -Descending)
            if ($matches.Count -gt 0) {
                return $matches[0].FullName
            }
        }
    }
    return $null
}

function Find-Ft8cnDirectory {
    param(
        [string]$ExplicitPath,
        [string[]]$CandidateRoots,
        [string[]]$RelativePatterns,
        [string]$RequiredChild
    )

    if (-not [string]::IsNullOrWhiteSpace($ExplicitPath) -and
            (Test-Path (Join-Path $ExplicitPath $RequiredChild))) {
        return (Resolve-Path $ExplicitPath).ProviderPath
    }
    foreach ($root in $CandidateRoots) {
        if (Test-Path (Join-Path $root $RequiredChild)) {
            return (Resolve-Path $root).ProviderPath
        }
        foreach ($pattern in $RelativePatterns) {
            $patternPath = Join-Path $root $pattern
            if (-not [System.Management.Automation.WildcardPattern]::ContainsWildcardCharacters($pattern) -and
                    (Test-Path $patternPath)) {
                $resolvedPattern = (Resolve-Path $patternPath).ProviderPath
                if (Test-Path (Join-Path $resolvedPattern $RequiredChild)) {
                    return $resolvedPattern
                }
            }
            foreach ($candidate in @(Get-ChildItem -Path $patternPath -Directory -ErrorAction SilentlyContinue |
                    Sort-Object FullName -Descending)) {
                if (Test-Path (Join-Path $candidate.FullName $RequiredChild)) {
                    return $candidate.FullName
                }
            }
        }
    }
    return $null
}

function Get-Ft8cnCommandVersion {
    param([string]$Path, [string[]]$Arguments)

    if ([string]::IsNullOrWhiteSpace($Path) -or -not (Test-Path $Path)) {
        return ''
    }
    # Native version commands often write normal output to stderr; capture both streams directly.
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $Path
    $startInfo.Arguments = (($Arguments | ForEach-Object {
                if ($_ -match '[\s"]') { '"' + $_.Replace('"', '\"') + '"' } else { $_ }
            }) -join ' ')
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::Start($startInfo)
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    $stdout = $stdoutTask.Result
    $stderr = $stderrTask.Result
    return (($stdout + $stderr).Trim())
}

function Get-Ft8cnFileSha256 {
    param([Parameter(Mandatory = $true)][string]$Path)

    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-Ft8cnStringSha256 {
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$Value)

    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Value)
        return (($sha.ComputeHash($bytes) | ForEach-Object { $_.ToString('x2') }) -join '')
    } finally {
        $sha.Dispose()
    }
}

function Get-Ft8cnRelativePath {
    param(
        [Parameter(Mandatory = $true)][string]$BasePath,
        [Parameter(Mandatory = $true)][string]$Path
    )

    $baseFull = [System.IO.Path]::GetFullPath($BasePath).TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    $pathFull = [System.IO.Path]::GetFullPath($Path)
    $baseUri = New-Object System.Uri($baseFull)
    $pathUri = New-Object System.Uri($pathFull)
    return [System.Uri]::UnescapeDataString($baseUri.MakeRelativeUri($pathUri).ToString()).Replace('/', '\')
}

function Invoke-Ft8cnNativeCapture {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [string[]]$Arguments = @()
    )

    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $Path
    $startInfo.Arguments = (($Arguments | ForEach-Object {
                if ($_ -match '[\s"]') { '"' + $_.Replace('"', '\"') + '"' } else { $_ }
            }) -join ' ')
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::Start($startInfo)
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    $stdout = $stdoutTask.Result
    $stderr = $stderrTask.Result
    return [pscustomobject]@{
        ExitCode = $process.ExitCode
        Output = ($stdout + $stderr).TrimEnd()
    }
}
