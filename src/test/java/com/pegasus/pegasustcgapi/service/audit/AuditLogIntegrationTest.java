package com.pegasus.pegasustcgapi.service.audit;

import static com.pegasus.pegasustcgapi.jooq.tables.AuditLog.AUDIT_LOG;
import static com.pegasus.pegasustcgapi.jooq.tables.PlatformSetting.PLATFORM_SETTING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pegasus.pegasustcgapi.jooq.tables.records.AuditLogRecord;
import com.pegasus.pegasustcgapi.model.audit.AuditAction;
import com.pegasus.pegasustcgapi.model.user.RoleCode;
import com.pegasus.pegasustcgapi.repository.seller.SellerVerificationRepository;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.seller.SellerOnboardingService;
import com.pegasus.pegasustcgapi.service.setting.PlatformSettingService;
import com.pegasus.pegasustcgapi.service.user.RoleService;
import com.pegasus.pegasustcgapi.support.ApiClient;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** TCG-431: rows are written with the action, roll back with it, hold no PII, and can be searched. */
class AuditLogIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private AuditLogService audit;

    @Autowired
    private SellerOnboardingService onboarding;

    @Autowired
    private SellerVerificationRepository verifications;

    @Autowired
    private PlatformSettingService settings;

    @Autowired
    private RoleService roles;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private List<AuditLogRecord> rowsFor(AuditAction action, Object targetId) {
        return dsl.selectFrom(AUDIT_LOG)
                .where(AUDIT_LOG.ACTION.eq(action.name()))
                .and(AUDIT_LOG.TARGET_ID.eq(String.valueOf(targetId)))
                .orderBy(AUDIT_LOG.ID)
                .fetch();
    }

    /** A submitted request with a recognisable legal name and account number. */
    private long submittedVerification(AuthPrincipal applicant, String account) {
        long profileId = onboarding.startApplication(applicant.userId()).id();
        return verifications.insert(profileId, "Somchai", "Jaidee", "KBANK", "Kasikorn",
                account, "verifications/" + UUID.randomUUID() + ".jpg");
    }

    @Test
    @DisplayName("approving a verification writes one row with the admin, and no name or account number")
    void approvalIsLoggedWithoutPii() {
        AuthPrincipal admin = data.admin();
        AuthPrincipal applicant = data.buyerWithoutAddress();
        String account = "9" + String.format("%09d", applicant.userId());
        long verificationId = submittedVerification(applicant, account);

        onboarding.approve(verificationId, admin.userId());

        List<AuditLogRecord> rows = rowsFor(AuditAction.SELLER_VERIFICATION_APPROVED, verificationId);
        assertThat(rows).hasSize(1);
        AuditLogRecord row = rows.getFirst();
        assertThat(row.getActorUserId()).isEqualTo(admin.userId());
        assertThat(row.getTargetType()).isEqualTo("SELLER_VERIFICATION");
        assertThat(row.getSummary()).contains("#" + verificationId);
        String metadata = row.getMetadata().data();
        assertThat(metadata).contains("sellerProfileId")
                .doesNotContain(account)
                .doesNotContain("Somchai")
                .doesNotContain("Jaidee")
                .doesNotContain("verifications/");
    }

    @Test
    @DisplayName("an action that fails after logging leaves no row behind")
    void rolledBackActionLeavesNoRow() {
        AuthPrincipal admin = data.admin();
        AuthPrincipal target = data.buyerWithoutAddress();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            roles.grantAsAdmin(target.userId(), RoleCode.SELLER, admin.userId());
            throw new IllegalStateException("boom after the grant");
        })).hasMessageContaining("boom");

        assertThat(rowsFor(AuditAction.USER_ROLE_GRANTED, target.userId())).isEmpty();

        roles.grantAsAdmin(target.userId(), RoleCode.SELLER, admin.userId());
        assertThat(rowsFor(AuditAction.USER_ROLE_GRANTED, target.userId())).hasSize(1);
    }

    @Test
    @DisplayName("an action refused by validation writes nothing")
    void refusedActionWritesNothing() {
        AuthPrincipal admin = data.admin();
        long before = dsl.fetchCount(AUDIT_LOG, AUDIT_LOG.ACTOR_USER_ID.eq(admin.userId()));

        assertThatThrownBy(() -> settings.update(PlatformSettingService.ORDER_CANCEL_WINDOW_HOURS,
                "{not json", admin.userId()));

        assertThat(dsl.fetchCount(AUDIT_LOG, AUDIT_LOG.ACTOR_USER_ID.eq(admin.userId()))).isEqualTo(before);
    }

    @Test
    @DisplayName("record() refuses to run outside a transaction")
    void recordNeedsATransaction() {
        assertThatThrownBy(() -> audit.record(null, AuditAction.SETTING_UPDATED, "x", "x", null))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("a setting change keeps the old and new value; the bank account setting keeps neither")
    void settingChangeKeepsBeforeAndAfter() {
        AuthPrincipal admin = data.admin();
        String key = PlatformSettingService.ORDER_CANCEL_WINDOW_HOURS;
        data.setting(key, "24");

        settings.update(key, "36", admin.userId());

        AuditLogRecord row = rowsFor(AuditAction.SETTING_UPDATED, key).getLast();
        assertThat(row.getActorUserId()).isEqualTo(admin.userId());
        assertThat(row.getMetadata().data()).contains("\"before\": 24").contains("\"after\": 36");

        String account = PlatformSettingService.PAYMENT_TRANSFER_ACCOUNT;
        String current = dsl.select(PLATFORM_SETTING.SETTING_VALUE).from(PLATFORM_SETTING)
                .where(PLATFORM_SETTING.SETTING_KEY.eq(account)).fetchSingle(PLATFORM_SETTING.SETTING_VALUE).data();
        data.setting(account, current);

        settings.update(account, current, admin.userId());

        AuditLogRecord redacted = rowsFor(AuditAction.SETTING_UPDATED, account).getLast();
        assertThat(redacted.getMetadata().data()).contains("redacted").doesNotContain("before");
    }

    private void row(OffsetDateTime at, Long actor, AuditAction action) {
        dsl.insertInto(AUDIT_LOG)
                .set(AUDIT_LOG.OCCURRED_AT, at)
                .set(AUDIT_LOG.ACTOR_USER_ID, actor)
                .set(AUDIT_LOG.ACTION, action.name())
                .set(AUDIT_LOG.TARGET_TYPE, action.targetType().name())
                .set(AUDIT_LOG.TARGET_ID, "1")
                .set(AUDIT_LOG.SUMMARY, "seeded")
                .execute();
    }

    @Test
    @DisplayName("GET /admin/audit-logs/summary counts one window: totals, admin vs system, per action, top admins")
    void summaryEndpoint() {
        AuthPrincipal busy = data.admin();
        AuthPrincipal quiet = data.admin();
        // A day far in the past that no other test writes to.
        OffsetDateTime day = OffsetDateTime.of(2001, 2, 3, 0, 0, 0, 0, ZoneOffset.ofHours(7));
        row(day.plusHours(1), busy.userId(), AuditAction.SETTING_UPDATED);
        row(day.plusHours(2), busy.userId(), AuditAction.USER_ROLE_GRANTED);
        row(day.plusHours(3), quiet.userId(), AuditAction.SETTING_UPDATED);
        row(day.plusHours(4), null, AuditAction.ORDERS_AUTO_RELEASED);
        row(day.plusDays(1), busy.userId(), AuditAction.SETTING_UPDATED); // outside the window

        ApiClient api = new ApiClient(port);
        String bearer = data.bearer(busy);
        String window = "?from=" + day.toInstant() + "&to=" + day.plusDays(1).toInstant();

        ApiClient.Response response = api.get("/api/v1/admin/audit-logs/summary" + window, bearer);
        assertThat(response.status()).isEqualTo(200);
        JsonNode summary = response.data();
        assertThat(summary.path("total").asLong()).isEqualTo(4);
        assertThat(summary.path("byAdmins").asLong()).isEqualTo(3);
        assertThat(summary.path("bySystem").asLong()).isEqualTo(1);
        assertThat(summary.path("byAction").path("SETTING_UPDATED").asLong()).isEqualTo(2);
        assertThat(summary.path("byAction").path("ORDERS_AUTO_RELEASED").asLong()).isEqualTo(1);
        JsonNode top = summary.path("topActors");
        assertThat(top.size()).isEqualTo(2);
        assertThat(top.get(0).path("userId").asLong()).isEqualTo(busy.userId());
        assertThat(top.get(0).path("count").asLong()).isEqualTo(2);
        assertThat(top.get(0).path("username").asString()).isEqualTo(busy.username());

        String backwards = "?from=" + day.plusDays(1).toInstant() + "&to=" + day.toInstant();
        assertThat(api.get("/api/v1/admin/audit-logs/summary" + backwards, bearer).status()).isEqualTo(400);
        AuthPrincipal buyer = data.buyerWithoutAddress();
        assertThat(api.get("/api/v1/admin/audit-logs/summary" + window, data.bearer(buyer)).status())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("GET /admin/audit-logs filters, searches, pages and joins the actor; non-admins get 403")
    void searchEndpoint() {
        AuthPrincipal admin = data.admin();
        AuthPrincipal other = data.admin();
        AuthPrincipal target = data.buyerWithoutAddress();
        roles.grantAsAdmin(target.userId(), RoleCode.SELLER, admin.userId());
        roles.revokeAsAdmin(target.userId(), RoleCode.SELLER, admin.userId());
        roles.grantAsAdmin(target.userId(), RoleCode.SELLER, other.userId());

        ApiClient api = new ApiClient(port);
        String bearer = data.bearer(admin);
        String base = "/api/v1/admin/audit-logs?targetType=USER&targetId=" + target.userId();

        ApiClient.Response all = api.get(base, bearer);
        assertThat(all.status()).isEqualTo(200);
        JsonNode page = all.data();
        assertThat(page.path("totalItems").asLong()).isEqualTo(3);
        // Newest first.
        assertThat(page.path("items").get(0).path("actor").path("userId").asLong()).isEqualTo(other.userId());
        assertThat(page.path("items").get(0).path("actor").path("username").asString()).isEqualTo(other.username());

        JsonNode byActor = api.get(base + "&actorUserId=" + admin.userId(), bearer).data();
        assertThat(byActor.path("totalItems").asLong()).isEqualTo(2);

        JsonNode byActions = api.get(base + "&action=USER_ROLE_REVOKED&action=USER_ROLE_GRANTED", bearer).data();
        assertThat(byActions.path("totalItems").asLong()).isEqualTo(3);
        JsonNode revokedOnly = api.get(base + "&action=USER_ROLE_REVOKED", bearer).data();
        assertThat(revokedOnly.path("totalItems").asLong()).isEqualTo(1);

        JsonNode searched = api.get(base + "&q=revoked%20seller", bearer).data();
        assertThat(searched.path("totalItems").asLong()).isEqualTo(1);

        JsonNode paged = api.get(base + "&size=2&page=1", bearer).data();
        assertThat(paged.path("items").size()).isEqualTo(1);
        assertThat(paged.path("totalPages").asInt()).isEqualTo(2);

        assertThat(api.get(base + "&action=NOT_AN_ACTION", bearer).status()).isEqualTo(400);
        assertThat(api.get(base, data.bearer(target)).status()).isEqualTo(403);
        assertThat(api.get(base, null).status()).isEqualTo(401);
    }
}
