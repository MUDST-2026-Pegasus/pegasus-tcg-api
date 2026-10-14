package com.pegasus.pegasustcgapi.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.support.ApiClient;
import com.pegasus.pegasustcgapi.support.ApiClient.Response;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import com.pegasus.pegasustcgapi.support.TestData.Card;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

@DisplayName("Admin card effect API (TCG-460)")
class AdminCardEffectApiIntegrationTest extends PostgresIntegrationTest {

    private ApiClient client;
    private String adminBearer;
    private Card card;

    @BeforeEach
    void setUp() {
        client = new ApiClient(port);
        adminBearer = data.bearer(data.admin());
        card = data.card(data.game("Pokemon"), "Pikachu ex", "SAR");
    }

    @Test
    @DisplayName("a non-admin gets 403 and nothing is saved")
    void nonAdminIsForbidden() {
        String buyer = data.bearer(data.buyer());

        assertThat(client.get(adminPath(), buyer).status()).isEqualTo(403);
        assertThat(client.put(adminPath(), buyer, body(attack("Thunder", 120))).status()).isEqualTo(403);
        assertThat(names(publicList())).isEmpty();
    }

    @Test
    @DisplayName("PUT saves the list in order and the public card page shows it")
    void savesInOrder() {
        Response saved = client.put(adminPath(), adminBearer, body(
                effect("RULE", "Terastal", "Benched: attacks cannot damage it.", null, List.of()),
                effect("ABILITY", "Resolute Heart", "Stays in play with 10 HP.", null, List.of()),
                attack("Topaz Bolt", 300)));

        assertThat(saved.status()).isEqualTo(200);
        assertThat(names(saved.data())).containsExactly("Terastal", "Resolute Heart", "Topaz Bolt");

        JsonNode bolt = publicList().get(2);
        assertThat(bolt.path("effectType").asText()).isEqualTo("ATTACK");
        assertThat(bolt.path("damage").asInt()).isEqualTo(300);
        assertThat(bolt.path("displayOrder").asInt()).isEqualTo(2);
        assertThat(bolt.path("energyCost").get(0).asText()).isEqualTo("Lightning");
        assertThat(names(client.get(adminPath(), adminBearer).data()))
                .containsExactly("Terastal", "Resolute Heart", "Topaz Bolt");
    }

    @Test
    @DisplayName("saving again reorders, edits and drops without tripping the unique order")
    void replacesTheWholeList() {
        client.put(adminPath(), adminBearer, body(attack("A", 10), attack("B", 20), attack("C", 30)));

        Response saved = client.put(adminPath(), adminBearer, body(attack("C", 30), attack("A", 50)));

        assertThat(saved.status()).isEqualTo(200);
        assertThat(names(publicList())).containsExactly("C", "A");
        assertThat(publicList().get(1).path("damage").asInt()).isEqualTo(50);
    }

    @Test
    @DisplayName("an empty list clears the card's rules")
    void emptyListClears() {
        client.put(adminPath(), adminBearer, body(attack("Thunder", 120)));

        assertThat(client.put(adminPath(), adminBearer, body()).status()).isEqualTo(200);
        assertThat(names(publicList())).isEmpty();
    }

    @Test
    @DisplayName("an invalid entry is a 400 and leaves what was saved untouched")
    void invalidEntryIsRejected() {
        client.put(adminPath(), adminBearer, body(attack("Thunder", 120)));

        List<Response> rejected = List.of(
                client.put(adminPath(), adminBearer, body(effect("SPELL", "X", null, null, List.of()))),
                client.put(adminPath(), adminBearer, body(effect("ATTACK", " ", null, 10, List.of()))),
                client.put(adminPath(), adminBearer, body(attack("Minus", -10))),
                client.put(adminPath(), adminBearer, body(effect("ATTACK", "X", null, 10, List.of(" ")))),
                client.put(adminPath(), adminBearer, Map.of()));

        assertThat(rejected).allSatisfy(response -> {
            assertThat(response.status()).isEqualTo(400);
            assertThat(response.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED.name());
        });
        assertThat(names(publicList())).containsExactly("Thunder");
    }

    @Test
    @DisplayName("an unknown product is a 404")
    void unknownProduct() {
        String path = "/api/v1/admin/catalog/products/999999999/effects";

        assertThat(client.get(path, adminBearer).errorCode()).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND.name());
        assertThat(client.put(path, adminBearer, body()).status()).isEqualTo(404);
    }

    private String adminPath() {
        return "/api/v1/admin/catalog/products/" + card.productId() + "/effects";
    }

    private JsonNode publicList() {
        Response response = client.get("/api/v1/catalog/products/" + card.productId() + "/effects", null);
        assertThat(response.status()).isEqualTo(200);
        return response.data();
    }

    @SafeVarargs
    private static Map<String, Object> body(Map<String, Object>... effects) {
        return Map.of("effects", List.of(effects));
    }

    private static Map<String, Object> attack(String name, int damage) {
        return effect("ATTACK", name, "Discard 3 Energy.", damage, List.of("Lightning", "Metal"));
    }

    private static Map<String, Object> effect(String type, String name, String text, Integer damage,
            List<String> energyCost) {
        // HashMap rather than Map.of: text and damage may be null.
        Map<String, Object> effect = new HashMap<>();
        effect.put("effectType", type);
        effect.put("name", name);
        effect.put("effectText", text);
        effect.put("damage", damage);
        effect.put("energyCost", energyCost);
        return effect;
    }

    private static List<String> names(JsonNode effects) {
        List<String> names = new ArrayList<>();
        effects.forEach(effect -> names.add(effect.path("name").asText()));
        return names;
    }
}
