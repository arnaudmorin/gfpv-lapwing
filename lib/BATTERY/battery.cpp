#include "battery.h"

#include <Arduino.h>

#include "debug.h"

void BatteryMonitor::init(uint8_t pin, uint8_t batScale, uint8_t batAdd, Buzzer *buzzer, Led *l) {
    buz = buzzer;
    led = l;
    vbatPin = pin;
    scale = batScale;
    add = batAdd;
    state = ALARM_OFF;
    memset(measurements, 0, sizeof(measurements));
    measurementIndex = 0;
    lastCheckTimeMs = millis();
    pinMode(vbatPin, INPUT);
#if defined(LILYGO_T_ENERGY_S3)
    // On the S3 the default 11dB attenuation full-scale is ~3.1V (not 3.3V) and the
    // ADC is nonlinear, so set the range explicitly and read calibrated
    // millivolts (same as LilyGo's T-Energy-S3 example: analogReadMilliVolts(pin) * 2).
    analogReadResolution(12);
    analogSetPinAttenuation(vbatPin, ADC_11db);
#endif

    for (int i = 0; i < AVERAGING_SIZE; i++) {
        getBatteryVoltage();  // kick averaging sum up to speed.
    }
}

static uint16_t averageSum = 0;

uint8_t BatteryMonitor::getBatteryVoltage() {
#if defined(LILYGO_T_ENERGY_S3)
    // Calibrated millivolts at the pin (0-~3100mV with 11dB atten); battery voltage
    // ranges from 4.2V to 3.0V, but the voltage is divided, so 2.1V - 1.5V at the pin.
    volatile uint16_t raw = analogReadMilliVolts(vbatPin);                          // mV at pin
    averageSum = averageSum - measurements[measurementIndex];                       // substract oldest val
    measurements[measurementIndex] = raw;                                           // replace old with new val
    averageSum += raw;                                                              // update averageSum
    measurementIndex = (measurementIndex + 1) % AVERAGING_SIZE;
    uint8_t scaled = (round(averageSum / AVERAGING_SIZE) * scale) / 100 + add;      // tenths of a volt, divider + voltage drop
#else
    // 0-3.3V maps to 0-4095, battery voltage ranges from 4.2V to 3.0V, but the voltage is divided, so 2.1V - 1.5V
    volatile uint16_t raw = analogRead(vbatPin);
    averageSum = averageSum - measurements[measurementIndex];  // substract oldest val
    measurements[measurementIndex] = raw;                      // replace old with new val
    averageSum += raw;                                         // update averageSum
    measurementIndex = (measurementIndex + 1) % AVERAGING_SIZE;
    uint8_t scaled = map(round(averageSum / AVERAGING_SIZE), 0, 4095, 0, 33 * scale) + add;  // 3.3v ref accuracy, divider + voltage drop
#endif
    DEBUG("Battery raw:%u, scaled:%u\n", raw, scaled);
    return scaled;
}

void BatteryMonitor::checkBatteryState(uint32_t currentTimeMs, uint8_t alarmThreshold) {
    switch (state) {
        case ALARM_OFF:
            if ((alarmThreshold > 0) && ((currentTimeMs - lastCheckTimeMs) > MONITOR_CHECK_TIME_MS)) {
                lastCheckTimeMs = currentTimeMs;
                if (getBatteryVoltage() <= alarmThreshold) {
                    state = ALARM_BEEPING;
                    buz->beep(MONITOR_BEEP_TIME_MS);
                    led->blink(MONITOR_BEEP_TIME_MS);
                }
            }
            break;
        case ALARM_BEEPING:
            if ((currentTimeMs - lastCheckTimeMs) > MONITOR_BEEP_TIME_MS) {
                lastCheckTimeMs = currentTimeMs;
                state = ALARM_IDLE;
            }
            break;
        case ALARM_IDLE:
            if ((currentTimeMs - lastCheckTimeMs) > MONITOR_BEEP_TIME_MS) {
                lastCheckTimeMs = currentTimeMs;
                if (getBatteryVoltage() <= alarmThreshold + 1) {  // add 0.1V of histeresis
                    state = ALARM_BEEPING;
                    buz->beep(MONITOR_BEEP_TIME_MS);
                } else {
                    led->off();
                    state = ALARM_OFF;
                }
            }
            break;
        default:
            break;
    }
}
