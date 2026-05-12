#!/usr/bin/env python3
"""
Generate a complete physical + logical schema diagram from every Liquibase
changelog + the Cassandra CQL init file. Emits Graphviz `dot` source, then
renders it to a high-resolution PNG (and a PDF) so the architecture PDF +
the data-architect deck can include it on a landscape page wide enough to
zoom in to easily.

Sources scanned:
  - backend/src/main/resources/db/changelog/*.xml            (BFF Postgres)
  - rpg-builder/src/main/resources/db/changelog/*.xml        (RPG-builder Postgres)
  - vn-builder/src/main/resources/db/changelog/*.xml         (VN-builder Postgres)
  - docker/cassandra-init.cql                                (Cassandra)

For each table the diagram shows:
  - the column list (name + type)
  - PK markers
  - foreign-key edges where the changelog declared one via
    <addForeignKeyConstraint> or `<constraints foreignKeyName="..." references="..."/>`
  - cluster grouping by source database (so the three Postgres tenants
    + Cassandra appear as distinct subgraphs)

The diagram is intentionally regenerated on every `make docs-schema-diagram`
run rather than checked in, so it stays in sync with the schema as
changelogs land.
"""
from __future__ import annotations

import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent.parent.parent
PUB_DIR   = REPO_ROOT / "docs" / "publishing"
OUT_DIR   = PUB_DIR / "diagrams-rendered" / "build"
OUT_DOT   = OUT_DIR / "schema-diagram.dot"
OUT_PNG   = OUT_DIR / "schema-diagram.png"
OUT_PDF   = OUT_DIR / "schema-diagram.pdf"

# Per-database outputs — one PDF per cluster so each can be embedded on
# its own page in the architecture + data-architect PDFs at a size large
# enough to zoom in to easily.
PER_DB_PDFS = {
    "bff":         OUT_DIR / "schema-diagram-bff.pdf",
    "rpg-builder": OUT_DIR / "schema-diagram-rpg-builder.pdf",
    "vn-builder":  OUT_DIR / "schema-diagram-vn-builder.pdf",
    "cassandra":   OUT_DIR / "schema-diagram-cassandra.pdf",
}

# Auto-generated \includepdf snippets. Each includes the per-database
# PDF on its own page sized to match the source PDF's natural width +
# height (so LaTeX's geometry-fixed A4 doesn't squeeze the diagram).
# Architecture chapter 4 \input's the `-arch` variant (paths relative to
# the architecture dir); the data-architect userguide \input's the
# `-userguide` variant (filename-only, resolved via TEXINPUTS).
INCLUDE_TEX_ARCH      = OUT_DIR / "schema-diagram-includes-arch.tex"
INCLUDE_TEX_USERGUIDE = OUT_DIR / "schema-diagram-includes-userguide.tex"

LB_NS = "{http://www.liquibase.org/xml/ns/dbchangelog}"


@dataclass
class Column:
    name: str
    type: str
    pk: bool = False
    nullable: bool = True
    fk_ref: str | None = None  # "table.column"


@dataclass
class Table:
    name: str
    cluster: str          # "bff" / "rpg-builder" / "vn-builder" / "cassandra"
    columns: list[Column] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)


