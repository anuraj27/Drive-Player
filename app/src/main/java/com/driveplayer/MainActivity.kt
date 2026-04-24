package com.driveplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.media3.common.util.UnstableApi
import com.driveplayer.di.AppModule
import com.driveplayer.navigation.AppNavigation
import com.driveplayer.ui.theme.DrivePlayerTheme

@UnstableApi
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppModule.init(this)
        setContent {
            DrivePlayerTheme {
                AppNavigation()
            }
        }
    }
}
