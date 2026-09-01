# ZipVoice-Distill INT8 模型部署

ShineVoice 使用官方 sherpa-onnx 发布版 `v1.13.6` AAR 和官方 `tts-models` 中的 `sherpa-onnx-zipvoice-distill-int8-zh-en-emilia`。AAR 与模型均不提交 Git。

## 标配内置（默认交付方式）

标准 ZipVoice 模型已作为标配打包进 APK：构建时 Gradle 任务 `prepareBundledModelAssets`
自动把 `runtime/zipvoice/`（模型目录、vocos 声码器、manifest、默认参考音频）装载到
`app/src/main/assets/bundled/`（Git 忽略；`runtime/` 缺失时任务跳过，APK 退化为无内置
模型，行为与旧版手动安装一致）。首次启动 `ModelDirectoryResolver.inspect()` 检测到
模型缺失时自动解包到应用专属目录（约 200 MB，数秒完成），随后照常执行聚合 SHA-256
校验；已就绪的用户自装模型永不被覆盖。APK 体积约 200 MB，用户零下载、离线即用。

## 下载与解包（更新内置模型 / 无 runtime 目录时）

在项目根目录执行：

```powershell
.\scripts\fetch-sherpa-onnx.ps1
.\scripts\prepare-zipvoice-runtime.ps1
```

脚本会把 AAR 放在 `third_party/`，模型放在 `runtime/zipvoice/`，并从官方模型包复制 `test_wavs/leijun-1.wav` 为测试参考音频。默认 `referenceText`：

```text
那还是三十六年前, 一九八七年. 我呢考上了武汉大学的计算机系.
```

脚本同时生成 `model-manifest.properties`。App 刷新模型时会校验 encoder、decoder、vocoder、tokens、lexicon 的总大小和聚合 SHA-256；缺失、损坏或版本/引擎不匹配时不会进入 Native 推理。

## 推送 Android

```powershell
.\scripts\push-zipvoice-assets.ps1 -Serial emulator-5554
```

Debug 包的 applicationId 是 `com.shinevoice.debug`。脚本推送到：

```text
/sdcard/Android/data/com.shinevoice.debug/files/models/zipvoice/
/sdcard/Android/data/com.shinevoice.debug/files/voices/default/reference.wav
```

如果切换到非 Debug 变体，需传入对应 `-Package`。应用设置页显示实际解析到的绝对路径与缺失文件，不会因模型缺失崩溃。

## ABI

当前 APK 只打包 `arm64-v8a` 与 `x86_64`。这样覆盖当前 x86_64 模拟器和常见真机 CPU；未启用 ARMv7、x86、GPU、QNN 或 NPU。
