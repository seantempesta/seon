---
type: research
status: active
tags: [research, agent, runtime]
---

# Per-form effect visibility for session curation

## Verdict

Post-execution visibility is sufficient to pin every request that crossed
`seon.effect` and every ordinary message committed by the run loop. It is not
yet sufficient to trace an agent-issued `seon.db/transact!` to its issuing
form, nor to query a normalized capability family such as `fs` or `web`.
Pre-execution reachability can identify capability owners from an indexed
function root, but an arbitrary run form has no durable call-root or unresolved
edge facts. It is therefore advisory, not a curation safety proof.

The safe current rule is:

- pin a form when any `:seon.effect` receipt exists for its run and ordinal;
- pin a form when its terminal transaction also created a message to another
  agent;
- do not pin a database write merely because it changed the branch: a fork
  from the exact pre-write commit can re-derive that change without changing
  the original branch;
- nevertheless fail closed on a form with an untraceable database write until
  transaction-to-form causation is recorded, because an arbitrary transaction
  can create routed facts or encode data later consumed outside the branch;
  and
- treat static reachability as supporting evidence only. Missing or dynamic
  calls must not be read as capability-free.

I read
[docs/prds/sci-execution-runtime/plan/bootstrap-vector-design-2026-08-01.md](../plan/bootstrap-vector-design-2026-08-01.md)
end to end before this investigation. Its run-loop inventory identifies
per-form receipt start, evaluation, and terminal settlement as one ordered
fold
(`docs/prds/sci-execution-runtime/plan/bootstrap-vector-design-2026-08-01.md:45-55`),
and its exam design uses branches forked at exact commits
(`docs/prds/sci-execution-runtime/plan/bootstrap-vector-design-2026-08-01.md:280-286,346-354`).
I also read
[docs/prds/sci-execution-runtime/research/workload-classification-2026-07-28.md](workload-classification-2026-07-28.md)
end to end. That report defines reachability from indexed call edges, leaf
metadata, and fail-closed unresolved edges
(`docs/prds/sci-execution-runtime/research/workload-classification-2026-07-28.md:71-103`),
while the current source below shows which parts have since landed.

## Dependency ledger and probe boundary

The dependency sources inspected at the live checkout are:

- Datahike commit `574c5f0f0db9411d1982769f14512cb24ef719da`, especially
  `reference-code/datahike/src/datahike/versioning.cljc`;
- core.async commit `dc35f3e0d7bc2eef502e77982f48641f025c8051`, whose Flow
  workload behavior was already measured in the named workload report; and
- SCI commit `2db3358cba913b6fbbe49c7b5b34d7ac72715924`; all SCI claims
  below cite first-party integration source at the integration boundary.

The live probe used only scratch cluster `session-curation-effects`, started
with:

```text
$ bin/seon start session-curation-effects
cluster session-curation-effects started in pid 3885
prepl 127.0.0.1:60957
web http://127.0.0.1:7942
```

The probe created agents `curation-a` and `curation-b`, then executed run
`curation-effects-run-1` with three ordered forms:

```clojure
(my.fs/read {:my.fs/path "deps.edn" :my.fs/max-bytes 16
             :my.fs/encoding :utf-8})
(my.message/send "curation-b" "effect visibility probe")
(seon.db/transact!
 [{:seon.cluster.message/content "curation branch-local write"}])
```

The real run-loop turn returned:

```clojure
{:next {:situation :resume
        :run-id "curation-effects-run-1"
        :agent-id "curation-a"
        :ordinal 0}
 :turn {:agent-id "curation-a"
        :situation :resume
        :forms-run 3
        :outcome :released
        :run-id "curation-effects-run-1"}}
```

## 1. The effect door records a request receipt before dispatch

`seon.effect` defines request identity as
`[run-id form-ordinal effect-ordinal]`, commits the receipt before invoking the
handler, and never dispatches an already-recorded identity
(`src/seon/effect.clj:2-7,194-208`). During evaluation, `seon.sci.eval` binds
the ambient branch connection, run ID, form ordinal, agent ID, cluster, and a
per-form effect counter (`src/seon/sci/eval.clj:1574-1589`). `request*` then:

