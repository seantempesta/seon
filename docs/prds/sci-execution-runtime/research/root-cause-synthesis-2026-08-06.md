---
type: research
status: complete
tags: [research, audit, runtime]
---

# Root-cause synthesis — 2026-08-06

## Executive agenda

### Verdict

Today's defects do not require twenty-seven independent repairs. They reduce
to **seven roots**. The first six make defects possible; the seventh lets them
escape a green gate.

| Root | Deepest missing invariant | Decisive witness |
|---|---|---|
| R1. Total post-custody settlement | Once a run is held, every exit durably settles, refuses, or releases it | A frozen plan survived a desk-rehydration throw before receipt zero |
| R2. Candidate isolation and atomic adoption | A turn's mutations are one isolated candidate; the terminal database transaction chooses the winner; only that winner reaches the live base | Two concurrent definitions/schemas can both report success while one durable row wins |
| R3. Causal episode identity | The trigger, prompt contents, waits, deliveries, and terminality are projections of one explicit causal closure | An unclaimed human message entered a paid turn held for a maintenance trigger |
| R4. Total, bounded, identity-preserving projection | Every producer-to-consumer crossing preserves meaning, errors, stable identity, and the consumer's aggregate fit | Ref maps were treated as lookup refs, errors vanished into `nil`, DOM ids collided, and one error face reached 4 MB |
| R5. Derived activation closure | A published source commit declares every program row, schema, attribute, config prerequisite, and runtime symbol needed to activate it | Fresh maintenance wrote an uninstalled attribute; isolated config omitted model schemas; stale source was forkable until current population failed |
| R6. Resource envelope | Every created resource has admission, owner/lifetime, footprint, and terminal release/reclamation | Same-JVM clusters share unbounded heap; retained eval samples cost ~42 MB; stale claims accumulate |
| R7. Recurring causal system proof | A non-vacuous scratch-cluster proof repeatedly crosses the real message, run, provider, database, and render boundaries | 1,007 tests were green and the first real turn still froze before its first receipt |

This set is minimal under today's evidence. Removing any root leaves a defect
class unexplained: R1 the pre-receipt wedge; R2 the concurrent-winner lie; R3
the wrong-message prompt; R4 the duplicate DOM identity and representation
loss; R5 the missing fresh-cluster attribute; R6 the co-host heap guarantee;
R7 the green-gate escape. Combining R1–R3 would hide three different
contracts—terminality, mutation adoption, and causality. Combining R4–R6 would
confuse data correctness, activation prerequisites, and physical lifetime.

### The owner rulings needed first

The implementation queue should not guess these answers:

1. **Pre-evaluation failure evidence:** should a failure after custody but
   before SCI evaluation create receipt ordinal 0, or close/refuse the run with
   zero receipts? **Recommendation:** run-level refusal with zero receipts;
   preserve “receipt” as evidence that an evaluation attempt began.
2. **Concurrent durable mutation:** must two agents be able to mutate the same
   namespace/schema concurrently, or may durable mutation serialize by
   namespace while ordinary turns remain concurrent? **Recommendation:** keep
   concurrency, but make the terminal database transaction select one
   candidate and make the loser an explicit refusal.
3. **Episode visibility:** may a run see facts committed after its opening
   database value? **Recommendation:** no, except facts connected through an
   explicit continuation/wait edge in that episode.
4. **Context fit authority:** may the final consumer-fit owner elide output
   from a named producer and replace it with an elision value?
   **Recommendation:** yes; semantic producers do not own consumer budgets.
5. **Older source under newer process code:** may activation refuse an older
   cluster whose published prerequisite closure cannot be satisfied, or must
   the process retain matching runtime artifacts? **Recommendation:** refuse
   activation explicitly; do not build a compatibility runtime.
6. **Heap isolation:** is same-JVM multi-cluster co-hosting non-negotiable?
   **Recommendation:** if a hard “one cluster cannot exhaust another” guarantee
   is required, allow only one active agent-executing cluster per JVM/operator
   root. No in-process accounting scheme can provide a hard JVM-heap fence.
7. **Real-provider cadence:** must the recurring proof call the shipped paid
   provider on every checkpoint, or may every commit use a deterministic
   provider with a real-provider drive at release/scheduled checkpoints?
   **Recommendation:** deterministic on every checkpoint, shipped provider on
   each coherent release checkpoint and scheduled health run.

### Proposed order

1. Seal the seven rulings above and put them in the active program roadmap.
2. Close R5's activation preflight first. It makes every later scratch proof
   meaningful and prevents fresh clusters from failing for undeclared inputs.
3. Land R2's candidate/adoption contract, then R1's total settlement around
   it. Settlement cannot be designed honestly until the winning mutation is
   defined.
4. Build R3's causal closure into the existing messaging wave; use the same
   query for prompt inclusion, wait wake-up, delivery, and drive terminality.
5. Consolidate R4 across the error-model, render/context, Datastar identity,
   strict-display, database error-value, and MCP faces. Fixing one renderer at
   a time will continue to move malformed data to the next consumer.
6. Finish R6's already-started admission/lifetime waves, then resolve the heap
   topology ruling and eval-retention economics.
7. Build R7 alongside steps 2–6: each root contributes one falsifier to one
   production-shaped scratch-cluster scenario. Make that scenario the final
   graduation gate, not a one-time drive.

### What is already fixed, and what is not

At the first synthesis cutoff (`7618a5e63`), three observed blockers had been
archived. During validation, the independent mutable-state audit landed
`e6d679387` with two additional blocker notes. The report therefore covers 29
distinct blocker defects seen today: the original 27 plus those two later
findings. Three original blockers are archived, so the current index correctly
contains 26 open blocker rows.

- `11ddaba1a` closes the two observed desk precursors: host/system Vars are no
  longer attributed to the turn, and unrestorable desk rows no longer abort
  rehydration. It does **not** establish R1 or R2.
- `31044d4ac` plus `6f2e9e9bb` close the observed problems projection. The
  wildcard-pull representation class in R4 remains.
- `9fa48fa20` closes the transcript set input and supplies the live SCI context
  to the data page; `7618a5e63` preserves the inspected data-page identity.
  These close observed instances, not the R4 contract.
