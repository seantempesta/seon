(ns seon.issue.task-ingestion-test
  "Task ingestion (spec 7.9-7.16): handle-valued edges, task EDN by handle with a
  revision basis, lookups both ways, and the publication seam's dangling refusal."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.cluster.source]
            [seon.db :as db]
            [seon.fn]
            [seon.issue :as issue]
            [seon.program :as program]
            [seon.test-support :as test-support]))

(def ^:private handle "043df611d179")

(def ^:private digest
  "A source digest the publication seam's contract admits."
  (apply str (repeat 64 "d")))

(def ^:private subject-source
  (str "(ns tq.a {:seon/tasks #{\"043df611d179\"} :seon/must-read ['tq.a/f]}\n"
       "  (:require [clojure.test :refer [deftest is]] [clojure.string :as s]))\n"
       "(defn f {:seon/tasks #{\"043df611d179\"} :seon/must-read ['tq.a/g] :seon/see-also [`s/join]} [v] v)\n"
       "(defn g {:seon/tasks #{\"043df611d179\"}} [v] (:seon/tasks {:seon/tasks #{::x}}))\n"
       "(deftest ^{:seon/tasks #{\"043df611d179\"}} proves (is (= 1 (f 1))) (is (= #{} (:seon/tasks {:seon/tasks #{}}))))\n"))

(def ^:private task-path "resources/seon/tasks/tq.a.edn")

(defn- task-text
  {:malli/schema [:=> [:cat :string :string] :string]}
  [intent members]
  (str "[#:seon.task{:id \"043df611d179\" :name \"prove-ingestion\" :kind :function :intent \"" intent "\" " members "}]"))

(defn- with-root
  "Call `body` with a fresh fixture directory holding `files` (relative path to text); delete it after."
  {:malli/schema [:=> [:cat [:map-of :string :string] [:fn clojure.core/ifn?]] :seon.schema/value]}
  [files body]
  (let [root (io/file "tmp" "task-ingestion-test" (str (random-uuid)))]
    (try (doseq [[path text] files] (io/make-parents (io/file root path)) (spit (io/file root path) text))
         (body root)
         (finally (test-support/delete-recursively! root)))))

