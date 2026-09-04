---
type: research
status: complete
date: 2026-09-04
tags: [research, docs, architecture]
---

# Context data-model census

This is the data-first census required by ruling 62. It reads the current
schema declarations and the code that writes and reads them; it does not treat
the current render surface as authority for the future model.

## Scope and method

I read these named authorities end to end before drawing conclusions:

- [`repl-first-behavior-2026-09-03.md`](../plan/repl-first-behavior-2026-09-03.md),
  including B1–B15 and section G;
- [`repl-first-visual-2026-09-04.md`](../plan/repl-first-visual-2026-09-04.md);
- [`repl-first-one-platform-2026-09-03.md`](../plan/repl-first-one-platform-2026-09-03.md);
- the active [`sci-execution-runtime/plan/README.md`](../../sci-execution-runtime/plan/README.md);
- [`.agents/skills/data-modeling/SKILL.md`](../../../../.agents/skills/data-modeling/SKILL.md);
- [`.agents/skills/datahike/SKILL.md`](../../../../.agents/skills/datahike/SKILL.md); and
- because this is Seon Clojure data design, [`.agents/skills/data-oriented-clojure/SKILL.md`](../../../../.agents/skills/data-oriented-clojure/SKILL.md).

For each durable family below I read its EDN declaration under
`resources/seon/schemas/`, its first-party writer and reader under `src/`, and
searched all call sites with `rg`. `bin/seon status` reported the shared
`ctxprobe` cluster alive, but this lane's tool inventory contains neither
`mcp__seon__runtime_status` nor `mcp__seon__eval_clj`. That already-recorded
tooling defect is
[`docs/seon/issues/lane-toolset-omits-required-seon-mcp-tools.md`](../../../seon/issues/lane-toolset-omits-required-seon-mcp-tools.md).
I did not construct a private prepl bridge or touch the shared cluster. The
schema resources and their compiler/writer call sites were unambiguous, so no
live schema query was needed.

The dependency ledger is small:

