package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.ForbiddenException;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.RoleCode;
import com.pegasus.pegasustcgapi.model.SellerProfile;
import com.pegasus.pegasustcgapi.model.SellerStatus;
import com.pegasus.pegasustcgapi.model.SellerVerification;
import com.pegasus.pegasustcgapi.model.VerificationStatus;
import com.pegasus.pegasustcgapi.repository.PayoutAccountRepository;
import com.pegasus.pegasustcgapi.repository.SellerProfileRepository;
import com.pegasus.pegasustcgapi.repository.SellerVerificationRepository;
import com.pegasus.pegasustcgapi.repository.UserRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turning a buyer into a seller, on the same account they already have [RQ-1,2].
 *
 * <p>The path is: apply, submit a name and a bank account, wait for an admin.
 * Approval is the only thing that grants the SELLER role, and it is also the
 * only thing that sets {@code seller_profile.status} to VERIFIED — the two must
 * move together or someone ends up with a role they cannot use, or a status that
 * lets them past a check they should not pass.
 *
 * <p>No identity documents are collected. The claim being checked is that the
 * person owns the account the money will go to, which is what a payout dispute
 * actually turns on.
 */
@Service
public class SellerOnboardingService {

    private final SellerProfileRepository profiles;
    private final SellerVerificationRepository verifications;
    private final UserRepository users;
    private final PayoutAccountRepository payoutAccounts;
    private final RoleService roles;
    private final Clock clock;

    public SellerOnboardingService(
            SellerProfileRepository profiles,
            SellerVerificationRepository verifications,
            UserRepository users,
            PayoutAccountRepository payoutAccounts,
            RoleService roles,
            Clock clock) {

        this.profiles = profiles;
        this.verifications = verifications;
        this.users = users;
        this.payoutAccounts = payoutAccounts;
        this.roles = roles;
        this.clock = clock;
    }

    // ---------- seller's own view ----------

