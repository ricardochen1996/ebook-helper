# 读问 AI（boox-ai-helper）

在文石 BOOX Leaf3 上给**微信读书**用的悬浮球 AI 伴读 App：点悬浮球读取当前页文字 → 你提问 → AI 流式回答并用语音朗读 → 会话按书自动归档，可在 App 里回看。

- 设备：BOOX Leaf3（Android 11 / API 30），也适用于同代文石设备
- 阅读 App：微信读书墨水屏版 `com.tencent.weread.eink`
- 自带 Key（BYOK）：大模型 API Key 只存在本机；**语音识别复用文石系统服务，不需要 ASR Key**
- 无后端、无统计；系统语音识别使用设备上的文石账号/配额

## 已经真机验证的关键点

- 微信读书当前页正文直接暴露在无障碍树的 `content-desc` 中（`book_page` 子树），**不需要截图 / OCR / root**。
- 目录面板顶部的 TextView 是书名，打开目录时可自动缓存；阅读页本身不显示书名。
- 系统有内置麦克风、扬声器；本机 Leaf3 固件没有文石自有 TTS 引擎，系统里唯一的 `android.intent.action.TTS_SERVICE` 是预装的 **Google TTS**（`SpeechServicesByGoogle`）。App 默认使用系统 TTS，初始化失败时才显式回退 Google TTS。Android 没有标准 `RecognitionService`，但文石 ksync 提供了 **`KCommonSpeechService`**（exported、无 `android:permission` 校验），App 已通过 AIDL 复用：本机实测自动选中 **阿里云 ASR**，识别出完整句子。

## 功能（当前版本）

- 悬浮球：可拖动、贴边、点击抓取当前页并打开问答面板
- 取词：无障碍 content-desc，本地毫秒级
- 问答：OpenAI 兼容 / Anthropic 流式 SSE；DeepSeek、通义千问、Kimi、智谱、OpenAI、Claude 预设 + 自定义
- 语音提问：面板底部「按住说话」，复用文石系统 ASR（本机为阿里云引擎），识别结果自动填入提问框
- 语音回答：系统默认 TTS（本机为预装 Google TTS，中文），按句流式朗读；可调语速
- 归档：Room 本地数据库，书籍 → 会话 → 消息；同一本书的会话自动聚合
- 安全：API Key 用 Android Keystore AES-GCM 加密，`allowBackup=false`，日志不打印 Key
- 墨水屏 UI：白底黑字、无动画、大按钮、流式文本节流

## 构建

需要：

