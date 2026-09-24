---
type: living-list
status: current
updated: 2026-09-24
---

# Open items (living list — root rewrites it in place; ledger of file owners: tmp/orchestrator/file-ownership.md)

## Running lanes (ledger: tmp/orchestrator/file-ownership.md)

- sweep-hand-built-ns-rows: scripted conversion of 52 test files to test-support/namespace-row.
- review-test-harness-astra: review of one-test-harness/design-2026-09-24.md → astra-review-2026-09-24.md; then to the owner.
- fix-affected-callers-interpret (sci/eval.clj): a branch changing one :core function refuses its whole acquisition because
  interpreted callers cannot resolve privates, def Vars and dependency aliases (docs/seon/issues/affected-callers-fail-to-interpret-against-uninstalled-bindings.md). Blocking: most branch tests.
- fix-test-world-refusals (diagnose first): fixture writes refused with :seon.error/unknown for the definition digest
  (bootstrap-test; 63 of a 65-test save gate red), and test worlds missing the :seon.config.agent/turn-completion-backstop-ms dial.

Landed today and no longer blocking: start --head gitlinks, unbounded failure text (then nuke, data/ 136→5.3 GB), host-bound
rows refuse only when called (f2bc7088a). Tool smell: every seon.sci.branch-execution-test member is marked long, so
`bin/test-check --ns` runs 0 of them without --include-long.

## Integration (root, at the end of the fix round)

1. Default already runs the nuked store from the checkout; after the running lanes land, `bin/seon down && bin/seon start`.
2. Verify HEAD loads; run each changed namespace's tests (landing notes list them):
   seon.id-test, seon.problems-test, seon.cluster-test, seon.cluster.publication-delta-test, seon.cluster.mcp-test,
   seon.fn.analyzer-test, seon.sci.entry-gate-test, seon.sci.eval-test, seon.schema.admission-gate-test,
   seon.schema.agent-nil-contract-test, seon.db-test, seon.error-test, seon.turn-disposition-test, seon.cluster.prompt-test,
   seon.render.transcript-test, seon.render.web-debug-test, seon.cluster.process-test, seon.shell.jvm-test,
   seon.maintenance-test, seon.fn-test, seon.sci.reader-test, seon.dev.mcp-bridge-test, seon.test.* (after runner lanes).
3. Triage reds by the three questions (deleted machinery / retired assumption / surviving owner); report to owner.

## Queued correctness (file held; launch when released)

- error/commit-tx callers (sci/eval.clj:1889, cluster.clj:3432) still record capped faults with no blob — route through recording + evidence-stage; declare :seon.blob/staged-writes on :seon.error/recording (seon.error.edn).
- schema.clj assert-config-display! is an authoring rule still run at projection build (would break old as-of views if tightened): move to projection-with-schema + update test/seon/config_test.clj:62.
- test/seon/schema/datahike_test.clj unsupported-database-attributes-refuse-at-one-rule is stale (register! outside a registration delta now refuses) — retired assumption.
- Hook publications refused when several lanes publish at once (fix-history-keeps-its-rules item 4) — publication concurrency; with adoption exclusion (da9910fec) re-measure.
- Cold as-of projection 263–273 ms per time point, re-derived each new commit (seon.db keying) — performance.
- HIGH (one shared JVM): tests that redefine default's Vars (with-redefs / alter-var-root) break everyone — e.g. fn_test.clj:1022 redefines seon.fn.analyzer/analyze JVM-wide, refusing concurrent publications ("analysis must not run"). Census + rewrite through injected worlds (law "Protect execution ownership"); launch after sweep-hand-built-ns-rows releases its files. Live evidence 2026-09-24: default faulted with four replaced roots from web_debug_test.
- Publication half-applies on a red save gate: some changed files loaded, a schema not stored (leftovers-1 landing) — publication must be all-or-nothing / leave affected execution unavailable until convergence (cluster.clj owner, after fix-adoption-excludes-tests).
- A stored :or attribute drops its children's nil refusal (docs/seon/issues/a-stored-or-attribute-drops-its-childrens-nil-refusal.md) — schema/datahike.clj.
- Error evidence cut with no blob kept (root turn 46118 render fault on :seon.render.history/entries: reason unrecoverable) — keep-the-whole-cause violation in the error writer (error.clj).
- Root's idle prompt takes 5,079 ms (66 KB); profile top seon.schema/canonical-value-string 723k calls / 9 s — performance.
- test/seon/schema/datahike_parity.edn is stale by 322 changed forms, 4 missing and 28 unexpected attributes (a hand-maintained baseline — a smell): after fix-leftovers-1 lands, regenerate its current sections from the schema, or replace the baseline file with a derived check; decide which (landing lane-fix-leftovers-2-2026-09-24.md).
- "Isolation requires a fresh owned branch." (src/seon/cluster/agent.clj:848) stops seon.issue-settlement-test's first test early — diagnose.
- Publication refuses while :cluster-default-candidate holds a connection (issue publication-refuses-while-the-candidate-branch-holds-a-connection.md); test worlds inherit the live cluster's errors/messages (issue test-fixture-worlds-inherit-the-live-clusters-errors-and-messages.md).
- Subprocess descendants die of SIGPIPE after the root exits (docs/seon/issues/subprocess-descendants-die-of-sigpipe-after-the-root-exits.md) — needs an owner ruling on stream handling.
- test/runner.clj + test.clj: reach leaves omit fixture metadata (stale green); test diagnostics drop causes; `find-var` on a referred Var makes the live-cluster drift snapshot always empty (issue filed).
- refers slice (tmp/fix-entry-2-refers.patch): needs script/seon/dev/mcp.clj, test/seon/custody_stability_test.clj, src/seon/test/runner.clj.
- error.clj: "Resolved" shown after recurrence (error.clj:1726) — after errors-on-their-branch.
- schema/edn.clj or schema.clj: admit with identity and no projection falsely refuses :inst declarations; projection-with-schema vs activation disagree on [:maybe X] value schemas.
- schema/admission.clj: restore one core guard — core :any/:some must carry the exemption + reason annotation.
- Ambiguous-nil producers: census running → per-file fix lanes; then add the one-sentence rule to the data-oriented-clojure skill.
- Tests red at HEAD before today: seon.db-test (19, EDN codec fixture), seon.sci.reader-test (19, symbols-as-strings), mcp_test fake connection, bounded-boundary-census (34 script subjects), shell time-limit fixture, fn-test members over 5 s bound, resume-turn test 16.7 s.

