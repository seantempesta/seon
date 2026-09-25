---
type: living-list
status: current
updated: 2026-09-24
---

# Open items (living list — root rewrites it in place; ledger of file owners: tmp/orchestrator/file-ownership.md)

## Now (rewritten 2026-09-24 ~22:10Z; ledger: tmp/orchestrator/file-ownership.md)

**RESOLVED (2026-09-25 ~03:30Z): call preparation dead on default** — stale dependent shape fingerprints. Fixed a0d2db39a
(a schema edit re-derives its dependents' shapes), 757bd7004 (a publication re-derives rows whose stored shape disagrees
with the projection), 7610ba9e9 (contract keys :seon.schema/key). After `(require 'seon.fn :reload)` + one publication,
default converged: seon.call-preparation-test 127/0 (was 80/21/13), supplier coherence 2/0. Not healed: 39 referenced
shapes whose own schema row is correct (finding them needs a hand list of shape-pointing attributes — not done).
Hub schema edit re-derives ~1,500 rows in 3 s, 2.4 s in seon.test.accretion/generatable? (unassigned).

**CHECKPOINT RUN 2026-09-25 (74 commands, 562 members): ~70 red; 28/74 commands green.** Stored counts (MCP): 301 failed,
26 unknown (was 365/18). Census lane census-checkpoint-reds → red-baseline-census-2026-09-25c.md. PUBLICATION ORDER
DEFECT seen: `init --changed` of S2b-b paths read render 0a's schema resources (new :seon.program/row-schema on
:seon.error.occurrence/id) from disk while the loaded program.cljc was pre-0a → panic "A program identity attribute
declares no source attribute". A --changed request ran full_source_refresh! (reads every schema resource). Fix: publish
0a's program.cljc with its resources once render-dispatch-opus commits 1a. Live faults: StackOverflowError in
cluster.agent-test/fixture-evaluate (x11); schema/call-with-projection max 115.9 s.

**RULINGS 2026-09-25 (owner):** floor slice 2 lands as ONE slice with the 100-line bound waived (conditions: §3
fixes folded in, ≤+20% time, compact elisions, Astra diff review). Harness S4c: attempt agent/turn only for members
whose reach includes effect/request*; a reach miss refuses loudly. **(root):** namespace-specific pages deleted (one
pair per entity schema); MCP JVM mode renders floor-only (no custody); error.clj bounded-text is STORAGE → canonical
EDN, not the floor; no-provider moves to config/development.edn applied by boot to default (census c option A);
overlay candidate attributes belong in its world. Census c (fe2f67953): 127 red + 17 never started; lanes
fix-config-boot, fix-test-shapes, fix-agent-test-redefs running. Kondo dependency cache refilled (434 files, 0 errors,
4.5 s). UGLY: MCP eval echoes every clj-kondo stderr line (~230 "already linted" events) unbounded — events bypass the
one render path; route events through the floor.
**Var-redefinition class (measured 2026-09-25):** 323 `with-redefs`/`alter-var-root` sites in 94 test files — a CLAUDE.md
law violation that leaks across members when a test never exits (StackOverflow x11 came from one). agent_test's
evaluator redef removed (2519a9ae0); its ai/complete, prompt, async/close!, next-agent-work, turn, next-entry redefs and
alter-var-root #'seon.turn/step remain. Owner: harness S5 sweep + S9 loopback/function provider (injection seams).

**OWNER RULING 2026-09-25: ONE render path** — a value is rendered by its schema's declared render function
(responsible: renders less), else by the floor printer, which holds the ONE budget and spreads it across the value's shape
(snippets of every branch of deep large values); nothing else clips. Clipping audit c6e75e57c (21 sites). Design
one-render-path-design-2026-09-25.md revised after Opus review 8a9e946d7 (67645d512); OWNER GO 2026-09-24 for all 11
slices (~725 net lines deleted). Running: render-dispatch-opus (slices 0a, 0b, 1) and render-floor-opus (slices 2, 3);
slices 4–11 follow in order, file-disjoint ones in parallel.

