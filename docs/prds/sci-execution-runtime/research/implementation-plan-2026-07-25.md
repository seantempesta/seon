---
type: research
status: active
tags: [research, runtime]
---

> **SUPERSEDED AS AN ORDERING (2026-07-26, owner ruling O17).** This file is
> EVIDENCE. The one ordering for this chunk lives in
> [roadmap.md](../roadmap.md) under "THE ONE ORDERED LEDGER". Seven
> orderings across six files in five naming schemes is why the previous plan
> had no referent — do not sequence work from here.

# Implementation plan, defect-ordered (2026-07-25, rewritten after the prototype)

This document decides and sequences. It was rewritten after
`flow-prototype-2026-07-25.md` measured the design and after the evening's
in-situ re-measurements; **every number in the previous revision that disagreed
with a measurement has been replaced, and the disagreements are recorded rather
than quietly dropped.**

## 0. How to read this

Companions, and what each is good for:

- `redesign-ledger-2026-07-25.md` — R-1..R-18, the physics list, ratified
  vocabulary. Still the deletion authority. Three of its rows are corrected
  below (R-5/R-9 citations, R-9's `::calls` conclusion, R-16's "done" status).
- `flow-prototype-2026-07-25.md` — D1..D16, the adversarial measurements. **This
  document's wave order comes from it.**
- `flow-design-2026-07-25.md` — **do not implement from it.** It says so itself
  at `:35-36`; three of its sentences are known false (the turn-level transform
  signature `:17-19`, "No ticker, no polling" `:100-103`, and the implied claim
  at `:57-66` that its limits bound an eval). Its line-count sentence `:55` is
  wrong on both numbers.
- `simplification-design-2026-07-25.md` — written 09:17, before the prototype and
  before the artifact landed. Four of its claims are superseded here.
- `vector-order-audit-2026-07-25.md` — actions 1 and 2 already landed as commit
  `5a37489c6`; 3, 4, 5 remain open.
- `wtf-review-2026-07-24.md` — the HEAD trace.

**Numbering.** The previous revision used `D1..D11` for *decisions* while the
prototype uses `D1..D16` for *defects*, in two files a reader opens together.
The decisions are renamed to words here. **`D1`..`D16` mean prototype defects
and nothing else.**

Item tags used throughout:

- `[READY]` — startable today, no owner ruling, no upstream item.
- `[OWNER:Ox]` — blocked on the named ruling in section 3.
- `[HEAD]` — a defect in the tree today.
- `[DESIGN]` — a defect the prototype hit; the new code must not reproduce it.
  Where HEAD has a *different* symptom for the same class, it is stated, because
  otherwise the cut wave aims at the wrong thing.
- `[UNVERIFIED]` — stated, not proven, by anyone in this session.

**Every item carries a falsifier that a faithful PORT fails.** The conversion
test is simplification, not relocation. A falsifier that a renamed old shape
could pass is too weak and must be sharpened before work starts.

## 1. The target, corrected

One process holds the database connection, **one shared SCI `ctx`**, and every
running agent. A run is a row with an epoch and a lease; when it becomes
claimable one virtual thread drives it to close. No phases, no handoffs.

Five framing rulings that were settled in conversation and appear in no other
file. They are load-bearing and belong here:

