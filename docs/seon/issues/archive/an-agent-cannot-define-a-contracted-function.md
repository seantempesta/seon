---
type: issue
status: resolved
severity: blocker
tags: [issue, runtime, agent, schema, live-drive]
---

# Let an agent define a contracted function

## Problem

An agent cannot `defn`. The moment its definition carries a `:malli/schema` —
which durable defns REQUIRE — `seon.program/declaration-row` violates its own
output contract and the form errors, so the very next form that calls the
function fails as an unresolved symbol.

This is the first thing a healthy agent tries to do and it is the first thing
that fails. It was invisible while the agent's context was empty; it surfaced
on the first turn that had a real prompt.

## Evidence

Cluster `default` (pid 79576), 2026-08-08, run
`d95c5c42-7307-4484-9a32-86e30bf0e29b`, triggered by human message
`inbound-536871139-0`. Four forms, four receipts, two errors, and the two
errors are this defect and its consequence:

| Ordinal | Form (head) | Outcome |
|---:|---|---|
| 0 | `(seon.db/q '[:find ?a ?v :in $ ?e :where [?e ?a ?v]] 25564)` | read the message that woke it — fine |
| 1 | `(defn live-drive-marker …{:malli/schema [:=> [:cat] [:map …]]}…)` | **`seon.program/declaration-row violated its contract (invalid-output)`** |
| 2 | `(live-drive-marker)` | `Unable to resolve symbol: live-drive-marker` |
| 3 | `(my.run/complete "…")` | completed |

The complaint, verbatim:

```text
seon.program/declaration-row violated its contract (invalid-output):
{:seon.ns/name [{:value nil, :message "missing required key"}],
 :seon.schema.admission/source [{:value nil, :message "missing required key"}],
 :seon.schema/key [{:value nil, :message "missing …
```

Three required keys are absent from the row the function builds:
`:seon.ns/name`, `:seon.schema.admission/source`, and `:seon.schema/key`.

Five occurrences of this signature exist on the cluster; two of them predate
any agent turn, so the boot path hits it as well (the observer lane recorded
two at boot, alongside the separate bootstrap-placeholder failures).

Two of the five render as `… 1 more subtree; requery refused: no stable
identity was supplied at path [] offset 0 with
:seon.render.profile/unspecified`, so the reader is told a subtree exists and
then refused it — a second, separate face defect on the same error.

## Owner

`seon.program/declaration-row` and whatever assembles its input on the agent
`defn` path.

## Acceptance

- An agent defines a function with a complete `:malli/schema` and the form
  settles a receipt with the Var, not a contract violation.
- The next form in the same run resolves and calls it.
- Zero `declaration-row` contract violations on a fresh boot.
- One class regression drives an agent `defn` with a `:malli/schema` through a
  real turn and asserts the definition is callable in the following form.

## Note for whoever fixes this

The 2026-08-08 re-drive is the reproduction: submit a message asking root to
define a schema-carrying function and call it. Nothing else is needed — this
fires on the first attempt.

## 2026-08-08 — first cause fixed; a second one is underneath it

### The finder's mechanism claim, corrected

"Three required keys are absent from the row it builds" reads Malli's
merged `[:or]` complaint as three findings. It is one. The output is
`[:or :seon.ns/ns :seon.fn/fn [:map …schema…] :seon.test/test]`, and each
arm reports its own missing identity, so `:seon.ns/name` and
`:seon.schema/key` are the OTHER families complaining that the row is not
theirs. Exactly one key was genuinely absent from the `:seon.fn/fn` arm:
`:seon.schema.admission/source`, which that arm requires. Falsified at
HEAD before anything was built on it:

```clojure
(program/declaration-row event :contracted)
;; => threw, "missing required key" for three families
(keys (program/declaration-row
       (assoc event :seon.schema.admission/source :agent) :contracted))
;; => (:seon.fn/sym :seon.fn/ns :seon.fn/source :seon.fn/arglists
;;     :seon.fn/private? :seon.fn/spec :seon.schema.admission/source)
```

### The class, and the construction that kills it

The admission source was an EVENT KEY every caller had to remember.
`seon.cluster.run` remembered it; both `seon.sci.eval` call sites did not,
which is why the agent path failed and the run-settlement path did not.
It is now a required ARGUMENT of `seon.program/declaration-row`, which
stamps it — a row with no admission source is not constructable, so no
future caller can forget it and no validation checks for it.

