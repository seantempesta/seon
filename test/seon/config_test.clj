(ns seon.config-test
  "Acceptance proofs for the one manifest compiler and config apply."
  (:require [malli.core] [clojure.edn :as edn]
            [datahike.api]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.db :as db]
            [seon.agent :as agent]
            [seon.ai :as ai]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as cluster-agent]
            [seon.config :as config]
            [seon.contracts-compile-test :as contracts-compile]
            [seon.reconcile :as reconcile]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.test-support :as test-support])
  (:import [java.util.concurrent TimeUnit]))

(set! *warn-on-reflection* true)

(schema.edn/load! {})

(def ^:private dial-attributes
  ;; entries only — never positional past :map, so an optional properties
  ;; map can come or go without silently dropping the first entry
  (into #{}
        (comp (filter vector?) (map first))
        (schema/schema-definition :seon.config/manifest)))

(deftest every-agent-dial-has-a-declared-settings-row
  (test-support/with-database
    (fn [connection]
      (test-support/seed-cluster! connection "settings-display")
      (test-support/transacted!
       connection
       (cluster-agent/creation-tx
        {:seon.agent/id "settings-display"
         :seon.ns/name 'my.agents.settings-display
         :seon.cluster/name "settings-display"}))
      (let [database (db/db connection)
            projection (schema/projection-from-database database)
            forms (:seon.schema.projection/forms projection)
            dials (into {}
                        (keep (fn [[attribute definition]]
                                (let [properties (malli.core/properties (seon.schema/structural-schema definition))]
                                  (when (and (:seon.config/dial properties)
                                             (:seon.config/per-agent properties))
                                    [attribute properties]))))
                        forms)
            rendered (schema/call-with-projection
                      projection
                      #(agent/render-settings-html
                        {:seon.db/db database :seon.agent/id "settings-display"}))
            strings (set (filter string? (tree-seq coll? seq rendered)))]
        (is (seq dials))
        (is (= :section (first rendered)))
        (doseq [[attribute properties] dials]
          (let [label (:seon.config/display-label properties)
                declaration (db/pull database
                                     [:seon.schema/key :seon.config/display-label]
                                     [:seon.schema/key attribute])]
            (is (= label (:seon.config/display-label declaration)) (str attribute))
            (is (contains? strings label) (str attribute))))
        (let [attribute (first (sort (keys dials)))
              incomplete (update-in forms [attribute 1] dissoc :seon.config/display-label)
              refusal (try (schema/projection-with-schema
                            projection attribute (get incomplete attribute)
                            {:seon.schema.admission/source :agent})
                           nil
                           (catch clojure.lang.ExceptionInfo failure (ex-data failure)))]
          ;; An authoring rule judges admission; a build of a population
          ;; admitted under an older rule (an as-of view) stays readable.
          (is (some? (schema/build-projection incomplete)))
          (is ((schema/projection-validator projection :seon.schema/validation-refusal)
               refusal))
          (is (= :seon.config/display-label (:seon.schema/expected-value refusal)))
          (is (= attribute (:seon.schema/key refusal))))))))

(deftest optional-setting-flags-assert-only-true
  (test-support/with-database
    (fn [connection]
      (let [flags {:seon.config.agent/show-all-settings true
                   :seon.config.ai/retain-reasoning true}]
        (test-support/apply-config! connection "flag-test" flags)
        (let [database (db/db connection)
              projection (schema/projection-from-database database)
              row (db/pull database (vec (keys flags))
                           [:seon.config/cluster "flag-test"])]
          (is (= flags row))
          (doseq [attribute (keys flags)]
            (let [valid? (schema/projection-validator projection attribute)]
              (is (valid? true))
              (is (not (valid? false)))
              (is (not (valid? nil))))))
        ;; An apply changes only its keys: clearing a flag is an explicit absence.
        (test-support/apply-config! connection "flag-test"
                                    (zipmap (keys flags) (repeat config/absent)))
        (let [row (db/pull (db/db connection)
                           [:seon.config/cluster
                            :seon.config.agent/show-all-settings
                            :seon.config.ai/retain-reasoning]
                           [:seon.config/cluster "flag-test"])]
          (is (= {:seon.config/cluster "flag-test"} row)))))))

(defn- with-default-document
  [document body]
  (let [resource io/resource
        directory (io/file "tmp/config-test-initialization")
        packaged-file (io/file directory "default.edn")]
    (.mkdirs directory)
    (spit packaged-file (str (pr-str document) "\n"))
    (try
      (with-redefs [io/resource
                    (fn [path]
                      (if (= config/default-manifest-path path)
                        (-> packaged-file .toURI .toURL)
                        (resource path)))]
        (body))
      (finally
        (.delete packaged-file)
        (.delete directory)))))

