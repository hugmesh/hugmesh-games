# Builder image for the 14 web-native game-core WASM shim crates
# (extensions/hugmesh-<game>-wasm/) targeting wasm32-unknown-unknown.
#
# This is deliberately NOT the same image as tools/build-rust-wasm/ (the
# `hugmesh-rust-wasm` GDExtension builder). That one carries a full emsdk
# because the Godot bridge targets wasm32-unknown-EMSCRIPTEN. The game-core
# shims are freestanding cdylibs with ZERO host imports — no emscripten, no
# wasi, no wasm-bindgen — so they need nothing but rustc + the
# wasm32-unknown-unknown std. Keeping them apart means:
#   • no emsdk clone/install, so this image is a thin layer on the official
#     rust slim base and rebuilds in seconds, and
#   • adding a target here can never invalidate the emsdk image that
#     `make build-rust-wasm` cache-checks with `docker image inspect`.
#
# The build runs in a container at all because the host toolchain is not
# guaranteed to have the wasm32 std-lib: distro-packaged Rust (Gentoo,
# Debian) ships no rustup and no wasm32-unknown-unknown target, so a plain
# host `cargo build --target wasm32-unknown-unknown` fails with
# "error[E0463]: can't find crate for `std`  /  the wasm32-unknown-unknown
# target may not be installed".
#
# Build:    docker build -t hugmesh-games-wasm tools/build-games-wasm/
# Run:      docker run --rm --user "$(id -u):$(id -g)" -v "$PWD:/work" \
#               -w /work/extensions/hugmesh-chess-wasm hugmesh-games-wasm \
#               cargo build --release --target wasm32-unknown-unknown --offline
#
# Drive it as your own uid (--user) rather than as root: cargo writes both
# target/ and a Cargo.lock straight into the bind-mounted checkout, and
# root-owned build output in a developer's working tree is a footgun. The
# official rust image chmods CARGO_HOME/RUSTUP_HOME world-writable so this
# works with no further setup.
#
# Normally you do not run either by hand — use `make build-games-wasm`.

FROM rust:1-slim-bookworm

# The freestanding wasm32-unknown-unknown target links with the bundled
# rust-lld, so no C toolchain / cc / emsdk is required here.
RUN rustup target add wasm32-unknown-unknown

WORKDIR /work
