package com.Music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.Music.MainViewModel.ShareLinkState

/**
 * Platform picker for the "Share link" flow, shared by the song-row menu and
 * the player overflow menu. Lists YouTube, YouTube Music, Spotify, Apple
 * Music (resolved via song.link) plus an "Original link" no-network fallback.
 * Shows a spinner overlay while [ShareLinkState.LOADING].
 */
private data class PlatformOption(
    val key: String,
    val label: String,
    val icon: Int
)

private val PLATFORM_OPTIONS = listOf(
    PlatformOption("youtube", "YouTube", R.drawable.ic_platform_youtube),
    PlatformOption("youtubeMusic", "YouTube Music", R.drawable.ic_platform_youtube_music),
    PlatformOption("spotify", "Spotify", R.drawable.ic_platform_spotify),
    PlatformOption("appleMusic", "Apple Music", R.drawable.ic_platform_apple_music)
)

@Composable
fun ShareLinkPlatformDialog(
    songTitle: String,
    state: ShareLinkState,
    onPickPlatform: (String) -> Unit,
    onPickOriginal: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (state != ShareLinkState.LOADING) onDismiss() },
        containerColor = MaterialTheme.colorScheme.background,
        title = { Text("Share link as") },
        text = {
            Column {
                if (state == ShareLinkState.LOADING) {
                    Column {
                        Text(
                            "Resolving \"${songTitle}\" via song.link…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                        androidx.compose.foundation.layout.Box(
                            Modifier.fillMaxWidth(),
                            contentAlignment = androidx.compose.ui.Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                } else {
                    Text(
                        "Pick which platform's link to share for \"${songTitle}\".",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    PLATFORM_OPTIONS.forEachIndexed { index, opt ->
                        if (index > 0) HorizontalDivider()
                        ListItem(
                            modifier = Modifier.clickable { onPickPlatform(opt.key) },
                            headlineContent = {
                                Text(opt.label, color = MaterialTheme.colorScheme.primary)
                            },
                            leadingContent = {
                                Icon(
                                    painter = painterResource(opt.icon),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        )
                    }
                    HorizontalDivider()
                    ListItem(
                        modifier = Modifier.clickable { onPickOriginal() },
                        headlineContent = {
                            Text("Original link", color = MaterialTheme.colorScheme.primary)
                        },
                        supportingContent = {
                            Text(
                                "Share the source URL as is",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        leadingContent = {
                            Icon(
                                Icons.Default.Link, null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                enabled = state != ShareLinkState.LOADING,
                onClick = onDismiss
            ) { Text("Cancel") }
        }
    )
}
