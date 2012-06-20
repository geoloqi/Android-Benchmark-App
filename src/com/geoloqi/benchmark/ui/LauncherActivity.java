package com.geoloqi.benchmark.ui;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.geoloqi.android.sdk.LQSession;
import com.geoloqi.android.sdk.LQTracker;
import com.geoloqi.android.sdk.LQTracker.LQTrackerProfile;
import com.geoloqi.android.sdk.service.LQService;
import com.geoloqi.android.sdk.service.LQService.LQBinder;
import com.geoloqi.benchmark.R;
import com.geoloqi.benchmark.receiver.SampleReceiver;

/**
 * <p>This is the main {@link Activity} for the Geoloqi Sample Android
 * app. It starts up and binds to the {@link LQService} tracker. It also
 * registers to receive broadcasts from the tracker using the
 * interfaces defined on the {@link SampleReceiver}.</p>
 * 
 * @author Tristan Waddington
 */
public class LauncherActivity extends Activity implements OnClickListener,
        OnItemSelectedListener {
    public static final String TAG = "LauncherActivity";
    public static final String PARAM_ACTIVE_TEST = "com.geoloqi.benchmark.param.ACTIVE_TEST";

    private String mActiveTestPath;

    private Button mStartTestButton;
    private Button mStopTestButton;
    private TextView mBatteryLevel;
    private Spinner mTrackerProfile;

    private SharedPreferences mSharedPreferences;
    private SimpleDateFormat mDateFormat;

    private BatteryChangeReceiver mBatteryChangeReceiver = new BatteryChangeReceiver();
    private LQService mService;
    private boolean mBound;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        
        // Start the tracking service
        Intent intent = new Intent(this, LQService.class);
        startService(intent);
        
        // Get our shared preferences instance
        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        
        // Create our date formatter for our logs
        mDateFormat = new SimpleDateFormat("yyDDD", Locale.US);
        
        // Determine if a test is currently active
        mActiveTestPath = mSharedPreferences.getString(PARAM_ACTIVE_TEST, null);
        
        // Get our TextView
        mBatteryLevel = (TextView) findViewById(R.id.battery_level);
        mBatteryLevel.setText(String.format("%s%%", getBattery()));
        
        // Get our Spinner
        mTrackerProfile = (Spinner) findViewById(R.id.tracker_profile);
        mTrackerProfile.setOnItemSelectedListener(this);
        
        // Wire up our buttons
        mStartTestButton = (Button) findViewById(R.id.start_test);
        mStartTestButton.setOnClickListener(this);
        
        mStopTestButton = (Button) findViewById(R.id.stop_test);
        mStopTestButton.setOnClickListener(this);
        
        // Toggle our UI state
        toggleButtonState();
    }

    @Override
    public void onResume() {
        super.onResume();
        
        // Bind to the tracking service so we can call public methods on it
        Intent intent = new Intent(this, LQService.class);
        bindService(intent, mConnection, 0);
        
        // Wire up our battery change receiver
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        registerReceiver(mBatteryChangeReceiver, filter);
    }

    @Override
    public void onPause() {
        super.onPause();
        
        // Unbind from LQService
        if (mBound) {
            unbindService(mConnection);
            mBound = false;
        }
        
        // Unregister receiver
        try {
            unregisterReceiver(mBatteryChangeReceiver);
        } catch (IllegalArgumentException e) {
            // Pass
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch(item.getItemId()) {
        case R.id.settings:
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return false;
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
        case R.id.start_test:
            startTest();
            break;
        case R.id.stop_test:
            stopTest();
            break;
        }
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position,
            long id) {
        if (mBound && mService != null) {
            LQTrackerProfile profile = null;
            switch (position) {
            case 0:
                profile = LQTrackerProfile.OFF;
                break;
            case 1:
                profile = LQTrackerProfile.ROUGH;
                break;
            case 2:
                profile = LQTrackerProfile.ADAPTIVE;
                break;
            case 3:
                profile = LQTrackerProfile.LOGGING;
                break;
            case 4:
                profile = LQTrackerProfile.REAL_TIME;
                break;
            }
            mService.getTracker().setProfile(profile);
        }
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {
        // Pass
    }

    /** Get the current battery level as a percent. */
    private int getBattery() {
        Intent intent = registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
    }
    
    /** Get the active {@link LQTrackerProfile} from the {@link LQTracker}. */
    private LQTrackerProfile getProfile() {
        if (mBound && mService != null) {
            return mService.getTracker().getProfile();
        }
        return null;
    }
    
    /** Get the current username from the active {@link LQSession}. */
    private String getUser() {
        if (mBound && mService != null) {
            return mService.getSession().getUsername();
        }
        return null;
    }
    
    /** Toggle the button state in the Activity layout. */
    private void toggleButtonState() {
        if (!TextUtils.isEmpty(mActiveTestPath)) {
            // Test running!
            mStartTestButton.setVisibility(View.GONE);
            mStopTestButton.setVisibility(View.VISIBLE);
        } else {
            // Test *not* running!
            mStartTestButton.setVisibility(View.VISIBLE);
            mStopTestButton.setVisibility(View.GONE);
        }
    }
    
    /** Start a new test and create a new log file. */
    private void startTest() {
        String state = Environment.getExternalStorageState();
        if (Environment.MEDIA_MOUNTED.equals(state)) {
            File path = getExternalFilesDir(null);
            
            // Get today's date code
            String dateCode = mDateFormat.format(new Date(System.currentTimeMillis()));
            
            // Create our log file
            int i = 1;
            File log = null;
            do {
                log = new File(path,
                        String.format("geoloqi-%s-%s.log", dateCode, i++));
            } while(log.exists());
            
            try {
                FileWriter fw = new FileWriter(log);
                fw.write(String.format("Start: %s; Battery: %s%%; Profile: %s; User: %s\n",
                        LQSession.formatTimestamp(System.currentTimeMillis()),
                        getBattery(), getProfile(), getUser()));
                fw.close();
                
                // Save the log filename
                Editor editor = mSharedPreferences.edit();
                editor.putString(PARAM_ACTIVE_TEST, log.getAbsolutePath());
                editor.commit();
                mActiveTestPath = log.getAbsolutePath();
                
                toggleButtonState();
            } catch (IOException e) {
                Log.e(TAG, "Failed to write to log!", e);
                Toast.makeText(this, "Failed to write to log!",
                        Toast.LENGTH_LONG).show();
            }
        } else {
            Toast.makeText(this, "Failed to start test log! Media not mounted.",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Stop an active test and write the final values to the log. */
    private void stopTest() {
        String state = Environment.getExternalStorageState();
        if (Environment.MEDIA_MOUNTED.equals(state)) {
            File log = new File(mActiveTestPath);
            try {
                FileWriter fw = new FileWriter(log, true);
                fw.write(String.format("End: %s; Battery: %s%%; Profile: %s; User: %s\n",
                        LQSession.formatTimestamp(System.currentTimeMillis()),
                        getBattery(), getProfile(), getUser()));
                fw.close();
                
                // Remove the logged filename
                Editor editor = mSharedPreferences.edit();
                editor.remove(PARAM_ACTIVE_TEST);
                editor.commit();
                mActiveTestPath = null;
                
                // Display our log path
                TextView view = (TextView) findViewById(R.id.log_path);
                if (view != null) {
                    view.setText(log.getAbsolutePath());
                }
                
                toggleButtonState();
            } catch (IOException e) {
                Log.e(TAG, "Failed to write to log!", e);
                Toast.makeText(this, "Failed to write to log!",
                        Toast.LENGTH_LONG).show();
            }
        } else {
            Toast.makeText(this, "Failed to stop test log! Media not mounted.",
                    Toast.LENGTH_LONG).show();
        }
    }
    
    /** A simple receiver for listening for battery level changes. */
    private class BatteryChangeReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                mBatteryLevel.setText(String.format("%s%%",
                        intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)));
            }
        }
    }
    
    /** Defines callbacks for service binding, passed to bindService() */
    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            try {
                // We've bound to LocalService, cast the IBinder and get LocalService instance.
                LQBinder binder = (LQBinder) service;
                mService = binder.getService();
                mBound = true;
                
                // Update the tracker profile Spinner
                mTrackerProfile.setSelection(getProfile().ordinal());
            } catch (ClassCastException e) {
                // Pass
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mBound = false;
        }
    };
}
