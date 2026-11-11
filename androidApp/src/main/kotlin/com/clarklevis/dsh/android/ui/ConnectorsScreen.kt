package com.clarklevis.dsh.android.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.Settings
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.android.platform.devicetools.AndroidDeviceConnectors
import com.clarklevis.dsh.android.platform.devicetools.ConnectorRegistrationState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ConnectorsContent(
    connectors: AndroidDeviceConnectors?,
    onBack: () -> Unit,
    onOpenStatus: () -> Unit
) {
    var enabling by remember { mutableStateOf<String?>(null) }
    var permissionDenied by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val module = enabling; enabling = null
        if (module != null && connectors?.available(module) == true) connectors.setEnabled(module, true)
        else permissionDenied = true
    }
    ConnectorRegistrationPolling(connectors)
    ConnectorPage(appText("连接器"), onBack, "connectors-screen") {
        ConnectorGroup {
            Row(Modifier.fillMaxWidth().clickable(onClick = onOpenStatus).padding(18.dp)
                .testTag("connector-registration-link").semantics { stateDescription = registrationTitle(connectors?.registrationState ?: ConnectorRegistrationState.DISCONNECTED) },
                verticalAlignment = Alignment.CenterVertically) {
                Text(appText("连接器状态"), Modifier.weight(1f))
                StatusIndicatorDot(registrationColor(connectors?.registrationState), Modifier.size(8.dp))
                Spacer(Modifier.width(12.dp))
                Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(appText("本机能力"), Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        ConnectorGroup {
            (connectors?.modules ?: listOf("calendar", "reminders", "location", "photos", "music", "alarms")).forEachIndexed { index, module ->
                if (index > 0) HorizontalDivider(Modifier.padding(start = 66.dp, end = 18.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    ConnectorIcon(module)
                    Spacer(Modifier.width(14.dp))
                    Text(connectorModuleTitle(module), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    DshSwitch(checked = connectors?.isOn(module) == true, enabled = connectors?.supported(module) == true && !connectors.syncing && enabling == null,
                        onCheckedChange = { value ->
                            if (connectors != null) {
                                val missing = connectors.platform.permissions(module).filter {
                                    ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
                                }
                                if (!value || missing.isEmpty()) connectors.setEnabled(module, value)
                                else { enabling = module; permission.launch(connectors.platform.permissions(module)) }
                            }
                        }, modifier = Modifier.testTag("connector-toggle-$module").semantics { contentDescription = connectorModuleTitle(module) })
                }
            }
        }
        ConnectorGroup {
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }, Modifier.fillMaxWidth()) {
                Text(appText("打开系统权限设置"), Modifier.fillMaxWidth().padding(8.dp))
            }
            HorizontalDivider(Modifier.padding(horizontal = 18.dp))
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }, Modifier.fillMaxWidth()) {
                Text(appText("音乐播放控制"), Modifier.fillMaxWidth().padding(8.dp))
            }
        }
    }
    if (permissionDenied) AlertDialog(onDismissRequest = { permissionDenied = false }, title = { Text(appText("连接器")) },
        text = { Text(appText("系统权限未允许，请在设置中检查。")) }, confirmButton = { TextButton(onClick = { permissionDenied = false }) { Text(appText("好")) } })
}

@Composable
internal fun ConnectorStatusScreen(connectors: AndroidDeviceConnectors?, onBack: () -> Unit) {
    ConnectorRegistrationPolling(connectors)
    ConnectorPage(appText("连接器状态"), onBack, "connector-status-screen") {
        ConnectorRegistrationContent(connectors)
    }
}

@Composable
private fun ConnectorRegistrationPolling(connectors: AndroidDeviceConnectors?) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(connectors, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { connectors?.refreshRegistrationStatus(); delay(10_000) }
        }
    }
}

@Composable
private fun ConnectorPage(
    title: String,
    onBack: () -> Unit,
    tag: String,
    content: @Composable ColumnScope.() -> Unit
) {
    val background = if (isSystemInDarkTheme()) Color.Black else Color(0xFFF2F2F7)
    DshMenuHost {
        Surface(color = background, contentColor = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxSize().testTag(tag)) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Box(Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 16.dp)) {
                    TopBarCircleButton(R.drawable.ic_back_chevron, appText("返回"), onBack, Modifier.align(Alignment.CenterStart))
                    Text(title, Modifier.align(Alignment.Center), style = MaterialTheme.typography.titleMedium)
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Spacer(Modifier.height(4.dp))
                    content()
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun ConnectorGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = if (isSystemInDarkTheme()) Color(0xFF1C1C1E) else Color.White,
        contentColor = MaterialTheme.colorScheme.onSurface) { Column(Modifier.fillMaxWidth(), content = content) }
}

