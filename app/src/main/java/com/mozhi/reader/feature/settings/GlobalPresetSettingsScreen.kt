package com.mozhi.reader.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mozhi.reader.ai.prompt.SillyTavernPresetImporter
import com.mozhi.reader.core.datastore.GlobalPromptInjectionPosition
import com.mozhi.reader.core.datastore.PromptRole
import com.mozhi.reader.core.datastore.GlobalPromptPreset
import com.mozhi.reader.ui.components.DashedAddRow
import com.mozhi.reader.ui.components.FrostedSurface
import com.mozhi.reader.ui.components.MoReadBackdrop
import kotlinx.coroutines.launch

/** 导入文件大小上限：酒馆预设是纯文本，正常几十 KB；超过这个量级基本是拿错文件了。 */
private const val MAX_TAVERN_PRESET_BYTES = 8 * 1024 * 1024

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalPresetSettingsScreen(
    onBack: () -> Unit,
    viewModel: GlobalPresetSettingsViewModel = hiltViewModel()
) {
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val importPreview by viewModel.importPreview.collectAsStateWithLifecycle()
    val importFlatten by viewModel.importFlatten.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<GlobalPromptPreset?>(null) }

    // 选一个酒馆预设 JSON，读成文本后交给 ViewModel 解析（解析完先出预览，不直接落库）
    val tavernImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("读不到这个文件")
                if (bytes.size > MAX_TAVERN_PRESET_BYTES) {
                    error("文件超过 ${MAX_TAVERN_PRESET_BYTES / 1024 / 1024}MB，不像是酒馆预设 JSON")
                }
                bytes.toString(Charsets.UTF_8)
            }.onSuccess(viewModel::previewTavernImport)
                .onFailure { error ->
                    scope.launch { snackbar.showSnackbar(error.message ?: "读取失败") }
                }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is GlobalPresetEvent.Message -> snackbar.showSnackbar(event.text)
            }
        }
    }

    MoReadBackdrop {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                }
                Text("全局预设", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize().imePadding(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        "开启的预设会加进每次伴读对话，已经发生的聊天记录不受影响。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(presets, key = GlobalPromptPreset::id) { preset ->
                    PresetRow(
                        preset = preset,
                        onEnabled = { viewModel.setEnabled(preset.id, it) },
                        onEdit = { editing = preset },
                        onDelete = { viewModel.delete(preset.id) }
                    )
                }
                item {
                    DashedAddRow(
                        label = "导入酒馆预设（JSON）",
                        onClick = {
                            tavernImport.launch(
                                arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")
                            )
                        }
                    )
                }
                item {
                    DashedAddRow(
                        label = "添加自定义预设",
                        onClick = {
                            editing = GlobalPromptPreset(
                                id = "",
                                name = "",
                                prompt = "",
                                enabled = true
                            )
                        }
                    )
                }
            }
        }
        SnackbarHost(hostState = snackbar, modifier = Modifier.padding(20.dp))
    }

    editing?.let { preset ->
        PresetEditorDialog(
            initial = preset,
            onDismiss = { editing = null },
            onSave = {
                viewModel.save(it)
                editing = null
            }
        )
    }

    importPreview?.let { result ->
        TavernImportDialog(
            result = result,
            flatten = importFlatten,
            onToggleFlatten = viewModel::setImportFlatten,
            onDismiss = viewModel::dismissTavernImport,
            onConfirm = viewModel::confirmTavernImport
        )
    }
}

