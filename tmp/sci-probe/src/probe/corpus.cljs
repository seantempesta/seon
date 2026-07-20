(ns probe.corpus
  "B1 corpus: the eval/repl behavioral test corpus driven through the
   sci adapter (probe.adapter). Each deftest names the production test
   it ports in a comment. cljs.test over the compiled harness bundle;
   -main runs everything and prints a summary + wall time.

   Research harness only, never production code."
  (:require
   [clojure.string :as str]
   [cljs.test :as t :refer [async deftest is testing]]
   [probe.adapter :as a]
   [sci.core :as sci]))

;; One shared warm harness (matches the production pattern: one
;; compile-state per child). Tests needing isolation build their own.
(def H (a/make-ctx))

(defn- ev
  ([form-str] (ev form-str nil))
  ([form-str ns-sym] (ev form-str ns-sym nil))
  ([form-str ns-sym deadline-ms]
   (a/eval* H form-str (cond-> {}
                         ns-sym (assoc :probe/starting-ns ns-sym)
                         deadline-ms (assoc :probe/deadline-ms deadline-ms)))))

(defn- err-msg [r] (get-in r [:seon/error :seon.error/message] ""))

;;; ------------------------------------------------------- basic envelope
;; ports: seon.eval.result-var-test/ordinary-form-evaluation-returns-its-value

