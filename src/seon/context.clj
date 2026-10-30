(ns seon.context
  "The agent's context, a render of its record (docs/render.md#a-part-answers-data): the system
  prompt, then its record (its evals, messages and replies, `records`) and the parts of its page
  the model reads (the task, each var the plan cites, what is related, how its work stands:
  `status`), each part's data sent by its model render and printed through the one value printer
  (seon.print). Nothing is stored: a turn records its basis (the commit it read and what it took
  that no fact holds), and `render` makes the text from it, for the model and again for anyone who
  reads it later."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [clojure.string :as str] [datahike.api :as d] [seon.render :as render]
            [seon.task :as task] [seon.db :as pdb] [seon.merge :as pm]
            [seon.print :as print] [seon.print.select :as select] [seon.ref :as ref] [seon.docs :as docs] [seon.config :as cfg] [seon.vector :as vector] [seon.ctx :as ctx] [seon.agent :as agent] [seon.program :as program] [seon.proofs :as proofs] [seon.problems :as problems] [seon.reconcile :as r] [seon.edn :as sedn] [seon.learn :as learn] [clojure.edn] [hasch.core :as hasch]))

(def help-page
  "The help page, docs/agent.md: what my.agent/help answers, each section on demand. Main holds
  it as the wiki's sections from root's boot on (seon.root/start!)."
  "docs/agent.md")

(defn help-text
  "The help page as db holds it (seon.docs/page-text). A db without it (no root booted on it) is
  refused by this contract, never answered empty."
  {:malli/schema [:=> [:cat :seon.host/db] :string]}
  [db] (docs/page-text db help-page))

(def system-page
  "The system prompt, docs/prompt.md: what every agent reads first (its system message, the same
  bytes for every agent and turn, so a provider caches it): the standard format's short prompt
  (the owner's ruling: the model's context is the standard format; the help page is read on
  demand, my.agent/help). Main holds it as the wiki's sections, as the help page."
  "docs/prompt.md")

