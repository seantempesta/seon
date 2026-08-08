---
type: prd
status: active
tags: [prd, runtime, platform, boot, testing]
---

# seon.env — the environment is a value (RULED, owner, 2026-08-07)

The environment — everything that makes one cluster's computation different
from another's — becomes ONE explicit value, constructed only by boot,
carried by the sci ctx for agent code, by submissions and proc args for
thread crossings, and by request maps for the web. The dynamic-var carrier
layer (dynamic vars, ThreadLocals behind process-global facades, load-time
registration) is deleted. A parallel provisioning design becomes unwritable,
not merely forbidden: the ingredients only exist inside the environment
value.

Owner rulings recorded in this document (chat, 2026-08-07 afternoon):

1. **Name and visibility.** The owning namespace/schema is `seon.env`. Its
   keys are the EXISTING names (`:seon.db/connection`, `:seon.db/db`,
   `:seon.schema/projection`, `:seon.cluster.agent/id`, …) — no new umbrella
   nouns inside the value. Agents are scoped as needed; the container is not
   handed to agent code, but its contents are supplied by declaration. Root
   can see every cluster's environment and evaluate code in any cluster's
   context to debug — through a named, recorded platform function (the
   cross-cluster evaluation MCP `eval_clj` already models), never through a
   dynamic var.
2. **r2 amendment.** The sealed r2 invariant "`seon.db/*conn*` remains the
   one dynamic source of custody" is AMENDED: providers read the runtime
   ctx's environment at call preparation; the dynamic vars
   (`seon.db/*conn*`, `seon.effect/*request-context*`,
   `seon.render/*walk-context*`, the schema projection bindings) are deleted
   in the same change. Time travel and cross-branch work need no dynamic
   var: the db value is a supplied default (`as-of`/`history`/`since` are
   ordinary verbs over it), foreign branch values are obtained explicitly
   and win by the caller-wins rule, custody-fenced.
3. **Sequencing.** This design is the spine of the test-infrastructure work
   ([test-infrastructure-spec-2026-08-07.md](test-infrastructure-spec-2026-08-07.md)),
   not a separate wave. Tests consume the same constructor via fork; the
   parallel suite at load is the standing stress test of environment
   isolation ("this is a stress test of our multi branching system", owner).
4. **Boot contract.** Boot is a 0→1 process: the REPL is never hostage
   (prepl at second zero survives later-layer failure), failures are clear
   flat errors naming the failed layer, and a partial environment is never
   handed out. Running code receives, never constructs.
5. **Naming (owner, evening).** No "ambient", no "tower". Call things what
   the interfacing libraries call them or the literal common name: dynamic
   vars / thread-local bindings (Clojure's names for the carriers),
   **call preparation** (sci's name for the hook seam — supersedes
   "ambient injection" as the working name; the P17 documents keep their
   historical titles), **boot** (just boot, in dependency order), supplied
   **defaults** (not "batteries"). Database temporality uses Datahike's own
   words: `d/db` on a connection is CURRENT; a database VALUE is pinned;
   the transaction report's `:db-after` is the next value.
6. **Database temporality — both modes are first-class (owner, evening).**
   "Sometimes I want a DB value and the ability to get the next db value
   via the transaction return and sometimes I just want it always to be
   current." Encoded below in [[#Current versus pinned database values]].
7. **Sequencing vs gate-red (owner, night).** Gate-exit finishes first;
   Phase 1 starts at bare green. The parallel-turn hang is investigated
   through the isolation lens before anyone symptom-patches it.
8. **Hook consultation cost is implementation discretion (owner, night).**
   The ~80 ns vs ~9 ns question is a performance ordering detail only —
   the safety guarantees (time limit, interrupt, output caps, admission
   bounds) are a separate mechanism and are unaffected; Phase 1 fixes the
   unarmed-thread hole regardless. Land the simple hook first; add
   plan-gating when the S1 machinery lands.
9. **The read-evidence sink rides the environment (owner, night).** The
   environment carries an optional declared evidence-sink handle; the db
   read path offers evidence to it when present. The last surviving
   dynamic var joins the deletion list.
10. **Cross-cluster debugging runs AS the target agent (owner, night).**
    Root is limited to its own cluster like everyone else; debugging
    another cluster means opening the evaluation as one of THAT cluster's
    agents, in that cluster's context — records land naturally as that
    agent's activity, no separate root log. The real initiator may be
    named through ordinary transaction metadata (the existing
    `:seon.db/user`/`:seon.db/process` provenance), never a second record
    path. This REPLACES the earlier "named cross-cluster platform
    function" phrasing in ruling 1: the mechanism is agent selection, not
    a cross-cluster eval capability.

## The defect this repairs

The [parallel isolation audit](../research/parallel-isolation-audit-2026-08-07.md)
(seven probes run, four failures, two design defects):

- **Defect I — the environment rides thread-local bindings.** Cluster
  identity, declarations,
  connection, and request identity ride thread-local dynamic bindings read
  through process-global facades. A raw or virtual-thread hop silently
  drops them all together — probed for the schema registry, `seon.db/*conn*`,
  and `seon.effect/*request-context*` in one shot. Every capability request
  crossing the guarded door on `:io` runs with no cluster identity today; it
  survives only because with one cluster the fallback happens to be right.
- **Defect II — derived state parked in process-wide slots.** One compiled
  validator generation for the whole JVM with a check-then-re-deref race;
  reproduced reading another environment's validators in both directions.

The platform's forking primitives are sound (branch forks at 24-way, sci/fork
at 16-way concurrency, shared executors, lease pool — all probed). What is
missing is only that code inside a fork can name which environment it is in.

