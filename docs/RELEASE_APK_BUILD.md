# ShineVoice 正式 APK 构建与签名

## 版本与签名来源

- 版本唯一来源：仓库根目录 version.properties。
- 目标包名：com.shinevoice。
- 正式包固定使用 ShineVoice release signing 配置。
- 密钥只从进程环境读取：SHINEVOICE_RELEASE_STORE_FILE、SHINEVOICE_RELEASE_STORE_PASSWORD、SHINEVOICE_RELEASE_KEY_ALIAS、SHINEVOICE_RELEASE_KEY_PASSWORD。
- scripts/build-release-apk.ps1 会把现有的 SHINE_WRITER_RELEASE_* 变量桥接到 ShineVoice 名称，兼容本机既有安全配置；不会输出密码或 alias 的值。

## Windows

~~~powershell
./scripts/build-release-apk.ps1
./scripts/verify-release-apk.ps1 -ApkPath ./dist/apk/release/ShineVoice-V1.0.0-release.apk
~~~

脚本输出正式 APK 路径、版本、文件大小与 SHA-256。验证脚本还会检查 zipalign、APK v2 签名、单一 signer、包名和 versionCode，以及发布证书指纹。

## CI / 发布前安全门禁

没有完整签名变量时，任何 release Gradle 任务都应 fail closed。调试包仍可在没有签名变量时构建。发布前应依次执行：

~~~powershell
./gradlew.bat :app:testDebugUnitTest --console=plain
./gradlew.bat :app:lintDebug --console=plain
./gradlew.bat :app:assembleDebug --console=plain
./scripts/build-release-apk.ps1
./scripts/verify-release-apk.ps1
node ./scripts/generate-update-metadata.js
node ./scripts/verify-release-metadata.js
~~~

构建产物位于 dist/，已加入 Git 忽略。不得提交 APK、WAV、模型、AAR、密钥库或包含秘密的日志。
