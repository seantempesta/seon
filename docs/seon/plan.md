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

Read this section as direction, not a ledger: each essential (§2), its status, what blocks it and who works it now.
History is in git; finished work leaves this section.

| Essential | Status | Blocked by | Working it now |
|---|---|---|---|
| 1 An honest number in every reply | **Done.** FEEDBACK-LOOP stages 1–13 and 16 live: a warm redefinition is about 97% attributed (entrance, step, writer, commit, labelled shared residual); like-for-like cold/warm cost per [symbol scope] (b37a3ff72); the diff shows only changed definitions (fde5f1e11). A fresh agent's warm `(+ 1 2)`: 72–74 ms, 6,668 calls; root's own context: 8,763 calls for the same work. | — | nobody; stages 14, 15, 17 wait (§6) |
| 2 One task worked end to end through the REPL | **The goal test.** A Claude lane passed the ordinary half (df87cba96). No model agent has yet worked a designed task red → green. | the `forgive` task and its red proofs (in flight) | lane `forgive-proofs` (stages 1, 3, 4); then root launches chain run 2 |
| 3 Red proofs before any assignment | Not started beyond the cost tasks. | nothing | after chain run 2 shows what a good proof needs |
| 4 Default runs HEAD | Restart per landing batch is routine; default runs b37a3ff72. Lag workarounds not yet deleted (§5 step 5). | — | — |
| 5 Correctness and efficiency over size | Node cache by bytes: a new agent's first request 2.37 GB → 0.38 GB; a warm redefinition's step about 217–232 MB. A `defn` reaching about 13 tests still costs 3.4–3.9 s and 4.3–5.5 GB in the test step. | — | — |

**Chain run 2, defined.** Done means: a DeepSeek-flash agent, launched on `seon.sci.forgive/forgiving-forgiven-text-changes-nothing`
with only the context Seon generates, turns its proofs green with no root rescue, and root reviews and merges its
branch. Its three known defects ("a]a", "('", "(\\") are in designed-task-examples §2. Launch: root's JVM REPL drives
`seon.competency/start-run!` and `await-runs` (the E1 path, per-branch `no-provider false`, a call cap) but keeps the
branch for review instead of `launch!`'s retirement. Every friction met is filed; that list decides the next build.

**Chain run 2, first attempt (2026-10-10, agent `chain2-forgive-1`, branch `:agent-e84b11b8bd78` kept).** Capped at 15
calls in 31.5 s, not green (proofs 2 red, 1 green); 156k prompt tokens (108k cached), 3.2k completion; 0 `defn`s,
19 repeated forms, `in-ns` on every call, 7 agent errors, 0 platform errors. It found the right spot and nearly the
research's fix (a dropped closer becomes a space, but without recording `:seon.batch/inserted`). Frictions, in causal
order:
1. `(my.program/source 'seon.sci.forgive/scan)` (about 250 lines) is cut from 3,533 to 1,989 tokens with a
   `:large-value` finding telling it to select less, so it cannot see the function whole (lane `source-whole`).
