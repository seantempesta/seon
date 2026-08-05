(ns seon.search
  "One per-cluster Lucene index derived from declared database fields.

  The database is truth. The index records the database basis it reflects,
  rebuilds whenever that basis cannot be advanced exactly, and exposes only
  ordinary data through `search`. Lucene objects remain process-local here."
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [seon.db :as db]
            [seon.schema.edn :as schema.edn])
  (:import [java.nio.file Files Paths]
           [java.util HashMap]
           [java.util.concurrent.locks ReentrantLock]
           [org.apache.lucene.analysis.standard StandardAnalyzer]
           [org.apache.lucene.document Document Field$Store StoredField
            StringField TextField]
           [org.apache.lucene.index DirectoryReader IndexCommit IndexWriter
            IndexWriterConfig Term]
           [org.apache.lucene.search BooleanClause$Occur BooleanQuery$Builder
            IndexSearcher PrefixQuery ScoreDoc TermQuery TopDocs WildcardQuery]
           [org.apache.lucene.search SearcherManager]
           [org.apache.lucene.queryparser.classic QueryParser]
           [org.apache.lucene.store Directory FSDirectory]))

(set! *warn-on-reflection* true)

(schema.edn/load! {})

(defonce ^:private owners
  (atom {:seon.search/by-id {}
         :seon.search/by-connection {}}))

(defn- owner-by-id
  [index-id]
  (get-in @owners [:seon.search/by-id index-id]))

(defn tokens
  "Split a keyword, symbol, or string on its natural non-alphanumeric
  separators. This pure normalization is shared by search and schema reuse."
  {:malli/schema [:=> [:cat [:or :keyword :symbol :string]] [:vector :string]]}
  [value]
  (let [flush-token
        (fn [{:keys [parts token]}]
          {:parts (cond-> parts (seq token) (conj token))
           :token ""})]
    (:parts
     (flush-token
      (reduce
       (fn [{:keys [token] :as state} character]
         (if (Character/isLetterOrDigit ^char character)
           (assoc state :token
                  (str token (Character/toLowerCase ^char character)))
           (flush-token state)))
       {:parts [] :token ""}
       (str value))))))

(defn similar-identities
  "Rank qualified schema keys by shared natural name tokens. At least one
  local-name token must overlap, so a common namespace alone stays silent."
  {:malli/schema
   [:=> [:cat :qualified-keyword [:sequential :qualified-keyword]
         [:int {:min 1}]]
    [:vector [:map [:seon.schema.admission/similar-key :qualified-keyword]
              [:seon.schema.admission/shared-tokens [:int {:min 1}]]]]]}
  [candidate existing limit]
  (let [candidate-all (set (tokens candidate))
        candidate-local (set (tokens (name candidate)))]
    (->> existing
         (keep
          (fn [existing-key]
            (let [local-shared
                  (count (filter candidate-local (tokens (name existing-key))))
                  shared (count (filter candidate-all (set (tokens existing-key))))]
              (when (pos? local-shared)
                {:seon.schema.admission/similar-key existing-key
                 :seon.schema.admission/shared-tokens shared}))))
         (sort-by (juxt (comp - :seon.schema.admission/shared-tokens)
                        (comp str :seon.schema.admission/similar-key)))
         (take limit)
         vec)))

(def ^:private document-specs-query
  '[:find ?field ?mode
    :where
    [?schema :seon.schema/key ?field]
    [?schema :seon.search/index ?mode]])

(def ^:private identity-attributes-query
  '[:find [?identity-attribute ...]
    :where
    [?schema :seon.schema/key ?identity-attribute]
    [?schema :seon.db/identity true]])

(defn- document-specs
  [database]
  (->> (db/q document-specs-query database)
       (map (fn [[field mode]]
              {:seon.search/field field
               :seon.search/index mode}))
       (sort-by (juxt (comp str :seon.search/field)
                      :seon.search/index))
       vec))

(defn- search-roster
  [database]
  {:seon.search/document-specs (document-specs database)
   :seon.search/identity-attributes
   (->> (db/q identity-attributes-query database)
        (sort-by str)
        vec)})

