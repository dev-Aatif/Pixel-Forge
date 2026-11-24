package com.pixelforge.app.presentation.screen.processing

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pixelforge.app.presentation.theme.LabTextStyles
import com.pixelforge.app.presentation.theme.TextTertiary

@Composable
fun ProcessingScreen(
    workId: String,
    onComplete: (recordId: Long) -> Unit,
    onCancel: () -> Unit,
    viewModel: ProcessingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showCancelConfirm by remember { mutableStateOf(false) }

    BackHandler(enabled = !uiState.isComplete && uiState.error == null) {
        showCancelConfirm = true
    }

    if (showCancelConfirm) {
        AlertDialog(
            onDismissRequest = { showCancelConfirm = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text("Cancel enhancement?") },
            text = { Text("Your image is currently being enhanced on device. Stop processing and return?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCancelConfirm = false
                        viewModel.cancelWork(workId)
                        onCancel()
                    }
                ) {
                    Text("Stop & return", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelConfirm = false }) {
                    Text("Keep running")
                }
            }
        )
    }

    LaunchedEffect(workId) {
        viewModel.observeWork(workId)
    }

    LaunchedEffect(uiState.isComplete, uiState.recordId) {
        if (uiState.isComplete && uiState.recordId != null) {
            onComplete(uiState.recordId!!)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            if (uiState.error != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = "Error",
                            modifier = Modifier.size(44.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Processing failed",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = uiState.error ?: "Unknown error",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = onCancel,
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Text("Return", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val animatedProgress by animateFloatAsState(
                        targetValue = uiState.progress,
                        label = "upscaleProgress"
                    )

                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier.size(152.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 4.dp,
                            trackColor = MaterialTheme.colorScheme.outline,
                            strokeCap = StrokeCap.Round
                        )
                        Text(
                            text = "${(animatedProgress * 100).toInt()}%",
                            style = LabTextStyles.TabularHero,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }

                    Spacer(modifier = Modifier.height(28.dp))

                    Text(
                        text = uiState.status,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "Processing locally on device",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary
                    )

                    Spacer(modifier = Modifier.height(40.dp))

                    if (!uiState.isComplete) {
                        OutlinedButton(
                            onClick = {
                                viewModel.cancelWork(workId)
                                onCancel()
                            },
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }
}
