---
type: prd
status: ruled direction, slices not started
created: 2026-09-17
owner: Sean (rulings in the design dialogue of 2026-09-17 morning)
tags: [prd, steward, program-graph, indexing, sci, acquisition, datahike, branches, write-back]
---

# Program facts are the runtime

**The rule this document exists to make true:** the database holds everything
needed to run agents. Source files are one projection *into* it (indexing) and,
later, one projection *out of* it (write-back). An agent authors the shared
cluster environment with ordinary Clojure forms that go through our reader,
analysis and SCI evaluation and become program facts in the cluster's
database; every agent on that branch sees them. An agent may instead work on a
fork of the SCI context and of the database, throw the fork away, or merge it.
One set of Malli schemas, attributes, values and refs describes code no matter
which seam produced it; the seam is a provenance attribute and nothing else.

This is the precondition for asking agents to improve the system from inside
the REPL, so the indexing and updating of code has to be exact before that
work starts.

Vocabulary is the dependency's: Datahike **entity / attribute / ref /
transaction / branch / commit**, SCI **ctx / fork / Var**, clj-kondo
**analysis**, `seon.schema.admission/source` **provenance**. No new nouns are
introduced here. Read [AGENTS.md](../../../../AGENTS.md) §2–§3 first; every
slice below is an application of §2.1 (values carry their world), §2.2 (facts
over inference, derive or die), §2.4 (total boundaries) and §2.5 (one
mechanism).

---


## 0a. The mission this PRD serves (owner, 2026-09-16, verbatim intent)

"The goal of Seon is to become the best software generation platform ever to
exist and we are going to do it by designing a programming environment that
is AI first, not human first. Fuck editing files and compiling code. The
entire program graph is in the database and it's queryable and we know every
function and call edge and what each input and output is and what tests
exist and on and on. We need to leverage this to make it easy to refactor and
impossible to cause certain software failures (like removing a function
that's still in use, or allowing a function to be used if it's violating the
schemas). Help me find all of these connections and make them unbreakable
AND easy to teach agents how to refactor with the data."

Every ruling below is a special case of this: a connection the graph knows
is a connection the writer refuses to sever, and the refusal hands back the
affected set, which is the data an agent refactors from. The inventory of
those connections is
[unbreakable-connections-2026-09-16.md](../research/unbreakable-connections-2026-09-16.md)
(in progress).

## 1. The rulings (owner, 2026-09-17, in his words where they were words)

R1. Agents author the shared cluster environment with pure Clojure forms,
    evaluated through our parser and eval system, written to the shared
    database; every agent on the main branch gets them. Agents may also live
    in their own experimental SCI-context world, and ideally on experimental
    database branches too, which can be thrown away or merged into main.

R2. The same Malli schemas, attributes, values and refs describe code whether
    it was indexed from a file or added during an agent's turn. Provenance is
    an additional attribute, never a different shape.

R3. Indexing source code and later updating that code in the database are the
    same operation on the same entity by the same identity.

R4. The shared base runs the faster compiled JVM definition of first-party
    code; an agent that overrides a first-party function runs the database
    (interpreted) version, and that fact is tracked in the database. Loading
    is decided per identity by the admitted row's provenance.

R5. Writing agent-authored or agent-overridden definitions back to the `.clj`
    files is the other direction of indexing. It is planned, and it is gated
    on checks passing first. Until it lands, an agent's override of a
    platform function is recorded and loaded in SCI, but the JVM's own code
    paths keep running the compiled definition.

---

## 1b. Task-loop rulings (owner, 2026-09-17 10:20Z)

The point of structured tasks is that the agent always knows what it is
supposed to be doing and the system, not the agent, decides when it is done.

T1. **Done is a query.** An issue's done condition is its cited tests
    verifying or its detector no longer naming the subject.
    `:seon.issue/resolved-tx` is written by settlement only. `start!` admits
    an issue with either tests or a detector and refuses one with neither
    (decision 4 of `owner-decisions-2026-09-17.md`, now ruled by
    implication of T1).

T2. **Continuation derives from the issue.** An issue-assigned agent takes
    another turn while its issue is open and its provider-turn budget
    remains, and stops when settlement writes `resolved-tx` or the budget is
    spent. `my.turn/complete` and `my.turn/wait` have no effect on an
    issue-assigned agent's session; they remain the disposition of a
    conversational agent answering a message. The undisposed-turn notice
    (decision 8b) is therefore dissolved for issue agents, and the
    continuation predicate fails closed by construction because T1 refuses
    an issue with no done condition.

T3. **The agent sees its tests and their results every turn.** The opening
    names the exact tests (or the detector) that will run after every turn.
    After each turn the system appends one concise evaluation to the agent's
    history with the results of those tests — which passed, which failed
    with the failure's shown text, what is still open — so the agent always
    knows where it is and what remains. This is an ordinary generated read
    (`my.issue/status`) re-evaluated by the system turn when its evidence
    changed, not a new render path; its render pair is curated (Part 2b of
    the decisions document).

T4. **Budget exhaustion is loud and resumable.** When an issue-assigned agent
    exhausts its budget with the issue still open, the session closes with a
    typed outcome recorded on the issue (`:seon.issue/budget-exhausted-tx`
    or an equivalent fact chosen at implementation with a docstring), and
    the root agent receives a message naming the issue, the turns spent and
    the last status. Root may resume the issue with a larger budget through
    the same `start!`/resume path; nothing about exhaustion is silent and
    nothing resumes on its own.

T5. **Waking is out of scope for now** (owner: "I don't think we have a good
    system for waking right now, but we can work on that later"). Slices in
    this document use the existing wake mechanics unchanged.

## 1c. Rulings of 2026-09-17 11:00Z (owner, answering the questions of 10:50Z)

C1. **Clusters are many; a merge names its target cluster explicitly, and the
    bar for admittance is high.** Every function is fully specified (input
    and output contract) and has at least one test before it is admitted to
    a shared cluster. The updating and testing system must be exactly right,
    because it is how we know an update broke nothing.

C2. **The write-back / merge gate (S5):** the agent's issue tests pass AND
    every test reaching any changed function passes (the program graph knows
    both sets). It is an explicit operation, never automatic, for now. Its
    target is the development cluster `default`, the same cluster the edit
    hook updates, so both directions (files → facts by the hook, facts →
    files by the write-back) meet on one cluster and stay in sync; a human
    edit that changes a file between the agent's read digest and its write is
    refused by the digest-guarded editor, which is the correct refusal.

C3. **Override scope (S3): all first-party namespaces**, unless a definition
    cannot be evaluated in SCI (host interop it cannot reach), in which case
    that identity keeps loading the cluster's JVM-loaded definition and the
    fact records the override as not loadable in SCI, a typed state.

C4. **Root resumes an agent by updating its budget and running it again.**
    Resuming is one function: assert the new budget on the issue/agent and
    open the next turn. If it is more complicated than that, the abstraction
    is wrong and is fixed, not worked around.

C5. **The per-turn status is the agent's own data on the agent's debug page.**
    There is no separate context-rendering system for issues. The data is
    attached to the agent entity (and its issue) with well-thought-out shape
    and a render pair for AI and HTML; the AI render shows the forms to run
    alongside the reasoning so agents learn by seeing executions. If the
    agent page cannot show it today, that is the work.

C6. **No shell for self-modification.** Agents never run `bin/seon` or any
    shell command to change their own system; the runtime exposes the
    functions (adoption, tests, collection) as declared requests. Execution
    limits differ between root and ordinary agents through the existing
    per-agent config overlays, and root can update an agent's overlay to
    change its limits.

C7. **The collector's trigger multiple is two** (S8). First dry run approved
    and taken.

C8. Vocabulary retired on the owner's word: "layer one / layer two" (say
    JVM-loaded first-party code / agent-authored definitions); `my.edit` is
    external source editing and is not taught to issue agents; "schema
    declaration form for a render pair" meant the `:seon.render/ai` /
    `:seon.render/html` properties on an entity schema — say that.

