package mobi.omegacentauri.gotosettings_adb;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.text.Layout;
import android.util.Log;
import android.view.View;
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
import java.util.Timer;
import java.util.TimerTask;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceListener;

public class gotosettings_adb extends Activity {
    static final String TLS_CONNECT = "_adb-tls-connect._tcp.local.";
    static final String SECURE_CONNECT = "_adb_secure_connect._tcp.local.";
    static final String TLS_PAIR = "_adb-tls-pairing._tcp.local.";
    static final String SECURE_PAIR = "_adb-tls-pairing._tcp.local.";

    InetAddress address = null;
    int port = -1;
    int pairPort = -1;
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
    private Button pairButton;
    private TextView pinField;
    private TextView pairPortField;
    private LinearLayout pairControls;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        options = PreferenceManager.getDefaultSharedPreferences(this);

        setContentView(R.layout.main);

        enableWiFiADBButton = findViewById(R.id.enable_wifi_adb_button);
        grantText = findViewById(R.id.grant);
        adbText = findViewById(R.id.adb);
        adbPath = getApplicationInfo().nativeLibraryDir + "/libadb.so";
        pairButton = (Button) findViewById(R.id.pair);
        pinField = (TextView) findViewById(R.id.pin);
        pairPortField = (TextView)findViewById(R.id.pair_port);
        pairControls = (LinearLayout) findViewById(R.id.pair_controls);
    }

    void checkPermissions() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if(Build.VERSION.SDK_INT >=Build.VERSION_CODES.M
                        &&
                        PackageManager.PERMISSION_DENIED ==
                                checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS"))
                {
                    enableWiFiADBButton.setEnabled(false);
                    grantText.setVisibility(View.VISIBLE);
                }
                else
                {
                    enableWiFiADBButton.setEnabled(true);
                    grantText.setVisibility(View.GONE);
                }
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();

        checkPermissions();
        updateAddressPort();
        listen();
    }

    @Override
    protected void onStop() {
        super.onStop();
        closeListen();
    }

    public void enableWiFiADB(View view) {
        Log.v(TAG, "must enable");
        if (! wifi() ) {
            Log.v(TAG, "enabling wifi");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Toast.makeText(this, "Please activate WiFi first", Toast.LENGTH_LONG).show();;
                startActivity(new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY));
                return;
            }
            else {
                wifiManager.setWifiEnabled(true);
            }
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
        Log.v(TAG, "closing");
        if (lock != null) {
            Log.v(TAG, "rel");
            lock.release();
            lock = null;
        }
        if (listeningThread != null) {
            Log.v(TAG, "stop");
            listening = false;
            try {
                listeningThread.stop();
            }
            catch(Exception e) {}
        }
        if (jmdns != null) {
            Log.v(TAG, "jmdns");
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
                    Log.v(TAG, "removed "+event.getType());
                    if (event.getType().equals(SECURE_PAIR) || event.getType().equals(TLS_PAIR)) {
                        pairPort = -1;
                        updateAddressPort();
                    }
                }

                @Override
                public void serviceResolved(ServiceEvent event) {
                    InetAddress[] hosts = event.getInfo().getInetAddresses();
                    if (hosts[0].equals(address)) {
                        String t = event.getType();
                        Log.v(TAG, t);
                        if (t.equals(SECURE_PAIR) || t.equals(TLS_PAIR)) {
                            pairPort = event.getInfo().getPort();
                            updateAddressPort();
                        }
                        else {
                            port = event.getInfo().getPort();
//                            closeListen();
                            updateAddressPort();
                        }
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
                                "adb_wifi_enabled",0) == 1 && wifi()) {
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
                            jmdns.addServiceListener(TLS_CONNECT, serviceListener);
                            jmdns.addServiceListener(SECURE_CONNECT, serviceListener);
                            jmdns.addServiceListener(TLS_PAIR, serviceListener);
                            jmdns.addServiceListener(SECURE_PAIR, serviceListener);
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
                if (pairPort >= 0)
                    pairPortField.setText(""+pairPort);
                else
                    pairPortField.setText("");
                if (port >= 0)
                    adbText.setText(address.getHostAddress()+":"+port);
                else
                    adbText.setText("");
                if (pairPort >= 0) {
                    pinField.setVisibility(View.VISIBLE);
                    pairControls.setVisibility(View.VISIBLE);
                }
                else {
                    pinField.setVisibility(View.INVISIBLE);
                    pairControls.setVisibility(View.GONE);
                }
            }
        });

    }

    public void noIPDMessage(View view) {
        if (port >= 0) {
            String cmd1[] = new String[] { adbPath, "connect", ""+address.getHostName() + ":" + port,
                        };
            String cmd2[] = new String[] { adbPath, "-s", ""+address.getHostName() + ":" + port,
                    "shell", "setprop", "debug.oculus.noIpdNotifier", "1" };
            new Thread(new Runnable() {
                @Override
                public void run() {
                    gotosettings_adb.this.adbrun(cmd1,cmd2);
                }
            }).start();
        }
    }

    private void adbrun(String[]... cmds) {
        for (String[] cmd : cmds) {
            if (cmd == null)
                continue;
            String s = "";
            for (String c : cmd)
                s += c + " ";
            Log.v(TAG,s);
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

    public void devSettings(View view) {
        Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >=24) i.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
        startActivity(i);
    }

    public boolean wifi() {
        if (!wifiManager.isWifiEnabled())
            return false;
        ConnectivityManager c = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        return c.getNetworkInfo(ConnectivityManager.TYPE_WIFI).isConnected();
    }

    public void pair(View view) {
        String cmd1[] = new String[] { adbPath, "kill-server" };
        String cmd2[] = new String[] { adbPath, "pair", ""+address.getHostName()+":"+pairPortField.getText(), String.valueOf(pinField.getText())};
        String cmd3[] = port < 0 ? null : new String[]{adbPath, "connect", "" + address.getHostName() + ":" + port};
        String cmd4[] = port < 0 ? null : new String[]{adbPath, "shell", "pm", "grant", "mobi.omegacentauri.gotosettings_adb", "android.permission.WRITE_SECURE_SETTINGS"};

        new Thread(new Runnable() {
            @Override
            public void run() {
                adbrun(cmd1,cmd2,cmd3,cmd4);
                checkPermissions();
                pairPort = -1;
                updateAddressPort();
            }
        }).start();
    }

    public void key0(View view) {
        key("0");
    }
    public void key1(View view) {
        key("1");
    }
    public void key2(View view) {
        key("2");
    }
    public void key3(View view) {
        key("3");
    }
    public void key4(View view) {
        key("4");
    }
    public void key5(View view) {
        key("5");
    }
    public void key6(View view) {
        key("6");
    }
    public void key7(View view) {
        key("7");
    }
    public void key8(View view) {
        key("8");
    }
    public void key9(View view) {
        key("9");
    }
    public void keyBS(View view) {
        CharSequence t = pinField.getText();
        if (t.length()>0)
            pinField.setText(t.subSequence(0,t.length()-1));
    }

    public void keyX(View view) {
        pairPort = -1;
        updateAddressPort();
    }

    private void key(String number) {
        CharSequence t = pinField.getText();
        pinField.setText(t+number);
    }
}
