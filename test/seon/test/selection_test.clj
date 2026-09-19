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
  (:require [babashka.process :as process]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.test.selection :as selection]
            [seon.test.cache :as cache]
            [seon.test :as sut]
            [seon.db :as db]
            [seon.error :as error]
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

(defn- install-selection-program!
  {:malli/schema [:=> [:cat :seon.db/connection :string] :nil]}
  [connection source]
  (let [database (db/db connection)
        rows (functions/source-rows database
               (program/shapes-in (:seon.schema.projection/forms (db/carried-projection database)))
               {:seon.ns/name 'selection.fixture} source
               (set (keys (:seon.schema.projection/forms (db/carried-projection database)))))]
    (support/transacted! connection rows)
    nil))

(defn- complete-selection!
  "Establish terminal run evidence; no claim that the canonical suite executed here."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.test.selection/request] :seon.test.run/admission]}
  [connection request]
  (let [admission (sut/selection-admission (assoc request :seon.db/db (db/db connection)))]
    (support/transacted! connection [[:db.fn/call sut/admit-run admission]])
    (let [ids (db/q '[:find [?member ...] :in $ ?id
                      :where [?run :seon.test.run/id ?id]
                             (or [?run :seon.test.run/members ?member]
                                 [?run :seon.test.run/covered-by ?member])]
                    (db/db connection) (get-in admission [:seon.test.run/provenance :seon.test.run/id]))]
      (when (seq ids)
        (support/transacted! connection
          (mapv (fn [eid] {:db/id eid
                           :seon.test.member/completed-tx "datomic.tx"
                           :seon.test.member/terminated-tx "datomic.tx"
                           :seon.test.member/began? true :seon.test.member/ended? true
                           :seon.test.member/pass-count 1 :seon.test.member/fail-count 0
                           :seon.test.member/error-count 0}) ids))))
    admission))

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
     (support/transacted! connection [{:seon.ns/name 'selection.fixture}])
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
           first-selection (select!)
           platform (into #{} (keep #(when ((:seon.test.member/reasons %) :platform) (:seon.test/sym %)))
                          (:seon.test.run/members first-selection))]
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
         (is (= (into expected platform) (symbols changed)) (pr-str changed))
         (is (= expected (set (sut/reaching {:seon.db/db (db/db connection)
                                             :seon.test/changed [(fixture-symbol "leaf")]}))))
         (is (every? #(get-in % [:seon.test.member/reasons]) (:seon.test.run/members changed)))
         (let [query db/q
               thread (Thread/currentThread)
               refusal (assoc (error/diagnostic
                                 {:seon.error/at (java.util.Date.) :seon.error/layer :seon.db/read
                                  :seon.error/operation 'seon.db/q
                                  :seon.error/message "Declared-reference read refused."
                                  :seon.error/diagnostic-layer :database-read
                                  :seon.error/diagnostic-operation 'seon.db/q
                                  :seon.error/diagnostic-member :declared-reference
                                  :seon.error/diagnostic-expected :available-read
                                  :seon.error/diagnostic-offending :refused
                                  :seon.error/diagnostic-cause :seon.db/invalid-read
                                  :seon.error/diagnostic-evidence {}}) :seon.db/invalid-read true)]
           (with-redefs [db/q (fn [& arguments]
                               (if (and (identical? thread (Thread/currentThread))
                                        (= @#'functions/declared-reference-rules (last arguments)))
                                 refusal (apply query arguments)))]
             (is (= refusal (select!)) "A refused declared-reference read refuses selection.")))
         (let [admission (sut/selection-admission (assoc request :seon.db/db (db/db connection)))]
           (support/transacted! connection [[:db.fn/call sut/admit-run admission]])
           (is (= (symbols changed) (symbols (select!))) "An open admission does not discharge obligations.")))
       (complete-selection! connection request)
       (is (empty? (:seon.test.run/members (select!))))
       (testing "Spec and reference edits seed their owning definition"
         (support/transacted! connection [{:seon.fn/sym (fixture-symbol "leaf")
                                           :seon.fn/spec "[:=> [:cat] :int]"}])
         (is (= (into expected platform) (symbols (select!))))
         (complete-selection! connection request)
         (support/transacted! connection [{:seon.fn/sym (fixture-symbol "stranger")
                                           :seon.fn/references #{(fixture-symbol "leaf")}}])
         (is (= (conj platform (fixture-symbol "unrelated")) (symbols (select!))))
         (complete-selection! connection request))
       (testing "Historical symbols survive imported deletion and recreation"
         (let [database (db/db connection)
               leaf (fixture-symbol "leaf")
               original (:db/id (db/pull database [:db/id] [:seon.fn/sym leaf]))
               removed (:db-after (d/with database [[:db/retractEntity original]]))
               recreated (:db-after (d/with removed [(support/program-fn-row removed leaf "(defn leaf [] 9)")]))]
           (is (not= original (:db/id (db/pull recreated [:db/id] [:seon.fn/sym leaf]))))
           (doseq [snapshot [removed recreated]]
             (is (= (into (conj expected (fixture-symbol "unrelated")) platform)
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

(deftest selection-derives-bases-obligations-and-exact-symbol-reach
  (exercise-selection! sut/select))

(deftest definition-content-agrees-across-exploratory-branches
  (support/with-database
   (fn [left]
     (support/with-database
      (fn [right]
        (support/transacted! left [{:seon.ns/name 'selection.fixture}])
        (support/transacted! right [{:seon.ns/name 'selection.offset}
                                   {:seon.ns/name 'selection.fixture}])
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
                        :seon.sci.admit/caps (config/result-caps (config/defaults))
                        :seon.sci.eval/time-limit-ms 10000
                        :seon.config/on-core-error :panic})
           declaration (program/declaration-row (:seon.program/row evaluation) :all :agent)]
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
    (is (not (cache/widening-path? root)))
    (is (not (cache/widening-path? (str root "/example.clj"))))))

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

(deftest a-recorded-green-basis-round-trips
  (let [root (.toFile (Files/createTempDirectory
                       (.toPath (io/file "tmp")) "selection-basis"
                       (into-array FileAttribute [])))]
    (try
      (is (nil? (selection/read-basis (.getPath root)))
          "no basis is an honest nil, never an empty map that reads as green")
      (selection/write-basis! (.getPath root)
                              {:seon.test.basis/at "2026-08-07T00:00:00Z"
                               :seon.test.basis/mode "all"
                               :seon.test.basis/digests {"src/a.clj" "abc"}})
      (is (= {:seon.test.basis/at "2026-08-07T00:00:00Z"
              :seon.test.basis/mode "all"
              :seon.test.basis/digests {"src/a.clj" "abc"}}
             (selection/read-basis (.getPath root))))
      (doseq [corrupt ["{" "nil" "[]" "{}"
                       "{:seon.test.basis/digests {\"src/a.clj\" nil}}"]]
        (spit (io/file root "tmp/test-basis/green-basis.edn") corrupt)
        (let [refusal (try (selection/read-basis (.getPath root))
                           (catch clojure.lang.ExceptionInfo failure
                             (ex-data failure)))]
          (is (= ::selection/invalid-basis (:seon.error/kind refusal)))
          (is (string? (get-in refusal [:seon.error/data ::selection/cause])))))
      (finally
        ((requiring-resolve 'seon.fs/delete-recursively!)
         (.getCanonicalPath (io/file "tmp"))
         (.getCanonicalPath root))))))

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
