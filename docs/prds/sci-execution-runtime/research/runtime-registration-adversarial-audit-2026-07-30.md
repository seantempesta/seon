---
type: research
status: active
tags: [sci, program-graph, schema, testing, audit]
---

# Runtime registration adversarial audit — 2026-07-30

## Verdict

The landing at `a117f4603` plus `2c7bea0e6` closes the original missing
schema/function/test publication proof, but it does **not** yet make namespace
registration exact. The schema, function, and test rows themselves are in
good shape. Fresh acquisition is only proven for one ordinary alias and one
unrenamed refer; the persisted namespace representation loses legal SCI
bindings before acquisition.

One blocker remains:

- renamed refers are reconstructed as if the local renamed symbol existed in
  the target namespace;
- multiple aliases to one target collapse to one alias at runtime; and
- `:as-alias` is reconstructed as `:as`, even though those operations have
  intentionally different loading behavior.

The same renamed-refer loss exists in the build reader, where it can silently
drop a test declaration. The recurring restart test is genuine, but its
namespace case is not adversarial enough to prove the claimed exactness.

## Audited boundary

Commits:

- `a117f4603` — runtime namespace capture, namespace-row acquisition, and
  typed deletion mechanics;
- `2c7bea0e6` — restart proof using `seon.schema` and `clojure.string` aliases
  plus a `clojure.test/deftest` refer; and
- `ef92d45b1` — the owner's superseding ordinary-REPL ruling for
  cross-namespace `ns-unmap`.

Primary source:

- `src/seon/sci/eval.clj` — evaluation, namespace capture, terminal
  materialization, and basis acquisition;
- `src/seon/cluster/run.cljc` — terminal transaction validation and exact row
  replacement/deletion;
- `src/seon/sci/reader.cljc` — build/runtime declaration identity and namespace
  parsing;
- `src/seon/program.cljc` — shared declaration row shapes; and
- `resources/seon/schema/program.edn` — persisted namespace component shape.

Dependency source:

- `reference-code/sci/src/sci/impl/load.cljc:95-147` stores aliases and referred
  Vars in the current namespace while applying `:rename`;
- `reference-code/sci/src/sci/impl/namespaces.cljc:478-554` exposes the same
  alias/refers maps from SCI's per-context namespace table;
- `reference-code/sci/src/sci/impl/resolve.cljc:40-53,136-141` resolves
  qualified symbols through `:aliases` and unqualified symbols through
  `:refers`; and
- `reference-code/sci/src/sci/core.cljc:304-323` says the context's internal
  organization is private and that `fork` gives the child its own environment
  atom.

## What is genuinely fixed

### Exact evaluated schema and terminal transaction

`seon.sci.eval/evaluate` evaluates `register!` inside an isolated registration
delta, reads the actual registered definition back from that delta, validates
that value, and puts its canonical EDN in `:seon.schema/form`. It does not
publish the reader syntax. `seon.cluster.run/program-row-tx` repeats pure
candidate validation against the transaction's database value and emits any
new Datahike attribute schema in the same transaction data as the program row
and terminal receipt.

`runtime-schema-registration-commits-the-evaluated-form-and-attribute` proves
all three facts: `(vector ...)` becomes the evaluated vector form, the schema
datom and receipt datom have the same transaction, and a persistence facet is
usable immediately.

### No process-global candidate mutation

The eval path uses `begin-registration-delta` and
`call-with-registration-delta`; it never calls `commit-registration-delta!` or
`activate-projection!`. Committed database projections are returned as values
on the supplied SCI context/acquisition result.

The refusal proof compares both `current-projection` identity and
`registered-schemas` value before/after a failed registration. The A-B-A proof
alternates incompatible definitions and an absent key across two databases,
then checks that the process-global projection was never repointed. The
injected terminal-transaction refusal also proves that post-commit installation
is not called and the evaluation overlay is discarded.

### Function and test materialization

Functions and tests install only from the successful transaction report's
`db-after`, resolved again by exact identity and source. Tests are live SCI
Vars, not merely rows: the focused test executes V1, replaces it with V2,
executes V2, and then removes both the row and binding.

Fresh acquisition reads current program rows without receipts, installs
namespace rows before function/test source, and the restart test stops and
reopens the cluster before checking all of:

- the schema validator;
- the aliased `clojure.string` call inside the acquired function;
- the referred `deftest` Var's `:test` function; and
- a second agent calling the acquired function.

This is a real restart proof, not a same-context check.

### `ns-unmap` retains ordinary SCI REPL semantics

The audit initially treated cross-namespace deletion as an ownership violation.
The owner ruled the opposite: `ns-unmap` remains the real REPL operation. A
successful terminal transaction retracts matching function/test facts by their
typed identities and then unmaps the target binding in this run's SCI context,
regardless of the namespace that evaluated the form. `ef92d45b1` removes the
incorrect ownership fence and proves both an absent foreign target and a
foreign target carrying matching function/test rows.

## Blocker — namespace facts are a lossy encoding of SCI state

### Runtime renamed refer fails in the terminal installer

SCI applies a renamed refer as an exact local-to-Var binding. For:

```clojure
(require '[clojure.string
           :refer [upper-case]
           :rename {upper-case up}])
```

SCI's namespace table contains `up` referring to the Var whose qualified name
is `clojure.string/upper-case`. `reader-context` correctly observes that as
`up -> clojure.string/upper-case`. `require-edges` then discards the target
name and stores only local `up` in `:seon.ns.require/refers`. `require-form`
reconstructs `[clojure.string :refer [up]]`.

The shortest real-turn falsifier used the form above followed by a contracted
function calling `up`. The require evaluation succeeded and produced its
namespace row; installation from the terminal `db-after` failed in vendored
SCI at `load.cljc:135`:

```text
up does not exist
```

So this is not merely a restart edge. The committed row cannot materialize its
own just-evaluated namespace state.

### Runtime multiple aliases and `:as-alias` are also lossy

`require-edges` reduces aliases into a map keyed only by target namespace and
uses `assoc-in [target :seon.ns.require/alias]`. Two local aliases for one
target therefore collapse to one. SCI's actual state is keyed by local alias,
so both are valid and independently resolvable.

SCI records `:as` and `:as-alias` in the same effective alias map. The runtime
projection cannot recover which syntax created the binding, yet
`require-form` defaults the missing flag to `:as`. Replaying an `:as-alias`
for a namespace that intentionally need not exist therefore attempts a real
load and can fail.

### The build reader silently drops renamed declarations

`seon.sci.reader/require-edge` ignores `:rename` and
`namespace-info` derives its reader context from the unrenamed refer set. This
input:

```clojure
(ns demo.rename-test
  (:require [clojure.test
             :refer [deftest]
             :rename {deftest dt}]))
(dt renamed-test :ok)
```

produced a namespace event with `:refers #{deftest}` and a second ordinary
reader event with no `:seon.test/sym`. The test declaration is silently absent
from the build index. By contrast, a build namespace with two aliases for
`seon.schema` retained two component edges and both aliased registrations were
recognized. The multiple-alias collapse is runtime-specific; renamed-refers
loss is shared.

## Archaeology — the exact shape already existed

Commit `57761ddb4` (`feat(program): persist canonical direct edges`) solved the
same representation problem on the prior JVM and CLJS platforms:

- its JVM `namespace-resolution` read SCI's namespace table and stored aliases
  as `local -> target namespace` plus refers as
  `local -> qualified target`, using `sci/var->symbol`; and
- `seon.program.edge` registered exactly
  `::aliases [:map-of :symbol :symbol]` and
  `::refers [:map-of :symbol :qualified-symbol]`.

The old call-edge proof consumed those exact bindings directly. The current
implementation rediscovered the SCI read boundary but compressed the result
back into require syntax, reintroducing the information loss.

## Simplest lossless model

Persist effective bindings, not accumulated `require` source:

- alias component fact: local alias + target namespace;
- refer component fact: local name + target namespace + target name; and
- a bare required-target fact only where dependency/load ordering needs a
  namespace with no alias or refer binding.

These are namespace-owned component facts, replaced as one current set. They
represent SCI's actual resolver inputs and naturally preserve rename, multiple
aliases, and the effective result of `:as-alias`. The pure reader can project
the same facts to its symbol maps. Runtime acquisition can install them into
the per-context SCI namespace table, resolving refer targets to the target SCI
Vars after their namespaces/functions are available.

Because SCI documents the context layout as implementation detail, the clean
dependency seam is a narrow public operation in Seon's maintained SCI fork for
installing effective namespace bindings. Seon should not grow a second
interpreter registry or write SCI's internal atom from several call sites.

Acquisition then needs explicit phases rather than lexical sorting:

1. create every admitted namespace and install alias symbol bindings;
2. install functions in dependency order, making target Vars available;
3. install exact refer bindings; and
4. install tests after their namespace bindings are complete.

Replaying accumulated require source is the more complex model: it stores an
ordered operational history, reruns load behavior, needs accumulation and
unalias semantics, and still has to distinguish syntax from effective state.
The database already wants the current namespace facts at one basis.

## Independent verification

After `ef92d45b1`, the combined focused gate was:

```text
Testing seon.cluster.turn-test
Testing seon.cluster.program-restart-test
Testing seon.program-test
Testing seon.sci.reader-test
Testing seon.fn-test
Testing seon.schema.program-test

Ran 59 tests containing 462 assertions.
0 failures, 0 errors.
```

Before combining the namespaces, the two direct registration/restart
namespaces alone were green at 33 tests and 226 assertions. The green gate is
honest for the cases it claims; it does not cover renamed refers, multiple
runtime aliases, `:as-alias`, or acquisition of refers whose target Var is an
agent-authored function.

## Required exit

Keep the registration issue open until one shared exact namespace-binding
projection is used at build read time and runtime acquisition. The recurring
proof must add:

- renamed `deftest` and renamed `seon.schema/register!` at build time;
- renamed refer, two aliases to one target, and `:as-alias` through a terminal
  runtime transaction and fresh acquisition; and
- an agent-authored target namespace proving acquisition orders target Vars
  before installing referring functions/tests.

Then rerun the focused 59-test boundary and the full gate.
