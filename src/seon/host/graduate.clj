(ns seon.host.graduate
  "Derive trusted JVM function bindings from recorded corpus facts."
  (:require [clojure.edn :as edn]
            [malli.core :as m]
            [sci.ctx-store]
            [seon.content-hash :as content-hash]
            [seon.host.context :as context]
            [seon.host.record :as record]
            [seon.schema :as schema])
  (:import [sci.lang Var]))

(set! *warn-on-reflection* true)

(schema/register! ::ok? :boolean)
(schema/register! ::error [:string {:min 1}])
(schema/register! ::result-edn :string)
(schema/register! ::tier [:enum :nursery :graduated])
(schema/register! ::schema-valid? :boolean)
(schema/register! ::test-covered? :boolean)
(schema/register!
 ::test-outcome
 [:map {:closed true}
  [::ok? ::ok?]
  [::result-edn {:optional true} ::result-edn]
  [::error {:optional true} ::error]])
(schema/register!
 ::gate-facts
 [:map {:closed true}
  [::schema-valid? ::schema-valid?]
  [::test-covered? ::test-covered?]
  [::source :string]
  [::fingerprint ::content-hash/digest]
  [::recorded-fingerprint ::content-hash/digest]
  [::nursery-test ::test-outcome]
  [::compiled-test ::test-outcome]])
(schema/register!
 ::function-row
 [:map
  [:seon.fn/sym :string]
  [:seon.fn/source :string]
  [:seon.fn/source-fingerprint {:optional true} ::content-hash/digest]
  [:seon.fn/execution-tier {:optional true} ::tier]
  [:seon.fn/spec {:optional true} :string]
  [:seon.fn/schema-error {:optional true} :string]
  [:seon.fn/arglists {:optional true} :string]
  [:seon.fn/doc {:optional true} :string]])
(schema/register! ::contexts [:vector ::context/ctx])
(schema/register! ::function-var 'some?)
(schema/register!
 ::install-request
 [:or
  [:map {:closed true}
   [::context/base ::context/base]
   [::context/registry ::context/registry]
   [::function-row ::function-row]
   [::contexts {:optional true} ::contexts]]
  [:map {:closed true}
   [::context/registry ::context/registry]
   [::function-row ::function-row]
   [::function-var ::function-var]
   [::contexts {:optional true} ::contexts]]])
(schema/register!
 ::graduate-request
 [:map {:closed true}
  [::context/base ::context/base]
  [::context/registry ::context/registry]
  [::context/writer ::context/writer]
  [::function-row ::function-row]
  [::contexts {:optional true} ::contexts]])
(schema/register! ::function-rows [:vector ::function-row])
(schema/register!
 ::rebuild-request
 [:map {:closed true}
  [::context/base ::context/base]
  [::context/registry ::context/registry]
  [::context/writer ::context/writer]])
(schema/register! ::installed [:int {:min 0}])
(schema/register! ::graduated [:int {:min 0}])
(schema/register! ::nursery [:int {:min 0}])
(schema/register! ::failures [:vector :map])
(schema/register!
 ::rebuild-report
 [:map {:closed true}
  [::installed ::installed]
  [::graduated ::graduated]
  [::nursery ::nursery]
  [::failures ::failures]])

(def ^:private recorded-function-query
  '[:find (pull ?fn [:seon.fn/sym
                     :seon.fn/source
                     :seon.fn/source-fingerprint
                     :seon.fn/execution-tier
                     :seon.fn/spec
                     :seon.fn/schema-error
                     :seon.fn/arglists
                     :seon.fn/doc])
    :where
    [?fn :seon.fn/execution-tier]])

(defn fingerprint
  "Fingerprint exact recorded source bytes with the content-hash owner."
  {:malli/schema [:=> [:cat :string] ::content-hash/digest]}
  [source]
  (content-hash/sha-256 source))

