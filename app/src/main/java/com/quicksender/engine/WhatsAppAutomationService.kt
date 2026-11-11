package com.quicksender.engine

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class WhatsAppAutomationService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (BroadcastEngineCoordinator.isPaused.get() || BroadcastEngineCoordinator.isCancelled.get()) return

        val rootNode = rootInActiveWindow ?: return

        // Click WhatsApp Send Button
        val sendById = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send")
        if (sendById.isNotEmpty() && sendById[0].isEnabled) {
            sendById[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
            performGlobalAction(GLOBAL_ACTION_BACK)
            return
        }

        // Fallback by Content Description
        val sendByDesc = mutableListOf<AccessibilityNodeInfo>()
        findNodesByDescription(rootNode, "Send", sendByDesc)
        if (sendByDesc.isNotEmpty() && sendByDesc[0].isEnabled) {
            sendByDesc[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
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
