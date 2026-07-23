package games.hugmesh.bff.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Test-only downstream endpoint for {@link FilterOrderingLiveChainTest}. Lives under
 * {@code /api/characters/**} (a {@code PLAYER_PATHS} authenticated() prefix) so a request
 * that passes every WebFilter has somewhere to land — proving the filters deny
 * SELECTIVELY (the clean in-brand token reaches here) rather than blanket-allowing.
 */
@RestController
public class PingTestController {

    @GetMapping("/api/characters/ping")
    public Mono<String> ping() {
        return Mono.just("pong");
    }
}
