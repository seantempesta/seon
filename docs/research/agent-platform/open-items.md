---
type: living-list
status: current
updated: 2026-09-25
---

# Open items (living list — root rewrites it in place; ledger of file owners: tmp/orchestrator/file-ownership.md)

## Now (rewritten in place 2026-09-25 ~17:40Z; ledger: tmp/orchestrator/file-ownership.md)

### Resume here — read, don't assume
1. Read `AGENTS.md` end to end: today's laws are "The one REPL path", the per-face render order, "an error is an ordinary
   value", "Bound a code change by what it lets us cut" (design docs may grow when revised in place), and "Opus writes
   first-pass designs and implements; Astra reviews every design and diff and audits".
2. Read the living spec `docs/research/agent-platform/language-requirements-2026-09-24.md` end to end.
3. Read this section, the ACTIVE rows of the ledger, and the landing note of every running lane before touching its files.
4. **Before designing or assigning anything, find the existing owner** (owner: "The agent didn't know there was already a
   parser so they wrote another one"). `rg` the concept; check `reference-code/` (the clj-kondo fork is the ONE parser;
   malli, clerk, fipp, puget, orchard, nrepl are vendored); read `duplicate-paths-audit-2026-09-25.md` §11 (the only
   deletion-credit ledger) and `query-render-building-block-2026-09-25.md` (11 prior query+render generations). Never
   write a second parser, renderer, registry, runner or retry.
5. Verify at the REPL (MCP `runtime_status`, `eval_clj` jvm with `(seon.cluster.boot/connection "default")`) before
   trusting any doc claim; probes finite, never on new code in default (PROBE SAFETY in the lane preamble).

### The goal (big picture)
Seon agents — ours included — code through ONE REPL path (read → eval → record → print; MCP and the test harness are
entrants, printing only at the edge; everything before it is a Malli-typed value, fact or blob). The language (code
metadata + task EDN + schemas) co-locates every fact where grep finds it and indexes it once for REPL agents; every id is a
handle followable by grep and by one REPL call. Docs, plans and issues move into the codebase as tasks. Six ingredients,
made capable and reused everywhere: value+schema, fact store, function, evaluation, render, flow.

### The organizing principle (owner 2026-09-25): one happy path is the unified environment and harness
"It should be our unified env / harness whatever not just a single slice. Keep pushing on making this one happy path the
main view for everything in the system." Every entrant runs through read → eval → record → print on its branch, and every
workstream is judged by whether it moves an entrant onto that path or deletes a parallel one. Entrants and their state:

| Entrant | Today | Converges by |
|---|---|---|
| Agent turns (turn drive) | on `seon.sci.eval/evaluate`, records via turn settlement | M1 S1 (one definition authority) |
| MCP SCI mode | evaluates but records nothing (no program or evaluation rows) | M1 S2: submit as an owned agent |
| MCP JVM mode | separate read/eval, unrecorded | S2 multi-form reader; recorded as root's (ruled 8.6) |
| Test harness | own runner/recording; bodies partly outside the recorder | tests record through `turn/evaluation-facts` (design §2) |
| Operator prepl (`bin/seon`) | separate request path | entrant on the same path (unassigned) |
| Web runtime-eval | invalidation channel, not an evaluator (audit §1.7) | render consumer only |
| Rendering (MCP + browser) | many printers and windows | one pipeline: selection → floor → two faces |
| Program change | files edited by shell; publication batched | REPL `defn`/`ns-unmap!` on a branch → merge → write-back |
| Tasks, errors, config | partly Markdown/EDN, several writers | data on the path: one writer each, reverse lookups |

### Owner authorizations for this push (2026-09-25, owner away)
- Root approves slices over the line bound itself; when possible, schedule identification of obsolete code/docs to delete.
- Task migration: slice 2 (3 records), then batches of hand-checked tasks, each Astra-reviewed.
- Floor printer: Opus revises the design per Astra `6e9deea71`, Astra re-reviews, Opus implements if it passes.
- Derived contracts: S0 (shape codec on `[:map :k]`), S2 (arm from projection contracts), F1 (clj-kondo fork exports
  per-arity return tags; push to seantempesta/clj-kondo). S3+ waits for the sweep's measured deletions.
- Standing: no paid agent calls (default runs no-provider via `config/development.edn`); root parks dirty lane edits as
  patches itself for publishes and restarts (never waits); no PRs; push personal forks.

