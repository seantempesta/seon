---
type: research
status: active
tags: [research, runtime]
---

# Redesign ledger: what must be deleted, and the test a port would fail (2026-07-25)

Owner instruction, 2026-07-25: the previous conversion lane was told to remove
the old way of doing things and instead **ported the design when the design
needed to be redesigned**. This ledger exists so that cannot happen again.

The defect was in the specification, not the lane. "Delete the old path" is
satisfiable by moving the old shape into a new file. So every row here carries
a **falsifier**: a concrete acceptance test that a faithful port FAILS and only
a real redesign passes. A row is closed by evidence against its falsifier, never
by the absence of the old namespace.

**Status update, 2026-07-25 evening (completeness audit).** Rows whose status
changed after the prototype and the in-situ re-measurements are marked
**STATUS CHANGED** in place; the rulings settled in conversation are appended as
R-19. Do not read a row without its status note.

Companion documents:

- `flow-design-2026-07-25.md` — the target design, rewritten 20:05 after the
  prototype. **Its three previously-false sentences are named and corrected in
  its own Status section**; any document that still says "do not implement from
  it because three of its sentences are false" is describing the pre-rewrite
  version.
- `measurements-2026-07-25.md` — every number with its conditions, the
  provenance rules, and what was NOT measured.
- `implementation-plan-2026-07-25.md` — decisions (named, not numbered), waves
  in defect order, and open owner rulings O1-O8.
- `flow-prototype-2026-07-25.md` — D1..D16, the adversarial measurements.
- `wtf-review-2026-07-24.md` — the fresh-eyes trace of one turn at HEAD, with
  its own ranked WTF list. This ledger does not repeat that analysis; it
  converts it into deletion units with falsifiers, and adds what the
  2026-07-25 design session found.
- `simplification-design-2026-07-25.md` — the target design (in progress).

## How to read a row

- **Symptom** — what a reader sees in the tree today.
- **Evidence** — file:line actually read.
- **Why it is a ported shape** — the old-model assumption still encoded.
- **Falsifier** — the test. If a port could pass it, the falsifier is too weak
  and must be sharpened before the work starts.

---

## R-1 — Two agentic loops coexist

**Symptom.** The claim-native driver and the Bun-era loop are both live.

**Evidence.**

- `src/seon/agent/driver.cljc` (647) — `acquire-run-state!` → `claim!` →
  `drive-claim!` → `drive-run!` → `scan!`.
- `src/seon/agent/loop.cljs` (702) — a `transitions` FSM (L39), `wake-handler`
  (L333), `install-wake-trigger!` (L484), `uninstall-all-wake-triggers!`
  (L545), `install-ticker!` (L596), `default-tick-ms` (L571).
- `src/seon/agent/turn.cljs:673` — a section headed
  `;;; CLAIM-NATIVE POD PHASE LEAF`, i.e. the pod acting as a second claimant
  beside the real one.
- Already recorded as WTF item 5 (superseded stack alive next to replacement)
  with concrete duplications: two turn-row constructions, two reply parses per
  turn, two attempt-evidence builders, dead `pod-phases`/`host-phases`.

**Why it is a ported shape.** A ticker plus a wake-trigger registry is how you
drive work in a single-threaded event-loop runtime that cannot block. On a JVM
claimant, a virtual thread blocks on the transaction feed and the ticker has no
job. The FSM in `loop.cljs` is a second copy of a state machine whose authority
is the durable phase cursor.

**Falsifier.** After the cut, `rg -n 'tick|wake-trigger|transitions' src/seon/agent/`
returns nothing, AND the surviving driver has no periodic timer of any kind:
every advance is caused by a committed transaction or a claim expiry, both
observable events. A port that renames the ticker into the driver fails this.

---

## R-2 — The portable core is written in async style for a tier being deleted

**Symptom.** Every function in the portable `.cljc` driver is `^:async`.

**Evidence.** `src/seon/agent/driver.cljc` — `^:async acquire-run-state!`
(L205), `^:async claim!` (L246), `^:async release!` (L289), `^:async
drive-claim!` (L517), `^:async drive-run!` (L598), `^:async scan!` (L607),
`^:async call-with-leaf` (L642).

**Why it is a ported shape.** The repo's own rule (`CLAUDE.md`, *Portable code,
platform edges, and SCI*) is "async is contagious upward — push it down": the
CLJ path is plain synchronous because virtual threads park, and `^:async`
markers exist only at the one executor/leaf call. Here the contagion has
reached the spine. It exists solely so the same file can run on Bun. That
requirement dies with the pod.

**Falsifier.** The surviving driver contains zero `^:async` and zero `await`,
and reads as straight-line Clojure — claim, drive, commit — with blocking calls
that a virtual thread parks on. Measure it: the driver should lose a large
fraction of its lines to nothing but removing async plumbing. A port that keeps
promise-threading "for portability" fails.

---

## R-3 — Seven containment mechanisms on one SCI primitive

**Symptom.** SCI supplies exactly one safety primitive; Seon has seven layers
around it with different vocabularies and different failure semantics.

**Evidence.**

- The primitive: `:interrupt-fn`, fired at every fn entry and every `recur`
  (`reference-code/sci/src/sci/impl/fns.cljc:52`), plus
  `sci.interrupt/interrupt!` for an uncatchable marker.
- Step budget — `src/seon/host/guard.cljc` (`check-holder!`, a `long-array`).
- Platform interrupt predicate — `src/seon/host/invoke.clj:39`.
- Watchdog thread interrupt — `src/seon/host/invoke.clj:37`.
- Output cap — `src/seon/host/eval.clj:217`.
- Wire result limit — `src/seon/host/invoke.clj:204`.
- Explicit cancel — `src/seon/host/invoke.clj:263`.
- Process `destroyForcibly` — `script/seon/dev/process.clj:2085`.

**Why it is a ported shape.** All of it is cooperative, and modern JDKs removed
`Thread.stop`, so none of it can stop a runaway that reaches no safepoint.
`cancel-active!` admits this: it settles the frame, interrupts, waits 2s, and
walks away leaving the thread running (`invoke.clj:280-283`). The layers exist
because the door is being asked to deliver a guarantee only a process boundary
can deliver.

**Falsifier.** The surviving design states, in one sentence, which mechanism
owns "runaway but stoppable" and which owns "wedged and unstoppable", and the
count of mechanisms drops. Specifically: the door promises only what a
safepoint can enforce, and the wedged case is owned by the claim lease plus
process replacement. A port that keeps all seven and merely relocates them
fails.

---

## R-4 — Graduation was built, then disabled; the gate tests the wrong property

**Symptom.** The tiering machinery the owner wants already exists and is
switched off.

**Evidence.** `src/seon/host/graduate.clj` — `trust-gate?` requires
schema-valid ∧ test-covered ∧ fingerprint match ∧ nursery test green ∧ compiled
test green ∧ identical results across tiers. Then `effective-tier` hardcodes
`:nursery` for every row, and `graduate!` refuses outright and records a core
fault citing owner ruling R48 pending P4 pure-call-graph admission.

**Why it is a ported shape.** The gate measures *correctness* (spec'd, tested,
differentially equal) and is being used to authorize *removal of containment* —
a graduated fn is a real JVM fn with no safepoint at all. Tests cannot
establish termination. `(defn count-to [n] (loop [i 0] (if (= i n) i (recur (inc i)))))`
passes every generative test Malli can produce and wedges the process forever on
`(count-to -1)`. R48 was right to refuse; the design error is asking a
correctness gate to make a containment decision.

**Falsifier.** The surviving fast tier keeps a safepoint. Concretely: a
graduated/compiled function, given an input that makes it loop forever, still
trips the budget and returns a flat `:seon/error` — demonstrated by an actual
test, not by argument. A port that re-enables `graduate!` to produce bare
`clojure.core/eval` fns fails this by construction.

---

## R-5 — The fast path for long computation does not exist on the tier that runs

**Symptom.** The owner wants long, hard computations. The JIT that makes SCI
fast is ClojureScript-only.

**Evidence.** `reference-code/sci/src/sci/impl/jit.cljs` (36KB) — codegen on
first call via `js/Function`, ~20x on tight loops per the fork's CHANGELOG
0.15.56. There is no `jit.clj`; `ls src/sci/impl | grep jit` returns one file.
It preserves the safepoint (`jit.cljs:772` emits `if(INT!==null)INT();` at the
head of the recur trampoline) and escapes unsupported subtrees back to the
interpreter through `H.ev` sharing the invocation array.

