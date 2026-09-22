(ns seon.search
  "One per-cluster Lucene index derived from declared database fields.

  The database is truth. The index records the database basis it reflects,
  rebuilds whenever that basis cannot be advanced exactly, and exposes only
  ordinary data through `search`. Lucene objects remain process-local here."
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [datahike.datom :as datom]
            [seon.db :as db]
            [seon.schema :as schema]
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

(defn- datahike-datom?
  [value]
  (datom/datom? value))

#_{:clj-kondo/ignore [:unused-private-var]}
(def ^:private datahike-datom-generator
  (gen/fmap
   (fn [[entity-id attribute value transaction added?]]
     (datom/datom entity-id attribute value transaction added?))
   (gen/tuple (gen/choose 1 1000000)
              gen/keyword-ns
              gen/any-printable
              (gen/choose 1 1000000)
              gen/boolean)))

(defn- ping-map-fn?
  [value]
  (fn? value))

#_{:clj-kondo/ignore [:unused-private-var]}
(def ^:private ping-map-fn-generator
  (gen/fmap
   (fn [[behavior projection-key value]]
     (case behavior
       :select (fn [state] (select-keys state [projection-key]))
       :constant (fn [_state] {projection-key value})
       :count (fn [state] {projection-key (count state)})))
   (gen/tuple (gen/elements [:select :constant :count])
              gen/keyword-ns
              gen/any-printable)))

(schema/register-core-predicate! 'seon.search/datahike-datom?
                                 datahike-datom?)
(schema/register-core-predicate! 'seon.search/ping-map-fn?
                                 ping-map-fn?)

(defrecord IndexHandle [])

(defonce ^:private handle-prototype (map->IndexHandle {}))

(defn- handle?
  ;; A registered core predicate, like `ping-map-fn?` and `datahike-datom?`
  ;; above: private and uncontracted, because a schema predicate runs inside
  ;; every validation of every schema that embeds this one.
  [value]
  (instance? (class handle-prototype) value))

(schema/register-core-predicate! 'seon.search/handle? handle?)

#_{:clj-kondo/ignore [:unused-private-var]}
(def ^:private handle-generator
  ;; A CLOSED handle holding no Lucene resources. A generator is sampled
  ;; wherever a schema embedding this one is sampled — the request, the
  ;; environment, the proc contract — so acquiring a real directory, writer
  ;; and SearcherManager per sample would make ordinary schema generation
  ;; open and commit hundreds of indexes. `close!` on one of these is the
  ;; no-op its own closed flag already declares.
  (gen/fmap
   (fn [_]
     (into handle-prototype
           {:seon.search/path ""
            :seon.search/basis (atom nil)
            :seon.search/lock (ReentrantLock.)
            :seon.search/closed? (atom true)}))
   (gen/return nil)))

(schema.edn/load! {})

(defn supplied-handle
  "Return this environment's index handle, or explain its absence.

  This is a declared call-preparation supplier: `search` declares
  `:seon.search/handle` on its request, so an agent that omits it receives the
  handle its own cluster's environment carries. A cluster whose index never
  opened refuses here, naming the member — never a silent empty result set.

  The declared return is the row's value shape plus `:seon.error/value`,
  exactly as `seon.call-preparation`'s coherence proof requires
  (`src/seon/call_preparation.clj:166`): a supplier declaring its own error
  family instead is not proved, and its row is dropped in silence — the call
  then refuses at the callee's contract for a missing key nobody filled."
  {:malli/schema [:=> [:cat :seon.env/environment]
                  [:or :seon.search/handle :seon.error/value]]}
  [environment]
  (or (:seon.search/handle environment)
      {:seon.error/at (java.util.Date.)
       :seon.error/layer :seon.search/environment
       :seon.error/operation 'seon.search/supplied-handle
       :seon.search/unavailable true
       :seon.error/message
       "This cluster's environment carries no search index handle."
       :seon.error/data {:seon.env/member :seon.search/handle}}))

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

    ;; A string identity value is a symbol not yet stored as one (AGENTS §3);
    ;; `symbol` validates nothing, so reading its namespace cannot throw and
    ;; the absent namespace is already nil.
    (string? identity-value)
    (namespace (symbol identity-value))

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
    (.setLiveCommitData ^IndexWriter (:seon.search/writer owner) (.entrySet metadata))
    (.commit ^IndexWriter (:seon.search/writer owner))
    (.maybeRefreshBlocking ^SearcherManager (:seon.search/searchers owner))
    (reset! (:seon.search/basis owner) (long basis-t))))

