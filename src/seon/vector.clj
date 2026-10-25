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
            [seon.step :as step] [jolt.ffi :as ffi] [datahike.api :as d] [clojure.string :as str]))

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

(defn vector-of
  "Entity e's vector on db, or nil when it has none."
  {:malli/schema [:=> [:cat :seon.host/db :int] [:maybe [:vector number?]]]}
  [db e] (some-> (stored db e :seon.vector/f16) unpack))

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
         rows (into [] (filter #(and (keep? (:e %)) (not= self (:e %)))) (d/datoms db :aevt :seon.vector/coarse))]
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
                   es (mapv #(:e (nth rows %)) (ffi/read-array ip :int64 m 0))
                   fp (ffi/alloc a (max 1 (* m 2 dims))) qf (ffi/alloc a (* 4 dims)) fs (ffi/alloc a (max 4 (* 4 m)))]
               (dotimes [j m] (ffi/write-bytes (ffi/slice fp (* j 2 dims)) (stored db (nth es j) :seon.vector/f16)))
               (ffi/write-array qf :float (float-array qv) 0)
               (c-fine fp m dims fd qf fs)
               ;; best first; the entity id breaks a tie, so one db answers one order
               (->> (map (fn [e s] [(- s) e]) es (ffi/read-array fs :float m 0))
                    sort (take k) (mapv (fn [[s e]] [e (- (double s))])))))))))))

(defn rrf
  "Rankings fused by reciprocal rank (Cormack et al. 2009): each item scores the sum of 1/(k + rank)
  over the rankings it is in, k :seon.config.vector/rrf-k; best first, the name breaking a tie.
  Measured fusing the call graph with embeddings (docs/vector.md#fused-with-the-call-graph); the
  screen's related lists fuse their reference graph with meaning by it (seon.context/related)."
  {:malli/schema [:=> [:cat [:sequential [:sequential :any]]] [:vector :any]]}
  [rankings]
  (let [k (cfg/value :seon.config.vector/rrf-k)
        s (apply merge-with + {} (for [r rankings] (into {} (map-indexed (fn [i x] [x (/ 1.0 (+ k i 1))])) r)))]
    (mapv peek (sort (map (fn [[x v]] [(- v) (str x) x]) s)))))

;; ---- what is embedded --------------------------------------------------------------------
;; EmbeddingGemma's prompts (its model card; v1's script/embed/gemma_text.py): a document is
;; embedded with the document prompt, a task's plan as what a search asks.

(def document-prompt "The prompt before a document's text." "title: none | text: ")
(def query-prompt "The prompt before a query's text." "task: code retrieval | query: ")

(def ^:private texts
  "Each embedded kind: the attribute holding its text, and the text the model reads."
  {:form/src (fn [src] (str document-prompt src))
   :seon.section/text (fn [text] (str document-prompt text))
   ;; a schema's form, so the screen's related schema keys are found by meaning too
   ;; (docs/vector.md#what-the-work-is-likely-to-need-as-an-eval)
   :schema/form (fn [form] (str document-prompt form))
   :seon.task/plan (fn [plan] (str query-prompt plan))})

(defn- due-among
  "Of the text datoms ds, those whose vector is due for model, made (an entity's
  :seon.vector/text-t) and of (its :seon.vector/model) read as functions of the entity:
  [[e text t] ...] in entity order."
  {:malli/schema [:=> [:cat [:sequential :any] :seon.host/fn :seon.host/fn :string] [:vector [:tuple :int :string :int]]]}
  [ds made of model]
  (vec (sort (for [{:keys [e a v tx]} ds
                   :when (or (not= model (of e)) (not= tx (made e)))]
               [e ((texts a) v) tx]))))

