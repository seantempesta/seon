---
type: plan
date: 2026-09-28
status: draft; research 2 of 4 in (substrate, flow-render); entities and loop pending
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
- **Tests are reproductions.** A test body runs through the one path, so a violation or slow call inside it is
  recorded with the test; the test is the failing proof handed to the fixing agent. (Pending: whether deftest Vars
  themselves are armed — entities research.)
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
   the next commit that touches it, or one snapshot blob at shutdown (owner question 1).
7. **Transaction metadata carries the rest** that a commit observed: violations (fn, schema, path, value blob, caller,
   test/evaluation refs) and errors, on the transaction that happened anyway.
8. **Test member run times** go into the test batch's one commit (today computed, `src/seon/test.clj:2013-2016`, never
   stored).

Never: a commit of its own, per event or per timer.

## 3. Per-entity health model

Pending entities research: for each kind, the facts its health predicate reads, what joins today (error → fn/schema/
test refs vs names), and the smallest producer change to record the link.

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

## 5. Finding → task → agent → proof → merge

Pending loop research (end-to-end example from a real stored violation or fault). Shape: violation / slow bucket /
error → finding (entity refs + reproduction form) → task on the entity, deduped by cause → agent with a branch and the
reproduction as its failing test → fix the producer in SCI, tighten its contract → green on the branch → propose →
root merges → the task closes when the measurement proves it.

## 6. Found while researching (to become tasks)

- Every commit keeps ~300 KB of commit record inlining index roots (26% of each commit's cost).
- `seon.problems` `failed-tests`: 1,784 reads, 228 ms armed (its docstring claims ~60 ms).
- `seon.cluster.status/agents` 324 ms; `status/snapshot` writes a JVM thread dump to a file on every call.
- `:seon.config.flow/ping-timeout-ms` is 20 ms on default, the audit cites 3 s, the schema declares no default.
- Process cache at 92% of 512 MB; `seon.cache` cannot remove entries.
- Drill 6–15 min (base-from-scratch 185–208 s); `start --head` 24 s to ready (boot-perf lane).

## 7. Owner questions

1. Must performance totals survive a restart (one snapshot blob at shutdown), or are settle-carried buckets enough?
2. Is "no extra commits" enough, or should health rows fall under a retention cutoff?
3. Make the 300 KB commit record a task now?
4. Per-query counters keyed by query form in bounded memory, or per-function time only?
5. Should heap, ping and cache readings refresh on an idle cluster, or only on commit or reconnect?
