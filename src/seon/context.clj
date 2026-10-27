(ns seon.context
  "The agent's context, rendered live each turn from its task and what it references:
  the current-state block (task plan and instructions, the proofs with their red
  assertions and source, every var the task's text cites (seon.ref/references) resolved to its doc and
  source, every wiki section, file and namespace it cites shown at the depth its delimiter asks (seon.docs/view, seon.ref/file-view, seon.ref/namespace-view), the last evaluations), printed through the one value printer (seon.print). Nothing is stored:
  a turn records its basis (the commit it read and the observations not in the db), and
  `render` makes the text from it, for the model and again for anyone who reads it later."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [clojure.string :as str] [datahike.api :as d] [seon.render :as render]
            [seon.task :as task] [seon.db :as pdb] [seon.merge :as pm]
            [seon.print :as print] [seon.print.select :as select] [seon.reader :as reader] [seon.ref :as ref] [seon.docs :as docs] [seon.config :as cfg] [seon.vector :as vector] [seon.ctx :as ctx] [seon.program :as program] [clojure.edn] [clojure.walk :as walk]))

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

(defn- failure
  "A failing assertion as the agent reads it (the test AI render): the assertion as written
  and what it evaluated to, (= 9 (square 3)) got (= 9 6), never the assertion twice; the
  message only when it says something."
  {:malli/schema [:=> [:cat :map] :map]}
  [r]
  (let [code #(cond-> % (string? %) print/code)]
    ;; an assertion as written is code (seon.print/code), printed as in the test file
    (cond-> {:assert (code (:expected r)) :got (code (or (:got r) (:actual r)))}
      (:message r) (assoc :message (:message r)))))

(defn callees
  "The program functions proof p calls directly (one level: its recorded form's :form/refs,
  the compiler's resolved references), each at summary level from its own facts on db: its
  contract and doc, never its body; a var is program when db holds it (a branch's callee
  this process never loaded included)."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-symbol] [:vector :map]]}
  [db p]
  (vec (for [q (sort (disj (set (d/q '[:find [?r ...] :in $ ?s :where [?e :var/name ?s] [?e :var/form ?f] [?f :form/refs ?r]] db p)) p))
             :let [v (d/entity db [:var/name q])]
             :when (and v (not= :test (:var/kind v)))]
         (cond-> {:var q}
           (:var/contract v) (assoc :contract (clojure.edn/read-string (:var/contract v)))
           (:var/doc v) (assoc :doc {:seon.print/prose (:var/doc v)})))))

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
           (for [{:keys [reason symbol]} rs :when (not (#{:contract-gap :unmerged :open-problem} reason))]
             (case reason
               (:regression :stray-red) (cond-> {:reason reason :test symbol}
                                          (seq (filter #(= symbol (:test %)) reds))
                                          (assoc :failures (mapv failure (filter #(= symbol (:test %)) reds)))
                                          ;; a red test the agent wrote is a proof it has not registered, or one to delete
                                          (and (= :stray-red reason) (defined? symbol))
                                          (assoc :do (list 'my.task/update! :seon.task/proofs 'conj (list 'quote symbol)))
                                          ;; main's test the branch removed: it still judges, from main's definition
                                          (and (= :stray-red reason) (not (defined? symbol)))
                                          (assoc :removed true))
               ;; a design made from a request carries none: it registers each it writes
               :no-proofs {:reason reason :do '(my.task/update! :seon.task/proofs conj 'ns.x-test/a-proof)}
               :no-design {:reason reason :unchanged :seon.task/plan
                           :do '(my.task/update! :seon.task/plan str "\n\n" "<what the implement step must do>")}
               ;; no result: never written, or never run; what writes and runs it
               :proof-unknown {:reason reason :test symbol
                               :do (list 'do (list 'in-ns (list 'quote (clojure.core/symbol (namespace symbol))))
                                         (list 'clojure.test/deftest (clojure.core/symbol (name symbol)) (list 'clojure.test/is false task/placeholder-assertion))
                                         (list 'clojure.test/run-test-var (list 'var symbol)))}
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
    {:line (inc (count (filter #(= \newline %) (subs text 0 start)))) :column (inc (- at start))
     :text (subs text start end)}))

(defn last-reply
  "What happened to the agent's last reply: cut at the token limit, each delimiter the
  reader repaired (what it dropped or inserted, and where: the line and column in source,
  the reply as the reader gave it to the REPL, and that line), or nil."
  {:malli/schema [:=> [:cat [:maybe :boolean] [:maybe :string] [:vector :map]] [:maybe :map]]}
  [truncated? source repairs]
  (let [rs (for [{:keys [at dropped inserted]} repairs :when (not= ";; " inserted)]
             (cond-> {:near (near (or source "") at)}
               (seq dropped) (assoc :dropped dropped)
               (seq inserted) (assoc :inserted inserted)))]
    (cond-> nil
      truncated? (assoc :cut "at the model's token limit: what followed the cut was lost. Answer with less: one change at a time.")
      (seq rs) (assoc :repaired (vec rs)))))


(defn taken-messages
  "The messages a turn took, as its basis's :observed names them: each that is a fact read
  from db (the commit the turn read, where its agent wrote it) by its id, then each send no
  fact holds as the basis keeps it. The one reader of a turn's messages (the block, the
  agent's window)."
  {:malli/schema [:=> [:cat :seon.host/db :map] [:vector [:map [:from :string] [:body :any]]]]}
  [db {:keys [message-ids messages]}]
  (into (vec (for [mid message-ids :let [m (d/pull db [:seon.message/from :seon.message/body] [:seon.message/id mid])]]
               {:id mid :from (:seon.message/from m) :body (:seon.message/body m)}))
        messages))

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

(defn tokens
  "Text s's identifier tokens, lower case: s split at every character that is no letter or digit
  and between a lower and an upper case letter, those of two characters or more. What relevance's
  lexical feature matches, so `seon.vector/near` and `near-of` match the words near and of. One
  pass over the characters: on 150 of the program's forms a third of the time of a regex split
  and a sixteenth of its bytes, the same tokens."
  {:malli/schema [:=> [:cat [:maybe :string]] [:vector :string]]}
  [s]
  (let [s (or s "") n (count s)
        word (fn [out start end] (if (and (>= start 0) (> (- end start) 1)) (conj! out (str/lower-case (subs s start end))) out))]
    (loop [i 0 start -1 lower? false out (transient [])]
      (if (< i n)
        (let [c (.charAt s i)]
          (cond (not (Character/isLetterOrDigit c)) (recur (inc i) -1 false (word out start i))
                ;; a word starts here: after a separator, or at an upper case letter after a lower
                (or (neg? start) (and lower? (Character/isUpperCase c))) (recur (inc i) i (Character/isLowerCase c) (word out start i))
                :else (recur (inc i) start (Character/isLowerCase c) out)))
        (persistent! (word out start n))))))

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

(defn- bm25
  "Each text's BM25 score (Robertson and Zaragoza 2009; k1 and b :seon.config.render/relevance-features's
  :bm25-k1 and :bm25-b) for the query's distinct tokens qs, fs each text's token frequencies; its idf
  counted over fs, so the texts scored are their own corpus and no index of the store is read. In
  fs's order."
  {:malli/schema [:=> [:cat [:set :string] [:sequential [:map-of :string :int]]] [:vector number?]]}
  [qs fs]
  (let [{k1 :bm25-k1 b :bm25-b} (cfg/value :seon.config.render/relevance-features)
        n (count fs) lens (mapv #(reduce + 0 (vals %)) fs) avg (max 1.0 (/ (reduce + 0.0 lens) (max 1 n)))
        idf (into {} (for [w qs :let [df (count (filter #(contains? % w) fs))]] [w (Math/log (+ 1.0 (/ (+ (- n df) 0.5) (+ df 0.5))))]))]
    (mapv (fn [f len] (reduce + 0.0 (for [w qs :let [c (get f w)] :when c]
                                      (* (idf w) (/ (* c (inc k1)) (+ c (* k1 (+ (- 1 b) (* b (/ len avg))))))))))
          fs lens)))

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
  cosine with qv, zb its BM25 for the text's tokens, zt its cosine with the touched items' mean
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
        qs (set (tokens text))
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
                             zb (z (bm25 qs (mapv #(frequencies (tokens (item-text db %))) pool)))
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
  item at or above it, uncounted (0: every item scored).
  Unlike before relevance, nothing below the cut fills the count: the owner's \"expose more of it but
  not if it's not related\" (docs/vector.md#relevance)."
  {:malli/schema [:function [:=> [:cat :seon.host/db :int] [:maybe [:map [:sections [:vector :string]] [:functions [:vector :qualified-symbol]] [:schemas [:vector :qualified-keyword]]
                                  [:relevance [:map-of :any number?]] [:doc [:set :any]] [:hidden [:map-of :keyword pos-int?]]]]]
                  [:=> [:cat :seon.host/db :int [:map [:touched {:optional true} [:set :qualified-symbol]] [:cut {:optional true} number?]]] [:maybe [:map [:sections [:vector :string]] [:functions [:vector :qualified-symbol]] [:schemas [:vector :qualified-keyword]]
                                  [:relevance [:map-of :any number?]] [:doc [:set :any]] [:hidden [:map-of :keyword pos-int?]]]]]]}
  ([db te] (related db te {}))
  ([db te {:keys [touched cut] :or {touched #{}}}]
   (let [k (related-count db te)
         qv (vector/vector-of db te)]
     (when (and (pos? k) qv)
       (let [t (d/pull db [:seon.task/id :seon.task/model :seon.config.render/relevance-summary] te)
             depth {:summary (or cut (cfg/value :seon.config.render/relevance-summary {:task t :model (:seon.task/model t)}))
                    :doc (cfg/value :seon.config.render/relevance-doc)}
             {:keys [plan]} (:seon.task/accumulated (task/task db (:seon.task/id t)))
             cites (ref/references db plan)
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
             scored (relevance db {:qv qv :text plan :seeds seeds :touched touched-forms} (set (keys relevance-kinds))
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
  symbol or a keyword; in a text, each qualified symbol that names a var and each qualified keyword."
  {:malli/schema [:=> [:cat :seon.host/db [:or :string :qualified-symbol :qualified-keyword]] [:vector :any]]}
  [db q]
  (cond (keyword? q) [q]
        (symbol? q) (if (d/entity db [:var/name q]) [q] [])
        :else (vec (distinct (concat (for [m (re-seq #"[a-z][a-z0-9.*+!?<>=-]*/[a-z0-9.*+!?<>=-]+" q)
                                       :let [sym (symbol m)] :when (d/entity db [:var/name sym])]
                                   sym)
                                     (for [m (re-seq #":[a-z][a-z0-9.-]*/[a-z0-9.-]+" q)] (keyword (subs m 1))))))))

(defn search
  "What on db is the best match for q (a text; a var's name, whose neighbours it also finds by who
  calls it, whom it calls and the sections naming it; or a schema key, whose readers and writers it
  finds), as a vector of hits best first, each {:kind :name :e :score :by :relevance :line :at}:
  :kind one of search-kinds, :name the var, section address, task id or schema key, :e its entity,
  :score the weighted reciprocal-rank score of seon.vector/rrf-scores over the rankings below
  (:seon.config.render/search-weights), :by the set of them it is in (:text :meaning :refs),
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
        weights (cfg/value :seon.config.render/search-weights)
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
        scores (vector/rrf-scores (vec (vals rankings)) (vec (map weights (keys rankings))))
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

(defn task-data
  "The task's data: task t's run sections as data, the same each turn of a run: the task (its
  plan as it accumulated along :needs, prerequisites first); its write set, each var the plan
  cites with its source and its callers (docs/callers), the names that resolve to nothing said
  once; each proof to the form that runs it at the REPL, or, when it is not written, that it
  is not and what it should assert in the plan's words (whether it fails, and why, is the
  proofs part's: it changes each turn); the wiki sections it cites; the functions nearest its plan
  by meaning that it does not name (`similar`). With write-set? false (the page's task part, whose
  cited vars are parts of their own, `cited`), no write set is made."
  {:malli/schema [:function [:=> [:cat :seon.host/db :seon.task/task] :map] [:=> [:cat :seon.host/db :seon.task/task :boolean] :map]]}
  ([db t] (task-data db t true))
  ([db t write-set?]
  (let [{:keys [plan proofs]} (:seon.task/accumulated t)
        cites (ref/references db (str plan "\n" (:seon.task/instructions t)))
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
    (if (:unresolved r) {:unresolved v :problem (:unresolved ref/problem-messages)} (assoc r :callers (docs/callers db v)))))

(defn turn-state
  "The turn sections of task t's current state as data, measured again each turn from
  observed: proofs with their status, red assertions, source and calls; what else the done
  rule refuses (regressions, stray reds, contract gaps), and a test run that failed as a whole;
  the branch's unmerged entries (base, ours, theirs); what happened to the last reply; turns
  left. What its merge carries, whether it can merge, what it changed under the wiki and its
  tests are the task render's views (seon.render.ui/divergence-view, merge-view, caused-view,
  tests-view). The messages delivered are the conversation's (`conversation`), not the block's."
  {:malli/schema [:=> [:cat :seon.host/db :seon.task/task :map] :map]}
  [db t {:keys [results reds turn max-turns unmerged truncated? reasons test-run-errors source repairs]}]
  (let [{:keys [proofs]} (:seon.task/accumulated t)]
    (cond-> {;; a passing proof is its status; a red or unrun one shows its source, its failures
             ;; and the program functions it calls; one not defined yet says so
             :proofs (vec (for [p (sort-by str proofs)
                                :let [src (docs/source-of db p)
                                      ;; no result: not written yet, the run failed as a whole, or it never ran (unknown,
                                      ;; the judge's word: seon.reconcile/done-explain)
                                      st (get results p (cond (nil? src) :undefined (seq test-run-errors) :not-run :else :unknown))
                                      fs (filter #(= p (:test %)) reds)]]
                            (cond-> {:test p :status st}
                              ;; its source is code, printed as in its file (seon.print/code)
                              (and src (not= :pass st)) (assoc :source (print/code src))
                              (seq fs) (assoc :failures (mapv failure fs))
                              (and src (not= :pass st) (seq (callees db p))) (assoc :calls (callees db p)))))}
      max-turns (assoc :turn (str (inc turn) " of " max-turns))
      (seq (refused (vec reasons) (vec reds) #(some? (docs/source-of db %)))) (assoc :refused (refused (vec reasons) (vec reds) #(some? (docs/source-of db %))))
      (seq test-run-errors) (assoc :test-run {:failed (vec test-run-errors)})
      (last-reply truncated? source (vec repairs)) (assoc :your-last-reply (last-reply truncated? source (vec repairs)))
      (seq unmerged) (assoc :unmerged (mapv #(select-keys % [:key :kind :base :ours :theirs]) unmerged)))))

(defn current-state
  "The current-state block of task t as data: its run sections (`task-data`) and its turn
  sections from observed (`turn-state`)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.task/task :map] :map]}
  [db t observed]
  (merge (task-data db t) (turn-state db t observed)))

(defn- folded
  "Response r with each of its members fold names (:value, :out: the handle of the earlier record
  that showed the same) as the form that reads it there, (:value result/e12)."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [r fold] (reduce (fn [r [k h]] (assoc r k (list k h))) r fold))

(defn eval-messages
  "An eval's part of the conversation as
  provider-neutral messages: a model's eval (its record names the provider's call id) is its
  assistant message, the reply's prose and reasoning as returned with the eval call (:call, the
  call's place in its reply: the provider's format folds a reply's calls into one message),
  then its tool message, the response printed now (seon.print/record-text, opts :fold: its value or
  output an earlier record showed, as the form that reads it there, `folded`; opts :same-as: the
  handle of an earlier eval of the same code that answered the same, shown beside the answer
  whole), or with opts
  :collapsed its handle and its value in one line (:line, which a record with a value or an
  error holds); anyone else's (the user's) is one user message
  {:by :code :response}, the response spliced as printed."
  {:malli/schema [:=> [:cat :map :any] [:vector :seon.model/message]]}
  [{:seon.eval/keys [by record]} opts]
  (let [{:keys [code prose content call-prose reasoning call-id call]} record
        ;; collapsed (opts :collapsed, an older one the history keeps small): its handle and its value in one line
        text (if (:collapsed opts)
               (str (get-in record [:response :handle]) " ⇒ " (:line record))
               (print/record-text (cond-> record
                                    (seq (:fold opts)) (update :response folded (:fold opts))
                                    (:same-as opts) (assoc-in [:response :same-as] (:same-as opts)))))]
    (if call-id
      ;; as the model sent it: the reply's text, and the call's own prose argument when it gave one
      ;; (a record with neither, from before the prose argument, replays its prose as the text)
      [(cond-> {:role "assistant" :content (or content (when-not call-prose prose) "")
                :tool-calls [(cond-> {:id call-id :code code} call-prose (assoc :prose call-prose))]}
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

(defn- ref-of
  "The ref a conversation's record is sent as (:seon.turn/sent): an eval's or a message's id."
  {:malli/schema [:=> [:cat :map] [:tuple :keyword :string]]}
  [v] (if (:seon.eval/id v) [:eval (:seon.eval/id v)] [:message (:seon.message/id v)]))

(defn- fold-of
  "Which of response r's members an earlier record of the conversation showed (seen, seon.print/seen
  over the records before it, in order; canon, their collection values by type and value): :value
  or :out, each the earlier one's handle, {} when none. A stored value is a copy of its own, so an
  equal earlier one stands for it (canon: by value, safe on stored data, which is finite)."
  {:malli/schema [:=> [:cat :map [:maybe :map] :map] :map]}
  [r seen canon]
  (into {} (for [k [:value :out] :when (contains? r k)
                 :let [v (get r k)
                       form (if (string? v)
                              (get-in seen [:strings v])
                              (when-let [x (get canon [(type v) v])]
                                (some (fn [[o f]] (when (identical? o x) f)) (get-in seen [:colls (System/identityHashCode x)]))))]
                 :when form]
             [k (second form)])))

(defn- cost
  "Tokens a part (its messages) sends, by the printer's sizer: each message's content and its calls' code."
  {:malli/schema [:=> [:cat [:vector :seon.model/message]] :int]}
  [ms] (select/estimate (str/join (mapcat (fn [m] (cons (:content m) (map :code (:tool-calls m)))) ms))))

(defn printed-run
  "The run's records new since cache (items, [ref v k tx] in tx order, ref [:eval id] or [:message
  id]) printed as the conversation sends them, each once (cache, what the run's earlier requests
  printed: content-keyed reuse by the record's id, AGENTS section 5): an eval whole (its value or
  output an earlier record showed, as the form that reads it there: its ref [:eval id fold]) and
  collapsed (its handle and one line), each with its cost; a page item ([:page a c0 c1], its
  messages a delay: `world`) drawn only when not printed before; and an eval of code an earlier
  eval of the run ran, with the same answer, whole beside that one's handle (its ref [:eval id
  {:same-as handle}]: the agent asked again and is told so on the answer it reads, where a fold
  showed it only the earlier one's form; whole whatever the history sends of that one, since it
  shows its own answer). A record's text is a function of it and
  the records before it, which never change, so a request prints only its new records and every
  older message is the same bytes (the provider's cache). An item already printed is skipped.
  Answers the cache: {:ids :parts {id part} :seen :canon :codes :tx} (:codes, each code's last
  eval: its handle and its answer's hash), each part {:ref :whole :collapsed
  :cost [whole collapsed] :call}, a folded one also :fold and :plain (it unfolded), :tx the newest
  record's."
  {:malli/schema [:=> [:cat [:sequential :any] [:maybe :map]] :map]}
  [items cache]
  (reduce (fn [{:keys [seen canon] :as c} [[kind id] v k tx]]
            (let [part (fn [ref whole collapsed]
                         {:ref ref :whole whole :collapsed collapsed :cost [(cost whole) (cost collapsed)] :call (:call (first whole) 0)})
                  c (cond-> (update c :ids conj id) tx (assoc :tx tx))]
              (cond
                (get-in c [:parts id]) (update c :ids pop)
                (= :message kind) (let [ms (message-messages v {})] (assoc-in c [:parts id] (part [:message id] ms ms)))
                ;; the page at a turn: drawn only now, when no earlier request printed it (v, a delay)
                (= :page kind) (let [ms @v] (assoc-in c [:parts id] (part (into [:page] id) ms ms)))
                :else
                (let [rec (:seon.eval/record v) r (:response rec)
                      ;; the agent's eval of code an eval of the run ran before, answered the same: that one's handle
                      answer (hash (assoc (dissoc r :handle :ms :ticks :bytes) :printed (:printed rec)))
                      same (when-let [[h a] (get-in c [:codes (:code rec)])] (when (= a answer) h))
                      c (cond-> c (not same) (assoc-in [:codes (:code rec)] [(:handle r) answer]))]
                  (if same
                    (assoc-in c [:parts id] (part [:eval id {:same-as same}] (eval-messages v {:same-as same}) (eval-messages v {:collapsed true})))
                (let [has? (and (contains? r :value) (coll? (:value r)))
                      ;; an equal earlier value stands for this one (seen is by identity)
                      x (when has? (get canon [(type (:value r)) (:value r)] (:value r)))
                      r (cond-> r has? (assoc :value x))
                      fold (if (:printed rec) {} (fold-of r seen canon))]
                  (-> c
                      (assoc-in [:parts id] (cond-> (part (cond-> [:eval id] (seq fold) (conj fold))
                                                          (eval-messages v {:fold fold}) (eval-messages v {:collapsed true}))
                                              ;; and as it reads unfolded: sent so while what it folds to is not shown whole
                                              (seq fold) (assoc :fold fold :plain (eval-messages v {}))))
                      (assoc :seen (if (:printed rec) seen (print/seen seen r))
                             :canon (cond-> canon has? (assoc [(type x) x] x))))))))))
          (or cache {:ids [] :parts {} :seen nil :canon {} :codes {}}) items))

(defn- as-sent
  "Part p (printed-run's) as a request sends it, whole-ids the evals it sends whole: folded (its
  value or output as the form that reads an earlier record's, :fold) only while each record it
  folds to is sent whole (folds point back, so those are known), else unfolded (:plain), its ref
  without the fold. Answers [ref messages]."
  {:malli/schema [:=> [:cat :map [:set :string]] [:tuple :any [:vector :seon.model/message]]]}
  [{:keys [ref whole fold plain]} whole-ids]
  (if (every? #(contains? whole-ids (name %)) (vals fold)) [ref whole] [[:eval (second ref)] plain]))

(defn- kept
  "Which of the parts (each {:cost [whole collapsed] :call}, oldest first) the history budget sends:
  the parts walked oldest first, holding two cuts, where the collapsed parts (a result's handle and
  one line) begin, every part before left out, and where the whole ones begin. When what is sent
  passes the budget, the oldest whole parts collapse until the whole ones fit in half of it, then,
  if all is still over half, the oldest collapsed are left out until it fits: the cuts only advance,
  in jumps that leave room for many turns, so between jumps each request is the one before and
  what was added (the provider's prefix cache; a cut recomputed each turn moved back and forth, and
  a resumed run hit 23% of 1.1M tokens). A cut never starts inside a reply (a part continuing
  the call before it), and the newest part is whole. A function of the parts alone: a cold read
  sends what a warm one does. Answers [picks dropped]: :whole or :line for each part from the first
  sent, and how many older ones were left out."
  {:malli/schema [:=> [:cat [:vector :map] :int] [:tuple [:vector [:enum :whole :line]] nat-int?]]}
  [parts budget]
  (let [n (count parts)
        sums (fn [k] (vec (reductions + 0 (map #(nth (:cost %) k) parts))))
        whole (sums 0) small (sums 1)
        span (fn [sv a b] (- (nth sv b) (nth sv a)))
        ;; a cut moved to i, then on past any part that continues a reply, at most to j
        start (fn [i j] (or (first (drop-while #(pos? (:call (nth parts %))) (range i (inc j)))) j))
        half (quot budget 2)
        [l w] (reduce (fn [[l w] j]
                        (let [sent #(+ (span small %1 %2) (span whole %2 (inc j)))]
                          (if (<= (sent l w) budget)
                            [l w]
                            (let [w (start (or (first (filter #(<= (span whole % (inc j)) half) (range w (inc j)))) j) j)
                                  l (if (<= (sent l w) half) l
                                        (start (or (first (filter #(<= (sent % w) half) (range l (inc w)))) w) w))]
                              [l w]))))
                      [0 0] (range n))]
    [(into (vec (repeat (- w l) :line)) (repeat (- n w) :whole)) l]))

(defn records
  "What agent a's conversation is made of on db, in tx order: its evals (not a context read's: that
  ends only the request that ran it) and the messages its runs took
  (written on its branch by the turn that took each), each [tx value schema-key]; with since,
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
               (for [[e tx] (d/q '[:find ?m ?tx :in $ ?a ?since :where [?ru :seon.run/agent ?a] [?m :seon.message/to ?ru ?tx] [(>= ?tx ?since)]] db a (or since 0))]
                 [tx (d/pull db [:seon.message/id :seon.message/from :seon.message/body] e) :seon.message/fact]))
       (sort-by (fn [[tx v]] [tx (:seon.eval/id v (:seon.message/id v))]))
       vec)))

(defn page
  "An agent's page as its model reads it, from its record c on db (its branch as of a turn's
  commit): its task's list (the layout :seon.render/render-ctx declares, drawn once by the walk,
  seon.render/draw), each component's hiccup exactly as its render returned it, by its element id,
  in order: the same hiccup the person's page shows (seon.repl/render-ctx-hiccup draws it through this
  process's walk)."
  {:malli/schema [:=> [:cat :seon.host/db [:map [:tree :map]]] :map]}
  [db c]
  (let [w (render/draw (:tree c) ctx/changes db (render/declared-var :seon.render/render-ctx))]
    (try (apply array-map (mapcat identity (render/placed-hiccup w ["#p-task"])))
         (finally (render/dispose-walk! w)))))

(defn world
  "What changed for agent entity a between two commits of its branch on db (the ones a turn's
  request and the one before it were made on), as data: {:page {key hiccup}, each part of its page whose hiccup changed (nil for one
  that went), :ctx {path [old new]}, seon.ctx/diff of its record advanced across them, every path that
  changed}, the empty ones left out; in the diff the agent's own records, which its conversation
  already shows, are told by what reads them: an eval by its handle, a turn by its id; with no c0, the page whole,
  {:page page}.
  Printed whole as one user message: the page and each change told once."
  {:malli/schema [:=> [:cat :seon.host/db pos-int? [:maybe :any] :any] [:vector :seon.model/message]]}
  [db a c0 c1]
  ;; the request's own commit is db itself (a judge's speculative db, d/with, has no commit of its own)
  (let [at #(if (= % (d/commit-id db)) db (d/commit-as-db db %))
        branch (:seon.agent/branch (d/entity db a))
        db1 (at c1)
        v (if (nil? c0)
            {:page (page db1 (ctx/build db1 a branch))}
            ;; the record at c0, built, then advanced to c1 by what the two commits differ by: one build
            (let [db0 (at c0) r0 (ctx/build db0 a branch) r1 (ctx/advance r0 db0 db1 branch)
                  p0 (page db0 r0) p1 (page db1 r1)
                  dp (into (array-map) (for [k (distinct (concat (keys p0) (keys p1))) :when (not= (get p0 k) (get p1 k))] [k (get p1 k)]))
                  ;; an eval's handle is result/<its id> (seon.repl/record!); a path under one is told as any path
                  told #(cond (not (map? %)) %
                              (:seon.eval/id %) (symbol "result" (:seon.eval/id %))
                              (:seon.turn/id %) (:seon.turn/id %)
                              :else %)
                  dc (into (sorted-map-by #(compare (str %1) (str %2)))
                           (for [[p ov] (ctx/diff r0 r1)] [p (mapv #(walk/prewalk told %) ov)]))]
              (cond-> {} (seq dp) (assoc :page dp) (seq dc) (assoc :ctx dc))))]
    [{:role "user" :content (print/text (vary-meta v assoc :seon.print/full true))}]))

(defn- turn-pages
  "The turns of agent entity a's session on db made by origin (:model or :outside), oldest
  first, each [tx commit]: the transaction that recorded it, and the commit its request was made
  on, after its record (so its page holds what it observed), as the page ref it sent names; the
  newest, recorded and not sent yet, the request now being made on db. A turn that sent nothing
  was never asked."
  {:malli/schema [:=> [:cat :seon.host/db pos-int? :keyword] [:vector [:tuple :int :any]]]}
  [db a origin]
  (let [ts (sort-by first (d/q '[:find ?tx ?t :in $ ?a ?o :where [?r :seon.run/agent ?a] [?t :seon.turn/run ?r] [?t :seon.turn/origin ?o ?tx]] db a origin))
        newest (second (last ts))]
    (vec (keep (fn [[tx t]]
                 (if-let [c (some (fn [ref] (when (= :page (first ref)) (nth ref 3))) (rseq (vec (:seon.turn/sent (d/pull db [:seon.turn/sent] t)))))]
                   [tx c]
                   (when (= t newest) [tx (d/commit-id db)])))
               ts))))

(defn request
  "The agent's request, a render of its record: agent a (its entity map, :db/id) for the turn
  basis b on db (opts :db :basis :help, and :help-ref, the record the system prompt renders): the system prompt (system-text), then the session in tx order (`records`, printed
  once by printed-run, opts :printed the run's earlier requests' prints, answered again as
  :printed; within the history budget, `kept`): each eval (eval-messages) and message
  (message-messages), and at each model turn of the session, the page: the first turn's whole,
  each later one's what changed since the turn before (`world`: the page's parts by key and ctx's
  diff), each at the commit its turn's request was made on, after the turn's record. So the
  session's first request reads the page whole and each later one what changed, each change told
  once, and every request is the one before and what was added. How many earlier parts the budget
  left out goes just after the system prompt. An outside agent's read (origin :outside): the
  system prompt and the page, whole at its first read and after it what changed since the read
  before. Answers {:messages
  :sent}: provider-neutral messages (seon.model/wire-messages makes the wire shape) and the
  records they render, in order (:seon.turn/sent): [:section eid t], the system prompt's section
  as of t; [:eval id] (with its fold); [:line id], an eval sent collapsed; [:message id]; [:page a
  c0 c1], the page at commit c1 (c0 nil) or what changed since c0; [:history n], the n parts the
  budget left out. Called by the turn."
  {:malli/schema [:=> [:cat :map :map] [:map [:messages [:vector :seon.model/message]] [:sent [:vector :any]] [:printed :map]]]}
  [a {:keys [db basis help help-ref printed]}]
  (let [e (:db/id a)
        model? (= :model (:origin basis))
        since (when model? (:tx printed))
        ;; each model turn's page at the commit its request was made on (a turn records itself
        ;; before its request; a read with none recorded, db's), drawn only when printed-run has not
        ;; printed it (a delay)
        ;; an outside read is recorded after its request, so it is the newest, on db; it tells
        ;; what changed since the read before it (the whole page at the first)
        ts (if model?
             (or (not-empty (turn-pages db e :model)) [[(:max-tx db) (d/commit-id db)]])
             (conj (turn-pages db e :outside) [(:max-tx db) (d/commit-id db)]))
        pages (for [[[tx c] prev] (map vector ts (cons nil (map second ts)))
                    :when (or (nil? since) (>= tx since))]
                [[:page [e prev c]] (delay (world db e prev c)) nil tx])
        pages (if model? pages [(last pages)])
        rs (if model? (mapv (fn [[tx v k]] [(ref-of v) v k tx]) (records db e since)) [])
        items (sort-by (fn [[_ _ _ tx]] (or tx Long/MAX_VALUE)) (concat rs pages))
        cache (printed-run items (when model? printed))
        parts (mapv #(get-in cache [:parts %]) (:ids cache))
        [picks dropped] (kept parts (cfg/value :seon.config.render/history-budget))
        whole-ids (into #{} (keep (fn [[{:keys [ref]} pick]] (when (and (= :whole pick) (= :eval (first ref))) (second ref)))) (map vector (drop dropped parts) picks))
        sent (mapv (fn [{:keys [ref collapsed] :as p} pick] (if (= :line pick) [[:line (second ref)] collapsed] (as-sent p whole-ids)))
                   (drop dropped parts) picks)
        note (when (pos? dropped) {:role "user" :content (pr-str {:history {:left-out dropped}})})]
    ;; how many parts the budget left out, just after the system prompt: it changes only when the
    ;; cut jumps, so every message after it is the one before and what was added
    {:messages (-> [{:role "system" :content help}] (cond-> note (conj note)) (into (mapcat second) sent))
     :printed cache
     :sent (-> [help-ref] (cond-> note (conj [:history dropped])) (into (map first) sent))}))

;; ---- a turn's basis: what its context was built from, and the text made from it again ----

(declare render)

(defn parts-of
  "Refs (as :seon.turn/sent names records) each beside its messages, each record by its own
  render: the system prompt's section as of its t (system-text), an eval by eval-messages with the
  fold its ref names (what it showed of an earlier record, as the form that reads it there) or
  collapsed ([:line id]), a message by its render, the page at a turn or what changed since the
  turn before ([:page a c0 c1], `world`, re-derived from the branch at those commits),
  the history note by its count: what the request sent, the same bytes. No request is built
  again: the same records, rendered."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :any]] [:vector [:tuple :any [:vector :seon.model/message]]]]}
  [db refs]
  (let [ev #(d/pull db [:seon.eval/id :seon.eval/by :seon.eval/record] [:seon.eval/id %])]
    (mapv (fn [[kind x t :as ref]]
            [ref (case kind
                   :section [{:role "system" :content (system-text (d/as-of db t))}]
                   :eval (eval-messages (ev x) (cond (:same-as t) t (map? t) {:fold t} :else {}))
                   :page (world db x t (nth ref 3))
                   :line (eval-messages (ev x) {:collapsed true})
                   :message (message-messages (d/pull db [:seon.message/id :seon.message/from :seon.message/body] [:seon.message/id x]) {})
                   :history [{:role "user" :content (pr-str {:history {:left-out x}})}])])
          refs)))

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
  system prompt (:section), the page (each turn's, whole or what changed: :page) and the
  conversation (each eval, whole or collapsed, each message, the history note).
  {:system :conversation :page}."
  {:malli/schema [:=> [:cat [:vector [:tuple :any [:vector :seon.model/message]]]]
                  [:map [:system nat-int?] [:conversation nat-int?] [:page nat-int?]]]}
  [parts]
  (let [part (fn [[kind]] (case kind :section :system :page :page :conversation))]
    ;; each part's messages sized together (the sizer's estimate is not additive across pieces)
    (merge {:system 0 :conversation 0 :page 0}
           (update-vals (group-by (comp part first) parts) #(messages-tokens (into [] (mapcat second) %))))))

(defn turn-input
  "What a turn's sections are made from (turn-state's input): the observations basis b holds,
  what happened to the last model turn lst's reply (cut at the token limit, the delimiters
  the reader repaired), the branch's unmerged entries on a merge task, and the turn count of
  a model turn (an outside agent's reads have none)."
  {:malli/schema [:=> [:cat :seon.host/db :map [:maybe :map]] :map]}
  [db {:keys [turn max-turns origin observed merge?]} lst]
  (let [{:keys [text repairs]} (when lst (reader/forgive (str/join "\n" (map :code (:seon.turn/calls lst)))))]
    (cond-> (assoc observed :truncated? (:seon.turn/truncated lst) :source text :repairs (vec repairs)
                   :unmerged (when merge? (pm/unmerged db)))
      (= :model origin) (assoc :turn turn :max-turns max-turns))))

(defn render
  "What a turn's reader got, from its basis b (seon.turn records it, :seon.turn/basis) on db,
  the commit b names: its task's current state (the task's data and the turn's state from what b
  observed: the block the turn's waste reads, what the page shows), and the messages, the agent's
  request (`request`), b's :agent's. The one maker of a context: the model's request is this, and so is every
  later reading of it. opts :printed, what the run's earlier requests printed (the cache
  `printed-run` answers): only what is new is printed. {:block :messages :sent :printed}."
  {:malli/schema [:function [:=> [:cat :seon.host/db :seon.turn/basis] [:map [:block :map] [:messages [:vector :seon.model/message]] [:sent [:vector :any]] [:printed :map]]]
                  [:=> [:cat :seon.host/db :seon.turn/basis :map] [:map [:block :map] [:messages [:vector :seon.model/message]] [:sent [:vector :any]] [:printed :map]]]]}
  ([db basis] (render db basis {}))
  ([db {:keys [task agent] :as basis} {:keys [printed]}]
  (let [block (current-state db (task/task db task) (turn-input db basis nil))
        a (d/pull db [:db/id :seon.agent/key] [:seon.agent/key agent])
        r (request a {:db db :basis basis :block block :help (system-text db) :printed printed
                      :help-ref [:section (docs/page-top db system-page) (docs/page-t db system-page)]})]
    {:block block :messages (:messages r) :sent (:sent r) :printed (:printed r)})))
