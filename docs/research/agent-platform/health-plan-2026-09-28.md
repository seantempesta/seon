---
type: plan
date: 2026-09-28
status: draft for owner review; all four research notes in
evidence: tmp/health-research/{substrate,flow-render,entities,loop}.md
---

# Health: know what is wrong, precisely, and hand it to agents to fix

**Goal (owner, 2026-09-28).** One health value, rendered for AI and HTML, that states everything wrong with the
system down to the entity (function, schema, test, namespace, agent, proc, store, cache, operator phase), with
enough clues that the fix is designable on sight, and that routes each problem to an agent who fixes it in SCI on a
branch with a test that proves it, then merges. No agent guesses at bugs. Collection is light: it rides work that
happens anyway, never thrashes the store, never locks anything, and no-ops when it cannot collect.

**What exists.** Every armed function is timed into an in-memory cell (`seon.profile/cell`, `src/seon/profile.clj:69`)
keyed `[sym digest scope]`; contracted functions are also validated (Malli wrapper). A settle commit already records
`:seon.cluster.eval/executed` and the calls over 100 (`src/seon/turn.clj:5254-5280`). Faults carry a noHistory
occurrence count. A whole health value exists, `seon.cluster/mcp-runtime-observation` (`src/seon/cluster.clj:279`),
recomputed from scratch on every call (~300 ms), AI face only. Operator phases (boot, drill, publication) are printed
once and never stored.

## 0. Transparent by construction (owner, 2026-09-28)

"Handle everything transparently so the rest of the system doesn't have to do anything." Ordinary functions change
nothing. Four existing owners do all of it:
1. **The armed wrapper** (`seon.instrument`) captures every call: timings, histogram, throws, contract refusals with
   digest and path, and the sampled tiers (§10).
2. **The one-path fold** binds the world (agent, branch, evaluation or test member, task) around every evaluation, so
   whatever is captured already knows where it happened.
3. **Entrant boundaries** (the fold, MCP, web, Flow procs, schedule handlers, boot, the test runner) already must
   convert every escaping failure (AGENTS.md "Nothing crashes"); they do it through the one constructor (§3), which
   fills in the linked context.
4. **The writers of commits that already happen** (settle, test batch, fault record, publication, adoption) attach
   the findings and significant buckets as transaction data, linked by ref.
A producer declaring its own error extension is optional (the base carries the common part).

**Flow procs get their world at the one proc seam.** Every proc is built by `seon.flow/var-process`
(`src/seon/flow.clj:278`), which already refuses args without a `:seon.env/environment` (Flow conveys no bindings by
design) and already wraps every step arity to convert a throw into one fault. It does not yet bind
`seon.batch/*world*` around the step, so capture, supply and error context see no world inside procs. The fix is in
that wrapper, about ten lines: bind a world derived from the args it already holds — the environment, the proc's pid
and graph, its agent — with the database read fresh per step. A turn proc's evaluation rebinds its own world inside,
as today. Listeners and boot get the same from the value each already holds (the connection's cluster, the boot
record).

**Every process is a Flow proc (owner, 2026-09-28).** "As much of the system that we can identify as different
processes, like agents, should be Flow; this will only increase with time." So the health system's coverage of
execution is exactly Flow's: a process that runs as a proc built by `var-process` gets the world, capture, linked
faults, ping and awaited stop for free, and a process outside Flow is itself a finding. Callbacks (Datahike listeners,
http-kit handlers) stay callbacks that only hand work to a proc. The inventory of execution outside Flow and its
conversion order is `tmp/health-research/processes.md`: 32 execution contexts outside Flow — 7 become procs
(MCP JVM-mode `jvm-run` through the agent's turn proc as SCI mode does; publication/adoption `refresh-source!` as an
adopter proc in the cluster graph; the per-turn backstop observer; foreground effect dispatch and shell capture through
the work launcher; one SSE painter proc per cluster instead of an unjoined loop per tab; test bodies as test agents),
15 are callbacks that must only hand off (4 need a change), 9 stay inside dependencies, 4 are dead or duplicate
(two graph-stop senders, the uncalled `:compute` root executor, a never-stopped `generator-server`, the raw FutureTask
effect path). Work-launcher tasks (`seon.flow/execute-io-work!`, `src/seon/flow.clj:465`) need the same world binding
as the step wrapper. A small process-level graph with one faults proc takes the three fault writers that run on
foreign threads today (Datahike listener failures, JMX memory pressure, the uncaught-exception handler); store GC can
move there later. Conversions run as their own slice after §8 step 1 lands, smallest risk first.

