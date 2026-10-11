package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.dto.CommissionRuleRequest;
import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.repository.CommissionRuleRepository;
import com.pegasus.pegasustcgapi.repository.CommissionRuleRepository.Detail;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommissionRuleService {

    private final CommissionRuleRepository rules;

    public CommissionRuleService(CommissionRuleRepository rules) {
        this.rules = rules;
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
        return get(rules.insert(request, adminId));
    }

    @Transactional
    public Detail update(long id, CommissionRuleRequest request) {
        get(id);
        validate(request);
        rules.update(id, request);
        return get(id);
    }

    @Transactional
    public void deactivate(long id) {
        get(id);
        rules.deactivate(id);
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
