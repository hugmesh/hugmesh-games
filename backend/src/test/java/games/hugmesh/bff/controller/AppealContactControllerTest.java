package games.hugmesh.bff.controller;

import games.hugmesh.bff.model.accounts.ModProfile;
import games.hugmesh.bff.repository.accounts.ModProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Resolver order: primary on-call → secondary on-call → fallback.
 */
class AppealContactControllerTest {

    private ModProfileRepository mods;
    private AppealContactController ctl;

    @BeforeEach
    void setUp() {
        mods = mock(ModProfileRepository.class);
        ctl  = new AppealContactController(mods, "fallback@example.com");
    }

    private static ModProfile mod(String email, String slot) {
        ModProfile m = new ModProfile();
        m.setContactEmail(email);
        m.setOncallSlot(slot);
        return m;
    }

    @Test
    void primary_on_call_wins_when_present() {
        when(mods.findByOncallSlot(ModProfile.SLOT_PRIMARY))
                .thenReturn(Flux.just(mod("primary@example.com", "primary")));

        StepVerifier.create(ctl.appealContact())
                .expectNext(Map.of("email", "primary@example.com"))
                .verifyComplete();
    }

    @Test
    void falls_back_to_secondary_when_primary_slot_empty() {
        when(mods.findByOncallSlot(ModProfile.SLOT_PRIMARY)).thenReturn(Flux.empty());
        when(mods.findByOncallSlot(ModProfile.SLOT_SECONDARY))
                .thenReturn(Flux.just(mod("secondary@example.com", "secondary")));

        StepVerifier.create(ctl.appealContact())
                .expectNext(Map.of("email", "secondary@example.com"))
                .verifyComplete();
    }

    @Test
    void falls_back_to_configured_address_when_no_on_call_slot_filled() {
        when(mods.findByOncallSlot(ModProfile.SLOT_PRIMARY)).thenReturn(Flux.empty());
        when(mods.findByOncallSlot(ModProfile.SLOT_SECONDARY)).thenReturn(Flux.empty());

        StepVerifier.create(ctl.appealContact())
                .expectNext(Map.of("email", "fallback@example.com"))
                .verifyComplete();
    }
}
