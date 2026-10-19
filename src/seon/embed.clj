(ns seon.embed
  "Embeddings of text, keyed by the content they embed, and the texts nearest a
  query by them. `seon.cache` is the hot layer, packed segments in a konserve
  store beside the cluster's Datahike store the durable one (it survives restart
  and reset), and the embed server is reached only for text neither holds."
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [datahike.api :as d]
            [konserve.core :as k]
            [konserve.filestore :as filestore]
            [seon.cache]
            [seon.config :as config]
            [seon.db :as db]
            [seon.error.refusal :as error]
            [seon.schema :as schema])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse
            HttpResponse$BodyHandlers]
           [java.nio ByteBuffer ByteOrder FloatBuffer]
           [java.nio.charset StandardCharsets]
           [java.time Duration]
           [java.util Arrays Base64 HexFormat PriorityQueue]))

(set! *warn-on-reflection* true)

(defonce ^{:private true :tag HttpClient
           :doc "The process's one client of the embed server: it owns the connection pool."}
  client
  (.build (HttpClient/newBuilder)))

(defn embedding-key
  "The content identity of `text` embedded by `settings`' model at its dimension for `task`."
  {:malli/schema [:=> [:cat :seon.embed/settings :seon.embed/task :seon.embed/text] :seon.embed/key]}
  [{:seon.config.embed/keys [model dimension]} task text]
  (schema/sha-256 (mapv #(.getBytes (str %) StandardCharsets/UTF_8)
                        [model "\u0000" task "\u0000" dimension "\u0000" text])))

