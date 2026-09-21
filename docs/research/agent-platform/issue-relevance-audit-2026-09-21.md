---
type: research
status: draft
created: 2026-09-21
tags: [issues, agent-platform, deletion, audit, lanes, triage]
---

# Issue-note relevance audit — the 369 live notes against the agent-platform cut

Read-only. No JVM, no `bin/test`, no `bin/seon`; nothing moved or edited but
this file. Inputs read end to end:
[the plan](../../prds/agent-platform/plan/README.md) and the seven lane specs
([A1](../../prds/agent-platform/plan/lane-a1-projection-carried.md),
[A2](../../prds/agent-platform/plan/lane-a2-datahike-one-answer.md),
[B1](../../prds/agent-platform/plan/lane-b1-one-publication-path.md),
[B2](../../prds/agent-platform/plan/lane-b2-walk-flow-fork.md),
[B3](../../prds/agent-platform/plan/lane-b3-errors-tasks-dials.md),
[B4](../../prds/agent-platform/plan/lane-b4-tests-in-process.md),
[C1](../../prds/agent-platform/plan/lane-c1-wrapper-profiling.md)),
[the issues README](../../seon/issues/README.md), and
[the lifecycle sweep](../../prds/agent-platform/research/issue-notes-lifecycle-sweep-2026-09-21.md).
Every classification was checked by grep against `src/`, `resources/`,
`script/` and `bin/` at HEAD `209a6652a`; the note set is
`grep -l '^status: open' docs/seon/issues/*.md` minus `README.md`,
`AGENTS.md` and `index.md` (those three match only because README quotes the
frontmatter template) = **369**.

Classes: **A** subject deleted or replaced by a spec · **B** subject survives,
the defect is dissolved by a spec's design · **C** subject survives, no spec
addresses it (the keepers) · **D** a design-law or class note whose class can
still occur · **E** undecidable.

**Shared-tree note.** A concurrent session added an untracked
`docs/prds/agent-platform/plan/lane-d1-isolation-merge-writeback.md` while
this audit was running. It was NOT read (it is another session's uncommitted
work); every D1 row below is taken from README §4's D1 table, so any D1 row
should be re-checked against that spec when it lands.

## 1. The consolidated deletion / keep table

Compact; this is the reference §2 classifies against. "replaced-by" means the
mechanism goes and a named construction answers the same question.

| Item | Fate | Lane | Spec section |
|---|---|---|---|
| per-call `supplied-projection` + `request-member` union; `boot-wrapper`; `bootstrap` second population; `contract-digest`; per-wrapper recompile; arity scan | deleted → wrapper closes over its generation | A1 | §5 A1-1 |
| `wrap-interpreted`'s `spec-edn` parameter | replaced-by `(mr/schema registry sym)` | A1 | §5 A1-1b |
| `derive-projection-from-database`, `projection-from-rows` (≈190 lines), the reusable arity | replaced-by `db/carried-projection` read | A1 | §5 A1-2 |
| classpath fallback + 158-line diagnostic (`schema.clj:929-1085`); `schema/edn.clj` `packaged-population-cache`, `forget-packaged-population!`; zero-arity `declaration-projection` | deleted → typed refusal | A1 | §5 A1-3 |
| `assert-config-display!` whole-population compile | replaced-by per-declaration check in `register!` | A1 | §5 A1-4 |
| 12 call-preparation Datalog queries, prepared-symbol queries, `plan` cache, `contract-transaction`, `by-fingerprint`, 4 inline guards | replaced-by plans derived from the compiled contract in the snapshot | A1 | §5 A1-5, §6 C1–C2 |
| `schema_shape` `canonical-form`/`split-schema-form`/`canonical-map-entry` walker | replaced-by normalized `m/ast` (RESET) | A1 | §5 A1-6 |
| `error.clj` `schema-expectation` case table | replaced-by Malli `default-errors` + 13-entry noun overlay (fork) | A1 | §5 A1-7 |
| `admission.clj` file walker, second form walker, `-main` (child JVM) | replaced-by pure admission over carried forms | A1 | §5 A1-8 |
| `schema.clj` `byte-array?`/`sha-256`, `with-compiled-cache`, `:seon.schema.projection/compiled` holder | deleted | A1 | §5 A1-9, §6 C3 |
| read currency arms (a) and (c): `index-evidence-current`, `replay-read`, `stable-value`, `read-result-digest`, `query-index-patterns`, `pull-index-patterns`, `read-evidence-changes`, `:seon.turn/changes`, the `seon.db.edn` replay/digest keys | replaced-by per-attribute revision equality (Datahike `source-context-unchanged?`) | A2 | §5 c1 |
| the EDN string codec: bridge union→string, `edn-encoded-attr-in?`, every decode/encode walker, `read-declarations`/`with-declarations`/`ask-declarations` population plumbing, `wake.clj:422` | replaced-by `:db.type/any` (fork f1) | A2 | §5 c2 |
| multi-arity `diff` family, `external-sink-reach-rules`…`callee-symbol` | deleted (0 production callers); `value-changes`/`apply-diff` kept | A2 | §5 c3 |
| `total-pull-selector` family | replaced-by `+default-limit+ nil` + resource-bound refusal as an elision | A2 | §5 f2, c4 |
| pulled-form inference, `validate-pulled-value`, `validate-pulled-result` | deleted (the write validated the datoms) | A2 | §5 c5 |
| whole-program arity + render-target validator queries | replaced-by a validator over the report's datoms; render symbols as schema-row facts | A2 | §5 c6 |
| `jdk-integers->long`; 56 inline error-shape checks; 8 `defonce`+`delay` holders | deleted | A2 | §5 c7, c8, c13 |
| `registry.clj` head read, `::cannot-retire-main`, `::cluster-connected`, `dry-run!` token exception, filestore directory walk | replaced-by fork exports (`branch-commit-id`, `:datahike.gc/plan-only?`, konserve `:size`) | A2 | §5 c9, c10 |
| `store.clj` `file-lock-generator`, `open-configuration`, `stored-main-keep-history?`, `contains?` liveness | replaced-by `active-connection` and the library's own stored-config refusal | A2 | §5 c12 |
| the manifest family (`build-manifest`, `database-manifest`, `manifest-data`, `artifact-by-path`, `manifest-function-symbols`, `declaration-digests`) | replaced-by rows diffed against the published commit value | B1 | §5 commit 6 |
| caller-less `fn.clj`: `build-artifact`, `rows`, `reconcile-tx`, `plan-file-change`, `output-path-report`, `backfill-contract-facts!` (≈660 src + ≈800 test) | deleted | B1 | §5 commit 3 |
| `discard-obsolete-cache-entries!`, the second clj-kondo `run!`, `forget-namespaces!`, the hook's transit-cache diagnostic | replaced-by clj-kondo fork `from-cache-1` skipping vanished `:disk` files | B1 | §2e, §5 commits 1–2 |
| scratch branch, `force-branch!`, seal row, `publication-input-digest!`, `upsert!`/`populate-upserts!`, `unresolved-report!` readback, `current-src.edn`/`ready.edn`/`program-currentness` | replaced-by ONE transaction on `current-src`; the commit id is the identity | B1 | §5 commit 7 |
| `source-snapshot`, toolchain hashing, `test.cache` gitlink/toolchain/`test-input-digest`, `retrying-source-change`, `require-publication-resources!` | deleted; per-path digest rows are the inventory | B1 | §5 commit 8 |
| `index-tempids`, `compile-index-transaction` string tempid table, the activation seal | replaced-by tempid = the row identity's own print | B1 | §5 commit 10, §2b |
| 9 progress mechanisms (`*source-progress!*`, `*boot-progress!*`, `report-index-progress!`, phase-clock atom, `publication-output!`, `SOURCE_PROGRESS`, stdout re-parse) and 17 of 18 bounds | replaced-by one `:seon.source/progress!` argument and one `:seon.config.source/phase-bounds-ms` fact | B1 | §2d, §5 commit 11 |
| operator process records, claim files, advertisement truth/repair, offline readers, phase logs, `await-advertisement!`, `source-preflight!`, `init-form` codegen, `publish!`, `reap-dead-roots!`, census, generation UUID | replaced-by `ProcessHandle` + one `<root>/prepl.edn`; every command but cold `start`/`reset` is one prepl request | B1 | §2c, §5 commit 12 |
| hook publication (200 lines: result files, pending queue, worker pid file, detached worker, `bin/seon` child, stdout re-parse), the shell digest walk, `.codex/hooks.json` wiring | replaced-by ~30 lines over `prepl-eval!`; hook publication re-enabled | B1 | §2d, §5 commit 13 |
| seven publication entry points incl. `operator/publish!`, `publication-base!`, `bootstrap_drive.clj:450` | replaced-by one `refresh-source!` request | B1 | §2d, §5 commit 14 |
| `:seon.program/analyzed-source-digest` (a FILE digest) | replaced-by `:seon.program/definition-digest`, per declaration (RESET) | B1 | §2g |
| `reload-order`'s `(first remaining)` fallback | replaced-by a typed refusal | B1 | §2f |
| `base-bindings`, `same-program-root?`, `regenerate-agent-context!`'s diff, the two kernel mirror atoms, `installation-covers-program-change?`, `::print-session`, `latest-print-fact` | replaced-by `sci/fork` + re-intern by `:sci/generation` | B2 | §5 commit 4 |
| 7,725 `copy-var*` root copies per acquisition, `install-first-party-namespaces!`, `host-namespace!`, `classpath-locatable?` | replaced-by binding the JVM `Var` object (one 8.6 ms walk) | B2 | §5 commit 3 |
| `program-documentation`…`install-program-doc!` (272 lines), `fork-candidate-ctx`, `accept-candidate!` | replaced-by one pull rendered by the declared pair | B2 | §5 commit 5 |
| `resume-turn`/`close-turn`/`generate-turn` siblings, `call-turn`'s 315-line body, delimiter repair, schema-change machinery, declaration helpers, `declaration-diverged-since-open?`, hand-rolled optimistic concurrency, the 5-helper since-diff | replaced-by ONE turn function with one source selector and one `db/since` | B2 | §5 commit 6 |
| the work launcher (704), capacity observer, the 252-line turn backstop, `await-turn-permit!` | replaced-by one flow proc with `:compute-timeout-ms` reported on `::flow/error` | B2 | §5 commit 7 |
| the relaying mailbox proc, `CountedSlidingBuffer`, `wake-channel`'s counter, `:seon.agent/episode` port | replaced-by the wake channel as the proc's `::flow/in-ports` | B2 | §5 commit 8 |
| `src/seon/render/transcript.clj` whole (2,443 lines), its three hand-assembled views, `render-session-html`, `request-error` key | replaced-by the walk over `seon.eval/of-agent` through the evaluation schema's pair (RESET) | B2 | §5 commit 9 |
| keyframe/delta packages, per-tab registration, drain await, the render proc's retention, the render invocation cache and its consumers, `*walk-context*` | replaced-by whole view per batch on a `(dropping-buffer 1)` tap, brotli-streamed by the SDK | B2 | §5 commit 10 |
| the namespace page's three-tier budget ladder (`ns.clj:416-837`) and its atom | replaced-by one full AI text (one elision value when the profile requires) + one full HTML view | B2 | §5 commit 11 |
| debug-page parallel derivations: `debug-applicable-candidates`, `applicable-renderers-html`, `generic-entity`, `declared-entity-units`, `namespace-candidates` | replaced-by `render/selection-inspection` + `walk/root-selector` | B2 | §5 commit 12 |
| `src/seon/run.clj` (and the `seon.run/walkthrough` literal) | replaced-by `my.turn` (RESET: stored evaluations hold the old bytes) | B2 | §5 commit 13 |
| 15 `defonce`+`delay` and 7 inline `requiring-resolve` in owned files | replaced-by the declared load order | B2 | §2h, §5 commit 14 |
| all 799 `:seon.error/kind` sites; 172 `:seon.error/class true` markers; `error_class_schema_test` | deleted (pure code; `kind` is absent from the live schema) | B3 | §5 commits 3 |
| the `diagnostic-*` ceremony at 275 sites (2,310 key lines), `error/diagnostic`, `refusal.clj:37-74` | replaced-by `(seon.error/error m)`, one additive pure constructor | B3 | §2a, §5 commit 1 |
| the 14 hand-copied error unions (695 lines, six member sets), `refusal_test`'s drift check, `seon.effect.edn`'s 264-line union | replaced-by `:seon.error/base` at the 14 pass-throughs; every domain function still names its union | B3 | §5 commit 2 |
| the stored EDN copy of every recorded error (`data-edn`, `data-size`, `capped?`), the path/segment/omission/key components and 33 predicates/generators, `max-evidence-bytes` | replaced-by declared datoms + shown text + `result/e<id>` (RESET) | B3 | §5 commit 5 |
| the error RENDERER inside `error.clj` (≈300 lines) and the two load-cycle delays | moved to `seon.render.error` (B2 lands the namespace) | B3 | §5 commit 4 |
| `seon.issue`, `issue/*` (incl. `issue/opening.clj`, `issue/detect.clj`), `my.issue`, `seon.plan`, `my.plan`, the plan tree reconciler, `:seon.agent/plan`, the note-ingestion pipeline and `seon.issue/index!` | replaced-by ONE `seon.task` entity + `my.task` + the D2 writer `trigger-call`; done is a scoped query | B3 | §2b, §5 commit 6 |
| `seon.search` whole (Lucene, `IndexHandle`, atoms, lock, `index-step` proc, cluster wiring, `:seon.search/handle`, `seon.search.edn`, `search_test`) | deleted; `tokens`/`similar-identities` move to `seon.schema.admission` | B3 | §2d, §5 commit 10 |
| 33 class-C dials with rows and readers; 5 class-B dials (`turn-completion-backstop-ms`, `operator/event-silence-backstop-ms`, `shell/termination-grace-ms`, `flow/ping-timeout-ms`, `render/coalesce-ms`); `error/recurrence-limit`, `error/escalate-to`, `ai.retry/*` | replaced-by the event each stood for / the seam's own declared bound (RESET) | B3 | §2c, §5 commits 7–8 |
| `seon.env`'s `defrecord`, `defonce` class pin, `environment?`, `environment-state` atom, `replace-environment!`, generators, `print-method`; `effect.clj`'s `*request-context*` | replaced-by a namespaced map carried on the value / the first argument of `request*` | B3 | §2c, §5 commit 8 |
| synchronous effect rows; `receipt-state`, `payload-face`, `receipt-identities` and the retired `receipt`/`face` spellings | deleted; a row is opened only for background and write-back provenance | B3 | §2d, §5 commit 9 |
| `seon.bootstrap`'s generated opening (`situation`, `next-entry`, intent acquisition, `beyond-closure-budget`) — 932 → ~120 | replaced-by system turn 0 evaluating the opening forms (decision 3, rec. (a)) | B3 | §5 commit 11, §6.4 |
| the worker pool, checkouts, exchange, EDN wire protocol, claim protocol, staged results, liveness watchdog + thread dumps, confirmation stage, packing, ambient drift, the 305-line reach-digest index, the `arm.clj` duplicate in the runner | deleted (in-process run) | B4 | §5 commit 2 |
| `bin/test-fast`, `bin/_test-slot`, `src/seon/test/fast.clj`, `bounds.clj`, `cache.clj`, `selection.clj`; `bin/test` → ≤ 100 lines | replaced-by `bin/test-check` + one `--platform` JVM | B4 | §5 commit 1 |
| `run`, `run-owned`, `check`, `check-in-process`, `check-admission`, `host-admission!`, `select-snapshot`; the four `test-input-digest` reads; `published-base-digest`/`tested-branch`/`covered-by` | replaced-by one `(seon.test/run request)` | B4 | §5 commit 3, §6 |
| `seon.test*.edn` process families; `:seon.test/subject` (0 rows) and its lifting; 14 `seon.test.edn` shapes | deleted (RESET); `long ⇒ long-ms` constraint added | B4 | §5 commit 4 |
| the fixture's `create-base`, `clone-directory!`, `replace-directory!`, `populate-published-*`, `Held` protocol, `retrying-base`, file-backed roots | replaced-by the hosting cluster's connection + `sci/fork` (37 ms) | B4 | §5 commit 5 |
| static `gate-sets` as the SELECTOR (saturated: 1,827 of 2,157 for every seed) | replaced-by observed `:seon.test/reach` from the armed wrappers; static stays the first-run seed | B4 | §2 step 3, §8 |
| ten test-machinery files (`test_runner_integration_test`, `test_runner_test`, `test/runner_test`, `bounds_test`, `published_selection_test`, `publication_test`, `test_preparation_test`, `test_cache_test`, `test_expiry_test`, `test_provenance_test`) | deleted (−6,600 in commit 1) | B4 | §7 |
| nothing | C1 deletes nothing; it ADDS `seon.profile` (≤ 240 src) | C1 | §0, §8 |
| `bin/seon init NAME` as a separate fork path; any second "changed entities" derivation | replaced-by a task fork = `branch!` + `sci/fork`, gated merge, write-back by span | D1 | README §4 D1 |
| `bin/codex-agent`, `.codex/hooks.json`, `.codex/agents/*.toml`, `SEON_CODEX_LANE` and its three consumers, `tmp/orchestrator`, `tmp/test-runs`, `tmp/head-wt` | deleted | — | README §8 |
| `docs/prds/steward-platform/`, `docs/prds/context-generation/`, the research directory and brief, 3 `docs/seon/reference/*` files, the `clojurescript` and `codex-lanes` skills | deleted at the clean write | — | README §8 |
| 89 of 109 `reference-code/` submodules (74 uncited + 15 history-only, 21.10 GB) | unvendored; 20 survive (12 on the classpath, 8 read for design) | — | README §8 |
| `AGENTS.md` 1,321 → ≤ 250; `docs/` 237,714 → ≤ 35,000 | rewritten | — | README §2b |
| the 369 notes themselves | survivors promoted to `seon.task` rows by a one-off script; the directory becomes a render of those rows | B3 | §2b, README §8 |

