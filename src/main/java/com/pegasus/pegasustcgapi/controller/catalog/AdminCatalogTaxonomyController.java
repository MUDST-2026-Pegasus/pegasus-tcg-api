package com.pegasus.pegasustcgapi.controller.catalog;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.dto.catalog.AdminGameResponse;
import com.pegasus.pegasustcgapi.dto.catalog.CardSetRequest;
import com.pegasus.pegasustcgapi.dto.catalog.CardSetResponse;
import com.pegasus.pegasustcgapi.dto.catalog.CategoryRequest;
import com.pegasus.pegasustcgapi.dto.catalog.GameAttributeRequest;
import com.pegasus.pegasustcgapi.dto.catalog.GameRequest;
import com.pegasus.pegasustcgapi.model.catalog.CardSet;
import com.pegasus.pegasustcgapi.model.catalog.CatalogCategory;
import com.pegasus.pegasustcgapi.model.catalog.Game;
import com.pegasus.pegasustcgapi.model.catalog.GameAttribute;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.catalog.CatalogTaxonomyService;
import com.pegasus.pegasustcgapi.service.catalog.GameService;
import com.pegasus.pegasustcgapi.storage.StorageService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Curating the catalogue's vocabulary [RQ-3]: the games, the fields their cards
 * have, the shelves and the releases.
 *
 * <p>Games and categories are never deleted: {@code catalog_product} points at
 * them with ON DELETE RESTRICT, so what is no longer offered is deactivated and
 * the products filed under it keep resolving. An attribute can go because no row
 * references one, and a card set can go once no product is filed under it.
 *
 * <p>Logos come back twice: the stored value to send back on update, and a URL
 * the browser can open, so an uploaded key shows as a picture here too.
 */
@RestController
@RequestMapping(ApiPaths.ADMIN)
@PreAuthorize("hasRole('ADMIN')")
public class AdminCatalogTaxonomyController {

    private final GameService games;
    private final CatalogTaxonomyService taxonomy;
    private final StorageService storage;

    public AdminCatalogTaxonomyController(
            GameService games, CatalogTaxonomyService taxonomy, StorageService storage) {

        this.games = games;
        this.taxonomy = taxonomy;
        this.storage = storage;
    }

    // ---------- games ----------

    /** Unlike the public list, this one shows retired games too. */
    @GetMapping("/games")
    public ApiResult<List<AdminGameResponse>> games(
            @RequestParam(defaultValue = "true") boolean includeInactive) {
        return ApiResult.success(games.list(includeInactive).stream().map(this::response).toList());
    }

    @PostMapping("/games")
    public ResponseEntity<ApiResult<AdminGameResponse>> createGame(
            @Valid @RequestBody GameRequest request, AuthPrincipal principal) {

        Game created = games.create(request.toFields(), principal.userId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.success("Game added", response(created)));
    }

    @PutMapping("/games/{gameId}")
    public ApiResult<AdminGameResponse> updateGame(
            @PathVariable short gameId, @Valid @RequestBody GameRequest request, AuthPrincipal principal) {

        return ApiResult.success("Game updated",
                response(games.update(gameId, request.toFields(), principal.userId())));
    }

    // ---------- game attributes ----------

    @GetMapping("/games/{gameId}/attributes")
    public ApiResult<List<GameAttribute>> attributes(@PathVariable short gameId) {
        return ApiResult.success(games.attributesOf(gameId));
    }

    @PostMapping("/games/{gameId}/attributes")
    public ResponseEntity<ApiResult<GameAttribute>> addAttribute(
            @PathVariable short gameId, @Valid @RequestBody GameAttributeRequest request,
            AuthPrincipal principal) {

        GameAttribute created = games.addAttribute(gameId, request.toFields(), principal.userId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.success("Attribute added", created));
    }

    @PutMapping("/games/{gameId}/attributes/{attributeId}")
    public ApiResult<GameAttribute> updateAttribute(
            @PathVariable short gameId,
            @PathVariable int attributeId,
            @Valid @RequestBody GameAttributeRequest request,
            AuthPrincipal principal) {

        return ApiResult.success("Attribute updated",
                games.updateAttribute(gameId, attributeId, request.toFields(), principal.userId()));
    }

    /** Values already stored under this key are left where they are, unread. */
    @DeleteMapping("/games/{gameId}/attributes/{attributeId}")
    public ApiResult<Void> deleteAttribute(
            @PathVariable short gameId, @PathVariable int attributeId, AuthPrincipal principal) {

        games.deleteAttribute(gameId, attributeId, principal.userId());
        return ApiResult.success("Attribute deleted", null);
    }

    // ---------- categories ----------

    @GetMapping("/categories")
    public ApiResult<List<CatalogCategory>> categories(
            @RequestParam(required = false) Short gameId,
            @RequestParam(defaultValue = "true") boolean includeInactive) {

        return ApiResult.success(taxonomy.categories(gameId, includeInactive));
    }

    @PostMapping("/categories")
    public ResponseEntity<ApiResult<CatalogCategory>> createCategory(
            @Valid @RequestBody CategoryRequest request, AuthPrincipal principal) {

        CatalogCategory created = taxonomy.createCategory(request.toFields(), principal.userId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.success("Category added", created));
    }

    /** The game is fixed at creation; everything else, including retiring it, is editable. */
    @PutMapping("/categories/{categoryId}")
    public ApiResult<CatalogCategory> updateCategory(
            @PathVariable int categoryId, @Valid @RequestBody CategoryRequest request,
            AuthPrincipal principal) {

        return ApiResult.success("Category updated",
                taxonomy.updateCategory(categoryId, request.toFields(), principal.userId()));
    }

    // ---------- card sets ----------

    @PostMapping("/games/{gameId}/card-sets")
    public ResponseEntity<ApiResult<CardSetResponse>> createCardSet(
            @PathVariable short gameId, @Valid @RequestBody CardSetRequest request,
            AuthPrincipal principal) {

        CardSet created = taxonomy.createCardSet(gameId, request.toFields(), principal.userId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.success("Card set added", response(created)));
    }

    @PutMapping("/card-sets/{cardSetId}")
    public ApiResult<CardSetResponse> updateCardSet(
            @PathVariable int cardSetId, @Valid @RequestBody CardSetRequest request,
            AuthPrincipal principal) {

        return ApiResult.success("Card set updated",
                response(taxonomy.updateCardSet(cardSetId, request.toFields(), principal.userId())));
    }

    /** A set that still has products, retired ones included, is refused with CARD_SET_IN_USE. */
    @DeleteMapping("/card-sets/{cardSetId}")
    public ApiResult<Void> deleteCardSet(@PathVariable int cardSetId, AuthPrincipal principal) {
        taxonomy.deleteCardSet(cardSetId, principal.userId());
        return ApiResult.success("Card set deleted", null);
    }

    private AdminGameResponse response(Game game) {
        return AdminGameResponse.of(game, storage.readUrl(game.logoUrl()));
    }

    private CardSetResponse response(CardSet cardSet) {
        return CardSetResponse.of(cardSet, storage.readUrl(cardSet.logoUrl()));
    }
}
