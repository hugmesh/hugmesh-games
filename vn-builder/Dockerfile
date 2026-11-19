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

# ---- Java4Shell (CVE-2026-114938): patched Java 25 runtime ------------------------------
# 25.0.5.1 fixes the heap overflow in the Base64 AVX-512 intrinsic, but eclipse-temurin:25-jre
# on Docker Hub still ships the vulnerable 25.0.5+8. Adoptium built Temurin 25.0.5.1+2 for
# linux x64 only, so arm64 gets a JRE linked (jlink, same module set as the base JRE) from
# Amazon Corretto 25.0.5.1.2, the same 25.0.5.1 security release. Both downloads are pinned to
# the vendors' published sha256. The runtime stage swaps this JRE in only while its base JRE
# is older. Remove this stage and the swap once eclipse-temurin:25-jre itself is >= 25.0.5.1.
FROM eclipse-temurin:25-jre AS jre-cve-2026-114938
ARG JRE_X64_URL=https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5.1%2B2/OpenJDK25U-jre_x64_linux_hotspot_25.0.5.1_2.tar.gz
ARG JRE_X64_SHA256=2edef9e4f81ec5ee426d023336d4cfbd7b4644e99884e7a73a6a867b70b17acf
ARG JDK_AARCH64_URL=https://corretto.aws/downloads/resources/25.0.5.1.2/amazon-corretto-25.0.5.1.2-linux-aarch64.tar.gz
ARG JDK_AARCH64_SHA256=9370d3e5bcf0d165e6be4a78c3f6b57160af16d3033319af583800251eb91df2
RUN set -eu; \
    mkdir -p /opt/jre; \
    arch="$(dpkg --print-architecture)"; \
    case "${arch}" in \
      amd64) url="${JRE_X64_URL}"; sum="${JRE_X64_SHA256}" ;; \
      arm64) url="${JDK_AARCH64_URL}"; sum="${JDK_AARCH64_SHA256}" ;; \
      *) echo "jre-cve-2026-114938: no patched build for ${arch}; keeping the base JRE"; exit 0 ;; \
    esac; \
    apt-get update; \
    apt-get install -y --no-install-recommends curl ca-certificates; \
    rm -rf /var/lib/apt/lists/*; \
    curl -fsSL --retry 3 -o /tmp/java.tar.gz "${url}"; \
    printf '%s  /tmp/java.tar.gz\n' "${sum}" > /tmp/java.sha256; \
    sha256sum -c /tmp/java.sha256; \
    mkdir /tmp/java; \
    tar -xzf /tmp/java.tar.gz -C /tmp/java --strip-components=1; \
    rmdir /opt/jre; \
    if [ "${arch}" = amd64 ]; then \
      mv /tmp/java /opt/jre; \
    else \
      java --list-modules > /tmp/modules; \
      modules="$(sed 's/@.*//' /tmp/modules | paste -sd, -)"; \
      [ -n "${modules}" ]; \
      /tmp/java/bin/jlink --add-modules "${modules}" --no-man-pages --no-header-files --output /opt/jre; \
      rm -rf /tmp/java /tmp/modules; \
    fi; \
    rm -f /tmp/java.tar.gz /tmp/java.sha256; \
    /opt/jre/bin/java -version

FROM eclipse-temurin:25-jre
# Java4Shell (CVE-2026-114938): use the patched JRE from the stage above while the base JRE
# is older than 25.0.5.1 (a symlink, so the JRE is not copied a second time).
COPY --from=jre-cve-2026-114938 /opt/jre/ /opt/java/jre-25.0.5.1/
RUN base="$(sed -n 's/^JAVA_VERSION="\(.*\)"$/\1/p' /opt/java/openjdk/release)" \
    && printf '%s\n25.0.5.1\n' "${base}" > /tmp/jre-versions \
    && if [ -x /opt/java/jre-25.0.5.1/bin/java ] && [ "${base}" != 25.0.5.1 ] \
          && sort -V -C /tmp/jre-versions; then \
         rm -rf /opt/java/openjdk && ln -s jre-25.0.5.1 /opt/java/openjdk && ldconfig; \
       else \
         rm -rf /opt/java/jre-25.0.5.1; \
       fi \
    && rm -f /tmp/jre-versions \
    && java -version
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
COPY --from=build /app/target/hugmesh-vn-builder.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
