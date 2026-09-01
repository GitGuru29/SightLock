                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           package com.example.sightlock

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sightlock.ui.theme.*

class MainActivity : ComponentActivity() {

    private val REGISTER_FACE_REQUEST_CODE = 201

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SightLockTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = SpaceBlack
                ) { innerPadding ->
                    PrivacyShieldScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }

    @Deprecated("Deprecated in Java", ReplaceWith("Recreate if needed manually or use ActivityResult APIs"))
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
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

    // Pulsing animation for active shield
    val infiniteTransition = rememberInfiniteTransition(label = "shield_pulse")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha_pulse"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(SpaceBlack, DeepGray)
                )
            )
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(40.dp))

        // Cyber Header
        Text(
            text = "SIGHTLOCK",
            fontSize = 32.sp,
            fontWeight = FontWeight.Black,
            color = NeonCyan,
            letterSpacing = 4.sp
        )
        Text(
            text = "BIOMETRIC PRIVACY SHIELD",
            fontSize = 12.sp,
            color = TextSecondary,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(48.dp))

        // Configuration Panel (Glassmorphic Card)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DefaultCardBg),
            border = BorderStroke(1.dp, NeonCyanDim),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = "SYSTEM STATUS",
                    color = NeonCyan,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Step 1: Camera
                StatusRow(
                    label = "OPTICS SENSOR (CAMERA)",
                    isComplete = hasCameraPermission,
                    onAction = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }
                )
                
                Spacer(modifier = Modifier.height(12.dp))

                // Step 2: Overlay
                StatusRow(
                    label = "HUD OVERLAY",
                    isComplete = hasOverlayPermission,
                    onAction = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        overlayPermissionLauncher.launch(intent)
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Step 3: Face Registration
                StatusRow(
                    label = "OWNER BIOMETRIC DATA",
                    isComplete = isFaceRegistered,
                    buttonText = if (isFaceRegistered) "RE-ENROLL" else "REGISTER",
                    onAction = {
                        if (isFaceRegistered) OwnerFaceStore.clearEmbedding(context)
                        isFaceRegistered = false
                        (context as? ComponentActivity)?.startActivityForResult(
                            Intent(context, RegisterFaceActivity::class.java),
                            201
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Shield Control Section
        val allSystemsGreen = hasCameraPermission && hasOverlayPermission && isFaceRegistered
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            if (allSystemsGreen) {
                // Outer glow when running
                if (isServiceRunning) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .height(65.dp)
                            .clip(RoundedCornerShape(32.dp))
                            .background(AlertRed.copy(alpha = glowAlpha))
                    )
                }
                
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
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(56.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isServiceRunning) AlertRed else SecureGreen,
                        contentColor = SpaceBlack
                    )
                ) {
                    Text(
                        text = if (isServiceRunning) "TERMINATE SHIELD" else "ENGAGE SHIELD",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.5.sp
                    )
                }
            } else {
                Text(
                    text = "AWAITING SYSTEM CONFIGURATION...",
                    color = AlertRed,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

// Helper Composable for the Status items (Glassmorphic internal UI)
val DefaultCardBg = Color(0x3314141C)

@Composable
fun StatusRow(
    label: String,
    isComplete: Boolean,
    buttonText: String = "GRANT",
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isComplete) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (isComplete) SecureGreen else AlertRed,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (isComplete) "INITIALIZED" else "OFFLINE",
                    color = if (isComplete) SecureGreen else AlertRed,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        
        OutlinedButton(
            onClick = onAction,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = if (isComplete) TextSecondary else NeonCyan
            ),
            border = BorderStroke(
                1.dp, 
                if (isComplete) Color.Transparent else NeonCyan
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            modifier = Modifier.height(32.dp)
        ) {
            Text(
                text = buttonText, 
                fontSize = 10.sp, 
                fontWeight = FontWeight.Bold
            )
        }
    }
}