internal fun connectorModuleTitle(module: String) = appText(when (module) {
    "calendar" -> "日历"; "reminders" -> "提醒事项"; "location" -> "位置"; "photos" -> "照片"
    "music" -> "音乐"; "alarms" -> "闹钟"; else -> module
})

@Composable
private fun ConnectorIcon(module: String) {
    val (icon, color) = when (module) {
        "calendar" -> R.drawable.ic_connector_calendar to Color(0xFFE34D59)
        "reminders" -> R.drawable.ic_connector_reminders to DshColors.Ocean
        "location" -> R.drawable.ic_connector_location to Color(0xFF34A853)
        "photos" -> R.drawable.ic_connector_photos to DshColors.Orange
        "music" -> R.drawable.ic_connector_music to Color(0xFFDE4162)
        else -> R.drawable.ic_connector_alarm to DshColors.Orange
    }
    Surface(shape = RoundedCornerShape(9.dp), color = color.copy(alpha = 0.1f)) {
        Icon(painterResource(icon), null, Modifier.padding(6.dp).size(26.dp), tint = color)
    }
}

private fun registrationColor(state: ConnectorRegistrationState?): Color = when (state) {
    ConnectorRegistrationState.REGISTERED -> DshColors.Success
    ConnectorRegistrationState.FAILED, ConnectorRegistrationState.TIMED_OUT, ConnectorRegistrationState.NOT_REGISTERED -> Color(0xFFE34D59)
    ConnectorRegistrationState.REGISTERING, ConnectorRegistrationState.UNVERIFIED, ConnectorRegistrationState.UNSUPPORTED -> DshColors.Orange
    else -> Color.Gray
}

internal fun registrationTitle(state: ConnectorRegistrationState) = appText(when (state) {
    ConnectorRegistrationState.DISCONNECTED -> "网关未连接"
    ConnectorRegistrationState.UNSUPPORTED -> "网关不支持连接器"
    ConnectorRegistrationState.REGISTERING -> "正在注册连接器"
    ConnectorRegistrationState.REGISTERED -> "网关已注册"
    ConnectorRegistrationState.UNVERIFIED -> "注册状态未确认"
    ConnectorRegistrationState.FAILED -> "连接器注册失败"
    ConnectorRegistrationState.TIMED_OUT -> "连接器注册超时"
    ConnectorRegistrationState.NOT_REGISTERED -> "已连接，连接器未注册"
})

