(ns seon.vector
  "Matryoshka vector pyramid (`libprobe.dylib` C SIMD — zero threads, safe everywhere),
   content-addressed text extraction, and isolated `embed-worker` (`libseon-embed.dylib` loaded ONLY in `serve!`)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datahike.api :as d]
            [jolt.ffi :as ffi]
            [seon.store :as store])
  (:import [java.io InputStream OutputStream]
           [java.security MessageDigest]
           [java.util.concurrent TimeUnit]))
{}

(def default-coarse-dims 128)

(def default-shortlist 64)

(def default-batch-tokens 8192)

(def default-batch-texts 16)

(def default-max-tokens 1024)

(def default-threads 4)

(def texts-version "v1-titled")

(def query-prompt "task: search result | query: ")

(when-not (ffi/find-symbol "seon_vec_pack")
  (ffi/load-library (str store/checkout-root "/target/lib/libprobe.dylib")))

(ffi/defcfn ^:private c-pack "seon_vec_pack" [:pointer :int :int :pointer :pointer] :void)

(ffi/defcfn ^:private c-coarse "seon_vec_coarse" [:pointer :long :int :pointer :int :pointer :pointer] :int)

(ffi/defcfn ^:private c-fine "seon_vec_fine" [:pointer :long :int :int :pointer :pointer] :void)

(ffi/defcfn ^:private c-f32 "seon_vec_f32" [:pointer :int :pointer] :void)

(defn- sanitize-floats [v]
  (let [n (count v) arr (float-array n)]
    (dotimes [i n]
      (let [x (double (nth v i))]
        (aset-float arr i (if (and (== x x) (not (Double/isInfinite x))) (float x) (float 0.0)))))
    arr))

(defn pack
  "Packs unit float vector `v` via `seon_vec_pack` into `{:seon.vector/f16 :seon.vector/coarse}`."
  {:malli/schema [:function
                  [:=> [:cat [:vector {:min 1} number?]] [:map [:seon.vector/f16 bytes?] [:seon.vector/coarse bytes?]]]
                  [:=> [:cat [:vector {:min 1} number?] pos-int?] [:map [:seon.vector/f16 bytes?] [:seon.vector/coarse bytes?]]]]
   :cost/ticks 2000000}
  ([v] (pack v default-coarse-dims))
  ([v coarse-dims]
   (let [n (count v) cd (min (long coarse-dims) n) farr (sanitize-floats v)]
     (ffi/with-arena [a]
       (let [vp (ffi/alloc a (* 4 n)) fp (ffi/alloc a (* 2 n)) cp (ffi/alloc a (+ cd 4))]
         (ffi/write-array vp :float farr 0)
         (c-pack vp n cd fp cp)
         {:seon.vector/f16 (ffi/read-array fp :int8 (* 2 n) 0)
          :seon.vector/coarse (ffi/read-array cp :int8 (+ cd 4) 0)})))))

(defn unpack
  "Unpacks an IEEE 754 f16 byte row into a float vector via `seon_vec_f32`."
  {:malli/schema [:=> [:cat bytes?] [:vector number?]]
   :cost/ticks 2000000}
  [f16]
  (let [blen (alength ^bytes f16) n (quot blen 2)]
    (if (zero? n)
      []
      (ffi/with-arena [a]
        (let [p (ffi/alloc a (max 2 blen)) o (ffi/alloc a (* 4 n))]
          (ffi/write-array p :int8 f16 0)
          (c-f32 p n o)
          (vec (ffi/read-array o :float n 0)))))))

