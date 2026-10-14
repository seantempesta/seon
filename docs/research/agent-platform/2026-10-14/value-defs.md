# Value `def`s as program: what exists, what it would cost (2026-10-14)

Research lane `value-defs`. Default JVM as of 2026-10-14 (`runtime_status` `:faulted`, one open fault, outside this
slice). Probes: SCI mode on agents `vd-probe-1` and `vd-probe-2` (own branches, retired at the end); read-only JVM
queries against default's database. No source or test edited. Follows defect 2 of
[digest-and-def-defects](digest-and-def-defects.md). An existing task already names the target:
`seon.sci.eval/a-repl-def-is-a-program-entity` (filed 6a421d96e).

The owner's question: is storing value `def`s "a major addition", since it would mean indexing them and linking
them for replay? **On the facts below, it is not.** The indexing, the linking and the replay already exist and run
on every value `def` in Seon's own files (960 of them on default). The REPL batch is the one producer that throws
the row away. It does so with one test, `(fn? @v)` in `seon.turn.batch/write-entity`. The work is removing that
exclusion and fixing two consumers that never saw a REPL value row before.

## 1. How a `def` is stored today

**A file's `def` is a program row.** `seon.fn/function-definition?` says so in its docstring: "a value def
(`def`/`defonce` …), literal or computed" is a program declaration. Its row is a `:seon.fn/fn` entity, with
`:seon.fn/arglists "()"`, `:seon.fn/source` (the authored text), `:seon.program/definition-digest`, `:seon.fn/calls`
for the initializer's edges, and `:seon.fn/constant? true` when the initializer is literal data
(`seon.fn.analyzer/literal-value?`: self-evaluating values, quoted data, and vectors, sets and maps of those, so no
symbols). On default:

| query (JVM, default db) | count |
|---|---|
| `:seon.fn/fn` rows | 6,779 |
| value rows (`:seon.fn/arglists "()"`) | 960 (516 in src namespaces, the rest in tests) |
| of those, `:seon.fn/constant? true` | 501 |
| `defonce` | 80 |
| `^:private` | 751 |
| `:seon.fn/host-bound? true` | 22 |
| with `:seon.fn/spec` | 0 |
| value rows over 4,096 bytes of source | 1 (max 4,520; 178 function sources are over 4 KB already) |

**A REPL `def` builds the same row, and the batch drops it.** The batch's own analysis produces a complete row for a
value `def`. `seon.turn.batch/analyzed-entities` over `seon.fn/runtime-analysis-batch`, called in JVM mode on four
sources, gives:

```clojure
"(def k1 42)"                   => {:seon.fn/sym probe.x/k1 :seon.fn/constant? true :seon.fn/arglists "()" :seon.fn/calls #{clojure.core/def} ...}
"(def table {:a 1 :b [1 2 3]})" => {:seon.fn/constant? true ...}
"(def state (atom {}))"         => {:seon.fn/calls #{clojure.core/def clojure.core/atom} ...}   ; no constant?
"(def k2 (inc 1))"              => {:seon.fn/calls #{clojure.core/inc clojure.core/def} ...}
;; keys: arglists call-arities calls constant? defined-by host-bound? ns private? source sym
;;       :seon.program/analyzed-source-digest :seon.program/definition-digest :seon.schema.admission/source
```

Then the first branch of `write-entity`'s `cond`, `(not (or (fn? @v) (:test (meta v))))`, answers
`:seon.batch/private {:seon.batch/reason :not-a-function}` and discards the row. A `def` of a `fn` passes that test
and is stored (`probe.vd1/as-fn`, source `(def as-fn (fn [] table))`).

**So `(def k1 42)` fits the existing entity with no new attribute.** Nothing would differ. The source is the
authored text, never a printed value, so the value printer is not involved. The digest is the same
`seon.db/definition-digest-of` that every row gets.

**The rule has a cost already.** `vd-probe-2` ran `(in-ns 'seon.render.hiccup-test) (def void-tags #{"br"})` to
change an existing core value `def`. The branch row kept the core source (`:seon.schema.admission/source :core`),
`(my.program/diff)` answered `{:added [] :changed [] :removed []}`, and the note said the def "lives only in your SCI
context". The Var render still offered `(my.test/check {:seon.test/changed [seon.render.hiccup-test/void-tags]})`,
but that check runs against the stored core row. **An agent cannot change any of Seon's 960 value `def`s through the
REPL path**, and nothing tells it so.

## 2. The reference is already recorded, and replay already handles value rows

On `vd-probe-1` (`(def k1 42) (def table {...}) (def state (atom {})) (defn uses-k1 [] k1) (defn uses-all [] [k1
(:a table) @state])`), the branch rows hold:

```clojure
probe.vd1/uses-k1  :seon.fn/calls [clojure.core/defn probe.vd1/k1]
probe.vd1/uses-all :seon.fn/calls [clojure.core/defn clojure.core/deref probe.vd1/k1 probe.vd1/state probe.vd1/table]
```

**The edge exists. It dangles**, because the target has no entity. Side finding: `clojure.core/defn` itself is
recorded as a call edge of every `defn` row.

