package com.streamflixreborn.streamflix.nextui.buttons

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.streamflixreborn.streamflix.nextui.R

@Composable
internal fun PreviousButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    PlayerButton(
        modifier = modifier.size(48.dp),
        onClick = onClick,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_skip_prev),
            contentDescription = stringResource(R.string.player_controls_previous),
            modifier = Modifier.size(28.dp),
        )
    }
}