- JDK 17
- Android SDK：`platforms;android-34`、`build-tools;34.0.0`、`platform-tools`
- Gradle Wrapper 会自行下载 Gradle 8.10.2

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew :app:assembleDebug
```

APK：`app/build/outputs/apk/debug/app-debug.apk`

### 正式签名 release

项目根目录已生成个人签名用的 `keystore/readask-release.jks` 与 `keystore.properties`（密码在其中，请自行保管；丢失后将无法覆盖升级）。

> 签名文件（`keystore/`、`keystore.properties`）与 `local.properties` 已在 `.gitignore` 中排除，不会进入 Git 仓库；请在本机单独备份。

```bash
./gradlew :app:assembleRelease
```

APK：`app/build/outputs/apk/release/app-release.apk`（不包含 debug 专用的 DebugConfigActivity）。

## 安装到 Leaf3

```bash
# 若之前装过不同签名的 debug 包，先卸载
adb uninstall com.booxai.helper
adb install -r app/build/outputs/apk/release/app-release.apk
```

然后在设备上：

1. 打开「读问 AI」→ 授予「显示在其他应用上层」
2. 系统设置 → 无障碍 → 开启「读问 AI 阅读助手」
3. 在 App 设置里填写 Base URL、模型、API Key，点「测试连接」
4. 回到 App 点「启动悬浮球」
5. 第一次按住说话时授予麦克风权限；也可以先用首页「打开面板测试语音」测试（不需要微信读书、不需要 API Key）

### 重要：文石系统限制

- **应用冻结**：文石系统可能把第三方 App 自动冻结/禁用（日志里的 EAC auto-freeze）。如果 App 打不开或悬浮球消失，请在系统设置 → 应用管理里找到「读问 AI」，关闭「自动冻结/应用优化」，允许后台运行；必要时执行 `adb shell pm enable com.booxai.helper`。
- **无障碍授权会被撤销**：每次覆盖安装/更新 APK 后，Android 会撤销无障碍服务，需要重新在系统设置里开启。
- **系统代理**：设备如果配置了 Wi-Fi 代理（例如 Mac 上的 Clash `192.168.1.86:7893`），App 会遵循系统代理。代理没运行时，API 请求会提示「无法连接服务器，请检查网络/代理」。请确保代理正在运行，或在系统 Wi-Fi 设置里关闭代理。
- **后台保活**：建议关闭应用冻结、允许自启动；悬浮球是前台服务，正常情况下有常驻通知。

## 已验证（2026-11-08 ~ 11-09，Leaf3 真机）

- 微信读书当前页正文可通过无障碍 `content-desc` 完整读取（实测《证券分析（全新升级版）》整页）。
- 悬浮球显示/点击，面板打开并显示当前页文字。
- OpenAI 兼容 SSE 流式回答（本地 mock）可正常逐段显示，状态显示「完成 · 2.3s · mock」。
- Google TTS 中文朗读可用（AudioTrack 正常播放）。
- Room 会话归档：App 首页能看到书籍和会话，重启后历史仍在。
- 书名目录识别逻辑已修正（只接受目录面板的真书名，过滤“下拉添加书签”等提示）；建议首次使用时打开一次微信读书目录确认。
- **系统 ASR 复用成功**：App 绑定 `KCommonSpeechService` → 服务自动选择 `aliyunNLP` → 实时返回「请解释一下折旧率是什么意思。」，面板状态「识别完成」。全程不需要任何 ASR Key。

## 常见问题

**Q：无障碍服务显示“已授权但未连接”？**
A：通常是文石系统把 App 自动冻结/禁用了。到系统设置 → 应用管理找到「读问 AI」，关闭自动冻结/应用优化（或加入白名单），然后到无障碍设置里把「读问 AI 阅读助手」关闭再开启一次。必要时执行 `adb shell pm enable com.booxai.helper`。

**Q：悬浮球突然不见了？**
A：同上，先检查 App 是否被系统冻结；悬浮球是前台服务，正常情况下通知栏有「读问 AI 正在运行」。

**Q：提问提示“无法连接服务器，请检查网络/代理”？**
A：设备如果设置了 Wi-Fi 代理（例如 Mac 上的 Clash `192.168.1.86:7893`），App 会走系统代理。请确认代理正在运行；或在系统 Wi-Fi 设置里关闭代理后再试。也可以先用「设置 → 测试连接」定位问题。

**Q：更新 APK 后无障碍失效？**
A：Android 会在覆盖安装后撤销无障碍授权，重新到系统设置里开启即可。

**Q：按住说话没反应 / 提示“系统语音额度已用完”？**
A：系统 ASR 走文石账号与语音配额；确认已登录文石账号、额度未用完、网络/代理正常。失败时面板会提示，也可以点输入框用键盘上的麦克风听写。

**Q：为什么悬浮球在微信读书里会消失？**
A：文石 EAC 自动冻结可能连前台服务一起冻结。必须给「读问 AI」关闭自动冻结/应用优化，并允许后台运行；否则悬浮球和无障碍都会被停掉。可先用首页「打开面板测试语音」验证 App 本身和系统 ASR 是否正常。

## 签名

Release APK 使用项目根目录的 `keystore/readask-release.jks` 签名（密码在 `keystore.properties`）。证书 SHA-256：

```
ac089f798ac9dc3ece7b4d1883f5a450f7da2b5836bbcca9190d165261033092
```

请保管好 keystore；后续升级必须使用同一签名，否则需要先卸载旧版。

## API 配置示例

| 服务商 | 协议 | Base URL | 模型示例 |
|---|---|---|---|
| DeepSeek | OpenAI 兼容 | `https://api.deepseek.com` | `deepseek-chat` |
| 通义千问 | OpenAI 兼容 | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen-plus` |
| Kimi | OpenAI 兼容 | `https://api.moonshot.cn/v1` | `moonshot-v1-8k` |
| 智谱 GLM | OpenAI 兼容 | `https://open.bigmodel.cn/api/paas/v4` | `glm-4-flash` |
| OpenAI | OpenAI 兼容 | `https://api.openai.com/v1` | `gpt-4o-mini` |
| Anthropic | Anthropic | `https://api.anthropic.com` | `claude-sonnet-5-5` |
| 本地 mock / 中转站 | OpenAI 兼容 | 自定义 | 自定义 |

