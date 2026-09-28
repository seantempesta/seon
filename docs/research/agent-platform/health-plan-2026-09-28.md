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
   (none/rare/frequent), over-bound yes/no. They move only on ~2× changes, so noise never writes.
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

Never: a commit of its own, per event or per timer.

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

**The finding value** (one per problem): kind (violation, slow, error, missing contract, red test), cause
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
is proven for a fresh install too, and the drill is deleted. The boot-perf lane measures what differs between the two
paths and what the from-scratch build redoes, to cost that cut.

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
