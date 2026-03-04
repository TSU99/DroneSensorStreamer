# DroneSensorStreamer

A lightweight Android utility that streams high-frequency sensor and GPS telemetry to an ESP32 via Bluetooth Low Energy (BLE). 

This app was built to turn a Redmi Note 9 Pro (or any Android 12+ device) into a high-performance sensor hub for drone projects.

### Features
- **50Hz Update Rate:** Streams a JSON telemetry packet every 20ms.
- **Full Telemetry:** 
    - GPS: Latitude, Longitude, Altitude, and Accuracy (HDOP).
    - Orientation: Compass heading.
    - IMU: 3-axis Accelerometer, Gyroscope, and Magnetometer.
- **Persistent Connection:** Runs as a foreground service with a Partial Wake Lock to prevent the CPU from sleeping during flight/streaming.
- **Auto-Discovery:** Automatically scans for and connects to GATT servers named "DroneESP32".
- **Crash-Proof:** Includes connection watchdogs and automated reconnection logic.

### Tech Stack
- **Language:** Kotlin
- **UI:** Jetpack Compose
- **Background:** Android Foreground Service (API 31+)
- **Location:** Google Play Services (FusedLocationProviderClient)
- **BLE:** Android Bluetooth GATT (MTU 512 requested for full JSON strings)

### JSON Payload Format
```json
{
  "ts": 1700000000000,
  "lat": 0.0, "lon": 0.0, "alt": 0.0, "hdop": 1.0,
  "head": 180.0,
  "ax": 0.0, "ay": 0.0, "az": 9.8,
  "gx": 0.0, "gy": 0.0, "gz": 0.0,
  "mx": 0.0, "my": 0.0, "mz": 0.0
}
```

### Setup
1. Ensure your ESP32 is advertising as "DroneESP32".
2. Open the app and grant Location, Bluetooth, and Battery Optimization permissions.
3. Tap "Start Streaming" to begin the data flow.