(defn trust-gate?
  "True when schema and both-tier differential test facts are green."
  {:malli/schema [:=> [:cat ::gate-facts] :boolean]}
  [{schema-valid? ::schema-valid?
    test-covered? ::test-covered?
    source ::source
    fingerprint-value ::fingerprint
    recorded-fingerprint ::recorded-fingerprint
    nursery-test ::nursery-test
    compiled-test ::compiled-test}]
  (boolean
   (and schema-valid?
        test-covered?
        (= fingerprint-value (fingerprint source))
        (= fingerprint-value recorded-fingerprint)
        (::ok? nursery-test)
        (::ok? compiled-test)
        (= (::result-edn nursery-test)
           (::result-edn compiled-test)))))

(defn effective-tier
  "Derive the executable tier from current source and graduation facts."
  {:malli/schema [:=> [:cat ::function-row] ::tier]}
  [function-row]
  (if (and (= :graduated (:seon.fn/execution-tier function-row))
           (= (fingerprint (:seon.fn/source function-row))
              (:seon.fn/source-fingerprint function-row)))
    :graduated
    :nursery))

(defn- error-result [message]
  {::ok? false ::error (str message)})

(defn- row-symbol [function-row]
  (symbol (:seon.fn/sym function-row)))

(defn- row-lib [function-row]
  (some-> function-row row-symbol namespace symbol))

(defn- row-name [function-row]
  (some-> function-row row-symbol name symbol))

