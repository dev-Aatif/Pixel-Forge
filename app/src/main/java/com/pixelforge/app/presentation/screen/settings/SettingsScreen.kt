package com.pixelforge.app.presentation.screen.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pixelforge.app.domain.model.ModelType
import com.pixelforge.app.presentation.theme.LabTextStyles
import com.pixelforge.app.presentation.theme.SuccessGreen
import com.pixelforge.app.presentation.theme.TextTertiary
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.downloadError) {
        uiState.downloadError?.let { msg ->
            snackbarHostState.showSnackbar("Download failed: $msg")
            viewModel.clearDownloadError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Lab settings") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp)
        ) {
            SectionTitle("Defaults")
            Spacer(modifier = Modifier.height(10.dp))

            SettingsPanel {
                Column(modifier = Modifier.padding(14.dp)) {
                    var expandedModel by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = expandedModel,
                        onExpandedChange = { expandedModel = !expandedModel },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = uiState.defaultModelType.displayName,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Default preset") },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedModel)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        )
                        ExposedDropdownMenu(
                            expanded = expandedModel,
                            onDismissRequest = { expandedModel = false }
                        ) {
                            ModelType.entries.forEach { model ->
                                DropdownMenuItem(
                                    text = { Text(model.displayName) },
                                    onClick = {
                                        viewModel.setModelType(model)
                                        expandedModel = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    var expandedScale by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = expandedScale,
                        onExpandedChange = { expandedScale = !expandedScale },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = "${uiState.defaultScaleFactor}×",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Default scale") },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedScale)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        )
                        ExposedDropdownMenu(
                            expanded = expandedScale,
                            onDismissRequest = { expandedScale = false }
                        ) {
                            listOf(2, 4).forEach { scale ->
                                DropdownMenuItem(
                                    text = { Text("${scale}×") },
                                    onClick = {
                                        viewModel.setScaleFactor(scale)
                                        expandedScale = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    var expandedRes by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = expandedRes,
                        onExpandedChange = { expandedRes = !expandedRes },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = "${uiState.maxResolution} px",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Max input edge") },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedRes)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        )
                        ExposedDropdownMenu(
                            expanded = expandedRes,
                            onDismissRequest = { expandedRes = false }
                        ) {
                            listOf(1200, 1600, 2048).forEach { res ->
                                DropdownMenuItem(
                                    text = { Text("$res px") },
                                    onClick = {
                                        viewModel.setMaxResolution(res)
                                        expandedRes = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))
            SectionTitle("Hardware & AI")
            Spacer(modifier = Modifier.height(10.dp))

            SettingsPanel {
                Column {
                    SettingSwitchRow(
                        title = "NPU / DSP acceleration",
                        subtitle = "Qualcomm QNN / NNAPI for fastest execution",
                        checked = uiState.useNpu,
                        onCheckedChange = viewModel::setUseNpu
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    SettingSwitchRow(
                        title = "Vulkan GPU acceleration",
                        subtitle = "GPU compute shaders for NCNN models",
                        checked = uiState.useGpu,
                        onCheckedChange = viewModel::setUseGpu
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    SettingSwitchRow(
                        title = "Face restoration",
                        subtitle = "Detect & sharpen faces automatically in portraits",
                        checked = uiState.faceEnhanceDefault,
                        onCheckedChange = viewModel::setFaceEnhanceDefault
                    )
                }
            }

            Spacer(modifier = Modifier.height(22.dp))
            SectionTitle("Export")
            Spacer(modifier = Modifier.height(10.dp))

            SettingsPanel {
                Column(modifier = Modifier.padding(14.dp)) {
                    var expandedFmt by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = expandedFmt,
                        onExpandedChange = { expandedFmt = !expandedFmt },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = uiState.exportFormat,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("File format") },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedFmt)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        )
                        ExposedDropdownMenu(
                            expanded = expandedFmt,
                            onDismissRequest = { expandedFmt = false }
                        ) {
                            listOf("PNG", "JPEG", "WEBP").forEach { fmt ->
                                DropdownMenuItem(
                                    text = { Text(fmt) },
                                    onClick = {
                                        viewModel.setExportFormat(fmt)
                                        expandedFmt = false
                                    }
                                )
                            }
                        }
                    }

                    if (uiState.exportFormat != "PNG") {
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Compression quality",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${uiState.jpegQuality}%",
                                style = LabTextStyles.TabularStat,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = uiState.jpegQuality.toFloat(),
                            onValueChange = { viewModel.setJpegQuality(it.roundToInt()) },
                            valueRange = 70f..100f
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))
            SectionTitle("Optional model packs")
            Spacer(modifier = Modifier.height(10.dp))

            SettingsPanel {
                Column {
                    ModelDownloadRow(
                        title = "Ultra Photo (realesrgan-x4plus)",
                        subtitle = if (uiState.isUltraDownloaded) {
                            "Installed (32 MB)"
                        } else {
                            "Heavy 23-RRDB model (~32 MB)"
                        },
                        isDownloaded = uiState.isUltraDownloaded,
                        progress = uiState.ultraDownloadProgress,
                        onDownload = viewModel::downloadUltraModel
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    ModelDownloadRow(
                        title = "Neural Face Restore (GFPGAN v1.4)",
                        subtitle = if (uiState.isFacePackDownloaded) {
                            "Installed (~340 MB)"
                        } else {
                            "Studio portrait restoration (~340 MB)"
                        },
                        isDownloaded = uiState.isFacePackDownloaded,
                        progress = uiState.facePackDownloadProgress,
                        onDownload = viewModel::downloadFaceRestoreModel
                    )
                }
            }

            if (uiState.downloadedModelsSizeStr != "0 B") {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = viewModel::clearDownloadedModels,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Text("Remove downloaded packs (${uiState.downloadedModelsSizeStr})")
                }
            }

            Spacer(modifier = Modifier.height(22.dp))
            SectionTitle("Storage")
            Spacer(modifier = Modifier.height(10.dp))

            SettingsPanel {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Temporary cache", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = uiState.cacheSizeStr,
                            style = LabTextStyles.TabularSmall,
                            color = TextTertiary
                        )
                    }
                    OutlinedButton(
                        onClick = viewModel::clearCache,
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Text("Clear")
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground
    )
}

@Composable
private fun SettingsPanel(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        content()
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary
            )
        )
    }
}

@Composable
private fun ModelDownloadRow(
    title: String,
    subtitle: String,
    isDownloaded: Boolean,
    progress: Float?,
    onDownload: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isDownloaded) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!isDownloaded && progress == null) {
                Button(
                    onClick = onDownload,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Download")
                }
            }
        }
        if (progress != null) {
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
