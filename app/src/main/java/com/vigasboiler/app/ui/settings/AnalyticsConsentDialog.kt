package com.vigasboiler.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.vigasboiler.app.R
import androidx.compose.material3.Icon

/**
 * Asks once, after the first sign-in, whether anonymous usage statistics may be sent. There is
 * no default: tapping outside does not count as an answer.
 */
@Composable
fun AnalyticsConsentDialog(onAnswer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        icon = { Icon(painterResource(R.drawable.ic_nav_stats), null) },
        title = { Text(stringResource(R.string.analytics_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.analytics_intro))
                Text(stringResource(R.string.analytics_details), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = { onAnswer(true) }) { Text(stringResource(R.string.analytics_yes)) } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text(stringResource(R.string.analytics_no)) } },
    )
}
