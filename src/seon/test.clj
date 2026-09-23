(ns seon.test
  "One test request on the agent execution lifecycle, and the recorded evidence it reads."
  (:require
            [clojure.java.io :as io]
            [clojure.string :as str]
            [sci.core :as sci]
            [seon.config :as config]
            [seon.cluster.source :as source]
            [seon.cluster.store :as store]
            [seon.db :as db]
            [seon.env :as env]
            [seon.fn :as functions]
            [seon.id :as id]
            [seon.program :as program]
            [seon.profile :as profile]
            [seon.schema :as schema]
            [seon.sci.eval :as sci.eval]
            [seon.test.runner :as runner])
  (:import [java.util.concurrent FutureTask]))


(defn- unknown
  {:malli/schema [:=> [:cat :seon.schema/value :string] :seon.test/unknown-error]}
  [input message]
  {:seon.error/at (java.util.Date.) :seon.error/layer :seon.test/execution
   :seon.error/operation 'seon.test/unknown :seon.test/unknown (str input)
   :seon.error/message message :seon.test/next-tier :none})

(defn failure-text
  "Show an assertion's exact claim, ordered contexts, and known source site."
  {:malli/schema [:=> [:cat [:or :seon.test.failure/value :seon.test.failure/report]] :string]}
  [failure]
  (let [field (fn [inline blob]
                (or (get failure inline)
                    (when-let [digest (get failure blob)]
                      (pr-str (list 'seon.blob/get digest)))))
        path (or (:seon.test.failure/reported-file failure)
                 (get-in failure [:seon.test.failure/file :seon.fn.file/relative-path]))]
    (str/join "\n"
      (remove nil?
        [(str (name (:seon.test.failure/type failure))
              (when path (str " " path ":" (:seon.test.failure/line failure))))
         (when (seq (:seon.test.failure/contexts failure))
           (str/join " > " (map second (sort-by first (:seon.test.failure/contexts failure)))))
         (:seon.test.failure/message failure)
         (when-let [expected (field :seon.test.failure/expected :seon.test.failure/expected-blob)]
           (str "expected: " expected))
         (when-let [actual (field :seon.test.failure/actual :seon.test.failure/actual-blob)]
           (str "actual: " actual))]))))

(defn failure-message
  "Read structured assertion evidence, retaining legacy text for old results."
  {:malli/schema [:=> [:cat :seon.test.runner/captured-result] :string]}
  [result]
  (if-let [failures (or (seq (:seon.test/failures result))
                        (seq (:seon.test.failure/reports result)))]
    (str/join "\n\n" (map failure-text failures))
    (or (:seon.test/failure-message result) "No assertion claim was retained.")))

(declare definition-digests)

(defn changed-since-green
  "Name reached definitions whose content changed since the latest admitted green."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.test/sym]
                  [:or [:vector :seon.fn/sym] :seon.test/unknown-error]]}
  [database test-symbol]
  (try
    (let [rows (db/q '[:find ?selected ?basis
                       :in $ ?symbol ?branch
                       :where [?member :seon.test.member/symbol ?symbol]
                              [?member :seon.test.member/completed-tx]
                              [?member :seon.test.member/terminated-tx]
                              [?member :seon.test.member/pass-count ?passes]
                              [(pos? ?passes)]
                              [?member :seon.test.member/fail-count 0]
                              [?member :seon.test.member/error-count 0]
                              [?run :seon.test.run/members ?member]
                              [?run :seon.test.run/selection-tx ?selected]
                              [?run :seon.test.run/basis-t ?basis]
                              [?run :seon.test.run/branch ?branch]
                              (not [?run :seon.test.run/published-base-digest])]
                     database test-symbol
                     (get-in (db/schema-database database) [:config :branch]))]
      (if (:seon.error/at rows)
        (unknown test-symbol (:seon.error/message rows))
        (if-let [[_ basis] (last (sort-by first rows))]
          (let [tested (db/as-of database basis)
                before (runner/reach-memberships tested [test-symbol])
                after (runner/reach-memberships database [test-symbol])
                failure (first (filter :seon.error/at [before after]))]
            (if failure (unknown test-symbol (:seon.error/message failure))
                (let [symbols (vec (set (concat (get before test-symbol) (get after test-symbol))))
                      old (definition-digests tested symbols)
                      current (definition-digests database symbols)]
                  (vec (sort (filter #(not= (get old %) (get current %)) symbols))))))
          (unknown test-symbol "No admitted green execution is recorded for this test."))))
    (catch Exception failure
      (unknown test-symbol (str "Recorded green comparison is unavailable: " (ex-message failure))))))

(defn- with-test-loader
  ([work] (with-test-loader (clojure.lang.RT/baseLoader) work))
  ([loader work]
  (let [thread (Thread/currentThread)
        previous (.getContextClassLoader thread)]
    (try
      (.setContextClassLoader thread loader)
      (with-bindings {clojure.lang.Compiler/LOADER loader} (work))
      (finally (.setContextClassLoader thread previous))))))

(defn- throwable-text
  "A throwable's whole cause chain, outermost to root, with each link's class,
  message and ex-data (`Throwable->map`, `clojure/core_print.clj:473`), and
  the root cause's first frame."
  {:malli/schema [:=> [:cat :seon.error/throwable] :string]}
  [throwable]
  (let [{links :via trace :trace} (Throwable->map throwable)]
    (str/join "\n"
              (concat
               (for [{link-class :type :keys [message data]} links]
                 (str link-class
                      (when message (str ": " message))
                      (when data (str " " (pr-str data)))))
               (when-let [frame (first trace)] [(str "at " (pr-str frame))])))))

(def ^:dynamic *member*
  "The execution handle of the member whose host (JVM) test body runs on this
  thread, bound by `seon.test/run` around that body exactly as clojure.test
  binds `*testing-vars*`. Canonical fixtures branch off it. Nil outside a
  member body; an agent (SCI) body reaches its branch through custody instead."
  nil)

(defn- bounded-result
  "Observe the body thread's exit under the request bound; cancellation is not exit.

  `release!` is called exactly once, with `true` when the body outlived the
  bound (from a watcher that joins the live thread) and `false` otherwise:
  at once when the body exits inside the bound, or before rethrowing when
  the body thread never started. The caller hands all cleanup to it."
  {:malli/schema [:=> [:cat :seon.test/var [:int {:min 1}]
                       [:map [:seon.db/connection {:optional true} :seon.db/connection]
                        [:seon.sci.eval/ctx :seon.sci.eval/ctx]
                        [:seon.test/member {:optional true} :seon.agent/execution-handle]]
                       [:=> [:cat :boolean] :nil]]
                  :seon.test.runner/captured-result]}
  [test-var timeout-ms custody release!]
  (let [started? (volatile! false)]
    (try
      (let [test-symbol (symbol (str (:ns (meta test-var))) (str (:name (meta test-var))))
            markers (program/test-markers (meta test-var) (meta (:ns (meta test-var))))
            ;; The SCI arm interrupts interpreted bodies at the declared per-test
            ;; bound; `duration-failures` fails any body that completes over it.
            body-bound (or (:seon.test/long-ms markers) 5000)
            member (:seon.test/member custody)
            task (FutureTask.
                  (bound-fn []
                    (with-test-loader
                      #(if (var? test-var)
                         ;; A host body runs unarmed, with no inherited cluster
                         ;; custody, as a JVM test body always has: it may
                         ;; evaluate its own SCI contexts, which another arm on
                         ;; this thread would refuse.
                         (binding [*member* member]
                           (runner/run-var! test-var {}))
                         (sci.eval/run-test
                          (assoc (dissoc custody :seon.test/member)
                                 :seon.test/var test-var
                                 :seon.sci.eval/time-limit-ms body-bound))))))
            thread (.unstarted (Thread/ofVirtual) task)
            failed (fn [message exited?]
                     {:seon.test/sym test-symbol
                      :seon.test.member/began? false
                      :seon.test.member/ended? false
                      :seon.test.run/terminated? exited?
                      :seon.test/pass-count 0 :seon.test/fail-count 0
                      :seon.test/error-count 1 :seon.test/failure-message message})
            ;; A timeout is not termination: the watcher releases resources
            ;; only after it observes the body's exit.
            watch-exit! (fn []
                          (.start (Thread/ofVirtual)
                                  ^Runnable (fn [] (.join thread) (release! true))))]
        (.start thread)
        (vreset! started? true)
        (try
          (if (.join thread (java.time.Duration/ofMillis timeout-ms))
            (do
              (release! false)
              (let [result (.get task)]
                (if (:seon.test/not-runnable result)
                  (failed (:seon.error/message result) true)
                  (assoc result :seon.test.run/terminated? true))))
            (do
              (watch-exit!)
              (failed (str "Test " test-symbol " did not exit within "
                           ":seon.test/check-time-limit-ms remainder " timeout-ms " ms; thread "
                           (.threadId thread) " remains live and keeps its branch until it exits. "
                           "No further body may start.")
                      false)))
          (catch InterruptedException _
            (.interrupt (Thread/currentThread))
            (watch-exit!)
            (failed (str "Interrupted while awaiting actual exit of " test-symbol
                         "; thread " (.threadId thread) ".")
                    false))
          (catch java.util.concurrent.ExecutionException failure
            (failed (str "Test execution failed:\n" (throwable-text (or (ex-cause failure) failure)))
                    true))))
      (catch Throwable failure
        (when-not @started? (release! false))
        (throw failure)))))

;;; ---------------------------------------------------------------------------
;;; Where a test may run: a destructive drill never runs on a development root
;;; ---------------------------------------------------------------------------

(defn- development-root
  "The declared operator root when THIS JVM was launched to operate its own
  working directory — the developer's checkout, with its live `data/store`.

  `bin/seon [--root PATH] start` declares the root it operates on every child
  JVM, so an ordinary development JVM declares the checkout it runs in, and
  the `bin/test --platform` host declares its fresh run root. Returns the
  canonical development root, or nil when this JVM operates an isolated root
  or declares nothing."
  [declared]
  (when-not (str/blank? declared)
    (let [root (.getCanonicalPath (io/file declared))
          working (.getCanonicalPath (io/file (System/getProperty "user.dir")))]
      (when (= root working) root))))

(defn destroyers
  "What each declared destructive owner destroys: `{owner-symbol text}`.

  THE ONE DERIVATION on this side, over `:seon.fn/destroys` — the declaration
  each owner carries in its own metadata at its definition, indexed as a
  program fact. There is no roster of owners in code: the cold gate's tier
  checker derives the same set from the same attribute over manifest rows
  (`seon.test.runner/destructive-owner-rows`), so the two halves of the rule
  read one declaration and can never disagree.

  A program in which NOTHING declares it is the typed unknown, never an empty
  set: an unpublished or drifted program would otherwise walk to nothing and
  admit every test, which is absence of signal read as health."
  {:malli/schema [:=> [:cat :seon.db/database-value]
                  [:or [:map-of :seon.fn/sym :seon.fn/destroys] :seon.error/value]]}
  [database]
  (let [rows (db/q '[:find ?sym ?destroys
                     :where
                     [?function :seon.fn/destroys ?destroys]
                     [?function :seon.fn/sym ?sym]]
                   database)]
    (cond
      (and (map? rows) (contains? rows :seon.error/at) (contains? rows :seon.error/layer) (contains? rows :seon.error/operation)) rows
      (empty? rows)
      (unknown :seon.fn/destroys
               (str "No function in this program declares :seon.fn/destroys, "
                    "so an in-process run cannot tell whether a test deletes a "
                    "filesystem path it did not create. Republish the program "
                    "(bin/seon init --dev default), or declare the attribute "
                    "in the owner's own metadata at its definition."))
      :else (into {} rows))))

(defn- destructive-reach
  "Every test symbol whose reach includes a destructive owner, mapped to it.

  Membership is the shared `:seon.fn/calls` derivation `seon.fn/tests-reaching`,
  walked from each owner `destroyers` names."
  [database]
  (let [owners (destroyers database)]
    (if (and (map? owners) (contains? owners :seon.error/at) (contains? owners :seon.error/layer) (contains? owners :seon.error/operation))
      owners
      (let [by-owner (functions/gate-sets database (sort (keys owners)))]
        (if (:seon.db/invalid-read by-owner)
          by-owner
          (reduce (fn [reached [owner tests]] (reduce #(assoc %1 %2 owner) reached tests))
                  {} (sort by-owner)))))))

(defn- destructive-paths
  "Shortest named call path from every name reaching `owner-symbol` down to it.

  One breadth-first reverse walk from the owner over the index edges
  `seon.fn/gate-set-in` walks (`:seon.fn/calls`, `:seon.fn/references`,
  `:seon.test/subject`), so the cost follows the owner's reverse closure once,
  never one forward walk per member."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.fn/sym]
                  [:map-of :qualified-symbol :seon.test/destructive-path]]}
  [database owner-symbol]
  (let [datoms (fn [& arguments]
                 (let [found (apply db/datoms database arguments)]
                   (if (:seon.db/invalid-read found)
                     (throw (ex-info "Destructive path unavailable." found))
                     found)))
        name-of (fn [entity]
                  (some #(:v (first (datoms :eavt entity %))) [:seon.test/sym :seon.fn/sym]))]
    (loop [frontier [owner-symbol] next-hop {owner-symbol nil}]
      (if (empty? frontier)
        (into {} (map (fn [start]
                        [start (loop [path [start]]
                                 (if-let [hop (next-hop (peek path))] (recur (conj path hop)) path))]))
              (keys next-hop))
        (let [fresh (reduce (fn [found [caller target]]
                              (if (or (contains? next-hop caller) (contains? found caller))
                                found
                                (assoc found caller target)))
                            {}
                            (for [target frontier
                                  attribute [:seon.fn/calls :seon.fn/references :seon.test/subject]
                                  datom (datoms :avet attribute target)
                                  :let [caller (name-of (:e datom))]
                                  :when caller]
                              [caller target]))]
          (recur (vec (sort (keys fresh))) (merge next-hop fresh)))))))

(defn- destructive-exclusion
  "One selected test's destructive evidence, or nil when it reaches no owner.
  `paths` maps each owner to `destructive-paths` from it; a reach the walk
  cannot name keeps no path key, never a nil one."
  [owners reach paths test-symbol]
  (when-let [owner (get reach test-symbol)]
    (let [path (get-in paths [owner test-symbol])]
      (cond-> {:seon.test/sym test-symbol
               :seon.fn/sym owner
               :seon.test/command ["bin/test" "--" (namespace (symbol test-symbol))]}
        path (assoc :seon.test/destructive-path path)
        (string? (get owners owner))
        (assoc :seon.fn/destroys (get owners owner))))))

(defn host
  "Where one test runs, and why, as data.

  `:seon.test/host` is `:seon.test.host/in-process` — the cluster's own JVM,
  where an agent's own run and `seon.test/check` execute it — or
  `:seon.test.host/isolated-snapshot`, because the test reaches a function
  declaring `:seon.fn/destroys`; that answer carries the owner, what it
  destroys, the declared call path between them, and the cold invocation that
  may run it.

  Derived, never declared on the test and never stored: the answer is the
  program graph read now, so it moves the moment a declaration or a call edge
  does. A test with NO program row has no known call graph and the answer is
  the typed unknown — absence of edges is never read as safe.

  Example:
  (seon.test/host (seon.db/db) \"seon.cluster.boot-test/a-real-boot\")"
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.test/sym]
                  [:or :seon.test/host-report :seon.error/value]]}
  [database test-symbol]
  (let [owners (destroyers database)]
    (if (and (map? owners) (contains? owners :seon.error/at) (contains? owners :seon.error/layer) (contains? owners :seon.error/operation))
      owners
      (let [row (db/pull database [:db/id] [:seon.test/sym test-symbol])]
        (cond
          (and (map? row) (contains? row :seon.error/at) (contains? row :seon.error/layer) (contains? row :seon.error/operation)) row
          (nil? (:db/id row))
          (unknown test-symbol
                   (str "No program row declares the test " test-symbol
                        ", so its call graph is unknown and where it runs "
                        "cannot be derived. Publish the program "
                        "(bin/seon init --dev default --changed <file>) and "
                        "ask again."))
          :else
          (let [reach (destructive-reach database)]
            (if (and (map? reach) (contains? reach :seon.error/at) (contains? reach :seon.error/layer) (contains? reach :seon.error/operation))
              reach
              (if-let [evidence (destructive-exclusion
                                 owners reach
                                 (when-let [owner (get reach test-symbol)]
                                   {owner (destructive-paths database owner)})
                                 test-symbol)]
                (assoc evidence :seon.test/host :seon.test.host/isolated-snapshot)
                {:seon.test/sym test-symbol
                 :seon.test/host :seon.test.host/in-process}))))))))

(defn host-text
  "One line an agent reads: where this test runs and why.
  The unknown says so; it never reads as in-process."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.test/sym] :string]}
  [database test-symbol]
  (let [report (host database test-symbol)]
    (cond
      (and (map? report) (contains? report :seon.error/at) (contains? report :seon.error/layer) (contains? report :seon.error/operation))
      (str "runs: unknown — " (:seon.error/message report))
      (= :seon.test.host/isolated-snapshot (:seon.test/host report))
      (str "runs: isolated snapshot, under its own operator root ("
           (str/join " -> " (:seon.test/destructive-path report)) ": "
           (or (:seon.fn/destroys report) "deletes a filesystem path it did not create")
           "). Cold invocation: "
           (str/join " " (:seon.test/command report)))
      :else "runs: in the cluster process")))

(declare resolve-test selection-admission admit-run)

(defn- selection-refusal
  "Name unavailable evidence on the supplied immutable database."
  {:malli/schema [:=> [:cat :seon.db/database-value :keyword :string :seon.schema/value]
                  :seon.test/selection-error]}
  [database kind message observed]
  (assoc {:seon.error/at (java.util.Date.)
    :seon.error/layer :seon.test/selection
    :seon.error/operation 'seon.test/select
    :seon.error/message message
    :seon.error/expected :complete-comparable-program-evidence
    :seon.error/offending observed
    :seon.error/data {:seon.test.run/basis-t (db/basis-t database)
     :seon.test.run/branch (get-in (db/schema-database database) [:config :branch])}}
         :seon.test/selection-refusal kind))

(defn- selection-read!
  "Carry a polymorphic query result unchanged; preserve a refused read exactly."
  {:malli/schema [:=> [:cat :seon.schema/value] :seon.schema/value]}
  [result]
  (if (and (map? result)
           (contains? result :seon.error/at)
           (contains? result :seon.error/layer)
           (contains? result :seon.error/operation))
    (throw (ex-info (:seon.error/message result) result))
    result))

(defn- selection-facts
  "Read each selected entity range once, then retain the requested attributes."
  {:malli/schema [:=> [:cat :seon.db/database-value [:sequential :int] [:vector :qualified-keyword]]
                  [:map-of :int [:map-of :qualified-keyword [:set :seon.schema/value]]]]}
  [database entities attributes]
  (reduce (fn [facts [entity attribute value]]
            (update-in facts [entity attribute] (fnil conj #{}) value))
          {} (selection-read!
              (db/q '[:find ?entity ?attribute ?value
                      :in $ [?entity ...] ?attributes
                      :where [?entity ?attribute ?value]
                             [(contains? ?attributes ?attribute)]]
                    database entities (set attributes)))))

(defn- definition-digests
  "Read the canonical declaration identity stored by the producer."
  {:malli/schema [:=> [:cat :seon.db/database-value [:sequential :qualified-symbol]]
                  [:map-of :qualified-symbol :seon.source/digest]]}
  [database symbols]
  (into {}
        (selection-read!
         (db/q '[:find ?symbol ?digest
                 :in $ [?symbol ...]
                 :where (or [?entity :seon.fn/sym ?symbol]
                            [?entity :seon.test/sym ?symbol])
                        [?entity :seon.program/definition-digest ?digest]]
               database symbols))))

(defn- changed-definition-symbols
  "Read assertion and retraction identities in the same lineage, including namespace bindings."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/basis-t]
                  [:set :qualified-symbol]]}
  [database basis]
  (let [history (db/history database)
        changes (db/since history basis)
        changed-entities (selection-read!
                (db/q '[:find [?entity ...] :in $ [?attribute ...]
                        :where [?entity ?attribute]]
                      changes
                      [:seon.fn/source :seon.fn/spec :seon.fn/calls :seon.fn/references
                       :seon.fn/sym :seon.test/sym :seon.test/source :seon.test/subject
                       :seon.test/platform :seon.test/fixture :seon.test/fixture-observation
                       :seon.test/long :seon.schema.admission/source
                       :seon.program/analyzed-source-digest]))
        direct (selection-read!
                (db/q '[:find [?symbol ...] :in $ [?entity ...]
                        :where (or [?entity :seon.fn/sym ?symbol]
                                   [?entity :seon.test/sym ?symbol])]
                      history changed-entities))
        before (definition-digests (db/as-of database basis) (vec direct))
        after (definition-digests database (vec direct))
        direct (filter #(not= (get before %) (get after %)) direct)
        changed-bindings (selection-read!
                    (db/q '[:find [?entity ...] :in $ [?attribute ...]
                            :where [?entity ?attribute]]
                          changes
                          [:seon.ns/source :seon.ns/requires :seon.ns/aliases
                           :seon.ns/refers :seon.ns/imports :seon.ns/name
                           :seon.ns.alias/local :seon.ns.alias/target-ns
                           :seon.ns.refer/local :seon.ns.refer/target-ns :seon.ns.refer/target-name
                           :seon.ns.import/local :seon.ns.import/target-class]))
        namespaces (selection-read!
                    (db/q '[:find [?ns ...] :in $ [?entity ...]
                            :where (or-join [?entity ?ns]
                                     (and [?entity :seon.ns/name] [(identity ?entity) ?ns])
                                     [?ns :seon.ns/aliases ?entity]
                                     [?ns :seon.ns/refers ?entity]
                                     [?ns :seon.ns/imports ?entity])]
                          history changed-bindings))
        namespace-symbols (if (seq namespaces)
                            (selection-read!
                             (db/q '[:find [?symbol ...] :in $ [?ns ...]
                                     :where
                                     (or [?entity :seon.fn/ns ?ns] [?entity :seon.test/ns ?ns])
                                     (or [?entity :seon.fn/sym ?symbol] [?entity :seon.test/sym ?symbol])]
                                   history namespaces)) [])]
    (into (set direct) namespace-symbols)))

