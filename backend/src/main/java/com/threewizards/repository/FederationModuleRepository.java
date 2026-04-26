package com.threewizards.repository;

import com.threewizards.model.FederationModule;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface FederationModuleRepository extends ReactiveCrudRepository<FederationModule, Long> {

    Flux<FederationModule> findAllByEnabledTrueOrderByKindAscSlugAsc();

    Flux<FederationModule> findByKindAndEnabledTrue(String kind);

    Mono<FederationModule> findBySlug(String slug);
}
