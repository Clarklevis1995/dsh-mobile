package com.clarklevis.dsh.android.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.android.AndroidSharedStateHolder
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.shared.gateway.GatewayConnectionState
import com.clarklevis.dsh.shared.gateway.GatewayPairingPayloadParser
import com.clarklevis.dsh.shared.gateway.GatewayRuntimeState
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import kotlinx.coroutines.launch

@Composable
internal fun GatewayAuthenticationMenu(
    state: GatewayRuntimeState,
    onScan: () -> Unit,
    onManualEntry: () -> Unit,
    onDarkBackground: Boolean = true
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        GlassCircleButton(
            iconRes = R.drawable.ic_gateway_auth,
            description = appText("设备认证，%1\$s", gatewayConnectionTitle(state.connection)),
            onClick = { expanded = true },
            onDarkBackground = onDarkBackground
        )
        DshMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(248.dp).testTag("gateway-auth-menu"),
            offset = DpOffset(x = (-244).dp, y = 8.dp)) {
            AuthenticationMenuItem(
                title = appText("扫描二维码"),
                iconRes = R.drawable.ic_qr_viewfinder,
                testTag = "gateway-auth-scan"
            ) {
                expanded = false
                onScan()
            }
            AuthenticationMenuItem(
                title = appText("手动输入配对信息"),
                iconRes = R.drawable.ic_keyboard_outline,
                testTag = "gateway-auth-manual"
            ) {
                expanded = false
                onManualEntry()
            }
        }
    }
}