**RESUME (2026-09-25 ~02:30Z).** Tests 2,154; 365 failed + 18 unknown (current, after a full rerun). Running, code only:
fixture-agent-rows (73), fix-sci-zero-arg-and-names (23), fix-src-singles-db-schema, update-retired-test-shapes (52),
fix-mcp-value-window-budget. When they land: checkpoint `bin/seon init --dev default` (never restart without one first), run
each landing's commands serially (tmp/orchestrator/verification-queue-2026-09-24.md), rerun the 16 non-routing unknowns,
report the count. Then: harness S2b-b/c and S4, S5 scripted migration (~120), one-happy-path wave 3 in parallel (src 84,806
lines, +647 net since f2bc7088a). Ledger: tmp/orchestrator/file-ownership.md.

**Standing rules (owner 2026-09-24).** No paid agent calls: config/default.edn ships `:seon.config.ai/no-provider true`
and `:seon.config.error/escalate-to` absent (boot re-applies that file). Root reads errors via MCP first, logs second.
LOAD RULE: lanes write and commit code; root runs every test request and adoption serially. Hook publication is
PAUSED; root publishes at checkpoints with `bin/seon init --dev default --changed <paths>` (zsh: `${=paths}`), because
a restart loads code from the checkout but schema and test rows install only through publication.

**Adoption unblocked (~23:10Z):** d1ff2e02c (first-party = file under the checkout outside every :local/root; analyzed, kept
by the stale check) + 366355e2f (fill = `(seon.fn.analyzer/analyze-dependencies!)`, 5.9 s warm). Nested-run hang DIAGNOSED by thread dump: not a wait — reach-entries derives every test's
reach cold on each new branch (572af5fb9 keyed the index by connection); fix: requested symbols only, one content-keyed
cache shared across branches (lane locks-fs-and-runner-stamp).

**Owner rulings 2026-09-24 (evening):**
1. Publication: OPTION 3 — named lane branches → root-accepted merge → fail-closed convergence (b8fae5bf8); option 1
   (root-only checkpoints, hook paused) is the interim until it lands. Slice plan: lane plan-publication-option3-astra.
2. Full reanalysis over 10 s (16 s) AUTHORIZED until analysis is made cheap (issue a-full-reanalysis-publication-takes-twenty-five-seconds.md).
3. SCI evaluation vs adoption: owner — locks are a smell, reloads are rare (root checkpoints / accepted merges). Proposed: DETECT, don't prevent — compare the loaded program identity (S3c loaded-commit-id) at evaluation/member start and end; a mismatch reads unknown with a named cause; then delete the program-change lock (da9910fec, S3a). OWNER YES 2026-09-24: "just detect and push back if there's a conflict. We don't need read/write locking most of the time in clojure so definitely audit all places this is happening" → lanes detect-program-change + audit-locks.
4. Test-declared expected faults: DESIGNED 59b52dd2e — a fault is expected iff a PASSING assertion's :actual holds its receipt (:seon.fault/recorded, by declared schema; or a passing thrown?'s cause chain); any other fault on the member's branches is a test error. No list, no metadata. 3 slices (runner ~25, member-result ~30, one-line test edit). Blocked on: nested branches acquired from a member handle must record :seon.test.member/branch and copy faults to the member, not cluster-default — launch after harness S2a lands (it reshapes test worlds).
5. Inherited agents inside test members: RULED (owner) — they disappear: a member contains only the agents its seeds or body create (harness S2a builds this).

**Publication option 3 plan:** D1 revised (ffad218e2; §10 corrected to the detect ruling, b0f5a286d): 19 slices O0–O7,
each ≤100 lines, depending on harness S2/S3/S4. Running: O0 (delete hook auto-publication). Next: O1a after S2a.
**Lock audit (9437d0e01):** 38 sites — 17 keep (cross-process file locks, PrintStream/prepl monitors, Thread.interrupt
holder, Flow CAS, Datahike writer/konserve), 7 replace, program-change lock (detect-program-change), one dead family
(reserve-cluster!, cluster.clj:818–842). Worst: agent.clj:872 acquire-context! holds the contexts monitor across branch
creation + SCI acquire + transact! (serializes every turn start); routing monitor held through join-graph! with a hidden
lock order vs contexts. Running: fs/jvm.clj write lock + runner.clj reach-index stamp. Waiting on files: agent.clj pair
(after S2a names its files), reserve-cluster! + running-instances/held-flocks/branch-open-monitor (cluster.clj/boot.clj
held). Findings: two forked prepl writers (cluster.clj:553 vs resources/seon/operator/prepl.clj); maintenance.clj:518
opens a store outside the refcount (correctness risk); lock-file generator leaves files under tmp/.

