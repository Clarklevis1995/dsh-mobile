package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.shared.protocol.GatewayTextPart

@Composable
internal fun SentReplyBubble(textParts: List<GatewayTextPart>, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val fill = if (dark) Color(0xFF172A46) else DshColors.Ocean.copy(alpha = 0.11f)
    val edge = if (dark) Color(0xFF314A6B) else DshColors.Ocean.copy(alpha = 0.08f)
    val quoteFill = if (dark) Color(0xFF243F63) else Color(0xFF3055A1).copy(alpha = 0.11f)
    val quoteForeground = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dark) 0.88f else 0.78f)
    val shape = RoundedCornerShape(15.dp)
    val body = textParts.filter { it.type == "text" }.joinToString("") { it.text }.trim()
    Column(modifier.widthIn(max = 360.dp).width(IntrinsicSize.Max)
        .background(fill, shape).border(0.7.dp, edge, shape).padding(10.dp)
        .testTag("sent-reply-bubble"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        textParts.filter { it.type == "quote" }.forEachIndexed { index, part ->
            var expanded by rememberSaveable(index, part.text) { mutableStateOf(false) }
            Text(part.text, fontSize = 14.sp, lineHeight = 20.sp,
                color = quoteForeground,
                maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
                    .background(quoteFill, RoundedCornerShape(9.dp))
                    .clickable(onClickLabel = appText(if (expanded) "收起引用" else "展开引用"), role = Role.Button) {
                        expanded = !expanded
                    }.padding(horizontal = 10.dp, vertical = 8.dp).testTag("sent-reply-quote-$index"))
        }
        if (body.isNotEmpty()) Text(body, fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.testTag("sent-reply-body"))
    }
}
