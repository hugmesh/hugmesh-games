package games.hugmesh.vnbuilder.service;

import games.hugmesh.vnbuilder.entity.BuildStatus;
import games.hugmesh.vnbuilder.entity.VnBuildJob;
import games.hugmesh.vnbuilder.entity.VnProject;
import games.hugmesh.vnbuilder.repository.VnBuildJobRepository;
import games.hugmesh.vnbuilder.util.SlugUtil;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class BuildJobService {
    private final VnBuildJobRepository jobs;
    private final VnProjectService projects;

    public BuildJobService(VnBuildJobRepository jobs, VnProjectService projects) {
        this.jobs = jobs;
        this.projects = projects;
    }

    public Mono<VnBuildJob> enqueue(UUID pid, String targetSlug, String owner, String bearer) {
        if (!SlugUtil.isValid(targetSlug)) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "targetSlug must match ^[a-z0-9][a-z0-9-]{0,63}$"));
        }
        if (bearer == null || bearer.isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing bearer token"));
        }
        return projects.get(pid, owner).flatMap((VnProject p) -> {
            VnBuildJob j = new VnBuildJob();
            j.setProjectId(pid);
            j.setStatus(BuildStatus.QUEUED);
            j.setProgressPct(0);
            j.setLogTail("");
            j.setTargetSlug(targetSlug);
            j.setOwnerCasdoorId(owner);
            j.setBearerToken(bearer);
            j.setCreatedAt(OffsetDateTime.now());
            return jobs.save(j);
        });
    }

    public Flux<VnBuildJob> listForProject(UUID pid, String owner) {
        return projects.get(pid, owner).flatMapMany(p -> jobs.findByProjectIdOrderByCreatedAtDesc(pid));
    }

    public Mono<VnBuildJob> get(UUID buildId, String owner) {
        return jobs.findById(buildId).flatMap(j ->
                projects.get(j.getProjectId(), owner).thenReturn(j));
    }
}