(defn- rebuild!
  [owner database]
  (let [writer ^IndexWriter (:seon.search/writer owner)
        roster (search-roster database)]
    (.lock ^ReentrantLock (:seon.search/lock owner))
    (try
      (.deleteAll writer)
      (doseq [eid (declared-entity-ids database roster)
              doc (entity-documents database roster eid)]
        (.addDocument writer ^Iterable doc))
      (set-basis! owner (db/basis-t database))
      (finally
        (.unlock ^ReentrantLock (:seon.search/lock owner)))))
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
   [:=> [:cat :seon.search/handle :map] :nil]}
  [owner report]
  (let [before (:db-before report)
          after (:db-after report)
          datoms (:tx-data report)
          before-roster (search-roster before)
          after-roster (search-roster after)
          relevant-attributes (roster-attributes after-roster)]
      (if (or (not= @(:seon.search/basis owner) (db/basis-t before))
              (not= before-roster after-roster))
        (rebuild! owner after)
        (do
          (.lock ^ReentrantLock (:seon.search/lock owner))
          (try
            (let [writer ^IndexWriter (:seon.search/writer owner)]
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
              (set-basis! owner (db/basis-t after)))
            (finally
              (.unlock ^ReentrantLock (:seon.search/lock owner)))))))
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
  "Open a cluster-owned Lucene handle, rebuilding unless its disk basis matches."
  {:malli/schema
   [:=> [:cat :seon.db/connection :seon.search/path] :seon.search/handle]}
  [connection path]
  (let [index-path (Paths/get path (make-array String 0))
        _ (Files/createDirectories index-path (make-array java.nio.file.attribute.FileAttribute 0))
        directory (FSDirectory/open index-path)]
    (try
      (let [disk-basis (existing-basis directory)
            analyzer (StandardAnalyzer.)]
        (try
          (let [writer (IndexWriter. directory (IndexWriterConfig. analyzer))]
            (try
              (let [searchers (SearcherManager. writer nil)]
                (try
                  (let [owner (into handle-prototype
                                    {:seon.search/path path
                                     :seon.search/directory directory
                                     :seon.search/analyzer analyzer
                                     :seon.search/writer writer
                                     :seon.search/searchers searchers
                                     :seon.search/basis (atom disk-basis)
                                     :seon.search/lock (ReentrantLock.)
                                     :seon.search/closed? (atom false)})
                        database @connection]
                    (when (not= disk-basis (db/basis-t database))
                      (rebuild! owner database))
                    owner)
                  (catch Throwable failure (.close searchers) (throw failure))))
              (catch Throwable failure (.close writer) (throw failure))))
          (catch Throwable failure (.close analyzer) (throw failure))))
      (catch Throwable failure (.close directory) (throw failure)))))

