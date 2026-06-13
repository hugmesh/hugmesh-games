package games.snapdragon.bff.service.realtime;

import org.springframework.stereotype.Component;

/** Registers the P2 reference sim in the tick service's factory registry. */
@Component
public class ReferenceArenaSimFactory implements SimCoreFactory {

    public static final String KIND = "reference-arena";

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public SimCore create(long seed, String configJson) {
        return ReferenceArenaSim.create(seed, configJson);
    }

    @Override
    public SimCore restore(String snapshotJson) {
        return ReferenceArenaSim.restore(snapshotJson);
    }
}
