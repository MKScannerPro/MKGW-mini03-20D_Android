package com.moko.support.remotegw20d.entity;

import java.io.Serializable;

import no.nordicsemi.android.support.v18.scanner.ScanResult;

public class DeviceInfo implements Serializable {
    public String name;
    public int rssi;
    public String mac;
    public String scanRecord;
    public int deviceType;
    public ScanResult scanResult;

    @Override
    public String toString() {
        return "DeviceInfo{" +
                "name='" + name + '\'' +
                ", rssi=" + rssi +
                ", mac='" + mac + '\'' +
                ", scanRecord='" + scanRecord + '\'' +
                '}';
    }
}
