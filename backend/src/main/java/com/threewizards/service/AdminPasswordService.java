package com.threewizards.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class AdminPasswordService {

    private static final Logger log = LoggerFactory.getLogger(AdminPasswordService.class);

    @Value("${casdoor.endpoint:http://localhost:8000}")
    private String casdoorEndpoint;

    @Value("${casdoor.client-id:twclient001}")
    private String clientId;

    @EventListener(ApplicationReadyEvent.class)
    @Profile("dev")
    void onReadyDev() {
        String sep = "=".repeat(64);
        log.info("\n{}\n"
                + "  DEV MODE — Casdoor admin UI : {}\n"
                + "  OAuth2 client ID            : {}\n"
                + "  Initial passwords are printed in the Casdoor container log:\n"
                + "    docker logs <casdoor-container>\n"
                + "  Or set CASDOOR_ADMIN_PASS / CASDOOR_PLAYER_PASS env vars\n"
                + "  before starting Docker Compose to use fixed values.\n"
                + "{}", sep, casdoorEndpoint, clientId, sep);
    }

    @EventListener(ApplicationReadyEvent.class)
    @Profile("!dev")
    void onReadyProd() {
        log.info("Casdoor endpoint: {} | client-id: {}", casdoorEndpoint, clientId);
    }
}