## 1. Detection: schemas and timings on the code graph

- **Schemas are the primary detector.** A contract violation already knows the function and digest, the schema and
  Malli path, the offending value, the caller, and the evaluation or test that triggered it. Recorded with refs to
  those entities, it is the finding.
- **Timings everywhere.** Every armed function is timed; the uncontracted ones are timed but unvalidated. Functions
  without contracts are themselves a health finding (the coverage hole list).
- **Tests are reproductions.** A test body runs through the one path, so the functions it calls are armed; deftest
  Vars themselves are not (0 of 264), and nothing records the test with a violation today (§3 change 3 fixes that).
  The test is then the failing proof handed to the fixing agent.
- **Operator phases become measured entities too** (boot layers, adoption, publication, drill steps), with the inputs
  they are proportional to (files, changed files, cache hits). Pending: loop research.

## 2. Recording: significant changes only, on commits that already happen

Measured (substrate research): every commit keeps ≥1.15 MB (0.85 MB index nodes + ~0.30 MB commit record inlining
index roots, `store.clj:195`), ~25 ms; noHistory only skips the history indexes (Datahike
`db/transaction.cljc:448,547`), so a separate health commit thrashes whatever its datoms. Data added to a commit that
happens anyway costs ~0 bytes (+240 B) and +2–4 ms per 100 upserts.

1. **In memory per `[fn digest]`: a log₂ histogram**, ~40 buckets of nanoseconds, beside today's calls/total/max/throws.
   Recording is one extra adder increment (bucket = one leading-zeros op), reusing the wrapper's two clock reads
   (clock reads are the cost under contention: 382 ns at 16 threads on macOS). p50/p99, rate and error ratio derive on
   demand.
2. **The recent window is free:** keep the bucket counts as of the last write; current minus those is "since the last
   update". No rolling machinery, nothing reset, and a lifetime total never hides a recent regression.
3. **Store judgments in stable buckets**, not raw numbers: p50/p99 bucket (`[8 ms, 16 ms)`), error-ratio class
   (none/rare/frequent), over-bound yes/no; a named warning schema over these attributes (e.g. over the bound) is what
   makes a function "slow", never a stored label. They move only on ~2× changes, so noise never writes.
4. **Write on significance, only on existing commits:** at a settle or test-batch commit, for the functions that
   evaluation ran (the per-world call sink already lists them: work ∝ executed functions), compare the window's
   buckets with the stored ones; upsert noHistory only when a bucket moved and the window has ≥ N calls (≈20). Most
   settles write nothing; a real regression writes once.
5. **A new definition digest is a new baseline:** buckets are per definition version, so "this change moved `f`'s p99
   from [1,2) to [64,128) ms" is a direct comparison and a ready finding.
6. **Work outside evaluations** (procs, boot, JVM-mode calls) stays in memory and is read on demand; it publishes on
   the next commit that touches it. **Owner ruling:** what is stored in the database is enough; on restart every
   in-memory accumulator starts from zero, cleanly (no snapshot blob).
7. **Transaction metadata carries the rest** that a commit observed: violations (fn, schema, path, value blob, caller,
   test/evaluation refs) and errors, on the transaction that happened anyway. The seam exists: 089ac703a stamps every
   write with process, operation and agent (`src/seon/db.clj:3523`, astra fixer's transaction provenance; not yet in
   the live store). Today only 37 of the latest 3000 transactions carry any metadata.
8. **Test member run times** go into the test batch's one commit (today computed, `src/seon/test.clj:2013-2016`, never
   stored).

9. **Every transaction describes the work that produced it (owner: "a data model written efficiently during
   transactions … so a single query knows everything that's slow or has too many calls").** The one seam every write
   passes, `seon.db/stamp-transaction` (`src/seon/db.clj:3523`), already receives the bound world and stamps
   `:seon.db.transaction/process`, `/operation`, `/agent`. It adds, from the world's call sink (a pure
   `seon.profile/transaction-observation`, its delta since that world's previous transaction): `/ms` (the work's
   elapsed time), `/calls` (armed calls), `/hot` (top 5 `[sym digest calls self-ms]` by self time), `/repeated`
   (`[sym digest calls]` over the repeat threshold). A handful of datoms per transaction, ∝ functions that work ran,
   ~0 extra bytes. Needs self time in the sink (profiling Part A). One query over transaction entities then answers
   slow or call-heavy work, who did it and what dominated it; the per-digest subject buckets (item 4) answer each
   function's current state; the named warning schemas are these conditions, declared once.

