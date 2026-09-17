package com.pegasus.pegasustcgapi.config;

import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param marketJobCron when the nightly market run starts, in {@code zone}
 * @param zone          whose calendar a day's market statistics belong to
 */
@ConfigurationProperties("pegasus.pricing")
public record PricingProperties(String marketJobCron, ZoneId zone) {
}
