package com.mozhi.reader.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 酒馆预设变量（`{{setvar}}` / `{{getvar}}`）的持久化。
 *
 * **为什么必须有**：真实预设把破限正文装进变量再在别的条目里取出来
 * （实测那份 144 条的预设里 `{{getvar::JailbreakPrompt}}` 就是破限正文的入口）。
 * 变量不跨轮保存的话，第二轮开始破限就整段消失。
 *
 * 作用域（v1）：全局单表，与酒馆的「global variable」对齐；酒馆的 chat-scoped 变量后续按会话切分。
 * 只存字符串，键名与值都做长度夹取，避免预设写脏数据把 DataStore 撑大。
 */
@Singleton
class TavernVariableStore @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {

    /** 读全部变量；解析失败返回空表（不抛异常，注入链路不该因为一条脏数据整轮失败）。 */
    suspend fun load(): Map<String, String> = dataStore.data.first()
        .let { preferences -> decode(preferences[KEY]) }

    /** 整表覆盖写入；空表直接清键。 */
    suspend fun save(values: Map<String, String>) {
        val clean = values.entries
            .filter { (key, _) -> key.isNotBlank() }
            .take(MAX_ENTRIES)
            .associate { (key, value) -> key.trim().take(MAX_KEY_CHARS) to value.take(MAX_VALUE_CHARS) }
        dataStore.edit { preferences ->
            if (clean.isEmpty()) preferences.remove(KEY) else preferences[KEY] = JSON.encodeToString(SERIALIZER, clean)
        }
    }

    /** 删掉单个变量（给设置页的「清空某个变量」用）。 */
    suspend fun remove(name: String) {
        val key = name.trim()
        if (key.isEmpty()) return
        val current = load()
        if (key !in current) return
        save(current - key)
    }

    suspend fun clear() = save(emptyMap())

    private fun decode(raw: String?): Map<String, String> = raw
        ?.takeIf(String::isNotBlank)
        ?.let { encoded -> runCatching { JSON.decodeFromString(SERIALIZER, encoded) }.getOrNull() }
        .orEmpty()

    companion object {
        private val KEY = stringPreferencesKey("tavern_variables_v1")
        private val JSON = Json { ignoreUnknownKeys = true }
        private val SERIALIZER = MapSerializer(String.serializer(), String.serializer())
        internal const val MAX_ENTRIES = 400
        internal const val MAX_KEY_CHARS = 120
        internal const val MAX_VALUE_CHARS = 20_000
    }
}