(defn- source-form [function-row]
  (let [source (:seon.fn/source function-row)
        lib (row-lib function-row)
        forms (record/read-forms {::record/source source
                                  ::record/ns-sym lib})
        form (first forms)]
    (when (and (= 1 (count forms))
               (seq? form)
               (contains? '#{defn defn-} (first form))
               (= (row-name function-row) (second form)))
      form)))

(defn- schema-valid? [function-row]
  (boolean
   (and (:seon.fn/spec function-row)
        (not (:seon.fn/schema-error function-row))
        (try
          (m/schema (edn/read-string (:seon.fn/spec function-row)))
          true
          (catch Throwable _ false)))))

(defn- nursery-context+var [base function-row]
  (let [ctx (context/fork-context base)
        lib (row-lib function-row)
        source (:seon.fn/source function-row)]
    (context/ensure-context-ns! ctx lib)
    (let [envelope
          (first
           (context/replay-defs!
            ctx [(str "(in-ns '" lib ")\n" source)]))]
      (if (and (:seon.eval/ok? envelope)
               (instance? Var (:seon.eval/value envelope)))
        [ctx (:seon.eval/value envelope)]
        (throw
         (ex-info "The recorded source did not produce one SCI function var."
                  {:seon.host.graduate/envelope envelope}))))))

(defn- compiled-var [function-row]
  (let [lib (row-lib function-row)
        form (source-form function-row)
        target-ns (or (find-ns lib) (create-ns lib))]
    (when-not form
      (throw (ex-info "Graduation requires one recorded defn form."
                      {:seon.fn/sym (:seon.fn/sym function-row)})))
    (binding [*ns* target-ns]
      (clojure.core/refer 'clojure.core)
      (clojure.core/eval form))))

(defn- test-outcome
  ([function-var]
   (test-outcome nil function-var))
  ([ctx function-var]
   (if-let [test-fn (:test (meta function-var))]
     (try
       {::ok? true
        ::result-edn
        (pr-str (if ctx
                  (sci.ctx-store/with-ctx ctx (test-fn))
                  (test-fn)))}
       (catch Throwable throwable
         (error-result (or (.getMessage throwable) (str throwable)))))
     (error-result "The recorded function has no inline :test example."))))

(defn- wrapper [function-row implementation]
  {(row-name function-row)
   (cond-> {::context/wrapper-fn implementation}
     (:seon.fn/arglists function-row)
     (assoc ::context/arglists
            (edn/read-string (:seon.fn/arglists function-row)))
     (seq (:seon.fn/doc function-row))
     (assoc ::context/doc (:seon.fn/doc function-row)))})

(defn- install-implementation!
  [registry contexts function-row implementation]
  (let [lib (row-lib function-row)
        source-fingerprint (fingerprint (:seon.fn/source function-row))
        implementation
        (with-meta implementation
          (assoc (meta implementation)
                 :seon.fn/source-fingerprint source-fingerprint))]
    (context/register-wrappers!
     {::context/registry registry
      ::context/lib lib
      ::context/wrappers (wrapper function-row implementation)})
    (doseq [ctx contexts]
      (context/install-registered-wrappers!
       {::context/registry registry ::context/ctx ctx ::context/lib lib}))))

(defn install-nursery!
  "Install recorded source through the shared registry as interpreted code."
  {:malli/schema [:=> [:cat ::install-request] :map]}
  [{base ::context/base
    registry ::context/registry
    function-row ::function-row
    function-var ::function-var
    contexts ::contexts}]
  (try
    (let [function-var (or function-var
                           (second (nursery-context+var base function-row)))]
      (install-implementation! registry contexts function-row @function-var)
      {::ok? true
       ::tier :nursery
       ::fingerprint (fingerprint (:seon.fn/source function-row))})
    (catch Throwable throwable
      (error-result (or (.getMessage throwable) (str throwable))))))

(defn graduate!
  "Promote one recorded function after the computed trust gate passes."
  {:malli/schema [:=> [:cat ::graduate-request] :map]}
  [{base ::context/base
    registry ::context/registry
    writer ::context/writer
    function-row ::function-row
    contexts ::contexts}]
  (try
    (let [source (:seon.fn/source function-row)
          current-fingerprint (fingerprint source)
          [interpreted-ctx interpreted] (nursery-context+var base function-row)
          nursery-test (test-outcome interpreted-ctx interpreted)
          schema-valid (schema-valid? function-row)
          test-covered (boolean (:test (meta interpreted)))
          preflight? (and schema-valid test-covered (::ok? nursery-test))
          compiled (when preflight? (compiled-var function-row))
          compiled-test (if compiled
                          (test-outcome compiled)
                          (error-result
                           "Compilation did not run before preflight passed."))
          facts {::schema-valid? schema-valid
                 ::test-covered? (boolean (and test-covered compiled
                                                (:test (meta compiled))))
                 ::source source
                 ::fingerprint current-fingerprint
                 ::recorded-fingerprint
                 (:seon.fn/source-fingerprint function-row)
                 ::nursery-test nursery-test
                 ::compiled-test compiled-test}]
      (if-not (trust-gate? facts)
        (assoc (error-result "The function did not pass the graduation gate.")
               ::gate-facts facts)
        (let [recorded
              (context/transact-writer!
               writer
               [{:seon.fn/sym (:seon.fn/sym function-row)
                 :seon.fn/source-fingerprint current-fingerprint
                 :seon.fn/execution-tier :graduated}])]
          (if-not (:seon.db/ok? recorded)
            (assoc (error-result "The graduation facts did not commit.")
                   :seon/error (:seon/error recorded))
            (do
              (install-implementation! registry contexts function-row @compiled)
              {::ok? true
               ::tier :graduated
               ::fingerprint current-fingerprint
               ::gate-facts facts})))))
    (catch Throwable throwable
      (error-result (or (.getMessage throwable) (str throwable))))))

(defn- install-row! [base registry function-row]
  (try
    (let [tier (effective-tier function-row)
          function-var (if (= :graduated tier)
                         (compiled-var function-row)
                         (second (nursery-context+var base function-row)))]
      (install-implementation! registry [] function-row @function-var)
      {::ok? true ::tier tier})
    (catch Throwable throwable
      (error-result (or (.getMessage throwable) (str throwable))))))

(defn rebuild!
  "Rebuild registry implementations from current corpus graduation facts."
  {:malli/schema [:=> [:cat ::rebuild-request] ::rebuild-report]}
  [{base ::context/base
    registry ::context/registry
    writer ::context/writer}]
  (let [queried (context/query-writer! writer recorded-function-query [])
        rows (if (:seon/error queried) [] (mapv first queried))
        query-failures (if (:seon/error queried) [queried] [])
        outcomes (mapv (fn [row]
                         (assoc (install-row! base registry row)
                                :seon.fn/sym (:seon.fn/sym row)))
                       rows)]
    {::installed (count (filter ::ok? outcomes))
     ::graduated (count (filter #(and (::ok? %)
                                      (= :graduated (::tier %)))
                                outcomes))
     ::nursery (count (filter #(and (::ok? %)
                                    (= :nursery (::tier %)))
                              outcomes))
     ::failures (into query-failures (remove ::ok?) outcomes)}))