(defn close!
  "Close one carried index handle. Idempotent; no registry lookup is involved."
  {:malli/schema [:=> [:cat :seon.search/handle] :nil]}
  [owner]
  (when (compare-and-set! (:seon.search/closed? owner) false true)
    (try
      (.close ^SearcherManager (:seon.search/searchers owner))
      (finally
        (try
          (.close ^IndexWriter (:seon.search/writer owner))
          (finally
            (try
              (.close ^StandardAnalyzer (:seon.search/analyzer owner))
              (finally (.close ^Directory (:seon.search/directory owner)))))))))
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
        (.lock ^ReentrantLock (:seon.search/lock owner))
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
                searcher (.acquire ^SearcherManager (:seon.search/searchers owner))]
            (try
              (let [^TopDocs hits (.search ^IndexSearcher searcher query-object
                                           (int (min 400 (* 4 limit))))
                    stored-fields (.storedFields ^IndexSearcher searcher)]
                {:seon.search/basis-t @(:seon.search/basis owner)
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
                (.release ^SearcherManager (:seon.search/searchers owner) searcher))))
          (finally
            (.unlock ^ReentrantLock (:seon.search/lock owner))))))))

(defn search
  "Search declared database fields through the current cluster's index.

  The identity attribute is the fact family. Family and optional namespace
  scoping are query arguments; results are bounded ordinary data and failures
  are flat values."
  {:malli/schema [:=> [:cat :seon.search/request] :seon.search/response]}
  [request]
  (if-let [owner (:seon.search/handle request)]
    (try
      (search-owner owner request)
      (catch Throwable failure
        {:seon.search/unavailable true
         :seon.error/message (str "Search could not run: " (ex-message failure))}))
    {:seon.search/unavailable true
     :seon.error/message "Search is unavailable because this cluster has no derived index."}))

(defn index-step
  "Flow proc that advances the cluster index from transaction reports."
  {:malli/schema
   [:function
    [:=>
     [:cat]
     [:map
      [:ins [:map [::transactions :string]]]
      [:outs [:map]]
      [:workload [:= :io]]
      [:ping-map-fn
       [:fn
        {:error/message "must project the search proc state to a ping map"
         :gen/gen 'seon.search/ping-map-fn-generator}
        'seon.search/ping-map-fn?]]]]
    [:=>
     [:catn
      [::request
       [:map
        [:seon.search/handle :seon.search/handle]
        [:seon.search/channel :seon.flow/channel]
        [:seon.search/completion :seon.flow/channel]]]]
     [:map
      [::handle :seon.search/handle]
      [::completion :seon.flow/channel]]]
    [:=>
     [:catn
      [::state
       [:map
        [::handle :seon.search/handle]
        [::completion :seon.flow/channel]]]
      [::transition [:enum ::flow/resume ::flow/pause ::flow/stop]]]
     [:map
      [::handle :seon.search/handle]
      [::completion :seon.flow/channel]]]
    [:=>
     [:catn
      [::state
       [:map
        [::handle :seon.search/handle]
        [::completion :seon.flow/channel]]]
      [::input [:= ::transactions]]
      [::report
       [:map
        [:db-before :seon.db/database-value]
        [:db-after :seon.db/database-value]
        [:tx-data
         [:vector
          [:fn
           {:error/message "must be a raw Datahike transaction datom"
            :gen/gen 'seon.search/datahike-datom-generator}
           'seon.search/datahike-datom?]]]]]]
     [:tuple
      [:map
       [::handle :seon.search/handle]
       [::completion :seon.flow/channel]]
      :nil]]]}
  ([] {:ins {::transactions "Datahike transaction reports"}
       :outs {}
       :workload :io
       :ping-map-fn (constantly {})})
  ([{:keys [:seon.search/handle :seon.search/channel
            :seon.search/completion] :as state}]
   (when-not (and handle channel completion)
     (throw (ex-info "The search index proc is missing a required resource."
                     {:seon.search/missing-resource true})))
   (assoc state
          ::flow/in-ports {::transactions channel}
          ::flow/out-ports {}
          ::handle handle
          ::completion completion))
  ([state transition]
   (when (= ::flow/stop transition)
     (close! (::handle state))
     (async/offer! (::completion state) ::stopped))
   state)
  ([state _ report]
   (apply-report! (::handle state) report)
   [state nil]))
