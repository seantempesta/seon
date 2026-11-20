(ns seon.reconciler-test
  "Fast (<50ms) unit tests for `seon.reconciler`, `seon.schema.datahike`, and `seon.health.static`.
   Uses in-memory Datahike DBs and pure vmap/registry inputs so the suite has no disk I/O."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [malli.core :as m]
            [malli.registry :as mr]
            [seon.reconciler :as rec]
            [seon.schema.datahike :as dh]
            [seon.health.static :as hs]
            [seon.store :as store]))

(def ^:private agent-schema
  [{:db/ident :seon.agent/id :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :seon.agent/branch :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :seon.agent/paused? :db/valueType :db.type/boolean :db/cardinality :db.cardinality/one}
   {:db/ident :seon.agent/archived? :db/valueType :db.type/boolean :db/cardinality :db.cardinality/one}
   {:db/ident :seon.agent/waiting? :db/valueType :db.type/boolean :db/cardinality :db.cardinality/one}
   {:db/ident :seon.agent/ready? :db/valueType :db.type/boolean :db/cardinality :db.cardinality/one}
   {:db/ident :seon.agent/tested-tx :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :seon.agent/merged-tx :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :seon.agent/auto-merge? :db/valueType :db.type/boolean :db/cardinality :db.cardinality/one}
   {:db/ident :seon.agent/awaiting :db/valueType :db.type/uuid :db/cardinality :db.cardinality/many}
   {:db/ident :seon.agent.message/id :db/valueType :db.type/uuid :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :seon.agent.message/seq :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :seon.agent.message/to :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one :db/index true}
   {:db/ident :seon.agent.eval/agent :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one :db/index true}
   {:db/ident :seon.agent.call/id :db/valueType :db.type/uuid :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :seon.agent.call/caller :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one :db/index true}
   {:db/ident :seon.agent.call/callee :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one :db/index true}
   {:db/ident :seon.agent.call/status :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one :db/index true}
   {:db/ident :seon.agent.call/delivered? :db/valueType :db.type/boolean :db/cardinality :db.cardinality/one}])

(defn- mem-db
  "Creates an isolated in-memory Datahike DB, transacts `schema` then each `tx-batch`,
   and returns the resulting immutable DB value (sub-ms to build)."
  [schema & tx-batches]
  (let [cfg {:store {:backend :memory :id (random-uuid)}}
        _   (d/create-database cfg)
        c   (d/connect cfg)]
    (d/transact c schema)
    (doseq [tx tx-batches] (when tx (d/transact c tx)))
    (d/db c)))

(def ^:private awaiting-cid (random-uuid))

(def ^:private unread-mid (random-uuid))

(def ^:private reconciler-db
  (delay
    (mem-db agent-schema
            [{:seon.agent/id :main :seon.agent/branch :main
              :seon.agent/ready? false :seon.agent/waiting? false}
             {:seon.agent/id :a/paused :seon.agent/branch :branch/p :seon.agent/paused? true}
             {:seon.agent/id :a/archived :seon.agent/branch :branch/ar :seon.agent/archived? true}
             {:seon.agent/id :a/await :seon.agent/branch :branch/aw :seon.agent/awaiting [awaiting-cid]}
             {:seon.agent.call/id awaiting-cid :seon.agent.call/caller :a/other
              :seon.agent.call/callee :a/await :seon.agent.call/status :pending}
             {:seon.agent/id :a/busy :seon.agent/branch :branch/busy}
             {:seon.agent.message/id unread-mid :seon.agent.message/to :a/active :seon.agent.message/seq 1}
             {:seon.agent/id :a/active :seon.agent/branch :branch/act}
             {:seon.agent/id :a/ready :seon.agent/branch :branch/rdy
              :seon.agent/ready? true :seon.agent/auto-merge? true}])))

