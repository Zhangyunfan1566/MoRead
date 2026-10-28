package com.mozhi.reader.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 从酒馆预设**顶层**读出来的请求参数。
 *
 * 为什么单独一个仓库、而不是塞进 [GlobalPromptPresetStore]：
 * 预设条目是"往提示词里塞什么"，这里是"请求怎么发"（采样参数）与"历史带多长"（上下文预算）。
 * 两者的开关生命周期不同，混在一起以后没法分别控制。
 *
 * **[applySampling] 默认 false**：导入一份预设就悄悄改掉采样参数，是用户最难察觉的那种行为变化。
 * 必须由用户在设置页显式打开。
 *
 * [reasoningEffort] 存酒馆原值（`low`/`medium`/`high`/`max`…），应用时只认本应用支持的档位——
 * 实测那份预设写的是 `max`，而 OpenAI 官方只认 low/medium/high，硬发过去会被严格供应商 400。
 */
@Serializable
data class TavernRequestSettings(
    val applySampling: Boolean = false,
    val temperature: Float? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val minP: Float? = null,
    val frequencyPenalty: Float? = null,
    val presencePenalty: Float? = null,
    val repetitionPenalty: Float? = null,
    val maxTokens: Int? = null,
    val seed: Long? = null,
    val reasoningEffort: String? = null,
    /** 酒馆的 `openai_max_context`（token）。本应用只在它**比默认更小**时才采用。 */
    val maxContextTokens: Int? = null,
    /** 按长度裁剪历史；关掉就退回原来的"固定条数窗口"。 */
    val trimHistory: Boolean = true,
    /** 历史字符预算（近似 token 的 4 倍）。 */
    val historyBudgetChars: Int = 24_000
) {
    /** 有没有任何可应用的采样值（全空时不必套装饰）。 */
    val hasSampling: Boolean
        get() = temperature != null || topP != null || topK != null || minP != null ||
            frequencyPenalty != null || presencePenalty != null || repetitionPenalty != null ||
            maxTokens != null || seed != null || reasoningEffort != null
}

@Singleton
class TavernRequestSettingsStore @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {

    val settings: Flow<TavernRequestSettings> = dataStore.data.map { preferences ->
        decode(preferences[KEY])
    }

    suspend fun current(): TavernRequestSettings = settings.first()

    suspend fun save(value: TavernRequestSettings) {
        val clean = value.copy(
            temperature = value.temperature?.coerceIn(0f, 4f),
            topP = value.topP?.coerceIn(0f, 1f),
            topK = value.topK?.coerceIn(0, 10_000),
            minP = value.minP?.coerceIn(0f, 1f),
            frequencyPenalty = value.frequencyPenalty?.coerceIn(-2f, 2f),
            presencePenalty = value.presencePenalty?.coerceIn(-2f, 2f),
            repetitionPenalty = value.repetitionPenalty?.coerceIn(0f, 4f),
            maxTokens = value.maxTokens?.coerceIn(1, 1_000_000),
            maxContextTokens = value.maxContextTokens?.coerceIn(0, 100_000_000),
            historyBudgetChars = value.historyBudgetChars.coerceIn(MIN_BUDGET_CHARS, MAX_BUDGET_CHARS)
        )
        dataStore.edit { preferences ->
            preferences[KEY] = JSON.encodeToString(TavernRequestSettings.serializer(), clean)
        }
    }

    suspend fun setApplySampling(enabled: Boolean) = save(current().copy(applySampling = enabled))

    suspend fun setTrimHistory(enabled: Boolean) = save(current().copy(trimHistory = enabled))

    private fun decode(raw: String?): TavernRequestSettings = raw
        ?.takeIf(String::isNotBlank)
        ?.let { encoded -> runCatching { JSON.decodeFromString(TavernRequestSettings.serializer(), encoded) }.getOrNull() }
        ?: TavernRequestSettings()

    companion object {
        private val KEY = stringPreferencesKey("tavern_request_settings_v1")
        private val JSON = Json { ignoreUnknownKeys = true }
        internal const val MIN_BUDGET_CHARS = 1_000
        internal const val MAX_BUDGET_CHARS = 4_000_000
    }
}