- reads the requesting function row and its declared handler
  (`src/seon/effect.clj:410-419`);
- derives the three-part request identity (`:446-450`);
- builds a receipt carrying the run ref, owner ref, scalar form ordinal,
  effect ordinal, canonical request EDN, and open instant (`:453-470`);
- commits that open receipt before dispatch (`:471-474`); and
- invokes the handler only after the open transaction succeeds
  (`:477-505`).

The receipt schema requires those fields and optionally carries terminal
result, duration, settled instant, or interrupted instant
(`resources/seon/schemas/seon.effect.edn:17-50`). It has no direct ref to a
`:seon.cluster.run.form` entity or `:seon.cluster.eval` receipt: the durable
link is the run ref plus scalar form ordinal
(`resources/seon/schemas/seon.effect.edn:22-28,73`). The form and eval schemas
independently carry the same run and ordinal
(`resources/seon/schemas/seon.cluster.run.form.edn:8-18`;
`resources/seon/schemas/seon.cluster.eval.edn:17-27`).

### Exact query: door requests issued by forms in run R

This query includes open, settled, and interrupted requests because it uses
only required receipt attributes:

```clojure
(seon.db/q
 '[:find ?ordinal ?form-source ?eval-id ?effect-id ?owner ?handler ?request
   :in $ ?run-id
   :where
   [?run :seon.cluster.run/id ?run-id]
   [?form :seon.cluster.run.form/run ?run]
   [?form :seon.cluster.run.form/ordinal ?ordinal]
   [?form :seon.cluster.run.form/source ?form-source]
   [?eval :seon.cluster.eval/run ?run]
   [?eval :seon.cluster.eval/ordinal ?ordinal]
   [?eval :seon.cluster.eval/id ?eval-id]
   [?effect :seon.effect/run ?run]
   [?effect :seon.effect/form-ordinal ?ordinal]
   [?effect :seon.effect/id ?effect-id]
   [?effect :seon.effect/owner ?owner-eid]
   [?owner-eid :seon.fn/sym ?owner]
   [?owner-eid :seon.effect/capability ?handler]
   [?effect :seon.effect/request-edn ?request]]
 database "curation-effects-run-1")
```

The live query, augmented with the optional settled result, returned one
request. The digest is shortened below; the remaining fields are verbatim:

```clojure
#{[0
   "(my.fs/read {:my.fs/path \"deps.edn\" :my.fs/max-bytes 16 :my.fs/encoding :utf-8})"
   "[\"curation-effects-run-1\" 0]"
   "[\"curation-effects-run-1\" 0 0]"
   "my.fs/read"
   "seon.fs.jvm/read"
   "#:my.fs{:path \"deps.edn\", :max-bytes 16, :encoding :utf-8}"
   "#:my.fs{:path \"deps.edn\", :digest \"f745...e38c2\",
             :file-bytes 7004, :byte-offset 0, :bytes-read 16,
             :eof? false, :text \"{;; THE SYSTEM (\"}"]}
```

This is an exact query for the requesting function and declared handler, not
for a normalized family. The database has `:seon.effect/capability` whose
value is a qualified handler symbol (`resources/seon/schemas/seon.effect.edn:1`),
but no `:seon.effect/family`. Deriving `fs` by splitting the owner or handler
namespace would be a naming convention rather than a fact. If curation needs
the closed answer `fs/web/llm/db`, that family declaration is missing.

For pinning, the open receipt alone is decisive. A terminal result is not
required: once the receipt was committed, the handler may have changed remote
state even if the process died before terminal settlement. The duplicate
identity refusal prevents redispatch but cannot prove that an interrupted
handler did nothing (`src/seon/effect.clj:194-208`).

## 2. Message delivery is traceable through the terminal transaction