- `cbaffa1f0` closes malformed SSE admission before agent code changes. The
  general projection/failure-face root remains.
- The bounded Flow submission and exclusive collection permits have landed
  strong local proofs. They close two R6 instances; they do not supply the
  resource envelope for heap, eval retention, directories, or external claims.

The agenda is therefore not “reopen fixed bugs.” It is “retain those fixes as
regressions while making the class unrepresentable.”

## Scope, authorities, and method

I read the root `AGENTS.md` end to end. I also read these named authorities end
to end before synthesizing:

- [default-cluster live drive](docs/prds/sci-execution-runtime/research/live-drive-2026-08-06.md);
- [live-drive observer](docs/prds/sci-execution-runtime/research/live-drive-observer-2026-08-06.md);
- [smell-sweep seeds](docs/prds/sci-execution-runtime/research/smell-sweep-seeds-2026-08-06.md);
- the complete blocker section and the complete friction/cluster context in
  [open issues index](docs/seon/issues/index.md);
- the current 2026-08-06 working-edge blocks in
  [unsettled program edge](docs/prds/sci-execution-runtime/plan/unsettled.md);
  and
- [messaging implementation wave](docs/prds/sci-execution-runtime/plan/messaging-wave-2026-08-06.md).

I then read every blocker note present at the audit snapshot, including the
three notes later archived today, and inspected the current owners in
`seon.cluster.loop`, `seon.cluster.agent`, `seon.cluster.prompt`,
`seon.cluster.work`, `seon.sci.eval`, `seon.render`, `seon.render.web`,
`seon.render.value`, `seon.db`, schema population, maintenance, Flow
submission, and operator claims. I used the data-oriented Clojure, Datahike,
Flow, data-modeling, and REPL runbooks for the causal pass.

Production remained read-only. I did not touch `default`. An isolated operator
root under `tmp/root-cause-synthesis-0806` was used only to probe readiness.
Both `status` and `start` emitted no readiness or failure event within bounded
observation; I stopped the exact probe processes. That establishes an
unobservable slow boundary, not its cause. It does **not** justify attributing
the 70–82 second publication incident to Malli, caching, or a foreign lane.
A separate load-only analyzer probe did not reproduce the suspected
`unresolved-excluded-var` severity elevation. Both unknowns remain residue
below.

The synthesis cutoff is commit `e6d679387`. Issue lifecycle moved during the
audit: the source evidence initially described 27 blocker defects; problems
projection and the two desk issues were subsequently archived; the mutable-
state audit then added two blocker notes. The current index therefore contains
26 open blocker rows. The coverage tables retain all 29 distinct defects so
diagnosis does not erase fixed evidence or omit newly discovered classes.

## Causal map

```text
declared source + schemas ──R5──> activatable cluster
                                      │
message/event ──R3──> causal episode ─┼──> held run
                                      │       │
                                      │      R1
                                      │       │
                                      └──> isolated candidate ──R2──> durable winner
                                                               │
                                                               v
facts/results ──R4──> bounded faithful context, errors, pages, and APIs

Every stage creates/retains physical things ──R6──> admitted, owned, measured,
                                                   terminal resources

The complete chain repeats in scratch production form ──R7──> graduation proof
```

R5 is first because a scratch cluster whose prerequisites are incomplete
cannot falsify the later runtime. R2 precedes R1 because terminal settlement
must know which candidate won. R3 can then bind prompt and completion to that
settled episode. R4 consumes their durable facts. R6 is cross-cutting but its
heap ruling can proceed independently. R7 grows with every stage and closes
last.

## R1 — A held run has no total post-custody settlement owner

### Deep chain

`next-agent-work` prefers the already held run. The agent proc calls
`seon.cluster.loop/turn` and republishes only a process-local completion permit
in `finally`; it does not durably settle an escaped failure
([agent turn owner](src/seon/cluster/agent.clj), `turn-step`, lines 230–279).
On the resume path, `fork-for-turn` and desk rehydration execute before receipt
creation ([run loop](src/seon/cluster/loop.clj), lines 1516–1564). `turn`
dispatches with no enclosing total value conversion (lines 1782–1812). Thus:

```text
durable custody + frozen plan
→ fallible process-local preparation
→ Throwable escapes before receipt zero
→ Flow records a core fault and republishes a completion permit
→ no run close/refusal/release transaction
→ held run wins every future work derivation
→ agent queue freezes
```

The atom row was the observed trigger, not the root. Any future failure in fork
construction, rehydration, prompt preparation, receipt request construction,
or terminal transaction data can recreate the same wedge.

### Defects explained

- [Frozen plan before first receipt](docs/seon/issues/run-freezes-before-first-receipt-after-plan-freeze.md).
- Resolved trigger: [unrestorable atom desk row](docs/seon/issues/archive/unrestorable-atom-desk-row-wedges-next-turn.md).
- [Cold `acquire!` has no per-row containment](docs/seon/issues/acquire-has-no-per-row-containment.md): one bad activation row can abort the whole acquire boundary rather than produce row evidence and a terminal activation result.
- [Evaluation errors cannot settle triage receipts](docs/seon/issues/eval-errors-cannot-settle-triage-receipts.md): the terminal transaction does not accept every error disposition the evaluator can produce.
- The live-drive stop after plan freeze and the observer's “custody held but no
  receipt” invariant failure.

### Current work versus the root

`11ddaba1a` correctly makes desk rehydration row-total and filters desk
attribution by SCI generation. That prevents the exact atom-triggered escape.
It does not wrap the full post-custody path, prove every phase returns a value,
or guarantee one terminal database transition. The receipt settlement and
acquire containment issues remain independent witnesses.

### Solution directions

| Direction | Guarantee | Cost and risk | What we give up |
|---|---|---|---|
| **1. Recommended — one total held-run reducer** | From the instant custody commits, exactly one durable outcome follows: receipt settlement, run-level refusal, close, or release. Every phase returns a disposition value consumed by one terminal owner. | Refactors loop phase contracts and the core-fault seam; risk is accidentally classifying a true core fault as an agent error. Requires generative phase-failure injection. | Phase-local throwing and phase-local terminal transactions. |
| 2. Create receipt zero before preparation | Every post-plan failure has receipt evidence and the existing receipt settlement path can close it. | Smaller loop surgery, but changes receipt semantics and may create “evaluation” receipts where no form began. | The current meaning that a receipt witnesses an evaluation attempt. |
| 3. Prepare before custody, then re-check/claim | Preparation failure leaves no held run; receipts retain current meaning. | Preparation uses a possibly stale database value and must be repeated or fenced at claim. It can be expensive and introduces a claim/preparation race. | One-pass claim-then-reduce simplicity. |

