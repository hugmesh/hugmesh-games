package com.threewizards.controller;

import com.threewizards.model.ControlEvent;
import com.threewizards.service.GameStateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/controls")
public class ControlController {

    private final GameStateService gameStateService;

    public ControlController(GameStateService gameStateService) {
        this.gameStateService = gameStateService;
    }

    @PostMapping
    public Mono<ResponseEntity<Void>> receiveControl(@RequestBody ControlEvent event) {
        return Mono.fromCallable(() -> {
            gameStateService.applyControl(event);
            return ResponseEntity.<Void>noContent().build();
        });
    }
}
