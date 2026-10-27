package com.mozhi.reader.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.ai.prompt.SillyTavernPresetImporter
import com.mozhi.reader.core.datastore.GlobalPromptPreset
import com.mozhi.reader.core.datastore.GlobalPromptPresetStore
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface GlobalPresetEvent {
    data class Message(val text: String) : GlobalPresetEvent
}

@HiltViewModel
class GlobalPresetSettingsViewModel @Inject constructor(
    private val store: GlobalPromptPresetStore
) : ViewModel() {
    val presets = store.presets.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        GlobalPromptPresetStore.DEFAULTS
    )
    private val eventChannel = Channel<GlobalPresetEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    /** 酒馆预设导入预览；非空时界面弹出确认框。 */
    private val _importPreview = MutableStateFlow<SillyTavernPresetImporter.Result?>(null)
    val importPreview = _importPreview.asStateFlow()

    /** 待导入的原始 JSON，切换"压到生成点"时要重新解析。 */
    private var pendingImportText: String? = null

    /** 是否把导入的条目全部压平到"最近用户消息之后"（忽略酒馆的 role 与 depth）。 */
    private val _importFlatten = MutableStateFlow(false)
    val importFlatten = _importFlatten.asStateFlow()

    /** 解析酒馆预设 JSON 并暂存结果，等用户确认后再写入。 */
    fun previewTavernImport(text: String) {
        pendingImportText = text
        _importFlatten.value = false
        rebuildImportPreview()
    }

    fun setImportFlatten(flatten: Boolean) {
        if (_importFlatten.value == flatten) return
        _importFlatten.value = flatten
        rebuildImportPreview()
    }

    private fun rebuildImportPreview() {
        val text = pendingImportText ?: return
        viewModelScope.launch {
            runCatching {
                SillyTavernPresetImporter.import(text, flattenToLastUser = _importFlatten.value)
            }.onSuccess { _importPreview.value = it }
                .onFailure { error ->
                    eventChannel.send(GlobalPresetEvent.Message(error.message ?: "导入失败"))
                }
        }
    }

    fun dismissTavernImport() {
        _importPreview.value = null
        pendingImportText = null
    }

    /** 确认导入。[enabled] 决定导入后是否立即启用（默认不启用，避免一次导入就改变行为）。 */
    fun confirmTavernImport(enabled: Boolean) {
        val result = _importPreview.value ?: return
        viewModelScope.launch {
            runCatching {
                result.presets.forEach { store.upsert(it.copy(enabled = enabled)) }
                // marker 条目一并收进列表：可见、可手动改成可注入，但本身不参与注入。
                result.markers.forEach { store.upsert(it.copy(enabled = false, marker = true)) }
            }.onSuccess {
                _importPreview.value = null
                val markerTail = if (result.markers.isEmpty()) "" else "，另存 ${result.markers.size} 条标记条目（不注入）"
                val tail = if (result.skipped.isEmpty()) "" else "，跳过 ${result.skipped.size} 条"
                eventChannel.send(GlobalPresetEvent.Message("已导入 ${result.presets.size} 条预设$markerTail$tail"))
            }.onFailure { error ->
                eventChannel.send(GlobalPresetEvent.Message(error.message ?: "写入失败"))
            }
        }
    }

    fun save(preset: GlobalPromptPreset) {
        viewModelScope.launch {
            runCatching {
                store.upsert(preset.copy(id = preset.id.ifBlank { UUID.randomUUID().toString() }))
            }.onSuccess {
                eventChannel.send(GlobalPresetEvent.Message("全局预设已保存"))
            }.onFailure { error ->
                eventChannel.send(GlobalPresetEvent.Message(error.message ?: "保存失败"))
            }
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { store.setEnabled(id, enabled) }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            store.delete(id)
            eventChannel.send(GlobalPresetEvent.Message("已删除预设"))
        }
    }
}
