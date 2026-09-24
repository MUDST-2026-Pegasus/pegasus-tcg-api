package com.pegasus.pegasustcgapi.config;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The cart is the one part of the shop open without an account, and it is open at
 * exactly one prefix. The filter chain used to also permit a bare {@code /cart},
 * which matched no route in this application — a standing grant waiting for the
 * first controller that happened to map there.
 */
@SpringBootTest
@DisplayName("Cart security rules")
class CartSecurityRulesTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    @DisplayName("serves the cart to a guest under the versioned prefix")
    void versionedCartIsPublic() throws Exception {
        mockMvc.perform(get(ApiPaths.CART))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("does not permit an unversioned /cart")
    void unversionedCartIsNotPermitted() throws Exception {
        mockMvc.perform(get("/cart"))
                .andExpect(status().isUnauthorized());
    }
}