Base URL 填到 `/chat/completions` 之前的前缀。App 会自己拼接 `/chat/completions`（Anthropic 拼 `/v1/messages`）。

## 手机扫码配置 API（推荐）

电子书上输入 Base URL / API Key 不方便，App 支持局域网扫码配置：

1. 电子书和手机连接同一个 Wi-Fi。
2. 电子书首页点「扫码配置」，或「设置 → 手机扫码配置 API」。
3. 电子书显示二维码；用手机相机/微信扫码。
4. 手机页面填写协议、Base URL、模型、API Key，点「保存到电子书」。
5. 电子书自动保存并返回，首页「AI 接口」会变成已配置。

说明：

- 配置页只在等待期间运行（最长 5 分钟），使用一次性随机 token，保存成功或超时后立即关闭。
- API Key 只在手机 → 电子书的局域网请求中传输，不经过第三方服务器；请只在可信 Wi-Fi 下使用。
- 仍然可以在「设置」里手动修改；扫码配置会覆盖 Base URL / 模型 / Key，协议也会一起更新。

## 使用

1. 在微信读书里打开一本书，正常阅读。
2. 点右侧悬浮球「AI」。
3. 面板显示当前书页；书名未知时点「改」填写，或先打开一次微信读书目录让 App 自动识别。
4. 输入问题，点「提问」。回答会流式出现并自动朗读。
5. 语音提问：按住底部「按住说话」，说完松开；识别结果会填入输入框，确认或修改后点「提问」。
6. 点「历史」查看同一本书的会话；App 首页按书归档。

## 系统语音识别（复用文石，不需要 ASR Key）

- App 绑定文石 ksync 的 `com.onyx.android.ksync/.service.KCommonSpeechService`（manifest 里 `exported=true`，没有声明 `android:permission`），通过 `ISpeechService` AIDL：
  - `start(configJson, callback)` 启动识别
  - `appendSpeechBuf(byte[])` 推 16kHz mono PCM16
  - `stop(3)` / `cancel()` 结束
- 服务端用**设备上的文石账号**加载当前激活的云端语音配置并检查配额；本机实测 active engine 是 **aliyunNLP（阿里云 ASR）**，不需要用户填 ASR Key。
- 这是一条未公开的 OEM 接口，固件升级可能失效；失败时面板会提示，可改用键盘麦克风听写（点输入框 → 键盘上的麦克风）。
- 会消耗设备文石账号的语音额度；额度/登录/网络错误会以「系统语音额度已用完 / 未登录文石账号 / 网络连接失败」提示。
- 音频只在你按住按钮时录制，由系统 ksync 服务发往其云端 ASR，不经过本 App 的服务器（本 App 没有服务器）。

## 书名归档说明

- 微信读书阅读页不显示书名，书名只在目录面板顶部。
- App 在无障碍事件里被动读取目录标题；你打开一次目录后，本书书名会被缓存。
- 也可以随时在面板点「改」手动填写/修改书名；改成一个已存在的书名会合并会话。

## 本地开发调试

`tools/mock_llm_server.py` 是一个不依赖第三方库的 mock 服务，支持 OpenAI 兼容 SSE 与 Anthropic SSE：

```bash
python3 tools/mock_llm_server.py 8765
adb reverse tcp:8765 tcp:8765
# App 设置：Base URL=http://127.0.0.1:8765/v1，Key=test，模型=mock
```

