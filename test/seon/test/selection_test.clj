(ns ^{:seon.test/platform
       "Moving part: the gate's own changed-test selector."}
    seon.test.selection-test
  "The class regression for the default tier's selector.

  THE CLASS: a gate that runs only some tests silently skips a test that
  could have observed the change. The selector must therefore be exact in
  both directions — every reaching test present, every non-reaching test
  absent — and it must decide from recorded facts (`:seon.fn/calls` edges
  and content digests), never from a modification time, a filename, or a
  maintained list."
  (:require [seon.schema] [babashka.process :as process]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.test.cache :as cache]
            [seon.test :as sut]
            [seon.test.runner :as runner]
            [seon.test-runner-failure-fixture :as failure-fixture]
            [seon.id :as id]
            [seon.db :as db]
            [datahike.api :as d]
            [seon.fn :as functions]
            [seon.config :as config]
            [seon.program :as program]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as support])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- fixture-symbol
  {:malli/schema [:=> [:cat :string] :qualified-symbol]}
  [name]
  (symbol "selection.fixture" name))

(defn- selection-program-rows
  {:malli/schema [:=> [:cat :seon.db/database-value :string] :seon.program/rows]}
  [database source]
  (let [projection (db/carried-projection database)]
    (functions/source-rows
     database (program/shapes-in projection)
     (db/pull database '[*] [:seon.ns/name 'selection.fixture]) source
     (set (keys (:seon.schema.projection/forms projection))))))

(defn- install-selection-program!
  {:malli/schema [:=> [:cat :seon.db/connection :string] :nil]}
  [connection source]
  (let [rows (selection-program-rows (db/db connection) source)]
    (support/transacted! connection rows)
    nil))

(defn- install-namespace!
  {:malli/schema [:=> [:cat :seon.db/connection :symbol] :nil]}
  [connection namespace-name]
  (support/transacted!
   connection
   [(program/declaration-row
     (db/carried-projection (db/db connection))
     {:seon.ns/name namespace-name
      :seon.ns/source (str "(ns " namespace-name ")")}
     :all :agent)])
  nil)

(defn- complete-selection-tx
  "Mark synthetic terminal evidence only on this admission's owned members."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.test.run/admission]
                  :seon.store/transaction-data]}
  [database admission]
  (let [complete-member #(assoc %
                               :seon.test.member/completed-tx "datomic.tx"
                               :seon.test.member/terminated-tx "datomic.tx"
                               :seon.test.member/began? true :seon.test.member/ended? true
                               :seon.test.member/pass-count 1 :seon.test.member/fail-count 0
                               :seon.test.member/error-count 0)
        owned (db/q '[:find [?member ...] :in $ ?run-id
                      :where [?run :seon.test.run/id ?run-id]
                             [?run :seon.test.run/members ?member]]
                    database (get-in admission [:seon.test.run/provenance :seon.test.run/id]))]
    (into (mapv (fn [row]
                  (cond-> row
                    (:seon.test.run/members row)
                    (update :seon.test.run/members #(mapv complete-member %))))
                (sut/admit-run database admission))
          (map (fn [member] (complete-member {:db/id member})))
          owned)))

(defn- complete-selection!
  "Establish terminal run evidence; no claim that the canonical suite executed here."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.test.selection/request] :seon.test.run/admission]}
  [connection request]
  (let [admission (sut/selection-admission (assoc request :seon.db/db (db/db connection)))]
    (support/transacted! connection [[:db.fn/call complete-selection-tx admission]])
    admission))

