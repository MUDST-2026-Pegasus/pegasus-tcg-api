package com.pegasus.pegasustcgapi.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    /** Injected rather than calling {@code now()} directly, so expiry logic is testable. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
