(ns seon.vector
  "Semantic search, native to the store and its query language (docs/vector.md). Each form's,
  wiki section's, schema entry's and task plan's text is embedded once by EmbeddingGemma-2 in process (root's
  :embed effect over llama.cpp, native/embed.c), and the vector kept on that entity as facts
  (resources/seon/schemas/seon.vector.edn); a form is content-addressed, so an unchanged text is
  never embedded again. `near` is a Datalog function, similarity one more relation:

    [(seon.vector/near $ ?q 10 {:of :form/id}) [[?e ?score]]]

  It searches as a pyramid (a Matryoshka vector's prefix is itself an embedding): the coarse
  level ranks every vector's first dims as int8, the fine level reranks the shortlist at f16
  (native/vector.c)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [seon.lib :as lib] [seon.config :as cfg]
            [seon.step :as step] [seon.sock :as sock] [jolt.ffi :as ffi] [datahike.api :as d] [seon.db :as pdb] [clojure.string :as str] [seon.error :as err] [seon.source :as source]))

;; ---- the pyramid (native/vector.c, in libprobe: no threads, every process loads it) ------

(lib/defc c-pack "seon_vec_pack" [:pointer :int :int :pointer :pointer] :void)
(lib/defc c-coarse "seon_vec_coarse" [:pointer :long :int :pointer :int :pointer :pointer] :int)
(lib/defc c-fine "seon_vec_fine" [:pointer :long :int :int :pointer :pointer] :void)
(lib/defc c-f32 "seon_vec_f32" [:pointer :int :pointer] :void)

(defn- pack-dims
  "Unit vector v as its f16 row and its coarse row of cd dims (seon_vec_pack)."
  {:malli/schema [:=> [:cat [:vector {:min 1} number?] pos-int?] [:map [:seon.vector/f16 :seon.host/bytes] [:seon.vector/coarse :seon.host/bytes]]]}
  [v cd]
  (let [n (count v) cd (min cd n)]
    (ffi/with-arena [a]
      (let [vp (ffi/alloc a (* 4 n)) fp (ffi/alloc a (* 2 n)) cp (ffi/alloc a (+ cd 4))]
        (ffi/write-array vp :float (float-array v) 0)
        (c-pack vp n cd fp cp)
        {:seon.vector/f16 (ffi/read-array fp :int8 (* 2 n) 0)
         :seon.vector/coarse (ffi/read-array cp :int8 (+ cd 4) 0)}))))

(defn pack
  "Unit vector v (the model's whole width) as its stored facts: the whole vector as f16, and the
  coarse level, its first :seon.config.vector/coarse-dims dims renormalized as int8 and their
  scale."
  {:malli/schema [:=> [:cat [:vector {:min 1} number?]] [:map [:seon.vector/f16 :seon.host/bytes] [:seon.vector/coarse :seon.host/bytes]]]}
  [v] (pack-dims v (cfg/value :seon.config.vector/coarse-dims)))

(defn- unpack
  "A stored f16 row as the vector it holds."
  {:malli/schema [:=> [:cat :seon.host/bytes] [:vector number?]]}
  [f16]
  (let [n (quot (count f16) 2)]
    (ffi/with-arena [a]
      (let [p (ffi/alloc a (max 1 (count f16))) o (ffi/alloc a (max 4 (* 4 n)))]
        (ffi/write-array p :int8 f16 0)
        (c-f32 p n o)
        (vec (ffi/read-array o :float n 0))))))

(defn- stored
  "Entity e's stored value of attribute a on db, or nil: one EAVT seek."
  {:malli/schema [:=> [:cat :seon.host/db :int :qualified-keyword] :any]}
  [db e a] (:v (first (d/datoms db :eavt e a))))

(defn- vector-e
  "The vector entity of entity e on db (its :seon.vector/of identity, docs/vector.md#a-vector-is-its-own-entity), or nil: one AVET seek."
  {:malli/schema [:=> [:cat :seon.host/db :int] [:maybe :int]]}
  [db e] (:e (first (d/datoms db :avet :seon.vector/of e))))

(defn- stored-of
  "Attribute a of the vector entity of e on db, or nil: two seeks."
  {:malli/schema [:=> [:cat :seon.host/db :int :qualified-keyword] :any]}
  [db e a] (when-let [ve (vector-e db e)] (stored db ve a)))

(defn- vectors-of
  "{vector entity e} of every vector on db: one scan of :seon.vector/of, the join `near` and `due`
  make from a scan of another vector attribute back to the entity embedded."
  {:malli/schema [:=> [:cat :seon.host/db] [:map-of :int :int]]}
  [db] (into {} (map (juxt :e :v)) (d/datoms db :aevt :seon.vector/of)))

(defn vector-of
  "Entity e's vector on db, or nil when it has none."
  {:malli/schema [:=> [:cat :seon.host/db :int] [:maybe [:vector number?]]]}
  [db e] (some-> (stored-of db e :seon.vector/f16) unpack))

(defn- fine-scores
  "The cosine of vector q with the vector of each vector entity of ves on db (the fine level over the
  first fd dims), nil where ves holds nil: `scores`' work, for callers that hold the vector entities."
  {:malli/schema [:=> [:cat :seon.host/db [:vector {:min 1} number?] [:sequential [:maybe :int]] pos-int?] [:vector [:maybe number?]]]}
  [db q ves fd]
  (let [dims (count q) fd (min fd dims)
        rows (mapv #(when % (stored db % :seon.vector/f16)) ves)
        have (filterv some? rows) m (count have)]
    (if (zero? m)
      (mapv (constantly nil) ves)
      (ffi/with-arena [a]
        (let [fp (ffi/alloc a (* m 2 dims)) qf (ffi/alloc a (* 4 dims)) fs (ffi/alloc a (* 4 m))]
          (dotimes [j m] (ffi/write-bytes (ffi/slice fp (* j 2 dims)) (nth have j)))
          (ffi/write-array qf :float (float-array q) 0)
          (c-fine fp m dims fd qf fs)
          (let [ss (ffi/read-array fs :float m 0)]
            (first (reduce (fn [[out j] r] (if r [(conj out (double (nth ss j))) (inc j)] [(conj out nil) j])) [[] 0] rows))))))))

