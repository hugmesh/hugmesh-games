package com.threewizards.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/assets")
public class AssetController {

    private static final Map<String, MediaType> MEDIA_TYPES = Map.ofEntries(
            Map.entry("png",  MediaType.IMAGE_PNG),
            Map.entry("jpg",  MediaType.IMAGE_JPEG),
            Map.entry("jpeg", MediaType.IMAGE_JPEG),
            Map.entry("svg",  MediaType.valueOf("image/svg+xml")),
            Map.entry("json", MediaType.APPLICATION_JSON),
            Map.entry("bin",  MediaType.APPLICATION_OCTET_STREAM),
            Map.entry("wasm", MediaType.valueOf("application/wasm")),
            Map.entry("pck",  MediaType.APPLICATION_OCTET_STREAM),
            Map.entry("glb",  MediaType.valueOf("model/gltf-binary")),
            Map.entry("gltf", MediaType.valueOf("model/gltf+json"))
    );

    @GetMapping("/{filename:.+}")
    public Mono<ResponseEntity<Resource>> getAsset(@PathVariable String filename) {
        return Mono.fromCallable(() -> {
            ClassPathResource resource = new ClassPathResource("assets/" + filename);
            if (!resource.exists()) {
                return ResponseEntity.<Resource>notFound().build();
            }
            String ext = filename.contains(".")
                    ? filename.substring(filename.lastIndexOf('.') + 1).toLowerCase()
                    : "";
            MediaType mediaType = MEDIA_TYPES.getOrDefault(ext, MediaType.APPLICATION_OCTET_STREAM);
            return ResponseEntity.ok().contentType(mediaType).<Resource>body(resource);
        });
    }
}
