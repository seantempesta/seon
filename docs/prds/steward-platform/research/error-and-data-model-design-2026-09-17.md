---
type: research
status: proposed
created: 2026-09-17
tags: [error-model, data-model, contracts, schema, private-functions]
---

# Errors, data shapes, and the contract campaign

**Recommendation: make error identity unambiguous, make diagnostic evidence
queryable, and derive read shapes before contracting their consumers.** Keep
the existing error family, predicate, disposition helper, instrumentation
owner, and final-report validator. A failed operation must never supply the
success value that the rest of an operation needs. Production collection
does not permit the failed computation to continue.

This is a design proposal, not a claim of implementation. The immediate
predicate and writer/turn repairs **do not wait for this proposal**. The
[schedule](../plan/README.md#the-schedule-from-2026-09-17-1950z-new-orchestrator-supersedes-today-above)
owns scheduling; §5 below supplies schema prerequisites and contract order,
not another competing wave schedule. §4 specifies exactly which parts of
those repairs survive this design and which later change.

## 1. The data model first

### 1.1 One error family; one class identity

Today there are two independent ways to recognize a failure. A class schema
requires a domain marker, sometimes a boolean and sometimes a meaningful
value; many constructors also write a different keyword into
`:seon.error/kind`. `seon.error/error?` matches declared classes, whereas
the private predicate at `src/seon/db.clj:160` requires kind and message.
`error-marker` at `src/seon/error.clj:1602` selects a required attribute from
the first matching class. With several required attributes, that is not a
sound way to identify the classification attribute.

The declaration census for this study found **338 top-level class declarations
in 65 resources**. Ten resources contain a literal `:seon.error/kind`
reference somewhere; only two contain it inside a marked class form. Those
are different measurements: ten files is not ten canonical classes. The
complete dated resource inventory and reproducible reader form are in §3.4.

**Proposed rule:** a class declaration's registry key is its canonical kind.
For example, the class declared at `:seon.config/refused-error` emits
`:seon.error/kind :seon.config/refused-error`. Declaration expansion derives
the equality constraint from that key. No authored second spelling, suffix
transformation, global enum roster, or boolean marker participates in
recognition. `:seon.error/class true` remains metadata on the declaration;
it is not another field on every error value.

The kind is an **observed classification token**, stored as a keyword value,
not a ref to a living schema entity. An old fault remains meaningful when
its declaring schema is retracted. Its declaration and source at the fault's
recorded program basis remain history queries. This is the same statement
versus observation distinction that makes function names in fault evidence
symbols, not refs. It does not introduce an entity-kind dispatch system:
fault identity is still its error signature, with occurrences owned by that
error. The explicit error-class requirement in PRD §1l is the reason for this
classification attribute.

There is a real choice about the declaration-to-value spelling:

| Option | Guarantee and benefit | Cost and what we give up |
|---|---|---|
| **Use the class registry key as kind — recommended** | Exactly one spelling, derived without a lookup convention; class facts join directly to observed kinds. | One coordinated constructor/declaration/renderer change across the class population. Existing `*-error` keys become visible kinds; existing marker-only fixtures change. |
| Declare a canonical kind property on each class | Existing shorter kinds can survive; the declaration checker enforces a unique mapping. | Adds a second named identity and mapping to every class. We give up deriving the spelling from the class's existing identity. |
| Make the existing domain marker the only classification | Most marker constructors remain unchanged; recognition stays shape-based. | Every later handler must resolve marker attributes through declarations, including payload-bearing markers and overlap. We give up the direct kind join requested by §1l. |

These are proposals for owner choice, not permission to change the 338
declarations in this study. The first option is the simplest final model.

**Migration of declarations:** derive each marked form's canonical kind in
the existing schema population compiler. In the same publication, update
its constructor, generator, renderer, and consumers. Remove a marker whose
only meaning is “this is this class.” Preserve a marker's substantive value
under its existing semantic attribute or an accurately named member/rule
attribute: for example, the refused configuration rule and the function
whose contract failed must not disappear with their markers. A kind-only
constructor with no class gets an actual class declaration. Two declarations
for the same diagnosis merge at their existing owner; distinct diagnoses
remain distinct even if their fields happen to match. Do not merge classes
merely because both contain a keyword and a message.

The implementation lane's predicate continues recognizing **today's declared
marker classes** until that publication. It must not start requiring the
future kind early. After the publication, the same predicate derives its
recognition from the new declarations. There is no permanent compatibility
predicate and no additional per-namespace recognition branch.

Recognition must also work while the declaration store is unavailable. The
same owner uses the already acquired projection when present and the small
common error grammar when it is not; it must not query the database to
recognize a database failure. Family recognition and validation of a known
class are separate internal questions. An unknown class token is preserved
as evidence of an unclassified diagnostic, never accepted as a successful
row and never repaired by guessing a namespace prefix. Bootstrap's common
grammar is derived from the same declared error value, not a second roster
of the 338 classes.

### 1.2 What every error says, and what a fault records

An error value is the operation's answer. A fault occurrence records a
trusted boundary's observation of that answer. They share one diagnostic
vocabulary; a fault is not a second taxonomy and an exception is only a
transport carrying the same flat value as `ex-data`.

`diagnostic` already accepts layer, operation, member, expected, offending,
cause, and evidence (`src/seon/error.clj:311`). It currently places much of
this under `:seon.error/data`, whose schema is bare `:map`
(`resources/seon/schemas/seon.error.edn:211`). That is useful display data
but insufficient query structure. The proposal promotes the stable query
dimensions to declared attributes on recorded diagnostics, retaining domain
detail as explicitly admitted evidence. Do not parse `data-edn` to triage.

| Information | Value contract and recorded representation | Why it exists |
|---|---|---|
| Kind and message | Canonical class keyword and nonempty message. Class membership derives from the declaration; no second class field. | Direct classification and useful human explanation. |
| Layer | `:seon.error/diagnostic-layer`, qualified keyword naming the responsible seam; unknown is explicit. | Group database-read, database-write, instrumentation, acquisition, effect, render and other declared boundaries without guessing from names. |
| Operation | `:seon.error/diagnostic-operation`, qualified function symbol where known; explicit unknown otherwise. | Join to current program facts; retain the symbol when the function is gone. |
| Member and path | `:seon.error/diagnostic-member`, the declared attribute/argument identity; add a typed diagnostic path for the exact failed position. Preserve Malli's schema path separately from the value path. | A failing leaf is different from its enclosing request. No string path parser. |
| Expected | Retain `:seon.instrument/expected` as its existing rendered string. Record a schema key or canonical shape identity plus the observed declaration/program digest where available; retain inline Malli forms as declared evidence for anonymous shapes. | The current declaration may differ from the contract that failed. Do not retype the existing string attribute. |
| Offending value | The actual object in the transient diagnostic; the fault retains the independently bounded projection of its own leaf at `:seon.instrument/actual`, its `actual-size`, and the path. Non-instrumentation diagnostics use the same evidence projection semantics. | A tiny bad value must survive a huge environment/request. Do not derive it from an already bounded whole-request string. |
| Fix | Existing `:seon.error/fix` string for an actionable remedy; declare the diagnostic-level remedy or an explicit “no remedy known” observation. Requery/repair identities are structured evidence, not prose to execute. | Human advice can improve without changing class identity. Unknown must not be invented advice. |
| Evidence | A declared diagnostic evidence map: known facts, basis transaction, relevant identity values, expected/actual observation, optional bounded blob digest, and explicit unavailable reason. Domain classes declare their extra members. | Future handling can distinguish a negative result from an observation that never succeeded. |
| Cause | Preserve the original error, or its durable evidence identity, when adding boundary context. No replacement of a known read refusal with “missing row” or “unknown transaction.” | The actionable cause survives propagation. |
| Critical/ordinary | A bounded disposition value on the **boundary observation**, with its declared reason and boundary operation. The helper consumes this and the existing dial. | A class is a diagnosis; severity depends on which guarantee failed. |
| Provenance | Actual recording transaction carries resolvable `:seon.db/user` and `:seon.db/process`. The log carries their identity values until recording is possible. Observed origin and recording actor are distinct on replay. | Do not copy transaction provenance onto every domain row or claim a later replay transaction happened at the original failure instant. |

No stored nil. “Known nil,” “known false,” “unavailable,” and “over the
evidence bound” are four different observations. The transient offending
value may legitimately be nil because this is a genuinely polymorphic
boundary. Its stored projection can say `nil`; the absence of a datom must
not be used to mean that observation. A missing required diagnostic field
is a constructor defect; an explicitly unknown field names why it is unknown.
Boot before store acquisition is a legitimate reason to lack a basis, not
a reason to fabricate one.

The independent leaf projection is already implemented by `e08749712`:
`src/seon/error.clj:495` extracts the offending entry before bounding the
source; `:528` fits fact payloads; `:562` prepares evidence.
`resources/seon/schemas/seon.instrument.edn:12` declares `actual`, and
`:18` declares its byte size. **Build on this.** Add path and queryable
expectation identity; do not replace it with a second evidence printer.
The signature groups a failure class/site (`src/seon/error.clj:294`); message,
process, agent, and arbitrary offending values must not turn every repetition
into a new signature.

The concrete stored proposal uses `:seon.error/disposition` as an enum
`:critical | :ordinary`, `:seon.error/expected-schema` as a qualified-keyword
observation when an expectation has a registered identity, and the existing
diagnostic layer/operation/member names as query attributes. Known operations
are qualified symbols. Keep missing-information reasons in a declared
observation shape; do not store nil or a stringified symbol to satisfy a
scalar attribute. An ordered diagnostic path is a value, **never a
cardinality-many vector whose order Datahike would discard**. Use the
existing canonical EDN codec for a genuinely mixed path/evidence grammar,
with declared literal arms. The proposal needs only the whole ordered path
and the separately queryable member; it does not add path-segment entities.
Shape/digest and blob identities reuse existing registry declarations.
Unknown expectation is explicit evidence, not a fabricated schema key.

For example, after these proposed attributes land, critical recorded
observations with their actual recording provenance are an ordinary query:

```clojure
[:find ?fault ?kind ?operation ?member ?user ?process
 :where
 [?fault :seon.error/kind ?kind]
 [?fault :seon.error/occurrences ?occurrence]
 [?occurrence :seon.error/disposition :critical]
 [?occurrence :seon.error.occurrence/count ?count ?recording-tx]
 [(get-else $ ?occurrence :seon.error/diagnostic-operation
            :seon.error/unknown) ?operation]
 [(get-else $ ?occurrence :seon.error/diagnostic-member
            :seon.error/unknown) ?member]
 [?recording-tx :seon.db/user ?user]
 [?recording-tx :seon.db/process ?process]]
```

This is a proposed-schema query, not one run against today's database. Its
defaults retain unattributed faults instead of dropping them through an
optional join. A replay event's originating actor remains separately named
evidence; this query deliberately asks who recorded it. A query needing
every historical repetition uses the transaction history, since current
occurrences aggregate observations.

### 1.3 Critical is a failed system guarantee, not “an error map exists”

The helper must receive trustworthy context from the owning boundary. A
user-supplied `ordinary` flag cannot turn a writer failure into success.
Neither namespace prefixes nor exception text identify responsibility.
Function admission provenance, the entry boundary, and the operation's
declared guarantee provide that context. Agent-authored code can call core
functions; callability is not permission to treat a core output defect as an
agent input mistake.

| Boundary/event | Classification | Development `:panic` | Production `:record` |
|---|---|---|---|
| An agent supplies an invalid form, wrong public argument, unknown subject, or a proposal that violates an ordinary domain rule | Ordinary mistake, provided the boundary positively identifies the authored input and performs no invalid action | Return the flat error to the agent; no system panic | Same value; ordinary evaluation/error history, without manufacturing a core fault |
| Core invokes a function with a bad argument, returns a value outside its contract, or violates its guard | Critical contract fault | Throw with the same flat diagnostic at the wrapper; stop the affected computation | Record through the existing committer and return the error; body is not run on input refusal and output is not consumed |
| A core consumer receives a refused database read, including a read inside a writer callback | Critical observation failure | Throw at the shared helper before truthiness, destructuring, mapping, arithmetic or another query | Preserve the read error, record through the database-error log, and terminate this operation with that value |
| An admitted write is refused by database validation, dependency/store failure, or an invalid internal settlement | Critical write fault; known rejection remains known rejection | Durable database-error evidence, then panic; no continuation pretending it committed | Durable log, flat refusal, no successful terminal fact |
| The transaction outcome cannot be established | Critical uncertainty | Durable log, then panic; never retry the original mutation blindly | Return explicit unknown outcome; reconcile from authoritative facts before any new mutation |
| A transaction function rejects an ordinary domain proposal | Ordinary refusal with rollback transport | Throw internally to abort the transaction; recover exactly that ordinary value at the owning boundary | Same rollback and value; never commit the error map as transaction data |
| A critical error reaches SCI, an effect catch, a renderer catch, or a flow boundary | Preserve its already established classification | Do not convert the panic into an ordinary evaluation result or relabel it | Preserve the cause; do not create one new fault per stack frame |

The distinction between an **ordinary rejected proposal** and a **failed
admitted write** is essential. A user requesting deletion with live callers
gets the complete repair refusal; a core settlement constructing invalid
transaction data has broken a system guarantee. A known rejection never
becomes “outcome unknown” merely because it used exception transport.
If the boundary cannot prove ordinary authorship/domain refusal, treat the
system's failed guarantee as critical, with the missing attribution explicit.

This applies PRD §1k over §1j's earlier universal “no throwing.” It also
corrects triage regression prose saying “no throw” without specifying the
dial: no incidental `ClassCastException`, yes the original flat error in a
development panic; production returns the original error. Converting
`config/refuse!` into a value constructor remains correct. Its callers must
then short-circuit or use the shared helper; simply removing `throw` would
continue the invalid configuration operation.

“System crash” still needs a scope decision. An exception inside a flow proc
is not proof that a JVM stopped; the dead-proc issue demonstrates that gap.
No low-level predicate or database callback should call `System/exit`.

| Option | Guarantee and benefit | Cost and what we give up |
|---|---|---|
| **Panic through the existing fatal supervision boundary — recommended** | The failing computation stops immediately; development fault handling makes the owning running system visibly failed. Process-wide store corruption reaches the process owner. | Wire and prove the existing supervision path once. Give up pretending every local throw already proves JVM termination; a multi-cluster process needs explicit scope. |
| Terminate the whole development JVM for every critical fault | The owner's strongest literal crash behavior is immediate and easy to observe. | Other clusters and the live REPL in that JVM stop too; requires evidence flush before termination and sacrifices live diagnosis. |
| Stop only the affected agent/cluster graph and retain the JVM | Preserves the REPL and independent clusters. | Requires a durable failed state and positive visibility at every status surface; gives up process termination and risks repeating today's silent-proc failure unless supervision is proved. |

The existing `:panic | :record` dial remains the only mode. This choice is
about the owner reached by panic, not a new severity setting per subsystem.

### 1.4 Agent rendering and fault storage are different projections

An ordinary error renders through the error schema's AI/HTML pair. AI
presentation elides only in that pair or the value renderer under the supplied
render profile; the evaluation saves exactly that shown text. History does
not clip it again. HTML has no presentation clipping. A critical fault shown
to an agent names the fault and its available evidence without disguising it
as a mistake the agent necessarily caused.

Fault evidence admission is the existing bounded external/durable projection
(`bounded-admission`, `src/seon/error.clj:392`), not another presentation
budget or a serialized evaluation-result mechanism. Preserve classification,
operation, path, and leaf evidence before admitting larger context. If evidence
cannot fit, store an explicit unavailable/over-bound observation with the
bound that fired. The B6 supervision repair must never cap away the key that
explains why the proc died. The AI renderer may present this evidence briefly;
the log and database retain its declared representation. No new `fit` call
belongs in a consumer, transaction request, or HTML terminal.

### 1.5 Durable database-error logging and replay

The existing recorder is the owner: `seon.error/normalize`, `recording`, and
`commit-call` (`src/seon/error.clj:666`, `:1477`, `:1400`). Its current
occurrence identity groups signature plus turn/agent, or process; each call
increments the count. **Upserting the same signature is not idempotent
replay.** Replaying after a commit whose acknowledgement was lost increments
recurrence and can send duplicate notifications.

Keep a bounded append record under the existing operator process root's
`logs/`, carrying the same normalized diagnostic plus an event identity,
observed instant, source branch/store identity, and provenance identity values.
Use the existing identity owner to mint an event once; never mint it on replay.
The append must have a durable completion before a database-fault panic is
considered recorded. An unavailable log is itself a critical reporting failure,
reported directly to the operator/stderr without calling the failed database
or recursively invoking the ordinary fault recorder. Do not claim durability
if both storage surfaces failed.

There is one material storage choice:

| Option | Guarantee and benefit | Cost and what we give up |
|---|---|---|
| **Record an imported event identity on the recording transaction — recommended** | At the serial writer, check this event identity and atomically add the fault/count/notifications and its recording datom once. Duplicate replay emits no second occurrence increment. Uses existing faults and transaction provenance; no second fault entity family. | Add one indexed event-token attribute and one atomic replay admission branch. Initially import one event per transaction. Give up bulk replay until measured need. |
| Store each observed event as a component of the existing fault occurrence | Every event has independently queryable original evidence; aggregate counts can derive from events. | More retained entities and a larger change to the current grouped occurrence writer. Give up the smallest compatible extension. |
| Append and replay at least once, accepting duplicate occurrence counts | Minimal recorder changes; evidence eventually arrives. | Counts and recurrence notifications become inaccurate after a crash. This does not satisfy reliable later handling and is not recommended. |

For the recommendation, the proposed attribute is
`:seon.error/recorded-event-id`, a value token on the **recording transaction**,
not a second error identity and not a ref to the original failed transaction.
The check and marker assertion execute in the writer's transaction function,
not in a log reader pre-read. Its index is derived from datoms. No in-memory
“seen event” set is authoritative. A log cursor is only an optimization:
restarting from the beginning remains correct.

Listen for a successful transaction before deriving pending work. The
existing fault-committer IO path imports a bounded batch after that event,
outside the writer's callback; it never recursively transacts within validation
or a database exception handler. A replay transaction's recorded-event marker
lets that event be recognized without a new boolean. A replay failure leaves
the original event pending and does not append infinite copies of itself.
Next healthy transaction or boot recovery retries the **fault import**, never
the failed application mutation. Partial final records, checksums, segment
rotation, acknowledgement loss, and old log formats require explicit replay
outcomes. Rotation deletes only acknowledged data; reset never silently
imports an old store's faults into an unrelated new branch.

At replay, transaction user/process identifies the actor actually recording.
Original user/process identity values and original observed instant remain
event evidence. These are not duplicate provenance: they answer different
questions. A target that no longer exists must not be re-created merely to
hang a historical fault ref from it. Error occurrence agent/turn/function
observations should use identity values when they must outlive their target;
actual ownership refs and blob components retain their declared semantics.

## 2. Shapes before contracts

### 2.1 Entity, transaction, and pull are three grammars

An entity schema describes the stored entity. Transaction input also admits
tempids, lookup refs, and operations. Pull returns selected keys, decoded
values, ref maps, component expansions, aliases, and defaults. Substituting
`:seon.turn/turn` for every pulled turn is therefore not a repair.

Use the existing schema/projection owners to derive a pulled contract from
**entity schema + selector + that database's projection**. This is the
[research B](entity-schema-vs-pulled-shape-2026-09-16.md) result. A source
declaration records those inputs; its compiled shape rides that projection
and is rebuilt when those definitions change. Do not hand-author a second
`pulled-turn` or widen every ref to `[:map [:db/id :int]]`.

For a known turn consumer the success grammar is:

```text
current turn read: nil | Pulled(:seon.turn/turn, declared selector)
consumer input:   Pulled(:seon.turn/turn, selector including turn identity)
exceptional arm:  the common error protocol, implicit at both boundaries
```

`Pulled(...)` here is **design notation**, not a registered key or a proposed
new API spelling. The campaign must first implement this derivation through
the existing schema form owner and contract acquisition. Its schema
declaration must be data, not an opaque predicate that queries a live database
on every call. Dynamic selectors need a declared pull request/result boundary
and runtime-derived shape; fixed consumers use fixed selectors. Unsupported
selector grammar returns a named refusal rather than quietly `:map`.

The physical read result remains `nil | row | error`. Nil means a successful
read found no entity; it never means unavailable. A required-row consumer
does not accept nil. A predicate like `open?` requires a turn identity even
if `closed-tx` is optional: the audit's all-optional map accepted an error as
an open turn. Open maps still permit extra attributes; they require their
declared information. Error values must be intercepted before ordinary
success-shape validation, because an open map could contain both error keys
and all the required row keys.

Malli output declarations describe successful values. PRD §1j supplies the
exceptional arm centrally; do not append `:seon.error/value` to every new
contract, and do not remove existing explicit unions in a separate cosmetic
sweep. Error constructors and readers intentionally inspecting errors declare
those inputs explicitly. Arbitrary user data can contain an error-shaped
map: traversal of an opaque user value is not failure propagation. Recognition
at the read/contract seam applies to the operation result, not every nested
map in a user's collection.

Pull derivation must cover aliases, defaults, forward/reverse cardinality,
component expansion, optional absence, and `:db/ident` on unexpanded refs.
Bounded recursion and selector limits must either be supported honestly or
refused. Datahike defaults cardinality-many pull to 1,000
(`reference-code/datahike/src/datahike/pull_api.cljc:16`, `:313`). A valid
shape does not prove a complete result. Complete validation, deletion and
gate selection use complete index traversal; bounded display reads carry
an explicit cut. Never assert “no callers” from a capped pull.

### 2.2 Bare maps, opaque values, and duplicated shapes

Ban bare `:map`, `[:vector :map]`, `[:sequential :map]`, and unqualified
`:any` success outputs where the producer knows the shape. A named alias to
`:map` does not improve it. Also inspect nested maps such as transaction
report `:tempids` and `:tx-meta`. The dated 69 public outputs and the private
candidates require the same review; privacy changes no modeling rule.

Do not replace genuine polymorphism with a false closed model.
`:seon.schema/value` is an explicitly exempted arbitrary in-memory value;
`:seon.sci.admit/value` is an external-evidence boundary. Map entries/forms
use the declared Malli/reader grammar; callbacks use a callable contract;
Java contexts, threads, sinks and Lucene documents use their actual admitted
host shape. A schema generator is not evidence that a shape is correct.

The corrections below are particularly important before bulk edits:

| Audit candidate or claim | Design decision |
|---|---|
| Missing `seon.env` refusal schemas | **Refuted.** Both classes were present before the audit; constructors carry their markers (`resources/seon/schemas/seon.env.edn:88`, `:94`; `src/seon/env.clj:202`, `:209`). Contract the existing shapes. Later apply the common class migration, not two new declarations. |
| Seven private error predicates | **Nine**, including instrument's `flat-error-value?` and schedule's `flat-error?`. Consolidation belongs to the active predicate lane, onto existing `seon.error/error?`. |
| Function names are strings and namespace names symbols | **Fixed by reset.** Functions/tests declare qualified symbols; namespaces symbols; calls/references/reach indexed sets of qualified symbols. No string/symbol compatibility union. The query-codec fixes are `fdf376b7a`, `a0b8c1ed0`, `925affcd3`. |
| `:seon.schema/projection-row` for `render.ns/schema-row` | Wrong existing shape: it is a tuple, not the pulled schema entity. Derive from the schema entity and selector. |
| `:seon.db/pull-options` for `schema-row`'s bounds argument | Also wrong: pull-options requires selector and eid; this helper supplies those itself. Use the actual bounds subset derived from the same request declaration, not a duplicate full pull request. |
| A value schema for `pull-call`'s operation argument | It is a callable dependency operation, not arbitrary data. Use the actual callable contract; the selector/result grammar remains separately derived. |
| `:seon.config/settings` for effective configuration | Wrong domain: settings is the sparse agent overlay. `:seon.config/effective` and `entity` are **generated** composites (`src/seon/schema/edn.clj:67`); absence from the raw resource is not missing registration. |
| New transaction-report schema | Already registered in `seon.db.edn`; `:seon.db/tx-data` aliases `:seon.store/transaction-data`. Improve the existing owner; do not duplicate either. |
| `:seon.db/db` versus `:seon.db/database-value` | Registered alias, not two meanings. No rename campaign. |
| `:seon.instrument.lookup/result` has three states | It is missing, but the source has **four**: found, missing, failed, no-program-graph (`src/seon/instrument.clj:167`). Declare all real states, with state-dependent evidence. Failed must retain its cause, not just status. |
| Kernel guard and admission frame/node candidate keys already exist | `:seon.sci.kernel/arm` exists; the proposed `guard` does not. `:seon.sci.admit/frame` and `/node` are not registered. Use the actual host guard shape and existing print-node/admission output; do not invent a serialized evaluation-result family. |
| Unindexed operator functions cannot be armed | Index coverage is missing, but host collection walks loaded `ns-interns`. Distinguish host arming from program-graph call/reach/contract coverage; fix both measures positively. |
| Every throw in the audits must become a returned value | Superseded by §1k. Constructors return values; transaction callbacks may throw to roll back; critical development failures must panic with those values. |
| `cluster/ref-identity` and `effect/ref-attribute` | Same read/identity operation in two owners. Reuse one existing database projection operation and derive its return from the selected attribute. Do not declare two nominally different generic-map schemas. |
| `error/fact`, occurrence, rendered latest fault, and flat diagnostic | Different projections of one family, not interchangeable stored entities. Derive the pulled/latest view; root class/site data stays on the root, occurrence evidence on occurrences, provenance on transactions. |

The raw-resource scan alone cannot decide registration: generated composites
and aliases must be resolved through the merged registry. Conversely a key
merely mentioned in a candidate contract is not a declaration. The table
distinguishes both cases.

## 3. Proposed schema edit list

All paths in the next tables are under `resources/seon/schemas/` unless
stated otherwise. Names explicitly called **proposed** are design names,
subject to the merged-registry check at implementation. The schema and its
loaded constructors/consumers publish together. “Before contracts” means
before arming new consumer contracts against the shape; it never means
publishing an incompatible resource alone. Stored type changes require the
orchestrator's reset boundary, not a migration shim.

### 3.1 Shared prerequisites and durable evidence

| Resource | Proposed edit and owning consumers | Required proof / publication boundary |
|---|---|---|
| `seon.error.edn` | Canonical class expansion; declared common diagnostic shape; typed layer/operation/member/path/expectation/evidence/remedy; preserve arbitrary raw offending input only at the explicit value boundary. Add proposed `recorded-event-id` on recording transactions and log-record contract. Replace broad `data` uses with class-declared evidence where known; preserve domain extras. | Class constructors, diagnostic, normalize, recorder and renderers in one publication. Required-field strengthening is breakage, not cosmetic accretion. Kind/schema correspondence and fault queries survive bounded evidence. |
| `seon.error.occurrence.edn` | Declare the new diagnostic dimensions retained per observation; preserve `actual` and size. Separate observed origin evidence from recording transaction provenance. Review historical agent/turn/proc references by the modeling guide; identity values for observations that must survive deletion, component/ref for actual ownership. | Existing grouped occurrence identity/count behavior unchanged by the minimal replay design. Replay dedup is atomic with count/notifications. No invented identity on ordinary component children. |
| `seon.instrument.edn` | Add typed value/schema paths and expectation identity without retyping existing rendered strings. Cover guard violations in the arm/result grammar. The contract input/output/guard evidence is one family. | Both JVM and interpreted wrappers keep the original failure and independently projected leaf. |
| `seon.instrument.lookup.edn` **proposed** | Register `result`: found requires the appropriate arglist evidence; missing is a successful negative lookup; failed requires failure evidence; no-program-graph explicitly describes unavailable custody. Reuse registered arglist forms rather than stringifying host arglists. | No status-only loss of a refused database read; audit 3's three-state candidate corrected. |
| `seon.schema.edn` | Declare entity+selector pull-shape request/derived contract data using existing schema/form machinery. Preserve `projection`, `projection/forms`, `projection-row` meanings. Tighten named diagnostic/analysis shapes that currently stand for known maps. | Research B selector matrix, temporal projection custody, typed unsupported selector and explicit result cuts. |
| `seon.schema.shape.edn`, `seon.schema.shape.entry.edn`, `seon.schema.shape.child.edn` | Reuse canonical shared shape facts for contract expectation identity and nested paths. Do not revive deleted AST facts or duplicate Malli forms as another graph. | The expectation can be read at the fault's observed basis; a later schema edit does not rewrite old evidence. |
| `seon.db.edn` | Keep `database-value`/`db` alias; use existing `pull-options`, selector, ref and eid grammars. Name internal declaration/decoder state and query/pull operation results; improve `transaction-report` tempid and metadata maps; use the existing `read-operation` grammar for replay. | Refused declarations are never a decoder; unknown replay operation names the member; report and elided transaction-summary arities stay distinct. |
| `seon.store.edn` | Reuse `transaction-data` and its operation union for all tx builders. Tighten any known nested request/metadata maps; retain dependency vocabulary at the Datahike crossing. | Expanded transaction functions and raw operations pass the same final report validator. No `[:vector :any]` masquerading as tx-data. |
| `seon.config.edn` | Reuse generated effective/entity composites and settings overlay; make `refused-error` constructors complete, including message and rule. Existing dial remains unchanged. Diagnostic context receives the resolved dial, never queries the failed database for it. | Config compiler remains composite authority (`schema/edn.clj:67`); constructor value and caller short-circuit land together. |
| `seon.env.edn` | Reuse existing incomplete-environment and invalid-member classes. Declare constructor input from its real member grammar; a boot partial input differs from a completed environment. Carry acquired error disposition/log capability in the environment if the helper needs it. | No runtime fetch of config/root/projection during failure; no second refusal family. |
| `seon.config.error.edn` | Reuse evidence/recurrence bounds. If log byte/work bounds lack a current declaration, add them here with explicit failure outcomes; no hard-coded infinite replay or endless append. | Exhaustion is visible, retains pending records, and never silently drops the critical event. New bounds need measurement in implementation. |
| `seon.operator.edn`, `seon.boot.edn` | Declare operator log append/replay result and acquired IO capability using existing process-root/log configuration. Known durable completion differs from unavailable completion. | Boot before the database can report a fault; failed log append never calls the same failing store. |

### 3.2 Domain shapes named by the audits

This table replaces the audits' broad-map candidates. Nullable below means
an explicit successful absence arm, not an exemption from the error protocol.
Where a return is already a scalar/set/tuple of declared values, reuse it;
not every private function needs a new registered map.

| Resource(s) | Candidates covered and shape to declare/reuse before their contracts | Evidence and acceptance |
|---|---|---|
| `seon.turn.edn` | `current-run`, `agent-run`, `require-open-run`, `open?`: selector-derived turn with required identity. `settlement-form`: declared selected evaluation source/identity fields. `stored-record-content`: named record containing its turn and ordered evaluations. Budget readers: declared nullable limit; predicates return boolean only on successful reads. | Audit 2 §2.1; triage #2/#6/#12. Identity required even when closed-tx absent. No refusal becomes open, absent, budget, or divergence evidence. |
| `seon.eval.edn`, existing `seon.cluster.eval.edn` consumers | `running-receipts`, `current-receipt`, `latest-evaluations`, `read-only-evaluation?`, `shown-result`, evaluation lookup/ordinal: derive selected evaluation rows, keyed source map, ordinal/eid scalars, exact shown text. Move a legacy contract when its owning evaluation migration lands; never mint a new legacy durable entity. | Audit 2 §2.1/§2.3. Source-to-evaluation map values have identity and read evidence; stored history never reruns writes or reconstructs result objects. |
| `seon.program.edn` | `analyze-settlement`, `row-tx`, `remaining-definition-facts`, `published-index-rows`, `reconcile-tx-in`: reuse declaration/deletion row union, identity tuple, definition-attributes and tx-data. Declare the analyzer request/analysis shape where not already covered. Identity-indexed row maps have declared key and value grammars. | `sci/eval.clj:720`; triage #9/#15. No error keys enter definition facts. Deleted pending-relation/tombstone/stub mechanisms get no new contracts. |
| `seon.fn.edn`, `seon.ns.edn`, `seon.test.edn` | Keep reset symbols: fn/test qualified symbols; ns symbols; indexed symbol call/reference/reach sets; required analyzed-input and reach-computation evidence. `declared-reference-edges` now produces the actual symbol-edge tuple relation, not audit 3's pair of entity integers. | Reset and triage §2. Positive present-row joins and known-empty analysis; unresolved names stay visible without fabricated rows. |
| `seon.fn.output.graph.edn` **proposed** | `output-graph`: register four actual members—functions, calls, sinks, boundaries—from `src/seon/fn.clj:1572`. Functions are symbol collection, calls map symbols to symbols, sinks/boundaries reuse their declared value grammars. | Audit 3 F6. A failed query is not an empty graph or a graph of MapEntries. Preserve sink/projection distinctions. |
| `seon.cluster.edn`, `seon.activation.edn`, `seon.cluster.source.edn` | `missing-process-rows`: vector of actual process identity rows; `closure-fact-missing`/`stored-activation`: declared activation closure and lookup-row shapes; `schema-row-changes`: canonical declaration rows; `program-currentness`: named coherence observations; `development-source-refresh!`: existing adoption result or a named completion; `recover-runs!`/`accrete-schema-population!`/`commit-fault!`: nil where genuinely no work, otherwise transaction-report. | Audit 2 §2.2/§2.6, triage #8/#13. Refusal must not fabricate missing facts, success, counts, or source-currentness. |
| `seon.db.process.edn`, `seon.cluster.process.edn` | Process identity rows and the process record remain different shapes. Use process identity, generation and paths from their existing owners; no “alive” mirror replaces observed process identity. | `cluster.clj:1073`; boot/lifecycle evidence is serious work, not a mechanical map annotation. |
| `seon.sci.eval.edn` | Reuse actual ctx/acquisition/request/evaluation result shapes for `acquire-program!`, `run-candidate-test!`, `record-acquisition-refusals!`; derive program documentation rows for `program-documentation`; `latest-print-fact` derives from the requested attribute, not generic `:map`. | Audit 2 §2.3. Acquire/record faults before letting a context run; doc/dir preserve symbol identities and their contract data. |
| `seon.sci.kernel.edn` | Reuse live `arm`; declare actual guard host value and constructor state for `new-guard`, `new-armed`, `own-arm`, `current-thread-arm`, `same-interpreter?`, `record`, `allocated-bytes`. Distinguish boolean, live object, diagnostic record and integer results. | Kernel source, audit 2 §2.3. No false `guard` alias to `arm`; time-limit and interpreter identity remain enforced. |
| `seon.sci.admit.edn`, `seon.print.edn` | `admit*`, `admit-walk`, `project`, frame/leaf helpers: reuse admitted/missing, caps and print-node grammars; declare actual traversal state only where consumed. `requery-form`: existing requery grammar plus genuine nil arm. | Audit 2 and audit 4. External evidence admission is not evaluation-result serialization; `unbounded?` exception cannot silently swallow a missing bound. |
| `seon.effect.edn` | `evaluation-eid`, `write-back-adds`, `settle-value!`, `interrupt!`: actual effect request/identity; tx-data builder; success result requires effect value and **successful** transaction evidence. Failure is top-level exceptional output, never stored inside `:seon.effect/transaction`. | Triage #10. Deleted capability-fn lookup gets no replacement identity schema. Reuse the common ref/attribute projection instead of the duplicate helper. |
| `my.plan.edn`, `seon.plan.edn` if a new system-only value requires it | Existing plan/step/note entity and request declarations own the shapes. `agent-eid`, `plan-eid`, `step-eid`, `subject-eid`, `ref-eid`: declared identity/ref input → nullable eid. `owned-step-eid!`: successful eid only. `next-position`: nonnegative position. Completion/scalar-retraction builders use tx-data; `transact-plan!` uses transaction-report. | Triage #7, audit 2 §2.5. No generic `:schema/value` for a reference with a known ref grammar; refuse before ownership or arithmetic. Do not create a parallel plan fact family. |
| `seon.message.edn`, `my.message.edn` | Reuse message request/entity/subject/assignment identities for recipient and inbox readers; derive selected listing rows. Existence is boolean after a successful observation; message instant is an instant, never nil produced from a read refusal. | Triage #11 and reset message ruling. Routing and handling remain separate; no retired inbox/read-tx semantics. |
| `seon.cluster.prompt.edn`, `seon.ai.edn`, `seon.ai.model.edn` | Calibration/config names use their existing keys; `model-details`/`rendered-model` derive selected model data, with an explicit transformed thinking-dial collection when needed. `attempt-without-private-provider-data` reuses render-unit. | Audit 2 §2.6 and audit 4. Refuse unavailable calibration before a paid request. A render schema must not expose private provider request data. |
| `seon.issue.edn` and detector result declarations | `declared-keys`: set of qualified keywords; `component-values`: declared entity-id set. `citation-attributes`: keyword→keyword map; `citation-index`: declared observed token→set of attribute/eid pairs, with symbol/keyword/string identity values preserved. Subject/detector rows derive from their selectors; generated report declares findings and explicit unavailable evidence. | Audit 3 F1–F3/F13; triage #3. No fabricated findings or string-coerced symbol citations. Error means the detector could not decide, not zero issues. |
| `seon.test.run.edn`, `seon.test.runner.edn`, `seon.test.failure.edn` | Reuse claim-completion, failure and run identities. `record-latest-tx`/`failure-replacement-tx`: declared completion/failures → tx-data. Selection tasks, skipped work, confirmations and liveness reports need their actual state-dependent shapes. | Triage #14/#16 belongs to test-system stages 1/3. Never duplicate their new run/request protocol with an audit-era schema. |
| `seon.test.cache.edn` | Declare or reuse the cache descriptor/change comparison/resource reference shapes consumed by `alive?`, `compatible-changes`, `reap!`, `referenced?`. Contracts preserve current cleanup/resource semantics. | The schedule's first paid slice is these four together. A no-database call is not proof that filesystem cleanup is harmless. |
| `seon.render.edn`, `seon.render.ns.edn` where declarations belong | `namespace-owner`: nullable agent ID after checked read; `repl-state`: named namespace/basis state; `schema-row`: derived schema entity, never projection-row tuple; `bounded-error-node`: actual print node or explicit admission failure. `ambient-database-value`/`custody-cluster` should use supplied environment/database, not gain a contract that blesses global fallback. | Triage #22. Page caches key off supplied immutable basis; failed reads are not cached as successful absent schema rows. |
| `seon.render.walk.edn`, `seon.render.value.edn`, `seon.render.web.edn` | `installed-attributes`: map qualified attribute→declared installed Datahike attribute shape. `reference-identity`: selected identity value under its declaration, with an explicit unresolved case. Web existence/provenance helpers use derived rows and the writer's own decision. | Audit 4. Preserve complete HTML and total value rendering; no successful empty attribute catalog after a refusal. |
| `seon.search.edn` | `document-specs`: vector of declared field/index specifications (`field` and `index` already exist). Register the missing `roster` with document-specs and identity-attributes from `search-roster`, and missing `document` as the actual Lucene host Document shape. `declared-entity-ids`: eid set; `entity-documents`: vector of those host documents, not entity maps. | `search.clj:180`, `:191`, `:237`, `:255`. A failed source derivation cannot trigger deletion and a falsely successful empty rebuild. |
| `seon.schedule.edn`, `seon.maintenance.edn`, `seon.maintenance.receipt.edn`, `seon.maintenance.result.edn` | Task readers derive task id/function/expression/zone and selected maintenance fields. `latest-receipt` needs the nullable selector-derived existing `:seon.maintenance.receipt/receipt`, including expanded request/result components, not the stored schema unchanged or an arbitrary result map. `transact-result` uses existing transaction-report; no second flat-error predicate. | Audit 4. Successful empty schedule differs from unreadable schedule; preserve current fn symbols. |
| `seon.operator.collect.edn`, `seon.cluster.registry.edn` | `branch-digests` and collection inventory return complete known roots or an explicit unavailable result. Never treat a failed history read as an empty retention set. | `operator.clj:688`. Destructive collection uses the existing owner and complete dependency evidence. |
| `my.fs.edn`, existing filesystem host-path declarations | `delete-recursively-impl!`: actual Path/File/string inputs at their normalization boundary; progress callback is callable, not a qualified symbol. Reuse the existing path contract only if it really admits these inputs. | `fs.clj:220`. Preserve no-symlink-follow semantics; do not narrow a host Path into an agent path string by annotation. |
| `my.background.edn`, `seon.program.edn` | `invalid-call`: existing invalid-call class. `render-referrers`: set of schema-key/property maps. `symbols`/`present-groups`: caller-derived symbol collection/group map, not `:map → :map`. Delete `stored-name` compatibility after reset rather than preserving a wrong union. | Audit 4 §2/§5. Pure helpers are easy only after their real shapes are declared. |

Names such as `seon.cluster.edn` or `seon.test.cache.edn` in this table name
the schema resource owner to use or create **only if the merged registry has
no existing owner for that value**. They do not assert all these files exist
today. Reuse is the first edit; a resource's filename is not evidence of a
second durable entity family. Scalar helpers elsewhere in the audit rosters
use the same existing scalar/collection/callable contracts; no unresolved
bare-map candidate is licensed by this table.

### 3.3 The declaration expansion and completeness checks

At schema admission, derive the error class union and exact kind constraint
from the registered class forms. Check duplicate classification, constructor
output shape, and required diagnostic members through the existing admission
and contract machinery. Fail on missing population; an empty class set is
not a green check. Class-specific generators must generate the complete
constructor shape, including substantive marker payloads after migration.

At function/contract admission, inspect canonical Malli shape facts, including
aliases and nested success shapes, for bare-map/any placeholders. Reuse the
existing explicit polymorphic exemption with its reason. Do not implement
this as production regexes over source. A function with a correct scalar
output can still misuse a read inside its body: contract completeness and
read-consumption coverage are separate findings.

The generic read API's selector-dependent result is a justified polymorphic
boundary, not one of 69 excuses to leave a known producer untyped. Its
specific consumers must state what row/tuple/collection they consume.

### 3.4 Dated per-resource error-class edit inventory

Every row gets the §1.1 expansion, constructor/generator migration, preservation
of substantive payload, and derived render dispatch. No class is deleted just
to reach a lower count. This is the **2026-09-17 inventory**, not a maintained
hand roster. Additional domain-shape work is in §3.1–§3.2.

| Resource | Marked classes | Specific review in addition to the common migration |
|---|---:|---|
| `my.background.edn` | 3 | Preserve malformed authored-call evidence. |
| `my.edit.edn` | 6 | Preserve source/digest conflict and member evidence. |
| `my.fs.edn` | 16 | Preserve path and failed operation. |
| `my.message.edn` | 6 | Distinguish authored recipient refusal from delivery failure. |
| `my.note.edn` | 5 | Preserve note identity/rule. |
| `my.plan.edn` | 13 | Ordinary proposal refusal versus failed ownership observation. |
| `my.shell.edn` | 5 | Retain execution bound/exit evidence. |
| `my.turn.edn` | 3 | Preserve authored turn disposition mistake. |
| `my.web.edn` | 11 | Preserve URL/status/query payload; class is not merely its marker. |
| `seon.agent.edn` | 6 | Separate agent input from core lifecycle fault. |
| `seon.ai.edn` | 20 | Preserve provider attempt evidence and explicit unknown outcome. |
| `seon.artifact.edn` | 1 | Preserve artifact identity. |
| `seon.blob.edn` | 5 | Blob acquisition failure can affect fault recording itself. |
| `seon.boot.edn` | 1 | Must work before database acquisition. |
| `seon.bootstrap.edn` | 2 | Name missing initialization member. |
| `seon.cluster.export.edn` | 5 | Preserve branch/root identity, not a new lifecycle protocol. |
| `seon.cluster.process.edn` | 1 | Exact process identity evidence. |
| `seon.cluster.prompt.edn` | 5 | Refuse before paid request construction. |
| `seon.cluster.registry.edn` | 8 | Unknown collection roots never mean none. |
| `seon.cluster.reply.edn` | 3 | Authored reply parsing remains ordinary. |
| `seon.cluster.source.edn` | 7 | Preserve publication/adoption phase and source basis. |
| `seon.cluster.store.edn` | 6 | Complete flat ex-data; durable log without recursive transact. |
| `seon.cluster.wake.edn` | 1 | Preserve missed delivery evidence. |
| `seon.config.edn` | 7 | Refused rule remains data; all constructors carry message. |
| `seon.context.edn` | 1 | Explicit unavailable context. |
| `seon.db.edn` | 5 | Known rejection versus genuinely unknown transaction outcome. |
| `seon.dev.mcp.artifact.edn` | 1 | Evidence retrieval failure remains typed. |
| `seon.dev.mcp.edn` | 5 | Preserve root/cluster/advertisement diagnostic. |
| `seon.effect.edn` | 3 | Failure never hides inside successful settlement. |
| `seon.env.edn` | 8 | Existing invalid-member/incomplete classes; no duplicate registration. |
| `seon.error.edn` | 1 | Recorder failure cannot recursively require itself. |
| `seon.eval.drive.edn` | 1 | Preserve deadline/driver evidence. |
| `seon.flow.edn` | 6 | Critical panic must survive supervision transport. |
| `seon.fn.binding.edn` | 1 | Preserve exact binding/member. |
| `seon.fn.edn` | 13 | Symbol identities and complete program refusal. |
| `seon.instrument.edn` | 2 | Preserve function, arm, paths, expected and actual leaf. |
| `seon.message.edn` | 5 | Reuse message/wake reset semantics. |
| `seon.operator.collect.edn` | 1 | Complete collection evidence. |
| `seon.operator.edn` | 6 | Durable report before process failure. |
| `seon.print.edn` | 1 | No extra clipping site. |
| `seon.problems.edn` | 2 | Reconcile structured problem and common diagnostic, not duplicate errors. |
| `seon.program.edn` | 4 | Refactoring input stays complete before rendering. |
| `seon.reconcile.edn` | 6 | Preserve exact desired/current member mismatch. |
| `seon.render.data.edn` | 1 | Unavailable data is not absent data. |
| `seon.render.edn` | 4 | Preserve unknown reason/refusal/call evidence. |
| `seon.render.hiccup.edn` | 1 | Ordinary values remain renderable. |
| `seon.render.value.edn` | 3 | Reuse value projection and total fallback. |
| `seon.render.walk.edn` | 3 | Do not cache or traverse a failure as a world. |
| `seon.render.web.edn` | 9 | Separate request mistake from core render fault. |
| `seon.schedule.edn` | 7 | Empty work only after successful derivation. |
| `seon.schema.datahike.edn` | 10 | Preserve native storage/codec grammar evidence. |
| `seon.schema.edn` | 25 | Retain payload-bearing schema/member markers as diagnostic data. |
| `seon.schema.edn.edn` | 8 | Declaration loading can fail before full projection exists. |
| `seon.schema.shape.edn` | 3 | Preserve canonical shape identity and path. |
| `seon.sci.admit.edn` | 1 | External evidence bound, not evaluation result storage. |
| `seon.sci.eval.edn` | 10 | Critical acquisition failures must not become ordinary agent errors. |
| `seon.sci.kernel.edn` | 7 | Distinguish ordinary authored evaluation failure from broken guard. |
| `seon.sci.reader.edn` | 5 | Authored reader mistakes remain ordinary. |
| `seon.search.edn` | 2 | Failed rebuild derivation cannot report a successful empty index. |
| `seon.test.accretion.edn` | 1 | Retain actual proposed contract change evidence. |
| `seon.test.edn` | 2 | Never report unavailable test evidence as passing. |
| `seon.test.run.edn` | 2 | Preserve claim/completion state and request basis. |
| `seon.test.runner.edn` | 10 | Runtime test-system owner controls protocol changes. |
| `seon.turn.edn` | 2 | Ordinary transition refusal and critical failed eligibility read differ. |
| `seon.turn.loop.edn` | 4 | Proc failure is positively observable, not silently parked. |

Reproduce the census with an EDN reader, including namespaced map syntax;
this form is included here so the evidence does not depend on a scratch file:

```clojure
(require '[clojure.edn :as edn] '[clojure.java.io :as io]
         '[clojure.string :as string])
(let [resources (filter #(and (.isFile %)
                              (string/ends-with? (.getName %) ".edn"))
                        (file-seq (io/file "resources/seon/schemas")))
      classes (for [file resources
                    [schema-key form] (edn/read-string (slurp file))
                    :when (and (vector? form) (map? (second form))
                               (true? (:seon.error/class (second form))))]
                [(.getName file) schema-key form])]
  {:seon.study/classes (count classes)
   :seon.study/by-resource
   (into (sorted-map)
         (map (fn [[file members]] [file (count members)]))
         (group-by first classes))})
```

## 4. The checks and what they emit

### 4.1 One owner per decision

| Decision | Owner and successful result | Failure result and future handling data |
|---|---|---|
| Does the call satisfy its contract? | Existing `seon.instrument` wrapper; invoke exactly once only after valid input; validate output and guard. | Preserve an existing error or construct one diagnostic with function, arm, schema/value paths, expectation identity and actual leaf. The shared helper applies the dial. |
| Can this read be consumed? | Existing `seon.error/error?` plus the one disposition helper; caller branches before consuming the result. | Original read failure, with boundary context as evidence. No `nil`, fabricated collection, arithmetic, subsequent query, or write. Database failure goes to the durable log. |
| Does the resulting transaction satisfy the data model? | `seon.db/write-report-error` / final-report validator on `db-after`, including attempted assertions and swept datoms. | One complete refusal naming identity, member/path, expected stored/owned shape and offending value. Datahike aborts before commit. |
| Is diagnostic admission complete? | Existing normalization/admission produces the bounded durable record and separately preserved classifying dimensions. | Explicit unavailable/bounded evidence; recorder/log failure stays visible and does not recurse. |
| Is a fault already recorded? | Existing serial fault writer checks proposed recorded-event-id and decides recurrence/notification in the same transaction. | Replay returns already-recorded evidence or a typed pending failure. No duplicate count and no apparent acknowledgement. |
| Is a function eligible for a contract task? | Existing program/issue detector queries declared contract, direct consumers, callers, gate set and schema dependencies. | Typed unknown on unavailable graph/read evidence, never an empty task set implying health. |

Dependency constraint: Malli's `:report` is side-effecting. Its wrapper calls
the function after reporting invalid input if the reporter returns normally
(`reference-code/malli/src/malli/core.cljc:2203–2221`). **Changing
`throwing-report` to return a value is insufficient and unsafe.** The existing
Seon wrapper must short-circuit in `:record` as well as in `:panic`; use the
implementation lane's chosen mechanism, not a second wrapper proposed here.
Likewise, a helper that returns an error in `:record` requires an actual
caller branch before arithmetic/destructuring. Contracting only the outer
function cannot detect an in-body read after it has become a plausible
boolean or empty set.

`buried-error` currently examines direct arguments and the direct return
(`src/seon/instrument.clj:99`). It is not proof of arbitrary nested discovery.
For a nested failing member, the validated path can identify the original
error; do not walk all arbitrary user objects hunting error-shaped maps.
Record once at the owning observation, and propagate its evidence identity.

The writer is already the last authority. Datahike calls the final-report
validator and throws its refusal before publishing the report
(`reference-code/datahike/src/datahike/db/transaction.cljc:1206`). Its writer
delivers rejected operations and retains the old basis
(`reference-code/datahike/src/datahike/writer.cljc:147–218`). Current Seon
validation includes owned components, deletion, render targets and call
arities (`src/seon/db.clj:3517`). Extend that owner; do not add a submission
pre-check that claims the final decision. Required refs refuse a sweep;
optional refs sweep; component children validate as part of their owning
value. Validation-node exhaustion refuses explicitly. No wildcard pull proves
complete ownership validation.

### 4.2 Compatibility with repairs being made now

| Active repair / triage | Keep now and under the proposed family | Change later, explicitly |
|---|---|---|
| #1 one predicate | All nine callers delegate to **existing** `seon.error/error?`; marker-only errors are recognized. Lazy resolution is acceptable at the existing load-cycle seam. | Only the class declarations/constructors and shared predicate acquisition change for canonical kinds. Delete remaining inline kind-presence tests; no call-site reclassification. |
| #2 writer turn eligibility | Check the original read before nil/open?; require turn identity in success input. Abort transaction on refusal. | Replace hand-written pulled-map contract with selector-derived shape; apply shared critical disposition without swallowing rollback/panic. |
| #3/#4/#11 detector, recorder, delivery | Guard every read before set/boolean/count/ref construction. Preserve exact error. Recorder failure must still be observable outside the failed database. | Fault recorder adds promoted diagnostic fields and atomic replay identity. Databases failing here use the durable log, not an immediate recursive `transact!`. |
| #5 transaction outcome | Preserve declared marker-class refusal through exception wrapping; never relabel known rejection unknown. | Constructor canonical kind changes once; outcome classification remains. Shared helper supplies `:panic/:record` after the real outcome is known. |
| #6/#7/#8/#12/#13 budget, plan, recovery, divergence, activation | Error check precedes numeric comparison, ownership, mapcat, history boolean and missing-set derivation. In production, error is returned intact. | New precise output/derived row schemas; development checks expect the same error as ex-data, not unconditional “never throws.” |
| #9 program definition | Refuse before `dissoc`/definition transformation. | Use canonical declaration/definition-attributes contract; no new error-containing success row. |
| #10 effect settlement | A refused write never becomes a successful `transaction` member or settled value. | Declare success result and transaction evidence precisely; failed admitted writes use database-log disposition. |
| #14/#16 test-system stages | Keep selection/recording refusing at the owner; no green result from a smaller accidental set. | Contract the **new** stage-1/stage-3 protocol. Do not reintroduce the discarded audit draft or old claim registry. |
| #17 config constructor | Return a complete declared error including message; callers stop on it. | Canonicalize kind/rule fields; ordinary malformed config remains a value, failed core/database observation uses the helper. |
| #18/#19/#20 declarations, replay, projection | Explicit refusal before decoding or compiling; total named replay operation. | Named decoder/projection/result contracts replace generic maps. No exception-to-empty fallback. |
| B6 supervision and bounded fault evidence | Proc death names the agent, class and operation positively; classification survives admission bounds. | Persist shared critical disposition/path dimensions; obey the chosen panic scope. |
| #21/#22 graph coverage and rendering | Positive graph coverage, supplied custody, and checked derivation before destructive rebuild/cache success. | Derived model/schema/task shapes and canonical error render dispatch; host arming remains separately measured. |

A repair returning a preserved error is valuable immediately. It becomes
incomplete only if its surrounding production caller still consumes it as a
success, or its development catch converts a critical panic back into data.
The campaign must inspect those edges, not undo the short-circuit repair.

### 4.3 Positive class regressions

These are proposed recurring proofs, **not tests run by this study**. Reuse
canonical database fixtures, real SCI, and the worker's armed contracts.
Every check asserts positive setup/subject presence before its negative case.
Use one parameterized regression per failure class, not one near-copy per
function. Existing tests should grow to cover the class where possible.

| Class regression | Wanted behavior it proves |
|---|---|
| Declared errors have one identity | Generate each declared class through its constructor; all nine former recognition sites agree. Unrelated maps are ordinary data. During transition marker-only classes work; after migration kind equals the declaring key and no ambiguity remains. |
| A refused read cannot supply a success value | Exercise the triage's seven consumption groups—truthiness, set conversion, presence, keyed access, boolean/count, arithmetic, direct bind—through real owner seams. Ordinary absent row still returns nil; a present row is consumed; refusal returns/exposes exactly its cause and the dependent effect never runs. |
| Both dial modes enforce contracts | Private and public host/SCI inputs, outputs, guard failures, and propagated errors: body count stays zero on bad input; record mode commits/logs one fault; panic carries the same diagnostic and reaches the fatal observer. Ordinary authored mistakes do not panic. |
| A known rejected transaction stays known | Marker-class/then canonical-class transaction refusal, final-report validation rejection, and an uncertain dependency outcome are distinct. No commit on known rejection; uncertainty never causes automatic replay of the original mutation. |
| The final database is the validation unit | Same-transaction repair succeeds; required ref deletion refuses; optional ref sweeps; child-only damage and idempotent attempted invalid assertions refuse; complete large component values are checked beyond pull's default cap. |
| Pull shape derives from its declaration and selector | Stored ref grammar differs from unexpanded/expanded/reverse/default/alias pull shapes; selected identity is required by consumers; changed declaration regenerates the contract; unsupported selectors and result cuts are explicit. |
| A bounded fault retains its actionable leaf | Huge environment with a tiny wrong member records kind, function, path, expected identity and independently bounded actual; known nil/false remain known. AI shown text respects its profile once and HTML introduces no clipping. |
| Database-fault recording survives database failure | Real unavailable/rejecting store path appends durable evidence with no recursive transaction. Restart/duplicate replay/import-commit-before-ack yields one count and one notification. Partial record/log failure remains a visible pending/reporting failure. |
| Findings and gate sets require successful observation | Positive subject/test graph exists. Refused reads produce unavailable/refusal, never manufactured findings, empty reach, a green reduced gate, or nil-keyed recording retractions. |
| Symbols survive every identity join | Nonempty known fn/ns/test/renderer joins, scalar and collection bindings, call/reference/reach queries preserve native symbols. Reuse the landed codec regressions. |
| Coverage includes the subject it claims | Known loaded private callable is armed; known operator function is indexed for call/reach; contracted/eligible populations are nonzero. Neither unloaded rows nor an empty census count as successful arming. |

Future issue detectors query fault kind, critical disposition, operation,
member/path, expected schema identity, occurrence basis, and provenance. They
join the operation symbol to current namespace/steward and gate set; missing
current definitions are a positive historical-observation result. The issue
identity stays the existing detector-plus-subject identity. Repetition is
the existing fault recurrence, not a new task registry. A diagnostic that
lacks attribution yields an explicit unassigned finding instead of vanishing
from the queue.

## 5. Campaign order and the separate live-agent pool

The triage's **285 private database consumers / 183 with no error token**
are dated source-scan evidence at `6ea932372`, not a semantic proof of which
functions check correctly. Its seven groups total 183 (23, 16, 10, 8, 5, 4,
117). The earlier 352 missing contracts and 69 public bare-map outputs came
from a different live graph/population. Do not subtract one census from the
other. Re-derive coverage from the reset symbol graph at campaign admission.

Immediate B1–B8 fixes and test-system stages follow the schedule unchanged.
The following is **contract dependency order within those owners**, reconciling
the audits' separate triage orders. Schemas must exist before their consumer
contracts are armed; repairs do not wait for speculative schema refinements.

| Contract domain, in dependency order | Schema prerequisites | Why this order / triage coverage |
|---|---|---|
| Error recognition, diagnostic construction, disposition and fault recording | §3.1 common error/occurrence/instrument/lookup/log shapes | Other failures must be reportable; #1/#4/#5/#17 and B6 remain implementation-owned. Verify both dial modes before expanding arming. |
| Database, schema, config and store read/write boundaries | Declaration/decoder/projection shapes, tx-data/report, generated config composites, pulled-shape derivation | Audit 1's transact → population/effective → pull → projection/declarations → replay order, after predicate consolidation; #18–#20 cannot silently supply a false schema world. |
| Turn/cluster writer and recovery, then plan/effect/message | Identity-bearing selected turn/evaluation, budgets, activation/source, effect settlement and plan/message request shapes | Audit 2's current/open → budget → recovery → definitions/ownership → settlement/delivery → process/closure sequence; critical #2/#6–#13. Arming boot/acquisition requires the orchestrator's isolated proof before default adoption. |
| Program, detector, fault attribution and test recorder | Canonical declarations/symbol edges, output graph, citations, detector result, current stage-1/stage-3 request/result shapes | Audit 3 puts detector correctness first, then recorder and gate/index evidence. Recorder safety is already a shared prerequisite above; #3/#14–#16/#21. No hand roster of tests. |
| SCI acquisition, environment, execution bounds and admission | Existing env classes; ctx/guard/arm, acquisition result, reader/Malli forms, admitted/missing print data | Audit 2's non-database functions here are not an easy pool: a bad boolean at the interrupt seam removes a bound. Keep real SCI semantics and panic propagation. |
| Render, provider context, search, scheduling and operator derivations | Derived schema/model/task shapes, installed attrs, search documents, retained roots, complete status/result shapes | Audit 4/#22 and B7: supplied world, checked derivation, no poisoned cache, no destructive rebuild on failure. Preserve renderer totality. |
| Remaining private functions, then public broad outputs | Caller-proven scalar/tuple/collection shapes, justified polymorphic boundaries; all outstanding §3 shapes | Finish the all-private ruling, then the dated 69 public broad outputs, pulling any critical public consumer forward when encountered. Shape quality is the criterion, not contract count. |

The **easy pool is separate from this serious list**. The schedule explicitly
selects one first paid slice: `seon.test.cache/alive?`, `compatible-changes`,
`reap!`, `referenced?`, together for one agent, after test stages 1/3, B6 and
detector correctness. Preserve that choice. Its existing resource/cleanup
test must remain the harness; inspect the real callback/path/descriptor
shapes before assigning contracts.

The triage records 27 named candidates (17 from audit 3, ten from audit 4),
not 27 automatically safe edits. Admit each only after a current query proves
its caller, reaching tests and schema prerequisites. Good small subsequent
candidates include pure rounding, existing `my.background/invalid-call`, and
`seon.print/requery-form` with its real nullable result. `present-groups`
does not qualify with `:map → :map`; declare the actual groups first.
Diagnostics such as `liveness-diagnostic` and `thread-info-text` must preserve
complete thread evidence. Provider-data redaction, cleanup, lifecycle,
deadlines, database calls and proc transforms require their specific proof
even when they have one caller. The remaining 357 no-database-call functions
from audit 2 are a search space, not a ready assignment roster.

Each task's done condition is: declared shape precedes its contract; real
callers satisfy it; both expected success and original-error propagation are
proved under canonical arming; no bare-map escape or silent empty result;
the relevant live result is positively observed by the implementation owner.
Lanes supply fast iteration; the orchestrator supplies cold/platform and
reset/adoption proof. This study runs none of those gates.

## 6. Evidence, reading record, and verification boundary

Read the supplied AGENTS.md §§0–7, including the replacement received during
the study; the data-modeling, Datahike, data-oriented Clojure and REPL skills;
the following named authorities end to end (PRD §1j–§1l was read first):

- [Program-facts PRD](../plan/program-facts-are-the-runtime-prd-2026-09-17.md).
- [Data-modeling guide](../../../seon/architecture/data-modeling-guide.md).
- [Datahike modeling study](datahike-modeling-study-2026-09-17.md).
- [Private audit: database/schema/config](private-function-audit-db-schema-config-2026-09-17.md).
- [Private audit: turn/cluster/SCI](private-function-audit-turn-cluster-sci-2026-09-17.md).
- [Private audit: test/program/issue](private-function-audit-test-program-issue-2026-09-17.md).
- [Private audit: my/render/operator](private-function-audit-my-render-operator-2026-09-17.md).
- [Read-error class issue](../../../seon/issues/a-database-reads-error-value-is-read-as-a-row-by-its-caller.md).
- [Private-contracts landing note](private-contracts-2026-09-17.md), re-read after resumption; it still reports slice 1, not a completed slice 2.
- [Critical-findings triage](critical-findings-triage-2026-09-17.md), end to end, including its corrections and source-scan limitations.
- [Steward-platform roadmap/schedule](../plan/README.md), end to end; the 19:50Z schedule controls over older entries.

Also read research B and the reset batch's correction/edit tables. Read
`resources/seon/schemas/seon.error.edn` and the 65 error-class resources
through EDN, inspecting their class forms, required members, aliases and
properties; source reads covered diagnostic/admission/recognition/recording,
instrumentation wrapping and reporting, database error recognition and the
inline sites named by audit 1. The six spellings are the db predicate, error
predicate, two config kind tests (`config.clj:461`, `:591`) and the writer's
two exception-data kind tests (audit-era `db.clj:3450`, `:3452`; relocated
under `transact-call`). The nine private copies are triage #1; inline tests
remain part of its semantic consolidation, not additional public predicates.

Dependency source ledger, read at the seam rather than inferred from tests:

| Dependency pin observed | Mechanism read | First-party consumer |
|---|---|---|
| Malli `3517a3cd9271b2083780ac7be1725493905bca2e` | `reference-code/malli/src/malli/core.cljc:2203` reporter/control flow; `:3119` instrumentation properties; `instrument.clj:43` contract collection | `src/seon/instrument.clj:450`, `:507`, `:593`, `:687` |
| Datahike `73afe78271a289861da236c5ac3457e64349653f` | `reference-code/datahike/src/datahike/db/transaction.cljc:1206` final report; `writer.cljc:147` rejection and callbacks; `pull_api.cljc:298` ref identity and `:313` limit/alias/default/cardinality | `src/seon/db.clj:3517` final validation; `:1919` pull; `:3707` transaction |
| SCI `fcbd8862800e638dc0f8f5521111f999279cbcd2` | Existing context/fork and interrupt semantics cited by the modeling/REPL authorities; no alternative interpreter proposed | `src/seon/sci/eval.clj` acquisition and `src/seon/sci/kernel.clj` guarded boundary |

Line references are dated source anchors, not stable addresses. The triage
re-located its anchors at `6ea932372`; this resumed study observed HEAD
`417a8d912f2e30615b5c37cfcdc9ac5ed768c210`. Concurrent implementations may move
them; follow the named functions. Source conclusions are not new live proofs.

Before the handoff, `bin/seon status` and MCP runtime status answered on
default PID 94566, with existing faults. One subsequent read-only explicit-
custody JVM evaluation was **attempted but did not execute**: MCP returned
`repl-unavailable`, `advertisement-state: missing`, for root
`/Users/sean/src/seon`, cluster `default`. It attempted only to read the
database basis, installed fn/ns/call/reach types, and carried-projection
presence. This was reported immediately. No lifecycle workaround was used.
The owner subsequently reported reset convergence and supplied the codec
commits; that supersedes the earlier unavailable observation. **After the
resumed instruction: no JVM calls, tests, lifecycle operations, or lane
operations.** The unavailable pre-handoff probe is not evidence that the
current reset cluster is unhealthy.

This note is the only owned path. No production/schema edits or extra issue
files are part of the assignment; the existing class issue and triage carry
the defect inventory, and this note records their modeling corrections.
No foreign gate failure was repaired or attributed. No scratch worktree,
cluster root, or background shell was created. Verification is document
consistency, resource census, source/dependency reading, and path-limited
diff review; implementation, cold gates, replay crash tests, and live adoption
remain explicitly unproved here.

The Markdown edit hook reported 33 repository-wide pin-citation errors,
including historical `agents-md-audit-2026-09-15.md` references whose pinned
gitlinks differ from current ones. Its implementation combines file validation
with repository pin validation (`bin/seon-hook:995`). This is a foreign
documentation-check boundary, not a production gate result. Those documents
were not edited; this note's dependency pins are the ones observed above.
