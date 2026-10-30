FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw dependency:go-offline -q
COPY src src
RUN ./mvnw -q package -DskipTests

# ── Godot toolchain stage ────────────────────────────────────────────────────
# Fetch Godot 4 headless + the Web export templates so the DEPLOYED maker can run a
# REAL `godot --headless --export-release "Web"` (GodotExporter.exportWeb) instead of
# falling back to writeStubBundle(). Mirrors the proven `make godot-export-tools` recipe.
#
# AIR-GAP: the appliance is Tailscale-only, so at build time on the appliance pass
# --build-arg GODOT_RELEASE_BASE_URL=<internal-mirror> to pull the binary + templates
# from the in-cluster artifact mirror instead of github.com. The default below keeps
# dev/CI builds working off the public GitHub releases.
FROM eclipse-temurin:25-jdk AS godot
ARG GODOT_VERSION=4.6-stable
ARG GODOT_RELEASE_BASE_URL=https://github.com/godotengine/godot/releases/download
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends curl unzip ca-certificates; \
    rm -rf /var/lib/apt/lists/*; \
    base="${GODOT_RELEASE_BASE_URL}/${GODOT_VERSION}"; \
    bin="Godot_v${GODOT_VERSION}_linux.x86_64"; \
    tpz="Godot_v${GODOT_VERSION}_export_templates.tpz"; \
    tdir="$(echo "${GODOT_VERSION}" | tr '-' '.')"; \
    mkdir -p /opt/godot "/root/.local/share/godot/export_templates/${tdir}" /tmp/godot-tpl; \
    curl -fsSL "${base}/${bin}.zip" -o /tmp/godot.zip; \
    unzip -oq /tmp/godot.zip -d /opt/godot; \
    mv "/opt/godot/${bin}" /opt/godot/godot; \
    chmod +x /opt/godot/godot; \
    curl -fsSL "${base}/${tpz}" -o /tmp/templates.tpz; \
    unzip -oq /tmp/templates.tpz -d /tmp/godot-tpl; \
    cp -r /tmp/godot-tpl/templates/* "/root/.local/share/godot/export_templates/${tdir}/"; \
    rm -rf /tmp/godot.zip /tmp/templates.tpz /tmp/godot-tpl

FROM eclipse-temurin:25-jre
WORKDIR /app
# Shared libs the Godot binary dynamically links even for a --headless Web export
# (libGL + X11 client libs + fontconfig). Without them `godot --version` fails to start,
# which would turn every real export into a fail-closed FAILED build.
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        libgl1 libxi6 libxrandr2 libxcursor1 libxinerama1 libfontconfig1; \
    rm -rf /var/lib/apt/lists/*
# The baked Godot binary + Web export templates (templates land under the JRE image's
# /root/.local/share/godot/export_templates/<version> — Godot's default lookup path).
COPY --from=godot /opt/godot /opt/godot
COPY --from=godot /root/.local/share/godot /root/.local/share/godot
# Point the maker at the baked binary. app.build.godot-enabled stays at its
# application.yml default (false) — the toolchain is PRESENT but real exports are not
# turned on until the deployment manifest flips APP_BUILD_GODOT_ENABLED=true (paired
# with the multi-GB ephemeral-storage the export workdir needs), so that is a
# config-only flip later, no image rebuild. APP_BUILD_BRIDGE_DIR (the compiled
# hugmesh_bridge binaries) is likewise supplied by the deployment; it defaults unset.
ENV APP_BUILD_GODOT_BIN=/opt/godot/godot
COPY --from=build /app/target/hugmesh-rpg-builder-0.2.0-beta.2.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