**Why it is a ported shape.** The performance story was inherited from the Bun
tier. Under the all-JVM target, claimants get the tree-walking interpreter and
HotSpot compiling *the interpreter*, not the program.

**Falsifier.** A measured number, on the JVM, for a representative long
computation, before and after whatever mechanism is chosen — and the mechanism
retains the interrupt-fn (see R-4). A port that declares the JIT "available"
because SCI is in the dependency list fails.

**STATUS CHANGED — measured, and the citation was wrong.** The substrate claim
is right; the line numbers this ledger printed are not. Verified in this
checkout: SCI's own comment "the `:clj` reify and `:cljd` branches DISCARD the
form at expansion time" is at `types.cljc:245-247`; `->Node` on `:clj` is a bare
`reify` that never references its `ast` argument at `:264-273`; `attach-ast` on
`:clj` is the identity `node` at `:281-288`; "the jit and its ast only exist on
cljs" at `:290`. **Lines 181-191 are the CLJS `js-eval-available`/`jit-enabled`
block** and were cited by mistake here and in `simplification-design`.

Measured ceiling, so the row can be closed rather than argued: `fib(30)` =
**7.0 ms** compiled with no interrupt check, **34.7 ms** compiled with the
production check, **70.9 ms** interpreted. **The check costs 4x the compiled
body it protects** — fix the check (see R-19) before any compiler. And see R-19:
SCI is ~5% of a turn, so a compile tier optimizes 5%.

---

## R-6 — Carried forward from `wtf-review-2026-07-24.md`

These are already evidenced there; recorded here with falsifiers so the
deletion wave can close them.

| # | Symptom | Falsifier |
|---|---|---|
| 6a | One turn spans three processes with two full claim-arbitration bounces (render/publish on the pod, llm/eval on the JVM) | One turn = one claim, one process, zero handoffs. Count the claim transitions per turn; the number is 1. |
| 6b | Two IPC mechanisms invoke agent code on the JVM in one turn — the claim cursor and a UDS frame protocol (`seon.host.clj` server, `session.leaf` client) | Exactly one way to invoke agent-authored code under the guard. `rg` for the frame/session/channel vocabulary returns nothing. |
| 6c | Per-reply plan/disposition reifies the whole program graph + schema corpus, then re-checks manifests against the projection they came from; `observed-generation` is assigned from `planned-generation` (vacuous); `cache-key` computed and never used | Placement, if it survives at all, is a set-membership test at resolution time. No unbounded query on the per-reply hot path. |
| 6d | The crash-recovery arm the six-phase cursor exists for does not compile-check: `settle-eval-step!` calls `run-eval-batch!` with 5 args against a 7-arg signature, `storage-view` in the `run` position | The recovery path has an executing test that kills a claimant mid-`:evaling` and observes resumption. Already filed: `docs/seon/issues/settle-eval-replay-arity-mismatch.md`. |
| 6e | Fence ceremony: a standalone probe tx before each batch, three separate fence builders, and 8+ independent pulls of the one config singleton per turn | One config acquisition per turn, passed as an ordinary value. One fence builder. No probe tx. |
| 6f | Vocabulary drift across one call stack: `:seon.repl/*` → `:seon.execution/*` → `:seon.agent.driver/*` → `:seon.host/*` → `::session/*` → `:seon.eval/*` for one logical operation | One turn has one vocabulary. Any surviving boundary term names the producing and consuming source on both sides, per the CLAUDE.md vocabulary rule. |

## What must NOT be deleted

Recorded so the cut wave does not overshoot. From `wtf-review-2026-07-24.md`:

- `seon.agent.run.core` (~190 lines) — the claim/epoch/lease/steal algebra.
  Tight, pure, and exactly what crash tolerance needs. Keep verbatim.
- `seon.host.guard` — step budget + deadline + output cap through one
  array-backed safepoint, including the retained-trip trick that stops a
  dependency from downgrading a policy stop. Proportionate.
- Receipt-before-run and the fn/ns/schema tee in `seon.host.eval` — the code
  corpus as data is the product.
- The UI path: facts → reactive derivation → SSE morph.

Additionally, from the 2026-07-25 session: the graduation *gate* in
`graduate.clj` (`trust-gate?`) is good evidence-gathering pointed at the wrong
decision. Repoint it; do not delete it.

**STATUS CHANGED — `graduate.clj` splits by caller; "repoint `trust-gate?`" was
wrong.** Verified: `trust-gate?` (defn at `graduate.clj:108`) has **zero
production callers** — the only other hit in the tree is
`test/seon/host_graduate_writer_test.clj:102-111`. "Keep and repoint" therefore
preserves dead code, and `flow-prototype`'s whole-file deletion breaks the
corpus install path, because `install-nursery!` and `rebuild!` **are** live
(`src/seon/host/eval.clj:321`, `src/seon/host.clj:319`). **The live evidence
gate neither position names is `my.plan.internal/green-tested?`**
(`src/my/plan/internal.cljc:842-849`), namespace-granular, feeding
`compile-namespace-dag`'s diff — a one-mechanism violation sitting directly on
the accretion gate's input.

**Also not free to delete:** `:seon.fn/execution-tier` is the corpus
**membership** where-clause, not merely a tier stamp. `record.clj:150` writes
`:nursery` on every eval-teed fn, first-party rows never carry it, and
`rebuild!`'s recorded-function query selects on its **presence** as its only
where-clause (`graduate.clj:90-100`, verified `:where [?fn
:seon.fn/execution-tier]`). Delete it alone and `rebuild!` installs **nothing**
at the next boot. The replacement — `[?fn :seon.fn/source]` minus the
boot-process provenance join `seon.db.program` already uses
(`src/seon/db/program.clj:40-45`) — is the R-19 one-corpus ruling expressed as a
query, and must land in the **same commit**.

---

## R-7 — The complexity is not where R-1..R-6 looked

**Symptom.** The audit measured the loop and found it already simple. The cost
is elsewhere.

**Evidence.** `drive-claim!` (`driver.cljc:517`) is already a plain `loop/recur`
asking `next-step` (`loop/core.cljc:76`). `seon.agent.run.core` is 189 lines of
correct pure CAS algebra. SCI's total contribution to the loop is `:interrupt-fn`.
The actual cost centres are (a) the two-process split of one turn — the pod owns
render+publish (`driver/pod.cljs:46-48`), the JVM owns eval+interaction+llm
(`driver/host.clj:68-73`) — and (b) the socket between an agent's eval and the
database: one unpinned agent write is two wire round-trips, four transit encodes
and four decodes; one form containing one write costs 6-7 writer round-trips.

**Falsifier.** One turn = one claim, one process, zero handoffs; and an agent's
database write is a function call with zero sockets on the eval path. Prove the
second by running a turn and counting sockets from the eval path.

---

## R-8 — Landmines no design budgeted (verified, unflagged before today)

**8a — `seon.host.instrument` is a global fair RRWL.** `instrument.clj:27`.
Every eval holds the read lock for its entire duration (`eval.clj:444`); every
successful `defn` takes the write lock (`eval.clj:518`). `:seon.host/contexts`
is never evicted — no `dissoc` anywhere in `src/`. **Removing the 10-thread pool
cap converts "one agent types `defn`" into a cluster-wide stall proportional to
contracts × agents-ever-started.** This is a larger parallelism risk than
anything in the three designs. Falsifier: N agents evaluate concurrently while
one defines a fn; no agent's latency depends on the others.

**STATUS CHANGED — the retention is simultaneously the leak and the model the
owner rejected.** Under R-19's ONE CORPUS ruling there is one shared base ctx
and a **fork per eval**, never a retained per-agent ctx. `ensure-context!` does
the per-agent thing *and* never evicts, so **one deletion fixes both**. Two
independent implementations fill `:seon.host/contexts`
(`src/seon/agent/driver/host.clj:136-152` and `src/seon/host.clj:138-160`,
duplicated fork+replay+install+reconcile), and each retained ctx carries exactly
one guard holder (`context.clj:1423-1430`), so per-agent concurrency shares the
counter and the interrupt cell. `simplification-design-2026-07-25.md:245` says
the opposite ("do not build a second cache — `ensure-context!` is the owner")
and **preserves the leak by citation**. Filed:
`docs/seon/issues/retained-agent-contexts-are-never-evicted-and-share-one-holder.md`.
R-8a itself was **NOT falsified** by the prototype — the RRWL was never in its
path.

