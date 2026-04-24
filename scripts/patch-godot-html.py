#!/usr/bin/env python3
"""
Post-export patch for Godot's generated index.html.

Godot regenerates index.html on every export, erasing custom edits.
This script re-applies two patches after each build:
  1. Adds index.side.wasm to GODOT_CONFIG fileSizes so the progress bar
     stays accurate (the 41 MB Rust WASM is the largest download).
  2. Injects a scene-prefetch script so the JSON is ready in
     window._twSceneConfig before GDScript polls for it (WASM compilation
     takes 30+ seconds, so a 200 ms fetch wins easily).

Usage: python3 scripts/patch-godot-html.py <index.html> [<index.side.wasm>]
"""

import re
import os
import sys

PREFETCH = """\

// Prefetch scene JSON early — WASM takes 30s+ to compile, so this data
// is ready long before GDScript polls for window._twSceneReady.
(function(){
\tvar p = new URLSearchParams(window.location.search);
\tvar gameId = p.get('game_id') || '1';
\tvar base = (window.location.host === 'localhost:4200') ? 'http://localhost:8080' : '';
\tfetch(base + '/api/games/' + gameId + '/scene')
\t\t.then(function(r) { return r.json(); })
\t\t.then(function(scene) {
\t\t\twindow._twSceneConfig = scene;
\t\t\twindow._twSceneReady = true;
\t\t\tconsole.log('[TW] scene prefetched:', scene.name, '—', (scene.elements || []).length, 'elements');
\t\t})
\t\t.catch(function(e) { console.warn('[TW] scene prefetch failed', e); });
})();
"""

def main():
    html_path = sys.argv[1]
    side_wasm_path = sys.argv[2] if len(sys.argv) > 2 else None

    html = open(html_path).read()

    # 1. Add index.side.wasm to fileSizes if present on disk.
    if side_wasm_path and os.path.exists(side_wasm_path):
        size = os.path.getsize(side_wasm_path)
        def add_side_wasm(m):
            body = m.group(1)
            if 'side.wasm' in body:
                return m.group(0)
            return '"fileSizes":{' + body + ',"index.side.wasm":' + str(size) + '}'
        html = re.sub(r'"fileSizes":\{([^}]+)\}', add_side_wasm, html)

    # 2. Inject prefetch immediately after engine construction.
    MARKER = 'const engine = new Engine(GODOT_CONFIG);'
    if '_twSceneReady' not in html and MARKER in html:
        html = html.replace(MARKER, MARKER + PREFETCH, 1)

    open(html_path, 'w').write(html)
    print('✓ index.html patched')

if __name__ == '__main__':
    main()
