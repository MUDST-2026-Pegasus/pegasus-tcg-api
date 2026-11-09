package com.pegasus.pegasustcgapi.service.finance.commission;

import com.pegasus.pegasustcgapi.model.audit.AuditAction;
import com.pegasus.pegasustcgapi.service.audit.AuditLogService;
import com.pegasus.pegasustcgapi.dto.finance.commission.CommissionRuleRequest;
import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.repository.finance.commission.CommissionRuleRepository;
import com.pegasus.pegasustcgapi.repository.finance.commission.CommissionRuleRepository.Detail;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommissionRuleService {

    private final CommissionRuleRepository rules;
    private final AuditLogService audit;

    public CommissionRuleService(CommissionRuleRepository rules, AuditLogService audit) {
        this.rules = rules;
        this.audit = audit;
    }

    public List<Detail> list() {
        return rules.findAll();
    }

    public Detail get(long id) {
        return rules.findById(id)
                .orElseThrow(() -> new NotFoundException(ErrorCode.COMMISSION_RULE_NOT_FOUND));
    }

    @Transactional
    public Detail create(CommissionRuleRequest request, long adminId) {
        validate(request);
        Detail created = get(rules.insert(request, adminId));
        audit.record(adminId, AuditAction.COMMISSION_RULE_CREATED, created.id(),
                "Created commission rule #" + created.id(), AuditLogService.meta("rule", created));
        return created;
    }

    @Transactional
    public Detail update(long id, CommissionRuleRequest request, long adminId) {
        Detail before = get(id);
        validate(request);
        rules.update(id, request);
        Detail after = get(id);
        audit.record(adminId, AuditAction.COMMISSION_RULE_UPDATED, id,
                "Updated commission rule #" + id, AuditLogService.diff(before, after));
        return after;
    }

    @Transactional
    public void deactivate(long id, long adminId) {
        get(id);
        rules.deactivate(id);
        audit.record(adminId, AuditAction.COMMISSION_RULE_DEACTIVATED, id,
                "Deactivated commission rule #" + id, Map.of());
    }

    @Transactional
    public void delete(long id, long adminId) {
        Detail doomed = get(id);
        rules.delete(id);
        audit.record(adminId, AuditAction.COMMISSION_RULE_DELETED, id,
                "Deleted commission rule #" + id, AuditLogService.meta("rule", doomed));
    }

    private void validate(CommissionRuleRequest r) {
        if (r.scope() == null || r.ratePercent() == null || r.fixedFee() == null
                || r.effectiveFrom() == null || r.ratePercent().signum() < 0
                || r.ratePercent().compareTo(new BigDecimal("100")) > 0
                || r.ratePercent().scale() > 3 || !validMoney(r.fixedFee())
                || (r.minFee() != null && !validMoney(r.minFee()))
                || (r.maxFee() != null && !validMoney(r.maxFee()))
                || (r.minFee() != null && r.maxFee() != null
                    && r.minFee().compareTo(r.maxFee()) > 0)
                || (r.effectiveTo() != null && !r.effectiveFrom().isBefore(r.effectiveTo()))) {
            throw new BadRequestException(ErrorCode.COMMISSION_RULE_INVALID);
        }
        boolean targetMatches = switch (r.scope()) {
            case "GLOBAL" -> r.gameId() == null && r.categoryId() == null && r.sellerProfileId() == null;
            case "GAME" -> r.gameId() != null && r.categoryId() == null && r.sellerProfileId() == null;
            case "CATEGORY" -> r.gameId() == null && r.categoryId() != null && r.sellerProfileId() == null;
            case "SELLER" -> r.gameId() == null && r.categoryId() == null && r.sellerProfileId() != null;
            default -> false;
        };
        if (!targetMatches) throw new BadRequestException(ErrorCode.COMMISSION_RULE_INVALID);
        if (!rules.targetExists(r.scope(), r.gameId(), r.categoryId(), r.sellerProfileId())) {
            throw new NotFoundException(switch (r.scope()) {
                case "GAME" -> ErrorCode.GAME_NOT_FOUND;
                case "CATEGORY" -> ErrorCode.CATEGORY_NOT_FOUND;
                default -> ErrorCode.SELLER_NOT_FOUND;
            });
        }
    }

    private static boolean validMoney(BigDecimal amount) {
        return amount.signum() >= 0 && amount.scale() <= 2;
    }
}
