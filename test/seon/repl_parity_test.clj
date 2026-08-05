(ns seon.repl-parity-test
  "Stock-Clojure behavior checks exercised through Seon's production door."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [seon.cluster.reply :as reply]
            [seon.config :as config]
            [seon.print :as print]
            [seon.sci.eval :as sci.eval]
            [seon.sci.reader :as sci.reader]
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
  "Evaluate forms through one live production context and return each face."
  [forms]
  (let [db *database*
        ctx (sci.eval/cluster-ctx db)]
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
               :ending-ns (:seon.sci.eval/ending-ns evaluation)
               ;; The receipt stores the closed print tree; presentation is a
               ;; render-time projection through the text sink.
               :print-node result-edn
               :printed
               (print/emit-text (edn/read-string result-edn)
                                (:seon.print/options evaluation))
               ;; This is the face recoverable from today's stored receipt,
               ;; which does not yet persist the captured SCI print options.
               :stored-printed
               (print/emit-text (edn/read-string result-edn) {})
               :semantic-printed
               (pr-str (:seon.sci.admit/value evaluation))}]
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

(defn- location-data
  [result]
  (some
   (fn [value]
     (when (and (map? value)
                (some? (:line value))
                (some? (:column value)))
       value))
   (tree-seq coll? seq (get-in result [:value :seon.error/data]))))

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
  [{:parity/row "A7"
    :parity/reason "Route c: Seon's reader intentionally refuses #= input."}
   {:parity/row "D10"
    :parity/reason "Route c: the door never enables outer read-eval."}
   {:parity/row "E1"
    :parity/reason "The door exposes neither clojure.main/ex-triage nor a raw Throwable."}
   {:parity/row "E5"
    :parity/reason "The guarded context cannot construct a Throwable with a replaced stack."}
   {:parity/row "E9"
    :parity/reason "evaluate catches the raw Throwable before sci/stacktrace is observable."}
   {:parity/row "E10"
    :parity/reason "evaluate catches the raw Throwable before sci/format-stacktrace is observable."}
   {:parity/row "E15"
    :parity/reason "Route c: Babashka's richer error block is deliberately rejected."}
   {:parity/row "E16"
    :parity/reason "Route c: the explicit time-limit face is genuinely Seon's."}
   {:parity/row "F5"
    :parity/reason "Route c: StringWriter capture has no terminal flush behavior."}
   {:parity/row "F6"
    :parity/reason "Route c: the production door is the input and exposes no stdin."}
   {:parity/row "G5"
    :parity/reason "The production reader fixes features and exposes no :read-cond :preserve option."}
   {:parity/row "G9"
    :parity/reason "The production reader returns events and does not expose its private EOF sentinel."}
   {:parity/row "I1"
    :parity/reason "Already owned by seon.sci.admit-test, not a stock-parity behavior."}
   {:parity/row "I2"
    :parity/reason "P-TOTAL runs in seon.print-test; this gate has no second production-door assertion."}
   {:parity/row "I3"
    :parity/reason "P-TEE runs in seon.print-test; this gate has no second production-door assertion."}
   {:parity/row "I4"
    :parity/reason "Stored result re-render is owned by seon.render.value-test."}
   {:parity/row "I5"
    :parity/reason "Capability reachability belongs to the seon.effect security owner."}
   {:parity/row "I7"
    :parity/reason "The capped-result line is owned by seon.render.transcript-test."}
   {:parity/row "I8"
    :parity/reason "Duplicates E16 and is already owned by SCI's interrupt suite."}])

