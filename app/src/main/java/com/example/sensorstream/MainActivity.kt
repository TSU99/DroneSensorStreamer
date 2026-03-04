package com.example.sensorstream

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.sensorstream.ui.theme.SensorStreamTheme

class MainActivity : ComponentActivity() {

    private var isConnected by mutableStateOf(false)
    private var lastData by mutableStateOf("No data sent yet")

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == SensorService.ACTION_STATUS_UPDATE) {
                isConnected = intent.getBooleanExtra("connected", false)
                lastData = intent.getStringExtra("data") ?: "No data"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val filter = IntentFilter(SensorService.ACTION_STATUS_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @SuppressLint("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }

        setContent {
            SensorStreamTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen(
                        isConnected = isConnected,
                        lastData = lastData,
                        onStart = { checkBluetoothAndPermissions() },
                        onStop = { stopService() }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(statusReceiver)
        } catch (e: Exception) {
            // Receiver not registered
        }
    }

    private fun checkBluetoothAndPermissions() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        
        if (adapter == null) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_LONG).show()
            return
        }

        if (!adapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            requestBluetoothLauncher.launch(enableBtIntent)
        } else {
            checkAndRequestPermissions()
        }
    }

    private val requestBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            checkAndRequestPermissions()
        } else {
            Toast.makeText(this, "Bluetooth is required", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        
        // Android 12+ Bluetooth permissions
        permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        
        // Location permissions
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        
        // Android 13+ Notification permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        // Android 14+ Foreground Service type permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE_LOCATION)
            permissions.add(Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE)
        }

        requestPermissionsLauncher.launch(permissions.toTypedArray())
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.all { it.value }) {
            requestBackgroundLocation()
        } else {
            Toast.makeText(this, "Missing required permissions", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            ignoreBatteryOptimizations()
        }
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            ignoreBatteryOptimizations()
        } else {
            Toast.makeText(this, "Background location recommended for stability", Toast.LENGTH_SHORT).show()
            ignoreBatteryOptimizations() // Continue anyway, service might still run
        }
    }

    @SuppressLint("BatteryLife")
    private fun ignoreBatteryOptimizations() {
        val packageName = packageName
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
        startSensorService()
    }

    private fun startSensorService() {
        val intent = Intent(this, SensorService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopService() {
        val intent = Intent(this, SensorService::class.java).apply {
            action = SensorService.ACTION_STOP
        }
        startService(intent)
        isConnected = false
        lastData = "Service Stopped"
    }
}

@Composable
fun MainScreen(isConnected: Boolean, lastData: String, onStart: () -> Unit, onStop: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "Drone Sensor Streamer", fontSize = 24.sp, modifier = Modifier.padding(bottom = 24.dp))
        
        Text(
            text = if (isConnected) "Status: CONNECTED" else "Status: DISCONNECTED",
            color = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            fontSize = 18.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onStart, 
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text("START SERVICE", fontSize = 18.sp)
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = onStop, 
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) {
            Text("STOP SERVICE", fontSize = 18.sp)
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(text = "Real-time Telemetry (50Hz):", fontSize = 14.sp, modifier = Modifier.align(Alignment.Start))
        
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 150.dp)
                .padding(top = 8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 4.dp
        ) {
            Text(
                text = lastData,
                modifier = Modifier.padding(12.dp),
                fontSize = 11.sp,
                lineHeight = 16.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )
        }
    }
}