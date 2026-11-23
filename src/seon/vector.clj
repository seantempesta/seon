(ns seon.vector
  "Local Matryoshka vector pyramid (`libprobe.dylib` C SIMD — zero threads, safe in every process),
   content-addressed text extraction for current Seon schemas, and isolated `embed-worker` subprocess
   (`libseon-embed.dylib` + EmbeddingGemma-2 GGUF — loaded ONLY inside `serve!`, NEVER in root or ForkParent)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datahike.api :as d]
            [jolt.ffi :as ffi]
            [seon.store :as store])
  (:import [java.io DataInputStream DataOutputStream EOFException InputStream OutputStream]
           [java.security MessageDigest]
           [java.util.concurrent TimeUnit]))

;; ---- defaults & constants ----------------------------------------------------

(def default-coarse-dims 128)
(def default-shortlist 64)
(def default-batch-tokens 8192)
(def default-batch-texts 16)
(def default-max-tokens 1024)
(def default-threads 4)
(def texts-version "v1-titled")
(def query-prompt "task: search result | query: ")

;; ---- C SIMD pyramid bindings (libprobe.dylib: zero threads, safe everywhere) -

(when-not (ffi/find-symbol "seon_vec_pack")
  (ffi/load-library (str store/checkout-root "/target/lib/libprobe.dylib")))

(ffi/defcfn ^:private c-pack "seon_vec_pack" [:pointer :int :int :pointer :pointer] :void)
(ffi/defcfn ^:private c-coarse "seon_vec_coarse" [:pointer :long :int :pointer :int :pointer :pointer] :int)
(ffi/defcfn ^:private c-fine "seon_vec_fine" [:pointer :long :int :int :pointer :pointer] :void)
(ffi/defcfn ^:private c-f32 "seon_vec_f32" [:pointer :int :pointer] :void)

(defn- sanitize-floats [v]
  (let [n (count v)
        arr (float-array n)]
    (loop [i 0]
      (when (< i n)
        (let [x (double (nth v i))]
          (aset-float arr i (if (Double/isFinite x) (float x) (float 0.0))))
        (recur (inc i))))
    arr))

(defn pack
  "Packs unit float vector `v` into `{:seon.vector/f16 bytes :seon.vector/coarse bytes}` via
   `libprobe.dylib` `seon_vec_pack` (f16 full row + Matryoshka int8 coarse prefix with f32 scale)."
  {:malli/schema [:function
                  [:=> [:cat [:vector {:min 1} number?]]
                   [:map [:seon.vector/f16 bytes?] [:seon.vector/coarse bytes?]]]
                  [:=> [:cat [:vector {:min 1} number?] pos-int?]
                   [:map [:seon.vector/f16 bytes?] [:seon.vector/coarse bytes?]]]]
   :cost/ticks 2000000}
  ([v] (pack v default-coarse-dims))
  ([v coarse-dims]
   (let [n (count v)
         cd (min (long coarse-dims) n)
         farr (sanitize-floats v)]
     (ffi/with-arena [a]
       (let [vp (ffi/alloc a (* 4 n))
             fp (ffi/alloc a (* 2 n))
             cp (ffi/alloc a (+ cd 4))]
         (ffi/write-array vp :float farr 0)
         (c-pack vp n cd fp cp)
         {:seon.vector/f16 (ffi/read-array fp :int8 (* 2 n) 0)
          :seon.vector/coarse (ffi/read-array cp :int8 (+ cd 4) 0)})))))

(defn unpack
  "Unpacks a stored IEEE 754 f16 byte row into a float vector via `libprobe.dylib` `seon_vec_f32`."
  {:malli/schema [:=> [:cat bytes?] [:vector number?]]
   :cost/ticks 2000000}
  [f16]
  (let [blen (alength ^bytes f16)
        n (quot blen 2)]
    (if (zero? n)
      []
      (ffi/with-arena [a]
        (let [p (ffi/alloc a (max 2 blen))
              o (ffi/alloc a (* 4 n))]
          (ffi/write-array p :int8 f16 0)
          (c-f32 p n o)
          (vec (ffi/read-array o :float n 0)))))))

(defn coarse-top
  "Ranks `rows` (contiguous Matryoshka int8+scale coarse byte rows) against `query` (coarse bytes or
   float vector) via `libprobe.dylib` `seon_vec_coarse`, returning top-`k` `[[row-idx score] ...]`."
  {:malli/schema [:function
                  [:=> [:cat [:sequential bytes?] [:or bytes? [:vector {:min 1} number?]] nat-int?]
                   [:vector [:tuple :int number?]]]]
   :cost/ticks 10000000}
  [rows query k]
  (let [rv (vec rows)
        n (count rv)
        kk (min n (long k))]
    (if (or (zero? n) (zero? kk))
      []
      (let [w (alength ^bytes (first rv))
            cd (- w 4)]
        (if (<= cd 0)
          []
          (let [^bytes qbytes (if (bytes? query)
                                query
                                (:seon.vector/coarse (pack query cd)))]
            (when (not= w (alength qbytes))
              (throw (ex-info (str "seon.vector/coarse-top: query coarse width " (alength qbytes)
                                   " != stored width " w)
                              {:query-width (alength qbytes) :row-width w})))
            (ffi/with-arena [a]
              (let [cp (ffi/alloc a (* n w))
                    qp (ffi/alloc a w)
                    ip (ffi/alloc a (* 8 kk))
                    sp (ffi/alloc a (* 4 kk))]
                (dotimes [i n]
                  (ffi/write-bytes (ffi/slice cp (* i w)) (nth rv i)))
                (ffi/write-bytes qp qbytes)
                (let [m (c-coarse cp n cd qp kk ip sp)
                      idxs (ffi/read-array ip :int64 m 0)
                      scrs (ffi/read-array sp :float m 0)]
                  (mapv (fn [i] [(long (nth idxs i)) (double (nth scrs i))]) (range m)))))))))))

