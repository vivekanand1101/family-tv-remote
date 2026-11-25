package com.vivekanand.familytvcompanion;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class TvAppController {
    private final Context context;
    private final PackageManager packageManager;

    TvAppController(Context context) {
        this.context = context.getApplicationContext();
        this.packageManager = this.context.getPackageManager();
    }

    JSONArray list() {
        JSONArray result = new JSONArray();
        List<LaunchableApp> apps = new ArrayList<>(launchableApps().values());
        apps.sort(Comparator.comparing(value -> value.label, String.CASE_INSENSITIVE_ORDER));
        for (LaunchableApp app : apps) {
            JSONObject value = new JSONObject();
            put(value, "id", app.packageName);
            put(value, "name", app.label);
            String icon = iconDataUrl(app.packageName);
            if (icon != null) put(value, "icon", icon);
            result.put(value);
        }
        return result;
    }

    JSONObject launch(String packageName) {
        if (packageName == null || packageName.isBlank()) return result(false, "App is required");
        LaunchableApp app = launchableApps().get(packageName);
        if (app == null) return result(false, "App is not available on this TV");
        try {
            Intent intent = packageManager.getLeanbackLaunchIntentForPackage(packageName);
            if (intent == null) intent = packageManager.getLaunchIntentForPackage(packageName);
            if (intent == null) {
                intent = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setComponent(new ComponentName(app.packageName, app.activityName));
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(intent);
            return result(true, app.label + " opened");
        } catch (Exception error) {
            return result(false, error.getMessage() == null ? "Could not open app" : error.getMessage());
        }
    }

    private Map<String, LaunchableApp> launchableApps() {
        Map<String, LaunchableApp> apps = new LinkedHashMap<>();
        addActivities(apps, Intent.CATEGORY_LEANBACK_LAUNCHER);
        addActivities(apps, Intent.CATEGORY_LAUNCHER);
        apps.remove(context.getPackageName());
        return apps;
    }

    private void addActivities(Map<String, LaunchableApp> apps, String category) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
        List<ResolveInfo> activities = new ArrayList<>(packageManager.queryIntentActivities(query, 0));
        for (ResolveInfo info : activities) {
            if (info.activityInfo == null || info.activityInfo.packageName == null) continue;
            String packageName = info.activityInfo.packageName;
            if (!isUserFacing(packageName)) continue;
            CharSequence loadedLabel = info.loadLabel(packageManager);
            String label = loadedLabel == null || loadedLabel.toString().isBlank()
                ? packageName
                : loadedLabel.toString().trim();
            apps.putIfAbsent(packageName, new LaunchableApp(packageName, info.activityInfo.name, label));
        }
    }

    private String iconDataUrl(String packageName) {
        try {
            Drawable drawable = packageManager.getApplicationIcon(packageName);
            int size = 128;
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            int width = Math.max(1, drawable.getIntrinsicWidth());
            int height = Math.max(1, drawable.getIntrinsicHeight());
            float scale = Math.min((float) size / width, (float) size / height);
            int drawnWidth = Math.round(width * scale);
            int drawnHeight = Math.round(height * scale);
            int left = (size - drawnWidth) / 2;
            int top = (size - drawnHeight) / 2;
            drawable.setBounds(left, top, left + drawnWidth, top + drawnHeight);
            drawable.draw(canvas);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
            bitmap.recycle();
            return "data:image/png;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isUserFacing(String packageName) {
        return !packageName.equals(context.getPackageName())
            && !packageName.equals("com.android.settings")
            && !packageName.equals("com.android.tv.settings")
            && !packageName.startsWith("com.mediatek.")
            && !packageName.startsWith("com.sony.dtv.");
    }

    private static JSONObject result(boolean ok, String message) {
        JSONObject value = new JSONObject();
        put(value, "ok", ok);
        put(value, "message", message);
        return value;
    }

    private static void put(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static final class LaunchableApp {
        final String packageName;
        final String activityName;
        final String label;

        LaunchableApp(String packageName, String activityName, String label) {
            this.packageName = packageName;
            this.activityName = activityName;
            this.label = label;
        }
    }
}
