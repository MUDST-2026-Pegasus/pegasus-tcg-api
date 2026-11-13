package com.pegasus.pegasustcgapi.controller.catalog;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.catalog.SearchAliasRequest;
import com.pegasus.pegasustcgapi.repository.catalog.SearchAliasRepository.Alias;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.catalog.SearchAliasService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Words that search as other words, e.g. โปเกมอน → Pokemon (TCG-773). */
@SecurityRequirement(name = "BearerAuth")
@RestController
@RequestMapping(ApiPaths.ADMIN + "/search-aliases")
@PreAuthorize("hasRole('ADMIN')")
public class AdminSearchAliasController {

    private final SearchAliasService aliases;

    public AdminSearchAliasController(SearchAliasService aliases) {
        this.aliases = aliases;
    }

    @GetMapping
    public ApiResult<PageResponse<Alias>> list(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResult.success(aliases.list(q, page, size));
    }

    @PostMapping
    public ResponseEntity<ApiResult<Alias>> create(@Valid @RequestBody SearchAliasRequest request,
            AuthPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.success("Search alias added", aliases.create(request, principal.userId())));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable long id, AuthPrincipal principal) {
        aliases.delete(id, principal.userId());
        return ApiResult.success("Search alias removed", null);
    }
}
