package games.snapdragon.bff.controller;

import games.snapdragon.bff.model.FederationManifestDto;
import games.snapdragon.bff.model.FederationManifestDto.FederationRemoteDto;
import games.snapdragon.bff.repository.FederationModuleRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Runtime federation manifest. The Angular host bootstrap fetches this and
 * hands the remotes list to native-federation's {@code initFederation(...)}
 * before {@code bootstrapApplication}. Replaces a static
 * {@code federation.manifest.json} so new game remotes appear in the portal
 * without redeploying the host shell.
 *
 * <p>Public on purpose: the host is unauthenticated until login completes,
 * but it still needs to know where the {@code login} remote lives. Per-remote
 * access (e.g. {@code admin}) is enforced in the host's route guard against
 * the JWT's {@code roles} claim — see {@code SecurityConfig.casdoorAuthoritiesConverter}.
 */
@Tag(name = "Federation", description = "Runtime module-federation manifest, served from PostgreSQL.")
@RestController
@RequestMapping("/api/federation")
public class FederationController {

    private final FederationModuleRepository repo;

    public FederationController(FederationModuleRepository repo) {
        this.repo = repo;
    }

    @Operation(
        summary = "Active federation manifest",
        description = """
            Returns every enabled `federation_modules` row, ordered by kind then slug.
            The Angular host calls this on bootstrap and on every full reload; the
            response is small (one row per remote module) so we don't bother caching.
            """)
    @GetMapping("/manifest")
    Mono<FederationManifestDto> manifest() {
        return repo.findAllByEnabledTrueOrderByKindAscSlugAsc()
                .map(FederationRemoteDto::from)
                .collectList()
                .map(remotes -> new FederationManifestDto(Instant.now().toString(), remotes));
    }
}