### Definition of done (owner 2026-09-25: "one step at a time… refined components we can keep building with")
A component is DONE only when it is REPL-proven on real data, green on published default, Astra diff-reviewed and
measured. No new implementation starts on top of anything that is not done; in-flight work narrows to what completes the
current component. Unverified commits are debt, not progress.

### Critical path, in order
1. **DONE 18:05Z:** the task-gate block is fixed (`f06358c74`..`153c9b584`): publication reconciles every task file, and
   adoption carries tasks into clusters as data. Default runs `6ab6ae2a`; `seon.issue.task-ingestion-test` 7/0.
2. **Verify:** read `tmp/orchestrator/verify-6ab6a945-results.txt` (50 focused commands, run b7sqnn06h); then run the
   queued lists in `tmp/orchestrator/verification-queue-2026-09-24.md` newest sections (render hand-off, turn, test rows,
   digest). **`seon.cluster.turn-test` first after publishing:** every turn opened `:error` before any model call until
   `3d0e68452` restored the database in the render argument (unpublished when written).
3. **Tasks:** run ingestion's tests (`seon.issue.task-ingestion-test`, `seon.fn-test`, `seon.issue-test`,
   `seon.cluster.source-test`) → slice 2 per `language-requirements-review-2026-09-25.md` "The first three slices" →
   slice 3 (`(task h)` and a function's open tasks) → batches from `tmp/task-inventory/draft-tasks.edn` (first batch of
   10 in `task-migration-inventory-2026-09-25.md`). Unwired: dangling check on program MERGE (`accept-merge!`).