**8b — "Delete the database wire (~6,560 lines)" is invalid.**
`src/seon/web/server.clj:10,12` requires `seon.db.host` and
`seon.db.transport.uds`; `:89-167` opens a UDS session for replica reads;
`:269-273` builds a `db.host/writer-session`. ~3,650 lines must survive unless
web-render moves into the claimant heap, which worsens the OOM blast radius.
Two of three designs claimed this deletion. Falsifier: name where web-render
reads from before counting those lines.

**8c — Collapsing the turn is a PORT, not a deletion.** ~1,273 CLJS-only lines
must move to the JVM (`ctx/driver.cljs` 604, `ctx/typeahead_steps.cljs` 540,
`ctx/admin.cljs` 129) plus `my.plan/publish-generated-program!`, and
`invoke.clj:167-172` currently *refuses* render on the host by explicit design
rule. This is the actual gate on the largest deletion, and no design budgeted
it. **This row is the ledger's own warning applied to itself: the port is
legitimate here, but it must be labelled a port and its output judged by
simplification, not by relocation.**

**8d — The lease is never renewed during a drive.** Written at claim time only;
with `stale-ms` = 1,200,000 any run driving longer than 20 minutes is stealable
from a *healthy* claimant. Directly contradicts the long-computation goal.

**8e — `:seon.agent.turn/evals` is cardinality-many, hence unordered.** "Form 3
of 7" is unanswerable today. And a form count derived from re-parsing the reply
blob is wrong, because `preflight/repair-read-entry` splices N entries in place
of one *mid-loop* (`eval.clj:383-388`) and rewrites source (`record.clj:384`).

---

## R-9 — Corrections to claims made earlier in this design session

Recorded because a ledger that hides its own errors is worthless.

- **"Seon's `interrupt/clojure-core` is a hand list of nine that leaves most
  code unmetered" — WRONG in effect.** Measured against Seon's real base:
  `(reduce + (map inc (range 5e6)))` fires 9,999,999 safepoints;
  `(count (filter even? (range 5e6)))` 7,500,000; `(sort (vec (range 3e6)))`
  3,000,000; `frequencies`, `group-by`, `distinct`, `clojure.string/join` all
  metered. Because `sci-range` produces an interrupt-aware lazy seq, any
  consumer of it is metered. The real hole is narrower: a single host call over
  already-materialized data is a safepoint-free window, bounded by data the
  agent already paid safepoints to build.
- **The capability annotations DO exist.** `context.clj:745-812` annotates
  fs write/edit/replace!/insert!, shell run/run-bg!/job-stop!, web fetch/search
  as `:external`. This makes effect receipts much cheaper than assumed.
- **There is no per-eval transaction scope and no rollback** anywhere in
  `src/seon/host/` or `src/seon/db/writer.clj`. Three writes then a throw leaves
  three committed transactions and one `:error` receipt.
- **A JVM SCI JIT has no AST to compile.** `->Node` on `:clj` expands to a bare
  `reify` that DISCARDS its ast; `attach-ast` is identity. The JIT is not "not
  ported" — the substrate it needs is absent on the JVM. **CITATION CORRECTED
  (2026-07-25 evening): the sites are `types.cljc:245-247`, `:264-273`,
  `:281-288`, `:290` — NOT `:181-191`, which is the CLJS `jit-enabled` block.**
  See R-5.
- **"There is no function-level call edge" — WRONG.** `:seon.program.edge/calls`
  exists, `[:set :string]` (`edge.cljc:11`), written per function as
  `[:db/add function-ref ::calls target]` (`edge.cljc:543-544`). The mistake was
  grepping only `:seon.fn/*`. What is broken is the *effect/purity rollup* over
  that graph (R-13), which is a different fact on the same entity.
  **STATUS CHANGED — the concluding half of this correction is itself wrong.**
  "The call set itself looks sound" and "'Is anyone using this function?' is
  answerable today" are **FALSE**. Three sites in `edge.cljc` discard resolved
  targets: `argument-uncertainties` computes the exact closed target set via
  `closed-targets` then uses only its set-ness (`:371-377`); `walk-expression`
  returns state unchanged for a resolved bare symbol (`:412-419`); and it never
  descends non-seq forms, so a var inside a map/vector/set literal is invisible
  (`:421`). Measured: `(map thumbnail ids)` records only `clojure.core/map`; a
  bare returned `thumbnail` and `{:render thumbnail}` record **nothing and no
  uncertainty**. **Every higher-order caller is a silent false negative.** Filed:
  `docs/seon/issues/closed-higher-order-call-targets-are-discarded-as-uncertainty.md`.
  Consequence: any accretion gate built on `::calls` must fix those three sites
  **first**, and the effect half is disqualified separately because
  `canonical-terminal` defaults every unannotated target to `:external`
  (`edge.cljc:143-152`) while the tee passes `{}` literally
  (`src/seon/host/record.clj:452`), making any purity rollup a **constant**
  (`docs/seon/issues/terminal-effect-defaults-external-for-every-unannotated-call.md`).

---

## The physics list — accepted, not engineered around

These are not backlog items. Any design that appears to solve one is wrong.

1. **No hard in-process kill.** `Thread.stop` is gone; cancellation is
   cooperative. SpacetimeDB's own V8 path admits the same limit verbatim.
2. **No memory bound.** MEASURED: OOM after 4,068 fn entries — 0.004% of the
   configured 100,000,000 step budget (`config/system.edn:84`). A step budget
   cannot bound allocation. **This is the one genuine regression from
   co-locating the writer and must be accepted explicitly by the owner**
   (open ruling O1). **STATUS SHARPENED:** an *allocated-bytes* budget cannot
   bound it either — D14 measured the metric as **cumulative allocation
   (throughput), anti-correlated with live footprint**: it killed a harmless
   `(reduce + (range 500000))` at 20 ms and **missed** a retained 1 GB that
   OOM'd the JVM. And the residual hole is un-seeable by any cadence:
   `(alength (byte-array 200000000))` allocated 200,033,752 bytes in **1 ms with
   0 fn entries**, outcome `:ok`, under both a 500 ms `time-limit` and a 64 MB
   cap. **No in-process metric expresses "this agent must not exhaust the
   heap"** — that is the process boundary, exactly as
   `reference-code/sci/doc/interrupt.md:85` says. What *was* measured is a
   REFUSED oversized allocation, survived twice; sustained heap exhaustion by
   retention across many threads was **never reproduced**.
3. **No suspend/resume of an SCI eval.** No continuations; the only escape is an
   uncatchable throw. Any "stop mid-form, do something, continue" design is
   unimplementable without a CPS tier.
4. **No retrofitted eval atomicity.** Speculative eids are a pure function of
   the db they run against (`transaction.cljc:56,1287`); any eid handed to agent
   code mid-eval is a prediction that is wrong whenever anyone else commits
   first — reproduced live as a cross-agent dangling ref written as a normal
   fact.
5. **No exactly-once external effects.** The honest ceiling is "this may or may
   not have happened, here is what it was."
6. **Write throughput is one core.** Datahike's processing go-loop threads
   db-before→db-after one transaction at a time (`writer.cljc:100-188`).
7. **One write connection per store.** **STATUS CHANGED — the first sentence
   was false as written.** "`create-writer` ships only `:self`" is wrong: the
   defmulti is at `writer.cljc:282` with `:self` at `:286` **and** a
   `:datahike-server` HTTP writer backend at `http/writer.clj:35`. The correct
   statement is *"the `:self` backend is process-local, so one WRITE CONNECTION
   per store for that backend."* **Corollary needing an owner ruling (O2):
   `architecture.md:242-246` promises interchangeable claimants and is UNSAFE as
   written** — D1 measured two live JVMs on one file store both winning the same
   epoch CAS, with **40 of 40** of the parent's successfully-returned commits
   vanishing, zero transact errors, and a store that looked pristine on reopen.
   The unsafe configuration must **refuse to open**. The concrete exit is
   `:datahike-server` for **writes** plus the existing `seon.db.host` interest
   transport for **wake** — necessary because Datahike's own `listen` is declared
   `:supports-remote? false` (`api/specification.cljc:1073`). Not new machinery;
   it is what the pod already uses.
8. **Snapshot isolation, not serializability.** `cas-assert` is the escape
   hatch; full-head `::db/expected-db` pinning degrades badly (measured
   19.9ms/commit at 1 thread → 72.1ms/commit with ~3M rejections at 64 threads,
   all `:transaction/stale-basis` with no logical conflict), and `my.plan` uses
   it on eleven paths with no retry.

---

## R-10 — Placement derivation makes zero reachable decisions, and is wrong for the target

**Symptom.** A per-reply whole-program derivation that decides nothing.

