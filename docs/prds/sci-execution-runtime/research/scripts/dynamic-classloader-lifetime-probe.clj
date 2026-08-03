(ns dynamic-classloader-lifetime-probe
  (:require [clojure.core.server :as server]
            [clojure.string :as str])
  (:import [clojure.lang DynamicClassLoader MultiFn Var]
           [java.io StringReader]
           [java.lang.ref Reference WeakReference]
           [java.util ArrayList]
           [java.util.concurrent ConcurrentHashMap]))

(def ^:private class-cache-field
  (doto (.getDeclaredField DynamicClassLoader "classCache")
    (.setAccessible true)))

(defn- class-cache
  ^ConcurrentHashMap []
  (.get class-cache-field nil))

(defn- identity-id [value]
  (when value
    (System/identityHashCode value)))

(defn- loader-description [^ClassLoader loader]
  (loop [loader loader
         result []]
    (if loader
      (recur (.getParent loader)
             (conj result
                   {::loader-id (identity-id loader)
                    ::loader-class (.getName (class loader))}))
      result)))

(defn- cache-entry-description [[class-name ^Reference reference]]
  (let [loaded-class (.get reference)
        loader (some-> ^Class loaded-class .getClassLoader)]
    {::class-name class-name
     ::reference-id (identity-id reference)
     ::class-id (identity-id loaded-class)
     ::loader-id (identity-id loader)
     ::loader-chain (loader-description loader)
     ::cleared? (nil? loaded-class)}))

(defn- cache-entries [prefix]
  (->> (class-cache)
       (filter (fn [[class-name _]] (str/starts-with? class-name prefix)))
       (map cache-entry-description)
       (sort-by ::class-name)
       vec))

(defn- cache-counts []
  (reduce (fn [counts [_ ^Reference reference]]
            (update counts (if (.get reference) ::live ::cleared) (fnil inc 0)))
          {::live 0 ::cleared 0}
          (class-cache)))

(defn- root-values [namespace-symbol]
  (mapcat
   (fn [[symbol ^Var var]]
     (when (.hasRoot var)
       (let [root (.getRawRoot var)]
         (cons {::symbol symbol ::value root}
               (when (instance? MultiFn root)
                 (map (fn [[dispatch-value method]]
                        {::symbol [symbol dispatch-value] ::value method})
                      (.getMethodTable ^MultiFn root)))))))
   (ns-interns namespace-symbol)))

(defn- root-loader-descriptions [namespace-symbol]
  (->> (root-values namespace-symbol)
       (keep (fn [{::keys [symbol value]}]
               (when-let [loader (some-> value class .getClassLoader)]
                 {::symbol symbol
                  ::value-class (.getName (class value))
                  ::loader-id (identity-id loader)
                  ::loader-chain (loader-description loader)})))
       vec))