(defn- parity-vars
  []
  (->> (ns-interns (the-ns 'seon.repl-parity-test))
       vals
       (filter (comp :parity/row meta))))

(def ^:private expected-family-row-counts
  [["A" 10] ["B" 11] ["C" 8] ["D" 11] ["E" 16]
   ["F" 6] ["G" 10] ["H" 8] ["I" 8]])

(def ^:private expected-row-ids
  (into #{}
        (mapcat (fn [[family row-count]]
                  (map #(str family %) (range 1 (inc row-count)))))
        expected-family-row-counts))

(defn- current-row-ids
  []
  (concat (map (comp :parity/row meta) (parity-vars))
          (map :parity/row pending-rows)))

(defn- family-row-counts
  [row-ids]
  (mapv (fn [[family _]]
          [family (count (filter #(str/starts-with? % family) row-ids))])
        expected-family-row-counts))

(defn- assert-complete-row-inventory!
  []
  (let [row-ids (vec (current-row-ids))]
    (is (= (count expected-row-ids) (count row-ids))
        (str "REPL parity row cardinality changed: " (sort row-ids)))
    (is (= expected-row-ids (set row-ids))
        (str "REPL parity row identities changed: "
             {::missing (sort (remove (set row-ids) expected-row-ids))
              ::unexpected (sort (remove expected-row-ids row-ids))}))
    (is (= expected-family-row-counts (family-row-counts row-ids))
        (str "REPL parity family cardinalities changed: "
             (family-row-counts row-ids)))))

(defn- database-fixture
  [run-tests]
  (test-support/with-database
    (fn [connection]
      (config/apply! {:seon.db/connection connection
                      :seon.config/manifest
                      {:seon.config/on-core-error :record}})
      (binding [*database* @connection]
        (run-tests)))))

(defn- report-fixture
  [run-tests]
  (assert-complete-row-inventory!)
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
;; Parity cases receive only the immutable database value and create a fresh
;; SCI ctx per session; none receives the connection or transacts. One
;; namespace-scoped population therefore preserves case isolation.
(use-fixtures :once database-fixture)

;;; Family B — collection faces and elision
;; Ported from Clojure's printer tables at
;; reference-code/clojure/test/clojure/test_clojure/printer.clj:17-186.

(defparity "B1" :passing
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
  ;; Pending Lane 1: *print-length* does not survive into the next form.
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
  ;; Pending Lane 1: vector printing sees the reset *print-length* binding.
  (let [actual
        (mapv (fn [[length _]]
                (:printed
                 (peek
                  (repl-session
                   [(str "(set! *print-length* " length ")")
                    "[0 1 2 3 4]"]))))
              print-length-vector-cases)]
    (compared (mapv second print-length-vector-cases) actual)))

(defparity "B4" :passing
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
  ;; Pending Lane 1: *print-level* does not survive into the next form.
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
  ;; Pending Lane 1: neither print binding survives across these forms.
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
  ;; Pending Lane 1: `struct` remains absent from the agent context.
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
            (:semantic-printed
             (first (repl-session ["(first {:a 1})"])))))

(defparity "B9" :passing
  (compared ["user.ParityRecord" "#user.ParityRecord{:a 1, :b 2}"]
            (mapv :printed
                  (repl-session
                   ["(defrecord ParityRecord [a b])"
                    "(->ParityRecord 1 2)"]))))

(defparity "B10" :passing
  (let [printed (:printed (first (repl-session ["(atom 1)"])))]
    (checked "#object[clojure.lang.Atom 0x…]"
             printed
             (boolean
              (re-matches #"#object\[clojure\.lang\.Atom 0x[0-9a-f]+(?: .*)?\]"
                          printed)))))

(defparity "B11" :known-divergence
  ;; Print-path residual: the emitted name still exposes sci.impl.fns/fun.
  (let [printed (:printed (first (repl-session ["(fn [] 1)"])))]
    (checked "a #object face with a demunged function name"
             printed
             (and (str/starts-with? printed "#object[")
                  (not (str/includes? printed "$"))
                  (not (str/includes? printed "sci.impl"))))))

;;; Family A — scalar print faces

(defparity "A1" :passing
  (compared "#'user/parity_scalar"
            (:printed
             (first (repl-session ["(def parity_scalar 1)"])))))

(defparity "A2" :known-divergence
  ;; Pending Lane 1: SCI rejects Float +/-Infinity before it reaches printing.
  (compared ["##Inf" "##-Inf" "##NaN" "##Inf" "##-Inf" "##NaN"]
            (mapv :printed
                  (repl-session
                   ["##Inf" "##-Inf" "##NaN"
                    "(float ##Inf)" "(float ##-Inf)" "(float ##NaN)"]))))

(defparity "A3" :passing
  (compared ["1N" "1M"]
            (mapv :semantic-printed (repl-session ["1N" "1M"]))))

(defparity "A4" :passing
  (compared ["\\a" "\\newline"]
            (mapv :semantic-printed
                  (repl-session ["\\a" "\\newline"]))))

(defparity "A5" :passing
  (compared "\"a\\n\\\"\\\\b\""
            (:semantic-printed
             (first (repl-session ["\"a\\n\\\"\\\\b\""])))))

(defparity "A6" :passing
  (let [result
        (first
         (repl-session
          ["(binding [*print-readably* nil] (pr-str \"hello\"))"]))]
    (compared "hello" (:value result))))

(defparity "A8" :passing
  (compared
   ["#inst \"2020-01-01T00:00:00.000-00:00\""
    "#uuid \"550e8400-e29b-41d4-a716-446655440000\""]
   (mapv :semantic-printed
         (repl-session
          ["#inst \"2020-01-01T00:00:00.000-00:00\""
           "#uuid \"550e8400-e29b-41d4-a716-446655440000\""]))))

(defparity "A9" :passing
  (let [source (str "(try (throw (ex-info \"parity\" {:a 1}))\n"
                    "     (catch Throwable failure failure))")
        printed (:printed (first (repl-session [source])))
        ;; The exact trace is JVM-dependent. Stock's stable contract is the
        ;; readable #error tag whose map round-trips to Throwable->map.
        stock-face? (str/starts-with? printed "#error {")]
    (checked "#error {:cause … :via […] :trace […]}"
             printed stock-face?)))

(defparity "A10" :passing
  (let [result
        (first
         (repl-session
          ["(binding [*print-meta* true] (prn ^{:a true} []))"]))]
    (checked "metadata prefix followed by [] and newline"
             (:out result)
             (and (str/starts-with? (:out result) "^{:a true}")
                  (str/ends-with? (:out result) "[]\n")))))

;;; Family C — REPL session vars

;; Pending Lane 1: the durable session image must restore *1 across a restart.
(defparity "C1" :known-divergence
  (compared 1
            (:value (peek (repl-session ["1" "*1"])))))

;; Pending Lane 1: the durable session image must restore *1/*2/*3 ordering.
(defparity "C2" :known-divergence
  (compared [3 2 1]
            (:value
             (peek (repl-session ["1" "2" "3" "[*1 *2 *3]"])))))

;; Pending Lane 1: the durable session image must restore the last error in *e.
(defparity "C3" :known-divergence
  (let [results
        (repl-session
         ["(throw (ex-info \"parity\" {:a 6}))"
          "(some? *e)"])]
    (compared true (:value (peek results)))))

;; Pending Lane 1: restored *e must retain the caught error's ex-data.
(defparity "C4" :known-divergence
  (let [results
        (repl-session
         ["(throw (ex-info \"parity\" {:a 6}))"
          "(ex-data *e)"])]
    (compared {:a 6} (:value (peek results)))))

;; Pending Lane 1: pst depends on the durable session image's restored *e.
(defparity "C5" :known-divergence
  (let [results
        (repl-session
         ["(/ 1 0)" "(pst 1)"])]
    (checked "pst reads *e and prints Divide by zero"
             (peek results)
             (str/includes? (:out (peek results)) "Divide by zero"))))

(defparity "C6" :known-divergence
  ;; Pending Lane 1: the production door still binds *out* and *err* together.
  (compared false
            (:value
             (first (repl-session ["(identical? *out* *err*)"])))))

(defparity "C7" :passing
  (let [plan (reply/sources "[] [] [999]" 'user)
        sources (mapv :seon.cluster.run.form/source plan)
        results (repl-session sources)]
    (compared ["[]" "[]" "[999]"]
              (mapv :semantic-printed results))))

(defparity "C8" :passing
  (let [results
        (repl-session
         ["(def parity_atom (atom 0))"
          "(swap! parity_atom inc)"
          "@parity_atom"])]
    (compared 1 (:value (peek results)))))

;;; Family D — doc, source, dir, apropos, and find-doc

(defparity "D1" :passing
  (let [results
        (repl-session
         ["(defmacro parity_doc \"foodoc\" ([x]) ([x y]))"
          "(doc parity_doc)"])
        expected
        (str "-------------------------\n"
             "user/parity_doc\n"
             "([x] [x y])\n"
             "Macro\n"
             "  foodoc\n")]
    (compared expected (:out (peek results)))))

(defparity "D2" :passing
  (let [results
        (repl-session
         ["(ns parity.doc \"foodoc\")"
          "(doc parity.doc)"])]
    (checked "namespace documentation includes its name and docstring"
             (:out (peek results))
             (and (str/includes? (:out (peek results)) "parity.doc")
                  (str/includes? (:out (peek results)) "foodoc")))))

(defparity "D3" :passing
  (let [results (repl-session ["(doc catch)" "(doc try)"])]
    (compared (:out (first results)) (:out (second results)))))

(defparity "D4" :passing
  (let [result
        (first (repl-session ["(let [x 1] (doc x))"]))]
    (compared "" (:out result))))

(defparity "D5" :known-divergence
  ;; Pending Lane 1: find-doc has not been admitted over program-graph facts.
  (let [result (first (repl-session ["(find-doc #\"map\")"]))]
    (checked "multiple matching documentation entries"
             result
             (and (nil? (:err result))
                  (str/includes? (:out result) "clojure.core/map")))))

(defparity "D6" :known-divergence
  ;; Pending Lane 1: apropos has not been admitted over program-graph facts.
  (let [results
        (repl-session
         ["(apropos \"defmacro\")"
          "(apropos #\"nothing-has-this-name\")"])]
    (compared [true []]
              [(some? (some #{'clojure.core/defmacro}
                            (:value (first results))))
               (:value (second results))])))

(defparity "D7" :passing
  (let [output (:out (first (repl-session ["(dir clojure.string)"])))
        lines (str/split-lines output)]
    (checked "sorted clojure.string publics"
             lines
             (and (= lines (sort lines))
                  (some #{"includes?"} lines)))))

(defparity "D8" :passing
  (let [error (:err (first (repl-session ["(dir parity.no-such-ns)"])))]
    (checked "No namespace: parity.no-such-ns found"
             error
             (str/includes? error "No namespace: parity.no-such-ns found"))))

(defparity "D9" :known-divergence
  ;; Pending Lane 1: source does not yet read exact program-graph source.
  (let [result
        (first (repl-session ["(source my.message/send)"]))]
    (checked "the exact :seon.fn/source bytes"
             result
             (and (nil? (:err result))
                  (str/includes? (:out result) "(defn send")))))

(defparity "D11" :known-divergence
  ;; Pending Lane 1: the table face exists, but print-table is unresolved.
  (let [result
        (first
         (repl-session
          ["(print-table [{:a 1 :b \"x\"} {:a 22 :b \"yy\"}])"]))]
    (compared
     (str "\n| :a | :b |\n"
          "|----+----|\n"
          "|  1 |  x |\n"
          "| 22 | yy |\n")
     (:out result))))

;;; Family E — error faces and triage

(defparity "E2" :known-divergence
  ;; Pending Lane 1: the production error report does not use SCI's formatter.
  (let [error
        (:err
         (first
          (repl-session ["(throw (Error. \"xyz\"))"])))]
    (checked "Execution error (Error) at (REPL:1).\nxyz\n"
             error
             (boolean
              (re-find
               #"^Execution error \(Error\) at .*\(REPL:1\)\.\nxyz\n?$"
               error)))))

(defparity "E3" :known-divergence
  ;; Pending Lane 1: compile errors do not yet use SCI's formatter.
  (let [error (:err (first (repl-session ["parity_missing_symbol"])))]
    (checked "Syntax error compiling at (REPL:1:1)"
             error
             (str/starts-with? error
                               "Syntax error compiling at (REPL:1:1)"))))

(defparity "E4" :passing
  (let [result
        (first
         (repl-session
          [(str "(try (throw (ex-info \"inner\" {:a 1}))\n"
                "     (catch Throwable failure failure))")]))]
    (checked "a Throwable value whose #error map carries root cause and data"
             result
             (str/starts-with? (:printed result) "#error {"))))

(defparity "E6" :passing
  (let [namespace-name (symbol (str "parity.e6." (gensym)))
        function-name (symbol (str namespace-name) "arity-probe")
        result
        (peek
         (repl-session
          [(str "(ns " namespace-name ")")
           "(defn arity-probe [x] x)"
           "(arity-probe)"]))]
    (compared (str "Wrong number of args (0) passed to: " function-name)
              (:err result))))

(defparity "E7" :passing
  (let [result
        (first
         (repl-session ["(apply (fn []) [1])"]))]
    (compared "Wrong number of args (1) passed to: function of arity 0"
              (:err result))))

(defparity "E8" :known-divergence
  ;; Pending Lane 1: the cause-side ex-data is not exposed on the error value.
  (let [result
        (first
         (repl-session
          ["(throw (ex-info \"parity\" {:parity/user-data true}))"]))]
    (compared {:parity/user-data true}
              (get-in result [:value :seon.error/data]))))

(defparity "E11" :passing
  (let [function-name (symbol (str "parity_loop_" (gensym)))
        result
        (peek
         (repl-session
          [(str "(defn " function-name " []\n"
                "  (loop [i 0]\n"
                "    (subs nil 0)))")
           (str "(" function-name ")")]))
        data (location-data result)]
    (checked "a located loop frame"
             data
             (and (some? (:line data))
                  (some? (:column data))))))

(def ^:private destructuring-location-cases
  [["(str (let [[a] 1] a))" [1 6]]
   ["(str (for [[a] [0]] :foo))" [1 6]]
   ["(str (for [[a] 1] (/ 1 a)))" [1 6]]
   ["(str (map (fn [[a]] a) [0]))" [1 11]]
   ["(str (if-let [[a] 0] a))" [1 6]]
   ["(str (when-let [[a] 0] a))" [1 6]]
   ["(str (if-some [[a] 0] a))" [1 6]]
   ["(str (when-some [[a] 0] a))" [1 6]]
   ["(str (doseq [a 0] a))" [1 6]]
   ["(str (doseq [[a] [0]] a))" [1 6]]])

(defparity "E12" :passing
  (let [actual
        (mapv
         (fn [[source _]]
           (let [data (location-data (first (repl-session [source])))]
             [(:line data) (:column data)]))
         destructuring-location-cases)]
    (compared (mapv second destructuring-location-cases) actual)))

(def ^:private let-like-arity-cases
  [["(str (if-let [x 0 y 1] x))"
    "if-let requires exactly 2 forms in binding vector"]
   ["(str (when-let [x 0 y 1] x))"
    "when-let requires exactly 2 forms in binding vector"]
   ["(str (if-some [x 0 y 1] x))"
    "if-some requires exactly 2 forms in binding vector"]
   ["(str (when-some [x 0 y 1] x))"
    "when-some requires exactly 2 forms in binding vector"]])

(defparity "E13" :passing
  (let [actual
        (mapv
         (fn [[source _]]
           (let [result (first (repl-session [source]))
                 data (location-data result)]
             [(:err result) [(:line data) (:column data)]]))
         let-like-arity-cases)]
    (compared
     (mapv (fn [[_ message]] [message [1 6]]) let-like-arity-cases)
     actual)))

(defparity "E14" :passing
  (let [result
        (first
         (repl-session
          [(str "(try (throw (ex-info \"parity\" nil))\n"
                "     (catch Throwable failure (ex-data failure)))")]))]
    (compared {} (:value result))))

;;; Family F — output capture and print vars

(defparity "F1" :passing
  (let [results
        (repl-session
         ["(println \"hello\")"
          "(print \"hello\")"
          "(prn \"hello\")"
          "(pr \"hello\")"
          "(newline)"])]
    (compared ["hello\n" "hello" "\"hello\"\n" "\"hello\"" "\n"]
              (mapv :out results))))

(defparity "F2" :passing
  (compared "hello\n"
            (:value
             (first
              (repl-session
               ["(with-out-str (println \"hello\"))"])))))

(defparity "F3" :known-divergence
  ;; Pending Lane 1: captured *print-length* is not persisted on the receipt.
  (let [result
        (peek
         (repl-session
          ["(set! *print-length* 3)" "(range 10)"]))]
    (compared "(0 1 2 ...)" (:stored-printed result))))

(defparity "F4" :known-divergence
  ;; Pending Lane 1: captured *print-level* is not persisted on the receipt.
  (let [result
        (peek
         (repl-session
          ["(set! *print-level* 1)" "[:a [:b [:c]]]"]))]
    (compared "[:a #]" (:stored-printed result))))

;;; Family G — reader behavior

(defn- read-events
  [text]
  (sci.reader/read
   {:seon.sci.reader/text text
    :seon.sci.reader/ns 'user
    :seon.sci.reader/aliases {}
    :seon.sci.reader/refers {}
    :seon.sci.reader/features #{:clj}
    :seon.sci.reader/tags {}
    :seon.sci.reader/max-chars 1048576}))

(defparity "G1" :passing
  (let [results
        (repl-session
         ["#inst \"2020-01-01T00:00:00.000-00:00\""
          "#uuid \"550e8400-e29b-41d4-a716-446655440000\""])]
    (checked "built-in inst and uuid literals"
             results
             (every? #(and (nil? (:err %))
                           (or (inst? (:value %))
                               (uuid? (:value %))))
                     results))))

(defparity "G2" :passing
  (let [result (first (repl-session ["#parity/unknown [1]"]))]
    (checked "Reader tag is not accepted: parity/unknown"
             (:err result)
             (str/includes? (:err result)
                            "Reader tag is not accepted: parity/unknown"))))

(defparity "G3" :passing
  (let [result (first (repl-session ["#=(+ 20 22)"]))]
    (checked "Reader evaluation is not accepted: #="
             (:err result)
             (str/includes? (:err result)
                            "Reader evaluation is not accepted: #="))))

(defparity "G4" :passing
  (let [results
        (repl-session
         ["(ns parity.reader (:require [clojure.string :as string]))"
          "#::string{:a 1 :other/b 2}"
          "#::{:a 1 :other/b 2}"])]
    (compared [{:clojure.string/a 1 :other/b 2}
               {:parity.reader/a 1 :other/b 2}]
              (mapv :value (rest results)))))

(defparity "G6" :passing
  (let [events (read-events "\n\n  (inc 1)")
        event (first events)]
    (compared [3 3]
              [(:seon.sci.reader/line event)
               (:seon.sci.reader/column event)])))

(defparity "G7" :passing
  (let [results
        (repl-session
         ["(def parity_reader_atom (atom 41))"
          "#'parity_reader_atom"
          "@parity_reader_atom"
          "(#(+ % 1) 41)"
          "`parity_reader_atom"])]
    (compared [41 42 'user/parity_reader_atom]
              [(:value (nth results 2))
               (:value (nth results 3))
               (:value (nth results 4))])))

(defparity "G8" :passing
  (let [results
        (repl-session
         ["1/2" "3.14M" "100N" "\"\\7\"" "0" "-0" "+0"])]
    (compared [1/2 3.14M 100N (str (char 7)) 0 0 0]
              (mapv :value results))))

(defparity "G10" :passing
  (let [invalid-symbol (read-events "foo/bar/baz")
        invalid-value (read-events "##Foo")]
    (checked "invalid symbols and symbolic values are clean reader errors"
             [invalid-symbol invalid-value]
             (every? #(= :seon.sci.reader/unreadable
                          (:seon.error/kind %))
                     [invalid-symbol invalid-value]))))

;;; Family H — namespaces and vars

(defparity "H1" :passing
  (compared "#'user/parity_h1"
            (:printed
             (first (repl-session ["(def parity_h1 1)"])))))

(defparity "H2" :known-divergence
  ;; Pending Lane 1: ns/require return values still differ from stock.
  (compared ["nil" "nil"]
            (mapv :printed
                  (repl-session
                   ["(ns parity.h2)"
                    "(require 'clojure.set)"]))))

(defparity "H3" :passing
  (let [results
        (repl-session
         ["(in-ns 'parity.h3)" "*ns*"])]
    (compared ['parity.h3 'parity.h3]
              (mapv :ending-ns results))))

(defparity "H4" :passing
  (let [result
        (peek
         (repl-session
          ["(ns parity.h4)"
           "(def x 1)"
           "(ns-publics 'parity.h4)"]))]
    (checked "{x #'parity.h4/x}"
             (:printed result)
             (and (str/includes? (:printed result) "#'parity.h4/x")
                  (not (str/includes? (:printed result)
                                      "seon.sci.admit"))))))

(defparity "H5" :known-divergence
  ;; Pending Lane 1: namespace mutations are still masked by ctx handling.
  (let [results
        (repl-session
         ["(ns parity.h5 (:require [clojure.string :as string]))"
          "(def x 1)"
          "(ns-unmap 'parity.h5 'x)"
          "(ns-unalias 'parity.h5 'string)"
          "(find-var 'parity.h5/x)"
          "(remove-ns 'parity.h5)"
          "(find-ns 'parity.h5)"])]
    (checked "namespace mutations are visible to find-var/find-ns"
             results
             (and (nil? (:value (nth results 4)))
                  (nil? (:value (peek results)))))))

(defparity "H6" :passing
  (let [result
        (peek
         (repl-session
          ["(defn parity_meta \"doc\" [x] x)"
           "(meta #'parity_meta)"]))
        printed (:printed result)]
    (checked "metadata with arglists, doc, name, and #object namespace"
             printed
             (and (str/includes? printed ":arglists ([x])")
                  (str/includes? printed ":doc \"doc\"")
                  (str/includes? printed "#object[sci.lang.Namespace")
                  (not (str/includes? printed "seon.sci.admit"))))))

(defparity "H7" :passing
  (let [result
        (peek
         (repl-session
          ["(def ^:dynamic *parity_dynamic* 1)"
           (str "[(binding [*parity_dynamic* 2] *parity_dynamic*)\n"
                " (do (with-redefs [*parity_dynamic* 3] *parity_dynamic*))\n"
                " (alter-var-root #'*parity_dynamic* inc)]")]))]
    (compared [2 3 2] (:value result))))

(defparity "H8" :passing
  (let [result
        (first
         (repl-session
          ["(alter-var-root #'clojure.core/inc (constantly dec))"]))]
    (checked "built-in var is read-only"
             (:err result)
             (str/includes? (:err result) "read-only"))))

;;; Family I — genuinely Seon-specific behavior

(defparity "I6" :passing
  (let [first-session
        (repl-session ["(def parity_scratch 41)" "(inc parity_scratch)"])
        second-session (repl-session ["parity_scratch"])]
    (checked "scratch defs persist within a session and not across sessions"
             {:within (peek first-session) :across (first second-session)}
             (and (= 42 (:value (peek first-session)))
                  (some? (:err (first second-session)))))))
