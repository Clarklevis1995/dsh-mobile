package com.clarklevis.dsh.shared.facade

/** 文件改动胶囊在 Android dp/sp 与 iOS pt 上共用的视觉规格。 */
object WorkspaceChangesStyle {
    const val FONT_SIZE = 15.0
    const val LINE_HEIGHT = 20.0
    const val HORIZONTAL_PADDING = 14.0
    const val VERTICAL_PADDING = 8.0
    const val ITEM_SPACING = 8.0
    const val COMPOSER_SPACING = 8.0
    const val PROGRESS_SIZE = 14.0
    const val LABEL_COLOR_ARGB = 0xFF8E8E93L
    const val ADDED_COLOR_ARGB = 0xFF28843BL
    const val DELETED_COLOR_ARGB = 0xFFC44B51L

    // Figma 文件审阅规格；dp/sp 与 pt 使用相同逻辑值。
    const val REVIEW_BREAKPOINT = 768.0
    const val REVIEW_WIDE_BREAKPOINT = 1200.0
    const val REVIEW_MAX_WIDTH = 1440.0
    const val REVIEW_CODE_MAX_WIDTH = 1000.0
    const val REVIEW_BLOCK_RADIUS = 8.0
    const val REVIEW_MENU_WIDTH = 304.0
    const val REVIEW_SIDEBAR_WIDTH = 248.0
    const val REVIEW_WIDE_SIDEBAR_WIDTH = 260.0
    const val REVIEW_INK = 0xFF171A20L
    const val REVIEW_SECONDARY = 0xFF69717DL
    const val REVIEW_MUTED = 0xFF949BA5L
    const val REVIEW_LINE = 0xFFE7E9EDL
    const val REVIEW_SUBTLE = 0xFFF7F8FAL
    const val REVIEW_BLUE = 0xFF3269D5L
    const val REVIEW_BLUE_SOFT = 0xFFEBF1FEL
    const val REVIEW_ADDED_BG = 0xFFE9F5EDL
    const val REVIEW_ADDED_INK = 0xFF236D43L
    const val REVIEW_DELETED_BG = 0xFFFBECEFL
    const val REVIEW_DELETED_INK = 0xFFAE3D51L
    const val REVIEW_HUNK_BG = 0xFFF0F3F7L
}