(defn- summarize-root-loaders [descriptions]
  (let [by-loader (group-by ::loader-id descriptions)
        examples (->> by-loader
                      (sort-by key)
                      (take 4)
                      (mapv (fn [[loader-id values]]
                              {::loader-id loader-id
                               ::symbols (mapv ::symbol (take 5 values))
                               ::loader-depth (count (::loader-chain (first values)))
                               ::loader-chain
                               (vec (take 4 (::loader-chain (first values))))})))]
    {::root-count (count descriptions)
     ::distinct-loader-count (count by-loader)
     ::loader-depths (frequencies (map #(count (::loader-chain %)) descriptions))
     ::examples examples}))

(defn- weak-watch [prefix]
  (into {}
        (keep (fn [[class-name ^Reference reference]]
                (when (str/starts-with? class-name prefix)
                  (when-let [loaded-class (.get reference)]
                    [class-name
                     {::class (WeakReference. loaded-class)
                      ::loader (WeakReference. (.getClassLoader ^Class loaded-class))}]))))
        (class-cache)))

(defn- watch-result [watch]
  (reduce-kv
   (fn [result class-name {::keys [class loader]}]
     (let [class-live? (some? (.get ^WeakReference class))
           loader-live? (some? (.get ^WeakReference loader))]
       (-> result
           (update [class-live? loader-live?] (fnil inc 0))
           (cond-> (not class-live?)
             (update ::collected-classes (fnil conj []) class-name)))))
   {}
   watch))

(defn- force-soft-reference-pressure! []
  (let [held (ArrayList.)
        allocated (volatile! 0)]
    (try
      (loop []
        (.add held (byte-array (* 1024 1024)))
        (vswap! allocated inc)
        (recur))
      (catch OutOfMemoryError _
        nil)
      (finally
        (.clear held)))
    (dotimes [_ 3]
      (System/gc)
      (Thread/sleep 200))
    @allocated))

(defn- throwable-chain [throwable]
  (loop [throwable throwable
         result []]
    (if throwable
      (recur (.getCause ^Throwable throwable)
             (conj result
                   {::throwable-class (.getName (class throwable))
                    ::throwable-message (.getMessage ^Throwable throwable)
                    ::stack (->> (.getStackTrace ^Throwable throwable)
                                 (take 16)
                                 (mapv str))}))
      result)))

(defn- datahike-create-result []
  (let [database-id (random-uuid)
        config {:store {:backend :memory :id database-id}
                :keep-history? true
                :schema-flexibility :write}]
    (try
      (let [create-database (requiring-resolve 'datahike.api/create-database)
            delete-database (requiring-resolve 'datahike.api/delete-database)
            connect (requiring-resolve 'datahike.api/connect)
            release (requiring-resolve 'datahike.api/release)
            transact (requiring-resolve 'datahike.api/transact)
            created-config (create-database config)
            connection (connect created-config)]
        (try
          (let [report (transact
                        connection
                        [{:db/ident :dynamic-classloader-probe/value
                          :db/valueType :db.type/string
                          :db/cardinality :db.cardinality/one}
                         {:dynamic-classloader-probe/value "proved"}])]
            {::outcome ::created-and-transacted
             ::created-config created-config
             ::transaction-count (count (:tx-data report))})
          (finally
            (release connection)
            (delete-database config))))
      (catch Throwable throwable
        {::outcome ::failed
         ::throwables (throwable-chain throwable)}))))

(defn- pressure-probe! []
  (require 'datahike.api)
  (let [writer-before (cache-entries "datahike.writer$")
        transaction-before (cache-entries "datahike.db.transaction$")
        writer-watch (weak-watch "datahike.writer$")
        transaction-watch (weak-watch "datahike.db.transaction$")
        writer-root-loaders (root-loader-descriptions 'datahike.writer)
        transaction-root-loaders (root-loader-descriptions 'datahike.db.transaction)]
    (prn {::event ::before-pressure
          ::java-version (System/getProperty "java.version")
          ::clojure-version (clojure-version)
          ::max-memory (.maxMemory (Runtime/getRuntime))
          ::cache-counts (cache-counts)
          ::writer-entry-count (count writer-before)
          ::transaction-entry-count (count transaction-before)
          ::writer-loader-count (count (set (keep ::loader-id writer-before)))
          ::transaction-loader-count (count (set (keep ::loader-id transaction-before)))
          ::writer-root-loaders (summarize-root-loaders writer-root-loaders)
          ::transaction-root-loaders (summarize-root-loaders transaction-root-loaders)
          ::writer-sample (mapv #(select-keys % [::class-name ::loader-id])
                                (take 4 writer-before))
          ::transaction-sample (mapv #(select-keys % [::class-name ::loader-id])
                                     (take 4 transaction-before))})
    (let [allocated-megabytes (force-soft-reference-pressure!)
          writer-after (cache-entries "datahike.writer$")
          transaction-after (cache-entries "datahike.db.transaction$")
          writer-watch-result (watch-result writer-watch)
          transaction-watch-result (watch-result transaction-watch)]
      (prn {::event ::after-pressure
            ::allocated-megabytes allocated-megabytes
            ::cache-counts (cache-counts)
            ::writer-entry-count (count writer-after)
            ::transaction-entry-count (count transaction-after)
            ::writer-cleared-count (count (filter ::cleared? writer-after))
            ::transaction-cleared-count (count (filter ::cleared? transaction-after))
            ::writer-watch (update writer-watch-result ::collected-classes
                                   #(vec (take 40 %)))
            ::transaction-watch (update transaction-watch-result ::collected-classes
                                        #(vec (take 40 %)))})
      (let [create-result (datahike-create-result)]
        (prn {::event ::create-database
              ::result create-result
              ::cache-counts (cache-counts)
              ::writer-cleared-count
              (count (filter ::cleared? (cache-entries "datahike.writer$")))
              ::transaction-cleared-count
              (count (filter ::cleared? (cache-entries "datahike.db.transaction$")))})
        create-result))))

(defn- prepare-synthetic-target [retain-loader?]
  (let [namespace (the-ns 'dynamic-classloader-lifetime-probe)
        _ (eval '(def synthetic-evictable-function (fn [] :target)))
        target-var (ns-resolve namespace 'synthetic-evictable-function)
        target-function (.getRawRoot ^Var target-var)
        target-class (class target-function)
        target-class-name (.getName ^Class target-class)
        target-loader (.getClassLoader ^Class target-class)
        caller-form (list 'defn
                          'synthetic-reconstruct-evicted-function
                          []
                          (list 'clojure.lang.Reflector/invokeConstructor
                                (list '.loadClass
                                      (list '.getClassLoader
                                            (list 'class
                                                  'synthetic-reconstruct-evicted-function))
                                      target-class-name)
                                (list 'object-array 0)))
        _ (eval caller-form)
        caller-var (ns-resolve namespace 'synthetic-reconstruct-evicted-function)
        class-reference (WeakReference. target-class)
        loader-reference (WeakReference. target-loader)]
    (ns-unmap namespace 'synthetic-evictable-function)
    (cond-> {::target-class-name target-class-name
             ::target-class-reference class-reference
             ::target-loader-reference loader-reference
             ::target-loader-id (identity-id target-loader)
             ::target-loader-chain (loader-description target-loader)
             ::caller-var caller-var
             ::caller-loader-chain
             (loader-description (.getClassLoader (class (.getRawRoot ^Var caller-var))))}
      retain-loader? (assoc ::retained-loader target-loader))))

(defn- synthetic-pressure-probe! [retain-loader?]
  (let [{::keys [target-class-name
                 target-class-reference
                 target-loader-reference
                 target-loader-chain
                 caller-var
                 caller-loader-chain]
         :as prepared}
        (prepare-synthetic-target retain-loader?)
        cache-live-before? (some? (.get ^Reference (.get (class-cache) target-class-name)))
        allocated-megabytes (force-soft-reference-pressure!)
        after-cache-reference (.get ^Reference (.get (class-cache) target-class-name))
        invocation
        (try
          {::outcome ::returned
           ::value (.invoke ^clojure.lang.IFn (.getRawRoot ^Var caller-var))}
          (catch Throwable throwable
            {::outcome ::failed
             ::throwables (throwable-chain throwable)}))]
    (prn {::event ::synthetic-pressure
          ::retain-loader? retain-loader?
          ::allocated-megabytes allocated-megabytes
          ::target-class-name target-class-name
          ::target-loader-chain target-loader-chain
          ::caller-loader-chain caller-loader-chain
          ::cache-live-before? cache-live-before?
          ::cache-live-after? (some? after-cache-reference)
          ::weak-class-live-after? (some? (.get ^WeakReference target-class-reference))
          ::weak-loader-live-after? (some? (.get ^WeakReference target-loader-reference))
          ::retained-loader-id (some-> (::retained-loader prepared) identity-id)
          ::invocation invocation})
    invocation))

(defn- prepl-probe! []
  (let [forms (str
               "(let [l (.getClassLoader (class (fn [] :first)))] "
               "  [(System/identityHashCode l) "
               "   (some-> l .getParent System/identityHashCode)])\n"
               "(let [l (.getClassLoader (class (fn [] :second)))] "
               "  [(System/identityHashCode l) "
               "   (some-> l .getParent System/identityHashCode)])\n"
               "(do (require 'datahike.db.transaction :reload) "
               "    (let [l (.getClassLoader (class "
               "              (var-get #'datahike.db.transaction/transact-tx-data)))] "
               "      {:function-loader (System/identityHashCode l) "
               "       :parents (loop [p l result []] "
               "                  (if p "
               "                    (recur (.getParent p) "
               "                           (conj result [(System/identityHashCode p) "
               "                                         (.getName (class p))])) "
               "                    result))}))\n")
        output (atom [])]
    (server/prepl (clojure.lang.LineNumberingPushbackReader.
                   (StringReader. forms))
                  #(swap! output conj %))
    (prn {::event ::prepl
          ::messages @output})
    @output))

(defn -main [& [mode]]
  (try
    (case mode
      "prepl" (prepl-probe!)
      "pressure" (pressure-probe!)
      "synthetic" (synthetic-pressure-probe! false)
      "synthetic-retain-loader" (synthetic-pressure-probe! true)
      (throw (ex-info "Expected mode: pressure, prepl, synthetic, or synthetic-retain-loader."
                      {::mode mode})))
    (finally
      (shutdown-agents))))

(apply -main *command-line-args*)
