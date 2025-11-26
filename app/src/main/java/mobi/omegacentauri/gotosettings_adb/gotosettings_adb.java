package mobi.omegacentauri.gotosettings_adb;

// TODO: tcpip 5555 optional
// TODO: if 5555 is really old, use new port?

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceListener;

public class gotosettings_adb extends Activity {
    static final String TLS_CONNECT =  "_adb-tls-connect._tcp.local.";
    static final String SECURE_CONNECT = "_adb_secure_connect._tcp.local.";
    static final String ADB_CONNECT = "_adb._tcp.local.";
    static final String TLS_PAIR = "_adb-tls-pairing._tcp.local.";
    static final String SECURE_PAIR = "_adb-tls-pairing._tcp.local.";
    static final String SETTINGS = "com.android.settings";
    static final String ADB_GRANT = "adb shell pm grant mobi.omegacentauri.gotosettings_adb android.permission.WRITE_SECURE_SETTINGS";
    static final int BUTTONS_PER_LINE = 4;

    InetAddress address = null;
    int port = -1;
    int pairPort = -1;
    JmDNS jmdns = null;
    CharSequence outputData = "";

    private static final String TAG = "gotosettings_adb_main";
    private String adbPath;
    private SharedPreferences options;
    private Button enableWiFiADBButton;
    private WifiManager wifiManager;
    private WifiManager.MulticastLock lock = null;
    private boolean listening = false;
    private int connectMode = 0;
    private static final int CONNECT_FAILED = -1;
    private static final int CONNECT_UNKNOWN = 0;
    private static final int CONNECT_SUCCESS = 1;
    private ServiceListener serviceListener;
    private Thread listeningThread = null;
    private TextView adbText;
    private Button pairButton;
    private TextView pinField;
    private TextView pairPortField;
    private TextView pressOpen;
    private LinearLayout pairControls;
    private TextView output;
    private ScrollView outputScroller;
    private List<LinearLayout> buttonLines = new ArrayList<>();
    private TableLayout scriptsTable;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        options = PreferenceManager.getDefaultSharedPreferences(this);

        setContentView(R.layout.main);

