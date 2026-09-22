---
type: reference
status: complete; read-only review, no code or test executed
created: 2026-09-22
tags: [agent-platform, lane-b3, errors, kind-cut, diff-review]
---

# B3 commit 4 — kind/class cut, read-only diff review

Range `aba6d445e~1..bc70a82e3` (21 commits, 192 files, +4,195/−1,818).
Read end to end: [AGENTS.md](../../../AGENTS.md) "Five design laws" and
"Data, tasks and admission";
[lane-b3-errors-tasks-dials.md](../../prds/agent-platform/plan/lane-b3-errors-tasks-dials.md)
§0 "Kinds, classes, guards" and §5 row 4;
[lane-b3-kind-cut-2026-09-21.md](../../prds/agent-platform/landing/lane-b3-kind-cut-2026-09-21.md)
(1,931 lines). Every `src/` hunk read; `test/` and `resources/` sampled.
No JVM, test, `bin/seon` or branch operation was run.

## Findings

| # | Sev | file:line (new tree) | Rule broken | Smallest correct fix |
|---|---|---|---|---|
| 1 | blocker | `src/my/program.clj:60,89,115` and 24 more (27 sites) | (c) — the branch must read the PRODUCER's declared distinguishing member. Producers are `seon.db/q`, `seon.db/pull`, `seon.fn/gate-set`, all declaring `:seon.db/error-result` (`resources/seon/schemas/seon.db.edn:6-30`, ~60 alternatives). The guard reads 2. `:seon.db.read/error` (`seon.db.read.edn:4`), `:seon.db.availability/error`, `:seon.db.write/error` carry neither member, so they now fall into the success arm, which queries/retracts/installs. Before the cut `:seon.error/kind` caught all of them. This is the project's named class: absence of signal read as health | Narrow `q`/`pull`'s declared return to the alternatives they actually produce, then read the one member that union guarantees; do not enumerate a subset at the call site |
| 2 | blocker | `src/seon/agent.clj:135-138`, continuation at `:146` | Same rule, concrete instance. `config/effective` (`src/seon/config.clj:871-876`) declares `:seon.config/error` whose distinguishing member is `:seon.config/error-key` (`seon.config.edn:57-64`). The `some` guard reads `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:seon.config/missing-effective` only. A config refusal is not `nil`, so it reaches `(ai/settings defaults overrides)` and is silently treated as settings. The lane's own inventory records the three-member list (landing note line 1309) | Add `(:seon.config/error-key %)` to the `some` predicate |
| 3 | defect | `src/seon/render/transcript.clj:2348,2352,2358,2361,2367` (the same 20-member chain five times over one `evaluations` binding); also `:1621,:1627,:1728`; `src/seon/plan.clj:1632`; 74 such chains repo-wide | "No hand-maintained lists"; §0 "Union copies" drives 14 copies to 0 — this cut created dozens more, lines up to ~1,000 chars. Rule (b): these render sites only forward, so the guard should be gone, not expanded | Delete the guard at forwarding sites; where a genuine decision remains, bind the refusal once per function |
| 4 | defect | `src/seon/db.clj:1030,1252,2204,2270` | (a) — "one declared schema per failure over `:seon.error/base`". Four distinct failures (`unknown-read-operation`, `unreadable-declarations`, `disagreeing-pull-schema`, `invalid-pulled-result`) lost their only discriminator; all four now carry just `:seon.db/invalid-read true`. Their identity survives solely in `:seon.error/diagnostic-cause`, a key §1 drives to 0. Tests collapsed to match (`test/seon/db_test.clj`, three `(is (= :seon.db/… kind))` → the same `(is (true? (:seon.db/invalid-read …)))`) | Declare four `…-error` schemas over `:seon.error/base` in `resources/seon/schemas/seon.db.read.edn`, each with its own member; restore the distinguishing assertions |
| 5 | defect | `src/seon/turn.clj:3480-3484` (`phase`) | (5)/(d) — `:seon.schema/value` IS `[:any {…}]` (`resources/seon/schemas/seon.schema.edn:44`). `[:or :seon.schema/value :seon.error/base :seon.turn.loop/phase-failed-error]` therefore validates everything; the `:seon.turn.loop/phase-failed` reads at its six callers have no contract backing | Drop `:seon.schema/value` from the output `:or` and declare the callback's result schema, or state plainly that `phase` is unchecked |
| 6 | defect | `resources/seon/schemas/seon.render.web.edn:183` | "Weakening an output is breakage"; and this is another copy of a union the plan drives to 0. `:seon.render.web/context-error` gained six alternatives by hand. Consumers changed in the same commit (`683fc9c20`), so item (6) of the hunt is procedurally clean | Derive the alternatives from the callables it forwards (the producing-contract rule) instead of listing them |
| 7 | defect | `test/seon/test_runner_test.clj:1504` | (7) — `(is (= {} (:seon.operator/exception-data (:seon.error/data failure))))`: the fixture's nested data was emptied and the assertion now compares `{}` with `{}`. Vacuous | Put a real declared member in the fixture's `:seon.operator/exception-data` and assert it survives |
| 8 | defect | `test/seon/test_runner_test.clj:1509-1517` | (7) — expected notice changed from `":seon.test-runner-test/probe bare"` to `"The recorder returned no committed result references."`; the test no longer proves the refusal's own message (`"bare"`) reaches the notice. Expectation changed to observed behaviour | Keep a refusal carrying a declared member and assert its message appears in the notice |
| 9 | defect | `test/seon/adoption_diagnostic_test.clj` (9→8 assertions); `test/seon/adoption_margin_test.clj` (2→1) | (7) — `(is (str/includes? shown "missing-namespace"))` and `(is (= :seon.cluster/source-observer-closed …))` deleted with no replacement member. The producing `ex-info` at `src/seon/cluster.clj:114` now carries only `:seon.source/progress`; nothing names the closed observer | Declare a distinguishing member on each `ex-info` and assert it |
| 10 | defect | `test/seon/db_test.clj` (`(is (contains? #{:seon.db/invalid-read :seon.db/invalid-request} …))` → `(is (or …))`) | (7) — `:seon.db/invalid-request` is no longer distinguished from `:seon.db/invalid-read` anywhere | Same fix as #4 |
| 11 | defect | `src/seon/render.clj:1083`; `src/seon/sci/kernel.clj:584`; `src/seon/render/web.clj:491,497,1034`; `src/seon/render/transcript.clj:1597`; `src/seon/call_preparation.clj:450` | (d) — seven surviving `;; debt:` guards are still the general error predicate `(and (:seon.error/at x) (:seon.error/layer x) (:seon.error/operation x))`. Honestly disclosed in the note; still a general "is this an error?" test in production | Belongs to B3 commits 1–3 with those callees' unions; track it, do not copy it |
| 12 | nit | 47 dangling `{` lines in `src/` (`src/seon/db.clj:1030,1252,1414,…`); 137 `{ :` and several `{,` in `resources/seon/schemas/*.edn` | Source hygiene — the key was deleted textually, not edited | Reflow the map literals |
| 13 | nit | `src/seon/render.clj:74-75` | `(or (:seon.config/missing-effective effective) (or … (:seon.config/missing-effective effective) …))` — the same read twice in nested `or`s | Collapse |
| 14 | nit | `src/seon/db.clj:213` | `(symbol (namespace operation) (name operation))` rebuilds a symbol from a keyword — the `(symbol s)` sighting | Hand `dependency-error` the symbol |
| 15 | nit | `src/seon/turn.clj:3632` | Stray `]] ]` in the `settle-batch!` contract | Reflow |