### GitHub Actions debug 构建

推送到 `main` 会自动运行 `.github/workflows/android-debug.yml`：

- Actions artifact：`reader-helper-debug`
- 同时把 APK 推到 `build-artifacts` 分支，方便本机直接取：
  ```bash
  git fetch origin build-artifacts
  git show FETCH_HEAD:reader-helper-debug.apk > /tmp/reader-helper-debug.apk
  adb install -r /tmp/reader-helper-debug.apk
  ```

debug 包的 applicationId 是 `com.booxai.helper.debug`，可以和正式包同时安装，不会覆盖正式包的会话数据。

真机联调（debug 包）：

```bash
# 悬浮窗权限
adb shell appops set com.booxai.helper.debug SYSTEM_ALERT_WINDOW allow

# 调试期间只启用 debug 的无障碍服务（会临时替换正式包的服务）
adb shell settings put secure enabled_accessibility_services \
  com.booxai.helper.debug/com.booxai.helper.accessibility.WeReadAccessibilityService
adb shell settings put secure accessibility_enabled 1

# 配 mock LLM（配合上面的 adb reverse）
adb shell am start -n com.booxai.helper.debug/com.booxai.helper.DebugConfigActivity \
  --es base_url http://127.0.0.1:8765/v1 --es model mock --es api_key test --es protocol openai

# 抓取当前微信读书页面，日志里看 len / byId / preview
adb logcat -s ReadAskDebug
adb shell am start -n com.booxai.helper.debug/com.booxai.helper.DebugConfigActivity --ez debug_capture true
```

调试完把正式包的无障碍服务恢复：

```bash
adb shell settings put secure enabled_accessibility_services \
  com.booxai.helper/com.booxai.helper.accessibility.WeReadAccessibilityService
```

### GitHub Actions release 构建

正式包需要把 release keystore 配成仓库 Secrets。**GitHub Secrets 不是 GitHub 提供的，
是你自己写入的加密变量**：

仓库 → Settings → Secrets and variables → Actions → New repository secret

需要 4 个：

| Secret 名称 | 值 |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `base64 < keystore/readask-release.jks` 的单行结果 |
| `ANDROID_KEYSTORE_PASSWORD` | `keystore.properties` 里的 `storePassword` |
| `ANDROID_KEY_ALIAS` | `keystore.properties` 里的 `keyAlias` |
| `ANDROID_KEY_PASSWORD` | `keystore.properties` 里的 `keyPassword` |

本机已有 keystore + properties 时，一条命令写入（值不会打印）：

```bash
tools/setup_release_secrets.sh \
  /path/to/readask-release.jks \
  /path/to/keystore.properties
```

然后到 GitHub → Actions → **Android Release Build** → Run workflow，或：

```bash
gh workflow run android-release.yml --repo ricardochen1996/reader-helper
```

产物：

- Actions artifact：`reader-helper-release`
- `release-artifacts` 分支：`reader-helper-release.apk`

注意：正式包 keystore 必须和已安装正式包一致（证书 SHA-256 见下方「签名」）。
如果 keystore 丢失，新建签名后必须卸载旧包再安装，会话历史会丢失。

## 隐私

- 书页文字只在你点「提问」时发送到你配置的 AI 接口。
- 按住说话时会录音；音频交给文石系统 ksync 语音服务（本机为阿里云引擎）转文字；App 不保存录音。
- App 不申请存储权限、不截屏、不读取微信读书以外的 App（无障碍服务限定 `com.tencent.weread.eink`）。
- API Key 用 Android Keystore 加密；关闭系统备份。
- 请遵守微信读书服务条款，仅用于个人阅读辅助。

## 已知限制

- 微信读书升级可能改动无障碍结构；届时需要适配或加 OCR 兜底。
- 阅读页右下角页码不在无障碍树里，当前版本不记录精确页码。
- 语音提问复用文石非公开 AIDL；固件升级可能失效，依赖文石账号/语音配额；失败时用键盘麦克风听写。
- 自动识别书名依赖目录面板；未打开目录时需手动确认一次。