Fixing that made a second instance of the same class visible at the same
owner: `declaration-row` returned the reader's namespace event verbatim,
with required namespaces as bare symbols in a vector where `:seon.ns/ns`
declares a set of `:seon.ns/name` lookup refs. It now canonicalizes them,
idempotently, since the evaluator builds the same components from SCI's
namespace table.

### What is proven

Fresh cluster `declrow` in isolated root `tmp/lanes/declrow`, booted from
a freshly published `current-src`:

- zero `declaration-row` contract violations at boot (three faults total,
  none of this signature; the drive's cluster had two at boot);
- `(ns probe.sample (:require clojure.set))` settles;
- `declaration-row` returns the complete row for a contracted `defn`.

Regression:
`seon.program-test/every-declaration-row-satisfies-its-own-output-contract`
drives all four identity families under both function policies and both
admission sources and asserts each row validates
`:seon.program/declaration-row`. Green with
`seon.program-test seon.schema.program-test seon.fn-test
seon.sci.eval-test seon.cluster.run-test` (111 tests, 778 assertions) and
`bin/test --platform` exit 0.

### What still blocks this note

An agent still cannot define a contracted function. The same form now
fails one frame later, at `seon.program/with-contract-facts`, because the
row it returns carries `:seon.fn/arities` and `:seon.fn/ast` component
entities and `:seon.db/ref` admits no component value. That is a
different class at a different owner (the schema registry, 46 identical
declaration sites) and its fix is a registry-wide contract decision, so
it is filed separately with three options for a ruling:
[Admit a component value under its own attribute's declared shape](a-component-value-is-refused-by-its-own-ref-shape.md).
The `(ns … :as …)` alias form is blocked by that same note.

This note stays OPEN until that one lands and an agent's `defn` settles a
receipt with the Var.

## 2026-08-08 — resolved: an agent's contracted defn settles a receipt with the Var

The second cause landed as
[Admit a component value under its own attribute's declared shape](a-component-value-is-refused-by-its-own-ref-shape.md)
(owner ruling, option 1, `e7bafc957`): the registry compiles a component
attribute's child position as "an existing ref OR the component's own
entity", derived from the `:seon.db/component true` property. With that,
`with-contract-facts` returns a row that validates its own declared
shape.

### The live receipts

Cluster `comp`, isolated root `tmp/lanes/component-ref`, booted from a
freshly published `current-src`. A real run through the run loop and the
real evaluator, recorded as ordinary receipts. This is the first
agent-authored contracted function this system has ever settled.

| Ordinal | Form | Receipt |
|---:|---|---|
| 7 | `(defn largest … {:malli/schema [:=> [:cat [:sequential :any]] …]} …)` | refused — `:any` in an agent-authored contract, which is the intended refusal, not this class |
| 8 | the same `defn` with a complete contract (no `:any`) | **`#:seon.print{:face :seon.print/var, :name "my.agents.root/largest"}`** |
| 9 | `(largest [{:label "a" :amount 3} {:label "b" :amount 9}])` | `{:label "b", :amount 9}` |
| 11 | `(largest [])` | `{}` |
| 12 | `(seon.db/q … :seon.fn/spec … "my.agents.root/largest")` | `"[:=> [:cat [:sequential [:map [:label :string] [:amount :int]]]] [:map [:label {:optional true} :string] [:amount {:optional true} :int]]]"` |

The definition settles a receipt with the Var, the NEXT form calls it and
returns a value, and the contract is a queryable `:seon.fn/spec` fact —
the three things this note asked for. Zero `declaration-row` contract
violations exist on the cluster; the six distinct error signatures
present are an absent `DEEPSEEK_API_KEY`, two external-claim census
refusals, a collection-verification refusal, the `:any` refusal above,
and the arity face noted below.

The forms were authored by the bootstrap plan rather than by a model:
`DEEPSEEK_API_KEY` is not set in this lane's environment, so a
model-driven turn was not available. Everything below the reply — the
run loop, the evaluator, declaration rows, contract facts, receipts —
is the production path either way.

The `(ns … (:require [x :as y]))` acceptance holds as well:
`(ns probe.alias (:require [clojure.set :as set]))` settles through the
cluster's evaluator, and `declaration-row` returns a row whose
`:seon.ns/aliases` component entities validate
`:seon.program/declaration-row`.

### Found while proving it

`(largest)` — a wrong-arity call to an agent's own function — surfaces as
`No such namespace: my.agents.root` (ordinal 10), which names neither the
arity nor the function. Filed as
[A wrong-arity call to an agent function reports a missing namespace](../a-wrong-arity-call-reports-a-missing-namespace.md).
