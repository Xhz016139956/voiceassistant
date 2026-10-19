# 单元测试运行脚本
#
# 为什么需要它：
#   工作区路径含中文（"新建文件夹 (7)"），Gradle 在 Windows 上给测试 worker 传
#   classpath 时会把中文编码坏掉，导致 JUnit 报
#     ClassNotFoundException: ...RuleParserTest
#   但测试类其实已经编译出来了。把源码镜像到纯英文路径再跑就没问题。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File tools\run-unit-tests.ps1

$ErrorActionPreference = "Stop"

$src = Split-Path -Parent $PSScriptRoot
$dst = "D:\va-ut"
$toolchain = "D:\android-toolchain"

$env:JAVA_HOME = Join-Path $toolchain "jdk17"
$env:ANDROID_HOME = Join-Path $toolchain "sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME

Write-Host "同步源码到 $dst ..."
robocopy $src $dst /E /XD build .gradle /NFL /NDL /NJH /NJS /NC /NS | Out-Null

Push-Location $dst
try {
    & (Join-Path $toolchain "gradle-8.7\bin\gradle.bat") testDebugUnitTest --no-daemon --console=plain
    Write-Host "测试结果：$dst\app\build\reports\tests\testDebugUnitTest\index.html"
} finally {
    Pop-Location
}
