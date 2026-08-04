---
type: research
status: active
tags: [research, sci, namespace, agent, session-curation]
---

# Session-curation namespace semantics (2026-08-04)

## Scope and verdict

I read
[docs/prds/sci-execution-runtime/plan/bootstrap-vector-design-2026-08-01.md](../plan/bootstrap-vector-design-2026-08-01.md)
end to end, all 426 lines, before designing or running these probes. Its
mechanical premise is that the bootstrap is an ordinary preplanned run whose
forms pass through ordinary receipt settlement and durable declaration
installation
([docs/prds/sci-execution-runtime/plan/bootstrap-vector-design-2026-08-01.md:31-70](../plan/bootstrap-vector-design-2026-08-01.md)). Inline session
curation therefore has to preserve the same namespace, receipt, and definition
semantics rather than introduce a second session mechanism.

The answers are:

- `:seon.cluster.agent/namespace` is a cardinality-one, unique-value ref, so a
  namespace can be assigned to at most one agent. Agent ID and namespace name
  are independent identities; the current evaluator nevertheless still derives
  `my.agents.<id>` instead of consistently reading the assigned ref
  (`resources/seon/schemas/seon.cluster.agent.edn:48-63`;
  `src/seon/sci/eval.clj:239-246`).
- A qualified `(defn other.ns/f ...)` from a different current namespace is
  rejected. `(in-ns 'other.ns)` followed by an unqualified `defn`, `intern`
  into an existing namespace, and `alter-var-root` of a foreign SCI Var all
  work and are immediately visible through the cluster's shared SCI context
  (probe P1 below; `reference-code/sci/src/sci/impl/analyzer.cljc:809-820`;
  `reference-code/sci/src/sci/impl/namespaces.cljc:452-456,610-637,816-840`).
- `install-program-row!` is not where a definition first becomes a database
  program fact. The atomic database admission is
  `run/receipt-settle-call` -> `program-row-tx`; `install-program-row!` runs
  only after that transaction succeeds and synchronizes the committed row into
  the live context (`src/seon/cluster/run.clj:669-768,790-843`;
  `src/seon/cluster/loop.clj:1612-1629`;
  `src/seon/sci/eval.clj:637-700`).
- An ownership refusal is consistent with ruling #20 only as an evidence-based
  integrity check on durable definition writes, never as a call restriction.
  It is not a security boundary. Ruling #27 explicitly leaves this write check
  open while preserving all-function callability
  ([docs/prds/sci-execution-runtime/plan/README.md:1663-1678,1779-1799](../plan/README.md);
  [docs/seon/architecture/agent-runtime.md:144-171](../../../seon/architecture/agent-runtime.md)).
- A query can answer both the reader-attributed namespace of a plan form and
  the namespace in which its evaluation started by joining form and receipt on
  run plus ordinal. It cannot directly answer the successful evaluation's
  ending namespace today: that value is returned in memory and drives the next
  form, but the durable receipt stores the starting namespace
  (`resources/seon/schemas/seon.cluster.run.form.edn:8-20`;
  `resources/seon/schemas/seon.cluster.eval.edn:10-24`;
  `src/seon/sci/eval.clj:1450-1468`;
  `src/seon/cluster/loop.clj:1657-1660`).

## Evidence basis

The source dependency is the checked-out maintained SCI revision
`2db3358cba913b6fbbe49c7b5b34d7ac72715924`
(`v0.14.56-18-g2db3358`). The relevant implementation is under
`reference-code/sci/`; no installed archive or remembered API behavior was
used. The revision values are probe output from `git -C reference-code/sci
rev-parse HEAD` and `git -C reference-code/sci describe --always --tags`.

All Seon probes below ran on the dedicated cluster
`session-curation-ns-fresh` under the isolated repository-local operator root
`tmp/session-curation-ns-root`. It was initialized and booted from the current
tree with:

```text
bin/seon --root tmp/session-curation-ns-root init
bin/seon --root tmp/session-curation-ns-root start session-curation-ns-fresh
```

The successful boot advertised HTTP port 7730 and prepl port 62231. No probe
read or mutated the default cluster. The first attempted scratch cluster had
joined an older already-running JVM, so its observations were discarded and
every reported probe was repeated in the isolated current-code JVM.

## 1. Agent identity and namespace assignment

