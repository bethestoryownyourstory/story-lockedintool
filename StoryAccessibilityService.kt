package com.story.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

class StoryAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { instance = this; refreshOverlay() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean { instance = null; refreshOverlay(); return super.onUnbind(intent) }

    // Station 3 moves into / out of the lock-screen-capable layer when this service turns on / off.
    private fun refreshOverlay() {
        if (OverlayService.running) runCatching { startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_REBUILD)) }
    }

    companion object { var instance: StoryAccessibilityService? = null }
}
