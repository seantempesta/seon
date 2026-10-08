---
type: research
status: census
created: 2026-10-09
tags: [sci, forks]
---

# Host reads and writes of SCI Vars outside their context (lane ctx-audit)

Since SCI-1 (SCI fork `cff2cefb`, pinned at `7cb34638`) a Var keeps its identity across `sci.core/fork`, and every
read or write of a Var's root or metadata selects the binding of the context in `sci.ctx-store/*ctx*`
(`sci.impl.vars/active-binding`). With no context bound, a read answers the Var's own field binding, which only the
context that created the Var (its `:sci/generation`) writes. Measured consequence (below): an agent's Var is born in
the eval request that first defines it, so host code reading it with no context bound sees its **first** definition
forever, not the base's and not the current one.

## Method

`census.bb` (this directory; `bb census.bb src`) walks every `src/` file with rewrite-clj and prints one row per
site of `deref`/`@`, `meta`, `alter-meta!`, `reset-meta!`, `var-get`, `alter-var-root`, `test-var`, `.getRawRoot`,
`.hasRoot`, `.bindRoot`, `sci/bind-root!`, `sci/intern`, `sci/var->symbol` (in head position, threaded, or passed
as a value), with its enclosing top-level definition and whether it sits lexically inside a `with-ctx`. The rows
whose operand can be an SCI Var were then classified by reading the operand's producer and the site's dynamic
extent (who calls it, on which thread, under which binding). `sci.core/intern` and `sci.core/bind-root!` bind their
`ctx` argument themselves (`sci.core/intern`, `sci.impl.utils/bind-root!`), so their rows are correct by
construction. `sci.core/resolve` reads no root.

## Counts (HEAD 4c7919e64)

