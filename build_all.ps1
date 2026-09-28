# yr2-logic-monitor 全平台一键构建与部署脚本 (Desktop + Android 混合Jar)
$ErrorActionPreference = "Stop"

$workspaceRoot = $PSScriptRoot
$libsDir = Join-Path $workspaceRoot "..\_libs"
$arcCoreJar = Join-Path $libsDir "arc-core-v160.5.jar"
$coreJar = Join-Path $libsDir "core-v160.5.jar"

$d8Path = "D:\tool or code environment\android-sdk\build-tools\35.0.0\d8.bat"
$androidJar = "D:\tool or code environment\android-sdk\platforms\android-34\android.jar"

Write-Host "1. 编译 Java 17 字节码..." -ForegroundColor Cyan
$classesDir = Join-Path $workspaceRoot "build\classes\java\main"
if (!(Test-Path $classesDir)) { New-Item -ItemType Directory -Path $classesDir -Force | Out-Null }

$javaFiles = Get-ChildItem -Recurse -Filter "*.java" (Join-Path $workspaceRoot "src") | ForEach-Object { $_.FullName }
javac --release 17 -cp "$arcCoreJar;$coreJar" -d $classesDir $javaFiles

Write-Host "2. 使用 Android D8 打包 classes.dex (兼容 Android 移动端)..." -ForegroundColor Cyan
$buildLibsDir = Join-Path $workspaceRoot "build\libs"
if (!(Test-Path $buildLibsDir)) { New-Item -ItemType Directory -Path $buildLibsDir -Force | Out-Null }

$classFiles = Get-ChildItem -Recurse -Filter "*.class" $classesDir | ForEach-Object { $_.FullName }
& $d8Path --min-api 14 --output $buildLibsDir --classpath $arcCoreJar --classpath $coreJar --lib $androidJar $classFiles

Write-Host "3. 打包全平台合一 Jar (包含 JVM class 与 Android dex)..." -ForegroundColor Cyan
$outputJar = Join-Path $buildLibsDir "yr2-logic-monitor.jar"
jar --create --file $outputJar -C $classesDir . -C $buildLibsDir classes.dex -C $workspaceRoot mod.hjson -C (Join-Path $workspaceRoot "assets") icon.png

Write-Host "4. 部署至游戏运行环境与备份目录..." -ForegroundColor Cyan
$destModPath = "C:\Users\NOSBhghgg\AppData\Roaming\Mindustry\mods\yr2-logic-monitor-1.6.0-v160.5.jar"
$destBackupPath = Join-Path $workspaceRoot "..\yr2-logic-monitor-1.6.0-desktop.jar"

Copy-Item -LiteralPath $outputJar -Destination $destModPath -Force
Copy-Item -LiteralPath $outputJar -Destination $destBackupPath -Force
Copy-Item -LiteralPath $outputJar -Destination (Join-Path $buildLibsDir "yr2-logic-monitorDesktop.jar") -Force

Write-Host "构建成功! 产物大小: $((Get-Item $outputJar).Length) 字节" -ForegroundColor Green
