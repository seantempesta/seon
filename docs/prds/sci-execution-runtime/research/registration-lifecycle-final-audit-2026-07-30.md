---
type: research
status: complete
tags: [sci, program-graph, schema, datahike, audit]
---

# Registration lifecycle final audit — 2026-07-30

## Verdict

The correction through root `75557d8e2` and maintained SCI `d84a010` is
**not closed and must not be called perfect**. The direct build falsifiers from
the prior audit now refuse, but the fix is another hand list: invoking the same
namespace mutations through ordinary Clojure evaluation silently drops or
misidentifies declarations. The recurring independent census shares this exact
blind spot and therefore reports green.

The runtime registration and schema lifecycle work is otherwise strong. The
focused root gate passed **93 tests / 611 assertions / 0 failures / 0 errors**,
the maintained SCI namespace gate passed **39 / 159 / 0 / 0**, and direct
probes confirmed evaluated runtime schema rows, sequential alias/require
reading, computed cross-namespace deletion, current-data refusal, removal after
retraction, ordinary history, and the explicit `noHistory` exception. Those
results do not cover the counterexamples below.

One further runtime durability gap remains: import-only `ns-unmap` is exact in
the current run context after commit, but fresh acquisition reconstructs the
removed default import. The complete SCI namespace snapshot is transient and
the database row does not represent import masks.

## Blocker 1 — the build resolver grammar is a direct-call hand list

`evaluated-resolver-operations` enumerates eight resolved operation symbols,
and `evaluated-resolver-dependency` recognizes only a direct call or direct
children of `do` (`src/seon/sci/reader.cljc:442-458`). `build-admit-event`
refuses only when that marker is present (`src/seon/fn.clj:68-95`). Ordinary
Clojure can invoke any of those operations through `eval`, `apply`, a resolved
Var, a helper function, or an initializer. None is outside Clojure source
semantics, and none is recognized by this finite syntactic set.

Three production probes used separate source roots and called
`seon.fn/rows` directly.

### Silent schema drop through `eval` and `apply`

```clojure
(ns audit.eval.alias)
(eval '(alias 'schema 'seon.schema))
(schema/register! ::x :int)
```

Production output, projected to declaration identities:

```clojure
[{}]
```

Only the namespace row exists. There is no schema row and no refusal. Replacing
the second form with the following has the same output:

```clojure
(apply alias ['schema 'seon.schema])
```

An actual sequential Clojure load proves the mutation is real rather than a
theoretical reachability concern:

```clojure
(ns audit.load.alias)
(eval '(alias 'str 'clojure.string))
(def result ::str/x)
```

After `load-file`, `audit.load.alias/result` is
`:clojure.string/x`.

### Wrong global schema identity through `eval`-wrapped `in-ns`

```clojure
(ns audit.eval.a)
(eval '(in-ns 'audit.eval.b))
(seon.schema/register! ::x :int)
```

Production indexing succeeds and emits:

```clojure
{:seon.schema/key :audit.eval.a/x,
 :seon.schema/form ":int"}
```

Actual sequential Clojure loading places the next definition in
`audit.eval.b` and resolves `::x` to `:audit.eval.b/x`. The index therefore
persists a wrong **global** identity, not merely incomplete namespace
attribution.

### Why the census is green for the wrong reason

The tools.reader census advances only direct `ns`, direct quoted `in-ns`, and
direct `do` children (`test/seon/fn_test.clj:205-264`). Its declaration
recognizer uses only the bindings that small state machine already knows
(`test/seon/fn_test.clj:150-203`). It neither evaluates nor refuses the
`eval`/`apply` forms, so it misses the same later declaration or assigns the
same stale schema identity as production. The complete tree census test passes,
but independence of implementation is not independence of semantic blind spot.

The current full-tree production inventory was **2,037 rows**: 116 namespaces,
1,305 functions, 7 in-source schemas, 609 tests, and 850 private functions.
Those are honest counts for the current tree, not proof that the reader admits
all legal source that the build contract claims to admit.

### Required repair

Do not add `eval`, `apply`, `load-string`, `resolve`, or helper-call patterns to
the set. Static call reachability still cannot decide arbitrary evaluation.
There are only two honest designs:

1. restore an actual sequential evaluated analyzer/compiler owner and derive
   namespace state plus registered values from its effective state—the
   historical `87ac3f9c6` / `d33b29cf9` direction; or
2. define a genuinely closed declarative build grammar and refuse **every**
   top-level executable form outside that grammar in a source unit that can
   contribute later declarations.

The second choice means an allowlist of proven inert/declarative *form shapes*,
not a denylist of known namespace mutators. It will deliberately reject helper
calls, `eval`, `apply`, executable `def` initializers, and unknown macros until
they are moved behind a function body or handled by the evaluated analyzer.
Anything less can be bypassed by another level of indirection.

