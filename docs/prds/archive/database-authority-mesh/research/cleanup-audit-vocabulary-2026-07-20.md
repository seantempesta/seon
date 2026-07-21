---
type: research
status: complete
tags: [research, architecture, database]
---

# Vocabulary cleanup audit — 2026-07-20

Read-only audit of active source (`src/`, `bin/`, `config/`, `test/`) on
branch `codex/runtime-reliability-refactor` against `CLAUDE.md` §Vocabulary,
the "functions, not verbs" ruling, and the dependency-seam-naming rule.

## Dependency ledger

Authorities read for this audit: `/Users/sean/src/seon/CLAUDE.md`
(§Vocabulary, §Data-oriented Clojure rules), owner rulings
`feedback_functions_not_verbs`, `feedback_canvas_is_default_interactive_surface`.
Seam dependencies whose own names govern their boundaries: Datahike/Proximum
(store ID, branch, branch head, commit ID, basis `:t`, transaction report,
db-before/db-after; `reference-code/datahike`), konserve (store), Datastar
(elements, signals, patch, morph, SSE datalines), shadow-cljs (build,
runtime, worker), Malli (schema), reitit (route, match), cljs.test (report
map `:type`), Bun/Node (process, child, socket), Inspect AI (task, scorer,
sample, epoch).

## Findings summary

The legislated vocabulary is in very good shape in `src/`. The remaining work
is (a) legacy "tile" naming concentrated in tests and one repair fixture, (b)
one consumer-owned `gym` remnant in `bin/acme`, and (c) a small set of
correct-but-confusing collisions worth comments, not renames.

## Violations table (legislated term broken)

| Term found | Evidence | Correct term | Class |
|---|---|---|---|
| tile (canvas surface) | test/seon/render/canvas_test.cljs:38,45,53,67–97,161,180–203 (`tile-fn`, `plan-tile`, "default tile", "broken tile", "tile-isolation") | canvas / canvas function | hard violation (test prose + fixture symbol names) |
| tile | test/my/ui_test.cljs:3 ("agent stacks into a tile") | surface (or canvas if focal) | hard violation (doc prose) |
| tile | test/seon/repair_test.cljc:94–120 (`my-kb-high-scores-tile`, "Start Screen Tile") | canvas function names | hard violation (fixture strings) |
| tile | test/seon/web/reactive/transform_test.cljs:116; test/seon/ui/html_test.cljc:242–265 (`tile` local + "agent tile") | surface/canvas | hard violation (test prose/locals) |
| tile | test/seon/repl/internal_test.cljc:842–846 ("Define the tile", `my-tile`) | canvas | hard violation (fixture strings) |
| gym | bin/acme:106–126 (`gym-diffusion`, `acme/gym/scenarios/`, `diffusion_gym.bb`) | consumer-owned scenario runner naming; "gym" is a retired Seon surface — rename in the acme lane's window | legacy remnant (downstream-owned paths) |
| verbs | test/seon/repl/internal_test.cljc:999,1005 ("both verbs are undefined", "dynamic verbs installed at boot") | functions | leftover in fixture prose (functions-not-verbs ruling) |

Not found anywhere in active source: `live-tile`, `world` (as surface noun —
all `world` hits are "hello world"/"source-world" prose), `inspector`,
`environment` as a cluster synonym (all hits are process/OS environment,
which is correct English), `coordinate`/`point`/`attachment` as generic
database maps (artifact_test "git coordinate" = Maven/Git dependency
coordinate, correct; `uds.clj` `.attachment` = `java.nio` SelectionKey API,
the dependency's own name).

## :recent-verbs / verb-offers migration status

Already migrated. The only remaining occurrences are historical comments:

- src/seon/agent/ctx/menu.cljs:477 — comment noting `:function-menu`
  "(renamed from `:recent-verbs`, …)". Keep or prune at leisure.
- src/seon/error.cljs:227–228 — comment: `:seon.eval/repl-form` "was
  `:seon.eval/repl-verb` until 2026-07-12"; old datoms carry the old attr.
  This comment is load-bearing (persisted-data compatibility note); keep.
- test/seon/repl/internal_test.cljc:999,1005 — fixture prose says "verbs";
  safe-now string edit.

No live code name uses `verb` (all other hits are `verbatim`/`verbose`).

## Synonym-drift inventory

Frequencies are whole-word case-insensitive counts across `src/`.

| Concept | Words in use (count) | Recommendation | Migration cost |
|---|---|---|---|
| Focal agent surface | canvas (324), tile (0 in src, ~30 in test), world (0) | **canvas** (legislated) | test-only string/symbol edits; zero schema impact |
| Context render unit | surface (224), card (119), panel (28), block (605) | Already differentiated: **block** = context unit, **surface** = a context render, **card** = CSS component only. `panel` (src/seon/render/value.cljs:12,43,706–727; render.cljs:522 `data-panel`; my/plan/internal.cljs:1828+) is a fourth word for the interactive HTML value view — either legislate "panel" for the drill-down value viewer or fold into "surface". Recommend: legislate **panel = the interactive value drill-down component** (dominant existing usage, coherent) | none if legislated; ~28 renames if folded |
| Live update channel | feed (227), stream (215), subscription (51) | **feed** for Seon's DB-derived SSE feeds (`:seon.web.feed/*` attrs already own it); "stream" only at the Datastar/HTTP seam (SSE is literally a stream — dependency name) and LLM streaming (`:stream` is the provider's own field). "subscription" appears mostly at listen!/trigger seams — audit case-by-case | low; mostly prose |
| Eval result | envelope (243), result (heavy), response (moderate) | **envelope** = the in-memory eval-result map (src/seon/eval.cljs:1374 declares it); **result** = the persisted `result/<id>` value; **response** = HTTP/LLM only. Current usage already follows this; legislate it | zero code |
| REPL/process session | session (597) | consistent; note collision: MCP REPL session vs UDS transport session (src/seon/db/transport/uds.clj) vs UI session — all genuinely session-shaped; no action | — |
| Agent execution | loop (434) / run / turn (1277) | consistent with architecture (loop/run/turn); no drift found | — |

