package com.hoho.android.usbserial.examples;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
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
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.github.mikephil.charting.components.Description;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.HexDump;
import com.hoho.android.usbserial.util.SerialInputOutputManager;
import com.github.mikephil.charting.charts.LineChart;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TerminalFragment extends Fragment implements SerialInputOutputManager.Listener {

    private enum UsbPermission { Unknown, Requested, Granted, Denied }
    private static final String INTENT_ACTION_GRANT_USB = BuildConfig.APPLICATION_ID + ".GRANT_USB";
    private static final int WRITE_WAIT_MILLIS = 2000;
    private static final int READ_WAIT_MILLIS  = 2000;

    // USB/Serial configuration and mode
    private int deviceId = 0;
    private int portNum  = 0;
    private int baudRate = 19200;
    private boolean withIoManager = true;
    // Custom mode used to show different button groups (e.g., "location", "actuator", etc.)
    private String mode = "main";

    private LineChart lineChart;
    private List<String> xValues;
    private List<String> yValues;
    private List<String> zValues;

    // USB connection members
    private UsbSerialPort usbSerialPort;
    private UsbPermission usbPermission = UsbPermission.Unknown;
    private SerialInputOutputManager usbIoManager;
    private boolean connected = false;

    // UI and control-line helper
    private final Handler mainLooper;
    private TextView receiveText;
    private ControlLines controlLines;

    private final BroadcastReceiver broadcastReceiver;

    public TerminalFragment() {
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (INTENT_ACTION_GRANT_USB.equals(intent.getAction())) {
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
        // Enable the options menu if needed
        setHasOptionsMenu(false);
        // Retain instance for configuration changes
        setRetainInstance(true);

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
        if (!connected && (usbPermission == UsbPermission.Unknown || usbPermission == UsbPermission.Granted)) {
            mainLooper.post(this::connect);
        }
    }

    @Override
    public void onPause() {
        if (connected) {
            status("disconnected");
            disconnect();
        }
        super.onPause();
    }

    /* ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     * Inflate UI and set up custom buttons
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~ */
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            ViewGroup container,
            Bundle savedInstanceState
    ) {
        // Inflate the common layout
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);

        // Basic UI references
        receiveText = view.findViewById(R.id.receive_text);
        receiveText.setTextColor(getResources().getColor(R.color.colorRecieveText));
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());

        EditText sendText = view.findViewById(R.id.send_text);
        ImageButton sendBtn = view.findViewById(R.id.send_btn);
        Button receiveBtn = view.findViewById(R.id.receive_btn);

        sendBtn.setOnClickListener(v -> {
            String str = sendText.getText().toString().trim();
            send(str);
        });

        // Custom sub-page buttons for different modes
        Button buttonGoVibration = view.findViewById(R.id.button_go_vibration);
        Button buttonGoTemperature = view.findViewById(R.id.button_go_temperature);
        Button buttonGoVoltage = view.findViewById(R.id.button_go_voltage);
        Button buttonGoMagnetic = view.findViewById(R.id.button_go_magnetic);
        Button buttonGoLocation = view.findViewById(R.id.button_go_location);
        Button buttonGoActuator = view.findViewById(R.id.button_go_actuator);
        Button buttonGoLoadCell = view.findViewById(R.id.button_go_loadcell);
        Button buttonGoLinear  = view.findViewById(R.id.button_go_linear_pot);
        Button buttonGoRotary = view.findViewById(R.id.button_go_rotary);

        Button buttonLocationOn  = view.findViewById(R.id.button_location_on);
        Button buttonLocationOff = view.findViewById(R.id.button_location_off);
        Button buttonActOn  = view.findViewById(R.id.button_actuator_on);
        Button buttonActOff = view.findViewById(R.id.button_actuator_off);
        Button buttonLoadOn  = view.findViewById(R.id.button_loadcell_on);
        Button buttonLoadOff = view.findViewById(R.id.button_loadcell_off);
        Button buttonLinOn  = view.findViewById(R.id.button_linearpot_on);
        Button buttonLinOff = view.findViewById(R.id.button_linearpot_off);
        Button buttonRotaryOn = view.findViewById(R.id.button_rotary_on);
        Button buttonRotaryOff = view.findViewById(R.id.button_rotary_off);
        Button buttonVibrationOn = view.findViewById(R.id.button_vibration_on);
        Button buttonVibrationOff = view.findViewById(R.id.button_vibration_off);
        Button buttonTemperatureOn = view.findViewById(R.id.button_temperature_on);
        Button buttonTemperatureOff = view.findViewById(R.id.button_temperature_off);
        Button buttonVoltageOn = view.findViewById(R.id.button_voltage_on);
        Button buttonVoltageOff = view.findViewById(R.id.button_voltage_off);
        Button buttonMagneticOn = view.findViewById(R.id.button_magnetic_on);
        Button buttonMagneticOff = view.findViewById(R.id.button_magnetic_off);

        Button buttonActuator0 = view.findViewById(R.id.button_actuator_0);
        Button buttonActuator25 = view.findViewById(R.id.button_actuator_25);
        Button buttonActuator50 = view.findViewById(R.id.button_actuator_50);
        Button buttonActuator75 = view.findViewById(R.id.button_actuator_75);
        Button buttonActuator100 = view.findViewById(R.id.button_actuator_100);


        LinearLayout sendContainer = view.findViewById(R.id.send_container);

        lineChart = view.findViewById(R.id.chart);
        Description description = new Description();
        description.setText("Degree");
        description.setPosition(150f, 15f);
        lineChart.setDescription(description);
        lineChart.getAxisRight().setDrawLabels(false);

        xValues = Arrays.asList("Altan", "mEHME", "bBABA");

        XAxis xAxis = lineChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setValueFormatter(new IndexAxisValueFormatter(xValues));
        xAxis.setLabelCount(4);
        xAxis.setGranularity(1f);

        YAxis yAxis = lineChart.getAxisLeft();
        yAxis.setAxisMinimum(0f);
        yAxis.setAxisMaximum(360f);
        yAxis.setAxisLineWidth(2f);
        yAxis.setAxisLineColor(Color.BLACK);
        yAxis.setLabelCount(10);


        // Show/hide groups depending on mode (if mode is "main", show go buttons; otherwise show the respective ON/OFF buttons)
        switch (mode) {
            case "magnetic":
                // Hide all go buttons
                buttonGoLocation.setVisibility(View.GONE);
                buttonGoActuator.setVisibility(View.GONE);
                buttonGoLoadCell.setVisibility(View.GONE);
                buttonGoLinear.setVisibility(View.GONE);
                buttonGoRotary.setVisibility(View.GONE);

                buttonGoTemperature.setVisibility(View.GONE);
                buttonGoMagnetic.setVisibility(View.GONE);
                buttonGoVoltage.setVisibility(View.GONE);
                buttonGoVibration.setVisibility(View.GONE);

                // Hide on/off buttons for other sensors
                buttonActuator0.setVisibility(View.GONE);
                buttonActuator25.setVisibility(View.GONE);
                buttonActuator50.setVisibility(View.GONE);
                buttonActuator75.setVisibility(View.GONE);
                buttonActuator100.setVisibility(View.GONE);


                buttonLocationOn.setVisibility(View.GONE);
                buttonLocationOff.setVisibility(View.GONE);
                buttonActOn.setVisibility(View.GONE);
                buttonActOff.setVisibility(View.GONE);
                buttonLoadOn.setVisibility(View.GONE);
                buttonLoadOff.setVisibility(View.GONE);
                buttonLinOn.setVisibility(View.GONE);
                buttonLinOff.setVisibility(View.GONE);
                buttonRotaryOn.setVisibility(View.GONE);
                buttonRotaryOff.setVisibility(View.GONE);
                buttonVibrationOn.setVisibility(View.GONE);
                buttonVibrationOff.setVisibility(View.GONE);
                buttonTemperatureOn.setVisibility(View.GONE);
                buttonTemperatureOff.setVisibility(View.GONE);
                buttonVoltageOn.setVisibility(View.GONE);
                buttonVoltageOff.setVisibility(View.GONE);

                // Show magnetic on/off buttons
                buttonMagneticOn.setVisibility(View.VISIBLE);
                buttonMagneticOff.setVisibility(View.VISIBLE);

                lineChart.setVisibility(View.GONE);

                buttonMagneticOn.setOnClickListener(v -> send("AS5600_ON"));
                buttonMagneticOff.setOnClickListener(v -> send("AS5600_OFF"));
                break;

            case "vibration":
                // Hide all go buttons
                buttonGoLocation.setVisibility(View.GONE);
                buttonGoActuator.setVisibility(View.GONE);
                buttonGoLoadCell.setVisibility(View.GONE);
                buttonGoLinear.setVisibility(View.GONE);
                buttonGoRotary.setVisibility(View.GONE);

                buttonGoTemperature.setVisibility(View.GONE);
                buttonGoMagnetic.setVisibility(View.GONE);
                buttonGoVoltage.setVisibility(View.GONE);
                buttonGoVibration.setVisibility(View.GONE);

                // Hide on/off buttons for other sensors
                buttonActuator0.setVisibility(View.GONE);
                buttonActuator25.setVisibility(View.GONE);
                buttonActuator50.setVisibility(View.GONE);
                buttonActuator75.setVisibility(View.GONE);
                buttonActuator100.setVisibility(View.GONE);
                buttonLocationOn.setVisibility(View.GONE);
                buttonLocationOff.setVisibility(View.GONE);
                buttonActOn.setVisibility(View.GONE);
                buttonActOff.setVisibility(View.GONE);
                buttonLoadOn.setVisibility(View.GONE);
                buttonLoadOff.setVisibility(View.GONE);
                buttonLinOn.setVisibility(View.GONE);
                buttonLinOff.setVisibility(View.GONE);
                buttonRotaryOn.setVisibility(View.GONE);
                buttonRotaryOff.setVisibility(View.GONE);
                buttonMagneticOn.setVisibility(View.GONE);
                buttonMagneticOff.setVisibility(View.GONE);
                buttonTemperatureOn.setVisibility(View.GONE);
                buttonTemperatureOff.setVisibility(View.GONE);
                buttonVoltageOn.setVisibility(View.GONE);
                buttonVoltageOff.setVisibility(View.GONE);

                // Show vibration on/off buttons
                buttonVibrationOn.setVisibility(View.VISIBLE);
                buttonVibrationOff.setVisibility(View.VISIBLE);

                lineChart.setVisibility(View.GONE);

                buttonVibrationOn.setOnClickListener(v -> send("VIBRATION_ON"));
                buttonVibrationOff.setOnClickListener(v -> send("VIBRATION_OFF"));
                break;

            case "voltage":
                // Hide all go buttons
                buttonGoLocation.setVisibility(View.GONE);
                buttonGoActuator.setVisibility(View.GONE);
                buttonGoLoadCell.setVisibility(View.GONE);
                buttonGoLinear.setVisibility(View.GONE);
                buttonGoRotary.setVisibility(View.GONE);

                buttonGoTemperature.setVisibility(View.GONE);
                buttonGoMagnetic.setVisibility(View.GONE);
                buttonGoVoltage.setVisibility(View.GONE);
                buttonGoVibration.setVisibility(View.GONE);

                // Hide on/off buttons for other sensors

                buttonActuator0.setVisibility(View.GONE);
                buttonActuator25.setVisibility(View.GONE);
                buttonActuator50.setVisibility(View.GONE);
                buttonActuator75.setVisibility(View.GONE);
                buttonActuator100.setVisibility(View.GONE);

                buttonLocationOn.setVisibility(View.GONE);
                buttonLocationOff.setVisibility(View.GONE);
                buttonActOn.setVisibility(View.GONE);
                buttonActOff.setVisibility(View.GONE);
                buttonLoadOn.setVisibility(View.GONE);
                buttonLoadOff.setVisibility(View.GONE);
                buttonLinOn.setVisibility(View.GONE);
                buttonLinOff.setVisibility(View.GONE);
                buttonRotaryOn.setVisibility(View.GONE);
                buttonRotaryOff.setVisibility(View.GONE);
                buttonMagneticOn.setVisibility(View.GONE);
                buttonMagneticOff.setVisibility(View.GONE);
                buttonVibrationOn.setVisibility(View.GONE);
                buttonVibrationOff.setVisibility(View.GONE);
                buttonTemperatureOn.setVisibility(View.GONE);
                buttonTemperatureOff.setVisibility(View.GONE);

                // Show voltage on/off buttons
                buttonVoltageOn.setVisibility(View.VISIBLE);
                buttonVoltageOff.setVisibility(View.VISIBLE);

                lineChart.setVisibility(View.GONE);

                buttonVoltageOn.setOnClickListener(v -> send("VOLT_ON"));
                buttonVoltageOff.setOnClickListener(v -> send("VOLT_OFF"));
                break;

            case "temperature":
                // Hide all go buttons
                buttonGoLocation.setVisibility(View.GONE);
                buttonGoActuator.setVisibility(View.GONE);
                buttonGoLoadCell.setVisibility(View.GONE);
                buttonGoLinear.setVisibility(View.GONE);
                buttonGoRotary.setVisibility(View.GONE);

                buttonGoTemperature.setVisibility(View.GONE);
                buttonGoMagnetic.setVisibility(View.GONE);
                buttonGoVoltage.setVisibility(View.GONE);
                buttonGoVibration.setVisibility(View.GONE);

                // Hide on/off buttons for other sensors

                buttonActuator0.setVisibility(View.GONE);
                buttonActuator25.setVisibility(View.GONE);
                buttonActuator50.setVisibility(View.GONE);
                buttonActuator75.setVisibility(View.GONE);
                buttonActuator100.setVisibility(View.GONE);


                buttonLocationOn.setVisibility(View.GONE);
                buttonLocationOff.setVisibility(View.GONE);
                buttonActOn.setVisibility(View.GONE);
                buttonActOff.setVisibility(View.GONE);
                buttonLoadOn.setVisibility(View.GONE);
                buttonLoadOff.setVisibility(View.GONE);
                buttonLinOn.setVisibility(View.GONE);
                buttonLinOff.setVisibility(View.GONE);
                buttonRotaryOn.setVisibility(View.GONE);
                buttonRotaryOff.setVisibility(View.GONE);
                buttonMagneticOn.setVisibility(View.GONE);
                buttonMagneticOff.setVisibility(View.GONE);
                buttonVibrationOn.setVisibility(View.GONE);
                buttonVibrationOff.setVisibility(View.GONE);
                buttonVoltageOn.setVisibility(View.GONE);
                buttonVoltageOff.setVisibility(View.GONE);

                // Show temperature on/off buttons
                buttonTemperatureOn.setVisibility(View.VISIBLE);
                buttonTemperatureOff.setVisibility(View.VISIBLE);

                lineChart.setVisibility(View.GONE);

                buttonTemperatureOn.setOnClickListener(v -> send("TEMP_ON"));
                buttonTemperatureOff.setOnClickListener(v -> send("TEMP_OFF"));
                break;

            case "location":
                // Hide all go buttons except the ones not needed
                buttonGoLocation.setVisibility(View.GONE);
                buttonGoActuator.setVisibility(View.GONE);
                buttonGoLoadCell.setVisibility(View.GONE);
                buttonGoLinear.setVisibility(View.GONE);
                buttonGoRotary.setVisibility(View.GONE);

                buttonGoTemperature.setVisibility(View.GONE);
                buttonGoMagnetic.setVisibility(View.GONE);
                buttonGoVoltage.setVisibility(View.GONE);
                buttonGoVibration.setVisibility(View.GONE);

                // Hide on/off buttons for other sensors

                buttonActuator0.setVisibility(View.GONE);
                buttonActuator25.setVisibility(View.GONE);
                buttonActuator50.setVisibility(View.GONE);
                buttonActuator75.setVisibility(View.GONE);
                buttonActuator100.setVisibility(View.GONE);
                buttonActOn.setVisibility(View.GONE);
                buttonActOff.setVisibility(View.GONE);
                buttonLoadOn.setVisibility(View.GONE);
                buttonLoadOff.setVisibility(View.GONE);
                buttonLinOn.setVisibility(View.GONE);
                buttonLinOff.setVisibility(View.GONE);
                buttonRotaryOn.setVisibility(View.GONE);
                buttonRotaryOff.setVisibility(View.GONE);
                buttonMagneticOn.setVisibility(View.GONE);
                buttonMagneticOff.setVisibility(View.GONE);
                buttonVibrationOn.setVisibility(View.GONE);
                buttonVibrationOff.setVisibility(View.GONE);
                buttonTemperatureOn.setVisibility(View.GONE);
                buttonTemperatureOff.setVisibility(View.GONE);
                buttonVoltageOn.setVisibility(View.GONE);
                buttonVoltageOff.setVisibility(View.GONE);

                // Show location on/off buttons
                buttonLocationOn.setVisibility(View.VISIBLE);
                buttonLocationOff.setVisibility(View.VISIBLE);

                lineChart.setVisibility(View.VISIBLE);

                buttonLocationOn.setOnClickListener(v -> send("LOCATION_ON"));
                buttonLocationOff.setOnClickListener(v -> send("LOCATION_OFF"));
                break;

            case "actuator":
                buttonGoLocation.setVisibility(View.GONE);
                buttonGoActuator.setVisibility(View.GONE);
                buttonGoLoadCell.setVisibility(View.GONE);
                buttonGoLinear.setVisibility(View.GONE);
                buttonGoRotary.setVisibility(View.GONE);

                buttonGoTemperature.setVisibility(View.GONE);
                buttonGoMagnetic.setVisibility(View.GONE);
                buttonGoVoltage.setVisibility(View.GONE);
                buttonGoVibration.setVisibility(View.GONE);

                // Hide on/off buttons for other sensors
                buttonLocationOn.setVisibility(View.GONE);
                buttonLocationOff.setVisibility(View.GONE);
                buttonLoadOn.setVisibility(View.GONE);
                buttonLoadOff.setVisibility(View.GONE);
                buttonLinOn.setVisibility(View.GONE);
                buttonLinOff.setVisibility(View.GONE);
                buttonRotaryOn.setVisibility(View.GONE);
                buttonRotaryOff.setVisibility(View.GONE);
                buttonMagneticOn.setVisibility(View.GONE);
                buttonMagneticOff.setVisibility(View.GONE);
                buttonVibrationOn.setVisibility(View.GONE);
                buttonVibrationOff.setVisibility(View.GONE);
                buttonTemperatureOn.setVisibility(View.GONE);
                buttonTemperatureOff.setVisibility(View.GONE);
                buttonVoltageOn.setVisibility(View.GONE);
                buttonVoltageOff.setVisibility(View.GONE);

                // Show actuator on/off buttons
                buttonActOn.setVisibility(View.VISIBLE);
                buttonActOff.setVisibility(View.VISIBLE);

                lineChart.setVisibility(View.GONE);

                buttonActOn.setOnClickListener(v -> send("ACT_ON"));
                buttonActOff.setOnClickListener(v -> send("ACT_OFF"));
                buttonActuator0.setOnClickListener(v -> send(("ACT_0")));
                buttonActuator25.setOnClickListener(v -> send(("ACT_25")));
                buttonActuator50.setOnClickListener(v -> send(("ACT_50")));
                buttonActuator75.setOnClickListener(v -> send(("ACT_75")));
                buttonActuator100.setOnClickListener(v -> send(("ACT_100")));
                break;

            case "loadcell":
                buttonGoLocation.setVisibility(View.GONE);
                buttonGoActuator.setVisibility(View.GONE);
                buttonGoLoadCell.setVisibility(View.GONE);
                buttonGoLinear.setVisibility(View.GONE);
                buttonGoRotary.setVisibility(View.GONE);

                buttonGoTemperature.setVisibility(View.GONE);
                buttonGoMagnetic.setVisibility(View.GONE);
                buttonGoVoltage.setVisibility(View.GONE);
                buttonGoVibration.setVisibility(View.GONE);

                // Hide on/off buttons for other sensors

                buttonActuator0.setVisibility(View.GONE);
                buttonActuator25.setVisibility(View.GONE);
                buttonActuator50.setVisibility(View.GONE);
                buttonActuator75.setVisibility(View.GONE);
                buttonActuator100.setVisibility(View.GONE);


                buttonLocationOn.setVisibility(View.GONE);
                buttonLocationOff.setVisibility(View.GONE);
                buttonActOn.setVisibility(View.GONE);
                buttonActOff.setVisibility(View.GONE);
                buttonLinOn.setVisibility(View.GONE);
                buttonLinOff.setVisibility(View.GONE);
                buttonRotaryOn.setVisibility(View.GONE);
                buttonRotaryOff.setVisibility(View.GONE);
                buttonMagneticOn.setVisibility(View.GONE);
                buttonMagneticOff.setVisibility(View.GONE);
                buttonVibrationOn.setVisibility(View.GONE);
                buttonVibrationOff.setVisibility(View.GONE);
                buttonTemperatureOn.setVisibility(View.GONE);
                buttonTemperatureOff.setVisibility(View.GONE);
                buttonVoltageOn.setVisibility(View.GONE);
                buttonVoltageOff.setVisibility(View.GONE);

                // Show loadcell on/off buttons
                buttonLoadOn.setVisibility(View.VISIBLE);
                buttonLoadOff.setVisibility(View.VISIBLE);

                lineChart.setVisibility(View.GONE);

                buttonLoadOn.setOnClickListener(v -> send("LOAD_ON"));
                buttonLoadOff.setOnClickListener(v -> send("LOAD_OFF"));
                break;

            case "linear":
                buttonGoLocation.setVisibility(View.GONE);
                buttonGoActuator.setVisibility(View.GONE);
                buttonGoLoadCell.setVisibility(View.GONE);
                buttonGoLinear.setVisibility(View.GONE);
                buttonGoRotary.setVisibility(View.GONE);

                buttonGoTemperature.setVisibility(View.GONE);
                buttonGoMagnetic.setVisibility(View.GONE);
                buttonGoVoltage.setVisibility(View.GONE);
                buttonGoVibration.setVisibility(View.GONE);

                // Hide on/off buttons for other sensors

                buttonActuator0.setVisibility(View.GONE);
                buttonActuator25.setVisibility(View.GONE);
                buttonActuator50.setVisibility(View.GONE);
                buttonActuator75.setVisibility(View.GONE);
                buttonActuator100.setVisibility(View.GONE);


                buttonLocationOn.setVisibility(View.GONE);
                buttonLocationOff.setVisibility(View.GONE);
                buttonActOn.setVisibility(View.GONE);
                buttonActOff.setVisibility(View.GONE);
                buttonLoadOn.setVisibility(View.GONE);
                buttonLoadOff.setVisibility(View.GONE);
                buttonRotaryOn.setVisibility(View.GONE);
                buttonRotaryOff.setVisibility(View.GONE);
                buttonMagneticOn.setVisibility(View.GONE);
                buttonMagneticOff.setVisibility(View.GONE);
                buttonVibrationOn.setVisibility(View.GONE);
                buttonVibrationOff.setVisibility(View.GONE);
                buttonTemperatureOn.setVisibility(View.GONE);
                buttonTemperatureOff.setVisibility(View.GONE);
                buttonVoltageOn.setVisibility(View.GONE);
                buttonVoltageOff.setVisibility(View.GONE);

                // Show linear on/off buttons
                buttonLinOn.setVisibility(View.VISIBLE);
                buttonLinOff.setVisibility(View.VISIBLE);

                lineChart.setVisibility(View.GONE);

                buttonLinOn.setOnClickListener(v -> send("LIN_ON"));
                buttonLinOff.setOnClickListener(v -> send("LIN_OFF"));
                break;

            case "rotary":
                buttonGoLocation.setVisibility(View.GONE);
                buttonGoActuator.setVisibility(View.GONE);
                buttonGoLoadCell.setVisibility(View.GONE);
                buttonGoLinear.setVisibility(View.GONE);
                buttonGoRotary.setVisibility(View.GONE);

                buttonGoTemperature.setVisibility(View.GONE);
                buttonGoMagnetic.setVisibility(View.GONE);
                buttonGoVoltage.setVisibility(View.GONE);
                buttonGoVibration.setVisibility(View.GONE);


                // Hide on/off buttons for other sensors

                buttonActuator0.setVisibility(View.GONE);
                buttonActuator25.setVisibility(View.GONE);
                buttonActuator50.setVisibility(View.GONE);
                buttonActuator75.setVisibility(View.GONE);
                buttonActuator100.setVisibility(View.GONE);


                buttonLocationOn.setVisibility(View.GONE);
                buttonLocationOff.setVisibility(View.GONE);
                buttonActOn.setVisibility(View.GONE);
                buttonActOff.setVisibility(View.GONE);
                buttonLoadOn.setVisibility(View.GONE);
                buttonLoadOff.setVisibility(View.GONE);
                buttonLinOn.setVisibility(View.GONE);
                buttonLinOff.setVisibility(View.GONE);
                buttonMagneticOn.setVisibility(View.GONE);
                buttonMagneticOff.setVisibility(View.GONE);
                buttonVibrationOn.setVisibility(View.GONE);
                buttonVibrationOff.setVisibility(View.GONE);
                buttonTemperatureOn.setVisibility(View.GONE);
                buttonTemperatureOff.setVisibility(View.GONE);
                buttonVoltageOn.setVisibility(View.GONE);
                buttonVoltageOff.setVisibility(View.GONE);

                // Show rotary on/off buttons
                buttonRotaryOn.setVisibility(View.VISIBLE);
                buttonRotaryOff.setVisibility(View.VISIBLE);

                lineChart.setVisibility(View.GONE);

                buttonRotaryOn.setOnClickListener(v -> send("ROT_ON"));
                buttonRotaryOff.setOnClickListener(v -> send("ROT_OFF"));
                break;

            case "main":
            default:
                // Main mode: show the go buttons (as defined by your design) and hide all on/off buttons
                buttonGoLocation.setVisibility(View.VISIBLE);
                buttonGoActuator.setVisibility(View.VISIBLE);
                buttonGoLoadCell.setVisibility(View.VISIBLE);
                buttonGoLinear.setVisibility(View.VISIBLE);
                buttonGoRotary.setVisibility(View.VISIBLE);

                // Hide all on/off buttons
                buttonActuator0.setVisibility(View.GONE);
                buttonActuator25.setVisibility(View.GONE);
                buttonActuator50.setVisibility(View.GONE);
                buttonActuator75.setVisibility(View.GONE);
                buttonActuator100.setVisibility(View.GONE);
                buttonLocationOn.setVisibility(View.GONE);
                buttonLocationOff.setVisibility(View.GONE);
                buttonActOn.setVisibility(View.GONE);
                buttonActOff.setVisibility(View.GONE);
                buttonLoadOn.setVisibility(View.GONE);
                buttonLoadOff.setVisibility(View.GONE);
                buttonLinOn.setVisibility(View.GONE);
                buttonLinOff.setVisibility(View.GONE);
                buttonRotaryOn.setVisibility(View.GONE);
                buttonRotaryOff.setVisibility(View.GONE);
                buttonMagneticOn.setVisibility(View.GONE);
                buttonMagneticOff.setVisibility(View.GONE);
                buttonVibrationOn.setVisibility(View.GONE);
                buttonVibrationOff.setVisibility(View.GONE);
                buttonTemperatureOn.setVisibility(View.GONE);
                buttonTemperatureOff.setVisibility(View.GONE);
                buttonVoltageOn.setVisibility(View.GONE);
                buttonVoltageOff.setVisibility(View.GONE);

                sendContainer.setVisibility(View.GONE);
                lineChart.setVisibility(View.GONE);

                buttonGoLocation.setOnClickListener(v -> goToSubPage("location"));
                buttonGoActuator.setOnClickListener(v -> goToSubPage("actuator"));
                buttonGoLoadCell.setOnClickListener(v -> goToSubPage("loadcell"));
                buttonGoLinear.setOnClickListener(v -> goToSubPage("linear"));
                buttonGoRotary.setOnClickListener(v -> goToSubPage("rotary"));
                buttonGoVibration.setOnClickListener(v -> goToSubPage("vibration"));
                buttonGoTemperature.setOnClickListener(v -> goToSubPage("temperature"));
                buttonGoVoltage.setOnClickListener(v -> goToSubPage("voltage"));
                buttonGoMagnetic.setOnClickListener(v -> goToSubPage("magnetic"));
                break;
        }
        // If using IO Manager, hide the manual read button; otherwise attach its listener.
        if (withIoManager) {
            receiveBtn.setVisibility(View.GONE);
        } else {
            receiveBtn.setOnClickListener(v -> read());
        }

        // Initialize control-lines (for toggling RTS/DTR and monitoring other signals)
        controlLines = new ControlLines(view);

        return view;
    }

    private void goToSubPage(String subMode) {
        TerminalFragment fragment = new TerminalFragment();
        Bundle args = new Bundle();
        args.putInt("device", deviceId);
        args.putInt("port", portNum);
        args.putInt("baud", baudRate);
        args.putBoolean("withIoManager", withIoManager);
        args.putString("mode", subMode);
        fragment.setArguments(args);
        getFragmentManager().beginTransaction()
                .replace(R.id.fragment, fragment, "terminal")
                .addToBackStack(null)
                .commit();
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        // Inflate options menu if needed
        inflater.inflate(R.menu.menu_terminal, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.clear) {
            receiveText.setText("");
            return true;
        } else if (id == R.id.send_break) {
            if (!connected) {
                Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            } else {
                try {
                    usbSerialPort.setBreak(true);
                    Thread.sleep(100);
                    usbSerialPort.setBreak(false);
                    SpannableStringBuilder spn = new SpannableStringBuilder();
                    spn.append("send <break>\n");
                    spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)),
                            0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    receiveText.append(spn);
                } catch (UnsupportedOperationException ignored) {
                    Toast.makeText(getActivity(), "BREAK not supported", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(getActivity(), "BREAK failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    /* ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     * SerialInputOutputManager.Listener methods
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~ */
    @Override
    public void onNewData(byte[] data) {
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
     * Connect / Disconnect / Serial operations
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~ */
    private void connect() {
        if (deviceId == 0) {
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
            status("connection failed: device not found (deviceId=" + deviceId + ")");
            return;
        }
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if (driver == null) {
            driver = CustomProber.getCustomProber().probeDevice(device);
        }
        if (driver == null) {
            status("connection failed: no driver for device");
            return;
        }
        if (driver.getPorts().size() <= portNum) {
            status("connection failed: not enough ports at device");
            return;
        }
        usbSerialPort = driver.getPorts().get(portNum);
        UsbDeviceConnection usbConnection = usbManager.openDevice(driver.getDevice());
        if (usbConnection == null && usbPermission == UsbPermission.Unknown && !usbManager.hasPermission(driver.getDevice())) {
            usbPermission = UsbPermission.Requested;
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_MUTABLE : 0;
            Intent intent = new Intent(INTENT_ACTION_GRANT_USB);
            intent.setPackage(getActivity().getPackageName());
            PendingIntent usbPermissionIntent = PendingIntent.getBroadcast(getActivity(), 0, intent, flags);
            usbManager.requestPermission(driver.getDevice(), usbPermissionIntent);
            return;
        }
        if (usbConnection == null) {
            if (!usbManager.hasPermission(driver.getDevice()))
                status("connection failed: permission denied");
            else
                status("connection failed: open failed");
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
            // Start control-lines management after a successful connection.
            controlLines.start();
        } catch (Exception e) {
            status("connection failed: " + e.getMessage());
            disconnect();
        }
    }

    private void disconnect() {
        connected = false;
        controlLines.stop();
        if (usbIoManager != null) {
            usbIoManager.setListener(null);
            usbIoManager.stop();
        }
        usbIoManager = null;
        try {
            if (usbSerialPort != null) {
                usbSerialPort.close();
            }
        } catch (IOException ignored) { }
        usbSerialPort = null;
    }

    private void send(String str) {
        if (!connected) {
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            // Append a newline to the string and convert to bytes
            byte[] data = (str + "\n").getBytes();
            // Directly display the text instead of dumping hex values
            SpannableStringBuilder spn = new SpannableStringBuilder();
            spn.append(str).append("\n");
            spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)),
                    0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            receiveText.append(spn);
            // Write the byte array to the USB serial port
            usbSerialPort.write(data, WRITE_WAIT_MILLIS);
        } catch (Exception e) {
            onRunError(e);
        }
    }

    private void read() {
        if (!connected) {
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
        // Convert the received bytes to a String.
        String receivedText = new String(data);

        // Check if the string contains the expected "Degrees:" prefix.
        if(receivedText.contains("Degrees:")) {
            // Use regex to extract the X, Y and Z degrees.
            Pattern pattern = Pattern.compile("Degrees: \\[X: ([0-9.]+), Y: ([0-9.]+), Z: ([0-9.]+)\\]");
            Matcher matcher = pattern.matcher(receivedText);
            if(matcher.find()) {
                try {
                    float x_deg = Float.parseFloat(matcher.group(1));
                    float y_deg = Float.parseFloat(matcher.group(2));
                    float z_deg = Float.parseFloat(matcher.group(3));

                    // Update the line chart with the new degrees.
                    updateLineChart(x_deg, y_deg, z_deg);
                } catch (NumberFormatException e) {
                    // In case of a conversion error, do nothing.
                    return;
                }
            }
        }
        // Optionally, you can still print or log all received data:
        SpannableStringBuilder spn = new SpannableStringBuilder();
        spn.append("receive: ").append(receivedText).append("\n");
        receiveText.append(spn);
    }

    private void updateLineChart(float x_deg, float y_deg, float z_deg) {
        // Get the chart's current data or create it if null.
        LineData data = lineChart.getData();
        if (data == null) {
            data = new LineData();
            lineChart.setData(data);
        }

        // Get or create dataset for X Degrees.
        LineDataSet xDataSet = (LineDataSet) data.getDataSetByLabel("X Degrees", true);
        if (xDataSet == null) {
            xDataSet = new LineDataSet(new ArrayList<Entry>(), "X Degrees");
            xDataSet.setColor(Color.BLUE);
            data.addDataSet(xDataSet);
        }

        // Get or create dataset for Y Degrees.
        LineDataSet yDataSet = (LineDataSet) data.getDataSetByLabel("Y Degrees", true);
        if (yDataSet == null) {
            yDataSet = new LineDataSet(new ArrayList<Entry>(), "Y Degrees");
            yDataSet.setColor(Color.GREEN);
            data.addDataSet(yDataSet);
        }

        // Get or create dataset for Z Degrees.
        LineDataSet zDataSet = (LineDataSet) data.getDataSetByLabel("Z Degrees", true);
        if (zDataSet == null) {
            zDataSet = new LineDataSet(new ArrayList<Entry>(), "Z Degrees");
            zDataSet.setColor(Color.RED);
            data.addDataSet(zDataSet);
        }

        // Use the entry count of the dataset as the new x value.
        int newEntryIndex = xDataSet.getEntryCount();

        // If the number of entries is 20 or more, remove the first entry from each dataset.
        if (newEntryIndex >= 20) {
            xDataSet.removeEntry(0);
            yDataSet.removeEntry(0);
            zDataSet.removeEntry(0);

            // After removal, re-index all entries so the x-axis remains sequential.
            for (int i = 0; i < xDataSet.getEntryCount(); i++) {
                Entry entry = xDataSet.getEntryForIndex(i);
                entry.setX(i);
            }
            for (int i = 0; i < yDataSet.getEntryCount(); i++) {
                Entry entry = yDataSet.getEntryForIndex(i);
                entry.setX(i);
            }
            for (int i = 0; i < zDataSet.getEntryCount(); i++) {
                Entry entry = zDataSet.getEntryForIndex(i);
                entry.setX(i);
            }
            newEntryIndex = xDataSet.getEntryCount();
        }

        // Add new entries to each dataset.
        data.addEntry(new Entry(newEntryIndex, x_deg), data.getIndexOfDataSet(xDataSet));
        data.addEntry(new Entry(newEntryIndex, y_deg), data.getIndexOfDataSet(yDataSet));
        data.addEntry(new Entry(newEntryIndex, z_deg), data.getIndexOfDataSet(zDataSet));

        // Notify the chart about the data change and refresh it.
        data.notifyDataChanged();
        lineChart.notifyDataSetChanged();
        lineChart.invalidate();
    }




    void status(String str) {
        SpannableStringBuilder spn = new SpannableStringBuilder(str + "\n");
        spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)),
                0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        receiveText.append(spn);
    }

    /* ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     * Inner class for managing control lines
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~ */
    class ControlLines {
        private static final int refreshInterval = 200; // milliseconds
        private final Runnable runnable;
        private final ToggleButton rtsBtn, ctsBtn, dtrBtn, dsrBtn, cdBtn, riBtn;

        ControlLines(View view) {
            // Use a single runnable instance to allow removal of callbacks
            runnable = this::run;
            rtsBtn = view.findViewById(R.id.controlLineRts);
            ctsBtn = view.findViewById(R.id.controlLineCts);
            dtrBtn = view.findViewById(R.id.controlLineDtr);
            dsrBtn = view.findViewById(R.id.controlLineDsr);
            cdBtn  = view.findViewById(R.id.controlLineCd);
            riBtn  = view.findViewById(R.id.controlLineRi);
            // Allow toggling RTS and DTR via UI
            rtsBtn.setOnClickListener(this::toggle);
            dtrBtn.setOnClickListener(this::toggle);
        }

        private void toggle(View v) {
            ToggleButton btn = (ToggleButton) v;
            if (!connected) {
                btn.setChecked(!btn.isChecked());
                Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
                return;
            }
            String ctrl = "";
            try {
                if (btn.equals(rtsBtn)) {
                    ctrl = "RTS";
                    usbSerialPort.setRTS(btn.isChecked());
                }
                if (btn.equals(dtrBtn)) {
                    ctrl = "DTR";
                    usbSerialPort.setDTR(btn.isChecked());
                }
            } catch (IOException e) {
                status("set" + ctrl + "() failed: " + e.getMessage());
            }
        }

        private void run() {
            if (!connected)
                return;
            try {
                EnumSet<UsbSerialPort.ControlLine> lines = usbSerialPort.getControlLines();
                rtsBtn.setChecked(lines.contains(UsbSerialPort.ControlLine.RTS));
                ctsBtn.setChecked(lines.contains(UsbSerialPort.ControlLine.CTS));
                dtrBtn.setChecked(lines.contains(UsbSerialPort.ControlLine.DTR));
                dsrBtn.setChecked(lines.contains(UsbSerialPort.ControlLine.DSR));
                cdBtn.setChecked(lines.contains(UsbSerialPort.ControlLine.CD));
                riBtn.setChecked(lines.contains(UsbSerialPort.ControlLine.RI));
                mainLooper.postDelayed(runnable, refreshInterval);
            } catch (Exception e) {
                status("getControlLines() failed: " + e.getMessage() + " -> stopped control line refresh");
            }
        }

        void start() {
            if (!connected)
                return;
            try {
                EnumSet<UsbSerialPort.ControlLine> supported = usbSerialPort.getSupportedControlLines();
                if (!supported.contains(UsbSerialPort.ControlLine.RTS)) rtsBtn.setVisibility(View.INVISIBLE);
                if (!supported.contains(UsbSerialPort.ControlLine.CTS)) ctsBtn.setVisibility(View.INVISIBLE);
                if (!supported.contains(UsbSerialPort.ControlLine.DTR)) dtrBtn.setVisibility(View.INVISIBLE);
                if (!supported.contains(UsbSerialPort.ControlLine.DSR)) dsrBtn.setVisibility(View.INVISIBLE);
                if (!supported.contains(UsbSerialPort.ControlLine.CD))  cdBtn.setVisibility(View.INVISIBLE);
                if (!supported.contains(UsbSerialPort.ControlLine.RI))  riBtn.setVisibility(View.INVISIBLE);
                run();
            } catch (Exception e) {
                Toast.makeText(getActivity(), "getSupportedControlLines() failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                rtsBtn.setVisibility(View.INVISIBLE);
                ctsBtn.setVisibility(View.INVISIBLE);
                dtrBtn.setVisibility(View.INVISIBLE);
                dsrBtn.setVisibility(View.INVISIBLE);
                cdBtn.setVisibility(View.INVISIBLE);
                riBtn.setVisibility(View.INVISIBLE);
            }
        }

        void stop() {
            mainLooper.removeCallbacks(runnable);
            rtsBtn.setChecked(false);
            ctsBtn.setChecked(false);
            dtrBtn.setChecked(false);
            dsrBtn.setChecked(false);
            cdBtn.setChecked(false);
            riBtn.setChecked(false);
        }
    }
}
