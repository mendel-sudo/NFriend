package com.nfriend.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.nfriend.app.crypto.KeyManager

/**
 * First-launch onboarding: user sets an alias and generates their X25519 identity.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var keyManager: KeyManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        keyManager = KeyManager(this)

        // If identity already exists, skip to main
        if (keyManager.hasIdentity()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_onboarding)

        val aliasInput = findViewById<TextInputEditText>(R.id.alias_input)
        val createBtn = findViewById<MaterialButton>(R.id.btn_create_identity)
        val progress = findViewById<View>(R.id.onboarding_progress)
        val status = findViewById<View>(R.id.onboarding_status)

        createBtn.setOnClickListener {
            val alias = aliasInput.text?.toString()?.trim() ?: ""
            if (alias.isEmpty()) {
                aliasInput.error = "Please enter a name"
                return@setOnClickListener
            }
            if (alias.length < 2) {
                aliasInput.error = "Name must be at least 2 characters"
                return@setOnClickListener
            }

            // Show loading
            createBtn.isEnabled = false
            progress.visibility = View.VISIBLE
            status.visibility = View.VISIBLE

            // Generate keypair on background thread
            Thread {
                try {
                    keyManager.generateKeyPair(alias)

                    runOnUiThread {
                        Toast.makeText(this, "Identity created!", Toast.LENGTH_SHORT).show()
                        // Navigate to main
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        createBtn.isEnabled = true
                        progress.visibility = View.GONE
                        status.visibility = View.GONE
                        Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
    }
}
