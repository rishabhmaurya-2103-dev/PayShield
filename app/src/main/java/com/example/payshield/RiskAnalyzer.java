package com.example.payshield;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RiskAnalyzer {

    private static final int THRESHOLD_MEDIUM = 40;
    private static final int THRESHOLD_HIGH = 70;

    private static final int SCORE_HIGH_AMOUNT = 35;
    private static final int SCORE_NEW_RECEIVER = 20;
    private static final int SCORE_SUSPICIOUS_WORD = 20;
    private static final int SCORE_BLACKLISTED_UPI = 40;
    private static final int SCORE_REPORTED_THREAT = 15;
    private static final int SCORE_UNKNOWN_UPI = 10;

    private static final double HIGH_AMOUNT_LIMIT = 10000.0;

    private static final List<String> SUSPICIOUS_KEYWORDS = Arrays.asList(
            "lottery",
            "reward",
            "cashback",
            "offer",
            "gift",
            "claim",
            "refund",
            "support",
            "prize",
            "win"
    );

    private static final List<String> BLACKLISTED_UPIS = Arrays.asList(
            "scam@upi",
            "fraud@okaxis",
            "fakepay@oksbi",
            "rewardclaim@okicici"
    );

    public static class RiskResult {
        public final int score;
        public final String riskLevel;
        public final List<String> reasons;
        public final String reasonsText;
        public final String receiverUpiId;
        public final int reportCount;

        public RiskResult(int score, String riskLevel, List<String> reasons, String receiverUpiId, int reportCount) {
            this.score = score;
            this.riskLevel = riskLevel;
            this.reasons = reasons;
            this.reasonsText = buildReasonsText(reasons);
            this.receiverUpiId = receiverUpiId;
            this.reportCount = reportCount;
        }

        private static String buildReasonsText(List<String> reasons) {
            if (reasons == null || reasons.isEmpty()) {
                return "• No major warning signs found";
            }

            StringBuilder sb = new StringBuilder();
            for (String reason : reasons) {
                sb.append("• ").append(reason).append("\n");
            }
            return sb.toString().trim();
        }
    }

    public static RiskResult analyze(String receiverUpiId,
                                     String amountStr,
                                     boolean isNewReceiver,
                                     int reportCount) {

        int score = 0;
        List<String> reasons = new ArrayList<>();

        double amount = parseAmount(amountStr);
        String cleanedUpi = normalizeUpi(receiverUpiId);

        if (amount > HIGH_AMOUNT_LIMIT) {
            score += SCORE_HIGH_AMOUNT;
            reasons.add("High transaction amount");
        }

        if (isNewReceiver) {
            score += SCORE_NEW_RECEIVER;
            reasons.add("First-time or unknown receiver");
        }

        if (isValidUpi(cleanedUpi) && hasSuspiciousKeyword(cleanedUpi)) {
            score += SCORE_SUSPICIOUS_WORD;
            reasons.add("Suspicious keyword found in receiver UPI ID");
        }

        if (isValidUpi(cleanedUpi) && isBlacklistedUpi(cleanedUpi)) {
            score += SCORE_BLACKLISTED_UPI;
            reasons.add("Receiver UPI ID matched fraud watchlist");
        }

        if (reportCount > 0) {
            score += Math.min(40, SCORE_REPORTED_THREAT + (reportCount * 5));
            reasons.add("Reported as threat " + reportCount + " time(s)");
        }

        if (!isValidUpi(cleanedUpi)) {
            score += SCORE_UNKNOWN_UPI;
            reasons.add("Receiver UPI ID could not be verified");
        }

        score = Math.min(score, 100);

        String riskLevel = getRiskLevel(score);

        if (reasons.isEmpty()) {
            reasons.add("No major warning signs found");
        }

        return new RiskResult(score, riskLevel, reasons, cleanedUpi, reportCount);
    }

    private static double parseAmount(String amountStr) {
        if (amountStr == null || amountStr.isEmpty()) {
            return 0.0;
        }

        try {
            String cleaned = amountStr
                    .replace("₹", "")
                    .replace("Rs.", "")
                    .replace("Rs", "")
                    .replace("rs.", "")
                    .replace("rs", "")
                    .replace(",", "")
                    .trim();

            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static String normalizeUpi(String upiId) {
        if (upiId == null) return null;
        return upiId.trim().toLowerCase();
    }

    private static boolean isValidUpi(String upiId) {
        if (upiId == null) return false;
        if (upiId.isEmpty()) return false;
        if (upiId.equals("unknown@upi")) return false;
        if (upiId.equals("not_found")) return false;
        if (upiId.equals("null")) return false;
        if (!upiId.contains("@")) return false;

        return upiId.matches("\\b[a-zA-Z0-9._-]{2,}@[a-zA-Z]{2,}\\b");
    }

    private static boolean hasSuspiciousKeyword(String upiId) {
        if (!isValidUpi(upiId)) return false;

        String lowerUpiId = upiId.toLowerCase();

        for (String keyword : SUSPICIOUS_KEYWORDS) {
            if (lowerUpiId.contains(keyword)) return true;
        }

        return false;
    }

    private static boolean isBlacklistedUpi(String upiId) {
        if (!isValidUpi(upiId)) return false;

        String lowerUpiId = upiId.toLowerCase().trim();

        for (String blocked : BLACKLISTED_UPIS) {
            if (lowerUpiId.equals(blocked)) return true;
        }

        return false;
    }

    private static String getRiskLevel(int score) {
        if (score >= THRESHOLD_HIGH) {
            return "HIGH";
        } else if (score >= THRESHOLD_MEDIUM) {
            return "MEDIUM";
        } else {
            return "LOW";
        }
    }
}