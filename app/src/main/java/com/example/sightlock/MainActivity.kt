package com.example.sightlock

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sightlock.ui.theme.SightLockTheme

class MainActivity : ComponentActivity() {

    private val REGISTER_FACE_REQUEST_CODE = 201

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SightLockTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PrivacyShieldScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Trigger recomposition when registration completes by recreating
        if (requestCode == REGISTER_FACE_REQUEST_CODE && resultCode == RegisterFaceActivity.RESULT_REGISTERED) {
            recreate()
        }
    }
}

@Composable
fun PrivacyShieldScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    var hasCameraPermission by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    var hasOverlayPermission by remember {
        mutableStateOf(Settings.canDrawOverlays(context))
    }
    var isFaceRegistered by remember {
        mutableStateOf(OwnerFaceStore.hasEmbedding(context))
    }
    var isServiceRunning by remember { mutableStateOf(false) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { hasCameraPermission = it }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { hasOverlayPermission = Settings.canDrawOverlays(context) }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("SightLock", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Privacy Shield", fontSize = 14.sp, color = Color.Gray)
        Spacer(modifier = Modifier.height(40.dp))

        // Step 1: Camera permission
        if (!hasCameraPermission) {
            Button(onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }) {
                Text("Grant Camera Permission")
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Step 2: Overlay permission
        if (!hasOverlayPermission) {
            Button(onClick = {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                overlayPermissionLauncher.launch(intent)
            }) {
                Text("Grant Overlay Permission")
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Step 3: Register owner face
        if (hasCameraPermission) {
            if (!isFaceRegistered) {
                Button(
                    onClick = {
                        (context as? ComponentActivity)?.startActivityForResult(
                            Intent(context, RegisterFaceActivity::class.java),
                            201
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1))
                ) {
                    Text("Register Owner Face")
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Required before starting the shield",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            } else {
                Text("✓  Owner face registered", fontSize = 13.sp, color = Color(0xFF22C55E))
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedButton(
                    onClick = {
                        // Re-enroll: clear and go to registration
                        OwnerFaceStore.clearEmbedding(context)
                        isFaceRegistered = false
                        (context as? ComponentActivity)?.startActivityForResult(
                            Intent(context, RegisterFaceActivity::class.java),
                            201
                        )
                    }
                ) {
                    Text("Re-enroll Face", fontSize = 12.sp)
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        // Step 4: Start / Stop shield (requires all permissions + face registered)
        if (hasCameraPermission && hasOverlayPermission && isFaceRegistered) {
            Button(
                onClick = {
                    if (isServiceRunning) {
                        PrivacyShieldService.stopService(context)
                        isServiceRunning = false
                    } else {
                        PrivacyShieldService.startService(context)
                        isServiceRunning = true
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isServiceRunning) Color(0xFFEF4444) else Color(0xFF22C55E)
                )
            ) {
                Text(if (isServiceRunning) "Stop Privacy Shield" else "Start Privacy Shield")
            }
        } else if (!isFaceRegistered) {
            Text("Register your face to activate the shield", fontSize = 13.sp, color = Color.Gray)
        } else {
            Text("Please grant all permissions to use the Privacy Shield.", fontSize = 13.sp, color = Color.Gray)
        }
    }
}