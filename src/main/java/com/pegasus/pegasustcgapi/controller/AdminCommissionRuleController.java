package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.dto.CommissionRuleRequest;
import com.pegasus.pegasustcgapi.repository.CommissionRuleRepository.Detail;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.CommissionRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Commission Rules (Admin)")
@SecurityRequirement(name = "BearerAuth")
@RestController
@RequestMapping(ApiPaths.ADMIN + "/commission-rules")
@PreAuthorize("hasRole('ADMIN')")
public class AdminCommissionRuleController {

    private final CommissionRuleService rules;

    public AdminCommissionRuleController(CommissionRuleService rules) {
        this.rules = rules;
    }

    @Operation(summary = "List commission rules")
    @GetMapping
    public ApiResult<List<Detail>> list() {
        return ApiResult.success(rules.list());
    }

    @Operation(summary = "Get commission rule")
    @GetMapping("/{id}")
    public ApiResult<Detail> get(@PathVariable long id) {
        return ApiResult.success(rules.get(id));
    }

    @Operation(summary = "Create commission rule")
    @PostMapping
    public ResponseEntity<ApiResult<Detail>> create(@Valid @RequestBody CommissionRuleRequest request,
            AuthPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.success(rules.create(request, principal.userId())));
    }

    @Operation(summary = "Update commission rule")
    @PutMapping("/{id}")
    public ApiResult<Detail> update(@PathVariable long id, @Valid @RequestBody CommissionRuleRequest request) {
        return ApiResult.success(rules.update(id, request));
    }

    @Operation(summary = "Deactivate commission rule without deleting its history")
    @PostMapping("/{id}/deactivate")
    public ApiResult<Void> deactivate(@PathVariable long id) {
        rules.deactivate(id);
        return ApiResult.success("Commission rule deactivated", null);
    }

    @Operation(summary = "Delete commission rule",
            description = "Orders keep the rate and amount frozen at checkout; "
                    + "commission charges only lose their link to the rule.")
    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable long id) {
        rules.delete(id);
        return ApiResult.success("Commission rule deleted", null);
    }
}
