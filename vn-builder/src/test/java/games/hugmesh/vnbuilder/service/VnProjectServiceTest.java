package games.hugmesh.vnbuilder.service;

import games.hugmesh.vnbuilder.entity.*;
import games.hugmesh.vnbuilder.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VnProjectServiceTest {

    private VnProjectRepository projects;
    private VnActRepository acts;
    private VnCharacterRepository characters;
    private VnBackgroundRepository backgrounds;
    private VnSceneRepository scenes;
    private VnLineRepository lines;
    private VnChoiceRepository choices;
    private VnProjectService svc;

    @BeforeEach
    void setUp() {
        projects = mock(VnProjectRepository.class);
        acts = mock(VnActRepository.class);
        characters = mock(VnCharacterRepository.class);
        backgrounds = mock(VnBackgroundRepository.class);
        scenes = mock(VnSceneRepository.class);
        lines = mock(VnLineRepository.class);
        choices = mock(VnChoiceRepository.class);
        svc = new VnProjectService(projects, acts, characters, backgrounds, scenes, lines, choices);
    }

    @Test void create_rejectsInvalidSlug() {
        VnProject p = new VnProject();
        p.setSlug("BadSlug");
        p.setDisplayName("Game");
        StepVerifier.create(svc.create(p, "alice"))
                .expectErrorMatches(err -> err instanceof ResponseStatusException rse
                        && rse.getStatusCode().value() == 400
                        && rse.getReason().contains("slug"))
                .verify();
    }

    @Test void create_rejectsBlankDisplayName() {
        VnProject p = new VnProject();
        p.setSlug("ok-slug");
        p.setDisplayName("");
        StepVerifier.create(svc.create(p, "alice"))
                .expectErrorMatches(err -> err instanceof ResponseStatusException rse
                        && rse.getStatusCode().value() == 400)
                .verify();
    }

    @Test void create_persistsAndStampsOwner() {
        when(projects.save(any())).thenAnswer(inv -> {
            VnProject saved = inv.getArgument(0);
            saved.setId(UUID.randomUUID());
            return Mono.just(saved);
        });
        VnProject p = new VnProject();
        p.setSlug("ok-slug"); p.setDisplayName("OK");
        StepVerifier.create(svc.create(p, "alice"))
                .assertNext(saved -> {
                    org.junit.jupiter.api.Assertions.assertEquals("alice", saved.getOwnerCasdoorId());
                })
                .verifyComplete();
    }

    @Test void createScene_rejectsMissingActId() {
        UUID pid = UUID.randomUUID();
        VnScene s = new VnScene();
        s.setSlug("opening"); s.setName("Opening");
        StepVerifier.create(svc.createScene(pid, s, "alice"))
                .expectErrorMatches(err -> err instanceof ResponseStatusException rse
                        && rse.getStatusCode().value() == 400
                        && rse.getReason().contains("act"))
                .verify();
    }

    @Test void createChoice_rejectsMissingTarget() {
        UUID pid = UUID.randomUUID();
        UUID lid = UUID.randomUUID();
        VnChoice c = new VnChoice();
        c.setLabel("Yes");
        StepVerifier.create(svc.createChoice(pid, lid, c, "alice"))
                .expectErrorMatches(err -> err instanceof ResponseStatusException rse
                        && rse.getStatusCode().value() == 400
                        && rse.getReason().contains("target"))
                .verify();
    }
}
