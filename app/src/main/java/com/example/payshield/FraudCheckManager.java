package com.example.payshield;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

public class FraudCheckManager {

    private static final String TAG = "FraudCheckManager";

    private static final String DB_URL =
            "https://payshield-8a0ad-default-rtdb.asia-southeast1.firebasedatabase.app/";

    public interface FraudCheckCallback {
        void onResult(int reportCount);
        void onFailure(String errorMessage);
    }

    public static void getReportCount(String upiId, FraudCheckCallback callback) {
        if (upiId == null || upiId.trim().isEmpty() || upiId.equalsIgnoreCase("not_found")) {
            if (callback != null) callback.onResult(0);
            return;
        }

        String cleanUpi = upiId.trim().toLowerCase();
        String firebaseKey = sanitizeKey(cleanUpi);

        try {
            FirebaseDatabase database = FirebaseDatabase.getInstance(DB_URL);
            DatabaseReference ref = database
                    .getReference("fraud_upis")
                    .child(firebaseKey);

            ref.addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snapshot) {
                    int reportCount = 0;

                    if (snapshot.exists()) {
                        Long directCount = snapshot.child("reportCount").getValue(Long.class);

                        if (directCount != null) {
                            reportCount = directCount.intValue();
                        } else if (snapshot.child("reporters").exists()) {
                            reportCount = (int) snapshot.child("reporters").getChildrenCount();
                        }
                    }

                    Log.d(TAG, "Fetched report count for " + cleanUpi + ": " + reportCount);

                    if (callback != null) callback.onResult(reportCount);
                }

                @Override
                public void onCancelled(@NonNull DatabaseError error) {
                    Log.e(TAG, "Fraud count fetch failed", error.toException());
                    if (callback != null) {
                        callback.onFailure(error.getMessage() == null ? "Firebase read failed" : error.getMessage());
                    }
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Fraud check exception", e);
            if (callback != null) {
                callback.onFailure(e.getMessage() == null ? "Unknown Firebase error" : e.getMessage());
            }
        }
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