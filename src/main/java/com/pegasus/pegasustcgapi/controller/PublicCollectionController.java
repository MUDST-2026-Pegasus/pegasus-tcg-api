package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.PublicCollectionItemResponse;
import com.pegasus.pegasustcgapi.service.CollectionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The cards someone chose to show on their profile, readable by anyone.
 *
 * <p>Only rows marked public, and never what the owner paid or wrote about them.
 */
@RestController
public class PublicCollectionController {

    private final CollectionService collection;

    public PublicCollectionController(CollectionService collection) {
        this.collection = collection;
    }

    @GetMapping(ApiPaths.USERS + "/{username}/collection")
    public ApiResult<PageResponse<PublicCollectionItemResponse>> collection(
            @PathVariable String username,
            @RequestParam(required = false) Short gameId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return ApiResult.success(collection.publicCollection(username, gameId, page, size));
    }
}