(defn- selection-seeds
  "Resolve supplied definition and namespace identities in this database."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.test/changed]
                  [:or [:set :qualified-symbol] :seon.test/selection-error :seon.test/unknown-error
                   :seon.db/invalid-read-error :seon.schema/missing-projection-error]]}
  [database changed]
  (reduce
   (fn [seeds change]
     (let [[attribute value] (if (vector? change) change [:seon.fn/sym change])]
       (if (= attribute :seon.ns/name)
         (let [members (db/q '[:find [?symbol ...] :in $ ?name
                               :where [?n :seon.ns/name ?name]
                               (or-join [?e ?n] [?e :seon.fn/ns ?n] [?e :seon.test/ns ?n])
                               (or [?e :seon.fn/sym ?symbol] [?e :seon.test/sym ?symbol])]
                             database value)]
           (cond (and (map? members)
                      (contains? members :seon.error/at)
                      (contains? members :seon.error/layer)
                      (contains? members :seon.error/operation)) (reduced members)
                 (empty? members) (reduced (unknown change "Namespace has no analyzed definitions."))
                 :else (into seeds members)))
         (if (qualified-symbol? value)
           (let [known (db/q '[:find ?e . :in $ ?symbol
                               :where (or [?e :seon.fn/sym ?symbol]
                                          [?e :seon.test/sym ?symbol]
                                          [?e :seon.fn/calls ?symbol]
                                          [?e :seon.fn/references ?symbol]
                                          [?e :seon.test/subject ?symbol])]
                             (db/history database) value)]
             (cond (and (map? known)
                        (contains? known :seon.error/at)
                        (contains? known :seon.error/layer)
                        (contains? known :seon.error/operation)) (reduced known)
                   known (conj seeds value)
                   :else (reduced (selection-refusal database :seon.test/identity-unresolved
                                   "Changed identity has no definition, history or incoming edge."
                                   [:seon.fn/sym value]))))
           (reduced (selection-refusal database :seon.test/coverage-unknown
                                       "This changed declaration has no bounded graph selection." change))))))
   #{} changed))

