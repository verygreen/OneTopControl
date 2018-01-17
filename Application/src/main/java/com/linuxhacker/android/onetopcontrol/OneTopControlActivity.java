/*
 * Copyright 2018 green@linuxhacker.ru
 */
package com.linuxhacker.android.onetopcontrol;

import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.app.Activity;
import android.os.CountDownTimer;
import android.os.IBinder;
import android.os.SystemClock;
import android.support.v4.app.NotificationCompat;
import android.text.InputType;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;
import android.widget.ToggleButton;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;

public class OneTopControlActivity extends Activity {
    private final static String TAG = OneTopControlActivity.class.getSimpleName();

    public static final String EXTRAS_DEVICE_NAME = "DEVICE_NAME";
    public static final String EXTRAS_DEVICE_ADDRESS = "DEVICE_ADDRESS";

    private ImageView mConnectionState;
    private ImageView mPowerIcon;
    private TextView mBaseTempField;
    private TextView mContentsTempField;
    private TextView mPowerOutputValue;
    private SeekBar mPowerControlSeekBar;
    private ToggleButton mSwitchTempUnit1;
    private ToggleButton mSwitchTempUnit2;
    private Button mSetBaseTempButton;
    private Button mSetContentsTempButton;
    private TextView mTargetTempLabel;
    private TextView mTargetTempValue;
    private TextView mStatusLine;
    private TextView mTimerOutput;
    private Button mSousVideButton;
    private String mDeviceName;
    private String mDeviceAddress;
    private BluetoothLeService mBluetoothLeService;
    private AlertDialog mTurnOnDialog = null;
    private AlertDialog mAddFoodDialog = null;

    private boolean mConnected = false;
    private int mCurrentState = 0;
    private int mCurrentMode = 0;
    private float mActiveSetTemp = 0;
    private int mActiveSetTempType = 0; // 0 - off, 2 - base, 1 - probe
    private long mSecondsLeft;
    private boolean mTimerPaused = true; // Only forin-app timer
    private boolean mIsVisible;
    private boolean mListenerStopped = true;
    private boolean mSousVideEnabled = false;
    private boolean mUIEnabled;
    private BluetoothGattCharacteristic mOnetopPowerStateCharacteristic;
    private BluetoothGattCharacteristic mOnetopPowerOutputCharacteristic;
    private BluetoothGattCharacteristic mOnetopTempCharacteristic;
    private BluetoothGattCharacteristic mOnetopModeCharacteristic;
    private BluetoothGattCharacteristic mOnetopSmartArrayCharacteristic;
    private BluetoothGattCharacteristic mOnetopTurnOffCharacteristic;
    private BluetoothGattCharacteristic mOnetopTimerStartCharacteristic;
    private BluetoothGattCharacteristic mOnetopTimerLeftCharacteristic;
    private Queue<String> mDebugMessages = new LinkedList<>();
    private CountDownTimer mTimer = null;

    private long mTimerUpdatedAt;

    private AlarmManager alarmMgr;
    private PendingIntent alarmIntent;

    // Notification stuff
    private int mNotificationId = 1;
    private static final String NOTIFICATION_CHANNEL_ID = "onetop_control_notification_channel";
    NotificationManager mNotificationManager;
    NotificationChannel mChannel;

    // Adjustable settings
    private boolean mTempCelsiusRequested = false;

    private final String LIST_NAME = "NAME";
    private final String LIST_UUID = "UUID";

