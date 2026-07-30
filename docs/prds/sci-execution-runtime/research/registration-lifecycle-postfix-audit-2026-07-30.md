---
type: research
status: complete
tags: [sci, program-graph, schema, datahike, audit]
---

# Registration lifecycle post-fix audit — 2026-07-30

## Verdict

The evaluated-index and import-persistence direction is structurally correct,
but the landing at `0fa045900` + `cf900d42e` + `7713bb0bf` was not yet a
production-complete wave. Independent probing found four build blockers: the
inspector inherited a classpath that could not load the test corpus, its cache
key omitted schema resources and maintained dependency source, its final
namespace snapshot included namespaces created only as dependency-load side
effects, and build evaluation had no schema registration delta in which
`unregister!` could operate. These were implementation seams in the new owner,
not reasons to restore static declaration recognition. They are closed by
`80d94e4c4`, `ce0963f07`, `dd05efa40`, and `aaac37105`; `43a9ac92e` and
`5525f4f0d` make the complete exact-lifecycle proof fast and honest.

The runtime and schema lifecycle contracts survived direct review and focused
falsification. SCI import masks round-trip through a fresh context on both
maintained Clojure versions. The database transaction owner uses one affected
schema graph for replacement and removal; it refuses current direct,
transitive, and entity-child data, preserves atomicity, allows the operation
after current retraction, and retains ordinary historical values plus the
historical global schema row. `:seon.db/no-history? true` is the explicit case
where the old value is gone.

No registration-lifecycle blocker remains after the final frozen-tree
falsifiers. The complete-suite and live reset/reopen checkpoints remain the
top-level integration gates; focused evidence here does not replace them.

## Dependency and archaeology check

The design is grounded in real prior implementations rather than a new
scanner. `87ac3f9c6` retains the analyzer-backed load spine;
`d33b29cf9:src/seon/indexing.clj` enumerates functions and tests from the CLJS
analyzer environment, while `d33b29cf9:src/seon/eval.cljs:622-823` diffs
evaluated analyzer definitions and the schema registry after evaluation.
`56ed96dd9` is the later computed cold-boot schema-parity repair. The present
JVM mechanism correctly carries forward the lesson—effective evaluated state
is the oracle—without carrying forward the CLJS machinery.

The maintained dependency boundaries checked here are SCI
`1305a90` (`namespace-bindings` / `install-namespace-bindings!`) and Datahike's
current schema-removal transaction path. No claim below is inferred from an
upstream API name alone.

## Findings against the named landing

### Blocker 1 — the production classpath could not inspect the test corpus

From the ordinary source-classpath surface, this direct production call failed
before producing any rows:

```clojure
(seon.fn/rows {:seon.fn/roots seon.fn/source-roots})
```

The process exited after **20.86 s** with:

```text
Could not locate cheshire/core__init.class, cheshire/core.clj or
cheshire/core.cljc on classpath.
```

The cause was architectural, not Cheshire-specific. `source-roots` includes
`test`, while the default / `:dev` classpath contains only `src` and
`resources`; the complete test environment is the existing `:test` alias,
which also owns `clojure.test.check` and `core.async.flow-monitor`. Replacing
one missing library cannot make production evaluation of the complete test
corpus honest. The inspector must run in the one declared corpus environment.

### Blocker 2 — the cache could return rows for different inputs

`content-digest` hashed the classpath string, requested Clojure files, and
`src` Clojure files. It did not hash `resources/seon/schema/*.edn`, even though
`seon.fn` loads those files and emits their evaluated registry forms. It also
did not hash maintained local dependency source such as SCI, whose evaluator
and namespace semantics affect the result. In a long-lived JVM, either edit
therefore hit `inspected-rows` and skipped the child entirely.

This is the same failure class as the stale-JVM incident: the recorded input
identity described paths, not the bytes that produced the program graph. A
cache is honest only when every local classpath input that can affect
evaluation participates by path and content, including `deps.edn`.

### Blocker 3 — dependency-load namespaces became first-party namespace rows

The first evaluated snapshot recorded every namespace newly present after a
top-level form. A normal `require` creates its transitive dependency
namespaces, so they acquired the requiring form as fake `:seon.ns/source` and
entered the first-party graph. This was a broad side-effect census, not source
ownership.

Commit `80d94e4c4` removed that path. Namespace rows now come only from direct
source namespace declarations or namespaces that own final indexed functions
or tests. Schema identities need no namespace row because they are global.

### Sharp edge — process isolation does not contain external side effects

The active issue and roadmap say that all build-evaluation side effects die
with the child process. Only process-local mutations do. A top-level file,
subprocess, socket, or database side effect crosses the child boundary and
survives. Sequential evaluation is still the correct Clojure/compiler owner
for trusted first-party source, but the durable wording must not imply a
security or effect-containment property the process does not provide.

### Blocker 4 — build evaluation did not establish a schema delta

After the first three repairs and the frozen `43a9ac92e` test-loop landing, an
expanded final-state falsifier registered and then unregistered one schema in
the same indexed source. The direct production/default caller failed after
15.62 seconds with:

