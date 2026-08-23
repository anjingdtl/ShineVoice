# Phase 1 PDCA

## Plan

以官方 sherpa-onnx v1.13.6 Kotlin JNI API 接入 ZipVoice-Distill INT8；用真实 `reference.wav + referenceText + 中文 targetText` 在 Android CPU 上生成并保存 WAV，完成断网、重启、生命周期与连续 20 次稳定性验收。

## Do

使用 `OfflineTtsConfig.zipvoice` 配置 encoder/decoder/vocoder/dataDir/lexicon/tokens，通过 `GenerationConfig` 传入参考音频样本、采样率、参考文本和 4 steps。单一 Native Runtime 复用，TtsManager 串行化任务，Room 保存每次结果。

## Check

交付前必须填入实际证据：

- [ ] 真实 Android 生成成功并输出 WAV
- [ ] 播放成功
- [ ] SAF 保存成功
- [ ] 断网生成成功
- [ ] APP 重启后生成成功
- [ ] 前后台/Activity 重建无 Crash，播放资源释放
- [ ] 连续 20 次成功率 100%，无 Crash/ANR/Native 错误
- [ ] 记录平均耗时、最大耗时、RTF、内存变化
- [ ] Logcat 和 crash buffer 清洁

## Act

只修复已确认的 Native 配置、ABI、路径、音频文件、生命周期、内存和稳定性问题；不重写第三方库、不进入后续 Provider。

