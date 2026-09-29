package dev.metro.anime.ui.player

import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object MediaButtonManager {
    var onMediaKey: ((KeyEvent) -> Boolean)? = null
}

class HeadsetHookDebouncer(
    private val scope: CoroutineScope,
    private val onSingleClick: () -> Unit,
    private val onDoubleClick: () -> Unit,
    private val onTripleClick: () -> Unit,
) {
    private var clickCount = 0
    private var timerJob: Job? = null

    fun onHeadsetHook() {
        clickCount++
        timerJob?.cancel()
        if (clickCount >= 3) {
            clickCount = 0
            onTripleClick()
            return
        }
        timerJob = scope.launch {
            delay(350)
            val count = clickCount
            clickCount = 0
            when (count) {
                1 -> onSingleClick()
                2 -> onDoubleClick()
            }
        }
    }

    fun cancel() {
        timerJob?.cancel()
        clickCount = 0
    }
}
