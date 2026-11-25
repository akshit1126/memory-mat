package com.memorymat.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.memorymat.app.ui.components.CuttingMatBackground
import com.memorymat.app.ui.components.DappledSunlightOverlay
import com.memorymat.app.ui.theme.MemoryMatTheme

@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize()) {
        CuttingMatBackground(modifier = Modifier.fillMaxSize())
        DappledSunlightOverlay(modifier = Modifier.fillMaxSize())
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun HomeScreenPreview() {
    MemoryMatTheme {
        HomeScreen()
    }
}
