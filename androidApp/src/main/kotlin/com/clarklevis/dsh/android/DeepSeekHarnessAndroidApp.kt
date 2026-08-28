package com.clarklevis.dsh.android

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
import com.clarklevis.dsh.shared.SharedModuleInfo
import com.clarklevis.dsh.shared.domain.SessionSummary
import com.clarklevis.dsh.shared.facade.SharedMobileSnapshot
import com.clarklevis.dsh.shared.gateway.GatewayConnectionState
import com.clarklevis.dsh.shared.gateway.GatewayRuntimeState
import com.clarklevis.dsh.shared.projection.ConversationItem
import kotlinx.coroutines.flow.distinctUntilChanged
import com.clarklevis.dsh.android.ui.DshProductApp
import com.clarklevis.dsh.android.ui.DshTheme

@Composable
fun DeepSeekHarnessAndroidApp() {
    val context = LocalContext.current
    val gatewayViewModel: AndroidGatewayViewModel = viewModel()
    val stateHolder = gatewayViewModel.stateHolder
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(stateHolder::prepareImage)
    }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(stateHolder.gatewayState.shouldKeepAliveInBackground) {
        if (
            stateHolder.gatewayState.shouldKeepAliveInBackground &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    DshTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            DshProductApp(
                stateHolder = stateHolder,
                onPickImage = { imagePicker.launch("image/*") }
            )
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
                    "Android 原生 Compose · ${SharedModuleInfo.summary()}",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onLoadFixture) { Text("加载共享 Fixture") }
                OutlinedButton(onClick = onReset) { Text("重置") }
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
            item { SectionTitle("共享 Session State") }
            items(snapshot.sessions, key = { "session:${it.id}" }) { session ->
                SessionRow(
                    session = session,
                    selected = session.id == snapshot.selectedSessionId,
                    onClick = { onSelectSession(session.id) }
                )
            }
        }

        if (snapshot.conversation.isNotEmpty()) {
            item { SectionTitle("共享 Conversation 投影") }
            items(snapshot.conversation, key = { conversationItemKey(it.id) }) { item ->
                ConversationRow(
                    item,
                    attachmentThumbnails,
                    attachmentStates,
                    onRetryAttachment
                )
            }
        }

        item { SectionTitle("真实 Gateway 消息/附件冒烟") }
        item {
            OutlinedTextField(
                value = messageDraft,
                onValueChange = onMessageDraftChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                label = { Text("消息") }
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
                    OutlinedButton(onClick = { onRemoveImage(index) }) { Text("移除") }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPickImage) { Text("选择图片") }
                Button(onClick = onSendMessage, enabled = canSend) { Text("发送") }
            }
        }

        item { SectionTitle("Gateway JSON 手动注入") }
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
                Text("交给 KMP decoder / reducer")
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
            Text("Android Gateway 开发冒烟入口", fontWeight = FontWeight.SemiBold)
            Text(
                "状态：${gatewayState.connection} · 网络：${if (gatewayState.networkAvailable) "可用" else "不可用"}"
            )
            OutlinedTextField(
                value = endpoint,
                onValueChange = onEndpointChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("ws:// 或 wss:// endpoint") }
            )
            OutlinedTextField(
                value = pairingPayload,
                onValueChange = onPairingPayloadChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                label = { Text("WebUI Base64URL 配对字符串") }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConnect) { Text("连接") }
                Button(onClick = onPair, enabled = pairingPayload.isNotBlank()) { Text("配对") }
                OutlinedButton(onClick = onDisconnect) { Text("断开") }
            }
            if (gatewayState.connection == GatewayConnectionState.CONNECTED) {
                OutlinedButton(onClick = onRefreshSessions) { Text("刷新会话") }
            }
        }
    }
}

@Composable
private fun StatusCard(snapshot: SharedMobileSnapshot) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("共享状态", fontWeight = FontWeight.SemiBold)
            Text("最后 frame：${snapshot.lastFrameKind ?: "尚未接收"}")
            Text("待回答 Human Question：${snapshot.pendingQuestionCount}")
            snapshot.lastError?.let { Text("错误：$it", color = MaterialTheme.colorScheme.error) }
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
        Text(if (session.isRunning) "运行中" else if (session.hasUnread) "未读" else "空闲")
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
            Text(item.text.ifEmpty { "（纯附件消息）" })
            item.images.forEach { attachment ->
                val bitmap = attachmentThumbnails[attachment.attachmentId]
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = attachment.name ?: "会话附件",
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
                                if (deferred) "附件已从内存缩略图缓存淘汰：$label" else "附件加载失败：$label",
                                color = if (deferred) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.error
                            )
                            OutlinedButton(onClick = { onRetryAttachment(attachment.attachmentId) }) {
                                Text("重试")
                            }
                        }
                    } else {
                        Text("附件加载中：$label")
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
