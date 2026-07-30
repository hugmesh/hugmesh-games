// Pure pixel-analysis helpers for the biome-worlds render assertions.
//
// Why screenshot + pngjs instead of canvas.getImageData? Godot 4.6 web
// exports create their WebGL2 context with preserveDrawingBuffer=false,
// so sampling the canvas between frames returns blank/black pixels even
// when the world renders fine. A Playwright ELEMENT screenshot is a
// compositor capture — it includes WebGL content regardless — so the
// steps screenshot `iframe.game-frame` and analyse the PNG bytes here.
//
// Every function below is pure (Buffer/typed-array in, numbers out) so
// they stay unit-testable without a browser.

import { PNG } from 'pngjs';

export interface Rgb {
  r: number;
  g: number;
  b: number;
}

export interface CropStats {
  /** Mean RGB over the crop — the per-biome palette fingerprint. */
  mean: Rgb;
  /** Mean rec.709 luma (0-255). >10 rejects a black screen. */
  meanLuma: number;
  /** Luma standard deviation. >8 rejects a solid splash colour. */
  lumaStdDev: number;
  width: number;
  height: number;
}

/** rec.709 luma for one pixel. */
export function luma(r: number, g: number, b: number): number {
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

/** Euclidean distance between two mean-RGB fingerprints. */
export function rgbDistance(a: Rgb, b: Rgb): number {
  const dr = a.r - b.r;
  const dg = a.g - b.g;
  const db = a.b - b.b;
  return Math.sqrt(dr * dr + dg * dg + db * db);
}

/**
 * Extract the RGBA bytes of a centred crop covering `fraction` of each
 * axis. Cropping to the centre keeps the stats clean of letterboxing,
 * loader chrome, or HUD strips hugging the frame edges.
 */
export function centerCrop(
  data: Uint8Array | Buffer,
  width: number,
  height: number,
  fraction = 0.5,
): { data: Uint8Array; width: number; height: number } {
  const cw = Math.max(1, Math.floor(width * fraction));
  const ch = Math.max(1, Math.floor(height * fraction));
  const x0 = Math.floor((width - cw) / 2);
  const y0 = Math.floor((height - ch) / 2);
  const out = new Uint8Array(cw * ch * 4);
  for (let y = 0; y < ch; y++) {
    const srcStart = ((y0 + y) * width + x0) * 4;
    out.set(data.subarray(srcStart, srcStart + cw * 4), y * cw * 4);
  }
  return { data: out, width: cw, height: ch };
}

/** Mean RGB over RGBA bytes. */
export function meanRgb(data: Uint8Array): Rgb {
  let r = 0, g = 0, b = 0;
  const n = data.length / 4;
  for (let i = 0; i < data.length; i += 4) {
    r += data[i];
    g += data[i + 1];
    b += data[i + 2];
  }
  return { r: r / n, g: g / n, b: b / n };
}

/** Mean rec.709 luma over RGBA bytes. */
export function meanLuma(data: Uint8Array): number {
  let sum = 0;
  const n = data.length / 4;
  for (let i = 0; i < data.length; i += 4) {
    sum += luma(data[i], data[i + 1], data[i + 2]);
  }
  return sum / n;
}

/** Luma standard deviation over RGBA bytes. */
export function lumaStdDev(data: Uint8Array): number {
  const n = data.length / 4;
  const mean = meanLuma(data);
  let acc = 0;
  for (let i = 0; i < data.length; i += 4) {
    const d = luma(data[i], data[i + 1], data[i + 2]) - mean;
    acc += d * d;
  }
  return Math.sqrt(acc / n);
}

/** One-stop stats for RGBA bytes (single definition of the metrics). */
export function analyzeCrop(data: Uint8Array, width: number, height: number): CropStats {
  return {
    mean: meanRgb(data),
    meanLuma: meanLuma(data),
    lumaStdDev: lumaStdDev(data),
    width,
    height,
  };
}

/**
 * Decode a PNG buffer (Playwright screenshot bytes), centre-crop it and
 * return the stats. `fraction` defaults to the middle 50% of each axis.
 */
export function analyzePngCenterCrop(png: Buffer, fraction = 0.5): CropStats {
  const decoded = PNG.sync.read(png);
  const crop = centerCrop(decoded.data, decoded.width, decoded.height, fraction);
  return analyzeCrop(crop.data, crop.width, crop.height);
}

/**
 * Fraction (0..1) of centre-crop pixels satisfying `pred(r,g,b)`.
 *
 * Unlike the mean-RGB fingerprint (which averages a coloured object
 * away against its background), this counts how much of the crop is a
 * specific colour — the tool for proving a distinct object (e.g. the
 * configured red hero robe) is actually present in the live world.
 * Pure: decode → centre-crop → matches / total. `fraction` is the
 * centre-crop size on each axis (default middle 50%).
 */
export function colorMatchFraction(
  png: Buffer,
  pred: (r: number, g: number, b: number) => boolean,
  fraction = 0.5,
): number {
  const decoded = PNG.sync.read(png);
  const crop = centerCrop(decoded.data, decoded.width, decoded.height, fraction);
  const { data } = crop;
  const n = data.length / 4;
  if (n === 0) return 0;
  let hits = 0;
  for (let i = 0; i < data.length; i += 4) {
    if (pred(data[i], data[i + 1], data[i + 2])) hits++;
  }
  return hits / n;
}

/**
 * Convenience over {@link colorMatchFraction}: fraction of centre-crop
 * pixels that are saturated-red-dominant (`r>150 && r>g*1.8 && r>b*1.8`).
 *
 * The seeded Six Realms hero robe is `#e21422` (r≈226,g≈20,b≈34); no
 * biome background is red-dominant (desert is tan with high G+B, cave
 * is warm-brown but not `r > g·1.8`), so a non-trivial fraction here
 * isolates the hero avatar rather than the world palette. The desert
 * negative control lands near 0.
 */
export function redDominantFraction(png: Buffer, fraction = 0.5): number {
  return colorMatchFraction(
    png,
    (r, g, b) => r > 150 && r > g * 1.8 && r > b * 1.8,
    fraction,
  );
}
