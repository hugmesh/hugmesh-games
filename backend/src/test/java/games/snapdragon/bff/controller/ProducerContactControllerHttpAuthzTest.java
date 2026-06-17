package games.snapdragon.bff.controller;

import games.snapdragon.bff.config.BanGateFilter;
import games.snapdragon.bff.config.DomainGuardFilter;
import games.snapdragon.bff.config.RateLimitFilter;
import games.snapdragon.bff.config.SecurityConfig;
import games.snapdragon.bff.config.SecurityHeadersFilter;
import games.snapdragon.bff.controller.support.SecuritySliceSupport;
import games.snapdragon.bff.model.ProducerContact;
import games.snapdragon.bff.repository.ProducerContactRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;

/**
 * HTTP/filter-layer authorization matrix for {@link ProducerContactController}
 * (wave-2c target — previously NO test at all). Two surfaces with two policies:
 * <ul>
 *   <li>player-facing {@code /api/producer-contacts*} — {@code isAuthenticated()}
 *       method gate, NO {@code SecurityConfig} path matcher, so method security
 *       is the SOLE HTTP control; anonymous is 401, the row + the "my
 *       applications" read key on the JWT subject (owner==subject);</li>
 *   <li>admin {@code /api/admin/producer-contacts*} — defence-in-depth: the
 *       {@code /api/admin/**} → {@code hasRole('ADMIN')} path matcher AND the
 *       method {@code @PreAuthorize("hasRole('ADMIN')")}; a USER/MOD is 403 and
 *       the repo decision/query is never reached.</li>
 * </ul>
 * DENY assertions pair the status with {@code verify(never())}.
 */
@WebFluxTest(controllers = ProducerContactController.class,
        excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                BanGateFilter.class, DomainGuardFilter.class,
                RateLimitFilter.class, SecurityHeadersFilter.class}))
@Import({SecurityConfig.class, SecuritySliceSupport.TenantBeans.class})
class ProducerContactControllerHttpAuthzTest {

    @Autowired WebTestClient web;

    @MockitoBean ProducerContactRepository repo;

    private static ProducerContactController.SubmitRequest validSubmit() {
        return new ProducerContactController.SubmitRequest(
                "Studio Name", "studio@example.com", "US",
                ProducerContact.ROLE_PRODUCER,
                "We have shipped three indie titles and want to publish here please.");
    }

    // ── player-facing : isAuthenticated + owner==subject ──────────────────────

    @Test
    void submit_anonymous_is401_andNeverSaves() {
        web.mutateWith(csrf())
                .post().uri("/api/producer-contacts")
                .bodyValue(validSubmit())
                .exchange()
                .expectStatus().isUnauthorized();
        verify(repo, never()).save(any());
    }

    @Test
    void submit_authenticated_is201_stampsJwtSubject() {
        when(repo.findFirstByCasdoorUserIdAndStatusOrderBySubmittedAtDesc(
                eq("alice"), eq(ProducerContact.STATUS_PENDING)))
                .thenReturn(Mono.empty());
        when(repo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        web.mutateWith(SecuritySliceSupport.user("alice"))
                .mutateWith(csrf())
                .post().uri("/api/producer-contacts")
                .bodyValue(validSubmit())
                .exchange()
                .expectStatus().isCreated()
                // the persisted row is stamped with the caller's subject, not a body field
                .expectBody().jsonPath("$.casdoorUserId").isEqualTo("alice");
        verify(repo).save(any());
    }

    @Test
    void mine_anonymous_is401() {
        web.get().uri("/api/producer-contacts/me")
                .exchange()
                .expectStatus().isUnauthorized();
        verify(repo, never()).findByCasdoorUserIdOrderBySubmittedAtDesc(any());
    }

    @Test
    void mine_authenticated_is200_scopedToJwtSubject() {
        when(repo.findByCasdoorUserIdOrderBySubmittedAtDesc(eq("alice"))).thenReturn(Flux.empty());
        web.mutateWith(SecuritySliceSupport.user("alice"))
                .get().uri("/api/producer-contacts/me")
                .exchange()
                .expectStatus().isOk();
        // Scoped to the subject — a caller cannot list another user's applications.
        verify(repo).findByCasdoorUserIdOrderBySubmittedAtDesc(eq("alice"));
        verify(repo, never()).findByCasdoorUserIdOrderBySubmittedAtDesc(eq("bob"));
    }

    // ── admin queue/decision : defence-in-depth role gate ─────────────────────

    @Test
    void pendingQueue_anonymous_is401() {
        web.get().uri("/api/admin/producer-contacts")
                .exchange()
                .expectStatus().isUnauthorized();
        verify(repo, never()).findByStatusOrderBySubmittedAtAsc(any());
    }

    @Test
    void pendingQueue_user_is403() {
        web.mutateWith(SecuritySliceSupport.user("eve"))
                .get().uri("/api/admin/producer-contacts")
                .exchange()
                .expectStatus().isForbidden();
        verify(repo, never()).findByStatusOrderBySubmittedAtAsc(any());
    }

    @Test
    void decide_mod_is403_andNeverPersists() {
        web.mutateWith(SecuritySliceSupport.mod("m"))
                .mutateWith(csrf())
                .post().uri("/api/admin/producer-contacts/1/decision")
                .bodyValue(new ProducerContactController.DecisionRequest("APPROVED", "ok"))
                .exchange()
                .expectStatus().isForbidden();
        verify(repo, never()).findById(any(Long.class));
        verify(repo, never()).save(any());
    }

    @Test
    void pendingQueue_admin_is200() {
        when(repo.findByStatusOrderBySubmittedAtAsc(eq(ProducerContact.STATUS_PENDING)))
                .thenReturn(Flux.empty());
        web.mutateWith(SecuritySliceSupport.admin("root"))
                .get().uri("/api/admin/producer-contacts")
                .exchange()
                .expectStatus().isOk();
        verify(repo).findByStatusOrderBySubmittedAtAsc(eq(ProducerContact.STATUS_PENDING));
    }
}
