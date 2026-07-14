(ns seon.eval.result-var-test
  "`result/<id>` vars (transcript-redesign-2026-06-18). Each SUCCESSFUL
   eval auto-binds its value as the plain var `result/<id>` — the agent
   references `result/auC-2606181147` directly, no `(result …)` call.

   Pins the mechanisms:

   - a recent eval's value resolves as `result/<id>` with NO
     undeclared-var error (analyzer def + globalThis slot);
   - an unknown / pruned `result/<id>` is a GRACEFUL MISS value, never a
     raw `:undeclared-var` error (errors-are-values);
   - the session cap prunes the OLDEST `result/*` past `result-vars-cap`,
     keeping recent ones live;
   - a FAILED eval binds NO `result/<id>` (no value to retrieve);
   - `result/<id>` is the SOLE value-reuse surface — the home ns defs no
     `result` symbol, so nothing shadows the reserved `result` ns and a
     bare `result/<id>` resolves top-level with no require-alias.

   Run via `bin/test-cljs`, or interactively:
     (require 'seon.eval.result-var-test :reload)
     (cljs.test/run-tests 'seon.eval.result-var-test)"
  (:require
    [cljs.test :refer [deftest is testing async]]
    [seon.agent.home :as home]
    [seon.client :as client]
    [seon.db :as db]
    [seon.db.id :as db.id]
    [seon.eval :as seval]
    [seon.repl :as repl]
    [seon.repl.internal :as repl-int]))

(def ^:private cap
  "Mirror of `seon.eval/result-vars-cap` (private const) for the cap test."
  (deref #'seval/result-vars-cap))

(defn- value
  "Read `result/<id>` in `ns-sym` on `cs`; returns the eval result map."
  [cs ns-sym id]
  (seval/eval cs (str "result/" id) {:seon.eval/starting-ns ns-sym :seon.eval/analyze-deps? false}))

(defn- run-batch
  "Run `source` (one form) through eval-batch! in a fresh agent ns.
   Returns a Promise of `#js {:cs … :hns … :result <eval-batch! map>}`."
  [_aid source]
  (-> (js/Promise.all #js [(repl/ensure-bootstrap!) (client/open-agent-conn!)])
      (.then (fn [pair]
               (let [cs   (aget pair 0)
                     conn (aget pair 1)
                     prev db/*conn*]
                 ;; CLJS dynamic bindings unwind before Promise callbacks.
                 ;; Own the root for this complete async span and restore it.
                 (set! db/*conn* conn)
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
                        :seon.db/conn conn})
                     (.then
                       (fn [env]
                         (let [aid (get-in env [::db.id/ids ::fixture-agent])
                               hns (home/home-ns aid)]
                           (-> (seval/setup-agent-ns! cs hns aid)
                               (.then
                                 (fn [_]
                                   (db/with-agent
                                     aid
                                     #(seval/eval-batch!
                                        cs (repl-int/parse-forms source) hns aid
                                        (get-in env [::db.id/ids ::fixture-turn])
                                        nil))))
                               (.then
                                 (fn [r]
                                   #js {:cs cs
                                        :hns hns
                                        :aid aid
                                        :conn conn
                                        :result r}))))))
                     (.finally (fn [] (set! db/*conn* prev)))))))))

;; ---------------------------------------------------------------------------
;; result-var-ref? — the bare-symbol predicate that drives :expr context +
;; graceful-miss recognition. Pure, no compile-state needed.
;; ---------------------------------------------------------------------------

(deftest result-var-ref?-recognises-only-bare-result-symbols
  (is (true?  (seval/result-var-ref? "result/auC-2606181147")))
  (is (true?  (seval/result-var-ref? "  result/foe-2606181326  "))
      "leading/trailing whitespace is trimmed")
  (is (false? (seval/result-var-ref? "(result :auC-2606181147)"))
      "the compat FN CALL is not a var ref")
  (is (false? (seval/result-var-ref? "(+ 1 2)")))
  (is (false? (seval/result-var-ref? "my.kb/something"))
      "a different ns is not a result ref")
  (is (false? (seval/result-var-ref? "result/a result/b"))
      "two forms is not a single bare ref"))

;; ---------------------------------------------------------------------------
;; A recent eval's value resolves as result/<id> — no undeclared warning.
;; ---------------------------------------------------------------------------

(deftest recent-evals-use-the-bounded-agent-ref-window
  (async done
    (-> (run-batch "recent-evals" "(+ 1 1)\n(+ 2 2)\n(+ 3 3)\n(+ 4 4)")
        (.then
          (fn [^js o]
            (let [rows (seval/recent
                         {:seon.db/db @(.-conn o)
                          :seon.agent/id (.-aid o)
                          :seon.eval/recent-limit 2})]
              (is (= ["(+ 3 3)" "(+ 4 4)"]
                     (mapv :seon.eval/source rows))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest recent-eval-value-resolves-as-result-var
  (async done
    (-> (run-batch "rv1-260618t" "(+ 40 2)")
        (.then (fn [^js o]
                 (let [cs (.-cs o) hns (.-hns o) r (.-result o)]
                   (is (= 1 (:seon.eval/n-ok r)) "the eval succeeded")
                   (-> (value cs hns (first (:seon.eval/ids r)))
                       (.then (fn [r2]
                                (is (:seon.eval/ok? r2)
                                    "result/<id> resolves — no undeclared-var error")
                                (is (= 42 (:seon.eval/value r2))
                                    "the value var reads the eval's value")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; Unknown / pruned result/<id> → graceful miss VALUE, not a raw error.
;; ---------------------------------------------------------------------------

(deftest unknown-result-id-is-a-graceful-miss
  (async done
    (-> (repl/ensure-bootstrap!)
        (.then (fn [cs]
                 (-> (seval/eval cs "(ns probe.resultmiss)" {:seon.eval/starting-ns 'cljs.user :seon.eval/analyze-deps? true})
                     (.then (fn [_] (value cs 'probe.resultmiss "zzz-9999999999")))
                     (.then (fn [r]
                              (is (:seon.eval/ok? r) "a miss is a VALUE, not a failed error")
                              (is (string? (:seon.eval/value r))
                                  "the miss remains a readable value"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; Session cap — bind cap+N synthetic ids; oldest pruned, recent live.
;; ---------------------------------------------------------------------------

(deftest cap-prunes-oldest-keeps-recent
  (async done
    (let [prior-results (js/Reflect.get js/globalThis
                                        (str seval/result-ns-sym))
          !cleanup      (atom nil)]
      (-> (repl/ensure-bootstrap!)
          (.then (fn [cs]
                   (-> (seval/eval cs "(ns probe.resultcap)"
                                   {:seon.eval/starting-ns 'cljs.user
                                    :seon.eval/analyze-deps? false})
                       (.then (fn [_]
                                (let [n   (+ cap 5)
                                      ids (mapv #(str "cz" (mod % 26)
                                                     "-99999999" (+ 10 %))
                                                (range n))]
                                  ;; Isolate the capped runtime without deleting any
                                  ;; live values established by earlier tests.
                                  ;; The runtime object's properties ARE the
                                  ;; live keys; no process-global mirror exists.
                                  (js/Reflect.set js/globalThis
                                                  (str seval/result-ns-sym)
                                                  (js/Object.create nil))
                                  (reset! !cleanup [cs ids])
                                  (doseq [[i id] (map-indexed vector ids)]
                                    (seval/bind-result-var! cs id (* 100 i)))
                                  (is (= cap
                                         (count
                                           (js/Object.keys
                                             (js/Reflect.get
                                               js/globalThis
                                               (str seval/result-ns-sym)))))
                                      "the live-result runtime plateaus at its cap")
                                  (is (false?
                                        ((deref #'seval/replace-live-result!)
                                         (first ids) -1))
                                      "late settlement cannot resurrect an evicted value")
                                  (is (true?
                                        ((deref #'seval/replace-live-result!)
                                         (last ids) (* 100 (dec n))))
                                      "a still-live pending value can settle in place")
                                  (-> (js/Promise.all
                                        #js [(value cs 'probe.resultcap (first ids))
                                             (value cs 'probe.resultcap (nth ids 5))
                                             (value cs 'probe.resultcap (last ids))])
                                      (.then
                                        (fn [rs]
                                          (testing "oldest value and analyzer handle evict together"
                                            (is (:seon.eval/ok? (aget rs 0)))
                                            (is (string? (:seon.eval/value (aget rs 0))))
                                            (let [miss (seval/lookup-result (first ids))]
                                              (is (false? (:seon.eval/ok? miss)))
                                              (is (not (contains? miss :seon.eval/value)))))
                                          (testing "first survivor still resolves"
                                            (is (= 500 (:seon.eval/value (aget rs 1)))))
                                          (testing "newest resolves through both readers"
                                            (is (= (* 100 (dec n))
                                                   (:seon.eval/value (aget rs 2))))
                                            (is (= (* 100 (dec n))
                                                   (seval/lookup-result (last ids))))))))))))))
          (.catch (fn [e] (is false (str "threw — " e))))
          (.finally
            (fn []
              (when-let [[cs ids] @!cleanup]
                (doseq [id ids]
                  ((deref #'seval/unbind-result-var!) cs id)))
              (if prior-results
                (js/Reflect.set js/globalThis (str seval/result-ns-sym)
                                prior-results)
                (js/Reflect.deleteProperty js/globalThis
                                           (str seval/result-ns-sym)))
              (done)))))))

(deftest pending-settlement-passes-retained-value-admission
  (let [eval-id "settled-oversize-9999999999"
        cs (atom {:cljs.analyzer/namespaces {}})
        prior-results (js/Reflect.get js/globalThis
                                      (str seval/result-ns-sym))]
    (js/Reflect.set js/globalThis (str seval/result-ns-sym)
                    (js/Object.create nil))
    (seval/bind-result-var! cs eval-id :pending)
    (try
      (is (true? ((deref #'seval/replace-live-result!)
                  eval-id (apply str (repeat (* 1024 1024) "z")))))
      (is (= :seon.eval/weight-cap-exceeded
             (:seon.eval/retained-reason
               (seval/lookup-result eval-id))))
      (finally
        ((deref #'seval/unbind-result-var!) cs eval-id)
        (if prior-results
          (js/Reflect.set js/globalThis (str seval/result-ns-sym)
                          prior-results)
          (js/Reflect.deleteProperty js/globalThis
                                     (str seval/result-ns-sym)))))))

;; ---------------------------------------------------------------------------
;; Failed eval binds NO result/<id> — its id is a graceful miss.
;; ---------------------------------------------------------------------------

(deftest failed-eval-binds-no-result-var
  (async done
    (-> (run-batch "rv2-260618t" "(this-var-does-not-exist 1 2)")
        (.then (fn [^js o]
                 (let [cs (.-cs o) hns (.-hns o) r (.-result o)]
                   (is (= 1 (:seon.eval/n-fail r)) "the eval failed")
                   (-> (value cs hns (first (:seon.eval/ids r)))
                       (.then (fn [r2]
                                (is (:seon.eval/ok? r2) "the read itself is a value")
                                (is (string? (:seon.eval/value r2))
                                    "a failed eval's id is a graceful miss — no value bound")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; Sole surface — `result/<id>` is the ONLY value-reuse mechanism. The home
;; ns defs no `result` symbol, so nothing shadows the reserved `result` ns:
;; a bare `result/<id>` resolves top-level with no require-alias setup.
;; ---------------------------------------------------------------------------

(deftest result-var-resolves-with-no-result-fn-shadow
  (async done
    (-> (run-batch "rv3-260618t" "(* 6 7)")
        (.then (fn [^js o]
                 (let [cs (.-cs o) hns (.-hns o)
                       id (first (:seon.eval/ids (.-result o)))]
                   (-> (value cs hns id)
                       (.then (fn [r]
                                (testing "result/<id> VAR resolves — no shadow, no alias"
                                  (is (:seon.eval/ok? r))
                                  (is (= 42 (:seon.eval/value r))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
