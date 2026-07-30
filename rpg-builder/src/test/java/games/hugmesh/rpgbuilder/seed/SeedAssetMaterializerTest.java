package games.hugmesh.rpgbuilder.seed;

import games.hugmesh.rpgbuilder.entity.RpgAsset;
import games.hugmesh.rpgbuilder.repository.RpgAssetRepository;
import games.hugmesh.rpgbuilder.service.ProceduralAssetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Boot-time seed materialization: a Liquibase-seeded asset row with a
 * {@code seed:*} sentinel storage_path gets real deterministic bytes on
 * disk through the exact {@code <assetsDir>/<projectId>/<assetId>}
 * layout an upload uses, and the row is UPDATEd to point at them. Reruns
 * are byte-identical (procedural generation is a pure function); rows
 * that are not sentinels or cannot generate are skipped, never fatal.
 */
class SeedAssetMaterializerTest {

    private RpgAssetRepository assets;
    private SeedAssetMaterializer materializer;
    @TempDir Path assetsDir;

    @BeforeEach
    void setUp() {
        assets = mock(RpgAssetRepository.class);
        materializer = new SeedAssetMaterializer(assets, new ProceduralAssetService(), assetsDir.toString());
        when(assets.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    }

    private static RpgAsset seedRow(UUID pid, String slug, String kind) {
        RpgAsset a = new RpgAsset();
        a.setId(UUID.randomUUID());
        a.setProjectId(pid);
        a.setSlug(slug);
        a.setKind(kind);
        a.setMimeType("image/svg+xml");
        a.setByteSize(0);
        a.setStoragePath("seed:six-realms-" + slug);
        a.setUploadedBy("publisher-flatland");
        return a;
    }

    @Test void materialize_writesBytesAndUpdatesRow() throws Exception {
        UUID pid = UUID.randomUUID();
        RpgAsset seed = seedRow(pid, "greeter-portrait", "portrait");
        when(assets.findByStoragePathStartingWith("seed:")).thenReturn(Flux.just(seed));

        StepVerifier.create(materializer.materialize()).expectNext(1L).verifyComplete();

        Path expected = assetsDir.resolve(pid.toString()).resolve(seed.getId().toString());
        assertThat(Files.exists(expected)).isTrue();
        byte[] onDisk = Files.readAllBytes(expected);
        assertThat(onDisk).isEqualTo(new ProceduralAssetService().generate("portrait", "greeter-portrait", null));
        assertThat(new String(onDisk)).startsWith("<svg").contains("</svg>");

        ArgumentCaptor<RpgAsset> saved = ArgumentCaptor.forClass(RpgAsset.class);
        verify(assets).save(saved.capture());
        assertThat(saved.getValue().getStoragePath()).isEqualTo(expected.toString());
        assertThat(saved.getValue().getByteSize()).isEqualTo(onDisk.length);
    }

    @Test void materialize_isDeterministicAcrossReruns() throws Exception {
        UUID pid = UUID.randomUUID();
        RpgAsset run1 = seedRow(pid, "greeter-portrait", "portrait");
        UUID fixedId = run1.getId();
        when(assets.findByStoragePathStartingWith("seed:")).thenAnswer(inv -> {
            // A fresh sentinel row each subscription (same identity), as a
            // re-deployed changelog + wiped disk would present it.
            RpgAsset again = seedRow(pid, "greeter-portrait", "portrait");
            again.setId(fixedId);
            return Flux.just(again);
        });

        materializer.materialize().block();
        Path target = assetsDir.resolve(pid.toString()).resolve(fixedId.toString());
        byte[] first = Files.readAllBytes(target);
        materializer.materialize().block();
        byte[] second = Files.readAllBytes(target);

        assertThat(second).isEqualTo(first);   // byte-identical rerun
    }

    /** Defence-in-depth: even if the query over-returns, a row whose
     *  storage_path is already a real path is never rewritten. */
    @Test void materialize_skipsNonSeedRow() {
        RpgAsset real = seedRow(UUID.randomUUID(), "uploaded-portrait", "portrait");
        real.setStoragePath("/var/hugmesh/rpg-assets/somewhere/real");
        when(assets.findByStoragePathStartingWith("seed:")).thenReturn(Flux.just(real));

        StepVerifier.create(materializer.materialize()).expectNext(0L).verifyComplete();
        verify(assets, never()).save(any());
    }

    /** A row that cannot generate (unsupported kind) is logged+skipped —
     *  the boot never fails, and other rows still materialize. */
    @Test void materialize_toleratesUngenerableRowAndContinues() {
        UUID pid = UUID.randomUUID();
        RpgAsset bad = seedRow(pid, "mystery", "audio");   // unsupported procedural kind
        RpgAsset good = seedRow(pid, "greeter-portrait", "portrait");
        when(assets.findByStoragePathStartingWith("seed:")).thenReturn(Flux.just(bad, good));

        StepVerifier.create(materializer.materialize()).expectNext(1L).verifyComplete();

        assertThat(Files.exists(assetsDir.resolve(pid.toString()).resolve(good.getId().toString()))).isTrue();
        assertThat(Files.exists(assetsDir.resolve(pid.toString()).resolve(bad.getId().toString()))).isFalse();
    }

    @Test void materialize_noSeedRowsIsANoOp() {
        when(assets.findByStoragePathStartingWith("seed:")).thenReturn(Flux.empty());
        StepVerifier.create(materializer.materialize()).expectNext(0L).verifyComplete();
        verify(assets, never()).save(any());
    }
}
