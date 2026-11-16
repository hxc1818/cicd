package com.checkdoor.app;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    private TextView tvDoorStatus;
    private TextView tvCurrentAngle;
    private TextView tvClosedAngle;
    private TextView tvOpenAngle;
    private TextView tvSensitivityLabel;
    private SeekBar sbSensitivity;
    private EditText etTargetIp;
    private EditText etTargetPort;
    private Button btnCalibrateClosed;
    private Button btnCalibrateOpen;
    private Button btnToggleService;
    private Button btnTestOpen;
    private Button btnTestClose;
    private TextView tvLogs;

    private SensorManager sensorManager;
    private Sensor rotationSensor;
    private Sensor accelSensor;
    private Sensor magSensor;
    private final float[] rotationMatrix = new float[9];
    private final float[] orientationAngles = new float[3];
    private final float[] accelValues = new float[3];
    private final float[] magValues = new float[3];
    private boolean hasAccel = false, hasMag = false;

    private float currentAzimuth = 0f;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DoorMonitorService.ACTION_STATUS_UPDATE.equals(intent.getAction())) {
                float angle = intent.getFloatExtra(DoorMonitorService.EXTRA_CURRENT_ANGLE, 0f);
                String state = intent.getStringExtra(DoorMonitorService.EXTRA_DOOR_STATE);
                float diff = intent.getFloatExtra(DoorMonitorService.EXTRA_ANGLE_DIFF, 0f);
                updateUiStatus(angle, state, diff);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        loadSavedConfig();
        setupListeners();
        requestPermissions();
        initLocalSensors();
    }

    private void initViews() {
        tvDoorStatus = findViewById(R.id.tvDoorStatus);
        tvCurrentAngle = findViewById(R.id.tvCurrentAngle);
        tvClosedAngle = findViewById(R.id.tvClosedAngle);
        tvOpenAngle = findViewById(R.id.tvOpenAngle);
        tvSensitivityLabel = findViewById(R.id.tvSensitivityLabel);
        sbSensitivity = findViewById(R.id.sbSensitivity);
        etTargetIp = findViewById(R.id.etTargetIp);
        etTargetPort = findViewById(R.id.etTargetPort);
        btnCalibrateClosed = findViewById(R.id.btnCalibrateClosed);
        btnCalibrateOpen = findViewById(R.id.btnCalibrateOpen);
        btnToggleService = findViewById(R.id.btnToggleService);
        btnTestOpen = findViewById(R.id.btnTestOpen);
        btnTestClose = findViewById(R.id.btnTestClose);
        tvLogs = findViewById(R.id.tvLogs);
    }

    private void loadSavedConfig() {
        float closed = DoorConfig.getClosedAngle(this);
        float open = DoorConfig.getOpenAngle(this);
        float threshold = DoorConfig.getThreshold(this);
        String ip = DoorConfig.getTargetIp(this);
        int port = DoorConfig.getTargetPort(this);

        if (closed >= 0) {
            tvClosedAngle.setText(String.format("关门基准角度: %.1f°", closed));
        }
        if (open >= 0) {
            tvOpenAngle.setText(String.format("开门参考角度: %.1f°", open));
        }

        int progress = (int) threshold;
        sbSensitivity.setProgress(progress);
        tvSensitivityLabel.setText(String.format("触发灵敏度阈值: %d° (转动超过此角度即触发开门)", progress));

        etTargetIp.setText(ip);
        etTargetPort.setText(String.valueOf(port));

        updateServiceButtonState();
    }

    private void setupListeners() {
        btnCalibrateClosed.setOnClickListener(v -> {
            DoorConfig.saveClosedAngle(this, currentAzimuth);
            tvClosedAngle.setText(String.format("关门基准角度: %.1f°", currentAzimuth));
            Toast.makeText(this, "已记录【关门基准角度】: " + String.format("%.1f°", currentAzimuth), Toast.LENGTH_SHORT).show();
            appendLog("校准关门角度为 " + String.format("%.1f°", currentAzimuth));
        });

        btnCalibrateOpen.setOnClickListener(v -> {
            DoorConfig.saveOpenAngle(this, currentAzimuth);
            tvOpenAngle.setText(String.format("开门参考角度: %.1f°", currentAzimuth));
            float closed = DoorConfig.getClosedAngle(this);
            if (closed >= 0) {
                float diff = DoorConfig.calculateAngleDiff(currentAzimuth, closed);
                Toast.makeText(this, "已记录【开门角度】，与关门差值: " + String.format("%.1f°", diff), Toast.LENGTH_SHORT).show();
                appendLog("校准开门角度为 " + String.format("%.1f°", currentAzimuth) + "，开度 " + String.format("%.1f°", diff));
            } else {
                Toast.makeText(this, "已记录【开门角度】: " + String.format("%.1f°", currentAzimuth), Toast.LENGTH_SHORT).show();
            }
        });

        sbSensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (progress < 2) progress = 2;
                tvSensitivityLabel.setText(String.format("触发灵敏度阈值: %d° (转动超过此角度即触发开门)", progress));
                if (fromUser) {
                    DoorConfig.saveThreshold(MainActivity.this, progress);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        TextWatcher networkWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                saveNetworkSettings();
            }
        };
        etTargetIp.addTextChangedListener(networkWatcher);
        etTargetPort.addTextChangedListener(networkWatcher);

        btnToggleService.setOnClickListener(v -> toggleMonitorService());

        btnTestOpen.setOnClickListener(v -> {
            saveNetworkSettings();
            String ip = DoorConfig.getTargetIp(this);
            int port = DoorConfig.getTargetPort(this);
            UdpSender.sendCommand(ip, port, "DOOR_OPEN", currentAzimuth);
            Toast.makeText(this, "已向 " + ip + ":" + port + " 发送【开门锁屏】指令", Toast.LENGTH_SHORT).show();
            appendLog("发送测试指令 -> DOOR_OPEN");
        });

        btnTestClose.setOnClickListener(v -> {
            saveNetworkSettings();
            String ip = DoorConfig.getTargetIp(this);
            int port = DoorConfig.getTargetPort(this);
            UdpSender.sendCommand(ip, port, "DOOR_CLOSED", currentAzimuth);
            Toast.makeText(this, "已向 " + ip + ":" + port + " 发送【关门解锁】指令", Toast.LENGTH_SHORT).show();
            appendLog("发送测试指令 -> DOOR_CLOSED");
        });
    }

    private void saveNetworkSettings() {
        String ip = etTargetIp.getText().toString().trim();
        if (ip.isEmpty()) ip = "255.255.255.255";
        int port = 39888;
        try {
            port = Integer.parseInt(etTargetPort.getText().toString().trim());
        } catch (Exception ignored) {}
        DoorConfig.saveNetwork(this, ip, port);
    }

    private void toggleMonitorService() {
        saveNetworkSettings();
        Intent intent = new Intent(this, DoorMonitorService.class);
        if (DoorMonitorService.isRunning) {
            stopService(intent);
            DoorMonitorService.isRunning = false;
            updateServiceButtonState();
            appendLog("监控服务已停止");
            Toast.makeText(this, "监控服务已停止", Toast.LENGTH_SHORT).show();
        } else {
            float closed = DoorConfig.getClosedAngle(this);
            if (closed < 0) {
                Toast.makeText(this, "请先点击校准【关门状态】后再启动服务！", Toast.LENGTH_LONG).show();
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            DoorMonitorService.isRunning = true;
            updateServiceButtonState();
            appendLog("后台监控服务已启动！");
            Toast.makeText(this, "后台实时监控已启动", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateServiceButtonState() {
        if (DoorMonitorService.isRunning) {
            btnToggleService.setText("⏹️ 停止后台监控服务");
            btnToggleService.setBackgroundColor(Color.parseColor("#D32F2F"));
        } else {
            btnToggleService.setText("▶️ 启动后台实时监控服务");
            btnToggleService.setBackgroundColor(Color.parseColor("#1976D2"));
        }
    }

    private void updateUiStatus(float angle, String state, float diff) {
        tvCurrentAngle.setText(String.format("当前朝向角度: %.1f°", angle));
        if ("OPEN".equals(state)) {
            tvDoorStatus.setText("🚨 门已打开 (偏差 " + String.format("%.1f°", diff) + ") - 电脑已锁屏");
            tvDoorStatus.setTextColor(Color.parseColor("#C62828"));
        } else if ("CLOSED".equals(state)) {
            tvDoorStatus.setText("🚪 门已关闭 (偏差 " + String.format("%.1f°", diff) + ") - 状态安全");
            tvDoorStatus.setTextColor(Color.parseColor("#2E7D32"));
        } else {
            tvDoorStatus.setText("⏳ 请先校准关门与开门角度");
            tvDoorStatus.setTextColor(Color.parseColor("#E65100"));
        }
    }

    private void appendLog(String log) {
        tvLogs.setText(String.format("[%s] %s\n%s",
                android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis()),
                log,
                tvLogs.getText().toString()));
    }

    private void initLocalSensors() {
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            if (rotationSensor == null) {
                accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, new IntentFilter(DoorMonitorService.ACTION_STATUS_UPDATE), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, new IntentFilter(DoorMonitorService.ACTION_STATUS_UPDATE));
        }

        if (sensorManager != null) {
            if (rotationSensor != null) {
                sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI);
            } else {
                if (accelSensor != null) sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_UI);
                if (magSensor != null) sensorManager.registerListener(this, magSensor, SensorManager.SENSOR_DELAY_UI);
            }
        }
        updateServiceButtonState();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(statusReceiver);
        } catch (Exception ignored) {}

        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (DoorMonitorService.isRunning) return;

        if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
            SensorManager.getOrientation(rotationMatrix, orientationAngles);
            calculateCurrentAngle(orientationAngles[0]);
        } else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, accelValues, 0, 3);
            hasAccel = true;
            tryCalculateOrientation();
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, magValues, 0, 3);
            hasMag = true;
            tryCalculateOrientation();
        }
    }

    private void tryCalculateOrientation() {
        if (hasAccel && hasMag) {
            boolean success = SensorManager.getRotationMatrix(rotationMatrix, null, accelValues, magValues);
            if (success) {
                SensorManager.getOrientation(rotationMatrix, orientationAngles);
                calculateCurrentAngle(orientationAngles[0]);
            }
        }
    }

    private void calculateCurrentAngle(float azimuthRadian) {
        float deg = (float) Math.toDegrees(azimuthRadian);
        if (deg < 0) deg += 360f;
        this.currentAzimuth = deg;

        tvCurrentAngle.setText(String.format("当前朝向角度: %.1f°", currentAzimuth));

        float closed = DoorConfig.getClosedAngle(this);
        if (closed >= 0) {
            float diff = DoorConfig.calculateAngleDiff(currentAzimuth, closed);
            float threshold = DoorConfig.getThreshold(this);
            if (diff >= threshold) {
                tvDoorStatus.setText("🚨 门已打开 (偏差 " + String.format("%.1f°", diff) + ") [服务未启动]");
                tvDoorStatus.setTextColor(Color.parseColor("#C62828"));
            } else {
                tvDoorStatus.setText("🚪 门已关闭 (偏差 " + String.format("%.1f°", diff) + ") [服务未启动]");
                tvDoorStatus.setTextColor(Color.parseColor("#2E7D32"));
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
}
