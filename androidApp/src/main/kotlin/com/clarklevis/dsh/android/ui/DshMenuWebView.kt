package com.clarklevis.dsh.android.ui

import android.content.Context
import android.graphics.Rect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebView
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.android.localization.appTextKey
import java.util.Locale

internal data class DshNativeTextMenu(
    val bounds: Rect,
    val actions: List<Pair<String, () -> Unit>>,
    val dismiss: () -> Unit
)

/** WebView 保留浏览器的原生选择行为，菜单动作和位置交由项目菜单组件呈现。 */
internal class DshMenuWebView(context: Context) : WebView(context) {
    var onMenuChanged: ((DshNativeTextMenu?) -> Unit)? = null
    private var activeMode: ActionMode? = null
    private var contentRect = Rect()
    private var actions = emptyList<Pair<String, () -> Unit>>()

    override fun startActionMode(callback: ActionMode.Callback): ActionMode? =
        startActionMode(callback, ActionMode.TYPE_FLOATING)

    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? {
        val projectCallback = object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                if (!callback.onCreateActionMode(mode, menu)) return false
                activeMode = mode
                capture(mode, menu)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                val result = callback.onPrepareActionMode(mode, menu)
                if (menu.size() > 0) capture(mode, menu)
                return result
            }

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean =
                callback.onActionItemClicked(mode, item)

            override fun onDestroyActionMode(mode: ActionMode) {
                if (activeMode === mode) {
                    activeMode = null
                    actions = emptyList()
                    onMenuChanged?.invoke(null)
                }
                callback.onDestroyActionMode(mode)
            }

            override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                if (callback is ActionMode.Callback2) {
                    callback.onGetContentRect(mode, view, outRect)
                } else {
                    super.onGetContentRect(mode, view, outRect)
                }
                contentRect = Rect(outRect)
                publish()
            }

            private fun capture(mode: ActionMode, menu: Menu) {
                // 保存浏览器动作，再清除系统菜单；原生 ActionMode 继续管理选择操作柄。
                actions = (0 until menu.size()).map(menu::getItem)
                    .filter { it.isVisible && it.isEnabled }
                    .map { item ->
                        val title = item.title.toString()
                        val label = when (title.lowercase(Locale.ROOT)) {
                            "copy", "复制" -> appTextKey("复制") // i18n-ignore: also matches the Chinese system menu title
                            "cut", "剪切" -> appTextKey("剪切") // i18n-ignore: also matches the Chinese system menu title
                            "paste", "粘贴" -> appTextKey("粘贴") // i18n-ignore: also matches the Chinese system menu title
                            "select all", "全选" -> appTextKey("全选") // i18n-ignore: also matches the Chinese system menu title
                            else -> title
                        }
                        appText(label) to {
                            callback.onActionItemClicked(mode, item)
                            Unit
                        }
                    }
                menu.clear()
                post {
                    if (activeMode === mode) {
                        mode.invalidateContentRect()
                        publish()
                    }
                }
            }
        }
        return super.startActionMode(projectCallback, type)
    }

    private fun publish() {
        val mode = activeMode ?: return
        if (actions.isEmpty() || !isAttachedToWindow) return
        val location = IntArray(2).also(::getLocationInWindow)
        val bounds = Rect(contentRect).apply { offset(location[0], location[1]) }
        onMenuChanged?.invoke(DshNativeTextMenu(bounds, actions, mode::finish))
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        activeMode?.invalidateContentRect()
    }

    fun dismissTextMenu() {
        activeMode?.finish()
    }

    override fun onDetachedFromWindow() {
        dismissTextMenu()
        super.onDetachedFromWindow()
    }
}