**Harness (all approved):** S12, S0, S1, S3a–S3c landed; next S2/S2a (clean facts/branch) → S2b (seeds; three seed
forms, design §2b) → S4 (custody, effects) → S5 (scripted migration by namespace groups).
**One-happy-path:** wave 1 mostly done; wave 2 −885 (launcher compute half) with log-line/log-report, dead boot fns,
dead snapshot fields still owed; wave 3 F2/F5/F6 (projection wrappers, call-with-projection sweep, lazy registry) next.

**Verification (root, serial):** tmp/orchestrator/verification-queue-2026-09-24.md. TRUE BASELINE 2026-09-25 ~01:40Z: 365 failed,
18 unknown (all current). Census #3 (aab55ddd3), 14 classes: H1 hand-built rows 58 + agent rows without branch 40 + label-
branch helper agent-row (turn_test.clj:131) 33; BOUND 43; spies never reached 33; nested refusal fields 27; single-owner 27
(17 FIX-SRC); retired shapes 25; SCI regressions 23; clean-world data 22; render selection 15; problems compile 12 (rerun);
sibling worlds 4. The 18 unknowns: two routing-conservation tests whose threads never exit block their requests.
Lanes (code only): fixture-agent-rows, fix-sci-zero-arg-and-names, fix-src-singles-db-schema,
fix-src-singles-prompt-web-mcp-config, update-retired-test-shapes.

## New platform findings (2026-09-25)
- Small publication 14.6 s: 9.4 s was relinting all 179 callers of zero-argument functions whose arity rows never compared
  equal (empty :seon.fn.arity/arguments written, never stored) — FIXED 5d8309442 (root applied the lane's patch; regression
  green). Remaining >1 s: seon.db arity check rebuilds its whole-program input on a branch's first write (1.2 s,
  db.clj:3738); Datahike transaction ~1.8 s. Analyzer-digest reach KEPT (root ruling: seon.db/schema/program genuinely shape
  rows; narrowing risks stale rows, a marker is a maintained list) — a db.clj edit costs one full reanalysis until full
  analysis is cheap. Restart procedure: checkpoint (init --dev) before down/start.

- A namespace loaded after boot arming is NOT armed (4d849406e): boot arms only src/ namespaces (cluster.clj:2419, by design
  in ed62a3e06); a test request loads its namespace lazily via requiring-resolve inside the member body (test.clj:1364) and
  nothing arms it — loaded test namespaces 3 of 46 contracted Vars armed vs 1,908 of 1,952 elsewhere. Ruled: seon.test/run
  loads each selected test namespace before members run and arms exactly those through instrument/apply! (lane
  fix-late-load-arming). Also: script/ and dev/ namespaces are never armed (seon.operator 0 of 27) — not yet assigned.
- seon.test/failure-text (test.clj:39) prints a blob hint `(seon.blob/get digest)` that refuses when followed (get takes a
  connection and a digest) → queued for the test.clj owner.
- Every seon.db read on an as-of view rebuilds its projection (2,334 ms for 18 reads) — the cold as-of projection item.

## Recurring classes — root causes, not symptoms (owner 2026-09-24: "step back and find root causes")

1. **Unbounded printed values kill default** — FOURTH OOM 2026-09-24T23:27Z ("Requested array size exceeds VM limit",
   10.8 GB dump java_pid39156.hprof) during seon.test.overlay-world-test: a test error carried a whole program overlay as
   its offending value and seon.test.runner failure-identity/failure-report still pr-str whole expected/actual. Fixed so far:
   failure-message datom, runner `is` message (10d629c54), flow/store panic print (d8d80331d). Now: lane
   fix-runner-failure-print-bound (identity by digest, report through the bounded renderer) + S2 lane bounds its thrown error.
   Class rule: an error's inline data is a bounded summary; the whole value goes to the blob tier; every text sink uses
   the one bounded renderer.
