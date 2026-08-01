(ns seon.sci.eval-test
  "Acceptance for the guarded eval (N3, C7).

  DRAFT FOR ORCHESTRATOR SEAL REVIEW (drafted 2026-07-27). Every test
  runs a REAL sci evaluation with a REAL armed boundary — there is no
  fake interrupt-fn here, because the one thing worth proving is that
  the mechanism stops what it claims to stop.

  The deadlines are short (a few hundred ms) and the runaway cases are
  genuinely unbounded, so a regression does not slow the suite: it
  fails it."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datahike.api :as d]
            [seon.config :as config]
            [seon.cluster.agent :as agent]
            [seon.cluster.work :as work]
            [seon.instrument :as instrument]
            [sci.core :as sci]
            [seon.render :as render]
            [seon.schema]
            [seon.sci.eval :as eval]
            [seon.test-support :as test-support]))

(def ^:private caps
  (config/result-caps (config/defaults)))

(defn- run-in
  [ctx source time-limit-ms]
  (eval/evaluate
   (cond-> {:seon.cluster.run.form/source source
            :seon.sci.admit/caps caps
            :seon.sci.eval/time-limit-ms time-limit-ms
            ;; development disposition: a codec hole must be loud
            ;; here of all places
            :seon.config/on-core-error :panic}
     ctx (assoc :seon.sci.eval/ctx ctx))))

(defn- run
  ([source] (run source 2000))
  ([source time-limit-ms]
   (run-in nil source time-limit-ms)))

(defn- deadlined-in
  "Evaluate on another thread so a runaway FAILS the suite rather than
  hanging it — the guard being tested is exactly the one that should
  make this unnecessary."
  [ctx source time-limit-ms]
  (let [task (future (run-in ctx source time-limit-ms))]
    (or (deref task 10000 nil)
        (do (future-cancel task) ::hung))))

(defn- deadlined
  [source time-limit-ms]
  (deadlined-in nil source time-limit-ms))

;;; PRESENCE IS THE STATE (owner ruling 2026-07-28): there is no
;;; status enum on an evaluation. These three disjoint readers ARE the
;;; state model this suite asserts.

(defn- cut?
  "The time limit fired: the evaluation carries its cut instant."
  [evaluation]
  (some? (:seon.cluster.eval/interrupted-at evaluation)))

(defn- failed?
  "The form failed on its own: an error with no cut instant."
  [evaluation]
  (and (some? (:seon.cluster.eval/error evaluation))
       (not (cut? evaluation))))

(defn- ok?
  "The form produced a value: no error and no cut instant."
  [evaluation]
  (and (nil? (:seon.cluster.eval/error evaluation))
       (not (cut? evaluation))))

;;; ---------------------------------------------------------------------------
;;; The ordinary path
;;; ---------------------------------------------------------------------------

(deftest the-request-is-what-the-contract-says-it-is
  ;; the dial is REQUIRED, so a caller cannot forget to decide
  (is (seon.schema/valid-candidate-value?
       :seon.sci.eval/request
       {:seon.cluster.run.form/source "(+ 1 1)"
        :seon.sci.admit/caps caps
        :seon.sci.eval/time-limit-ms 1000
        :seon.config/on-core-error :panic}))
  (is (not (seon.schema/valid-candidate-value?
            :seon.sci.eval/request
            {:seon.cluster.run.form/source "(+ 1 1)"
             :seon.sci.admit/caps caps
             :seon.sci.eval/time-limit-ms 1000}))
      "no dial, no evaluation"))

(deftest the-diagnostics-are-recorded-and-are-not-limits
  (let [evaluation (run "(reduce + (map inc (range 500)))")
        record (:seon.sci.admit/record evaluation)]
    (is (ok? evaluation))
    (is (= 125250 (:seon.sci.admit/value evaluation)))
    (testing "fn-entries counted the interpreted work"
      (is (pos? (:seon.eval/fn-entries record))))
    (is (= :ok (:seon.eval/outcome record)))
    (is (int? (:seon.eval/duration-ms record)))
    (is (int? (:seon.eval/allocated-bytes record))
        "-1 is honest when the platform cannot measure; nil is not")))