(deftest config-loads-the-packaged-population-in-a-fresh-jvm
  (let [command
        ["clojure" "-M:dev" "-e"
         (str
          "(require 'seon.config 'seon.schema) "
          "(when-not (contains? (seon.schema/registered-schemas) "
          ":seon.ai.model/provider-id) "
          "(throw (ex-info \"packaged schema missing\" {}))) "
          "seon.config/defaults "
          "(println :fresh-config-ready) "
          "(shutdown-agents)")]
        process
        (.start
         (doto
          (ProcessBuilder. ^java.util.List command)
          (.directory (io/file (System/getProperty "user.dir")))
          (.redirectErrorStream true)))
        output (future (slurp (.getInputStream process)))
        exited? (.waitFor process 30 TimeUnit/SECONDS)]
    (when-not exited?
      (.destroyForcibly process)
      (.waitFor process test-support/event-backstop-seconds TimeUnit/SECONDS))
    (is exited? (str "the fresh config JVM exceeded its external-process backstop: "
                     (deref output (* 1000 test-support/event-backstop-seconds)
                            "child output did not close")))
    (when exited?
      (is (zero? (.exitValue process)) @output)
      (is (str/includes? @output ":fresh-config-ready") @output))))

(deftest the-default-document-has-one-canonical-complete-location
  (is (.equals "config/default.edn" config/default-manifest-path))
  (is (.isFile (io/file config/default-manifest-path)))
  (is (= dial-attributes
         (set
          (remove
           #{config/initialization-key}
           (keys
            (edn/read-string (slurp config/default-manifest-path))))))
      "the shipped EDN itself, not a second registry, covers every production attribute")
  (is (= dial-attributes
         (set (keys (config/default-decisions))))
      "the shipped document makes one decision for every registered config attribute"))

(deftest shipped-initialization-admission-is-registry-derived
  (let [base (edn/read-string (slurp config/default-manifest-path))
        row {:seon.db.process/id "config-population-admission-test"}]
    (testing "a declared row with exactly one declared identity is admitted"
      (with-default-document
        (assoc base config/initialization-key [row])
        #(is (= [row] (config/default-population)))))
    (testing "the reserved entry is not a sparse overlay dial"
      (let [data
            (test-support/refusal-data
             #(config/compile-manifest
               {:seon.boot/cluster-name "default" :seon.config/manifest
                {config/initialization-key [row]}}))]
        (is (= ::config/initialization-not-allowed (::config/rule data)))))
    (doseq [[label population expected-rule expected-key]
            [["the population must be a vector"
              row
              ::config/invalid-initialization
              nil]
             ["every member must be a map"
              ["not-a-row"]
              ::config/invalid-initialization-row
              nil]
             ["keys must be qualified keywords"
              [{:id "x"}]
              ::config/invalid-initialization-attribute
              :id]
             ["unknown attributes are refused"
              [{:seon.unknown/id "x"}]
              ::config/unknown-initialization-attribute
              :seon.unknown/id]
             ["declared values are rigorously validated"
              [{:seon.db.process/id ""}]
              ::config/invalid-initialization-value
              :seon.db.process/id]
             ["a row must have an identity attribute"
              [{:seon.config.eval/time-limit-ms 1234}]
              ::config/invalid-initialization-identity
              nil]
             ["a row cannot have two identity attributes"
              [{:seon.db.process/id "x"
                :seon.config/cluster "x"}]
              ::config/invalid-initialization-identity
              nil]]]
      (testing label
        (let [data
              (with-default-document
                (assoc base config/initialization-key population)
                #(test-support/refusal-data config/default-population))]
          (is (= expected-rule (::config/rule data)))
          (when expected-key
            (is (= expected-key (::config/key data)))))))))

(deftest initialization-rows-apply-query-and-converge-with-the-config-row
  (let [base (edn/read-string (slurp config/default-manifest-path))
        process-id "seon.db.process/config-population-test"
        document
        (assoc base config/initialization-key
               [{:seon.db.process/id process-id}])]
    (with-default-document
      document
      #(test-support/with-database
         (fn [connection]
           (let [first-result
                 (config/apply! {:seon.boot/cluster-name "default" :seon.db/connection connection})
                 committed-basis (:max-tx @connection)
                 second-result
                 (config/apply! {:seon.boot/cluster-name "default" :seon.db/connection connection})]
             (is (false? (:seon.reconcile/converged? first-result)))
             (is (= process-id
                    (db/q
                     '[:find ?id .
                       :in $ ?id
                       :where [_ :seon.db.process/id ?id]]
                     @connection
                     process-id)))
             (is (true? (:seon.reconcile/converged? second-result)))
             (is (zero? (:seon.reconcile/operations second-result)))
             (is (= committed-basis (:max-tx @connection))
                 "an identical config and population write no transaction")))))))

(deftest config-writer-submits-only-the-fact-difference
  (test-support/with-database
   (fn [connection]
     (let [request {:seon.db/connection connection :seon.boot/cluster-name "difference"}
           _ (config/apply! request)
           original @#'config/reconcile-call
           transactions (atom [])
           changed (assoc request :seon.config/manifest
                          {:seon.config.flow.compute/queue-depth 23})]
       (with-redefs-fn
         {#'config/reconcile-call
          (fn [database desired request]
            (let [tx-data (original database desired request)]
              (swap! transactions conj tx-data)
              tx-data))}
         #(do
            (is (false? (:seon.reconcile/converged? (config/apply! changed))))
            (let [basis (:max-tx @connection)]
              (is (true? (:seon.reconcile/converged? (config/apply! changed))))
              (is (= basis (:max-tx @connection))))))
       (is (= 1 (count @transactions)))
       (let [tx-data (first @transactions)
             rows (filter map? tx-data)]
         (is (= [[:db.fn/retractAttribute [:seon.config/cluster "difference"]
                   :seon.config.flow.compute/queue-depth]]
                (filterv vector? tx-data)))
         (is (= 1 (count rows)))
         (is (= #{:db/id :seon.config/cluster :seon.config.flow.compute/queue-depth
                  :seon.config/overlay-keys}
                (set (keys (first rows)))))
         (is (= #{:seon.config.flow.compute/queue-depth}
                (:seon.config/overlay-keys (first rows)))
             "the row records the one dial this apply decided")
         (is (= 23 (:seon.config.flow.compute/queue-depth (first rows)))))))))