(defn fine-scores
  "Computes renormalized cosine similarity between float vector `q` and each f16 byte row in `f16-rows`
   (nil entries stay nil) over the first `fine-dims` dimensions via `libprobe.dylib` `seon_vec_fine`."
  {:malli/schema [:function
                  [:=> [:cat [:vector {:min 1} number?] [:sequential [:maybe bytes?]]]
                   [:vector [:maybe number?]]]
                  [:=> [:cat [:vector {:min 1} number?] [:sequential [:maybe bytes?]] pos-int?]
                   [:vector [:maybe number?]]]]
   :cost/ticks 10000000}
  ([q f16-rows] (fine-scores q f16-rows (count q)))
  ([q f16-rows fine-dims]
   (let [dims (count q)
         fd (min (long fine-dims) dims)
         expected-bytes (* 2 dims)
         rows (vec f16-rows)
         valid? (fn [r] (and (bytes? r) (= expected-bytes (alength ^bytes r))))
         have (filterv valid? rows)
         m (count have)]
     (if (zero? m)
       (mapv (constantly nil) rows)
       (let [qf-arr (sanitize-floats q)]
         (ffi/with-arena [a]
           (let [fp (ffi/alloc a (* m expected-bytes))
                 qf (ffi/alloc a (* 4 dims))
                 fs (ffi/alloc a (* 4 m))]
             (dotimes [j m]
               (ffi/write-bytes (ffi/slice fp (* j expected-bytes)) (nth have j)))
             (ffi/write-array qf :float qf-arr 0)
             (c-fine fp m dims fd qf fs)
             (let [ss (ffi/read-array fs :float m 0)]
               (first
                (reduce (fn [[out j] r]
                          (if (valid? r)
                            [(conj out (double (nth ss j))) (inc j)]
                            [(conj out nil) j]))
                        [[] 0]
                        rows))))))))))

;; ---- Datahike vector lookup, Datalog `near`, and `semantic-scores-for` -------

(defn- stored-val [db e a]
  (:v (first (d/datoms db :eavt e a))))

(defn- resolve-eid [db ref-or-eid]
  (cond
    (int? ref-or-eid) ref-or-eid
    (and (vector? ref-or-eid) (= 2 (count ref-or-eid)) (keyword? (first ref-or-eid)))
    (when (contains? (:schema db) (first ref-or-eid))
      (:e (first (d/datoms db :avet (first ref-or-eid) (second ref-or-eid)))))
    :else nil))

(defn vector-of
  "Returns entity `eid-or-ref`'s unpacked float vector on `db`, or nil when unembedded."
  {:malli/schema [:=> [:cat :seon.host/db :any] [:maybe [:vector number?]]]
   :cost/ticks 2000000}
  [db eid-or-ref]
  (when (and db (contains? (:schema db) :seon.vector/of))
    (when-let [eid (resolve-eid db eid-or-ref)]
      (when-let [ve (:e (first (d/datoms db :avet :seon.vector/of eid)))]
        (when-let [f16 (stored-val db ve :seon.vector/f16)]
          (unpack f16))))))

(defn indexed-count
  "Counts stored `:seon.vector/of` entities on `db` in one linear AEVT scan."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db]] :int]
   :cost/ticks 2000000}
  [db]
  (if (and db (contains? (:schema db) :seon.vector/of))
    (count (d/datoms db :aevt :seon.vector/of))
    0))

(defn- merge-join-coarse-rows [db keep? self]
  (let [cv (vec (d/datoms db :aevt :seon.vector/coarse))
        n (count cv)]
    (loop [os (seq (d/datoms db :aevt :seon.vector/of))
           i 0
           ves (transient [])
           cbytes (transient [])
           es (transient [])]
      (if-let [o (first os)]
        (let [oe (:e o)
              i (loop [j i] (if (and (< j n) (< (:e (nth cv j)) oe)) (recur (inc j)) j))
              hit? (and (< i n) (= oe (:e (nth cv i))) (keep? (:v o)) (not= self (:v o)))]
          (recur (next os) i
                 (cond-> ves hit? (conj! oe))
                 (cond-> cbytes hit? (conj! (:v (nth cv i))))
                 (cond-> es hit? (conj! (:v o)))))
        [(persistent! ves) (persistent! cbytes) (persistent! es)]))))

(defonce ^:private ^:seon.flow/edge coarse-rows-cache (store/lru-cache 16))

