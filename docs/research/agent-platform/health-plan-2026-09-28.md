---
type: plan
date: 2026-09-28
status: owner-directed; building (lanes health-attribution-opus, health-recording-opus, health-page-opus)
evidence: tmp/health-research/{substrate,entities,flow-render,loop,processes}.md, tmp/boot-perf/
---

# Health: the database knows what is wrong

**Goal.** What is wrong with the system, and what to fix first, is one query away: every function, schema, test,
agent, proc and operation is linked in the database to its errors, costs and evidence, so nobody profiles by hand and
no agent guesses at a bug. The health report and every agent's context are renders over that graph.

**What exists.** Every armed function is timed into an in-memory cell keyed `[sym digest]`
(`src/seon/profile.clj:69`) and, when contracted, validated. Every write passes `seon.db/stamp-transaction`
(`src/seon/db.clj:3523`), which already receives the bound world. The error schemas already declare most of the
links needed (`resources/seon/schemas/seon.error.occurrence.edn`) — and almost none are written: no stored error is
linked by ref to the function, schema or test that caused it; contract violations are stored as strings; 0 of 87
occurrences name their evaluation or turn (entities research).

## 1. The model: four kinds of facts, one graph

1. **Subjects** — the entities that already exist: function (`:seon.fn/sym` + definition digest), schema, test,
   namespace, agent, branch, turn, evaluation, test run and member, task. Two are added: a Flow proc (graph + pid) and
   an operator record (boot, adoption, publication), so every process and operation is a subject too.