### Owner question

For a failure after a plan is frozen but before SCI begins—desk rehydration is
the concrete case—should the database contain **(A)** a run-level refusal and
zero receipts (**recommended**), or **(B)** a failed ordinal-0 receipt? This
decides the terminal schema and must precede the loop repair.

## R2 — Turn mutation is not one isolated candidate followed by atomic adoption

### Deep chain

The system now has generation-aware SCI forks, but the full mutation contract
still spans several owners: evaluation mutates a candidate context; session
delta discovery interprets context state; definition/schema rows transact;
receipt success is constructed; and the live base installs rows. If those
steps do not share one adoption result, attribution and truth can diverge:

```text
shared or incompletely attributed context mutation
→ each run derives a plausible delta/success
→ concurrent terminal transactions race on durable identity
→ one database row wins or a later conflict appears
→ both receipts may still claim success
→ live base, receipt, and durable program graph disagree
```

Namespace removal exposes the same missing distinction: a transient session
mutation and a durable contracted definition are both observed through
context state, so rebuild logic cannot know which semantic owner to preserve.

### Defects explained

- [Shared session delta crosses run attribution](docs/seon/issues/shared-context-session-delta-crosses-run-attribution.md).
- [Concurrent definition receipt divergence](docs/seon/issues/concurrent-definition-receipts-can-diverge-from-durable-program-row.md).
- [Concurrent divergent schemas both report success](docs/seon/issues/concurrent-divergent-schema-declarations-falsely-both-succeed.md).
- [Namespace removal rebuilds the wrong definitions](docs/seon/issues/namespace-removal-does-not-rebuild-contracted-only.md).
- Resolved witness: [system Vars captured as the agent desk](docs/seon/issues/archive/agent-desk-captures-newly-loaded-system-vars.md).
- The reachability half of smell seed 3: requiring one host namespace can copy
  host `ns-interns` that are not explicit turn-authored mutations.

### Current work versus the root

`11ddaba1a` uses SCI generation metadata to close false desk authorship and
contains malformed desk rows. It is an essential R2 primitive, not atomic
adoption: it does not make a receipt depend on the database winner, isolate
all program/schema mutation, or define namespace deletion. The current
per-run fork and schema collision waves are therefore still required.

### Solution directions

| Direction | Guarantee | Cost and risk | What we give up |
|---|---|---|---|
| **1. Recommended — candidate → terminal CAS/admission → install** | Each run evaluates in one fresh candidate fork. Its proposed desk/program/schema/namespace delta is ordinary data. One terminal database transaction decides success. Only `db-after` rows install into the live base; a loser receives an explicit refusal. | Crosses SCI eval, loop settlement, schema admission, and live installation. Risk is hidden mutation not represented in the candidate delta; requires source/SCI probes for every mutation form. | Success before durable adoption and best-effort post-commit installation. |
| 2. Serialize durable mutation by namespace | At most one durable mutation candidate for a namespace can be active, while non-mutating turns continue concurrently. | Lower implementation risk, but needs a queryable namespace-mutation classification before eval and honest waiting semantics. | Concurrent edits to one namespace. |
| 3. Restrict durable change to editor/revision/proof adoption | Ordinary turns remain session-only; durable changes enter the already ruled revision/proof workflow, whose adoption transaction is the sole winner. | Strongest model and easiest review, but significantly changes immediate interactive `defn`/schema behavior. | Immediate durable definition from an ordinary agent turn. |

### Owner question

Is concurrent durable mutation of the same namespace/schema a required
capability? Choose **(A)** yes, and require the recommended transactional
winner/refused-loser contract, or **(B)** no, and serialize durable mutations
per namespace while keeping ordinary turns concurrent. Do not choose silent
last-writer-wins; it is the current defect.

## R3 — Trigger identity is not the boundary of a causal episode

### Deep chain

`seon.cluster.prompt/prompt` proves that a held run has a trigger or background
result, then asks the render pipeline for the agent's current walk
([prompt owner](src/seon/cluster/prompt.clj), lines 66–94). The walk is not
filtered to the trigger's causal closure or opening database value
([web render context pass](src/seon/render/web.clj), lines 778–792). Separately,
the existing drive derives direct runs/initiating-agent terminality rather
than the transitive closure of messages, deliveries, runs, and waits. Thus:

```text
event A opens/holds run A
→ later message B commits for the same agent
→ prompt renders current agent neighborhood, including B
→ model answers B inside A's custody
→ result/delivery facts remain attached to A
→ B is semantically consumed but causally unclaimed
```

O4 is the other direction of the same hole: the initiating run can look done
while a causally delegated run/message remains live, because no shared episode
closure defines terminality.

### Defects explained

- [Unclaimed message enters an unrelated prompt](docs/seon/issues/unclaimed-message-enters-an-unrelated-run-prompt.md).
- [Bootstrap O4 stops before causal delegation](docs/seon/issues/bootstrap-o4-stops-before-causal-delegation-settles.md).
- Smell seed 7, wake arbitration.
- Observer finding: 16 of 17 system-generated messages lacked arrival
  ordinals, so their relative position was not an explicit fact
  ([system-generated message ordinals](docs/seon/issues/system-generated-messages-omit-arrival-ordinals.md)).
- The “wrong trigger, right semantic answer” half of the live-drive freeze.

### Current work versus the root

The [messaging implementation wave](docs/prds/sci-execution-runtime/plan/messaging-wave-2026-08-06.md)
settles explicit addressing, waits, honest interleaving, result handles, and
completion-as-safety-net. Those are required edges. The wave does **not yet
state one query** that both prompt inclusion and terminality must use. Without
that addition, M3 can fix wake semantics while prompt rendering and the drive
continue to compute different episode boundaries.

### Solution directions

