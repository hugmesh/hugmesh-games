package com.threewizards.controller;

import com.threewizards.controller.GameAccessController.AccessGrantRequest;
import com.threewizards.repository.GameAccessRepository;
import com.threewizards.repository.GameRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Validation rules on the access-grant endpoint. Mocks the repos because
 * the rule checks happen before any DB call — Mono.error is reached
 * synchronously and never touches the lookup.
 */
class GameAccessControllerTest {

    private final GameAccessController ctl = new GameAccessController(
            mock(GameAccessRepository.class),
            mock(GameRepository.class)
    );

    @Test
    void rejectsUnknownScope() {
        Mono<?> result = ctl.grant("any-slug",
                new AccessGrantRequest("everyone", null), null);
        StepVerifier.create(result)
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(err.getMessage()).contains("scope must be one of");
                })
                .verify();
    }

    @Test
    void rejectsRoleScopeWithoutValue() {
        Mono<?> result = ctl.grant("any-slug",
                new AccessGrantRequest("role", "  "), null);
        StepVerifier.create(result)
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(err.getMessage()).contains("value is required");
                })
                .verify();
    }

    @Test
    void rejectsUserScopeWithoutValue() {
        Mono<?> result = ctl.grant("any-slug",
                new AccessGrantRequest("user", null), null);
        StepVerifier.create(result)
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(err.getMessage()).contains("value is required");
                })
                .verify();
    }

    @Test
    void revokeRejectsUnknownScope() {
        Mono<?> result = ctl.revoke("any-slug", "everyone", null);
        StepVerifier.create(result)
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(err.getMessage()).contains("scope must be one of");
                })
                .verify();
    }
}