| | Rows |
|---|---|
| Rows printed | 773 (21 are a local named `test-var`, not an op) |
| Operand is not an SCI Var (atoms, delays, promises, volatiles, host `clojure.lang.Var`s, forms, fns, databases) | 690 |
| Operand can be an SCI Var | 62, in 30 definitions (table below) |
| — lexically inside the right `with-ctx` | 32 |
| — dynamically inside the evaluating context (observer, show stage, a form's own host call, `seon.test.runner/run-vars!`) | 16 |
| — reads only identity metadata (`:name`, `:ns`, `:macro`), equal in every binding | 7 |
| — outside any context, correct today only through an interim (wrong once SCI-3 lands) | 5 |
| — outside any context, scheduled for deletion (SCI-3) | 2 |
| Wrong, fixed before this census | 2 (`seon.turn.batch/derive-form` 4c7919e64, `seon.turn/entry-bindings` 7e39aeed3) |
| Wrong, open (one class, not one site) | host threads invoking SCI fns: `seon.sci.eval/a-host-thread-reads-a-redefined-var-as-its-first-definition` |

## SCI-Var sites

| Site (by enclosing definition) | Op | Context | Verdict |
|---|---|---|---|
| `seon.sci.eval/turn-interns` | meta, deref | lexical `with-ctx ctx` | correct |
| `seon.sci.eval/install-function-contract!` | deref | lexical `with-ctx ctx` | correct |
| `seon.sci.eval/transfer-evaluated-roots!` | deref, meta, reset-meta! | lexical `with-ctx source` / `ctx` | correct |
| `seon.sci.eval/install-selected!` | deref | lexical `with-ctx ctx` | correct |
| `seon.sci.eval/base-bindings` | deref, meta | lexical `with-ctx ctx` | correct |
| `seon.sci.eval/regenerate-agent-context!` | deref, meta, reset-meta! | lexical `with-ctx ctx` / `regenerated` | correct |
| `seon.sci.eval/fork-cluster-ctx` | deref, meta | lexical `with-ctx ctx` | correct |
| `seon.sci.arm/arm!` | meta, deref | lexical `with-ctx ctx` | correct |
| `seon.turn.batch/derive-form` → `write-entity` | meta, deref | lexical `with-ctx ctx` (H3) | correct since 4c7919e64 |
| `seon.turn/entry-bindings` | deref, meta | lexical `with-ctx ctx` | correct since 7e39aeed3 |
| `seon.sci.observe/observe` (expansion and write observers) | meta | inside the evaluation's `with-ctx` | correct |
| `seon.sci.arm/write-event` | meta | called by the write observer | correct |
| `seon.sci.lookup/documentation-value`, `seon.sci.lookup/stored-source-fn` | meta | expanded from `doc`/`source` in the agent's form | correct |
| `seon.effect/context-handler` | deref | `seon.effect/request*` on the requesting thread | correct (the handler's later invocation: the open class below) |
| `seon.render/call-cache-evidence`, `seon.render/retained-program-current?` | deref | the show stage runs inside `seon.batch/evaluate-step`'s observed run | correct on the eval path; a web page re-rendering a retained call outside an evaluation is unverified |
| `seon.test.runner/run-vars!`, `run-selected-tests`, `duration-failures` | meta, `(@hook)` | `with-bindings {*ctx* ctx}` of the custody | correct |
| `seon.test.runner/var-symbol`, `seon.sci.arm/var-symbol`, `seon.render.var/var-symbol`, `seon.effect/owner-symbol` | meta | none | correct: `:name`/`:ns` only |
| `seon.render/head-function` | meta | none | correct: `:macro` only (a redefinition changing a fn into a macro is the residue) |
| `seon.test/resolve-test` | `(:test (meta v))` | none | interim: see below |
| `seon.test/apply-seeds!` | `(:seon.test/seed (meta v))` | none | interim: see below |
| `seon.test/bounded-result` | `(:seon.test/long-ms (meta v))`, the SCI time limit | none | interim: see below |
| `seon.sci.eval/inherited?` | deref | none | deleted by SCI-3; under SCI-1 the `identical?` arm answers first |

The three `seon.test` rows read the test Var with no context, though they hold the member's context (`ctx` of
`seon.test/member-result`, the one `resolve-test` resolves in). Today the test world is built by
`seon.sci.eval/base-ctx` from the candidate's definitions (the interim in `seon.test/clean-request-handle`), so the
redefined test Var is interpreted on that world's fork and the reads answer the branch's metadata: measured green in
`seon.sci.context-reads-test/a-branch-redefinition-of-a-loaded-test-runs-under-its-own-bound` (a loaded deftest
declaring 100 ms, redefined on a branch to 4000 ms around a 400 ms interpreted spin) and live (agent
ctx-audit-probe redefining `seon.sci.eval-test/absent-program-function-is-reported-before-namespace-lookup` with
200 ms around a 1000 ms spin: red, as declared). SCI-3 makes the world fork the agent's live base again; the agent's
redefinition then lives in a context binding and these three reads would answer the first definition. SCI-3's lane
binds the member context there (`sci.ctx-store/with-ctx ctx`) and keeps that test green.

## The open class: a thread with no context

Live, agent ctx-audit-probe on default (dcfbee6c1), three eval requests in `ctxaudit.lm`:
`(def holder 1) (defn- priv [] :first)`, then `(def holder 2) (defn- priv [] :second)`, then `(def holder 3)` and

```clojure
(let [p (promise) t (Thread. (fn [] (deliver p [holder (priv)])))] (.start t) [holder (priv) (deref p 1000 :timeout)])
;; => [3 :second [1 :first]]
[@(future [holder (priv)]) (first (pmap (fn [_] [holder (priv)]) [1]))
 (.get (.submit (java.util.concurrent.ForkJoinPool/commonPool) ^Callable (fn [] [holder (priv)])))]
;; => [[3 :second] [3 :second] [1 :first]]
```

`future` and `pmap` convey bindings, `*ctx*` included; a raw `Thread`, an executor or any host thread does not, and
reads the first definition. This is not one Seon site: it is every SCI fn invoked off the evaluating thread
(agent-made threads, a background capability handler `seon.effect/request*` dispatches, Flow procs, executors).
Wrapping each Seon entrance is incomplete (agent-made threads stay wrong) and wrapping with the wrong context is a
bug of its own, so it is filed for root with options: task
`seon.sci.eval/a-host-thread-reads-a-redefined-var-as-its-first-definition`, red proof
`seon.sci.context-reads-test/a-host-thread-reads-the-current-definition`.

## Not SCI Vars (spot checks)

`seon.sci.eval/copy-host-var` (`.bindRoot` on a freshly copied Var: its creator, in place),
`seon.sci.eval/same-program-root?` (fn metadata), `seon.sci.eval/namespace-source` (form metadata),
`seon.instrument` (host `clojure.lang.Var`s), `seon.error.refusal/var-digest` (host Vars), `seon.ref/library-name`
and `seon.problems/indexed-function-var?` (host `find-var`), `seon.call-preparation/supply-from-row`
(`requiring-resolve`, host), `seon.sci.lookup/namespace-documentation` (namespace metadata), the
`::kernel/program-snapshot` and `::kernel/installed-functions` derefs (atoms), `sci/print-length`, `sci/ns` and the
print-budget Var (dynamic built-ins read inside the evaluation).