(defn- cached-coarse-rows [db of self]
  (let [compute (fn []
                  (let [keep? (if (and of (contains? (:schema db) of))
                                (into #{} (map :e) (d/datoms db :aevt of))
                                (if of #{} any?))]
                    (merge-join-coarse-rows db keep? self)))
        ck (when (instance? datahike.db.DB db)
             [(get-in db [:config :store :id])
              (get-in db [:config :branch] :main)
              (:max-tx db)
              of
              self])]
    (if ck
      (store/lru-get! coarse-rows-cache 16 ck compute)
      (compute))))

(defn near
  "Matryoshka 2-stage pyramid semantic search — usable directly OR as a Datahike Datalog function:
     `[(seon.vector/near $ ?q 10 {:of :seon.code.var/name}) [[?e ?score]]]`
   `q` may be a float vector, an integer entity ID, or a lookup ref `[attr val]`.
   Returns `[[eid cosine-score] ...]` sorted best-first (tie-broken by `eid` ascending)."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db :any nat-int?] [:vector [:tuple :int number?]]]
                  [:=> [:cat :seon.host/db :any nat-int?
                        [:map
                         [:of {:optional true} [:maybe :qualified-keyword]]
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
         (let [[ves cbytes es] (cached-coarse-rows db of self)
               n (count cbytes)]
           (if (zero? n)
             []
             (let [dims (count qv)
                   first-f16 (stored-val db (first ves) :seon.vector/f16)
                   stored-dims (if (bytes? first-f16) (quot (alength ^bytes first-f16) 2) 0)
                   _ (when (and (pos? stored-dims) (not= dims stored-dims))
                       (throw (ex-info (str "seon.vector/near: query dims " dims " != stored dims " stored-dims)
                                       {:dims dims :stored stored-dims})))
                   sl (min n (max (long k) (long (or shortlist default-shortlist))))
                   fd (min dims (long (or fine-dims dims)))
                   short-hits (coarse-top cbytes qv sl)
                   hit-idxs (mapv first short-hits)
                   hit-es (mapv #(nth es %) hit-idxs)
                   hit-f16s (mapv #(stored-val db (nth ves %) :seon.vector/f16) hit-idxs)
                   fscores (fine-scores qv hit-f16s fd)]
               (->> (map (fn [e s] (when (some? s) [(- (double s)) e])) hit-es fscores)
                    (remove nil?)
                    sort
                    (take k)
                    (mapv (fn [[neg-s e]] [e (- (double neg-s))])))))))))))

(defn semantic-scores-for
  "Computes `{eid cosine-score}` for candidate entity IDs/lookup-refs `eids` against `query-vec`
   in one batched C SIMD `seon_vec_fine` call (for `rank-candidates` 4th `:semantic` signal)."
  {:malli/schema [:function
                  [:=> [:cat [:maybe :seon.host/db] [:maybe [:vector number?]] [:sequential :any]]
                   [:map-of :any number?]]
                  [:=> [:cat [:maybe :seon.host/db] [:maybe [:vector number?]] [:sequential :any] pos-int?]
                   [:map-of :any number?]]]
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
        (reduce-kv (fn [acc idx s]
                     (if (some? s) (assoc! acc (nth ev idx) (double s)) acc))
                   (transient {})
                   scrs))))))

(defn describe-hits
  "Enriches `[[eid score] ...]` from `near` into human/agent-friendly `{:eid :score :kind :ref :label}` maps."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential [:tuple :int number?]]] [:vector :map]]
   :cost/ticks 10000000}
  [db hits]
  (mapv (fn [[e s]]
          (let [p (d/pull db '[:seon.code.var/name :seon.code.doc/id :seon.code.doc/heading
                               :seon.schema/key :seon.task/id :seon.task/title
                               :seon.error/id :seon.error/kind :seon.error/message] e)
                [kind ref label]
                (cond
                  (:seon.code.var/name p) [:var [:seon.code.var/name (:seon.code.var/name p)] (str (:seon.code.var/name p))]
                  (:seon.code.doc/id p) [:doc [:seon.code.doc/id (:seon.code.doc/id p)]
                                         (str (:seon.code.doc/id p) (when (:seon.code.doc/heading p) (str " — " (:seon.code.doc/heading p))))]
                  (:seon.schema/key p) [:schema [:seon.schema/key (:seon.schema/key p)] (str (:seon.schema/key p))]
                  (:seon.task/id p) [:task [:seon.task/id (:seon.task/id p)] (str (:seon.task/id p) " — " (:seon.task/title p))]
                  (:seon.error/id p) [:error [:seon.error/id (:seon.error/id p)]
                                      (str (:seon.error/kind p :error) ": " (let [m (str (:seon.error/message p))]
                                                                             (if (> (count m) 80) (str (subs m 0 80) "…") m)))]
                  :else [:entity [:db/id e] (str "entity/" e)])]
            {:eid e :score (double s) :kind kind :ref ref :label label}))
        hits))

;; ---- EmbeddingGemma-2 prompt formatting, content digest & due-entities -------

(defn document-text
  "Formats a document for EmbeddingGemma-2 (`\"title: <title> | text: <text>\"`)."
  {:malli/schema [:=> [:cat [:maybe :string] :string] :string]
   :cost/ticks 50000}
  [title text]
  (str "title: " (if (str/blank? title) "none" title) " | text: " text))