    public SellerProfile requireProfile(long userId) {
        return profiles.findByUserId(userId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.SELLER_NOT_FOUND));
    }

    /**
     * The gate every listing endpoint calls before letting anything be published.
     *
     * @throws ForbiddenException when the profile exists but is not VERIFIED
     */
    public SellerProfile requireVerifiedSeller(long userId) {
        SellerProfile profile = requireProfile(userId);
        if (!profile.canPublish()) {
            throw new ForbiddenException(ErrorCode.SELLER_NOT_VERIFIED,
                    "Seller profile is " + profile.status() + "; only VERIFIED may publish listings");
        }
        return profile;
    }

    /** Creates the profile in NOT_APPLIED, or returns the one already there. Safe to call twice. */
    @Transactional
    public SellerProfile startApplication(long userId) {
        if (users.findById(userId).isEmpty()) {
            throw new NotFoundException(ErrorCode.USER_NOT_FOUND);
        }
        long id = profiles.createIfAbsent(userId);
        return profiles.findById(id).orElseThrow(
                () -> new NotFoundException(ErrorCode.SELLER_NOT_FOUND));
    }

    /**
     * Files a name and a bank account for review.
     *
     * @param bankAccountNumber digits only by the time it gets here; the DTO strips
     *        the dashes people type, so the same account cannot slip past the
     *        duplicate check written two different ways
     */
    @Transactional
    public SellerVerification submitVerification(
            long userId, String legalFirstName, String legalLastName,
            String bankCode, String bankName, String bankAccountNumber) {

        SellerProfile profile = startApplication(userId);

        if (profile.status() == SellerStatus.SUSPENDED) {
            throw new ForbiddenException(ErrorCode.ACCESS_DENIED,
                    "A suspended seller cannot submit a new verification");
        }
        if (verifications.hasOpenRequest(profile.id())) {
            throw new ConflictException(ErrorCode.VERIFICATION_IN_REVIEW);
        }

        if (verifications.bankAccountUsedByAnother(bankAccountNumber, profile.id())) {
            throw new ConflictException(ErrorCode.BANK_ACCOUNT_ALREADY_USED);
        }

        long id = verifications.insert(profile.id(), legalFirstName, legalLastName,
                bankCode, bankName, bankAccountNumber);

        // An already-verified seller is changing bank, not reapplying: dropping them
        // to PENDING would pull their listings down while they wait.
        if (profile.status() != SellerStatus.VERIFIED) {
            profiles.updateStatus(profile.id(), SellerStatus.PENDING, null, null);
        }

        return verifications.findById(id).orElseThrow(
                () -> new NotFoundException(ErrorCode.VERIFICATION_NOT_FOUND));
    }

    public List<SellerVerification> myVerifications(long userId) {
        return verifications.findByProfileId(requireProfile(userId).id());
    }

    @Transactional
    public SellerProfile updateSettings(
            long userId, short handlingDays, boolean vacationMode, boolean autoAcceptOrders) {

        SellerProfile profile = requireProfile(userId);
        profiles.updateSettings(profile.id(), handlingDays, vacationMode, autoAcceptOrders);
        return requireProfile(userId);
    }

    // ---------- admin review ----------

    public List<SellerVerification> queue(VerificationStatus status, int page, int size) {
        return verifications.findByStatus(status, size, page * size);
    }

    public long queueSize(VerificationStatus status) {
        return verifications.countByStatus(status);
    }

    /** Claims a request so a second admin opening the queue can see it is taken. */
    @Transactional
    public SellerVerification startReview(long verificationId, long adminId) {
        return decide(verificationId, VerificationStatus.SUBMITTED, VerificationStatus.UNDER_REVIEW,
                adminId, null, null);
    }

    /**
     * Approves the submission and, in the same transaction, hands over the SELLER
     * role, flips the profile to VERIFIED, and registers the account that was
     * just verified as the seller's payout target — so nobody has to type the
     * same bank details a second time.
     */
    @Transactional
    public SellerVerification approve(long verificationId, long adminId) {
        SellerVerification request = require(verificationId);
        OffsetDateTime now = OffsetDateTime.now(clock);

        SellerVerification approved = decide(verificationId, request.status(),
                VerificationStatus.APPROVED, adminId, now, null);

        profiles.updateStatus(request.sellerProfileId(), SellerStatus.VERIFIED, now, null);

        SellerProfile profile = profiles.findById(request.sellerProfileId()).orElseThrow(
                () -> new NotFoundException(ErrorCode.SELLER_NOT_FOUND));
        roles.grant(profile.userId(), Set.of(RoleCode.SELLER), adminId);

        registerVerifiedAccount(request);
        return approved;
    }

    /**
     * Points the seller's payouts at the account that was just checked.
     *
     * <p>The only place a payout account is ever written, which is what
     * guarantees a seller is only paid into an account somebody approved. A
     * seller who re-verifies to change bank has their one account replaced, so
     * there is never a stale account left to be paid into by mistake.
     */
    private void registerVerifiedAccount(SellerVerification request) {
        payoutAccounts.upsert(request.sellerProfileId(), request.id(), request.bankCode(),
                request.bankName(), request.legalName(), request.bankAccountNumber());
    }

    /** The profile drops back to REJECTED; the seller may submit a new document afterwards. */
    @Transactional
    public SellerVerification reject(long verificationId, long adminId, String reason) {
        SellerVerification request = require(verificationId);

        SellerVerification rejected = decide(verificationId, request.status(),
                VerificationStatus.REJECTED, adminId, OffsetDateTime.now(clock), reason);

        profiles.updateStatus(request.sellerProfileId(), SellerStatus.REJECTED, null, null);
        return rejected;
    }

    private SellerVerification require(long verificationId) {
        return verifications.findById(verificationId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.VERIFICATION_NOT_FOUND));
    }

    /**
     * Moves a request only from the state it was actually in. The conditional
     * update is what stops two admins deciding the same request at once — the
     * second one finds nothing to update and is told so.
     */
    private SellerVerification decide(long verificationId, VerificationStatus from,
            VerificationStatus to, long adminId, OffsetDateTime reviewedAt, String reason) {

        if (from.isFinal()) {
            throw new ConflictException(ErrorCode.VERIFICATION_ALREADY_DECIDED);
        }
        boolean moved = verifications.transition(
                verificationId, from, to, adminId, reviewedAt, reason);

        if (!moved) {
            throw new ConflictException(ErrorCode.VERIFICATION_ALREADY_DECIDED,
                    "Another admin changed this request first");
        }
        return require(verificationId);
    }
}