## Seam-alignment findings per dependency

- **Datahike/Proximum — ALIGNED.** `src/seon/web/datastar.cljs:921–1156`
  uses exactly `store-id`, `branch`, `commit-id`, `basis-t`,
  `::db.branch/head`, and reads `:datahike/commit-id`/`:t` off the database
  value. `src/seon/db/restore/schema.cljc` uses `from/to/forced-commit-id`.
  No generic coordinate/point/attachment maps found. No `datahike.api` calls
  outside `src/seon/db/` (only alias-named prose in embed.clj comments).
- **konserve — ALIGNED.** "store" at the konserve/backend seam
  (src/seon/db/backend.clj, embed/preflight.clj `{:store {:backend :memory}}`)
  is the dependency's own config key. Seon-level prose uses of "store"
  ("blob store", "shared store" in src/seon/render/canvas.cljs:528 agent
  prose, "eval result store" in render/value.cljs:150) are *drift*, not
  hard violations — CLAUDE.md bans "store" as *the db noun*; "blob store" is
  a distinct konserve-backed thing. Recommend agent-facing prose in
  canvas.cljs:528 say "database" ("entity in the shared store" → "entity in
  the database") since that IS the db.
- **Datastar — ALIGNED.** `patch-elements`, datalines, morph, patch mode
  `outer`, signals (`data-bind`, `$planstep`) all use Datastar's own names
  (src/seon/web/datastar.cljs:17–140).
- **cljs.test — correct-but-confusing.** `src/seon/test/runner.cljs:38,184–282`
  uses bare `:type` keys. This mirrors cljs.test's report-map contract (the
  dependency's own name) and is correct at the seam; worth the existing
  comment, not a rename.
- **Malli/reitit/shadow-cljs/Bun — no invented umbrella nouns found**
  (schema, route, build/runtime/watcher, process/child/socket used directly;
  `seon.dev.artifact` "coordinate" = dependency Git coordinate, standard
  Clojure deps vocabulary).
- **Inspect AI** — out of the audited tree (`src-inspect-ai/` untracked);
  not audited here.

## :seon.error/kind and :seon.repl/kind

`:seon.error/kind` (enum classifying an error value: :user-input, :agent,
:core-bug) and `:seon.repl/kind` (`[:enum :form :read :comment]`,
src/seon/repl.cljs:71) are value classifications on closed enums, not entity
taxonomies — entities are still found by attribute presence. **Correct but
collides with the banned word.** Recommend either (a) a one-line comment at
each register! noting it is a closed error/parse classification, not an
entity kind, or (b) rename to `:seon.error/class` in a schema-migration
window. (a) is sufficient.

## Proposed unified glossary (next §Vocabulary revision)

| Say | Never | Meaning |
|---|---|---|
| functions, schemas, tests | verbs | ordinary Clojure constructs |
| database or `db` | store, inventory, memory | the `seon.db` authority |
| canvas | tile, live-tile, world | `:seon.render.canvas/content`, the focal agent surface |
| surface; card for CSS only | tile | a context render; a visual component |
| panel | viewer, explorer | the interactive value drill-down component in the web UI |
| block | section (for context units) | one database-derived context unit |
| feed | stream (except SSE/LLM seams), subscription | a DB-derived live update channel (`:seon.web.feed/*`) |
| envelope | — | the in-memory eval-result map; `result` = persisted value; `response` = HTTP/LLM only |
| web UI | inspector | `/`, `/agent/{id}`, debug, and `/data` |
| subagents | collaboration system | agents connected through database refs |
| cluster | environment | one database, pod, root, and task agents |
| attributes + connections | entity kind/type | the Datahike model |

(Database seam rows unchanged from current CLAUDE.md.)

## Ordered migration plan

1. **Safe now (string/prose only, no schema, no behavior):**
   - Rename `tile` symbols/prose in test files: canvas_test.cljs,
     ui_test.cljs, repair_test.cljc fixtures, html_test.cljc,
     transform_test.cljs, internal_test.cljc:842–846.
   - Fix "verbs" fixture prose in internal_test.cljc:999,1005.
   - Reword canvas.cljs:528 agent prose "shared store" → "database".
2. **Legislate without code change:** add panel/feed/envelope/block rows to
   §Vocabulary; add closed-enum comments at `:seon.error/kind` and
   `:seon.repl/kind` register! sites.
3. **Quiet-surface-window (downstream-owned):** `bin/acme gym-diffusion` +
   `acme/gym/` path naming — coordinate with the acme lane owner; the "gym"
   word is retired at the Seon level.
4. **Schema-migration-required:** none found. (`:seon.eval/repl-verb` is
   already migrated; only historical datoms carry it, documented at
   error.cljs:227.)