def parse_liquibase_dir(dir_path: Path, cluster: str, tables: dict[str, Table]) -> None:
    """Scan every changelog .xml in `dir_path`; mutate `tables` in place."""
    for xml_path in sorted(dir_path.glob("*.xml")):
        if xml_path.name == "db.changelog-master.xml":
            continue
        try:
            tree = ET.parse(xml_path)
        except ET.ParseError as exc:
            print(f"WARN: {xml_path}: {exc}", file=sys.stderr)
            continue
        for ct in tree.iter(f"{LB_NS}createTable"):
            tname = ct.attrib.get("tableName")
            if not tname:
                continue
            t = tables.setdefault(tname, Table(name=tname, cluster=cluster))
            for col in ct.findall(f"{LB_NS}column"):
                c = Column(
                    name=col.attrib.get("name", "?"),
                    type=col.attrib.get("type", "?"),
                )
                cons = col.find(f"{LB_NS}constraints")
                if cons is not None:
                    if cons.attrib.get("primaryKey", "").lower() == "true":
                        c.pk = True
                    if cons.attrib.get("nullable", "").lower() == "false":
                        c.nullable = False
                    ref = cons.attrib.get("references")
                    if ref:
                        c.fk_ref = ref.replace(" ", "")
                t.columns.append(c)

        # Standalone FK constraints — <addForeignKeyConstraint baseTableName=... baseColumnNames=... referencedTableName=... referencedColumnNames=.../>
        for fk in tree.iter(f"{LB_NS}addForeignKeyConstraint"):
            base = fk.attrib.get("baseTableName")
            bcol = fk.attrib.get("baseColumnNames")
            rtab = fk.attrib.get("referencedTableName")
            rcol = fk.attrib.get("referencedColumnNames")
            if not (base and bcol and rtab and rcol):
                continue
            t = tables.get(base)
            if t is None:
                continue
            for col in t.columns:
                if col.name == bcol and not col.fk_ref:
                    col.fk_ref = f"{rtab}({rcol})"

        # <addColumn tableName="X"><column name=... type=...>
        for ac in tree.iter(f"{LB_NS}addColumn"):
            tname = ac.attrib.get("tableName")
            if not tname or tname not in tables:
                continue
            t = tables[tname]
            for col in ac.findall(f"{LB_NS}column"):
                if col.attrib.get("name") in {c.name for c in t.columns}:
                    continue
                c = Column(
                    name=col.attrib.get("name", "?"),
                    type=col.attrib.get("type", "?"),
                )
                cons = col.find(f"{LB_NS}constraints")
                if cons is not None:
                    if cons.attrib.get("nullable", "").lower() == "false":
                        c.nullable = False
                t.columns.append(c)


def parse_cassandra(cql_path: Path, tables: dict[str, Table]) -> None:
    """Extract CREATE TABLE blocks from the .cql; very permissive parser."""
    text = cql_path.read_text()
    text = re.sub(r"--[^\n]*", "", text)        # strip line comments
    for m in re.finditer(
        r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?(\w+)\s*\((.*?)\)\s*(?:WITH[^;]*)?;",
        text, re.IGNORECASE | re.DOTALL,
    ):
        tname = m.group(1)
        body  = m.group(2)
        t = Table(name=tname, cluster="cassandra")
        pk_names: set[str] = set()
        pk_match = re.search(r"PRIMARY\s+KEY\s*\(\(?([^)]+)\)?(?:\s*,\s*([^)]+))?\)",
                             body, re.IGNORECASE)
        if pk_match:
            partition = pk_match.group(1)
            clustering = pk_match.group(2) or ""
            for raw in re.split(r"\s*,\s*", partition + "," + clustering):
                nm = raw.strip().rstrip(",").strip()
                if nm:
                    pk_names.add(nm)
        # Column lines: "name type" before the PRIMARY KEY clause.
        for line in body.split(","):
            line = line.strip().rstrip(",")
            if not line or line.upper().startswith("PRIMARY"):
                continue
            parts = line.split(None, 1)
            if len(parts) != 2:
                continue
            cname, ctype = parts
            if cname.upper() == "PRIMARY":
                continue
            t.columns.append(Column(
                name=cname, type=ctype.strip(),
                pk=(cname in pk_names),
                nullable=(cname not in pk_names),
            ))
        tables[tname] = t


# ── Build the model ───────────────────────────────────────────────────────
def build_model() -> dict[str, Table]:
    tables: dict[str, Table] = {}
    parse_liquibase_dir(REPO_ROOT / "backend" / "src" / "main" / "resources" / "db" / "changelog",
                        "bff", tables)
    rpg = REPO_ROOT / "rpg-builder" / "src" / "main" / "resources" / "db" / "changelog"
    if rpg.exists():
        parse_liquibase_dir(rpg, "rpg-builder", tables)
    vn = REPO_ROOT / "vn-builder" / "src" / "main" / "resources" / "db" / "changelog"
    if vn.exists():
        parse_liquibase_dir(vn, "vn-builder", tables)
    cql = REPO_ROOT / "docker" / "cassandra-init.cql"
    if cql.exists():
        parse_cassandra(cql, tables)
    return tables


