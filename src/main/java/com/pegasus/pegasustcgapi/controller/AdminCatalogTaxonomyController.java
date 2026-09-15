package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResponse;
import com.pegasus.pegasustcgapi.dto.CardSetRequest;
import com.pegasus.pegasustcgapi.dto.CategoryRequest;
import com.pegasus.pegasustcgapi.dto.GameAttributeRequest;
import com.pegasus.pegasustcgapi.dto.GameRequest;
import com.pegasus.pegasustcgapi.model.CardSet;
import com.pegasus.pegasustcgapi.model.CatalogCategory;
import com.pegasus.pegasustcgapi.model.Game;
import com.pegasus.pegasustcgapi.model.GameAttribute;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.CatalogTaxonomyService;
import com.pegasus.pegasustcgapi.service.GameService;
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
 * <p>Nothing here deletes. {@code catalog_product} points at a category and a set
 * with ON DELETE RESTRICT, so what is no longer offered is deactivated and the
 * products filed under it keep resolving. Attributes are the exception: no row
 * references one, so a mistaken field can go.
 */
@RestController
@RequestMapping(ApiPaths.ADMIN)
@PreAuthorize("hasRole('ADMIN')")
public class AdminCatalogTaxonomyController {

    private final GameService games;
    private final CatalogTaxonomyService taxonomy;

    public AdminCatalogTaxonomyController(GameService games, CatalogTaxonomyService taxonomy) {
        this.games = games;
        this.taxonomy = taxonomy;
    }

    // ---------- games ----------

    /** Unlike the public list, this one shows retired games too. */
    @GetMapping("/games")
    public ApiResponse<List<Game>> games(
            @RequestParam(defaultValue = "true") boolean includeInactive) {
        return ApiResponse.success(games.list(includeInactive));
    }

    @PostMapping("/games")
    public ResponseEntity<ApiResponse<Game>> createGame(
            @Valid @RequestBody GameRequest request, AuthPrincipal principal) {

        Game created = games.create(request.toFields(), principal.userId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Game added", created));
    }

    @PutMapping("/games/{gameId}")
    public ApiResponse<Game> updateGame(
            @PathVariable short gameId, @Valid @RequestBody GameRequest request) {

        return ApiResponse.success("Game updated", games.update(gameId, request.toFields()));
    }

    // ---------- game attributes ----------

    @GetMapping("/games/{gameId}/attributes")
    public ApiResponse<List<GameAttribute>> attributes(@PathVariable short gameId) {
        return ApiResponse.success(games.attributesOf(gameId));
    }

    @PostMapping("/games/{gameId}/attributes")
    public ResponseEntity<ApiResponse<GameAttribute>> addAttribute(
            @PathVariable short gameId, @Valid @RequestBody GameAttributeRequest request) {

        GameAttribute created = games.addAttribute(gameId, request.toFields());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Attribute added", created));
    }

    @PutMapping("/games/{gameId}/attributes/{attributeId}")
    public ApiResponse<GameAttribute> updateAttribute(
            @PathVariable short gameId,
            @PathVariable int attributeId,
            @Valid @RequestBody GameAttributeRequest request) {

        return ApiResponse.success("Attribute updated",
                games.updateAttribute(gameId, attributeId, request.toFields()));
    }

    /** Values already stored under this key are left where they are, unread. */
    @DeleteMapping("/games/{gameId}/attributes/{attributeId}")
    public ApiResponse<Void> deleteAttribute(
            @PathVariable short gameId, @PathVariable int attributeId) {

        games.deleteAttribute(gameId, attributeId);
        return ApiResponse.success("Attribute deleted", null);
    }

    // ---------- categories ----------

    @GetMapping("/categories")
    public ApiResponse<List<CatalogCategory>> categories(
            @RequestParam(required = false) Short gameId,
            @RequestParam(defaultValue = "true") boolean includeInactive) {

        return ApiResponse.success(taxonomy.categories(gameId, includeInactive));
    }

    @PostMapping("/categories")
    public ResponseEntity<ApiResponse<CatalogCategory>> createCategory(
            @Valid @RequestBody CategoryRequest request) {

        CatalogCategory created = taxonomy.createCategory(request.toFields());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Category added", created));
    }

    /** The game is fixed at creation; everything else, including retiring it, is editable. */
    @PutMapping("/categories/{categoryId}")
    public ApiResponse<CatalogCategory> updateCategory(
            @PathVariable int categoryId, @Valid @RequestBody CategoryRequest request) {

        return ApiResponse.success("Category updated",
                taxonomy.updateCategory(categoryId, request.toFields()));
    }

    // ---------- card sets ----------

    @PostMapping("/games/{gameId}/card-sets")
    public ResponseEntity<ApiResponse<CardSet>> createCardSet(
            @PathVariable short gameId, @Valid @RequestBody CardSetRequest request) {

        CardSet created = taxonomy.createCardSet(gameId, request.toFields());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Card set added", created));
    }

    @PutMapping("/card-sets/{cardSetId}")
    public ApiResponse<CardSet> updateCardSet(
            @PathVariable int cardSetId, @Valid @RequestBody CardSetRequest request) {

        return ApiResponse.success("Card set updated",
                taxonomy.updateCardSet(cardSetId, request.toFields()));
    }
}