| Direction | Guarantee | Cost and risk | What we give up |
|---|---|---|---|
| **1. Recommended — one queryable causal closure** | Starting from one trigger, derive its run, emitted messages, addressed deliveries, waits, triggered runs, replies, and terminal facts. Prompt inclusion, wake-up, drive completion, and forensics query this same closure at the run's opening database value plus explicit continuation edges. | Adds/validates causal connections and transitive queries. Risk is an accidental cycle or an edge omitted by a producer; properties must generate delegation chains. | Incidental visibility of unrelated same-agent facts. |
| 2. One run per trigger at a frozen opening database value | A run cannot absorb later messages; temporal isolation is simple. | Same-episode delegated results require an explicit resume/new-run rule, and long waits cannot see a result without advancing the episode basis. | Live ambient context during a run. |
| 3. Serialize all inbound episodes per agent | A newer inbound message cannot overlap an older one. | Operationally simple but still needs causal closure for delegation; long background waits block independent work. | Concurrent independent conversations with one agent. |

### Owner questions

1. May a run see facts committed after its opening database value? Choose
   **(A)** only through explicit wait/continuation edges (**recommended**) or
   **(B)** the agent's entire current neighborhood.
2. When two independent inbound messages queue for one agent, should they
   produce **(A)** one run per trigger (**recommended**) or **(B)** one batched
   run with an explicit ordered trigger vector? This is distinct from whether
   the agent executes them serially.

## R4 — Projection and composition have no total consumer-fit boundary

### Deep chain

Several apparently unrelated UI/API failures share one sequence:

```text
producer emits valid data in its local representation
→ boundary assumes a different representation, required dependency, identity,
  or size discipline
→ conversion is partial or delayed
→ failure becomes nil, duplicate identity, raw stack, repeated block, or huge
  nested error data
→ downstream consumers amplify the defect
```

Concrete examples:

- Datahike wildcard pull represents refs as `{:db/id ...}` maps; the problems
  consumer promised transaction-shaped lookup refs.
- `seon.db/q` performs overloaded database/query dispatch before propagating an
  error-valued database argument, so a prior error can become apparent absence.
- `/data` constructed a render unit without the live SCI context its producer
  required.
- `seon.render.value/node-id` falls back to agent + anonymous/block root + path
  ([value projection](src/seon/render/value.clj), lines 27–41). Multiple values
  can receive the same address before their final walk identity exists.
- Named producers and the generic floor do not share one aggregate token/size
  owner, so repeated blocks, raw config faces, and a 4,010,918-character error
  can all be locally valid and globally unusable.

“Total” here means more than “does not throw.” The boundary must preserve the
source meaning, propagate an error value, assign a stable semantic identity,
enforce the selected consumer profile over the aggregate, and produce one
bounded failure face if it cannot.

### Defects explained

- [Rendered value id collisions](docs/seon/issues/rendered-value-ids-collide-within-one-page.md).
- Fixed instances: [transcript set passed to `pull-many`](docs/seon/issues/transcript-about-lookup-passes-a-set-to-pull-many.md) and
  [data page omitted SCI context](docs/seon/issues/data-page-omits-the-live-sci-context.md).
- Resolved instance: [problems projection broke health/root render](docs/seon/issues/archive/problems-projection-breaks-health-and-root-render.md).
- [`seon.db/q` returns `nil` for an error-valued database](docs/seon/issues/seon-db-q-returns-nil-on-error-value-db-argument.md).
- [Development MCP envelope/status regression](docs/seon/issues/dev-mcp-envelopes-misdirect-errors-and-sprawl-status.md).
- [Malformed SSE changed code](docs/seon/issues/malformed-sse-data-can-change-agent-code.md), now fixed at its admission boundary.
- Live-drive/observer context defects: 34k/44k-token prompts, 519/520 KB
  pages, repeated renderer failures, 185 duplicate id values, raw config maps,
  and debug not originally serving the exact captured prompt.
- [Private render-token dials](docs/seon/issues/render-token-budgets-are-private-dials-no-producer-supplies.md),
  [raw cluster/config/bootstrap faces](docs/seon/issues/cluster-config-and-bootstrap-plan-render-as-raw-maps.md), and
  [contract violation serialized a print tree](docs/seon/issues/contract-violation-serializes-print-tree-inside-error-data.md).
- Observer log findings: raw `/data` stacks and duplicate expected-refusal
  logging ([one bounded refusal log face](docs/seon/issues/expected-refusal-logs-raw-datom-error-twice.md)).
- Stale comment-oriented bootstrap/render semantics and the strict REPL display
  issues: semantic projection was not faithful even when it returned text.
- Smell seeds 4–6: unbounded error payload, context dedup failure, and
  wildcard-pull ref degradation.

### Current work versus the root

The problems, transcript, `/data`, exact-debug-capture, and malformed-SSE
commits are correct instance repairs. The remaining duplicate-id issue proves
that no final identity owner exists. The token-budget and error-data issues
prove that producer-local validation still does not imply consumer-fit output.
The current error-model W1–W5 and context/render lanes can close R4 only if
they share one terminal composition contract rather than adding independent
guards.

### Solution directions

| Direction | Guarantee | Cost and risk | What we give up |
|---|---|---|---|
| **1. Recommended — one final projection/composition owner** | Every source representation has one declared total converter; error inputs short-circuit before overload dispatch; every unit receives final stable identity before producer invocation; `seon.print/fit` applies the selected render profile to named and floor output; aggregate composition deduplicates by semantic identity and emits elision values. One bounded failure face serves agent, page, log, and MCP projections. | Broad integration across error-model, render walk, value floor, Datastar packaging, db error propagation, and MCP. Risks double-fitting and loss of forensic detail; preserve full evidence as facts/blobs and fit only the consumer face. | Producer-private budgets, anonymous root identity, raw arbitrary error data at consumer boundaries. |
| 2. Explicit converter and property per producer | Each important producer proves its immediate output shape and stable id. | Incremental and lower-risk, but aggregate duplication/size and cross-face error consistency remain dispersed. | A single system-wide fit guarantee. |
| 3. Narrow default context to a declared small block set | Prompt/page size becomes predictably small; other facts require explicit drill. | Fastest operational relief, but a hand list would violate the queryable-system law unless block relevance facts are added. | Automatic broad neighborhood discovery. |

