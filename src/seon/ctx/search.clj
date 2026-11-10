(ns seon.ctx.search
  "Search and relevance over what an agent's record can pull: the pool of program vars, sections and tests a task's plan reaches, ranked (docs/ctx.md#what-the-scorer-reads-from-a-pulled-node)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.db :as pdb]
            [seon.ref :as ref]
            [seon.doc :as doc]
            [seon.config :as cfg]
            [seon.vector :as vector]
            [seon.program :as program]
            [seon.ctx :as ctx]))

(defn- near
  "Where position at of text is: its line's number and its column (1-based), and the
  whole line (the printer budgets it with the block)."
  {:malli/schema [:=> [:cat :string :int] [:map [:line pos-int?] [:column pos-int?] [:text :string]]]}
  [text at]
  (let [at (min at (count text))
        start (if (pos? at) (inc (or (str/last-index-of text "\n" (dec at)) -1)) 0)
        end (or (str/index-of text "\n" at) (count text))]
    {:line (:at (doc/line-at text start)) :column (inc (- at start))
     :text (subs text start end)}))

(defn repaired
  "Each repair the forgiving reader (seon.reader/forgive) made to an eval call's code, as that
  call's result tells it (:repaired, docs/prompt.md): what it dropped or inserted (a delimiter
  it closed or removed, a ;; that made a line of text a comment), and where, the line and
  column in source (the code as the REPL ran it) and that whole line."
  {:malli/schema [:=> [:cat :string [:vector :map]] :seon.eval/repairs]}
  [source repairs]
  (vec (for [{:keys [at dropped inserted]} repairs]
         (cond-> {:near (near source at)}
           (seq dropped) (assoc :dropped dropped)
           (seq inserted) (assoc :inserted inserted)))))