(defn system-text
  "The system prompt as db holds it (seon.docs/page-text): a turn's, at the commit it read. A db
  without it is refused by this contract, never answered empty."
  {:malli/schema [:=> [:cat :seon.host/db] :string]}
  [db] (docs/page-text db system-page))

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
  assertions that each threw one NullPointerException are one cause, read once (the md-scan bench). See docs/render.md#a-part-answers-data."
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
  the compiler's resolved references), each at summary level from its own facts on db: its
  contract and doc, and for a helper of p's own namespace its source too (what the proof means:
  the md-scan bench's agent read its proofs' block-of first); a var is program when db
  holds it (a branch's callee this process never loaded included). See docs/agent.md#what-you-read."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-symbol] [:vector :map]]}
  [db p]
  (vec (for [q (sort (disj (set (d/q '[:find [?r ...] :in $ ?s :where [?e :var/name ?s] [?e :var/form ?f] [?f :form/refs ?r]] db p)) p))
             :let [v (d/entity db [:var/name q])]
             :when (and v (not= :test (:var/kind v)))]
         (cond-> {:var q}
           (:var/contract v) (assoc :contract (clojure.edn/read-string (:var/contract v)))
           (:var/doc v) (assoc :doc {:seon.print/prose (:var/doc v)})
           (= (namespace p) (namespace q)) (merge (some->> (docs/source-of db q) print/code (hash-map :source)))))))

(defn- refused
  "The done rule's reasons as the agent reads them, beyond its own red proofs (shown with
  the proofs): a red test names its failures (a stray one the form that makes it a proof), a contract gap its var, a design step that
  has not improved its plan the call that does, an open problem its work made true what it
  is (seon.problems/caused). defined? says whether the branch defines a test: a stray red it
  does not is one of main's tests the branch removed, which judges all the same (seon.agent/
  test-plan's :removed), so it is marked :removed and offered no form that makes it a proof."
  {:malli/schema [:=> [:cat [:vector :seon.reconcile/not-done] [:vector :map] ifn?] [:vector :map]]}
  [reasons reds defined?]
  (let [rs (remove #(= :proof-red (:reason %)) reasons)
        gaps (vec (keep #(when (= :contract-gap (:reason %)) (:symbol %)) rs))
        unmerged (vec (keep #(when (= :unmerged (:reason %)) (:key %)) rs))
        opened (vec (keep #(when (= :open-problem (:reason %)) (:key %)) rs))]
    ;; a contract gap and an unmerged entry say one thing about many names: one entry each,
    ;; its names a list the printer budgets
    (vec (concat
           (for [{:keys [reason symbol behind]} rs :when (not (#{:contract-gap :unmerged :open-problem} reason))]
             (case reason
               (:regression :stray-red) (cond-> {:reason reason :test symbol}
                                          (seq (filter #(= symbol (:test %)) reds))
                                          (assoc :failures (failures (filter #(= symbol (:test %)) reds)))
                                          ;; a red test the agent wrote is a proof it has not registered, or one to delete
                                          (and (= :stray-red reason) (defined? symbol))
                                          (assoc :do (list 'my.task/update! :seon.task/proofs 'conj (list 'quote symbol)))
                                          ;; main's test the branch removed: it still judges, from main's definition
                                          (and (= :stray-red reason) (not (defined? symbol)))
                                          (assoc :removed true))
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
    {:line (:at (docs/line-at text start)) :column (inc (- at start))
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


(defn plan-citations
  "The references task t's texts make, read on db: the citations their writes derived
  (:seon.task/citations, along its needs: seon.task/references-of), once each at their strongest
  depth, those db holds (seon.ref/kept). What every part reads of what the plan cites; none parses
  the plan again with the program the agent may be changing. See docs/references.md#task-references."
  {:malli/schema [:=> [:cat :seon.host/db :seon.task/task] [:vector :seon.ref/citation]]}
  [db t] (ref/kept db (ref/cited (vec (:citations (:seon.task/accumulated t))))))

(defn- program-vars
  "Of citations, those of the program's vars (seon.program/program-names), in their order: what a
  task's page cites as vars, and what its related search starts from."
  {:malli/schema [:=> [:cat :seon.host/db [:vector :seon.ref/citation]] [:vector :seon.ref/citation]]}
  [db cites]
  (let [vars (filterv #(= :var (docs/citation-kind db %)) cites)
        names (set (program/program-names db (mapv :seon.ref/name vars)))]
    (filterv (comp names :seon.ref/name) vars)))

(defn- not-written
  "What unwritten proof p should assert, by the plan's own words: the plan's sentences that name
  it, or that it is not written yet when none does (the plan is on the page already)."
  {:malli/schema [:=> [:cat :qualified-symbol :string] :string]}
  [p plan]
  (let [ss (filter #(str/includes? % (name p)) (str/split plan #"(?<=[.!?])\s+"))]
    (if (seq ss) (str "not written yet; it should assert what the plan says: " (str/join " " ss)) "not written yet")))

(defn similar
  "The program functions nearest task entity te's plan by meaning (its vector: seon.vector/near,
  exact: every form's vector at every dim, no coarse first pass), those in named left out, the
  first :seon.config.render/similar of them, each one line: its name and its doc's first
  sentence ({:nearest :more}, :more the forms that search further). nil when the count is 0
  (off, the default; a task turns it on in its :seon.task/config); a plan with no vector yet says
  so ({:not-embedded}), never an empty list."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :int] [:set :qualified-symbol]]
                  [:maybe [:or [:map [:nearest [:vector :string]] [:more :string]] [:map [:not-embedded :string]]]]]}
  [db te named]
  (let [;; through the one resolver: a task's own setting (its flattened :seon.task/config), else its model's, else the config's
        t (when te (d/pull db [:seon.config.render/similar :seon.task/model] te))
        k (cfg/value :seon.config.render/similar {:task t :model (:seon.task/model t)})
        ;; a program function, as related's: no test helper or fixture of test/
        program (program/program-nss db)
        function? #(let [n (symbol (namespace %))]
                     (and (= :fn (:var/kind (d/entity db [:var/name %])))
                          (or (contains? program n) (nil? (:ns/file (d/entity db [:ns/name n]))))))
        ;; every vector, every dim, every rank: the exact ranking (near's shortlist and fine level
        ;; over all of them; it sorts them all anyway), walked until k functions are found, as the
        ;; nearest forms are often tests
        all Long/MAX_VALUE]
    (cond
      (zero? k) nil
      (nil? (some->> te (vector/vector-of db))) {:not-embedded "this plan has no vector yet: (my.agent/search \"text\") searches by any text"}
      :else {:nearest (into [] (comp (mapcat (fn [[f _]] (map :var/name (:var/_form (d/entity db f)))))
                                     (remove named) (filter function?) (distinct) (take k)
                                     (map (fn [s] (str s (some->> (:var/doc (d/entity db [:var/name s])) ref/lead-sentence (str ": "))))))
                            (vector/near db te all {:of :form/id :shortlist all :fine-dims all}))
             :more "(my.agent/search 'ns/f) or (my.agent/search \"text\"): more, by meaning, exact words and references"})))

(defn- related-count
  "How many of each kind task entity te's related part shows: :seon.config.render/related through the
  one resolver (its task, its model, the config), as `similar` reads its count."
  {:malli/schema [:=> [:cat :seon.host/db :int] nat-int?]}
  [db te]
  (let [t (d/pull db [:seon.task/model] te)]
    (cfg/value :seon.config.render/related {:task t :model (:seon.task/model t)})))

;; ---- relevance: one calibrated score for a context and an item (docs/vector.md#relevance) ----

(defn- item-text
  "Entity e's text as relevance's lexical feature reads it: its name twice (a var's, a section's
  address and heading, a schema key) and its text (a form's source, a section's, a schema's form)."
  {:malli/schema [:=> [:cat :seon.host/db :int] :string]}
  [db e]
  (let [x (d/entity db e)
        [n t] (cond (:form/id x) [(some->> (:var/_form x) (map :var/name) sort first str) (:form/src x)]
                    (:seon.section/address x) [(str (:seon.section/address x) " " (:seon.section/heading x)) (:seon.section/text x)]
                    (:schema/key x) [(str (:schema/key x)) (pr-str (:schema/form x))]
                    :else ["" ""])]
    (str n " " n " " t)))

(defn- section-names
  "Each var name the wiki's sections name, to those sections: {name #{e}}, one read of
  :seon.section/names (unindexed), so `neighbours` finds a var's sections by a lookup."
  {:malli/schema [:=> [:cat :seon.host/db] [:map-of :qualified-symbol [:set :int]]]}
  [db] (reduce (fn [m {:keys [e v]}] (update m v (fnil conj #{}) e)) {} (d/datoms db :aevt :seon.section/names)))

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
                      (:seon.section/address x) (concat (map form-of (:seon.section/names x))
                                                        (map :db/id (:seon.section/references x))
                                                        (map :db/id (:seon.section/_references x))
                                                        (some-> (:seon.section/parent x) :db/id vector)
                                                        (map :db/id (:seon.section/_parent x)))
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
               (seq (d/datoms db :eavt e :seon.section/address))
               (let [x (d/entity db e)]
                 (+ (k :seon.section/names) (k :seon.section/references) (count (:seon.section/_references x)) (count (:seon.section/_parent x))))
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

(def relevance-kinds
  "Each kind relevance ranks, the attribute its entities have."
  {:function :form/id :section :seon.section/address :schema :schema/key})

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
  (a search's text hits), scored beside the pool. For each kind of kinds (relevance-kinds) the pool is the
  :seon.config.render/relevance-pool nearest by meaning (seon.vector/near) and the seeds'
  neighbourhood (each seed's first neighbours by each read, and those `pushed` at
  :seon.config.render/relevance-eps reached), keep? (of kind and entity) true;
  each item scored by a logistic over five features (:seon.config.render/relevance-weights): zc its
  cosine with qv, zb its BM25 (seon.docs/bm25) for the text's terms (seon.docs/terms), zt its cosine with the touched items' mean
  vector, each a z-score over the pool, g1 log(1 + the seeds it neighbours x its rarity,
  (1 + cap)/(1 + its degree to cap)) and ppr log(1 + scale x its rank), the constants
  :seon.config.render/relevance-features; the logit read as a chance by the kind's calibration (`percent`). Measured on 57 merged
  tasks' plans held out: functions 0.427 at 5 against the rank fusion's 0.367, sections 0.798
  against 0.651. Answers {kind [[e p] ...]}, best first, the touched items left out (a caller leaves out
  what its page already shows)."
  {:malli/schema [:=> [:cat :seon.host/db [:map [:qv [:maybe [:vector number?]]] [:text :string] [:seeds [:set :int]] [:touched [:set :int]]
                                           [:candidates {:optional true} [:set :int]]]
                       [:set :keyword] :seon.host/fn]
                  [:map-of :keyword [:vector [:tuple :int number?]]]]}
  [db {:keys [qv text seeds touched candidates]} kinds keep?]
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
        qs (set (docs/terms text))
        centroid (when (seq touched)
                   (let [vs (keep #(vector/vector-of db %) touched)]
                     (when (seq vs)
                       (let [s (apply mapv + vs) n (Math/sqrt (reduce + 0.0 (map #(* % %) s)))]
                         (when (pos? n) (mapv #(/ % n) s))))))
        near (fn [attr] (if qv (map first (vector/near db qv cap {:of attr})) []))]
    (into {} (for [kind kinds
                   :let [attr (relevance-kinds kind)
                         of-kind? #(seq (d/datoms db :eavt % attr))
                         pool (into [] (comp (distinct) (remove touched) (filter #(and (of-kind? %) (keep? kind %))))
                                    (concat (near attr) seeds candidates (keys links) (keys rank)))]]
               [kind (if (empty? pool)
                       []
                       (let [w (weights kind)
                             zc (z (if qv (vector/scores db qv pool) (repeat (count pool) nil)))
                             zb (z (let [fs (mapv #(frequencies (docs/terms (item-text db %))) pool)] (docs/bm25 qs fs (mapv #(reduce + 0 (vals %)) fs))))
                             zt (z (if centroid (vector/scores db centroid pool) (repeat (count pool) nil)))]
                         (->> (map (fn [e c b t]
                                     (let [l (+ (:intercept w) (* (:zc w) c) (* (:zb w) b) (* (:zt w) t)
                                                (* (:g1 w) (Math/log (+ 1.0 (* (get links e 0) (if (links e) (rare e) 0.0)))))
                                                (* (:ppr w) (Math/log (+ 1.0 (* ppr-scale (get rank e 0.0))))))]
                                       [(- (percent (knots kind) l)) (- l) e]))
                                   pool zc zb zt)
                              sort (mapv (fn [[p _ e]] [e (- p)])))))]))))

(defn related
  "What the work on task entity te's plan is likely to need beyond what the plan cites, each with its
  relevance (`relevance`: the chance the work needs it), so a page shows it at the depth that earns:
  the wiki sections, program functions and schema keys nearest the plan by meaning, words and
  references, the context being the plan, its vector, the vars and sections it cites and the
  schema keys it names (the seeds), and the program vars touched (the branch changed them; their
  forms are the touched items). A plan citing no defined var (a user's request in prose) seeds the
  graph with the first program functions among the :seon.config.render/related-seeds-near forms
  nearest it (task seon.context/a-prose-request-finds-its-functions). Of each kind, at most
  :seon.config.render/related shown, those at :seon.config.render/relevance-summary or more
  (each through the one resolver: the task, its model, the config), the rest counted. Answers
  {:sections [address] :functions [sym] :schemas [key] :relevance {name p} :doc #{name}
  :hidden {kind n}}, :doc those at :seon.config.render/relevance-doc or more, what the plan cites left out (the task's
  data shows it); nil when the count is 0 (none shown) or the plan has no vector yet. opts: :touched
  the vars the branch changed, :cut a cut in place of the configured one, which then lists every
  item at or above it, uncounted (0: every item scored), :focus a vector to rank by in place of the
  plan's and :words the text to match in place of the plan's (`selected`'s trajectory).
  Unlike before relevance, nothing below the cut fills the count: the owner's \"expose more of it but
  not if it's not related\" (docs/vector.md#relevance)."
  {:malli/schema [:function [:=> [:cat :seon.host/db :int] [:maybe [:map [:sections [:vector :string]] [:functions [:vector :qualified-symbol]] [:schemas [:vector :qualified-keyword]]
                                  [:relevance [:map-of :any number?]] [:doc [:set :any]] [:hidden [:map-of :keyword pos-int?]]]]]
                  [:=> [:cat :seon.host/db :int [:map [:touched {:optional true} [:set :qualified-symbol]] [:cut {:optional true} number?]
                                                   [:focus {:optional true} [:vector number?]] [:words {:optional true} :string]]] [:maybe [:map [:sections [:vector :string]] [:functions [:vector :qualified-symbol]] [:schemas [:vector :qualified-keyword]]
                                  [:relevance [:map-of :any number?]] [:doc [:set :any]] [:hidden [:map-of :keyword pos-int?]]]]]]}
  ([db te] (related db te {}))
  ([db te {:keys [touched cut focus words] :or {touched #{}}}]
   (let [k (related-count db te)
         qv (or focus (vector/vector-of db te))]
     (when (and (pos? k) qv)
       (let [t (d/pull db [:seon.task/id :seon.task/model :seon.config.render/relevance-summary :seon.config.render/relevance-doc] te)
             depth {:summary (or cut (cfg/value :seon.config.render/relevance-summary {:task t :model (:seon.task/model t)}))
                    :doc (cfg/value :seon.config.render/relevance-doc {:task t :model (:seon.task/model t)})}
             ;; what the plan cites, as its write derived it (seon.task/references-of): no draw parses it
             tk (task/task db (:seon.task/id t))
             {:keys [plan]} (:seon.task/accumulated tk)
             cites (plan-citations db tk)
             fns (into #{} (keep :seon.ref/name) (program-vars db cites))
             ;; a wiki page's section only: a cited file is read whole by its view
             secs (into #{} (comp (filter #(and (some-> (:seon.ref/path %) (str/ends-with? ".md")) (= :view (docs/citation-kind db %))))
                                  (keep #(:seon.ref/address (docs/cited-view db %))))
                        cites)
             ;; a program function: no test, no macro, nothing outside the program's dirs (test/'s
             ;; fixtures and helpers); a namespace with no file yet (one the agent made at its REPL) is its new work
             program (program/program-nss db)
             function? #(let [n (symbol (namespace %))]
                          (and (not (#{:test :macro} (:var/kind (d/entity db [:var/name %]))))
                               (or (contains? program n) (nil? (:ns/file (d/entity db [:ns/name n]))))))
             form-of #(some-> (d/entity db [:var/name %]) :var/form :db/id)
             functions-of (fn [e] (into [] (comp (map :var/name) (filter function?)) (:var/_form (d/entity db e))))
             defined (filterv #(d/entity db [:var/name %]) fns)
             seeded (when (empty? defined)
                      (into [] (comp (mapcat (comp functions-of first)) (distinct) (take k))
                            (vector/near db te (cfg/value :seon.config.render/related-seeds-near) {:of :form/id})))
             named-keys (filterv #(pdb/eid db :schema/key %) (filter keyword? (keep :seon.ref/name cites)))
             touched-forms (into #{} (keep form-of) touched)
             seeds (into touched-forms (remove nil?) (concat (map form-of (concat defined seeded))
                                                             (map #(pdb/eid db :seon.section/address %) secs)
                                                             (map #(pdb/eid db :schema/key %) named-keys)))
             scored (relevance db {:qv qv :text (or words plan) :seeds seeds :touched touched-forms} (set (keys relevance-kinds))
                               (fn [kind e] (or (not= :function kind) (seq (functions-of e)))))
             name-of {:function functions-of
                      :section #(vector (:seon.section/address (d/entity db %)))
                      :schema #(vector (:schema/key (d/entity db %)))}
             cited {:function fns :section secs :schema #{}}
             ranked (into {} (for [[kind es] scored]
                               [kind (into [] (comp (mapcat (fn [[e p]] (map #(vector % p) ((name-of kind) e))))
                                                    (remove (comp (cited kind) first)) (distinct))
                                           es)]))
             ;; a cut asked for lists all it keeps: (my.task/related 0) is every item scored
             shown (into {} (for [[kind xs] ranked] [kind (into [] (comp (filter #(>= (second %) (:summary depth))) (if cut identity (take k))) xs)]))]
         {:sections (mapv first (shown :section)) :functions (mapv first (shown :function)) :schemas (mapv first (shown :schema))
          :relevance (into {} (mapcat val) shown)
          :doc (into #{} (comp (mapcat val) (filter #(>= (second %) (:doc depth))) (map first)) shown)
          :hidden (into {} (for [[kind xs] ranked :let [n (- (count xs) (count (shown kind)))] :when (pos? n)]
                             [({:function :functions :section :sections :schema :schemas} kind) n]))})))))

(def search-kinds
  "What my.agent/search finds: a function (:var), a :test, a wiki :section, a :task, a :schema key."
  #{:var :test :section :task :schema})

(defn- hit-of
  "Entity e as the hits it is, each [kind name]: the vars (or tests) of a form, a section's address, a
  task's id, a schema key; the kinds asked only."
  {:malli/schema [:=> [:cat :seon.host/db :int [:set :keyword]] [:vector [:tuple :keyword :any]]]}
  [db e kinds]
  (let [x (d/entity db e)]
    (cond (:form/id x) (vec (for [v (:var/_form x) :let [kind (if (= :test (:var/kind v)) :test :var)] :when (kinds kind)] [kind (:var/name v)]))
          (:seon.section/address x) (if (kinds :section) [[:section (:seon.section/address x)]] [])
          (:seon.task/id x) (if (kinds :task) [[:task (:seon.task/id x)]] [])
          (:schema/key x) (if (kinds :schema) [[:schema (:schema/key x)]] [])
          :else [])))

(def ^:private near-attr {:var :form/id :test :form/id :section :seon.section/address :task :seon.task/id :schema :schema/key})

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
    :section (pdb/eid db :seon.section/address n)
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
  :kind one of search-kinds, :name the var, section address, task id or schema key, :e its entity,
  :score the reciprocal-rank score of seon.vector/rrf-scores over the rankings below, :by the set of them it is in (:text :meaning :refs),
  :relevance the chance it is worth reading (seon.context/relevance, over q's vector, words and the
  names it holds, the first k x :seon.config.render/related-near hits by the fused score joined to its
  pool; a function, section or schema key, not yet a test or task),
  :line the line of its text holding a word of q and :at its number. The rankings: the text (seon.docs/tokens:
  BM25 over q's identifier-split words, kept at z >= :seon.config.render/search-token-z), meaning
  (seon.vector/near from qv, the query's vector or an entity with one; none when qv is nil) and the
  references (the callers, callees and naming sections of the names in q: its var, its key, the
  qualified names and keywords of a text; never of the text hits). Best first: by relevance where a hit
  has one, then by the fused score, equal ones by name. A hit with a relevance below :min is left out. When hits are left out by :k or :min, a last entry
  {:kind :more :left n :counts {kind n} :more \"...\"} says how many per kind and the call that returns
  them. opts, all optional: :kinds (a set of search-kinds; #{:var :section} by default: tests, tasks and
  schema keys on request), :by (a set of :text :meaning :refs, all), :ns (a prefix: only vars and tests
  whose namespace starts with it), :k (:seon.config.render/search), :min (:seon.config.render/relevance-summary,
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
        in-ns? (fn [[kind n]] (or (not (#{:var :test} kind)) (nil? prefix) (str/starts-with? (str n) prefix)))
        toks (if (by :text) (filterv #(in-ns? [(:kind %) (:name %)]) (docs/tokens db text kinds (cfg/value :seon.config.render/search-token-z))) [])
        known (into {} (map (fn [h] [[(:kind h) (:name h)] (select-keys h [:line :at :z])])) toks)
        n (* k (cfg/value :seon.config.render/related-near))
        ;; one ranking by meaning: each kind's nearest (near takes one attribute), merged by cosine, which
        ;; one query's scores share
        meaning (if (and (by :meaning) qv)
                  (into [] (comp (mapcat (fn [[e _]] (hit-of db e kinds))) (filter in-ns?) (distinct))
                        (sort-by (comp - second) (mapcat (fn [attr] (vector/near db qv n {:of attr})) (sort (distinct (map near-attr kinds))))))
                  [])
        seeds (names-in db q)
        refs (if (by :refs)
               (into [] (comp (filter in-ns?) (filter (comp kinds first)) (distinct))
                     (concat (for [s seeds :when (symbol? s) c (docs/callers db s) :let [kd (kind-of db c)] :when (kinds kd)] [kd c])
                             (for [s seeds :when (symbol? s) c (map :var (callees db s)) :let [kd (kind-of db c)] :when (kinds kd)] [kd c])
                             (for [s seeds :when (symbol? s) e (d/q '[:find [?e ...] :in $ ?n :where [?e :seon.section/names ?n]] db s) :when (kinds :section)]
                               [:section (:seon.section/address (d/entity db e))])
                             ;; a schema key: the functions whose code or contract name it
                             (for [s seeds :when (keyword? s) c (docs/callers db s) :let [kd (kind-of db c)] :when (kinds kd)] [kd c])))
               [])
        rankings (select-keys {:text (mapv (juxt :kind :name) toks) :meaning meaning :refs refs} (seq by))
        scores (vector/rrf-scores (vec (vals rankings)))
        in (fn [h] (into #{} (keep (fn [[m r]] (when (some #{h} r) m))) rankings))
        first-line (fn [[kind n]] (case kind
                                    (:var :test) (or (some-> (d/entity db [:var/name n]) :var/doc ref/lead-sentence) "")
                                    :section (or (some-> (d/entity db [:seon.section/address n]) :seon.section/lead) "")
                                    :task (or (some-> (d/entity db [:seon.task/id n]) :seon.task/plan (str/split #"\n") first) "")
                                    ""))
        ;; each hit of a kind relevance weighs is scored by it, the hits joined to its pool: a function by
        ;; its form, a section, a schema entry (seon.context/relevance; a test or a task has no chance yet)
        rkind {:var :function :section :section :schema :schema}
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
                         r (relevance db {:qv qvec :text text :seeds sd :touched #{}
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

(declare records related-view)

(defn- unit
  "v as a unit vector, or nil when it has no length."
  {:malli/schema [:=> [:cat [:sequential number?]] [:maybe [:vector number?]]]}
  [v] (let [n (Math/sqrt (reduce + 0.0 (map #(* % %) v)))] (when (pos? n) (mapv #(/ % n) v))))

(defn focus
  "Where agent a's work on task entity te points now, on db: the decayed sum of its trajectory, the
  plan's vector first and then each of the agent's evals' vectors in order, each item's weight
  halving every :seon.config.render/relevance-half-life items after it, so the plan is a prior the
  agent's own work takes over from (the owner: \"the plan will often be fairly empty
  ... we need to adapt to whatever they are doing\"); and the words of its last :seon.config.render/relevance-words evals, the
  plan's before any. {:vector :words}, nil when neither the plan nor any eval has a vector yet.
  Measured on 44 recorded turns of 10 runs (the relevance replay, docs/vector.md): R@10 0.441 and
  0.55 within 800 tokens of what the agent read next, against 0.402 and 0.48 by the plan alone and
  0.411 and 0.49 with the plan held at full weight beside the evals."
  {:malli/schema [:=> [:cat :seon.host/db :int :int] [:maybe [:map [:vector [:vector number?]] [:words :string]]]]}
  [db te a]
  (let [h (cfg/value :seon.config.render/relevance-half-life)
        evs (into [] (keep (fn [[_ v]] (when-let [e (some->> (:seon.eval/id v) (pdb/eid db :seon.eval/id))]
                                        [(some-> (vector/vector-of db e) unit) (get-in v [:seon.eval/record :code])])))
                  (records db a))
        items (into (if-let [pv (some-> (vector/vector-of db te) unit)] [pv] []) (keep first evs))
        n (count items)]
    (when (pos? n)
      {:vector (unit (reduce (fn [acc [i v]] (mapv + acc (map #(* % (Math/pow 0.5 (/ (- n 1 i) h))) v)))
                             (vec (repeat (count (first items)) 0.0)) (map-indexed vector items)))
       :words (if (seq evs)
                (str/join " " (map second (take-last (cfg/value :seon.config.render/relevance-words) evs)))
                (or (:seon.task/plan (d/entity db te)) ""))})))

(defn selected
  "What agent a's context includes beside its work on task entity te now, given prior (what its last
  turn included, :seon.agent/included): units [name chance], each a section's address, a function's
  or a schema key's name that `related` scores against the work's `focus`. One held before stays while
  its chance is :seon.config.render/relevance-summary or more; a new one enters at
  :seon.config.render/relevance-doc or more, best first, after every one before it, while the lines fit
  :seon.config.render/relevance-budget tokens (the owner: 60% to enter, 40% to leave, items
  in entry order, so a turn's page moves by what crossed a cut and every earlier byte holds for the
  prompt cache). The turn calls it once and records its answer (seon.turn), so no frame waits on
  relevance and a replay draws the same units; nil when the work has no vector yet (nothing to decide,
  the record stands)."
  {:malli/schema [:=> [:cat :seon.host/db :int :int [:sequential [:tuple :any number?]]] [:maybe [:vector [:tuple :any number?]]]]}
  [db te a prior]
  (when-let [{fv :vector words :words} (focus db te a)]
    (let [t (d/pull db [:seon.task/model :seon.config.render/relevance-doc :seon.config.render/relevance-summary] te)
          at #(cfg/value % {:task t :model (:seon.task/model t)})
          enter (at :seon.config.render/relevance-doc)
          leave (at :seon.config.render/relevance-summary)]
      ;; nothing enters below the doc cut: no unit the work is not likely to need, however empty the
      ;; page (the owner, 2026-10-27: "if there's nothing at the threshold then show nothing until we
      ;; have more context")
      (when-let [r (related db te {:cut (min enter leave) :focus fv :words words})]
        (let [ch (:relevance r)
              held (into [] (keep (fn [[x]] (when-let [p (ch x)] (when (>= p leave) [x p])))) prior)
              in? (set (map first held))
              kind-of (fn [x] (cond (string? x) :sections (keyword? x) :schemas :else :functions))
              ;; a line's tokens as it is drawn, whole (chars/4)
              cost (fn [x] (quot (count (str (first (vals (dissoc (related-view db {(kind-of x) [x] :relevance {x 1.0} :doc #{x}}) :read :more))))) 4))
              fresh (sort-by (comp - second) (filter (fn [[x p]] (and (>= p enter) (not (in? x)))) ch))]
          (first (reduce (fn [[us used] [x p]] (let [c (cost x)] (if (<= (+ used c) (cfg/value :seon.config.render/relevance-budget)) [(conj us [x p]) (+ used c)] [us used])))
                         [held (reduce + 0 (map (comp cost first) held))] fresh)))))))

(defn included
  "What agent key k's context includes beside task id now on db: `selected` over the work's focus, its
  prior the agent's :seon.agent/included, never a section of the system prompt (`system-page`, the
  request's first message); nil when the work has no vector yet."
  {:malli/schema [:=> [:cat :seon.host/db :seon.task/id :string] [:maybe [:vector [:tuple :any number?]]]]}
  [db task k]
  (let [te (pdb/eid db :seon.task/id task) a (pdb/eid db :seon.agent/key k)]
    ;; the system prompt's own sections are the request's first message already: never again as related
    (when (and te a) (into [] (remove (fn [[u]] (and (string? u) (str/starts-with? u (str system-page "#")))))
                           (selected db te a (or (:seon.agent/included (d/entity db a)) []))))))

(defn shown
  "Units [name chance] (`selected`'s) as `related`'s map, every one whole, for related-view."
  {:malli/schema [:=> [:cat [:sequential [:tuple :any number?]]]
                  [:map [:sections [:vector :string]] [:functions [:vector :qualified-symbol]] [:schemas [:vector :qualified-keyword]]
                   [:relevance [:map-of :any number?]] [:doc [:set :any]] [:hidden [:map-of :keyword nat-int?]]]]}
  [units]
  {:sections (vec (for [[x] units :when (string? x)] x))
   :functions (vec (for [[x] units :when (symbol? x)] x))
   :schemas (vec (for [[x] units :when (keyword? x)] x))
   :relevance (into {} units) :doc (set (map first units)) :hidden {}})

(defn related-view
  "What `related` answers, each a line: a section its address and its lead's first sentence
  (seon.docs/view; its heading when it has no lead), a function its name and its doc's first
  sentence, beside the registry keys it operates on when it names any ([line keys], its
  :form/keywords), a schema key itself. With relevance (`related`'s :relevance), each line names its
  chance, and one at doc depth (its :doc) shows a section's whole lead and a function's whole doc;
  what the cut hid is counted (:hidden) with the form that lists it. With the forms that read one
  whole (naming the text's key: a step-6 run's model read the view with (:text ...) eight times
  over) and search further: what my.task/related and my.agent/related answer. nil when there is
  nothing."
  {:malli/schema [:=> [:cat :seon.host/db [:map [:sections {:optional true} [:vector :string]] [:functions {:optional true} [:vector :qualified-symbol]]
                                           [:schemas {:optional true} [:vector :qualified-keyword]] [:relevance {:optional true} [:map-of :any number?]]
                                           [:doc {:optional true} [:set :any]] [:hidden {:optional true} [:map-of :keyword pos-int?]]]]
                  [:maybe [:map [:sections {:optional true} [:vector :string]]
                           [:functions {:optional true} [:vector [:or :string [:tuple :string [:vector :qualified-keyword]]]]]
                           [:schemas {:optional true} [:vector [:or :qualified-keyword [:tuple :qualified-keyword :string]]]]
                           [:hidden {:optional true} [:map [:counts [:map-of :keyword pos-int?]] [:all :string]]] [:read :string] [:more :string]]]]}
  [db {:keys [sections functions schemas relevance doc hidden]}]
  (when (or (seq sections) (seq functions) (seq schemas) (seq hidden))
   (let [pct (fn [x] (if-let [p (get relevance x)] (str " " (Math/round (* 100.0 (double p))) "%") ""))
         doc? (fn [x] (contains? (or doc #{}) x))]
    (cond-> {}
     (seq sections) (assoc :sections (mapv #(let [x (d/entity db [:seon.section/address %]) v (docs/view db % :summary)]
                                               (str % (pct %) ": " (or (when (doc? %) (:seon.section/lead x)) (:seon.ref/summary v) (:seon.ref/heading v))))
                                            sections))
     ;; with each function the registry keys its code and contract name (its :form/keywords): the
     ;; data it operates on, which earned their tokens (docs/vector.md#what-the-work-is-likely-to-need-as-an-eval)
     (seq functions) (assoc :functions (mapv #(let [v (d/entity db [:var/name %])
                                                    ks (sort-by str (filter (fn [k] (pdb/eid db :schema/key k)) (:form/keywords (:var/form v))))]
                                                ;; its keys a vector beside its line, so the one printer cuts a long one
                                                (cond-> (str % (pct %) (some->> (:var/doc v) ((if (doc? %) identity ref/lead-sentence)) (str ": ")))
                                                  (seq ks) (vector (vec ks))))
                                             functions))
     (seq schemas) (assoc :schemas (mapv #(if (get relevance %) [% (str/trim (pct %))] %) schemas))
     (seq hidden) (assoc :hidden {:counts hidden :all "(my.task/related 0): every item scored, with its chance"})
     true (assoc :read "(:seon.ref/text (my.agent/help \"docs/page.md#anchor\")): one section's text whole; (source ns/f) a function; (my.schema/forms :ns/key) a schema's form"
                 :more "(my.agent/search \"your words\" {:kinds #{:section}}): sections by any text; (my.agent/search \"your words\"): functions and tasks too; (my.agent/callers 'ns/f) or (my.agent/callers :ns/key): who calls it, who operates on it")))))

(declare cited)

(defn cite-key
  "Citation c's one key on a page: the name it cites, else its address (docs/references.md#resolving-and-showing)."
  {:malli/schema [:=> [:cat :seon.ref/citation] :string]}
  [c] (str (or (:seon.ref/name c) (:seon.ref/address c) (:seon.ref/path c))))

(defn current-ns
  "The namespace agent a's REPL is in on db: the one its newest eval left it in (its response's :ns,
  as nREPL's carries it), or nil before it has evaluated anything there. Where its next eval runs (docs/render.md#a-part-answers-data)."
  {:malli/schema [:=> [:cat :seon.host/db :int] [:maybe :symbol]]}
  [db a]
  ;; newest eval first: eval ids are the store's counter, so the index read backward from a's last datom is
  ;; time order (seon.ctx's rseek), and it stops at the first record that carries the ns (every response does)
  (some (fn [dt] (get-in (d/pull db [:seon.eval/record] (:e dt)) [:seon.eval/record :response :ns]))
        (take-while #(and (= :seon.eval/agent (:a %)) (= a (:v %))) (d/rseek-datoms db :avet :seon.eval/agent a))))

(defn namespace-data
  "Namespace nsym as db records it, the facts an agent asks its REPL for before it changes code
  there (the md-scan bench: ~15 evals of ns-aliases, ns-publics and find-ns): its
  aliases (from its recorded ns form, seon.learn/aliases), its definitions by name in file order,
  and its deftests, how many are green and which are red: its datoms, never its forms read (a
  definition's arglists are (doc f)'s, asked of the one at hand). nil when db records no such
  namespace. See docs/render.md#a-part-answers-data."
  {:malli/schema [:=> [:cat :seon.host/db :symbol] [:maybe :map]]}
  [db nsym]
  (when-let [e (pdb/eid db :ns/name nsym)]
    (let [src (d/q '[:find ?src . :in $ ?n :where [?f :form/defines ?n] [?f :form/src ?src]] db e)
          ;; its vars in one pass over the index of :var/ns, each read for the attributes it shows: its
          ;; definitions, and its deftests
          vars (for [dt (d/datoms db :avet :var/ns e) :let [x (d/entity db (:e dt))]]
                 {:var/name (:var/name x) :var/kind (:var/kind x) :seon.test/status (:seon.test/status x)
                  :var/form {:form/order (:form/order (:var/form x))}})
          {tests true defs false} (group-by #(= :test (:var/kind %)) vars)
          defs (->> defs
                    (sort-by (fn [{v :var/name {o :form/order} :var/form}] [(or o Long/MAX_VALUE) (str v)]))
                    (mapv #(symbol (name (:var/name %)))))
          tests (for [{v :var/name st :seon.test/status} tests] [v (or st :none)])
          by (group-by second tests)]
      (cond-> {:ns nsym}
        src (assoc :aliases (into (sorted-map) (learn/aliases [src])))
        (seq defs) (assoc :defines defs)
        (seq tests) (assoc :tests (cond-> {:deftests (count tests) :green (count (by :green))}
                                    (seq (by :red)) (assoc :red (vec (sort (map first (by :red)))))))))))

(defn namespaces-of
  "The namespaces agent a's work on task t touches, {name its namespace-data}: the one its REPL is in
  (`current-ns`) first, marked :here, then those of what the plan
  cites (a var's, a namespace itself) and of the task's proofs, once each, those db records. What
  the page's namespaces part shows the model (docs/render.md#a-part-answers-data)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.task/task :int] [:map-of :symbol :map]]}
  [db t a]
  (let [{:keys [proofs]} (:seon.task/accumulated t)
        cited (for [{n :seon.ref/name} (plan-citations db t)
                    :when (symbol? n)] (if (namespace n) (symbol (namespace n)) n))]
    (let [here (current-ns db a)]
      ;; the one the REPL is in first; a map by name, in
      ;; that order (a part answers a map: a vector is hiccup)
      (->> (concat (some-> (some->> here (namespace-data db)) (assoc :here true) vector)
                   (keep #(namespace-data db %) (remove #{here} (distinct (concat cited (map (comp symbol namespace) proofs))))))
           (mapcat (fn [d] [(:ns d) (dissoc d :ns)]))
           (apply array-map)))))

(defn citations
  "The citations of task t's plan and instructions as their write derived them (:seon.task/citations,
  seon.task/references-of: no draw parses the plan with the program the agent may be changing), one per
  name, in first-mention order, each at the depth its delimiter asks
  (docs/references.md#the-delimiter-picks-the-depth): each that pulls something
  (seon.docs/citation-kind), the task's own proofs left out (the status shows them). What the page and
  the model are shown of what the task cites, one part each (seon.render.ui/cited)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.task/task] [:vector :seon.ref/citation]]}
  [db t]
  (let [{:keys [proofs]} (:seon.task/accumulated t)
        proof? (set proofs)]
    (let [cs (into [] (comp (filter #(docs/citation-kind db %)) (remove #(proof? (:seon.ref/name %))))
                   (plan-citations db t))
          by (group-by cite-key cs)]
      ;; a name cited twice is shown once, at the deepest depth it is cited at
      (mapv (fn [k] (apply max-key #(get {:summary 0 :doc 1 :code 2} (:seon.ref/depth %) 0) (by k)))
            (distinct (map cite-key cs))))))

(defn task-data
  "The task's data: task t's run sections as data, the same each turn of a run: the task (its
  plan as it accumulated along :needs, prerequisites first); its write set, each var the plan
  cites with its source and its callers (docs/callers), the names that resolve to nothing said
  once; each proof to the form that runs it at the REPL, or, when it is not written, that it
  is not and what it should assert in the plan's words (whether it fails, and why, is the
  proofs part's: it changes each turn); the wiki sections it cites; the functions nearest its plan
  by meaning that it does not name (`similar`). With write-set? false (the page's task part, whose cited vars are
  parts of their own, `cited`), no write set is made."
  {:malli/schema [:function [:=> [:cat :seon.host/db :seon.task/task] :map] [:=> [:cat :seon.host/db :seon.task/task :boolean] :map]]}
  ([db t] (task-data db t true))
  ([db t write-set?]
  (let [{:keys [plan proofs]} (:seon.task/accumulated t)
        cites (plan-citations db t)
        refs (program-vars db cites)
        sections (into [] (comp (filter #(= :view (docs/citation-kind db %))) (map #(docs/cited-view db %)))
                       cites)
        near (similar db (some->> (:seon.task/id t) (pdb/eid db :seon.task/id)) (into (set proofs) (map :seon.ref/name) refs))]
    (cond-> {:task (assoc (select-keys t [:seon.task/id :seon.task/kind :seon.task/instructions])
                          :seon.task/plan plan)
             ;; a proof is shown with the proofs, not again in the write set; the names that
             ;; resolve to nothing are one entry, their problem said once
             :write-set (when write-set?
                          (let [rs (into [] (comp (remove (comp (set proofs) :seon.ref/name)) (map #(cited db (:seon.ref/name %)))) refs)
                              {gone true there false} (group-by (comp boolean :unresolved) rs)]
                          (cond-> (vec there)
                            gone (conj {:unresolved (mapv :unresolved gone) :problem (:unresolved ref/problem-messages)}))))
             ;; (the turn sections' :proofs are each turn's statuses; these are how to run them)
             :to-prove (into (sorted-map-by #(compare (str %1) (str %2)))
                             (for [p proofs]
                               [p (if (docs/source-of db p)
                                    (list 'my.agent/prove #{p})
                                    (not-written p plan))]))}
      (seq sections) (assoc :sections sections)
      near (assoc :similar near)
      (not write-set?) (dissoc :write-set)))))

(defn cited
  "Var v as the task's page shows it: its source and its callers (docs/callers), or that it
  resolves to nothing."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-symbol] :map]}
  [db v]
  (let [r (docs/resolve-ref db v :code)]
    (if (:unresolved r) {:unresolved v :problem (:unresolved ref/problem-messages)} (let [secs (docs/citing-sections db v)]
                                                (cond-> (assoc r :callers (docs/callers db v)) (seq secs) (assoc :sections secs))))))

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

(defn status
  "How agent a's work on its task stands on its branch db, as data (task
  seon.context/the-context-is-the-agents-evals-and-seons, R2b-1): each proof by the verdict its var
  entity holds (`verdicts`; none: :unknown, or :undefined while the branch defines no such test),
  a red or unknown one with its source and the program functions it calls (`callees`), a red one
  with its failing assertions (`failure`); the done rule's reasons (seon.reconcile/done-explain)
  and each as what to do (`refused`); what the branch's work did since its merge base, from one
  diff (seon.merge/delta-changes of :seon.agent/base-commit against db): the functions without a
  contract, the problems it caused and the sections it made stale (seon.problems/caused-split),
  what its merge carries, and whether it can merge now when it is given main's commit; its
  unmerged entries; a test run that failed as a whole (:seon.process/test-error). It reads facts
  about the work only, never the conversation (its turns, evals and messages), so a turn's record
  draws it again only when it changed the work; turns left and a cut reply are the run's
  (seon.render.ui/run-head). Nothing is held: each is a fact of db. :reasons is the done rule's
  own answer, the turn's check (seon.turn/work!), which the model and the page read as :refused. opts :index, a function answering the reconciler's index of db (seon.reconcile/index; else
  one built cold), called only when a test outside the task's proofs is red (whether it is a
  regression or a stray red is main's claims: measured cold on a demo branch, 14.8M ticks and
  70 MB), :main, main's commit (the merge view), and :deferred, the task's proofs a run leaves to root's gate (process
  tests: none while every proof has a verdict). See docs/render.md#how-the-work-stands."
  {:malli/schema [:=> [:cat :seon.host/db :int :map] :map]}
  [db a {:keys [index deferred main]}]
  (let [ag (d/entity db a)
        tid (some-> ag :seon.agent/task :seon.task/id)
        t (when tid (task/task db tid))
        {:keys [proofs plan]} (:seon.task/accumulated t)
        kind (:seon.task/kind t :work)
        ;; a test run that failed as a whole: present on its process only while it is so (the
        ;; process's other facts, written on every eval, are not read)
        test-error (d/q '[:find ?err . :in $ ?a :where [?p :seon.process/test-error ?err] [?p :seon.process/agent ?a]] db a)
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
        ;; its run by the agent, never through its turns: a turn's record moves nothing this reads
        run (some->> (d/q '[:find [?r ...] :in $ ?a :where [?r :seon.run/agent ?a]] db a) seq (apply max) (d/entity db))
        proof? (set proofs)
        ;; main's claims only when a test outside the proofs is red: the agent's index, else one built cold
        claims (when (some (fn [[t st]] (and (= :fail st) (not (proof? t)))) results)
                 (cond index (index)
                       ;; in an agent's process its index, advanced by what differs; else one built cold
                       (= :agent (:mode @agent/state)) (agent/reconcile-index db)
                       :else (r/index db)))
        input (cond-> (if claims
                        (r/done-input claims tid results (set gaps) (mapv #(sedn/encode (:key %)) unmerged) nil (:seon.run/plan run) (set deferred))
                        (cond-> {:proofs (set proofs) :kind kind :results results :main-green #{} :allowed-red #{}
                                 :uncontracted (set gaps) :unmerged (mapv #(sedn/encode (:key %)) unmerged) :deferred (set deferred)}
                          (= :design kind) (assoc :plan plan)
                          (and (= :design kind) (:seon.run/plan run)) (assoc :plan-before (:seon.run/plan run))))
                (seq (:caused split)) (assoc :caused (set (:caused split))))
        reasons (r/done-explain input)
        reds (vec (for [[p {:keys [failures]}] vs f failures] (assoc f :test p)))
        defined? #(some? (docs/source-of db %))]
    (cond-> {:reasons reasons
             :proofs (vec (for [p (sort-by str proofs)
                                :let [src (docs/source-of db p)
                                      st (get results p (cond (deferred p) :deferred (nil? src) :undefined (some? test-error) :not-run :else :unknown))
                                      fs (get-in vs [p :failures])
                                      calls (when (and src (not= :pass st)) (callees db p))]]
                            (cond-> {:test p :status st}
                              (and src (not= :pass st)) (assoc :source (print/code src))
                              (seq fs) (assoc :failures (failures fs))
                              (seq calls) (assoc :calls calls))))}
      (seq (refused reasons reds defined?)) (assoc :refused (refused reasons reds defined?))
      test-error (assoc :test-run {:failed [(:seon.error/message test-error)]})
      (seq unmerged) (assoc :unmerged (mapv #(select-keys % [:key :kind :base :ours :theirs]) unmerged))
      (seq gaps) (assoc :without-a-contract gaps)
      (seq (:caused split)) (assoc :caused (:caused split))
      (seq (:attention split)) (assoc :attention (:attention split))
      (seq (pm/divergence ch)) (assoc :its-merge-carries (pm/divergence ch))
      (and main base) (assoc :merge (pm/readiness base db (d/commit-as-db (:store db) main) moved)))))

(defn cited-names
  "The program vars task t's plan and instructions cite (seon.ref/references, those of the program:
  `program-vars`), by name: what the task part shows of its write set, which the turn's waste log
  charges a form reading again to (seon.learn/waste, producer seon.docs/resolve-ref)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.task/task] [:vector :symbol]]}
  [db t]
  (mapv :seon.ref/name (program-vars db (plan-citations db t))))

(defn status-model
  "The status as the model is sent it (the declared model render of :seon.context/status): its
  data, the done rule's raw :reasons left out (each is told as what to do, :refused). See
  docs/render.md#a-part-answers-data."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [_opts v] (dissoc v :reasons))

(defn eval-messages
  "An eval's part of the conversation as
  provider-neutral messages: a model's eval (its record names the provider's call id) is its
  assistant message, the reply's prose and reasoning as returned with the eval call (:call, the
  call's place in its reply: the provider's format folds a reply's calls into one message),
  then its tool message, the response printed now (seon.print/record-text, a function of the
  record alone); anyone else's (the user's) is one user message
  {:by :code :response}, the response spliced as printed."
  {:malli/schema [:=> [:cat :map :any] [:vector :seon.model/message]]}
  [{:seon.eval/keys [by record]} _]
  (let [{:keys [code prose content reasoning call-id call]} record
        text (print/record-text record)]
    (if call-id
      ;; as the model sent it: the reply's text (its first call's :content) and the call's own prose
      [(cond-> {:role "assistant" :content (or content "")
                :tool-calls [(cond-> {:id call-id :code code} prose (assoc :prose prose))]}
         reasoning (assoc :reasoning reasoning)
         call (assoc :call call))
       {:role "tool" :tool-call-id call-id :content text}]
      [{:role "user" :content (str "{:by " (pr-str by) " :code " (pr-str code) " :response " text "}")}])))

(defn message-messages
  "A message's part of the conversation: one
  user message {:from :body}."
  {:malli/schema [:=> [:cat :map :any] [:vector :seon.model/message]]}
  [{:seon.message/keys [from body]} _]
  [{:role "user" :content (print/text {:from from :body body} (:context print/budgets))}])

(defn turn-messages
  "A turn's reply with no call, as the conversation keeps it: one assistant message, the reply's
  text and the reasoning as returned (a reply that called is its evals' messages, `eval-messages`). See docs/render.md#the-models-context-a-cached-prefix-then-the-turn."
  {:malli/schema [:=> [:cat :map :any] [:vector :seon.model/message]]}
  [{:seon.turn/keys [reply reasoning]} _]
  [(cond-> {:role "assistant" :content reply} reasoning (assoc :reasoning reasoning))])

(defn- ref-of
  "The ref a conversation's record is sent as (:seon.model-call/sent): an eval's, a message's or a turn's id."
  {:malli/schema [:=> [:cat :map] [:tuple :keyword :string]]}
  [v] (cond (:seon.eval/id v) [:eval (:seon.eval/id v)] (:seon.message/id v) [:message (:seon.message/id v)] :else [:turn (:seon.turn/id v)]))

(defn printed-run
  "The run's records new since cache (items, [ref v k tx] in tx order, ref [:eval id], [:message
  id], [:turn id] or [:parts id]) printed as the conversation sends them, each once (cache, what the
  run's earlier requests printed: content-keyed reuse by the record's id, AGENTS section 5): an
  eval, a message or a prose reply by its own render, a function of that record alone; a parts item
  (a delay of {:ref :whole}, `request`) drawn only when not printed before. A record's text depends
  on nothing before it, so a request prints only its new records and every older message is the same
  bytes (the provider's cache). An item already printed is skipped. Answers the cache: {:ids :parts
  {id part} :tx}, each part {:ref :whole}, :tx the newest item's."
  {:malli/schema [:=> [:cat [:sequential :any] [:maybe :map]] :map]}
  [items cache]
  (reduce (fn [c [[kind id] v k tx]]
            (let [part (fn [ref whole] {:ref ref :whole whole})
                  c (cond-> (update c :ids conj id) tx (assoc :tx tx))]
              (cond
                (get-in c [:parts id]) (update c :ids pop)
                (= :message kind) (assoc-in c [:parts id] (part [:message id] (message-messages v {})))
                (= :turn kind) (assoc-in c [:parts id] (part [:turn id] (turn-messages v {})))
                (= :parts kind) (assoc-in c [:parts id] @v)
                :else (assoc-in c [:parts id] (part [:eval id] (eval-messages v {}))))))
          (or cache {:ids [] :parts {}}) items))

(defn records
  "What agent a's conversation is made of on db, in tx order: its evals (not a context read's: that
  ends only the request that ran it), the messages it took
  (:seon.message/agent, written on its branch by the turn that took each) and its turns' replies that
  called nothing (the agent winding down: its prose is its assistant message), each [tx value schema-key]; with since,
  only those of transaction since and later (what a request has not printed yet). The
  session's, not one run's: a run that wakes or loads the session reads every run before it
  (design agent-loop section 1)."
  {:malli/schema [:function [:=> [:cat :seon.host/db :int] [:vector [:tuple :int :map :qualified-keyword]]]
                  [:=> [:cat :seon.host/db :int [:maybe :int]] [:vector [:tuple :int :map :qualified-keyword]]]]}
  ([db a] (records db a nil))
  ([db a since]
  (->> (concat (for [[e tx] (d/q '[:find ?e ?tx :in $ ?a ?since :where [?e :seon.eval/agent ?a ?tx] [(>= ?tx ?since)]] db a (or since 0))
                     :let [v (d/pull db [:seon.eval/id :seon.eval/by :seon.eval/record] e)]
                     :when (not= "context" (:seon.eval/by v))]
                 [tx v :seon.db/eval-fact])
               (for [[e tx] (d/q '[:find ?m ?tx :in $ ?a ?since :where [?m :seon.message/agent ?a ?tx] [(>= ?tx ?since)]] db a (or since 0))]
                 [tx (d/pull db [:seon.message/id :seon.message/from :seon.message/body] e) :seon.message/fact])
               (for [[t tx] (d/q '[:find ?t ?tx :in $ ?a ?since :where [?t :seon.turn/agent ?a] [?t :seon.turn/reply _ ?tx] [(>= ?tx ?since)]] db a (or since 0))
                     :let [v (d/pull db [:seon.turn/id :seon.turn/reply :seon.turn/reasoning :seon.turn/calls :seon.turn/truncated] t)]
                     ;; a reply cut at the token limit is no message, as the turn loop reads it (seon.turn/act!)
                     :when (and (empty? (:seon.turn/calls v)) (not (:seon.turn/truncated v)) (not (str/blank? (:seon.turn/reply v))))]
                 [tx (dissoc v :seon.turn/calls :seon.turn/truncated) :seon.turn/fact]))
       (sort-by (fn [[tx v]] [tx (:seon.eval/id v (:seon.message/id v (:seon.turn/id v)))]))
       vec)))

(def page-lists "The lists of an agent's page its model reads: its task's (#p-task)." ["#p-task"])

(defn drawn-parts
  "The parts of an agent's page its model reads, drawn cold from its record c on db (its branch as of a
  commit): its task's list (the layout :seon.render/render-ctx declares, drawn once by the walk,
  seon.render/draw), each component's hiccup exactly as its render returned it, by its element id,
  in order: the same hiccup the person's page shows (seon.repl/page-hiccup has it from the live walk,
  at the cost of what changed). A read of history (a replay, a request with no live walk), never a turn's work. See docs/render.md#the-page-is-sent-as-what-changed."
  {:malli/schema [:=> [:cat :seon.host/db [:map [:tree :map]]] :map]}
  [db c]
  (let [w (render/draw (:tree c) ctx/changes db (render/declared-var :seon.render/render-ctx))]
    (try (apply array-map (mapcat identity (render/placed-hiccup w page-lists)))
         (finally (render/dispose-walk! w)))))

(defn- drawn-at
  "Agent a's parts as of commit c of db's branch (`drawn-parts`); db itself when c is its commit (a
  judge's speculative db has no commit of its own)."
  {:malli/schema [:=> [:cat :seon.host/db :int :uuid] :map]}
  [db a c]
  (let [db1 (if (= c (d/commit-id db)) db (d/commit-as-db db c))]
    (drawn-parts db1 (ctx/build db1 a (:seon.agent/branch (d/entity db a))))))

(defn- parts-message
  "The one user message of the parts a request sent: {id hiccup} in the order of ids, nil for a part
  that left, printed whole; none when ids is empty."
  {:malli/schema [:=> [:cat :map [:sequential :string]] [:vector :seon.model/message]]}
  [live ids]
  (if (empty? ids)
    []
    [{:role "user" :content (print/text (vary-meta (apply array-map (mapcat (fn [k] [k (get live k)]) ids)) assoc :seon.print/full true))}]))

(defn- calls-of
  "The model calls of agent a's turns of origin o that sent, [id tx call-entity] oldest first (tx: of
  the turn's record)."
  {:malli/schema [:=> [:cat :seon.host/db :int :keyword] [:vector :any]]}
  [db a o]
  (->> (d/q '[:find ?id ?tx ?m :in $ ?a ?o :where [?t :seon.turn/agent ?a] [?t :seon.turn/origin ?o ?tx] [?m :seon.model-call/turn ?t]
              [?m :seon.model-call/id ?id] [?m :seon.model-call/sent]] db a o)
       (sort-by second) vec))

(defn- sent-of
  "What model call entity m sent (:seon.model-call/sent)."
  {:malli/schema [:=> [:cat :seon.host/db :int] [:vector :any]]}
  [db m] (:seon.model-call/sent (d/pull db [:seon.model-call/sent] m)))

(defn- trimmed?
  "Whether a conversation record the refs sent names is gone from agent a's records (the agent
  retracted it with swap! on @ctx): the request after is drawn whole."
  {:malli/schema [:=> [:cat :seon.host/db :int [:sequential :any]] :boolean]}
  [db a refs]
  (let [attrs {:eval [:seon.eval/agent :seon.eval/id] :message [:seon.message/agent :seon.message/id] :turn [:seon.turn/agent :seon.turn/id]}]
    (boolean
      (some (fn [[kind ids]]
              (let [[ag idk] (attrs kind)
                    have (set (d/q [:find '[?id ...] :in '$ '?a :where ['?x ag '?a] ['?x idk '?id]] db a))]
                (not-every? have ids)))
            (update-vals (group-by first (filter (comp attrs first) refs)) #(map second %))))))

(declare parts-of)

(defn- ref-id
  "The id printed-run keys a request's ref by: a record's id, a parts ref's call."
  {:malli/schema [:=> [:cat [:sequential :any]] :string]}
  [ref] (if (= :parts (first ref)) (str "parts/" (nth ref 5)) (second ref)))

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
  system prompt (system-text), then the session in tx order (`records`, printed once by printed-run,
  opts :printed the run's earlier requests' prints, answered again as :printed; all of it, as a REPL
  keeps its scrollback): each eval (eval-messages), message (message-messages) and prose reply
  (turn-messages), and the page's parts as one user message each request holds (`parts-message`):
  every part whole at the session's first request (before the records), after it the parts whose
  content differs from what the previous model call recorded of them (:hashes) and nil for one that
  left, at the turn's place in the records; none when none changed. So the request is the previous
  request's refs (read back from its model call when this process holds no print of them) and what
  was added. opts :parts, {id hiccup}, the agent's live walk's (seon.repl/page-hiccup; else drawn cold
  at db's commit). A record the previous request sent that is gone (the agent trimmed its context
  with swap! on @ctx) draws the request whole: the system prompt, every part whole, the records that
  remain. Origin :outside (a read): the system prompt and the parts, whole at its first read, after it
  what changed since the read before. Answers {:messages :sent :printed}: provider-neutral messages
  (seon.model/wire-messages makes the wire shape) and the records they render, in order
  (:seon.model-call/sent): [:section eid t]; [:eval id]; [:message id]; [:turn id]; [:parts a c whole?
  ids call], the parts the message holds (drawn cold at commit c on replay), and last [:hashes {id
  hash}], the content hash of every part the model has now, which the next request compares. Called by
  the turn. See docs/render.md#the-page-is-sent-as-what-changed and the task
  seon.context/the-context-is-the-agents-evals-and-seons."
  {:malli/schema [:=> [:cat :map :map] [:map [:messages [:vector :seon.model/message]] [:sent [:vector :any]] [:printed :map]]]}
  [a {:keys [db basis help help-ref printed parts]}]
  (let [e (:db/id a)
        model? (= :model (:origin basis))
        id (str (:run basis) (if model? "/" "/read-") (:turn basis))
        ;; this turn's own tx: the request is the turn's as it was, whatever came after it on db (a read of
        ;; history on a later db answered the newest turn's request), so the call before it is the last one
        ;; below it and the records are those at or before it
        now (or (when model? (d/q '[:find ?tx . :in $ ?id :where [?t :seon.turn/id ?id] [?t :seon.turn/origin _ ?tx]] db id)) (:max-tx db))
        [_ prev-tx prev-m] (last (filter #(and (not= id (first %)) (< (second %) now)) (calls-of db e (:origin basis))))
        prev (when prev-m (sent-of db prev-m))
        cut? (boolean (and prev (trimmed? db e prev)))
        old (when-not cut? (some #(when (= :hashes (first %)) (second %)) prev))
        whole? (nil? old)
        live (or parts (drawn-at db e (d/commit-id db)))
        ;; a part's hash is its printed form's: its hiccup holds the Vars of its components, which no content hash reads
        hs (into {} (map (fn [[k v]] [k (hasch/uuid (pr-str v))])) live)
        ids (into (vec (for [k (keys live) :when (or whole? (not= (get old k) (get hs k)))] k))
                  (when old (sort (remove #(contains? live %) (keys old)))))
        ref [:parts e (d/commit-id db) whole? ids id]
        current [[:parts id] (delay {:ref ref :whole (parts-message live ids)}) nil (if whole? 0 now)]
        ;; what the previous request sent, its refs in order: this process's print of them, else read back from the records
        before (when (and model? (not whole?)) (into [] (remove #(#{:section :hashes} (first %))) prev))
        base (when before
               (if (= before (mapv #(:ref (get-in printed [:parts %])) (:ids printed))) printed (cache-of db before)))
        rs (if model? (into [] (comp (filter (fn [[tx]] (<= tx now))) (map (fn [[tx v k]] [(ref-of v) v k tx]))) (records db e (when before prev-tx))) [])
        items (sort-by (fn [[_ _ _ tx]] (or tx Long/MAX_VALUE)) (concat rs [current]))
        cache (printed-run items base)
        ps (mapv #(get-in cache [:parts %]) (:ids cache))]
    {:messages (into [{:role "system" :content help}] (mapcat :whole) ps)
     :printed cache
     :sent (-> [help-ref] (into (map :ref) ps) (conj [:hashes hs]))}))

;; ---- a turn's basis: what its context was built from, and the text made from it again ----

(declare render)

(defn parts-of
  "Refs (as :seon.model-call/sent names records) each beside its messages, each record by its own
  render: the system prompt's section as of its t (system-text), an eval by eval-messages, a
  message or a prose reply by its render, the parts of a turn ([:parts a c whole? ids], drawn cold from
  the branch at commit c, the ids' hiccup: what the request sent, the same bytes), without the hashes (a request's, for the next to compare: no part of it). No request is built again: the same records, rendered. See docs/render.md#the-page-is-sent-as-what-changed."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :any]] [:vector [:tuple :any [:vector :seon.model/message]]]]}
  [db refs]
  (let [ev #(d/pull db [:seon.eval/id :seon.eval/by :seon.eval/record] [:seon.eval/id %])]
    (mapv (fn [[kind x t :as ref]]
            [ref (case kind
                   :section [{:role "system" :content (system-text (d/as-of db t))}]
                   :eval (eval-messages (ev x) {})
                   :turn (turn-messages (d/pull db [:seon.turn/id :seon.turn/reply :seon.turn/reasoning] [:seon.turn/id x]) {})
                   :parts (if (empty? (nth ref 4)) [] (parts-message (drawn-at db x t) (nth ref 4)))
                   :message (message-messages (d/pull db [:seon.message/id :seon.message/from :seon.message/body] [:seon.message/id x]) {}))])
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
  {:system :conversation :page}."
  {:malli/schema [:=> [:cat [:vector [:tuple :any [:vector :seon.model/message]]]]
                  [:map [:system nat-int?] [:conversation nat-int?] [:page nat-int?]]]}
  [parts]
  (let [part (fn [[kind]] (case kind :section :system :parts :page :conversation))]
    ;; each part's messages sized together (the sizer's estimate is not additive across pieces)
    (merge {:system 0 :conversation 0 :page 0}
           (update-vals (group-by (comp part first) parts) #(messages-tokens (into [] (mapcat second) %))))))

(defn render
  "What a turn's reader got, from its basis b (seon.turn records it on the turn's model call, :seon.model-call/basis) on db,
  the commit b names: the agent's request (`request`), b's :agent's: the system prompt, its record and
  its parts, how its work stands among them (`status`, a fact of the commit). The one maker of a
  context: the model's request is this, and so is every later reading of it. opts :printed, what
  the run's earlier requests printed (the cache `printed-run` answers): only what is new is printed;
  :parts, {id content}, the agent's live walk's parts at db (`request`; else drawn cold).
  {:messages :sent :printed}."
  {:malli/schema [:function [:=> [:cat :seon.host/db :seon.model-call/basis] [:map [:messages [:vector :seon.model/message]] [:sent [:vector :any]] [:printed :map]]]
                  [:=> [:cat :seon.host/db :seon.model-call/basis :map] [:map [:messages [:vector :seon.model/message]] [:sent [:vector :any]] [:printed :map]]]]}
  ([db basis] (render db basis {}))
  ([db {:keys [agent] :as basis} {:keys [printed parts]}]
  (let [a (d/pull db [:db/id :seon.agent/key] [:seon.agent/key agent])
        r (request a {:db db :basis basis :help (system-text db) :printed printed :parts parts
                      :help-ref [:section (docs/page-top db system-page) (docs/page-t db system-page)]})]
    (select-keys r [:messages :sent :printed]))))
