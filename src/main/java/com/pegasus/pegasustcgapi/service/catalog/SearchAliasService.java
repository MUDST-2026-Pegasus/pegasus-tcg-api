package com.pegasus.pegasustcgapi.service.catalog;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.common.Paging;
import com.pegasus.pegasustcgapi.dto.catalog.SearchAliasRequest;
import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.model.audit.AuditAction;
import com.pegasus.pegasustcgapi.repository.catalog.SearchAliasRepository;
import com.pegasus.pegasustcgapi.repository.catalog.SearchAliasRepository.Alias;
import com.pegasus.pegasustcgapi.service.audit.AuditLogService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin upkeep of the Thai search aliases (TCG-773); the search reads them on every miss, so changes apply at once. */
@Service
public class SearchAliasService {

    private final SearchAliasRepository aliases;
    private final GameService games;
    private final AuditLogService audit;

    public SearchAliasService(SearchAliasRepository aliases, GameService games, AuditLogService audit) {
        this.aliases = aliases;
        this.games = games;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<Alias> list(String q, int page, int size) {
        Paging paging = Paging.of(page, size);
        return PageResponse.of(aliases.page(q, paging.size(), paging.offset()), paging.page(), paging.size(),
                aliases.count(q));
    }

    @Transactional
    public Alias create(SearchAliasRequest request, long adminId) {
        if (request.gameId() != null) {
            games.requireActive(request.gameId());
        }
        Alias created;
        try {
            created = aliases.insert(request.alias().trim(), request.target().trim(), request.gameId(), adminId);
        } catch (DuplicateKeyException duplicate) {
            throw new ApiException(ErrorCode.SEARCH_ALIAS_EXISTS);
        }
        audit.record(adminId, AuditAction.SEARCH_ALIAS_CREATED, created.id(), "Search alias added",
                AuditLogService.meta("gameId", created.gameId()));
        return created;
    }

    @Transactional
    public void delete(long id, long adminId) {
        Alias deleted = aliases.delete(id);
        if (deleted == null) {
            throw new ApiException(ErrorCode.SEARCH_ALIAS_NOT_FOUND);
        }
        audit.record(adminId, AuditAction.SEARCH_ALIAS_DELETED, id, "Search alias removed",
                AuditLogService.meta("gameId", deleted.gameId()));
    }
}
