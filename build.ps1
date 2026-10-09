# Via Menu Fix —— 免 Gradle 构建脚本（Windows）
#
# 依赖：JDK 17、Android SDK（build-tools 34.x + platforms/android-34）
# 用法：powershell -ExecutionPolicy Bypass -File build.ps1
# 可选环境变量：ANDROID_HOME / ANDROID_SDK_ROOT、VIAMENUFIX_KEYSTORE、VERSION_NAME、VERSION_CODE

$ErrorActionPreference = 'Continue'   # 原生命令的 warning 走 stderr 属正常，用 $LASTEXITCODE 判断成败

$ROOT = $PSScriptRoot
$VERSION_NAME = if ($env:VERSION_NAME) { $env:VERSION_NAME } else { '1.0.0' }
$VERSION_CODE = if ($env:VERSION_CODE) { $env:VERSION_CODE } else { '1' }

# ---- 定位 SDK ----
$SDK = if ($env:ANDROID_HOME) { $env:ANDROID_HOME }
       elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT }
       elseif (Test-Path 'E:\Android\sdk') { 'E:\Android\sdk' }
       else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }

$BT_DIR = Get-ChildItem (Join-Path $SDK 'build-tools') -Directory -ErrorAction SilentlyContinue |
          Where-Object { $_.Name -like '34.*' } | Sort-Object Name -Descending | Select-Object -First 1
if (-not $BT_DIR) { throw "找不到 $SDK\build-tools\34.*，请先用 sdkmanager 安装 build-tools;34.0.0" }
$BT = $BT_DIR.FullName
$ANDROID_JAR = Join-Path $SDK 'platforms\android-34\android.jar'

$AAPT2    = Join-Path $BT 'aapt2.exe'
$D8       = Join-Path $BT 'd8.bat'
$ZIPALIGN = Join-Path $BT 'zipalign.exe'
$APKSIGN  = Join-Path $BT 'apksigner.bat'
$KEYSTORE = if ($env:VIAMENUFIX_KEYSTORE) { $env:VIAMENUFIX_KEYSTORE } else { Join-Path $ROOT 'keystore\viamenufix.jks' }

foreach ($p in @($AAPT2, $D8, $ZIPALIGN, $APKSIGN, $ANDROID_JAR)) {
    if (-not (Test-Path $p)) { throw "缺少构建依赖: $p" }
}

# ---- 工作目录：aapt2 在 Windows 上处理不了含中文的路径，统一镜像到纯 ASCII 目录 ----
$WORK = Join-Path $env:TEMP 'viamenufix-build'
if ($WORK -match '[^\x00-\x7F]') { $WORK = "$env:SystemDrive\viamenufix-build" }
$BUILD = Join-Path $WORK 'build'
$OUT   = Join-Path $ROOT 'out'

Write-Host "[0/6] 镜像工程到 $WORK"
Remove-Item -Recurse -Force $WORK -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $WORK, $OUT,
    (Join-Path $BUILD 'classes'), (Join-Path $BUILD 'dex') | Out-Null
Copy-Item (Join-Path $ROOT 'AndroidManifest.xml') $WORK -Force
foreach ($d in @('src', 'res', 'assets', 'lib')) {
    if (Test-Path (Join-Path $ROOT $d)) { Copy-Item (Join-Path $ROOT $d) (Join-Path $WORK $d) -Recurse -Force }
}

Write-Host '[1/6] aapt2 compile/link'
& $AAPT2 compile --dir (Join-Path $WORK 'res') -o (Join-Path $BUILD 'res.zip')
& $AAPT2 link `
    -o (Join-Path $BUILD 'base.apk') `
    -I $ANDROID_JAR `
    --manifest (Join-Path $WORK 'AndroidManifest.xml') `
    --java (Join-Path $BUILD 'gen') `
    --min-sdk-version 19 --target-sdk-version 34 `
    --version-code $VERSION_CODE --version-name $VERSION_NAME `
    (Join-Path $BUILD 'res.zip')
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link 失败' }

Write-Host '[2/6] javac'
$sources = Get-ChildItem -Path (Join-Path $WORK 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& javac -source 8 -target 8 -encoding UTF-8 -nowarn `
    -bootclasspath $ANDROID_JAR `
    -cp (Join-Path $WORK 'lib\api-82.jar') `
    -d (Join-Path $BUILD 'classes') $sources
if ($LASTEXITCODE -ne 0) { throw 'javac 失败' }

Write-Host '[3/6] d8'
$classFiles = Get-ChildItem -Path (Join-Path $BUILD 'classes') -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& $D8 --lib $ANDROID_JAR --lib (Join-Path $WORK 'lib\api-82.jar') --min-api 19 `
    --output (Join-Path $BUILD 'dex') $classFiles 2>$null
if ($LASTEXITCODE -ne 0) { throw 'd8 失败' }

Write-Host '[4/6] 组装 APK'
$unsigned = Join-Path $BUILD 'unsigned.apk'
Copy-Item (Join-Path $BUILD 'base.apk') $unsigned -Force
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($unsigned, 'Update')
try {
    $dex = (Get-ChildItem (Join-Path $BUILD 'dex') -Filter *.dex | Select-Object -First 1).FullName
    foreach ($pair in @(@{ src = $dex; name = 'classes.dex' },
                        @{ src = (Join-Path $WORK 'assets\xposed_init'); name = 'assets/xposed_init' })) {
        $old = $zip.GetEntry($pair.name)
        if ($old) { $old.Delete() }
        [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $zip, $pair.src, $pair.name, [System.IO.Compression.CompressionLevel]::Optimal)
    }
} finally { $zip.Dispose() }

Write-Host '[5/6] zipalign'
$aligned = Join-Path $BUILD 'aligned.apk'
& $ZIPALIGN -f 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw 'zipalign 失败' }

Write-Host '[6/6] apksigner'
if (-not (Test-Path $KEYSTORE)) {
    Write-Host "  未找到密钥，生成自签密钥: $KEYSTORE" -ForegroundColor Yellow
    New-Item -ItemType Directory -Force -Path (Split-Path $KEYSTORE) | Out-Null
    & keytool -genkeypair -keystore $KEYSTORE -alias viamenufix -keyalg RSA -keysize 2048 `
        -validity 10000 -storepass android -keypass android `
        -dname 'CN=ViaMenuFix, O=local, C=CN' | Out-Null
}
$apk = Join-Path $OUT "ViaMenuFix-$VERSION_NAME.apk"
& $APKSIGN sign --ks $KEYSTORE --ks-key-alias viamenufix `
    --ks-pass pass:android --key-pass pass:android `
    --v1-signing-enabled true --v2-signing-enabled true `
    --out $apk $aligned
if ($LASTEXITCODE -ne 0) { throw 'apksigner 失败' }

Write-Host ''
& $APKSIGN verify --print-certs $apk | Select-Object -First 2
Write-Host "构建完成: $apk" -ForegroundColor Green
Write-Host ("大小: " + [math]::Round((Get-Item $apk).Length / 1KB, 1) + " KB")
