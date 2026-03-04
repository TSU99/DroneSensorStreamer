package com.example.sensorstream

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import org.json.JSONObject
import java.util.*

class SensorService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var writableCharacteristic: BluetoothGattCharacteristic? = null

    private var gravity = FloatArray(3)
    private var geomagnetic = FloatArray(3)
    private var accel = FloatArray(3)
    private var gyro = FloatArray(3)
    private var orientation = FloatArray(3)

    private var lastLocation: Location? = null
    private val handler = Handler(Looper.getMainLooper())
    private val SERVICE_UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
    private val CHAR_UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")

    private var isConnected = false
    private var lastSentData = ""

    // Write queue to prevent BLE stack flooding
    private var writeInProgress = false
    private val writeQueue: ArrayDeque<ByteArray> = ArrayDeque()
    private var lastWriteTimestamp = 0L
    private val WRITE_TIMEOUT_MS = 500L

    // Wake lock to prevent Android from freezing the service
    private lateinit var wakeLock: PowerManager.WakeLock

    companion object {
        const val CHANNEL_ID = "SensorServiceChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "STOP_SERVICE"
        const val ACTION_STATUS_UPDATE = "com.example.sensorstream.STATUS_UPDATE"
        private const val TAG = "SensorService"
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Drone Sensor Streaming - Active"))

        // Acquire wake lock — prevents Android from suspending CPU during streaming
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SensorStream::BleStreamingWakeLock"
        )
        wakeLock.acquire()

        registerSensors()
        startLocationUpdates()
        startBleScan()
        startDataStreaming()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID, "Sensor Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(content: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Drone Sensor Streamer")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun registerSensors() {
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 20L)
            .setMinUpdateIntervalMillis(20L)
            .build()

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            lastLocation = locationResult.lastLocation
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        // Fully close any stale GATT before scanning
        bluetoothGatt?.close()
        bluetoothGatt = null

        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: run {
            Log.w(TAG, "BLE scanner unavailable — retrying in 2s")
            handler.postDelayed({ startBleScan() }, 2000)
            return
        }

        // Stop any existing scan before starting a new one
        try { scanner.stopScan(scanCallback) } catch (_: Exception) {}

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, scanCallback)
        Log.d(TAG, "BLE scan started")

        // Watchdog: if nothing connects in 10s, restart the scan
        handler.postDelayed({
            if (!isConnected) {
                Log.w(TAG, "Scan watchdog fired — restarting scan")
                startBleScan()
            }
        }, 10000)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val deviceName = result.device.name
            if (deviceName != null && deviceName.contains("DroneESP32")) {
                if (bluetoothGatt == null && !isConnected) {
                    Log.d(TAG, "DroneESP32 found — connecting")
                    bluetoothAdapter?.bluetoothLeScanner?.stopScan(this)
                    bluetoothGatt = result.device.connectGatt(
                        this@SensorService, false, // false = connect immediately, not lazy auto-connect
                        gattCallback, BluetoothDevice.TRANSPORT_LE
                    )
                    // Connection watchdog: if GATT doesn't connect in 8s, start over
                    handler.postDelayed({
                        if (!isConnected) {
                            Log.w(TAG, "Connection watchdog fired — restarting scan")
                            bluetoothGatt?.close()
                            bluetoothGatt = null
                            startBleScan()
                        }
                    }, 8000)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed with error: $errorCode — retrying in 2s")
            handler.postDelayed({ startBleScan() }, 2000)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connected to GATT server.")
                gatt.requestMtu(512)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected from GATT server. Status: $status")
                isConnected = false
                writableCharacteristic = null
                writeInProgress = false
                writeQueue.clear()
                // Close the old GATT fully before reconnecting
                gatt.close()
                bluetoothGatt = null
                broadcastStatus()
                // Small delay before rescanning to let BLE stack settle
                handler.postDelayed({ startBleScan() }, 500)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "MTU changed to $mtu")
                gatt?.discoverServices()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                writableCharacteristic = service?.getCharacteristic(CHAR_UUID)
                if (writableCharacteristic != null) {
                    isConnected = true
                    broadcastStatus()
                    Log.d(TAG, "Service and Characteristic discovered.")
                }
            }
        }

        // With WRITE_TYPE_NO_RESPONSE this may not always fire, but handle it if it does
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Characteristic write failed with status: $status")
            }
            writeInProgress = false
            processWriteQueue()
        }
    }

    private fun processWriteQueue() {
        // Watchdog: if a write has been "in progress" for too long, the callback was lost — reset
        if (writeInProgress && (System.currentTimeMillis() - lastWriteTimestamp) > WRITE_TIMEOUT_MS) {
            Log.w(TAG, "Write timeout — resetting writeInProgress flag")
            writeInProgress = false
        }

        if (writeInProgress || writeQueue.isEmpty()) return
        val char = writableCharacteristic ?: return
        if (!isConnected || bluetoothGatt == null) {
            writeQueue.clear()
            return
        }
        val bytes = writeQueue.removeFirst()
        writeInProgress = true
        lastWriteTimestamp = System.currentTimeMillis()

        @SuppressLint("MissingPermission")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bluetoothGatt?.writeCharacteristic(char, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            char.value = bytes
            bluetoothGatt?.writeCharacteristic(char)
        }
        // NO_RESPONSE writes don't wait for ACK — safe to clear immediately
        writeInProgress = false
    }

    private fun startDataStreaming() {
        handler.post(object : Runnable {
            override fun run() {
                sendData()
                handler.postDelayed(this, 20)
            }
        })
    }

    private fun sendData() {
        val loc = lastLocation
        val json = JSONObject().apply {
            put("ts", System.currentTimeMillis())
            put("lat", loc?.latitude ?: 0.0)
            put("lon", loc?.longitude ?: 0.0)
            put("alt", loc?.altitude ?: 0.0)
            put("hdop", loc?.accuracy ?: 0.0)
            put("head", Math.toDegrees(orientation[0].toDouble()))
            put("ax", accel[0])
            put("ay", accel[1])
            put("az", accel[2])
            put("gx", gyro[0])
            put("gy", gyro[1])
            put("gz", gyro[2])
            put("mx", geomagnetic[0])
            put("my", geomagnetic[1])
            put("mz", geomagnetic[2])
        }

        val dataString = json.toString()
        lastSentData = dataString

        if (isConnected && bluetoothGatt != null) {
            val bytes = dataString.toByteArray()
            // Keep queue shallow — drop old data if falling behind to avoid memory buildup
            if (writeQueue.size < 3) {
                writeQueue.addLast(bytes)
            } else {
                Log.w(TAG, "Write queue full — dropping packet to prevent BLE stack overflow")
            }
            processWriteQueue()
        }

        broadcastStatus()
    }

    private fun broadcastStatus() {
        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            putExtra("connected", isConnected)
            putExtra("data", lastSentData)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, gravity, 0, event.values.size)
                System.arraycopy(event.values, 0, accel, 0, event.values.size)
            }
            Sensor.TYPE_GYROSCOPE -> {
                System.arraycopy(event.values, 0, gyro, 0, event.values.size)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, geomagnetic, 0, event.values.size)
            }
        }

        val r = FloatArray(9)
        val i = FloatArray(9)
        if (SensorManager.getRotationMatrix(r, i, gravity, geomagnetic)) {
            SensorManager.getOrientation(r, orientation)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        sensorManager.unregisterListener(this)
        fusedLocationClient.removeLocationUpdates(locationCallback)
        writeQueue.clear()
        if (wakeLock.isHeld) wakeLock.release()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}