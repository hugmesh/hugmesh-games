// games/_shared/wasm-host.js
//
// WasmHost — the ONE place every web-native Snapdragon game frontend marshals
// JSON across the wasm boundary. It loads a game's freestanding
// `wasm32-unknown-unknown` engine (zero host imports) and wraps the
// alloc/dealloc + packed-(ptr<<32|len) JSON contract into an ergonomic
// `call(fnName, jsonStringOrNull) -> parsedJson`.
//
// THE CONTRACT (must agree byte-for-byte with the JVM driver
// backend/.../realtime/wasm/WasmSimCore.java AND the Rust shim documented in
// extensions/snapdragon-fps-core-wasm/src/lib.rs):
//
//   Passing bytes IN  (host → module):
//     1. ptr = alloc(len)            — module hands back a writable region
//     2. write UTF-8 JSON into       [ptr, ptr+len) of the module's `memory`
//     3. call export(... , ptr, len) — the (ptr,len) pair are the LAST two args
//     4. dealloc(ptr, len)           — release the input buffer after the call
//
//   Receiving bytes OUT (module → host) — the PACKED i64 return:
//        packed: u64 == (ptr as u64) << 32 | (len as u64 & 0xFFFF_FFFF)
//        ptr = (packed >> 32) & 0xFFFF_FFFF
//        len =  packed        & 0xFFFF_FFFF
//     JS sees the i64 as a Number or BigInt; we normalise through
//     BigInt.asUintN(64, …) so a sign-extended return decodes the same way the
//     JVM's `& 0xFFFF_FFFFL` masking does. The pointed-to bytes are UTF-8 JSON
//     the host reads IMMEDIATELY and then hands back via dealloc(ptr, len)
//     (the module leaked a Box<[u8]>; not dealloc-ing leaks for the instance's
//     life). A packed value of 0 means "no data" → null.
//
// Not every export returns packed JSON: some return a plain i32 status
// (chess_new() -> 1, chess_from_fen() -> 0|1, fps_new() -> 0|neg). `call()`
// auto-detects: a packed pointer decodes to a readable region, a small status
// int does not. Callers that KNOW the shape can use the explicit helpers
// (`callPacked`, `callStatus`, `callRaw`).
//
// Vanilla ES module. No build step, no dependencies. Served as a static file.

const PTR_MASK = 0xffffffffn;

export class WasmHost {
  /**
   * @param {string} wasmUrl absolute or relative URL to the `.wasm` engine
   * @param {object} [imports] extra import object; defaults to {} (the
   *        freestanding modules this project ships have ZERO host imports, so
   *        the empty object is the correct value — pass something only if a
   *        future engine declares an import).
   */
  constructor(wasmUrl, imports = {}) {
    this.wasmUrl = wasmUrl;
    this.imports = imports;
    /** @type {WebAssembly.Instance|null} */
    this.instance = null;
    /** @type {WebAssembly.Exports|null} */
    this.exports = null;
    /** @type {WebAssembly.Memory|null} */
    this.memory = null;
    this._enc = new TextEncoder();
    this._dec = new TextDecoder();
  }

  /**
   * Stream-compile + instantiate the module. Prefers
   * `WebAssembly.instantiateStreaming` (the documented Explore path: the HOST
   * browser compiles the engine while it downloads). Falls back to ArrayBuffer
   * instantiation when the server doesn't send `application/wasm` (e.g. a dev
   * static host) or when `fetch`/streaming is unavailable.
   */
  async load() {
    const resp = await fetch(this.wasmUrl);
    if (!resp.ok) {
      throw new Error(`WasmHost: failed to fetch ${this.wasmUrl} (HTTP ${resp.status})`);
    }
    let result;
    if (typeof WebAssembly.instantiateStreaming === 'function') {
      try {
        result = await WebAssembly.instantiateStreaming(resp.clone(), this.imports);
      } catch (_streamErr) {
        // Common when the response lacks the application/wasm MIME type;
        // fall back to the buffer path rather than failing the whole boot.
        const bytes = await resp.arrayBuffer();
        result = await WebAssembly.instantiate(bytes, this.imports);
      }
    } else {
      const bytes = await resp.arrayBuffer();
      result = await WebAssembly.instantiate(bytes, this.imports);
    }
    return this._bind(result.instance);
  }

  /**
   * Instantiate from raw bytes already in hand (ArrayBuffer / TypedArray) — the
   * path a Node test or a server-side driver uses where `fetch` can't read the
   * file. The browser uses {@link load} (streaming); this is the byte-source
   * twin, marshalling identical to it once instantiated.
   * @param {ArrayBuffer|Uint8Array} bytes
   */
  async loadBytes(bytes) {
    const { instance } = await WebAssembly.instantiate(bytes, this.imports);
    return this._bind(instance);
  }

  /** Wire up exports/memory + validate the contract. Shared by load/loadBytes. */
  _bind(instance) {
    this.instance = instance;
    this.exports = instance.exports;
    this.memory = this.exports.memory;
    if (!this.memory) {
      throw new Error('WasmHost: module exports no `memory` — not a freestanding Snapdragon engine');
    }
    if (typeof this.exports.alloc !== 'function' || typeof this.exports.dealloc !== 'function') {
      throw new Error('WasmHost: module is missing the alloc/dealloc contract exports');
    }
    return this;
  }

  /** A fresh Uint8Array view over current linear memory (memory may grow). */
  _bytes() {
    return new Uint8Array(this.memory.buffer);
  }

