package com.v2ray.ang.ui.base

import android.os.Bundle
import android.os.SystemClock
import android.view.ViewTreeObserver
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.ui.compose.AppTheme
import com.v2ray.ang.ui.main.MainActivity
import com.v2ray.ang.util.PerformanceDiagnostics

abstract class BaseComponentActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val screenStartedAt = SystemClock.elapsedRealtime()
        super.onCreate(savedInstanceState)
        AppLocaleManager.onActivityCreated(this)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                ScreenContent()
            }
        }
        val contentView = window.decorView
        contentView.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (contentView.viewTreeObserver.isAlive) {
                    contentView.viewTreeObserver.removeOnPreDrawListener(this)
                }
                PerformanceDiagnostics.recordScreenFirstDraw(
                    this@BaseComponentActivity.javaClass.simpleName,
                    SystemClock.elapsedRealtime() - screenStartedAt,
                )
                if (this@BaseComponentActivity is MainActivity) {
                    PerformanceDiagnostics.recordColdStart(SystemClock.elapsedRealtime())
                }
                return true
            }
        })
    }

    @Composable
    protected abstract fun ScreenContent()
}