**Evidence.** `parsed-reply-plan` (`driver/host.clj:580-621`) runs once per reply:
three unbounded queries reify the entire program graph and schema corpus
(`plan.cljc:114-142`), every form is re-`read-string`'d, the whole graph is
sha-256'd. Production passes a single-entry tier inventory `{:jvm inv}`
(`driver/host.clj:587-590`), so `eligible ⊆ #{:jvm}` and both `:release` arms
(`driver.cljc:384-390`) are **unreachable**. Four separate checks are tautologies:
`observed-generation` is assigned from `planned-generation` (`driver.cljc:362-365`);
`planned-basis`/`observed-basis` likewise; `missing-leaves`/`missing-exports` are
empty **by construction** because `terminal-tiers` already removed any tier not
serving every binding. `cache-key` has **no reader in `src/` at all**. Nothing in
`src/` reads the schema or capability manifests except the disposition that
re-derives them.

**Worse — it is semantically wrong for three tiers.** The fold *intersects*
eligible tiers, so a reply calling one JS-package binding and one JVM-package
binding intersects to the empty set → `:unplannable` → refusal. The mixed-package
program is exactly the feature this is supposed to enable.

**Field record.** `docs/seon/issues/jvm-claimant-rejects-visible-reply-without-exact-execution-plan.md`
— three live drives blocked, including a lone `(seon.agent.lifecycle/complete "PLANSCHEMA_ALIVE")`.
Net production record: one blocker issue, three live failures, **zero placement
decisions made**.

**Also a hand list, and a stale one.** `plan.cljc:195-198` is two literal
`str/starts-with?` tests. `"seon.packages.jvm."` **matches nothing** — installed
packages never carry a `jvm.` segment. It also duplicates the real owner,
`seon.packages/row->host` (`packages.cljc:161-167`), which derives the host from
the ledger row's ecosystem attribute — a computed fact that already exists.

**Falsifier.** Deleted, not optimized. `rg 'plan-execution|execution-plan-disposition'`
returns nothing, and a reply mixing a JS-package call and a JVM-package call
executes.

---

## R-11 — The missing primitive: tier-local handles do not exist

**Symptom.** Three independent architects designed a location-transparent call.
All three were killed by the same thing: **a stateful package cannot be used at
all.**

**Evidence.** AGENTS.md states "tier-local objects cross as result-symbol
references." That clause **is not implemented anywhere**: `seon.eval` was deleted
whole (commit `fbc6b28b5`, 5,362 lines, taking `result_var_test.cljs` with it);
the JVM never had it (`docs/seon/issues/jvm-result-symbols-not-bound-r32.md`,
open, blocker); `::live-value` is attached and immediately `dissoc`'d
(`host/eval.clj:228`, `:488`).

Concrete failure, reproduced against all three designs: `pg.connect` returns a
`Client`; `playwright.launch` returns a `Browser`. Not ordinary data → the call
returns an error. Every design routed around the gap instead of filling it.

**The answer already exists in the repo's own vocabulary.** Name a result symbol
by `(pid, start-instant)` — the process identity the operator already uses
(AGENTS.md vocabulary table ↔ JDK `ProcessHandle`). Then crash semantics need
**zero recovery code**: when the process dies, every symbol naming it is dead by
construction.

**Falsifier.** An agent runs the browser drill — launch, new-page, click — across
three calls, and a killed leaf runtime turns the next call into a flat
`:seon/error`, not a hang or a stale object.

---

## R-12 — `ordinary-wire-value?` is the root wire defect

**Symptom.** The predicate rejects what Transit handles and accepts what corrupts.

**Evidence (measured on the JVM).** Transit round-trips lazy seqs, chunked seqs,
`cons`, `range` and `Character` perfectly. Seon's `ordinary-wire-value?`
(`db/protocol.cljc:144`, `(sequential? value) false` at `:173-174`) rejects them,
`wire-projection` (`:298`) replaces them with `pr-str` text, and
`record-wire-degradation!` (`transport/uds.cljc:217-235`) files a **`:core-bug`
fault against us**. So `(map inc [1 2 3])` — the most ordinary return value in
Clojure — becomes the string `"(2 3 4)"` and is logged as our bug.

Meanwhile genuinely lossy coercions report `degraded? false`: sorted-map →
PersistentArrayMap, sorted-set → PersistentHashSet, `java.net.URI` →
`com.cognitect.transit.impl.URIImpl` (so `=` fails).

And there are **two predicates that disagree**: `ordinary-wire-value?` vs
`persisted-value?` differ on 6 of 9 tested shapes — including
`#{[1 "a"] [2 "b"]}`, an ordinary Datalog query result.

Because the projector makes everything ordinary first, `uds/encode` never throws
for representability, which makes the `try/catch` predicates in
`host/eval.clj:133-150` and `session/leaf.clj:139-156` **unreachable dead code**:
"The function returned a value that cannot cross IPC" can never be returned.

**Falsifier.** One predicate, and `(map inc [1 2 3])` crosses as `(2 3 4)`.

---

## R-13 — The `:seon.program.edge` substrate marks essentially everything `:external`

**Symptom.** Any derivation built on the edge graph is a constant.

**Evidence (measured under `clojure -M:writer`).** `(defn add [a b] (+ a b))`
tees terminal `clojure.core/+` with `::effect :external`, because
`canonical-terminal` (`edge.cljc:145`) defaults any target absent from the
resolution's `::effects` map to `:external`. Uncertainty is structural, not
statistical: `(defn f [xs] (map inc xs))` → `:value-passed-pattern`;
`(defn f [m] (-> m (assoc :a 1)))` → `:macro-expansion`.

**Why it matters beyond placement.** `:seon.schema/pure-predicate-symbols` and
the P4 pure-call-graph admission gate — the stated reason R48 refused native
graduation — both consume this substrate. If purity is universally `:external`,
that gate can never open on its current input.

**Falsifier.** `(defn add [a b] (+ a b))` derives as pure.

---

---

## R-14 — The layering cost curve is inverted (the owner's own warning sign)

**Symptom.** The second layer costs more mechanism than the first or the third.

**Evidence.** Three layers, measured:

1. **A corpus function authored in an eval — ZERO `src/` diff.** `tee-tx-data`
   writes the `:seon.fn` row, `install-recorded-function!` (`eval.clj:311`)
   hands it to `install-nursery!`, `registry-load-fn` (`context.clj:613`) serves
   it to every other ctx on first `require`. Genuinely data-only.
2. **A new `my.*` toolkit namespace — the MOST expensive.** A `src` file, a hand
   `require` in `client.cljs` (L140-197), passage of the `pure-block?` **source
   regex** (`context.clj:1060`), and — if effectful — hand entries in BOTH
   `host-toolkit-bindings` (`context.clj:1298`) and
   `host-toolkit-implementation-namespaces` (`context.clj:1306`). Two
   hand-maintained lists, which the repo bans.
3. **A new host capability family** — one `install!` block
   (`context.clj:635-1013`).

**Falsifier.** Adding a `my.*` namespace costs no more than adding a corpus
function, and no hand list is edited.

---

## R-15 — The demo breaks on DISCOVERY, not execution

**Symptom.** A small model on the claimant can only call what its five home
`require`s already name.

**Evidence.** Four discovery paths exist; on the surviving claimant JVM **three
are dead**:

- `grep-graph` — unregistered and CLJS-only;
- `my.ns/functions` — excluded, but **NOT by the async regex**; see the status
  note below;
- **`seon.embed/enabled?` is wired to `(constantly false)`** (`context.clj:731`,
  with `search-pull` a fixed `:user-input` error at `:729-739`) — semantic search
  over the corpus is hard-off for agents on the JVM host;
- the pushed `:namespaces` / `:function-menu` blocks — unreachable on the JVM.

**STATUS CHANGED — two of the three stated reasons were wrong, and the wrong
reasons inflate the port estimate.**

- *"no JVM caller at all; every consumer is `.cljs`"* is **false**.
  `src/seon/agent/ctx/namespaces.cljc` and `src/seon/agent/ctx/menu.cljc` are
  **both `.cljc` with `.cljc` consumers** (`agent/ctx.cljc`, `agent/home.cljc`,
  `config/resolve.cljc`). The block code is **already portable**; the actual
  blocker is `src/seon/host/invoke.clj:167-172` refusing render on the host —
  i.e. R-8c's ~1,273 CLJS-only lines, not these two files.