### Owner questions

1. May the final fit owner replace part of a named producer's output with an
   ordinary elision value when the consumer profile is exhausted?
   **Recommendation: yes.**
2. Should the default agent prompt include **(A)** only causally relevant
   blocks plus explicitly declared global instructions (**recommended**) or
   **(B)** the broad neighborhood subject to a token fit? This decides whether
   dedup/bounds are a safety net or the primary selector.
3. At public projection boundaries, should wildcard `[*]` pulls be
   **(A)** forbidden when the function promises a registered entity/value
   schema (**recommended**) or **(B)** permitted through a generic pulled-ref
   conversion layer?
4. For a rendered root with no database identity, must the caller supply an
   explicit root/block identity (**recommended**), or may identity be unique
   only to one render invocation?

## R5 — Runtime activation is enumerated instead of derived as closure

### Deep chain

Fresh activation currently composes several independently selected sets:
published source facts, schema-resource population, Datahike attributes,
initialization rows, config defaults, and host namespace loading. A producer
can therefore name a prerequisite absent from the selected set:

```text
current code/schema/config producer declares or emits X
→ publication/boot derives only a hand-selected subset of prerequisites
→ cluster fork or isolated runner is admitted
→ first real consumer names X
→ missing lookup ref, schema, attribute, default, or exact program Var
→ failure appears late and far from publication
```

The inverse leak also occurs: requiring a namespace for one program function
can expose its host `ns-interns`, including operator/process plumbing that was
never an explicit program fact. Activation is therefore both incomplete and
over-broad.

### Defects explained

- [Isolated runner omits AI model schemas](docs/seon/issues/isolated-runner-does-not-load-ai-model-schemas.md).
- [Fresh maintenance attributes are not installed](docs/seon/issues/fresh-maintenance-result-attributes-are-not-installed.md).
- [New cluster boot accepts stale published source](docs/seon/issues/new-cluster-boot-fails-on-a-stale-published-source.md).
- [Eight web config dials lacked shipped defaults](docs/seon/issues/web-config-dials-ship-without-shipped-defaults.md): defaults and registry membership were compared from the same incomplete authority.
- [Process-global schema state crosses cluster database values](docs/seon/issues/process-global-schema-state-crosses-cluster-bases.md): database-derived candidate forms and one active projection live in a
  process-global atom, so activating one sovereign cluster can change native
  schema/error semantics for another.
- Smell seed 3, agent-reachable operator plumbing.
- Smell seed 2's whole-manifest blast-radius design question: publication
  admission does not distinguish a load-blocking missing dependency from a
  non-blocking analyzer finding by declared artifact dependency.
- The activation half of cold `acquire!` row containment.

### Current work versus the root

The maintenance schema-install, database model registry/config schema-load,
web default, stale-source population, and per-cluster acquire lanes each name
one missing prerequisite. Fixing their enumerations separately will preserve
the root. The correct common exit is a publication-time query whose nonempty
subjects come from the program/schema facts and whose result is stored with
the published source commit.

The process-global schema-state note adds a scope requirement to that exit:
database-derived closure is acquired **per cluster at its database value**.
Only qualified-symbol-to-host-function compilation may remain a process-local
cache. A complete activation set stored in one global mutable projection would
still violate sovereign cluster semantics.

The analyzer instance was removed by `b07ccfef0`, but the suspected severity
elevation did not reproduce in a load-only probe. The exact cause is residue;
only the admission design question belongs here.

### Solution directions

| Direction | Guarantee | Cost and risk | What we give up |
|---|---|---|---|
| **1. Recommended — derive and publish one activation closure** | Every persisted map must match a declared database-backed schema; every schema contributes required attributes; every initialization lookup ref resolves in the source commit; every config dial has a declared default or explicit required status; every executable symbol is an exact program fact. Publication preflights the nonempty closure atomically; each cluster acquires its projection at its database value; fork refuses if the closure cannot be satisfied. | Requires new queries/facts at the existing publication owner and may expose many latent omissions. Risk is treating an unresolved static edge as proof of absence; unresolved edges must fail explicitly, not disappear. | Ad hoc load-order success, a process-global active projection, and implicit access to host namespace contents. |
| 2. Eagerly load every current schema/runtime namespace | Isolated current-code runners see all current declarations. | Small immediate change, but breaks sovereign older-source semantics and widens agent reachability. | Exact source-commit activation and minimal runtime surface. |
| 3. Retain a matching runtime artifact per published source commit | Old clusters always execute against the code/schema set that produced them. | Strong compatibility, but adds artifact selection, retention, and operator complexity—the sort of parallel runtime the deletion program rejects. | One current JVM codebase and simple hot-reload semantics. |

### Owner questions

1. When an older cluster's published source lacks a prerequisite required by
   current process code, should activation **(A)** refuse with the missing
   fact list (**recommended**) or **(B)** retain/load a matching historical
   runtime artifact?
2. May SCI expose a private/current host Var merely because its namespace
   contains one indexed program function? Choose **(A)** no—exact program
   facts are authoritative (**recommended**) or **(B)** yes—namespace require
   implies all interns.
3. Must a maintenance handler's result match a declared persisted entity
   schema before it can transact (**recommended**), or may the scheduler
   accept arbitrary open maps and discover missing attributes at write time?
4. Should non-load-blocking analyzer findings refuse complete publication?
   Choose **(A)** only syntax, resolution, privacy, and arity failures on a
   required artifact veto (**recommended**) or **(B)** every elevated finding
   vetoes the atomic manifest.
5. Should native schema consumers receive **(A)** an explicit cluster
   projection/database value in their request (**recommended**) or **(B)** a
   dynamically bound per-cluster projection installed around each graph proc?
   A process-global default is not an admissible third option.

## R6 — Resource creation lacks one admission, lifetime, footprint, and terminal contract

### Deep chain

The transport law says when in-flight data may be lossy, but resource owners
still independently decide whether to bound creation, record ownership,
measure retained footprint, and release/reclaim. The repeated shape is:

```text
creator allocates or retains resource
→ admission occurs after allocation, blocks indefinitely, or is absent
→ ownership/lifetime is implicit or stale
→ footprint is unobservable
→ cleanup guesses reachability or never runs
→ one task/session/cluster taxes or endangers unrelated work
```

