package com.motor.esp32;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import java.io.*;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final UUID   BT_UUID     = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String DEVICE_NAME = "Motor_ESP32";
    private static final int    REQUEST_BT  = 1;

    // Frecuencia de red: 60 Hz → 8333 µs  |  50 Hz → 10000 µs
    private static final float SEMI_PERIODO_US = 8333f;

    // ── Puntos de calibración de voltaje (del usuario) ──
    // tiempoDisparo 7712 µs → 9.12 V RMS
    // tiempoDisparo 7200 µs → 24.0 V RMS
    private static final float T_CAL_LO  = 7712f;  // µs → voltaje bajo
    private static final float V_CAL_LO  = 9.12f;  // V RMS
    private static final float T_CAL_HI  = 7200f;  // µs → voltaje alto
    private static final float V_CAL_HI  = 24.0f;  // V RMS

    // ── Rango del slider ──
    private static final int RPM_MIN = 800;
    private static final int RPM_MAX = 3000;

    // ── Bluetooth ──
    private BluetoothAdapter  btAdapter;
    private BluetoothSocket   btSocket;
    private InputStream       btIn;
    private OutputStream      btOut;
    private boolean           isConnected = false;
    private Thread            readThread;

    // ── UI ──
    private TextView   tvStatusDot, tvStatusText, tvEstadoMotor;
    private TextView   tvRpmActual, tvRpmObjetivo, tvAngulo, tvTiempoDisparo;
    private TextView   tvVoltaje, tvModoControl, tvLog, tvSliderValue;
    private SeekBar    seekBarRpm;
    private SineWaveView sineWaveView;
    private Button     btnConectar, btnEnviar, btnStop;

    private final Handler       uiHandler = new Handler(Looper.getMainLooper());
    private final StringBuilder logBuffer = new StringBuilder();

    // =========================================================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        setupSeekBar();
        setupButtons();

        btAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    // =========================================================
    // BINDING
    // =========================================================
    private void bindViews() {
        tvStatusDot     = findViewById(R.id.tvStatusDot);
        tvStatusText    = findViewById(R.id.tvStatusText);
        tvEstadoMotor   = findViewById(R.id.tvEstadoMotor);
        tvRpmActual     = findViewById(R.id.tvRpmActual);
        tvRpmObjetivo   = findViewById(R.id.tvRpmObjetivo);
        tvAngulo        = findViewById(R.id.tvAngulo);
        tvTiempoDisparo = findViewById(R.id.tvTiempoDisparo);
        tvVoltaje       = findViewById(R.id.tvVoltaje);
        tvModoControl   = findViewById(R.id.tvModoControl);
        tvLog           = findViewById(R.id.tvLog);
        tvSliderValue   = findViewById(R.id.tvSliderValue);
        seekBarRpm      = findViewById(R.id.seekBarRpm);
        sineWaveView    = findViewById(R.id.sineWaveView);
        btnConectar     = findViewById(R.id.btnConectar);
        btnEnviar       = findViewById(R.id.btnEnviar);
        btnStop         = findViewById(R.id.btnStop);
    }

    // =========================================================
    // SEEKBAR  (progress 0..2200 → RPM 800..3000)
    // =========================================================
    private void setupSeekBar() {
        // Posición inicial en 1500 RPM
        seekBarRpm.setProgress(1500 - RPM_MIN);
        tvSliderValue.setText("1500 RPM");

        seekBarRpm.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int rpm = progress + RPM_MIN;
                tvSliderValue.setText(rpm + " RPM");
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
    }

    // =========================================================
    // BOTONES
    // =========================================================
    private void setupButtons() {
        btnConectar.setOnClickListener(v -> {
            if (isConnected) disconnectBluetooth();
            else connectBluetooth();
        });

        btnEnviar.setOnClickListener(v -> {
            int rpm = seekBarRpm.getProgress() + RPM_MIN;
            if (isConnected) {
                sendData(String.valueOf(rpm));
                addLog(">> Enviado: " + rpm + " RPM");
            } else {
                Toast.makeText(this, "No conectado al motor", Toast.LENGTH_SHORT).show();
            }
        });

        btnStop.setOnClickListener(v -> {
            if (isConnected) {
                sendData("0");
                addLog(">> STOP enviado");
            } else {
                Toast.makeText(this, "No conectado", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // =========================================================
    // VOLTAJE ESTIMADO (interpolación lineal calibrada)
    // =========================================================
    /**
     * Interpolación lineal entre los dos puntos medidos:
     *   T_CAL_LO (7712 µs) → V_CAL_LO (9.12 V)
     *   T_CAL_HI (7200 µs) → V_CAL_HI (24.0 V)
     *
     * Pendiente: ΔV/Δt = (24.0 - 9.12) / (7200 - 7712) = -0.02906 V/µs
     * Al bajar el tiempo de disparo, sube el voltaje.
     */
    private float calcVoltaje(int tiempoUs) {
        float slope = (V_CAL_HI - V_CAL_LO) / (T_CAL_HI - T_CAL_LO); // negativo
        float v = V_CAL_LO + slope * (tiempoUs - T_CAL_LO);
        // Saturar entre 0 y 230 V (o el máximo esperado)
        if (v < 0f)    v = 0f;
        if (v > 230f)  v = 230f;
        return v;
    }

    // =========================================================
    // BLUETOOTH — CONEXIÓN
    // =========================================================
    private void connectBluetooth() {
        if (btAdapter == null) {
            Toast.makeText(this, "Este dispositivo no tiene Bluetooth", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!btAdapter.isEnabled()) {
            Toast.makeText(this, "Activa el Bluetooth primero", Toast.LENGTH_SHORT).show();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{
                                Manifest.permission.BLUETOOTH_CONNECT,
                                Manifest.permission.BLUETOOTH_SCAN
                        }, REQUEST_BT);
                return;
            }
        }

        BluetoothDevice device = null;
        Set<BluetoothDevice> paired = btAdapter.getBondedDevices();
        for (BluetoothDevice d : paired) {
            if (DEVICE_NAME.equals(d.getName())) { device = d; break; }
        }

        if (device == null) {
            Toast.makeText(this,
                    "'" + DEVICE_NAME + "' no encontrado.\n¿Ya lo emparejaste en Ajustes?",
                    Toast.LENGTH_LONG).show();
            addLog("ERROR: Dispositivo no encontrado");
            return;
        }

        btnConectar.setEnabled(false);
        addLog("Conectando a " + DEVICE_NAME + "...");

        final BluetoothDevice finalDevice = device;
        new Thread(() -> {
            try {
                BluetoothSocket socket =
                        finalDevice.createRfcommSocketToServiceRecord(BT_UUID);
                btAdapter.cancelDiscovery();
                socket.connect();

                btSocket    = socket;
                btIn        = socket.getInputStream();
                btOut       = socket.getOutputStream();
                isConnected = true;

                uiHandler.post(() -> {
                    setConnectedUI(true);
                    btnConectar.setEnabled(true);
                    addLog("✓ Conectado a " + DEVICE_NAME);
                });

                startReading();

            } catch (IOException e) {
                uiHandler.post(() -> {
                    btnConectar.setEnabled(true);
                    addLog("ERROR al conectar: " + e.getMessage());
                    Toast.makeText(this, "Fallo la conexión. Reintenta.", Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    // =========================================================
    // LECTURA EN HILO SEPARADO
    // =========================================================
    private void startReading() {
        readThread = new Thread(() -> {
            StringBuilder buffer = new StringBuilder();
            byte[] bytes = new byte[512];

            while (isConnected && !Thread.interrupted()) {
                try {
                    int count = btIn.read(bytes);
                    if (count <= 0) continue;

                    buffer.append(new String(bytes, 0, count));

                    String buf = buffer.toString();
                    int idx;
                    while ((idx = buf.indexOf('\n')) != -1) {
                        String line = buf.substring(0, idx).trim();
                        buf = buf.substring(idx + 1);
                        if (!line.isEmpty()) processLine(line);
                    }
                    buffer = new StringBuilder(buf);

                } catch (IOException e) {
                    if (isConnected) {
                        uiHandler.post(() -> {
                            addLog("Conexión perdida");
                            disconnectBluetooth();
                        });
                    }
                    break;
                }
            }
        });
        readThread.setDaemon(true);
        readThread.start();
    }

    // =========================================================
    // PARSING
    // =========================================================
    private void processLine(String line) {
        if (line.startsWith(">>")) {
            uiHandler.post(() -> addLog(line));
            return;
        }

        // Formato: $estado,rpmObj,rpmAct,tiempoUs#
        if (line.startsWith("$") && line.endsWith("#")) {
            String inner = line.substring(1, line.length() - 1);
            String[] parts = inner.split(",");
            if (parts.length == 4) {
                try {
                    int estado   = Integer.parseInt(parts[0].trim());
                    int rpmObj   = Integer.parseInt(parts[1].trim());
                    int rpmAct   = Integer.parseInt(parts[2].trim());
                    int tiempoUs = Integer.parseInt(parts[3].trim());

                    float angulo  = (tiempoUs / SEMI_PERIODO_US) * 180f;
                    float voltaje = calcVoltaje(tiempoUs);

                    uiHandler.post(() -> updateUI(estado, rpmObj, rpmAct, tiempoUs, angulo, voltaje));
                } catch (NumberFormatException ignored) {
                    uiHandler.post(() -> addLog("Parse error: " + line));
                }
            }
        }
    }

    // =========================================================
    // ACTUALIZACIÓN DE UI
    // =========================================================
    private void updateUI(int estado, int rpmObj, int rpmAct,
                          int tiempoUs, float angulo, float voltaje) {

        tvRpmActual.setText(String.valueOf(rpmAct));
        tvRpmObjetivo.setText(String.valueOf(rpmObj));
        tvAngulo.setText(String.format("%.1f", angulo));
        tvTiempoDisparo.setText(tiempoUs + " µs");
        tvVoltaje.setText(String.format("%.1f V", voltaje));

        // Color del voltaje según nivel de potencia
        if      (voltaje < 12f)  tvVoltaje.setTextColor(Color.parseColor("#58A6FF")); // azul: bajo
        else if (voltaje < 18f)  tvVoltaje.setTextColor(Color.parseColor("#FFA657")); // naranja: medio
        else                     tvVoltaje.setTextColor(Color.parseColor("#FF5252")); // rojo: alto

        // Estado del motor
        String estadoStr;
        int    estadoColor;
        switch (estado) {
            case 0:  estadoStr = "DETENIDO"; estadoColor = Color.parseColor("#FF5252"); break;
            case 1:  estadoStr = "RAMPA";    estadoColor = Color.parseColor("#FFA657"); break;
            case 2:  estadoStr = "CONTROL";  estadoColor = Color.parseColor("#3FB950"); break;
            default: estadoStr = "---";      estadoColor = Color.GRAY;
        }
        tvEstadoMotor.setText("Estado: " + estadoStr);
        tvEstadoMotor.setTextColor(estadoColor);
        tvModoControl.setText("Modo: " + estadoStr);

        // Color RPM actual según proximidad al objetivo
        if (estado == 0 || rpmObj == 0) {
            tvRpmActual.setTextColor(Color.parseColor("#6E7681"));
        } else {
            int diff = Math.abs(rpmAct - rpmObj);
            if      (diff <= 100) tvRpmActual.setTextColor(Color.parseColor("#3FB950"));
            else if (diff <= 350) tvRpmActual.setTextColor(Color.parseColor("#FFA657"));
            else                  tvRpmActual.setTextColor(Color.parseColor("#FF5252"));
        }

        sineWaveView.setFiringAngle(angulo);
    }

    // =========================================================
    // ENVÍO
    // =========================================================
    private void sendData(String data) {
        if (!isConnected || btOut == null) return;
        new Thread(() -> {
            try { btOut.write((data + "\n").getBytes()); }
            catch (IOException e) {
                uiHandler.post(() -> addLog("Error al enviar: " + e.getMessage()));
            }
        }).start();
    }

    // =========================================================
    // DESCONEXIÓN
    // =========================================================
    private void disconnectBluetooth() {
        isConnected = false;
        if (readThread != null) readThread.interrupt();
        try { if (btSocket != null) btSocket.close(); } catch (IOException ignored) {}
        btSocket = null; btIn = null; btOut = null;
        setConnectedUI(false);
    }

    // =========================================================
    // HELPERS UI
    // =========================================================
    private void setConnectedUI(boolean connected) {
        if (connected) {
            tvStatusDot.setTextColor(Color.parseColor("#3FB950"));
            tvStatusText.setText("CONECTADO — " + DEVICE_NAME);
            btnConectar.setText("DESC.");
            btnConectar.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#DA3633")));
        } else {
            tvStatusDot.setTextColor(Color.parseColor("#FF5252"));
            tvStatusText.setText("DESCONECTADO");
            tvEstadoMotor.setText("Estado: --");
            tvRpmActual.setText("0");
            tvRpmObjetivo.setText("0");
            tvAngulo.setText("---");
            tvTiempoDisparo.setText("---- µs");
            tvVoltaje.setText("-- V");
            tvModoControl.setText("Modo: --");
            btnConectar.setText("BT");
            btnConectar.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#1F6FEB")));
            sineWaveView.setFiringAngle(180f);
        }
    }

    private void addLog(String msg) {
        logBuffer.append(msg).append("\n");
        String full = logBuffer.toString();
        String[] lines = full.split("\n");
        if (lines.length > 5) {
            StringBuilder sb = new StringBuilder();
            for (int i = lines.length - 5; i < lines.length; i++)
                sb.append(lines[i]).append("\n");
            logBuffer.setLength(0);
            logBuffer.append(sb);
        }
        tvLog.setText(logBuffer.toString().trim());
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQUEST_BT && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            connectBluetooth();
        } else {
            Toast.makeText(this, "Permiso Bluetooth denegado", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnectBluetooth();
    }
}