| Seam | Dependency mechanism | Grounding | First-party use |
|---|---|---|---|
| Durable identity and refs | Datahike unique identity, lookup refs, component refs | [`datahike/schema.cljc:35`](../../../../reference-code/datahike/src/datahike/schema.cljc) | [`seon.schema.datahike`](../../../../src/seon/schema/datahike.clj) |
| Write chronology | Datahike transaction entity `:db/txInstant`; datom `tx` | [`datahike/db.cljc:218`](../../../../reference-code/datahike/src/datahike/db.cljc) | [`seon.schedule/task-created-at`](../../../../src/seon/schedule.clj#L217) |
| Temporal read identity | Database `basis-t` and origin chain | [`datahike/versioning.cljc`](../../../../reference-code/datahike/src/datahike/versioning.cljc) | [`seon.context/capture-tx`](../../../../src/seon/context.clj#L154) |
| Bulky values | Content-addressed blob digest plus bounded inline projection | [`konserve/core.cljc`](../../../../reference-code/konserve/src/konserve/core.cljc) | [`seon.cluster.run/result-storage`](../../../../src/seon/cluster/run.clj#L84) |
| Code identity | Clojure symbols and namespaces | Clojure `symbol`, `ns-name`, Var metadata | [`seon.fn/namespace-row`](../../../../src/seon/fn.clj#L197), [`seon.fn/var-row`](../../../../src/seon/fn.clj#L328) |

The date rule used throughout this census is: use a domain instant when it is
part of the event (`opened-at`, `completed-at`, `nominal-at`); otherwise derive
write time by joining the identity datom's transaction to `:db/txInstant`.
Adding `created-at` to every row would store the transaction's fact twice.

One authority conflict must be made explicit before implementation. The active
runtime ledger's 2026-08-06 ID policy says a durable row is identified only by
its Datahike entity id and calls run/message/error UUIDs API shape to delete
([`README.md:921`](../../sci-execution-runtime/plan/README.md#L921)). The later
September context authorities instead make each family's declared identity
attribute the family discriminator and teach lookup refs; their examples use
message IDs directly
([`repl-first-one-platform:128`](../plan/repl-first-one-platform-2026-09-03.md#L128),
[`repl-first-behavior:331`](../plan/repl-first-behavior-2026-09-03.md#L331)).
This census follows the later, data-first contract because a raw eid cannot
identify an entity's family or provide an upsert key before transaction. The
owner should record that supersession in the one ledger before the retype.
This is not permission to maintain both an eid handle and a second canonical
agent-visible identity.

## Executive answer

The primary modeling defect is the transcript split. Planning creates a
`:seon.cluster.run.form/id` from `(run-id, ordinal)` and receipt start creates a
different `:seon.cluster.eval/id` from the same pair
([`seon.cluster.run/receipt-identity:604`](../../../../src/seon/cluster/run.clj#L604),
[`seon.cluster.run/form-identity:613`](../../../../src/seon/cluster/run.clj#L613)).
Settlement must therefore recover the form by joining run plus ordinal
([`seon.cluster.run/settlement-form:1058`](../../../../src/seon/cluster/run.clj#L1058)),
and transcript rendering separately queries forms and receipts. These are not
two things. They are the intent and eventual disposition of one evaluation.

Three other high-leverage defects follow the same pattern:

1. `:my.plan.item/about` stores symbol/keyword tokens, then resolves them back
   to entities on every read. It is neither a database ref nor traversable by
   the context walk ([`my.plan/ready-subjects:672`](../../../../src/my/plan.clj#L672)).
2. `:seon.def/key` is the identity while `:seon.def/id` repeats part of that
   identity and `agent`, `ns`, and `name` are checked against both at every
   settlement ([`seon.cluster.run/def-row:1489`](../../../../src/seon/cluster/run.clj#L1489)).
3. A committed error persists both an error fact and a rendered explanation
   message solely because the current run loop wakes on message transactions
   ([`seon.error/message-tx:792`](../../../../src/seon/error.clj#L792),
   [`seon.error/commit-tx:813`](../../../../src/seon/error.clj#L813)). B14's
   attribute-based wake routing makes the second durable copy unnecessary.

Conversely, several suspected gaps are not gaps. Notes already have a real
`:my.note/about` ref. Runs, messages, errors, fires, receipts, and captures
already have honest dates or a derivable transaction instant. Message read
state is the real exception: handling derives from a run whose `trigger` refs
the message, but “seen and not yet handled” cannot be queried. B15.2's
recommended `read-at` is a genuine agent assertion, not a cached inference.

## Current census

“Agent writes?” distinguishes direct ruling-62 transaction data from facts the
indexer/runtime must mint. “Indirect” means the agent authors the cause (for
example a `def`) but a system boundary admits and writes the durable row.

### Namespace

Schema: [`seon.ns.edn:1`](../../../../resources/seon/schemas/seon.ns.edn#L1),
with component binding schemas
[`seon.ns.alias.edn`](../../../../resources/seon/schemas/seon.ns.alias.edn),
[`seon.ns.refer.edn`](../../../../resources/seon/schemas/seon.ns.refer.edn), and
[`seon.ns.import.edn`](../../../../resources/seon/schemas/seon.ns.import.edn).
Writer: [`seon.fn/namespace-row:197`](../../../../src/seon/fn.clj#L197) and
[`seon.fn/index!:1785`](../../../../src/seon/fn.clj#L1785).

| Attribute | Type | Identity? | Date? | Ref→ | Agent writes? | Smell | Proposal |
|---|---|---:|---:|---|---|---|---|
| `:seon.ns/name` | symbol | Yes | No | — | System indexer | Honest Clojure identity | Keep |
| `source`, `doc`, `admission/source` | strings / keyword | No | No; derive row tx | — | System indexer | `source` and `doc` are real authored evidence | Keep |
| `requires` | set ref | No | No | namespace rows | System indexer | Correct graph edge; reverse publics derive through `fn/ns` | Keep |
| `aliases` | set component ref | No | No | alias bindings | System indexer | Child `target-ns` repeats a namespace as a symbol, so the walk cannot follow it | Make `target-ns` a namespace ref |
| `refers` | set component ref | No | No | refer bindings | System indexer | Same symbolic target; `target-name` also stops short of a function row | Store `target` as a function ref; keep only local binding symbol |
| `imports` | set component ref | No | No | import bindings | System indexer | A JVM class has no Seon row; symbol is honest external identity | Keep |

There is deliberately no stored “publics” collection. Publics derive from
reverse `:seon.fn/ns`; adding a list would be a mirror.

### Function/public

Schema: [`seon.fn.edn:1`](../../../../resources/seon/schemas/seon.fn.edn#L1),
[`seon.fn.arity.edn:1`](../../../../resources/seon/schemas/seon.fn.arity.edn#L1),
and [`seon.fn.argument.edn:1`](../../../../resources/seon/schemas/seon.fn.argument.edn#L1).
Writer: [`seon.fn/var-row:328`](../../../../src/seon/fn.clj#L328). The population
includes external identity tombstones, so refs remain stable
([`seon.fn/desired-rows:1698`](../../../../src/seon/fn.clj#L1698)).

| Attribute | Type | Identity? | Date? | Ref→ | Agent writes? | Smell | Proposal |
|---|---|---:|---:|---|---|---|---|
| `:seon.fn/sym` | string | Yes | No; derive definition tx | — | Indirect through admitted `defn`; otherwise indexer | A Clojure symbol is encoded as a string while namespace identity uses `:symbol`; `sym` is terse at the agent surface | New `:seon.fn/symbol :qualified-symbol` identity |
| `ns` | ref | No | No | namespace | Indexer/admission | Honest edge | Keep |
| `source`, `doc` | strings | No | No | — | Indexer/admission | Exact source and prose are distinct | Keep |
| `arglists`, `arglists-override?` | string, boolean | No | No | — | Indexer | `arglists` mirrors structured `arities` for contracted functions | Retain only as explicit external/override evidence; derive ordinary display from arities |
| `private?`, `macro?`, `workload` | booleans / enum | No | No | — | Indexer | Honest declared/analyzed facts | Keep |
| `spec` | string | No | No | — | Indexer/admission | Name says a value, storage is EDN source text | Rename to `contract-source` or make a schema ref; never parse an ambiguously named string |
| `calls` | set ref | No | No | function rows | Indexer | Canonical program graph | Keep |
| `keywords` | set qualified keyword | No | No | — | Indexer | Honest literal-use evidence, not an entity edge | Keep |
| `arities`, `ast` | component refs | No | No | arity/AST rows | Indexer | Structured contract facts are the authority | Keep |
| arity `input`, `output`, `guard`, `*-schema`, `*-refs` | refs / sets of refs | No | No | schema/function rows | Indexer | Queryable contract graph | Keep |
| argument `binding`, `schema`, `rest-*-schema` | component/ref | No | No | binding/schema rows | Indexer | Queryable contract graph | Keep |
| `external-sink`, `projection-boundary`, `effect/capability` | enums / capability value | No | No | — | Indexer | Explicit leaf facts, not rosters | Keep |

The missing “settled-at” from B3 should be a query over the latest identity
datom transaction, not another stored instant. That keeps program identities
as permanent tombstones while still sorting revisions.

### Run

Schema: [`seon.cluster.run.edn:1`](../../../../resources/seon/schemas/seon.cluster.run.edn#L1).
Writers: `open-call`, custody transitions, and close/recovery in
[`seon.cluster.run`](../../../../src/seon/cluster/run.clj).

| Attribute | Type | Identity? | Date? | Ref→ | Agent writes? | Smell | Proposal |
|---|---|---:|---:|---|---|---|---|
| `:seon.cluster.run/id` | string | Yes | No | — | System | Opaque durable identity | Keep |
| `agent` | ref | No | No | agent | System | Required ownership edge | Keep |
| `trigger` | indexed ref | No | No | message/work entity | System | Makes handled message/work derivable | Keep |
| `background-results` | set ref | No | No | result entities | System | Real causal edges | Keep |
| `opened-at`, `closed-at`, `interrupted-at`, `undisposed-at` | instants | No | Yes | — | System | Domain transition times, honest | Keep |
| `opening-commit-id` | UUID | No | No | Datahike commit identity | System | Underivable fork/open evidence | Keep |
| `starting-ns` | ref | No | No | namespace | System | Honest starting world | Keep |
| `supersedes` | set ref | No | No | earlier runs | System | Honest curation history | Keep |
| `process` | string | No | No | — | System only | Custody is presence, but a string is not the process-record ref described by the architecture | Change to a process identity ref when that durable process family lands |
| `plan-digest` | string | No | No | — | System | Digest of frozen ordered sources; valid snapshot evidence | Keep, rename with the ruled `sources-*` wave |
| `forms` | set component ref | No | No | run-form rows | System | Duplicates reverse `eval/run` and owns the wrong half of split transcript | Delete after eval merge |
| `error` | string | No | No | — | System | Parallel free-text failure beside durable `seon.error` facts | Replace with `error` ref or derive reverse `seon.error/run` |

Run status remains correctly derived from presence/absence of custody and
terminal instants. Do not add a status enum.

### Parsed run form (current half one)

Schema: [`seon.cluster.run.form.edn:1`](../../../../resources/seon/schemas/seon.cluster.run.form.edn#L1).
Writer: [`seon.cluster.run/plan-call:622`](../../../../src/seon/cluster/run.clj#L622).

| Attribute | Type | Identity? | Date? | Ref→ | Agent writes? | Smell | Proposal |
|---|---|---:|---:|---|---|---|---|
| `:seon.cluster.run.form/id` | string | Yes | No | — | System parser/planner | Second identity for the same `(run, ordinal)` evaluation | Delete family; use eval identity |
| `run`, `ordinal` | ref, int | No | No | run | System | Repeated on receipt; the pair already determines identity | Move once to eval |
| `author`, `source` | enum, no-history string | No | No | — | System from agent/system reply | These are evaluation intent, not a separate entity | Move to eval; retain source history or refuse non-identical upsert |
| `ns` | ref | No | No | starting namespace | System | Receipt also has `ns`, with unclear start/result semantics | Move as eval `starting-ns` |
| `refreshes` | unique ref | No | No | prior run form | System | Valid relationship, wrong target family | Point to prior eval |
| `fn/calls`, `fn/keywords`, `test/subject` | refs / values | No | No | program/test rows | System analyzer | Valid analysis of source | Move to eval |

### Evaluation/receipt (current half two)

Schema: [`seon.cluster.eval.edn:1`](../../../../resources/seon/schemas/seon.cluster.eval.edn#L1).
Writers: [`receipt-start-call:1034`](../../../../src/seon/cluster/run.clj#L1034)
and [`receipt-settle-call:1616`](../../../../src/seon/cluster/run.clj#L1616).

| Attribute | Type | Identity? | Date? | Ref→ | Agent writes? | Smell | Proposal |
|---|---|---:|---:|---|---|---|---|
| `:seon.cluster.eval/id` | string | Yes | No | — | System | Right family but minted only when execution starts | Mint at parse/plan time |
| `run`, `ordinal` | ref, int | No | No | run | System | Duplicates form half | Keep once on unified eval |
| `at` | instant | No | Yes | — | System | Vague name: specifically receipt start | Rename `started-at` |
| `ns`, `sci.eval/ending-ns` | refs | No | No | namespace rows | System | Namespace ownership crosses families; `ns` meaning is opaque | Unified `starting-ns` and `ending-ns` under eval namespace |
| `result-edn`, `result-blob`, `result-size` | bounded string, blob digest, int | No | No | blob by digest | System settlement | One full blob plus bounded inline window and size is intentional, not duplicate authority | Keep; name inline field `result-preview-edn` when blob exists |
| `error`, `error/kind`, `problems/id`, `triage-edn` | strings/keywords | No | No | — | System settlement | Several parallel error projections; none is a ref to the durable error family | Store a `:seon.cluster.eval/error` ref; keep only display projections proven hot |
| `interrupted-at` | instant | No | Yes | — | System recovery | Honest terminal event | Keep |
| `output` | no-history string | No | No | — | System | Captured printed output is distinct from returned result | Keep, with blob/window policy if it can grow |
| `read-basis-transaction` | basis t | No | No | temporal DB identity | System | Underivable pre-read evidence | Keep; rename `read-basis-t` for project vocabulary |
| `read-evidence` components | vector component refs | No | No | dependency revisions | System | `read-result` stores full query/pull values again; measured duplicate bytes are already ruled for deletion | Keep dependency plan/revision/request only; delete `read-result` |
| `test.accretion/*` counts/status/report | scalars/blob | No | No | report blob | System test settlement | Evaluation-specific evidence, appropriate accretion | Keep |

The unified row has useful absences: no `started-at` means queued; `started-at`
without any terminal result/error/interruption means dangling; a terminal fact
means settled. No evaluation-status enum is needed.

### Message

Schema: [`seon.cluster.message.edn:1`](../../../../resources/seon/schemas/seon.cluster.message.edn#L1).
Writers: inbound transactions and returned-value conversion in
[`seon.cluster.message/inbound-tx:260`](../../../../src/seon/cluster/message.clj#L260)
and [`seon.cluster.message/delivery:311`](../../../../src/seon/cluster/message.clj#L311).
Readers: [`my.message/inbox:104`](../../../../src/my/message.clj#L104) and
[`my.message/read:118`](../../../../src/my/message.clj#L118).

| Attribute | Type | Identity? | Date? | Ref→ | Agent writes? | Smell | Proposal |
|---|---|---:|---:|---|---|---|---|
| `:seon.cluster.message/id` | string | Yes | No | — | Today system; R62 direct | Current IDs depend on delivery position for outbound messages | Agent supplies an opaque ID or system uses transaction-independent UUID |
| `to` | ref | No | No | agent | R62 direct | Correct edge; raw `:seon.cluster.agent/id` string would lose traversal and referential admission | Keep ref; generated form uses a lookup ref |
| `from` | indexed ref | No | No | agent | Today system-derived | For direct agent writes it normally equals transaction `:seon.db/user`, but it is the message's semantic sender and an important walk edge; the system may transact on the sender's behalf | Keep; derive it at the scoped writer for agent sends rather than accept spoofable input |
| `content` | no-history text | No | No | — | R62 direct | Message is immutable evidence, yet `no-history` permits destructive same-ID overwrite | Keep text, keep history, and reject conflicting identity reuse |
| `at` | instant | No | Yes | — | Today system | Sortable but vague about send versus commit | Rename `sent-at`; for outside input it is the accepted/received event time |
| `ordinal` | int | No | No | — | Today system | Delivery-vector position is not message chronology | Delete; order by transaction plus ID |
| `caused-by` | indexed ref | No | No | prior message | R62 direct/derived | Honest causal edge | Keep |
| `about` | indexed ref | No | No | any durable entity | R62 direct | Honest subject edge | Keep |
| `my.message/reason` | enum/value | No | No | — | R62 direct | Declination-specific assertion on the same message | Keep |
| `read-at` | absent | — | Missing | — | R62 direct | Current data cannot distinguish unseen from seen-but-unhandled | Add instant; recipient alone may assert it |

`my.message/read` is a fetch and currently writes nothing. Under R62,
mark-read is not another wrapper: it is one `:db/add` of `read-at`. Because a
message has exactly one `to`, the recipient-specific assertion belongs on the
message. “Handled” remains the distinct reverse relation `run/trigger →
message`.

### Agent def

Schema: [`seon.def.edn:1`](../../../../resources/seon/schemas/seon.def.edn#L1).
Capture occurs in [`seon.sci.eval`](../../../../src/seon/sci/eval.clj#L401),
settlement normalizes rows at
[`seon.cluster.run/def-row:1489`](../../../../src/seon/cluster/run.clj#L1489),
and a turn fork restores them in
[`seon.sci.eval/cluster-ctx`](../../../../src/seon/sci/eval.clj#L1613).

| Attribute | Type | Identity? | Date? | Ref→ | Agent writes? | Smell | Proposal |
|---|---|---:|---:|---|---|---|---|
| `:seon.def/key` | string | Yes | No; derive settlement tx | — | Indirect through `def`/`defn` | Serialized composite identity called “key” | Replace with sole `:seon.def/id` identity |
| `id` | string | No | No | — | Indirect | Repeats namespace/name identity inside `key` | Delete old field; new ID includes agent plus qualified symbol |
| `agent` | ref | No | No | agent | System settlement | Required ownership/walk edge | Keep |
| `ns`, `name` | ref, symbol | No | No | namespace | Indirect | Together name the restore slot; not duplicate if ID remains opaque | Rename pair to `ns` plus `symbol` if clarity warrants; keep edge |
| `admission/source` | keyword | No | No | — | System | Constant provenance repeated per row | Derive from transaction/process or delete if all defs share it |
| `value-edn`, `blob`, `size` | inline EDN, digest, int | No | No | blob by digest | Indirect | Correct small-value/full-blob split | Keep, call inline value a preview only when bounded |
| `unrestorable-reason`, `atom?` | string, boolean | No | No | — | Indirect/system | Honest restore facts | Keep |
| `ordinal` | int | No | No | — | System | Settlement order is real for deterministic restore, but scope is not named | Rename `settlement-ordinal` or document scope; keep |

Agent defs are not direct ruling-62 transactions: SCI must observe the live env,
serialize atoms/values, admit the shape, and settle program rows atomically.
The agent still authors them with ordinary Clojure.

### Note

Schema: [`my.note.edn:1`](../../../../resources/seon/schemas/my.note.edn#L1).
Current transition wrappers are
[`my.note/add!:218`](../../../../src/my/note.clj#L218),
[`my.note/forget!:233`](../../../../src/my/note.clj#L233), and
[`my.note/notes:246`](../../../../src/my/note.clj#L246).

| Attribute | Type | Identity? | Date? | Ref→ | Agent writes? | Smell | Proposal |
|---|---|---:|---:|---|---|---|---|
| `:my.note/id` | string | Yes | No; derive identity tx | — | R62 direct | Global ID means two agents cannot reuse a local label; wrapper compensates with ownership checks | Prefer globally opaque ID; do not promise local labels unless a tuple identity is introduced |
| `agent` | ref | No | No | agent | R62 direct | Required reverse-walk edge; tx author alone is not traversed by the schema walk | Keep and require it to match scoped transaction author at the transaction boundary |
| `content` | text | No | No | — | R62 direct | Honest note body | Keep |
| `about` | ref | No | No | any durable entity | R62 direct | Already present; the proposed walk requirement is satisfied | Keep |

No `created-at` is missing. Sort note identity datoms by transaction
`:db/txInstant`. If notes become editable and UI needs both creation and last
edit, those are two derived transaction queries, not copied instants.

### Plan item

Schema: [`my.plan.item.edn:1`](../../../../resources/seon/schemas/my.plan.item.edn#L1)
and agent anchor extension in
[`my.plan.edn:45`](../../../../resources/seon/schemas/my.plan.edn#L45).
Current single-item transitions begin at
[`my.plan/add!:243`](../../../../src/my/plan.clj#L243); whole-tree compilation is
[`my.plan/plan!:582`](../../../../src/my/plan.clj#L582).

| Attribute | Type | Identity? | Date? | Ref→ | Agent writes? | Smell | Proposal |
|---|---|---:|---:|---|---|---|---|
| `:my.plan.item/id` | string | Yes | No; derive identity tx | — | R62 direct | Same global-ID/local-label tension as notes | Use opaque global ID; labels stay ephemeral input only |
| `title`, `description`, `expected-result` | strings | No | No | — | R62 direct | Honest authored content | Keep |
| `agent` | ref | No | No | agent | R62 direct | Required ownership/walk edge | Keep with scoped-author enforcement |
| `parent` | ref | No | No | plan item | R62 direct | Honest hierarchy | Keep |
| `needs` | set ref | No | No | plan items | R62 direct | Honest dependency graph | Keep |
| `about` | EDN-coded ordered vector of symbol/keyword tokens | No | No | None | R62 direct | The writer validates a guessed name, stores the guess, and every reader resolves again; the walk sees no edge | Replace with ordered component subject rows containing an entity ref |
| `completed-at` | instant | No | Yes | — | R62 direct | Genuine user assertion; absence means open | Keep |
| `agent/my.plan/anchor` | ref | No | No | plan item | R62 direct | Genuine authored focus, not derived | Keep |

Obligations are already correctly derived from open message/run/test facts.
The whole-tree compiler, label resolution, basis fence, and convergence result
are machinery around writing facts. Under R62 the context should teach small
transactions; plan rendering remains a query. The ordered subject component is
needed only because current behavior promises authored subject order—a
cardinality-many ref is a set.

### Error

Schema: [`seon.error.edn:1`](../../../../resources/seon/schemas/seon.error.edn#L1).
Normalization and commit are
[`seon.error/normalize:333`](../../../../src/seon/error.clj#L333) and
[`seon.error/commit-tx:813`](../../../../src/seon/error.clj#L813).

| Attribute | Type | Identity? | Date? | Ref→ | Agent writes? | Smell | Proposal |
|---|---|---:|---:|---|---|---|---|
| `:seon.error/id` | string | Yes | No | — | System only | Honest occurrence identity | Keep |
| `at` | instant | No | Yes | — | System | Genuine observation time; may precede commit | Keep |
| `process` | string | No | No | — | System | Ambiguous beside tx process provenance and durable process identity | Rename `process-instance` or make it a process ref |
| `kind`, `message` | keyword, text | No | No | — | System | Hot query/render projections | Keep |
| `signature` | indexed digest | No | No | — | System | Derived and stored, but it is the recurrence lookup key; recomputing from capped evidence can be impossible | Keep as an explicitly materialized classification key |
| `data-edn`, `capped?`, `throwable-class` | EDN text, boolean, string | No | No | — | System | `data-edn` repeats some projected fields in bytes, but preserves bounded evidence | Keep until evidence becomes one blob plus query projections |
| `proc`, `op`, `cid` | keywords | No | No | — | System | Honest flow provenance | Keep |
| dropped-fault count/digest | int, digest | No | No | — | System | Honest loss observation | Keep |
| `basis-t` | basis t | No | No | temporal database | System | Underivable incident-time basis | Keep |
| `run`, `agent` | indexed refs | No | No | run, agent | System | Required attribution/walk edges | Keep |
| `instrument/*` | refs/EDN evidence | No | No | function/contract rows where typed as refs | System | Honest contract failure evidence | Keep |

The duplicate is outside the row: `commit-tx` stores the rendered error notice
again as a message so `message/to` wakes an agent. When B14 routes wakes from
changed attributes, route `error/agent` directly and remove that message.

### Schedule and task

Schemas:
[`seon.schedule.edn:1`](../../../../resources/seon/schemas/seon.schedule.edn#L1),
[`seon.schedule.task.edn:1`](../../../../resources/seon/schemas/seon.schedule.task.edn#L1),
and [`seon.schedule.fire.edn:1`](../../../../resources/seon/schemas/seon.schedule.fire.edn#L1).
The current writer hand-rosters root tasks in
[`seon.schedule:35`](../../../../src/seon/schedule.clj#L35); creation time is
already derived correctly in
[`task-created-at:217`](../../../../src/seon/schedule.clj#L217).

| Attribute | Type | Identity? | Date? | Ref→ | Agent writes? | Smell | Proposal |
|---|---|---:|---:|---|---|---|---|
| schedule `id` | string | Yes | No; derive identity tx | — | Today system; R62 direct | Honest schedule identity | Keep |
| schedule `expression`, `zone-id` | strings | No | No | — | Today system; R62 direct | Dependency vocabulary is honest cron expression and zone ID | Keep |
| task `id` | string | Yes | No; derive identity tx | — | Today system; R62 direct | Honest task identity | Keep |
| task `owner` | indexed ref | No | No | agent | Today system; R62 direct | Required walk/routing edge | Keep |
| task `function` | indexed ref | No | No | function | Today system; R62 direct | Honest executable relationship | Keep |
| task `schedule` | indexed ref | No | No | schedule | Today system; R62 direct | Honest timing relationship | Keep |
| fire `id` | string | Yes | No | — | System only | Deterministic occurrence identity | Keep |
| fire `task` | indexed ref | No | No | task | System only | Honest occurrence edge | Keep |
| fire `nominal-at`, `observed-at` | instants | No | Yes | — | System only | Both are genuine: scheduled time versus detection time | Keep |

The root maintenance portfolio is currently a hand-authored source constant,
not database data an agent can inspect and edit. R62 should move only the
schedule/task declarations to ordinary transaction data. Fires remain
system-authored consequences.

### Maintenance request and receipt

Schemas:
[`seon.maintenance.request.edn:1`](../../../../resources/seon/schemas/seon.maintenance.request.edn#L1),
[`seon.maintenance.receipt.edn:1`](../../../../resources/seon/schemas/seon.maintenance.receipt.edn#L1),
and [`seon.maintenance.result.edn:1`](../../../../resources/seon/schemas/seon.maintenance.result.edn#L1).
Writer: [`seon.schedule/request-entity:268`](../../../../src/seon/schedule.clj#L268),
[`fire-call:303`](../../../../src/seon/schedule.clj#L303), and
[`settle-call:402`](../../../../src/seon/schedule.clj#L402).

| Attribute | Type | Identity? | Date? | Ref→ | Agent writes? | Smell | Proposal |
|---|---|---:|---:|---|---|---|---|
| request `id` | string | Yes | No | — | System only | Same identity as receipt in practice, but stored on a component | Remove request identity; make it an anonymous component of receipt |
| request `task`, `fire`, `handler`, `agent` | indexed refs | No | Via fire | task, fire, fn, agent | System only | Receipt repeats task/fire/handler; request repeats facts reachable through fire/task, but handler/agent snapshots may differ after task edits | Keep frozen handler/agent; keep fire; derive task from fire; remove duplicate receipt refs |
| request `cluster-name`, roots, log-dir | strings/paths | No | No | — | System only | Legitimate frozen execution world | Keep on request component |
| request `nominal-at`, `observed-at` | instants | No | Yes | — | System only | Exact copies of the referenced fire | Delete; join through fire when constructing the in-memory request value |
| request config fields | ints/ratios | No | No | — | System only | Legitimate frozen config, because later config must not change admitted work | Keep |
| receipt `id` | string | Yes | No | — | System only | Honest execution identity | Keep |
| receipt `fire`, `task`, `handler` | indexed refs | No | Via fire | fire, task, fn | System only | Same relationships repeated in request | Keep only fire directly; frozen handler stays in request; derive task through fire |
| receipt `request` | component ref | No | No | request snapshot | System only | Correct “values carry world” component | Keep, without identity |
| receipt `started-at`, `completed-at`, `interrupted-at` | indexed instants | No | Yes | — | System only | Honest lifecycle facts; absence derives state | Keep |
| receipt `result` | component ref | No | No | maintenance result | System only | Honest bounded result tree | Keep |
| receipt `error` | indexed ref | No | No | error fact | System only | Correct durable failure edge | Keep |
| result `id` | string | Yes | No | — | System only | A component already owned by one receipt does not need global identity | Remove ID; retain typed component tree |
| result operation fields/components | typed scalars/components | No | Operation-specific | nested result evidence | System only | They are concrete evidence, not summaries; some booleans genuinely assert closed outcomes | Keep; collection-first rendering prevents graph flood |

The context walk should reach the task directly from `task/owner` and render a
bounded receipt summary by reverse fire/task joins. It should not follow fifty
request snapshots merely because each repeats `request/agent`.

### Context capture

Schema: [`seon.context.capture.edn:1`](../../../../resources/seon/schemas/seon.context.capture.edn#L1).
Writer: [`seon.context/capture-tx:154`](../../../../src/seon/context.clj#L154).

| Attribute | Type | Identity? | Date? | Ref→ | Agent writes? | Smell | Proposal |
|---|---|---:|---:|---|---|---|---|
| `:seon.context.capture/id` | string | Yes | No; derive identity tx | — | System only | Derived from run plus basis; valid deterministic identity | Keep |
| `run` | ref | No | No | run | System only | Correct path back to agent | Keep |
| `basis-t` | int | No | No | exact temporal database | System only | Essential pre-provider read identity, not a datetime | Keep; optionally name `read-basis-t` consistently |
| `prompt` | no-history text | No | No | — | System only | Exact provider input is authoritative evidence, but same-ID overwrite would erase history | Keep history or refuse a differing upsert |
| `ai.tokens/characters` | int | No | No | — | System only | Deliberate cheap projection for token calibration; not a second prompt | Keep |
| `contributions` | set component refs | No | No | source contribution evidence | System only | Honest provenance; set ordering is appropriate | Keep |
| `error/kind`, `error/message` | keyword/text | No | No | — | System only | Parallel error projection rather than durable error ref | Prefer `capture/error` ref; keep local projections only if capture can fail before error commit |
| `cluster.run/live-processes` | set of process strings | No | No | — | System only | Snapshot is valid but vocabulary/typing belongs to process identity | Store process refs once durable process rows exist |

A capture time is not missing. The transaction instant says when it was
recorded; the run and basis say exactly which world it captured.

## Proposed data model

These are sketches, not drop-in registry files. Every map is open. The change
is a destructive retype/reset—never a migration—and old keys disappear in the
same wave as their last reader.

### 1. One evaluation family

```clojure
#:seon.cluster.eval
{:id          [:string {:seon.db/identity true}]
 :run         :seon.db/ref
 :ordinal     [:int {:min 0}]
 :author      [:enum :agent :system]
 :source      [:string {:min 1}]
 :starting-ns :seon.db/ref
 :refreshes   [:and {:seon.db/unique true} :seon.db/ref]
 :started-at  :inst
 :ending-ns   :seon.db/ref
 :result-preview-edn :string
 :result-blob :seon.blob/digest
 :result-size [:int {:min 0}]
 :error       :seon.db/ref
 :interrupted-at :inst
 :output      :string
 :read-basis-t :seon.db/basis-t
 :read-evidence [:vector {:seon.db/component true} :seon.db/ref]}
```

The parser transacts `id/run/ordinal/author/source/starting-ns` and analysis
facts. Execution accretes `started-at`; settlement accretes exactly one result,
error, or interruption disposition plus diagnostics. Delete
`seon.cluster.run.form.edn`, `run/forms`, both identity constructors, the
run-plus-ordinal settlement join, and `read-result`.

Generated context consequence:

```clojure
;; Inspect this run's evaluation history; source and disposition share a row.
(seon.db/q
 '[:find ?ordinal ?source ?started-at ?result ?error
   :in $ ?run
   :where
   [?evaluation :seon.cluster.eval/run ?run]
   [?evaluation :seon.cluster.eval/ordinal ?ordinal]
   [?evaluation :seon.cluster.eval/source ?source]
   [(get-else $ ?evaluation :seon.cluster.eval/started-at nil) ?started-at]
   [(get-else $ ?evaluation :seon.cluster.eval/result-preview-edn nil) ?result]
   [(get-else $ ?evaluation :seon.cluster.eval/error nil) ?error]]
 [:seon.cluster.run/id "run-…"])
```

The generated form is one query, not a zipper over two sorted collections.

### 2. Program edges are refs; Clojure identities are values

```clojure
#:seon.fn
{:symbol [:qualified-symbol
          {:seon.db/identity true :seon.search/index :symbol}]
 :ns     :seon.db/ref
 :calls  [:set :seon.db/ref]
 :arities [:vector {:seon.db/component true} :seon.db/ref]}

#:seon.ns.alias
{:local     :symbol
 :target-ns :seon.db/ref}

#:seon.ns.refer
{:local  :symbol
 :target :seon.db/ref}
```

Do not store `fn/settled-at`; query the last transaction that asserted a
definition attribute. Do not store ordinary `arglists` beside complete arity
rows. External functions may retain explicitly named raw arglist evidence.

Generated context consequence:

```clojure
;; Show the current public functions in this namespace, newest definition first.
(seon.db/q
 '[:find ?symbol ?doc ?at
   :in $ ?namespace
   :where
   [?fn :seon.fn/ns ?namespace]
   [?fn :seon.fn/symbol ?symbol ?tx]
   [?tx :db/txInstant ?at]
   [(get-else $ ?fn :seon.fn/doc "") ?doc]]
 [:seon.ns/name 'my.agents.root])
```

### 3. Agent-authored messages, notes, and plan facts

```clojure
#:seon.cluster.message
{:id        [:string {:seon.db/identity true}]
 :to        [:and {:seon.db/index true} :seon.db/ref]
 :from      [:and {:seon.db/index true} :seon.db/ref]
 :content   [:string {:min 1 :seon.search/index :text}]
 :caused-by [:and {:seon.db/index true} :seon.db/ref]
 :about     [:and {:seon.db/index true} :seon.db/ref]
 :sent-at   :inst
 :read-at   :inst}

#:my.note
{:id      [:string {:seon.db/identity true}]
 :agent   :seon.db/ref
 :content [:string {:min 1}]
 :about   :seon.db/ref}

#:my.plan.item
{:id              [:string {:seon.db/identity true}]
 :agent           :seon.db/ref
 :title           [:string {:min 1}]
 :description     [:string {:min 1}]
 :parent          :seon.db/ref
 :needs           [:set :seon.db/ref]
 :subjects        [:vector {:seon.db/component true} :seon.db/ref]
 :completed-at    :inst
 :expected-result [:string {:min 1}]}

#:my.plan.item.subject
{:order  [:int {:min 0}]
 :entity :seon.db/ref}
```

The scoped transaction boundary stamps `:seon.db/user`; HEAD's generic
`transact!` currently stamps only a receipt
([`seon.db/stamp-receipt:1867`](../../../../src/seon/db.clj#L1867)), so direct
R62 writes require this authority change. That boundary derives message `from`
for agent sends and refuses a note/plan `agent` ref that differs from the
scoped author. This is one decision at the writer, not a pre-read. Subject
components preserve authored order while giving the walk real edges.

Generated write/read consequences:

```clojure
;; Tell agent review what changed about this plan item.
(seon.db/transact!
 [{:seon.cluster.message/id "msg-018f…"
   :seon.cluster.message/to [:seon.cluster.agent/id "review"]
   :seon.cluster.message/content "The data census is ready."
   :seon.cluster.message/sent-at #inst "2026-09-04T17:45:00.000-00:00"
   :seon.cluster.message/about [:my.plan.item/id "context-data-census"]}])

;; Mark the one-recipient message read; handling is still a separate run fact.
(seon.db/transact!
 [[:db/add [:seon.cluster.message/id "msg-018f…"]
   :seon.cluster.message/read-at #inst "2026-09-04T17:46:00.000-00:00"]])

;; Remember why this function is relevant.
(seon.db/transact!
 [{:my.note/id "note-018f…"
   :my.note/agent [:seon.cluster.agent/id "root"]
   :my.note/content "This is the single transcript settlement owner."
   :my.note/about [:seon.fn/symbol 'seon.cluster.run/receipt-settle-call]}])

;; Add one plan item with ordered, traversable subjects.
(seon.db/transact!
 [{:my.plan.item/id "context-data-census"
   :my.plan.item/agent [:seon.cluster.agent/id "root"]
   :my.plan.item/title "Retype context facts"
   :my.plan.item/subjects
   [{:my.plan.item.subject/order 0
     :my.plan.item.subject/entity
     [:seon.fn/symbol 'seon.cluster.run/receipt-settle-call]}
    {:my.plan.item.subject/order 1
     :my.plan.item.subject/entity
     [:seon.ns/name 'seon.cluster.run]}]}])

;; Complete it; absence of this fact means open.
(seon.db/transact!
 [[:db/add [:my.plan.item/id "context-data-census"]
   :my.plan.item/completed-at #inst "2026-09-04T18:00:00.000-00:00"]])

;; Read notes newest-first without storing created-at.
(seon.db/q
 '[:find ?id ?content ?at
   :in $ ?agent
   :where
   [?note :my.note/agent ?agent]
   [?note :my.note/id ?id ?tx]
   [?note :my.note/content ?content]
   [?tx :db/txInstant ?at]]
 [:seon.cluster.agent/id "root"])
```

Fetching alone changes nothing. The explicit form above is mark-read; a run
that handles the message still writes `run/trigger`.

### 4. Def identity

```clojure
#:seon.def
{:id                 [:string {:seon.db/identity true}]
 :agent              :seon.db/ref
 :ns                 :seon.db/ref
 :name               :symbol
 :value-edn          :string
 :blob               :seon.blob/digest
 :size               [:int {:min 0}]
 :atom?              :boolean
 :unrestorable-reason [:string {:min 1}]
 :settlement-ordinal [:int {:min 0}]}
```

`id` is an opaque stable encoding of `(agent, namespace, name)` and is the only
serialized identity. `agent/ns/name` remain because they are actual query and
restore edges, not because callers should reconstruct the ID. The admission
boundary, not agent transaction data, writes this family.

Generated consequence remains ordinary Clojure:

```clojure
;; Keep a counter in this agent's defs; settlement persists the changed Var.
(def counter (atom 0))

;; Inspect persisted defs only when diagnosing restoration.
(seon.db/q
 '[:find ?ns-name ?name ?ordinal
   :in $ ?agent
   :where
   [?definition :seon.def/agent ?agent]
   [?definition :seon.def/ns ?ns]
   [?ns :seon.ns/name ?ns-name]
   [?definition :seon.def/name ?name]
   [?definition :seon.def/settlement-ordinal ?ordinal]]
 [:seon.cluster.agent/id "root"])
```

### 5. Agent-authored schedule/task, system-authored occurrences

```clojure
#:seon.schedule
{:id         [:string {:seon.db/identity true}]
 :expression [:string {:min 1}]
 :zone-id    [:string {:min 1}]}

#:seon.schedule.task
{:id       [:string {:seon.db/identity true}]
 :owner    [:and {:seon.db/index true} :seon.db/ref]
 :function [:and {:seon.db/index true} :seon.db/ref]
 :schedule [:and {:seon.db/index true} :seon.db/ref]}

#:seon.maintenance.receipt
{:id             [:string {:seon.db/identity true}]
 :fire           [:and {:seon.db/index true} :seon.db/ref]
 :request        [:and {:seon.db/component true} :seon.db/ref]
 :started-at     [:and {:seon.db/index true} :inst]
 :completed-at   [:and {:seon.db/index true} :inst]
 :interrupted-at [:and {:seon.db/index true} :inst]
 :result         [:and {:seon.db/component true} :seon.db/ref]
 :error          [:and {:seon.db/index true} :seon.db/ref]}

#:seon.maintenance.request
{:handler         :seon.db/ref
 :agent           :seon.db/ref
 :cluster-name    :seon.boot/cluster-name
 :repository-root :seon.operator/repository-root
 :managed-root    :seon.operator/managed-root
 :log-dir         :seon.boot/log-dir
 ;; frozen config fields remain here
 }
```

The request is an anonymous component and carries the frozen execution world.
The receipt points to the fire; fire points to task; task points to owner,
function, and schedule. Do not repeat those refs or fire times on every layer.

Generated consequence:

```clojure
;; Ask root to run maintenance/reap every fifteen minutes in UTC.
(seon.db/transact!
 [{:seon.schedule/id "every-15m"
   :seon.schedule/expression "0 */15 * * * *"
   :seon.schedule/zone-id "UTC"}
  {:seon.schedule.task/id "root-reap"
   :seon.schedule.task/owner [:seon.cluster.agent/id "root"]
   :seon.schedule.task/function [:seon.fn/symbol 'seon.maintenance/reap]
   :seon.schedule.task/schedule [:seon.schedule/id "every-15m"]}])

;; List this agent's tasks and their most recent fire.
(seon.db/q
 '[:find ?task-id ?expression (max ?nominal-at)
   :in $ ?agent
   :where
   [?task :seon.schedule.task/owner ?agent]
   [?task :seon.schedule.task/id ?task-id]
   [?task :seon.schedule.task/schedule ?schedule]
   [?schedule :seon.schedule/expression ?expression]
   [?fire :seon.schedule.fire/task ?task]
   [?fire :seon.schedule.fire/nominal-at ?nominal-at]]
 [:seon.cluster.agent/id "root"])
```

### 6. Errors and captures point, rather than copy

```clojure
;; additions/retypes only
#:seon.error{:process-instance :seon.db/ref}
#:seon.context.capture
{:error          :seon.db/ref
 :live-processes [:set :seon.db/ref]}
```

Keep error occurrence time and capture basis. Delete the stored rendered error
message once the attribute watcher can wake the attributed agent. A capture
references a durable error instead of copying kind/message when an error fact
exists; an early capture failure may still be committed atomically with that
error.

Generated consequence:

```clojure
;; Inspect errors attributed to this agent, newest first.
(seon.db/q
 '[:find ?id ?kind ?message ?at
   :in $ ?agent
   :where
   [?error :seon.error/agent ?agent]
   [?error :seon.error/id ?id]
   [?error :seon.error/kind ?kind]
   [?error :seon.error/message ?message]
   [?error :seon.error/at ?at]]
 [:seon.cluster.agent/id "root"])
```

## Price and decision

Exactly three viable scopes exist. The first is recommended because it removes
the split mechanisms before the context generator makes them public syntax.

| Option | Guarantee | Cost | Give-up |
|---|---|---|---|
| **1. Coherent destructive retype (recommended)** | Every rendered neighborhood follows real refs; one evaluation row owns intent through settlement; agent-authored note/plan/message/schedule facts use ordinary transactions; chronology is domain time or Datahike time, never both | About 18 schema files; `src/seon/cluster/run.clj`, `cluster/message.clj`, `sci/eval.clj`, `fn.clj`, `my/note.clj`, `my/plan.clj`, `error.clj`, `schedule.clj`, `context.clj`; render/query owners and focused tests. Retype, `bin/seon init`, destructive reset/refork; no migration | Existing clusters and stored histories are discarded, as already ruled |
| **2. Smaller transcript-and-plan retype** | Removes the two shapes that would most poison generated forms: split eval and token subjects | About 7 schema files plus run, plan, transcript render/query owners and tests; destructive reset/refork | Messages retain indirect delivery, errors retain duplicate notice messages, maintenance remains a source roster, and names stay inconsistent |
| **3. Generate forms over HEAD** | Fastest route to a demonstrable context generator; no data retype | Context generator/render work and query tests only | Publishes joins, token resolution, wrappers, and duplicate durable facts as the agent's learned API; contradicts ruling 62's “data first” purpose |

Option 1 should be sliced into reset-boundary commits but designed as one model:

1. install the new schema keys and change all writers/readers in the same
   branch;
2. delete old schemas, wrappers, split joins, and rendered error-message writes;
3. publish current source with `bin/seon init`;
4. destructively reset/refork isolated proof clusters; and
5. prove generated write/read forms against the freshly forked data.

There is no migration plan. Any compatibility period would create the second
mechanism forbidden by the repository's design law.

## Consequences for the context walk

- Namespace → function, alias, refer, call, contract, note, and plan-subject
  edges are direct refs. No name resolution occurs during a walk.
- Agent → message, run, note, plan item, task, and error collections are reverse
  ref queries. Collection-first rendering can count and sort before expansion.
- Run → eval is one reverse ref query. Every result is paired with its source by
  entity identity, not collection position.
- Message “unread” is absence of `read-at`; “unhandled” is independently the
  absence of reverse `run/trigger`. No boolean mirrors either assertion.
- Dates sort from domain instants when present, otherwise from identity-datom
  transaction time. Missing dates are typed only when neither exists.
- Maintenance history is reached through task → fire → receipt. Frozen request
  data stays behind the receipt and is expanded only on demand.
- Captures remain system evidence and do not become an agent writing surface.

The smallest stable generated language is therefore ordinary Clojure plus
`seon.db/q`, `pull`, and `transact!`. Convenience functions may render as
documentation, but the durable model no longer requires them to make the data
coherent.