`my.message/send` is a pure constructor. It reads and commits nothing; the
run loop interprets its returned value and commits the message in the form's
terminal transaction (`src/my/message.clj:12-23,100-123`). Delivery derives a
message ID from run ID, form ordinal, and result index
(`src/seon/cluster/message.clj:195-201`), then constructs a message row with
recipient, sender, content, time, and optional causal refs
(`src/seon/cluster/message.clj:367-421`).

The message entity does not store a run, form, or eval ref
(`resources/seon/schemas/seon.cluster.message.edn:47-73`). The trace is still
queryable without parsing its ID: the loop deliberately places both message
rows and the terminal eval receipt in one `seon.db/transact!`
(`src/seon/cluster/loop.clj:1556-1562,1586-1621`). A committed
`:seon.cluster.message/to` fact is delivery: it is the wake attribute, and its
commit wakes the recipient's agent graph; there is no second queue or
acknowledgement (`src/seon/cluster/message.clj:1-16`).

### Exact query: this form delivered a message to agent B

```clojure
(seon.db/q
 '[:find ?ordinal ?source ?eval-id ?message-id ?from-id ?to-id ?content ?tx
   :in $ ?run-id
   :where
   [?run :seon.cluster.run/id ?run-id]
   [?form :seon.cluster.run.form/run ?run]
   [?form :seon.cluster.run.form/ordinal ?ordinal]
   [?form :seon.cluster.run.form/source ?source]
   [?eval :seon.cluster.eval/run ?run]
   [?eval :seon.cluster.eval/ordinal ?ordinal]
   [?eval :seon.cluster.eval/id ?eval-id]
   (or [?eval :seon.cluster.eval/result-edn _ ?tx]
       [?eval :seon.cluster.eval/result-blob _ ?tx])
   [?message ?changed-attribute _ ?tx]
   [?message :seon.cluster.message/id ?message-id]
   [?message :seon.cluster.message/from ?from]
   [?message :seon.cluster.message/to ?to]
   [?from :seon.cluster.agent/id ?from-id]
   [?to :seon.cluster.agent/id ?to-id]
   [?message :seon.cluster.message/content ?content]]
 database "curation-effects-run-1")
```

Live result:

```clojure
#{[1
   "(my.message/send \"curation-b\" \"effect visibility probe\")"
   "[\"curation-effects-run-1\" 1]"
   "curation-effects-run-1-1-message-0"
   "curation-a"
   "curation-b"
   "effect visibility probe"
   536871017]}
```

The transaction join is stronger than parsing the derived message ID and
already answers the safety question. A direct lineage ref would make the
relationship more obvious and independently constrained, but it is not
required for the current ordinary-message query.

## 3. `seon.db/transact!` writes have no issuing-form provenance

During an eval, `seon.db/*conn*` is bound to the agent's cluster branch
connection (`src/seon/sci/eval.clj:1574-1577`). `seon.db/transact!` passes the
transaction directly to Datahike, using that ambient connection when no
explicit connection is supplied (`src/seon/db.clj:880-887,916-940`). It does
not call `seon.effect/request!`, add a request identity, or attach run/form
transaction metadata.

The scratch form's write and terminal receipt were separate transactions.
The instants are elided from this selected probe output:

```clojure
{:write [[14204 536871020 #inst "2026-08-04T..."]]
 :tx-entity {:db/id 536871020
             :db/txInstant #inst "2026-08-04T..."}
 :terminal-receipt-transactions [[536871022]]}
```

The exact write query was:

```clojure
(seon.db/q
 '[:find ?e ?tx ?at
   :where
   [?e :seon.cluster.message/content "curation branch-local write" ?tx]
   [?tx :db/txInstant ?at]]
 database)
```

Pulling transaction entity `536871020` returned only `:db/id` and
`:db/txInstant`. There was no run, form, eval, user, or process connection.
The form's eval result happened to contain a serialized Datahike transaction
report, but serialized output is not a Datalog edge and is neither a complete
nor stable provenance mechanism.

### Branch reasoning and fork probe

