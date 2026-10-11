package com.puzzowl.bot

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        root.addView(TextView(this).apply {
            textSize = 16f
            text = "Puzzowl Bot\n\n1. Neeche button dabao -> Accessibility me 'Puzzowl Bot' ON karo.\n" +
                "2. Puzzowl game kholo (Play Now wali screen).\n" +
                "3. Screen ke right-middle me chhota START button dikhega, use dabao.\n" +
                "4. Rokne ke liye wahi button (STOP) dabao."
        })
        root.addView(Button(this).apply {
            text = "Open Accessibility Settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        setContentView(root)
    }
}
