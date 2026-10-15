package com.idlemaster.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.idlemaster.android.IdleMasterApp
import com.idlemaster.android.R
import com.idlemaster.android.data.IdleMode
import com.idlemaster.android.data.Settings
import com.idlemaster.android.data.SortMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: IdleMasterApp, onBack: () -> Unit) {
    val initial = remember { app.settings.settings.value }
    var mode by remember { mutableStateOf(initial.mode) }
    var sort by remember { mutableStateOf(initial.sort) }
    var onlyPlayed by remember { mutableStateOf(initial.idleOnlyPlayed) }
    var showName by remember { mutableStateOf(initial.showUsername) }
    var maxSim by remember { mutableFloatStateOf(initial.maxSimultaneous.toFloat()) }
    var blacklist by remember { mutableStateOf(initial.blacklist.sorted().joinToString("\n")) }
    var whitelist by remember { mutableStateOf(initial.whitelist.sorted().joinToString("\n")) }

    fun saveAndExit() {
        val new = initial.copy(
            mode = mode,
            sort = sort,
            idleOnlyPlayed = onlyPlayed,
            showUsername = showName,
            maxSimultaneous = maxSim.toInt(),
            blacklist = parseIds(blacklist),
            whitelist = parseIds(whitelist),
        )
        app.settings.update { new }
        // Same rule as frmMain.settingsToolStripMenuItem_Click: anything that changes what/how we idle restarts idling.
        if (new.copy(showUsername = initial.showUsername) != initial) app.engine.onSettingsChanged()
        onBack()
    }

    Scaffold(
        containerColor = SteamColors.Background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SteamColors.Surface),
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
                actions = { TextButton(onClick = { saveAndExit() }) { Text(stringResource(R.string.save)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Section(stringResource(R.string.settings_mode))
            listOf(
                IdleMode.FAST to R.string.mode_fast,
                IdleMode.ONE_THEN_MANY to R.string.mode_one_then_many,
                IdleMode.MANY_THEN_ONE to R.string.mode_many_then_one,
                IdleMode.ONE_BY_ONE to R.string.mode_one_by_one,
                IdleMode.WHITELIST to R.string.mode_whitelist,
            ).forEach { (m, label) ->
                RadioRow(stringResource(label), selected = mode == m) { mode = m }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Section(stringResource(R.string.settings_sort))
            listOf(
                SortMode.DEFAULT to R.string.sort_default,
                SortMode.MOST_CARDS to R.string.sort_most,
                SortMode.LEAST_CARDS to R.string.sort_least,
            ).forEach { (s, label) ->
                RadioRow(stringResource(label), selected = sort == s) { sort = s }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Section(stringResource(R.string.settings_other))
            SwitchRow(stringResource(R.string.only_played), onlyPlayed) { onlyPlayed = it }
            SwitchRow(stringResource(R.string.show_username), showName) { showName = it }
            Text(stringResource(R.string.max_simultaneous, maxSim.toInt()), Modifier.padding(top = 8.dp))
            Slider(
                value = maxSim,
                onValueChange = { maxSim = it },
                valueRange = 1f..Settings.MAX_STEAM_GAMES.toFloat(),
                steps = Settings.MAX_STEAM_GAMES - 2,
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            IdList(stringResource(R.string.blacklist), blacklist) { blacklist = it }
            Spacer(Modifier.padding(4.dp))
            IdList(stringResource(R.string.whitelist), whitelist) { whitelist = it }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = SteamColors.Blue, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun IdList(title: String, value: String, onChange: (String) -> Unit) {
    val count = parseIds(value).size
    Section("$title — ${stringResource(R.string.list_count, count)}")
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(stringResource(R.string.list_hint)) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
    )
}

private fun parseIds(text: String): Set<Int> =
    text.split(Regex("""[\s,;]+""")).mapNotNull { it.trim().toIntOrNull()?.takeIf { id -> id > 0 } }.toSet()