## Source grounding — the pattern, from four deep dives

The four dated reports (all in `../research/`, every claim file:line):

- [flow](../research/environment-mechanism-flow-2026-08-07.md) —
  core.async.flow conveys NO bindings anywhere by design (zero `bound-fn`
  under `flow/`); the flow-native carrier is `:args` → state, delivered at
  proc start, re-delivered free on graph rebuild. Verdict: one
  namespaced key carrying one immutable map on every submission and in
  every proc's args; the three `bound-fn*` sites deleted; a missing
  environment refused at construction.
- [sci](../research/environment-mechanism-sci-2026-08-07.md) — the ctx
  travels with the code, not the thread: every interpreted node evaluates
  against the ctx it was built with; a closure handed to a virtual-thread
  executor still carries its captured ctx (`:interrupt-fn` already rides
  this path). Everything else in sci is thread-local. The P17 hook must
  read the RUNTIME ctx (a present bug: the built-in call observer is read
  from the analysis ctx). Containment: the environment container is a host
  record absent from `:classes`; no installed function returns it.
- [system composition](../research/environment-mechanism-system-composition-2026-08-07.md)
  — adopt the value, reject the registry: integrant's dependency-ordered
  init producing a flat map with the config's exact key set, refuse-up-front
  before any side effect, reversed teardown, free subset boot; hyperlith's
  ctx map merged into every request surviving three thread transitions with
  zero dynamic vars. Reject every open extension point (defmulti resolving
  arbitrary keywords to vars); dev conveniences live in a separate
  REPL-only namespace.
- [malli + datahike](../research/environment-mechanism-malli-datahike-2026-08-07.md)
  — compiled state hangs off the value it derives from (Malli validators on
  the schema instance; Datahike rschema/writer on the db record/connection),
  so cache identity is structural and there is nothing to invalidate.
  Shared caches are keyed by complete identity, never a "current X" slot.
  Protocols only where a second implementation exists: **the environment is
  a map, not a protocol.** Malli's strict mode
  (`-Dmalli.registry/mode=strict`) makes the global-registry mutator throw —
  an enforcement lever we turn on.

Independent confirmation: upstream hyperlith's lockstep rework (23 commits
past our pin, inspected 2026-08-07) delivers its effects entry point `tx!`
as a member of the ctx map, captured lexically — the same two moves
(environment as a value in the request; effects entry as an environment
member). Borrowable and queued below: tick-coalesced write batching
(measure, never adopt blind) and deterministic broadcast order.

## The design

### The value

`seon.env` declares the environment schema: a flat open map of existing
keys. Initial contents (exact set settled at implementation against the
current `*request-context*` assembly and ctx custody):

- `:seon.db/connection` — the cluster's live branch connection (custody);
- `:seon.db/db` — supplied at basis by the provider, not stored;
- `:seon.schema/projection` — the acquired projection (carrying its own
  compiled-validator cache, per Defect II);
