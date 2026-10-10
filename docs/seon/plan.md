---
title: The plan
type: plan
status: active
created: 2026-10-10
---

# The plan

One plan, revised in place. Written by lane fable-docs on 2026-10-10 from AGENTS.md (fb98f1752), the 2026-10-09
plan and its two audits, the 2026-10-10 research (measurement, one-system, astra-one-system, designed-task-examples,
generative-testing, astra-malli-behavior, fable-simplify) and the cards BEHAVIOR-SPEC, COST-REG and PLATFORM-STEP.
Live facts were checked on default (archive ffe62e1c3, pid 20363, `:faulted`) in JVM mode, read-only. It supersedes
`docs/research/agent-platform/2026-10-09/plan.md`.

## 0. Where we are (revised in place; last 2026-10-10)

**Landed and confirmed live on default:**
- FEEDBACK-LOOP stages 1–13 and 16 (card `schedule/cards/FEEDBACK-LOOP.md`). Every reply attributes its cost to
  entrance, step, writer and commit, plus a labelled shared residual. A warm redefinition reads about 358 MB with
  97.5% attributed; this morning it read 28 MB.
- Each reply carries a compact diff to main; the agent's block shows its branch cost; slow paths are one query over
  `:seon.fn.cost/*` entities.
- The merge base is recorded by `t`, advanced at each merge, inherited by nested forks and keyed by fork id
  (d9eb15c05, aa7ffb17f; Astra-reviewed).
- Write-back refuses a relative or archive root (93d9e4ef6).
- The Datahike fork's per-batch commit cost (fork cf815730; bump 5acc29e23).

- Byte-bounded node cache (fork cb3bce51, 75bd6a23a, 12473ca94; lane astra-node-cache), measured live:
  - A warm redefinition's step went from about 309 MB / 171 ms CPU to 217–232 MB / 155–180 ms; the JVM window
    from 358 to 263–283 MB.
  - A new agent's first request went from 2.37 GB / 830 ms custody to 0.38 GB / 319 ms.
