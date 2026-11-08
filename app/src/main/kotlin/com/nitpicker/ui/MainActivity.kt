package com.nitpicker.ui
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.nitpicker.CompanionApp
import com.nitpicker.ui.navigation.AppNav
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { AppNav((application as CompanionApp).container) } }
    }
}
