(ns seon.eval.promise-ergonomics-test
  "Promise ergonomics for agent eval (cljs-async-await design §D). A form
   whose value is a Promise the auto-await can't deliver as data in time —
   an auto-await TIMEOUT or an explicit `(seon.eval/defer …)` — must:

   - record a clean `:seon.eval/pending` PLACEHOLDER as the form value (never
     the raw Promise — the value renderer must not `seq` a Promise);
   - store the live Promise HANDLE at `result/<id>` (NOT the placeholder);
   - resolve to real DATA when `result/<id>` is re-referenced in a later eval
     (the existing auto-await on the eval-batch path).

   `defer` must NOT block — it hands the pipeline the handle synchronously.
   The CLJS-async gotcha this guards: a Promise sitting in an `if`/`or`/`cond`
   TEST position is AUTO-AWAITED by the compiler, which would resolve (and
   block on) the handle. So the pending branch tests `(some? handle)` — never
   a bare `handle` — and binds via `(if pending? handle …)`, keeping the
   Promise in branch/arg positions only.

   Run via `bin/test-cljs`, or:
     (require 'seon.eval.promise-ergonomics-test :reload)
     (cljs.test/run-tests 'seon.eval.promise-ergonomics-test)"
  (:require
    [cljs.test :refer [deftest is async]]
    [clojure.string :as str]
    [seon.agent.home :as home]
    [seon.client :as client]
    [seon.db :as db]
    [seon.db.id :as db.id]
    [seon.eval :as seval]
    [seon.repl :as repl]
    [seon.repl.internal :as repl-int]))

(defn- with-conn
  "Open a fresh :memory conn, `set!` it as the ROOT `db/*conn*` (a plain
   `binding` does NOT survive await boundaries in CLJS), run `body` (0-arg,
   returns a Promise), restore the prior root after."
  [body]
  (-> (client/open-agent-conn!)
      (.then (fn [conn]
               (let [prev db/*conn*]
                 (set! db/*conn* conn)
                 (-> (js/Promise.resolve (body))
                     (.finally (fn [] (set! db/*conn* prev)))))))))

(defn- run-batch
  "Run `source` (one form) through eval-batch! in `aid`'s home ns against the
   current root conn. Returns a Promise of the eval-batch! result map. The
   live-result store (`result/<id>`) is process-global, so a later run-batch
   re-references a handle a prior one bound."
  [_aid _turn-id source]
  (-> (repl/ensure-bootstrap!)
      (.then (fn [cs]
               (-> (db.id/allocate!
                     {::db.id/allocations
                      [{::db.id/key ::fixture-agent
                        ::db.id/identity-attr :seon.agent/id}
                       {::db.id/key ::fixture-turn
                        ::db.id/identity-attr :seon.agent.turn/id}]
                      ::db.id/transaction-builder
                      (fn [ids]
                        {:seon.db/tx-data
                         [{:seon.agent/id (::fixture-agent ids)}
                          {:seon.agent.turn/id (::fixture-turn ids)}]})
                      :seon.db/conn db/*conn*})
                   (.then
                     (fn [env]
                       (let [aid (get-in env [::db.id/ids ::fixture-agent])
                             hns (home/home-ns aid)]
                         (-> (seval/setup-agent-ns! cs hns aid)
                             (.then
                               (fn [_]
                                 (seval/eval-batch!
                                   cs (repl-int/parse-forms source) hns aid
                                   (get-in env [::db.id/ids ::fixture-turn])
                                   nil))))))))))))

(defn- row
  "The :seon.eval entity for `id` (read against the current root conn)."
  [id]
  (db/entity {:seon.db/ref [:seon.eval/id id]}))

;; ---------------------------------------------------------------------------
;; defer — unit. A Promise becomes a `Deferred` handle; a non-Promise passes
;; through untouched (nothing to defer).
;; ---------------------------------------------------------------------------

(deftest defer-wraps-promise-passes-non-promise
  (is (instance? seval/Deferred (seval/defer (js/Promise.resolve 1)))
      "a Promise becomes a Deferred handle")
  (is (= 42 (seval/defer 42))
      "a non-Promise is returned unchanged — nothing to defer")
  (is (= [:plain :data] (seval/defer [:plain :data]))))

;; ---------------------------------------------------------------------------
;; defer e2e — the form does NOT block; it records the placeholder and stores
;; the live Promise; re-referencing result/<id> auto-awaits it to data.
;; ---------------------------------------------------------------------------

(deftest defer-records-placeholder-stores-handle-and-re-reference-resolves
  (async done
    (let [!id (atom nil)]
      (-> (with-conn
            (fn []
              (-> (run-batch "pe-defer-t" "turnprom0001"
                    "(seon.eval/defer (js/Promise. (fn [resolve _] (js/setTimeout (fn [] (resolve {:deferred/data 42})) 150))))")
                  (.then (fn [r]
                           (is (= 1 (:seon.eval/n-ok r))
                               "the deferred form records as OK (a placeholder value, not an error)")
                           (let [id     (first (:seon.eval/ids r))
                                 handle (seval/lookup-result id)
                                 rrow   (row id)]
                             (reset! !id id)
                             (is (instance? js/Promise handle)
                                 "result/<id> holds the still-running Promise handle")
                             (is (true? (:seon.eval/ok? rrow)))
                             (is (str/includes? (:seon.eval/result-edn rrow) ":seon.eval/pending")
                                 "the recorded value is the :seon.eval/pending placeholder")
                             (is (str/includes? (:seon.eval/result-edn rrow) (str "result/" id))
                                 "the placeholder names result/<id> so the agent knows how to await it")
                             handle)))         ; return the handle → next .then waits for it to resolve
                  (.then (fn [_]
                           (run-batch "pe-defer-ref-t" "turnprom0002"
                                      (str "(identity result/" @!id ")"))))
                  (.then (fn [r2]
                           (is (= 1 (:seon.eval/n-ok r2)))
                           (is (str/includes? (:seon.eval/result-edn (row (first (:seon.eval/ids r2))))
                                              ":deferred/data")
                               "re-reference records the RESOLVED data, not a Promise"))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

;; ---------------------------------------------------------------------------
;; timeout e2e — a Promise that exceeds the per-form auto-await budget records
;; a placeholder + stores the handle (same downstream as defer); re-reference
;; resolves it. The one-shot `(budget …)` sets the bound INSIDE the form.
;; ---------------------------------------------------------------------------

(deftest timed-out-promise-stores-handle-and-re-reference-resolves
  (async done
    (let [!id (atom nil)]
      (-> (with-conn
            (fn []
              (-> (run-batch "pe-timeout-t" "turnprom0003"
                    "(seon.eval/budget 50 (js/Promise. (fn [resolve _] (js/setTimeout (fn [] (resolve {:timeout/data 7})) 500))))")
                  (.then (fn [r]
                           (is (= 1 (:seon.eval/n-ok r))
                               "the timed-out form records as OK (placeholder, not error)")
                           (let [id     (first (:seon.eval/ids r))
                                 handle (seval/lookup-result id)]
                             (reset! !id id)
                             (is (instance? js/Promise handle)
                                 "the auto-await TIMEOUT stores the live Promise handle, not the resolved value")
                             (is (str/includes? (:seon.eval/result-edn (row id)) ":seon.eval/pending")
                                 "timeout records the placeholder")
                             handle)))         ; return the handle → next .then waits for it to resolve
                  (.then (fn [_]
                           (run-batch "pe-timeout-ref-t" "turnprom0004"
                                      (str "(identity result/" @!id ")"))))
                  (.then (fn [r2]
                           (is (str/includes? (:seon.eval/result-edn (row (first (:seon.eval/ids r2))))
                                              ":timeout/data")
                               "re-reference resolves the timed-out handle to real data"))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))
