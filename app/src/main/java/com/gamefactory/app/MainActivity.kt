package com.gamefactory.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.gamefactory.app.ui.FactoryApp

class MainActivity : ComponentActivity() {

    private val viewModel: FactoryViewModel by viewModels { FactoryViewModel.factory(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FactoryApp(viewModel)
        }
    }
}