Formal creation accepts an agent ID and namespace name as separate inputs,
creates/upserts the namespace entity by `:seon.ns/name`, and relates the agent
to it by ref (`src/seon/cluster/agent.clj:86-101`). Agent ID and namespace name
are separate database identities: `:seon.cluster.agent/id` is an identity
string, while `:seon.ns/name` is an identity symbol
(`resources/seon/schemas/seon.cluster.agent.edn:48-63`;
`resources/seon/schemas/seon.ns.edn:4-7`). There is no equality constraint
between them.

The agent entity makes the namespace ref optional, while the formal
`creation-request` requires a namespace name
(`resources/seon/schemas/seon.cluster.agent.edn:1-14,48-53`). The ref is
cardinality one by default and carries `:seon.db/unique true`, which the schema
bridge installs as Datahike unique-value (`resources/seon/schemas/seon.cluster.agent.edn:62-63`).
The existing regression proves ordinary reassignment and rejects assigning one
namespace to two agents with Datahike `:transact/unique`
(`test/seon/cluster/agent_namespace_test.clj:34-71`).

Probe P0 queried the isolated cluster's installed schema and assignment:

```text
(select-keys (get (:schema (seon.db/db))
                  :seon.cluster.agent/namespace)
             [:db/valueType :db/cardinality :db/unique])
=> #:db{:valueType :db.type/ref,
       :cardinality :db.cardinality/one,
       :unique :db.unique/value}

(seon.db/q '[:find ?agent-id ?namespace-name
             :where
             [?agent :seon.cluster.agent/id ?agent-id]
             [?agent :seon.cluster.agent/namespace ?namespace]
             [?namespace :seon.ns/name ?namespace-name]])
=> #{["root" my.agents.root]}
```

`agent/owner-of` is already the inverse query from namespace name to assigned
agent ID (`src/seon/cluster/agent.clj:103-114`). Current reply parsing and the
evaluation fallback do not use that assignment consistently: both call the
ID-derived `agent-namespace` (`src/seon/cluster/loop.clj:1285-1306,1525-1534`;
`src/seon/sci/eval.clj:239-246`). This is the already-filed blocker
[docs/seon/issues/evals-ignore-the-agents-assigned-namespace.md:8-19,61-70](../../../seon/issues/evals-ignore-the-agents-assigned-namespace.md).

### What breaks when two agents evaluate in one namespace

The database prevents two formal owners, but the SCI runtime does not prevent
another agent from entering or mutating that namespace. Every agent's run
reduces over the cluster's one live context
(`src/seon/cluster/loop.clj:1466-1476`), and an agent definition is intended to
be immediately cluster-wide
([docs/seon/architecture/agent-runtime.md:149-154](../../../seon/architecture/agent-runtime.md)).

The concrete consequences are:

- Both evaluations address the same qualified SCI Vars. Probe P1 redefined
  `session.curated.other/f` after an earlier definition and a later evaluation
  immediately returned `:second-writer`; the result is order-dependent shared
  mutation, not two agent-local definitions.
- Contracted functions collide on the global identity `:seon.fn/sym`, and an
  existing row is exactly replaced (`resources/seon/schemas/seon.fn.edn:11-18,44-46`;
  `src/seon/cluster/run.clj:763-768`). Uncontracted session definitions collide
  on the qualified string `:seon.code.def/id`
  (`resources/seon/schemas/seon.code.def.edn:3-9,26-29`;
  `src/seon/sci/eval.clj:443-468`).
- A red form is routed to the owner of its parse-time form namespace, with the
  run author only as fallback. A second agent writing in an owned namespace can
  therefore create repair work assigned to that namespace's owner rather than
  to itself (`src/seon/cluster/work.clj:219-241`;
  `src/seon/problems.clj:158-209,211-228`).

These are integrity and coordination failures with evidence in the current
runtime; they do not imply that calling another namespace's functions should
be restricted.

## 2. Live SCI namespace and Var probes

### P1. Qualified `defn`, `in-ns`, visibility, and overwrite

All forms below used MCP `eval_clj`, mode `door`, namespace `user`, against the
isolated cluster:

```text
(defn session.curated.other/f [] :qualified-defn)
=> :seon.cluster.eval/error "Var name should be simple symbol."
   :seon.eval/outcome "error", phase "analysis", ending-ns user

(do (in-ns 'session.curated.other)
    (defn f [] :via-in-ns)
    [(ns-name *ns*) (f)])
=> [session.curated.other :via-in-ns]
   ending-ns session.curated.other

[(session.curated.other/f) (ns-name *ns*)]
=> [:via-in-ns user]

(do (in-ns 'session.curated.other)
    (defn f [] :second-writer)
    (f))
=> :second-writer

(session.curated.other/f)
=> :second-writer
```

