---
type: research
status: active
tags: [research, agent]
---

# Seon CLJS Test-Suite Audit — fragility + slowness

Branch `feature/agent-fsm`, 2026-06-25. Scope: every `test/**/*.cljs` (54 files), excluding `.cljs.disabled`. Suite GREEN at audit time. Builds on the in-flight de-pinning (`789d32b`, `37280e3`, `d213c24`, `bd9b353`); reports what REMAINS.

Governing directive: [[feedback_test_behavior_not_exact_strings]] — never pin exact text of refactoring surfaces; assert mechanism/logic. KEEP byte-stable-between-renders + appear/vanish + schema/contract.

## TL;DR

The suite is already in good shape — most files assert mechanisms (set-equality on affected entities, section appears/vanishes, byte-stability across two renders, schema round-trips). NO frozen-blob snapshot equality survives. What remains: (1) one actively-churning glyph cluster (`; namespace X`), (2) a scatter of teaching/warning PROSE phrases layered on already-behavioral assertions, (3) one badly-N+1 slow file. ~38 fragile assertions across 7 namespaces: ~5 DELETE, ~28 REWRITE→behavioral, rest borderline-KEEP (API-name pins).

## Part 1 — Fragility (clustered, worst first)

### Cluster A — the `; namespace X` glyph-label anchor (14 assertions) — #1 offender

Format `bd9b353`/`c11d2a5` just changed (`;; ── namespace x ──` → `; namespace`); tests were RE-PINNED to the new label instead of de-pinned. Comment-glyph + label-format pin. The mechanism each wants is "this ns's BLOCK rendered" (vs a bare name in a `(:require …)`). Robust anchor: the rendered **`(ns X` source head** (real content in every full block), not the decorative label.

- `ctx_test.cljs` L190/193/261/266 — REWRITE: anchor on ns name + its real body form (body forms `(def helper 1)`/`(def w 2)`/`(def w 1)`/`(def k 3)` already asserted right below — the label adds only glyph-fragility).
- `agent_render_namespace_test.cljs` (whole file built on this anchor) L97/140/142/162/163/185/227 + L206-207 block-count — REWRITE: use the rendered ns-source head `(ns cyc.a`/`(ns test.parent` as the block delimiter to count/distinguish blocks; same disambiguation power, no glyph dependency.
- `handlers/test_test.cljs` L177 — REWRITE, same fix.
- (`teachings_test.cljs:315` `(str/index-of nss "; namespace ")` is corpus-cutting LOGIC not an assertion, but carries the same coupling — move with the cluster.)

### Cluster B — teaching/warning PROSE phrases (~15 assertions)

Sit atop assertions that already test the behavior; the prose string is the fragile part. Behavior covered elsewhere → DELETE; else REWRITE to anchor on the data/contract token.

- `ctx_test.cljs` L356 `"purpose is UNSET"` / L358 `"transact it onto your own"` / L373 — REWRITE: anchor unset-teaching on the attr keyword `:seon.agent/purpose`; anchor vanish on "section no longer contains that keyword / shrank".
- `ctx_test.cljs` L383 `"messages render as markdown"` (hardcoded `system-text` phrase) — REWRITE: keep the behavioral sibling L389 (every line comment-shaped / reader-valid), strengthen it to cover all of `system-text`, drop the content-phrase pin.
- `ctx_test.cljs` L808 `(not (includes? "relevant context —"))` — DELETE: redundant with L805 `(not (contains? (texts-of r1) :relevant-source))`.
- `warn_test.cljs` L337 `"convert at write time"` — DELETE/REWRITE: assert example non-blank + names both attrs.
- `warn_test.cljs` L503 `"hop cap"` — DELETE: redundant with L500/502 (`hops 4/4`).
- `warn_test.cljs` L577 `"BROKEN RIGHT NOW"` — DELETE: redundant with L571 `(true? (:seon.warn/urgent? r))`.
- `warn_test.cljs` L332 `"vs established :warntest.dom/duration-seconds (2 entities)"` — REWRITE: keep the attr name + count `2`, drop prose.
- `warn_test.cljs` L502 `"hops 4/4 — wake refused"` — REWRITE: assert includes `"4/4"`.
- `warn_test.cljs` L515 `"lookup-ref on :kb.doc/path"` — REWRITE: assert includes `:kb.doc/path`.
- `warn_test.cljs` L575 `"live tile of warntst-tile01"` — REWRITE: assert includes id `warntst-tile01`.
- `warn_test.cljs` L685-687 `[fake-healthy]`/`[warn-check-error]` — REWRITE (low): assert the kind name appears, drop bracket. (L678/681 throw-message + broken-check name are KEEP — test-owned fixtures.)
- `schema_test.cljs` L42 `:kb.workout/date` — REWRITE: assert any valid multi-segment keyword (regex `:\w+\.\w+/\w+`).
- `schema_test.cljs` L44 `(seon.db/store-inventory)` — borderline KEEP (API symbol).

### Cluster C — status-glyph + line-format pins (~8 assertions)