- `my.ns/functions` is written `^{:async true}`, which does **not** match
  `pure-block?`'s literal `\^:async` alternative (`context.clj:1060-1065`). It is
  excluded because its body contains `(await` (`src/my/ns.cljs:71`), `db/query`
  (`:76`) and `db/pull` (`:96`) — **database markers whose reason is VOID on the
  JVM, where `db/query` is synchronous.** This is the strongest single instance
  of R-14 and it was recorded here with the wrong cause. Correct it in
  `docs/seon/issues/host-base-agent-surface-parity.md:139-142` too.

An agent's reachable surface without discovery is **five** namespaces
(`src/seon/agent/home.cljc:95-112`).

**The token-economics table below is `[UNVERIFIED]` in one respect: no
reproduction command was ever recorded with it.** Treat the break points as
estimates, not measurements.

**Measured token economics** (for corpus sizing):

| | cost |
|---|---|
| one compact card | median **41** est. tokens (mean 45.7, p90 63, max 257) |
| whole current public schema-complete surface, 474 rows | **21,659** est. tokens |
| one `:seon.ns/summary` catalog line | median **20** est. tokens |
| catalog for all 206 namespaces | **4,078** est. tokens |

Cards break first at ~500 functions; the catalog survives to ~5,000 namespaces;
**search precision fails first at ~5,000 functions — and both searches are off.**

**Falsifier.** A small model, given only the pushed context, calls a corpus
function it has never seen and that no home `require` names.

---

## R-16 — There is no waiting state; `wait` closes the run

**Symptom.** Nothing durable records what a run awaits.

**Evidence.** `seon.agent.lifecycle/wait` (`lifecycle.cljc:314-338`) **CLOSES**
the run with `:seon.agent.run/closed-reason :waited`; the agent then has no run
pointer, and the only way back is a NEW run from an inbound message or a due
schedule. Messages carry no correlation attribute.

**But the mechanism already exists for one case.** `release!`
(`driver.cljc:562-563`) retracts `:seon.agent.run/claimant` while leaving the run
`:open` with its cursor intact — wait-without-a-thread, crash-surviving,
CAS-arbitrated. It awaits exactly ONE thing (the phase cursor), never a set.

**STATUS CHANGED — this row's "waking is genuinely event-driven, no JVM ticker"
claim is REVOKED. The wiring at `driver/host.clj:807-815` is the defect, not the
solution.** Two independent measurements:

- **D5, the stampede.** `scan!` commits; every commit fires `listen!`; every
  `listen!` submits a whole new `scan!`. Measured commits per useful run
  **7.0 → 14.4 → 124.8**; lost CAS claims **5 → 157 → 10,343**;
  `OutOfMemoryError` at n=20 after 2,555 scans. The claimant passes the **worst
  available option** — `:datahike.read/dependency-plan :all` with
  `(fn [_] (scan!))` (`src/seon/agent/driver/host.clj:809-814`) — a full
  open-runs query inline in the callback for **every cluster commit**. The fix
  is a **PARAMETER of the existing mechanism**, not new machinery:
  `seon.db.host/listen!` (`src/seon/db/host.clj:306-329`) already accepts
  `::protocol/datom-patterns` (e/a/v/added?, max 64,
  `src/seon/db/protocol.cljc:600-601`) and the writer already maintains a
  `::by-attribute` interest index (`src/seon/db/writer.clj:2860-2878`,
  `:2900-2905`). **This claimant-side `:all` listener is currently UNFILED.**
- **D2, the strand — structural.** Datahike's `listen` callback fires "on each
  transact" **only** (`api/specification.cljc:1076`), so a lease going stale —
  which is not a commit — can **never** be delivered by the
  committed-transaction feed, and the moment it matters is exactly when the feed
  goes silent. Measured: a stranded run stayed stranded four lease periods until
  an unrelated commit arrived. Prefer changing the interface so the claim
  publishes its own liveness (the commit that *sets* the lease is itself an
  event); a clock stays only as a loud last-resort backstop whose firing is a bug
  report.

Compounding, and verified: **the lease is never renewed during a drive.**
`beat-tx-data` (`src/seon/agent/run/core.cljc:160-170`) is reachable only through
`claim-plan`'s `:held` arm (`:138-142`); `drive-claim!`
(`src/seon/agent/driver.cljc:517-594`) re-reads state between steps and never
beats. With `stale-ms` 1,200,000 (`config/system.edn:619`) a healthy run driving
longer than 20 minutes is stealable — R-8d, confirmed.

**Design that adds no mechanism.** A waiting run is an OPEN, UNCLAIMED run;
readiness becomes one more clause of `eligible?`, reusing `:my.plan/needs` and
the existing `blocked`/`ready`/`open-work` Datalog rules
(`my/plan/internal.cljc:34-46`). **There are no counters, so the concurrent-owner
race dissolves** rather than needing `cas-assert`.

**Honest hole.** An owner agent that itself calls `wait` leaves its step
`:active` with no terminal fact — the waiter blocks forever with neither an event
nor a clock.

**Falsifier.** Kill an owner mid-fix; the waiting proposer resumes.

---

## R-17 — The diff already exists and is thrown away

**Evidence.** `compile-namespace-dag` returns
`::internal/diff {:my.plan/added N :my.plan/dropped N :my.plan/updated N}`
(`internal.cljc:1021-1023`); `publish-generated-program!` propagates it as
`::diff` (`my/plan.cljc:1321`); **`turn.cljs:771` checks only
`(false? (:my.plan/ok? publication))` and DISCARDS the whole value.**
`:my.plan/diff` is already a registered public agent-facing shape
(`my/plan.cljc:221-222`).

So "the caller gets back a diff of what happened" is **wiring, not design** — the
only real gap is that the counts are namespace-granularity where the owner wants
function-granularity.

---

---

## R-18 — MEASURED: the guard works, but only on a platform thread

Prototype run 2026-07-25 (`clojure -M:writer:host`, JDK 26.0.1, `-Xmx2g`),
one shared `sci/init` base forked per agent, one `:interrupt-fn` checking a
counter every entry and sampling clock + allocation every 1024 entries.

**On a platform thread — everything works:**

| case | outcome |
|---|---|
| `(reduce + (map inc (range 100000)))` | completes, 14ms |
| `(loop [] (recur))` | killed `:time` at 502ms, **271,197,184 fn entries** recorded |
| allocation loop in interpreted code | killed `:memory` at **exactly 64MB**, 21ms |
| allocation via `doall`/`repeatedly` (host call) | killed `:memory` at 64MB, 16ms |
| overhead, 3M-iteration loop | 38ms unguarded → **42ms guarded (~10%)**, both checks |

**On a virtual thread — allocation is unmeasurable.** Both
`getCurrentThreadAllocatedBytes` and `getThreadAllocatedBytes(id)` return
**`-1`**. JMX does not track virtual threads. Time-based kill still works
(verified: 200 agents, 100 runaway, all killed correctly). Memory-based kill
does not: the same allocation case died with a raw `Java heap space` instead of
a clean `:memory` verdict.

**Consequence for the design.** One virtual thread per agent for the *waiting*
(LLM, database, leaf calls) — cheap, scales, and allocation is not the risk
while parked. The SCI eval itself runs on a **platform thread**, where
allocation is measurable and killable.

**NEAR MISS — record this.** The Wave-2 audit recommended deleting the "fixed
10-thread platform eval pool + virtual→platform handoff", replaced by "the run's
own virtual thread". That deletion would have **silently removed memory
enforcement**, because the property depends on the thread kind. The existing pool
has the right shape for a reason nobody wrote down. The audit's other complaint
stands — the *fixed count* should be a semaphore — but the platform thread is
load-bearing and must survive the cut.

**STATUS QUALIFIED — "load-bearing" holds on a SECOND reason, and the first one
is conditional.**

1. Allocation measurability (the reason above) buys **only the
   `:seon.eval/allocated-bytes` DIAGNOSTIC** unless an allocation *limit* is
   authorized — and D14 shows the metric is **anti-correlated** with the heap
   risk it appears to bound (physics item 2). **Open ruling O4** decides this,
   and it decides the thread kind. The D8 time flag is a volatile boolean
   readable on **any** thread, so time enforcement does not need a platform
   thread.
2. **Independent and unconditional: agent code on a platform thread has no
   carrier-pinning surface.** Verified with
   `jdk.virtualThreadScheduler.parallelism=1` and 8 claimants wedged inside
   evals — an unrelated virtual thread still completed 5/5 steps in 902 ms;
   `Semaphore.acquire` and `Future.get` both unmount.