(defn- green-members
  "Member identities with positive, terminated assertion evidence."
  {:malli/schema [:=> [:cat :seon.db/database-value [:sequential :int]] [:set :int]]}
  [database members]
  (set (selection-read!
        (db/q '[:find [?member ...] :in $ [?member ...]
                :where [?member :seon.test.member/completed-tx]
                       [?member :seon.test.member/terminated-tx]
                       [?member :seon.test.member/began? true]
                       [?member :seon.test.member/ended? true]
                       [?member :seon.test.member/pass-count ?pass]
                       [(> ?pass 0)]
                       [?member :seon.test.member/fail-count 0]
                       [?member :seon.test.member/error-count 0]
                       (not [?member :seon.test.member/error])]
              database members))))

(defn select
  "Select test memberships from explicit cluster custody.
  Baselines and outstanding work derive from admitted run/member facts, ordered
  by selection transaction. Calls, references and declared subjects use one
  union gate walk. Named requests retain their declared eligibility scope.
  Green evidence from an earlier program is reusable when its reachable content
  and external inputs still match; its tested provenance remains unchanged.
  Unknown coverage refuses. No filesystem reads occur."
  {:malli/schema [:=> [:cat :seon.test.selection/request]
                  [:or :seon.test.selection/result :seon.test/selection-error :seon.test/unknown-error :seon.test.run/unavailable-error
                   :seon.db/invalid-read-error :seon.schema/missing-projection-error]]}
  [{database :seon.db/db cluster :seon.test.run/cluster
    requested-namespaces :seon.test/namespaces requested-identities :seon.test/identities
    supplied-basis :seon.test.run/change-basis-t :as request}]
  (try
    (if-not cluster
      (selection-refusal database :seon.test/cluster-required "Selection requires explicit cluster custody." :absent)
      (let [cluster-row (when cluster (selection-read! (db/pull database [:db/id :seon.cluster/name] cluster)))
            cluster-id (:db/id cluster-row)
            refuse! (fn [kind message observed]
                      (let [refusal (selection-refusal database kind message observed)]
                        (throw (ex-info message refusal))))
            _ (when (and cluster (not (:seon.cluster/name cluster-row)))
                (refuse! :seon.test/cluster-unavailable "The requested cluster is absent." cluster))
            branch (if cluster (get-in (db/schema-database database) [:config :branch]) :current-src)
            basis-t (db/basis-t database)
            _ (when (and supplied-basis (> supplied-basis basis-t))
                (refuse! :seon.test/invalid-basis "A comparison basis cannot be in the future." supplied-basis))
            _ (doseq [attribute [:seon.fn/calls :seon.fn/references :seon.test/reach]]
                (let [installed (get (:schema (db/schema-database database)) attribute)]
                  (when-not (and (= :db.type/symbol (:db/valueType installed))
                                 (= :db.cardinality/many (:db/cardinality installed))
                                 (:db/index installed))
                    (refuse! :seon.test/edge-schema-mismatch "Selection requires indexed symbol edges." attribute))))
            namespaces (set requested-namespaces)
            identities (set requested-identities)
            policy (or (:seon.test.run/policy request)
                       (when (or (seq namespaces) (seq identities)) :named) :incremental)
            include-long? (or (= :full policy) (true? (:seon.test/include-long? request)))
            run-ids (selection-read!
                     (if cluster
                       (db/q '[:find [?run ...] :in $ ?cluster
                               :where [?run :seon.test.run/cluster ?cluster]] database cluster-id)
                       (db/q '[:find [?run ...] :in $ ?branch
                               :where [?run :seon.test.run/branch ?branch]
                                      (not [?run :seon.test.run/cluster])]
                             database branch)))
            source-ids (selection-read!
                        (db/q '[:find [?source ...] :where [?source :seon.source/digest]] database))
            member-ids (selection-read!
                        (db/q '[:find [?member ...] :in $ [?run ...] [?attribute ...]
                                :where [?run ?attribute ?member]]
                              database run-ids [:seon.test.run/members :seon.test.run/covered-by]))
            facts (selection-facts database (concat run-ids source-ids member-ids)
                    [:seon.source/digest :seon.source/test-input-digest
                     :seon.test.run/id :seon.test.run/at :seon.test.run/program-digest
                     :seon.test.run/cluster :seon.test.run/policy
                     :seon.test.run/branch :seon.test.run/tested-branch
                     :seon.test.run/include-long? :seon.test.run/input-digest
                     :seon.test.run/basis-t :seon.test.run/selection-tx
                     :seon.test.run/namespaces :seon.test.run/identities
                     :seon.test.run/members :seon.test.run/covered-by
                     :seon.test.member/symbol :seon.test.member/reasons
                     :seon.test.member/completed-tx :seon.test.member/terminated-tx
                     :seon.test.member/began? :seon.test.member/ended?
                     :seon.test.member/pass-count :seon.test.member/fail-count
                     :seon.test.member/error-count :seon.test.member/error])
            one (fn [entity attribute] (first (get-in facts [entity attribute])))
            sources source-ids
            input-digest (when (= 1 (count sources))
                           (one (first sources) :seon.source/test-input-digest))
            _ (when-not input-digest
                (refuse! :seon.test/input-evidence-unavailable
                         "The publication has no unique external-input identity." (vec sources)))
            _ (when-not (selection-read!
                          (db/q '[:find ?test . :where [?test :seon.test/sym]] database))
                (refuse! :seon.test/population-unknown "No indexed test population is available." :absent))
            runs (->> run-ids
                      (filter #(and (one % :seon.test.run/id)
                                    (= cluster-id (one % :seon.test.run/cluster))
                                    (= branch (one % :seon.test.run/branch))
                                    (or (nil? (one % :seon.test.run/tested-branch))
                                        (= branch (one % :seon.test.run/tested-branch)))
                                    (or (= :platform policy) (not= :platform (one % :seon.test.run/policy)))
                                    (= include-long? (one % :seon.test.run/include-long?))
                                    (= input-digest (one % :seon.test.run/input-digest))
                                    (= namespaces (get-in facts [% :seon.test.run/namespaces] #{}))
                                    (= identities (get-in facts [% :seon.test.run/identities] #{}))
                                    (one % :seon.test.run/selection-tx)))
                      (sort-by #(one % :seon.test.run/selection-tx)))
            members (fn [run] (into (get-in facts [run :seon.test.run/members] #{})
                                   (get-in facts [run :seon.test.run/covered-by] #{})))
            admitted-members (selection-facts (db/history database) runs
                               [:seon.test.run/members :seon.test.run/covered-by])
            _ (doseq [run runs]
                (let [admitted (into (get-in admitted-members [run :seon.test.run/members] #{})
                                     (get-in admitted-members [run :seon.test.run/covered-by] #{}))]
                  (when-not (= admitted (members run))
                    (refuse! :seon.test/population-unknown
                             "Admitted membership refs were removed; coverage is unavailable."
                             (one run :seon.test.run/id)))))
            _ (doseq [run runs member (members run)]
                (when-not (and (one member :seon.test.member/symbol)
                               (seq (get-in facts [member :seon.test.member/reasons])))
                  (refuse! :seon.test/population-unknown "An admitted membership is unavailable." member)))
            complete? (fn [member]
                        (and (one member :seon.test.member/completed-tx)
                             (one member :seon.test.member/terminated-tx)
                             (number? (one member :seon.test.member/pass-count))
                             (number? (one member :seon.test.member/fail-count))
                             (number? (one member :seon.test.member/error-count))))
            green? (green-members database member-ids)
            completed (filter #(and (seq (members %)) (every? complete? (members %))) runs)
            last-green (last (filter #(every? green? (members %)) completed))
            comparison (or supplied-basis (some-> (last completed) (one :seon.test.run/basis-t)))
            removed-files (when comparison
                            (selection-read!
                             (db/q '[:find [?path ...] :in $ $changes
                                     :where [$changes ?file :seon.fn.file/relative-path ?path _ false]
                                            (not-join [?file] [?file :seon.fn.file/relative-path])]
                                   database (db/since (db/history database) comparison))))
            first-run? (and (= :incremental policy) (or (nil? last-green) (seq removed-files)))
            pending (reduce (fn [result run]
                              (reduce (fn [result member]
                                        (let [symbol (one member :seon.test.member/symbol)]
                                          (if (green? member) (dissoc result symbol)
                                            (update result symbol (fnil into #{})
                                                    (get-in facts [member :seon.test.member/reasons])))))
                                      result (members run)))
                            {} (if last-green (drop-while #(<= (one % :seon.test.run/selection-tx)
                                                                (one last-green :seon.test.run/selection-tx)) runs) runs))
            observed-changed (if comparison (changed-definition-symbols database comparison) #{})
            requested-changed (selection-read! (selection-seeds database (vec (:seon.test/changed request))))
            changed (into observed-changed requested-changed)
            schema-changes (when (and comparison (= :incremental policy) (not first-run?))
                             (selection-read!
                              (db/q '[:find [?e ...] :in $ [?attribute ...] :where [?e ?attribute]]
                                    (db/since (db/history database) comparison)
                                    [:seon.schema/key :seon.schema/edn])))
            _ (when (seq schema-changes)
                (refuse! :seon.test/coverage-unknown "Schema changes require proven bounded dependencies." (vec schema-changes)))
            reached (if (seq changed)
                      (selection-read! (functions/gate-sets {:seon.db/db database :seon.fn/seeds changed})) [])
            work? (or first-run? (seq changed) (seq pending))
            reached (set reached)
            ;; A named request's `:seon.test/changed` names the tests reaching
            ;; exactly those identities; changes observed since the last run
            ;; widen only incremental requests.
            requested-reached (if (and (= :named policy) (seq requested-changed))
                                (set (selection-read!
                                      (functions/gate-sets {:seon.db/db database
                                                            :seon.fn/seeds requested-changed})))
                                #{})
            candidates
            (if (or first-run? (#{:all :full} policy))
              (selection-read! (db/q '[:find [?symbol ...] :where [_ :seon.test/sym ?symbol]] database))
              (into (into (into identities (if (= :named policy) requested-reached reached))
                          (keys pending))
                    (concat
                     (when (and (= :incremental policy) (not work?))
                       (map #(one % :seon.test.member/symbol) (mapcat members runs)))
                     (when (seq namespaces)
                       (selection-read!
                        (db/q '[:find [?symbol ...] :in $ [?name ...]
                                :where [?ns :seon.ns/name ?name]
                                       [?test :seon.test/ns ?ns] [?test :seon.test/sym ?symbol]]
                              database namespaces)))
                     ;; The declared platform tier has its own isolated host
                     ;; (`bin/test --platform`); an incremental request asks for
                     ;; its changed reach and outstanding work only.
                     (when (= :platform policy)
                       (selection-read!
                        (db/q '[:find [?symbol ...]
                                :where [?test :seon.test/platform] [?test :seon.test/sym ?symbol]] database))))))
            tests (into {} (selection-read!
                            (db/q '[:find ?symbol ?entity :in $ [?symbol ...]
                                    :where [?entity :seon.test/sym ?symbol]] database candidates)))
            candidate-data (selection-facts database (vec (vals tests))
                             [:seon.test/sym :seon.test/ns :seon.fn/file
                              :seon.schema.admission/source :seon.program/analyzed-source-digest
                              :seon.test/fixture :seon.test/fixture-observation
                              :seon.test/long :seon.test/platform])
            related-ids (into #{} (mapcat (fn [row]
                                           (concat (:seon.test/ns row) (:seon.fn/file row))))
                              (vals candidate-data))
            candidate-data (merge candidate-data
                                  (selection-facts database (vec related-ids)
                                    [:seon.ns/name :seon.fn.file/relative-root]))
            one (fn [entity attribute]
                  (first (get (or (get candidate-data entity) (get facts entity)) attribute)))
            _ (doseq [entity (vals tests)]
                (when-not (one entity :seon.program/analyzed-source-digest)
                  (refuse! :seon.test/analysis-unknown "Program analysis provenance is absent."
                           (or (one entity :seon.fn/sym) (one entity :seon.test/sym)))))
            named? (fn [[symbol entity]]
                     (or (identities symbol)
                         (namespaces (one (one entity :seon.test/ns) :seon.ns/name))))
            ;; Fixture material is never gate membership. A declared fixture
            ;; observation IS membership: it needs a file-backed host, which
            ;; `seon.test/run` gives it off a development root and names
            ;; with its platform command on one.
            excluded? (fn [[_ entity]]
                        (one entity :seon.test/fixture))
            _ (doseq [entry tests :when (and (named? entry) (excluded? entry))]
                (refuse! :seon.test/fixture-excluded "Explicit fixture material is not gate membership." (first entry)))
            eligible (into {}
                       (filter (fn [[_ entity :as entry]]
                                 (and (not (excluded? entry))
                                      (or (= :agent (one entity :seon.schema.admission/source))
                                          (= "test" (one (one entity :seon.fn/file) :seon.fn.file/relative-root))
                                          (named? entry))
                                      (or include-long? (identities (first entry))
                                          (not (one entity :seon.test/long)))
                                      ;; A named request's changed identities
                                      ;; name the tests reaching them too.
                                      (or (not= :named policy) (named? entry)
                                          (requested-reached (first entry)))))) tests)
            _ (doseq [symbol identities :when (not (get eligible symbol))]
                (refuse! :seon.test/identity-unresolved "The requested test is not eligible." symbol))
            _ (doseq [ns-symbol namespaces
                      :when (not-any? #(= ns-symbol (one (one % :seon.test/ns) :seon.ns/name)) (vals tests))]
                (refuse! :seon.test/namespace-unresolved "The requested namespace has no eligible tests." ns-symbol))
            long-excluded
            (into [] (keep (fn [[test-symbol entity]]
                             (when (and (not include-long?) (not (identities test-symbol))
                                        (or (not= :named policy) (named? [test-symbol entity]))
                                        (not (excluded? [test-symbol entity])) (one entity :seon.test/long))
                               {:seon.test/sym test-symbol :seon.test/long (one entity :seon.test/long)
                                :seon.test/command (if cluster
                                                    ["bin/test-check" (:seon.cluster/name cluster-row)
                                                     "--test" (str test-symbol)]
                                                    ["bin/test" "--full"])}))) tests)
            reasons (reduce-kv
                     (fn [result symbol entity]
                       (let [entry [symbol entity]
                             reasons (cond-> (if (= :incremental policy) (get pending symbol #{}) #{})
                                       (and first-run? (not= :platform policy)) (conj :first-run)
                                       (and (not= :platform policy) (reached symbol)) (conj :reaches-changed)
                                       (or (named? entry) (#{:all :full} policy)) (conj :named)
                                       (and (#{:platform :all :full} policy) (one entity :seon.test/platform)) (conj :platform))]
                         (if (seq reasons) (assoc result symbol reasons) result)))
                     (sorted-map) eligible)
            digest (selection-read! (runner/program-digest database))
            latest (reduce
                    (fn [result run-eid]
                      (reduce (fn [result member]
                                (assoc result (one member :seon.test.member/symbol) [run-eid member]))
                              result (get-in facts [run-eid :seon.test.run/members])))
                    {} (sort-by #(one % :seon.test.run/selection-tx)
                                (filter #(and (= branch (one % :seon.test.run/branch))
                                              (not (one % :seon.test.run/tested-branch))
                                              (= input-digest (one % :seon.test.run/input-digest))
                                              (one % :seon.test.run/selection-tx)) run-ids)))
            ;; Recorded evidence was earned by the program its JVM loaded. When
            ;; the JVM loaded source the cluster's program rows do not describe
            ;; (a restart from changed files, before adoption), the rows cannot
            ;; vouch for what executes: unknown, so nothing is reused.
            loaded-drift? (when-let [loaded (:seon.test/loaded-source request)]
                            (not= loaded (:seon.source/commit-id
                                          (selection-read!
                                           (db/pull database [:seon.source/commit-id] cluster)))))
            reuse-candidates (cond
                               loaded-drift? {}
                               (and (= :incremental policy) (not work?)) eligible
                               :else reasons)
            changed-program-candidates
            (into {} (keep (fn [[test-symbol _]]
                             (when-let [[run-eid member] (get latest test-symbol)]
                               (when (and (green? member)
                                          (not (reached test-symbol))
                                          (or (nil? supplied-basis)
                                              (= supplied-basis (one run-eid :seon.test.run/basis-t)))
                                          (not= digest (one run-eid :seon.test.run/program-digest)))
                                 [test-symbol (one run-eid :seon.test.run/basis-t)]))))
                  reuse-candidates)
            current-reach (when (seq changed-program-candidates)
                            (selection-read! (runner/reach-digests database (vec (keys changed-program-candidates)))))
            recorded-reach
            (reduce-kv (fn [result tested-basis entries]
                         (merge result
                                (selection-read!
                                 (runner/reach-digests (db/as-of database tested-basis)
                                                       (mapv first entries)))))
                       {} (group-by val changed-program-candidates))
            reused (into (sorted-map)
                         (keep (fn [[test-symbol _]]
                                 (when-let [[run-eid member] (get latest test-symbol)]
                                   (when (and (green? member)
                                              (or (nil? supplied-basis) (= supplied-basis (one run-eid :seon.test.run/basis-t)))
                                              (not (reached test-symbol))
                                              (or (= digest (one run-eid :seon.test.run/program-digest))
                                                  (and (string? (get current-reach test-symbol))
                                                       (= (get current-reach test-symbol)
                                                          (get recorded-reach test-symbol)))))
                                     [test-symbol
                                      {:seon.test/sym test-symbol :seon.test/unchanged true
                                       :seon.test/run-basis-t (one run-eid :seon.test.run/basis-t)
                                       :seon.test.run/basis-t (one run-eid :seon.test.run/basis-t)
                                       :seon.test.run/program-digest (one run-eid :seon.test.run/program-digest)
                                       :seon.test.run/input-digest input-digest
                                       :seon.test/run-at (one run-eid :seon.test.run/at)
                                       :seon.test/run [:seon.test.run/id (one run-eid :seon.test.run/id)]
                                       :seon.test/recorded-basis-t (one member :seon.test.member/completed-tx)
                                       :seon.test/pass-count (one member :seon.test.member/pass-count)
                                       :seon.test/fail-count 0 :seon.test/error-count 0}]))))
                         reuse-candidates)
            reasons (apply dissoc reasons (keys reused))]
        (cond-> {:seon.test.run/basis-t basis-t
                 :seon.test.run/policy policy
                 :seon.test.run/include-long? include-long?
                 :seon.test.run/input-digest input-digest
                 :seon.test.run/members (mapv (fn [[symbol reasons]]
                                               {:seon.test/sym symbol :seon.test.member/reasons reasons}) reasons)}
          cluster-id (assoc :seon.test.run/cluster cluster-id)
          (seq reused) (assoc :seon.test.selection/unchanged (vec (vals reused))
                              :seon.test.run/covered-by
                              (set (map #(second (get latest %)) (keys reused))))
          (seq long-excluded) (assoc :seon.test/long-excluded long-excluded)
          first-run? (assoc :seon.test.selection/widenings
                            [(cond (seq removed-files) :removed-file
                                   (some #(and (= cluster-id (one % :seon.test.run/cluster))
                                               (= :incremental (one % :seon.test.run/policy))
                                               (one % :seon.test.run/selection-tx)
                                               (not= input-digest (one % :seon.test.run/input-digest)))
                                         run-ids) :outside-program-graph
                                   :else :missing-basis)])
          (seq removed-files) (assoc :seon.test.selection/removed (vec (sort removed-files)))
          comparison (assoc :seon.test.run/change-basis-t comparison)
          (seq namespaces) (assoc :seon.test.run/namespaces namespaces)
          (seq identities) (assoc :seon.test.run/identities identities)
          loaded-drift? (assoc :seon.test/loaded-source-drift
                               ;; Rows that record no source commit are absent,
                               ;; never a nil commit id.
                               (let [recorded (:seon.source/commit-id
                                               (selection-read!
                                                (db/pull database [:seon.source/commit-id] cluster)))]
                                 (cond-> {:seon.test/loaded-source (:seon.test/loaded-source request)}
                                   recorded (assoc :seon.source/commit-id recorded)))))))
    (catch clojure.lang.ExceptionInfo failure
      (let [refusal (ex-data failure)]
        (if (or (:seon.test/selection-refusal refusal)
                (:seon.test/unknown refusal)
                (:seon.test.run/unavailable refusal)
                (:seon.db/invalid-read refusal)
                (:seon.schema/missing-projection refusal))
          refusal
          (throw failure))))))

(defn reaching
  "Return tests reaching explicit changed definitions through the same union graph owner."
  {:malli/schema [:=> [:cat :seon.test/reaching-request]
                  [:or [:vector :seon.test/sym] :seon.test/selection-error :seon.test/unknown-error
                   :seon.db/invalid-read-error :seon.schema/missing-projection-error]]}
  [{database :seon.db/db changed :seon.test/changed}]
  (let [seeds (selection-seeds database changed)]
    (if (and (map? seeds)
             (contains? seeds :seon.error/at)
             (contains? seeds :seon.error/layer)
             (contains? seeds :seon.error/operation)) seeds
      (functions/gate-sets {:seon.db/db database :seon.fn/seeds seeds}))))

(defn selection-admission
  "Prepare the same complete admission for either execution host, before any body starts."
  {:malli/schema [:=> [:cat :seon.test.selection/request]
                  [:or :seon.test.run/admission :seon.test/selection-error :seon.test/unknown-error :seon.test.run/unavailable-error
                   :seon.db/invalid-read-error :seon.schema/missing-projection-error]]}
  [{database :seon.db/db :as request}]
  (let [selected (select request)]
    (if (and (map? selected)
             (contains? selected :seon.error/at)
             (contains? selected :seon.error/layer)
             (contains? selected :seon.error/operation)) selected
      (let [provenance (or (:seon.test.run/provenance request) (runner/provenance database))]
        (if (and (map? provenance)
                 (contains? provenance :seon.error/at)
                 (contains? provenance :seon.error/layer)
                 (contains? provenance :seon.error/operation)) provenance
          (-> selected
              (dissoc :seon.test.run/basis-t)
              (assoc :seon.test.run/provenance provenance)
              (update :seon.test.run/members
                      (fn [members]
                        (mapv (fn [member]
                                {:seon.test.member/symbol (:seon.test/sym member)
                                 :seon.test.member/reasons (:seon.test.member/reasons member)}) members)))))))))

(defn- admission-refusal!
  {:malli/schema [:=> [:cat :keyword :seon.test.run/id :seon.schema/value :seon.schema/value] :nil]}
  [kind run-id expected offending]
  (let [failure
        (assoc {:seon.error/at (java.util.Date.)
          :seon.error/layer :seon.test/admission
          :seon.error/operation 'seon.test/admit-run
          :seon.error/message "Test run admission refused inconsistent evidence."
          :seon.error/expected expected
          :seon.error/offending offending
          :seon.error/data {:seon.test.run/id run-id}} :seon.test/admission-refusal kind)]
    (throw (ex-info (:seon.error/message failure) failure))))

(defn- admission-members
  {:malli/schema [:=> [:cat :seon.db/database-value :int] [:vector [:map-of :keyword :seon.schema/value]]]}
  [database run-eid]
  ;; Query refs rather than pulling the parent's cardinality-many collection:
  ;; Datahike's default pull limit must not shorten an execution obligation.
  (let [run-id (selection-read!
                (db/q '[:find ?id . :in $ ?run :where [?run :seon.test.run/id ?id]]
                      database run-eid))
        ids (db/q '[:find [?member ...] :in $ ?run [?attribute ...]
                    :where [?run ?attribute ?member]]
                  database run-eid
                  [:seon.test.run/members :seon.test.run/covered-by])]
    (when (and (map? ids)
               (contains? ids :seon.error/at)
               (contains? ids :seon.error/layer)
               (contains? ids :seon.error/operation))
      (throw (ex-info (:seon.error/message ids) ids)))
    (mapv #(let [row (db/pull database
                             [:db/id :seon.test.member/symbol :seon.test.member/reasons] %)]
             (when (or (and (map? row)
                            (contains? row :seon.error/at)
                            (contains? row :seon.error/layer)
                            (contains? row :seon.error/operation))
                       (not (:seon.test.member/symbol row))
                       (not (seq (:seon.test.member/reasons row))))
               (admission-refusal! :seon.test/population-unknown run-id
                                   :admitted-member (or row :seon.error/unknown)))
             row)
          ids)))

(defn- admission-scope
  {:malli/schema [:=> [:cat [:map-of :keyword :seon.schema/value]] [:map-of :keyword :seon.schema/value]]}
  [row]
  (cond-> (-> (select-keys row [:seon.test.run/cluster :seon.test.run/program-digest
                       :seon.test.run/published-base-digest :seon.test.run/overlay-input-digest
                       :seon.test.run/input-digest :seon.test.run/branch
                       :seon.test.run/tested-branch :seon.test.run/change-basis-t
                       :seon.test.run/policy :seon.test.run/include-long?])
      (assoc :seon.test.run/namespaces (set (:seon.test.run/namespaces row))
             :seon.test.run/identities (set (:seon.test.run/identities row))))
    (= :named (:seon.test.run/policy row))
    (assoc :seon.test.run/basis-t (:seon.test.run/basis-t row))))

(defn- admission-member-values
  {:malli/schema [:=> [:cat [:sequential [:map-of :keyword :seon.schema/value]]] [:set [:map-of :keyword :seon.schema/value]]]}
  [members]
  (into #{} (map #(-> (select-keys % [:seon.test.member/symbol
                                     :seon.test.member/reasons])
                      (update :seon.test.member/reasons set))) members))

(defn admit-run
  "Reserve a supplied selection at the writer, before any test executes.

  Invoke with [:db.fn/call seon.test/admit-run request]. The request
  carries the publisher's input digest and immutable tested provenance;
  selection is handed as immutable input. This function executes no test bodies. It checks the current program and custody,
  then reserves only members not already covered by matching admitted runs.
  Concurrent requests see earlier reservations in the transaction database.
  An identical replay emits no evidence datoms; a changed replay refuses.

  The primary host supplies its own cluster. Published snapshots carry the
  source authority's immutable base, overlay, program and basis handoff."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.test.run/admission]
                  :seon.store/transaction-data]}
  [database {run :seon.test.run/provenance
             cluster :seon.test.run/cluster
             members :seon.test.run/members :as request}]
  (let [run-id (:seon.test.run/id run)
        snapshot? (some? (:seon.test.run/published-base-digest run))
        cluster-row (when cluster (db/pull database [:db/id :seon.cluster/name] cluster))
        cluster-id (:db/id cluster-row)
        branch (get-in (db/schema-database database) [:config :branch])
        digest (if snapshot? (:seon.test.run/program-digest run) (runner/program-digest database))
        row (-> (merge (select-keys request
                                   [:seon.test.run/cluster :seon.test.run/input-digest
                                    :seon.test.run/policy :seon.test.run/include-long?
                                    :seon.test.run/deadline
                                    :seon.test.run/exclusions
                                    :seon.test.run/change-basis-t :seon.test.run/namespaces
                                    :seon.test.run/identities])
                       (select-keys run
                                    [:seon.test.run/id :seon.test.run/at :seon.test.run/git-sha
                                     :seon.test.run/published-base-digest :seon.test.run/overlay-input-digest
                                     :seon.test.run/program-digest :seon.test.run/basis-t
                                     :seon.test.run/callers-at-head
                                     :seon.test.run/branch :seon.test.run/tested-branch]))
                (cond-> cluster-id (assoc :seon.test.run/cluster cluster-id)))
        selector (into [:db/id :seon.test.run/selection-tx
                        [:seon.test.run/callers-at-head :limit nil]
                        [:seon.test.run/namespaces :limit nil]
                        [:seon.test.run/exclusions :limit nil]
                        [:seon.test.run/identities :limit nil]]
                       (keys (dissoc row :seon.test.run/members
                                     :seon.test.run/namespaces :seon.test.run/identities
                                     :seon.test.run/callers-at-head
                                     :seon.test.run/exclusions)))
        previous (db/pull database selector [:seon.test.run/id run-id])]
    (doseq [read-result [cluster-row previous]]
      (when (and (map? read-result)
                 (contains? read-result :seon.error/at)
                 (contains? read-result :seon.error/layer)
                 (contains? read-result :seon.error/operation))
        (throw (ex-info (:seon.error/message read-result) read-result))))
    (when-not (or snapshot? (:seon.cluster/name cluster-row))
      (admission-refusal! :seon.test/cluster-unavailable run-id
                          :explicit-authority-cluster cluster))
    (when (and (not snapshot?) (or (not= branch (:seon.test.run/branch run))
                                  (:seon.test.run/tested-branch run)))
      (admission-refusal! :seon.test/cluster-mismatch run-id branch
                          (select-keys run [:seon.test.run/branch
                                            :seon.test.run/tested-branch])))
    (when (and snapshot?
               (or (not= :current-src (:seon.test.run/branch run))
                   (not (:seon.test.run/overlay-input-digest run))))
      (admission-refusal! :seon.test/cluster-mismatch run-id
                          :published-source-snapshot run))
    (when (or (and (map? digest)
                   (contains? digest :seon.error/at)
                   (contains? digest :seon.error/layer)
                   (contains? digest :seon.error/operation))
              (not= digest (:seon.test.run/program-digest run)))
      (admission-refusal! :seon.test/program-mismatch run-id digest
                          (:seon.test.run/program-digest run)))
    (when (and (not snapshot?) (or (> (:seon.test.run/basis-t run) (db/basis-t database))
              (> (get request :seon.test.run/change-basis-t 0)
                 (:seon.test.run/basis-t run))))
      (admission-refusal! :seon.test/invalid-basis run-id
                          (db/basis-t database)
                          (select-keys row [:seon.test.run/basis-t
                                            :seon.test.run/change-basis-t])))
    (when (and (not snapshot?) (seq (changed-definition-symbols database (:seon.test.run/basis-t run))))
      (admission-refusal! :seon.test/program-mismatch run-id
                          :unchanged-tested-program :program-changed-after-tested-basis))
    (when-not snapshot?
    (let [inputs (selection-read!
                  (db/q '[:find [?digest ...] :where [_ :seon.source/test-input-digest ?digest]] database))]
      (when-not (= 1 (count inputs))
        (admission-refusal! :seon.test/input-evidence-unavailable run-id :one-publication-input-digest inputs))
      (when-not (= (first inputs) (:seon.test.run/input-digest request))
        (admission-refusal! :seon.test/program-mismatch run-id (first inputs) (:seon.test.run/input-digest request)))))
    (when (not= (count members) (count (set (map :seon.test.member/symbol members))))
      (admission-refusal! :seon.test.run/immutable run-id
                          :one-membership-per-symbol members))
    (if (:db/id previous)
      (let [prior (cond-> (dissoc previous :db/id :seon.test.run/selection-tx)
                    (:seon.test.run/cluster previous)
                    (assoc :seon.test.run/cluster
                           (get-in previous [:seon.test.run/cluster :db/id])))
            requested-members
            (into (vec members)
                  (selection-read!
                   (db/pull-many database
                                 [:seon.test.member/symbol [:seon.test.member/reasons :limit nil]]
                                 (vec (:seon.test.run/covered-by request)))))
            normalize #(-> %
                           (cond-> (:seon.test.run/callers-at-head %)
                             (update :seon.test.run/callers-at-head set))
                           (update :seon.test.run/exclusions
                                   (fn [rows] (set (map (fn [row] (dissoc row :db/id)) rows))))
                           (dissoc :seon.test.run/namespaces :seon.test.run/identities)
                           (merge (select-keys (admission-scope %)
                                              [:seon.test.run/namespaces
                                               :seon.test.run/identities])))]
        (when (or (not (:seon.test.run/selection-tx previous))
                  (not= (normalize row) (normalize prior))
                  (not= (admission-member-values requested-members)
                        (admission-member-values
                         (admission-members database (:db/id previous)))))
          (admission-refusal! :seon.test.run/immutable run-id row prior))
        [])
      (let [runs (if snapshot?
                   (db/q '[:find [?run ...] :in $ ?base ?overlay ?digest ?basis
                           :where [?run :seon.test.run/published-base-digest ?base]
                                  [?run :seon.test.run/overlay-input-digest ?overlay]
                                  [?run :seon.test.run/program-digest ?digest]
                                  [?run :seon.test.run/basis-t ?basis]
                                  [?run :seon.test.run/selection-tx]]
                         database (:seon.test.run/published-base-digest run)
                         (:seon.test.run/overlay-input-digest run) digest (:seon.test.run/basis-t run))
                   (db/q '[:find [?run ...] :in $ ?cluster ?digest ?inputs
                         :where [?run :seon.test.run/cluster ?cluster]
                                [?run :seon.test.run/program-digest ?digest]
                                [?run :seon.test.run/input-digest ?inputs]
                                [?run :seon.test.run/selection-tx]]
                       database cluster-id digest (:seon.test.run/input-digest row)))
            _ (when (and (map? runs)
                         (contains? runs :seon.error/at)
                         (contains? runs :seon.error/layer)
                         (contains? runs :seon.error/operation))
                (throw (ex-info (:seon.error/message runs) runs)))
            ;; A body cannot outlive its JVM: only runs admitted since this JVM
            ;; started can hold members; older admissions are interrupted work,
            ;; outstanding obligations for selection, never reservations.
            jvm-started (java.util.Date. (.getStartTime (java.lang.management.ManagementFactory/getRuntimeMXBean)))
            ;; One query bounded by the requested members, never a walk of
            ;; every recorded run's members.
            symbols (mapv :seon.test.member/symbol members)
            candidates (if (and (seq runs) (seq symbols))
                         (selection-read!
                          (db/q '[:find ?run ?member ?symbol
                                  :in $ [?run ...] [?symbol ...] ?since
                                  :where [?run :seon.test.run/members ?member]
                                         [?member :seon.test.member/symbol ?symbol]
                                         [?run :seon.test.run/at ?at]
                                         [(compare ?at ?since) ?order]
                                         [(>= ?order 0)]]
                                database (vec runs) symbols jvm-started))
                         [])
            scope-of (memoize
                      (fn [run-eid]
                        (let [candidate (selection-read! (db/pull database selector run-eid))]
                          (admission-scope
                           (cond-> candidate
                             (:seon.test.run/cluster candidate)
                             (assoc :seon.test.run/cluster
                                    (get-in candidate [:seon.test.run/cluster :db/id])))))))
            existing
            (into {}
                  (keep (fn [[run-eid member-eid test-symbol]]
                          (let [member-row (selection-read!
                                     (db/pull database [:db/id :seon.test.member/symbol
                                                        :seon.test.member/reasons
                                                        :seon.test.member/completed-tx
                                                        :seon.test.member/terminated-tx]
                                              member-eid))]
                            (when (if (:seon.test.member/completed-tx member-row)
                                    ;; A body not observed to exit holds its test
                                    ;; in every scope.
                                    (not (:seon.test.member/terminated-tx member-row))
                                    ;; An admitted, unstarted member covers an
                                    ;; identical concurrent request.
                                    (= (admission-scope row) (scope-of run-eid)))
                              [test-symbol (select-keys member-row [:db/id :seon.test.member/symbol
                                                             :seon.test.member/reasons])]))))
                  (sort-by first > candidates))
            covered (keep #(get existing (:seon.test.member/symbol %)) members)
            coverage (into (set (:seon.test.run/covered-by request)) (map :db/id) covered)
            reserved (remove #(get existing (:seon.test.member/symbol %)) members)]
        [(cond-> (assoc row :seon.test.run/selection-tx "datomic.tx")
           (seq reserved) (assoc :seon.test.run/members (vec reserved))
           (seq coverage) (assoc :seon.test.run/covered-by coverage))]))))

(defn reach-digest
  "Digest this test's source, transitively reached definitions and named schemas."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.test/sym]
                  [:or :seon.test/reach-digest :seon.error/value]]}
  [database test-symbol]
  (let [result (runner/reach-digests database [test-symbol])]
    (if (and (map? result) (contains? result :seon.error/at) (contains? result :seon.error/layer) (contains? result :seon.error/operation)) result (get result test-symbol))))

(declare verified?)

(defn recorded-result
  "Read this branch's latest admitted test result, or name missing evidence."
  {:malli/schema [:=> [:cat :seon.db/database-value :qualified-symbol]
                  [:or :seon.test/result :seon.test/execution-error :seon.test/unknown-error]]}
  [database test-symbol]
  (let [results (runner/latest-results database [test-symbol])]
    (if (:seon.error/at results) results
        (or (first results) (unknown test-symbol "No admitted execution is recorded for this test.")))))

(defn- stale-in
  {:malli/schema [:=> [:cat :seon.db/database-value [:sequential :qualified-symbol]]
                  [:or [:vector :qualified-symbol] :seon.test/execution-error]]}
  [database test-symbols]
  (reduce (fn [result test-symbol]
            (let [verified (verified? database test-symbol)]
              (cond
                (:seon.error/at verified)
                (reduced (assoc verified :seon.test/execution-refusal :seon.test/sym))
                (true? verified) result
                :else (conj result test-symbol))))
          [] test-symbols))

(defn stale
  "Source-bearing tests with no result or a changed reach digest.
  Declared fixture observations are always stale. The named arity answers
  for exactly the supplied tests, so a caller holding its own set never
  digests the whole population to learn which of them must run again."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/database-value]
     [:or [:vector :seon.test/sym] :seon.test/execution-error]]
    [:=> [:cat :seon.db/database-value [:sequential :seon.test/sym]]
     [:or [:vector :seon.test/sym] :seon.test/execution-error]]]}
  ([database]
   (let [symbols (db/q '[:find [?s ...] :where
                         [?t :seon.test/sym ?s] [?t :seon.test/source]] database)]
     (if (:seon.db/invalid-read symbols)
       (assoc symbols :seon.test/execution-refusal :seon.test/sym)
         (stale-in database (vec (sort symbols))))))
  ([database test-symbols]
   (if (empty? test-symbols)
     []
     (stale-in database (vec (sort (distinct test-symbols)))))))

(defn resolve-test
  "Resolve an admitted test in the host or acquired SCI program by provenance."
  {:malli/schema [:=> [:cat :seon.test/resolution-request]
                  [:or :seon.test/var :seon.test/resolution-error :seon.test/not-runnable-error :seon.test.run/unavailable-error :seon.db/invalid-read-error :seon.schema/missing-projection-error
                   :seon.sci.eval/row-acquisition-error :seon.sci.eval/interpretation-error]]}
  [{database :seon.db/db test-symbol :seon.test/identity
    ctx :seon.sci.eval/ctx loader :seon.test/class-loader
    projection :seon.schema/projection}]
  (let [refuse (fn [kind message observed]
                 (assoc {:seon.error/at (java.util.Date.)
                   :seon.error/layer :seon.test/resolution
                   :seon.error/operation 'seon.test/resolve-test
                   :seon.error/message message
                   :seon.error/expected :admitted-executable-test
                   :seon.error/offending observed
                   :seon.error/data {:seon.test.run/basis-t (db/basis-t database)}
          :seon.test/error-test-symbol test-symbol} :seon.test/resolution-refusal kind))
        row (db/pull database
                     '[:db/id :seon.test/source :seon.schema.admission/source
                       :seon.program/analyzed-source-digest :seon.fn/file
                       {:seon.test/ns [:seon.ns/name]}]
                     [:seon.test/sym test-symbol])
        acquired (sci.eval/acquired-program ctx)
        ;; The acquisition owner answers whether this context holds the tested
        ;; program (same value, or equal program revisions at another commit).
        acquired? (sci.eval/acquired-database? ctx database)
        source (:seon.test/source row)
        admission (:seon.schema.admission/source row)]
    (cond
      (and (map? row) (contains? row :seon.error/at) (contains? row :seon.error/layer) (contains? row :seon.error/operation)) row
      (not (:db/id row))
      (refuse :seon.test/identity-unresolved "The admitted test has no current row." test-symbol)
      (or (not (string? source))
          (not= (symbol (namespace test-symbol))
                (get-in row [:seon.test/ns :seon.ns/name]))
          ;; Analysis covers the resolver context, not just declaration text.
          ;; Acquired program equality below verifies the complete admitted facts.
          (nil? (:seon.program/analyzed-source-digest row)))
      (refuse :seon.test/provenance-unknown "The test lacks matching source, namespace or analysis evidence." row)
      (not acquired?)
      (refuse :seon.test/program-mismatch "The SCI context did not acquire the tested program."
              {:seon.source/commit-id (db/commit-id database)
               :seon.test/acquired-commit-id (some-> (:seon.db/db acquired) db/commit-id)})
      (seq (:seon.test/acquisition-refusals acquired))
      (first (:seon.test/acquisition-refusals acquired))
      (not (or (= :agent admission) (and (= :core admission) (:seon.fn/file row))))
      (refuse :seon.test/provenance-unknown "The test has no executable admission provenance." row)
      :else
      (try
        (let [test-var (schema/call-with-projection
                        projection
                        #(if (= :agent admission)
                           (sci/resolve ctx test-symbol)
                           (with-test-loader loader (fn [] (requiring-resolve test-symbol)))))]
          (if (and (runner/var-reference? test-var) (ifn? (:test (meta test-var))))
            test-var
            {:seon.error/at (java.util.Date.) :seon.error/layer :seon.test/resolution
             :seon.error/operation 'seon.test/resolve-test
             :seon.test/not-runnable (str test-symbol)
             :seon.error/message "The admitted identity has no executable test Var."}))
        (catch LinkageError failure
          (refuse :seon.test/classpath-incompatible
                  (or (ex-message failure) (.getName (class failure))) test-symbol))
        (catch Exception failure
          (refuse :seon.test/classpath-unavailable
                  (or (ex-message failure) (.getName (class failure))) test-symbol))))))

(defn owned-symbols
  "Read the test symbols declared in the calling agent's assigned namespace."
  {:malli/schema [:=> [:cat :my.plan/request] [:vector :seon.test/sym]]}
  [{database :seon.db/db agent-id :seon.agent/id}]
  (vec (sort (db/q '[:find [?symbol ...] :in $ ?agent-id
                     :where [?agent :seon.agent/id ?agent-id]
                            [?agent :seon.agent/namespace ?namespace]
                            [?test :seon.test/ns ?namespace]
                            [?test :seon.test/sym ?symbol]]
                   database agent-id))))

(defn verified?
  "Whether the latest admitted native result is green for the requested program.
  Missing execution is false; unavailable recorded evidence remains a refusal."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/database-value :seon.test/sym]
     [:or :boolean :seon.test/host-error]]
    [:=> [:cat :seon.db/database-value :seon.test/sym :seon.test.run/program-digest]
     [:or :boolean :seon.test/host-error]]]}
  ([database test-symbol]
   (let [result (recorded-result database test-symbol)]
     (cond
       (:seon.test/unknown result) false
       (:seon.error/at result) result
       (not (and (pos? (:seon.test/pass-count result))
                 (zero? (:seon.test/fail-count result))
                 (zero? (:seon.test/error-count result)))) false
       :else
       (let [inputs (db/q '[:find [?digest ...]
                            :where [_ :seon.source/test-input-digest ?digest]] database)
             current (runner/reach-digests database [test-symbol])
             tested (runner/reach-digests
                     (db/as-of database (:seon.test.run/basis-t result)) [test-symbol])
             present (db/pull database [:seon.test/source :seon.test/fixture-observation]
                              [:seon.test/sym test-symbol])]
         (or (first (filter :seon.error/at [inputs current tested present]))
             (boolean
              (and (:seon.test/source present)
                   (not (:seon.test/fixture-observation present))
                   (= #{(:seon.test.run/input-digest result)} (set inputs))
                   (string? (get current test-symbol))
                   (= (get current test-symbol) (get tested test-symbol)))))))))
  ([database test-symbol program-digest]
   (let [result (recorded-result database test-symbol)]
     (cond
       (:seon.test/unknown result) false
       (:seon.error/at result) result
       :else (and (pos? (:seon.test/pass-count result))
                  (zero? (:seon.test/fail-count result))
                  (zero? (:seon.test/error-count result))
                  (= program-digest (:seon.test.run/program-digest result)))))))
;;; ---------------------------------------------------------------------------
;;; One test request: a test is an isolated agent that lives for one body
;;; ---------------------------------------------------------------------------

;;; LOAD-CYCLE BOUNDARY. `seon.cluster.agent` requires `seon.turn`, which
;;; requires `seon.plan`, which requires this namespace. One resolution per
;;; entrance Var, realized at first use.
(def ^:private acquire-context!
  (delay (requiring-resolve 'seon.cluster.agent/acquire-context!)))

(def ^:private release-context!
  (delay (requiring-resolve 'seon.cluster.agent/release-context!)))

(defn- request-refusal
  "Name one refused request member with the value it carried."
  {:malli/schema [:=> [:cat :keyword :string :seon.schema/value] :seon.test/selection-error]}
  [kind message offending]
  {:seon.error/at (java.util.Date.)
   :seon.error/layer :seon.test/selection
   :seon.error/operation 'seon.test/run
   :seon.error/message message
   :seon.error/expected :seon.test/request
   :seon.error/offending offending
   :seon.test/selection-refusal kind})

(defn- host-exclusions
  "Members that never execute in a JVM operating the development checkout.

  A member reaching a `:seon.fn/destroys` owner deletes a filesystem path it
  did not create; a member declaring `:seon.test/fixture-observation` needs a
  file-backed root. Both run on the platform host, whose command is named.
  Any other JVM (a scratch or platform root) excludes nothing."
  {:malli/schema [:=> [:cat :seon.db/database-value [:or :nil :string] [:vector :seon.test/sym]]
                  [:or [:map [:seon.test/destructive-excluded :seon.test/destructive-excluded]
                        [:seon.test/deferred :seon.test/deferred]]
                   :seon.error/value]]}
  [database declared-root symbols]
  (if-not (development-root declared-root)
    {:seon.test/destructive-excluded [] :seon.test/deferred []}
    (let [owners (destroyers database)
          reach (if (:seon.error/at owners) owners (destructive-reach database))]
      (if (:seon.error/at reach)
        reach
        (let [paths (into {} (map (fn [owner] [owner (destructive-paths database owner)]))
                          (distinct (keep reach symbols)))
              destructive (into [] (keep #(destructive-exclusion owners reach paths %)) symbols)
              excluded (set (map :seon.test/sym destructive))
              observations (into {} (selection-read!
                                     (db/q '[:find ?symbol ?reason
                                             :in $ [?symbol ...]
                                             :where [?test :seon.test/sym ?symbol]
                                                    [?test :seon.test/fixture-observation ?reason]]
                                           database symbols)))
              deferred (into []
                             (keep (fn [test-symbol]
                                     (when-let [reason (and (not (excluded test-symbol))
                                                            (get observations test-symbol))]
                                       {:seon.test/sym test-symbol
                                        :seon.test/fixture-observation reason
                                        :seon.test/command ["bin/test" "--platform" "--" (str test-symbol)]})))
                             symbols)]
          {:seon.test/destructive-excluded
           (mapv #(assoc % :seon.test/command ["bin/test" "--platform" "--" (str (:seon.test/sym %))])
                 destructive)
           :seon.test/deferred deferred})))))

(defn- member-result
  "Execute one admitted member on its own branch off the captured commit.

  The branch, its connection and its forked context come from the agent
  entrance, exactly as an isolated agent's do. The body runs on its own thread;
  the branch is released and unlinked only after that thread has been observed
  to exit. Every cleanup is attempted even when setup or another cleanup
  fails, and each failure's cause chain reaches the member's recorded result.
  A body still live at the bound keeps its branch; `settle!` records its
  termination (and any cleanup failure) once the watcher observes its exit."
  {:malli/schema [:=> [:cat :seon.agent/execution-handle :seon.test/sym [:int {:min 1}]
                       [:=> [:cat :seon.test.runner/captured-result] :nil]]
                  [:map [:seon.test/result :seon.test.runner/captured-result]
                   [:seon.test/timings
                    [:map [:seon.agent/branch :seon.agent/branch]
                     [:seon.test.timing/acquire-ms :seon.test.timing/acquire-ms]
                     [:seon.test.timing/resolve-ms :seon.test.timing/resolve-ms]
                     [:seon.test.timing/run-ms :seon.test.timing/run-ms]
                     [:seon.test.timing/release-ms :seon.test.timing/release-ms]]]]]}
  [request-handle test-symbol remaining-ms settle!]
  (let [elapsed (fn [started] (/ (- (System/nanoTime) started) 1000000.0))
        started (System/nanoTime)
        child (@acquire-context! request-handle nil
                                 {:seon.agent/isolate? true
                                  :seon.cluster.registry/from (:seon.source/commit-id request-handle)})
        acquired (elapsed started)
        release-ms (volatile! 0.0)
        cleanup-failure (volatile! nil)
        failed (fn [message]
                 {:seon.test/sym test-symbol
                  :seon.test.member/began? false :seon.test.member/ended? false
                  :seon.test.run/terminated? true
                  :seon.test/pass-count 0 :seon.test/fail-count 0 :seon.test/error-count 1
                  :seon.test/failure-message message})
        with-cleanup-failure
        (fn [result]
          (if-let [failure @cleanup-failure]
            (-> result
                (update :seon.test/error-count (fnil inc 0))
                (update :seon.test/failure-message
                        #(str (when % (str % "\n"))
                              "Member cleanup failed; its branch "
                              (:seon.agent/branch child) " may remain:\n"
                              (throwable-text failure))))
            result))
        ;; Owned by this function until `bounded-result` takes it.
        release! (fn [_watched?]
                   (let [started (System/nanoTime)]
                     (try (@release-context! child)
                          (catch Throwable failure (vreset! cleanup-failure failure)))
                     (vreset! release-ms (elapsed started)))
                   nil)
        handed? (volatile! false)
        timing (fn [resolved executed]
                 {:seon.agent/branch (:seon.agent/branch child)
                  :seon.test.timing/acquire-ms acquired
                  :seon.test.timing/resolve-ms resolved
                  :seon.test.timing/run-ms executed
                  :seon.test.timing/release-ms @release-ms})]
    (try
      (let [started (System/nanoTime)
            ctx (:seon.sci.eval/ctx child)
            test-var (resolve-test
                      {:seon.db/db (:seon.db/db child)
                       :seon.db/connection (:seon.db/connection child)
                       :seon.test/identity test-symbol
                       :seon.sci.eval/ctx ctx
                       :seon.schema/projection (:seon.schema/projection child)
                       :seon.test/class-loader (or (:seon.test/class-loader (sci.eval/acquired-program ctx))
                                                   (clojure.lang.RT/baseLoader))})
            resolved (elapsed started)
            started (System/nanoTime)
            ;; A member over one second reports what the armed definitions did.
            profile-mark (profile/begin)
            registry-before (runner/live-cluster-schema-states)
            result (if (:seon.error/at test-var)
                     (failed (:seon.error/message test-var))
                     (do
                       (vreset! handed? true)
                       (schema/call-with-projection
                        (:seon.schema/projection child)
                        ;; An agent (SCI) test is the member branch's own work,
                        ;; so its elided `seon.db` arities reach that branch. A
                        ;; host test body inherits no custody, as a JVM test body
                        ;; always has; its fixtures find the member through
                        ;; `*member*`.
                        #(bounded-result
                          test-var remaining-ms
                          (if (var? test-var)
                            {:seon.sci.eval/ctx ctx :seon.test/member child}
                            {:seon.sci.eval/ctx ctx
                             :seon.db/connection (:seon.db/connection child)})
                          (fn [watched?]
                            (release! watched?)
                            (when watched?
                              (settle! (with-cleanup-failure
                                        (assoc (failed (str "Test " test-symbol
                                                            " exited after its request bound."))
                                               :seon.test.run/terminated? true))))
                            nil)))))
            executed (elapsed started)
            explanation (profile/explain-slow profile-mark)
            drifted (runner/schema-restore-drift
                     (runner/restore-live-cluster-schema! registry-before))
            result (if (empty? drifted)
                     result
                     (-> result
                         (update :seon.test/error-count (fnil inc 0))
                         (update :seon.test/failure-message
                                 #(str (when % (str % "\n"))
                                       "Live cluster schema registry changed and was restored: "
                                       (pr-str drifted)))))]
        (when-not @handed? (vreset! handed? true) (release! false))
        {:seon.test/result (cond-> (with-cleanup-failure result)
                             (not (:seon.test.run/terminated? result))
                             (assoc :seon.agent/branch (:seon.agent/branch child))
                             explanation
                             (assoc :seon.profile/explanation explanation))
         :seon.test/timings (timing resolved executed)})
      (catch Throwable failure
        (when-not @handed? (release! false))
        (when-let [cleanup @cleanup-failure] (.addSuppressed failure cleanup))
        (throw failure)))))

(defn isolated-members
  "The tests a development-root JVM never runs, which the platform host does:
  the declared `:seon.test/platform` rows, members reaching a
  `:seon.fn/destroys` owner, and members declaring a
  `:seon.test/fixture-observation`. Fixture material is never a member. An
  unanswerable destroyer declaration is the typed unknown."
  {:malli/schema [:=> [:cat :seon.db/database-value]
                  [:or [:set :seon.test/sym] :seon.error/value]]}
  [database]
  (let [reach (destructive-reach database)]
    (if (:seon.error/at reach)
      reach
      (let [declared (selection-read!
                      (db/q '[:find [?symbol ...]
                              :where (or [?test :seon.test/platform]
                                         [?test :seon.test/fixture-observation])
                                     [?test :seon.test/sym ?symbol]
                                     (not [?test :seon.test/fixture])]
                            database))
            material (set (selection-read!
                           (db/q '[:find [?symbol ...]
                                   :where [?test :seon.test/fixture] [?test :seon.test/sym ?symbol]]
                                 database)))]
        (into (set declared) (remove material) (keys reach))))))

(defn declared-bound-ms
  "The sum of the named tests' declared body bounds: `:seon.test/long-ms` where
  declared, the ordinary 5,000 ms otherwise. A request over them is bounded by
  what its members declare, never by a blanket allowance."
  {:malli/schema [:=> [:cat :seon.db/database-value [:set :seon.test/sym]] [:int {:min 1}]]}
  [database symbols]
  (let [declared (into {} (selection-read!
                           (db/q '[:find ?symbol ?ms :in $ [?symbol ...]
                                   :where [?test :seon.test/sym ?symbol] [?test :seon.test/long-ms ?ms]]
                                 database (vec symbols))))]
    (max 1 (reduce + 0 (map #(get declared % 5000) symbols)))))

(def batch-limit
  "The declared bound on members per admission and per release transaction.
  A request over more members admits, runs and releases one batch at a time,
  each its own run, so no transaction's size grows with the request."
  64)

(defn- green?
  {:malli/schema [:=> [:cat :seon.test/result] :boolean]}
  [result]
  (and (pos? (:seon.test/pass-count result 0))
       (zero? (:seon.test/fail-count result 0))
       (zero? (:seon.test/error-count result 0))))

(defn run
  "Answer one test request on the execution value it names.

  `:seon.test/execution` is an agent context source or execution handle; the
  request captures its current commit once. `:seon.test/policy` selects
  eligibility (`:named` needs identities or namespaces; `:incremental` derives
  changed reach and outstanding obligations from recorded evidence; `:platform`
  the declared rows; `:all` every eligible row). Selection and admission read
  and write the explicit `:seon.test/recording-connection`, which must hold the
  execution branch. A member whose recorded green still holds is answered from
  the record with `:seon.test/unchanged` and no execution.

  Each executed member is an isolated agent for one body: a fresh branch off
  the captured commit through `seon.cluster.agent/acquire-context!`, the body
  under that branch's custody (`seon.sci.eval/run-test`), its result recorded
  on the recording connection, and the branch unlinked by
  `release-context!` after the body's thread exits. The request is bounded by
  `:seon.test/check-time-limit-ms` (request member or the cluster's dial); a
  body still live at the bound fails, keeps its branch, and admits no further
  body. Members reaching a destructive owner or declaring a file-backed fixture
  are excluded in a JVM operating the development checkout, with the platform
  command. `:seon.test/passed?` is true only when every obligation has green
  evidence: exclusions, pending and unfinished members are unfulfilled."
  {:malli/schema [:=> [:cat :seon.test/request]
                  [:or :seon.test/run-result :seon.test/selection-error :seon.test/unknown-error
                   :seon.test/admission-error :seon.test.run/unavailable-error
                   :seon.config/error :seon.db.write/error :seon.db/invalid-read-error
                   :seon.schema/missing-projection-error]]}
  [{execution :seon.test/execution recording :seon.test/recording-connection
    policy :seon.test/policy identities :seon.test/identities
    namespaces :seon.test/namespaces :as request}]
  (let [started (System/nanoTime)
        elapsed #(/ (- (System/nanoTime) started) 1000000.0)
        cluster (:seon.cluster/name execution)
        database (db/db (:seon.db/connection execution))
        held-store (or (:seon.store/store execution) (:seon.store/store (env/of execution)))
        effective (config/effective database cluster)
        bound (or (:seon.test/check-time-limit-ms request)
                  (:seon.test/check-time-limit-ms effective))]
    (cond
      (:seon.error/at effective) effective
      (and (= :named policy) (empty? identities) (empty? namespaces)
           (empty? (:seon.test/changed request)))
      (request-refusal :seon.test/policy-unscoped
                       "A :named request needs :seon.test/identities, :seon.test/namespaces or :seon.test/changed."
                       (select-keys request [:seon.test/policy]))
      (not (pos-int? bound))
      (unknown :seon.test/check-time-limit-ms
               "Apply cluster configuration to supply the declared test check time limit.")
      :else
      (let [selection (selection-admission
                       (cond-> {:seon.db/db database
                                :seon.test.run/cluster [:seon.cluster/name cluster]
                                :seon.test.run/policy policy
                                :seon.test/include-long? (true? (:seon.test/include-long? request))}
                         (seq identities) (assoc :seon.test/identities (set identities))
                         (seq namespaces) (assoc :seon.test/namespaces (set namespaces))
                         (seq (:seon.test/changed request))
                         (assoc :seon.test/changed (vec (:seon.test/changed request)))
                         ;; The held store's published source head, which adoption may not have reached.
                         held-store
                         (assoc :seon.test/loaded-source
                                (:seon.source/commit-id (source/current held-store)))))
            selected (when-not (:seon.error/at selection)
                       (mapv :seon.test.member/symbol (:seon.test.run/members selection)))
            exclusions (when selected
                         (host-exclusions database (store/declared-operator-root) selected))
            runnable (when (and selected (not (:seon.error/at exclusions)))
                       (let [excluded (set (map :seon.test/sym
                                                (concat (:seon.test/destructive-excluded exclusions)
                                                        (:seon.test/deferred exclusions))))]
                         (vec (remove excluded selected))))
            provenance (:seon.test.run/provenance selection)
            exclusion-rows
            (when runnable
              (vec (concat
                    (for [entry (:seon.test/long-excluded selection)]
                      {:seon.test.member/symbol (:seon.test/sym entry)
                       :seon.test.selection/disposition :long})
                    (for [entry (:seon.test/destructive-excluded exclusions)]
                      {:seon.test.member/symbol (:seon.test/sym entry)
                       :seon.test.selection/disposition :destructive})
                    (for [entry (:seon.test/deferred exclusions)]
                      {:seon.test.member/symbol (:seon.test/sym entry)
                       :seon.test.selection/disposition :deferred}))))]
        (cond
          (:seon.error/at selection) selection
          (:seon.error/at exclusions) exclusions
          :else
          (let [platform? (fn [test-symbol]
                            (some #(and (= test-symbol (:seon.test.member/symbol %))
                                        ((:seon.test.member/reasons %) :platform))
                                  (:seon.test.run/members selection)))
                ordered (sort-by (fn [test-symbol] [(if (platform? test-symbol) 0 1) test-symbol])
                                 runnable)
                ;; Admission, execution and release proceed one bounded batch
                ;; at a time: no transaction carries more than `limit` members,
                ;; and a batch is admitted only when its turn comes.
                limit (or (:seon.test/batch-limit request) batch-limit)
                batches (vec (partition-all limit ordered))
                deadline (+ started (* 1000000 bound))
                remaining-ms #(quot (- deadline (System/nanoTime)) 1000000)
                recording-completion
                (fn [batch-provenance results terminated?]
                  {:seon.db/db database
                   :seon.test.runner/results results
                   :seon.test/run-basis-t (:seon.test.run/basis-t batch-provenance)
                   :seon.test/run-at (:seon.test.run/at batch-provenance)
                   :seon.test.run/provenance batch-provenance
                   :seon.test.run/terminated? terminated?})
                admit! (fn [index members]
                         (let [batch-provenance (cond-> provenance
                                                  (pos? index) (assoc :seon.test.run/id (id/id)))
                               row (cond-> (assoc selection
                                                  :seon.test.run/provenance batch-provenance
                                                  :seon.test.run/members
                                                  (filterv #((set members) (:seon.test.member/symbol %))
                                                           (:seon.test.run/members selection)))
                                     (and (zero? index) (seq exclusion-rows))
                                     (assoc :seon.test.run/exclusions exclusion-rows)
                                     (pos? index) (dissoc :seon.test.run/covered-by))
                               report (db/transact! recording [[:db.fn/call admit-run row]])]
                           (if (:seon.error/at report)
                             report
                             {::provenance batch-provenance
                              ;; Execute only what THIS batch's writer reserved:
                              ;; a member another admitted run still holds
                              ;; (unfinished, or not yet exited) is covered
                              ;; there, never run twice at once.
                              ::reserved (set (db/q '[:find [?symbol ...] :in $ ?run-id
                                                      :where [?run :seon.test.run/id ?run-id]
                                                             [?run :seon.test.run/members ?member]
                                                             [?member :seon.test.member/symbol ?symbol]]
                                                    (:db-after report)
                                                    (:seon.test.run/id batch-provenance)))})))
                request-handle (when (seq ordered)
                                 (@acquire-context! execution nil
                                                    {:seon.agent/isolate? true
                                                     :seon.cluster.registry/from (db/commit-id database)}))
                run-batch
                (fn [outcome batch-provenance members]
                  (let [settle! (fn [result]
                                  ;; A later observation of termination adds its
                                  ;; transaction without changing the recorded outcome.
                                  (runner/commit-results!
                                   recording (recording-completion batch-provenance [result] true))
                                  nil)]
                    ;; A throw leaves no admitted member open: each unrecorded
                    ;; member is recorded red with the throwable, which is rethrown.
                    (try
                    (loop [remaining members outcome outcome]
                      (if-let [test-symbol (first remaining)]
                        (if-not (pos? (remaining-ms))
                          (assoc outcome ::stopped (vec remaining))
                          (let [{result :seon.test/result timings :seon.test/timings}
                                (member-result request-handle test-symbol (remaining-ms) settle!)
                                committed (runner/commit-results!
                                           recording
                                           (recording-completion
                                            batch-provenance [(dissoc result :seon.agent/branch)]
                                            (true? (:seon.test.run/terminated? result))))
                                outcome (-> outcome
                                            (update :seon.test/timings conj
                                                    (assoc timings :seon.test/sym test-symbol))
                                            (update :seon.test/results conj
                                                    (if (:seon.error/at committed)
                                                      (assoc result :seon.test/recording-refusal committed)
                                                      (merge result (first committed)))))]
                            (cond
                              (:seon.error/at committed)
                              (assoc outcome :seon.test/recording-refusal committed
                                             ::stopped (vec (next remaining)))
                              (not (:seon.test.run/terminated? result))
                              (assoc outcome :seon.test/unfinished [result]
                                             ::stopped (vec (next remaining)))
                              (and (platform? test-symbol) (not (green? result)))
                              (assoc outcome ::stopped (vec (next remaining)))
                              :else (recur (next remaining) outcome))))
                        outcome))
                    (catch Throwable failure
                      (try
                        (let [recorded (runner/record-interrupted!
                                        recording (recording-completion batch-provenance [] true) failure)]
                          (when (:seon.error/at recorded)
                            (.addSuppressed failure (ex-info (:seon.error/message recorded) recorded))))
                        (catch Throwable recording-failure
                          (.addSuppressed failure recording-failure)))
                      (throw failure)))))
                release-not-started!
                ;; A reserved member a batch never started is released as an
                ;; unfulfilled obligation (one transaction of at most `limit`),
                ;; never left to cover later requests.
                (fn [batch-provenance members]
                  (when (seq members)
                    (runner/commit-results!
                     recording
                     (recording-completion
                      batch-provenance
                      (mapv (fn [test-symbol]
                              {:seon.test/sym test-symbol
                               :seon.test.member/began? false :seon.test.member/ended? false
                               :seon.test/pass-count 0 :seon.test/fail-count 0
                               :seon.test/error-count 1
                               :seon.test/failure-message
                               (str "Not started: request " (:seon.test.run/id provenance)
                                    " stopped before this member.")})
                            members)
                      true))))
                outcome
                (try
                  (loop [index 0
                         outcome {:seon.test/results [] :seon.test/timings []
                                  ::held [] :seon.test/pending []}]
                    (if-let [members (get batches index)]
                      (if-not (pos? (remaining-ms))
                        (update outcome :seon.test/pending into (mapcat identity (subvec batches index)))
                        (let [admitted (admit! index members)]
                          (if (:seon.error/at admitted)
                            (assoc outcome ::admission-refusal admitted
                                           :seon.test/pending
                                           (into (:seon.test/pending outcome)
                                                 (mapcat identity (subvec batches index))))
                            (let [reserved (::reserved admitted)
                                  outcome (-> (update outcome ::held into (remove reserved members))
                                              (run-batch (::provenance admitted) (filterv reserved members)))
                                  stopped (::stopped outcome)
                                  released (release-not-started! (::provenance admitted) stopped)
                                  outcome (cond-> (dissoc outcome ::stopped)
                                            (:seon.error/at released)
                                            (update :seon.test/recording-refusal #(or % released)))]
                              (if (some? stopped)
                                (update outcome :seon.test/pending into
                                        (concat stopped (mapcat identity (subvec batches (inc index)))))
                                (recur (inc index) outcome))))))
                      outcome))
                  (finally
                    (when request-handle (@release-context! request-handle))))
                held-elsewhere (::held outcome)
                reused (vec (:seon.test.selection/unchanged selection))
                results (into (vec (:seon.test/results outcome)) reused)
                ;; Every exclusion is an unfulfilled obligation, a declared
                ;; long test included: it never reads as green.
                excluded (concat (:seon.test/destructive-excluded exclusions)
                                 (:seon.test/deferred exclusions)
                                 (:seon.test/long-excluded selection))
                pending (into held-elsewhere (:seon.test/pending outcome))]
            (if-let [refusal (and (empty? (:seon.test/results outcome)) (::admission-refusal outcome))]
              refusal
            (cond-> {:seon.test.run/id (:seon.test.run/id provenance)
                     :seon.test.run/policy policy
                     :seon.test.run/basis-t (:seon.test.run/basis-t provenance)
                     :seon.test.run/program-digest (:seon.test.run/program-digest provenance)
                     :seon.source/commit-id (db/commit-id database)
                     :seon.test/results results
                     :seon.test/executed-count (count (:seon.test/results outcome))
                     :seon.test/reused-count (count reused)
                     :seon.test/timings (:seon.test/timings outcome)
                     :seon.test/passed?
                     (boolean (and (every? green? results)
                                   (seq results)
                                   (empty? excluded)
                                   (empty? pending)
                                   (empty? (:seon.test/unfinished outcome))
                                   (nil? (:seon.test/recording-refusal outcome))))
                     :seon.test/elapsed-ms (elapsed)}
              (seq (:seon.test/destructive-excluded exclusions))
              (assoc :seon.test/destructive-excluded (:seon.test/destructive-excluded exclusions))
              (seq (:seon.test/deferred exclusions))
              (assoc :seon.test/deferred (:seon.test/deferred exclusions))
              (seq (:seon.test/long-excluded selection))
              (assoc :seon.test/long-excluded (:seon.test/long-excluded selection))
              (seq pending)
              (assoc :seon.test/pending pending)
              (:seon.test/loaded-source-drift selection)
              (assoc :seon.test/loaded-source-drift (:seon.test/loaded-source-drift selection))
              (seq (:seon.test/unfinished outcome))
              (assoc :seon.test/unfinished (:seon.test/unfinished outcome))
              (:seon.test/recording-refusal outcome)
              (assoc :seon.test/recording-refusal (:seon.test/recording-refusal outcome))
              (::admission-refusal outcome)
              (assoc :seon.test/recording-refusal (::admission-refusal outcome))))))))))

(defn tally
  "Render one request's answer as the text a launcher prints.
  The recorded counts come from the run's result; failures show their claims."
  {:malli/schema [:=> [:cat [:or :seon.test/run-result :seon.error/value]] :string]}
  [result]
  (if (:seon.error/at result)
    (str "test request refused: " (:seon.error/message result))
    (let [results (:seon.test/results result)
          red (remove green? results)]
      (str "run " (:seon.test.run/id result)
           " / executed " (:seon.test/executed-count result)
           " / reused " (:seon.test/reused-count result)
           " / pass " (reduce + 0 (map #(:seon.test/pass-count % 0) results))
           " / fail " (reduce + 0 (map #(:seon.test/fail-count % 0) results))
           " / error " (reduce + 0 (map #(:seon.test/error-count % 0) results))
           " / " (Math/round (double (:seon.test/elapsed-ms result))) " ms"
           " / passed? " (:seon.test/passed? result)
           (apply str (for [failure red]
                        (str "\nred " (:seon.test/sym failure) ": " (failure-message failure))))
           (apply str (for [entry (:seon.test/unfinished result)]
                        (str "\nunfinished " (:seon.test/sym entry) " on branch "
                             (:seon.agent/branch entry) ": " (:seon.test/failure-message entry))))
           (when-let [drift (:seon.test/loaded-source-drift result)]
             (str "\nno reuse: source " (:seon.test/loaded-source drift)
                  " is published but not yet adopted; the cluster's program rows record "
                  (or (:seon.source/commit-id drift) "no source commit")
                  " (adopt the files to reuse recorded evidence)"))
           (when-let [pending (seq (:seon.test/pending result))]
             (str "\npending (not started): " (str/join " " pending)))
           (apply str (for [entry (concat (:seon.test/destructive-excluded result)
                                          (:seon.test/deferred result))]
                        (str "\nexcluded " (:seon.test/sym entry) " — run "
                             (str/join " " (:seon.test/command entry)))))
           (apply str (for [entry (:seon.test/long-excluded result)]
                        (str "\nlong " (:seon.test/sym entry) ": " (:seon.test/long entry))))
           (when-let [refusal (:seon.test/recording-refusal result)]
             (str "\nrecording refused: " (:seon.error/message refusal)))))))