(defn- identity-namespace
  [identity-value]
  (cond
    (keyword? identity-value)
    (namespace identity-value)

    (symbol? identity-value)
    (or (namespace identity-value) (str identity-value))

    (string? identity-value)
    (try
      (namespace (symbol identity-value))
      (catch Throwable _ nil))

    :else nil))

(defn- document
  [family eid field mode identity-value namespace-name value]
  (let [text (str value)
        indexed-text (case mode
                       :symbol (str/join " " (tokens value))
                       :text text)
        ^String namespace-text (str (or namespace-name ""))
        doc-id (str family "|" eid "|" field)
        result (Document.)]
    (.add result (StringField. "doc-id" doc-id Field$Store/YES))
    (.add result (StringField. "entity-id" (str eid) Field$Store/YES))
    (.add result (StringField. "family" (str family) Field$Store/YES))
    (.add result (StringField. "namespace" namespace-text
                               Field$Store/YES))
    (.add result (StringField. "field" (str field) Field$Store/YES))
    (.add result (StoredField. "identity" (pr-str identity-value)))
    (.add result (StoredField. "text-value" text))
    (.add result (TextField. "text" indexed-text Field$Store/NO))
    (.add result (StringField. "normalized" (str/join " " (tokens text))
                               Field$Store/NO))
    result))