Datahike can create a branch from an exact commit UUID; it reads the stored
database at that commit and publishes an independently named branch
(`reference-code/datahike/src/datahike/versioning.cljc:237-291`). Seon's one
registry owner passes either an exact commit or branch to that operation
(`src/seon/cluster/registry.clj:160-198`), and normal cluster creation requires
an exact immutable source commit (`src/seon/cluster/registry.clj:200-222`).

The live probe found the commit immediately before the three-form run, forked
temporary branch `:curation-effect-fork-probe` from that exact commit, queried
the branch-local write, replayed it on the fork, and retired the branch:

```clojure
{:fork-before 0
 :fork-after-replay 1
 :original-after 1
 :fork-effect-receipts 0}
```

Therefore an ordinary database write is not pinned merely for changing branch
facts. If curation forks from the exact pre-run or pre-form commit, replay
reconstructs the write on the fork while the original branch remains at one
copy. Forking from a post-write commit and replaying is a different operation;
upsert, uniqueness, and transaction-function semantics decide whether that is
idempotent.

This conclusion is limited to branch-contained database state. A transaction
can itself assert routed attributes such as `:seon.cluster.message/to`, and a
live cluster routes that attribute as a wake
(`src/seon/cluster/wake.clj:78-93`). It can also write facts later interpreted
by code that crosses the effect door. Until direct transaction causation and
the resulting externally visible consequences are queryable, arbitrary
agent-issued writes should fail closed for curation even though the database
storage operation itself is fork-replayable.

## 4. Static prediction before execution is incomplete for forms

The current indexer does record first-party call edges. It accepts only
first-party caller and target functions from clj-kondo's resolved usages
(`src/seon/fn.clj:209-237`), then stores `:seon.fn/calls`, optional
`:seon.fn/workload`, and optional `:seon.effect/capability` on each function
row (`src/seon/fn.clj:292-349`). Admission verifies that a declared capability
owner is `:io`, names a private schema'd handler, and reaches
`seon.effect/request!` (`src/seon/fn.clj:453-529`). These attributes are owned
program-row facts (`src/seon/program.cljc:22-30`).

`seon.effect/capabilities` is the exact landed reachability query: recursive
Datalog rules walk `:seon.fn/calls`, select rows carrying
`:seon.effect/capability`, and include a root that is itself a capability owner
(`src/seon/effect.clj:133-158`). The scratch database reported:

```clojure
{:capabilities
 {my.fs/read #{"my.fs/read"}
  my.message/send #{}
  seon.db/transact! #{}}
 :rows
 {my.fs/read
  {:seon.fn/sym "my.fs/read"
   :seon.fn/workload :io
   :seon.effect/capability seon.fs.jvm/read
   :seon.fn/calls [{:seon.fn/sym "seon.effect/request!"}]}
  my.message/send
  {:seon.fn/sym "my.message/send"
   :seon.fn/calls [{:seon.fn/sym "my.message/send-value"}]}
  seon.db/transact!
  {:seon.fn/sym "seon.db/transact!"
   :seon.fn/calls
   [{:seon.fn/sym "seon.db/connection?"}
    {:seon.fn/sym "seon.db/current-connection"}
    {:seon.fn/sym "seon.db/transact-call"}
    ...]}}
```

The same probe pulled form 0. Its complete entity had only ID, run, ordinal,
source, and namespace—the declared form schema's complete shape
(`resources/seon/schemas/seon.cluster.run.form.edn:1-24`). It had no call roots,
call edges, capability set, or uncertainty facts.

The distinction matters:

- For a known indexed function root, `seon.effect/capabilities` answers whether
  its recorded call graph reaches a declared door owner.
- For a direct form, there is no root function row to give that query. The
  existing form analyzer currently returns source plus lint findings, not call
  roots (`src/seon/fn/analyzer.clj:319-365`).
- Agent-authored contracted functions are published from the SCI reader's
  declaration. That row records identity, namespace, doc, contract, workload,
  and arities, but no `:seon.fn/calls` or capability marker
  (`src/seon/sci/reader.cljc:238-286`; `src/seon/sci/eval.clj:269-292`). An
  empty edge set is therefore ambiguous between "calls nothing" and "calls
  were never indexed."
