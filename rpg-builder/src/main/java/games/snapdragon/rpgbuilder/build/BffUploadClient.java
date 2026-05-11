package games.snapdragon.rpgbuilder.build;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Posts a built bundle zip to the BFF's existing upload endpoint as
 * the originating user (the build job carries that user's bearer
 * token). The BFF runs its own publisher-scope check at upload time
 * via {@code PublisherService.requireOwns}, so the side service never
 * has to know which slugs the user is allowed to ship.
 */
@Component
public class BffUploadClient {

    private static final Logger log = LoggerFactory.getLogger(BffUploadClient.class);

    private final WebClient bff;

    public BffUploadClient(WebClient bffWebClient) { this.bff = bffWebClient; }

    public Mono<String> upload(byte[] zipBytes, String filename, String bearerToken) {
        MultipartBodyBuilder mpb = new MultipartBodyBuilder();
        mpb.part("bundle", new ByteArrayResource(zipBytes) {
            @Override public String getFilename() { return filename; }
        }).contentType(MediaType.APPLICATION_OCTET_STREAM);

        return bff.post().uri("/api/admin/games/upload")
                .headers(h -> h.setBearerAuth(bearerToken))
                .body(BodyInserters.fromMultipartData(mpb.build()))
                .exchangeToMono(resp -> {
                    if (resp.statusCode().is2xxSuccessful()) {
                        return resp.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .doOnNext(b -> log.info("[upload] BFF accepted bundle {} ({} bytes)", filename, zipBytes.length));
                    }
                    return resp.bodyToMono(String.class).defaultIfEmpty("(empty)")
                            .flatMap(body -> {
                                log.warn("[upload] BFF rejected bundle {} → {} {} | body: {}",
                                        filename, resp.statusCode().value(), resp.statusCode(),
                                        body.length() > 500 ? body.substring(0, 500) + "..." : body);
                                return Mono.error(new RuntimeException("BFF rejected: HTTP "
                                        + resp.statusCode().value() + " — " + body));
                            });
                });
    }
}
