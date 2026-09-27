(ns seon.custody-stability-test
  "Standing database checks for the current custody-returning surface."
  (:require [clojure.set]
            [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [sci.core :as sci]
            [sci.impl.utils :as sci.utils]
            [seon.config :as config]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.sci.eval :as eval]
            [seon.test-support :as test-support]))

(def ^:private caps
  (config/result-caps config/defaults))

(def ^:private custody-output-schema-keys
  #{:seon.store/store
    :seon.db/connection
    :seon.sci.eval/ctx})

(def ^:private expected-custody-returning-functions
  ;; Symbols, as the census reads them: `:seon.fn/sym` is a Datahike symbol.
  #{['seon.cluster.store/open-branch! :seon.db/connection]
    ['seon.cluster.store/open-store! :seon.store/store]
    ['seon.sci.eval/build-base-ctx :seon.sci.eval/ctx]
    ['seon.sci.eval/cluster-ctx :seon.sci.eval/ctx]
    ;; The fifth, reviewed 2026-08-08 (P17 S2). `seon.db/supplied-connection`
    ;; is the declared supplier of `:seon.db/connection`: handing out that
    ;; cluster's connection IS its whole contract, and it can only produce
    ;; the one carried by the environment it is called with, so it widens no
    ;; custody. This snapshot caught it exactly as intended — the entity landed
    ;; with S1 and this census was the thing that noticed.
    ['seon.db/supplied-connection :seon.db/connection]
    ;; Source acquisition owns both values while constructing the one base
    ;; later forked by sovereign clusters. Neither function is agent-callable:
    ;; `source-base!` returns only newly constructed source custody, and
    ;; `fork-cluster-ctx` replaces connection custody with its explicit branch.
    ['seon.cluster/source-base! :seon.store/store]
    ['seon.cluster/source-base! :seon.sci.eval/ctx]
    ['seon.sci.eval/fork-cluster-ctx :seon.sci.eval/ctx]
    ;; Export fallback borrows the already-registered branch connection; it
    ;; neither creates custody nor changes its reference count. Returning that
    ;; exact connection is the function's declared purpose.
    ['seon.cluster.registry/active-branch-connection
     :seon.db/connection]
    ;; Ruling 47 grew the program population to every ctx-resolvable name.
    ;; These ordinary public functions are therefore visible to the derived
    ;; custody census and require the same explicit review as prior members.
    ['seon.cluster.boot/connection :seon.db/connection]
    ;; The install gate's disposable copy: a fresh isolated branch of the
    ;; agent's commit (the agent entrance) and the evaluating context forked
    ;; onto it. It returns only the custody it just acquired; the gate
    ;; releases it.
    ['seon.sci.eval/disposable-copy :seon.sci.eval/ctx]
    ['seon.sci.eval/disposable-copy :seon.db/connection]
    ;; Reviewed 2026-09-26. `seon.db/branch-connection` borrows the
    ;; connection Datahike's registry already holds for a branch, as
    ;; `active-branch-connection` does; it opens nothing.
    ['seon.db/branch-connection :seon.db/connection]
    ;; The base-context owners: `derive-base-ctx` builds the interpreter and
    ;; acquires the program, `base-ctx` memoizes it per database value and
    ;; `copy-base-ctx` forks that memoized base. A base is program-only: it
    ;; carries no connection custody until `fork-cluster-ctx` binds one.
    ['seon.sci.eval/base-ctx :seon.sci.eval/ctx]
    ['seon.sci.eval/copy-base-ctx :seon.sci.eval/ctx]
    ['seon.sci.eval/derive-base-ctx :seon.sci.eval/ctx]
    ;; Test-world owners, program entities like any other. The fixture fork is the
    ;; production fork onto the fixture's own connection; `open-world!` returns
    ;; the member branch connection it opened; the agent-custody test's private
    ;; helper returns the connection a handle already holds.
    ['seon.test-support/fork-cluster-ctx :seon.sci.eval/ctx]
    ['seon.test/open-world! :seon.db/connection]
    ['seon.cluster.agent-custody-test/held-connection :seon.db/connection]
    ;; `carry-connection-projection-state!` returns the very connection it was
    ;; handed after attaching the owning projection state (law 2.1). Like
    ;; `seon.db/supplied-connection` it widens no custody: it can only return
    ;; the connection its caller already holds.
    ['seon.db/carry-connection-projection-state! :seon.db/connection]})

(def ^:private custody-returning-query
  '[:find ?function-symbol ?schema-key
    :in $ [?schema-key ...]
    :where
    ;; `:seon.fn.arity/output-refs` holds the schema keys themselves.
    [?arity :seon.fn.arity/output-refs ?schema-key]
    [?function :seon.fn/arities ?arity]
    [?function :seon.fn/sym ?function-symbol]])

(def ^:private namespace-assertions-query
  '[:find ?namespace-name ?source-tx
    :where
    [?namespace :seon.ns/name ?namespace-name]
    [?namespace :seon.ns/source _ ?source-tx]])

(def ^:private relocated-root-vars
  {'seon.cluster/running-instances 'running-instances
   'seon.cluster/root-store-holder 'root-store-holder
   'seon.cluster.store/held-flocks 'held-flocks})

(def ^:private foreign-context-form-generator
  (gen/fmap
   (fn [[suffix value]]
     [(str "(+ " value " 1)")
      (str "(def foreign-value-" suffix " " value ")")
      (str "(defn foreign-function-" suffix " [] " value ")")
      (str "(do (in-ns 'foreign.generated-" suffix ") "
           "(def local-value " value "))")
      (str "(ns-unmap 'user 'foreign-value-" suffix ")")
      "@@#'seon.cluster/running-instances"
      "@@#'seon.cluster/root-store-holder"
      "@@#'seon.cluster.store/held-flocks"
      "(resolve 'seon.operator.runtime/running-instances)"
      "(seon.db/q '[:find (count ?e) . :where [?e :seon.cluster/name]])"])
   (gen/tuple (gen/choose 0 1000000)
              (gen/choose -1000000 1000000))))

(defn- loaded-core-namespaces
  [db]
  (into (sorted-map)
        (keep
         (fn [[namespace-name source-tx]]
           (let [admission
                 (schema/admission-from-asserting-transaction db source-tx)]
             (when (and (= :core (:seon.schema.admission/source admission))
                        (find-ns namespace-name))
               [namespace-name (find-ns namespace-name)]))))
        (db/q namespace-assertions-query db)))

(defn- var-root
  [value]
  (when (or (instance? clojure.lang.Var value)
            (sci.utils/var? value))
    (try
      {:seon.custody-stability/bound? true
       :seon.custody-stability/value @value}
      (catch Throwable _
        {:seon.custody-stability/bound? false}))))

(defn- context-snapshot
  [ctx]
  (let [namespace-state (sci/namespace-state ctx)]
    {:seon.custody-stability/namespace-state namespace-state
     :seon.custody-stability/var-roots
     (into (sorted-map)
           (mapcat
            (fn [[namespace-name namespace-map]]
              (keep
               (fn [[intern-name value]]
                 (when-let [root (var-root value)]
                   [[namespace-name intern-name] root]))
               namespace-map)))
           namespace-state)}))

(defn- evaluate-in
  [ctx source]
  (eval/evaluate
   {:seon.cluster.eval/source source
    :seon.cluster.eval/ns [:seon.ns/name 'user]
    :seon.sci.eval/ctx ctx
    :seon.sci.admit/caps caps
    :seon.sci.eval/time-limit-ms 5000}))

(deftest indexed-custody-returning-surface-is-derived-and-exact
  (test-support/with-database
    (fn [connection]
      (let [db @connection
            contracted-functions
            (db/q '[:find (count ?function) .
                   :where
                   [?function :seon.fn/arities]]
                 db)
            actual
            (db/q custody-returning-query db custody-output-schema-keys)]
        (is (pos? contracted-functions)
            "a missing program graph is failure, never a healthy empty census")
        ;; Exact is deliberate: a maximum would let one of today's custody
        ;; returners disappear silently, while this snapshot makes both a fifth
        ;; function and any removal demand an explicit custody review.
        (is (= expected-custody-returning-functions actual)
            (pr-str {:seon.custody-stability/actual actual}))))))

(deftest acquired-first-party-reachability-is-public-plus-every-indexed-function
  (test-support/with-database
    (fn [connection]
      (let [db @connection
            host-namespaces (loaded-core-namespaces db)
            indexed-function-names
            (reduce
             (fn [by-namespace [function-symbol namespace-name]]
               (update by-namespace namespace-name (fnil conj #{})
                       (symbol (name (symbol function-symbol)))))
             {}
             (db/q '[:find ?function-symbol ?namespace-name
                     :where
                     [?function :seon.fn/sym ?function-symbol]
                     [?function :seon.fn/source _]
                     [?function :seon.fn/private? _]
                     [?function :seon.fn/ns ?namespace]
                     [?namespace :seon.ns/name ?namespace-name]]
                   db))
            declared-names
            (reduce
             (fn [by-namespace [function-symbol namespace-name]]
               (update by-namespace namespace-name (fnil conj #{})
                       (symbol (name (symbol function-symbol)))))
             {}
             (db/q '[:find ?function-symbol ?namespace-name
                     :where
                     [?function :seon.fn/sym ?function-symbol]
                     [?function :seon.fn/ns ?namespace]
                     [?namespace :seon.ns/name ?namespace-name]]
                   db))
            ;; A public Var the program does not declare as a function is not
            ;; SCI's to install: a deftest is a test declaration, run by
            ;; `seon.test/run`, and a compiler-generated proxy Var has no entity.
            expected
            (into (sorted-map)
                  (map (fn [[namespace-name host-namespace]]
                         [namespace-name
                          (into (sorted-set)
                                (concat
                                 (filter (get declared-names namespace-name #{})
                                         (keys (ns-publics host-namespace)))
                                 (get indexed-function-names
                                      namespace-name)))]))
                  host-namespaces)
            installed-all (sci/namespace-interns
                           (test-support/fork-cluster-ctx connection))
            installed
            (select-keys
             installed-all
             (keys host-namespaces))]
        (is (seq host-namespaces)
            "a missing loaded program graph is failure, never a healthy census")
        (is (not (contains? host-namespaces 'seon.operator.runtime))
            "operator custody has no indexed program-graph namespace entity")
        (is (not (contains? installed-all 'seon.operator.runtime))
            "the operator-owned namespace is never installed into SCI")
        (doseq [[consumer-symbol referred-name] relocated-root-vars
                :let [consumer-namespace (symbol (namespace consumer-symbol))
                      root-var (ns-resolve consumer-namespace referred-name)]]
          (is (= 'seon.operator.runtime
                 (some-> root-var meta :ns ns-name))
              (pr-str {:seon.custody-stability/consumer consumer-symbol
                       :seon.custody-stability/root-var root-var})))
        ;; Ruling 47 gives every ctx-resolvable name an entity, including macros
        ;; and injected non-function bindings. Publics cover those bindings;
        ;; the graph side therefore selects source-backed function definitions
        ;; instead of treating every identity entity as an installed function.
        (is (= expected installed)
            (pr-str {:seon.custody-stability/expected expected
                     :seon.custody-stability/installed installed}))))))

(deftest non-function-private-custody-roots-and-operator-state-stay-unreachable
  (test-support/with-database
    (fn [connection]
      (let [ctx (test-support/fork-cluster-ctx connection)
            forbidden
            ['seon.cluster/running-instances
             'seon.cluster/root-store-holder
             'seon.cluster.store/held-flocks]
            installed (sci/namespace-interns ctx)]
        (doseq [qualified forbidden
                :let [namespace-name (symbol (namespace qualified))
                      local-name (symbol (name qualified))]]
          (is (nil? (get-in installed [namespace-name local-name]))
              (pr-str {:seon.custody-stability/forbidden qualified})))
        (is (nil? (:seon.sci.admit/value
                   (evaluate-in
                    ctx
                    "(resolve 'seon.operator.runtime/running-instances)"))))
        (doseq [source ["@@#'seon.cluster/running-instances"
                        "@@#'seon.cluster/root-store-holder"
                        "@@#'seon.cluster.store/held-flocks"]]
          (is (some? (:seon.cluster.eval/error (evaluate-in ctx source)))
              source))))))

(deftest foreign-context-integrity-is-invariant-under-agent-evaluation
  (let [property
        (prop/for-all
         [sources foreign-context-form-generator]
         (test-support/with-database
           (fn [connection-a]
             (test-support/with-database
               (fn [connection-b]
                 (let [ctx-a (test-support/fork-cluster-ctx connection-a)
                       ctx-b (test-support/fork-cluster-ctx connection-b)
                       before (context-snapshot ctx-b)]
                   (every?
                    true?
                    (map (fn [source]
                           (evaluate-in ctx-a source)
                           (= before (context-snapshot ctx-b)))
                         sources))))))))
        check (tc/quick-check 10 property :seed 4303020260802)]
    (is (:result check) (pr-str check))))

(deftest cross-cluster-write-isolation
  (test-support/with-database
    (fn [connection-a]
      (test-support/with-database
        (fn [connection-b]
          ;; A message is its recipient and its content. The recipient is a
          ;; canonical agent on each branch (`agent-tx`); a refused write would
          ;; make every isolation assertion below vacuously true.
          (doseq [connection [connection-a connection-b]]
            (test-support/transacted! connection (test-support/agent-tx @connection "custody-recipient")))
          (let [ctx-a (test-support/fork-cluster-ctx connection-a)
                _ (sci/intern ctx-a 'user 'own-connection connection-a)
                _ (sci/intern ctx-a 'user 'foreign-connection connection-b)
                message-ids
                (fn [connection]
                  (set
                   (db/q
                    '[:find [?id ...]
                      :where [_ :seon.message/id ?id]]
                    @connection)))
                ;; Both fixture branches inherit the member world's messages;
                ;; isolation is about what this evaluation adds.
                before-a (message-ids connection-a)
                before-b (message-ids connection-b)
                message-data
                (fn [message-id]
                  (str "[{:seon.message/id \"" message-id "\" "
                       ":seon.message/to [:seon.agent/id \"custody-recipient\"] "
                       ":seon.message/content \"custody isolation probe\"}]"))
                own
                (evaluate-in
                 ctx-a
                 (str "(seon.db/transact! own-connection "
                      (message-data "custody-own") ")"))
                ambient
                (evaluate-in
                 ctx-a
                 (str "(seon.db/transact! "
                      (message-data "custody-ambient") ")"))
                foreign
                (evaluate-in
                 ctx-a
                 (str "(seon.db/transact! foreign-connection "
                      (message-data "custody-foreign") ")"))]
            (is (nil? (:seon.cluster.eval/error own)))
            (is (nil? (:seon.cluster.eval/error ambient)))
            (is (string? (get-in foreign [:seon.sci.admit/value :seon.db.write.attempt/request-id])))
            (is (= #{"custody-own" "custody-ambient"}
                   (clojure.set/difference (message-ids connection-a) before-a)))
            (is (= before-b (message-ids connection-b)))))))))
