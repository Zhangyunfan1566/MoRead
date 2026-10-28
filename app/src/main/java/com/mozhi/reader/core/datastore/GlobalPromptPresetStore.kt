package com.mozhi.reader.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 全局提示词的**绝对锚点**。前两项进入 system，中间两项只改写本次请求里最新一条 user，
 * 后两项是酒馆语义的聊天历史锚点。设计语义参考 SillyTavern Prompt Manager。
 *
 * 旧数据里只可能出现前四个值，新增两个不影响反序列化（`ignoreUnknownKeys` 已开）。
 */
@Serializable
enum class GlobalPromptInjectionPosition {
    BEFORE_SYSTEM,
    AFTER_SYSTEM,
    BEFORE_LAST_USER,
    AFTER_LAST_USER,
    /** 酒馆语义：聊天历史之前（system 块之后、第一条历史消息之前）。 */
    BEFORE_HISTORY,
    /** 酒馆语义：聊天历史之后（追加到末尾）。 */
    AFTER_HISTORY
}

/**
 * 注入消息的角色。与酒馆一致：条目可以以 system / user / **assistant** 身份插入，
 * 其中 assistant 等价于酒馆的「预填充」（prefill）——破限最强的手段。
 */
@Serializable
enum class PromptRole { SYSTEM, USER, ASSISTANT }

@Serializable
data class GlobalPromptPreset(
    val id: String,
    val name: String,
    val prompt: String,
    val enabled: Boolean = false,
    /** 绝对锚点；[depth] >= 0 时忽略此项。 */
    val position: GlobalPromptInjectionPosition = GlobalPromptInjectionPosition.AFTER_SYSTEM,
    val builtIn: Boolean = false,
    /** 注入角色；[PromptRole.ASSISTANT] 等价于酒馆的预填充条目。 */
    val role: PromptRole = PromptRole.SYSTEM,
    /**
     * 相对深度：**-1 = 用 [position] 锚点**；`0..N` = 从对话末尾往上数 N 条消息处插入
     * （`0` 表示最后一条之后，即预填充位）。对应酒馆的 `injection_depth`。
     */
    val depth: Int = -1,
    /** 酒馆预设里的 identifier（main / jailbreak / nsfw / assistant_prefill …），仅作识别与记录。 */
    val identifier: String = "",
    /** 同锚点 / 同深度内的先后顺序，对应酒馆 `prompt_order`。 */
    val order: Int = 0,
    /** 酒馆的 marker 条目：保留在列表里可见可改，但**不注入**。 */
    val marker: Boolean = false
)

@Singleton
class GlobalPromptPresetStore @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    val presets: Flow<List<GlobalPromptPreset>> = dataStore.data.map { preferences ->
        decode(preferences[KEY]) ?: DEFAULTS
    }

    suspend fun current(): List<GlobalPromptPreset> = presets.first()

    suspend fun upsert(preset: GlobalPromptPreset) {
        val clean = preset.copy(
            id = preset.id.ifBlank { UUID.randomUUID().toString() },
            name = preset.name.trim().take(MAX_NAME_CHARS),
            prompt = preset.prompt.trim().take(MAX_PROMPT_CHARS)
        )
        require(clean.name.isNotBlank()) { "预设名称不能为空" }
        // marker 是酒馆预设里的**占位条目**（worldInfoBefore / chatHistory…），正文本来就是空的，
        // 而且它永远不参与注入——对它套"提示词不能为空"会让导入任何真实预设都失败。
        require(clean.prompt.isNotBlank() || clean.marker) { "提示词不能为空" }
        dataStore.edit { preferences ->
            val existing = decode(preferences[KEY]) ?: DEFAULTS
            preferences[KEY] = encode(existing.filterNot { it.id == clean.id } + clean)
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        dataStore.edit { preferences ->
            val existing = decode(preferences[KEY]) ?: DEFAULTS
            preferences[KEY] = encode(existing.map { preset ->
                if (preset.id == id) preset.copy(enabled = enabled) else preset
            })
        }
    }

    suspend fun delete(id: String) {
        dataStore.edit { preferences ->
            val existing = decode(preferences[KEY]) ?: DEFAULTS
            preferences[KEY] = encode(existing.filterNot { it.id == id })
        }
    }

    private fun encode(value: List<GlobalPromptPreset>): String =
        JSON.encodeToString(ListSerializer(GlobalPromptPreset.serializer()), value)

    private fun decode(value: String?): List<GlobalPromptPreset>? = value
        ?.takeIf(String::isNotBlank)
        ?.let { encoded ->
            runCatching {
                JSON.decodeFromString(ListSerializer(GlobalPromptPreset.serializer()), encoded)
            }.getOrNull()
        }

    companion object {
        private val KEY = stringPreferencesKey("global_prompt_presets")
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private const val MAX_NAME_CHARS = 80
        const val MAX_PROMPT_CHARS = 12_000

        val DEFAULTS = listOf(
            GlobalPromptPreset(
                id = "builtin-natural-style",
                name = "自然表达",
                prompt = "使用自然、具体、连贯的中文回答，避免空泛套话和不必要的重复总结。",
                position = GlobalPromptInjectionPosition.AFTER_SYSTEM,
                builtIn = true
            ),
            GlobalPromptPreset(
                id = "builtin-immersive-roleplay",
                name = "沉浸式角色扮演",
                prompt = "保持角色视角与说话方式，通过动作、语气和细节增强沉浸感；不要代替用户决定其言行。",
                position = GlobalPromptInjectionPosition.AFTER_SYSTEM,
                builtIn = true
            ),
            GlobalPromptPreset(
                id = "builtin-concise",
                name = "简洁回答",
                prompt = "优先直接回答问题；除非用户要求展开，否则控制篇幅并省略重复背景。",
                position = GlobalPromptInjectionPosition.BEFORE_LAST_USER,
                builtIn = true
            )
        )
    }
}
