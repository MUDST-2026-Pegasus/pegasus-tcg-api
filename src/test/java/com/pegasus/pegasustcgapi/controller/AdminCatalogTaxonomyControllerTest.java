package com.pegasus.pegasustcgapi.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.GlobalExceptionHandler;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.CatalogCategory;
import com.pegasus.pegasustcgapi.model.Game;
import com.pegasus.pegasustcgapi.security.AuthClaims;
import com.pegasus.pegasustcgapi.security.AuthPrincipalArgumentResolver;
import com.pegasus.pegasustcgapi.service.CatalogTaxonomyService;
import com.pegasus.pegasustcgapi.service.GameService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** RQ-3: administrators curate games, categories and card sets over HTTP. */
@ExtendWith(MockitoExtension.class)
class AdminCatalogTaxonomyControllerTest {

    private static final String ADMIN = ApiPaths.ADMIN;

    @Mock GameService games;
    @Mock CatalogTaxonomyService taxonomy;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AdminCatalogTaxonomyController(games, taxonomy))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthPrincipalArgumentResolver())
                .build();
        Jwt jwt = Jwt.withTokenValue("test-admin").header("alg", "HS256").subject("41")
                .claim(AuthClaims.EMAIL, "admin@example.com")
                .claim(AuthClaims.USERNAME, "admin")
                .claim(AuthClaims.ROLES, List.of("ADMIN"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void retiredGamesAreIncludedByDefault() throws Exception {
        when(games.list(true)).thenReturn(List.of(new Game((short) 2, "POKEMON", "Pokemon", null,
                "pokemon", null, (short) 0, false, 41L, null)));

        mvc.perform(get(ADMIN + "/games"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].active").value(false));
        verify(games).list(true);
    }

    @Test
    void activeOnlyAndCrossGameCategoryFiltersReachServices() throws Exception {
        when(games.list(false)).thenReturn(List.of());
        when(taxonomy.categories(null, false)).thenReturn(List.of(
                new CatalogCategory(7, null, null, "ACCESSORY", "Accessories", "accessories",
                        (short) 0, true, null)));

        mvc.perform(get(ADMIN + "/games").param("includeInactive", "false"))
                .andExpect(status().isOk());
        mvc.perform(get(ADMIN + "/categories").param("includeInactive", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].gameId").doesNotExist());
        verify(games).list(false);
        verify(taxonomy).categories(null, false);
    }

    @Test
    void createGameUsesAdminIdentityAndDefaults() throws Exception {
        when(games.create(any(), org.mockito.ArgumentMatchers.eq(41L)))
                .thenReturn(new Game((short) 2, "POKEMON", "Pokemon", null, "pokemon", null,
                        (short) 0, true, 41L, null));

        mvc.perform(post(ADMIN + "/games").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"POKEMON\",\"name\":\"Pokemon\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.code").value("POKEMON"));
        verify(games).create(argThat(fields -> fields.active() && fields.displayOrder() == 0),
                org.mockito.ArgumentMatchers.eq(41L));
    }

    @Test
    void invalidGameCodeIsRejectedBeforeWriting() throws Exception {
        mvc.perform(post(ADMIN + "/games").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"bad code\",\"name\":\"Pokemon\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.violations[0].field").value("code"));
        verifyNoInteractions(games, taxonomy);
    }

    @Test
    void invalidCategorySlugIsRejectedBeforeWriting() throws Exception {
        mvc.perform(post(ADMIN + "/categories").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"SINGLE\",\"name\":\"Singles\",\"slug\":\"Bad Slug\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.violations[0].field").value("slug"));
        verifyNoInteractions(taxonomy);
    }

    @Test
    void createCategoryDefaultsActiveAndOrder() throws Exception {
        when(taxonomy.createCategory(any())).thenReturn(new CatalogCategory(8, (short) 2, null,
                "SINGLE", "Singles", "singles", (short) 0, true, null));

        mvc.perform(post(ADMIN + "/categories").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gameId\":2,\"code\":\"SINGLE\",\"name\":\"Singles\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(8));
        verify(taxonomy).createCategory(argThat(fields -> fields.active()
                && fields.displayOrder() == 0 && fields.gameId() == 2));
    }

    @Test
    void cardSetRejectsNonPositiveCardCountBeforeWriting() throws Exception {
        mvc.perform(post(ADMIN + "/games/2/card-sets").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"SV8a\",\"name\":\"Terastal\",\"totalCards\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.violations[0].field").value("totalCards"));
        verifyNoInteractions(taxonomy);
    }

    @Test
    void attributeKeyMustBeAStableLowercaseIdentifier() throws Exception {
        mvc.perform(post(ADMIN + "/games/2/attributes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attrKey\":\"HP Value\",\"label\":\"HP\",\"dataType\":\"NUMBER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.violations[0].field").value("attrKey"));
        verifyNoInteractions(games);
    }

    @Test
    void cardSetWithProductsCannotBeDeleted() throws Exception {
        org.mockito.Mockito.doThrow(new ConflictException(ErrorCode.CARD_SET_IN_USE))
                .when(taxonomy).deleteCardSet(9);

        mvc.perform(delete(ADMIN + "/card-sets/9"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.code").value("CARD_SET_IN_USE"));
        verify(taxonomy).deleteCardSet(9);
    }

    @Test
    void unknownCardSetIsReportedAsNotFoundOnUpdate() throws Exception {
        when(taxonomy.updateCardSet(org.mockito.ArgumentMatchers.eq(99), any()))
                .thenThrow(new NotFoundException(ErrorCode.CARD_SET_NOT_FOUND));

        mvc.perform(put(ADMIN + "/card-sets/99").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"SV8a\",\"name\":\"Terastal\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.code").value("CARD_SET_NOT_FOUND"));
    }

    @Test
    void deletingAnUnusedCardSetReturnsSuccess() throws Exception {
        mvc.perform(delete(ADMIN + "/card-sets/9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Card set deleted"));
        verify(taxonomy).deleteCardSet(9);
    }
}
