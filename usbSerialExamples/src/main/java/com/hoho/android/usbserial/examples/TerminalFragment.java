package com.hoho.android.usbserial.examples;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.util.Arrays;

public class TerminalFragment extends Fragment implements SerialInputOutputManager.Listener {

    private enum UsbPermission { Unknown, Requested, Granted, Denied }
    private static final String INTENT_ACTION_GRANT_USB = BuildConfig.APPLICATION_ID + ".GRANT_USB";
    private static final int WRITE_WAIT_MILLIS = 2000;
    private static final int READ_WAIT_MILLIS  = 2000;

    // USB/Serial config
    private int deviceId = 0;
    private int portNum  = 0;
    private int baudRate = 19200;
    private boolean withIoManager = true;

    // "Mode" controls which page or set of buttons are shown
    // e.g. "main", "location", "actuator", etc.
    private String mode = "main";

    // Keep track of permission & connection
    private UsbSerialPort usbSerialPort;
    private UsbPermission usbPermission = UsbPermission.Unknown;
    private SerialInputOutputManager usbIoManager;
    private boolean connected = false;

    // For UI updates
    private final Handler mainLooper;
    private TextView receiveText;

    private final BroadcastReceiver broadcastReceiver;

    public TerminalFragment() {
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if(INTENT_ACTION_GRANT_USB.equals(intent.getAction())) {
                    usbPermission = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                            ? UsbPermission.Granted : UsbPermission.Denied;
                    connect();
                }
            }
        };
        mainLooper = new Handler(Looper.getMainLooper());
    }

    /* ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     * Fragment Lifecycle
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~ */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(false);   // No menu
        setRetainInstance(true);    // Retain across config changes

        // Grab arguments (device, port, baud, mode, etc.)
        if (getArguments() != null) {
            deviceId     = getArguments().getInt("device", 0);
            portNum      = getArguments().getInt("port", 0);
            baudRate     = getArguments().getInt("baud", 19200);
            withIoManager = getArguments().getBoolean("withIoManager", true);
            mode         = getArguments().getString("mode", "main");
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        ContextCompat.registerReceiver(
                getActivity(),
                broadcastReceiver,
                new IntentFilter(INTENT_ACTION_GRANT_USB),
                ContextCompat.RECEIVER_NOT_EXPORTED
        );
    }

    @Override
    public void onStop() {
        getActivity().unregisterReceiver(broadcastReceiver);
        super.onStop();
    }

    @Override
    public void onResume() {
        super.onResume();
        // If not connected yet, and permission is not denied, try connecting
        if(!connected && (usbPermission == UsbPermission.Unknown || usbPermission == UsbPermission.Granted)) {
            mainLooper.post(this::connect);
        }
    }

    @Override
    public void onPause() {
        if(connected) {
            status("disconnected");
            disconnect();
        }
        super.onPause();
    }

    /* ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     * Inflate UI (Single Layout for all modes)
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~ */
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            ViewGroup container,
            Bundle savedInstanceState
    ) {
        // We use the same layout each time
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);

        // 1) Basic UI references
        receiveText = view.findViewById(R.id.receive_text);
        receiveText.setTextColor(getResources().getColor(R.color.colorRecieveText));
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());

        EditText sendText    = view.findViewById(R.id.send_text);
        ImageButton sendBtn  = view.findViewById(R.id.send_btn);
        Button receiveBtn    = view.findViewById(R.id.receive_btn);

        // 2) Sub-page or “mode” buttons
        Button buttonGoLocation = view.findViewById(R.id.button_go_location);
        Button buttonGoActuator = view.findViewById(R.id.button_go_actuator);
        Button buttonGoLoadCell = view.findViewById(R.id.button_go_loadcell);
        Button buttonGoLinear  = view.findViewById(R.id.button_go_linear_pot);

        // 3) If we have I/O Manager, we read automatically, so we can hide the manual read button
        Button buttonLocationOn  = view.findViewById(R.id.button_location_on);
        Button buttonLocationOff = view.findViewById(R.id.button_location_off);

        Button buttonActOn  = view.findViewById(R.id.button_actuator_on);
        Button buttonActOff = view.findViewById(R.id.button_actuator_off);

        Button buttonLoadOn  = view.findViewById(R.id.button_loadcell_on);
        Button buttonLoadOff = view.findViewById(R.id.button_loadcell_off);

        Button buttonLinOn  = view.findViewById(R.id.button_linearpot_on);
        Button buttonLinOff = view.findViewById(R.id.button_linearpot_off);

        // 4) The “send” button to write text from the EditText
        sendBtn.setOnClickListener(v -> {
            String str = sendText.getText().toString().trim();
            send(str);
        });

        // 5) Show/hide each group of buttons depending on the “mode”
        switch (mode) {
            case "location":
                //  Hide main "go to" buttons
                buttonGoLocation.setVisibility(View.GONE);
                buttonGoActuator.setVisibility(View.GONE);
                buttonGoLoadCell.setVisibility(View.GONE);
                buttonGoLinear.setVisibility(View.GONE);

                // Show location ON/OFF
                buttonLocationOn.setVisibility(View.VISIBLE);
                buttonLocationOff.setVisibility(View.VISIBLE);

                // Hide other pages' ON/OFF
                buttonActOn.setVisibility(View.GONE);
                buttonActOff.setVisibility(View.GONE);
                buttonLoadOn.setVisibility(View.GONE);
                buttonLoadOff.setVisibility(View.GONE);
                buttonLinOn.setVisibility(View.GONE);
                buttonLinOff.setVisibility(View.GONE);

                // Attach click listeners
                buttonLocationOn.setOnClickListener(v -> {
                    // do location ON logic
                    send("LOCATION_ON");
                });
                buttonLocationOff.setOnClickListener(v -> {
                    // do location OFF logic
                    send("LOCATION_OFF");
                });
                break;

            case "actuator":
                // Hide main "go" buttons
                buttonGoLocation.setVisibility(View.GONE);
                buttonGoActuator.setVisibility(View.GONE);
                buttonGoLoadCell.setVisibility(View.GONE);
                buttonGoLinear.setVisibility(View.GONE);

                // Show only actuator ON/OFF
                buttonActOn.setVisibility(View.VISIBLE);
                buttonActOff.setVisibility(View.VISIBLE);

                // Hide location, loadCell, linearPot
                buttonLocationOn.setVisibility(View.GONE);
                buttonLocationOff.setVisibility(View.GONE);
                buttonLoadOn.setVisibility(View.GONE);
                buttonLoadOff.setVisibility(View.GONE);
                buttonLinOn.setVisibility(View.GONE);
                buttonLinOff.setVisibility(View.GONE);

                // Attach click listeners
                buttonActOn.setOnClickListener(v -> {
                    // Actuator ON
                    send("ACT_ON");
                });
                buttonActOff.setOnClickListener(v -> {
                    // Actuator OFF
                    send("ACT_OFF");
                });
                break;

            case "loadcell":
                // Hide main "go" buttons
                buttonGoLocation.setVisibility(View.GONE);
                buttonGoActuator.setVisibility(View.GONE);
                buttonGoLoadCell.setVisibility(View.GONE);
                buttonGoLinear.setVisibility(View.GONE);

                // Show loadCell ON/OFF
                buttonLoadOn.setVisibility(View.VISIBLE);
                buttonLoadOff.setVisibility(View.VISIBLE);

                // Hide other pages
                buttonLocationOn.setVisibility(View.GONE);
                buttonLocationOff.setVisibility(View.GONE);
                buttonActOn.setVisibility(View.GONE);
                buttonActOff.setVisibility(View.GONE);
                buttonLinOn.setVisibility(View.GONE);
                buttonLinOff.setVisibility(View.GONE);

                // Clicks
                buttonLoadOn.setOnClickListener(v -> send("LOAD_ON"));
                buttonLoadOff.setOnClickListener(v -> send("LOAD_OFF"));
                break;

            case "linear":
                // Hide main "go" buttons
                buttonGoLocation.setVisibility(View.GONE);
                buttonGoActuator.setVisibility(View.GONE);
                buttonGoLoadCell.setVisibility(View.GONE);
                buttonGoLinear.setVisibility(View.GONE);

                // Show linear ON/OFF
                buttonLinOn.setVisibility(View.VISIBLE);
                buttonLinOff.setVisibility(View.VISIBLE);

                // Hide others
                buttonLocationOn.setVisibility(View.GONE);
                buttonLocationOff.setVisibility(View.GONE);
                buttonActOn.setVisibility(View.GONE);
                buttonActOff.setVisibility(View.GONE);
                buttonLoadOn.setVisibility(View.GONE);
                buttonLoadOff.setVisibility(View.GONE);

                // Clicks
                buttonLinOn.setOnClickListener(v -> send("LIN_ON"));
                buttonLinOff.setOnClickListener(v -> send("LIN_OFF"));
                break;

            case "main":
            default:
                // MAIN => show the 4 "go" buttons, hide all ON/OFF
                buttonGoLocation.setVisibility(View.VISIBLE);
                buttonGoActuator.setVisibility(View.VISIBLE);
                buttonGoLoadCell.setVisibility(View.VISIBLE);
                buttonGoLinear.setVisibility(View.VISIBLE);

                // Hide the sub-page ON/OFF
                buttonLocationOn.setVisibility(View.GONE);
                buttonLocationOff.setVisibility(View.GONE);
                buttonActOn.setVisibility(View.GONE);
                buttonActOff.setVisibility(View.GONE);
                buttonLoadOn.setVisibility(View.GONE);
                buttonLoadOff.setVisibility(View.GONE);
                buttonLinOn.setVisibility(View.GONE);
                buttonLinOff.setVisibility(View.GONE);

                // "Go to LOCATION page"
                buttonGoLocation.setOnClickListener(v -> {
                    goToSubPage("location");
                });
                // "Go to ACTUATOR page"
                buttonGoActuator.setOnClickListener(v -> {
                    goToSubPage("actuator");
                });
                // "Go to LOAD CELL page"
                buttonGoLoadCell.setOnClickListener(v -> {
                    goToSubPage("loadcell");
                });
                // "Go to LINEAR POT page"
                buttonGoLinear.setOnClickListener(v -> {
                    goToSubPage("linear");
                });
                break;
        }

        return view;
    }

    private void goToSubPage(String subMode) {
        TerminalFragment fragment = new TerminalFragment();
        Bundle args = new Bundle();
        // Pass along the same USB device config if needed
        args.putInt("device", deviceId);
        args.putInt("port", portNum);
        args.putInt("baud", baudRate);
        args.putBoolean("withIoManager", withIoManager);

        // Set the new mode
        args.putString("mode", subMode);

        fragment.setArguments(args);
        getFragmentManager().beginTransaction()
                .replace(R.id.fragment, fragment, "terminal")
                .addToBackStack(null) // so user can press back arrow
                .commit();
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        // If you had menu items, you'd inflate them here
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle menu clicks if any
        return super.onOptionsItemSelected(item);
    }

    /* ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     * SerialInputOutputManager.Listener
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~ */
    @Override
    public void onNewData(byte[] data) {
        // Called from SerialInputOutputManager’s background thread
        mainLooper.post(() -> receive(data));
    }

    @Override
    public void onRunError(Exception e) {
        mainLooper.post(() -> {
            status("connection lost: " + e.getMessage());
            disconnect();
        });
    }

    /* ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     * Connect / Disconnect
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~ */
    private void connect() {
        if(deviceId == 0) {
            // We skip actual hardware connection if device=0
            status("Skipping real USB connection (deviceId=0).");
            return;
        }

        UsbManager usbManager = (UsbManager) getActivity().getSystemService(Context.USB_SERVICE);
        UsbDevice device = null;
        for (UsbDevice v : usbManager.getDeviceList().values()) {
            if (v.getDeviceId() == deviceId) {
                device = v;
                break;
            }
        }
        if (device == null) {
            status("No matching USB device found. (deviceId=" + deviceId + ")");
            return;
        }

        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if (driver == null) {
            // Maybe it's a custom device
            driver = CustomProber.getCustomProber().probeDevice(device);
        }
        if (driver == null) {
            status("connection failed: no driver for device");
            return;
        }
        if (driver.getPorts().size() < portNum) {
            status("connection failed: not enough ports at device");
            return;
        }

        usbSerialPort = driver.getPorts().get(portNum);
        UsbDeviceConnection usbConnection = usbManager.openDevice(driver.getDevice());
        if (usbConnection == null && usbPermission == UsbPermission.Unknown && !usbManager.hasPermission(device)) {
            usbPermission = UsbPermission.Requested;
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_MUTABLE : 0;
            Intent intent = new Intent(INTENT_ACTION_GRANT_USB);
            intent.setPackage(getActivity().getPackageName());
            PendingIntent usbPermissionIntent = PendingIntent.getBroadcast(getActivity(), 0, intent, flags);
            usbManager.requestPermission(driver.getDevice(), usbPermissionIntent);
            return;
        }
        if (usbConnection == null) {
            if (!usbManager.hasPermission(device)) {
                status("connection failed: permission denied");
            } else {
                status("connection failed: open failed");
            }
            return;
        }

        try {
            usbSerialPort.open(usbConnection);
            try {
                usbSerialPort.setParameters(baudRate, 8, 1, UsbSerialPort.PARITY_NONE);
            } catch (UnsupportedOperationException e) {
                status("unsupported setParameters()");
            }
            if (withIoManager) {
                usbIoManager = new SerialInputOutputManager(usbSerialPort, this);
                usbIoManager.start();
            }
            status("connected");
            connected = true;
        } catch (Exception e) {
            status("connection failed: " + e.getMessage());
            disconnect();
        }
    }

    private void disconnect() {
        connected = false;
        if (usbIoManager != null) {
            usbIoManager.setListener(null);
            usbIoManager.stop();
        }
        usbIoManager = null;
        try {
            if (usbSerialPort != null) {
                usbSerialPort.close();
            }
        } catch (IOException ignored) {}
        usbSerialPort = null;
    }

    /* ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     * Send / Read / Receive
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~ */
    private void send(String str) {
        if(!connected) {
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            byte[] data = (str + "\n").getBytes();

            // Show in the text area that we’re sending
            SpannableStringBuilder spn = new SpannableStringBuilder();
            spn.append("Sending: ").append(str).append("\n");
            spn.setSpan(
                    new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)),
                    0, spn.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            );
            receiveText.append(spn);

            // Write data to USB serial
            usbSerialPort.write(data, WRITE_WAIT_MILLIS);
        } catch (Exception e) {
            onRunError(e);
        }
    }

    private void read() {
        if(!connected) {
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            byte[] buffer = new byte[8192];
            int len = usbSerialPort.read(buffer, READ_WAIT_MILLIS);
            receive(Arrays.copyOf(buffer, len));
        } catch (IOException e) {
            status("connection lost: " + e.getMessage());
            disconnect();
        }
    }

    private void receive(byte[] data) {
        if(data.length > 0) {
            String receivedText = new String(data);
            receiveText.append(receivedText);
        }
    }

    /* ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     * Helper: status messages
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~ */
    void status(String str) {
        SpannableStringBuilder spn = new SpannableStringBuilder(str + "\n");
        spn.setSpan(
                new ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)),
                0, spn.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        );
        receiveText.append(spn);
    }
}