(deftest converged-apply-uses-carried-projection-and-remains-exact
  (test-support/with-database
   (fn [connection]
     (let [request {:seon.db/connection connection
                    :seon.boot/cluster-name "default"}
           first-result (config/apply! request)
           basis (:max-tx @connection)
           rebuild schema/projection-from-database
           fallback db/projection-fallback
           plan reconcile/plan
           caller (Thread/currentThread)
           rebuilds (atom 0)
           fallbacks (atom 0)
           planned-operations (atom [])]
       (is (false? (:seon.reconcile/converged? first-result)))
       (is (some? (db/carried-projection (db/db connection))))
       (with-redefs [schema/projection-from-database
                     (fn [& arguments]
                       (when (identical? caller (Thread/currentThread))
                         (swap! rebuilds inc))
                       (apply rebuild arguments))
                     db/projection-fallback
                     (fn [operation]
                       (when (identical? caller (Thread/currentThread))
                         (swap! fallbacks inc))
                       (fallback operation))
                     reconcile/plan
                     (fn [database request]
                       (let [operations (plan database request)]
                         (when (identical? caller (Thread/currentThread))
                           (swap! planned-operations conj (count operations)))
                         operations))]
         (is (= {:seon.reconcile/converged? true
                 :seon.reconcile/operations 0}
                (config/apply! request))))
       (is (zero? @rebuilds))
       (is (zero? @fallbacks))
       (is (= [0] @planned-operations)
           "a real exact read observes convergence without rebuilding")
       (is (= basis (:max-tx @connection)))
       (let [compiled (config/compile-manifest {:seon.boot/cluster-name "default"})
             queue-depth (get-in compiled [:seon.config/effective
                                          :seon.config.flow.compute/queue-depth])]
         (let [edited (db/transact!
                       connection
                       [{:db/id [:seon.config/cluster "default"]
                         :seon.config.flow.compute/queue-depth (inc queue-depth)}])]
           (is (some? (:db-after edited)) (pr-str edited))
           (is (= (inc queue-depth)
                  (:seon.config.flow.compute/queue-depth
                   (db/pull (db/db connection)
                            [:seon.config.flow.compute/queue-depth]
                            [:seon.config/cluster "default"])))
               "the hand edit committed before reconciliation"))
         (is (false? (:seon.reconcile/converged? (config/apply! request)))
             "the same manifest repairs a hand edit")
         (is (= queue-depth (:seon.config.flow.compute/queue-depth
                             (config/effective @connection "default"))))
         (let [process-identity [:seon.db.process/id "config-apply-cost-initialization"]
               changed (update compiled :seon.config/initialization conj
                               {(first process-identity) (second process-identity)})]
           (is (false? (:seon.reconcile/converged?
                        (config/apply-compiled! connection changed)))
               "initialization changes even when the dials stay equal")
           (is (= (second process-identity)
                  (:seon.db.process/id
                   (db/pull @connection [:seon.db.process/id] process-identity))))))))))

(deftest packaged-defaults-use-the-same-shipped-document-authority
  (let [resource io/resource
        directory (io/file "tmp/config-test-packaged")
        packaged-file (io/file directory "default.edn")
        repository-decisions (edn/read-string
                              (slurp config/default-manifest-path))
        packaged-decisions
        (assoc repository-decisions
               :seon.config.flow.compute/queue-depth 19)]
    (.mkdirs directory)
    (spit packaged-file (str (pr-str packaged-decisions) "\n"))
    (try
      (with-redefs [io/resource
                    (fn [path]
                      (if (= config/default-manifest-path path)
                        (-> packaged-file .toURI .toURL)
                        (resource path)))]
        (is (= 19
               (:seon.config.flow.compute/queue-depth
                (config/default-decisions)))
            "a packaged resource wins without copying the decision roster"))
      (finally
        (.delete packaged-file)
        (.delete directory)))))

(deftest no-provider-is-a-development-overlay-decision-never-a-shipped-default
  ;; Test worlds compile the shipped document, so they reach provider stand-ins;
  ;; the development cluster boots under config/development.edn and runs no-provider.
  (is (nil? (find (:seon.config/effective
                   (config/compile-manifest {:seon.boot/cluster-name "default"}))
                  :seon.config.ai/no-provider)))
  (is (true? (:seon.config.ai/no-provider
              (:seon.config/effective
               (config/compile-manifest {:seon.boot/cluster-name "default"
                                         :seon.config/manifest (config/development-manifest)}))))))

