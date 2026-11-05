(ns seon.context
  "The agent's context, a render of its record (docs/render.md#a-part-answers-data): the system
  prompt, then its record (its evals, messages and replies, `records`) and the parts of its page
  the model reads (the task, each var the plan cites, how its work stands:
  `status`), each part's data sent by its model render and printed through the one value printer
  (seon.print). Nothing is stored: a turn records its basis (the commit it read and what it took
  that no fact holds), and `render` makes the text from it, for the model and again for anyone who
  reads it later."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [clojure.string :as str] [datahike.api :as d] [seon.render :as render]
            [seon.task :as task] [seon.db :as pdb] [seon.merge :as pm]
            [seon.print :as print] [seon.print.select :as select] [seon.ref :as ref] [seon.doc :as doc] [seon.config :as cfg] [seon.model :as model] [seon.vector :as vector] [seon.ctx :as ctx] [seon.ratom :as ratom] [seon.agent :as agent] [seon.program :as program] [seon.proofs :as proofs] [seon.problems :as problems] [seon.reconcile :as r] [seon.edn :as sedn] [clojure.edn] [hasch.core :as hasch]))

(def help-page
  "The help page, docs/agent.md: what `doc` answers of an anchor, each section on demand. Main holds
  it as the wiki's sections from root's boot on (seon.root/start!)."
  "docs/agent.md")

(defn help-text
  "The help page as db holds it (seon.doc/page-text). A db without it (no root booted on it) is
  refused by this contract, never answered empty."
  {:malli/schema [:=> [:cat :seon.host/db] :string]}
  [db] (doc/page-text db help-page))

(def system-page
  "The system prompt, docs/prompt.md: what every agent reads first (its system message, the same
  bytes for every agent and turn, so a provider caches it): the standard format's short prompt
  (the owner's ruling: the model's context is the standard format; the help page is read on
  demand, doc). Main holds it as the wiki's sections, as the help page."
  "docs/prompt.md")

(defn system-text
  "The system prompt as db holds it (seon.doc/page-text): a turn's, at the commit it read. A db
  without it is refused by this contract, never answered empty."
  {:malli/schema [:=> [:cat :seon.host/db] :string]}
  [db] (doc/page-text db system-page))