- `:seon.boot/cluster-name`, `:seon.cluster.agent/id`,
  `:seon.cluster.run/id`, `:seon.cluster.run.form/ordinal` — identity;
- `:seon.flow/work-launcher`, `:seon.sci.admit/caps` — capability handles.

The container is a host record type not registered in sci `:classes`, so
agent code cannot traverse it; its contents reach agent code only by
declaration (injection). Platform code passes it, or its members,
explicitly.

### Construction — boot, the only constructor

Boot builds one environment per cluster in dependency order
(store → branch → facts/projection → ctx → graphs), refusing the
configuration up front and naming the failed layer in a flat error. The
prepl opens at second zero and survives later-layer failure. The
production constructor (`start-fork!` in the test-infrastructure spec) IS
the test bracket's constructor; subset boot (store+facts, no web) is
supported for tests that want less. Teardown is the same graph reversed.

### Carriage — how it travels, one rule per medium

1. **Agent code: the sci ctx/fork.** The environment attaches to the
   per-cluster ctx; each turn's fork carries it; closures capture it across
   any thread by construction. The P17 call-preparation hook reads it from
   the RUNTIME ctx and fills declared-and-absent arguments (schema is the
   request; caller wins; unavailable is a flat error). The r2 design
   otherwise stands.
2. **Thread crossings: data on the submission.** `submit!`/`submit!!`
   require the environment on the submission map and merge it into what the
   work-fn and `complete!` receive. Proc `:args` carry it at graph
   construction. No `bound-fn*` anywhere; missing environment is refused at
   construction.
3. **Web: merged into the request.** Each cluster's web service is built at
   boot holding that cluster's environment; handlers receive it with the
   request map; the render walk uses its explicit walk context; SSE writer
   procs receive it as proc args.

### Current versus pinned database values

The environment stores the CONNECTION, never a database value. Two modes,
both first-class, selected by what the caller does — Datahike's own
semantics, no new mechanism:

- **Current (the default).** A function that declares `:seon.db/db` and is
  called without one receives `(d/db connection)` derefed AT CALL TIME by
  the provider — always the latest committed value. This is the right
  default for most reads (renders, probes, one-off queries) and is why the
  provider derives rather than the environment storing: a stored value
  goes stale silently.
- **Pinned (explicit, for consistency).** A caller that needs one
  consistent basis passes the database value explicitly and it WINS —
  never replaced by injection. The next value comes from the transaction
  report's `:db-after` (or `as-of`/`since` for time travel), so a
  consistent chain is `opening-db → transact! → :db-after → …`, exactly
  the run loop's shape (the reduce over forms whose accumulator is the
  basis; `run/opening-db` via `as-of` already landed). The run loop, plan
  execution, and any multi-read invariant use this mode.

The rule in one line: **elide for current, pass for consistent.** Both ride
the same declared contract; there is no mode flag, no second function, and
no way to get a silently mixed basis — a call either derefed once at its
own preparation or used the caller's explicit value throughout.

### Derived state

