package com.clarklevis.dsh.android.ui

import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import com.clarklevis.dsh.shared.facade.CodeSyntaxHighlighting

internal object DshCodeSyntaxHighlighter {
    fun highlight(
        code: String,
        language: String?,
        dark: Boolean,
    ): Spanned =
        SpannableString(code).apply {
            CodeSyntaxHighlighting.highlight(code, language).forEach { token ->
                setSpan(
                    ForegroundColorSpan(CodeSyntaxHighlighting.color(token.kind, dark)),
                    token.start,
                    token.endExclusive,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
}
