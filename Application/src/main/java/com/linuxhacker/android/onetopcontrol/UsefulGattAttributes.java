/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.linuxhacker.android.onetopcontrol;

import java.util.HashMap;

/**
 * This class includes a small subset of standard GATT attributes for demonstration purposes.
 */
public class UsefulGattAttributes {
    private static HashMap<String, String> attributes = new HashMap();
    public static String ONETOP_CONTROL_SERVICE = "be030457-0506-4287-9e2f-e12f7f99c3f1";
    public static String HEART_RATE_MEASUREMENT = "00002a37-0000-1000-8000-00805f9b34fb";
    public static String CLIENT_CHARACTERISTIC_CONFIG = "00002902-0000-1000-8000-00805f9b34fb";
    public static String DEVICE_NAME = "00002a00-0000-1000-8000-00805f9b34fb";
    public static String ONETOP_POWER_OUTPUT = "0dd11103-ad63-4862-9798-ff5d85d1d205";
    public static String ONETOP_TEMP_READOUT = "8f080b1c-7c3b-fbb9-584a-f0afd57028f0";
    public static String ONETOP_CURRENT_STATE = "0d42a2ed-d3d8-4db8-b46c-16454beaa117";
    public static String ONETOP_SMART_CONTROL = "29888f67-52e0-4507-8c50-fc4224657d33";
    public static String ONETOP_CURRENT_MODE = "129be657-e380-49ff-a3db-d0df896038ac";
    public static String ONETOP_TURNOFF_CONTROL = "9b5967c5-5f69-4f64-831e-d9d8aa213b56";
    public static String ONETOP_UNKNOWN_MODE = "7761b033-5395-47b6-95fb-af45c1f6cd3c"; // 00 in manual, 01 in smart.
    public static String ONETOP_TIMER_SECONDS_LEFT = "a5f358b5-6e40-4bd9-ad46-61979333acc1"; // 2 bytes bigendian
    public static String ONETOP_TIMER_START = "a9a826b9-fb04-4655-b7b6-428aec551df6";
    static {
        // Sample Services.
        attributes.put("0000180d-0000-1000-8000-00805f9b34fb", "Heart Rate Service");
        attributes.put("0000180a-0000-1000-8000-00805f9b34fb", "Device Information Service");
        attributes.put(ONETOP_CONTROL_SERVICE, "One Top Control Service");
        // Sample Characteristics.
        attributes.put(HEART_RATE_MEASUREMENT, "Heart Rate Measurement");
        attributes.put(DEVICE_NAME, "Device Name");
        attributes.put("00001800-0000-1000-8000-00805f9b34fb", "Device Information");
        attributes.put("be030457-0506-4287-9e2f-e12f7f99c3f1", "Cooking Control Service");
        attributes.put("00002a29-0000-1000-8000-00805f9b34fb", "Manufacturer Name String");
        attributes.put(ONETOP_POWER_OUTPUT, "Current Power Output");
        attributes.put(ONETOP_TEMP_READOUT, "Current Temperature reads");
        attributes.put(ONETOP_CURRENT_STATE, "OneTop Power State"); // 0 - off 1 - on/preheating 2 - doneheating 3 - timer started, 4 timerdone
        attributes.put(ONETOP_SMART_CONTROL, "One Top Smart Control");
        attributes.put(ONETOP_CURRENT_MODE, "One Top current operating mode"); // 00 - off, 01 - manual power, 02 - smart power
        attributes.put(ONETOP_TURNOFF_CONTROL, "One Top TurnOff Control"); // write 0xa0b0 to turn off
        attributes.put(ONETOP_TIMER_SECONDS_LEFT, "Seconds left on the timer");
        attributes.put(ONETOP_TIMER_START, "Timer start");
    }

    public static String lookup(String uuid, String defaultName) {
        String name = attributes.get(uuid);
        return name == null ? defaultName : name;
    }
}