# ── Emit Graphviz dot ─────────────────────────────────────────────────────
CLUSTER_LABEL = {
    "bff":         "BFF Postgres — gateway, catalog, identity, sessions",
    "rpg-builder": "RPG-builder Postgres — areas, rooms, exits, items, NPCs",
    "vn-builder":  "VN-builder Postgres — acts, scenes, characters, lines, choices",
    "cassandra":   "Cassandra — high-write cache, audit, rate-limit log",
}
CLUSTER_COLOUR = {
    "bff":         "#5B3FD9",
    "rpg-builder": "#3E91B8",
    "vn-builder":  "#A45BD9",
    "cassandra":   "#D9663F",
}


def escape_dot(s: str) -> str:
    return (s.replace("\\", "\\\\")
             .replace("\"", r"\"")
             .replace("<", "&lt;")
             .replace(">", "&gt;")
             .replace("&", "&amp;"))


def column_row(col: Column) -> str:
    badge = ""
    if col.pk:
        badge = "<B>PK</B> "
    elif col.fk_ref:
        badge = "<I>FK</I> "
    type_short = col.type
    # Trim long-form decimal/varchar wrappers for readability.
    type_short = re.sub(r"\s+", " ", type_short).strip()
    return (
        f'<TR><TD ALIGN="LEFT">{badge}{escape_dot(col.name)}</TD>'
        f'<TD ALIGN="LEFT"><FONT POINT-SIZE="9" COLOR="#666666">'
        f'{escape_dot(type_short)}</FONT></TD></TR>'
    )


def render_single_cluster_dot(tables: dict[str, Table], cluster_name: str) -> str:
    """Emit a Graphviz dot source containing only one cluster's tables +
    intra-cluster FK edges. Used for the per-database PDFs."""
    colour = CLUSTER_COLOUR[cluster_name]
    label  = CLUSTER_LABEL[cluster_name]
    cluster_tables = [t for t in tables.values() if t.cluster == cluster_name]
    if not cluster_tables:
        return ""

    lines: list[str] = [
        "digraph SnapdragonSchemaSingle {",
        '  rankdir=LR;',
        f'  graph [fontname="Inter, Helvetica, sans-serif", fontsize=18, bgcolor="#FFFFFF", pad=0.4, splines=ortho, nodesep=0.4, ranksep=1.0, label="{label}", labelloc=t, fontcolor="{colour}"];',
        '  node  [fontname="Inter, Helvetica, sans-serif", fontsize=11, shape=plain];',
        '  edge  [fontname="Inter, Helvetica, sans-serif", fontsize=9, color="#888888"];',
        "",
    ]
    table_names = {t.name for t in cluster_tables}
    for t in sorted(cluster_tables, key=lambda t: t.name):
        header = (
            f'<TR><TD COLSPAN="2" BGCOLOR="{colour}" PORT="header">'
            f'<FONT COLOR="white"><B>{escape_dot(t.name)}</B></FONT></TD></TR>'
        )
        cols = "".join(column_row(c) for c in t.columns)
        lines.append(
            f'  "{t.name}" [label=<<TABLE BORDER="0" CELLBORDER="1" CELLSPACING="0">'
            f'{header}{cols}</TABLE>>];'
        )
    lines.append("")
    # FK edges only within this cluster.
    for t in cluster_tables:
        for c in t.columns:
            if not c.fk_ref:
                continue
            m = re.match(r"(\w+)\(", c.fk_ref)
            if not m:
                continue
            target = m.group(1)
            if target in table_names:
                lines.append(f'  "{t.name}" -> "{target}" [arrowhead=normal, style=solid];')

    lines.append("}")
    return "\n".join(lines)