@Composable
private fun ConnectorRegistrationContent(connectors: AndroidDeviceConnectors?) {
    val state = connectors?.registrationState ?: ConnectorRegistrationState.DISCONNECTED
    ConnectorGroup {
        Row(Modifier.fillMaxWidth().padding(18.dp).testTag("connector-registration-status"), verticalAlignment = Alignment.CenterVertically) {
            StatusIndicatorDot(registrationColor(state), Modifier.size(8.dp))
            Spacer(Modifier.width(10.dp)); Text(registrationTitle(state), Modifier.weight(1f))
            if (connectors?.checkingRegistration == true || state == ConnectorRegistrationState.REGISTERING) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        if (state == ConnectorRegistrationState.REGISTERED && connectors != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
                Text(appText("网关已确认"), Modifier.weight(1f)); Text(appText("%1\$s 项能力", connectors.registeredToolCount))
            }
            connectors.registrationConfirmedAt?.let { at ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
                    Text(appText("最近确认"), Modifier.weight(1f)); Text(DateFormat.getTimeInstance().format(Date(at)), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            val message = appText(when (state) {
                ConnectorRegistrationState.DISCONNECTED -> "连接主机后，将显示网关确认的能力。"
                ConnectorRegistrationState.UNSUPPORTED -> "请更新并重启主机端网关，然后重新连接。"
                ConnectorRegistrationState.REGISTERING -> "正在等待网关确认本机能力。"
                ConnectorRegistrationState.NOT_REGISTERED -> "聊天连接正常，但网关没有当前连接的能力登记。请重新注册。"
                else -> "网关尚未返回有效的能力清单，请刷新状态或重新注册。"
            })
            Text(message, Modifier.padding(horizontal = 18.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { connectors?.refreshRegistrationStatus() }, enabled = connectors?.canCheckRegistration == true && !connectors.checkingRegistration,
                modifier = Modifier.testTag("connector-refresh-registration")) { Text(appText("刷新状态")) }
            TextButton(onClick = { connectors?.register() }, enabled = connectors?.canRetryRegistration == true,
                modifier = Modifier.testTag("connector-retry-registration")) { Text(appText("重新注册")) }
        }
    }
    if (state == ConnectorRegistrationState.REGISTERED && connectors != null) {
        Spacer(Modifier.height(12.dp))
        Text(appText("网关已登记的能力"), Modifier.padding(start = 16.dp, bottom = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        ConnectorGroup {
            connectors.registeredModules.forEachIndexed { index, module ->
                var expanded by rememberSaveable(module.id) { mutableStateOf(false) }
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 18.dp))
                Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(18.dp).testTag("connector-registered-module-${module.id}"),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(connectorModuleTitle(module.id), Modifier.weight(1f))
                    Text(appText(when {
                        !module.available -> "设备未授权或不支持"
                        !module.enabled || connectors.enabled[module.id] != true -> "手机未开启"
                        connectors.hostPermissions[module.id] != true -> "主机未允许"
                        module.tools.isEmpty() -> "网关未接入"
                        else -> "已允许"
                    }), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(painterResource(if (expanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right), null, Modifier.padding(start = 8.dp).size(18.dp))
                }
                if (expanded) module.tools.forEach { tool ->
                    Text(connectorCapabilityTitle(tool), Modifier.padding(start = 20.dp, end = 18.dp, bottom = 12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

internal fun connectorCapabilityTitle(tool: String): String = appText(when (tool) {
    "calendar.busy" -> "查询日历忙闲"; "calendar.create" -> "创建日历日程"
    "reminders.list" -> "查询 Whaleness 提醒"; "reminders.create" -> "新建 Whaleness 提醒"
    "location.current" -> "获取近似位置"; "location.precise" -> "获取精确位置"
    "maps.geocode" -> "解析地址"; "maps.reverse-geocode" -> "查询坐标地址"; "maps.open" -> "打开地图应用"
    "maps.markers.create" -> "保存应用内地点"; "maps.markers.list" -> "查询应用内地点"
    "maps.markers.update" -> "修改应用内地点"; "maps.markers.delete" -> "删除应用内地点"
    "photos.select" -> "选择照片"; "music.search" -> "搜索本机音乐"; "music.library" -> "浏览本机音频库"
    "music.play-library" -> "打开本机歌曲"; "music.play" -> "播放音乐"; "music.pause" -> "暂停音乐"
    "music.seek" -> "调整播放进度"; "music.state" -> "读取当前播放器"
    "alarms.open-create" -> "设置系统闹钟"
    else -> tool
})

@Composable
internal fun ConnectorRequestHost(connectors: AndroidDeviceConnectors?) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(connectors, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) connectors?.visibility(true)
            else if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_DESTROY) connectors?.visibility(false)
        }
        lifecycle.addObserver(observer)
        connectors?.visibility(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose { lifecycle.removeObserver(observer); connectors?.visibility(false) }
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var photoInvocation by remember { mutableStateOf<String?>(null) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val expected = photoInvocation; photoInvocation = null
        if (expected == null || expected != connectors?.pending?.optString("invocationId")) return@rememberLauncherForActivityResult
        if (uri == null) connectors.photoSelected(null)
        else scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                    require(options.outWidth > 0 && options.outHeight > 0)
                    options.inJustDecodeBounds = false
                    while (maxOf(options.outWidth, options.outHeight) / maxOf(1, options.inSampleSize) > 768) options.inSampleSize = maxOf(1, options.inSampleSize) * 2
                    val decoded = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: error("decode")
                    val exif = runCatching { context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) } }.getOrNull()
                    val matrix = Matrix().apply {
                        if (exif?.isFlipped == true) postScale(-1f, 1f)
                        postRotate((exif?.rotationDegrees ?: 0).toFloat())
                    }
                    val bitmap = if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { if (it !== decoded) decoded.recycle() }
                    val output = ByteArrayOutputStream()
                    try {
                        for (quality in listOf(65, 45, 25, 10)) {
                            output.reset()
                            check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output))
                            if (output.size() <= 64 * 1024) break
                        }
                        require(output.size() <= 64 * 1024)
                        JSONObject().put("images", JSONArray().put(JSONObject().put("mediaType", "image/jpeg").put("data", Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)).put("width", bitmap.width).put("height", bitmap.height)))
                    } finally { bitmap.recycle() }
                }.getOrNull()
            }
            if (expected == connectors.pending?.optString("invocationId")) connectors.photoSelected(result)
        }
    }
    LaunchedEffect(connectors?.selectingPhoto) {
        if (connectors?.selectingPhoto == true) {
            photoInvocation = connectors.pending?.optString("invocationId")
            photoPicker.launch("image/*")
        }
    }
}
