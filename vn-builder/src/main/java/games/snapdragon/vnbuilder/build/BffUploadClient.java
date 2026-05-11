package games.snapdragon.vnbuilder.build;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class BffUploadClient {
    private static final Logger log = LoggerFactory.getLogger(BffUploadClient.class);
    private final WebClient bff;
    public BffUploadClient(WebClient bffWebClient) { this.bff = bffWebClient; }

    public Mono<String> upload(byte[] zip, String filename, String bearer) {
        MultipartBodyBuilder mpb = new MultipartBodyBuilder();
        mpb.part("bundle", new ByteArrayResource(zip) {
            @Override public String getFilename() { return filename; }
        }).contentType(MediaType.APPLICATION_OCTET_STREAM);

        // Let WebClient set Content-Type with its own boundary when
        // composing the multipart body — see rpg-builder BffUploadClient
        // for the rationale (pre-setting MULTIPART_FORM_DATA without a
        // boundary produces an invalid Content-Type Spring Security
        // rejects with 403).
        return bff.post().uri("/api/admin/games/upload")
                .headers(h -> h.setBearerAuth(bearer))
                .body(BodyInserters.fromMultipartData(mpb.build()))
                .retrieve()
                .bodyToMono(String.class)
                .doOnSuccess(r -> log.info("[upload] BFF accepted {} ({} bytes)", filename, zip.length))
                .doOnError(e -> log.warn("[upload] BFF rejected {}: {}", filename, e.toString()));
    }
}