(deftest a-sparse-apply-changes-only-its-keys-and-keeps-no-provider
  ;; Class regression (issue a-sparse-config-apply-drops-the-no-provider-overlay-
  ;; and-calls-a-paid-provider): a one-key apply once recompiled from the shipped
  ;; defaults alone, so a fixture's provider stand-in fell back to the shipped
  ;; paid endpoint and credential. Resolution only; no provider call is made.
  (test-support/with-database
   (fn [connection]
     (let [request {:seon.db/connection connection
                    :seon.boot/cluster-name "sparse-apply"}
           stand-in "http://127.0.0.1:1/v1"
           shipped (config/compile-manifest {:seon.boot/cluster-name "sparse-apply"})
           _ (config/apply! (assoc request :seon.config/manifest
                                   {:seon.config.ai/no-provider true
                                    :seon.config.ai/endpoint stand-in
                                    :seon.config.ai.backup/model config/absent}))
           sparse (config/apply! (assoc request :seon.config/manifest
                                        {:seon.config.run/max-episode-runs 1}))
           effective (config/effective @connection "sparse-apply")
           primary (:seon.ai/primary (ai/targets (or (db/carried-projection @connection)
                                                        (schema/handed-projection))
                                                    effective))]
       (is (boolean? (:seon.reconcile/converged? sparse)) (pr-str sparse))
       (is (= 1 (:seon.config.run/max-episode-runs effective)))
       (is (true? (:seon.config.ai/no-provider effective))
           "the sparse apply keeps the no-provider decision beneath it")
       (is (= stand-in (:seon.config.ai/endpoint effective)))
       (is (nil? (find effective :seon.config.ai.backup/model))
           "a held absence stays absent")
       (is (true? (:seon.ai/no-provider primary))
           "the resolved provider target is the no-provider one")
       (is (not= (get-in shipped [:seon.config/effective :seon.config.ai/endpoint])
                 (:seon.ai/endpoint primary))
           "the shipped paid endpoint is never resolved")
       (is (= #{:seon.config.ai/no-provider :seon.config.ai/endpoint
                :seon.config.ai.backup/model :seon.config.run/max-episode-runs}
              (set (:seon.config/overlay-keys
                    (db/pull @connection [:seon.config/overlay-keys]
                             [:seon.config/cluster "sparse-apply"])))))
       (config/apply! request config/default-manifest-path)
       (let [reset (config/effective @connection "sparse-apply")]
         (is (= (select-keys (:seon.config/effective shipped)
                             [:seon.config.ai/endpoint :seon.config.run/max-episode-runs])
                (select-keys reset [:seon.config.ai/endpoint :seon.config.run/max-episode-runs]))
             "applying the shipped document replaces every held decision")
         (is (nil? (find reset :seon.config.ai/no-provider))))))))

(deftest an-apply-on-a-branch-lacking-the-overlay-attribute-installs-it-first
  ;; Class regression (revert 81f9b1b3e): boot wrote a row naming
  ;; :seon.config/overlay-keys onto a branch whose program had not adopted its
  ;; declaration, and Datahike's :write schema mode refused the whole boot.
  (test-support/with-database
   (fn [connection]
     (let [request {:seon.db/connection connection :seon.boot/cluster-name "unadopted"}
           removed (test-support/transacted!
                    connection
                    (cluster/attribute-change-tx @connection [:seon.config/overlay-keys] {}))
           _ (is (nil? (get-in @connection [:schema :seon.config/overlay-keys])) (pr-str removed))
           applied (db/call-with-custody
                    {:seon.db/connection connection}
                    #(config/apply! (assoc request :seon.config/manifest
                                           {:seon.config.ai/no-provider true})))
           sparse (db/call-with-custody
                   {:seon.db/connection connection}
                   #(config/apply! (assoc request :seon.config/manifest
                                          {:seon.config.run/max-episode-runs 3})))
           effective (config/effective @connection "unadopted")]
       (is (false? (:seon.reconcile/converged? applied)) (pr-str applied))
       (is (boolean? (:seon.reconcile/converged? sparse)) (pr-str sparse))
       (is (= :db.cardinality/many
              (get-in @connection [:schema :seon.config/overlay-keys :db/cardinality])))
       (is (true? (:seon.config.ai/no-provider effective)))
       (is (= 3 (:seon.config.run/max-episode-runs effective)))))))

(deftest a-dial-the-checkout-no-longer-declares-is-left-for-adoption
  ;; Class regression (fix-dial-retirement-adoption 2026-09-26): boot applies
  ;; the checkout's compiled config to a branch that has not adopted the
  ;; checkout's declarations. Deleting `:seon.config/on-core-error` made that
  ;; apply retract a value the branch's own declarations still required, and
  ;; `start --head` refused. Retiring an attribute is adoption's (it retracts
  ;; the attribute with its data); config leaves what it does not declare.
  (test-support/with-database
   (fn [connection]
     (let [dial :seon.config-test/retired-dial
           cluster [:seon.config/cluster "default"]]
       (test-support/transacted! connection [{:db/ident dial
                                              :db/valueType :db.type/keyword
                                              :db/cardinality :db.cardinality/one}])
       (test-support/transacted! connection [{:db/id cluster dial :panic}])
       (let [result (config/apply-compiled!
                     connection (config/compile-manifest {:seon.boot/cluster-name "default"}))]
         (is (nil? (:seon.error/at result)) (pr-str result))
         (is (= :panic (get (db/pull (db/db connection) [dial] cluster) dial))
             "the undeclared dial keeps its value until adoption retires it"))))))

