---
title: Retired words in current src (CN-1 to CN-27): inventory and slice order
type: research
status: read-only audit, 2026-10-12; no src, test or resources file edited; no commit
---

# Retired words in current src: inventory and slice order

Scope: `src/**/*.clj[c]` (111 files) and `resources/seon/schemas/*.edn` (133 files), counted against the CN rows of
`script/seon/vocabulary.edn` (20 patterns). Seven ledger rows have no vocabulary pattern (CN-2, CN-4, CN-13, CN-19, CN-21,
CN-23, CN-24); they were scanned with the ledger's own words, and CN-19 and CN-23 have no src identifier (§5). The
approved renames are the CN rows of `docs/seon/schedule/agent-loop-ledger.md` §3.18 plus CN-25 to CN-27 (owner,
2026-10-12). Dated research keeps its words; this inventory does not touch it.

## 0. Method and limits

- **Scan.** A small Clojure lexer splits each file into code, string (docstring or prose), and comment regions. Each
  identifier token is split on `- . / : * ! ? + < > = _ '` and matched against the segment set of its CN entry
  (`arm`, `armed`, `disarm`, … for CN-1; `land`, `landed`, `unlanded`, … for CN-26). Counts are occurrences, not lines.
- **Caller counts.** Whole-token occurrences of the bare name across `src`, `test` and `script`, minus the definition.
  Upper bounds: `row`, `projection`, `world`, `admitted`, `diagnostic` and `receipt` are also local binding names and
  English words in code.