def render_dot(tables: dict[str, Table]) -> str:
    lines: list[str] = [
        "digraph SnapdragonSchema {",
        '  rankdir=LR;',
        '  graph [fontname="Inter, Helvetica, sans-serif", fontsize=14, bgcolor="#FFFFFF", pad=0.4, splines=ortho, nodesep=0.4, ranksep=1.0];',
        '  node  [fontname="Inter, Helvetica, sans-serif", fontsize=11, shape=plain];',
        '  edge  [fontname="Inter, Helvetica, sans-serif", fontsize=9, color="#888888"];',
        "",
        # Title banner
        '  legend [label=<<TABLE BORDER="0" CELLBORDER="0" CELLPADDING="6">'
        '<TR><TD COLSPAN="2"><B>Snapdragon Games — complete schema</B></TD></TR>'
        '<TR><TD ALIGN="LEFT"><B>PK</B></TD><TD ALIGN="LEFT">primary key</TD></TR>'
        '<TR><TD ALIGN="LEFT"><I>FK</I></TD><TD ALIGN="LEFT">foreign-key column (edge shown)</TD></TR>'
        '<TR><TD ALIGN="LEFT" BGCOLOR="#5B3FD9"><FONT COLOR="white">BFF</FONT></TD><TD ALIGN="LEFT">primary Postgres (catalog + identity + sessions)</TD></TR>'
        '<TR><TD ALIGN="LEFT" BGCOLOR="#3E91B8"><FONT COLOR="white">RPG</FONT></TD><TD ALIGN="LEFT">rpg-builder side-service Postgres</TD></TR>'
        '<TR><TD ALIGN="LEFT" BGCOLOR="#A45BD9"><FONT COLOR="white">VN</FONT></TD><TD ALIGN="LEFT">vn-builder side-service Postgres</TD></TR>'
        '<TR><TD ALIGN="LEFT" BGCOLOR="#D9663F"><FONT COLOR="white">CAS</FONT></TD><TD ALIGN="LEFT">Cassandra (cache, audit, rate-limit log)</TD></TR>'
        '</TABLE>>, shape=box, style="rounded,filled", fillcolor="#F4EFFE"];',
        "",
    ]

    # Cluster subgraphs.
    by_cluster: dict[str, list[Table]] = {}
    for t in tables.values():
        by_cluster.setdefault(t.cluster, []).append(t)

    for cluster_idx, cluster_name in enumerate(["bff", "rpg-builder", "vn-builder", "cassandra"]):
        cluster_tables = by_cluster.get(cluster_name, [])
        if not cluster_tables:
            continue
        colour = CLUSTER_COLOUR[cluster_name]
        label  = CLUSTER_LABEL[cluster_name]
        lines.append(f'  subgraph cluster_{cluster_idx} {{')
        lines.append(f'    label="{label}";')
        lines.append(f'    style="rounded,filled";')
        lines.append(f'    fillcolor="{colour}11";')
        lines.append(f'    color="{colour}";')
        lines.append(f'    penwidth=1.5;')
        lines.append(f'    fontcolor="{colour}";')
        lines.append("")
        for t in sorted(cluster_tables, key=lambda t: t.name):
            header = (
                f'<TR><TD COLSPAN="2" BGCOLOR="{colour}" PORT="header">'
                f'<FONT COLOR="white"><B>{escape_dot(t.name)}</B></FONT></TD></TR>'
            )
            cols = "".join(column_row(c) for c in t.columns)
            lines.append(
                f'    "{t.name}" [label=<<TABLE BORDER="0" CELLBORDER="1" CELLSPACING="0">'
                f'{header}{cols}</TABLE>>];'
            )
        lines.append("  }")
        lines.append("")

    # Foreign-key edges.
    for t in tables.values():
        for c in t.columns:
            if not c.fk_ref:
                continue
            m = re.match(r"(\w+)\(", c.fk_ref)
            if not m:
                continue
            target = m.group(1)
            if target in tables:
                lines.append(f'  "{t.name}" -> "{target}" [arrowhead=normal, style=solid];')

    lines.append("}")
    return "\n".join(lines)


def render(dot_src: str, fmt: str, out: Path) -> bool:
    out.parent.mkdir(parents=True, exist_ok=True)
    out_dot = out.with_suffix(".dot")
    out_dot.write_text(dot_src)
    cmd = ["dot", f"-T{fmt}", str(out_dot), "-o", str(out)]
    if fmt == "png":
        cmd[1:1] = ["-Gdpi=200"]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"ERROR: dot failed for {out}: {result.stderr}", file=sys.stderr)
        return False
    print(f"✓ wrote {out}")
    return True