(deftest apply-surfaces-a-refused-reconcile-as-its-flat-rule-error
  ;; A real refusal inside the reconcile transaction: the desired row names a
  ;; function with no program row. The writer returns that flat error verbatim.
  (test-support/with-database
    (fn [connection]
      (let [dial :seon.config.web/search-result-projection
            missing 'seon.config-test/no-such-projection
            compiled (assoc-in (config/compile-manifest {:seon.boot/cluster-name "default"})
                               [:seon.config/desired-row dial] missing)
            result (test-support/refusal-data
                    #(config/apply-compiled! connection compiled))]
        (is (= ::config/missing-function (::config/rule result)))
        (is (= dial (:seon.config/error-key result)))
        (is (= [[dial missing]] (:seon.error/offending result)))
        (is (not= missing (get (config/effective (db/db connection) "default") dial))
            "the refused reconcile committed nothing")))))

(deftest cluster-scoped-config-refuses-an-omitted-cluster
  ;; compile and apply name no cluster to default from, so an omitted cluster
  ;; refuses at entry. `effective` declares `:seon.boot/cluster-name`, which the
  ;; running evaluation's environment supplies by schema name (call-time
  ;; defaults): the omitted read answers that cluster, exactly as naming it.
  (test-support/with-database
   (fn [connection]
     (let [basis (:max-tx @connection)]
       (doseq [[caller invoke]
               [['seon.config/compile-manifest #(config/compile-manifest {})]
                ['seon.config/apply! #(config/apply! {:seon.db/connection connection})]]]
         (let [refusal (test-support/refusal-data invoke)]
           (is (= :input (:seon.instrument/check refusal)))
           (is (= caller (:seon.instrument/fn refusal)))))
       (is (= basis (:max-tx @connection)))))))

(deftest a-seeded-cluster-reads-its-own-row-on-the-member-branch
  ;; Class regression (shrink-turn 2026-09-26): tests that seed their own
  ;; cluster read "requires a matching cluster row", because the reader
  ;; required a dial the world's rows predated. The seeded row is read on the
  ;; member branch it was written to.
  (test-support/with-database
   (fn [connection]
     (config/apply! {:seon.db/connection connection :seon.boot/cluster-name "seeded-proof"
                     :seon.config/manifest {:seon.config.eval/time-limit-ms 4321}})
     (let [effective (config/effective (db/db connection) "seeded-proof")]
       (is (nil? (:seon.error/at effective)) (pr-str effective))
       (is (= 4321 (:seon.config.eval/time-limit-ms effective)))))))

(deftest a-row-written-before-a-dial-existed-reads-its-declared-default
  ;; Accretion (Astra F5, 2026-09-26): a retained branch's row, written by a
  ;; program older than a declared-default dial, lacks it. The reader requires
  ;; nothing of it: the dial answers its declared default by schema name, and
  ;; every dial the row holds reads as held.
  (test-support/with-database
   (fn [connection]
     (config/apply! {:seon.db/connection connection :seon.boot/cluster-name "older-row"
                     :seon.config/manifest {:seon.config.eval/time-limit-ms 4321}})
     (let [dial :seon.config.cache/max-bytes
           declared (get (schema.edn/packaged-forms) dial)
           older (:db-after (datahike.api/with
                             (db/db connection)
                             [[:db/retract [:seon.config/cluster "older-row"] dial
                               (get (db/pull (db/db connection) [dial] [:seon.config/cluster "older-row"]) dial)]]))
           effective (config/effective older "older-row")]
       (is (nil? (get (db/pull older [dial] [:seon.config/cluster "older-row"]) dial))
           "the row genuinely lacks the dial")
       (is (nil? (:seon.error/at effective)) (pr-str effective))
       (is (= (:seon.config/default (malli.core/properties (schema/structural-schema declared)))
              (get effective dial)))
       (is (= 4321 (:seon.config.eval/time-limit-ms effective)))))))

(deftest zero-overlay-compilation-resolves-every-registered-config-attribute
  (let [compiled (config/compile-manifest {:seon.boot/cluster-name "default"})
        effective (:seon.config/effective compiled)
        row (:seon.config/desired-row compiled)]
    (is (= dial-attributes (:seon.config/resolved-attributes compiled))
        "this is the standing zero-overlay completeness proof")
    (is (= effective (select-keys row dial-attributes)))
    (is (= "default" (:seon.config/cluster row))
        "the caller names the cluster explicitly")
    (is ((schema/projection-validator (schema/handed-projection) :seon.config/effective) effective))
    (is ((schema/projection-validator (schema/handed-projection) :seon.config/entity) row))
    (is (= (long (.availableProcessors (Runtime/getRuntime)))
           (:seon.config.flow.compute/concurrency effective)))
    (is (true? (:seon.config.db/keep-history? effective))
        "ordinary operator roots retain history by shipped decision")
    (is (not (contains? effective :seon.config.web/port))
        "an explicit absence decision resolves without storing nil")
    (is (= (:seon.config.ai.backup/model (config/default-decisions))
           (:seon.config.ai.backup/model effective))
        "the explicitly shipped backup decision resolves without a mirror")
    (is (= :disabled (:seon.config.ai/thinking effective))
        "the shipped Flash posture explicitly disables thinking")
    (is (not (contains? effective :seon.config.ai/temperature))
        "optional request dials resolve absence without storing markers")))

(deftest one-compiler-applies-default-overlay-environment-precedence
  (let [compiled
        (config/compile-manifest
         {:seon.config/manifest
          {:seon.config.flow.compute/queue-depth 11
           :seon.config.db/keep-history? false
           :seon.config.eval/time-limit-ms 1234}
          :seon.config/environment
          {:seon.config.flow.compute/queue-depth 12}
          :seon.boot/cluster-name "alpha"})
        effective (:seon.config/effective compiled)]
    (is (= 12 (:seon.config.flow.compute/queue-depth effective))
        "explicit environment wins over the selected sparse overlay")
    (is (= 1234 (:seon.config.eval/time-limit-ms effective))
        "the sparse overlay wins over shipped defaults")
    (is (false? (:seon.config.db/keep-history? effective))
        "an isolated root may select the non-temporal representation")
    (is (= 65536 (:seon.config.eval.result/max-nodes effective))
        "an unmentioned entry inherits its shipped decision")
    (is (= "alpha"
           (:seon.config/cluster (:seon.config/desired-row compiled))))))

(deftest explicit-absence-is-a-decision-never-a-stored-value
  (is ((schema/projection-validator (schema/handed-projection) :seon.config/manifest) {:seon.config.run/max-episode-runs config/absent})
      "the derived manifest schema admits explicit absence for an optional dial")
  (let [baseline (config/compile-manifest {:seon.boot/cluster-name "default"})
        omitted (config/compile-manifest {:seon.boot/cluster-name "default" :seon.config/manifest {}})
        absent
        (config/compile-manifest
         {:seon.boot/cluster-name "default" :seon.config/manifest
          {:seon.config.run/max-episode-runs config/absent}})
        effective (:seon.config/effective absent)
        row (:seon.config/desired-row absent)]
    (is (= 100
           (:seon.config.run/max-episode-runs
            (:seon.config/effective baseline))
           (:seon.config.run/max-episode-runs
            (:seon.config/effective omitted)))
        "omission inherits the shipped default; it never means retraction")
    (is (not (contains? effective :seon.config.run/max-episode-runs)))
    (is (not (contains? row :seon.config.run/max-episode-runs)))
    (is (not-any? nil? (vals row)))
    (is (not= (:seon.config/effective baseline)
              (:seon.config/effective absent)))
    (testing "a required entry cannot be removed"
      (let [data
            (test-support/refusal-data
             #(config/compile-manifest
               {:seon.boot/cluster-name "default" :seon.config/manifest
                {:seon.config.flow.compute/queue-depth config/absent}}))]
        (is (= ::config/required-absent (::config/rule data)))
        (is (= :seon.config.flow.compute/queue-depth
               (::config/key data)))))))

(deftest effective-config-is-independent-of-map-construction-order
  (let [left
        (config/compile-manifest
         {:seon.boot/cluster-name "default" :seon.config/manifest
          (array-map
           :seon.config.eval/time-limit-ms 1234
           :seon.config.flow.compute/queue-depth 22)})
        right
        (config/compile-manifest
         {:seon.config/manifest
          (array-map
           :seon.config.flow.compute/queue-depth 22
           :seon.config.eval/time-limit-ms 1234)
          :seon.boot/cluster-name "other"})]
    (is (= (:seon.config/effective left)
           (:seon.config/effective right)))
    (is (= (dissoc (:seon.config/desired-row left) :seon.config/cluster)
           (dissoc (:seon.config/desired-row right) :seon.config/cluster)))))

(deftest sparse-file-reading-does-not-compile-a-second-time
  (let [directory (io/file "tmp/config-test")
        path (io/file directory "override.edn")]
    (.mkdirs directory)
    (spit path "{:seon.config.eval/time-limit-ms 1234}\n")
    (try
      (is (= {:seon.config.eval/time-limit-ms 1234}
             (config/read-manifest (str path)))
          "selection reads a sparse overlay; compile owns all merging")
      (test-support/with-database
       (fn [connection]
         (let [acquire schema/declaration-projection
               acquisitions (atom 0)
               result (with-redefs [schema/declaration-projection
                                    (fn [forms]
                                      (swap! acquisitions inc)
                                      (acquire forms))]
                        (config/apply! {:seon.boot/cluster-name "default"
                                        :seon.db/connection connection}
                                       (str path)))]
           (is (<= @acquisitions 1)
               "file apply acquires at most one declaration generation (none when the process cache holds it)")
           (is (false? (:seon.reconcile/converged? result)))
           (is (= 1234 (:seon.config.eval/time-limit-ms
                           (config/effective (db/db connection) "default")))))))
      (finally
        (.delete path)
        (.delete directory)))))

(deftest compiler-ignores-extra-keys-and-refuses-invalid-declared-values
  (testing "unknown means no registered config attribute schema"
    (let [compiled
          (config/compile-manifest
           {:seon.boot/cluster-name "default" :seon.config/manifest
            {:seon.config.old/transport-timeout-ms 60000}})]
      (is (not (contains? (:seon.config/effective compiled)
                          :seon.config.old/transport-timeout-ms))
          "an extra key is ignored until a declaration gives it meaning")))
  (testing "a registered attribute with the wrong value carries Malli's explanation"
    (let [data
          (test-support/refusal-data
           #(config/compile-manifest
             {:seon.boot/cluster-name "default" :seon.config/environment
              {:seon.config.flow.compute/queue-depth 0}}))]
      (is (= :input (:seon.instrument/check data)))
      (is (= 'seon.config/compile-manifest
             (:seon.instrument/fn data)))
      (is (= #{[:seon.config/environment
                :seon.config.flow.compute/queue-depth]}
             (set (:seon.instrument/problem-paths data)))))))

(deftest unreadable-manifest-refuses-by-name
  (let [data
        (test-support/refusal-data
         #(config/read-manifest
           "tmp/config-test/this-manifest-does-not-exist.edn"))]
    (is (= ::config/manifest-unreadable (::config/rule data)))))