- **AV (agent-visible).** A keyword in an agent-facing namespace (`:my.*`, `:seon.repl/*`, `:seon.batch/*`,
  `:seon.error/*`, `:seon.health/*`, `:seon.eval/*`, `:seon.problems/*`, `:seon.await/*`, `:seon.cluster.eval/*`,
  `:seon.render.value/*`) or any name in `src/my/`. A Var is `AV` when it is in `src/my/` and `AV-doc` when it is a public
  `defn` (readable through `doc` from an agent's SCI); otherwise `INTERNAL`.
- **STORED (approximate).** A keyword that is a key in a `resources/seon/schemas` file that carries `:seon.db/` markers.
  98 of the 116 schema files with retired keys carry them, so this flags the store's schema files, not individual
  Datahike attributes. Confirm an attribute's storage in the Datahike bridge before a schema change.
- **SLATED.** Marked only when a named plan, spec or ledger row deletes or folds the identifier. Each mark cites its
  source. A zero-caller identifier with no plan is listed as `DEAD` for the owner to decide, not as slated.
- **Living documents** were counted with the same lexer, which is approximate for Markdown. The authoritative count is
  `clojure -M -m seon.vocabulary` (script/seon/vocabulary.clj).
- **Not checked:** the stored `:seon.db/*` attribute list per key (the bridge decides it), tests beyond
  `bare-name` caller counts, and runtime use from the REPL.

## 1. Slice order (top 8)

Ranked: agent-visible first (my.*, reply and error keys, public Vars an agent reads with `doc`), then by how widely the
identifiers are read, then internal. None of slices 1 to 7 touches a file currently claimed by a lane. Each slice is one
CN entry, with its named tests in the ledger row. Every slice also patches AGENTS.md (CLAUDE.md is its symlink) and the
living docs that name the word, which `root-docs` and `docs-drift` hold (see §6).

| # | Entry (rename) | Agent-visible surface | Files (src with hits) | Identifiers | Callers / blockers |
|---|---|---|---|---|---|
| 1 | **CN-20** batch, pass to eval request (`seon.batch` to `seon.repl`) | `:seon.batch/*` reply keys: 1,189 code hits in src; `seon.batch.edn` 345 keys (stored); `:seon.batch/defined` in `src/my/program.clj` | `src/seon/batch.clj` (563 code), `src/seon/turn/batch.clj` (629), `src/seon/sci/batch.clj` (14); `src/seon/repl.clj` receives the stages (no CN-20 hits today). `src/seon/fs/jvm.clj` `pass` names are algorithm passes, excluded (§5) | `seon.batch` ns; `evaluate-batch` (batch.clj:914, 1 caller); `settle-batch!` (turn.clj:3115, 6); `settle-batch-refusal!` (turn.clj:3395, 6); `runtime-analysis-batch` (fn.clj:1007, 4; verify it is an eval request, §5) | `seon.batch` required by 54 files, `seon.turn.batch` 14, `seon.sci.batch` 3. Blocked on RP-6b, RP-11c, RP-17b, EN-8b, EN-9 (ledger). Stored keys: a schema change, adopted on a live-store branch. |
| 2 | **CN-27** frontier to ready (`seon.task/frontier` to `seon.task/ready`) | `seon.task/frontier` is public, read through `doc`; cited in plan.md (3 hits) | `src/seon/task.clj` (frontier 45 bare callers, 7 files), `src/seon/cluster/agent.clj` (3 sites, lines 1732, 1749, 2116), `src/seon/render/walk.clj` (`frontier-datoms`, `frontier-edges`) | `frontier`, `frontier-rules` (task.clj:871, 4 callers) | Ready now; no dependency. Tests: `test/seon/task_test.clj` (about 6 call sites). Not a stored key. |
| 3 | **CN-15** custody to checked-out branch | `db/call-with-custody` in `src/my/agent.clj`; `unsupplied-custody-error` (error type, AV) | `src/seon/db.clj` (`call-with-custody` db.clj:443, 3 callers; `call-without-custody` db.clj:473, 0 callers); 24 src files carry the word | 5 Vars, 34 call sites; `call-with-custody` to `with-branch` (CN-15 row) | Dead `call-without-custody`: delete or convert with the row. `:seon.db/custody-request` and `:custody-connection` are schema keys in `seon.db.edn`. Blocked on LR-CACHEb and MA-5b (db.clj, ledger). |
| 4 | **CN-18** roster to registry (or the collection's word) | `:my.agent/roster` in `src/my/agent.clj` (stored, `my.agent.edn`); `:seon.cluster.registry/roster` (operator.edn) | `src/seon/cluster/registry.clj` (`roster` defn, registry.clj:111), `src/seon/cluster/agent.clj` (`child-roster!` 653, 4 callers; `entry-roster` 1927; `roster-facts` 1775), `src/seon/profile.clj` | 4 Vars; 98 code tokens, 11 distinct; `registry/roster` in 42 files (bare name) | Blocked on GV-2 (`:branches`) and AT-1 (ledger CN-18). |
| 5 | **CN-5** world to bindings (`binding`, `bound-fn*`, `with-bindings`) | `:seon.error/world` (AV error key, stored); `:seon.batch/world` (stored, 3 keys); `:seon.batch/*world*` dynamic var | `src/seon/turn/world.clj` (`world` defn 46, `with-world` 69 with 3 callers; ns required by 25 files), `src/seon/batch.clj` (`world-members` 47, 0 callers), `src/seon/sci/kernel.clj` (`with-world-ctx` 543, 0 callers), `src/seon/profile.clj` (`world-counter?` 214, 0 callers) | 17 Vars; 264 code tokens, 45 distinct | Blocked on S5-05, S5-06, EN-4 (ledger CN-5). Three zero-caller Vars: delete, do not rename. |
| 6 | **CN-6** receipt to tx-report or result | `:my.background/receipt` (`src/my/background.clj`, AV); `:seon.turn.loop/receipt` (reply); `:seon.problems/errored-receipts` | `src/seon/turn.clj` (about 20 `receipt-*` defs), `src/seon/effect.clj`, `src/seon/schedule.clj`, `src/seon/maintenance.clj` (namespace `seon.maintenance.receipt` is the entity), `src/seon/fault.clj` (folds, see §4) | 27 Vars; 429 code tokens; `:seon.maintenance.receipt/*` keys (about 14, stored) | Blocked on EN-1, RP-14, RP-15a (ledger). `errored-receipts` (problems.clj:279) has 0 callers. |
| 7 | **CN-26** land to merge (git words) | `:seon.task/landed`, `:seon.task/landed-git` (stored, `seon.task.edn`); `:seon.source/landed` (AV in `src/my/program.clj`; `source.edn` declares `:landed`) | `src/seon/cluster/source.clj` (`land!` 1762, 2 callers; `land-held!` 1720, 1 caller), `src/seon/turn.clj` (`land-end!` 2254, 3; `landing-tx` 2229, 2; `land-ended!` 2279, 0 callers) | 8 Vars; 58 code tokens; `unlanded-ends`, `land-ends!` (agent.clj) are slated, §4 | Sequence after hybrid-push U3, which deletes the names the rename would otherwise convert. Rename the survivors only. |
| 8 | **CN-9** shown text to printed value | `:seon.eval/shown` (stored, 50 code hits; AV); `:seon.error/shown`, `:seon.error/shown-blob`, `:seon.error/shown-size` (stored AV error keys); `:seon.batch/shown` (reply) | `src/seon/sci/eval.clj` (38 CN-9 hits), `src/seon/repl.clj` (`shown-value` 62, 10 callers), `src/seon/turn.clj` (`final-shown` 2850), `src/seon/cluster/squash.clj` (private `shown-state`, 7 callers), `src/seon/render/value.clj` | 15 Vars; 362 code tokens | **Collides with `program-context`** (claims `src/seon/sci/eval.clj` and its test). Deps SR-D, SR-F, SR-I add these keys (ledger CN-9). |

Next in line, and why they wait: **CN-16** supply to defaults is AV (`:seon.call-preparation/invalid-supplied-value-error`
and the other `:seon.call-preparation/*-error` keys) but waits on the §3.22 decision for `seon.call-preparation`, which a deletion would make moot.
**CN-11** diagnostic to error is AV (`:diagnostics` in replies, 68 AV hits) but the errors cut replaces the `diagnostic`
constructor, so renaming its 180 callers first wastes the sweep (errors.md:154, §4). **CN-21** is a deletion row (§4), not
a rename. The internal, largest entries come after: CN-3 (projection,
3,504 code hits; most are database and registry keys, 760 stored-approx), CN-4 (admission, 1,509), CN-25 (row, 1,262 code
hits, most of them local bindings that need no schema change), CN-1 (arm/armed, 329), CN-7 (settle, 270).

## 2. Summary by entry

Code = occurrences in code; prose = in strings, docstrings and comments (`src` only). Distinct = distinct code tokens. Files = src files with code hits. Defs = Vars whose own name carries the word (Var callers: see §3). AV/STORED are counts of code occurrences: AV = keyword in an agent-facing namespace (`:my.*`, `:seon.repl/*`, `:seon.batch/*`, `:seon.error/*`, `:seon.health/*`, `:seon.eval/*`, `:seon.problems/*`, `:seon.await/*`, `:seon.cluster.eval/*`, `:seon.render.value/*`), STORED = keyword declared as a key in a `resources/seon/schemas` file that also carries `:seon.db/` markers (approximate; see §0).

| Entry | Sense (vocabulary.edn) | Code | Prose | Distinct | Files | Defs | AV | STORED | in `src/my` |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| CN-1 | arm/armed/arming -> instrument (Malli `m/-instrument`); agent graph start/stop (CN-2) | 329 | 408 | 65 | 21 | 24 | 8 | 29 | 0 |
| CN-2 | arm-agent/disarm!/stop-flows! -> start/stop agent graph | 4 | 2 | 2 | 2 | 1 | 0 | 0 | 0 |
| CN-3 | projection -> registry (Malli) / database schema (Datahike) | 3504 | 590 | 244 | 50 | 90 | 11 | 760 | 0 |
| CN-4 | admission -> validation (entry policy stays) | 1509 | 500 | 151 | 63 | 44 | 0 | 835 | 3 |
| CN-5 | world -> bindings (`binding`, `bound-fn*`, `with-bindings`); checked-out branch + SCI context | 264 | 358 | 45 | 24 | 17 | 41 | 36 | 0 |
| CN-6 | receipt -> tx-report (Datahike) / result | 429 | 143 | 70 | 15 | 27 | 11 | 64 | 2 |
| CN-7 | settle/settlement -> record, recording transaction | 270 | 297 | 61 | 11 | 27 | 24 | 45 | 0 |
| CN-8 | envelope -> reply (prepl message map) | 0 | 3 | 0 | 0 | 0 | 0 | 0 | 0 |
| CN-9 | shown text -> printed value | 362 | 41 | 41 | 22 | 15 | 98 | 121 | 0 |
| CN-10 | fault -> recorded error (folds into seon.error, E-SDELETE) | 297 | 341 | 60 | 22 | 20 | 11 | 43 | 0 |
| CN-11 | diagnostic -> error (or finding) | 438 | 125 | 30 | 65 | 14 | 68 | 72 | 12 |
| CN-12 | test member -> test var | 253 | 8 | 25 | 13 | 0 | 0 | 244 | 0 |
| CN-13 | accretion test -> generative check (`malli.generator/check`) | 42 | 10 | 26 | 4 | 0 | 0 | 33 | 0 |
| CN-14 | gate / gate set -> entry policy / tests reaching the change | 35 | 73 | 18 | 8 | 8 | 0 | 0 | 3 |
| CN-15 | custody -> checked-out branch | 101 | 113 | 13 | 24 | 5 | 0 | 0 | 1 |
| CN-16 | supply/supplied -> defaults (Malli `:default`, `default-value-transformer`) | 254 | 391 | 42 | 20 | 12 | 0 | 42 | 2 |
| CN-17 | adopt/adoption -> reload (`require :reload`, clj-reload) | 107 | 168 | 34 | 12 | 10 | 0 | 32 | 0 |
| CN-18 | roster -> registry (or the collection's own word) | 98 | 100 | 11 | 9 | 4 | 1 | 3 | 7 |
| CN-20 | pass / batch -> eval request (`seon.repl`); clojure.test's `:pass` is NOT retired | 1522 | 394 | 249 | 35 | 11 | 1086 | 1016 | 1 |
| CN-21 | situation / disposition (turn stored state) deleted, not renamed | 84 | 50 | 16 | 13 | 5 | 13 | 53 | 0 |
| CN-22 | overlay -> branch definitions / overrides | 100 | 76 | 25 | 15 | 10 | 0 | 22 | 2 |
| CN-24 | severity -> :level (clj-kondo finding keys) | 6 | 8 | 2 | 1 | 0 | 6 | 4 | 0 |
| CN-25 | row -> entity / datom / transaction; markdown entry; table test case | 1262 | 639 | 157 | 44 | 47 | 7 | 146 | 12 |
| CN-26 | land/landed/landing -> merge / merged / unmerged; commit for own work | 58 | 80 | 19 | 6 | 8 | 0 | 4 | 2 |
| CN-27 | frontier -> ready | 57 | 12 | 8 | 8 | 4 | 0 | 0 | 0 |

## 3. Identifiers per entry (code tokens, top 15 each; defined Vars with bare-name caller counts)

Caller counts are whole-token occurrences of the bare name across `src`, `test` and `script`, minus the definition. They over-count where the name is an English or local word (`row`, `projection`, `world`, `admitted`, `diagnostic`, `receipt`): read those as upper bounds and check the owner's file by hand.

### CN-1: arm/armed/arming -> instrument (Malli `m/-instrument`); agent graph start/stop (CN-2)

Top code tokens (occurrences): `armed` 82, `arm-request` 29, `arm` 27, `thread-arm` 21, `:seon.sci.kernel/arm` 10, `::thread-arm` 10, `arming-identities` 8, `carried-arm` 8, `:seon.batch/arm-error` 7, `arm-state` 7, `:seon.instrument/arm` 6, `:seon.maintenance.settlement/arm` 6

| Var | Where | Callers | Public | Flags |
|---|---|---:|---|---|
| `arm` | `src/seon/sci/kernel.clj:433` | 229 | yes | AV-doc (public) |
| `arm!` | `src/seon/sci/arm.clj:38` | 16 | yes | AV-doc (public) |
| `carried-arm` | `src/seon/flow.clj:475` | 10 | no | INTERNAL |
| `current-arm` | `src/seon/sci/kernel.clj:602` | 8 | yes | AV-doc (public) |
| `adopt-arm` | `src/seon/sci/kernel.clj:668` | 7 | yes | AV-doc (public) |
| `arm-plan` | `src/seon/call_preparation.clj:807` | 4 | yes | AV-doc (public) |
| `arm-var!` | `src/seon/instrument.clj:989` | 4 | no | INTERNAL; PROBABLE: ledger UL-12 replaces the arming loop with Malli's options (not named) |
| `armed-dials` | `src/seon/sci/eval.clj:2411` | 4 | no | INTERNAL |
| `arm-first-loads!` | `src/seon/test.clj:2525` | 3 | no | INTERNAL |
| `new-armed` | `src/seon/sci/kernel.clj:341` | 3 | no | INTERNAL |
| `with-arm` | `src/seon/sci/kernel.clj:489` | 3 | yes | AV-doc (public) |
| `acquire-arm` | `src/seon/sci/kernel.clj:411` | 2 | no | INTERNAL |
| `arm-host-program!` | `src/seon/cluster.clj:2370` | 2 | yes | AV-doc (public) |
| `arming-projection` | `src/seon/sci/arm.clj:18` | 2 | yes | AV-doc (public) |
| `current-thread-arm` | `src/seon/sci/kernel.clj:406` | 2 | no | INTERNAL |
| `arm-measures` | `src/seon/batch.clj:352` | 1 | yes | AV-doc (public) |
| `arm-timer` | `src/seon/schedule.clj:710` | 1 | no | INTERNAL |
| `arm-turn-completion-backstop!` | `src/seon/turn.clj:5202` | 1 | no | INTERNAL |
| `arm?` | `src/seon/sci/kernel.clj:560` | 1 | yes | AV-doc (public) |
| `detached-arm` | `src/seon/sci/kernel.clj:619` | 1 | yes | AV-doc (public) |
| `development-arming-identities` | `src/seon/cluster.clj:2415` | 1 | no | INTERNAL |
| `own-arm` | `src/seon/sci/kernel.clj:362` | 1 | no | INTERNAL |
| `release-arm!` | `src/seon/sci/kernel.clj:656` | 1 | yes | AV-doc (public) |
| `arm-generator` | `src/seon/sci/kernel.clj:571` | 0 | yes | AV-doc (public); DEAD: zero callers (not named by a plan) |

### CN-2: arm-agent/disarm!/stop-flows! -> start/stop agent graph

Top code tokens (occurrences): `stop-flows!` 3, `cluster/stop-flows!` 1

| Var | Where | Callers | Public | Flags |
|---|---|---:|---|---|
| `stop-flows!` | `src/seon/cluster.clj:3509` | 4 | yes | AV-doc (public) |

### CN-3: projection -> registry (Malli) / database schema (Datahike)

Top code tokens (occurrences): `projection` 1534, `:seon.schema/projection` 408, `:seon.schema.projection/forms` 122, `db/carried-projection` 118, `:seon.schema.projection/registry` 116, `::projection` 64, `:seon.sci.eval/projection-state` 49, `projection-state` 45, `schema/projection-cache-value` 42, `schema/projection-from-database` 41, `schema/call-with-projection` 32, `:seon.schema.projection/function-contracts` 30

| Var | Where | Callers | Public | Flags |
|---|---|---:|---|---|
| `projection` | `src/seon/render/transcript.clj:573` | 3280 | no | INTERNAL |
| `projection` | `src/seon/web/jvm.clj:377` | 3280 | no | INTERNAL |
| `projection-state` | `src/seon/sci/eval.clj:213` | 53 | yes | AV-doc (public) |
| `projection-cache` | `src/seon/schema.clj:412` | 24 | no | INTERNAL |
| `carried-projection` | `src/seon/db.clj:1891` | 17 | yes | AV-doc (public) |
| `build-projection` | `src/seon/schema.clj:2355` | 15 | yes | AV-doc (public) |
| `context-projection` | `src/seon/sci/eval.clj:841` | 13 | no | INTERNAL |
| `context-projection` | `src/seon/sci/kernel.clj:297` | 13 | yes | AV-doc (public) |
| `current-projection` | `src/seon/schema.clj:3740` | 11 | yes | AV-doc (public) |
| `projection-with-schema` | `src/seon/schema.clj:3400` | 9 | yes | AV-doc (public) |
| `declared-projection` | `src/seon/config.clj:384` | 8 | no | INTERNAL |
| `declared-projection` | `src/seon/render.clj:1283` | 8 | yes | AV-doc (public) |
| `projection-cache-key` | `src/seon/db.clj:1521` | 8 | no | INTERNAL |
| `projection-registry` | `src/seon/schema.clj:579` | 7 | no | INTERNAL |
| `projection-value` | `src/seon/db.clj:1505` | 7 | no | INTERNAL |
| `request-projection` | `src/seon/render.clj:88` | 7 | yes | AV-doc (public) |
| `content-projection` | `src/seon/db.clj:1603` | 6 | no | INTERNAL |
| `projection-complete-generator` | `src/seon/error.clj:2762` | 5 | yes | AV-doc (public) |
| `projection-validator` | `src/seon/schema.clj:3954` | 5 | yes | AV-doc (public) |
| `projection-with-declarations` | `src/seon/schema.clj:2626` | 5 | no | INTERNAL |
| `advance-context-projection!` | `src/seon/sci/eval.clj:856` | 4 | no | INTERNAL |
| `declaration-projection` | `src/seon/schema.clj:1259` | 4 | yes | AV-doc (public) |
| `load-projection` | `src/seon/schema.clj:3358` | 4 | yes | AV-doc (public) |
| `projection-cache-value` | `src/seon/schema.clj:422` | 4 | yes | AV-doc (public) |
| `carry-projection-state` | `src/seon/db.clj:313` | 3 | yes | AV-doc (public) |
| `operation-projection` | `src/seon/error/refusal.clj:242` | 3 | no | INTERNAL |
| `projection-build` | `src/seon/schema.clj:2173` | 3 | no | INTERNAL |
| `projection-builder` | `src/seon/db.clj:1801` | 3 | no | INTERNAL |
| `projection-fingerprint` | `src/seon/schema.clj:858` | 3 | no | INTERNAL |
| `projection-form` | `src/seon/render/web.clj:606` | 3 | no | INTERNAL |
| `projection-form-keys` | `src/seon/test/runner.clj:536` | 3 | no | INTERNAL |
| `projection-from-database` | `src/seon/schema.clj:3387` | 3 | yes | AV-doc (public) |
| `submit-source-in-projection` | `src/seon/cluster/agent.clj:720` | 3 | no | INTERNAL |
| `active-projection` | `src/seon/schema.clj:1164` | 2 | no | INTERNAL |
| `analysis-projection` | `src/seon/fn.clj:2983` | 2 | no | INTERNAL |
| `arming-projection` | `src/seon/sci/arm.clj:18` | 2 | yes | AV-doc (public) |
| `carry-world-projection!` | `src/seon/test.clj:1949` | 2 | no | INTERNAL |
| `evaluation-projection` | `src/seon/sci/eval.clj:847` | 2 | no | INTERNAL |
| `hold-committed-projection!` | `src/seon/db.clj:1871` | 2 | no | INTERNAL |
| `identity-only-projection-in` | `src/seon/schema.clj:4128` | 2 | yes | AV-doc (public) |
| `plain-projection` | `src/seon/render/value.clj:1599` | 2 | no | INTERNAL |
| `projection-admissions` | `src/seon/schema.clj:2889` | 2 | no | INTERNAL |
| `projection-advanced-by` | `src/seon/schema.clj:3202` | 2 | no | INTERNAL |
| `projection-cache-policy` | `src/seon/db.clj:1486` | 2 | yes | AV-doc (public) |
| `projection-delta` | `src/seon/sci/eval.clj:3640` | 2 | no | INTERNAL |
| `projection-explainer` | `src/seon/schema.clj:4040` | 2 | yes | AV-doc (public) |
| `projection-fallback` | `src/seon/db.clj:1469` | 2 | yes | AV-doc (public) |
| `projection-fingerprint-from-data` | `src/seon/schema.clj:2404` | 2 | no | INTERNAL |
| `projection-from-entities` | `src/seon/schema.clj:3088` | 2 | yes | AV-doc (public) |
| `projection-of-population` | `src/seon/schema.clj:3040` | 2 | no | INTERNAL |
| `projection-ranges` | `src/seon/schema.clj:3343` | 2 | yes | AV-doc (public) |
| `shape-projection` | `src/seon/schema.clj:4055` | 2 | no | INTERNAL |
| `speculative-projection` | `src/seon/db.clj:1628` | 2 | no | INTERNAL |
| `value-projection` | `src/seon/db.clj:1615` | 2 | no | INTERNAL |
| `advanced-projection` | `src/seon/schema.clj:3242` | 1 | no | INTERNAL |
| `carry-connection-projection-state!` | `src/seon/db.clj:300` | 1 | yes | AV-doc (public) |
| `checking-projection` | `src/seon/error/refusal.clj:253` | 1 | no | INTERNAL |
| `connection-projection-state` | `src/seon/db.clj:284` | 1 | no | INTERNAL |
| `construction-projection` | `src/seon/db.clj:1673` | 1 | no | INTERNAL |
| `declaration-projection-bytes-per-form` | `src/seon/schema.clj:1254` | 1 | yes | AV-doc (public) |
| `handed-projection` | `src/seon/schema.clj:3746` | 1 | yes | AV-doc (public) |
| `held-projection` | `src/seon/db.clj:1816` | 1 | no | INTERNAL |
| `incremental-projection` | `src/seon/fn.clj:3429` | 1 | no | INTERNAL |
| `projection-bindings` | `src/seon/schema.clj:1127` | 1 | yes | AV-doc (public) |
| `projection-bytes-per-contract` | `src/seon/schema.clj:1256` | 1 | yes | AV-doc (public) |
| `projection-bytes-per-form` | `src/seon/schema.clj:1255` | 1 | yes | AV-doc (public) |
| `projection-entities` | `src/seon/schema.clj:2872` | 1 | no | INTERNAL |
| `projection-executor` | `src/seon/cluster.clj:3345` | 1 | yes | AV-doc (public) |
| `projection-state-bindings` | `src/seon/schema.clj:1138` | 1 | yes | AV-doc (public) |
| `projection-state?` | `src/seon/sci/eval.clj:188` | 1 | yes | AV-doc (public) |
| `projection-value?` | `src/seon/schema.clj:4136` | 1 | yes | AV-doc (public) |
| `projection-with-pulled-form-in` | `src/seon/schema.clj:3654` | 1 | yes | AV-doc (public) |
| `refuse-projection-source` | `src/seon/schema.clj:3319` | 1 | no | INTERNAL |
| `result-projections` | `src/seon/maintenance.clj:23` | 1 | no | INTERNAL |
| `reusable-projection-fingerprint` | `src/seon/schema.clj:2414` | 1 | no | INTERNAL |
| `settings-projection` | `src/seon/config.clj:484` | 1 | no | INTERNAL |
| `settlement-projection` | `src/seon/turn.clj:185` | 1 | yes | AV-doc (public) |
| `shape-projections-with` | `src/seon/schema.clj:2517` | 1 | no | INTERNAL |
| `speculative-report-projection` | `src/seon/db.clj:1850` | 1 | no | INTERNAL |
| `advance-projection` | `src/seon/schema.clj:3300` | 0 | yes | AV-doc (public); DEAD: zero callers (not named by a plan) |
| `advance-projection!` | `src/seon/env.clj:121` | 0 | yes | AV-doc (public); DEAD: zero callers (not named by a plan) |
| `call-with-projection` | `src/seon/schema.clj:1150` | 0 | yes | AV-doc (public); DEAD: zero callers (not named by a plan) |
| `call-with-projection-state` | `src/seon/schema.clj:1157` | 0 | yes | AV-doc (public); DEAD: zero callers (not named by a plan) |
| `identity-only-projection` | `src/seon/schema.clj:4159` | 0 | yes | AV-doc (public); DEAD: zero callers (not named by a plan) |
| `projection-attributes` | `src/seon/schema.clj:3352` | 0 | yes | AV-doc (public); DEAD: zero callers (not named by a plan) |
| `projection-complete?` | `src/seon/error.clj:2668` | 0 | yes | AV-doc (public); DEAD: zero callers (not named by a plan) |
| `projection-identity` | `src/seon/schema.clj:4146` | 0 | yes | AV-doc (public); DEAD: zero callers (not named by a plan) |
| `projection-state-generator` | `src/seon/sci/eval.clj:197` | 0 | yes | AV-doc (public); DEAD: zero callers (not named by a plan) |
| `projection-with-function-contract` | `src/seon/schema.clj:3723` | 0 | yes | AV-doc (public); DEAD: zero callers (not named by a plan) |
| `projection-without-schema` | `src/seon/schema.clj:3691` | 0 | yes | AV-doc (public); DEAD: zero callers (not named by a plan) |

### CN-4: admission -> validation (entry policy stays)

Top code tokens (occurrences): `:seon.schema.admission/reason` 165, `:seon.schema.admission/exemption` 163, `:seon.schema.admission/polymorphic-boundary` 163, `admitted` 124, `:seon.sci.admit/caps` 111, `:seon.schema.admission/source` 85, `admission` 66, `:seon.sci.admit/value` 42, `:seon.sci.admit/record` 33, `schema-admissions` 25, `function-admissions` 24, `admit` 21

| Var | Where | Callers | Public | Flags |
|---|---|---:|---|---|
| `admitted` | `src/seon/task.clj:296` | 481 | no | INTERNAL |
| `admission` | `src/seon/effect.clj:174` | 378 | no | INTERNAL |
| `admit` | `src/seon/schema/admission.clj:423` | 87 | yes | AV-doc (public) |
| `admit` | `src/seon/schema/edn.clj:588` | 87 | yes | AV-doc (public) |
| `admit` | `src/seon/sci/admit.clj:816` | 87 | yes | AV-doc (public) |
| `admission-refusal!` | `src/seon/test.clj:1279` | 10 | no | INTERNAL |
| `admit-run` | `src/seon/test.clj:1347` | 7 | yes | AV-doc (public) |
| `admitted-form` | `src/seon/turn/batch.clj:769` | 6 | no | INTERNAL |
| `admit-destructive-path!` | `src/seon/cluster/store.clj:303` | 5 | yes | AV-doc (public) |
| `admit-destructive-path!` | `src/seon/fs.clj:85` | 5 | yes | AV-doc (public) |
| `admitted-members` | `src/seon/test/runner.clj:1375` | 5 | no | INTERNAL |
| `bounded-admission` | `src/seon/error.clj:247` | 4 | no | INTERNAL |
| `admission-scope` | `src/seon/test.clj:1326` | 3 | no | INTERNAL |
| `admit*` | `src/seon/sci/admit.clj:752` | 3 | no | INTERNAL |
| `admitted-value` | `src/seon/effect.clj:181` | 3 | no | INTERNAL |
| `arity-admitted?` | `src/seon/db.clj:4782` | 3 | no | INTERNAL |
| `not-admitted` | `src/seon/turn.clj:2887` | 3 | no | INTERNAL |
| `admission-closed?` | `src/seon/turn.clj:2133` | 2 | no | INTERNAL |
| `admission-member-values` | `src/seon/test.clj:1340` | 2 | no | INTERNAL |
| `admit-initialization` | `src/seon/config.clj:355` | 2 | no | INTERNAL |
| `admit-member` | `src/seon/render/value.clj:1257` | 2 | no | INTERNAL |
| `admit-walk` | `src/seon/sci/admit.clj:769` | 2 | no | INTERNAL |
| `admitted-default-document` | `src/seon/config.clj:378` | 2 | no | INTERNAL |
| `admitted-external-finding?` | `src/seon/fn.clj:1405` | 2 | no | INTERNAL |
| `admitted-uri` | `src/seon/web/jvm.clj:42` | 2 | no | INTERNAL |
| `agent-admitted?` | `src/seon/cluster.clj:606` | 2 | no | INTERNAL |
| `bounded-error-admission` | `src/seon/error.clj:340` | 2 | no | INTERNAL |
| `projection-admissions` | `src/seon/schema.clj:2889` | 2 | no | INTERNAL |
| `refuse-closed-admission!` | `src/seon/turn.clj:282` | 2 | no | INTERNAL |
| `selection-admission` | `src/seon/test.clj:1254` | 2 | yes | AV-doc (public) |
| `unrecorded-admission` | `src/seon/schema.clj:3081` | 2 | no | INTERNAL |
| `admission-members` | `src/seon/test.clj:1296` | 1 | no | INTERNAL |
| `admit-changed-identities` | `src/seon/schema/edn.clj:553` | 1 | no | INTERNAL |
| `admit-chunk` | `src/seon/render/value.clj:1195` | 1 | no | INTERNAL |
| `admit-initialization-entities` | `src/seon/config.clj:272` | 1 | no | INTERNAL |
| `admit-value` | `src/seon/sci/admit.clj:803` | 1 | yes | AV-doc (public) |
| `admitted-message` | `src/seon/cluster/message.clj:611` | 1 | no | INTERNAL |
| `admitted-size` | `src/seon/error.clj:435` | 1 | no | INTERNAL |
| `admission-from-asserting-transaction` | `src/seon/schema.clj:968` | 0 | yes | AV-doc (public); DEAD: zero callers (not named by a plan) |
| `admit-declarations!` | `src/seon/schema.clj:4333` | 0 | yes | AV-doc (public); DEAD: zero callers (not named by a plan) |
| `admit-partitioned` | `src/seon/sci/admit.clj:859` | 0 | yes | AV-doc (public); DEAD: zero callers (not named by a plan) |
| `close-admissions!` | `src/seon/cluster/host.clj:169` | 0 | yes | AV-doc (public); DEAD: zero callers (not named by a plan) |
| `schema-scoped-admissions` | `src/seon/schema.clj:2904` | 0 | no | INTERNAL; DEAD: zero callers (not named by a plan) |
| `system-run-if-admitted-call` | `src/seon/turn.clj:758` | 0 | no | INTERNAL; DEAD: zero callers (not named by a plan) |

### CN-5: world -> bindings (`binding`, `bound-fn*`, `with-bindings`); checked-out branch + SCI context

Top code tokens (occurrences): `world` 106, `seon.batch/*world*` 32, `:seon.batch/world` 31, `seon.turn.world` 6, `supply-from-world` 5, `:seon.batch/form-world` 4, `world-supplied` 4, `:seon.error/world` 4, `in-world` 4, `world/world` 4, `world-refusal` 4, `seon.batch/world-members` 3

| Var | Where | Callers | Public | Flags |
|---|---|---:|---|---|
| `world` | `src/seon/turn/world.clj:46` | 687 | yes | AV-doc (public) |
| `supply-from-world` | `src/seon/call_preparation.clj:899` | 4 | no | INTERNAL |
| `in-world` | `src/seon/flow.clj:267` | 3 | no | INTERNAL |
| `open-world!` | `src/seon/test.clj:1957` | 3 | no | INTERNAL |
| `with-world` | `src/seon/turn/world.clj:69` | 3 | yes | AV-doc (public) |
| `world-ctx` | `src/seon/sci/kernel.clj:528` | 3 | yes | AV-doc (public) |
| `world-refusal` | `src/seon/test.clj:1938` | 3 | no | INTERNAL |
| `carry-world-projection!` | `src/seon/test.clj:1949` | 2 | no | INTERNAL |
| `request-world` | `src/seon/cluster/boot.clj:629` | 2 | no | INTERNAL |
| `world-reference` | `src/seon/error/refusal.clj:595` | 2 | yes | AV-doc (public) |
| `current-world` | `src/seon/error/refusal.clj:612` | 1 | no | INTERNAL |
| `form-world` | `src/seon/batch.clj:537` | 1 | no | INTERNAL |
| `world-base` | `src/seon/test.clj:1996` | 1 | no | INTERNAL |
| `world-member?` | `src/seon/call_preparation.clj:562` | 1 | no | INTERNAL |
| `with-world-ctx` | `src/seon/sci/kernel.clj:543` | 0 | yes | AV-doc (public); DEAD: zero callers (not named by a plan) |
| `world-counter?` | `src/seon/profile.clj:214` | 0 | yes | AV-doc (public); DEAD: zero callers (not named by a plan) |
| `world-members` | `src/seon/batch.clj:47` | 0 | yes | AV-doc (public); DEAD: zero callers (not named by a plan) |

### CN-6: receipt -> tx-report (Datahike) / result

Top code tokens (occurrences): `receipt` 117, `?receipt` 47, `receipts` 28, `claimed-receipt-id` 24, `:seon.turn.loop/receipt` 15, `:seon.maintenance.receipt/id` 14, `receipt-settle-call` 11, `receipt-eid` 10, `receipt-identity` 9, `:seon.maintenance.receipt/completed-at` 8, `receipt-id` 7, `*receipt*` 6

| Var | Where | Callers | Public | Flags |
|---|---|---:|---|---|
| `receipt-settle-call` | `src/seon/turn.clj:1347` | 10 | yes | AV-doc (public) |
| `receipt-identity` | `src/seon/schedule.clj:242` | 8 | no | INTERNAL |
| `receipt-identity` | `src/seon/turn.clj:505` | 8 | yes | AV-doc (public) |
| `receipt-run` | `src/seon/turn.clj:780` | 5 | no | INTERNAL |
| `run-receipts` | `src/seon/eval/drive.clj:101` | 5 | yes | AV-doc (public) |
| `run-receipts` | `src/seon/turn.clj:1454` | 5 | no | INTERNAL |
| `current-receipt` | `src/seon/turn.clj:771` | 4 | no | INTERNAL |
| `receipt-row` | `src/seon/turn.clj:810` | 4 | no | INTERNAL |
| `receipt-start-call` | `src/seon/turn.clj:857` | 4 | yes | AV-doc (public) |
| `receipt-settle-batch-tx` | `src/seon/turn.clj:906` | 3 | yes | AV-doc (public) |
| `receipt-settle-tx` | `src/seon/turn.clj:884` | 3 | yes | AV-doc (public) |
| `carried-receipt` | `src/seon/fault.clj:315` | 2 | yes | AV-doc (public); E-SDELETE: `seon.fault` folds into `seon.error` (errors.md:311-314, 371; ledger E-SDELETE, CN-10) |
| `fault-receipt` | `src/seon/mcp.clj:456` | 2 | no | INTERNAL |
| `receipt-entry` | `src/seon/render/transcript.clj:202` | 2 | no | INTERNAL |
| `receipt-identities` | `src/seon/effect.clj:57` | 2 | no | INTERNAL |
| `receipt-settle-batch-call` | `src/seon/turn.clj:894` | 2 | yes | AV-doc (public) |
| `receipt-state` | `src/seon/effect.clj:46` | 2 | no | INTERNAL |
| `receipt-terminal-assertions` | `src/seon/turn.clj:1143` | 2 | no | INTERNAL |
| `receipt-value` | `src/seon/turn.clj:1461` | 2 | no | INTERNAL |
| `red-receipt?` | `src/seon/turn.clj:1671` | 2 | yes | AV-doc (public) |
| `terminal-receipt?` | `src/seon/turn.clj:1727` | 2 | no | INTERNAL |
| `receipt-reference-assertions` | `src/seon/turn.clj:1136` | 1 | no | INTERNAL |
| `receipt-start-tx` | `src/seon/turn.clj:790` | 1 | yes | AV-doc (public) |
| `recent-receipt-entities` | `src/seon/render/transcript.clj:96` | 1 | no | INTERNAL |
| `running-receipts` | `src/seon/turn.clj:320` | 1 | no | INTERNAL |
| `terminal-receipt` | `src/seon/schedule.clj:401` | 1 | no | INTERNAL |
| `errored-receipts` | `src/seon/problems.clj:279` | 0 | no | INTERNAL; DEAD: zero callers (not named by a plan) |

### CN-7: settle/settlement -> record, recording transaction

Top code tokens (occurrences): `settled` 49, `settlement` 18, `settle!` 14, `settlement-evaluation` 12, `:seon.cluster.eval/settle-request` 11, `receipt-settle-call` 11, `:seon.turn.loop/settled` 9, `settle` 8, `:seon.maintenance.settlement/result` 7, `:seon.batch/settle` 6, `settle-value!` 6, `:seon.maintenance.settlement/arm` 6

| Var | Where | Callers | Public | Flags |
|---|---|---:|---|---|
| `settled` | `src/seon/turn.clj:4595` | 224 | no | INTERNAL |
| `settle` | `src/seon/render/value.clj:1004` | 62 | no | INTERNAL |
| `settle` | `src/seon/turn/batch.clj:859` | 62 | yes | AV-doc (public) |
| `settle!` | `src/seon/cache.clj:131` | 17 | no | INTERNAL |
| `settle!` | `src/seon/schedule.clj:559` | 17 | no | INTERNAL |
| `settle!` | `src/seon/turn.clj:3514` | 17 | yes | AV-doc (public) |
| `receipt-settle-call` | `src/seon/turn.clj:1347` | 10 | yes | AV-doc (public) |
| `settle-batch!` | `src/seon/turn.clj:3115` | 6 | no | INTERNAL |
| `settle-batch-refusal!` | `src/seon/turn.clj:3395` | 6 | no | INTERNAL |
| `settle-value!` | `src/seon/effect.clj:586` | 5 | no | INTERNAL |
| `receipt-settle-batch-tx` | `src/seon/turn.clj:906` | 3 | yes | AV-doc (public) |
| `receipt-settle-tx` | `src/seon/turn.clj:884` | 3 | yes | AV-doc (public) |
| `bind-settled-results!` | `src/seon/sci/eval.clj:3411` | 2 | no | INTERNAL |
| `closing-settlement?` | `src/seon/turn.clj:2840` | 2 | no | INTERNAL |
| `receipt-settle-batch-call` | `src/seon/turn.clj:894` | 2 | yes | AV-doc (public) |
| `settle-kept-branches!` | `src/seon/cluster/agent.clj:2124` | 2 | no | INTERNAL; hybrid-push.md §5 deleted (`settle-kept-branches!`' marker query) |
| `background-settlement-request` | `src/seon/effect.clj:661` | 1 | no | INTERNAL |
| `form-settlement` | `src/seon/turn.clj:1783` | 1 | yes | AV-doc (public) |
| `settle-background-terminal!` | `src/seon/effect.clj:676` | 1 | no | INTERNAL |
| `settle-call` | `src/seon/effect.clj:278` | 1 | yes | AV-doc (public) |
| `settle-call` | `src/seon/schedule.clj:420` | 1 | no | INTERNAL |
| `settle-evaluated!` | `src/seon/turn.clj:3223` | 1 | no | INTERNAL |
| `settled-into` | `src/seon/batch.clj:891` | 1 | no | INTERNAL |
| `settled-results` | `src/seon/sci/eval.clj:3394` | 1 | no | INTERNAL |
| `settlement-projection` | `src/seon/turn.clj:185` | 1 | yes | AV-doc (public) |
| `plan-settlement` | `src/seon/turn.clj:1824` | 0 | yes | AV-doc (public); DEAD: zero callers (not named by a plan) |
| `settlement-connection` | `src/seon/turn/world.clj:87` | 0 | yes | AV-doc (public); DEAD: zero callers (not named by a plan) |

### CN-8: envelope -> reply (prepl message map)

Top code tokens (occurrences): 

No Var is named with the word; the word lives in keywords, schema keys and prose.

### CN-9: shown text -> printed value

Top code tokens (occurrences): `shown` 130, `:seon.eval/shown` 50, `:shown` 23, `:seon.error/shown` 11, `:seon.batch/shown` 10, `:seon.error/shown-blob` 10, `shown-state` 8, `:shown-width` 7, `shown-diagnostics` 7, `:seon.batch/shown-map` 6, `:seon.error/shown-size` 6, `names-shown` 6

| Var | Where | Callers | Public | Flags |
|---|---|---:|---|---|
| `shown-value` | `src/seon/repl.clj:62` | 10 | yes | AV-doc (public) |
| `shown-state` | `src/seon/cluster/squash.clj:58` | 7 | no | INTERNAL |
| `shown-result` | `src/seon/sci/eval.clj:4220` | 6 | yes | AV-doc (public) |
| `final-shown` | `src/seon/turn.clj:2850` | 5 | no | INTERNAL |
| `shown-tests` | `src/seon/turn.clj:5417` | 4 | no | INTERNAL |
| `shown-width` | `src/seon/render/value.clj:585` | 4 | no | INTERNAL |
| `string-shown` | `src/seon/render/value.clj:831` | 4 | no | INTERNAL |
| `diagnostics-shown` | `src/seon/render/value.clj:277` | 3 | no | INTERNAL |
| `expected-shown` | `src/seon/error.clj:1803` | 2 | no | INTERNAL |
| `shown-chain` | `src/seon/render/value.clj:210` | 2 | no | INTERNAL |
| `problem-shown` | `src/seon/error.clj:1822` | 1 | no | INTERNAL |
| `read-shown` | `src/seon/repl.clj:48` | 1 | no | INTERNAL |
| `shown-counts` | `src/seon/render/walk.clj:1142` | 1 | no | INTERNAL |
| `shown-failure` | `src/seon/sci/eval.clj:4326` | 1 | yes | AV-doc (public) |
| `shown-output` | `src/seon/repl.clj:143` | 1 | no | INTERNAL |

### CN-10: fault -> recorded error (folds into seon.error, E-SDELETE)

Top code tokens (occurrences): `fault` 40, `:seon.flow/commit-fault!` 32, `fault/fault!` 27, `commit-fault!` 19, `faults` 19, `:seon.fault/recorded` 13, `seon.fault` 12, `:seon.fault/stop-request!` 10, `fault/print-unrecorded!` 6, `fault-signature` 6, `fault!` 5, `:seon.cluster.eval/faults` 5

| Var | Where | Callers | Public | Flags |
|---|---|---:|---|---|
| `fault!` | `src/seon/fault.clj:358` | 23 | yes | AV-doc (public); E-SDELETE: `seon.fault` folds into `seon.error` (errors.md:311-314, 371; ledger E-SDELETE, CN-10) |
| `fault-values` | `src/seon/render/transcript.clj:274` | 3 | no | INTERNAL |
| `faults-input` | `src/seon/error.clj:2401` | 3 | no | INTERNAL; errors.md S-renders: prose renderers in seon.error deleted (fault-specific) |
| `entrance-fault!` | `src/seon/render/web.clj:2198` | 2 | no | INTERNAL |
| `fault-entities` | `src/seon/error.clj:2387` | 2 | no | INTERNAL; errors.md S-renders: prose renderers in seon.error deleted (fault-specific) |
| `fault-order` | `src/seon/error.clj:2378` | 2 | no | INTERNAL; errors.md S-renders: prose renderers in seon.error deleted (fault-specific) |
| `fault-receipt` | `src/seon/mcp.clj:456` | 2 | no | INTERNAL |
| `open-faults` | `src/seon/problems.clj:235` | 2 | yes | AV-doc (public) |
| `fault-handed-off!` | `src/seon/flow.clj:898` | 1 | no | INTERNAL; PROBABLE: ledger UL-17 deletes the flow WritePort reify (not named) |
| `fault-port` | `src/seon/flow.clj:350` | 1 | no | INTERNAL; PROBABLE: ledger UL-17 deletes the flow WritePort reify (not named) |
| `fault-window` | `src/seon/turn.clj:3051` | 1 | no | INTERNAL; errors.md:287 replaces the temporal fault window |
| `faults-form` | `src/seon/error.clj:2455` | 1 | yes | AV-doc (public); errors.md S-renders: prose renderers in seon.error deleted (fault-specific) |
| `hand-off-fault!` | `src/seon/flow.clj:914` | 1 | yes | AV-doc (public); PROBABLE: ledger UL-17 deletes the flow WritePort reify (not named) |
| `record-faults` | `src/seon/batch.clj:878` | 1 | no | INTERNAL |
| `record-turn-backstop-fault!` | `src/seon/turn.clj:5179` | 1 | no | INTERNAL |
| `record-write-refusal-fault!` | `src/seon/turn.clj:5162` | 1 | no | INTERNAL |
| `render-faults-html` | `src/seon/error.clj:2495` | 1 | yes | AV-doc (public); errors.md S-renders: prose renderers in seon.error deleted (fault-specific) |
| `with-fault-windows` | `src/seon/turn.clj:3071` | 1 | no | INTERNAL; errors.md:287 replaces the temporal fault window |
| `record-fault` | `src/seon/turn/world.clj:94` | 0 | yes | AV-doc (public); DEAD: zero callers (not named by a plan) |
| `render-faults-ai` | `src/seon/error.clj:2481` | 0 | yes | AV-doc (public); errors.md S-renders: prose renderers in seon.error deleted (fault-specific) |

### CN-11: diagnostic -> error (or finding)

Top code tokens (occurrences): `seon.error.refusal/diagnostic` 180, `diagnostic` 86, `refusal/diagnostic` 22, `:seon.cluster.eval/diagnostics` 21, `:seon.await/diagnostic` 16, `diagnostics` 15, `:seon.batch/diagnostics` 14, `error.refusal/diagnostic` 12, `:seon.render.value/diagnostics` 10, `:diagnostic` 7, `shown-diagnostics` 7, `:seon.batch/diagnostic` 4

| Var | Where | Callers | Public | Flags |
|---|---|---:|---|---|
| `diagnostic` | `src/seon/await.clj:28` | 271 | no | INTERNAL; errors cut: ~225 `diagnostic` calls become the one constructor `error` (errors.md:154) |
| `diagnostic` | `src/seon/batch.clj:369` | 271 | yes | AV-doc (public); errors cut: ~225 `diagnostic` calls become the one constructor `error` (errors.md:154) |
| `diagnostic` | `src/seon/cluster/boot.clj:275` | 271 | yes | AV-doc (public); errors cut: ~225 `diagnostic` calls become the one constructor `error` (errors.md:154) |
| `diagnostic` | `src/seon/db.clj:210` | 271 | no | INTERNAL; errors cut: ~225 `diagnostic` calls become the one constructor `error` (errors.md:154) |
| `diagnostic` | `src/seon/error/refusal.clj:630` | 271 | yes | AV-doc (public); errors cut: ~225 `diagnostic` calls become the one constructor `error` (errors.md:154) |
| `diagnostic` | `src/seon/test/platform.clj:98` | 271 | no | INTERNAL; errors cut: ~225 `diagnostic` calls become the one constructor `error` (errors.md:154) |
| `diagnostics-shown` | `src/seon/render/value.clj:277` | 3 | no | INTERNAL |
| `debug-diagnostic` | `src/seon/render/web.clj:654` | 2 | no | INTERNAL |
| `diagnostic-text` | `src/seon/print.cljc:477` | 2 | no | INTERNAL |
| `diagnostic-arglists` | `src/seon/instrument.clj:250` | 1 | no | INTERNAL |
| `diagnostic-present-attrs` | `src/seon/schema.clj:4173` | 1 | no | INTERNAL |
| `diagnostic-schema-keys` | `src/seon/schema.clj:4181` | 1 | no | INTERNAL |
| `flat-diagnostic` | `src/seon/sci/eval.clj:4155` | 1 | no | INTERNAL |
| `with-diagnostic` | `src/seon/turn.clj:2877` | 1 | no | INTERNAL |

### CN-12: test member -> test var

Top code tokens (occurrences): `:seon.test.member/symbol` 47, `:seon.test.member/completed-tx` 23, `:seon.test.member/fail-count` 18, `:seon.test.member/error-count` 18, `:seon.test.member/reach-digest` 17, `:seon.test.member/reasons` 15, `:seon.test.member/terminated-tx` 14, `:seon.test.member/pass-count` 13, `:seon.test.member/alloc-bytes` 12, `:seon.test.member/began?` 12, `:seon.test.member/ended?` 12, `:seon.test.member/error` 7

No Var is named with the word; the word lives in keywords, schema keys and prose.

### CN-13: accretion test -> generative check (`malli.generator/check`)

Top code tokens (occurrences): `:seon.test.accretion/error-group-count` 3, `:seon.test.accretion/error-groups` 3, `:seon.test.accretion.group/ordinal` 3, `seon.test.accretion` 3, `:seon.test.accretion/failure-source` 3, `:seon.test.accretion.group/count` 2, `:seon.test.accretion.group/failures` 2, `:seon.test.accretion.failure/ordinal` 2, `accretion` 2, `:seon.test.accretion/install-refused-error` 2, `:seon.test.accretion/advisories` 2, `:seon.test.accretion.group/shape` 1

No Var is named with the word; the word lives in keywords, schema keys and prose.

### CN-14: gate / gate set -> entry policy / tests reaching the change

Top code tokens (occurrences): `:seon.source/gate-run` 4, `gate` 3, `gate-set-in` 3, `gate-edges` 3, `functions/gate-sets` 3, `gate-names` 2, `gate-sets-in` 2, `:seon.fn/gate-request` 2, `gate-set` 2, `gate-input-files` 2, `gate-input-directories` 2, `function/gate-set` 1

| Var | Where | Callers | Public | Flags |
|---|---|---:|---|---|
| `gate-sets` | `src/seon/fn.clj:2086` | 5 | yes | AV-doc (public) |
| `gate-set` | `src/seon/fn.clj:2159` | 3 | yes | AV-doc (public) |
| `gate-edges` | `src/seon/fn.clj:1950` | 2 | no | INTERNAL |
| `gate-set-in` | `src/seon/fn.clj:1925` | 2 | no | INTERNAL |
| `candidate-gate!` | `src/seon/cluster.clj:2684` | 1 | yes | AV-doc (public) |
| `gate-input-directories` | `src/seon/test/cache.clj:123` | 1 | yes | AV-doc (public) |
| `gate-input-files` | `src/seon/test/cache.clj:118` | 1 | yes | AV-doc (public) |
| `gate-sets-in` | `src/seon/fn.clj:1987` | 1 | no | INTERNAL |

### CN-15: custody -> checked-out branch

Top code tokens (occurrences): `db/call-with-custody` 34, `custody` 32, `::custody` 14, `unsupplied-custody-error` 5, `:seon.sci.eval/custody` 4, `:seon.db/custody-request` 3, `call-with-custody` 2, `custody-database` 2, `message-custody` 1, `call-without-custody` 1, `'seon.db/unsupplied-custody-error` 1, `:custody` 1

| Var | Where | Callers | Public | Flags |
|---|---|---:|---|---|
| `unsupplied-custody-error` | `src/seon/db.clj:2767` | 4 | no | INTERNAL |
| `call-with-custody` | `src/seon/db.clj:443` | 3 | yes | AV-doc (public) |
| `custody-database` | `src/seon/error.clj:1979` | 1 | no | INTERNAL |
| `message-custody` | `src/seon/context.clj:35` | 1 | yes | AV-doc (public) |
| `call-without-custody` | `src/seon/db.clj:473` | 0 | yes | AV-doc (public); DEAD: zero callers (not named by a plan) |

### CN-16: supply/supplied -> defaults (Malli `:default`, `default-value-transformer`)

Top code tokens (occurrences): `supplied` 87, `:seon.call-preparation/supplier-symbol` 24, `supplier` 18, `supplied-projection-state` 18, `supplied-projection` 15, `supplied-basis` 8, `:seon.call-preparation/supplied-defaults` 6, `:seon.call-preparation/supplied-default` 5, `supply-from-world` 5, `supplied?` 5, `world-supplied` 4, `supply-from-row` 4

| Var | Where | Callers | Public | Flags |
|---|---|---:|---|---|
| `supply` | `src/seon/call_preparation.clj:864` | 41 | yes | AV-doc (public) |
| `supply-from-world` | `src/seon/call_preparation.clj:899` | 4 | no | INTERNAL |
| `supplied-agent-id` | `src/seon/env.clj:338` | 3 | yes | AV-doc (public) |
| `supply-from-row` | `src/seon/call_preparation.clj:923` | 3 | no | INTERNAL |
| `supply-index` | `src/seon/call_preparation.clj:630` | 2 | no | INTERNAL |
| `coherent-supplier` | `src/seon/call_preparation.clj:184` | 1 | no | INTERNAL |
| `supplied-connection` | `src/seon/db.clj:2804` | 1 | yes | AV-doc (public) |
| `supplied-database-value` | `src/seon/db.clj:2777` | 1 | yes | AV-doc (public) |
| `supplied-entry-problems` | `src/seon/instrument.clj:273` | 1 | no | INTERNAL |
| `supplied-arguments` | `src/seon/call_preparation.clj:669` | 0 | yes | AV-doc (public); DEAD: zero callers (not named by a plan) |
| `supplied-context` | `src/my/program.clj:395` | 0 | yes | AV (my.*); DEAD: zero callers (not named by a plan) |
| `supplied-map-entries` | `src/seon/call_preparation.clj:650` | 0 | yes | AV-doc (public); DEAD: zero callers (not named by a plan) |

### CN-17: adopt/adoption -> reload (`require :reload`, clj-reload)

Top code tokens (occurrences): `:seon.reconcile/adopt-identities` 24, `adopted` 17, `attribute-adoption` 4, `adoption` 4, `adoption?` 4, `adopt-identities` 4, `:seon.source/adoption-result` 3, `:seon.cluster/adoption` 3, `adopt-entities!` 3, `::adopted-allocated-bytes` 3, `::adopted-cpu-ns` 3, `adopt-tasks-tx` 3

| Var | Where | Callers | Public | Flags |
|---|---|---:|---|---|
| `adopt-arm` | `src/seon/sci/kernel.clj:668` | 7 | yes | AV-doc (public) |
| `attribute-adoption` | `src/seon/cluster.clj:659` | 5 | no | INTERNAL |
| `adopt-entities!` | `src/seon/cluster.clj:2484` | 2 | no | INTERNAL |
| `adopt-tasks-tx` | `src/seon/task.clj:351` | 2 | yes | AV-doc (public) |
| `rehearsal-adoption!` | `src/seon/cluster.clj:2811` | 2 | no | INTERNAL |
| `adopt-cluster-declarations!` | `src/seon/cluster/agent.clj:1057` | 1 | no | INTERNAL |
| `adoption-guard-tx` | `src/seon/cluster.clj:2452` | 1 | no | INTERNAL |
| `adoption-record` | `src/seon/cluster.clj:2521` | 1 | no | INTERNAL |
| `adopt-tasks-call` | `src/seon/task.clj:378` | 0 | yes | AV-doc (public); DEAD: zero callers (not named by a plan) |
| `adoption-tx` | `src/seon/fn.clj:4119` | 0 | yes | AV-doc (public); DEAD: zero callers (not named by a plan) |

### CN-18: roster -> registry (or the collection's own word)

Top code tokens (occurrences): `roster` 65, `registry/roster` 10, `parent-roster` 7, `child-roster!` 3, `::roster-released` 3, `entry-roster` 3, `:seon.flow.plan/roster` 2, `roster-facts` 2, `:my.agent/roster` 1, `:roster` 1, `:seon.cluster.registry/roster` 1

| Var | Where | Callers | Public | Flags |
|---|---|---:|---|---|
| `roster` | `src/seon/cluster/registry.clj:111` | 232 | yes | AV-doc (public) |
| `child-roster!` | `src/seon/cluster/agent.clj:653` | 4 | no | INTERNAL |
| `entry-roster` | `src/seon/cluster/agent.clj:1927` | 2 | no | INTERNAL |
| `roster-facts` | `src/seon/cluster/agent.clj:1775` | 1 | yes | AV-doc (public) |

### CN-20: pass / batch -> eval request (`seon.repl`); clojure.test's `:pass` is NOT retired

Top code tokens (occurrences): `batch` 127, `:seon.batch/ordinal` 53, `:seon.batch/request` 50, `:seon.batch/sink` 37, `:seon.batch/change` 33, `seon.batch/*world*` 32, `:seon.batch/folding` 31, `:seon.batch/world` 31, `:seon.batch/text` 26, `:seon.batch/version` 24, `:seon.batch/working` 24, `:seon.batch/entry` 22

| Var | Where | Callers | Public | Flags |
|---|---|---:|---|---|
| `settle-batch!` | `src/seon/turn.clj:3115` | 6 | no | INTERNAL |
| `settle-batch-refusal!` | `src/seon/turn.clj:3395` | 6 | no | INTERNAL |
| `render-pass` | `src/seon/render/web.clj:2277` | 5 | no | INTERNAL |
| `runtime-analysis-batch` | `src/seon/fn.clj:1007` | 4 | no | INTERNAL |
| `receipt-settle-batch-tx` | `src/seon/turn.clj:906` | 3 | yes | AV-doc (public) |
| `blob-pass` | `src/seon/fs/jvm.clj:582` | 2 | no | INTERNAL |
| `receipt-settle-batch-call` | `src/seon/turn.clj:894` | 2 | yes | AV-doc (public) |
| `whole-file-pass` | `src/seon/fs/jvm.clj:337` | 2 | no | INTERNAL |
| `batch-limit` | `src/seon/test.clj:2465` | 1 | yes | AV-doc (public) |
| `evaluate-batch` | `src/seon/batch.clj:914` | 1 | yes | AV-doc (public) |
| `window-pass` | `src/seon/fs/jvm.clj:297` | 1 | no | INTERNAL |

### CN-21: situation / disposition (turn stored state) deleted, not renamed

Top code tokens (occurrences): `:seon.turn.work/situation` 23, `:my.turn/disposition` 13, `situation` 12, `disposition` 8, `:seon.turn/disposition` 7, `:seon.cluster.boot/disposition` 6, `:seon.effect/disposition` 3, `:seon.test.selection/disposition` 3, `early-disposition?` 2, `:seon.agent/situation` 1, `'seon.bootstrap/situation` 1, `situation-form` 1

| Var | Where | Callers | Public | Flags |
|---|---|---:|---|---|
| `disposition` | `src/seon/turn.clj:2702` | 79 | yes | AV-doc (public) |
| `situation` | `src/seon/bootstrap.clj:73` | 51 | yes | AV-doc (public) |
| `early-disposition?` | `src/seon/turn.clj:4215` | 1 | no | INTERNAL; ledger CN-21 deletes turn situation/disposition (zero or one caller) |
| `render-situation-ai` | `src/seon/cluster/agent.clj:378` | 0 | yes | AV-doc (public); ledger CN-21 deletes turn situation/disposition (zero or one caller) |
| `situation-form` | `src/seon/cluster/agent.clj:253` | 0 | yes | AV-doc (public); ledger CN-21 deletes turn situation/disposition (zero or one caller) |

### CN-22: overlay -> branch definitions / overrides

Top code tokens (occurrences): `overlay` 20, `:seon.config/agent-overlay` 13, `ai/agent-overlay` 9, `*candidate-forms-overlay*` 9, `:seon.test.run/overlay-input-digest` 8, `:seon.config/overlay-keys` 4, `interpret-overlay!` 4, `overlay-keys` 3, `derive-overlay-plan` 3, `store/open-overlay!` 2, `:seon.store/overlay` 2, `:seon.store/overlay-configuration` 2

| Var | Where | Callers | Public | Flags |
|---|---|---:|---|---|
| `derive-overlay-plan` | `src/seon/sci/eval.clj:2872` | 3 | no | INTERNAL |
| `interpret-overlay!` | `src/seon/sci/eval.clj:3055` | 3 | no | INTERNAL |
| `overlay-plan` | `src/seon/sci/eval.clj:2909` | 2 | no | INTERNAL |
| `program-overlay-tx` | `src/seon/test.clj:1855` | 2 | no | INTERNAL |
| `agent-overlay` | `src/seon/ai.clj:320` | 1 | yes | AV-doc (public) |
| `derive-overlay-ctx` | `src/seon/sci/eval.clj:3629` | 1 | no | INTERNAL |
| `held-overlay` | `src/seon/config.clj:823` | 1 | no | INTERNAL |
| `open-overlay!` | `src/seon/cluster/store.clj:681` | 1 | yes | AV-doc (public) |
| `overlay!` | `src/seon/sci/eval.clj:3198` | 1 | no | INTERNAL |
| `overlay-weight` | `src/seon/sci/eval.clj:3619` | 1 | no | INTERNAL |

### CN-24: severity -> :level (clj-kondo finding keys)

Top code tokens (occurrences): `:seon.health/severity` 4, `:seon.health/faulted-severity` 2

No Var is named with the word; the word lives in keywords, schema keys and prose.

### CN-25: row -> entity / datom / transaction; markdown entry; table test case

Top code tokens (occurrences): `row` 390, `rows` 184, `:seon.program/row` 37, `program/row-identity` 36, `namespace-row` 28, `:seon.program/rows` 22, `end-row` 20, `first-row` 20, `:row` 19, `function-rows` 18, `row-bindings` 17, `namespace-rows` 14

| Var | Where | Callers | Public | Flags |
|---|---|---:|---|---|
| `row` | `src/seon/problems.clj:552` | 946 | no | INTERNAL |
| `namespace-row` | `src/seon/sci/eval.clj:2620` | 30 | no | INTERNAL |
| `row-bindings` | `src/seon/sci/eval.clj:698` | 21 | no | INTERNAL |
| `function-rows` | `src/seon/sci/eval.clj:2386` | 20 | no | INTERNAL |
| `namespace-rows` | `src/seon/turn.clj:544` | 14 | no | INTERNAL |
| `source-rows` | `src/seon/fn.clj:1579` | 13 | yes | AV-doc (public) |
| `source-rows` | `src/seon/turn.clj:573` | 13 | no | INTERNAL |
| `canonical-row` | `src/seon/program.cljc:1438` | 5 | yes | AV-doc (public) |
| `row-identity` | `src/seon/program.cljc:462` | 5 | yes | AV-doc (public) |
| `overridden-row?` | `src/seon/sci/eval.clj:1339` | 4 | no | INTERNAL |
| `parsed-rows` | `src/seon/schema.clj:3000` | 4 | no | INTERNAL |
| `receipt-row` | `src/seon/turn.clj:810` | 4 | no | INTERNAL |
| `row-definition-digest` | `src/seon/sci/eval.clj:1292` | 4 | no | INTERNAL |
| `function-rows-in` | `src/seon/sci/eval.clj:2924` | 3 | no | INTERNAL |
| `host-bound-row?` | `src/seon/sci/eval.clj:1355` | 3 | no | INTERNAL |
| `install-row!` | `src/seon/sci/eval.clj:1393` | 3 | yes | AV-doc (public) |
| `program-row-identity` | `src/seon/sci/eval.clj:717` | 3 | no | INTERNAL |
| `row-tx` | `src/seon/turn.clj:977` | 3 | yes | AV-doc (public) |
| `supply-from-row` | `src/seon/call_preparation.clj:923` | 3 | no | INTERNAL |
| `attempt-rows-tx` | `src/seon/task.clj:619` | 2 | yes | AV-doc (public) |
| `authored-rows` | `src/seon/sci/eval.clj:2862` | 2 | no | INTERNAL |
| `binding-rows` | `src/seon/sci/eval.clj:666` | 2 | no | INTERNAL |
| `cell-rows` | `src/seon/db.clj:4194` | 2 | no | INTERNAL |
| `cluster-row` | `src/seon/cluster/status.clj:33` | 2 | no | INTERNAL |
| `entity-row` | `src/seon/schema.clj:3256` | 2 | no | INTERNAL |
| `evaluate-row` | `src/seon/turn.clj:4260` | 2 | no | INTERNAL |
| `file-rows` | `src/seon/cluster/source.clj:1190` | 2 | no | INTERNAL |
| `install-evaluated-rows!` | `src/seon/sci/eval.clj:1750` | 2 | yes | AV-doc (public) |
| `list-row` | `src/seon/render/value.clj:655` | 2 | no | INTERNAL |
| `match-row` | `src/my/program.clj:756` | 2 | no | AV (my.*) |
| `public-rows` | `src/seon/render/ns.clj:222` | 2 | no | INTERNAL |
| `schema-row` | `src/seon/test/accretion.clj:35` | 2 | yes | AV-doc (public) |
| `schema-row-in` | `src/seon/test/accretion.clj:55` | 2 | yes | AV-doc (public) |
| `stored-namespace-row` | `src/seon/turn/batch.clj:226` | 2 | no | INTERNAL |
| `base-namespace-rows` | `src/seon/sci/eval.clj:2629` | 1 | no | INTERNAL |
| `contract-facts-row` | `src/seon/fn.clj:3247` | 1 | yes | AV-doc (public) |
| `declaration-row` | `src/seon/program.cljc:1468` | 1 | yes | AV-doc (public) |
| `delivery-rows` | `src/seon/turn.clj:2780` | 1 | no | INTERNAL |
| `failure-rows` | `src/seon/render/test.clj:111` | 1 | no | INTERNAL |
| `organic-row` | `src/seon/web/search.clj:5` | 1 | no | INTERNAL |
| `publish-row` | `src/seon/turn.clj:4317` | 1 | no | INTERNAL |
| `read-row` | `src/seon/turn.clj:4229` | 1 | no | INTERNAL |
| `show-row` | `src/seon/turn.clj:4288` | 1 | no | INTERNAL |
| `turn-rows-state` | `src/seon/turn.clj:4222` | 1 | no | INTERNAL |
| `committed-row?` | `src/seon/sci/eval.clj:1071` | 0 | yes | AV-doc (public); DEAD: zero callers (not named by a plan) |
| `deletion-row` | `src/seon/program.cljc:1636` | 0 | yes | AV-doc (public); DEAD: zero callers (not named by a plan) |
| `namespace-context-row` | `src/seon/sci/eval.clj:824` | 0 | no | INTERNAL; DEAD: zero callers (not named by a plan) |

### CN-26: land/landed/landing -> merge / merged / unmerged; commit for own work

Top code tokens (occurrences): `landed` 22, `:seon.source/landed` 3, `unlanded-ends` 3, `land-ends!` 3, `landing` 3, `land-end!` 3, `land-held!` 2, `'seon.cluster.source/land!` 2, `:seon.task/landed` 2, `:seon.task/landed-git` 2, `landed-occurrence` 2, `source-land!` 2

| Var | Where | Callers | Public | Flags |
|---|---|---:|---|---|
| `land-end!` | `src/seon/turn.clj:2254` | 3 | no | INTERNAL |
| `land!` | `src/seon/cluster/source.clj:1762` | 2 | yes | AV-doc (public) |
| `land-ends!` | `src/seon/cluster/agent.clj:2147` | 2 | yes | AV-doc (public); hybrid-push.md §5 deleted list; U3 |
| `landing-tx` | `src/seon/turn.clj:2229` | 2 | no | INTERNAL |
| `unlanded-ends` | `src/seon/cluster/agent.clj:1997` | 2 | yes | AV-doc (public); hybrid-push.md §5 deleted list; U3 |
| `land-held!` | `src/seon/cluster/source.clj:1720` | 1 | no | INTERNAL |
| `landed-occurrence` | `src/seon/fault.clj:67` | 1 | no | INTERNAL; E-SDELETE: `seon.fault` folds into `seon.error` (errors.md:311-314, 371; ledger E-SDELETE, CN-10) |
| `land-ended!` | `src/seon/turn.clj:2279` | 0 | yes | AV-doc (public); DEAD: zero callers (not named by a plan) |

### CN-27: frontier -> ready

Top code tokens (occurrences): `frontier` 38, `frontier-schemas` 5, `frontier-rules` 5, `task/frontier-rules` 2, `frontier-datoms` 2, `frontier-edges` 2, `next-frontier` 2, `task/frontier` 1

| Var | Where | Callers | Public | Flags |
|---|---|---:|---|---|
| `frontier` | `src/seon/task.clj:885` | 45 | yes | AV-doc (public) |
| `frontier-rules` | `src/seon/task.clj:871` | 4 | yes | AV-doc (public) |
| `frontier-datoms` | `src/seon/render/walk.clj:909` | 1 | no | INTERNAL |
| `frontier-edges` | `src/seon/render/walk.clj:921` | 1 | no | INTERNAL |


## 4. Slated for deletion or folding (by plan)

Each row is an identifier a current plan removes or folds into another name. Renaming these is wasted work; convert their
callers as part of the deletion. "Named" = the plan lists the symbol; "probable" = the plan's row covers the code that
holds the identifier but does not name it, so verify before sweeping.

| Identifier(s) | Where | Status | Source |
|---|---|---|---|
| `seon.fault` namespace: `fault!`, `commit-fault!`, `record-fault!`, `landed-occurrence`, `carried-receipt`, `fault-signature`, `faults-*` (about 127 hits in `src/seon/fault.clj`, 451 lines) | `src/seon/fault.clj` | Named: the remainder folds into `seon.error`; fault.clj goes to 0 lines | errors.md:311-314, 371 (S-delete); ledger E-SDELETE:343, CN-10:637 |
| `:seon.fault/*` keys (`recorded`, `folded`, `folded?`, `committed?`, `stop-request!`, `delivered-to`, `context`); `:seon.error.fault/*`; `:seon.cluster.eval/faults`; `:seon.task/faults`; `:seon.problems/open-fault` | `seon.fault.edn`, `seon.cluster.eval.edn`, `seon.task.edn`, `seon.problems.edn` | Named: the fault vocabulary retires; old rows not migrated | errors.md:313-314 |
| `fault-window`, `with-fault-windows` (turn.clj:3051, 3071) | `src/seon/turn.clj` | Named: replaced by the live-value error path | errors.md:287 |
| `render-faults-ai`, `render-faults-html`, `faults-form`, `faults-input`, `fault-order`, `fault-entities` (error.clj) | `src/seon/error.clj` | Named class: prose renderers in seon.error deleted (fault-specific ones are in the class) | errors.md S-renders (line ~368) |
| `diagnostic` constructor (`seon.error.refusal/diagnostic`, about 225 call sites), `:diagnostics` reply key | `src/seon/error/refusal.clj`, reply keys | Named: one constructor `error`; `:diagnostics` joins `:errors` (inferred from errors.md:285; verify) | errors.md:154, 285; ledger E-CUT |
| `land-ends!`, `unlanded-ends` (agent.clj:2147, 1997) | `src/seon/cluster/agent.clj` | Named | hybrid-push.md §5 (deleted list), U3 |
| `settle-kept-branches!` (agent.clj:2124) | `src/seon/cluster/agent.clj` | Named: the deleted marker query | hybrid-push.md §5 |
| `land-end!`, `land-ended!` (turn.clj:2254, 2279) | `src/seon/turn.clj` | Probable: the `end-task!` derived lifecycle (U3) replaces the land path; `land-ended!` has 0 callers | hybrid-push.md U3 (verify) |
| `:seon.turn.work/situation`, `:seon.turn/disposition`, `early-disposition?`, `situation-form`, `render-situation-ai` | `seon.turn.work.edn`, `seon.turn.edn`, `src/seon/turn.clj`, `src/seon/cluster/agent.clj` | Named: CN-21 deletes the stored state (the last two are 0-caller) | ledger CN-21 |
| `arm-var!` (instrument.clj:989) and the instrumentation arming loop | `src/seon/instrument.clj` | Probable: UL-12 replaces the arming loop with Malli's options | ledger UL-12, UL-3b |
| `fault-port`, `hand-off-fault!`, `fault-handed-off!`, `commit-fault!` (flow.clj) | `src/seon/flow.clj` | Probable: UL-17 deletes the WritePort reify that carries them | ledger UL-17 (flow.clj:304-322) |
| `supply*`, `supply-from-world`, `supply-from-row`, `supplier` (call-preparation) | `src/seon/call_preparation.clj` | Not slated; waits on the §3.22 decision (a deletion makes CN-16 moot) | ledger CN-16 deps |
| `:seon.squash/*` keys, `squash-merge!`, `conflict-task!`, `copy!` | `src/seon/cluster/squash.clj` | Named deletions; no retired word in these names, so they are not counted above. `shown-state` (private, squash.clj:58) lives in the namespace that hybrid-push §5 shrinks | hybrid-push.md §5 |
| `acquire-arm`, `adopt-arm`, `current-arm`, `release-arm!`, `detached-arm`, `own-arm`, `with-arm`, `arm?` (sci/kernel.clj) | `src/seon/sci/kernel.clj` | Not named. hybrid-immutable-env names `acquire!`, `base-bindings`, `copy-base-ctx` beside them; verify with the Opus lane before sweeping | hybrid-immutable-env.md (deleted list) |
| `held arm` (hybrid-step-semantics P10) | not located by a retired-word name in `src/seon/cluster/host.clj` | Not found: P10 deletes "the held arm"; its identifier is not in current src under a retired word | hybrid-step-semantics.md P10 |

The hybrid-immutable-env deleted list (`acquire!`, `acquired-database?`, `loaded-program-current?`, `copy-base-ctx`,
`fork-cluster-ctx`, `branch-ctx`, `program-equal`, `base-bindings`, `same-program-root?`, `transfer-evaluated-roots!`)
carries no retired word, so none of its names appears in §3. The ledger's §3.22 rows (UL-1, UL-3a, UL-3b, UL-12, UL-13,
UL-16, UL-17, UL-22, UL-25) delete code next to CN identifiers but name no retired word; they change the counts in §3 only
where the probable rows above point.

## 5. Not sweep targets, and entries the vocabulary does not cover

- **clojure.test vocabulary, keep.** `:pass` (test/runner.clj:258; `:seon.test.member/pass-count`, `fail-count`,
  `error-count`) are clojure.test report types and counters. CN-20 retires *batch* and *pass* only where they mean one
  submission of forms.
- **Algorithm passes, not eval requests (CN-20).** `src/seon/fs/jvm.clj`: `whole-file-pass` (337), `blob-pass` (582),
  `window-pass` (297) and their `pass` locals. Keep.
- **Verify before CN-20 sweeps.** `render-pass` (src/seon/render/web.clj:2277, 5 callers) and `runtime-analysis-batch`
  (src/seon/fn.clj:1007, 4 callers): read each to decide between eval request and another sense.
- **Local `row` bindings (CN-25).** Most of the 1,262 code hits are local binding names in `sci/eval.clj` (436 hits in that
  file alone), `turn.clj` and `fn.clj`. Rename only the identifiers that are entities, datoms, schema keys or Vars.
- **Gate (CN-14).** `:gate` is a value named in CLAUDE.md (`:gate`/`:warn` entry policy) and stays. The merge-side "gate"
  prose (`my/program.clj`, `fn.clj`) moves to "tests reaching the change" with the row.
- **`:my.turn/disposition` (AV, CN-21 family).** Not named by CN-21 (which names `:seon.turn/disposition`); owner decision.
- **CN-23** (`*1 *2 *3 *e`) has no pattern in vocabulary.edn; no src hit. **CN-19** (run-id) has no vocabulary pattern;
  `run-id` appears under turn.clj and `seon.turn.work.edn`, not counted here.
- **CN-8** (envelope): 3 prose hits in src, 0 code hits; nothing to rename in code.
- **CN-4** (admission): entry policy and test-request admission keep the word (ledger CN-4). Only `admit`, `admitted`,
  `admission` names that mean value validation move; the counts in §3 include the kept sense.
- **STORED counts are approximate** (§0): `:seon.schema/projection` and similar keys are schema keys in files with
  `:seon.db/` markers; some are request-map keys that are never Datahike attributes.

## 6. Collisions with current bin/lane claims

Claims from `bin/lane status` at the time of this audit:

- **`program-context`**: `src/seon/sci/eval.clj`, `test/seon/sci/eval_test.clj`, `resources/seon/tasks/seon.sci.eval.edn`,
  `docs/research/agent-platform/2026-10-12/hybrid-immutable-env.md`. `sci/eval.clj` carries CN-25 436, CN-3 323, CN-4 204,
  CN-1 83, CN-10 47, CN-11 46, CN-16 43, CN-9 38, CN-22 22, CN-20 10. Slices **CN-9**, **CN-22**, **CN-1** and CN-25's eval
  part wait for this lane or ride inside it. The test file adds CN-4 155, CN-3 131, CN-9 34. The task file adds CN-25 52,
  CN-20 35, CN-22 22, CN-7 20.
- **`docs-drift`**: 12 `.agents/skills/*` files, `docs/seon/plan.md`, `docs/research/agent-platform/2026-10-11/hybrid-start-a-flow.md`,
  `2026-10-11/hybrid-agent-world.md`, `2026-10-12/hybrid-step-semantics.md`. These hold CN-26 36 in plan.md, CN-20 16, CN-14 7,
  CN-5 5, CN-27 3 (plan.md, relevant to slice 2), CN-5 12 in `clojure-testing/SKILL.md` (slice 5 docs), CN-20 13 in
  `data-oriented-clojure/SKILL.md`, and CN-3 28, CN-22 18, CN-5 13, CN-1 13 in hybrid-start-a-flow.md. Each slice's docs
  edits land with its commit; coordinate with `docs-drift` on those files.
- **`root-docs`**: `AGENTS.md` (CLAUDE.md is its symlink; every slice's CLAUDE.md patch goes here), `script/seon/vocabulary.edn`
  (CN-20 4, CN-26 2, the word list itself; keep until its rows land), `resources/seon/tasks/seon.vocabulary.edn`,
  `test/seon/vocabulary_test.clj`.
- **`astra-one-system`, `astra-review-merge-base`, `root-report`**: docs only; no slice collides.
- **No top-8 slice touches a claimed src file except slice 8 (CN-9, `sci/eval.clj`).**

Lexer counts for the Markdown files are approximate (§0). The `my.vocabulary` tool is the authority for living-doc counts.

## 7. Zero-caller retired-word Vars (candidates to delete with their row, not renamed)

The bare-name count is 0 across `src`, `test` and `script`. A REPL-only caller would not appear here; check the agents' SCI
namespaces before deleting.

| CN | Var | Where |
|---|---|---|
| CN-1 | `arm-generator` | `src/seon/sci/kernel.clj:571` |
| CN-10 | `render-faults-ai` | `src/seon/error.clj:2481` |
| CN-10 | `record-fault` | `src/seon/turn/world.clj:94` |
| CN-15 | `call-without-custody` | `src/seon/db.clj:473` |
| CN-16 | `supplied-context` | `src/my/program.clj:395` |
| CN-16 | `supplied-map-entries` | `src/seon/call_preparation.clj:650` |
| CN-16 | `supplied-arguments` | `src/seon/call_preparation.clj:669` |
| CN-17 | `adoption-tx` | `src/seon/fn.clj:4119` |
| CN-17 | `adopt-tasks-call` | `src/seon/task.clj:378` |
| CN-21 | `situation-form` | `src/seon/cluster/agent.clj:253` |
| CN-21 | `render-situation-ai` | `src/seon/cluster/agent.clj:378` |
| CN-25 | `deletion-row` | `src/seon/program.cljc:1636` |
| CN-25 | `namespace-context-row` | `src/seon/sci/eval.clj:824` |
| CN-25 | `committed-row?` | `src/seon/sci/eval.clj:1071` |
| CN-26 | `land-ended!` | `src/seon/turn.clj:2279` |
| CN-3 | `advance-projection!` | `src/seon/env.clj:121` |
| CN-3 | `projection-complete?` | `src/seon/error.clj:2668` |
| CN-3 | `call-with-projection` | `src/seon/schema.clj:1150` |
| CN-3 | `call-with-projection-state` | `src/seon/schema.clj:1157` |
| CN-3 | `advance-projection` | `src/seon/schema.clj:3300` |
| CN-3 | `projection-attributes` | `src/seon/schema.clj:3352` |
| CN-3 | `projection-without-schema` | `src/seon/schema.clj:3691` |
| CN-3 | `projection-with-function-contract` | `src/seon/schema.clj:3723` |
| CN-3 | `projection-identity` | `src/seon/schema.clj:4146` |
| CN-3 | `identity-only-projection` | `src/seon/schema.clj:4159` |
| CN-3 | `projection-state-generator` | `src/seon/sci/eval.clj:197` |
| CN-4 | `close-admissions!` | `src/seon/cluster/host.clj:169` |
| CN-4 | `admission-from-asserting-transaction` | `src/seon/schema.clj:968` |
| CN-4 | `schema-scoped-admissions` | `src/seon/schema.clj:2904` |
| CN-4 | `admit-declarations!` | `src/seon/schema.clj:4333` |
| CN-4 | `admit-partitioned` | `src/seon/sci/admit.clj:859` |
| CN-4 | `system-run-if-admitted-call` | `src/seon/turn.clj:758` |
| CN-5 | `world-members` | `src/seon/batch.clj:47` |
| CN-5 | `world-counter?` | `src/seon/profile.clj:214` |
| CN-5 | `with-world-ctx` | `src/seon/sci/kernel.clj:543` |
| CN-6 | `errored-receipts` | `src/seon/problems.clj:279` |
| CN-7 | `plan-settlement` | `src/seon/turn.clj:1824` |
| CN-7 | `settlement-connection` | `src/seon/turn/world.clj:87` |

## 8. Namespaces that carry a retired word

No retired-word namespace lacks live requirers; `seon.fault` is the one slated for folding. "Requiring files" counts src,
test and script files that name the namespace in a `require` or a qualified symbol.

| Namespace | File | Requiring files | Entries | Status |
|---|---|---:|---|---|
| `seon.batch` | src/seon/batch.clj | 54 | CN-20, CN-5, CN-7, CN-9, CN-11, CN-16 | live; slice 1 |
| `seon.turn.batch` | src/seon/turn/batch.clj | 14 | CN-20, CN-7, CN-9, CN-11 | live; slice 1 |
| `seon.sci.batch` | src/seon/sci/batch.clj | 3 | CN-20, CN-5, CN-1 | live; slice 1 |
| `seon.turn.world` | src/seon/turn/world.clj | 25 | CN-5, CN-6, CN-7, CN-10, CN-15 | live; slice 5 |
| `seon.sci.arm` | src/seon/sci/arm.clj | 5 | CN-1, CN-3, CN-4, CN-5, CN-20 | live; RP-3, RP-4 touch it; no plan deletes it |
| `seon.fault` | src/seon/fault.clj | 32 | CN-10, CN-6, CN-7, CN-9, CN-11 | slated: folds into `seon.error` (§4) |
| `seon.test.accretion` | src/seon/test/accretion.clj | 8 | CN-13, CN-14, CN-9, CN-1 | live; PR-13 touches it |
| `seon.cluster.squash` | src/seon/cluster/squash.clj | (see §4) | CN-9 | shrinks under hybrid-push §5 |
| `seon.call-preparation` | src/seon/call_preparation.clj | (see §3) | CN-16, CN-1, CN-4 | waits on §3.22 |
| `seon.instrument` | src/seon/instrument.clj | (see §3) | CN-1, CN-11, CN-16 | UL-3, UL-12, UL-13 reshape it |

## 9. Reproduction

The scan, counts and tables were produced from scratch scripts outside the repository (lexer, bare-name caller counts,
schema-key scan, lane claims). Re-run them with the owner's `script/seon/vocabulary.clj` for living docs. No file under
`src`, `test` or `resources` was edited; `bin/lane status` and `git status` were read only.
