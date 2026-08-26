package com.clarklevis.dsh.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.clarklevis.dsh.shared.SharedModuleInfo
import com.clarklevis.dsh.shared.domain.SessionSummary
import com.clarklevis.dsh.shared.facade.SharedMobileSnapshot
import com.clarklevis.dsh.shared.projection.ConversationItem

@Composable
fun DeepSeekHarnessAndroidApp() {
    val stateHolder = remember { AndroidSharedStateHolder() }
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            SharedLogicScreen(
                snapshot = stateHolder.snapshot,
                wirePayload = stateHolder.wirePayload,
                onWirePayloadChange = { stateHolder.wirePayload = it },
                onLoadFixture = stateHolder::loadFixture,
                onSubmitWirePayload = stateHolder::submitWirePayload,
                onSelectSession = stateHolder::selectSession,
                onReset = stateHolder::reset,
                modifier = Modifier.safeDrawingPadding()
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
    modifier: Modifier = Modifier
) {
    LazyColumn(
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

        item { StatusCard(snapshot) }

        if (snapshot.sessions.isNotEmpty()) {
            item { SectionTitle("共享 Session State") }
            items(snapshot.sessions, key = SessionSummary::id) { session ->
                SessionRow(
                    session = session,
                    selected = session.id == snapshot.selectedSessionId,
                    onClick = { onSelectSession(session.id) }
                )
            }
        }

        if (snapshot.conversation.isNotEmpty()) {
            item { SectionTitle("共享 Conversation 投影") }
            items(snapshot.conversation, key = ConversationItem::id) { item -> ConversationRow(item) }
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
private fun ConversationRow(item: ConversationItem) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(item.title, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Text(item.text.ifEmpty { "（纯附件消息）" })
        }
    }
}

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