(defn- entity-documents
  [database roster eid]
  (let [row (db/pull database '[*] eid)]
    (into
     []
     (mapcat
      (fn [family]
        (when-let [identity-value (get row family)]
          (let [namespace-name (identity-namespace identity-value)]
            (keep
             (fn [{field :seon.search/field
                   mode :seon.search/index}]
               (when-let [value (get row field)]
                 (document family eid field mode identity-value
                           namespace-name value)))
             (:seon.search/document-specs roster))))))
     (:seon.search/identity-attributes roster))))

(defn- declared-entity-ids
  [database roster]
  (into
   #{}
   (mapcat
    (fn [{field :seon.search/field}]
      (db/q '[:find [?e ...]
              :in $ ?field
              :where [?e ?field _]]
            database field)))
   (:seon.search/document-specs roster)))

(defn- set-basis!
  [owner basis-t]
  (let [metadata (doto (HashMap.)
                   (.put "seon.database/basis-t" (str basis-t)))]
    (.setLiveCommitData ^IndexWriter (:writer owner) (.entrySet metadata))
    (.commit ^IndexWriter (:writer owner))
    (.maybeRefreshBlocking ^SearcherManager (:searchers owner))
    (reset! (:basis owner) (long basis-t))))

(defn- rebuild!
  [owner database]
  (let [writer ^IndexWriter (:writer owner)
        roster (search-roster database)]
    (.lock ^ReentrantLock (:lock owner))
    (try
      (.deleteAll writer)
      (doseq [eid (declared-entity-ids database roster)
              doc (entity-documents database roster eid)]
        (.addDocument writer ^Iterable doc))
      (set-basis! owner (:max-tx database))
      (finally
        (.unlock ^ReentrantLock (:lock owner)))))
  owner)

(defn- roster-attributes
  [roster]
  (into
   (set (:seon.search/identity-attributes roster))
   (map :seon.search/field)
   (:seon.search/document-specs roster)))

(defn apply-report!
  "Advance one derived index by one exact transaction report. A coalesced
  or otherwise non-contiguous report rebuilds from `db-after`."
  {:malli/schema
   [:=> [:cat :seon.search/index-id :map] :nil]}
  [index-id report]
  (let [owner (owner-by-id index-id)]
    (when-not owner
      (throw (ex-info "The derived search index id is not open."
                      {:seon.search/index-id index-id})))
    (let [before (:db-before report)
          after (:db-after report)
          datoms (:tx-data report)
          before-roster (search-roster before)
          after-roster (search-roster after)
          relevant-attributes (roster-attributes after-roster)]
      (if (or (not= @(:basis owner) (long (:max-tx before)))
              (not= before-roster after-roster))
        (rebuild! owner after)
        (do
          (.lock ^ReentrantLock (:lock owner))
          (try
            (let [writer ^IndexWriter (:writer owner)]
              (doseq [eid (into #{}
                                (comp
                                 (filter
                                  #(contains? relevant-attributes (nth % 1)))
                                 (map first))
                                datoms)]
                (let [^"[Lorg.apache.lucene.index.Term;" terms
                      (into-array Term [(Term. "entity-id" (str eid))])]
                  (.deleteDocuments writer terms))
                (doseq [doc (entity-documents after after-roster eid)]
                  (.addDocument writer ^Iterable doc)))
              (set-basis! owner (:max-tx after)))
            (finally
              (.unlock ^ReentrantLock (:lock owner))))))))
  nil)

(defn- existing-basis
  [^Directory directory]
  (when (DirectoryReader/indexExists directory)
    (with-open [reader (DirectoryReader/open directory)]
      (some-> ^IndexCommit (.getIndexCommit reader)
              .getUserData
              (get "seon.database/basis-t")
              parse-long))))

(declare close!)

(defn open!
  "Open the one derived index for `connection`, rebuilding from its current
  database value unless the on-disk commit records that exact basis."
  {:malli/schema
   [:=> [:cat :seon.db/connection :seon.search/path]
    :seon.search/index-id]}
  [connection path]
  (let [index-id path]
    (when (get-in @owners [:seon.search/by-connection connection])
      (throw (ex-info "The cluster already has a search index owner."
                      {:seon.search/index-id index-id})))
    (let [index-path (Paths/get path (make-array String 0))
          _ (Files/createDirectories index-path (make-array java.nio.file.attribute.FileAttribute 0))
          directory (FSDirectory/open index-path)
          disk-basis (existing-basis directory)
          analyzer (StandardAnalyzer.)
          writer (IndexWriter. directory (IndexWriterConfig. analyzer))
          searchers (SearcherManager. writer nil)
          owner {:id index-id
                 :connection connection
                 :path path
                 :directory directory
                 :analyzer analyzer
                 :writer writer
                 :searchers searchers
                 :basis (atom disk-basis)
                 :lock (ReentrantLock.)
                 :closed? (atom false)}
          database @connection]
      (swap! owners
             (fn [current]
               (-> current
                   (assoc-in [:seon.search/by-id index-id] owner)
                   (assoc-in [:seon.search/by-connection connection] index-id))))
      (try
        (when (not= disk-basis (long (:max-tx database)))
          (rebuild! owner database))
        index-id
        (catch Throwable failure
          (close! index-id)
          (throw failure))))))

(defn close!
  "Close and forget one process-local index owner. Idempotent."
  {:malli/schema [:=> [:cat :seon.search/index-id] :nil]}
  [index-id]
  (when-let [owner (owner-by-id index-id)]
    (when (compare-and-set! (:closed? owner) false true)
      (swap! owners
             (fn [current]
               (-> current
                   (update :seon.search/by-id dissoc index-id)
                   (update :seon.search/by-connection dissoc
                           (:connection owner)))))
      (.close ^SearcherManager (:searchers owner))
      (.close ^IndexWriter (:writer owner))
      (.close ^StandardAnalyzer (:analyzer owner))
      (.close ^Directory (:directory owner))))
  nil)

(defn- family-query
  [families]
  (let [builder (BooleanQuery$Builder.)]
    (doseq [family (sort families)]
      (.add builder (TermQuery. (Term. "family" (str family)))
            BooleanClause$Occur/SHOULD))
    (.setMinimumNumberShouldMatch builder 1)
    (.build builder)))

(defn- namespace-query
  [namespace-prefix]
  (let [prefix (str namespace-prefix)
        builder (BooleanQuery$Builder.)]
    (.add builder (TermQuery. (Term. "namespace" prefix))
          BooleanClause$Occur/SHOULD)
    (.add builder (PrefixQuery. (Term. "namespace" (str prefix ".")))
          BooleanClause$Occur/SHOULD)
    (.setMinimumNumberShouldMatch builder 1)
    (.build builder)))

(defn- text-query
  [query match]
  (case match
    :token
    (.parse (QueryParser. "text" (StandardAnalyzer.))
            (QueryParser/escape query))

    :substring
    (WildcardQuery. (Term. "normalized"
                           (str "*" (str/join " " (tokens query)) "*")))))

(defn- search-owner
  [owner {:seon.search/keys
          [query families namespace-prefix match limit]}]
  (let [normalized (str/trim query)]
    (if (or (empty? normalized) (empty? (tokens normalized)))
      {:seon.search/unavailable true
       :seon.error/message "Search query must contain a letter or digit."}
      (do
        (.lock ^ReentrantLock (:lock owner))
        (try
          (let [builder (BooleanQuery$Builder.)
                _ (.add builder (text-query normalized match)
                        BooleanClause$Occur/MUST)
                _ (.add builder (family-query families)
                        BooleanClause$Occur/FILTER)
                _ (when namespace-prefix
                    (.add builder (namespace-query namespace-prefix)
                          BooleanClause$Occur/FILTER))
                query-object (.build builder)
                searcher (.acquire ^SearcherManager (:searchers owner))]
            (try
              (let [^TopDocs hits (.search ^IndexSearcher searcher query-object
                                           (int (min 400 (* 4 limit))))
                    stored-fields (.storedFields ^IndexSearcher searcher)]
                {:seon.search/basis-t @(:basis owner)
                 :seon.search/results
                 (mapv
                  (fn [^ScoreDoc score-doc]
                    (let [stored (.document stored-fields (.-doc score-doc))
                          family (edn/read-string (.get stored "family"))
                          namespace-name (.get stored "namespace")]
                      (cond->
                       {:seon.search/family family
                        :seon.search/field
                        (edn/read-string (.get stored "field"))
                        :seon.search/identity
                        (edn/read-string (.get stored "identity"))
                        :seon.search/text (.get stored "text-value")
                        :seon.search/score (double (.-score score-doc))}
                        (seq namespace-name)
                        (assoc :seon.search/namespace-prefix
                               (symbol namespace-name)))))
                  (->> (.-scoreDocs hits)
                       (sort-by (juxt (comp - #(double (.-score ^ScoreDoc %)))
                                      #(.-doc ^ScoreDoc %)))
                       (take limit)))})
              (finally
                (.release ^SearcherManager (:searchers owner) searcher))))
          (finally
            (.unlock ^ReentrantLock (:lock owner))))))))

(defn search
  "Search declared database fields through the current cluster's index.

  The identity attribute is the fact family. Family and optional namespace
  scoping are query arguments; results are bounded ordinary data and failures
  are flat values."
  {:malli/schema [:=> [:cat :seon.search/request] :seon.search/response]}
  [request]
  (if-let [owner (some-> (get-in @owners
                                 [:seon.search/by-connection db/*conn*])
                         owner-by-id)]
    (try
      (search-owner owner request)
      (catch Throwable failure
        {:seon.search/unavailable true
         :seon.error/message (str "Search could not run: " (ex-message failure))}))
    {:seon.search/unavailable true
     :seon.error/message "Search is unavailable because this cluster has no derived index."}))

(defn index-step
  "Flow proc that advances the cluster index from transaction reports."
  ([] {:ins {::transactions "Datahike transaction reports"}
       :outs {}
       :workload :io
       :ping-map-fn (constantly {})})
  ([{:keys [:seon.search/index :seon.search/channel
            :seon.search/completion] :as state}]
   (when-not (and index channel completion)
     (throw (ex-info "The search index proc is missing a required resource."
                     {:seon.error/kind ::missing-resource})))
   (assoc state
          ::flow/in-ports {::transactions channel}
          ::flow/out-ports {}
          ::index-id index
          ::completion completion))
  ([state transition]
   (when (= ::flow/stop transition)
     (close! (::index-id state))
     (async/offer! (::completion state) ::stopped))
   state)
  ([state _ report]
   (apply-report! (::index-id state) report)
   [state nil]))
