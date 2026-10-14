# Digest refusals and the vanishing private `def` (2026-10-14)

Research lane `defect-research`. Default JVM pid 2041 (started 2026-10-13 10:01 UTC, so it includes 86430115b), HEAD
984789a27. Every probe ran in SCI mode as a fresh agent (`dd-probe-1` … `dd-probe-8`, one branch each); no JVM-mode
write, no source or test edited. Tasks:
`seon.db/a-long-defn-after-a-def-of-fn-or-a-namespace-form-change-is-refused-for-its-digest` (defect 1) and
`seon.sci.eval/a-private-function-def-disappears-between-mcp-evaluations` (defect 2).

**What I found, in short.** Defect 2 reproduces in three requests. Its producer is the batch's private/program split:
a function that names a private value Var is admitted as a program row, but the program context interprets rows
without the agent's private Vars. Defect 1 has two shapes. The test-world shape (Prove table Surprise 1, the four
"program overlay … refused" incidents) reproduces: it is a stale digest left on the branch, and the world's overlay
transaction is the first write to check it. The branch-side shape (a first `defn` answered "exists in your SCI
context only") did not reproduce under nine shapes, listed below. The two defects meet only in one place: a branch
row that does not stand alone (stale digest, dangling private reference) fails the first time it is reinstalled
somewhere else (the world, a new base context).

## Defect 2: a program function that names a private `def`

### Reproduction (agent `dd-probe-1`, fresh branch)

```clojure
;; request 1
(ns probe.dd1)
;; request 2 (namespace probe.dd1)
(def k1 42)
(defn uses-k1 [] k1)
(uses-k1)                       ; => 42
;; request 3
(uses-k1)                       ; => 42 (the agent's own context still holds k1)
(defn other [] 1)               ; reply carries the error below
;; request 4, and every later request that forks a base context
(uses-k1)                       ; => 42, with the same error again (occurrence count 2, 3, ...)
```

The error delivered beside the value:

```clojure
{:seon.error/layer :seon.sci.eval/program
 :seon.error/operation seon.sci.eval/install-row!
 :seon.error/message "Cannot interpret probe.dd1/uses-k1: Unable to resolve symbol: k1"}
```

The same thing happens with the review agent's shape (`dd-probe-2`): `(def request {:n 3})`, `(def measure (fn []
(:n request)))`. The next request that changes the program answers "Cannot interpret probe.dd2/measure: Unable to
resolve symbol: request".

### Data flow and root cause

1. `seon.turn.batch/write-entity` splits each final root write: a Var whose value is not a function (`k1`, `request`)
   becomes `:seon.batch/private {:seon.batch/reason :not-a-function}`, kept only in the agent's SCI fork. A function
   Var, whether written as `defn` or as `def` of a `fn` (`uses-k1`, `measure`, `a2`), becomes a program row. Checked:
   `(seon.db/q '[:find ?s ?src :where [?e :seon.fn/sym ?s] …])` on the branch lists `(def a2 (fn …))` and
   `(def a3 (fn …))` as `:seon.fn/source`.
2. The row's body names `probe.dd1/k1`, a Var that has no program entity. Nothing records or refuses that edge.
3. On a later request, `seon.sci.eval/base-ctx` forks `seon.sci.eval/program-context` for the branch. That is the
   branch's program interpreted from rows by `seon.sci.eval/install-selected!` → `seon.sci.eval/install-row!`, with no
   private Vars in it. `uses-k1` cannot be analyzed, so `seon.sci.eval/interpretation-failure` builds the refusal and
   `seon.sci.eval/install-refusing-root!` binds a refusing root. `seon.sci.eval/record-acquisition-refusals!` delivers
   it again on every fork, which is the "recurring" error the Done-rule table saw with `base-cid`.
4. The agent's own fork keeps both Vars (`(uses-k1)` still answers 42, and `ns-interns` still lists `measure`). The
   review agent's report that `measure` stopped resolving is the same row failing in a context rebuilt from the
   base: there the function comes from the program, the private data does not, and the function is unavailable. I did
   not rebuild the agent's own context in this lane (no reset of default), so that last step is inferred from the
   code path, not observed.

**Root cause:** `seon.turn.batch/write-entity` admits a function row whose definition depends on a Var it has just
classified as private. The program the branch holds is therefore not closed under its own references. Every consumer
that reinstalls rows outside the agent's fork (base context, test world, merge write-back) receives a definition that
cannot load. Write-back has the same problem: the merged file would hold `(defn uses-k1 [] k1)` with no `k1`.

### Fix at the producer (two options; option 1 recommended)

1. **Refuse the row at its producer, naming the private Var (recommended).** In `seon.turn.batch/derive-form`, the
   batch knows both sets for the form: `:seon.batch/private` (its own and earlier private writes of the namespace,
   from the SCI context) and each row's analyzed usages. A row whose same-namespace usages include a private Var is not
   a program declaration. Answer it as `:seon.batch/private` with `:seon.batch/reason :names-private-state` and the
   offending symbols, and deliver the `not-admitted` refusal (`seon.turn/not-admitted`) with a fix text: "inline the
   value, or make it a function". The definition still evaluates and its value returns (total path). Only its
   program facts are withheld, at the moment the agent can act, instead of on every later request. This is the
   existing law "refuse missing children; repair surviving declaration references in the same transaction or refuse"
   applied at the producer. It is not a new check beside a mechanism.
2. **Make referenced value `def`s program declarations.** Record a `def` whose value is not a function as a program
   row when (and only when) a program row names it, so the program and the merged file are closed. This changes the
   owner's "nothing defined with def is persisted" rule and needs an owner ruling.

Also at its producer: `seon.repl/def-note` decides "lives only in your SCI context" by reading the form's text with
`clojure.edn/read-string`, not from the batch's decision. It tells the agent that `measure`, `a2`, `pv2` and `obs2`
(all stored program rows) are not persisted. It stays silent on `(def a3 (fn [xs] (map #(some-> % inc) xs)))` because
EDN cannot read `#(...)`. The note should come from the batch's `:seon.batch/private` entry for that Var. That
removes the text parse as well (the "reading Clojure text" row of the library table).

### Red proof (in `seon.turn.batch-test`, through `seon.mcp/call`, as `a-defn-keeps-its-namespace-row` does)

```clojure
(deftest ^{:seon/tasks #{'seon.sci.eval/a-private-function-def-disappears-between-mcp-evaluations}}
  a-function-naming-a-private-def-is-refused-when-defined-and-never-later
  ((requiring-resolve 'seon.test-support/with-running-agent)
   (fn [running agent-id connection]
     (let [defined (eval! "(ns batch-test.private) (def k1 42) (defn uses-k1 [] k1)" agent-id running)
           later   (eval! "(in-ns 'batch-test.private) (defn other [] 1) (uses-k1)" agent-id running)]
       ;; the program never holds a row that cannot load without the agent's private state
       (is (nil? (db/pull (db/db connection) [:seon.fn/sym] [:seon.fn/sym 'batch-test.private/uses-k1])))
       ;; the defining reply names the private Var (option 1); read by content, not position
       (is (some #(= 'batch-test.private/k1 %) (reply-offending defined)))
       ;; no later request carries an install-row! interpretation error
       (is (not-any? #(= "seon.sci.eval/install-row!" (get % "seon.error/operation")) (reply-errors later)))
       ;; the agent's own value is untouched (total path)
       (is (= 42 (reply-value later)))))))
```

`reply-errors` / `reply-offending` / `reply-value` are the shared test-support reader of reply items by content. Today
the first and third assertions are red (the row is stored, and the error recurs). A second case belongs to the
`def-note` producer, in `seon.repl-test/a-def-note-follows-the-evaluations-surface-and-outcome`: `(def f (fn [] 1))`
earns no "lives only in your SCI context" note, and `(def k 1)` does.

Tests that reach the change: `seon.turn.batch-test` (all), `seon.repl-test/a-def-note-follows-the-evaluations-surface-and-outcome`,
`seon.sci.program-change-test`, `seon.sci.lazy-acquisition-test`.

## Defect 1: "the stored definition digest … is not the one its definition produces"

Producer and checker, as found:

- Producer: `seon.turn.batch/analyzed-entities` digests each row with `seon.db/definition-digest-of` under
  `:seon.fn/namespace-row`. `seon.turn.batch/stored-namespace-row` supplies that row: SCI's live bindings when the
  form wrote its namespace, otherwise the row the working value stores.
- Checker: `seon.db/write-digest-error` recomputes `seon.db/definition-digest-of` for every row the transaction
  touched, under the namespace row the transaction leaves stored.
- Re-derivation: `seon.db/prepared-write` appends `seon.db/namespace-digests-tx` for each namespace map written with a
  digest. That re-derives the digests of the namespace's other declarations (those the transaction does not write).

Both sides digest through the one owner, so a mismatch means they read different inputs: a different namespace row,
or a stored row that no longer matches its namespace.

### Shape A, reproduced: the test world refuses a stale branch digest

Agent `dd-probe-8`:

```clojure
;; request 1
(ns probe.dd8 (:require [clojure.string :as x] [clojure.test :refer [deftest is]]))
(defn f1 [s] (x/upper-case s))
(deftest f1-test (is (= "A" (f1 "a"))))          ; green, test world builds fine
;; request 2 (namespace probe.dd8)
(ns-unalias 'probe.dd8 'x)
(alias 'x 'clojure.set)
(defn g1 [] 2)
```

Request 2's own write is admitted. Its test step answers `:seon.turn.loop/test-error` "The test world's program
overlay (14 entries) was refused: The stored definition digest of [:seon.fn/sym probe.dd8/f1] is not the one its
definition produces" (`seon.test/world-refusal` from `seon.test/clean-request-handle`). A checker run over the
branch, `(= stored (seon.db/definition-digest-of projection entity-map namespace-map))` per declaration (the `chk`
helper, built from `seon.db/declaration-value`), gives `f1 false` and every other declaration `true`. After request 3
(`(alias 'y 'clojure.walk)`), `g1`, `chk` and `f1-test` were re-derived (still `true`) but `f1` stayed `false`.

Mechanism: the branch keeps a row whose stored digest its namespace no longer produces. `seon.db/write-digest-error`
checks only rows a transaction touches, so the branch never notices. `seon.test/program-overlay-tx` copies the row
into the world (`seon.fn/reconcile-tx`), the world's transaction touches it, and the checker refuses it there. The
Prove table's four "program overlay (48..112 entries) was refused" incidents are this shape. Their recorded
`Interrupted` reds and the reuse that pinned them are the separate Surprise-1 finding about `designed-red?`.

The cause of the staleness is `seon.db/a-cross-pass-alias-retarget-leaves-dependant-digests-stale` (already filed).
New evidence for that task: a later namespace write re-derives the namespace's other declarations but not `f1`, so
`seon.db/namespace-digests-tx` either skips `f1` or derives the same stale value. Its `(not= derived stored)` test
should then add `f1`. One form, `(seon.db/definition-digest-of …)` against `namespace-digests-tx`'s
`declaration-value` on the transaction's own value, decides which. I did not chase it further (the task is filed and
owned).

