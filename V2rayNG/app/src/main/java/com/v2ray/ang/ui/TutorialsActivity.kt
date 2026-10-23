package com.v2ray.ang.ui

import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar

class TutorialsActivity : BaseComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState) }

    @Composable
    override fun ScreenContent() = TutorialsScreen(onBack = ::finish)
}

@Composable
private fun TutorialsScreen(onBack: () -> Unit) {
    val sections = listOf(
        R.string.tutorial_setup_title to R.string.tutorial_setup_body,
        R.string.tutorial_settings_title to R.string.tutorial_settings_body,
        R.string.tutorial_server_title to R.string.tutorial_server_body,
    )
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { AppTopBar(title = stringResource(R.string.tutorials_title), onBackClick = onBack) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            sections.forEach { (title, body) ->
                var expanded by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { expanded = !expanded }) { Text(stringResource(title)) }
                if (expanded) Text(stringResource(body), modifier = Modifier.padding(12.dp))
            }
        }
    }
}
