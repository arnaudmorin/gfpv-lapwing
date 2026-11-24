#include "bleserver.h"

#include <Update.h>
#include <esp_mac.h>

#include "debug.h"

static const char *ble_name_prefix = "Lapwing";

static void putUint16(uint8_t *buf, uint16_t v) {
    buf[0] = v & 0xFF;
    buf[1] = (v >> 8) & 0xFF;
}

static void putUint32(uint8_t *buf, uint32_t v) {
    for (int i = 0; i < 4; i++) buf[i] = (v >> (8 * i)) & 0xFF;
}

static uint32_t getUint32(const uint8_t *buf) {
    return (uint32_t)buf[0] | ((uint32_t)buf[1] << 8) | ((uint32_t)buf[2] << 16) | ((uint32_t)buf[3] << 24);
}

void BleServer::init(Config *config, LapTimer *lapTimer, BatteryMonitor *batMonitor, Buzzer *buzzer, Led *l) {
    conf = config;
    timer = lapTimer;
    monitor = batMonitor;
    buz = buzzer;
    led = l;

    // Device name ends with the last 2 bytes of the BT MAC, e.g. Lapwing_A1B2
    uint8_t mac[6];
    esp_read_mac(mac, ESP_MAC_BT);
    char name[20];
    snprintf(name, sizeof(name), "%s_%02X%02X", ble_name_prefix, mac[4], mac[5]);

    NimBLEDevice::init(name);
    NimBLEDevice::setMTU(BLE_ATT_MTU_MAX);
    NimBLEDevice::setPower(ESP_PWR_LVL_P9);

    server = NimBLEDevice::createServer();
    server->setCallbacks(this, false);

    NimBLEService *svc = server->createService(BLE_SERVICE_UUID);
    configChr = svc->createCharacteristic(BLE_CONFIG_UUID, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::WRITE);
    controlChr = svc->createCharacteristic(BLE_CONTROL_UUID, NIMBLE_PROPERTY::WRITE);
    rssiChr = svc->createCharacteristic(BLE_RSSI_UUID, NIMBLE_PROPERTY::NOTIFY);
    lapChr = svc->createCharacteristic(BLE_LAP_UUID, NIMBLE_PROPERTY::NOTIFY);
    statusChr = svc->createCharacteristic(BLE_STATUS_UUID, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
    infoChr = svc->createCharacteristic(BLE_INFO_UUID, NIMBLE_PROPERTY::READ);
    otaCtrlChr = svc->createCharacteristic(BLE_OTA_CTRL_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::NOTIFY);
    otaDataChr = svc->createCharacteristic(BLE_OTA_DATA_UUID, NIMBLE_PROPERTY::WRITE);

    configChr->setCallbacks(this);
    controlChr->setCallbacks(this);
    statusChr->setCallbacks(this);
    otaCtrlChr->setCallbacks(this);
    otaDataChr->setCallbacks(this);

    char info[192];
    snprintf(info, sizeof(info), "{\"fw\":\"%s\",\"chip\":\"%s\",\"rev\":%u,\"name\":\"%s\",\"board\":\"%s\"}",
             FIRMWARE_VERSION, ESP.getChipModel(), ESP.getChipRevision(), name, BOARD_NAME);
    infoChr->setValue((uint8_t *)info, strlen(info));

    svc->start();

    NimBLEAdvertising *adv = NimBLEDevice::getAdvertising();
    adv->addServiceUUID(BLE_SERVICE_UUID);
    adv->setScanResponse(true);  // the name goes into the scan response, the 128-bit UUID fills the advertisement
    adv->start();

    DEBUG("BLE advertising as %s\n", name);
}

void BleServer::onConnect(NimBLEServer *srv, ble_gap_conn_desc *desc) {
    DEBUG("BLE client connected (handle %u)\n", desc->conn_handle);
    buz->beep(200);
    led->on(200);
    // Keep advertising so a second phone (e.g. a spotter) can connect too.
    if (srv->getConnectedCount() < CONFIG_BT_NIMBLE_MAX_CONNECTIONS) {
        NimBLEDevice::startAdvertising();
    }
}

void BleServer::onDisconnect(NimBLEServer *srv, ble_gap_conn_desc *desc) {
    DEBUG("BLE client disconnected (handle %u)\n", desc->conn_handle);
    if (otaActive && otaConnHandle == desc->conn_handle) {
        DEBUG("OTA client went away, aborting update\n");
        otaReset();
    }
    buz->beep(100);
    led->blink(200);
    NimBLEDevice::startAdvertising();
}

void BleServer::onRead(NimBLECharacteristic *chr, ble_gap_conn_desc *desc) {
    if (chr == configChr) {
        char buf[256];
        size_t len = conf->toJson(buf, sizeof(buf));
        configChr->setValue((uint8_t *)buf, len);
        led->on(200);
    } else if (chr == statusChr) {
        uint8_t status[2] = {monitor->getBatteryVoltage(), (uint8_t)(timer->isRunning() ? 1 : 0)};
        statusChr->setValue(status, sizeof(status));
    }
}

void BleServer::onWrite(NimBLECharacteristic *chr, ble_gap_conn_desc *desc) {
    NimBLEAttValue value = chr->getValue();
    const uint8_t *data = value.data();
    size_t len = value.length();

    if (chr == configChr) {
        JsonDocument doc;
        DeserializationError err = deserializeJson(doc, data, len);
        if (err || !doc.is<JsonObject>()) {
            DEBUG("Invalid config JSON: %s\n", err.c_str());
            return;
        }
        conf->fromJson(doc.as<JsonObjectConst>());
        led->on(200);
    } else if (chr == controlChr) {
        handleControl(data, len);
    } else if (chr == otaCtrlChr) {
        handleOtaControl(data, len, desc->conn_handle);
    } else if (chr == otaDataChr) {
        handleOtaData(data, len, desc->conn_handle);
    }
}

void BleServer::handleControl(const uint8_t *data, size_t len) {
    if (len < 1) return;
    switch (data[0]) {
        case CMD_TIMER_START:
            timer->start();
            break;
        case CMD_TIMER_STOP:
            timer->stop();
            break;
        default:
            DEBUG("Unknown control command 0x%02x\n", data[0]);
            return;
    }
    sendStatus();
}

void BleServer::sendStatus() {
    uint8_t status[2] = {monitor->getBatteryVoltage(), (uint8_t)(timer->isRunning() ? 1 : 0)};
    statusChr->setValue(status, sizeof(status));
    statusChr->notify();
}

void BleServer::otaReply(uint8_t command, uint8_t status) {
    uint8_t reply[2] = {command, status};
    otaCtrlChr->setValue(reply, sizeof(reply));
    otaCtrlChr->notify();
}

void BleServer::otaReset() {
    if (Update.isRunning()) {
        Update.abort();
    }
    otaActive = false;
    otaConnHandle = BLE_HS_CONN_HANDLE_NONE;
    otaSize = 0;
    otaReceived = 0;
}

void BleServer::handleOtaControl(const uint8_t *data, size_t len, uint16_t connHandle) {
    if (len < 1) return;
    switch (data[0]) {
        case OTA_BEGIN: {
            if (len != 1 + 4 + 16) {
                otaReply(OTA_BEGIN, OTA_ERR_STATE);
                return;
            }
            if (otaActive && otaConnHandle != connHandle) {
                otaReply(OTA_BEGIN, OTA_ERR_STATE);  // another client is already updating
                return;
            }
            otaReset();
            if (timer->isRunning()) {
                timer->stop();
            }
            uint32_t size = getUint32(&data[1]);
            char md5[33];
            for (int i = 0; i < 16; i++) snprintf(&md5[i * 2], 3, "%02x", data[5 + i]);

            DEBUG("OTA begin, size %u md5 %s\n", size, md5);
            if (size == 0 || !Update.begin(size, U_FLASH)) {
                DEBUG("OTA begin failed: %s\n", Update.errorString());
                otaReply(OTA_BEGIN, OTA_ERR_BEGIN);
                return;
            }
            Update.setMD5(md5);
            otaActive = true;
            otaConnHandle = connHandle;
            otaSize = size;
            otaReceived = 0;
            led->blink(100);
            otaReply(OTA_BEGIN, OTA_OK);
            break;
        }
        case OTA_END: {
            if (!otaActive || otaConnHandle != connHandle) {
                otaReply(OTA_END, OTA_ERR_STATE);
                return;
            }
            if (otaReceived != otaSize) {
                otaReset();
                otaReply(OTA_END, OTA_ERR_SIZE);
                return;
            }
            // end() verifies the MD5 and that the image is valid for this chip
            if (!Update.end()) {
                DEBUG("OTA end failed: %s\n", Update.errorString());
                otaReset();
                otaReply(OTA_END, OTA_ERR_END);
                led->off();
                return;
            }
            DEBUG("OTA complete, rebooting\n");
            otaActive = false;
            otaReply(OTA_END, OTA_OK);
            buz->beep(500);
            rebootAtMs = millis() + BLE_OTA_REBOOT_DELAY_MS;
            if (rebootAtMs == 0) rebootAtMs = 1;
            break;
        }
        case OTA_ABORT:
            if (otaConnHandle == connHandle) {
                DEBUG("OTA aborted by client\n");
                otaReset();
                led->off();
            }
            otaReply(OTA_ABORT, OTA_OK);
            break;
        default:
            break;
    }
}

void BleServer::handleOtaData(const uint8_t *data, size_t len, uint16_t connHandle) {
    if (!otaActive || otaConnHandle != connHandle) {
        otaReply(OTA_ERROR, OTA_ERR_STATE);
        return;
    }
    if (otaReceived + len > otaSize) {
        otaReset();
        otaReply(OTA_ERROR, OTA_ERR_SIZE);
        return;
    }
    // Writes are acknowledged (write with response), so the client cannot outrun the flash.
    size_t written = Update.write((uint8_t *)data, len);
    if (written != len) {
        DEBUG("OTA write failed: %s\n", Update.errorString());
        otaReset();
        otaReply(OTA_ERROR, OTA_ERR_WRITE);
        return;
    }
    otaReceived += len;
}

void BleServer::handleBleUpdate(uint32_t currentTimeMs) {
    if (rebootAtMs != 0 && (int32_t)(currentTimeMs - rebootAtMs) >= 0) {
        ESP.restart();
    }

    if (timer->isLapAvailable()) {
        uint16_t index = timer->getLapIndex();
        uint32_t lapTime = timer->getLapTime();
        uint8_t payload[6];
        putUint16(payload, index);
        putUint32(&payload[2], lapTime);
        lapChr->setValue(payload, sizeof(payload));
        lapChr->notify();
        DEBUG("Lap %u sent: %u ms\n", index, lapTime);
    }

    // RSSI is only streamed while somebody listens (calibration screen)
    if (rssiChr->getSubscribedCount() > 0 && (currentTimeMs - rssiSentMs) > BLE_RSSI_SEND_INTERVAL_MS) {
        uint8_t rssi = timer->getRssi();
        rssiChr->setValue(&rssi, 1);
        rssiChr->notify();
        rssiSentMs = currentTimeMs;
    }

    if (statusChr->getSubscribedCount() > 0 && (currentTimeMs - statusSentMs) > BLE_STATUS_SEND_INTERVAL_MS) {
        sendStatus();
        statusSentMs = currentTimeMs;
    }
}
