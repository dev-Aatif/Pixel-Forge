package com.pixelforge.app.presentation.screen.editor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.pixelforge.app.domain.model.ModelType
import com.pixelforge.app.presentation.theme.LabTextStyles
import com.pixelforge.app.presentation.theme.TextTertiary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    imageUri: String,
    onStartProcessing: (workId: String) -> Unit,
    onBack: () -> Unit,
    viewModel: EditorViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        val workId = viewModel.startProcessing()
        onStartProcessing(workId)
    }

    LaunchedEffect(imageUri) {
        viewModel.loadImage(imageUri)
    }

    LaunchedEffect(uiState.downloadError) {
        uiState.downloadError?.let { msg ->
            snackbarHostState.showSnackbar("Model download failed: $msg")
            viewModel.clearDownloadError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Configure enhance") },
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
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Output target",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${uiState.outputWidth}×${uiState.outputHeight} ${uiState.config.exportFormat}",
                                style = LabTextStyles.TabularStat,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Button(
                            onClick = {
                                val needsNotificationPerm =
                                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                        ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.POST_NOTIFICATIONS
                                        ) != PackageManager.PERMISSION_GRANTED
                                if (needsNotificationPerm) {
                                    notificationPermissionLauncher.launch(
                                        Manifest.permission.POST_NOTIFICATIONS
                                    )
                                } else {
                                    val workId = viewModel.startProcessing()
                                    onStartProcessing(workId)
                                }
                            },
                            enabled = uiState.isReady,
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Enhance",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            // Print-well image preview
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.25f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (uiState.loadError != null) {
                    Text(
                        text = uiState.loadError!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(20.dp)
                    )
                } else if (uiState.imageUri.isNotEmpty()) {
                    AsyncImage(
                        model = uiState.imageUri,
                        contentDescription = "Selected photo preview",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
            }

            if (uiState.isDownscaledInput) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Input capped at ${uiState.config.maxResolution} px before ${uiState.config.scaleFactor}× enhance (adjustable in Settings)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Specimen ledger strip (3 hairline cells)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                ) {
                    SpecimenCell(
                        label = "Source",
                        value = "${uiState.imageWidth}×${uiState.imageHeight}",
                        modifier = Modifier.weight(1f)
                    )
                    VerticalDivider(
                        modifier = Modifier.fillMaxHeight(),
                        color = MaterialTheme.colorScheme.outline
                    )
                    SpecimenCell(
                        label = "Size",
                        value = uiState.fileSize.ifEmpty { "—" },
                        modifier = Modifier.weight(0.8f)
                    )
                    VerticalDivider(
                        modifier = Modifier.fillMaxHeight(),
                        color = MaterialTheme.colorScheme.outline
                    )
                    SpecimenCell(
                        label = "Faces",
                        value = if (uiState.detectedFaces > 0) "${uiState.detectedFaces}" else "None",
                        modifier = Modifier.weight(0.7f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            Text(
                text = "Scale factor",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                    .height(IntrinsicSize.Min)
                    .selectableGroup()
            ) {
                val scales = listOf(2, 4)
                scales.forEachIndexed { index, scale ->
                    val selected = uiState.config.scaleFactor == scale
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .background(
                                if (selected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                }
                            )
                            .selectable(
                                selected = selected,
                                role = Role.RadioButton,
                                onClick = { viewModel.setScaleFactor(scale) }
                            )
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${scale}× Enlarge",
                            style = LabTextStyles.TabularStat,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    if (index == 0) {
                        VerticalDivider(
                            modifier = Modifier.fillMaxHeight(),
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            Text(
                text = "Processing model",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (uiState.isIllustrationRecommended) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Artwork or text detected — Anime & Text preset active",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            // Hairline-divided model panel
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.fillMaxWidth().selectableGroup()) {
                    ModelType.entries.forEachIndexed { index, modelType ->
                        val isSelected = uiState.config.modelType == modelType
                        val needsDownload =
                            modelType == ModelType.ULTRA_PHOTO && !uiState.isUltraAvailable

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isSelected) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    }
                                )
                                .selectable(
                                    selected = isSelected,
                                    enabled = !needsDownload,
                                    role = Role.RadioButton,
                                    onClick = { viewModel.setModelType(modelType) }
                                )
                                .height(IntrinsicSize.Min),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .fillMaxHeight()
                                    .background(
                                        if (isSelected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        }
                                    )
                            )

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                            ) {
                                Text(
                                    text = modelType.displayName,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = modelType.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                                    } else {
                                        TextTertiary
                                    }
                                )
                                if (modelType == ModelType.ULTRA_PHOTO && uiState.ultraDownloadProgress != null) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    LinearProgressIndicator(
                                        progress = { uiState.ultraDownloadProgress ?: 0f },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }

                            if (needsDownload && uiState.ultraDownloadProgress == null) {
                                TextButton(onClick = { viewModel.downloadUltraModel() }) {
                                    Icon(
                                        Icons.Default.CloudDownload,
                                        contentDescription = "Download",
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("32 MB", style = LabTextStyles.TabularSmall)
                                }
                            }
                        }

                        if (index < ModelType.entries.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }

            if (uiState.config.modelType != ModelType.ANIME_ART) {
                Spacer(modifier = Modifier.height(16.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = uiState.config.faceEnhance,
                                role = Role.Switch,
                                onValueChange = { viewModel.setFaceEnhance(it) }
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Face,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Face restoration", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = if (uiState.detectedFaces > 0) {
                                    "${uiState.detectedFaces} face(s) ready for clarity pass"
                                } else {
                                    "Enhances portrait faces when detected"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = uiState.config.faceEnhance,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SpecimenCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = LabTextStyles.TabularStat,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