### Shape B, not reproduced: a first defn refused on the agent's own write

What I tried, each on a fresh branch with the shared checker afterwards (all admitted, all digests consistent):

| # | shape | result |
|---|---|---|
| 1 | `(def a2 (fn …))`, `(def a3 (fn [xs] (map #(some-> % inc) xs)))`, `(defn a1 [xs] (map #(inc %) xs))`, docstring `"doc -> [x]"` | admitted |
| 2 | Done-rule table's `green?`, `red?`, `done-refusals` verbatim (docstring `"[branch main judged] -> [refusal ...]"`, `#(...)` bodies, three fully-qualified namespaces) | admitted |
| 3 | then the `ns` form re-evaluated with `[clojure.test :refer [deftest is]]` added, a deftest and two defns in the same request | admitted, tests green |
| 4 | Prove table's `probe.prove` ns form (14 requires) with its six helpers and `prove-verdict` as `def` of `fn` with `#(some-> …)`, first define | admitted |
| 5 | then `(require '[clojure.test :refer [deftest is]])` and two deftests calling `prove-verdict`; then `brief`, `done-rule`, `main-database` | admitted, consistent |
| 6 | then `prove-verdict` redefined from `def`-of-`fn` to `defn` | admitted, consistent |
| 7 | in a stored namespace: `(defn y1 [] 1)` then `(require '[clojure.set :refer [union]])` in one request | admitted |
| 8 | in a stored namespace: `z1`, then `z2` auto-requiring `clojure.walk` and `clojure.edn`, then `z3`, in one request | admitted, consistent |
| 9 | `(def base-cid 7)` then `(defn observe "[x] -> y" [x] (+ x base-cid))` | admitted (defect 2 follows later) |

