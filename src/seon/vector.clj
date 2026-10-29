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
            [seon.step :as step] [jolt.ffi :as ffi] [datahike.api :as d] [seon.db :as pdb] [clojure.string :as str] [seon.error :as err]))

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
  entity with no vector: `near`'s ranking of its shortlist, and seon.context/relevance's of a pool.
  Cost: two seeks an entity (its vector entity by :seon.vector/of, inlined: `vector-e` is a contracted call an entity, 100 more in a related part; then the vector)."
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
  (let [cv (vec (d/datoms db :aevt :seon.vector/coarse)) n (count cv) j (volatile! 0) rows (volatile! (transient [])) es (volatile! (transient []))]
    (reduce (fn [_ o]
              (let [oe (:e o)]
                (loop [i @j]
                  (cond (>= i n) (vreset! j i)
                        (< (:e (nth cv i)) oe) (recur (inc i))
                        :else (do (vreset! j i)
                                  (when (and (= oe (:e (nth cv i))) (keep? (:v o)) (not= self (:v o)))
                                    (vswap! rows conj! (nth cv i)) (vswap! es conj! (:v o)))))))
              nil)
            nil (d/datoms db :aevt :seon.vector/of))
    [(persistent! @rows) (persistent! @es)]))

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
         keep? (if of (into #{} (map :e) (d/datoms db :aevt of)) any?)
         [rows rowe] (coarse-rows db keep? self)]
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
  call), so rrf (called per line of the page's related part) costs the one contracted call it did
  (seon.context-cost-test/related-ranks-each-kind-by-one-search)."
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
  Measured fusing the call graph with embeddings (docs/vector.md#fused-with-the-call-graph); the
  page's related part fuses their reference graph with meaning by it (seon.context/related), and
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
   :seon.section/text (fn [title text] (document title text))
   ;; a schema's form, so the related part's schema keys are found by meaning too
   ;; (docs/vector.md#what-the-work-is-likely-to-need-as-an-eval)
   :schema/form (fn [title form] (document title form))
   :seon.task/plan (fn [_ plan] (str query-prompt plan))
   ;; an eval: what the agent asked and what it got, as a search asks, so the agent's recent work is a
   ;; direction a page can follow (seon.context/selected; docs/vector.md#relevance)
   :seon.eval/record (fn [_ record] (str query-prompt (:code record) "\n" (:line record)))})

(defn- titles
  "Each titled entity's title on db, {e title}: a form's by the first of its vars' names, a
  section's heading, a schema entry's key. One scan of each attribute, as `due` reads its others."
  {:malli/schema [:=> [:cat :seon.host/db] [:map-of :int :string]]}
  [db]
  (let [names (into {} (map (juxt :e :v)) (d/datoms db :aevt :var/name))]
    (merge (into {} (map (fn [[f ds]] [f (str (first (sort (keep (comp names :e) ds))))]))
                 (group-by :v (d/datoms db :aevt :var/form)))
           (into {} (map (juxt :e :v)) (d/datoms db :aevt :seon.section/heading))
           (into {} (map (fn [d] [(:e d) (str (:v d))])) (d/datoms db :aevt :schema/key)))))

(defn- title
  "Entity e's title on db, as `titles` reads it, by seeks: for a few entities, not a scan."
  {:malli/schema [:=> [:cat :seon.host/db :int] [:maybe :string]]}
  [db e]
  (or (some->> (d/datoms db :avet :var/form e) (keep #(stored db (:e %) :var/name)) sort first str)
      (stored db e :seon.section/heading)
      (some-> (stored db e :schema/key) str)))

(defn- due-among
  "Of the text datoms ds, those whose vector is due for model, made (an entity's
  :seon.vector/text-t), of (its :seon.vector/model) and title-of (its title) read as functions of the entity:
  [[e text t] ...] in entity order."
  {:malli/schema [:=> [:cat [:sequential :any] :seon.host/fn :seon.host/fn :seon.host/fn :string] [:vector [:tuple :int :string :int]]]}
  [ds made of title-of model]
  (vec (sort (for [{:keys [e a v tx]} ds
                   :when (or (not= model (of e)) (not= tx (made e)))]
               [e ((texts a) (title-of e) v) tx]))))

(defn due
  "The texts on db whose vector is due for model: each form's source, wiki section's text, schema
  entry's form and task's plan with no vector, a vector made from an earlier text (its :seon.vector/text-t is not
  the text's transaction), or another model's. [[e text t] ...] in entity order, t the text's
  transaction. With ds, only those of the text datoms ds (`written`), each one's vector read by
  one seek: the cost of ds, not of the store."
  {:malli/schema [:function [:=> [:cat :seon.host/db :string] [:vector [:tuple :int :string :int]]]
                  [:=> [:cat :seon.host/db :string [:sequential :any]] [:vector [:tuple :int :string :int]]]]}
  ([db model]
   (let [on (vectors-of db)
         by-e (fn [a] (into {} (keep (fn [d] (when-let [e (on (:e d))] [e (:v d)]))) (d/datoms db :aevt a)))
         made (by-e :seon.vector/text-t)
         of (by-e :seon.vector/model)]
     (let [title-of (titles db)]
       (due-among (for [a (keys texts) d (d/datoms db :aevt a)] d) #(made %) #(of %) #(title-of %) model))))
  ([db model ds]
   (due-among (filter (comp texts :a) ds) #(stored-of db % :seon.vector/text-t) #(stored-of db % :seon.vector/model) #(title db %) model)))

(defn- retract-vector
  "The retractions of every :seon.vector/* fact entity ve holds on db. Only the vector's own attributes: a
  vector an earlier version wrote on a form or section is dropped the same way, never the entity."
  {:malli/schema [:=> [:cat :seon.host/db :int] [:vector :any]]}
  [db ve]
  (into [] (comp (filter #(= "seon.vector" (namespace (:a %)))) (map (fn [d] [:db/retract (:e d) (:a d) (:v d)]))) (d/datoms db :eavt ve)))

(defn written
  "The text datoms db holds that db from does not (datahike.api/datoms-diff, which skips the
  subtrees they share): the forms, sections and plans a branch wrote since from. Cost: the
  datoms that differ."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db] [:vector :any]]}
  [from db] (filterv (comp texts :a) (second (d/datoms-diff from db :eavt []))))

(defn- gone-in
  "The retractions of the vectors whose :seon.vector/of datom is among the datoms removed, which db lacks
  (see `orphaned`)."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :any]] [:vector :any]]}
  [db removed]
  (into [] (comp (filter #(= :seon.vector/of (:a %))) (map :e) (distinct)
                 (remove #(seq (d/datoms db :eavt % :seon.vector/of)))
                 (mapcat #(retract-vector db %)))
        removed))

(defn fact
  "The vector entity of entity e (an id, tempid or lookup ref): its vector v of the text at transaction t,
  that text's whole tokens n (past :seon.config.vector/max-tokens its first were embedded), made by
  model. Its identity is :seon.vector/of, so a vector written again replaces e's
  (docs/vector.md#a-vector-is-its-own-entity)."
  {:malli/schema [:=> [:cat :seon.db/ref :int [:vector {:min 1} number?] :int :string] :map]}
  [e t v n model]
  (assoc (pack v) :seon.vector/of e :seon.vector/model model :seon.vector/text-t t :seon.vector/tokens n))

(defn orphaned
  "The retractions of the vectors on db whose entity was retracted, [[:db/retract ve a v] ...].
  Retracting an embedded entity drops the :seon.vector/of datom that pointed at it, and leaves its vector
  (about 1.6 KB) behind. With from, those whose :seon.vector/of datom db lacks and from held (datoms-diff:
  the cost of the commits between); without, every coarse row with no :seon.vector/of (one pass over the two
  scans, as `near` joins them), for the round that has no commit to diff from
  (docs/vector.md#a-vector-is-its-own-entity)."
  {:malli/schema [:function [:=> [:cat :seon.host/db] [:vector :any]]
                  [:=> [:cat :seon.host/db :seon.host/db] [:vector :any]]]}
  ([db]
   (let [of (into #{} (map :e) (d/datoms db :aevt :seon.vector/of))]
     (into [] (comp (map :e) (remove of) (mapcat #(retract-vector db %))) (d/datoms db :aevt :seon.vector/coarse))))
  ([from db] (gone-in db (first (d/datoms-diff from db :eavt [])))))

(defn tx-data
  "The vector entities of embedded texts: entity es[i]'s vector vs[i] of the text at transaction ts[i], that
  text's whole tokens ns[i] (past :seon.config.vector/max-tokens its first were embedded), made by
  model (`fact`)."
  {:malli/schema [:=> [:cat [:sequential :int] [:sequential :int] [:sequential [:vector {:min 1} number?]] [:sequential :int] :string] [:vector :map]]}
  [es ts vs ns model]
  (mapv (fn [e t v n] (fact e t v n model)) es ts vs ns))

;; ---- the model (native/embed.c over llama.cpp: root's :embed worker alone loads it) ------
;; Bound here, resolved on the first call: the library is loaded by `open`, in the worker, so an
;; agent or the fork server never maps it (ggml starts threads; docs/failure-points.md#physics).

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

(defn open
  "The :embed kind's host object: the model at :seon.config.vector/model, loaded in this process
  with a context of :seon.config.vector/batch-tokens tokens and :seon.config.vector/batch-texts
  texts and ggml's CPU pool of :seon.config.vector/threads (seon_embed_open), {:handle :model :dims}; a missing file or a model llama.cpp cannot load
  is :seon.vector/model-unopened (its cause is in llama.cpp's log)."
  {:malli/schema [:=> [:cat :any] :map]}
  [_ctx]
  (let [path (cfg/value :seon.config.vector/model)]
    (if-not (.exists (java.io.File. path))
      (contract/error :seon.vector/model-unopened #'seon.vector/open (str "no model file at " path " (bin/build embed fetches it)") {:path path})
      (do (ffi/load-library (cfg/value :seon.config.vector/lib))
          (let [h (ffi/with-arena [a] (c-open (ffi/string->ptr a path) (cfg/value :seon.config.vector/batch-tokens)
                                              (cfg/value :seon.config.vector/batch-texts) (cfg/value :seon.config.vector/threads) 0))]
            (if (zero? (ffi/address h))
              (contract/error :seon.vector/model-unopened #'seon.vector/open (str "llama.cpp could not load " path) {:path path})
              {:handle h :model (model-id path) :dims (c-dims h)}))))))

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

(defn embed
  "The :embed effect, a batch kind: every request's :texts embedded in one call, each request
  answered {:value {:model :vectors :tokens}}, its vectors and each text's whole token count in its
  texts' order; :cost is its texts."
  {:malli/schema [:=> [:cat [:map [:batch [:vector :map]] [:handle :map]]] [:vector :map]]}
  [{:keys [batch handle]}]
  (let [ns (mapv #(count (get-in % [:req :texts])) batch)
        r (embed-texts handle (into [] (mapcat #(get-in % [:req :texts])) batch))]
    (if (contract/error? r)
      (vec (repeat (count batch) r))
      (first (reduce (fn [[acc vs ts] n] [(conj acc {:value {:model (:model handle) :vectors (subvec vs 0 n) :tokens (subvec ts 0 n)} :cost n})
                                          (subvec vs n) (subvec ts n)])
                     [[] (:vectors r) (:tokens r)] ns)))))

;; ---- keeping main's vectors current -------------------------------------------------------

(defn- round
  "At main's commit (a notice {:t :commit}), the next round: the first :texts due (:seon.config.vector/texts; 0 asks nothing) go
  to the :embed effect. When the proc is caught up (:clean, the commit at which everything due was asked
  for), only the texts that commit to this one changed are looked at (`written`: the cost of the commit,
  not of the store); otherwise, at the first round and after a backlog, a failure or an unwritten
  answer, all of them (`due`). A round that asks for all that is due leaves the proc caught up at the
  commit. Both dbs are made from their commits here (seon.db/at-commit) and let go with the round: the
  proc keeps references, never db values (AGENTS section 4).
  The vectors of entities retracted since go in the same write (`orphaned`), or alone when no text is due.
  Answers [st outputs]; nothing due and nothing retracted, nothing asked."
  {:malli/schema [:=> [:cat :map :map] [:tuple :map [:vector :map]]]}
  [st m]
  (let [db (pdb/at-commit (:conn st) (:commit m))
        clean (:clean st)
        ;; one diff from the previous round's commit (:seen) serves both: the texts it added and the vectors whose entity it
        ;; removed. Not from :clean, which is the commit before this proc's own write of a vector: a vector written and
        ;; orphaned after :clean has no datom in a diff from it
        from (when (and clean (:seen st)) (pdb/at-commit (:conn st) (:seen st)))
        [removed added] (when from (d/datoms-diff from db :eavt []))
        base (when clean (if (= (:commit clean) (:seen st)) from (pdb/at-commit (:conn st) (:commit clean))))
        todo (remove (comp (:unembeddable st) first)
                     (cond (not clean) (due db (:model st))
                           (identical? base from) (due db (:model st) (filterv (comp texts :a) added))
                           :else (due db (:model st) (written base db))))
        ;; the vectors of retracted entities go in the write of the embeddings, or alone when none is due
        gone (if from (gone-in db removed) (orphaned db))
        due (take (:texts st) todo)
        st (assoc st :seen (:commit m))
        st (assoc st :clean (when (= (count due) (count todo)) (select-keys m [:t :commit])))]
    (cond
      (seq due)
      (let [[st m] (step/ask st [:vectors :answers] [:embedded [(mapv first due) (mapv peek due) (select-keys m [:t :commit]) gone]]
                             {:op :fx :kind :embed :req {:texts (mapv second due)}})]
        [(assoc st :running true) [m]])
      (and (seq gone) (pos? (:texts st)))
      (let [[st m] (step/ask st [:vectors :answers] [:written 0] {:op :write :req {:op :tx :branch :db :tx-data gone}})]
        [(assoc st :running true) [m]])
      :else [(assoc st :running false) []])))

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
  ([args] {:rid 0 :pending {} :conn (:conn args) :model (:model args) :texts (:texts args) :running false :embedded 0 :written-t -1 :unembeddable #{} :clean nil :seen nil})
  ([st _] st)
  ([st in m]
   (let [[st outs]
         (case in
           :main (if (or (:running st) (< (:t m) (:written-t st)))
                   [(cond-> st (>= (:t m) (get-in st [:latest :t] -1)) (assoc :latest (select-keys m [:t :commit]))) []]
                   (round (dissoc st :latest) m))
           :answers (let [[st [step es]] (step/answered st (:tag m)) r (:r m)]
                      (case step
                        :embedded (let [[es ts asked gone] es]
                                    (if (contract/error? r)
                                      ;; the failure is recorded here (seon.error/asked-tx), closed by the next
                                      ;; vectors written (:seon.vector/model)
                                      (let [st (cond-> (assoc st :clean nil :seen nil) (= :seon.vector/encode-failed (:seon.error/type r)) (update :unembeddable into es))
                                            ;; the newest main it holds, so the row's count reads what is stored
                                            tx (err/asked-tx (pdb/at-commit (:conn st) (:commit (or (:latest st) asked))) r :seon.vector/model [])]
                                        (if (seq tx)
                                          (let [[st w] (step/ask st [:vectors :answers] [:recorded nil] {:op :write :req {:op :tx :branch :db :tx-data tx}})]
                                            [st [w]])
                                          [(assoc st :running false) []]))
                                      (let [{:keys [model vectors tokens]} (:value r)
                                            [st w] (step/ask st [:vectors :answers] [:written (count es)]
                                                             {:op :write :req {:op :tx :branch :db :tx-data (into (tx-data es ts vectors tokens model) gone)}})]
                                        [st [w]])))
                        :written (let [st (cond-> (assoc st :running false)
                                            (not (contract/error? r)) (-> (update :embedded + es) (assoc :written-t (:t r)))
                                            (contract/error? r) (assoc :clean nil :seen nil))
                                       l (:latest st)]
                                   (if (and l (>= (:t l) (:written-t st)))
                                     (round (dissoc st :latest) l)
                                     [st []]))
                        ;; the failure's own commit starts no round (it would ask again and fail again,
                        ;; on an idle main): the next round reads a commit after it
                        :recorded (let [st (cond-> (assoc st :running false) (not (contract/error? r)) (assoc :written-t (inc (:t r))))
                                        l (:latest st)]
                                    (if (and l (>= (:t l) (:written-t st)))
                                      (round (dissoc st :latest) l)
                                      [st []]))
                        [st []])))]
     [st (if (seq outs) {:to-api outs} {})])))
