package com.questtick.ui.components.qr

// QR 登录成功后选择是否保持登录的确认卡片。

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import com.questtick.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.questtick.ui.components.PanelCard
import com.questtick.ui.theme.TextSecondary

@Composable
internal fun QrKeepLoginChoiceCard(
    keepDescription: String,
    onceDescription: String,
    onKeep: () -> Unit,
    onOnce: () -> Unit,
    keepEnabled: Boolean = true,
    keepUnavailableDescription: String? = null,
) {
    PanelCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "是否保持登录状态？",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))

            Button(
                onClick = onKeep,
                enabled = keepEnabled,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Text("保持登录（推荐）", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
            Text(
                if (keepEnabled) keepDescription else keepUnavailableDescription ?: keepDescription,
                fontSize = 12.sp,
                color = TextSecondary,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = onOnce,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    "仅本次登录",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                onceDescription,
                fontSize = 12.sp,
                color = TextSecondary,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
        }
    }
}
