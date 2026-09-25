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
- **Root rulings 01:45Z (owner delegated "do everything you think you should"):** process race = freeze (SIGSTOP walk to fixpoint) then kill; references-as-refs Q1 `:seon.db/reference-to`, Q2 docstring links via a markdown library parse tree (no regex), Q3 error members stay symbol values, Q4 `:seon.render/face` declared fact; one-pipeline Q3 = (c).
- **Owner 01:10Z: "slow means poorly designed since we know we can spin up envs and execute them faster."** Test cost is a design defect: a member's world must be a branch of a prepared commit (ms), never a file store, full canonical population, whole-program rebuild or nested full run. Harness redesign launched (design-fast-harness-opus → Astra).
- **Store nuked 22:05Z (owner):** old store 124 GB, ≥8.76 GB world copies (projection 6.44 GB, printed SCI ctx 2.32 GB). Boot from zero was broken twice: nuke deleted the kondo dependency cache with no in-boot refill (issue filed; root refilled from the shell, 41 s) and call reach read `:seon.fn/sym` on a store without it (fixed 58e341cf3). Fresh default pid 39484, ready 33 s, no-provider true, store 3.5 GB. Shape mirror → Malli re-landed as A e7b9e8791 + B e6066f091 (no migration on the fresh store).
- **Owner rulings 23:00Z:** (1) renderers MAY read the database — they are handed a stable db value (contract-declared input, supplied as a default); root's "renderers make no database reads" was wrong and is withdrawn. (2) Errors and facts REFERENCE the world, never copy it: db value → commit id/basis, SCI ctx → agent/branch identity, request → evaluation/turn ref, stored values → blob digest. (3) Default went down twice today on unadoptable commits (schema retirement 0bd3a38b4; config attribute written before its schema 54c550e4c) — both reverted (e00a3ccda, 81f9b1b3e) and re-landing with adoption proven on a branch of the live store first.
- **Owner direction 21:25Z — context is a composed graph render:** the agent's context is the rendering of the agent entity; anything attached by reference appears. One walker (declared query, bounded) → per-node pure render pairs → one composer; reference-following rules (:content/:address/:skip) declared on attribute schemas; topological order (definitions before uses; turns by tx); each node rendered once (later refs = addresses); node renders cached by content digest + face. This is the query+render building block; task-context design round 3 revises to it.
- **Owner rulings 20:50Z:** core faults deliver to root by default AND surface as errors on the cluster's MCP interactions, so agents see them; C4 shape mirror replaced by Malli (option B); R7 = fix the one reader's errors first, then delete the bridge reader; task context = simplest: the agent names its task, and the agent's context is a render function over agent → task → N hops.
- **Owner law 20:10Z (AGENTS.md ba41af0c0):** the Seon agent is the perspective; MCP, tests and web are views into its world (no second model/evaluator/printer/store). Plus: find whole functions a library call replaces (Astra library-replacement audit running); keep ≥2 deletion/migration lanes running (bridge R7/R3/R6, closure walkers, C4 shape mirror).
- **Verified on default 6ab6cd5e (20:05Z):** entry-gate 66/0, restoration 18/0, reader 292/0, reply 124/0, agent-custody 9/0, merge 16/0, source 23/0, one-request 12/0. New reds routed: registry 12 errors (custody lane), render.ns 52 fails + subprocess 4 (deletion lane), read-evidence 4 (db cost lane). Pre-existing: db-test ~30, effect 2–3. Start 50.9 s / adoption 32 s (boot lane).
- **Owner rulings 19:45Z:** audit fixes = design per owner seam → Astra review → file-disjoint implementation lanes that delete their class's per-site copies; boot to ≤10 s now (profiling lane); db witness change accepted AND read-evidence refresh must draw from the task-focused context view (spec §5a), not the namespace — install task-focused context as THE generator first (design → review); test worlds inject values (dependency analysis into analyze-forms; the runner's recording writer) instead of redefinitions.
- **Every slice collapses duplicates (owner 2026-09-25, "focus on the pillars… not writing parallel solutions").** Before designing, rg for every other place computing the same thing and check the duplicate-paths audit §11; route callers through the one owner and delete the copies in the same slice; the landing names each deleted path with line counts and net lines. Today's slices all grew net (S1 +257/−106, hold +72/−31, custody +32/−14, tff +89/−65) — that stops. Each lane launch carries this rule; every Astra review checks it.
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

