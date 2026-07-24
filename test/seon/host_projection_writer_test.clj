(ns seon.host-projection-writer-test
  "Committed schema projection admission and publication proofs."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.host :as host]
            [seon.host.context :as context]
            [seon.host.sample :as host.sample]
            [seon.schema :as schema]))

(defn- projection [k form]
  (schema/build-projection {k form}))

(defn- acquired [t k form]
  {::context/database {:db-name "projection-test" :t t}
   ::context/projection (projection k form)})

(defn- acquisition-functions
  [populations calls]
  (let [form-entities
        (into {}
              (map
                (fn [[[identity-attr form-attr] rows]]
                  [[identity-attr form-attr]
                   (mapv (fn [i row]
                           {:entity-id [identity-attr form-attr i]
                            :row row})
                         (range)
                         rows)]))
              populations)
        identity-entities
        (reduce-kv
          (fn [result [identity-attr _] entities]
            (update result identity-attr (fnil into []) entities))
          {}
          form-entities)]
    {'index-page
     (fn [request]
       (swap! calls conj [:index-page request])
       (let [identity-attr (first (:seon.db/components request))
             offset (or (:seon.db/cursor request) 0)
             population (get identity-entities identity-attr [])
             page (subvec population
                          offset
                          (min (count population)
                               (+ offset (:seon.db/limit request))))
             next-offset (+ offset (count page))]
         (cond-> {:datahike.index-page/datoms
                  (mapv (comp vector :entity-id) page)
                  :datahike.index-page/complete?
                  (= next-offset (count population))}
           (< next-offset (count population))
           (assoc :datahike.index-page/cursor next-offset))))
     'query
     (fn [request]
       (swap! calls conj [:query request])
       (let [[[entity-id] identity-attr form-attr] (:seon.db/args request)]
         (if-let [entry
                  (some #(when (= entity-id (:entity-id %)) %)
                        (get form-entities [identity-attr form-attr]))]
           [(:row entry)]
           [])))}))

(deftest publication-is-monotonic-across-success-and-fault-races
  (let [state (atom (acquired 9 :projection.test/nine :int))
        ten (acquired 10 :projection.test/ten :int)
        eleven (acquired 11 :projection.test/eleven :int)]
    (context/publish-committed-projection! state eleven)
    (context/publish-committed-projection! state ten)
    (is (= 11 (get-in @state [::context/database :t])))
    (reset! state {::context/fault {:seon.error/message "t13 failed"}
                   ::context/committed-basis 13})
    (context/publish-committed-projection! state (acquired 12 :projection.test/twelve :int))
    (is (= 13 (::context/committed-basis @state)))
    (context/publish-committed-projection! state (acquired 13 :projection.test/thirteen :int))
    (is (= 13 (get-in @state [::context/database :t])))
    (is (nil? (::context/fault @state)))))

(deftest a-newer-generation-fault-refuses-the-host-drill-read
  (let [fault {:seon.error/message "projection refresh failed"
               :seon.error/kind :core-bug}
        state (atom {::context/fault fault
                     ::context/committed-basis 11})
        result (host.sample/drill-value
                 {::host/projection-state state}
                 {:projection.test/id 1}
                 {:seon.render.value/path []
                  :seon.render.value/offset 0
                  :seon.render.value/effective-limits
                  {:seon.config.render/value-max-path-segments 32
                   :seon.config.render/value-max-path-bytes 4096
                   :seon.config.render/value-max-realized-items 32
                   :seon.config.render/value-max-depth 3
                   :seon.config.render/value-max-string 80
                   :seon.config.render/value-shape-sample 8
                   :seon.render.value/page-size 8}})]
    (is (false? (:seon.render.value/ok? result)))
    (is (= {:seon.error/message
            "Schema-aware value browsing is unavailable."
            :seon.error/kind :core-bug}
           (:seon/error result)))
    (is (< (count (pr-str result)) 256))))

