package games.snapdragon.rpgbuilder.service;

import games.snapdragon.rpgbuilder.entity.BuildStatus;
import games.snapdragon.rpgbuilder.entity.RpgBuildJob;
import games.snapdragon.rpgbuilder.entity.RpgProject;
import games.snapdragon.rpgbuilder.repository.RpgBuildJobRepository;
import games.snapdragon.rpgbuilder.util.SlugUtil;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class BuildJobService {

    private final RpgBuildJobRepository jobs;
    private final RpgProjectService projects;

    public BuildJobService(RpgBuildJobRepository jobs, RpgProjectService projects) {
        this.jobs = jobs;
        this.projects = projects;
    }

    /**
     * Enqueue a build for the given project. The bearer token forwarded
     * here lets the worker re-authenticate against the BFF as the
     * originating user when it POSTs the produced zip.
     */
    public Mono<RpgBuildJob> enqueue(UUID projectId, String targetSlug, String ownerCasdoorId, String bearerToken) {
        if (!SlugUtil.isValid(targetSlug)) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "targetSlug must match ^[a-z0-9][a-z0-9-]{0,63}$"));
        }
        if (bearerToken == null || bearerToken.isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing bearer token"));
        }
        return projects.get(projectId, ownerCasdoorId).flatMap((RpgProject p) -> {
            RpgBuildJob j = new RpgBuildJob();
            j.setProjectId(projectId);
            j.setStatus(BuildStatus.QUEUED);
            j.setProgressPct(0);
            j.setLogTail("");
            j.setTargetSlug(targetSlug);
            j.setOwnerCasdoorId(ownerCasdoorId);
            j.setBearerToken(bearerToken);
            j.setCreatedAt(OffsetDateTime.now());
            return jobs.save(j);
        });
    }

    public Flux<RpgBuildJob> listForProject(UUID projectId, String ownerCasdoorId) {
        return projects.get(projectId, ownerCasdoorId).flatMapMany(p -> jobs.findByProjectIdOrderByCreatedAtDesc(projectId));
    }

    public Mono<RpgBuildJob> get(UUID buildId, String ownerCasdoorId) {
        return jobs.findById(buildId).flatMap(j ->
                projects.get(j.getProjectId(), ownerCasdoorId).thenReturn(j));
    }
}