(deftest apply-compiles-once-and-round-trips-through-database-facts
  (test-support/with-database
    (fn [connection]
      (let [result
            (config/apply!
             {:seon.boot/cluster-name "default" :seon.db/connection connection
              :seon.config/manifest
              {:seon.config.eval/time-limit-ms 1234}})
            committed-basis (:max-tx @connection)
            converged
            (config/apply!
             {:seon.boot/cluster-name "default" :seon.db/connection connection
              :seon.config/manifest
              {:seon.config.eval/time-limit-ms 1234}})]
        (is (false? (:seon.reconcile/converged? result)))
        (is (true? (:seon.reconcile/converged? converged)))
        (is (zero? (:seon.reconcile/operations converged)))
        (is (= committed-basis (:max-tx @connection))
            "a converged apply writes no transaction")
        (is (= 1234
               (:seon.config.eval/time-limit-ms
                (config/effective @connection "default"))))
        (is (= config/managing-process-identity
               (db/q
                '[:find ?process-id .
                  :where
                  [?entity :seon.config/cluster "default"]
                  [?entity :seon.config.eval/time-limit-ms _ ?tx]
                  [?tx :seon.db/process ?process]
                  [?process :seon.db.process/id ?process-id]]
                @connection)))))))

(deftest apply-replaces-an-inherited-config-identity-regardless-of-provenance
  (test-support/with-database
    (fn [connection]
      (let [ancestor
            (:seon.config/desired-row
             (config/compile-manifest
              {:seon.boot/cluster-name "ancestor"}))]
        (test-support/transacted! connection [ancestor])
        (config/apply!
         {:seon.db/connection connection
          :seon.config/manifest
          {:seon.config.flow.compute/queue-depth 17}
          :seon.boot/cluster-name "fork"})
        (is (= ["fork"]
               (filterv #{"ancestor" "fork"}
                (db/q '[:find [?cluster-name ...]
                        :where
                        [_ :seon.config/cluster ?cluster-name]]
                      @connection))))
        (is (= 17
               (:seon.config.flow.compute/queue-depth
                (config/effective @connection "fork"))))))))

