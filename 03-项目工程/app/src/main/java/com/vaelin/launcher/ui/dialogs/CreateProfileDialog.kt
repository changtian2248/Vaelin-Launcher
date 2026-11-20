package com.vaelin.launcher.ui.dialogs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vaelin.launcher.ui.RendererOption
import kotlin.math.roundToInt

private const val MEMORY_STEP_MB = 256

/**
 * 默认渲染器的短 id。
 * 注册表第一项是 opengles3（Krypton Wrapper），但新增项按注册顺序排在最前只说明它是最后加的，
 * 不代表最稳。GL4ES（opengles2）是注册表里兼容面最广的一档，作为新建实例的默认值。
 * 若它被移出注册表，退回第一项，不让对话框出现「没有可选渲染器」的假死。
 */
private const val PREFERRED_DEFAULT_RENDERER_ID = "opengles2"

private fun defaultRenderer(options: List<RendererOption>): RendererOption? =
    options.firstOrNull { it.rendererId == PREFERRED_DEFAULT_RENDERER_ID } ?: options.firstOrNull()

/**
 * 新建实例。
 *
 * 三处默认值都有来源，不是随手写的：
 *  - MC 版本 1.20.1：规格书给定的默认目标版本
 *  - 渲染器：见 [PREFERRED_DEFAULT_RENDERER_ID]
 *  - 内存：MemoryTiers 按本机总内存算出的推荐档
 */
@Composable
fun CreateProfileDialog(
    options: List<RendererOption>,
    heapMinMb: Int,
    heapMaxMb: Int,
    defaultMemoryMb: Int,
    onDismiss: () -> Unit,
    onConfirm: (name: String, minecraftVersion: String, renderer: RendererOption, memoryMb: Int) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var minecraftVersion by rememberSaveable { mutableStateOf("1.20.1") }
    var selectedUniqueId by rememberSaveable { mutableStateOf(defaultRenderer(options)?.uniqueId.orEmpty()) }
    var memoryMb by rememberSaveable {
        mutableIntStateOf(defaultMemoryMb.coerceIn(heapMinMb, heapMaxMb))
    }
    var rendererMenuExpanded by remember { mutableStateOf(false) }

    val selectedRenderer = options.firstOrNull { it.uniqueId == selectedUniqueId }
    val canConfirm = name.isNotBlank() && minecraftVersion.isNotBlank() && selectedRenderer != null

    val safeMax = if (heapMaxMb > heapMinMb) heapMaxMb else heapMinMb + MEMORY_STEP_MB
    val stepCount = ((safeMax - heapMinMb) / MEMORY_STEP_MB) - 1

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建实例") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("实例名称") },
                    singleLine = true,
                    supportingText = { Text("留空则不允许创建；名称只用于显示") },
                )

                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = minecraftVersion,
                    onValueChange = { minecraftVersion = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Minecraft 版本") },
                    singleLine = true,
                    supportingText = { Text("例如 1.20.1") },
                )

                Spacer(Modifier.height(10.dp))

                Text(
                    text = "渲染器",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { rendererMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = options.isNotEmpty(),
                    ) {
                        Text(
                            text = selectedRenderer?.name ?: if (options.isEmpty()) "渲染器注册表为空" else "请选择",
                            fontWeight = FontWeight.Normal,
                        )
                    }
                    DropdownMenu(
                        expanded = rendererMenuExpanded,
                        onDismissRequest = { rendererMenuExpanded = false },
                    ) {
                        options.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(option.name)
                                        option.summary?.let {
                                            Text(
                                                text = it,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    selectedUniqueId = option.uniqueId
                                    rendererMenuExpanded = false
                                },
                            )
                        }
                    }
                }
                selectedRenderer?.summary?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(14.dp))

                Text(
                    text = "堆内存：${memoryMb} MB",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = memoryMb.toFloat(),
                    onValueChange = { raw ->
                        val snapped = (raw / MEMORY_STEP_MB).roundToInt() * MEMORY_STEP_MB
                        memoryMb = snapped.coerceIn(heapMinMb, safeMax)
                    },
                    valueRange = heapMinMb.toFloat()..safeMax.toFloat(),
                    steps = if (stepCount > 0) stepCount else 0,
                )
                Text(
                    text = "$heapMinMb – $safeMax MB，按本机总内存推荐的档位是 $defaultMemoryMb MB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val renderer = selectedRenderer ?: return@TextButton
                    onConfirm(name.trim(), minecraftVersion.trim(), renderer, memoryMb)
                },
                enabled = canConfirm,
            ) {
                Text("创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
