package com.faceauth.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.faceauth.app.databinding.ActivityMainBinding
import com.faceauth.app.security.DemoFileCreator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val TAG = "MainActivity"

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) openCamera()
        else Toast.makeText(this,
            "Camera permission required for face authentication",
            Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvVersion.text = "FaceAuth v1.0  ·  MobileNetV2  ·  threshold 85%"

        // Create demo files in the background so they exist before
        // Class B is detected. These are the files the payload will encrypt.
        lifecycleScope.launch {
            val created = withContext(Dispatchers.IO) {
                DemoFileCreator.createDemoFiles(this@MainActivity)
            }
            if (created.isNotEmpty()) {
                Log.i(TAG, "Created ${created.size} demo sensitive file(s)")
                binding.tvVersion.text =
                    "FaceAuth v1.0  ·  ${created.size} demo files ready"
            }
        }

        binding.btnAuthenticate.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) openCamera()
            else permLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun openCamera() =
        startActivity(Intent(this, CameraActivity::class.java))
}
