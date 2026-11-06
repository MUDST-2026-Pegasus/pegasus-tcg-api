package com.pegasus.pegasustcgapi.service.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pegasus.pegasustcgapi.config.PromptPayProperties;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("PromptPayQrService")
class PromptPayQrServiceTest {

    private PromptPayQrService qrService;
    private PromptPayProperties properties;

    @BeforeEach
    void setUp() {
        properties = new PromptPayProperties(
                "PHONE",
                "0947761119",
                "",
                "PEGASUS TCG"
        );
        qrService = new PromptPayQrService(properties);
    }

    @Nested
    @DisplayName("CRC16-CCITT Checksum Calculation")
    class Crc16Tests {

        @Test
        @DisplayName("Calculates known CRC-16 CCITT test vector for '123456789'")
        void calculatesKnownTestVector() {
            byte[] input = "123456789".getBytes(StandardCharsets.US_ASCII);
            int crc = PromptPayQrService.calculateCrc16Ccitt(input);
            assertThat(String.format("%04X", crc)).isEqualTo("29B1");
        }

        @Test
        @DisplayName("Calculates expected CRC for full PromptPay payload prefix")
        void calculatesPayloadCrc() {
            String payloadWithoutCrc = "00020101021229370016A0000006770101110113006694776111953037645406673.005802TH5911PEGASUS TCG6007Bangkok6304";
            int crc = PromptPayQrService.calculateCrc16Ccitt(payloadWithoutCrc.getBytes(StandardCharsets.US_ASCII));
            assertThat(String.format("%04X", crc)).isEqualTo("C2AF");
        }
    }

    @Nested
    @DisplayName("Payload Generation and Structure")
    class PayloadStructureTests {

        @Test
        @DisplayName("Generates exact PromptPay dynamic QR payload for amount 673.00")
        void generatesExactPayloadFor673() {
            String payload = qrService.generatePayload(new BigDecimal("673.00"));
            String expected = "00020101021229370016A0000006770101110113006694776111953037645406673.005802TH5911PEGASUS TCG6007Bangkok6304C2AF";
            assertThat(payload).isEqualTo(expected);
        }

        @Test
        @DisplayName("Contains all required EMVCo tags in order")
        void containsRequiredTags() {
            String payload = qrService.generatePayload(new BigDecimal("673.00"));

            // Tag 00: Payload Format Indicator = "01"
            assertThat(payload).startsWith("000201");
            // Tag 01: Dynamic QR = "12"
            assertThat(payload).contains("010212");
            // Tag 29: AID + Phone Target
            assertThat(payload).contains("29370016A00000067701011101130066947761119");
            // Tag 53: THB = "764"
            assertThat(payload).contains("5303764");
            // Tag 54: Amount = "673.00"
            assertThat(payload).contains("5406673.00");
            // Tag 58: Country Code = "TH"
            assertThat(payload).contains("5802TH");
            // Tag 59: Merchant Name = "PEGASUS TCG"
            assertThat(payload).contains("5911PEGASUS TCG");
            // Tag 60: City = "Bangkok"
            assertThat(payload).contains("6007Bangkok");
            // Tag 63: CRC16 prefix + 4-char hex
            assertThat(payload).endsWith("6304C2AF");
        }

        @Test
        @DisplayName("Formats amounts with 2 decimal places properly")
        void formatsAmountsWith2Decimals() {
            String payload = qrService.generatePayload(new BigDecimal("100"));
            assertThat(payload).contains("5406100.00");

            String payload2 = qrService.generatePayload(new BigDecimal("1250.5"));
            assertThat(payload2).contains("54071250.50");
        }

        @Test
        @DisplayName("Throws IllegalArgumentException when amount is null or non-positive")
        void throwsOnInvalidAmount() {
            assertThatThrownBy(() -> qrService.generatePayload(null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> qrService.generatePayload(BigDecimal.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> qrService.generatePayload(new BigDecimal("-10.00")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Target Phone Formatting and Masking")
    class TargetFormattingTests {

        @Test
        @DisplayName("Formats standard 10-digit Thai mobile to EMVCo format")
        void formatsPhoneToEmvco() {
            assertThat(PromptPayQrService.formatPromptPayTarget("0947761119"))
                    .isEqualTo("0066947761119");
            assertThat(PromptPayQrService.formatPromptPayTarget("094-776-1119"))
                    .isEqualTo("0066947761119");
            assertThat(PromptPayQrService.formatPromptPayTarget("+66947761119"))
                    .isEqualTo("0066947761119");
            assertThat(PromptPayQrService.formatPromptPayTarget("0066947761119"))
                    .isEqualTo("0066947761119");
        }

        @Test
        @DisplayName("Masks PromptPay ID to 094-xxx-1119")
        void masksPromptPayId() {
            assertThat(qrService.maskedPromptPayId()).isEqualTo("094-xxx-1119");
            assertThat(PromptPayQrService.maskPromptPayId("0947761119")).isEqualTo("094-xxx-1119");
            assertThat(PromptPayQrService.maskPromptPayId("0066947761119")).isEqualTo("094-xxx-1119");
            assertThat(PromptPayQrService.maskPromptPayId("+66947761119")).isEqualTo("094-xxx-1119");
        }
    }
}