(defn request!
  "The embed server's embeddings of `texts` for `task`, in order, each as
  little-endian float32 octets, or the unreachable error naming its endpoint:
  the one interop edge. Task \"none\" sends no task: the text carries its own
  prefix (`title: T | text: C`)."
  {:malli/schema [:=> [:cat :seon.embed/settings :seon.embed/task [:vector :seon.embed/text]]
                  [:or [:vector :seon.embed/octets] :seon.embed/unreachable-error]]}
  [{:seon.config.embed/keys [endpoint dimension timeout-ms]} task texts]
  (let [unreachable (fn [reason cause]
                      (error/error :seon.embed/unreachable-error
                                   (cond-> {:seon.error/operation 'seon.embed/request!
                                            :seon.error/message "The embed server did not answer with embeddings."
                                            :seon.error/fix "Start the embed server: bin/seon start runs it beside the cluster."
                                            :seon.config.embed/endpoint endpoint
                                            :seon.embed/reason reason}
                                     cause (assoc :seon.error/throwable cause))))]
    (try
      (let [body (json/write-str {:texts texts :task (when-not (= "none" task) task) :dim dimension :encoding "f32"})
            http (-> (HttpRequest/newBuilder (URI/create endpoint))
                     (.timeout (Duration/ofMillis (long timeout-ms)))
                     (.header "content-type" "application/json")
                     (.POST (HttpRequest$BodyPublishers/ofString body))
                     .build)
            ^HttpResponse response (.send client http (HttpResponse$BodyHandlers/ofString))]
        (if (= 200 (.statusCode response))
          (let [^bytes octets (.decode (Base64/getDecoder) ^String (get (json/read-str (.body response)) "vectors"))
                width (* 4 (long dimension))]
            (mapv #(Arrays/copyOfRange octets (int (* % width)) (int (* (inc %) width))) (range (count texts))))
          (unreachable (str "HTTP " (.statusCode response)) nil)))
      (catch java.io.IOException cause
        (unreachable (or (ex-message cause) (.getName (class cause))) cause)))))

(defn directory
  "The durable embedding store's directory for `database`: `embeddings` beside
  its file store, so a reset keeps it and a nuke deletes it; an in-memory
  store's under the temporary directory, by its store id."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:string {:min 1}]]}
  [database]
  (let [{:keys [path id]} (get-in database [:config :store])]
    (str (if path
           (io/file (.getParentFile (io/file ^String path)) "embeddings")
           (io/file (System/getProperty "java.io.tmpdir") (str "seon-embeddings-" id))))))

;;; The durable store: packed segments, one per fill pass. A segment is one immutable binary value under
;;; `[:seon.embed/segment id]`, its id the SHA-256 of its bytes: a little-endian int32 count n and int32 width w,
;;; then n 32-byte digests (the content keys), then n embeddings of w octets in the same order. The manifest
;;; `:seon.embed/segments` lists the segment ids in write order.

(def ^:private digest-width
  "Bytes per content key in a segment: a SHA-256 digest."
  32)

(defn segment
  "The packed segment of the embeddings `found` holds by content key, in key order."
  {:malli/schema [:=> [:cat :seon.embed/found] :seon.embed/segment]}
  [found]
  (let [rows (sort-by key found)
        width (alength ^bytes (val (first rows)))
        buffer (.order (ByteBuffer/allocate (+ 8 (* (count rows) (+ digest-width width)))) ByteOrder/LITTLE_ENDIAN)]
    (.putInt buffer (count rows))
    (.putInt buffer width)
    (run! #(.put buffer (.parseHex (HexFormat/of) ^String (key %))) rows)
    (run! #(.put buffer ^bytes (val %)) rows)
    (.array buffer)))

(defn- segment-rows
  "Each content key segment `id` of `store` holds, with its segment, row and width: read from its header alone."
  {:malli/schema [:=> [:cat :seon.embed/store :seon.embed/segment-id] [:map-of :seon.embed/key :seon.embed/row]]}
  [store id]
  (let [head (.order (ByteBuffer/wrap (k/bget-range store [::segment id] 0 8 {:sync? true})) ByteOrder/LITTLE_ENDIAN)
        n (.getInt head)
        width (.getInt head)
        ^bytes digests (k/bget-range store [::segment id] 8 (* n digest-width) {:sync? true})
        hex (HexFormat/of)]
    (into {} (map (fn [row]
                    [(.formatHex hex digests (int (* row digest-width)) (int (* (inc row) digest-width)))
                     {::segment id ::row row ::count n ::width width}]))
          (range n))))

(defn- segment-ids
  "The ids of `store`'s segments, in write order."
  {:malli/schema [:=> [:cat :seon.embed/store] [:vector :seon.embed/segment-id]]}
  [store]
  (k/get store ::segments [] {:sync? true}))

(defn present
  "Where each embedding `store` holds lives, by content key: derived once per manifest, from the previous
  manifest's answer and the newest segment's header when that answer is held."
  {:malli/schema [:=> [:cat :seon.embed/store] [:map-of :seon.embed/key :seon.embed/row]]}
  [store]
  (let [ids (segment-ids store)]
    (seon.cache/lookup ::present ids #(+ 64 (* 192 (count %)))
                       (fn [] (if-let [before (and (seq ids) (seon.cache/present ::present (pop ids)))]
                                (merge before (segment-rows store (peek ids)))
                                (transduce (map #(segment-rows store %)) merge {} ids))))))

(defn- stored-octets
  "The octets of each of `rows` (content key to where it lives in `store`): one range read per segment,
  spanning that segment's lowest to highest wanted row."
  {:malli/schema [:=> [:cat :seon.embed/store [:map-of :seon.embed/key :seon.embed/row]]
                  :seon.embed/found]}
  [store rows]
  (into {} (mapcat (fn [[id wanted]]
                     (let [{n ::count width ::width} (val (first wanted))
                           low (transduce (map (comp ::row val)) min Long/MAX_VALUE wanted)
                           high (transduce (map (comp ::row val)) max -1 wanted)
                           start (+ 8 (* n digest-width) (* low width))
                           ^bytes span (k/bget-range store [::segment id] start (* (inc (- high low)) width) {:sync? true})]
                       (map (fn [[content-key {::keys [row]}]]
                              (let [from (* (- row low) width)]
                                [content-key (Arrays/copyOfRange span (int from) (int (+ from width)))]))
                            wanted))))
        (group-by (comp ::segment val) rows)))

(defn write-segment!
  "Write the segment of `found` to `store` and append its id to the manifest; the id."
  {:malli/schema [:=> [:cat :seon.embed/store :seon.embed/found] :seon.embed/segment-id]}
  [store found]
  (let [packed (segment found)
        id (schema/sha-256 [packed])]
    (k/bassoc store [::segment id] packed {:immutable? true} {:sync? true})
    (k/update store ::segments #(conj (or % []) id) {:sync? true})
    id))

(defn convert!
  "Pack the embeddings `store` holds in the per-key layout (one content key per stored value) that no segment holds
  into one segment, written durably, then delete every per-key value; how many it packed. A conversion cut short
  resumes: what its durable segment already holds is not packed again."
  {:malli/schema [:=> [:cat :seon.embed/store] nat-int?]}
  [store]
  (let [legacy (into [] (comp (map :key) (filter string?)) (k/keys store {:sync? true}))
        held (present store)
        missing (into [] (remove #(contains? held %)) legacy)]
    (when (seq missing)
      ;; durable before a per-key value is deleted: this write syncs, though the store's own writes skip it
      (write-segment! (assoc-in store [:config :sync-blob?] true)
                      (into {} (map (fn [content-key] [content-key (k/get store content-key nil {:sync? true})]))
                            missing)))
    (run! #(k/dissoc store % {:sync? true}) legacy)
    (count missing)))

(defn- opened
  "The durable embedding store in directory `dir`, created when absent and converted from the per-key layout
  ([[convert!]]). Its writes skip the fsync: a segment a crash loses costs one re-embed of its texts."
  {:malli/schema [:=> [:cat [:string {:min 1}]] :seon.embed/store]}
  [dir]
  (doto (filestore/connect-fs-store dir :opts {:sync? true} :config {:sync-blob? false})
    convert!))

(defn open-store
  "The durable embedding store in directory `dir` ([[opened]]), opened once per process: the background fill's
  call ([[fill!]]), which converts a per-key store at boot, and a waiting caller's, which joins it."
  {:malli/schema [:=> [:cat [:string {:min 1}]] :seon.embed/store]}
  [dir]
  (seon.cache/lookup ::store dir (constantly 1024) #(opened dir)))

(defn store-within
  "[[open-store]] that waits at most `wait-ms`: in progress past it, while the store opens on its own thread (or
  the background fill's), so a call never converts the store itself."
  {:malli/schema [:=> [:cat [:string {:min 1}] nat-int?] [:or :seon.embed/store :seon.cache/pending]]}
  [dir wait-ms]
  (seon.cache/lookup-within ::store dir (constantly 1024) #(opened dir) some? wait-ms nil))

(defn cluster-settings
  "The embed dials of the cluster `database` belongs to (`seon.config/effective`)."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:or :seon.embed/settings :seon.config/error]]}
  [database]
  (let [effective (config/effective database (db/q '[:find ?n . :where [_ :seon.config/cluster ?n]] database))]
    (if (:seon.error/operation effective)
      effective
      (select-keys effective [:seon.config.embed/endpoint :seon.config.embed/model :seon.config.embed/dimension
                              :seon.config.embed/request-bytes :seon.config.embed/timeout-ms :seon.config.embed/wait-ms]))))

(defn- hot!
  "Retain `octets` under `content-key` in the process cache, answering them."
  {:malli/schema [:=> [:cat :seon.embed/key :seon.embed/octets] :seon.embed/octets]}
  [content-key octets]
  (seon.cache/lookup ::embedding content-key #(+ 96 (alength ^bytes %)) (constantly octets)))

(defn requests
  "The content keys `missing` as embed-server requests: sorted by their text's length (`text-of`), then cut so
  each request's texts total at most `budget` UTF-8 bytes, a longer text alone."
  {:malli/schema [:=> [:cat [:map-of :seon.embed/key :seon.embed/text] pos-int? [:vector :seon.embed/key]]
                  [:vector [:vector :seon.embed/key]]]}
  [text-of budget missing]
  (let [size #(alength (.getBytes ^String (text-of %) StandardCharsets/UTF_8))
        [done last-batch _] (reduce (fn [[done batch total] content-key]
                                      (let [n (size content-key)]
                                        (if (and (seq batch) (< budget (+ total n)))
                                          [(conj done batch) [content-key] n]
                                          [done (conj batch content-key) (+ total n)])))
                                    [[] [] 0]
                                    (sort-by size missing))]
    (cond-> done (seq last-batch) (conj last-batch))))

(defn- requested
  "The embeddings `request` answers for `missing` (content keys, `text-of` naming each one's text), in
  `settings`' byte-budget requests, and the unreachable error of the first request that answered none."
  {:malli/schema [:=> [:cat :seon.embed/settings :seon.embed/request :seon.embed/task
                       [:map-of :seon.embed/key :seon.embed/text] [:vector :seon.embed/key]]
                  [:map [::found :seon.embed/found]
                   [::error {:optional true} :seon.embed/unreachable-error]]]}
  [settings request task text-of missing]
  (reduce (fn [answered batch]
            (let [answer (request settings task (mapv text-of batch))]
              (if (error/error? answer)
                (reduced (assoc answered ::error answer))
                (update answered ::found into (map vector batch answer)))))
          {::found {}}
          (requests text-of (:seon.config.embed/request-bytes settings) missing)))

(defn- layered
  "The embeddings of `texts` for `task` by content key, with how many came from each layer: the process cache,
  then `store`'s segments, then `request` for the rest, which are retained in the process cache."
  {:malli/schema [:=> [:cat :seon.embed/settings :seon.embed/store :seon.embed/request
                       :seon.embed/task [:vector :seon.embed/text]]
                  :seon.embed/layers]}
  [settings store request task texts]
  (let [content-keys (mapv #(embedding-key settings task %) texts)
        distinct-keys (distinct content-keys)
        hot (into {} (keep (fn [content-key]
                             (when-let [octets (seon.cache/present ::embedding content-key)] [content-key octets])))
                  distinct-keys)
        durable-octets (stored-octets store (select-keys (present store) (remove hot distinct-keys)))
        _ (run! (fn [[content-key octets]] (hot! content-key octets)) durable-octets)
        missing (into [] (comp (remove hot) (remove durable-octets)) distinct-keys)
        {::keys [found error]} (requested settings request task (zipmap content-keys texts) missing)]
    (run! (fn [[content-key octets]] (hot! content-key octets)) found)
    (cond-> {::content-keys content-keys ::from-cache hot ::from-store durable-octets ::from-server found}
      error (assoc ::error error))))

(defn- answer
  "What [[layered]] found as the embeddings of its texts in order with their layer counts, or its error."
  {:malli/schema [:=> [:cat :seon.embed/layers] [:or :seon.embed/embedded :seon.embed/unreachable-error]]}
  [{::keys [content-keys from-cache from-store from-server error]}]
  (if error
    error
    (let [found (merge from-cache from-store from-server)]
      {:seon.embed/vectors (mapv found content-keys)
       :seon.embed/hot (count from-cache)
       :seon.embed/durable (count from-store)
       :seon.embed/requested (count from-server)})))

(defn embed!
  "The embeddings of `texts` for `task`, in order, with how many came from each layer: the process cache first,
  then `store`'s segments, then `settings`' byte-budget requests for the rest, written to `store` as one segment
  and to the cache. Text repeated within `texts` is requested once. The unreachable error when the embed server
  is needed and does not answer; what it answered before is still written."
  {:malli/schema [:=> [:cat :seon.embed/settings :seon.embed/store :seon.embed/request
                       :seon.embed/task [:vector :seon.embed/text]]
                  [:or :seon.embed/embedded :seon.embed/unreachable-error]]}
  [settings store request task texts]
  (let [found (layered settings store request task texts)]
    (when (seq (::from-server found)) (write-segment! store (::from-server found)))
    (answer found)))

(defn- embed-query!
  "[[embed!]] of one query text, never written to `store`: a query embedding stays in the process cache."
  {:malli/schema [:=> [:cat :seon.embed/settings :seon.embed/store :seon.embed/request :seon.embed/task :seon.embed/text]
                  [:or :seon.embed/embedded :seon.embed/unreachable-error]]}
  [settings store request task text]
  (answer (layered settings store request task [text])))

(defn- stacked
  "The index of `texts` from their embeddings: row i the embedding of text i."
  {:malli/schema [:=> [:cat [:vector :seon.embed/text] :seon.embed/embedded] :seon.embed/index]}
  [texts {:seon.embed/keys [vectors]}]
  (let [out (java.io.ByteArrayOutputStream. (int (transduce (map #(alength ^bytes %)) + 0 vectors)))]
    (run! #(.write out ^bytes %) vectors)
    {:seon.embed/texts texts :seon.embed/matrix (.toByteArray out)}))

(defn- index-key
  "The content identity of the index of `texts` embedded for `task` by `settings`' model at its dimension."
  {:malli/schema [:=> [:cat :seon.embed/settings :seon.embed/task [:vector :seon.embed/text]] :seon.embed/key]}
  [settings task texts]
  (schema/sha-256 (into [(.getBytes (str (:seon.config.embed/model settings) "\u0000" task "\u0000"
                                         (:seon.config.embed/dimension settings) "\u0000")
                                    StandardCharsets/UTF_8)]
                        (map #(.getBytes (str % "\u0000") StandardCharsets/UTF_8))
                        texts)))

(defn- index-weight
  "The bytes an index of `texts` holds in the process cache."
  {:malli/schema [:=> [:cat :seon.embed/index] nat-int?]}
  [held]
  (+ 256 (alength ^bytes (:seon.embed/matrix held)) (* 64 (count (:seon.embed/texts held)))))

(defn index
  "The embeddings of `texts` for `task` as one matrix ([[stacked]]), derived
  once per content (model, dimension, task and texts) and held in the process
  cache; the unreachable error when an embedding is missing and the server
  does not answer. Waits for the derivation: the background fill's call."
  {:malli/schema [:=> [:cat :seon.embed/settings :seon.embed/store :seon.embed/request
                       :seon.embed/task [:vector :seon.embed/text]]
                  [:or :seon.embed/index :seon.embed/unreachable-error]]}
  [settings store request task texts]
  (seon.cache/lookup ::index (index-key settings task texts) index-weight
                     (fn [] (let [embedded (embed! settings store request task texts)]
                              (if (error/error? embedded) embedded (stacked texts embedded))))
                     #(not (error/error? %))))

(defn- float-view
  "`octets` read as little-endian float32."
  {:malli/schema [:=> [:cat :seon.embed/octets] :seon.schema/value]}
  ^FloatBuffer [^bytes octets]
  (.asFloatBuffer (.order (ByteBuffer/wrap octets) ByteOrder/LITTLE_ENDIAN)))

(defn nearest
  "The `k` texts of `index` nearest `query` (an embedding's octets), nearest
  first, each with its cosine similarity: one pass over the matrix, the
  embeddings being unit length."
  {:malli/schema [:=> [:cat :seon.embed/index :seon.embed/octets pos-int?] :seon.embed/neighbours]}
  [{:seon.embed/keys [texts matrix]} query k]
  (let [^FloatBuffer q (float-view query)
        ^FloatBuffer m (float-view matrix)
        dimension (.remaining q)
        ;; the k best so far, the worst at the head
        ^PriorityQueue best (PriorityQueue. (int (inc k)) (fn [[a] [b]] (compare a b)))]
    (dotimes [row (count texts)]
      (let [offset (* row dimension)
            score (loop [i 0 sum 0.0]
                    (if (< i dimension)
                      (recur (inc i) (+ sum (* (.get q (int i)) (.get m (int (+ offset i))))))
                      sum))]
        (.add best [score row])
        (when (< k (.size best)) (.poll best))))
    (->> (repeatedly (.size best) #(.poll best))
         reverse
         (mapv (fn [[score row]]
                 {:seon.embed/text (nth texts row)
                  :seon.embed/score (/ (Math/round (* 1000.0 (double score))) 1000.0)})))))

(defn similar
  "The `k` of `texts` nearest `query` when `texts` are embedded for `task` and
  `query` for `query-task`, nearest first with their scores, or the
  unreachable error. Waits for the index of `texts`: a reply that must rank
  (an unresolved name's nearest calls), never an agent's own by-meaning call
  ([[similar-now]])."
  {:malli/schema [:=> [:cat :seon.embed/settings :seon.embed/store :seon.embed/request :seon.embed/task
                       [:vector :seon.embed/text] :seon.embed/task :seon.embed/text pos-int?]
                  [:or :seon.embed/neighbours :seon.embed/unreachable-error]]}
  [settings store request task texts query-task query k]
  (let [held (index settings store request task texts)
        asked (when-not (error/error? held) (embed-query! settings store request query-task query))]
    (cond (error/error? held) held
          (error/error? asked) asked
          :else (nearest held (first (:seon.embed/vectors asked)) k))))

(defn similar-now
  "[[similar]] that waits for the index of `texts` at most `settings`' wait:
  embedding in progress past it, while the index is derived on its own thread
  (`seon.cache/lookup-within`) or by the background fill ([[fill-step]]), so a
  later call finds it. The query is the one text it embeds itself."
  {:malli/schema [:=> [:cat :seon.embed/settings :seon.embed/store :seon.embed/request :seon.embed/task
                       [:vector :seon.embed/text] :seon.embed/task :seon.embed/text pos-int?]
                  [:or :seon.embed/answer :seon.embed/unreachable-error]]}
  [settings store request task texts query-task query k]
  (let [held (seon.cache/lookup-within ::index (index-key settings task texts) index-weight
                                       (fn [] (let [embedded (embed! settings store request task texts)]
                                                (if (error/error? embedded) embedded (stacked texts embedded))))
                                       #(not (error/error? %)) (:seon.config.embed/wait-ms settings) nil)
        pending? (some? (:seon.cache/pending-kind held))
        asked (when-not (or pending? (error/error? held)) (embed-query! settings store request query-task query))]
    (cond pending? {:seon.embed/pending (count texts)}
          (error/error? held) held
          (error/error? asked) asked
          :else (nearest held (first (:seon.embed/vectors asked)) k))))

(defn similar-in
  "[[similar]] with `database`'s cluster dials, its durable store and the embed server."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.embed/task [:vector :seon.embed/text] :seon.embed/task
                       :seon.embed/text pos-int?]
                  [:or :seon.embed/neighbours :seon.embed/unreachable-error :seon.config/error]]}
  [database task texts query-task query k]
  (let [dials (cluster-settings database)]
    (if (:seon.error/operation dials)
      dials
      (similar dials (open-store (directory database)) request! task texts query-task query k))))

(defn similar-now-in
  "[[similar-now]] with `database`'s cluster dials, its durable store and the embed server: in progress while
  the store still opens ([[store-within]])."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.embed/task [:vector :seon.embed/text] :seon.embed/task
                       :seon.embed/text pos-int?]
                  [:or :seon.embed/answer :seon.embed/unreachable-error :seon.config/error]]}
  [database task texts query-task query k]
  (let [dials (cluster-settings database)
        store (when-not (:seon.error/operation dials)
                (store-within (directory database) (:seon.config.embed/wait-ms dials)))]
    (cond (:seon.error/operation dials) dials
          (:seon.cache/pending-kind store) {:seon.embed/pending (count texts)}
          :else (similar-now dials store request! task texts query-task query k))))

;;; The corpora agents rank, and their background fill.

(defn meaning-texts
  "Each public function's text as the embed server reads a titled document, `title: sym | text: arglists and
  docstring`, beside its symbol, sorted by symbol: what an agent calls, so private helpers never outrank it."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:vector [:tuple :qualified-symbol :seon.embed/text]]]}
  [database]
  (->> (db/q database '[:find ?s ?arglists ?doc :where [?e :seon.fn/sym ?s]
                        [(get-else $ ?e :seon.fn/private? false) ?private] [(not ?private)]
                        [(get-else $ ?e :seon.fn/arglists "") ?arglists] [(get-else $ ?e :seon.fn/doc "") ?doc]])
       (sort-by first)
       (mapv (fn [[sym arglists doc]] [sym (str "title: " sym " | text: " arglists "\n" doc)]))))

(defn name-texts
  "Every function and test the program declares on `database`, as qualified-name text, sorted."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:vector :seon.embed/text]]}
  [database]
  (into [] (comp (map str) (distinct))
        (sort (concat (db/q database '[:find [?s ...] :where [?e :seon.fn/sym ?s]])
                      (db/q database '[:find [?s ...] :where [?e :seon.test/sym ?s]])))))

(defn fill!
  "Derive the indexes of `database`'s two corpora (by meaning, `meaning-texts`; by name, `name-texts`) with its
  cluster's dials, embedding what neither cache holds through `request`: the work a call ranking them would
  otherwise pay. What each derivation answered, by task."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.embed/request]
                  [:or [:map-of :seon.embed/task [:or :seon.embed/index :seon.embed/unreachable-error]]
                   :seon.config/error]]}
  [database request]
  (let [dials (cluster-settings database)]
    (if (:seon.error/operation dials)
      dials
      (let [store (open-store (directory database))]
        {"none" (index dials store request "none" (mapv second (meaning-texts database)))
         "similarity" (index dials store request "similarity" (name-texts database))}))))

(def ^:private corpus-attributes
  "The attributes whose change changes a corpus's text."
  #{:seon.fn/sym :seon.fn/doc :seon.fn/arglists :seon.fn/private? :seon.test/sym})

(defn- corpus-report?
  "Whether the transaction `report` changed a corpus's text."
  {:malli/schema [:=> [:cat :map] :boolean]}
  [report]
  (boolean (some #(contains? corpus-attributes (nth % 1)) (:tx-data report))))

(defn fill-step
  "The background fill proc in Flow's four arities, `:io`: one payload-free wake
  (`:seon.embed/channel`, sliding-1) from its own listener on the cluster's
  connection, offered when a transaction changes a corpus's text and once as
  it resumes (boot), so the boot's and every later main write's embedding
  happens here and never in an agent's call. Each pass derives the corpora
  from the connection's current value ([[fill!]])."
  {:malli/schema
   [:function [:=> [:cat] [:map]] [:=> [:cat :map] :map] [:=> [:cat :map :keyword] :map]
    [:=> [:cat :map :keyword [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary
                                    :seon.schema.admission/reason "The wake is a payload-free keyword; Flow hands the port's message as it is."
                                    :gen/elements [nil false 0 "" :k [] {}]}]]
     [:tuple :map :nil]]]}
  ([]
   {:ins {}
    :outs {}
    :workload :io
    :ping-map-fn #(select-keys % [::passes ::filled])})
  ([args]
   (assoc args
          ::flow/in-ports {::wake (:seon.embed/channel args)}
          ::passes 0
          ::listener-key (random-uuid)))
  ([state transition]
   (let [connection (get-in state [:seon.turn.loop/cluster :seon.db/connection])
         channel (:seon.embed/channel state)]
     (case transition
       ::flow/resume
       (do (d/listen connection (::listener-key state)
                     (fn [report] (when (corpus-report? report) (async/offer! channel ::wake))))
           (async/offer! channel ::wake)
           state)
       ::flow/pause
       (do (d/unlisten connection (::listener-key state)) state)
       ::flow/stop
       (do (d/unlisten connection (::listener-key state))
           (async/close! channel)
           state)
       state)))
  ([state _input _message]
   (let [filled (fill! (db/db (get-in state [:seon.turn.loop/cluster :seon.db/connection]))
                       (:seon.embed/request state request!))]
     [(-> state
          (update ::passes inc)
          (assoc ::filled (if (:seon.error/operation filled)
                            filled
                            (update-vals filled #(if (error/error? %) % (count (:seon.embed/texts %)))))))
      nil])))