This is one root across different physical resources, not a request for one
generic “resource manager.” Each existing owner remains concrete; the shared
contract is the four facts/events it must expose.

### Defects explained

- [Deletable directories lacked claims and size facts](docs/seon/issues/deletable-directories-have-no-claim-or-size-facts.md).
- [Ranged collection could delete resurrected branch data](docs/seon/issues/ranged-store-collection-can-delete-live-segments-via-branch-resurrection.md).
- [Flow work submission could block before its time limit](docs/seon/issues/work-submission-can-block-before-its-time-limit.md).
- [Each eval sample costs about 42 MB of database](docs/seon/issues/eval-samples-cost-42mb-of-store-each.md).
- [One co-hosted cluster can exhaust the JVM heap](docs/seon/issues/cohosted-clusters-share-one-unbounded-agent-heap.md).
- [Dropped core-fault observations are not durable](docs/seon/issues/dropped-core-fault-count-is-not-durable.md): overflow increments a per-cluster atom and prints stderr, but no
  nonblocking handoff reaches the fault committer.
- Smell seed 8 and the observer/status finding: invalid external claims persist
  and are repeatedly reported without a resolution transition
  ([status claim flood](docs/seon/issues/status-floods-unreadable-external-claim-warnings.md)).
- Publication's missing progress/readiness event belongs to this root's
  observability contract, although the 70–82 second cost's computational cause
  is not yet established.

### Current work versus the root

The operator directory authority now has claims, footprints, liveness, log
retention, and fixture reaping; scheduled conservative reaping and durable
maintenance results remain. Exclusive reachability permits close the GC
resurrection race locally. Bounded Flow submission closes pre-admission
blocking locally. None answers eval retention or the JVM heap question, and a
warning-only invalid-claim path is still not a lifecycle transition. The new
core-fault drop note shows that even a deliberately bounded resource still
needs a durable overflow disposition: bounded dropping without a surviving
fact closes capacity but loses forensics.

### Solution directions

| Direction | Guarantee | Cost and risk | What we give up |
|---|---|---|---|
| **1. Recommended baseline — require the four-part contract at every existing owner** | Before creation/retention: bounded admission. While live: explicit owner and lifetime. During observation: footprint/capacity. At terminal: one release/reclaim transition, including the durable bounded overflow disposition selected below. Database/blob collection shares the existing exclusive reachability gate. | Cross-owner inventory and proofs; risk is inventing generic machinery instead of strengthening each owner. It bounds known resources but cannot hard-partition JVM retained heap. | Unbounded submission, process-only drop counters, anonymous durable roots, and retention with no declared cutoff. |
| 2. One active agent-executing cluster per JVM/operator root | A cluster's unbounded retained heap cannot kill a sibling cluster because no sibling executes in that JVM. | Operational topology change; separate roots lose cheap shared executors/store and make multi-cluster use heavier. | Same-JVM active co-hosting. |
| 3. Keep co-hosting and add per-cluster budgets everywhere | Bounded queues, blobs, candidate contexts, writes, and retained captures reduce known amplification. | Very broad accounting and backpressure work; Java does not provide a reliable hard per-cluster heap quota, so the headline isolation guarantee remains false. | A hard no-cross-cluster-OOM guarantee. |

The new core-fault note exposes a conservation constraint inside direction 1.
Under an unbounded producer rate, a finite nonblocking handoff cannot also
guarantee durable identity for every item before a crash. A reserved bounded
drop-summary channel can preserve a coalesced count but can itself saturate; an
unbounded queue preserves observations by risking heap; synchronous durable
append preserves observations by risking producer blocking. The design session
must choose which guarantee yields rather than naming a second channel as if it
solved the contradiction.

### Owner questions

1. Is hard heap isolation required? If yes, choose **(A)** at most one active
   agent-executing cluster per JVM/root (**recommended for a hard guarantee**).
   If same-JVM co-hosting is non-negotiable, explicitly choose **(B)** bounded
   known owners plus accepted residual JVM-wide OOM coupling.
2. For eval/context evidence, should retention be **(A)** a declared bounded
   window with durable summaries and blob-backed selected captures
   (**recommended**) or **(B)** full history until explicit maintenance?
3. Should an invalid external claim be **(A)** durably transitioned to a
   resolved/quarantined fact by reconciliation (**recommended**) or **(B)**
   retained forever and only hidden from the status face?
4. At core-fault overflow, which guarantee may yield: **(A)** bounded memory,
   **(B)** a nonblocking faulting proc, or **(C)** durable per-fault identity?
   **Recommendation:** retain bounded/nonblocking behavior and durably commit a
   coalesced drop fact with count + first/last bounded digests, explicitly
   accepting a crash window between atomic counter increment and commit. If
   zero-loss identity is mandatory, the owner must instead authorize blocking
   persistence or unbounded memory.

## R7 — There is no recurring, non-vacuous end-to-end causal proof

### Deep chain

The test inventory is large, but its strongest proofs mostly begin below the
production composition boundary. Some completeness tests derive both subjects
and expectations from the same incomplete list. Manual drives are rich but do
not recur. Therefore:

```text
local owner tests pass
→ no recurring proof publishes source, forks a fresh cluster, commits an
  inbound message, verifies its exact prompt/episode, settles code + database
  + messaging work, and inspects the public faces
→ cross-owner contracts are never exercised together
→ the first live user turn becomes the integration test
```

R7 does not cause malformed projections or wedges. It is the escape mechanism
that explains why all six causal roots coexisted with a green bare gate.

### Defects explained

- Smell seed 9: 51.4% test-source ratio and a green 1,007-test gate followed by
  a frozen first real turn.
- O4's false terminal condition and the context MVP drive's known false-green
  risk.
- Web-config and other completeness tests whose subject can be empty or
  selected from the same hand-maintained authority.
- The live drive's blocked phases: contracted function publication/call,
  runtime schema transact/query, second-agent wait/completion, and honest error
  faces were not reached.
- Observer calibration: effect settlement and interruption recovery were not
  exercised; browser-only behavior was not observed.

### Current work versus the root