2. **Transactions describe the work that produced them.** At `stamp-transaction`, from the world's call sink (its delta
   since that world's previous transaction), beside the existing `:seon.db.transaction/process`, `/operation`,
   `/agent`: `/ms` (the work's elapsed time), `/calls`, `/hot` (top 5 `[sym digest calls self-ms]` by self time),
   `/repeated` (`[sym digest calls]` over the repeat threshold). 10–12 datoms, ~1 KB, on a commit that happens anyway
   (measured ~0 extra store bytes); only `/ms` and `/calls` are indexed (each indexed stamp attribute added ~0.6 files
   per commit). History from before the stamp has none, so joins on it use `get-else`.
3. **Measurements on subjects, per definition version, written only on significant change** (noHistory): a function's
   p50/p99 bucket (log₂ ns), error-ratio class, calls class; a test member's duration; an operator phase's duration
   and what it was proportional to (files, changed files, cache hits); a cache kind's byte share and hit class.
4. **Errors — events, as a network.** An error happened, so it is recorded; its entity's attributes are exactly the
   named Malli schema it satisfies (a named extension of `:seon.error/base`, e.g. an input violation) — no kind or
   severity field, no "error" type:
   - **Signature** (one per `:seon.error/signature`, stable): producer → function (by the blame rule) + producer digest,
     schema → schema entity + Malli path, the named schema's specific attributes, noHistory count and last seen,
     `resolved-by` → the change that fixed it.
   - **Occurrence** (first K per signature per producer digest, then only the count): signature, agent, branch,
     evaluation or turn or test member or proc, task, caller → function + caller digest, the transaction (with 2's
     work summary), and one blob digest (cause chain with frames, offending value).

**Warnings are not stored.** A warning is a state of a subject — a p99 bucket over its bound, a hot function no test
reaches, a function with no contract, a cache kind holding much and hitting little, a proc whose backlog grows. A named
warning schema is a predicate over subject attributes; its matches are a query. It disappears when the data changes.

**Blame follows the producer (owner ruling).** An input violation counts against the caller that produced the bad
value (an agent's own form: the agent's mistake, returned to it); an output violation against the function itself.

**Errors are part of the program.** Failures are declared output alternatives, returned as values; the Var render
shows a function's error alternatives beside its contract; an error value passed into a function that does not accept
it is an input violation blamed on the caller. Recorded: every refusal and throw, and every error value that reaches
an entrant (an evaluation's value, a reply, a proc step, a test body). An error a caller handles is data flow.

## 2. Writing it: transparent, one pipeline, no extra commits

- **Ordinary functions change nothing.** Four owners do it: the armed wrapper (capture), the one-path fold and every
  Flow proc step and work-launcher task (bind the world: `seon.flow/var-process`, `execute-io-work!`), the entrant
  boundaries (convert escaping failures through the one constructor), and `stamp-transaction` plus the existing commit
  writers (attach facts).
- **One error pipeline:** detect (wrapper, entrant boundary) → build (`seon.error.refusal/diagnostic`: the common part
  from the world and the armed refusal, specifics from the producer, bulk to one blob) → store (one recorder: upsert the
  signature, add an occurrence under the cap, else bump its count).
- **Refs, measured (model proof):** a lookup ref to a missing entity fails the whole transaction and an identity map
  silently creates a placeholder, while an index seek costs 1.6 µs whether or not the entity exists. So links to
  subjects that may be absent on a branch — the producer and caller functions — are stored as `[sym digest]` values and
  joined at read time (only `:seon.fn/sym` is a unique identity); refs are used where the target is written in the
  same transaction or certainly exists (signature, occurrence, evaluation, agent, task, schema by key). The cap
  ("first K occurrences, then the count") is decided by a transaction function at the writer, the only place that
  sees the current count (1,000 identical failures → 5 occurrences, one count, ~73 datoms, +1.6 ms per commit);
  repeated failures inside one evaluation are folded in memory first.
- **Blame needs authorship on the definition:** the 12,423 current definition digests came from only 92 transactions
  (one bulk adoption asserted 4,540), so "which change introduced this" reads the definition's own task, proposal or
  source commit, not the transaction that happened to assert it.
- **Cost:** the success path is counter increments (a LongAdder ~6.6 ns; the bracket reuses its two clock reads —
  never add one: 382 ns at 16 threads); build and store run only on failure or a moved bucket; the context read is
  32 B; every fact rides a commit already happening (~0 extra bytes; a separate commit keeps ≥1.15 MB). A function
  failing a million times writes K occurrences and one counter. In-memory accumulators restart from zero on boot.

## 3. Reading it: queries, then renders

- **Everything is a query over the graph.** "What is slow or doing too much": transactions with `/ms` or `/calls` over
  a bound, with their operation, agent and hot functions. "What is failing": signatures by occurrence count and
  recency, joined to producer, schema and callers (first-run 7.4 ms for the top 20); reaching tests are a transitive walk
  (692 ms for 20 producers, core functions reached by ~1,700 tests), so the report shows a count and the direct
  `:seon.test/subject` tests, and "hot and untested" is computed once per program version. "What regressed": a producer digest whose bucket
  moved, joined to the transaction and agent that wrote it. Named warning and error schemas compile to their queries,
  so a new one is queryable when registered.
- **The health report** (`:seon.health/report`) is those queries ranked (severity declared on each named schema ×
  calls affected × time × recency), each result cached in `seon.cache` by the revisions of the attributes it reads
  (0.22 ms hit vs 217 ms miss; unrelated commits do not invalidate). A miss shows `:pending` with its requery and never
  blocks. One value behind MCP `runtime_status`, `bin/seon status` and `/health`; AI face = pruned data plus dig forms,
  HTML face = hiccup. It runs on the cluster graph's existing render proc: no new proc, listener or timer.
- **Procs** answer `flow/ping` (bounded, on demand only, never on a timer) with a `:ping-map-fn` of their few counters;
  unknown is never green.
- **Agents learn the same way.** A task refs a signature or a subject plus a named warning schema; its render is the
  briefing at distance 1, and `:seon.related/data` walks the declared refs for the rest. Tasks consume the graph; the
  graph stays whole whether or not a task exists. Auto-created tasks need a recorded red run first (owner ruling); a
  task closes when the producer's digest changed and its proof is green, or the subject no longer satisfies the
  warning — derived, never a flag.

## 4. Every process is a Flow proc

A proc built by `var-process` gets the world, capture, linked faults, ping and awaited stop for free; execution
outside Flow is a finding. Inventory (processes.md): 32 contexts outside Flow — 7 become procs (MCP JVM mode through
the agent's turn proc; publication/adoption as an adopter proc; the per-turn backstop observer; foreground effects and
shell capture through the work launcher; one SSE painter per cluster; test bodies as test agents), 15 callbacks only
hand off, 9 stay in dependencies, 4 are deleted. A small process graph gets one faults proc for the three fault writers
on foreign threads.

## 5. Build order

1. **Errors as a network** (health-attribution-opus): the one constructor and recorder, the blame rule, refs from the
   world and the wrapper, the world bound in procs and work-launcher tasks, signature → task → derived close. Proof:
   fault 103511 (a refusal in `seon.turn/record-evaluated-tx` whose bad value came from `seon.turn/system-turn`, open
   16 h because resolution watches the refuser) is attributed to `system-turn` and closes from evidence.
2. **Transactions and measurements** (health-recording-opus): histogram cells with self time, the transaction summary
   at `stamp-transaction`, significance-only buckets, test member durations. Proof: one query returns slow and
   call-heavy work with who and what; noise writes nothing.
3. **The report** (health-page-opus): named-schema queries, ranking, cache, renders, `/health`; the five fault lists,
   the runtime observation and the second status namespace collapse into it.
4. **Processes into Flow** (§4), smallest risk first; operator records with their proportional inputs.
Each slice is built live at the REPL, then written to files, then documented; proven by adoption on a branch of the
live store and root's batched restart.

## 6. What the research already found (each becomes a task)

- The arity gate's three cache kinds held 458 of 503 MB at 8–23% hit rates, rebuilding a whole-program base per write
  (142 ms mean, 1.3 s max): check only the edges a write touches (astra fixer, top priority).
- Every commit keeps ~300 KB of commit record inlining index roots (26% of each commit's cost).
- `malli->datahike-attr-in` throws as control flow: 73,492 times in 1.79 M calls (patch: 423 → 72 ms).
- `seon.problems` `failed-tests`: 1,784 reads, 228 ms; `seon.cluster.status/agents` 324 ms; `status/snapshot` writes a
  thread dump to a file on every call; the Flow ping timeout is 20 ms on default and has no declared default.
- Boot: 20 s before the source is current because a change to any of the 348 definitions the analyzer reaches
  re-derives all 850 inputs; 0.46–1.9 s per publication spent on a digest nothing compares (patch: −83 lines);
  adoption carries 483 files whose only change is a restamped derivation key.
- The drill rehearsed a second boot path; one writer now fills an empty store with identical data, so the drill and
  its rule are deleted (AGENTS.md 626c3d1d4); static analysis of `script/` namespaces goes stale (key it by content).

## 7. Rulings

Blame the producer. Red-first proofs for auto-created tasks. Accumulators restart from zero; stored facts are enough.
No kind or severity attributes: named schemas identify errors and warnings. Errors are events; warnings are queries.
Every process is a Flow proc. No drill. Open: whether health rows fall under a retention cutoff; per-query timing
cells keyed by the normalized query form; whether heap, ping and cache readings refresh on an idle cluster.