- Owner ruling, "if it's unchanged it's unchanged": the cost diff compares only definitions changed on the branch
  (a digest different from main's, or new) and the tests reaching them. An unchanged definition is never an entry.
  Regressions in unchanged code surface in health and at merge. Landed fde5f1e11, confirmed live: a redefinition's diff shows only its own changed definitions.

**Running:**
- REPL-only acceptance (`repl-only-3`):
  - Ordinary half PASSED end to end through the REPL with branch evidence (df87cba96, names-in 1,490 → 128
    distance computations).
  - Host-bound half NOT yet passed: 31b442840 (write-back helper placement) fell back to `--test`, because branch
    evidence called `proven-file` unreached, and its subject isn't flagged host-bound. It still gates new
    platform machinery.
  - 13 frictions were filed (c9040c2d5).
  - Cost finding: a `defn` reaching about 13 tests costs 3.4–3.9 s and 4.3–5.5 GB in the test step.

**Next, in order:**
1. A second chain run with a DeepSeek-flash implementer (`forgive` idempotence, designed-task-examples A).
2. Backlog triage: prove the top ~30 tasks; the rest go to an unproven backlog.
3. FEEDBACK-LOOP stages 14, 15 and 17.
4. Check `seon.db/pull-logical`, which reads about 3× main on agent branches.

**Course corrections (root's reflection, 2026-10-10):**
- Every lane names the essential it serves (§2).
- The REPL-only acceptance is the gate for new platform machinery. The feedback loop was the owner-sanctioned
  exception, and that exception is now closed.
- Most of the day built the instrument. From now on the instrument is used: a model agent working a designed task
  is the next real test of the goal.
- At most two or three implementation lanes at a time, on disjoint files, because collisions cost hours today.
- Lanes delete with plain `rm`, never `git rm`, and leave nothing staged.
- A whole-namespace `--test` is refused when the namespace has declared-long tests; name the vars instead.
- A dependency bump blocks every `bin/lane commit` until default restarts; root lands it last in a batch.
- Codebase growth is acceptable for now (owner); Seon agents trim it later from the stored cost data.

## 1. The goal

Software that grows by accretion: open data under named schemas, stable names, every call checked and measured, built
by many agents each in its own branch and SCI world. Work is tasks that chain (define → research → specify → prove →
implement), each step an agent whose context Seon generates. A core of about 10,000 lines where ordinary work is
sub-second.

## 2. The essentials

Five things matter now; everything else waits on them.

1. **An honest number in every reply.** A redefinition reports 28 MB and costs 2.1 GB and 2.4–3.2 CPU-seconds
   (measurement.md, one-system.md, reproduced by astra-one-system). We cannot judge any change, budget or fix until the
   reply tells the truth. This is the first designed task (§4).
2. **One task worked end to end by hand, through the REPL.** Both audits said the same thing: run the acceptance before
   building more. The chain (§3) is run by Claude lanes playing each role, on default's REPL, for the honest-measurement
   task. Every friction met becomes the next task. Nothing else platform-shaped lands first.
3. **Red proofs before any assignment.** 44 of 604 tasks had a tagged proof (astra audit); 13 of 15 fan-out fixes
   needed root rescue; the fan-out 9 bug passed its one reaching test. A task is assignable when it has an `are` table
   holding the reported input (and, for cost tasks, one `is` on a count), red on default. Ten such tasks, not 590.
4. **Default runs HEAD.** Most of wave A's machinery (file rebase, published-archive snapshots, "live after restart",
   templates per slot) exists because default lags HEAD (opus review §3.1). Restart onto HEAD after each landing batch,
   then delete the workarounds.
5. **Correctness and efficiency over size, for now** (owner, 2026-10-10). Temporary growth of the codebase is
   acceptable; Seon agents trim it later, using the cost data the feedback loop now stores. Every change is judged
   by whether it is correct and by its measured cost.

## 3. The method: the chain, run by hand

A task moves through five agents, each with its own context, each handing the next a value in the database:

| Stage | Produces | Lane for the first run | Later |
|---|---|---|---|
| 1 Define | `:seon.task/intent` in plain language, must-reads, the reported input | owner + root | owner, root |
| 2 Research | what exists to reuse or change: producers and consumers of each key (`:seon.fn.arity/input-refs`, `output-refs`), the dependency seam, the simplest alternative | Claude (Opus) | Claude |
| 3 Specify | Malli schemas in `resources/seon/schemas/<ns>.edn` and the function contracts, by name, on both ends | Claude (Sonnet) | Claude |
| 4 Prove | deftests in the subject's test namespace, tagged `:seon/tasks`, red: an `are` table with the reported input, one `is` on a count for a cost task, a seeded property only where a one-line law exists | Claude (Sonnet) | Claude |
| 5 Implement | bodies until the proofs are green; REPL-first on a branch, `my.program/write-branch!`, `bin/lane commit --branch-evidence` (host-bound: `--test`) | Claude (Opus, platform) | DeepSeek-flash (ordinary), Opus (platform) |
| Review and merge | dual review (fresh Opus, Astra), root merges | root | root |

Rules of the run: each stage's output is checked at the REPL before the next starts (stage 3 compiles its schemas
against real values; stage 4 confirms red on default); a stage that finds the design wrong stops and reports with
options; every friction (a tool missing, a read over a second, a refusal with no fix text) is filed as a task with the
exact form and reply. The first run is a measurement of the chain itself: how many requests each stage took and where
the context was missing. That measurement, not a card, decides what gets built next.

## 4. The first task: honest measurement

**Task** `seon.flow/a-reply-accounts-for-the-work-it-caused`. Must-read: `seon.flow/var-process`,
`seon.flow/graph-executor`, `seon.turn/step`, `seon.batch/request-cost`, `seon.batch/jvm-window`, `seon.db/transact!`,
`seon.test/measured-body`, `seon.profile/measure`. Evidence: `docs/research/agent-platform/2026-10-10/measurement.md`,
`one-system.md`, `astra-one-system.md` (stage 2 is done; the three lanes agree on the mechanism).

**Intent.** A reply's `:seon.cost/request` covers the work the request caused: the agent's turn step on a platform
thread (exact counters), the test bodies it ran (already folded by `seon.test/measured-body`), the writer's processing
of each of its transactions, and its share of each commit; what remains (GC, JIT, other agents) is shown once as a
labelled JVM residual. On a quiet JVM the attributed total is within 10% of the JVM window for a redefinition. The
reply is delivered after accounting, never before.