SCI expands `defn` to `def` (`reference-code/sci/src/sci/impl/fns.cljc:320-361`).
Its `def` analyzer accepts a simple symbol or a qualified symbol whose qualifier
equals the current namespace, and rejects a different qualifier with the exact
probe message (`reference-code/sci/src/sci/impl/analyzer.cljc:809-820`).
`in-ns` calls `set-namespace!`, creating/switching the SCI namespace
(`reference-code/sci/src/sci/impl/namespaces.cljc:452-456`). Seon evaluates
against the supplied context as given, which is why the later call sees the new
Var (`src/seon/sci/eval.clj:1540-1543,1612-1624`).

### P2. `intern`

```text
(let [v (intern 'session.curated.other 'interned 41)]
  [(str v) @v])
=> ["#'session.curated.other/interned" 41]

[session.curated.other/interned (ns-name *ns*)]
=> [41 user]

(intern 'session.curated.missing 'x 1)
=> :seon.cluster.eval/error
   "No namespace: session.curated.missing found"
```

`intern` is admitted. It requires the target namespace to exist, binds an
existing Var root or creates a new qualified SCI Var, and returns it
(`reference-code/sci/src/sci/impl/namespaces.cljc:610-637`). The vendored SCI
tests cover both existing and new intern cases
(`reference-code/sci/test/sci/core_test.cljc:1635-1658`).

### P3. `alter-var-root`

```text
(do (alter-var-root #'session.curated.other/f
                    (constantly (fn [] :altered-root)))
    (session.curated.other/f))
=> :altered-root

[(session.curated.other/f) session.curated.other/interned]
=> [:altered-root 41]
```

`alter-var-root` is admitted and delegates root mutation to SCI's
generation-aware `bind-root!` (`reference-code/sci/src/sci/impl/namespaces.cljc:816-840`).
The dependency's recurring tests cover its update and return semantics
(`reference-code/sci/test/sci/vars_test.cljc:214-222`).

### P4. The curation fork is copy-on-write at the current SCI revision

The isolated JVM probe created a standalone SCI context, defined `x`, forked
the context, redefined `x` and added `y` in the fork:

```text
{:base-x 1,
 :fork-x 2,
 :base-y "Unable to resolve symbol: y",
 :fork-y 3}
```

A second fork probe ran `(alter-var-root #'x inc)` and returned:

```text
{:base-x 1, :fork-x 2}
```

A third fork probe interned a new name into an inherited namespace and
returned:

```text
{:base-z "Unable to resolve symbol: fork.intern/z", :fork-z 9}
```

At the selected SCI revision, `fork` shallow-copies the environment into a new
atom and assigns a new generation (`reference-code/sci/src/sci/core.cljc:331-337`).
Root binding detects an inherited Var, copies it into the fork's generation,
and mutates the copy (`reference-code/sci/src/sci/impl/utils.cljc:356-379`).
The probes therefore falsify the older repository instruction that
redefinition through `sci/fork` leaks into its parent. For current session
curation, `sci/fork` is a viable candidate-context isolation primitive for the
tested `def`, `intern`-created name, and `alter-var-root` cases; adoption still
needs the durable namespace check described next.

## 3. The durable-definition choke point

The durable contracted-definition path is:

```text
evaluate -> :seon.sci.eval/program-row
         -> terminal-tx / receipt-settle-call
         -> program-row-tx
         -> database commit
         -> install-program-row! against db-after
```

The evaluator produces the row and mutates the faithful live REPL context
before persistence is decided (`src/seon/sci/eval.clj:1639-1660`). The run loop
copies that row into the receipt request
(`src/seon/cluster/loop.clj:249-287`). Inside the terminal transaction,
`receipt-settle-call` validates the running receipt and calls
`program-row-tx`, which validates and exact-upserts the declaration
(`src/seon/cluster/run.clj:669-768,790-843`). Only after a successful commit
does the loop call `install-program-row!` with the `db-after`
(`src/seon/cluster/loop.clj:1612-1629`).

`install-program-row!` knows the committed row's namespace but receives only
the SCI context, database value, and row. It receives neither agent ID nor run
identity (`src/seon/sci/eval.clj:637-662,681-700`). It is therefore the wrong
place for the authoritative ownership decision: it is after the database write
and cannot derive which agent authored it from its request.

