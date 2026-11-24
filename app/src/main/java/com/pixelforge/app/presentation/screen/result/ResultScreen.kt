package com.pixelforge.app.presentation.screen.result

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pixelforge.app.core.extensions.formatFileSize
import com.pixelforge.app.presentation.components.BeforeAfterSlider
import com.pixelforge.app.presentation.theme.LabTextStyles
import com.pixelforge.app.presentation.theme.TextTertiary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(
    recordId: Long,
    onBack: () -> Unit,
    onHome: () -> Unit,
    viewModel: ResultViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var zoomLevel by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(recordId) {
        viewModel.loadResult(recordId)
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Compare result") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = onHome) {
                            Icon(Icons.Default.Home, contentDescription = "Home")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                        actionIconContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                uiState.error != null -> {
                    Text(
                        text = uiState.error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                uiState.beforeBitmap != null && uiState.afterBitmap != null && uiState.record != null -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Inspection Loupe Well
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outline,
                                    RoundedCornerShape(10.dp)
                                )
                        ) {
                            BeforeAfterSlider(
                                beforeImage = uiState.beforeBitmap!!,
                                afterImage = uiState.afterBitmap!!,
                                modifier = Modifier.fillMaxSize(),
                                onZoomChanged = { zoomLevel = it }
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp)
                                    .align(Alignment.TopCenter),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                LoupeTag("Before")
                                LoupeTag("After")
                            }

                            LoupeTag(
                                text = if (zoomLevel > 1.05f) {
                                    "${"%.1f".format(zoomLevel)}× loupe • Double-tap to reset"
                                } else {
                                    "Drag divider • Pinch or double-tap to zoom"
                                },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(10.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Hairline Inspection Ledger (2x2 tabular readout)
                        val record = uiState.record!!
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(IntrinsicSize.Min)
                                ) {
                                    LedgerCell(
                                        label = "Resolution",
                                        value = "${record.outputWidth}×${record.outputHeight}",
                                        modifier = Modifier.weight(1f)
                                    )
                                    VerticalDivider(
                                        modifier = Modifier.fillMaxHeight(),
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                    LedgerCell(
                                        label = "Time",
                                        value = "${"%.1f".format(record.processingTimeMs / 1000.0)}s",
                                        modifier = Modifier.weight(0.75f)
                                    )
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(IntrinsicSize.Min)
                                ) {
                                    LedgerCell(
                                        label = "Model & Engine",
                                        value = "${record.modelType.displayName} (${record.backendUsed})",
                                        valueColor = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    VerticalDivider(
                                        modifier = Modifier.fillMaxHeight(),
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                    LedgerCell(
                                        label = "File size",
                                        value = record.fileSizeBytes.formatFileSize(),
                                        modifier = Modifier.weight(0.75f)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = onHome,
                                modifier = Modifier
                                    .weight(0.9f)
                                    .defaultMinSize(minHeight = 50.dp),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Text("Home")
                            }

                            Button(
                                onClick = {
                                    record.outputUri?.let { uriString ->
                                        val uri = Uri.parse(uriString)
                                        val mime = context.contentResolver.getType(uri) ?: "image/png"
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            clipData = ClipData.newRawUri("", uri)
                                            type = mime
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        runCatching {
                                            context.startActivity(
                                                Intent.createChooser(shareIntent, "Share enhanced image")
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .weight(1.3f)
                                    .defaultMinSize(minHeight = 50.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Icon(
                                    Icons.Default.Share,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Share image",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Saved automatically to Pictures/PixelForge",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextTertiary,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoupeTag(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = LabTextStyles.TabularSmall,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.78f))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
private fun LedgerCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
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
            color = valueColor,
            maxLines = 2,
            softWrap = true,
            overflow = TextOverflow.Ellipsis
        )
    }
}
