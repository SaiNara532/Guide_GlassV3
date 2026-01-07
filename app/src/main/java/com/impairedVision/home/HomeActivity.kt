package com.impairedVision.home

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.impairedVision.R
import com.impairedVision.yolo.MainYolo
import android.widget.Button

class HomeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        val btnYolo = findViewById<Button>(R.id.btnYolo)
        btnYolo.setOnClickListener {
            startActivity(Intent(this, MainYolo::class.java))
        }
    }
}
