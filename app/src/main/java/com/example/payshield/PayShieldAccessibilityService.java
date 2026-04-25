package com.example.payshield;

import android.accessibilityservice.AccessibilityService;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PayShieldAccessibilityService extends AccessibilityService {

    private static final String TAG = "PayShieldService";

    private static final long PROCESS_DEBOUNCE_MS = 700;
    private static final long SAME_PAYMENT_COOLDOWN_MS = 12000;
    private static final double MIN_TRIGGER_AMOUNT = 1.0;

    private boolean analysisDoneForCurrentPayment = false;
    private String lastAnalyzedSignature = "";

    private String storedReceiverUpi = null;
    private String storedReceiverName = null;

    private long lastProcessingTime = 0L;
    private long lastAnalysisTime = 0L;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "PayShield Accessibility Service connected.");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;

        String packageName = event.getPackageName().toString();
        if (!isSupportedPaymentApp(packageName)) return;

        long now = SystemClock.elapsedRealtime();
        if (now - lastProcessingTime < PROCESS_DEBOUNCE_MS) {
            return;
        }
        lastProcessingTime = now;

        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return;

        List<String> allTexts = new ArrayList<>();
        collectAllTexts(rootNode, allTexts);
        collectEventTexts(event, allTexts);

        String fullText = joinTexts(allTexts).toLowerCase(Locale.ROOT);

        boolean homeScreen = isClearlyHomeScreen(fullText);
        boolean newPaymentFlow = isNewPaymentFlow(fullText);

        String amount = extractAmount(allTexts);
        double amountValue = parseAmountValue(amount);

        boolean paymentScreen = isPaymentScreen(fullText, amountValue);
        boolean pinEntryScreen = isPinEntryScreen(fullText);

        if (homeScreen && amountValue < MIN_TRIGGER_AMOUNT) {
            Log.d(TAG, "Home screen detected, skipping overlay logic");
            return;
        }

        if (newPaymentFlow && !paymentScreen) {
            resetPaymentState();
        }

        extractAndStoreReceiverDetails(allTexts, paymentScreen, fullText);

        if (pinEntryScreen) {
            Log.d(TAG, "PIN/password screen detected, skipping overlay");
            return;
        }

        if (!paymentScreen) {
            Log.d(TAG, "Not a payment screen yet");
            return;
        }

        Log.d(TAG, "Payment screen detected");
        Log.d(TAG, "Package: " + packageName);

        String visibleUpi = extractUpiFromTexts(allTexts);
        String finalUpi = pickBestUpi(visibleUpi, storedReceiverUpi);
        String name = sanitizeName(storedReceiverName);

        Log.d(TAG, "Current visible extracted UPI: " + visibleUpi);
        Log.d(TAG, "Using stored receiver UPI only: " + storedReceiverUpi);
        Log.d(TAG, "Final chosen UPI: " + finalUpi);
        Log.d(TAG, "Using amount: " + amount);
        Log.d(TAG, "Stored receiver name: " + name);

        if (amount == null || amountValue < MIN_TRIGGER_AMOUNT) {
            Log.d(TAG, "Skipping overlay - amount missing or less than ₹1");
            return;
        }

        String safeUpiForAnalysis = finalUpi == null ? "not_found" : finalUpi;

        String safeNameForOverlay;
        if (name == null || name.equalsIgnoreCase("recommended")) {
            safeNameForOverlay = "UPI User";
        } else {
            safeNameForOverlay = name;
        }

        String signature = buildPaymentSignature(packageName, safeUpiForAnalysis, amount, safeNameForOverlay);

        if (analysisDoneForCurrentPayment && signature.equals(lastAnalyzedSignature)) {
            Log.d(TAG, "Analysis already done for current payment, skipping");
            return;
        }

        if (signature.equals(lastAnalyzedSignature) && (now - lastAnalysisTime) < SAME_PAYMENT_COOLDOWN_MS) {
            Log.d(TAG, "Same payment detected in cooldown, skipping duplicate overlay");
            return;
        }

        lastAnalyzedSignature = signature;
        lastAnalysisTime = now;
        analysisDoneForCurrentPayment = true;

        FraudCheckManager.getReportCount(safeUpiForAnalysis, new FraudCheckManager.FraudCheckCallback() {
            @Override
            public void onResult(int reportCount) {
                Log.d(TAG, "Dynamic Firebase report count: " + reportCount);

                RiskAnalyzer.RiskResult result = RiskAnalyzer.analyze(
                        safeUpiForAnalysis,
                        amount,
                        true,
                        reportCount
                );

                OverlayManager.showOverlay(
                        getApplicationContext(),
                        result.score,
                        result.riskLevel,
                        safeUpiForAnalysis,
                        safeNameForOverlay,
                        amount,
                        reportCount
                );
            }

            @Override
            public void onFailure(String errorMessage) {
                Log.e(TAG, "Fraud count fetch failed: " + errorMessage);

                RiskAnalyzer.RiskResult result = RiskAnalyzer.analyze(
                        safeUpiForAnalysis,
                        amount,
                        true,
                        0
                );

                OverlayManager.showOverlay(
                        getApplicationContext(),
                        result.score,
                        result.riskLevel,
                        safeUpiForAnalysis,
                        safeNameForOverlay,
                        amount,
                        0
                );
            }
        });
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "Accessibility Service interrupted.");
    }

    private void resetPaymentState() {
        analysisDoneForCurrentPayment = false;
        lastAnalyzedSignature = "";
        storedReceiverUpi = null;
        storedReceiverName = null;
        lastAnalysisTime = 0L;
        Log.d(TAG, "Reset payment state for new flow");
    }

    private boolean isSupportedPaymentApp(String packageName) {
        return packageName.contains("paisa")
                || packageName.contains("phonepe")
                || packageName.contains("paytm")
                || packageName.contains("bhim")
                || packageName.contains("amazon.mShop")
                || packageName.contains("cred");
    }

    private boolean isNewPaymentFlow(String text) {
        return text.contains("pay anyone on upi")
                || text.contains("enter upi id")
                || text.contains("scan any qr")
                || text.contains("search phone number")
                || text.contains("bank transfer")
                || text.contains("to mobile number")
                || text.contains("to bank account")
                || text.contains("choose bank account")
                || text.contains("new payment");
    }

    private boolean isClearlyHomeScreen(String text) {
        int score = 0;
        if (text.contains("people")) score++;
        if (text.contains("rewards")) score++;
        if (text.contains("upi lite")) score++;
        if (text.contains("activate")) score++;
        if (text.contains("home")) score++;
        if (text.contains("money")) score++;
        if (text.contains("you")) score++;
        if (text.contains("pay by name or phone")) score++;
        if (text.contains("businesses")) score++;
        if (text.contains("bill payments")) score++;
        return score >= 2;
    }

    private boolean hasStrongPaymentConfirmation(String text) {
        return text.contains("confirm payment")
                || text.contains("proceed to pay")
                || text.contains("sending money")
                || text.contains("pay ₹")
                || text.contains("pay rs")
                || text.contains("paying")
                || text.contains("masked account number");
    }

    private boolean isPinEntryScreen(String text) {
        return text.contains("enter pin")
                || text.contains("google pin")
                || text.contains("upi pin")
                || text.contains("never enter your upi pin")
                || text.contains("enter 6-digit pin")
                || text.contains("password")
                || text.contains("passcode");
    }

    private boolean isPaymentScreen(String text, double amountValue) {
        return hasStrongPaymentConfirmation(text) && amountValue >= MIN_TRIGGER_AMOUNT;
    }

    private String buildPaymentSignature(String packageName, String upi, String amount, String name) {
        return (packageName == null ? "" : packageName.trim().toLowerCase(Locale.ROOT)) + "|"
                + (upi == null ? "" : upi.trim().toLowerCase(Locale.ROOT)) + "|"
                + (amount == null ? "" : amount.trim().toLowerCase(Locale.ROOT)) + "|"
                + (name == null ? "" : name.trim().toLowerCase(Locale.ROOT));
    }

    private void extractAndStoreReceiverDetails(List<String> texts, boolean paymentScreen, String fullText) {
        String upi = extractUpiFromTexts(texts);
        String name = extractPossibleReceiverName(texts);

        if (isValidReceiverUpi(upi) && !looksLikeOwnOrGenericUpi(upi, fullText, paymentScreen)) {
            storedReceiverUpi = upi;
            Log.d(TAG, "Stored receiver UPI from early screen: " + storedReceiverUpi);
        } else if (upi != null) {
            Log.d(TAG, "Ignored generic/self UPI: " + upi);
        }

        if (isUsefulName(name) && looksLikeRealReceiverName(name, paymentScreen)) {
            storedReceiverName = name;
            Log.d(TAG, "Stored receiver name from early screen: " + storedReceiverName);
        } else if (name != null) {
            Log.d(TAG, "Ignored generic name: " + name);
        }
    }

    private boolean looksLikeOwnOrGenericUpi(String upi, String fullTextLower, boolean paymentScreen) {
        if (upi == null) return true;

        String lower = upi.toLowerCase(Locale.ROOT).trim();

        if (lower.endsWith("@gmail")) return true;
        if (!paymentScreen && fullTextLower.contains("people")) return true;
        if (!paymentScreen && fullTextLower.contains("home")) return true;

        return false;
    }

    private String pickBestUpi(String visibleUpi, String storedUpi) {
        if (isValidReceiverUpi(visibleUpi) && !visibleUpi.endsWith("@gmail")) return visibleUpi;
        if (isValidReceiverUpi(storedUpi) && !storedUpi.endsWith("@gmail")) return storedUpi;
        return null;
    }

    private String sanitizeName(String name) {
        if (!isUsefulName(name)) return null;

        String cleaned = name.trim().replaceAll("\\s+", " ");
        if (cleaned.equalsIgnoreCase("recommended")) return null;

        if (cleaned.toLowerCase(Locale.ROOT).startsWith("to ")) {
            return cleaned;
        }
        return cleaned;
    }

    private void collectAllTexts(AccessibilityNodeInfo node, List<String> texts) {
        if (node == null) return;

        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();

        if (text != null) {
            String t = text.toString().trim();
            if (!t.isEmpty()) texts.add(t);
        }

        if (desc != null) {
            String d = desc.toString().trim();
            if (!d.isEmpty()) texts.add(d);
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            collectAllTexts(node.getChild(i), texts);
        }
    }

    private void collectEventTexts(AccessibilityEvent event, List<String> texts) {
        if (event.getText() != null) {
            for (CharSequence cs : event.getText()) {
                if (cs != null) {
                    String t = cs.toString().trim();
                    if (!t.isEmpty()) texts.add(t);
                }
            }
        }

        CharSequence contentDesc = event.getContentDescription();
        if (contentDesc != null) {
            String d = contentDesc.toString().trim();
            if (!d.isEmpty()) texts.add(d);
        }
    }

    private String joinTexts(List<String> texts) {
        StringBuilder sb = new StringBuilder();
        for (String s : texts) {
            sb.append(s).append(" ");
        }
        return sb.toString().trim();
    }

    private String extractUpiFromTexts(List<String> texts) {
        Pattern upiPattern = Pattern.compile("\\b[a-zA-Z0-9._-]{2,}@[a-zA-Z]{2,}\\b");

        for (String text : texts) {
            Matcher matcher = upiPattern.matcher(text);
            while (matcher.find()) {
                String candidate = matcher.group().trim().toLowerCase(Locale.ROOT);

                if (isValidReceiverUpi(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private boolean isValidUpi(String upi) {
        if (upi == null) return false;

        upi = upi.trim().toLowerCase(Locale.ROOT);

        if (upi.isEmpty()) return false;
        if (upi.equals("unknown@upi")) return false;
        if (upi.equals("not_found")) return false;
        if (upi.equals("null")) return false;
        if (!upi.contains("@")) return false;

        return upi.matches("\\b[a-zA-Z0-9._-]{2,}@[a-zA-Z]{2,}\\b");
    }

    private boolean isValidReceiverUpi(String upi) {
        if (!isValidUpi(upi)) return false;

        String lower = upi.toLowerCase(Locale.ROOT);

        if (lower.endsWith("@gmail")) return false;
        if (lower.endsWith("@yahoo")) return false;
        if (lower.endsWith("@outlook")) return false;
        if (lower.endsWith("@hotmail")) return false;

        return true;
    }

    private String extractAmount(List<String> texts) {
        Pattern amountPattern = Pattern.compile("₹\\s?\\d+(?:[.,]\\d{1,2})?");

        for (String text : texts) {
            Matcher matcher = amountPattern.matcher(text);
            while (matcher.find()) {
                String amt = matcher.group().trim();
                double value = parseAmountValue(amt);
                if (value >= MIN_TRIGGER_AMOUNT) {
                    return amt;
                }
            }
        }
        return null;
    }

    private double parseAmountValue(String amount) {
        if (amount == null) return 0.0;

        try {
            String cleaned = amount.replace("₹", "").replace(",", "").trim();
            return Double.parseDouble(cleaned);
        } catch (Exception e) {
            return 0.0;
        }
    }

    private String extractPossibleReceiverName(List<String> texts) {
        String bestCandidate = null;

        for (String text : texts) {
            String cleaned = text.trim().replace("\n", " ");

            if (cleaned.length() < 3 || cleaned.length() > 50) continue;
            if (cleaned.contains("@")) continue;
            if (cleaned.matches(".*\\d.*")) continue;

            String lower = cleaned.toLowerCase(Locale.ROOT);

            if (isIgnoredLabel(lower)) continue;

            if (cleaned.matches("[A-Z][a-z]+")) {
                return cleaned;
            }

            if (bestCandidate == null) {
                bestCandidate = cleaned;
            }
        }

        return bestCandidate;
    }

    private boolean looksLikeRealReceiverName(String name, boolean paymentScreen) {
        if (name == null) return false;

        String lower = name.toLowerCase(Locale.ROOT).trim();

        if (isIgnoredLabel(lower)) return false;

        if (lower.contains("state bank of india")
                || lower.contains("sbi")
                || lower.contains("ippb")
                || lower.contains("account")
                || lower.contains("bank")
                || lower.contains("number")
                || lower.contains("masked")
                || lower.contains("menu")
                || lower.contains("mobile")
                || lower.contains("backspace")
                || lower.contains("gallery")
                || lower.contains("flashlight")
                || lower.contains("expand")
                || lower.contains("clean up")
                || lower.contains("recommended")) {
            return false;
        }

        return paymentScreen;
    }

    private boolean isIgnoredLabel(String lower) {
        return lower.contains("pay")
                || lower.contains("payment")
                || lower.contains("profile")
                || lower.contains("upi")
                || lower.contains("enter")
                || lower.contains("pin")
                || lower.contains("proceed")
                || lower.contains("continue")
                || lower.contains("scan")
                || lower.contains("qr")
                || lower.contains("code")
                || lower.contains("logo")
                || lower.contains("close")
                || lower.contains("show menu")
                || lower.contains("menu")
                || lower.contains("google")
                || lower.contains("phonepe")
                || lower.contains("paytm")
                || lower.contains("bhim")
                || lower.contains("gpay")
                || lower.contains("rewards")
                || lower.contains("activate")
                || lower.contains("money")
                || lower.contains("home")
                || lower.contains("you")
                || lower.contains("people")
                || lower.contains("clock")
                || lower.contains("support")
                || lower.contains("show more")
                || lower.contains("masked")
                || lower.contains("mobile")
                || lower.contains("ippb")
                || lower.contains("state bank of india")
                || lower.contains("sbi")
                || lower.contains("account")
                || lower.contains("bank")
                || lower.contains("number")
                || lower.contains("backspace")
                || lower.contains("upload from gallery")
                || lower.contains("turn on the flashlight")
                || lower.contains("flashlight")
                || lower.contains("gallery")
                || lower.contains("expand")
                || lower.contains("clean up")
                || lower.contains("recommended")
                || lower.equals("ok");
    }

    private boolean isUsefulName(String name) {
        if (name == null) return false;
        String lower = name.trim().toLowerCase(Locale.ROOT);
        return !lower.isEmpty() && !isIgnoredLabel(lower);
    }
}