        scriptsTable = (TableLayout) findViewById(R.id.scripts_table);
        enableWiFiADBButton = findViewById(R.id.enable_wifi_adb_button);
        //grantText = findViewById(R.id.grant);
        adbText = findViewById(R.id.adb);
        adbPath = getApplicationInfo().nativeLibraryDir + "/libadb.so";
        pairButton = (Button) findViewById(R.id.pair);
        pinField = (TextView) findViewById(R.id.pin);
        pairPortField = (TextView)findViewById(R.id.pair_port);
        pairControls = (LinearLayout) findViewById(R.id.pair_controls);
        output = (TextView)findViewById(R.id.output);
        outputScroller = (ScrollView)findViewById(R.id.output_scroller);
        pressOpen = (TextView)findViewById(R.id.press_open);
    }



    void checkPermissions() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                    try {
                        Intent intent = new Intent();
                        intent.setAction(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                        Uri uri = Uri.fromParts("package", gotosettings_adb.this.getPackageName(), null);
                        intent.setData(uri);
                        startActivity(intent);
                    } catch (Exception e) {
                        Intent intent = new Intent();
                        intent.setAction(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                        startActivity(intent);
                    }
                } //TODO:lower version
            }
        });
    }

    @SuppressLint("NewApi")
    void addButtons() {
        Button b;
        try {
            File storage = new File(Environment.getExternalStorageDirectory() + "/adbscripts");
            outputData += "Reading: "+storage.getAbsolutePath()+"\n";
            scrollOutput();
            File[] files = storage.listFiles();
            if (files == null)
                return;
            Arrays.sort(files);
            int n = 0;
            TableRow row = null;
            for (File f : files) {
                b = new Button(this);
                b.setAllCaps(false);
                b.setMaxLines(1);
                b.setText(f.getName());
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        runScript(f);
                    }
                });

                if (n % BUTTONS_PER_LINE == 0) {
                    row = new TableRow(this);
                    scriptsTable.addView(row);
                }
                row.addView(b);
                n++;
            }
        }
        catch (Exception e) {
            Log.v(TAG, ""+e);
        }
        //Log.v(TAG, String.valueOf(Environment.getExternalStorageDirectory()));
    }

    String getName(int port) {
        if (address != null)
            return "127.0.0.1:"+port; //address.getHostName()
        else
            return "127.0.0.1:"+port;
    }

    private void runScript(File f) {
        List<String> cmds = new ArrayList<>();
        if (!adbrun("adb connect "+getName(port)))
            return;
        try {
            BufferedReader br = new BufferedReader(new FileReader(f));
            while(true) {
                String line = br.readLine();
                if (line == null)
                    break;
                if (line.startsWith("adb ")) {
                    line = "adb -s "+getName(port)+" "+line.substring(4);
                }
                cmds.add(line);
            }
            if (adbrun_array(cmds.toArray(new String[0]))) {
                port = 5555;
                updateAddressPort();
            }
        } catch (IOException e) {
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        pressOpen.setVisibility(View.INVISIBLE);

        connectMode = CONNECT_UNKNOWN;

        checkPermissions();
        outputData = "";
        buttonLines.clear();
        scriptsTable.removeAllViews();
        new Thread(new Runnable() {
            @Override
            public void run() {
                int oldPort = port;
                if (adbrun("adb connect 127.0.0.1:5555")) {
                    Log.v(TAG, "connected successfully to 5555");
                    port = 5555;
                }
                else {
                    connectMode = CONNECT_UNKNOWN;
                    enableWiFiADB(false);
                }
                updateAddressPort();
            }
        }).start();
        updateAddressPort();
        listen();
        addButtons();
    }

    @Override
    protected void onStop() {
        super.onStop();
        closeListen();
    }

    public void enableWiFiADB(View view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && PackageManager.PERMISSION_DENIED ==
                checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") &&
                port >= 0) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    adbrun("adb kill-server",
                            "adb connect "+getName(port),
                            ADB_GRANT
                            );
                    enableWiFiADB(true);
                }
            }).start();
        }

        enableWiFiADB(true);
    }

    public boolean isWriteSecureGranted() {
        if(Build.VERSION.SDK_INT >=Build.VERSION_CODES.M
                &&
                PackageManager.PERMISSION_DENIED ==
                        checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS"))
            return false;
        return true;
    }

    public boolean isADBWiFiEnabled() {
        return (1==Settings.Global.getInt(getContentResolver(), "adb_wifi_enabled", 0));
    }

    public void enableWiFiADB(boolean force) {
        if (! isWriteSecureGranted() )
            return;

        if (! force && isADBWiFiEnabled()) {
            return;
        }

        Log.v(TAG, "must enable");
        if (! wifi() ) {
            Log.v(TAG, "enabling wifi");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(gotosettings_adb.this, "Please activate WiFi first", Toast.LENGTH_LONG).show();
                        startActivity(new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY));
                        startActivity(new Intent("com.oculus.action.WIFI_SETTINGS"));
                    }
                });
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

    public boolean isPackageInstalled(String s) {
        PackageManager pm = getPackageManager();
        for (PackageInfo pi : pm.getInstalledPackages(0)) {
            if (pi.packageName.equals(s))
                return true;
        }
        return false;
    }

    public void goToSettings(View view) {
        PackageManager pm = getPackageManager();
        try {
            Intent i = pm.getLaunchIntentForPackage(SETTINGS);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_TASK_ON_HOME | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
            startActivity(i);
        }
        catch(Exception e) {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + SETTINGS));
            i.setPackage(SETTINGS);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_TASK_ON_HOME | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
            startActivity(i);
            pressOpen.setVisibility(View.VISIBLE);
            pressOpen.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        pressOpen.setVisibility(View.GONE);
                    }
                    catch(Exception e) {}
                }
            }, 5000);
        }
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
            try {
                Log.v(TAG, "jmdns close");
                jmdns.close();
            } catch (IOException e) {
            }
        }
        jmdns = null;
    }

    private boolean isLocal(InetAddress[] hosts) {
        for (int i=0;i<hosts.length;i++)
            if (hosts[i].isLoopbackAddress())
                return true;

        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    for (int i=0;i<hosts.length;i++)
                        if (hosts[i].equals(addr))
                            return true;
                }
            }
        } catch (SocketException e) {
            return false;
        }
        return false;
    }

    public void listen() {
        closeListen();
        Log.v(TAG, "opening jmdns");
        if (lock == null) {
            lock = wifiManager.createMulticastLock("jmdns_multicast_lock");
            lock.setReferenceCounted(false);
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
                    if (isLocal(hosts)) {
//                        for (int i=0;i<hosts.length;i++)
//                            outputData += ""+hosts[i].getHostName()+"\n";
//                        scrollOutput();
                        String t = event.getType();
                        Log.v(TAG, t);
                        if (t.equals(SECURE_PAIR) || t.equals(TLS_PAIR)) {
                            pairPort = event.getInfo().getPort();
                            updateAddressPort();
                        }
                        else {
                            int p = event.getInfo().getPort();
//                            outputData += t+" "+p+"\n";
//                            scrollOutput();
                            if (port != 5555) {
                                port = p;
                                if (adbrun("adb connect 127.0.0.1:"+port))
                                    adbrun("adb -s 127.0.0.1:"+port+" tcpip 5555");
                            }
                            updateAddressPort();
                        }
                    }
                }

            };

        new Thread(new Runnable() {
            @Override
            public void run() {
                    try {
                        address = InetAddress.getLocalHost();
                        jmdns = JmDNS.create(address);
                        Log.v(TAG, "jmdns start");
                        jmdns.addServiceListener(TLS_CONNECT, serviceListener);
                        jmdns.addServiceListener(SECURE_CONNECT, serviceListener);
                        jmdns.addServiceListener(ADB_CONNECT, serviceListener);
                        jmdns.addServiceListener(TLS_PAIR, serviceListener);
                        jmdns.addServiceListener(SECURE_PAIR, serviceListener);
                        listeningThread = null;
                        Log.v(TAG, "go");
                    }
                    catch (IOException e) {
                        Log.v(TAG, ""+e);
                    }
                }
        }).start();

    }

    private void updateAddressPort() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (pairPort >= 0)
                    pairPortField.setText(""+pairPort);
                else
                    pairPortField.setText("");
                if (port >= 0) {
                    String s = address.getHostAddress()+":"+port;
                    if (connectMode == CONNECT_FAILED) {
                        s += ". Connection failed. Try pairing in the system developer wireless debugging settings.";
                        scriptsTable.setVisibility(View.VISIBLE);
                        enableWiFiADBButton.setVisibility(View.VISIBLE);
                        enableWiFiADBButton.setEnabled(true);
                    }
                    else {
                        if (connectMode == CONNECT_SUCCESS)
                            s += ". Connection succeeded.";
                        scriptsTable.setVisibility(View.VISIBLE);
                        enableWiFiADBButton.setVisibility(View.INVISIBLE);
                    }
                    adbText.setText(s);
                }
                else {
                    scriptsTable.setVisibility(View.GONE);
                    enableWiFiADBButton.setVisibility(View.VISIBLE);
                    if (isWriteSecureGranted()) {
                        adbText.setText("");
                        enableWiFiADBButton.setEnabled(true);
                    }
                    else {
                        adbText.setText("Please enable/pair WiFi ADB manually.");
                        enableWiFiADBButton.setEnabled(false);
                    }
                }
                if (pairPort >= 0) {
                    if (pinField.getVisibility() != View.VISIBLE) {
                        pinField.setVisibility(View.VISIBLE);
                        pinField.setText("");
                    }
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
            String cmd1 = "adb connect "+getName(port);
            String cmd2 = "adb shell setprop debug.oculus.noIpdNotifier 1";
            new Thread(new Runnable() {
                @Override
                public void run() {
                    gotosettings_adb.this.adbrun(cmd1,cmd2);
                }
            }).start();
        }
    }

    private boolean adbrun_array(String[] cmds) {
        for (String cmd : cmds) {
            if (cmd == null || cmd.length() == 0)
                continue;
            cmd = cmd.trim();
            if (! cmd.startsWith("adb ") && ! cmd.startsWith("ADB "))
                continue;
            Log.v(TAG,cmd);
            outputData += ">" + cmd + "\n";
            scrollOutput();
            boolean connecting = cmd.substring(4).startsWith("connect ");
            if (connecting) {
                Log.v(TAG, "connecting");
                connectMode = CONNECT_UNKNOWN;
            }
            String[] splitAndFixed = new String[] {
                "sh", "-c", adbPath + cmd.substring(3)
            };
            ProcessBuilder builder = new ProcessBuilder(splitAndFixed);
            builder.environment().put("HOME", "/data/data/mobi.omegacentauri.gotosettings_adb");
            builder.environment().put("TMPDIR", "/data/data/mobi.omegacentauri.gotosettings_adb");
            builder.redirectErrorStream(true);
            InputStream output = null;
            try {
                output = builder.start().getInputStream();
            } catch (IOException e) {
                return false;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(output));
            while (true) {
                String line = null;
                try {
                    line = r.readLine();
                    if (connecting && line != null) {
                        if (line.startsWith("connected to") || line.startsWith("already connected")) {
                            Log.v(TAG, "success");
                            connectMode = CONNECT_SUCCESS;
                        }
                        else if (line.toLowerCase().contains("cannot connect") ||
                            line.toLowerCase().contains("failed"))
                            connectMode = CONNECT_FAILED;
                    }
                } catch (IOException e) {
                    return false;
                }
                if (line != null) {
                    outputData += line + "\n";
                    scrollOutput();
                }
                else
                    break;
            }
            if (connecting && connectMode == CONNECT_FAILED) {
                updateAddressPort();
                return false;
            }
        }
        updateAddressPort();
        return true;
    }

    private boolean adbrun(String... cmds) {
        return adbrun_array((String[])cmds);
    }

    private void scrollOutput() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                output.setText(outputData);
                outputScroller.post(new Runnable() {
                    @Override
                    public void run() {
                        outputScroller.smoothScrollTo(0, output.getBottom());
                    }
                });
                // outputScroller.getHeight());
            }
        });
    }

/*    public void devSettings(View view) {
        Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >=24) i.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
        startActivity(i);
    } */

    public boolean wifi() {
        if (!wifiManager.isWifiEnabled())
            return false;
        ConnectivityManager c = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        return c.getNetworkInfo(ConnectivityManager.TYPE_WIFI).isConnected();
    }

    public void pair(View view) {
        String cmd1 = "adb kill-server";
        String cmd2 = "adb pair "+"127.0.0.1"/*address.getHostName()*/+":"+pairPortField.getText()+" "+String.valueOf(pinField.getText());
        String cmd3 = "adb connect " + getName(port);
        String cmd4 = ADB_GRANT;
        String cmd5 = "adb tcpip 5555";

        new Thread(new Runnable() {
            @Override
            public void run() {
                if (port < 0)
                    adbrun(cmd1,cmd2);
                else
                    adbrun(cmd1,cmd2,cmd3,cmd4,cmd5);
//                checkPermissions();
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
