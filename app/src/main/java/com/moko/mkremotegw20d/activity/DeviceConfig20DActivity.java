package com.moko.mkremotegw20d.activity;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.github.lzyzsd.circleprogress.DonutProgress;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.moko.ble.lib.MokoConstants;
import com.moko.ble.lib.event.ConnectStatusEvent;
import com.moko.ble.lib.event.OrderTaskResponseEvent;
import com.moko.ble.lib.task.OrderTaskResponse;
import com.moko.mkremotegw20d.AppConstants;
import com.moko.mkremotegw20d.R;
import com.moko.mkremotegw20d.activity.set.AdvertiseIBeacon20DActivity;
import com.moko.mkremotegw20d.base.BaseActivity;
import com.moko.mkremotegw20d.databinding.ActivityDeviceConfig20dBinding;
import com.moko.mkremotegw20d.db.DBTools20D;
import com.moko.lib.scannerui.dialog.CustomDialog;
import com.moko.mkremotegw20d.entity.MQTTConfig;
import com.moko.mkremotegw20d.entity.MokoDevice;
import com.moko.mkremotegw20d.utils.SPUtiles;
import com.moko.lib.scannerui.utils.ToastUtils;
import com.moko.support.remotegw20d.MQTTConstants;
import com.moko.lib.mqtt.MQTTSupport;
import com.moko.support.remotegw20d.MokoSupport;
import com.moko.support.remotegw20d.OrderTaskAssembler;
import com.moko.lib.mqtt.entity.MsgNotify;
import com.moko.support.remotegw20d.entity.OrderCHAR;
import com.moko.support.remotegw20d.entity.ParamsKeyEnum;
import com.moko.lib.mqtt.event.MQTTMessageArrivedEvent;

import org.eclipse.paho.client.mqttv3.MqttException;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.lang.reflect.Type;

public class DeviceConfig20DActivity extends BaseActivity<ActivityDeviceConfig20dBinding> {
    private MQTTConfig mAppMqttConfig;
    private MQTTConfig mDeviceMqttConfig;
    private Handler mHandler;
    private int mSelectedDeviceType;
    private boolean mIsMQTTConfigFinished;
    private boolean mIsWIFIConfigFinished;
    private CustomDialog mqttConnDialog;
    private DonutProgress donutProgress;
    private boolean isSettingSuccess;
    private boolean isDeviceConnectSuccess;

