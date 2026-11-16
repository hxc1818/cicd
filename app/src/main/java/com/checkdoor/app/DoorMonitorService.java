package com.checkdoor.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class DoorMonitorService extends Service implements SensorEventListener {
    private static final String TAG = "DoorMonitorService";
    public static final String ACTION_STATUS_UPDATE = "com.checkdoor.app.STATUS_UPDATE";
    public static final String EXTRA_CURRENT_ANGLE = "current_angle";
    public static final String EXTRA_DOOR_STATE = "door_state"; // "OPEN", "CLOSED", "UNCALIBRATED"
    public static final String EXTRA_ANGLE_DIFF = "angle_diff";

    private static final String CHANNEL_ID = "checkdoor_monitor_channel";
    private static final int NOTIFICATION_ID = 1001;

    private SensorManager sensorManager;
    private Sensor rotationVectorSensor;
    private Sensor accelerometerSensor;
    private Sensor magneticSensor;

    private float[] accelerometerReading = new float[3];
    private float[] magnetometerReading = new float[3];
    private boolean hasAccel = false;
    private boolean hasMag = false;

    private final float[] rotationMatrix = new float[9];
    private final float[] orientationAngles = new float[3];

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    private float currentAzimuth = 0f;
    private String currentDoorState = "UNCALIBRATED"; // UNCALIBRATED, CLOSED, OPEN

    private Handler heartbeatHandler;
    private Runnable heartbeatRunnable;

    public static boolean isRunning = false;

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("开门卫士服务已启动", "正在实时监测门的状态..."));

        acquireLocks();
        initSensors();
        startHeartbeat();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "门禁监控前台服务",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("保持传感器与网络后台运行，实时检测开门联动电脑锁屏");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String title, String content) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String title, String content) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(title, content));
        }
    }

    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CheckDoor:ServiceWakeLock");
                wakeLock.acquire();
            }

            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "CheckDoor:WifiLock");
                wifiLock.acquire();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error acquiring locks: " + e.getMessage());
        }
    }

    private void releaseLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error releasing locks: " + e.getMessage());
        }
    }

    private void initSensors() {
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager == null) return;

        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (rotationVectorSensor != null) {
            sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_UI);
            Log.d(TAG, "Registered TYPE_ROTATION_VECTOR sensor");
        } else {
            accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            magneticSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
            if (accelerometerSensor != null) {
                sensorManager.registerListener(this, accelerometerSensor, SensorManager.SENSOR_DELAY_UI);
            }
            if (magneticSensor != null) {
                sensorManager.registerListener(this, magneticSensor, SensorManager.SENSOR_DELAY_UI);
            }
            Log.d(TAG, "Registered fallback Accel + Mag sensors");
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
            SensorManager.getOrientation(rotationMatrix, orientationAngles);
            updateOrientation(orientationAngles[0]);
        } else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, accelerometerReading, 0, accelerometerReading.length);
            hasAccel = true;
            processAccelMag();
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, magnetometerReading, 0, magnetometerReading.length);
            hasMag = true;
            processAccelMag();
        }
    }

    private void processAccelMag() {
        if (hasAccel && hasMag) {
            boolean success = SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerReading, magnetometerReading);
            if (success) {
                SensorManager.getOrientation(rotationMatrix, orientationAngles);
                updateOrientation(orientationAngles[0]);
            }
        }
    }

    private void updateOrientation(float azimuthRadian) {
        float deg = (float) Math.toDegrees(azimuthRadian);
        if (deg < 0) {
            deg += 360f;
        }
        this.currentAzimuth = deg;

        evaluateDoorState();
    }

    private void evaluateDoorState() {
        float closedAngle = DoorConfig.getClosedAngle(this);
        float threshold = DoorConfig.getThreshold(this);

        if (closedAngle < 0) {
            currentDoorState = "UNCALIBRATED";
            broadcastState(0f);
            return;
        }

        float diff = DoorConfig.calculateAngleDiff(currentAzimuth, closedAngle);
        String previousState = currentDoorState;

        if ("CLOSED".equals(currentDoorState) || "UNCALIBRATED".equals(currentDoorState)) {
            if (diff >= threshold) {
                currentDoorState = "OPEN";
            } else {
                currentDoorState = "CLOSED";
            }
        } else if ("OPEN".equals(currentDoorState)) {
            // 迟滞阈值: 回到 threshold * 0.5f 以内才判定关门
            if (diff <= (threshold * 0.5f)) {
                currentDoorState = "CLOSED";
            }
        }

        if (!currentDoorState.equals(previousState)) {
            onStateChanged(currentDoorState, previousState);
        }

        broadcastState(diff);
    }

    private void onStateChanged(String newState, String oldState) {
        Log.i(TAG, "Door state transition: " + oldState + " -> " + newState);
        String targetIp = DoorConfig.getTargetIp(this);
        int port = DoorConfig.getTargetPort(this);

        if ("OPEN".equals(newState)) {
            updateNotification("🚨 警报: 门已打开！", "已触发局域网电脑立即锁屏！");
            UdpSender.sendCommand(targetIp, port, "DOOR_OPEN", currentAzimuth);
        } else if ("CLOSED".equals(newState)) {
            updateNotification("🚪 门已关闭", "已通知电脑解除锁屏保护");
            UdpSender.sendCommand(targetIp, port, "DOOR_CLOSED", currentAzimuth);
        }
    }

    private void broadcastState(float diff) {
        Intent intent = new Intent(ACTION_STATUS_UPDATE);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_CURRENT_ANGLE, currentAzimuth);
        intent.putExtra(EXTRA_DOOR_STATE, currentDoorState);
        intent.putExtra(EXTRA_ANGLE_DIFF, diff);
        sendBroadcast(intent);
    }

    private void startHeartbeat() {
        heartbeatHandler = new Handler(Looper.getMainLooper());
        heartbeatRunnable = new Runnable() {
            @Override
            public void run() {
                String targetIp = DoorConfig.getTargetIp(DoorMonitorService.this);
                int port = DoorConfig.getTargetPort(DoorMonitorService.this);
                UdpSender.sendCommand(targetIp, port, "HEARTBEAT_" + currentDoorState, currentAzimuth);
                heartbeatHandler.postDelayed(this, 3000);
            }
        };
        heartbeatHandler.postDelayed(heartbeatRunnable, 3000);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        if (heartbeatHandler != null && heartbeatRunnable != null) {
            heartbeatHandler.removeCallbacks(heartbeatRunnable);
        }
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        releaseLocks();
        Log.d(TAG, "DoorMonitorService destroyed");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