(defn- entities-of
  {:malli/schema [:=> [:cat :string] :seon.program/rows]}
  [source]
  (with-root {"tq/a.clj" source} #(seon.fn/program-entities {:seon.fn/roots [(.getPath %)]})))

(defn- refusal
  "The task rule a returned or thrown refusal names."
  {:malli/schema [:=> [:cat [:fn clojure.core/ifn?]] [:maybe :seon.task/refused]]}
  [thunk]
  (try (:seon.task/refused (thunk))
       (catch clojure.lang.ExceptionInfo e (some #(:seon.task/refused (ex-data %)) (take-while some? (iterate ex-cause e))))))

(defn- admit!
  {:malli/schema [:=> [:cat :seon.db/connection :seon.program/rows] :seon.schema/value]}
  [connection entities]
  (test-support/transacted! connection (seon.fn/reconcile-tx @connection entities [])))

(defn- ingest!
  {:malli/schema [:=> [:cat :seon.db/connection :string [:* :seon.task/basis]] :seon.schema/value]}
  [connection text & [basis]]
  (issue/index-tasks! (cond-> {:seon.db/connection connection :seon.task/files {task-path text}}
                        basis (assoc :seon.task/basis basis))))

(deftest ingested-tasks-answer-both-ways-and-unchanged-input-writes-nothing
  (test-support/with-database
   (fn [connection]
     (let [entities (entities-of subject-source) text (task-text "Prove ingestion." ":must-read #{tq.a/g} :evidence #{}")]
       (admit! connection entities)
       (is (:seon.task/changed? (ingest! connection text)))
       (is (= #:seon.task{:subjects '#{tq.a tq.a/f tq.a/g} :proofs '#{tq.a/proves} :referrers #{'tq.a 'tq.a/f handle}}
              (issue/task-edges @connection handle)))
       (is (= '#{clojure.string/join} (set (:seon.fn/see-also (db/pull @connection [:seon.fn/see-also] '[:seon.fn/sym tq.a/f])))))
       (is (= #{handle} (issue/open-tasks @connection '[:seon.fn/sym tq.a/g])))
       (is (not-any? #(contains? % handle) (vals (issue/task-findings @connection))))
       (testing "unchanged input, with an empty optional set, writes nothing"
         (is (= {:seon.task/changed? false} (ingest! connection text)))
         (is (empty? (seon.fn/reconcile-tx @connection entities []))))
       (testing "the same aspect with a new intent keeps its handle; clearing a set retracts it"
         (is (:seon.task/changed? (ingest! connection (task-text "Changed." ""))))
         (is (= {:seon.task/intent "Changed."}
                (db/pull @connection [:seon.task/intent :seon.task/must-read] [:seon.task/id handle]))))
       (testing "removing one annotation drops only that edge"
         (admit! connection (entities-of (str/replace subject-source "(defn g {:seon/tasks #{\"043df611d179\"}}" "(defn g")))
         (is (= '#{tq.a tq.a/f} (:seon.task/subjects (issue/task-edges @connection handle)))))
       (testing "program attributes carry the handle edges, never task entities"
         (let [attributes (program/program-attributes (db/carried-projection @connection))]
           (is (contains? attributes :seon.task/tasks))
           (is (not-any? attributes [:seon.task/id :seon.task/intent]))))
       (is (:seon.error/at (issue/open-tasks @connection '[:seon.no-such/attribute x])) "a refused read returns whole")))))

(deftest two-edits-from-one-basis-the-second-refuses-and-the-winner-stays
  (test-support/with-database
   (fn [connection]
     (let [basis (issue/task-basis @connection {task-path "[]"})]
       (is (:seon.task/changed? (ingest! connection (task-text "First." "") basis)))
       (is (= :revision-conflict (refusal #(ingest! connection (task-text "Second." "") basis))))
       (is (= "First." (:seon.task/intent (db/pull @connection [:seon.task/intent] [:seon.task/id handle]))))))))

(deftest malformed-task-files-refuse-by-name-without-writing
  (test-support/with-database
   (fn [connection]
     (let [before (db/basis-t @connection)]
       (doseq [[rule text] {:not-one-vector "[] [#:seon.task{:name \"x\" :kind :function :intent \"i\"}]"
                            :unreadable-file "[{"
                            :invalid-entry "[#:seon.task{:name \"x\" :kind :function :intnet \"i\"}]"
                            :handle-mismatch "[#:seon.task{:id \"000000000000\" :name \"x\" :kind :function :intent \"i\"}]"
                            :duplicate-id "[#:seon.task{:name \"x\" :kind :function :intent \"i\"} #:seon.task{:name \"x\" :kind :function :intent \"j\"}]"}]
         (is (= rule (refusal #(ingest! connection text)))))
       (testing "only #{} at an optional set member is absence; other empty values meet the closed entry"
         (doseq [members [":unknown []" ":must-read []" ":evidence {}" ":id []" ":id \"\""]]
           (let [text (str "[#:seon.task{:name \"x\" :kind :function :intent \"i\" " members "}]")
                 refused (ingest! connection text)]
             (is (= :invalid-entry (:seon.task/refused refused)))
             (is (= (edn/read-string text) (:seon.error/offending refused)) "the diagnostic keeps the authored entry"))))
       (is (= :unreadable-file (refusal #(ingest! connection "#=(+ 1 2)"))))
       (is (= :not-one-vector (refusal #(ingest! connection "{}"))))
       (is (= :misplaced-file (refusal #(issue/index-tasks! {:seon.db/connection connection :seon.task/files {"tmp/tq.a.edn" "[]"}}))))
       (is (= before (db/basis-t @connection)) "no refusal writes")))))

(deftest annotation-placement-is-positional-and-body-usage-stays-legal
  (let [head "(ns tq.a (:require [clojure.test :refer [deftest is]] [tq.b :as-alias b]))\n"]
    (is (= :metadata-after-test-name (refusal #(entities-of (str head "(deftest t {:seon/tasks #{\"x\"}} (is true))\n")))))
    (is (nil? (refusal #(entities-of (str head "(deftest t (is (:seon/tasks {:seon/tasks #{::b/x}})))\n")))))
    (is (= :malformed-annotation (refusal #(entities-of (str head "(defn f {:seon/tasks #{::b/x}} [v] v)\n")))))
    (is (= :malformed-annotation (refusal #(entities-of (str head "(defn ^:seon/tasks f [v] v)\n")))))
    (is (= :malformed-annotation (refusal #(entities-of (str head "(defn f {:seon/must-read [clojure.core/inc]} [v] v)\n")))))))

(deftest publication-ingests-tasks-and-refuses-a-dangling-handle
  ;; The destination is a branch of the live program, so the checkout's own task
  ;; files ride along: only this fixture's handle may dangle.
  (let [publish-tasks! #'seon.cluster.source/index-tasks!
        checkout (issue/task-files "." nil)
        dangling #(try (% ) nil (catch clojure.lang.ExceptionInfo e (keys (:seon.task/dangling (ex-data e)))))]
    (test-support/with-database
     (fn [connection]
       (admit! connection (entities-of subject-source))
       (is (= [handle] (with-root checkout (fn [root] (dangling #(publish-tasks! connection digest (.getPath root) nil)))))
           "an annotation whose task is absent at the destination refuses with its holders")
       (with-root (assoc checkout task-path (task-text "Prove ingestion." ""))
         (fn [root]
           (is (:seon.task/changed? (publish-tasks! connection digest (.getPath root) nil)) "coherent task and annotation admission")
           (is (= {:seon.task/changed? false} (publish-tasks! connection digest (.getPath root) nil)) "repeat is a no-op")
           (io/delete-file (io/file root task-path))
           (is (= [handle] (dangling #(publish-tasks! connection digest (.getPath root) nil)))
               "a removed task file retracts its task, leaving its annotations dangling")))))))

(deftest a-source-only-publication-admits-the-unchanged-task-file-its-annotations-name
  ;; Class regression (lane fix-task-gate-publication 2026-09-25): a publication
  ;; whose changed paths hold no task file, over a destination that recorded the
  ;; task file's digest but never admitted its tasks, refused its own annotations
  ;; as dangling. The gate reads the tasks of the same published source.
  (test-support/with-database
   (fn [connection]
     (admit! connection (entities-of subject-source))
     (with-root (assoc (issue/task-files "." nil) task-path (task-text "Prove ingestion." ""))
       (fn [root]
         (is (:seon.task/changed? (#'seon.cluster.source/index-tasks! connection digest (.getPath root) nil)))
         (is (not-any? #(contains? % handle) (vals (issue/task-findings @connection)))))))))

(deftest adoption-carries-published-tasks-keeps-the-destinations-own-and-refuses-a-competing-edit
  ;; P1-2 (review 5fb38b91f): the adoption compares against the destination value
  ;; it read, and only the source's history makes a missing task a deletion.
  (test-support/with-database
   (fn [source]
     (test-support/with-database
      (fn [destination]
        ;; The inner fixture holds destination's custody; the source's writes run under its own.
        (let [publish! #(is (:seon.task/changed? (db/call-with-custody {:seon.db/connection source} (fn [] (ingest! source %))))
                            "the source admits the edit")
              adopt! #(test-support/transacted! destination (issue/adopt-tasks-call @source @destination))
              intent #(:seon.task/intent (db/pull @destination [:seon.task/intent] [:seon.task/id %]))
              own (issue/task-handle 'tq.b "own" :function)]
          (is (= [] (issue/adopt-tasks-call @source @source)) "an agreeing cluster adopts nothing")
          (issue/index-tasks! {:seon.db/connection destination :seon.task/basis {}
                               :seon.task/files {"resources/seon/tasks/tq.b.edn" "[#:seon.task{:name \"own\" :kind :function :intent \"Mine.\"}]"}})
          (publish! (task-text "Prove ingestion." ""))
          (adopt!)
          (is (= ["Prove ingestion." "Mine."] (map intent [handle own])) "published arrives; a task the source never held stays")
          (is (= [] (issue/adopt-tasks-call @source @destination)) "repeat adopts nothing")
          (testing "a competing edit between the adoption's read and its transaction refuses; the competitor stays"
            (publish! (task-text "Revised." ""))
            (let [call (issue/adopt-tasks-call @source @destination)]
              (is (seq call) "the proposal carries the source's revision")
              (is (:seon.task/changed? (ingest! destination (task-text "Competing." ""))))
              (is (= :revision-conflict (refusal #(test-support/transacted! destination call))))
              (is (= ["Competing." "Mine."] (map intent [handle own])) "the refused adoption changes no task")))
          (testing "a published task the source deletes retracts; the destination's own stays"
            (publish! "[]")
            (adopt!)
            (is (= [nil "Mine."] (map intent [handle own]))))))))))

(deftest a-merged-program-dangles-by-holder-against-the-destinations-admitted-tasks
  ;; P1-3: the candidate's entities for the merged identities, every other holder's
  ;; from the destination, against the destination's tasks.
  (test-support/with-database
   (fn [connection]
     (let [head @connection
           merged #{[:seon.fn/sym 'tq.a/f] [:seon.test/sym 'tq.a/proves]}]
       (admit! connection (entities-of subject-source))
       (is (= {handle '#{tq.a/f tq.a/proves}} (issue/candidate-dangling @connection head merged)))
       (is (not (contains? (issue/candidate-dangling @connection head #{}) handle)) "unmerged holders are the destination's")
       (is (= {handle '#{tq.a tq.a/g}} (select-keys (issue/candidate-dangling head @connection merged) [handle]))
           "a merged entity without the annotation drops its edge; the unmerged holders keep theirs")
       (ingest! connection (task-text "Prove ingestion." ""))
       (is (= {} (select-keys (issue/candidate-dangling @connection @connection merged) [handle])))))))

(deftest the-checkouts-migrated-records-ingest-by-aspect-and-repeat-as-a-no-op
  ;; Slice 2 (spec 7.12, 7.15): the checkout's own task files are the migrated
  ;; records. Each handle is its aspect's; an entry moved to another namespace's
  ;; file names a different aspect, so its written handle refuses there.
  (test-support/with-database
   (fn [connection]
     (let [files (issue/task-files "." nil)
           basis (issue/task-basis @connection files)]
       (issue/index-tasks! {:seon.db/connection connection :seon.task/files files :seon.task/basis basis})
       (doseq [[id aspect] {"132fee0467c8" '[seon.sci.eval "definitions-bypass-entry-gate" :function]
                                "cea47b015b23" '[seon.schema "agents-can-validate" :namespace]}]
         (is (= aspect ((juxt :seon.task/ns :seon.task/name :seon.task/kind)
                        (db/pull @connection [:seon.task/ns :seon.task/name :seon.task/kind] [:seon.task/id id]))))
         (is (= id (apply issue/task-handle aspect))))
       (is (= {:seon.task/changed? false} (issue/index-tasks! {:seon.db/connection connection :seon.task/files files})))
       (is (= :handle-mismatch
              (refusal #(issue/index-tasks! {:seon.db/connection connection
                                             :seon.task/files {"resources/seon/tasks/seon.sci.eval.edn"
                                                               (get files "resources/seon/tasks/seon.schema.edn")}}))))))))
