package com.vivekanand.familytvcompanion;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CompanionService.start(this);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(64, 48, 64, 48);
        layout.setBackgroundColor(Color.rgb(242, 246, 241));

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextColor(Color.rgb(23, 48, 33));
        title.setTextSize(34);
        title.setGravity(Gravity.CENTER);

        TextView status = new TextView(this);
        status.setText(R.string.ready_message);
        status.setTextColor(Color.rgb(77, 98, 84));
        status.setTextSize(20);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 28, 0, 0);

        layout.addView(title);
        layout.addView(status);
        setContentView(layout);
    }
}