## Blocker 2 — import removal is committed but not reacquired

The maintained SCI seam snapshots the complete `:namespaces` map and replaces
that complete map after the terminal commit
(`reference-code/sci/src/sci/core.cljc:720-738`; `src/seon/sci/eval.clj:
758-766,838-862`; `src/seon/sci/eval.clj:537-544`). That is exact for the
current serial fold: `src/seon/cluster/loop.cljc:1023-1052` performs the
transaction and immediately installs the snapshot before the next form. No
current call-site interleaving was found that can clobber a newer namespace
change.

The snapshot is not a durable database representation, however. Namespace rows
store requires, aliases, and refers; `acquire!` reconstructs those through
`install-namespace-bindings!`, whose contract has no imports or nil import
masks (`reference-code/sci/src/sci/core.cljc:740-777`). The private
`::namespace-state` value rides only the in-process evaluation row and is not a
program fact.

The shortest fresh-context equivalent of acquisition produced:

```clojure
{:removed-now false,
 :fresh-acquisition true}
```

Here `:removed-now` is `(some? (resolve 'String))` after import-only
`ns-unmap` in the committed run context, and `:fresh-acquisition` is the same
query in a fresh base fork after installing the persisted empty
requires/aliases/refers bindings. The first is correctly false; the second is
wrongly true.

Thus the landed tests prove commit/refusal ordering within one live ctx, but
not restart durability. Either persist the exact per-namespace resolver delta
needed for import masks and reacquire it, or explicitly rule import removal
process-local and stop representing its transaction as durable namespace
registration. Given that standalone require/alias/refers already survive
acquisition and the surface is described as a real REPL, the consistent repair
is the former.

The SCI API's complete-map replacement is also broader than the logical
operation. It is safe at the current serial per-agent call site, but should not
become a general shared-context API: the snapshot contains every namespace and
process-local Var value. A per-namespace exact delta/state operation would make
the ownership boundary explicit and avoid future whole-context clobber.

## What is genuinely closed

- Direct standalone `alias`, computed `require`, computed `in-ns`, and direct
  resolver operations are now loud build refusals rather than stale guesses.
- Computed schema syntax such as `(vector :int)` is refused at build time;
  runtime evaluates it and commits its canonical value. Literal data uses an
  identity-based private sentinel, so a user value cannot collide with the
  refusal marker (`src/seon/fn.clj:30-66`). Quoted symbols are stripped to the
  same literal value before build serialization.
- Runtime replies semantically read one form against the SCI state left by the
  preceding settled form. The alias and computed-require turn tests pass.
- Qualified computed cross-namespace function/test deletion commits typed
  identities, is absent from fresh acquisition, and survives cluster reopen.
- Import-only removal is installed only after commit, and injected refusal
  leaves the original resolver state unchanged in the covered case.
- Global schema change/removal refuses while direct, transitive, or entity-child
  current data exists. After current retraction it succeeds. Ordinary history
  plus the historical global schema row rebuilds old validation; Datahike's
  physical schema map itself does not time travel. `:seon.db/no-history? true`
  intentionally forfeits the old value.
- The complete physical schema diff preserves independently registered leaf
  attributes when an entity/composite schema changes or is removed.

## Verification performed

- Root registration/runtime/schema focus:
  `seon.fn-test`, `seon.sci.reader-test`, `seon.program-test`,
  `seon.sci.eval-test`, `seon.cluster.turn-test`,
  `seon.cluster.program-restart-test`, and
  `seon.schema-usage-guard-test` — **93 tests / 611 assertions / 0 / 0**.
- Maintained SCI `sci.namespaces-test` on its default Clojure 1.10.3 test
  alias — **39 / 159 / 0 / 0**.
- Production full-tree indexing — **2,037 rows**, with the family counts above.
- Production `seon.fn/rows` probes for direct prior falsifiers, computed schema
  refusal, `eval`-wrapped alias, `apply`-wrapped alias, and `eval`-wrapped
  `in-ns`.
- Actual JVM `load-file` probes for sequential alias and `in-ns` reader state.
- Direct maintained-SCI current-context versus fresh-acquisition import-mask
  probe.

## Acceptance boundary

The wave is closed only when:

1. the three indirect build counterexamples above either produce their exact
   declaration identities or refuse at the first unsupported executable form;
2. the independent recurring census cannot share production's resolver-state
   approximation—prefer an evaluated analyzer oracle, or test the closed
   grammar by generating indirect executable forms and requiring refusal;
3. import-only `ns-unmap` remains absent after fresh database acquisition and a
   real cluster reopen, while injected transaction refusal still leaves it
   present; and
4. the existing complete registration matrix, both maintained SCI Clojure
   versions, maintained Datahike schema focus, and reset-boundary reopen proof
   remain green.

