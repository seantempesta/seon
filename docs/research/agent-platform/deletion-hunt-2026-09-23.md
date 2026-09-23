---
type: research
status: read-only census; no source, test or JVM touched
created: 2026-09-23
lane: deletion-hunt (Opus 5.5)
tags: [agent-platform, deletion, size, dependency-already-does-it, libraries]
---

# Deletion hunt — what src can lose, ranked by lines

Scope: the tracked `src/` tree (105 files, **84,942 lines**; `git ls-files src`) of
the working tree at HEAD `024a1e12c` (committed src identical to `dd75a5b4b`; the tree
carried other lanes' uncommitted edits in `src/seon/cluster/boot.clj` and
`src/seon/fn.clj`). Line spans are clj-kondo `:var-definitions` rows (`:row`..`:end-row`)
of that tree. `reference-code/datahike` is read at its checkout `684d3290`.
Scripts: `tmp/deletion-hunt/*.clj` (kondo EDN → bb joins); raw outputs beside them.

Also untracked and not counted: `src/seon/test.clj.orig` (1,880 lines, a stale merge
leftover of another lane). It inflates every `find src | xargs wc` count by 1,880 and
doubles kondo definitions (it is why a `>= 60` count reads 224 instead of 214); its
owner should delete it.

## Summary

1. **Plan-owned deletions not yet landed: ≈ 25,000 lines** (§3, rows S*). Every
   dependency-audit row except two (publication lock, oversight search) is still in
   the tree; every deep-review win (projection transport, shape family, call
   preparation, provider classifier, parser pre-checks, refusing `:report`) is undeleted.
2. **New rows found here: ≈ 4,000 lines** (rows N*), each replacing Seon code with a
   Datahike, Malli, clj-kondo, babashka or library function named with `file:line`.
3. **Probe-first rows worth ≈ 1,850 more** (edamame reader, fipp printer, provider
   transport, MCP projection, context tx fns), not summed.
4. Sum: 84,942 − 25,000 − 4,000 ≈ **55,900**; with the probe rows ≈ **54,000**. That
   meets README §5's 55,000 only if the probe rows land. It does **not** approach the
   owner's new 10,000 target; §7 prices what 10,000 would take, subsystem by subsystem.
5. Dead code is small: **743 lines in 53 vars** have no caller anywhere; **1,061 lines
   in 62 vars** are called only from tests. Zero-caller hunting is worth ≈ 2 % —
   the lines are in mechanisms that are *called*, not in orphans.
6. The largest mechanism family is Seon re-deciding what its dependency decides:
   read currency beside the fork's `q-with-evidence` dependency plan (671 lines),
   entity validation beside Malli `explain` (764), schema-update rules beside
   Datahike's `check-schema-update` (607), three projection constructors beside Malli's
   lazy registry (1,177), a hand hiccup serializer beside chassis/hiccup2 (533).
7. **Today's additions grew src.** `1625fb9bc` added a third projection constructor
   (+282 net src); `schema.clj` is 494 lines above the size A1 planned from. The two
   script additions (`script/seon/dev/citations.clj` 1,040; move-to-head +649) have
   ~100-line versions (§4).
8. Of the 209 functions over 60 lines (21,116 lines, §6): plan deletes 9,018 lines,
   a library does 2,705, Seon has 520 elsewhere, 8,873 genuinely needed.
9. Guard (§8): a 20-line `commit-msg` hook that prints a commit's net src lines and
   refuses a src-growing commit without a `Src-growth:` trailer. 50 ms, git plumbing only.
10. Two operations of this lane exceeded ten seconds (the two clj-kondo analyses,
    §9). Justification and the cheaper route are recorded there; the issue note is
    the orchestrator's to file (this lane owns one document).

## 1. Audit residue — what is still undeleted

`dependency-already-does-it-audit-2026-09-23.md` rows, checked by `rg -c` at this tree:

| audit row | still present | where |
|---|---|---|
| 1 projection stamp | yes | `db.clj:226-317` `connection-projection-state` … `resolve-database-value` (92 lines) *and* the memo `db.clj:1248-1495` — both mechanisms now coexist |
| 2 temporal re-merge | yes | `db.clj:2760` `database-view` |
| 3 encoded-operation stamp | yes | `schema/datahike.clj` (5 hits) |
| 5 index/replay arms | yes | `db.clj:981` `replay-read`, `:1107` `index-evidence-current` |
| 6 read-basis-transaction | yes | 25 hits: `turn.clj` 11, `render/transcript.clj` 6, three schema files |
| 7 packaged-population cache | yes | `schema/edn.clj:370` atom, `:387` forget hatch |
| 8 environment delay | yes | `env.clj` `declared-members` (4 hits) |
| 9 staleness by basis-t | yes | `env.clj` `advance-projection!` + 4 callers |
| 10 owning-instance search | **gone** | — |
| 11 publication lock | **gone** | — |
| 14 GC head re-read | yes | `maintenance.clj:622` `branch-reopens?` |
| 15 render walk entity cache | yes | `render/walk.clj` `acquire-entity` |
| 16 fabricated report | yes | `cluster/source.clj` `changed-identities` |
| 17 SHA-256 of gitlink | yes | `test/cache.clj:176` `gitlink-digests` |
| 19/20 shape family, ambient vars | yes | `schema.clj:1019-1023` four dynamic vars; `program.cljc:260` `!authored-shapes` |

Deep-review wins still present: ambient transport (`call-with-projection` 57 hits in 21
files, `handed-projection` 23), shape family (`schema.shape` 137 hits in 6 files),
call preparation (1,537 lines), `:seon.ai/error-class` (12 hits), parser pre-checks
(`query-call-valid?`, `pull-call-valid?`, `datoms-call-valid?`, `unknown-attribute-error`
present). The swallowed-errors census's F0 (no constructor carries the cause) still
stands; its nine hand chain readers are row N10.

## 2. Census method and raw numbers

- **(b) callers.** clj-kondo `{:analysis {:var-usages true :var-definitions {:shallow false}}}`
  over the 105 tracked src files, then over the 329 tracked test/script/dev/bin files for
  test usage. A var's src caller count excludes its own recursive calls. A zero-caller
  var is then text-searched (qualified name) across `src resources bin script dev
  deps.edn test`, because EDN render pairs, `[:db.fn/call sym]` transaction functions
  and SCI-exposed `my.*` Vars are called by data, not by code. `my.*`, records' `->X`
  constructors and `-main` are excluded.
  - 4,060 definitions, 81,386 lines inside definitions.
  - 447 have no src caller (8,022 lines); after the text search **53 are dead (743)**
    and **62 are test-only (1,061)**. 1,566 have one src caller (36,248 lines; 1,159 of
    them private, 23,913 lines) — single-use private helpers are the normal shape of
    Clojure decomposition and are not a deletion signal on their own.
- **(c) same name in ≥ 2 namespaces:** 167 names, 8,328 lines. Most are the
  declared render pairs (`render-ai`/`render-html` × 16 each — the law, not a
  duplicate). The real duplicates are rows N8–N12.
- **(d) hand mechanisms:** `defonce` state (40 sites), `locking` (19), `Thread/sleep` (2),
  `MessageDigest` (4 files), cause-chain readers (19 functions).

Dead vars (no caller in any tracked file; each needs one REPL `ns-publics` check before
deletion because a string-built `requiring-resolve` would not show): `fs.clj:89`
`admit-destructive-path!` 75 and `:182` `log-deletion!` 30 (both duplicate
`cluster/store.clj:243,267`), `schema.clj:2565` `maintain-projection-delta` 51,
`render/ns.clj:797,614,416` `budgeted-html`/`budgeted-ai`/`token-budget` 61,
`render/lint.clj:503` `check-render` 27, `cluster.clj:3584` `read-advertisement` 26,
`db.clj:399` `call-without-custody` 24, `schema/internal.cljc:498`
`assert-multi-segment-namespace!` 24, `call_preparation.clj:706` `watch!` 23,
`schema.clj:3944` `register-all!` 20, `render/walk.clj:619` `owning-namespace` 20,
`sci/admit.clj:622` `restorable-node` 17, `db.clj:3976` `deletion-error` 17,
`cluster/export.clj:316` `reidentify-branches!` 16, `test/arm.clj:239`
`initialize-contracts!` 16, `schedule.clj:102,118` `valid-cron?`/`valid-timezone?` 28,
`web/jvm.clj:210` `read-blob` 15, the old boot span `cluster.clj:239-834`
(`*boot-progress!*`, `boot-phase`, `server-name`, `reserve-cluster!`,
`release-reservation!`, `create-directories!`, `require-cluster-target!`) 52, and 30
smaller (full list: `bb tmp/deletion-hunt/dead.clj`).

Test-only vars with a mechanism behind them (delete the var and its test together):
`fn.clj:1724` `contract-findings` 142, `:2581` `plan-file-change` 108, `:2797`
`backfill-contract-facts!` 97, `:2206` `build-artifact` 50, `:2053`
`output-path-report` 37; `instrument.clj:1005` `restore!` 55; `cluster.clj:2683`
`publication-base!` 69; `fn/analyzer.clj:795` `analyze-forms` 63 (a second copy of
`fn.clj:1006`); `problems.clj:568` `log-report` 51; `context.clj:227` `compact-tx` 46
(verify: may be a `:db.fn/call` target).

## 3. The ranked list

"Lines" is the removal estimate: the measured definition span for DELETE rows, span
minus the replacement for REPLACE rows, the owning spec's own figure for plan rows
(marked *plan*). Callers are kondo call sites from other namespaces (src / test);
data references (EDN) are named where found. Risk is what could break.

