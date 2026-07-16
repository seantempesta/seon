(ns seon.db.registry-test
  "Database registry lifecycle, routing, and native branch tests."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [datahike.api :as d]
            [seon.db.backend :as backend]
            [seon.db.coordinate :as coordinate]
            [seon.db.datahike.schema :as datahike.schema]
            [seon.db.id :as id]
            [seon.db.registry :as registry]
            [seon.db.restore :as db.restore]))

(defn- isolate-registry
  [test-fn]
  (let [{::registry/keys [snapshot]} (registry/snapshot-registry {})]
    (try
      (test-fn)
      (finally
        (registry/restore-registry! {::registry/snapshot snapshot})))))

(use-fixtures :each isolate-registry)

(defn- ensure-database!
  [request]
  (registry/ensure-database!
   (assoc request ::registry/initialize-connection!
          (fn [_request] nil))))

(deftest ordered-lifecycle-comparison-stops-at-first-mismatch
  (let [left-realized (atom 0)
        right-realized (atom 0)]
    (letfn [(counted [counter values]
              (lazy-seq
               (when-let [[value & remaining] (seq values)]
                 (swap! counter inc)
                 (cons value (counted counter remaining)))))]
      (is (false? (#'registry/same-ordered-values?
                   (counted left-realized [1 2 3])
                   (counted right-realized [9 2 3]))))
      (is (= 1 @left-realized))
      (is (= 1 @right-realized)))
    (is (true? (#'registry/same-ordered-values? [1 2 3] [1 2 3])))
    (is (false? (#'registry/same-ordered-values? [1 2] [1 2 3])))))

(deftest ensure-is-idempotent-and-concurrent
  (let [database-name :registry/concurrent
        start (promise)
        attempts
        (mapv (fn [_]
                (future
                  @start
                  (ensure-database!
                   {::registry/database-name database-name
                    ::registry/backend :memory})))
              (range 8))]
    (deliver start true)
    (let [entries (mapv deref attempts)
          connections (mapv ::registry/conn entries)
          connection (first connections)]
      (is (every? #(identical? connection %) connections))
      (is (nil? (id/assert-allocation-writer! connection)))
      (is (= [database-name]
             (mapv ::registry/database-name
                   (::registry/databases (registry/list-databases {})))))
      (is (true? (::registry/released?
                  (registry/release-database!
                   {::registry/database-name database-name}))))
      (is (false? (::registry/released?
                   (registry/release-database!
                   {::registry/database-name database-name})))))))

(deftest transport-connections-share-datahike-reference-lifetime
  (let [database-name :registry/shared-transport
        unrelated-name :registry/unrelated-release
        opened (ensure-database!
                {::registry/database-name database-name
                 ::registry/backend :memory})
        conn (::registry/conn opened)
        connection-a (Object.)
        connection-b (Object.)
        connect-calls (atom 0)
        release-calls (atom 0)
        drains (atom [])
        real-connect d/connect
        real-release d/release]
    (with-redefs [d/connect
                  (fn [config]
                    (swap! connect-calls inc)
                    (real-connect config))
                  d/release
                  (fn [connection]
                    (swap! release-calls inc)
                    (real-release connection))]
      (let [first-acquire
            (registry/acquire-database!
             {::registry/database-name database-name
              ::registry/transport-connection connection-a})
            duplicate-acquire
            (registry/acquire-database!
             {::registry/database-name database-name
              ::registry/transport-connection connection-a})
            second-acquire
            (registry/acquire-database!
             {::registry/database-name database-name
              ::registry/transport-connection connection-b})]
        (is (::registry/acquired? first-acquire))
        (is (false? (::registry/acquired? duplicate-acquire)))
        (is (::registry/acquired? second-acquire))
        (is (= 1 @connect-calls)
            "the first connection takes the ensured reference; only its sibling reconnects")
        (is (identical? conn
                        (::registry/conn
                         (registry/resolve-connection
                          {::registry/database-name database-name}))))
        (is (::registry/released?
             (registry/release-database-acquisition!
              {::registry/database-name database-name
               ::registry/transport-connection connection-a
               ::registry/drain! #(swap! drains conj %)})))
        (is (= 1 @release-calls))
        (is (empty? @drains)
            "a sibling release never fences the shared database")
        (is (= (d/db conn)
               (d/db (::registry/conn
                      (registry/resolve-connection
                       {::registry/database-name database-name}))))
            "the sibling still reads the identical live connection")
        (is (false?
             (::registry/released?
              (registry/release-database-acquisition!
               {::registry/database-name database-name
                ::registry/transport-connection connection-a
                ::registry/drain! #(swap! drains conj %)}))))
        (is (= 1 @release-calls)
            "a duplicate close does not release another Datahike reference")
        (is (::registry/released?
             (registry/release-database-acquisition!
              {::registry/database-name database-name
               ::registry/transport-connection connection-b
               ::registry/drain!
               (fn [request]
                 (swap! drains conj request)
                 (is (::registry/conn
                      (deref
                       (future
                         (ensure-database!
                          {::registry/database-name unrelated-name
                           ::registry/backend :memory}))
                       1000
                       nil))
                     "final drain does not hold the registry-wide lock"))})))
        (is (= 2 @release-calls))
        (is (= [{::registry/database-name database-name
                 ::registry/attachment (::registry/attachment opened)}]
               @drains))
        (is (= {} (registry/lookup-connection
                   {::registry/database-name database-name})))
        (is (thrown? clojure.lang.ExceptionInfo (d/db conn)))))))

(deftest administrative-reference-is-independent-of-live-connections
  (let [database-name :registry/admin-and-transport
        connection (Object.)]
    (ensure-database!
     {::registry/database-name database-name ::registry/backend :memory})
    (registry/acquire-database!
     {::registry/database-name database-name
      ::registry/transport-connection connection})
    (ensure-database!
     {::registry/database-name database-name ::registry/backend :memory})
    (is (::registry/released?
         (registry/release-database!
          {::registry/database-name database-name})))
    (is (::registry/conn
         (registry/resolve-connection
          {::registry/database-name database-name}))
        "administrative release leaves the live transport connection intact")
    (is (::registry/released?
         (registry/release-database-acquisition!
          {::registry/database-name database-name
           ::registry/transport-connection connection
           ::registry/drain! (fn [_] nil)})))
    (is (= {} (registry/lookup-connection
               {::registry/database-name database-name})))))

(deftest final-connection-release-fences-reacquire-and-stale-close
  (let [database-name :registry/final-release-race
        connection-a (Object.)
        connection-b (Object.)
        entered-drain (promise)
        finish-drain (promise)
        first (ensure-database!
               {::registry/database-name database-name
                ::registry/backend :memory})
        first-conn (::registry/conn first)]
    (registry/acquire-database!
     {::registry/database-name database-name
      ::registry/transport-connection connection-a})
    (let [release
          (future
            (registry/release-database-acquisition!
             {::registry/database-name database-name
              ::registry/transport-connection connection-a
              ::registry/drain!
              (fn [_]
                (deliver entered-drain true)
                @finish-drain)}))]
      @entered-drain
      (let [failure
            (try
              (registry/acquire-database!
               {::registry/database-name database-name
                ::registry/transport-connection connection-b})
              nil
              (catch clojure.lang.ExceptionInfo exception exception))]
        (is (= :seon.db.registry.error/releasing
               (:seon.error/kind (ex-data failure)))))
      (deliver finish-drain true)
      (is (::registry/released? @release)))
    (let [reopened (ensure-database!
                    {::registry/database-name database-name
                     ::registry/backend :memory})
          second-conn (::registry/conn reopened)]
      (is (not (identical? first-conn second-conn))
          "reopen creates a new Datahike connection generation")
      (registry/acquire-database!
       {::registry/database-name database-name
        ::registry/transport-connection connection-b})
      (is (false?
           (::registry/released?
            (registry/release-database-acquisition!
             {::registry/database-name database-name
              ::registry/transport-connection connection-a
              ::registry/drain! (fn [_] nil)})))
          "a late close from the old socket cannot release the reopened route")
      (is (identical? second-conn
                      (::registry/conn
                       (registry/resolve-connection
                        {::registry/database-name database-name})))))))

(deftest failed-final-connection-drain-retains-cleanup-required
  (let [database-name :registry/final-drain-failure
        connection (Object.)]
    (ensure-database!
     {::registry/database-name database-name ::registry/backend :memory})
    (registry/acquire-database!
     {::registry/database-name database-name
      ::registry/transport-connection connection})
    (let [result
          (registry/release-database-acquisition!
           {::registry/database-name database-name
            ::registry/transport-connection connection
            ::registry/drain!
            (fn [_]
              (throw (ex-info "injected final drain failure" {})))})]
      (is (false? (::registry/released? result)))
      (is (re-find #"injected final drain failure"
                   (::registry/release-error result)))
      (is (= :seon.db.registry.error/cleanup-required
             (::registry/error-kind
              (registry/resolve-connection
               {::registry/database-name database-name})))))))

(deftest release-failure-remains-visible-and-retains-registry-identity
  (let [database-name :registry/release-failure
        entry
        (ensure-database!
         {::registry/database-name database-name
          ::registry/backend :memory})
        failure
        (with-redefs [d/release
                      (fn [_]
                        (throw (ex-info "injected release failure"
                                        {:registry-test/failure true})))]
          (registry/release-database!
           {::registry/database-name database-name}))
        retry
        (registry/release-database!
         {::registry/database-name database-name})
        listed
        (first
         (filter #(= database-name (::registry/database-name %))
                 (::registry/databases (registry/list-databases {}))))]
    (is (false? (::registry/released? failure)))
    (is (re-find #"injected release failure"
                 (::registry/release-error failure)))
    (is (= failure retry)
        "the same process cannot reclassify an unproved release as success")
    (is (= {} (registry/lookup-connection
               {::registry/database-name database-name}))
        "a terminal release failure is retained but never routed")
    (is (= :seon.db.registry.error/cleanup-required
           (::registry/error-kind
            (registry/resolve-connection
             {::registry/database-name database-name}))))
    (is (= (::registry/release-error failure)
           (::registry/release-error listed)))))

(deftest failed-open-validation-retains-unproved-cleanup-identity
  (let [database-name :registry/open-validation-cleanup
        failure
        (with-redefs [id/assert-allocation-writer!
                      (fn [_connection]
                        (throw (ex-info "injected allocation validation" {})))
                      d/release
                      (fn [_connection]
                        (throw (ex-info "injected validation cleanup" {})))]
          (try
            (ensure-database!
             {::registry/database-name database-name
              ::registry/backend :memory})
            nil
            (catch clojure.lang.ExceptionInfo exception exception)))
        retained
        (first
         (filter #(= database-name (::registry/database-name %))
                 (::registry/databases (registry/list-databases {}))))]
    (is (= :seon.db.registry.error/cleanup-required
           (:seon.error/kind (ex-data failure))))
    (is (= :seon.db.registry.entry/cleanup-required
           (::registry/entry-state retained)))
    (is (re-find #"injected allocation validation"
                 (::registry/initialization-error retained)))
    (is (re-find #"injected validation cleanup"
                 (::registry/release-error retained)))
    (is (= {} (registry/lookup-connection
               {::registry/database-name database-name})))
    (is (= :seon.db.registry.error/cleanup-required
           (::registry/error-kind
            (registry/resolve-connection
             {::registry/database-name database-name}))))))

(deftest delete-refuses-to-run-after-release-failure
  (let [database-name :registry/delete-release-failure
        deleted? (atom false)]
    (ensure-database!
     {::registry/database-name database-name
      ::registry/backend :memory})
    (let [result
          (with-redefs [d/release
                        (fn [_]
                          (throw (ex-info "cannot prove release" {})))
                        d/delete-database
                        (fn [_]
                          (reset! deleted? true))]
            (registry/delete-database!
             {::registry/database-name database-name}))]
      (is (false? (::registry/released? result)))
      (is (false? (::registry/deleted? result)))
      (is (false? @deleted?)
          "destructive deletion cannot follow an unproved release")
      (is (re-find #"cannot prove release" (::registry/error result))))))

(deftest initial-schema-is-installed-before-connection-publication
  (let [database-name :registry/initial-schema
        declaration {:db/ident :registry.initial/id
                     :db/valueType :db.type/string
                     :db/cardinality :db.cardinality/one}
        observed (atom nil)
        entry
        (registry/ensure-database!
         {::registry/database-name database-name
          ::registry/backend :memory
          ::registry/initial-tx [declaration]
          ::registry/initialize-connection!
          (fn [{::registry/keys [conn]}]
            (reset! observed
                    (get (:schema (d/db conn)) :registry.initial/id)))})]
    (is (= (select-keys declaration [:db/valueType :db/cardinality])
           (select-keys @observed [:db/valueType :db/cardinality])))
    (is (identical? (::registry/conn entry)
                    (::registry/conn
                     (registry/ensure-database!
                      {::registry/database-name database-name
                       ::registry/backend :memory
                       ::registry/initial-tx []
                       ::registry/initialize-connection!
                       (fn [_request]
                         (throw (ex-info "initializer reran" {})))}))))))

(deftest native-branch-attachments-are-distinct-routes-to-one-database
  (let [main-name :registry/native-main
        branch-name :registry/native-branch
        main (ensure-database!
              {::registry/database-name main-name
               ::registry/backend :memory})
        main-attachment (::registry/attachment main)
        branch-attachment
        (assoc main-attachment ::coordinate/branch :experiment/one)]
    (d/branch! (::registry/conn main) :db :experiment/one)
    (let [branch (ensure-database!
                  {::registry/database-name branch-name
                   ::registry/backend :memory
                   ::registry/attachment branch-attachment})
          resolved (registry/resolve-connection
                    {::registry/database-name branch-name})
          summaries (::registry/databases (registry/list-databases {}))]
      (is (= main-attachment
             (coordinate/attachment (::registry/coordinate main))))
      (is (= branch-attachment (::registry/attachment branch)))
      (is (= branch-attachment
             (coordinate/attachment (::registry/coordinate branch))))
      (is (= branch-attachment (::registry/attachment resolved)))
      (is (not (identical? (::registry/conn main)
                            (::registry/conn branch))))
      (is (= #{main-attachment branch-attachment}
             (set (map ::registry/attachment summaries)))))))

(deftest logical-routes-and-attachments-form-a-bijection
  (let [main-name :registry/bijection-main
        main (ensure-database!
              {::registry/database-name main-name
               ::registry/backend :memory})
        attachment (::registry/attachment main)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"already has a logical route"
         (ensure-database!
          {::registry/database-name :registry/duplicate-route
           ::registry/backend :memory
           ::registry/attachment attachment})))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"cannot change its registered attachment"
         (ensure-database!
          {::registry/database-name main-name
           ::registry/backend :memory
           ::registry/attachment
           (assoc attachment ::coordinate/branch :experiment/other)})))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"disagree on backend configuration"
         (ensure-database!
          {::registry/database-name :registry/conflicting-physical-config
           ::registry/backend :file
           ::registry/path "tmp/registry-conflicting-physical-config/db"
           ::registry/attachment
           (assoc attachment ::coordinate/branch :experiment/config)})))))

(deftest non-main-attachment-requires-current-durable-roster-membership
  (let [main-name :registry/roster-main
        branch-name :registry/roster-branch
        main (ensure-database!
              {::registry/database-name main-name
               ::registry/backend :memory})
        main-connection (::registry/conn main)
        branch :experiment/deleted
        attachment (assoc (::registry/attachment main)
                          ::coordinate/branch branch)]
    (d/branch! main-connection :db branch)
    (let [opened (ensure-database!
                  {::registry/database-name branch-name
                   ::registry/backend :memory
                   ::registry/attachment attachment})]
      (is (= attachment (::registry/attachment opened))))
    (registry/release-database! {::registry/database-name branch-name})
    (d/delete-branch! main-connection branch)
    (is (not (contains? (d/branches main-connection) branch)))
    (let [cfg (backend/datahike-config
               {::backend/database-name branch-name
                ::backend/backend :memory
                ::coordinate/attachment attachment})
          stale (d/connect cfg)]
      (try
        (is (= attachment
               (coordinate/attachment (coordinate/resolved (d/db stale))))
            "raw Datahike can still open the deleted branch head")
        (finally
          (d/release stale))))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"absent from Datahike's durable branch roster"
         (ensure-database!
          {::registry/database-name branch-name
           ::registry/backend :memory
           ::registry/attachment attachment})))
    (is (empty? (registry/lookup-connection
                 {::registry/database-name branch-name})))))

(deftest native-branch-create-release-delete-is-exact-and-isolated
  (let [source-name :registry/lifecycle-source
        target-name :registry/lifecycle-target
        target-branch :registry.branch/lifecycle
        source (ensure-database!
                {::registry/database-name source-name
                 ::registry/backend :memory})
        source-connection (::registry/conn source)]
    (d/transact source-connection
                [{:db/ident :registry.lifecycle/value
                  :db/valueType :db.type/string
                  :db/cardinality :db.cardinality/one}
                 {:registry.lifecycle/value "shared"}])
    (let [source-head
          (::registry/coordinate
           (registry/resolve-connection
            {::registry/database-name source-name}))
          created
          (registry/create-branch!
           {::registry/source-database-name source-name
            ::registry/target-database-name target-name
            ::registry/source-coordinate source-head
            ::registry/expected-source-head source-head
            ::registry/target-branch target-branch
            ::registry/initialize-connection! (fn [_request] nil)})
          target-connection (::registry/conn
                             (registry/lookup-connection
                              {::registry/database-name target-name}))]
      (is (true? (::registry/created? created)))
      (is (false? (::registry/adopted? created)))
      (is (= (assoc source-head ::coordinate/branch target-branch)
             (::registry/coordinate created)))
      (is (= "shared"
             (d/q '[:find ?value .
                    :where [_ :registry.lifecycle/value ?value]]
                  (d/db target-connection))))
      (d/transact target-connection
                  [{:registry.lifecycle/value "target-only"}])
      (is (nil? (d/q '[:find ?entity .
                        :where [?entity :registry.lifecycle/value "target-only"]]
                      (d/db source-connection))))
      (let [target-head
            (::registry/coordinate
             (registry/resolve-connection
              {::registry/database-name target-name}))
            release
            (registry/release-attachment!
             {::registry/target-database-name target-name
              ::registry/attachment (::registry/attachment created)
              ::registry/expected-target-head target-head})
            deleted
            (registry/delete-branch!
             {::registry/source-database-name source-name
              ::registry/target-database-name target-name
              ::registry/attachment (::registry/attachment created)
              ::registry/expected-target-head target-head})]
        (is (true? (::registry/released? release)))
        (is (false? (::registry/released? deleted))
            "delete is retryable after a separately completed release")
        (is (true? (::registry/deleted? deleted)))
        (is (not (contains? (d/branches source-connection) target-branch)))
        (is (some? (d/branch-as-db source-connection target-branch))
            "Datahike retains a stale raw head until garbage collection")
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"absent from Datahike's durable branch roster"
             (ensure-database!
              {::registry/database-name target-name
               ::registry/backend :memory
               ::registry/attachment (::registry/attachment created)})))))))

(deftest lifecycle-observation-reads-the-complete-native-roster
  (let [database-name :registry/lifecycle-observation
        main (ensure-database!
              {::registry/database-name database-name
               ::registry/backend :memory})
        connection (::registry/conn main)
        main-coordinate (coordinate/resolved (d/db connection))
        retained-branch :registry.branch/unopened]
    (d/branch! connection
               (::coordinate/commit-id main-coordinate)
               retained-branch)
    (let [retained-coordinate
          (coordinate/resolved (d/branch-as-db connection retained-branch))
          observation
          (registry/observe-database-lifecycle
           {::registry/database-name database-name})]
      (is (= #{:db retained-branch}
             (::registry/branch-roster observation)))
      (is (= main-coordinate (::registry/main-coordinate observation)))
      (is (= {:db main-coordinate retained-branch retained-coordinate}
             (::registry/branch-coordinates observation)))
      (is (= {}
             (registry/lookup-connection
              {::registry/database-name :registry/unopened-branch}))
          "native roster membership never depends on an open branch route"))))

(deftest lifecycle-observation-reads-complete-durable-restore-facts
  (let [database-name :registry/restore-observation
        main (ensure-database!
              {::registry/database-name database-name
               ::registry/backend :memory})
        connection (::registry/conn main)
        completion-id "restore00001"
        database-id (::coordinate/database-id
                     (coordinate/resolved (d/db connection)))
        completion
        {::db.restore/id completion-id
         ::db.restore/db-name database-name
         ::db.restore/database-id database-id
         ::db.restore/from-branch :db
         ::db.restore/from-commit-id (random-uuid)
         ::db.restore/from-t 536870920
         ::db.restore/to-branch :seon.branch/retained
         ::db.restore/to-commit-id (random-uuid)
         ::db.restore/to-t 536870900
         ::db.restore/forced-commit-id (random-uuid)
         ::db.restore/undo-branch :seon.restore.undo/r-restore00001
         ::db.restore/target-branch :seon.restore.target/r-restore00001}
        completion-schema
        (into [:map]
              (map (fn [attribute] [attribute attribute]))
              (rest db.restore/completion-attrs))]
    (d/transact
     connection
     (into [{:db/ident ::db.restore/id
             :db/valueType :db.type/string
             :db/cardinality :db.cardinality/one
             :db/unique :db.unique/identity}]
           (datahike.schema/malli-map->datahike-schema completion-schema)))
    (d/transact connection [completion])
    (let [main-db (d/db connection)
          completion-coordinate (coordinate/resolved main-db)
          completion-t
          (d/q '[:find ?transaction .
                 :in $ ?id
                 :where
                 [?completion :seon.db.restore/id ?id ?transaction true]]
               (d/history main-db)
               completion-id)
          _ (d/force-branch!
             main-db :db #{(::coordinate/commit-id completion-coordinate)}
             {:expected-current-commit
              (::coordinate/commit-id completion-coordinate)})
          _ (registry/release-database!
             {::registry/database-name database-name})
          reopened (ensure-database!
                    {::registry/database-name database-name
                     ::registry/backend :memory})
          reopened-db (d/db (::registry/conn reopened))
          observation
          (registry/observe-database-lifecycle
           {::registry/database-name database-name})]
      (is (= (set (or (d/parent-commit-ids reopened-db) []))
             (::registry/main-parent-commit-ids observation)))
      (is (= [completion] (::registry/restore-completions observation)))
      (is (= #{completion-id}
             (::registry/completed-restore-ids observation)))
      (is (= completion-t (::coordinate/t completion-coordinate)))
      (is (not= completion-coordinate (coordinate/resolved reopened-db))
          "the force commit repeats t at a different commit coordinate")
      (is (= {completion-id completion-coordinate}
             (::registry/restore-completion-coordinates observation)))
      (let [original-pull d/pull
            foreign
            (with-redefs [d/pull
                          (fn [db pattern lookup-ref]
                            (assoc (original-pull db pattern lookup-ref)
                                   ::db.restore/database-id (random-uuid)))]
              (try
                (registry/observe-database-lifecycle
                 {::registry/database-name database-name})
                nil
                (catch clojure.lang.ExceptionInfo exception exception)))]
        (is (= :seon.db.protocol.error/restore-divergence
               (:seon.error/kind (ex-data foreign)))))
      (let [original-pull d/pull
            malformed
            (with-redefs [d/pull
                          (fn [db pattern lookup-ref]
                            (dissoc (original-pull db pattern lookup-ref)
                                    ::db.restore/to-t))]
              (try
                (registry/observe-database-lifecycle
                 {::registry/database-name database-name})
                nil
                (catch clojure.lang.ExceptionInfo exception exception)))]
        (is (= :seon.db.protocol.error/restore-divergence
               (:seon.error/kind (ex-data malformed)))))
      (let [original-q d/q
            transaction-query
            '[:find ?id ?transaction
              :where
              [?completion :seon.db.restore/id ?id ?transaction true]]
            ambiguous
            (with-redefs [d/q
                          (fn [query db & inputs]
                            (if (= transaction-query query)
                              #{[completion-id completion-t]
                                [completion-id (inc completion-t)]}
                              (apply original-q query db inputs)))]
              (try
                (registry/observe-database-lifecycle
                 {::registry/database-name database-name})
                nil
                (catch clojure.lang.ExceptionInfo exception exception)))]
        (is (= :seon.db.protocol.error/restore-divergence
               (:seon.error/kind (ex-data ambiguous)))))
      (d/transact (::registry/conn reopened)
                  [[:db/add [::db.restore/id completion-id]
                    ::db.restore/to-t (inc (::db.restore/to-t completion))]])
      (let [mutated
            (try
              (registry/observe-database-lifecycle
               {::registry/database-name database-name})
              nil
              (catch clojure.lang.ExceptionInfo exception exception))]
        (is (= :seon.db.protocol.error/restore-divergence
               (:seon.error/kind (ex-data mutated)))
            "a later payload transaction invalidates the completion")))))

(deftest lifecycle-observation-fails-closed-on-partial-or-moving-storage
  (let [database-name :registry/lifecycle-observation-failure
        main (ensure-database!
              {::registry/database-name database-name
               ::registry/backend :memory})
        connection (::registry/conn main)
        original-branches d/branches
        original-branch-as-db d/branch-as-db
        missing
        (with-redefs [d/branches (fn [_] #{:db :registry.branch/missing})
                      d/branch-as-db
                      (fn [conn branch]
                        (when (= :db branch)
                          (original-branch-as-db conn branch)))]
          (try
            (registry/observe-database-lifecycle
             {::registry/database-name database-name})
            nil
            (catch clojure.lang.ExceptionInfo exception exception)))
        reads (atom 0)
        moved
        (with-redefs [d/branches
                      (fn [conn]
                        (if (= 1 (swap! reads inc))
                          (original-branches conn)
                          (conj (set (original-branches conn))
                                :registry.branch/late)))]
          (try
            (registry/observe-database-lifecycle
             {::registry/database-name database-name})
            nil
            (catch clojure.lang.ExceptionInfo exception exception)))
        branch-reads (atom 0)
        moved-head
        (with-redefs [d/branch-as-db
                      (fn [conn branch]
                        (when (= 2 (swap! branch-reads inc))
                          (d/transact
                           conn
                           [{:db/ident :registry.lifecycle.observation/moved
                             :db/valueType :db.type/boolean
                             :db/cardinality :db.cardinality/one}]))
                        (original-branch-as-db conn branch))]
          (try
            (registry/observe-database-lifecycle
             {::registry/database-name database-name})
            nil
            (catch clojure.lang.ExceptionInfo exception exception)))
        parent-reads (atom 0)
        parent-before (random-uuid)
        parent-after (random-uuid)
        moved-main-facts
        (with-redefs [d/parent-commit-ids
                      (fn [_]
                        #{(if (= 1 (swap! parent-reads inc))
                            parent-before
                            parent-after)})]
          (try
            (registry/observe-database-lifecycle
             {::registry/database-name database-name})
            nil
            (catch clojure.lang.ExceptionInfo exception exception)))]
    (is (= :seon.db.protocol.error/branch-missing
           (:seon.error/kind (ex-data missing))))
    (is (= :seon.db.protocol.error/stale-branch-roster
           (:seon.error/kind (ex-data moved))))
    (is (= :seon.db.protocol.error/stale-target-head
           (:seon.error/kind (ex-data moved-head))))
    (is (= :seon.db.protocol.error/stale-target-head
           (:seon.error/kind (ex-data moved-main-facts))))
    (is (= #{:db}
           (set (original-branches connection))))))

(deftest native-branch-create-retries-adopt-the-exact-published-route
  (let [source-name :registry/retry-source
        target-name :registry/retry-target
        target-branch :registry.branch/retry
        source (ensure-database!
                {::registry/database-name source-name
                 ::registry/backend :memory})
        source-connection (::registry/conn source)
        source-head (::registry/coordinate
                     (registry/resolve-connection
                      {::registry/database-name source-name}))
        request {::registry/source-database-name source-name
                 ::registry/target-database-name target-name
                 ::registry/source-coordinate source-head
                 ::registry/expected-source-head source-head
                 ::registry/target-branch target-branch
                 ::registry/initialize-connection! (fn [_request] nil)}
        created (registry/create-branch! request)
        immediate-retry (registry/create-branch! request)
        target-connection (::registry/conn
                           (registry/lookup-connection
                            {::registry/database-name target-name}))]
    (is (true? (::registry/created? created)))
    (is (false? (::registry/created? immediate-retry)))
    (is (true? (::registry/adopted? immediate-retry)))
    (is (= (::registry/coordinate created)
           (::registry/coordinate immediate-retry)))
    (d/transact target-connection
                [{:db/ident :registry.retry/value
                  :db/valueType :db.type/string
                  :db/cardinality :db.cardinality/one}])
    (let [advanced-head (::registry/coordinate
                         (registry/resolve-connection
                          {::registry/database-name target-name}))
          restart-retry (registry/create-branch! request)]
      (is (not= (::registry/coordinate created) advanced-head))
      (is (false? (::registry/created? restart-retry)))
      (is (true? (::registry/adopted? restart-retry)))
      (is (= advanced-head (::registry/coordinate restart-retry))
          "a retained create intent adopts the exact route at its fresh head"))))

(deftest native-branch-create-adopts-retained-branch-before-source-head-fence
  (let [source-name :registry/retained-retry-source
        source (ensure-database!
                {::registry/database-name source-name
                 ::registry/backend :memory})
        source-connection (::registry/conn source)
        source-head (coordinate/resolved (d/db source-connection))
        adopted-branch :registry.branch/retained-retry
        mismatch-branch :registry.branch/retained-mismatch
        stale-new-branch :registry.branch/stale-new]
    (d/branch! source-connection
               (::coordinate/commit-id source-head)
               adopted-branch)
    (d/transact source-connection
                [{:db/ident :registry.retained/value
                  :db/valueType :db.type/string
                  :db/cardinality :db.cardinality/one}])
    (let [current-source-head (coordinate/resolved (d/db source-connection))
          _ (d/branch! source-connection
                       (::coordinate/commit-id current-source-head)
                       mismatch-branch)
          adopted
          (registry/create-branch!
           {::registry/source-database-name source-name
            ::registry/target-database-name :registry/retained-retry-target
            ::registry/source-coordinate source-head
            ::registry/expected-source-head source-head
            ::registry/target-branch adopted-branch
            ::registry/initialize-connection! (fn [_request] nil)})
          mismatch
          (try
            (registry/create-branch!
             {::registry/source-database-name source-name
              ::registry/target-database-name :registry/retained-mismatch-target
              ::registry/source-coordinate source-head
              ::registry/expected-source-head source-head
              ::registry/target-branch mismatch-branch
              ::registry/initialize-connection! (fn [_request] nil)})
            nil
            (catch clojure.lang.ExceptionInfo exception exception))
          stale-new
          (try
            (registry/create-branch!
             {::registry/source-database-name source-name
              ::registry/target-database-name :registry/stale-new-target
              ::registry/source-coordinate source-head
              ::registry/expected-source-head source-head
              ::registry/target-branch stale-new-branch
              ::registry/initialize-connection! (fn [_request] nil)})
            nil
            (catch clojure.lang.ExceptionInfo exception exception))]
      (is (false? (::registry/created? adopted)))
      (is (true? (::registry/adopted? adopted)))
      (is (= (assoc source-head ::coordinate/branch adopted-branch)
             (::registry/coordinate adopted)))
      (is (= :seon.db.protocol.error/branch-exists
             (:seon.error/kind (ex-data mismatch))))
      (is (= :seon.db.protocol.error/stale-source-head
             (:seon.error/kind (ex-data stale-new))))
      (is (not (contains? (d/branches source-connection) stale-new-branch))))))

(deftest native-branch-create-rejects-contained-cut-and-adopts-exact-head
  (let [source-name :registry/lifecycle-cut-source
        source (ensure-database!
                {::registry/database-name source-name
                 ::registry/backend :memory})
        source-connection (::registry/conn source)]
    (d/transact source-connection
                [{:db/ident :registry.lifecycle.cut/value
                  :db/valueType :db.type/string
                  :db/cardinality :db.cardinality/one}])
    (let [source-db (d/db source-connection)
          source-head (coordinate/resolved source-db)
          contained-cut
          (coordinate/at
           {::coordinate/db-value source-db
            ::coordinate/target-t (dec (::coordinate/t source-head))})
          cut-branch :registry.branch/cut
          cut-failure
          (try
            (registry/create-branch!
             {::registry/source-database-name source-name
              ::registry/target-database-name :registry/lifecycle-cut-target
              ::registry/source-coordinate contained-cut
              ::registry/expected-source-head source-head
              ::registry/target-branch cut-branch
              ::registry/initialize-connection! (fn [_request] nil)})
            nil
            (catch clojure.lang.ExceptionInfo exception exception))]
      (is (= :seon.db.protocol.error/cut-not-branchable
             (:seon.error/kind (ex-data cut-failure))))
      (is (not (contains? (d/branches source-connection) cut-branch)))
      (let [created-failure-branch :registry.branch/created-failure
            created-failure-name :registry/lifecycle-created-failure
            failure
            (try
              (registry/create-branch!
               {::registry/source-database-name source-name
                ::registry/target-database-name created-failure-name
                ::registry/source-coordinate source-head
                ::registry/expected-source-head source-head
                ::registry/target-branch created-failure-branch
                ::registry/initialize-connection!
                (fn [_request]
                  (throw (ex-info "injected created open failure" {})))})
              nil
              (catch clojure.lang.ExceptionInfo exception exception))]
        (is (re-find #"injected created open failure" (.getMessage failure)))
        (is (not (contains? (d/branches source-connection)
                            created-failure-branch))
            "a branch created by the failed invocation is cleanup-owned")
        (is (= {} (registry/lookup-connection
                   {::registry/database-name created-failure-name}))))
      (let [adopted-branch :registry.branch/adopted
            _ (d/branch! source-connection
                         (::coordinate/commit-id source-head)
                         adopted-branch)
            adopted
            (registry/create-branch!
             {::registry/source-database-name source-name
              ::registry/target-database-name :registry/lifecycle-adopted
              ::registry/source-coordinate source-head
              ::registry/expected-source-head source-head
              ::registry/target-branch adopted-branch
              ::registry/initialize-connection! (fn [_request] nil)})]
        (is (false? (::registry/created? adopted)))
        (is (true? (::registry/adopted? adopted)))
        (is (= (assoc source-head ::coordinate/branch adopted-branch)
               (::registry/coordinate adopted))))
      (let [retained-branch :registry.branch/retained-adoption
            retained-name :registry/lifecycle-retained-adoption
            _ (d/branch! source-connection
                         (::coordinate/commit-id source-head)
                         retained-branch)
            failure
            (try
              (registry/create-branch!
               {::registry/source-database-name source-name
                ::registry/target-database-name retained-name
                ::registry/source-coordinate source-head
                ::registry/expected-source-head source-head
                ::registry/target-branch retained-branch
                ::registry/initialize-connection!
                (fn [_request]
                  (throw (ex-info "injected adopted open failure" {})))})
              nil
              (catch clojure.lang.ExceptionInfo exception exception))]
        (is (re-find #"injected adopted open failure" (.getMessage failure)))
        (is (contains? (d/branches source-connection) retained-branch)
            "an exact pre-existing branch is never cleanup-owned")
        (is (= {} (registry/lookup-connection
                   {::registry/database-name retained-name})))))))
