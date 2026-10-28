param(
    [string]$JavaHome = $env:JAVA_HOME,
    [Parameter(Mandatory=$true)][string]$GnuBin,
    [Parameter(Mandatory=$true)][string]$KotlinStdlib,
    [Parameter(Mandatory=$true)][string]$OutputDir
)
$ErrorActionPreference='Stop'
$scriptDir=Split-Path -Parent $MyInvocation.MyCommand.Path
$moduleRoot=[IO.Path]::GetFullPath((Join-Path $scriptDir '../../..'))
$output=[IO.Path]::GetFullPath($OutputDir)
New-Item -ItemType Directory -Path $output -Force | Out-Null
$oldPath=$env:PATH
try {
    $env:PATH="$GnuBin;$oldPath"
    & (Join-Path $GnuBin 'g++.exe') -shared -O2 -std=c++17 `
        "-I$JavaHome/include" "-I$JavaHome/include/win32" `
        (Join-Path $moduleRoot 'src/main/cpp/ft4_jni.cpp') `
        (Join-Path $moduleRoot 'rust/target/release/liblook4sat_ft4.a') `
        -lkernel32 -lntdll -luserenv -lws2_32 -ldbghelp -o (Join-Path $output 'look4sat_ft4.dll')
    if($LASTEXITCODE -ne 0) { throw 'Host JNI link failed' }
    $classes=Join-Path $moduleRoot 'build/intermediates/runtime_library_classes_jar/debug/bundleLibRuntimeToJarDebug/classes.jar'
    $classpath="$classes;$KotlinStdlib"
    & (Join-Path $JavaHome 'bin/javac.exe') -cp $classpath -d $output (Join-Path $scriptDir 'java/com/rtbishop/look4sat/core/ft4/Ft4JniHostTest.java')
    if($LASTEXITCODE -ne 0) { throw 'Host JNI test compilation failed' }
    & (Join-Path $JavaHome 'bin/java.exe') "-Djava.library.path=$output" -cp "$output;$classpath" `
        com.rtbishop.look4sat.core.ft4.Ft4JniHostTest (Join-Path $moduleRoot 'rust/tests/fixtures/wsjtx-ft4.wav')
    if($LASTEXITCODE -ne 0) { throw 'Host JNI contract failed' }
} finally { $env:PATH=$oldPath }
