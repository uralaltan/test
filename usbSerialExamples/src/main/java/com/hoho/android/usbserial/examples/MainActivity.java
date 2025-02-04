package com.hoho.android.usbserial.examples;

import android.content.Intent;
import android.os.Bundle;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

public class MainActivity extends AppCompatActivity implements FragmentManager.OnBackStackChangedListener {

    private static final int SKIP_TO_TERMINAL = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        if (savedInstanceState == null) {
            if (SKIP_TO_TERMINAL == 1) {
                // =============== Skip directly to TerminalFragment =================
                // 1) Create a TerminalFragment and supply "dummy" or default arguments
                Bundle args = new Bundle();
                args.putInt("device", 0);
                args.putInt("port", 0);
                args.putInt("baud", 19200);
                args.putBoolean("withIoManager", true);

                Fragment terminalFragment = new TerminalFragment();
                terminalFragment.setArguments(args);

                // 2) Replace the container with TerminalFragment
                getSupportFragmentManager().beginTransaction()
                        .replace(R.id.fragment, terminalFragment, "terminal")
                        .commit();
            } else {
                // =============== Normal usage: show DevicesFragment =================
                getSupportFragmentManager().beginTransaction()
                        .add(R.id.fragment, new DevicesFragment(), "devices")
                        .commit();
            }
        }
    }

    @Override
    public void onBackStackChanged() {
        getSupportActionBar().setDisplayHomeAsUpEnabled(getSupportFragmentManager().getBackStackEntryCount()>0);
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        if("android.hardware.usb.action.USB_DEVICE_ATTACHED".equals(intent.getAction())) {
            TerminalFragment terminal = (TerminalFragment)getSupportFragmentManager().findFragmentByTag("terminal");
            if (terminal != null)
                terminal.status("USB device detected");
        }
        super.onNewIntent(intent);
    }

}
