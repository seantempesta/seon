---
type: research
status: complete
tags: [research, testing]
---

# Function-level affected-test selection

## Decision

Affected-test selection is a pure derivation over one immutable database
value of the N5 program graph:

```text
changed file spans
  → semantically changed callable symbols
  → reverse `:seon.program.edge/calls` closure
  → `:seon.test/sym` roots
  → distinct `:seon.ns/name` suites
```

Any missing, stale, incomplete, or dynamically unresolved input widens the
result. It never removes a test. This is the plan's settled target and replaces
the hook's clj-kondo namespace graph rather than adding another graph builder
(`docs/prds/sci-execution-runtime/plan/README.md:288-293`,
`docs/prds/sci-execution-runtime/plan/README.md:763-769`).

This document is specification only. The selector must not be implemented
until N5 publishes the dependency contract in [What N5 must emit](#what-n5-must-emit).
N5 is the rung that makes authored functions durable program facts and
acquires them at a database basis
(`docs/prds/sci-execution-runtime/plan/README.md:749-761`).

## Dependency ledger

| Dependency or mechanism | Selected revision | Evidence and use |
|---|---|---|
| Seon checkout | `f399551fda53e81cf029e83ba949751012360c4c` | The current hook, JVM indexer, edge analyzer, and plan ruling are the quarry for this specification. |
| Clojure | 1.12.5 | The fresh project pins Clojure 1.12.5 (`deps.edn:13-14`). The indexer reads the `:clj` projection of `.clj` and `.cljc` source (`script/seon/dev/program_indexer.clj:49-67`, `script/seon/dev/program_indexer.clj:173-180`). |
| Datahike | `reference-code/datahike` at `caf526850084` | The fresh project selects the maintained checkout (`deps.edn:19-21`). Selection reads one immutable database value; it does not build a mutable cache or a second persisted graph. |
| `seon.program.edge` | checkout revision above | This is the existing direct-call producer and vocabulary. It defines `:seon.program.edge/calls`, `:seon.program.edge/generation`, and the uncertainty set (`src-old/seon/program/edge.cljc:8-25`, `src-old/seon/program/edge.cljc:75-89`). |
| `bin/test` | checkout revision above | The surviving gate accepts zero or more test namespace symbols and calls `clojure.test/run-tests` (`bin/test:15-26`, `bin/test:34-44`). Selection therefore returns suites, not an invented test runner. |

No behavior in this specification depends on clj-kondo. The current selector's
clj-kondo invocation is quarry only (`script/seon/dev/changed_test.clj:118-119`,
`script/seon/dev/changed_test.clj:217-244`).

## Quarry: the current selector

The edit hook currently extracts only changed paths and enqueues those paths;
it carries no line spans or source identity
(`bin/seon-hook:654-685`,
`script/seon/dev/changed_test.clj:82-116`). The manual command has the same
path-only contract (`script/seon/dev/cli.clj:1243-1257`).

`changed_test.clj` asks clj-kondo for namespace definitions and namespace
usages, converts them into path-to-namespace ownership and `from → required
namespace` edges, and retains runner test namespaces
(`script/seon/dev/changed_test.clj:178-215`). It then seeds the changed
namespace, computes reverse namespace closure, and intersects that closure
with the runner roots (`script/seon/dev/changed_test.clj:246-254`,
`script/seon/dev/changed_test.clj:268-316`).

Consequently, every edit to a known source file changes the same namespace
seed. A one-line docstring edit and a function-body edit therefore select the
same namespace closure; the selector never reads the changed forms or lines
(`script/seon/dev/changed_test.clj:271-278`). This is the known imprecision the
replacement must falsify.

The current mechanism does already have the correct safety direction:
unavailable host analysis or an unknown relevant resource selects a whole
retained boundary, not an empty set
(`script/seon/dev/changed_test.clj:281-310`,
`script/seon/dev/changed_test.clj:317-326`). The replacement preserves that
fail-wide law.

The execution seam is also already sufficient. `run-writer!` passes selected
namespace symbols directly to `bin/test`, and the surrounding code preserves
locking, process cleanup, reports, and the `:no-affected-tests` result
(`script/seon/dev/changed_test.clj:482-502`,
`script/seon/dev/changed_test.clj:504-540`). Only impact selection is replaced.

## Quarry: the program graph

### What the JVM indexer emits today

The indexer inventories `.clj` and `.cljc` files below `src/`, parses the
`:clj` reader projection, and retains the selected production namespace
closure (`script/seon/dev/program_indexer.clj:49-73`,
`script/seon/dev/program_indexer.clj:92-149`). It separately discovers JVM
test files through the existing test-root owner
(`script/seon/dev/program_indexer.clj:411-413`,
`script/seon/dev/test_roots.clj:108-136`).

For each production `defn` or `defn-`, it emits a `:seon.fn/sym` row with its
namespace ref and exact form source
(`script/seon/dev/program_indexer.clj:192-227`,
`script/seon/dev/program_indexer.clj:243-261`). It sends those forms through
`edge/analyze-function` and attaches:

- `:seon.program.edge/generation`;
- `:seon.program.edge/calls`;
- read and written database attributes;
- `:seon.program.edge/uncertainties`; and
- terminal refs

(`script/seon/dev/program_indexer.clj:331-390`).

The direct call set is a cardinality-many string attribute on the callable
entity (`src-old/seon/program/edge.cljc:10-25`,
`src-old/seon/program/edge.cljc:86-89`). Exact resolved call heads and closed
higher-order function values enter that set; unresolved heads, open
higher-order calls, macro invocations, and dynamic calls enter the uncertainty
set (`src-old/seon/program/edge.cljc:393-425`,
`src-old/seon/program/edge.cljc:427-465`). The existing regression proves a
named function passed to `map`, returned bare, or stored in a literal remains a
call edge, while an open function argument is uncertain
(`test-old/seon/program_edge_test.cljc:135-151`).

The persisted transition retracts the prior exact edge projection and asserts
the current calls and uncertainties on the `:seon.fn/sym` entity
(`src-old/seon/program/edge.cljc:550-574`). Its generation hashes the canonical
edge bundle, and a program-graph digest function already hashes ordered bundles
(`src-old/seon/program/edge.cljc:467-525`).

### What it does not emit

Test rows currently contain only `:seon.test/sym`, `:seon.test/ns`, and
`:seon.test/source`; they are not sent through the edge analyzer
(`script/seon/dev/program_indexer.clj:392-409`). `indexed-function-rows`
receives only selected production descriptions, while the test descriptions
are used only for namespace and test rows
(`script/seon/dev/program_indexer.clj:500-519`). Therefore the current facts
cannot answer which `deftest` calls a changed function, and helper `defn`s in
test namespaces are absent from the call graph.

The indexer computes a repository-relative resource path in its transient
source description, but namespace, function, and test rows do not persist that
resource (`script/seon/dev/program_indexer.clj:75-90`,
`script/seon/dev/program_indexer.clj:211-241`,
`script/seon/dev/program_indexer.clj:392-409`). It also retains exact form
source but no source line span (`script/seon/dev/program_indexer.clj:151-190`).

Finally, an empty `:seon.program.edge/calls` set is not evidence of complete
analysis. The analyzer records some uncertainty classes, but the stored row
omits the uncertainty attribute when the set is empty
(`script/seon/dev/program_indexer.clj:367-389`). Treating absence of calls and
absence of uncertainty as health would repeat the known
absence-of-signal-as-health failure mode. N5 must publish an explicit
completeness fact.

## Required selection input

The pure selector receives:

1. one immutable N5-ready database value;
2. the normalized repository-relative changed paths from the existing hook;
3. the current bytes of those paths; and
4. the current bytes or absence of every other resource in N5's indexed
   resource inventory.

The current hook already normalizes and coalesces repository-relative paths
(`script/seon/dev/changed_test.clj:121-139`,
`script/seon/dev/changed_test.clj:99-110`). It does not need to preserve
line numbers across a burst. At execution time, the selector diffs each
changed resource's stored `:seon.ns/source` against its current bytes and
derives old and new half-open line spans. This makes a coalesced generation
describe every edit since the indexed source, even when earlier insertions
shift later line numbers.

Each derived change is ordinary data:

```clojure
{:seon.dev.changed-test/path "src/seon/example.clj"
 :seon.dev.changed-test/before-spans [[18 19]]
 :seon.dev.changed-test/after-spans [[18 19]]}
```

Line numbers are one-based; each span is `[start-line end-line-exclusive]`.
Insertion has an empty before span, deletion has an empty after span, and a
replacement has both. A caller that can supply only a path still uses this
derivation. If stored source, current source, or a line diff is unavailable,
that resource is unknown and widens.

## Changed spans to changed callables

For each changed resource, parse both the stored and current `:clj`
projections one top-level form at a time. Associate every form with the line
span consumed by the same reader pass that returns its exact source slice. The
quarry already performs the form-and-source pass, so this extends the one
reader rather than adding a second analyzer
(`script/seon/dev/program_indexer.clj:151-190`).

The supported top-level callable forms are `defn`, `defn-`, and `defmacro`.
The supported test-root form is `deftest`. A changed test helper is an ordinary
callable in its test namespace. A changed `deftest` is selected directly as a
test root.

A diff span is classified as follows:

- Whitespace, commas, comments, and source-location metadata disappear under
  reading and select nothing.
- A `defn`, `defn-`, or `defmacro` docstring-only change selects nothing.
  Compare a semantic form projection that removes the optional docstring but
  retains the name, attr map other than `:doc`, arglists, pre/post maps, and
  body. A `deftest` body is never stripped.
- Any other change to that semantic projection seeds the old qualified symbol
  and, when it still exists, the new qualified symbol.
- A changed `deftest` semantic projection also seeds that test root directly,
  because the suite itself changed even if its callees did not.
- An added, removed, or renamed callable seeds every identity that exists in
  the stored graph. If a new identity has no graph row, selection widens to all
  test suites because no current reverse edge can yet point to it.
- A changed `ns` form, a changed top-level executable form outside the
  supported set, a reader failure, or overlapping forms that cannot be paired
  by qualified identity widens to all test suites.

The docstring exclusion is deliberate: the docstring hook owns docstring
structure, while affected tests target executable function changes. The
current hook runs Markdown/docstring validation separately from changed-test
feedback (`bin/seon-hook:262-282`, `bin/seon-hook:294-296`).

## Graph and reverse closure

### Nodes and edges

A callable node is a `:seon.fn/sym` entity emitted for a production or test
helper function or macro. A test-root node is a `:seon.test/sym` entity emitted
for a `deftest`. Both carry the same canonical outgoing attribute,
`:seon.program.edge/calls`; this preserves one graph rather than introducing
`:seon.test/calls`.

For a callable or test root `caller`, each string in
`:seon.program.edge/calls` names one statically resolved callee. A call whose
target is another indexed callable is an internal graph edge. A target outside
the indexed resource population is a terminal and does not need a callable
row. A target whose namespace is indexed but whose callable row is absent is
an incomplete graph and widens to all suites.

### Closure

Let:

- `S` be the changed callable symbols;
- `E` be the internal `caller → callee` edges;
- `U` be every callable or test-root node whose mandatory
  `:seon.program.edge/calls-complete?` is explicitly false; and
- `R` be the least fixed point beginning with `S ∪ U` and repeatedly adding
  each caller whose outgoing callees intersect `R`.

The test roots are `(R ∩ test-root-nodes)` plus directly changed `deftest`
roots. Map each root through `:seon.test/ns` to `:seon.ns/name`, deduplicate,
and sort by symbol before calling `bin/test`. The runner remains
namespace-granular because that is its existing public selector
(`bin/test:15-26`, `bin/test:34-44`).

Including `U` makes every incompletely analyzed node a possible caller of
every changed function. Reverse closure then selects each suite that reaches
such a node. A missing completeness fact is a broken N5 contract and selects
`:all`; explicit false produces the narrower fail-wide closure. Either case may
run more suites and cannot hide a suite behind a missing edge.

## Test-root mapping

N5 analyzes a `deftest` body exactly like a function body and stores its direct
call edges on the `:seon.test/sym` entity. Ordinary example tests therefore
name exercised functions by calling them.

Generative coverage follows the same graph:

- A named property helper is an indexed `:seon.fn/sym` in the test namespace.
  The `deftest` calls the helper, and the helper calls production functions.
- A named function passed as a higher-order value is a direct call edge. The
  quarry already walks nested function bodies and expression arguments
  (`src-old/seon/program/edge.cljc:375-425`,
  `src-old/seon/program/edge.cljc:427-465`).
- An anonymous property body is walked recursively, so its statically
  resolved calls belong to the enclosing test root
  (`src-old/seon/program/edge.cljc:375-383`,
  `src-old/seon/program/edge.cljc:452-465`).

Some properties exercise a function only through quoted commands, a schema,
data-driven dispatch, or another representation the call analyzer cannot
resolve. Such a `deftest` names those functions with metadata:

```clojure
(deftest ^{:seon.test/exercises
           #{seon.cluster.run/open-tx
             seon.cluster.run/claim-tx}}
  generated-run-transitions
  ...)
```

`:seon.test/exercises` is producer input, not a second persisted edge
relation. N5 validates every value as a current qualified callable symbol and
merges it into the test root's `:seon.program.edge/calls`. A missing or invalid
symbol makes `:seon.program.edge/calls-complete?` false. If the analyzer sees
indirect or dynamic call syntax without either a resolved edge or an explicit
exercise, it also marks the root incomplete.

This annotation is required only for genuinely indirect coverage. A property
that directly or higher-order calls the production function needs no
annotation. The result remains one reverse call graph and one test-root
mapping.

## Widening rules

Widening is monotone. Each rule unions tests into the selected set; no rule
subtracts a root already derived.

### Unknown callee

An unresolved symbol, dynamic call head, open higher-order value, or other
unknown callee sets `:seon.program.edge/calls-complete?` false and records the
specific `:seon.program.edge/uncertainties` values. The quarry already names
`:dynamic-call`, `:open-higher-order`, `:unresolved-symbol`, and
`:value-passed-pattern` (`src-old/seon/program/edge.cljc:16-24`,
`src-old/seon/program/edge.cljc:393-425`). The incomplete node joins `U`, so
every suite that could reach it is selected.

### Macro edges

A statically resolved macro invocation emits a normal call edge to the indexed
`defmacro` node. Its analyzed expansion contributes any statically resolved
calls introduced by the macro. If the macro cannot be expanded at the indexed
basis, or the expansion contains unresolved calls, the caller is incomplete.
The quarry currently records only `:macro-expansion` uncertainty instead of a
call edge (`src-old/seon/program/edge.cljc:409-412`), so N5 must strengthen
this producer before the selector can build.

### Dynamic resolution

Calls through `resolve`, `requiring-resolve`, multimethod dispatch, protocol
dispatch without a closed target set, maps of functions, or constructed
symbols are complete only when the analyzer derives every first-party target.
Otherwise the node joins `U`. An explicit `:seon.test/exercises` declaration
can close an indirect test root, but it does not claim that arbitrary
production dynamic dispatch is complete.

### Absence is not health

Missing `:seon.program.edge/calls-complete?`, missing
`:seon.program.edge/generation`, a missing resource fingerprint, or a test root
without edge analysis widens to all suites. An empty calls set is precise only
when `calls-complete?` is explicitly true. An empty uncertainties set, whether
stored as no datoms or an empty pull result, is never used as the completeness
signal.

### Non-program inputs

Changes to `deps.edn`, `bin/test`, schema files, build inputs, or any source
outside N5's callable/resource inventory select all suites. The current
selector already treats dependency and runner changes as full-boundary inputs
(`script/seon/dev/changed_test.clj:288-310`).

## Staleness and database-basis handling

The selector captures one immutable database value and performs every query
against it. It never rereads a connection midway through derivation.

N5 facts are expected to lag the edited tree for the reported changed
resources; their stored source is the before-image used to derive spans. The
lag is safe only under these checks:

1. The database value carries N5's explicit ready/completion fact.
2. Every indexed resource has exactly one `:seon.ns/resource`, full
   `:seon.ns/source`, and matching `:seon.ns/source-fingerprint`.
3. Every callable and test root refers to that resource's namespace and was
   edge-analyzed from the same source fingerprint in one resource transaction.
4. Every unreported indexed resource's current bytes match its stored source
   fingerprint.
5. Every reported resource's stored source can be diffed against its current
   bytes, including a reported deletion.

Failure of checks 1-3 means there is no trustworthy graph and selects all
suites. Failure of check 4 means the hook missed a tree change and selects all
suites. Failure of check 5 makes that changed resource unknown and selects all
suites.

A graph update racing selection is harmless: the captured database value is
immutable. The next hook generation may use the newer basis. The selector
records the database basis transaction and N5 graph digest in its existing
report so a surprising selection is reproducible.

The quarry is build-time and freezes rows into initialization pages
(`script/seon/dev/program_indexer.clj:496-535`). N5 must make those rows
queryable current facts before this selector replaces clj-kondo, exactly as the
plan requires (`docs/prds/sci-execution-runtime/plan/README.md:288-293`).

## Hook integration seam

Replace these selector-only pieces:

- `host-analysis-config`;
- `analysis->host-graph`;
- `analyze-host`;
- namespace `reverse-closure`; and
- `host-impact`

(`script/seon/dev/changed_test.clj:118-119`,
`script/seon/dev/changed_test.clj:175-326`).

Keep:

- normalized path collection and coalesced hook generations
  (`script/seon/dev/changed_test.clj:82-139`);
- the worker lock, process-tree cleanup, bounded reports, and advisory status
  (`script/seon/dev/changed_test.clj:328-470`,
  `script/seon/dev/changed_test.clj:542-628`);
- `run-writer!`; and
- `bin/test` as the sole JVM correctness gate
  (`script/seon/dev/changed_test.clj:482-487`, `bin/test:1-8`).

The new selector returns either a sorted vector of test namespace symbols or
`:all`. `run-writer!` receives that value unchanged. A zero-selection result
does not launch `bin/test` and preserves `:no-affected-tests`
(`script/seon/dev/changed_test.clj:489-496`,
`script/seon/dev/changed_test.clj:519-525`).

No clj-kondo process, namespace-usage graph, Shadow graph, source watcher, or
new test runner survives this seam.

## What N5 must emit

The selector may build only after all of the following are queryable from one
N5-ready database value.

### Resource facts

For every indexed production and `test/` source resource:

- one `:seon.ns/name` identity;
- one repository-relative `:seon.ns/resource` string;
- full `:seon.ns/source`;
- one SHA-256 `:seon.ns/source-fingerprint`; and
- the namespace's require edges.

`:seon.ns/resource` is unique identity for source ownership: one current
resource maps to one namespace and one namespace maps to one resource. The
quarry already computes the repository-relative resource and full source but
drops the resource from persisted rows
(`script/seon/dev/program_indexer.clj:75-90`,
`script/seon/dev/program_indexer.clj:229-241`).

### Callable facts

For every `defn`, `defn-`, and `defmacro` in production or test resources:

- `:seon.fn/sym`, `:seon.fn/ns`, and exact `:seon.fn/source`;
- `:seon.fn/source-fingerprint`;
- `:seon.program.edge/generation`;
- zero or more `:seon.program.edge/calls`;
- zero or more `:seon.program.edge/uncertainties`; and
- mandatory `:seon.program.edge/calls-complete?`.

The callable source fingerprint identifies the exact `:seon.fn/source` form.
That form must occur exactly once in the owning namespace's stored source.
Updating a resource retracts removed callable rows and replaces the namespace
source, all remaining callable sources, and all edge facts in one transaction.

### Test-root facts

For every `deftest` in every `test/` resource:

- `:seon.test/sym`, `:seon.test/ns`, and exact `:seon.test/source`;
- `:seon.test/source-fingerprint`;
- `:seon.program.edge/generation`;
- zero or more `:seon.program.edge/calls`;
- zero or more `:seon.program.edge/uncertainties`; and
- mandatory `:seon.program.edge/calls-complete?`.

The test source fingerprint identifies the exact `:seon.test/source` form,
which must occur exactly once in the owning namespace's stored source.
`:seon.test/exercises` metadata is validated and merged into the canonical
calls attribute; it is not stored as a parallel edge set.

The quarry already discovers every JVM test file and gives every `deftest` a
stable qualified identity (`script/seon/dev/test_roots.clj:90-117`,
`script/seon/dev/program_indexer.clj:198-202`,
`script/seon/dev/program_indexer.clj:392-409`). N5 must add edge analysis and
test-helper callable rows.

### Snapshot facts

N5 publishes:

- a ready fact that is present only after the complete resource population is
  queryable;
- one graph digest over sorted
  `[resource source-fingerprint node-identity edge-generation
  calls-complete?]` tuples; and
- the basis transaction at which the ready fact and digest are observed.

The graph digest uses the existing canonical edge-generation inputs rather
than inventing a second call analysis
(`src-old/seon/program/edge.cljc:467-525`). Bootstrap publication and every
later resource replacement must never expose ready with a partial resource
population.

These facts are the precise N5 → affected-test dependency contract. Missing any
one of them keeps the selector unbuilt; a compatibility read of the quarry's
partial rows is forbidden.

## Acceptance criteria

Each acceptance case uses a sealed fixture graph with at least two unrelated
test suites and inspects both the selected namespaces and whether `bin/test`
was invoked.

### Docstring-only falsifier

Given a complete graph and a one-line change only to a `defn` docstring:

- derived changed callable symbols are empty;
- selected test namespaces are empty;
- status is `:no-affected-tests`; and
- `bin/test` is not launched.

This fails the current path-to-namespace behavior, which seeds the namespace
without inspecting the changed form (`script/seon/dev/changed_test.clj:271-278`).

### Body-edit falsifier

Given complete edges `suite-a/test-a → app/caller → app/subject` and unrelated
`suite-b/test-b → other/function`, changing only the body of `app/subject`:

- seeds exactly `app/subject`;
- reaches exactly `app/caller` and `suite-a/test-a` in reverse closure;
- invokes `bin/test suite-a`; and
- does not select `suite-b`.

The assertion is on the exact namespace set, not merely containment.

### Unknown-edge falsifier

Given the body-edit fixture plus `suite-c/test-c → app/dynamic-caller`, where
`app/dynamic-caller` has an unknown callee and
`:seon.program.edge/calls-complete? false`, changing `app/subject`:

- retains the exact known reverse-closure suite;
- also selects `suite-c` through the incomplete-node widening set `U`; and
- reports the uncertainty reason.

If `calls-complete?` is absent rather than false, the result is `:all`, proving
that absence cannot masquerade as health.

### N5 contract falsifiers

- A named generative property helper in `test/` is a callable node, and its
  production calls lead back to the owning `deftest`.
- An indirect property with valid `:seon.test/exercises` metadata reaches the
  named production functions through canonical calls.
- An unresolved explicit exercise makes the test root incomplete.
- A macro edit reaches every suite with a known call edge to that macro; an
  unexpandable macro call widens.
- A stale unreported resource selects `:all`.
- A reported docstring edit remains zero even though its stored resource
  fingerprint differs from current bytes.
- A missing N5 ready fact, resource path, source fingerprint, edge generation,
  or completeness fact selects `:all`.

## Non-goals

This specification does not implement N5, change the test runner, select
individual `deftest` vars, infer coverage from runtime instrumentation, or
persist a reverse graph. Reverse edges and test suites are derived from forward
facts at one database value. Full suites remain the frozen-tree checkpoint
gate; affected selection remains the per-edit advisory cadence
(`docs/prds/sci-execution-runtime/plan/README.md:288-293`).
