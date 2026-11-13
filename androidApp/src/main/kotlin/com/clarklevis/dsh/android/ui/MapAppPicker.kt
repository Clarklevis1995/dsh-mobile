package com.clarklevis.dsh.android.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.clarklevis.dsh.android.R
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.android.localization.appTextKey
import com.clarklevis.dsh.shared.facade.SharedMapDestination
import com.clarklevis.dsh.shared.facade.SharedMapLinks

/** 每个插件只能打开编译期登记的应用，消息内容不能指定包名或任意 Intent。 */
internal data class AndroidMapAppPlugin(val id: String, val packageName: String, val title: String) {
    fun intent(destination: SharedMapDestination): Intent? = SharedMapLinks().let { it.openURL(it.navigationDestination(destination), id, "android") }?.let {
        Intent(Intent.ACTION_VIEW, Uri.parse(it)).setPackage(packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    fun installed(context: Context, destination: SharedMapDestination): Boolean =
        intent(destination)?.resolveActivity(context.packageManager) != null
}

internal val androidMapPlugins = listOf(
    AndroidMapAppPlugin("amap", "com.autonavi.minimap", appTextKey("高德地图")),
    AndroidMapAppPlugin("baidu", "com.baidu.BaiduMap", appTextKey("百度地图")),
    AndroidMapAppPlugin("google", "com.google.android.apps.maps", appTextKey("谷歌地图"))
)

/** Figma 的中性系统色，不继承 Material 的 surface tint。 */
internal data class MapPickerPalette(
    val background: Color, val row: Color, val ink: Color, val secondary: Color,
    val separator: Color, val close: Color, val handle: Color
)

internal fun mapPickerPalette(dark: Boolean) = if (dark) MapPickerPalette(
    Color(0xFF000000), Color(0xFF1C1C1E), Color(0xFFF2F2F7), Color(0xFF98989F),
    Color(0xFF38383A), Color(0xFF2C2C2E), Color(0xFF5C5C62)
) else MapPickerPalette(
    Color(0xFFF2F2F7), Color.White, Color(0xFF171A20), Color(0xFF747880),
    Color(0xFFE8E8ED), Color(0xFFE6E6EB), Color(0xFFC3C4CA)
)

internal val AndroidMapAppPlugin.iconResource get() = when (id) {
    "amap" -> R.drawable.map_app_amap
    "baidu" -> R.drawable.map_app_baidu
    else -> R.drawable.map_app_google
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MapAppPicker(destination: SharedMapDestination, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var apps by remember(destination) { mutableStateOf(androidMapPlugins.filter { it.installed(context, destination) }) }
    var failed by remember { mutableStateOf(false) }
    DisposableEffect(lifecycle, destination) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) apps = androidMapPlugins.filter { it.installed(context, destination) }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    MapAppPickerSheet(destination, apps, failed, onDismiss,
        onApp = { plugin ->
            val intent = plugin.intent(destination)
            failed = intent == null || runCatching { context.startActivity(intent) }.isFailure
            if (!failed) onDismiss()
        }, onWeb = {
            val url = SharedMapLinks().let { it.browserURL(it.navigationDestination(destination)) }
            failed = url == null || runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isFailure
            if (!failed) onDismiss()
        })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MapAppPickerSheet(
    destination: SharedMapDestination, apps: List<AndroidMapAppPlugin>, failed: Boolean = false,
    onDismiss: () -> Unit, onApp: (AndroidMapAppPlugin) -> Unit, onWeb: () -> Unit
) {
    val palette = mapPickerPalette(MaterialTheme.colorScheme.background.luminance() < .5f)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        containerColor = palette.background, contentColor = palette.ink, tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = .32f),
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top) },
        dragHandle = {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 16.dp).testTag("map-picker-handle"), contentAlignment = Alignment.Center) {
                Box(Modifier.size(36.dp, 5.dp).background(palette.handle, CircleShape))
            }
        }
    ) {
        DshMenuHost {
            MapAppPickerContent(destination, apps, palette, failed, onDismiss, onApp, onWeb)
        }
    }
}

/** 无平台跳转副作用的展示层，行和字体可随辅助字号扩展。 */
@Composable
internal fun MapAppPickerContent(
    destination: SharedMapDestination, apps: List<AndroidMapAppPlugin>, palette: MapPickerPalette,
    failed: Boolean = false, onDismiss: () -> Unit, onApp: (AndroidMapAppPlugin) -> Unit, onWeb: () -> Unit
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).background(palette.background)
        .padding(horizontal = 20.dp).navigationBarsPadding().testTag("map-app-picker")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).testTag("map-picker-header"), verticalAlignment = Alignment.CenterVertically) {
            Text(appText("选择地图应用"), Modifier.weight(1f), fontSize = 21.sp, lineHeight = 30.sp,
                fontWeight = FontWeight.Medium, color = palette.ink)
            Box(Modifier.size(32.dp).background(palette.close, CircleShape)
                .clickable(role = Role.Button, onClickLabel = appText("关闭"), onClick = onDismiss)
                .testTag("map-picker-close"), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_close), appText("关闭"), Modifier.size(18.dp), tint = palette.secondary)
            }
        }
        Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(destination.name.ifBlank { appText("地图地点") }, fontSize = 17.sp, lineHeight = 25.sp,
                fontWeight = FontWeight.Medium, color = palette.ink)
            val transport = when (destination.transport) { "walking" -> appText("步行"); "transit" -> appText("公交"); else -> appText("驾车") }
            Text(appText("从当前位置出发 · %1\$s", transport), fontSize = 13.sp, lineHeight = 19.sp, color = palette.secondary)
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(palette.row).testTag("map-picker-apps")) {
            apps.forEachIndexed { index, plugin ->
                Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(role = Role.Button) { onApp(plugin) }
                    .padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 14.dp).testTag("map-provider-${plugin.id}"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Image(painterResource(plugin.iconResource), null, Modifier.size(44.dp).clip(RoundedCornerShape(11.dp))
                        .testTag("map-icon-${plugin.id}"), contentScale = ContentScale.Fit)
                    Text(appText(plugin.title), Modifier.weight(1f), fontSize = 17.sp, lineHeight = 22.sp, color = palette.ink)
                    Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(18.dp), tint = Color(0xFFA1A3AA))
                }
                if (index != apps.lastIndex) Box(Modifier.padding(start = 74.dp).fillMaxWidth().height(.5.dp).background(palette.separator))
            }
            if (apps.isEmpty()) Text(appText("未检测到可用地图应用"), Modifier.padding(16.dp), color = palette.secondary)
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 59.dp).clickable(role = Role.Button, onClick = onWeb)
            .testTag("map-provider-browser"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Icon(painterResource(R.drawable.ic_map_globe), null, Modifier.size(19.dp), tint = DshColors.Ocean)
            Spacer(Modifier.width(10.dp))
            Text(appText("使用网页地图"), fontSize = 14.sp, lineHeight = 20.sp, color = DshColors.Ocean)
        }
        if (failed) Text(appText("无法打开地图，请重新选择"), Modifier.padding(bottom = 12.dp), color = palette.ink)
    }
}
