package com.threewizards.controller;

import com.threewizards.model.Game;
import com.threewizards.repository.GameRepository;
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

    public GameController(GameRepository games) {
        this.games = games;
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
}