2. No REPL way to change one branch of a large definition: `defn` of the whole function is the only path, so the
   agent `slurp`ed and string-edited the file instead (decision for the owner: an edit tool over a definition's source).
3. A relative `spit` from SCI rewrote the running archive `data/source/<sha>/src/seon/sci/forgive.clj` (root
   restored it from git; the compiled Var was unaffected); lane `archive-readonly` makes extracted archives read-only.
4. Model tool markup `</invoke>` in a reply is read as code (3 errors; `seon.sci.forgive` reply-mode prose rules).
5. A guessed `seon.print/inspect`; `my.program/callers` passed a function (refused with a fix; agent errors).

**How root keeps the goal moving (2026-10-10 stall review).** The day after the instrument landed, the goal test was
"next" for two hours and never started: it had no owner and no first step, each owner observation about the
instrument became a new instrument lane, root chores were prepended to the next list, and root idled after landings.
Standing rules:
- One lane slot always serves essential 2 or 3 (the goal), never the instrument; the head of "next" is always work in
  flight with a named lane, never a label.
- An instrument finding is filed and sequenced unless it surfaces false data (that blocks the goal, so it is fixed).
- Root chores (branch cleanup, measurements) run beside lanes, never ahead of goal work; after a landing root
  launches the next step in the same turn.
- Every lane names the essential it serves; at most two or three implementation lanes, on disjoint files.
- The REPL-only acceptance's host-bound half (31b442840 fell back to `--test`; `proven-file` unreached) still gates
  new platform machinery.
- Lanes delete with plain `rm`, never `git rm`, and leave nothing staged; a whole-namespace `--test` on a namespace
  with declared-long tests is refused (name the vars); a dependency bump lands last in a batch, then a restart.

**Next, in order (after chain run 2):** backlog triage (prove the top ~30 tasks; the rest is an unproven backlog);
the host-bound acceptance half; FEEDBACK-LOOP stages 14, 15, 17; `seon.db/pull-logical` reading about 3× main on
agent branches; the cold-calls dial not reaching cells from the cluster configuration (task filed). Beside them:
retire the leftover agent branches (demo-*, loop-*, *-probe*, repl-only-3*, astra-node-cache*, par-a/b).

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

## 4. The first task: honest measurement (done)

Worked as the FEEDBACK-LOOP card (`schedule/cards/FEEDBACK-LOOP.md`, stages 1–13 and 16 landed); its design, proofs and
measurements are there and in git. The step's allocation it predicted was the node cache, since bounded by bytes.

## 5. Next steps, in order

Each is small, names its proof and what it makes measurably better.

1. **Second chain run, non-platform**: `seon.sci.forgive/forgiving-forgiven-text-changes-nothing`
   (designed-task-examples A: three defects, red `are` table and seeded idempotence property already written as
   prototypes). Stages 3–4 Sonnet, stage 5 DeepSeek-flash as implementer with a Claude lane watching. Better by: one
   model agent takes a designed task red → green with no root rescue, or the exact friction is filed.
2. **Default runs HEAD per landing batch**, then delete what the lag required: the write-back file rebase's snapshot of
   the published archive in `my.test/check-platform`, template-per-slot (store identity read from the store record, not
   `nameUUIDFromBytes(path)`), "live after restart" proofs. Better by: `bin/seon check` under 10 s warm (37 s now).
3. **Ten tasks with red `are` proofs** (Sonnet, ~2 h): root picks ten open tasks with small owners and a known bad
   input; one `are` deftest each, tagged, red on default. Only these ten are assignable. Better by: tasks with a red
   proof 26 → 36.
4. **A10: a visited test namespace makes every later request 3× slower** (opus review 2.5; 141 ms test step for `(+ 1 2)`).
   Fix at the producer (entered Vars counted as the agent's changes) with a `seon.profile/measure` row on the second
   request. Better by: a trivial form stays at ~70 ms after any `in-ns`.
5. **Task entity and docs in the database** (§8 Q1–Q2), after the two chain runs show what the agents actually lacked.

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
- 2026-10-10 Only reliable data is surfaced (owner: "false regressions are the bug"). A number shown or stored as a
  baseline is like-for-like: the same scope (interpreted or compiled), warm, and with enough calls. Otherwise it is
  an explicit unknown, never a misleading ratio. Landed b37a3ff72 and confirmed live: one cost entity per [symbol, scope],
  each with a cold sample (a definition's first call in the process) and a warm sample. Every comparison is cold to
  cold or warm to warm, in the same scope, with enough calls; otherwise it is an explicit unknown. Health reads warm;
  cold cost is its own query. The 81 ms / 8.6k-call reading was root's own context (8,763 calls); a fresh agent's
  warm `(+ 1 2)` is 72–74 ms / 6,668 calls, so no counting overhead. Open: the cold-calls dial does not yet reach cells from the cluster
  configuration (task filed).
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
