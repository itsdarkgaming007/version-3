package com.quicksender.engine

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class WhatsAppAutomationService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (BroadcastEngineCoordinator.isPaused.get() || BroadcastEngineCoordinator.isCancelled.get()) return

        val rootNode = rootInActiveWindow ?: return

        // 1. Regular WhatsApp & Business WhatsApp send button IDs
        val sendIds = arrayOf("com.whatsapp:id/send", "com.whatsapp.w4b:id/send")
        for (id in sendIds) {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty() && nodes[0].isEnabled) {
                nodes[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                performGlobalAction(GLOBAL_ACTION_BACK)
                return
            }
        }

        // 2. Fallback search by description (Send / அனுப்பி)
        val descNodes = mutableListOf<AccessibilityNodeInfo>()
        findNodesByDescription(rootNode, "Send", descNodes)
        if (descNodes.isNotEmpty() && descNodes[0].isEnabled) {
            descNodes[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    private fun findNodesByDescription(node: AccessibilityNodeInfo?, target: String, list: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        if (node.contentDescription?.toString().equals(target, ignoreCase = true)) {
            list.add(node)
        }
        for (i in 0 until node.childCount) {
            findNodesByDescription(node.getChild(i), target, list)
        }
    }

    override fun onInterrupt() {}
}
