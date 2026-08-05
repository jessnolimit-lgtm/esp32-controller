package com.example.rccarcontroller;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Set;
import java.util.UUID;

/**
 * MainActivity — Landscape RC Car Dashboard
 *
 * Layout (landscape):
 *   LEFT  : Drive controls  (Forward / Stop / Backward)
 *   CENTER: Car dashboard   (Speedometer, status, headlights, signals, hazard, horn)
 *   RIGHT : Steering controls (Left / Right)
 *
 * ESP32 command protocol:
 *   F – Forward      B – Backward     L – Steer Left    R – Steer Right    S – Stop
 *   H – Headlights   I – Left Signal  J – Right Signal  K – Hazard         P – Horn
 */
public class MainActivity extends AppCompatActivity {

    // Standard SPP UUID for Bluetooth Classic serial (SPP)
    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private static final String DEVICE_NAME = "ESP32_RC_CAR";
    private static final int PERMISSION_REQUEST_CODE = 1;

    // Blink interval (ms) — mirrors the ESP32 BLINK_INTERVAL
    private static final int BLINK_INTERVAL_MS = 500;

    // Bluetooth
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket  bluetoothSocket;
    private OutputStream     outputStream;

    // ── Dashboard state ─────────────────────────────────────────
    private boolean headlightsOn  = false;
    private boolean leftSignalOn  = false;
    private boolean rightSignalOn = false;
    private boolean hazardOn      = false;
    private boolean blinkVisible  = false;  // current blink phase

    // ── UI ───────────────────────────────────────────────────────
    // Drive
    private ImageView btnForward, btnBackward, btnStop;
    // Steering
    private ImageView btnLeft, btnRight;
    // Dashboard controls
    private ImageView btnHeadlights, btnHazard, btnHorn;
    private ImageView btnLeftSignal, btnRightSignal;
    // Dashboard indicators
    private ImageView indicatorLeft, indicatorRight;
    // Speedometer
    private SpeedometerView speedometerView;
    private TextView tvGearLabel;
    // Status / connect
    private TextView tvStatus, btnConnect;