The retained [in-server tests PRD](docs/prds/in-server-tests/README.md) owns the
fast in-process execution surface and already carries four owner questions.
It can remove restart/load cost, but speed alone does not create a
production-shaped subject. The live drive and O4 should become one scenario
owned by the existing `bin/test`/operator surfaces, not a second bespoke
runner. The [background-work PRD](docs/prds/background-work/README.md) and
[operational-events PRD](docs/prds/operational-events/README.md) contribute
their boundaries to that scenario after messaging settles.

### Solution directions

| Direction | Guarantee | Cost and risk | What we give up |
|---|---|---|---|
| **1. Recommended — one recurring scratch-cluster causal drive** | From a fresh published source: nonempty assertions prove the agent/schema/config subjects exist; a deterministic provider drives inbound message → exact causal prompt → plan/eval → database fact → second-agent wait/reply → close → error value → web/MCP projection. Terminality uses production's causal closure. Each root adds a fault injection. | Cross-owner fixture and event-driven readiness work. Must avoid exact prose assertions and must preserve evidence without growing another runner. | A very small/fast complete checkpoint; keep focused tests for iteration. |
| 2. Separate boot, messaging, render, and resource live gates | Better failure attribution and parallel execution. | Weaker composition guarantee; can repeat the current gap between individually green boundaries. | One proof that the whole episode composes. |
| 3. Manual real-provider drive at release only | Exercises the shipped provider honestly with little routine cost. | Slow feedback and easy omission; repeats today's escape window. | Per-checkpoint integration confidence. |

### Owner questions

1. Should every coherent checkpoint run **(A)** the deterministic production
   loop and each release checkpoint add the shipped provider (**recommended**),
   or **(B)** call the shipped provider in every recurring drive?
2. Is a connected graphical browser required for final graduation, or is
   deterministic HTTP/DOM identity plus a scheduled graphical visual-QA run
   sufficient? **Recommendation:** require graphical QA at release/final
   graduation, not every source checkpoint.
3. May the recurring scenario reset its isolated operator root on every run?
   **Recommendation:** yes; database data is already ruled disposable and a
   fresh activation is part of the proof.

## Exhaustive evidence coverage

### Drive chain and observer findings

| Evidence defect | Root | Status at cutoff |
|---|---|---|
| Maintenance cannot settle because `:seon.operator.log/path` is not installed | R5 | Open; maintenance schema-install repair |
| Failed maintenance trigger holds root; human message enters its prompt | R3 | Open; messaging context-causality addition required |
| Accepted plan freezes before receipt zero | R1 | Exact desk precursor fixed; totality open |
| Problems projection breaks MCP health and root rendering | R4 | Fixed and archived; wildcard-pull class open |
| Transcript passes a set to `pull-many` and repeats failures | R4 | Fixed instance |
| `/data` omits SCI context and returns 500 | R4 | Fixed instance; identity follow-up landed |
| Debug response blocks/recomputes instead of serving exact capture | R4 | Exact captured-prompt/first-byte fix landed; aggregate fit still open |
| Malformed provider SSE body reaches code path | R4 | Admission class fixed, issue review pending |
| System atoms are attributed to root's desk | R2, secondarily R5 | Fixed and archived; broader roots open |
| Bootstrap teaches stale messaging/comment semantics | R4 | Messaging M5 + strict REPL display waves remain |
| 34k/44k-token prompts; repeated config/schema faces | R4 | Open |
| 519/520 KB pages; repeated errors/raw ids | R4 | Open; some repeated error sources fixed |
| 4,010,918-character nested error data | R4 | Open in error-model/instrumentation wave |
| 185 duplicate DOM id values | R4 | Open Datastar identity repair |
| 16/17 messages omit arrival ordinal | R3 | Open message transaction-data repair |
| Expected transaction refusal logs twice | R4 | Open Datahike logging seam |
| Eight stale external-claim warnings recur | R6 | Face and lifecycle remain open |
| Contract/schema/multi-agent/error phases blocked by first wedge | R7 | Not tested; must become recurring proof |
| Effect settlement and interruption recovery not exercised | R7 | Not covered, not failed |
| Browser typography/layout/console/morph behavior not observed | Residue/R7 | Environment gap; final visual proof decision required |

### Ten smell-sweep seeds

| Seed | Root or residue | Deepest honest verdict |
|---|---|---|
| 1. Publication 25× discrepancy | Residue; R6 owns readiness evidence | The 70–82 second cost is measured, but no phase profile proves its computational cause. “RUNNABLE in registration” is not enough. |
| 2. Analyzer veto blast radius | R5 design; residue for exact trigger | Publication admission needs dependency-derived severity/blast radius. The reported elevation path did not reproduce in a load-only probe. |
| 3. Agent-reachable require graph | R5, with R2 attribution symptom | Host namespace contents exceed exact published program facts. |
| 4. Unbounded error payload | R4 | No bounded semantic error projection at the consumer boundary. |
| 5. Context dedup failure | R4 | No stable identity plus aggregate consumer-fit composition. |
| 6. Wildcard-pull ref degradation | R4 | Producer representation and promised boundary schema differ. |
| 7. Wake arbitration | R3 | Trigger/prompt/terminality do not share one episode closure. |
| 8. Stale claim accumulation | R6 | Claims have observation but no resolution lifecycle. |
| 9. Coverage skew | R7 | No recurring production-shaped causal subject. |
| 10. Lanes exited with work uncommitted | Non-product residue | Root instructions already require path-limited coherent commits despite foreign verification breakage; this is execution/supervision compliance, not a Seon runtime mechanism. |

### All 29 blocker defects encountered today

