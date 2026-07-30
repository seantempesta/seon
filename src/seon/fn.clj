(ns seon.fn
  "Build-time indexing of the Clojure program graph through the one reader."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.program :as program]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.sci.reader :as reader]))

(schema.edn/load! {})

(def source-roots
  "The Clojure source roots admitted to the program graph."
  ["src" "test"])

(defn- source-file?
  [file]
  (and (.isFile ^java.io.File file)
       (or (str/ends-with? (.getName ^java.io.File file) ".clj")
           (str/ends-with? (.getName ^java.io.File file) ".cljc"))))

(defn- durable-row
  [event]
  ;; Build indexing keeps every directly read top-level function, including
  ;; private and uncontracted helpers, as input to the future call graph.
  (program/declaration-row event :all))

(defn- source-files
  [roots]
  (let [files
        (into []
              (mapcat (fn [root]
                        (->> (file-seq (io/file root))
                             (filter source-file?)
                             (sort-by (fn [file]
                                        (.getCanonicalPath
                                         ^java.io.File file))))))
              roots)]
    files))

(defn- actual-reader-context
  [namespace-object]
  {:seon.sci.reader/ns (ns-name namespace-object)
   :seon.sci.reader/aliases
   (into {}
         (map (fn [[local target]] [local (ns-name target)]))
         (ns-aliases namespace-object))
   :seon.sci.reader/refers
   (into {}
         (keep (fn [[local target]]
                 (let [{target-ns :ns target-name :name} (meta target)]
                   (when (and target-ns
                              (not= 'clojure.core (ns-name target-ns)))
                     [local (symbol (str (ns-name target-ns))
                                    (str target-name))]))))
         (ns-refers namespace-object))})

(defonce ^:private inspected-rows (atom {}))

(def ^:private default-jvm-imports
  (let [name (symbol (str "seon.fn.jvm-defaults." (random-uuid)))
        namespace-object (create-ns name)]
    (try
      (ns-imports namespace-object)
      (finally
        (remove-ns name)))))

(defn- namespace-row
  [namespace-object source]
  (let [{namespace-name :seon.sci.reader/ns
         aliases :seon.sci.reader/aliases
         refers :seon.sci.reader/refers}
        (actual-reader-context namespace-object)
        imports
        (let [current (ns-imports namespace-object)]
          (into {}
                (concat
                 (keep (fn [[local target]]
                         (when-not (= target (get default-jvm-imports local))
                           [local (symbol (.getName ^Class target))]))
                       current)
                 (keep (fn [[local _target]]
                         (when-not (contains? current local)
                           [local nil]))
                       default-jvm-imports))))
        target-namespaces (into #{} (concat (vals aliases)
                                             (keep (comp symbol namespace)
                                                   (vals refers))))]
    (cond-> {:seon.ns/name namespace-name
             :seon.ns/source source
             :seon.ns/aliases
             (into #{}
                   (map (fn [[local target]]
                          {:seon.ns.alias/local local
                           :seon.ns.alias/target-ns target}))
                   aliases)
             :seon.ns/imports
             (into #{}
                   (map (fn [[local target-class]]
                          (cond-> {:seon.ns.import/local local}
                            target-class
                            (assoc :seon.ns.import/target-class
                                   target-class))))
                   imports)
             :seon.ns/refers
             (into #{}
                   (map (fn [[local target]]
                          {:seon.ns.refer/local local
                           :seon.ns.refer/target-ns
                           (symbol (namespace target))
                           :seon.ns.refer/target-name
                           (symbol (name target))}))
                   refers)}
      (seq target-namespaces)
      (assoc :seon.ns/requires target-namespaces))))

(defn- var-state
  [namespace-objects]
  (into {}
        (mapcat
         (fn [namespace-object]
           (map
            (fn [[local var]]
              (let [metadata (meta var)
                    root (when (bound? var) @var)
                    qualified (symbol (str (ns-name namespace-object))
                                      (str local))]
                [qualified
                 {:var var
                  :metadata metadata
                  :root-identity (when root
                                   (System/identityHashCode root))}]))
            (ns-interns namespace-object))))
        (distinct namespace-objects)))

(defn- var-row
  [qualified {:keys [var metadata]} source]
  (let [namespace-name (some-> metadata :ns ns-name)
        declaration-name (:name metadata)
        qualified (if (and namespace-name declaration-name)
                    (symbol (str namespace-name) (str declaration-name))
                    qualified)]
    (cond
      (:test metadata)
      {:seon.test/sym (str qualified)
       :seon.test/ns [:seon.ns/name namespace-name]
       :seon.test/source source}

      (and (:arglists metadata)
           (not (:macro metadata))
           (bound? var)
           (fn? @var))
      (cond->
       {:seon.fn/sym (str qualified)
        :seon.fn/ns [:seon.ns/name namespace-name]
        :seon.fn/source source
        :seon.fn/arglists (pr-str (:arglists metadata))
        :seon.fn/private? (boolean (:private metadata))}
        (:doc metadata) (assoc :seon.fn/doc (:doc metadata))
        (:malli/schema metadata)
        (assoc :seon.fn/spec (pr-str (:malli/schema metadata)))
        (contains? #{:io :compute} (:seon.workload metadata))
        (assoc :seon.fn/workload (:seon.workload metadata)))

      :else nil)))

(defn- inspect-source-file
  [file rows]
  (let [eof (Object.)
        canonical-path (.getCanonicalPath ^java.io.File file)]
    (with-open [input (clojure.lang.LineNumberingPushbackReader.
                       (io/reader file))]
      (binding [*file* canonical-path
                *ns* (the-ns 'user)
                *read-eval* false]
        (loop [schemas (schema/registered-schemas)]
          (let [starting-ns *ns*
                vars (var-state [starting-ns])
                context (actual-reader-context starting-ns)
                [form source]
                (read+string {:eof eof
                              :read-cond :allow
                              :features #{:clj}}
                             input)]
            (when-not (identical? eof form)
              (let [events
                    (reader/read
                     (merge context
                            {:seon.sci.reader/text source
                             :seon.sci.reader/features #{:clj}
                             :seon.sci.reader/tags {'inst identity
                                                    'uuid identity}}))]
                (when (map? events)
                  (throw
                   (ex-info (:seon.error/message events)
                            (assoc (:seon.error/data events)
                                   :seon.fn/file canonical-path))))
                (let [event (first events)]
                  (eval form)
                  (let [next-schemas (schema/registered-schemas)
                        next-vars (var-state [starting-ns *ns*])
                        changed-schemas
                        (into []
                              (keep (fn [[schema-key definition]]
                                      (when (not= definition
                                                  (get schemas schema-key))
                                        {:seon.schema/key schema-key
                                         :seon.schema/form
                                         (pr-str definition)})))
                              next-schemas)]
                    (when-let [row (some-> event durable-row)]
                      (when (:seon.ns/name row)
                        (swap! rows assoc (program/row-identity row) row)))
                    (doseq [namespace-object (distinct [starting-ns *ns*])
                            :when (not= 'user (ns-name namespace-object))]
                      (let [identity [:seon.ns/name
                                      (ns-name namespace-object)]
                            observed (namespace-row namespace-object source)
                            existing (get @rows identity)]
                        (swap! rows assoc identity
                               (merge existing observed
                                      (when existing
                                        {:seon.ns/source
                                         (:seon.ns/source existing)
                                         :seon.ns/requires
                                         (into (or (:seon.ns/requires existing)
                                                   #{})
                                               (:seon.ns/requires observed))})))))
                    (doseq [[qualified state] next-vars
                            :when (and (not= state (get vars qualified))
                                       (= canonical-path
                                          (:file (:metadata state))))]
                      (when-let [row (var-row qualified state source)]
                        (swap! rows assoc (program/row-identity row) row)))
                    (doseq [[qualified state] vars
                            :when (and (not (contains? next-vars qualified))
                                       (= canonical-path
                                          (:file (:metadata state))))]
                      (swap! rows dissoc [:seon.fn/sym (str qualified)])
                      (swap! rows dissoc [:seon.test/sym (str qualified)]))
                    (doseq [row changed-schemas]
                      (swap! rows assoc (program/row-identity row) row))
                    (doseq [schema-key
                            (set/difference (set (keys schemas))
                                            (set (keys next-schemas)))]
                      (swap! rows dissoc [:seon.schema/key schema-key]))
                    (recur next-schemas)))))))))))

(defn- inspect-rows!
  [request output-path]
  (let [rows
        (atom
         (into {}
               (map (fn [[schema-key definition]]
                      [[:seon.schema/key schema-key]
                       {:seon.schema/key schema-key
                        :seon.schema/form (pr-str definition)}]))
               (schema/registered-schemas)))]
    (try
      (doseq [file (source-files (:seon.fn/roots request))]
        (inspect-source-file file rows))
      (spit output-path (pr-str {:seon.fn/rows (vec (vals @rows))}))
      (catch Throwable error
        (spit output-path
              (pr-str {:seon.error/message (or (ex-message error)
                                               (str error))
                       :seon.error/data
                       (merge {:seon.error/kind ::index-refused}
                              (ex-data error))}))
        (throw error)))))

(defn- content-digest
  [request]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")
        files (source-files
               (distinct (conj (:seon.fn/roots request) "src")))]
    (doseq [value (concat [(System/getProperty "java.class.path")]
                          (mapcat (fn [file]
                                    [(.getCanonicalPath ^java.io.File file)
                                     (slurp file)])
                                  files))]
      (.update digest (.getBytes (str value) java.nio.charset.StandardCharsets/UTF_8))
      (.update digest (byte-array [(byte 0)])))
    (apply str (map #(format "%02x" (bit-and 0xff %)) (.digest digest)))))

(defn- isolated-rows
  [request]
  (let [directory (io/file "tmp" "program-index")
        digest (content-digest request)
        id (str (random-uuid))
        request-file (io/file directory (str id ".request.edn"))
        output-file (io/file directory (str id ".result.edn"))
        java (io/file (System/getProperty "java.home") "bin" "java")]
    (.mkdirs directory)
    (spit request-file
          (pr-str (select-keys request [:seon.fn/roots])))
    (try
      (if-let [cached (get @inspected-rows digest)]
        cached
        (let [process
            (.start
             (doto
              (ProcessBuilder.
               ^java.util.List
               [(.getCanonicalPath java)
                "-cp" (System/getProperty "java.class.path")
                "clojure.main" "-m" "seon.fn"
                "--inspect" (.getCanonicalPath request-file)
                (.getCanonicalPath output-file)])
               (.redirectErrorStream true)))
            output (slurp (.getInputStream process))
            exit (.waitFor process)
            result (when (.isFile output-file)
                     (edn/read-string (slurp output-file)))]
        (when (or (not (zero? exit)) (:seon.error/message result))
          (throw
           (ex-info
            (or (:seon.error/message result)
                "Isolated program inspection failed.")
            (merge {:seon.error/kind ::index-refused
                    ::inspector-output output
                    ::inspector-exit exit}
                   (:seon.error/data result)))))
          (let [rows (:seon.fn/rows result)]
            (swap! inspected-rows assoc digest rows)
            rows)))
      (finally
        (.delete request-file)
        (.delete output-file)))))

(defn rows
  "Canonical program rows produced by isolated sequential source evaluation."
  {:malli/schema [:=> [:cat :seon.fn/index-request] [:vector :map]]}
  [request]
  (isolated-rows request))

(defn -main
  [& [operation request-path output-path]]
  (when-not (= "--inspect" operation)
    (throw (ex-info "Unknown seon.fn operation." {::operation operation})))
  (inspect-rows! (edn/read-string (slurp request-path)) output-path))

(defn- row-identity
  [row]
  (program/row-identity row))

(defn- ref-value
  [db identity-attr value]
  (when value
    (if (and (vector? value) (= identity-attr (first value)))
      value
      [identity-attr
       (or
        (when (symbol? value) value)
        (when (map? value) (get value identity-attr))
        (d/q '[:find ?identity .
               :in $ ?entity ?identity-attr
               :where [?entity ?identity-attr ?identity]]
             db
             (if (map? value) (:db/id value) value)
             identity-attr)
        (throw
         (ex-info
          "Source indexing could not resolve a program reference."
          {:seon.error/kind ::index-refused
           ::identity-attr identity-attr
           ::reference value})))])))

(defn- process-identity
  [db process]
  (when process
    (d/q '[:find ?process-id .
           :in $ ?process
           :where [?process :seon.db.process/id ?process-id]]
         db
         (if (map? process) (:db/id process) process))))

(defn- component-binding
  [binding]
  (dissoc binding :db/id))

(defn- canonical-row
  [db row]
  (let [row (program/canonical-row row)
        row
        (cond-> row
          (:seon.fn/ns row)
          (update :seon.fn/ns #(ref-value db :seon.ns/name %))

          (:seon.test/ns row)
          (update :seon.test/ns #(ref-value db :seon.ns/name %))

          (contains? row :seon.ns/aliases)
          (update :seon.ns/aliases
                  #(into #{} (map component-binding) %))

          (contains? row :seon.ns/imports)
          (update :seon.ns/imports
                  #(into #{} (map component-binding) %))

          (contains? row :seon.ns/refers)
          (update :seon.ns/refers
                  #(into #{} (map component-binding) %))

          (contains? row :seon.ns/requires)
          (update :seon.ns/requires set))]
    (into
     {}
     (remove
      (fn [[attribute value]]
        (or (nil? value)
            (and (contains? #{:seon.ns/requires
                              :seon.ns/aliases
                              :seon.ns/imports
                              :seon.ns/refers}
                            attribute)
                 (empty? value)))))
     row)))

(defn- current-rows
  [db shape]
  (let [identity-attr (:seon.program/identity-attribute shape)
        source-attr (:seon.program/source-attribute shape)
        provenance
        (into
         {}
         (map (fn [[entity process-id]] [entity process-id]))
         (d/q '[:find ?entity ?process-id
                :in $ ?identity-attr ?source-attr
                :where
                [?entity ?identity-attr _]
                [?entity ?source-attr _ ?tx]
                [?tx :seon.db/process ?process]
                [?process :seon.db.process/id ?process-id]]
              db
              identity-attr
              source-attr))]
    (into
     {}
     (map
      (fn [[entity identity]]
        (let [row
              (d/pull
               db
               [:db/id
                identity-attr
                source-attr
                :seon.ns/doc
                :seon.ns/requires
                {:seon.ns/aliases
                 [:db/id
                  :seon.ns.alias/local
                  :seon.ns.alias/target-ns]}
                {:seon.ns/imports
                 [:db/id
                  :seon.ns.import/local
                  :seon.ns.import/target-class]}
                {:seon.ns/refers
                 [:db/id
                  :seon.ns.refer/local
                  :seon.ns.refer/target-ns
                  :seon.ns.refer/target-name]}
                {:seon.fn/ns [:db/id :seon.ns/name]}
                :seon.fn/arglists
                :seon.fn/doc
                :seon.fn/private?
                :seon.fn/spec
                :seon.fn/workload
                {:seon.test/ns [:db/id :seon.ns/name]}]
               entity)]
          [[identity-attr identity]
           {::entity entity
            ::process-id (get provenance entity)
            ::row (canonical-row db row)
            ::alias-eids (into [] (keep :db/id) (:seon.ns/aliases row))
            ::import-eids (into [] (keep :db/id) (:seon.ns/imports row))
            ::refer-eids (into [] (keep :db/id) (:seon.ns/refers row))}])))
     (d/q '[:find ?entity ?identity
            :in $ ?identity-attr
            :where [?entity ?identity-attr ?identity]]
          db
          identity-attr))))

(defn- assert-one-row-per-identity!
  [desired]
  (when-let [duplicate
             (some (fn [[identity n]] (when (> n 1) identity))
                   (frequencies (map row-identity desired)))]
    (throw
     (ex-info
      "Source indexing refused a duplicate program identity."
      {:seon.error/kind ::index-refused
       ::identity duplicate}))))

(defn- assert-populated!
  [desired]
  (doseq [identity-attr [:seon.ns/name :seon.fn/sym]]
    (when-not (some identity-attr desired)
      (throw
       (ex-info
        (str "Source indexing produced no " identity-attr
             " rows; refusing a partial program graph.")
        {:seon.error/kind ::index-refused
         ::missing-population identity-attr})))))

(defn- changed-row-tx
  [shape identity desired current]
  (let [identity-attr (:seon.program/identity-attribute shape)
        current-row (::row current)
        changed-attrs (program/changed-attributes current-row desired)
        binding-attrs #{:seon.ns/aliases :seon.ns/imports :seon.ns/refers}]
    (when (seq changed-attrs)
      (let [edge-retracts
            (into []
                  (map (fn [eid] [:db.fn/retractEntity eid]))
                  (concat
                   (when (some #{:seon.ns/aliases} changed-attrs)
                     (::alias-eids current))
                   (when (some #{:seon.ns/imports} changed-attrs)
                     (::import-eids current))
                   (when (some #{:seon.ns/refers} changed-attrs)
                     (::refer-eids current))))
            retracts
            (into
             (vec edge-retracts)
             (keep
              (fn [attribute]
                (when (and (not (contains? binding-attrs attribute))
                           (contains? current-row attribute)
                           (not= (get current-row attribute)
                                 (get desired attribute)))
                  [:db.fn/retractAttribute identity attribute])))
             changed-attrs)
            additions
            (select-keys desired (conj changed-attrs identity-attr))]
        (cond-> retracts
          (> (count additions) 1) (conj additions))))))

(defn- shape-plan
  [db process-id shape desired]
  (let [identity-attr (:seon.program/identity-attribute shape)
        current (current-rows db shape)
        desired
        (into {}
              (map
               (fn [row]
                 (let [identity (row-identity row)]
                   [identity (canonical-row db row)])))
              (filter identity-attr desired))
        changes
        (into
         []
         (mapcat
          (fn [[identity desired-row]]
            (if-let [current-row (get current identity)]
              (when (or (= process-id (::process-id current-row))
                        (not (contains? (::row current-row)
                                        (:seon.program/source-attribute shape))))
                (changed-row-tx shape identity desired-row current-row))
              [desired-row])))
         desired)
        stale
        (into
         []
         (keep
          (fn [[identity current-row]]
            (when (and (= process-id (::process-id current-row))
                       (not (contains? desired identity)))
              [:db.fn/retractEntity (::entity current-row)])))
         current)]
    (into changes stale)))

(defn- desired-program-rows
  [request]
  (let [source-rows (rows request)
        canonical-schemas (schema/canonical-schema-rows (java.util.Date. 0))
        canonical-keys (into #{} (map :seon.schema/key) canonical-schemas)
        source-only
        (remove (fn [row]
                  (contains? canonical-keys (:seon.schema/key row)))
                source-rows)]
    (doseq [{schema-key :seon.schema/key
             form-string :seon.schema/form}
            (filter :seon.schema/key source-only)]
      (when-not (and form-string
                     (schema/malli-form? (edn/read-string form-string)))
        (throw
         (ex-info "Source indexing refused a non-Malli schema declaration."
                  {:seon.error/kind ::index-refused
                   :seon.schema/key schema-key}))))
    (into (vec source-only) canonical-schemas)))

(defn- digest-plan
  [db desired-digest]
  (if-not desired-digest
    []
    (let [current
          (d/q '[:find ?ancestor ?digest
                 :where [?ancestor :seon.ancestor/digest ?digest]]
               db)]
      (case (count current)
        0 [{:seon.ancestor/digest desired-digest
            :seon.ancestor/built-at (java.util.Date.)}]
        1 (let [[ancestor current-digest] (first current)]
            (if (= current-digest desired-digest)
              []
              [[:db.fn/retractAttribute ancestor :seon.ancestor/digest]
               {:db/id ancestor :seon.ancestor/digest desired-digest}]))
        (throw
         (ex-info
          "Source indexing requires at most one recorded ancestor digest."
          {:seon.error/kind ::index-refused
           ::recorded-digests (into #{} (map second) current)}))))))

(defn index!
  "Exact-reconcile source-owned program rows and preserve authored facts.

  Rows whose current defining datom carries `:seon.db/process` are owned
  only when that process matches this request. Agent-authored rows and all
  non-program facts are therefore outside the reconciled slice. Namespace,
  function, schema, and test rows absent from the desired population are
  removed. The desired schema population is the canonical evaluated registry
  plus source-only declarations, so canonical rows do not need a family-wide
  stale-removal exemption.

  When `:seon.ancestor/digest` is supplied, its one current value advances
  only after the program rows commit. After priming, that value means “this
  cluster was explicitly synchronized from this source digest, preserving
  agent-authored overrides,” not “this branch was originally forked from
  the ancestor branch named by this digest.” A converged call performs no
  transaction."
  {:malli/schema [:=> [:cat :seon.fn/index-request] :seon.reconcile/result]}
  [{connection :seon.store/branch-connection
    process :seon.db/process
    :as request}]
  (let [program-rows (desired-program-rows request)
        _ (assert-one-row-per-identity! program-rows)
        _ (assert-populated! program-rows)
        process-id (process-identity @connection process)
        _ (when (nil? process-id)
            (throw
             (ex-info
              "Source indexing requires a resolvable process identity."
              {:seon.error/kind ::index-refused
               ::process process})))
        transaction
        (fn [operations]
          (when (seq operations)
            (d/transact
             connection
             (cond-> {:tx-data operations}
               process (assoc :tx-meta {:seon.db/process process}))))
          (count operations))
        namespace-plan
        (shape-plan @connection process-id
                    (program/shape :seon.ns/name)
                    program-rows)
        namespace-operations (transaction namespace-plan)
        declaration-plan
        (into
         []
         (mapcat
          #(shape-plan @connection process-id % program-rows))
         (keep (fn [identity-attribute]
                 (when-not (= :seon.ns/name identity-attribute)
                   (program/shape identity-attribute)))
               program/identity-attributes))
        final-plan
        (into declaration-plan
              (digest-plan @connection (:seon.ancestor/digest request)))
        declaration-operations (transaction final-plan)
        operations (+ namespace-operations declaration-operations)]
    {:seon.reconcile/converged? (zero? operations)
     :seon.reconcile/operations operations}))
