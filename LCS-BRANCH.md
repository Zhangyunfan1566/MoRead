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