/** 酒馆预设导入确认：先说清楚"能导入几条、跳过几条、为什么跳过"，再让用户决定是否启用。 */
@Composable
internal fun TavernImportDialog(
    result: SillyTavernPresetImporter.Result,
    flatten: Boolean,
    onToggleFlatten: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (enabled: Boolean) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入酒馆预设") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    result.sourceName?.let { "预设：$it" } ?: "预设：未命名",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    "可注入 ${result.presets.size} 条，标记条目 ${result.markers.size} 条（不注入），跳过 ${result.skipped.size} 条。",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (result.unsupportedMacros.isNotEmpty()) {
                    Text(
                        "含本应用不执行的宏：" + result.unsupportedMacros.joinToString("、") +
                            "（原样保留，不会替换）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (result.splitNames.isNotEmpty()) {
                    Text(
                        "超长条目已自动分段：" + result.splitNames.joinToString("、"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                result.presets.take(12).forEach { preset ->
                    Text(
                        "· ${preset.name} → ${preset.placementSummary()}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (result.presets.size > 12) {
                    Text(
                        "…还有 ${result.presets.size - 12} 条",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (result.markers.isNotEmpty()) {
                    Text("标记条目（保留但不注入）", style = MaterialTheme.typography.titleSmall)
                    result.markers.take(6).forEach { marker ->
                        Text(
                            "· ${marker.name}" + if (marker.identifier.isNotBlank()) "（${marker.identifier}）" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (result.markers.size > 6) {
                        Text(
                            "…还有 ${result.markers.size - 6} 条",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (result.skipped.isNotEmpty()) {
                    Text("跳过明细", style = MaterialTheme.typography.titleSmall)
                    result.skipped.take(8).forEach { skipped ->
                        Text(
                            "· ${skipped.name}：${skipped.reason}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (result.skipped.size > 8) {
                        Text(
                            "…还有 ${result.skipped.size - 8} 条",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("压平到「最近用户消息之后」", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "关掉（默认）= 保留酒馆的角色与相对深度；打开 = 忽略两者，全部作为 system 文本压到生成点。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = flatten, onCheckedChange = onToggleFlatten)
                }
                Text(
                    "已按酒馆语义保留：角色（含 assistant 预填充）与相对深度；绝对位置按角色映射到锚点。" +
                        "导入后可在列表里逐条开关、改角色/深度。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(false) }) { Text("仅导入") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(onClick = { onConfirm(true) }) { Text("导入并启用") }
            }
        }
    )
}

@Composable
internal fun PresetRow(
    preset: GlobalPromptPreset,
    onEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    FrostedSurface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit),
        shape = RoundedCornerShape(22.dp),
        shadowElevation = 5.dp
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(preset.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        preset.placementSummary() + if (preset.builtIn) " · 内置" else " · 自定义",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = preset.enabled, onCheckedChange = onEnabled)
            }
            Text(
                preset.prompt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Row(modifier = Modifier.align(Alignment.End)) {
                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.Edit, contentDescription = "编辑", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PresetEditorDialog(
    initial: GlobalPromptPreset,
    onDismiss: () -> Unit,
    onSave: (GlobalPromptPreset) -> Unit,
    kindLabel: String = "全局预设"
) {
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var prompt by remember(initial.id) { mutableStateOf(initial.prompt) }
    var position by remember(initial.id) { mutableStateOf(initial.position) }
    var role by remember(initial.id) { mutableStateOf(initial.role) }
    var depthText by remember(initial.id) { mutableStateOf(initial.depth.toString()) }
    var marker by remember(initial.id) { mutableStateOf(initial.marker) }
    var expanded by remember { mutableStateOf(false) }
    var roleExpanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id.isBlank()) "添加$kindLabel" else "编辑$kindLabel") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(80) },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                // 角色：酒馆语义。assistant = 预填充（以助手身份插入，破限最强的手段）
                ExposedDropdownMenuBox(expanded = roleExpanded, onExpandedChange = { roleExpanded = it }) {
                    OutlinedTextField(
                        value = role.label(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("角色") },
                        supportingText = {
                            if (role == PromptRole.ASSISTANT) Text("预填充：以助手身份插入，模型会当作自己已经开始说")
                        },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(roleExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(expanded = roleExpanded, onDismissRequest = { roleExpanded = false }) {
                        PromptRole.entries.forEach { candidate ->
                            DropdownMenuItem(
                                text = { Text(candidate.label()) },
                                onClick = { role = candidate; roleExpanded = false }
                            )
                        }
                    }
                }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = position.label(),
                        onValueChange = {},
                        readOnly = true,
                        enabled = depthText.toIntOrNull()?.let { it < 0 } != false,
                        label = { Text("注入位置") },
                        supportingText = {
                            if (depthText.toIntOrNull()?.let { it >= 0 } == true) {
                                Text("已用相对深度，此锚点被忽略")
                            }
                        },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        GlobalPromptInjectionPosition.entries.forEach { candidate ->
                            DropdownMenuItem(
                                text = { Text(candidate.label()) },
                                onClick = { position = candidate; expanded = false }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = depthText,
                    onValueChange = { raw ->
                        // 允许前导负号；清空即等于 -1（用锚点）
                        depthText = raw.filterIndexed { index, c -> c.isDigit() || (c == '-' && index == 0) }.take(4)
                    },
                    label = { Text("相对深度") },
                    singleLine = true,
                    supportingText = { Text("酒馆的 injection_depth：-1 = 用上面的锚点；0 = 最后一条之后（预填充位）；N = 从末尾往上数 N 条") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("标记条目（不注入）", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "酒馆的 marker（如 charDescription / chatHistory）：保留在列表里，但不参与注入",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = marker, onCheckedChange = { marker = it })
                }
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it.take(12_000) },
                    label = { Text("提示词") },
                    minLines = 5,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && prompt.isNotBlank(),
                onClick = {
                    onSave(
                        initial.copy(
                            name = name,
                            prompt = prompt,
                            position = position,
                            role = role,
                            depth = depthText.toIntOrNull() ?: -1,
                            marker = marker
                        )
                    )
                }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

internal fun GlobalPromptInjectionPosition.label(): String = when (this) {
    GlobalPromptInjectionPosition.BEFORE_SYSTEM -> "主系统提示词之前"
    GlobalPromptInjectionPosition.AFTER_SYSTEM -> "主系统提示词之后"
    GlobalPromptInjectionPosition.BEFORE_LAST_USER -> "最近用户消息之前"
    GlobalPromptInjectionPosition.AFTER_LAST_USER -> "最近用户消息之后"
    GlobalPromptInjectionPosition.BEFORE_HISTORY -> "聊天历史之前"
    GlobalPromptInjectionPosition.AFTER_HISTORY -> "聊天历史之后"
}

internal fun PromptRole.label(): String = when (this) {
    PromptRole.SYSTEM -> "系统"
    PromptRole.USER -> "用户"
    PromptRole.ASSISTANT -> "助手（预填充）"
}

/** 行内摘要：角色 + 落点（深度优先）。 */
internal fun GlobalPromptPreset.placementSummary(): String {
    if (marker) return "标记条目 · 不注入"
    val place = if (depth >= 0) "深度 $depth（从末尾往上数）" else position.label()
    return role.label() + " · " + place
}
