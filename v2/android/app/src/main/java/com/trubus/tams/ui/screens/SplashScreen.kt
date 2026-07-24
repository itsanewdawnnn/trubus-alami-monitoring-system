package com.trubus.tams.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.trubus.tams.R

/**
 * Cold-start splash: the TAMS logo centered on a plain background, shown
 * before the login screen or dashboard for exactly as long as
 * MainViewModel.validateSessionOnStartup() takes to resolve -- see
 * MainAppScreen's isValidatingSession gate. No artificial minimum duration:
 * a fresh install with no stored token to validate skips this screen
 * entirely. MainViewModel's own initialization (version check, Remote
 * Management refresh, WorkManager enqueue) already starts concurrently the
 * moment it's constructed in MainActivity, independent of this gate.
 *
 * Background is hardcoded to a fixed off-white (#FDFDFD) rather than
 * MaterialTheme.colorScheme.background (the app's Bento theme uses a very
 * faint off-white/lavender tint there) for a consistent, theme-independent
 * splash color. If logo_splash.png's own canvas isn't the same shade, the
 * difference can show up as a faint box/seam around the logo -- re-export
 * the asset to match if that's visible.
 */
@Composable
fun SplashScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFFDFDFD)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(id = R.drawable.logo_splash),
            contentDescription = null,
            modifier = Modifier.size(160.dp)
        )
    }
}