Kept and built, for class-C placement: `seon.db` (A2, 5,000 lines), `seon.fn`
+ `program.cljc` + the indexer (B1), `seon.turn` (B2, 3,300), `seon.render*`
minus transcript (B2), `sci/*` (B2), `seon.error` (B3, 550) + `seon.task`,
`config.clj` (650), `effect.clj` (500), `env.clj` (150), `seon.test` +
`test/runner.clj` (B4, ≤ 1,800), `seon.schema*` + `instrument.clj` +
`call_preparation.clj` (A1), `seon.profile` (C1), and the remainder
(`fs*`, `shell/*`, `web/*`, `schedule.clj`, `maintenance.clj`,
`reconcile.cljc`, `edit*`, `context.clj`, `problems.clj`, `background.clj`,
`eval/*`, `bootstrap_drive.clj` → 8,000 lines, unowned by any lane).

## 2. Classification — one row per open note

Severity: `bl` blocker · `fr` friction · `cl` cleanup. Order is the
directory's own (`docs/seon/issues/*.md`, `status: open`).

| Note | Sev | Class | Spec item / kept-code location | Reason |
|---|---|---|---|---|
| a-blocking-realization-is-not-bounded-by-the-interrupt | fr | C | B2 `sci/admit.clj` (kept, ~unchanged in §9) | The walk realizes a child before it can poll `:interrupt-fn`; no spec touches `frame-advance`. |
| a-call-preparation-facet-requires-unstorable-candidates | bl | B | A2 c2 `:db.type/any` + B3 §2a declared error members | A vector-of-vectors member becomes storable natively; the error's stored shape is re-declared at the reset. |
| a-collection-member-refusal-does-not-name-the-members-index | fr | B | A1-7 Malli `default-errors` + noun overlay; B3 §2a `:seon.error/path` as a value | The hand `schema-expectation` table that loses the index is deleted; Malli's problem path carries it. |
| a-concurrent-classpath-resolution-corrupts-tools-deps-and-aborts-the-gate | fr | A | B4 commit 1 (`bin/test` ≤ 100 lines) | The dependency-cache freshness phase and the gate that ran it are deleted. |
| a-database-reads-error-value-is-read-as-a-row-by-its-caller | bl | D | class/absence-as-health `class-kill`; kept: `seon.db` (A2), every caller | Errors stay values and `seon.db` stays the one read owner, so the class can still occur; B3 converts guards but declares no caller-side rule. |
| a-deleted-source-file-is-not-a-publication-difference | bl | B | B1 §2a step 6 (removed identities → `:db/retractEntity`) + fork change 1 | The diff computes the file's previous identities; a vanished file no longer answers from the kondo cache. |
| a-fast-gate-jvm-dies-on-the-shared-kondo-cache-lock | fr | A | B4 commit 1 (`bin/test-fast` deleted) + B1 commit 2 | Both the fast launcher and the cache sweep that contended for the lock are deleted. |
| a-file-digest-does-not-identify-complete-caller-analysis | fr | B | B1 §2a step 7 (`changed-contracts` → caller files → lint) | The design it asks for is the spec's centrepiece: callers are linted exactly when a contract datom is in the diff. |
| a-failed-turn-wakes-itself-through-its-own-fault-message | fr | B | B3 §2a (recurrence opens a task, not a message); B2 commit 7 fault committer | The self-addressed fault message is replaced by a durable fault plus one task; `escalate-to` is deleted. |
| a-fixture-refusal-loses-its-diagnostic-at-the-test-reporter | fr | C | B4 `test_support.clj` (≤ 450) and `runner.clj` (≤ 1,100) | The fixture still throws its refusal as ex-data and the tally render must print it; no spec states the diagnostic survives. |
| a-hot-adopted-handle-shape-change-wedges-the-live-turn-proc-silently | bl | B | B2 §2b (flow `:compute-timeout-ms` → `::flow/error` → fault committer) | A wedged turn becomes a bounded, reported fault instead of silence; the handle-shape diff machinery goes with commit 4. |
| a-live-cluster-arms-ten-fewer-contracts-than-it-declares | fr | B | A1-1 wrapper reads the registry; T1 (uncontracted = a positive finding at publication) | Arming stops being a whole-image walk with its own parity gap; coverage becomes a fact. |
| a-nonexistent-test-namespace-crashes-the-coordinator-instead-of-a-typed-tally | bl | A | B4 commit 2 (`run-coordinator!`, workers, `-main` deleted) | The coordinator that threw does not exist after the cut. |
| a-missing-required-dial-kills-every-io-prepl-connection | bl | C | B3 `config.clj` (650) + B1 `cluster.clj` prepl bind | Dials shrink 92 → 50 but nothing says a missing required dial degrades instead of killing the prepl. |
| a-generic-attribute-scoped-render-answers-with-the-owning-entity | fr | C | B2 `render.clj` (1,400) candidate selection | Attribute-scoped selection survives the cut untouched; the no-fallback consequence is unaddressed. |
| a-parked-turn-proc-pings-unknown-on-a-healthy-agent | fr | A | B2 commit 7–8 (procs 3 → 2; `flow/ping` replaces the oversight ping) | The proc whose ping answered `unknown` and the oversight ping path are both deleted. |
| a-render-producers-contract-refusal-is-too-large-to-admit-so-the-typed-unknown-loses-its-kind | fr | A | B3 commit 3 (`:seon.error/kind` → 0 sites) | The note's mechanism is `kind`-derived; `kind` is deleted repo-wide. |
| a-platform-test-leaves-its-worker-stripped-of-every-contract | cl | A | B4 commits 1–2 (worker pool deleted) | No worker exists to be stripped; the platform tier is one boot-from-zero JVM. |
| a-namespace-page-serves-twenty-seven-megabytes-in-thirty-four-seconds | bl | B | B2 commit 11 (ladder deleted, one view) + commit 10 (per-tab render) | The O(program) walk behind the 34 s is removed; B2 §1 states nothing above 2 s survives in this area. |
| a-search-contract-predicate-cannot-be-made-durable | fr | A | B3 commit 10 (`seon.search` deleted whole) | `index-step` and its unquoted predicate go with the namespace. |
| a-selected-prompt-no-longer-reconstructs-from-the-full-join | fr | B | B2 §2c (prompt = whole-unit selection over the walk) | The "full join of every acquired evaluation" the check compares against is replaced by the walk's units. |
| a-program-identity-row-pulls-nil | fr | A | B1 §2h `program.cljc` (`deletion-row`/tombstone remnants deleted); AGENTS §3 deletion-is-retraction ruling | Its premise — "program identity rows never retract" — was retired by the 2026-09-16 ruling. |
| a-throwable-fault-keeps-no-inline-evidence-at-any-plausible-bound | fr | A | B3 §5 commit 5 (evidence-cap machinery dissolves; `max-evidence-bytes` deleted) | The inline-evidence bound it measures no longer exists; shown text + `result/e<id>` replace it. |
| a-turn-that-dies-before-replying-still-answers-its-wakes | fr | E | cited owner `src/seon/cluster/work.clj` is deleted at HEAD | Answeredness moved to `turn.clj` with an accepted-reply rule; whether the open-before-reply gap survives needs a live read, which this audit may not take. |
| a-stored-entity-schema-requires-a-cardinality-many-key-that-empty-cannot-satisfy | bl | D | class/p1 + G4 (the looking is an event); kept: `db.clj` final-report validator (A2 c6) | A2 narrows the validator to the report's datoms but the empty-cardinality-many trap is a modelling law, still constructible. |
| a-wildcard-pull-is-still-cut-at-one-thousand-members | fr | B | A2 f2 (`+default-limit+` nil) + c4 (`total-pull-selector` deleted) | The fork removes the silent cut and reports the resource bound as an elision. |
| acquired-dir-data-has-no-read-evidence | fr | A | B2 commit 5 (`program-documentation`…`install-program-doc!` deleted) | `program-dir-var`'s captured quoted documentation is replaced by one pull rendered by the declared pair. |
| activation-closure-records-no-schema-keys | fr | A | B1 commit 10 (activation seal deleted; ruled deleted in goals §5) | `require-activation!`'s always-empty checks die with the closure. |
| about-identity-resolution-logs-an-error-per-non-matching-attribute | fr | A | B2 commit 9 (`render/transcript.clj` deleted) | The transcript render that emits one `:error` per identity attribute is the whole subject. |
| admit-inst-overlap-prefers-collection-shape | fr | C | B2 `sci/admit.clj` (kept) | Admission's `inst?` ordering is untouched by every spec. |
| adopted-default-no-provider-turn-does-not-settle | bl | B | B2 commit 6 (one turn function, one source selector; `close-call` on the shared tail) | The no-provider reply branch is one of the three siblings the cut collapses. |
| adoption-can-leave-a-changed-contract-armed-with-its-previous-shape | fr | B | A1-1 (`current-wrapper?` over the closed-over generation) + B1 commit 9 (arm only reloaded namespaces) | Re-arming is keyed to the changed contract in the transaction report. |
| admitted-test-results-are-invisible-to-legacy-consumers | bl | A | B4 commits 2–3 (`record-tx` keeps only the admitted branch; the old latest-result attributes go at commit 4) | The two-writer split it reports is deleted; there is one recorder. |
| agent-install-gate-cannot-resolve-selected-test-bindings | fr | B | B4 commit 3 (one `run`, resolution by identity from facts) + D1 merge gate | Test selection for a candidate install becomes the one in-process request against the fork. |
| agent-repl-cannot-require-clojure-pprint | fr | C | B2 `sci/eval.clj` base ctx (kept, rebuilt at commit 3) | The `:namespaces` set the base ctx installs is rebuilt but no spec adds `clojure.pprint`. |
| adoption-probe-emits-an-invalid-root-namespace-lookup | fr | E | caller never identified | The note states outright it is unattributed; nothing in the specs can be matched to it. |
| agent-flow-fixture-omits-render-interest | fr | A | B2 commit 10 (render interest and the render proc deleted) + B4 commit 5 (fixture rebuilt) | `:seon.render.web/interest` does not exist after per-tab render. |
| an-agent-turn-proc-dies-on-every-pass-and-oversight-still-reports-it-armed | bl | B | B2 commit 7 (one flow proc; `::flow/error` is the only report) + F4 | Oversight's independent "armed" answer is replaced by flow's own `ping`. |
| an-analysis-time-snapshot-change-never-reaches-the-one-publication-retry | fr | A | B1 commit 8 (`retrying-source-change` and `source-change-phases` deleted with the second observation) | The retry and the snapshot race it guards are both removed. |
| an-aborted-publication-leaves-no-record | bl | B | B1 commit 11 (one progress argument, typed phase refusals naming what was in flight) | Each phase fails with a typed error; a client timeout no longer takes the server work down silently. |
| an-agent-cannot-make-its-own-source-edit-live | bl | B | README decision 8 rec. (a): `my.test/check` adopts what it needs; B1 `refresh-source!` as a declared request (C6) | The agent-facing adoption path is a ruled deliverable of the cut. |
| an-unrelated-fixture-transaction-mints-a-half-agent | fr | A | B4 commit 5 + commit 8 (fixture and the render/web suites rebuilt) | The two fixtures that mint the bystander agent are deleted or rewritten. |
| anonymous-runtime-contracts-have-recurred | fr | D | class/n6; kept: every function's contract (T1), A1's registry | Contracts remain hand-written Malli; `:any`/`:some` can recur, and T1 only makes absence a finding, not anonymity. |
| an-interrupted-fixture-leaks-datahikes-roster-permit-and-wedges-the-jvm | bl | A | B4 commit 5 (`Held` protocol, `retrying-base`, `acquire/release/close-base!` deleted; the fixture is a branch) | The roster permit it leaks belongs to the base-clone protocol the cut removes. |
| an-open-map-keyed-only-by-universal-attributes-shadows-every-entity | fr | D | AGENTS §2.5 open maps + §3 no kind stamps; kept: `seon.schema` | Open maps are law and no spec adds a discriminant, so the shadowing class survives every cut. |
| base-context-membership-still-varies-with-what-happened-to-load | fr | A | B2 commit 3 (`host-namespace!`/`classpath-locatable?` fold into one walk over `:seon.ns` rows) | Membership becomes a function of the database value, not of load order. |
| blob-get-assumes-file-store-callback-shape | fr | C | A2 `blob.clj` (kept, 430 → one `payload-bytes`) | A2 c11 touches the unwrapper, not the absent-key callback assumption. |
| armed-error-validation-throws-on-non-keyword-sorted-maps | bl | B | A1-1 + B3 §6.1 (wrapper validates the DECLARED union only, ≤ 5 validators) | The all-facets scan through Malli map validation that reaches `Keyword/compareTo` is deleted. |
| background-binary-settlement-does-not-publish-required-event | fr | C | B3 `effect.clj` (500) — background rows are the ONE kind still written | B3 keeps background effect rows explicitly; the missing terminal event is not addressed. |
| bootstrap-next-entry-has-no-production-caller | cl | A | B3 commit 11 (`bootstrap.clj` → `seed-tx` + `supervision-tx`) | `next-entry` is named in the deleted span; README decision 3 rec. (a). |
| bootstrap-o4-stops-before-causal-delegation-settles | fr | E | `eval/drive.clj` + `bootstrap_drive.clj` sit in the unowned remainder (README §2b, ~10,900 → 8,000) | No spec owns the evaluation drivers; whether `my.run/wait` semantics survive is not stated anywhere. |
| blob-roots-are-derived-from-digest-shape-not-from-a-declared-fact | fr | C | A2 `cluster/registry.clj` + B1 `operator.clj` | Both walkers survive their lanes' cuts; the shape-instead-of-fact derivation is untouched. |
| blocked-plan-values-refuse-pull-during-ai-projection | fr | A | B3 commit 6 (`seon.plan`, `my.plan`, `:seon.agent/plan` deleted) | "Blocked plan items" are not an entity after the task merge. |
| browser-ui-observation-has-no-accessible-window | fr | E | tooling note, no `src/` subject | A session-environment observation about CUA; no spec, no kept code. |
| cache-reuse-regression-exceeds-live-test-bound | fr | A | B4 commit 1 (`test_runner_test.clj` and the published-base cache deleted) | The regression and the cache it exercises both go. |
| bound-transaction-input-selects-an-older-turn | bl | C | A2 `db.clj` query seam + the vendored Datahike query engine | A datom-pattern input failing to constrain a query is a fork query-engine question no lane owns. |
| bound-pull-selector-evidence-retains-all-attributes | fr | B | A2 §6.1 (the `:all`-plan census and its top source) + c1 | An input-supplied pull selector planning `:all` is exactly the 95 % case A2 probes and fixes at the seam. |
| canonical-fixture-population-missing-carried-projection | fr | A | B4 commit 5 (fixture = the hosting cluster's connection + ctx) + A1-2 | The fixture that rebuilt a population is deleted; the projection is carried. |
| candidate-context-shares-parent-program-metadata | fr | A | B2 commit 5 (`fork-candidate-ctx`/`accept-candidate!` deleted; the writer decides) | The candidate ctx fork it audits is removed. |
| call-preparation-refusal-dumps-the-acquired-projection | fr | B | A1-5 (plans from the compiled contract) + B3 §2a (offending value = shown text + `result/e<id>`) | The refusal stops carrying a whole projection as its offending value. |
| call-graph-fidelity-selection-awaits-adopted-proof | bl | B | B1 §2a steps 5–6 (rows and edges from one analysis) + B4 §2 step 3 (observed reach) | Its baseline — missing body calls, zero-reach handlers, saturated selection — is the measurement the cut replaces. |
| chart-plan-examples-retain-old-juniper-step-ids | cl | A | README §8: `docs/prds/context-generation/` deleted at the clean write | The chart it corrects is deleted documentation. |
| captured-prompt-history-is-disabled | fr | A | B2 commit 9 (the walk is the history; the capture views go with `render/transcript.clj`) | `:seon.context.capture/prompt` as a second prompt record is superseded by stored evaluations. |
| capability-component-arguments-stay-in-request-edn | cl | B | B3 commit 9 (`seon.effect.edn` narrowed; sync rows dropped) + A2 c2 (`:db.type/any`) | Map-valued request keys become storable values instead of EDN residue. |
| canonical-fixture-retains-old-function-contracts-after-adoption | fr | A | B4 commit 5 (no base clone; the fixture forks the live cluster's ctx) + A1-2 | A fixture cannot hold an old projection once it branches the hosting cluster. |
| class-dependency-representations-leak-past-boundaries | fr | D | class/n13 `class-kill`; kept: every seam that takes a callback or collection | Concrete-representation leakage is a design class no spec closes. |
| class-classification-is-inferred-from-hand-lists | fr | D | class/n7 `class-kill`; AGENTS §2.2 (no hand lists) | The cut deletes several named lists (write-seam, reload sets, rosters) but the class is a standing law. |
| class-anonymous-contracts-cannot-survive-publication | fr | D | class/n6 `class-kill`; kept: A1's registry + B1's contract rows | Anonymous contracts remain constructible; T1 only requires a contract to exist. |
| class-accepted-work-can-end-without-terminal-evidence | bl | D | class/n10 `class-kill`; AGENTS §2.3 both halves | Bounded-execution law; B2/B3/B4 remove several instances but the class is the law itself. |
| class-domain-order-falls-through-to-strings-and-hashes | fr | D | class/n8 `class-kill` | Ordering by identifier string is still constructible everywhere. |
| class-documentation-restates-executable-contracts | cl | D | class/n12 `class-kill`; README §8 shrinks `AGENTS.md` and `docs/` | Docstrings remain program facts rendered into agent context; the class survives. |
| class-local-updates-recompute-global-projections | fr | D | class/n9 `class-kill` — the cut's own thesis | Every lane attacks instances of exactly this class; keep it as the law until the measured rows land. |
| class-destructive-reachability-changes-are-not-atomic | bl | D | class/n14 `class-kill`; kept: `blob.clj`, `cluster/registry.clj` (A2), `operator.clj` (B1) | GC/replacement atomicity is untouched by A2 c10's `plan-only?` change. |
| class-outward-values-bypass-total-render-contract | bl | D | class/n1 `class-kill`; AGENTS §2.4 | B2 consolidates render paths; the total-render law and its failure class remain. |
| class-mutable-resources-lack-explicit-root-and-lifetime | bl | D | class/n4 `class-kill`; kept: `store.clj`, `operator.clj`, `fs.clj` | B1 removes claims and roots machinery but the lifetime class is law. |
| class-readerless-duplicate-mechanisms-survive-cuts | cl | D | class/n11 `class-kill` | The cut is the largest readerless-mechanism sweep yet; the class-kill note is how the next one is caught. |
| class-loaded-artifacts-lack-source-identity | bl | D | class/n3 `class-kill`; B1 §2g per-definition digest is a partial answer | The digest closes the publication half; a JVM serving stale loaded code is still constructible. |
| cluster-ctx-delegating-arities-refused-under-instrumentation | fr | A | B2 commit 3–4 (`cluster-ctx` folds into the one base-ctx construction) | The delegating arities are part of the acquisition machinery being rebuilt. |
| cluster-boot-instruments-in-flight-working-tree-vars | fr | C | B1 `cluster.clj` boot + A1 `instrument.clj` | Boot still arms loaded Vars from the working tree; no spec ties arming to the published commit. |
| cluster-named-store-collides-with-the-store-directory | fr | C | B1 `cluster.clj` `cluster-paths` + `operator/state.clj` | Path derivation survives the operator cut; no reserved-name rule is added. |
| class-proofs-pass-without-exercising-their-premise | fr | D | class/n2 `class-kill`; AGENTS §5 "assert current ruled behavior" | The premise-free-proof class is the reason the cut demands measured rows; keep. |
| cohosted-clusters-share-one-unbounded-agent-heap | bl | C | B2 `sci/eval.clj` time-limit + `operator/runtime.clj` executors | Per-cluster heap admission is named nowhere; one JVM makes it more, not less, relevant. |
| cohost-start-races-the-reachability-sweep | fr | C | A2 `cluster/registry.clj` GC + B1 `operator.clj` | A2 c10 changes the dry run, not the start/sweep race. |
| cold-publication-returns-a-blank-commit-without-publishing | fr | B | B1 commit 7 (one transaction; the commit id is the identity) + commit 11 (typed phase refusals) | A blank commit id cannot be returned when the transaction either commits or refuses by name. |
| compiled-guard-error-functions-refuse-error-observation | fr | B | A1-1 (one compiled contract per Var) + B3 §6.1 declared-union validation | The double-compiled guard that swallows the refusal is deleted. |
| collection-attribute-query-bindings-leak-edn-storage-values | fr | A | A2 c2 (the codec and every decode walker deleted) | There is no storage string to leak once values are `:db.type/any`. |
| cluster-handle-retains-old-environment-projection-after-adoption | fr | A | A1-2 (projection read from the value) + B3 §2c (env is a map on the value) | A handle cannot retain a projection it no longer holds. |
| concurrent-eval-test-calibrates-interpreted-work-to-wall-time | fr | C | B2 `test/seon/sci/eval_test.clj` (kept; B2 §7 rewrites only the base-bindings cases) | The wall-time oracle is not in any spec's rewrite list. |
| concurrent-publications-serialize-past-the-hook-bound | fr | A | B1 §2d (one request, the JVM monitor coalesces) + README §8 (lanes and `bin/codex-agent` deleted) | Multi-lane concurrent publication is the condition the cut removes; the 106 s path is measured to ≤ 700 ms. |
| confirmation-parallel-failure-blocks-reading-worker-protocol | fr | A | B4 commit 2 (confirmation stage and wire protocol deleted) | Both halves of the wedge are deleted mechanisms. |
| complete-program-publication-is-refused-on-a-cardinality-many-set | bl | B | A2 c6 (validator reads the report's datoms) + G4 | Whole-entity revalidation of untouched cardinality-many sets stops happening on every program write. |
| context-cookbook-trial-provider-has-no-credits | fr | E | provider account state, no code subject | An account/credit observation; nothing in `src/` and no spec item. |
| context-wave-leaves-three-small-honesty-defects | cl | E | cites `cluster.clj`, `fn.clj`, `render/ns.clj` — all three heavily rewritten | Three unnamed small defects; without reading each in its rewritten owner the disposition cannot be decided here. |
| core-program-stubs-prevent-required-file-provenance | bl | B | B1 §2e (prelude restricted to `:agent` rows) + §2a step 5 (rows from analysis) | `desired-rows`' manufactured external identities stamped `:core` go with the manifest/rows rewrite. |
| context-capture-does-not-carry-error-recording-custody | fr | A | B2 commit 9 + B3 §2a (custody is the connection; provenance in tx-meta) | The capture entity and its custody decision belong to the deleted capture path. |
| data-page-takes-five-and-a-half-seconds-for-three-kilobytes | fr | B | A1-2 (seam is a read) + B2 commit 10 (per-tab render) | The single database inspection behind `/data` pays a projection rebuild today; it becomes a metadata read. |
| database-value-shape-name-duplicates-the-db-key | cl | C | A2 `seon.db.edn` + `db.clj` (kept) | A2 prunes replay/digest keys only; the `:seon.db/database-value` duplicate name survives. |
| datahike-fork-is-28-commits-behind-upstream | fr | C | A2 `reference-code/datahike` (kept — one of the 20 surviving submodules) | A2 adds six fork commits and never rebases; the 28 missing correctness fixes stay missing. |
| cua-browser-surface-is-unavailable-in-the-implementation-session | fr | E | tooling/session note, no code subject | Same class as the CUA window note; no spec, no kept code. |
| debug-feed-backstop-fault-wakes-the-viewed-agent | fr | A | B2 commit 10 (feed deltas, packages, the drain await deleted) + B3 §2c (`render/coalesce-ms` deleted) | `:seon.render.web/feed-delta` does not exist after whole-view-per-batch. |
| db-test-still-expects-a-unique-agent-namespace | fr | B | B3 §2b (`:seon.agent/namespace` is not unique; the task family replaces issue workers) | The uniqueness the test asserts contradicts the ruled many-agents-per-namespace direction. |
| debug-feed-subject-change-regression-times-out | fr | A | B2 commit 10 + §7 (`web_feed_test` becomes the two-tabs-repaint regression) | The regression is named for rewriting; the feed mechanism is deleted. |
| db-diff-render-bypasses-print-fit-and-has-no-html | fr | B | B2 §2.4 one clipping spot + the declared render pair; A2 c3 keeps `value-changes` only | The diff value renders through the value renderer like any other value. |
| debug-reread-summary-duplicates-shown-value-and-diff-owners | cl | A | B2 commit 12 (debug-page parallel derivations deleted) | `readable-shown` and `changed-paths` are exactly the parallel derivations the commit removes. |
| default-eligibility-matched-differently-named-schema-aliases | fr | B | A1-5 / §6 C2 (supplier matching by compiled-form equality) | Matching stops going through fingerprint joins, so equivalent-but-differently-named forms are decided by one rule. |
| debug-html-render-carries-no-agent-scoped-environment | fr | B | B2 commit 4 (`fork-for-turn`) + B3 §2c (env as a map on the value, supplied by call preparation) | The HTML path receives the same scoped environment value as the AI path. |
| debug-feed-waits-fail-only-in-pooled-worker | fr | A | B4 commit 1 (pooled workers deleted) + B2 commit 10 | Both the pool and the feed events are deleted. |
| dependency-resolution-can-race-maven-model-validation | fr | A | B4 commit 1 (no gate JVM resolves a classpath) + B1 commit 12 | Classpath acquisition per gate disappears with the launcher. |
| development-adoption-can-mix-host-and-sci-generations | bl | B | B2 commit 3 (`:core` = the JVM Var) + B1 commit 9 (reload exactly the report's namespaces) | A JVM Var cannot diverge from its SCI binding once SCI calls through `Var.invoke`. |
| default-component-probe-times-out-after-adoption | fr | A | B4 commits 1–3 (the daemon-future loader path and `check` deleted) | The probe used `seon.test`'s own loader and the deferred check command, both deleted. |
| development-adoption-cannot-load-test-support | fr | B | B4 §2 (tests run in the cluster's JVM; `seon.test.cache` classpath machinery deleted) + B1 commit 12 | Publication stops needing a test classpath at all. |
| declaration-settlement-consumes-invalid-read-as-ref | fr | B | B2 commit 6 (declaration helpers and `row-tx` prelude deleted; the writer owns them) | Settlement's own declaration handling is replaced by `program/exact-replacement-tx` and the final-report validator. |
| development-adoption-drops-the-web-server | fr | B | B1 commit 13 (hook publication ≈ 30 lines, one request) + B2 commit 10 | Adoption stops rebuilding the web service; the page is a per-tab render over the live value. |
| default-web-request-times-out-during-partial-adoption | fr | B | B1 §2a steps 11–13 (adoption proportional to the report) + B2 commit 10 | Partial adoption no longer holds the page thread. |
| documentation-arglists-with-auto-keywords-are-not-edn | fr | A | B2 commit 5 (`function-doc-map`/`directory-value` assembly deleted) | The documentation values it reads are replaced by one pull through the declared pair. |
| default-pull-unknown-attribute-omits-candidates | fr | C | A2 `db.clj` `pull` (kept) + B3 `seon.error/error` | A2 narrows validation but no spec makes an unknown read attribute teach installed candidates. |
| documentation-fixture-omits-interpreted-contract-configuration | fr | A | B4 commit 5 (fixture rebuilt) + B3 (message send refusal at the writer) | The fixture and its panic-config assumption are rewritten with the fixture cut. |
| development-adoption-window-loses-newer-issue-and-test-facts | fr | A | B3 commit 6 (issue indexing deleted) + B4 commit 3 (one recorder) | The two writers that raced — the note indexer and the test recorder — are both deleted. |
| dynamic-in-ns-cannot-persist-definition-namespace | fr | C | B2 `sci/eval.clj` + B1's indexer (`:seon.ns` rows) | Nested `in-ns` and its missing namespace row are untouched by every spec. |
| development-adoption-cannot-compile-docstring-var-contracts | bl | B | A1-1/A1-2 (the wrapper reads the compiled contract from the registry; no recompile per Var) | Contracts referencing schema Vars stop being recompiled at adoption. |
| edit-hook-kondo-false-positives-on-seon-db-dynamic-vars | fr | B | B1 commit 1 (clj-kondo fork: stale `:disk` entries skipped) + commit 2 (cache sweep deleted) | The stale-cache cause named in the note is the fork change. |
| duplicate-identity-refusal-evidence-is-unordered | cl | C | B1 `reconcile.cljc` (kept; unowned remainder) | Reconciliation's hash-map-iteration choice is in no lane's deletion list. |
| effect-settlement-cannot-classify-polymorphic-handler-results | fr | B | B3 commit 3 (`kind` deleted) + commit 2 (`:seon.error/base` at pass-throughs) | `settle-value!`'s `kind` test is replaced by the declared base predicate at the one polymorphic boundary. |
| development-adoption-retains-old-web-service-inputs | fr | B | B1 §2a steps 11–13 + B2 commit 10 | The retained service inputs belong to the render proc and registration the cut deletes. |
| effective-settings-read-refreshes-after-system-turn | fr | B | B2 commit 6 (since-diff = one `db/since` over read evidence) + A2 c1 (revision currency) | A read whose own turn wrote its wake datom is decided by attribute revision, not replay. |
| effect-run-and-form-ordinal-duplicate-the-evaluation-ref | cl | A | B3 commit 9 (`seon.effect.edn` narrowed) + the `:seon.eval` identity ruling | The two duplicate parts are dropped when the receipt entity narrows to background + provenance. |
| error-observation-component-walk-passes-an-invalid-schema-to-malli | bl | A | B3 commit 5 (`error.clj:2314-2658` and the component family deleted) | `stored-observation`'s component walk is inside the deleted span. |
| drive-one-starts-without-required-plan-facts | fr | A | B3 commit 6 (`my.plan` deleted) + README §8 (the drive's PRD deleted) | Its subject is a `my.plan` item set on a preserved drive agent. |
| error-result-retirement-crosses-held-readers | bl | B | B3 §2a (shown text + `result/e<id>`) + B2 §2a (`::result-objects` is the one holder) | The one-result-identity ruling it reports is implemented by the cut. |
| error-observation-aliases-inherit-native-uniqueness | fr | C | A2 `schema/datahike.clj` bridge (kept, 555 → smaller) | Alias-following before reading storage properties is not in A2's deletion list. |
| eval-samples-cost-42mb-of-store-each | fr | E | `src/seon/eval/*` sits in the unowned remainder; the note already retracts its own per-sample number | Inspect-eval store growth has no owning spec and the note's central figure is withdrawn. |
| effect-feedback-orders-receipts-by-id | fr | C | B3 `effect.clj` (500, kept) — `cluster/run.clj` is already deleted | Effect ordering by id string survives; B3 removes the `receipt` spelling, not the ordering key. |
| evaluation-fixtures-still-read-retired-lifecycle-shapes | fr | A | B2 §7 (the `gen/loop_test` family and the turn suites collapse to three classes) | The fixtures it names are in the deleted test corpus. |
| evaluation-reader-and-writer-use-different-entity-schemas | cl | B | A2 §2 (f) (pulled-form inference deleted; a read of written datoms is valid by construction) + `seon.eval` one-entity ruling | The three-grammar split it reports is exactly what A2 removes. |
| expected-refusal-logs-raw-datom-error-twice | fr | C | A2 `reference-code/datahike` writer logging + `db.clj` | The sweep already ruled this out of class/n1; A2 touches the validator, not the fork's logging seam. |
| entity-pair-debug-parity-remains-unverified-after-adoption | fr | A | B2 commit 12 (debug page cut to the four §16 sections) | The parallel debug derivation it observes is deleted. |
| fast-overlay-admission-prints-the-complete-program-manifest | fr | A | B4 commit 1 (`bin/test-fast` deleted) + B1 commit 6 (the manifest dissolved) | Both the printer and the printed value are deleted. |
| feed-writer-casts-an-absent-package-number | fr | A | B2 commit 10 (packages deleted) | There is no package number after whole-view-per-batch. |
| file-store-commits-pay-five-times-the-fsyncs-they-need | fr | C | A2 `cluster/store.clj` (kept) + the Datahike fork | The note already corrects itself to the current configuration; no spec revisits fsync counts. |
| eval-drives-duplicate-a-four-minute-run-clock | fr | C | unowned remainder: `eval/drive.clj`, `bootstrap_drive.clj` | Two `(or supplied magic-number)` clocks; B3 cuts dials but not these two drivers. |
| fixture-branch-keywords-are-not-readable-edn | fr | A | B4 commit 5 (fixture rebuilt: `d/branch!` off the hosting cluster) | The branch-naming helper that minted unreadable keywords goes with the base protocol. |
| filesystem-stat-refusal-fails-its-return-contract | fr | C | unowned remainder `shell/jvm.clj`, `fs*` | A declared-union gap in the shell capability; B3 owns `effect.clj`, not the handlers. |
| final-report-validation-runs-unbounded-on-the-writer-thread | bl | A | A2 c6 (validator reads only the report's datoms) | Its whole subject is the O(program) validator A2 narrows. |
| fault-injection-drops-an-existing-callable-arity | fr | C | B1 `program.cljc` (kept, 1,000) + the fixture | Arity-preserving fault injection is named in AGENTS §5 but in no spec's work. |
| flow-skill-forbids-current-turn-acquisition | fr | B | B2 commit 4 (`fork-for-turn` = `sci/fork` + re-intern) + README §8 (the skills are rewritten) | The code converges on the skill's rule; the doc/code disagreement closes at B2's landing. |
| fixtures-that-ignore-a-refused-transaction-read-absence-as-behaviour | fr | D | class/absence-as-health; kept: `transacted!` (B4 commit 5 keeps it) | The five instances are swept but a fixture can still drop a refusal; the class outlives the cut. |
| flow-error-proc-has-no-declared-step-function-ref | fr | B | B2 §2b (the `::flow/error` map carries `::flow/pid` and the proc's args; `var-process` is the declared step) | The fault committer receives flow's own error map instead of inferring a function from a keyword. |
| filesystem-dials-absent-throws-a-bare-nullpointerexception | fr | C | B3 `config.clj` (650) + the `fs` capability dials (kept) | Dial absence still throws; B3 deletes 33 dials but declares no absent-dial refusal. |
| fresh-cljc-files-are-jvm-only | cl | C | 7 `.cljc` files in `src/` (kept; owner preference "MAXIMIZE portable .cljc") | The `.cljc`-without-CLJS question survives; the note's second half about the datastar skill closes with README §8's skill rewrite. |
| foreign-write-fence-reads-only-the-dynamic-var | fr | C | A2 `db.clj:477-481` (kept — C1 §6 relies on it) | C1 binds custody in more places, which makes the fence more load-bearing, not fixed. |
| full-publication-tests-exceed-liveness-while-compiling-the-commit-projection | fr | A | A1-2 (no commit-projection compile) + B4 commit 1 (the liveness backstop deleted) | Both the 3,996 ms compile and the silence watchdog are deleted. |
| fixture-schema-readmission-after-adoption-refuses-environment | fr | A | B4 commit 5 + B3 §2c (env as a map) | The scratch-fixture re-run path is replaced by branch + ctx fork. |
| gate-selection-refusal-contracts-only-distinguish-markers | fr | B | B3 §2a (the schema is the meaning; indistinguishable facets are refused) + A1 declared-union validation | `gate-sets`' two marker-only facets are exactly the union-drift shape B3 removes. |
| generated-issue-opening-promises-tests-it-does-not-have | fr | A | B3 commit 6 (`seon.issue/render-ai`, `issue/opening.clj` deleted) | The generated opening block is deleted with the issue family. |
| generated-issues-carry-no-tests-so-start-refuses-them | fr | A | B3 §2b (`trigger-call` creates task + agent; T1 governs `start!`) | Generation-without-tests is replaced by the D2 writer. |
| flow-work-launcher-graph-omits-its-root-io-executor | fr | A | B2 commit 7 (the work launcher graph deleted) | The graph with the missing `:io-exec` does not exist after the cut. |
| generation-dependency-analysis-ignores-keywords | fr | A | B3 commit 11 (`bootstrap.clj`'s generated opening deleted) | Its subject is the retired opening generator's frontier. |
| guarded-public-walk-exceeds-allocation-bound | fr | B | A1-1 (per-call scan deleted) + B2 commit 3 (no 7,725 root copies) | The allocation the assertion counts is the acquisition and wrapper work both lanes delete. |
| historical-function-values-do-not-identify-an-error-entity | bl | B | B3 §2a (one root per D13 signature, occurrences as components, declared members only) | The four-rows-for-one-error shape is the stored model B3 replaces. |
| gate-set-rederives-the-declared-reference-population-on-every-call | fr | A | B4 §2 step 3 (observed reach replaces `gate-set` as the selector; static is the first-run seed only) | The per-call whole-database derivation stops being on the selection path. |
| guarded-schema-declarations-still-exceed-the-allocation-regression-bound | fr | B | A1-2 + B2 commit 3 | Same allocation source as the public-walk note; both O(program) steps are deleted. |
| heterogeneous-identity-query-refuses-during-reach-acquisition | fr | B | A2 c2 (`:db.type/any`) + c6 (validator from the report) | A heterogeneous identity set stops being a codec/validation problem. |
| hyperlith-pin-behind-lockstep-rework | fr | A | README §8 + B2 §3 — hyperlith is read for design; B2 takes the render-handler idiom and vendors nothing | The pin question dissolves: the SDK's own brotli profile is used and hyperlith stays a read-only reference. |
| give-offline-roster-discovery-a-current-read-only-helper | fr | A | B1 §2c (offline readers deleted; "no JVM running" is the answer) | The offline-roster path it asks to improve is deleted. |
| historical-call-edge-analyses-need-rederivation | fr | A | B1 §2b (a reset re-analyses from zero) + AGENTS "database data is disposable" | Backfill is never done; the reset republishes every edge. |
| history-prompt-fixture-retracts-required-turn-attributes | fr | A | B2 §7 (`render/web_test` cut to ~1/3) + B4 commit 5 | The fixture helper and its whole-entity refusal go with the test corpus cut. |
| incremental-population-owners-accrete-but-never-retract | fr | B | B1 §2a step 6 (removed identities → `:db/retractEntity` from the diff) | Retraction becomes part of the one diff for every owner. |
| help-trial-copies-prompt-and-invents-results | fr | C | B2 `cluster/prompt.clj`, `repl.clj` (kept) | A provider-behaviour finding about the prompt's shape; no spec addresses reply fidelity. |
| hook-quiet-window-assumptions-survive-immediate-drain | cl | A | B1 commit 13 (hook publication ≈ 30 lines; coalescing is the JVM monitor's) + README §2b (`AGENTS.md` ≤ 250) | The documented quiet window and the hook that contradicted it are both rewritten. |
| in-process-test-timeout-precedes-fixture-release | fr | B | B4 commit 5 (no hold protocol) + commit 3 (`:seon.test/remaining-ms` inside the one `run`) | The fixture is a branch pointer, so a bound firing cannot strand a held base. |
| instant-vector-admission-and-instrumentation-disagree | fr | B | A1-6 (fingerprint off `m/ast`) + A1-8 (admission pure over carried forms) | Hook admission and contract compilation read the same compiled forms. |
| history-policy-refusal-test-is-load-flaky | fr | A | B4 commit 1 (pooled workers deleted; serial in-process execution) | Load flakiness from concurrent worker JVMs is removed by construction. |
| inbox-block-omits-message-content | fr | A | B2 commit 9 (`inbox-form`/`message-form` move to `cluster/message.clj` as declared pairs) | The inbox block is re-authored as part of the transcript deletion. |
| isolated-operator-init-requires-a-source-checkout | fr | A | B1 §2c (every command but cold `start`/`reset` is one prepl request) | The isolated-root init path it describes is rewritten whole. |
| inline-form-in-reply-prose-becomes-an-evaluation | fr | C | B2 `cluster/reply.clj` (kept) | B2 deletes delimiter repair, not the reader's prose/form boundary rule. |
| instrumented-vars-hide-their-constant-pool-from-runtime-dependency-inspection | fr | C | A1 `instrument.clj` (750) + C1's sample on the same wrapper | The wrapper stays a closure over the original; C1 adds to it. Dependency inspection still cannot see through it. |
| identity-upserts-still-require-complete-entity-maps | fr | D | class/p1-adjacent modelling law; kept: `db.clj` final-report validator | A2 narrows WHICH entities are validated, not the completeness rule; the trap survives. |
| juniper-submission-repeats-existing-turn-refusals | fr | A | B2 commit 6 (one turn function) + B4 commit 5 (`context_blocks_fixture` in the deleted corpus) | Its subject is the fixture and the sibling turn path. |
| interrupted-blob-staging-leaves-no-observable-artifact | fr | C | A2 `blob.clj` (kept) + `blob_test.clj` | A2 c11 folds one unwrapper; the staging observability gap is untouched. |
| issue-worker-creation-in-process-test-exceeds-bound | fr | A | B3 commit 6 (`issue_test.clj` deleted) + B4 commit 3 | Both the test and the worker-creation transaction are deleted. |
| llm-provider-skill-names-retired-default-model | cl | A | README §8 (skills and docs rewritten at the clean write) | A documentation drift in a file the cut rewrites. |
| incremental-publication-cannot-select-the-live-operator | fr | A | B1 §2c (`prepl.edn` is the only file; advertisement truth deleted) | The operator-discovery disagreement it reports is the deleted mechanism. |
| live-resources-outrun-the-loaded-program-identity-list | bl | B | B1 §2a step 5 + A1-3 (`packaged-forms` keeps only the stamped resource read; no process-global cache) | The resource-vs-loaded split is removed; the identity list derives from the same forms the publication uses. |
| kondo-does-not-resolve-datalog-parser-generated-variable-constructor | fr | C | A2 `db.clj:579` + the kondo config (kept) | B1's fork change is about vanished files, not generated constructors. |
| mcp-parent-watchdog-can-follow-a-reused-pid | fr | B | B1 §2c (process identity is `(pid, start-instant)` from `ProcessHandle`) | The reused-pid class is closed by the identity rule the operator cut adopts. |
| instrumentation-record-mode-has-no-acquired-fault-recorder | bl | B | A1-1 (the wrapper closes over its world) + B3 §2a (`:seon.config/on-core-error` read at the fault committer) | Recorder acquisition becomes part of the armed wrapper's carried generation. |
| mcp-jvm-small-result-projection-fails-during-live-adoption | fr | B | B2 commit 10 + B3 §2a (shown text is the projection) | The MCP result projection reads the same shown text every evaluation stores. |
| mcp-exception-projection-is-opaque-after-the-kind-removal | bl | A | B3 commit 3 (`kind` deleted everywhere, including the MCP projection) | Its subject is the half-removed `kind`; the cut finishes the removal. |
| message-completion-replies-from-the-wrong-agent-and-duplicates-the-trigger | bl | C | B2 `cluster/message.clj` (kept) + `turn.clj` | Message routing and the reply's `from` are untouched by every spec's deletion list. |
| issue-indexing-at-publication-costs-13-seconds | fr | A | B3 commit 6 (the note pipeline and `seon.issue/index!` deleted) + B1 §2b | Note indexing leaves the publication path entirely. |
| mcp-session-loss-claims-unobserved-restart | fr | B | B1 §2c (`(pid, start-instant)`; the prepl answers liveness) | A lost session and a replaced process become distinguishable facts. |
| mcp-sci-error-projection-passes-a-nil-database | fr | B | A1-2 (the seam returns the carried value or refuses) | A nil database is a typed refusal naming the caller instead of a silent nil. |
| namespace-binding-targets-are-symbols-not-refs | fr | D | AGENTS §3 G2 (a name observed is a VALUE, a statement is a ref) | The ruling now favours symbols for observed names; the note asks for refs. It survives only as a modelling question for alias/refer rows, which B1's indexer still writes. |
| keep-history-is-on-by-default-without-a-decision | fr | C | A2 `cluster/store.clj` `datahike-configuration` (kept) | A2 deletes `stored-main-keep-history?` but never states the default explicitly. |
| my-program-native-evaluation-adds-a-second-sci-owner | fr | B | B2 commit 3–5 (one base ctx, one acquisition owner) | A second SCI evaluation owner cannot survive the single-base construction. |
| my-fs-write-docstring-hides-its-own-request-shape | fr | C | B2 `my/*` (kept, ~10,800) + `my.fs.edn` | Docstring fidelity for the capability surface is in no spec's work. |
| namespace-require-summary-renders-nil-identities | fr | A | B2 commit 11 (`ns.clj:416-837` deleted; one full view) | The summary that printed `[nil :as nil]` is in the deleted ladder. |
| map-unions-have-no-explicit-discriminants | fr | D | AGENTS §3 no kind stamps + open maps; kept: `seon.schema` | Six producer-key-discriminated unions; the law forbids the stamp the note wants, so the tension survives the cut. |
| namespace-page-repeats-renderer-unavailable | fr | B | B2 commit 11 + AGENTS §2.4 (an unavailable observation is the typed unknown) | The generic failure string is replaced by one view or one typed elision value. |
| observable-graph-transitions-are-polled-in-tests | fr | D | class/p2; kept: every test that waits | B2 makes transitions flow events, but polling remains constructible in the surviving corpus. |
| namespace-page-first-byte-exceeds-ten-seconds | fr | B | B2 commit 10–11 (per-tab render, ladder deleted) + A1-2 | The O(program) render is deleted; B2 §1 admits nothing above 2 s here. |
| mcp-runtime-status-lists-no-clusters-for-an-explicit-root | fr | A | B1 §2c (advertisement truth/repair deleted; one `prepl.edn` per root) | Discovery and evaluation stop being two mechanisms that can disagree. |
| nested-test-snapshot-overwrites-its-fresh-run-claim | fr | A | B4 commit 1 (snapshots, run roots and the concurrent launcher deleted) | Its subject is the launcher fixture's parent/child run roots. |
| moving-a-failing-assertion-conflicts-with-its-immutable-report | fr | A | B4 commit 2 (`record-tx` keeps only the admitted branch; staged results deleted) | `:seon.test/report-conflict` belongs to the immutable staged-report protocol. |
| negative-import-masks-escape-static-admission | fr | C | B2 `sci/eval.clj` + B1 `fn/analyzer.clj` | `ns-unmap`'s nil-mask persisting in SCI is not in any deletion list. |
| opening-generator-pushes-undemanded-candidates | fr | A | B3 commit 11 / README decision 3 (a) | The live-pull opening generator is the retired direction. |
| opening-does-not-explain-per-line-comment-markers | fr | B | B3 commit 11 + the system turn 0 opening | The opening's teaching sentence moves with the opening; `cluster/instruction.clj` keeps only what the new opening needs. |
| namespace-layout-confines-most-content-to-scroll-boxes | fr | C | B2 `render/web.clj` (2,300), `render/ns.clj` (480), `resources/public/css` | B2 changes what is rendered, not the page layout; UGLY OUTPUT stays a standing order. |
| opaque-contract-generators-share-live-process-objects | cl | D | class/p1; kept: the opaque runtime schemas (`prepl`, http-kit, mult, SCI ctx) | Generators over live objects survive every lane; the p1 law is the owner. |
| operator-test-status-still-reads-retired-results-branch | fr | A | B1 commit 12 (`fresh_operator.clj` → ~600 lines) + B4 §8 decision (results live where the run ran) | The `:test-results` reader is inside the deleted span. |
| namespace-responsibility-and-issue-workers-are-singular | bl | B | B3 §2b (`seon.task/agent`, many agents per namespace) + README vocabulary `:seon.ns/agents` | The singular `:seon.ns/steward` and `steward-call` are replaced by the many-to-many ruling. |
| operator-status-refuses-foreign-live-root | fr | A | B1 §2c (claims and creator checks deleted; status is one prepl request) | The creator-comparison refusal is in the deleted claim machinery. |
| operator-root-inference-guesses-from-directory-names | fr | D | class/p2; kept: `cluster.clj` root inference, `fresh_operator.clj` (600 lines survive) | B1 shrinks the operator but names no replacement for the directory-name inference; the naming-convention law is the owner. |
| output-sink-query-excludes-operator-and-mcp-scripts | fr | C | B1 `fn.clj` `:seon.fn/external-sink` + `script/` roots (kept) | `script/` stays outside the indexed roots by design; no spec revisits it. |
| one-lanes-intermediate-edit-refuses-adoption-for-every-lane | fr | A | README §8 (lanes, `bin/codex-agent`, `SEON_CODEX_LANE` deleted) + B1 §2a (changed paths only) | Multi-lane concurrent editing is the condition the cut removes; publication takes only the changed paths. |
| orphan-ast-nodes-outlive-the-declaration-that-held-them | cl | A | AGENTS §3 ("the old `seon.fn.ast` family is deleted"; 0 hits in `src/` at HEAD) | The AST entity family no longer exists. |
| ordinary-turns-do-not-use-the-additive-system-turn | fr | B | B2 commit 6 (`turn-source` selects `:generate` for every turn) | The system-turn source becomes one arm of the single turn function, not a debug-only API. |
| platform-flow-census-reaches-root-cleanup-through-scheduler | bl | A | B4 commit 1 (tier selection and the census deleted; `--platform` is one JVM) | Its subject is the platform selection inside the deleted launcher. |
| operator-fast-iterations-end-on-unattributed-term | fr | A | B4 commit 1 + B1 commit 12 | Both the fast launcher and the operator test suites it ran are deleted. |
| platform-fixtures-refuse-function-schema-acquisition | fr | A | A1-2 (no rebuild) + B4 commit 5 (no published base, no lag) | "Published base 15 commits behind" is not a state the new fixture can be in. |
| priming-indexes-with-the-live-jvms-loaded-code | bl | B | B1 §2a (one `refresh-source!`; the JVM reads changed paths and reloads the report's namespaces) | Indexing and the loaded code converge in one request; `bin/seon index` as a separate path goes. |
| orderly-stop-completion-joins-have-no-bound | bl | C | B2 `flow.clj` (600), B1 `cluster.clj` stop path | B2 deletes the launcher but names no bound for the remaining teardown joins. |
| post-adoption-check-times-out-on-message-contract-test | fr | A | B4 commit 3 (`check`/`check-in-process` and the deferred check command deleted) | The automatic post-adoption check is one of the deleted entry points. |
| parallel-test-stress-exposes-eleven-isolation-sensitive-tests | fr | A | B4 commit 1 (`--full`, the nine-worker pool and parallel execution deleted; serial by ruling) | Parallel isolation is not a property the new runner has. |
| program-graph-tests-do-not-carry-their-current-contract-projection | bl | A | A1-2 + B4 commit 5 | A fixture carrying a stale projection is unconstructable once the value carries it. |
| packaged-forms-per-call-reads-recur-at-every-new-caller | fr | A | A1-3 (`packaged-population-cache` deleted; `packaged-forms` keeps the stamped read) + A1-2 | The per-call re-read and its cache are both removed by the carried-projection seam. |
| production-docstrings-teach-deleted-semantics | fr | D | class/n12; kept: every docstring as a program fact | The cut deletes many of the mechanisms these strings describe, but the class recurs with each rename. |
| posh-cardinality-one-pull-analysis-has-an-arity-defect | fr | A | README §8 (89 submodules unvendored; `posh` is uncited) | The vendored comparative source is unvendored. |
| provider-failure-diagnostic-is-absent-from-next-prompt | fr | B | B2 §2c (prompt from the walk's units) + B3 §2a (the fault's shown text) | A recorded provider failure renders into the next prompt through the one history. |
| platform-tier-rejects-small-publication-fixture-observations | fr | A | B4 commit 1 (`runner_test.clj` deleted) + B1 §7 (one small publication fixture) | The tier-selection refusal and its test are both deleted. |
| prospective-issue-refs-render-as-unnamed-checks | fr | A | B3 commit 6 (`seon.issue/status-text`, the issue renderer deleted) | Its subject is the issue render pair. |
| problems-rejects-signatures-with-no-occurrences | fr | B | B3 §2a (occurrence count derived inside `commit-call`) + B2 (the routed-problem block leaves `turn.clj`) | The zero-occurrence signature stops being representable once occurrences are components of the root. |
| publication-issue-indexing-refuses-in-test-runner-fixtures | bl | A | B3 commit 6 (note indexing deleted) + B4 commit 1 (the fixtures deleted) | Both halves are deleted mechanisms. |
| problems-refuses-its-own-zero-occurrence-signature | fr | B | same as above (B3 §2a) | Duplicate shape of the previous note; the stored occurrence model changes under it. |
| provider-reference-and-price-schedule-drift | fr | C | B3 `config/default.edn` provider descriptor rows (kept) | Provider descriptor accuracy survives; B3 cuts dials, not the descriptor's content. |
| recorded-selection-completion-leaves-changed-members-pending | fr | A | B4 commit 3 (`selection.clj` deleted; `select` shrinks to ≤ 120 lines) | `complete-selection!` and its obligations model are deleted. |
| prompt-tests-retain-incompatible-turn-fixtures | fr | A | B2 §7 (seven turn namespaces → three classes) + B4 commit 5 | The fixtures are in the deleted/collapsed corpus. |
| program-namespace-retraction-needs-shared-schema-writer | fr | C | B1 `program.cljc`/`fn.clj` + `my/program.clj` (kept) | Namespace retraction must also reconcile schema attributes; no spec states it. |
| render-candidate-checks-mix-different-arities | fr | C | B2 `render.clj` candidate selection (kept, 1,400) | Per-render candidate selection survives; the arity-mixing rule is untouched. |
| read-and-admission-producers-still-require-thread-projections | fr | A | A1-2 / A1-2b (`handed-projection` fallback becomes a refusal once B1 names the boot loader) | The thread/handed projection path is deleted at the seam. |
| pull-caps-cardinality-many-at-1000-so-recorded-reach-reads-truncate-silently | fr | B | A2 f2 (`+default-limit+` nil) + B4 commit 7 (observed reach, no reach-digest index) | Both readers it names are rewritten and the silent cut is removed in the fork. |
| provider-output-token-wire-key-is-hard-coded | fr | B | B3 §2c (the seven `:seon.config.ai/*` wire passthroughs read through `:seon.ai/wire`; moving them onto the provider descriptor row is B2's item) | The literal on the dial moves onto the descriptor row. |
| renderer-codec-fixture-loses-a-declared-contract | fr | A | A2 c2 (the transaction-function codec deleted) | Its subject is agent-authored render symbols crossing the deleted codec. |
| recomputed-program-digest-disagrees-with-the-stored-run-digest | fr | A | B1 §2g (per-definition digest; `runner/program-digest` and the aggregates deleted) + B4 commit 3 | The recomputed aggregate digest is deleted on both sides. |
| reap-dead-roots-calls-delete-recursively-with-a-nil-path | fr | A | B1 §2c (`reap-dead-roots!`, census and claims deleted) | The function is named in the deleted span. |
| raw-identity-projection-hides-selected-steward | fr | A | B3 §2b + README vocabulary (`:seon.ns/steward` retired) + B2 commit 9 | Both the steward fact and the transcript capture it was observed in are replaced. |
| render-selection-loses-the-viewing-namespace | fr | C | B2 `render.clj` + `render/walk.clj` (kept) | B2 commit 12 deletes the debug namespace stage, not the viewing-namespace preference in ordinary selection. |
| rebuilt-test-evidence-refers-to-another-transaction-history | bl | A | B1 commit 7 (no rebuild from `:db`; one transaction on `current-src`) + B4 (run rows recorded where they ran) | The publisher's copy of run rows is deleted. |
| reset-refork-refuses-held-elsewhere-without-naming-the-holder | bl | C | B1 §2c reset path, `operator/state.clj` flock (kept, 700 lines) | The root lifecycle flock survives the operator cut; naming the holder is not in the spec. |
| recursive-call-coverage-outlives-its-cancelled-future | bl | C | B1 `fn.clj` `function-reaches` (kept in the 2,000-line target) | The all-pairs recursive relation is not on any deletion list; the unbounded recursion stays. |
| reset-deletes-a-bloated-store-one-lstat-at-a-time | fr | C | unowned remainder `fs.clj` `delete-recursively!` + B1's reset command | The store no longer bloats (the cut's whole point) but O(files) lstat deletion is untouched. |
| render-fixtures-dump-context-on-stale-assertions | fr | A | B4 commit 1 (`test_runner_test.clj` deleted) + B2 §7 | The fixture and the 3.8 GB log it produced belong to deleted suites. |
| retracting-a-listened-entity-broadens-the-pattern | bl | C | B2 `cluster/wake.clj` (kept) + `seon.listen.edn` | B2 edits one line of `wake.clj` for A2's codec; the optional-ref-means-any-entity rule survives. |
| render-contract-coherence-stops-at-a-transparent-schema-wrapper | fr | B | A1-1 (`m/deref-all` on the compiled contract) + A1-5 (slots read off the compiled node) | Transparent `:schema` wrappers are traversed by Malli's own accessors instead of a Seon coherence check. |
| retired-form-projection-still-declared-and-selected | cl | A | B2 commit 9/11 + README vocabulary (`:seon.render/form` is a legacy spelling) | `:seon.render/form` is declared retired and its consumers are in the deleted transcript/ladder spans. |
| reply-sources-return-contract-disagrees-with-vector-results | fr | C | B2 `cluster/reply.clj` (kept) | A declared-union/return-shape mismatch in a namespace no spec rewrites. |
| root-page-warm-read-evidence-replay-exceeds-300ms | fr | A | A2 c1 (replay deleted; currency is revision equality, ≤ 5 ms for 435 reads) | The replay it measures is the deleted arm. |
| repl-parity-divergences | fr | C | B2 `sci/*`, `repl.clj` (kept); `repl_parity_test.clj` is not in any deletion list | The standing parity gate survives and its divergence list is unaddressed. |
| retained-identities-have-no-declared-retirement-state | bl | A | AGENTS §3 (deletion is retraction; "no program tombstone or retirement sentinel is stored") + B1 `program.cljc` | The ruling deletes the premise: there is no retirement state to declare. |
| root-maintenance-context-exceeds-provider-budget | fr | C | unowned remainder `maintenance.clj`, `schedule.clj`; B2 `cluster/prompt.clj` whole-unit selection | The maintenance portfolio is still [TARGET]; prompt budgeting changes but the maintenance context is unowned. |
| runtime-block-html-is-raw-ids-and-instants | fr | B | B2 commit 9–10 (one declared pair per entity, one HTML view) + UGLY OUTPUT standing order | The `:seon.runtime/entity` HTML is re-authored as a declared pair. |
| restorable-node-has-no-caller-after-the-shown-text-cut | cl | A | B2 §2a (shown text; no restorable print node) + B3 §2a | `restorable-node`'s whole job is reading a stored print node, which the ruling deleted. |
| root-empty-plan-read-shows-nil | fr | A | B3 commit 6 (`my.plan` deleted; `seon.task` replaces it) | The plan read it corrects does not exist after the task merge. |
| running-fixture-regression-exceeds-in-process-bound | fr | A | B4 commit 5 (fixture ≈ 40 ms) + commit 6 (`long ⇒ long-ms`) | Its cause is the 4,648 ms base the cut deletes. |
| runtime-status-throws-on-a-map-entry | fr | C | B1 `script/seon/dev/mcp.clj` projection (kept — the MCP tool is the lanes' and agents' seam) | The MCP value projection is not in any spec; the `MapEntry` cast is live. |
| root-compute-executor-has-no-per-cluster-fairness | fr | C | B2 `flow.clj` (600) + `operator/runtime.clj` executors (kept) | B2 deletes the capacity observer; per-cluster admission is named nowhere. |
| runner-launcher-failures-repeat-complete-classpaths | fr | A | B4 commit 1 (`test_runner_test.clj` and the launcher deleted) | Its subject is the deleted concurrent-launcher fixture. |
| runtime-status-refuses-error-occurrence-count | fr | B | B3 §2a (occurrence count from the component sum) | Same stored-model change as the two `problems` notes. |
| schema-guard-refuses-accretive-loosenings-with-data | fr | A | B2 commit 6 (schema-change machinery and `assert-schema-data-unused!` deleted; the writer's validator owns it) | The guard it names is inside the deleted span. |
| root-turns-on-a-fresh-dev-cluster-hit-the-context-acquisition-backstop | fr | A | B2 commit 7 (the backstop deleted) + B3 §2c (`render/context-acquisition` dial deleted) | Both the bound and the render-context channel it waited on are deleted. |
| schema-field-types-admit-contradictory-owning-values | bl | C | A1 `fn/schema_shape.clj` (390) + A2 c6 validator | A2 narrows the validator to the report; cross-field entity guarantees are still unmodelled. |
| runtime-listens-do-not-yet-participate-in-turn-eligibility | fr | B | B2 commit 6 + 8 (`next-agent-work` over the wake in-port; one work derivation) | Work derivation and delivery become the same mechanism. |
| sci-acquisition-must-derive-from-current-identity-provenance | fr | B | B2 commit 3 (one base ctx from `:seon.ns` rows) + B1 §2g (`definition-digest` decides `:jvm` vs interpret) | Acquisition stops calling `admission-from-asserting-transaction`. |
| runtime-lint-does-not-resolve-namespace-aliases | fr | B | B1 §2e (agent-form linting decided: cache on, prelude restricted to `:agent` rows) + §2g (digest carries the alias map) | Alias resolution becomes part of the declared resolver context. |
| schema-declaration-regression-disagrees-with-current-row-shape | fr | A | B2 §7 (`sci/eval_test` rewritten) + B1 commit 5 (row shape re-declared) | The exact-map assertion is against a row shape the cut re-specifies. |
| schema-source-provenance-accumulates-in-a-global-atom | cl | A | A1-3 (`schema/edn.clj:361-390` cache and `forget-packaged-population!` deleted) | `!source-files` is the process-global population state A1 removes. |
| sci-test-declarations-drop-explicit-usage-metadata | fr | A | B4 commit 4 (`:seon.test/usage` among the deleted `seon.test.edn` shapes) | The attribute is in the schema deletion list. |
| runtime-turn-and-evaluate-kernels-conflate-boundaries | fr | B | B2 commit 6 (turn = one function with one source selector) + commit 5 | The two nested bodies are the exact subject of B2's largest cut. |
| schema-projection-state-contract-invokes-deref-as-a-predicate | fr | A | A1-2 + B3 §2c (`projection-state` holder deleted; the value carries the projection) | The holder whose contract dereferenced its candidate is deleted. |
| scratch-root-has-agent-errors-without-attempt-facts | fr | B | B2 commit 6 (`provider-attempt` split from the evaluate/settle tail) + B3 §2a | An evaluation error and a provider attempt become separately recorded facts. |
| sci-error-unions-name-indistinguishable-test-facets | fr | B | B3 §2a (the schema is the meaning; A1's declared-union validation) + B4 commit 4 (`seon.test.edn` shapes deleted) | Both indistinguishable facets are in the deleted schema set. |
| sci-evaluation-tests-still-expect-retired-storage | fr | A | B2 §7 + B3 §2a (stored result EDN deleted) | Tests pinned to retired result storage are in the collapsed corpus. |
| sci-fork-three-arity-passes-nil-to-a-required-projection-state | fr | A | B2 commit 4 (`fork-cluster-ctx`/`projection-state` folded into `fork-for-turn`) | The delegating arity and the required projection-state member are deleted. |
| seon-mcp-tools-absent-in-codex-lane-again | fr | A | README §8 (`bin/codex-agent`, `.codex/*` deleted) | Codex lanes do not exist after the cut. |
| scratch-boot-cannot-load-the-cluster-namespace | bl | B | B1 commit 12 (one child JVM with a resolved classpath) + B4 commit 1 (`test/cache.clj` deleted) | Scratch boot becomes the one `launch!` path. |
| scratch-debug-feed-and-turn-backstops-after-adoption | fr | A | B2 commit 7 + 10 (both backstops and the feed deleted) | Both fault kinds it reports belong to deleted mechanisms. |
| scratch-boot-refuses-the-shell-stdin-predicate | fr | A | A1-3 (the classpath fallback and `declaration-population` refusal path rewritten) + A1-2 | "Predicate has no admitted callable in the corpus projection" comes from the population construction A1 deletes. |
| shell-timeout-refusal-is-not-admitted-by-run-contract | fr | C | unowned remainder `shell/jvm.clj` + B3 `effect.clj` | A handler-failure facet outside the declared return union; B3 owns the boundary, not the capability's union. |
| search-index-property-collides-with-process-index-id | fr | A | B3 commit 10 (`seon.search` deleted whole, including the cluster wiring and `seon.search.edn`) | Both meanings of `:seon.search/index` go with the namespace. |
| seon-db-has-no-branch-or-commit-reads | fr | B | A2 c9 + f3 (`versioning/branch-commit-id` as a fork export; `registry.clj` reads it) | Branch/commit reads get one owner instead of scattered `datahike.api` calls. |
| source-publication-fingerprint-collision-after-config-property-reordering | fr | A | A1-6 (fingerprint = normalized `m/ast`, property order irrelevant) | Property reordering cannot change the canonical AST. |
| september-14-issue-notes-leave-the-issue-authority-invalid | fr | A | B3 commit 6 (`bin/issues-index` reads `seon.task/report`; the note pipeline deleted) | Note-level metadata validation stops being an authority once notes become task rows. |
| secondary-only-attributes-have-no-covering-index | fr | C | A2 `schema/datahike.clj` bridge (kept) | `:db.secondary/only` emission is not in A2's deletion list; no covering index exists. |
| shared-store-grows-during-render-source-work | fr | B | A2 c10 (`:datahike.gc/plan-only?`, konserve `:size`) + B1 §2b (no scratch branch, no seal) + README §2b store targets | The publication writes that grew the store are the ones the cut removes; footprint is a measured integration row. |
| store-grew-to-69-gigabytes-in-one-day-of-lanes | bl | A | README §8 (lanes deleted; reset wipes the store) + B1 commit 7 | One-day-of-lanes growth is not a state the post-cut system reaches; the sweep rule is in AGENTS §6. |
| shown-value-diff-disables-the-dependency-work-bound | fr | A | A2 c3 (the multi-arity `diff` family deleted; only `value-changes`/`apply-diff` survive) + B2 commit 6 | The `:vec-timeout Long/MAX_VALUE` call site in the since-diff is deleted with the five helpers. |
| source-publication-cache-contention-hides-dependency-analysis-failure | fr | B | B1 commit 2 (the sweep and second `run!` deleted) + commit 11 (typed phase refusals) | The lock contention it names is removed and a failed phase is named instead of swallowed. |
| settle-is-public-without-a-complete-contract | fr | A | cites `src/seon/cluster/loop.clj`, deleted at HEAD; T1 covers the successor | The named owner no longer exists; contract coverage is target T1's campaign. |
| terminal-refusal-error-fact-fails-on-oversized-data | fr | A | B3 commit 5 (the stored EDN copy and its size bound deleted) | Oversized `:seon.error/data` is not a stored shape after the cut. |
| stale-dev-dependency-cache-serves-wrong-classes-silently | fr | C | `dev_cache.clj` / `target/dev-dependency-classes` (kept; no spec owns it) | B4 removes the gate that consumed it, but the AOT cache and its silent staleness remain for `-M:dev`. |
| storage-gc-runs-without-a-cutoff-so-it-reclaims-almost-nothing | fr | C | A2 c10 (`dry-run!` → `plan-only?`) + `cluster/registry.clj` (kept) | A2 changes the dry run, not the missing `remove-before` cutoff. |
| source-load-is-118s-against-the-ten-second-law | fr | B | B2 §2h (load order, 15 delays + 7 `requiring-resolve` deleted) + README "seconds, not minutes" | Namespace load time is directly attacked by the layering cut and the ≤ 55,000-line target. |
| stored-runner-facets-require-an-unstorable-offending-value | bl | A | B4 commit 4 (`seon.test.runner.edn` worker/wire families deleted) + B3 §2a | `:seon.test.runner/invalid-marker-reason-error` belongs to the deleted process schema. |
| test-fast-path-snapshots-are-misclassified-as-full-gates | fr | A | B4 commit 1 (`bin/test-fast` deleted) + B1 §2c (the process census deleted) | Both the classifier and the classified mode are deleted. |
| task-execution-fixture-has-no-acquired-sci-program | fr | A | B4 commit 1 (`runner_test.clj` deleted) + commit 5 (the fixture forks the cluster's ctx) | The fixture cannot lack an acquired program once it forks the live base. |
| stop-contract-rejects-stopped-instances | fr | C | B1 `cluster.clj` `stop!` + A1 `instrument.clj` | Idempotent `stop!` versus its declared contract is untouched by every spec. |
| test-check-classifies-completed-errors-as-expiry | fr | A | B4 commit 3 (`check` deleted; one `run` with `bounded-result`) | `check`'s expiry classification is in the deleted function. |
| test-recording-work-exceeds-claim-deadlines | fr | A | B4 commit 2 (claim protocol deleted) | There are no claims to miss a deadline. |
| test-coordinator-label-attributes-worker-startup-to-analysis | cl | A | B4 commits 1–2 (coordinator and workers deleted) | The mislabelled phase belongs to the deleted coordinator. |
| system-generated-messages-omit-arrival-ordinals | fr | C | B2 `cluster/message.clj` (kept) + B3 `seon.error` notices | The renderer-failure and error-notice constructors survive; the missing ordinal is unaddressed. |
| test-host-classification-and-path-use-different-program-edges | fr | B | B4 §2 step 6 (`host`/`destroyers` fed the OBSERVED reach) | Both halves read one reach set instead of two edge derivations. |
| the-agent-profile-no-longer-cuts-an-oversized-rendered-string | fr | C | B2 `render/value.clj` (kept; the one clipping spot) | The value renderer survives the cut as the clipping authority; the profile bug is live and unowned. |
| test-provenance-probe-exceeds-mcp-bound | fr | A | B4 commit 3 (the provenance model deleted) + `test_provenance_test.clj` deleted | An unavailable observation about a deleted mechanism. |
| test-refusal-observations-overflow-in-projection-acquisition | bl | A | A1-2 (no acquisition rebuild) + B3 commit 5 (no stored observation overflow) | Both the acquisition and the overflowing observation shape are deleted. |
| the-agent-history-render-throws-on-a-string-where-it-casts-an-instant | bl | A | B2 commit 9 (`render/transcript.clj` deleted; the walk is the history) | The throwing render is in the deleted namespace. |
| test-check-defaults-an-omitted-cluster | fr | A | B4 commit 3 (`check`/`check-in-process` deleted; `run` takes `:seon.db/connection`, required) | Substituting `"default"` is unconstructable: the connection is a required member. |
| the-first-debug-page-after-an-adoption-takes-eighteen-seconds | fr | B | A1-2 + B2 commits 10 and 12 | The cold cost is acquisition plus the debug page's parallel derivations, both deleted. |
| the-cold-fixture-base-outruns-the-liveness-silence-backstop | fr | A | B4 commits 1 and 5 (the silence backstop and the cold base both deleted) | Neither half exists after the cut. |
| the-default-jvm-loaded-dependency-classes-the-current-cache-no-longer-selects | bl | B | B1 commit 12–13 (one prepl request; no toolchain digest, no producer/classpath comparison) | The loaded-vs-cache comparison that refused adoption is deleted. |
| test-launcher-fixtures-omit-required-helpers | fr | A | B4 commit 1 (`bin/_test-slot` and the launcher fixtures deleted) | The omitted file and the fixture that copied it are both deleted. |
| the-indexer-resolves-its-declaration-world-per-file-and-per-row | fr | A | A1-2 (carried projection) + B1 §2a step 5 (rows built once from one analysis) | Both call-time fetches are the fetch-at-call-time shape both lanes delete. |
| the-indexer-drops-clj-kondos-defined-by-so-generated-declarations-look-authored | fr | C | B1 `fn.clj:600-680` row construction + `fn/analyzer.clj` (kept) | B1 rewrites row construction but adds no `:defined-by` fact; macro-generated declarations stay indistinguishable. |
| the-in-process-test-loader-cannot-load-a-namespace-needing-a-test-alias-dependency | fr | A | B4 commit 1 (`test_runner_test.clj` and `dev-cache` consumer deleted) + §2 (tests run in the cluster's JVM) | The `test-loader` classpath path is deleted with the launcher tier. |
| test-results-persistence-can-time-out-during-development-adoption | fr | A | B4 commit 2 (staged results deleted; one transaction per member) | The post-run persistence stage does not exist. |
| the-loaded-producer-guard-refuses-a-freshly-booted-host | bl | A | B1 commit 8 (toolchain/producer digests deleted) | The guard's inputs are the deleted aggregates. |
| the-live-juniper-fixture-wipes-turns-under-a-running-agent-loop | fr | D | class/p1; AGENTS §5 rule 9 (a fixture never retracts what a running loop is settling) | The instance is fixed at the writer but the rule is a standing fixture law; B4's fixture cut does not restate it. |
| the-issue-ai-render-no-longer-teaches-its-requery-form | fr | A | B3 commit 6 (`seon.issue/render-ai` deleted; `seon.task` has its own pair) | Its subject is the deleted issue render pair. |
| the-debug-page-poller-observes-adoption-stages-twice | fr | A | B2 commit 12 (debug page cut) + B1 §2a (one adoption, not four stages) | Both the poller's test and the multi-stage adoption are rewritten. |
| the-published-graph-shows-zero-out-edges-for-seon-print-emit-after-the-protocol-impls-fix | fr | C | B1 `fn/analyzer.clj` + `fn.clj` edge construction (kept) | B1 changes WHEN the analysis runs, not which clj-kondo rows become `:seon.fn/calls`; protocol-impl bodies still contribute nothing. |
| the-publication-export-does-not-identify-the-exported-program | bl | A | B1 commits 7 and 14 (`export`, the base store and `publication-base!` deleted with B4) | The export artifact it refuses on is a deleted mechanism. |
| the-o1-bootstrap-drive-test-is-red-at-head | bl | E | `bootstrap_drive.clj` + `eval/drive.clj` are in the unowned remainder | No spec owns the drive; whether the red survives the cut is not decidable from the specs. |
| the-index-cache-retains-4gb-of-expanded-schema-shape-forms | bl | A | A1-3/A1-9 (`with-compiled-cache`, `:seon.schema.projection/compiled` 5,589 entries, the population caches deleted) + A1-6 | The dominator is the schema-shape cache the cut removes. |
| the-supported-mcp-runtime-tools-were-absent-from-a-bounded-lane | fr | A | README §8 (lanes deleted) | A lane-tooling observation about a deleted process. |
| the-test-reporter-attributes-a-failing-is-to-its-enclosing-let-binding-line | fr | C | B4 `runner.clj` capture spans (`:111-435`, kept) | The capture surface survives the cut; line attribution is unaddressed and is a named absence-as-health instance. |
| the-query-planner-rejects-a-negation-bound-by-a-recursive-rule | fr | C | A2 `reference-code/datahike` query planner (kept) | A2 adds six fork commits, none in `query/lower.cljc` or `plan.cljc`. |
| the-lifecycle-watchdog-measures-silence-from-lock-acquisition | fr | A | B1 §2d (17 of 18 bounds deleted; one phase-bounds fact, each phase named) | The lifecycle watchdog and its 900 s silence bound are deleted. |
| throwable-class-is-declared-a-string-but-written-as-a-symbol-beside-exception-class | cl | A | B3 §2a (the stored member list keeps `exception-class` only; `throwable-class` is dropped) | One of the two duplicate attributes is deleted and the type is re-declared at the reset. |
| transaction-feedback-regressions-disagree-with-final-report-validation | fr | A | A2 c6 (the validator narrowed) + B2 §7 (the turn suites collapse) | The regressions and the validator they disagree with are both rewritten. |
| the-thirty-second-write-bound-fails-program-publication-under-load | bl | B | A2 c6 (validator ∝ the report) + B1 §2b (`init-zero` ≤ 60 s, transaction ≤ 10 s) | The publication transaction stops being able to exceed the write bound. |
| the-over-bound-evaluation-path-returns-a-lookup-ref-where-its-contract-promises-a-string | fr | B | B3 §2a (`(seon.error/error m)`, declared members only) + A1-1 | The message member stops being constructed from a lookup ref by the deleted `diagnostic-*` ceremony. |
| transcript-candidate-window-orders-receipts-and-comments-by-id | fr | A | B2 commit 9 (`render/transcript.clj` deleted) | Its subject is the transcript's bounded candidate window. |
| turn-bookkeeping-exceeds-recorded-regression-bound | fr | A | B2 commit 6 (delimiter repair and the six-form bookkeeping path deleted) | The sweep already kept it out of class/n9; the measured path is deleted whole. |
| transaction-html-has-a-second-generic-value-renderer | fr | A | B2 §2.4 one clipping spot + commit 10 (`transaction-value-html` is one of the parallel renderers) | A second generic renderer is forbidden by the one-renderer construction. |
| the-root-page-first-paint-after-a-reset-exceeds-forty-seconds | fr | B | A1-2 + B2 commits 10–11 | The `neighborhood` → `schema.form` path it dumps is the O(program) render both lanes delete. |
| turn-evaluations-bypass-work-submission | fr | A | B2 commit 6 (`submit-evaluation!!` deleted) + commit 7 (the launcher deleted) | The unused helper and the submission path it bypassed are both deleted. |
| turn-loop-regressions-still-expect-retired-state-and-time-shapes | fr | A | B2 §7 (seven turn namespaces → three classes) | The whole namespace is in the collapsed corpus. |
| turn-consumer-fixtures-read-retired-result-storage | fr | A | B2 §7 + B3 §2a (stored result EDN deleted) | Same corpus and same retired storage. |
| thinking-tool-continuations-have-no-faithful-request-shape | fr | C | B2 `ai.clj`/`ai.cljc` (kept; B2 converts only the retry policy) | Assistant history, tool calls and reasoning content are still unrepresentable. |
| two-turn-backstops-fire-and-the-sliding-fault-channel-keeps-the-wrong-one | fr | A | B2 commit 7 (the backstop and the fault channel's sliding buffer deleted; `::flow/error` is the one report) | Two backstops cannot fire when there is one deadline. |
| turn-source-syntax-blocks-default-adoption-2026-09-08 | fr | B | B1 §2a step 4 (blocking findings refuse before any write, naming the file) | An index refusal becomes a named, path-scoped finding instead of a whole-tree block. |
| unlogged-findings-2026-08-01 | fr | E | five unrelated micro-findings (`bin/css`, `bin/fix-bootstrap-macros`, SCI `:classes`, a deleted schema resource) | A bundle note; its members span deleted and kept code and cannot be classified as one row. |
| transcript-and-turn-fixtures-retain-deleted-storage-and-turn-shapes | bl | A | B2 §7 + B3 §2a + B4 commit 5 | Every named fixture and storage shape is deleted. |
| vendor-parinferish-under-reference-code | cl | A | B2 commit 6 (delimiter repair deleted — `sci.reader`'s typed refusal is the reply) | The dependency it asks to vendor has no consumer after the cut. |
| unowned-namespace-oversight-still-inverts-assignment | cl | A | B3 §2b + README vocabulary (`:seon.ns/agents`, many-to-many) + B2 (the routed-problem block leaves `turn.clj`) | `unowned-namespaces` derives from the retired singular steward edge. |
| vendored-babashka-process-carries-a-local-aot-patch | cl | E | README §8 keeps 20 submodules but does not name them | Whether `babashka-process` is among the 12 on the classpath is not stated; the uncommitted patch question needs that list. |
| turn-declaration-transactions-have-no-agent-or-turn-metadata | fr | B | B3 §2a (provenance in tx-meta, never on the entity) + B2 commit 6 | Declaration transactions gain the same tx-meta provenance every write uses. |
| virtual-turn-control-loses-agent-routing | bl | A | B2 commit 12 (debug controls cut to the four §16 sections) + commit 8 (routing is the wake in-port) | The virtual-turn POST and its routing atom are rewritten with the debug page. |
| vendored-transit-clj-drifts-from-the-pinned-artifact | cl | E | README §8 unvendors 89 submodules but does not list the 20 survivors | Same gap as the babashka-process note: the decision depends on a list the plan defers. |
| web-debug-fixture-transactions-and-budget-expectations-fail | fr | A | B2 commit 12 + §7 (`web_debug_test` cut to the four sections) | Its assertions are against deleted debug derivations and budgets. |
| two-keyword-value-members-are-mistaken-for-one-lookup-ref | bl | C | A2 `db.clj` write path + the Datahike fork `maybe-wrap-multival` | A2's fork commits do not touch `transaction.cljc:718-737`; a two-keyword set is still read as a lookup ref. |
| within-run-schema-key-refinement-needs-an-owner-ruling | fr | E | owner ruling requested; the specs do not rule it | A divergent `:seon.schema/key` form with no data and no dependents: no spec decides it. |
| wildcard-pull-rearming-refuses-a-loaded-contract | fr | B | A1-1 (one compiled contract per Var, no per-wrapper recompile) + A2 c5 (read-side pulled validation deleted) | The re-arm that refused a wildcard pull's unparsed fields is deleted on both sides. |
| worktree-edit-hook-publication-targets-main-root | fr | A | AGENTS §12 (lanes never create worktrees) + README §8 (`tmp/head-wt` deleted) + B1 commit 13 | Worktree publication routing is removed with the multi-lane process. |
| value-floor-fixtures-still-hand-strings-to-symbol-typed-attributes | fr | A | AGENTS §3 symbols-everywhere ruling + B4 commit 5 (fixture rebuilt; B4 §5 fixes the string sites on the way) | The string-for-symbol fixtures are named for conversion in the cut. |
| write-reference-validation-substitutes-zero-before-malli | cl | B | A2 §2 (d)/(f) (the validator reads the report's datoms; pulled-form inference deleted) | The `0` substitution belongs to the pre-Malli reference rewriting A2 removes. |
| writer-rejection-prints-the-complete-program-projection | fr | A | A2 c2 (`schema.datahike/encode*` and the codec deleted) + A1-2 | The projection stops crossing the writer's `:args` at all. |
| virtual-loop-fixture-submission-can-race-an-armed-turn | fr | A | B2 §7 (`loop_proof_test` in the collapsed corpus) + commit 7 | The pooled gate and the submission path are both deleted. |
| wildcard-pulled-collections-do-not-satisfy-entity-set-contracts | cl | B | A2 §2 (f) + f2 (pull complete or refuse) + AGENTS §3 (the pulled shape DERIVES) | Wildcard pull stops being validated against the stored entity schema. |
| write-seam-is-a-named-set-not-a-declared-fact | cl | C | B1 `fn.clj` (2,000, kept) — `write-seam-symbols` is not in any deletion list | A one-member hand list mirroring a program-graph fact; still live at HEAD. |

## 3. Counts

| Class | Count | blocker | friction | cleanup |
|---|---:|---:|---:|---:|
| **A** subject deleted or replaced by a spec | **165** | 20 | 130 | 15 |
| **B** subject survives, the defect is dissolved by a spec | **95** | 23 | 68 | 4 |
| **C** subject survives, no spec addresses it (**the keepers**) | **70** | 10 | 56 | 4 |
| **D** design-law / class note whose class can still occur | **26** | 7 | 16 | 3 |
| **E** undecidable | **13** | 1 | 9 | 3 |
| **total** | **369** | **61** | **279** | **29** |

Severity totals match the sweep's §5 exactly (61 / 279 / 29), so no note was
missed or double-counted.

**A + B = 260 of 369 (70 %) close at a lane's landing**, 165 of them because
the mechanism they describe stops existing. The remaining 109 are the 70
keepers, the 26 standing class/law notes, and 13 undecidable.

Lane attribution (a row may name more than one lane; these are mentions, not
a partition): B2 115 · B1 83 · B3 73 · B4 72 · A1 53 · A2 45 · C1 2 · D1 1.

| Class | A1 | A2 | B1 | B2 | B3 | B4 | C1 | unowned remainder |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| C (keepers), by PRIMARY lane | 2 | 18 | 13 | 23 | 5 | 2 | 0 | 7 |

## 4. The keepers (class C), grouped by the lane whose files they sit in

These are live defects in code the cut keeps. Each implementing lane should
read its group before landing, because every one of them is a defect its own
spec will otherwise carry forward unchanged.

### A1 — `schema*`, `instrument.clj`, `call_preparation.clj`, `fn/schema_shape.clj` (2)

| Note | The defect in one line |
|---|---|
| instrumented-vars-hide-their-constant-pool-from-runtime-dependency-inspection | The armed wrapper's `AFunction$1` class hides the original's constant pool, so runtime dependency inspection sees nothing — and C1 adds a second closure layer on the same seam. |
| schema-field-types-admit-contradictory-owning-values | Entity schemas type each field but state no relationship between fields, so a live validator admits contradictory owning values. |

### A2 — `db.clj`, `schema/datahike.clj`, `cluster/{store,registry}.clj`, `blob.clj`, the Datahike fork (18)

| Note | The defect in one line |
|---|---|
| blob-get-assumes-file-store-callback-shape | `seon.blob/get` assumes konserve skips the `bget` callback for an absent key; the memory store calls it with `{:input-stream nil}`. |
| blob-roots-are-derived-from-digest-shape-not-from-a-declared-fact | Two functions answer "which attributes name a blob" by walking schema forms instead of reading a declared fact. |
| interrupted-blob-staging-leaves-no-observable-artifact | An interrupted oversized write leaves no staging file, and the next assertion NPEs on the absence. |
| bound-transaction-input-selects-an-older-turn | A datom-pattern input does not constrain the query; an older accepted reply is selected. |
| two-keyword-value-members-are-mistaken-for-one-lookup-ref | `maybe-wrap-multival` reads a legitimate two-keyword set as a lookup ref, refusing canonical population. |
| the-query-planner-rejects-a-negation-bound-by-a-recursive-rule | The fork's planner refuses a negation whose variable is bound by a recursive rule. |
| datahike-fork-is-28-commits-behind-upstream | 28 upstream query-engine correctness fixes are missing and A2 never rebases. |
| expected-refusal-logs-raw-datom-error-twice | A designed refusal logs the raw datom exception AND the typed refusal — the fork's writer logging seam, outside `seon.render`. |
| error-observation-aliases-inherit-native-uniqueness | The bridge follows an attribute alias before reading storage properties, so an observation attribute inherits `:db.unique/identity`. |
| secondary-only-attributes-have-no-covering-index | The bridge will emit `:db.secondary/only` although Seon declares no secondary index anywhere. |
| default-pull-unknown-attribute-omits-candidates | An unknown read attribute returns Datahike's generic exception data instead of teaching installed candidates. |
| database-value-shape-name-duplicates-the-db-key | `:seon.db/database-value` is a second name for `:seon.db/db` (~146 occurrences). |
| keep-history-is-on-by-default-without-a-decision | The store configuration inherits Datahike's `true` rather than stating the ruled default. |
| storage-gc-runs-without-a-cutoff-so-it-reclaims-almost-nothing | `d/gc-storage` without `remove-before` marks every extant branch's whole ancestry. |
| cohost-start-races-the-reachability-sweep | A second cluster's start races the GC sweep. |
| file-store-commits-pay-five-times-the-fsyncs-they-need | Fsync counts per commit (the note already narrows the claim to the current configuration). |
| foreign-write-fence-reads-only-the-dynamic-var | `transact!`'s cross-cluster fence exists only while `*conn*` is bound — and C1 §6 leans on exactly that check. |
| kondo-does-not-resolve-datalog-parser-generated-variable-constructor | `parser.type/->Variable` is unresolved at `db.clj:579` even with a repopulated cache. |

### B1 — `fn.clj`, `fn/*`, `program.cljc`, `cluster.clj`, `cluster/source.clj`, `operator*`, the hook (13)

| Note | The defect in one line |
|---|---|
| the-published-graph-shows-zero-out-edges-for-seon-print-emit-after-the-protocol-impls-fix | Protocol-implementation bodies contribute no `:seon.fn/calls` edges although `:protocol-impls` is enabled. |
| the-indexer-drops-clj-kondos-defined-by-so-generated-declarations-look-authored | Nothing records which macro interned a declaration, so `deftype`/`defrecord`/`defprotocol`/`defmulti` rows look hand-written. |
| recursive-call-coverage-outlives-its-cancelled-future | The all-pairs `function-reaches` relation does not complete and survives cancellation. |
| write-seam-is-a-named-set-not-a-declared-fact | `write-seam-symbols` is a one-member hand list mirroring a program-graph fact. |
| output-sink-query-excludes-operator-and-mcp-scripts | The output-floor diagnostic cannot see `script/`, where the operator and MCP live by design. |
| program-namespace-retraction-needs-shared-schema-writer | `my.program/remove-ns!` retracts rows without reconciling the namespace's schema attributes. |
| fault-injection-drops-an-existing-callable-arity | Fault injection replaces a transform and loses one of its arities. |
| duplicate-identity-refusal-evidence-is-unordered | Reconciliation picks the reported duplicate identity by hash-map iteration. |
| cluster-boot-instruments-in-flight-working-tree-vars | Boot forks the published commit for data but arms whatever the working tree loaded. |
| cluster-named-store-collides-with-the-store-directory | No reserved cluster names; a cluster can be placed at the store's own directory level. |
| reset-refork-refuses-held-elsewhere-without-naming-the-holder | A refork refusal reports "held elsewhere" without naming the holder. |
| stop-contract-rejects-stopped-instances | `stop!`'s documented idempotent nil is refused by its own armed contract. |
| runtime-status-throws-on-a-map-entry | The MCP value projection throws `ClassCastException` on a `MapEntry`; the MCP tool is the agents' and orchestrator's live seam. |

### B2 — `sci/*`, `turn.clj`, `render*`, `cluster/{agent,wake,message,prompt,reply}.clj`, `flow.clj`, `ai.clj`, `my/*` (23)

| Note | The defect in one line |
|---|---|
| a-blocking-realization-is-not-bounded-by-the-interrupt | The admission walk realizes a child before it can poll `:interrupt-fn`, so a blocking lazy source is unbounded. |
| admit-inst-overlap-prefers-collection-shape | An object that is both `Inst` and `Collection` is projected as a vector instead of a `Date`. |
| agent-repl-cannot-require-clojure-pprint | The agent's base ctx has no `clojure.pprint`. |
| cohosted-clusters-share-one-unbounded-agent-heap | SCI's time limit is not a heap limit; one cluster can exhaust the shared JVM. |
| root-compute-executor-has-no-per-cluster-fairness | One process-root compute pool with no cluster identity, quota or fair admission. |
| orderly-stop-completion-joins-have-no-bound | Several teardown joins omit the loud bounded half, so `stop!` can park forever. |
| message-completion-replies-from-the-wrong-agent-and-duplicates-the-trigger | A two-message conversation produces a duplicate self-request and a reply carrying the wrong agent's text. |
| system-generated-messages-omit-arrival-ordinals | Renderer-failure and error-notice constructors omit `:seon.cluster.message/ordinal`, so ordering silently substitutes zero. |
| retracting-a-listened-entity-broadens-the-pattern | An optional entity ref means "any entity", so retracting the target broadens a listen pattern instead of ending it. |
| inline-form-in-reply-prose-becomes-an-evaluation | The reply reader admits an inline map from explanatory prose as an evaluation. |
| reply-sources-return-contract-disagrees-with-vector-results | `cluster.reply/sources` refuses its own return for a vector result. |
| repl-parity-divergences | The standing REPL parity gate's divergence list is unaddressed and the gate is in no deletion list. |
| negative-import-masks-escape-static-admission | SCI persists a nil-mask after `ns-unmap`, which static admission does not see. |
| dynamic-in-ns-cannot-persist-definition-namespace | A nested `in-ns` changes the live SCI namespace without establishing the namespace row the next definition needs. |
| concurrent-eval-test-calibrates-interpreted-work-to-wall-time | CPU speed and JIT state are part of a correctness oracle. |
| a-generic-attribute-scoped-render-answers-with-the-owning-entity | An attribute-scoped render with no declared pair falls to the generic printer. |
| render-candidate-checks-mix-different-arities | Candidate discovery can satisfy input and output claims from different arities. |
| render-selection-loses-the-viewing-namespace | Selection derives the rendered member's namespace and skips the viewing agent's own candidates. |
| the-agent-profile-no-longer-cuts-an-oversized-rendered-string | The one legal clipping spot stops cutting — measured at HEAD, predating the facet change. |
| namespace-layout-confines-most-content-to-scroll-boxes | One unit takes the page width; everything else is a narrow column of nested scroll boxes. |
| my-fs-write-docstring-hides-its-own-request-shape | The capability docstring an agent writes calls from omits its own request shape. |
| thinking-tool-continuations-have-no-faithful-request-shape | `seon.ai` cannot represent assistant history, tool calls, tool results or reasoning content. |
| help-trial-copies-prompt-and-invents-results | A measured provider trial copied the prompt's prefix and invented a `#:seon.repl` value. |

### B3 — `error*`, `config.clj`, `effect.clj`, `env.clj`, `bootstrap.clj`, `seon.task` (5)

| Note | The defect in one line |
|---|---|
| a-missing-required-dial-kills-every-io-prepl-connection | A newly required dial with no reconciled value kills every io-prepl connection instead of degrading. |
| filesystem-dials-absent-throws-a-bare-nullpointerexception | Absent filesystem dials throw a bare NPE. |
| background-binary-settlement-does-not-publish-required-event | An accepted background binary request can end with no terminal transaction event — and background rows are the ONE effect row B3 keeps. |
| effect-feedback-orders-receipts-by-id | Effect feedback and background-result selection order by effect id string. |
| provider-reference-and-price-schedule-drift | Provider descriptor prices and assumptions are stale against the September trial. |

### B4 — `test.clj`, `test/runner.clj`, `test_support.clj` (2)

| Note | The defect in one line |
|---|---|
| a-fixture-refusal-loses-its-diagnostic-at-the-test-reporter | The fixture throws the complete flat error as ex-data and the reporter prints only class/message/frame — the named absence-as-health instance. |
| the-test-reporter-attributes-a-failing-is-to-its-enclosing-let-binding-line | A failure header names a `let` binding line, not the assertion; the capture spans survive the cut. |

### Unowned remainder — no lane owns these files (7)

`fs*`, `shell/*`, `maintenance.clj`, `schedule.clj`, `eval/*`,
`bootstrap_drive.clj`, `dev_cache.clj` are in README §2b's "remainder of
`src/`" row (~10,900 → 8,000) with no spec. **Whoever takes that row inherits
these seven**, and three of them are bounded-execution defects.

| Note | The defect in one line |
|---|---|
| eval-drives-duplicate-a-four-minute-run-clock | Two drivers independently invent the same `(or supplied 240000)` fallback. |
| reset-deletes-a-bloated-store-one-lstat-at-a-time | `delete-recursively!` is O(files) at syscall pace, single-threaded (21+ minutes on 69 GB). |
| root-maintenance-context-exceeds-provider-budget | Maintenance runs cannot reach the model: the minimum-distance prompt already exceeds the budget. |
| filesystem-stat-refusal-fails-its-return-contract | An outside-root stat refusal fails its own armed return contract. |
| shell-timeout-refusal-is-not-admitted-by-run-contract | The shell timeout's handler-failure facet is not in the declared return union. |
| stale-dev-dependency-cache-serves-wrong-classes-silently | `target/dev-dependency-classes` served stale classes for twelve days with no signal. |
| fresh-cljc-files-are-jvm-only | Seven `.cljc` files with no CLJS consumer; the owner's standing preference is to maximize portable `.cljc`. |

## 5. Notes whose claim contradicts a spec — review findings for the clean write

Eleven places where a note's evidence and a spec's design disagree. Each is a
question for the clean write, not a classification.

1. **`foreign-write-fence-reads-only-the-dynamic-var` vs C1 §2 option (b).**
   The note's finding is that `seon.db/transact!`'s cross-cluster fence is
   only `(when (some? *conn*) …)` — it exists only while the dynamic var is
   bound, and compares against that same var. C1 CHOSE `*conn*` as the
   profiler's branch attribution and adds a binding at `turn.clj:5007` to
   widen its coverage. The cut therefore makes a seam the note calls
   structurally unsound carry more weight. C1's own §6 names the writer's
   mismatch check (`db.clj:477-481`) as the guard; the note says that guard
   IS the dynamic var. Decide at the clean write which is true.
2. **`pull-caps-cardinality-many-at-1000…` and `a-wildcard-pull-is-still-cut-at-one-thousand-members` vs A2 c4.**
   A2 deletes `total-pull-selector` AND sets `+default-limit+` to nil in the
   fork. The deletion is safe only if the fork commit lands first; the specs'
   own ordering (f2 before c4) says so, but B4 commit 7 also stops using the
   truncating reader. If the fork is refused upstream, deleting
   `total-pull-selector` re-opens both notes at full severity.
3. **`the-agent-profile-no-longer-cuts-an-oversized-rendered-string` vs the one-clipping-spot law.**
   B2 keeps `render/value.clj` as THE clipping spot and B2 §2c requires the
   namespace page's AI text to emit one elision value under the profile. The
   note measures that the profile does not cut at all at HEAD. If that is
   still true when B2 lands, the law's single enforcement point is inert and
   every "HTML never clips / AI always clips" acceptance in B2 §5 commit 11
   is unprovable.
4. **`background-binary-settlement-does-not-publish-required-event` vs B3 §2d.**
   B3 keeps background effect rows precisely because "only background rows
   can be open across a restart" — the recovery argument. The note's evidence
   is that a background binary request can end with NO terminal event. The
   one row family B3 keeps for recovery is the one with no proof it settles.
5. **`write-seam-is-a-named-set-not-a-declared-fact` vs B1's `:seon.fn/writes`.**
   B1 keeps `fn.clj`'s writes derivation but never deletes
   `write-seam-symbols`, a one-member hand list — an AGENTS §2.2 banned
   substitute sitting inside the lane that owns the program graph.
6. **`the-published-graph-shows-zero-out-edges-for-seon-print-emit…` vs B4's whole premise.**
   B4's fallback for a never-executed test is `gate-sets` over
   `:seon.fn/calls`. If protocol-implementation bodies contribute no call
   edges, the first-run seed is not merely saturated (B4 §4) but *incomplete*
   in the opposite direction for every protocol. B4 §8's option (1) treats
   static reach as "the unknown floor"; this note says the floor has holes.
7. **`namespace-binding-targets-are-symbols-not-refs` vs AGENTS §3 G2.**
   The note asks for refs so a graph walk can traverse alias/refer rows. The
   ruling since 2026-09-16 says an observed NAME is a value and only a
   statement about a living entity is a ref. The note is arguing against a
   landed ruling; the clean write should either close it as superseded or
   record why alias targets are statements.
8. **`datahike-fork-is-28-commits-behind-upstream` vs A2's six fork commits.**
   A2 adds `:db.type/any`, a nil pull limit, `branch-commit-id`,
   `plan-only?` and possibly an Integer coercion — five more commits ahead
   on a fork already 95 ahead and 28 behind, with the missing 28 described as
   query-engine correctness fixes. Nothing in any spec schedules the rebase,
   and `the-query-planner-rejects-a-negation-bound-by-a-recursive-rule` is a
   live planner defect in the same engine.
9. **`runtime-status-throws-on-a-map-entry` and the two MCP-tools-absent notes vs the plan's REPL protocol.**
   Every lane spec's §4 depends on `mcp__seon__eval_clj` and
   `runtime_status`. `script/seon/dev/mcp.clj` is in no spec's owned-file
   list, and one blocker note says its value projection throws on an ordinary
   `MapEntry`. The measurement protocol the whole plan rests on has no owner.
10. **`instrumented-vars-hide-their-constant-pool…` vs C1-2.**
    C1 brackets the wrapper's `apply` with two `nanoTime` reads, adding a
    second closure over the original. The note's finding is that the first
    closure already hides the original from runtime dependency inspection.
    C1 §6's "sample at `:776` inside `m/-instrument`" candidate would make it
    worse; the probe should record the inspection consequence, not only the ns.
11. **`a-blocking-realization-is-not-bounded-by-the-interrupt` vs AGENTS §2.3 and B2 §2b.**
    B2 states plainly that flow's `.get` does NOT cancel and that "the bounds
    that STOP work are SCI's `time-limit`/`:interrupt-fn`". This note's
    evidence is that the interrupt is a poll the admission walk can be
    blocked past. If it holds, B2's deadline story has no stopping bound
    underneath it for a blocking source, and the turn's `TimeoutException`
    leaves the transform running forever — exactly the hang AGENTS §2.3 calls
    worse than a failure.

## 6. What could not be decided (class E, 13)

| Note | What is missing |
|---|---|
| a-turn-that-dies-before-replying-still-answers-its-wakes | Its cited owner `src/seon/cluster/work.clj` is deleted; answeredness moved to `turn.clj` with an accepted-reply rule. Deciding it needs one live read of `seon.turn/latest-answering-turn-t` against an opened-but-unsettled turn, which this read-only audit may not take. |
| adoption-probe-emits-an-invalid-root-namespace-lookup | The note says outright that the caller was never identified. Nothing to match against a spec. |
| bootstrap-o4-stops-before-causal-delegation-settles | `eval/drive.clj` and `bootstrap_drive.clj` are in README §2b's unowned remainder; no spec states whether the drivers survive, are rewritten, or are deleted. |
| the-o1-bootstrap-drive-test-is-red-at-head | Same gap: a red in an unowned namespace whose future the plan does not state. |
| eval-samples-cost-42mb-of-store-each | Its own central figure is withdrawn in the note, and `src/seon/eval/*` has no owner. |
| context-wave-leaves-three-small-honesty-defects | Three unnamed small defects in `cluster.clj`, `fn.clj` and `render/ns.clj` — all three heavily rewritten. Deciding needs each defect read in its rewritten owner. |
| unlogged-findings-2026-08-01 | A bundle of five unrelated micro-findings spanning deleted and kept code; it cannot be one row. Split it or close it. |
| context-cookbook-trial-provider-has-no-credits | Provider account state, no code subject. |
| browser-ui-observation-has-no-accessible-window | Session tooling (CUA), no `src/` subject and no spec. |
| cua-browser-surface-is-unavailable-in-the-implementation-session | Same. |
| vendored-babashka-process-carries-a-local-aot-patch | README §8 keeps 20 submodules ("12 on the classpath, 8 read for design") but never lists them. Whether this one survives decides the note. |
| vendored-transit-clj-drifts-from-the-pinned-artifact | Same missing list; the note also names a live dependency with no checkout at all. |
| within-run-schema-key-refinement-needs-an-owner-ruling | It asks for an owner ruling that no spec makes. |

Two of the thirteen (`vendored-babashka-process…`,
`vendored-transit-clj…`) become decidable the moment README §8's surviving-20
submodule list is written; four more (`bootstrap-o4…`, `the-o1-…`,
`eval-samples…`, `eval-drives-duplicate…`, currently class C) become
decidable when someone owns the `src/` remainder row.
