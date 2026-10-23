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
            [seon.print :as print] [seon.print.select :as select] [seon.reader :as reader] [seon.ref :as ref] [seon.docs :as docs] [seon.config :as cfg] [seon.vector :as vector] [clojure.edn]))

(def help-page
  "The help page, docs/agent.md: what every agent reads first (its system message, the same
  bytes for every agent and turn, so a provider caches it), and what my.agent/help answers.
  Main holds it as the wiki's sections from root's boot on (seon.root/start!)."
  "docs/agent.md")

(defn help-text
  "The help page as db holds it (seon.docs/page-text): a turn's, at the commit it read. A db
  without it (no root booted on it) is refused by this contract, never answered empty."
  {:malli/schema [:=> [:cat :seon.host/db] :string]}
  [db] (docs/page-text db help-page))

(defn source-of
  "The recorded source of var sym on db (the text of the form that last defined it), or nil:
  one AVET seek (seon.db/eid), never a query."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-symbol] [:maybe :string]]}
  [db sym] (some->> (pdb/eid db :var/name sym) (d/entity db) :var/form :form/src))

(def reference-tokens
  "Tokens a cited var's source may take in the context: the config's."
  (cfg/value :seon.config.render/reference-tokens))

(defn resolve-ref
  "Var sym as its citation's depth shows it (docs/references.md#the-delimiter-picks-the-depth):
  :summary its arglists and its doc's first sentence, :doc its arglists, doc and contract,
  :code its source (from the db). A source over reference-tokens shows the doc instead, with
  what it left out and the form that reads it; at :doc a var with no doc shows its source. A
  name that resolves to nothing says so."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-symbol :seon.ref/depth] :map]}
  [db sym depth]
  (let [m (some-> (find-ns (symbol (namespace sym))) ns-interns (get (symbol (name sym))) meta)
        src (source-of db sym)
        doc (cond-> {:symbol sym}
              (:arglists m) (assoc :arglists (:arglists m))
              (:doc m) (assoc :doc (:doc m))
              (:malli/schema m) (assoc :contract (:malli/schema m)))
        n (some-> src select/estimate)]
    (cond
      (and (nil? m) (nil? src)) {:symbol sym :unresolved true :problem (:unresolved ref/problem-messages)}
      (= :summary depth) (cond-> (select-keys doc [:symbol :arglists]) (:doc m) (assoc :summary (ref/lead-sentence (:doc m))))
      (or (nil? src) (and (= :doc depth) (:doc m))) doc
      (<= n reference-tokens) {:symbol sym :source src}
      :else (assoc doc :left-out (str "its source, about " n " tokens, over the context's " reference-tokens)
                       :read (list 'clojure.repl/source sym)))))

(defn- rrf
  "Rankings fused by reciprocal rank (Cormack et al. 2009): each item scores the sum of 1/(k + rank)
  over the rankings it is in, k :seon.config.vector/rrf-k; best first, the name breaking a tie."
  {:malli/schema [:=> [:cat [:sequential [:sequential :qualified-symbol]]] [:vector :qualified-symbol]]}
  [rankings]
  (let [k (cfg/value :seon.config.vector/rrf-k)
        s (apply merge-with + {} (for [r rankings] (into {} (map-indexed (fn [i x] [x (/ 1.0 (+ k i 1))])) r)))]
    (mapv peek (sort (map (fn [[x v]] [(- v) (str x) x]) s)))))

(defn found
  "The helpers the plan does not name (R4's lever), from two rankings fused by reciprocal rank
  (rrf): the call graph's, the program functions that sources (the plan's cited vars and the
  proofs) name in their recorded definitions, one hop, ranked as a search ranks terms (tf-idf:
  called by more of the sources, and by fewer forms of the whole program); and the program's
  forms nearest the task's plan (seon.vector/near over its vector, the :seon.config.render/found-near
  nearest), which finds a helper nothing cited calls. Less named and less macros; each at doc
  depth; at most the config's :seon.config.render/found, then a line saying how many it left and
  how to read them. A task with no vector yet (nil, or not embedded) ranks by the call graph alone."
  {:malli/schema [:=> [:cat :seon.host/db [:set :qualified-symbol] [:vector :qualified-symbol] [:maybe :int]] [:vector :any]]}
  [db named sources task]
  (let [pairs (d/q '[:find ?s ?r :in $ [?s ...] :where [?e :var/name ?s] [?e :var/form ?f] [?f :form/refs ?r] [?v :var/name ?r] [?v :var/form _]]
                   db sources)
        macro? #(= :macro (:var/kind (d/entity db [:var/name %])))
        ;; tf-idf: called by more of the sources (k), and by fewer of the program's n forms (a
        ;; defn's contract plumbing is named by every contracted form, so it sinks)
        n (count (d/datoms db :aevt :form/id))
        score (fn [[r k]] (* k (Math/log (/ n (inc (count (d/datoms db :avet :form/refs r)))))))
        ;; each hit's score taken once (sort-by would take it per comparison: an index count each)
        calls (->> pairs (map second) (remove #(or (named %) (macro? %))) frequencies
                   (map (fn [e] [(- (score e)) (str (key e)) (key e)])) sort (mapv peek))
        ;; the forms nearest the plan, as the vars they define, in their order
        similar (when task
                  (into [] (comp (mapcat (fn [[f _]] (map :var/name (:var/_form (d/entity db f)))))
                                 (remove #(or (named %) (macro? %))) (distinct))
                        (vector/near db task (+ (cfg/value :seon.config.render/found-near) (count named)) {:of :form/id})))
        hits (if (seq similar) (rrf [calls similar]) calls)
        [shown more] (print/bounded hits :seon.config.render/found "(my.agent/callees 'ns/f) of each cited var, (my.agent/similar \"text\")")]
    (cond-> (mapv #(resolve-ref db % :doc) shown) more (conj more))))

(defn- summary-of
  "Var sym's first doc sentence: its runtime doc, else the docstring of its recorded form;
  nil when it has none."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-symbol] [:maybe :string]]}
  [db sym]
  (some-> (or (some-> (find-ns (symbol (namespace sym))) ns-interns (get (symbol (name sym))) meta :doc)
              (let [f (binding [*read-eval* false] (read-string (or (source-of db sym) "nil")))]
                (when (and (seq? f) (string? (nth f 2 nil))) (nth f 2))))
          ref/lead-sentence))

(defn ns-map
  "The map of the namespaces syms live in (the proofs and the vars the plan cites): each
  namespace's functions as a line (name, arglists, first doc sentence), most referenced by the
  program first, within an equal share of :seon.config.render/map-tokens, and the form that
  lists them all (root's research, lever 1: aider's ranked map; v1 R4's helper naming)."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :qualified-symbol]] [:vector :map]]}
  [db syms]
  (let [nss (vec (distinct (map (comp symbol namespace) syms)))
        share (quot (cfg/value :seon.config.render/map-tokens) (max 1 (count nss)))
        uses #(count (d/datoms db :avet :form/refs %))
        line (fn [s] (let [m (some-> (find-ns (symbol (namespace s))) ns-interns (get (symbol (name s))) meta)
                           sm (summary-of db s)]
                       (cond-> {:symbol s}
                         (:arglists m) (assoc :arglists (:arglists m))
                         sm (assoc :summary sm))))]
    (vec (for [ns nss
               :let [vs (d/q '[:find [?n ...] :in $ ?ns :where [?e :ns/name ?ns] [?v :var/ns ?e] [?v :var/name ?n] [?v :var/form _]] db ns)
                     ;; each var's uses counted once (sort-by would count per comparison)
                     lines (map line (map peek (sort (map (fn [v] [(- (uses v)) (str v) v]) vs))))
                     shown (reduce (fn [[acc spent] l] (let [s (+ spent (:tokens (print/print-value l {:budget share}) share))]
                                                         (if (<= s share) [(conj acc l) s] (reduced [acc spent]))))
                                   [[] 0] lines)]
               :when (seq vs)]
           {:ns ns :vars (first shown) :all (list 'clojure.repl/dir ns)}))))

(defn- cited-view
  "What citation c asks to see, at its depth: a wiki section from main's sections, else a file of
  the checkout read now (never stored), else a task by its ns/name, else a namespace; a
  citation that reaches nothing says so, as an unresolved name does."
  {:malli/schema [:=> [:cat :seon.host/db :seon.ref/citation] :map]}
  [db {:seon.ref/keys [name path anchor depth]}]
  (let [a (or (some-> name str) (str path (when anchor (str "#" anchor))))]
    (or (some-> (if name
                  (if (qualified-symbol? name) (ref/task-view db name depth) (ref/namespace-view db name depth))
                  (or (when anchor (docs/view db a depth)) (ref/file-view db (cfg/checkout) path anchor depth)))
                (assoc :seon.ref/depth depth))
        {:seon.ref/address a :unresolved true :problem (:unresolved ref/problem-messages)})))

(defn- failure
  "A failing assertion as the agent reads it (the test AI render): the assertion as written
  and what it evaluated to, (= 9 (square 3)) got (= 9 6), never the assertion twice; the
  message only when it says something."
  {:malli/schema [:=> [:cat :map] :map]}
  [r]
  (cond-> {:assert (:expected r) :got (or (:got r) (:actual r))}
    (:message r) (assoc :message (:message r))))

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
           (:var/doc v) (assoc :doc (:var/doc v))))))

;; ---- the last evaluations: folded, each an equal share --------------------------------

(def last-evals-budget
  "Tokens one turn's evaluations spend together, each folded item an equal share: the config's."
  (cfg/value :seon.config.render/evals-budget))

(defn fold-evals
  "items (one turn's evaluations, in order, as (my.turn/evals) holds them) with identical
  form and answer folded into the first: [{:k index-of-the-first :item it :times n}], in
  first-seen order. One pass."
  {:malli/schema [:=> [:cat [:sequential :map]] [:vector [:map [:k :int] [:item :map] [:times pos-int?]]]]}
  [items]
  (let [{:keys [order by]} (reduce (fn [{:keys [order by] :as acc} [k it]]
                                     (let [key [(:form it) (:tag it) (:printed it) (:out it)]]
                                       (if (by key)
                                         (update-in acc [:by key :times] inc)
                                         {:order (conj order key) :by (assoc by key {:k k :item it :times 1})})))
                                   {:order [] :by {}} (map-indexed vector items))]
    (mapv by order)))

(defn- shown-eval
  "One evaluation as the transcript shows it: its form, its live value (else its printed
  answer: a value whose producer named its render shows that render's text, as the REPL
  answered it), its output; an error keeps :tag :err and shows its error map (:err: a
  contract's problems and fix, an exception's class and causes) in place of its message. A
  sub-map of the item, so every cut's path reads the item itself."
  {:malli/schema [:=> [:cat :map] :map]}
  [item]
  (let [rendered? (and (contains? item :value) (render/rendered? (:value item)))]
    (cond-> (select-keys item [:form :tag :value :printed :out :err])
      rendered? (dissoc :value)
      (and (contains? item :value) (not rendered?)) (dissoc :printed)
      (contains? item :err) (dissoc :printed)
      (= :ret (:tag item)) (dissoc :tag))))

(defn shown-evals
  "The last evaluations (folded) as the model reads them: each folded item printed by the
  one printer within an equal share of last-evals-budget, its cuts reading through
  (nth (my.turn/evals) k): [{:times n :text}] (the printer's answer)."
  {:malli/schema [:=> [:cat [:vector :map]] [:vector :map]]}
  [folded]
  (let [share (max 32 (quot last-evals-budget (max 1 (count folded))))]
    (vec (for [{:keys [k item times]} folded
               :let [r (print/print-value (shown-eval item) {:handle (list 'nth '(my.turn/evals) k) :budget share})]]
           (assoc (if (:text r) r {:text (:seon.error/message r)}) :times times)))))

(defn- transcript
  "The shown evaluations as one text, a repeated one headed by how many times it repeated."
  {:malli/schema [:=> [:cat [:vector :map]] :string]}
  [folded]
  (str/join "\n" (for [{:keys [times text]} (shown-evals folded)]
                   (str (when (> times 1) (str ";; " times "x, the same form and answer\n")) text))))

(defn- refused
  "The done rule's reasons as the agent reads them, beyond its own red proofs (shown with
  the proofs): a red test names its failures, a contract gap its var, a design step that
  has not improved its plan the call that does, an open problem its work made true what it
  is (seon.problems/caused)."
  {:malli/schema [:=> [:cat [:vector :seon.reconcile/not-done] [:vector :map]] [:vector :map]]}
  [reasons reds]
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
                                          (assoc :failures (mapv failure (filter #(= symbol (:test %)) reds))))
               :no-design {:reason reason :unchanged :seon.task/plan
                           :do '(my.task/update! :seon.task/plan str "\n\n" "<what the implement step must do>")}
               ;; no result: never written, or never run; what writes and runs it
               :proof-unknown {:reason reason :test symbol
                               :do (list 'do (list 'in-ns (list 'quote (clojure.core/symbol (namespace symbol))))
                                         (list 'clojure.test/deftest (clojure.core/symbol (name symbol)) '(clojure.test/is false "write the assertion: the behaviour, on data"))
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
  "What happened to the agent's last reply: cut at the token limit, and each delimiter the
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

(defn current-state
  "The current-state block of task t as data: proofs with their status, red assertions and
  source; what else the done rule refuses (regressions, stray reds, contract gaps), and a
  test run that failed as a whole; resolved references, each at its citation's depth; the
  helpers the plan does not name (:found); the measured evidence it starts from; the
  branch's unmerged entries (base, ours, theirs); what its merge will carry to main; what
  happened to the last reply; the messages delivered since the last turn (the owner's, root's,
  other agents'); the last evaluations; turns left."
  {:malli/schema [:=> [:cat :seon.host/db :seon.task/task :map] :map]}
  [db t {:keys [results reds last-items turn max-turns unmerged truncated? reasons test-run-errors source repairs will-merge tests messages message-ids attention]}]
  (let [{:keys [plan proofs]} (:seon.task/accumulated t)
        cites (ref/references db (str plan "\n" (:seon.task/instructions t)))
        task? #(and (qualified-symbol? (:seon.ref/name %)) (ref/task? db (:seon.ref/name %)))
        refs (filterv #(and (qualified-symbol? (:seon.ref/name %)) (not (task? %))) cites)
        named (into (set proofs) (map :seon.ref/name) refs)
        helpers (found db named (into (mapv :seon.ref/name refs) (sort-by str proofs)) (some->> (:seon.task/id t) (pdb/eid db :seon.task/id)))
        nsmap (ns-map db (into (vec (sort-by str proofs)) (map :seon.ref/name) refs))
        sections (into [] (comp (filter #(or (simple-symbol? (:seon.ref/name %)) (task? %) (and (:seon.ref/path %) (not (:seon.ref/problem-kind %)))))
                                (map #(cited-view db %)))
                       cites)]
    (cond-> {:task (assoc (select-keys t [:seon.task/id :seon.task/kind :seon.task/instructions])
                          ;; the plan as it accumulated along :needs (prerequisites first)
                          :seon.task/plan plan)
             ;; a passing proof is its status; a red or unrun one shows its source, its failures
             ;; and the program functions it calls; one not defined yet says so
             :proofs (vec (for [p (sort-by str proofs)
                                :let [src (source-of db p)
                                      ;; no result: not written yet, the run failed as a whole, or it asserted nothing
                                      st (get results p (cond (nil? src) :undefined (seq test-run-errors) :not-run :else :no-assertions))
                                      fs (filter #(= p (:test %)) reds)]]
                            (cond-> {:test p :status st}
                              (and src (not= :pass st)) (assoc :source src)
                              (seq fs) (assoc :failures (mapv failure fs))
                              (and src (not= :pass st) (seq (callees db p))) (assoc :calls (callees db p)))))
             ;; a reference already shown as a proof is not shown twice; the names that resolve
             ;; to nothing are one entry, their problem said once
             :references (let [rs (into [] (comp (remove (comp (set proofs) :seon.ref/name))
                                                 (map #(resolve-ref db (:seon.ref/name %) (:seon.ref/depth %))))
                                        refs)
                               {gone true there false} (group-by (comp boolean :unresolved) rs)]
                           (cond-> (vec there)
                             gone (conj {:unresolved (mapv :symbol gone) :problem (:unresolved ref/problem-messages)})))}
      max-turns (assoc :turn (str (inc turn) " of " max-turns))
      (seq sections) (assoc :sections sections)
      (seq helpers) (assoc :found helpers)
      (seq nsmap) (assoc :map nsmap)
      (seq (refused (vec reasons) (vec reds))) (assoc :refused (refused (vec reasons) (vec reds)))
      (seq test-run-errors) (assoc :test-run {:failed (vec test-run-errors)})
      (last-reply truncated? source (vec repairs)) (assoc :your-last-reply (last-reply truncated? source (vec repairs)))
      (:seon.task/evidence t) (assoc :evidence (:seon.task/evidence t))
      (seq unmerged) (assoc :unmerged (mapv #(select-keys % [:key :kind :base :ours :theirs]) unmerged))
      ;; owner D4: every change the branch's merge carries to main, every turn
      ;; measured and empty ({}) says so; absent only when not measured (a basis before D4)
      ;; the sections of the wiki this branch's code changes made stale (seon.problems/caused-split): to read and
      ;; probably fix; never a reason to refuse done
      (seq attention) (assoc :attention attention)
      (some? will-merge) (assoc :will-merge will-merge)
      ;; the messages the turn took: those that are facts read again from db (the commit the turn
      ;; read, where its agent wrote them), the rest as the basis holds them
      (or (seq message-ids) (seq messages))
      (assoc :messages (mapv #(dissoc % :id) (taken-messages db {:message-ids message-ids :messages messages})))
      ;; root's ruling (a): the tests this turn ran (the proofs and the tests the
      ;; branch changed), those the judge runs at the run's end, the process tests run only
      ;; when root asks (DECISIONS, Process tests are not automatic); a run its bound ended leaves
      ;; the rest unknown, never red
      tests (assoc :tests (cond-> tests
                            (:unfinished tests) (assoc :unfinished-run '(my.agent/prove))
                            (pos? (:at-judge tests 0)) (assoc :judge-runs '(my.agent/prove))
                            (pos? (:when-asked tests 0)) (assoc :root-runs '(my.agent/deferred))))
      (seq last-items) (assoc :last-evals (transcript (fold-evals last-items))))))

(defn evaluations-text
  "One turn's evaluations (as (my.turn/evals) holds them) as the model reads them: folded, each
  an equal share of the evals budget. What the turn records (:seon.turn/evals): the later
  turns' scrollback shows it."
  {:malli/schema [:=> [:cat [:sequential :map]] :string]}
  [items] (transcript (fold-evals items)))

(defn- kept-turns
  "The earlier turns the history budget keeps, whole, in order: the newest always, then older
  ones while they fit. A turn costs its reply's and its calls' code and evaluations' tokens
  (the printer's sizer, seon.print.select/estimate)."
  {:malli/schema [:=> [:cat [:vector [:map [:reply :string] [:calls [:vector :map]]]] :int] [:vector :map]]}
  [turns budget]
  (let [cost #(select/estimate (str/join (cons (:reply %) (mapcat (juxt :code :evals) (:calls %)))))
        [newest & older] (rseq turns)]
    (->> older
         (reduce (fn [[kept spent] t] (let [s (+ spent (cost t))] (if (<= s budget) [(conj kept t) s] (reduced [kept spent]))))
                 [[newest] (cost newest)])
         first rseq vec)))

(defn- turn-messages
  "One earlier turn as the conversation holds it: the model's reply with its eval calls (and
  its reasoning, which a thinking provider needs back), then
  each call's answer as a tool message (what the REPL answered, before the model's next call).
  A reply that called nothing is followed by a user message saying so (last? false), so two
  replies never meet."
  {:malli/schema [:=> [:cat [:map [:reply :string] [:calls [:vector :map]]] :boolean] [:vector :seon.model/message]]}
  [{:keys [reply calls reasoning]} last?]
  (into [(cond-> {:role "assistant" :content reply}
           reasoning (assoc :reasoning reasoning)
           (seq calls) (assoc :tool-calls (mapv #(select-keys % [:id :code :thinking]) calls)))]
        (if (seq calls)
          (map (fn [{:keys [id evals]}] {:role "tool" :tool-call-id id :content evals}) calls)
          (when-not last? [{:role "user" :content "Nothing ran: the reply called no eval."}]))))

(def run-keys
  "The block's run sections: what the task is and what it cites, the same each turn of an
  run (made from its first turn's basis), so they are sent once, in the cached prefix."
  [:task :references :found :map :sections :evidence])

(defn- run-message
  "The run sections of block, printed by the one printer under the context's budget."
  {:malli/schema [:=> [:cat :map] :string]}
  [block]
  (str "Your task and what it cites, the same for this whole run:\n"
       (print/text (select-keys block run-keys) (:context print/budgets))))

(defn- turn-message
  "The turn sections of block (everything but the run's), printed under the context's
  budget, then the last evaluations while no earlier turn shows them, then how many earlier
  turns the history budget left out."
  {:malli/schema [:=> [:cat :map :boolean :int] :string]}
  [block scrollback? dropped]
  (cond-> (str "The current state:\n" (print/text (apply dissoc block :last-evals run-keys) (:context print/budgets)))
    (and (not scrollback?) (:last-evals block)) (str "\n\nYour last evaluations:\n" (:last-evals block))
    (pos? dropped) (str "\n\n;; " dropped " earlier turns are left out here (the history budget); (my.turn/evals) holds the last turn's")))

(defn messages
  "The model request's messages, its unchanging part first so a provider caches it: help, the
  help page (the same for every agent); the run sections, made from block when no turns
  came before, else the first turn's (:run, which with turns it must carry: the prefix
  is never rebuilt from today's block); the conversation so far, as a REPL keeps its
  scrollback (each earlier turn's reply, its eval calls and each call's answer as a tool
  message, whole turns within the history budget, the newest kept); then the turn sections
  of block, each cut naming the form that reads its rest. turns: [{:run :reply :calls}]
  of this run, oldest first."
  {:malli/schema [:function [:=> [:cat :string :map] [:vector :seon.model/message]]
                  [:=> [:cat :string :map [:schema [:cat [:map [:run :string]] [:* :map]]]] [:vector :seon.model/message]]]}
  ([help block]
   [{:role "system" :content help} {:role "user" :content (run-message block)}
    {:role "user" :content (turn-message block false 0)}])
  ([help block turns]
   (let [kept (kept-turns (vec turns) (cfg/value :seon.config.render/history-budget))]
     (-> [{:role "system" :content help} {:role "user" :content (:run (first turns))}]
         (into (mapcat (fn [[t nxt]] (turn-messages t (nil? nxt))) (partition-all 2 1 kept)))
         (conj {:role "user" :content (turn-message block true (- (count turns) (count kept)))})))))

(defn reader-text
  "What a turn's reader got beyond the help page and the scrollback: its run sections and
  its turn sections, as one text (the server's context, the page's panel)."
  {:malli/schema [:=> [:cat [:vector :seon.model/message]] :string]}
  [msgs] (str (:content (second msgs)) "\n\n" (:content (peek msgs))))

;; ---- a turn's basis: what its context was built from, and the text made from it again ----

(defn- turn-record
  "The turn entity e on db, its facts."
  {:malli/schema [:=> [:cat :seon.host/db :int] :map]}
  [db e]
  (d/pull db [:db/id :seon.turn/id :seon.turn/n :seon.turn/origin :seon.turn/basis :seon.turn/reply :seon.turn/calls :seon.turn/truncated :seon.turn/reasoning] e))

(defn- model-turns
  "The model turns of run on db before turn n, oldest first."
  {:malli/schema [:=> [:cat :seon.host/db :seon.run/id :int] [:vector :map]]}
  [db run n]
  (->> (d/q '[:find ?e ?k :in $ ?a ?n :where [?e :seon.turn/run ?a] [?e :seon.turn/origin :model] [?e :seon.turn/n ?k] [(< ?k ?n)]]
            db run n)
       (sort-by second) (mapv #(turn-record db (first %)))))

(declare render)

(defn remade
  "The request turn entity e on db read, made again from its basis on the commit it read
  (render): {:basis :block :messages}."
  {:malli/schema [:=> [:cat :seon.host/db :int] [:map [:basis :seon.turn/basis] [:block :map] [:messages [:vector :seon.model/message]]]]}
  [db e]
  (let [b (:seon.turn/basis (turn-record db e))]
    (assoc (render (d/commit-as-db db (:commit b)) b) :basis b)))

(defn turn-text
  "What the reader of turn entity e on db got (reader-text), made again from its basis on the
  commit it read."
  {:malli/schema [:=> [:cat :seon.host/db :int] :string]}
  [db e] (reader-text (:messages (remade db e))))

(defn sections
  "Where a request's tokens went, as the printer sizes them (seon.print.select/estimate), in
  the order it sends them: the help page, the run sections, the scrollback (its earlier
  turns), the turn sections; the run and turn sections each with the tokens of each key
  of block printed alone. [{:part :tokens :keys [[k tokens]]}]."
  {:malli/schema [:=> [:cat [:map [:block :map] [:messages [:vector :seon.model/message]]]]
                  [:vector [:map [:part :string] [:tokens nat-int?] [:keys {:optional true} [:vector [:tuple :keyword nat-int?]]]]]]}
  [{:keys [block messages]}]
  (let [est #(select/estimate (:content %))
        scroll (subvec messages 2 (dec (count messages)))
        each (fn [ks] (vec (for [k ks :when (contains? block k)]
                             [k (select/estimate (print/text {k (get block k)} (:context print/budgets)))])))]
    [{:part "help page" :tokens (est (first messages))}
     {:part "run sections" :tokens (est (second messages)) :keys (each run-keys)}
     {:part (str "scrollback, " (count (filter #(= "assistant" (:role %)) scroll)) " earlier turns") :tokens (reduce + 0 (map est scroll))}
     {:part "turn sections" :tokens (est (peek messages)) :keys (each (remove (set run-keys) (keys block)))}]))

(defn request-text
  "The whole request messages, as one text: each message headed by its role (and a tool
  answer by its call's id, an assistant's eval calls after its reply). What the agent's page
  links as a turn's whole context."
  {:malli/schema [:=> [:cat [:vector :seon.model/message]] :string]}
  [messages]
  (str/join "\n\n" (for [{:keys [role content tool-calls tool-call-id]} messages]
                     (str ";; ---- " role (when tool-call-id (str " · answer to " tool-call-id)) "\n" content
                          (str/join (for [{:keys [id code]} tool-calls] (str "\n;; eval call " id "\n" code)))))))

(defn- run-text
  "The run sections the first model turn e on db was sent, made again from its basis."
  {:malli/schema [:=> [:cat :seon.host/db :int] :string]}
  [db e]
  (let [b (:seon.turn/basis (turn-record db e))]
    (:content (second (:messages (render (d/commit-as-db db (:commit b)) b))))))

(defn render
  "What a turn's reader got, from its basis b (seon.turn records it, :seon.turn/basis) on db,
  the commit b names: the current-state block of its task with the observations b holds, and
  the messages, its run's earlier model turns as the scrollback (their replies, eval calls
  and each call's evaluations as recorded, the run sections the first one's, made again from
  its own basis). The one
  maker of a context: the model's request is this, and so is every later reading of it.
  {:block :messages}."
  {:malli/schema [:=> [:cat :seon.host/db :seon.turn/basis] [:map [:block :map] [:messages [:vector :seon.model/message]]]]}
  [db {:keys [task run turn max-turns origin observed merge? last-evals]}]
  (let [prev (if (= :model origin) (model-turns db run turn) [])
        lst (peek prev)
        {:keys [text repairs]} (when lst (reader/forgive (str/join "\n" (map :code (:seon.turn/calls lst)))))
        ;; an outside agent's reads are not model turns: they have no turn count
        block (cond-> (current-state db (task/task db task)
                                     (cond-> (assoc observed :truncated? (:seon.turn/truncated lst)
                                                    :source text :repairs (vec repairs)
                                                    :unmerged (when merge? (pm/unmerged db)))
                                       (= :model origin) (assoc :turn turn :max-turns max-turns)))
                last-evals (assoc :last-evals last-evals))
        turns (vec (map-indexed (fn [i r] (cond-> {:reply (:seon.turn/reply r) :calls (vec (:seon.turn/calls r))}
                                             (:seon.turn/reasoning r) (assoc :reasoning (:seon.turn/reasoning r))
                                             (zero? i) (assoc :run (run-text db (:db/id r)))))
                                prev))]
    {:block block :messages (if (seq turns) (messages (help-text db) block turns) (messages (help-text db) block))}))

(defn recorded
  "The last context run's reader got, made again from its turn's basis on db (seon.turn:
  a model turn's user message, or an outside read through the server): {:text :origin
  :turn}, or nil. The page shows it; nothing stores the text."
  {:malli/schema [:=> [:cat :seon.host/db :string] [:maybe [:map [:text :string] [:origin :keyword] [:turn :string]]]]}
  [db run]
  (when-let [e (last (sort (d/q '[:find [?e ...] :in $ ?a :where [?e :seon.turn/run ?a] [?e :seon.turn/basis _]] db run)))]
    (let [r (turn-record db e)]
      {:text (turn-text db e) :origin (:seon.turn/origin r) :turn (:seon.turn/id r)})))
