#!/usr/bin/env bash
set -euo pipefail

# Manages the committed scalafmt oracle corpus consumed by kyo-print's parity
# tests (kyo-print/shared/src/test/scala/kyo/ScalafmtOracle.scala).
#
#   scripts/scalafmt-oracle.sh harvest [--families binPack,optIn]
#       Regenerate the corpus from a scalafmt checkout. The checkout location
#       defaults to ~/src/scalafmt and can be overridden with SCALAFMT_DIR.
#       The revision it was harvested from is recorded inside the generated
#       file; regenerate from a moved-forward checkout deliberately and commit
#       the corpus diff together with it.
#
#   scripts/scalafmt-oracle.sh docs
#       Regenerate the docs-example corpus at
#       scripts/scalafmt-oracle/docs/*.stat from the mdoc:scalafmt blocks of
#       the checkout's docs/configuration.md. Expected outputs are produced by
#       running the checkout's scalafmt, with the docs site's base config
#       (default preset, maxColumn 40, scala213 dialect) baked into each case.
#       Slow on first run (compiles the checkout via sbt).
#
#   scripts/scalafmt-oracle.sh classic
#       Regenerate the classic-probe corpus at
#       scripts/scalafmt-oracle/classic/*.stat from the committed probe
#       definitions in scripts/scalafmt-oracle/classic-probes.stat. Expected
#       outputs are produced by running the checkout's scalafmt (newlines.source
#       = classic), same as the docs family. Slow on first run.
#
#   scripts/scalafmt-oracle.sh check [--families binPack,optIn,docs,classic]
#       Re-format every harvested case with the scalafmt checkout and verify
#       the committed expected output reproduces byte-for-byte. Slow on first
#       run (compiles the checkout via sbt); subsequent runs reuse its build.
#
# The default family set is "binPack,optIn,docs,classic"; the "docs" and
# "classic" families resolve to the generated stat files under
# scripts/scalafmt-oracle/<family>/, everything else to
# scalafmt-tests/src/test/resources/<family>/ in the checkout.

here="$(cd "$(dirname "$0")" && pwd)"
SCALAFMT_DIR="${SCALAFMT_DIR:-$HOME/src/scalafmt}"
OUT="kyo-print/shared/src/test/scala/kyo/ScalafmtOracle.scala"

families=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        harvest|check|docs|classic) mode="$1"; shift ;;
        --families) IFS=, read -ra families <<< "$2"; shift 2 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done
[[ -n "${mode:-}" ]] || { grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 2; }
[[ ${#families[@]} -gt 0 ]] || families=(binPack optIn docs classic)

resolve_families() {
    local out=()
    for f in "${families[@]}"; do
        case "$f" in
            docs) out+=("docs=$here/scalafmt-oracle/docs") ;;
            classic) out+=("classic=$here/scalafmt-oracle/classic") ;;
            *) out+=("$f") ;;
        esac
    done
    printf '%s\n' "${out[@]}"
}

case "$mode" in
    docs)
        [[ -d "$here/scalafmt-oracle/docs" ]] || mkdir -p "$here/scalafmt-oracle/docs"
        find "$here/scalafmt-oracle/docs" -name '*.stat' -delete
        scala-cli run "$here/scalafmt-oracle/docs.sc" -- \
            "$SCALAFMT_DIR" "$here/scalafmt-oracle/docs"
        ;;
    classic)
        [[ -d "$here/scalafmt-oracle/classic" ]] || mkdir -p "$here/scalafmt-oracle/classic"
        find "$here/scalafmt-oracle/classic" -name '*.stat' -delete
        scala-cli run "$here/scalafmt-oracle/classic.sc" -- \
            "$SCALAFMT_DIR" "$here/scalafmt-oracle/classic-probes.stat" \
            "$here/scalafmt-oracle/classic"
        ;;
    harvest)
        mapfile -t resolved < <(resolve_families)
        scala-cli run "$here/scalafmt-oracle/harvest.sc" -- \
            stat "$SCALAFMT_DIR" "$OUT" "${resolved[@]}"
        ;;
    check)
        mapfile -t resolved < <(resolve_families)
        scala-cli run "$here/scalafmt-oracle/check.sc" -- \
            "$SCALAFMT_DIR" "${resolved[@]}"
        ;;
esac
