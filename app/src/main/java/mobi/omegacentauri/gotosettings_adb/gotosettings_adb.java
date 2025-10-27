package mobi.omegacentauri.gotosettings_adb;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothProfile;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.TabStopSpan;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceListener;

public class gotosettings_adb extends Activity {

    InetAddress address = null;
    int port = -1;
    JmDNS jmdns = null;

    private static final String TAG = "gotosettings_adb_main";
    private String adbPath;
    private SharedPreferences options;
    private Button enableWiFiADBButton;
    private TextView grantText;
    private WifiManager wifiManager;
    private WifiManager.MulticastLock lock = null;
    private boolean listening = false;
    private ServiceListener serviceListener;
    private Thread listeningThread = null;
    private TextView adbText;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        options = PreferenceManager.getDefaultSharedPreferences(this);

        setContentView(R.layout.main);

        enableWiFiADBButton = findViewById(R.id.enable_wifi_adb_button);
        grantText = findViewById(R.id.grant);
        adbText = findViewById(R.id.adb);
        adbPath = getApplicationInfo().nativeLibraryDir+"/libadb.so";
    }

    @Override
    protected void onResume() {
        super.onResume();


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && PackageManager.PERMISSION_DENIED == checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")) {
            enableWiFiADBButton.setEnabled(false);
            grantText.setVisibility(View.VISIBLE);
        }
        else {
            enableWiFiADBButton.setEnabled(true);
            grantText.setVisibility(View.GONE);
        }
        listen();
    }

    @Override
    protected void onPause() {
        super.onPause();
        closeListen();
    }

    public void enableWiFiADB(View view) {
        if (! wifiManager.isWifiEnabled()) {
            wifiManager.setWifiEnabled(true);
            new Timer().schedule(new TimerTask() {
                @Override
                public void run() {
                    Settings.Global.putInt(
                            getContentResolver(),
                            "adb_wifi_enabled", 1);
                    listen();
                }
            }, 4000);
        }
        else {
            Settings.Global.putInt(
                    getContentResolver(),
                    "adb_wifi_enabled", 1);
            listen();
        }
    }

    public void goToSettings(View view) {
        Intent i = new Intent();
        i.setComponent(new ComponentName("com.android.settings", "com.android.settings.Settings"));
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_TASK_ON_HOME);
        startActivity(i);
        finish();
    }

    public void closeListen() {
        if (lock != null) {
            lock.release();
            lock = null;
        }
        if (listeningThread != null) {
            listening = false;
            listeningThread.stop();
        }
        if (jmdns != null) {
            try {
                jmdns.close();
            } catch (IOException e) {
            }
        }
        jmdns = null;
    }

    public void listen() {
        closeListen();
        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (lock == null) {
            lock = wifiManager.createMulticastLock("jmdns_multicast_lock");
            lock.setReferenceCounted(true);
            lock.acquire();
        }
        if (serviceListener == null)
            serviceListener = new javax.jmdns.ServiceListener(){
                @Override
                public void serviceAdded(ServiceEvent event) {
                    jmdns.requestServiceInfo(event.getType(), event.getName(), true);
                }

                @Override
                public void serviceRemoved(ServiceEvent event) {

                }

                @Override
                public void serviceResolved(ServiceEvent event) {
                    InetAddress[] hosts = event.getInfo().getInetAddresses();
                    if (hosts[0].equals(address)) {
                        port = event.getInfo().getPort();
                        closeListen();
                        updateAddressPort();
                    }
                }
            };

        listeningThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (listening) {
                    try {
                        int ip = -1;
                        if (Settings.Global.getInt(
                                getContentResolver(),
                                "adb_wifi_enabled",0) == 1 &&
                            wifiManager.isWifiEnabled()) {
                            WifiInfo connInfo = wifiManager.getConnectionInfo();
                            if (connInfo.getBSSID() != null) {
                                ip = connInfo.getIpAddress();
                            }
                            Log.v(TAG, "ip "+ip);
                        }
                        if (ip != -1) {
                            address = InetAddress.getByAddress(new byte[] { (byte)(ip&0xff),
                                    (byte)((ip>>8)&0xff),
                                    (byte)((ip>>16)&0xff),
                                    (byte)((ip>>24)&0xff) });
                            jmdns = JmDNS.create(address);
                            Log.v(TAG, "jmdns");
                            jmdns.addServiceListener("_adb-tls-connect._tcp.local.", serviceListener);
                            jmdns.addServiceListener("_adb_secure_connect._tcp.local.", serviceListener);
                            listeningThread = null;
                            Log.v(TAG, "go");
                            return;
                        }
                        else {
                            port = -1;
                            updateAddressPort();
                        }
                    }
                    catch (IOException e) {
                    }
                    if (jmdns != null) {
                        try {
                            jmdns.close();
                        } catch (IOException e) {
                        }
                    }
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                    }
                }
            }
        });
        listening = true;
        listeningThread.start();

    }

    private void updateAddressPort() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (port >= 0)
                    adbText.setText(address.getHostAddress()+":"+port);
                else
                    adbText.setText("");
            }
        });

    }

    public void noIPDMessage(View view) {
        if (port >= 0) {
            String cmd[] = new String[] { adbPath, "connect", ""+address.getHostName() + ":" + port,
                        };
            run(cmd);
            cmd = new String[] { adbPath, "-s", ""+address.getHostName() + ":" + port,
                    "shell", "setprop", "debug.oculus.noIpdNotifier", "1" };
            run(cmd);
        }
    }

    private void run(String[] cmd) {
        ProcessBuilder builder = new ProcessBuilder(cmd);
        builder.environment().put("HOME", "/data/data/mobi.omegacentauri.gotosettings_adb");
        builder.environment().put("TMPDIR", "/data/data/mobi.omegacentauri.gotosettings_adb");
        builder.redirectErrorStream(true);
        InputStream output = null;
        try {
            output = builder.start().getInputStream();
        } catch (IOException e) {
            return;
        }
        //InputStream error = Runtime.getRuntime().exec(cmd/*,env*/).getErrorStream();
        BufferedReader r = new BufferedReader(new InputStreamReader(output));
        while (true) {
            String line = null;
            try {
                line = r.readLine();
            } catch (IOException e) {
                return;
            }
            if (line != null)
                Log.v(TAG, line);
            else
                break;
        }
    }
}
