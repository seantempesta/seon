(ns seon.sci.entry-gate-test
  "Entry into an agent's SCI context: every program namespace carries its
  reader context, a contracted definition is armed at evaluation, and a
  definition without a contract is refused and its definitions restored."
  (:require [clojure.test :refer [deftest is]]
            [seon.config :as config]
            [seon.db :as db]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as support]))

(defn- evaluate-in
  ([ctx source] (evaluate-in ctx source (config/result-caps config/defaults)))
  ([ctx source caps]
  (sci.eval/evaluate
   {:seon.cluster.eval/source source
    :seon.cluster.eval/ns [:seon.ns/name 'scratch.entry-gate]
    :seon.sci.eval/ctx ctx
    :seon.sci.admit/caps caps
    :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms config/defaults)})))

(deftest every-program-namespace-carries-its-stored-aliases
  (support/with-database
   (fn [connection]
     (let [ctx (support/fork-cluster-ctx connection)
           row (db/pull (db/db connection) '[{:seon.ns/aliases [*]}]
                        [:seon.ns/name 'seon.note])
           stored (into {} (map (juxt :seon.ns.alias/local :seon.ns.alias/target-ns))
                        (:seon.ns/aliases row))]
       (is (= 'seon.db (get stored 'db)) "the fixture's seon.note row declares db")
       (is (= stored
              (:seon.sci.admit/value
               (evaluate-in ctx "(into {} (map (fn [[k v]] [k (ns-name v)])) (ns-aliases 'seon.note))")))
           "an uninterpreted file namespace has its row's aliases in SCI")))))

(deftest a-contracted-definition-is-armed-before-any-commit
  (support/with-database
   (fn [connection]
     (let [ctx (support/fork-cluster-ctx connection)
           defined (evaluate-in ctx (str "(defn twice \"Double x.\" "
                                         "{:malli/schema [:=> [:cat :int] :int]} [x] (* 2 x))"))]
       (is (= :ok (get-in defined [:seon.sci.admit/record :seon.eval/outcome])))
       (is (= 4 (:seon.sci.admit/value (evaluate-in ctx "(twice 2)"))))
       (let [refused (:seon.sci.admit/value (evaluate-in ctx "(twice \"a\")"))]
         (is (= :input (:seon.instrument/check refused))
             (pr-str refused)))))))

(deftest a-definition-without-a-contract-is-refused-and-restored
  (support/with-database
   (fn [connection]
     (let [ctx (support/fork-cluster-ctx connection)]
       (evaluate-in ctx (str "(defn twice \"Double x.\" "
                             "{:malli/schema [:=> [:cat :int] :int]} [x] (* 2 x))"))
       (doseq [source ["(defn bare \"No contract.\" [x] (inc x))"
                       "(defn twice \"No contract.\" [x] (* 3 x))"]]
         (let [refusal (:seon.sci.admit/value (evaluate-in ctx source))]
           (is (= 'seon.sci.eval/evaluate (:seon.error/operation refusal)) source)
           (is (= :seon.fn/spec (:seon.sci.eval/missing-declaration refusal)) source)))
       (is (= 'absent (:seon.sci.admit/value
                       (evaluate-in ctx "(if (resolve 'bare) 'present 'absent)")))
           "the refused definition never entered the context")
       (is (= 4 (:seon.sci.admit/value (evaluate-in ctx "(twice 2)")))
           "the refused redefinition left the armed root in place")
       (is (= :input
              (:seon.instrument/check
               (:seon.sci.admit/value (evaluate-in ctx "(twice \"a\")")))))))))

(deftest printed-output-rides-with-the-value-under-the-storage-bound
  (support/with-database
   (fn [connection]
     (let [ctx (support/fork-cluster-ctx connection)
           caps (assoc (config/result-caps config/defaults)
                       :seon.config.eval.result/max-bytes 64)
           printed (evaluate-in ctx "(do (println \"hi\") (binding [*out* *err*] (print \"é\")) 1)" caps)
           runaway (evaluate-in ctx "(do (dotimes [_ 100] (print \"0123456789\")) :done)" caps)
           refusal (:seon.sci.admit/value runaway)]
       (is (= "hi\né" (:seon.cluster.eval/output printed)) (pr-str printed))
       (is (= "1" (:seon.eval/shown printed)))
       (is (= (apply str (repeat 6 "0123456789"))
              (:seon.cluster.eval/output runaway))
           "the exact text up to the bound is kept")
       (is (= :seon.config.eval.result/max-bytes (:seon.error/member refusal)) (pr-str refusal))
       (is (= 64 (:seon.sci.admit/bound-bytes refusal)))
       (is (= 70 (:seon.sci.admit/bytes refusal)))))))

(deftest ^{:seon/tasks #{"132fee0467c8"}} every-definition-a-form-makes-is-gated-and-armed
  (support/with-database
   (fn [connection]
     (let [ctx (support/fork-cluster-ctx connection)
           absent? #(= 'absent (:seon.sci.admit/value
                                (evaluate-in ctx (str "(if (resolve '" % ") 'present 'absent)"))))]
       (evaluate-in ctx (str "(defn twice \"Double x.\" "
                             "{:malli/schema [:=> [:cat :int] :int]} [x] (* 2 x))"))
       (doseq [source ["(do (defn nested \"No contract.\" [x] x))"
                       "(let [k 3] (defn nested \"No contract.\" [x] (* k x)))"
                       "(def nested (fn [x] x))"
                       ;; A point-free def is a declaration since P2-A (1a7029d81): gated like any function.
                       "(def nested (comp inc inc))"]]
         (let [refusal (:seon.sci.admit/value (evaluate-in ctx source))]
           (is (= :seon.fn/spec (:seon.sci.eval/missing-declaration refusal)) source)
           (is (absent? "nested") source)
           (is (= 4 (:seon.sci.admit/value (evaluate-in ctx "(twice 2)"))) source)))
       (is (= :ok (get-in (evaluate-in ctx "(defrecord Pair [a b])")
                          [:seon.sci.admit/record :seon.eval/outcome]))
           "a record is one host-bound declaration, not gated")
       (let [refusal (:seon.sci.admit/value
                      (evaluate-in ctx (str "(do (defn one \"Inc.\" {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))"
                                            " (defn two \"Dec.\" {:malli/schema [:=> [:cat :int] :int]} [x] (dec x)))")))]
         (is (= '[scratch.entry-gate/one scratch.entry-gate/two]
                (:seon.sci.eval/declarations refusal))
             "two declarations in one form refuse, naming both, rather than keep one row")
         (is (and (absent? "one") (absent? "two"))))
       (doseq [source ["(defn one \"Inc.\" {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))"
                       "(defn two \"Dec.\" {:malli/schema [:=> [:cat :int] :int]} [x] (dec x))"]]
         (evaluate-in ctx source))
       (doseq [call ["(one \"a\")" "(two \"a\")"]]
         (is (= :input (:seon.instrument/check
                        (:seon.sci.admit/value (evaluate-in ctx call))))
             call))))))

(deftest ^{:seon/tasks #{"132fee0467c8"}} a-form-changes-the-program-by-one-indexed-declaration
  ;; The edge cases the S1 REPL probes exposed (landing lane-impl-repl-m1-2026-09-25.md).
  (support/with-database
   (fn [connection]
     (let [ctx (support/fork-cluster-ctx connection)
           value #(:seon.sci.admit/value (evaluate-in ctx %))
           absent? #(= 'absent (value (str "(if (resolve '" % ") 'present 'absent)")))
           contract "{:malli/schema [:=> [:cat :int] :int]}"
           stored "seon.note/render-notes-ai"]
       (doseq [[source changed] [[(str "(eval '(defn quoted \"D.\" " contract " [x] x))") 'quoted]
                                 [(str "(defmacro make [n] (list 'defn n \"D.\" '" contract " '[x] 'x))") nil]
                                 ["(make generated)" 'generated]
                                 [(str "(defn scratch.entry-gate/same-ns \"D.\" " contract " [x] x)") 'same-ns]]
               :let [refusal (value source)]
               :when changed]
         (is (= #{(symbol "scratch.entry-gate" (str changed))}
                (:seon.sci.eval/unindexed refusal))
             (str source " is an unindexed change: " (pr-str refusal)))
         (is (absent? (str changed)) source))
       (value (str "(defn kept \"D.\" " contract " [x] (inc x))"))
       (doseq [source ["(def kept 42)" "(def kept nil)"]]
         (is (= '#{scratch.entry-gate/kept} (:seon.sci.eval/unindexed (value source)))
             (str source ": a function replaced by a value is a definition change"))
         (is (= 2 (value "(kept 1)")) source))
       (is (= '#{scratch.entry-gate/->One scratch.entry-gate/->Two}
              (set (:seon.sci.eval/declarations
                    (value "(do (defrecord One [x]) (defrecord Two [y]))"))))
           "two records are two declarations")
       (is (= '#{scratch.entry-gate/hidden}
              (:seon.sci.eval/unindexed
               (value (str "(do (defrecord Pair [a]) (eval '(defn hidden \"D.\" " contract " [x] x)))"))))
           "a record exempts only the helpers defined at its own declaration")
       (is (absent? "hidden"))
       (is (= '[scratch.entry-gate/twin scratch.entry-gate/twin]
              (:seon.sci.eval/declarations
               (value (str "(do (defn twin \"D.\" " contract " [x] x) "
                           "(defn twin \"D.\" " contract " [x] (inc x)))"))))
           "two occurrences of one name are two declarations")
       (is (= '#{scratch.entry-gate/overwritten}
              (:seon.sci.eval/unindexed
               (value (str "(do (defn overwritten \"D.\" " contract " [x] x)\n"
                           " (eval '(defn overwritten \"E.\" " contract " [x] (inc x))))"))))
           "an analyzed name is not the declaration that ran when a later eval overwrote it")
       (is (:seon.error/message (value (str "(defn trailing \"D.\" " contract " [x] x))")))
           "an unmatched closer refuses the whole input, as Clojure's reader does")
       (is (absent? "trailing") "and the complete form before it never ran")
       (is (:seon.sci.eval/unindexed (value "(alter-meta! #'kept assoc :doc \"changed\")"))
           "a metadata-only change is a definition change too")
       (is (= "D." (value "(:doc (meta #'kept))")))
       (is (:seon.error/message (value "(defn kept \"D.\" {:malli/schema [:=> :int]} [x] x)"))
           "a contract that fails validation refuses")
       (is (= 2 (value "(kept 1)")) "and restores the prior root")
       (value (str "(def held (var " stored "))"))
       (is (= #{(symbol stored)}
              (:seon.sci.eval/unindexed
               (value (str "(alter-meta! (var " stored ") assoc :doc \"changed\")"))))
           "an inherited stored Var's metadata change is a definition change")
       (is (not= "changed" (value "(:doc (meta held))"))
           "and SCI copied the Var before writing it, so the shared one never changed")
       (is (= #{(symbol stored)}
              (:seon.sci.eval/unindexed (value (str "(do (ns-unmap 'seon.note 'render-notes-ai) 1)"))))
           "a nested unmap of a stored row's Var refuses as unindexed")
       (let [refusal (value (str "(do (defn fresh \"D.\" " contract " [x] x) "
                                 "(ns-unmap 'seon.note 'render-notes-ai))"))]
         (is (= '[scratch.entry-gate/fresh] (:seon.sci.eval/declarations refusal)))
         (is (= #{(symbol stored)} (:seon.sci.eval/removals refusal))
             "a definition beside a removal refuses, naming both")
         (is (absent? "fresh")))
       (is (:seon.error/message
            (value (str "(do (intern 'seon.note 'render-notes-ai (fn [x] x)) "
                        "(throw (ex-info \"boom\" {})))"))))
       (is (true? (value (str "(identical? held (var " stored "))")))
           "every refusal and a later throw leave the stored row's Var as it was")
       (let [admitted (evaluate-in ctx (str "(defn kept \"D.\" " contract " [x] (* 5 x))"))]
         (is (= 'scratch.entry-gate/kept (get-in admitted [:seon.program/row :seon.fn/sym]))
             (pr-str (:seon.sci.admit/value admitted)))
         (is (= 5 (value "(kept 1)")))
         ((:seon.sci.eval/restore admitted))
         (is (= 2 (value "(kept 1)"))
             "a settlement refusal restores the admitted change through its evaluation"))))))

(deftest ^{:seon/tasks #{"132fee0467c8"}} a-definition-is-the-root-its-analyzed-declaration-ran
  ;; Astra e19adf392 findings 1 and 3: provenance is the analyzer's exact span
  ;; plus SCI's rebound mark, never a text search; a refused change to an
  ;; unbound Var leaves it unbound.
  (support/with-database
   (fn [connection]
     (let [ctx (support/fork-cluster-ctx connection)
           value #(:seon.sci.admit/value (evaluate-in ctx %))
           absent? #(= 'absent (value (str "(if (resolve '" % ") 'present 'absent)")))
           contract "{:malli/schema [:=> [:cat :int] :int]}"
           declaration (str "(defn echoed \"D.\" " contract " [x] x)")]
       (is (= '#{scratch.entry-gate/rewritten}
              (:seon.sci.eval/unindexed
               (value (str "(do\n  (defn rewritten \"Identity.\" " contract " [x] x)\n"
                           "  (alter-var-root #'rewritten (constantly inc)))"))))
           "a root written after the definition is not the analyzed body")
       (is (absent? "rewritten"))
       (let [admitted (evaluate-in ctx (str "(do\n  ;" declaration "\n  " declaration ")"))
             row (:seon.program/row admitted)]
         (is (= 'scratch.entry-gate/echoed (:seon.fn/sym row))
             (str "a comment holding the same bytes is not the declaration: "
                  (pr-str (:seon.sci.admit/value admitted))))
         (is (= declaration (:seon.fn/source row)))
         (is (not (contains? row :seon.fn/form-span))
             "the span locates the row within this form only")
         (is (= 1 (value "(echoed 1)"))))
       (is (= 'scratch.entry-gate/later
              (get-in (evaluate-in ctx (str "(do (def note \"é😀\")\n"
                                            "  (defn later \"😀\" " contract " [x] x))"))
                      [:seon.program/row :seon.fn/sym]))
           "positions after multibyte characters and lines match their analyzed bytes")
       (value "(declare pending)")
       (is (= :seon.fn/spec (:seon.sci.eval/missing-declaration
                             (value "(defn pending \"Missing contract.\" [x] x)"))))
       (is (false? (value "(bound? #'pending)"))
           "a refused definition of an unbound Var leaves it unbound")
       (is (:seon.error/message
            (value "(do (defn pending \"M.\" [x] x) (ns-unmap 'scratch.entry-gate 'pending) (defn bare \"No contract.\" [x] x))")))
       (is (= [true false] (value "[(some? (resolve 'pending)) (bound? #'pending)]"))
           "a refused redefinition-then-unmap puts the unbound Var back unbound")))))

(deftest a-top-level-do-admits-each-child-before-the-next-runs
  ;; Clojure evaluates a top-level `do`'s children as top-level forms
  ;; (Compiler.java:7742-7748): a later child calls the earlier child's
  ;; admitted, armed definition. A def a failed form leaves is said loudly.
  (support/with-database
   (fn [connection]
     (let [ctx (support/fork-cluster-ctx connection)
           value #(:seon.sci.admit/value (evaluate-in ctx %))
           contract "{:malli/schema [:=> [:cat :int] :int]}"
           called (evaluate-in ctx (str "(do (defn inc1 \"Inc.\" " contract " [x] (inc x)) (inc1 \"a\"))"))]
       (is (= :input (:seon.instrument/check (:seon.sci.admit/value called)))
           (str "the call inside the do meets the armed contract: " (pr-str (:seon.sci.admit/value called))))
       (is (re-find #"NOT ADMITTED.*scratch\.entry-gate/inc1" (str (:seon.cluster.eval/output called)))
           (str "the failed form says its definition is no row: " (pr-str (:seon.cluster.eval/output called))))
       (is (= :input (:seon.instrument/check (value "(inc1 \"a\")")))
           "the private definition a failed form left stays armed")
       (let [admitted (evaluate-in ctx (str "(do (do (defn inc2 \"Inc.\" " contract " [x] (inc x))) (inc2 1))"))]
         (is (= 2 (:seon.sci.admit/value admitted)))
         (is (= 'scratch.entry-gate/inc2 (get-in admitted [:seon.program/row :seon.fn/sym]))
             "a nested do's definition is the form's one row")
         (is (not (:seon.cluster.eval/output admitted))))
       (is (= :input (:seon.instrument/check (value "(inc2 \"a\")"))))
       (let [failed (evaluate-in ctx (str "(let [] (defn inc3 \"Inc.\" " contract " [x] (inc x)) "
                                          "(throw (ex-info \"boom\" {})))"))]
         (is (re-find #"NOT ADMITTED.*scratch\.entry-gate/inc3" (str (:seon.cluster.eval/output failed)))
             "any failed form's surviving definition is said, not only a do's"))
       (is (not (re-find #"NOT ADMITTED"
                         (str (:seon.cluster.eval/output (evaluate-in ctx "(do (def data 1) (throw (ex-info \"boom\" {})))")))))
           "private data is no program definition")
       (is (= '[scratch.entry-gate/one scratch.entry-gate/two]
              (:seon.sci.eval/declarations
               (value (str "(do (defn one \"D.\" " contract " [x] x) (defn two \"D.\" " contract " [x] x) (two \"a\"))"))))
           "a second declaration refuses at its own child, before a later child runs")))))