| # | Seon code (span, lines) | duplicates | replacement | callers | lines | risk |
|---|---|---|---|---|---:|---|
| S5 | task family: `issue.clj` 1,378, `issue/detect.clj` 333, `issue/opening.clj` 236, `plan.clj` 1,597, `note.clj` 308, `my/{issue,plan,note}.clj` 261 | one entity with linked work facts (B3 §2) | `seon.task` 450 + `my.task` 80 | 10 src sites / 4 files; 205 test sites / 24 files | *plan* −3,222 | B3 conditional on start/done guarantees |
| S1 | `render/transcript.clj` 2,449 | `render/walk.clj` history (B2 c8) | pairs → `render/agent.clj`, message forms → `cluster/message.clj` (+212 moved) | 13 / 2 files; 114 test / 17 | *plan* −2,237 | cut 4 |
| S13 | A2 over `db.clj` + bridge + store + registry + blob | Datahike's parser, currency, codec (`:db.type/any`), GC | A2 §5 c1–c13 | — | *plan* −2,149 | c2 waits the comparator proof |
| S4 | `turn.clj` 5,604 → 3,700: `call-turn` `:4284` 348, `resume-turn` `:4824` 163, `generate-turn` `:5013` 122, `open-turn` `:4208` 62, delimiter repair, `system-turn` `:2055` 178 | one turn function (B2 c4), one read owner (c5) | `turn` + `provider-attempt` | 32 test sites on `turn` alone | *plan* −1,904 | cut 4, last |
| S14 | `bootstrap.clj` 882, `env.clj` 445, `effect.clj` 950, `config.clj` 798 | B3 targets 120 / 150 / 500 / 650 | B3 §8 | bootstrap 14 src / 4 files | *plan* −1,655 | bootstrap target conditional on B3 §6.4 |
| S3 | `error.clj` 2,334 + `error/refusal.clj` 141 | rendering duplicated per error; `Throwable->map` (`clojure/core_print.clj:473`) carries the cause | B3 c6: 550 + 300 moved to `seon.render.error` | 62 src sites / 18 files | *plan* −1,625 | F0 constructor lands first |
| S2 | `test.clj` 1,890 → 800, `test/runner.clj` 1,828 → 1,300 | `clojure.test/test-vars` (`clojure/test.clj:725`) and the agent lifecycle | B4 c2–c3, one `seon.test/run` | 19 src / 7 files; 205 test / 16 | *plan* −1,618 | 1.3d commits 4–5 |
| S9 | `fn.clj` 3,691 → 2,300 | manifest family, caller-less vars, second `analyze-forms` | B1 table | — | *plan* −1,384 (includes the 475 test-only lines of §2) | fn.clj held often |
| S6 | `render/web.clj` 3,807 → 2,500: debug surface 996 lines in 18 defs (`acquire-debug-data` `:1629` 146, `debug-response` `:3276` 118, …), packages/registration/retention (`render-step` `:2618` 139, `render-pass` `:2386` 115, `feed` `:2857` 101) | datastar SDK `->sse-response` (`datastar-clojure/…/http_kit2.clj:40`); walk's `neighborhood` | B2 c9 whole view per notification, c11 debug page | 12 test sites / 1 file | *plan* −1,307 | depth lane owns detail |
| S7 | `sci/eval.clj` 3,774 → 2,500: `acquire-program!` `:1866` 306, `regenerate-agent-context!` `:2224` 62, `installation-covers-program-change?` 48, doc block `:1585-1700` | SCI `fork` keeps the live env (`sci/core.cljc`) | B2 c1–c3 | — | *plan* −1,274 | M9 adoption edits the same file |
| S8 | `call_preparation.clj` 1,537 | `m/-function-info` (malli `core.cljc:2193`) gives arity min/max; shape rows | one function over the compiled contract (A1 win 5) | 7 src / 4 files; 71 test / 4 | *plan* −1,237 | fixed-arity precedence behaviour tests |
| S19 | publication: `cluster.clj:1637-2751` 975 in 24 defs (`populate-source!` 144, `development-source-refresh!` 146, `full-source-refresh!` 116, `development-namespaces` 92), `cluster/source.clj` 700 → 350 | B1 1.2b envelope dissolution; clj-reload's `reload` (`clj-reload/core.clj:334`) walks the same dependents | capture → compare → rows on a branch; `require :reload` per declaration | 11 src / 3; 44 test / 17 | *plan* −929 | hook publication paused until measured |
| S10 | `schema.clj` A1 items: classpath fallback `:1074-1256` 149, ambient `:1019-4398` 236, shape `:2030-4378` 224, sha, config assert | the value carries the projection | A1-3/4/12/13 | ambient: 78 src / 24 files; **616 test / 105 files** (scripted) | *plan* −856 | test conversion is mechanical — script it |
| S12 | `ai.clj` classifier: `disposition`, `caused-by?`, `retry-strategy` (`:495`, `:1103`, `:1160`) and the `:seon.ai/error-class` enum | a kind stamp (ruling D3) | one declared error per failure; failover reads "sent?" | 27 src / 8; 254 test / 20 | *plan* −740 | provider parsing fiddly; stream fold stays |
| N7+N9 | dead 743 + test-only 1,061 (§2), minus the 970 already inside plan rows | — | delete var + its test | 0 src | −834 | a string `requiring-resolve` caller; check `ns-publics` usage once |
| N4 | `schema.clj:2172-3375` three constructors, 1,177 lines in 28 defs: `build-projection` 213, `projection-from-rows` 221, `projection-with-declarations` 240 (added today, `1625fb9bc`), `maintain-projection-delta` 51 (dead), `projection-delta`, `compose-projection-data`, `materialize-projection` | Malli's registry is the incremental structure: `mr/lazy-registry` (`malli/registry.cljc:81`) compiles a key on first use, `mr/composite-registry` (`:54`) layers a changed set over a base; `-memoize` caches each schema's validator (`core.cljc:268`) | ONE constructor: `(assoc forms k new-form)` for changed declarations → reverse closure recomputed by `reverse-dependencies` → registry over the forms map; the incremental case is the same function with a smaller changed set | 13 src / 4 files; 111 test / 28 | −680 (after A1's shape share) | A1's acquisition probes; admission bound |
| S20 | `run.clj` 152, `sci/kernel.clj` 689 → 620, `cluster/agent.clj` 1,373 → 950 | B2 c12, c2, c6 | — | run: 6 src / 3 | *plan* −644 | — |
| S11 | `flow.clj` 1,317 → 700: launcher `:192-944` | core.async flow `futurize` (`flow/impl.clj:29-36`) on the workload executor | B2 c7, only on the admission proof | 16 src / 5; 136 test / 26 | *plan* −617 | owner ruled launcher kept until proof |
| N3 | `cluster.clj:1002-1635` schema accretion, 607 lines in 26 defs (`accretive-property-change?` 44 re-implements the rule it cites, `attribute-change-tx` 66, `schema-row-changes` 26, `accrete-schema-population!` 68, `transact-initialization!` 48) | Datahike refuses an invalid schema update itself: `check-schema-update` (`datahike/db/transaction.cljc:925`) calling `find-invalid-schema-updates` (`datahike/schema.cljc:257`); "No seam may act on a pre-read" | transact the declared attribute rows; translate Datahike's typed refusal once; keep only value retraction for dropped attributes (`invalid-value-retraction` 30) | 1 src / 1; 14 test / 5 | −450 | index backfill semantics (`:db/index` added monotonically) must hold — probe on a branch (1.3e rule) |
| N15 | `render/hiccup.clj` 533 (a hand StringBuilder hiccup serializer: escape, `raw`, void elements, tag shorthand `:258` 81, style maps) + `render/lint.clj:238` `balance` 66 | **chassis** (`dev.onionpancakes/chassis` 1.0.365, Clojars, maintained; `c/html`, `c/raw`, escaping by default, StringBuilder/Appendable) or **hiccup2** (`hiccup2.core/html`, weavejester, 2.0) | `->string` → `chassis.core/html`; keep a 20-line attribute normaliser (sorted keys, camelCase style) | `->string` 12 src; 101 test | −450 | byte-identical output for sorted attributes; probe 101 test expectations |
| S17 | `render/ns.clj` 936 → 500 (ladder `:416-837`) | B2 c10 | `render-ai` bounded once | — | *plan* −436 | — |
| S16 | `fn/schema_shape.clj` 467 | compiled registry answers structure | keep `typed-key-facts` (≈ 40) | 19 src / 4; 34 test / 3 | *plan* −427 | RESET batch 1 |
| S18 | `render.clj` 1,897 → 1,500: invocation cache `:701-889`, `*walk-context*` `:1719-1874` | per-value memo keyed by `:cache-context` | B2 c9 | — | *plan* −397 | — |
| N2 | `db.clj:2987-4153` write validator, 764 lines in 27 defs (`write-owned-values-error` `:3372` 218, `write-report-error` `:4058` 80, `write-attribute-error` 44, `rejected-value` 40, `write-render-target-error` 39, `write-entity-error` 37, `invalid-write` 35) | Malli `m/explain` (`malli/core.cljc:2659`) over the final owning value returns the path, schema and value of every problem; `malli.error/humanize` (`malli/error.cljc:374`) the message; Datahike's own `validate-val`/uniqueness refusals (`datahike/db/transaction.cljc:33`) | one `m/explain` per changed owning entity (report-scoped, A2 c6's rule) → the flat error from the one constructor; the owned-value walk (EAVT + AVET discovery) stays | 0 src (internal); 15 test / 6 | −374 (beyond A2 c6's −90) | refusal texts change; the *mission* refusals (arity gate, 343 lines `:3591-3992`) are KEPT |
| S21 | `instrument.clj` 1,099 → 930, `schema/admission.clj` 487 → 320 | Malli fork refusing `:report` (win 9); one file walker | A1 | — | *plan* −336 | — |
| N10 | 19 cause/frame readers, 273 lines: `error.clj:91,105,338`, `error/refusal.clj:74`, `ai.clj:1160`, `instrument.clj:107,141,531`, `test.clj:29,110`, `test/runner.clj:86,99`, `sci/eval.clj:2927`, `sci/reader.cljc:696`, `cluster/message.clj:37,48`, `cluster.clj:355,364`, `fs.clj:165` | `clojure.core/Throwable->map` (`clojure/core_print.clj:473`: `:via` with every link's type, message, data; `:trace`; `:cause`) and `clojure.main/ex-triage` (`clojure/main.clj:207`) for the phase-aware summary | the F0 constructor stores `Throwable->map`; readers read `:via` | ≈ 40 sites | −200 | shown-text changes; census F0 first |
| N11 | per-namespace error builders: `refuse!` × 14 (99 lines: `plan.clj:81`, `reconcile.cljc:48`, `issue.clj:532`, `config.clj:177`, `fs/jvm.clj:45`, `cluster/prompt.clj:154`, `turn.clj:303`, `cluster/boot.clj:288`, `cluster/registry.clj:81`, `cluster/store.clj:201`, `note.clj:149`, `cluster/source.clj:36`, `artifact.clj:13`, `cluster/export.clj:89`), `diagnostic` × 5 (95: `db.clj:177`, `error/refusal.clj:90`, `issue.clj:166`, `cluster/boot.clj:258`, `await.clj:28`), `error-value` × 4 (50) | `seon.error.refusal/diagnostic` (`error/refusal.clj:90`) — the 1.1 constructor | call it; each copy becomes its declared `:seon.error` key | 14 files | −180 | each copy's schema key must survive (1.3e refuses otherwise) |
| N17 | `profile.clj` 286 (C1, `ed62a3e06`): `cell`, `with-cell`, `reusable?`, `begin`, `explain`, `growth-line`, `summary`, `locking live` | `java.util.concurrent.atomic.LongAdder` per `(symbol, digest)` in a `ConcurrentHashMap`; the wrapper already runs per call | the wrapper does `(.add adder (- (System/nanoTime) t0))`; `summary` is one sort | 18 src / 4 | −166 | C1's attribution of child work |
| N19 | `db.clj:1248-1495` projection memo with three durable keys (revisions, commit, declaration content) + value tier, 224 lines | `clojure.core.cache.wrapped/lookup-or-miss` (core.cache 1.1.234 `wrapped.clj:38`) keyed by the `:cache-context` attribute revisions of `projection-attributes` — exactly `datahike/schema_cache.cljc:8-29` | one cache, one key; the stamp `:226-317` goes with audit row 1 | 90 src sites / 23 files (mostly `carried-projection`, unchanged signature) | −160 | speculative (in-transaction) values have no commit; keep the value tier only if a probe shows the miss |
| N1 | `db.clj:512-1209,2632` read evidence, 671 lines in 22 defs (`query-index-patterns` `:608` 114, `pull-index-patterns` 50, `stable-value` 46, `read-result-digest`, `append-query-evidence!` 43, `read-evidence` 53, `read-evidence-current?` 49) | the fork's `d/q-with-evidence` returns the query's **dependency plan** (`datahike/query.cljc:128`), `dependency-plan-attributes` (`:2942`) interprets it and `source-context-unchanged?` (`:2999`) answers currency; the fork's result cache is keyed by exactly those attributes (`:2420-2483`) | store the plan's attributes + the revisions; currency = the fork's predicate; delete the Seon pattern extraction and result digests | 20 src / 4; 74 test / 13 | −140 beyond A2 c1's −380 (whole family → ≈ 150) | pull reads need the same plan: probe `d/pull` evidence in the fork first |
| N6 | `maintenance.clj:553-936` GC wrapper, 369 lines in 16 defs (`collect-store!` 81, `root-verification` 48, `dry-run-store!` 46, `refuse-misspelled-options!` 33 + `documented-request-keys` 18) | `datahike.gc/gc-storage!` (`datahike/gc.cljc:83`) with `:datahike.gc/reachable-extension` (`:152`) for blob keys and its own permit (`:125-169`); misspelled options = Malli `[:map {:closed true}]` | one `gc-storage!` call + one report entity; closed map for the request | 2 src / 1; 21 test / 3 | −130 beyond A2 c9/c10 | dry-run parity; the root verification is owner-requested evidence — keep its row |
| N5 | `schema.clj:822-944` canonical fingerprint, 112 lines (`canonical-value-string` 40, `projection-fingerprint` 31, `portable-string-hash`, `sha-256`) | **hasch** (already on the classpath through Datahike, `org.replikativ/hasch` 0.4.100; `hasch.core/edn-hash`, order-independent for maps/sets) or `seon.id/digest` (`id.clj:40`) over sorted forms | one call | 23 src / 11; 11 test / 6 | −100 | a stored fingerprint changes value: RESET item |
| N13 | `fs/jvm.clj:732-857` glob, 117 lines | `babashka.fs/glob` (`babashka/fs/src/babashka/fs.cljc:419`; `:follow-links` defaults false, `:hidden`) | glob under the opened root, then the existing confinement check per path | 0 src (SCI surface `my.fs/glob`) | −80 | confinement must stay: filter results through `relative-segments` |
| N12 | duplicated leaf helpers: `sha-256` × 3 (`schema.clj:895`, `test/cache.clj:21`, `id.clj:17`), `strict-utf8`/`octet-values`/`empty-digest`/`io-buffer-bytes` in both `fs/jvm.clj` and `shell/jvm.clj`; identity-checked tree kill twice (`shell/jvm.clj:253-291` 35 vs `cluster/process.clj:125-162`) | `seon.id/sha-256`; `babashka.process/destroy-tree` (`babashka-process/src/babashka/process.cljc:165`) + one exact-identity force | one owner each | ≈ 12 sites | −70 | none |
| N14 | require-cycle delays: `db.clj:51-69` (9), `issue.clj:22-33` (6), `render.clj:46-51` (3), `error.clj:41` | habit 1 (fetch at call time) | move the called function below its caller or pass it as an argument | 21 | −30 | none |

**Probe-first rows (not summed).** Each names a library that plausibly does the job;
none was probed here.

| Seon code | library, maturity, exact function | potential | what the probe decides |
|---|---|---:|---|
| `sci/reader.cljc` 938 (`read-events` `:546` 93) | **edamame** (borkdude; vendored `reference-code/edamame`, the reader SCI itself uses): `edamame.core/parse-string-all` with `:location?`, `:end-location`, `:read-cond` | −500 | location/end-location fidelity for the reply reader's spans and its typed refusals |
| `print.cljc` 1,375 (`fit` `:1302` 66) | **fipp** (brandonbloom, 0.6.29; linear time, bounded space, data documents) | −600 | whether the elision grammar (bound, count, path, requery) can be emitted as fipp nodes |
| `ai.clj:1330` `send-request` 155 + HTTP/SSE plumbing | **langchain4j** streaming chat models (vendored as `reference-code/langchain4clj`, `streaming.clj`) or **hato** / **babashka http-client** over `java.net.http` | −400 | the llm-providers skill (`SKILL.md:92-100`) rules the builders "useful examples" and the loops duplicate — an owner decision |
| `cluster.clj:260-691` MCP projection 415 | `seon.render.value` / `seon.print` already project values | −200 | overlap with the value renderer |
| `context.clj:161-507` `append-tx`/`capture-tx`/`compact-tx`/`remove-tx` 154 | Datahike `:db.fn/call` bodies | −150 | whether they are live transaction functions |

## 4. Today's additions: the ~100-line version

| commit | added | the smaller version |
|---|---|---|
| `3a8f2e6d7` skill citation checker, `script/seon/dev/citations.clj` 1,040 + test 166 + hook 58 | its own Markdown block parser, a `git cat-file --batch` reader (`:492-551`), per-citation baselines and relocation (`:551-640`), a shell-word parser for `git commit` command lines (`:900-1014`), a second clj-kondo definition runner with its own cache (`:356-470`) beside `bin/seon-hook:355` and `script/seon/dev/clj_kondo.clj` | ≈ 100 lines: `rg -o` the `` `path:N` `` spans; for Clojure targets read clj-kondo `:var-definitions` once (the hook's runner) and check the named Var's `:row..:end-row` contains N; for other targets check the line exists and contains the named text. Drop baselines (git blame answers "what moved"), drop `--fix`, and run as a `pre-commit`/`commit-msg` hook over `git diff --cached --name-only` so no shell command is parsed. −900 script lines |
| `1625fb9bc` incremental projection, `schema.clj` +282 net | a third constructor `projection-with-declarations` 240 + `shape-projections-with` 57 + `fingerprint-with`, `set-changes`, `population-options` | row N4: one constructor over the forms map with Malli's lazy/composite registry; the incremental case is the same call with a smaller changed set. The shape parts leave with A1-13 |
| `ed62a3e06` C1 minimal, +602 src | `profile.clj` 274, `cluster.clj` +172 (`definition-digests` 21, `published-program` 23, `arm-host-program!` 33) | row N17 (LongAdder per identity, ≈ 120); the arming functions duplicate `instrument/apply!` over the same contracts — one arming entry |
| `ddd9f8edf` + `5b101a78a` move-to-head, `script/seon/operator.clj` +649 | 15 functions: archive, pins, cache sharing, prune, head capture/restore forms evaluated in the JVM | ≈ 200: `stop` + `start` already exist; the snapshot is `git archive` with caches linked (already `committed-source!`); head restore is Datahike's `force-branch!` with `:expected-current-commit` (`datahike/versioning.cljc:323-352`) in one prepl form. −450 script lines |
| `schema.clj` +996 today | see N4 and S10 | `schema.clj` must return to ≤ 3,050 (A1) — it is 4,398 |
| `maintenance.clj` 966 | row N6 | ≈ 450 |
| `cluster/boot.clj` 571 | B1b planned ≈ 420; `diagnostic` `:258` 29 duplicates `error/refusal/diagnostic` (N11); the old boot span in `cluster.clj` was left behind (52 dead lines, §2) | delete the remnant; 420 |

## 5. The largest files: share already ruled for deletion

| file | lines | plan target | ruled share | plus rows here |
|---|---:|---:|---:|---|
| `turn.clj` | 5,604 | 3,700 (B2) | 34 % | `gate-function-install` `:3205` 78 duplicates the D1 definition-time gate in `sci/eval.clj` |
| `db.clj` | 4,654 | A2 ≈ 5,150 over six files | ≈ 30 % | N1, N2, N19: −674 more; depth lane |
| `schema.clj` | 4,398 | 3,050 (A1, planned from 3,904) | 31 % | N4, N5: −780; depth lane |
| `render/web.clj` | 3,807 | 2,500 (B2) | 34 % | debug surface alone 996; depth lane |
| `sci/eval.clj` | 3,774 | 2,500 (B2) | 34 % | — |
| `fn.clj` | 3,691 | 2,300 (B1) | 38 % | 475 test-only lines inside it |
| `cluster.clj` | 3,609 | publication 1,279 → 700 (B1); boot span to `boot.clj` | ≈ 25 % | N3 −450; dead boot remnant 52 |

## 6. Functions over 60 lines (209 defs, 21,116 lines)

Strictly over 60 lines, tracked src only (214 at ≥ 60; 224 when the untracked `.orig`
is counted — the orchestrator's 223). Class totals: **plan deletes it 9,018; library
does it 2,705; Seon already has it elsewhere 520; genuinely needed 8,873.** "Genuinely
needed" means no dependency or Seon function was found that does the job; it is
not a claim the function is the right size (`sci.eval/evaluate` 407 is one entry to
split, not to delete). `db.clj`, `schema.clj` and `render/web.clj` rows are classed at
the level this broad pass can support; their depth lanes own the detail.

| # | function | lines | src/test callers | class | basis |
|---|---|---:|---|---|---|
| 1 | `seon.sci.eval/evaluate` (`src/seon/sci/eval.clj:3154`) | 407 | 4/100 | genuinely needed | the one evaluation entry; 407 lines is a split, not a deletion |
| 2 | `seon.turn/call-turn` (`src/seon/turn.clj:4284`) | 348 | 1/0 | plan deletes it | B2 c4: turn = one function |
| 3 | `seon.test/select` (`src/seon/test.clj:537`) | 347 | 1/5 | genuinely needed | B4 keeps the rules; drops duplicate snapshot construction |
| 4 | `seon.sci.eval/acquire-program!` (`src/seon/sci/eval.clj:1866`) | 306 | 1/0 | plan deletes it | B2 c1: program identity replaces database identity |
| 5 | `seon.test/run` (`src/seon/test.clj:1584`) | 269 | 3/10 | plan deletes it | B4 c2: one `seon.test/run`; `clojure.test/test-vars` (clojure/test.clj:725) runs the members |
| 6 | `seon.schema/projection-with-declarations` (`src/seon/schema.clj:2848`) | 240 | 4/0 | library does it | Malli registry: assoc changed forms, `mr/lazy-registry` (registry.cljc:81) compiles on demand; NEW row N4 (today's 3rd constructor) |
| 7 | `seon.schema/projection-from-rows` (`src/seon/schema.clj:3155`) | 221 | 1/0 | library does it | same one constructor; A1 already deletes it (acquisition test :38) |
| 8 | `seon.effect/request*` (`src/seon/effect.clj:719`) | 221 | 2/0 | plan deletes it | B3 effect 950 → 500 |
| 9 | `seon.db/write-owned-values-error` (`src/seon/db.clj:3372`) | 218 | 1/1 | library does it | Malli `m/explain` (malli core.cljc:2659) over the owning entity + `me/humanize` (error.cljc:374); NEW row N2 |
| 10 | `seon.schema/build-projection` (`src/seon/schema.clj:2172`) | 213 | 15/101 | library does it | same one constructor (NEW row N4) |
| 11 | `seon.test/admit-run` (`src/seon/test.clj:986`) | 191 | 1/11 | plan deletes it | B4 c2: one `seon.test/run`; `clojure.test/test-vars` (clojure/test.clj:725) runs the members |
| 12 | `seon.render/render-call` (`src/seon/render.clj:1412`) | 185 | 9/21 | plan deletes it | B2 c9 invocation cache (render.clj:701-889) consumer |
| 13 | `seon.fn/index!` (`src/seon/fn.clj:3501`) | 184 | 3/18 | genuinely needed | the indexer over clj-kondo analysis |
| 14 | `seon.turn/system-turn` (`src/seon/turn.clj:2055`) | 178 | 3/45 | plan deletes it | B2 c5: read currency has one owner |
| 15 | `seon.schema/assert-complete-contract!` (`src/seon/schema.clj:1526`) | 173 | 3/1 | genuinely needed | the complete-contract law; admission |
| 16 | `seon.issue/index-tx` (`src/seon/issue.clj:322`) | 172 | 3/10 | plan deletes it | B3: one `seon.task` family, 4,113 → 530 |
| 17 | `seon.sci.eval/install-row!` (`src/seon/sci/eval.clj:940`) | 169 | 2/13 | plan deletes it | 1.3d commit 1 rewrites it over `definition-digest` |
| 18 | `seon.turn/record-attempt!` (`src/seon/turn.clj:3967`) | 166 | 1/5 | genuinely needed | B2 keeps: turn semantics; cut 4 |
| 19 | `seon.turn/resume-turn` (`src/seon/turn.clj:4824`) | 163 | 3/1 | plan deletes it | B2 c4 |
| 20 | `seon.test.runner/record-latest-tx` (`src/seon/test/runner.clj:1398`) | 162 | 1/0 | plan deletes it | B4 c3: process machinery deleted, runner ≤ 1,300 |
| 21 | `seon.cluster.source/publish!` (`src/seon/cluster/source.clj:543`) | 158 | 2/5 | plan deletes it | B1 1.2b |
| 22 | `seon.error/prepare` (`src/seon/error.clj:515`) | 157 | 3/6 | plan deletes it | B3 c6: rendering moves, constructor keeps `Throwable->map` (core_print.clj:473) |
| 23 | `seon.ai/send-request` (`src/seon/ai.clj:1330`) | 155 | 1/7 | library does it | langchain4j streaming chat model transport (vendored langchain4clj streaming.clj) or hato; skill ruling keeps Seon's loop — owner decision |
| 24 | `seon.cluster/arm-agents!` (`src/seon/cluster.clj:3349`) | 154 | 1/0 | genuinely needed | boot layer; moves to boot.clj (B1b) |
| 25 | `seon.cluster.agent/acquire-context!` (`src/seon/cluster/agent.clj:783`) | 153 | 1/25 | plan deletes it | B2 c2: fork once |
| 26 | `seon.turn/step` (`src/seon/turn.clj:5453`) | 152 | 5/8 | genuinely needed | B2 keeps: turn semantics; cut 4 |
| 27 | `seon.plan/compile-tree` (`src/seon/plan.clj:1154`) | 149 | 1/0 | plan deletes it | B3: one `seon.task` family |
| 28 | `seon.cluster/development-source-refresh!` (`src/seon/cluster.clj:2470`) | 146 | 1/0 | plan deletes it | B1 1.2b publication envelope |
| 29 | `seon.render.web/acquire-debug-data` (`src/seon/render/web.clj:1629`) | 146 | 1/1 | plan deletes it | B2 c11 debug page |
| 30 | `seon.cluster/populate-source!` (`src/seon/cluster.clj:1637`) | 144 | 2/3 | plan deletes it | B1 1.2b |
| 31 | `seon.call-preparation/plan-for` (`src/seon/call_preparation.clj:992`) | 143 | 1/2 | plan deletes it | A1 win 5: one function over `m/-function-info`, 1,537 → 300 |
| 32 | `seon.fn/contract-findings` (`src/seon/fn.clj:1724`) | 142 | 0/2 | plan deletes it | 0 src callers (test-only); B1 caller-less vars |
| 33 | `seon.render.web/render-step` (`src/seon/render/web.clj:2618`) | 139 | 1/6 | plan deletes it | B2 c9: whole view per notification (render proc retention) |
| 34 | `seon.fn/var-row` (`src/seon/fn.clj:598`) | 139 | 1/0 | genuinely needed | indexer row |
| 35 | `seon.render.web/page-result` (`src/seon/render/web.clj:434`) | 138 | 1/1 | genuinely needed | page and SSE delivery; DEPTH LANE (web.clj) |
| 36 | `seon.cluster.agent/armer-step` (`src/seon/cluster/agent.clj:1240`) | 134 | 3/6 | genuinely needed | agent graph |
| 37 | `seon.test.runner/complete-members` (`src/seon/test/runner.clj:1267`) | 130 | 1/0 | plan deletes it | B4 c3: process machinery deleted, runner ≤ 1,300 |
| 38 | `seon.cluster.process/run-process!` (`src/seon/cluster/process.clj:163`) | 129 | 1/22 | genuinely needed | babashka.process has no silence bound (audit row 26 KEEP) |
| 39 | `seon.fn.analyzer/analyze` (`src/seon/fn/analyzer.clj:518`) | 124 | 3/33 | genuinely needed | the clj-kondo call |
| 40 | `seon.turn/row-tx` (`src/seon/turn.clj:1184`) | 124 | 1/10 | genuinely needed | B2 keeps: turn semantics; cut 4 |
| 41 | `seon.test/member-result` (`src/seon/test.clj:1410`) | 123 | 1/0 | plan deletes it | B4 c2: one `seon.test/run`; `clojure.test/test-vars` (clojure/test.clj:725) runs the members |
| 42 | `seon.db/transact-call` (`src/seon/db.clj:4326`) | 122 | 1/0 | genuinely needed | the one writer |
| 43 | `seon.turn/generate-turn` (`src/seon/turn.clj:5013`) | 122 | 1/2 | plan deletes it | B2 c4 |
| 44 | `seon.instrument/violation` (`src/seon/instrument.clj:272`) | 121 | 1/1 | library does it | Malli `m/explain` + `me/humanize` (error.cljc:374) |
| 45 | `seon.cluster.boot/request!` (`src/seon/cluster/boot.clj:444`) | 119 | 0/0 | genuinely needed | B1b request surface |
| 46 | `seon.render.web/debug-response` (`src/seon/render/web.clj:3276`) | 118 | 3/1 | plan deletes it | B2 c11 |
| 47 | `seon.sci.admit/open-node` (`src/seon/sci/admit.clj:207`) | 117 | 1/0 | genuinely needed | print admission |
| 48 | `seon.turn/evaluate-sources` (`src/seon/turn.clj:4668`) | 117 | 3/10 | genuinely needed | B2 keeps: turn semantics; cut 4 |
| 49 | `seon.cluster/full-source-refresh!` (`src/seon/cluster.clj:1978`) | 116 | 1/1 | plan deletes it | B1 1.2b |
| 50 | `seon.schema/pulled-attribute-entry` (`src/seon/schema.clj:3543`) | 116 | 1/0 | plan deletes it | A2 c5 pulled form |
| 51 | `seon.render.web/data-response` (`src/seon/render/web.clj:3508`) | 116 | 1/1 | genuinely needed | page and SSE delivery; DEPTH LANE (web.clj) |
| 52 | `seon.render.web/render-pass` (`src/seon/render/web.clj:2386`) | 115 | 1/6 | plan deletes it | B2 c9 |
| 53 | `seon.render.value/value-node*` (`src/seon/render/value.clj:328`) | 114 | 1/0 | genuinely needed | the one clipping spot |
| 54 | `seon.db/query-index-patterns` (`src/seon/db.clj:608`) | 114 | 1/0 | library does it | fork `d/q-with-evidence` dependency plan (DH query.cljc:128, :2942); NEW row N1 |
| 55 | `seon.render.transcript/render-session` (`src/seon/render/transcript.clj:1367`) | 114 | 2/3 | plan deletes it | B2 c8: file deleted, the walk is the history |
| 56 | `seon.schedule/fire-call` (`src/seon/schedule.clj:322`) | 113 | 1/1 | genuinely needed | B3 fire claim; cron is already cron-utils |
| 57 | `seon.turn/evaluation-terminal-data` (`src/seon/turn.clj:3451`) | 113 | 2/0 | genuinely needed | B2 keeps: turn semantics; cut 4 |
| 58 | `seon.error/commit-call` (`src/seon/error.clj:1365`) | 112 | 1/0 | plan deletes it | B3 c6: rendering moves, constructor keeps `Throwable->map` (core_print.clj:473) |
| 59 | `seon.schema/canonical-definition` (`src/seon/schema.clj:618`) | 111 | 2/5 | genuinely needed | definition identity |
| 60 | `seon.cluster.wake/route!` (`src/seon/cluster/wake.clj:438`) | 111 | 1/11 | genuinely needed | wake route |
| 61 | `seon.schema/validate-declarations!` (`src/seon/schema.clj:2061`) | 108 | 2/0 | genuinely needed | admission |
| 62 | `seon.fn/plan-file-change` (`src/seon/fn.clj:2581`) | 108 | 0/3 | plan deletes it | 0 src callers; B1 |
| 63 | `seon.instrument/apply!` (`src/seon/instrument.clj:839`) | 108 | 4/44 | library does it | `malli.instrument/instrument!`/`collect!`; A1 keeps the compiled-contract wrapper |
| 64 | `seon.cluster/mcp-project` (`src/seon/cluster.clj:404`) | 107 | 1/0 | genuinely needed | B1 MCP tool (probe for overlap with `seon.render.value`) |
| 65 | `seon.render/project-node*` (`src/seon/render.clj:1111`) | 105 | 3/0 | genuinely needed | value projection |
| 66 | `seon.program/digest-map` (`src/seon/program.cljc:447`) | 103 | 0/7 | Seon already has it elsewhere | `seon.id/digest` (id.clj:40) |
| 67 | `seon.cluster/commit-fault!` (`src/seon/cluster.clj:3120`) | 102 | 3/5 | plan deletes it | flow PRD N1–N4: one error route |
| 68 | `seon.render.web/feed` (`src/seon/render/web.clj:2857`) | 101 | 1/0 | plan deletes it | B2 c9: packages; datastar SDK `->sse-response` (http_kit2.clj:40) |
| 69 | `seon.cluster.boot/stand-boot-layers!` (`src/seon/cluster/boot.clj:156`) | 100 | 1/0 | genuinely needed | B1b |
| 70 | `seon.cluster.store/open-store!` (`src/seon/cluster/store.clj:417`) | 99 | 3/52 | genuinely needed | store owner |
| 71 | `seon.render.walk/neighborhood` (`src/seon/render/walk.clj:751`) | 99 | 3/20 | genuinely needed | walk |
| 72 | `seon.render.walk/acquisition-members` (`src/seon/render/walk.clj:252`) | 99 | 1/0 | genuinely needed | walk |
| 73 | `seon.fn/compile-index-transaction` (`src/seon/fn.clj:3032`) | 97 | 2/2 | genuinely needed | indexer |
| 74 | `seon.render.transcript/ledger-turn-body` (`src/seon/render/transcript.clj:1859`) | 97 | 2/0 | plan deletes it | B2 c8: file deleted, the walk is the history |
| 75 | `seon.render/walk` (`src/seon/render.clj:1801`) | 97 | 0/0 | genuinely needed | render pair (22 EDN refs) |
| 76 | `seon.fn/backfill-contract-facts!` (`src/seon/fn.clj:2797`) | 97 | 0/2 | plan deletes it | B1 names it |
| 77 | `seon.sci.kernel/invoke` (`src/seon/sci/kernel.clj:594`) | 96 | 2/25 | genuinely needed | SCI kernel |
| 78 | `seon.fn/published-index-rows` (`src/seon/fn.clj:3308`) | 96 | 5/3 | genuinely needed | indexer |
| 79 | `seon.issue/create-tx` (`src/seon/issue.clj:1038`) | 96 | 1/0 | plan deletes it | B3: one `seon.task` family, 4,113 → 530 |
| 80 | `seon.cluster.agent/arm!` (`src/seon/cluster/agent.clj:937`) | 94 | 1/23 | genuinely needed | agent graph |
| 81 | `seon.call-preparation/derive-snapshot` (`src/seon/call_preparation.clj:428`) | 94 | 2/0 | plan deletes it | A1 win 5: one function over `m/-function-info`, 1,537 → 300 |
| 82 | `seon.web.jvm/fetch` (`src/seon/web/jvm.clj:291`) | 93 | 0/0 | library does it | hato / babashka http-client + jsoup `Element.text()` for extraction |
| 83 | `seon.cluster.reply/sources` (`src/seon/cluster/reply.clj:301`) | 93 | 1/32 | genuinely needed | reply reader |
| 84 | `seon.reconcile/plan-transaction-data` (`src/seon/reconcile.cljc:322`) | 93 | 2/0 | library does it | editscript diff (vendored; db.clj already uses it) — probe |
| 85 | `seon.sci.reader/read-events` (`src/seon/sci/reader.cljc:546`) | 93 | 4/0 | library does it | edamame `parse-string-all` with `:location?` (vendored `reference-code/edamame`) — probe fidelity first |
| 86 | `seon.turn/evaluation-facts` (`src/seon/turn.clj:91`) | 92 | 2/1 | genuinely needed | B2 keeps: turn semantics; cut 4 |
| 87 | `seon.schema.edn/admit` (`src/seon/schema/edn.clj:568`) | 92 | 1/5 | genuinely needed | A1 keeps |
| 88 | `seon.cluster/development-namespaces` (`src/seon/cluster.clj:2203`) | 92 | 1/15 | library does it | clj-reload `reload` (clj-reload core.clj:334) — but the per-declaration rule (README 1.2b) is Seon's; keep the rule, drop the walk |
| 89 | `seon.web.jvm/search` (`src/seon/web/jvm.clj:393`) | 92 | 0/0 | library does it | same |
| 90 | `seon.schema.internal/assert-complete-schema!` (`src/seon/schema/internal.cljc:291`) | 91 | 1/0 | genuinely needed | admission |
| 91 | `seon.render.web/derive-page` (`src/seon/render/web.clj:2106`) | 90 | 1/0 | plan deletes it | B2 c9 invocation-cache consumer |
| 92 | `seon.schema/compilable-form` (`src/seon/schema.clj:264`) | 88 | 13/10 | genuinely needed | Malli form preparation |
| 93 | `seon.error/refusal-data` (`src/seon/error.clj:974`) | 87 | 1/0 | plan deletes it | B3 c6: rendering moves, constructor keeps `Throwable->map` (core_print.clj:473) |
| 94 | `seon.turn/render-ai` (`src/seon/turn.clj:1734`) | 86 | 4/2 | genuinely needed | B2 keeps: turn semantics; cut 4 |
| 95 | `seon.fn/assert-capability-contracts!` (`src/seon/fn.clj:2119`) | 86 | 1/0 | genuinely needed | contract law |
| 96 | `seon.test.runner/restore-live-cluster-schema!` (`src/seon/test/runner.clj:500`) | 86 | 1/4 | plan deletes it | B4: stays until A1 proves no mutable registry |
| 97 | `seon.render.web/render-source-call` (`src/seon/render/web.clj:1437`) | 85 | 2/1 | plan deletes it | B2 c9 consumer (web.clj:1409-1576) |
| 98 | `seon.sci.eval/build-base-ctx` (`src/seon/sci/eval.clj:188`) | 85 | 3/40 | genuinely needed | B2 keeps: SCI evaluation seam |
| 99 | `seon.config/admit-initialization-rows` (`src/seon/config.clj:257`) | 83 | 1/0 | plan deletes it | B3 |
| 100 | `seon.sci.eval/declared-row` (`src/seon/sci/eval.clj:2785`) | 82 | 1/1 | genuinely needed | B2 keeps: SCI evaluation seam |
| 101 | `seon.context/comparison` (`src/seon/context.clj:302`) | 82 | 0/4 | plan deletes it | B3 surviving remainder |
| 102 | `seon.fn/reconcile-tx-in` (`src/seon/fn.clj:3185`) | 81 | 3/1 | genuinely needed | indexer |
| 103 | `seon.cluster.boot/stand-cluster-runtime!` (`src/seon/cluster/boot.clj:48`) | 81 | 1/0 | genuinely needed | B1b |
| 104 | `seon.sci.eval/host-namespace!` (`src/seon/sci/eval.clj:1316`) | 81 | 1/0 | plan deletes it | B2 c3 doc/dir as data |
| 105 | `seon.maintenance/collect-store!` (`src/seon/maintenance.clj:803`) | 81 | 1/0 | library does it | Datahike `gc-storage!` (DH gc.cljc:83) with `:datahike.gc/reachable-extension` (:152); NEW row N6 |
| 106 | `seon.render.hiccup/shorthand` (`src/seon/render/hiccup.clj:258`) | 81 | 2/4 | library does it | chassis (`dev.onionpancakes/chassis` 1.0.365) or hiccup2 own tag shorthand, escaping, raw, void elements |
| 107 | `seon.call-preparation/prepare` (`src/seon/call_preparation.clj:1404`) | 81 | 1/3 | plan deletes it | A1 win 5: one function over `m/-function-info`, 1,537 → 300 |
| 108 | `seon.bootstrap/next-entry-in` (`src/seon/bootstrap.clj:650`) | 80 | 1/0 | plan deletes it | B3: 882 → 120 |
| 109 | `seon.repl/entity-emission` (`src/seon/repl.clj:366`) | 80 | 4/11 | genuinely needed | REPL reply |
| 110 | `seon.db/write-report-error` (`src/seon/db.clj:4058`) | 80 | 1/0 | library does it | same: Malli explain over the report's owning values; NEW row N2 |
| 111 | `seon.render.web/debug-page-result` (`src/seon/render/web.clj:1776`) | 80 | 1/0 | plan deletes it | B2 c11 |
| 112 | `seon.program/declaration-row` (`src/seon/program.cljc:1132`) | 79 | 7/20 | genuinely needed | program row |
| 113 | `seon.cluster/disarm-agents!` (`src/seon/cluster.clj:3504`) | 79 | 1/0 | genuinely needed | boot layer |
| 114 | `seon.turn/record-evaluated-call` (`src/seon/turn.clj:1424`) | 78 | 1/0 | genuinely needed | B2 keeps: turn semantics; cut 4 |
| 115 | `seon.render.web/debug-found-value` (`src/seon/render/web.clj:1289`) | 78 | 2/1 | plan deletes it | B2 c11 |
| 116 | `seon.ai/parsed-completion` (`src/seon/ai.clj:893`) | 78 | 2/0 | genuinely needed | stream fold kept (win 6) |
| 117 | `seon.test/bounded-result` (`src/seon/test.clj:132`) | 78 | 1/1 | genuinely needed | B4 keeps the interrupt seam |
| 118 | `seon.db/report-arity-comparison` (`src/seon/db.clj:3684`) | 78 | 1/0 | genuinely needed | the graph's write refusal (mission); its hand base cache → Datahike `:cache-context` memo |
| 119 | `seon.turn/receipt-settle-call` (`src/seon/turn.clj:1562`) | 78 | 5/4 | genuinely needed | B2 keeps: turn semantics; cut 4 |
| 120 | `seon.turn/next-agent-work` (`src/seon/turn.clj:2833`) | 78 | 4/86 | genuinely needed | B2 keeps: turn semantics; cut 4 |
| 121 | `seon.cluster/ensure-cluster-entity!` (`src/seon/cluster.clj:2833`) | 78 | 1/13 | genuinely needed | boot layer |
| 122 | `seon.turn/gate-function-install` (`src/seon/turn.clj:3205`) | 78 | 1/0 | Seon already has it elsewhere | the definition-time gate `sci/eval.clj` `evaluate-for-install`/`install-row!` also runs (D1 §refs keeps one) |
| 123 | `seon.render.transcript/render-outline` (`src/seon/render/transcript.clj:1633`) | 76 | 1/4 | plan deletes it | B2 c8: file deleted, the walk is the history |
| 124 | `seon.call-preparation/supply` (`src/seon/call_preparation.clj:1240`) | 76 | 1/3 | plan deletes it | A1 win 5: one function over `m/-function-info`, 1,537 → 300 |
| 125 | `seon.agent/render-settings-html` (`src/seon/agent.clj:170`) | 76 | 0/3 | genuinely needed | render pair |
| 126 | `seon.fn.analyzer/stale-cache-entries` (`src/seon/fn/analyzer.clj:244`) | 76 | 1/0 | library does it | clj-kondo's own cache (B1: sweep and second run −63) |
| 127 | `seon.cluster/serve!` (`src/seon/cluster.clj:3019`) | 75 | 1/4 | genuinely needed | boot layer |
| 128 | `seon.flow/fault-committer-step` (`src/seon/flow.clj:1005`) | 75 | 1/0 | genuinely needed | flow PRD: the one error route keeps a committer |
| 129 | `seon.flow/work-launcher-step` (`src/seon/flow.clj:509`) | 75 | 1/0 | plan deletes it | B2 c7: launcher retired, `futurize` on the workload executor (core.async flow/impl.clj:29) |
| 130 | `seon.fs/admit-destructive-path!` (`src/seon/fs.clj:89`) | 75 | 0/0 | Seon already has it elsewhere | `seon.cluster.store/admit-destructive-path!` (store.clj:243); 0 callers |
| 131 | `seon.test.arm/arm-contracts!` (`src/seon/test/arm.clj:163`) | 75 | 1/1 | Seon already has it elsewhere | `seon.instrument/apply!` arms the same contracts (B4: arm.clj copy) |
| 132 | `seon.turn/turn` (`src/seon/turn.clj:5136`) | 74 | 1/32 | plan deletes it | B2 c4 (survivor absorbs the others) |
| 133 | `seon.render.web/debug-header-html` (`src/seon/render/web.clj:846`) | 74 | 1/0 | plan deletes it | B2 c11 |
| 134 | `seon.render.web/derive-context!` (`src/seon/render/web.clj:2543`) | 74 | 0/2 | plan deletes it | B2 c9; 0 src callers |
| 135 | `seon.flow/start-error-fanout!` (`src/seon/flow.clj:1191`) | 74 | 1/5 | plan deletes it | flow PRD N1–N4 replaces the fan-out |
| 136 | `seon.shell.jvm/execute` (`src/seon/shell/jvm.clj:357`) | 73 | 1/0 | genuinely needed | bounded shell (babashka.process underneath) |
| 137 | `seon.cluster.agent/submit-source-in-projection` (`src/seon/cluster/agent.clj:627`) | 73 | 2/0 | genuinely needed | agent graph |
| 138 | `seon.instrument/wrap-interpreted` (`src/seon/instrument.clj:447`) | 73 | 1/17 | genuinely needed | SCI-side wrapper |
| 139 | `seon.fs/delete-recursively-impl!` (`src/seon/fs.clj:274`) | 72 | 2/0 | library does it | babashka.fs `delete-tree` (no-follow) |
| 140 | `seon.cluster.source/record-results-at-head!` (`src/seon/cluster/source.clj:430`) | 72 | 1/0 | plan deletes it | B1/B4 |
| 141 | `seon.sci.eval/install-evaluated-rows!` (`src/seon/sci/eval.clj:1203`) | 72 | 1/8 | genuinely needed | B2 keeps: SCI evaluation seam |
| 142 | `seon.error/recording` (`src/seon/error.clj:1478`) | 72 | 7/22 | genuinely needed | the error fact writer |
| 143 | `seon.render.web/debug-render-experiment` (`src/seon/render/web.clj:1523`) | 71 | 1/0 | plan deletes it | B2 c11 |
| 144 | `seon.fn/add-contract-facts` (`src/seon/fn.clj:2725`) | 71 | 1/2 | genuinely needed | contract facts |
| 145 | `seon.schema.internal/assert-error-declaration!` (`src/seon/schema/internal.cljc:219`) | 71 | 2/0 | genuinely needed | admission |
| 146 | `seon.turn/settle!` (`src/seon/turn.clj:3785`) | 71 | 8/5 | plan deletes it | B3 task settlement replaces it |
| 147 | `seon.schema.admission/house-findings` (`src/seon/schema/admission.clj:256`) | 70 | 1/0 | plan deletes it | A1: file walker −88 |
| 148 | `seon.flow/execute-work!` (`src/seon/flow.clj:344`) | 70 | 1/0 | plan deletes it | B2 c7: launcher retired, `futurize` on the workload executor (core.async flow/impl.clj:29) |
| 149 | `seon.test.accretion/auto-check` (`src/seon/test/accretion.clj:159`) | 70 | 2/0 | genuinely needed | accretion check |
| 150 | `seon.issue/status` (`src/seon/issue.clj:744`) | 70 | 5/2 | plan deletes it | B3 |
| 151 | `seon.flow/submit!!` (`src/seon/flow.clj:836`) | 69 | 1/7 | plan deletes it | B2 c7: launcher retired, `futurize` on the workload executor (core.async flow/impl.clj:29) |
| 152 | `seon.cluster/publication-base!` (`src/seon/cluster.clj:2683`) | 69 | 0/1 | plan deletes it | B1: 0 src callers |
| 153 | `seon.config/apply-compiled!` (`src/seon/config.clj:655`) | 69 | 2/5 | genuinely needed | config apply |
| 154 | `my.program/breaks` (`src/my/program.clj:279`) | 68 | 3/6 | genuinely needed | agent surface |
| 155 | `seon.cluster/accrete-schema-population!` (`src/seon/cluster.clj:1568`) | 68 | 2/3 | library does it | same; NEW row N3 |
| 156 | `seon.sci.eval/fork-cluster-ctx` (`src/seon/sci/eval.clj:2716`) | 68 | 2/8 | genuinely needed | B2 keeps: SCI evaluation seam |
| 157 | `seon.render.web/start!` (`src/seon/render/web.clj:3730`) | 68 | 1/4 | library does it | http-kit `run-server` + datastar SDK; keep a thin start |
| 158 | `seon.turn/opening-deferred?` (`src/seon/turn.clj:2699`) | 67 | 2/0 | genuinely needed | B2 keeps: turn semantics; cut 4 |
| 159 | `seon.ai/stream-event` (`src/seon/ai.clj:780`) | 67 | 1/0 | genuinely needed | stream fold kept (win 6) |
| 160 | `seon.turn/refusal-terminal-data` (`src/seon/turn.clj:3651`) | 67 | 2/0 | genuinely needed | B2 keeps: turn semantics; cut 4 |
| 161 | `seon.cluster.message/render-html` (`src/seon/cluster/message.clj:349`) | 67 | 2/4 | genuinely needed | render pair |
| 162 | `seon.call-preparation/coherent-supplier` (`src/seon/call_preparation.clj:220`) | 67 | 1/0 | plan deletes it | A1 win 5: one function over `m/-function-info`, 1,537 → 300 |
| 163 | `seon.test.runner/reusable-result` (`src/seon/test/runner.clj:1593`) | 67 | 0/2 | plan deletes it | B4 c3: process machinery deleted, runner ≤ 1,300 |
| 164 | `seon.test.runner/prepare-failures!` (`src/seon/test/runner.clj:1161`) | 67 | 1/0 | plan deletes it | B4 c3: process machinery deleted, runner ≤ 1,300 |
| 165 | `seon.db/carried-projection` (`src/seon/db.clj:1430`) | 66 | 79/124 | plan deletes it | audit row 1 / A1 memo: one `cw/lookup-or-miss` |
| 166 | `seon.problems/html-report` (`src/seon/problems.clj:463`) | 66 | 1/7 | plan deletes it | B3 routed-problem block |
| 167 | `seon.cluster/attribute-change-tx` (`src/seon/cluster.clj:1193`) | 66 | 1/1 | library does it | Datahike `check-schema-update` (DH db/transaction.cljc:925) + `find-invalid-schema-updates` (schema.cljc:257); NEW row N3 |
| 168 | `seon.render.lint/balance` (`src/seon/render/lint.clj:238`) | 66 | 1/2 | library does it | jsoup parse of the emitted HTML (`Jsoup/parse`), or chassis never emits unbalanced output |
| 169 | `seon.fn/analysis-rows-by-file` (`src/seon/fn.clj:1220`) | 66 | 3/1 | genuinely needed | indexer |
| 170 | `seon.render.walk/ordered-episode` (`src/seon/render/walk.clj:897`) | 66 | 1/9 | genuinely needed | walk |
| 171 | `seon.schema/unresolved-reference-refusal` (`src/seon/schema.clj:469`) | 66 | 1/0 | genuinely needed | typed refusal |
| 172 | `seon.shell.jvm/copy-blob-stdin!` (`src/seon/shell/jvm.clj:160`) | 66 | 1/0 | library does it | babashka.process `:in` accepts an InputStream (process.cljc:367) |
| 173 | `seon.print/fit` (`src/seon/print.cljc:1302`) | 66 | 1/5 | library does it | fipp (Wadler-style, bounded width) — elision grammar is Seon's; probe |
| 174 | `seon.schema/register!` (`src/seon/schema.clj:1731`) | 66 | 1/22 | plan deletes it | A1-12 ambient transport |
| 175 | `seon.effect/settle-call` (`src/seon/effect.clj:302`) | 65 | 1/0 | plan deletes it | B3 settlement |
| 176 | `seon.test.runner/derive-program-digest` (`src/seon/test/runner.clj:932`) | 65 | 2/0 | plan deletes it | B4 c3: process machinery deleted, runner ≤ 1,300 |
| 177 | `seon.bootstrap/situation` (`src/seon/bootstrap.clj:103`) | 65 | 2/3 | plan deletes it | B3: 882 → 120 |
| 178 | `seon.cluster/refresh-source!` (`src/seon/cluster.clj:2617`) | 65 | 5/11 | plan deletes it | 1.3d commit 3 splits it |
| 179 | `seon.fn/analyze-forms` (`src/seon/fn.clj:1006`) | 65 | 3/7 | Seon already has it elsewhere | `seon.fn.analyzer/analyze-forms` (analyzer.clj:795) — two copies |
| 180 | `seon.turn/settle-batch-refusal!` (`src/seon/turn.clj:3719`) | 65 | 1/0 | plan deletes it | B3 settlement |
| 181 | `seon.blob/stage-binary!` (`src/seon/blob.clj:236`) | 65 | 4/0 | genuinely needed | blob owner |
| 182 | `seon.test/resolve-test` (`src/seon/test.clj:1232`) | 65 | 1/7 | genuinely needed | B4 keeps |
| 183 | `seon.plan/add-step-call` (`src/seon/plan.clj:577`) | 65 | 1/0 | plan deletes it | B3: one `seon.task` family |
| 184 | `seon.db/transact!` (`src/seon/db.clj:4590`) | 65 | 66/386 | genuinely needed | the one writer |
| 185 | `seon.flow/submit!` (`src/seon/flow.clj:771`) | 64 | 1/9 | plan deletes it | B2 c7: launcher retired, `futurize` on the workload executor (core.async flow/impl.clj:29) |
| 186 | `seon.print/node-generator` (`src/seon/print.cljc:127`) | 64 | 0/1 | genuinely needed | test generator |
| 187 | `seon.fn/source-output-paths` (`src/seon/fn.clj:1988`) | 64 | 1/0 | plan deletes it | B1 manifest family |
| 188 | `seon.sci.eval/record-acquisition-refusals!` (`src/seon/sci/eval.clj:1801`) | 64 | 3/0 | plan deletes it | B2 c1 |
| 189 | `seon.edit/form` (`src/seon/edit.clj:324`) | 64 | 1/7 | genuinely needed | already rewrite-clj |
| 190 | `seon.error/log-line` (`src/seon/error.clj:1219`) | 63 | 1/2 | plan deletes it | B3 c6 |
| 191 | `seon.schema.datahike/compiled-storage` (`src/seon/schema/datahike.clj:77`) | 63 | 2/0 | plan deletes it | A2 c2 codec |
| 192 | `seon.turn/plan-call` (`src/seon/turn.clj:611`) | 63 | 2/1 | genuinely needed | B2 keeps: turn semantics; cut 4 |
| 193 | `seon.fn.analyzer/analyze-forms` (`src/seon/fn/analyzer.clj:795`) | 63 | 0/2 | Seon already has it elsewhere | duplicate of `seon.fn/analyze-forms` (fn.clj:1006); test-only |
| 194 | `seon.issue/start-tx` (`src/seon/issue.clj:1135`) | 63 | 1/0 | plan deletes it | B3: one `seon.task` family, 4,113 → 530 |
| 195 | `seon.program/contract-facts` (`src/seon/program.cljc:967`) | 63 | 3/2 | genuinely needed | contract facts |
| 196 | `seon.schema.admission/admit` (`src/seon/schema/admission.clj:419`) | 62 | 1/17 | genuinely needed | A1 keeps |
| 197 | `seon.turn/settle-batch!` (`src/seon/turn.clj:3567`) | 62 | 1/0 | plan deletes it | B3 settlement |
| 198 | `seon.sci.eval/regenerate-agent-context!` (`src/seon/sci/eval.clj:2224`) | 62 | 2/1 | plan deletes it | B2 c2: fork once, keep it |
| 199 | `seon.instrument/arm-var!` (`src/seon/instrument.clj:747`) | 62 | 2/5 | library does it | Malli `-instrument` (core.cljc:3118); win 9 refusing `:report` |
| 200 | `seon.fn/analyzed-form` (`src/seon/fn.clj:943`) | 62 | 1/0 | genuinely needed | indexer |
| 201 | `seon.turn/open-turn` (`src/seon/turn.clj:4208`) | 62 | 1/1 | plan deletes it | B2 c4 |
| 202 | `seon.db/replay-read` (`src/seon/db.clj:981`) | 62 | 1/3 | plan deletes it | A2 c1 |
| 203 | `seon.schedule/schedule-step` (`src/seon/schedule.clj:773`) | 61 | 1/0 | genuinely needed | schedule proc |
| 204 | `seon.cluster.message/delivery` (`src/seon/cluster/message.clj:179`) | 61 | 3/14 | Seon already has it elsewhere | `seon.cluster.wake/delivery` (wake.clj:326) — same name, same job |
| 205 | `seon.edit/exact` (`src/seon/edit.clj:410`) | 61 | 1/4 | genuinely needed | already rewrite-clj |
| 206 | `seon.fs.jvm/write` (`src/seon/fs/jvm.clj:670`) | 61 | 1/0 | genuinely needed | atomic precondition write (D1 keeps) |
| 207 | `seon.eval.drive/run-episode!` (`src/seon/eval/drive.clj:307`) | 61 | 2/0 | genuinely needed | eval driver |
| 208 | `seon.render/raw-output` (`src/seon/render.clj:1237`) | 61 | 3/0 | genuinely needed | renderer |
| 209 | `seon.cluster.status/agents` (`src/seon/cluster/status.clj:103`) | 61 | 1/2 | genuinely needed | status |

### Files over 800 lines (31)

| file | lines | target | class | the dependency that carries the rest |
|---|---:|---|---|---|
| `src/seon/ai.clj` | 1540 | 800 | plan + probe | langchain4j transport (owner decision) |
| `src/seon/bootstrap.clj` | 882 | 120 | plan (B3) | — |
| `src/seon/call_preparation.clj` | 1537 | 300 | plan (A1 w5) | `m/-function-info` |
| `src/seon/cluster.clj` | 3609 | ≈ 2,000 after B1/B1b | plan + library | Datahike `check-schema-update` (N3); clj-reload walk |
| `src/seon/cluster/agent.clj` | 1373 | 950 | plan (B2 c2/c6) | flow in-ports |
| `src/seon/db.clj` | 4654 | A2 ≈ 5,150 (6 files) | library + plan | fork `q-with-evidence` plan, Malli `explain`, Datahike parser; depth lane |
| `src/seon/effect.clj` | 950 | 500 | plan (B3) | — |
| `src/seon/error.clj` | 2334 | 550 (+300 moved) | plan + library | `Throwable->map`, `clojure.main/ex-triage`, `me/humanize` |
| `src/seon/flow.clj` | 1317 | 700 | plan (B2 c7) | core.async flow `futurize` |
| `src/seon/fn.clj` | 3691 | 2,300 | plan (B1) | clj-kondo analysis is already the dependency |
| `src/seon/fn/analyzer.clj` | 857 | 620 | plan (B1) | clj-kondo cache |
| `src/seon/fs/jvm.clj` | 898 | ≈ 800 | library (N13) | `babashka.fs/glob` |
| `src/seon/instrument.clj` | 1099 | 930 | plan + library | `malli.instrument`, fork `:refuse` |
| `src/seon/issue.clj` | 1378 | → seon.task | plan (B3) | — |
| `src/seon/maintenance.clj` | 966 | ≈ 450 | library (N6) | `datahike.gc/gc-storage!` |
| `src/seon/plan.clj` | 1597 | → seon.task | plan (B3) | — |
| `src/seon/print.cljc` | 1375 | kept (deep review) | probe | fipp documents |
| `src/seon/program.cljc` | 1331 | 1,050 | plan (B1) | `seon.id/digest` for `digest-map` |
| `src/seon/render.clj` | 1897 | 1,500 | plan (B2 c9) | — |
| `src/seon/render/ns.clj` | 936 | 500 | plan (B2 c10) | — |
| `src/seon/render/transcript.clj` | 2449 | 0 | plan (B2 c8) | the walk |
| `src/seon/render/walk.clj` | 1018 | 950 | plan (B2) | `cw/lookup-or-miss` for `acquire-entity` |
| `src/seon/render/web.clj` | 3807 | 2,500 | plan + library | datastar SDK `->sse-response`, http-kit; depth lane |
| `src/seon/schedule.clj` | 833 | kept | needed | already cron-utils + core.async flow |
| `src/seon/schema.clj` | 4398 | 3,050 | library + plan | Malli lazy/composite registry; depth lane |
| `src/seon/sci/admit.clj` | 835 | 900 | needed | — |
| `src/seon/sci/eval.clj` | 3774 | 2,500 | plan (B2 c1–c3) | SCI `fork`, `copy-var` |
| `src/seon/sci/reader.cljc` | 938 | 944 (B2) | probe | edamame `parse-string-all` |
| `src/seon/test.clj` | 1890 | 800 | plan (B4) | `clojure.test/test-vars` |
| `src/seon/test/runner.clj` | 1828 | 1,300 | plan (B4) | `clojure.test` capture; kaocha (vendored) was the rejected alternative |
| `src/seon/turn.clj` | 5604 | 3,700 | plan (B2 c4–c5) | one turn function over flow; nothing to import |

## 7. Toward 10,000 lines: what it would take

Owner, 2026-09-23: "I actually want this down to 10k or below". The rows above reach
≈ 54,000–56,000. The tree by subsystem (`bb tmp/deletion-hunt/subsys.clj`) and the
smallest shape each could take if the named library carries the rest:

| subsystem | lines now | 10k-world size | carried by |
|---|---:|---:|---|
| render, web, print (`render*`, `print.cljc`, `repl.clj`) | 14,220 | 2,000 | chassis or hiccup2 (HTML), datastar SDK + http-kit (SSE), fipp (printing); the walk as the one history |
| turn and agent runtime (`turn`, `cluster/{agent,wake,reply,message,prompt}`, `run`, `context`, …) | 10,955 | 2,000 | core.async flow procs; one turn function |
| schema, projection, contracts (`schema*`, `call_preparation`, `instrument`, `program`) | 11,211 | 1,000 | Malli registry, `malli.instrument`, Datahike schema |
| cluster, boot, config, effect, schedule, flow | 10,779 | 1,000 | Datahike branches, babashka.process, cron-utils (already), flow |
| database owner and storage (`db`, `blob`, store, registry, export, maintenance) | 7,523 | 1,000 | Datahike API through one custody function; `gc-storage!`; Malli `explain` |
| SCI evaluation (`sci/*`) | 6,236 | 1,000 | SCI `fork`; edamame |
| indexer and publication (`fn`, analyzer, source, reconcile) | 5,702 | 800 | clj-kondo analysis → rows; `require :reload` |
| tests (`test*`) | 4,646 | 300 | `clojure.test/test-vars` on a branch |
| tasks (issue, plan, note, `my.*`) | 4,113 | 530 | B3 `seon.task` |
| fs, shell, web, edit, artifact, id, profile | 3,482 | 600 | babashka.fs, babashka.process, hato, jsoup, rewrite-clj (already) |
| errors and problems | 3,093 | 300 | `Throwable->map`, `malli.error/humanize` |
| ai provider | 1,786 | 400 | langchain4j transport or hato |
| `my.*` surface | 1,291 | 300 | thin functions over the same writers |
| **total** | **84,942** | **≈ 11,200** | |

This sketch is not a plan. It prices what 10,000 lines means: every hand-built
guarantee the plan keeps would have to come from a library or be given up. That
includes typed refusals naming every member, read currency per evaluation, the
owned-value write validator, and the elision grammar. README §5 already says a
tenfold result "needs a second dissolution pass over the surviving mechanisms".
The owner has to rule which guarantees the 10,000-line system keeps, because each
row above needs that ruling before a lane can start.

## 8. The top 20 as launchable slices

Ordered by lines. File collisions matter more than order: rows 9–11 and 18 all edit
`db.clj`, and rows 4 and 6 both edit `schema.clj`. Each group is ONE lane, run in
sequence (the one-file-one-lane rule). "Proof" is the acceptance check the slice's
spec already names, or the probe named in §3.

| # | slice | files | lines | callers to convert | proof |
|---|---|---|---:|---|---|
| 1 | B3 task family (S5) | `issue*.clj`, `plan.clj`, `note.clj`, `my/{issue,plan,note}.clj` → `seon/task.clj`, `my/task.clj` | −3,222 | 10 src / 4 files; 205 test / 24 | B3 §4 forms: trigger → task → start → done as a query |
| 2 | errors: F0 constructor + B3 c6 + N10 + N11 | `error.clj`, `error/refusal.clj`, 14 `refuse!` files, 19 chain readers | −2,005 | 62 src / 18 files | census probe: a wrapped `ex-info` stores both `:via` links with data |
| 3 | B3 remainder (S14) | `bootstrap.clj`, `env.clj`, `effect.clj`, `config.clj` | −1,655 | bootstrap 14 src / 4 | B3 §8 `wc -l` + HEAD loads |
| 4 | A1 ambient + fallback + shape family (S10 + S16) | `schema.clj`, `fn/schema_shape.clj`, `program.cljc`; 105 test files by script | −1,283 | 78 src / 24 files; 616 test / 105 (scripted) | `rg -c 'call-with-projection\|handed-projection\|current-projection' src` = 0; a raw `d/db` value refuses by name |
| 5 | B4 one run + machinery (S2) | `test.clj`, `test/runner.clj`, `test/cache.clj` | −1,618 | 19 src / 7 files | an unchanged green request executes no test |
| 6 | N4 one projection constructor | `schema.clj` (after row 4, same lane) | −680 | 13 src / 4; 111 test / 28 | the projection-acquisition probes: an incremental derive equals a from-forms derive for one changed key |
| 7 | B1 fn.clj (S9) | `fn.clj`, `fn/analyzer.clj` | −1,384 | — | B1 table `wc -l`; the dependency-selection samples |
| 8 | A1 call preparation (S8) | `call_preparation.clj` | −1,237 | 7 src / 4; 71 test / 4 | A1 §7 behaviour tests over `prepare`/`supply` |
| 9 | A2 c1 + N1 read currency | `db.clj`, then `turn.clj` c5 consumers | −520 | 20 src / 4; 74 test / 13 | B2 c5 probe F6: a retracted test, an empty read, a temporal read, answered by `source-context-unchanged?` |
| 10 | B1 publication (S19) | `cluster.clj` 1637-2751, `cluster/source.clj` | −929 | 11 src / 3; 44 test / 17 | a docstring edit adopts in ≤ 700 ms (publication script row) |
| 11 | N2 write validator → Malli `explain` | `db.clj` (same lane as 9) | −374 | internal; 15 test / 6 | the owned-value tests refuse with Malli paths; arity gate untouched |
| 12 | A2 c13 parser pre-checks | `db.clj` (same lane) | −342 | internal | A2's eight-input table: Datahike throws on each |
| 13 | ai classifier (S12) | `ai.clj`, `seon.ai.edn` | −740 | 27 src / 8; 254 test / 20 | three recorded outcomes settle through `record-attempt!` |
| 14 | dead and test-only vars (N7 + N9) | ≈ 40 files, one script | −834 | 0 src | HEAD loads; the deleted tests' namespaces load; `ns-publics` check per var |
| 15 | N3 schema accretion → Datahike's own rule | `cluster.clj` 1002-1635 (after row 10, same lane) | −450 | 1 src; 14 test / 5 | 1.3e: the declaration transaction applied on a branch of a live store; an illegal `:db/unique` change refused by Datahike's `check-schema-update` |
| 16 | N15 chassis for HTML | `render/hiccup.clj`, `render/lint.clj`, `deps.edn` | −450 | `->string` 12 src; 101 test | byte-identical output on the 101 test expectations or a named diff |
| 17 | N19 + audit row 1: one projection memo, no stamp | `db.clj` (same lane as 9) | −252 | 90 src / 23 (signature unchanged) | audit probe 1: `:cache-context` present on the value the stamp was glued to; one memo hit across an unrelated commit |
| 18 | N6 + A2 c9/c10 GC | `maintenance.clj` | −250 | 2 src; 21 test / 3 | dry run then sweep through `gc-storage!`; the root verification row still written |
| 19 | N17 profiling on LongAdder + one arming entry | `profile.clj`, `cluster.clj` 2317-2410 | −166 | 18 src / 4 | C1's timing rows unchanged for a probe call; overhead measured |
| 20 | leaf dedupe: N5, N12, N13, N14 | `schema.clj` 822-944, `id.clj`, `fs/jvm.clj`, `shell/jvm.clj`, `cluster/process.clj`, delay sites | −280 | ≈ 60 | HEAD loads; fingerprint RESET item recorded |

**Sum of the top 20: ≈ 18,700 lines.**

## 9. Sum

| part | lines |
|---|---:|
| plan-owned rows (S*) | ≈ 24,990 |
| new rows (N*) | ≈ 4,040 |
| **ranked total** | **≈ 29,000 → src ≈ 55,900** |
| probe-first rows (not summed) | ≈ 1,850 → src ≈ 54,000 |
| plus the untracked `src/seon/test.clj.orig` | 1,880 (not in any count) |

Overlaps were removed where they were visible: the 475 test-only `fn.clj` lines sit
inside S9, dead vars inside plan files (≈ 970) are charged to their plan row, and
N1 and N2 are counted beyond A2 c1/c6. Plan figures (S*) are the specs' own and
were not re-measured here. N* figures are definition spans minus an estimated
replacement; none was implemented or probed.

## 10. A guard against regrowth

A `commit-msg` hook, about 20 lines, that uses only git plumbing:

```sh
#!/bin/sh
# Print a commit's net src lines; refuse growth without a stated reason.
net=$(git diff --cached --numstat -- 'src/*.clj' 'src/*.cljc' |
      awk '{a+=$1; d+=$2} END {print a-d+0}')
echo "src net lines: $net" >&2
[ "$net" -le 0 ] && exit 0
git interpret-trailers --parse "$1" | grep -q '^Src-growth: ..*' && exit 0
echo "Refusing: this commit grows src by $net lines. Add the trailer" >&2
echo "'Src-growth: <what it deletes next, or why nothing can go>'." >&2
exit 1
```

- **Why it is not fragile.** It reads the staged diff (`--numstat`) and the message
  trailers that git itself parses (`interpret-trailers`). It parses no shell
  command, lints nothing, and uses no cache. Measured: `numstat` over `1625fb9bc`
  took 50 ms and reported +282, which the hook would have refused.
- **Price.** 20 lines in `bin/src-growth-check`, installed as
  `.git/hooks/commit-msg`. Do not set `core.hooksPath`: it would bypass the existing
  email `pre-commit`. It costs 50 ms per commit. `--no-verify` bypasses it, and that
  bypass shows in review.
- **What it does not do.** It does not judge the reason, and it counts moved code as
  growth at its destination. That matches README §5's rule to charge moved code
  once, at its destination. Landing notes quote the same number.

## 11. Timings (this lane)

| operation | ms | proportional to | justification over 1 s |
|---|---:|---|---|
| clj-kondo analysis of src (var-usages + definitions, `--parallel`) | **10,963** | all 105 src files (84,942 lines) | **Over 10 s, so a defect.** The question (every var's callers) covers the whole program, and kondo analysis output is not cached. The cheaper route was `:seon.fn/calls` facts in `default` through `eval_clj` (indexed call edges, sub-second). The brief asked for kondo with no JVM. Issue note to file (orchestrator): "whole-program caller census re-runs kondo instead of reading the indexed call facts" |
| clj-kondo analysis of test/script/dev/bin usages | **10,585** | 329 files | same, and the same defect |
| bb join of 45.8 MB kondo EDN (`callers.clj`) | 9,491 | analysis output size | EDN parse of 45.8 MB. Fixed later in the lane: a 4-tuple usage index (`usages.edn`) made later joins take 1,043 ms |
| zero-caller text search (`zero.clj`) | 4,024 | 447 candidates × ≈ 900 files | one regex pass per candidate. One combined `rg` would be sub-second |
| usage re-join (`ext.clj`, run twice) | 10,458 (first run) | same 45.8 MB parse | **over 10 s.** The first run failed on a namespace lookup and reparsed. Same fix as above (the second run wrote `usages.edn`) |
| every other scan (rg, git, bb over `rows.edn`) | < 1,000 each | — | — |
| guard probe (`git diff --numstat` of one commit) | 50 | changed files | — |
| lane wall time to this document | ≈ 900,000 | — | research, not an operation |

## 12. Limits

- No probe was run and no JVM was used. Every N* row is a reading of source plus a
  proposal, and its §3 proof is the evidence still needed.
- kondo cannot see calls made through data: EDN render pairs, `:db.fn/call` symbols,
  string-built `requiring-resolve`, and SCI-exposed `my.*`. A qualified-name text
  search covered the first two. A dynamically built name would still read as dead.
- Plan figures (S*) come from the owning specs as written. They are not remeasured.
- The web research named libraries and functions from their documentation. Only the
  vendored or classpath ones (hasch, core.cache, babashka.fs/process, edamame,
  langchain4clj, datastar SDK, clj-reload, cron-utils, Malli, Datahike) were read in
  source. chassis, hiccup2, fipp, hato, jsoup, jtokkit, diehard, beholder, loom and
  ubergraph were not read in source.
- Libraries considered and not proposed: **diehard / resilience4clj** for retry (the
  retry ladder goes with S12, and no retry is proposed). **loom / ubergraph** for the
  reverse closure and gate sets: Datalog rules over indexed facts already compute
  them (`fn.clj:1456-1606`), and a graph copy would duplicate the facts.
  **beholder** for file watching: the hook is the event source. **jtokkit** for
  token counts (`ai/tokens.cljc` 246 lines estimates tokens by design; an exact
  tokenizer answers a different question and is provider-specific). **kaocha**
  (vendored) for tests: B4 rules `clojure.test/test-vars` inside the agent lifecycle.

Sources (web research, 2026-09-23): [chassis](https://github.com/onionpancakes/chassis),
[hiccup](https://github.com/weavejester/hiccup), [fipp](https://github.com/brandonbloom/fipp),
[hasch](https://github.com/replikativ/hasch), [babashka/fs](https://github.com/babashka/fs),
[hato](https://github.com/gnarroway/hato), [babashka/http-client](https://github.com/babashka/http-client),
[jsoup](https://jsoup.org/cookbook/extracting-data/attributes-text-html),
[langchain4j streaming](https://docs.langchain4j.dev/tutorials/response-streaming/),
[malli function schemas / instrument](https://github.com/metosin/malli/blob/master/docs/function-schemas.md),
[clj-reload](https://github.com/tonsky/clj-reload), [cron-utils](https://github.com/jmrozanec/cron-utils),
[chime](https://github.com/jarohen/chime), [diehard](https://github.com/sunng87/diehard),
[resilience4clj-retry](https://github.com/resilience4clj/resilience4clj-retry),
[loom](https://github.com/aysylu/loom), [ubergraph](https://github.com/Engelberg/ubergraph),
[beholder](https://github.com/nextjournal/beholder), [jtokkit](https://github.com/knuddelsgmbh/jtokkit).