(defn- derive*
  "Runs `derive-agent-status` against the shared in-memory DB with store lookups stubbed
   (branch head == main DB, no seen messages, no config)."
  ([aid] (derive* aid {}))
  ([aid live-map]
   (with-redefs [store/head (fn [_ _] nil)
                 store/agent-seen-message-ids (fn [_ _] #{})
                 store/config-val (fn [_ _ _] nil)]
     (rec/derive-agent-status @reconciler-db nil live-map #{} #{} #{} aid))))

(deftest ^{:cost/ticks 4000000} status-derivation-test
  (testing "waiting / paused / archived terminals"
    (is (= :waiting (:status (derive* :main))))
    (is (= :paused (:status (derive* :a/paused))))
    (is (nil? (derive* :a/archived)) "archived agents are invisible to the reconciler"))
  (testing "unread message -> runnable turn"
    (let [v (derive* :a/active)]
      (is (= :runnable (:status v)))
      (is (some? (:needs-turn? v)))
      (is (= 1 (:unread-count v)))
      (is (false? (:needs-test? v)))))
  (testing "busy via live-map -> running"
    (is (= :running (:status (derive* :a/busy {:a/busy {:pid 1 :busy? true}})))))
  (testing "pending incoming call -> awaiting"
    (let [v (derive* :a/await)]
      (is (= :awaiting (:status v)))
      (is (= 1 (:awaiting-count v)))
      (is (false? (:needs-turn? v)))))
  (testing "ready + auto-merge + untested head -> testing"
    (let [v (derive* :a/ready)]
      (is (= :testing (:status v)))
      (is (true? (:needs-test? v)))
      (is (false? (:needs-merge? v))))))

(deftest ^{:cost/ticks 1500000} reconciler-proc-state-test
  (testing "init frame"
    (let [st (rec/reconciler-proc {:conn nil})]
      (is (= {} (:live st)))
      (is (= 0 (:main-tx st)))
      (is (contains? st :testing))))
  (testing "nil message passes state through"
    (let [st (rec/reconciler-proc {:conn nil})]
      (is (= [st nil] (rec/reconciler-proc st :db nil)))))
  (testing "exits drive the live-map: fork -> busy -> idle -> exit"
    (let [st        (rec/reconciler-proc {:conn nil})
          [s1 _]    (rec/reconciler-proc st :exits {:agent-id :a :ev :forked :pid 7})
          [s2 _]    (rec/reconciler-proc s1 :exits {:agent-id :a :ev :turn-idle :pid 7})
          [s3 _]    (rec/reconciler-proc s2 :exits {:agent-id :a :ev :exited :pid 7})]
      (is (= {:pid 7 :busy? true} (get (:live s1) :a)))
      (is (false? (get-in s2 [:live :a :busy?])))
      (is (nil? (get (:live s3) :a)) "exit removes the live entry once pid matches"))))

(defn- user-registry []
  (mr/registry
   {:user/id     (m/schema [:and {:seon.db/identity true} :keyword])
    :user/name   (m/schema :string)
    :user/tags   (m/schema [:set :keyword])
    :user/score  (m/schema [:and {:seon.db/index true} :int])
    :user/uniq   (m/schema [:and {:seon.db/unique true} :string])
    :user/entity (m/schema [:map {:seon.db/attributes true}
                            [:user/id [:and {:seon.db/identity true} :keyword]]
                            [:user/name :string]
                            [:user/tags {:optional true} [:set :keyword]]])}))

(deftest ^{:cost/ticks 1500000} datahike-type-mapping-test
  (testing "primitive type mapping"
    (is (= :db.type/string (dh/type->datahike :string)))
    (is (= :db.type/long (dh/type->datahike :int)))
    (is (= :db.type/keyword (dh/type->datahike :qualified-keyword)))
    (is (= :db.type/uuid (dh/type->datahike :uuid)))
    (is (= :db.type/instant (dh/type->datahike 'inst?)))))

(deftest ^{:cost/ticks 2500000} datahike-declaration-test
  (let [reg (user-registry)]
    (testing "unique identity attribute"
      (is (= {:db/ident :user/id :db/valueType :db.type/keyword
              :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
             (dh/declaration reg :user/id))))
    (testing "set -> cardinality many"
      (let [d (dh/declaration reg :user/tags)]
        (is (= :db.cardinality/many (:db/cardinality d)))
        (is (= :db.type/keyword (:db/valueType d)))))
    (testing "index + unique props"
      (is (true? (:db/index (dh/declaration reg :user/score))))
      (is (= :db.unique/value (:db/unique (dh/declaration reg :user/uniq)))))
    (testing "storable? guards"
      (is (true? (dh/storable? reg :user/name)))
      (is (false? (dh/storable? reg :user/missing))))
    (testing "unstorable schema throws ex-info"
      (let [bad (mr/registry {:x (m/schema [:maybe :string])})]
        (is (thrown? clojure.lang.ExceptionInfo (dh/declaration bad :x)))))))

(deftest ^{:cost/ticks 2000000} datahike-attribute-keys-and-metadata-test
  (let [reg (user-registry)
        ks  (set (dh/attribute-keys reg))
        md  (dh/merge-metadata reg)]
    (testing "attribute-keys mixes entity-map members and prop-carrying schemas"
      (is (contains? ks :user/id))
      (is (contains? ks :user/name))
      (is (contains? ks :user/tags))
      (is (contains? ks :user/score))
      (is (contains? ks :user/uniq)))
    (testing "merge-metadata identity / many / skipped"
      (is (some #{:user/id} (:identity-attrs md)))
      (is (contains? (:many-attrs md) :user/tags))
      (is (contains? (:skipped-attrs md) :db/id))
      (is (contains? (:skipped-attrs md) :db/txInstant)))
    (testing "declarations are sorted by attribute name"
      (let [ids (mapv :db/ident (dh/declarations reg))]
        (is (= ids (vec (sort-by str ids))))))))

(deftest ^{:cost/ticks 1500000} blocking-in-flow-procs-test
  (let [vmap {'seon.demo/blocking-proc {:seon.code.var/ns 'seon.demo
                                        :seon.code.var/refs #{'clojure.core.async/<!!}}
              'seon.demo/caller-proc   {:seon.code.var/ns 'seon.demo
                                        :seon.code.var/refs #{'seon.demo/blocking-proc}}
              'seon.demo/clean-proc    {:seon.code.var/ns 'seon.demo
                                        :seon.code.var/refs #{}}
              'seon.demo/edge-proc     {:seon.code.var/ns 'seon.demo
                                        :seon.code.var/flow-edge? true
                                        :seon.code.var/refs #{'clojure.core.async/<!!}}}
        res (hs/blocking-in-flow-procs @reconciler-db vmap)
        subjects (set (map :subject res))]
    (testing "direct and transitive blocking procs flagged"
      (is (contains? subjects "seon.demo/blocking-proc"))
      (is (contains? subjects "seon.demo/caller-proc"))
      (is (= 2 (count res))))
    (testing "clean and flow-edge procs ignored"
      (is (not (contains? subjects "seon.demo/clean-proc")))
      (is (not (contains? subjects "seon.demo/edge-proc"))))
    (testing "finding shape"
      (is (every? #(= :blocking-in-flow-proc (:class %)) res))
      (is (every? #(= :error (:severity %)) res)))))

(deftest ^{:cost/ticks 1200000} state-outside-flow-test
  (let [vmap {'seon.demo/state      {:seon.code.var/ns 'seon.demo
                                     :seon.code.var/arglists []
                                     :seon.code.var/refs #{'clojure.core/atom}}
              'seon.demo/fn-var     {:seon.code.var/ns 'seon.demo
                                     :seon.code.var/arglists '[[]]
                                     :seon.code.var/refs #{'clojure.core/atom}}
              'seon.demo/edge-state {:seon.code.var/ns 'seon.demo
                                     :seon.code.var/arglists []
                                     :seon.code.var/flow-edge? true
                                     :seon.code.var/refs #{'clojure.core/atom}}}
        res (hs/state-outside-flow @reconciler-db vmap)]
    (testing "only a non-function, non-edge top-level atom is flagged"
      (is (= 1 (count res)))
      (is (= "seon.demo/state" (:subject (first res))))
      (is (= :state-outside-flow (:class (first res))))
      (is (= :warning (:severity (first res)))))))

(def ^:private code-schema
  [{:db/ident :seon.code.form/key :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/index true}
   {:db/ident :seon.code.form/ns :db/valueType :db.type/symbol :db/cardinality :db.cardinality/one :db/index true}
   {:db/ident :seon.code.form/order :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :seon.code.form/deps :db/valueType :db.type/symbol :db/cardinality :db.cardinality/many}
   {:db/ident :seon.code.form/src :db/valueType :db.type/string :db/cardinality :db.cardinality/one}])

(def ^:private code-db
  (delay
    (mem-db code-schema
            [{:seon.code.form/key "foo/a" :seon.code.form/ns 'foo :seon.code.form/order 0
              :seon.code.form/deps ['foo/b]}
             {:seon.code.form/key "foo/b" :seon.code.form/ns 'foo :seon.code.form/order 1}
             ;; bar: reference is to an earlier definition -> clean
             {:seon.code.form/key "bar/x" :seon.code.form/ns 'bar :seon.code.form/order 1
              :seon.code.form/deps ['bar/y]}
             {:seon.code.form/key "bar/y" :seon.code.form/ns 'bar :seon.code.form/order 0}
             ;; baz: forward ref but preceded by a (declare ...) -> clean
             {:seon.code.form/key "baz#expr#0" :seon.code.form/ns 'baz :seon.code.form/order -1
              :seon.code.form/src "(declare baz/w)"}
             {:seon.code.form/key "baz/z" :seon.code.form/ns 'baz :seon.code.form/order 0
              :seon.code.form/deps ['baz/w]}
             {:seon.code.form/key "baz/w" :seon.code.form/ns 'baz :seon.code.form/order 1}])))

(deftest ^{:cost/ticks 2000000} used-before-defined-test
  (let [res (hs/used-before-defined @code-db ['foo 'bar 'baz])]
    (testing "forward reference without declare is flagged"
      (is (= 1 (count res)))
      (is (= "foo/a" (:subject (first res))))
      (is (= :used-before-defined (:class (first res))))
      (is (= 'foo (:ns (first res)))))
    (testing "earlier refs and declared forward refs are clean"
      (is (not-any? #(= 'bar (:ns %)) res))
      (is (not-any? #(= 'baz (:ns %)) res)))))
