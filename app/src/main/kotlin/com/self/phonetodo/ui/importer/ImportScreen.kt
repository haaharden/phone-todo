package com.self.phonetodo.ui.importer

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.self.phonetodo.importer.ImportPreview
import com.self.phonetodo.importer.SuperProductivityImporter
import com.self.phonetodo.model.Category
import com.self.phonetodo.model.Todo
import com.self.phonetodo.ui.theme.JadeOk

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    existingTodoIds: Set<String>,
    defaultTimeZone: String,
    onBack: () -> Unit,
    onConfirmImport: (categories: List<Category>, todos: List<Todo>) -> Pair<Int, Int>,
) {
    val context = LocalContext.current
    var pastedText by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<ImportPreview?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var resultBanner by remember { mutableStateOf<String?>(null) }

    var includeActive by remember { mutableStateOf(true) }
    var includeRepeat by remember { mutableStateOf(true) }
    var includeArchived by remember { mutableStateOf(false) }

    fun parseRaw(raw: String) {
        errorMsg = null
        resultBanner = null
        SuperProductivityImporter.parse(raw, existingTodoIds, defaultTimeZone)
            .onSuccess { preview = it }
            .onFailure {
                preview = null
                errorMsg = it.message ?: "解析失败"
            }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
            }.onSuccess { content ->
                parseRaw(content)
            }.onFailure {
                errorMsg = "读取文件失败：${it.message}"
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                title = { Text("导入 Super Productivity", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "从导出文件批量迁移",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "在 Super Productivity 选择「设置 → 同步与导出 → 导入/导出 → 导出数据」，导入生成的 JSON 备份或 Worklog CSV 日志。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { filePicker.launch(arrayOf("*/*")) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.FileUpload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("选择备份或日志文件")
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "或直接粘贴日志文本",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    OutlinedTextField(
                        value = pastedText,
                        onValueChange = { pastedText = it },
                        placeholder = { Text("粘贴 JSON / CSV 日志，或每行一条待办") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        maxLines = 8,
                        shape = RoundedCornerShape(12.dp),
                    )
                    OutlinedButton(
                        onClick = { parseRaw(pastedText) },
                        enabled = pastedText.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("解析粘贴内容")
                    }
                }
            }

            errorMsg?.let { err ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }

            resultBanner?.let { ok ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = JadeOk.copy(alpha = 0.14f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = ok,
                        color = JadeOk,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }

            preview?.let { p ->
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = p.sourceType,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "分类 ${p.categories.size} 个  自动去重跳过 ${p.skippedDuplicateCount} 条",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        p.warnings.forEach { w ->
                            Text(w, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = includeActive, onCheckedChange = { includeActive = it })
                            Text("导入活跃待办（${p.activeTodos.size} 条）")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = includeRepeat, onCheckedChange = { includeRepeat = it })
                            Text("导入重复任务（${p.repeatTodos.size} 条）")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = includeArchived, onCheckedChange = { includeArchived = it })
                            Text("同时导入历史归档日志（${p.archivedTodos.size} 条）")
                        }

                        val selectedTodos = buildList {
                            if (includeActive) addAll(p.activeTodos)
                            if (includeRepeat) addAll(p.repeatTodos)
                            if (includeArchived) addAll(p.archivedTodos)
                        }

                        Spacer(Modifier.height(4.dp))

                        Button(
                            onClick = {
                                val (catsAdded, todosAdded) = onConfirmImport(p.categories, selectedTodos)
                                resultBanner = "导入完成：新增 $catsAdded 个分类、$todosAdded 条待办"
                                preview = null
                            },
                            enabled = selectedTodos.isNotEmpty(),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                        ) {
                            Text("确认批量导入（${selectedTodos.size} 条）", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}
