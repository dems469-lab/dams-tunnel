package com.v2ray.ang.ui.checkupdate

import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.NavigationBarsSpacer
import com.v2ray.ang.ui.compose.SettingsMenuItem
import com.v2ray.ang.util.Utils

class CheckUpdateActivity : BaseComponentActivity() {
    @Composable
    override fun ScreenContent() {
        CheckUpdateScreen(onBackClick = { finish() })
    }
}

@Composable
fun CheckUpdateScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.update_check_for_update),
                onBackClick = onBackClick,
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.dams_update_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 16.dp),
            )
            SettingsMenuItem(
                icon = painterResource(R.drawable.ic_check_update_24dp),
                title = stringResource(R.string.dams_update_view_releases),
                onClick = { Utils.openUri(context, AppConfig.DAMS_RELEASES_URL) },
            )
            Text(
                text = stringResource(R.string.dams_update_security_warning),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            NavigationBarsSpacer()
        }
    }
}
