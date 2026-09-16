---
type: research
status: active
tags: [research, test, orchestrator]
---

# Gate ledger — 2026-09-15 (orchestrator-maintained)

Rule (owner, 21:05Z–21:50Z): astra lanes do focused fixes and never launch
test JVMs (`bin/_test-slot` orchestrator-only mode, `fd98bc5a5`); the
orchestrator batches gates in dedicated Opus threads on committed HEAD only
(`bin/test --paths README.md -- <namespaces>`), saves results under
`tmp/orchestrator/gate-results/<batch>/<lane>.md`, records them here, and
resumes each lane pointing at exactly its reds. One gate per batch; never
the same namespace twice for the same HEAD.

## Batches

| Batch | HEAD region | Namespaces | Result | Reds handed to |
|---|---|---|---|---|
| 1 | `4c8740cf0`–`1d17650a9` | refusal-grammar (10) + fixtures-events (4) + platform | 207/6191 green; platform 84/567 green; results recorded | none |
| 2 | `f5ca25ba9`+ | p1-ambient-state (19) | ABORTED at 22:12Z by the orchestrator: the gate alone ran 9 pool workers + serial + 6 concurrent confirmation JVMs (load avg 75 on 18 cores, 26 GB compressed); runner capped, re-run as batch 2b. The killed batch-2 gate thread RELAUNCHED its run (uncapped) beside 2b; killed again at 22:45Z. Lesson: a gate thread's instruction must say "if the command is killed, do not rerun; report" | — |
| 2b | `f5ca25ba9`+ | p1-ambient-state (19) | KILLED at 21:44Z by the superseded batch-2 thread (the two threads had swapped runs); partial log through seon.sci.eval-test, no tally | — |
| 2c | `48605a1de` | p1-ambient-state (19), capped at 3 workers, nice 15 | 392/2437: 50 failures, 20 errors in 6 namespaces (sci.eval, render-simplification, schema.datahike, effect, render.value, cluster.mcp); 13 namespaces green; published-base 44 s, tests 748 s; results NOT recorded (live prepl unavailable) | p1-ambient-state: class = fixtures mint bare database values and the fallback now refuses; Datom-where-map in mcp-test; two effect events |
| 33 | `7b3a9ecc8` | FRESH STORE (reset: 72 GB → 107 MB; default pid 27828): platform (recording) then seon.issue-test (indexer `75996a9e6`…`da0309344`) seon.test-runner-test seon.test.runner-test seon.test-support-test (reach-closure `bbbfafaf1`, `1086a7b80`) | A: PLATFORM RED 86/579, 13 F all in seon.cluster.source-test/latest-test-evidence-survives-rebuilding-from-an-older-base — `commit-results!` refused at the writer: `:seon.test.run/unavailable The completion lacks its tested database reach membership` (reach-closure's seam); gate recording also rejected; root run.5goq1Y. BLOCKER for the platform tier; fixed by reach-closure `8199364a2` (recording total: `:seon.test/reach-unknown`). B: 66/514, 3 F — seon.issue-test 2 tests (`:seon.issue/unresolved` vector vs set; steward's indexer); runner/support namespaces GREEN; root run.6P5SxE | steward |
| 37 | `e25f0f360` | steward's issue-index-publication-cost `6ed16de1a` (delta-only index-tx; 1,276 → 358 ms): seon.issue-test seon.cluster.source-test | 21/221, 0 F 0 E — both GREEN (source-test's latest-test-evidence… passes under named selection); recording failed only because default was restarting; root run.sK2dTh swept | steward |
| 39 | `3c446a558` | transcript second pass (`c19e826fa`…`583dab7c9`) + config-apply-cost `5e5aa6293`: seon.render.transcript-test seon.render.web-debug-test seon.config-test seon.reconcile-test | 56/568, 1 F: web-debug, config, reconcile GREEN; transcript-test 13 → 1 — every-generated-history-is-ordered-and-total (generative: two same-instant inbound messages order, seed 2026073104); root run.q75EGY | steward |
| 46 | HEAD | RECORDER FIX `60516d27c` (completion staged as EDN under the run root; form 207,166 → 890 bytes) — platform (recording) then 15 ns: runner/support, fixture-write-helper (`477cb615c` `074fbceda`), reach-closure final (`fec3918dd` `9f0771cfc` `c6507facb`), effect-facts (`8f4561450`) + entity-pairs `698d15d51` | A: PLATFORM GREEN 88/602, exit 0, root removed as successful, RESULTS RECORDED — first recorded gate since batch 30; the recorder class (data as code) is dead. B (snapshot `2b996b1b5`): 361/2655, 6 distinct reds — 13 ns GREEN (fixture-write-helper ×5, reach-closure final ×4, effect-test, sci.eval, runner/support, entity-pairs); RED: seon.cluster.turn-test 2 (the last two turn defects; Opus lane), seon.edit-test 3 (effect-facts, steward), seon.fn-test 1 (file identities vs manifest, steward); recorded; root run.VjSBek | orchestrator; steward |
| 50 | `d9ff63194` | steward's shapes dissolution (`8795db4ac` `d9ff63194`: `seon.program/shapes` derived from declared row schemas; the hour of "declares no row schema" was a cached throwable): seon.program-test seon.fn-test seon.cluster.source-test (platform held until the recorder is total for absent identities) | `a3620d746`: 81/589, 0 F — ALL GREEN; recording still refused on `seon.fn/source-files` (recorder-absent-identity lane, steward); root run.NAYaWP swept | steward |
| 52 | `7d817fd89` | steward's recorder-absent-identity (`64230d4de` `b375b5dcc` `7d817fd89`: reach members resolved against the tested value during the source-files rename window; now one decision on the writer's own database inside the tx fn, identity tombstones minted, file identities never minted): PLATFORM WITH RECORDING (the proof) then seon.test-failure-facts-test seon.test.runner-test seon.test-runner-test seon.program-test | A: PLATFORM GREEN 88 tests, exit 0, RECORDED, root removed — the recorder is total on a HEAD with a renamed identity. B (`316337e98`): 92/659, 0 F, exit 0, recorded — failure-facts, runner ×2, program-test GREEN | steward |
| 54 | HEAD | post-refork (pid 95853): start-arms (`df4c1c012` `89d68eb6c`) + gate-set-cost `1d141d26a` + dir-elision (`bb33b93fa` `c5abf5d5b` `bf381de05`): platform (recording) then 10 ns (agent-arming, cluster.turn, error, html-views, fn, test-support, print, render.value, render.web, render.transcript) | A: PLATFORM GREEN 88/602, exit 0, recorded, root removed (reforked default records). B (`960cc8155`): 292/2128, 15 F 1 E — GREEN: error, fn, test-support, render.transcript; RED (steward): agent-arming-test 1 (start-arms' own regression: the armed event never published), html-views 1 (known golden), print-test 1 + render.value-test 5 + render.web-test 1 (dir-elision's cold reds); turn-test delimiter-repair bookkeeping 625 ms cold (was 7,157; 15a15e9c1 not yet in the snapshot); recorded; root run.kPnRnr | steward; orchestrator |
| 56 | `9a4e873d2` | steward's start-arms round 2 `aa174d941` (armer primes itself at flow resume; an agent committed before the armer read was never armed): seon.cluster.agent-arming-test seon.cluster.turn-test seon.cluster-test | 65/467, 1 F — agent-arming GREEN (start-arms r2 proven), cluster-test GREEN, only the known bookkeeping bound in cluster.turn-test; recorded | steward |
| 61 | HEAD | steward's render-selection r2 `cfbc941a8` (the arity red was a with-redefs stub of kernel/context-projection; projection now handed on the value's metadata) + render-repl `358133a78` (rename): platform (recording) then seon.render-simplification-test | A (`2d26648a4`): PLATFORM GREEN 88/602, exit 0, recorded. B (`3d803a572`): 22/131, 0 F — render-simplification GREEN (render-selection r2 proven); exit 0, recorded | steward |
| 68 | HEAD | steward's call-prep-recovery (`6cbe2017c` `3e41a5d22`: a real `seon.instrument` defect — `:seon.error/diagnostic-offending` carried a problem leaf instead of the checked value, arity refusals printed no count; boot-test expectation drift): seon.instrument-test seon.error-test seon.call-preparation-test seon.cluster.boot-test | RED 112/548, 18 F 14 E (root run.Ub6t31; results NOT recorded: the store was held by the reset in flight — default reset mid-batch, 19 GB → 100 MB). instrument-test GREEN, error-test GREEN. call-preparation-test 2 E (`compilable-form` refused predicate-functions nil; `prepare` refused plan-value missing `:contract-t`). boot-test 12 reds, dominated by `seon.cluster/stop!` refusing its instance (`:seon.store/connection-object` "must be a live unreleased Datahike connection held by this process root"; `:seon.turn.loop/cluster :seon.db/connection` "from the calling cluster") — a contract predicate now refusing boot-test fixture roots/instances, suspect `ccccea806` (declared roots) or `fe44a981b`; plus the legacy-schema refusal test seeing `:malli.core/invalid-schema` instead of the steer, adoption `built? true`, a fixture write missing `:seon.config/applied-manifest-digest`. Triage thread launched; re-gate as batch 69 | steward; orchestrator |
| 69 | `c96fb93b7` | after default reset #5 (pid 63433, store 100 MB): A platform tier; B steward's maintenance-success (`67487fa4d` — a successful cleanup collection was silently ERASED as an empty component, now persisted; batch-67 fire fixture fixed) + check-excludes-long (`4e22d2256`, `:seon.test/long` a program-row fact, UNRUN): seon.maintenance-test seon.maintenance-schema-test seon.schedule-test seon.test-reaching-test seon.test.runner-test | A (`c1559f34b`): PLATFORM GREEN 87/574, exit 0, recorded, root removed; store 249 → 281 MB. B first launch crashed at load on the request's nonexistent `my.test-test` (issue filed `90250bb2c`; root run.Ok1sVf). B2 (`54d3ee20f`, five namespaces): RED 58/375, 3 F-groups + 2 E — maintenance-test/last-collection-answers… (`:seon.error/diagnostic-offending` nil, expected the collected root); maintenance-schema-test/receipt-request… fixture write refused "Nothing found for entity id [:seon.ns/name maintenance-schema-test]"; schedule-test/root-owned-portfolio… fixture write missing `:seon.schedule/zone-id`; test-reaching-test/an-expired-check-reports-the-verdicts-it-already-recorded 9 F (the expired check reports zero results/passed, both tests pending — first run of the lane's regression); runner-test GREEN; recorded; root run.kP1d2O; store 482 → 513 MB | steward |
| 70 | HEAD (≥`ca9a8b0e8`) | steward's fixture-write sweep second pass (`61519c245` `e35c32118` `4209bdf89` `0da13c8ae` `0a1bc44c1` `d090c9934`, cold-gate-unproven): the 27 touched namespaces minus the maintenance pair (batch 69 B2), boot-test and call-preparation-test (batch 68, under triage) — 23 namespaces, list in tmp/orchestrator/gate-results/batch-70-namespaces.txt | first launch: my zsh word-splitting error (one argument), no tests; relaunch (`ca9a8b0e8`, root run.1jtfxl): RED 306/4816, 297 F 16 E, recorded; store 513 MB → 1.0 GB during the batch, flat after. Dominant shapes: `:seon.turn/closed-tx` is a ref (`#:db{:id …}`) where ~50 assertions expect an inst (turn-loop, turn-work, problem-routing, transcript-run…); concurrency-independence 199 F and concurrency-streams 9 F expect message sets and get `#{}` (nothing delivered/written); schema-usage-guard ~20 (`:seon.schema-usage-guardb/entity-id` invalid-schema — the probe schema now fails in its own namespace cold); 45 token-budget elision-string mismatches; fixture writes refused for `:seon.schema.admission/source` and `:seon.config/applied-manifest-digest`; bootstrap absent-intent-budget 2. Extract: tmp/orchestrator/gate-results/batch-70/named.md. The steward's d090c9934 wave is proven red cold | steward |
| 71 | HEAD (≥`90527acba`) | call-prep recovery (`ed2eb3423`) + boot-test custody triage (`7f99fe695` `d427728d7`) + maintenance expectations (`274da4108`): seon.instrument-test seon.error-test seon.call-preparation-test seon.cluster.boot-test seon.cluster.store-test seon.cluster-test seon.maintenance-test seon.maintenance-schema-test | RED 151/735, 19 F 5 E, all in boot-test (root run.zeiltk; recorded): instrument, error, call-preparation (`ed2eb3423` proven), store-test, cluster-test, maintenance pair (`274da4108` proven) GREEN. boot-test residue: sovereign steer 11 F (open blocker, not root-caused); a-failed-stop-remains-addressable 4 F 2 E — one more member of the stop! class: `seon.cluster.wake/unlisten!` demands a live connection (triage thread resumed on it); incremental-source-refresh 2, partial-clusters NPE, boot-order 1, generated-prefix timeout, development-adoption exchange-bound 270 s. Store 1.3 → 2.0 GB during the batch, flat after | steward; orchestrator |
| 72 | HEAD (≥`841fd67e6`) | write-storm class 2 (`f86ec57ed`: a refused turn write is bounded by `:seon.config.agent/write-refusal-bound` 3, the storm becomes one fault) + check-long (`beb95c1af`): A platform tier; B seon.turn-test seon.cluster.turn-test seon.config-test seon.test-reaching-test | A: PLATFORM GREEN 87/575, exit 0. B (root run.6zeAHA, recorded): 129/1085, 1 F 1 E — both the write-storm lane's: turn-test/a-refused-turn-write-is-bounded-and-commits-exactly-one-fault ERROR (`seon.config/compile-manifest` refused: `:seon.boot/cluster-name` nil — the new regression's fixture compiles a manifest without a cluster name); config-test/the-default-document-has-one-canonical-complete-location 1 F (dial attribute set ≠ manifest keys after the `write-refusal-bound` pair). seon.test-reaching-test GREEN (`beb95c1af` check-long proven), seon.cluster.turn-test GREEN. Store 2.0 → 3.0 GB across A+B (recording cost; runner lane owns) | steward |
| 74 | HEAD (≥`251816349`) | stop!-class root close (`6ea39d45a`) + agent-identity/dials triage (`62110d9b0` `f3ac3be86`): seon.cluster.boot-test seon.cluster.wake-test seon.db-test seon.cluster.agent-identity-test seon.context-selection-test (batch 73 = write-storm class 1 `f3b61b975`, held for the lane's confirmation) | RED 100/640, 19 F 3 E (root run.1Pw2fm; recorded). CLASS CLOSED COLD: a-failed-stop-remains-addressable-and-retryable GREEN; seon.db-test, seon.cluster.agent-identity-test, seon.context-selection-test GREEN (`62110d9b0` `f3ac3be86` proven). boot-test residue unchanged: sovereign steer 11 (open blocker), incremental-source-refresh 2, partial-clusters NPE, boot-order 1, generated-prefix timeout, development-adoption exchange-bound 270 s (declared long; runner lane). NEW: seon.cluster.wake-test 4 F — `:seon.issue/agent` is now a listened attribute and a created agent wakes its armer (`df4c1c012`, the steward platform's issue-assignment wake): the-listened-set-is-declared-not-listed 2, an-unrouted-recipient-reaches-the-armer 1, route-render-wake-and-disjointness-property 1 (shrunk to `[[:agent]]`) — stale expectations against a deliberate ruling, steward's. Store 3.0 → 4.8 GB across 100 tests | orchestrator; steward |
| 75 | HEAD (≥`280518d68`) | sweep third pass (`4d181533d` closed-tx read through the tx ref at one reader; `55a0abae0` remaining fixture keys; `d7e5a0268` bystander recipient), cold-only: seon.turn-loop-test seon.turn-work-test seon.bootstrap-test seon.web.jvm-test seon.background-blob-test seon.context-selection-test seon.cluster.problem-routing-test seon.render.web-test seon.render.web-context-test seon.concurrency-independence-test seon.test-support-test | RED 141/3732, 184 F 7 E (root run.jA3Evi; recorded) — down from 297/16 in batch 70 on the same namespaces. concurrency-independence 154 F (the history-cut ruling, blocked as declared); turn-work 13 F, turn-loop 11 F, problem-routing 2, web-context 2, web 1, bootstrap intent-membership 1; errors: `seon.render.walk/ordered-episode` refused `:seon.repl/subject` (symbol where the contract says int) ×2, "capability request does not satisfy its owner contract" ×2 (effect fetch/search), reborn-opening, committed-ending-namespace, background-binary. seon.web.jvm-test, seon.background-blob-test (bar 1 E), seon.context-selection-test, seon.render.web-test (bar 1), seon.test-support-test GREEN-ish. Extract: tmp/orchestrator/gate-results/batch-75/named.md. Store 4.8 → 5.1 GB | steward |
| 73 | HEAD (≥`b9e87298b`) | write-storm lane confirmed: `f86ec57ed` (class 2: refused turn write bounded by `write-refusal-bound`, one fault), `f3b61b975` (class 1: the storm's root was CUSTODY — a test body inherited the agent evaluation's `*conn*` via bound-fn, so elided writes hit the live cluster; every test Var now runs without custody, the drift detector snapshots schema keys), `f0cb3f692` (batch-72 fixture), plus the shipped value for `:seon.config.render/issue-opening`: A platform tier; B seon.schema-usage-guard-test seon.test-support-test seon.test.runner-test seon.turn-test seon.cluster.turn-test seon.config-test | A: PLATFORM GREEN 89/585, exit 0. B (root run.IO9fnv, recorded): 139/1190, 4 F 3 E — all the write-storm lane's: turn-test/a-refused-turn-write-is-bounded-and-commits-exactly-one-fault 2 F 1 E (a wake is still polled after the bound; the fault's `:seon.error/kind` is nil, expected `:seon.turn.loop/write-refusals-exhausted` — class 2 NOT proven cold); schema-usage-guard-test/unregister-stages-removal 2 F 1 E (`:malli.core/invalid-schema` on the probe base key; changed-keys empty) and generic-schema-deletion-refuses-committed-dependencies 1 E (fixture refs `[:seon.ns/name seon.schema-usage-guard]`, never minted) — class 1 partially proven (test-support-test's two regressions GREEN; guard-test still red). test-support-test, runner-test, cluster.turn-test, config-test GREEN (`:seon.config.render/issue-opening` value proven). Store 5.1 → 5.6 GB; reset #6 follows | steward |
| 78 | HEAD (≥`fd094894c`) | after reset #6 (pid 88182, store 101 MB): predicate-functions one derivation (`3764c7965`: `seon.schema/predicate-functions-in` / `with-predicate-functions`, fourteen reads and four writes converted, checker fails on a bare read; compilable-form still refuses nil) + wake-test expectations derived from canonical rows (`1b1215ba8`): A platform tier; B seon.schema-test seon.fn-test seon.sci.eval-test seon.call-preparation-test seon.instrument-test seon.cluster.wake-test | A ABORTED at SELECT (root run.NsaMnu, fresh published base `ab038b2c…` built from HEAD `8ffc6c60d`): `seon.test.runner/verify-long-declarations-indexed!` (`8c2f62701`) refused — "The indexed program rows disagree with the declared long tests" for exactly 12 tests (seon.cluster.armed-test ×6, concurrency-independence ×2, concurrency-streams ×2, program-restart, sci.eval-instrumentation) — the ones whose `:seon.test/long` is declared on the NAMESPACE (`(ns ^{:seon.test/long …})`) and inherited by every deftest at the Var read, while the row indexer lifts only deftest metadata: two readers of one declaration disagree (the same disease as exact-source). The other ~40 long tests, declared per deftest, index fine. The checker (correctly) stops every gate until the ns-level declaration reaches the test rows (index it onto each test row at the one lifting seam, or retire ns-level long). B not run | steward; runner lane |
| 78b | HEAD (≥`742ac38c2`) | long-lift landed (`742ac38c2`: `seon.program/test-markers` is the one rule — deftest or namespace declaration, deftest winning per attribute — used by the static and loaded-Var seams; armed_test 6/6 rows carry long statically) + write-storm residue (`b7c7edf5e` `006e7e450` `e074f8208` `543a151d9`: all three batch-73 reds were fixture defects; class 1's drift check caught a real foreign leak — context_blocks_fixture's `:example/order` in default's projection): A platform tier; B seon.schema-test seon.fn-test seon.sci.eval-test seon.call-preparation-test seon.instrument-test seon.cluster.wake-test seon.turn-test seon.schema-usage-guard-test | running | steward |
| 67 | ≥`ec260cd2e` | operator-test regressions fixed `ec260cd2e` (collection key widened accretively to `[:or result error]`; stale lifecycle expectation; the contract test no longer re-collects against malli's default registry): seon.operator-test seon.maintenance-test seon.maintenance-schema-test | `e93e7f30c`: 39/209, 0 F 3 E — seon.operator-test GREEN (ccccea806's regressions closed); maintenance-test 1 + maintenance-schema-test 2 are fixture writes refused for missing `:seon.schedule.fire/agent` / `:seon.schedule/zone-id` (loud-fixture class; steward's maintenance lane); recorded; STORE 14 → 18 GB across a 39-test batch — growth exploded (2.5 GB an hour earlier); root run.L4ttfb | orchestrator; steward |
| 66 | ≥`24ead5f03` | WIPE-CLASS COLD PROOF: platform tier (with the steward's tier checker `9fa1f101d`/`f03adc248` verifying no destructive drill before the first task) then seon.test.runner-test seon.test-runner-test seon.cluster.cohost-boot-test seon.test-support-test seon.test-reaching-test (in-process refusal `9012800d6`) seon.cluster.store-test (sentinel `24ead5f03`) seon.operator-test; store size checked before/after each run | A (`23360f577`): PLATFORM GREEN 86/570 (three destructive drills demoted; the tier checker passed), exit 0, recorded; store 2.2 → 2.4 GB intact. B: 137/878, 1 F 2 E — store-test (sentinel) GREEN, test-reaching-test (in-process destructive refusal) GREEN, runner/support/cohost GREEN; seon.operator-test 3 (regressions of `ccccea806`: cleanup-cluster! return contract vs the collection map; lifecycle delegates now receive enriched boot maps; `:seon.operator/cleanup-request` schema unregistered) — Opus fix; recorded; store 2.5 GB; root run.H6AjEP | orchestrator; steward |
| 65 | `ccccea806` | GATES RESUMED after the root-refusal fix `ccccea806`: platform (recording) then the fixture-write-sweep's 95 namespaces + seon.operator-test seon.cluster.store-test (the wipe regressions); store size checked before/after each run | A: PLATFORM RED 89/608, 1 F — my new `creation-never-deletes-a-complete-store-or-an-undeclared-root` asserts the CHECKOUT's data/store exists, which is false in a pooled worker (no store there): wrong cold observable, subject right — fixed `24ead5f03` (the test owns a scratch complete store + sentinel, byte-identical before/after; 9/0/0); dev store intact at 1.1 GB; root run.EDKx7P. B (95-ns sweep + operator/store tests): 1,152/8,091, 168 F 101 E — a near-full suite; attribution below; store 2.2 GB after (intact); root run.mVNxT1 | orchestrator; steward |
| 64 | `d32a69073` | one-read-per-file `d32a69073` (analysis lints a private mirror of the captured bytes; stale-offset class killed) + steward's fixture-write sweep (`391e3be12` `e485aa8d2` `f37556a78`, three dead turn fixtures made honest): seon.fn-test seon.fn.analyzer-test seon.turn-test seon.render-coverage-test seon.test-support-test | 99/994, 1 F — fn-test, analyzer-test (one-read proven), turn-test (three dead fixtures fixed), test-support GREEN; render-coverage typed-unknown expectation (steward); NOT recorded: "Test recording requires a published current-src" — the wiped store; root run.5QicwL | orchestrator; steward |
| 63 | HEAD | steward's tombstone minting generalized `734253727` (one rule over every program identity attribute; file/schema/lint identities become the typed unknown; the renamed-test seal refusal is gone) + adoption-retry `d93f57328` (one `source-change-phases` declaration drives one retry for adoption or analysis): seon.test-failure-facts-test seon.cluster.source-test seon.cluster-test + platform (+ one-read-per-file when landed) | A: PLATFORM GREEN 88/602, exit 0, recorded (tombstones + adoption-retry on the platform tier). B (`71395752f`): 36/278, 0 F, exit 0, recorded — failure-facts, source-test, cluster-test ALL GREEN | steward |
| 62 | ≥`50a7110b7` | settlement `2da44c50d` + prompt `c27727551` + exact-source `50a7110b7` + steward's render-repl-cold-reds: platform (recording) then cluster.turn, turn, cluster.prompt, render-simplification, returned-error, page-settings, render-coverage, fn-test | A (`f6b364603`): PLATFORM GREEN 88/602, exit 0, recorded. B (`d93f57328`): 171/1478, 2 F 3 E — seon.cluster.turn-test GREEN (the 300 ms bookkeeping bound PASSES cold after `2da44c50d`; class closed), cluster.prompt, render-simplification, returned-error, page-settings GREEN; RED: seon.turn-test 3 + seon.fn-test 1 — fixture writes now REFUSED LOUDLY through `transacted!` (missing `:seon.turn/agent`; `receipt-exists`) — dead fixtures the sweep made honest (steward's sweep lane); render-coverage 1 — the typed-unknown expectation after the render.clj:995 fix (steward); recorded; root run.cdS8Pp | orchestrator; steward |
| 60 | `b2a905f7f` | FRESH STORE (pid 17352): platform (recording) then steward's render-selection (`68f1ad52c`…`33a4c2035`) + dir-elision r3 `415ab40fc` + gate-set contract `5ffa964cb`: seon.render-simplification-test seon.html-views-test seon.error-test seon.render.web-test seon.fn-test | A: PLATFORM GREEN 88/602, exit 0, recorded on the fresh store. B (`b2c581667`): 171/1180, 13 F 1 E — html-views (golden fixed), error-test, render.web-test (dir-elision r3), fn-test (gate-set contract) ALL GREEN; render-simplification-test 2 tests (nested-ai-values… 7 — the known one, and candidate-input-and-output-must-fit-the-same-arity 6+1, new) — steward's render-selection; recorded; root run.whIlnA. (fn.clj re-adoption refused by the steward's transacted!-sweep in-flight edits.) | steward; orchestrator |
| 59 | `5ffa964cb` | turn-settlement (`3594331c8` `60e0ba923` `97d1f69e0`), ordered-evaluation `91cd63e5a`, render root-address `ac95db78a`: seon.cluster.turn-test seon.turn-test seon.cluster.evaluate-sources-test seon.cluster.prompt-test seon.render-coverage-test | 101/1013, 2 F 1 E — turn-test, evaluate-sources GREEN; render-coverage 39/0/0-class fixed, only the `invocation-unknown` nil ERROR (steward's lane); cluster.turn-test delimiter-repair `settlement and writes 412.5 ms` (projection rebuild in settlement — Opus lane); cluster.prompt-test prompt-prices… "turns left: 99 of 100" stale after the budget ruling (same lane); recorded; root run.INRtF9 | orchestrator; steward |
| 58 | `ebb2bcc65` | steward's plan-derivation (`3cd566973` `da9c168e8`: Datahike planner credits no bindings to a recursive-rule op past ~400 items → readiness derived in Clojure from the pulled component tree) + cold verification of seven in-process neighbour reds: my.plan-test seon.render-simplification-test seon.html-views-test seon.returned-error-test seon.render.page-settings-test | first attempt aborted before tests: tools.deps `Error building classpath … HashMap$Node cannot be cast to HashMap$TreeNode` (a concurrent-mutation corruption inside Maven resolution; classpath builds fine seconds later) — relaunched as 58b (`60e0ba923`): 56/441, 11 F 1 E — my.plan-test GREEN (plan-derivation proven); REAL cold reds: render-simplification-test/nested-ai-values-retain-data-and-html-uses-declared-faces (7; the other three in-process reds were not real), html-views/fault-pairs-preserve-ai (2, known), returned-error-test/returned-refusal-is-a-schema-first-error… (1), render.page-settings-test/effective-settings… (1 F + seon.repl/source-text contract ERROR); recorded; root run.mVfIGe | steward |
| 57 | HEAD | steward's dir-elision r2 `90f7abec0`: seon.print-test seon.render.value-test seon.render.web-test | `b7e0bda66`: 113/735, 1 F — print-test and render.value-test GREEN (dir-elision r2 proven); render.web-test/declared-units-are-components-in-schema-order (`declared` returns the list plus :db/id :db/txInstant, web_test.clj:608) — steward; recorded; root run.NvgmtR | steward |
| 55 | `e765058fe` | request-profile `15a15e9c1` + monotonic-index (`0b910eb69`…`5dc864346`): platform (recording) then seon.cluster.evaluate-sources-test seon.cluster.turn-test seon.render-coverage-test seon.cluster-test | A: PLATFORM GREEN 88/602, exit 0, recorded, root removed (monotonic-index adoption on the platform tier). B: 72/572, 33 F 4 E — seon.cluster-test GREEN (schema-row-convergence did not fail cold), evaluate-sources-test GREEN; render-coverage-test 3 tests (every HTML render returns `:seon.render.value/missing-root-identity` with agent nil; `seon.render/unknown` refuses nil value — steward's render change); turn-test: delimiter-repair bookkeeping 652.6 ms cold (research lane on the remainder), ordered-evaluation… 9 is in seon.cluster.evaluate-sources-test (Opus lane; corrected); recorded; root run.1J6jsu | orchestrator; steward |
| 53 | `e62c79b40`+ | analyzer prelude `3d2384dfb` + last turn defects `b166c4246`/`42661e5b0` + steward's sci-pull `59c7d78c4`: platform (recording) then seon.fn.analyzer-test seon.cluster.turn-test seon.render.value-test seon.problems-test | A (`02437423d`): PLATFORM GREEN 88/602, exit 0, recorded. B (`4b833f47a`): 113/933, 1 F — only the 300 ms bookkeeping bound (7,157 ms; gate-set lane in flight); analyzer-test, value-test (sci-pull proven), problems-test, rest of cluster.turn-test GREEN; recorded; root run.Pa8p6b | orchestrator; steward |
| 51 | `a3620d746`+ | kondo-cache class `b50f4ddc7` + adoption-rows `d4a201237`: seon.fn-test seon.fn.analyzer-test seon.public-contract-test seon.adoption-rows-test | snapshot `dc88ee725`: 58/346, 1 F — adoption-rows GREEN (both closed), fn-test GREEN (kondo regression), public-contract GREEN; analyzer-test/ordered-forms… red: its synthetic prelude references the retired `seon.run` and passed only via the stale shared cache — Opus fix; recorded (no NOT line); root run.J4LwD6 | orchestrator |
| 49 | `d4a201237` | steward's fn-test determinism fix `0eba4b8c3` (one `containing-root` for manifest and single-file seams): seon.fn-test | `d4a201237`: 42/266, 0 F — GREEN (determinism closed); NOT recorded: `:seon.db/rejected Nothing found for entity id [:seon.fn/sym "seon.fn/source-files"]` — the completion references a program identity default's graph lacks (the wrapper now names it); root run.k7su7n | steward |
| 48 | `f1c624a61` | steward's edit-test fixture fix `1219d96c2` (fixture seeded no config row → absent fs dial read as a path → bare NPE): seon.edit-test | `3afb051a4`: 9/45, 0 F — GREEN, exit 0, recorded, root removed | steward |
| 47 | `fb6261d58` | source-root-fact (`925ca19fe` `66a7c2e02`) + adoption-identities `bb46455fb`: seon.fn-test seon.issue-generate-test seon.adoption-rows-test | 48/306, 1 F 3 E — issue-generate GREEN; fn-test file-artifacts… still red (steward); adoption-rows-test 2 (new regression refused cold by a blocking analyzer finding in its fixture; pre-existing agent-row-fault message nil) — Opus lane; recorded; root run.EpXtBI | steward; orchestrator |
| 45 | HEAD | issue-generator fixture fixes (`e47d05dec` `7fe7777a1` `e40f52059`: unchecked fixture writes, fifth hit of the class) + reach-closure `5a9de3185` (structured test failures rendered): seon.issue-generate-test seon.issue-test seon.problems-test seon.test-failure-facts-test seon.render.entity-pairs-test | `8d5a7bcea`: 40/504, 8 F — issue-generate, issue, problems, failure-facts GREEN; entity-pairs-test 1 (test render pair source changed by `5a9de3185`: 4 forms, status sentence); recording `Method code too large!` (fix in flight); root run.fy1YSs | steward |
| 44 | `b77c553e4` | recording wrapper carries the cluster's cause (`b77c553e4`): platform (recording — first gate that can NAME the rejection) then seon.dev.fresh-operator-test seon.test-runner-test | A: PLATFORM GREEN 87/591; recording notice NAMED THE CAUSE: `Method code too large!` — `persistent-results-form` inlines the whole completion as a code literal, exceeding the JVM 64 KB method limit for gate-sized completions (small ones record; grew past the limit with 8199364a2's structured failure facts). Opus fix lane: data travels as a file the cluster reads, form O(1). B: 84/515, 0 F 2 E — both errors are the worker exchange bound (270 s) on the two declared-long fresh-operator tests (forced-reset…, init-owns…, normally 93–111 s) while batch 45 ran concurrently on the second slot — orchestrator error: gates must stay strictly serial even with two slots; test-runner-test GREEN; root run.kNJct3 | orchestrator |
| 43 | HEAD | steward's effect-facts (`0e15593aa` `774b4da39`) + issue-generator (`88b04b970` `8c01f7420` `e2117dd73`): seon.effect-test seon.edit-test seon.fn-test seon.issue-generate-test seon.issue-test | `e3bfa76d1`: 70/482, 17 F 4 E — fn-test, issue-test GREEN; effect-test 2 (detached limit fails instead of interrupting; my.fs refuses the fixture temp path in a cold worker), edit-test 4 (my.edit/form! now requires :my.edit/expected-digest; fixtures refused), issue-generate-test 2 (idempotence, reopen); root run.RRQXky | steward |
| 42 | `c010f87fd` | transcript generator fix `f75112dbd`: seon.render.transcript-test | 17/275, 0 F — GREEN (transcript class closed: 13 → 0); recording rejected again | steward |
| 41 | `cfac8275c` | reach-closure's five commits (`d2a0ad636` expiry vs permit, `3c6a6bb8f` failure identities at the writer, `e2eb91fcd` recording rebased across publications, `e8a017620`, `bb2843264`): platform (recording) then seon.test-runner-test seon.test.runner-test seon.test-failure-facts-test seon.cluster.source-test | A: PLATFORM GREEN 86/582 0/0 (source-test :546 passes); recording STILL rejected, value still hidden; root run.tEkmC6. B: 68/485, 0 F 0 E — seon.test-runner-test, seon.test.runner-test, seon.test-failure-facts-test, seon.cluster.source-test ALL GREEN (batch-34 reds closed); recording rejected again | steward |
| 40 | `dafdd7dc8` | custody-stability fix `3c115ff15`: seon.custody-stability-test | 5/26, 0 F — GREEN; recording still `rejected the prepl operation` on a healthy pid 45917 (recorder race / permit class, in reach-closure's resume) | orchestrator |
| 38 | `1f7bce9c1` | evaluation-context loader fix `653d4d4ef`: seon.sci.eval-test seon.custody-stability-test | 73/365, 4 F: seon.sci.eval-test GREEN (fix proven cold); seon.custody-stability-test 2 — cross-cluster-write-isolation (fixture writes lack the now-required :seon.message/to) and indexed-custody-returning-surface… (expected roster drifted vs derived surface); Opus fix lane launched; root run.1NPVXM | orchestrator |
| 36 | HEAD | config-apply-cost `6313d2006`: seon.config-test seon.reconcile-test seon.schema-test seon.db-test seon.cluster.turn-test seon.test-support-test | `7216a688b`: 160/1161, 17 F 5 E. GOOD: generated-model-attempt-traces 105 s (no longer at the 270 s bound); schema, db, test-support GREEN. RED: seon.config-test 2 (converged-apply regression: raw-deref projection nil; hand edit not repaired; default.edn dial set), seon.reconcile-test 4 (hand edit, provenance lost, identity-scope refusal gone, pull slice) — the lane's unverified reconcile semantics; seon.cluster.turn-test 4 (delimiter-repair 14 receipts vs 6 with `(help)` first — opening/system-turn expectation; a-lost-model-call; NEW ns-unmap-retracts… ×4 and qualified-dynamic-ns-unmap…); root run.gIQ9CS. config-apply-cost resumed with a one-fast-run exception (base poisoned) | config-apply-cost; turn-test-reds (deferred) |
| 35 | `68a3f080b` | default restarted (pid 37572, permit leak cleared): platform (recording) then transcript-web-debug (`0986475bd` `b0951e459` `5b4a4e08b`) + seon.repl-test + seon.issue-test (`bc9181842`) | A: PLATFORM 86 tests, 1 F — source-test/latest-test-evidence… :546 (reach-closure's, in its resume); recording rejected (permit leak fix pending); root run.3qt5Dv. B: 50/532, 23 F 3 E — seon.issue-test GREEN, seon.repl-test GREEN; seon.render.transcript-test 7 distinct and web-debug-test 1 still red (13 → 8); root run.V7UPqN | steward |
| 34 | `8199364a2`+ | recording-total fix: platform (recording) then seon.cluster.source-test seon.test-failure-facts-test seon.test-runner-test seon.test.runner-test seon.test-support-test seon.issue-test | A: PLATFORM 86/579, 1 F (was 13): source-test/latest-test-evidence… :546 run-entity equality after rebuild (reach-closure's new run attributes); gate recording still `rejected the prepl operation` (default's JVM was wedged: Datahike roster permit leaked by an interrupted fixture — steward's find, issue `an-interrupted-fixture-leaks-datahikes-roster-permit-and-wedges-the-jvm.md`); root run.X3y47H. B: 85/660, 8 F — GREEN test-failure-facts, test.runner, test-support; RED seon.test-runner-test 3 (recording totality regressed under concurrent retraction: conflicting failure-fact upsert; elision/reach-unknown in stored vs returned results), source-test :546, issue-test 2 (fixed `bc9181842`); root run.4avadX | steward (reach-closure resume) |
| 32 | `2843d6ec7` | fresh-operator config-proof read fix `8208754cb` + steward's program/shapes fix `7cfe02790`: seon.dev.fresh-operator-test seon.fn-test | 77/500, 0 F 0 E — both GREEN; NOT recorded: `:seon.test.run/unavailable The completion lacks its tested database reach membership` (new seam from reach-closure-facts `bbbfafaf1`/`1086a7b80`; handed to the steward); root run.HwsG9I | orchestrator; steward |
| 31 | `34c5a9535` | turn-test-reds third slice: seon.cluster.turn-test seon.turn-test | 82/797, 7 F 6 E: seon.turn-test GREEN; seon.cluster.turn-test 3 — delimiter-repair… (bookkeeping 5.6 s vs 300 ms), a-lost-model-call… (provider diagnostic missing), generated-model-attempt-traces… hit the 270 s worker exchange bound (same cost class suspected); root run.6byCYT. Class: 44 → 3. Opus research lane on the bookkeeping cost launched | orchestrator |
| 30 | `56f0a4ca8` | cold-arming fix `eeafb9dba` (98b5f2afe culprit; `direct-references` defaults an omitted predicate map to {}) + retention-sweep `5a10f5dfa` + arming/analyzer + transcript-test + issue-settlement `0c8f90630`: platform (recording) then 11 ns | A: PLATFORM GREEN 86/579 0/0, exit 0, root removed as successful, results RECORDED on default (runs 9a399119df79 / 4cf0c1d016be at 05:32Z) — first recorded gate since the sweep class was found. B: 178/1341, 77 F 8 E — GREEN: schedule, blob, registry, data-shapes, instrument, issue-settlement, issue-test; RED: seon.render.transcript-test 13 tests (renderer-ref class, steward), seon.fn-test 4 (analyzer-facets: `:seon.fn/call-arities` nil in cold workers — the dev-JVM-vs-cold-worker class), seon.render.web-debug-test 1 (known residual), seon.dev.fresh-operator-test/init-owns-current-source… 2 (shipped-decision prepl round-trip false after the dial removal → retention-sweep) | steward; retention-sweep |
| 29 | `bf58f1ab0` | steward's arming-includes-referenced-schemas (`98b5f2afe`, `1c98259ba`) + analyzer-facets (`efaa45a68`): seon.data-shapes-test seon.instrument-test seon.render.web-debug-test seon.fn-test (seon.render.transcript-test queued for the next batch) | ABORTED at worker initialization on committed HEAD: every cold worker dies arming contracts — `seon.schema/compilable-form refused predicate-functions at []: expected a map, got nil` via `seon.instrument/throwing-report` (instrument.clj:414); root run.C1Ahr5. BLOCKER for every cold gate until fixed; handed to the steward session (arming lane's instrument.clj) | steward |
| 28 | HEAD | turn-test-reds landing: Datahike fork `49ea5933` (speculative tx detaches committed query-cache identity) + `c1d7d4695`, 11 batch-23 members repaired, 4 remain (two refused seeds, bookkeeping bound, provider-error prompt visibility); ns: seon.cluster.turn-test seon.sci.eval-test seon.turn-test seon.datahike-fork-test | `258150603`: 150/980, 27 F 6 E in 6 distinct tests: seon.cluster.turn-test 4 (refused-terminal-program… 15, delimiter-repair… 10, a-lost-model-call… 2, generated-model-attempt-traces… 1), seon.turn-test 2 (virtual-turns… 4, settlement-mints… 1); eval-test and fork-test GREEN; root run.sO5c2C; batch 23 had 44 distinct reds → 7. Resumed: `c34b5c166` settlement-mints; base refreshed once (27.5 s); `99828a14d` virtual-turns (246 assertions), `d8b06746b` generated-model-attempt (48 trials), `80d8fbd0f` refused-terminal (33). TWO REMAIN, both real defects: delimiter-repair… (turn bookkeeping 5,646 ms vs the 300 ms bound — issue `turn-bookkeeping-exceeds-recorded-regression-bound.md`) and a-lost-model-call… (provider diagnostic missing from the next prompt). Lane stopped; batch 31 gates the two namespaces | turn-test-reds (stopped) |
| 27 | `7c7395c8a` | platform (recording) then fixture-stores `6d4705498` (steward: non-temporal store writes, executor frame conveyance) + recording pre-read fix `7c7395c8a` — 9 ns | A: PLATFORM GREEN 86/579 0/0; still NOT recorded (`prepl-response-silent` 30 s with all other clients paused → the record send itself is slow; Opus latency lane launched; root run.4hIzdV). B (`b033e0860`): 155/1001, 0 F 1 E — seon.dev.fresh-operator-test/init-owns-current-source-and-dormant-cluster-lifecycle hung to the 270 s worker exchange bound (suspect: 7c7395c8a's prepl reply seam; handed to the latency lane); other 8 ns GREEN incl. registry/store/transact-feedback/runner; root run.o0NcHO | steward; recording-latency (Opus) |
| 26 | `29d077c22`+overlay | recording platform tier on reforked default (pid 53378), then seon.test-runner-test | A: PLATFORM RED 86/578, 1 F 1 E (registry-test non-temporal-collection…, store-test branch-connections-inherit…) — bare run overlaid the steward fixer's uncommitted cluster.clj/test_support.clj; NOT recorded (`prepl-response-silent` 30 s). B: seon.test-runner-test GREEN 42/267 (runner exhaust fix `86b4c8ff4` proven); NOT recorded (`live-prepl-unavailable`) while MCP evals on the same JVM answered in ms — recording's prepl client is the suspect, not load | steward fixer (platform); orchestrator (recording research) |
| 25 | `a14a3101c` | steward session's issue-settlement (7 ns) | 109 tests / 924 assertions, 6 F 1 E: issue-settlement, my.plan, contracts-plan, db, cluster.source GREEN; seon.issue-test 1 test (known, triage); seon.turn-test 2 tests (`settlement-mints-rows-for-unindexed-call-targets`, `virtual-turns-use-the-proc-and-compaction-is-agent-scoped`); results NOT recorded (`live-prepl-unavailable`); root run.r3qnVJ | steward (issue-test), turn-test-reds (turn-test) |
| 24 | `d47ebcc3e` | attempt-and-eval-facts re-run (2 ns) after `52044b4f4` | faults-test GREEN; issue-test 1 test (plan-opening fixture, issue-settlement's) | steward session |
| 23 | `f817acfd0` | turn-test-reds (3 ns) after `2209387e2`, `c01df3773`, `7c097f8f2` | 148/867: 141 failures, 38 errors — cluster.turn-test still 44 tests red cold (unchanged since batch 20), turn-test 2, sci.eval GREEN; the base built (52 s) so the missing-projection refusal did not hit this path; results not recorded (prepl silent) | decision: option 1 for the transaction-cache defect (clear committed cache identity at transaction-function entry in the Datahike fork; deletion projection at the writer); lane resumed |
| 22 | `01539d18a`+ | steward session's write-validation-class (2 ns; `20d30a0bd`) | 18/181 GREEN, exit 0 | write-validation-class CLOSED |
| 21 | `fe9aeb336`+ | platform tier alone (issue-family's fix `fe9aeb336`: issue indexing out of the publication path), then issue-family's 3 namespaces | PLATFORM GREEN 84/569 at `7ccd30496`, NOT RECORDED (prepl-response-silent) — accepted on the batch-21 log as evidence; a recording-only platform run follows the steward session's refork; named 18/162: 1 error (issue-test opening-links-its-issue: of-agent renderer-fn ref — attempt-and-eval-facts' residual), issues-test and source-test GREEN | steward session |
| 20 | `cecfaf428` | steward session's error-graph (12 ns) | 230/1618: 236 failures, 53 errors; six namespaces GREEN (error, problems, cluster, wake, schedule, status); red: cluster.turn-test 48 (pre-existing class, overlaps turn-test-reds), render.transcript-test 15, turn-loop-test 11, turn-test 2, flow 1, faults 1; results not recorded (prepl unavailable) | steward session, with a baseline-first instruction |
| 19 | `e4080bd3f` | HEAD publishes again (`ff48a4110`). Six lanes in one batch: turn-test-reds (2) + steward session's reach-digest (2), program-provenance (3), entity-pairs (1), attempt-and-eval-facts (6), generated-read-identities (4) + platform; all 18 namespaces validated against test/ | named: 355/2452, 82 failures, 23 errors; entity-pairs GREEN; turn-test-reds 19 tests; reach-digest 4; program-provenance 11; attempt-and-eval-facts 4; generated-read-identities 3; PLATFORM RED 84/568: 13 failures all in seon.cluster.source-test (latest-test-evidence-survives-rebuilding-from-an-older-base ×12, incremental-first-party-publication ×1) — last touch of cluster/source.clj is the steward session's `a7d1e115e` (issue-family); results not recorded (prepl silent 30 s; result store held by another process) | per-lane files and the platform attribution sent to the steward session; turn-test-reds resumed |
| 18b | `806e6e8d8` | turn-test-reds (2 ns), relaunched once | ABORTED in published-base preparation: schema publication refuses `:seon.issue/issue` (`:seon.render/ai` names `seon.issue/render-ai` whose declared input nil does not accept the declaring shape) — introduced by the other session's commit `6a491f0b3` (01:26Z). EVERY cold gate at HEAD is blocked until that schema is fixed | owner / the steward session |
| 18 | `0eba2ae10` | turn-test-reds (2 ns; five slices: a production deletion fix `4b3322b04`, the test entity AI/HTML pair `ff351811b`, 27 repaired tests / 188 assertions, two obsolete tests replaced; 16 tests still unresolved) | running | — |
| 17 | `47dcf6d92` | n7-eval-call-edges re-gate (2 ns) after `171c0c193` (4 tests green on a fresh base) | 89/458: fn-test GREEN; seon.cluster.turn-test still 44 tests red cold (103 blocks) — the lane verified 4 of ~89 tests; BASELINE at pre-wave 4c8740cf0: 59 tests, 56 failures, 44 errors — 43 tests fail in both, 7 baseline-only (fixed since), 1 HEAD-only. seon.cluster.turn-test was deeply red before the wave; not an N7 regression | n7-eval-call-edges CLOSED for its slice (the 1 HEAD-only test handed back); seon.cluster.turn-test opens as its own class lane |
| 16 | `8bf2dceaf` | p1-ambient-state re-gate (5 ns) after `253206238`, `0edd57230` (supplied test bounds honored; one fresh-store fixture arity; render bounds caller-owned; SCI test state scoped) | 115/663 GREEN cold, no worker-global mutations; 114 s | p1-ambient-state CLOSED for its read/admission scope |
| 15 | `2a4e43d25` | n1-render-substitution (2 ns) | 54/361: render.value GREEN cold; 1 failure = distance-spends-only-real-ref-hops-and-caps-win, already in P1's batch 12b reds | n1-render-substitution CLOSED for its slice; the distance test stays with P1 |
| 14 | `6e4fe9511` | n7-eval-call-edges (2 ns) | 89/365: 59 failures, 44 errors — seon.cluster.turn-test 51 tests red cold (~30 contract refusals at instrument.clj:413, 8 schema refusals at edn.clj:46) after the settlement change; fn-test 1 | n7-eval-call-edges: reproduce cold, fix at the settlement seam |
| 13 | `bebdfb39e` | n1-mcp-bypass (4 ns) | 65/407: seon.mcp-test, render.value, mcp-bridge GREEN cold; 1 failure = the merged artifact test asserting one fresh store now sees 2 (same red as batch 12b, already with P1) | n1-mcp-bypass CLOSED for its slice; the fresh-store count stays with P1 |
| 12b | `b1be50c2a` | P1's 13 real namespaces + seon.fn-test | 340/2448: 11 failures in 6 tests (was 153 blocks); published base reused (0 s); tests 285 s | p1-ambient-state 5 tests (sci.eval 3 incl. two that mutate worker-global state; render-simplification 1; the merged mcp artifact test 1); n7-eval-call-edges 1 (agent-source-reaches-the-evaluator-through-one-visible-path) — handed when the lane stops |
| 12 | `d57a69c3a` | p1-ambient-state re-gate (14 ns + platform) after `72d7dc3a9` (evaluation database context carried; batch-4 fixture contracts repaired; all 52 batch-4 regressions + the platform regression pass in-process) | named gate ABORTED at load 6/14: the request named seon.sci.kernel-test, which does not exist (orchestrator did not validate the request); PLATFORM GREEN again (84/566 at `f402c5d3d`, 125 s); 12b re-runs the 13 real namespaces | — |
| 11 | `0d057a799` | debug-page-cost (1: seon.render.web-context-test) | 4/53 GREEN cold; published base reused (0 s); 34 s | debug-page-cost CLOSED |
| 10 | `deb077923` | slow-tests-merge (1) | 2/59 GREEN cold; 33 s | slow-tests-merge CLOSED |
| 9 | `0cd0ad907` | debug-page-cost (3) + test-runner-waste (1) | 24/330: 1 failure; test-runner-waste GREEN (closed); retained + web-debug GREEN cold; web-context 1 (retained page count 9 vs 10 across an unrelated adoption); tests 53 s | debug-page-cost (`0d057a799`: the tenth render was cluster status, which legitimately depends on the adoption commit; regression now asserts every other renderer is reused → batch 11 pending: seon.render.web-context-test) |
| 8 | `af487bc76` | slow-tests-merge (1): cold-acquired fixture with explicit environment/projection/profile; the raw-EDN failure could not be reproduced in-process, so this gate is the cold-worker confirmation | 2/46: the merged grammar test GREEN cold (raw-EDN class gone, 36 s for the namespace); 1 error: the kept order-schema test now fails invalid-schema :example/order-row (fixture no longer registers it) | slow-tests-merge |
| 7 | `5f80c1871` | test-runner-waste (2) + reaching-tests-tier (2) + debug-page-cost (3) + slow-tests-merge (1) | 79/686: 31 failures, 1 error; reaching-tests-tier GREEN; zero confirmation JVMs; published-base 40 s, tests 99 s | debug-page-cost 22 (fixture render pair / grammar not selected in a fresh worker), slow-tests-merge 8 (same class), test-runner-waste 2 (nested probe runs in a fresh worker). Class: passes in the dev JVM, fails in a cold worker — fixtures relying on ambient dev-JVM state |
| 6 | `6df05c861` | test-runner-waste (2) + reaching-tests-tier (2) | ABORTED in SELECT: the new regrowth check refuses selected tests reaching expensive fixtures without a declared observation — existing tests were never declared; zero confirmation JVMs launched (row 1 works); published-base 39 s | test-runner-waste: declare observations on every legitimately reaching test first |
| 5 | `ec52657de` | slow-tests-merge (6 ns; rows 2–6) | 55/559: 8 failures all in one merged test (refusal-grammar-survives-real-evaluation renders raw EDN, not the grammar); 5 ns green; measured: problems 15 tests/221 s, agent 22/72 s, mcp 11/55 s, config-application 4/27 s, contracts-plan 2/11 s | slow-tests-merge: CLOSED green in batch 10 (`a1fbdb347`, `af487bc76`, `deb077923`) |
| 4 | `8e19dee42` | p1-ambient-state re-gate (9 + platform) after `28e955327` | WORSE: 210/1132: 134 failures, 19 errors (7 ns); PLATFORM RED (1 error: reads-require-their-carried-projection…, test_support.clj:479); published-base 40 s, tests 182 s | p1-ambient-state: platform first; iterate in-process with seon.test/run before any re-gate |
| 3 | `b5b5b0fb3` | startup-and-hook-waste (4) + debug-page-cost (3) + reaching-tests-tier (7, overlapping 3) + platform | 88/597: 13 failures, 2 errors; platform 84/567 green; published-base 44 s, tests 157 s | startup-and-hook-waste GREEN; debug-page-cost: retained_test 8 FAIL + web-debug 1 ERROR; reaching-tests-tier: source-reconciliation 5 FAIL + 1 ERROR |

## Lane → slice → status

| Lane | Landed | Gate | Open residuals |
|---|---|---|---|
| refusal-grammar-2 | `1fd81b2be`, `4c8740cf0` | batch 1 green | class residuals in its note; argument-count refusal omits the count (issue) |
| fixtures-events | `e4f8bbe07`, `1d17650a9` | batch 1 green | P2/P3/N2 members open with residuals |
| p1-ambient-state | `b80f78a7c`, `f5ca25ba9`, `28e955327`, `9c3c3d8d4` | batch 2c red → batch 4 worse (platform red) → in-process repair `72d7dc3a9` → batch 16 GREEN (closed) | adoption/lifecycle members open (recorded options in its note); stale fixture-contract-after-adoption class queued |
| n7-query-classification | `5deb40e4e`, `872fb25d4`; n7-eval-call-edges `f402c5d3d` (analysis returns core + my.* edges) | gated by lane before the rule (82/418, platform 86/542); seon.fn-test pending batch 12b | stored toolkit (cluster.clj, n1 lane holds it), turn.clj persistence landed `924fdbf3a` (batch 14 running); schema-fallback (P1); stored toolkit still open (cluster.clj) |
| bisect-today-reds | `6dc70f30a`, `ee8d54dca` | gated by lane before the rule (103/248, platform 86/542) | none |
| debug-page-cost | `cfb35a22b`, `671108b60`, `c20b83d20` (three kills: retained reuse across carried values; passive directory audit off; shared derivations once) | batch 3 red (retained_test 8, web-debug 1) → fixed `8920d1dfe` (25 + 95 assertions green in-process) → batch 7 red (22) → root cause `27b9f7165`: the cold fixture carried the BOOTSTRAP projection (0 function contracts) while its database held 1,045; the constructor now carries the populated database's projection → batch 9 pending (3 ns) | cold page after adoption 18 s → 1.7–3.4 s (residual acquisition cost in the issue); the 0-contract bootstrap projection is probably the same cause behind slow-tests-merge's raw-EDN refusals (batch 8 will tell) |
| reaching-tests-tier | `e9af61d87`, `b5b5b0fb3` (seon.test/check in the development JVM; hook runs the reaching tests after adoption and withholds escalation on red; bin/test-check) | batch 3: 3 ns green, source-reconciliation red → repaired `558d5614a`, `dec12ea41` (empty check 6.6 s → 1.86 ms; both namespaces green in-process) → batch 7 GREEN | cold development JVMs lack some test dependencies (issue) |
| startup-and-hook-waste | `c395610db`, `db7e653ca`, `f75c85402` (both initializers build one projection, 998/998 armed; hook drains immediately, exactly one successor batch; edit-feedback test updated) | batch 3 GREEN | hook issue narrowed to AGENTS.md wording |

## Live checks (default)

| When | Debug page warm | Fallback warnings / 100 KB log | Note |
|---|---|---|---|
| 21:00Z | 1.81 s | 7 | after restart |
| 21:10Z | 0.70 s | 7 | P1 edits adopted |
| 21:45Z | 0.75 s | 7 | P1 committed; plan attributes the floor to retained-read replay and directory reconstruction |
| 22:05Z | 0.18 s (cold after adoption 18.4 s) | 7 | debug-page-cost kills 1–2 adopted; cold path filed as an issue |
| 22:35Z | 0.14 s (cold after adoption 19.5 s) | 7 | kill 3 adopted; lane in-process 64–76 ms |
| 23:20Z | 0.11–0.16 s (cold after adoption 3.4 s) | — | slice 4 adopted; unrelated adoption re-renders zero evaluations |

## Plans reviewed

| Plan | Decision | Implementation lanes |
|---|---|---|
| slow-surfaces-plan (`65642226b`) | rows 1–3 approved; hook option 1 (no idle delay); rows 4–7 deferred until measured | startup-and-hook-waste (rows 2–3, landed); row 1 (adoption rebuilds the projection) queued behind P1 |
| debug-page-cost-plan (`5755bcd60`) | kills 1–3 approved; option A for the directory audit | debug-page-cost (landed; cold-page slice in flight) |
| n1-total-render-plan (`9167db1a8`, partial: stopped after three malformed probes) | option A approved (close the MCP sorted-map bypass at its owner; early root validation in value/prepare; one class regression); the 20 unverified members go to an Opus verification pass with the corrected probe recipe before any wider scope | n1-mcp-bypass (astra, in flight); N1 member verification (Opus, done: 13 resolved / 7 confirmed / 0 unverifiable — research/n1-member-verification-2026-09-16.md; the class is ALIVE on the agent path: a pulled :seon.fn row renders as a 100-char stale-Var instruction, a config row as English prose, a turn entity as the empty string) → n1-render-substitution landed `563034709` (batch 15 running) |
| test-suite-cost-plan (`78f0d15c0`) | row 1 option 1 (no automatic confirmation; explicit `--confirm`); row 7 publication deferred, lazy checkouts approved; row 8 approved; rows 2–6 approved; regrowth check approved | test-runner-waste (GREEN in batch 9, closed; rows 1, 7-lazy, 8, regrowth landed `ea5861329`…`e0dded0c6`; `e5b206987` carries the connection projection through in-process runs; `5f80c1871` declares 52 existing fixture observations — full selection 1,652 tests, 54 expensive, zero refusals; batch 7); slow-tests-merge (rows 2–6 landed, final `ec52657de`; flow-health test still blocked by a missing projection at seon.program/base-context-injected-symbols — P1 territory) |

## 23:55Z — Codex usage limit

The Codex account hit its usage limit ("try again at Sep 19th, 2026 3:03 AM").
reaching-tests-tier died after landing `558d5614a` (reconciliation fixture
repair; request narrowed to seon.source-reconciliation-test +
seon.test-reaching-test). debug-page-cost died mid-slice with uncommitted
edits in src/seon/render.clj and test/seon/render/retained_test.clj —
preserved, untouched. p1-ambient-state, slow-tests-merge (row 3 landed
`fc90bb972`; row 4 `e3af34340`), test-runner-waste (`ea5861329` landed) will
fail on their next turn. Owner decision pending: credits, or Opus
implementation threads under the same rules.

## Open class noticed by two lanes (00:40Z)

Both n7-eval-call-edges and n1-mcp-bypass hit "the canonical fixture retains
the old function contract after adoption" in the development JVM
(docs/seon/issues/canonical-fixture-retains-old-function-contracts-after-adoption.md):
the in-memory fixture base caches a projection whose contracts predate the
hot-adopted definitions, so an in-process regression can be blocked by a
stale contract the cold worker never sees. Same family as the projection
carriage work (P1); assign after P1's current slice.

## 01:15Z — a second orchestrator session is live

A separate Claude Code session (the owner's steward-platform dialogue, pid
75117) launched lanes `cold-page-plan` and `hook-publication-race` from the
wave-2 spec templates. This session does not resume, stop, or gate them.
Gate coordination across sessions rests on `tmp/test-slots` (two slots
machine-wide, orchestrator-only mode); each session runs at most one gate
at a time, so the machine sees at most two.

## 01:50Z — default reforked by the other session; status refusal

`default` is now pid 7595 (start 01:36Z), reforked by the steward session.
`runtime_status` refuses: `seon.problems/problems refused return value at
[... :seon.problems/occurrences]: expected an integer, got an integer` —
a zero-occurrence signature against `[:int {:min 1}]`, and the grammar
dropped the constraint. Issue filed
(problems-refuses-its-own-zero-occurrence-signature.md); an Opus agent is
fixing both under the REPL rule (cost rule: Opus for mechanical fixes).

## 02:05Z — gates blocked by the steward session's schema commit

`6a491f0b3` (steward session) declared `:seon.issue/issue` with a render
pair whose contract the publication refuses; `bin/test`'s shared published
base cannot be built at HEAD, so no cold gate can run for anyone. That
session's five lanes also hold uncommitted edits in src/seon/problems.clj,
error.clj, cluster.clj, cluster/source.clj, test.clj, test/runner.clj and
several tests; this session's Opus fix for the problems zero-occurrence
refusal was stopped before writing to avoid clobbering them. Handoff to the
owner: the steward session must fix the seon.issue render-pair contract (or
revert `6a491f0b3`) before any gate here resumes; turn-test-reds' 18b re-gate
and the problems fix are queued behind it.

## 02:20Z — coordination with the steward session

The steward session (seon-61) owns the seon.issue refusal via its
issue-family lane and is repairing it; cold gates here HOLD until its
ledger line says HEAD publishes. Its in-flight lanes: error-graph
(error.clj, cluster.clj commit-fault!, seon.error*.edn, problems.clj
readers), reach-digest (test.clj, test/runner.clj, seon.test.edn),
program-provenance (fn.clj, program.cljc; also fixes the form-span tuple
retraction blocking `init --dev`). Landed from that side today:
`474234fb7` (system turns open again), `ac34ce5a3`/`ff351811b`/`54f9155f1`
(fn/test entity pairs), `3402913f3` (file/span + seon.lint), `17dd75e89`
(usage/renderer facts). Handed to it: the third-error-writer cause behind
the status refusal (fold into error-graph). It reforks default once more
after its schema edits land.


## 02:30Z — HEAD publishes again (steward session)

`ff48a4110` (issue-family) declares the `seon.issue` render pair contracts
the publication accepts; the hook's batch at 02:19:55Z converged on the
tree carrying that fix (commit `6aa9fc8a`, digest `f53a8de4…`), and
`bin/seon init --dev default` exited 0 after `f9a46b0bd` (tuple retraction
with values). Cold gates may resume. Still uncommitted from the steward
session: error-graph (error.clj, cluster.clj commit-fault! region,
problems.clj, seon.error.edn, their tests) and issue-family (issue.clj,
cluster/source.clj, bin/seon, bin/issues-index, script/seon/dev/issues.clj,
seon.agent.edn). Landed today: reach-digest `f2d537187`/`e5de6ebc7`
(warm check 2.65 ms; one changed function 56 ms, three digests recomputed),
program-provenance `3402913f3`/`f9a46b0bd`, entity-pairs, generated-read
fix `474234fb7`, attempt facts `17dd75e89`.

## 05:40Z — default's prepl saturates under in-process regressions

Eight lanes across both sessions ran their in-process regressions inside
default's JVM at once; the hook's publications exited 124 on their bound
and `runtime_status` timed out. The steward session paused four of its
lanes. Same shape as the test-JVM saturation: the development JVM's prepl
is one shared resource; in-process test runs need the same admission as
gates (a slot, or the reaching-tests check running them serially).

## 06:05Z — recording failures explained

The "result-cluster store held by another live process" and
"prepl-response-silent" recording failures in batches 19–20 came from
`f2d537187` (reach-digest): `completion-reach-digests` opened the gate's
shared published-base store under its lifetime flock on every
`commit-results!`. Replaced at HEAD by `1b5c09e15` (canonical private
fixture, no store open); recording should work again from the next gate at
or after that commit. reach-digest's only remaining cold red belongs to
agent-call-edges.

## 06:20Z — batch 20 attribution (steward session's Opus triage, `b45881f32`)

None of batch 20's reds attribute to error-graph. A PLATFORM CLASS since
`26ec13420`: `seon.db/write-map-error` validates a partial entity map
against every entity schema that lists its identity attribute, so fixture
seeds are silently refused and tests assert against an empty database
(issue raw-write-validation-refuses-reverse-refs-and-partial-entity-maps,
blocker; astra lane write-validation-class on db.clj). That explains
seon.cluster.turn-test growing 18 → 48 red between batches 19 and 20; the
turn-test-reds lane was told to classify seed-refused tests as blocked by
that lane and work only the rest. Other attributions: render.faults →
attempt-and-eval-facts; three transcript/turn-loop tests stale (Opus fix
later); turn-test settlement row → agent-call-edges (`76774d044`);
flow-test kill_child reads absence as readiness. Default's own fault
committer transactions are refused on default ("at [0 :seon.error/at]")
after error-graph's schema change: RESET NEEDED; the steward session
reforks after issue-family lands and messages first.

## 07:10Z — HOLD on fresh-base gates

Steward session finding (issue
fixture-base-population-refuses-without-a-carried-projection, `ebc718e7f`):
at committed HEAD, `seon.test-support/create-base` with no published base
refuses `:seon.schema/missing-projection` from
`accrete-schema-population!` (cluster.clj:1347 binds forms but no
projection) — introduced with write-validation-class `20d30a0bd`. Batch 22
was green because it reused a cached base. Gates HOLD until the fix commit
is at HEAD; batch 23 (already running) is allowed to finish and is void if
it refuses at its first with-database. Also: renderer-fn residual fixed
`52044b4f4`; attempt-and-eval-facts re-run queued for the first batch after
the hold.


### 2026-09-16 04:20Z — default refused every publication; second refork

`default` (pid 7595) had `:seon.test/reach-digest` installed as
`:db.unique/identity` from an earlier bridge; the current bridge derives no
uniqueness, and adoption's `declaration-changes` compares only the keys the
new declaration carries, so the drop read as compatible. Every test with a
changed reach digest then carried two identities and `seon.fn/index-tempids`
refused the whole publication ("Program indexing found multiple entity
identities."). Symptoms: hook "Publication did not finish within its declared
bound", `bin/seon init --dev default --changed …` hanging, batch 25 results
not recorded (`live-prepl-unavailable`). Issue:
`docs/seon/issues/adoption-misses-a-dropped-uniqueness-on-an-installed-attribute.md`
(fix handed to the steward session, which holds `src/seon/cluster.clj`).
The steward session is reforking default; the recording-only platform tier
runs after its message. Also landed: `86b4c8ff4` — the runner's confirmation
test wrote `workers/` into the repository root when no test root property was
set; `worker-parent` now refuses that state and `/build/` is ignored.
Machine load at the time was Spotlight (`corespotlightd` 123%) and Backblaze
(`bztransmit` 99%), not our JVMs.

### 2026-09-16 05:00Z — recording refusals: cause found (dc5c7d57f)

Three gates in a row could not record results on a cluster that answered
evaluations in ms. Research verdict
(`gate-recording-refusals-2026-09-16.md`): the recorder never sent its form;
`live-root-value!` gates on a census pre-read whose reply is parsed with
`clojure.edn/read-string`, and the dev JVM's in-process fixture branch
keyword `:seon.test-support.fixture/0` is unreadable EDN → `reachable? false`
→ `live-prepl-unavailable` (batches 25, 26B); the same probe timed out under
the 30 s silence backstop during the platform gate (26A). Refusal reproduces
in 268 ms; the recorder's form answers over the same socket in 13 ms. This is
the pre-read-vs-authority class (AGENTS.md §"No seam may act on a pre-read").
Opus fix lane launched: dissolve the pre-read (send is the authority), mint a
readable fixture keyword, make the parse seam total, one regression.
turn-test-reds lane still live (4 commits, latest `b3266e25b`); its gate runs
when it stops.

### 2026-09-16 05:40Z — the stall class: minute blob sweep under an exclusive permit

Latency lane (commit `4764c233a`, no source edit): the recorder is fine
(`commit-results!` 699 ms); `seon.blob.retention/reclaim!` runs every minute,
takes the store's exclusive sweep permit and walks 380,285 konserve keys
(64.6 s) to find 3,624 blobs; `datahike.api/branch!` waits unbounded, the
reachability gate is closed 96.4% of the time, `registry/branch!`
57–74 s. This explains the recording silence (batches 26A, 27A), the hook
publication timeouts, slow forks/retires, and the fresh-operator-test hang in
batch 27 B (declared-long test slowed 2.4× to the 270 s bound; `7c7395c8a`
refuted as cause). Issue
`blob-retention-sweep-starves-every-roster-writer.md` (blocker). Lanes:
astra `retention-sweep` (spec `tmp/orchestrator/wave2/retention-sweep.spec`:
candidates from blob-write facts, permit only around deletes, event-driven
budget check); Opus research `store-footprint-2026-09-16.md` (why 72 GB /
380k keys; reset vs `gc-storage!`).

### 2026-09-16 06:10Z — retention: option 1 (dissolve) chosen at the design gate

retention-sweep's investigation (`e375a3a97`, `retention-sweep-2026-09-16.md`)
measured inventory 70.3 s and the gate closed 96.6% even with blobs UNDER
budget, and found Datahike's reachability GC already preserves referenced
blobs (`registry.clj:519-548`, weekly `root/maintenance/compact`). Options:
(1) remove automatic byte-budget retention, keep existing GC — 1–2 h,
gives up byte-budget enforcement; (2) keep the policy on root facts with
guarded deletion — 1–2 days across owners; (3) manual-only retention —
30–60 min, manual calls still pause writers. The orchestrator chose (1)
under the dissolution law (owner not present; reversible by one revert) and
resumed the lane. Flagged for the owner.

### 2026-09-16 06:30Z — store footprint: 67 GB unreachable, GC never runs in practice

Research `cfef57241` (`store-footprint-2026-09-16.md`): 71.6 GB / 382,425
files, 93.3% `pss/leaf` copy-on-write index nodes; created 2026-09-08; grew
2.8 → 15.4 → 52.9 GB per day 09-13 → 09-15; ~50 files / ~1.5 MB retained per
transaction on `:cluster-default` at ~6 tx/min, dominated by edit-hook
`:seon.fn.ast/*` upserts and the per-minute maintenance result rows. The only
GC caller (`registry/collect!`) is weekly and the store grew through its
window. Plan: retention-sweep removes the per-minute writer; the steward
session resets default's store at the next refork (disposable-data rule);
GC cadence appended to the existing issue
`storage-gc-runs-without-a-cutoff-so-it-reclaims-almost-nothing.md`.

### 2026-09-16 07:20Z — retention-sweep landed (`5a10f5dfa`, evidence `af0359703`)

Automatic byte-budget blob retention removed (dial, schemas, test, schedule
seed); default's seeded per-minute row retired by transaction; weekly
reachability GC (`registry/collect!`) remains. Live on default: reachability
gate open 3.36% → 100% over 90 s, zero scheduler key walks, in-process 4 tests
/ 21 assertions green, adoption converged. Issue
`blob-retention-sweep-starves-every-roster-writer.md` resolved and archived.
Gate (seon.schedule-test seon.blob-test seon.cluster.registry-test
seon.dev.fresh-operator-test + platform) waits on the cold-arming blocker
(batch 29) and runs on the fresh store after the reset.

### 2026-09-16 08:05Z — fresh-operator config proof: refuted as retention's

retention-sweep verified the batch-30 red in
`init-owns-current-source-and-dormant-cluster-lifecycle` is pre-existing:
both assertions pull the config entity from a raw `@connection`, which
carries no projection state, so `seon.db/pull` returns
`:seon.schema/missing-projection` (0 actual keys vs 77 expected); through
`(seon.db/db connection)` all 76 decision keys match (evidence `147010c50`,
issue `fresh-operator-config-proof-pulls-an-unprojected-database.md`).
Fixed `8208754cb` (two read sites through `seon.db/db`); gated in batch 32 with seon.fn-test on the steward's `7cfe02790` (program/shapes hand list killed).

### 2026-09-16 09:00Z — turn bookkeeping cost: config/apply! rebuilds, not the store

Research `c2972178b` (`turn-bookkeeping-cost-2026-09-16.md`): the six-form
bookkeeping path is ~100 ms warm (whole call-turn window 452 ms: prompt 217,
6× evaluate 134, 4× transact 38, settle 22, analyze once 16, request-profile
15 over 64 calls, issue tests 0) — the archived per-form disease has not
recurred. The 270 s worker bound is the FIXTURE: an empty `with-cluster` costs
~5.0 s (with-database 1 ms), ×48 trials ≈ 240 s. One cause, two halves, in
`seon.config/apply!` (called twice per fixture cluster, 2–3 s each,
already converged): `apply-compiled!` rebuilds `projection-from-database`
(`config.clj:459`, 682–720 ms) where the carried projection is 0 ms — the
§2.1 defect relocated to config — and `reconcile/plan` (`:479`) spends
773–984 ms to return 0 operations. Store size refuted (MemoryStore fixture;
1-datom transact 3 ms); the reset changes none of this. Plan: hand
`apply-compiled!` the carried projection; a converged apply becomes a read on
`:seon.config/applied-manifest-digest` + basis `:t`; seed the fixture
cluster into the canonical base. Flagged: a 13 s / 89k-datom publication
transaction on default; `request-profile` derived 64× per turn. The 5.6 s
first-turn cost in a fresh worker is not yet attributed (needs a cold JVM).

### 2026-09-16 09:40Z — store reset; config-apply-cost lane launched

Steward session ran `bin/seon reset --force`: default pid 27828, forked from
current-src at `f8c00a5be`; store 72 GB → 107 MB. Astra lane
`config-apply-cost` launched (spec `tmp/orchestrator/wave2/config-apply-cost.spec`):
carried projection into `apply-compiled!`, converged apply as a read on the
applied-manifest digest, dissolve the zero-op reconcile plan cost, one
`apply!` per fixture cluster. Steward owns the 13 s publication issue
indexing (`issue-indexing-at-publication-costs-13-seconds.md`) and filed
`request-profile-is-derived-64-times-per-turn.md`.

### 2026-09-16 10:30Z — publications refused by a foreign analyzer error; recording races current-src

Hook log: every current-src publication since 06:23Z refused — static
analysis finds "Unresolved var: support/test-context" at
`test/seon/test_failure_facts_test.clj:120` (reach-closure's new regression
file); the config-apply-cost lane's edits were refused for it at 06:24:21.
Gate recording on the fresh store is rejected with two
`datahike.versioning` "Branch head changed before force-branch!"
(`:stale-branch-head` on `:current-src`) during batch 34 A: the recorder's
scratch fork/retire (`source.clj:300-323`) pre-reads the current-src head
and races the hook's publications — the pre-read-vs-authority class again.
Both handed to the steward's reach-closure lane.

### 2026-09-16 11:20Z — config-apply-cost landed (`6313d2006`, note `8f17a527c`)

Both projection rebuilds in `apply-compiled!` removed (carried projection
reused) and the duplicate fixture apply deleted: an identical-plan apply
612–1,336 ms → 80–86 ms. Digest-only convergence was refuted by the lane
(initialization changes and hand edits remain possible), so the plan still
runs but no longer rebuilds. One class regression added. The lane could not
verify the full fixture target in-process: the restarted default's shared
fixture base had cached a missing-classpath exception (the base-poison
class again, issue `in-process-test-runs-poison-the-shared-fixture-base`).
Gate: batch 36 after batch 35.

### 2026-09-16 11:50Z — base poison on pid 37572: the evaluation context loads test namespaces

Orchestrator's one-shot base refresh (the turn-test-reds form) failed in
25 s: `seon.sci.eval` (`eval.clj:1026`) refused to load
`seon.dev.dependency-cache-test` — cause chain: syntax error at
`dev_cache.clj:1:1` ← `clojure.tools.build.api` not on the classpath. Since
reach-closure made tests program rows, the base SCI context requires every
test namespace too, and `dev-cache` needs a :test-alias-only dependency the
dev JVM lacks (the test loader adds :test paths, not its extra-deps). Every
db-backed in-process run on default is blocked until reach-closure decides:
the evaluation context stops loading test namespaces, or the loader carries
the :test alias's deps. Handed to the steward's reach-closure resume.

### 2026-09-16 12:40Z — base poison root cause (`653d4d4ef`, steward): thread context classloader

Refutes the reach-closure hypothesis. `seon.sci.eval/classpath-locatable?`
used one-arg `io/resource`, i.e. the calling THREAD's context classloader; an
in-process `seon.test/run` binds the test DynamicClassLoader around its body,
so the first base construction inside it saw all 214 test namespaces as
servable and required them until `dev_cache.clj` → tools.build failed. The
fix resolves through the system classloader; regression
`seon.sci.eval-test/process-membership-ignores-a-thread-context-classloader`.
A poisoned delay cannot be un-poisoned in place: default restarted. Queued
for the next batch: seon.sci.eval-test. Follow-on issue filed by the steward
(host-namespace! find-ns branch admits any already-loaded namespace).

### 2026-09-16 13:20Z — config-apply-cost second slice (`5e5aa6293`)

The batch-36 config/reconcile reds were refuted as a semantic regression:
the fixtures' unchecked writes were being REFUSED by the stricter admission
(rows the schema no longer admits) and the tests read the absence as a
changed semantics; the lane fixed the fixture inputs, carried the database
value through `seon.db/db` (the raw-deref class again), and restored the
shipped `:seon.test/check-time-limit-ms` decision missing from
`config/default.edn`. One authorised cold iteration: 27 tests / 128
assertions green. Re-gated in batch 39 with the transcript second pass.

### 2026-09-16 13:50Z — custody-stability reds fixed (`3c115ff15`)

Both were fixture/roster drift: the isolation fixture wrote messages without
the now-required `:seon.message/to` (all writes refused, assertions passed
over an empty database — absence read as health); it now upserts a real
recipient. The custody-returning roster gained two reviewed members
(`seon.cluster.agent/acquire-context!` → ctx,
`seon.db/carry-connection-projection-state!` → connection). The lane kept the
literal expected set as the dated reviewed record whose drift checker is the
test itself (the derived side is already `:seon.fn.arity/output-refs`, so a
query on both sides would be tautological) — accepted under §2.2's
"enforced by a checker" clause. In-process 5/0/0 and 2/0/0. Batch 40.

### 2026-09-16 14:20Z — recording rejected on a healthy JVM: read-only research launched

Every gate since batch 30 ends `persistent results NOT recorded … The cluster
rejected the prepl operation`, including batch 40 on an idle pid 45917; the
wrapper drops the rejection value. Opus read-only lane launched to capture
the exact rejection by sending the recorder's own form to default and to
verify the stale-head race hypothesis (`gate-recording-rejection-2026-09-16.md`).

### 2026-09-16 15:10Z — recording rejection: the wrapper hides the cause (`bfb578efb`)

Research verdict: the operator replaces a prepl `:exception` reply with the
fixed sentence "The cluster rejected the prepl operation."
(`script/seon/fresh_operator.clj:1605`) and the runner keeps only the message
(`runner.clj:2007`), so the cause is unnameable from any log by
construction. The recorder's form itself is GREEN on idle default over the raw
prepl socket (2,010 ms, committed refs); every in-cluster failure returns as a
value, so the `:exception` must come from prepl's own read/emit path
(`src/seon/cluster.clj:478-495`). Falsified: the stale-head race (retried,
diagnostic value) and a concurrent publication. Opus fix lane launched: the
wrapper carries the cluster's cause, the notice prints it, regression
replaces the stale-sentence assertion at `fresh_operator_test.clj:1776`;
then one reproduction through the operator path to name the real cause.

### 2026-09-16 16:30Z — wrapper fix landed (`b77c553e4`); cause still unnamed

The operator's `:exception` branch now raises `:seon.fresh-operator/prepl-exception`
carrying the cluster's `Throwable->map` (cause, via chain, ex-data, first
frame, clipped form, advertisement), and the runner's notice prints kind,
message and data. Two more hypotheses falsified live: the full recorder
through `live-root-value! "."` commits on default in 2,229 ms, and reply
sizes up to 739 KB printed return complete — so neither payload size nor a
failure value explains the sentence; the archived gates that printed it were
green 15–16-test runs. Batch 44 is the first gate whose notice can name the
rejection. Stale-sentence assertion at `fresh_operator_test.clj:1776`
replaced (4 in-process regressions green).

### 2026-09-16 17:10Z — RECORDING CAUSE: "Method code too large!"

Batch 44 A's notice (the first with the cluster's cause):
`:seon.fresh-operator/prepl-exception … clojure.lang.Compiler$CompilerException:
Method code too large!` via `IndexOutOfBoundsException` at
`clojure.asm.MethodWriter computeMethodInfoSize`. The recorder sends the
entire completion as a literal inside one `(try (let …))` form, so the
cluster compiles a method over the JVM's 64 KB bytecode limit. Data was
travelling as code — the transport-law violation. Explains every failure
since batch 30 (batch 37's 21-test run recorded; 86–178-test runs did not;
the per-result payload grew with `8199364a2`). Fix lane launched: the
runner writes the completion to a file under its run root and sends a tiny
form naming it; the cluster reads it with clojure.edn; regression = a
2,000-result completion sends a form under 1 KB.

### 2026-09-16 18:00Z — recorder fix landed (`60516d27c`)

`persistent-results-form` inlined the completion as a quoted literal;
live, a 2,000-result inlined form reproduces `Method code too large!` on
demand. Now the completion is staged as EDN under the coordinator's run root
and the sent form names only the path (207,166 → 890 bytes for 2,000
results; 500-result live send 261 ms); the read side is total
(`:seon.test.runner/staged-completion-unreadable` on a missing/truncated
file). Finding to carry: `requiring-resolve` returns nil for
`seon.cluster/running-instances` inside the cluster where `ns-resolve`
resolves it — caught only by the live send. Batch 46 is the recording proof.

### 2026-09-16 18:40Z — adoption-identities `#{nil}` fixed (`bb46455fb`)

`seon.cluster/changed-identities` used a per-call roster of three identity
attributes where `seon.program/identity-attributes` declares six; every
published file carries a `:seon.fn.file/path` digest row and one
`:seon.lint/id` row per finding, which the roster read as nil. Now
`adoption-identities` derives through `seon.program/row-identity` (the one
authority) and keeps declaration identities only (including
`:seon.schema/key`, which the roster silently dropped). Regression
`seon.adoption-rows-test/adoption-identities-carry-no-nil-member` 5/0/0;
live `init --dev default --changed src/seon/cluster.clj` exit 0, commit
advanced. Noted for later: the write diagnostic says "expected a set, got a
set" (seon.db admission should name the offending member); a scalar
adoption recorded an empty identity set (possible under-recording).

### 2026-09-16 20:00Z — adoption-rows reds: a fixture namespace poisons the worker's kondo cache

`d4a201237`: the agent-row-fault test read the error identity row for
`:seon.error/message`, which lives on the occurrence and is answered by the
declared projection `seon.error/latest-fact` — read fixed, owner correct;
8/0/0. The cold `adoption-identities-carry-no-nil-member` red was NOT a stale
kondo cache (the snapshot ships no cache): `seon.fn-test/keyword-usage-is-
indexed-per-declaration` analyzes a decoy `(ns seon.error)` fixture with the
shared kondo cache on, so the worker's `seon.error` cache entry becomes a
stub and every later analysis calling `error/diagnostic|prepare|recording`
in that worker is refused — worker-global state mutated by a test, hidden
until now by same-JVM ordering. Issue
`a-fixture-namespace-poisons-the-workers-shared-kondo-cache.md`; Opus lane
launched to isolate fixture analysis at the analyzer seam (no shared cache
for paths outside declared roots) with one class regression.

### 2026-09-16 21:00Z — fixture kondo-cache class killed (`b50f4ddc7`)

`seon.fn.analyzer/analyze` now runs kondo with `:cache false` unless every
analyzed path is the checkout's own declared source (roots from `deps.edn`
`:paths` + alias `:extra-paths`, minus the test alias's `.`); fixture roots
under `tmp/` are isolated by construction and the stdin special case
dissolves into the rule. Reproduced then killed live: the old rule shrank
`seon.error.transit.json` 13,570 → 194 bytes and made `await.clj` report
`Unresolved var: error/diagnostic`; the new rule leaves the entry
byte-identical. Regression
`fixture-analysis-never-writes-the-checkouts-dependency-cache` 3/0/0.
Proofs on evaluated forms (default does not adopt right now). Two
pre-existing reds noted for cold verification: `analyzer-test/
ordered-forms-use-existing-context-and-original-row-numbers` and
`fn-test/keyword-usage-is-indexed-per-declaration`'s database assertions
(likely the un-adopted default). Batch 51.

### 2026-09-16 22:10Z — analyzer-test prelude drift fixed (`3d2384dfb`)

The synthetic prelude in `ordered-forms-use-existing-context-and-original-row-numbers`
named the retired `seon.run/complete`; it passed only through the stale
shared kondo cache. Now `seon.turn/open?` (declared as an available-function
row in the prelude); assertions untouched; 7/0/0 in-process. Batch 53.

### 2026-09-16 22:40Z — the last two turn-test defects (`b166c4246` tests, `42661e5b0` owner, `316337e98` research)

delimiter-repair: the fixture counted every evaluation (system turn 0 now
stores the opening), read a retired attribute (the 4 instrument ERRORs), and
matched two turns' replies; now agent-authored evaluations of the driven turn
via `:seon.eval/shown`; 15/1/0 — the one remaining failure is the 300 ms
bookkeeping bound at 6,162 ms, ATTRIBUTED: `seon.fn/gate-set`
(`turn.clj:3171` → `fn.clj:1052`) is 5,976 ms of a 6,416 ms window (93%) —
one recursive-rule Datalog query per installing definition (the earlier
~100 ms warm figure was a turn whose definition did not install). Real
defect, needs its own lane on `src/seon/fn.clj`.
a-lost-model-call: the reason lives on the occurrence (test read fixed);
the prompt half was two defects — the stand-in evaluator replaced generated
reads with `1` (now real SCI) and `seon.error/faults-form` selected only
`:seon.error/fn`-reachable errors so a provider fault (no function ref) was
durable and invisible (absence-as-health class); now one `or-join` also
selects errors whose occurrence names the agent; 5/0/0.
Boundary: proofs on reloaded source before default's adoption converged;
afterwards the in-process cluster-turn fixture stopped deriving work
(`next-agent-work` → nil) with another lane's wake/agent edits dirty — theirs;
the cold gate is the proof (batch 53).

### 2026-09-16 23:00Z — gate-set-cost astra lane launched

`seon.fn/gate-set` costs ~6 s per installing definition (one recursive-rule
query over the whole call graph per call). Lane spec
`tmp/orchestrator/wave2/gate-set-cost.spec`: prefer the reach-closure facts
as a lookup; else one derivation per turn; results must equal the current
derivation; the 300 ms bookkeeping assertion stays and must pass.

### 2026-09-16 23:40Z — gate-set-cost landed (`1d141d26a`)

Gate sets are now derived from indexed incoming call edges instead of one
recursive rule per identity: `gate-set` 6,753 → 11.4 ms with identical
results across all 4,879 identities; the changed-function check (`seon.test/
check`) 5,923 → 5.4 ms; the six-form turn's bookkeeping 6,530 → 366 ms.
The 300 ms assertion still fails at 366 ms — the residual is the prompt
(~217 ms, `request-profile` derived 64× per turn; issue
`request-profile-is-derived-64-times-per-turn.md`), next dissolution.
Reach regressions 25 assertions green; adoption was blocked by the
`:seon.issue/agent` index change (refork in progress). Batch 54 gates it
with start-arms on the reforked default.

### 2026-09-17 00:00Z — request-profile lane launched; default reforked (pid 95853)

Steward reforked default for the `:seon.issue/agent` index addition
(adoption compares declarations with `=`; index addition is accretion —
their fix at `cluster.clj:888`). Opus lane launched on
`request-profile-is-derived-64-times-per-turn` (derive once per render
request, carry it; §2.1) — the residual behind the 300 ms bookkeeping bound.
Batch 54 waits for adoption to converge on the new pid.

### 2026-09-17 00:40Z — request-profile landed (`15a15e9c1`)

Premise refined: `request-profile` already returned a carried profile first
and the render proc derived once (146 calls / 1 derivation on a cold prompt);
the real per-form deriver was `seon.turn/evaluate-sources` (six forms = 6
derivations / 14.7 ms). Now derived once before the loop and carried on
every evaluation request (7 calls / 1 derivation / 2.4 ms;
`evaluate-sources` 48.8 → 29.4 ms). Regression
`seon.cluster.evaluate-sources-test/one-turn-derives-the-render-profile-exactly-once`
3/0/0. The 300 ms bookkeeping assertion measured 393–954 ms in-process on
the SHARED loaded dev JVM where `db/transact!` costs 227 ms/call (3–4 ms
idle) — the cold gate decides. Exonerated red filed:
`ordered-evaluation-preview-test-refuses-agent-already-running.md`.
Batch 55.

### 2026-09-17 01:30Z — remaining bookkeeping cost attributed (`9a4e873d2`); record-tx 6 s in-process

Warm on pid 95853, six runs: 365–531 ms, never under 300. Inside the
window: ONE settlement Datahike commit 219 ms (batch size: `turn.clj:3510`,
tx-data `:3541-3550`), the defining form's install 120 ms (`analyze-forms`
runs twice for one form), three other transacts 19 ms, request-profile 5 ms,
gate-set 1 ms. Per-turn, not first-use; the cold gate's extra ~230 ms is a
fresh worker's class loading / first analysis / first fork, inside the
window by construction. Verdict: 300 ms is not achievable warm today; the
assertion should bound settlement+writes and assert the install separately
rather than be raised. Incidental and larger: `seon.test.runner/record-tx`
costs 5.9–8.0 s per IN-PROCESS test run against default's file store —
this dominates every lane's REPL-first loop; read-only research launched
(`in-process-record-tx-cost-2026-09-17.md`). Settlement batch size and the
double analyze-forms go to a turn.clj lane after ordered-evaluation lands.

### 2026-09-17 02:00Z — render-coverage reds: no root address on the fixture's render request

Steward's dir-elision landing proved the floor is not the cause (identical
12/20/3 before and after seon.print): `seon.render.value/node-id`
(`value.clj:82`) requires a caller-supplied root address and
`render_coverage_test.clj:37` supplies none; it passed only while selection
found a declared producer for effect receipts. Opus lane launched to decide
at the owner (derive a stable root when none is supplied per §2.4 "renders
never refuse an ordinary value", or prove production always supplies one
and fix the fixture) and to make the typed-unknown contribution total.
Queued: batch 57 = dir-elision r2 `90f7abec0` (+ render-coverage when fixed).

### 2026-09-17 02:30Z — ordered-evaluation preview fixed (`91cd63e5a`); two lanes launched

Two causes, one class (a transaction report never read): the fixture closed
turns with a map that write admission refused (missing `:seon.turn/agent`),
so the turn stayed open and `agent-already-running` was genuine; and
`seon.turn/stored-record-content` (`turn.clj:1484`) compared resolved tx refs
against the request's `"datomic.tx"` tempid so an identical re-record could
never be the specified no-op. Fixed at the owner (`recorded-run`) and in the
fixture (closes through `turn/close-tx`, asserts the report); 36/0/0 (was
24/9). Findings: `seon.fn/tests-reaching` throws "gate-set refused return
value at [0]" (regression from `1d141d26a`; breaks the reaching-tests
tier) — Opus fix lane launched on fn.clj; a fixture's JVM-global
`with-redefs` write counter is sound only in the gate's worker. turn.clj
released → astra lane `turn-settlement-cost` launched (settlement delta,
one analyze-forms per form, budget semantics, the 300 ms assertion
re-expressed).

### 2026-09-17 03:00Z — the write floor: default's store is 12 GB again; every commit 4–9 s

`in-process-record-tx-cost-2026-09-17.md` (`8a9832ba8`) falsified the recorder:
record-tx builds its data in 263 ms; an EMPTY-DELTA transaction costs
3.9–8.9 s on default vs 46–48 ms on a fresh file store; idle sampling rules
out contention. `data/store` is 12,043 MB / 101,506 keys eight hours after
the 107 MB reset — copy-on-write index churn with nothing collecting it.
Every write on the dev cluster pays the floor (hook adoption, turns, probes,
recording). Read-only lane launched on write latency vs store size (per-
commit files/bytes/fsyncs, growth curve on scratch stores, GC dry-run) to
bring three owner options. Second-order recorder waste (reaches re-derived,
814 lookup-ref pulls, identical `:seon.test/reach` churn per run) queued for
a runner.clj lane.

### 2026-09-17 04:20Z — write floor REFUTED (`83d3e92a0`): growth, not latency

`write-latency-vs-store-size-2026-09-17.md`: an empty-delta commit on the
12 GB / 107k-key store costs 23–38 ms writing 1–2 keys; a clean store is
flat 17–32 ms / 2 files from 4k to 800k datoms; APFS directory fsync is
flat 8.6–11.1 ms. The 4–9 s samples were single commits flushing a large
accumulated dirty-leaf set (473 files / 39 MB in one): branching factor
4096 → ~300 KB leaves × 6 indexes × fsync per blob. GC is wired
(`operator/collect!` → `registry/collect!`) but only on the weekly cron; the
lane's intended dry run ran a REAL collection (Datahike's gc ignores
`:dry-run?`) — 12,043 → 10,406 MB and still running. Three owner options in
the page (footprint/key-ceiling signal → collect!; a per-commit bound that
names the batch; narrower leaves or LMDB). `seon.fn/exact-source` threw
IndexOutOfBounds again during a publication coincident with a live fn.clj
edit — a typed "source changed during analysis" refusal is owed (queued
after the gate-set-contract lane releases fn.clj).

### 2026-09-17 04:50Z — render-coverage reds landed (`ac95db78a`, issue `f10d2e8ef`)

Neither a missing root nor the shadow class: the fixture's single seed
`transact!` was REFUSED (`{:seon.fn/sym "my.fs/read"}` lacked
`:seon.schema.admission/source`), nothing seeded, every render got `{}` and
fell to the floor — the unchecked-fixture-write class again (the fixture now
asserts every report and seeds in two writes). Separately proven live:
`seon.render/producer-argument` strips `:seon.render.call/id` before a
declared producer runs (`render.clj:196`) and the page walk carries no
`:seon.render.value/root` (`web.clj:2977`), so `node-id` now derives the
address from the value's own installed identity attribute — no digest
fallback (surface ids must be injective). A stale clause asserting an
`:seon.effect/run` unit removed. In-process 39/0/0, 7/0/0, 12/0/1. The
remaining ERROR is `render.clj:995`: `invocation-unknown` passes a nil
`:seon.error/value` for a time-limited producer (issue
`a-time-limited-render-producer-passes-nil-where-the-typed-unknown-requires-a-map.md`,
render.clj — steward's).

### 2026-09-17 05:10Z — turn-settlement-cost landed (`3594331c8` `60e0ba923` `97d1f69e0`)

Budget semantics fixed: provider attempts and accepted replies count against
`:seon.issue/budget`; the opening (system turn 0) spends nothing — exactly
one provider turn follows the opening at budget 1 (37 assertions green).
Settlement writes 249 datoms with no unchanged namespace/program rows; the
< 50 ms commit target is unmet because projection rebuilding dominates the
settlement (§2.1, owner change owed — recorded in the research page). The
duplicate analyze-forms stopped at protected `fn.clj` (the lane proved the
shortcut lost same-turn call edges and removed it; owed at the analyzer
seam). The timing regression now bounds writes and installation separately
at 300 ms each. Batch 59 (cold): cluster.turn, turn, evaluate-sources,
cluster.prompt, render-coverage; platform recording after the reset.

### 2026-09-17 05:30Z — default reset (pid 17352, 99 MB); batch 60 planned

Steward reset default after the write-floor research reported; store 12 GB
→ 99 MB. Steward's render-selection landed (`68f1ad52c` `b67a9dfe3`
`33a4c2035`: selection's projection derived from the handed database value;
the html-views golden was a frozen pre-pair dump). Batch 60 after 59:
platform (recording) + render-simplification, html-views, error, render.web
(dir-elision r3 `415ab40fc`), + fn-test when the gate-set contract lane
lands. Steward's two Opus lanes: the three remaining 58b reds + the
`invocation-unknown` nil issue; a sweep of raw `db/transact!` fixture writes
onto `transacted!`.

### 2026-09-17 05:50Z — gate-set return contract fixed (`5ffa964cb`)

`gate-set` concatenated its three selection queries without reading them, so
a flat `seon.db` refusal spliced in as map entries (element 0 = the MapEntry
`[:seon.db/invalid-read true]`) — surfaced only when a read refused, which is
why healthy runs never reproduced it. Every read is now checked and its
refusal returned whole; `gate-set`/`tests-reaching` declare
`[:or [:vector :seon.test/sym] :seon.error/value]` (the shape
`seon.test/reaching` already declares). Live: clean call → 96 sorted strings;
injected refusal returned whole; sweep of every indexed `:seon.fn/sym` clean.
Boundary: default was reforked mid-lane; the final in-process run sat in
`seon.await` 40 min on the fresh JVM (unverified end to end) and the last
publication ended "source changed during adoption" — the orchestrator is
re-adopting fn.clj now; batch 60 gates `seon.fn-test` cold.

### 2026-09-17 06:40Z — exact-source raw exception: third sighting, fix lane launched

`bin/seon init --dev default --changed src/seon/fn.clj` on pid 17352 threw
`IndexOutOfBoundsException` (no message) from `seon.fn/exact-source` again —
a span computed on one source snapshot read against bytes that changed in
between (a concurrent lane's live edit). Opus lane launched: spans read
against the captured source they came from, else a typed "source changed
during analysis" refusal naming file/span/digests; class regression;
live convergence proof. fn.clj re-adoption follows its landing.

### 2026-09-17 07:00Z — settlement projection rebuild dissolved (`2da44c50d`); prompt expectation (`c27727551`)

`seon.turn/row-tx` (`turn.clj:1281`) called `schema/projection-from-database`
on every declaration settlement and got back the identical object it was
handed — 209 ms of a 253 ms settlement window, three scans to learn nothing.
The premise "substitute the carried projection" was refined: inside a
`:db.fn/call` the writer's db carries the ENTERING projection, so a same-
transaction declaration that precedes a request needs the writer-side
derivation — now done only for that case, marked by the one owner that
orders those calls. Settlement and writes 253 → 53 ms warm, derivations
1 → 0, install 130 → 106 ms; regression
`a-settling-declaration-uses-the-projection-its-database-carries` 6/0/0.
Prompt: derivation correct, expectation stale — an open turn with no attempt
and no reply spends nothing (PRD §14) → "turns left: 100 of 100". No adopted
proof (adoption refused on a foreign renamed test identity); batch 62.

### 2026-09-17 07:30Z — exact-source typed refusal landed (`50a7110b7`)

Cause: `source-contexts` (`fn.clj:138`) captures each file's bytes while
`analyzer/analyze` re-reads the same paths (`fn.clj:1622`, `:1526`), so rows
and columns describe a different read than the text they slice — two reads
of one path (the pre-read class). The span read is now total: a span past
the captured text refuses with `:seon.fn/source-changed-during-analysis`
naming path, span, captured length and both digests; a fitting span reads
the captured source unchanged. Not yet adopted (adoption refuses for
everyone until the absent-identity extension lands) — batch 62 gates
`seon.fn-test` cold. Follow-ups: analyze FROM the captured text so there is
one read (sibling issue `source-analysis-can-slice-changing-files-with-
stale-offsets.md` open); adoption's retry predicate (`cluster.clj:2244`)
should also key on the analysis-time refusal (steward's file).

### 2026-09-17 08:00Z — live checks on the fresh default (pid 17352)

Debug page `/ns/my.agents.juniper/debug`: 0.42 s first request, 23–27 ms
warm (the night's opening complaint of 1.8 s stands fixed across every
landing). Store: 99 MB at the reset → 3.6 GB two and a half hours later
(~1.4 GB/hour of copy-on-write leaves with only the weekly GC) — the owner's
GC-signal decision is the standing priority. `bin/issues-index --check`
exited 1 on five notes declared `type: defect` (the README's one type is
`issue`); corrected → exit 0, 268 open notes, 0 refusals.

### 2026-09-17 08:30Z — one read per analyzed file (`d32a69073`)

`seon.fn.analyzer/analyze` takes the captured sources, writes them to one
private mirror under `tmp/analysis-mirror/`, lints that and deletes it —
rows and sliced text are the same bytes by construction (kondo's `run!`
takes only path strings; stdin is one file per invocation, 336 per build).
The shared kondo cache stays on: cache-off silently dropped one blocking
finding and 60 of 1,412 external arity annotations on fn.clj (absence of
signal); the mirror resolves back to its source path so b50f4ddc7's
ownership rule holds. fn.clj 495 → 452–524 ms; full build 5,711 → 5,164 ms.
Both source-analysis issues resolved. Boundaries: the lane's bound-expired
in-process run left pid 17352's fixture base with a shut-down writer (base
poison, restart clears); `init --dev` now fails in branch publication with
"no branch or commit :db to branch from" (cluster/*, steward's) — adoption on
default refusing since 10:17Z.

### 2026-09-16 ~11:00Z (UTC; earlier headers today are ~3 h low) — INCIDENT: data/store wiped to 28 KB

The steward found `data/store` at 28 KB with no `:db`/`current-src` branch
("no branch or commit :db to branch from", first logged 10:17Z); it was
3.6 GB an hour earlier. Evidence copy: `tmp/orchestrator/refork/store-wiped-
2026-09-17T1100Z/`; default reforked (data disposable). No lane of this
session ran a GC/reset/force-init on the main root after the 05:30 reset.
Lead: batch 61 A (platform tier) ran `seon.cluster.registry-test/
non-temporal-collection-marks-current-blob-references` and `reset-returns-a-
cluster-to-source-state` at 10:18:11–12Z. Read-only investigation launched
(`store-wipe-2026-09-17.md`); every gate that includes the platform tier or
registry-test is HELD until it reports.

### 2026-09-16 ~11:20Z — default reforked after the wipe (pid 38993, 99 MB)

Debug page on the new pid: 0.41 s first request, 25 ms warm. Steward's
fixture-write-sweep landed (588 sites / 96 files, four dead fixtures
repaired; request `fixture-write-sweep.txt`). ALL cold gates stay held —
not only platform/registry — until the wipe investigation names the root
resolution, because any destructive fixture with the same defect would
empty the store again.

### 2026-09-16 ~11:40Z — wipe verdict (`fbd9c0cd9`): genesis re-creation via an unset root

The store was deleted and re-created from genesis (`:branches` = `#{:db}`),
not collected: Datahike's gc only reads `:branches`; registry-test's fixture
builds its own `tmp/registry-test/<uuid>/store`. Healthy at 10:17:35Z;
window 10:17:35 → ~10:50Z; batch 61 A's platform tier began 10:18:11Z inside
it. Paths of the shape `(io/file <root> "data" "store")` with a nil/relative
root — `operator.clj:277/:294` cleanup and `store.clj:281` create-store! —
with root fallbacks at `cluster.clj:792` and `test_support.clj:112`
(bin/test-fast sets no operator root): the `86b4c8ff4` class. Exact caller
not established (no delete is logged; the evidence copy lost mtimes). Opus
fix lane launched: a bad root is unconstructable at both owners (typed
refusal before any delete, the owner's explicit `reset --force` kept), a
log line before every delete, the fallbacks removed, a symlinked-sentinel
class regression. All gates held until it lands.

### 2026-09-16 ~12:10Z — THE WIPE'S CAUSE, and the class closed (`ccccea806`)

`seon.cluster/operator-root` answered `-Dseon.operator.root` (the dev JVM's
property = the checkout) BEFORE the root the caller held, so an IN-PROCESS
fixture run in default's JVM — `test-support/populate-published-root!` with a
`tmp/<test>/<uuid>` root — resolved its store to `data/store` and ran
`delete-recursively!` + clone over the developer's store (verified live on
pid 38993: `resolve-bootstrap` with a tmp root returned `/Users/sean/src/seon/
data/store`). So a lane's repl-rule run wiped the store, not a gate. Fix: a
recursive deletion is admitted only when root and target are absolute, the
canonical target is under the canonical root, and the target is outside the
working directory's `data/` unless the caller DECLARES that directory as the
operator root the JVM was launched to operate (an argument, never a property
read at the seam); `operator-root` takes the caller's root first;
`create-store!` refuses to delete a store whose `:branches` roster is present;
every delete logs root/targets/bytes/operation/caller/pid. Regressions:
`seon.operator-test/a-destructive-root-is-declared-never-inferred-from-the-
working-directory` 22/0/0, `seon.cluster.store-test/creation-never-deletes-
a-complete-store-or-an-undeclared-root` 6/0/0, 8 neighbours 40/0/0. Left in
the issue: the platform tier must declare no destructive drill (a tier
selection checker).

### 2026-09-16 ~12:40Z — platform tier carries no destructive drill (steward `9fa1f101d` `f03adc248`)

`seon.test.runner/destructive-owners` is a measured roster (the three
functions that delete a path they did not create: the two
`populate-published-*` fixtures and `cleanup-root-under-lock!`), resolved
against the manifest with a refusal if one goes missing; the coordinator
verifies before the first platform task that no `:seon.test/platform` test
reaches an owner (reach from `:seon.fn/calls`), refusing with test +
shortest path; three platform tests that reached one moved to the bulk tier.
The "reaches the delete-admission seam" predicate was refuted by measurement
(42 of ~80 platform tests reach `create-store!` through `with-source-store`).
The orchestrator declined the proposed `:seon.fn/destructive` marker facet as
a speculative addition with one consumer. Lane rule: destructive tests are
cold-only. Batch 66 (after 65): platform (the proof) + seon.test.runner-test
seon.test-runner-test seon.cluster.cohost-boot-test seon.test-support-test.

### 2026-09-16 ~13:30Z — batch 65 B attribution: the sweep's loud-fixture wave

142 distinct failing tests in 47 namespaces (boot 12, turn-loop 12,
call-preparation 10, turn-work 10, schema-usage-guard 9, bootstrap 7,
web.jvm 7, db 5 …). Sampled blocks are "Fixture write was refused at the
write" through `transacted!` (`test_support.clj:250`), dominated by
`run transition refused: receipt-exists` — dead fixtures the sweep made
honest, not a regression of `ccccea806` (the platform tier was green apart
from the sentinel). Two look real: call-preparation (a supplied value reaches
the callee unreplaced) and boot-test's dead-holder recovery. Helper defect:
the refusal text in test output is elided by the AI render profile — a test
diagnostic must never be clipped. All with the steward's sweep lane.

### 2026-09-16 ~14:40Z — check's missing long filter (lane launched)

The triage of the 65 B reds found `seon.test/check` has no `:seon.test/long`
filter: a changed function reaching a real-boot long test pulls it into the
development JVM and burns the 120 s bound. The steward's Opus lane (`check-excludes-long-tests`) owns it: a default
exclusion with a named report mirroring the destructive one, an opt-in key,
and an expired bound that reports completed verdicts plus the typed expiry.
The orchestrator launched a duplicate lane two minutes before learning that
and stopped it (no commit; own hunks backed out by hand) — lesson: announce
a lane before launching when the other session is active in the same area.

### 2026-09-16 06:40 — store growth cause: a poisoned shared registry plus an unbounded turn-write retry

Measured on default (pid 38993) while `data/store` stood at 21 GB and
33,695 files: `:max-tx` advanced by 3 in 15 s, i.e. almost no transaction
was committing, yet files kept appearing at roughly a gigabyte a minute.
The steward (seon-61) read default's own log: the Datahike writer throws
continuously with `:malli.core/invalid-schema
:seon.schema-usage-guardb/entity-id` inside `seon.turn/row-tx` →
`seon.schema/projection-with-schema`, invoked from the turn loop's
`:db.fn/call` retain-transaction. That key is
`seon.schema-usage-guard-test`'s synthetic probe schema: an in-process run
of that namespace (one of the sweep's 46) registered it into default's
shared registry and never restored it, so every turn write fails and the
loop re-fires without bound; each failed attempt still flushes dirty
leaves, hence the growth. Two classes, both filed by the steward:

1. in-process tests mutating the shared schema registry (the registry
   needs the equivalent of `preserving-instrumentation-state`);
2. a failing turn write re-firing forever — bounded execution says a
   repeated write error must stop and become a fault, never a storm.

Corrections to earlier notes: the "write floor" and "growth, not size"
readings were symptoms of this storm, not of Datahike's commit cost; the
earlier retention sweep's regrowth (99 MB → 19 GB) has the same cause.
Verdict: the steward resets `default` (`reset --force`); lanes paused;
batch 68 runs cold and is unaffected. The regression for class 2 belongs
in `seon.turn` (a repeated `:db.fn/call` refusal closes the turn with a
fault); for class 1 in `seon.test-support`.

### 2026-09-16 — open ruling for the owner: partial upserts and required keys

From the steward's fixture-write sweep: write admission validates a
PARTIAL upsert of an existing entity (a map naming its identity plus one
changed attribute) against the entity's complete required-key set, so an
honest one-attribute update is refused for keys it never meant to touch.
Two readings: (a) admission validates only the keys present against their
own schemas, and required-ness is checked on the entity's resulting state
(a `:db.fn/call` at the authority); (b) an upsert must always be complete.
Reading (a) matches "adding is free, omitted keys are left unchanged"
(CLAUDE.md §3, datahike skill). Not decided here; the admission owner and
the owner rule.

### 2026-09-16 07:05 — batch 68 boot-test triage: attribution corrected

The dominant class (8 of 12 reds) was NOT `ccccea806` or `fe44a981b`:
`seon.cluster/stop!`'s `:seon.boot/instance` contract demanded LIVE
resources (the store connection, its flock, the turn-loop connection), so
an already-stopped instance — exactly the value its documented idempotence
exists for — was refused before the body. Latent since `2d2655922`, exposed
when the gate armed contracts; `6215ff0bc` had relaxed one of the three
members and left two. Fix `7f99fe695`: liveness is decided at the authority
(Datahike at the transaction, the flock's own validity at release), never
asserted as a shape; the store map references connection and lock objects
structurally, widening only. `d427728d7`: a boot-test fixture wrote a config
row no owner can mint (the digest) — rewritten as one `[:db/add …]` datom;
the sovereign-steer expectation updated to the current words, but that test
stays red on `:malli.core/invalid-schema` before any declaration comparison
(blocker issue filed; suspects `a3cbcd9a8` `0b910eb69` `bb46455fb`). Not
root-caused: boot-order index ordering, partial-clusters refusal,
incremental-source-refresh, two timeouts. Live defect found in passing:
`seon.render.transcript/render-ai` throws `ClassCastException` (String →
Date) for agent root on default (blocker issue). Landing note:
`batch-68-boot-test-reds-2026-09-16.md` (`868d7b992`). Batch 71 adds
seon.cluster.store-test and seon.cluster-test.

### 2026-09-16 07:07 — the io-prepl drop: a required dial without its decision killed the connection seam

The steward found the prepl drop the batch-68 triage thread met: every
io-prepl connection thread on default died at connect with
`seon.cluster/mcp-io-prepl refused bootstrap-effective at
[:seon.config.agent/write-refusal-bound]` (one log line per connection) —
the write-storm lane's new required dial was hook-adopted before its
default value was applied, so MCP, `config apply`, and gate recording all
saw "Connection reset". The pair is committed (`f86ec57ed`) but the
effective config in default's database still lacked the value and only a
restart reconciles it; the steward restarts default. Class (steward
filing): a required dial declared without its decision must not refuse
the socket — the seam serves a typed refusal in the value. Batch 71 runs
cold through the restart; if its recording is refused, that is this
restart, not the batch.

### 2026-09-16 07:20 — the stop! class closed at its root (`6ea39d45a`)

The batch-71 residue (`seon.cluster.wake/unlisten!` refusing a released
connection) was not one more member: every request shape that names a
connection references the ONE key `:seon.db/connection`
(`seon.db.edn:136`), whose predicate was the liveness check. `6ea39d45a`
makes that key structural (`seon.db/connection-object?`, "a Datahike
connection object, live or released"); `seon.cluster.store/connection-object?`
delegates to it; no liveness predicate remains in any declared shape.
Liveness stays the authority's runtime question (`transact!` answers "The
explicit transaction connection is not live."). Totality repair riding
along: `connection-identity` on a released connection answered a throw from
an identity projection; it now answers the typed unknown. Verified live on
pid 74930 (shapes compile); no test JVM. Re-gate as batch 74:
seon.cluster.boot-test seon.cluster.wake-test seon.db-test.

`development-adoption-targets-one-of-two-cohosted-clusters` was ALREADY
declared `:seon.test/long` (`d756a09d4`, boot_test.clj:1007). It hits the
270 s worker exchange bound because a gate naming its namespace runs it
complete, long tests included (§5). Open question for the check-long
owner: a named-namespace gate should either exclude declared-long tests
unless opted in, or the exchange bound must derive from the declaration —
a bound that ignores the declared long-ness is the tuned-constant defect
(§2.3).

Steward's ruling on the long-test bound (recorded in their working edge):
keep §5 ("explicit namespaces run complete"); the worker exchange bound
DERIVES from the declaration — a test declared `:seon.test/long` carries
its own allowance, the runner's per-exchange bound is max(default,
declared). Excluding long tests from a named gate would silently narrow
"run this namespace"; the in-process `check` exclusion exists because
check selects by reach, not by name. Runner slice; queued behind the
write-storm lane's class-1 release of runner.clj.