| Blocker defect | Primary root | Current fix versus class |
|---|---|---|
| Unrestorable atom desk row wedges next turn | R1/R2 | `11ddaba1a` closes the trigger; R1 remains |
| Rendered value ids collide | R4 | Open |
| Frozen plan before receipt zero | R1 | Open at class level |
| Unclaimed message enters unrelated prompt | R3 | Open |
| Problems projection corrupts refs | R4 | Fixed/archived; wildcard class remains |
| Transcript set passed to `pull-many` | R4 | Fixed instance |
| Data page lacks SCI context | R4 | Fixed instance |
| System Vars captured as desk | R2/R5 | Fixed/archived; exact program reachability remains |
| Isolated runner lacks model schemas | R5 | Open |
| Deletable directories lack claims/size | R6 | Mostly implemented; scheduled terminal work remains |
| Ranged GC deletes resurrected data | R6 | Exclusive permit implementation landed; review/issue lifecycle pending |
| O4 stops before causal delegation | R3/R7 | Open |
| Malformed SSE changes code | R4 | Fix landed; review/issue lifecycle pending |
| Flow submission blocks before time limit | R6 | Bounded admission implementation/proof landed; review pending |
| Eval samples cost ~42 MB each | R6 | Open |
| `acquire!` lacks per-row containment | R1/R5 | Open |
| Stale published source fails population | R5 | Open |
| Co-hosted clusters share unbounded heap | R6 | Owner design gate |
| `seon.db/q` turns error-valued db into `nil` | R4 | Open; sibling reads need same boundary audit |
| MCP envelopes misdirect/sprawl | R4 | Main fixes landed; issue remains for complete envelope/status scope |
| Web dials ship without defaults | R5/R7 | Defaults now present; registry-derived completeness proof absent |
| Shared session delta crosses runs | R2 | Open |
| Concurrent definition receipts diverge | R2 | Open |
| Eval errors cannot settle triage receipt | R1 | Open |
| Concurrent schema declarations both succeed | R2 | Open |
| Namespace removal rebuilds wrong definitions | R2 | Open |
| Maintenance result attributes absent | R5 | Open |
| Process-global schema state crosses cluster bases | R5 | Newly filed by mutable-state audit; open |
| Dropped core-fault observation is process-only | R6 | Newly filed by mutable-state audit; open and requires the overflow conservation ruling |

## Solution sequence and ownership

| Order | Root exit | Existing PRD/wave that absorbs it | Integrated proof before advancing |
|---|---|---|---|
| 0 | Record owner rulings | [active program roadmap](docs/prds/sci-execution-runtime/plan/README.md) and [unsettled edge](docs/prds/sci-execution-runtime/plan/unsettled.md) | Seven binary rulings above are explicit; no lane guesses semantics |
| 1 | R5 activation closure | Database model registry/config schema-load repair, maintenance schema-install repair, visual-QA stale-source repair, per-cluster live-graph acquire wave, per-cluster schema acquisition wave, config derivation/publication registration-provenance waves | Fresh isolated publication/fork refuses an intentionally missing prerequisite before boot, then succeeds with the complete derived closure; two co-hosted clusters keep distinct acquired schema projections; exact callable program set matches facts |
| 2 | R2 candidate/adoption | Per-run fork context wave, schema collision admission wave, namespace-removal work; later session-curation proof/adoption owner | Two concurrent divergent candidates yield exactly one durable winner, one refused loser, matching receipts, and matching next-turn live base |
| 3 | R1 total settlement | Live-drive run-loop repair, receipt settlement repair, acquire containment; align with [error-model PRD](docs/prds/error-model/README.md) | Inject a failure at every post-custody phase; each run reaches exactly one durable terminal/release state and the next message proceeds |
| 4 | R3 causal episode | Add a causal-closure exit to [messaging implementation wave](docs/prds/sci-execution-runtime/plan/messaging-wave-2026-08-06.md), especially M3/M4; bootstrap delegation-drive repair; [background-work PRD](docs/prds/background-work/README.md) | Two queued inbound messages plus a delegated wait prove no cross-episode prompt leakage and no premature drive terminality |
| 5 | R4 projection/composition | [error-model PRD](docs/prds/error-model/README.md) W1–W5, context wave fix lane, Datastar value-identity repair, strict REPL display wave, important-schema producer wave, database error-value repair, MCP envelope repair, universal output floor | Generative representations and repeated blocks remain schema-valid, uniquely identified, error-preserving, bounded by one consumer profile, and faithful across AI/HTML/MCP/log faces |
| 6 | R6 resource envelope | Operator directory-claim governor, exclusive sweep implementation, Flow bounded-submission, fault-committer durability, eval-scale economics, store/perf, shared-surface scheduling and no-crash architecture gates; [operational-events PRD](docs/prds/operational-events/README.md) records terminal transitions | Saturation/restart/reachability tests prove bounded admission and terminal cleanup for each owner; overflow proof matches the conservation ruling; heap proof matches the topology ruling |
| 7 | R7 recurring proof | [in-server tests PRD](docs/prds/in-server-tests/README.md), using existing `bin/test` and operator surfaces; no new runner | The complete scratch-cluster causal drive passes twice—deterministic provider every checkpoint, shipped provider and graphical QA at the ruled cadence |

R7 begins at order 1, not after order 6: each root's integrated falsifier is
added to the scenario when the root lands. Order 7 is when the assembled
scenario becomes the graduation gate.

## Honest residue

The following evidence is deliberately **not** explained by a named product
root beyond what was actually proven:

1. **Complete publication takes 70–82 seconds.** R6 explains why readiness and
   phase footprint must be observable; it does not identify the computational
   hotspot. Profile the named phases before assigning this to Malli,
   clj-kondo, Datahike, cache eviction, or source load.
2. **The dead `:refer-clojure :exclude [fetch]` finding blocked the manifest.**
   R5 explains why publication blast radius should follow declared dependency
   and load-blocking severity. The mechanism that elevated the specific
   finding did not reproduce and remains unknown.
3. **Two lanes exited with coherent work uncommitted.** This is a supervision
   and instruction-compliance failure. The repository instruction already
   states the correct rule; no Seon source mechanism follows from the evidence.
4. **No connected graphical browser was available.** This limits visual proof;
   it is not evidence that the web UI is defective beyond the HTTP/DOM defects
   actually observed.
5. **The live drive did not reach contracted-function, schema/database,
   multi-agent, deliberate-error, effect-settlement, or interruption phases.**
   These are unknown, not presumed failures. R7 owns making them recurring
   evidence.
6. **The isolated-root `status`/`start` probe emitted no readiness event before
   it was stopped.** This corroborates an unobservable slow boundary only. It
   does not establish foreign breakage or a publication cause.

Everything else in the drive, observer report, ten seeds, and blocker snapshot
is assigned above. No patch is proposed as a substitute for an unanswered
owner ruling.