(deftest a-config-row-cannot-be-left-without-a-required-result-cap
  ;; This assertion moved. It used to retract a cap key, then prove
  ;; `result-caps` refused the stale row it produced. Write admission reads an
  ;; identity-keyed map against the WHOLE config schema, so that row can no
  ;; longer be constructed at all: the retraction itself is refused, naming
  ;; the key, and nothing lands. `result-caps` remains proven against the
  ;; refusal shapes that DO reach it — a `missing-effective-error` in
  ;; `two-clusters-on-one-jvm-have-no-config-bleed`, and a cluster-less
  ;; refusal below — because `:seon.config/effective`, its other declared
  ;; input, cannot itself be missing a cap key.
  (test-support/with-database
    (fn [connection]
      (config/apply! {:seon.boot/cluster-name "default" :seon.db/connection connection})
      (let [basis (:max-tx @connection)
            refused (db/transact!
                     connection
                     [[:db/retract
                       [:seon.config/cluster "default"]
                       :seon.config.eval.result/max-nodes]])]
        (is (string? (:seon.db.write.attempt/request-id refused)))
        (is (str/includes? (:seon.error/message refused)
                           ":seon.config.eval.result/max-nodes"))
        (is (= basis (:max-tx @connection))
            "and nothing landed")))))

(deftest caps-refused-for-want-of-a-cluster-carry-that-refusal-as-the-cause
  ;; The caps refusal identifies its missing constraint and carries the
  ;; complete configuration refusal that prevented acquisition.
  (let [refusal {:seon.error/at (java.util.Date.)
                 :seon.error/layer :seon.config/read
                 :seon.error/operation 'seon.config/effective
                 :seon.config/error-key :seon.boot/cluster-name
                 :seon.error/expected-key :seon.boot/cluster-name
                 :seon.error/message "this database names no cluster."}
        result (config/result-caps refusal)]
    (is (inst? (:seon.error/at result)))
    (is (= {:seon.error/at (:seon.error/at result)
            :seon.error/layer :seon.config/read
            :seon.error/operation 'seon.config/result-caps
            :seon.config/error-key :seon.config.eval.result/max-bytes
            :seon.error/expected-key :seon.config.eval.result/max-bytes
            :seon.error/message "Value-admission caps require every declared configuration bound."
            :seon.error/offending refusal
            :seon.config/key :seon.config.eval.result/max-bytes}
           result))))