    @Override
    protected void onCreate() {
        mSelectedDeviceType = getIntent().getIntExtra(AppConstants.EXTRA_KEY_SELECTED_DEVICE_TYPE, -1);
        String mqttConfigAppStr = SPUtiles.getStringValue(this, AppConstants.SP_KEY_MQTT_CONFIG_APP, "");
        mAppMqttConfig = new Gson().fromJson(mqttConfigAppStr, MQTTConfig.class);
        mHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    protected ActivityDeviceConfig20dBinding getViewBinding() {
        return ActivityDeviceConfig20dBinding.inflate(getLayoutInflater());
    }

    @Subscribe(threadMode = ThreadMode.POSTING, priority = 50)
    public void onConnectStatusEvent(ConnectStatusEvent event) {
        String action = event.getAction();
        EventBus.getDefault().cancelEventDelivery(event);
        if (isSettingSuccess) return;
        if (MokoConstants.ACTION_DISCONNECTED.equals(action)) {
            runOnUiThread(() -> {
                Intent intent = new Intent();
                setResult(RESULT_OK, intent);
                finish();
            });
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onOrderTaskResponseEvent(OrderTaskResponseEvent event) {
        final String action = event.getAction();
        if (MokoConstants.ACTION_ORDER_FINISH.equals(action)) {
            dismissLoadingProgressDialog();
        }
        if (MokoConstants.ACTION_ORDER_RESULT.equals(action)) {
            OrderTaskResponse response = event.getResponse();
            OrderCHAR orderCHAR = (OrderCHAR) response.orderCHAR;
            int responseType = response.responseType;
            byte[] value = response.responseValue;
            if (orderCHAR == OrderCHAR.CHAR_PARAMS) {
                if (value.length >= 4) {
                    int header = value[0] & 0xFF;// 0xED
                    int flag = value[1] & 0xFF;// read or write
                    int cmd = value[2] & 0xFF;
                    if (header == 0xED) {
                        ParamsKeyEnum configKeyEnum = ParamsKeyEnum.fromParamKey(cmd);
                        if (configKeyEnum == null) {
                            return;
                        }
                        int length = value[3] & 0xFF;
                        if (flag == 0x01) {
                            // write
                            int result = value[4] & 0xFF;
                            if (configKeyEnum == ParamsKeyEnum.KEY_EXIT_CONFIG_MODE) {
                                if (result != 1) {
                                    ToastUtils.showToast(this, "Setup failed！");
                                } else {
                                    isSettingSuccess = true;
                                    showConnMqttDialog();
                                    subscribeTopic();
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMQTTMessageArrivedEvent(MQTTMessageArrivedEvent event) {
        final String topic = event.getTopic();
        final String message = event.getMessage();
        if (TextUtils.isEmpty(topic) || isDeviceConnectSuccess) {
            return;
        }
        if (TextUtils.isEmpty(message)) return;
        int msg_id;
        try {
            JsonObject object = new Gson().fromJson(message, JsonObject.class);
            JsonElement element = object.get("msg_id");
            msg_id = element.getAsInt();
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }
        if (msg_id != MQTTConstants.NOTIFY_MSG_ID_NETWORKING_STATUS) return;
        Type type = new TypeToken<MsgNotify<Object>>() {
        }.getType();
        MsgNotify<Object> msgNotify = new Gson().fromJson(message, type);
        final String mac = msgNotify.device_info.mac;
        if (!mDeviceMqttConfig.staMac.equals(mac)) {
            return;
        }
        if (donutProgress == null) return;
        if (!isDeviceConnectSuccess) {
            isDeviceConnectSuccess = true;
            donutProgress.setProgress(100);
            donutProgress.setText(100 + "%");
            // 关闭进度条弹框，保存数据，跳转修改设备名称页面
            mBind.tvName.postDelayed(() -> {
                dismissConnMqttDialog();
                MokoDevice mokoDevice = DBTools20D.getInstance(DeviceConfig20DActivity.this).selectDeviceByMac(mDeviceMqttConfig.staMac);
                String mqttConfigStr = new Gson().toJson(mDeviceMqttConfig, MQTTConfig.class);
                if (mokoDevice == null) {
                    mokoDevice = new MokoDevice();
                    mokoDevice.name = mDeviceMqttConfig.deviceName;
                    mokoDevice.mac = mDeviceMqttConfig.staMac;
                    mokoDevice.mqttInfo = mqttConfigStr;
                    mokoDevice.topicSubscribe = mDeviceMqttConfig.topicSubscribe;
                    mokoDevice.topicPublish = mDeviceMqttConfig.topicPublish;
                    mokoDevice.lwtEnable = mDeviceMqttConfig.lwtEnable ? 1 : 0;
                    mokoDevice.lwtTopic = mDeviceMqttConfig.lwtTopic;
                    mokoDevice.deviceType = mSelectedDeviceType;
                    DBTools20D.getInstance(DeviceConfig20DActivity.this).insertDevice(mokoDevice);
                } else {
                    mokoDevice.name = mDeviceMqttConfig.deviceName;
                    mokoDevice.mac = mDeviceMqttConfig.staMac;
                    mokoDevice.mqttInfo = mqttConfigStr;
                    mokoDevice.topicSubscribe = mDeviceMqttConfig.topicSubscribe;
                    mokoDevice.topicPublish = mDeviceMqttConfig.topicPublish;
                    mokoDevice.lwtEnable = mDeviceMqttConfig.lwtEnable ? 1 : 0;
                    mokoDevice.lwtTopic = mDeviceMqttConfig.lwtTopic;
                    mokoDevice.deviceType = mSelectedDeviceType;
                    DBTools20D.getInstance(DeviceConfig20DActivity.this).updateDevice(mokoDevice);
                }
                Intent modifyIntent = new Intent(DeviceConfig20DActivity.this, ModifyName20DActivity.class);
                modifyIntent.putExtra(AppConstants.EXTRA_KEY_DEVICE, mokoDevice);
                startActivity(modifyIntent);
            }, 1000);
        }
    }

    public void onBack(View view) {
        if (isWindowLocked()) return;
        back();
    }

    @Override
    public void onBackPressed() {
        if (isWindowLocked()) return;
        back();
    }

    private void back() {
        MokoSupport.getInstance().disConnectBle();
    }

    public void onAdvertiseIBeacon(View view){
        if (isWindowLocked()) return;
        startActivity(new Intent(this, AdvertiseIBeacon20DActivity.class));
    }

    public void onWifiSettings(View view) {
        if (isWindowLocked()) return;
        Intent intent = new Intent(this, WifiSettings20DActivity.class);
        startWIFISettings.launch(intent);
    }

    public void onMqttSettings(View view) {
        if (isWindowLocked()) return;
        Intent intent = new Intent(this, MqttSettings20DActivity.class);
        startMQTTSettings.launch(intent);
    }

    public void onNtpSettings(View view) {
        if (isWindowLocked()) return;
        Intent intent = new Intent(this, NtpSettings20DActivity.class);
        startActivity(intent);
    }

    public void onScannerFilter(View view) {
        if (isWindowLocked()) return;
        Intent intent = new Intent(this, ScannerFilter20DActivity.class);
        startActivity(intent);
    }

    public void onDeviceInfo(View view) {
        if (isWindowLocked()) return;
        Intent intent = new Intent(this, DeviceInformation20DActivity.class);
        startActivity(intent);
    }

    public void onConnect(View view) {
        if (isWindowLocked()) return;
        if (!mIsWIFIConfigFinished || !mIsMQTTConfigFinished) {
            ToastUtils.showToast(this, "Please configure WIFI and MQTT settings first!");
            return;
        }
        showLoadingProgressDialog();
        MokoSupport.getInstance().sendOrder(OrderTaskAssembler.exitConfigMode());
    }

    private final ActivityResultLauncher<Intent> startWIFISettings = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() == RESULT_OK)
            mIsWIFIConfigFinished = true;
    });
    private final ActivityResultLauncher<Intent> startMQTTSettings = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() == RESULT_OK) {
            mIsMQTTConfigFinished = true;
            mDeviceMqttConfig = (MQTTConfig) result.getData().getSerializableExtra(AppConstants.EXTRA_KEY_MQTT_CONFIG_DEVICE);
        }
    });
    private int progress;

    private void showConnMqttDialog() {
        isDeviceConnectSuccess = false;
        View view = LayoutInflater.from(this).inflate(R.layout.layout_mqtt_conn_content, null);
        donutProgress = view.findViewById(R.id.dp_progress);
        mqttConnDialog = new CustomDialog.Builder(this)
                .setContentView(view)
                .create();
        mqttConnDialog.setCancelable(false);
        mqttConnDialog.show();
        new Thread(() -> {
            progress = 0;
            while (progress <= 100 && !isDeviceConnectSuccess) {
                runOnUiThread(() -> {
                    donutProgress.setProgress(progress);
                    donutProgress.setText(progress + "%");
                });
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                progress++;
            }
        }).start();
        mHandler.postDelayed(() -> {
            if (!isDeviceConnectSuccess) {
                isDeviceConnectSuccess = true;
                isSettingSuccess = false;
                dismissConnMqttDialog();
                ToastUtils.showToast(DeviceConfig20DActivity.this, getString(R.string.mqtt_connecting_timeout));
                finish();
            }
        }, 90 * 1000);
    }

    private void dismissConnMqttDialog() {
        if (mqttConnDialog != null && !isFinishing() && mqttConnDialog.isShowing()) {
            isDeviceConnectSuccess = true;
            isSettingSuccess = false;
            mqttConnDialog.dismiss();
            mHandler.removeMessages(0);
        }
    }

    private void subscribeTopic() {
        // 订阅
        try {
            if (TextUtils.isEmpty(mAppMqttConfig.topicSubscribe)) {
                MQTTSupport.getInstance().subscribe(mDeviceMqttConfig.topicPublish, mAppMqttConfig.qos);
            }
        } catch (MqttException e) {
            e.printStackTrace();
        }
        // 订阅遗愿主题
        try {
            if (mDeviceMqttConfig.lwtEnable
                    && !TextUtils.isEmpty(mDeviceMqttConfig.lwtTopic)
                    && !mDeviceMqttConfig.lwtTopic.equals(mDeviceMqttConfig.topicPublish)) {
                MQTTSupport.getInstance().subscribe(mDeviceMqttConfig.lwtTopic, mAppMqttConfig.qos);
            }
        } catch (MqttException e) {
            e.printStackTrace();
        }
    }
}
