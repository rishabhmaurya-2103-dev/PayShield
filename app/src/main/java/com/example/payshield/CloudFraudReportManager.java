package com.example.payshield;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.Transaction;

import java.util.HashMap;
import java.util.Map;

public class CloudFraudReportManager {

    private static final String TAG = "CloudFraudReportManager";

    private static final String DB_URL =
            "https://payshield-8a0ad-default-rtdb.asia-southeast1.firebasedatabase.app/";

    public interface ReportCallback {
        void onSuccess();
        void onDuplicateReport();
        void onFailure(String errorMessage);
    }

    public static void reportFraudUpi(Context context, String upiId, ReportCallback callback) {
        if (context == null) {
            if (callback != null) callback.onFailure("Context is null");
            return;
        }

        if (upiId == null || upiId.trim().isEmpty()) {
            if (callback != null) callback.onFailure("Empty UPI");
            return;
        }

        String cleanUpi = upiId.trim().toLowerCase();
        String firebaseKey = sanitizeKey(cleanUpi);
        String appUserId = AppUserManager.getAppUserId(context);
        String safeUserKey = sanitizeKey(appUserId);
        long now = System.currentTimeMillis();

        Log.d(TAG, "Reporting fraud UPI: " + cleanUpi);
        Log.d(TAG, "Firebase key: " + firebaseKey);
        Log.d(TAG, "App User ID: " + appUserId);

        try {
            FirebaseDatabase database = FirebaseDatabase.getInstance(DB_URL);

            DatabaseReference upiRef = database
                    .getReference("fraud_upis")
                    .child(firebaseKey);

            DatabaseReference reporterRef = upiRef
                    .child("reporters")
                    .child(safeUserKey);

            DatabaseReference appUserRef = database
                    .getReference("app_users")
                    .child(safeUserKey);

            upiRef.child("upiId").setValue(cleanUpi);
            upiRef.child("lastReportedAt").setValue(now);

            reporterRef.runTransaction(new Transaction.Handler() {
                private boolean alreadyReported = false;

                @NonNull
                @Override
                public Transaction.Result doTransaction(@NonNull MutableData currentData) {
                    Object existing = currentData.getValue();

                    if (existing != null) {
                        alreadyReported = true;
                        return Transaction.abort();
                    }

                    Map<String, Object> reporterData = new HashMap<>();
                    reporterData.put("reported", true);
                    reporterData.put("firstReportedAt", now);
                    reporterData.put("lastReportedAt", now);
                    reporterData.put("appUserId", appUserId);

                    currentData.setValue(reporterData);
                    return Transaction.success(currentData);
                }

                @Override
                public void onComplete(DatabaseError error, boolean committed, DataSnapshot currentData) {
                    if (error != null) {
                        Log.e(TAG, "Reporter transaction failed", error.toException());
                        if (callback != null) {
                            callback.onFailure(error.getMessage() == null ? "Transaction failed" : error.getMessage());
                        }
                        return;
                    }

                    if (!committed || alreadyReported) {
                        Log.d(TAG, "Duplicate report blocked for user: " + appUserId + ", UPI: " + cleanUpi);
                        if (callback != null) callback.onDuplicateReport();
                        return;
                    }

                    incrementNode(upiRef.child("reportCount"));
                    incrementNode(appUserRef.child("totalFraudReportsSubmitted"));

                    appUserRef.child("appUserId").setValue(appUserId);
                    appUserRef.child("lastReportAt").setValue(now);

                    Log.d(TAG, "Fraud UPI reported successfully: " + cleanUpi);
                    if (callback != null) callback.onSuccess();
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Firebase exception", e);
            if (callback != null) {
                callback.onFailure(e.getMessage() == null ? "Unknown Firebase error" : e.getMessage());
            }
        }
    }

    private static void incrementNode(DatabaseReference ref) {
        ref.runTransaction(new Transaction.Handler() {
            @NonNull
            @Override
            public Transaction.Result doTransaction(@NonNull MutableData currentData) {
                Long value = currentData.getValue(Long.class);
                if (value == null) value = 0L;
                currentData.setValue(value + 1L);
                return Transaction.success(currentData);
            }

            @Override
            public void onComplete(DatabaseError error, boolean committed, DataSnapshot currentData) {
                if (error != null) {
                    Log.e(TAG, "Increment failed: " + error.getMessage());
                }
            }
        });
    }

    private static String sanitizeKey(String input) {
        return input
                .replace(".", ",")
                .replace("#", "_")
                .replace("$", "_")
                .replace("[", "_")
                .replace("]", "_")
                .replace("/", "_");
    }
}