(deftest a-fork-per-evaluation-means-no-leakage
  (run "(def leaked 1)")
  (let [evaluation (run "leaked")]
    (is (failed? evaluation)
        "one evaluation's def cannot reach the next")
    (is (= :seon.sci.eval/evaluation-failed
           (:seon.error/kind (:seon.sci.admit/value evaluation))))))

(deftest require-context-rows-persist-namespace-lookup-refs
  (let [ctx (eval/fork)
        evaluation (run-in ctx "(require 'clojure.set)" 2000)]
    (is (ok? evaluation))
    (is (= #{[:seon.ns/name 'clojure.set]}
           (get-in evaluation
                   [:seon.sci.eval/program-row :seon.ns/requires]))
        "SCI symbols become canonical lookup refs only at persistence")))

(deftest the-dispositions-are-callable-and-come-back-as-values
  (let [evaluation (run "(my.run/complete \"done\")")]
    (is (ok? evaluation))
    (is (= {:my.run/disposition :completed :my.run/result "done"}
           (:seon.sci.admit/value evaluation))
        "the loop reads its disposition out of exactly this"))
  (let [evaluation (run "(my.run/wait \"later\")")]
    (is (= :wait (:my.run/disposition (:seon.sci.admit/value evaluation))))))

(deftest an-unbound-var-remains-structured-after-production-admission
  (let [evaluation (run "(do (declare zz) zz)")
        admitted (:seon.sci.admit/value evaluation)]
    (is (= {:seon.sci.admit/opaque "sci.impl.vars.SciUnbound"} admitted)
        "the real door preserves a value-level marker; no error string is parsed")
    (is (work/unbound-value? admitted))
    (is (nil? (:seon.cluster.eval/error evaluation))
        "sci produced a value; E2-PRIME, not the evaluator, classifies it red")))

(deftest an-instrumented-multi-arity-miss-reads-like-clojure
  (test-support/with-database
    (fn [connection]
      (let [ctx (eval/fork)
            _ (eval/acquire! {:seon.sci.eval/ctx ctx
                              :seon.db/db @connection})]
        (try
          (instrument/apply! {:seon.config/on-core-error :panic
                              :seon.sci.admit/caps caps})
          (let [evaluation (run-in ctx "(my.message/send)" 2000)
                failure (:seon.sci.admit/value evaluation)]
            (is (= "Wrong number of args (0) passed to: my.message/send"
                   (:seon.error/message failure)
                   (:seon.cluster.eval/error evaluation)))
            (is (= :seon.instrument/contract-violated
                   (:seon.error/kind failure)))
            (is (= 0 (get-in failure
                             [:seon.error/data :seon.instrument/arity])))
            (is (= '[[to content] [to content about]]
                   (get-in failure
                           [:seon.error/data :seon.instrument/arglists])))
            (is (not (str/includes? (:seon.cluster.eval/result-edn evaluation)
                                    ":malli.core/invalid-schema"))))
          (finally
            (instrument/remove!)))))))

(deftest bare-dir-and-program-derived-doc-are-repl-native
  (test-support/with-database
    (fn [connection]
      (let [db @connection
            ctx (eval/fork)
            _ (eval/acquire! {:seon.sci.eval/ctx ctx :seon.db/db db})
            directory (run-in ctx "(dir my.message)" 2000)
            documentation (run-in ctx "(doc my.message/send)" 2000)
            row (d/pull db '[:seon.fn/sym :seon.fn/doc
                             :seon.fn/arglists]
                        [:seon.fn/sym "my.message/send"])
            expected-output
            (str "-------------------------\n"
                 (:seon.fn/sym row) "\n"
                 (:seon.fn/arglists row) "\n"
                 (str/join "\n"
                           (map #(str "; " %)
                                (str/split-lines (:seon.fn/doc row))))
                 "\n")
            read-forms
            (with-open [reader (java.io.PushbackReader.
                                (java.io.StringReader.
                                 (:seon.cluster.eval/output documentation)))]
              (loop [forms []]
                (let [form (edn/read {:eof ::eof} reader)]
                  (if (= ::eof form)
                    forms
                    (recur (conj forms form))))))]
        (is (= "decline\nsend\n"
               (:seon.cluster.eval/output directory)))
        (is (nil? (:seon.sci.admit/value directory)))
        (is (= expected-output (:seon.cluster.eval/output documentation))
            "doc prints the acquired program-row facts, not SCI Var metadata")
        (is (nil? (:seon.sci.admit/value documentation)))
        (is (= [(symbol "-------------------------")
                'my.message/send
                '([to content] [to content about])]
               read-forms)
            "the complete printed bytes read as ordinary Clojure")))))

