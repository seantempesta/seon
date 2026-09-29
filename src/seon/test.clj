(ns seon.test
  "One test request on the agent execution lifecycle, and the recorded evidence it reads."
  (:require
            [clojure.core.cache.wrapped :as cache]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [sci.core :as sci]
            [sci.lang]
            [clojure.core.async.flow :as-alias flow]
            [seon.config :as config]
            [seon.cluster.process :as cluster.process]
            [seon.cluster.registry :as registry]
            [seon.cluster.source :as source]
            [seon.cluster.store :as store]
            [seon.db :as db]
            [seon.env :as env]
            [seon.error.refusal]
            [seon.fault :as fault]
            [seon.fn :as functions]
            [seon.id :as id]
            [seon.instrument :as instrument]
            [seon.program :as program]
            [seon.profile :as profile]
            [seon.schema :as schema]
            [seon.sci.eval :as sci.eval]
            [seon.sci.kernel :as kernel]
            [seon.print.value :as print.value]
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
  (let [;; The shown text only: a stored whole value is the failure's
        ;; `...-blob` digest, data the reply carries, never a text hint.
        field (fn [inline] (get failure inline))
        path (or (:seon.test.failure/reported-file failure)
                 (get-in failure [:seon.test.failure/file :seon.fn.file/relative-path]))]
    (str/join "\n"
      (remove nil?
        [(str (name (:seon.test.failure/type failure))
              (when path (str " " path ":" (:seon.test.failure/line failure))))
         (when (seq (:seon.test.failure/testing-contexts failure))
           (str/join " > " (:seon.test.failure/testing-contexts failure)))
         (field :seon.test.failure/message)
         (when-let [expected (field :seon.test.failure/expected)]
           (str "expected: " expected))
         (when-let [actual (field :seon.test.failure/actual)]
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
    (let [entities (db/q '[:find ?selected ?basis
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
      (if (:seon.error/at entities)
        (unknown test-symbol (:seon.error/message entities))
        (if-let [[_ basis] (last (sort-by first entities))]
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

(def ^:dynamic *member*
  "The execution handle of the member whose host (JVM) test body runs on this
  thread, bound by `seon.test/run` around that body exactly as clojure.test
  binds `*testing-vars*`. Canonical fixtures branch off it. Nil outside a
  member body; an agent (SCI) body reaches its branch through custody instead."
  nil)

(def ^:private acquire-context!
  (delay (requiring-resolve 'seon.cluster.agent/acquire-context!)))

(defn- apply-seeds!
  "Apply the test Var's ordered `:seon.test/seed` to the member's connection.
  Maps are tx-data; a function maps the previous entry's database to `:seon.test.seed/value`
  (manifest via `config/apply!`, tx-data via the checked writer). The first refusal stops
  entry; else the member reacquires its own branch head through the agent entrance, so the
  held handle, its program and the commit canonical fixtures branch from are the prepared ones.
  Seeds that change the test's own `:seon.program/definition-digest` refuse (`:changes-test`):
  the body would run the Var resolved before them."
  {:malli/schema [:=> [:cat [:maybe :seon.agent/execution-handle] :seon.test/sym :seon.test/var]
                  [:or [:maybe :seon.agent/execution-handle] :seon.test.seed/preparation-error]]}
  [member test-symbol test-var]
  (let [refusal (fn [kind ordinal message offending]
                  (cond-> {:seon.error/at (java.util.Date.) :seon.error/layer :seon.test/seed :seon.error/operation `apply-seeds!
                           :seon.error/message message :seon.error/expected :seon.test.seed/value :seon.error/offending offending
                           :seon.test.seed/preparation kind :seon.test/error-test-symbol test-symbol} ordinal (assoc :seon.test.seed/ordinal ordinal)))
        connection (:seon.db/connection member) seeds (vec (:seon.test/seed (meta test-var)))]
    (cond
      (empty? seeds) member
      (nil? member) (refusal :no-member nil (str test-symbol " declares seeds but runs without a member.") seeds)
      :else
      (let [digest #(:seon.program/definition-digest
                     (db/pull (db/db connection) [:seon.program/definition-digest] [:seon.test/sym test-symbol]))
            before (digest)]
        (or
         (reduce-kv
         (fn [_ ordinal entry]
           ;; Each entry reads the head the previous one left on the member branch.
           (let [value (cond (map? entry) {:seon.test.seed/tx-data [entry]}
                             (vector? entry) {:seon.test.seed/tx-data entry}
                             :else (entry {:seon.db/db (db/db connection)}))
                 refuse #(reduced (refusal %1 ordinal (str "Seed entry " ordinal " of " test-symbol " could not prepare its world: " %2) %3))
                 {manifest :seon.config/manifest tx-data :seon.test.seed/tx-data} value
                 applied (delay (when manifest (config/apply! {:seon.db/connection connection :seon.config/manifest manifest
                                                               :seon.boot/cluster-name (:seon.cluster/name member)})))
                 written (delay (when (seq tx-data) (db/transact! connection tx-data)))]
             (cond
               (schema/explain-candidate-value (:seon.schema/projection member) :seon.test.seed/value value)
               (refuse :invalid-value "it does not return :seon.test.seed/value." value)
               (:seon.error/at @applied) (refuse :write-refused (:seon.error/message @applied) @applied)
               (:seon.error/at @written) (refuse :write-refused (:seon.error/message @written) @written)
               :else nil)))
         nil seeds)
         ;; The body runs the Var resolved before seeding; a seed redefining its test refuses.
         (let [after (digest)]
           (when (not= before after)
             (refusal :changes-test nil (str "Seeds of " test-symbol " change its own definition digest,"
                                             " so its body would run the pre-seed Var.") seeds)))
         (@acquire-context! member nil {:seon.agent/branch (:seon.agent/branch member)}))))))

(def ^:private attempt-writers
  (delay {:agent (requiring-resolve 'seon.cluster.agent/creation-tx)
          :turn (requiring-resolve 'seon.turn/open-tx)
          :context (requiring-resolve 'seon.effect/*request-context*)}))

(defn- attempt-request-context
  "Open an effect-reaching member's attempt agent and turn through the canonical writers (owner
  2026-09-25: no other world changes); return its evaluation's request context, else the refusal."
  {:malli/schema [:=> [:cat :seon.agent/execution-handle :seon.test/sym]
                  [:or :seon.effect/request-context :seon.error/value]]}
  [member test-symbol]
  (let [{:keys [agent turn]} @attempt-writers
        connection (:seon.db/connection member)
        agent-id (str "attempt-" (id/id)) turn-id (str agent-id "-turn")
        cluster-name (:seon.cluster/name member)
        ;; One transaction: Datahike applies its operations in order, so the
        ;; turn's lookup of the agent resolves the agent written before it
        ;; (each write is a whole validated commit, 350-440 ms on a member branch).
        written (db/transact! connection
                              (into (agent {:seon.agent/id agent-id :seon.ns/name (symbol (namespace test-symbol))
                                            :seon.cluster/name cluster-name :seon.agent/branch (:seon.agent/branch member)})
                                    (turn {:seon.turn/id turn-id :seon.turn/agent [:seon.agent/id agent-id]
                                           :seon.turn/opened-tx "datomic.tx"})))
        failed (when (:seon.error/at written) written)
        effective (when-not failed (config/effective (db/db connection) cluster-name))
        ctx (:seon.sci.eval/ctx member)]
    (cond failed failed
          (:seon.error/at effective) effective
          :else
      {:seon.db/connection connection :seon.agent/id agent-id :seon.turn/id turn-id
       :seon.cluster.eval/ordinal 0 :seon.boot/cluster-name cluster-name
       :seon.sci.admit/caps (config/result-caps effective)
       :seon.sci.eval/projection-state (:seon.sci.eval/projection-state (meta connection))
       :seon.env/environment (env/scope (env/of ctx) {:seon.agent/id agent-id :seon.turn/id turn-id
                                                      :seon.cluster.eval/ordinal 0})
       :seon.sci.eval/ctx ctx :seon.effect/counter (atom -1)})))

(defn- bounded-result
  "Observe the body thread's exit under the request bound; cancellation is not exit.

  The body thread reads `loaded`, the loaded program identity, then calls
  `resolve!` for the Var it runs and runs it; nothing waits for adoption. When
  the identity is unknown or no longer `since` (the request's, read before
  admission) nothing resolves or runs, and when another thread changes it
  before the end the result is discarded: either way the result carries
  `:seon.test.run/stopped :program-unknown` and no outcome.

  `release!` is called exactly once, with `true` when the body outlived the
  bound (from a watcher that joins the live thread) and `false` otherwise:
  at once when the body exits inside the bound, or before rethrowing when
  the body thread never started. The caller hands all cleanup to it."
  {:malli/schema [:=> [:cat :seon.sci.eval/loaded-identity :map :seon.test/sym
                       [:=> [:cat] [:or :seon.test/var :seon.error/value]] [:int {:min 1}]
                       [:map [:seon.db/connection {:optional true} :seon.db/connection]
                        [:seon.sci.eval/ctx :seon.sci.eval/ctx]
                        [:seon.test/member {:optional true} :seon.agent/execution-handle]
                        [:seon.test/effect-attempt? {:optional true} :boolean]]
                       [:=> [:cat :boolean] :nil]]
                  :seon.test.runner/captured-result]}
  [loaded since test-symbol resolve! timeout-ms custody release!]
  (let [started? (volatile! false)]
    (try
      (let [member (:seon.test/member custody)
            ;; A development adoption spanning this body makes it unknown.
            task (FutureTask.
                  ;; The body thread is the member branch's own work from its
                  ;; first step (resolution, seeds, the body): the custody its
                  ;; creator's bindings carried is replaced, never inherited.
                  (bound-fn []
                    (db/call-with-custody
                     (select-keys custody [:seon.db/connection])
                     (fn [] (sci.eval/call-detecting-program-change
                     loaded `bounded-result since
                     #(let [test-var (resolve!)
                            ;; Seeds prepare under the body's program-change detection.
                            member (if (:seon.error/at test-var) member
                                       (apply-seeds! member test-symbol test-var))
                            ;; Only an `effect/request*`-reaching member gets an evaluation.
                            context (when (and (:seon.test/effect-attempt? custody) (not (:seon.error/at member)))
                                      (attempt-request-context member test-symbol))]
                        (cond
                          (:seon.error/at test-var) test-var
                          (:seon.error/at member) member
                          (:seon.error/at context) context
                          :else
                          (with-bindings {(:context @attempt-writers) context}
                          (with-test-loader
                           (fn [] (if (var? test-var)
                              ;; A host body runs unarmed (it may evaluate its own SCI
                              ;; contexts, which another arm on this thread would
                              ;; refuse), under the custody of its member branch only,
                              ;; as its member's agent: its calls receive their
                              ;; supplied defaults from the member's world, the
                              ;; database from the custody in force at each call
                              ;; (`seon.instrument/calling-environment`), so a body
                              ;; that switches custody (a fixture branch) is
                              ;; supplied its own branch, never a start-of-body value.
                              (binding [*member* member
                                        kernel/*host-world* (:seon.sci.eval/ctx custody)]
                                (runner/run-var! test-var (select-keys member [:seon.db/connection])))
                              ;; The SCI arm interrupts interpreted bodies at the
                              ;; declared per-test bound; `duration-failures` fails
                              ;; any body that completes over it.
                              (sci.eval/run-test
                               (assoc (dissoc custody :seon.test/member :seon.test/effect-attempt?)
                                      :seon.test/var test-var
                                      :seon.sci.eval/time-limit-ms
                                      (or (:seon.test/long-ms
                                           (program/test-markers (meta test-var) (meta (:ns (meta test-var)))))
                                          5000))))))))))))))
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
                (cond (= :seon.cluster/adoption (:seon.error/layer result))
                      (assoc (failed (:seon.error/message result) true) :seon.test.run/stopped :program-unknown)
                      (:seon.error/at result) (failed (:seon.error/message result) true)
                      :else (assoc result :seon.test.run/terminated? true))))
            (do
              (watch-exit!)
              (failed (str "Test " test-symbol " did not exit within "
                           ":seon.test/check-time-limit-ms remainder " timeout-ms " ms; thread "
                           (.threadId thread) " remains live and keeps its branch until it exits. "
                           "This request starts no further body; later requests hold only this test until it exits.")
                      false)))
          (catch InterruptedException _
            (.interrupt (Thread/currentThread))
            (watch-exit!)
            (failed (str "Interrupted while awaiting actual exit of " test-symbol
                         "; thread " (.threadId thread) ".")
                    false))
          (catch java.util.concurrent.ExecutionException failure
            (failed (str "Test execution failed:\n" (print.value/throwable-text (or (ex-cause failure) failure)))
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
  (let [entities (db/q '[:find ?sym ?destroys
                     :where
                     [?function :seon.fn/destroys ?destroys]
                     [?function :seon.fn/sym ?sym]]
                   database)]
    (cond
      (and (map? entities) (contains? entities :seon.error/at) (contains? entities :seon.error/layer) (contains? entities :seon.error/operation)) entities
      (empty? entities)
      (unknown :seon.fn/destroys
               (str "No function in this program declares :seon.fn/destroys, "
                    "so an in-process run cannot tell whether a test deletes a "
                    "filesystem path it did not create. Republish the program "
                    "(bin/seon init --dev default), or declare the attribute "
                    "in the owner's own metadata at its definition."))
      :else (into {} entities))))

(defn- destructive-reach
  "Every test symbol whose reach includes a destructive owner, mapped to it.

  Membership is the shared `:seon.fn/calls` derivation `seon.fn/tests-reaching`,
  walked from each owner `destroyers` names."
  [database]
  (let [walk (fn []
                 (let [owners (destroyers database)]
                   (if (:seon.error/at owners)
                     owners
                     (let [by-owner (functions/gate-sets database (sort (keys owners)))]
                       (if (:seon.db/invalid-read by-owner)
                         by-owner
                         (reduce (fn [reached [owner tests]] (reduce #(assoc %1 %2 owner) reached tests))
                                 {} (sort by-owner)))))))
        program (runner/program-revisions database)
        ;; The walk also reads the data attributes declared edges name
        ;; (`:seon.fn/invokes` holders, `:seon.fn/reference-to` keys).
        edge-attributes (when program
                          (db/q '[:find [?attribute ...]
                                  :where (or [_ :seon.fn/invokes ?attribute]
                                             (and [?declaration :seon.fn/reference-to :seon.fn/sym]
                                                  [?declaration :seon.schema/key ?attribute]))]
                                database))
        memo-key (when (and program (vector? edge-attributes))
              [program (select-keys (get-in database [:cache-context :datahike.cache/attribute-revisions])
                                    edge-attributes)])
        memo (when memo-key
               (schema/projection-cache-value (db/carried-projection database) ::destructive-reach
                                              #(cache/lru-cache-factory {} :threshold 4)))]
    (if-not memo
      (walk)
      (let [reach @(cache/lookup-or-miss memo memo-key (fn [_] (delay (walk))))]
        (when (:seon.error/at reach) (cache/evict memo memo-key))
        reach))))

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
      (let [entity (db/pull database [:db/id] [:seon.test/sym test-symbol])]
        (cond
          (and (map? entity) (contains? entity :seon.error/at) (contains? entity :seon.error/layer) (contains? entity :seon.error/operation)) entity
          (nil? (:db/id entity))
          (unknown test-symbol
                   (str "No program entity declares the test " test-symbol
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
              ;; Both bound: an unbound attribute filtered by predicate cost
              ;; ~200 ms per call on any entity count, bound 2 ms (2026-09-28).
              (db/q '[:find ?entity ?attribute ?value
                      :in $ [?entity ...] [?attribute ...]
                      :where [?entity ?attribute ?value]]
                    database entities attributes))))

(defn- definition-digests
  "Read the canonical declaration identity stored by the producer."
  {:malli/schema [:=> [:cat :seon.db/database-value [:sequential :qualified-symbol]]
                  [:map-of :qualified-symbol :seon.source/digest]]}
  [database symbols]
  (into {}
        ;; One query per identity attribute: an `or` over both leaves the
        ;; attribute unbound, which scans (38 ms current, 180 ms as-of, against
        ;; about 1 ms with the attribute bound, 2026-09-26).
        (mapcat (fn [attribute]
                  (selection-read!
                   (db/q '[:find ?symbol ?digest
                           :in $ [?symbol ...] ?attribute
                           :where [?entity ?attribute ?symbol]
                                  [?entity :seon.program/definition-digest ?digest]]
                         database symbols attribute)))
                [:seon.fn/sym :seon.test/sym])))

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

(defn green-members
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
      (let [cluster-entity (when cluster (selection-read! (db/pull database [:db/id :seon.cluster/name] cluster)))
            cluster-id (:db/id cluster-entity)
            refuse! (fn [kind message observed]
                      (let [refusal (selection-refusal database kind message observed)]
                        (throw (ex-info message refusal))))
            _ (when (and cluster (not (:seon.cluster/name cluster-entity)))
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
            ;; Only this branch's runs: every reader below drops another
            ;; branch's, and an agent branch inherits all of its cluster's
            ;; (11.8k member facts read to select one test, 2026-09-28).
            run-ids (selection-read!
                     (if cluster
                       (db/q '[:find [?run ...] :in $ ?cluster ?branch
                               :where [?run :seon.test.run/cluster ?cluster]
                                      [?run :seon.test.run/branch ?branch]] database cluster-id branch)
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
                     :seon.test.run/loaded-commit-id
                     :seon.test.run/basis-t :seon.test.run/selection-tx
                     :seon.test.run/namespaces :seon.test.run/identities
                     :seon.test.run/members :seon.test.run/covered-by
                     :seon.test.member/symbol :seon.test.member/reasons
                     :seon.test.member/completed-tx :seon.test.member/terminated-tx
                     :seon.test.member/began? :seon.test.member/ended?
                     :seon.test.member/pass-count :seon.test.member/fail-count
                     :seon.test.member/error-count :seon.test.member/error
                     :seon.test.member/reach-digest])
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
            reaching #(if (seq %)
                        (set (selection-read! (functions/gate-sets {:seon.db/db database :seon.fn/seeds %})))
                        #{})
            ;; Tests reaching a change observed since the recorded evidence
            ;; cannot reuse it. An identity the request names as changed is no
            ;; such observation: its reach selects candidates, and an unchanged
            ;; reach digest still reuses their green.
            observed-reached (reaching observed-changed)
            requested-walk (reaching requested-changed)
            reached (into observed-reached requested-walk)
            work? (or first-run? (seq changed) (seq pending))
            ;; A named request's `:seon.test/changed` names the tests reaching
            ;; exactly those identities; changes observed since the last run
            ;; widen only incremental requests.
            requested-reached (if (= :named policy) requested-walk #{})
            ;; A first run widens to every test only when the request names
            ;; nothing: named changes, tests or namespaces select their reach.
            scoped? (or (seq requested-changed) (seq identities) (seq namespaces))
            candidates
            (if (or (and first-run? (not scoped?)) (#{:all :full} policy))
              (selection-read! (db/q '[:find [?symbol ...] :where [_ :seon.test/sym ?symbol]] database))
              ;; A test named as changed is itself a member; the walk from
              ;; it finds only tests reaching it.
              (into (into (into (into identities (if (= :named policy) requested-reached reached))
                                (when (= :incremental policy) requested-changed))
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
            related-ids (into #{} (mapcat (fn [entity-map]
                                           (concat (:seon.test/ns entity-map) (:seon.fn/file entity-map))))
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
                                          (named? entry)
                                          ;; A test the request names as changed, from any root.
                                          (and (= :incremental policy) (requested-changed (first entry))))
                                      (or include-long? (identities (first entry))
                                          (not (one entity :seon.test/long)))
                                      ;; A named request's changed identities
                                      ;; name the tests reaching them too.
                                      (or (not= :named policy) (named? entry)
                                          (requested-reached (first entry)))))) tests)
            _ (doseq [symbol identities :when (not (get eligible symbol))]
                (refuse! :seon.test/identity-unresolved "The requested test is not eligible." symbol))
            ;; A named change's reaching test the eligibility rule drops is
            ;; missing coverage: it refuses by name, never an empty verdict.
            _ (when-let [dropped (seq (sort (keep (fn [[test-symbol entity :as entry]]
                                                    (when (and (requested-reached test-symbol)
                                                               (not (get eligible test-symbol))
                                                               (not (excluded? entry))
                                                               (not (one entity :seon.test/long)))
                                                      test-symbol))
                                                  tests)))]
                (refuse! :seon.test/coverage-unknown
                         (str "These tests reach the named change but are neither agent-authored, under a test root, "
                              "nor named, so this request cannot run them.")
                         (vec dropped)))
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
                                                    ["bin/test-check" (:seon.cluster/name cluster-entity)
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
            ;; the JVM loaded source the cluster's program entities do not describe
            ;; (a restart from changed files, before adoption), the entities cannot
            ;; vouch for what executes: unknown, so nothing is reused.
            ;; The adopted commit whose loaded, armed definitions agree with
            ;; its record (`seon.sci.eval/loaded-identity`): base provenance.
            recorded (when cluster-id
                       (:seon.source/commit-id
                        (selection-read! (db/pull database [:seon.source/commit-id] cluster))))
            loaded-drift? (when-let [loaded (:seon.test/loaded-source request)]
                            (not= loaded recorded))
            reuse-candidates (cond
                               loaded-drift? {}
                               (and (= :incremental policy) (not work?)) eligible
                               :else reasons)
            ;; Reuse is content-keyed: a green member's stored reach digest
            ;; against the current one, one batched read (B4 §2a). A member
            ;; recorded without it is unknown and executes.
            current-reach (let [greens (into [] (keep (fn [[test-symbol _]]
                                                        (when-let [[_ member] (get latest test-symbol)]
                                                          (when (and (green? member) (one member :seon.test.member/reach-digest))
                                                            test-symbol))))
                                             reuse-candidates)]
                            (if (seq greens) (selection-read! (runner/reach-digests database greens)) {}))
            reused (into (sorted-map)
                         (keep (fn [[test-symbol _]]
                                 (when-let [[run-eid member] (get latest test-symbol)]
                                   ;; Evidence naming no loaded program is unknown.
                                   (when (and (green? member)
                                              (one run-eid :seon.test.run/loaded-commit-id)
                                              (or (nil? supplied-basis) (= supplied-basis (one run-eid :seon.test.run/basis-t)))
                                              (not (observed-reached test-symbol))
                                              (string? (get current-reach test-symbol))
                                              (= (get current-reach test-symbol)
                                                 (one member :seon.test.member/reach-digest)))
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
                               ;; Entities that record no source commit are absent,
                               ;; never a nil commit id.
                               (cond-> {:seon.test/loaded-source (:seon.test/loaded-source request)}
                                 recorded (assoc :seon.source/commit-id recorded))))))
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
          ;; The kind names which evidence disagreed; `expected` joins it only
          ;; when it is a scalar, so the message stays bounded.
          :seon.error/message (str "Test run admission refused inconsistent evidence: " kind
                                   (when-not (coll? expected) (str ", expected " (pr-str expected)))
                                   ".")
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
    (mapv #(let [entity (db/pull database
                             [:db/id :seon.test.member/symbol :seon.test.member/reasons] %)]
             (when (or (and (map? entity)
                            (contains? entity :seon.error/at)
                            (contains? entity :seon.error/layer)
                            (contains? entity :seon.error/operation))
                       (not (:seon.test.member/symbol entity))
                       (not (seq (:seon.test.member/reasons entity))))
               (admission-refusal! :seon.test/population-unknown run-id
                                   :admitted-member (or entity :seon.error/unknown)))
             entity)
          ids)))

(defn- admission-scope
  {:malli/schema [:=> [:cat [:map-of :keyword :seon.schema/value]] [:map-of :keyword :seon.schema/value]]}
  [entity]
  (cond-> (-> (select-keys entity [:seon.test.run/cluster :seon.test.run/program-digest
                       :seon.test.run/published-base-digest :seon.test.run/overlay-input-digest
                       :seon.test.run/input-digest :seon.test.run/branch
                       :seon.test.run/tested-branch :seon.test.run/change-basis-t
                       :seon.test.run/policy :seon.test.run/include-long?])
      (assoc :seon.test.run/namespaces (set (:seon.test.run/namespaces entity))
             :seon.test.run/identities (set (:seon.test.run/identities entity))))
    (= :named (:seon.test.run/policy entity))
    (assoc :seon.test.run/basis-t (:seon.test.run/basis-t entity))))

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
  A body of the cluster not observed to exit holds its own test: a later
  request's member for that test is not reserved (the request reports it
  pending); every other member is admitted.
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
        ;; Its commit is the adopted program agreeing with its record
        ;; (`seon.sci.eval/loaded-identity`), read here at the writer.
        cluster-entity (when cluster (db/pull database [:db/id :seon.cluster/name :seon.source/commit-id] cluster))
        cluster-id (:db/id cluster-entity)
        branch (get-in (db/schema-database database) [:config :branch])
        ;; The writer's value has no commit id, so the digest memo cannot name it;
        ;; with no program write since the tested basis its digest is the tested one.
        digest (if (or snapshot? (false? (runner/program-written-since? database (:seon.test.run/basis-t run))))
                 (:seon.test.run/program-digest run)
                 (runner/program-digest database))
        entity (-> (merge (select-keys request
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
                (cond-> cluster-id (assoc :seon.test.run/cluster cluster-id)
                  (:seon.source/commit-id cluster-entity)
                  (assoc :seon.test.run/loaded-commit-id (:seon.source/commit-id cluster-entity))))
        selector (into [:db/id :seon.test.run/selection-tx
                        [:seon.test.run/callers-at-head :limit nil]
                        [:seon.test.run/namespaces :limit nil]
                        [:seon.test.run/exclusions :limit nil]
                        [:seon.test.run/identities :limit nil]]
                       (keys (dissoc entity :seon.test.run/members
                                     :seon.test.run/namespaces :seon.test.run/identities
                                     :seon.test.run/callers-at-head
                                     :seon.test.run/exclusions)))
        previous (db/pull database selector [:seon.test.run/id run-id])]
    (doseq [read-result [cluster-entity previous]]
      (when (and (map? read-result)
                 (contains? read-result :seon.error/at)
                 (contains? read-result :seon.error/layer)
                 (contains? read-result :seon.error/operation))
        (throw (ex-info (:seon.error/message read-result) read-result))))
    (when-not (or snapshot? (:seon.cluster/name cluster-entity))
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
                          (select-keys entity [:seon.test.run/basis-t
                                            :seon.test.run/change-basis-t])))
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
                                   (fn [entities] (set (map (fn [entity] (dissoc entity :db/id)) entities))))
                           (dissoc :seon.test.run/namespaces :seon.test.run/identities)
                           (merge (select-keys (admission-scope %)
                                              [:seon.test.run/namespaces
                                               :seon.test.run/identities])))]
        (when (or (not (:seon.test.run/selection-tx previous))
                  (not= (normalize entity) (normalize prior))
                  (not= (admission-member-values requested-members)
                        (admission-member-values
                         (admission-members database (:db/id previous)))))
          (admission-refusal! :seon.test.run/immutable run-id entity prior))
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
                       database cluster-id digest (:seon.test.run/input-digest entity)))
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
            ;; A body not observed to exit holds its test in every scope of
            ;; its cluster, and only its test: one stuck body never wedges the
            ;; cluster's other requests (2026-09-27, web-test). The program
            ;; digest names a commit, so any write since that body's admission
            ;; (its own recording) changes it; the hold reads runs since JVM start.
            live (if (or snapshot? (empty? symbols))
                   []
                   (let [since-start (selection-read!
                                      (db/q '[:find [?run ...] :in $ ?cluster ?since
                                              :where [?run :seon.test.run/cluster ?cluster]
                                                     [?run :seon.test.run/selection-tx]
                                                     [?run :seon.test.run/at ?at]
                                                     [(compare ?at ?since) ?order]
                                                     [(>= ?order 0)]]
                                            database cluster-id jvm-started))]
                     (selection-read!
                      (db/q '[:find ?run ?member ?symbol :in $ [?run ...] [?symbol ...]
                              :where [?run :seon.test.run/members ?member]
                                     [?member :seon.test.member/symbol ?symbol]
                                     [?member :seon.test.member/completed-tx]
                                     (not [?member :seon.test.member/terminated-tx])]
                            database (vec since-start) symbols))))
            candidates (distinct (concat candidates live))
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
                          (let [member-entity (selection-read!
                                     (db/pull database [:db/id :seon.test.member/symbol
                                                        :seon.test.member/reasons
                                                        :seon.test.member/completed-tx
                                                        :seon.test.member/terminated-tx]
                                              member-eid))]
                            (when (if (:seon.test.member/completed-tx member-entity)
                                    ;; A body not observed to exit holds its test
                                    ;; in every scope.
                                    (not (:seon.test.member/terminated-tx member-entity))
                                    ;; An admitted, unstarted member covers an
                                    ;; identical concurrent request; a stopped
                                    ;; run is no longer concurrent.
                                    (and (not (:seon.test.run/stopped
                                               (selection-read! (db/pull database [:seon.test.run/stopped] run-eid))))
                                         (= (admission-scope entity) (scope-of run-eid))))
                              [test-symbol (select-keys member-entity [:db/id :seon.test.member/symbol
                                                             :seon.test.member/reasons])]))))
                  (sort-by first > candidates))
            covered (keep #(get existing (:seon.test.member/symbol %)) members)
            coverage (into (set (:seon.test.run/covered-by request)) (map :db/id) covered)
            reserved (remove #(get existing (:seon.test.member/symbol %)) members)]
        [(cond-> (assoc entity :seon.test.run/selection-tx "datomic.tx")
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

(defn- resolve-loaded
  "The host Var of `test-symbol`, loading its namespace when the JVM lacks it.

  `requiring-resolve` loads nothing for a namespace still in `*loaded-libs*`
  after it was removed (or failed after its `ns` form), so the test would
  resolve to nil. An absent namespace is loaded with `:reload`, as
  `seon.sci.eval/host-namespace!` does for the process classpath; this runs
  under the test loader, which that owner does not consult. Nothing loaded
  exists to be replaced."
  {:malli/schema [:=> [:cat :qualified-symbol] [:maybe :seon.test/var]]}
  [test-symbol]
  (let [namespace-name (symbol (namespace test-symbol))]
    (when-not (find-ns namespace-name) (require namespace-name :reload))
    (requiring-resolve test-symbol)))

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
        entity (db/pull database
                     '[:db/id :seon.test/source :seon.schema.admission/source
                       :seon.program/analyzed-source-digest :seon.fn/file
                       {:seon.test/ns [:seon.ns/name]}]
                     [:seon.test/sym test-symbol])
        acquired (sci.eval/acquired-program ctx)
        ;; The acquisition owner answers whether this context holds the tested
        ;; program (same value, or equal program revisions at another commit).
        acquired? (sci.eval/acquired-database? ctx database)
        source (:seon.test/source entity)
        admission (:seon.schema.admission/source entity)]
    (cond
      (and (map? entity) (contains? entity :seon.error/at) (contains? entity :seon.error/layer) (contains? entity :seon.error/operation)) entity
      (not (:db/id entity))
      (refuse :seon.test/identity-unresolved "The admitted test has no current entity." test-symbol)
      (or (not (string? source))
          (not= (symbol (namespace test-symbol))
                (get-in entity [:seon.test/ns :seon.ns/name]))
          ;; Analysis covers the resolver context, not just declaration text.
          ;; Acquired program equality below verifies the complete admitted facts.
          (nil? (:seon.program/analyzed-source-digest entity)))
      (refuse :seon.test/provenance-unknown "The test lacks matching source, namespace or analysis evidence." entity)
      (not acquired?)
      (refuse :seon.test/program-mismatch "The SCI context did not acquire the tested program."
              {:seon.source/commit-id (db/commit-id database)
               :seon.test/acquired-commit-id (some-> (:seon.db/db acquired) db/commit-id)})
      (seq (:seon.test/acquisition-refusals acquired))
      (first (:seon.test/acquisition-refusals acquired))
      (not (or (= :agent admission) (and (= :core admission) (:seon.fn/file entity))))
      (refuse :seon.test/provenance-unknown "The test has no executable admission provenance." entity)
      :else
      (try
        (let [test-var (schema/call-with-projection
                        projection
                        #(if (= :agent admission)
                           (sci/resolve ctx test-symbol)
                           (with-test-loader loader (fn [] (resolve-loaded test-symbol)))))]
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
       ;; A member's typed unknown is unverified; a failed read refuses.
       (or (:seon.test/unknown result) (and (:seon.error/at result) (:seon.test/run result))) false
       (:seon.error/at result) result
       (not (and (pos? (:seon.test/pass-count result))
                 (zero? (:seon.test/fail-count result))
                 (zero? (:seon.test/error-count result)))) false
       :else
       (let [inputs (db/q '[:find [?digest ...]
                            :where [_ :seon.source/test-input-digest ?digest]] database)
             current (runner/reach-digests database [test-symbol])
             present (db/pull database [:seon.test/source :seon.test/fixture-observation]
                              [:seon.test/sym test-symbol])]
         (or (first (filter :seon.error/at [inputs current present]))
             (boolean
              (and (:seon.test/source present)
                   (not (:seon.test/fixture-observation present))
                   (= #{(:seon.test.run/input-digest result)} (set inputs))
                   (string? (get current test-symbol))
                   (= (get current test-symbol) (:seon.test.member/reach-digest result)))))))))
  ([database test-symbol program-digest]
   (let [result (recorded-result database test-symbol)]
     (cond
       ;; A member's typed unknown is unverified; a failed read refuses.
       (or (:seon.test/unknown result) (and (:seon.error/at result) (:seon.test/run result))) false
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
;;; entrance Var, realized at first use (`acquire-context!` sits above `apply-seeds!`).
(def ^:private release-context!
  (delay (requiring-resolve 'seon.cluster.agent/release-context!)))

(def ^:private routing
  (delay (requiring-resolve 'seon.cluster.agent/routing)))

(def ^:private seed-cluster-facts!
  (delay (requiring-resolve 'seon.cluster/seed-cluster-facts!)))

(defn- digest-differences
  "The declarations `world` and `candidate` hold at different definition
  digests, as `[offered-by-candidate held-by-world]` identity sets.

  Entity ids differ between lineages, so declarations compare by stored
  definition digest: a digest one value holds and the other lacks names a
  declaration that was added, changed or retracted. A refused read answers
  its refusal."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value [:vector :qualified-keyword]]
                  [:or [:tuple :seon.program/identity-set :seon.program/identity-set] :seon.error/value]]}
  [world candidate families]
  (let [;; One query per value (0.5 ms on 10,779 declarations; the armed
        ;; `db/datoms` read of the same range measured 44 ms).
        by-digest (fn [database]
                    (let [found (db/q '[:find ?digest ?e :where [?e :seon.program/definition-digest ?digest]]
                                      database)]
                      (if (:seon.error/at found) found (into {} found))))
        [held offered :as maps] (mapv by-digest [world candidate])
        named (fn [database digests other]
                (let [entities (db/pull-many database families
                                         (into [] (keep (fn [[digest e]] (when-not (get other digest) e)))
                                               digests))]
                  (if (:seon.error/at entities) entities
                      (into #{} (mapcat (fn [entity] (keep #(when-some [v (get entity %)] [% v]) families)))
                            entities))))
        present (when-not (some :seon.error/at maps) (named candidate offered held))
        changed (when (set? present) (named world held offered))]
    (cond (some :seon.error/at maps) (some #(when (:seon.error/at %) %) maps)
          (not (set? present)) present
          (not (set? changed)) changed
          :else [present changed])))

(defn- program-overlay-tx
  "Transaction data putting `candidate`'s differing program rows onto `world`.

  Only the `digest-differences` identities are read and replaced
  (`seon.fn/reconcile-tx`) with their program-partition attributes, an
  identity absent from the candidate retracting, and with any row they name
  by lookup that the world lacks (a new file). Callers of a changed
  definition interpret it at acquisition. Attributes the candidate installed
  and the world lacks come first. Equal programs and attributes answer `[]`;
  a refused read answers its refusal."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value]
                  [:or :seon.db/tx-data :seon.error/value]]}
  [world candidate]
  (let [families (vec (:seon.program/compared-families
                       (program/declaration-families (db/carried-projection candidate))))
        differences (digest-differences world candidate families)
        [present changed] (when (vector? differences) differences)
        ;; Attributes the candidate installed that the world lacks (an agent's
        ;; or fixture's new schema): schema-on-write needs them installed before
        ;; a carried entity or a member writes them. Sub-millisecond on 2,452.
        attributes (into [] (keep (fn [[attribute declaration]]
                                    (when (and (qualified-keyword? attribute)
                                               (not (get (:schema world) attribute)))
                                      declaration)))
                         (sort-by key (filter (comp keyword? key) (:schema candidate))))
        ;; Only the program partition carries over: another writer's fact on
        ;; an entity (a namespace's steward agent) names data the world lacks.
        others #(let [program (program/program-attributes (db/carried-projection candidate))]
                  (into #{} (remove (fn [a] (or (program a) (= "db" (namespace a)))))
                        (filter qualified-keyword? (keys (:schema candidate)))))
        read-entities #(functions/published-index-entities candidate (vec (sort %)) (others))
        identity? (set (db/identity-attributes world))
        ;; Any identity the transaction names by lookup that neither the world
        ;; nor the transaction's entities hold: a recomputed entity (a file) a carried
        ;; entity names travels with it; the world keeps its own file entities.
        missing (fn [tx entities]
                  (let [created (into #{} (map program/row-identity) entities)]
                    (into #{} (filter #(and (vector? %) (not (map-entry? %)) (= 2 (count %)) (identity? (first %))
                                            (not (created %))
                                            (nil? (db/q '[:find ?e . :in $ ?a ?v :where [?e ?a ?v]]
                                                        world (first %) (second %)))))
                          (tree-seq coll? seq tx))))
        overlay (fn [extra]
                  (let [entities (read-entities (into present extra))
                        tx (if (:seon.error/at entities) entities
                               (functions/reconcile-tx world entities (vec (sort (into present changed)))))]
                    [entities tx]))]
    (cond
      (:seon.error/at differences) differences
      (empty? (into present changed)) attributes
      :else
      (loop [extra #{}]
        (let [[entities tx] (overlay extra)
              absent (when (vector? tx) (missing tx entities))]
          (cond
            (not (vector? tx)) tx
            (empty? absent) (into attributes tx)
            (and (empty? (remove extra absent)) (seq extra))
            (throw (ex-info (str "The test world's overlay names " (count absent)
                                 " identities its candidate cannot supply.")
                            {:seon.error/expected :seon.program/row
                             :seon.error/offending (vec (take 5 (sort absent)))}))
            :else (recur (into extra absent))))))))

;;; A checkpoint is a shared branch named by the content it holds. The
;;; registry's roster serializes its creation: exactly one `branch!` call is
;;; `created?`, and only that caller writes it. Completion is a fact, the
;;; source record written last; nothing else marks readiness.
(defn- checkpoint-head
  "The checkpoint branch's head when its source record names `base`, else nil."
  {:malli/schema [:=> [:cat :seon.store/store :seon.store/branch :seon.cluster/name :seon.source/commit-id]
                  [:maybe :seon.source/commit-id]]}
  [store branch cluster base]
  (let [head (registry/branch-commit-id {:seon.store/store store :seon.store/branch branch})]
    ;; No checkpoint branch yet is no checkpoint; the registry names it.
    ;; The raw commit value: deriving its projection costs ~110 ms, the read 9 ms.
    (when-let [database (when-not (:seon.cluster.registry/branch-missing head)
                          (source/commit-database store head))]
      (when (= base (db/q '[:find ?commit . :in $ ?name
                            :where [?c :seon.cluster/name ?name] [?c :seon.source/commit-id ?commit]]
                          database cluster))
        head))))

(defn- world-refusal
  "A refused test-world read or write as a bounded ex-info: the refusal's
  message and operation and at most 400 characters of its offending value,
  which may be a whole transaction its own evidence already retains."
  {:malli/schema [:=> [:cat :string :seon.error/value] :seon.error/throwable]}
  [what refusal]
  (ex-info (str "The test world's " what " was refused: " (:seon.error/message refusal))
           {:seon.error/message (:seon.error/message refusal)
            :seon.error/operation (:seon.error/operation refusal)
            :seon.error/offending (print.value/bounded-text (:seon.error/offending refusal) 400)}))

(defn- open-world!
  "Open `branch`, `cluster`'s test world, with the schema projection its value derives, from `candidate`'s."
  {:malli/schema [:=> [:cat :seon.store/store :seon.store/branch :seon.db/database-value :seon.cluster/name]
                  :seon.db/connection]}
  [store branch candidate cluster]
  (let [connection (store/open-branch! store branch)]
    (try
      (db/carry-connection-projection-state!
       connection (sci.eval/projection-state
                   (db/db connection)
                   (schema/projection-from-database (db/db connection) (db/carried-projection candidate))
                   cluster))
      connection
      (catch Throwable failure
        (try (store/release-branch! connection) (catch Throwable cleanup (.addSuppressed failure cleanup)))
        (throw failure)))))

(defn- prepare-checkpoint!
  "Write boot's cluster facts, then the source record, onto the checkpoint
  branch this caller created. A refusal retires it and throws."
  {:malli/schema [:=> [:cat :seon.store/store :seon.store/branch :seon.cluster/name
                       :seon.config/effective :seon.db.process/id :seon.source/commit-id
                       :seon.db/database-value]
                  :nil]}
  [store branch cluster effective process base candidate]
  (let [connection (open-world! store branch candidate cluster)]
    (try
      (db/call-with-custody
       {:seon.db/connection connection}
       #(do (@seed-cluster-facts! connection cluster
                                  {:seon.config/effective effective
                                   :seon.config/desired-row (assoc effective :seon.config/cluster cluster)
                                   :seon.config/resolved-attributes (set (keys effective))}
                                  process)
            (let [report (db/transact! connection [{:seon.cluster/name cluster :seon.source/commit-id base}])]
              (when-not (:db-after report) (throw (world-refusal "source record" report))))))
      (catch Throwable failure
        (doseq [cleanup! [#(store/release-branch! connection)
                          #(registry/retire-branch! {:seon.store/store store :seon.store/branch branch})]]
          (try (cleanup!) (catch Throwable cleanup (.addSuppressed failure cleanup))))
        (throw failure)))
    (store/release-branch! connection)
    nil))

(defn- world-base
  "The source commit `cluster`'s adoption record on `recording` names: the
  published program this JVM loaded, which every test world starts from."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.cluster/name] :seon.source/commit-id]}
  [recording cluster]
  (or (:seon.source/commit-id
       (db/pull (db/db recording) [:seon.source/commit-id] [:seon.cluster/name cluster]))
      (throw (ex-info "The cluster records no adopted source commit to branch a test world from."
                      {:seon.error/expected :seon.source/commit-id
                       :seon.error/offending cluster}))))

(declare request-refusal)

(defn changes
  "What a check of `execution` must reach: the declarations its program holds
  at other definition digests than the published commit its test world
  starts from, exactly what `clean-request-handle` overlays onto that world.
  A namespace identity is a binding change; it stays only when the namespace
  declares something a test can reach. Equal programs refuse by name: there
  is no change to check."
  {:malli/schema [:=> [:cat :seon.agent/context-source :seon.db/connection]
                  [:or :seon.test/changed :seon.test/selection-error :seon.error/value]]}
  [execution recording]
  (let [store (or (:seon.store/store execution) (:seon.store/store (env/of execution)))
        base (world-base recording (:seon.cluster/name execution))
        candidate (db/db (:seon.db/connection execution))
        families (vec (:seon.program/compared-families
                       (program/declaration-families (db/carried-projection candidate))))
        differences (digest-differences (source/database store base) candidate families)
        identities (when (vector? differences) (apply into differences))
        declaring (when identities
                    (db/q '[:find [?name ...] :in $ [?name ...]
                            :where [?n :seon.ns/name ?name] (or [?e :seon.fn/ns ?n] [?e :seon.test/ns ?n])]
                          candidate (vec (keep (fn [[a v]] (when (= :seon.ns/name a) v)) identities))))
        changed (when (sequential? declaring)
                  (vec (sort-by pr-str (filter (fn [[a v]] (or (not= :seon.ns/name a) (some #{v} declaring)))
                                               identities))))]
    (cond (:seon.error/at differences) differences
          (not (sequential? declaring)) declaring
          (empty? changed)
          (request-refusal :seon.test/policy-unscoped
                           "My program equals the published commit my tests start from: there is no change to check. Name tests with :seon.test/identities, or run my namespace's tests with (my.test/run)."
                           base)
          :else changed)))

(defn- clean-request-handle
  "Acquire the request's world: the loaded published program, boot's facts and
  the execution's differing program rows.

  The base is the source commit the recording cluster's adoption record names:
  the program this JVM loaded, which admission records as the run's
  `:seon.test.run/loaded-commit-id`. A published commit holds program rows only,
  so no agent, turn, message or error of the execution is inherited. Boot's
  facts live on a checkpoint branch named by the digest of their inputs (base,
  cluster, effective config, process), prepared once by the one caller the
  roster lets create it and read only once its source record exists, so every
  request with equal inputs shares its preparation. A checkpoint another caller
  is still preparing refuses this request. The request then owns a private
  branch off the checkpoint's head, which roots that commit for its members,
  and writes `program-overlay-tx`'s rows there. Checkpoints of every base in
  use coexist (an agent branch records the commit it was forked at); boot
  retires them all (`registry/retire-unclaimed!`), as the name holds the process."
  {:seon/tasks #{"c86d834ab695" "845e1bce2592"}
   :malli/schema [:=> [:cat :seon.agent/context-source :seon.db/connection
                       :seon.cluster/name :seon.config/effective]
                  :seon.agent/execution-handle]}
  [execution recording cluster effective]
  (let [store (or (:seon.store/store execution) (:seon.store/store (env/of execution)))
        base (world-base recording cluster)
        candidate (db/db (:seon.db/connection execution))
        ;; Branching shares the base's entity ids, so entities reconciled against
        ;; the base apply unchanged on its checkpoint's descendants.
        overlay (program-overlay-tx (source/database store base) candidate)
        _ (when (:seon.error/at overlay) (throw (world-refusal "program overlay read" overlay)))
        process (cluster.process/id (cluster.process/current-identity))
        branch (keyword (str "checkpoint-" cluster "-" (id/digest 24 [base cluster (sort effective) process])))
        at {:seon.store/store store :seon.store/branch branch}
        head (or (checkpoint-head store branch cluster base)
                 (when (:seon.cluster/created? (registry/branch! (assoc at :seon.cluster.registry/from base)))
                   (prepare-checkpoint! store branch cluster effective process base candidate)
                   (checkpoint-head store branch cluster base))
                 ;; The name includes this process, so an incomplete checkpoint
                 ;; is one a live caller here is still preparing: push back.
                 (checkpoint-head store branch cluster base)
                 (throw (ex-info "Another request is preparing this test world; retry once it completes."
                                 {:seon.error/expected :seon.test/prepared-checkpoint
                                  :seon.error/offending branch})))
        ;; The request's writes — its branch, the program overlay and every
        ;; member branch acquired from this handle — stay in memory over the
        ;; shared store (`store/open-overlay!`), which they never write; the
        ;; checkpoint above is shared by digest and stays durable. Only the
        ;; recorded results reach the store, on the recording connection.
        store (store/open-overlay! store)
        ;; The request branch holds the overlay and roots its commit for the members.
        request-branch (keyword (str "agent-" (id/id)))
        release! (fn [^Throwable failure steps]
                   (doseq [cleanup! steps]
                     (try (cleanup!) (catch Throwable cleanup (.addSuppressed failure cleanup))))
                   failure)
        drop! #(store/release-store! store)
        _ (when-not (:seon.cluster/created? (registry/branch! {:seon.store/store store :seon.store/branch request-branch
                                                              :seon.cluster.registry/from head}))
            (throw (release! (ex-info "The request branch already exists." {:seon.error/offending request-branch})
                             [drop!])))
        retire! #(registry/retire-branch! {:seon.store/store store :seon.store/branch request-branch})
        connection (try (open-world! store request-branch candidate cluster)
                        (catch Throwable failure (throw (release! failure [retire! drop!]))))
        report (when (seq overlay)
                 (try (db/call-with-custody {:seon.db/connection connection}
                                            #(db/transact! connection overlay))
                      (catch Throwable failure
                        (throw (release! failure [#(store/release-branch! connection) retire! drop!])))))]
    (when (and report (not (:db-after report)))
      (throw (release! (world-refusal (str "program overlay (" (count overlay) " entries)") report)
                       [#(store/release-branch! connection) retire! drop!])))
    (let [database (db/db connection)
          source (or (:seon.sci.eval/base-ctx execution) (:my.program/base-ctx execution)
                     (:seon.sci.eval/ctx execution))
          environment (or (env/of execution) (env/of source))
          ;; Acquired once on a fork (389 ms), never on the execution's own context:
          ;; each member forks this and finds its program by commit (29 ms), where
          ;; re-acquiring per member measured 325-581 ms (2026-09-25). The fork
          ;; acquires `database` itself and refuses when its refusals cannot be
          ;; recorded (`seon.sci.eval/fork-cluster-ctx`).
          ctx (try (sci.eval/fork-cluster-ctx source database connection
                                              (:seon.sci.eval/projection-state (meta connection))
                                              {:seon.env/environment environment})
                   (catch Throwable failure
                     (throw (release! failure [#(store/release-branch! connection) retire! drop!]))))]
      ;; The execution's value with this request's own world: a fresh roster,
      ;; so member contexts and graphs stay the request's.
      (assoc execution
             :seon.agent/routing (@routing)
             :seon.sci.eval/base-ctx ctx :seon.sci.eval/ctx ctx
             :seon.env/environment environment
             :seon.db/connection connection :seon.db/db database
             :seon.source/commit-id (db/commit-id database)
             :seon.schema/projection (db/carried-projection database)
             :seon.agent/branch request-branch :seon.agent/mode :isolated
             :seon.agent/owns-connection? true :seon.agent/owns-branch? true
             :seon.store/store store))))

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
  A body still live at the bound keeps its branch; once the watcher observes
  its exit and releases the branch, `exited!` gets any cleanup failure."
  {:malli/schema [:=> [:cat :seon.agent/execution-handle :map :seon.test/sym [:int {:min 1}]
                       [:=> [:cat [:maybe :seon.error/throwable]] :nil] :boolean]
                  [:map [:seon.test/result :seon.test.runner/captured-result]
                   [:seon.test/timings
                    [:map [:seon.agent/branch :seon.agent/branch]
                     [:seon.test.timing/acquire-ms :seon.test.timing/acquire-ms]
                     [:seon.test.timing/resolve-ms :seon.test.timing/resolve-ms]
                     [:seon.test.timing/run-ms :seon.test.timing/run-ms]
                     [:seon.test.timing/release-ms :seon.test.timing/release-ms]]]]]}
  [request-handle since test-symbol remaining-ms exited! effect-attempt?]
  (let [elapsed (fn [started] (/ (- (System/nanoTime) started) 1000000.0))
        started (System/nanoTime)
        child (@acquire-context! request-handle nil
                                 ;; The handle's own store (the request's overlay) holds that commit.
                                 {:seon.agent/isolate? true
                                  :seon.cluster.registry/from (:seon.source/commit-id request-handle)})
        acquired (elapsed started)
        release-ms (volatile! 0.0)
        cleanup-failure (volatile! nil)
        with-cleanup-failure
        (fn [result]
          (if-let [failure @cleanup-failure]
            (-> result
                (update :seon.test/error-count (fnil inc 0))
                (update :seon.test/failure-message
                        #(str (when % (str % "\n"))
                              "Member cleanup failed; its branch "
                              (:seon.agent/branch child) " may remain:\n"
                              (print.value/throwable-text failure))))
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
      ;; The member's world is the executing agent's: a call omitting its
      ;; declared agent id receives this one, on the member's own branch.
      (when-let [agent-id (:seon.agent/id request-handle)]
        (let [ctx (:seon.sci.eval/ctx child)]
          (env/replace-environment! (get ctx env/state-carrier)
                                    (env/scope (env/of ctx) {:seon.agent/id agent-id}))))
      (let [ctx (:seon.sci.eval/ctx child)
            resolved (volatile! 0.0)
            ;; Resolved on the body thread, inside its program-change detection.
            resolve! #(let [started (System/nanoTime)]
                        (try (resolve-test
                              {:seon.db/db (:seon.db/db child)
                               :seon.db/connection (:seon.db/connection child)
                               :seon.test/identity test-symbol
                               :seon.sci.eval/ctx ctx
                               :seon.schema/projection (:seon.schema/projection child)
                               :seon.test/class-loader (or (:seon.test/class-loader (sci.eval/acquired-program ctx))
                                                           (clojure.lang.RT/baseLoader))})
                             (finally (vreset! resolved (elapsed started)))))
            started (System/nanoTime)
            ;; A member over one second reports what the armed definitions did.
            profile-mark (profile/begin)
            registry-before (runner/live-cluster-schema-states)
            result (do
                     (vreset! handed? true)
                     (schema/call-with-projection
                      (:seon.schema/projection child)
                      ;; A test body is the member branch's own work, so its
                      ;; elided `seon.db` arities reach that branch: an SCI body
                      ;; through this custody, a host body through the custody
                      ;; `bounded-result` hands `run-var!`.
                      #(bounded-result
                        sci.eval/loaded-identity since test-symbol resolve! remaining-ms
                        {:seon.sci.eval/ctx ctx :seon.db/connection (:seon.db/connection child)
                         :seon.test/member child :seon.test/effect-attempt? effect-attempt?}
                        (fn [watched?]
                          (release! watched?)
                          (when watched? (exited! @cleanup-failure))
                          nil))))
            executed (- (elapsed started) @resolved)
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
                                       ;; bounded once by the floor: the drift can
                                       ;; be whole registry entries
                                       (print.value/bounded-text drifted)))))]
        (when-not @handed? (vreset! handed? true) (release! false))
        {:seon.test/result (cond-> (with-cleanup-failure result)
                             (not (:seon.test.run/terminated? result))
                             (assoc :seon.agent/branch (:seon.agent/branch child))
                             explanation
                             (assoc :seon.profile/explanation explanation))
         :seon.test/timings (timing @resolved executed)})
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
  "Judge counts alone: a captured, reused or recorded result all carry them."
  {:malli/schema [:=> [:cat :seon.test.runner/captured-result] :boolean]}
  [result]
  (and (pos? (:seon.test/pass-count result 0))
       (zero? (:seon.test/fail-count result 0))
       (zero? (:seon.test/error-count result 0))))

(defn- arm-first-loads!
  "Load the `namespaces` this JVM has not loaded, then arm exactly the Vars that
  load interned, once, before any member body, against the LOADED program's
  projection, never the requesting branch's. The loaded program is the commit
  `seon.sci.eval/loaded-identity` names now, read from the context's store; the
  context's snapshot is its acquisition's value and stays behind a later
  adoption. An identity naming no commit is the booted program the snapshot
  acquired; an unknown one answers its diagnostic. A branch whose contract for
  one of their declarations differs from the loaded one refuses by name, before
  loading anything."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.config/effective :seon.sci.eval/ctx [:set :symbol]]
                  [:or :nil :seon.instrument/applied :seon.instrument/registration-error
                   :seon.sci.eval/interpretation-error :seon.test/not-runnable-error]]}
  [database effective ctx namespaces]
  (let [fresh (into (sorted-set) (remove find-ns) namespaces)
        snapshot (when (seq fresh) @(:seon.sci.kernel/program-snapshot ctx))
        loaded-id (when (seq fresh) @sci.eval/loaded-identity)
        loaded (when (and (seq fresh) (not (:seon.error/at loaded-id)))
                 (if-let [commit (:seon.source/commit-id loaded-id)]
                   (source/database (:seon.store/store (env/of ctx)) commit)
                   (:seon.sci.eval/loaded-database snapshot)))
        projection (when loaded (db/carried-projection loaded))
        contracts (:seon.schema.projection/function-contracts projection)
        branch-contracts (when (seq fresh) (:seon.schema.projection/function-contracts (db/carried-projection database)))
        changed (first (sort (filter #(and (fresh (symbol (namespace %)))
                                           (not= (get contracts % ::absent) (get branch-contracts % ::absent)))
                                     (into (set (keys contracts)) (keys branch-contracts)))))
        evidence-bytes (:seon.config.error/max-evidence-bytes effective)]
    (cond
      (empty? fresh) nil
      (:seon.error/at loaded-id) loaded-id
      changed
      (seon.error.refusal/diagnostic (java.util.Date.) :seon.sci.eval/program `arm-first-loads!
       {:seon.error/message (str "Cannot interpret " changed ": Host-bound declaration " changed
                                 " must change through the loaded source files.")
        :seon.error/expected :seon.sci.eval/interpretable-definition
        :seon.sci.eval/refused-function changed
        :seon.sci.eval/interpretation-reason "its branch contract differs from the loaded program's"})
      :else
      (let [before (set (all-ns))
            ;; A namespace that cannot load (no file, or a compile error) is a
            ;; declared refusal naming it, with its whole cause, never an escape.
            unloadable (some (fn [namespace-name]
                               (try (require namespace-name) nil
                                    (catch Exception failure [namespace-name failure])))
                             fresh)
            interns (for [n (remove before (all-ns)) s (keys (ns-interns n))] (symbol (str n) (str s)))]
        (if-let [[namespace-name failure] unloadable]
          (seon.error.refusal/diagnostic (java.util.Date.) :seon.test/execution `arm-first-loads!
           {:seon.error/message (str "Test namespace " namespace-name " cannot load: "
                                     (or (ex-message failure) (.getName (class failure))))
            :seon.error/expected "a test namespace that loads"
            :seon.error/offending namespace-name
            :seon.error/throwable failure
            :seon.test/not-runnable (str namespace-name)})
        (schema/call-with-projection
         projection
         #(instrument/apply!
           (cond-> {:seon.sci.admit/caps (config/result-caps effective)
                    :seon.schema/projection projection
                    ;; The request's supplied-default entities the contracts plan from.
                    :seon.db/db database
                    :seon.instrument/changed-identities (into #{} (map (fn [sym] [:seon.fn/sym sym])) interns)}
             evidence-bytes (assoc :seon.config.error/max-evidence-bytes evidence-bytes)
             (:seon.flow/commit-fault! snapshot)
             (assoc :seon.flow/commit-fault! (:seon.flow/commit-fault! snapshot))))))))))

(defn run
  "Answer one test request on the execution value it names.

  `:seon.test/execution` is an agent context source or execution handle; the
  request captures its current commit once. `:seon.test/policy` selects
  eligibility (`:named` needs identities or namespaces; `:incremental` derives
  changed reach and outstanding obligations from recorded evidence; `:platform`
  the declared rows; `:all` every eligible row). Selection and admission read
  and write the explicit `:seon.test/recording-connection`, which must hold the
  execution branch; `:seon.db/writer` (default `seon.db/transact!`)
  writes admission and stop there. A member whose recorded green still holds is answered from
  the record with `:seon.test/unchanged` and no execution.

  Each executed member is an isolated agent for one body: a fresh branch off
  the request's world through `seon.cluster.agent/acquire-context!`. A cluster's
  world is its loaded published program plus boot's cluster facts, holding none
  of its agents, turns, messages or errors, plus the execution's differing
  program rows, so an agent branch tests its candidate (`clean-request-handle`). The body
  under that branch's custody (`seon.sci.eval/run-test`), its result recorded
  on the recording connection, and the branch unlinked by
  `release-context!` after the body's thread exits. The request is bounded by
  `:seon.test/check-time-limit-ms` (request member or the cluster's dial); a
  body still live at the bound fails and keeps its branch; the rest of its
  request stops, and a later request holds (reports pending) only that test
  until it exits.
  Members reaching a destructive owner or declaring a file-backed fixture
  are excluded in a JVM operating the development checkout, with the platform
  command. `:seon.test/passed?` is true only when every obligation has green
  evidence: exclusions, pending and unfinished members are unfulfilled."
  {:malli/schema [:=> [:cat :seon.test/request]
                  [:or :seon.test/run-result :seon.test/selection-error :seon.test/unknown-error
                   :seon.test/admission-error :seon.test.run/unavailable-error
                   :seon.config/error :seon.db.write/error :seon.db/transaction-refused-error
                   :seon.db/invalid-read-error
                   :seon.schema/missing-projection-error :seon.instrument/registration-error
                   :seon.sci.eval/interpretation-error :seon.test/not-runnable-error
                   :seon.sci.kernel/memory-pressure-error]]}
  [{execution :seon.test/execution recording :seon.test/recording-connection
    policy :seon.test/policy identities :seon.test/identities
    namespaces :seon.test/namespaces write! :seon.db/writer
    :or {write! db/transact!} :as request}]
  (let [started (System/nanoTime)
        elapsed #(/ (- (System/nanoTime) started) 1000000.0)
        cluster (:seon.cluster/name execution)
        database (db/db (:seon.db/connection execution))
        held-store (or (:seon.store/store execution) (:seon.store/store (env/of execution)))
        effective (config/effective database cluster)
        bound (or (:seon.test/check-time-limit-ms request)
                  (:seon.test/check-time-limit-ms effective))
        ;; The arms' own admission, asked before a world is built for them.
        pressure (kernel/memory-pressure)]
    (cond
      pressure (kernel/memory-pressure-error pressure)
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
            ;; Host test namespaces first loaded by this request arm before any body.
            armed (when (seq runnable)
                    (arm-first-loads!
                     database effective (:seon.sci.eval/ctx execution)
                     (into #{} (comp (remove (comp find-ns symbol namespace))
                                     (filter #(= :core (:seon.schema.admission/source
                                                        (db/pull database [:seon.schema.admission/source] [:seon.test/sym %]))))
                                     (map (comp symbol namespace)))
                           runnable)))
            ;; One reach read over every runnable member: each member's
            ;; recording then reads the index (68 members 1,086 ms, against
            ;; 317 ms for each member read alone, 2026-09-23).
            ;; The loaded program every member must run in: an adoption from
            ;; here on, before or during a member, makes that member unknown.
            since @sci.eval/loaded-identity
            reach (when (seq runnable) (runner/reach-digests database runnable))
            provenance (:seon.test.run/provenance selection)
            exclusion-entities
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
          (:seon.error/at reach) reach
          (:seon.error/at armed) armed
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
                ;; One policy for the runner's own admission, completion, stop and
                ;; exit writes, on this thread and the exit watcher's: a declared
                ;; caller mistake (admission evidence disagreed, or a recording
                ;; connection outside the caller's custody) is returned; any other
                ;; refusal is a stored, delivered core fault, which leaves the
                ;; request unsuccessful (it throws only when it cannot be stored).
                core-fault! #(fault/fault!
                              (env/environment {:seon.boot/cluster-name cluster :seon.db/connection recording
                                                :seon.sci.admit/caps (config/result-caps effective)})
                              %2 {:seon.error/layer :seon.test/recording :seon.error/operation %1
                                  :seon.db.process/id (cluster.process/id (cluster.process/current-identity))})
                own-write! (fn [operation written]
                             (when (and (:seon.error/at written) (not (:seon.test/admission-refusal written))
                                        (not (get-in written [:seon.error/data :seon.db/ambient-branch])))
                               ;; The refusal holds its whole cause as data, `:seon.error/chain`
                               ;; (`seon.error.refusal/diagnostic` consumes the Throwable).
                               (core-fault! operation (ex-info (:seon.error/message written) written)))
                             written)
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
                               entity (cond-> (assoc selection
                                                  :seon.test.run/provenance batch-provenance
                                                  :seon.test.run/members
                                                  (filterv #((set members) (:seon.test.member/symbol %))
                                                           (:seon.test.run/members selection)))
                                     (and (zero? index) (seq exclusion-entities))
                                     (assoc :seon.test.run/exclusions exclusion-entities)
                                     (pos? index) (dissoc :seon.test.run/covered-by))
                               report (own-write! `admit-run (write! recording [[:db.fn/call admit-run entity]]))]
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
                ;; Every request, a cluster's or an isolated attempt's, branches
                ;; from its loaded program with boot's facts and its execution's
                ;; differing program entities, never from its operational data. It is
                ;; built by the first admitted batch: a refused admission builds none.
                held-handle (volatile! nil)
                request-handle! #(or @held-handle
                                     (vreset! held-handle
                                              (clean-request-handle execution recording cluster effective)))
                ;; Once per request: the members an attempt evaluation serves,
                ;; those whose own reach (the index `reach` read) names the
                ;; effect owner, never the owner's whole reverse closure
                ;; (1,539 names, 65-90 ms per request, 2026-09-26).
                effect-reach (when (seq ordered)
                               (let [reaches (runner/reach-memberships database (vec ordered))]
                                 (if (:seon.error/at reaches)
                                   (throw (ex-info "Effect reach is unreadable." reaches))
                                   (into #{} (keep (fn [[test-symbol names]]
                                                     (when (contains? names 'seon.effect/request*) test-symbol)))
                                         reaches))))
                run-batch
                (fn [outcome batch-provenance members]
                  (let [run-id (:seon.test.run/id batch-provenance)
                        ;; Shared only with this batch's asynchronous exit callbacks.
                        ;; The atomic handoff fences exits racing its one recording.
                        observed (atom {::exited #{} ::results [] ::recorded? false})
                        record-exit! (fn [test-symbol cleanup-failure]
                                       (try (when (::recorded? (swap! observed update ::exited conj test-symbol))
                                              (own-write! `runner/record-exit!
                                                          (runner/record-exit! recording run-id test-symbol)))
                                            (finally (some->> cleanup-failure (core-fault! `release-context!))))
                                       nil)
                        captured (fn [{::keys [results exited]}]
                                   (mapv #(assoc % :seon.test.run/terminated?
                                                (or (true? (:seon.test.run/terminated? %))
                                                    (contains? exited (:seon.test/sym %)))) results))
                        handoff! (fn [results]
                                   (let [[before] (swap-vals! observed assoc ::recorded? true)
                                         folded (into #{} (keep #(when (:seon.test.run/terminated? %) (:seon.test/sym %))) results)]
                                     ;; Exits racing capture are separate; later exits belong to the callback.
                                     (doseq [sym (remove folded (::exited before))]
                                       (own-write! `runner/record-exit! (runner/record-exit! recording run-id sym)))))]
                    ;; A throw leaves no admitted member open: each unrecorded
                    ;; member is recorded red with the throwable, which is rethrown.
                    (try
                    (let [outcome
                    (loop [remaining members outcome outcome]
                      (if-let [test-symbol (first remaining)]
                        (if-not (pos? (remaining-ms))
                          (assoc outcome ::stopped (vec remaining) ::stop-cause :deadline)
                          (let [{result :seon.test/result timings :seon.test/timings}
                                (member-result (request-handle!) since test-symbol (remaining-ms)
                                               #(record-exit! test-symbol %)
                               (contains? effect-reach test-symbol))]
                          ;; An unknown loaded program started nothing: this
                          ;; member and the rest keep no outcome, so read unknown.
                          (if-let [cause (:seon.test.run/stopped result)]
                            (assoc outcome ::stopped (vec remaining) ::stop-cause cause)
                          (let [_ (swap! observed update ::results conj (dissoc result :seon.agent/branch))
                                outcome (-> outcome
                                            (update :seon.test/timings conj
                                                    (assoc timings :seon.test/sym test-symbol))
                                            (update :seon.test/results conj result))]
                            (cond
                              (not (:seon.test.run/terminated? result))
                              (assoc outcome :seon.test/unfinished [result]
                                             ::stopped (vec (next remaining)) ::stop-cause :member-unfinished)
                              (and (platform? test-symbol) (not (green? result)))
                              (assoc outcome ::stopped (vec (next remaining)) ::stop-cause :platform-red)
                              :else (recur (next remaining) outcome))))))
                        outcome))
                          results (captured @observed)
                          committed (when (seq results)
                                      (own-write! `runner/commit-results!
                                                  (runner/commit-results!
                                                   recording (recording-completion batch-provenance results false))))]
                      (if (:seon.error/at committed)
                        (assoc outcome :seon.test/recording-refusal committed
                                       ::stopped [] ::stop-cause :recording-refused)
                        (let [by-symbol (into {} (map (juxt :seon.test/sym identity)) committed)]
                          (handoff! results)
                          (update outcome :seon.test/results
                                  #(mapv (fn [result] (merge result (by-symbol (:seon.test/sym result)))) %)))))
                    (catch Throwable failure
                      (try
                        (let [results (captured @observed)
                              recorded (runner/record-interrupted!
                                        recording (recording-completion batch-provenance results true) failure)]
                          (if (:seon.error/at recorded)
                            (.addSuppressed failure (ex-info (:seon.error/message recorded) recorded))
                            (handoff! results)))
                        (catch Throwable recording-failure
                          (.addSuppressed failure recording-failure)))
                      (throw failure)))))
                release-not-started!
                ;; A stopped run records why; the members it never started keep
                ;; no outcome, so each reads as an unknown (never green) and,
                ;; the run being stopped, covers no later request.
                (fn [batch-provenance cause]
                  (own-write! `release-not-started!
                              (write! recording [[:db/add [:seon.test.run/id (:seon.test.run/id batch-provenance)]
                                                  :seon.test.run/stopped cause]])))
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
                                  released (when stopped
                                             (release-not-started! (::provenance admitted) (::stop-cause outcome)))
                                  outcome (cond-> (dissoc outcome ::stopped ::stop-cause)
                                            (:seon.error/at released)
                                            (update :seon.test/recording-refusal #(or % released)))]
                              (if (some? stopped)
                                (update outcome :seon.test/pending into
                                        (concat stopped (mapcat identity (subvec batches (inc index)))))
                                (recur (inc index) outcome))))))
                      outcome))
                  (finally
                    (when-let [request-handle @held-handle]
                      ;; The request's branches live in its overlay: releasing
                      ;; the handle, then the overlay, leaves nothing to collect.
                      (try (@release-context! request-handle)
                           (finally (store/release-store! (:seon.store/store request-handle)))))))
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
                  " is published but not yet adopted; the cluster's program entities record "
                  (or (:seon.source/commit-id drift) "no source commit")
                  " (adopt the files to reuse recorded evidence)"))
           (when-let [pending (seq (:seon.test/pending result))]
             (str "\npending (not started): " (str/join " " pending)))
           (apply str (for [entry (concat (:seon.test/destructive-excluded result)
                                          (:seon.test/deferred result))]
                        (str "\nexcluded " (:seon.test/sym entry) " — run "
                             (str/join " " (:seon.test/command entry)))))
           (apply str (for [entry (:seon.test/long-excluded result)]
                        (str "\nexcluded (declared long; include-long runs it) "
                             (:seon.test/sym entry) ": " (:seon.test/long entry))))
           (when-let [refusal (:seon.test/recording-refusal result)]
             (str "\nrecording refused: " (:seon.error/message refusal)))))))
