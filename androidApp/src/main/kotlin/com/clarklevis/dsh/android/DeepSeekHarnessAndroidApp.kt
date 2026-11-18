package com.clarklevis.dsh.android

import com.clarklevis.dsh.android.ui.AdaptiveWindowHost
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.graphics.ImageBitmap
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.shared.SharedModuleInfo
import com.clarklevis.dsh.shared.domain.SessionSummary
import com.clarklevis.dsh.shared.facade.SharedMobileSnapshot
import com.clarklevis.dsh.shared.gateway.GatewayConnectionState
import com.clarklevis.dsh.shared.gateway.GatewayRuntimeState
import com.clarklevis.dsh.shared.projection.ConversationItem
import kotlinx.coroutines.flow.distinctUntilChanged
import com.clarklevis.dsh.android.ui.DshProductApp
import com.clarklevis.dsh.android.ui.DshTheme
import com.clarklevis.dsh.android.ui.rememberComposerCamera

@Composable
fun DeepSeekHarnessAndroidApp() {
    val context = LocalContext.current
    val application = context.applicationContext as DshAndroidApplication
    val hosts = application.hosts
    if (!hosts.ready) return
    val notificationRoute = application.pendingNotificationSession
    LaunchedEffect(notificationRoute, hosts.activeId) {
        if (notificationRoute != null && notificationRoute.gatewayId != hosts.activeId) {
            val profile = hosts.profiles.firstOrNull { it.localId == notificationRoute.gatewayId }
            if (profile != null) hosts.select(profile)
            else application.clearNotificationSession(notificationRoute)
        }
    }
    val stateHolder = hosts.activeGraph.stateHolder
    val imageOwner = remember { androidx.compose.runtime.mutableStateOf<Pair<AndroidSharedStateHolder, String?>?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(
        com.clarklevis.dsh.android.platform.AndroidImagePreprocessor.MAXIMUM_IMAGE_COUNT
    )) { uris ->
        imageOwner.value?.takeIf { it.first === stateHolder }?.let { (owner, sessionId) ->
            if (uris.isNotEmpty()) owner.prepareImages(uris, sessionId)
        }
        imageOwner.value = null
    }
    val fileOwner = remember { androidx.compose.runtime.mutableStateOf<Pair<AndroidSharedStateHolder, String?>?>(null) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        fileOwner.value?.takeIf { it.first === stateHolder }?.let { (owner, sessionId) ->
            if (uris.isNotEmpty()) owner.prepareFiles(uris, sessionId)
        }
        fileOwner.value = null
    }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    DshTheme {
        val takePhoto = rememberComposerCamera(stateHolder)
        Surface(modifier = Modifier.fillMaxSize()) {
            AdaptiveWindowHost {
                androidx.compose.runtime.key(stateHolder) {
                    DshProductApp(
                        stateHolder = stateHolder,
                        onPickImage = { imageOwner.value = stateHolder to stateHolder.snapshot.selectedSessionId; imagePicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onPickFile = { fileOwner.value = stateHolder to stateHolder.snapshot.selectedSessionId; filePicker.launch(arrayOf("*/*")) },
                        notificationRoute = notificationRoute?.takeIf { it.gatewayId == hosts.activeId },
                        onNotificationRouteOpened = application::clearNotificationSession,
                        onTakePhoto = takePhoto
                    )
                }
            }
            hosts.error?.let { message ->
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { hosts.error = null },
                    title = { Text(appText("主机连接")) },
                    text = { Text(appText(message)) },
                    confirmButton = { androidx.compose.material3.TextButton(onClick = { hosts.error = null }) { Text(appText("好")) } }
                )
            }
        }
    }
}

