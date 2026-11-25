package com.vivekanand.familytvcompanion;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;

/** A short-lived activity whose only job is to turn the Sony display on. */
public final class WakeActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestScreenOn();
        CompanionService.start(this);
        new Handler(Looper.getMainLooper()).postDelayed(this::finish, 1500);
    }

    @SuppressWarnings("deprecation")
    private void requestScreenOn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) setTurnScreenOn(true);
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
    }
}