;;; ---------------------------------------------------------------------------
;;; The armed boundary — time is the only limit
;;; ---------------------------------------------------------------------------

(deftest an-interpreted-infinite-loop-dies-at-the-limit
  (let [started (System/nanoTime)
        evaluation (deadlined "(loop [] (recur))" 300)
        elapsed-ms (/ (- (System/nanoTime) started) 1e6)]
    (is (not= ::hung evaluation) "the limit is the limit")
    (is (cut? evaluation))
    (is (inst? (:seon.cluster.eval/interrupted-at evaluation))
        "the cut instant is the one fact — presence is the state")
    (is (= :time (:seon.eval/outcome (:seon.sci.admit/record evaluation))))
    (is (< elapsed-ms 5000)
        (str "died in " elapsed-ms "ms — the deadline, not luck"))
    (testing "and the agent is told what happened, as a value"
      (is (= :seon.sci.eval/time-limit
             (:seon.error/kind (:seon.sci.admit/value evaluation))))
      (is (re-find #"(?i)time"
                   (:seon.cluster.eval/error evaluation))))))

(deftest an-agent-cannot-catch-the-interrupt
  ;; sci's try refuses to hand the interrupt to a user catch clause, and
  ;; sandboxed code cannot forge the marker
  (let [evaluation (deadlined
                    "(try (loop [] (recur)) (catch Throwable _ :swallowed))"
                    300)]
    (is (not= ::hung evaluation))
    (is (cut? evaluation))
    (is (not= :swallowed (:seon.sci.admit/value evaluation)))))

(deftest a-previously-defined-function-uses-the-current-evaluation-limit
  (let [ctx (eval/fork)
        definition
        (run-in ctx
                "(defn spin [] (loop [i 0] (recur (inc i))))"
                1000)
        evaluation (deadlined-in ctx "(spin)" 300)]
    (is (ok? definition))
    (is (not= ::hung evaluation))
    (is (cut? evaluation))
    (is (= :time
           (:seon.eval/outcome (:seon.sci.admit/record evaluation))))
    (is (= :seon.sci.eval/time-limit
           (:seon.error/kind (:seon.sci.admit/value evaluation)))
        "the wrapped sci interrupt remains a flat time-limit value")))

(deftest a-base-created-function-uses-the-invoking-threads-arm
  ;; The interpreted corpus will be installed into `base`, so its functions
  ;; capture the base's interrupt-fn when SCI creates them. Create this one on
  ;; the test thread, then invoke it through a fork on another thread: arming
  ;; must follow the invoking thread, not the thread that created the function.
  (let [base (eval/base)
        definition
        (sci/eval-string*
         base
         (str "(defn substrate-base-spin [] "
              "(loop [i 0] (recur (inc i))))"))
        ctx (eval/fork)
        evaluation (deadlined-in ctx "(substrate-base-spin)" 300)]
    (is (ifn? definition))
    (is (identical? (:interrupt-fn base) (:interrupt-fn ctx))
        "the base and every fork share the one process guard")
    (is (not= ::hung evaluation))
    (is (cut? evaluation)
        "the caller thread's arm cuts a function created on another thread")
    (is (= :time
           (:seon.eval/outcome (:seon.sci.admit/record evaluation))))))