**Data model** (reuse first). Existing: `:seon.cost/request`, `:seon.cost/ms`, `:seon.cost/calls`, `:seon.cost/reads`,
`:seon.cost/transactions`, `:seon.cost/stages`, `:seon.cost/jvm-window` (landed cdce8a37e, live after restart),
`:seon.profile/alloc-bytes`, `:seon.profile/calls`. New names, each with one producer and one consumer:
`:seon.cost/step` (the proc thread's allocated bytes and CPU ns for the step; producer `seon.flow/var-process`, consumer
the reply render), `:seon.cost/writer` (sum of `:datahike/processing` over the request's tx reports; producer
`seon.db/transact!`), `:seon.cost/commit` (the commit cost on each finalized report, with `:seon.cost/batch-size` and
`:seon.cost/basis :datoms`; producer the Datahike fork's commit loop, modelled on upstream `listen-commits!`),
`:seon.cost/shared` (JVM window minus attributed parts, plus GC CPU and JIT ms; never apportioned). The scope labels
`:evaluation-arms-only` and `:unavailable` for step and writer work disappear.

**Interface.**
- `seon.flow/graph-executor`: `Thread/ofPlatform`, daemon, named by graph and proc; still thread-per-task (no pool, no
  starvation; about 3 parked threads per agent, 32–90 KB RSS each; no wall difference measured, one-system Q4).
- `seon.flow/var-process` 4-arity: brackets the step with current-thread counters, folds the world sink, assoc's the
  cost into each `::replies` value and offers it; the step returns replies as data. The three `async/offer!` sites in
  `seon.turn/step` go.
- `seon.cluster.store` writer config gains `:write-fn-map` wrapping `datahike.writing/transact!` and `merge-writer!` with
  counters; `seon.db/transact!` reads `:datahike/processing` off the report (proven on a disposable store, one-system Q5).
- Fork (upstreamable, ~30 lines): the commit loop runs `datahike.writing/commit!` synchronously on one
  `clojure.core.async/thread` and attaches the commit cost to every report in the batch.
- `seon.sci.kernel/future-call` adds its body thread's delta on exit.

**Proofs** (red before the bodies; all tagged with the task):
- `seon.request-cost-test/a-redefinitions-reply-accounts-for-the-window`: through the real MCP entrance on a disposable
  agent (the form of `measurement-probe.clj`), `(:seon.cost/step …)` plus writer and commit shares are at least 90% of
  the outer `getTotalThreadAllocatedBytes` window; today 28 MB against 2,118 MB.
- `seon.flow-test/a-proc-thread-is-a-platform-thread`: `(not (.isVirtual (Thread/currentThread)))` inside a step.
- `seon.db-test/a-transaction-report-carries-its-processing-cost`: one `transact!` on a disposable branch answers
  `:seon.cost/writer` with positive bytes.
- `seon.request-cost-test/the-reply-is-offered-after-accounting`: the reply value already carries `:seon.cost/step`
  when the port receives it.
- Budget (one `is`): the accounting adds at most two counter pairs per step (0.42 µs each) and zero transactions;
  asserted as `:seon.profile/calls` unchanged and `:seon.cost/transactions` unchanged on a trivial form.

**Lanes.** Stage 3 and 4: one Sonnet lane (schemas, red tests; ~2 h). Stage 5: one Opus lane (one-path and boot-critical;
`bin/lane commit --test` naming the four proofs plus `seon.request-cost-test`, `seon.test-cost-test`,
`seon.eval-alloc-test`; ~1 day including the fork change). Root restarts default onto HEAD once, then the probe is
repeated live. Better by: the redefinition reply reads about 2 GB, not 28 MB, and names the step as its owner.

**What the honest number will show first** (so the next task is already visible): the step's allocation on a cold
redefinition is dominated by Datahike index-node restoration under `:test-step` (about 2.1 GB, 1.8 GB with no Seon
frame in 64 stack frames). That is §5 step 4, not part of this task.

## 5. Next steps, in order

Each is small, names its proof and what it makes measurably better.

1. **Honest measurement** (§4), then restart default onto HEAD. Better by: reply cost 28 MB → ~2 GB on the same form.
2. **Latest performance numbers, stored cheaply, and a compact diff to main in every reply** (owner, 2026-10-10).
   One row per function, test and schema validator holds its latest numbers: calls, CPU, allocation and ms, as a
   rolling average updated in place. These attributes are declared without history and upserted only when a number
   moves meaningfully, inside the transaction the request already writes. Slow paths are one query. Main's rows are
   the same rows read on main, so the diff to main is a join. Each reply and the agent's context show the cost of the
   request plus a compact diff to main for every function, test and schema it touched, always on. Interpreted
   numbers on a branch are shown as guidance; the compiled check at merge flags real regressions for root.
   Schema validation cost is measured per schema, so expensive checks become visible (a database value is checked
   as a database value, never by walking it). This reshapes `seon.profile.measurement` and COST-REG slice 1 into
   that one store rather than deleting them. It deletes the per-request upsert of about 300 measurement rows, the
   7 unused `seon.config.test.cost` dials, `seon.health/definition-comparisons` and the hand-read `ThreadMXBean`
   test helpers. Better by: "which functions are slow" is one query, and an agent sees its cost delta on every change.
3. **Second chain run, non-platform**: `seon.sci.forgive/forgiving-forgiven-text-changes-nothing`
   (designed-task-examples A: three defects, red `are` table and seeded idempotence property already written as
   prototypes). Stages 3–4 Sonnet, stage 5 DeepSeek-flash as implementer with a Claude lane watching. Better by: one
   model agent takes a designed task red → green with no root rescue, or the exact friction is filed.
4. **The 2.1 GB test step** (Astra, performance): name the callers with `-XX:FlightRecorderOptions:stackdepth=256`, then
   fix the owner (`seon.cluster.store/the-node-cache-counts-nodes-not-bytes` is the likely producer: node cache bounded
   by bytes, so nodes are not reloaded per request). Proof: a redefinition's `:seon.cost/step` at a budget derived from
   the work (one fork, one evaluation, one transaction, the reached tests), written by the designer after step 1 shows
   the real number. Better by: define-and-prove under one second.
5. **Default runs HEAD per landing batch**, then delete what the lag required: the write-back file rebase's snapshot of
   the published archive in `my.test/check-platform`, template-per-slot (store identity read from the store record, not
   `nameUUIDFromBytes(path)`), "live after restart" proofs. Better by: `bin/seon check` under 10 s warm (37 s now).
6. **Ten tasks with red `are` proofs** (Sonnet, ~2 h): root picks ten open tasks with small owners and a known bad
   input; one `are` deftest each, tagged, red on default. Only these ten are assignable. Better by: tasks with a red
   proof 26 → 36.
7. **A10: a visited test namespace makes every later request 3× slower** (opus review 2.5; 141 ms test step for `(+ 1 2)`).
   Fix at the producer (entered Vars counted as the agent's changes) with a `seon.profile/measure` row on the second
   request. Better by: a trivial form stays at ~70 ms after any `in-ns`.
8. **Task entity and docs in the database** (§8 Q1–Q2), after the two chain runs show what the agents actually lacked.

## 6. Deliberately deferred

- Child tasks, task graphs, planning and fan-out (`my.plan`, `seon.plan` are in git at cea904e92 for later).
- COST-REG slices 2–3 (baselines at assignment, 32 runs, factor/floor reds, `:seon.task/cost-accepted`): interpreted
  bodies cost 280× their compiled self (G4), so every correct agent body is red against main; counts in proofs (§3
  stage 4) and the honest window replace it.
- Growth tests (n…8n exponents), guards, guard admission fixes, the structural schema index, the Malli `mg/check` seed
  change: 9% + 1% of defects, three blockers, confusing to the owner. Properties stay optional where a one-line law exists.
- Auto-generated checks on `defn`, per-function meters and real-input capture in the armed wrapper: not needed by the
  first task; each is machinery with no friction yet behind it. Revisit after chain run two.
- A storage meter beyond the tx report's datom count (index files and bytes per commit, G3): wanted, not yet needed.
- Embeddings and semantic search (§8 Q4): after lexical search over indexed docs has been used and found wanting.
- The Datahike fork rebase onto upstream (165 vs 171 commits; `listen-commits!`, metrics, binding conveyance): a root
  batch after step 1 lands, modelled on upstream's event shape so the rebase is a deletion.
- A stateful lifecycle test suite (23% of defects): its own owner and research card, not per task.
- A Flow-hosted Datahike writer: refused (a second writer, loses group commit, cljc).
- The positive-wording pass over AGENTS.md's remaining negatives: root, in small commits, never a lane's task.

## 7. Decisions made

- 2026-10-10 Seon grows by accretion; the four properties and the design order in AGENTS.md are the laws.
- 2026-10-10 Work is a chain of five separate agents; run by hand first; the first task is honest measurement; nail a
  single task before child tasks.
- 2026-10-10 Roles: Claude designs; DeepSeek-flash implements non-platform; Opus implements platform; root reviews and merges.
- 2026-10-10 Datahike's writer stays as it is (go-loops, group commit); Seon does not batch; `seon.batch` is a misleading
  name for the eval-request fold (rename belongs to its owner).
- 2026-10-10 Flow procs run on platform threads, one per task; accounting closes at `seon.flow/var-process`; the reply
  is published after accounting; writer cost per transaction via `:write-fn-map`; node cache bounded by bytes.
- 2026-10-10 Tasks are entities upserted as the chain refines them, with history; author little, derive the rest.
- 2026-10-10 Task names are fully namespaced symbols; docs live at the symbol level, linked in the database.
- 2026-10-10 The feedback loop (honest step cost, cost entities, live diff to main, schema cost) is designed in
  [FEEDBACK-LOOP](schedule/cards/FEEDBACK-LOOP.md) as 17 stages of about 15 minutes each; it replaces §4 and §5
  steps 1–2 as the work order. The loop works crudely after stages 1, 2, 4 and 5.
- 2026-10-10 Stored data is described in Datahike's terms: entities, attributes, values, datoms (AGENTS.md vocabulary).
  Cost is a cost entity per function, identified by its symbol, with values replaced in place and no history.
- 2026-10-10 Numbers are live: each reply shows the request's own measured values against main's stored values (which
  change only when merges land). A threshold of 1.41× only gates when a branch rewrites its stored value.
- 2026-10-10 Schema cost is measured once per schema digest at contract-writing time (generate samples, validate,
  record), flagged in that reply when over budget, and re-measured only when the schema changes.
- 2026-10-10 Land the Datahike fork's commit-cost change now (FEEDBACK-LOOP stage 11).
- 2026-10-10 Merge bases are found by the fork point's `t` and `as-of`, never by walking commits the collection may
  have removed (task `seon.cluster.source/merge-base-survives-collection`). There is no live data to migrate:
  default is nuked and rebuilt after the fix.
- 2026-10-10 Focus on correctness and efficiency; temporary codebase growth is acceptable, and Seon agents trim the
  codebase later. Next, in order: the node-cache cut (efficiency, measured by the loop), the REPL-only acceptance
  rerun, a second chain run with a DeepSeek-flash implementer, then backlog triage. FEEDBACK-LOOP stages 14, 15
  and 17 wait. At most two or three implementation lanes at once.
- 2026-10-09 Freeze new platform machinery until the REPL-only acceptance passes (one non-host-bound and one host-bound fix).
- 2026-10-09 Auto-restart default onto HEAD per landing batch; then delete the lag workarounds.
- 2026-10-09 Proof gates scheduling; the rest is an unproven backlog. Stay on deepseek-flash. Commits 1–3 lines; docs
  only for evidence that would otherwise be lost.
- 2026-10-09 Budgets with four meters (calls, CPU, allocation, storage) estimated by the designer; the agent pushes back
  with data; root decides; completion is automatic when proofs are green.
- 2026-10-10 Doc sections are entities identified by path#anchor, with parsed references as facts; files are write-back.
- 2026-10-10 The database is the source of truth: Seon persists to disk and can start from disk, and the conversions
  are lossless, proven by a round-trip test (tasks, code, schemas, docs).
- 2026-10-10 Search: the fork's Lucene secondary index (`:scriptum`) now; EmbeddingGemma vectors (`:proximum`) only
  after a chain run shows a lexical miss.
- 2026-10-10 Latest performance numbers are stored per function, test and schema validator, as rolling values
  updated in place with no history. Every reply carries a compact cost diff to main, always on; live feedback is
  automated wherever it is cheap.
- 2026-10-10 Flow procs move to platform threads (tens of KB each, a few per agent). Moving Datahike's writer into
  Flow is deferred; it is correct as it is.
- 2026-10-08 A question task declares its answer's Malli schema; the answer is ordinary Datahike data.

## 8. Open questions for the owner

Each with options and a recommendation; findings are stated as facts checked today.

**Q1. Where do cross-cutting docs live, so agents grepping code see them?** *Decided 2026-10-10: (a), doc sections as entities.*
Facts: docstrings are already database facts with full history (`:seon.fn/doc`, `:seon.ns/doc`; 4,234 functions have
doc history; `seon.sci.arm/arm!`'s docstring has 6 versions since 2026-09-28). `seon.ref` already parses every Markdown
text once (commonmark, cached by digest) into headings, code spans and links, resolves them, and reports unresolved
ones as problems; 524 task entries reference `.md` paths. The web view has no Markdown renderer yet (nextjournal/markdown
is in deps.edn, unused). Namespace-docstring expansion failed before because prose in a `ns` form has no identity of its
own, cannot be referenced by section and is re-indexed with the namespace.
Options: (a) **doc sections as entities**: each Markdown heading section is an entity identified by its path and anchor
(the identity `seon.ref` already derives), with its text (inline or blob), its parsed references as facts
(`:seon.doc/references` → function symbols, schema keys, tasks) and the inverse read "the sections that reference
`seon.fn/gate-set`" one indexed query; files stay the write-back, as for functions; `my.program/source` and the task
block show the referencing sections. (b) Docs as symbol-named entities written at the REPL (`my.doc/define!`), files
generated. (c) Expand docstrings.
Recommendation: (a) now, (b) as its REPL face later; never (c). It is the linkage, not the storage, that stops drift:
a function's context lists the sections that cite it, and a section citing a renamed symbol is a problem on that
section at ingestion. Honest answer on files: files remain better for humans, git review and editors, and the harness
greps them; the database index is what the agent needs. Markdown inside doc entities is wanted and cheap to render
(nextjournal/markdown → hiccup through the one printer's HTML face). What this never builds: a second resolver, a docs
folder scanner, prose in `ns` forms.

**Q2. What does a task author, and what is derived?** *Decided 2026-10-10: the database is the source of truth; files are lossless write-back and boot input (see §7).*
Recommendation (root's proposal, with one change): authored = `:seon.task/name`, `:seon.task/intent` (one Markdown
paragraph with typed references), `:seon.task/must-read`, later `:seon.task/depends-on`; derived = proofs (deftests
tagged with the task), interface (functions tagged), schemas (from those contracts), status (proofs green). No budget
key: a budget is one `is` in a proof (fable-simplify R3), so it lives in git beside the test. Retire `:seon.task/kind`.
Upserts come free with history (Q3). The chain's stages upsert the same entity: stage 2 adds must-reads, stage 3 the
schema keys, stage 4 the proofs by tagging. The blocker to remove first: task files are not writable from the REPL
(`seon.task/create!` writes the database; write-back of `resources/seon/tasks/<ns>.edn` does not exist), so stage 1–4
agents cannot record their output. Options: (a) write-back for task files through `my.program/write-branch!`'s path;
(b) the database is the task's home and the EDN file is generated at commit; (c) keep hand-edited files.
Recommendation: (a); it is the same mechanism functions use.

**Q3. Is full history really kept?** *Decided 2026-10-10: yes; keep the 1 h window. The merge base, the one consumer that loaded old commits, moves to `t` (see §7).*
Verified at the REPL (JVM mode, default): `d/history` sees 819 transactions spanning 265 hours (2026-09-28 to today);
`d/as-of` at 2026-10-03 answers 5,610 functions against 5,874 now; the last collection (2026-10-09, 4.3 GB reclaimed,
792,620 objects swept) removed none of it. Why: with `:keep-history? true` retracted datoms live in the temporal
indexes of the head commit, and `datahike.gc/reachable-in-branch` marks the head's temporal roots whatever
`remove-before` is; the one-hour window prunes only commit records and index nodes no head reaches. What is lost after
one hour: loading an old snapshot by commit id (`d/commit-as-db`), branching from an old commit, and per-commit
provenance beyond `:db/txInstant`. `as-of`, `since` and `history` are unaffected.
Options: (a) keep the 1 h window; (b) lengthen it for provenance. Recommendation: (a); nothing we do needs old
snapshots. Record this in the datahike skill (verify, then cite `datahike.gc/reachable-in-branch`).

**Q4. Search: lexical, semantic or both?** *Decided 2026-10-10: (a) now, (b) later.*
Facts: our Datahike fork has secondary indexes declared in schema and maintained in-transaction
(`datahike.index.secondary`): `:scriptum` is Lucene full text with branch-aware copy-on-write
(`datahike.index.secondary.scriptum`), `:proximum` is an HNSW vector index; `:db.secondary/only` keeps a large value in
the index and out of the datoms. Lucene 10.5 is already in Seon's deps.edn and nothing uses it. EmbeddingGemma 2
(blog and model card, 2026): 740M parameters total, 270M for text, about 190 MB active RAM quantized, 768-d vectors
truncatable to 128, 8k context, Apache 2.0, MTEB-Code 78.7; runs on CPU through llama.cpp, ONNX or sentence-transformers.
Honest answer: embeddings are not simply better. Agents searching code mostly know a name or a fragment, where lexical
search is exact and explains its hits; semantic search wins for "which function does X" discovery and loses on exact
symbols, numbers and keywords; hybrids beat either in published retrieval benchmarks, at twice the machinery. Costs on a
16 GB machine: Lucene over ~6k docstrings and ~500 doc sections is a few MB and sub-millisecond; embeddings are
200–600 MB resident model, 5–20 ms per text on CPU, 18 MB of vectors at 768 d (3 MB at 128 d), re-embedded per changed
entity keyed by content digest (fits the cache model), and a JVM integration (ONNX Runtime Java in-process, or a
llama.cpp sidecar) that every user must run.
Options: (a) Lucene secondary index over `:seon.fn/doc`, `:seon.ns/doc`, task intents and doc sections, with blobbed
text indexed at `seon.blob/inline-or-stage` time or declared `:db.secondary/only`; (b) embeddings in `:proximum` with a
local EmbeddingGemma; (c) both, hybrid.
Recommendation: (a) now, one schema declaration and one `my.program/search` face; (b) added as a second secondary index
only after a chain run shows a discovery the lexical search missed. What this never builds: a Seon search module or a
sidecar store for vectors.

**Q5. Accept the one-system design as decided?** *Decided 2026-10-10: (a); Datahike writer into Flow deferred.* The writer stays as Datahike's go-loops observed through
`:write-fn-map`; all procs on platform threads; the fork's commit runs synchronously on one platform thread for exact
commit cost; GC and JIT shown as a labelled residual, never apportioned. Options: (a) accept all four; (b) only the
turn proc on platform (lift `var-process`'s `:mixed` refusal); (c) keep commit as wall time, labelled shared.
Recommendation: (a). It is what §4 is written against; (b) saves two threads per agent for a second rule; (c) leaves
the konserve commit writes (53 MB sampled on one redefinition) unattributed.

**Q6. Rule on the test design.** *Decided 2026-10-10: proofs as written (an `are` table with the reported input plus counts), and in addition latest numbers stored and a cost diff to main in every reply (§5 step 2).* From fable-simplify: cut COST-REG slices 2–3 and the acceptance key; a stored member cost
is shown, never red by itself; properties optional (one seeded deftest where a one-line law exists); guards dropped;
the growth test retired; the proof is an `are` table holding the reported input plus one `is` on a count. Two places I
disagree with the current rulings and ask for a change: prove ten tasks first, not thirty (ten is one afternoon and
enough for two fan-outs); and defer auto-instrumentation and real-input capture until a chain run asks for them.
Options: (a) accept as written; (b) keep COST-REG slice 2 as a shown comparison only; (c) keep growth tests for
designer-declared cost tasks. Recommendation: (a), with (b) as the compromise if a member's stored cost must be visible
beside main's (it already is, slice 1).