**And one written argument for moving SCI onto virtual threads is VOID.**
`simplification-design-2026-07-25.md:205-208` claims the parkNanos fairness fix
is "free on a virtual thread … ~1.4 ms each on a platform thread". Measured: raw
`LockSupport/parkNanos(1000)` is **3.6 µs/call platform, 11.9 µs/call virtual** —
~390x cheaper than the claim and **cheaper on the platform thread**. Over
3,000,001 fn entries at one park per 65,536 entries (45 parks): virtual +0.7 ms,
platform −7.3 ms. Noise on both. Delete that argument wherever it appears.

**Implementation trap, measured:** the `:interrupt-fn` must be ARMED ON THE
COMPUTE THREAD, not by the `:io` caller. Arming on the caller reported 183 KB for
a run that allocated ~67 MB and misattributed a `:memory` kill as `:time`.

**Correction to an earlier claim.** The predicted "host calls have no safepoint
so allocation there is uncatchable" is mostly wrong: `sci.interrupt`'s overridden
`doall`/`repeatedly` fire the interrupt-fn per element, so the allocation was
caught at the cap. The hole is narrower than stated — a *single* huge allocation
inside one un-overridden host call.

---

## Owner rulings — 2026-07-25 night, the blocking four

**O1. Co-location ACCEPTED.** Agent evals run in the database process. Reads
are a pointer into the immutable database value; writes are a function call.
The entire wire layer comes off the agent path. The blast radius is accepted
explicitly: nothing in-process bounds live memory, one large host allocation is
uncatchable, and an agent OOM is a cluster restart — downtime, not data loss,
because the lease resumes the work. Measured basis: an agent OOM did not take
the writer down (0 failed transactions, store consistent on reopen, one 180ms
latency spike against a 33ms median).

**O2. The wedge is a process kill.** The door promises only what a safepoint
can enforce. A CPU-bound host call reaches no interpreted fn body, the
`:interrupt-fn` never fires, and `Thread.stop` is gone — so a wedged eval is
detected by lease expiry and the process is replaced, with the run resuming
from receipts. State the hole; never imply the limits bound it. Benefit: the
recovery path is exercised routinely instead of never.

**O3. Spec gaps close in parallel with Wave 1.** They are documentation
consistency, not design error.

**O4. NAMESPACE-ADDRESSED RESIDENT AGENTS — the premise of "ownership" is
rejected.** There is no ownership claim, no lease, no CAS, and nothing stored.

- An agent's name IS a namespace. Ephemeral agents spun up for a short task get
  `seon.agents.<id>`.
- A failure in namespace N sends a message to N.
- If an agent already lives at N, **wake it and tell it what it broke.**
- If nobody lives at N, **spin up a new agent named for that namespace.**
- Reuse an existing resident; otherwise create.

So `:seon.ns/owner` is not a stored attribute — "who is responsible for N" is
**derived** from whether an agent's home namespace is N. This deletes the
ownership mechanism, the lease-renewal bug that came with it, and the
liveness question of what happens when an owner dies: nobody home means spin
one up. Already filed as
`docs/seon/issues/namespace-addressed-resident-agents.md` — that note is now
owner-ruled, not a proposal.

## Owner rulings — 2026-07-25 night, second batch

**O5. `:seon.agent.run/process` replaces `claimant`.** Ratified. Grounded both
sides: `script/seon/dev/process.clj` ↔ JDK `ProcessHandle`.

**O6. Sharing is AUTOMATIC, and the bar for now is a valid schema.** A function
becomes available to every agent when it is a valid function with a
`:malli/schema`. Nothing explicit interns it. The bar RATCHETS later — has at
least one unit test, has been generatively tested against its schema — but
those are added as further *attributes to filter on*, never as a second
mechanism. What agents see is a query over those attributes.

**O7. ONE messaging system, namespace-addressed, replacing the old one.** The
flow-shaped, database-fact, namespace-addressed messaging supersedes the
existing paths — it is not a second system beside them. Consolidate rather than
add.

**O8. Root and the owner can write into any namespace**, regardless of who is
resident.

**O9. CLUSTERS ARE CHEAP AND DISPOSABLE.** Run different agents on different
clusters, reset any of them at any time. **NO DATA MIGRATIONS, ever.** Lanes may
cycle a cluster freely for tests that need the artifact. This also makes
load-testing safe: drive a throwaway cluster to destruction.

**O10. Packages are scheduled AFTER the JVM system is solid** — after the new
flow, the new messaging, and agents doing real work safely. The
package-runtimes research is deferred, not cancelled.

**O11. Implementation goes to sol lanes; Fable for design review only**, used
sparingly.

**O12. IT IS NOT A PROTOTYPE. IT IS THE SYSTEM. RIP AND TEAR.**
(owner, 2026-07-25 night, overriding the disposition recorded an hour earlier.)

`src-flow-prototype/` moves into `src/` under real namespaces and the old paths
are DELETED. Not kept beside it, not migrated onto, not preserved until the new
one is proven. **Git is the archive** — every deletion is recoverable, so there
is no reason to carry both.

The owner's reasoning, and it is the sharpest statement of the failure mode
this whole ledger exists to prevent: *keeping the old one and the new one at
the same time is how the design gets broken.* Every hour both exist is an hour
someone reconciles the new design against the old one's shape — which is
exactly how the previous conversion became a port.

Consequences:

- The sixteen defects D1-D16 are fixed **in `src/`**, not in a sandbox first.
  CUT FIRST, SEAM-FIX SECOND already rules this: a discovered seam defect gets
  a one-line issue and the cutting continues.
- The attack suite becomes real tests under `test/`, not a parallel harness.
- "Wait until it is production-ready" is REFUSED. It becomes production by
  being the only thing there.
- Deleting the placement layer from the old driver is subsumed — that driver is
  going. The lane doing it is being widened rather than finished.

## Vocabulary — RATIFIED by the owner, 2026-07-25

Owner ruling 2026-07-25: invented language is the loudest complaint. Where SCI,
Clojure, or the source material already has a word, that word wins. These rows
move into the `AGENTS.md` vocabulary table once ratified; each names the
defining source on BOTH sides, per that table's own rule.

| Proposed | Replaces | Defining source |
|---|---|---|
| `:interrupt-fn` | "the guarded door", "the door" | `sci/doc/interrupt.md:6` ↔ `seon.sci.interrupt` |
| `interrupt!` | `guard/stop!`, `steering-error!` | `sci/src/sci/interrupt.cljc:32` |
| `time-limit` | `deadline-ms` | `sci/doc/interrupt.md:26` (**corrected**; `:32` is a usage line) |
| `time-limit` is the **only** limit | `interpreter-step-budget`, **"fuel"** | `sci/doc/interrupt.md:26` |
| `:io` / `:compute` / `:mixed` | invented workload words | `reference-code/core.async/.../async.clj:561-563` — `thread-call`'s docstring, where the dependency **defines** them (`impl/dispatch.clj:127-133` only *constructs* the executors) |
| sliding-buffer of one | "dropping buffer", "latest-wins queue" | `reference-code/core.async/.../async.clj:125-129` ("oldest elements in buffer will be dropped"); a dropping-buffer discards the **newest** (`:119-122`) |
| `:seon.eval/fn-entries` — a recorded count, **not a limit** | any step/call budget | `sci/doc/interrupt.md:50` ("every `fn` body entrance") |
| "every `fn` body entrance" | **"safepoint"** | `sci/doc/interrupt.md:50` |
| `ctx`, `fork` | "warm base", "the agent's world" | `sci/src/sci/core.cljc:318` |
| `seon.sci.interrupt` / `.ctx` / `.eval` | `seon.host.guard` | mirrors `sci.core` / `sci.interrupt` |
| **accretion** / **breakage** | "graduation", `:nursery`, `:graduated` | **`[UNVERIFIED]` — see the note below. Use the WORDS; do not print the citation.** |
| `:seon.fn/private?` | a new visibility flag | **already exists**, 26 uses (`src/seon/schema.cljc:423`, `src/seon/host/record.clj:154`); Clojure's `defn-`. **Spelled WITH the question mark** — the row previously printed `:seon.fn/private`, which is itself an invented name. |

**`[UNVERIFIED]` — the accretion attribution.** *"accretion"*, *"breakage"*, and
the rule *"require no more, provide no less"* attributed to Rich Hickey's
*Spec-ulation* (Clojure/conj 2016) is **NOT VERIFIED**. The lane assigned to
confirm it failed; the 2026-07-25 evening reconciliation did not verify it
either — no vendored source under `reference-code/` contains the talk and no
external material was fetched. **The words may be used; the citation may not,
until confirmed against a primary source.** It is **already asserted as fact in
the committed tree** at
`docs/seon/issues/output-map-closedness-decides-accretion-legality.md:15-16`,
which defeats this marker unless that line is marked in the same pass. **That
line was still unmarked at the time of this audit.**

