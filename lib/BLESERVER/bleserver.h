#pragma once

#include <NimBLEDevice.h>

#include "battery.h"
#include "laptimer.h"

// See docs/BLE_PROTOCOL.md for the full description of the GATT service.
#define BLE_SERVICE_UUID "5a3e0001-7c1f-4d8b-9a6e-2f4c1b0d9e10"
#define BLE_CONFIG_UUID "5a3e0002-7c1f-4d8b-9a6e-2f4c1b0d9e10"
#define BLE_CONTROL_UUID "5a3e0003-7c1f-4d8b-9a6e-2f4c1b0d9e10"
#define BLE_RSSI_UUID "5a3e0004-7c1f-4d8b-9a6e-2f4c1b0d9e10"
#define BLE_LAP_UUID "5a3e0005-7c1f-4d8b-9a6e-2f4c1b0d9e10"
#define BLE_STATUS_UUID "5a3e0006-7c1f-4d8b-9a6e-2f4c1b0d9e10"
#define BLE_INFO_UUID "5a3e0007-7c1f-4d8b-9a6e-2f4c1b0d9e10"
#define BLE_OTA_CTRL_UUID "5a3e0008-7c1f-4d8b-9a6e-2f4c1b0d9e10"
#define BLE_OTA_DATA_UUID "5a3e0009-7c1f-4d8b-9a6e-2f4c1b0d9e10"

#define BLE_RSSI_SEND_INTERVAL_MS 100
#define BLE_STATUS_SEND_INTERVAL_MS 2000
#define BLE_OTA_REBOOT_DELAY_MS 1000

enum BleControlCommand : uint8_t {
    CMD_TIMER_START = 0x01,
    CMD_TIMER_STOP = 0x02,
};

enum BleOtaCommand : uint8_t {
    OTA_BEGIN = 0x01,  // + uint32 LE image size + 16 byte MD5 digest
    OTA_END = 0x02,
    OTA_ABORT = 0x03,
    OTA_ERROR = 0xFF,  // only sent by the timer, asynchronously
};

enum BleOtaStatus : uint8_t {
    OTA_OK = 0,
    OTA_ERR_BEGIN = 1,
    OTA_ERR_WRITE = 2,
    OTA_ERR_END = 3,
    OTA_ERR_STATE = 4,
    OTA_ERR_SIZE = 5,
};

class BleServer : public NimBLEServerCallbacks, public NimBLECharacteristicCallbacks {
   public:
    void init(Config *config, LapTimer *lapTimer, BatteryMonitor *batMonitor, Buzzer *buzzer, Led *l);
    void handleBleUpdate(uint32_t currentTimeMs);

    // NimBLEServerCallbacks
    void onConnect(NimBLEServer *server, ble_gap_conn_desc *desc) override;
    void onDisconnect(NimBLEServer *server, ble_gap_conn_desc *desc) override;
    // NimBLECharacteristicCallbacks
    void onRead(NimBLECharacteristic *chr, ble_gap_conn_desc *desc) override;
    void onWrite(NimBLECharacteristic *chr, ble_gap_conn_desc *desc) override;

   private:
    void sendStatus();
    void handleControl(const uint8_t *data, size_t len);
    void handleOtaControl(const uint8_t *data, size_t len, uint16_t connHandle);
    void handleOtaData(const uint8_t *data, size_t len, uint16_t connHandle);
    void otaReply(uint8_t command, uint8_t status);
    void otaReset();

    Config *conf;
    LapTimer *timer;
    BatteryMonitor *monitor;
    Buzzer *buz;
    Led *led;

    NimBLEServer *server = nullptr;
    NimBLECharacteristic *configChr = nullptr;
    NimBLECharacteristic *controlChr = nullptr;
    NimBLECharacteristic *rssiChr = nullptr;
    NimBLECharacteristic *lapChr = nullptr;
    NimBLECharacteristic *statusChr = nullptr;
    NimBLECharacteristic *infoChr = nullptr;
    NimBLECharacteristic *otaCtrlChr = nullptr;
    NimBLECharacteristic *otaDataChr = nullptr;

    uint32_t rssiSentMs = 0;
    uint32_t statusSentMs = 0;

    volatile bool otaActive = false;
    volatile uint16_t otaConnHandle = BLE_HS_CONN_HANDLE_NONE;
    volatile uint32_t otaSize = 0;
    volatile uint32_t otaReceived = 0;
    volatile uint32_t rebootAtMs = 0;
};