  /**
   * alloc a buffer in linear memory and write `str` (UTF-8) into it.
   * @returns {[number, number]} [ptr, byteLength]
   */
  _writeUtf8(str) {
    const buf = this._enc.encode(str);
    const ptr = Number(this.exports.alloc(buf.length));
    if (buf.length > 0) {
      this._bytes().set(buf, ptr);
    }
    return [ptr, buf.length];
  }

  /** Decode a packed (ptr<<32|len) return into its UTF-8 string, then dealloc it. */
  _readPacked(packed) {
    const big = BigInt.asUintN(64, BigInt(packed));
    const ptr = Number((big >> 32n) & PTR_MASK);
    const len = Number(big & PTR_MASK);
    if (ptr === 0 || len === 0) {
      return null;
    }
    // .slice() copies out before dealloc reclaims the region.
    const out = this._bytes().slice(ptr, ptr + len);
    this.exports.dealloc(ptr, len);
    return this._dec.decode(out);
  }

  /**
   * Call an export with an optional string argument and return the raw numeric
   * result the export produced — NO packed-pointer decoding, NO input
   * dealloc-loss: the input buffer (if any) is released, but the i64/i32 return
   * is handed back verbatim (as the export's own type — Number or BigInt).
   *
   * The string argument is written VERBATIM as UTF-8 (NOT JSON-encoded here):
   * pass exactly the bytes the export parses — a raw token (`'e2e4'`) or a full
   * JSON document (an FPS config) — the export's contract decides which.
   *
   * @param {string} fnName
   * @param {string|null} [jsonStringOrNull] the UTF-8 bytes to pass, or null for
   *        a no-argument export.
   * @param {Array<number|bigint>} [leadingArgs] args BEFORE the (ptr,len) pair —
   *        e.g. fps_new(seed, cfgPtr, cfgLen) passes [seed].
   * @param {Array<number|bigint>} [trailingArgs] args AFTER the (ptr,len) pair —
   *        e.g. go_from_board(boardPtr, boardLen, size, side) passes [] leading
   *        and [size, side] trailing. Ignored for a no-argument (null) call.
   */
  callRaw(fnName, jsonStringOrNull = null, leadingArgs = [], trailingArgs = []) {
    const fn = this.exports[fnName];
    if (typeof fn !== 'function') {
      throw new Error(`WasmHost: export '${fnName}' is not a function`);
    }
    if (jsonStringOrNull === null || jsonStringOrNull === undefined) {
      return fn(...leadingArgs, ...trailingArgs);
    }
    const [ptr, len] = this._writeUtf8(jsonStringOrNull);
    try {
      return fn(...leadingArgs, ptr, len, ...trailingArgs);
    } finally {
      this.exports.dealloc(ptr, len);
    }
  }

  /**
   * Call an export whose return is a PACKED (ptr<<32|len) pointer to a UTF-8
   * JSON string; decode + dealloc the output buffer and return the parsed JSON
   * (or null when the export returned 0 / empty).
   */
  callPacked(fnName, jsonStringOrNull = null, leadingArgs = [], trailingArgs = []) {
    const packed = this.callRaw(fnName, jsonStringOrNull, leadingArgs, trailingArgs);
    const json = this._readPacked(packed);
    return json === null ? null : JSON.parse(json);
  }

  /**
   * Call an export whose return is a plain status int (e.g. 0/1 or 0/negative).
   * Returned as a Number, masked to the low 32 bits so a sign-extended i32
   * decodes identically to the JVM driver's `(int)(ret & 0xFFFF_FFFFL)`.
   */
  callStatus(fnName, jsonStringOrNull = null, leadingArgs = [], trailingArgs = []) {
    const raw = this.callRaw(fnName, jsonStringOrNull, leadingArgs, trailingArgs);
    return Number(BigInt.asUintN(64, BigInt(raw)) & PTR_MASK) | 0;
  }

  /**
   * Ergonomic front door: call an export, pass an optional JSON string, get
   * parsed JSON back. Auto-detects the return shape:
   *   - a value that decodes to a readable (ptr,len) region → parsed JSON
   *   - anything else (a small status int, or 0) → the raw Number
   *
   * Use the explicit `callPacked` / `callStatus` when you know the export's
   * contract (clearer, and avoids the rare ambiguity where a tiny status int
   * happens to look like a valid pointer).
   *
   * @returns {*} parsed JSON object/array/primitive, or a Number for status
   *              returns, or null when the export returned 0/empty packed.
   */
  call(fnName, jsonStringOrNull = null, leadingArgs = [], trailingArgs = []) {
    const raw = this.callRaw(fnName, jsonStringOrNull, leadingArgs, trailingArgs);
    const big = BigInt.asUintN(64, BigInt(raw));
    const ptr = Number((big >> 32n) & PTR_MASK);
    const len = Number(big & PTR_MASK);
    // A genuine packed pointer has a non-zero high word (ptr) AND a length
    // that fits inside current linear memory starting at ptr. A bare status
    // int (1, 0, -1, …) has a zero high word, so it never looks packed.
    if (ptr !== 0 && len !== 0 && ptr + len <= this.memory.buffer.byteLength) {
      const json = this._readPacked(raw);
      return json === null ? null : JSON.parse(json);
    }
    // Not a pointer — a status/scalar. Hand back the masked low word.
    return Number(big & PTR_MASK) | 0;
  }
}

export default WasmHost;