**"claimant" is a Seon COINAGE, not ratified vocabulary.** `rg -i claimant
reference-code/datahike/ -l` → **0 files**, against `rg -c claimant src/` → **25
files**. Proposed replacement `:seon.agent.run/process`, grounded on both sides
(`script/seon/dev/process.clj:95, :910, :918, :929-938` carries the process
record with `(pid, start-instant)` and generation ↔ JDK `ProcessHandle`).
**UNRATIFIED — open ruling O3**, to be decided in the same change as the
result-symbol identity (R-11) so one word covers process identity in both places.

**`:seon.ns/owner` DOES NOT EXIST in source.** Registered `:seon.ns` attributes
are name, source, doc, summary, require-edges (`src/seon/ns/source.cljc:17`,
`:19-37`, `:45`, `:46`). Any design naming it is proposing a **new** attribute,
not a repoint.

**Vocabulary debt in this file.** Rows R-1..R-18 above were written before the
ban and still use **"safepoint"** for what SCI calls **"every `fn` body
entrance"** (`sci/doc/interrupt.md:50`). Read them with that substitution; do not
propagate the word into new text.

### Why `interpreter-step-budget` was wrong

VERIFIED: the SCI interpreter contains **zero counters**. `:interrupt-fn` fires
at exactly three sites, all in the fn invocation trampoline
(`fns.cljc:52`, `:77`, `:166`) — once per fn invocation and once per `recur` —
plus per-element inside the `sci.interrupt` sequence overrides. There is no
"step". The name asserted a mechanism the dependency does not have, which is
why the default sat at 100,000,000 and was never calibrated.

### Settled design consequences

- **Containment is two mechanisms**: one `:interrupt-fn` bounded by *time*, and
  process replacement. Output cap and result limit are value-size limits at
  serialization and move to where the value is produced.
- **Time is read from a flag set by a timer, never polled from the clock** in
  the hot path. A volatile read is ~1ns; `nanoTime` is ~25ns and is a large part
  of why the current check costs 4× the compiled body it protects.
- **The call count survives as a diagnostic, not a limit.** 30s + 400M fn
  entries reads as a runaway loop; 30s + 12 fn entries reads as blocked in a
  host call. Same fact, two different messages to the agent.
- **Long computations are declared, not guessed.** Short default `time-limit`;
  an agent intending a long computation says so, and that becomes a fact the
  lease renewal honours.
- **Ownership assigns responsibility, not write access.** Owner ruling: the
  cross-namespace proposal evaluates everywhere immediately (already built);
  `:seon.ns/owner` decides who receives the failures for a namespace.
- **Availability to other agents is derived from stored evidence** (test passed
  at this source fingerprint), never from a stored tier.
- **Waiting is a database state, never a parked thread.** Same mechanism for an
  LLM call, a subagent, a user reply, and a package-runtime call.

---

## R-19 — Rulings settled in conversation, 2026-07-25

These were owner-ruled or settled in the design session and existed in **no
file** until the evening reconciliation. They are recorded here because the
ledger is the deletion authority and several of them change what may be cut.

### 19a — ONE CORPUS, UNIVERSAL (owner ruling)

There are **no separate concepts** for agent-authored code and the rest of the
system. Quality is **attributes on the row** (`:seon.fn/spec`,
`:seon.fn/schema-error`, `:seon.fn/source-fingerprint`, `:seon.test/*`), and
**advertising is filtered by those attributes**. What is personal to an agent is
its **environment** — entity, messages, context blocks — **never its code**.

This is already the data model: `seon.db.program` reconciles first-party boot
code into the **same identity space** as agent-authored code and separates the
two by **provenance** — a join through the transaction to
`:seon.db/process :seon.db.process/boot` — never by a kind stamp
(`src/seon/db/program.clj:19-20`, `:40-45`). **The one surviving kind stamp is
`:seon.fn/execution-tier`**, which is exactly the attribute the plan deletes; see
the same-commit companion recorded under "What must NOT be deleted".

**Runtime consequence, and it is the whole of `seon.sci.ctx`:** ONE shared base
ctx per process, ONE `sci/fork` per **eval** for in-flight isolation only, and
**NO retained per-agent ctx**. See R-8a.

**Falsifier.** After the cut, `rg` finds no per-agent ctx map, and a query over
the corpus distinguishes boot rows from authored rows by provenance alone — no
attribute encodes "which kind of code this is". A port that adds an eviction
policy to `:seon.host/contexts` fails: the retention *is* the rejected model.

### 19b — Graduation splits in two (owner ruling)

- Compiling agent code to a native JVM fn **deletes the `:interrupt-fn` and must
  never happen.** This supersedes any reading of R-4 that treats a compiled tier
  as a goal.
- "A proven function becomes available to every agent" is a question of **which
  namespace the var is interned in** — ordinary Clojure, **nothing to do with
  speed**. `simplification-design-2026-07-25.md:395-396` objects that `sci/fork`
  snapshots the namespace map so a promoted var is absent from already-forked
  agents; **that objection holds only in the retained-per-agent-ctx model 19a
  rejects.** With one shared base and a fork per eval, a var interned in a base
  namespace is visible to the next fork.

### 19c — Namespace ownership assigns RESPONSIBILITY, not write access

`:seon.ns/owner` decides **who receives the failures** for a namespace. The
cross-namespace proposal **evaluates everywhere immediately** (already built).
This resolves the owner's own tension between "one agent per namespace" and
"large cross-namespace proposals". **Falsifier:** a proposal failing in 3
namespaces sends exactly 3 messages, and **no write is refused anywhere**. Note
the attribute does not exist yet.

### 19d — `:seon.eval/fn-entries` is a RECORDED DIAGNOSTIC; `time-limit` is the only limit

There is **no fn-entry limit** (owner ruling; an earlier proposal that named one
was explicitly corrected). The diagnostic is what makes the error message good:
**271M fn entries in 500 ms reads as a spin; 12 entries in 500 ms reads as
blocked in a host call.** Same fact, two different messages to the agent.

`:seon.eval/allocated-bytes` is likewise a diagnostic and **not** a limit —
pending O4, which is the ruling that decides the eval thread kind (R-18).

### 19e — Flow control is three cases, not one

| traffic | policy | mechanism | why |
|---|---|---|---|
| agent messages | **never drop** | a committed datom; delivery derived by one predicate (`src/seon/agent/message.cljc:286-307`, whose own comment names it "ONE source of truth for this message wakes") | losing one loses work |
| unclaimed runs | **queue in the database** | inspectable durable backlog; backpressure is the CLAIM, semaphore-bounded (measured: 22 evals against 18 permits → 4 queued, 71 ms max wait, zero bounced claims) | a queue you can query is a feature |
| Datastar frames | **latest wins** | already built — `enqueue-latest!` does `(.clear mailbox)` then `(.offer mailbox value)` (`src/seon/web/feed.clj:22-25`) | a morph is absolute, so dropping is lossless |

**Why dropping is safe there and ONLY there.** Datastar's default
`ElementPatchMode` is `outer`, docstring *"Morphs the element into the existing
element"* (`reference-code/datastar-clojure/.../consts.clj:46-48`, default at
`:79-82`). A morph carries the **complete** element, so any frame supersedes
every earlier one — the same property as reconnect = repaint. Datastar **does**
have incremental modes (`append`/`prepend`/`before`/`after`, `:62-77`), so **the
property belongs to Seon's usage, not to Datastar**:
`(datastar/patch-elements! sse value)` at `feed.clj:40` is the sole patch call
under `src/seon/web/` and `src/seon/ui/` and passes no opts. Reaching for
`:append` silently breaks the drop.

**Free deletion:** `::mailbox-depth` is dead configuration — `.clear` before
every `.offer` on every path makes depth > 1 structurally unreachable, yet the
knob costs `config/system.edn:171`,
`src/seon/config/resolve.cljc:284`/`:1134-1135`/`:2055-2056` and
`src/seon/web/server.clj:21,33,293`.

### 19f — The flow admin surface is FREE, and this is the strongest single argument for the design

