package com.moko.support.remotegw20d.task;

import com.moko.ble.lib.task.OrderTask;
import com.moko.support.remotegw20d.entity.OrderCHAR;

public class GetHardwareRevisionTask extends OrderTask {

    public byte[] data;

    public GetHardwareRevisionTask() {
        super(OrderCHAR.CHAR_HARDWARE_REVISION, OrderTask.RESPONSE_TYPE_READ);
    }

    @Override
    public byte[] assemble() {
        return data;
    }
}