## 1d. Rulings of 2026-09-17 11:20Z (owner)

D1. **The database runner is the one test gate.** `bin/test` becomes a
    launcher of isolated worker JVMs that run through the same runtime and
    SCI contexts and record through the same functions; it is not a separate
    test runner decoupled from the system. Default behaviour: name the
    cluster, run only the minimum tests implied by what changed since the
    last recorded run, and schedule them knowing globally how many tests are
    running in which workers. Closer integration with the database and SCI
    as necessary. (Slice S9.)

D2. **Focus on the agent's runtime REPL.** Agents update their runtime and
    pass the tests we give them. `my.edit` is not a focus now; it may later
    be the mechanism that writes accepted work back to disk (S5). Shell
    commands are not taught; a namespace's teaching is pulled into an agent's
    session only when that namespace is required there, and what a session
    requires is what teaches it.

D3. **Issues are how work is farmed for now; the same machinery is,
    eventually, an agent branching into its own world (database + SCI) and
    merging back into the shared cluster.** The admittance bar (C1) is
    enforced at the merge, not at evaluation: an agent may define an untested
    function in its own session; the merge/write-back gate refuses an
    identity no test reaches, by name; a detector opens the issue in the
    meantime; the per-turn status (T3) shows the agent which of its
    definitions still lack a test, so the bar is visible while it works
    rather than a refusal mid-thought.

## 1e. Rulings of 2026-09-17 18:00Z (owner, answering the lunch questions)

F1. **Destructive tests run on an immutable snapshot of the named cluster in
    an isolated process; recording goes to the cluster.** Accepted. And: we
    must know WHICH tests are destructive from facts, not by hand — the
    owner functions that destroy (boot, publish, kill, delete a store)
    declare it once, the test's destructiveness derives by reach, and it is
    indexed on the test so an agent can query where a test runs and why.

F2. **Write admission validates ALL inputs, always.** A grammar that is
    validated less (raw datom adds as an escape from entity validation) is a
    hack and is not an answer. Find out exactly what is happening at the
    admission seam; if validating the merged entity inside the transaction
    function is easy and correct, do it, and a failed validation ABORTS the
    whole transaction (Datahike rejects a transaction whose `:db.fn/call`
    throws — nothing partial is written). Dedicated research first.

F3. **The agent's history is rendered per evaluation, composed, never as one
    giant string.** Each evaluation entity renders through its own pair;
    the prompt is a composition of rendered units and the budget selects
    units. If the way evaluations are stored on the entity gets in the way,
    change the storage. Research and propose the composable design.

F4. **Conversational agents get their N turns like everyone else, and get a
    reply concept with feedback every turn.** A conversation's done
    condition is that a reply entity exists for the triggering message (a
    Markdown reply). The agent is told each turn, like an issue agent,
    whether it has replied yet and how (the exact form). Everything it does
    on the way renders well: `;;` comments saved and shown as thinking,
    evaluation results with their HTML renders on the page. No agent burns
    thirty turns with zero feedback because it never called the right
    function. This is the dual-render concern (AI and HTML) applied to the
    conversation.

F5. **Everything that is a symbol is stored as a symbol.** Written into
    AGENTS.md §3. Upgrade the schemas, delete the database and reset from
    scratch; no migration.

F6. **Root collects the store automatically** on the existing daily row at
    twice the last retained size, with the derived cutoff, no human step.

F7. **First agent tasks are the simple ones:** functions without contracts
    or without a reaching test, not render pairs (rendering is complicated).
    Detectors for both; the without-test detector depends on the call-graph
    fidelity fix so it does not lie.

F8. **The orchestrator resets `default` whenever needed and does not wait
    on the owner for important things.**

## 1f. Rulings of 2026-09-16 late evening (owner) — deletion, edges, provenance

Grounding: `../research/datahike-deletion-and-the-program-graph-2026-09-16.md`
(experiments on a throwaway in-memory Datahike at 5,000 functions / 30,000
edges) and `../research/schema-key-audit-2026-09-16.md`. Owner: "The
recommendation, which I agree with. Awesome. Do it!"

G1. **Deletion is retraction.** A deleted function, test, namespace, schema
    key or issue-with-no-note is `[:db/retractEntity …]`. No entity is kept
    for the sake of another entity's refs. The past is `history` / `as-of` /
    `since`. There is NO retirement attribute. Ruling 47's two corollaries
    (identity rows never retract; the population invariant) are RETIRED and
    AGENTS.md §2 is rewritten in the same commit as the change.
G2. **Call edges and test reach are values, not refs.** `:seon.fn/calls
    [:set :qualified-symbol]`, `:seon.test/reach [:set :qualified-symbol]`
    (symbols-everywhere). Deleting a function touches only its own datoms;
    "A calls a name with no row" is one Datalog clause and is the honest
    unresolved-call fact, reported positively by a query, never prevented by
    the writer. Refs stay where a genuine entity relation exists (`:seon.fn/ns`,
    `/file`, `/ast`, `/arities`, `/capability-fn`). Reverse reach joins the
    symbol's AVET index; `gate-sets` maps eid→symbol once per operation.
G3. **Delete the tombstone machinery**: `seon.db/write-tombstone-validator`,
    `seon.cluster.source/identity-tombstone-rows` / `mintable-identity` /
    `identity-ref`, the identity-only "retired row" shapes in
    `seon.program/exact-replacement-tx` callers. A ref to nothing is refused
    at the writer as Datahike already refuses a missing lookup ref.
G4. **Looking is an event and the event is a datom.** If a reader must
    distinguish "we looked and found nothing" from "we never looked", the
    looking is a positive fact. Every definition row carries the identity of
    what produced its definition facts (the file entity/digest for an indexed
    declaration, the evaluation for an agent-authored one), REQUIRED.
    `analyzed?` is its presence; "calls nothing" is that fact plus no edges;
    `#{}` is never a sentinel and submission-time-only validation is a
    pre-read (rejected).
G5. **A component is part of its parent's value.** The whole-entity write
    validator validates the parent pulled with its components expanded as one
    value against the parent's schema; no identities are invented for
    component rows.
G6. All of G1–G5 land in the ONE reset with symbols-everywhere and the
    required-derivables slice (S2). Database data is disposable; no migration.


### 1g. Owner rulings 2026-09-17 ~05:10Z (deletion, agents, capability-fn)

- **Deletion is strict with no escape** (owner, verbatim intent): "we want
  either all the fixes in a single transaction (so we can verify with the
  db-after that everything is still correct) or we will farm out in a
  distributed way to agents to refactor and remove references to the
  function so it can be removed. If an agent declares a function and
  doesn't want it they just remove it and no one is depending on it so
  there's no problem ... lets stick with being strict and figuring out how to
  make this work with agents."