    // Blink handler
    private final Handler blinkHandler = new Handler(Looper.getMainLooper());
    private final Runnable blinkRunnable = new Runnable() {
        @Override
        public void run() {
            blinkVisible = !blinkVisible;
            updateSignalIndicators();
            if (leftSignalOn || rightSignalOn || hazardOn) {
                blinkHandler.postDelayed(this, BLINK_INTERVAL_MS);
            }
        }
    };

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep screen on while the controller is open
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        bindViews();
        setUpListeners();
        setDisconnectedState();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        blinkHandler.removeCallbacks(blinkRunnable);
        disconnect();
    }

    // =========================================================================
    // View binding
    // =========================================================================

    private void bindViews() {
        // Drive
        btnForward  = findViewById(R.id.btnForward);
        btnBackward = findViewById(R.id.btnBackward);
        btnStop     = findViewById(R.id.btnStop);

        // Steering
        btnLeft  = findViewById(R.id.btnLeft);
        btnRight = findViewById(R.id.btnRight);

        // Dashboard controls
        btnHeadlights  = findViewById(R.id.btnHeadlights);
        btnHazard      = findViewById(R.id.btnHazard);
        btnHorn        = findViewById(R.id.btnHorn);
        btnLeftSignal  = findViewById(R.id.btnLeftSignal);
        btnRightSignal = findViewById(R.id.btnRightSignal);

        // Indicators
        indicatorLeft  = findViewById(R.id.indicatorLeft);
        indicatorRight = findViewById(R.id.indicatorRight);

        // Speedometer
        speedometerView = findViewById(R.id.speedometerView);
        tvGearLabel     = findViewById(R.id.tvGearLabel);

        // Status / connect
        tvStatus   = findViewById(R.id.tvStatus);
        btnConnect = findViewById(R.id.btnConnect);
    }

    // =========================================================================
    // Listeners
    // =========================================================================

    private void setUpListeners() {
        // Connect
        btnConnect.setOnClickListener(v -> connectBluetooth());

        // Drive
        btnForward.setOnClickListener(v -> {
            sendCommand("F");
            setDriveState(SpeedometerView.STATE_FORWARD, "FORWARD");
        });
        btnBackward.setOnClickListener(v -> {
            sendCommand("B");
            setDriveState(SpeedometerView.STATE_REVERSE, "REVERSE");
        });
        btnStop.setOnClickListener(v -> {
            sendCommand("S");
            setDriveState(SpeedometerView.STATE_IDLE, "IDLE");
        });

        // Steering
        btnLeft.setOnClickListener(v  -> sendCommand("L"));
        btnRight.setOnClickListener(v -> sendCommand("R"));

        // Lights
        btnHeadlights.setOnClickListener(v -> toggleHeadlights());
        btnHazard.setOnClickListener(v     -> toggleHazard());
        btnHorn.setOnClickListener(v       -> honkHorn());

        // Signals
        btnLeftSignal.setOnClickListener(v  -> toggleLeftSignal());
        btnRightSignal.setOnClickListener(v -> toggleRightSignal());
    }

    // =========================================================================
    // Drive state
    // =========================================================================

    private void setDriveState(int state, String label) {
        speedometerView.setState(state);
        tvGearLabel.setText(label);

        int color;
        switch (state) {
            case SpeedometerView.STATE_FORWARD:
                color = ContextCompat.getColor(this, R.color.accent_blue);
                break;
            case SpeedometerView.STATE_REVERSE:
                color = ContextCompat.getColor(this, R.color.btn_stop);
                break;
            default:
                color = ContextCompat.getColor(this, R.color.text_secondary);
                break;
        }
        tvGearLabel.setTextColor(color);
    }

    // =========================================================================
    // Light controls
    // =========================================================================

    private void toggleHeadlights() {
        headlightsOn = !headlightsOn;
        sendCommand("H");
        updateHeadlightUI();
    }

    private void updateHeadlightUI() {
        if (headlightsOn) {
            btnHeadlights.setColorFilter(
                    ContextCompat.getColor(this, R.color.light_on_white));
            btnHeadlights.setAlpha(1f);
        } else {
            btnHeadlights.clearColorFilter();
            btnHeadlights.setAlpha(0.4f);
        }
    }

    private void toggleLeftSignal() {
        // Cancel conflicting states
        if (hazardOn) { hazardOn = false; }
        if (rightSignalOn) { rightSignalOn = false; }

        leftSignalOn = !leftSignalOn;
        sendCommand("I");
        restartBlink();
        updateSignalButtonTint();
    }

    private void toggleRightSignal() {
        if (hazardOn) { hazardOn = false; }
        if (leftSignalOn) { leftSignalOn = false; }

        rightSignalOn = !rightSignalOn;
        sendCommand("J");
        restartBlink();
        updateSignalButtonTint();
    }

    private void toggleHazard() {
        leftSignalOn  = false;
        rightSignalOn = false;
        hazardOn      = !hazardOn;
        sendCommand("K");
        restartBlink();
        updateSignalButtonTint();
    }

    private void honkHorn() {
        sendCommand("P");
        // Visual feedback: brief tint then restore
        btnHorn.setColorFilter(ContextCompat.getColor(this, R.color.accent_blue));
        btnHorn.setAlpha(1f);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            btnHorn.clearColorFilter();
            btnHorn.setAlpha(0.5f);
        }, 300);
    }

    // ── Blink management ─────────────────────────────────────────

    private void restartBlink() {
        blinkHandler.removeCallbacks(blinkRunnable);
        blinkVisible = true;

        if (leftSignalOn || rightSignalOn || hazardOn) {
            updateSignalIndicators();
            blinkHandler.postDelayed(blinkRunnable, BLINK_INTERVAL_MS);
        } else {
            // All signals off — dim indicators
            indicatorLeft.setAlpha(0.2f);
            indicatorRight.setAlpha(0.2f);
        }
        updateSignalButtonTint();
    }

    private void updateSignalIndicators() {
        if (hazardOn) {
            float alpha = blinkVisible ? 1f : 0.15f;
            indicatorLeft.setAlpha(alpha);
            indicatorRight.setAlpha(alpha);
        } else if (leftSignalOn) {
            indicatorLeft.setAlpha(blinkVisible ? 1f : 0.15f);
            indicatorRight.setAlpha(0.15f);
        } else if (rightSignalOn) {
            indicatorLeft.setAlpha(0.15f);
            indicatorRight.setAlpha(blinkVisible ? 1f : 0.15f);
        } else {
            indicatorLeft.setAlpha(0.2f);
            indicatorRight.setAlpha(0.2f);
        }
    }

    private void updateSignalButtonTint() {
        // Left signal button
        if (leftSignalOn || hazardOn) {
            btnLeftSignal.setColorFilter(
                    ContextCompat.getColor(this, R.color.light_on_amber));
            btnLeftSignal.setAlpha(1f);
        } else {
            btnLeftSignal.clearColorFilter();
            btnLeftSignal.setAlpha(0.4f);
        }

        // Right signal button
        if (rightSignalOn || hazardOn) {
            btnRightSignal.setColorFilter(
                    ContextCompat.getColor(this, R.color.light_on_amber));
            btnRightSignal.setAlpha(1f);
        } else {
            btnRightSignal.clearColorFilter();
            btnRightSignal.setAlpha(0.4f);
        }

        // Hazard button
        if (hazardOn) {
            btnHazard.setAlpha(1f);
        } else {
            btnHazard.setAlpha(0.4f);
        }
    }

    // =========================================================================
    // Bluetooth connection
    // =========================================================================

    private void connectBluetooth() {
        if (bluetoothAdapter == null) {
            toast("Bluetooth not supported on this device.");
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            toast("Please enable Bluetooth first.");
            return;
        }

        // Request BLUETOOTH_CONNECT permission on Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.BLUETOOTH_CONNECT,
                                     Manifest.permission.BLUETOOTH_SCAN},
                        PERMISSION_REQUEST_CODE);
                return;
            }
        }

        tvStatus.setText("● Connecting…");
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_blue));

        new Thread(() -> {
            try {
                BluetoothDevice device = findPairedDevice(DEVICE_NAME);
                if (device == null) {
                    runOnUiThread(() -> toast(
                            "Device \"" + DEVICE_NAME + "\" not found.\n" +
                            "Pair it in Bluetooth settings first."));
                    runOnUiThread(this::setDisconnectedState);
                    return;
                }

                bluetoothAdapter.cancelDiscovery();

                BluetoothSocket socket =
                        device.createRfcommSocketToServiceRecord(SPP_UUID);
                socket.connect();

                bluetoothSocket = socket;
                outputStream    = socket.getOutputStream();

                runOnUiThread(() -> {
                    tvStatus.setText("● Connected");
                    tvStatus.setTextColor(
                            ContextCompat.getColor(this, R.color.status_connected));
                    btnConnect.setText("DISCONNECT");
                    btnConnect.setOnClickListener(v -> {
                        disconnect();
                        setDisconnectedState();
                    });
                    toast("Connected to " + DEVICE_NAME);
                });

            } catch (IOException | SecurityException e) {
                runOnUiThread(() -> {
                    toast("Connection failed: " + e.getMessage());
                    setDisconnectedState();
                });
            }
        }).start();
    }

    /** Find a bonded device by name. */
    private BluetoothDevice findPairedDevice(String name) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                return null;
            }
        }
        Set<BluetoothDevice> paired = bluetoothAdapter.getBondedDevices();
        for (BluetoothDevice d : paired) {
            if (name.equals(d.getName())) return d;
        }
        return null;
    }

    private void disconnect() {
        try {
            if (outputStream    != null) { outputStream.close();    outputStream    = null; }
            if (bluetoothSocket != null) { bluetoothSocket.close(); bluetoothSocket = null; }
        } catch (IOException ignored) {}
    }

    // =========================================================================
    // Command sending
    // =========================================================================

    /** Sends a single-character command to the ESP32 via Bluetooth. */
    private void sendCommand(String command) {
        if (outputStream == null) {
            toast("Not connected.");
            return;
        }
        new Thread(() -> {
            try {
                outputStream.write(command.getBytes());
            } catch (IOException e) {
                runOnUiThread(() -> {
                    toast("Send failed — connection lost.");
                    setDisconnectedState();
                });
            }
        }).start();
    }

    // =========================================================================
    // UI helpers
    // =========================================================================

    private void setDisconnectedState() {
        tvStatus.setText("○ Disconnected");
        tvStatus.setTextColor(
                ContextCompat.getColor(this, R.color.status_disconnected));
        btnConnect.setText("CONNECT");
        btnConnect.setOnClickListener(v -> connectBluetooth());
        setDriveState(SpeedometerView.STATE_IDLE, "IDLE");
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // =========================================================================
    // Permission result
    // =========================================================================

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                connectBluetooth();
            } else {
                toast("Bluetooth permission denied.");
            }
        }
    }
}
