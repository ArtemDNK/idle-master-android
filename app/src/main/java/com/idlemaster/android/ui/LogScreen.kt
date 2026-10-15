package com.idlemaster.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.idlemaster.android.R
import com.idlemaster.android.data.DebugLog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val version by DebugLog.version.collectAsState()
    val text = remember(version) { DebugLog.snapshot() }
    val vScroll = rememberScrollState()
    LaunchedEffect(version) { vScroll.scrollTo(vScroll.maxValue) }

    Scaffold(
        containerColor = SteamColors.Background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SteamColors.Surface),
                title = { Text(stringResource(R.string.log)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
                actions = {
                    TextButton(onClick = { copy(ctx, text) }) { Text(stringResource(R.string.copy)) }
                    IconButton(onClick = { share(ctx, text) }) { Icon(Icons.Default.Share, null) }
                },
            )
        },
    ) { padding ->
        SelectionContainer(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(vScroll)
                .horizontalScroll(rememberScrollState())
                .padding(8.dp),
        ) {
            Text(text.ifEmpty { "—" }, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
        }
    }
}

private fun copy(ctx: Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Idle Master log", text))
    Toast.makeText(ctx, R.string.copied, Toast.LENGTH_SHORT).show()
}

private fun share(ctx: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(send, null))
}
