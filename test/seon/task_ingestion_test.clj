(ns seon.task-ingestion-test
  "Task ingestion (spec 7.9-7.16): symbol-valued edges, task EDN with derived handles
  and a revision basis, lookups both ways, and the publication seam's dangling refusal."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.cluster.source]
            [seon.db :as db]
            [seon.fn]
            [seon.id]
            [seon.profile :as profile]
            [seon.task :as task]
            [seon.program :as program]
            [seon.test-support :as test-support]))

(def ^:private handle (task/task-handle 'tq.a "prove-ingestion"))

(def ^:private tag 'tq.a/prove-ingestion)

(def ^:private digest
  "A source digest the publication seam's contract admits."
  (apply str (repeat 64 "d")))

(def ^:private subject-source
  (str "(ns tq.a {:seon/tasks #{'tq.a/prove-ingestion} :seon/must-read ['tq.a/f]}\n"
       "  (:require [clojure.test :refer [deftest is]] [clojure.string :as s]))\n"
       "(defn f {:seon/tasks #{'tq.a/prove-ingestion} :seon/must-read ['tq.a/g] :seon/see-also [`s/join]} [v] v)\n"
       "(defn g {:seon/tasks #{'tq.a/prove-ingestion}} [v] (:seon/tasks {:seon/tasks #{::x}}))\n"
       "(deftest ^{:seon/tasks #{'tq.a/prove-ingestion}} proves (is (= 2 (f 1))) (is (= #{} (:seon/tasks {:seon/tasks #{}}))))\n"))

(def ^:private task-path "resources/seon/tasks/tq.a.edn")

(defn- task-text
  {:malli/schema [:=> [:cat :string :string] :string]}
  [intent members]
  (str "[#:seon.task{:name \"prove-ingestion\" :intent \"" intent "\" " members "}]"))

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
  (task/index-tasks! (cond-> {:seon.db/connection connection :seon.task/files {task-path text}}
                        basis (assoc :seon.task/basis basis))))

(deftest ingested-tasks-answer-both-ways-and-unchanged-input-writes-nothing
  (test-support/with-database
   (fn [connection]
     (let [entities (entities-of subject-source) text (task-text "Prove ingestion." ":must-read #{tq.a/g} :evidence #{}")]
       (admit! connection entities)
       (is (:seon.task/changed? (ingest! connection text)))
       (is (= #:seon.task{:subjects '#{tq.a tq.a/f tq.a/g} :proofs '#{tq.a/proves} :referrers #{'tq.a 'tq.a/f tag}}
              (task/task-edges @connection tag)))
       (is (= '#{clojure.string/join} (set (:seon.fn/see-also (db/pull @connection [:seon.fn/see-also] '[:seon.fn/sym tq.a/f])))))
       (is (= #{tag} (task/open-tasks @connection '[:seon.fn/sym tq.a/g])))
       (is (not-any? #(contains? % tag) (vals (task/task-findings @connection))))
       (testing "unchanged input, with an empty optional set, writes nothing"
         (is (= {:seon.task/changed? false} (ingest! connection text)))
         (let [[again calls] (test-support/armed-calls
                              '#{seon.task/file-declarations seon.task/task-delta}
                              #(ingest! connection text))]
           (is (= {:seon.task/changed? false} again))
           (is (every? zero? (vals calls))))
         (is (empty? (seon.fn/reconcile-tx @connection entities []))))
       (testing "the same aspect with a new intent keeps its handle; clearing a set retracts it"
         (is (:seon.task/changed? (ingest! connection (task-text "Changed." ""))))
         (is (= {:seon.task/intent "Changed."}
                (db/pull @connection [:seon.task/intent :seon.task/must-read] [:seon.task/id handle]))))
       (testing "removing one annotation drops only that edge"
         (admit! connection (entities-of (str/replace subject-source "(defn g {:seon/tasks #{'tq.a/prove-ingestion}}" "(defn g")))
         (is (= '#{tq.a tq.a/f} (:seon.task/subjects (task/task-edges @connection tag)))))
       (testing "program attributes carry the symbol edges, never task entities"
         (let [attributes (program/program-attributes (db/carried-projection @connection))]
           (is (contains? attributes :seon.task/tasks))
           (is (not-any? attributes [:seon.task/id :seon.task/intent]))))
       (is (:seon.error/at (task/open-tasks @connection '[:seon.no-such/attribute x])) "a refused read returns whole")))))

(deftest two-edits-from-one-basis-the-second-refuses-and-the-winner-stays
  (test-support/with-database
   (fn [connection]
     (let [basis (task/task-basis @connection {task-path "[]"})]
       (is (:seon.task/changed? (ingest! connection (task-text "First." "") basis)))
       (is (= :revision-conflict (refusal #(ingest! connection (task-text "Second." "") basis))))
       (is (= "First." (:seon.task/intent (db/pull @connection [:seon.task/intent] [:seon.task/id handle]))))))))

(deftest malformed-task-files-refuse-by-name-without-writing
  (test-support/with-database
   (fn [connection]
     (let [before (db/basis-t @connection)]
       (doseq [[rule text] {:not-one-vector "[] [#:seon.task{:name \"x\" :intent \"i\"}]"
                            :unreadable-file "[{"
                            :invalid-entry "[#:seon.task{:name \"x\" :intnet \"i\"}]"
                            :invalid-name "[#:seon.task{:name \"x y\" :intent \"i\"}]"
                            :duplicate-name "[#:seon.task{:name \"x\" :intent \"i\"} #:seon.task{:name \"x\" :intent \"j\"}]"}]
         (is (= rule (refusal #(ingest! connection text)))))
       (testing "only #{} at an optional set member is absence; other empty values meet the closed entry"
         (doseq [members [":unknown []" ":must-read []" ":evidence {}" ":id \"000000000000\"" ":id []"]]
           (let [text (str "[#:seon.task{:name \"x\" :intent \"i\" " members "}]")
                 refused (ingest! connection text)]
             (is (= :invalid-entry (:seon.task/refused refused)))
             (is (= (edn/read-string text) (:seon.error/offending refused)) "the diagnostic keeps the authored entry"))))
       (is (= :unreadable-file (refusal #(ingest! connection "#=(+ 1 2)"))))
       (is (= :not-one-vector (refusal #(ingest! connection "{}"))))
       (is (= :misplaced-file (refusal #(task/index-tasks! {:seon.db/connection connection :seon.task/files {"tmp/tq.a.edn" "[]"}}))))
       (is (= before (db/basis-t @connection)) "no refusal writes")))))

(deftest a-task-is-identified-by-its-namespace-and-name
  ;; A task stored under the handle of an older aspect ([ns name kind]) leaves
  ;; before the handle its namespace and name derive claims the unique symbol,
  ;; in one transaction; the entry declares no kind.
  (test-support/with-database
   (fn [connection]
     (test-support/transacted! connection [{:seon.task/id (seon.id/id ['tq.a "prove-ingestion" :function]) :seon.task/sym tag
                                            :seon.task/ns 'tq.a :seon.task/name "prove-ingestion" :seon.task/intent "i"}])
     (is (:seon.task/changed? (ingest! connection "[#:seon.task{:name \"prove-ingestion\" :intent \"i\"}]")))
     (is (= {:seon.task/id (seon.id/id ['tq.a "prove-ingestion"])}
            (db/pull @connection [:seon.task/id] [:seon.task/sym tag]))))))

(deftest annotation-placement-is-positional-and-body-usage-stays-legal
  (let [head "(ns tq.a (:require [clojure.test :refer [deftest is]] [tq.b :as-alias b]))\n"]
    (is (= :metadata-after-test-name (refusal #(entities-of (str head "(deftest t {:seon/tasks #{\"x\"}} (is true))\n")))))
    (is (nil? (refusal #(entities-of (str head "(deftest t (is (:seon/tasks {:seon/tasks #{::b/x}})))\n")))))
    (is (= :malformed-annotation (refusal #(entities-of (str head "(defn f {:seon/tasks #{::b/x}} [v] v)\n")))))
    (is (= :malformed-annotation (refusal #(entities-of (str head "(defn f {:seon/tasks #{\"043df611d179\"}} [v] v)\n"))))
        "a task is tagged by its symbol, never a handle")
    (is (= :malformed-annotation (refusal #(entities-of (str head "(defn ^:seon/tasks f [v] v)\n")))))
    (is (= :malformed-annotation (refusal #(entities-of (str head "(defn f {:seon/must-read [clojure.core/inc]} [v] v)\n")))))))

(deftest publication-ingests-tasks-and-refuses-a-dangling-tag
  ;; The destination is a branch of the live program, so the checkout's own task
  ;; files ride along: only this fixture's tag may dangle.
  (let [publish-tasks! #'seon.cluster.source/index-tasks!
        checkout (task/task-files "." nil)
        dangling #(try (% ) nil (catch clojure.lang.ExceptionInfo e (keys (:seon.task/dangling (ex-data e)))))]
    (test-support/with-database
     (fn [connection]
       (admit! connection (entities-of subject-source))
       (is (= [tag] (with-root checkout (fn [root] (dangling #(publish-tasks! connection digest (.getPath root) nil)))))
           "an annotation whose task is absent at the destination refuses with its holders")
       (with-root (assoc checkout task-path (task-text "Prove ingestion." ""))
         (fn [root]
           (is (:seon.task/changed? (publish-tasks! connection digest (.getPath root) nil)) "coherent task and annotation admission")
           (is (= {:seon.task/changed? false} (publish-tasks! connection digest (.getPath root) nil)) "repeat is a no-op")
           (io/delete-file (io/file root task-path))
           (is (= [tag] (dangling #(publish-tasks! connection digest (.getPath root) nil)))
               "a removed task file retracts its task, leaving its annotations dangling")))))))

(deftest a-source-only-publication-admits-the-unchanged-task-file-its-annotations-name
  ;; Class regression (lane fix-task-gate-publication 2026-09-25): a publication
  ;; whose changed paths hold no task file, over a destination that recorded the
  ;; task file's digest but never admitted its tasks, refused its own annotations
  ;; as dangling. The gate reads the tasks of the same published source.
  (test-support/with-database
   (fn [connection]
     (admit! connection (entities-of subject-source))
     (with-root (assoc (task/task-files "." nil) task-path (task-text "Prove ingestion." ""))
       (fn [root]
         (is (:seon.task/changed? (#'seon.cluster.source/index-tasks! connection digest (.getPath root) nil)))
         (is (not-any? #(contains? % tag) (vals (task/task-findings @connection)))))))))

(deftest adoption-carries-published-tasks-keeps-the-destinations-own-and-refuses-a-competing-edit
  ;; P1-2 (review 5fb38b91f): the adoption compares against the destination value
  ;; it read, and only the source's history makes a missing task a deletion.
  (test-support/with-database
   (fn [source]
     (test-support/with-database
      (fn [destination]
        ;; The inner fixture holds destination's custody; the source's writes run under its own.
        (let [publish! #(is (:seon.task/changed? (db/with-branch {:seon.db/connection source} (fn [] (ingest! source %))))
                            "the source admits the edit")
              adopt! #(test-support/transacted! destination (task/adopt-tasks-call @source @destination))
              intent #(:seon.task/intent (db/pull @destination [:seon.task/intent] [:seon.task/id %]))
              own (task/task-handle 'tq.b "own")]
          (is (= [] (task/adopt-tasks-call @source @source)) "an agreeing cluster adopts nothing")
          (task/index-tasks! {:seon.db/connection destination :seon.task/basis {}
                               :seon.task/files {"resources/seon/tasks/tq.b.edn" "[#:seon.task{:name \"own\" :intent \"Mine.\"}]"}})
          (publish! (task-text "Prove ingestion." ""))
          (adopt!)
          (is (= ["Prove ingestion." "Mine."] (map intent [handle own])) "published arrives; a task the source never held stays")
          (is (= [] (task/adopt-tasks-call @source @destination)) "repeat adopts nothing")
          (testing "a competing edit between the adoption's read and its transaction refuses; the competitor stays"
            (publish! (task-text "Revised." ""))
            (let [call (task/adopt-tasks-call @source @destination)]
              (is (seq call) "the proposal carries the source's revision")
              (is (:seon.task/changed? (ingest! destination (task-text "Competing." ""))))
              (is (= :revision-conflict (refusal #(test-support/transacted! destination call))))
              (is (= ["Competing." "Mine."] (map intent [handle own])) "the refused adoption changes no task")))
          (testing "a published task the source deletes retracts; the destination's own stays"
            (publish! "[]")
            (adopt!)
            (is (= [nil "Mine."] (map intent [handle own]))))))))))

(deftest a-merged-program-dangles-by-holder-against-the-destinations-admitted-tasks
  ;; P1-3: the candidate's entities for the merged identities, against the
  ;; destination's tasks; a holder the merge leaves untouched never refuses it.
  (test-support/with-database
   (fn [connection]
     (let [head @connection
           merged #{[:seon.fn/sym 'tq.a/f] [:seon.test/sym 'tq.a/proves]}]
       (admit! connection (entities-of subject-source))
       (is (= {tag '#{tq.a/f tq.a/proves}} (task/candidate-dangling @connection head merged)))
       (is (not (contains? (task/candidate-dangling @connection head #{}) tag)) "unmerged holders are the destination's")
       (is (= {} (select-keys (task/candidate-dangling head @connection merged) [tag]))
           "a merged entity without the annotation drops its edge; the destination's untouched holders are its own")
       (ingest! connection (task-text "Prove ingestion." ""))
       (is (= {} (select-keys (task/candidate-dangling @connection @connection merged) [tag])))))))

(deftest the-checkouts-records-ingest-by-symbol-and-repeat-as-a-no-op
  ;; The checkout's own task files: each task is found by its symbol, under the
  ;; handle its aspect derives.
  (test-support/with-database
   (fn [connection]
     (let [files (task/task-files "." nil)
           basis (task/task-basis @connection files)]
       (task/index-tasks! {:seon.db/connection connection :seon.task/files files :seon.task/basis basis})
       (doseq [[sym aspect] {'seon.sci.eval/acquisition-rebuilds-the-whole-program '[seon.sci.eval "acquisition-rebuilds-the-whole-program"]
                             'seon.schema/agents-can-validate '[seon.schema "agents-can-validate"]}]
         (is (= (conj aspect (apply task/task-handle aspect))
                ((juxt :seon.task/ns :seon.task/name :seon.task/id)
                 (db/pull @connection [:seon.task/ns :seon.task/name :seon.task/id] [:seon.task/sym sym])))))
       (is (= {:seon.task/changed? false} (task/index-tasks! {:seon.db/connection connection :seon.task/files files})))))))

(deftest a-revised-task-shows-on-the-next-call-without-reassignment
  (test-support/with-running-agent
   (fn [running agent-id connection]
     (admit! connection (entities-of subject-source))
     (ingest! connection (task-text "First." ""))
     (let [declaration {:seon.db/connection connection :seon.agent/id agent-id :seon.task/ns 'tq.a
                        :seon.task/name "prove-ingestion"}
           id handle
           intent #(:seon.task/intent (db/pull @connection [:seon.task/intent] [:seon.task/id id]))
           assigned (fn [] (select-keys (task/assignment @connection agent-id (get-in (db/db connection) [:config :branch])) [:seon.task/assigned :seon.task/proofs :seon.error/type]))]
       (is (:seon.task/assignment (task/assign! {:seon.agent/context-source (test-support/running-cluster running) :seon.db/connection connection :seon.task/assigned id
                                                :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch (get-in (db/db connection) [:config :branch])}))
           "a task whose proof has not run is assigned")
       (is (= id (:seon.task/assigned (assigned))) "the assignment answers before the revision")
       (is (:seon.task/changed? (task/create! (assoc declaration :seon.task/intent "Second."
                                                     :seon.task/basis {id (#'task/declared (db/carried-projection @connection)
                                                                                           (db/pull @connection '[*] [:seon.task/id id]))}))))
       (is (= "Second." (intent)) "the task shows its revised declaration")
       (is (= id (:seon.task/assigned (assigned))) "the assignment is current without a new assign!")
       (is (nil? (:seon.error/type (assigned))) "the revision is not refused as revised-since-assignment")))))

(deftest assignment-refuses-a-task-without-proofs
  (test-support/with-database
   (fn [connection]
     (test-support/transacted! connection (test-support/agent-tx @connection "revisor"))
     (let [id (:seon.task/id (task/create! {:seon.db/connection connection :seon.agent/id "revisor" :seon.task/ns 'tq.r
                                            :seon.task/name "bare" :seon.task/intent "No proof."}))
           refused (task/assign! {:seon.db/connection connection :seon.task/assigned id
                                  :seon.task/agent [:seon.agent/id "revisor"] :seon.task/branch (get-in (db/db connection) [:config :branch])})]
       (is (= :no-proofs (:seon.task/refused refused)))
       (is (nil? (:seon.task/assignment refused)))))))

(deftest an-assignment-offers-no-merge-request
  (test-support/with-running-agent
   (fn [running agent-id connection]
     (admit! connection (entities-of subject-source))
     (ingest! connection (task-text "First." ""))
     (let [reply (task/assign! {:seon.agent/context-source (test-support/running-cluster running) :seon.db/connection connection :seon.task/assigned handle
                                :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch (get-in (db/db connection) [:config :branch])})]
       (is (:seon.task/assignment reply) "the assignment is written")
       (is (= [] (:seon.render/operations reply)) "nothing is offered that cannot be done yet")))))

(deftest ^{:seon/tasks #{'seon.task/an-assignment-reads-only-its-proofs}} an-assignment-reads-only-its-proofs
  (test-support/with-running-agent
   (fn [running agent-id connection]
     (admit! connection (entities-of subject-source))
     (ingest! connection (task-text "First." ""))
     (let [read (profile/measure #(task/assignment-read (db/db connection) handle [:seon.agent/id agent-id]))
           assigned (profile/measure #(task/assign! {:seon.agent/context-source (test-support/running-cluster running)
                                                     :seon.db/connection connection :seon.task/assigned handle
                                                     :seon.task/agent [:seon.agent/id agent-id]
                                                     :seon.task/branch (get-in (db/db connection) [:config :branch])}))]
       (is (= #{'tq.a/proves} (:seon.task/proofs (:seon.profile/value read))))
       (is (< (:seon.profile/alloc-bytes read) (* 8 1024 1024)) "the assignment's read allocates under 8 MB")
       (is (:seon.task/assignment (:seon.profile/value assigned)))
       ;; measured 1,193,221,736 bytes in a check JVM (run 60c4370fa87e); the proof run's own cost is
       ;; seon.test/one-test-run-at-the-repl-allocates-over-a-gigabyte
       (is (< (:seon.profile/alloc-bytes assigned) (* 2 1024 1024 1024)) "the assignment with its one proof run allocates under 2 GB")))))

(defn- ignored-roots
  "The directories `.gitignore` names at the root, as path prefixes."
  []
  (->> (str/split-lines (slurp ".gitignore"))
       (filter #(and (str/ends-with? % "/") (not (str/starts-with? % "#"))))
       (map #(str/replace % #"^/" ""))
       vec))

(deftest ^{:seon/tasks #{'seon.task/a-task-evidence-path-names-no-gitignored-file}}
  a-task-evidence-path-names-no-gitignored-file
  (let [ignored (ignored-roots)
        entries (for [file (sort-by #(.getName ^java.io.File %) (.listFiles (io/file "resources/seon/tasks")))
                      entry (edn/read-string (slurp file))
                      path (:seon.task/evidence entry)]
                  {:file (.getPath ^java.io.File file) :task (:seon.task/name entry) :path path})
        offending (filter (fn [{:keys [path]}] (some #(str/starts-with? path %) ignored)) entries)]
    (is (some #{"tmp/"} ignored) "the census read the ignored directories")
    (is (> (count entries) 100) "the census read the task evidence")
    (is (= [] (vec offending)) "each site is a task whose evidence names a gitignored path")))