## What is good

- **The cut is real, not a rename.** `rg ':seon.error/kind|:seon.error/class'` over `src test script bin resources` returns nothing. The diff introduces no `:seon.error/type`, `/code`, `/class`, and no discriminator enum anywhere — hunt item (1) is clean.
- **Two general predicates deleted with every caller in the same slice**: `seon.turn/settlement-refused?` and the two `(and (map? v) (keyword? (:seon.error/kind v)))` helpers. Hunt item (2) found no new one.
- **`;; debt:` guards 167 → 7**, and the seven survivors keep their original spelling and name their callee's generic union — they were not reworded to evade the scan (hunt item 9 clean). The note additionally lists 22 pre-existing other-spelling comments.
- **New distinguishing members carry evidence, not a bare boolean**: `:seon.fn/namespace-unresolvable` requires `:seon.cluster.eval/source`; `:seon.turn/generated-read-attributes`, `:seon.turn/invalid-disposition-source`, `:seon.cluster.status/unavailable-observation` likewise. Schema and consumer land in the same commit throughout (hunt item 6 clean).
- **Contracts added, not removed**, on dozens of previously bare private functions (`seon.db/error-value`, `lookup-ref-error`, `missing-query-error`, all of `my.program`, `seon.turn/generated-read-fault`). `seon.cluster/mcp-get-value` went from a bare `[:any {exemption}]` to `:seon.schema/value` — equivalent, but the exemption now lives in one declaration.
- **No `Thread/sleep`, no new regex, no `catch … nil`, no new tuned constant, cache or mechanism** in the diff (hunt item 8 clean). The three added `contains?` uses are `#{'defmulti …}` set membership, not map-key probing.
- `error_class_schema_test` deleted exactly as plan §5 row 4 prescribes.

