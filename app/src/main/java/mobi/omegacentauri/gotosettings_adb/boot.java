package mobi.omegacentauri.gotosettings_adb;

import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import java.util.Timer;
import java.util.TimerTask;

public class boot extends BroadcastReceiver {

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.v("gotosettings_adb", "on boot");
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                // this code will be executed after 2 seconds
//                Settings.Global.putInt(
//                        context.getContentResolver(),
//                        "adb_wifi_enabled", 1);
            }
        }, 4000);
//        Toast.makeText(context, "ehllo", Toast.LENGTH_LONG).show();

//        MyChrono.clearSaved(options);
    }
}

