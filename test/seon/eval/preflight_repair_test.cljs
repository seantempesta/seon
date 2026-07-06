(ns seon.eval.preflight-repair-test
  "Integration tests for the pre-flight form-autofix gate (owner rulings
   2026-07-05) at the `eval-batch!` seam: a provable near-miss is FIXED
   and evaluated with a visible `↻ fixed:` note (+ queryable
   `:seon.repair/*` datoms); ambiguity REFUSES with did-you-mean; the
   parinfer→symbol chain composes.

   Same harness as `seon.eval.repair-batch-test`: a fresh full-schema
   `:memory` conn set! as the root, the REAL bootstrap compile-state,
   `parse-forms` → `eval-batch!`, rows read back.

   Run interactively (single ns, NEVER overlapping in the live pod):
     (require 'seon.eval.preflight-repair-test :reload)
     (cljs.test/run-tests 'seon.eval.preflight-repair-test)"
  (:require
    [cljs.test :as t :refer [deftest is testing async]]
    [clojure.string :as str]
    [seon.agent]                          ; :seon.eval / :seon.agent.turn registrations
    [seon.client :as client]
    [seon.db :as db]
    [seon.eval :as seval]
    [seon.repl :as repl]
    [seon.repl.internal :as internal]))

(defn- with-conn
  "Open a fresh full-schema :memory conn, `set!` it as the ROOT
   `db/*conn*`, run `body` (0-arg, may return a Promise), restore after."
  [body]
  (-> (client/open-agent-conn!)
      (.then (fn [conn]
               (let [prev db/*conn*]
                 (set! db/*conn* conn)
                 (-> (js/Promise.resolve (body))
                     (.finally (fn [] (set! db/*conn* prev)))))))))

(defn- run-batch!
  [source turn-id]
  (-> (repl/ensure-bootstrap!)
      (.then (fn [_]
               (seval/eval-batch! @repl/!compile-state
                                  (internal/parse-forms source)
                                  'my.agent.preflight-test
                                  "pf-agent-2607"
                                  turn-id
                                  nil)))))

(defn- eval-rows
  "Recorded eval rows as {:id :ok? :source :error :narration :result}."
  [db*]
  (->> (db/query '[:find (pull ?e [:seon.eval/id :seon.eval/ok?
                                   :seon.eval/source :seon.eval/error
                                   :seon.eval/narration :seon.eval/result-edn])
                   :where [?e :seon.eval/id]]
                 db*)
       (map first)
       (map (fn [m]
              {:id        (:seon.eval/id m)
               :ok?       (:seon.eval/ok? m)
               :source    (:seon.eval/source m)
               :error     (:seon.eval/error m)
               :narration (:seon.eval/narration m)
               :result    (:seon.eval/result-edn m)}))
       (sort-by :id)
       vec))

(defn- fix-datoms
  "All `[:eval-id class from to]` fix rows in `db*`."
  [db*]
  (vec (db/query '[:find ?id ?c ?f ?t
                   :where [?e :seon.repair/applied-class ?c]
                          [?e :seon.eval/id ?id]
                          [?e :seon.repair/from ?f]
                          [?e :seon.repair/to ?t]]
                 db*)))

;; ===========================================================================
;; The headline class: a typo'd core fn (`even` → `even?`) is fixed
;; BEFORE execution, evaluated, and the ↻ note + fix datoms are visible.
;; ===========================================================================

(deftest core-fn-typo-fixed-with-visible-note-and-datoms
  (async done
    (-> (with-conn
          (fn ^:async run []
            (let [res (await (run-batch! "(filter even [1 2 3 4])" (db/new-id!)))
                  db* @db/*conn*
                  row (first (eval-rows db*))]
              (testing "fixed + evaluated — no wasted turn"
                (is (= 1 (:seon.eval/n-ok res)))
                (is (= 0 (:seon.eval/n-fail res)))
                (is (true? (:ok? row))))
              (testing "the FIXED source is what recorded (and would tee)"
                (is (str/includes? (str (:source row)) "even?"))
                (is (not (str/includes? (str (:source row)) "(filter even "))))
              (testing "the value is the fixed form's result"
                (is (str/includes? (str (:result row)) "(2 4)")))
              (testing "the ↻ fixed note is visible in the narration"
                (is (str/includes? (str (:narration row)) "↻ fixed:"))
                (is (str/includes? (str (:narration row)) "even"))
                (is (str/includes? (str (:narration row)) "even?")))
              (testing "queryable fix datoms on the eval entity"
                (let [[_ cls from to] (first (fix-datoms db*))]
                  (is (= :seon.repair/undeclared-var cls))
                  (is (= "even" from))
                  (is (= "even?" to)))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ===========================================================================
;; def-vs-defn: `(def f [x] …)` (the dropped-`n` class) compiles only as
;; defn — rewritten, proven, evaluated, callable.
;; ===========================================================================

(deftest def-vs-defn-rewritten-and-defined
  (async done
    (-> (with-conn
          (fn ^:async run []
            (let [res (await (run-batch!
                               "(def pf-dvd-fn [x] (+ x 1))" (db/new-id!)))
                  db* @db/*conn*
                  row (first (eval-rows db*))]
              (testing "rewritten to defn + evaluated ok"
                (is (= 1 (:seon.eval/n-ok res)))
                (is (true? (:ok? row)))
                (is (str/starts-with? (str/trim (str (:source row)))
                                      "(defn pf-dvd-fn")))
              (testing "the fn is genuinely defined"
                (is (some? (seval/lookup-value
                             'my.agent.preflight-test/pf-dvd-fn))))
              (testing "note + class datom"
                (is (str/includes? (str (:narration row)) "↻ fixed:"))
                (is (= :seon.repair/def-vs-defn
                       (second (first (fix-datoms db*)))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ===========================================================================
;; Ambiguity ALWAYS refuses: two session fns both one edit away → error
;; (ok? false) carrying BOTH candidates; nothing was substituted.
;; ===========================================================================

(deftest ambiguous-candidates-refuse-with-did-you-mean
  (async done
    (-> (with-conn
          (fn ^:async run []
            (let [res (await (run-batch!
                               (str "(defn pf-thing-aa [x] x)\n"
                                    "(defn pf-thing-ab [x] x)\n"
                                    "(pf-thing-ax 3)")
                               (db/new-id!)))
                  rows (eval-rows @db/*conn*)
                  ;; ids are random — select the call row by SOURCE, never
                  ;; by lexical id order.
                  row  (->> rows
                            (filter #(str/includes? (str (:source %))
                                                    "pf-thing-ax"))
                            first)]
              (testing "the two defns ran; the ambiguous call FAILED"
                (is (= 2 (:seon.eval/n-ok res)))
                (is (= 1 (:seon.eval/n-fail res)))
                (is (false? (:ok? row))))
              (testing "the call was NOT silently rewritten"
                (is (str/includes? (str (:source row)) "pf-thing-ax")))
              (testing "refused as a did-you-mean naming BOTH candidates"
                (let [msg (str (:error row))]
                  ;; BEHAVIOR (not exact wording): the refusal surfaces as a
                  ;; did-you-mean that frames both defns as near-matches for
                  ;; the broken call. The exact word "ambiguous" is NOT
                  ;; asserted — it only appears when both compile-trials
                  ;; finish inside the 50ms repair budget (config/test.edn
                  ;; leaves it unset), which the cold node-test JVM can miss,
                  ;; degrading to the plain "nearest matches" note. Both notes
                  ;; name both candidates and refuse the rewrite (pinned
                  ;; above), which is the contract that matters.
                  (is (str/includes? msg "pf-thing-aa"))
                  (is (str/includes? msg "pf-thing-ab"))
                  (is (re-find #"(?i)matches for" msg)))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ===========================================================================
;; No near miss at all → the plain sharpened error, byte-compatible with
;; the pre-autofix behavior (no note, no datoms, no suggestion line).
;; ===========================================================================

(deftest no-candidate-stays-plain-error
  (async done
    (-> (with-conn
          (fn ^:async run []
            (let [_   (await (run-batch!
                               "(zz-nothing-near-this-9x7q 1)" (db/new-id!)))
                  db* @db/*conn*
                  row (first (eval-rows db*))]
              (is (false? (:ok? row)))
              (is (re-find #"(?i)not defined|undeclared" (str (:error row))))
              (is (not (str/includes? (str (:narration row)) "↻ fixed:")))
              (is (empty? (fix-datoms db*))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