Never: a commit of its own, per event or per timer.

## 2b. The event graph: what is written, as refs, when something happens

Tasks are one consumer of this graph, not its limit (owner, 2026-09-28): everything observed is stored as linked
facts on entities that already exist, so it can be queried later in ways nobody planned. Three layers, all written on
the transaction that already records the work (settle, test batch, fault record, publication, adoption; plus the
per-write provenance stamp of 089ac703a: process, operation, agent). "Declared" = already in the schema resources,
mostly never written today; "new" = to add.

**Errors are events; warnings are signals (owner, 2026-09-28).** An error happened — something was refused or thrown
at a moment, in a world — so it gets an occurrence with its context and blob. A warning did not happen: it is a state
of a subject the database already describes (a function's p99 bucket over its bound, a function with no contract or
hot with no reaching test, a cache kind with a high byte share and low hit rate, a proc whose backlog grows, a boot
phase proportional to the whole program). **No warning entities and no warning writes:** only the measurements and facts
themselves are written (buckets on significant change, contracts, test reach, operator phase records). A named warning
schema is a predicate over a subject's attributes; the health view derives its matches by a query built from the
schema, cached by the revisions of the attributes it reads. A warning disappears when the data changes — nothing to
resolve or close; a task made from a signal refs the subject and the named warning schema, and its done condition is
"the subject no longer satisfies it". The pipeline below applies to errors; warnings are read, never recorded.