(defn query-text
  "Formats a search query or task intent for EmbeddingGemma-2 (`\"task: search result | query: <q>\"`)."
  {:malli/schema [:=> [:cat :string] :string]
   :cost/ticks 50000}
  [q]
  (str query-prompt q))

(defn text-digest
  "Returns deterministic 64-char lowercase SHA-256 hex digest of UTF-8 string `s`."
  {:malli/schema [:=> [:cat :string] :string]
   :cost/ticks 200000}
  [^String s]
  (let [md (MessageDigest/getInstance "SHA-256")
        bs (.digest md (.getBytes s "UTF-8"))
        sb (StringBuilder. 64)]
    (dotimes [i (alength bs)]
      (let [b (bit-and 0xff (aget bs i))]
        (when (< b 16) (.append sb \0))
        (.append sb (Integer/toHexString b))))
    (.toString sb)))

(defn model-id
  "Derives stable vector `:seon.vector/model` identifier from model file path + `texts-version`."
  {:malli/schema [:=> [:cat :string] :string]
   :cost/ticks 100000}
  [path]
  (let [nm (.getName (io/file path))
        dot (str/last-index-of nm ".")
        stem (if (and dot (pos? dot)) (subs nm 0 dot) nm)]
    (str stem "+" texts-version)))

(defn resolve-model-path
  "Returns existing GGUF model path from `override` or `SEON_EMBED_MODEL`, else nil."
  {:malli/schema [:function
                  [:=> [:cat] [:maybe :string]]
                  [:=> [:cat [:maybe :string]] [:maybe :string]]]
   :cost/ticks 100000}
  ([] (resolve-model-path (System/getenv "SEON_EMBED_MODEL")))
  ([path]
   (when (and (string? path) (not (str/blank? path)))
     (let [f (io/file path)]
       (when (.isFile f) (.getAbsolutePath f))))))

(defn extract-entity-text
  "Extracts `{:eid :kind :text :digest}` for one entity via O(1) `:eavt`/`:avet` seeks, or nil."
  {:malli/schema [:=> [:cat :seon.host/db :int] [:maybe :map]]
   :cost/ticks 500000}
  [db eid]
  (let [sch (:schema db)
        has? #(contains? sch %)
        vname (when (has? :seon.code.var/name) (stored-val db eid :seon.code.var/name))
        dbody (when (and (nil? vname) (has? :seon.code.doc/body)) (stored-val db eid :seon.code.doc/body))
        skey (when (and (nil? vname) (nil? dbody) (has? :seon.schema/key)) (stored-val db eid :seon.schema/key))
        tid (when (and (nil? vname) (nil? dbody) (nil? skey) (has? :seon.task/id)) (stored-val db eid :seon.task/id))
        emsg (when (and (nil? vname) (nil? dbody) (nil? skey) (nil? tid) (has? :seon.error/message))
               (stored-val db eid :seon.error/message))]
    (cond
      vname
      (let [fk (when (has? :seon.code.var/form) (stored-val db eid :seon.code.var/form))
            fe (when (and fk (has? :seon.code.form/key)) (:e (first (d/datoms db :avet :seon.code.form/key fk))))
            src (or (when fe (stored-val db fe :seon.code.form/src))
                    (when (has? :seon.code.var/doc) (stored-val db eid :seon.code.var/doc)))]
        (when-not (str/blank? (str src))
          (let [t (document-text (str vname) (str src))]
            {:eid eid :kind :var :text t :digest (text-digest t)})))

      (and dbody (not (str/blank? (str dbody))))
      (let [head (or (when (has? :seon.code.doc/heading) (stored-val db eid :seon.code.doc/heading))
                     (when (has? :seon.code.doc/id) (stored-val db eid :seon.code.doc/id)))
            t (document-text (when head (str head)) (str dbody))]
        {:eid eid :kind :doc :text t :digest (text-digest t)})

      skey
      (let [sdoc (when (has? :seon.schema/doc) (stored-val db eid :seon.schema/doc))
            sform (when (has? :seon.schema/form) (stored-val db eid :seon.schema/form))
            body (str/trim (str (when-not (str/blank? (str sdoc)) (str sdoc "\n"))
                                (when (some? sform) (pr-str sform))))]
        (when-not (str/blank? body)
          (let [t (document-text (str skey) body)]
            {:eid eid :kind :schema :text t :digest (text-digest t)})))

      tid
      (let [title (or (when (has? :seon.task/title) (stored-val db eid :seon.task/title)) (str tid))
            plan (when (has? :seon.task/plan) (stored-val db eid :seon.task/plan))
            body (str/trim (str title (when-not (str/blank? (str plan)) (str "\n" plan))))]
        (when-not (str/blank? body)
          (let [t (query-text body)]
            {:eid eid :kind :task :text t :digest (text-digest t)})))

      (and emsg (not (str/blank? (str emsg))))
      (let [ekind (when (has? :seon.error/kind) (stored-val db eid :seon.error/kind))
            t (document-text (str (or ekind :seon.error/entity)) (str emsg))]
        {:eid eid :kind :error :text t :digest (text-digest t)})

      :else nil)))

