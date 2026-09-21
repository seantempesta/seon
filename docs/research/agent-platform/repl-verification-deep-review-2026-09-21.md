---
type: research
status: measured findings integrated; composed candidate proof remains
created: 2026-09-21
tags: [agent-platform, repl, isolation, cancellation, review]
---

# REPL verification of the deep review

Read both Fable research notes end to end and the affected specification changes at
`60db9f18a`. Probes used the live default JVM; no production definitions were
changed. Temporary namespaces were removed, the cancellation thread was released
and joined, and the candidate proof branch was released and retired.

## Measured outcomes

| Probe | Result | Consequence |
|---|---|---|
| FutureTask cancellation, 16 ms | isDone true; body thread alive; exit signal false; get immediately throws cancellation | B2 must wait on actual body completion, not cancelled Future state |
| Contract-only SCI wrapper, 42 ms | direct invalid argument refused; compiled caller returns invalid argument; interpreting only caller restores refusal | Reuse the callee body, but preserve enforcement through affected callers; no blanket contract-only exemption |
| Candidate primitives, 151 ms envelope | branch 74.65 ms; open 25.62 ms; SCI fork 0.021 ms; exact commit retained; arithmetic returns 42 | Promising cheap foundation, not proof of a fully armed candidate, scoped effects/faults, recovery or isolation |
| Uninstalled query attribute, 26 ms | Datahike returns empty relation; Seon returns invalid-read naming attribute | A2 must retain that semantic check; deletion estimate remains conditional |

The earlier readiness checkpoint now passed: 2 executed, 16 assertions, no
failures/errors (`landing/fresh-start-core-readiness-retry.log`). This does not
make the 186-test combined checkpoint green and does not justify repairing every
legacy failure before the refactor.

## Exact contract probe

The cancellation form (all cleanup bounded):

```clojure
(let [started (java.util.concurrent.CountDownLatch. 1) release (java.util.concurrent.CountDownLatch. 1) exited (java.util.concurrent.CountDownLatch. 1) task (java.util.concurrent.FutureTask. ^java.util.concurrent.Callable (fn [] (.countDown started) (try (loop [] (when-not (try (.await release 20 java.util.concurrent.TimeUnit/MILLISECONDS) (catch InterruptedException _ false)) (recur))) :finished (finally (.countDown exited))))) thread (Thread. task "seon-bounded-cancel-probe")] (.setDaemon thread true) (try (.start thread) (when-not (.await started 1 java.util.concurrent.TimeUnit/SECONDS) (throw (ex-info "probe failed to start" {}))) (.cancel task true) {:seon.probe/future-done (.isDone task) :seon.probe/thread-alive (.isAlive thread) :seon.probe/exit-observed (zero? (.getCount exited)) :seon.probe/get-result (try (.get task 20 java.util.concurrent.TimeUnit/MILLISECONDS) (catch java.util.concurrent.CancellationException _ :cancelled))} (finally (.countDown release) (when-not (.await exited 1 java.util.concurrent.TimeUnit/SECONDS) (throw (ex-info "probe failed to exit" {}))) (.join thread 1000))))
```

Unknown-attribute comparison:

```clojure
(let [database (seon.db/db (seon.operator/connection "default"))
      query '[:find ?e :where [?e :seon.probe/nonexistent-attribute ?v]]]
  {:dependency (datahike.api/q query database)
   :seon (select-keys (seon.db/q query database)
                      [:seon.error/message :seon.error/at :seon.db/invalid-read])})
```

```clojure
(let [name (symbol (str "seon.probe.contract-" (seon.id/id))) space (create-ns name)] (try (binding [*ns* space] (clojure.core/refer 'clojure.core) (eval '(defn callee [x] x)) (eval '(defn caller [x] (callee x))) (let [callee (ns-resolve space 'callee) caller (ns-resolve space 'caller) sci-ns (sci.core/create-ns name) ctx (sci.core/init {:namespaces {name {'callee (sci.core/copy-var* callee sci-ns) 'caller (sci.core/copy-var* caller sci-ns)}}}) wrapped (malli.core/-instrument {:schema [:=> [:cat :int] :int]} @callee) call #(try (sci.core/eval-form ctx (list (symbol (str name) %) "invalid")) (catch Exception _ :refused))] (sci.core/bind-root! ctx (sci.core/resolve ctx (symbol (str name) "callee")) wrapped) (let [direct (call "callee") before (call "caller")] (sci.core/eval-string* ctx (str "(in-ns '" name ") (defn caller [x] (callee x))")) {:seon.probe/direct direct :seon.probe/compiled-caller before :seon.probe/interpreted-caller (call "caller")}))) (finally (remove-ns name))))
```

Uses real SCI plus Malli `-instrument` (`core.cljc:3118`), with the default
throwing report. It isolates call routing, not the complete Seon fixture or flat
error protocol. The direct call positively verifies that the wrapper is active.

## Exact candidate primitive probe

```clojure
(let [instance (get @seon.operator.runtime/running-instances "default") store (:seon.store/store instance) shared (seon.db/db (seon.operator/connection "default")) basis (seon.db/commit-id shared) branch (keyword (str "probe-candidate-" (seon.id/id))) started (System/nanoTime)] (try (let [created (seon.cluster.registry/branch! {:seon.store/store store :seon.store/branch branch :seon.cluster.registry/from basis}) branched (System/nanoTime) connection (seon.cluster.store/open-branch! store branch)] (try (let [opened (System/nanoTime) ctx (sci.core/fork (get-in instance [:seon.turn.loop/cluster :seon.sci.eval/ctx])) forked (System/nanoTime)] {:seon.probe/created? (:seon.cluster/created? created) :seon.probe/same-commit? (= basis (seon.db/commit-id @connection)) :seon.probe/branch-ms (/ (- branched started) 1e6) :seon.probe/open-ms (/ (- opened branched) 1e6) :seon.probe/sci-fork-ms (/ (- forked opened) 1e6) :seon.probe/sci-result (sci.core/eval-string* ctx "(+ 20 22)")}) (finally (seon.cluster.store/release-branch! connection)))) (finally (seon.cluster.registry/retire-branch! {:seon.store/store store :seon.store/branch branch}))))
```

The forked context evaluates arithmetic only. It was not rebound into a complete
candidate environment; no candidate agent was armed and no provider was called.

## Source-grounded limits

core.async `futurize` creates a Java FutureTask
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:29`).
Cancellation is not body termination. Use the existing execution owner's
completion from body exit, include cancellation-before-entry, and refuse context
reuse until the appropriate terminal fact. Never treat disarming a graph as
proof that arbitrary host work or its external effects stopped.

Datahike `fork-database` already holds source/target reachability permits
(`reference-code/datahike/src/datahike/versioning.cljc:608-614`), releasing them
in finally. Preserve these if narrowing copied keys. The barrier alone is not
a lifetime proof. GC's walker tolerates absent records and prunes history by a
cutoff; an export cannot silently inherit those omissions. No dependency patch
was made or timed, and the claimed fifteen-line change is not verified.

The single schema projection transport and removal of unused shape facts remain
good directions, conditional on complete consumer conversion. Branch-plus-handle
is the recommended candidate direction, conditional on the scoped full-agent
proof. Removing the lifecycle lock needs start/reset/delete races, not merely
two simultaneous starts: a store lock cannot protect files after it is released.
No source-line target or complete candidate startup estimate was established by
this review.
