# yr2-logic-monitor 全平台一键构建与部署脚本 (Desktop + Android 混合Jar)
$ErrorActionPreference = "Stop"

$workspaceRoot = $PSScriptRoot
$libsDir = Join-Path $workspaceRoot "..\_libs"
$arcCoreJar = Join-Path $libsDir "arc-core-v160.5.jar"
$coreJar = Join-Path $libsDir "core-v160.5.jar"

$d8Path = "D:\tool or code environment\android-sdk\build-tools\35.0.0\d8.bat"
$androidJar = "D:\tool or code environment\android-sdk\platforms\android-34\android.jar"
$modsDir = "C:\Users\NOSBhghgg\AppData\Roaming\Mindustry\mods"
$gameVersion = "v160.5"

#版本号只认 mod.hjson 这一个出处: 写死在脚本里, 产物名会与实际版本对不上,
#部署时就在 mods 里留下"同模组两份不同名"的 jar, 游戏同时加载, 谁也不知道跑的是哪份。
$modHjsonText = Get-Content (Join-Path $workspaceRoot "mod.hjson") -Raw
if ($modHjsonText -notmatch '(?m)^\s*version:\s*"([^"]+)"') { throw "mod.hjson 里读不到 version" }
$version = $Matches[1]
Write-Host "模组版本: $version (适配 $gameVersion)" -ForegroundColor Cyan

Write-Host "1. 编译 Java 17 字节码..." -ForegroundColor Cyan
$classesDir = Join-Path $workspaceRoot "build\classes\java\main"
if (!(Test-Path $classesDir)) { New-Item -ItemType Directory -Path $classesDir -Force | Out-Null }

$javaFiles = Get-ChildItem -Recurse -Filter "*.java" (Join-Path $workspaceRoot "src") | ForEach-Object { $_.FullName }
javac --release 17 -Xlint:all -cp "$arcCoreJar;$coreJar" -d $classesDir $javaFiles

Write-Host "2. 使用 Android D8 打包 classes.dex (兼容 Android 移动端)..." -ForegroundColor Cyan
$buildLibsDir = Join-Path $workspaceRoot "build\libs"
if (!(Test-Path $buildLibsDir)) { New-Item -ItemType Directory -Path $buildLibsDir -Force | Out-Null }

$classFiles = Get-ChildItem -Recurse -Filter "*.class" $classesDir | ForEach-Object { $_.FullName }
& $d8Path --min-api 14 --output $buildLibsDir --classpath $arcCoreJar --classpath $coreJar --lib $androidJar $classFiles

Write-Host "3. 打包全平台合一 Jar (包含 JVM class 与 Android dex)..." -ForegroundColor Cyan
$outputJar = Join-Path $buildLibsDir "yr2-logic-monitor.jar"
jar --create --file $outputJar -C $classesDir . -C $buildLibsDir classes.dex -C $workspaceRoot mod.hjson -C (Join-Path $workspaceRoot "assets") icon.png

Write-Host "4. 部署至游戏运行环境与备份目录..." -ForegroundColor Cyan
$destModPath = Join-Path $modsDir "yr2-logic-monitor-$version-$gameVersion.jar"
$destBackupPath = Join-Path $workspaceRoot "..\yr2-logic-monitor-$version-desktop+android.jar"

#铁律: mods 里只能有一份 yr2 jar。旧的那份不论叫什么版本号都先清掉, 否则两份同时被加载。
Get-ChildItem -Path $modsDir -Filter "yr2-logic-monitor*.jar" -File | ForEach-Object {
    Write-Host "  清除旧产物: $($_.Name)" -ForegroundColor Yellow
    Remove-Item -LiteralPath $_.FullName -Force
}

Copy-Item -LiteralPath $outputJar -Destination $destModPath -Force
Copy-Item -LiteralPath $outputJar -Destination $destBackupPath -Force

#这里不再把合并包盖回 yr2-logic-monitorDesktop.jar: 那个名字在 Gradle 那条流水线里指"只有桌面字节码"的产物,
#而合并包里已经含 classes.dex, 一旦被后续 jarAndroid 再喂给 d8, 就会打出双份 dex。
$md5 = (Get-FileHash -Algorithm MD5 -LiteralPath $destModPath).Hash
Write-Host "构建成功! 版本 $version 大小 $((Get-Item $outputJar).Length) 字节 MD5 $md5" -ForegroundColor Green
Write-Host "已部署: $destModPath" -ForegroundColor Green
