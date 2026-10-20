# AI 能力调研 — Android 16 与澎湃 OS 3

> 目的：评估本项目可集成 / 利用的端侧 AI 能力。数据来源为官方文档（截至 2026-06-29），未做真机实测。

## 结论

端侧 AI 有两条可用路径：
1. **AICore 路径**（Android 15+）：系统级 LLM 推理，经 ML Kit GenAI 或 AICore API 调用；模型分发依赖 Google Play Services，**推理完全离线**。有配额（超限 `ErrorCode.BUSY`）且**只能前台推理**。
2. **NNAPI / LiteRT 路径**（API 27+）：自带模型的传统 ML 推理，完全离线、纯 AOSP。

## 能力清单

| 类别 | 能力 | 需 Play Services | 需网络 | 设备要求 |
|---|---|---|---|---|
| AOSP 系统服务 | AICore（设备端 LLM） | 模型下载需要 | 仅模型下载 | Android 15+、有 NPU |
| AOSP 核心 API | NNAPI（硬件加速推理） | 不需要 | 不需要 | API 27+ |
| AOSP 核心 API | LiteRT（推理引擎） | 不需要 | 不需要 | 通用 |
| AOSP 核心 API | Camera/Media AI、InputPredictor | 不需要 | 不需要 | Android 16+ |
| Play Services | ML Kit GenAI（摘要/校稿/重写/图像描述/语音） | 需要 | 不需要 | 部分高端设备 |
| Play Services | ML Kit Vision / Natural Language | 需要 | 不需要 | 通用 |

ML Kit GenAI 包：`com.google.mlkit.nl.{summarization, proofreading, rewriting, imagedescription, speechrecognition, prompt}`。ML Kit 非 GenAI：`com.google.mlkit.vision.{text, barcode, face, label, object}`、`com.google.mlkit.nl.{translate, languageid, smartreply}`（均需 Play Services）。

支持 AICore + Gemini Nano 的机型：Pixel 9/10 系列；小米 14T Pro / 15 / 15T / 15T Pro / 15 Ultra / 17 / 17 Ultra / Pad Mini；Galaxy S25/S26、Z Fold7、Z TriFold；OnePlus 13 / 15；OPPO Find N5、X8/9/10；vivo X200/X300。

## Android 16 API 要点

- **AICore**（`android.aicore`，API 35 引入、36 强化）：`AICoreManager` / `AICoreModel` / `AICoreInference` / `AICoreSession`；模型下载依赖 Play Services。
- **Gemini Nano**：约 1.8B~3.25B 参数，API 36 支持 8K→32K token 上下文与多模态。
- **NNAPI 更新**：Extension API 2.0（厂商自定义算子）、`ANeuralNetworksModel_setOperandExtensionData`、FP16 改进、新量化算子、Transformer 算子（LayerNorm / MultiHeadAttention）。
- **Camera/Media AI**：`CameraManager.getAISceneDetection()`、`MediaCodec.AI_ENHANCEMENT_MODE`（与本项目不相关）。
- **InputPredictor**：`android.view.inputmethod.InputPredictor`（与本项目不相关）。
- **Private Compute**：`PrivateComputeManager.getAISession()` 提供隔离执行环境（对行为数据采集有参考价值）。
- **AI App Actions**：`Intent.AI_SUGGESTED_ACTION`、`ShortcutManager.pushAIShortcuts()`。

归属与价值：AICore 核心（AOSP，高）、Private Compute（AOSP，高）、Gemini Nano / NNAPI（中）、Media/Camera、InputPredictor、AI App Actions（低）。

## HyperOS 3

**核心变化：小米从「系统 API 开放」转向「Agent 生态开放」** —— 不暴露底层模型 API，而是通过 MCP / Skill / Agent 接入。

- **Miclaw**（Xiaomi MiMo 大模型驱动的系统级 Agent，定向邀请公测）：可发布 ①MCP 服务（HTTP API 包装成 MCP，OAuth2 用户级 / API_KEY 企业级认证）②Skill 技能（`Skill.md`）③Agent 应用（Prompt/Config/Profile/Tools/Skill/Memory，含云真机预览、扫码调试、版本回退）。平台：<https://dev.mi.com/xiaomihyperos/>。
- **应用商店智能体分发**：经百度文心 / 扣子 Coze / 腾讯元器一键发布（三方托管对话式 AI，非系统原生 API）。
- **小爱同学开放平台**：`developers.xiaoai.mi.com`（需独立开发技能，与本项目关联度低）。
- **未公开**：HyperMind API、端侧 LLM API、AI 影像 / 语音 / 翻译 / 相册 API、`IAIService` 类系统服务。

## 与本项目的关联

| 能力 | 集成难度 | 价值 | 优先级 |
|---|---|---|---|
| Android AICore（设备端行为分析 / 异常检测摘要） | 中 | 高 | ⭐⭐⭐ |
| Miclaw MCP 服务（包装查询接口，自然语言查监控数据） | 低 | 高 | ⭐⭐⭐ |
| Private Compute Services（隔离执行） | 低 | 中 | ⭐⭐ |
| NNAPI（传感器实时分类） | 高（需模型） | 中 | ⭐⭐ |
| Miclaw Agent 应用（封装为 AI Agent） | 中 | 中 | ⭐⭐ |
| 小爱同学技能 | 高 | 低 | ⭐ |

建议路线：短期先做 `AICoreManager.isAvailable()` 兼容性检测与设备 AI 能力探测；中期把查询接口包装为 Miclaw MCP；长期再用 AICore 做本地行为分析摘要。

## 参考

[AICore](https://developer.android.com/ai/aicore) · [Gemini Nano 端侧 API](https://ai.google.dev/gemini-api/docs/on-device) · [NNAPI](https://developer.android.com/ndk/guides/neuralnetworks) · [ML Kit GenAI](https://developers.google.com/ml-kit/genai) · [澎湃 OS 开发者平台](https://dev.mi.com/xiaomihyperos/) · [Agent 生态公告](https://dev.mi.com/xiaomihyperos/announcement/detail?id=41)
