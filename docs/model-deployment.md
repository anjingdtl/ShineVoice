# ZipVoice-Distill INT8 模型部署

ShineVoice 使用官方 sherpa-onnx 发布版 `v1.13.6` AAR 和官方 `tts-models` 中的 `sherpa-onnx-zipvoice-distill-int8-zh-en-emilia`。AAR 与模型均不提交 Git。

## 下载与解包

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
