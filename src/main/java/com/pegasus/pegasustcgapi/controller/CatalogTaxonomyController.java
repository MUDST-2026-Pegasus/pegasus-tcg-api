package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.dto.CategoryResponse;
import com.pegasus.pegasustcgapi.model.CardSet;
import com.pegasus.pegasustcgapi.model.Game;
import com.pegasus.pegasustcgapi.model.GameAttribute;
import com.pegasus.pegasustcgapi.service.CatalogTaxonomyService;
import com.pegasus.pegasustcgapi.service.GameService;
import com.pegasus.pegasustcgapi.storage.StorageService;
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
 *
 * <p>Pictures leave as URLs a browser can open: stored keys are signed on the way out.
 */
@RestController
public class CatalogTaxonomyController {

    private final GameService games;
    private final CatalogTaxonomyService taxonomy;
    private final StorageService storage;

    public CatalogTaxonomyController(
            GameService games, CatalogTaxonomyService taxonomy, StorageService storage) {

        this.games = games;
        this.taxonomy = taxonomy;
        this.storage = storage;
    }

    @GetMapping(ApiPaths.GAMES)
    public ApiResult<List<Game>> games() {
        return ApiResult.success(games.list(false).stream().map(this::readable).toList());
    }

    @GetMapping(ApiPaths.GAMES + "/{gameId}")
    public ApiResult<Game> game(@PathVariable short gameId) {
        return ApiResult.success(readable(games.requireActive(gameId)));
    }

    /** The filter sidebar is built from this, so a new game brings its own filters. */
    @GetMapping(ApiPaths.GAMES + "/{gameId}/attributes")
    public ApiResult<List<GameAttribute>> attributes(@PathVariable short gameId) {
        games.requireActive(gameId);
        return ApiResult.success(games.attributesOf(gameId));
    }

    /** @param gameId optional; given one, the cross-game categories come with it. */
    @GetMapping(ApiPaths.CATEGORIES)
    public ApiResult<List<CategoryResponse>> categories(
            @RequestParam(required = false) Short gameId) {

        return ApiResult.success(taxonomy.categories(gameId, false).stream()
                .map(category -> CategoryResponse.of(category, storage.readUrl(category.imageKey())))
                .toList());
    }

    @GetMapping(ApiPaths.CARD_SETS)
    public ApiResult<List<CardSet>> cardSets(@RequestParam short gameId) {
        return ApiResult.success(taxonomy.cardSets(gameId));
    }

    /** The logo column takes a pasted URL or an uploaded key; either way the browser gets a URL. */
    private Game readable(Game game) {
        return game.withLogoUrl(storage.readUrl(game.logoUrl()));
    }
}
