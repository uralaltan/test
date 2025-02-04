package com.hoho.android.usbserial.examples;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

public class MainActivity extends AppCompatActivity implements FragmentManager.OnBackStackChangedListener {

    private static final int SKIP_TO_TERMINAL = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        getSupportFragmentManager().addOnBackStackChangedListener(this);

        if (savedInstanceState == null) {
            if (SKIP_TO_TERMINAL == 1) {
                Bundle args = new Bundle();
                args.putInt("device", 0);
                args.putInt("port", 0);
                args.putInt("baud", 19200);
                args.putBoolean("withIoManager", true);

                Fragment terminalFragment = new TerminalFragment();
                terminalFragment.setArguments(args);

                getSupportFragmentManager().beginTransaction()
                        .replace(R.id.fragment, terminalFragment, "terminal")
                        .commit();
            } else {
                getSupportFragmentManager().beginTransaction()
                        .add(R.id.fragment, new DevicesFragment(), "devices")
                        .commit();
            }
        }
        onBackStackChanged();
    }

    @Override
    public void onBackStackChanged() {
        boolean canGoBack = getSupportFragmentManager().getBackStackEntryCount() > 0;
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(canGoBack);
            getSupportActionBar().setDisplayShowHomeEnabled(canGoBack);
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if ("android.hardware.usb.action.USB_DEVICE_ATTACHED".equals(intent.getAction())) {
            TerminalFragment terminal = (TerminalFragment) getSupportFragmentManager().findFragmentByTag("terminal");
            if (terminal != null) {
                terminal.status("USB device detected");
            }
        }
    }
}
