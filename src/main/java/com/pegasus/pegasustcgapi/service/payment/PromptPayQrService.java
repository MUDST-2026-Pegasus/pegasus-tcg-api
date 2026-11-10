package com.pegasus.pegasustcgapi.service.payment;

import com.pegasus.pegasustcgapi.config.PromptPayProperties;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * Generates dynamic PromptPay QR code payloads adhering to the EMVCo standard
 * and Bank of Thailand PromptPay specifications.
 */
@Service
public class PromptPayQrService {

    private static final String PAYLOAD_FORMAT_INDICATOR = "01";
    private static final String POINT_OF_INITIATION_DYNAMIC = "12";
    private static final String PROMPTPAY_AID = "A000000677010111";
    private static final String THB_CURRENCY = "764";
    private static final String COUNTRY_CODE_TH = "TH";
    private static final String MERCHANT_CITY_BANGKOK = "Bangkok";

    private final PromptPayProperties properties;

    public PromptPayQrService(PromptPayProperties properties) {
        this.properties = properties != null ? properties : new PromptPayProperties(null, null, null, null);
    }

    /**
     * Generates a dynamic PromptPay QR payload string with the specified amount.
     *
     * @param amount the transaction amount (must be positive)
     * @return full EMVCo raw string with CRC-16 checksum
     */
    public String generatePayload(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero");
        }

        String formattedAmount = String.format(Locale.US, "%.2f", amount);
        String targetEmvco = formatPromptPayTarget(properties.target());

        StringBuilder sb = new StringBuilder();

        // Tag 00: Payload Format Indicator ("01")
        appendTlv(sb, "00", PAYLOAD_FORMAT_INDICATOR);

        // Tag 01: Point of Initiation Method ("12" = Dynamic QR)
        appendTlv(sb, "01", POINT_OF_INITIATION_DYNAMIC);

        // Tag 29: Merchant Account Information (PromptPay AID + Target)
        StringBuilder tag29 = new StringBuilder();
        appendTlv(tag29, "00", PROMPTPAY_AID);
        appendTlv(tag29, "01", targetEmvco);
        appendTlv(sb, "29", tag29.toString());

        // Tag 53: Transaction Currency ("764" = THB)
        appendTlv(sb, "53", THB_CURRENCY);

        // Tag 54: Transaction Amount (2 decimal places)
        appendTlv(sb, "54", formattedAmount);

        // Tag 58: Country Code ("TH")
        appendTlv(sb, "58", COUNTRY_CODE_TH);

        // Tag 59: Merchant Name
        appendTlv(sb, "59", properties.name());

        // Tag 60: Merchant City ("Bangkok")
        appendTlv(sb, "60", MERCHANT_CITY_BANGKOK);

        // Tag 63: CRC16-CCITT checksum prefix
        sb.append("6304");

        // Calculate CRC16-CCITT (poly 0x1021, init 0xFFFF) over string through "6304"
        int crc = calculateCrc16Ccitt(sb.toString().getBytes(StandardCharsets.US_ASCII));
        sb.append(String.format(Locale.US, "%04X", crc));

        return sb.toString();
    }

    /**
     * Returns the masked PromptPay ID (e.g., "094-xxx-1119").
     */
    public String maskedPromptPayId() {
        return maskPromptPayId(properties.target());
    }

    /**
     * Normalizes a phone number to EMVCo PromptPay target format (e.g., "0066947761119").
     */
    public static String formatPromptPayTarget(String target) {
        if (target == null || target.isBlank()) {
            return "0066947761119";
        }
        String cleaned = target.replaceAll("[^0-9+]", "");
        if (cleaned.startsWith("+66")) {
            return "0066" + cleaned.substring(3);
        }
        if (cleaned.startsWith("0066")) {
            return cleaned;
        }
        if (cleaned.startsWith("66") && cleaned.length() == 11) {
            return "00" + cleaned;
        }
        if (cleaned.startsWith("0")) {
            return "0066" + cleaned.substring(1);
        }
        if (cleaned.length() == 9) {
            return "0066" + cleaned;
        }
        return cleaned;
    }

    /**
     * Masks a PromptPay phone number target into standard display format (e.g., "094-xxx-1119").
     */
    public static String maskPromptPayId(String target) {
        if (target == null || target.isBlank()) {
            return "094-xxx-1119";
        }
        String cleaned = target.replaceAll("[^0-9]", "");
        if (cleaned.startsWith("0066") && cleaned.length() == 13) {
            cleaned = "0" + cleaned.substring(4);
        } else if (cleaned.startsWith("66") && cleaned.length() == 11) {
            cleaned = "0" + cleaned.substring(2);
        }
        if (cleaned.length() == 10) {
            return cleaned.substring(0, 3) + "-xxx-" + cleaned.substring(6);
        }
        return target;
    }

    /**
     * Computes CRC16-CCITT (polynomial 0x1021, initial value 0xFFFF).
     */
    public static int calculateCrc16Ccitt(byte[] data) {
        int crc = 0xFFFF;
        for (byte b : data) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x8000) != 0) {
                    crc = ((crc << 1) ^ 0x1021) & 0xFFFF;
                } else {
                    crc = (crc << 1) & 0xFFFF;
                }
            }
        }
        return crc & 0xFFFF;
    }

    private static void appendTlv(StringBuilder sb, String tag, String value) {
        if (value == null) {
            value = "";
        }
        sb.append(tag);
        sb.append(String.format(Locale.US, "%02d", value.getBytes(StandardCharsets.UTF_8).length));
        sb.append(value);
    }
}