(defn scores
  "The cosine of vector q with the vector of each entity of es on db, in es's order (the fine level,
  c-fine, over their first fd dims, each prefix renormalized: all of q's unless given), nil for an
  entity with no vector: `near`'s ranking of its shortlist, and seon.ctx.search/relevance's of a pool.
  Cost: two seeks an entity (its vector entity by :seon.vector/of, inlined: `vector-e` is a contracted call an entity, then the vector)."
  {:malli/schema [:function [:=> [:cat :seon.host/db [:vector {:min 1} number?] [:sequential :int]] [:vector [:maybe number?]]]
                  [:=> [:cat :seon.host/db [:vector {:min 1} number?] [:sequential :int] pos-int?] [:vector [:maybe number?]]]]}
  ([db q es] (scores db q es (count q)))
  ([db q es fd] (fine-scores db q (mapv #(:e (first (d/datoms db :avet :seon.vector/of %))) es) fd)))

(defn- coarse-rows
  "The coarse rows of db that embed an entity keep? accepts and self is not: [rows es], rows the coarse
  datoms (their :e the vector entity, their :v the bytes) and es the entities they embed, in step. A merge
  join of the scans of :seon.vector/of and :seon.vector/coarse, both in vector-entity order: advance the
  smaller, emit when equal, building nothing per row but the datoms the scans read (a vector left on
  another entity has no :seon.vector/of, and is no row)."
  {:malli/schema [:=> [:cat :seon.host/db :any [:maybe :int]] [:tuple :any :any]]}
  [db keep? self]
  (let [cv (vec (d/datoms db :aevt :seon.vector/coarse)) n (count cv)]
    ;; the coarse scan's place i, the rows and their entities, carried by the loop: values, no cell
    (loop [os (seq (d/datoms db :aevt :seon.vector/of)) i 0 rows (transient []) es (transient [])]
      (if-let [o (first os)]
        (let [oe (:e o)
              i (loop [i i] (if (and (< i n) (< (:e (nth cv i)) oe)) (recur (inc i)) i))
              hit? (and (< i n) (= oe (:e (nth cv i))) (keep? (:v o)) (not= self (:v o)))]
          (recur (next os) i (cond-> rows hit? (conj! (nth cv i))) (cond-> es hit? (conj! (:v o)))))
        [(persistent! rows) (persistent! es)]))))

;; derived data (docs/data-modeling.md#derived-data-is-a-cache-never-relied-on): [the db value's [:hash :max-tx], {[of self]
;; [rows es]}], the coarse rows of one db value and their :of filter, built once a value and replaced by the next value's, keyed as
;; seon.doc/text-index keys its index; a value read again (a page's search asks three times, one attribute each) reads no row again
(defonce ^:private kept-rows (atom nil))

(defn- rows-of
  "The coarse rows of db for near (`coarse-rows`), of the entities holding attribute of (all when nil) but self, kept for db's value
  (kept-rows): built at the first ask of a value, then read. [rows es]."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :qualified-keyword] [:maybe :int]] [:tuple :any :any]]}
  [db of self]
  (let [at [(:hash db) (:max-tx db)] k [of self] [held m] @kept-rows]
    (or (when (= held at) (get m k))
        (let [keep? (if of (into #{} (map :e) (d/datoms db :aevt of)) any?)
              r (coarse-rows db keep? self)]
          (swap! kept-rows (fn [[h m]] (if (= h at) [h (assoc m k r)] [at {k r}])))
          r))))

(defn near
  "The k entities of db nearest q, [[e score] ...] best first, score the cosine: a Datalog
  function, [(seon.vector/near $ ?q 10) [[?e ?score]]]. q is an entity with a vector (the k
  others nearest it) or a vector (a query's: seon.vector/query). Two levels: the coarse rows of
  every vector rank a shortlist, which the fine level ranks at f16 (docs/vector.md#the-pyramid).
  opts: :of an attribute the entities must have (:form/id, the program's forms), :shortlist and
  :fine-dims over their config keys (a shortlist of every vector ranks them all exactly). With no
  vectors, or an entity without one, it answers []."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db [:or :int [:vector number?]] nat-int?] [:vector [:tuple :int number?]]]
                  [:=> [:cat :seon.host/db [:or :int [:vector number?]] nat-int?
                        [:map [:of {:optional true} :qualified-keyword] [:shortlist {:optional true} pos-int?] [:fine-dims {:optional true} pos-int?]]]
                   [:vector [:tuple :int number?]]]]}
  ([db q k] (near db q k {}))
  ([db q k {:keys [of shortlist fine-dims]}]
   (let [self (when (int? q) q)
         qv (if self (vector-of db self) q)
         [rows rowe] (rows-of db of self)]
     (if (or (empty? rows) (empty? qv) (zero? k))
       []
       (let [n (count rows) w (count (:v (first rows))) cd (- w 4) dims (count qv)
             stored-dims (quot (count (stored db (:e (first rows)) :seon.vector/f16)) 2)
             _ (when (not= dims stored-dims)
                 (throw (ex-info (str "seon.vector/near: a query of " dims " dims over vectors of " stored-dims) {:dims dims :stored stored-dims})))
             sl (min n (max k (or shortlist (cfg/value :seon.config.vector/shortlist))))
             fd (min dims (or fine-dims (cfg/value :seon.config.vector/fine-dims)))]
         (ffi/with-arena [a]
           (let [cp (ffi/alloc a (* n w)) qp (ffi/alloc a w) ip (ffi/alloc a (* 8 sl)) sp (ffi/alloc a (* 4 sl))]
             (dotimes [i n] (ffi/write-bytes (ffi/slice cp (* i w)) (:v (nth rows i))))
             (ffi/write-bytes qp (:seon.vector/coarse (pack-dims qv cd)))
             (let [m (c-coarse cp n cd qp sl ip sp)
                   hit (vec (ffi/read-array ip :int64 m 0))
                   es (mapv rowe hit)]
               ;; best first; the entity id breaks a tie, so one db answers one order
               (->> (map (fn [e s] [(- s) e]) es (fine-scores db qv (mapv #(:e (nth rows %)) hit) fd))
                    sort (take k) (mapv (fn [[s e]] [e (- (double s))])))))))))))

(defmacro fused
  "{item score} of rankings fused by reciprocal rank, as an expression in the caller: a macro, not a
  function (a def'd fn escapes the contracts every function carries, a contracted one is a second
  call), so rrf costs the one contracted call it did."
  [rankings]
  `(let [k# (cfg/value :seon.config.vector/rrf-k)]
     (apply merge-with + {} (for [r# ~rankings]
                              (into {} (map-indexed (fn [i# x#] [x# (/ 1.0 (+ k# i# 1))])) r#)))))

(defn rrf-scores
  "Rankings fused by reciprocal rank (Cormack et al. 2009): {item score}, each item's score the sum
  of 1/(k + rank) over the rankings it is in, k :seon.config.vector/rrf-k."
  {:malli/schema [:=> [:cat [:sequential [:sequential :any]]] [:map-of :any number?]]}
  [rankings] (fused rankings))

(defn rrf
  "The items of rankings fused by reciprocal rank (rrf-scores), best first, the name breaking a tie.
  Measured fusing the call graph with embeddings (docs/vector.md#fused-with-the-call-graph);
  my.agent/search fuses exact text, meaning and references."
  {:malli/schema [:=> [:cat [:sequential [:sequential :any]]] [:vector :any]]}
  [rankings]
  (mapv peek (sort (map (fn [[x v]] [(- v) (str x) x]) (fused rankings)))))

;; ---- what is embedded --------------------------------------------------------------------
;; EmbeddingGemma's prompts (its model card; v1's script/embed/gemma_text.py): a document is
;; embedded with the document prompt, a task's plan as what a search asks.

(def query-prompt
  "The prompt before a query's text: the model card's search prompt, which ranked 0.33 at 5 against
  code retrieval's 0.24 on 20 queries (docs/vector.md#relevance)."
  "task: search result | query: ")

(defn document
  "A document's text as the model reads it: its title (a var's name, a section's heading, a schema
  key; \"none\" when it has none) in the prompt's title slot, then its text. Titled, functions and
  sections ranked 0.58 at 5 against 0.44 untitled (docs/vector.md#relevance)."
  {:malli/schema [:=> [:cat [:maybe :string] :string] :string]}
  [title text] (str "title: " (or title "none") " | text: " text))

(def ^:private texts
  "Each embedded kind: the attribute holding its text, and the text the model reads, of its title
  (`title`'s, nil when it has none) and its text."
  {:form/src (fn [title src] (document title src))
   :seon.doc/text (fn [title text] (document title text))
   ;; a schema's form, so a search finds schema keys by meaning too
   ;; (docs/vector.md#relevance)
   :schema/form (fn [title form] (document title form))
   :seon.task/plan (fn [_ plan] (str query-prompt plan))
   ;; a search a context holds: its query, as search embeds a query, so its render ranks by meaning once embedded
   :seon.search/query (fn [_ q] (str query-prompt q))})

(defn- titles
  "Each titled entity's title on db, {e title}: a form's by the first of its vars' names, a
  section's heading, a schema entry's key. One scan of each attribute, as `due` reads its others."
  {:malli/schema [:=> [:cat :seon.host/db] [:map-of :int :string]]}
  [db]
  (let [names (into {} (map (juxt :e :v)) (d/datoms db :aevt :var/name))]
    (merge (into {} (map (fn [[f ds]] [f (str (first (sort (keep (comp names :e) ds))))]))
                 (group-by :v (d/datoms db :aevt :var/form)))
           (into {} (map (juxt :e :v)) (d/datoms db :aevt :seon.doc/heading))
           (into {} (map (fn [d] [(:e d) (str (:v d))])) (d/datoms db :aevt :schema/key)))))

(defn- title
  "Entity e's title on db, as `titles` reads it, by seeks: for a few entities, not a scan."
  {:malli/schema [:=> [:cat :seon.host/db :int] [:maybe :string]]}
  [db e]
  (or (some->> (d/datoms db :avet :var/form e) (keep #(stored db (:e %) :var/name)) sort first str)
      (stored db e :seon.doc/heading)
      (some-> (stored db e :schema/key) str)))

(defn- due-among
  "Of the text datoms ds, those whose vector is due for model, made (an entity's :seon.vector/text, the digest of the text it
  embeds), of (its :seon.vector/model) and title-of (its title) read as functions of the entity: [[e text digest] ...] in entity
  order. See docs/vector.md#what-is-embedded."
  {:malli/schema [:=> [:cat [:sequential :any] :seon.host/fn :seon.host/fn :seon.host/fn :string] [:vector [:tuple :int :string :string]]]}
  [ds made of title-of model]
  (vec (sort (for [{:keys [e a v]} ds
                   :let [text ((texts a) (title-of e) v) h (lib/digest text)]
                   :when (or (not= model (of e)) (not= h (made e)))]
               [e text h]))))

(defn due
  "The texts on db whose vector is due for model: each form's source, wiki section's text, schema
  entry's form and task's plan with no vector, a vector made from another text (its :seon.vector/text is not the text's
  digest), or another model's. [[e text digest] ...] in entity order. With ds, only those of the text datoms ds (`written`), each one's vector read by
  one seek: the cost of ds, not of the store."
  {:malli/schema [:function [:=> [:cat :seon.host/db :string] [:vector [:tuple :int :string :string]]]
                  [:=> [:cat :seon.host/db :string [:sequential :any]] [:vector [:tuple :int :string :string]]]]}
  ([db model]
   (let [on (vectors-of db)
         by-e (fn [a] (into {} (keep (fn [d] (when-let [e (on (:e d))] [e (:v d)]))) (d/datoms db :aevt a)))
         made (by-e :seon.vector/text)
         of (by-e :seon.vector/model)]
     (let [title-of (titles db)]
       (due-among (for [a (keys texts) d (d/datoms db :aevt a)] d) #(made %) #(of %) #(title-of %) model))))
  ([db model ds]
   (due-among (filter (comp texts :a) ds) #(stored-of db % :seon.vector/text) #(stored-of db % :seon.vector/model) #(title db %) model)))

(defn written
  "The text datoms db holds that db from does not (datahike.api/datoms-diff, which skips the
  subtrees they share): the forms, sections and plans a branch wrote since from. Cost: the
  datoms that differ."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db] [:vector :any]]}
  [from db] (filterv (comp texts :a) (second (d/datoms-diff from db :eavt []))))

(defn fact
  "The vector entity of entity e (an id, tempid or lookup ref): its vector v of the text whose digest is h, made by
  model. Its identity is :seon.vector/of, so a vector written again replaces e's
  (docs/vector.md#a-vector-is-its-own-entity)."
  {:malli/schema [:=> [:cat :seon.db/ref :string [:vector {:min 1} number?] :string] :map]}
  [e h v model]
  (assoc (pack v) :seon.vector/of e :seon.vector/model model :seon.vector/text h))

(defn tx-data
  "The vector entities of embedded texts: entity es[i]'s vector vs[i] of the text whose digest is hs[i], made by
  model (`fact`)."
  {:malli/schema [:=> [:cat [:sequential :int] [:sequential :string] [:sequential [:vector {:min 1} number?]] :string] [:vector :map]]}
  [es hs vs model]
  (mapv (fn [e h v] (fact e h v model)) es hs vs))

;; ---- the model (native/embed.c over llama.cpp: only the exec'd helper loads it) ------------
;; Bound here, resolved on the first call: the library is loaded by `open-model`, in the helper
;; (`seon embed-worker`), so root, an agent or the fork server never maps it (ggml and Metal start
;; threads, and a fork cannot run Metal; docs/failure-points.md#physics-who-may-run-threads).

(lib/defc c-open "seon_embed_open" [:pointer :int :int :int :int] :pointer {:blocking true})
(lib/defc c-dims "seon_embed_dims" [:pointer] :int)
(lib/defc c-batch "seon_embed_batch" [:pointer :pointer :pointer :int :int :pointer :pointer] :int {:blocking true})

(def ^:private texts-version
  "The version of what is embedded (`texts`, `document`, query-prompt): part of every vector's
  model id, so a change to the texts makes every vector due once, as a new model does."
  "titled")

(defn model-id
  "The id of the model file at path and of the texts it embeds, on every vector it makes: the
  file's name, which bin/build makes its sha256 (bin/env's SEON_EMBED_MODEL), and texts-version."
  {:malli/schema [:=> [:cat :string] :string]}
  [path] (let [f (.getName (java.io.File. path))] (str (subs f 0 (or (str/last-index-of f ".") (count f))) "+" texts-version)))

(defn- open-model
  "The model at :seon.config.vector/model, loaded in this process (the helper's) with a context of
  :seon.config.vector/batch-tokens tokens and :seon.config.vector/batch-texts texts and ggml's CPU
  pool of :seon.config.vector/threads (seon_embed_open), {:handle :dims}; a missing file or a model
  llama.cpp cannot load is :seon.vector/model-unopened (its cause is in llama.cpp's log)."
  {:malli/schema [:=> [:cat] :map]}
  []
  (let [path (:embed-model (cfg/process))]
    (if-not (.exists (java.io.File. path))
      (contract/error :seon.vector/model-unopened #'seon.vector/open-model (str "no model file at " path " (bin/build embed fetches it)") {:path path})
      (do (ffi/load-library (str (:checkout (cfg/process)) "/target/lib/libseon-embed.dylib"))
          (let [h (ffi/with-arena [a] (c-open (ffi/string->ptr a path) (cfg/value :seon.config.vector/batch-tokens)
                                              (cfg/value :seon.config.vector/batch-texts) (cfg/value :seon.config.vector/threads) 0))]
            (if (zero? (ffi/address h))
              (contract/error :seon.vector/model-unopened #'seon.vector/open-model (str "llama.cpp could not load " path) {:path path})
              {:handle h :dims (c-dims h)}))))))

(defn- embed-texts
  "texts embedded by the model m in one call: their unit vectors and each one's whole token count,
  in order (seon_embed_batch packs them into encodes; a text over :seon.config.vector/max-tokens
  keeps its first tokens). Answers {:vectors :tokens} or the encode's failure."
  {:malli/schema [:=> [:cat :map [:vector :string]] :map]}
  [{:keys [handle dims]} texts]
  (let [bs (mapv #(.getBytes ^String % "UTF-8") texts)
        offs (long-array (reductions + 0 (map count bs)))
        n (count texts)]
    (ffi/with-arena [a]
      (let [tp (ffi/alloc a (max 1 (aget offs n))) op (ffi/alloc a (* 8 (inc n)))
            out (ffi/alloc a (max 4 (* 4 n dims))) np (ffi/alloc a (max 4 (* 4 n)))]
        (dotimes [i n] (ffi/write-bytes (ffi/slice tp (aget offs i)) (nth bs i)))
        (ffi/write-array op :int64 offs 0)
        (let [r (c-batch handle tp op n (cfg/value :seon.config.vector/max-tokens) out np)]
          (if (neg? r)
            (contract/error :seon.vector/encode-failed #'seon.vector/embed-texts (str "llama.cpp's encode answered " r) {:code r})
            {:vectors (mapv #(vec (ffi/read-array out :float dims (* 4 dims %))) (range n))
             :tokens (vec (ffi/read-array np :int32 n 0))}))))))

(defn serve!
  "The helper's whole life (`seon embed-worker`, docs/vector.md#the-model): load the model, answer
  the first frame on stdout, {:ready {:dims n}} or {:error e} (then exit 1), then answer each
  request frame on stdin, {:texts [...]}, with {:vectors :tokens} or {:error e}, and exit 0 at
  stdin's end: root closing it is the one event that ends the helper. It runs no thread of its own
  and never reads a request ahead of its answer."
  {:malli/schema [:=> [:cat] :any]}
  []
  (let [m (open-model)
        reply! (fn [v] (let [r (sock/send-frame! 1 (sock/frame v))] (when (= :epipe r) (lib/c-exit 0))))]
    (if (contract/error? m)
      (do (reply! {:error m}) (lib/c-exit 1))
      (do (reply! {:ready {:dims (:dims m)}})
          (loop []
            (let [r (sock/recv-stream! System/in)]
              (when (map? r)
                (let [e (embed-texts m (:texts r))]
                  (reply! (if (contract/error? e) {:error e} e)))
                (recur))))
          (lib/c-exit 0)))))

;; ---- root's side: the helper as the :embed kind's host object ---------------------------------
;; The kind's worker (seon.fx/worker-proc) holds `open`'s handle, {:helper (volatile! helper-or-nil)
;; :facts (volatile! the helper's pid, start and exit)}: no process yet. `embed` starts the helper
;; when it has a batch and none runs, `close` ends it when the owner's queue is empty; a helper that
;; dies answers its batch an error and the next batch starts another.

(defn open
  "The :embed kind's host object: a handle with no helper running yet (it starts with the first batch,
  `embed`). Never fails."
  {:malli/schema [:=> [:cat :any] :map]}
  [_ctx]
  {:helper (volatile! nil) :facts (volatile! {})
   :err (str (cfg/checkout) "/target/tmp/embed-helper-" (random-uuid) ".err")})

(defn- helper-argv
  "The helper's command line: this checkout's binary and the config layers root was started with
  (`--config`, `--set`), so it reads the config root does."
  {:malli/schema [:=> [:cat] [:vector :string]]}
  []
  (into [(str (cfg/checkout) "/target/seon") "embed-worker"]
        (comp (filter (comp #{"--config" "--set"} first)) cat)
        (partition 2 (rest (cfg/argv)))))

(defn- finished
  "The helper h ended, however it did: its exit status and stderr, recorded in the handle's facts
  (nothing is held any more). Its pipes closed, a helper that has not exited is killed first when
  kill? (it ended its output without ending), else waited for. Answers the facts {:pid :exit :stderr}."
  {:malli/schema [:=> [:cat :map :map :boolean] :map]}
  [handle h kill?]
  (let [p (:process h)
        _ (when kill? (.destroyForcibly p))
        f {:pid (:pid h) :exit (.waitFor p) :stderr (or (lib/tail-bytes (:err handle) (cfg/value :seon.config.vector/stderr-bytes)) "")}
        _ (.delete (java.io.File. ^String (:err handle)))]
    (vreset! (:helper handle) nil)
    (vswap! (:facts handle) assoc :exit (:exit f) :exited-ms (System/currentTimeMillis))
    f))

(defn- start!
  "Start the helper (`seon embed-worker` in this checkout's target/), its model still loading, and hold it in the handle:
  the helper {:process :pid :in :out :new true}, or :seon.vector/model-unopened (the model file missing) or
  :seon.vector/helper-died (it did not start). Its first frame, {:ready ..} once its model is loaded or {:error e}, is read
  with its first answer on the embed stream's reader (`embed`), never waited on in a step: a root's stop during a model's
  load (1.5-1.7 s) ends it at once (docs/vector.md#the-model)."
  {:malli/schema [:=> [:cat :map] :map]}
  [handle]
  (let [path (:embed-model (cfg/process))
        bin (first (helper-argv))]
    (if-not (.exists (java.io.File. ^String path))
      (contract/error :seon.vector/model-unopened #'seon.vector/start! (str "no model file at " path " (bin/build embed fetches it)") {:path path})
      (let [_ (.mkdirs (java.io.File. (str (cfg/checkout) "/target/tmp")))
            p (try (.start (doto (ProcessBuilder. ^java.util.List (helper-argv)) (.directory (java.io.File. (str (cfg/checkout) "/target")))
                             (.redirectError (java.io.File. ^String (:err handle)))))
                   (catch java.io.IOException e e))]
        (if (instance? Throwable p)
          (contract/error :seon.vector/helper-died #'seon.vector/start! (str bin " did not start: " (.getMessage ^Throwable p)) {:exit -1 :stderr ""})
          (let [h {:process p :pid (.pid p) :in (.getOutputStream p) :out (.getInputStream p) :new true}]
            (vreset! (:helper handle) h)
            (vreset! (:facts handle) {:pid (:pid h) :started-ms (System/currentTimeMillis)})
            h))))))

(defn- send!
  "One request frame to the helper h: true, or :unsent when the write failed (the helper was gone before the request: it
  died while idle, or never started)."
  {:malli/schema [:=> [:cat :map :map] [:or :boolean [:= :unsent]]]}
  [h req]
  (if (try (doto ^java.io.OutputStream (:in h) (.write ^bytes (sock/frame req)) (.flush)) true
           (catch Exception _ false))
    true
    :unsent))

(defn- died
  "The helper h ended before it answered: finished (killed if it has not exited), and the :seon.vector/helper-died its batch
  answers, with its exit status and stderr."
  {:malli/schema [:=> [:cat :map :map :string] :seon.vector/helper-died]}
  [handle h why]
  (let [f (finished handle h true)]
    (contract/error :seon.vector/helper-died #'seon.vector/died (str "the embedding helper " why " (exit " (:exit f) "): " (:stderr f)) (dissoc f :pid))))

(defn embed
  "The :embed effect, a batch kind: every request's :texts embedded in one call by the helper
  (started if none runs), each request answered {:value {:model :vectors :tokens}}, its vectors and
  each text's whole token count in its texts' order; :cost is its texts. The call is fx's :frames stream
  (docs/flow.md#effects-one-owner-and-its-workers-per-kind): this step only starts a helper when none is held; its reader
  writes the request (a batch's texts may pass a pipe's buffer while the model loads) and reads the helper's frames, its
  {:ready} first when new, then the answer, so the worker's stop shuts a helper still loading at once. A held helper gone
  while idle fails the write: one new helper, the request again. A helper that dies mid-batch answers every request
  :seon.vector/helper-died (exit status and stderr), and the next batch starts another."
  {:malli/schema [:=> [:cat [:map [:batch [:vector :map]] [:handle :map]]] [:or [:vector :map] [:map [:stream :map]]]]}
  [{:keys [batch handle]}]
  (let [ns (mapv #(count (get-in % [:req :texts])) batch)
        texts (into [] (mapcat #(get-in % [:req :texts])) batch)
        model (model-id (:embed-model (cfg/process)))
        req {:texts texts}
        outcomes (fn [r] (if (contract/error? r)
                           (vec (repeat (count batch) r))
                           (first (reduce (fn [[acc vs ts] n] [(conj acc {:value {:model model :vectors (subvec vs 0 n) :tokens (subvec ts 0 n)} :cost n})
                                                               (subvec vs n) (subvec ts n)])
                                          [[] (:vectors r) (:tokens r)] ns))))
        h0 (when (seq texts) (or @(:helper handle) (start! handle)))]
    (cond
      (empty? texts) (outcomes {:vectors [] :tokens []})
      (contract/error? h0) (outcomes h0)
      :else
      (let [cur (volatile! h0) sent (volatile! false) over (volatile! false)
            ;; the reader's pull: the request written once, then each frame; nil once the answer is read
            pull (fn []
                   (cond
                     @over nil
                     (not @sent) (do (vreset! sent true)
                                     (let [w (send! @cur req)
                                           w (if (and (= :unsent w) (not (:new @cur)))
                                               (do (finished handle @cur true)
                                                   (let [h2 (start! handle)] (if (contract/error? h2) h2 (do (vreset! cur h2) (send! h2 req)))))
                                               w)]
                                       (cond (contract/error? w) (do (vreset! over true) w)
                                             (= :unsent w) (do (vreset! over true) (died handle @cur "was gone before the request"))
                                             :else {:sent true})))
                     :else (let [r (sock/recv-stream! (:out @cur))]
                             (cond (= :eof r) (do (vreset! over true) (died handle @cur "ended mid-batch"))
                                   (and (map? r) (:ready r)) (do (vswap! cur dissoc :new) (vreset! (:helper handle) @cur) r)
                                   ;; the helper's own refusal of its model ({:error e} before {:ready}): it exits after it
                                   (and (map? r) (:error r) (:new @cur)) (do (vreset! over true) (finished handle @cur true) (:error r))
                                   :else (do (vreset! over true) r)))))]
        {:stream {:frames true :pull pull :init nil
                  :step (fn [s f] [(if (or (:sent f) (:ready f)) s f) []])
                  :final (fn [a] (outcomes (if (and (map? a) (:error a)) (:error a) a)))
                  :close (fn [_] [])
                  ;; the reader's end, or the worker's stop: a helper that has not answered is ended
                  :shut (fn [] (when-not @over (vreset! over true) (finished handle @cur true)))}}))))

(defn close
  "The :embed kind's :close, told when its queue has emptied: the helper's stdin closed, which ends it
  (it exits 0 at the end of its input), and its exit recorded. Answers the helper's facts
  {:pid :exit :stderr}, or nil when none ran. See docs/vector.md#the-model."
  {:malli/schema [:=> [:cat :map] [:maybe :map]]}
  [handle]
  (when-let [h @(:helper handle)]
    (.close ^java.io.OutputStream (:in h))
    (finished handle h false)))

;; ---- keeping main's vectors current -------------------------------------------------------

(defn- release
  "The proc's spell of asking ended (nothing due, or a write that starts no next round) while the last
  request it sent said :more: an empty request without :more, so its answer is the event that lets the
  :embed kind close its helper (docs/vector.md#the-model). Answers [st outs]; nothing is sent when no
  :more is out."
  {:malli/schema [:=> [:cat :map] [:tuple :map [:vector :map]]]}
  [st]
  (if (:more st)
    (let [[st m] (step/ask (dissoc st :more) [:vectors :answers] [:released nil] {:op :fx :kind :embed :req {:texts []}})]
      [st [m]])
    [st []]))

(defn- library-inputs
  "What main's dependency source is read from, on db: its pins and the deps digest root's source fact holds (:seon.env/deps), as one
  digest; the library is read again when it moves (`library-round`). See docs/vector.md#search."
  {:malli/schema [:=> [:cat :seon.host/db] :string]}
  [db]
  (lib/digest (str/join "\n" (concat (for [[p c] (sort (source/pins-of db))] (str p " " c))
                                      [(str (get-in (d/pull db [:seon.system/source] [:seon.system/id :root]) [:seon.system/source :inputs :config :seon.env/deps]))]))))

(defn- library-round
  "A round with nothing to embed: when the dependency inputs moved (`library-inputs`) and were not asked yet, the :library effect at
  the lowest :prio for the roots whose identity moved; else the round ends. See docs/vector.md#search."
  {:malli/schema [:=> [:cat :map :seon.host/db] [:tuple :map [:vector :map]]]}
  [st db]
  (let [in (library-inputs db)
        held (d/pull db [:seon.library/inputs :seon.library/roots] [:seon.system/id :root])
        ;; a store never imported from a tree (a test's) holds no pins and no deps: nothing it depends on to read
        deps? (or (seq (source/pins-of db)) (get-in (d/pull db [:seon.system/source] [:seon.system/id :root]) [:seon.system/source :inputs :config :seon.env/deps]))]
    (if (and deps? (not= in (:seon.library/inputs held)) (not= in (:library-asked st)))
      (let [[st m] (step/ask st [:vectors :answers] [:library in]
                             {:op :fx :kind :library :prio -1
                              :req {:root (cfg/checkout) :pins (source/pins-of db) :since (or (:seon.library/roots held) {})}})]
        [(assoc st :running true :library-asked in) [m]])
      (release (assoc st :running false)))))

(defn- round
  "At main's commit (a notice {:t :commit}), the next round: the first :texts due (:seon.config.vector/texts; 0 asks nothing) go
  to the :embed effect. After a round (:clean, the commit it scanned at, and :owed, the entities it found due and left
  for later), only the texts that commit to this one changed are looked at (`written`: the cost of the commit, not of the
  store), then as many owed as the round has room for; at the first round and after a failure or an unwritten answer, all
  of them (`due`). Catching up on a backlog of n texts is n/texts rounds of seeks, never a scan a round. Both dbs are made from their commits here (seon.db/at-commit) and let go with the round: the
  proc keeps references, never db values (docs/storage.md#a-kept-db-value-is-a-reference).
  A vector ends with the entity it embeds in that entity's own retraction (docs/storage.md#the-write-checks-every-entity-it-touches), so a round has none to sweep.
  Answers [st outputs]; nothing due, nothing asked."
  {:malli/schema [:=> [:cat :map :map] [:tuple :map [:vector :map]]]}
  [st m]
  (let [db (pdb/at-commit (:conn st) (:commit m))
        clean (:clean st)
        ;; one diff from the previous round's commit (:seen): the texts it added
        from (when (and clean (:seen st)) (pdb/at-commit (:conn st) (:seen st)))
        [_ added] (when from (d/datoms-diff from db :eavt []))
        base (when clean (if (= (:commit clean) (:seen st)) from (pdb/at-commit (:conn st) (:commit clean))))
        fresh (remove (comp (:unembeddable st) first)
                      (cond (not clean) (due db (:model st))
                            (identical? base from) (due db (:model st) (filterv (comp texts :a) added))
                            :else (due db (:model st) (written base db))))
        ;; what an earlier round found due and left for later (:owed, entity ids): read again only as far as this round has room,
        ;; so catching up on n texts is n/texts rounds of seeks, never a scan of every text each round
        owed (remove (into #{} (map first) fresh) (when clean (:owed st)))
        room (max 0 (- (:texts st) (count fresh)))
        later (when (and (pos? room) (seq owed)) (remove (comp (:unembeddable st) first)
                                        (due db (:model st) (for [e (take room owed) a (keys texts) d (d/datoms db :eavt e a)] d))))
        todo (concat fresh later)
        due (take (:texts st) todo)
        left (into (mapv first (drop (:texts st) todo)) (drop room owed))
        ;; the searches holding a vector but no hits for their query (seon.ctx.search/hits-owed): read once, the first round this state
        ;; holds none (a start, after a failure, or a proc whose state was made before this code arrived in place), then owed round by
        ;; round; seon.ctx.search requires this namespace, so it is resolved here
        hits-owed (if (and clean (contains? st :hits-owed)) (:hits-owed st) ((requiring-resolve 'seon.ctx.search/hits-owed) db))
        ;; scanned at this commit: what is still due is owed, so the next round starts from here, not from a full scan
        st (assoc st :seen (:commit m) :clean (select-keys m [:t :commit]) :owed left :hits-owed (vec hits-owed))]
    (cond
      (seq due)
      (let [more? (boolean (seq left))
            [st m] (step/ask st [:vectors :answers] [:embedded [(mapv first due) (mapv peek due) (select-keys m [:t :commit])]]
                             {:op :fx :kind :embed :req (cond-> {:texts (mapv second due)} more? (assoc :more true))})]
        [(assoc st :running true :more more?) [m]])
      ;; nothing to embed: the owed searches' hits from their stored vectors, :texts a round, then the library
      (and (seq hits-owed) (pos? (:texts st)))
      (let [es (vec (take (:texts st) hits-owed))
            tx ((requiring-resolve 'seon.ctx.search/hits-tx) db es (mapv #(vector-of db %) es))
            [st w] (step/ask (update st :hits-owed #(vec (drop (count es) %))) [:vectors :answers] [:written 0] {:op :write :req {:op :tx :branch :db :tx-data tx}})]
        [(assoc st :running true) [w]])
      :else (library-round st db))))

(defn vectors-proc
  "Keeps main's vectors current (docs/vector.md#what-is-embedded). At main's commit notice
  (:main, a sliding buffer of one), the texts due (seon.vector/due) go to root's :embed effect,
  :seon.config.vector/texts at a time, and their vectors are written to main; that commit's notice
  starts the next round, so a backlog is embedded round by round and an idle main asks nothing.
  One round at a time: a notice during one is kept, and the round after the write reads the
  latest notice at or after it. A failed embedding is recorded on main by this proc, its
  :effect row naming :seon.vector/model (seon.error/asked-tx), and the next notice after that
  write tries again, except texts llama.cpp could not encode (:seon.vector/encode-failed):
  those are kept out of later rounds (:unembeddable, shown in its ping), so they never hold back
  the texts due after them."
  {:malli/schema :seon.step/step-fn}
  ([] {:params {:conn "the store main's commits are read from (seon.db/at-commit)"
                :model "the model id its vectors are made by (seon.vector/model-id)"
                :texts "the texts one round asks for, :seon.config.vector/texts (0: none, the model never loads)"}
       :ins {:main "main's commit notice {:t :commit}, a sliding buffer of one" :answers "the api's answers"}
       :outs {:to-api "its requests to the api: the :embed effect, the write to main"}
       :workload :io
       :ping-map-fn (fn [st] (-> (select-keys st [:running :embedded :written-t]) (assoc :unembeddable (count (:unembeddable st)))))})
  ([args] {:rid 0 :pending {} :conn (:conn args) :model (:model args) :texts (:texts args) :running false :embedded 0 :written-t -1 :unembeddable #{} :owed [] :hits-owed [] :clean nil :seen nil})
  ([st _] st)
  ([st in m]
   (let [[st outs]
         (case in
           :main (if (or (:running st) (< (:t m) (:written-t st)))
                   [(cond-> st (>= (:t m) (get-in st [:latest :t] -1)) (assoc :latest (select-keys m [:t :commit]))) []]
                   (round (dissoc st :latest) m))
           :answers (let [[st [step es]] (step/answered st (:tag m)) r (:r m)]
                      (case step
                        :embedded (let [[es ts asked] es]
                                    (if (contract/error? r)
                                      ;; the failure is recorded here (seon.error/asked-tx), closed by the next
                                      ;; vectors written (:seon.vector/model)
                                      (let [st (cond-> (assoc st :clean nil :seen nil :owed []) (= :seon.vector/encode-failed (:seon.error/type r)) (update :unembeddable into es))
                                            ;; the newest main it holds, so the row's count reads what is stored
                                            tx (err/asked-tx (pdb/at-commit (:conn st) (:commit (or (:latest st) asked))) r :seon.vector/model [])]
                                        (if (seq tx)
                                          (let [[st w] (step/ask st [:vectors :answers] [:recorded nil] {:op :write :req {:op :tx :branch :db :tx-data tx}})]
                                            [st [w]])
                                          (release (assoc st :running false))))
                                      (let [{:keys [model vectors]} (:value r)
                                            db (pdb/at-commit (:conn st) (:commit asked))
                                            ;; a search's hits are searched once, with its query's vector, at a commit holding every text's vector:
                                            ;; while this round left texts owed (:more, a backfill), the corpus is part embedded, so the batch's
                                            ;; searches write their vectors alone and join :hits-owed, whose hits are searched once nothing is due
                                            ;; (`round`); otherwise here (seon.ctx.search/embedded-tx; seon.ctx.search requires this namespace, so
                                            ;; it is resolved at the call). Every agent forked after reads the hits from main
                                            later (when (:more st) (filterv #(stored db % :seon.search/query) es))
                                            tx (if (:more st)
                                                 (tx-data es ts vectors model)
                                                 ((requiring-resolve 'seon.ctx.search/embedded-tx) db es ts vectors model))
                                            st (cond-> st (seq later) (update :hits-owed #(vec (distinct (into (vec %) later)))))
                                            [st w] (step/ask st [:vectors :answers] [:written (count es)]
                                                             {:op :write :req {:op :tx :branch :db :tx-data tx}})]
                                        [st [w]])))
                        :written (let [st (cond-> (assoc st :running false)
                                            (not (contract/error? r)) (-> (update :embedded + es) (assoc :written-t (:t r)))
                                            (contract/error? r) (assoc :clean nil :seen nil))
                                       l (:latest st)]
                                   (cond (and l (>= (:t l) (:written-t st))) (round (dissoc st :latest) l)
                                         ;; a failed write commits nothing, so no notice follows: the spell ends here
                                         (contract/error? r) (release st)
                                         :else [st []]))
;; the dependency source read (library-round): its files written through the one path with the inputs they were read at;
                        ;; a failed read recorded as a failure is (seon.error/asked-tx), closed by the next read that writes
                        :library (let [db (pdb/at-commit (:conn st) (:commit (or (:latest st) (:clean st))))
                                       tx (if (contract/error? r)
                                            (err/asked-tx db r :seon.library/inputs [])
                                            (conj (source/library-tx db (:value r)) {:seon.system/id :root :seon.library/inputs es}))]
                                   (if (seq tx)
                                     (let [[st w] (step/ask st [:vectors :answers] (if (contract/error? r) [:recorded nil] [:written 0])
                                                            {:op :write :req {:op :tx :branch :db :tx-data tx}})]
                                       [st [w]])
                                     (release (assoc st :running false))))
                        ;; the failure's own commit starts no round (it would ask again and fail again,
                        ;; on an idle main): the next round reads a commit after it
                        :recorded (let [st (cond-> (assoc st :running false) (not (contract/error? r)) (assoc :written-t (inc (:t r))))
                                        l (:latest st)]
                                    (cond (and l (>= (:t l) (:written-t st))) (round (dissoc st :latest) l)
                                          (contract/error? r) (release st)
                                          :else [st []]))
                        [st []])))]
     [st (if (seq outs) {:to-api outs} {})])))