**Errors are part of the program, for the agents who write it (owner, 2026-09-28).** A function that can fail
declares each failure as a named alternative of its output union and returns it (AGENTS.md "Nothing crashes"); the Var
render shows a function's declared error alternatives beside its contract, so an agent calling it sees what it must
handle. An error value a caller passes on into a function that does not accept it is an input violation blamed on the
caller (§3), so an unhandled error becomes a finding on the code that ignored it. What is recorded: an error value that
reaches an entrant (an evaluation's value, a reply, a proc step, a test body) and every refusal or throw; an error
value a caller handles is ordinary data flow and is not recorded.

**Refs without reads.** Every ref is written as a Datahike lookup ref or identity map in the transaction data
(`[:seon.fn/sym+digest [sym digest]]`, `[:seon.error/signature sig]`, `[:seon.agent/id id]`), which the writer
resolves by index seek inside the transaction it is already running — no read before the write. The identities are
all values already in hand: the wrapper's cell key `[sym digest]`, the world's agent and evaluation ids, the schema
key, the hashed signature. A ref whose target may not exist is written as an upsert map only where its identity is
owned by that write (signature, occurrence); a program entity that may be absent on this branch keeps its value
(`[sym digest]`) and joins at read time, never a placeholder entity.

**One base, one pipeline for errors (owner question: "what is the common thing … or are we writing the
instrumentation stuff separately?").** Every named error shares one base: the **occurrence context** — where (agent, branch,
evaluation / turn / test member / proc, task: from the bound world), who (producer function + digest, caller function +
digest: from the armed wrapper, whose cell is already keyed `[sym digest]`), what (schema ref + Malli path, when a
contract was involved), evidence (one blob digest: cause chain, offending value), and when (the transaction it rides,
with its provenance stamp). The declared `:seon.error.occurrence/*` attributes are nearly this set. A named error
(`:seon.error/base` extensions) adds only its specific attributes; its named schema validates the
whole. Instrumentation is not a separate writer — three stages, each owned once:
detect (armed wrapper, entrant boundary, significance check) → build (`seon.error.refusal/diagnostic`: base from world
+ wrapper, specifics from the producer, bulk to a blob) → store (one recorder: upsert the signature by identity, add an
occurrence only under the cap, else bump the noHistory count, all as tx data on the commit already happening).
Measurements are not events: they are subject attributes per digest (§2). Cost: the success path is counter
increments only; build and store run only when something is wrong or a bucket moved; the context is already in hand
(world read: 32 B; digest: the cell key); the signature is a hash, so recording is an upsert, never a scan; a function
failing a million times writes K occurrences and one counter.

**Subjects (exist today; nothing new):** function `:seon.fn/sym` (+ its definition digest), schema `:seon.schema/key`,
test `:seon.test/sym`, namespace `:seon.ns/name`, agent `:seon.agent/id`, branch, turn `:seon.turn/id`, evaluation
`:seon.cluster.eval/id` (already holds `executed #{[sym digest]}`, `call-count`, `repeated-calls`), test run and
member, task. New subjects: a Flow proc (`:seon.proc/id` = graph + pid, ref to its agent or cluster) and an operator
record (boot, adoption, publication: phases with durations and what each was proportional to).

**No kind field, no "error" type (owner, 2026-09-28; AGENTS.md "no discriminator stamp").** What a problem *is* is the
named Malli schema its attributes satisfy: an input violation is a value satisfying, say,
`:seon.instrument/input-violation-error` (a named extension of `:seon.error/base`); a slow function satisfies a named
warning schema such as `:seon.profile/over-bound-warning`. Nothing writes "this is an error" or a kind: the entity
carries the attributes, and all of them together are that named error because they satisfy its schema. Severity and
the task threshold are properties of the named schema's declaration, not per-entity data. "All X" is a query derived
from the named schema (the attributes it requires, as Datalog patterns, then Malli validation of the pulled entities),
so a new named error or warning is queryable the moment its schema is registered.

**Signature — "this problem" (one entity per `:seon.error/signature`), stable across occurrences:**

| Attribute | Kind | Status |
|---|---|---|
| `:seon.error/signature`, `/layer`, `/exception-class`, `/message` | values | declared, written |
| `:seon.error/producer` → function entity (the blame rule) | ref | new; replaces the `:seon.instrument/fn` name and the `/operation` guess |
| `:seon.error/producer-digest` | value | new (declared `digest-then`/`digest-now` exist, unwritten) |
| `:seon.error/schema` → schema entity, `:seon.error/path` (Malli path) | ref, value | `path` declared; ref new |
| `:seon.error/occurrence-count`, `/last-at` | values, noHistory | declared, written |
| `:seon.error/resolved-by` → the change that fixed it (derived close) | ref | declared |

**Occurrence — "it happened here", bounded (first K per signature per producer digest plus a count; declared
`:seon.error/capped` marks the cut):**

| Attribute | Kind | Status |
|---|---|---|
| `:seon.error.occurrence/id`, `/first-at`, `/last-at`, `/count` | values | declared, written |
| `/agent`, `/branch`, `/cluster` | refs / values | declared; agent written on 35 of 87 |
| `/evaluation` → evaluation, `/turn` → turn | refs | declared, never written |
| `/test-member` → test member (the test is then the reproduction) | ref | new |
| `/proc` → Flow proc (was `proc-fn`), `/effect` → effect | refs | declared, never written |
| `/caller` → function entity, `/caller-digest` | ref, value | new (today a `"ns (file:line)"` string) |
| `/task` → the task in force (from the world) | ref | new |
| `/blob-digest` — cause chain with frames, offending value | value | declared |
| the transaction itself — process, operation, agent | tx data | 089ac703a |

**Measurements — on the subject, per definition version, significance-only (§2), noHistory:** a component of the
function entity per digest (`:seon.fn.health/digest`, `/p50-bucket`, `/p99-bucket`, `/error-class`, `/calls-class`,
`/over-bound?`, `/at`) — new; a test member's duration (new; computed today, discarded); an operator record's phase
durations and proportional inputs (new); a cache kind's bytes share and hit class on the process record (new).

**What this lets anyone query, with no task involved:** which functions produce the most violations since a commit;
which change (producer digest → the transaction's agent and task) introduced a regression; which tests reproduce a
signature; hot functions reached by no test; errors per namespace, per agent, per branch; what a proc was doing when it
faulted; every occurrence a given evaluation caused. A task is created from a signature, refs it, and renders it; the
graph stays whole whether or not a task exists.

## 3. Per-entity health model

**Today no error is linked by ref to the function, schema or test that caused it** (entities research, live default):
- Every error carries two competing cause symbols: `:seon.error/operation` (a value) and `:seon.instrument/fn` (a
  name), which disagree on 41 of 47 errors; `:seon.instrument/fn` often names the recorder (`seon.turn.world/
  record-fault`), and fault resolution keys on it (`src/seon/error.clj:1156`). No error records a definition digest.
- Declared links are unwritten: `error/agent`, `run`, `issue`, `regressions`, `cause` empty on 47 of 47;
  `occurrence/turn`, `evaluation`, `proc-fn`, `effect`, `related` empty on 87 of 87; issue → error 0 of 463.
- **Contract violations lose their detail when stored.** At the throw the refusal holds function, check, arity, schema
  key, Malli path, argument, offending value and caller (as a `"ns (file:line)"` string); stored: nothing (MCP JVM
  mode), a message string (agent evaluation), a printed string (test body, 579 distinct), or a printed schema with
  path, value, caller and digest dropped (core fault). Violations joined by ref to fn + schema + test: 0.
- **Tests:** deftest Vars are not armed (0 of 264); `:seon.test/reach` empty on 2383 of 2383; nothing records what a
  test executed (only a static-closure digest); `seon.test.failure` has 0 entities (delete).
- **Contracts:** 2816 of 5798 first-party functions contracted; 2570 non-macro, non-constant ones have none; contract
  status stored nowhere, computed in four places; 1572 stored lint findings read by no surface.
- **Performance joins cleanly:** 2551 of 2555 profile cells match a stored `[sym definition-digest]` — in memory only.
  `seon.db/q` is one cell (53,453 calls, 93.5 s total, max 5.5 s); the normalized query form Datahike already
  memoizes (`src/seon/db.clj:2776`) is a ready query identity.

**Blame follows the producer (owner ruling 2026-09-28).** An input violation means the *caller* produced the bad value: the
cause is the calling definition (or, when the caller is an agent's own form, the agent's mistake, delivered to it and
not counted against the function). An output violation means the function itself is wrong: the cause is the function
at its digest. This is AGENTS.md "fix the producer" applied to attribution, and it answers "which symbol is the cause".

**One way to catch, translate, link and store an error (owner, 2026-09-28).** "We need a consistent way to catch
errors, translate them to useful data and link everything up that's responsible in the database" — the common part
from Seon functions at the moment of the error, the specifics from the producer, never "600 pages of stacktrace".
The one constructor exists — `seon.error.refusal/diagnostic` (`src/seon/error/refusal.clj:497`), which already derives
the class, cause chain and root frame and references the worlds it is handed — so it grows, nothing is added beside it:
- **The ~90% common part is supplied, not authored:** at the moment of the error, `diagnostic` reads the calling
  evaluation's world (`seon.batch/*world*`: agent, branch, evaluation or test member, task) the way call preparation
  supplies world members, and the armed wrapper's refusal supplies function, digest, schema key, Malli path and caller.
  The blame rule picks the producing definition. The producer adds only its specific fields (its named extension of
  `:seon.error/base`).
- **Bounded by schema, the bulk by reference:** the stored value is validated against its `:seon.error/base`
  extension and holds refs and small fields only; the whole cause chain with frames and the offending value go to one
  blob, referenced by digest (AGENTS.md: "a large value one blob digest"). What is stored is what the health view,
  resolution and the fixing agent read; the rest is one `get` away.
- **Linked by ref, stored once:** the error entity refs the producing function entity (+ digest), the schema entity,
  the agent, evaluation or test member and task, and rides the transaction that already records the failing work
  (§2.7). Every entrant boundary uses this one path (AGENTS.md "Nothing crashes"), so a thrown failure, a contract
  refusal and a declared error value all land with the same shape and the same links.

**The finding value** (one per problem): the named schema it satisfies (input or output violation, over-bound, missing contract, red test…), cause
`[sym digest]` + ref, schema key + Malli path, offending-value blob, caller chain (from the code graph), world (agent,
branch, evaluation or test member), the reproduction (the test member, else the evaluation's form), and counts.

**Producer changes, smallest first:**
1. One cause per occurrence: `[sym digest]` plus a ref, chosen by the blame rule; stop writing the recorder into
   `:seon.instrument/fn`; resolution keys on a new digest *whose proof passes*, not on any digest change.
2. `boundary-refusal` (`src/seon/instrument.clj:665`) carries the refusing definition's digest and a structured caller.
3. Each entrant stores that refusal as data on its existing transaction instead of printing it; a test member records
   member → violation and the `[sym digest]` it executed (the per-world sink already has it), so the test is the
   reproduction.
4. Write the declared refs (turn, evaluation, proc-fn) and delete the ones nothing will write.
5. A query-identity profile cell for `seon.db/q`, keyed by the memoized normalized form, digested only when stored or shown.
6. One open-fault relation becomes the only source; the other four fault lists, the dead "failed runs" family and the
   second status namespace go.

## 4. Execution and presentation: no new proc, lazy, cached, never blocking

- **No new proc, listener, timer or channel.** The health page is one more watched page on the cluster graph's render
  proc (`seon.render.web/render-step`, `src/seon/render/web.clj:2572`), woken by the one routing listener and
  coalesced by its floor: zero cost with no tab open or an idle database.
- **Cached by what each family reads:** key each problem family in `seon.cache` by the Datahike revisions of the
  attributes it reads — 0.27 ms to key, 0.22 ms hit vs 217 ms miss, unrelated commits do not invalidate (6 of 8
  families today; `failed-tests` and `error-signatures` each have one unattributable read to fix at the producer).
- **Never waits:** a miss renders the family `:pending` with its requery form; the derivation runs off the render pass
  and its completion offers a payload-free wake. A slow collector is never cancelled (interrupts wedged the JVM
  before); Caffeine shares the one in-flight computation. `seon.cache` gains a non-blocking read (~15 lines, Caffeine
  async with a deadline).
- **One value, three views:** `:seon.health/report`, made of units, each reusing its owner's render pair (problems,
  oversight, cluster status, maintenance); it replaces the separate runtime observation, so MCP `runtime_status`,
  `bin/seon status` and the page show one value. AI face = pruned data + dig forms; HTML face = hiccup.
- **Flow's own monitoring is the proc unit.** `flow/ping` (bounded, `reference-code/core.async/src/main/clojure/
  clojure/core/async/flow.clj:136`) answers per proc its status, message count, datafied in/out channels (buffer fill:
  backlog) and `::flow/state` through the proc's `:ping-map-fn`; `:error-chan` already feeds faults (`var-process`).
  Every proc declares a `:ping-map-fn` projecting its few meaningful counters (the schedule proc does today), and the
  health view pings on demand only, bounded, never on a timer (the control-mult wedge). `core.async.flow-monitor` is a
  separate web server and UI: not adopted (a second UI), but its topology view is the model for the HTML render.
- **Where the data lives:** per-call accumulators in the armed wrapper's cells (in memory, owned by the wrapper, never
  evicted: ~2,555 cells × ~40 histogram buckets is a few MB, to be measured); derived health values in `seon.cache`
  (evictable, content-keyed, rebuilt on a miss); significant buckets and findings in the database; bulky detail in
  blobs by digest; proc state in the procs, read by ping.
- **Lockup rules kept:** listeners only `offer!`; nothing new on the writer thread; no monitor across commits; no
  timer pings (control-mult wedge); unknown is never green.

## 5. Finding → task → agent → proof → merge (loop research)

Every link has an owner except three, and in each the data is computed and then thrown away: who produced the bad
value lives only in message text; no operation above one evaluation leaves a stored record; nothing turns a finding
into a task or closes a task from evidence (no code calls `seon.task/create!`; runtime tasks close only by merge).

**Real example (fault 103511, open 16 h, one of default's 17):** a contract refusal in `seon.turn/record-evaluated-tx`
whose bad value came from its caller `seon.turn/system-turn` (only in "Called from …" text). The caller was fixed
(3a168b8c9) 2m45s after the last occurrence, with no regression; resolution watches the refuser, whose digest never
changes, so the fault never closes. Under this plan: blame → `system-turn` at its digest; task auto-created; the
reproduction is the recorded evaluation; the fix's proof is a test red before, green after; the producer's new digest
plus the green proof closes it.

**The loop, on existing owners only (~180 added, 130+ deleted):**
1. **A finding is a fault** (no new entity kind). `seon.fault/record!` gains: the producing definition ref + digest (by
   the blame rule, read from the stack only when recording — zero cost on ordinary calls), the reproducing turn,
   evaluation or test member, the schema path and refused schema as data, and measurements for slow findings.
2. **Task:** the first occurrence of a signature auto-creates a runtime task on the producing definition, deduped by
   signature and by open tasks on that definition, riding the fault's own write (no extra commit).
3. **Claim:** one `my.task/open` query + `seon.task/assign!`, the same queue for Seon agents and our MCP lanes (outside
   agents are Seon agents: `eval_clj` with `agent`).
4. **Fix:** on the agent's branch in SCI; per the B8 ruling a platform function is proven by calling it directly.
5. **Proof:** a test red before the fix and green after (owner ruling: required for auto-created tasks; hand-written
   tasks keep today's rule); for slow findings the test asserts call counts
   (deterministic), wall time kept as evidence beside it.
6. **Close:** derived, never a stored flag: resolved when the producer's digest changed *and* the proof tests are green
   on it; merge carries it to the cluster.

**Operator visibility:** the JVM records its own boot layers, adoption and publication phases on commits that already
happen (boot, adoption, publication records), with what each was proportional to (files, changed files, cache
hits); the bb operator passes its phase times as data in the launch request it already sends. The drill itself is under
review (owner: if it is an artifact of doing something the wrong way, delete it): see §9.

**Second real finding, from existing counters in 11–42 ms:** `seon.db/arity-base` rebuilds from the whole program on
every write revision (116 calls, 142 ms mean, 1.3 s max, ∝ 108,130 call-arity datoms); its three cache kinds report
misses = evictions and 0 bytes kept — a ~58 MB single entry is likely rejected while `decoded-edn` holds 397 of 512 MB
(hypothesis; probe in loop.md). Belongs on task `0caf995ddc22`.

## 8. Build order (REPL-first per slice, then files, then docs)

1. **Attribution at the producer** (§3 changes 1–3, §5 step 1): the refusal as data with digest and structured caller;
   blame rule; stored on existing transactions; test member → violation + executed. Proof: fault 103511's class closes
   from evidence.
2. **Findings → tasks → claim → close** (§5 steps 2–6). Proof: one auto-created task, fixed by an agent on a branch,
   red→green, merged, closed without a flag.
3. **Significance recording** (§2): histogram cells, per-digest buckets on settle/test-batch commits, test member times.
4. **Operator records** (§5 operator visibility), then query-identity cells (§3 change 5).
5. **The health value and page** (§4): `:seon.health/report` units, attribute-keyed cache, non-blocking read,
   `:pending`, HTML face; collapse the duplicate fault lists and status surfaces (§3 change 6).
Each slice lands through the ordinary loop: an agent on a branch, proof tests, merge, write-back, drill.

## 6. Found while researching (to become tasks)

- Every commit keeps ~300 KB of commit record inlining index roots (26% of each commit's cost).
- `seon.problems` `failed-tests`: 1,784 reads, 228 ms armed (its docstring claims ~60 ms).
- `seon.cluster.status/agents` 324 ms; `status/snapshot` writes a JVM thread dump to a file on every call.
- `:seon.config.flow/ping-timeout-ms` is 20 ms on default, the audit cites 3 s, the schema declares no default.
- Process cache at 92% of 512 MB; `seon.cache` cannot remove entries.
- Drill 6–15 min (base-from-scratch 185–208 s); `start --head` 24 s to ready (boot-perf lane); a drill spends ~200 s
  before static analysis could have refused it.
- `seon.db/arity-base` whole-program rebuild per write revision; arity caches keep 0 bytes (§5).

## 9. The drill: an artifact of two boot paths

`bin/seon drill` rehearses a change by building a store from scratch, moving it onto the change, and building from
scratch again (`script/seon/operator.clj:1585`). It was added after HEAD failed to boot about seven times, each from a
change proven only incrementally: vector ordering, an eid attribute, a misplaced schema key, the logging-hook contract,
indexing a ref as an entity, a derivation digest read before install, conflicting render maps. Each passed the
incremental path and failed the from-scratch one, i.e. **boot-from-files and adoption are two paths that diverge**.
AGENTS.md already says the opposite should hold: prove schema changes incrementally on a branch of a live store;
inability to adopt is a publication defect, never a reason to boot from zero. The fix is one path: a from-scratch boot
is adoption of every file onto an empty store, so a change proven by adoption on a branch of the live store (seconds)
is proven for a fresh install too, and the drill is deleted. Measured (boot-perf lane, tmp/boot-perf/): only one of the seven failures
was from-scratch-only (conflicting entity maps, from `index!`'s fresh-store branch, `src/seon/fn.clj:4185-4201`); two
failed only on existing stores; four came from weak rehearsals any `start --head` catches; six more from-scratch-only
failures in history share the class "an empty store selects different code". The reconcile writer populates an empty
store with the same 89,069 datoms after a one-line `seon.db` fix. **Root chose option A:** one writer (db.clj +8/−3,
fn.clj −58/+11 deleting the fresh branch), then `bin/seon drill` and its AGENTS.md rule are deleted; proof is
publication and adoption on a branch of the live store (now the code a fresh install runs), the long test
`fresh-population-flattens-with-its-supplied-declarations` in root's central tier, and root's batched `start --head`,
which already restores the previous commit on failure.

## 10. Warnings, and what the armed wrapper can capture

**Warnings are signals read from stored data (§2b), never recorded as entities.** A named warning schema is a
predicate over a subject's attributes (severity declared on the schema); the health view and the reply of the
evaluation that touched the subject show the matches. It is recorded only when its measurement
crosses a significance threshold (§2), deduplicated by signature, delivered beside the value of the evaluation that
triggered it (as the contract warning is today), and shown in the health view. A task is created only above a declared
severity. Nothing is a string in a log.

**What every armed call can capture, by cost tier** (measured costs from the profiling research: the bracket is
~31 ns; a LongAdder increment ~6.6 ns and flat under contention; a clock read up to 382 ns at 16 threads on macOS, so
never add one; reading the bound world allocates 32 B):
- **Always on (counter increments, no clock read, no allocation):** calls, the latency histogram, throws by class
  (throws used as control flow: `malli->datahike-attr-in` threw 73,492 times in 1.79 M calls), contract failures by
  side and Malli path, re-entry, in-flight concurrency (a gauge: contention and serialization show up as in-flight
  counts), calls per evaluation from the world sink (N+1 and repeated work: thousands of calls to one function in one
  evaluation), and functions never called since boot (dead or untested paths).
- **Sampled (1 in N calls, or the first calls of each new digest):** allocated bytes per call (thread allocation
  counter), argument and result sizes for counted collections, the dynamic caller edge (what calls what at runtime vs
  the static graph), identical-argument repeats (a memoization or repeated-work finding: what Astra hunts by hand),
  and blocking on the wrong thread (a Datahike writer or listener thread, a virtual thread pinned by a monitor).
- **On demand, for one function:** an agent asks to capture the next N calls of `f` (arguments, results, timings,
  callers) into a blob, like a debugger trace, and the capture stops itself.
- **Cache discipline as a warning, not a bigger budget:** a cache kind whose share of bytes is high while its hit rate
  is low is a finding against the function that fills it (`seon.cache/status` already counts hits, misses, evictions and
  bytes per kind). Measured 2026-09-28: the three arity kinds held 458 of 503 MB at 8–23% hit rates and evicted
  everything else. The budget stays; the finding names the derivation to make incremental.
- **Process-level warnings** (the same shape, from existing readers): heap after GC trend, the cache near its budget
  (92% today), a store commit's size, a proc that stops answering pings, a listener callback over its bound.

**Named warning schemas this gives, each with the clues a fix needs:** a p99 or allocation bucket that moved with a new digest
(the change that caused it); repeated identical work in one evaluation (the caller and the repeat count); throws used
as control flow; a function hot in production but reached by no test; contention on one function; blocking on the
writer thread; memory or cache pressure with the kinds holding it. Every tier's cost is measured on the live JVM before
it lands, parent against change.

## 7. Owner questions

1. ~~Restart~~ ruled: stored buckets are enough; accumulators restart from zero.
2. Is "no extra commits" enough, or should health rows fall under a retention cutoff?
3. Make the 300 KB commit record a task now?
4. Per-query counters keyed by query form in bounded memory, or per-function time only?
5. Should heap, ping and cache readings refresh on an idle cluster, or only on commit or reconnect?
6. ~~Blame~~ ruled: producer.
7. A fault on an agent branch: ref the branch's function entity (same digest joins the cluster's when unchanged)?
8. ~~Red-first~~ ruled: auto-created tasks only.
9. Slow findings: call counts as the done condition, wall time as evidence (recommended)?
10. ~~Drill record~~ superseded by §9 (delete the drill by making boot one path).
