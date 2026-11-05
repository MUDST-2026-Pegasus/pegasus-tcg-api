package com.pegasus.pegasustcgapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.payment.promptpay")
public record PromptPayProperties(
        String type,
        String target,
        String bank,
        String name
) {
    public PromptPayProperties {
        if (type == null || type.isBlank()) {
            type = "PHONE";
        }
        if (target == null || target.isBlank()) {
            target = "0947761119";
        }
        if (bank == null || bank.isBlank()) {
            bank = "ธนาคารไทยพาณิชย์ (SCB)";
        }
        if (name == null || name.isBlank()) {
            name = "PEGASUS TCG";
        }
    }
}
