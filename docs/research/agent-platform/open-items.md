---
type: living-list
status: current
updated: 2026-09-26
---

# Open items (living list — root rewrites it in place; ledger of file owners: tmp/orchestrator/file-ownership.md)

## Now (current state — rewritten in place 2026-09-26 ~02:00Z; never append; ledger: tmp/orchestrator/file-ownership.md)

### Resume here — read everything, don't tail
**Milestone (owner 2026-09-26): dogfood first.** One lane completes a real change entirely through the REPL path — MCP
branch (hosted agent, P3b) → `defn` → the tests reaching the change → root merge → write-back — with no file edits.
Everything else is subordinate until that works: green host-test, the write-back loop (merge gate), agent-test; then one
real task through it; then lanes work that way. Beside it, always at least one lane deleting obsolete code or converting
callers to an existing component/function/test that does the same thing better (not dogmatic: net-negative is the aim).
1. Read `AGENTS.md` end to end. Laws added 2026-09-25 that shape everything below: the one REPL path is a composition of
   stages (read → evaluate → record → print; other readers/evaluators plug in as values, never a parallel pipeline); the Seon
   agent is the perspective (MCP, tests, web are views into its world); core is composable (shared db values, a shared SCI
   base each agent forks into its private universe, one Flow graph per agent); errors are ordinary values; render selection
   order (value key → viewing agent's ns fn → schema pair → floor) with contract-declared inputs supplied as defaults;
   bound code by what it deletes; REPL-first on real data; Opus designs and implements, Astra reviews and audits.
2. Read this whole section, the ACTIVE ledger rows, and the landing note of every lane before touching its files.
3. Find the existing owner before designing: `rg` the concept, `reference-code/` (clj-kondo fork = the ONE analyzer;
   edamame = SCI's reader; malli, fipp, datahike, core.async, sci vendored), the duplicate-paths audit §11, the
   library-replacement audit, the lost-errors audit. Never write a second parser, renderer, registry, runner, walker or cache.
4. Verify at the REPL before trusting a doc (MCP `runtime_status`; `eval_clj` jvm with `(seon.cluster.boot/connection "default")`).

### Standing rulings (owner, 2026-09-25 — folded here, not a log)
- **Pillars, not parallel solutions:** every slice rgs for duplicates and deletes them in the same slice, listing deleted
  paths with line counts and net lines; ≥2 deletion/migration lanes run at all times; lean on libraries for the tricky
  parts — our code is the data transformation between their seams; hunt whole functions a library call replaces.
- **Context = a composed graph render of the agent entity.** The agent names its task by a task handle
  (`:seon.agent/task`); anything attached by reference renders. One walker (set-at-a-time index joins per level, bounded by
  N, default 3, caller-overridable) → each node through its own schema render pair (presence of a pair decides inclusion; no
  mode keywords) → one composer (topological: definitions before uses; turns by tx; each node once, later refs back-point;
  renders cached by content digest + face). Renderers MAY read the stable db value they are handed. Root gets a standing
  root task. `:seon.render/units` is to be deleted (the walk replaces it). The debug page shows it: `/agent/{id}/debug?n=0..3`.
- **AI renders are REPL entries, evaluated; HTML renders are hiccup** (owner 2026-09-26, refining the same-day "text"
  ruling). An AI render returns `;;` comments and forms; the one REPL path evaluates each form read-only in the viewing
  agent's SCI world (disposable fork, cached by content digest) and prints form + result — the context is a `clojure.main`
  transcript the agent can rerun (language requirements 5c.3/5c.5). Curated namespaces attach to the agent/task and render as
  `(require '[my.program :as program])` then `dir`/`doc` with contracts, so the wrapper API is explained, never magic. The
  floor printer lays out results; its provenance budget applies to the printed transcript. Dogfooding requires this API to
  be working and intuitive: every valid SCI form at the REPL, not only `defn`.
  Rendering evaluates through the SAME one REPL path (reader, `seon.sci.eval/evaluate`, shown-result, run owners) — no
  second parse, eval, printer, cache or store; a missing capability is a stage value supplied to that pipeline (owner).
- **Store references, never copies:** errors/facts carry a db value as commit id/basis, an SCI ctx as agent/branch identity,
  a request as its evaluation/turn ref, a large value as one blob digest. Opaque world objects are typed identities at the
  admission edge, never walked.
- **References are decided at the one producer** (`var-row` and siblings from clj-kondo analysis). Program references become
  validated refs with named schema keys where the design proves it (`references-as-refs-design`); `:seon.db/reference-to`
  names targets; docstring links come from a markdown library's parse tree (no regex); error members stay symbol values;
  a render function's output is known by its attachment key (`:seon.render/ai`/`/html`) or its declared output schema. The word "face" is being eliminated from code, schemas and docs (owner 2026-09-26): delete every face concept that re-says the key, contract or call site; rename any genuinely needed one to a grounded term (audit-face-concept). Every top-level def is a declaration (P2-A).
- **Faults:** core faults deliver to root by default AND surface as errors in the cluster's MCP replies.
- **Tests:** slow = poorly designed. A test member's world is a branch of a prepared commit (ms), never a file store, full
  canonical population, whole-program rebuild or nested full run. One way to write a test for us and for agents (deftest via
  the REPL path or a file, run by the one `seon.test/run`), one setup/fixture system callable from SCI and files alike — no
  duplicate harness. Runs report new/fixed/still-red from stored evidence and run only against committed program states.
- **Paid calls:** default stays no-provider; a stray cheap call is not an emergency (fix silent fallbacks as correctness).
- **Process owner:** a timeout freezes the descendant tree (SIGSTOP walk to a fixpoint) then kills it; survivors named by (pid, start-instant).
- Standing: no PRs; push personal forks (sci, clj-kondo, datahike); root parks dirty lane edits itself; nuke/reset freely (db data is disposable).

### Definition of done
A component is DONE only when REPL-proven on real data, green on published default, Astra diff-reviewed, measured, and its
duplicates deleted. Unverified commits are debt. Two lessons from 2026-09-25: a scratch-namespace REPL copy runs UNARMED (validate
real values against the declared contract before committing); a new attribute must install before its first write on an
EXISTING store (prove adoption on a branch of the live store, never only on a fresh one).

### Platform state (2026-09-26 ~04:00Z)
- Default: pid 76020, booted from the checkout at 8fa430cc6 on the existing store (all layers, ready 21 s — over the 10 s
  start target; owed a profile row), no-provider true. Health "faulted" from 8 faults recorded before this boot (14c6292e,
  f6d16919 and two `seon.db/transact!` signatures lead); none new.
- P2-A (every top-level def a declaration) reverted (8fa430cc6, 39dae4235): boot's indexing tx referenced a declaration first
  written in the same tx by lookup ref (`[:seon.fn/sym seon.cluster/*boot-progress!*]`). Re-land must use tempids at the one
  producer and prove boot on a branch of the live store first.
- Boot reads the working tree: a lane's half-edited file blocks boot ("Static program analysis found blocking errors").
  Root parks uncommitted lane edits to `tmp/orchestrator/parked/<stamp>/` before a restart; lanes keep files analyzable.
- Boot from zero works: boot fills a missing clj-kondo dependency analysis (71f14d1d4); call reach without functions 58e341cf3.
  Open: `boot/stop!` marks running-instances with a bare Object that projection readers refuse (issue filed).
- Adoption blockers seen, each fixed: `[:= {}]` read as properties (db51da2e7); reload refuses a retargeted alias (issue
  filed); deleted paths in `--changed` (pass existing paths only); schema retirement (A/B two-step); attribute before schema.
- Hook publication no longer publishes (`bin/seon-hook:12`).

### Component status
| Component | State |
|---|---|
| REPL S1 (one definition authority) | green: entry-gate 77/0, restoration 24/0, reader 331/0, reply 124/0; Astra revision accepted after S1 revise-2 |
| Custody class + merge gate | green: merge 16/0, source 23/0, agent-custody 15/0, one-request 89/0; Astra ACCEPT-AFTER-FIXES folded |
| World-reference admission (22.8 GB → 2.36 ms/1.09 MB) | green: sci.admit 80/0, error-result long 11/0; error-test's 4 reds pre-existing |
| Shape mirror → Malli (−784 src, −611 test) | call-preparation 130/0; fn/program/schema/instrument reds classified pre-existing or environmental |
| Candidate/parent isolation | proven: two forks of one base keep their own roots (8/0), parent untouched (6/0); fork 4–8 ms |
| Graph walk + debug page (S1, T0 −650) | walk-graph 17/0, debug-page test 24/0; page readable (screenshots tmp/impl-debug-page-ui/) |
| Status one face (R4 −88) / turn result fact (R5) | boot status 5/0, drive 11/0; mcp-test/turn-test/program-test reds with the deletion lane |
| Read-sink / read evidence | read-evidence 47/0, form-scoped sink green; `(inc 1)` records 0 reads |
| Config sparse apply (installs attribute first) | re-landed 278b22317; config 219/2 (2 pre-existing), no-provider 21/1 (turn-owner red) |
| Seams S-scripts / S-process | shell.jvm 18/0, subprocess 16/0, dependency-cache 10/0 (after reloading the digest ns) |
| Turns | local-provider turn 5/0 in bound; batched turn 3 fails (deletion lane); episode-limit loud fault green |
| Concurrency | long suites not yet run; concurrency-test "Agent creation refused" unassigned |

### Running lanes (read their landing notes)
| Lane | Doing | Files |
|---|---|---|
| design-fast-harness-opus | harness S1 acquire-once landed (1985849f4); Astra review, then next slices | harness files in its landing |
| design-one-pipeline-opus | P1 print-once landed (19fbb7901); committing P2a (reply splitter keeps `#?`, regex, `#inst` as code); P2 ruled: keep two passes, documented | reply.clj, turn_test, design doc |
| design-lost-error-seams-opus | P0/P2 landed; escalate-to root default (178a67487); converting three tests; then P1 production: fault port + stop from a separate thread (Flow stop deadlock) | flow.clj, seon.flow.edn, flow/fault/armed/error tests |
| design-references-as-refs-opus | Astra re-review of the reference design | design doc |
| impl-index-point-free-defs-opus | P2-A re-land with tempid-safe references, proven on the live store | fn.clj indexing, entry-gate test |
| impl-floor-fipp-opus | floor printer on fipp (one clipper; giant values as blob refs) | print.cljc, seon.print.edn, render/value.clj |
| audit-face-concept (Astra) | eliminate "face" from code/schemas/docs; grounded rename where a concept is needed | face-concept-audit-2026-09-26.md |

### Designs (authorities; each revised in place)
| Design | Verdict |
|---|---|
| `task-context-install-design-2026-09-25.md` (graph render; slices S1 done, T0 done, B/C next) | Astra ACCEPT-AFTER-FIXES; B (neighborhood onto `graph`) needs review before it changes live openings |
| `render-graph-research-2026-09-25.md` | measured: 0.8 ms seek walk, 97% cache hits, one SCI fork safe for parallel render (4.7×) |
| `references-as-refs-design-2026-09-25.md` | REVISE → round 2 folded; re-review next |
| `one-repl-language-2026-09-25.md` rev 5 (one composable pipeline) | ACCEPT-AFTER-FIXES; implementing |
| `one-reader-collapse-2026-09-25.md` | ACCEPT-AFTER-FIXES; implementing option A |
| `lost-error-seams-design-2026-09-25.md` (audit: 106 instances, 7 classes) | ACCEPT-AFTER-FIXES; implementing |
| `fast-harness-design-2026-09-25.md` | in design |
| `library-replacement-audit-2026-09-25.md` | 12 candidates, 2 landed (−20); rest MED/HIGH or held files |
| `language-requirements-2026-09-24.md` (living spec, 125 rows: 31 met, 20 proposed, 15 open) | the parser already sees every link (parser-proves-the-spec); producers must emit them |
| floor §3, function face, query+render block, derived contracts | older; floor/face/block now converge on the graph render — re-read before starting |

### Queued (root's ordered backlog, rewritten 2026-09-26 ~17:40Z; lanes take the top ready item on free files)
**A. Dogfood milestone (primary).**
1. Green the path: host-test, mcp-bridge (operator discovery via archive root → seams), write-back (merge gate "named run
   not recorded on the tested branch"), agent-test's 8 pre-existing reds, custody-stability (stale, fixture lane).
2. Agent API blockers (fix-dogfood-api lane): `my.test/check`, `my.test/run`, REPL `deftest` reach, in-run ungated
   redefinition, merge proposal as a thin my.* wrapper over the existing merge owner.
3. First real change end to end through MCP (branch → forms → tests → root merge → write-back, file bytes equal, reindex).
4. Then convert lanes to REPL-first work (one lane first, preamble updated), keeping the standing dogfood lane cycling;
   its ranked report (dogfood-report-2026-09-26.md) feeds fix lanes.
**B. Context agents can understand.**
5. REPL-entry renders: Astra review → owner ruling on §6 history (recommend A: never recorded) → slice 1 (+60/−80) →
   agent/task/namespace renders; retire text AI pairs and the transcript renderer's parallel path (~640 lines).
6. Curated namespaces via `:seon.ns/aliases`; shrink `dir` (10–35 KB) and the own-agent pull (78 KB); Var prints `#'ns/v`.
7. Task-focused context: the agent's context is its task graph render (N levels); verify on the debug page.
8. Printer/render defects: render-coverage (50 fails, `unknown-output`), help as map, `admit-chunk` nil, raw pull inlining
   (D11), P3m (MCP errors through the one path).
**C. Correctness.**
9. `seon.schema` reload leak (`converged-predicate-var` reloads + swallows) — eval-schema lane.
10. Call-preparation snapshot staleness (D7): commit-identity memo, hook within 20%.
11. `preview-sources` commits writes (D6); reload-overlap detection absent (a376a6985).
12. Agent code reaches every held context of its execution; adoption leaves retired graphs running; two loaded-identity
    answers; generator http-kit server; unowned `seon.db/q` refusal in loop-proof (now printed on next run).
13. Operator: failed-move headline + exit code; a focused route for long members (merge-test cannot run today).
14. Storage GC reclaims nothing (store +1–2 GB/10 min under tests).
**D. Performance.**
15. Commit floor: Datahike/Konserve fork change so the branch head points at the durable commit record (one barrier).
16. Adoption 11–27 s (`refresh-source!`); boot 28–39 s vs the 10 s target; hosted MCP run 165 ms warm.
17. Test design: turn-test ~100 s, agent-test/web-test 120 s, loop-proof 39 s → fast-harness next slices (in-memory
    branches of one prepared commit); `program-fn-row` one-arg manifest (~15 s/call).
18. seon.profile window misattribution (per-thread windows or JFR).
**E. Shrink (≥1 lane always).**
19. Atom bundles in order: R8 wake, R11 MCP, R12 provider, R6 runner, R7 sequential IO, R3 rendering, R1 SCI context,
    R5 lifecycle, R13 fault idempotence at the writer.
20. C5 projection-as-a-read sweep (72+ sites) and read-refusal residue (~33 sites) as files are released; C2 after test
    confinement; dead window-* schema declarations (two-step retirement).
21. Face-concept elimination from the Astra audit (face-concept-audit-2026-09-26.md) as a scripted rename.
22. Largest files: cluster.clj (~3.7k), sci/eval.clj, schema.clj, turn.clj — shrink assignments by responsibility.
**F. Review debt (Astra).** P3b diff; 2(i) diff; R1 diff; roster slice; references-as-refs re-review status; harness S1.

### Operating checklist
- After `bin/seon start --head`, read its WHOLE result and then `bin/seon status` `:seon.operator/source-root`: a move
  whose JVM misses readiness or adoption falls back to the previous archive and still prints a pid and ready-ms
  (operator.clj:1319-1352). 2026-09-26 root filtered the output and ran three test passes against a stale archive.
- Publish/adopt: `bin/seon init --dev default --changed <existing paths only>`; park dirty lane files first; after a fork or
  dependency bump run `(seon.fn.analyzer/analyze-dependencies!)` (or the shell form) before restart.
- Tests: root runs serially with `tmp/orchestrator/run-verify2.sh LIST OUT`; never in a publication window; `--include-long`
  for long members (skipped long members show `passed? false`).
- Probes: bounded loops only (default Vars are direct-linked; republishing never stops a running loop); scratch copies are unarmed.

### Where evidence lives
Ledger `tmp/orchestrator/file-ownership.md` · verify outputs `tmp/orchestrator/verify-*.out(.d)` · red routing
`tmp/orchestrator/red-routing-2330.md` · landing notes `docs/prds/agent-platform/landing/` · issues `docs/seon/issues/` ·
lane preamble `tmp/orchestrator/fix-lane-preamble-2026-09-24.md` · parked patches `tmp/orchestrator/parked/`.

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
