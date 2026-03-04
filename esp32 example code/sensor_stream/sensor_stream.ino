#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <ArduinoJson.h>
#include <esp_task_wdt.h>

#define SERVICE_UUID        "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define CHARACTERISTIC_UUID "beb5483e-36e1-4688-b7f5-ea07361b26a8"

//THIS IS EXAMPLE CODE TO TEST YOUR CONNECTION IS WORKING AND WHAT UPDATE FREQUENCY YOU ARE GETTING

BLEServer* pServer = nullptr;
BLECharacteristic* pCharacteristic = nullptr;
bool deviceConnected = false;

struct SensorPacket {
    unsigned long ts;
    double lat, lon;
    float alt, hdop, heading;
    float ax, ay, az;
    float gx, gy, gz;
} latestPacket;

volatile bool newDataAvailable = false;

// Track heap over time to detect leaks
uint32_t heapAtConnect = 0;
uint32_t minHeapSeen = 0xFFFFFFFF;

class MyServerCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) {
        deviceConnected = true;
        heapAtConnect = esp_get_free_heap_size();
        minHeapSeen = heapAtConnect;
        Serial.printf("Phone connected! Heap at connect: %d\n", heapAtConnect);
    }

    void onDisconnect(BLEServer* pServer) {
        deviceConnected = false;
        uint32_t heapNow = esp_get_free_heap_size();
        Serial.printf("Disconnected! Heap now: %d | Min seen: %d | Leaked: %d bytes\n",
                      heapNow,
                      minHeapSeen,
                      (int)heapAtConnect - (int)heapNow);
        Serial.printf("   Stack HWM: %d\n", uxTaskGetStackHighWaterMark(NULL));
        Serial.println("Restarting advertising...");
        pServer->startAdvertising();
    }
};

class MyCallbacks : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pCharacteristic) {
        String value = pCharacteristic->getValue();
        if (value.length() == 0) return;

        StaticJsonDocument<512> doc;
        DeserializationError err = deserializeJson(doc, value);

        if (err == DeserializationError::Ok) {
            latestPacket.ts      = doc["ts"];
            latestPacket.lat     = doc["lat"];
            latestPacket.lon     = doc["lon"];
            latestPacket.alt     = doc["alt"];
            latestPacket.hdop    = doc["hdop"];
            latestPacket.heading = doc["head"];
            latestPacket.ax      = doc["ax"];
            latestPacket.ay      = doc["ay"];
            latestPacket.az      = doc["az"];
            latestPacket.gx      = doc["gx"];
            latestPacket.gy      = doc["gy"];
            latestPacket.gz      = doc["gz"];
            newDataAvailable = true;
        } else {
            Serial.printf(" JSON parse error: %s | len=%d\n", err.c_str(), value.length());
        }
    }
};

void setup() {
    Serial.begin(115200);
    Serial.println("\n ESP32 BLE Sensor Receiver — heap monitoring build");
    Serial.printf("Boot heap: %d\n", esp_get_free_heap_size());

    esp_task_wdt_config_t wdt_config = {
        .timeout_ms     = 10000,
        .idle_core_mask = 0,
        .trigger_panic  = true
    };
    esp_task_wdt_init(&wdt_config);
    esp_task_wdt_add(NULL);

    BLEDevice::init("DroneESP32");
    pServer = BLEDevice::createServer();
    pServer->setCallbacks(new MyServerCallbacks());

    BLEService *pService = pServer->createService(SERVICE_UUID);
    pCharacteristic = pService->createCharacteristic(
                        CHARACTERISTIC_UUID,
                        BLECharacteristic::PROPERTY_WRITE |
                        BLECharacteristic::PROPERTY_NOTIFY
                      );
    pCharacteristic->setCallbacks(new MyCallbacks());
    pCharacteristic->addDescriptor(new BLE2902());
    pService->start();

    BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
    pAdvertising->addServiceUUID(SERVICE_UUID);
    pAdvertising->setScanResponse(false);
    pAdvertising->setMinPreferred(0x06);
    BLEDevice::startAdvertising();

    Serial.println("Advertising as 'DroneESP32' — ready for phone");
}

unsigned long lastPrint = 0;
int packetCount = 0;

void loop() {
    esp_task_wdt_reset();

    if (newDataAvailable) {
        newDataAvailable = false;
        packetCount++;
    }

    // Track minimum heap seen during session
    uint32_t currentHeap = esp_get_free_heap_size();
    if (currentHeap < minHeapSeen) {
        minHeapSeen = currentHeap;
    }

    if (millis() - lastPrint > 100) {
        Serial.printf("%d pkt/s | Connected: %s | Heading: %.1f° | Heap: %d (min: %d) | Stack HWM: %d\n",
                      packetCount,
                      deviceConnected ? "YES" : "NO",
                      latestPacket.heading,
                      currentHeap,
                      minHeapSeen,
                      uxTaskGetStackHighWaterMark(NULL));
        packetCount = 0;
        lastPrint = millis();
    }

    delay(1); // was 5 — gives BLE stack more CPU time for ACKs
}