`receipt-settle-call` is the correct atomic seam for a contracted program-row
check. It already resolves the held run from the mid-transaction database value
before calling `program-row-tx` (`src/seon/cluster/run.clj:819-843`). From that
run, the transaction function can join run -> agent -> assigned namespace and
compare it with `:seon.fn/ns` or `:seon.test/ns`. `refuse!` already aborts the
whole transaction with structured data (`src/seon/cluster/run.clj:167-174`),
and `seon.db/transact!` returns a Seon transition refusal verbatim as a flat
error value (`src/seon/db.clj:880-914,916-940`).

### One seam must also cover session-image definitions

A check only inside `program-row-tx` is incomplete. Seon snapshots all changed
intern roots across all SCI namespaces and emits `:seon.code.def` rows keyed by
qualified name (`src/seon/sci/eval.clj:332-353,443-468`). This includes changes
from `intern`, `alter-var-root`, uncontracted `def`, and definitions installed
before a later evaluation failure (`src/seon/sci/eval.clj:1705-1716`).

Those rows currently bypass `receipt-settle-call`: `session-image-tx` builds
them separately, and the run loop concatenates them beside `terminal-tx` in the
outer transaction (`src/seon/cluster/loop.clj:386-476,1612-1621`). The complete
one-choke design must therefore put both the optional program row and every
session-image row behind one transaction-function admission at receipt
settlement. The check can compare each durable definition's namespace with the
run agent's assigned namespace, refusing only when that namespace is assigned
to a different agent. An unowned namespace is not a conflict under the stated
rule.

This matters even with a candidate fork. Durable admission should succeed
before the curated context is adopted. In the ordinary live path, evaluation
mutates the context before persistence and a later transaction refusal does not
roll the mutation back (`src/seon/sci/eval.clj:1647-1650`). Curation can avoid
that existing live-path limitation by evaluating on the copy-on-write candidate
context proven in P4 and adopting only after the one durable settlement admits
all changed definition namespaces.

### Compatibility with ruling #20 and the no-hobbling ruling

The check is consistent with ruling #20 if and only if its guarantee is narrow:
every agent can still resolve and call every function in the cluster program
graph; only durable definition writes into a namespace assigned to a different
agent are refused. Ruling #20 defines `my.*` as a curated front door, never a
wall
([docs/prds/sci-execution-runtime/plan/README.md:1663-1678](../plan/README.md)). The
architecture separately says namespace ownership coordinates who should edit
and never gates callability
([docs/seon/architecture/agent-runtime.md:167-171](../../../seon/architecture/agent-runtime.md)).

This is also consistent with the 2026-08-03 no-hobbling ruling because the
motivation is not hypothetical malice. P1-P3 demonstrate that honest agent code
can switch namespace, intern, or mutate another namespace's Var today; the
global identity and repair-routing consequences are present in current source.
The refusal should be described as an honest-mistake/data-integrity check,
return the owner and target namespace in its flat error data, and confer no
security or capability guarantee.

One prerequisite remains: the evaluator must use the database assignment rather
than unconditional `my.agents.<id>` derivation. Otherwise the system can refuse
an agent for writing outside its assignment after the system itself started the
agent in the wrong namespace (`src/seon/sci/eval.clj:239-246`;
`src/seon/cluster/loop.clj:1285-1306,1525-1534`;
[docs/seon/issues/evals-ignore-the-agents-assigned-namespace.md:46-59](../../../seon/issues/evals-ignore-the-agents-assigned-namespace.md)).

## 4. Form and receipt namespace attribution

The reply parser delegates to the one SCI reader and copies each read event's
namespace into the source map as `:seon.ns/name`
(`src/seon/cluster/reply.clj:115-144,217-244`). The reader begins in the
supplied namespace or `user`, and a literal top-level `ns` or `in-ns` changes
the namespace attributed to following top-level forms
(`src/seon/sci/reader.cljc:444-496,569-596`). A dynamic or nested namespace
change that static reading cannot establish removes subsequent attribution
instead of guessing; runtime receipts remain the truth
(`src/seon/sci/reader.cljc:489-493`;
`test/seon/sci/reader_test.clj:281-334`).

`plan-call` persists the parser's namespace as
`:seon.cluster.run.form/ns`, falling back to the agent's assigned namespace
when the source carries none (`src/seon/cluster/run.clj:405-471`). The plan-form
schema makes that ref optional and identifies the form by run plus ordinal
(`resources/seon/schemas/seon.cluster.run.form.edn:8-22`).

