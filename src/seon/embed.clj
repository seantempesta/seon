(ns seon.embed
  "Embeddings of text, keyed by the content they embed, and the texts nearest a
  query by them. `seon.cache` is the hot layer, a konserve store beside the
  cluster's Datahike store the durable one (it survives restart and reset), and
  the embed server is reached only for text neither holds."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
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
           [java.util Arrays Base64 PriorityQueue]))

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

(defn open-store
  "The durable embedding store in directory `dir`, created when absent and
  opened once per process. Its writes skip the fsync: a write a crash loses
  costs one re-embed of its text."
  {:malli/schema [:=> [:cat [:string {:min 1}]] :seon.embed/store]}
  [dir]
  (seon.cache/lookup ::store dir (constantly 1024)
                     #(filestore/connect-fs-store dir :opts {:sync? true} :config {:sync-blob? false})))

(defn cluster-settings
  "The embed dials of the cluster `database` belongs to (`seon.config/effective`)."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:or :seon.embed/settings :seon.config/error]]}
  [database]
  (let [effective (config/effective database (db/q '[:find ?n . :where [_ :seon.config/cluster ?n]] database))]
    (if (:seon.error/operation effective)
      effective
      (select-keys effective [:seon.config.embed/endpoint :seon.config.embed/model :seon.config.embed/dimension
                              :seon.config.embed/batch-size :seon.config.embed/timeout-ms]))))

(defn- hot!
  "Retain `octets` under `content-key` in the process cache, answering them."
  {:malli/schema [:=> [:cat :seon.embed/key :seon.embed/octets] :seon.embed/octets]}
  [content-key octets]
  (seon.cache/lookup ::embedding content-key #(+ 96 (alength ^bytes %)) (constantly octets)))

(defn- requested!
  "`found` with the embeddings `request` answers for the texts of `batch`
  (content keys, `text-of` naming each one's text), each written to `store`
  and the process cache; the unreachable error, reduced, when it answers none."
  {:malli/schema [:=> [:cat :seon.embed/settings :seon.embed/store :seon.embed/request :seon.embed/task
                       [:map-of :seon.embed/key :seon.embed/text] [:map-of :seon.embed/key :seon.embed/octets]
                       [:sequential :seon.embed/key]]
                  :seon.schema/value]}
  [settings store request task text-of found batch]
  (let [answer (request settings task (mapv text-of batch))]
    (if (error/error? answer)
      (reduced answer)
      (into found (map (fn [content-key octets]
                         (k/assoc store content-key octets {:sync? true})
                         [content-key (hot! content-key octets)])
                       batch answer)))))

(defn embed!
  "The embeddings of `texts` for `task`, in order, with how many came from each
  layer: the process cache first, then `store`, then one `request` per
  `settings`' batch-size of the rest, each written to `store` and the cache
  once. Text repeated within `texts` is requested once. The unreachable error
  when the embed server is needed and does not answer."
  {:malli/schema [:=> [:cat :seon.embed/settings :seon.embed/store :seon.embed/request
                       :seon.embed/task [:vector :seon.embed/text]]
                  [:or :seon.embed/embedded :seon.embed/unreachable-error]]}
  [settings store request task texts]
  (let [content-keys (mapv #(embedding-key settings task %) texts)
        hot (into {} (keep (fn [content-key]
                             (when-let [octets (seon.cache/present ::embedding content-key)] [content-key octets])))
                  (distinct content-keys))
        durable (into {} (keep (fn [content-key]
                                 (when-let [octets (k/get store content-key nil {:sync? true})]
                                   [content-key (hot! content-key octets)])))
                      (into [] (comp (distinct) (remove hot)) content-keys))
        text-of (zipmap content-keys texts)
        missing (into [] (comp (distinct) (remove hot) (remove durable)) content-keys)
        requested (reduce (partial requested! settings store request task text-of)
                          {}
                          (partition-all (:seon.config.embed/batch-size settings) missing))]
    (if (error/error? requested)
      requested
      (let [found (merge hot durable requested)]
        {:seon.embed/vectors (mapv found content-keys)
         :seon.embed/hot (count hot)
         :seon.embed/durable (count durable)
         :seon.embed/requested (count requested)}))))

(defn- stacked
  "The index of `texts` from their embeddings: row i the embedding of text i."
  {:malli/schema [:=> [:cat [:vector :seon.embed/text] :seon.embed/embedded] :seon.embed/index]}
  [texts {:seon.embed/keys [vectors]}]
  (let [out (java.io.ByteArrayOutputStream. (int (transduce (map #(alength ^bytes %)) + 0 vectors)))]
    (run! #(.write out ^bytes %) vectors)
    {:seon.embed/texts texts :seon.embed/matrix (.toByteArray out)}))

(defn index
  "The embeddings of `texts` for `task` as one matrix ([[stacked]]), derived
  once per content (model, dimension, task and texts) and held in the process
  cache; the unreachable error when an embedding is missing and the server
  does not answer."
  {:malli/schema [:=> [:cat :seon.embed/settings :seon.embed/store :seon.embed/request
                       :seon.embed/task [:vector :seon.embed/text]]
                  [:or :seon.embed/index :seon.embed/unreachable-error]]}
  [settings store request task texts]
  (seon.cache/lookup
   ::index
   (schema/sha-256 (into [(.getBytes (str (:seon.config.embed/model settings) "\u0000" task "\u0000"
                                          (:seon.config.embed/dimension settings) "\u0000")
                                     StandardCharsets/UTF_8)]
                         (map #(.getBytes (str % "\u0000") StandardCharsets/UTF_8))
                         texts))
   (fn [held] (+ 256 (alength ^bytes (:seon.embed/matrix held)) (* 64 (count texts))))
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
  unreachable error."
  {:malli/schema [:=> [:cat :seon.embed/settings :seon.embed/store :seon.embed/request :seon.embed/task
                       [:vector :seon.embed/text] :seon.embed/task :seon.embed/text pos-int?]
                  [:or :seon.embed/neighbours :seon.embed/unreachable-error]]}
  [settings store request task texts query-task query k]
  (let [held (index settings store request task texts)
        asked (when-not (error/error? held) (embed! settings store request query-task [query]))]
    (cond (error/error? held) held
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