(defn program-vars
  "Of cites (cite entities, as seon.task/cites-of reads them: each target's identity), the names of the program's
  vars (seon.program/program-names), in their order: what a task's data shows as its write set. See
  docs/references.md#an-entity-cites."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :map]] [:vector :qualified-symbol]]}
  [db cites]
  (vec (program/program-names db (into [] (keep #(get-in % [:seon.cite/target :var/name])) cites))))

;; ---- relevance: one calibrated score for a context and an item (docs/vector.md#relevance) ----

(defn- item-text
  "Entity e's text as relevance's lexical feature reads it: its name twice (a var's, a section's
  address and heading, a schema key) and its text (a form's source, a section's, a schema's form)."
  {:malli/schema [:=> [:cat :seon.host/db :int] :string]}
  [db e]
  (let [x (d/entity db e)
        [n t] (cond (:form/id x) [(some->> (:var/_form x) (map :var/name) sort first str) (:form/src x)]
                    (:seon.doc/address x) [(str (:seon.doc/address x) " " (:seon.doc/heading x)) (:seon.doc/text x)]
                    (:schema/key x) [(str (:schema/key x)) (pr-str (:schema/form x))]
                    :else ["" ""])]
    (str n " " n " " t)))

(defn- section-names
  "Each var name to the wiki's sections that name it, as a function of the name: one seek of :seon.doc/names (indexed) for
  the name asked, so `neighbours` and `degree` cost the names they read, never the wiki's."
  {:malli/schema [:=> [:cat :seon.host/db] :seon.host/fn]}
  [db] (fn [nm] (map :e (d/datoms db :avet :seon.doc/names nm))))

(defn- neighbours
  "Entity e's neighbours in the reference graph on db, a set of entities: a form's are the forms of
  the vars it calls or names, the schema entries of the keys it names, the forms calling or naming
  its vars and the sections naming them (names, `section-names`); a section's the forms of the
  vars it names, the sections it references or is referenced by, its parent and its children; a
  schema entry's the forms naming its key. Each read of who names it takes at most cap datoms, so a
  var every form names (seon.contract/checked) costs cap, not its hundreds of callers."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/fn :int pos-int?] [:set :int]]}
  [db names e cap]
  (let [x (d/entity db e)
        form-of #(some-> (d/entity db [:var/name %]) :var/form :db/id)]
    (disj (into #{} (remove nil?)
                (cond (:form/id x) (concat (map form-of (concat (:form/refs x) (:form/quoted x)))
                                           (map #(pdb/eid db :schema/key %) (:form/keywords x))
                                           (for [v (:var/_form x) a [:form/refs :form/quoted] d (take cap (d/datoms db :avet a (:var/name v)))] (:e d))
                                           (for [v (:var/_form x) s (take cap (names (:var/name v)))] s))
                      (:seon.doc/address x) (concat (map form-of (:seon.doc/names x))
                                                        (map :db/id (:seon.doc/references x))
                                                        (map :db/id (:seon.doc/_references x))
                                                        (some-> (:seon.doc/parent x) :db/id vector)
                                                        (map :db/id (:seon.doc/_parent x)))
                      (:schema/key x) (map :e (take cap (d/datoms db :avet :form/keywords (:schema/key x))))
                      :else []))
          e)))

(defn- degree
  "How many neighbours entity e has in the reference graph (`neighbours`), counted to cap by seeks
  of its datoms (no reference resolved; a form's or key's entity never made): what a form names,
  its vars' callers and the sections naming them; a section's names, links, linkers and children;
  a key's readers.
  Each read stops at cap, so a var every form names costs cap datoms."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/fn :int pos-int?] nat-int?]}
  [db names e cap]
  (let [n #(bounded-count cap (d/datoms db %1 %2 %3))
        k (fn [a] (n :eavt e a))]
    (min cap
         (cond (seq (d/datoms db :eavt e :form/id))
               (+ (k :form/refs) (k :form/quoted) (k :form/keywords)
                  (reduce + 0 (for [v (d/datoms db :avet :var/form e) :let [nm (:v (first (d/datoms db :eavt (:e v) :var/name)))]]
                                (+ (bounded-count cap (names nm)) (n :avet :form/refs nm) (n :avet :form/quoted nm)))))
               (seq (d/datoms db :eavt e :seon.doc/address))
               (let [x (d/entity db e)]
                 (+ (k :seon.doc/names) (k :seon.doc/references) (count (:seon.doc/_references x)) (count (:seon.doc/_parent x))))
               :else (if-let [key (:v (first (d/datoms db :eavt e :schema/key)))] (n :avet :form/keywords key) 0)))))

(defn- pushed
  "Personalized PageRank from seeds over the reference graph (`neighbours`), by local push
  (Andersen, Chung and Lang 2006): a node holding at least eps of residual mass per neighbour keeps
  an :alpha share of it (:seon.config.render/relevance-features) and passes the rest to its
  neighbours evenly; a hub, a node of :hub neighbours or more, never pushes (as at eps 1e-3 none but
  a lone seed could: on the 57 held-out plans a :hub of 64 ranked exactly as no bound). {:rank {e
  rank} :degree {e degree}}, :degree every node's degree it counted (to :hub), so a caller counts
  none twice. Cost: each push keeps at least alpha eps of the one unit of mass, so at most
  1/(alpha eps) pushes; a node's degree is counted when its residual first reaches eps (`degree`:
  seeks, at most :hub datoms a read) and its neighbours read only when it pushes: flat in the
  store's size."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/fn [:set :int] number?]
                  [:map [:rank [:map-of :int number?]] [:degree [:map-of :int nat-int?]]]]}
  [db names seeds eps]
  (let [{:keys [alpha hub]} (cfg/value :seon.config.render/relevance-features)]
    ;; queued: the nodes in the queue, each once; ds: each node's degree, counted once; nbs: a
    ;; pushing node's neighbours, read once
    (loop [p {} r (zipmap seeds (repeat (/ 1.0 (max 1 (count seeds))))) queue (vec seeds) queued (set seeds) ds {} nbs {}]
      (if-let [u (peek queue)]
        (let [queue (pop queue) queued (disj queued u)
              ru (get r u 0.0)
              du (or (ds u) (degree db names u hub))
              ds (assoc ds u du)]
          (if (or (>= du hub) (zero? du) (< ru (* eps du)))
            (recur p r queue queued ds nbs)
            (let [ns (or (nbs u) (neighbours db names u hub))
                  share (/ (* (- 1 alpha) ru) (max 1 (count ns)))
                  r (reduce (fn [r v] (update r v (fnil + 0.0) share)) (assoc r u 0.0) ns)
                  more (filterv #(and (>= (get r %) eps) (not (queued %))) ns)]
              (recur (update p u (fnil + 0.0) (* alpha ru)) r (into queue more) (into queued more) ds (assoc nbs u ns)))))
        {:rank p :degree ds}))))

(def kind-attr
  "Each kind a search finds and relevance scores, the identity attribute its entities have (a var's and a test's
  form, a section, a task, a schema entry): the one table of kinds search's meaning scan and relevance's pool read."
  {:var :form/id :test :form/id :section :seon.doc/address :task :seon.task/id :schema :schema/key})

(defn- percent
  "The relevance a logit l is, by kind k's calibration (:seon.config.render/relevance-calibration):
  its knots [[logit p] ...] read piecewise linearly, held flat past either end."
  {:malli/schema [:=> [:cat [:vector [:tuple number? number?]] number?] number?]}
  [knots l]
  (let [[[x0 y0]] knots [xn yn] (peek knots)]
    (cond (<= l x0) y0
          (>= l xn) yn
          :else (some (fn [[[a ya] [b yb]]] (when (<= a l b) (+ ya (* (- yb ya) (/ (- l a) (max 1e-9 (- b a)))))))
                      (partition 2 1 knots)))))

(defn- z
  "xs as z-scores over their non-nil values (a nil is 0: no evidence either way)."
  {:malli/schema [:=> [:cat [:sequential [:maybe number?]]] [:vector number?]]}
  [xs]
  (let [ys (filterv some? xs) n (count ys)
        mu (/ (reduce + 0.0 ys) (max 1 n))
        sd (Math/sqrt (/ (reduce + 0.0 (map #(* (- % mu) (- % mu)) ys)) (max 1 n)))]
    (mapv #(if (and % (pos? sd)) (/ (- % mu) sd) 0.0) xs)))

(defn relevance
  "How relevant each item near a context is, as a calibrated chance (0 to 1) that the context's work
  needs it, by kind (docs/vector.md#relevance). The context: :qv the query's vector (a plan's, a
  search's), :text its words, :seeds the entities it already holds (the forms of the vars it cites
  or changed, the sections it cites, the schema entries it names), :touched those of them it
  worked on (changed or read), :candidates (optional) entities a caller found by other means
  (a search's text hits), scored beside the pool, :near (optional) the caller's scan of each kind's nearest by
  meaning, {attr [[e cosine] ...]}, read for that kind's pool in place of a second scan. For each kind of kinds (:var, :section or :schema; `kind-attr`) the pool is the
  :seon.config.render/relevance-pool nearest by meaning (seon.vector/near) and the seeds'
  neighbourhood (each seed's first neighbours by each read, and those `pushed` at
  :seon.config.render/relevance-eps reached), keep? (of kind and entity) true;
  each item scored by a logistic over five features (:seon.config.render/relevance-weights): zc its
  cosine with qv, zb its BM25 among the pool (seon.doc/bm25: idf over the pool itself, the name counted twice; search's own text ranking is the index's, over every text) for the text's terms (seon.doc/terms), zt its cosine with the touched items' mean
  vector, each a z-score over the pool, g1 log(1 + the seeds it neighbours x its rarity,
  (1 + cap)/(1 + its degree to cap)) and ppr log(1 + scale x its rank), the constants
  :seon.config.render/relevance-features; the logit read as a chance by the kind's calibration (`percent`). zb scored by the
  index's bm25 instead (idf over every text) ranked a changed item first in 14 of 49 dev tasks against 23: a pool of near
  neighbours is told apart by the words rare among them, not in the whole program (the context lab's L0, docs/vector.md#relevance). Measured on 57 merged
  tasks' plans held out: functions 0.427 at 5 against the rank fusion's 0.367, sections 0.798
  against 0.651. Answers {kind [[e p] ...]}, best first, the touched items left out (a caller leaves out
  what its page already shows)."
  {:malli/schema [:=> [:cat :seon.host/db [:map [:qv [:maybe [:vector number?]]] [:text :string] [:seeds [:set :int]] [:touched [:set :int]]
                                           [:candidates {:optional true} [:set :int]]
                                           [:near {:optional true} [:maybe [:map-of :keyword [:sequential [:tuple :int number?]]]]]]
                       [:set :keyword] :seon.host/fn]
                  [:map-of :keyword [:vector [:tuple :int number?]]]]}
  [db {:keys [qv text seeds touched candidates] given :near} kinds keep?]
  (let [names (section-names db)
        {rank :rank counted :degree} (if (seq seeds) (pushed db names seeds (cfg/value :seon.config.render/relevance-eps)) {:rank {} :degree {}})
        ;; a link to a seed weighs by the item's rarity, (1 + cap)/(1 + its degree to cap), so what every form
        ;; names (seon.contract/checked, seon.db/eid) weighs little: 20 to 1 plumbing lines of 570
        cap (cfg/value :seon.config.render/relevance-pool)
        links (frequencies (mapcat #(neighbours db names % cap) seeds))
        {:keys [rarity-cap ppr-scale]} (cfg/value :seon.config.render/relevance-features)
        ;; its degree as the push counted it, else counted to the cap
        rare (fn [e] (/ (inc rarity-cap) (inc (min rarity-cap (or (counted e) (degree db names e rarity-cap))))))
        weights (cfg/value :seon.config.render/relevance-weights)
        knots (cfg/value :seon.config.render/relevance-calibration)
        qs (set (doc/terms text))
        centroid (when (seq touched)
                   (let [vs (keep #(vector/vector-of db %) touched)]
                     (when (seq vs)
                       (let [s (apply mapv + vs) n (Math/sqrt (reduce + 0.0 (map #(* % %) s)))]
                         (when (pos? n) (mapv #(/ % n) s))))))
        ;; a kind's nearest: the caller's scan when it handed one (a search scans each kind once), else its own
        near (fn [attr] (cond (contains? given attr) (map first (take cap (given attr)))
                              qv (map first (vector/near db qv cap {:of attr}))
                              :else []))]
    (into {} (for [kind kinds
                   :let [attr (kind-attr kind)
                         of-kind? #(seq (d/datoms db :eavt % attr))
                         pool (into [] (comp (distinct) (remove touched) (filter #(and (of-kind? %) (keep? kind %))))
                                    (concat (near attr) seeds candidates (keys links) (keys rank)))]]
               [kind (if (empty? pool)
                       []
                       (let [w (weights kind)
                             zc (z (if qv (vector/scores db qv pool) (repeat (count pool) nil)))
                             zb (z (let [fs (mapv #(frequencies (doc/terms (item-text db %))) pool)] (doc/bm25 qs fs (mapv #(reduce + 0 (vals %)) fs))))
                             zt (z (if centroid (vector/scores db centroid pool) (repeat (count pool) nil)))]
                         (->> (map (fn [e c b t]
                                     (let [l (+ (:intercept w) (* (:zc w) c) (* (:zb w) b) (* (:zt w) t)
                                                (* (:g1 w) (Math/log (+ 1.0 (* (get links e 0) (if (links e) (rare e) 0.0)))))
                                                (* (:ppr w) (Math/log (+ 1.0 (* ppr-scale (get rank e 0.0))))))]
                                       [(- (percent (knots kind) l)) (- l) e]))
                                   pool zc zb zt)
                              sort (mapv (fn [[p _ e]] [e (- p)])))))]))))

(defn- hit-of
  "Entity e as the hits it is, each [kind name]: the vars (or tests) of a form, a section's address, a
  task's id, a schema key; the kinds asked only."
  {:malli/schema [:=> [:cat :seon.host/db :int [:set :keyword]] [:vector [:tuple :keyword :any]]]}
  [db e kinds]
  (let [x (d/entity db e)]
    (cond (:form/id x) (vec (for [v (:var/_form x) :let [kind (if (contains? (set (:var/meta v)) :test) :test :var)] :when (kinds kind)] [kind (:var/name v)]))
          (:seon.doc/address x) (if (kinds :section) [[:section (:seon.doc/address x)]] [])
          (:seon.task/id x) (if (kinds :task) [[:task (:seon.task/id x)]] [])
          (:schema/key x) (if (kinds :schema) [[:schema (:schema/key x)]] [])
          :else [])))


(defn- kind-of
  "Var v's search kind: :test for a test, else :var."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-symbol] :keyword]}
  [db v] (if (contains? (set (:var/meta (d/entity db [:var/name v]))) :test) :test :var))

(defn- entity-of
  "The entity that hit [kind name] is on db, or nil: the var, the section, the task or the schema entry."
  {:malli/schema [:=> [:cat :seon.host/db [:tuple :keyword :any]] [:maybe :int]]}
  [db [kind n]]
  (case kind
    (:var :test) (pdb/eid db :var/name n)
    :section (pdb/eid db :seon.doc/address n)
    :task (pdb/eid db :seon.task/id n)
    :schema (pdb/eid db :schema/key n)))

(defn- names-in
  "The names q holds that name something on db, as seeds of the references: q itself when it is a var's
  symbol or a keyword; in a text, each qualified symbol that names a var and each qualified keyword, as
  the citation parser reads them (seon.ref/parse: in backticks or bare), once each in first-mention order.
  See docs/references.md."
  {:malli/schema [:=> [:cat :seon.host/db [:or :string :qualified-symbol :qualified-keyword]] [:vector :any]]}
  [db q]
  (cond (keyword? q) [q]
        (symbol? q) (if (d/entity db [:var/name q]) [q] [])
        :else (into [] (comp (keep :seon.ref/name) (filter #(or (keyword? %) (d/entity db [:var/name %]))))
                    (ref/cited (:seon.ref/citations (ref/parse nil q))))))

(defn search
  "What on db is the best match for q (a text; a var's name, whose neighbours it also finds by who
  calls it, whom it calls and the sections naming it; or a schema key, whose readers and writers it
  finds), as a vector of hits best first, each {:kind :name :e :score :by :relevance :line :at}:
  :kind (:var, :test, :section, :task or :schema), :name the var, section address, task id or schema key, :e its entity,
  :score the reciprocal-rank score of seon.vector/rrf-scores over the rankings below, :by the set of them it is in (:text :meaning :refs),
  :relevance the chance it is worth reading (seon.ctx.search/relevance, over q's vector, words and the
  names it holds, the first k x :seon.config.render/related-near hits by the fused score joined to its
  pool; a function, section or schema key, not yet a test or task),
  :line the line of its text holding a word of q and :at its number. The rankings: the text (seon.doc/tokens:
  BM25 over q's identifier-split words, kept at z >= :seon.config.render/search-token-z), meaning
  (seon.vector/near from qv, the query's vector or an entity with one; none when qv is nil) and the
  references (the callers, callees and naming sections of the names in q: its var, its key, the
  qualified names and keywords of a text; never of the text hits). Best first: by relevance where a hit
  has one, then by the fused score, equal ones by name. A hit with a relevance below :min is left out. When hits are left out by :k or :min, a last entry
  {:kind :more :left n :counts {kind n} :more \"...\"} says how many per kind and the call that returns
  them. opts, all optional: :kinds (a set of those kinds; #{:var :section} by default: tests, tasks and
  schema keys on request), :by (a set of :text :meaning :refs, all), :ns (a name: only vars and tests
  under it, seon.doc/named-under?), :k (:seon.config.render/search), :min (:seon.config.render/relevance-summary,
  the page's cut).
  Plain data, so a caller filters it by Clojure."
  {:malli/schema [:=> [:cat :seon.host/db [:or :string :qualified-symbol :qualified-keyword]
                       [:map [:kinds {:optional true} [:set :keyword]] [:by {:optional true} [:set :keyword]]
                        [:ns {:optional true} :string] [:k {:optional true} pos-int?] [:min {:optional true} :double]]
                       [:maybe [:or :int [:vector number?]]]]
                  [:vector [:map [:kind :keyword]]]]}
  [db q {:keys [kinds by k] prefix :ns minimum :min :as opts} qv]
  (let [kinds (or kinds #{:var :section})
        by (or by #{:text :meaning :refs})
        k (or k (cfg/value :seon.config.render/search))
        minimum (or minimum (cfg/value :seon.config.render/relevance-summary))
        text (str q)
        in-ns? (fn [[kind n]] (or (not (#{:var :test} kind)) (nil? prefix) (doc/named-under? (str n) prefix)))
        toks (if (by :text) (filterv #(in-ns? [(:kind %) (:name %)]) (doc/tokens db text kinds (cfg/value :seon.config.render/search-token-z))) [])
        known (into {} (map (fn [h] [[(:kind h) (:name h)] (select-keys h [:in :z])])) toks)
        n (* k (cfg/value :seon.config.render/related-near))
        ;; each kind's nearest, scanned once (near takes one attribute) to the larger of the n this ranking reads and
        ;; the pool relevance reads, which it is handed
        nears (when (and (by :meaning) qv)
                (let [m (max n (cfg/value :seon.config.render/relevance-pool))]
                  (into {} (for [attr (sort (distinct (map kind-attr kinds)))] [attr (vector/near db qv m {:of attr})]))))
        ;; one ranking by meaning: each kind's first n nearest, merged by cosine, which one query's scores share
        meaning (if nears
                  (into [] (comp (mapcat (fn [[e _]] (hit-of db e kinds))) (filter in-ns?) (distinct))
                        (sort-by (comp - second) (mapcat #(take n %) (vals nears))))
                  [])
        seeds (names-in db q)
        refs (if (by :refs)
               (into [] (comp (filter in-ns?) (filter (comp kinds first)) (distinct))
                     (concat (for [s seeds :when (symbol? s) c (doc/callers db s) :let [kd (kind-of db c)] :when (kinds kd)] [kd c])
                             (for [s seeds :when (symbol? s) c (map :var (ctx/callees db s)) :let [kd (kind-of db c)] :when (kinds kd)] [kd c])
                             (for [s seeds :when (symbol? s) e (d/q '[:find [?e ...] :in $ ?n :where [?e :seon.doc/names ?n]] db s) :when (kinds :section)]
                               [:section (:seon.doc/address (d/entity db e))])
                             ;; a schema key: the functions whose code or contract name it
                             (for [s seeds :when (keyword? s) c (doc/callers db s) :let [kd (kind-of db c)] :when (kinds kd)] [kd c])))
               [])
        rankings (select-keys {:text (mapv (juxt :kind :name) toks) :meaning meaning :refs refs} (seq by))
        scores (vector/rrf-scores (vec (vals rankings)))
        in (fn [h] (into #{} (keep (fn [[m r]] (when (some #{h} r) m))) rankings))
        first-line (fn [[kind n]] (case kind
                                    (:var :test) (or (some-> (d/entity db [:var/name n]) :var/doc ref/lead-sentence) "")
                                    :section (or (some-> (d/entity db [:seon.doc/address n]) :seon.doc/lead) "")
                                    :task (or (some-> (d/entity db [:seon.task/id n]) :seon.task/plan (str/split #"\n") first) "")
                                    ""))
        ;; each hit of a kind relevance weighs is scored by it, the hits joined to its pool: a function by
        ;; its form, a section, a schema entry (seon.ctx.search/relevance; a test or a task has no chance yet)
        rkind #{:var :section :schema}
        form-of (fn [[kind n]] (case kind
                                 ;; a var's form: its :var/form, or the form whose :form/defines holds it
                                 :var (let [v (d/entity db [:var/name n])] (or (some-> v :var/form :db/id) (some-> v :form/_defines first :db/id)))
                                 (entity-of db [kind n])))
        ;; the hits search can show: the first k x related-near by the fused score, a bound whatever
        ;; the corpus (a common word's text hits grow with it); the rest keep their fused order after
        ;; each sort key made once, never in the comparator (a str of a hit at every comparison was most of a search's own ticks)
        hits (into [] (comp (map peek) (take n)) (sort (map (fn [[h s]] [(- s) (str h) h]) scores)))
        chance (let [ks (into #{} (keep (comp rkind first)) hits)]
                 (if (empty? ks)
                   {}
                   (let [qvec (if (int? qv) (vector/vector-of db qv) qv)
                         sd (into #{} (keep #(if (keyword? %) (pdb/eid db :schema/key %) (some-> (d/entity db [:var/name %]) :var/form :db/id))) seeds)
                         r (relevance db {:qv qvec :text text :seeds sd :touched #{} :near nears
                                          :candidates (into #{} (comp (filter (comp rkind first)) (keep form-of)) hits)}
                                      ks (constantly true))]
                     (into {} (for [[_ es] r [e p] es] [e p])))))
        ;; each scored hit's chance, read once (a sort's key is read at every comparison)
        rels (into {} (for [h hits :let [r (when (rkind (first h)) (some-> (form-of h) chance))] :when r] [h r]))
        rel rels
        ;; best first: by relevance where a hit has one, then the rest by the fused score
        ranked (mapv peek (sort (map (fn [[h s :as e]] (let [r (rel h)] [(if r 0 1) (- (or r 0.0)) (- s) (str h) e])) scores)))
        kept (remove (fn [[h _]] (let [r (rel h)] (and r (< r minimum)))) ranked)
        shown (take k kept)
        left (concat (drop k kept) (filter (fn [[h _]] (let [r (rel h)] (and r (< r minimum)))) ranked))]
    (cond-> (mapv (fn [[h s]] (let [l (first-line h) r (rel h)]
                                (merge (cond-> {:kind (first h) :name (second h) :e (entity-of db h) :score s :by (in h)}
                                         (seq l) (assoc :line l) r (assoc :relevance r))
                                       ;; the line of its text holding a word of q, read for a hit shown alone (seon.doc/line-of)
                                       (some->> (:in (known h)) (doc/line-of db text)))))
                  shown)
      (seq left) (conj {:kind :more :left (count left) :counts (frequencies (map (comp first key) left))
                        :more (str "(my.agent/search " (pr-str q) " " (pr-str (assoc opts :k (+ k (count left)) :min 0.0)) ") returns them; a narrower :kinds or :ns finds fewer")}))))