(deftest ordinary-form-evaluation-returns-its-value
  (let [r (ev "(+ 20 22)")]
    (is (true? (:seon.eval/ok? r)))
    (is (= 42 (:seon.eval/value r)))
    (is (= 'user (:seon.eval/ending-ns r)))))

;; ports: multi-form eval-str semantics (raw-eval evaluates all forms,
;; returns the LAST value — cljs.js eval-str contract)
(deftest multi-form-string-returns-last-value
  (let [r (ev "(def probe-a 1) (def probe-b 2) (+ probe-a probe-b)")]
    (is (true? (:seon.eval/ok? r)))
    (is (= 3 (:seon.eval/value r)))))

;; ports: the self-host cross-eval-str VALUE-def gap (clojurescript
;; skill / probe gap 4). Self-host: bare value defs don't resolve in a
;; later eval-str (agents are steered to atoms). sci: they DO.
(deftest value-def-persists-across-evals
  (ev "(def counter-probe 41)")
  (let [r (ev "counter-probe")]
    (is (true? (:seon.eval/ok? r)))
    (is (= 41 (:seon.eval/value r))
        "IMPROVEMENT vs self-host: bare value defs read back")))

(deftest defn-persists-and-redefinition-is-visible
  (ev "(defn shout-probe [s] (str s \"!\"))")
  (is (= "hi!" (:seon.eval/value (ev "(shout-probe \"hi\")"))))
  (ev "(defn shout-probe [s] (str s \"?\"))")
  (is (= "hi?" (:seon.eval/value (ev "(shout-probe \"hi\")")))
      "redefinition is immediately visible (JIT var-epoch)"))

;; ports: seon.eval.promise-ergonomics-test/
;;   promise-returning-form-preserves-its-namespace
(deftest promise-returning-form-preserves-its-namespace
  (let [r1 (ev "(ns scratch.promise-namespace)")
        r2 (ev "(js/Promise.resolve 323)" 'scratch.promise-namespace)]
    (is (true? (:seon.eval/ok? r1)))
    (is (= 'scratch.promise-namespace (:seon.eval/ending-ns r1))
        "an (ns …) form moves the ending ns")
    (is (true? (:seon.eval/ok? r2)))
    (is (= 'scratch.promise-namespace (:seon.eval/ending-ns r2))
        "a Promise-returning form does NOT corrupt the ns fold")))

;;; ---------------------------------------------------- undeclared symbols
;; ports: seon.eval.auto-refer-test/before-bare-agent-ns-cannot-resolve-db-alias

(deftest bare-agent-ns-cannot-resolve-db-alias
  (ev "(ns scratch.before-73-sci)")
  (let [r (ev "(defn db-ok? [] (some? db/query))" 'scratch.before-73-sci)]
    (is (false? (:seon.eval/ok? r))
        "without the canonical alias, db/query does not resolve")
    (is (str/includes? (err-msg r) "db/query")
        "the error names the unresolved db/query")))

(deftest undeclared-bare-symbol-is-an-error-value
  (let [r (ev "(totally-undefined-fn-probe 1 2)")]
    (is (false? (:seon.eval/ok? r)))
    (is (str/includes? (err-msg r) "totally-undefined-fn-probe"))))

;;; -------------------------------------------------------- requires / ns
;; ports: seon.eval.require-test/require-of-host-bundled-ns-succeeds-and-alias-resolves
;; (host-bundled == present in the admitted binding table here)

(deftest require-of-admitted-ns-succeeds-and-alias-resolves
  (let [r1 (ev "(ns scratch.require-b4-sci (:require [seon.ai.tokens :as tokens]))")
        r2 (ev "(tokens/chars->tokens 40)" 'scratch.require-b4-sci)]
    (is (true? (:seon.eval/ok? r1)) (err-msg r1))
    (is (true? (:seon.eval/ok? r2)) (err-msg r2))
    (is (= 10 (:seon.eval/value r2))
        "aliased var resolves to the live host fn")))

;; ports: seon.eval.require-test/bare-require-form-of-host-bundled-ns-succeeds
(deftest bare-require-form-of-admitted-ns-succeeds
  (let [r (ev "(require '[seon.ai.tokens])")]
    (is (true? (:seon.eval/ok? r)) (err-msg r))))

;; ports: seon.eval.require-test/require-of-genuinely-absent-ns-still-errors-legibly
(deftest require-of-genuinely-absent-ns-still-errors-legibly
  (let [r (ev "(ns scratch.require-absent-sci (:require [no.such.namespace :as nope]))")]
    (is (false? (:seon.eval/ok? r)) "an absent ns is still a real error")
    (is (str/includes? (err-msg r) "no.such.namespace")
        "the error names the missing namespace")))

;; ports: seon.eval.require-test/supplied-program-loads-transitive-authored-source-without-a-db
(deftest supplied-program-loads-transitive-authored-source
  (let [h (a/make-ctx
           {:probe/authored-sources
            {'my.authority.dep-sci
             "(ns my.authority.dep-sci) (defn base [] 41)"
             'my.authority.target-sci
             (str "(ns my.authority.target-sci "
                  "(:require [my.authority.dep-sci :as dep])) "
                  "(defn answer [] (inc (dep/base)))")}})
        r (a/eval* h
                   (str "(ns my.authority.caller-sci "
                        "(:require [my.authority.target-sci :as target])) "
                        "(target/answer)")
                   {})]
    (is (true? (:seon.eval/ok? r)) (err-msg r))
    (is (= 42 (:seon.eval/value r))
        "transitive authored source loads through the :load-fn seam")))

;; ports: seon.eval.require-test/absent-authored-dependency-does-not-fall-back-to-a-db
(deftest absent-authored-dependency-errors-naming-the-namespace
  (let [h (a/make-ctx
           {:probe/authored-sources
            {'my.authority.incomplete-sci
             (str "(ns my.authority.incomplete-sci "
                  "(:require [my.authority.missing-sci :as missing]))")}})
        r (a/eval* h
                   (str "(ns my.authority.consumer-sci "
                        "(:require [my.authority.incomplete-sci :as t]))")
                   {})]
    (is (false? (:seon.eval/ok? r)))
    (is (str/includes? (err-msg r) "my.authority.missing-sci")
        (err-msg r))))

;; ports: seon.eval.auto-refer-test/after-augmented-agent-ns-resolves-db-alias
;; (augment-ns-source itself is engine-independent; its canonical OUTPUT
;; must evaluate — that is the engine question)
(deftest augmented-agent-ns-output-resolves-db-alias
  (let [augmented (str "(ns my.recall.ar73-sci (:require "
                       "[seon.db :as db] "
                       "[seon.agent.message :as message] "
                       "[my.plan :as plan] "
                       "[seon.schema :as schema]))")
        r1 (ev augmented)
        r2 (ev "(defn db-ok? [] (some? db/query))" 'my.recall.ar73-sci)
        r3 (ev "(db-ok?)" 'my.recall.ar73-sci)]
    (is (true? (:seon.eval/ok? r1)) (err-msg r1))
    (is (true? (:seon.eval/ok? r2)) (err-msg r2))
    (is (true? (:seon.eval/ok? r3)) (err-msg r3))
    (is (true? (:seon.eval/value r3))
        "db/query is the live function in the authored ns")))

;;; ------------------------------------------------ async / Promise seam
;; ports: the maybe-await-value one-eval-await-persist contract +
;; probe gap 1 (^:async/await)

(deftest db-verb-promise-awaits-to-its-envelope
  (async done
    (-> (a/full-eval H "(seon.db/transact! [[:db/add 1 :a 1]])" {})
        (.then
         (fn [r]
           (is (true? (:seon.eval/ok? r)))
           (is (true? (get-in r [:seon.eval/value :seon.db/ok?]))
               "the db envelope comes back as plain data")))
        (.catch (fn [e] (is false (str e))))
        (.finally done))))

(deftest async-defn-with-await-returns-a-native-promise
  (async done
    (ev (str "(defn ^:async slow-inc-probe [x] "
             "(let [v (await (js/Promise.resolve x))] (inc v)))"))
    (-> (a/full-eval H "(slow-inc-probe 41)" {})
        (.then
         (fn [r]
           (is (true? (:seon.eval/ok? r)))
           (is (= 42 (:seon.eval/value r))
               "^:async + await works natively in sci")))
        (.catch (fn [e] (is false (str e))))
        (.finally done))))

;; DIVERGENCE PROBE (goes sci's way): in COMPILED CLJS a `try` in
;; expression position inside a ^:async fn becomes an awaited async
;; IIFE and silently unwraps Promise values (the quirk the feasibility
;; probe hit first-hand). In sci the async transform is await-driven,
;; so an awaitless try keeps its Promise VALUE.
(deftest async-try-expression-keeps-its-promise-value
  (async done
    (ev (str "(defn ^:async try-probe [] "
             "(let [v (try (js/Promise.resolve 41) "
             "(catch :default e :err))] "
             "(instance? js/Promise v)))"))
    (-> (a/full-eval H "(try-probe)" {})
        (.then
         (fn [r]
           (is (true? (:seon.eval/ok? r)) (err-msg r))
           (is (true? (:seon.eval/value r))
               (str "sci: the try expression's Promise is NOT silently "
                    "unwrapped (compiled CLJS unwraps it) — got "
                    (pr-str (:seon.eval/value r))))))
        (.catch (fn [e] (is false (str e))))
        (.finally done))))

(deftest top-level-await-fails-like-self-host
  (let [r (ev "(await (js/Promise.resolve 1))")]
    (is (false? (:seon.eval/ok? r))
        "bare top-level await is rejected — SAME restriction as self-host")
    (is (str/includes? (err-msg r) "await"))))

(deftest form-rejection-becomes-an-error-value
  (async done
    (-> (a/full-eval H "(js/Promise.reject (js/Error. \"boom-probe\"))" {})
        (.then
         (fn [r]
           (is (false? (:seon.eval/ok? r)))
           (is (str/includes? (err-msg r) "boom-probe"))))
        (.catch (fn [e] (is false (str e))))
        (.finally done))))

;;; -------------------------------------------------- defer / budget
;; ports: seon.eval.promise-ergonomics-test/defer-wraps-promises-and-passes-ordinary-values

(deftest defer-wraps-promises-and-passes-ordinary-values
  (is (instance? a/Deferred (a/defer (js/Promise.resolve 1))))
  (is (instance? a/Deferred
                 (a/defer (a/budget 25 (js/Promise.resolve 1)))))
  (is (= 42 (a/defer 42)))
  (is (= [:plain :data] (a/defer [:plain :data]))))

;; ports: seon.eval.promise-ergonomics-test/defer-wins-in-either-budget-composition-order
(deftest defer-wins-in-either-budget-composition-order
  (async done
    (let [budget-outside-p (js/Promise. (fn [_ _]))
          defer-outside-p (js/Promise. (fn [_ _]))
          values [(a/budget 25 (a/defer budget-outside-p))
                  (a/defer (a/budget 25 defer-outside-p))]]
      (-> (js/Promise.all (clj->js (mapv a/maybe-await-value values)))
          (.then
           (fn [results]
             (let [[budget-outside defer-outside] (array-seq results)]
               (is (false? (:seon.eval/ok? budget-outside)))
               (is (identical? budget-outside-p
                               (:seon.eval/pending-promise budget-outside)))
               (is (false? (:seon.eval/ok? defer-outside)))
               (is (identical? defer-outside-p
                               (:seon.eval/pending-promise defer-outside))))))
          (.catch (fn [e] (is false (str e))))
          (.finally done)))))

(defn- delayed-value [ms value]
  (js/Promise. (fn [resolve _] (js/setTimeout #(resolve value) ms))))

;; ports: seon.eval.promise-ergonomics-test/deadlines-belong-to-values-not-consumption-order
(deftest deadlines-belong-to-values-not-consumption-order
  (async done
    (let [short-p (delayed-value 80 :short-finished)
          long-b  (a/budget 250 (delayed-value 80 :long-finished))
          short-b (a/budget 10 short-p)]
      (-> (js/Promise.all
           #js [(a/maybe-await-value short-b)
                (a/maybe-await-value long-b)])
          (.then
           (fn [results]
             (let [short-r (aget results 0)
                   long-r  (aget results 1)]
               (is (false? (:seon.eval/ok? short-r)))
               (is (identical? short-p (:seon.eval/pending-promise short-r)))
               (is (= {:seon.eval/ok? true :seon.eval/value :long-finished}
                      long-r)))))
          (.catch (fn [e] (is false (str e))))
          (.finally done)))))

;;; ------------------------------------------------------- race-timeout
;; ports: seon.eval.race-timeout-test (all five contracts, against the
;; adapter's port — the production fn is engine-independent)

(deftest inner-win-returns-value-and-clears-timer
  (async done
    (let [orig-clear (.-clearTimeout js/globalThis)
          !cleared (atom 0)]
      (set! (.-clearTimeout js/globalThis)
            (fn [id] (swap! !cleared inc) (orig-clear id)))
      (-> (a/race-timeout (js/Promise.resolve :fast) 60000)
          (.then (fn [v]
                   (set! (.-clearTimeout js/globalThis) orig-clear)
                   (is (= :fast v))
                   (is (false? (a/timed-out? v)))
                   (is (pos? @!cleared))
                   (done)))
          (.catch (fn [e]
                    (set! (.-clearTimeout js/globalThis) orig-clear)
                    (is false (str e))
                    (done)))))))

(deftest timer-win-returns-the-sentinel-and-runs-cancellation
  (async done
    (let [events (atom [])
          never (js/Promise. (fn [_ _]))]
      (-> (a/race-timeout never 20 #(swap! events conj :cancelled))
          (.then (fn [v]
                   (is (true? (a/timed-out? v)))
                   (is (= [:cancelled] @events))
                   (done)))
          (.catch (fn [e] (is false (str e)) (done)))))))

(deftest inner-win-never-runs-cancellation
  (async done
    (let [!cancelled (atom 0)]
      (-> (a/race-timeout (js/Promise.resolve :fast) 60000
                          #(swap! !cancelled inc))
          (.then (fn [v]
                   (is (= :fast v))
                   (is (zero? @!cancelled))
                   (done)))
          (.catch (fn [e] (is false (str e)) (done)))))))

(deftest sentinel-is-identity-not-shape
  (async done
    (-> (a/race-timeout
         (js/Promise.resolve #js {:_seon_eval_timeout true}) 60000)
        (.then (fn [v]
                 (is (false? (a/timed-out? v)))
                 (done)))
        (.catch (fn [e] (is false (str e)) (done))))))

;;; --------------------------------------------------- result/<id> vars
;; ports: seon.eval.result-var-test/result-var-ref-recognizes-only-one-bare-result-symbol

(deftest result-var-ref-recognizes-only-one-bare-result-symbol
  (is (true? (a/result-var-ref? "result/auC-2606181147")))
  (is (true? (a/result-var-ref? "  result/foe-2606181326  ")))
  (is (false? (a/result-var-ref? "(result :auC-2606181147)")))
  (is (false? (a/result-var-ref? "(+ 1 2)")))
  (is (false? (a/result-var-ref? "my.kb/something")))
  (is (false? (a/result-var-ref? "result/a result/b"))))

;; ports: seon.eval.result-var-test/unknown-result-id-is-a-graceful-value
(deftest unknown-result-id-is-a-graceful-value
  (let [r (ev "result/zzz-9999999999")]
    (is (true? (:seon.eval/ok? r))
        "a dead result reference is a graceful MISS, not an error")
    (is (string? (:seon.eval/value r)))
    (is (str/includes? (:seon.eval/value r) "re-run"))))

(deftest live-result-var-resolves-to-its-value
  (a/bind-result-var! H "abc-2607200001" {:probe/kept 42})
  (let [r (ev "result/abc-2607200001")]
    (is (true? (:seon.eval/ok? r)))
    (is (= {:probe/kept 42} (:seon.eval/value r)))))

;;; ----------------------------------------------- in-process interruption
;; NEW capability (probe containment bonus): the self-host child
;; documents "a tight CPU loop can NOT be cancelled here".

(deftest tight-cpu-loop-is-cancelled-in-process
  (let [t0 (js/Date.now)
        r (ev "(loop [i 0] (recur (inc i)))" nil 200)
        elapsed (- (js/Date.now) t0)]
    (is (false? (:seon.eval/ok? r))
        "IMPROVEMENT: the runaway loop returns an error value in-process")
    (is (str/includes? (err-msg r) "budget exceeded"))
    (is (< elapsed 5000) (str "cancelled promptly, took " elapsed "ms"))))

(deftest sandboxed-try-cannot-swallow-the-interrupt
  (let [r (ev (str "(try (loop [i 0] (recur (inc i))) "
                   "(catch :default e :swallowed))")
              nil 200)]
    (is (false? (:seon.eval/ok? r))
        "user catch cannot swallow the interrupt marker")
    (is (not= :swallowed (:seon.eval/value r)))))

;;; ------------------------------------------------------ instrumentation
;; ports: the instrument_* eval seam (malli wrapper around the evaluated
;; fn's var; errors-as-values envelope on bad input; wrapper survives
;; because sci call sites deref vars per call)

(deftest instrumented-var-returns-errors-as-values-envelope
  (ev "(defn add2-probe [x] (+ x 2))")
  (let [ctx (:probe/ctx H)
        v (sci/eval-string* ctx "#'add2-probe")
        raw @v
        wrapped (fn [& args]
                  (if (and (= 1 (count args)) (int? (first args)))
                    (apply raw args)
                    {:seon/error
                     {:seon.error/kind :seon.error/invalid-input
                      :seon.error/message "add2-probe expects one int"}}))]
    (sci/alter-var-root v (constantly wrapped))
    (is (= 42 (:seon.eval/value (ev "(add2-probe 40)"))))
    (let [bad (:seon.eval/value (ev "(add2-probe :kw)"))]
      (is (= :seon.error/invalid-input
             (get-in bad [:seon/error :seon.error/kind]))
          "bad input returns the envelope VALUE, not a throw"))))

;;; ----------------------------------------------------------- printing
;; seam probe for seon.eval print capture (print_capture_test's ALS
;; dispatcher is host-side; the sci-side requirement is that println
;; inside evaluated code reaches a host-controlled print-fn)

(deftest println-inside-sci-reaches-the-bound-host-print-fn
  (let [!out (atom "")]
    (sci/binding [sci/print-fn (fn [s] (swap! !out str s))
                  sci/print-newline true]
      (ev "(println \"print-probe-line\")"))
    (is (str/includes? @!out "print-probe-line")
        "sci/print-fn is the capture seam (host ALS bridges async)")))

;;; ------------------------------------------------------------ perf note

(def burst-forms
  (vec (mapcat (fn [i]
                 [(str "(defn burst-f" i " [x] (+ (* x 2) " i "))")
                  (str "(burst-f" i " " i ")")])
               (range 100))))

(deftest two-hundred-form-burst-through-the-full-envelope-path
  (let [t0 (js/performance.now)]
    (doseq [s burst-forms] (ev s))
    (let [ms (- (js/performance.now) t0)]
      (is (< ms 2000) (str "200 envelope evals took " ms "ms"))
      (println (str "PERF 200-form burst through eval* envelope: "
                    (.toFixed ms 1) "ms")))))

;;; ------------------------------------------------------------- runner

(def !start (atom nil))

(defmethod t/report [:cljs.test/default :end-run-tests] [m]
  (let [ms (- (js/performance.now) @!start)]
    (println (str "CORPUS WALL " (.toFixed ms 1) "ms"))
    (println (str "SUMMARY " (pr-str (select-keys m [:test :pass :fail :error]))))
    (js/process.exit (if (t/successful? m) 0 1))))

(defn -main [& _]
  (reset! !start (js/performance.now))
  (t/run-tests 'probe.corpus))

(set! *main-cli-fn* -main)