def page_size(pdf_path: Path) -> tuple[float, float] | None:
    """Return (width_pt, height_pt) from `pdfinfo` output, or None."""
    try:
        r = subprocess.run(["pdfinfo", str(pdf_path)], capture_output=True, text=True)
    except FileNotFoundError:
        return None
    if r.returncode != 0:
        return None
    for line in r.stdout.split("\n"):
        m = re.match(r"Page size:\s+([0-9.]+)\s+x\s+([0-9.]+)\s+pts", line)
        if m:
            return float(m.group(1)), float(m.group(2))
    return None


def emit_include_snippets(produced: list[tuple[str, Path]]) -> None:
    """Emit two `\\input`-able .tex snippets — one for the architecture
    chapter, one for the data-architect userguide — with each diagram's
    \\includepdf preceded by an \\AddToHookNext{shipout} block that
    overrides \\pdfpagewidth / \\pdfpageheight to the source PDF's
    natural size. This is the workaround for the geometry package's
    paper-size lock at document start."""
    arch_lines: list[str] = ["% Auto-generated by build-schema-diagram.py — do not edit by hand."]
    user_lines: list[str] = ["% Auto-generated by build-schema-diagram.py — do not edit by hand."]
    for cluster, pdf_path in produced:
        wh = page_size(pdf_path)
        if wh is None:
            print(f"  WARN: cannot read page size of {pdf_path}; skipping include for {cluster}", file=sys.stderr)
            continue
        w, h = wh
        fname = pdf_path.name
        # \afterpage queues an action for the START of the next page,
        # which is exactly when \pdfpagewidth / \pdfpageheight need to
        # be set for that page's shipout. Unlike \AddToHookNext{shipout}
        # which only retains the latest call, \afterpage builds a
        # proper FIFO queue, so all five diagram-sized hooks fire on
        # their matching pages.
        hook = (
            f"\\afterpage{{\\clearpage"
            f"\\global\\pdfpagewidth={w:.2f}pt"
            f"\\global\\pdfpageheight={h:.2f}pt}}"
        )
        snippet_arch = (
            f"{hook}\n"
            f"\\includepdf[pages=1,fitpaper=false,noautoscale=true,"
            f"pagecommand={{\\thispagestyle{{empty}}}}]"
            f"{{../diagrams-rendered/build/{fname}}}\n"
        )
        snippet_user = (
            f"{hook}\n"
            f"\\includepdf[pages=1,fitpaper=false,noautoscale=true,"
            f"pagecommand={{\\thispagestyle{{empty}}}}]"
            f"{{{fname}}}\n"
        )
        arch_lines.append(snippet_arch)
        user_lines.append(snippet_user)

    INCLUDE_TEX_ARCH.write_text("\n".join(arch_lines))
    INCLUDE_TEX_USERGUIDE.write_text("\n".join(user_lines))
    print(f"✓ wrote {INCLUDE_TEX_ARCH}")
    print(f"✓ wrote {INCLUDE_TEX_USERGUIDE}")


def main() -> int:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    tables = build_model()
    print(f"→ parsed {len(tables)} tables across all schema sources")

    produced: list[tuple[str, Path]] = []

    # Per-database PDFs first so the include-snippets ordering puts
    # tenant-scoped views before the combined cross-cluster view.
    for cluster, pdf_path in PER_DB_PDFS.items():
        per = render_single_cluster_dot(tables, cluster)
        if not per:
            print(f"  skipped {cluster} (no tables)")
            continue
        if not render(per, "pdf", pdf_path): return 1
        produced.append((cluster, pdf_path))

    # Combined diagram (all clusters + cross-cluster FKs).
    combined = render_dot(tables)
    OUT_DOT.write_text(combined)
    if not render(combined, "png", OUT_PNG): return 1
    if not render(combined, "pdf", OUT_PDF): return 1
    produced.append(("combined", OUT_PDF))

    emit_include_snippets(produced)
    return 0


if __name__ == "__main__":
    sys.exit(main())