(defn- scan-attr-map [db attr]
  (if (contains? (:schema db) attr)
    (persistent! (reduce (fn [m dt] (assoc! m (:e dt) (:v dt))) (transient {}) (d/datoms db :aevt attr)))
    {}))

(defn- collect-due-vars [db of->text of->model cur-model]
  (let [fk-by-e (scan-attr-map db :seon.code.form/key)
        fsrc-by-e (scan-attr-map db :seon.code.form/src)
        fkey->src (persistent!
                   (reduce-kv (fn [m fe fk]
                                (if-let [s (get fsrc-by-e fe)] (assoc! m fk s) m))
                              (transient {})
                              fk-by-e))
        vnames (scan-attr-map db :seon.code.var/name)
        vforms (scan-attr-map db :seon.code.var/form)
        vdocs (scan-attr-map db :seon.code.var/doc)]
    (persistent!
     (reduce-kv
      (fn [acc e vname]
        (let [src (or (when-let [fk (get vforms e)] (get fkey->src fk)) (get vdocs e))]
          (if (str/blank? (str src))
            acc
            (let [t (document-text (str vname) (str src))
                  h (text-digest t)]
              (if (and (= cur-model (get of->model e)) (= h (get of->text e)))
                acc
                (conj! acc {:eid e :kind :var :text t :digest h}))))))
      (transient [])
      vnames))))

(defn- collect-due-docs [db of->text of->model cur-model]
  (let [bodies (scan-attr-map db :seon.code.doc/body)
        heads (scan-attr-map db :seon.code.doc/heading)
        ids (scan-attr-map db :seon.code.doc/id)]
    (persistent!
     (reduce-kv
      (fn [acc e body]
        (if (str/blank? (str body))
          acc
          (let [head (or (get heads e) (get ids e))
                t (document-text (when head (str head)) (str body))
                h (text-digest t)]
            (if (and (= cur-model (get of->model e)) (= h (get of->text e)))
              acc
              (conj! acc {:eid e :kind :doc :text t :digest h})))))
      (transient [])
      bodies))))

(defn- collect-due-schemas [db of->text of->model cur-model]
  (let [skeys (scan-attr-map db :seon.schema/key)
        sdocs (scan-attr-map db :seon.schema/doc)
        sforms (scan-attr-map db :seon.schema/form)]
    (persistent!
     (reduce-kv
      (fn [acc e skey]
        (let [d (get sdocs e)
              f (get sforms e)
              body (str/trim (str (when-not (str/blank? (str d)) (str d "\n")) (when (some? f) (pr-str f))))]
          (if (str/blank? body)
            acc
            (let [t (document-text (str skey) body)
                  h (text-digest t)]
              (if (and (= cur-model (get of->model e)) (= h (get of->text e)))
                acc
                (conj! acc {:eid e :kind :schema :text t :digest h}))))))
      (transient [])
      skeys))))

(defn- collect-due-tasks [db of->text of->model cur-model]
  (let [tids (scan-attr-map db :seon.task/id)
        titles (scan-attr-map db :seon.task/title)
        plans (scan-attr-map db :seon.task/plan)]
    (persistent!
     (reduce-kv
      (fn [acc e tid]
        (let [title (or (get titles e) (str tid))
              plan (get plans e)
              body (str/trim (str title (when-not (str/blank? (str plan)) (str "\n" plan))))]
          (if (str/blank? body)
            acc
            (let [t (query-text body)
                  h (text-digest t)]
              (if (and (= cur-model (get of->model e)) (= h (get of->text e)))
                acc
                (conj! acc {:eid e :kind :task :text t :digest h}))))))
      (transient [])
      tids))))

(defn- collect-due-errors [db of->text of->model cur-model]
  (let [msgs (scan-attr-map db :seon.error/message)
        kinds (scan-attr-map db :seon.error/kind)]
    (persistent!
     (reduce-kv
      (fn [acc e msg]
        (if (str/blank? (str msg))
          acc
          (let [t (document-text (str (or (get kinds e) :seon.error/entity)) (str msg))
                h (text-digest t)]
            (if (and (= cur-model (get of->model e)) (= h (get of->text e)))
              acc
              (conj! acc {:eid e :kind :error :text t :digest h})))))
      (transient [])
      msgs))))

(defn due-entities
  "Finds entities on `db` missing a vector or whose text digest / `cur-model` changed, bounded by `limit`.
   Returns `[{:eid :kind :text :digest} ...]` in deterministic entity-ID order."
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
          all (into []
                    (concat (collect-due-vars db of->text of->model cur-model)
                            (collect-due-docs db of->text of->model cur-model)
                            (collect-due-schemas db of->text of->model cur-model)
                            (collect-due-tasks db of->text of->model cur-model)
                            (collect-due-errors db of->text of->model cur-model)))]
      (->> all
           (sort-by :eid)
           (take (long limit))
           vec))))

(def ^:private tracked-text-attrs
  #{:seon.code.form/src :seon.code.var/name :seon.code.var/form :seon.code.var/doc
    :seon.code.doc/body :seon.code.doc/heading
    :seon.schema/doc :seon.schema/form
    :seon.task/title :seon.task/plan
    :seon.error/message :seon.error/kind})

