package com.example.payshield;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

public class AppUserManager {

    private static final String PREF_NAME = "payshield_prefs";
    private static final String KEY_APP_USER_ID = "app_user_id";

    public static String getAppUserId(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String appUserId = prefs.getString(KEY_APP_USER_ID, null);

        if (appUserId == null || appUserId.trim().isEmpty()) {
            appUserId = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_APP_USER_ID, appUserId).apply();
        }

        return appUserId;
    }
}