package com.pegasus.pegasustcgapi.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on {@code @Scheduled}; the one job so far is the nightly market run. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