(defn dirty-eids-from-tx-data
  "Extracts embeddable entity IDs affected by incremental `tx-data` datoms in O(|tx-data|)."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :any]] [:vector :int]]
   :cost/ticks 2000000}
  [db tx-data]
  (let [sch (:schema db)
        has-vform? (contains? sch :seon.code.var/form)
        has-fkey? (contains? sch :seon.code.form/key)]
    (vec
     (persistent!
      (reduce (fn [acc dt]
                (let [a (:a dt)
                      e (:e dt)]
                  (cond
                    (not (contains? tracked-text-attrs a)) acc
                    (= :seon.code.form/src a)
                    (if (and has-fkey? has-vform?)
                      (if-let [fk (stored-val db e :seon.code.form/key)]
                        (reduce (fn [s vdt] (conj! s (:e vdt))) acc (d/datoms db :avet :seon.code.var/form fk))
                        acc)
                      acc)
                    (int? e) (conj! acc e)
                    :else acc)))
              (transient #{})
              tx-data)))))

(defn tx-data-for-batch
  "Builds Datahike upsert tx-data for embedded `items` (`[{:eid :digest} ...]`), `vectors`,
   optional `tokens`, and `cur-model`. Output passes `seon.store/validate-tx-data!`."
  {:malli/schema [:function
                  [:=> [:cat [:sequential :map] [:sequential [:vector {:min 1} number?]]
                        [:maybe [:sequential [:maybe :int]]] :string]
                   [:vector :map]]]
   :cost/ticks 20000000}
  [items vectors tokens cur-model]
  (let [iv (vec items)
        vv (vec vectors)
        tv (when (seq tokens) (vec tokens))
        n (min (count iv) (count vv))]
    (vec
     (keep (fn [i]
             (let [it (nth iv i)
                   v (nth vv i)
                   tok (when (and tv (< i (count tv))) (nth tv i))]
               (when (and (:eid it) (seq v))
                 (cond-> (assoc (pack v)
                                :seon.vector/of (:eid it)
                                :seon.vector/model cur-model
                                :seon.vector/text (str (or (:digest it) "")))
                   (integer? tok) (assoc :seon.vector/tokens (long tok))))))
           (range n)))))

;; ---- length-prefixed EDN stream framing --------------------------------------

(defn ^:seon.flow/edge write-frame!
  "Writes one `[int32 len][UTF-8 EDN]` frame onto `OutputStream` and flushes."
  {:malli/schema [:function [:=> [:cat :any :any] :boolean]]
   :cost/ticks 500000}
  [^OutputStream out val]
  (let [bs (.getBytes (binding [*print-length* nil *print-level* nil *print-namespace-maps* false]
                        (pr-str val))
                      "UTF-8")
        dos (DataOutputStream. out)]
    (.writeInt dos (alength bs))
    (.write dos bs)
    (.flush dos)
    true))

(defn ^:seon.flow/edge read-frame!
  "Reads one `[int32 len][UTF-8 EDN]` frame from `InputStream`, returning `:eof` on clean EOF."
  {:malli/schema [:function [:=> [:cat :any] :any]]
   :cost/ticks 500000}
  [^InputStream in]
  (let [dis (DataInputStream. in)]
    (try
      (let [len (.readInt dis)]
        (when (or (neg? len) (> len (* 64 1024 1024)))
          (throw (ex-info (str "Invalid embed frame length: " len) {:len len})))
        (let [bs (byte-array len)]
          (.readFully dis bs)
          (edn/read-string (String. bs "UTF-8"))))
      (catch EOFException _ :eof))))

;; ---- isolated `embed-worker` runtime (libseon-embed.dylib ONLY here!) --------

