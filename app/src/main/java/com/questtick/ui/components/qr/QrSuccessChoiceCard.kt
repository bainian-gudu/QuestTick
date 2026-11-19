package com.questtick.ui.components.qr

// QR 登录成功后的确认卡片。

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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.questtick.ui.components.Text
import com.questtick.ui.components.PanelCard
import com.questtick.ui.theme.TextSecondary

/** 云游戏扫码成功后选择是否保存可自动续期的登录凭证。 */
@Composable
internal fun QrKeepLoginChoiceCard(
    keepDescription: String,
    onceDescription: String,
    onKeep: () -> Unit,
    onOnce: () -> Unit,
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
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Text("保持登录（推荐）", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
            Text(
                keepDescription,
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

/** 只提供 Cookie、无法自动续期时的确认卡片（米游社扫码）。 */
@Composable
internal fun QrCookieOnlyConfirmCard(
    description: String,
    onConfirm: () -> Unit,
) {
    PanelCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "确认保存登录信息",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))

            Button(
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Text("保存 Cookie", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
            Text(
                description,
                fontSize = 12.sp,
                color = TextSecondary,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
        }
    }
}