- `handlers/test_test.cljs` L91/92/94 + L185 `✓/✗/•` — REWRITE: assert three run-states render DISTINCTLY (pass/fail/no-run stem), not glyph+wording. L89/179 `[test demo.ns/t-pass]` — REWRITE: anchor on sym `demo.ns/t-pass`. (L93 `expected 1, got 2` borderline KEEP.)
- `agent/todo_test.cljs` L160 `(re-find #"(?m)^; \S+ \[2m\] first \(oldest\)$" block)` — REWRITE (high within C): pins glyph `;` + age-badge `[2m]` + `(oldest)` ordering label at once. Assert oldest todo's title (`"first"`) + id render, and (if ordering matters) precedes the newer item.
- `agent/todo_test.cljs` L155 `complete!` template regex — REWRITE: assert names the fn + `:seon.agent.todo/id` arg, not exact whitespace/placeholder.
- `agent/todo_test.cljs` L226/230 `#"live item"` — borderline low.
- `ctx_test.cljs` L659 `«:lift :run»` — REWRITE (low): assert `:lift` + `:run` appear, not guillemets. (L640/642 `date 3`/`type 3` live counts KEEP.)
- `ctx_test.cljs` L866 `"; # Heading"` — low: L867 already asserts every line comment-shaped; glyph pin redundant.

### Cluster D — exact-arglist pins on LIVE fns (also the slow file)

`index_core_test.cljs` uses live `seon.db` fns as parser fixtures → a refactor of `db/pull`/`db/query`/`db/entity` arglists breaks the parser test.
- L84/95/96 exact arglist strings — REWRITE: feed the parser a SYNTHETIC fixture fn with a known arglist, or assert structural properties (3 arities recovered, none collapses to `"()"`/mangled — the L99/86/87 negatives are the real point).
- L45 `(defn ^:async transact!` — REWRITE (low): drop `^:async`; assert starts `"(defn"` + contains `transact!` + not `,,,`.

### Confirmed KEEP — do NOT touch

- Cache-prefix byte-stability across two renders in one run: `ctx_test.cljs:527/563/820`.
- Section appears/vanishes on data presence: `ctx_test.cljs:371-373/619/688`, `warn_test.cljs:419-427/599-627/435-444`.
- Decoupling contract `ctx_test.cljs:915-919/937` (`= ctx/system-text (effective-system-prompt …)` — compares the const, not a literal).
- `teachings_test.cljs` in full (executable-teaching harness; corpus derived from live render).
- `internal_boundary_test.cljs` in full (structural predicates).
- Schema/db round-trips + error-guidance naming APIs/keywords/env-vars (`with-agent`, `seon.schema/register!`, `ANTHROPIC_API_KEY`, `SEON_FS_LOCK`, `:seon.db/identity`, `:cache_control`). The `(?i)`-alternation regexes in `eval/repair_batch_test.cljs` are exemplary.
- `render_test.cljs`, `debug_test.cljs`, `render/chat_test.cljs`, `log_test.cljs`, `resume_replay_test.cljs` (topo-sort ordering is a real contract), `web/brand_test.cljs`.

## Part 2 — Slowness

Isolated run: **74s total = ~23s compile + ~51s run** (the cited ~160s was concurrent P4 suite stealing CPU). Per-ns deltas ≥2s:

| ns | ~time | why |
|----|------|-----|
| `index-core-test` | ~17s | N+1 introspection |
| `web.inspector-chips-test` | ~6s | per-test boot |
| `warn-test` | ~4s | ~17 deftests, each fresh boot + large seed-tx |
| `ctx`, `teachings`, `store.wire`, `eval.memory-safety`, `eval.record-eval-tee` | ~3s each | per-test conn boots |

No real sleeps/timers; no large gen counts in hot files. Cost = datahike conn boots + `index-core!` file-read introspection repeated per test.

- **#1 `index-core-test` (~17s):** `(client/index-core!)` does full runtime introspection over the whole build closure (file-read + paren-parse for 50+ fns), invoked once/twice in EVERY deftest (~15-20 identical re-indexes). FIX: compute once + share — `(def core-tx (delay (client/index-core!)))` or `use-fixtures :once`, then `@core-tx`. Pure ⇒ safe. **Est. ~12-14s saved.** Pairs with the Cluster-D rewrite (same file).
- **#2 per-test conn boots (warn/ctx/inspector-chips):** `with-seeded-db` boots conn + transacts large seed per deftest; almost all are pure readers. FIX: `use-fixtures :once` boot+seed ONE conn, deref its db value. Est. ~2-3s in warn alone.

Net achievable: 74s → ~58s (#1) → ~52s (#2).

## Part 3 — Wave plan (independent lanes, file-isolated)

- **Lane 1 — render-label de-glyph:** `ctx_test.cljs`, `agent_render_namespace_test.cljs`, `handlers/test_test.cljs` (shared `; namespace`→`(ns ` fix).
- **Lane 2 — warn prose+fixture:** `warn_test.cljs` (Cluster B/C rewrites + `:once` fixture).
- **Lane 3 — index-core speed+arglist:** `index_core_test.cljs` (memoize + synthetic fixtures).
- **Lane 4 — small prose:** `schema_test.cljs`, `agent/todo_test.cljs`, `web/inspector_chips_test.cljs`, ctx_test B/C leftovers.

Coupling: Lanes 1 + 4 both read the namespaces-section render AND both touch `ctx_test.cljs` — ONE owner for `ctx_test.cljs` (sequence its edits in a single lane) to avoid a merge touch-up.

Top actions by leverage: (1) memoize `index-core!`; (2) replace `; namespace X`→`(ns X` (14 assertions, 3 files); (3) `:once` seeded-conn fixture (warn, inspector-chips); (4) delete the 5 redundant prose lines; (5) rewrite warn `where`/`explain` pins to the embedded data; (6) de-glyph `todo_test:160` + `✓/✗/•`; (7) synthetic-fixture the arglist parser.
