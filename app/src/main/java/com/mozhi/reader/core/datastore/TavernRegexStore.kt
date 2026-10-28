package com.mozhi.reader.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mozhi.reader.ai.prompt.TavernRegexScript
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 导入的酒馆正则脚本（`extensions.regex_scripts`）的持久化。
 *
 * 单独一个键、单独一个仓库，不塞进 [GlobalPromptPresetStore]：
 * 正则脚本的启用状态与"预设条目是否启用"是两回事（前者按放置位/深度作用于消息，
 * 后者决定注入哪些提示词条目），混在一起以后没法分别控制。
 */
@Singleton
class TavernRegexStore @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {

    val scripts: Flow<List<TavernRegexScript>> = dataStore.data.map { preferences ->
        decode(preferences[KEY])
    }

    suspend fun current(): List<TavernRegexScript> = scripts.first()

    /** 只保留会真正跑起来的脚本（启用 + 非纯显示用途）。 */
    suspend fun active(): List<TavernRegexScript> = current().filter { it.enabled }

    /** 整表替换（导入时用）。 */
    suspend fun replaceAll(value: List<TavernRegexScript>) {
        val clean = value.take(MAX_SCRIPTS).map { script ->
            script.copy(
                id = script.id.ifBlank { "tavern-regex-" + script.name.hashCode().toString(16) },
                name = script.name.trim().take(MAX_NAME_CHARS),
                find = script.find.take(MAX_FIND_CHARS),
                replacement = script.replacement.take(MAX_REPLACE_CHARS),
                trimStrings = script.trimStrings.take(MAX_TRIM_STRINGS).map { it.take(MAX_TRIM_CHARS) }
            )
        }.filter { it.find.isNotBlank() }
        dataStore.edit { preferences ->
            if (clean.isEmpty()) preferences.remove(KEY) else preferences[KEY] = JSON.encodeToString(SERIALIZER, clean)
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        val current = current()
        if (current.none { it.id == id }) return
        replaceAll(current.map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    suspend fun clear() = replaceAll(emptyList())

    private fun decode(raw: String?): List<TavernRegexScript> = raw
        ?.takeIf(String::isNotBlank)
        ?.let { encoded -> runCatching { JSON.decodeFromString(SERIALIZER, encoded) }.getOrNull() }
        .orEmpty()

    companion object {
        private val KEY = stringPreferencesKey("tavern_regex_scripts_v1")
        private val JSON = Json { ignoreUnknownKeys = true }
        private val SERIALIZER = ListSerializer(TavernRegexScript.serializer())
        internal const val MAX_SCRIPTS = 200
        internal const val MAX_NAME_CHARS = 120
        internal const val MAX_FIND_CHARS = 20_000
        internal const val MAX_REPLACE_CHARS = 60_000
        internal const val MAX_TRIM_STRINGS = 20
        internal const val MAX_TRIM_CHARS = 500
    }
}