(defn failure
  "A failing assertion as the agent reads it (the test AI render): the assertion as written
  and what it evaluated to, (= 9 (square 3)) got (= 9 6), never the assertion twice; the
  message only when it says something. See docs/agent.md#what-you-read."
  {:malli/schema [:=> [:cat :map] :map]}
  [r]
  (let [code #(cond-> % (string? %) print/code)]
    ;; an assertion as written is code (seon.print/code), printed as in the test file
    (cond-> {:assert (code (:expected r)) :got (code (or (:got r) (:actual r)))}
      (:message r) (assoc :message (:message r)))))

(defn failures
  "A test's failing assertions as the agent reads them (`failure`), those that got the same value
  with the same message one entry, {:asserts [assertion ...] :got v}, in first-failure order: nine
  assertions that each threw one NullPointerException are one cause, read once. See docs/render.md#a-part-answers-data."
  {:malli/schema [:=> [:cat [:sequential :map]] [:vector :map]]}
  [fs]
  (let [ms (mapv failure fs)
        k (juxt :got :message)
        order (distinct (map k ms))
        by (group-by k ms)]
    (vec (for [g order :let [[m :as same] (by g)]]
           (if (next same) (-> m (dissoc :assert) (assoc :asserts (mapv :assert same))) m)))))

(defn callees
  "The program functions proof p calls directly (one level: its recorded form's :form/refs,
  the compiler's resolved references), each by its name, and for a helper of p's own namespace
  its source too (what the proof means, which an agent reads first); a var is program when db holds it (a branch's callee this process never loaded
  included). A callee's doc and contract are its source's or `doc`'s to show: a docstring
  each was a large share of the first request (task
  seon.context/an-agent-follows-its-instructions-to-green). See docs/agent.md#what-you-read."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-symbol] [:vector :map]]}
  [db p]
  (vec (for [q (sort (disj (set (d/q '[:find [?r ...] :in $ ?s :where [?e :var/name ?s] [?e :var/form ?f] [?f :form/refs ?r]] db p)) p))
             :let [v (d/entity db [:var/name q])]
             :when (and v (not= :test (:var/kind v)))]
         (cond-> {:var q}
           (= (namespace p) (namespace q)) (merge (some->> (doc/source-of db q) print/code (hash-map :source)))))))

(defn- refused
  "The done rule's reasons as the agent reads them, beyond its own red proofs (shown with
  the proofs): a red test names its failures and, as a red proof does, its source and the program
  functions it calls (calls-of, `callees`: a stray red cannot be traced from its failures alone), a stray one the form that makes it a proof, a contract gap its var, a design step that
  has not improved its plan the call that does, an open problem its work made true what it
  is (seon.problems/caused). src-of answers the source the branch defines for a test, or nil: a stray red it
  does not define is one of main's tests the branch removed, which judges all the same (seon.agent/
  test-plan's :removed), so it is marked :removed and offered no form that makes it a proof. own? answers whether
  the branch's work wrote or changed a test (its source differs from its merge base's): only such a stray red is
  offered the form that makes it a proof; another is main's test, which the work broke or its environment fails.
  See docs/render.md#how-the-work-stands."
  {:malli/schema [:=> [:cat [:vector :seon.reconcile/not-done] [:vector :map] ifn? ifn? ifn?] [:vector :map]]}
  [reasons reds src-of calls-of own?]
  (let [rs (remove #(= :proof-red (:reason %)) reasons)
        defined? (comp some? src-of)
        gaps (vec (keep #(when (= :contract-gap (:reason %)) (:symbol %)) rs))
        unmerged (vec (keep #(when (= :unmerged (:reason %)) (:key %)) rs))
        opened (vec (keep #(when (= :open-problem (:reason %)) (:key %)) rs))]
    ;; a contract gap and an unmerged entry say one thing about many names: one entry each,
    ;; its names a list the printer budgets
    (vec (concat
           (for [{:keys [reason symbol behind]} rs :when (not (#{:contract-gap :unmerged :open-problem} reason))]
             (case reason
               (:regression :stray-red) (let [src (src-of symbol) calls (when src (calls-of symbol))]
                                          (cond-> {:reason reason :test symbol}
                                            (seq (filter #(= symbol (:test %)) reds))
                                            (assoc :failures (failures (filter #(= symbol (:test %)) reds)))
                                            ;; its source and what it calls, as a red proof shows them
                                            src (assoc :source (print/code src))
                                            (seq calls) (assoc :calls calls)
                                            ;; a red test the agent wrote is a proof it has not registered, or one to delete; main's
                                            ;; test is no proof of this task, so it is never offered as one to register
                                            (and (= :stray-red reason) src (own? symbol))
                                            (assoc :do (list 'my.task/update! :seon.task/proofs 'conj (list 'quote symbol)))
                                            ;; main's test the branch removed: it still judges, from main's definition
                                            (and (= :stray-red reason) (not src))
                                            (assoc :removed true)))
               ;; a design made from a request carries none: it registers each it writes
               ;; judged before it merged main: merge it in, rerun the proofs, and done again
               :behind {:reason reason :behind behind :do '(my.branch/merge-main!)}
               :no-proofs {:reason reason :do '(my.task/update! :seon.task/proofs conj 'ns.x-test/a-proof)}
               :no-design {:reason reason :unchanged :seon.task/plan
                           :do '(my.task/update! :seon.task/plan str "\n\n" "<what the implement step must do>")}
               ;; no result: never run, or never written. Written, the runner whose results the
               ;; judge reads runs it (the template would have overwritten a finished deftest); else
               ;; what writes and runs it
               :proof-unknown (if (defined? symbol)
                                {:reason reason :test symbol :written true :do '(my.agent/prove)}
                                {:reason reason :test symbol
                                 ;; written, then judged by the runner whose results the done rule reads (my.agent/prove),
                                 ;; never clojure.test's own run, whose report goes to *out* and answers nil
                                 :do (list 'do (list 'in-ns (list 'quote (clojure.core/symbol (namespace symbol))))
                                           (list 'clojure.test/deftest (clojure.core/symbol (name symbol)) (list 'clojure.test/is false task/placeholder-assertion))
                                           '(my.agent/prove))})
               {:reason reason}))
           (when (seq gaps) [{:reason :contract-gap :vars gaps}])
           (when (seq unmerged) [{:reason :unmerged :keys unmerged}])
           (when (seq opened) [{:reason :open-problem :problems opened}])))))

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
  it closed or removed, a ;; that made a line of prose a comment), and where, the line and
  column in source (the code as the REPL ran it) and that whole line."
  {:malli/schema [:=> [:cat :string [:vector :map]] :seon.eval/repairs]}
  [source repairs]
  (vec (for [{:keys [at dropped inserted]} repairs]
         (cond-> {:near (near source at)}
           (seq dropped) (assoc :dropped dropped)
           (seq inserted) (assoc :inserted inserted)))))


(defn- program-vars
  "Of cites (cite entities, as ctx holds them or seon.task/cites-of reads them), the names of the program's
  vars (seon.program/program-names), in their order: what a task's data shows as its write set."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :map]] [:vector :qualified-symbol]]}
  [db cites]
  (vec (program/program-names db (into [] (comp (keep :seon.cite/name) (filter qualified-symbol?) (remove #(ref/task? db %))) cites))))

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
  "Each var name the wiki's sections name, to those sections: {name #{e}}, one read of
  :seon.doc/names (unindexed), so `neighbours` finds a var's sections by a lookup."
  {:malli/schema [:=> [:cat :seon.host/db] [:map-of :qualified-symbol [:set :int]]]}
  [db] (reduce (fn [m {:keys [e v]}] (update m v (fnil conj #{}) e)) {} (d/datoms db :aevt :seon.doc/names)))

(defn- neighbours
  "Entity e's neighbours in the reference graph on db, a set of entities: a form's are the forms of
  the vars it calls or names, the schema entries of the keys it names, the forms calling or naming
  its vars and the sections naming them (names, `section-names`); a section's the forms of the
  vars it names, the sections it references or is referenced by, its parent and its children; a
  schema entry's the forms naming its key. Each read of who names it takes at most cap datoms, so a
  var every form names (seon.contract/checked) costs cap, not its hundreds of callers."
  {:malli/schema [:=> [:cat :seon.host/db [:map-of :qualified-symbol [:set :int]] :int pos-int?] [:set :int]]}
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
  {:malli/schema [:=> [:cat :seon.host/db [:map-of :qualified-symbol [:set :int]] :int pos-int?] nat-int?]}
  [db names e cap]
  (let [n #(bounded-count cap (d/datoms db %1 %2 %3))
        k (fn [a] (n :eavt e a))]
    (min cap
         (cond (seq (d/datoms db :eavt e :form/id))
               (+ (k :form/refs) (k :form/quoted) (k :form/keywords)
                  (reduce + 0 (for [v (d/datoms db :avet :var/form e) :let [nm (:v (first (d/datoms db :eavt (:e v) :var/name)))]]
                                (+ (count (names nm)) (n :avet :form/refs nm) (n :avet :form/quoted nm)))))
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
  {:malli/schema [:=> [:cat :seon.host/db [:map-of :qualified-symbol [:set :int]] [:set :int] number?]
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
    (cond (:form/id x) (vec (for [v (:var/_form x) :let [kind (if (= :test (:var/kind v)) :test :var)] :when (kinds kind)] [kind (:var/name v)]))
          (:seon.doc/address x) (if (kinds :section) [[:section (:seon.doc/address x)]] [])
          (:seon.task/id x) (if (kinds :task) [[:task (:seon.task/id x)]] [])
          (:schema/key x) (if (kinds :schema) [[:schema (:schema/key x)]] [])
          :else [])))


(defn- kind-of
  "Var v's search kind: :test for a test, else :var."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-symbol] :keyword]}
  [db v] (if (= :test (:var/kind (d/entity db [:var/name v]))) :test :var))

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
  :relevance the chance it is worth reading (seon.context/relevance, over q's vector, words and the
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
        known (into {} (map (fn [h] [[(:kind h) (:name h)] (select-keys h [:line :at :z])])) toks)
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
                             (for [s seeds :when (symbol? s) c (map :var (callees db s)) :let [kd (kind-of db c)] :when (kinds kd)] [kd c])
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
        ;; its form, a section, a schema entry (seon.context/relevance; a test or a task has no chance yet)
        rkind #{:var :section :schema}
        form-of (fn [[kind n]] (case kind
                                 ;; a var's form: its :var/form, or the form whose :form/defines holds it
                                 :var (let [v (d/entity db [:var/name n])] (or (some-> v :var/form :db/id) (some-> v :form/_defines first :db/id)))
                                 (entity-of db [kind n])))
        ;; the hits search can show: the first k x related-near by the fused score, a bound whatever
        ;; the corpus (a common word's text hits grow with it); the rest keep their fused order after
        hits (into [] (comp (map key) (take n)) (sort-by (juxt (comp - val) (comp str key)) scores))
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
                                       (select-keys (known h) [:line :at]))))
                  shown)
      (seq left) (conj {:kind :more :left (count left) :counts (frequencies (map (comp first key) left))
                        :more (str "(my.agent/search " (pr-str q) " " (pr-str (assoc opts :k (+ k (count left)) :min 0.0)) ") returns them; a narrower :kinds or :ns finds fewer")}))))

(declare cited)

(defn current-ns
  "The namespace the REPL of agent record c (its ctx) is in: its :seon.agent/ns, which its eval's record writes when the REPL
  moved (seon.repl/record-tx; one key, so what reads it redraws on a move alone, never on an eval); before its first turn, where it starts,
  its task's :seon.task/ns (seon.agent/start-ns); nil for a task that names none. Where its next eval runs
  (docs/render.md#a-part-answers-data)."
  {:malli/schema [:=> [:cat :map] [:maybe :symbol]]}
  [c] (or (:seon.agent/ns c) (:seon.task/ns (:task c))))

(defn task-data
  "The task's data: task t's run sections as data, the same each turn of a run: the task (its
  plan as it accumulated along :needs, prerequisites first); its write set, each program var cites
  name with its source and its callers (doc/callers), the names that resolve to nothing said
  once; each proof to the form that runs it at the REPL, or, when it is not written, that it
  is not and what it should assert in the plan's words (whether it fails, and why, is the
  proofs part's: it changes each turn); the wiki sections and the tasks cites name (a task by its plan, seon.ref/task-view). cites: what the task cites, its cite entities and its needs'
  (seon.task/task-cites). What `my.task/data` answers at the agent's REPL, on demand; the page's task part is
  the pulled task's own renders (seon.render.ui/task, docs/render.md#a-part-answers-data)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.task/task [:sequential :map]] :map]}
  [db t cites]
  (let [{:keys [plan proofs]} (:seon.task/accumulated t)
        refs (program-vars db cites)
        sections (into [] (keep (fn [{a :seon.cite/address n :seon.cite/name tk :seon.cite/task d :seon.cite/depth}]
                                  (cond a (doc/view db a d) tk (ref/task-view db n d)))) cites)]
    (cond-> {:task (assoc (select-keys t [:seon.task/id :seon.task/kind :seon.task/instructions])
                          :seon.task/plan plan)
             ;; a proof is shown with the proofs, not again in the write set; the names that
             ;; resolve to nothing are one entry, their problem said once
             :write-set (let [rs (into [] (comp (remove (set proofs)) (map #(cited db %))) refs)
                              {gone true there false} (group-by (comp boolean :unresolved) rs)]
                          (cond-> (vec there)
                            gone (conj {:unresolved (mapv :unresolved gone) :problem (:unresolved ref/problem-messages)})))
             ;; (the turn sections' :proofs are each turn's statuses; these are how to run them)
             :to-prove (into (sorted-map-by #(compare (str %1) (str %2)))
                             (for [p proofs]
                               [p (if (doc/source-of db p)
                                    (list 'my.agent/prove #{p})
                                    "not written yet")]))}
      (seq sections) (assoc :sections sections))))

(defn cited
  "Var v as the task's page shows it: its source and its callers (doc/callers), or that it
  resolves to nothing."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-symbol] :map]}
  [db v]
  (let [r (doc/resolve-ref db v :code)]
    (if (:unresolved r) {:unresolved v :problem (:unresolved ref/problem-messages)} (let [secs (doc/citing-sections db v)]
                                                (cond-> (assoc r :callers (doc/callers db v)) (seq secs) (assoc :sections secs))))))

(defn- verdicts
  "Every test the branch db holds a verdict of (seon.proofs/result-tx: the verdict at its reach digest
  now), {test {:status :pass|:fail|:deferred :failures [assertion]}}: green is :pass, red and flaky
  :fail (a flaky test is no green), deferred left to root's gate. One scan of :seon.test/status's datoms."
  {:malli/schema [:=> [:cat :seon.host/db] [:map-of :qualified-symbol :map]]}
  [db]
  (into {} (for [{e :e st :v} (d/datoms db :aevt :seon.test/status)
                 :let [v (d/entity db e)]]
             [(:var/name v) (cond-> {:status (case st :green :pass :deferred :deferred :fail)}
                              (seq (:seon.test/failures v)) (assoc :failures (:seon.test/failures v)))])))

(defn task-of
  "The task agent record c holds (its :task, as ctx pulls it, docs/ctx.md#the-pattern), read for what its
  accumulated plan and proofs take (seon.task/pulled-accumulated): its id, kind, plan and proofs and its
  needs', and each proof's var as :proved holds it ({name var}: its verdict, failures and form), the
  task's and its needs'. Each read by its path, so a reader of it reads nothing else of the task."
  {:malli/schema [:=> [:cat :map] [:maybe [:map [:task :map] [:proved [:map-of :qualified-symbol :map]]]]]}
  [c]
  (when-let [t (:task c)]
    (let [own (fn [x] (into {} (for [k [:seon.task/id :seon.task/kind :seon.task/plan :seon.task/proofs] :let [v (get x k)] :when (some? v)] [k (ratom/realize v)])))
          needs (vals (:needs t))]
      {:task (assoc (own t) :needs (into {} (map-indexed (fn [i n] [i (own n)])) needs))
       :proved (into {} (for [ps (cons (:proved t) (map :proved needs)) v (vals ps) :let [n (:var/name v)] :when n]
                          ;; its verdict and failures, and its source (whether it is defined): its form's text
                          [n (cond-> (into {} (for [k [:var/name :seon.test/status :seon.test/failures :seon.test/ticks :seon.test/bytes] :let [x (get v k)] :when (some? x)] [k (ratom/realize x)]))
                               (get-in v [:form :form/src]) (assoc :form {:form/src (ratom/realize (get-in v [:form :form/src]))}))]))})))

(defn form-at
  "The form of src that opens at offset i, as written, up to its closing delimiter (strings and character literals read as such),
  or nil when it never closes. A slice of src: text the db holds, found by position."
  {:malli/schema [:=> [:cat [:maybe :string] [:maybe nat-int?]] [:maybe :string]]}
  [src i]
  (when (and src i)
    (let [n (count src)]
      (loop [j i depth 0 in-str? false]
        (when (< j n)
          (let [ch (.charAt ^String src (int j))]
            (cond
              in-str? (cond (= ch \\) (recur (+ j 2) depth true) (= ch \") (recur (inc j) depth false) :else (recur (inc j) depth true))
              (= ch \") (recur (inc j) depth true)
              (= ch \\) (recur (+ j 2) depth false)
              (#{\( \[ \{} ch) (recur (inc j) (inc depth) false)
              (#{\) \] \}} ch) (if (= 1 depth) (subs src i (inc j)) (recur (inc j) (dec depth) false))
              :else (recur (inc j) depth false))))))))

(defn test-shape
  "Where an unwritten proof p goes, as the agent needs it to write one: its test namespace's `:require` entries that the example
  uses (an alias it calls, `clojure.test`, the namespace under test), the helpers the example calls (the rest are `(dir ns)`), and
  one test of it to copy: the shortest that uses a helper or `my.test`, else the shortest. Nil when the branch has no such
  namespace yet (the agent makes it first). One index read, and each var of the namespace read once. See
  docs/render.md#how-the-work-stands."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-symbol] [:maybe :map]]}
  [db p]
  (when-let [n (d/entity db [:ns/name (symbol (namespace p))])]
    (let [vs (map #(d/entity db %) (d/q '[:find [?e ...] :in $ ?n :where [?e :var/ns ?n]] db (:db/id n)))
          {tests true others false} (group-by #(= :test (:var/kind %)) vs)
          names (mapv #(name (:var/name %)) others)
          srcs (keep #(get-in % [:var/form :form/src]) tests)
          calls? (fn [s h] (re-find (re-pattern (str "\\(" (java.util.regex.Pattern/quote h) "[\\s)]")) s))
          uses? (fn [s] (or (str/includes? s "my.test/") (some #(calls? s %) names)))
          src (first (sort-by count (or (seq (filter uses? srcs)) srcs)))
          ns-src (get-in n [:ns/form :form/src])
          under (str/replace (namespace p) #"-test$" "")
          req (when-let [i (some-> ns-src (str/index-of "(:require"))] (form-at ns-src i))
          entries (when req (re-seq #"\[[^\[\]]*(?:\[[^\[\]]*\][^\[\]]*)*\]" req))
          keep? (fn [e] (let [alias (second (re-find #":as\s+(\S+?)[\s\]]" e))]
                          (or (and alias src (str/includes? src (str alias "/"))) (str/includes? e "clojure.test") (str/starts-with? e (str "[" under " ")) (str/starts-with? e (str "[" under "]")))))
          requires (when (seq entries) (str "(:require " (str/join " " (filter keep? entries)) ")"))]
      (cond-> {:ns (symbol (namespace p))}
        requires (assoc :requires requires)
        (and src (seq others)) (assoc :helpers (vec (sort (map symbol (filter #(calls? src %) names)))))
        src (assoc :example src)))))

(defn status
  "How the agent of record rec (its ctx: its :db/id and its task) works on its task stands on its branch db, as data (task
  seon.context/the-context-is-the-agents-evals-and-seons, R2b-1): each proof by the verdict its var
  entity holds (`verdicts`; none: :unknown, or :undefined while the branch defines no such test),
  a red or unknown one with its source and the program functions it calls (`callees`), a red one
  with its failing assertions (`failure`); the done rule's reasons (seon.reconcile/done-explain)
  and each as what to do (`refused`); what the branch's work did since its merge base, from one
  diff (seon.merge/delta-changes of :seon.agent/base-commit against db): the functions without a
  contract, the problems it caused and the sections it made stale (seon.problems/caused-split),
  what its merge carries, and whether it can merge now when it is given main's commit; its
  unmerged entries; a test run that failed as a whole (:seon.agent/test-error). It reads facts
  about the work only, never the conversation (its turns, evals and messages), so a turn's record
  draws it again only when it changed the work; turns left and a cut reply are the run's
  (seon.render.ui/agent-state). Nothing is held: each is a fact of db. :reasons is the done rule's
  own answer, the turn's check (seon.turn/work!), which the model and the page read as :refused. A design task's plan before its run (:no-design) is its run's, the run its newest model call names, so it waits for the first turn. opts :index, a function answering the reconciler's index of db (seon.reconcile/index; else
  one built cold), called only when a test outside the task's proofs is red (whether it is a
  regression or a stray red is main's claims: measured cold on a demo branch, 14.8M ticks and
  70 MB), :main, main's commit (the merge view), and :deferred, the task's proofs a run leaves to root's gate (process
  tests: none while every proof has a verdict). See docs/render.md#how-the-work-stands."
  {:malli/schema [:=> [:cat :seon.host/db :map :map] :map]}
  [db rec {:keys [index deferred main]}]
  (let [a (:db/id rec)
        ag (d/entity db a)
        ;; the task and its proofs as the record pulls them (task-of): the accumulated plan and proofs over its needs
        {t :task proved :proved} (task-of rec)
        tid (:seon.task/id t)
        {:keys [proofs plan]} (when tid (task/pulled-accumulated t))
        kind (:seon.task/kind t :work)
        ;; a test run that failed as a whole: present on its process only while it is so (the
        ;; process's other facts, written on every eval, are not read)
        test-error (:seon.agent/test-error ag)
        ;; while it is so no verdict counts: what the branch holds is from before the change that the run
        ;; failed to judge (every proof :not-run, as the run's own results were none)
        vs (if test-error {} (verdicts db))
        ;; a deferred proof is neither red nor unknown (seon.reconcile/done-explain)
        deferred (into (set deferred) (keep (fn [[t {st :status}]] (when (= :deferred st) t))) vs)
        results (into {} (keep (fn [[t {st :status}]] (when (not= :deferred st) [t st]))) vs)
        base (some->> (:seon.agent/base-commit ag) (d/commit-as-db (:store db)))
        moved (when base (pm/content-datoms base db #{}))
        ch (if base (pm/delta-changes base db #{} moved) {})
        ;; what caused-split reads differs from the content (sections, names, plans, catches): its own
        ;; attributes' diff, one descent each (seon.problems/caused-datoms), never a scan of the wiki
        split (when base (problems/caused-split base db ch (problems/caused-datoms base db)))
        gaps (vec (sort-by str (pm/uncontracted ch)))
        unmerged (pm/unmerged db)
        ;; a design task's plan before the work: the plan its branch began with, its merge base's (the judge reads the same, seon.reconcile/judge)
        plan-before (when (and base (= :design kind)) (some-> (task/task base tid) :seon.task/accumulated :plan))
        proof? (set proofs)
        ;; main's claims only when a test outside the proofs is red: the agent's index, else one built cold
        claims (when (some (fn [[t st]] (and (= :fail st) (not (proof? t)))) results)
                 (cond index (index)
                       ;; in an agent's process its index, advanced by what differs; else one built cold
                       (= :agent (:mode @agent/state)) (agent/reconcile-index db)
                       :else (r/index db)))
        ;; a test red outside the proofs that main was red on too, at the branch's merge base: main's, not a stray
        ;; red of the work (one scan of the base's verdicts, only while such a red exists)
        main-red (when (and claims base)
                   (into #{} (keep (fn [[t {st :status}]] (when (and (= :fail st) (= :fail (get results t))) t))) (verdicts base)))
        input (cond-> (if claims
                        (r/done-input claims tid results (set gaps) (mapv #(sedn/encode (:key %)) unmerged) nil plan-before (set deferred))
                        (cond-> {:proofs (set proofs) :kind kind :results results :main-green #{} :allowed-red #{}
                                 :uncontracted (set gaps) :unmerged (mapv #(sedn/encode (:key %)) unmerged) :deferred (set deferred)}
                          (= :design kind) (assoc :plan plan)
                          (and (= :design kind) plan-before) (assoc :plan-before plan-before)))
                (seq (:caused split)) (assoc :caused (set (:caused split)))
                (seq main-red) (assoc :main-red main-red))
        reasons (r/done-explain input)
        reds (vec (for [[p {:keys [failures]}] vs f failures] (assoc f :test p)))
        blockers (refused reasons reds #(doc/source-of db %) #(callees db %)
                          #(or (nil? base) (not= (doc/source-of db %) (doc/source-of base %))))]
    (cond-> {:reasons reasons
             ;; each proof as :proved holds its var: its verdict (green :pass, red and flaky :fail, as `verdicts`), its source
             :proofs (vec (for [p (sort-by str proofs)
                                :let [v (get proved p)
                                      src (get-in v [:form :form/src])
                                      verdict (when-not test-error (case (:seon.test/status v) nil nil :green :pass :deferred :deferred :fail))
                                      st (cond (and verdict (not= :deferred verdict)) verdict
                                               (or (deferred p) (= :deferred verdict)) :deferred
                                               (nil? src) :undefined (some? test-error) :not-run :else :unknown)
                                      fs (when-not test-error (:seon.test/failures v))
                                      calls (when (and src (not= :pass st)) (callees db p))
                                      shape (when (= :undefined st) (test-shape db p))]]
                            (cond-> {:test p :status st}
                              (or (:seon.test/ticks v) (:seon.test/bytes v)) (assoc :cost (cond-> {} (:seon.test/ticks v) (assoc :ticks (:seon.test/ticks v)) (:seon.test/bytes v) (assoc :bytes (:seon.test/bytes v))))
                              (and src (not= :pass st)) (assoc :source (print/code src))
                              shape (assoc :shape shape)
                              (seq fs) (assoc :failures (failures fs))
                              (seq calls) (assoc :calls calls))))}
      (seq blockers) (assoc :refused blockers)
      test-error (assoc :test-run {:failed [(:seon.error/message test-error)]})
      (seq unmerged) (assoc :unmerged (mapv #(select-keys % [:key :kind :base :ours :theirs]) unmerged))
      (seq gaps) (assoc :without-a-contract gaps)
      (seq (:caused split)) (assoc :caused (:caused split))
      (seq (:attention split)) (assoc :attention (:attention split))
      (seq (pm/divergence ch)) (assoc :its-merge-carries (pm/divergence ch))
      (and main base) (assoc :merge (pm/readiness base db (d/commit-as-db (:store db) main) moved)))))

(def ^:private eval-keys
  "The attributes of an eval a reader of the conversation reads (:seon.db/eval-fact less :seon.eval/agent and :seon.eval/opened). See docs/render.md#a-part-answers-data."
  [:seon.eval/id :seon.eval/by :seon.eval/code :seon.eval/intent :seon.eval/call-id
   :seon.eval/schema :seon.eval/basis :seon.eval/fns :seon.eval/turn :seon.eval/response])

(defn basis-value
  "The value eval e names by its basis (:seon.eval/basis, @ctx's: the record of e's agent on branch db as of t,
  seon.ctx/build), drawn by the schema e kept: what the eval answered, rebuilt from what exists, since no copy of the
  session is stored at each @ctx. A value as of t never changes: its reads are not the reader's (no later transaction
  touches them), so it is built when asked and never because the branch moved. See docs/render.md#a-render-is-a-value-printed-once."
  {:malli/schema [:=> [:cat :seon.host/db :map] :map]}
  [db e]
  (let [{:keys [t branch]} (:seon.eval/basis e)
        ;; the agent of the eval's record, else (an eval not yet recorded, whose text is made first) the one this process is
        a (or (d/q '[:find ?a . :in $ ?id :where [?e :seon.eval/id ?id] [?e :seon.eval/agent ?a]] db (:seon.eval/id e))
              (pdb/eid db :seon.agent/key (:key @agent/state)))]
    (with-meta (assoc (:tree (binding [datahike.index.interface/*reads* nil] (ctx/build (d/as-of db t) a branch))) :t t)
      {:seon.render/schema (:seon.eval/schema e)})))

(defn eval-messages
  "An eval's part of the conversation as
  provider-neutral messages: a model's eval (it names the provider's call id) is its
  assistant message, the call alone, naming its reply (:reply, the turn it answered: the reply's
  text and reasoning are its model call's `turn-messages`, and the provider's format folds a reply's messages into one),
  then its tool message, the response printed now (seon.print/record-text, a function of the
  eval alone, and of the db for an eval that names its basis: opts :db, `basis-value`, the same bytes cold as live);
  an eval with intent and no call id was asked, not called (docs/turn.md#an-eval-can-be-inserted): its call is made here from its
  own id, intent and code, standing alone (no reply to name, so a model's message is never edited), and no reader asks who asked;
  anyone else's (the user's) is one user message
  {:by :code :response}, the response spliced as printed. See docs/render.md#the-models-context-a-cached-prefix-then-the-turn."
  {:malli/schema [:=> [:cat [:maybe :map] :map] [:vector :seon.model/message]]}
  [opts {:seon.eval/keys [id by code intent call-id basis turn] :as e}]
  (let [text (print/record-text (cond-> e basis (assoc-in [:seon.eval/response :value] (basis-value (:db opts) e))) render/model)
        cid (or call-id (when-not (str/blank? intent) (str "eval-" id)))]
    (if cid
      [(cond-> {:role "assistant" :content ""
                :tool-calls [(cond-> {:id cid :code code} intent (assoc :intent intent))]}
         (and call-id (pos? (or turn 0))) (assoc :reply turn))
       {:role "tool" :tool-call-id cid :content text}]
      [{:role "user" :content (str "{:by " (pr-str by) " :code " (pr-str code) " :response " text "}")}])))

(defn message-messages
  "A message's part of the conversation: one
  user message {:from :body}."
  {:malli/schema [:=> [:cat :any :map] [:vector :seon.model/message]]}
  [_ {:seon.message/keys [from body]}]
  [{:role "user" :content (print/text {:from from :body body} (:context print/budgets))}])

(defn turn-messages
  "A model call's reply as the conversation keeps it: one assistant message, the reply's text and the reasoning as
  returned, naming the reply (:reply, the call's number n) so the provider's format folds the messages of its evals into
  it; none for a reply with neither text nor reasoning (its evals' messages stand alone). See
  docs/render.md#the-models-context-a-cached-prefix-then-the-turn."
  {:malli/schema [:=> [:cat :any :map] [:vector :seon.model/message]]}
  [_ {:seon.model-call/keys [reply reasoning n]}]
  (if (and (str/blank? reply) (nil? reasoning))
    []
    [(cond-> {:role "assistant" :content (or reply "")} reasoning (assoc :reasoning reasoning) n (assoc :reply n))]))

(defn- ref-of
  "The ref a conversation's record is sent as (:seon.model-call/sent): an eval's, a message's or a reply's (its model call's) id."
  {:malli/schema [:=> [:cat :map] [:tuple :keyword :string]]}
  [v] (cond (:seon.eval/id v) [:eval (:seon.eval/id v)] (:seon.message/id v) [:message (:seon.message/id v)] :else [:reply (:seon.model-call/id v)]))

(defn- record-messages
  "Record v of schema k as the conversation sends it: the model render k declares, a function of that record alone (and of db, for an eval that names its basis). See
  docs/render.md#the-models-context-a-cached-prefix-then-the-turn."
  {:malli/schema [:=> [:cat :qualified-keyword :map [:maybe :seon.host/db]] [:vector :seon.model/message]]}
  [k v db] (:value (render/model (vary-meta v assoc :seon.render/schema k) {:db db})))

(defn printed-run
  "The session's records new since cache (items, [ref v k position] in order, ref [:eval id], [:message
  id], [:reply id], [:parts id], [:example key] or [:session id]) printed as the conversation sends them, each once (cache, what the
  session's earlier requests printed, and db, the branch an eval that names its basis is rebuilt on: content-keyed reuse by the record's kind and id, AGENTS section 5): an
  eval, a message or a prose reply by its own render, a function of that record alone; a parts, example or
  session item (a delay of {:ref :whole}, `request`) drawn only when not printed before. A record's text depends
  on nothing before it, so a request prints only its new records and every older message is the same
  bytes (the provider's cache). An item already printed is skipped. Answers the cache: {:ids :parts
  {id part}}, each part {:ref :whole}. See
  docs/render.md#the-models-context-a-cached-prefix-then-the-turn."
  {:malli/schema [:=> [:cat [:sequential :any] [:maybe :map] [:maybe :seon.host/db]] :map]}
  [items cache db]
  (reduce (fn [c [[kind id] v k]]
            (let [part (fn [ref whole] {:ref ref :whole whole})
                  pid (str (name kind) "/" id)
                  c (update c :ids conj pid)]
              (cond
                (get-in c [:parts pid]) (update c :ids pop)
                (#{:parts :example :session} kind) (assoc-in c [:parts pid] @v)
                :else (assoc-in c [:parts pid] (part [kind id] (record-messages k v db))))))
          (or cache {:ids [] :parts {}}) items))

(defn loop-eval?
  "Is eval e (:seon.eval/by, no call id, no turn) the agent's own turn loop (seon.turn/work!, asked of the agent named id,
  answering no model's call and taking no turn: seon.repl/record!)? It holds the agent's turns; no reader of the conversation reads it as work:
  neither the model (`records`) nor the page (seon.render.ui/conversation-list). Reads e by its paths, never realized
  whole: the layout reads no eval's opened flag."
  {:malli/schema [:=> [:cat [:maybe :keyword] :any] :boolean]}
  [id e]
  (boolean (and id (= (:seon.eval/by e) (name id)) (nil? (:seon.eval/turn e)) (not (:seon.eval/call-id e)))))

(defn records
  "The conversation of ctx value c (an agent's @ctx, or one seon.ctx/build drew) on db, as [position value schema-key]
  in order: its evals (not a context read's, nor its own loop's: `loop-eval?`), the messages it took and its model calls'
  replies (a call with evals left in c, or an uncut one with text; one with nothing to send is none). The position is [turn rank n], docs/turn.md#the-turn-is-a-number. With since, a turn
  number: the evals and replies of that turn and later only (read newest first from ctx, so a request costs what is new);
  every message. Reading them from ctx is the owner's ruling (docs/decisions.md): what the pattern leaves out, the model does not read."
  {:malli/schema [:function [:=> [:cat :map :seon.host/db] [:vector [:tuple [:vector :int] :map :qualified-keyword]]]
                  [:=> [:cat :map :seon.host/db [:maybe :int]] [:vector [:tuple [:vector :int] :map :qualified-keyword]]]]}
  ([c db] (records c db nil))
  ([c db since]
   (let [id (:seon.agent/id c)
         since (or since 0)
         num (fn [x] (or x 0))
         ;; ctx's members are newest first: those whose turn (a) is not below since. The agent's own loop eval takes no
         ;; turn and is recorded last, so it is left out before the turns are read
         fresh (fn [k a skip?] (take-while (fn [[_ v]] (>= (num (a v)) since)) (remove (fn [[_ v]] (skip? v)) (get c k))))
         evals (vec (for [[_ v] (fresh :evals :seon.eval/turn #(and (not (:seon.eval/call-id %)) (loop-eval? id %)))
                          ;; a model's eval (nearly all of them) answers a call: no contracted call per record to say so
                          :when (not= "context" (:seon.eval/by v))]
                      v))
         ;; the turns with an eval left in c
         called (into #{} (keep #(when (:seon.eval/call-id %) (:seon.eval/turn %))) evals)
         ;; an eval's issue number: its id's trailing digits (e12 is 12; an example's example/x/e12 too), so e9 comes before e10
         issued (map #(or (some-> (re-find #"\d+$" (:seon.eval/id %)) parse-long) 0) evals)]
     (->> (concat (map (fn [v n] [[(num (:seon.eval/turn v)) 2 n] (select-keys v eval-keys) :seon.db/eval-fact]) evals issued)
                  (for [[e v] (get c :messages)]
                    [[(num (:seon.message/turn v)) 0 (or (:tx (first (d/datoms db :eavt e :seon.message/agent))) 0)]
                     (select-keys v [:seon.message/id :seon.message/from :seon.message/body :seon.message/turn]) :seon.message/fact])
                  (for [[_ v] (fresh :calls :seon.model-call/n (constantly false))
                        ;; a reply cut at the token limit is no message (seon.turn/act!), unless its calls were kept
                        :when (and (= :model (:seon.model-call/origin v))
                                   (or (contains? called (:seon.model-call/n v))
                                       (and (not (:seon.model-call/truncated v)) (not (str/blank? (:seon.model-call/reply v)))))
                                   (or (some? (:seon.model-call/reasoning v)) (not (str/blank? (:seon.model-call/reply v)))))]
                    [[(num (:seon.model-call/n v)) 1 0] (select-keys v [:seon.model-call/id :seon.model-call/n :seon.model-call/origin :seon.model-call/reply :seon.model-call/reasoning :seon.model-call/forms])
                     :seon.model-call/fact]))
          (sort-by first)
          vec))))

(defn- agent-ctx
  "Agent a's ctx value as db holds it, drawn cold (seon.ctx/build): a read of a session with no live @ctx. See
  docs/ctx.md#the-pattern."
  {:malli/schema [:=> [:cat :seon.host/db :int] :map]}
  [db a] (:tree (ctx/build db a (:seon.agent/branch (d/pull db [:seon.agent/branch] a)))))

(defn examples-of
  "The keys of the example sessions agent a's task names (:seon.task/examples, agents keyed
  \"example/<id>\") on db, in key order; none when its task names none. See
  docs/tasks.md#a-task-names-example-sessions."
  {:malli/schema [:=> [:cat :seon.host/db :int] [:vector :string]]}
  [db a]
  (vec (sort (d/q '[:find [?k ...] :in $ ?a :where [?a :seon.agent/task ?t] [?t :seon.task/examples ?x] [?x :seon.agent/key ?k]] db a))))

(defn- example-t
  "The newest transaction of example session k on db: its agent's and its records'. The session never
  changes after it, so a replay draws it as of this t. See
  docs/render.md#the-models-context-a-cached-prefix-then-the-turn."
  {:malli/schema [:=> [:cat :seon.host/db :string] :int]}
  [db k]
  (let [a (pdb/eid db :seon.agent/key k)]
    (reduce max 0 (keep #(d/q % db a) '[[:find (max ?tx) . :in $ ?a :where [?a :seon.agent/key _ ?tx]]
                                         [:find (max ?tx) . :in $ ?a :where [_ :seon.eval/agent ?a ?tx]]
                                         [:find (max ?tx) . :in $ ?a :where [_ :seon.message/agent ?a ?tx]]
                                         [:find (max ?tx) . :in $ ?a :where [?m :seon.model-call/agent ?a] [?m :seon.model-call/reply _ ?tx]]]))))

(defn- session-open
  "The user message that opens the agent's own session, id, in its request, after its task's example
  sessions. See docs/render.md#the-models-context-a-cached-prefix-then-the-turn."
  {:malli/schema [:=> [:cat :string] [:vector :seon.model/message]]}
  [id] [{:role "user" :content (str "<session id=\"" id "\" from=\"seon\">\nYour own session: the work you are asked to do.")}])

(defn example-messages
  "Example session k on db as of t as the model reads it: a user message `<session id=\"k\" kind=\"example\" from=\"seon\">` (Seon's framing, not a person's: a
  user message is the one turn every provider renders in place) saying it is finished work of another task, and the request it answered (its agent's description), the session's records printed by `printed-run`
  exactly as `request` prints the agent's own, then a user message `</session>`. See
  docs/render.md#the-models-context-a-cached-prefix-then-the-turn."
  {:malli/schema [:=> [:cat :seon.host/db :string :int] [:vector :seon.model/message]]}
  [db k t]
  (let [db (d/as-of db t)
        {a :db/id desc :seon.agent/description} (d/pull db [:db/id :seon.agent/description] [:seon.agent/key k])
        c (printed-run (map (fn [[tx v kk]] [(ref-of v) v kk tx]) (records (agent-ctx db a) db)) nil db)]
    (-> [{:role "user" :content (str "<session id=\"" k "\" kind=\"example\" from=\"seon\">\n"
                                     "A finished session of another task, shown for how its work was done; not yours. Its request:\n" desc)}]
        (into (mapcat #(:whole (get-in c [:parts %]))) (:ids c))
        (conj {:role "user" :content "</session>"}))))

(def ^:private session-kinds
  "The kinds of record `records` answers, each with the attributes that name it and its agent. See
  docs/tasks.md#a-task-names-example-sessions."
  {:seon.db/eval-fact [:seon.eval/id :seon.eval/agent]
   :seon.message/fact [:seon.message/id :seon.message/agent]
   :seon.model-call/fact [:seon.model-call/id :seon.model-call/agent]})

(defn- example-record
  "An eval m as example ek keeps it (pre: ek's prefix for ids): its call id and
  result handle prefixed with pre. See docs/tasks.md#a-task-names-example-sessions."
  {:malli/schema [:=> [:cat :string :string :map] :map]}
  [ek pre m]
  (let [h (get-in m [:seon.eval/response :handle])
        h' (when h (symbol (namespace h) (str pre (name h))))]
    (cond-> m
      (:seon.eval/call-id m) (update :seon.eval/call-id #(str pre %))
      h (assoc-in [:seon.eval/response :handle] h'))))

(defn- example-rekey
  "Record m of one of `session-kinds` (its id and agent attributes id-k and agent-k) as example ek keeps it: its db
  attributes dropped, its id under ek (a model call's is `<ek>/<n>`), its agent ek's, an eval as `example-record` has it. See
  docs/tasks.md#a-task-names-example-sessions."
  {:malli/schema [:=> [:cat :string :string :map [:tuple :qualified-keyword :qualified-keyword]] :map]}
  [ek pre m [id-k agent-k]]
  (-> (apply dissoc m (filter #(= "db" (namespace %)) (keys m)))
      ;; a model call is `<agent key>/<n>` (docs/turn.md#the-turn-is-a-number), the example's too: the page finds it by that
      (as-> m (if-let [n (:seon.model-call/n m)] (assoc m id-k (str ek "/" n)) (update m id-k #(str ek "/" %))))
      (assoc agent-k [:seon.agent/key ek])
      (cond-> (:seon.eval/code m) (->> (example-record ek pre)))))

(defn- session-request
  "The request agent a answered, for an example: its task's plan, else its own description; nil when it has neither
  (a blank one is none). See docs/tasks.md#a-task-names-example-sessions."
  {:malli/schema [:=> [:cat :seon.host/db :int] [:maybe :string]]}
  [db a]
  (->> [(:seon.task/plan (:seon.agent/task (d/pull db [{:seon.agent/task [:seon.task/plan]}] a)))
        (:seon.agent/description (d/pull db [:seon.agent/description] a))]
       (remove str/blank?) first))

(defn session-tx
  "Agent key's session on db as example id: the transactions that recreate it as an example, a vector of
  tx-data, one per record in the order `records` answers them (each keeps its turn number, so `records` reads
  the copy in that order), or the `:seon.context/session-refused` error when no agent has key, it has no request to
  show (its task has no plan and it has no description) or a record is of a kind an example does not hold.
  The first transaction writes the example agent, an ended agent like any (key \"example/<id>\", id
  :example-<id>, its description the request, `session-request`, its turn the last its records carry); the model's evals
  are kept, an eval the harness made in the agent is not; each other holds one record,
  re-keyed under the example's key (`example-rekey`: ids, call ids and result handles,
  so none meets a live agent's, nor another example's). Pure data: written with pr-str, read back,
  `my.root/load-example!` transacts it. See docs/tasks.md#a-task-names-example-sessions."
  {:malli/schema [:=> [:cat :seon.host/db :string :string]
                  [:or [:vector [:vector :map]] :seon.context/session-refused]]}
  [db key id]
  (let [a (pdb/eid db :seon.agent/key key)
        refuse #(contract/error :seon.context/session-refused #'seon.context/session-tx % {:key key})
        ek (str "example/" id)
        request (when a (session-request db a))
        ;; the model's own evals (a call id) and its messages and replies: not an eval the harness made in the agent
        ;; an eval of @ctx keeps its code, and its value as the marker of what it was: its basis is a commit of the original branch, which a copy on another cannot rebuild
        cannot (fn [[pos v k]] (if (and (= :seon.db/eval-fact k) (:seon.eval/basis v))
                                 [pos (-> v (dissoc :seon.eval/basis :seon.eval/schema)
                                          (assoc-in [:seon.eval/response :value] {:seon.value/gone {:type "the agent's record at a basis a copy cannot rebuild"}}))
                                  k]
                                 [pos v k]))
        rs (when a (map cannot (remove (fn [[_ v k]] (and (= :seon.db/eval-fact k) (not (:seon.eval/call-id v)))) (records (agent-ctx db a) db))))
        unknown (first (remove #(session-kinds (nth % 2)) rs))
        ]
    (cond (nil? a) (refuse (str "no agent is keyed \"" key "\""))
          (nil? request) (refuse (str "agent \"" key "\" has no request to show: its task has no plan and it has no description"))
          unknown (refuse (str "agent \"" key "\" holds a record of kind " (nth unknown 2) ", which an example does not"))
          ;; its turn is the last its records carry (docs/turn.md#the-turn-is-a-number): the page draws a component for each
          :else (into [[{:seon.agent/key ek :seon.agent/id (keyword (str "example-" id)) :seon.agent/status :ended :seon.agent/description request
                         :seon.agent/turn (reduce max 0 (map (fn [[pos]] (first pos)) rs))}]]
                      (for [[_ v k] rs]
                        [(example-rekey ek (str (str/replace ek "/" "-") "-") v (session-kinds k))])))))

(def page-lists "The lists of an agent's page its model reads: its task's (#p-task)." ["#p-task"])

(defn drawn-parts
  "The parts of an agent's page its model reads, drawn cold from its record c on db (its branch as of a
  commit): its task's list (the layout :seon.render/render-ctx declares, drawn once by the walk,
  seon.render/draw, which draws that list alone: the page's other lists are no part of the model's), each component's hiccup exactly as its render returned it, by its element id,
  in order: the same hiccup the person's page shows (seon.repl/page-hiccup has it from the live walk,
  at the cost of what changed). A read of history (a replay, a request with no live walk), never a turn's work. See docs/render.md#the-page-is-sent-as-what-changed."
  {:malli/schema [:=> [:cat :seon.host/db [:map [:tree :map]]] :map]}
  [db c]
  (let [w (render/draw (:tree c) ctx/changes db (render/declared-var :seon.render/render-ctx) page-lists)]
    (try (render/placed-hiccup w page-lists)
         (finally (render/dispose-walk! w)))))

(defn- drawn-at
  "Agent a's parts as of commit c of db's branch (`drawn-parts`); db itself when c is its commit (a
  judge's speculative db has no commit of its own)."
  {:malli/schema [:=> [:cat :seon.host/db :int :uuid] :map]}
  [db a c]
  (let [db1 (if (= c (d/commit-id db)) db (d/commit-as-db db c))]
    (drawn-parts db1 (ctx/build db1 a (:seon.agent/branch (d/entity db a))))))

(defn- parts-message
  "The one user message of the parts a request sent, each printed as its render (seon.render/part-text), a blank line
  between: those of ids that are in live, whole (a part that moved is sent again whole; one that left is not named: it
  was said once); none when ids is empty or none of them is live."
  {:malli/schema [:=> [:cat :map [:sequential :string]] [:vector :seon.model/message]]}
  [live ids]
  (let [texts (into [] (keep #(some-> (get live %) render/part-text)) ids)]
    (if (empty? texts) [] [{:role "user" :content (str/join "\n\n" texts)}])))

(defn- calls-of
  "The model calls of agent a of origin o that sent, [id n call-entity] oldest first (n: the turn a model's call answers,
  a read's own count: docs/turn.md#the-turn-is-a-number)."
  {:malli/schema [:=> [:cat :seon.host/db :int :keyword] [:vector :any]]}
  [db a o]
  (->> (d/q '[:find ?id ?n ?m :in $ ?a ?o :where [?m :seon.model-call/agent ?a] [?m :seon.model-call/origin ?o] [?m :seon.model-call/n ?n]
              [?m :seon.model-call/id ?id] [?m :seon.model-call/sent]] db a o)
       (sort-by second) vec))

(defn- sent-of
  "What model call entity m sent (:seon.model-call/sent)."
  {:malli/schema [:=> [:cat :seon.host/db :int] [:vector :any]]}
  [db m] (:seon.model-call/sent (d/pull db [:seon.model-call/sent] m)))

(defn- trimmed?
  "Whether a conversation record the refs sent names is gone from ctx value c (the agent retracted it with swap! on @ctx, or
  its pattern no longer reaches it): the request after is drawn whole."
  {:malli/schema [:=> [:cat :map [:sequential :any]] :boolean]}
  [c refs]
  (let [attrs {:eval [:evals :seon.eval/id] :message [:messages :seon.message/id] :reply [:calls :seon.model-call/id]}]
    (boolean
      (some (fn [[kind ids]]
              (let [[k idk] (attrs kind) have (into #{} (map (fn [[_ v]] (get v idk))) (get c k))]
                (not-every? have ids)))
            (update-vals (group-by first (filter (comp attrs first) refs)) #(map second %))))))

(declare parts-of)

(defn- ref-id
  "The id printed-run keys a request's ref by: its kind and a record's id or a parts ref's call, so an eval, a message, a reply, a parts message, an example session and the opening of a session never meet by an id that happens to be alike. See
  docs/render.md#the-models-context-a-cached-prefix-then-the-turn."
  {:malli/schema [:=> [:cat [:sequential :any]] :string]}
  [ref] (str (name (first ref)) "/" (if (= :parts (first ref)) (nth ref 5) (second ref))))

(defn- cache-of
  "The print cache (printed-run's answer) of the refs a model call sent, each by its own render
  (parts-of): what a process holding no print of its run (a restart, a new run) starts from."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :any]] :map]}
  [db refs]
  {:ids (mapv ref-id refs)
   :parts (into {} (map (fn [[ref ms]] [(ref-id ref) {:ref ref :whole ms}])) (parts-of db refs))})

(defn request
  "The agent's request, a render of its record: agent a (its entity map, :db/id) for the turn
  basis b on db (opts :db :basis :help, and :help-ref, the record the system prompt renders): the
  system prompt (system-text), then the session in the order of its turn numbers (`records`: those of turns before the request's own, and the messages
  it took, printed once by printed-run,
  opts :printed the run's earlier requests' prints, answered again as :printed; all of it, as a REPL
  keeps its scrollback): each eval (eval-messages), message (message-messages) and prose reply
  (turn-messages), and the page's parts as one user message each request holds (`parts-message`):
  every part whole at the session's first request (before the records), after it the parts whose
  content differs from what the previous model call recorded of them (:hashes), each whole, at the turn's place in the
  records; none when none changed. So the request is the previous
  request's refs (read back from its model call when this process holds no print of them) and what
  was added. opts :parts, {id hiccup}, the agent's live walk's (seon.repl/page-hiccup; else drawn cold
  at db's commit), and :ctx, its @ctx at db (else drawn cold). A record the previous request sent that is gone (the agent trimmed its context
  with swap! on @ctx) draws the request whole: the system prompt, every part whole, the records that
  remain. Origin :outside (a read): the system prompt and the parts, whole at its first read, after it
  what changed since the read before. Answers {:messages :sent :printed}: provider-neutral messages
  (seon.model/wire-messages makes the wire shape) and the records they render, in order
  (:seon.model-call/sent): [:section eid t]; [:example key t], an example session the agent's task names
  (:seon.task/examples, `examples-of`), sent whole right after the system message by `example-messages` as of
  its newest record's t; [:session id], the opening of the agent's own session after its examples (both only
  in a model's first request of a task that names examples); [:eval id]; [:message id]; [:reply id]; [:parts a c whole?
  ids call], the parts the message holds (drawn cold at commit c on replay), and last [:hashes {id
  hash}], the content hash of every part the model has now, which the next request compares; :parts, each
  record's ref beside the messages it made, in order (what the debug page labels its messages by). Called by
  the turn. See docs/render.md#the-page-is-sent-as-what-changed, docs/tasks.md#a-task-names-example-sessions and the task
  seon.context/the-context-is-the-agents-evals-and-seons."
  {:malli/schema [:=> [:cat :map :map] [:map [:messages [:vector :seon.model/message]] [:sent [:vector :any]] [:printed :map]
                                              [:parts [:vector [:tuple :any [:vector :seon.model/message]]]]]]}
  [a {:keys [db basis help help-ref printed parts ctx]}]
  (let [e (:db/id a)
        ;; the agent's @ctx, as its turn hands it; a read of history draws one
        c (delay (or ctx (agent-ctx db e)))
        model? (= :model (:origin basis))
        n (:turn basis)
        id (str (or (:agent basis) (:seon.agent/key a)) (if model? "/" "/read-") n)
        ;; this turn's own number: the request is the turn's as it was, whatever came after it on db (a read of
        ;; history on a later db answered the newest turn's request), so the call before it is the last one
        ;; below its number and the records are those of earlier turns and the messages it took (turn n's own evals
        ;; and reply came of this request)
        [_ prev-n prev-m] (last (filter #(and (not= id (first %)) (< (second %) n)) (calls-of db e (:origin basis))))
        prev (when prev-m (sent-of db prev-m))
        cut? (boolean (and prev (trimmed? @c prev)))
        old (when-not cut? (some #(when (= :hashes (first %)) (second %)) prev))
        whole? (nil? old)
        live (or parts (drawn-parts db {:tree @c}))
        ;; a part's hash is its printed form's: its hiccup holds the Vars of its components, which no content hash reads
        hs (into {} (map (fn [[k v]] [k (hasch/uuid (pr-str v))])) live)
        ids (into (vec (for [k (keys live) :when (or whole? (not= (get old k) (get hs k)))] k))
                  (when old (sort (remove #(contains? live %) (keys old)))))
        ref [:parts e (d/commit-id db) whole? ids id]
        ;; a model's first request (whole) holds its task's example sessions right after the system message, then
        ;; the opening of its own session; a later one has them in the refs it continues
        ;; read from the task's datoms, so a task naming none costs no contracted call (examples-of is asked only when it names some)
        ex-items (when (and model? whole? (seq (d/datoms db :eavt (:db/id (:seon.agent/task (d/entity db e))) :seon.task/examples)))
                   (when-let [ks (seq (examples-of db e))]
                     (conj (mapv (fn [k] (let [t (example-t db k)] [[:example k] (delay {:ref [:example k t] :whole (example-messages db k t)}) nil [0 0 0 0]])) ks)
                           [[:session (:session basis (:agent basis))] (delay {:ref [:session (:session basis (:agent basis))] :whole (session-open (:session basis (:agent basis)))}) nil [1 0 0 0]])))
        ;; every item beside its position, [group turn rank n]: the examples, the session's opening, the page whole, the
        ;; records by their turn numbers, then the page's change, last
        current [[:parts id] (delay {:ref ref :whole (parts-message live ids)}) nil (if whole? [2 0 0 0] [4 0 0 0])]
        ;; what the previous request sent, its refs in order: this process's print of them, else read back from the records
        before (when (and model? (not whole?)) (into [] (remove #(#{:section :hashes} (first %))) prev))
        ;; a print whose first refs are those (a later request's of the same session: the debug page's latest, drawn
        ;; again as the branch moves) is reused as far as they go, each record's print a function of its ref
        base (when before
               (let [k (count before) ids (:ids printed)]
                 (if (and ids (<= k (count ids)) (= before (mapv #(:ref (get-in printed [:parts %])) (subvec ids 0 k))))
                   (let [ids (subvec ids 0 k)] {:ids ids :parts (select-keys (:parts printed) ids)})
                   (cache-of db before))))
        rs (if model? (into [] (comp (filter (fn [[pos]] (neg? (compare (subvec pos 0 2) [n 1])))) (map (fn [[pos v k]] [(ref-of v) v k (into [3] pos)])))
                            (records @c db (when before prev-n))) [])
        items (sort-by #(nth % 3) (concat rs ex-items [current]))
        cache (printed-run items base db)
        ps (mapv #(get-in cache [:parts %]) (:ids cache))]
    {:messages (into [{:role "system" :content help}] (mapcat :whole) ps)
     ;; the parts it drew, and those it drew of the request before (the same for every later request until the next call)
     :printed cache
     :sent (-> [help-ref] (into (map :ref) ps) (conj [:hashes hs]))
     :parts (into [[help-ref [{:role "system" :content help}]]] (map (juxt :ref :whole)) ps)}))

;; ---- a turn's basis: what its context was built from, and the text made from it again ----

(declare render)

(defn parts-of
  "Refs (as :seon.model-call/sent names records) each beside its messages, each record by its own
  render: the system prompt's section as of its t (system-text), an eval by eval-messages, a
  message or a prose reply by its render, the parts of a turn ([:parts a c whole? ids], drawn cold from
  the branch at commit c, the ids' hiccup: what the request sent, the same bytes), without the hashes (a request's, for the next to compare: no part of it). No request is built again: the same records, rendered. See docs/render.md#the-page-is-sent-as-what-changed."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :any]] [:vector [:tuple :any [:vector :seon.model/message]]]]}
  [db refs]
  (let [ev #(d/pull db eval-keys [:seon.eval/id %])
        ;; each commit's parts drawn once: a ref sent as what changed reads the commit before it, the turn before's own
        drawn (let [m (volatile! {})] (fn [a c] (or (get @m [a c]) (let [p (drawn-at db a c)] (vswap! m assoc [a c] p) p))))]
    (mapv (fn [[kind x t :as ref]]
            [ref (case kind
                   :section [{:role "system" :content (system-text (d/as-of db t))}]
                   :eval (record-messages :seon.db/eval-fact (ev x) db)
                   :reply (record-messages :seon.model-call/fact (d/pull db [:seon.model-call/id :seon.model-call/n :seon.model-call/reply :seon.model-call/reasoning] [:seon.model-call/id x]) db)
                   :example (example-messages db x t)
                   :session (session-open x)
                   :parts (let [ids (nth ref 4)] (if (empty? ids) [] (parts-message (drawn x t) ids)))
                   :message (record-messages :seon.message/fact (d/pull db [:seon.message/id :seon.message/from :seon.message/body] [:seon.message/id x]) db))])
          (remove #(= :hashes (first %)) refs))))

(defn messages-tokens
  "Messages ms in tokens, by the printer's sizer over their contents and their calls' code, sized
  together (the sizer's estimate is not additive across pieces): how sent-split and a turn's
  cache check (seon.turn/cache-miss) apportion a request. It reads each part's length and copies
  no text, so it costs its parts, not their characters."
  {:malli/schema [:=> [:cat [:sequential :map]] nat-int?]}
  [ms] (select/chars-tokens (transduce (comp (mapcat (fn [m] (cons (:content m) (map :code (:tool-calls m))))) (map #(count (str %)))) + 0 ms)))

(defn sent-split
  "Where a turn's request went, in tokens (the printer's sizer over each part's content and its
  calls' code), from its parts as parts-of reads them back from the refs the turn recorded: the
  system prompt (:section), the page (each turn's parts, whole or what changed: :page) and the
  conversation (each eval and each message).
  {:system :conversation :page}, and :examples when it sent example sessions (`example-messages`)."
  {:malli/schema [:=> [:cat [:vector [:tuple :any [:vector :seon.model/message]]]]
                  [:map [:system nat-int?] [:conversation nat-int?] [:page nat-int?] [:examples {:optional true} nat-int?]]]}
  [parts]
  (let [part (fn [[kind]] (case kind :section :system :parts :page (:example :session) :examples :conversation))]
    ;; each part's messages sized together (the sizer's estimate is not additive across pieces)
    (merge {:system 0 :conversation 0 :page 0}
           (update-vals (group-by (comp part first) parts) #(messages-tokens (into [] (mapcat second) %))))))

(defn render
  "What a turn's reader got, from its basis b (seon.turn records it on the turn's model call, :seon.model-call/basis) on db,
  the commit b names: the agent's request (`request`), b's :agent's: the system prompt, its record and
  its parts, how its work stands among them (`status`, a fact of the commit). The one maker of a
  context: the model's request is this, and so is every later reading of it. opts :printed, what
  the run's earlier requests printed (the cache `printed-run` answers): only what is new is printed;
  :parts, {id content}, the agent's live walk's parts at db (`request`; else drawn cold); :ctx, its @ctx at db (else drawn cold).
  {:messages :sent :printed :parts}: :parts, each record's ref beside its messages (`request`)."
  {:malli/schema [:function [:=> [:cat :seon.host/db :seon.model-call/basis] [:map [:messages [:vector :seon.model/message]] [:sent [:vector :any]] [:printed :map] [:parts [:vector :any]]]]
                  [:=> [:cat :seon.host/db :seon.model-call/basis :map] [:map [:messages [:vector :seon.model/message]] [:sent [:vector :any]] [:printed :map] [:parts [:vector :any]]]]]}
  ([db basis] (render db basis {}))
  ([db {:keys [agent] :as basis} {:keys [printed parts ctx]}]
  (let [a (d/pull db [:db/id :seon.agent/key] [:seon.agent/key agent])
        r (request a {:db db :basis basis :help (system-text db) :printed printed :parts parts :ctx ctx
                      :help-ref [:section (ref/page-top db system-page) (doc/page-t db system-page)]})]
    (select-keys r [:messages :sent :printed :parts]))))

(defn provider-of
  "The model provider a turn with basis b used, from db at b's commit: main as the agent last heard it
  (its :seon.agent/main-commit; the branch itself before it heard any), the task's own settings and the
  run's model (seon.config/provider-at). The turn calls it at its basis and a replay at the same commit, so
  both name one provider. See docs/render.md#the-debug-page-is-the-wire-request."
  {:malli/schema [:=> [:cat :seon.host/db :seon.model-call/basis] :seon.model/provider]}
  [db {:keys [agent task model]}]
  (let [main (some->> (:seon.agent/main-commit (d/entity db [:seon.agent/key agent])) (d/commit-as-db (:store db)))]
    (cfg/provider-at (or main db) db task model)))

(defn wire-turn
  "Turn basis b's request as it went on the wire, made again from db at b's commit (one `render`):
  {:body :provider :sources :printed}, the body seon.model/request-body made of its messages under its provider
  (`provider-of`), :sources, for each of the body's messages in order the refs of the records that made it
  (seon.model/wire-groups folds a reply's calls into one message), and :printed, the print cache `render`
  answers. With printed, an earlier answer's :printed: what of it this request holds is not printed again
  (the debug page's latest, drawn as the branch moves). See docs/render.md#the-debug-page-is-the-wire-request."
  {:malli/schema [:function [:=> [:cat :seon.host/db :seon.model-call/basis] [:map [:body :map] [:provider :seon.model/provider] [:sources [:vector [:vector :any]]] [:printed :map]]]
                  [:=> [:cat :seon.host/db :seon.model-call/basis [:maybe :map]] [:map [:body :map] [:provider :seon.model/provider] [:sources [:vector [:vector :any]]] [:printed :map]]]]}
  ([db basis] (wire-turn db basis nil))
  ([db basis printed]
  (let [{:keys [messages parts] :as r} (render db basis {:printed printed})
        p (provider-of db basis)
        ;; each neutral message's ref, in order, then each wire message's refs by the one fold
        refs (into [] (mapcat (fn [[ref ms]] (repeat (count ms) ref))) parts)]
    {:body (model/request-body p {:messages messages :task (:task basis) :turn (:turn basis)})
     :provider p
     :sources (mapv (fn [is] (vec (distinct (map refs is)))) (model/wire-groups messages))
     :printed (:printed r)})))