```text
schema/unregister! requires an evaluation registration delta.
```

Runtime eval correctly wraps schema-bearing forms in
`begin-registration-delta` / `call-with-registration-delta`; the build
inspector instead evaluated its sequential source population directly against
the child process's candidate population. Registration happened to work
because the process was disposable. Unregistration correctly refused the
missing transaction-like scope. The unified repair in `aaac37105` is one delta
around the complete sequential build population and its final schema snapshot,
not a build-only unregister special case. Build explicitly uses `:core`
admission while runtime keeps `:agent` admission; both now use the same
registration-delta mechanism.

## What is genuinely solid

- `eval` / `apply` namespace mutation, computed schema values, indirect
  function and test definitions, evaluated `in-ns`, and return from a third
  namespace are observed from actual final JVM state. There is no resolver
  operation blacklist left.
- Function rows come from actual final Vars, including private functions;
  tests are Vars carrying `:test`; final absence is the natural deletion
  representation.
- `seon.schema/canonical-definition` refuses unnamed callables and converts
  evaluated named predicate roots back to EDN symbols before function
  contracts or schema forms cross into rows.
- SCI persists imports as `{local-symbol target-class-symbol}` and persists a
  removed default import as `{local-symbol nil}`. A direct current/fresh probe
  returned `String => nil` in both contexts.
- Runtime namespace state is installed only after the terminal database commit;
  the refusal regression leaves the supplied run context unchanged.
- Schemas have one global `:seon.schema/key` identity and no namespace owner.
  Namespace-derived lookup remains only a presentation/query convenience.
- Schema removal dependency blockers are derived from the immutable projection:
  reverse-transitive schema dependents plus function contracts. Current data
  checks derive the affected database attributes from those same forms.

## Verification record

- Maintained SCI `sci.namespaces-test`, Clojure 1.10.3:
  **40 tests / 160 assertions / 0 failures / 0 errors**.
- Maintained SCI `sci.namespaces-test`, Clojure 1.11.0-alpha1:
  **40 / 160 / 0 / 0**.
- Direct SCI serialization/reacquisition probe:
  `{:imports {String nil}, :current nil, :fresh nil}`.
- Git archaeology opened the exact files in `87ac3f9c6`, `d33b29cf9`, and
  `56ed96dd9`; the evaluated-state claim is supported, while the newer JVM
  implementation is not claimed to be a literal port.

### Post-repair build and lifecycle proof

- The exact post-`aaac37105` build falsifier covered `eval` and `apply`
  aliases, computed registration, register-then-unregister, evaluated `in-ns`,
  direct and indirect functions, an indirect test, a third namespace,
  function/test unmap, and return to the prior namespace. The computed schema,
  three functions, one test, and three source-owned namespaces were present;
  the unregistered schema and both unmapped Vars were absent.
- A default `clojure -M:dev` parent produced **116 namespace / 1,331 function /
  552 schema / 608 test rows**, including **867 private functions**. Cold
  evaluation took **16,812.94 ms** and the identical cached call took **111.68
  ms** with identical rows. All **953** serialized function contracts and
  schema forms EDN-read successfully; none contained an object tag.
- Final `seon.fn-test` on `aaac37105` + `5525f4f0d` passed **8 tests / 65
  assertions / 0 failures / 0 errors**. It retains real V1→V2→V3 file mutation
  and now recurs build registration followed by unregister in the shared exact
  population.
- The runtime acquisition, schema registration/removal/refusal, alternating
  cluster projections, unmap, restart, and cross-agent call matrix passed **21
  tests / 121 assertions / 0 failures / 0 errors** before the build-delta
  repair; `aaac37105` separately passed its synthetic build and
  default-parent/runtime unregister proofs at **3 tests / 26 assertions / 0
  failures / 0 errors**.
- The user's exact history question passed independently: unused removal,
  removal after retract with historical reconstruction, and the explicit
  no-history exception passed **3 tests / 11 assertions / 0 failures / 0
  errors**. The probe replaced ancestor indexing with a no-op only because a
  separate lane then had an in-flight syntax error; it exercised the real
  schema population, Datahike transactions, `program-row-tx`, historical
  database value, and projection validator.

## Final ruling

Schemas are global identities. A function contract points to schema keys; an
agent context may reverse-collect those keys from the namespace's functions,
but schema ownership never follows the function namespace. A schema change or
removal refuses while current data exists on any directly or transitively
affected database attribute, and removal also refuses while a schema or
function contract depends on it. Once those current datoms and dependencies
are gone, change or removal is allowed.

Ordinary historical database values retain the old datoms and historical
global schema row, which reconstructs the validator needed by a simulation.
Datahike's physical schema map itself does not time-travel. An attribute
explicitly declared with `:seon.db/no-history? true` does not promise recovery
of its old values.

The final blocker ranking is empty. One durable sharp edge was corrected in
the active issue and roadmap: child evaluation contains process-local
registration state only, not external effects. Their SCI version claim was
also corrected to the vendored alias's actual Clojure 1.11.0-alpha1 dependency.
