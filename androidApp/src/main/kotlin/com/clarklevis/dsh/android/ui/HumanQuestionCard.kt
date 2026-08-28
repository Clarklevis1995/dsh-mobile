package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clarklevis.dsh.shared.protocol.GatewayPendingQuestionRequest
import com.clarklevis.dsh.shared.protocol.GatewayQuestion
import com.clarklevis.dsh.shared.protocol.GatewayQuestionAnswer

@Composable
internal fun HumanQuestionCard(
    request: GatewayPendingQuestionRequest,
    onAnswer: (List<GatewayQuestionAnswer>) -> Unit,
    onCancel: () -> Unit
) {
    var currentIndex by remember(request.rpcId) { mutableIntStateOf(0) }
    var collapsed by remember(request.rpcId) { mutableStateOf(false) }
    val selections = remember(request.rpcId) { mutableStateMapOf<String, Set<String>>() }
    val custom = remember(request.rpcId) { mutableStateMapOf<String, String>() }
    val question = request.questions[currentIndex]
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp)
            .shadow(20.dp, RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.98f), RoundedCornerShape(24.dp))
            .border(0.8.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f), RoundedCornerShape(24.dp))
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 17.dp, end = 9.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("?", color = DshColors.Ocean, fontWeight = FontWeight.Bold, modifier = Modifier.background(DshColors.Ocean.copy(alpha = 0.12f), CircleShape).padding(horizontal = 8.dp, vertical = 3.dp))
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(if (collapsed) "Agent 正在等待回答" else "需要你的回答", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (request.replay) Text("已从断线前恢复", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
            }
            TextButton(onClick = { collapsed = !collapsed }) { Text(if (collapsed) "⌃" else "⌄") }
            TextButton(onClick = onCancel) { Text("×") }
        }
        if (!collapsed) {
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
            Column(Modifier.padding(horizontal = 17.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                question.header?.takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)) }
                Text(question.question, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                question.detail?.takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)) }
                if (question.allowsMultipleSelections) Text("☷  可以选择多项", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f))
                question.options.orEmpty().forEach { option ->
                    val selected = option.label in selections[question.id].orEmpty()
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .background(if (selected) DshColors.Ocean.copy(alpha = 0.12f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.045f))
                            .border(1.dp, if (selected) DshColors.Ocean.copy(alpha = 0.55f) else Color.Transparent, RoundedCornerShape(14.dp))
                            .clickable {
                                val old = selections[question.id].orEmpty()
                                selections[question.id] = if (question.allowsMultipleSelections) {
                                    if (selected) old - option.label else old + option.label
                                } else setOf(option.label)
                            }.padding(13.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(if (selected) "●" else "○", color = if (selected) DshColors.Ocean else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(option.label, fontWeight = FontWeight.Medium)
                            option.description?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)) }
                        }
                    }
                }
                OutlinedTextField(
                    value = custom[question.id].orEmpty(),
                    onValueChange = { custom[question.id] = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = if (question.options.isNullOrEmpty()) 92.dp else 52.dp),
                    placeholder = { Text(if (question.options.isNullOrEmpty()) "输入你的答案" else "或输入自定义答案") },
                    shape = RoundedCornerShape(13.dp)
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${currentIndex + 1} / ${request.questions.size}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                Spacer(Modifier.weight(1f))
                if (currentIndex > 0) TextButton(onClick = { currentIndex-- }) { Text("上一题") }
                if (currentIndex < request.questions.lastIndex) {
                    Button(onClick = { currentIndex++ }, enabled = answered(question, selections, custom)) { Text("下一题") }
                } else {
                    Button(
                        onClick = {
                            onAnswer(request.questions.map { item ->
                                GatewayQuestionAnswer(item.id, selections[item.id].orEmpty().toList(), custom[item.id])
                            })
                        },
                        enabled = request.questions.all { answered(it, selections, custom) }
                    ) { Text("提交回答") }
                }
            }
        }
    }
}

private fun answered(
    question: GatewayQuestion,
    selections: Map<String, Set<String>>,
    custom: Map<String, String>
): Boolean = selections[question.id].orEmpty().isNotEmpty() || custom[question.id]?.isNotBlank() == true