- **Agents are never retracted; an archived positive fact hides them** (owner:
  "Agents are always resumable but sure we can have an archived flag and if
  so we don't surface it in the UI"): `:seon.agent/archived-tx`, derived
  `archived?`; the debug page and lists filter it; nothing else changes.
- **`:seon.fn/capability-fn`: the ref is deleted; the handler symbol joins
  the deletion refusal set** (decided by the orchestrator from the
  program-graph research: two live pairs, the docstring names the symbol
  as the primary fact, `:seon.effect/capability` already carries it; cost
  one lookup on the effect path; overturns G2's exception).
- **The message/wake/provenance modeling is NOT settled by identity values**
  (owner: "the message wake system is a hack and we need to refactor it ...
  sometimes you need to refactor how the data is modeled"): research pass
  `message-wake-and-provenance-modeling-2026-09-17` finds the prior notes
  and proposes the model per case.

### 1h. Owner rulings 2026-09-17 ~05:50Z (message, wake, provenance — from `message-wake-and-provenance-modeling-2026-09-17.md`)

- `:seon.message/about` is split three ways: the subject becomes the identity
  token the agent supplies (no `resolve-about` pre-read); `:seon.message/from`
  is the sole inside-wake marker; the assignment/declination protocol gets
  its own attribute.
- `:seon.wake/inside` stays declared on `from` for now; deriving inside/
  outside from transaction provenance is revisited with the listening
  redesign (needs a positive human account identity on inbound transactions).
- `:seon.eval/origin` retypes to `:seon.issue/id` (a value that survives the
  issue's deletion); the outline keeps its "generated by" label.
- `:seon.cluster.eval/refreshes` and both `refresh-call` functions are
  deleted; supersession stays the since-diff's fold over ordinals (PRD §14);
  no read-form digest until a query needs it.
- Drift to repair with the same slice: message handling RETRACTS the routed
  inbox edge (turn.clj:435-437, message.clj:276-277), which ruling 70
  forbade; the PRD's mechanism is restored — `:seon.message/to` listened,
  handled = a claim ref from the handling run, answering by the `:t` rule.

### 1i. Owner ruling 2026-09-17 ~06:35Z — the Datahike modeling study overrides prior decisions

Owner, verbatim: "learn from the datahike modeling and override our previous
decisions on the schemas and refs vs components or whatever. don't be
dogmatic." The astra study `datahike-modeling-study` (its note
`../research/datahike-modeling-study-2026-09-17.md`) is the authority the
reset batch follows where it corrects G1–G6, 1g, 1h or the reset plan; the
integrator rebases `reset-batch` on its corrections before the reset; every
override is written into this PRD with the Datahike line that decided it.

### 1j. Every function carries a contract, private included (owner, 2026-09-17 16:30Z)

Evidence (one Datalog query on default, pid 94566): 5,191 functions, 3,161
private, 16 private with a contract; 520 functions call a `seon.db` read
directly, 354 of them private, 352 with no contract; 69 public contracts
declare a bare `:map` output. The three overnight "error read as a row"
instances were two uncontracted private consumers and one in-body misuse.

Rulings:
- **All private functions can and should have contracts.** The public-only
  rule in AGENTS §2.4 is retired; instrumentation arms every function that
  declares a contract, private included.
- **Errors stay values.** A failed database read is an error describing
  what the caller did wrong; it is allowed at every boundary without being
  listed, and a contracted consumer refuses it by shape (the wrapper's
  buried-error propagation). No throwing.
- **First mined issue class for live agents:** private read-consumers
  without a contract (352), then every private function without one, then
  the 69 bare-`:map` outputs. Fix the critical ones now (db operations,
  anything that can return an error, orchestrator's judgement), expecting
  and welcoming breakage: every new red is a finding about a wrong
  understanding, filed as an issue.
- **Open design question (owner):** how issues are triaged — a namespace
  steward agent triaging and launching sub-agents to write contracts and
  sanity-check data in and out. Design note requested before Phase 4.

### 1k. Loud in development, collected in production; one dial (owner, 2026-09-17 17:45Z)

Owner: "We want to fail loud in development and we need the instrumentation
to help make that happen (so throw instead of catch in dev) and we need the
production system to keep collecting the errors or to write them to the
database and then we'll just triage them from there — that should work for
all but database errors so careful there."

The dial already exists: `:seon.config/on-core-error` `:panic | :record`
(`resources/seon/schemas/seon.config.edn:62`; `config/default.edn:285` is
`:panic`; the panic reporter is `src/seon/instrument.clj:451`). The ruling
extends it to every check the audits are adding:
- **`:panic` (development):** a contract violation, a refused database read
  reaching a consumer, or an unknown transaction outcome THROWS at the seam
  (the instrumentation wrapper for contracts; the one error predicate's
  consumer helper for reads) with the flat error as ex-data — never caught
  and relabelled, never carried on as data.
- **`:record` (production):** the same events are recorded as fault facts
  through the existing fault committer with their provenance, the flat
  value is returned, and the system continues; triage happens from the
  database (the issue detectors read faults).
- **Database errors are the exception:** when the read or write that failed
  IS the database (store unavailable, writer refused, connection gone), the
  fault cannot be written there; it goes to the operator's durable fault
  log (a file under the process root's logs, the same evidence shape) and
  the next healthy transaction records the backlog. Recording a database
  error into the database that refused it is the recursion to design out.
- One predicate (`seon.error/error?`) decides "is this an error"; one
  helper decides throw-or-record from the dial; no per-namespace copies.

### 1l. The best schemas we have ever written come out of this (owner, 2026-09-17 18:00Z)

Owner: "I want the best schemas we've ever written to come out of this. We
need a thoughtful analysis about the data model first, how we can improve
it, and then we need to have the right checks in place and to return the
best data for handling it in the future (system crash for critical so we
immediately fix those)."

Order: data-model analysis first (the error value family and every shape
the audits found flowing uncontracted), then the checks, then the contract
campaign. A contract written against a wrong or missing schema is the
mirror class again. The error model must say what every error carries so
that handling it later is a query (kind, class, layer, member, expected,
offending value, fix, evidence, provenance) and which errors are critical
(a crash in development, an immediate fix).

### 1m. Owner rulings 2026-09-17 ~21:10Z (answering the error design's options)

- **Critical fault under `:panic`:** stop only the affected agent/cluster
  graph and keep the JVM (the third option in
  `research/error-and-data-model-design-2026-09-17.md` §1.3); this requires a
  durable failed state and positive visibility at every status surface — the
  dead-turn-proc blocker is the same requirement.
- **Database errors:** "if the db is down panic because the system is down,
  so shut down what we need to and the system REPL has to fix it. Otherwise
  errors are just data so we store them and connect them to best surface
  them." No durable side log with replay; a database failure is a system-down
  panic handled at the REPL, and every other error is a stored fact.
- **Error identity:** the owner's model is that the SCHEMAS are the kinds — a
  shared base every error carries, plus the required members that make an
  error a database failure, a system failure, a turn failure. The three
  spellings offered were not accepted as posed; the design is to be
  re-expressed in those terms (shared aspects + per-kind required aspects,
  kind derived from which entity schema the value satisfies) before any
  constructor changes.
- **The one-predicate consolidation (B1) waits until the owner has read the
  design note.**

### 1n. Owner ruling 2026-09-17 ~21:50Z — boot carries no test namespaces

Asked whether the cluster JVM should keep booting with the test classpath
(`research/boot-and-load-sequence-2026-09-17.md` §4): "We are indexing the
tests into the database and building our own runner so yeah I think the
platform tests need their own testing using the system and once it's booted
we do our own thing." Ruling: the cluster boots from `src/` alone (option A,
now: drop the test classpath from the cluster launch,
`script/seon/fresh_operator.clj:2078`, `:469-473`); tests are program facts
resolved by identity through `seon.test/resolve-test` after boot (test-system
stage 2), never required at boot; the platform/destructive tier tests the
system from isolated snapshots (test-system PRD §0b). Option C (the graph
declares admission per namespace) is the model fix, folded into stage 2.
Owner, ~22:05Z: "Once the system is up and running most of the tests and
software should be at runtime." The boot is the platform from `src/` only;
everything after — tests, agent-authored definitions, repairs — is program
facts executed by the running system (the in-process runner, resolution by
identity, `run-owned`), with isolated snapshot workers reserved for the
platform and destructive tier.

## 2. What exists today, with the seams named

Verified on `steward-platform` at `a36d55c3b`/`849bbce0b` on 2026-09-17.

### 2.1 Indexing (files → facts)

- The edit hook (`.claude/seon-hook.edn:25-29`, root `.`, cluster `default`)
  runs `bin/seon init --dev default --changed PATH` after a tracked edit; the
  operator evaluates `(seon.cluster/refresh-source! …)` inside the running
  JVM (`script/seon/fresh_operator.clj:2404-2406`). `refresh-source!` is
  public (`src/seon/cluster.clj:2355`).
- Analysis is `seon.fn.analyzer/analyze` (`src/seon/fn/analyzer.clj`),
  clj-kondo analysis over paths. `seon.fn/build-artifact`
  (`src/seon/fn.clj:1580`) analyses a file; `src/seon/fn.clj:712-730` analyses
  a **source string** on stdin (`paths ["-"]`) after prefixing the namespace
  form so aliases resolve — the same analysis a file gets.
- Rows are built from analysis by `seon.program/canonical-row` under the
  declared shapes (`src/seon/program.cljc:863`; shapes are now a threaded
  value, `:seon.program/shapes`, since `849bbce0b`), and written by
  `seon.fn/reconcile-tx` (`src/seon/fn.clj:2352`) with exact replacement
  (`program/exact-replacement-tx-in`, `src/seon/program.cljc:1023`).
- A function entity carries `:seon.fn/sym` (identity), `:seon.fn/ns` (ref),
  `:seon.fn/source`, `:seon.fn/arglists`, `:seon.fn/doc`, `:seon.fn/spec`,
  `:seon.fn/private?`, `:seon.fn/calls` (`[:set :seon.db/ref]`,
  `resources/seon/schemas/seon.fn.edn:21`), `:seon.fn/call-arities`
  (`:35`), declared keys, and its file coordinates (`:seon.fn.file/*`,
  root-relative since `28f1a761e`). Provenance is
  `:seon.schema.admission/source :core`.
- Identities are never retracted: deletion retracts definition facts and the
  identity survives as a tombstone (ruling 47).

### 2.2 Evaluation (an agent's form → facts)

- An agent's turn evaluates forms in its SCI fork (`fork-for-turn`,
  `src/seon/sci/eval.clj:1790`). After evaluation, for each interned Var that
  changed, the seam builds the program row **by hand from the Var's
  metadata** — `src/seon/sci/eval.clj:396-430`: `:seon.fn/sym`, `:seon.fn/ns`,
  `:seon.fn/source`, `:seon.fn/arglists` (printed), `:seon.fn/private?`,
  `:seon.fn/doc`, `:seon.fn/macro?`, `:seon.fn/spec` (normalised through
  `accretion/data-contract!`), `:seon.fn/workload`; tests get `:seon.test/*`
  and the test markers.
- That map is validated by `program/declaration-row row :all :agent`
  (`src/seon/program.cljc:906`), installed into the live base by
  `install-evaluated-rows!` / `install-row!` (`src/seon/sci/eval.clj:938`,
  `:791`), and written by the turn writer's `row-tx`
  (`src/seon/turn.clj:1277`) in the transaction that settles the evaluation.
- A `defn` without a `:malli/schema` contract is not installed and the agent
  is told so (`src/seon/repl.clj:127-129`).

**The defect (R2/R3 broken today):** the evaluation seam does not analyse, so
an agent-authored function has **no `:seon.fn/calls` edges, no declared keys,
and no analyzer-derived attributes**. It validates anyway because maps are
open and those attributes are optional. Reach selection, `tests-reaching`,
`changed-since-green` and the population invariant are all weaker for agent
code than for indexed code, silently.

### 2.3 Acquisition (facts → a running SCI context)

- `build-base-ctx` (`src/seon/sci/eval.clj:183`) builds the cluster's base
  SCI context. First-party functions enter **by reference to the JVM Var**
  with `sci/copy-var*` (`:197`, `:1168`); agent-authored rows are interpreted
  from their database source. Each agent's persistent context is a fork
  (`fork-cluster-ctx`, `:1877`) that receives accepted base changes before
  later turns; a candidate context (`fork-candidate-ctx`, `:2572`) tests a
  definition before `accept-candidate!` (`:2629`) installs it.
- The choice "reference the JVM Var" vs "interpret the database source" is
  made **by namespace kind**, not by the admitted row's provenance. An agent's
  override of a first-party identity is therefore only a shadow in that
  agent's fork; nothing records it as a state, and the base never loads it.

### 2.4 Platform code and the JVM

First-party namespaces run on the JVM (loaded by `require` at boot). Compiled
callers inside the JVM call compiled functions directly; they never consult
SCI Vars. So an override recorded in the database and loaded in SCI changes
what agents run, not what the turn loop, the writer or the web server run,
until the source file is rewritten from the database and the namespace is
reloaded (`refresh-source!`). That is R5's gap and the write-back closes it.

### 2.5 Branches

Datahike's versioning namespace in our fork
(`reference-code/datahike/src/datahike/versioning.cljc`) provides `branch!`
(`:212`), `delete-branch!` (`:279`), `force-branch!` (`:323`), `branch-as-db`
(`:499`), `commit-as-db` (`:469`), `branch-history` (`:191`), `commit-id` and
`parent-commit-ids`. There is **no merge operation**. Seon forks a cluster
from the published commit with `force-branch!`. A cluster is one connection;
custody, wake listeners and publication all hang off it. The agent-facing
branch functions are a target row in the vocabulary table, not landed.

---

## 3. Invariants every slice must leave true

I1. **One shape per kind of code.** A function, namespace, schema key or test
    is one entity with the attributes declared in `resources/seon/schemas/`,
    whichever seam wrote it. The only attributes allowed to differ between
    the two seams are `:seon.schema.admission/source` and the file
    coordinates — `:seon.fn/file` (a ref to the file entity), `:seon.fn/form-span`
    (byte offsets) and any `:seon.fn.file/*` fact — which an agent form does
    not have until it is written back. Absence of file coordinates is
    meaningful, never defaulted. (Amended 2026-09-17 10:30Z after the S1
    lane's probe: the coordinate attributes are the two above.)

    **Schema keys.** Schemas are declared once under `resources/seon/schemas/`
    (AGENTS.md §3). An agent declares one by evaluating
    `(seon.schema/register! key form)`, which the SCI reader recognises as a
    schema declaration event (`src/seon/sci/reader.cljc:347-374`); the
    indexer does NOT read `register!` from `.clj` source and must not learn
    to. Parity for a schema key is therefore between the resource seam and
    the evaluation seam: the same `:seon.schema` entity results from a
    declaration in a schema resource and from an agent's `register!`. S5
    writes an agent-declared schema back into the schema resources, never
    into a `.clj` file.

I2. **Derivable means required.** Any attribute the analyzer derives for every
    declaration of a kind — `:seon.fn/calls` first — is a required key of
    that entity schema, with the empty set as a legitimate value. A producer
    that skips analysis is then refused by the schema instead of validating
    a smaller map.

I3. **Provenance decides loading, per identity.** At acquisition, an identity
    whose admitted row is `:core` loads by reference to the JVM Var; one
    whose admitted row is `:agent` loads by interpreting the database source.
    The override set is a query, never a flag.

I4. **Population invariant and tombstones** (ruling 47) hold on both seams:
    every name the SCI context can resolve has a program entity; identities
    never retract.

I5. **Absence is typed.** "No calls", "no file", "never written back", "no
    overrides" are values or typed unknowns, never silence.

I6. **One analyzer, one row constructor, one writer per direction.** No second
    analysis, no hand-built rows, no compatibility arity.

---

## 4. Slices

Each slice names its owner files, its acceptance regression on the canonical
harness (`seon.test-support/with-database`, armed contracts), its live proof
on `default`, and its verification boundary. Lanes follow AGENTS.md §7
launching rules: cite this section, carry raw evidence, one class per lane.

### S1 — Analysis on both seams (R2, R3; I1, I6)

**Change.** The evaluation seam stops building rows from Var metadata. For
each accepted top-level form in a turn, it hands the form's **source text
and the agent's namespace context** (name, requires, aliases — the SCI
reader already tracks them, `namespace-info`,
`src/seon/sci/reader.cljc:181`) to the same source-string analysis the
indexer uses (`src/seon/fn.clj:712-730`, made a public function with a
contract if it is not one), and builds rows with `program/canonical-row`
under the threaded shapes. Runtime-only facts the analyzer cannot know are
**merged onto** the analysed row, never used instead of it: the normalised
contract data (`accretion/data-contract!`), the workload tag, the test
markers. The hand-built map at `src/seon/sci/eval.clj:396-430` is deleted.
`program/declaration-row` continues to stamp provenance `:agent`.

**Owner files.** `src/seon/sci/eval.clj` (the row seam), `src/seon/fn.clj`
(exposing the source-string analysis), `src/seon/program.cljc` only if a
constructor signature must widen (accretion, never a second arity kept for
compatibility).

**Acceptance regression (the standing one, keep forever).**
`seon.program-test/indexed-and-evaluated-declarations-are-the-same-entities`:
take a fixture namespace file with functions that call each other, declare a
schema key, and hold a deftest; index it through the publication path onto a
fixture branch; evaluate the same forms as an agent through the turn writer
onto another fixture branch; pull every declaration entity from both; assert
the pulled maps are equal after removing `:db/id`,
`:seon.schema.admission/source` and `:seon.fn.file/*`. Assert
`:seon.fn/calls` is non-empty on the evaluated side.

**Live proof.** On `default`: evaluate a two-function namespace as Juniper,
then `(seon.fn/tests-reaching db "<the callee>")` and a `:seon.fn/calls`
pull show the edges. Compare with `bin/seon init --dev default --changed` of
the same source written to a scratch file under `test/`.

**Boundary.** In-process on `default` plus the cold gate on
`seon.program-test seon.fn-test seon.turn-test seon.sci.eval-test`.

### S2 — Derivable attributes are required (I2, I5)

**Change.** In `resources/seon/schemas/seon.fn.edn`, `:seon.fn/calls` becomes
a required key of the function entity map (empty set allowed). Do the same
for any other attribute S1 shows the analyzer always produces (candidates:
`:seon.fn/arglists`, `:seon.fn/private?`; decide from the S1 diff, not from
taste). Refusals must name the producing seam
(`seon.error/diagnostic`). Requiring a previously optional key is breakage
under §2.5, admissible here because database data is disposable by ruling:
the slice records RESET NEEDED and the orchestrator reforks `default`.

**Acceptance.** A hand-built function row without `:seon.fn/calls` is refused
by `seon.db/transact!` with a refusal naming the key; the S1 regression still
passes; `bin/test --platform` green.

**Boundary.** Cold gate on `seon.fn-test seon.program-test seon.schema-test
seon.db-test` plus the reset-boundary live proof (fresh `default`, Juniper
reseeded, adoption converged).

### S3 — Acquisition decides by provenance (R4; I3)

**Change.** `build-base-ctx` and the fork/diff path resolve each identity by
its admitted row's `:seon.schema.admission/source`: `:core` → `sci/copy-var*`
of the JVM Var (as today); `:agent` → interpret `:seon.fn/source` from the
database (as agent namespaces do today), **including when the identity is
first-party**. `accept-candidate!` applies to first-party identities exactly
as to agent ones, so an accepted override reaches every agent's fork through
the base diff. `dir` and `doc` (`src/seon/sci/eval.clj:1194`, `:1222`) render
the source of record, i.e. the database row, so an agent sees what it will
run.

**Owner, 2026-09-16 ~02:10Z:** "We should have a function that can generate
the SCI context for an agent from the database and that way we can just
update the database and regenerate right?" — S3 is built as ONE pure
`(base-ctx db)` over a database value; an agent's context is a fork of it
plus its private layer (in-memory only, §15); accepted change = regenerate;
the base-diff path is an optimization proven equal to regeneration or
deleted, decided by measured cost.

**The override set is a query, declared once as a public read** in the
program namespace: identities whose current admitted row is `:agent` and
whose namespace has a file entity under the `src` root
(`:seon.fn.file/relative-root "src"`). The indexed definition it replaced is
readable through `seon.db/history`; no shadow copy is stored.

**Honest boundary, documented in the render:** the JVM's own compiled callers
still run the compiled definition until S5 writes it back and reloads. The
`doc` of an overridden first-party function says so in one line, derived
from the same query (I5: this is a typed state, not silence).

**Acceptance.** `seon.sci.eval-test/an-accepted-override-of-a-first-party-function-loads-from-the-database`:
as an agent, define a contracted replacement for a small first-party function
that agents call (pick one with no Java interop); accept it; a second agent's
next-turn fork calls the replacement; the JVM Var is unchanged (compare
`(deref #'the-fn)` identity before and after); the override query names the
identity; `doc` carries the not-yet-loaded line. A second test: a `:core`
identity still resolves to the identical JVM function object.

**Boundary.** In-process on `default`; cold gate on `seon.sci.eval-test
seon.cluster.agent-test seon.turn-test`.

### S4 — Experimental database branches (R1)

Two slices, because fork/discard is cheap and merge is a design.

**S4a — fork and discard.** An agent-facing request creates a branch of the
cluster's database from its current commit (`datahike.api/branch!`, or
`force-branch!` from an exact commit), forks the agent's SCI context, and
rebinds the agent's custody so its elided `seon.db` arities resolve to a
connection on the branch (the connection is carried as a value on the agent's
scoped environment, §2.1; `seon.db/call-with-custody`,
`src/seon/db.clj:259`, is the one scope). Wakes: the agent's turn-loop
listener moves to the branch connection; messages addressed to it on main are
visible as a read of main and are not answered until merge or discard
(document this in the message render). Discard is `delete-branch!` plus
dropping the fork. Every live experimental branch roots its ancestry for the
collector, so a branch carries its creating agent and instant as facts on
main, and `root/maintenance` reports branches older than a declared age
(I5: an abandoned branch is a typed finding, not silent growth).

**S4b — merge by replay.** Our fork of Datahike has no merge. Merge is
defined as: read the branch's transactions since its fork commit
(`datahike.api/tx-range` on the branch connection), and transact them onto
main in order, under a gate. Program facts merge by identity upsert;
evaluations and turns are agent-scoped and cannot collide. A collision (two
branches replacing the same identity since the fork) is detected by comparing
the identity's main-branch `:t` with the fork commit's basis and is a typed
refusal naming the identities, resolved by re-running the gate on a rebased
branch (replay main's transactions since the fork onto the branch first).
The gate before a merge is the S5 gate (reach-selected tests plus the
platform tier) run against the branch.

**Acceptance.** S4a: an agent on a branch transacts and defines; main is
byte-identical before and after; discard deletes the branch and the fork; the
maintenance report names an aged branch. S4b: a branch that defines a
function and a test merges onto main and both entities exist there with
provenance `:agent`; a conflicting branch is refused by name; the replay's
transactions carry the branch's provenance in `tx-meta`.

**Boundary.** Own scratch cluster for the destructive parts; cold gate on
`seon.db-test seon.cluster.agent-test seon.turn-test` and the new namespace.

### S5 — Write-back, database → files, gated (R5)

**Change.** One declared request (owned by the effect owner, executed on the
detached arm `my.background` with its 600 s bound, `config/default.edn:36`)
takes the override set from S3 and, for each namespace with overridden or
agent-added first-party identities:

1. **Candidate.** Build the namespace's new source: the file's current forms
   with each overridden identity's `:seon.fn/source` substituted and each new
   identity appended in a deterministic position (after its last caller or
   at the end). Analyse the candidate source with S1's analysis; the
   candidate's rows must equal the database rows for those identities apart
   from file coordinates (I1, mechanically checked).
2. **Gate.** In a candidate SCI context, run the tests reaching every changed
   identity (`seon.fn/tests-reaching`), then `bin/test --platform`'s
   declared set in process. Red is a typed refusal naming the tests; nothing
   is written.
3. **Write.** Through the one editor (`seon.edit`, digest-guarded on the
   file's current digest), write the candidate source.
4. **Reload and republish.** `seon.cluster/refresh-source!` for the written
   paths; the identities' admitted rows become `:core` again by the ordinary
   indexing path (the override set shrinks to empty by construction), and
   the JVM now runs the definition.
5. **Commit.** A path-limited `git commit` naming the agent and the issue.

The gate's exact composition is the owner's ruling (see §6); the default
above is the one the cold gate already uses.

**Acceptance.** An agent overrides a first-party function; the request writes
the file; the S1 diff regression holds between the rewritten file's index and
the agent's row; the override query is empty afterwards; a red candidate
writes nothing and names the failing test. Plant a symlinked sentinel in any
cleanup path (AGENTS.md §6).

**Boundary.** Own scratch checkout and cluster for the write; cold gate on
the effect, edit, cluster and fn namespaces.

### S7 — The issue task loop (T1–T4)

**Change.** In `src/seon/issue.clj` and `src/seon/turn.clj`: `start-tx`
admits tests-or-detector and refuses neither by name (decision 4);
`next-agent-work` / the continuation predicate for an agent with an open
issue derives from `issue open ∧ budget remaining` (T2) and ignores
dispositions; settlement runs the issue's done-query at the ordinary close
(tests today, detector added) and writes `resolved-tx`; the system turn
appends the concise per-turn status evaluation (T3) whenever the done-query's
evidence changed; on exhaustion the typed outcome is written on the issue and
one message is sent to root (T4); the resume path accepts a larger budget.
The opening (decision 2) states the done condition and the tests in one
block and drops any `my.turn/complete` teaching for issue agents.

**Acceptance.** (1) an issue with a detector and no tests starts; one with
neither is refused by name; (2) an issue agent whose last form returns
neither disposition takes another turn while the issue is open; (3) the
agent's history after turn *n* contains the status evaluation naming the
failing test's shown text; after the fix lands it names the pass and
settlement wrote `resolved-tx`; (4) a budget of two turns exhausts, the issue
carries the typed outcome, root has one message naming it, and a resume with
budget four continues from the same issue. All on the canonical harness
through the virtual-turn helpers already in `test/seon/turn_test.clj`.

**Boundary.** In-process on `default` with Juniper; cold gate on
`seon.issue-test seon.turn-test seon.turn-loop-test seon.issue-settlement-test`.

### S8 — Root runs the collector and the rest of its maintenance (owner, 2026-09-17 10:45Z)

**Ruling.** The root agent owns storage reclamation and the other scheduled
maintenance; it is not an operator chore and not a reset. Grounding is
decision 3 of `owner-decisions-2026-09-17.md` and batch C's reading of
`reference-code/datahike/src/datahike/gc.cljc` and
`reference-code/konserve/src/konserve/gc.cljc`.

**Change.**
1. **The signal is the unreachable-key ratio, from numbers the dependency
   already computes.** Numerator: `konserve.filestore/count-konserve-keys`
   (`filestore.clj:221-227`). Denominator: the retained-file count Datahike's
   own mark produces, which `seon.cluster.registry`'s dry run already returns
   as `:seon.cluster.registry/retained-files` and the real collection path
   currently discards — return the same inventory map from both. Stored as a
   maintenance fact by the owner that landed on 2026-09-16
   (`seon.maintenance/last-collection`), whose typed "never collected" answer
   means "collect once to establish the denominator", never "fine".
2. **The cutoff is derived, never tuned.** `remove-before` = the creation
   instant of the oldest commit id any live fact still names — today the
   published `:seon.source/commit-id` a cluster forks from — resolved through
   the commit record's `:datahike/created-at`; with no such fact, the branch
   heads alone (which `gc-storage!` retains unconditionally). Any live
   experimental branch (S4, later) is in the roster and so is marked.
3. **The trigger lives on the existing schedule row.** `root/maintenance/footprint`
   (`src/seon/schedule.clj:46-50`, daily) compares the two numbers and calls
   `seon.operator/collect!` with the derived cutoff when keys exceed the last
   retained count by a declared multiple (a dial in the maintenance config
   family, default to be ruled: two or three). `root/maintenance/compact`
   (weekly) stays as the floor. No new counter, cache or index.
4. **Defect to close in the same slice:** `gc-storage!` ignores unknown option
   keys, so a caller passing `{:dry-run? true}` (not in the `:datahike.gc/*`
   family) performs a REAL collection — it happened once on `default`. The
   one Seon entry point refuses unrecognised option keys by name.
5. **The rest of the portfolio** (`reap-dead-roots`, `rotate-logs`,
   `process-census`) is reviewed for the same absence-as-health shape: each
   row reports a typed result fact, and "nothing to do" is a value.

**First step before any code:** one dry-run collection on `default` with the
correct key (`{:seon.operator.collect/dry-run? true}`), which deletes nothing
and returns candidate bytes, retained files and the mark's duration — the
denominator and the real cost at today's size.

**Acceptance.** (1) after a dry run, the maintenance fact carries retained and
candidate counts and the mark duration; (2) with keys above the multiple, the
scheduled row collects with the derived cutoff and the fact updates; below it,
it records "no collection needed" with both numbers; (3) an unknown option key
is refused by name; (4) the store on `default` stays under the multiple across
a day of gates without a reset — measured, with the numbers in the landing
note.

**Boundary.** Scratch root for the destructive proof; cold gate on
`seon.maintenance-test seon.maintenance-schema-test seon.cluster.registry-test`.
The dry run on `default` is the only production-root action, on the owner's word.

### S9 — The database runner is the one gate (D1)

**What exists.** `seon.test/run` and `run-owned` run one test Var and commit
result facts (pass/fail/error counts, failure components keyed by signature,
the run ref) — data, delta-recorded. `seon.test/check` selects tests reaching
changed symbols; `seon.fn/gate-set` (`src/seon/fn.clj:1157`) and
`tests-reaching` (`:1214`) derive selection from `:seon.fn/calls`;
`changed-since-green` (`src/seon/test.clj:54`) derives the set from the last
recorded green basis. A run is a `:seon.test.run` entity with id,
program digest, basis `:t` and destination branch
(`resources/seon/schemas/seon.test.run.edn`). `bin/test` is a coordinator
that snapshots HEAD plus named paths, publishes a base, launches pooled
worker JVMs under a two-slot bound (`bin/_test-slot`), tallies, and records
through `seon.test.runner/commit-results!` (`src/seon/test/runner.clj:2313`).

**What is decoupled today, and therefore the work.**
1. **Runs are keyed to the publication, not to a cluster.** Results land on
   `:current-src`; an agent-authored test lives on its cluster branch. The
   run request names a cluster; selection and recording read and write that
   cluster's database; the run entity carries the cluster (branch) it ran
   against, which the schema already has a field for.
2. **Workers resolve tests from files.** A worker loads a test Var from the
   snapshot's classpath. An agent-authored test has no file (until S5); the
   worker acquires the cluster's program from its facts — the same base SCI
   context acquisition agents get (S3) — and runs an evaluated test through
   `run-owned` exactly as the agent would. One resolution: by test identity,
   from facts; the file is where the analyzer found it, not how the test
   loads.
3. **Selection is per invocation, not global.** Two invocations select
   independently and can both run the same test. The set of tests currently
   running, per worker, is a fact (the run entity's in-flight tests with the
   worker's process record), and a new invocation subtracts what another
   worker is already running against the same program digest and basis.
   The slot bound stays as the process-count bound; the fact is what makes
   scheduling knowable.
4. **The tally is the database.** The coordinator's printed tally is a render
   of the run entity's results; nothing is counted twice. Logs remain logs.
5. **In-process and cold are one path with two hosts.** The development JVM
   and a worker JVM call the same functions with different custody; the
   loader gap (`the-in-process-test-loader-cannot-load-a-namespace-needing-a-test-alias-dependency`)
   is closed by deriving the worker's and the dev JVM's classpath from the
   same alias.

**Acceptance.** (1) `bin/test --cluster default` after one edit runs exactly
the tests `changed-since-green` names for that cluster and records them on
it; (2) an agent-authored deftest with no file runs in a worker and its
result lands on the agent's cluster; (3) two concurrent invocations against
the same digest and basis do not run the same test twice, and the second
reports what it skipped and why; (4) the printed tally equals a query over
the run entity; (5) a run's recording of an unchanged result writes zero
datoms (already true, kept as the regression).

**Boundary.** This slice rewrites the coordinator's spine; it runs on a
scratch checkout and cluster, with the cold gate proving it on its own
namespaces (`seon.test.runner-test`, `seon.test-runner-test`,
`seon.test-support-test`, `my.test-test`) and the platform tier. Astra lane,
design review at `high` effort before implementation.

### S10 — The conversational reply and per-turn feedback (F4)

**Ruling.** A conversational agent gets its N turns like everyone else. Its
done condition is a fact: a reply entity exists for the triggering message
(a Markdown reply, addressed back to the sender through the one message
owner, `seon.cluster.message` / `my.message`). Every turn, exactly as the
issue task loop does with `my.issue/status`, the system re-evaluates a
generated read that tells the agent whether it has replied and, if not, the
exact form to call. Nothing it does on the way is invisible: `;;` comments
are saved and rendered as a distinct thinking block on the agent page,
evaluation results render through their value's HTML pair, and the AI
history shows the same units. No agent burns its budget with zero feedback.

**Depends on.** S7 (the task-loop mechanics: derived continuation, per-turn
status as a generated read, budget exhaustion) and the composable-history
research (F3) for the unit rendering. Design inputs arrive from
`docs/prds/steward-platform/research/composable-history-2026-09-17.md` §4.

**Acceptance (to be sharpened by the research):** a message to an agent →
turn 1's history carries "you have not replied to message X; reply with
(my.message/reply {...})"; after the agent writes the reply entity, the next
status says so and continuation stops; the agent page shows the comments as
thinking and the results with their HTML renders; budget exhaustion without
a reply is a typed outcome plus one root message, exactly as for issues.

### S11 — Composable history: select whole units, delete the re-fit (F3)

**Grounding.** `docs/prds/steward-platform/research/composable-history-2026-09-17.md`.
The prompt already renders one unit per evaluation through the evaluation
schema's AI pair (`seon.render.walk/history`, `src/seon/render/walk.clj:891`;
`history-segments`, `src/seon/render/web.clj:2381`). The defect is a second
clipping spot: `seon.render.transcript/bounded-scalar` / `floor-text`
(`src/seon/render/transcript.clj:412-435`) re-admits an already-rendered AI
string as a scalar node and re-fits it with `seon.print/fit-text` — the
batch-70 bytes match its fields exactly. The prompt budget only reports a
verdict (`seon.ai.tokens/budget-report`).

**Change (research Option A, no schema change, no reset).**
1. Each history unit carries `:seon.ai.tokens/estimate`, DERIVED at
   composition from its stored shown text (never stored: calibration
   drifts).
2. Pure `select`: given the units in turn order and the prompt budget,
   keep whole units newest-first until the budget is spent, oldest dropped
   first, and emit ONE elision value naming the dropped count, the oldest
   surviving ordinal and the requery form. Pure `compose`: the kept units
   joined. `acquire-context-report` (`src/seon/cluster/prompt.clj`) calls
   them; `budget-report` stays as the verdict on the composed result.
3. DELETE the transcript re-fit (`bounded-scalar`/`floor-text` on rendered
   units); the value renderer already bounded each shown text at evaluation
   time, which is the one clipping spot.
4. `;;` comments (`:seon.cluster.eval/comment`, stored separately) render as
   their own "thinking" block in the HTML pair; the AI pair keeps them in the
   REPL grammar as today.

**Acceptance.** (a) with a budget smaller than the history, the prompt
contains whole evaluations only, the newest ones, and one elision value —
never a mid-form cut (the batch-70 assertion class goes green); (b) with a
budget larger than the history, the prompt is byte-identical to today's
composition; (c) the agent page shows a comment as a thinking block and a
result through its HTML pair; (d) no call to `fit-text` remains on the
history path (assert by reach: `tests-reaching`/`calls` from
`acquire-context-report` do not reach `seon.print/fit-text`).

**Boundary.** In-process on `default`; cold gate on `seon.cluster.prompt-test
seon.render.transcript-run-test seon.concurrency-independence-test
seon.render.web-debug-test`.

### S6 — The identity list derives from the declarations (I6; open issue)

`seon.program/identity-attributes` is a literal vector while
`authored-shapes` derives from the schema resources on disk; one lane's
uncommitted rename of an identity key refused every in-process test run on
`default` for an hour
(`docs/seon/issues/live-resources-outrun-the-loaded-program-identity-list.md`).
An identity attribute is one declaring `:seon.program/row-schema`; derive the
list from the same declaration forms and keep the admission order as an
explicit `:seon.program/admission-order` property on those declarations so the
order is also a fact. **Acceptance:** renaming an identity key in the resource
alone yields a typed refusal naming both the resource and the loaded list, or
the list follows automatically; the S1 diff regression passes.

---

### S4 is deferred (owner, 2026-09-17): groundwork only

The owner does not need experimental database branches now and wants the
feature later. No slice implements S4a or S4b yet. Every other slice keeps
the groundwork so S4 remains a bounded addition later, not a refactor:

- The agent's connection is carried as a **value** on its scoped environment
  and bound through the one custody scope (`seon.db/call-with-custody`);
  nothing new reads "the" cluster connection from a global.
- Every write from an agent's turn carries the agent and turn in `tx-meta`
  (provenance the replay in S4b will need); S1 and S5 must not drop it.
- Queries that answer "the program" take the database value they are handed
  and never assume the main branch (§2.1); the override query in S3 and the
  write-back in S5 are written against a `db` argument.
- Nothing introduces a per-cluster singleton keyed by cluster name where a
  connection or database value would do.

---


### S12 — REPL-native refactoring vocabulary (owner, 2026-09-16 ~01:20Z)

Owner, verbatim: "Agents can actually rewrite functions by just redefining
them, same with schema changes and overwriting tests. We do need ways to
retract them that are REPL friendly. ... Everything should be able to be
done within a repl env that the agent is controlling." and "we need to come
up with a full vocab and code them up so they work perfectly with our
system. properly rejecting problems and suggesting solutions and even
returning refactoring plans we can just launch."

Owner correction (~01:35Z): "even if clojure has native versions we need
our own so we can update the database. keep that in mind." Every operation
is OUR function whose act is the database write; a native SCI/Clojure form
is at most what it performs inside the agent's context, never the entry
point; a native form that would bypass the facts is forbidden or wrapped.

Slice: the full vocabulary of program operations an agent performs from its
REPL, using Clojure's names where they fit (`defn` re-evaluation,
`ns-unmap`, `remove-ns`, a re-evaluated `deftest`) and one `my.*` function
per operation Clojure does not name (rename, move, change-contract, breaks,
who-calls, tests-reaching, revert an override). Every operation returns
data: the changed entity, or a flat refusal carrying the affected set AND a
refactoring plan in exactly the shape `seon.issue/start!` launches (one
issue per affected caller or namespace, identity-deduplicated, each with the
tests that must stay green), so the retraction applies when the plan's
tests are green. Spec: [repl-native-retraction-and-refactoring-2026-09-16.md](../research/repl-native-retraction-and-refactoring-2026-09-16.md)
(research running); implementation on astra after the deletion contract
(tier 2) lands; the vocabulary rows land in AGENTS.md in the same commit as
the functions.

## 4b. Lane rules for every slice in this document (owner, 2026-09-17)

These are in addition to AGENTS.md §0–§10 and §7's launching rules.

1. **Read the integration seams' source end to end before designing**, and
   say so in the landing note with the paths: for S1 the analyzer
   (`src/seon/fn/analyzer.clj`), the indexer's source-string path
   (`src/seon/fn.clj:700-760`), `seon.program/canonical-row` and
   `declaration-row`, the SCI reader's namespace handling
   (`src/seon/sci/reader.cljc`), and clj-kondo's analysis output shape in
   `reference-code/clj-kondo` for the keys it consumes; for S3 SCI's
   `copy-var*`, `fork` and `intern` in `reference-code/sci/src/sci/core.cljc`
   and `build-base-ctx`; for S5 `seon.edit`, `refresh-source!` and the hook.
   A lane that has not read the seam does not edit it.
2. **Never build a testing harness.** The canonical fixture is
   `seon.test-support/with-database`; program rows come from
   `program-fn-row`; config from `apply-config!`; every fixture write through
   `transacted!`; awaits bounded by `event-backstop-seconds`. A new fixture
   function is admissible only when the landing note shows the existing one
   cannot express the case, and it lands in `test/seon/test_support.clj`
   with a docstring, never in a test namespace.
3. **Do everything through the existing owners.** Analysis through
   `seon.fn.analyzer/analyze`; rows through `seon.program`; writes through
   `seon.db/transact!`; adoption through `seon.cluster/refresh-source!`;
   edits through `seon.edit`. A second path for any of these is refused at
   review.
4. **The orchestrator personally reviews every slice's diff before it
   gates.** The lane reports the commit; the orchestrator reads the full
   diff, the landing note and the regression, writes a short review note
   under `docs/prds/steward-platform/research/` naming what it checked and
   what it rejected, and only then requests the gate. A slice is not landed
   until that note exists.
5. **Report the boundary honestly**: in-process proofs are named as such;
   the cold gate is the proof of record; RESET NEEDED is recorded when a
   key's meaning changes.

## 5. Sequencing and cost

| order | slice | why here | estimate |
|---|---|---|---|
| 1 | S1 analysis on both seams | everything else reads the facts it makes exact | 1 lane-day |
| 2 | S2 required derivables | flushes any remaining hand-built producer; needs a reset boundary | ½ day + reset |
| 3 | S6 derived identity list | closes the one-lane-refuses-everyone class before more lanes touch schemas | ½ day |
| 4 | S3 acquisition by provenance | makes R4 real; depends on S1 so overrides carry edges | 1 day |
| 5 | S4a fork/discard | cheap; unblocks experimental work | 1 day |
| 6 | S5 write-back gated | closes the JVM gap; depends on S1, S3 | 2–3 days |
| 7 | S4b merge by replay | DEFERRED with S4a | — |
| 2b | S7 issue task loop | the first structured task (render pairs) needs it; independent of S1 | 1–2 days |
| 2c | decision 9, no render fallback | the render-pair task needs every uncurated attribute visible | ½ day |
| 3b | S8 root runs the collector | eight resets a day is not a substrate; dry run first | 1 day + dry run |
| 4b | S9 database runner is the one gate | the admittance bar (C1) and the merge gate (C2) run on it | 3–4 days, design first |

S1 goes to one astra lane (design-sensitive); S2, S6 to Opus; S3 to astra;
S4a Opus; S5 and S4b astra with design review at `high` effort. No slice
starts before the owner rules §6.

---

## 6. Owner rulings still needed for this document

1. **The write-back gate (S5).** Default proposed: tests reaching every
   changed identity green in a candidate context, plus the platform tier.
   Add anything? (An issue's cited tests when the agent works an issue? A
   human approval step before the `git commit`?)
2. **Which first-party namespaces may agents override at all (S3).** Default
   proposed: any, with interop failures surfacing as typed evaluation errors.
   If you want a declared exclusion, it is a schema property on the namespace
   entity, never a name list.
3. **Merge collision policy (S4b).** Default proposed: refuse by name and
   require a rebase; never last-writer-wins on a merge.
4. **Where an agent on an experimental branch is addressable (S4a).**
   Default proposed: messages on main are readable from the branch and
   unanswered until merge or discard.

---

## 7. What is deliberately not in this document

- The hook's own coalescing, timeouts, and the adoption retry
  (`src/seon/cluster.clj:2042`) are unchanged; they remain the file→facts
  path for human edits.
- The issue system's opening, detectors and scope are decisions 2, 4 and 5 of
  [owner-decisions-2026-09-17.md](owner-decisions-2026-09-17.md); they
  consume these facts and do not change them.
- Store reclamation (decision 3 there) is independent, except that S4a's
  branches must be visible to the collector's roster, which they are by
  construction.
