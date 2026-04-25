package com.example.payshield;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class OverlayManager {

    private static final String TAG = "OverlayManager";

    private static View overlayView = null;
    private static boolean isShowing = false;

    private static String lastOverlaySignature = null;
    private static long lastOverlayShownAt = 0L;

    private static final long DUPLICATE_OVERLAY_COOLDOWN_MS = 10000L;

    public static void showOverlay(Context context,
                                   int riskScore,
                                   String riskLevel,
                                   String receiverUpiId,
                                   String name,
                                   String amount,
                                   int reportCount) {

        if (!Settings.canDrawOverlays(context)) {
            Toast.makeText(context, "Overlay permission not granted", Toast.LENGTH_SHORT).show();
            return;
        }

        String safeUpi = (receiverUpiId == null || receiverUpiId.trim().isEmpty())
                ? "not_found" : receiverUpiId.trim();
        String safeAmount = (amount == null || amount.trim().isEmpty())
                ? "Unknown" : amount.trim();
        String safeName = (name == null || name.trim().isEmpty())
                ? "Unknown Receiver" : name.trim();

        String currentSignature = buildOverlaySignature(
                riskLevel, safeUpi, safeName, safeAmount, reportCount
        );
        long now = System.currentTimeMillis();

        if (isDuplicateOverlay(currentSignature, now)) {
            Log.d(TAG, "Duplicate overlay suppressed: " + currentSignature);
            return;
        }

        if (overlayView != null && isShowing) {
            Log.d(TAG, "Overlay already showing");
            return;
        }

        try {
            WindowManager windowManager =
                    (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);

            LayoutInflater inflater = LayoutInflater.from(context);
            overlayView = inflater.inflate(R.layout.overlay_warning, null);

            TextView tvTitle = overlayView.findViewById(R.id.tvOverlayTitle);
            TextView tvScore = overlayView.findViewById(R.id.tvOverlayScore);
            TextView tvMessage = overlayView.findViewById(R.id.tvOverlayMessage);
            Button btnReportFraud = overlayView.findViewById(R.id.btnReportFraud);
            Button btnClose = overlayView.findViewById(R.id.btnCloseOverlay);

            String crowdStatus = reportCount > 0
                    ? "Reported by " + reportCount + " user(s)"
                    : "No fraud reports yet";

            String riskMessage;
            if ("HIGH".equalsIgnoreCase(riskLevel)) {
                tvTitle.setText("🚨 HIGH RISK");
                tvTitle.setTextColor(0xFFD32F2F);
                riskMessage = "This payment looks suspicious. Avoid proceeding unless you fully trust the receiver.";
            } else if ("MEDIUM".equalsIgnoreCase(riskLevel)) {
                tvTitle.setText("⚠ MEDIUM RISK");
                tvTitle.setTextColor(0xFFF9A825);
                riskMessage = "Please verify the receiver details carefully before entering your PIN.";
            } else {
                tvTitle.setText("✅ LOW RISK");
                tvTitle.setTextColor(0xFF2E7D32);
                riskMessage = "No strong warning signs were found, but always verify before paying.";
            }

            tvScore.setText("Risk Score: " + riskScore + " | Level: " + riskLevel);

            String message =
                    "Receiver: " + safeName +
                            "\nUPI ID: " + safeUpi +
                            "\nAmount: " + safeAmount +
                            "\nCrowd Signal: " + crowdStatus +
                            "\n\n" + riskMessage;

            tvMessage.setText(message);

            if (btnClose != null) {
                btnClose.setOnClickListener(v -> hideOverlay(context));
            }

            if (btnReportFraud != null) {
                btnReportFraud.setOnClickListener(v -> {
                    Log.d(TAG, "Report Fraud clicked for UPI: " + safeUpi);

                    if (!isReportableUpi(safeUpi)) {
                        Toast.makeText(context, "No valid receiver UPI ID found", Toast.LENGTH_SHORT).show();
                        hideOverlay(context);
                        return;
                    }

                    btnReportFraud.setEnabled(false);

                    CloudFraudReportManager.reportFraudUpi(
                            context,
                            safeUpi,
                            new CloudFraudReportManager.ReportCallback() {
                                @Override
                                public void onSuccess() {
                                    Log.d(TAG, "Fraud UPI saved successfully: " + safeUpi);
                                    Toast.makeText(context, "Fraud UPI reported successfully", Toast.LENGTH_SHORT).show();
                                    hideOverlay(context);
                                }

                                @Override
                                public void onDuplicateReport() {
                                    Log.d(TAG, "Duplicate fraud report blocked for: " + safeUpi);
                                    Toast.makeText(context, "You already reported this UPI", Toast.LENGTH_SHORT).show();
                                    hideOverlay(context);
                                }

                                @Override
                                public void onFailure(String errorMessage) {
                                    Log.e(TAG, "Failed to save fraud UPI: " + errorMessage);
                                    Toast.makeText(context, "Report failed: " + errorMessage, Toast.LENGTH_SHORT).show();
                                    btnReportFraud.setEnabled(true);
                                }
                            }
                    );
                });
            }

            int layoutType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
            );

            params.gravity = Gravity.TOP;
            params.y = 120;

            windowManager.addView(overlayView, params);
            isShowing = true;
            lastOverlaySignature = currentSignature;
            lastOverlayShownAt = now;

            Log.d(TAG, "Overlay shown");

        } catch (Exception e) {
            Log.e(TAG, "Overlay crash", e);
            overlayView = null;
            isShowing = false;
        }
    }

    public static void hideOverlay(Context context) {
        if (overlayView != null && isShowing) {
            try {
                WindowManager windowManager =
                        (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
                windowManager.removeView(overlayView);
                Log.d(TAG, "Overlay hidden");
            } catch (Exception e) {
                Log.e(TAG, "Remove overlay error", e);
            }
        }

        overlayView = null;
        isShowing = false;
    }

    private static boolean isDuplicateOverlay(String currentSignature, long now) {
        if (currentSignature == null) return false;
        if (lastOverlaySignature == null) return false;
        if (!currentSignature.equals(lastOverlaySignature)) return false;
        return (now - lastOverlayShownAt) < DUPLICATE_OVERLAY_COOLDOWN_MS;
    }

    private static String buildOverlaySignature(String riskLevel,
                                                String upi,
                                                String name,
                                                String amount,
                                                int reportCount) {
        return (riskLevel == null ? "" : riskLevel.trim().toLowerCase()) + "|"
                + (upi == null ? "" : upi.trim().toLowerCase()) + "|"
                + (name == null ? "" : name.trim().toLowerCase()) + "|"
                + (amount == null ? "" : amount.trim().toLowerCase()) + "|"
                + reportCount;
    }

    private static boolean isReportableUpi(String upi) {
        if (upi == null) return false;

        String lower = upi.trim().toLowerCase();

        if (lower.isEmpty()) return false;
        if (lower.equals("not_found")) return false;
        if (lower.equals("unknown@upi")) return false;
        if (lower.equals("null")) return false;
        if (!lower.contains("@")) return false;
        if (lower.endsWith("@gmail")) return false;
        if (lower.endsWith("@yahoo")) return false;
        if (lower.endsWith("@outlook")) return false;
        if (lower.endsWith("@hotmail")) return false;

        return lower.matches("\\b[a-zA-Z0-9._-]{2,}@[a-zA-Z]{2,}\\b");
    }
}