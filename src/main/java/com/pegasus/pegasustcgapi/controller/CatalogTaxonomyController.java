package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResponse;
import com.pegasus.pegasustcgapi.model.CardSet;
import com.pegasus.pegasustcgapi.model.CatalogCategory;
import com.pegasus.pegasustcgapi.model.Game;
import com.pegasus.pegasustcgapi.model.GameAttribute;
import com.pegasus.pegasustcgapi.service.CatalogTaxonomyService;
import com.pegasus.pegasustcgapi.service.GameService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What a visitor needs before they can browse: which games exist, how each one's
 * cards are described, and how they are shelved.
 *
 * <p>Open to everyone. Someone deciding whether to sign up is exactly who needs
 * to see the catalogue first, and none of this is anybody's private data.
 * Retired rows are hidden here; an admin sees those through the admin endpoints.
 */
@RestController
public class CatalogTaxonomyController {

    private final GameService games;
    private final CatalogTaxonomyService taxonomy;

    public CatalogTaxonomyController(GameService games, CatalogTaxonomyService taxonomy) {
        this.games = games;
        this.taxonomy = taxonomy;
    }

    @GetMapping(ApiPaths.GAMES)
    public ApiResponse<List<Game>> games() {
        return ApiResponse.success(games.list(false));
    }

    @GetMapping(ApiPaths.GAMES + "/{gameId}")
    public ApiResponse<Game> game(@PathVariable short gameId) {
        return ApiResponse.success(games.require(gameId));
    }

    /** The filter sidebar is built from this, so a new game brings its own filters. */
    @GetMapping(ApiPaths.GAMES + "/{gameId}/attributes")
    public ApiResponse<List<GameAttribute>> attributes(@PathVariable short gameId) {
        return ApiResponse.success(games.attributesOf(gameId));
    }

    /** @param gameId optional; given one, the cross-game categories come with it. */
    @GetMapping(ApiPaths.CATEGORIES)
    public ApiResponse<List<CatalogCategory>> categories(
            @RequestParam(required = false) Short gameId) {

        return ApiResponse.success(taxonomy.categories(gameId, false));
    }

    @GetMapping(ApiPaths.CARD_SETS)
    public ApiResponse<List<CardSet>> cardSets(@RequestParam short gameId) {
        return ApiResponse.success(taxonomy.cardSets(gameId));
    }
}
