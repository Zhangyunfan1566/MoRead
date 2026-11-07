# LCS 改造线说明（MoRead）

这个仓库是 `ovo066/MoRead`（墨知：原生 Android 本地小说阅读器 + AI 伴读，**GPL-3.0**）的
**公开 fork**，用于 LCS 改造线的开发。上游作者仓库只读，本仓库只承载改造内容。

## 仓库与远端

| 远端 | 地址 | 用途 |
|---|---|---|
| `origin` / `pub` | `https://github.com/Zhangyunfan1566/MoRead.git` | **本仓库**（公开 fork，直接在这里改） |
| `upstream` | `https://github.com/ovo066/MoRead.git` | 作者仓库，只读拉取 |
| `local-mirror` | `/home/lzk/workspace/DSH/moread-upstream-mirror.git` | 作者仓库的本地裸镜像（离线保险） |
| `lcs-private` | `https://github.com/Zhangyunfan1566/moread-lcs.git` | **历史私有库**（八轮改造的原始落点，已并入本仓库，仅存档） |

## 分支

| 分支 | 基线 | 内容 |
|---|---|---|
| `main` | 上游 `fb3e3e0`（v1.5.0） | **跟随上游**，不落任何改造 |
| `lcs/v2-on-1.5.0` | 上游 `fb3e3e0` | **LCS 改造线主分支**：八轮改造在 1.5.0 基线上重建 |

## 同步上游

```bash
git fetch upstream
git switch lcs/v2-on-1.5.0
git merge upstream/main          # 上游发版后重放改造
git push origin lcs/v2-on-1.5.0
```

pull request 请提到 `lcs/v2-on-1.5.0`，不要提到 `main`（`main` 只做上游镜像）。

## 改造内容总览

### 一、生图链路重写（替换原有两次请求 + 自然语言拼接）

原链路先让模型挑人物（且先试 `CHEAP` 角色，吃不到全局预设/破限串），再由代码拼一段
`Style: … / Character X: … / Scene: … / Avoid: …` 塞给 NAI —— NAI 收到的是非标准自然语言串，
且完全没有「角色档案维护」协议。

重写为**一次请求产出完整配方**（参照 SillyTavern 扩展 ST-BaiBai-Image 的做法）：

- **`ai/media/ImageSceneArchitect.kt`** —— 消息顺序固定：**破限 → 后端规范 → 思考清单 → 任务协议 → 正文**；
  破限/规范/思考清单均可在设置里覆盖（`ImageApiSettings.jailbreakPrompt` 等，留空即用内置）。
  - 内置破限含**成人内容条款**（照实写画面、不因露骨删减；**未成年人边界**；真实公众人物边界；只输出协议 JSON）
  - 内置 NAI 规范讲 **Base 块与角色块的分工**（Base 只放人数/景别/场景/光线/共享关系；
    每角色外貌·服装·动作·表情·视线进 `characters[]`），含显式场景条款与多人绑定规则
  - 输出协议固定 JSON：`{thinking, images[{tag, nl, characters[], negative, size}], changes[]}`
  - 解析器：剥 `<thinking>` → 取第一个平衡 JSON（容忍围栏/前后说明/字符串内花括号）→ 校验清洗
- **`ai/media/ImageTagNormalizer.kt`** —— NAI tag 归一化（去中文 tag、剔通用质量负面词、按上限截断）
- **外貌库闭环**：`changes` 写回 `LookSpec`（`field:"new"` 解析 `hair=…; eyes=…`；
  永久变化推进 `sinceChapter`，变化之前的画面仍取旧档案）
- **NAI 原生结构**：`ImageRecipe` 增加 `baseTag` / `characterTags` / `negativeExtra`；
  `RecipeAssembler` 优先用规划器的 Base 块与角色块，不再拼自然语言
- **NAI 参数可配**：`ucPreset`（Heavy/Light/None/Human Focus）与 `qualityToggle` 从硬编码改为设置项
- **选区出图不再自拼中文提示词**：`ReaderSelectionMediaViewModel` 把「选段 + 附近语境 + 章节信息」原样交给规划器
- **不再是"静默降级"**：规划失败显式报错（书籍封面保留旧兜底路径）

### 二、SillyTavern（酒馆）预设体系移植

- **`ai/prompt/SillyTavernPresetImporter.kt`** —— 解析预设 JSON 的 `prompts[]`，跳过 marker 条目并列出原因；
  位置近似映射（absolute+system → `AFTER_SYSTEM`，absolute+user/assistant → `BEFORE_LAST_USER`，
  relative+`depth≤0` → `AFTER_LAST_USER`，relative+`depth≥1` → `BEFORE_LAST_USER`）；
  启用状态优先取 `prompt_order`；单条超过 12,000 字符**按空行/句末切段**不丢弃；支持 `overridePosition`
  （把整份预设统一压到指定位置）
- **`ai/prompt/TavernMacros.kt`** —— 宏引擎与**条件块**（`{{#if}}` / `{{else}}` / `{{/if}}`、
  `{{random::a::b}}`、`{{pick::…}}`、变量读写、`{{setvar}}`/`{{getvar}}` 等）
- **`ai/prompt/GlobalPromptInjector.kt`** —— `role` / `depth` / `order` 三要素注入
- **`ai/prompt/TavernRegexEngine.kt`** —— 酒馆正则脚本（scoped / placement / 前后处理三段）
- **`ai/prompt/TavernWorldInfo.kt`** + `TavernWorldInfoImporter.kt` —— 世界书（Lorebook）引擎与导入，
  关键词触发、常驻、递归扫描、按位置注入
- **`ai/prompt/TavernContextBudget.kt`** —— 历史窗口按长度裁剪（不再按条数硬切）
- **`ai/client/GlobalPresetChatApiClient.kt`** —— CHAT 请求装饰器：正则 → 注入 → 世界书，顺序固定
- **采样参数落到请求**：预设顶层 `temperature` / `top_p` / `top_k` / `rep_pen` 等进入实际请求体
- **四个新仓库**：`core/datastore/Tavern{Variable,Regex,WorldInfo,RequestSettings}Store.kt`
- **UI**：`feature/settings/GlobalPresetSettingsScreen.kt` 与 `...ViewModel.kt` 里新增导入/预览/世界书开关/采样/裁剪设置

### 三、Agent 循环

- **`ai/agent/ToolCallDeduper.kt`** —— 同一轮内 (工具, 参数) 组合去重，治「原地打转」
- **`ai/agent/AgentLoop.kt`** —— 配套改动

## 许可（重要）

上游为 **GPL-3.0**。本仓库是公开 fork，**任何分发（含打 APK 给别人装）都必须按 GPL-3.0 一并开源修改版**。
本仓库即履行该义务的落点：改造全部在此，源码可取。
