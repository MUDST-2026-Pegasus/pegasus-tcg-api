package com.pegasus.pegasustcgapi.support;

import com.pegasus.pegasustcgapi.service.user.AddressService;
import com.pegasus.pegasustcgapi.service.auth.JwtService;
import com.pegasus.pegasustcgapi.service.listing.ListingService;
import com.pegasus.pegasustcgapi.service.listing.ListingUnitService;
import org.jooq.DSLContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** The beans {@link PostgresIntegrationTest} and the Cucumber glue add to the application. */
@TestConfiguration(proxyBeanMethods = false)
public class IntegrationTestConfig {

    @Bean
    @Primary
    MutableClock mutableClock() {
        return new MutableClock();
    }

    @Bean
    TestData testData(DSLContext dsl, ListingService listings, ListingUnitService units,
            AddressService addresses, JwtService jwt) {
        return new TestData(dsl, listings, units, addresses, jwt);
    }
}