@Composable
internal fun SharedLogicScreen(
    snapshot: SharedMobileSnapshot,
    wirePayload: String,
    onWirePayloadChange: (String) -> Unit,
    onLoadFixture: () -> Unit,
    onSubmitWirePayload: () -> Unit,
    onSelectSession: (String) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    gatewayState: GatewayRuntimeState = GatewayRuntimeState(),
    endpoint: String = "",
    onEndpointChange: (String) -> Unit = {},
    pairingPayload: String = "",
    onPairingPayloadChange: (String) -> Unit = {},
    onConnect: () -> Unit = {},
    onPair: () -> Unit = {},
    onDisconnect: () -> Unit = {},
    onRefreshSessions: () -> Unit = {},
    messageDraft: String = "",
    onMessageDraftChange: (String) -> Unit = {},
    preparedImages: List<com.clarklevis.dsh.android.platform.AndroidPreparedImage> = emptyList(),
    onPickImage: () -> Unit = {},
    onRemoveImage: (Int) -> Unit = {},
    onSendMessage: () -> Unit = {},
    canSend: Boolean = false,
    platformError: String? = null,
    attachmentThumbnails: Map<String, ImageBitmap> = emptyMap(),
    attachmentStates: Map<String, AttachmentLoadState> = emptyMap(),
    onVisibleAttachmentIdsChange: (Set<String>) -> Unit = {},
    onThumbnailTargetSizeChange: (Int, Int) -> Unit = { _, _ -> },
    onRetryAttachment: (String) -> Unit = {}
) {
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val thumbnailWidthPixels = windowInfo.containerSize.width
    val thumbnailHeightPixels = with(density) { 240.dp.roundToPx() }
    LaunchedEffect(thumbnailWidthPixels, thumbnailHeightPixels) {
        onThumbnailTargetSizeChange(thumbnailWidthPixels, thumbnailHeightPixels)
    }
    val listState = rememberLazyListState()
    val conversationByKey = remember(snapshot.conversation) {
        snapshot.conversation.associateBy { conversationItemKey(it.id) }
    }
    LaunchedEffect(listState, conversationByKey) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
                conversationByKey[info.key]?.images
            }.flatten().mapTo(mutableSetOf()) { it.attachmentId }
        }.distinctUntilChanged().collect(onVisibleAttachmentIdsChange)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("DeepSeek Harness", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    appText("Android 原生 Compose · %1\$s", SharedModuleInfo.summary()),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onLoadFixture) { Text(appText("加载共享 Fixture")) }
                OutlinedButton(onClick = onReset) { Text(appText("重置")) }
            }
        }

        item {
            GatewaySmokeCard(
                gatewayState = gatewayState,
                endpoint = endpoint,
                onEndpointChange = onEndpointChange,
                pairingPayload = pairingPayload,
                onPairingPayloadChange = onPairingPayloadChange,
                onConnect = onConnect,
                onPair = onPair,
                onDisconnect = onDisconnect,
                onRefreshSessions = onRefreshSessions
            )
        }

        platformError?.let { error ->
            item { Text(error, color = MaterialTheme.colorScheme.error) }
        }

        item { StatusCard(snapshot) }

        if (snapshot.sessions.isNotEmpty()) {
            item { SectionTitle(appText("共享 Session State")) }
            items(snapshot.sessions, key = { "session:${it.id}" }) { session ->
                SessionRow(
                    session = session,
                    selected = session.id == snapshot.selectedSessionId,
                    onClick = { onSelectSession(session.id) }
                )
            }
        }

        if (snapshot.conversation.isNotEmpty()) {
            item { SectionTitle(appText("共享 Conversation 投影")) }
            items(snapshot.conversation, key = { conversationItemKey(it.id) }) { item ->
                ConversationRow(
                    item,
                    attachmentThumbnails,
                    attachmentStates,
                    onRetryAttachment
                )
            }
        }

        item { SectionTitle(appText("真实 Gateway 消息/附件冒烟")) }
        item {
            OutlinedTextField(
                value = messageDraft,
                onValueChange = onMessageDraftChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                label = { Text(appText("消息")) }
            )
        }
        if (preparedImages.isNotEmpty()) {
            items(preparedImages.size) { index ->
                val image = preparedImages[index]
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${image.width}×${image.height} · ${image.byteCount} bytes")
                    OutlinedButton(onClick = { onRemoveImage(index) }) { Text(appText("移除")) }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPickImage) { Text(appText("选择图片")) }
                Button(onClick = onSendMessage, enabled = canSend) { Text(appText("发送")) }
            }
        }

        item { SectionTitle(appText("Gateway JSON 手动注入")) }
        item {
            OutlinedTextField(
                value = wirePayload,
                onValueChange = onWirePayloadChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                label = { Text("Wire frame") },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            )
        }
        item {
            Button(onClick = onSubmitWirePayload, modifier = Modifier.fillMaxWidth()) {
                Text(appText("交给 KMP decoder / reducer"))
            }
        }
        item { HorizontalDivider(modifier = Modifier.padding(bottom = 20.dp)) }
    }
}

