---
type: issue
status: resolved
severity: friction
tags: [issue, mcp, sci, runtime]
---

RESOLVED 2026-08-03 by `a54b8bddb` (Bound MCP door value projections):
recursive collection windowing, nested string clipping with the print
grammar's elision markers, and shipped `:seon.print/length 32` /
`:seon.print/level 8` defaults. Live proof, independently re-verified
by the orchestrator on the reforked default: door `(vec (range 50000))`
envelope 304,265 → ~4,332 bytes with `windowed? true`, digest, and
`retrievable? true`; `get_value` pages the admitted artifact correctly
(offset 8000 → `[8000..8007]`; past the admitted 8,192 length returns
empty honestly). One mechanism, no MCP-only budget; the MCP server
script was untouched, so no client restart was needed.

# MCP door eval envelope leaks nested bulk through the top-level window

## Observed (2026-08-03, fresh `default` JVM at HEAD after reset)

`eval_clj` mode `door` with `(vec (range 50000))` returned a 304,265-character
envelope. The blob/digest side of ruling #44 WORKED: the outer projection
reports `windowed? true`, `capped? true`, `:seon.blob/digest` +
`:seon.blob/size 689050`, `retrievable? true`. The leak is the "projected
window" itself:

- `render.value/print-node-window` windows only the TOP-LEVEL collection.
  The projected value is `evaluate`'s result map (~7 entries, all kept at
  page-size 8), and its NESTED entries pass through whole:
  - `:seon.sci.admit/value` — the admission-capped 8,192-element vector
    (max-collection cap applied, ~40K chars);
  - `:seon.cluster.eval/result-edn` — the print projection at 262,144 chars
    (max-string cap), because `seon.print/options` length/level are BOTH
    null: the schema declares `:seon.print/length` but `config/default.edn`
    ships no default, so printing is unbounded up to max-string.
- The envelope therefore duplicates the value twice (admitted window + print
  string) at ~304K chars into the caller's context.

Direct probe evidence: `(mcp-project "default" eff (vec (range 50000)))` on a
RAW value returns an 8-element window + digest correctly. The defect only
manifests when the projected value nests large children — exactly what door
mode always produces, since it wraps `evaluate`'s whole result map.

## Expected

Ruling #44 (2): a bounded projected window plus a retrievable digest, one
mechanism, no MCP-only budget. Ruling #25: result-edn over the blob threshold
settles to a blob with a bounded window at the RECEIPT seam — door mode has no
receipt, so nothing bounds its result-edn today.

## Fix direction (one mechanism, not an MCP budget)

1. Make the print-node window bound NESTED content: window collections at
   every level and clip oversized strings with the print grammar's existing
   cut/elision markers. The complete artifact stays in the blob; `get_value`
   drills it.
2. Ship a config default for `:seon.print/length` (and level) so result-edn
   prints like a real REPL with `*print-length*` set, with the loud "N of M"
   elision line derived — bounded for every consumer, not just MCP.

## Acceptance

Door-mode `(vec (range 50000))` returns an envelope a few KB at most, with
digest + honest counts; `get_value` drills the digest to any depth/offset;
the same nested-bulk value through `mcp-project` in a test proves the window
bounds every level; no second truncation mechanism exists.
