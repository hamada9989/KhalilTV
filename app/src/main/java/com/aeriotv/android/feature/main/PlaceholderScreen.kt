package com.aeriotv.android.feature.main

import com.aeriotv.android.ui.scale.subtext
import com.aeriotv.android.ui.theme.textAccent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Generic "Coming in a later phase" surface for tabs whose feature work hasn't landed yet.
 * Used by Favorites, DVR, and On Demand until their respective phases ship.
 */
@Composable
fun PlaceholderScreen(tabLabel: String, hint: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = tabLabel,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.textAccent,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = hint,
            style = MaterialTheme.typography.bodyMedium.subtext(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}
