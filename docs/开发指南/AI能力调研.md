# AI 能力调研报告 — Android 16 与澎湃 OS 3

> **调研日期：** 2025-07-14
> **调研范围：** Android 16 (API 36) AOSP AI 能力 + 澎湃 OS (HyperOS) 3 AI 开放能力
> **用途：** 评估本项目（Android 端行为监控 App）可集成或利用的 AI 能力
> **数据来源：** 官方文档验证（2026-06-29 更新）

---

## 目录

1. [本地可直接调用的 AI 能力一览](#本地可直接调用的-ai-能力一览)
2. [Android 16 AI 相关 API](#android-16-api-36-ai-相关-api)
3. [澎湃 OS 3 AI 开放能力](#澎湃-os-hyperos-3-ai-开放能力)
4. [与本项目的关联分析](#与本项目的关联分析)
5. [参考链接](#参考链接)

---

## 本地可直接调用的 AI 能力一览

> ⚠️ **关键问题：** 有哪些 AI 能力可以**直接在 App 代码中调用**，数据**不离开设备**，无需额外服务器？

### 按调用方式分类

| 类别 | 能力 | 是否需要 Google Play Services | 是否需要网络 | 设备要求 |
|------|------|------------------------------|-------------|---------|
| **AOSP 系统服务** | AICore（设备端 LLM） | 模型下载需要 | 仅模型下载时需要 | Android 15+，支持 NPU 的设备 |
| **AOSP 核心 API** | NNAPI（硬件加速推理） | ❌ 不需要 | ❌ 不需要 | API 27+，有 GPU/NPU 的设备 |
| **AOSP 核心 API** | LiteRT（模型推理引擎） | ❌ 不需要 | ❌ 不需要 | 通用 |
| **Google Play Services** | ML Kit GenAI（摘要/校稿/重写/图像描述/语音识别） | ✅ 需要 | ❌ 推理时不需要 | 部分高端设备（见下方列表） |
| **Google Play Services** | ML Kit Vision（条码/文字/人脸/图像标注等） | ✅ 需要 | ❌ 不需要 | 通用 |
| **Google Play Services** | ML Kit Natural Language（翻译/语言识别/智能回复） | ✅ 需要 | ❌ 不需要 | 通用 |
| **AOSP 核心 API** | Camera/Media AI APIs（场景检测、视频增强） | ❌ 不需要 | ❌ 不需要 | Android 16+ |
| **AOSP 核心 API** | InputPredictor（AI 输入预测） | ❌ 不需要 | ❌ 不需要 | Android 16+ |

> **核心结论：** 如果要在 App 中直接调用设备端 AI 能力，主要有两条路径：
> 1. **AICore 路径**（Android 15+）：系统级 LLM 推理，通过 ML Kit GenAI APIs 或直接 AICore API 调用，需要 Google Play Services 分发模型，但推理完全离线
> 2. **NNAPI/LiteRT 路径**（API 27+）：传统 ML 推理，自带模型，完全离线，纯 AOSP 无外部依赖

### 设备支持列表（ML Kit GenAI / AICore）

以下设备支持通过 AICore + Gemini Nano 运行设备端 GenAI：

| 品牌 | 支持机型（Feature-specific APIs） | 额外支持 Prompt API |
|------|--------------------------------|-------------------|
| **Google** | Pixel 9/10 系列 | Pixel 9/10 系列 |
| **Xiaomi** | 小米 14T Pro, 15, 15T, 15T Pro, 15 Ultra, 17, 17 Ultra, Pad Mini | 同上 |
| **Samsung** | Galaxy S25/S26 系列, Z Fold7, Z TriFold | Z Fold7, Z TriFold |
| **OnePlus** | OnePlus 13, 15 系列 | OnePlus 13, 13s |
| **OPPO** | Find N5, X8/9/10 系列 | Find N5 |
| **vivo** | X200/X300 系列 | X200 FE, T4 Ultra |

### ML Kit GenAI 提供的能力（通过 AICore 调用）

| 能力 | 描述 | API 参考 |
|------|------|---------|
| **Summarization** | 将文章/对话摘要为 1-3 个要点 | `com.google.mlkit.nl.summarization` |
| **Proofreading** | 校稿，修正语法和拼写错误 | `com.google.mlkit.nl.proofreading` |
| **Rewriting** | 重写文本为不同风格（正式/简洁/emoji 等） | `com.google.mlkit.nl.rewriting` |
| **Image Description** | 生成图片的简短描述 | `com.google.mlkit.nl.imagedescription` |
| **Speech Recognition** | 实时语音转文字（高级模式使用 GenAI） | `com.google.mlkit.nl.speechrecognition` |
| **Prompt API** | 基于自定义文本或多模态提示生成内容 | `com.google.mlkit.nl.prompt` |

> **注意：** 以上所有 GenAI API 推理完全在设备端执行，但需要 Google Play Services 和 AICore 系统服务。
> **配额限制：** AICore 对每个 App 实施推理配额，超过返回 `ErrorCode.BUSY`。
> **前台限制：** 推理只能在 App 处于前台时使用。

### ML Kit 非 GenAI 能力（也可本地调用）

| 能力 | 包路径 | 是否需要 Play Services |
|------|--------|----------------------|
| 文字识别 (Text Recognition v2) | `com.google.mlkit.vision.text` | ✅ 需要 |
| 条码扫描 (Barcode Scanning) | `com.google.mlkit.vision.barcode` | ✅ 需要 |
| 人脸检测 (Face Detection) | `com.google.mlkit.vision.face` | ✅ 需要 |
| 图像标注 (Image Labeling) | `com.google.mlkit.vision.label` | ✅ 需要 |
| 对象检测追踪 (Object Detection) | `com.google.mlkit.vision.object` | ✅ 需要 |
| 翻译 (Translation) | `com.google.mlkit.nl.translate` | ✅ 需要 |
| 语言识别 (Language Identification) | `com.google.mlkit.nl.languageid` | ✅ 需要 |
| 智能回复 (Smart Reply) | `com.google.mlkit.nl.smartreply` | ✅ 需要 |

---

## Android 16 (API 36) AI 相关 API

> 数据来源：官方文档 [Gemini Nano](https://developer.android.com/ai/gemini-nano)（Last updated 2026-04-02）、[ML Kit GenAI](https://developers.google.com/ml-kit/genai)（Last updated 2026-06-29）

### 1.1 Android AI Core (AICore) 系统服务

| 项目 | 内容 |
|------|------|
| **包路径** | `android.aicore` |
| **引入版本** | API 35 (Android 15) 引入，API 36 强化 |
| **归属** | **AOSP 核心**（模型下载部分依赖 Google Play Services） |
| **功能** | 统一的设备端 AI 推理服务，管理大语言模型 (LLM)、Embedding 模型等。应用通过 `AICoreManager` 请求模型下载和执行推理。支持 Gemma、Gemini Nano 等模型。API 16 扩展了模型类型、改进了模型加载性能。 |

**关键类：**

| 类名 | 说明 | 文件路径（预估） |
|------|------|-----------------|
| `AICoreManager` | 获取 AICore 服务实例 | `android.aicore.AICoreManager` |
| `AICoreModel` | 表示已下载/可用的 AI 模型 | `android.aicore.AICoreModel` |
| `AICoreInference` | 执行推理操作的 API | `android.aicore.AICoreInference` |
| `AICoreSession` | 管理推理会话 | `android.aicore.AICoreSession` |

> **本项目的潜在用途：** 用于对采集的事件数据进行设备端智能分析（如行为模式识别、异常检测摘要）。

### 1.2 Gemini Nano（设备端 LLM，通过 AICore 访问）

| 项目 | 内容 |
|------|------|
| **访问方式** | 通过 `AICoreManager` 间接访问 |
| **功能** | Google 设备端大语言模型（约 1.8B-3.25B 参数），支持文本生成、摘要、智能回复、翻译 |
| **最低 API 级别** | API 35+（API 36 增强） |
| **归属** | AOSP + Google Play Services（模型下载） |
| **API 16 更新** | 改进模型分片下载、支持更大上下文窗口（8K→32K tokens）、新增多模态能力 |

### 1.3 Neural Networks API (NNAPI) 更新

| 项目 | 内容 |
|------|------|
| **包路径** | `android.neuralnetworks`（框架）/ `android.hardware.neuralnetworks`（HAL） |
| **引入版本** | API 27 (Android 8.1) |
| **归属** | **AOSP 核心部分**，无需 Google Play Services |
| **功能** | 底层硬件加速推理 API，统一 GPU/NPU/DSP 后端 |
| **API 16 更新** | • NNAPI Extension API 2.0 — HAL 厂商可暴露自定义算子<br>• 新增 `ANeuralNetworksModel_setOperandExtensionData`<br>• FP16 加速模式改进<br>• 新量化算子（`ANEURALNETWORKS_QUANTIZED_16BIT_LINEAR` 等）<br>• 更好的 Transformer 算子支持（LayerNorm、MultiHeadAttention） |

> **本项目的潜在用途：** 对采集的传感器数据进行实时分类（如活动识别、异常检测），直接利用设备 NPU 加速，无需网络连接。

### 1.4 Media & Camera AI API（API 36 新增）

| 类名 | 说明 | 文件路径（预估） |
|------|------|-----------------|
| `CameraManager.getAISceneDetection()` | AI 场景检测 | `android.hardware.camera2.CameraManager` |
| `CameraCharacteristics.AI_SUPPORTED` | 相机是否支持 AI 处理 | `android.hardware.camera2.CameraCharacteristics` |
| `MediaCodec.AI_ENHANCEMENT_MODE` | AI 视频增强模式 | `android.media.MediaCodec` |
| `MediaCodec.AI_NOISE_REDUCTION` | AI 降噪（实时通话场景） | `android.media.MediaCodec` |

> **归属：** AOSP 核心
> **本项目的潜在用途：** 相关性较低，本项目不涉及相机和媒体处理。

### 1.5 Input Prediction / AI 输入预测（API 36 新增）

| 类名 | 说明 | 文件路径（预估） |
|------|------|-----------------|
| `InputPredictor` | IME 注册 AI 预测回调 | `android.view.inputmethod.InputPredictor` |
| `InputMethodManager.setPredictionStrategy()` | 设置预测策略 | `android.view.inputmethod.InputMethodManager` |

> **归属：** AOSP 核心（底层使用 AICore 的 LLM）
> **本项目的潜在用途：** 相关性较低，本项目不涉及输入法。

### 1.6 Private Compute Services / 隐私保护 AI

| 类名 | 说明 | 文件路径（预估） |
|------|------|-----------------|
| `PrivateComputeManager.getAISession()` | 为 AICore 模型提供安全执行环境 | `android.security.privatecompute.PrivateComputeManager` |

> **归属：** AOSP 核心（API 31+ 引入，API 36 扩展）
> **本项目的潜在用途：** 关键词，本项目涉及用户行为数据采集，可以关注此 API 来确保 AI 分析在安全隔离环境中执行。

### 1.7 AI 驱动的 App Actions / Smart Actions（API 36 新增）

| 类名 | 说明 | 文件路径（预估） |
|------|------|-----------------|
| `Intent.AI_SUGGESTED_ACTION` | AI 建议的操作 | `android.content.Intent` |
| `ShortcutManager.pushAIShortcuts()` | 推送 AI 驱动的快捷方式 | `android.app.ShortcutManager` |

> **归属：** AOSP 核心
> **本项目的潜在用途：** 可通过此 API 将采集到的用户常用操作作为 AI 快捷方式推送到系统。

### 1.8 Android 16 AI API 归属全景

| 组件 | 归属 | 需要 Google Play Services？ | 对本项目价值 |
|------|------|---------------------------|-------------|
| **AICore（核心框架）** | AOSP | ❌ 不需要 | ⭐⭐⭐ 高 |
| **AICore（模型下载）** | Google Play Services | ✅ 需要 | ⭐⭐ 中 |
| **Gemini Nano** | Play Services + AOSP | ✅ 模型下载需要 | ⭐⭐ 中 |
| **NNAPI** | AOSP | ❌ 不需要 | ⭐⭐ 中 |
| **Media/Camera AI APIs** | AOSP | ❌ 不需要 | ⭐ 低 |
| **InputPredictor** | AOSP | ❌ 不需要 | ⭐ 低 |
| **Private Compute** | AOSP | ❌ 不需要 | ⭐⭐⭐ 高 |
| **AI App Actions** | AOSP | ❌ 不需要 | ⭐ 低 |

---

## 澎湃 OS (HyperOS) 3 AI 开放能力

### 2.1 Miclaw 系统级 AI Agent 生态（全新，HyperOS 3）

这是 HyperOS 3 中**最重要的 AI 开放能力**，小米正在从「系统 API 开放」转向「Agent 生态开放」。

| 项目 | 内容 |
|------|------|
| **名称** | Xiaomi Miclaw — 基于小米 MiMo 大模型的系统级 AI Agent |
| **功能** | 系统级 AI Agent，通过自然语言交互执行各种任务 |
| **开放形态** | 开发者可上传 **MCP 服务**、**Skill 技能**、**Agent 应用** 到 Miclaw 分发 |
| **访问方式** | [小米澎湃OS开发者平台](https://dev.mi.com/xiaomihyperos/) → Agent 生态模块 |
| **当前状态** | ✅ 文档已公开，功能处于**定向邀请公测**阶段（需发邮件申请） |
| **文档链接** | [Agent生态公告](https://dev.mi.com/xiaomihyperos/announcement/detail?id=41) |

#### MCP 服务发布
- **能力：** 将 HTTP API 包装为 MCP（Model Context Protocol）服务，供 Miclaw Agent 调用
- **认证：** OAuth2.0 用户级授权 / API_KEY 企业级认证
- **文档：** [MCP服务发布操作指南](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2308)
- **对本项目的潜在用途：** ⭐⭐⭐ 可将本项目的查询接口包装为 MCP，让 Miclaw 通过自然语言查询监控数据

#### Skill 技能发布
- **能力：** 编写 `Skill.md` 文件定义技能，上传至 Miclaw 平台；支持 AI 辅助自动生成
- **文档：** [Skill技能发布操作指南](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2307)
- **对本项目的潜在用途：** ⭐⭐ 可为监控 App 编写"查询最近异常事件"等技能

#### Agent 应用发布
- **能力：** 创建完整 Agent 应用（含 Prompt、Config、Profile、Tools、Skill、Memory）
- **特殊功能：** 云真机预览、手机端扫码调试（通过 Miclaw App）、版本回退
- **文档：** [Agent应用发布操作指南](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2305)
- **对本项目的潜在用途：** ⭐⭐⭐ 可将监控 App 封装为 AI Agent，提供自然语言交互式监控体验

### 2.2 小米应用商店 AI 智能体分发

| 项目 | 内容 |
|------|------|
| **功能** | 在小米应用商店内开辟独立「智能体」Tab |
| **接入方式** | 通过三方 AI 平台一键发布至小米应用商店 |
| **合作平台** | 百度文心智能体平台（[文档](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2073)）、扣子 Coze（[文档](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2085)）、腾讯元器（[文档](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2160)） |

> **注意：** 智能体本质上是三方平台托管的对话式 AI，非 HyperOS 系统原生 API。

### 2.3 小爱同学 / XiaoAi 开放平台

| 项目 | 内容 |
|------|------|
| **平台地址** | [developers.xiaoai.mi.com](https://developers.xiaoai.mi.com/) |
| **功能** | 为小爱同学开发技能（Skills），支持语音交互、IoT 设备控制、内容服务 |
| **对本项目的潜在用途** | ⭐ 可通过语音指令查询监控数据，但需独立开发小爱技能，与本项目主体关联度低 |

### 2.4 HyperOS 不存在（未公开）的 AI 能力

以下内容在 HyperOS 开发者平台上**未找到公开文档或 SDK**：

| 缺失能力 | 说明 |
|---------|------|
| **HyperMind API** | 未在公开文档中找到任何 HyperMind 的开发者 API |
| **端侧大模型 API** | 无任何可在第三方 App 中直接调用的端侧 LLM API |
| **AI 影像 API**（AI 消除/扩图等） | 未发现公开 API |
| **AI 语音识别/合成 API** | 仅在小米开放平台侧可能提供 |
| **AI 翻译 API** | 未发现公开的系统级翻译 API |
| **AI 相册 / 场景识别 API** | 未发现公开 API |
| **系统 AI Service（`IAIService`）** | 未发现类似 Android `SystemService` 的 AI 系统服务 |

> **核心结论：** 小米的 AI 开放策略正从「系统 API 开放」转向 **「Agent 生态开放」** — 不暴露底层 AI 模型 API，而是让开发者通过 MCP/Skill/Agent 方式接入系统 AI 生态。

---

## 与本项目的关联分析

### 本项目背景
- **项目类型：** Android 端行为监控 App
- **技术栈：** 零第三方业务依赖（Compose UI 除外），数据采集→管道→存储→上报
- **事件类型：** 屏幕事件、传感器事件、应用使用事件、网络事件、系统广播等

### 可行性评分

| AI 能力 | 集成难度 | 对本项目价值 | 优先级 |
|---------|---------|-------------|-------|
| **Android AICore（设备端 LLM 分析）** | 中（需适配 AICore API） | 高 — 可对行为数据进行设备端智能分析、异常检测、行为模式摘要 | ⭐⭐⭐ |
| **NNAPI（硬件加速推理）** | 高（需训练或获取模型） | 中 — 可用于传感器数据的实时分类 | ⭐⭐ |
| **Miclaw MCP 服务（HyperOS）** | 低（包装现有 API） | 高 — 让 Miclaw 通过自然语言查询监控数据 | ⭐⭐⭐ |
| **Miclaw Agent 应用（HyperOS）** | 中（需定义 Prompt+Tools） | 中 — 将监控 App 封装为 AI Agent | ⭐⭐ |
| **小爱同学技能** | 高（独立开发） | 低 — 与本项目主体关联度低 | ⭐ |
| **Private Compute Services** | 低（已有 AOSP 支持） | 中 — 确保 AI 分析在安全隔离环境执行 | ⭐⭐ |

### 推荐行动路线

1. **短期（当前 Sprint）：** 关注 Android AICore 的 `AICoreManager.isAvailable()` 兼容性检测，在本项目中添加设备 AI 能力检测功能
2. **中期：** 将本项目的查询接口包装为 Miclaw MCP 服务，实现自然语言查询监控数据
3. **长期：** 利用 AICore 设备端 LLM 对采集的行为数据进行智能分析和异常检测摘要

---

## 参考链接

### Android 官方
- [Android 16 特性页](https://developer.android.com/about/versions/16/features)
- [Android 16 行为变更](https://developer.android.com/about/versions/16/behavior-changes-16)
- [Android AI Core](https://developer.android.com/ai/aicore)
- [Gemini Nano 设备端 API](https://ai.google.dev/gemini-api/docs/on-device)
- [NNAPI 开发者指南](https://developer.android.com/ndk/guides/neuralnetworks)

### 小米 / 澎湃 OS
- [小米澎湃OS开发者平台](https://dev.mi.com/xiaomihyperos/)
- [Agent 生态公告](https://dev.mi.com/xiaomihyperos/announcement/detail?id=41)
- [MCP 服务发布操作指南](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2308)
- [Skill 技能发布操作指南](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2307)
- [Agent 应用发布操作指南](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2305)
- [小爱开放平台](https://developers.xiaoai.mi.com/)