## Testing framework (owner 2026-09-24: "we need to nail this")

1. Survey DONE (01c6571d6, d0d9dad21): one-test-harness/test-survey — harness 1,913 / platform 230 / delete 19; redef-census classifies the 351 sites.
2. Design DONE (623918f70, one-test-harness/design-2026-09-24.md; capabilities dropped: effects through config facts or owner arguments) — OWNER RULING 2026-09-24: the harness adds nothing the agent path lacks; a test is an agent attempt with a deftest body instead of model turns. Every lesson lands in the ONE agent path (retire stops the graph and awaits exit; attempts record base + program identity; capabilities as world values) and the harness only composes it; harness-only concerns are limited to running a body and recording test evidence — anything more is a smell. Design: ONE harness through the one interface — world = checkpoint fork + seed branch + capabilities as values; seon.test/run fork→inject→run→record→unlink; each member records the program it actually ran (root ruling on adoption-between-members); everything else is a platform test.
2b. Design input: agent graphs keep stepping after their test branch's store closes (docs/seon/issues/an-agent-graph-steps-after-its-test-branch-store-closes.md) — the harness's unlink must stop the member's graph and await its exit before releasing the store.
3. Astra review DONE (218aa33dc): revise before implementation, 9 findings, all accepted by root; open question ruled: base plus the branch's differing program rows. Revision RUNNING (revise-test-harness-astra, in place); probe-test-custody-escape verifies finding 1 against today's tests. Then owner go; then scripted conversion by class with deletions.

## Scheduled (owner: wait for fixes before large items)

- Committer-graph deletion (~470 lines): needs turn.clj:5359-5406, render/web.clj, cluster/wake.clj, cluster/agent.clj, cluster.clj, cluster/boot.clj (join-launcher-errors! now inert), schemas seon.flow/boot/cluster.wake/render.web (landing lane-fix-fault-route-2026-09-24.md; issue durable-faults-cross-dropping-channels.md).
- Launcher compute half: submit!! referenced by dead turn.clj:3290-3334, env_test.clj, instrument_test.clj.
- First-ever delivery gate still in error.clj:1393.
- hasch.core/edn-hash identity switch (prereqs in ledger).
- Ingestion slice: analyzer stores :seon/tasks, :seon/must-read, :seon/see-also, ^{:seon/task}, and :seon.test/seed as edges; contract schema keys as keyword edges; task EDN loader; dangling refusal (requirements 7.9, 7.9b, 5a.5).
- Checkpoint execution model (§4b with review corrections): base/cluster/task checkpoints, host Vars installed directly.
- Render pipeline 5 slices (clojure.main grammar, one printer, faults opener done, openers as data + help topics, retire transcript projection).
- Proof entrance: run a task's named deftests through seon.test/run on a throwaway fork (~130 ms target).
- One-happy-path waves 2–4 (dead code, dependency replacements, collapse second paths).
- `bin/seon faults --wait` (after errors-on-their-branch).
- Datahike fork gaps: the writer's next-write starting value must carry committed revisions (today temporary revision ids on the writer's in-memory value differ from the published value's commit-id revisions on 130 attributes at the same :max-tx, writer.cljc:246-253, db/transaction.cljc:1064-1070) — then land tmp/fix-unbounded-revisions-slice.patch (~25 lines; needs seon.test.run.edn) to make program-written-since? 0.2 ms instead of 0.1–0.7 s; total comparator, merge base, as-of revisions, avet seek cast, pull-find dependencies; Malli seed; SCI bind-root! on host Vars; clj-kondo cache/in-memory/rename; edamame ex-data.
- Projection passed explicitly (call-with-projection 79,689 calls; resume-turn 16.7 s) — includes SCI row 5 remainder: base contexts carry no environment; projection-state invents "current-src" for ~118 call sites (docs/seon/issues/sci-arming-guesses-the-cluster-from-a-database-row.md).
- Live DeepSeek loop test — only once the context is not garbage.
- Update the plan artifact (still shows the v0.2 task list).
- MEMORY.md is over its size limit: consolidate.

## Owner rulings 2026-09-24

- ab7e4a643: REVIEW NOW (Astra review launched).
- Entry gate: GATE ONLY, no :warn dial.
- exp-* branches: DISCARD at reset.
- Ingestion slice: AFTER THIS FIX ROUND (first language work after the reset + namespace tests).

## Queued (from config-read-2, 91c9079a9)

- `:seon.boot/cluster-name` supplier: one row in config/default.edn + small supplier in src/seon/env.clj, so (agent-history {}) works; transcript ledger's per-model calibrations still store refusals as calibrations.
- Tests passing only :seon.db/db to render/request-profile will now refuse (expected; count at integration).
