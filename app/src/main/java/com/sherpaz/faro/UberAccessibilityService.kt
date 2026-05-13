package com.sherpaz.faro

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class UberAccessibilityService : AccessibilityService() {
    companion object {
        var floatingServiceInstance: FloatingService? = null
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}

data class TripData(val clpHora: Int, val clpKm: Int)