Ruled out as causes on their own: `#(...)` literals (gensym'd params), `->` or `[...]` in docstrings, length, a `def`
of a `fn`, a `def`-to-`defn` redefinition, an added refer by `require` or by the `ns` form, auto-required namespaces
before or after other defns in the same request, and a private value `def` named by the defn.

Still open and consistent with the evidence: the Done-rule agent's namespace `probe.done.judge` had been through
`my.program/ns-unmap!` calls (one refused with two symbol args, one returning `:ok` without retracting), redefinitions
of `p1`, `r4` and `gap7`, and the `base-cid` defect-2 refusal. Any of these can leave a stored row or namespace row the
checker no longer agrees with. One plausible route: a stale namespace digest makes the next row write in that
namespace mismatch. The first form to run on a reproduction is the checker over the namespace before the refused
`defn` (`seon.db/definition-digest-of` per declaration and `seon.db/namespace-digest-of` for the namespace). A `false`
there means the refused row was correct and something earlier left the namespace stale.

### Fix and proof for shape A (owned by the filed alias task; stated here for the chain)

The fix is at the producer: a namespace write re-derives every declaration whose resolver context it changed,
`seon.db/namespace-digests-tx` over the stored row, so a branch never holds a digest its namespace does not produce.
No world-side tolerance. Red proof, in `seon.turn.batch-test`:

```clojure
(deftest ^{:seon/tasks #{'seon.db/a-cross-pass-alias-retarget-leaves-dependant-digests-stale
                         'seon.db/a-long-defn-after-a-def-of-fn-or-a-namespace-form-change-is-refused-for-its-digest}}
  a-retargeted-alias-leaves-no-stale-digest-for-the-test-world
  ((requiring-resolve 'seon.test-support/with-running-agent)
   (fn [running agent-id connection]
     (eval! "(ns batch-test.alias (:require [clojure.string :as x] [clojure.test :refer [deftest is]])) (defn f1 [s] (x/upper-case s)) (deftest f1-test (is (= \"A\" (f1 \"a\"))))" agent-id running)
     (let [later (eval! "(in-ns 'batch-test.alias) (ns-unalias 'batch-test.alias 'x) (alias 'x 'clojure.set) (defn g1 [] 2)" agent-id running)
           database (db/db connection)]
       ;; every declaration's stored digest is the one its stored namespace produces
       (is (empty? (stale-declarations database 'batch-test.alias)))
       ;; the test step builds its world: no world-refusal error in the reply
       (is (not-any? #(= "seon.test/world-refusal" (get % "seon.error/producer")) (reply-errors later)))))))
```

`stale-declarations` is the checker above as a test-support function over `seon.db/definition-digest-of`. It is
already the invariant `seon.db/declaration-value`'s docstring states. Red today on both assertions. Tests reaching:
`seon.turn.batch-test`, `seon.db-test` (writer digest checks), `seon.test` world tests
(`seon.test/a-changed-check-builds-a-world-per-request` and the program-overlay tests).

## Other findings from the probes (record as tasks; not fixed here)

- **One error signature for different failures.** `7c52d6ce256964e2…` is the signature of "Cannot interpret
  probe.dd1/uses-k1: … k1", "Cannot interpret probe.dd2/measure: … request" and "Cannot interpret probe.dd8/f1: …
  x/upper-case", on three branches. The signature ignores the refused function and its cause, so one occurrence
  count covers unrelated defects (`seon.sci.eval/interpretation-error` is the producer).
- **`ns-interns` in SCI lists clojure.core.** `(ns-interns 'probe.dd2)` returned about 120 `clojure.core` Vars
  (`ensure`, `gen-class`, `agent`, …) as interns of `probe.dd2` beside `request`, `measure` and `other`. Clojure's
  `ns-interns` holds only the namespace's own Vars. This is noise in every agent's view of its namespace.
- **Requests over one second.** Requests whose test step ran a reaching test took 1.34–1.67 s (test-step
  0.99–1.47 s, GC 0.9 s in the shared window). `probe.prove/s1` (calling `prove-verdict` over the whole database)
  allocated 1.18 GB.
- **`clojure.data` is not available in agent SCI** ("Unable to resolve symbol: clojure.data/diff").

## Cleanup

Agent branches `dd-probe-1` … `dd-probe-8` are disposable. They hold only `probe.dd*`/`probe.prove` namespaces and
were left for root to retire with `my.agent/retire-branch!`, since this lane does not act as root on default.
