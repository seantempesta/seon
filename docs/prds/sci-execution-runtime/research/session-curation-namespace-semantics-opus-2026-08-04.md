---
type: research
status: active
tags: [research, sci, agent, namespace, session-curation, identity]
---

# Session curation and namespace semantics (2026-08-04)

Independent research lane. Question: inline session curation re-executes a
corrected ordered vector of form sources on a fork and adopts the clean
session. What does that do to agent identity, namespace assignment, and the
durable program graph?

**I read
[docs/prds/sci-execution-runtime/plan/bootstrap-vector-design-2026-08-01.md](docs/prds/sci-execution-runtime/plan/bootstrap-vector-design-2026-08-01.md)
end to end before doing any of this work**, plus the root `AGENTS.md` (through
the `CLAUDE.md` link) and
[docs/prds/sci-execution-runtime/AGENTS.md](docs/prds/sci-execution-runtime/AGENTS.md).

Every claim below carries a `file:line` or a probe transcript. Live probes ran
on my own scratch cluster `opusns` (`bin/seon start opusns`), never `default`.
Two probe surfaces were used: `mcp__seon__eval_clj` in `door` mode (the
cluster's real shared SCI ctx) and `jvm` mode (the cluster's `io-prepl`, from
which `seon.sci.eval/evaluate` was called directly to inspect values the MCP
projection hides).

## Summary of findings

1. **Namespace assignment is unique and enforced by Datahike; namespace
   *occupancy* is not enforced at all.** `:seon.cluster.agent/namespace` is
   `:db.unique/value`, so a second agent's assignment transaction is *rejected*.
   But nothing stops any agent evaluating — and durably defining — in any
   namespace. Assignment and evaluation location are already decoupled in
   fact; only the *assignment* half is constrained.
2. **`(in-ns …)` works, is tracked statically by the reply reader, and is
   threaded dynamically by the fold — and the two can disagree.** For static
   `in-ns` they agree by construction. For a namespace change the reader cannot
   see (`(when true (in-ns 'x))`), the reader and the runtime disagree, and
   *which one wins depends on whether the fold was interrupted*. That
   non-determinism is the single most important thing for session curation,
   because curation's whole premise is that re-executing the vector reproduces
   the session.
3. **There is exactly one choke point where a durable `defn` becomes a program
   fact, it is a transaction function, and it already has the agent one join
   away** — `seon.cluster.run/receipt-settle-call` (`src/seon/cluster/run.clj:819-843`),
   whose `program-row-tx` already refuses on a namespace condition
   (`::program-namespace-missing`, `run.clj:714-716`). An ownership check would
   be five lines there. **I recommend not adding it**, on the owner's
   no-hobbling ruling; §4 argues that honestly and proposes the accretive
   alternative (record the author, refuse nothing).

## 1. Agent identity versus namespace today

### The schema

`resources/seon/schemas/seon.cluster.agent.edn:60`:

```clojure
:id [:string {:min 1, :seon.db/identity true}]
```

`resources/seon/schemas/seon.cluster.agent.edn:62-63`:

```clojure
:namespace [:and #:seon.db{:unique true} :seon.db/ref]
```

and on the agent entity itself, `seon.cluster.agent.edn:9-11`:

```clojure
[:seon.cluster.agent/namespace {:optional true} :seon.cluster.agent/namespace]
```

Three facts fall straight out, and all three matter:

- **Agent id is independent of namespace.** It is its own `:seon.db/identity`
  string attribute. Nothing derives it from a namespace and nothing derives a
  namespace from it *in the database*.
- **The namespace ref is OPTIONAL.** An agent may exist with no assigned
  namespace at all.
- **The namespace ref is UNIQUE**, so the relation is at most 1:1 in the
  assignment direction.

Live confirmation of the installed physical schema and of the refusal
(cluster `opusns`, jvm mode):

```clojure
;; two agents, one namespace
(db/transact! conn {:tx-data (agent/creation-tx {:seon.cluster.agent/id "probe-alice"
                                                 :seon.ns/name 'my.agents.shared
                                                 :seon.cluster/name "opusns"})})
(db/transact! conn {:tx-data (agent/creation-tx {:seon.cluster.agent/id "probe-bob"
                                                 :seon.ns/name 'my.agents.shared
                                                 :seon.cluster/name "opusns"})})
```

