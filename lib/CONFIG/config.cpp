#include "config.h"

#include <EEPROM.h>

#include "RX5808.h"
#include "debug.h"

void Config::init(void) {
    if (sizeof(laptimer_config_t) > EEPROM_RESERVED_SIZE) {
        DEBUG("Config size too big, adjust reserved EEPROM size\n");
        return;
    }

    EEPROM.begin(EEPROM_RESERVED_SIZE);  // Size of EEPROM
    load();                              // Override default settings from EEPROM

    checkTimeMs = millis();

    DEBUG("EEPROM Init Successful\n");
}

void Config::load(void) {
    modified = false;
    EEPROM.get(0, conf);

    uint32_t version = 0xFFFFFFFF;
    if ((conf.version & CONFIG_MAGIC_MASK) == CONFIG_MAGIC) {
        version = conf.version & ~CONFIG_MAGIC_MASK;
    }

    // If version is not current, reset to defaults
    if (version != CONFIG_VERSION) {
        setDefaults();
    }
}

void Config::write(void) {
    if (!modified) return;

    DEBUG("Writing to EEPROM\n");

    EEPROM.put(0, conf);
    EEPROM.commit();

    DEBUG("Writing to EEPROM done\n");

    modified = false;
}

size_t Config::toJson(char* buf, size_t len) {
    JsonDocument config;
    config["freq"] = conf.frequency;
    config["minLap"] = conf.minLap;
    config["alarm"] = conf.alarm;
    config["anType"] = conf.announcerType;
    config["anRate"] = conf.announcerRate;
    config["enterRssi"] = conf.enterRssi;
    config["exitRssi"] = conf.exitRssi;
    config["name"] = conf.pilotName;
    return serializeJson(config, buf, len);
}

// Only keys present in the source are applied, so clients may send partial updates.
template <typename T>
static void applyUint(JsonObjectConst source, const char* key, T& field, long minVal, long maxVal, bool& modified) {
    JsonVariantConst v = source[key];
    if (!v.is<long>()) return;
    long value = constrain(v.as<long>(), minVal, maxVal);
    if ((T)value != field) {
        field = (T)value;
        modified = true;
    }
}

void Config::fromJson(JsonObjectConst source) {
    JsonVariantConst freq = source["freq"];
    if (freq.is<long>() && (freq.as<long>() == POWER_DOWN_FREQ_MHZ || (freq.as<long>() >= 5000 && freq.as<long>() <= 6000))) {
        applyUint(source, "freq", conf.frequency, 0, 0xFFFF, modified);
    }
    applyUint(source, "minLap", conf.minLap, 0, 255, modified);
    applyUint(source, "alarm", conf.alarm, 0, 255, modified);
    applyUint(source, "anType", conf.announcerType, 0, 255, modified);
    applyUint(source, "anRate", conf.announcerRate, 0, 255, modified);
    applyUint(source, "enterRssi", conf.enterRssi, 0, 255, modified);
    applyUint(source, "exitRssi", conf.exitRssi, 0, 255, modified);
    JsonVariantConst name = source["name"];
    if (name.is<const char*>() && strcmp(name.as<const char*>(), conf.pilotName) != 0) {
        strlcpy(conf.pilotName, name.as<const char*>(), sizeof(conf.pilotName));
        modified = true;
    }
}

uint16_t Config::getFrequency() {
    return conf.frequency;
}

uint32_t Config::getMinLapMs() {
    return conf.minLap * 100;
}

uint8_t Config::getAlarmThreshold() {
    return conf.alarm;
}

uint8_t Config::getEnterRssi() {
    return conf.enterRssi;
}

uint8_t Config::getExitRssi() {
    return conf.exitRssi;
}

void Config::setDefaults(void) {
    DEBUG("Setting EEPROM defaults\n");
    // Reset everything to 0/false and then just set anything that zero is not appropriate
    memset(&conf, 0, sizeof(conf));
    conf.version = CONFIG_VERSION | CONFIG_MAGIC;
    conf.frequency = POWER_DOWN_FREQ_MHZ;  // RX5808 stays off until a channel is chosen
    conf.minLap = 100;
    conf.alarm = 36;
    conf.announcerType = 2;
    conf.announcerRate = 10;
    conf.enterRssi = 120;
    conf.exitRssi = 100;
    strlcpy(conf.pilotName, "", sizeof(conf.pilotName));
    modified = true;
    write();
}

void Config::handleEeprom(uint32_t currentTimeMs) {
    if (modified && ((currentTimeMs - checkTimeMs) > EEPROM_CHECK_TIME_MS)) {
        checkTimeMs = currentTimeMs;
        write();
    }
}
