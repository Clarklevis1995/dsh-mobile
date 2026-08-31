package com.clarklevis.dsh.android.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.shared.facade.SharedApprovalStatusSnapshot
import com.clarklevis.dsh.shared.protocol.GatewayApprovalOutcome
import com.clarklevis.dsh.shared.protocol.GatewayPendingApprovalRequest
import com.clarklevis.dsh.shared.protocol.JsonValue

@Composable
internal fun ApprovalRequestCard(
    request: GatewayPendingApprovalRequest,
    status: SharedApprovalStatusSnapshot,
    commandPreview: String?,
    details: JsonValue?,
    onDecision: (GatewayApprovalOutcome) -> Unit
) {
    var collapsed by remember(request.rpcId) { mutableStateOf(false) }
    var detailsExpanded by remember(request.rpcId) { mutableStateOf(false) }
    val amber = Color(0xFFF59E0B)
    val busy = status.kind == "submitting" || status.kind == "accepted"
    val shape = RoundedCornerShape(24.dp)
    val reason = request.reason?.takeIf(String::isNotBlank)
        ?: "${request.toolName} 请求执行需要审批的操作"

    LaunchedEffect(request.rpcId, status.kind, collapsed, detailsExpanded) {
        Log.i(
            "DshApproval",
            "ui card visible replay=${request.replay} status=${status.kind} " +
                "collapsed=$collapsed detailsExpanded=$detailsExpanded " +
                "hasCommand=${!commandPreview.isNullOrBlank()} hasDetails=${details != null}"
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .shadow(10.dp, shape)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, amber.copy(alpha = 0.78f), shape)
    ) {
        if (collapsed) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !busy) { collapsed = false }
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.size(9.dp).background(amber, CircleShape))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Agent 正在等待审批", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        reason,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                }
                ApprovalChevron(pointsUp = true)
            }
        } else {
            ApprovalHeader(
                request = request,
                busy = busy,
                amber = amber,
                onCollapse = { collapsed = true }
            )
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 17.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = reason,
                    fontSize = 16.sp,
                    lineHeight = 23.sp,
                    fontWeight = FontWeight.Medium
                )
                commandPreview?.takeIf(String::isNotBlank)?.let { command ->
                    Text(
                        text = command,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                }
                details?.let { value ->
                    ApprovalDetailsDisclosure(
                        value = value.normalizedJsonValue(),
                        expanded = detailsExpanded,
                        onToggle = { detailsExpanded = !detailsExpanded }
                    )
                }
                if (status.kind == "rejected") {
                    Text(
                        status.failureArgument ?: if (status.failureCode == "DISCONNECTED") {
                            "WebSocket 已断开，重连后再处理审批。"
                        } else {
                            "审批响应未被服务端接受，请重试。"
                        },
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { onDecision(GatewayApprovalOutcome.REJECTED) },
                        enabled = !busy
                    ) { Text("拒绝") }
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = { onDecision(GatewayApprovalOutcome.ALLOWED_ONCE) },
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.onSurface,
                            contentColor = MaterialTheme.colorScheme.surface
                        )
                    ) { Text("允许一次") }
                }
            }
        }
    }
}

@Composable
private fun ApprovalHeader(
    request: GatewayPendingApprovalRequest,
    busy: Boolean,
    amber: Color,
    onCollapse: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 外层已统一裁剪顶部圆角；标题栏保持矩形，确保下沿没有圆角。
            .background(amber.copy(alpha = 0.09f))
            .padding(start = 17.dp, end = 9.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.size(9.dp).background(amber, CircleShape))
        Spacer(Modifier.width(10.dp))
        Text("等待审批", color = amber, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        if (request.replay) {
            Text("  ·  已恢复", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
        Spacer(Modifier.weight(1f))
        if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        IconButton(
            onClick = onCollapse,
            enabled = !busy,
            modifier = Modifier.size(36.dp)
        ) {
            ApprovalChevron(
                pointsUp = false,
                contentDescription = "收起审批卡片"
            )
        }
    }
}

@Composable
private fun ApprovalDetailsDisclosure(
    value: JsonValue,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f))
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "{ }  审批详情",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            ApprovalChevron(pointsUp = expanded)
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .heightIn(max = 190.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                val objectValue = value.objectValue
                if (objectValue == null) {
                    Text(
                        value.jsonDisplayText(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                } else {
                    objectValue.toSortedMap().forEach { (key, item) ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                key,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                detailText(item),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ApprovalChevron(
    pointsUp: Boolean,
    contentDescription: String? = null
) {
    Icon(
        painter = painterResource(R.drawable.ic_chevron_right),
        contentDescription = contentDescription,
        modifier = Modifier
            .size(14.dp)
            .rotate(if (pointsUp) -90f else 90f),
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
    )
}

private fun detailText(value: JsonValue): String = when (value) {
    is JsonValue.StringValue -> value.value
    else -> value.jsonDisplayText()
}