2. **Custody inferred from a label, not the held connection** (agent.clj:205 → 43 "fresh owned branch" refusals; latent
   live-root escape through a shared context-state atom). Fixed b2fcb5225: the writer records the branch the transacting connection holds; an isolated acquisition gets its own context-state (a member's inherited root now refuses loudly instead of borrowing live root). Residue: 17 fixture sites hand-write the label branch (turn suites — after the subsumption review, survivors only); my/agent.clj mode now reads the acquisition's own entry (5a60f51d8). NEW: the MCP entrance acquires branches with a nil agent id, so (my.agent/branch ...) refuses on MCP branches — the entrance should acquire as a named agent. seon.env/supplied-agent-id puts the whole environment in :seon.error/offending (render 2.5 s; class 1). Design question for the owner: should inherited agents run inside a member at all?
3. **Hand-built rows in fixtures** (ns, fn and test rows missing required digests → most save-gate reds). The rows should
   come from canonical writers; Namespace rows DONE (4 commits, 53ae022b2: 31 digest reds → 0). Fn/test rows (59 sites, 45 carrying keys the analyzer does not derive) and agent rows without :seon.agent/branch (first failure in 22 tests; agent-tx also makes stewardship) are NOT hand-converted: they are harness S5 material (seeds through canonical writers) — owner rule, do not repair what the harness replaces.
4. **Every save runs a heavy save gate on the shared JVM** ROOT CAUSE FOUND (36af5c620, projection-call-cost): instrument keeps one compiled wrapper per function keyed on the exact projection object it last saw, so any fresh-but-equal projection recompiles wrappers (14–23× per call); carry-derived-projection builds a fresh 460 ms projection every publication; every seon.db read pays ~0.11 ms and published-index-rows reads rows one at a time (866k reads ≈ 25 s); canonical-value-string recurses through its own contract wrapper. Fixes: F1: caller converted 6a6db1862 (equal forms 3,381 / keys 5,565; 465 ms → 0.02–0.09 ms, only on 4 of ~36 publications); ROOT applies tmp/fix-f1-carry-derived-projection/delete-carry-derived-projection.patch with fn_test.clj:1966 → @connection once fix-f4 releases fn_test; then seon.schema/projection-delta has no src caller (delete); platform regression seon.cluster.source-test/publication-hands-its-commit-projection-unstamped to run at integration, F4 DONE 36abe8bef (9 reads instead of 901 for two files; full reanalysis 24.8 → 16.0 s, still over 10 s; publication reads the same rows up to 3× at fn.clj 2505/2912/2946; whole-program index rows overflow on the my.agents.root steward cycle — issue whole-program-index-rows-overflow-on-an-agent-steward-cycle.md); steward cycle FIXED f4c56ad12 (non-program refs written as their declared identity via :seon.program/partition; 12,460 rows in ~750 ms; a non-program entity with no unique identity would still inline as {} — should refuse), F2 delete per-call projection selection in instrument (~−100, needs a recompile counter first), F3 hasch (scheduled), F5 sweep call-with-projection (~−650), F6 lazy registry (~−1,260). F2/F5/F6 are one-happy-path wave 3 — owner go. (ROOT OBSERVATION: .claude/settings.json runs bin/seon-hook on EVERY Pre/PostToolUse event in every session, and :shell-writes compares every src/test file digest with that session's previous event — so any session's next tool call, even a Read, publishes OTHER sessions' edits; N sessions race to publish the same change — read-only lanes triggered 14.9 s publications; fed to the publication research) (plus: a lane that commits without publishing blocks every later publication that reloads its file; the hook never retries; one held dependent refuses an unrelated adoption — issue a-db-adoption-refuses-while-another-lane-edits-a-dependent.md) (65 reaching tests, ~100 s, 274% CPU; refresh-source! 107 s max;
   call-with-projection 34,393 calls / 362 s). With reds from class 3 each also prints class 1's giant errors. Cost follows
   the whole reach, not the changed inputs — the projection-passed-explicitly item and the harness's per-member cost target.
6. **A red baseline blocks every adoption.** The save gate correctly requires every reaching test green; fn.clj reaches 13 fail + 4 error that pre-date today (fixture refusals = class 3), so correct fixes publish but do not adopt (f4c56ad12). The gate is right; the red baseline is harness S5 work — the strongest argument for the harness go.
5. **Tests redefine default's Vars** (four replaced roots from web_debug_test). Harness S-slices + redef census.
- Seen in these lanes: SCI cannot interpret test macro seon.render.web-test/with-server (18 errors; same class as affected callers); a member branch inherits default's open faults (issue test-fixture-worlds-inherit-...); adoption does not load the test namespaces a --test request names ("no executable test Var" until loaded); a save gate spent 47 s of 59 s in adoption while lanes published concurrently (class 4).
Rule while fixing: code on the one-happy-path deletion list is deleted or routed to its surviving owner, never repaired.

- Tool: the operator cannot read PREPL values holding Vars (#'seon.test.runner/record-tx in retained tx-data) → every such hook result is "degraded". FIXED 1a3a9acbe + ce149b23d (clojure read-string, *read-eval* false, tagged-literal default; thrown values surface their own message; 8/8). Root reloaded seon.operator by hand: script/ namespaces loaded by the JVM are not published (issue script-namespaces-loaded-by-the-jvm-are-not-published.md). seon.adoption-diagnostic-test bound 1024 bytes vs 2596 (a thrown io-prepl :val carries its stack trace) — red before; decide bound vs kept trace.

- Delta branch deleted 104239f33 (−69: build-projection's projection+delta branch, compose-projection-data, reverse-dependencies). Its adoption waited ~6 min on other lanes' uncommitted edits, then 20.8 s (class 4).
- Schema rules DONE: projection-delta deleted (53a038419, −38); assert-config-display! at admission (e9f6dc805); :inst refusal and [:maybe X] were already fixed (ab7cc7190, a70688cc8) — the :inst regression never ran its path because the runner hands every test a projection (1cc45977a binds it nil). 9 pre-existing reds each in seon.schema-test and seon.config-test.

- Render pair selection FIXED 14a9291ad (an all-optional map declaring a render pair gets a shape row; census: 358 pairs, problems was the only unselected one). LIVE since d8951c477: every carried-projection key includes a builder digest (stored definition digests reached from seon.schema/load-projection, 244 symbols; 1.6 ms once per loaded commit; one 402 ms rebuild); problems now selects ai-prose. Follow-up: share one reach-digest function in seon.db for analyzer-digest (a whole-program query, 8.8 ms) and the builder digest (index walk, 1.6 ms).
- A new MCP branch's first evaluation lists 10 inherited errors from other agent branches (since 0) — branch-errors should start at the branch's fork basis. A tiny SCI eval allocated 667 MB (acquisition).

- SCI host names FIXED 4f880a823 (an interpreted caller also gets the loaded namespace's aliases): the 24 live class-2 reds were all `effect/request!` in my.edit — its alias comes from a top-level (require '[seon.effect :as effect]) outside the ns form, which namespace rows do not record. ROOT CAUSE of that require: my.edit/my.fs/my.shell must define predicates before seon.effect loads the schema population that resolves them (load-order coupling) — the one-happy-path "predicates in schemas → Malli fork m/eval" replacement removes it; do not patch the requires. 19 of the 24 are now correctly red on another lane's unadopted runner.clj edit. Stale test: branch_execution_test interpreted-callers-resolve-unchanged-names-to-compiled-vars expects seon.cluster/serve! interpreted (host-bound since 55af9e04b).

- Retired-shape tests: 16 reds cleared (041102a76, 3791dcfd2), 9 more committed unrun. Found: creation-tx still refuses :seon.agent/branch on fixture branches although branch-call answers (agent.clj:186-196; census class 3's 27 reds, my.plan-test 21); orphan ;;; Development Helpers banner at the end of schema.clj breaks the reader round-trip test; instrument-test sovereign-fork (base-ctx lacks the interpreted wrapper, :record still throws). Node-not-found has an existing CRITICAL issue: published-head-references-never-written-index-nodes.md (2026-09-23).
- Unstarted members DONE (2a543c27a deleted tests not counted; cb632bd81 unstarted members read unknown, the run records :seon.test.run/stopped with its cause, a stopped run no longer covers a later request). Default: failed 658 → 571, unknown 1 → 135. Open: a test's time bound covers only its body, not the request (111 s vs 60 s declared); a member stopped unfinished may never get its exit recorded (seen once in a draft); one-request-test red needs a parent comparison (queued).
- S3a DONE d77a9461a (call-with-program-change marks the program unknown before touching Vars; record inside the lock; a failed reload/arm/record leaves members refused with the cause; status shows program-unavailable). Open: refused members counted red not unknown (test.clj:184); evaluations/seeds not gated (S3b); the failure is status-only, not a delivered fault; the mark blocks every host member JVM-wide. Adoption took 7 attempts (6 refused by other lanes' edits; 136.8 s) — class 4.
- ns-usages span filter DONE a2bf4d8ed (requires/aliases/refers from every kondo usage by :from; only my.edit/fs/shell rows change; 4f880a823's caller-bindings deleted as redundant; imports/:rename keep the ns-form span — kondo gives them no :from).
- agent-eid refused read DONE 053a91b92. basis-t nil REFUTED as a release bug: it comes from with-redefs stand-ins of seon.cluster.prompt/prompt in test/seon/cluster/agent_test.clj (~837, ~899, ~1002) — redef class, harness S5; unexplained: capture-tx's input contract was not in force at those occurrences (re-arming gap during adoption?). Same refused-map-through-[:int] defect in seon.turn/latest-answering-turn-t.
- Fault delivery: 76e4e0ab6 (a fault with no steward and no attributed agent reaches root with or without an exception class) and addab5ae5 (an oversized fault keeps its cause chain: class, message, first-party frames). :seon.error/chain is blob-only by design. Test-branch faults not copied to cluster: premise wrong — :seon.test.run/branch is the result branch; members' agent-* branches carry no test marker; design (≈15 lines in seon.test member-result: record the member branch, copy? false in fault.clj:144, count its errors as test errors) → S3 lane follow-up. NOTE: 76e4e0ab6 sends MORE faults to root's paid DeepSeek turn once installed — OWNER RULED: record, no LLM wake (dial default off) — lane fault-no-llm-wake, lands before the restart.
- S1 DONE (aed1b3838 routing owned by the execution handle; d6e93ce72 release marks routing released, joins every graph, closes only after exit, keeps resources on a failed join; 10/0/0, and red without the fix). RESTART NEEDED (boot handle routing; two leaked probe graphs). Verification queue: tmp/orchestrator/verification-queue-2026-09-24.md. Class-1 residue: a failed assertion printing a routing entry OOMed — runner failure-identity/failure-report still pr-str whole expected/actual (runner.clj).

- ROOT LESSON (21:4xZ): with hook publication paused, a restart loads code from the checkout, but schema
  attributes and test rows install only through publication. S3c's :seon.test.run/loaded-commit-id made EVERY
  test request refuse ("Bad entity attribute") and new tests read "not eligible". Root publishes at each
  checkpoint with `bin/seon init --dev default --changed <paths>` (zsh: split the list with `${=paths}`);
  102 files adopted in 11 s. Start should publish or refuse when the checkout differs from the published
  program (schema resources especially), rather than run mismatched.
- Verification results: tmp/orchestrator/verification-queue-2026-09-24.md (8 of 17 focused regressions green; reds returned to their lanes).
- Datahike fused-root pin: fork commit fa0cc1a7 (+19/−6, regression datahike.test.fusion-root-pin-test);
  fork test RED (the test does not reproduce the unwritten-root precondition; count 50 vs 51) — back to the lane; no gitlink bump yet. force-branch! was never
  exposed (it writes fused roots); only commit! was.

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
- schema/admission.clj: restore one core guard — core :any/:some must carry the exemption + reason annotation.
- Ambiguous-nil producers: census running → per-file fix lanes; then add the one-sentence rule to the data-oriented-clojure skill.
- Tests red at HEAD before today: seon.db-test (19, EDN codec fixture), seon.sci.reader-test (19, symbols-as-strings), mcp_test fake connection, bounded-boundary-census (34 script subjects), shell time-limit fixture, fn-test members over 5 s bound, resume-turn test 16.7 s.

## Testing framework (owner 2026-09-24: "we need to nail this")

- Env-reading macro callers: fixed 55af9e04b (host-bound when using a JVM macro from a namespace SCI does not interpret, from the loaded Var's :macro; clj-kondo does not flag dependency macros). Over-approximates (timbre/warn also host-bound): no analysis fact separates &env readers. 0 acquisition refusals, 722 ms (was 1,052 ms with 5 refusals).
- Partial render profile: fixed at entry (c3c48bde2): request-profile refuses a partial carried profile and tags a carried config refusal; the log's "profile" was an effective-in refusal for cluster "flow-evaluation" (producer already removed by c1cd41b25). Stale test expectations: seon.render.call-test:28-29, seon.flow-test:948 (fixture has no config row for its cluster).
- Turn-suite subsumption DONE (c8621765a, read-only): 152 tests → 98 keep, 51 subsumed, 3 delete (−54 tests, −1,909 lines). Deletions wait for the harness go (with S12). Survivors to trim: retirement checks, timing-as-behaviour asserts, call-count spies; turn_test:1386 declares 60 s with no authorization; provider-progress test sleeps 2×10 s.

1. Survey DONE (01c6571d6, d0d9dad21): one-test-harness/test-survey — harness 1,913 / platform 230 / delete 19; redef-census classifies the 351 sites.
2. Design DONE (623918f70, one-test-harness/design-2026-09-24.md; capabilities dropped: effects through config facts or owner arguments) — OWNER RULING 2026-09-24: the harness adds nothing the agent path lacks; a test is an agent attempt with a deftest body instead of model turns. Every lesson lands in the ONE agent path (retire stops the graph and awaits exit; attempts record base + program identity; capabilities as world values) and the harness only composes it; harness-only concerns are limited to running a body and recording test evidence — anything more is a smell. Design: ONE harness through the one interface — world = checkpoint fork + seed branch + capabilities as values; seon.test/run fork→inject→run→record→unlink; each member records the program it actually ran (root ruling on adoption-between-members); everything else is a platform test.
2b. Design input: agent graphs keep stepping after their test branch's store closes (docs/seon/issues/an-agent-graph-steps-after-its-test-branch-store-closes.md) — the harness's unlink must stop the member's graph and await its exit before releasing the store.
3. Astra review DONE (218aa33dc): revise before implementation, 9 findings, all accepted by root; open question ruled: base plus the branch's differing program rows. Revision RUNNING (revise-test-harness-astra, in place); probe-test-custody-escape verifies finding 1 against today's tests. Then owner go; then scripted conversion by class with deletions.

## Scheduled (owner: wait for fixes before large items)

- Committer-graph deletion (~470 lines): needs turn.clj:5359-5406, render/web.clj, cluster/wake.clj, cluster/agent.clj, cluster.clj, cluster/boot.clj (join-launcher-errors! now inert), schemas seon.flow/boot/cluster.wake/render.web (landing lane-fix-fault-route-2026-09-24.md; issue durable-faults-cross-dropping-channels.md).
- Launcher compute half: submit!! referenced by dead turn.clj:3290-3334, env_test.clj, instrument_test.clj.
- First-ever delivery gate still in error.clj:1393.
- hasch.core/edn-hash identity switch (prereqs in ledger).
- Ingestion slice: analyzer stores :seon/tasks, :seon/must-read, :seon/see-also, ^{:seon/task}, and :seon.test/seed (entries: literal tx data, qualified seed symbol, or inline fn — owner 2026-09-24, harness design §2b) as edges; contract schema keys as keyword edges; task EDN loader; dangling refusal (requirements 7.9, 7.9b, 5a.5).
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