(defn coarse-top
  "Ranks Matryoshka int8+scale coarse `rows` against `query` (bytes or float vec) via `seon_vec_coarse`."
  {:malli/schema [:function
                  [:=> [:cat [:sequential bytes?] [:or bytes? [:vector {:min 1} number?]] nat-int?]
                   [:vector [:tuple :int number?]]]]
   :cost/ticks 10000000}
  [rows query k]
  (let [rv (vec rows) n (count rv) kk (min n (long k))]
    (if (or (zero? n) (zero? kk))
      []
      (let [w (alength ^bytes (first rv)) cd (- w 4)]
        (if (<= cd 0)
          []
          (let [^bytes qb (if (bytes? query) query (:seon.vector/coarse (pack query cd)))]
            (when (not= w (alength qb))
              (throw (ex-info (str "seon.vector/coarse-top: query width " (alength qb) " != " w)
                              {:query-width (alength qb) :row-width w})))
            (ffi/with-arena [a]
              (let [cp (ffi/alloc a (* n w)) qp (ffi/alloc a w)
                    ip (ffi/alloc a (* 8 kk)) sp (ffi/alloc a (* 4 kk))]
                (dotimes [i n] (ffi/write-bytes (ffi/slice cp (* i w)) (nth rv i)))
                (ffi/write-bytes qp qb)
                (let [m (c-coarse cp n cd qp kk ip sp)
                      idxs (ffi/read-array ip :int64 m 0)
                      scrs (ffi/read-array sp :float m 0)]
                  (mapv (fn [i] [(long (nth idxs i)) (double (nth scrs i))]) (range m)))))))))))

(defn fine-scores
  "Renormalized cosine of `q` against each f16 byte row in `f16-rows` (nil stays nil) over `fine-dims` via `seon_vec_fine`."
  {:malli/schema [:function
                  [:=> [:cat [:vector {:min 1} number?] [:sequential [:maybe bytes?]]] [:vector [:maybe number?]]]
                  [:=> [:cat [:vector {:min 1} number?] [:sequential [:maybe bytes?]] pos-int?] [:vector [:maybe number?]]]]
   :cost/ticks 10000000}
  ([q f16-rows] (fine-scores q f16-rows (count q)))
  ([q f16-rows fine-dims]
   (let [dims (count q) fd (min (long fine-dims) dims) exp-b (* 2 dims)
         rows (vec f16-rows) ok? #(and (bytes? %) (= exp-b (alength ^bytes %)))
         have (filterv ok? rows) m (count have)]
     (if (zero? m)
       (mapv (constantly nil) rows)
       (let [qarr (sanitize-floats q)]
         (ffi/with-arena [a]
           (let [fp (ffi/alloc a (* m exp-b)) qf (ffi/alloc a (* 4 dims)) fs (ffi/alloc a (* 4 m))]
             (dotimes [j m] (ffi/write-bytes (ffi/slice fp (* j exp-b)) (nth have j)))
             (ffi/write-array qf :float qarr 0)
             (c-fine fp m dims fd qf fs)
             (let [ss (ffi/read-array fs :float m 0)]
               (first
                (reduce (fn [[out j] r]
                          (if (ok? r) [(conj out (double (nth ss j))) (inc j)] [(conj out nil) j]))
                        [[] 0] rows))))))))))

(defn- stored-val [db e a] (:v (first (d/datoms db :eavt e a))))

(defn- resolve-eid [db ref-or-eid]
  (cond
    (int? ref-or-eid) ref-or-eid
    (and (vector? ref-or-eid) (= 2 (count ref-or-eid)) (keyword? (first ref-or-eid)))
    (when (contains? (:schema db) (first ref-or-eid))
      (:e (first (d/datoms db :avet (first ref-or-eid) (second ref-or-eid)))))
    :else nil))

(defn vector-of
  "Returns unpacked float vector of `eid-or-ref` on `db`, or nil."
  {:malli/schema [:=> [:cat :seon.host/db :any] [:maybe [:vector number?]]]
   :cost/ticks 2000000}
  [db eid-or-ref]
  (when (and db (contains? (:schema db) :seon.vector/of))
    (when-let [eid (resolve-eid db eid-or-ref)]
      (when-let [ve (:e (first (d/datoms db :avet :seon.vector/of eid)))]
        (some-> (stored-val db ve :seon.vector/f16) unpack)))))

