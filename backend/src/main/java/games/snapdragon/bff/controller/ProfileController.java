package games.snapdragon.bff.controller;

import games.snapdragon.bff.model.accounts.ModProfile;
import games.snapdragon.bff.model.accounts.PlayerProfile;
import games.snapdragon.bff.model.accounts.ProducerProfile;
import games.snapdragon.bff.repository.accounts.ModProfileRepository;
import games.snapdragon.bff.repository.accounts.PlayerProfileRepository;
import games.snapdragon.bff.repository.accounts.ProducerProfileRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Per-role profile surface — reads + updates the caller's own
 * {@code accounts.{players,producers,mods}} row. One controller per
 * accounts-schema table; the role gates ensure a player can only
 * touch their player profile, a producer their producer profile, etc.
 *
 * <p>Player profile is LAZY: the first GET creates a default row
 * if none exists. Producer / Mod profiles are STRICT: the row only
 * exists after an admin has APPROVED the matching producer_contacts
 * application, so a GET on a missing row returns 404 (the caller
 * doesn't yet hold the role).
 *
 * <p>Update endpoints accept only the role-owner-mutable fields
 * (display name, contact email, on-call slot). Admin-only fields
 * (approved_via_contact_id, approved_at) flow through the admin
 * decision endpoint on producer-contacts, never through here.
 */
@Tag(name = "Profile",
     description = "Per-role profile carrying the role's load-bearing state.")
@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final PlayerProfileRepository players;
    private final ProducerProfileRepository producers;
    private final ModProfileRepository mods;

    public ProfileController(PlayerProfileRepository players,
                             ProducerProfileRepository producers,
                             ModProfileRepository mods) {
        this.players = players;
        this.producers = producers;
        this.mods = mods;
    }

    public record PlayerUpdate(@NotBlank String displayName) {}
    public record ProducerUpdate(@NotBlank String displayName,
                                  @NotBlank String contactEmail,
                                  @NotBlank String jurisdiction) {}
    public record ModUpdate(@NotBlank String displayName,
                             @NotBlank String contactEmail,
                             String oncallSlot) {}

    // ── Player ──────────────────────────────────────────────────────

    @Operation(summary = "Read the caller's player profile (lazy-creates on first call).")
    @GetMapping("/me/player")
    @PreAuthorize("isAuthenticated()")
    public Mono<PlayerProfile> getPlayer(@AuthenticationPrincipal Jwt jwt) {
        String casdoorId = jwt.getSubject();
        return players.findById(casdoorId)
                .switchIfEmpty(Mono.defer(() -> {
                    PlayerProfile fresh = new PlayerProfile();
                    fresh.setCasdoorUserId(casdoorId);
                    Object name = jwt.getClaim("name");
                    fresh.setDisplayName(name == null ? casdoorId : name.toString());
                    fresh.setXpLevel(1);
                    fresh.setFriendCount(0);
                    fresh.setBlockCount(0);
                    fresh.setCreatedAt(Instant.now());
                    fresh.setUpdatedAt(Instant.now());
                    return players.save(fresh.markNew());
                }));
    }

    @Operation(summary = "Update the caller's player profile (display name only).")
    @PutMapping("/me/player")
    @PreAuthorize("isAuthenticated()")
    public Mono<PlayerProfile> updatePlayer(@AuthenticationPrincipal Jwt jwt,
                                              @Valid @RequestBody PlayerUpdate req) {
        String casdoorId = jwt.getSubject();
        return players.findById(casdoorId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .flatMap(row -> {
                    row.setDisplayName(req.displayName());
                    row.setUpdatedAt(Instant.now());
                    return players.save(row);
                });
    }

    // ── Producer ────────────────────────────────────────────────────

    @Operation(summary = "Read the caller's producer profile.")
    @GetMapping("/me/producer")
    @PreAuthorize("hasRole('PUBLISHER') or hasRole('PRODUCER')")
    public Mono<ProducerProfile> getProducer(@AuthenticationPrincipal Jwt jwt) {
        return producers.findById(jwt.getSubject())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no producer profile — submit a producer_contacts application first")));
    }

    @Operation(summary = "Update the caller's producer profile.")
    @PutMapping("/me/producer")
    @PreAuthorize("hasRole('PUBLISHER') or hasRole('PRODUCER')")
    public Mono<ProducerProfile> updateProducer(@AuthenticationPrincipal Jwt jwt,
                                                  @Valid @RequestBody ProducerUpdate req) {
        return producers.findById(jwt.getSubject())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .flatMap(row -> {
                    row.setDisplayName(req.displayName());
                    row.setContactEmail(req.contactEmail());
                    row.setJurisdiction(req.jurisdiction());
                    row.setUpdatedAt(Instant.now());
                    return producers.save(row);
                });
    }

    // ── Mod ─────────────────────────────────────────────────────────

    @Operation(summary = "Read the caller's mod profile.")
    @GetMapping("/me/mod")
    @PreAuthorize("hasRole('MOD')")
    public Mono<ModProfile> getMod(@AuthenticationPrincipal Jwt jwt) {
        return mods.findById(jwt.getSubject())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no mod profile — submit a producer_contacts application first")));
    }

    @Operation(summary = "Update the caller's mod profile.")
    @PutMapping("/me/mod")
    @PreAuthorize("hasRole('MOD')")
    public Mono<ModProfile> updateMod(@AuthenticationPrincipal Jwt jwt,
                                       @Valid @RequestBody ModUpdate req) {
        return mods.findById(jwt.getSubject())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .flatMap(row -> {
                    row.setDisplayName(req.displayName());
                    row.setContactEmail(req.contactEmail());
                    row.setOncallSlot(req.oncallSlot());
                    row.setUpdatedAt(Instant.now());
                    return mods.save(row);
                });
    }
}