`core.async.flow` ships `start` with `:report-chan` and `:error-chan`, `stop`,
`pause`/`resume`, `pause-proc`/`resume-proc`, `ping`, `ping-proc`, `inject`
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:107-158`).
Every one maps onto something Seon already has, and every mapping is **strictly
stronger**, because a query is durable, historical and available to anyone while
a ping is a live probe answering one caller once:

| flow | seon |
|---|---|
| `ping` / `ping-proc` | a query over run and receipt facts |
| `pause` / `resume` | release / reclaim the run (claim CAS + epoch) |
| `inject` | commit a fact |
| `:report-chan` | the committed-transaction feed |
| `:error-chan` | fault datoms |

Stronger still: `ping` takes `timeout-ms` (default 1000) and returns status only
"for those procs that reply within timeout-ms" — **flow's own introspection
silently omits the wedged process you most need to see**, which is exactly the D9
case.

**Flow needs an admin API because its state is hidden in memory. Putting the
state in the database does not replace that surface — it DELETES THE NEED FOR
ONE.**

### 19g — Form granularity is FORCED, not chosen

The **identical form** answered **0** against the turn's opening basis and **9**
against the step's basis. A turn-level transform
`(db, agent, message) -> [tx-data, messages, effects]` cannot express
read-your-own-writes and was never built. **The turn is a fold of forms; the
resume unit is the form.** Read-your-own-writes costs **zero extra round trips** —
each step's basis is the previous step's transaction report `:db-after`, which
Datahike already returns.

This is the design's single strongest simplification, it survived every attack
aimed at it, and **no recurring test claims it.** The turn-level signature was a
**shape PORTED from `core.async.flow`**, caught by review and then falsified by
measurement — the clearest instance of this ledger's own anti-port rule catching
itself.

### 19h — Performance is not the problem

One 7-step turn measured **~104 ms/step of which SCI eval is 0-5 ms**, across 12
transactions per turn, **before an LLM call that dwarfs everything**. **SCI is
~5% of a turn.** The JIT, graduation-as-speed and every compiled-tier proposal
optimize that 5%. The only real performance problem is the ~10 s JVM boot, and it
is **orthogonal to this architecture**. Write this down or the numbers read as an
argument *for* a compile tier.

### 19i — Two caches at two levels, unrelated

- **AOT + AppCDS** caches **Seon's own** compiled JVM classes at process boot and
  changes only when Seon is rebuilt.
- **The SCI base ctx** caches agent-visible namespaces, built ~80 ms after boot.

**Agent-authored functions can NEVER enter AppCDS** — `effective-tier` returns
`:nursery` unconditionally (`src/seon/host/graduate.clj:128-134`), so every
corpus function executes through SCI as a `:seon.fn/source` row and never becomes
JVM bytecode.

**The AOT-versus-AppCDS correction, quantified.** AppCDS caches **class loading
only**; the 6.1 s is **compilation** — Clojure namespace loading *is*
compilation; **only AOT skips compilation** and AppCDS then caches the result.
Measured on `datahike.api`: 6,568 → 755 (AOT) → 300 ms (AOT + archive) = **92.7%
AOT, 7.3% AppCDS**. The earlier claim that *"AppCDS targets it directly"* was an
**overclaim and must not be repeated.**

**Boot is a separate axis with a separate owner.** The ~10 s JVM namespace load
is **not** the 271 s pod cluster reset: AOT+AppCDS does nothing for the 271 s, and
de-quadratic `build-projection` does nothing for the 10 s. Two line items, two
owners.

### 19j — Corrections this session made, recorded so they are not re-derived

- *"There is no function-level call edge"* was WRONG — and its own correction was
  half wrong (R-9).
- *"core.async's `go` blocks impose async contagion"* was **WRONG** for modern
  core.async on this JDK: `go*` expands to `(thread-call (^:once fn* [] ~@body)
  :io)` — a virtual thread, **no state-machine transform** — whenever virtual
  threads are available (`async.clj:519`, `:530`). `^:async` in CLJS is a real
  transform; `go` here is not.
- *"200 concurrent"* was a **BENCHMARK SIZE, not a limit**. Curve
  30.25 / 6.73 / 2.04 / 0.73 ms/tx at n = 1/10/50/200, still improving; **the
  ceiling was never found.** (A separate dedicated 200-transaction benchmark
  reported 0.53 ms/tx against 45.09 serial — two different runs; **name the run
  with each number**.) Mechanism: Datahike's writer is a serial processing
  go-loop feeding a commit thread that drains the queue as one batch-commit with
  `DEFAULT_COMMIT_WAIT_TIME = 0` (`writer.cljc:83`, `:100-188`, `:213`, `:266`),
  so batch size **self-tunes upward with offered load**. Seon sets **neither**
  `:commit-wait-time` nor `:transaction-queue-size` — an unexplored free dial on
  the one measured cost centre.
- **Both proposed fixes for the vector-order bug were killed by evidence.**
  Tuples throw above 8 homogeneous values and element-level Datalog against a
  tuple returns nothing; component refs written in permutation pull back in
  ascending **entity ID** order. **The cause was wrong DECLARATIONS, not a wrong
  bridge** — and the fix landed as commit `5a37489c6` with **zero** lines of
  `form->cardinality` or the tuple trigger changed. The rule the episode teaches,
  stated in no architecture document: **Datahike has exactly two cardinalities
  (`schema.cljc:59`), so order is NEVER a property of the collection type** — it
  is a stored ordinal on the child, a recovered transaction id, or an explicit
  sort key.
- **Line counts were overclaimed.** "~250 lines" → **450-550 projected**;
  "~10,000+ replaced" → **~6,994**. Ratio **13-15x, not 40x**. Two subtractions
  must travel with it or it becomes a lie: `host/context.clj` (2,181) +
  `agent/ctx.cljc` (1,959) = 4,140 lines are a **PORT** (R-8c), and ~3,650 wire
  lines **SURVIVE** for web-render (R-8b).
- **The `:interrupt-fn` cost is ~3x, not 44x**, and **there are TWO
  `:interrupt-fn` shapes that every prior analysis conflated**: `build-base!`
  installs a `sci.ctx-store/get-ctx` closure (`context.clj:1410-1413`) while
  `fork-context` **overwrites** `:interrupt-fn` on every agent fork with a
  closure over the holder (`:1423-1430`). **Agent evals never pay the ctx-store
  deref**, worth 14.3 ns/entry — 47% of the base overhead. Settled in situ over
  3,000,001 real fn entries: none 24.6 ms; agent-fork 73.4 ms (16.3 ns, 2.98x);
  base ctx-store 116.4 ms (30.6 ns, 4.73x); target closed-over `long-array` + one
  volatile read 34.5 ms (3.3 ns, 1.40x). The fix owner is `guard.cljc` **and**
  `context.clj:1410-1413`.

### 19k — Open owner rulings this ledger is waiting on

O1 co-located writer heap blast radius (and the un-interruptible single host call
that no in-process metric can bound — physics item 2); O2 horizontal claimant
topology vs `architecture.md:242-246` (physics item 7); O3 replace the coinage
"claimant"; O4 **is there an allocation LIMIT at all** (decides the eval thread
kind, R-18); O5 which tuple trigger survives the bridge reconciliation; O6 which
runner claims `src-flow-prototype/`; O7 commit the AOT/CDS work; O8 the
release-path AppCDS hole. Full statements and recommendations:
`implementation-plan-2026-07-25.md` §3.

---

## Status

Seeded 2026-07-25 from the design session's own verified reading plus the
2026-07-24 WTF review. R-1..R-6 verified at HEAD by direct file reads.
R-7..R-9 and the physics list added from the 20-agent parallel design audit of
the same date; its target design is `simplification-design-2026-07-25.md`.

Updated 2026-07-25 evening by the completeness audit, after the prototype
(`flow-prototype-2026-07-25.md`, D1..D16) and the in-situ re-measurements
(`measurements-2026-07-25.md`). Rows with a changed status: **R-5** (citation
wrong, ceiling measured), **R-8a** (retention is the rejected model *and* the
leak; R-8a itself not falsified), **R-9** (the `::calls` conclusion is wrong),
**R-15** (two of three stated reasons wrong), **R-16** (loses its
event-driven/"no ticker" status — the wake wiring is the defect), **R-18**
(qualified: one justification is conditional on O4, the parkNanos argument is
void), **physics 2** (an allocation budget cannot bound it either), **physics 7**
(`create-writer` does not ship only `:self`), **the do-not-delete list**
(`graduate.clj` splits by caller; `:seon.fn/execution-tier` is not free), and
**the vocabulary table** (three citations corrected, accretion marked
`[UNVERIFIED]`, "claimant" marked a coinage).

No row is closed without evidence against its falsifier.