(deftest ^{:seon.test/long-ms 8000
           :seon.test/long
           "Measured armed fixture lifecycle: cluster/config seed, four declarations,
            four selections, six provenance calculations and validated writes.
            Three quiet probes: 6336, 5067, 4903 ms; 8 s covers the observed
            1433 ms first-run spread above the maximum (landing 2026-09-21)."}
  named-selection-reuses-green-members-by-reachable-content
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "named-selection")
     (install-namespace! connection 'selection.fixture)
     (install-selection-program!
      connection
      "(defn leaf [] 1)
       (defn stranger [] 2)
       (clojure.test/deftest direct (clojure.test/is (= 1 (leaf))))
       (clojure.test/deftest unrelated (clojure.test/is (= 2 (stranger))))")
     (let [database (db/db connection)
           seal (or (db/q '[:find ?digest . :where [_ :seon.source/digest ?digest]] database)
                    (apply str (repeat 64 "a")))
           _ (support/transacted! connection
               [{:seon.source/digest seal :seon.source/test-input-digest (apply str (repeat 64 "b"))}])
           request {:seon.db/db (db/db connection)
                    :seon.test.run/cluster [:seon.cluster/name "named-selection"]
                    :seon.test/namespaces #{'selection.fixture}}
           select! #(sut/select (assoc request :seon.db/db (db/db connection)))
           expected #{(fixture-symbol "direct") (fixture-symbol "unrelated")}
           initial (select!)
           _ (is (= expected (set (map :seon.test/sym (:seon.test.run/members initial))))
                 (pr-str initial))
           admitted (complete-selection! connection request)
           reused (select!)]
       (is (= [] (:seon.test.run/members reused)) (pr-str reused))
       (is (= expected (set (map :seon.test/sym (:seon.test.selection/unchanged reused)))))
       (is (every? #(and (:seon.test/unchanged %)
                        (= (get-in admitted [:seon.test.run/provenance :seon.test.run/basis-t])
                           (:seon.test.run/basis-t %))
                        (string? (:seon.test.run/program-digest %))
                        (string? (:seon.test.run/input-digest %)))
                   (:seon.test.selection/unchanged reused)))
       (install-selection-program! connection "(defn leaf [] 3)")
       (let [acquire runner/reach-digests
             requested (atom #{})
             changed (with-redefs [runner/reach-digests
                                  (fn [database symbols]
                                    (swap! requested into symbols)
                                    (acquire database symbols))]
                       (select!))]
         (is (not (@requested (fixture-symbol "direct")))
             "A test already reaching changed code cannot reuse green; do not derive its reach digest.")
         (is (= #{(fixture-symbol "direct")}
                (set (map :seon.test/sym (:seon.test.run/members changed)))) (pr-str changed))
         (is (= #{(fixture-symbol "unrelated")}
                (set (map :seon.test/sym (:seon.test.selection/unchanged changed))))))))))

(defn exercise-selection!
  "Canonical change history shared by the selector and both-host admission regression."
  {:malli/schema [:=> [:cat [:=> [:cat :seon.test.selection/request]
                             [:or :seon.test.selection/result :seon.error/value
                              :seon.db/invalid-read-error :seon.schema/missing-projection-error]]]
                  :seon.schema/value]}
  [select-request]
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "selection")
     (install-namespace! connection 'selection.fixture)
     (let [source "(defn leaf [] 1)
                   (defn right [] (leaf))
                   (defn middle [] (leaf) (right))
                   (defn stranger [] 2)
                   (clojure.test/deftest direct (clojure.test/is (= 1 (leaf))))
                   (clojure.test/deftest indirect (clojure.test/is (= 1 (middle))))
                   (clojure.test/deftest reference (clojure.test/is (fn? leaf)))
                   (clojure.test/deftest unrelated (clojure.test/is (= 2 (stranger))))"
           _ (install-selection-program! connection source)
           seal (or (db/q '[:find ?digest . :where [_ :seon.source/digest ?digest]] (db/db connection))
                    (apply str (repeat 64 "a")))
           inputs (apply str (repeat 64 "b"))
           _ (support/transacted! connection [{:seon.source/digest seal :seon.source/test-input-digest inputs}])
           request {:seon.db/db (db/db connection) :seon.test.run/cluster [:seon.cluster/name "selection"]}
           select! #(select-request (assoc request :seon.db/db (db/db connection)))
           symbols #(set (map :seon.test/sym (:seon.test.run/members %)))
           expected (set (map fixture-symbol ["direct" "indirect" "reference"]))
           first-selection (select!)]
       (is (seq (:seon.test.run/members first-selection)) (pr-str first-selection))
       (is (every? (symbols first-selection) (conj expected (fixture-symbol "unrelated"))))
       (is (= :seon.test/cluster-required
              (:seon.test/selection-refusal (select-request (dissoc request :seon.test.run/cluster)))))
       (is (= :seon.test/cluster-unavailable
              (:seon.test/selection-refusal (select-request (assoc request :seon.test.run/cluster [:seon.cluster/name "missing"])))))
       (is (= :seon.test/invalid-basis
              (:seon.test/selection-refusal (select-request (assoc request :seon.test.run/change-basis-t
                                                  (inc (db/basis-t (db/db connection))))))))
       (complete-selection! connection request)
       (is (= #{} (symbols (select!))) "A green bare rerun selects zero, including platform.")
       (is (= (symbols first-selection)
              (set (map :seon.test/sym (:seon.test.selection/unchanged (select!)))))
           "A bare request reports the matching recorded members with their confidence.")
       (complete-selection! connection request)
       (is (= (symbols first-selection)
              (set (map :seon.test/sym (:seon.test.selection/unchanged (select!)))))
           "A zero-member admission does not erase earlier recorded green evidence.")
       (doseq [policy [:named :all :platform]]
         (let [selection (select-request
                          (cond-> (assoc request :seon.db/db (db/db connection)
                                                 :seon.test.run/policy policy)
                            (= :named policy) (assoc :seon.test/identities #{(fixture-symbol "direct")})))
               reused (:seon.test.selection/unchanged selection)]
           (is (empty? (:seon.test.run/members selection)) (pr-str selection))
           (is (seq reused) (pr-str selection))
           (is (every? #(and (:seon.test/unchanged %)
                            (integer? (:seon.test.run/basis-t %))
                            (string? (:seon.test.run/program-digest %))
                            (string? (:seon.test.run/input-digest %))) reused))))
       (let [database (db/db connection)
             definitions (sort (db/q '[:find [?symbol ...]
                                       :where [_ :seon.fn/sym ?symbol]] database))]
         (doseq [n [1 10 100]]
           (let [snapshot (:db-after (d/with database
                                      (mapv (fn [symbol]
                                              [:db/add [:seon.fn/sym symbol] :seon.fn/spec
                                               "[:=> [:cat] :boolean]"])
                                            (take n definitions))))
                 started (System/nanoTime)
                 selected (sut/select (assoc request :seon.db/db snapshot))]
             (is (vector? (:seon.test.run/members selected)) (pr-str selected))
             (println "A1 selection measurement"
                      {:seeds n :elapsed-ms (/ (double (- (System/nanoTime) started)) 1e6)
                       :members (count (:seon.test.run/members selected))}))))
       (let [full (assoc request :seon.test.run/policy :full)]
         (complete-selection! connection full)
         (let [selection (select-request (assoc full :seon.db/db (db/db connection)))]
           (is (empty? (:seon.test.run/members selection)) (pr-str selection))
           (is (seq (:seon.test.selection/unchanged selection)))))
       (complete-selection! connection (assoc request :seon.test/identities #{(fixture-symbol "unrelated")}))
       (install-selection-program! connection (str/replace source "leaf [] 1" "leaf [] 3"))
       (let [changed (select!)]
         (is (= expected (symbols changed)) (pr-str changed))
         (is (= expected (set (sut/reaching {:seon.db/db (db/db connection)
                                             :seon.test/changed [(fixture-symbol "leaf")]}))))
         (is (every? #(get-in % [:seon.test.member/reasons]) (:seon.test.run/members changed)))
         (let [query db/q
               thread (Thread/currentThread)
               injected (atom 0)
               refusal (assoc {:seon.error/at (java.util.Date.)
                                  :seon.error/layer :seon.db/read
                                  :seon.error/operation 'seon.db/q
                                  :seon.error/message "Declared-reference read refused."
                                  :seon.error/expected :available-read
                                  :seon.error/offending :refused
                                  :seon.error/data {:seon.error/layer :database-read :seon.error/member :declared-reference}} :seon.db/invalid-read true)]
           (with-redefs [db/q (fn [& arguments]
                               (if (and (identical? thread (Thread/currentThread))
                                        (some #{'(declared-edge ?caller ?target)}
                                              (first arguments)))
                                 (do (swap! injected inc) refusal)
                                 (apply query arguments)))]
             (is (= refusal (select!)) "A refused declared-reference read refuses selection.")
             (is (pos? @injected) "The owning declared-edge query was exercised.")))
         (let [admission (sut/selection-admission (assoc request :seon.db/db (db/db connection)))]
           (support/transacted! connection [[:db.fn/call sut/admit-run admission]])
           (is (= (symbols changed) (symbols (select!))) "An open admission does not discharge obligations.")
           (support/transacted! connection [[:db.fn/call complete-selection-tx admission]])))
       (is (empty? (:seon.test.run/members (select!))))
       (testing "Spec and reference edits seed their owning definition"
         (install-selection-program!
          connection
          "(defn leaf {:malli/schema [:=> [:cat] :int]} [] 3)")
         (is (= expected (symbols (select!)))
             "Unchanged green platform members remain discharged after a spec edit.")
         (complete-selection! connection request)
         (install-selection-program! connection "(defn stranger [] leaf 2)")
         (is (= #{(fixture-symbol "unrelated")} (symbols (select!)))
             "A reference edit executes only members whose reachable content changed.")
         (complete-selection! connection request))
       (testing "Historical symbols survive imported deletion and recreation"
         (let [database (db/db connection)
               leaf (fixture-symbol "leaf")
               original (:db/id (db/pull database [:db/id] [:seon.fn/sym leaf]))
               removed (:db-after (d/with database [[:db/retractEntity original]]))
               leaf-row (some #(when (= leaf (:seon.fn/sym %)) %)
                              (selection-program-rows removed "(defn leaf [] 9)"))
               recreated (:db-after (d/with removed [leaf-row]))]
           (is (not= original (:db/id (db/pull recreated [:db/id] [:seon.fn/sym leaf]))))
           (doseq [snapshot [removed recreated]]
             (is (= (conj expected (fixture-symbol "unrelated"))
                    (symbols (select-request (assoc request :seon.db/db snapshot))))))))
       (testing "Explicit fixture material refuses"
         (is (= :seon.test/fixture-excluded
                (:seon.test/selection-refusal
                 (select-request (assoc request :seon.db/db (db/db connection)
                                       :seon.test/identities #{'seon.test-runner-failure-fixture/failing-example}))))))
       (support/transacted! connection [{:seon.source/digest seal
                                         :seon.source/test-input-digest (apply str (repeat 64 "c"))}])
       (let [invalidated (select!)]
         (is (= (symbols first-selection) (symbols invalidated)))
         (is (every? #((:seon.test.member/reasons %) :first-run)
                     (:seon.test.run/members invalidated))))
       (complete-selection! connection request)
       (support/transacted! connection [{:seon.fn.file/relative-path "src/selection-removed.clj"
                                         :seon.fn.file/digest seal}])
       (support/transacted! connection [[:db/retractEntity [:seon.fn.file/relative-path "src/selection-removed.clj"]]])
       (let [removed (select!)]
         (is (= [:removed-file] (:seon.test.selection/widenings removed)))
         (is (empty? (symbols removed))
             "A create-then-delete leaves the same program; widening eligibility reuses its green evidence.")
         (is (= (symbols first-selection)
                (set (map :seon.test/sym (:seon.test.selection/unchanged removed))))))
       (let [database (db/db connection)
             source-eid (:db/id (db/pull database [:db/id] [:seon.source/digest seal]))
             missing-inputs (:db-after (d/with database [[:db/retract source-eid :seon.source/test-input-digest]]))
             test-eid (:db/id (db/pull database [:db/id] [:seon.test/sym (fixture-symbol "direct")]))
             missing-analysis (:db-after (d/with database [[:db/retract test-eid :seon.program/analyzed-source-digest]]))
             member-eid (db/q '[:find ?member . :where
                                [_ :seon.source/test-input-digest ?inputs]
                                [?run :seon.test.run/input-digest ?inputs]
                                [?run :seon.test.run/policy :incremental]
                                [?run :seon.test.run/members ?member]] database)
             missing-member (:db-after (d/with database [[:db/retractEntity member-eid]]))]
         (is (= :seon.test/population-unknown
                (:seon.test/selection-refusal (select-request (assoc request :seon.db/db missing-member)))))
         (is (= :seon.test/input-evidence-unavailable
                (:seon.test/selection-refusal (select-request (assoc request :seon.db/db missing-inputs)))))
         (is (= :seon.test/analysis-unknown
                (:seon.test/selection-refusal (select-request (assoc request :seon.db/db missing-analysis))))))))))

(deftest ^{:seon.test/long "Canonical complete-population selection and recorded reach across multiple transaction bases."
           :seon.test/long-ms 900000}
  selection-derives-bases-obligations-and-exact-symbol-reach
  (exercise-selection! sut/select))

(deftest definition-content-agrees-across-exploratory-branches
  (support/with-database
   (fn [left]
     (support/with-database
      (fn [right]
        (install-namespace! left 'selection.fixture)
        (install-namespace! right 'selection.offset)
        (install-namespace! right 'selection.fixture)
        (let [left-basis (db/basis-t (db/db left))
              right-basis (db/basis-t (db/db right))
              target (fixture-symbol "branch-leaf")
              content #(get (#'sut/definition-digests (db/db %) [target]) target)]
          (install-selection-program! left "(defn branch-leaf [] 1)")
          (install-selection-program! right "\n(defn branch-leaf [] 1)\n")
          (is (not= (get-in (db/db left) [:config :branch])
                    (get-in (db/db right) [:config :branch])))
          (is (not= (:db/id (db/pull (db/db left) [:db/id] [:seon.fn/sym target]))
                    (:db/id (db/pull (db/db right) [:db/id] [:seon.fn/sym target]))))
          (is (string? (content left)))
          (is (not= (:seon.program/analyzed-source-digest
                     (db/pull (db/db left) [:seon.program/analyzed-source-digest] [:seon.fn/sym target]))
                    (:seon.program/analyzed-source-digest
                     (db/pull (db/db right) [:seon.program/analyzed-source-digest] [:seon.fn/sym target]))))
          (is (= (content left) (content right)))
          (is (= #{target} (#'sut/changed-definition-symbols (db/db left) left-basis)
                          (#'sut/changed-definition-symbols (db/db right) right-basis)))
          (let [basis (db/basis-t (db/db right))]
            (install-selection-program! right "(defn branch-leaf [] 2)")
            (is (not= (content left) (content right)))
            (is (= #{target} (#'sut/changed-definition-symbols (db/db right) basis))))))))))

(deftest fileless-sci-tests-use-the-same-selection
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "fileless-selection")
     (let [seal (or (db/q '[:find ?digest . :where [_ :seon.source/digest ?digest]] (db/db connection))
                    (apply str (repeat 64 "a")))
           _ (support/transacted! connection [{:seon.source/digest seal
                                               :seon.source/test-input-digest (apply str (repeat 64 "b"))}])
           ctx (support/fork-cluster-ctx connection)
           evaluation (sci.eval/evaluate
                       {:seon.sci.eval/ctx ctx
                        :seon.cluster.eval/source
                        "(clojure.test/deftest fileless-selection (clojure.test/is (string? (seon.id/id))))"
                        :seon.cluster.eval/ns [:seon.ns/name 'seon.test.selection-test]
                        :seon.sci.admit/caps (config/result-caps config/defaults)
                        :seon.sci.eval/time-limit-ms 10000
                        :seon.config/on-core-error :panic})
           _ (when-not (:seon.program/row evaluation)
               (throw (ex-info "SCI test declaration did not produce a program row." evaluation)))
           declaration (program/declaration-row (seon.schema/handed-projection) (:seon.program/row evaluation) :all :agent)]
       (is (nil? (:seon.cluster.eval/error evaluation)))
       (support/transacted! connection [declaration])
       (is (not (:seon.fn/file declaration)))
       (let [request {:seon.db/db (db/db connection)
                      :seon.test.run/cluster [:seon.cluster/name "fileless-selection"]
                      :seon.test/identities #{(symbol "seon.test.selection-test" "fileless-selection")}}
             result (sut/selection-admission request)]
         (is (some #(= (symbol "seon.test.selection-test" "fileless-selection") (:seon.test.member/symbol %))
                   (:seon.test.run/members result)) (pr-str result))
         (is (some #{(symbol "seon.test.selection-test" "fileless-selection")}
                   (sut/reaching {:seon.db/db (db/db connection) :seon.test/changed ['seon.id/id]}))))))))

(deftest gate-inputs-no-call-edge-can-reach-widen
  (is (cache/widening-path? "resources/seon/schemas/seon.db.edn"))
  (is (cache/widening-path? "deps.edn"))
  (is (cache/widening-path? "bin/test"))
  (is (cache/widening-path? "config/default.edn"))
  (is (not (cache/widening-path? "src/seon/db.clj")))
  (is (not (cache/widening-path? "test/seon/db_test.clj")))
  ;; Inputs are DECLARED (deps.edn roots and local/root dependencies, config,
  ;; the manifest, the launchers); a path that is none of those is not an
  ;; input and never widens a gate, however it is spelled (2026-09-19).
  (is (not (cache/widening-path? "resources-of-mine.edn")))
  (is (not (cache/widening-path? "new-gate-input/custom.edn")))
  (is (not (cache/widening-path? "src-other/example.clj")))
  (is (cache/widening-path? "reference-code/datahike"))
  (is (cache/widening-path? "reference-code/datahike/src/datahike/api.cljc"))
  (doseq [root cache/graph-roots]
    (is (not (cache/input-path? (cache/input-roots ".") root)))
    (is (not (cache/widening-path? root)))
    (is (cache/widening-path? (str root "/fixtures/input.txt")))
    (doseq [extension [".clj" ".cljc" ".edn"]]
      (is (not (cache/widening-path? (str root "/example" extension)))))))

(deftest documentation-only-input-changes-do-not-refuse-published-selection
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "documentation-selection")
     (install-namespace! connection 'selection.fixture)
     (install-selection-program!
      connection
      "(clojure.test/deftest documentation-safe (clojure.test/is true))")
     (let [base-inventory {"deps.edn" (apply str (repeat 64 "a"))
                           "docs/base-note.md" (apply str (repeat 64 "b"))}
           head-inventory (assoc base-inventory "docs/base-note.md"
                                 (apply str (repeat 64 "c")))
           published (cache/external-input-digests "." base-inventory)
           requested (cache/external-input-digests "." head-inventory)
           input-digest (cache/input-evidence-digest published)
           database (db/db connection)
           source-digest (db/q '[:find ?digest . :where [_ :seon.source/digest ?digest]] database)
           _ (support/transacted!
              connection
              [{:seon.source/digest source-digest
                :seon.source/test-input-digest input-digest}])
           database (db/db connection)
           selection
           (sut/select
            {:seon.db/db database
             :seon.test.run/input-digest (cache/input-evidence-digest requested)
             :seon.test.selection/input-evidence
             {:seon.test.selection/published-inputs published
              :seon.test.selection/requested-inputs requested}
             :seon.test.run/policy :incremental
             :seon.test.run/members []
             :seon.test.run/provenance
             {:seon.test.run/id (id/id)
              :seon.test.run/at (java.util.Date.)
              :seon.test.run/program-digest (runner/program-digest database)
              :seon.test.run/published-base-digest (apply str (repeat 64 "d"))
              :seon.test.run/overlay-input-digest (apply str (repeat 64 "e"))
              :seon.test.run/basis-t (db/basis-t database)
              :seon.test.run/branch :current-src}})]
       (is (= published requested))
       (is (not (:seon.error/at selection)) (pr-str selection))
       (is (contains? (set (map :seon.test/sym (:seon.test.run/members selection)))
                      (fixture-symbol "documentation-safe")))))))

(deftest changed-inputs-are-decided-by-content-not-modification-time
  (let [root (.toFile (Files/createTempDirectory
                       (.toPath (io/file "tmp")) "selection-test"
                       (into-array FileAttribute [])))
        source (io/file root "src" "example")
        _ (.mkdirs source)
        file (io/file source "leaf.clj")]
    (try
      (process/check (process/shell {:dir (.getPath root) :out :string :err :string}
                                    "git" "init"))
      (spit file "(ns example.leaf)\n")
      (let [first-pass (cache/input-digests (.getPath root))]
        (is (contains? first-pass "src/example/leaf.clj"))

        (testing "rewriting identical bytes with a newer timestamp is no change"
          (spit file "(ns example.leaf)\n")
          (.setLastModified file (+ (System/currentTimeMillis) 60000))
          (is (= {:seon.test.cache/changed []
                  :seon.test.cache/removed []}
                 (cache/changed-inputs
                  first-pass (cache/input-digests (.getPath root))))))

        (testing "different bytes are exactly one changed path"
          (spit file "(ns example.leaf)\n(defn leaf [] 1)\n")
          (is (= {:seon.test.cache/changed ["src/example/leaf.clj"]
                  :seon.test.cache/removed []}
                 (cache/changed-inputs
                  first-pass (cache/input-digests (.getPath root))))))

        (testing "a deleted input is reported as removed"
          (.delete file)
          (is (= ["src/example/leaf.clj"]
                 (:seon.test.cache/removed
                  (cache/changed-inputs
                   first-pass (cache/input-digests (.getPath root))))))))
      (finally
        ((requiring-resolve 'seon.fs/delete-recursively!)
         (.getCanonicalPath (io/file "tmp"))
         (.getCanonicalPath root))))))

(deftest a-symlinked-input-is-digested-through-the-link-never-traversed
  (testing "THE CLASS: bin/test copies first-party directories into its
            isolated run root but SYMLINKS top-level files. A digest walk
            that skips symlinks reports deps.edn as removed on every run and
            silently widens the tier forever; a walk that follows symlinked
            DIRECTORIES escapes the root it was given."
    (let [root (.toFile (Files/createTempDirectory
                         (.toPath (io/file "tmp")) "selection-links"
                         (into-array FileAttribute [])))
          outside (io/file root "outside")
          _ (.mkdirs (io/file outside "nested"))
          _ (spit (io/file outside "nested" "escaped.clj") "(ns escaped)\n")
          _ (spit (io/file outside "target.edn") "{:a 1}\n")
          checkout (io/file root "checkout")
          _ (.mkdirs (io/file checkout "src"))]
      (try
        (process/check (process/shell {:dir (.getPath checkout) :out :string :err :string}
                                      "git" "init"))
        (spit (io/file checkout "src" "real.clj") "(ns real)\n")
        (Files/createSymbolicLink
         (.toPath (io/file checkout "deps.edn"))
         (.toPath (.getCanonicalFile (io/file outside "target.edn")))
         (into-array FileAttribute []))
        (Files/createSymbolicLink
         (.toPath (io/file checkout "config"))
         (.toPath (.getCanonicalFile outside))
         (into-array FileAttribute []))
        (let [digests (cache/input-digests (.getPath checkout))]
          (is (contains? digests "deps.edn")
              "a symlinked file's content is the basis input")
          (is (contains? digests "src/real.clj"))
          (is (not-any? #(str/includes? % "escaped") (keys digests))
              "a symlinked directory is never descended"))
        (finally
          ((requiring-resolve 'seon.fs/delete-recursively!)
           (.getCanonicalPath (io/file "tmp"))
           (.getCanonicalPath root)))))))

(deftest external-input-identity-includes-gitlinks-but-not-program-edits
  (let [root (.toFile (Files/createTempDirectory (.toPath (io/file "tmp"))
                                                "selection-inputs" (into-array FileAttribute [])))
        git! (fn [& args] (process/check (apply process/shell
                                                 {:dir (str root) :out :string :err :string}
                                                 "git" args)))
        digest #(cache/test-input-digest (str root) (cache/input-digests (str root)))]
    (try
      (git! "init")
      (.mkdirs (io/file root "src"))
      (spit (io/file root "src/example.clj") "(ns example)")
      (spit (io/file root "deps.edn") "{}")
      (git! "update-index" "--add" "--cacheinfo"
            (str "160000," (apply str (repeat 40 "a")) ",reference-code/example"))
      (let [before (digest)]
        (spit (io/file root "src/example.clj") "(ns example) (def x 1)")
        (is (= before (digest)))
        (git! "update-index" "--cacheinfo"
              (str "160000," (apply str (repeat 40 "b")) ",reference-code/example"))
        (is (not= before (digest)) "A gitlink changes evidence without traversing its directory.")
        (let [pinned (digest)]
          (spit (io/file root "deps.edn") "{:paths []}")
          (is (not= pinned (digest)))
          (let [changed (digest)]
            (.delete (io/file root "deps.edn"))
            (is (not= changed (digest))))))
      (finally ((requiring-resolve 'seon.fs/delete-recursively!)
                (.getCanonicalPath (io/file "tmp")) (.getCanonicalPath root))))))