(defn- merge-join-coarse-rows [db keep? self]
  (let [cv (vec (d/datoms db :aevt :seon.vector/coarse)) n (count cv)]
    (loop [os (seq (d/datoms db :aevt :seon.vector/of))
           i 0 ves (transient []) cb (transient []) es (transient [])]
      (if-let [o (first os)]
        (let [oe (:e o)
              i (loop [j i] (if (and (< j n) (< (:e (nth cv j)) oe)) (recur (inc j)) j))
              hit? (and (< i n) (= oe (:e (nth cv i))) (keep? (:v o)) (not= self (:v o)))]
          (recur (next os) i (cond-> ves hit? (conj! oe))
                 (cond-> cb hit? (conj! (:v (nth cv i)))) (cond-> es hit? (conj! (:v o)))))
        [(persistent! ves) (persistent! cb) (persistent! es)]))))

(defonce ^:private ^:seon.flow/edge coarse-rows-cache (store/lru-cache 16))

(defn- cached-coarse-rows [db of self]
  (let [compute #(let [keep? (if (and of (contains? (:schema db) of))
                               (into #{} (map :e) (d/datoms db :aevt of))
                               (if of #{} any?))]
                   (merge-join-coarse-rows db keep? self))
        ck (when (instance? datahike.db.DB db)
             [(get-in db [:config :store :id]) (get-in db [:config :branch] :main) (:max-tx db) of self])]
    (if ck (store/lru-get! coarse-rows-cache 16 ck compute) (compute))))

(defn near
  "Matryoshka pyramid semantic search (direct or Datalog `[(seon.vector/near $ ?q 10 {:of :seon.code.var/name}) [[?e ?score]]]`)."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db :any nat-int?] [:vector [:tuple :int number?]]]
                  [:=> [:cat :seon.host/db :any nat-int?
                        [:map [:of {:optional true} [:maybe :qualified-keyword]]
                         [:shortlist {:optional true} pos-int?]
                         [:fine-dims {:optional true} pos-int?]]]
                   [:vector [:tuple :int number?]]]]
   :cost/ticks 25000000}
  ([db q k] (near db q k {}))
  ([db q k {:keys [of shortlist fine-dims]}]
   (if-not (and db (pos? (long k)) (contains? (:schema db) :seon.vector/coarse))
     []
     (let [self (when-not (and (vector? q) (number? (first q))) (resolve-eid db q))
           qv (if self (vector-of db self) (when (and (vector? q) (seq q) (number? (first q))) q))]
       (if (empty? qv)
         []
         (let [[ves cbytes es] (cached-coarse-rows db of self) n (count cbytes)]
           (if (zero? n)
             []
             (let [dims (count qv)
                   f0 (stored-val db (first ves) :seon.vector/f16)
                   sdims (if (bytes? f0) (quot (alength ^bytes f0) 2) 0)
                   _ (when (and (pos? sdims) (not= dims sdims))
                       (throw (ex-info (str "seon.vector/near: query dims " dims " != stored dims " sdims)
                                       {:dims dims :stored sdims})))
                   sl (min n (max (long k) (long (or shortlist default-shortlist))))
                   fd (min dims (long (or fine-dims dims)))
                   idxs (mapv first (coarse-top cbytes qv sl))
                   hit-es (mapv #(nth es %) idxs)
                   fscores (fine-scores qv (mapv #(stored-val db (nth ves %) :seon.vector/f16) idxs) fd)]
               (->> (map (fn [e s] (when (some? s) [(- (double s)) e])) hit-es fscores)
                    (remove nil?) sort (take k)
                    (mapv (fn [[ns e]] [e (- (double ns))])))))))))))

(defn semantic-scores-for
  "Batched C SIMD cosine `{eid score}` for candidate `eids` against `query-vec` (for `rank-candidates`)."
  {:malli/schema [:function
                  [:=> [:cat [:maybe :seon.host/db] [:maybe [:vector number?]] [:sequential :any]] [:map-of :any number?]]
                  [:=> [:cat [:maybe :seon.host/db] [:maybe [:vector number?]] [:sequential :any] pos-int?] [:map-of :any number?]]]
   :cost/ticks 15000000}
  ([db query-vec eids] (semantic-scores-for db query-vec eids (max 1 (count (or query-vec [])))))
  ([db query-vec eids fine-dims]
   (if-not (and db (seq query-vec) (seq eids) (contains? (:schema db) :seon.vector/of))
     {}
     (let [ev (vec eids)
           f16s (mapv (fn [item]
                        (when-let [eid (resolve-eid db item)]
                          (when-let [ve (:e (first (d/datoms db :avet :seon.vector/of eid)))]
                            (stored-val db ve :seon.vector/f16))))
                      ev)
           scrs (fine-scores query-vec f16s (min (long fine-dims) (count query-vec)))]
       (persistent!
        (reduce-kv (fn [acc idx s] (if (some? s) (assoc! acc (nth ev idx) (double s)) acc))
                   (transient {}) scrs))))))

(defn document-text
  "Formats document text for EmbeddingGemma-2 (`\"title: <title> | text: <text>\"`)."
  {:malli/schema [:=> [:cat [:maybe :string] :string] :string] :cost/ticks 50000}
  [title text]
  (str "title: " (if (str/blank? title) "none" title) " | text: " text))

(defn query-text
  "Formats query or task intent for EmbeddingGemma-2 (`\"task: search result | query: <q>\"`)."
  {:malli/schema [:=> [:cat :string] :string] :cost/ticks 50000}
  [q]
  (str query-prompt q))

(defn text-digest
  "Deterministic 64-char lowercase SHA-256 hex digest of UTF-8 string `s`."
  {:malli/schema [:=> [:cat :string] :string] :cost/ticks 200000}
  [^String s]
  (let [bs (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))
        sb (StringBuilder. 64)]
    (dotimes [i (alength bs)]
      (let [b (bit-and 0xff (aget bs i))]
        (when (< b 16) (.append sb \0))
        (.append sb (Integer/toHexString b))))
    (.toString sb)))

(defn model-id
  "Derives stable `:seon.vector/model` id from model file path + `texts-version`."
  {:malli/schema [:=> [:cat :string] :string] :cost/ticks 100000}
  [path]
  (let [nm (.getName (io/file path)) dot (str/last-index-of nm ".")]
    (str (if (and dot (pos? dot)) (subs nm 0 dot) nm) "+" texts-version)))

(defn resolve-model-path
  "Returns existing GGUF model path from `override` or `SEON_EMBED_MODEL`, else nil."
  {:malli/schema [:function [:=> [:cat] [:maybe :string]] [:=> [:cat [:maybe :string]] [:maybe :string]]]
   :cost/ticks 100000}
  ([] (resolve-model-path (System/getenv "SEON_EMBED_MODEL")))
  ([path]
   (when (and (string? path) (not (str/blank? path)))
     (let [f (io/file path)] (when (.isFile f) (.getAbsolutePath f))))))

(defn- scan-attr-map [db attr]
  (if (contains? (:schema db) attr)
    (persistent! (reduce (fn [m dt] (assoc! m (:e dt) (:v dt))) (transient {}) (d/datoms db :aevt attr)))
    {}))

(defn- collect-due [primary-map kind text-fn of->text of->model cur-model]
  (persistent!
   (reduce-kv
    (fn [acc e v]
      (if-let [t (text-fn e v)]
        (let [h (text-digest t)]
          (if (and (= cur-model (get of->model e)) (= h (get of->text e)))
            acc
            (conj! acc {:eid e :kind kind :text t :digest h})))
        acc))
    (transient []) primary-map)))

(defn due-entities
  "Finds entities missing a vector or with changed digest/`cur-model`, bounded by `limit`."
  {:malli/schema [:=> [:cat :seon.host/db :string nat-int?] [:vector :map]]
   :cost/ticks 50000000}
  [db cur-model limit]
  (if (or (nil? db) (<= (long limit) 0))
    []
    (let [ve->of (scan-attr-map db :seon.vector/of)
          ve->text (scan-attr-map db :seon.vector/text)
          ve->model (scan-attr-map db :seon.vector/model)
          of->text (persistent! (reduce-kv (fn [m ve e] (if-let [v (get ve->text ve)] (assoc! m e v) m)) (transient {}) ve->of))
          of->model (persistent! (reduce-kv (fn [m ve e] (if-let [v (get ve->model ve)] (assoc! m e v) m)) (transient {}) ve->of))
          fkeys (scan-attr-map db :seon.code.form/key) fsrcs (scan-attr-map db :seon.code.form/src)
          fk->src (persistent! (reduce-kv (fn [m fe fk] (if-let [s (get fsrcs fe)] (assoc! m fk s) m)) (transient {}) fkeys))
          vforms (scan-attr-map db :seon.code.var/form) vdocs (scan-attr-map db :seon.code.var/doc)
          dheads (scan-attr-map db :seon.code.doc/heading) dids (scan-attr-map db :seon.code.doc/id)
          sdocs (scan-attr-map db :seon.schema/doc) sforms (scan-attr-map db :seon.schema/form)
          ttitles (scan-attr-map db :seon.task/title) tplans (scan-attr-map db :seon.task/plan)
          ekinds (scan-attr-map db :seon.error/kind)
          all (concat
               (collect-due (scan-attr-map db :seon.code.var/name) :var
                            (fn [e vn] (let [s (or (some-> (get vforms e) fk->src) (get vdocs e))]
                                         (when-not (str/blank? (str s)) (document-text (str vn) (str s)))))
                            of->text of->model cur-model)
               (collect-due (scan-attr-map db :seon.code.doc/body) :doc
                            (fn [e b] (when-not (str/blank? (str b))
                                        (document-text (some-> (or (get dheads e) (get dids e)) str) (str b))))
                            of->text of->model cur-model)
               (collect-due (scan-attr-map db :seon.schema/key) :schema
                            (fn [e sk] (let [sd (get sdocs e) sf (get sforms e)
                                             b (str/trim (str (when-not (str/blank? (str sd)) (str sd "\n")) (when (some? sf) (pr-str sf))))]
                                         (when-not (str/blank? b) (document-text (str sk) b))))
                            of->text of->model cur-model)
               (collect-due (scan-attr-map db :seon.task/id) :task
                            (fn [e tid] (let [pl (get tplans e)
                                              b (str/trim (str (or (get ttitles e) (str tid))
                                                               (when-not (str/blank? (str pl)) (str "\n" pl))))]
                                          (when-not (str/blank? b) (query-text b))))
                            of->text of->model cur-model)
               (collect-due (scan-attr-map db :seon.error/message) :error
                            (fn [e msg] (when-not (str/blank? (str msg))
                                          (document-text (str (or (get ekinds e) :seon.error/entity)) (str msg))))
                            of->text of->model cur-model))]
      (vec (take (long limit) (sort-by :eid all))))))

(defn tx-data-for-batch
  "Builds Datahike upsert tx-data for embedded `items`, `vectors`, optional `tokens`, and `cur-model`."
  {:malli/schema [:function
                  [:=> [:cat [:sequential :map] [:sequential [:vector {:min 1} number?]]
                        [:maybe [:sequential [:maybe :int]]] :string] [:vector :map]]]
   :cost/ticks 20000000}
  [items vectors tokens cur-model]
  (let [iv (vec items) vv (vec vectors) tv (when (seq tokens) (vec tokens)) n (min (count iv) (count vv))]
    (vec
     (keep (fn [i]
             (let [it (nth iv i) v (nth vv i) tok (when (and tv (< i (count tv))) (nth tv i))]
               (when (and (:eid it) (seq v))
                 (cond-> (assoc (pack v)
                                :seon.vector/of (:eid it)
                                :seon.vector/model cur-model
                                :seon.vector/text (str (or (:digest it) "")))
                   (integer? tok) (assoc :seon.vector/tokens (long tok))))))
           (range n)))))

(defn ^:seon.flow/edge write-frame!
  "Writes one `[int32 len][UTF-8 EDN]` frame onto `OutputStream`."
  {:malli/schema [:function [:=> [:cat :any :any] :boolean]] :cost/ticks 500000}
  [^OutputStream out val]
  (let [bs (.getBytes (binding [*print-length* nil *print-level* nil *print-namespace-maps* false] (pr-str val)) "UTF-8")
      len (alength bs)
      hdr (byte-array [(unchecked-byte (unsigned-bit-shift-right len 24))
                       (unchecked-byte (unsigned-bit-shift-right len 16))
                       (unchecked-byte (unsigned-bit-shift-right len 8))
                       (unchecked-byte len)])]
  (.write out hdr)
  (.write out bs)
  (.flush out)
  true))

(defn ^:seon.flow/edge read-frame!
  "Reads one `[int32 len][UTF-8 EDN]` frame from `InputStream`, returning `:eof` on clean EOF."
  {:malli/schema [:function [:=> [:cat :any] :any]] :cost/ticks 500000}
  [^InputStream in]
  (let [read-exact (fn [^bytes buf]
                   (let [need (alength buf)]
                     (loop [off 0]
                       (if (= off need)
                         true
                         (let [n (.read in buf off (- need off))]
                           (cond
                             (neg? n) :eof
                             (zero? n) (recur off)
                             :else (recur (+ off n))))))))
      hdr (byte-array 4)]
  (if (not= true (read-exact hdr))
    :eof
    (let [len (bit-or (bit-shift-left (bit-and 0xff (aget hdr 0)) 24)
                      (bit-shift-left (bit-and 0xff (aget hdr 1)) 16)
                      (bit-shift-left (bit-and 0xff (aget hdr 2)) 8)
                      (bit-and 0xff (aget hdr 3)))]
      (when (or (neg? len) (> len (* 64 1024 1024)))
        (throw (ex-info (str "Invalid embed frame length: " len) {:len len})))
      (let [bs (byte-array len)]
        (if (= true (read-exact bs))
          (edn/read-string (String. bs "UTF-8"))
          :eof))))))

(defn- ^:seon.flow/edge open-native-embedder!
  [model-path {:keys [lib-path batch-tokens batch-texts max-tokens threads]
               :or {batch-tokens default-batch-tokens batch-texts default-batch-texts
                    max-tokens default-max-tokens threads default-threads}}]
  (let [lp (or lib-path (not-empty (System/getenv "SEON_EMBED_LIB"))
               (str store/checkout-root "/target/lib/libseon-embed.dylib"))]
    (cond
      (not (and model-path (.isFile (io/file model-path)))) {:ok? false :error :model-missing :path model-path}
      (not (.isFile (io/file lp))) {:ok? false :error :embed-lib-missing :path lp}
      :else
      (do
        (ffi/load-library lp)
        (let [c-open (ffi/cfn "seon_embed_open" [:pointer :int :int :int :int] :pointer :blocking)
              c-dims (ffi/cfn "seon_embed_dims" [:pointer] :int)
              c-batch (ffi/cfn "seon_embed_batch" [:pointer :pointer :pointer :int :int :pointer :pointer] :int :blocking)
              c-close (ffi/cfn "seon_embed_close" [:pointer] :void)
              h (ffi/with-arena [a]
                  (c-open (ffi/string->ptr a model-path) (int batch-tokens) (int batch-texts) (int threads) 0))]
          (if (zero? (ffi/address h))
            {:ok? false :error :model-unopened :path model-path}
            (let [dims (int (c-dims h))]
              {:ok? true :dims dims :model-id (model-id model-path)
               :embed-fn (fn [texts]
                           (let [tv (vec texts) n (count tv)]
                             (if (zero? n)
                               {:ok? true :dims dims :vectors [] :tokens []}
                               (let [bs (mapv #(.getBytes ^String (str %) "UTF-8") tv)
                                     offs (long-array (reductions + 0 (map #(alength ^bytes %) bs)))]
                                 (ffi/with-arena [a]
                                   (let [tp (ffi/alloc a (max 1 (aget offs n))) op (ffi/alloc a (* 8 (inc n)))
                                         out (ffi/alloc a (max 4 (* 4 n dims))) np (ffi/alloc a (max 4 (* 4 n)))]
                                     (dotimes [i n] (ffi/write-bytes (ffi/slice tp (aget offs i)) (nth bs i)))
                                     (ffi/write-array op :int64 offs 0)
                                     (let [rc (c-batch h tp op n (int max-tokens) out np)]
                                       (if (neg? rc)
                                         {:ok? false :error :encode-failed :code rc}
                                         {:ok? true :dims dims
                                          :vectors (mapv #(vec (ffi/read-array out :float dims (* 4 dims %))) (range n))
                                          :tokens (vec (ffi/read-array np :int32 n 0))}))))))))
               :close-fn #(try (c-close h) (catch Throwable _ nil))})))))))

(defn ^:seon.flow/edge serve-loop!
  "Framed protocol loop over `in`/`out` streams using `embedder`; exits cleanly on EOF."
  {:malli/schema [:function [:=> [:cat :any :any :map] :map]] :cost/ticks 5000000}
  [^InputStream in ^OutputStream out {:keys [dims model-id embed-fn close-fn]}]
  (try
    (write-frame! out {:ok? true :ready? true :dims dims :model-id model-id})
    (loop [served 0]
      (let [req (read-frame! in)]
        (cond
          (= :eof req) {:ok? true :served served :reason :eof}
          (= :quit (:op req)) (do (write-frame! out {:ok? true :bye? true}) {:ok? true :served served :reason :quit})
          (= :ping (:op req)) (do (write-frame! out {:ok? true :ready? true :dims dims}) (recur served))
          (= :embed (:op req))
          (let [ans (try (embed-fn (:texts req))
                         (catch Throwable t {:ok? false :error :embed-exception :message (or (.getMessage t) (str t))}))]
            (write-frame! out ans)
            (recur (inc served)))
          :else (do (write-frame! out {:ok? false :error :unknown-op :op (:op req)}) (recur served)))))
    (finally (when close-fn (close-fn)))))

(defn ^:seon.flow/edge serve!
  "Exec'd `target/seon embed-worker` entrypoint: loads `libseon-embed.dylib` & `SEON_EMBED_MODEL`, serves stdin/stdout."
  {:malli/schema [:function [:=> [:cat] :any] [:=> [:cat :map] :any]] :cost/ticks 5000000}
  ([] (serve! {}))
  ([opts]
   (let [raw-out System/out raw-in System/in mpath (resolve-model-path (:model-path opts))]
     (binding [*out* (java.io.OutputStreamWriter. System/err)]
       (let [emb (open-native-embedder! mpath opts)]
         (if-not (:ok? emb) (do (write-frame! raw-out emb) emb) (serve-loop! raw-in raw-out emb)))))))

(defn- default-worker-argv []
  (let [env-bin (not-empty (System/getenv "SEON_EMBED_WORKER_BIN"))
        seon-bin (str store/checkout-root "/target/seon")
        jolt-bin (str store/checkout-root "/target/jolt/jolt")]
    (cond
      (and env-bin (.canExecute (io/file env-bin))) [env-bin "embed-worker"]
      (.canExecute (io/file seon-bin)) [seon-bin "embed-worker"]
      (.canExecute (io/file jolt-bin)) [jolt-bin "-m" "seon.main" "embed-worker"]
      :else nil)))

(defn- ^:seon.flow/edge close-worker-handle! [h force?]
  (when h
    (let [^Process p (:proc h)]
      (try (.close ^OutputStream (:in h)) (catch Throwable _ nil))
      (when force? (try (.destroyForcibly p) (catch Throwable _ nil)))
      (when-not (try (.waitFor p 2000 TimeUnit/MILLISECONDS) (catch Throwable _ false))
        (try (.destroyForcibly p) (catch Throwable _ nil))
        (try (.waitFor p 500 TimeUnit/MILLISECONDS) (catch Throwable _ nil)))
      (try (.close ^InputStream (:out h)) (catch Throwable _ nil))
      (let [ef (io/file (:err-path h))
            raw (try (if (.isFile ef) (slurp ef) "") (catch Throwable _ ""))
            stderr (if (> (count raw) 2048) (subs raw (- (count raw) 2048)) raw)]
        (try (.delete ef) (catch Throwable _ nil))
        {:exit (try (.exitValue p) (catch Throwable _ -1)) :stderr stderr}))))

(defn- ^:seon.flow/edge spawn-worker-handle! [mpath argv]
  (cond
    (nil? mpath) {:ok? false :error :model-missing}
    (empty? argv) {:ok? false :error :worker-binary-missing}
    :else
    (let [tmp-dir (doto (io/file store/checkout-root "target/tmp") (.mkdirs))
          err-path (str (.getAbsolutePath tmp-dir) "/embed-worker-" (random-uuid) ".err")
          pb (doto (ProcessBuilder. ^java.util.List (vec argv))
               (.directory (io/file store/checkout-root))
               (.redirectError (io/file err-path)))]
      (doto (.environment pb) (.put "SEON_EMBED_MODEL" mpath) (.put "SEON_CHECKOUT" store/checkout-root))
      (try
        (let [p (.start pb)
              h {:proc p :in (.getOutputStream p) :out (.getInputStream p) :err-path err-path}
              ready (try (read-frame! (:out h)) (catch Throwable t {:ok? false :error (.getMessage t)}))]
          (if (and (map? ready) (:ok? ready) (:ready? ready))
            {:ok? true :handle (assoc h :dims (:dims ready))}
            (let [fin (close-worker-handle! h true)]
              {:ok? false :error (or (:error ready) :worker-start-failed) :exit (:exit fin) :stderr (:stderr fin)})))
        (catch Throwable t
          (try (.delete (io/file err-path)) (catch Throwable _ nil))
          {:ok? false :error :worker-spawn-failed :message (or (.getMessage t) (str t))})))))

(defn- ^:seon.flow/edge ask-worker-once! [h texts]
  (try
    (write-frame! (:in h) {:op :embed :texts (vec texts)})
    (let [resp (read-frame! (:out h))]
      (if (and (map? resp) (:ok? resp)) resp {:ok? false :error (or (:error resp) :worker-eof) :retryable? (= :eof resp)}))
    (catch Throwable t
      {:ok? false :error :worker-io-error :message (or (.getMessage t) (str t)) :retryable? true})))

(defn ^:seon.flow/edge embed-client
  "Creates a lazy, crash-recovering subprocess client for `target/seon embed-worker` (0 MB at rest)."
  {:malli/schema [:function [:=> [:cat] :map] [:=> [:cat :map] :map]] :cost/ticks 1000000}
  ([] (embed-client {}))
  ([opts]
   (let [mpath (if (contains? opts :model-path) (resolve-model-path (:model-path opts)) (resolve-model-path))
         argv (or (:argv opts) (default-worker-argv))
         state (volatile! nil) lock (Object.)]
     {:model-path mpath
      :model-id (when mpath (model-id mpath))
      :alive? #(locking lock (boolean (when-let [h @state] (.isAlive ^Process (:proc h)))))
      :close! #(locking lock (when-let [h @state] (vreset! state nil) (close-worker-handle! h false)))
      :embed! (fn [texts]
                (let [tv (vec texts)]
                  (if (empty? tv)
                    {:ok? true :dims 0 :vectors [] :tokens []}
                    (locking lock
                      (let [cur @state reused? (boolean (and cur (.isAlive ^Process (:proc cur))))
                            sr (if reused? {:ok? true :handle cur}
                                   (do (when cur (close-worker-handle! cur true) (vreset! state nil))
                                       (spawn-worker-handle! mpath argv)))]
                        (if-not (:ok? sr)
                          sr
                          (let [h (:handle sr) _ (vreset! state h) ans (ask-worker-once! h tv)]
                            (if (:ok? ans)
                              ans
                              (let [fin (close-worker-handle! h true)]
                                (vreset! state nil)
                                (if (and reused? (:retryable? ans))
                                  (let [sr2 (spawn-worker-handle! mpath argv)]
                                    (if-not (:ok? sr2)
                                      sr2
                                      (let [h2 (:handle sr2) _ (vreset! state h2) ans2 (ask-worker-once! h2 tv)]
                                        (if (:ok? ans2) ans2 (do (vreset! state nil) (merge ans2 (close-worker-handle! h2 true)))))))
                                  (merge ans fin)))))))))))})))