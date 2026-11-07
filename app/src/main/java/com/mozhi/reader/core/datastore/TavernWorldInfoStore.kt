package com.mozhi.reader.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mozhi.reader.ai.prompt.WorldInfoEngine
import com.mozhi.reader.ai.prompt.WorldInfoEntry
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 全局世界书（导入的酒馆 Lorebook）与扫描设置。
 *
 * 与「角色卡自带的世界书」（`PersonaEntity.worldBookJson`）是**两个不同作用域**：
 * 前者是跨角色生效的设定集（酒馆的 lorebook），后者跟着某一张角色卡走。
 * 所以这里单独一个键、单独一个仓库，互不覆盖。
 */
@Serializable
data class TavernWorldInfo(
    val entries: List<WorldInfoEntry> = emptyList(),
    /** 导入时记录来源，便于界面显示"这份世界书是从哪来的"。 */
    val sourceName: String = "",
    /** 总开关；关掉后条目都还在，只是不注入。 */
    val enabled: Boolean = true,
    val options: WorldInfoEngine.Options = WorldInfoEngine.Options()
)

@Singleton
class TavernWorldInfoStore @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {

    val worldInfo: Flow<TavernWorldInfo> = dataStore.data.map { preferences ->
        decode(preferences[KEY])
    }

    suspend fun current(): TavernWorldInfo = worldInfo.first()

    suspend fun save(value: TavernWorldInfo) {
        val clean = value.copy(
            sourceName = value.sourceName.take(200),
            entries = value.entries.take(MAX_ENTRIES).map { entry ->
                entry.copy(
                    id = entry.id.ifBlank { "wi-" + entry.comment.hashCode().toString(16) },
                    comment = entry.comment.take(200),
                    content = entry.content.take(MAX_CONTENT_CHARS),
                    keys = entry.keys.take(MAX_KEYS).map { it.take(MAX_KEY_CHARS) },
                    secondaryKeys = entry.secondaryKeys.take(MAX_KEYS).map { it.take(MAX_KEY_CHARS) },
                    depth = entry.depth.coerceIn(0, 1000),
                    order = entry.order.coerceIn(-10_000, 10_000),
                    probability = entry.probability.coerceIn(0, 100),
                    scanDepth = entry.scanDepth?.coerceIn(0, 1000)
                )
            },
            options = value.options.copy(
                scanDepth = value.options.scanDepth.coerceIn(0, 1000),
                maxChars = value.options.maxChars.coerceIn(200, 200_000)
            )
        )
        dataStore.edit { preferences ->
            preferences[KEY] = JSON.encodeToString(TavernWorldInfo.serializer(), clean)
        }
    }

    suspend fun setEnabled(enabled: Boolean) = save(current().copy(enabled = enabled))

    suspend fun clear() = save(TavernWorldInfo())

    private fun decode(raw: String?): TavernWorldInfo = raw
        ?.takeIf(String::isNotBlank)
        ?.let { encoded -> runCatching { JSON.decodeFromString(TavernWorldInfo.serializer(), encoded) }.getOrNull() }
        ?: TavernWorldInfo()

    companion object {
        private val KEY = stringPreferencesKey("tavern_world_info_v1")
        private val JSON = Json { ignoreUnknownKeys = true }
        internal const val MAX_ENTRIES = 500
        internal const val MAX_CONTENT_CHARS = 20_000
        internal const val MAX_KEYS = 50
        internal const val MAX_KEY_CHARS = 200
    }
}
