(ns seon.repl-parity-test
  "Stock-Clojure behavior checks exercised through Seon's production door."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [seon.config :as config]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as test-support]))

;; Upstream corpus pins (2026-08-01):
;; clojure@b18d3adc5b5f4d5d0ccea966203fb67a614d5c3d
;; sci@937d392a008e4f2f246b9ddf9dd816ca99de9d4e
;; babashka@0fb349c414e717800be775ba9cb77c95a9eb700d
;; edamame@38e627467daa3f6f1e5a8eb6421f702d2a940b7f

(def ^:dynamic *database*
  "The immutable fixture database value used by one parity-gate run."
  nil)

(def ^:private observations (atom {}))

(defn- production-request
  [db ctx namespace-name source]
  (let [effective (config/effective db)]
    {:seon.cluster.run.form/source source
     :seon.cluster.run.form/ns [:seon.ns/name namespace-name]
     :seon.sci.admit/caps (config/result-caps effective)
     :seon.sci.eval/ctx ctx
     :seon.sci.eval/time-limit-ms
     (:seon.config.eval/time-limit-ms effective)
     :seon.config/on-core-error
     (:seon.config/on-core-error effective)}))

(defn repl-session
  "Evaluate forms through one acquired production fork and return each face."
  [forms]
  (let [db *database*
        ctx (sci.eval/fork)
        acquired (sci.eval/acquire! {:seon.sci.eval/ctx ctx
                                     :seon.db/db db})
        ctx (assoc ctx :seon.schema/projection
                   (:seon.schema/projection acquired))]
    (second
     (reduce
      (fn [[namespace-name results] source]
        (let [evaluation
              (sci.eval/evaluate
               (production-request db ctx namespace-name source))
              error (:seon.cluster.eval/error evaluation)
              result-edn (:seon.cluster.eval/result-edn evaluation)
              result
              {:out (or (:seon.cluster.eval/output evaluation) "")
               :value (:seon.sci.admit/value evaluation)
               :err error
               ;; Until the sealed print path lands, result-edn is the exact
               ;; value face the production transcript has available. Keeping
               ;; this seam explicit makes every print-path repair promote a
               ;; row instead of changing its expectation.
               :printed result-edn}]
          [(or (:seon.sci.eval/ending-ns evaluation) namespace-name)
           (conj results result)]))
      ['user []]
      forms))))

(defn- compared
  [expected actual]
  {:parity/pass? (= expected actual)
   :parity/expected expected
   :parity/actual actual})

(defn- checked
  [expected actual pass?]
  {:parity/pass? (boolean pass?)
   :parity/expected expected
   :parity/actual actual})

(defn- check-row!
  [row-id expected-state check]
  (let [outcome
        (try
          (check)
          (catch Throwable failure
            {:parity/pass? false
             :parity/expected :completed-check
             :parity/actual {:class (class failure)
                             :message (ex-message failure)}}))
        passes? (:parity/pass? outcome)]
    (swap! observations assoc row-id
           (assoc outcome :parity/expected-state expected-state))
    (case expected-state
      :passing
      (is passes?
          (str row-id " regressed: " (pr-str outcome)))

      :known-divergence
      (is (false? passes?)
          (str row-id " now matches stock Clojure; promote the row: "
               (pr-str outcome))))))

(defmacro defparity
  [row-id expected-state check]
  (let [test-name (symbol (str "parity-" (str/lower-case row-id)))
        test-name (with-meta test-name
                    {:parity/row row-id
                     :parity/known-divergence
                     (= :known-divergence expected-state)})]
    `(deftest ~test-name
       (check-row! ~row-id ~expected-state (fn [] ~check)))))

(def pending-rows
  "Checklist rows with no honest executable production-door assertion yet."
  [])

(defn- parity-vars
  []
  (->> (ns-interns (the-ns 'seon.repl-parity-test))
       vals
       (filter (comp :parity/row meta))))

(defn- database-fixture
  [run-tests]
  (test-support/with-database
    (fn [connection]
      (config/apply! {:seon.config/connection connection
                      :seon.config/manifest
                      {:seon.config/on-core-error :record}})
      (binding [*database* @connection]
        (run-tests)))))

(defn- report-fixture
  [run-tests]
  (reset! observations {})
  (run-tests)
  (let [known (->> (parity-vars)
                   (filter (comp :parity/known-divergence meta))
                   (map (comp :parity/row meta))
                   sort
                   vec)]
    (println (str "REPL parity known divergences (" (count known) "): "
                  (str/join ", " known)))
    (println (str "REPL parity pending rows (" (count pending-rows) "): "
                  (str/join ", " (map :parity/row pending-rows))))))

(use-fixtures :once report-fixture)
(use-fixtures :each database-fixture)

;;; Family B — collection faces and elision
;; Ported from Clojure's printer tables at
;; reference-code/clojure/test/clojure/test_clojure/printer.clj:17-186.

(defparity "B1" :known-divergence
  (compared ["(1 2 3)" "[1 2 3]"]
            (mapv :printed
                  (repl-session ["'(1 2 3)" "[1 2 3]"]))))

(def ^:private print-length-seq-cases
  [[0 "(...)"]
   [1 "(0 ...)"]
   [2 "(0 1 ...)"]
   [3 "(0 1 2 ...)"]
   [4 "(0 1 2 3 ...)"]
   [5 "(0 1 2 3 4)"]])

(defparity "B2" :known-divergence
  (let [actual
        (mapv (fn [[length _]]
                (:printed
                 (peek
                  (repl-session
                   [(str "(set! *print-length* " length ")")
                    "(range 5)"]))))
              print-length-seq-cases)]
    (compared (mapv second print-length-seq-cases) actual)))

(def ^:private print-length-vector-cases
  [[0 "[...]"]
   [1 "[0 ...]"]
   [2 "[0 1 ...]"]
   [3 "[0 1 2 ...]"]
   [4 "[0 1 2 3 ...]"]
   [5 "[0 1 2 3 4]"]])

(defparity "B3" :known-divergence
  (let [actual
        (mapv (fn [[length _]]
                (:printed
                 (peek
                  (repl-session
                   [(str "(set! *print-length* " length ")")
                    "[0 1 2 3 4]"]))))
              print-length-vector-cases)]
    (compared (mapv second print-length-vector-cases) actual)))

(defparity "B4" :known-divergence
  (let [list-results
        (repl-session ["(set! *print-length* 0)" "()"
                       "(set! *print-length* 1)" "()"])
        vector-results
        (repl-session ["(set! *print-length* 0)" "[]"
                       "(set! *print-length* 1)" "[]"])]
    (compared ["()" "()" "[]" "[]"]
              (mapv :printed
                    [(nth list-results 1)
                     (nth list-results 3)
                     (nth vector-results 1)
                     (nth vector-results 3)]))))

(def ^:private print-level-cases
  [[0 "#"]
   [1 "(0 #)"]
   [2 "(0 (1 #))"]
   [3 "(0 (1 (2 #)))"]
   [4 "(0 (1 (2 (3 #))))"]
   [5 "(0 (1 (2 (3 (4)))))"]])

(defparity "B5" :known-divergence
  (let [actual
        (mapv (fn [[level _]]
                (:printed
                 (peek
                  (repl-session
                   [(str "(set! *print-level* " level ")")
                    "'(0 (1 (2 (3 (4)))))"]))))
              print-level-cases)]
    (compared (mapv second print-level-cases) actual)))

(def ^:private print-level-length-cases
  [[0 1 "#"]
   [1 1 "(if ...)"]
   [1 2 "(if # ...)"]
   [1 3 "(if # # ...)"]
   [1 4 "(if # # #)"]
   [2 1 "(if ...)"]
   [2 2 "(if (member x ...) ...)"]
   [2 3 "(if (member x y) (+ # 3) ...)"]
   [3 2 "(if (member x ...) ...)"]
   [3 3 "(if (member x y) (+ (first x) 3) ...)"]
   [3 4 "(if (member x y) (+ (first x) 3) (foo (a b c d ...)))"]
   [3 5 "(if (member x y) (+ (first x) 3) (foo (a b c d Baz)))"]])

(defparity "B6" :known-divergence
  (let [actual
        (mapv (fn [[level length _]]
                (:printed
                 (peek
                  (repl-session
                   [(str "(set! *print-level* " level ")")
                    (str "(set! *print-length* " length ")")
                    "'(if (member x y) (+ (first x) 3) (foo (a b c d \"Baz\")))"]))))
              print-level-length-cases)]
    (compared (mapv #(nth % 2) print-level-length-cases) actual)))

(def ^:private namespace-map-cases
  [["{}" "{}"]
   ["{:a 1, :b 2}" "{:a 1, :b 2}"]
   ["{:user/a 1}" "#:user{:a 1}"]
   ["{:user/a 1, :user/b 2}" "#:user{:a 1, :b 2}"]
   ["{:user/a 1, :b 2}" "{:user/a 1, :b 2}"]
   ["{:user/a 1, 'user/b 2}" "#:user{:a 1, b 2}"]
   ["{:user/a 1, :foo/b 2}" "{:user/a 1, :foo/b 2}"]
   ["{:user/a 1, :user/b 2, 100 200}"
    "{:user/a 1, :user/b 2, 100 200}"]
   ["(struct (create-struct :q/a :q/b :q/c) 1 2 3)"
    "#:q{:a 1, :b 2, :c 3}"]
   ["{:x.y/a {:rem 0}, :x.y/b {:rem 1}}"
    "#:x.y{:a {:rem 0}, :b {:rem 1}}"]
   ["(into (sorted-map-by (fn [k1 k2] (compare k1 k2)))
           {:x.y/a {:rem 0}, :x.y/b {:rem 1}})"
    "#:x.y{:a {:rem 0}, :b {:rem 1}}"]
   ["(sorted-map-by (fn [a b] (compare b a))
                    :k/a 1 :k/b 2 :k/c 3 :k/d 4 :k/e 5
                    :k/f 6 :k/g 7 :k/h 8 :k/i 9)"
    "#:k{:i 9, :h 8, :g 7, :f 6, :e 5, :d 4, :c 3, :b 2, :a 1}"]])

(defparity "B7" :known-divergence
  (let [actual
        (mapv (fn [[source _]]
                (:printed
                 (peek
                  (repl-session
                   ["(set! *print-namespace-maps* true)" source]))))
              namespace-map-cases)]
    (compared (mapv second namespace-map-cases) actual)))

(defparity "B8" :passing
  (compared "[:a 1]"
            (:printed
             (first (repl-session ["(first {:a 1})"])))))

(defparity "B9" :known-divergence
  (compared ["user.ParityRecord" "#user.ParityRecord{:a 1, :b 2}"]
            (mapv :printed
                  (repl-session
                   ["(defrecord ParityRecord [a b])"
                    "(->ParityRecord 1 2)"]))))

(defparity "B10" :known-divergence
  (let [printed (:printed (first (repl-session ["(atom 1)"])))]
    (checked "#object[clojure.lang.Atom 0x…]"
             printed
             (boolean
              (re-matches #"#object\[clojure\.lang\.Atom 0x[0-9a-f]+(?: .*)?\]"
                          printed)))))

(defparity "B11" :known-divergence
  (let [printed (:printed (first (repl-session ["(fn [] 1)"])))]
    (checked "a #object face with a demunged function name"
             printed
             (and (str/starts-with? printed "#object[")
                  (not (str/includes? printed "$"))
                  (not (str/includes? printed "sci.impl"))))))