At evaluation time, the fold chooses the prior evaluation's ending namespace,
then the stored form namespace, then the ID-derived fallback; it records the
selected namespace in the evaluation request
(`src/seon/cluster/loop.clj:1024-1080,1506-1549`). Successful and failed
evaluation values both set `:seon.cluster.eval/ns` to that starting namespace
(`src/seon/sci/eval.clj:1450-1499`). The receipt persists it
(`src/seon/cluster/loop.clj:249-287`;
`src/seon/cluster/run.clj:770-788`). The ending namespace is carried as
`:seon.sci.eval/ending-ns` only to the next reduce iteration
(`src/seon/cluster/loop.clj:1657-1660`); there is no durable ending-namespace
attribute in `resources/seon/schemas/seon.cluster.eval.edn:1-55`.

### P5. Query proof on an ordinary namespace-changing run

An HTTP form POST to the isolated cluster asked the root agent to evaluate
exactly these two top-level forms:

```clojure
(in-ns 'session.curated.run)
(def marker 7)
```

The POST returned 204. Joining `run.form` and `eval` by their shared run and
ordinal returned:

```text
["0ba0ba25-4eda-4330-ba31-fa43ad18e6d4"
 0
 "(in-ns (quote session.curated.run))"
 my.agents.root
 my.agents.root]

["0ba0ba25-4eda-4330-ba31-fa43ad18e6d4"
 1
 "(def marker 7)"
 session.curated.run
 session.curated.run]
```

The query was:

```clojure
(seon.db/q
 '[:find ?run-id ?ordinal ?source ?form-ns ?eval-ns
   :where
   [?run :seon.cluster.run/id ?run-id]
   [?form :seon.cluster.run.form/run ?run]
   [?form :seon.cluster.run.form/ordinal ?ordinal]
   [?form :seon.cluster.run.form/source ?source]
   [?form :seon.cluster.run.form/ns ?form-ns-ref]
   [?form-ns-ref :seon.ns/name ?form-ns]
   [?eval :seon.cluster.eval/run ?run]
   [?eval :seon.cluster.eval/ordinal ?ordinal]
   [?eval :seon.cluster.eval/ns ?eval-ns-ref]
   [?eval-ns-ref :seon.ns/name ?eval-ns]])
```

Therefore “which namespace did this form start evaluating in?” is queryable.
“Which namespace was current after this form evaluated?” is not directly
queryable today. The architecture target says a receipt records the ending
namespace
([docs/seon/architecture/agent-runtime.md:100-107](../../../seon/architecture/agent-runtime.md)), so current
source is short of that target. Inline curation needs an explicit durable
ending-namespace fact if adoption or later diagnosis must distinguish a form's
start from its post-evaluation namespace without inferring from the next form.

## Design constraints for inline session curation

1. Resolve the run agent's starting namespace from
   `:seon.cluster.agent/namespace`, not from its ID. Preserve explicit reader
   movement for following forms (`src/seon/cluster/agent.clj:103-114`;
   `src/seon/sci/reader.cljc:444-496`).
2. Execute the corrected ordered sources on a current-revision `sci/fork` or an
   equivalently isolated candidate context; P4 proves copy-on-write for existing
   Var root mutation at the pinned revision.
3. Before adopting that context, settle all contracted program rows and
   uncontracted session-image rows through one transaction-function admission
   that derives the author and assigned namespace from the run facts
   (`src/seon/cluster/run.clj:819-843`;
   `src/seon/cluster/loop.clj:1612-1621`).
4. Refuse only a durable definition whose target namespace is assigned to a
   different agent. Do not restrict calls, reads, or evaluation of ordinary
   expressions in that namespace
   ([docs/seon/architecture/agent-runtime.md:144-171](../../../seon/architecture/agent-runtime.md)).
5. Decide explicitly whether curation needs the namespace at evaluation start,
   the ending namespace, or both. Only start is a general receipt fact today
   (`src/seon/sci/eval.clj:1450-1499`;
   `resources/seon/schemas/seon.cluster.eval.edn:1-55`).

## Render-quality observations

The first `runtime_status` probe requested one selected cluster but returned a
single roughly 19,000-token JSON line containing the entire operator-root
inventory, including a degraded unrelated cluster's deeply duplicated contract
failure. The selected-cluster filter did not make the rendered result scoped or
readable. This was ugly output under the standing order; it did not block the
research because the isolated-root `eval_clj` envelopes were bounded and
usable.

The invalid-mode response from the first fork probe was concise and actionable:
“Evaluation mode must be 'jvm' or 'door'.” The corrected `jvm` probe succeeded.
