# LCS 改造线说明（moread-lcs）

这个仓库是 `ovo066/MoRead`（墨知：原生 Android 本地小说阅读器 + AI 伴读，GPL-3.0）的 **LCS 私有改造线**，
用于在私有环境里做提示词/Agent 侧的改造研究，不跟随上游发布节奏。

## 分支与远端

| 远端 | 地址 | 用途 |
|---|---|---|
| `origin` | `https://github.com/Zhangyunfan1566/moread-lcs.git` | 本改造线（私有，推送前自备凭据） |
| `upstream` | `https://github.com/ovo066/MoRead.git` | 作者仓库，只读拉取 |
| `local-mirror` | `/home/lzk/workspace/DSH/moread-upstream-mirror.git` | 作者仓库的本地裸镜像（离线保险） |

合并上游：

```bash
git fetch upstream
git merge upstream/main     # 冲突多在版本号/日志文件
```

## 当前内容

基线 = 上游 `60e627a`（`fix: 发布 1.4.1 导入兼容与伴读体验升级`，2026-10-21，tag 见仓库）。
本线在其之上新增 **分支 `feature/tavern-preset-import`**：

**导入 SillyTavern（酒馆）对话补全预设 → 本应用「全局预设」**

- 新增 `app/src/main/java/com/mozhi/reader/ai/prompt/SillyTavernPresetImporter.kt`
  - 解析酒馆预设 JSON 的 `prompts[]`；**跳过 marker 条目**（`main` / `charDescription` / `chatHistory` /
    `worldInfoBefore|After` / `personaDescription` / `dialogueExamples` / `enhanceDefinitions` …），
    本应用没有 marker 系统，跳过项会在结果里列明原因
  - 位置近似映射：`injection_position=1`(absolute)+system → `AFTER_SYSTEM`；
    absolute+user/assistant → `BEFORE_LAST_USER`；relative+`depth≤0` → `AFTER_LAST_USER`（最贴近生成点）；
    relative+`depth≥1` → `BEFORE_LAST_USER`
  - 启用状态优先取 `prompt_order`，其次条目自身 `enabled`
  - 单条超过 `GlobalPromptPresetStore.MAX_PROMPT_CHARS`（12,000）**按空行/句末切段**，不丢弃
  - 支持 `overridePosition`：把整份预设统一压到指定位置（破限向用法）
- 新增单元测试 `app/src/test/java/com/mozhi/reader/ai/prompt/SillyTavernPresetImporterTest.kt`（11 项）
- `feature/settings/GlobalPresetSettingsViewModel.kt`：`previewTavernImport` / `setImportFlatten` /
  `confirmTavernImport(enabled)` / `dismissTavernImport`
- `feature/settings/GlobalPresetSettingsScreen.kt`：列表底部新增「导入酒馆预设（JSON）」入口
  （`OpenDocument` + 8MB 上限）、导入预览确认框（导入/跳过明细 + 「全部压到最近用户消息之后」开关）

## 分支 `feature/image-scene-architect`：出图链路重写

原出图链路是**两次请求 + 自然语言拼接**：先让模型挑人物（且先试 `CHEAP` 角色，吃不到全局预设/破限串），
再由代码拼一段 `Style: … / Character X: … / Scene: … / Avoid: …` 塞给 NAI ——
NAI 收到的是非标准自然语言串而不是它的原生结构，且完全没有「角色档案维护」协议。

重写为**一次请求产出完整配方**（参照 SillyTavern 扩展「柏宝绘」ST-BaiBai-Image 的做法）：

- **新增** `ai/media/ImageSceneArchitect.kt`
  - 消息顺序固定：**破限 → 后端规范 → 思考清单 → 任务协议 → 正文**；破限/规范/思考清单均可在设置里覆盖
  - 内置破限含**成人内容条款**（照实写画面、不因露骨删减；未成年人边界；只输出协议 JSON）
  - 内置 NAI 规范讲 **Base 块与角色块的分工**（Base 只放人数/景别/场景/光线/共享关系；
    每角色外貌·服装·动作·表情·视线进 `characters[]`），含**显式场景条款**
    （逐个写出可见身体部位与接触关系，禁止只写 nsfw 糊过去）与多人绑定规则
  - 输出协议固定 JSON：`{thinking, images[{tag, nl, characters[], negative, size}], changes[]}`
  - 解析器：剥 `<thinking>` → 取第一个平衡 JSON（容忍围栏/前后说明/字符串内花括号）→ 校验清洗
    （NAI 后端丢弃含中文 tag、剔除通用质量负面词、按 `maxImages` 截断、`minImages` 不足报错）
- **外貌库闭环**：`changes` 写回 `LookSpec`（建档 `field:"new"` 解析 `hair=…; eyes=…`；
  永久变化推进 `sinceChapter`，变化之前的画面仍取旧档案）；纯逻辑抽成 `SceneArchitectLibrary` 便于单测
- **NAI 原生结构**：`ImageRecipe` 增加 `baseTag` / `characterTags` / `negativeExtra`；
  `RecipeAssembler` 优先用规划器的 Base 块与角色块，不再拼自然语言
- **NAI 参数可配**：`ucPreset`（Heavy/Light/None/Human Focus）与 `qualityToggle` 从硬编码改为设置项
- **选区出图不再自拼中文提示词**：`ReaderSelectionMediaViewModel` 把「选段 + 附近语境 + 章节信息」原样交给规划器
- **兜底**：规划器不可用（没有对话模型 / 返回无法解析）时退回旧的镜头规划路径，仍能出图
- **测试**：`ImageSceneArchitectTest`（解析、清洗、档案合并、协议内容、消息顺序、设置覆盖）

## 实测结论（省得重踩）

- **本线尚未编译验证**：本机 2026-10-10 起已按需清理 Android/Flutter 工具链
  （重建脚本见 `plana-build/bootstrap-toolchain.sh`，属 plana 线资产）。MoRead 要求
  `compileSdk 37` / `minSdk 26`、Gradle **9.6.1**（wrapper 自带），需要 JDK 21 + Android SDK platform 37。
- 映射规则用**独立 Python 原型**在真实酒馆预设上验证过（同一套规则）：
  `muse_V4.json` 81 条 → 可导入 41 / 跳过 40（13 marker、21 关闭、6 空）；
  `miki_v06_museJB.json` → 可导入 5（**强穿甲-变量 / miki_jb_identity / 强穿甲-系统 / 强穿甲-预填充** 全部命中）；
  `miki_v06_core.json` → 可导入 4（4 条强穿甲全中）。
- **没有"按对话深度插入"**：酒馆的 depth 分层在本应用只能压成两个位置，多层级越狱会退化；
  「全部压到最近用户消息之后」是补偿方案，不是等价还原。
- **只导文本条目**：采样参数（temperature/top_p…）、`assistant_prefill`、正则脚本未导入
  （本应用预设体系里没有这些位置）。

## 许可

上游为 **GPL-3.0**。自用无碍；**一旦分发（含打包 APK 给别人装），必须按 GPL-3.0 一并开源修改版**。