(deftest one-database-value-pins-both-authority-queries
  (let [database {:db-name "projection-test" :t 42}
        calls (atom [])
        populations
        {[:seon.schema/key :seon.schema/form]
         [[:projection.test/value ":int"
           {:seon.db/process
            {:seon.db.process/id :seon.db.process/boot}}]]
         [:seon.fn/sym :seon.fn/spec] []
         [:seon.fn/sym :seon.fn/source] []}]
    (with-redefs [context/resolve-head! (fn [_] database)]
      (with-redefs-fn
        {#'context/bound-database-functions
         (fn [_] (acquisition-functions populations calls))}
        (fn []
          (let [result (context/acquire-committed-projection! {})]
            (is (= 42 (get-in result [::context/database :t])))
            (is (every? #(= database (:seon.db/db (second %))) @calls))
            (is (every? #(= 60000
                            (:seon.db/max-result-weight (second %)))
                        @calls))
            (is (= 3 (count (filter #(= :index-page (first %)) @calls))))
            (is (= 1 (count (filter #(= :query (first %)) @calls))))))))))

(deftest variable-size-source-population-is-bounded-per-row-not-in-aggregate
  (let [database {:db-name "projection-test" :t 42}
        calls (atom [])
        source (apply str (repeat 2000 "x"))
        source-rows
        (mapv (fn [i]
                [(str "projection.test/f" i) source
                 {:seon.db/process
                  {:seon.db.process/id :seon.db.process/boot}}])
              (range 40))
        populations
        {[:seon.schema/key :seon.schema/form] []
         [:seon.fn/sym :seon.fn/spec] []
         [:seon.fn/sym :seon.fn/source] source-rows}]
    (with-redefs [context/resolve-head! (fn [_] database)]
      (with-redefs-fn
        {#'context/bound-database-functions
         (fn [_] (acquisition-functions populations calls))}
        (fn []
          (let [result (context/acquire-committed-projection! {})
                source-queries
                (filter
                  (fn [[operation request]]
                    (and (= :query operation)
                         (= :seon.fn/source
                            (nth (:seon.db/args request) 2))))
                  @calls)]
            (is (nil? (:seon/error result)))
            (is (> (reduce + (map (comp count second) source-rows))
                   60000))
            (is (= 40 (count source-queries)))
            (is (every? #(= 1
                            (count (first
                                     (:seon.db/args (second %)))))
                        source-queries))))))))

(deftest acquisition-failure-names-the-population-and-stage
  (let [database {:db-name "projection-test" :t 42}
        calls (atom [])
        functions
        (assoc
          (acquisition-functions
            {[:seon.schema/key :seon.schema/form] []
             [:seon.fn/sym :seon.fn/spec] []
             [:seon.fn/sym :seon.fn/source]
             [["projection.test/f" "(defn f [] 1)"
               {:seon.db/process
                {:seon.db.process/id :seon.db.process/boot}}]]}
            calls)
          'query
          (fn [request]
            (if (= :seon.fn/source
                   (nth (:seon.db/args request) 2))
              {:seon.error/message "bounded source read rejected"
               :seon.error/kind :resource-limit}
              [])))]
    (with-redefs [context/resolve-head! (fn [_] database)]
      (with-redefs-fn
        {#'context/bound-database-functions (fn [_] functions)}
        (fn []
          (let [error
                (:seon/error
                  (context/acquire-committed-projection! {}))]
            (is (= :query
                   (get-in error
                           [:seon.error/data
                            :seon.host.context/stage])))
            (is (= :seon.fn/sym
                   (get-in error
                           [:seon.error/data
                            :seon.host.context/identity-attribute])))
            (is (= :seon.fn/source
                   (get-in error
                           [:seon.error/data
                            :seon.host.context/form-attribute])))
            (is (= "bounded source read rejected"
                   (get-in error
                           [:seon.error/data
                            :seon.db/error
                            :seon.error/message])))))))))

(deftest acquisition-does-not-replace-jvm-private-candidates
  (let [before (schema/snapshot-state)
        private-key :projection.test.jvm/private]
    (try
      (schema/register! private-key [:map [:projection.test.jvm/id :int]])
      (let [database {:db-name "projection-test" :t 5}
            calls (atom [])
            populations
            {[:seon.schema/key :seon.schema/form]
             [[:projection.test.browser/id ":int"]
              [:projection.test.browser/shape
               "[:map [:projection.test.browser/id :projection.test.browser/id]]"]]
             [:seon.fn/sym :seon.fn/spec] []
             [:seon.fn/sym :seon.fn/source] []}]
        (with-redefs [context/resolve-head! (fn [_] database)]
          (with-redefs-fn
            {#'context/bound-database-functions
             (fn [_] (acquisition-functions populations calls))}
            (fn []
              (let [result (context/acquire-committed-projection! {})]
                (is (schema/valid-candidate-value?
                      private-key {:projection.test.jvm/id 1}))
                (is (not (contains?
                           (:seon.schema.projection/forms
                             (::context/projection result))
                           private-key))))))))
      (finally (schema/restore-state! before)))))

(deftest malformed-population-refuses-host-before-readiness
  (let [closed? (atom false)]
    (with-redefs [context/writer-session
                  (fn [_] {::context/base-projection (atom nil)})
                  context/close-session! (fn [_] (reset! closed? true))
                  context/load-base-projection!
                  (fn [_ _]
                    {:seon.dev.artifact/base-projection
                     (schema/build-projection {})})
                  context/resolve-head! (fn [_] {:db-name "unused" :t 1})
                  context/verify-applied-identity! (fn [& _] nil)
                  context/build-base!
                  (fn [& _]
                    {::context/tier-inventory
                     {:seon.execution.inventory/tier :jvm
                      :seon.execution.inventory/bindings #{}
                      :seon.execution.inventory/remote-bindings #{}
                      :seon.execution.inventory/pure-bindings #{}
                      :seon.execution.inventory/digest "empty"}})
                  context/acquire-preprocessed-projection!
                  (fn [& _] {:seon/error
                             {:seon.error/message "unresolved committed form"
                              :seon.error/kind :core-bug}})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"unresolved committed form"
                            (host/start! {::host/socket-path "tmp/never-ready.sock"
                                          ::context/writer-socket-path "unused"
                                          ::context/database-name "unused"})))
      (is @closed?))))

(deftest terminal-result-reports-projection-change-from-actual-tx-rows
  (let [before (schema/snapshot-state)
        at (java.util.Date.)
        request {:seon.eval/id "eval-test"
                 ::context/envelope {:seon.eval/ok? true
                                     :seon.eval/result :ok}
                 ::context/at at
                 ::context/duration-ms 1
                 ::context/source "(schema/register! :projection.test/new :int)"
                 ::context/narration ""
                 ::context/ns-sym 'projection.test
                 ::context/agent-id "agent-test"
                 ::context/forms
                 '[(schema/register! :projection.test/new :int)]
                 ::context/var-meta {}
                 ::context/new-schema-keys #{:projection.test/new}}]
    (try
      (schema/register! :projection.test/new :int)
      (with-redefs-fn
        {#'context/resolve-head! (fn [_] {:db-name "projection-test" :t 6})
         #'context/maintain-host-divergence-cache
         (fn [_ _ _]
           {::context/cache-row
            {:seon.runtime.admission.cache/id "committed-projection"}
            ::context/projection (projection :projection.test/new :int)})
         #'context/record-transact!
         (fn [_ request]
           (is (= 1 (count (filter :seon.runtime.admission.cache/id
                                   (::context/tx-data request))))
               "program and cache rows are one transaction")
           {:seon.db/ok? true :db-after {:t 7}})}
        (fn []
          (is (true? (::context/projection-changed?
                       (context/record-eval-terminal! {} request))))))
      (finally (schema/restore-state! before)))))

(deftest a-covering-newer-generation-makes-an-older-refresh-failure-harmless
  (let [state (atom (acquired 11 :projection.test/eleven :int))]
    (with-redefs [context/acquire-committed-projection!
                  (fn [_ _] {:seon/error
                           {:seon.error/message "late t10 refresh failed"
                            :seon.error/kind :core-bug}})]
      (is (= 11
             (get-in (context/refresh-committed-projection! {} state 10)
                     [::context/database :t])))
      (is (= 11 (get-in @state [::context/database :t]))))))

(deftest older-failure-and-success-cannot-lower-a-newer-fault-floor
  (let [state (atom (acquired 9 :projection.test/nine :int))
        responses (atom [{:seon/error
                          {:seon.error/message "t13 failed"
                           :seon.error/kind :core-bug}}
                         {:seon/error
                          {:seon.error/message "t12 failed"
                           :seon.error/kind :core-bug}}
                         (acquired 12 :projection.test/twelve :int)])]
    (with-redefs [context/acquire-committed-projection!
                  (fn [_ _] (let [response (first @responses)]
                            (swap! responses subvec 1)
                            response))]
      (context/refresh-committed-projection! {} state 13)
      (context/refresh-committed-projection! {} state 12)
      (context/refresh-committed-projection! {} state 12)
      (is (= 13 (::context/committed-basis @state)))
      (is (= "t13 failed"
             (get-in @state [::context/fault :seon.error/message]))))))

(deftest failed-refresh-preserves-artifact-exports-for-recovery
  (let [exports #{'my.compiled/renderer}
        initial
        (update (acquired 9 :projection.test/nine :int)
                ::context/projection
                assoc :seon.schema.projection/artifact-exports exports)
        state (atom initial)
        observed-exports (atom [])]
    (with-redefs [context/acquire-committed-projection!
                  (fn [_ artifact-exports]
                    (swap! observed-exports conj artifact-exports)
                    (if (= 1 (count @observed-exports))
                      {:seon/error
                       {:seon.error/message "t10 failed"
                        :seon.error/kind :core-bug}}
                      (assoc (acquired 10 :projection.test/ten :int)
                             ::context/projection
                             (assoc (projection :projection.test/ten :int)
                                    :seon.schema.projection/artifact-exports
                                    artifact-exports))))]
      (context/refresh-committed-projection! {} state 10)
      (context/refresh-committed-projection! {} state 10)
      (is (= [exports exports] @observed-exports))
      (is (= exports
             (get-in @state
                     [::context/projection
                      :seon.schema.projection/artifact-exports]))))))

(deftest stale-success-after-a-newer-commit-faults-and-refuses-drill
  (let [state (atom (acquired 8 :projection.test/eight :int))]
    (with-redefs [context/acquire-committed-projection!
                  (fn [_ _] (acquired 9 :projection.test/nine :int))]
      (is (= :core-bug
             (get-in (context/refresh-committed-projection! {} state 10)
                     [:seon/error :seon.error/kind])))
      (is (= 10 (::context/committed-basis @state)))
      (is (:seon/error (context/current-committed-projection state))))))

(deftest drill-refuses-through-the-whole-post-commit-refresh-window
  (let [state (atom (acquired 9 :projection.test/nine :int))
        entered (promise)
        release (promise)]
    (with-redefs [context/acquire-committed-projection!
                  (fn [_ _]
                    (deliver entered true)
                    @release
                    (acquired 10 :projection.test/ten :int))]
      (let [refresh (future
                      (context/refresh-committed-projection! {} state 10))]
        @entered
        (is (= 10 (::context/committed-basis @state)))
        (is (:seon/error (context/current-committed-projection state)))
        (deliver release true)
        (is (= 10 (get-in @refresh [::context/database :t])))
        (is (= 10 (get-in @state [::context/database :t])))
        (is (nil? (::context/fault @state)))))))
