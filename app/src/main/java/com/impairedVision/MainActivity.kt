package com.impairedVision

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.impairedVision.ui.theme.GuideGlassTheme
import com.impairedVision.yolo.MainYolo // Import your Yolo activity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GuideGlassTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // We pass the modifier to handle the system bars (padding)
                    MainMenu(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun MainMenu(modifier: Modifier = Modifier) {
    // 1. Get the context so we can launch a new Activity
    val context = LocalContext.current

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Guide Glass",
            fontSize = 32.sp,
            modifier = Modifier.padding(bottom = 48.dp)
        )

        // 2. The Button to start the Vision System
        Button(
            onClick = {
                // This command launches your other file!
                val intent = Intent(context, MainYolo::class.java)
                context.startActivity(intent)
            },
            modifier = Modifier
                .fillMaxWidth(0.8f) // Make it 80% of screen width
                .height(80.dp)      // Make it tall/easy to tap
        ) {
            Text(text = "Start Vision", fontSize = 24.sp)
        }
    }
}