package games.snapdragon.bff.controller;

import games.snapdragon.bff.model.accounts.ModProfile;
import games.snapdragon.bff.repository.accounts.ModProfileRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Resolves the appeal-contact email for a revoked / banned user.
 *
 * <p>The federated {@code banned} remote renders an appeal email
 * template that needs a recipient address. A revoked user has no
 * JWT (the gateway stamps {@code X-Revoked} on every request from
 * a banned key), so the resolver must be publicly reachable.
 *
 * <p>Resolution order:
 *
 * <ol>
 *   <li>{@code accounts.mods.contact_email} where
 *       {@code oncall_slot = 'primary'} — the dispute-routing
 *       pipeline's primary recipient.</li>
 *   <li>{@code accounts.mods.contact_email} where
 *       {@code oncall_slot = 'secondary'} — fall back if the
 *       primary slot is empty.</li>
 *   <li>{@code app.security.appeal-contact-fallback} — last-resort
 *       support address (defaults to {@code support@snapdragon.games}).</li>
 * </ol>
 *
 * <p>The endpoint deliberately exposes only the email string — no
 * mod identity, no on-call slot label, nothing that could be used
 * for targeted phishing or reconnaissance against the mod team.
 */
@Tag(name = "Security",
     description = "Appeal-contact resolver for revoked users. Anonymous-accessible by design.")
@RestController
@RequestMapping("/api/security")
public class AppealContactController {

    private final ModProfileRepository mods;
    private final String fallback;

    public AppealContactController(
            ModProfileRepository mods,
            @Value("${app.security.appeal-contact-fallback:support@snapdragon.games}") String fallback) {
        this.mods     = mods;
        this.fallback = fallback;
    }

    @Operation(
        summary = "Resolve the appeal-contact email shown to revoked users.",
        description = """
            Looks up the current on-call mod's contact_email. Returns
            `{ "email": "<address>" }`. Falls back to a configurable
            platform address when no on-call mod has a contact email
            on file.
            """)
    @GetMapping("/appeal-contact")
    public Mono<Map<String, String>> appealContact() {
        return mods.findByOncallSlot(ModProfile.SLOT_PRIMARY)
                .next()
                .map(ModProfile::getContactEmail)
                .switchIfEmpty(Mono.defer(() ->
                        mods.findByOncallSlot(ModProfile.SLOT_SECONDARY)
                                .next()
                                .map(ModProfile::getContactEmail)))
                .defaultIfEmpty(fallback)
                .map(email -> Map.of("email", email));
    }
}