@Composable
private fun GatewaySmokeCard(
    gatewayState: GatewayRuntimeState,
    endpoint: String,
    onEndpointChange: (String) -> Unit,
    pairingPayload: String,
    onPairingPayloadChange: (String) -> Unit,
    onConnect: () -> Unit,
    onPair: () -> Unit,
    onDisconnect: () -> Unit,
    onRefreshSessions: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(appText("Android Gateway 开发冒烟入口"), fontWeight = FontWeight.SemiBold)
            Text(
                appText("状态：%1\$s · 网络：%2\$s", gatewayState.connection, if (gatewayState.networkAvailable) appText("可用") else appText("不可用"))
            )
            OutlinedTextField(
                value = endpoint,
                onValueChange = onEndpointChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(appText("ws:// 或 wss:// endpoint")) }
            )
            OutlinedTextField(
                value = pairingPayload,
                onValueChange = onPairingPayloadChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                label = { Text(appText("WebUI Base64URL 配对字符串")) }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConnect) { Text(appText("连接")) }
                Button(onClick = onPair, enabled = pairingPayload.isNotBlank()) { Text(appText("配对")) }
                OutlinedButton(onClick = onDisconnect) { Text(appText("断开")) }
            }
            if (gatewayState.connection == GatewayConnectionState.CONNECTED) {
                OutlinedButton(onClick = onRefreshSessions) { Text(appText("刷新会话")) }
            }
        }
    }
}

@Composable
private fun StatusCard(snapshot: SharedMobileSnapshot) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(appText("共享状态"), fontWeight = FontWeight.SemiBold)
            Text(appText("最后 frame：%1\$s", snapshot.lastFrameKind ?: appText("尚未接收")))
            Text(appText("待回答 Human Question：%1\$s", snapshot.pendingQuestionCount))
            snapshot.lastError?.let { Text(appText("错误：%1\$s", it), color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun SessionRow(session: SessionSummary, selected: Boolean, onClick: () -> Unit) {
    val container = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(container, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(session.title, fontWeight = FontWeight.SemiBold)
            Text(session.id, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
        Text(if (session.isRunning) appText("运行中") else if (session.hasUnread) appText("未读") else appText("空闲"))
    }
}

@Composable
private fun ConversationRow(
    item: ConversationItem,
    attachmentThumbnails: Map<String, ImageBitmap>,
    attachmentStates: Map<String, AttachmentLoadState>,
    onRetryAttachment: (String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(item.title, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Text(item.text.ifEmpty { appText("（纯附件消息）") })
            item.images.forEach { attachment ->
                val bitmap = attachmentThumbnails[attachment.attachmentId]
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = attachment.name ?: appText("会话附件"),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)
                    )
                } else {
                    val label = attachment.name ?: attachment.attachmentId
                    if (
                        attachmentStates[attachment.attachmentId] in
                        setOf(AttachmentLoadState.FAILED, AttachmentLoadState.DEFERRED)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val deferred = attachmentStates[attachment.attachmentId] == AttachmentLoadState.DEFERRED
                            Text(
                                if (deferred) appText("附件已从内存缩略图缓存淘汰：%1\$s", label) else appText("附件加载失败：%1\$s", label),
                                color = if (deferred) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.error
                            )
                            OutlinedButton(onClick = { onRetryAttachment(attachment.attachmentId) }) {
                                Text(appText("重试"))
                            }
                        }
                    } else {
                        Text(appText("附件加载中：%1\$s", label))
                    }
                }
            }
        }
    }
}

private fun conversationItemKey(itemId: String): String = "conversation:$itemId"

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Preview(showBackground = true, widthDp = 390, heightDp = 840)
@Composable
private fun SharedLogicScreenPreview() {
    val store = remember { com.clarklevis.dsh.shared.facade.SharedMobileStore() }
    MaterialTheme {
        SharedLogicScreen(
            snapshot = store.loadManualTestFixture(),
            wirePayload = AndroidSharedStateHolder.DEFAULT_WIRE_PAYLOAD,
            onWirePayloadChange = {},
            onLoadFixture = {},
            onSubmitWirePayload = {},
            onSelectSession = {},
            onReset = {}
        )
    }
}
