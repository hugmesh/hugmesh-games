"""
Merge the BFF's OpenAPI 3 spec with the editorial sidecar at
docs/publishing/openapi/metadata.json.

Output is the OpenAPI spec PLUS x-hugmesh extension fields:
  - On each tag entry, the metadata's tag.blurb / marble / chapter
    are written to x-hugmesh under the tag.
  - On each operation, the metadata's longDescription / sse /
    binaryUpload / sample{Request,Response,Event} are written to
    operation.x-hugmesh.

The result is one self-describing JSON file that:
  - Is still valid OpenAPI 3.0 (the x-* extensions are explicitly
    allowed by the spec — readers ignore unknown vendor keys).
  - The Angular API explorer can render directly (it knows how to
    look in x-hugmesh for SSE + binary-upload hints).
  - The LaTeX generator can read for the long-form prose.

The merge is order-independent and idempotent: re-running with the
same inputs produces a byte-identical output.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any


def normalise_op_key(method: str, path: str) -> str:
    """ {METHOD} {PATH}, METHOD upper, no double slashes. """
    return f"{method.upper()} {path}"


def merge(spec: dict, meta: dict) -> dict:
    out = json.loads(json.dumps(spec))   # deep copy

    # ── Tags ──────────────────────────────────────────────────────────────
    tag_meta = meta.get("tags", {})
    out_tags = out.setdefault("tags", [])
    seen = {t.get("name"): t for t in out_tags}
    for tag_name, tm in tag_meta.items():
        ext = {k: v for k, v in tm.items() if v is not None}
        if tag_name in seen:
            seen[tag_name].setdefault("x-hugmesh", {}).update(ext)
        else:
            # Tag wasn't declared by the BFF (e.g. live spec missing the
            # @Tag block). Add it so the API explorer can still group by
            # name without dropping rows.
            out_tags.append({
                "name": tag_name,
                "description": tm.get("blurb", ""),
                "x-hugmesh": ext,
            })

    # ── Operations ────────────────────────────────────────────────────────
    op_meta = meta.get("operations", {})
    paths = out.get("paths", {})
    matched, unmatched = 0, []
    for path, ops in list(paths.items()):
        for method, op in list(ops.items()):
            if method.lower() not in {
                "get", "post", "put", "delete", "patch", "options", "head"
            }:
                continue
            key = normalise_op_key(method, path)
            if key in op_meta:
                ext = {k: v for k, v in op_meta[key].items() if v is not None}
                op["x-hugmesh"] = {**op.get("x-hugmesh", {}), **ext}
                matched += 1

    # Track unmatched metadata entries so the build prints what didn't
    # land — typo-catching during edit cycles.
    for key in op_meta.keys():
        try:
            method, path = key.split(" ", 1)
        except ValueError:
            unmatched.append((key, "malformed"))
            continue
        ops = paths.get(path, {})
        if method.lower() not in {m.lower() for m in ops.keys()}:
            unmatched.append((key, "no matching path/method in spec"))

    # Top-level branding so consumers can verify they're reading the
    # merged file (not the raw spec).
    out.setdefault("info", {}).setdefault("x-hugmesh", {}).update({
        "merged": True,
        "metadataPath": "docs/publishing/openapi/metadata.json",
        "publisher": "HugMesh Games",
    })

    return out, matched, unmatched


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--spec", required=True, type=Path)
    ap.add_argument("--metadata", required=True, type=Path)
    ap.add_argument("--out", required=True, type=Path)
    args = ap.parse_args()

    spec = json.loads(args.spec.read_text())
    meta_raw = json.loads(args.metadata.read_text())
    # Strip our editorial markers ($schema, _comment) before merging
    # so they never leak into the published OpenAPI document.
    meta = {k: v for k, v in meta_raw.items()
            if not k.startswith("_") and not k.startswith("$")}
    merged, matched, unmatched = merge(spec, meta)

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(merged, indent=2, sort_keys=False) + "\n")

    print(f"  matched   {matched} operation(s)")
    if unmatched:
        print(f"  WARN: {len(unmatched)} metadata entries didn't match the spec:",
              file=sys.stderr)
        for key, reason in unmatched:
            print(f"    - {key} ({reason})", file=sys.stderr)


if __name__ == "__main__":
    main()