### Running lanes (18:55Z) — read each landing note before touching its files
fix-turn-hang-opus round 4 (batched-turn hang: settle! parked on the writer while a hot `declared-sources` neighborhood render
runs; local-provider turn 6.05 s > 5 s; dumps `tmp/turn-hang-1243/`). Idle, results routed: impl-repl-m1-opus (S1 done
pending eval-test triage), fix-ingestion-p1s-opus (custody class `c1e40b7e8`), fix-test-failure-facts-opus (`72578e81a`).
Root run `tmp/orchestrator/verify-1852.out` (merge, source, test-failure-facts, eval, evaluate-sources) on 6ab6c29c.
REPL-first is law (AGENTS.md 303972bc7): prove on real data — parse, validate, store, pull back — before implementing.

### Component status (definition of done: REPL-proven, green on default, Astra-reviewed, measured)
| Component | State |
|---|---|
| Task ingestion + gate + adoption | ingestion-test green 67/0 on 6ab6bf57; merge gate custody class fixed `c1e40b7e8` (prepare, gate test run, acquisition refusals under the acquired branch's custody); green on 6ab6c29c: merge-test 16/0 (35 s, long tier), source-test 21/0 (3 platform-only members await root's platform tier); Astra review running (review-custody-ingestion) |
| Task slice 2 (2 records) | landed `0a332e8fc`/`842e86f4f`; tests pending |
| Derived S0 (shape codec) | **DONE**: green 8/0, Astra accept `5fb38b91f` |
| REPL S1 (definition authority) | P0-A..D + P1-A..C landed (`bbbdbd5bd` `51dda24e9` `a9c74ad46` `6dfe778e4`); green on default: entry-gate 66/0, restoration 16/0, reader 292/0, reply 124/0; armed cost P1-D: contracted defn 16.3 ms (parent 22.4), `(+ 1 2)` 1.5 ms; eval-test 4 reds new vs pre-S1 (1 was root's helper, fixed `8e731ac68`) — none S1-caused (red at 08:34, before S1); Astra re-review REVISE `e19adf392` (3 P0 + 3 P1: provenance on root writes, unbound-Var restoration, restoration captured before mutation, restoration error delivered, metadata CoW vs newer Var, nested reader positions) — lane revising |
| Test harness hold | overdue member's late exit records the exit only (`1197a35ac`); regressions green 12/0 + 3/0 |
| Turns | local-provider turn settles (5/0) but 6.05 s > 5 s bound; batched turn hangs (lane round 4) |

### Queued slices (root-ruled, waiting on a held file)
- R5 grader half: settlement writes optional `:seon.turn/result` beside the disposition; `drive/read-result` (13) goes — needs turn.clj after fix-turn-hang round 6.
- R4 status: one render pair for the runtime observation; `bin/seon status` and MCP print through it; −88/+40 — needs script/seon/operator.clj, after delete-mcp-bridge R7.
- fn/reaches? → `db/call-reach` (−16) and runner.clj:839-849 closure → `db/call-reach` — fn.clj after impl-shape-mirror-malli; runner.clj free.
- Library audit D6 (instrument.clj), D7 fn half — after impl-shape-mirror-malli.
- Agent HTML face refuses "did not return an observation" after 417 ms on the fresh store (task-graph S1 landing item 11); renderer owner.
- Graph walk: the node-count cut never fires because query work is capped by the same `max-nodes` (S1 landing item 10) — separate query-work cap is a config decision (owner).
- Task-graph S1 4f18ab5db over budget (+206/0): accepted on condition that T0 (retire neighborhood + declared-sources walks onto `graph`) lands next.
- registry-test runs out of time (62.6 s for 11 members; two stay pending): each member builds a file store and writes every canonical attribute (~2 s). Shrink assignment: one populated store per namespace, members branch from it (custody lane, 87cd929d7 landing).
- `seon.test/resolve-test` (test.clj:1462) uses plain `requiring-resolve`, so a test namespace registered as loaded but absent from the JVM cannot run ("no executable test Var"); route through `seon.sci.eval/host-namespace!` (eval.clj:1925), which already reloads (UI lane).
- web-debug-test foreign reds (UI landing): `repl/render-ai` returns the raw evaluation map (render dispatch; d315faa6f had fixed it); fault card renders nil kicker/message (error pair); fixture writes refused (`unowned-entity`; issue "only the creator may remove tests"); `turn-details` 34 s + ledger bytes mismatch + `error/recording` missing declared-schema.
- accretion `source-harvest-keeps-refusal-render-contracts-coherent` 10.7–17.5 s vs 5 s: rebuilds the whole program's rows per run (cost owner).
- Process owner race (S-process landing): a descendant forked during termination escapes and the refusal reads `reaped? true` — OWNER DECISION: kill -STOP freeze / process groups / declared outside the guarantee.
- Publication gap: `script/` namespaces loaded by path (`dev_cache.clj:220-221` loads `script/seon/dev/dependency_digest.clj` only when absent) are never reloaded by adoption, so committed script changes never reach the running JVM (S-scripts landing). Fold into the `script/` indexing/move-into-src plan.
- accretion `a-gate-test-runs-with-its-authors-cluster-custody` 6.3 s vs 5 s (newly measurable; candidate evaluation + gate test, unprofiled).
- seon.test-test reds (UI landing fc410f18f): `admission-refuses-stale-program…` fixture writes a bare doc onto a stored row (use `support/revision`); `recording-distinguishes-run-replay…` `with-redefs` on `db/pull` (banned; inject the refused read as a value); `recording-preserves-admission-and-refuses-a-deleted-definition`: `runner/record-tx` inside `:db.fn/call` throws and `db/transact!` rethrows as outcome-unknown where the test expects a returned refusal — runner/db contract question.
- Datahike parity baseline (`test/seon/schema/datahike_parity.edn`) is weeks stale (516 forms differ, ~53 extra / 32 missing attributes): regenerate inside a test world with a per-attribute explanation, as its own assignment — never as a side effect of another lane.
- `seon.ai/complete` ignores `:seon.ai/no-provider` (issue provider-completion-ignores-the-no-provider-request-attribute.md; ai.clj owner).
- Concurrency: long-tier suites (independence 137 s pool, streams, publication-concurrency) run with --include-long at a quiet window; concurrency-test "Agent creation refused" error unassigned.

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
- QUESTION (data integrity): upserting a re-analyzed function row onto a stored declaration keeps the OLD `:seon.fn/doc`, `:seon.fn/calls`, `:seon.fn/arities`, `:seon.fn/form-span`, `:seon.fn/file` the new definition lacks (REPL 18:55Z on seon.id/valid?); the writer digests that merged value. Verify every production redefinition path retracts absent owned attributes; a stale call edge or doc is a false fact.
- SLOW START: `bin/seon start` ready in 11.1 s at 18:50Z (pid 23282) against the ten-second start law; 4.8 s at 18:43Z — find what grew (dependency-classes cache miss both times).
- SCI FORK: sci.core-test has 4 failures at e663d9e2 AND 409888f6 (pre-existing): `(meta (fn []))` returns `:sci.impl/root-data` (function-results-dont-have-metadata, core_test.cljc:1335-1336) and two error-location message mismatches (:559, :567). Likely from fcbd8862 (fact-safe root installation) — our fork leaks internal metadata onto fns. Evidence tmp/sci-core-409888f6.txt, tmp/sci-core-e663d9e2.txt.
- PRODUCER (likely root of read-evidence cost): the read sink bound at `turn.clj:4753` wraps the whole install, so the platform's own reads count as a form's dependencies — `(quote 1)` carries 7,286 reads (fix-cost-db landing). Bound the sink to the form's evaluation only; the digest/scan fixes (901bcaa5b, d46bbae77) then shrink further.
- COST: `schema.clj:853` `canonical-value-string` recurses through its armed wrapper (539,778 calls for one digest, ~120 ms/form).
- DUPLICATE: the forward call-closure walk exists four times — `effect/capabilities` (now a pull-many walk, 1b9aafa96), `render.clj:783-803` render-program-evidence, `db.clj:1413-1427` builder-digest, `fn.clj:2288-2302` reaches? — collapse to one walker at its owner (fix-cost-capabilities landing).
- UGLY: `(seon.fn.analyzer/analyze-dependencies!)` prints ~230 "already linted, skipping" stderr lines per call; report a count.
- SLOW START again: 18.6 s ready at 19:17Z (pid 32831), after a dependency-analysis refresh; start law is 10 s.
- PROBE SAFETY: default Vars are direct-linked (probe stack shows invokeStatic), so republishing a Var does NOT stop a running probe loop; an unbounded probe (capabilities over every function) grew default to 11.8 GB and needed a restart (19:17Z).
- UGLY/UNBOUNDED: `bin/seon status` printed 1.1 MB after a restart, one full error map per never-started test in `:seon.problems/failed-tests-unknown` (18:43Z); problems must render through the one render path with counts, not raw maps.
- PRE-S1 RED: evaluate-sources-test `ordered-evaluation-retains-one-explicit-basis-without-publication` :204 expects `:seon.turn/recorded-content-conflict` (red since 09-24, rerun-results-2026-09-25.txt:49); owner = turn settlement, unassigned.
- HANG SOURCE: `seon.error/prepare-result` renders a contract error's offending value as full HTML with no bound (stuck deep in `seon.render.hiccup/hiccup?` on a huge value) — violates the one render path; it must go through the floor (lane-fix-turn-hang landing).
- SECURITY-SHAPED: `shown-result` reads a key from the raw result before the floor classifies it (`sci/eval.clj:3274`), so a hostile `java.util.Map` runs its own code first (`RT.java:788`); the floor design moves the read after classification.
- `render_coverage_test.clj:49`: forms threaded into `comp` make assertions vacuous (`0f5f849bd` class).
- Census e BOUND/HANG/LOOP/BUDGET classes (`red-baseline-census-2026-09-25e.md` §3).
- 2,768 of 4,867 loaded function rows carry no contract; three producers of `:seon.fn/spec`.
- Operational: the clj-kondo dependency cache empties across restarts (refill `(seon.fn.analyzer/analyze-dependencies!)`);
  MCP eval echoes every stderr line; adding reference-code submodules forces "JVM RESTART NEEDED"; `init --changed` runs a
  full refresh; publication takes 25–47 s; the testing skill wrongly says `seon.test/run` does not arm contracts.

### Operating checklist (learned 2026-09-25)
- Publication reads the working tree: park dirty lane files first (`tmp/orchestrator/parked/<name>-<file>.patch`, one per
  file), publish, then tell each owner to `git apply` its own patch.
- A dependency or fork bump (SCI, clj-kondo, datahike gitlinks) needs `(seon.fn.analyzer/analyze-dependencies!)` BEFORE
  `bin/seon down && bin/seon start`, or startup refuses "clj-kondo holds no analysis of …".
- An overdue test member blocks every later body until a JVM replacement: avoid running known-hanging tests (turn tests)
  until their fix lands; restart with `down`/`start` (never reset) to clear it.

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
