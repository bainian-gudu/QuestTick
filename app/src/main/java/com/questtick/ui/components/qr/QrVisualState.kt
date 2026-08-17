package com.questtick.ui.components.qr

/** QR 登录流程的视觉状态定义。 */

import androidx.compose.ui.graphics.Color

internal sealed interface QrVisualState {
    object Loading : QrVisualState

    object Waiting : QrVisualState

    object Scanned : QrVisualState

    object Success : QrVisualState

    object Expired : QrVisualState

    object Cancelled : QrVisualState

    data class Busy(val message: String) : QrVisualState

    data class Error(
        val message: String,
        val title: String = "",
    ) : QrVisualState
}

internal data class QrStatusMessage(
    val text: String,
    val color: Color,
)