    // Code to manage Service lifecycle.
    private final ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            mBluetoothLeService = ((BluetoothLeService.LocalBinder) service).getService();
            if (!mBluetoothLeService.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth");
                finish();
            }
            // Automatically connects to the device upon successful start-up initialization.
            mBluetoothLeService.connect(mDeviceAddress);
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mBluetoothLeService = null;
        }
    };

    // Handles various events fired by the Service.
    // ACTION_GATT_CONNECTED: connected to a GATT server.
    // ACTION_GATT_DISCONNECTED: disconnected from a GATT server.
    // ACTION_GATT_SERVICES_DISCOVERED: discovered GATT services.
    // ACTION_DATA_AVAILABLE: received data from the device.  This can be a result of read
    //                        or notification operations.
    private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
                mConnected = true;
                updateConnectionState(android.R.drawable.presence_online);
                invalidateOptionsMenu();
                // Not enabling UI here, need to wait for the discovery to come through
                mStatusLine.setText(R.string.status_reconnected);
            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                mConnected = false;
                updateConnectionState(android.R.drawable.presence_offline);
                invalidateOptionsMenu();
                mStatusLine.setText(R.string.status_disconnected);
                disableUI();

                // If we are invisible, don't reconnect until triggered by onStart()
                if (mIsVisible) {
                    mBluetoothLeService.connect(mDeviceAddress); // Reconnect right back.
                }

                // No connection == no point in hogging cpu, right?
                mBluetoothLeService.dontSleep(false);
            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                // Register our listeners for the services.
                registerOneTopStateListeners(mBluetoothLeService.getService(UUID.fromString(UsefulGattAttributes.ONETOP_CONTROL_SERVICE)));
            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
                displayData(intent.getStringExtra(BluetoothLeService.EXTRA_CHAR_UUID),
                            intent.getByteArrayExtra(BluetoothLeService.EXTRA_DATA_RAW));
            }
        }
    };

    private void disableUI()
    {
        mPowerControlSeekBar.setEnabled(false);
        mSousVideButton.setEnabled(false);
        mSetBaseTempButton.setEnabled(false);
        mSetContentsTempButton.setEnabled(false);
        mUIEnabled = false;
    }

    private void enableUI()
    {
        mPowerControlSeekBar.setEnabled(true);
        mSousVideButton.setEnabled(true);
        mSetBaseTempButton.setEnabled(true);
        mSetContentsTempButton.setEnabled(true);
        mUIEnabled = true;
    }

    private int toUnsigned(byte val) {
        int retval = val;

        retval = 0x00ff & retval;

        return retval;
    }

    private void displayData(String uuid, byte[] data) {
        if (data == null) {
            return;
        }

        //final StringBuilder stringBuilder = new StringBuilder(data.length);
        //for(byte byteChar : data)
        //    stringBuilder.append(String.format("%02X ", byteChar));
        //String msg = "Received data update from " + uuid + " data[" + data.length + "]: " + data[0]; //stringBuilder.toString();

        //Log.d(TAG, msg);

        // Check our attributes for updates
        if (UsefulGattAttributes.ONETOP_POWER_OUTPUT.equals(uuid)) {
            updatePower(data[0], false);
        } else if (UsefulGattAttributes.ONETOP_CURRENT_STATE.equals(uuid)) {
            updateCurrentState(data[0]);
        } else if (UsefulGattAttributes.ONETOP_TEMP_READOUT.equals(uuid)) {
            updateTemp(toUnsigned(data[0]) * 256 + toUnsigned(data[1]),
                    toUnsigned(data[2]) * 256 + toUnsigned(data[3]));
        } else if (UsefulGattAttributes.ONETOP_CURRENT_MODE.equals(uuid)) {
            updateCurrentMode(data[0]);
        } else if (UsefulGattAttributes.ONETOP_SMART_CONTROL.equals(uuid)) {
            updateSmartMode(data);
        } else if (UsefulGattAttributes.ONETOP_TIMER_SECONDS_LEFT.equals(uuid)) {
            if (mCurrentState > 2) {// Only if cooking already
                mSousVideEnabled = true; // In case we lost this bit.
                updateTimer(toUnsigned(data[0]) * 256 + toUnsigned(data[1]));
            }
        }
    }

    private class PowerControlSeekbarListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress,
                                      boolean fromUser) {
            if (fromUser)
                updatePower(progress, fromUser);
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {}

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {}
    }

    private void updateCurrentMode(int mode) {
        mCurrentMode = mode;

        if (mode == 2) {
            mPowerControlSeekBar.setBackgroundColor(Color.GRAY);
            mPowerControlSeekBar.setEnabled(false);
        } else if (mode == 1) {
            if (mCurrentState > 0)
                mPowerControlSeekBar.setBackgroundColor(Color.TRANSPARENT);

            mPowerControlSeekBar.setEnabled(true);
        } else {
            // If mode == 0 - we are likely off so don't muck with the colors.
            mPowerControlSeekBar.setEnabled(true);
        }

        // On mode change reread the whole set of attributes
        mBluetoothLeService.readCharacteristic(mOnetopSmartArrayCharacteristic);
    }

    private void updateCurrentState(int newState) {
        Integer powerIconId = android.R.drawable.star_off;
        int oldState = mCurrentState;
        Uri alert = null;

        mCurrentState = newState;

        // Were we waiting for the OneTop to turn on? Dismiss the dialog
        if (mTurnOnDialog != null && mCurrentState > 0) {
            mTurnOnDialog.dismiss();
            mTurnOnDialog = null;
        }

        if (mCurrentState > 0) {
            powerIconId = android.R.drawable.star_on; // XXX: Update to the proper one like in updatePower

            if (mCurrentMode != 2)
                mPowerControlSeekBar.setBackgroundColor(Color.TRANSPARENT);

            // And now keep the screen on while the power is on
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            // When power is off, no temperature updates and readout is 0 too
            updateTemp(0, 0);
            mPowerControlSeekBar.setBackgroundColor(Color.GRAY);

            // When the One Top is off we allow the app to sleep.
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        Integer status_id;
        switch (newState) {
            case 0:
                status_id = R.string.status_poweroff;

                if (mTimer != null) {
                    mTimer.cancel();
                    mTimer = null;
                }
                mSecondsLeft = 0;
                drawTimer();
                mSousVideEnabled=false;
                mSousVideButton.setText(R.string.sousvide_button_text);
                break;
            case 1:
                status_id = R.string.status_heating;

                // This is kind of important - need to keep phone awake until set temp is reached
                // or we might miss it and there is no audio signal from OneTop for this event
                mBluetoothLeService.dontSleep(true);
                break;
            case 2:
                status_id = R.string.status_done_heating;
                if (oldState == 1) {
                    alert = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);

                    if (mSousVideEnabled && mAddFoodDialog == null)
                        displayAddFoodDialog();
                }

                // Also allow the phone to sleep now
                mBluetoothLeService.dontSleep(false);
                break;
            case 3:
                status_id = R.string.status_cooking_timer;
                if (oldState == 2)
                    alert = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);

                if (mSecondsLeft == 0)
                    mBluetoothLeService.readCharacteristic(mOnetopTimerLeftCharacteristic);
                break;
            case 4:
                status_id = R.string.status_done_cooking;
                if (oldState == 3)
                    alert = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);

                if (mSecondsLeft == 0)
                    mBluetoothLeService.readCharacteristic(mOnetopTimerLeftCharacteristic);

                break;
            default:
                status_id = R.string.status_unknown;
        }

        mStatusLine.setText(status_id);
        mPowerIcon.setImageResource(powerIconId);
        Log.d(TAG,"Updated power state to " + newState);

        if (alert != null) {
            if (mIsVisible) {
                Ringtone r = RingtoneManager.getRingtone(getApplicationContext(), alert);
                r.play();
            } else {

                Log.d(TAG, "Preparing notification");
                // If we are not visible, do the notification
                postNotification(status_id, false);
            }
        }

        // Since we get no more auto updates in off state or stale info in on state, do one round of manual updates
        if (mOnetopPowerOutputCharacteristic != null)
            mBluetoothLeService.readCharacteristic(mOnetopPowerOutputCharacteristic);
        if (mOnetopSmartArrayCharacteristic != null)
            mBluetoothLeService.readCharacteristic(mOnetopSmartArrayCharacteristic);
    }

    private void postNotification(int message_id, boolean alarm) {
        NotificationCompat.Builder mBuilder;

        Intent resultIntent = new Intent(this, OneTopControlActivity.class);
        resultIntent.putExtra(OneTopControlActivity.EXTRAS_DEVICE_NAME, mDeviceName);
        resultIntent.putExtra(OneTopControlActivity.EXTRAS_DEVICE_ADDRESS, mDeviceAddress);
        resultIntent.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);

        PendingIntent resultPendingIntent =
                PendingIntent.getActivity(
                        this,
                        0,
                        resultIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT
                );

        if (mNotificationManager == null)
            mNotificationManager =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            NotificationChannel notificationChannel = mNotificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_ID);

            if (notificationChannel == null) {
                notificationChannel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, "OneTop Control Notifications", NotificationManager.IMPORTANCE_DEFAULT);

                // Configure the notification channel.
                notificationChannel.setDescription("OneTop Control notifications");
                notificationChannel.enableLights(true);
                notificationChannel.setLightColor(Color.BLUE);
                notificationChannel.enableVibration(true);

                mNotificationManager.createNotificationChannel(notificationChannel);
            }

            mBuilder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID);
            mBuilder.setContentTitle(getResources().getString(message_id))  // required
                    .setSmallIcon(R.drawable.ic_notification) // required
                    .setContentText(this.getString(R.string.app_name))  // required
                    .setDefaults(Notification.DEFAULT_ALL)
                    .setAutoCancel(true)
                    .setContentIntent(resultPendingIntent)
                    .setTicker(getResources().getString(message_id));

        } else {
            mBuilder = new NotificationCompat.Builder(this);
            mBuilder.setContentTitle(getResources().getString(message_id))                           // required
                    .setSmallIcon(R.drawable.ic_notification) // required
                    .setContentText(this.getString(R.string.app_name))  // required
                    .setDefaults(Notification.DEFAULT_ALL)
                    .setAutoCancel(true)
                    .setContentIntent(resultPendingIntent)
                    .setTicker(getResources().getString(message_id));
        }

        try {
            mNotificationManager.notify(mNotificationId, mBuilder.build());
        } catch (Exception e) {
            // No notification for us.
            Log.d(TAG, "Failed to notify" + e);
        }
    }

    private void updatePower(int newPower, boolean fromUser) {
        // Do some power updates
        String powertext;
        Integer powerIconId = android.R.drawable.star_off;

        switch (newPower) {
            case 1:
            case 2:
            case 3:
            case 4:
                powertext = Integer.toString(newPower * 100);
                powerIconId = android.R.drawable.star_on;
                break;
            case 5:
            case 6:
            case 7:
            case 8:
            case 9:
                powertext = Integer.toString(newPower * 200 - 400);
                powerIconId = android.R.drawable.star_big_on;
                break;
            case 10:
                powertext = "1500";
                powerIconId = android.R.drawable.ic_notification_overlay;
                break;
            default:
                powertext = "0";
        }
        mPowerOutputValue.setText(powertext);
        if (mCurrentState > 0) // Only update the icon if the power is applied.
            mPowerIcon.setImageResource(powerIconId);

        if (!fromUser) {
            mPowerControlSeekBar.setProgress(newPower);
        } else {
            mOnetopPowerOutputCharacteristic.setValue(newPower, mOnetopPowerOutputCharacteristic.FORMAT_UINT8, 0);
            mBluetoothLeService.writeCharacteristic(mOnetopPowerOutputCharacteristic);
        }

    }

    // 0 in either one means no data.
    // Additionally there's a strange value when the contents probe is not plugged in: 3200 = 0°C
    private void updateTemp(Integer BaseTempRaw, Integer ContentsTempRaw) {
        if (BaseTempRaw == 0 && ContentsTempRaw == 0) {
            mBaseTempField.setText(R.string.NA_text);
            mContentsTempField.setText(R.string.NA_text);
            mBaseTempField.setBackgroundColor(Color.TRANSPARENT);
            mContentsTempField.setBackgroundColor(Color.TRANSPARENT);
            return;
        }
        Float BaseTemp = BaseTempRaw.floatValue()/ 100; // Fahrenheit
        Float ContentsTemp = ContentsTempRaw.floatValue() / 100; // Fahrenheit

        if (mTempCelsiusRequested) {
            BaseTemp = (BaseTemp - 32) * 5 / 9;
            ContentsTemp = (ContentsTemp - 32) * 5 / 9;
        }

        mBaseTempField.setText(String.format("%.1f", BaseTemp));
        mContentsTempField.setText(String.format("%.1f", ContentsTemp));

        // Now if we have smart mode let's display how far are we from the target.
        if (mCurrentMode == 2) {
            if (mActiveSetTempType == 1) {
                if (ContentsTemp - mActiveSetTemp > 1)
                    mContentsTempField.setBackgroundColor(Color.RED);
                else if (mActiveSetTemp - ContentsTemp > 1)
                    mContentsTempField.setBackgroundColor(Color.CYAN);
                else
                    mContentsTempField.setBackgroundColor(Color.GREEN);
            } if (mActiveSetTempType == 2) {
                if (BaseTemp - mActiveSetTemp > 1)
                    mBaseTempField.setBackgroundColor(Color.RED);
                else if (mActiveSetTemp - BaseTemp > 1)
                    mBaseTempField.setBackgroundColor(Color.CYAN);
                else
                    mBaseTempField.setBackgroundColor(Color.GREEN);
            }
        } else {
            mBaseTempField.setBackgroundColor(Color.TRANSPARENT);
            mContentsTempField.setBackgroundColor(Color.TRANSPARENT);
        }

        if (ContentsTempRaw == 3200) // Gotta be a better way to indicate the sensor is not plugged.
            mContentsTempField.setBackgroundColor(Color.GRAY);

    }

    private void updateSmartMode(byte[] data) {

        if (data.length > 7 && data[7] != 0) {
            mActiveSetTemp = (toUnsigned(data[5]) * 256 + toUnsigned(data[6])) / 100;
            mActiveSetTempType = data[7] - 0x20;

            if (mActiveSetTempType == 2) {
                mSetBaseTempButton.setText(android.R.string.no);
                mSetContentsTempButton.setText(R.string.Set_Temp_Button_Text);
                mTargetTempLabel.setVisibility(View.VISIBLE);
                mTargetTempValue.setVisibility(View.VISIBLE);
            } else if (mActiveSetTempType == 1) {
                mSetBaseTempButton.setText(R.string.Set_Temp_Button_Text);
                mSetContentsTempButton.setText(android.R.string.no);
                mTargetTempLabel.setVisibility(View.VISIBLE);
                mTargetTempValue.setVisibility(View.VISIBLE);
            }
            if (mActiveSetTemp > 0) {
                float temp = mActiveSetTemp;
                if (mTempCelsiusRequested)
                    temp = (mActiveSetTemp - 32) * 5 / 9;
                mTargetTempValue.setText(String.format("%.1f", temp));
            }

            int Timer1 = toUnsigned(data[1]) * 256 + toUnsigned(data[2]);
            int Timer2 = toUnsigned(data[3]) * 256 + toUnsigned(data[4]);

            if (Timer1 > 0 || Timer2 > 0) {
                mSousVideEnabled = true;
                mSousVideButton.setText(R.string.sousvide_button_cancel_text);
                if (mSecondsLeft == 0 && mCurrentState < 3)
                    mSecondsLeft = Timer1;
                // For 3 and 4 modes we'll request ~1 minute readout from mode update
            } else {
                mSousVideEnabled = false;
                mSousVideButton.setText(R.string.sousvide_button_text);
                mSecondsLeft = 0;
            }
            drawTimer();
        } else {
            mActiveSetTempType = 0;
            mActiveSetTemp = 0;
            mSetBaseTempButton.setText(R.string.Set_Temp_Button_Text);
            mSetContentsTempButton.setText(R.string.Set_Temp_Button_Text);
            mTargetTempLabel.setVisibility(View.INVISIBLE);
            mTargetTempValue.setVisibility(View.INVISIBLE);
        }
    }

    private void updateTimer(int seconds)
    {
        mSecondsLeft = seconds;

        mTimerUpdatedAt = System.currentTimeMillis();

        Log.d(TAG, "Got Timer update request: " + seconds + " SousVide active: " + mSousVideEnabled);

        if (mTimer != null) {
            mTimer.cancel();
        }
        if (mSecondsLeft > 0) {
            mTimer = new CountDownTimer(seconds * 1000, 990) {

                public void onTick(long millisUntilFinished) {
                    mSecondsLeft = millisUntilFinished / 1000;
                    drawTimer();
                }

                public void onFinish() {
                    mTimerOutput.setText(R.string.empty_timer);
                    mTimer = null;

                    // Just regular alarm? Issue a notification
                    if (!mSousVideEnabled)
                        postNotification(R.string.Timer_Is_Up, true);
                    cancelAlarm();
                }
            }.start();
            mTimerPaused = false;

            // In SousVide mode OneTop will sound the alarm.
            if (!mSousVideEnabled)
                setAlarm(mSecondsLeft);
        } else {
            mTimerPaused = true;
            cancelAlarm();
        }

        drawTimer();
    }

    private void drawTimer()
    {
        if (mSecondsLeft == 0) {
            mTimerOutput.setText(R.string.empty_timer);
            return;
        }
        long hours = mSecondsLeft / 3600;
        long minutes = mSecondsLeft % 3600 / 60;
        long seconds = mSecondsLeft % 60;

        String timer = "";

        if (hours > 0)
            timer = String.format("%02d", hours) + ":";

        if (minutes > 0)
            timer += String.format("%02d", minutes);

        timer += ":" + String.format("%02d", seconds);
        mTimerOutput.setText(timer);
    }

    private void setAlarm(long seconds)
    {
        alarmMgr = (AlarmManager)getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(this, OneTopControlActivity.class);

        alarmIntent = PendingIntent.getBroadcast(this, 0, intent, 0);
        alarmMgr.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + (seconds - 5) * 1000, alarmIntent);
    }

    private void cancelAlarm() {
        if (alarmMgr != null) {
            alarmMgr.cancel(alarmIntent);
            alarmMgr = null;
        }
    }

    public void onToggleTempClicked(View view) {
        ToggleButton otherSwitch;
        boolean state = ((ToggleButton) view).isChecked();

        // Now switch the other switch too
        if (view == mSwitchTempUnit1)
            otherSwitch = mSwitchTempUnit2;
        else
            otherSwitch = mSwitchTempUnit1;

        mTempCelsiusRequested = state;
        otherSwitch.setChecked(state);

        // Not updating the feed display since we are getting a constant stream of updates anyway.

        // Save the settings
        SharedPreferences sharedPref = getPreferences(Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPref.edit();
        editor.putBoolean(getString(R.string.temp_celsius_requested), mTempCelsiusRequested);
        editor.apply();
    }

    private void turnOffOneTop() {
        byte[] data = new byte[] {(byte) 0xa0, (byte) 0xb0};

        if (!mUIEnabled) // Cannot talk to anything when disconnected
            return;

        mOnetopTurnOffCharacteristic.setValue(data);
        mBluetoothLeService.writeCharacteristic(mOnetopTurnOffCharacteristic);

        mBluetoothLeService.dontSleep(false);
    }
    public void onTogglePowerOff(View view) {
        if (mCurrentState > 0)
            turnOffOneTop();

        if (!mConnected)
             mBluetoothLeService.connect(mDeviceAddress); // Reconnect right back.
    }

    public void onToggleConnect(View view) {
        if (!mConnected)
            mBluetoothLeService.connect(mDeviceAddress); // Reconnect right back.
    }

    private void displayTurnOnDeviceDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.turn_on_device_dialog_title);

        // Set up the input
        TextView message = new TextView(this);
        message.setText(R.string.turn_on_device_dialog_message);
        builder.setView(message);

        // No OK button - wait for the device to be turned on
        builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                turnOffOneTop();
                dialog.cancel();

                mTurnOnDialog = null;

                mSousVideEnabled = false;
                mSecondsLeft = 0;
                drawTimer();
            }
        });

        mTurnOnDialog = builder.show();
    }

    private void displayAddFoodDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.add_food_dialog_title);

        // Set up the input
        final TextView message = new TextView(this);
        message.setText(R.string.add_food_dialog_message);
        builder.setView(message);

        builder.setPositiveButton("Set", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                byte[] byte_on = new byte[] { (byte) 1};


                dialog.dismiss();
                mAddFoodDialog = null;

                if (!mUIEnabled) // Disconnected meanwhile? bail out
                    return;

                mOnetopTimerStartCharacteristic.setValue(byte_on);
                mBluetoothLeService.writeCharacteristic(mOnetopTimerStartCharacteristic);
            }
        });

        // No cancel button

        mAddFoodDialog = builder.show();
    }

    public void onSetTempClicked(View view) {
        final int TempType = (view == mSetContentsTempButton)?1:2;

        // Are we already in set temp mode? Then this is a cancel request
        if (mActiveSetTempType != 0) {
            turnOffOneTop();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.set_temp_dialog_title);

        // Set up the input
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);

        builder.setView(input);

        // Set up the buttons
        builder.setPositiveButton("Set", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                float Temp = 0;

                if (!mUIEnabled) {
                    // Disconnected meanwhile? bail out
                    dialog.dismiss();
                    return;
                }

                try {
                    Temp = Integer.parseInt(input.getText().toString());
                } catch (Exception e) {
                    dialog.dismiss();
                }

                if (Temp >= 450)
                    Temp = 450;

                if (Temp <= 33) {
                    dialog.dismiss();
                    return;
                }

                if (mTempCelsiusRequested)
                    Temp = Temp * 9 / 5 + 32;

                Temp *= 100;

                byte[] OneTopcontrolArray = new byte[40];

                Arrays.fill(OneTopcontrolArray, (byte)0);
                OneTopcontrolArray[0] = 10; // Power?
                OneTopcontrolArray[7] = (byte) (0x20 + TempType);
                OneTopcontrolArray[6] = (byte)((int)Temp & 0xff);
                OneTopcontrolArray[5] = (byte)(((int)Temp & 0xff00) >> 8);

                mOnetopSmartArrayCharacteristic.setValue(OneTopcontrolArray);
                mBluetoothLeService.writeCharacteristic(mOnetopSmartArrayCharacteristic);

                dialog.dismiss();

                displayTurnOnDeviceDialog();

            }
        });
        builder.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
            }
        });

        builder.show();
    }

    public void onTimerClick(View view) {
        if (view == mSousVideButton && mSousVideEnabled) {
            turnOffOneTop();
            return;
        }

        if (view == mTimerOutput && !mSousVideEnabled && mSecondsLeft > 0) {
            if (mTimerPaused) {
                updateTimer((int) mSecondsLeft);
            } else {
                mTimer.cancel();
                mTimerPaused = true;
                cancelAlarm();
            }
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = getLayoutInflater();
        View v = inflater.inflate(R.layout.sousvide_dialog, null);
        final TimePicker timepicker_input = v.findViewById(R.id.TimePicker);
        timepicker_input.setIs24HourView(true);
        timepicker_input.setCurrentHour(1);
        timepicker_input.setCurrentMinute(0);
        final EditText temp_input = v.findViewById(R.id.SousTemperature);
        if (view == mTimerOutput) {
            // If we want just the timer, rename the dialog as such
            builder.setTitle("Set Timer");
            builder.setMessage("");
            temp_input.setEnabled(false);
            temp_input.setVisibility(View.INVISIBLE);
        } else {
            builder.setTitle("SousVide mode");
            builder.setMessage(R.string.set_temp_dialog_title);
        }
        builder.setView(v);
        builder.setPositiveButton("Start", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                float Temp;

                try {
                    Temp = Integer.parseInt(temp_input.getText().toString());
                } catch (Exception e) {
                    Temp = 0;
                }

                if (Temp >= 450)
                    Temp = 450;

                if (Temp <= 33) {
                    Temp = 0;
                }

                // Until I figure out real timers
                //if ( Temp == 0) {
                //    dialog.dismiss();
                //    return;
                //}

                int Hour = timepicker_input.getCurrentHour();
                int Minute = timepicker_input.getCurrentMinute();
                int Seconds = Hour*3600 + Minute*60;

                mSecondsLeft = Seconds;
                drawTimer();

                dialog.dismiss();

                // Only if Temperature was set
                if ( Temp > 0) {
                    if (!mUIEnabled) // Disconnected meanwhile? bail out
                        return;

                    mSousVideEnabled = true;

                    if (mTempCelsiusRequested)
                        Temp = Temp * 9 / 5 + 32;

                    Temp *= 100;

                    byte[] OneTopcontrolArray = new byte[40];

                    Arrays.fill(OneTopcontrolArray, (byte) 0);
                    OneTopcontrolArray[0] = 10; // Power?
                    OneTopcontrolArray[7] = (byte) (0x21); // Always probe
                    OneTopcontrolArray[6] = (byte) ((int) Temp & 0xff);
                    OneTopcontrolArray[5] = (byte) (((int) Temp & 0xff00) >> 8);
                    OneTopcontrolArray[1] = (byte) ((Seconds & 0xff00) >> 8);
                    OneTopcontrolArray[2] = (byte) (Seconds & 0xff);
                    OneTopcontrolArray[3] = (byte) 7; // 30 minutes - postheating mode
                    OneTopcontrolArray[4] = (byte) 8;

                    mOnetopSmartArrayCharacteristic.setValue(OneTopcontrolArray);
                    mBluetoothLeService.writeCharacteristic(mOnetopSmartArrayCharacteristic);

                    displayTurnOnDeviceDialog();
                } else {
                    updateTimer(Seconds);
                }
            }
        });
        builder.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
            }
        });
        builder.show();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_one_top_control);

        final Intent intent = getIntent();
        mDeviceName = intent.getStringExtra(EXTRAS_DEVICE_NAME);
        mDeviceAddress = intent.getStringExtra(EXTRAS_DEVICE_ADDRESS);

        // Sets up UI references.
        mConnectionState = findViewById(R.id.connection_state);
        mPowerIcon = findViewById(R.id.PowerIcon);
        mBaseTempField = findViewById(R.id.base_temp_out_text);
        mContentsTempField = findViewById(R.id.contents_temp_out_text);
        mPowerOutputValue = findViewById(R.id.power_output_value);
        mSwitchTempUnit1 = findViewById(R.id.switch_degrees1);
        mSwitchTempUnit2 = findViewById(R.id.switch_degrees2);
        mPowerControlSeekBar = findViewById(R.id.power_control_bar);
        mPowerControlSeekBar.setOnSeekBarChangeListener(new PowerControlSeekbarListener());
        mSetBaseTempButton = findViewById(R.id.set_base_temp_button);
        mSetContentsTempButton = findViewById(R.id.set_contents_temp_button);
        mTargetTempLabel = findViewById(R.id.target_temp_label);
        mTargetTempValue = findViewById(R.id.target_temp);
        mStatusLine = findViewById(R.id.status_line_text);
        mTimerOutput = findViewById(R.id.timer_text);
        mTimerOutput.setText(R.string.empty_timer);
        mTimerOutput.setOnLongClickListener(new View.OnLongClickListener() {
                                                @Override
                                                public boolean onLongClick(View v) {
                                                    if (!mSousVideEnabled && mTimerPaused && mSecondsLeft > 0)
                                                        updateTimer(0);

                                                    return true;
                                                }
                                            });
        mSousVideButton = findViewById(R.id.sousvide_button);

        getActionBar().setTitle(mDeviceName);
        getActionBar().setDisplayHomeAsUpEnabled(true);
        Intent gattServiceIntent = new Intent(this, BluetoothLeService.class);
        bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);

        // We'll try listening all the time unless the power is off.
        registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
        mListenerStopped = false;

        // Get and set the temperature preferences
        SharedPreferences sharedPref = getPreferences(Context.MODE_PRIVATE);
        mTempCelsiusRequested = sharedPref.getBoolean(getString(R.string.temp_celsius_requested), false);
        mSwitchTempUnit2.setChecked(mTempCelsiusRequested);
        mSwitchTempUnit1.setChecked(mTempCelsiusRequested);

        disableUI();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (mListenerStopped) {
            registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
            if (mBluetoothLeService != null) {
                final boolean result = mBluetoothLeService.connect(mDeviceAddress);
                Log.d(TAG, "Connect request result=" + result);
            }
            mListenerStopped = false;

            Log.d(TAG, "onResume and no receiver, reregister and reconnect");
            mBluetoothLeService.connect(mDeviceAddress);
        }

        // If we have a timer running and slept for too long - update it.
        if (!mTimerPaused && mSecondsLeft > 0 && System.currentTimeMillis() - mTimerUpdatedAt > 1500) {
            long seconds = (System.currentTimeMillis() - mTimerUpdatedAt) / 1000;
            if (mSecondsLeft < seconds)
                seconds = 0;
            else
                seconds = mSecondsLeft - seconds;

            updateTimer((int)seconds);
        }
    }

    @Override
    protected void onStart() {
        mIsVisible = true;
        super.onStart();

        if (mListenerStopped) {
            registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
            if (mBluetoothLeService != null) {
                final boolean result = mBluetoothLeService.connect(mDeviceAddress);
                Log.d(TAG, "Connect request result=" + result);
            }
            mListenerStopped = false;

            Log.d(TAG, "onStart and no receiver, reregister and reconnect");
            // And now do the reconnect just in case we need to refresh our state
            mBluetoothLeService.connect(mDeviceAddress);
        }
    }

    @Override
    protected void onStop() {
        mIsVisible = false;
        super.onStop();
        if (mCurrentState == 0) {
            Log.d(TAG, "onStop and power is off, unregistering receiver");
            unregisterReceiver(mGattUpdateReceiver);
            mListenerStopped = true;
            mBluetoothLeService.disconnect();
        } else {
            moveTaskToBack(true);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!mListenerStopped) {
            unregisterReceiver(mGattUpdateReceiver);
            mListenerStopped = true;
            mBluetoothLeService.disconnect();
        }
        unbindService(mServiceConnection);
        mBluetoothLeService = null;
    }

    private void updateConnectionState(final int resourceId) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mConnectionState.setImageResource(resourceId);
            }
        });
    }

    private void registerOneTopStateListeners(BluetoothGattService gattService) {
        if (gattService == null) {
            Log.e(TAG, "Service does not support OneTop service uuid?");
            Toast.makeText(this, "Service does not support OneTop service uuid?", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        List<BluetoothGattCharacteristic> gattCharacteristics =
                gattService.getCharacteristics();

        // Loops through available Characteristics.
        for (BluetoothGattCharacteristic gattCharacteristic : gattCharacteristics) {
            String uuid = gattCharacteristic.getUuid().toString();

            if (uuid.equals(UsefulGattAttributes.ONETOP_CURRENT_STATE)) {
                mOnetopPowerStateCharacteristic = gattCharacteristic;
                mBluetoothLeService.setCharacteristicNotification(gattCharacteristic, true);
                mBluetoothLeService.readCharacteristic(gattCharacteristic);
                Log.d(TAG, "registered power state listener");
            }
            if (uuid.equals(UsefulGattAttributes.ONETOP_CURRENT_MODE)) {
                mOnetopModeCharacteristic = gattCharacteristic;
                mBluetoothLeService.readCharacteristic(gattCharacteristic);
                mBluetoothLeService.setCharacteristicNotification(gattCharacteristic, true);
                Log.d(TAG, "Registered mode listener");
            }
            if (uuid.equals(UsefulGattAttributes.ONETOP_SMART_CONTROL)) {
                mOnetopSmartArrayCharacteristic = gattCharacteristic;
                mBluetoothLeService.readCharacteristic(gattCharacteristic);
                Log.d(TAG, "Read Smart Mode");
            }
            if (uuid.equals(UsefulGattAttributes.ONETOP_POWER_OUTPUT)) {
                mOnetopPowerOutputCharacteristic = gattCharacteristic;
                mBluetoothLeService.setCharacteristicNotification(gattCharacteristic, true);
                mBluetoothLeService.readCharacteristic(gattCharacteristic);
                Log.d(TAG, "registered power output listener");
            }
            if (uuid.equals(UsefulGattAttributes.ONETOP_TEMP_READOUT)) {
                mOnetopTempCharacteristic = gattCharacteristic;
                mBluetoothLeService.readCharacteristic(gattCharacteristic);
                mBluetoothLeService.setCharacteristicNotification(gattCharacteristic, true);
                Log.d(TAG, "Registered temp listener");
            }
            if (uuid.equals(UsefulGattAttributes.ONETOP_TIMER_SECONDS_LEFT)) {
                mOnetopTimerLeftCharacteristic = gattCharacteristic;
                // Don't read - 60 sec granularity mBluetoothLeService.readCharacteristic(gattCharacteristic);
                mBluetoothLeService.setCharacteristicNotification(gattCharacteristic, true);
                Log.d(TAG, "Registered timer listener");
            }
            if (uuid.equals(UsefulGattAttributes.ONETOP_TURNOFF_CONTROL)) {
                mOnetopTurnOffCharacteristic = gattCharacteristic;
            }
            if (uuid.equals(UsefulGattAttributes.ONETOP_TIMER_START)) {
                mOnetopTimerStartCharacteristic = gattCharacteristic;
            }

        }

        if (mOnetopSmartArrayCharacteristic == null) {
            // This is totally unexpected, what do we do now?
            mStatusLine.setText(R.string.status_errordevice);
            return;
        }

        // Now that we updated all the characteristics - enable UI back.
        if (!mUIEnabled)
            enableUI();

    }

    private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
        return intentFilter;
    }
}