Compiled/derived state hangs off the value it derives from: the
compiled-shape and identity-only caches move onto the projection value.
Any surviving shared cache is keyed by complete identity
(cf. Datahike's `[connection-id generation commit-id]` query cache) and
read exactly once. `ensure-shape-generation-for!`'s check-then-act shape is
banned.

### Deletion list (the dynamic-var half dies)

- `seon.db/*conn*`, `seon.effect/*request-context*`,
  `seon.render/*walk-context*`, `seon.cluster/*source-progress!*` /
  `*boot-progress!*` as carriers, the four schema projection dynamic vars,
  and the registry facade's cluster-specific global backing;
- the 22 load-time registration sentinels + `packaged-base-forms` +
  `!source-files` — replaced by acquisition at a basis;
- `!predicate-functions` — replaced by `requiring-resolve`d Vars keyed by
  qualified symbol;
- `!shape-generation` / `!identity-only-generation` as process slots;
- the three `bound-fn*` conveyance sites in `seon.flow`;
- `seon.schema/*verified-release-identity*` (namespace-load env read) —
  becomes an acquired config fact;
- the bespoke `seon.db` elision internals (`current-database-value`,
  `current-connection`) per the r2 deletion boundary, plus the named
  non-owner readers: `src/my/background.clj:54`, `src/seon/search.clj:411`,
  `src/seon/fs/jvm.clj:453`, `src/seon/render.clj:521-523`,
  `src/seon/web/jvm.clj:301-303,384-389`.

### What stays mutable, and the enforceable rule

From the [atom census](../research/atom-census-2026-08-06.md) (77
constructors): the process-root resource registries (`running-instances`,
`root-store-holder`, `held-flocks`, `search/owners`) stay — they hold live
OS/JVM objects, become boot's private bookkeeping keyed by cluster/store,
and running code never reads them (handles arrive through the
environment). Invocation-local coordination stays. The rule the edit hook
enforces:

**A mutable reference is admissible only if it holds a live resource handle
or invocation-local coordination — never facts, never state derived from a
value, and never anything that varies by cluster.** If two clusters would
need different contents, it belongs in the environment. The exemption set
is the declared resource-owner namespaces (a fact, not a judgment call).

Enforcement stack: no side channel exists (carriers deleted); Malli strict
mode on; sci refuses unregistered classes; the program-graph query pattern
from the test-infrastructure spec (construction owners as resolvable
`:seon.fn/calls` targets) — never a namespace prefix, filename, or regex.

## Also in scope (platform items this design does not itself fix)

1. **Interrupt-arm probe (top hypothesis, unprobed).** The `:interrupt-fn`
   arm is a ThreadLocal on the process guard; work handed across a thread
   by agent code plausibly runs unarmed. Falsifier first; if confirmed, the
   arm rides the ctx/fork and submissions like everything else.
2. **Dropped-fault durability.** The `drops` counter violation — dropped
   core faults reach the fault committer as facts
   ([issue](../../../seon/issues/dropped-core-fault-count-is-not-durable.md)).
3. **sci observer bug.** The built-in call observer is read from the
   analysis ctx (`analyzer.cljc:1719`); a node cache would pin the wrong
   fork's observer. File and fix in the maintained fork with the hook work.
4. **Work-launcher executor gap.** The launcher graph passes only
   `:compute-exec`, so its loop runs on core.async's global `:io` executor,
   not the process root's (flow report).
5. **Hyperlith deltas (measured, not adopted blind):** tick-coalesced write
   batching for high-churn non-receipt paths; deterministic broadcast order
   in render fan-out; pin bump for the upstream-delta sweep.
6. **Docs-as-queryable-facts** is explicitly OUT of this PRD — separate
   short design after this seals (prior art: the pod-era "DB stores the
   path, file keeps the body" thesis; the current-era skills + corpus-facts
   mechanism).

## Phase 0 findings (running log)

**Runtime-ctx hook — MINIMAL-EDIT VIABLE**
([report](../research/env-phase0-runtime-ctx-hook-2026-08-07.md)). No-edit is
refuted with file:line (the observer never sees copy-var leaves, discards its
return, and takes no ctx; `wrap` is analyzer-internal, unreachable from
options, and replaces the callee rather than reshaping args). The ~30-line
hook on sci branch `seon-env-hook-probe` (submodule commit `a072c8e`, pin
untouched) validates the contract
`(hook runtime-ctx var evaluated-args) -> prepared-args | (reduced result)`,
firing on the calling thread inside the node body: declared-and-absent
filling, caller-wins, nested + interpreted-defn call sites, 320 concurrent
virtual-thread calls across 8 forks with zero mismatches, unavailable
short-circuits without entering the callee; sci suite green. Three
constraints the implementation MUST absorb:

1. **The environment is `assoc`'d onto the ctx, never passed to `sci/init`** —
   `opts/init` silently drops unknown option keys (a quiet wrong answer;
   loud refusal queued for our fork).
2. **Interpreted functions pin the ctx they were evaluated against.** A fn
   pre-evaluated into the shared base resolves the BASE environment forever.
   Program-graph functions are host Vars or are (re)created in the running
   fork — never pre-evaluated once into the shared base ctx.
3. **Hook consultation costs ~80 ns even with an empty plan (vs 9 ns for the
   inert node).** Phase 1 gates consultation to call sites that actually
   have a plan (cluster-scoped program-graph data); only the environment
   read is per-fork.

**Fork carriage — PASS; interrupt arm — CONFIRMED UNARMED**
([report](../research/env-phase0-fork-carriage-2026-08-07.md)). 24 forks × 8
rounds, 576 checked arms, 0 failures: every fork's code resolved ITS fork's
environment on raw and virtual threads through three carriers
(`:interrupt-fn`, a per-fork provider closure, the runtime-ctx hook); the
negative control reproduced the audited defect 192/192 off-thread. Measured
cross-fork carriage: a closure built in fork A resolves fork A's environment
even called from inside fork B's evaluation — the sci report's invariant
(fn objects never cross a turn boundary; round-trip through source) is
load-bearing. **Probe B confirmed the top hypothesis:** the guard's arm is a
plain ThreadLocal, so work handed to another thread runs with NO time limit
and cannot be interrupted (unbounded loop still running at 5× its 300 ms
limit; control interrupted at 310 ms). Blocker filed:
[interrupt-arm-does-not-cross-a-thread-hop](../../../seon/issues/interrupt-arm-does-not-cross-a-thread-hop.md),
riding the Phase 1 constructor wave — the arm travels exactly like the
environment. Corollary: `:seon.eval/fn-entries` silently under-reports
across threads (0 can mean 20k entrances elsewhere) — the same root cause;
the vocabulary table's "12 reads as blocked" caveat gains "or the work ran
on another thread" until the arm fix lands.

**Flow carriage — PASS. Phase 0 CLOSED: three for three**
([report](../research/env-phase0-flow-carriage-2026-08-07.md)). Three real
launchers, three submitter threads, 540 work + 180 `complete!` observations
over 3 repetitions with decoy bindings installed: io/compute/callback each
received exactly their submission's environment (60/60 each per round),
zero cross-submission or cross-launcher reads, and stop→create→start
re-delivered proc `:args` value-identically at 0.034–0.44 ms per cycle.
Two corrections the implementation MUST absorb:

1. **The flow report's `:params` refusal recommendation is FALSIFIED** —
   `start-proc` assoc's `::flow/pid` into args so flow's own assert always
   sees a truthy map. The missing-environment refusal is SEON'S OWN, at
   `var-process` (which already refuses non-Var steps and `:mixed`) and at
   `submit!`/`submit!!`.
2. **Carrier key is `:seon.env/environment`, owned by `seon.env`** — the
   same key on a submission, in proc `:args`, and on a web request map;
   never a flow-namespaced key.
3. Sequencing constraint for Phase 3: the `bound-fn*` deletions land IN THE
   SAME CHANGE as the environment merge — while conveyance remains, a
   forgotten environment is invisible on `:compute` and fatal on `:io`
   (the exact audited signature).

Side finding filed with honest qualification:
[flow-work-launcher-graph-omits-its-root-io-executor](../../../seon/issues/flow-work-launcher-graph-omits-its-root-io-executor.md)
(inert today — the process root's `:io` IS core.async's global executor).
Recurring ugly-output theme across all three lanes: virtual threads report
an empty `.getName`, so name-based thread diagnostics render blank on
exactly the executor where failures live — use `.threadId`/`.isVirtual`.

**Branch verbs for root — designed and probed, six for six**
([branch-verbs-design-2026-08-07.md](../research/branch-verbs-design-2026-08-07.md)).
checkout = the checkout fact selecting the run's opening value (foreign
head or pinned commit — downstream already takes an explicit value); log =
a store-only commit walk via `parent-commit-ids` (Datahike's
`branch-history` is unusable for root: needs an attached connection,
returns a channel, not exported); diff = `since` over the branch's
`history` view (bare `since` is refuted by probe — replaced values
vanish), cross-branch as two diffs from the fork point, symmetric diff
refused; status = head + basis + behind-count, pure query; fork =
`registry/branch!` (the one owner). Out: remotes, index, merge
(`versioning/merge!` records parents and leaves merged tx-data to the
caller — not trivial), `force-branch!`, `fork-database`. Probed on an
isolated store: explicit foreign value beats current resolution (5/5 at
the caller's basis), foreign WRITE refused with both `[store-id branch]`
ids while foreign reads stay open; fork 17.0 ms median, foreign head
value 0.219 ms. **Blocker absorbed into the Phase 3 contract:** the
foreign-write fence is `(when (some? *conn*) …)` (`src/seon/db.clj:163-174`)
— deleting `*conn*` without moving the fence ADMITS every foreign write
silently (an unbound caller's write commits today, probed). The fence
moves to environment-carried custody IN THE SAME CHANGE that deletes the
dynamic var
([issue](../../../seon/issues/foreign-write-fence-reads-only-the-dynamic-var.md)).
Friction: `seon.db` has no branch/commit reads, so the verbs currently
must reach past it to `datahike.api`
([issue](../../../seon/issues/seon-db-has-no-branch-or-commit-reads.md));
`diff` refuses on `:keep-history? false` rather than answering wrongly;
`log` needs a declared `:seon.render/ai` producer (raw commit walks render
as full uuids + 536M transaction ids — unreadable).

**W4 measurement — the per-fork installation question DISSOLVES to a
hybrid, adopted**
([report](../research/env-phase1-w4-fork-install-measurement-2026-08-07.md)).
Numbers: `sci/fork` 225 ns; per-defn install ~24 µs median (25–65 µs
band); eager at N=1000 costs 120 ms and — the binding constraint —
23 MB PER FORK (232 MB across 10 concurrent turns); lazy collapses on
chained corpora (sci resolves callees at definition time) to 66–114 ms.
The hazard also SHRANK under probing: each cluster's own base ctx makes
cross-cluster leakage impossible; only TURN-scoped members can be wrong,
and an ABSENT member refuses loudly instead of lying. Adopted (owner may
veto on review): the base carries only cluster-scoped environment
members — converting the silent class to loud refusals for free — and
the fork re-creates only the turn-scoped CALLER closure derived over
`:seon.fn/calls` (callees are forced by sci's definition-time
resolution; the closure is caller-directed). Today's real installed
program has zero agent-authored interpreted defns, so nothing is urgent.
Two constraints for W1/W2: `sci/fork` copies ONLY `:env` — every other
ctx key (including kernel's `::installed-functions` atom) is shared by
identity between base and forks, so per-fork state needs a per-fork
holder set at turn start (proven isolated 16/16), never a shared-atom
installed-check.

**W3 landed — the fork pin is `seon-env-hook` f934044**
([notes](../research/env-phase1-w3-notes-2026-08-07.md), pin bump
`288fab5c6` after orchestrator review). Three commits: runtime-ctx
observer read (issue resolved — and its `:interrupt-fn` half WITHDRAWN
with evidence: `fns/fun` receives the runtime ctx at fn-creation, sci's
ctx-travels-with-code design working correctly); the
`:call-preparation-hook` `(hook ctx var args) -> args | reduced` on the
direct-Var path only, documented in the init docstring; loud refusal of
unknown option keys in `sci/init`/`merge-opts` — which immediately
caught two silent-drop defects in sci's own upstream tests. JVM suite
393/1470 green on Clojure 1.10.3 AND 1.11.1; CLJS failures unchanged
from the pin; Phase 0 falsifiers 320/320. The graduation also fixed a
bug in the Phase 0 probe itself (its node ordering silently disabled
the observer when the hook was installed) — reauthored, not
cherry-picked. Costs measured under load: 12 ns unhooked / 115 ns
empty-plan / 396 ns prepared — ruling 8's premise confirmed.

**W2 landed — the arm is a value; the fn-entries lie is dead**
([notes](../research/env-phase1-w2-notes-2026-08-07.md), commits
`06b065a99`/`237f74d96`). `new-armed` extracts the arm as a value
(AtomicLong counters, latch, identity); `current-arm`/`adopt-arm` are
the one hand-out/install pair; adoption is strictly nested
(save/serve/restore) so no merge or refusal cases exist on a worker;
`stop!` no longer cancels a travelled arm's deadline — detached work is
cut at ITS limit after the parent disarms. Proven: Probe B inverted
(detached unbounded loop settled by sci's own interrupt at ~300 ms limit,
5+3 consecutive greens, non-vacuous — neutered adoption survives 3 s at
183k ticks); 20k-entry workload on a virtual thread records ≥20000 where
baseline recorded 0; class regression at
`test/seon/sci/kernel_arm_carriage_test.clj`. Handoff to W1 is exactly
two calls (assoc `current-arm` into the submission environment; wrap
work/`complete!` in `adopt-arm`); the arm member is OPTIONAL (nil =
submitter unarmed), unlike the environment itself. The blocker issue
stays open until W1 wires the flow crossings. BONUS root-cause fix for
everyone (`de31c5316`, archived with evidence): schema admission's
exclusion seeded from the live registry, so EVERY edit to an existing
schema resource collided with its own published self — pure accretion
included; measured and fixed at the one owner.

**W1 landed — Phase 1 complete (pending one foreign-red baseline)**
([notes](../research/env-phase1-w1-notes-2026-08-07.md); commits
`a808ad980`, `ee00c6dd3`, `19d61b1b4`, `204e94421`, `61b65efbe`). The
environment value + boot constructor are live: schema-declared members
read once, refuse-up-front naming the first failed layer, subset
construction, environment on the ctx (never init options), submissions
carrying `:seon.env/environment` with refusal at all four crossings, the
W2 arm wired (capture on submit, `adopt-arm` around io/compute/`complete!`
— [interrupt-arm issue](../../../seon/issues/archive/) RESOLVED with
submission-level proof), Phase 0 probes graduated as
`test/seon/env_test.clj`, `bin/seon init` publishes at HEAD, and the
reset-boundary live proof SUCCEEDED (full boot to every layer; ctx
environment `identical?` to the instance's; two-cluster isolation across
two roots). W1 also caught its own perf regression via thread dump
(construction now 0.37 µs) and filed the underlying owner defect
([packaged-forms per-call re-read](../../../seon/issues/packaged-forms-rereads-every-schema-resource-per-call.md)
— dispatched to a lane), plus the day's most consequential new blocker:
[a cohosted second cluster cannot boot](../../../seon/issues/a-cohosted-second-cluster-cannot-boot.md)
(Defect II at the boot boundary; blocks the four-worker parallel target;
dispatched to a lane). ALL THREE suite reds are PROVEN pre-existing by
worktree baseline at a pre-W1 SHA (`4f5b8c5ac`): render.web SSE timeouts,
gen.loop planner census, and boot-test refork — these set the integration
checkpoint's honest expectations. W1 CLOSED 2026-08-07 night; Phase 1 is
complete. The checkpoint (complete bare run) waits only on the two
dispatched repair lanes landing so the tree is coherent.

**Declaration-population family, write side CLOSED; read side escalated**
([research](../research/declaration-population-per-item-2026-08-07.md),
commit `66679cf89`). Resolve-once-and-pass landed at `seon.schema` /
`seon.config` / `seon.print`: reconcile identity scan 21–26 s → 11.4 ms
(286,672 resource reads → 152), config registration 1,003 ms → 24.8 ms,
print option-defaults 67.9 ms → 11.3 ms; a FOURTH instance threading
cannot reach (the registered `malli-form?` predicate resolving per
attribute — 82,992 reads inside config admission) contained at the one
supply seam and
[filed for the environment fix](../../../seon/issues/malli-form-predicate-resolves-the-declaration-population-itself.md).
Class regression counts reads at the one seam: one population per
operation. The READ side
([db-read-decoding-resolves-declarations-per-attribute](../../../seon/issues/db-read-decoding-resolves-declarations-per-attribute.md))
is ESCALATED from Phase 3 to immediate: it now wedges reconcile-test and
config-application-test at the 300 s backstop (one `config/effective` =
84,664 reads inside one `pull '[*]`) — lane dispatched on `seon.db`'s
five walkers with the write-side fix as the model. Also from that lane's
ugly-output list: the classpath fallback needs one loud dev-mode warning
naming its caller (the 286k-read "map lookup" logged NOTHING — found
only by thread dump), which is the ethos section's diagnostics rule
applied to this exact seam.

## Rollout — test-first, REPL-iterated, then farmed out

Phase 0 — falsify the three load-bearing mechanics live (opus REPL lanes,
own scratch clusters, no production edits): (a) environment-on-fork — a
closure crossing to a virtual thread still resolves ITS fork's environment;
(b) the runtime-ctx hook seam in the maintained sci fork prepares arguments
for a host function; (c) a flow submission carrying the environment as data
delivers it to `:io` work. Each is a probe file → future class regression.

Phase 1 — the environment value + constructor land with the
test-infrastructure work: `start-fork!`/`with-cluster` consume it; the
audit's probes graduate as the isolation regressions; deliberately
overlapping tests run in parallel and pass with repetition.

Phase 2 — live-drive iteration: opus lanes drive real end-to-end agent
turns on forks (the standing dogfood pattern), hammering overlap at load.
The parallel suite becomes the standing stress harness. Iterate here until
turns run end to end clean; the design is not "first thing tried" — it is
revised in this phase with evidence, in chat with the owner.

Phase 3 — the production sweep, farmed out with well-written specs: delete
the dynamic-var carriers, convert the named readers, land the r2 call-
preparation slices (S1–S4 in [p17-ambient-slices-2026-08-05.md](p17-ambient-slices-2026-08-05.md)
amended per ruling 2 above), fix `submit!`/`submit!!`, move the compiled
caches onto the projection. Each slice is one lane with owned paths and the
probes as acceptance evidence.

Phase 4 — the terminology sweep (owner-directed, 2026-08-07 evening): after
the production sweep lands, the whole project — source, docstrings, schemas,
docs, skills, plan documents — is swept and STANDARDIZED on the proper
terms of the libraries we interface with (Clojure, sci, Datahike, Malli,
core.async/flow, konserve) or the literal common name, per ruling 5 and the
standing names-grounded-in-source rule. Mechanics: orchestrator-owned
atomic rename waves (shared-tree renames are never split across lanes),
each wave grounded in the dependency's own source for the chosen term, the
vocabulary table updated in the same change, and the
[rename pass precedent](rename-pass-2026-08-05.md) as the working model.
Candidate terms already known dead: "ambient", "tower", "batteries";
the sweep enumerates the rest by reading, not by grep-guessing.

Owner extension (2026-08-07 night): the boot/environment/running triad
is the settled vocabulary (AGENTS.md table row added); "the runtime" and
"the platform" retire as umbrella nouns (~60 doc uses, heaviest in
`architecture.md`). **The rename list below is owner-APPROVED verbatim
("I approve all of those renames. Make it happen when the time is
right.")** — execution waits for Phase 4's window (post-production-sweep,
frozen-tree rename waves); the per-file grounding read remains as an
execution step and may only downgrade a rename back to the owner with
evidence, never silently substitute a different name:

- `seon.operator.runtime` → `seon.operator.resources` (it holds the live
  resource registries — instances, store holder, flocks, executors);
- `seon.fresh-operator` (+ `script/seon/fresh_operator.clj`) → merge into
  `seon.operator` ("fresh" distinguished against the deleted old system);
- `seon.bootstrap` / `seon.bootstrap-drive` → `seon.initial-forms` /
  `-drive` (the mechanism's own ruled name; ends the boot/bootstrap
  confusion);
- `seon.sci.kernel` → `seon.sci.guard` or a split (no kernel exists in
  sci's vocabulary; the file owns guard/arm, invoke, install);
- `seon.schema-split` → evaluate merge-or-delete (schema-monolith
  deletion artifact);
- `seon.oversight`, `seon.problems`, `seon.maintenance` → read and either
  justify or rename to the literal function.

Deliberately kept: operator, cluster, store (Datahike's own word for its
store), ctx/fork (sci), graph/proc (flow), `my.*`. Historical PRD
directory and document titles are never renamed.

Graduation gate: the test-infrastructure spec's gate (every ordinary test
receives a distinct branch, connection, sci fork, and projection state from
one source base; two concurrent forks cannot exchange a compiled schema,
predicate function, declaration population, or IO-submission environment)
PLUS a live two-cluster drive where an agent turn in each cluster completes
end to end while the parallel suite runs, and the deletion list is empty in
`src/`.

## Dependency ledger

Carried by the four research reports and the audit (revisions pinned
there): sci `2db3358c`, Malli `80138076`, Datahike `10540578`, core.async
`1.10.874-alpha3` / flow at the vendored pin, Clojure 1.12.5. Existing
mechanisms: `cluster-ctx` custody + `::projection-state`
(`src/seon/sci/eval.clj:1430-1460`), P12 argument-address facts (graduated
2026-08-05), the r2 injection design, the test-infrastructure spec, the
custody-isolation invariants (re-proven, not deleted).