(deftest two-clusters-on-one-jvm-have-no-config-bleed
  (test-support/with-database
    (fn [alpha]
      (test-support/with-database
        (fn [beta]
          ;; The outer world is written under its own custody, not the inner one's.
          (test-support/apply-config! alpha "alpha" {:seon.config.flow.compute/queue-depth 11})
          (test-support/apply-config! beta "beta" {:seon.config.flow.compute/queue-depth 22})
          (is (= 11
                 (:seon.config.flow.compute/queue-depth
                  (config/effective @alpha "alpha"))))
          (is (= 22
                 (:seon.config.flow.compute/queue-depth
                  (config/effective @beta "beta"))))
          (let [missing-beta (config/effective @alpha "beta")
                missing-alpha (config/effective @beta "alpha")]
            (is (= "beta" (:seon.config/missing-effective missing-beta)))
            (is (= "alpha" (:seon.config/missing-effective missing-alpha)))
            (doseq [refusal [missing-beta missing-alpha]
                    :let [caps-refusal (config/result-caps refusal)]]
              (is (= :seon.config/effective (:seon.error/expected-key refusal)))
              (is (= {:seon.error/at (:seon.error/at caps-refusal)
                      :seon.error/layer :seon.config/read
                      :seon.error/operation 'seon.config/result-caps
                      :seon.config/error-key :seon.config.eval.result/max-bytes
                      :seon.error/expected-key :seon.config.eval.result/max-bytes
                      :seon.error/message "Value-admission caps require every declared configuration bound."
                      :seon.error/offending refusal
                      :seon.config/key :seon.config.eval.result/max-bytes
                      :seon.config/missing-effective (:seon.config/missing-effective refusal)}
                     caps-refusal))
              (is (string? (:seon.error/message refusal))))))))))

(deftest a-refused-read-is-returned-as-the-cause-not-reported-as-missing-facts
  ;; The class: a reader that treats an error value as an ordinary/absent row.
  ;; `effective-in` bound `seon.db/pull`'s answer as the config row and derived
  ;; `missing` from its keys, so an hour of `:seon.db/invalid-read` refusals
  ;; reported every dial missing and named the FACTS instead of the READ.
  ;; `seon.db/pull` returns its database argument unchanged when that argument
  ;; is itself an error value (`pull-call`), so a flat refusal handed as the
  ;; database is the honest construction of a refused read.
  (test-support/with-database
    (fn [connection]
      (config/apply! {:seon.boot/cluster-name "default" :seon.db/connection connection})
      (let [projection (or (db/carried-projection (db/db connection))
                           (schema/projection-from-database (db/db connection)))
            refusal (db/projection-fallback 'seon.config/effective)
            result (#'config/effective-in refusal "default" projection)]
        (is (identical? refusal result))
        (is (= :seon.schema/projection (:seon.schema/expected-value result)))
        (is (not (contains? result :seon.config/missing-effective)))))))


(defn checkout-effective
  "The shipped effective configuration the checkout at `root` compiles to, or
  the refusal `seon.config/defaults` would throw at namespace load.

  Reads the checkout's schema resources and `config/default.edn` from disk
  (a JVM running a published archive has that archive's copies on its
  classpath) and compiles them through the one compiler
  `seon.config/defaults` uses. Run it over the working tree before committing
  a dial, a `:seon.config/optional` change or a shipped decision."
  {:malli/schema [:=> [:cat :string] :map]}
  [root]
  (let [projection (schema/declaration-projection
                    (contracts-compile/checkout-packaged-forms root))
        document (edn/read-string (slurp (io/file root config/default-manifest-path)))]
    (try (:seon.config/effective (@#'config/compile-settings projection document {}))
         (catch clojure.lang.ExceptionInfo refusal (ex-data refusal)))))

(deftest the-shipped-decisions-satisfy-every-required-dial
  ;; CLASS: a dial whose declaration stopped being `:seon.config/optional`
  ;; while config/default.edn still decided it `:seon.config/absent` made
  ;; `seon.config/defaults` refuse at namespace load ("Configuration requires a
  ;; value for each required configuration key", :seon.config.maintenance/collect?,
  ;; HEAD 2e0d7c35f), so no namespace requiring seon.config compiled and default
  ;; could not boot. The shipped document and the declarations compile together.
  (let [effective (checkout-effective ".")]
    (is (not (:seon.error/message effective)) (pr-str (select-keys effective [:seon.config/error-key :seon.config/rule])))
    (is (true? (:seon.config.maintenance/collect? effective))
        "whole-store collection ships on")))

(deftest a-projection-answers-its-dial-membership-once
  ;; Every config read on a fresh database value asked the whole registry
  ;; again (perf-eval-overhead 2026-09-26); the projection's holder answers.
  (let [projection (schema/handed-projection)
        dials (config/dial-attributes projection)]
    (is (contains? dials :seon.config.agent/turn-completion-backstop-ms))
    (is (identical? dials (config/dial-attributes projection)))))