@Composable
private fun AuthenticationMenuItem(
    title: String,
    iconRes: Int,
    testTag: String,
    onClick: () -> Unit
) {
    DshMenuItem(
        text = {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium
            )
        },
        leadingIcon = {
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface)
            )
        },
        modifier = Modifier.height(58.dp).testTag(testTag),
        contentPadding = PaddingValues(horizontal = 22.dp),
        onClick = onClick
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ManualGatewayPairingSheet(
    stateHolder: AndroidSharedStateHolder,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val isDark = isSystemInDarkTheme()
    val background = if (isDark) MaterialTheme.colorScheme.background else Color(0xFFF2F2F7)
    var pairingText by rememberSaveable { mutableStateOf("") }
    var validationError by rememberSaveable { mutableStateOf<String?>(null) }
    var didAttemptConnection by rememberSaveable { mutableStateOf(false) }
    val hosts = (LocalContext.current.applicationContext as? com.clarklevis.dsh.android.DshAndroidApplication)?.hosts
    val pairingState = if (hosts != null && didAttemptConnection) {
        GatewayRuntimeState(
            connection = if (hosts.pairing) GatewayConnectionState.CONNECTING else GatewayConnectionState.DISCONNECTED
        )
    } else stateHolder.gatewayState
    val isConnecting = pairingState.connection in setOf(
        GatewayConnectionState.CONNECTING,
        GatewayConnectionState.AUTHENTICATING,
        GatewayConnectionState.WAITING_FOR_NETWORK
    )
    val canSubmit = pairingText.isNotBlank() && !isConnecting
    val canUsePrimaryAction = isConnecting || canSubmit
    val dismissWithAnimation = {
        coroutineScope.launch {
            sheetState.hide()
            onDismiss()
        }
        Unit
    }

    ModalBottomSheet(
        modifier = Modifier.adaptiveSheetBounds(),
        sheetMaxWidth = adaptiveSheetWidth(),
        onDismissRequest = dismissWithAnimation,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 38.dp, topEnd = 38.dp),
        containerColor = background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        scrimColor = Color.Black.copy(alpha = 0.22f),
        dragHandle = null
    ) {
        DshMenuColumnHost {
            Column(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f)
                    .navigationBarsPadding().imePadding()
                    .testTag("gateway-manual-sheet")
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 22.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(appText("设备认证"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Surface(
                        modifier = Modifier.align(Alignment.CenterEnd).height(48.dp)
                            .clickable(role = Role.Button, onClick = dismissWithAnimation),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                        shadowElevation = 0.dp
                    ) {
                        Box(Modifier.padding(horizontal = 19.dp), contentAlignment = Alignment.Center) {
                            Text(appText("完成"), fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                        .padding(horizontal = 22.dp, vertical = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(appText("手动输入配对信息"), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(
                            appText("粘贴 Harness WebUI 提供的 Base64URL 配对字符串。长期设备 token 仍只会安全保存到 Android Keystore。"),
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.52f),
                            fontSize = 15.sp,
                            lineHeight = 21.sp
                        )
                    }

                    PairingTextEditor(
                        value = pairingText,
                        onValueChange = {
                            pairingText = it
                            validationError = null
                        }
                    )

                    PairingResultCard(
                        presentation = pairingResultPresentation(
                            state = pairingState,
                            didAttemptConnection = didAttemptConnection,
                            validationError = validationError
                        )
                    )

                    Surface(
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                            .clickable(enabled = canUsePrimaryAction, role = Role.Button) {
                                if (isConnecting) {
                                    if (hosts?.pairing == true) {
                                        hosts.cancelPairing()
                                    } else {
                                        stateHolder.disconnect()
                                    }
                                } else {
                                    validationError = null
                                    didAttemptConnection = true
                                    val trimmed = pairingText.trim()
                                    val validation = runCatching {
                                        GatewayPairingPayloadParser.parse(trimmed, System.currentTimeMillis())
                                    }
                                    if (validation.isFailure) {
                                        validationError = validation.exceptionOrNull()?.message ?: appText("配对信息无效")
                                    } else {
                                        stateHolder.pair(
                                            payload = trimmed,
                                            reportFailureGlobally = false,
                                            onFailure = { validationError = it }
                                        )
                                    }
                                }
                            }
                            .testTag("gateway-manual-connect"),
                        shape = RoundedCornerShape(16.dp),
                        color = DshColors.Ocean.copy(alpha = if (canUsePrimaryAction) 1f else 0.45f)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isConnecting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(19.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Image(
                                    painter = painterResource(R.drawable.ic_link),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    colorFilter = ColorFilter.tint(Color.White)
                                )
                            }
                            Spacer(Modifier.width(9.dp))
                            Text(
                                if (isConnecting) {
                                    appText("取消连接")
                                } else if (stateHolder.gatewayState.connection == GatewayConnectionState.CONNECTED) {
                                    appText("重新配对并连接")
                                } else {
                                    appText("连接")
                                },
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun PairingTextEditor(value: String, onValueChange: (String) -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().heightIn(min = 210.dp).clip(shape)
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.025f), shape)
            .border(
                0.8.dp,
                MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f),
                shape
            )
            .padding(16.dp)
            .testTag("gateway-pairing-input"),
        textStyle = TextStyle(
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.88f),
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontFamily = FontFamily.Monospace
        ),
        cursorBrush = SolidColor(DshColors.Ocean),
        decorationBox = { input ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                if (value.isEmpty()) {
                    Text(
                        MANUAL_PAIRING_PLACEHOLDER,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                input()
            }
        }
    )
}

private data class PairingResultPresentation(
    val title: String,
    val detail: String,
    val color: Color,
    val iconRes: Int
)

@Composable
private fun pairingResultPresentation(
    state: GatewayRuntimeState,
    didAttemptConnection: Boolean,
    validationError: String?
): PairingResultPresentation {
    if (validationError != null) {
        return PairingResultPresentation(
            appText("配对信息无效"),
            appText(validationError),
            MaterialTheme.colorScheme.error,
            R.drawable.ic_permission_warning
        )
    }
    if (!didAttemptConnection) {
        return PairingResultPresentation(
            gatewayConnectionTitle(state.connection),
            appText("输入 Base64URL 配对字符串后点击连接，结果会显示在这里。"),
            MaterialTheme.colorScheme.onBackground.copy(alpha = 0.50f),
            R.drawable.ic_gateway_auth
        )
    }
    return when (state.connection) {
        GatewayConnectionState.CONNECTING,
        GatewayConnectionState.AUTHENTICATING,
        GatewayConnectionState.WAITING_FOR_NETWORK -> PairingResultPresentation(
            appText("正在连接"),
            appText("正在提交一次性配对码并等待 Mobile Gateway 完成设备鉴权…"),
            DshColors.Amber,
            R.drawable.ic_link
        )
        GatewayConnectionState.CONNECTED -> PairingResultPresentation(
            appText("连接成功"),
            appText("设备鉴权已完成，长期 token 已安全保存到 Android Keystore。"),
            DshColors.Success,
            R.drawable.ic_menu_check
        )
        GatewayConnectionState.FAILED -> PairingResultPresentation(
            appText("连接失败"),
            pairingFailureDetail(state.lastError),
            MaterialTheme.colorScheme.error,
            R.drawable.ic_permission_warning
        )
        GatewayConnectionState.DISCONNECTED,
        GatewayConnectionState.SUSPENDED -> PairingResultPresentation(
            appText("未连接"),
            appText("请检查配对信息后重新连接。"),
            MaterialTheme.colorScheme.onBackground.copy(alpha = 0.50f),
            R.drawable.ic_gateway_auth
        )
    }
}

private fun pairingFailureDetail(error: String?): String = when (error) {
    "connection-timeout" -> appText("连接超时，请检查网络或配对信息后重试。")
    "network-lost" -> appText("网络不可用，请恢复网络后重新连接。")
    else -> error?.let { appText(it) } ?: appText("请检查配对信息后重新连接。")
}

@Composable
private fun PairingResultCard(presentation: PairingResultPresentation) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(presentation.color.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
            .border(0.8.dp, presentation.color.copy(alpha = 0.18f), RoundedCornerShape(18.dp))
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp),
        verticalAlignment = Alignment.Top
    ) {
        Image(
            painter = painterResource(presentation.iconRes),
            contentDescription = null,
            modifier = Modifier.size(26.dp).padding(2.dp),
            colorFilter = ColorFilter.tint(presentation.color)
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(presentation.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(
                presentation.detail,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.52f),
                fontSize = 15.sp,
                lineHeight = 20.sp
            )
        }
    }
}

@Composable
internal fun GatewayQrScannerScreen(
    onCode: (String) -> Unit,
    onCancel: () -> Unit,
    onFailure: (String) -> Unit
) {
    BackHandler(onBack = onCancel)
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (!granted) {
            onFailure(appText("未获得相机权限。请在系统设置中允许 DeepSeek Harness 使用相机后重试。"))
        }
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).testTag("gateway-qr-scanner")) {
        val scanSize = minOf(360.dp, (maxWidth - 44.dp).coerceAtLeast(0.dp), maxHeight * 0.40f)
        if (hasPermission) {
            GatewayCameraPreview(onCode = onCode, onFailure = onFailure)
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to Color.Black.copy(alpha = 0.58f),
                        0.48f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.72f)
                    )
                )
            )
        )
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(top = 18.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(appText("扫描设备配对码"), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(
                        appText("请扫描 Harness WebUI 生成的二维码"),
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 15.sp
                    )
                }
                Spacer(Modifier.width(12.dp))
                Surface(
                    modifier = Modifier.size(44.dp).clickable(role = Role.Button, onClick = onCancel)
                        .semantics { contentDescription = appText("取消扫描") },
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.16f),
                    border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.22f))
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Image(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            colorFilter = ColorFilter.tint(Color.White)
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier.size(scanSize).testTag("gateway-scan-frame")
                    .border(3.dp, Color.White, RoundedCornerShape(28.dp)),
                contentAlignment = Alignment.BottomCenter
            ) {
                Surface(
                    modifier = Modifier.offset(y = 58.dp),
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.18f),
                    border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.18f))
                ) {
                    Text(
                        appText("将二维码完整放入框内"),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun GatewayCameraPreview(onCode: (String) -> Unit, onFailure: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val hapticFeedback = LocalHapticFeedback.current
    val barcodeScanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        )
    }
    val controller = remember(context) {
        LifecycleCameraController(context).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        }
    }
    var didFinish by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, controller, barcodeScanner) {
        val analyzer = MlKitAnalyzer(
            listOf(barcodeScanner),
            ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED,
            mainExecutor
        ) { result ->
            if (didFinish) return@MlKitAnalyzer
            val value = result?.getValue(barcodeScanner)
                ?.firstNotNullOfOrNull { barcode -> barcode.rawValue?.takeIf(String::isNotBlank) }
            if (value != null) {
                didFinish = true
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                onCode(value)
            }
        }
        runCatching {
            controller.bindToLifecycle(lifecycleOwner)
            controller.setImageAnalysisAnalyzer(mainExecutor, analyzer)
        }.onFailure {
            if (!didFinish) {
                didFinish = true
                onFailure(appText("相机启动失败，请稍后重试。"))
            }
        }
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            barcodeScanner.close()
        }
    }

    AndroidView(
        factory = { previewContext ->
            PreviewView(previewContext).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                this.controller = controller
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

internal fun gatewayConnectionTitle(state: GatewayConnectionState): String = when (state) {
    GatewayConnectionState.CONNECTED -> appText("已连接")
    GatewayConnectionState.CONNECTING,
    GatewayConnectionState.AUTHENTICATING -> appText("正在连接")
    GatewayConnectionState.WAITING_FOR_NETWORK -> appText("等待网络")
    GatewayConnectionState.FAILED -> appText("连接失败")
    GatewayConnectionState.DISCONNECTED,
    GatewayConnectionState.SUSPENDED -> appText("未连接")
}

private const val MANUAL_PAIRING_PLACEHOLDER =
    "eyJ2ZXJzaW9uIjoyLCJwdWJsaWNVcmwiOiJ3c3M6Ly9nYXRld2F5LmV4YW1wbGUuY29tL3dzL21vYmlsZSIsLi4ufQ"
