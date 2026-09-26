---
type: report
status: stopped (default JVM died of OOM mid-lane; steps 5-6 not reached)
created: 2026-09-26
scope: dogfood lane dogfood-maintainer-opus — a Seon agent maintaining first-party code at the REPL (MCP only)
---
# Dogfood report: maintaining first-party code as a Seon agent

**Method.** MCP only: `branch` create/retire, `eval_clj` mode `sci` on my branch as `mcp/<branch>`, and
JVM-mode `eval_clj` for read-only diagnosis of my own wedged run (thread list, my branch's error rows). No
source edits. I read source only after hitting a defect, to diagnose it (`src/seon/schema.clj:3617`, the
default JVM log). Default ran archive/pid 19859 (started 16:02:02Z, HEAD around e6c3487ad).

**Outcome.** Of the six steps, only the docstring-only redefinition of a function with **zero** reaching tests
worked. Every other first-party change was refused or blocked. The lane then stopped: the default JVM died of
`OutOfMemoryError` (17.7 GB heap dump, `data/heap/java_pid19859.hprof`) about a minute after my step-4 run
panicked. **RESET NEEDED** (default is down; assigned lanes do not restart it). Branch
`dogfood-maintainer-opus-2` could not be retired because retiring needs the live JVM.

## Ranked findings

| # | Severity | Finding | Form, output, time | Model I suspect is wrong |
|---|---|---|---|---|
| 1 | **blocks work** | **The default JVM died of OOM after my step-4 run.** The run's alias analysis panicked. Attribution to my run is **not proven**, because other lanes were running tests in the same JVM. | `(ns my.fs.find (:require [seon.effect :as effect]))` + `(defn glob … (effect/request! #'glob request))` + a call, in one run. MCP timed out at 60 s. The log's last entries are two `SEON CORE FAULT (panic) … clj-kondo holds no analysis of effect` from `seon.turn/analyze-submitted` (turn.clj:3483), then `java.lang.OutOfMemoryError: Java heap space`, and the JVM exited. | Same class as dogfood-report #11/#22 (an alias required in the same run is unanalyzed). Here it is a `:panic` core fault, not a hang. A panic (or whatever ran after it) must not take the shared JVM's heap. |
| 2 | **blocks work** | **A first-party redefinition cannot pass its own entry gate when a file test reaches it.** The gate interprets the file tests in SCI, where their test-support aliases do not resolve, so every change is refused. `my.test/check` runs the **same** tests green. | Docstring-only `defn my.note/add!`: `Gate: 4 tests · 0 passed · 4 failed · auto-check 25/25 · install refused`, each failing `Unable to resolve symbol: support/with-database`. Arity change to `my.fs/write!`: `Gate: 3 tests · 0 passed · 3 failed` (`test-support/with-database`). Then `(my.test/check {:seon.test/changed ['my.fs/write!]})` passed the same 3 tests, 14 assertions, in **7,165 ms**. | New evidence on [a-first-party-redefinition-gates-on-saturated-reach-interpreted-in-sci-and-is-refused](../../seon/issues/a-first-party-redefinition-gates-on-saturated-reach-interpreted-in-sci-and-is-refused.md). This is not only a saturated-reach problem. The small, unsaturated gates fail the same way, because the entry gate evaluates file tests in a second place (SCI) that lacks their requires. |
| 3 | **blocks work** | **A docstring-only edit runs tests, and the wrong ones.** The owner ruled that a docstring-only edit runs zero tests. | `my.note/add!` docstring edit gated on `seon.cluster.publication-inputs-test/file-row-selection-…`, `seon.cluster.publication-test/previous-file-artifacts-…`, `seon.supplied-documentation-test/docs-separate-runtime-keys-…` and `seon.test-reaching-test/reaching-is-the-program-graph-relation`. It did **not** select `my.note-test/add-upserts-current-content-by-identity`, which does call `add!`. | No definition-digest comparison skips unchanged behavior, and reach is a mention/graph relation, not call reach. Commit 20a87b1d1 ("tests are selected by call reach, never by a symbol mention") landed after this JVM started, so this needs re-verification. |
| 4 | **blocks work** | **Saturated reach makes whole render/issue/note families unchangeable.** | `tests-reaching` counts: `seon.issue/render-ai`, `status-text`, `render-html`, `my.issue/status`, `my.note/notes` and `my.fs/read` are all **1524**. `my.note/notes` has **no callers** (`callers` → `nil`). `seon.issue/exhaust-tx` is 264. I therefore did not attempt my chosen fix ([the issue AI render no longer teaches its requery form](../../seon/issues/the-issue-ai-render-no-longer-teaches-its-requery-form.md), a two-line change to `seon.issue/status-text`), because the load-safety rule forbids a gate of more than 50 tests. | Same as #2 of the dogfood report (saturated reach used as behavior evidence). |
| 5 | **blocks work** | **A result value that is a sorted map with symbol keys wedges the agent permanently.** | `(into (sorted-map) (for [n '[my.note …]] [n (into (sorted-map) …)]))`. The run's error row reads `ClassCastException: Symbol cannot be cast to Keyword` at `seon.schema/valid-candidate-value?`, frame `Keyword.compareTo`. The run never closed, and every later `(+ 1 1)` answered `agent-already-running` (checked at +30 s and +60 s). No worker thread was left. The branch had to be retired. | New evidence on [armed-error-validation-throws-on-non-keyword-sorted-maps](../../seon/issues/armed-error-validation-throws-on-non-keyword-sorted-maps.md). Malli map validation calls `(get sorted-map :kw)` on a symbol-keyed sorted map. At the agent REPL, that throw is outside any settlement that closes the turn (same class as report #22). |
| 6 | slows work | `breaks` for a **contract change** is written as if it were a retraction. It takes the contract only as a **string**, lists the subject as its own caller, and never states the arity mismatch. | `(my.program/breaks {… :seon.program/contract [:=> …]})` → `expected a string (:string), got a vector`. With `pr-str`, the plan issues are titled "my.fs/write! must be repaired before retracting my.fs/write!" and "seon.effect-test/… must be repaired before retracting my.fs/write!". The call sites show `:call-arity 1` beside `:declared-arities [{:min 1 :max 1}]` (the **old** arity) and do not mention the proposed arity 2. | The plan producer has one template (retraction). A contract is a native value but is stored as a string ("Preserve native values"). The self `#'write!` reference counts as a caller. Filed: [breaks-for-a-contract-change-plans-a-retraction](../../seon/issues/breaks-for-a-contract-change-plans-a-retraction.md). |
| 7 | slows work | An arity-changing redefinition's refusal says nothing about arity or callers. It shows only gate failures and `auto-check 0/25`. | See #2 (arity change of `my.fs/write!`). | The install gate does not consult `seon.program/unresolved-callers` or `breaks`. |
| 8 | confusing | A throwaway helper `defn` is refused for lacking `:malli/schema`, and the refusal's `Example:` line reads "No docstring example is available." | `(defn src [s] …)` → `seon.sci.eval/evaluate refused :malli/schema … user/src declares no :malli/schema`. The next two forms of the run then failed with `Unable to resolve symbol: src`. | The entry policy defaults to `:gate`. I found no REPL entrance that switches my own experiments to `:warn` (`apropos "warn"` not tried, because the lane stopped; see missing entrances). |
| 9 | confusing | A reader error (unbalanced delimiter) is attributed to `clojure.core/for`. | `clojure.core/for refused source at [3 179]: expected readable Clojure source … Fix: Balance the delimiters…` | The refusal's operation is taken from the last symbol read, not from the reader. |
| 10 | ugly | A one-argument type mistake in JVM mode printed **about 30 KB**. The explanation's `:schema-location` holds elision nodes whose `:seon.print/requery-form` embeds the whole path again, several levels deep. | `(seon.db/branch-connection "default" :x)` (wrong first argument). | The instrument explanation carries a printed/elided projection (`#:seon.print{…}` strings, `requery-form`s) as data, and the floor then prints and elides that again. |
| 11 | ugly | `(dir my.program)` is about 25 KB. Each supplied-schema reference is expanded inline with its full `:description`, twice per map entry (dogfood report #18, still open). | `(clojure.repl/dir my.program)` | Already filed (doc-replaces-every-schema-keyword…). |
| 12 | confusing | `my.note/notes`'s contract names `:my.plan/request` as its input. `seon.issue/status-view` is private with **no** `:malli/schema`, although the law requires a contract on every admitted function, private ones included. | `(my.program/history {:seon.program/subject 'seon.issue/status-view})` source | A request schema is borrowed from another namespace, and admission does not enforce contracts on private functions. |
| 13 | confusing | The PostToolUse hook again blamed my `eval_clj` for another lane's unreadable file (`test/seon/fn_test.clj:2980:58`). No eval of mine writes files. | hook output after `breaks` | Already noted in the dogfood report (smaller findings). |

## Steps, as assigned

1. **Pick and find.** Found through the REPL: `doc` works. `clojure.repl/source` says "Source not found" (known).
   `(my.program/history {…})` gives the exact source. `callers` and `tests-reaching` work (about 0.2–0.4 s each).
   A sweep of `tests-reaching` over about 80 publics exceeded MCP's 30 s bound. The chosen fix (issue render
   requery form) was blocked by saturated reach (#4).
2. **Redefine in place.** `(in-ns 'my.fs)` + `defn stat` (docstring only): **allowed**, 0 tests, no gate, the
   call still works. The only advisory was "No example test gates my.fs/stat…". `defn my.note/add!` (docstring
   only): **refused**, 4 unrelated tests, all red in SCI (#2, #3).
3. **Arity change.** `my.fs/write!` `[request]` → `[request options]`: **refused** by gate failures that are
   unrelated to arity (#2, #7). `breaks` with the proposed contract named the two `seon.effect-test` callers,
   but framed them as a retraction (#6). The callers are file `deftest`s whose bodies use
   `test-support/with-database`, so redefining them at the agent REPL would hit the same SCI resolution failure.
   **Callers not fixed.**
4. **Move and delete.** Moving `my.fs/glob` to a new `my.fs.find` namespace (with `(:require [seon.effect :as
   effect])`) never settled. It panicked in alias analysis, and the JVM then died of OOM (#1). **Not done.**
5. **Compatible schema change.** Not reached (default down).
6. **Reaching tests green + `propose-merge!`.** Not reached. The only installed change (the `my.fs/stat`
   docstring) is on branch `dogfood-maintainer-opus-2`, which is unretired because retiring needs the JVM.

## Missing entrances (each tried or searched, and failed)

1. **Run the entry gate the way `my.test/check` runs tests** (compiled file tests with their requires). There is
   no way to make a first-party redefinition pass a gate that reaches a file test.
2. **Switch my own entry policy to `:warn` for an experiment.** The refusal names no such dial.
3. **A prospective arity check.** `breaks` accepts a contract, but its answer is a retraction plan. Nothing says
   "these call sites pass 1 argument, the new contract needs 2".
4. **Move a function between namespaces.** No entrance exists. The manual route (new `ns` with a require, then
   `defn`, then `ns-unmap!`) hits the alias-analysis defect (#1; report #22).
5. **Recover a wedged run from the REPL** (report #21, again: #5 here needed a branch retire).
6. **Source of a first-party function at `source`** (report missing-entrance #5, still true).

## What worked well

- `my.program/history` returns exact source plus definition facts (calls, call-arities, spans) for first-party
  functions, which makes it a usable `source`.
- `callers` for `my.fs/write!` listed exact call sites with call arity. `breaks` produced gating tests per caller.
- A docstring-only redefinition of a zero-reach first-party function (`my.fs/stat`) installed in one run, and the
  new function answered correctly immediately.
- `my.note/notes` now shows `:my.note/agent [:seon.agent/id "mcp/…"]` (the raw entity id from cycle 2 is fixed).
- `my.test/check` on a named change ran exactly the 3 reaching tests, green, with per-member profiles, but took
  7.2 s (`seon.db/with-declarations` ×1,566,881, `seon.fn/gate-set-in` ×256 at 7,085 ms).

## Branches

- `dogfood-maintainer-opus-1`: wedged by #5, retired.
- `dogfood-maintainer-opus-2`: **not retired**. The default JVM died before I could retire it; root should
  retire it after the restart.