```clojure
{:first :committed
 :second #:seon.error{:kind :seon.db/rejected
                      :message "… Cannot add #datahike/Datom [14061 :seon.cluster.agent/namespace 14059 …] because of unique constraint: #datahike/Datom [14060 :seon.cluster.agent/namespace 14059 …] {:error :transact/unique, :attribute :seon.cluster.agent/namespace …}"}
 :owner-of-shared "probe-alice"
 :schema-unique {:db/ident :seon.cluster.agent/namespace
                 :db/valueType :db.type/ref
                 :db/cardinality :db.cardinality/one
                 :db/unique :db.unique/value}}
```

So the constraint is real, live, and returns a **flat error value** rather than
throwing — `seon.db` semantics. It is also claimed by a recurring test:
`test/seon/cluster/agent_namespace_test.clj:53-71`
(`one-namespace-cannot-be-assigned-to-two-agents`), and reassignment (retarget
the agent's ref) is separately covered at `agent_namespace_test.clj:36-51`.

### The one derivation that does couple them

`seon.sci.eval/agent-namespace` (`src/seon/sci/eval.clj:239-246`):

```clojure
(defn agent-namespace
  "The ONE namespace name for an agent: `my.agents.<id>`. …"
  [agent-id]
  (symbol (str "my.agents." agent-id)))
```

This is a **string-built name**, i.e. exactly the shape the root instructions
call a hand rule in disguise ("deriving a symbol by string-building a name … is
a hand rule in disguise"). It is used as the evaluator's *fallback* namespace
(`eval.clj:1568`), as the fold's `::fallback-namespace` (`loop.clj:1533-1534`),
and in the bootstrap drive's grading (`src/seon/bootstrap_drive.clj`). The
database already holds the authoritative answer —
`seon.cluster.agent/owner-of` inverts it (`src/seon/cluster/agent.clj:103-114`)
— but the forward direction is computed from the id rather than read.

This is the coupling that a decoupling design has to remove; see §6. It also
contradicts the owner ruling recorded in the vocabulary table ("real agents own
namespaces anywhere in the tree, including seon core"): today an agent whose
assigned namespace is `seon.render.web` still gets `my.agents.<id>` as its
evaluation fallback, because the fallback never consults the assignment. There
is an existing issue in the same family:
[docs/seon/issues/evals-ignore-the-agents-assigned-namespace.md](docs/seon/issues/evals-ignore-the-agents-assigned-namespace.md).

### What breaks if two agents evaluate in the same namespace

Nothing refuses, and that is the point. Concretely, probed live:

- **They see each other's definitions immediately.** One SCI ctx per cluster
  (`eval.clj:75-85`: "a supplied ctx is used AS GIVEN: cluster boot builds and
  acquires it once, and every agent in that cluster evaluates against the same
  live program graph"). Probe, door mode: a `defn` made while `in-ns`'d to
  `other.probe.ns` was called from a form evaluated in
  `my.agents.someone-else` and returned `:from-probe`.
- **A durable `defn` is keyed only by its qualified symbol.** Two agents
  defining `f` in one namespace is last-writer-wins on one `:seon.fn/sym` row.
- **The program row carries no author.** Probed (jvm mode, calling
  `se/evaluate` with `:seon.cluster.agent/id "probe-bob"` and
  `:seon.cluster.run.form/ns [:seon.ns/name 'my.agents.shared]`, a namespace
  assigned to `probe-alice`):

  ```clojure
  {:row {:seon.fn/sym "my.agents.shared/owned-by-alice"
         :seon.fn/ns [:seon.ns/name "my.agents.shared"]
         :seon.fn/private? false}
   :row-keys (:seon.fn/arglists :seon.fn/arities :seon.fn/ast :seon.fn/doc
              :seon.fn/ns :seon.fn/private? :seon.fn/source :seon.fn/spec
              :seon.fn/sym :seon.sci.eval/evaluated?)
   :error nil}
  ```

  No agent, author, or owner attribute exists on `:seon.fn/*` at all. The only
  agent-bearing attributes in the whole installed schema are
  `:seon.cluster.agent/{cluster,id,instructions,namespace,run}`,
  `:seon.cluster.run/agent`, `:seon.config/per-agent`, `:seon.effect/owner`,
  and `:seon.error/agent`.
- **Two real consequences already depend on this being permissive**, so
  "nothing breaks" is not quite right — things *work* because of it:
  - `seon.cluster.work/form-owner` (`src/seon/cluster/work.clj:219-241`) routes
    a red receipt to the *namespace's owner*, falling back to the run's author.
    Cross-namespace evaluation is how one agent's mistake reaches the agent who
    owns the code — a designed feature, not an accident.
  - Objective O4 of the bootstrap experiment
    (`bootstrap-vector-design-2026-08-01.md` §5) explicitly requires A to call
    a symbol B authored, "without a reinstall".

So the honest statement is: **shared-namespace evaluation is a supported
capability with no attribution.** The gap is not permission. It is the missing
fact.

## 2. What `defn` into another namespace actually does

All probed live on `opusns` through `eval_clj` `mode: door` (the cluster's real
shared ctx, the same door an agent's forms take).

### Qualified `defn` — refused by SCI, as a flat error value

```text
my.agents.probe=> (defn other.probe.ns/qualified-defn [] :landed)
```

```clojure
{:seon.cluster.eval/ns [:seon.ns/name my.agents.probe]
 :seon.sci.eval/ending-ns my.agents.probe
 :seon.cluster.eval/error "Var name should be simple symbol."
 :seon.sci.admit/record {:seon.eval/outcome :error, :seon.eval/duration-ms 8 …}}
```

Note it is an **error value**, not a throw — `eval.clj:42-46`. The agent sees a
`:seon.error` map. Good.

### `(in-ns …)` then `defn` — lands in the target, visible cluster-wide

```text
my.agents.probe=> (do (in-ns 'other.probe.ns)
                      (defn landed-here [] :from-probe)
                      [(str (ns-name *ns*)) (landed-here)])
```

```clojure
{:seon.cluster.eval/ns [:seon.ns/name my.agents.probe]
 :seon.sci.eval/ending-ns other.probe.ns
 :seon.dev.mcp/text "[\"other.probe.ns\" :from-probe]"}
```

Then, from a *different* namespace and a different nominal agent:

```text
my.agents.someone-else=> [(str (ns-name *ns*)) (other.probe.ns/landed-here)]
=> ["my.agents.someone-else" :from-probe]
```

**`in-ns` works in the door today.** The `seon.sci.eval` namespace docstring
(`eval.clj:57-64`) still says the model "never needs to write `(in-ns …)` —
which is what the first live drive tried, and what failed with `Can't
change/establish root binding of clojure.core/*ns*`". The failure it describes
is fixed (`sci/binding [sci/ns namespace-object …]` at `eval.clj:1614`, with
`(vreset! ending-namespace (sci/ns-name @sci/ns))` at `eval.clj:1623`), and the
landed bootstrap vector's form 1 *is* `(in-ns 'my.agents.root)` (§5). The
docstring reads as if `in-ns` were still unsupported. Since docstrings render
into agent context, this is a mild accuracy defect worth a one-line fix.

### `intern` — admitted, creates a Var in an arbitrary namespace

```text
my.agents.probe=> (try [:intern (intern (create-ns 'other.probe.ns) 'interned-here 42)]
                       (catch Exception e [:threw (ex-message e)]))
=> [:intern #'other.probe.ns/interned-here]
```

### `alter-var-root` — admitted, rebinds a foreign namespace's function

```text
my.agents.probe=> (try [:alter (alter-var-root #'other.probe.ns/landed-here
                                               (constantly (fn [] :HIJACKED)))
                        :now (other.probe.ns/landed-here)]
                       (catch Exception e [:threw (ex-message e)]))
=> [:alter #object[sci.impl.fns/fun/arity-0--70125 …] :now :HIJACKED]
```

**Important distinction for curation:** `intern` and `alter-var-root` mutate
**process-local ctx state only**. Neither produces a `:seon.sci.eval/program-row`
(`eval.clj:269-292` admits only reader `declaration-row`/`deletion-row` shapes),
so neither becomes a durable fact and neither survives a stateless resume. They
are session-image effects. The reader-level session image
(`:seon.sci.eval/session-defs`, `eval.clj:1680-1683`) is what carries them, and
a curation pass that drops the form that made them silently drops the effect.

## 3. Where the namespace of a form is recorded, and whether it is queryable

Two independent records exist, and both are real datoms.

### Plan-time: `:seon.cluster.run.form/ns`

The reply reader tracks the namespace-in-effect *statically* across the reply
and projects it per form. `seon.cluster.reply/plan-sources`
(`src/seon/cluster/reply.clj:217-244`) — "Each plan form carries the reader's
namespace-in-effect when the reader attributed one" — and
`seon.cluster.run/plan-call` (`src/seon/cluster/run.clj:432-463`) upserts the
`:seon.ns` and points the form at it: "THE PARSE-TIME NAMESPACE IS PROJECTED,
NEVER DERIVED HERE."

Probed live:

```clojure
(reply/sources "(def a 1)\n(in-ns 'other.place)\n(defn f [] 2)\n(in-ns 'my.agents.probe)\n(def b 3)"
               'my.agents.probe)
```

```clojure
[["(def a 1)"                "my.agents.probe"]
 ["(in-ns 'other.place)"     "my.agents.probe"]
 ["(defn f [] 2)"            "other.place"]
 ["(in-ns 'my.agents.probe)" "other.place"]
 ["(def b 3)"                "my.agents.probe"]]
```

This is exactly right REPL semantics: the `in-ns` form itself is attributed to
the namespace it was *read in*, and the following form to the new one. **The
ordered vector already carries a complete, queryable namespace track.**

### Terminal: `:seon.cluster.eval/ns` on the receipt

The evaluation returns `:seon.cluster.eval/ns [:seon.ns/name namespace-name]`
(`eval.clj:1463` for success, `eval.clj:1488` for failure), the loop copies it
into the receipt request (`loop.clj:282-283`), and `terminal-tx` asserts it
(`loop.clj:320`). The attribute is a ref (`seon.cluster.eval.edn:5`,
`:ns :seon.db/ref`).

**So "which namespace did this form evaluate in" IS answerable by query**, both
before and after execution. Verified structurally on `opusns` (queries parse
and run; receipts empty because the seeded bootstrap run has not been driven):

```clojure
;; per-form evaluation namespace, from receipts
'[:find ?agent-id ?ordinal ?ns-name
  :where
  [?run :seon.cluster.run/agent ?agent] [?agent :seon.cluster.agent/id ?agent-id]
  [?run :seon.cluster.run/id ?run-id]   [?receipt :seon.cluster.run/id ?run-id]
  [?receipt :seon.cluster.eval/ordinal ?ordinal]
  [?receipt :seon.cluster.eval/ns ?ns]  [?ns :seon.ns/name ?ns-name]]
```

### The gap: the *ending* namespace is never a fact

`:seon.sci.eval/ending-ns` is on the evaluation map (`eval.clj:1464`) and is
consumed by the fold's `recur` (`loop.clj:1658-1660`) — but a full-tree grep
finds it in **no** transaction: `src/seon/cluster.clj:217` (MCP projection),
`src/seon/sci/eval.clj:1464,1489`, `src/seon/cluster/loop.clj:1659`, tests, and
two schema files. It is never asserted on the receipt.

Consequence: for `(in-ns 'X)` at ordinal *n*, the receipt records
`:seon.cluster.eval/ns` = the namespace it *started* in. The transition itself
is recoverable only by re-reading the form source. For a static `in-ns` that is
fine, because the reader recorded the same transition on the plan side. For a
dynamic one it is not — see §3.1.

### 3.1 The divergence that matters for curation

The fold picks the evaluation namespace at `loop.clj:1055-1058`:

```clojure
evaluation-namespace (or current-namespace                              ; threaded ending-ns
                         (second (:seon.cluster.run.form/ns form))      ; the reader's static track
                         fallback-namespace)                            ; my.agents.<id>
```

`current-namespace` is the fold-loop variable, seeded `nil` at `loop.clj:1508`
and advanced to `(:seon.sci.eval/ending-ns evaluation)` at `loop.clj:1658-1660`.

- **Uninterrupted fold:** the *runtime* `ending-ns` wins for every form after
  the first.
- **Fold that starts mid-run** (resume after a release, an interruption, a
  process handoff): `current-namespace` is `nil` again, so the *reader's static*
  namespace wins.

For static `in-ns` the two agree, so this is invisible. Probed divergence:

```text
;; the reader's static track
(reply/sources "(when true (in-ns 'sneaky.ns))\n(defn g [] 1)" 'my.agents.probe)
=> [["(when true (in-ns 'sneaky.ns))" "my.agents.probe"]
    ["(defn g [] 1)"                  "my.agents.probe"]]

;; the runtime, same first form, door mode
my.agents.probe=> (when true (in-ns 'sneaky.ns))
   :seon.cluster.eval/ns      [:seon.ns/name my.agents.probe]
   :seon.sci.eval/ending-ns   sneaky.ns
```

So `(defn g [] 1)` lands in `sneaky.ns` in an uninterrupted fold and in
`my.agents.probe` in a resumed one. **The same ordered vector has two
outcomes.** For session curation — whose contract is "re-execute the corrected
vector and adopt the result" — this is a correctness bug, not a curiosity: a
curated re-execution can produce a different program graph than the session it
claims to reproduce, and no fact records which one happened, because
`ending-ns` is not committed.

I did not find an existing issue note for this; §7 records it.

## 4. The one choke point, and whether an ownership check belongs there

### The seam

There are two distinct operations, and the question names the wrong one.

`seon.sci.eval/install-program-row!` (`src/seon/sci/eval.clj:637-754`) installs
a declaration into the SCI ctx **after** the terminal transaction committed
(called at `loop.clj:1622-1630`). Its request is
`{:seon.sci.eval/ctx, :seon.db/db, :seon.sci.eval/program-row}`
(`eval.clj:641-644`) — **it does not know the agent**, and it must not refuse:
by the time it runs the fact is already durable.

The **durable write** happens one step earlier, inside the terminal
transaction, and it is a transaction function:

- `seon.cluster.run/receipt-settle-tx` (`run.clj:565-572`) emits
  `[[:db.fn/call #'receipt-settle-call request]]`;
- `seon.cluster.run/receipt-settle-call` (`run.clj:819-843`) resolves the run
  (`receipt-run`, `run.clj:493-501` → `current-run`, a mid-transaction
  `db/pull '[*]`), validates the receipt, and then:

  ```clojure
  (into (if-let [row (:seon.sci.eval/program-row request)]
          (program-row-tx db request row)
          [])
        (receipt-terminal-assertions receipt request))
  ```

- `program-row-tx` (`run.clj:669-…`) already performs a namespace-conditioned
  refusal at `run.clj:711-716`:

  ```clojure
  namespace-ref (or (:seon.fn/ns row) (:seon.test/ns row))
  …
  (when (and namespace-ref
             (not (:db/id (db/pull db [:db/id] namespace-ref))))
    (refuse! `receipt-settle-call ::program-namespace-missing request))
  ```

**This is the exact seam.** It is one function, it runs inside the transaction
on the transaction's own `db` (so it cannot race a concurrent assignment), it
already has the row's namespace ref, and the agent is one join away —
`(:db/id (::agent run))`, precisely the join `plan-call` already performs at
`run.clj:423-429`. An ownership admission check would be roughly:

```clojure
(when-let [owner (agent/owner-of db (second namespace-ref))]
  (when (not= owner (run-agent-id db run))
    (refuse! `receipt-settle-call ::program-namespace-not-owned request)))
```

five lines, and `refuse!` already produces a flat `:seon.error` value the agent
sees. Cost: near zero. Ruling #20 is not violated — this gates a durable
DEFINITION write, never a call.

### Whether it should exist — my honest answer: no

The owner ruled on 2026-08-03: "Do not add allowlists, credential redaction,
env sanitization, per-agent grants, or any restriction justified by a
HYPOTHETICAL risk… A security restriction is admissible only after EVIDENCE of
a real problem, recorded with that evidence." The design concern is honest
mistakes, not malice.

Applying that test to this check:

- **Is there evidence of a real problem?** No. I found no issue note, no
  receipt, and no lane report of an agent clobbering another agent's function.
  Objective O4 of the bootstrap experiment *requires* cross-agent definition
  reachability, and `work/form-owner` (`work.clj:219-241`) exists precisely to
  route cross-namespace consequences to the namespace's owner — a designed
  collaboration path, not a leak.
- **Would the check catch an honest mistake?** Only one:
  `(in-ns 'my.agents.other)` typed or hallucinated by mistake, followed by a
  `defn`. That is real but rare, and it is *already* recoverable — the row is
  exact-upsert with full source, history is retained, and `form-owner` routes
  the red receipt to the affected owner.
- **What would it cost?** It would forbid the legitimate case the ruling on
  namespaces explicitly blesses ("real agents own namespaces anywhere in the
  tree, including seon core"): a curator agent, a repair agent, or a curation
  re-execution acting *on behalf of* another agent writes into a namespace it
  does not own by assignment. **Session curation is itself the counterexample**:
  the curator proposes the vector, the system re-executes it on a fork, and
  whichever agent identity drives that re-execution is by definition not always
  the namespace's assigned owner. A refusal at `receipt-settle-call` would break
  the feature this research exists to support.

So: **the check is cheap and well-sited and should not be built.** What is
missing is not a refusal — it is the fact.

### The accretive alternative

Record `:seon.fn/author` (a ref to the agent) on the program row at exactly the
same seam, refusing nothing. `receipt-settle-call` already holds the run and
therefore the agent; adding one ref to the row is pure accretion under the
open-map rules (a new key, validated once declared, never narrowing anything).
It turns "who defined this, and on whose behalf" into a Datalog query, which is
the standing principle: *if you cannot answer a question by query, the missing
fact is the defect.* Then, if evidence of real cross-agent damage ever arrives,
the refusal is a five-line addition on top of a fact that already exists — and
until then nothing is hobbled.

Note this is strictly better than deriving the author from transaction
provenance (`:seon.db/user` / `:seon.db/process`): provenance names the process,
not the agent, and the root instructions forbid copying provenance onto domain
entities as `created-by`. An explicit `:seon.fn/author` ref is the declared
fact, not a provenance projection.

## 5. Live state of the bootstrap vector (incidental, and useful)

`opusns` boots with the design document's candidate vector already seeded as a
frozen plan for agent `root` — the "creation-time seeding call" listed as gap 1
in `bootstrap-vector-design-2026-08-01.md` §1 is **landed**
(`src/seon/bootstrap_drive.clj:151` sets `:seon.cluster.run.form/ns`). Queried
live:

| ordinal | source | `run.form/ns` |
|---:|---|---|
| 0 | `(help)` | `my.agents.root` |
| 1 | `(in-ns 'my.agents.root)` | `user` |
| 2 | `(dir my.run)` | `my.agents.root` |
| 3 | `(doc my.run/complete)` | `my.agents.root` |
| 4 | `(dir my.message)` | `my.agents.root` |
| 5 | `(seon.db/q '[:find (count ?f) . :where [?f :seon.fn/sym _]])` | `my.agents.root` |
| 6 | the parsed-contract discovery query | `my.agents.root` |
| 7 | `(defn largest …)` open input map | `my.agents.root` |
| 8 | `(defn largest …)` closed input + closed return | `my.agents.root` |
| 9 | `(largest [{:label "a" :amount 3} {:label "b" :amount 9}])` | `my.agents.root` |
| 10 | `(largest)` | `my.agents.root` |
| 11 | `(largest [])` | `my.agents.root` |
| 12 | the persistence query | `my.agents.root` |

Ordinal 0 is attributed `my.agents.root` while ordinal 1 — the `in-ns` that
*establishes* it — is attributed `user`. That is internally consistent with the
reader's semantics only if forms 0 and 1 were attributed by different means;
under a single reader pass, form 0 would read as `user` too. It is harmless
here (the fold's fallback is `my.agents.root` either way) but it is a small
attribution inconsistency in the seeded vector worth a look by the bootstrap
owner.

## 6. Recommended design: decouple agent id from operating namespace

The database already models this correctly. The coupling is entirely in one
computed fallback and one missing fact. Four named seams, in dependency order.

### Seam A — replace the string-built fallback with the assigned fact

**Owner:** `seon.sci.eval/agent-namespace` (`eval.clj:239-246`) and its two
call sites, `eval.clj:1568` and `loop.clj:1533-1534`.

Today: `(symbol (str "my.agents." agent-id))`. The database already answers
this: `seon.cluster.agent/owner-of` (`agent.clj:103-114`) is its exact inverse,
and the forward query is the same join read the other way. Replace the
computation with a read of `:seon.cluster.agent/namespace` from the pinned
database value the turn already holds, keeping `my.agents.<id>` only as the
**creation-time default** in `creation-tx` (`agent.clj:86-101`) — where a
default is a data choice, not a derivation rule.

Effect: an agent assigned `seon.render.web` evaluates in `seon.render.web`.
This is what the recorded ruling already says should be true, and it closes
[evals-ignore-the-agents-assigned-namespace](docs/seon/issues/evals-ignore-the-agents-assigned-namespace.md).
It also removes a naming-convention rule, per the standing prohibition.

**Unchanged:** the uniqueness constraint, agent id, the prompt (it reads the
same one derivation, which is now a query).

### Seam B — make the operating namespace a per-run fact, not a per-agent one

**New attribute, on the run:** the namespace a run's fold *starts* in. Today
that is implicit in `::fallback-namespace` (`loop.clj:1533`). Making it a fact
on `:seon.cluster.run` means a curation re-execution can declare "run this
vector starting in namespace X **as** agent Y", which is precisely the shape
curation needs, without inventing a second identity for Y or reassigning any
`:seon.cluster.agent/namespace`.

This is the actual decoupling: **assignment** (`:seon.cluster.agent/namespace`,
unique, 1:1, durable) says *whose surface this namespace is* — it drives the
namespace page, `form-owner` routing, and red-receipt delivery. **Operation**
(the run's starting namespace + the per-form track) says *where these forms
evaluate*. Curation only ever touches the second.

**Unchanged:** assignment uniqueness, `form-owner`, namespace-page routing
(`render/ns.clj:314`, `render/web.clj:1226,1235`).

### Seam C — commit the ending namespace, and make the fold prefer facts

**Owner:** `eval.clj:1463-1464` / `loop.clj:282-283,320` (assert
`:seon.sci.eval/ending-ns` on the receipt alongside `:seon.cluster.eval/ns`),
and `loop.clj:1055-1058` + `loop.clj:1506-1508` (seed `current-namespace` from
the previous ordinal's committed ending namespace rather than `nil`).

This kills the §3.1 divergence at its root rather than fencing it: after the
change, a resumed fold and an uninterrupted fold pick the same namespace for
every form, because both read the same committed fact. It also makes "where did
this form actually end up" queryable, which curation needs in order to *verify*
that its re-execution reproduced the session rather than merely to hope so.

Cost: one attribute (already declared as `:seon.sci.eval/ending-ns`, a symbol —
it would want to become a `:seon.db/ref` to `:seon.ns` for consistency with
`:seon.cluster.eval/ns`), one assertion, one loop seed.

**Unchanged:** the reader's static track, `plan-call`, the fold's shape.

### Seam D — record the author of a durable declaration

**Owner:** `seon.cluster.run/program-row-tx` (`run.clj:669-716`), inside
`receipt-settle-call`, where the run — and therefore the agent — is already
resolved.

Add `:seon.fn/author` (ref to the agent entity) to the committed row. Refuse
nothing (§4). This makes every question the ownership check was reaching for a
query: which agent defined this, which functions did a curated re-execution
author, did the curator write outside its own surface.

**Unchanged:** ruling #20 (calling is never gated), the guarded door, exact
upsert semantics, everything about who may call what.

### What stays unchanged overall

- `:seon.cluster.agent/namespace` stays unique and 1:1. Nothing here relaxes it.
- Agent id stays an independent `:seon.db/identity` string.
- One SCI ctx per cluster; every agent sees every definition immediately
  (rulings #20 and #27).
- The guarded door still bounds effects, never callability.
- `form-owner`'s routing of red receipts to the namespace's owner, with the
  run's author as the total fallback.
- No new refusal, no allowlist, no per-agent grant.

### What this buys session curation, concretely

A curation pass becomes: fork at the run's basis, open a new run **as the same
agent** with an explicit starting namespace (Seam B) and the corrected ordered
vector, fold it, and compare the committed per-form ending-namespace track
(Seam C) and the authored `:seon.fn/sym` set (Seam D) against the original. If
the tracks match, the session is genuinely reproduced and adoption is sound. If
they do not, the mismatch is a queryable fact rather than an invisible
divergence. None of that requires a second agent identity, a namespace
reassignment, or a permission check.

## 7. Defects observed, to be filed

1. **The fold's namespace source is order-dependent** (§3.1). Uninterrupted →
   runtime `ending-ns`; resumed mid-run → the reader's static track. They differ
   whenever a namespace change is not statically visible. No fact records which
   applied. `loop.clj:1055-1058`, `loop.clj:1506-1508`, `loop.clj:1658-1660`.
   Blocks: any curation contract of the form "re-executing the vector reproduces
   the session". Acceptance: Seam C, plus a regression that folds
   `[(when true (in-ns 'x)) (defn g [] 1)]` both straight through and with a
   forced mid-run resume, asserting the same committed namespace for `g`.
2. **`:seon.sci.eval/ending-ns` is never committed** (§3). It exists on the
   evaluation map and is consumed only by a loop variable. Acceptance: Seam C.
3. **`seon.sci.eval/agent-namespace` string-builds a name the database already
   holds** (`eval.clj:239-246`), which is the prohibited naming-convention
   shape and contradicts the "agents own namespaces anywhere in the tree"
   ruling. Overlaps the open issue
   [evals-ignore-the-agents-assigned-namespace](docs/seon/issues/evals-ignore-the-agents-assigned-namespace.md).
   Acceptance: Seam A.
4. **The `seon.sci.eval` namespace docstring says `in-ns` fails**
   (`eval.clj:57-64`) when it demonstrably works and the landed bootstrap
   vector uses it (§2, §5). Docstrings render into agent context, so this is a
   context-accuracy defect. Acceptance: correct the two sentences.
5. **Bootstrap plan form 0 is attributed `my.agents.root` while form 1 (the
   `in-ns` that establishes it) is attributed `user`** (§5). Likely benign;
   worth the bootstrap owner's eye.

## 8. Ugly rendered output encountered (standing order, 2026-08-03)

1. **`:seon.db/rejected` messages are stringified Java exceptions.** The
   uniqueness refusal in §1 arrives as
   `"clojure.lang.ExceptionInfo: Cannot add #datahike/Datom [14061 …] because of unique constraint: #datahike/Datom [14060 …] {:error :transact/unique, :attribute …}"`
   — a host class name, two raw datom tuples with bare entity ids, and a nested
   `ex-data` map flattened into prose. An agent reading this cannot tell which
   *agent* already owns the namespace, only that entity 14060 does. The
   information needed (the attribute, the conflicting value, the existing
   holder) is all available at the refusal site. This shape should carry a
   declared `:seon.render/ai` producer.
2. **Every `eval_clj` `mode: jvm` exception reports the same frame.** Three
   different failures — an NPE, an `IllegalStateException` for a private var,
   and others — all returned
   `:seon.dev.mcp/frame ["seon.cluster$mcp_io_prepl" "invokeStatic" "cluster.clj" 336]`
   (`src/seon/cluster.clj:330-337`). That is the io-prepl serving frame, not
   the throw site, so the field is worse than absent: it actively misdirects.
3. **A reproducible NPE leaks host wording into MCP output.** Two consecutive
   `mode: jvm` forms in fresh sessions returned
   `Cannot invoke "java.util.concurrent.Future.get()" because "fut" is null`,
   attributed to the same `cluster.clj:336` frame. The same forms succeeded in
   another session, so it is session/state dependent rather than form
   dependent. Worth a probe by the MCP owner; I did not chase it further
   because it did not block this lane.

## Dependency ledger

- **Datahike** (`reference-code/datahike`) — `:db.unique/value` semantics for
  `:seon.cluster.agent/namespace`; `:db.fn/call` transaction functions, which
  is what makes `receipt-settle-call` the correct choke point (it sees the
  transaction's own `db`).
- **SCI** (`reference-code/sci`) — `sci/ns` dynamic binding (`eval.clj:1614`),
  `sci/ns-name` (`eval.clj:1623`), `sci/fork` env-copy semantics
  (`reference-code/sci/src/sci/core.cljc:318-323`, cited at `eval.clj:75-85`),
  `sci/namespace-state` / `sci/install-namespace-state!`.
- **First-party owners read:** `src/seon/cluster/agent.clj`,
  `src/seon/cluster/loop.clj`, `src/seon/cluster/run.clj`,
  `src/seon/cluster/reply.clj`, `src/seon/cluster/work.clj`,
  `src/seon/sci/eval.clj`, `src/seon/cluster.clj`,
  `resources/seon/schemas/seon.cluster.agent.edn`,
  `resources/seon/schemas/seon.cluster.run.form.edn`,
  `resources/seon/schemas/seon.cluster.eval.edn`,
  `test/seon/cluster/agent_namespace_test.clj`.
- **Live surface:** cluster `opusns` (`bin/seon start opusns`), probed through
  `mcp__seon__eval_clj` in both `door` and `jvm` modes.