4. **Floor:** re-review `98c563b34` says implement after fixes, naming the first sub-slice; start it after the current run is green.
4b. **Docs become queryable data** (owner 2026-09-25: "getting all the documentation integrated with the code and parsing it
   and having it all be queryable will make it more useful"), after task slices 2–3 are done: docstrings parsed by the one
   Markdown owner, their `[[wikilinks]]`/symbols/keywords as typed edges (one-REPL design C4); help derived from tool
   namespace docstrings (C3); long docs indexed by heading and links so a definition shows the design sections that govern
   it, and a doc citing a gone definition surfaces as stale (obsolete-doc identification rides here).
5. **Derived contracts:** S0 landed (`eb7e64ffa`); S2 and F1 wait for green. S2 needs a rule for the 43 metadata-only armed
   Vars, which item 5b resolves by making `script/` program.
5b. **Index `script/` as program, after cleanup** (owner: "you can index more directories … consider refactoring, merging";
   audit `script-dev-roots-audit-2026-09-25.md` `6fc7cde58`): (i) delete the changed-test/cache-helper family (663) and
   `schema_split.clj` (141); (ii) move `dev-cache`, `seon.operator.runtime`, `seon.operator.prepl` and harness-costs into
   `src/`; (iii) add `script/` as a program root (bb-loadable code stays bb-loadable); the MCP reader and render cuts (145)
   ride with S2. This unblocks the MCP task migration and arming the 43 Vars.
6. **The system REPL is the main input (owner 2026-09-25): keep improving it.** One-REPL milestone M0 → M1 (option B) → D1 per `one-repl-language-2026-09-25.md` (`42308d868`); write-back is
   constrained by 7.17 (one-file-one-owner; honest guarantee).
7. **Then** the function face (`52e252f74`; Astra `49b1122d4` implement after fixes) and the query+render block
   (`2ae3e003c`; Astra `ae7cfa793` implement after fixes), both on the floor's one pipeline.

### Running lanes (18:20Z) — read each landing note before touching its files
impl-repl-m1-opus (S1 one definition authority + qualified defn; then S2) · impl-task-slice2-opus (3 records) ·
impl-derived-prereqs-opus (S0 ONLY; S2 and F1 wait for green) · design-floor-revise-opus (then Astra re-review, then implement) ·
root verification run bhklahejy (`tmp/orchestrator/verify-6ab6ae2a.txt`, 65 commands, cluster.turn-test first).
REPL-first is law (AGENTS.md 303972bc7): prove on real data — parse, validate, store, pull back — before implementing.

### Component status (definition of done: REPL-proven, green on default, Astra-reviewed, measured)
| Component | State |
|---|---|
| Task ingestion + gate + adoption | green (46 assertions); Astra `5fb38b91f`: ingestion accept after fixes, publication/adoption revise (3 P1s) — lane fix-ingestion-p1s-opus |
| Task slice 2 (2 records) | landed `0a332e8fc`/`842e86f4f`; tests pending |
| Derived S0 (shape codec) | **DONE**: green 8/0, Astra accept `5fb38b91f` |
| REPL S1 (definition authority) | entry-gate 6/0 green; Astra `ef099dccf` REVISE (4 P0s: incomplete observed set, metadata via SCI seam, unique occurrence, restore over the real execution) — lane revising |
| Turns | a turn never completes (`cluster.turn-test` hang) — lane fix-turn-hang-opus |

### Designs (authorities; each revised in place)
| Design | Latest | Review verdict |
|---|---|---|
| Living spec | `language-requirements-2026-09-24.md` (task ids = `seon.id/id` over ordered `[ns name kind]`; one `:seon/tasks` key; 1.15 staged schemas) | reviewed `4aedb01b2`, shaken `6cfb6a789`, integrated |
| One REPL path | `one-repl-language-2026-09-25.md` `42308d868` | proceed after fixes (re-review) |
| Render selection §1–§2 | `one-render-path-design-2026-09-25.md` (value key → viewing agent's ns fn → schema pair → floor; contract inputs are defaults, caller wins) | installed; nested ladder open |
| Floor §3 | same file, shell order `0b83d61a5` | redesign before implementation `6e9deea71` — revising |
| Query+render block | `query-render-building-block-2026-09-25.md` `2ae3e003c` | implement after fixes |
| Function face | `function-face-and-canonical-authoring-2026-09-25.md` `52e252f74` | implement after fixes |
| Derived contracts | `derived-contracts-2026-09-25.md` `d88ce949f` (call-graph return inference) | implement after fixes `511b948c6` |
| Parser proof | `parser-proves-the-spec-2026-09-25.md` `76d5f06b3` | links exist in kondo analysis; producers dropped them |
| Duplicate paths | `duplicate-paths-audit-2026-09-25.md` `7c577876d` | 3,062 measured retirement candidates |

### Open defects, not yet assigned (assign when files free)
- `:my.edit/error` and `my.fs` error schemas require attributes their handlers never produce (make them the handler's union).
- ~50 `ai/complete` redefs in `test/seon/cluster/turn_test.clj` → the injected local provider seam (`146b8a5fb` pattern).
- `seon.fn/analyzed-form` merges digested attributes after the digest; `var-row` is a second digest producer.
- Call preparation cannot see optional inputs inside `:seon.render/unit` (patch in `lane-fix-render-handoff` landing).
- `:int`→`:string` schema change throws ClassCastException in `seon.db/transact-call`; `seon.effect/request!` writes the
  wrong branch inside a nested test run; SCI undeclared-attribute write returns no read error (`lane-fix-test-rows-e`).
- SECURITY-SHAPED: `shown-result` reads a key from the raw result before the floor classifies it (`sci/eval.clj:3274`), so a hostile `java.util.Map` runs its own code first (`RT.java:788`); the floor design moves the read after classification.
- `render_coverage_test.clj:49`: forms threaded into `comp` make assertions vacuous (`0f5f849bd` class).
- Census e BOUND/HANG/LOOP/BUDGET classes (`red-baseline-census-2026-09-25e.md` §3).
- 2,768 of 4,867 loaded function rows carry no contract; three producers of `:seon.fn/spec`.
- Operational: the clj-kondo dependency cache empties across restarts (refill `(seon.fn.analyzer/analyze-dependencies!)`);
  MCP eval echoes every stderr line; adding reference-code submodules forces "JVM RESTART NEEDED"; `init --changed` runs a
  full refresh; publication takes 25–47 s; the testing skill wrongly says `seon.test/run` does not arm contracts.

### Where evidence lives
Ledger `tmp/orchestrator/file-ownership.md` · run queue `tmp/orchestrator/verification-queue-2026-09-24.md` · runner
`tmp/orchestrator/run-verify2.sh LIST OUT` (full outputs in `OUT.d/`) · landing notes `docs/prds/agent-platform/landing/` ·
censuses `red-baseline-census-2026-09-25c/d/e.md` · lane preamble `tmp/orchestrator/fix-lane-preamble-2026-09-24.md`.

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
