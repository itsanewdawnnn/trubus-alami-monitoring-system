package com.trubus.tams.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.trubus.tams.data.model.VersionInfoDto
import com.trubus.tams.data.update.UpdateFlowState

/**
 * True only when the update currently on screen must be installed before
 * the app can be used again. Not private: MainAppScreen.kt reuses this same
 * definition to structurally gate the login/dashboard content itself (not
 * just this dialog), so the two can never drift out of sync about what
 * counts as "blocking".
 */
val UpdateFlowState.isForced: Boolean
    get() = when (this) {
        is UpdateFlowState.Available -> info.force_update
        is UpdateFlowState.Downloading -> info.force_update
        is UpdateFlowState.DownloadFailed -> info.force_update
        is UpdateFlowState.NeedsInstallPermission -> info.force_update
        is UpdateFlowState.ReadyToInstall -> info.force_update
        UpdateFlowState.None -> false
    }

private val UpdateFlowState.versionInfo: VersionInfoDto?
    get() = when (this) {
        is UpdateFlowState.Available -> info
        is UpdateFlowState.Downloading -> info
        is UpdateFlowState.DownloadFailed -> info
        is UpdateFlowState.NeedsInstallPermission -> info
        is UpdateFlowState.ReadyToInstall -> info
        UpdateFlowState.None -> null
    }

/**
 * Renders the whole OTA update flow as a single dialog whose content swaps
 * with [state] -- available/downloading/failed/needs-permission all reuse
 * one AlertDialog shell instead of five separate dialog composables, so the
 * title/icon/dismiss behavior for a force update stays consistent no matter
 * which sub-state is showing.
 *
 * Renders nothing for [UpdateFlowState.None], and for a NON-forced
 * [UpdateFlowState.ReadyToInstall] (a near-instantaneous hand-off state for
 * an optional update -- the caller, MainAppScreen, launches the system
 * installer for it via a LaunchedEffect rather than showing UI here, since
 * starting an Activity is a side effect that doesn't belong inside a pure
 * render function).
 *
 * A FORCED [UpdateFlowState.ReadyToInstall] is different on purpose: it
 * still renders (see the INSTALL button in [UpdateDialogConfirmButton]),
 * because this is exactly the state a user lands back on after cancelling,
 * failing, or backing out of the Package Installer -- if this dialog also
 * disappeared for that sub-state, the screen underneath would become the
 * only thing visible, which was the original bypass this whole flow now
 * closes structurally (see MainAppScreen's isForced-gated content).
 *
 * A force update's dialog has no dismiss path at all: `onDismissRequest` is
 * only wired to the backdrop/back-press when [UpdateFlowState.isForced] is
 * false, and the Later button (which calls `onLaterClick`) is never
 * rest of the app underneath stays inert the whole time since Compose's
 * Dialog already captures all touch input while it's on screen (and, as of
 * this dialog's ReadyToInstall handling above, MainAppScreen's structural
 * gate keeps it inert even in the moments this dialog isn't on screen).
 */
@Composable
fun UpdateDialog(
    state: UpdateFlowState,
    onLaterClick: () -> Unit,
    onUpdateClick: () -> Unit,
    onRetryClick: () -> Unit,
    onOpenSettingsClick: () -> Unit,
    onInstallClick: () -> Unit,
) {
    if (state is UpdateFlowState.None) return
    if (state is UpdateFlowState.ReadyToInstall && (!state.isForced)) return

    val info = state.versionInfo ?: return
    val forced = state.isForced

    AlertDialog(
        onDismissRequest = { if (!forced) onLaterClick() },
        shape = OverlayCardShape,
        icon = {
            Icon(
                imageVector = if (forced) Icons.Filled.Warning else Icons.Filled.SystemUpdate,
                contentDescription = null,
                tint = if (forced) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(if (forced) "Mandatory Update" else "Update Available")
        },
        text = { UpdateDialogBody(state, info) },
        confirmButton = { UpdateDialogConfirmButton(state, onUpdateClick, onRetryClick, onOpenSettingsClick, onInstallClick) },
        dismissButton = {
            // Later is never offered for a forced update, in any sub-state
            // (available, downloading, failed, needs-permission) -- the user
            // must see this through to install before continuing.
            if (!forced) {
                TextButton(onClick = onLaterClick) { Text("LATER") }
            }
        }
    )
}

@Composable
private fun UpdateDialogBody(state: UpdateFlowState, info: VersionInfoDto) {
    Column {
        Text(
            "Version ${info.version_name} is available.",
            style = MaterialTheme.typography.bodyMedium
        )

        if (info.release_notes.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            info.release_notes.forEach { note ->
                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text("•  ", style = MaterialTheme.typography.bodyMedium)
                    Text(note, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        when (state) {
            is UpdateFlowState.Downloading -> {
                Spacer(Modifier.height(16.dp))
                if (state.percent >= 0) {
                    LinearProgressIndicator(
                        progress = { state.percent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Downloading... ${state.percent}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    // Server didn't send a Content-Length header -- no
                    // percentage is computable, so this falls back to an
                    // indeterminate bar plus a running byte count instead of
                    // showing a stuck/misleading 0%.
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Downloading... ${formatBytes(state.downloadedBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            is UpdateFlowState.DownloadFailed -> {
                Spacer(Modifier.height(16.dp))
                Text(
                    state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            is UpdateFlowState.NeedsInstallPermission -> {
                Spacer(Modifier.height(16.dp))
                Text(
                    "The app needs \"Install unknown apps\" permission to install this update. Tap Open Settings, enable that permission for TAMS, then return to the app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Only reachable here when forced (see this file's UpdateDialog
            // doc comment) -- shown when the automatic install hand-off
            // (MainAppScreen's LaunchedEffect, or MainViewModel.onAppResumed()
            // retrying after the user returns from a cancelled/failed
            // installer) hasn't yet succeeded, so the user always has a
            // manual way forward instead of the dialog silently stalling.
            is UpdateFlowState.ReadyToInstall -> {
                Spacer(Modifier.height(16.dp))
                Text(
                    "The update has been downloaded. If the installer didn't open automatically, tap Install below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> Unit
        }
    }
}

/** "12.3 MB" style formatting for the unknown-total-size download fallback. */
private fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return "%.1f MB".format(mb)
}

@Composable
private fun UpdateDialogConfirmButton(
    state: UpdateFlowState,
    onUpdateClick: () -> Unit,
    onRetryClick: () -> Unit,
    onOpenSettingsClick: () -> Unit,
    onInstallClick: () -> Unit
) {
    when (state) {
        is UpdateFlowState.Available -> {
            Button(onClick = onUpdateClick) { Text("UPDATE") }
        }
        is UpdateFlowState.Downloading -> {
            // No confirm action while a download is already in flight --
            // shown instead of an empty slot so the dialog's button row
            // doesn't visibly collapse/jump between states.
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }
        is UpdateFlowState.DownloadFailed -> {
            Button(onClick = onRetryClick) { Text("RETRY") }
        }
        is UpdateFlowState.NeedsInstallPermission -> {
            Button(onClick = onOpenSettingsClick) { Text("OPEN SETTINGS") }
        }
        // Only reachable when forced -- see UpdateDialog's doc comment.
        is UpdateFlowState.ReadyToInstall -> {
            Button(onClick = onInstallClick) { Text("INSTALL") }
        }
        else -> Unit
    }
}