(deftest an-acquired-function-uses-the-current-evaluation-limit
  (test-support/with-database
    (fn [connection]
      (d/transact
       connection
       [{:seon.ns/name 'authored.interrupt
         :seon.ns/source "(ns authored.interrupt)"}])
      (d/transact
       connection
       [{:seon.fn/sym "authored.interrupt/spin"
         :seon.fn/ns [:seon.ns/name 'authored.interrupt]
         :seon.fn/source
         (str "(defn ^{:malli/schema [:=> [:cat] :int]} spin [] "
              "(loop [i 0] (recur (inc i))))")
         :seon.fn/arglists "([])"
         :seon.fn/private? false
         :seon.fn/spec "[:=> [:cat] :int]"}])
      (let [ctx (eval/fork)
            acquired (eval/acquire! {:seon.sci.eval/ctx ctx
                                     :seon.db/db @connection})
            evaluation
            (deadlined-in ctx "(authored.interrupt/spin)" 300)]
        (is (= 2 (:seon.sci.eval/installed acquired)))
        (is (not= ::hung evaluation))
        (is (cut? evaluation))
        (is (= :time
               (:seon.eval/outcome
                (:seon.sci.admit/record evaluation))))))))

(deftest acquisition-binds-loaded-first-party-compiled-vars
  (test-support/with-database
    (fn [connection]
      (let [ctx (eval/fork)
            _ (eval/acquire! {:seon.sci.eval/ctx ctx
                              :seon.db/db @connection})
            evaluation
            (run-in ctx "(seon.sci.eval/agent-namespace \"probe\")" 2000)
            external (run-in ctx "(datahike.api/q '[:find ?e :where [?e]])"
                             2000)]
        (is (identical? #'eval/agent-namespace
                        (get-in (sci/namespace-state ctx)
                                ['seon.sci.eval 'agent-namespace]))
            "the host binding is the live compiled Var, never a copied root")
        (is (ok? evaluation))
        (is (= 'my.agents.probe (:seon.sci.admit/value evaluation)))
        (is (failed? external)
            "loaded dependencies are not first-party merely because loaded")))))

(deftest public-walk-is-callable-through-an-agent-sci-eval
  (test-support/with-database
    (fn [connection]
      (test-support/seed-cluster! connection "host-walk")
      (d/transact connection
                  (agent/creation-tx
                   {:seon.cluster.agent/id "host-walker"
                    :seon.cluster/name "host-walk"
                    :seon.ns/name 'my.agents.host-walker}))
      (let [ctx (eval/fork)
            _ (eval/acquire! {:seon.sci.eval/ctx ctx
                              :seon.db/db @connection})
            evaluation
            (render/call-with-walk-context
             {:seon.store/branch-connection connection
              :seon.cluster.agent/id "host-walker"
              :seon.sci.admit/caps caps}
             #(run-in ctx "(seon.render/walk)" 5000))
            value (:seon.sci.admit/value evaluation)]
        (is (ok? evaluation))
        (is (string? value))
        (is (re-find #"root=\[:seon\.cluster\.agent/id \"host-walker\"\]"
                     value))
        (is (true? (:seon.sci.admit/capped? evaluation))
            "the ordinary top-level result cap still applies to a host call")))))

(deftest one-context-arms-concurrent-threads-independently
  (let [ctx (eval/fork)
        definition
        (run-in
         ctx
         (str "(defn finite-spin [] "
              "(loop [i 0] (if (< i 100000000) (recur (inc i)) i)))")
         1000)
        start (java.util.concurrent.CountDownLatch. 1)
        submit
        (fn [time-limit-ms]
          (future
            (.await start)
            (run-in ctx "(finite-spin)" time-limit-ms)))
        cut-task (submit 200)
        complete-task (submit 10000)]
    (is (ok? definition))
    (.countDown start)
    (let [cut-evaluation (deref cut-task 15000 ::hung)
          complete-evaluation (deref complete-task 15000 ::hung)]
      (is (not= ::hung cut-evaluation))
      (is (not= ::hung complete-evaluation))
      (is (cut? cut-evaluation))
      (is (ok? complete-evaluation)
          "arming and interrupting one thread never cuts its sibling")
      (is (= 100000000
             (:seon.sci.admit/value complete-evaluation))))))

(deftest disarm-clears-the-current-threads-flag-exactly
  (let [ctx (eval/fork)
        {stop! :seon.sci.eval/stop!} (#'eval/arm ctx 30)
        interrupt-fn (:interrupt-fn ctx)
        backstop (+ (System/nanoTime) 1000000000)
        reached
        (loop []
          (if (> (System/nanoTime) backstop)
            ::hung
            (let [interrupted
                  (try
                    (interrupt-fn)
                    false
                    (catch Throwable failure
                      (if (eval/interrupted? failure)
                        true
                        (throw failure))))]
              (if interrupted
                true
                (do
                  (Thread/onSpinWait)
                  (recur))))))]
    (is (= true reached)
        "the scheduled task published the observable interrupt event")
    (stop!)
    (is (nil? (interrupt-fn))
        "the stable hook has no stale armed state after stop!")
    (let [later (run-in ctx "(+ 1 2)" 1000)]
      (is (ok? later))
      (is (= 3 (:seon.sci.admit/value later))))))

(def ^:private ordinary-source-value-generator
  (gen/one-of
   [gen/small-integer
    gen/boolean
    gen/string-alphanumeric
    gen/keyword
    (gen/return nil)
    (gen/vector gen/small-integer 0 8)]))

(def ^:private failing-source-generator
  (gen/elements
   ["(throw (ex-info \"x\" {:probe true}))"
    "(/ 1 0)"
    "(no-such-fn 1)"
    "(recur)"
    "#{"
    "(let [x])"
    "#foo/bar [1]"
    "#=(System/exit 1)"
    "(java.io.File. \"/etc/passwd\")"]))

(deftest generated-sources-compose-fork-guard-and-admission
  (let [check
        (tc/quick-check
         100
         (prop/for-all
          [ordinary ordinary-source-value-generator
           failing-source failing-source-generator]
          (let [ordinary-evaluation (deadlined (pr-str ordinary) 300)
                failed-evaluation (deadlined failing-source 300)
                evaluations [ordinary-evaluation failed-evaluation]]
            (and
             (ok? ordinary-evaluation)
             (= ordinary (:seon.sci.admit/value ordinary-evaluation))
             (failed? failed-evaluation)
             (every?
              (fn [evaluation]
                (and
                 (not= ::hung evaluation)
                 (map? evaluation)
                 ;; Presence is the state: exactly one of these facts
                 ;; describes every completed guarded composition.
                 (= 1 (count (filter true?
                                     [(ok? evaluation)
                                      (failed? evaluation)
                                      (cut? evaluation)])))
                 (string? (:seon.cluster.eval/result-edn evaluation))
                 (do (edn/read-string
                      (:seon.cluster.eval/result-edn evaluation))
                     true)
                 (seon.schema/valid-candidate-value?
                  :seon.sci.eval/evaluation evaluation)))
              evaluations))))
         :seed 202607280802)]
    (test-support/assert-check! check
                                "Guarded evaluation composition failed.")))

;;; ---------------------------------------------------------------------------
;;; The honest ceiling — stated, not papered over
;;; ---------------------------------------------------------------------------

(deftest a-blocking-host-call-is-NOT-stopped-by-the-time-limit
  ;; Found by the totality property above, and it is not a defect: the
  ;; interrupt-fn fires on interpreted fn body entrances, and a thread
  ;; parked inside a HOST call never enters one. sci says so itself
  ;; (reference-code/sci/doc/interrupt.md, closing note: for hard
  ;; guarantees run untrusted code in a separate process).
  ;;
  ;; This test exists so the ceiling is a KNOWN, RECURRING fact rather
  ;; than a docstring claim: what covers this case is the caller's
  ;; submission backstop (whose firing IS a bug report, n3-plan §4.4)
  ;; and the process boundary — never this deadline.
  (let [task (future (run "(deref (promise))" 200))
        outcome (deref task 1500 ::still-running)]
    (is (= ::still-running outcome)
        "the time limit did NOT stop it — if this ever passes by
         returning, the mechanism changed and the ceiling moved")
    (future-cancel task)))

;;; ---------------------------------------------------------------------------
;;; The single owner of the interrupt question
;;; ---------------------------------------------------------------------------

(deftest interrupted?-recognises-only-the-real-marker
  (is (false? (eval/interrupted? (ex-info "ordinary" {}))))
  (is (false? (eval/interrupted? (RuntimeException. "ordinary"))))
  (is (false?
       (eval/interrupted? (ex-info "forged" {:sci.impl/interrupt false})))
      "sci's private marker identity, not key presence, owns the answer")
  (let [interrupt
        (try ((requiring-resolve 'sci.interrupt/interrupt!) "x")
             (catch Throwable failure failure))]
    (is (true? (eval/interrupted? interrupt)))
    (is (true? (eval/interrupted?
                (ex-info "location wrapper" {:sci/error true} interrupt))))
    (is (false? (eval/interrupted?
                 (ex-info "ordinary wrapper" {}
                          (RuntimeException. "ordinary")))))))
