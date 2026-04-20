package com.threewizards.controller;

import com.threewizards.model.GameState;
import com.threewizards.service.GameStateService;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;


@RestController
@RequestMapping("/api/game")
public class GameStreamController {

    private final GameStateService gameStateService;

    public GameStreamController(GameStateService gameStateService) {
        this.gameStateService = gameStateService;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<GameState>> streamGameState() {
        return gameStateService.stateStream
                .map(state -> ServerSentEvent.<GameState>builder()
                        .id(String.valueOf(state.tick()))
                        .event("game-state")
                        .data(state)
                        .build());
    }

    @GetMapping("/state")
    public GameState currentState() {
        return gameStateService.currentState();
    }
}
