package com.pixelforge.app.presentation.screen.about

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.pixelforge.app.BuildConfig
import com.pixelforge.app.core.constants.AppConstants
import com.pixelforge.app.presentation.theme.LabTextStyles
import com.pixelforge.app.presentation.theme.PrimaryContainer
import com.pixelforge.app.presentation.theme.PrimaryDark
import com.pixelforge.app.presentation.theme.SecondaryContainer
import com.pixelforge.app.presentation.theme.SecondaryDark
import com.pixelforge.app.presentation.theme.TextTertiary
import kotlinx.coroutines.launch

private data class CreditEntry(
    val name: String,
    val role: String,
    val url: String
)

private val openSourceCredits = listOf(
    CreditEntry(
        name = "Real-ESRGAN & ncnn-vulkan",
        role = "xinntao — Photo, anime, and text super-resolution weights",
        url = "https://github.com/xinntao/Real-ESRGAN"
    ),
    CreditEntry(
        name = "Tencent NCNN",
        role = "Tencent — High-performance Vulkan GPU & ARM neural runtime",
        url = "https://github.com/Tencent/ncnn"
    ),
    CreditEntry(
        name = "ONNX Runtime Mobile",
        role = "Microsoft — Qualcomm QNN HTP, NNAPI & XNNPACK execution",
        url = "https://github.com/microsoft/onnxruntime"
    ),
    CreditEntry(
        name = "MediaPipe Tasks Vision",
        role = "Google AI Edge — On-device BlazeFace detection pipeline",
        url = "https://github.com/google-ai-edge/mediapipe"
    ),
    CreditEntry(
        name = "GFPGAN v1.4",
        role = "TencentARC — Optional neural portrait face restoration",
        url = "https://github.com/TencentARC/GFPGAN"
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val appIconBitmap = remember(context) {
        runCatching {
            context.packageManager.getApplicationIcon(context.packageName)
                .toBitmap(144, 144)
                .asImageBitmap()
        }.getOrNull()
    }

    fun openExternalUrl(url: String) {
        if (!url.startsWith("https://")) return
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onFailure {
            scope.launch { snackbarHostState.showSnackbar("No browser available to open link") }
        }
    }

    fun openDeveloperEmail() {
        runCatching {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${AppConstants.DEVELOPER_EMAIL}")).apply {
                putExtra(Intent.EXTRA_SUBJECT, "PixelForge v${BuildConfig.VERSION_NAME} Feedback")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onFailure {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("Developer email", AppConstants.DEVELOPER_EMAIL))
            scope.launch {
                snackbarHostState.showSnackbar("Email copied: ${AppConstants.DEVELOPER_EMAIL}")
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("About PixelForge") },
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
            // Hero Darkroom Lab Plate
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Subtle safelight hairline accent bar at top of the plate
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        PrimaryDark.copy(alpha = 0.25f),
                                        PrimaryDark,
                                        SecondaryDark.copy(alpha = 0.7f)
                                    )
                                )
                            )
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            modifier = Modifier.size(72.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, PrimaryDark.copy(alpha = 0.55f))
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (appIconBitmap != null) {
                                    Image(
                                        bitmap = appIconBitmap,
                                        contentDescription = "PixelForge app icon",
                                        modifier = Modifier
                                            .size(64.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.AutoAwesome,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(34.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = AppConstants.APP_NAME,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Offline On-Device AI Darkroom",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = PrimaryContainer,
                            border = BorderStroke(1.dp, PrimaryDark.copy(alpha = 0.45f))
                        ) {
                            Text(
                                text = "v${BuildConfig.VERSION_NAME} • build ${BuildConfig.VERSION_CODE} • arm64-v8a",
                                style = LabTextStyles.TabularSmall,
                                color = PrimaryDark,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "100% local image upscaling and portrait restoration. No cloud uploads, no queues, and no telemetry.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextTertiary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Specimen specs strip (3 hairline cells)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                ) {
                    SpecCell("Backends", "QNN • Vulkan", Modifier.weight(1f))
                    VerticalDivider(
                        modifier = Modifier.fillMaxHeight(),
                        color = MaterialTheme.colorScheme.outline
                    )
                    SpecCell("Min OS", "Android 12+", Modifier.weight(0.95f))
                    VerticalDivider(
                        modifier = Modifier.fillMaxHeight(),
                        color = MaterialTheme.colorScheme.outline
                    )
                    SpecCell("License", "GPL-3.0", Modifier.weight(0.75f))
                }
            }

            Spacer(modifier = Modifier.height(22.dp))
            AboutSectionHeader("Developer")
            Spacer(modifier = Modifier.height(10.dp))

            // Developer / About Me Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier.size(48.dp),
                            shape = RoundedCornerShape(10.dp),
                            color = PrimaryContainer,
                            border = BorderStroke(1.dp, PrimaryDark.copy(alpha = 0.5f))
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "dA",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = PrimaryDark
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${AppConstants.DEVELOPER_NAME} (${AppConstants.DEVELOPER_HANDLE})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Android & On-Device AI Engineer",
                                style = MaterialTheme.typography.bodySmall,
                                color = SecondaryDark
                            )
                        }
                    }

                    Text(
                        text = "Crafting fast, offline-first mobile imaging tools powered by local neural accelerators instead of remote servers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    AboutLinkRow(
                        icon = Icons.Default.Person,
                        title = "GitHub profile",
                        subtitle = "github.com/dev-Aatif",
                        onClick = { openExternalUrl(AppConstants.URL_GITHUB_PROFILE) }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    AboutLinkRow(
                        icon = Icons.Default.Code,
                        title = "Source repository",
                        subtitle = "github.com/dev-Aatif/Pixel-Forge",
                        onClick = { openExternalUrl(AppConstants.URL_GITHUB_REPO) }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    AboutLinkRow(
                        icon = Icons.Default.NewReleases,
                        title = "Releases & changelog",
                        subtitle = "View published APKs and release notes",
                        onClick = { openExternalUrl(AppConstants.URL_GITHUB_RELEASES) }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    AboutLinkRow(
                        icon = Icons.Default.BugReport,
                        title = "Report a bug or idea",
                        subtitle = "Open an issue on GitHub",
                        onClick = { openExternalUrl(AppConstants.URL_GITHUB_ISSUES) }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    AboutLinkRow(
                        icon = Icons.Default.Email,
                        title = "Contact email",
                        subtitle = AppConstants.DEVELOPER_EMAIL,
                        onClick = ::openDeveloperEmail
                    )
                }
            }

            Spacer(modifier = Modifier.height(22.dp))
            AboutSectionHeader("Credits & Upstream AI")
            Spacer(modifier = Modifier.height(10.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column {
                    openSourceCredits.forEachIndexed { index, credit ->
                        AboutLinkRow(
                            icon = Icons.Default.Memory,
                            iconTint = SecondaryDark,
                            iconContainer = SecondaryContainer,
                            title = credit.name,
                            subtitle = credit.role,
                            onClick = { openExternalUrl(credit.url) }
                        )
                        if (index < openSourceCredits.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))
            AboutSectionHeader("License")
            Spacer(modifier = Modifier.height(10.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                AboutLinkRow(
                    icon = Icons.Default.Gavel,
                    title = "GNU General Public License v3.0",
                    subtitle = "Free & open-source software — view LICENSE on GitHub",
                    onClick = { openExternalUrl(AppConstants.URL_GITHUB_LICENSE) }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AboutSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground
    )
}

@Composable
private fun SpecCell(
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

@Composable
private fun AboutLinkRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    iconTint: Color = PrimaryDark,
    iconContainer: Color = PrimaryContainer,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(34.dp),
            shape = RoundedCornerShape(8.dp),
            color = iconContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = "Open link",
            tint = TextTertiary,
            modifier = Modifier.size(16.dp)
        )
    }
}
