package com.booxai.helper.ui

/**
 * 从 App 内“继续此会话”进入的全屏聊天界面。
 * 此时不在微信读书页面上，因此不显示当前书页/截图区域，只保留会话和输入。
 */
class SessionChatActivity : PanelActivity() {
    override fun isFullScreen(): Boolean = true
}