## Established vs unproved

**Established by reading the diff** (static facts, verifiable without running anything):
the scan is empty; no renamed discriminator; the general predicates and 160 of 167
propagation guards are gone; every schema-resource edit has its consumer in the same
commit; the class markers are removed from all 27 resource files.

**Unproved.** Every namespace request in the landing note is red or was refused at
recording. The only recorded green in the whole note is `seon.cluster.reply-test`,
run `d38c9d20479b`, 18 tests / 124 assertions / 0 / 0. The final no-forms regression
has never passed at the final cut — the note states this plainly. Recorded red runs
exist (`876775d57511`, `a64dc228cada`, `1f6499b44492`, `edc124c7a7d2`); the rest carry
timestamps and tallies but no run id because recording was refused. There is no cold
`bin/test --paths` gate, no `--platform` tier, and no live `default` settlement proof.
Per-commit "prescribed HEAD load exited 0" is asserted without captured output.
Hunt item (10): the note is unusually honest — it labels its own gaps — but several
claims ("Prescribed load exited 0", the Slice A no-forms pass at 04:13:01) rest on
timestamps rather than a recorded run id.

**Blocker 1 — stale fixture base.** `seon.test-support/with-database` copies the
PUBLISHED store and derives its projection from that database; selecting newer source
paths does not publish declarations into it. The fixture carries publication
`b771bf4fa00eea263a2b679e89aa58fce34471659c38a5c1e11c7c43786797e0`, six commits behind
HEAD, so the newly declared `:seon.turn/generated-read-depends-on-turns-error` and
`:seon.turn/invalid-disposition-error` are absent from its registry. The lane's
exclusive probe (note §"Canonical fixture contract compilation probe") shows
`m/schema` throwing `:malli.core/invalid-schema` for exactly three authored contracts:
`seon.turn/system-turn`, `seon.turn/generated-read-fault`, `seon.turn/disposition-rule-error`.
The no-forms regression consequently stores `:malli.core/invalid-schema` where it
expects the no-forms text — the assertion fails on the error TEXT while the evaluation,
declared transaction attributes and unparked-proc assertions pass. Orchestrator action:
republish the head base / reset `default`. A lane cannot.

**Blocker 2 — the premise needs correcting.** The note records no Datahike logging
failure. What it records for effects is exit 124 at
`seon.effect-test/request-commits-before-io-dispatch-and-settles-once` after the
runner's 320-second no-progress bound — a hang, with no tally and no green record.
Separately, `seon.cluster.program-restart-test` printed
`SEON FAULT COMMITTER LOSS: pid=:seon.flow/fault-committer op=:step failure=java.lang.ClassCastException`;
the diagnostic names only the exception class, not the throw site, and the lane
explicitly declines to attribute it. A diagnostic that reports a class with no throw
site is itself a defect worth its own note.

## Verdict

Acceptable with the listed fixes. The cut itself is done correctly and honestly: the
retired key is gone everywhere, nothing was renamed into a new discriminator, the two
general predicates died with their callers, and 160 propagation guards disappeared
rather than being reworded. What it substituted at the surviving domain decisions is
wrong in a specific, fixable way — 74 hand-written subsets of the producer's declared
union, several of them narrower than the general predicate they replaced, which turns
a caught refusal into data at `my.program` (27 sites) and `seon.agent/effective-settings`.
Fix #1 and #2 before this range is treated as landed; #3–#6 before B3 commit 5 touches
the same unions, since commit 5 is where they are supposed to collapse to
`:seon.error/base` inputs anyway. Do not revert: reverting reinstates 799 kind sites and
a general predicate to buy back coverage that two narrow fixes restore.