**One corpus, universal.** There is no separate concept for agent-authored code.
Quality is *attributes on the row* (`:seon.fn/spec`, `:seon.fn/schema-error`,
`:seon.fn/source-fingerprint`, `:seon.test/*`), and advertising is *filtered by
those attributes*. What is personal to an agent is its **environment** — entity,
messages, context blocks — never its code. This is already the data model:
`seon.db.program` reconciles first-party boot code into the same identity space
and separates it by *provenance*, not by a kind stamp
(`src/seon/db/program.clj:19-20`, `:40-45`). Runtime consequence: **one shared
base ctx and a fork per eval, for in-flight isolation only — not a retained
per-agent ctx.** Today's `ensure-context!` does the per-agent thing *and* never
evicts, so it is simultaneously the rejected model and the R-8a memory leak;
one deletion fixes both (`src/seon/agent/driver/host.clj:136-152`,
`src/seon/host.clj:138-160`; no `dissoc` of that map exists anywhere in `src/`).
Note that `simplification-design:245` says the opposite ("do not build a second
cache — `ensure-context!` is the owner"), so the leak is preserved by citation
unless this lands explicitly.

**Form granularity is forced, not chosen.** The identical form answered **0**
against the turn's opening basis and **9** against the step's basis, so a
turn-level transform `(db, agent, message) -> [tx-data, messages, effects]`
cannot express read-your-own-writes and was never built
(`flow-prototype:44-56`). The turn is a fold of forms; the resume unit is the
form. Read-your-own-writes costs zero extra round trips — each step's basis is
the previous step's transaction report `:db-after`, which Datahike already
returns. This is the design's single strongest simplification, it survived every
attack aimed at it, and **no recurring test claims it** (see O6).

**The flow admin surface is free, and this is the strongest single argument for
the design.** `core.async.flow` ships `start` with `:report-chan` and
`:error-chan`, `stop`, `pause`/`resume`, `pause-proc`/`resume-proc`, `ping`,
`ping-proc`, `inject` (`reference-code/core.async/.../flow.clj:107-158`). Every
one maps onto something Seon already has, and each database form is *strictly
stronger* because a query is durable, historical and available to anyone:
`ping` → a query; `pause`/`resume` → release/reclaim the run; `inject` → commit
a fact; `:report-chan` → the transaction feed; `:error-chan` → fault datoms.
Stronger still: `ping` takes `timeout-ms` (default 1000) and returns status only
"for those procs that reply within timeout-ms" — flow's own introspection
**silently omits the wedged process you most need to see**, which is exactly the
D9 case. Flow needs an admin API *because* its state is hidden in memory.
Putting state in the database does not replace that surface; **it deletes the
need for one.**

**Flow control is three cases, not one.**

| Traffic | Policy | Why, and where it is already implemented |
|---|---|---|
| agent messages | **never drop** | a message is a committed datom; delivery is one derivation whose source names itself the single truth (`src/seon/agent/message.cljc:286-307`). Losing one loses work. |
| unclaimed runs | **queue in the database** | an inspectable durable backlog; backpressure is the CLAIM (semaphore-bounded), never a refusal — measured 22 evals against 18 permits: 4 queued, 71 ms max wait, zero bounced claims |
| Datastar frames | **latest wins** | already built: `enqueue-latest!` does `(.clear mailbox)` then `(.offer mailbox value)` per connection (`src/seon/web/feed.clj:22-25`) |

Dropping is safe **there and only there** because a Datastar morph is
*absolute*, not incremental: the default `ElementPatchMode` is `outer`,
docstring "Morphs the element into the existing element"
(`reference-code/datastar-clojure/.../consts.clj:46-48`, `:79-82`), so any frame
supersedes every earlier one — the same property as reconnect = repaint.
Datastar *does* have incremental modes (`:62-77`), so the property belongs to
Seon's usage, not to Datastar: `src/seon/web/feed.clj:40` is the sole patch call
under `src/seon/web/` and `src/seon/ui/` and passes no opts. Name the buffer
what core.async names it — a **sliding buffer of one** ("oldest elements in
buffer will be dropped", `async.clj:125-129`), never a dropping buffer
(`:119-122`, which discards the *newest*).

**Performance is not the problem.** One 7-step turn measured ~104 ms/step of
which SCI eval is 0-5 ms, across 12 transactions per turn, before an LLM call
that dwarfs everything (`flow-prototype:129-131`). SCI is ~5% of a turn, so the
JIT, accretion, and every compiled-tier proposal optimize 5%. The only real
performance problem is boot, which is orthogonal to the architecture. Write this
sentence down or the numbers read as an argument *for* a compile tier.

**Two caches at two levels, unrelated.** (a) AOT+AppCDS caches Seon's own
compiled JVM classes at process boot and changes only when Seon is rebuilt.
(b) The SCI base ctx caches agent-visible namespaces, built ~80 ms after boot.
**Agent-authored functions can never enter AppCDS** — `effective-tier` returns
`:nursery` unconditionally (`src/seon/host/graduate.clj:128-134`), so every
corpus function executes through SCI as a `:seon.fn/source` row and never
becomes JVM bytecode.

## 2. Decisions

Named, not numbered. Status column: `settled` = evidence exists;
`blocked` = needs a ruling in section 3.

| Name | Decision | Kills | Status |
|---|---|---|---|
| one-tier | Agent code runs **only** on the cluster JVM. | the pod as agent loop; `loop.cljs`/`run.cljs`/`turn.cljs` | settled |
| two-mechanisms | Containment is one `:interrupt-fn` (time) + process replacement. | step budget, watchdog, interrupt predicate, cancel dance — 5 of 7 | settled |
| time-only | `time-limit` is the only limit. `:seon.eval/fn-entries` is a **recorded diagnostic**; there is no fn-entry limit. Long computations are declared. | `interpreter-step-budget` and its calibration problem | settled |
| allocation-diagnostic | `:seon.eval/allocated-bytes` is recorded, **not enforced**. D14 proved the metric measures cumulative throughput, anti-correlated with the heap risk it appears to bound. | the memory "limit" implied by `flow-design`'s Monitoring section | **blocked O4** |
| platform-eval | The SCI eval runs on a **platform** thread; the *fixed count* becomes a semaphore. | `default-eval-threads 10` (`src/seon/host.clj:61`) as a hard cluster ceiling | settled on justification (b); see O4 |
| one-writer | One write connection per store. Cluster JVM forward writes. The unsafe configuration **refuses to open**. | the silent multi-writer corruption of D1 | **blocked O2** |
| no-placement | No placement derivation. Every package runs in a leaf. | `plan-execution`, `execution-plan-disposition`, the package-prefix hand list | settled |
| result-symbols | Tier-local values cross as result symbols named by `(pid, start-instant)`. | all crash-recovery code for handles — dead by construction | settled; interacts with O3 |
| one-wire-predicate | One predicate. `ordinary-wire-value?` fixed, `persisted-value?` merged into it. | the `pr-str` degradation path, the dead `try/catch` encode predicates | settled |
| one-convention | `(ns/fn {namespaced-map})` → one response shape; `seon.result/ok?` to `.cljc`, total. | 27 discriminators, 4 failure conventions | settled |
| accretion | Availability derives from test evidence at a source fingerprint. **A fast tier never removes the `:interrupt-fn`.** | `:nursery`/`:graduated`; `:seon.fn/execution-tier` — **but see the same-commit companion in Wave 5** | settled in principle, substrate blocked |
| accretion-is-interning | "A proven fn becomes available to every agent" = **which namespace the var is interned in**. Ordinary Clojure. Compiling agent code to a native JVM fn deletes the `:interrupt-fn` and must never happen. | the speed framing of accretion | settled (owner) |
| ownership | `:seon.ns/owner` assigns **responsibility for failures**, not write access. Proposals evaluate everywhere immediately. | the write-lock design that conflicted with cross-namespace proposals | settled; **note the attribute does not exist yet** (`src/seon/ns/source.cljc:17,:19-37,:45,:46`) |
| waiting | A waiting run is an **open, unclaimed** run; readiness is a clause of `eligible?`. | correlation attributes, counters, the concurrent-owner race | settled |
| sci-namespaces | `seon.sci.interrupt` / `seon.sci.ctx` / `seon.sci.eval`. | `seon.host.guard` and its vocabulary | settled |

Vocabulary corrections to the ratified table (each row's citation was checked
against the file and three were wrong):

- `time-limit` is **defined** at `reference-code/sci/doc/interrupt.md:26`; `:32`
  is a usage line.
- `:io`/`:compute`/`:mixed` are **defined** in `thread-call`'s docstring at
  `reference-code/core.async/.../async.clj:561-563`. `impl/dispatch.clj:127-133`
  only *constructs* the executors. A borrowed word must cite where the
  dependency defines it.
- the visibility attribute is `:seon.fn/private?` **with the question mark**
  (`src/seon/schema.cljc:423`, `src/seon/host/record.clj:154`). The ratified row
  spelled it without, making the ratified row itself an invented name.
- `"safepoint"` stays **banned** — a JVM safepoint is a different real thing
  (GC). SCI's own phrase is "every `fn` body entrance"
  (`reference-code/sci/doc/interrupt.md:50`).
- **`accretion` / `breakage` and "require no more, provide no less" are
  `[UNVERIFIED]`.** The attribution to Rich Hickey's Spec-ulation (Clojure/conj
  2016) was not confirmed by the lane assigned to it and was not confirmed in
  this session; no vendored source under `reference-code/` contains the talk.
  The *words* may be used; the *citation* may not, until confirmed against a
  primary source. It is already asserted as fact in the committed tree at
  `docs/seon/issues/output-map-closedness-decides-accretion-legality.md:15-16`,
  which must be marked in the same pass or the marker is defeated by a
  repository document.

## 3. Open owner rulings

Each names what it blocks. Nothing in Wave 0 or Wave 1 depends on any of them.

**O1 — Co-located writer heap blast radius.** Nothing in-process bounds live
memory, and a single host allocation is uncatchable. What was **measured** is a
*refused oversized allocation*, twice: `(byte-array 2000000000)` under `-Xmx512m`
surfaced as a flat error value and the very next `d/transact` committed; a
retained-1GB attack reproduced it; the transactor survived with 0 errors, 94
further commits, a store consistent on reopen, one 180 ms latency spike against
a 33 ms median. What was **never reproduced** is sustained heap exhaustion by
retention across many threads. The honest sentence, which must replace
`simplification-design:478-486`'s "an agent OOM restarts the whole cluster":
*a single oversized allocation is refused and survivable (measured); sustained
retention is unproven and is bounded by no in-process metric.*
*Recommendation: accept, with that sentence and a named exit — process
isolation, per `reference-code/sci/doc/interrupt.md:85`.* Blocks: nothing
directly; it is the honest framing O4 depends on.

**O2 — Horizontal cluster JVM topology.** `architecture.md:242-246` promises
interchangeable cluster JVM. Two live JVMs on one file store both won the same
epoch CAS and **40 of 40 of the parent's successfully-returned commits vanished**
with zero transact errors and a store that looked pristine on reopen (D1).
*Recommendation: change `architecture.md`, make the unsafe configuration refuse
to open, and record the concrete exit — `:datahike-server`
(`reference-code/datahike/src/datahike/http/writer.clj:35`) for WRITES plus the
existing `seon.db.host` interest transport for WAKE, necessary because
Datahike's own `listen` is declared `:supports-remote? false`
(`api/specification.cljc:1073`).* Not new machinery; it is what the pod already
uses. Blocks: Wave 2's `one-writer` item.

**O3 — Replace the coinage "cluster JVM" with `:seon.agent.run/process`?**
"cluster JVM" has zero hits in the vendored Datahike checkout against 25 files
under `src/`. `:seon.agent.run/process` is grounded on both sides:
`script/seon/dev/process.clj` already carries a process record with
`(pid, start-instant)` and a generation, matching JDK `ProcessHandle`.
*Recommendation: ratify, in the same change as the result-symbol identity, so
one word covers process identity in both places.* Cost: a mechanical rename
across 25 `src/` files plus tests and docs, which must be done atomically at the
top level, never by a lane. Blocks: nothing; it spreads with every new file.

**O4 — Is there an allocation LIMIT at all? This decides the thread kind and it
exists in no document.** The owner ruled `time-limit` is the only limit and
`fn-entries` is a diagnostic. D14 shows the allocation metric measures
*cumulative allocation*, not live footprint: it killed a harmless
`(reduce + (range 500000))` at 20 ms and missed a retained 1GB that OOM'd the
JVM. If no allocation limit is authorized, the platform thread buys only the
`:seon.eval/allocated-bytes` **diagnostic** — a time flag is a volatile boolean
readable on any thread. *Recommendation: keep allocation as a recorded
diagnostic, delete it as a limit, and still keep the platform thread on the
second, independent justification: agent code on a platform thread has no
carrier-pinning surface (verified with `parallelism=1` and 8 cluster JVM wedged
inside evals, an unrelated virtual thread still completed 5/5 steps in 902 ms).*
State the consequence loudly: at the default cap **every** interpreted runaway
is currently reported `:memory` in ~12 ms, which makes ":time with few fn
entries = blocked in a host call" — the single most diagnostic string in the
design — effectively unreachable. Removing the cap as a limit restores it.
Supporting fact: interpreted SCI allocates ~48.1 bytes per fn entry at ~5.2 GB/s,
so a 64 MB cap is a **work budget** of ~1.4M interpreted steps, not a heap bound.
Blocks: Wave 2's `allocation-diagnostic` and the final thread-kind wording.

**O5 — Which tuple trigger survives the bridge reconciliation?** The JVM builder
(the installation owner) makes tuple ⟺ float inner type alone
(`src/seon/db/datahike/schema.clj:163-166`, `:196-198`); the portable derivation
makes it tuple ⟺ the computed `:db.secondary/only` property
(`src/seon/db/internal.cljc:160-176`). *Recommendation: keep the portable rule,
delete the float trigger.* It is the property that is actually true, it is the
same property the recurring invariant test keys its exemption on, and "tuple
because the value type is float" is a hand rule about a value type. Latent today
because the only registered float vector is secondary-only
(`src/seon/embed.clj:174-175`); the failure if left is silent — a non-secondary
`[:vector :float]` installs as a cardinality-one `:db.type/tuple` and throws at
Datahike's 8-value homogeneous cap
(`reference-code/datahike/src/datahike/db/transaction.cljc:1006-1012`).

**O6 — Where does `src-flow-prototype/` live and which runner claims it?** It is
checked in, claimed by no runner (`bin/test-cljs`, `bin/test-writer`,
`bin/seon test operator` all miss it), has no Malli schemas, and ~40 `store-a*`
directories sit in the working tree. By the repo's own rule a proof invisible to
every runner counts as **NOT COVERED**, so D1..D16 are currently evidence, not
coverage — and not one of them is filed under `docs/seon/issues/`.
*Recommendation: claim the fatal-defect regressions under `bin/test-writer` and
delete the rest.* Minimum surviving set: D1, D2, D5, D6, D9, D10, D11, **plus
the 0-vs-9 basis measurement**, the load-bearing proof that form granularity is
forced, which today no test claims and which exists only as prose in two
documents. Remove the checked-in store directories regardless.

**O7 — Commit the uncommitted AOT/CDS work now?** `build.clj`,
`script/seon/dev/{artifact,process,config}.clj` and three test files are
working-tree-only, and the artifacts they produce live under `tmp/`, which
`.gitignore` excludes. *Recommendation: commit immediately, path-limited.* This
is the 10,293 → 3,886 ms boot win and a branch switch or reset loses it with a
full writer AOT compile as the rebuild price. Three non-negotiable companions:
(a) a **determinism re-check**, because commit `be30f420` REMOVED AOT precisely
because two clean builds from identical inputs produced different normalized
digests (`docs/seon/issues/archive/writer-uber-aot-is-nondeterministic.md`);
(b) **pruning** of `tmp/seon-jvm-artifacts` — 297 MB after two builds 19 minutes
apart, with the prune idiom already at
`script/seon/dev/test_artifact.clj:184`; (c) **one live `bin/seon up`** proving
an `-Xshare:on` argv, since readiness is proven only by arithmetic over the
manifest today and no process record on disk shows the flag.

**O8 — The release-path AppCDS hole.** `process-artifact`'s else-branch builds a
fresh manifest map that omits all five writer publication keys
(`script/seon/dev/process.clj:267-318`), so `jvm-publication-status` (`:425-460`)
compares `nil` against real digests, always mismatches, prints one loud line, and
`jvm-family-argv` (`:462-487`) falls back to a plain `-cp` launch with no
`-Xshare:on`. The manifest schema marks those keys `{:optional true}`
(`script/seon/dev/artifact.clj:80-86`, from commit `14ce293b8` "Keep CDS manifest
fields backward compatible"), so such a manifest **validates**. Every non-source
deployment silently pays ~10.3 s boot while development enjoys ~3.9 s — the exact
divergence that makes a fast dev loop lie about production. *Recommendation: fix
by EMITTING the five keys, not by tightening the schema* (that would break the
compatibility the cited commit deliberately preserved). It is in no issue file
and no roadmap; that is the actual risk.

## 3.5 Build order after the cut (owner method, 2026-07-25 night)

> "After the old is gone build it up in pieces so you can test each piece. Get
> the clusters healthy and normal with the db and then start building. Do as
> much as you can in parallel when you see straightaways, and slow down on the
> curvy dangerous parts."

Two rules fall out, and the second is the one people get wrong: caution is
**spent where the road bends**, not spread evenly. A team that is careful
everywhere is slow everywhere and still crashes on the curve.

### First, and alone: get a cluster healthy

Nothing below can be tested until a cluster boots. **Known blocker:** pod
admission fails with the default database initialization stuck `:in-progress`
at 93 pages (found by Wave 0, undiagnosed). This is a curve — a boot that
half-succeeds is worse than one that fails, because everything downstream then
lies. Fix it, prove `bin/seon up` reaches ready, and only then build.

Clusters are cheap and disposable (O9) — do this on a throwaway, reset freely,
never migrate.

### Straightaways — run these in parallel

Each is independently testable with no database and no cluster, so a lane can
own one end to end and prove it alone:

| piece | proves itself by |
|---|---|
| `seon.sci.interrupt` | kills on `time-limit`; kills on allocation; records `fn-entries`; the marker survives `try`/`catch`. All measurable on one thread, no database. |
| `seon.sci.ctx` | fork isolates new defs; the base-vars-are-immutable invariant holds; fork cost stays ~539 bytes. |
| the attack suite → `test/` | mechanical relocation of proven tests. |
| vocabulary and doc reconciliation | text only, collides with nothing. |

### Curves — one at a time, with a falsifier before the edit

Every one of these is a place where a wrong answer is *silent*:

| piece | why it bends |
|---|---|
| the database init stall | a half-initialized cluster makes every later result untrustworthy |
| `seon.sci.eval` | needs interrupt + ctx; D7 (`read-string` honours `*read-eval*`) and D8 (sampling bounds nothing) both live here |
| the driver's claim/CAS | D3 receipt identity — a wrong key let one step run **704 times** while receipts read clean |
| D1 write connection | two writers on one store silently destroyed each other's history |
| D6 read-modify-write | 39 of 40 concurrent updates lost with **no error at all** |
| D2 the wake path | a lease expiry is not a commit, so `listen!` structurally cannot deliver it |

The tell for a curve: **the failure produces no exception.** Where a mistake
throws, go fast — the system tells you. Where it silently returns a wrong
answer, slow down and write the falsifier first.

### The order

1. cluster healthy (curve, alone)
2. `interrupt` ‖ `ctx` ‖ attack-suite relocation (straightaway, parallel)
3. `eval` (curve)
4. driver claim/CAS + D1 + D3 (curve, one at a time)
5. receipts + ordinal, D2, D6 (curve)
6. **one live turn** — the gate

## 4. Waves, in defect order

A wave lands completely before the next begins (cut first, seam-fix second).
Correctness defects precede capability, per the prototype's own instruction to
implement from the defect ledger rather than the design document.

### Wave 0 — velocity and free deletion `[READY]`

Nothing here depends on an owner ruling or on any other item. All can run in
parallel.

| Item | Falsifier a port would fail |
|---|---|
| **AOT+AppCDS for the writer closure.** The artifact **already exists and is current**: `tmp/seon-jvm-artifacts/dee7cc11…/seon-jvm-aot.jar` (53,779,158 B) and `.jsa` (101,990,400 B), published 18:25, sha-256s matching `tmp/seon-artifact-build/writer.edn` and `tmp/seon-operator/artifact.edn` (version 14). Remaining work is O7's commit + determinism + prune + live proof, and O8's release path. | A live `bin/seon up` whose writer process record on disk shows `-Xshare:on` and `-XX:SharedArchiveFile=`, **and** a rebuild from identical inputs producing an identical normalized digest. Reporting the manifest arithmetic as proof is the port failure — that is exactly what commit `be30f420` learned the hard way. |
| **`:interrupt-fn` cost fix.** Closure over a `long-array` plus **one volatile read**, not a map re-destructure and not `sci.ctx-store/get-ctx`. Owners are BOTH `src/seon/host/guard.cljc` and `src/seon/host/context.clj:1410-1413`. | Re-measure in situ over ≥3M real SCI fn entries and quote the *agent-fork* path, which is the one agents pay. Any report quoting 44× fails; any report that does not distinguish the two `:interrupt-fn` shapes fails (see below). |
| **Clojure 1.12.0 → 1.12.5.** `deps.edn:6,20` pins 1.12.0; the writer classpath resolves 1.12.0; `1.12.5` is present in the local maven repository so the bump resolves offline. **`[UNVERIFIED]`: no document in this session records a measured benefit.** | `bin/test-writer` green at 1.12.5 **and** a re-run of the boot breakdown at the same flags. If neither moves, record it as hygiene, not as a lever — claiming a boot win from it without the pair of numbers is the port failure. |
| **Zero-caller deletions — re-verify each unit today.** The previous revision's "~1,160 lines, zero `src`/`test` callers" is **wrong for at least two of its three units**: `seon.runtime.recovery` is required by `src/seon/client.cljs:102` and two tests, and `loop.cljs`'s `transitions` has an in-file caller at `:62`. | For each unit, `rg` shows no caller **before** the cut and the suites stay green **after**. A wave that deletes on the strength of a stale list fails; these two ride the pod cut in Wave 4 instead. |
| **Delete the broken recovery arm** (5-arg call to a 7-arg `run-eval-batch!`, `storage-view` in the `run` position, `driver/host.clj` ~700). Filed: `settle-eval-replay-arity-mismatch.md`. | Kill a cluster JVM after `:reply-ready → :evaling` with zero receipts and observe a second cluster JVM replay. Deleting the arm without that observation is the port failure — the arm exists for a case nobody has run. |
| **Correct `src/seon/agent/AGENTS.md`**, which still documents the dead FSM as the loop core. | The file describes `driver.cljc` and names no `transitions` table. |
| **File the three unfiled findings** (see section 7): the cluster JVM's `:all` listener, the fixed-pool drain, and the release-path AppCDS hole. Update `docs/seon/issues/index.md`, which lists 133 of ~146 real issue files. | The index names every 2026-07-25 finding. |

Measured for the first two items, so the wave has a baseline:

- Boot, JDK 26.0.1, `-Xmx2g`: **10,293 ms source → 3,976 ms AOT-only → 3,886 ms
  AOT+archive**. `datahike.api` alone **6,568 → 755 → 300 ms**. **AOT contributes
  92.7% of the saving, AppCDS 7.3%.** AppCDS caches class *loading*; the 6.1 s is
  *compilation*; only AOT skips compilation and AppCDS then caches the result.
  "AppCDS targets it directly" was an overclaim and must not be repeated. The
  residual 3,886 ms is 63% the three namespaces **not** AOT-compiled (`sci.core`
  825 + `host.context` 900 + `db.writer` 723 = 2,448 ms); the blocker is named at
  `build.clj:105-137` (SCI's `copy-vars` asserts during compilation).
- `:interrupt-fn` in situ over 3,000,001 real SCI fn entries (median of 5, 3 warm
  runs): none **24.6 ms**; agent-fork shape **73.4 ms** (16.3 ns/entry, 2.98×);
  production base ctx-store shape **116.4 ms** (30.6 ns/entry, 4.73×); target
  closed-over `long-array` + one volatile read **34.5 ms** (3.3 ns/entry, 1.40×).
  **Quote no ratio above ~3× for the agent path.** `0.204 ns/step` from the
  original issue is below one memory operation — a JIT-eliminated loop. The
  independent `29.857 ns/check` from `u1-fuel-calibration-2026-07-23.md:64-71`
  agrees with 30.6.
- **The finding nobody had: two `:interrupt-fn` shapes exist and every prior
  analysis conflated them.** `build-base!` installs a closure doing
  `(sci.ctx-store/get-ctx)` plus two keyword lookups per entry
  (`context.clj:1410-1413`); `fork-context` **overwrites** `:interrupt-fn` on
  every agent fork with a closure over the holder (`context.clj:1423-1430`).
  Agent evals never pay the ctx-store deref, worth 14.3 ns/entry — 47% of the
  production overhead. Base-ctx evaluation does.
- **`parkNanos` costs nothing on either thread kind, and less on a platform
  thread**: 3.6 µs/call platform, 11.9 µs/call virtual; over 3,000,001 fn entries
  at one park per 65,536 entries (45 parks) the delta was virtual +0.7 ms,
  platform −7.3 ms — noise on both. **`simplification-design:205-208`'s "~1.4 ms
  each on a platform thread", and the deletion argument built on it, are void.**

### Wave 1 — containment defects `[READY]`

These are correctness defects in the *containment* mechanism, most of them live
in the tree today. Nothing downstream is safe until they close.

| Item | Where | Falsifier a port would fail |
|---|---|---|
| **D7 — agent source is evaluated at READ time, walking around SCI entirely.** `[HEAD]` The mechanism is `clojure.tools.reader`'s **own** `*read-eval*` — a *different var* from `clojure.core/*read-eval*` — defaulting to true with `#=` wired as a dispatch macro (tools.reader 1.5.2 `reader.clj:879-895`, `:816`, `:591-595`). `record/read-forms` (`src/seon/host/record.clj:36-58`) binds only `*ns*` and `*alias-map*`; `read-host-form` (`:77-87`), `host/eval.clj:477-481` and `host/context.clj:1033-1040` bind nothing. | `tools-reader-evaluates-agent-source-at-read-time.md` | `(defn f [] #=(clojure.core/spit "<path>" "…") 1)` writes no file. Reproduced today: it *did* write the file and returned `(defn f [] nil 1)` — zero fn entries, no ctx, no `:classes`, no receipt. **`read-forms` is gated on `(= :form kind)`, NOT on `ok?`**, so a form SCI already REJECTED is handed to tools.reader anyway. A fix binding only `clojure.core/*read-eval*`, or merely reordering behind `ok?`, does not close it and fails this falsifier. |
| **D8 — the 1024-entry sample means the limits do not bound an eval.** `[BOTH]` Reproduced: the nested-bigint form ran **319 fn entries, 8,867 ms against a 500 ms limit, 99,341,268,032 bytes against a 64 MB cap, outcome `:ok`** — the agent received its answer. **The fix is proven, not projected:** reading the time flag as a **volatile on every fn entry** (allocation still sampled at 1024) killed the same form `:time` at 505 ms with 99 fn entries. | `core-hof-forms-bypass-the-guard-safepoint-entirely.md`, `eval-deadline-interrupt-swallowed-by-database-call.md` | The same form is killed `:time` at ≈ the limit. A design that keeps a single sample cadence "for cost" fails; the cost is a volatile read against ~48 bytes/entry the interpreter already allocates. |
| **State the hole rather than implying a bound.** `[DESIGN]` `(alength (byte-array 200000000))` allocated 200,033,752 bytes in **1 ms with 0 fn entries** and outcome `:ok` under both a 500 ms limit and a 64 MB cap. **Nothing can see it.** No in-process metric expresses "this agent must not exhaust the heap" — that is the process boundary, exactly as `reference-code/sci/doc/interrupt.md:85` says. | `eval-process-isolation-memory-containment.md` (update it; it is written in the CLJS-pod framing) | The agent-facing docs and the design both state the single-host-call ceiling explicitly. `flow-design:57-66` and its Monitoring section imply a bound; a document that keeps that implication fails. |
| **D9 — one poisoned form drains the pool permanently.** `[BOTH, different symptoms]` Prototype: `(host/block 600000)` drained every permit. **HEAD is different and worse in one way**: no semaphore at all, a **fixed 10-thread platform pool** (`src/seon/host.clj:61`, `:332-333`), and `cancel-active!` waits 2 s on the `Future` then **walks away leaving the thread running** (`src/seon/host/invoke.clj:280-283`). Ten wedged evals exhaust the cluster permanently with no queue, no permit accounting, no signal. Better in one way: no lease-steal-into-the-same-step path, so a wedged eval cannot be re-fed to the next victim. | **unfiled** — named today only inside R-3's evidence list | Wedge N evals; cluster capacity degrades by exactly N, not to zero, and a query names the wedged step. The rule that keeps `:compute` truthful against its own definition ("must not ever block", `async.clj:562`): **an agent-initiated blocking call RELEASES the `:compute` permit while it waits.** A design that adopts core.async's word without that rule fails. |
| **Arm the `:interrupt-fn` on the compute thread, not the `:io` caller.** `[DESIGN]` Arming on the caller reported 183 KB for a run that allocated ~67 MB and misattributed a `:memory` kill as `:time`. | | Kill a `:memory` case and read the reported bytes; they match the eval's actual allocation. |
| **`policy-error!` files a resource event as an agent mistake.** `[HEAD]` `src/seon/host/guard.cljc:149-150` hardcodes `:seon.error/fault :agent`, so a `time-limit` or output-cap trip is recorded as an agent coding mistake. | | A `time-limit` trip produces a fault whose `:seon.error/fault` is not `:agent`. |
| **D15 — agent code cannot name a catchable class.** `[HEAD]` Verified: `Throwable`, `Error`, `RuntimeException`, `StackOverflowError` and `:default` all fail with "Unable to resolve classname"; only `Exception` resolves. `StackOverflowError` reports a nil message. | | Idiomatic `(catch Throwable t …)` resolves, and a `StackOverflowError` reports its class name. |

Two facts this wave must not lose, because they bound what the mechanism is
worth:

- **The tree's base merges BOTH interrupt-aware namespaces**
  (`src/seon/host/context.clj:1405-1406`), so idiomatic agent Clojure **is**
  metered. Measured against the real merged base:
  `(reduce + (map inc (range 1e6)))` **1,999,999** fn entries;
  `(count (filter even? (range 1e6)))` 1,500,000; `(sort (vec (range 300000)))`
  300,000; `(frequencies (range 200000))` 200,000; `string/join` 100,000;
  `string/replace` over 200k chars 100,000; `string/split` 300,000;
  `(apply + (repeat 1e6 1))` 1,000,000. The `core-hof` issue's headline
  ("0 safepoints … no in-process bound at all") measured a **bare** ctx merging
  neither and is false for this tree; it has been corrected in place. **D16 is
  prototype-only** (`src-flow-prototype/src/flow/ctx.clj:20` merges core but not
  string); carry it forward only as an invariant `seon.sci.ctx` must not regress.
- **`sci/interrupt!`'s marker holds.** `reference-code/sci/src/sci/interrupt.cljc:32-42`
  states the contract and the prototype confirmed agent code cannot swallow it.

### Wave 2 — loop and topology defects

| Item | Ruling | Falsifier a port would fail |
|---|---|---|
| **D1 — one write connection per store; the unsafe configuration refuses to open.** Two live JVMs on one file store both won the same epoch CAS; **40 of 40** of the parent's successfully-returned commits vanished with zero transact errors and a pristine-looking store. Restate the physics item too: `create-writer`'s defmulti is at `writer.cljc:282` with `:self` at `:286` **and** `:datahike-server` exists at `http/writer.clj:35` — "ships only `:self`" is false as written. | `[OWNER:O2]` | Start two cluster JVM against one file store; the second **refuses to open**. A port that documents the constraint without enforcing it fails — D1's failure was silent and left a pristine store. |
| **D5 — the wake path is a positive feedback loop.** `[HEAD]` `scan!` commits; every commit fires `listen!`; every `listen!` submits a new `scan!`. Measured commits/useful-run **7.0 → 14.4 → 124.8**, lost CAS claims **5 → 157 → 10,343**, OOM at n=20 after 2,555 scans. **The fix is a parameter of the existing mechanism**: `seon.db.host/listen!` already accepts `::protocol/datom-patterns` (e/a/v/added?, max 64, `src/seon/db/protocol.cljc:595-601`) and the writer maintains a `::by-attribute` interest index (`src/seon/db/writer.clj:2860-2878`, `:2900-2905`); the cluster JVM passes the worst option, `:datahike.read/dependency-plan :all` with `(fn [_] (scan!))` (`src/seon/agent/driver/host.clj:809-814`). | `[READY]` | Commits per useful run is O(1) in agent count. **R-16 loses its "done" status and `flow-design:100-103` loses "No ticker, no polling"** — a port that cites `driver/host.clj:807-815` as already-correct fails by citation. |
| **D2 — a stale lease is structurally undeliverable by the commit feed.** `[HEAD]` Datahike's `listen` fires "on each transact" only (`api/specification.cljc:1076`), so a lease going stale — which is not a commit — can never be delivered, and the moment it matters is exactly when the feed goes silent (measured: stranded four lease periods until an unrelated commit arrived). Compounding: **the lease is never renewed during a drive** — `beat-tx-data` (`src/seon/agent/run/core.cljc:160-170`) is reachable only through `claim-plan`'s `:held` arm (`:138-142`), and `drive-claim!` (`src/seon/agent/driver.cljc:517-594`) never beats. With `stale-ms` 1,200,000 (`config/system.edn:619`) a healthy run driving > 20 min is stealable. | `[READY]` | Prefer changing the interface so the claim **publishes its own liveness**. This is the legitimate case for a loud last-resort backstop whose firing is itself a bug report; a design that keeps a bare periodic scan as the primary path fails. |
| **D3/D4 — position, fencing, and duplicate detection.** `[BOTH, inverted symptoms]` D4 is **prototype-only**: `run-fence` (`src/seon/agent/run/core.cljc:38-47`) already emits the epoch CAS plus the agent-pointer CAS on every phase/work transaction. D3's *mechanism* is prototype-only too — HEAD's terminal transition is CAS-fenced `:running → :running` (`src/seon/eval/receipt.cljc:90-100`) so double *recording* is prevented. **HEAD's symptom is the inverse and equally bad**: `:seon.eval/id` is a generated `::db.id/compact-value` (`receipt.cljc:48`), so HEAD cannot destroy a receipt **and cannot detect a duplicate execution either**, having no index at all. | `[READY]` | "Form 3 of 7" is answerable by query, and a step with a terminal receipt at its index is never re-executable. A port that keeps a generated eval id fails both halves. |
| **The committed ordered step plan, with its race fixed in the same design.** The step plan is the only *proven* variant: it modelled preflight splicing (6 emitted entries → 7 executed forms) and answered "total 7" throughout where a reply re-parse answers 6; resume was correct at six kill positions plus a double kill (converged at epoch 3), one re-execution per crash (8 evals for 7 steps); SIGKILL inside `d/transact` at 8 points over 200-datom transactions produced **zero** torn transactions. **D11 is a mandatory companion, not a separate defect**: `start-run!` is check-then-act and spliced two model replies into one 7-step plan `BBBBAAA` in 3 of 12 trials, the window being the model call. | `[READY]` | Gate the insert on a `:db/cas` or write the plan as one cardinality-one value. **Reject** the per-receipt remaining vector (no race, but rewrites the tail every step). **`:seon.eval/position` is SUPERSEDED, not to be implemented** — the repo already owns three ordering idioms (`:seon.error.frame/index`; the terminal status datom's transaction id; `(juxt :seon.eval/at :db/id)`) and a fourth is the banned parallel mechanism. The design's `:seon.eval/index` + `:seon.eval/total` is the same fact under a different name; **reconcile the name**. |
| **D6 — read-modify-write loses updates silently.** `[DESIGN]` 40 concurrent 1-step runs for one agent produced 40 `:ok` receipts and a counter of **1**. Nothing reported a problem. | `[READY]` | Emit `[:db/cas eid attr seen (inc seen)]` and re-run against a fresh basis, **or stop storing it** — the repo rule is derive projections instead of storing them, and this counter is exactly `(count receipts)`. A port that stores the derived value and adds a retry fails the simplification test. |
| **D10 — agent-returned tx-data reaches the database unfiltered.** `[DESIGN]` A poisoned fact detonated in the driver's own `d/transact` and the exception escaped `drive-run!`: run left open, receipt stuck `:running`, **no fault recorded anywhere**. Hostile-but-valid facts wrote 424242 into another agent's counter. | `[READY]` | Violates "Nothing throws into the agent loop." Commit the step transaction inside a try; on failure commit a **terminal** receipt carrying the fault, alone, which both records it and kills the pill because resume advances past a terminal receipt. |
| **D12 — `resume` uses a count as a position.** `[DESIGN]` With a hole in the receipt set, `resume` answered `{:total 7, :next-index 5}` — 3 and 4 skipped forever. | `[READY]` | Next-index is the first index in `(range total)` with no terminal receipt. One line, and it also removes the in-flight special case. |
| **D13 — message identity upserted an earlier message and killed a cycle after 3 hops, silently.** `[DESIGN]` | `[READY]` | Derive message identity from the sending receipt `(run, index, epoch)`. **Design D3 and D13 together**: a deterministic id is exactly what keeps delivery idempotent under re-execution. |
| **`allocation-diagnostic`: record, do not enforce.** | `[OWNER:O4]` | With the cap removed as a limit, an interpreted runaway reports `:time` with a large `fn-entries` count, and a blocked host call reports `:time` with a small one. Today the default cap makes the second message unreachable. |
| **Delete the fixed eval-thread count; keep the platform thread.** `default-eval-threads 10` becomes a semaphore (measured: 22 permits against 18 → 4 queued, 71 ms max wait, zero bounced claims). | `[OWNER:O4]` for the final justification wording | Cluster concurrency is a config fact, not a constant, and a wedged eval degrades capacity by one. A port that renames the pool fails. |
| **Delete the per-agent ctx retention.** `[HEAD]` One shared base + fork per eval; no `:seon.host/contexts` map. Two independent implementations fill it (`driver/host.clj:136-152`, `host.clj:138-160`) and nothing removes an entry; each retained ctx carries exactly one guard holder, so per-agent concurrency shares the step counter and the interrupt cell. | `[READY]` | After the cut, `rg` finds no per-agent ctx map. A port that adds an eviction policy to the same map fails — the retention *is* the rejected model. |

### Wave 3 — the wire and the missing primitive

Everything after this depends on these three.

| Item | Falsifier a port would fail |
|---|---|
| One wire predicate; delete the `pr-str` degradation path and the unreachable encode `try/catch`. | `(map inc [1 2 3])` crosses as `(2 3 4)`; a sorted-map either crosses sorted or is **reported**, never silently coerced. A port that keeps two predicates "for compatibility" fails. |
| Result symbols named by `(pid, start-instant)`. | The browser drill — launch, new-page, click — works across three calls; killing the leaf turns the next call into a flat `:seon/error`, not a hang or a stale object. **Zero crash-recovery code for handles**; if the port needs recovery code, the naming is wrong. |
| `seon.result/ok?` to `.cljc`, total across fs/shell/web/db. | `rg '/ok\?'` finds one. |

### Wave 4 — the cuts

| Item | Falsifier a port would fail |
|---|---|
| Delete placement derivation entirely. | `rg 'plan-execution\|execution-plan-disposition'` returns nothing, **and** a reply mixing a JS-package call and a JVM-package call executes. Optimizing it instead of deleting it fails. |
| Port render to the JVM (~1,273 CLJS-only lines) — **labelled a PORT**, judged on simplification. | `invoke.clj:167-172`'s refusal is deleted; one turn = one claim, one process, **zero** handoffs. Count claim transitions per turn; the number is 1. Correction to R-15: `agent/ctx/namespaces.cljc` and `agent/ctx/menu.cljc` are **both `.cljc` with `.cljc` consumers** — "every consumer is `.cljs`" is wrong and inflates the estimate. The real blocker is the render refusal. |
| Delete the pod agent loop and the second IPC path. `seon.runtime.recovery` and `loop.cljs` ride here, not in Wave 0. | `rg` finds no frame/session/channel vocabulary for agent code. |
| De-async the portable driver. | Zero `^:async` and zero `await` in `driver.cljc`; the 22 private `(defmacro await [v] v)` copies are gone. **Note the correction**: modern core.async `go` does *not* impose async contagion on this JDK — `go*` expands to `(thread-call … :io)`, a virtual thread with no state-machine transform (`async.clj:519`, `:530`). `^:async` in CLJS is a real transform; `go` here is not. |

Expected: 468 of 493 reader conditionals become unnecessary once the JS tier is
wire-only.

### Wave 5 — the new capability

| Item | Ruling | Falsifier a port would fail |
|---|---|---|
| Leaf runtimes (bun 13 ms/30 MB; JVM+AppCDS 100 ms/94 MB), death event-driven via `onExit`. | `[READY]` | Kill a leaf mid-call; the caller gets a flat error and the next call starts a fresh leaf. No timeout in the path. |
| Packages on demand, per cluster, cluster-wide once required. | pending the packages workflow | — |
| `:seon.ns/owner` + failure routing grouped by `(turn, ns)`. **The attribute does not exist**: registered `:seon.ns` attributes are name, source, doc, summary, require-edges (`src/seon/ns/source.cljc:17`, `:19-37`, `:45`, `:46`). This is a new attribute, not a repoint. | `[READY]` | A proposal failing in 3 namespaces sends exactly 3 messages, and no write is refused anywhere — ownership is responsibility, not a lock. |
| Waiting = open + unclaimed; readiness a clause of `eligible?`. | `[READY]` | Kill an owner mid-fix; the waiting proposer resumes. No correlation attribute and no counter is introduced. |
| **The accretion gate is a CHAIN, not one row.** Wave 3 of the previous revision listed "accretion gate on `:seon.program.edge/calls`" as a single item resting on a substrate that returns a silent false negative for every higher-order caller. | `[READY]` for the substrate fixes | The chain, in order: (1) **sound `::calls`** — three sites in `src/seon/program/edge.cljc` discard resolved targets (`argument-uncertainties` computes the exact closed target set then uses only its set-ness, `:371-377`; `walk-expression` returns state unchanged for a resolved bare symbol, `:412-419`; it never descends non-seq forms, `:421`). Measured: `(map thumbnail ids)` records only `clojure.core/map`; a bare returned `thumbnail` and `{:render thumbnail}` record **nothing and no uncertainty**. (2) **a non-constant effect rollup** — `canonical-terminal` defaults every unannotated target to `:external` (`:143-152`) while the tee passes `{}` literally (`src/seon/host/record.clj:452`), so any purity rollup is a constant. (3) a settled output-map closedness rule. (4) **a JVM producer for test evidence** — `seon.test.runner` is CLJS-only, so on the surviving tier the gate's evidence input has no writer at all. (5) **a function-granular test link** — `:seon.test/ns` is a ref to a *namespace* and no `:seon.test/fn` exists, so "does THIS function have a test?" is answerable only by a name convention, the banned hand rule. Falsifier: `(defn add [a b] (+ a b))` derives as pure, and "is anyone using this function?" answers correctly for a var passed to `map`. |
| **`graduate.clj` splits by caller; it is not a unit.** `trust-gate?` has **zero production callers** (defn at `:108`; the only other hit is `test/seon/host_graduate_writer_test.clj:102-111`), so "keep and repoint" preserves dead code. `install-nursery!` and `rebuild!` **are live** (`src/seon/host/eval.clj:321`, `src/seon/host.clj:319`), so whole-file deletion breaks the corpus install path. **The live gate none of the three documents names is `my.plan.internal/green-tested?`** (`src/my/plan/internal.cljc:842-849`), namespace-granular, feeding `compile-namespace-dag`'s diff — a one-mechanism violation sitting directly on the accretion gate's input. | `[READY]` | After the split, exactly one evidence gate exists and it has a production caller. |
| **Deleting `:seon.fn/execution-tier` is not free — same-commit companion required.** `src/seon/host/record.clj:150` writes `:nursery` on every eval-teed fn; first-party rows never carry it; `rebuild!`'s corpus query selects on its **presence** as its only where-clause (`src/seon/host/graduate.clj:90-100`, verified: `:where [?fn :seon.fn/execution-tier]`). Delete it alone and `rebuild!` installs **nothing** at the next boot. | `[READY]` | The replacement is presence-plus-provenance — `[?fn :seon.fn/source]` minus the boot-process join `seon.db.program` already uses (`program.clj:40-45`) — i.e. the one-corpus ruling expressed as a query. Falsifier: after the cut, a boot rebuild installs the same corpus function count as before. |
| Wire up the diff — already computed and discarded. `compile-namespace-dag` returns `::diff` (`internal.cljc:1016-1023`), `publish-generated-program!` propagates it (`my/plan.cljc:1319-1322`), `:my.plan/diff` is a registered public shape (`:220-222`), and `turn.cljs:771-784` tests only `(false? (:my.plan/ok? publication))` and never reads it again. | `[READY]` | The caller of `generate-code!` receives a **function-granularity** diff. Note `turn.cljs` is on the deletion list in three documents, so the only pointer to the discard site goes with it. |
| Fix discovery. Three paths are dead on the cluster JVM: `seon.embed/enabled?` is `(constantly false)` with `search-pull` a fixed `:user-input` error (`context.clj:729-739`); `grep-graph` is CLJS-only (`src/seon/agent/search.cljs:303`) and not among the 24 host `::lib` registrations; `my.ns` is excluded — **and the recorded reason is wrong**: `my.ns/functions` is written `^{:async true}`, which does not match `pure-block?`'s literal `\^:async` alternative (`context.clj:1060-1065`); it is excluded because its body contains `(await`, `db/query` and `db/pull` (`src/my/ns.cljs:71`, `:76`, `:96`) — **database markers whose reason is void on the JVM, where `db/query` is synchronous.** This is the strongest single instance of R-14 and it is currently recorded with the wrong cause. An agent's reachable surface without discovery is **five** namespaces (`src/seon/agent/home.cljc:95-112`). | `[READY]` | A small model finds and calls a corpus fn that no home `require` names. Sizing for the advertising decision: one compact card median **41** est. tokens; the whole public schema-complete surface at 474 rows **21,659**; one catalog line median **20**; the catalog for all 206 namespaces **4,078**. Cards break first at ~500 functions; search precision fails first at ~5,000 — and both searches are off. **`[UNVERIFIED]`: no reproduction command is recorded with these token figures.** |
| **`:seon.fn/private?` presence law.** `[HEAD]` R39 says presence means private, absence means public, and the boot index obeys it; the authored tee writes the attribute unconditionally as a boolean (`record.clj:154`), so every public authored row carries `false` and the corpus carries **two encodings of one fact**. Under the one-corpus ruling advertising is filtered by attributes, so a presence query and a truthiness query now give different answers on the two halves of the one corpus — the defect moves from cosmetic to functional. | `[READY]` | Emit the attribute only when the form is `defn-`. Keep the attribute; it is Clojure's own visibility word. |

### Wave 6 — the acceptance test

The photos demo. A small non-programming model handles a user request by finding
and calling corpus functions; when none fits, it calls `generate-code!` and gets
back a diff.

**Exit test: the `src/seon/` diff for the new capability is ZERO.** If it is not
zero, we have found where layers cost mechanism, and that is the finding.

## 5. Deletion arithmetic

The prototype's measurement supersedes every prior claim.

- **Replacement size: 450-550 lines.** 767 built; 284 net core; 450-550 projected
  once the D1-D16 fixes land (every-entry volatile time flag, permit release +
  bounded `.get`, CAS-gated step-plan insert, `::datom-patterns` interest, lease
  liveness, the `*read-eval*` binding). **Quote 450-550, never 284** — 284
  excludes the fixes that make it correct.
- **Replaced size: ~6,994 lines across 14 files**, each still owing its falsifier
  run before the cut (`flow-prototype:537-576`).
- **Ratio: 13-15×, not 40×.** `flow-design:55`'s "roughly 250 lines total …
  ~10,000+" is wrong on both numbers and must not enter the spec set.

Two subtractions must travel with the ratio or it becomes a lie:

- `src/seon/host/context.clj` (2,181) + `src/seon/agent/ctx.cljc` (1,959) =
  **4,140 lines are a PORT, not a deletion** (R-8c). They move tiers.
- **~3,650 wire lines SURVIVE** for web-render (R-8b). Never counted as saved.

Deletions that are **not** free: `:seon.fn/execution-tier` (the corpus-membership
where-clause) and `:compute`'s *thread arrangement* (only its fixed count dies).
Deletions that **are** free, verified: `::mailbox-depth` across
`config/system.edn:171`, `config/resolve.cljc:284`/`:1134-1135`/`:2055-2056` and
`web/server.clj:21,33,293` — `.clear` before every `.offer` makes depth > 1
structurally unreachable; and the 09:17/14:18 nil-digest AOT reports, superseded
by the 18:25 artifact.

Boot arithmetic is a **separate axis** and a separate owner: the ~9.6 s JVM
namespace load is not the 271 s pod cluster reset. AOT+AppCDS does nothing for
the 271 s; de-quadratic `build-projection` does nothing for the 9.6 s.

Coverage arithmetic: ~146 real issue files, 133 listed in `index.md`. `D1..D16`
are claimed by **zero** runners, so by the repo's own rule the entire adversarial
suite currently counts as NOT COVERED.

## 6. Do not delete

- `seon.agent.run.core` — 189 lines of claim/epoch/lease/steal CAS. Keep verbatim.
- The `:interrupt-fn` state and its retained-trip behaviour (a dependency that
  catches the marker cannot downgrade the stop).
- Receipt-before-run and the fn/ns/schema tee — the corpus as data is the product.
- `seon.agent.message` (584 lines) — the built channel layer; messages are
  fully-formed facts and "my conversation" is derived by query.
- `:seon.program.edge/calls` — **the attribute is real and written per function**
  (`edge.cljc:11`, `:543-544`); "there is no function-level call edge" was wrong.
  The *reverse index* is not sound (Wave 5), which R-9's concluding sentence gets
  wrong.
- `graduate.clj`'s `install-nursery!` and `rebuild!` — **not** `trust-gate?`,
  which is dead.
- The UI path: facts → reactive derivation → SSE morph. The full chain is
  verified end to end: attribute-indexed interest
  (`src/seon/db/writer.clj:3153-3159`) → equality suppression
  (`src/seon/reactive.cljc:490-494`) → the latest-wins mailbox
  (`src/seon/web/feed.clj:22-25`).

## 7. Corrections this session made

Recorded because these are how the session avoided shipping wrong designs.
A plan that hides its own reversals is worthless.

- **"There is no function-level call edge" was WRONG.** `:seon.program.edge/calls`
  exists. But R-9's follow-on — "the call set itself looks sound" — is also
  wrong; see Wave 5.
- **"core.async's go blocks impose async contagion" was WRONG** for modern
  core.async on this JDK (`async.clj:519`, `:530`).
- **The turn-level transform signature was a shape PORTED from
  `core.async.flow`**, caught by review and then falsified by measurement (0 vs
  9). The clearest instance of the standing anti-port rule catching itself.
- **Line counts were overclaimed**: "~250" → 450-550; "~10,000+" → ~6,994.
- **Both proposed fixes for the vector-order bug were killed by evidence.**
  Tuples throw above 8 values; component refs pull in ascending entity-ID order.
  The cause was wrong **declarations**, not a wrong bridge — neither bridge
  distinguishes `[:vector X]` from `[:set X]`
  (`src/seon/db/internal.cljc:135-140`, `src/seon/db/datahike/schema.clj:163`).
  Nine landed as commit `5a37489c6`; **"12" was never the population** — at least
  thirteen ordered declarations survive in namespaces the recurring test cannot
  load. The rule the episode teaches, stated in no architecture document:
  Datahike has exactly two cardinalities (`schema.cljc:59`), so **order is never
  a property of the collection type.**
- **"200 concurrent" was a benchmark size, not a limit.** Curve
  30.25 / 6.73 / 2.04 / 0.73 ms/tx at n = 1/10/50/200, still improving; the
  dedicated 200-transaction benchmark reported 0.53 ms/tx against 45.09 serial.
  These are **two different runs in the same document**; name the run with each.
  The mechanism: Datahike's writer is a serial processing go-loop feeding a
  separate commit thread that drains the queue as one batch-commit with
  `DEFAULT_COMMIT_WAIT_TIME = 0` (`writer.cljc:83`, `:100-188`, `:213`, `:266`),
  so batch size self-tunes upward with offered load. Seon sets **neither**
  `:commit-wait-time` nor `:transaction-queue-size` — an unexplored free dial on
  the one measured cost centre.
- **The JVM SCI JIT has no substrate, and the citation was wrong.** On `:clj`,
  `->Node` expands to a bare `reify` that never references its ast; `attach-ast`
  is the identity. Verified in this checkout at
  `reference-code/sci/src/sci/impl/types.cljc:245-247`, `:264-273`, `:281-288`,
  `:290` — **not** `:181-191`, which is the CLJS `jit-enabled` block. Measured
  ceiling anyway: `fib(30)` = 7.0 ms compiled with no check, 34.7 ms compiled
  with the production check, 70.9 ms interpreted — **the check costs 4× the
  compiled body today**, so fix the check before any compiler.
- **Six wrong file:line citations were found across the active set**, in a corpus
  whose whole premise is that every claim carries one: the JIT substrate (above),
  the workload tags, `time-limit`, the virtual-thread allocation lines,
  `guard.cljc` (`:250-252` and `:149` in a 242-line file; the sites are `:242`
  and `:150`, and there is no `.clj` twin), and `:seon.fn/private?`.

## 8. Known risks and unverified claims

- **`[UNVERIFIED]`** — the accretion/Spec-ulation citation (section 2), and
  whether Malli's generative check is sound as the accretion gate. The lane
  assigned to settle both failed. Gates Wave 5's accretion item, nothing earlier.
- **`[UNVERIFIED]`** — that Clojure 1.12.5 delivers any measured benefit over the
  pinned 1.12.0. It resolves offline; that is all that is established.
- **`[UNVERIFIED]`** — the R-15 token-economy figures have no recorded
  reproduction command.
- **`[UNVERIFIED]`** — `sci/fork` cost. The 539 bytes / 2.1 µs in three documents
  has no recorded provenance; the finer measurement (182 bytes, 0.04 µs at
  100,000 live forks) was taken against an **empty** `(sci/init {})`, not Seon's
  ~182-namespace base. **Re-measure against the real base before quoting either**,
  and restate `simplification-design:224`'s "10,000 live forks = 5.1 MB" as
  1.8-5.4 MB. Either way 1,000 forks move the heap ~0.18-0.5 MB, which reads as
  0 MB at whole-MB resolution: **forks are cheap, not free.**
- **`[UNVERIFIED]`** — sustained heap exhaustion by retention across many threads
  was never reproduced (O1).
- Wave 4's render port **is a port**; it carries R-8c's falsifier for that reason.
- Measurement provenance is itself a risk. Quote **one** boot pair with its flags
  (10,293 → 3,886 ms, `-Xmx2g`, JDK 26.0.1); every memory figure carries its
  `-Xmx`; the AOT+CDS path is not a memory regression (~53 MB lower heap, ~100 MB
  lower RSS at the same `-Xmx`). Unrecorded elsewhere: `sci.core` got ~380 ms
  **slower** in the artifact path (464 ms source vs 845/825 ms from the 53 MB
  uberjar, independent of CDS) — ~10% of the remaining 3,886 ms.
- Every `-Xshare:on` launch prints `[error][cds] Mismatched values for property
  jdk.module.addmods … Disabling optimized module handling`, because dump-time
  flags (`build.clj:31-35` via `:173-188`) differ from launch flags
  (`script/seon/dev/artifact.clj:19-24`). Not fatal — the archive loads and the
  21.9× on `datahike.api` still lands — but an unexplained `[error]` line on
  every boot will be misread as breakage.
- The three independent readers of the same code (filed at
  `host-base-agent-surface-parity.md:165-181`, in no design document): the pod
  boot indexer reads the admitted artifact; the JVM host base reads `src/my` from
  the **process working directory** via a relative `io/file` file-seq
  (`context.clj:1017`); `graduate/rebuild!` replays `:seon.fn/source` from the
  database. Reader (2) makes the cluster JVM depend on a source tree at its working
  directory rather than on the corpus, directly contradicting the
  corpus-is-the-authority model.
- `:seon.agent.message/to` is registered through a `#?(:cljs … :clj nil)` helper,
  so **the message schema does not exist on the JVM**. Invisible today; a hard
  blocker the moment messaging moves to the cluster JVM, and the structural reason
  the JVM invariant test can never see the attribute.

## 9. Plan changes and owner rulings, 2026-07-26

Appended, not rewritten. The rows above keep their wording; this section
records what changed under them, what the owner ruled, and which of the plan's
own citations went stale. A row and its correction must be read together.

### 9.1 Stale citations found by grep, 2026-07-26

Three of this plan's `file:line` anchors no longer resolve. The plan's premise
is that every claim carries one, so these are recorded rather than silently
re-pointed:

- **`src/seon/agent/driver/host.clj` does not exist.** The driver is
  `src/seon/agent/driver.clj` (667). Every Wave 1/2 row citing
  `driver/host.clj:136-152`, `:580-621`, `:773-795`, `:809-814` is pointing at
  a deleted file; read it in git history.
- **D5's fix had already half-landed.** `:datahike.read/dependency-plan :all`
  is gone; `::protocol/datom-patterns` is in place at
  `src/seon/agent/driver.clj:658-666`. It is **still a feedback loop**, for a
  reason the plan did not anticipate: `open-run-tx-data` writes
  `:seon.agent.run/lease-until` (`driver.clj:374`) and that attribute is in the
  listener's own pattern set (`:664`). The residual invariant, which belongs in
  the design rather than in a patch: **no wake attribute may be one the wake
  path's own work commits.** Owned by lane `d5-wake`.
- **Placement is gone.** `rg 'plan-execution|execution-plan-disposition'`
  returns nothing across `src/` and `test/`. Wave 4's first row is discharged.

### 9.2 The name reconciliation runs OPPOSITE to Wave 2's assumption

Wave 2 says `:seon.eval/index` + `:seon.eval/total` "is the same fact under a
different name; **reconcile the name**", and the O12 cut shipped
`:seon.eval/ordinal` instead.

**Evidence, corrected 2026-07-26.** A first pass of this table was wrong in
three rows; lane `reconcile` refused to act on it and re-grepped, and the
corrections were then verified independently. Recorded in full because the
decision rests entirely on these counts and the first version would have
justified the opposite conclusion for the wrong reason.

| meaning | attributes |
|---|---|
| **stored ordinal of a child in an ordered collection** | `:seon.eval/ordinal` (`eval/receipt.cljc:16`), `:seon.agent.turn.timing/ordinal` (`:71`), `:seon.agent.run.form/ordinal` (`agent/run/core.cljc:33`, aliased to `:seon.eval/ordinal`) — and the one outlier, `:seon.error.frame/ordinal` (`error.cljc:81`) — renamed by `ee000a4e7` |
| **an index, i.e. Datahike's and Proximum's own word** | `:db/index` (14), `::db/index` (7), `:seon.db/index` (4), `:datahike/index` (2); `:seon.db.protocol/index` and `seon.db/::index`, both `[:enum :eavt :aevt :avet]` (`protocol.cljc:671`, `db.cljc:57`); `:seon.embed/index`, a Proximum **secondary index** (`embed.clj:18`, `:115`); `:datahike.index-page/*` |
| **not a competing spelling** | `:my.plan/position` (`my/plan.cljc:375`) is a derived "where am I in the plan" projection — root, step, progress — never a stored ordinal |

Three corrections to the first pass, each of which **strengthens** the
conclusion:

- `:seon.ai.attempt/ordinal` is **not registered anywhere.** Its registration
  was deleted by `f6f6673b6`; five consumers survive in
  `src/seon/web/serve.cljs` (`:976`, `:999`, `:1010`, `:1175`, `:1181`) plus
  `test/seon/web/serve_test.cljs:919`. That is a **dangling attribute
  reference**, not supporting evidence — file it; it rides the pod cut (O13).
- `:seon.db.protocol.operation/index` **does not exist.** The real attribute is
  `::index` = `[:enum :eavt :aevt :avet]` — index *selection*, not a position.
- `:seon.embed/index` is a Proximum secondary-index identity, not a position.

So the count of stored-position attributes is 3 × `ordinal` + 1 × `index`, and
**every other one of ~27 `index` uses means an index.** Note the honest
weakness: all three `ordinal` attributes are new from this program, and
`:seon.error.frame/ordinal` was the pre-existing `index` spelling — so seniority argued for
`index`. Non-collision decides it the other way and decides it more strongly:
Datahike and Proximum both own the word `index`, and **a position is not an
index.**

**Decision (landed `ee000a4e7`): keep `ordinal`; rename `:seon.error.frame/index`; correct
`measurements-2026-07-25.md:1001-1007`,** which names `index` as the idiom.
This is a reconciliation to ONE spelling, which is what the row asked for; it
is not a fourth mechanism.

### 9.3 O13 — the pod dies unconditionally (owner ruling, 2026-07-26)

Every remaining `.cljs` goes, and `:seon.dev.process/pod` is removed from the
supervised set (`script/seon/dev/process.clj:33`). Five supervised processes
become three: watcher, writer/cluster JVM, web-render.

Bun returns **only** as a disposable on-demand **leaf runtime** for the
packages work — agents installing java/CLJ and js/cljs packages, with calls
routing transparently to those runtimes, executing, and returning data across
the wire. Never a long-lived supervised process. Owner: *"This is complicated
so do this last."* That is Wave 5's leaf-runtime row plus O10, and it is the
**final** wave, after render.

The agent loop is already gone: `src/seon/agent/loop/` and
`src/seon/agent/turn/` are empty directories.

### 9.4 O14 — the datastar/render work joins this plan, and is INVESTIGATED before it is designed

Owner ruling, 2026-07-26. Two parts, and the second one gates the first.

**Part 1 — it belongs here.** The plan treated Wave 4's render item as "a PORT
of ~1,273 CLJS-only lines". That framing is wrong in both directions:

- *Less is missing than the plan says.* The JVM web-render process **already
  serves SSE over http-kit** (`src/seon/web/server.clj:8` requires
  `org.httpkit.server`), the sliding buffer of one **is built**
  (`src/seon/web/feed.clj:22-25`), and the render engine is **already
  portable** — `render.cljc` 1,132, `reactive.cljc` 680, `ui/html.cljc` 353,
  `ui/markdown.cljc` 226, `ui/clojure.cljc` 192, `render/canvas.cljc`,
  `my/canvas.cljc`, `my/ui.cljc`. The genuinely CLJS-only set is
  `web/datastar.cljs` 1,268, `agent/ctx/driver.cljs` 605,
  `ui/agent_view.cljs` 93, `ui/header.cljs` 47.
- *The blocker is one hardcoded refusal.* `src/seon/host/invoke.clj:167-172`,
  comment verbatim: *"render-prompt!/render-agent-view! remain pod-served: the
  host serves EVAL; the pod keeps rendering (design §1)."* It comes off by
  **deletion** with BLOCK 1, not by an edit.

**Agent-authored render is not a special case.** It is the first of the three
sanctioned shapes — pure code returning a VALUE the driver interprets — so it
is contained by the *same* one `:interrupt-fn`, one `time-limit`, one
`:compute` permit. Owner requirement, verbatim: *"If an agent writes hiccup
with an infinite loop we are to detect it and to kill it and tell the agent
they fucked up without it crashing the system or locking everything up."*
The diagnostic already exists: large `fn-entries` reads as a spin, small reads
as blocked in a host call.

**Part 2 — do NOT decide the design yet.** Owner, verbatim: *"I don't want to
quickly decide on the web rendering side. I want you to come up with possible
ideas and investigate them with sol agents and maybe mock something up to test
it before we commit to any design. I want to understand the actual pros/cons
and what the optimal path is."*

Two design inputs the owner supplied, both load-bearing:

1. **Render once, cache per consumption, de-dupe across consumers.** *"an agent
   authoring some hiccup, us rendering it once to sanity check it and then it's
   cached for that specific consumption and de-duped so if the another tab is
   opened we still only rendered it once and the results are still there and
   cached."* Target: N connected tabs on one canvas cause exactly **one**
   agent-code evaluation.
2. **The Bun version's cost is a design input.** *"the reactive web rendering
   was working really well in the Bun pod but it was a resource pig. This new
   design should be better NOT a straight port."* That cost centre is recorded
   in **no** document. A design that cannot name it cannot claim to beat it.

Investigation lane: `render-design`, delivering
`research/jvm-render-design-2026-07-26.md`. It commits nothing under `src/`.
The render wave is **blocked on that report plus an owner ruling on its
recommendation.**

Vendored and readable, all present: `reference-code/datastar`,
`reference-code/datastar-clojure` (`libraries/sdk-http-kit` is what
`deps.edn:70-72` resolves), `reference-code/hyperlith`,
`reference-code/http-kit` (v2.9.0-beta2 `70432d3`, made a proper submodule by
`2953a3b2f`; it was the only loose directory of 99).
Hyperlith examples are under `reference-code/hyperlith/examples/` (the
previous `/reference-code/n/examples/` path was stale).

### 9.5 Revised wave order after these rulings

1. **BLOCK 1 — the old guarded door.** `src/seon/host.clj` + all of
   `src/seon/host/`, 5,715 lines, zero production `:require` from outside
   itself. Discharges Wave 1's D9 and `policy-error!` rows, Wave 2's
   per-agent-ctx-retention and fixed-eval-thread-count rows, and half of Wave
   4's second-IPC-path row — **by deletion, not by edit.** Lane `cut1-door`.
2. **D5 + D2, the wake path.** Lane `d5-wake`. §9.1.
3. **The name reconciliation.** §9.2.
4. **Wave 1's residue** — D15's catchable classes (`seon.sci.ctx:29-32` adds
   `Throwable`/`Error` only; `RuntimeException`, `StackOverflowError` and
   `:default` are still unresolvable), and stating the single-host-call
   allocation hole rather than implying a bound. **D7 is structurally closed
   by BLOCK 1's deletion**: `seon.sci.eval` parses with
   `sci/parse-string` inside the armed ctx (`eval.clj:110-112`), which refuses
   `#=`, and `seon.repl.parse` is pure rewrite-clj. No tools.reader read path
   survives on the agent path.
5. **Wave 2's residue** — D1/D3/D6/D10/D12/D13, the committed ordered step
   plan and its D11 companion.
6. **Wave 3 — the wire.** One predicate; result symbols; `seon.result/ok?`.
   Free deletion riding here: `::mailbox-depth` (`config/system.edn:171`,
   `config/resolve.cljc:284`/`:1133-1135`/`:2054-2055`,
   `web/server.clj:21,33,293`, `web/feed.clj:114`) — `.clear` before every
   `.offer` makes depth > 1 structurally unreachable.
7. **RENDER — blocked on O14's investigation and ruling.** Then the pod's
   remaining `.cljs` and `:seon.dev.process/pod` go, per O13.
8. **Wave 5's capability work**, then packages/bun-as-leaf **last**.

### 9.4a O15 — index at COMPILE time, from a JVM build only; never at runtime

Owner ruling, 2026-07-26. Verbatim: *"We index at compile time and during
runtime we are always updating the database with the new functions specs and
tests being authored. There is zero shit that misses both of these points."*
*"There are two states — the agent is starting up a fresh cluster so during
compilation we need to come up with a pre-load set of datoms to fully index all
of the code that was compiled and the startup state (from aero config). Then we
have a resume state where the system still reads the config to see if anything
needs to be overridden, but otherwise is just resuming from the database. The
database is the single source of truth."* *"Do not index the source at
runtime."* And: *"I want fast startup and fast resume in all conditions."*

**Ruled: index everything from a JVM build only.** The shadow-cljs indexing
hooks go with the pod. One indexer, one build, no shadow dependency. The owner
accepted the known cost of this explicitly: packages will then need their
surface enumerated without the CLJS analyzer that knows it — recorded below as
an open problem for the packages wave, not solved here.

**What is already built, and where it leaks.** The compile-time indexer the
ruling describes EXISTS, as six shadow-cljs build hooks wired at
`shadow-cljs.edn:63-80` and `:116-126`:
`prepare-program-rows!` (`:optimize-prepare`), `publish!`, `publish-rows!`,
`publish-base-projection!`, `publish-page-plan!`, `publish-inventory!` — all in
`script/seon/dev/program_artifact.clj` (653). It is fed by
`seon.dev.program-inventory/analyzer-fn-inventory`
(`script/seon/dev/program_inventory.clj:46`, docstring *"Derive callable symbols
from a selected compiler analysis closure"*) and it publishes **precomputed
initialization pages**, which is the ratified vocabulary
(`AGENTS.md:472` ↔ `src/seon/db/protocol.cljc` pages/phases ↔ Datahike tx-data).

Three defects, all verified 2026-07-26:

1. **It is CLJS-only.** It runs off shadow's analyzer data, and
   `program-row-build-js` (`program_artifact.clj:215`) literally runs a JS
   program that writes the rows to stdout (`:248`). **No `.clj`/`.cljc` source
   has ever been indexed at build time.**
2. **A runtime-index leak.** `seon.db.protocol/initialization-pages`
   (`src/seon/db/protocol.cljc:2000-2007`) returns *"the precomputed pages **or
   derive them from raw initialization**"*. That `or` is runtime indexing, and
   it is a silent slow path rather than a failure. Plausibly a large part of the
   81 s corpus-indexing term in the 271 s reset.
3. **The reconciler is orphaned.** `seon.db.program/compile-tx-data`
   (`src/seon/db/program.clj:292`) has exactly **one caller in the tree: its own
   test.** Its docstring still says *"compilation remains a pod concern."*

**O16 — delete the derive branch.** Owner-ruled: missing pages is a **loud
failure, no fallback** (R41: panic in development, log loudly in production).
A broken artifact must not present as an 81-second boot.

**The two states, neither of which indexes anything.**

| state | behaviour |
|---|---|
| fresh cluster | load the precomputed initialization pages. Nothing derives, nothing reads source. |
| resume | read the config manifest for overrides only — already the ruled behaviour, writes nothing when converged — then resume from the database. Zero source reading, zero indexing. |

**Runtime writes only agent-authored facts**: the driver commits
`:seon.fn`/`:seon.schema`/`:seon.test` in the terminal transaction next to the
receipt. That is the deleted tee re-imagined as one commit, shape 3. Nothing
else writes the index at runtime. Between compile-time indexing and this, the
owner's *"zero shit that misses both of these points"* is the coverage claim to
prove.

**Deletion scope for this ruling** (sizes verified):
`script/seon/dev/program_artifact.clj` 653, `src/seon/client/indexing.clj` 108,
`script/seon/dev/program_inventory.clj` 74, plus the `:build-hooks` vectors in
`shadow-cljs.edn` and the orphaned reconciler path in `src/seon/db/program.clj`
(297). Blast radius to check before cutting: `script/seon/dev/artifact.clj`,
`script/seon/dev/release.clj`, and five tests under `test/seon/dev/` all consume
`program-inventory`.

**Open problem, owner-acknowledged, deferred to the packages wave (LAST):** with
no CLJS analyzer in the pipeline, how does a JS/CLJS package's callable surface
get enumerated? Candidates not yet evaluated: a leaf reporting its own surface
at install time; reading the ecosystem's own manifest; a one-shot build. Do not
design this before the packages wave; do not let it justify keeping the shadow
hooks alive in the meantime.

**Fast startup and fast resume are two axes with two owners**
(`measurements-2026-07-25.md` 2.7), and both need re-measurement after
`8dc8623ad`:

| axis | measured | note |
|---|---|---|
| JVM namespace load | 10,293 → 3,886 ms with AOT+AppCDS, `-Xmx2g`, JDK 26.0.1. AOT carries 92.7%, AppCDS 7.3% | residual is 63% three non-AOT namespaces: `sci.core` 825 + `host.context` 900 + `db.writer` 723. **`host.context` is now DELETED**, so ~900 ms should already be gone — UNMEASURED |
| fresh cluster reset | 271 s = 81 s corpus indexing + 46 s `build-projection` computed **twice** + 35 s unlogged gap + 16 s paging | build-time indexing attacks the 81 s; the double computation is a straight bug; the 35 s gap is unexplained |

### 9.4b O17 — the plan is restructured into ONE owner-keyed ledger

Owner ruling, 2026-07-26, after asking why the previous plan was written and
then not followed. The diagnosis is that it was not unclear — it was
**un-followable** — and the evidence is in this document:

1. **Two competing orderings, with no statement of which wins.** §3.5 "The
   order" (cluster healthy → `interrupt` ‖ `ctx` → `eval` → driver claim/CAS →
   receipts → one live turn) is not §4's wave order (velocity/deletion →
   containment → loop/topology → wire → cuts → capability → acceptance). §3.5
   puts the driver at step 4 where §4 puts it in Wave 2 behind all of
   containment, and §3.5 never mentions the wire, the cuts, or the capability
   work at all. A reader must choose, and choosing is where drift enters.
2. **Rows anchored to line numbers that the plan's own work invalidates.**
   Three anchors were dead within a day (§9.1).
3. **Forty readiness tags, one state marker.** `rg` counts 40
   `[READY]`/`[HEAD]`/`[DESIGN]`/`[OWNER:]` against 1 mention of anything having
   landed. The plan says what is *startable* forty times and what is *done*
   once, so a returning session must re-derive what remains — which is exactly
   what happened.
4. **Rows organized by defect, not by owner.** One deletion (`8dc8623ad`)
   discharged **five** separate rows — Wave 1's D9 and `policy-error!`, Wave 2's
   per-agent-ctx-retention and fixed-eval-thread-count, and half of Wave 4's
   second-IPC-path — because all five lived in `src/seon/host/`. The structure
   could not show that, so it would have scheduled five lanes to edit files a
   sixth was deleting.

**Ruled: rewrite as one owner-keyed ledger** — one ordered list; rows keyed by
the file or mechanism that must change; a state field per row
(`open` / `blocked on Ox` / `discharged by <sha>`); symbol anchors rather than
line numbers where a symbol exists. Every existing row's evidence and falsifier
is preserved verbatim inside its new row; nothing is dropped.

**Ruled: restructure ONCE, after the three in-flight audit/research lanes
land** (`pod-verdict`, `capability-ledger`, `render-design`), because all three
produce rows.

### 9.6 Still open, and NOT decided by these rulings

- **`seon.sci.eval` borrows `:compute` without core.async's dispatch.**
  `src/seon/sci/eval.clj:33-38` hand-rolls
  `(Executors/newCachedThreadPool …)` while its docstring says *"on a bounded
  `:compute` platform thread"*. core.async's `:compute` is `make-ctp-named`
  (`reference-code/core.async/.../impl/dispatch.clj:71-73`) — the same
  construct, and core.async is already loaded in this process (Datahike's
  transactor is a go-loop). Borrowing the word without the mechanism is the
  "claimant" defect. *Recommendation: route through core.async's dispatch, do
  not rename our pool.* The owner has stated the design frame — all state in
  the database, all execution on `:io` or `:compute` threads — but has not
  ruled on this site.
- **O4, the allocation limit.** `src/seon/sci/interrupt.clj:5` demotes
  allocation to a diagnostic. Defensible under O2, but it was a silent
  deviation. Ratify or restore.
- **`core.async.flow`'s non-adoption.** Zero occurrences in `src/`; its
  vocabulary and transform discipline were adopted, the library was not.
  Deliberate, still unratified.
- **The admin surface** (`ping`/`pause`/`resume`/`inject` → queries and facts),
  recorded as the single strongest argument for the design and verified
  2026-07-26 as **not built**. `seon.sci.eval/available` exists and nothing
  exposes it.

### 9.7 Corrections to the plan's own framing

- The one surviving `:seon.agent.turn/evals` order consumer is `no-progress-streak`
  (`src/seon/agent/loop/core.cljc:26-48` → `driver.cljc:168-169`) and its failure
  mode is a **false negative**: a reordered pull makes two identical turns compare
  unequal, the streak resets, and the run does not close at limit 3. It never
  falsely closes a run. **`my.plan` is not a positional consumer** — it recovers
  order from the terminal status datom's transaction id — so the remaining work is
  ~3× smaller than the audit and issue read.