(defn due
  "The texts on db whose vector is due for model: each form's source, wiki section's text, schema
  entry's form and task's plan with no vector, a vector made from an earlier text (its :seon.vector/text-t is not
  the text's transaction), or another model's. [[e text t] ...] in entity order, t the text's
  transaction. With ds, only those of the text datoms ds (`written`), each one's vector read by
  one seek: the cost of ds, not of the store."
  {:malli/schema [:function [:=> [:cat :seon.host/db :string] [:vector [:tuple :int :string :int]]]
                  [:=> [:cat :seon.host/db :string [:sequential :any]] [:vector [:tuple :int :string :int]]]]}
  ([db model]
   (let [made (into {} (map (juxt :e :v)) (d/datoms db :aevt :seon.vector/text-t))
         of (into {} (map (juxt :e :v)) (d/datoms db :aevt :seon.vector/model))]
     (due-among (for [a (keys texts) d (d/datoms db :aevt a)] d) #(made %) #(of %) model)))
  ([db model ds]
   (due-among (filter (comp texts :a) ds) #(stored db % :seon.vector/text-t) #(stored db % :seon.vector/model) model)))

(defn written
  "The text datoms db holds that db from does not (datahike.api/datoms-diff, which skips the
  subtrees they share): the forms, sections and plans a branch wrote since from. Cost: the
  datoms that differ."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db] [:vector :any]]}
  [from db] (filterv (comp texts :a) (second (d/datoms-diff from db :eavt []))))

(defn tx-data
  "The facts of embedded texts: entity es[i]'s vector vs[i] of the text at transaction ts[i], that
  text's whole tokens ns[i] (past :seon.config.vector/max-tokens its first were embedded), made by
  model."
  {:malli/schema [:=> [:cat [:sequential :int] [:sequential :int] [:sequential [:vector {:min 1} number?]] [:sequential :int] :string] [:vector :map]]}
  [es ts vs ns model]
  (mapv (fn [e t v n] (assoc (pack v) :db/id e :seon.vector/model model :seon.vector/text-t t :seon.vector/tokens n)) es ts vs ns))

;; ---- the model (native/embed.c over llama.cpp: root's :embed worker alone loads it) ------
;; Bound here, resolved on the first call: the library is loaded by `open`, in the worker, so an
;; agent or the fork server never maps it (ggml starts threads; docs/failure-points.md#physics).

(lib/defc c-open "seon_embed_open" [:pointer :int :int :int :int] :pointer {:blocking true})
(lib/defc c-dims "seon_embed_dims" [:pointer] :int)
(lib/defc c-batch "seon_embed_batch" [:pointer :pointer :pointer :int :int :pointer :pointer] :int {:blocking true})

(defn model-id
  "The id of the model file at path, on every vector it makes: its name, which bin/build makes its
  sha256 (bin/env's SEON_EMBED_MODEL)."
  {:malli/schema [:=> [:cat :string] :string]}
  [path] (let [f (.getName (java.io.File. path))] (subs f 0 (or (str/last-index-of f ".") (count f)))))

(defn open
  "The :embed kind's host object: the model at :seon.config.vector/model, loaded in this process
  with a context of :seon.config.vector/batch-tokens tokens and :seon.config.vector/batch-texts
  texts and ggml's CPU pool of :seon.config.vector/threads (seon_embed_open), {:handle :model :dims}; a missing file or a model llama.cpp cannot load
  is :seon.vector/model-unopened (its cause is in llama.cpp's log)."
  {:malli/schema [:=> [:cat :any] :map]}
  [_ctx]
  (let [path (cfg/value :seon.config.vector/model)]
    (if-not (.exists (java.io.File. path))
      (contract/error :seon.vector/model-unopened 'seon.vector/open (str "no model file at " path " (bin/build embed fetches it)") {:path path})
      (do (ffi/load-library (cfg/value :seon.config.vector/lib))
          (let [h (ffi/with-arena [a] (c-open (ffi/string->ptr a path) (cfg/value :seon.config.vector/batch-tokens)
                                              (cfg/value :seon.config.vector/batch-texts) (cfg/value :seon.config.vector/threads) 0))]
            (if (zero? (ffi/address h))
              (contract/error :seon.vector/model-unopened 'seon.vector/open (str "llama.cpp could not load " path) {:path path})
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
            (contract/error :seon.vector/encode-failed 'seon.vector/embed (str "llama.cpp's encode answered " r) {:code r})
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
  "At main's db, the next round: the first :texts due (:seon.config.vector/texts; 0 asks nothing) go
  to the :embed effect.
  Answers [st outputs]; nothing due, nothing asked."
  {:malli/schema [:=> [:cat :map :seon.host/db] [:tuple :map [:vector :map]]]}
  [st db]
  (let [due (take (:texts st) (remove (comp (:unembeddable st) first) (due db (:model st))))]
    (if (empty? due)
      [(assoc st :running false) []]
      (let [[st m] (step/ask st [:vectors :answers] [:embedded [(mapv first due) (mapv peek due)]]
                             {:op :fx :kind :embed :req {:texts (mapv second due)}})]
        [(assoc st :running true) [m]]))))

(defn vectors-proc
  "Keeps main's vectors current (docs/vector.md#what-is-embedded). At main's commit notice
  (:main, a sliding buffer of one), the texts due (seon.vector/due) go to root's :embed effect,
  :seon.config.vector/texts at a time, and their vectors are written to main; that commit's notice
  starts the next round, so a backlog is embedded round by round and an idle main asks nothing.
  One round at a time: a notice during one is kept, and the round after the write reads the
  latest notice at or after it. A failed embedding is root's problem (seon.fx/outcome) and the
  next notice tries again, except texts llama.cpp could not encode (:seon.vector/encode-failed):
  those are kept out of later rounds (:unembeddable, shown in its ping), so they never hold back
  the texts due after them."
  {:malli/schema :seon.step/step-fn}
  ([] {:params {:model "the model id its vectors are made by (seon.vector/model-id)"
                :texts "the texts one round asks for, :seon.config.vector/texts (0: none, the model never loads)"}
       :ins {:main "main's commit notice {:t :db}, a sliding buffer of one" :answers "the api's answers"}
       :outs {:to-api "its requests to the api: the :embed effect, the write to main"}
       :workload :io
       :ping-map-fn (fn [st] (-> (select-keys st [:running :embedded :written-t]) (assoc :unembeddable (count (:unembeddable st)))))})
  ([args] {:rid 0 :pending {} :model (:model args) :texts (:texts args) :running false :embedded 0 :written-t -1 :unembeddable #{}})
  ([st _] st)
  ([st in m]
   (let [[st outs]
         (case in
           :main (if (or (:running st) (< (:t m) (:written-t st)))
                   [(cond-> st (>= (:t m) (get-in st [:latest :t] -1)) (assoc :latest m)) []]
                   (round (dissoc st :latest) (:db m)))
           :answers (let [[st [step es]] (step/answered st (:tag m)) r (:r m)]
                      (case step
                        :embedded (let [[es ts] es]
                                    (if (contract/error? r)
                                      [(cond-> (assoc st :running false)
                                         (= :seon.vector/encode-failed (:seon.error/type r)) (update :unembeddable into es))
                                       []]
                                      (let [{:keys [model vectors tokens]} (:value r)
                                            [st w] (step/ask st [:vectors :answers] [:written (count es)]
                                                             {:op :write :req {:op :tx :branch :db :tx-data (tx-data es ts vectors tokens model)}})]
                                        [st [w]])))
                        :written (let [st (cond-> (assoc st :running false)
                                            (not (contract/error? r)) (-> (update :embedded + es) (assoc :written-t (:t r))))
                                       l (:latest st)]
                                   (if (and l (>= (:t l) (:written-t st)))
                                     (round (dissoc st :latest) (:db l))
                                     [st []]))
                        [st []])))]
     [st (if (seq outs) {:to-api outs} {})])))
