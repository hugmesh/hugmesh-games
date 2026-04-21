package com.threewizards.controller;

import com.threewizards.model.*;
import com.threewizards.repository.GameRepository;
import com.threewizards.repository.SceneElementRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

@RestController
@RequestMapping("/api/games")
public class GameController {

    private final GameRepository games;
    private final SceneElementRepository sceneElements;

    public GameController(GameRepository games, SceneElementRepository sceneElements) {
        this.games = games;
        this.sceneElements = sceneElements;
    }

    @GetMapping
    public Flux<Game> list() {
        return Mono.<List<Game>>fromCallable(games::findAll)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapIterable(l -> l);
    }

    @GetMapping("/{id}")
    public Mono<Game> get(@PathVariable Long id) {
        return Mono.fromCallable(() -> games.findById(id)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}/scene")
    public Mono<SceneDto> getScene(@PathVariable Long id) {
        return Mono.fromCallable(() -> {
            Game game = games.findById(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            List<SceneElementDto> elements = sceneElements
                    .findByGameIdOrderBySortOrder(game.getId())
                    .stream()
                    .map(e -> new SceneElementDto(
                            e.getId(), e.getElementType(), e.getName(),
                            e.getPositionX(), e.getPositionY(), e.getPositionZ(),
                            e.getRotationX(), e.getRotationY(), e.getRotationZ(),
                            e.getProperties()))
                    .toList();
            return new SceneDto(game.getId(), game.getName(), game.getSlug(), game.getSlug(), elements);
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
