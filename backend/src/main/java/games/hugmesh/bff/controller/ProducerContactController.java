package games.hugmesh.bff.controller;

import games.hugmesh.bff.model.ProducerContact;
import games.hugmesh.bff.repository.ProducerContactRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Set;

/**
 * REST surface for the producer-registration federated remote
 * (Roadmap item #8). Three endpoints:
 *
 * <ul>
 *   <li>{@code POST   /api/producer-contacts}        — submit an
 *       application. Authenticated user only; the principal's
 *       Casdoor id is stamped on the row.</li>
 *   <li>{@code GET    /api/producer-contacts/me}     — list the
 *       caller's own submissions.</li>
 *   <li>{@code GET    /api/admin/producer-contacts}  — admin queue
 *       of pending submissions (admin role required).</li>
 *   <li>{@code POST   /api/admin/producer-contacts/{id}/decision}
 *       — admin flips status to APPROVED / REJECTED + records
 *       reviewer notes.</li>
 * </ul>
 *
 * <p>The decision endpoint is the load-bearing one — granting the
 * actual Casdoor role on approval happens via the hugmesh-actions
 * service (which the admin console invokes after a successful
 * decision call). This controller's job is the intake record + the
 * admin queue, not the Casdoor mutation.
 */
@Tag(name = "Producer contacts",
     description = "Producer / mod applications submitted via the federated remote.")
@RestController
@RequestMapping("/api")
public class ProducerContactController {

    private final ProducerContactRepository repo;

    public ProducerContactController(ProducerContactRepository repo) {
        this.repo = repo;
    }

    public record SubmitRequest(
            @NotBlank String displayName,
            @NotBlank @Email String contactEmail,
            @NotBlank String jurisdiction,
            @NotBlank String requestedRole,
            @NotBlank @Size(min = 20) String rationale) {}

    public record DecisionRequest(
            @NotBlank String status,        // APPROVED / REJECTED
            String reviewerNotes) {}

    private static final Set<String> VALID_ROLES = Set.of(
            ProducerContact.ROLE_PRODUCER, ProducerContact.ROLE_MOD);
    private static final Set<String> TERMINAL_STATUSES = Set.of(
            ProducerContact.STATUS_APPROVED, ProducerContact.STATUS_REJECTED);

    // ── Player-facing endpoints ────────────────────────────────────

    @Operation(summary = "Submit a producer / mod application.")
    @PostMapping("/producer-contacts")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ProducerContact> submit(@AuthenticationPrincipal Jwt jwt,
                                         @Valid @RequestBody SubmitRequest req) {
        if (!VALID_ROLES.contains(req.requestedRole())) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "requestedRole must be one of " + VALID_ROLES));
        }
        String casdoorId = jwt.getSubject();
        if (casdoorId == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        }
        // Block double-submissions while a prior one is still pending —
        // the admin queue stays clean + the user can't spam.
        return repo.findFirstByCasdoorUserIdAndStatusOrderBySubmittedAtDesc(
                        casdoorId, ProducerContact.STATUS_PENDING)
            .flatMap(existing -> Mono.<ProducerContact>error(
                    new ResponseStatusException(HttpStatus.CONFLICT,
                            "you already have a pending application; wait for review")))
            .switchIfEmpty(Mono.defer(() -> {
                ProducerContact row = new ProducerContact();
                row.setCasdoorUserId(casdoorId);
                row.setDisplayName(req.displayName());
                row.setContactEmail(req.contactEmail());
                row.setJurisdiction(req.jurisdiction());
                row.setRequestedRole(req.requestedRole());
                row.setRationale(req.rationale());
                row.setStatus(ProducerContact.STATUS_PENDING);
                row.setSubmittedAt(Instant.now());
                return repo.save(row);
            }));
    }

    @Operation(summary = "List the caller's own applications.")
    @GetMapping("/producer-contacts/me")
    @PreAuthorize("isAuthenticated()")
    public Flux<ProducerContact> mine(@AuthenticationPrincipal Jwt jwt) {
        String casdoorId = jwt.getSubject();
        if (casdoorId == null) {
            return Flux.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        }
        return repo.findByCasdoorUserIdOrderBySubmittedAtDesc(casdoorId);
    }

    // ── Admin endpoints ───────────────────────────────────────────

    @Operation(summary = "Admin queue: every PENDING submission, oldest first.")
    @GetMapping("/admin/producer-contacts")
    @PreAuthorize("hasRole('ADMIN')")
    public Flux<ProducerContact> pendingQueue() {
        return repo.findByStatusOrderBySubmittedAtAsc(ProducerContact.STATUS_PENDING);
    }

    @Operation(summary = "Record an admin decision on an application.")
    @PostMapping("/admin/producer-contacts/{id}/decision")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<ProducerContact> decide(@AuthenticationPrincipal Jwt jwt,
                                         @PathVariable Long id,
                                         @Valid @RequestBody DecisionRequest req) {
        if (!TERMINAL_STATUSES.contains(req.status())) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "status must be APPROVED or REJECTED"));
        }
        return repo.findById(id)
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
            .flatMap(row -> {
                if (!ProducerContact.STATUS_PENDING.equals(row.getStatus())) {
                    return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                            "application already decided; create a new submission"));
                }
                row.setStatus(req.status());
                row.setReviewerNotes(req.reviewerNotes());
                row.setReviewedByCasdoorId(jwt.getSubject());
                row.setReviewedAt(Instant.now());
                return repo.save(row);
            });
    }
}