- Dynamic invocation, higher-order targets, and unresolved symbols do not
  become complete first-party call edges because the indexer admits only
  resolved first-party caller/target pairs (`src/seon/fn.clj:209-237`).
- Workload is a scheduling fact, not an effect fact. `:io` does not by itself
  say external state changed, and both pure value messaging and direct
  database writes correctly reach no door owner.

There is one useful nearby mechanism, but it is not a pre-execution form
query. After a defining form executes, SCI gathers resolved referenced Vars,
unproven call-position Vars, nondeterministic built-ins, and impure built-ins
(`src/seon/sci/eval.clj:364-466,1660-1701`). The loop uses that evidence to
decide whether a changed session definition is restorable and fails closed on
missing program rows or capability reachability
(`src/seon/cluster/loop.clj:343-369,386-463`). It then removes the detailed
sets before storing the session-image row (`src/seon/cluster/loop.clj:415-443`).
It covers definitions rather than every executed form and occurs after the
effect opportunity, so it cannot currently certify a form before curation
replay.

## 5. Honest missing-fact list

The complete per-form effect trace is blocked by these missing facts:

1. **Database transaction causation.** A transaction issued through ambient
   `seon.db/transact!` has no ref to the run, form, or eval that caused it. The
   transaction's datoms exist, but "which form wrote them?" is not a query.
2. **Normalized capability family.** Door receipts join to an exact owner and
   handler, but no declared fact says `fs`, `web`, `llm`, or `db`. Namespace
   parsing would be a forbidden inferred classification.
3. **Per-form analyzed call roots.** A run form stores source but no resolved
   function roots from which capability reachability can begin.
4. **Per-form uncertainty.** No durable form fact distinguishes a complete
   static call projection from dynamic call, open higher-order target,
   unresolved symbol, host interop, or an agent-authored function lacking
   indexed edges. Absence of an edge cannot prove absence of a call.
5. **Agent-authored call edges.** Runtime SCI declarations do not publish
   `:seon.fn/calls`, so later forms calling those functions cannot obtain the
   same reachability proof as first-party source.
6. **Door outcome semantics.** A receipt proves dispatch was possible and is
   sufficient to pin conservatively, but handler-specific external identity,
   mutation, and delivery outcome remain encoded in request/result EDN rather
   than queryable attributes. An interrupted open receipt is necessarily
   "may have happened," not proof of either success or absence.
7. **Direct form-to-message lineage.** Ordinary sends are queryable today by
   joining the terminal transaction, but the message schema does not declare
   that relationship directly. This is an explicitness and referential
   integrity gap, not a blocker for the current query.
8. **A derived per-form curation answer.** There is no query owner that unions
   door receipts, message delivery, database-write causation, and static
   uncertainty into `replayable` versus `pinned` with concrete reasons. The
   answer should remain derived from the facts above rather than stored as a
   mutable boolean.

These gaps also expose one architectural falsifier: any external effect that
bypasses `seon.effect` will be invisible to door receipts. That is not a reason
for text scanning or a second tracing mechanism; it is evidence that the
capability leaf or its call graph is undeclared.

## Render-quality observations

The live research surfaced two ugly results:

- creating the two scratch agents returned an approximately 3,021,000-byte
  blob for two transaction reports, even though the useful answer was only
  their identities and committed status; and
- the one-datom `seon.db/transact!` form produced an approximately
  1,988,599-byte `:seon.cluster.eval/result-edn` containing a projected
  `datahike.db.TxReport`, including large `db-before` and `db-after` windows.

The latter made a trivial database write nearly unreadable and is especially
misleading here: a huge rendered transaction report looks like provenance but
does not create queryable causation. The MCP preflight projection also marked a
4,489-byte static reachability result as windowed and blob-backed; its useful
payload was under a dozen small rows. These are render defects, not evidence
failures.
