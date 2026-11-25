package io.lightknight.omniread.ui.explore

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.lightknight.omniread.core.OmniAppContainer
import io.lightknight.omniread.data.local.SourcePluginEntity
import io.lightknight.omniread.data.model.MediaType
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginManagerScreen(
    container: OmniAppContainer,
    onBack: () -> Unit
) {
    val pluginDao = container.database.sourcePluginDao()
    val plugins by pluginDao.observeAll().collectAsState(initial = emptyList())
    var editingPlugin by remember { mutableStateOf<SourcePluginEntity?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Quản lý Nguồn Plugin JS") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại") }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            val summary = container.extensionCatalogManager.refreshAllRepos()
                            snackbarHostState.showSnackbar(
                                if (summary.failedRepos == 0) "Đã đồng bộ ${summary.refreshedRepos} kho tiện ích"
                                else "Đồng bộ ${summary.refreshedRepos} kho, lỗi ${summary.failedRepos}"
                            )
                        }
                    }) { Icon(Icons.Default.Refresh, "Đồng bộ kho tiện ích") }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, "Thêm plugin mới")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            items(plugins, key = { it.id }) { plugin ->
                ListItem(
                    modifier = Modifier.clickable { editingPlugin = plugin },
                    headlineContent = { Text(plugin.name) },
                    supportingContent = { Text("${plugin.mediaType.name} · ${plugin.baseUrl}") },
                    trailingContent = {
                        Row {
                            IconButton(onClick = {
                                scope.launch {
                                    pluginDao.deleteById(plugin.id)
                                    snackbarHostState.showSnackbar("Đã gỡ ${plugin.name}")
                                }
                            }) {
                                Icon(Icons.Default.DeleteOutline, "Gỡ plugin")
                            }
                            Switch(
                                checked = plugin.enabled,
                                onCheckedChange = { enabled ->
                                    scope.launch {
                                        pluginDao.upsert(plugin.copy(enabled = enabled, updatedAt = System.currentTimeMillis()))
                                    }
                                }
                            )
                        }
                    }
                )
                HorizontalDivider()
            }
        }
    }

    editingPlugin?.let { target ->
        var baseUrl by remember(target.id) { mutableStateOf(target.baseUrl) }
        var script by remember(target.id) { mutableStateOf(target.scriptContent) }
        AlertDialog(
            onDismissRequest = { editingPlugin = null },
            title = { Text("Sửa nguồn: ${target.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("Base URL (đổi khi web đổi tên miền)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = script,
                        onValueChange = { script = it },
                        label = { Text("Script JS") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        pluginDao.upsert(
                            target.copy(
                                baseUrl = baseUrl.trim(),
                                scriptContent = script,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                        editingPlugin = null
                        snackbarHostState.showSnackbar("Đã lưu plugin ${target.name}")
                    }
                }) { Text("Lưu") }
            },
            dismissButton = { TextButton(onClick = { editingPlugin = null }) { Text("Đóng") } }
        )
    }

    if (showCreateDialog) {
        var name by remember { mutableStateOf("") }
        var baseUrl by remember { mutableStateOf("https://") }
        var mediaType by remember { mutableStateOf(MediaType.NOVEL) }
        var script by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Thêm Plugin JS mới") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Tên nguồn") }, singleLine = true)
                    OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("Base URL") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = mediaType == MediaType.NOVEL, onClick = { mediaType = MediaType.NOVEL }, label = { Text("Truyện chữ") })
                        FilterChip(selected = mediaType == MediaType.COMIC, onClick = { mediaType = MediaType.COMIC }, label = { Text("Truyện tranh") })
                    }
                    OutlinedTextField(
                        value = script,
                        onValueChange = { script = it },
                        label = { Text("Mã JS (popular, search, getDetail, getChapters, getChapterContent)") },
                        modifier = Modifier.fillMaxWidth().height(160.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank() && script.isNotBlank()) {
                        scope.launch {
                            pluginDao.upsert(
                                SourcePluginEntity(
                                    id = "custom_" + UUID.randomUUID().toString().take(8),
                                    name = name.trim(),
                                    mediaType = mediaType,
                                    baseUrl = baseUrl.trim(),
                                    scriptContent = script,
                                    enabled = true,
                                    isStarter = false
                                )
                            )
                            showCreateDialog = false
                        }
                    }
                }) { Text("Tạo") }
            },
            dismissButton = { TextButton(onClick = { showCreateDialog = false }) { Text("Hủy") } }
        )
    }
}