(defn- ^:seon.flow/edge open-native-embedder!
  "Loads `libseon-embed.dylib` and opens `model-path` via `seon_embed_open`.
   MUST ONLY BE CALLED inside the exec'd `target/seon embed-worker` child process!"
  [model-path {:keys [lib-path batch-tokens batch-texts max-tokens threads]
               :or {batch-tokens default-batch-tokens
                    batch-texts default-batch-texts
                    max-tokens default-max-tokens
                    threads default-threads}}]
  (let [lp (or lib-path
               (not-empty (System/getenv "SEON_EMBED_LIB"))
               (str store/checkout-root "/target/lib/libseon-embed.dylib"))]
    (cond
      (not (and model-path (.isFile (io/file model-path))))
      {:ok? false :error :model-missing :path model-path}

      (not (.isFile (io/file lp)))
      {:ok? false :error :embed-lib-missing :path lp}

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
              {:ok? true
               :dims dims
               :model-id (model-id model-path)
               :embed-fn (fn [texts]
                           (let [tv (vec texts)
                                 n (count tv)]
                             (if (zero? n)
                               {:ok? true :dims dims :vectors [] :tokens []}
                               (let [bs (mapv #(.getBytes ^String (str %) "UTF-8") tv)
                                     offs (long-array (reductions + 0 (map #(alength ^bytes %) bs)))
                                     total (aget offs n)]
                                 (ffi/with-arena [a]
                                   (let [tp (ffi/alloc a (max 1 total))
                                         op (ffi/alloc a (* 8 (inc n)))
                                         out (ffi/alloc a (max 4 (* 4 n dims)))
                                         np (ffi/alloc a (max 4 (* 4 n)))]
                                     (dotimes [i n]
                                       (ffi/write-bytes (ffi/slice tp (aget offs i)) (nth bs i)))
                                     (ffi/write-array op :int64 offs 0)
                                     (let [rc (c-batch h tp op n (int max-tokens) out np)]
                                       (if (neg? rc)
                                         {:ok? false :error :encode-failed :code rc}
                                         {:ok? true
                                          :dims dims
                                          :vectors (mapv #(vec (ffi/read-array out :float dims (* 4 dims %))) (range n))
                                          :tokens (vec (ffi/read-array np :int32 n 0))}))))))))
               :close-fn (fn [] (try (c-close h) (catch Throwable _ nil)))})))))))

(defn ^:seon.flow/edge serve-loop!
  "Pure framed protocol loop over `in`/`out` streams using `embedder` (`{:dims :model-id :embed-fn :close-fn}`).
   Emits ready frame, serves `:embed`/`:ping`/`:quit`, and closes cleanly on stdin EOF."
  {:malli/schema [:function [:=> [:cat :any :any :map] :map]]
   :cost/ticks 5000000}
  [^InputStream in ^OutputStream out {:keys [dims model-id embed-fn close-fn]}]
  (try
    (write-frame! out {:ok? true :ready? true :dims dims :model-id model-id})
    (loop [served 0]
      (let [req (read-frame! in)]
        (cond
          (= :eof req) {:ok? true :served served :reason :eof}
          (= :quit (:op req)) (do (write-frame! out {:ok? true :bye? true})
                                  {:ok? true :served served :reason :quit})
          (= :ping (:op req)) (do (write-frame! out {:ok? true :ready? true :dims dims})
                                  (recur served))
          (= :embed (:op req))
          (let [ans (try (embed-fn (:texts req))
                         (catch Throwable t {:ok? false :error :embed-exception :message (or (.getMessage t) (str t))}))]
            (write-frame! out ans)
            (recur (inc served)))
          :else
          (do (write-frame! out {:ok? false :error :unknown-op :op (:op req)})
              (recur served)))))
    (finally
      (when close-fn (close-fn)))))

(defn ^:seon.flow/edge serve!
  "Entrypoint for exec'd `target/seon embed-worker` child process.
   Loads `libseon-embed.dylib`, opens `SEON_EMBED_MODEL`, serves framed EDN on stdin/stdout, and exits 0 on EOF."
  {:malli/schema [:function [:=> [:cat] :any] [:=> [:cat :map] :any]]
   :cost/ticks 5000000}
  ([] (serve! {}))
  ([opts]
   (let [raw-out System/out
         raw-in System/in
         mpath (resolve-model-path (:model-path opts))]
     (binding [*out* (java.io.OutputStreamWriter. System/err)]
       (let [emb (open-native-embedder! mpath opts)]
         (if-not (:ok? emb)
           (do (write-frame! raw-out emb)
               emb)
           (serve-loop! raw-in raw-out emb)))))))

;; ---- parent-side lazy `embed-client` (exec'd subprocess, 0 MB at rest) -------

(defn- default-worker-argv []
  (let [env-bin (not-empty (System/getenv "SEON_EMBED_WORKER_BIN"))
        seon-bin (str store/checkout-root "/target/seon")
        jolt-bin (str store/checkout-root "/target/jolt/jolt")]
    (cond
      (and env-bin (.canExecute (io/file env-bin))) [env-bin "embed-worker"]
      (.canExecute (io/file seon-bin)) [seon-bin "embed-worker"]
      (.canExecute (io/file jolt-bin)) [jolt-bin "-m" "seon.main" "embed-worker"]
      :else nil)))

(defn- ^:seon.flow/edge tail-file [path max-bytes]
  (try
    (let [f (io/file path)]
      (if (.isFile f)
        (let [s (slurp f)]
          (if (> (count s) max-bytes) (subs s (- (count s) max-bytes)) s))
        ""))
    (catch Throwable _ "")))

(defn ^:seon.flow/edge embed-client
  "Creates a lazy, crash-recovering subprocess client for `target/seon embed-worker`.
   Never spawns until first `:embed!` call; `:close!` closes stdin so worker exits 0 (0 MB held at rest)."
  {:malli/schema [:function [:=> [:cat] :map] [:=> [:cat :map] :map]]
   :cost/ticks 1000000}
  ([] (embed-client {}))
  ([opts]
   (let [mpath (if (contains? opts :model-path)
                 (resolve-model-path (:model-path opts))
                 (resolve-model-path))
         mid (when mpath (model-id mpath))
         argv (or (:argv opts) (default-worker-argv))
         state (volatile! nil)
         lock (Object.)
         close-handle! (fn [h force?]
                         (when h
                           (let [^Process p (:proc h)]
                             (try (.close ^OutputStream (:in h)) (catch Throwable _ nil))
                             (when force? (try (.destroyForcibly p) (catch Throwable _ nil)))
                             (let [exited? (try (.waitFor p 2000 TimeUnit/MILLISECONDS) (catch Throwable _ false))]
                               (when-not exited?
                                 (try (.destroyForcibly p) (catch Throwable _ nil))
                                 (try (.waitFor p 500 TimeUnit/MILLISECONDS) (catch Throwable _ nil))))
                             (try (.close ^InputStream (:out h)) (catch Throwable _ nil))
                             (let [stderr (tail-file (:err-path h) 2048)]
                               (try (.delete (io/file (:err-path h))) (catch Throwable _ nil))
                               {:exit (try (.exitValue p) (catch Throwable _ -1)) :stderr stderr}))))
         start-worker! (fn []
                         (cond
                           (nil? mpath) {:ok? false :error :model-missing}
                           (empty? argv) {:ok? false :error :worker-binary-missing}
                           :else
                           (let [tmp-dir (io/file store/checkout-root "target/tmp")
                                 _ (.mkdirs tmp-dir)
                                 err-path (str (.getAbsolutePath tmp-dir) "/embed-worker-" (random-uuid) ".err")
                                 pb (doto (ProcessBuilder. ^java.util.List (vec argv))
                                      (.directory (io/file store/checkout-root))
                                      (.redirectError (io/file err-path)))]
                             (let [env (.environment pb)]
                               (.put env "SEON_EMBED_MODEL" mpath)
                               (.put env "SEON_CHECKOUT" store/checkout-root))
                             (try
                               (let [p (.start pb)
                                     h {:proc p :in (.getOutputStream p) :out (.getInputStream p) :err-path err-path}
                                     ready (try (read-frame! (:out h)) (catch Throwable t {:ok? false :error (.getMessage t)}))]
                                 (if (and (map? ready) (:ok? ready) (:ready? ready))
                                   (do (vreset! state (assoc h :dims (:dims ready)))
                                       {:ok? true :handle (assoc h :dims (:dims ready))})
                                   (let [fin (close-handle! h true)]
                                     {:ok? false :error (or (:error ready) :worker-start-failed)
                                      :exit (:exit fin) :stderr (:stderr fin)})))
                               (catch Throwable t
                                 (try (.delete (io/file err-path)) (catch Throwable _ nil))
                                 {:ok? false :error :worker-spawn-failed :message (or (.getMessage t) (str t))})))))
         ask-once! (fn [h texts]
                     (try
                       (write-frame! (:in h) {:op :embed :texts (vec texts)})
                       (let [resp (read-frame! (:out h))]
                         (if (and (map? resp) (:ok? resp))
                           resp
                           {:ok? false :error (or (:error resp) :worker-eof) :retryable? (= :eof resp)}))
                       (catch Throwable t
                         {:ok? false :error :worker-io-error :message (or (.getMessage t) (str t)) :retryable? true})))]
     {:model-path mpath
      :model-id mid
      :alive? (fn []
                (locking lock
                  (boolean (when-let [h @state] (.isAlive ^Process (:proc h))))))
      :close! (fn []
                (locking lock
                  (when-let [h @state]
                    (vreset! state nil)
                    (close-handle! h false))))
      :embed! (fn [texts]
                (let [tv (vec texts)]
                  (if (empty? tv)
                    {:ok? true :dims 0 :vectors [] :tokens []}
                    (locking lock
                      (let [cur @state
                            reused? (boolean (and cur (.isAlive ^Process (:proc cur))))
                            start-res (if reused? {:ok? true :handle cur} (do (when cur (close-handle! cur true) (vreset! state nil))
                                                                              (start-worker!)))]
                        (if-not (:ok? start-res)
                          start-res
                          (let [h (:handle start-res)
                                ans (ask-once! h tv)]
                            (if (:ok? ans)
                              ans
                              (let [fin (close-handle! h true)]
                                (vreset! state nil)
                                ;; Retry once cleanly if an idle worker died before receiving the batch
                                (if (and reused? (:retryable? ans))
                                  (let [sr2 (start-worker!)]
                                    (if-not (:ok? sr2)
                                      sr2
                                      (let [h2 (:handle sr2)
                                            ans2 (ask-once! h2 tv)]
                                        (if (:ok? ans2)
                                          ans2
                                          (let [fin2 (close-handle! h2 true)]
                                            (vreset! state nil)
                                            (merge ans2 fin2))))))
                                  (merge ans fin)))))))))))})))

(defn ^:seon.flow/edge remote-embed-search!
  "Queries the standing Seon daemon's `:op :embed-search` over loopback HTTP (safe from forked agents)."
  {:malli/schema [:function [:=> [:cat :string pos-int? :map] :map]]
   :cost/ticks 5000000}
  [query k opts]
  (let [port (or (:port opts) (System/getenv "SEON_PORT") 9998)
        url (str "http://127.0.0.1:" port "/op")
        payload (pr-str {:op :embed-search :query query :k (long k) :opts (dissoc (or opts {}) :port)})
        post-fn (try (requiring-resolve 'seon.model/http-post!) (catch Throwable _ nil))]
    (if-not post-fn
      {:ok? false :error :http-client-unavailable :results []}
      (try
        (let [resp (post-fn url [["Content-Type" "application/edn"]] payload)]
          (if (= 200 (:status resp))
            (edn/read-string (:body resp))
            {:ok? false :error :http-error :status (:status resp) :results []}))
        (catch Throwable t
          {:ok? false :error :daemon-unreachable :message (or (.getMessage t) (str t)) :results []})))))
