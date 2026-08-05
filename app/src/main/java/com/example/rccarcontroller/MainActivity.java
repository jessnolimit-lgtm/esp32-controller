package com.example.rccarcontroller;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
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
 * MainActivity — sole screen of the RC Car Controller app.
 *
 * Flow:
 *  1. User taps "Connect Bluetooth".
 *  2. App looks for a paired device named "ESP32_RC_CAR".
 *  3. Opens an RFCOMM (SPP) socket to that device.
 *  4. Direction buttons send single-character commands over the socket.
 */
public class MainActivity extends AppCompatActivity {

    // Standard SPP UUID used by Bluetooth Classic serial connections
    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private static final String DEVICE_NAME = "ESP32_RC_CAR";
    private static final int PERMISSION_REQUEST_CODE = 1;

    // Bluetooth objects
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket bluetoothSocket;
    private OutputStream outputStream;

    // UI
    private Button btnConnect;
    private TextView tvStatus;

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        // Bind views
        btnConnect   = findViewById(R.id.btnConnect);
        tvStatus     = findViewById(R.id.tvStatus);

        // Connect button
        btnConnect.setOnClickListener(v -> connectBluetooth());

        // Direction buttons — each sends its single-character command
        findViewById(R.id.btnForward).setOnClickListener(v  -> sendCommand("F"));
        findViewById(R.id.btnBackward).setOnClickListener(v -> sendCommand("B"));
        findViewById(R.id.btnLeft).setOnClickListener(v    -> sendCommand("L"));
        findViewById(R.id.btnRight).setOnClickListener(v   -> sendCommand("R"));
        findViewById(R.id.btnStop).setOnClickListener(v    -> sendCommand("S"));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnect();
    }

    // -------------------------------------------------------------------------
    // Bluetooth connection
    // -------------------------------------------------------------------------

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

        // Run connection on a background thread — never block the UI thread
        new Thread(() -> {
            try {
                BluetoothDevice device = findPairedDevice(DEVICE_NAME);
                if (device == null) {
                    runOnUiThread(() -> toast(
                            "Device \"" + DEVICE_NAME + "\" not found.\n" +
                            "Pair it in Bluetooth settings first."));
                    return;
                }

                // Cancel any ongoing discovery to speed up connection
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
                    btnConnect.setText("Disconnect");
                    btnConnect.setOnClickListener(v -> {
                        disconnect();
                        resetConnectButton();
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

        tvStatus.setText("Connecting…");
    }

    /** Finds a device by name from the paired (bonded) devices list. */
    private BluetoothDevice findPairedDevice(String name) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                return null;
            }
        }
        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        for (BluetoothDevice d : pairedDevices) {
            if (name.equals(d.getName())) return d;
        }
        return null;
    }

    private void disconnect() {
        try {
            if (outputStream != null) { outputStream.close(); outputStream = null; }
            if (bluetoothSocket != null) { bluetoothSocket.close(); bluetoothSocket = null; }
        } catch (IOException ignored) {}
    }

    // -------------------------------------------------------------------------
    // Command sending
    // -------------------------------------------------------------------------

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
                    toast("Send failed. Connection lost.");
                    setDisconnectedState();
                });
            }
        }).start();
    }

    // -------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------

    private void setDisconnectedState() {
        disconnect();
        resetConnectButton();
        tvStatus.setText("○ Disconnected");
        tvStatus.setTextColor(
                ContextCompat.getColor(this, R.color.status_disconnected));
    }

    private void resetConnectButton() {
        btnConnect.setText("Connect Bluetooth");
        btnConnect.setOnClickListener(v -> connectBluetooth());
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // -------------------------------------------------------------------------
    // Permission result
    // -------------------------------------------------------------------------

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                connectBluetooth(); // retry after permission granted
            } else {
                toast("Bluetooth permission denied.");
            }
        }
    }
}
