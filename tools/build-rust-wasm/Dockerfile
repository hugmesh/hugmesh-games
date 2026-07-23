# Builder image for the HugMesh Bridge Rust GDExtension targeting
# wasm32-unknown-emscripten. Combines the official rust toolchain with
# emscripten so the build is reproducible and doesn't depend on
# host-installed emscripten / emsdk.
#
# Build:    docker build -t hugmesh-rust-wasm tools/build-rust-wasm/
# Run:      docker run --rm -v "$PWD:/work" hugmesh-rust-wasm \
#               cargo build --release --target wasm32-unknown-emscripten \
#               --manifest-path /work/extensions/hugmesh-bridge/Cargo.toml
FROM rust:1-bookworm

# Emscripten via the official emsdk script. Pinned at a known-good
# version that godot-rust currently supports for wasm32-unknown-emscripten.
ARG EMSDK_VERSION=3.1.74

RUN apt-get update && apt-get install -y --no-install-recommends \
        git ca-certificates python3 \
    && rm -rf /var/lib/apt/lists/*

RUN git clone --depth 1 https://github.com/emscripten-core/emsdk.git /opt/emsdk \
 && cd /opt/emsdk \
 && ./emsdk install ${EMSDK_VERSION} \
 && ./emsdk activate ${EMSDK_VERSION}

ENV PATH="/opt/emsdk:/opt/emsdk/upstream/emscripten:${PATH}"
ENV EMSDK="/opt/emsdk"
ENV EMSCRIPTEN="/opt/emsdk/upstream/emscripten"

# wasm32-unknown-emscripten std for the Rust toolchain installed above.
RUN rustup target add wasm32-unknown-emscripten

WORKDIR /work