Replay already treats value rows as a kind:

- `seon.sci.eval/bind-lazy-members!` binds functions to lazy roots and leaves "a value definition (its
  `:seon.fn/arglists` the empty list)" for eager interpretation.
- `seon.sci.eval/interpret-source-namespaces!` acquires "values and tests in binding order".
- `seon.sci.eval/install-selected!` declares every new Var of the namespace first, then installs rows "in file order,
  as loading the file evaluates them: a def reading another def finds it bound. Rows with no span (defined at a REPL)
  follow, by name."

**The one new piece replay needs is order for REPL value rows.** REPL rows carry no `:seon.fn/form-span` (checked:
`uses-k1` has none). Name order is wrong for `(def z 1) (def a (inc z))`: `a` installs first and reads `z` while it is
still unbound. Functions do not care about order, because they are declared first and their bodies resolve when
called. Literal value rows do not care either, because a literal cannot name a Var. Only computed value rows need
the order. The edges for it are already stored in `:seon.fn/calls`, so sorting a namespace's no-span rows by those
edges is about ten lines in `install-selected!`. Write-back has the same need: `seon.cluster.source/write-back!`
appends added declarations to the namespace file, and a computed `def` must be written before the rows that read it.

## 3. Write-back today, with a private `k1`

`seon.cluster.source/write-back!` writes the declarations of the accepted identities. It proves the result only by
reindexing the file with the analyzer, and it never loads the file. `k1` has no identity, so the file receives
`(defn uses-k1 [] k1)` and no `k1`. Reindexing does not complain about that. **The written file then fails on
`require` with "Unable to resolve symbol: k1".** This is inferred from the code path; I did not write into the
checkout. The same file breakage is why the branch's base context refuses the row (defect 2: `install-row!`,
"Cannot interpret probe.vd1/uses-k1: Unable to resolve symbol: k1", occurrence count 4 on `vd-probe-1`).

## 4. What storing value `def`s would touch

- **Live atoms.** The row stores source, not the value. That is how the file index already stores
  `(defonce loaded-identity (atom {}))` and 14 other referenced atom `def`s. What does not travel is the atom's current
  contents: a fresh base context or test world re-evaluates `(atom {})`, which is exactly what loading the file does.
  The agent's own fork keeps its Var ("private defs, atoms and objects survive program changes"). The rule allowing
  at most one validated state map per process is a merge-time lint over code (ledger §3.19), and it applies
  unchanged. No exception is needed for atoms.
- **Host objects** (`(def mx (ManagementFactory/getThreadMXBean))`). Same story: the row is the source, and
  `:seon.fn/host-bound?` is already derived per declaration (22 value rows on default carry it). A host-bound
  branch row already refuses by name when it is called.
- **Large literals.** `:seon.fn/source` is stored inline today, and 178 function sources already exceed 4 KB. Only
  1 of the 960 value sources does. Nothing new here.
- **`defonce`.** It keeps SCI's existing Var where one is bound and evaluates its initializer in a fresh context. 80
  rows already exist. Nothing new.
- **`^:private`.** `:seon.fn/private?` is already on every row (751 private value rows).
- **Contracts: this one needs a change.** `seon.cluster.source/contract-gaps` exempts only macros and host-bound
  rows. It reports `:missing-spec` for value rows today: `seon.render.value/text-limits` (constant, caller count 6),
  `seon.render.hiccup-test/void-tags` and `seon.schema/!fallback-counts`. So the first branch value row would be
  refused at merge as "uncontracted" and would grow the REPL warning. `seon.contracts/candidate-query` already
  excludes constants and `arglists "()"`, saying "value defs (no arglist) are not functions to contract". The fix is
  one clause in `contract-gaps` with the same rule, which also makes the two places agree. CLAUDE.md's contract rule
  covers "every admitted function", and a value `def` is not one.
- **Effects in a computed initializer.** This is the one real sacrifice. A computed value row is evaluated eagerly
  wherever its namespace is interpreted: the branch's base context, test worlds and, after merge, file load. A
  scratch `(def r (my.task/refine! ...))` or `(def resp (my.web/fetch url))` would become program on the branch.
  Its effect would repeat whenever a new context acquires that namespace, and it would show up in the merge request's
  diff. A literal cannot do this.
- **The `def-note` producer.** "Nothing defined with def is persisted" becomes false. It must come from the batch's
  decision (task `seon.repl/a-fn-valued-def-is-told-nothing-is-persisted`), not from `seon.repl/def-note` parsing the
  source as EDN.
- **Rebinding.** The task `seon.turn.batch/a-value-def-over-a-function-keeps-the-stale-function-row` becomes moot
  under (b) and (d) when the value is a row: the value's row replaces the function's.

## 5. How common the case is in Seon's own code

Same-namespace references (`:seon.fn/calls` or `:seon.fn/references`) from a function row to a value row, on
default:

| | count |
|---|---|
| value rows | 960 |
| referenced by a function in the same namespace | **644 (67%)**: 429 in src, 215 in tests |
| of those, literal (`:seon.fn/constant?`) | 376 |
| of those, computed | 268 |
| computed whose initializer calls `delay`/`atom`/`volatile!`/`promise`/`ref`/`agent` | 105 (89 of them call `delay`) |
| computed whose initializer calls outside clojure.core/string/set | 125 (e.g. `(def ^:private caps (config/result-caps config/defaults))`) |
| top-level `(def… (atom` / `(volatile!` in src (rg) | 3 (`seon.sci.eval/loaded-identity`, `seon.sci.kernel/memory-pressure-state`, `seon.schema/!fallback-counts`); 13 in test |

The most common heads of computed initializers are `delay` (89), `requiring-resolve` (56), `str` (27),
`seon.config/result-caps` (20), `fn` (18), `into` (18) and `seon.config/defaults` (18). A function reading a value
`def` in its own namespace is ordinary Clojure, and it is the majority shape in the code agents will edit.

## 6. Options

| | exact change | size | guarantee | sacrifice / left to agents |
|---|---|---|---|---|
| **(a)** referenced value defs only | admit a value `def` as a row when some row names it. An earlier request's private `def` keeps no source (`:seon.batch/private` holds sym, join and reason), so a later `defn` naming it cannot admit it. That forces storing every private def's source anyway, plus reclassifying it when its last reference goes. | larger than (b) | program closed under references | the entity family depends on who references it. Unreferenced edits to existing value defs (the `void-tags` case) are still lost. Not recommended. |
| **(b)** every top-level def is a row, as in files; no live-object exception | drop the `(fn? @v)` branch in `seon.turn.batch/write-entity`. Make one clause in `seon.cluster.source/contract-gaps` exempt `arglists "()"`. Order REPL value rows by `:seon.fn/calls` within the namespace in `seon.sci.eval/install-selected!`, and the same order for the rows `write-back!` appends. Take `def-note` from the batch decision. | about −4 / +15 lines | REPL and file are the same program. Every edit to Seon's 960 value defs reaches diff, merge and write-back. No dangling edge. | a computed initializer with effects runs again in every new context and test world, and scratch defs show up in the diff. The live object exception is unnecessary, because rows are source, not values. |
| **(c)** refuse a function naming a private def | in `seon.turn.batch/derive-form`, when a row's same-namespace `:seon.fn/calls` include a Var this batch or an earlier one classed `:not-a-function`, answer it as `:seon.batch/private :names-private-state` with the offending symbols and the `seon.turn/not-admitted` refusal (defect note, option 1). | about +15 lines | the program never holds a row that cannot load | forbids the 67% shape. Edits to existing value defs are still silently lost. Agents must rewrite `(def k 42)` as `(defn k [] 42)`. |
| **(d)** literal defs are rows; computed value defs stay private, and a function naming one is refused | in `write-entity`, the first branch becomes `(not (or (fn? @v) (:test (meta v)) (:seon.fn/constant? row)))`. Add the `contract-gaps` clause for value rows. Apply (c)'s refusal only to computed private defs. No ordering change, because a literal names no Var. | about +2 lines, plus (c)'s 15 | literals (376 of the 644 referenced) behave as in files, with no effect and no ordering hazard | computed value defs (268 referenced in Seon, 125 calling first-party code) are still not editable at the REPL. Agents must turn them into functions. |

**Recommendation: (b).** It is the simplest option consistent with the rules. It deletes the exclusion rather than
adding a classifier or a refusal, and it makes the REPL producer agree with the file producer, which already owns
this rule (`seon.fn/function-definition?`). It also stops agents being restricted: an agent can change any def Seon
has, as in Clojure. (c) and (d) both add a refusal, and the refusal fences off a shape that Seon's own code uses 644
times.

The one hazard is an effectful computed initializer re-running in new contexts. Under (b) that is Clojure's own
semantics: loading or reloading a file does the same. It is visible as data (the row's `:seon.fn/calls` name the
effect), and it reaches main only through root's diff review. Agents keep the edge cases they would have in any
Clojure codebase:

- wrap effects in a function or a `delay`, as 89 of Seon's own defs already do;
- keep scratch work in a scratch namespace they retire;
- use `defonce` for state that should survive re-evaluation.

If the owner wants scratch effects kept off the program entirely, (d) is the fallback. It costs about two lines, but
it leaves computed defs, Seon's included, outside the REPL path.

Proofs for (b):

- the defect note's red test, `a-function-naming-a-private-def-is-refused-when-defined-and-never-later`, inverted:
  `k1` has a row, and no later `install-row!` error occurs;
- a `void-tags`-shaped case, where redefining an existing value def appears in `(my.program/diff)`;
- `(def z 1) (def a (inc z))` installs in a fresh base context;
- `contract-gaps` is empty for `(def k1 42)`.

Reaching tests: `seon.turn.batch-test`, `seon.repl-test/a-def-note-follows-the-evaluations-surface-and-outcome`,
`seon.sci.program-change-test`, `seon.sci.lazy-acquisition-test`, and the `seon.cluster.source` merge-verdict tests.
