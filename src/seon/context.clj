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
    (cond-> (mapv #(docs/resolve-ref db % :doc) shown) more (conj more))))

(defn- summary-of
  "Var sym's first doc sentence: its runtime doc, else the docstring of its recorded form;
  nil when it has none."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-symbol] [:maybe :string]]}
  [db sym]
  (some-> (or (some-> (find-ns (symbol (namespace sym))) ns-interns (get (symbol (name sym))) meta :doc)
              (let [f (binding [*read-eval* false] (read-string (or (docs/source-of db sym) "nil")))]
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

(defn run-state
  "The run sections of task t's current state as data, the same each turn of a run: the
  task (its plan as it accumulated along :needs, prerequisites first), its cited vars resolved
  each at its citation's depth, the helpers the plan does not name (:found), the namespaces'
  map, the wiki sections it cites, and the measured evidence it starts from."
  {:malli/schema [:=> [:cat :seon.host/db :seon.task/task] :map]}
  [db t]
  (let [{:keys [plan proofs]} (:seon.task/accumulated t)
        cites (ref/references db (str plan "\n" (:seon.task/instructions t)))
        refs (filterv #(= :var (docs/citation-kind db %)) cites)
        named (into (set proofs) (map :seon.ref/name) refs)
        helpers (found db named (into (mapv :seon.ref/name refs) (sort-by str proofs)) (some->> (:seon.task/id t) (pdb/eid db :seon.task/id)))
        nsmap (ns-map db (into (vec (sort-by str proofs)) (map :seon.ref/name) refs))
        sections (into [] (comp (filter #(= :view (docs/citation-kind db %))) (map #(docs/cited-view db %)))
                       cites)]
    (cond-> {:task (assoc (select-keys t [:seon.task/id :seon.task/kind :seon.task/instructions])
                          :seon.task/plan plan)
             ;; a reference already shown as a proof is not shown twice; the names that resolve
             ;; to nothing are one entry, their problem said once
             :references (let [rs (into [] (comp (remove (comp (set proofs) :seon.ref/name))
                                                 (map #(docs/resolve-ref db (:seon.ref/name %) (:seon.ref/depth %))))
                                        refs)
                               {gone true there false} (group-by (comp boolean :unresolved) rs)]
                           (cond-> (vec there)
                             gone (conj {:unresolved (mapv :symbol gone) :problem (:unresolved ref/problem-messages)})))}
      (seq sections) (assoc :sections sections)
      (seq helpers) (assoc :found helpers)
      (seq nsmap) (assoc :map nsmap)
      (:seon.task/evidence t) (assoc :evidence (:seon.task/evidence t)))))

(defn turn-state
  "The turn sections of task t's current state as data, measured again each turn from
  observed: proofs with their status, red assertions, source and calls; what else the done
  rule refuses (regressions, stray reds, contract gaps), and a test run that failed as a whole;
  the branch's unmerged entries (base, ours, theirs); what its merge will carry to main; the
  wiki sections its changes made stale; the tests run; what happened to the last reply; turns
  left. The messages delivered are the conversation's (`conversation`), not the block's."
  {:malli/schema [:=> [:cat :seon.host/db :seon.task/task :map] :map]}
  [db t {:keys [results reds turn max-turns unmerged truncated? reasons test-run-errors source repairs will-merge tests attention]}]
  (let [{:keys [proofs]} (:seon.task/accumulated t)]
    (cond-> {;; a passing proof is its status; a red or unrun one shows its source, its failures
             ;; and the program functions it calls; one not defined yet says so
             :proofs (vec (for [p (sort-by str proofs)
                                :let [src (docs/source-of db p)
                                      ;; no result: not written yet, the run failed as a whole, or it asserted nothing
                                      st (get results p (cond (nil? src) :undefined (seq test-run-errors) :not-run :else :no-assertions))
                                      fs (filter #(= p (:test %)) reds)]]
                            (cond-> {:test p :status st}
                              (and src (not= :pass st)) (assoc :source src)
                              (seq fs) (assoc :failures (mapv failure fs))
                              (and src (not= :pass st) (seq (callees db p))) (assoc :calls (callees db p)))))}
      max-turns (assoc :turn (str (inc turn) " of " max-turns))
      (seq (refused (vec reasons) (vec reds))) (assoc :refused (refused (vec reasons) (vec reds)))
      (seq test-run-errors) (assoc :test-run {:failed (vec test-run-errors)})
      (last-reply truncated? source (vec repairs)) (assoc :your-last-reply (last-reply truncated? source (vec repairs)))
      (seq unmerged) (assoc :unmerged (mapv #(select-keys % [:key :kind :base :ours :theirs]) unmerged))
      ;; the sections of the wiki this branch's code changes made stale (seon.problems/caused-split): to read and
      ;; probably fix; never a reason to refuse done
      (seq attention) (assoc :attention attention)
      ;; owner D4: every change the branch's merge carries to main, every turn measured and
      ;; empty ({}) says so; absent only when not measured (a basis before D4)
      (some? will-merge) (assoc :will-merge will-merge)
      ;; root's ruling (a): the tests this turn ran (the proofs and the tests the
      ;; branch changed), those the judge runs at the run's end, the process tests run only
      ;; when root asks (DECISIONS, Process tests are not automatic); a run its bound ended leaves
      ;; the rest unknown, never red
      tests (assoc :tests (cond-> tests
                            (:unfinished tests) (assoc :unfinished-run '(my.agent/prove))
                            (pos? (:at-judge tests 0)) (assoc :judge-runs '(my.agent/prove))
                            (pos? (:when-asked tests 0)) (assoc :root-runs '(my.agent/deferred)))))))

(defn current-state
  "The current-state block of task t as data: its run sections (`run-state`) and its turn
  sections from observed (`turn-state`)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.task/task :map] :map]}
  [db t observed]
  (merge (run-state db t) (turn-state db t observed)))

(defn evaluations-text
  "Responses (as (my.turn/evals) holds them) as one text: each as every reader reads it (its
  :text, seon.repl/printed)."
  {:malli/schema [:=> [:cat [:sequential :map]] :string]}
  [rs] (str/join "\n\n" (keep :text rs)))

(defn eval-messages
  "An eval's part of the conversation (the :seon.render/ai render of :seon.db/eval-fact) as
  provider-neutral messages: a model's eval (its record names the provider's call id) is its
  assistant message, the reply's prose and reasoning as returned with the eval call (:call, the
  call's place in its reply: the provider's format folds a reply's calls into one message),
  then its tool message, the response as it was printed; anyone else's (the user's, the
  screen's) is one user message {:by :code :response}, the response spliced as printed."
  {:malli/schema [:=> [:cat :map :any] [:vector :seon.model/message]]}
  [{:seon.eval/keys [by record]} _]
  (let [{:keys [code text prose content call-prose reasoning call-id call]} record]
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
  "A message's part of the conversation (the :seon.render/ai render of :seon.message/fact): one
  user message {:from :body}."
  {:malli/schema [:=> [:cat :map :any] [:vector :seon.model/message]]}
  [{:seon.message/keys [from body]} _]
  [{:role "user" :content (print/text {:from from :body body} (:context print/budgets))}])

(defn- ai-messages
  "Record v's part of the conversation: its schema k's declared :seon.render/ai render (seon.render/render-of)."
  {:malli/schema [:=> [:cat :map :qualified-keyword] [:vector :seon.model/message]]}
  [v k] ((render/render-of v :ai k) v {}))

(defn- kept
  "The newest parts (each a record's messages, oldest first) the history budget keeps, whole:
  the newest always, then older ones while they fit (the printer's sizer), never starting
  inside a reply (a part continuing the call before it). Answers [kept dropped]."
  {:malli/schema [:=> [:cat [:vector [:vector :seon.model/message]] :int] [:tuple [:vector [:vector :seon.model/message]] nat-int?]]}
  [parts budget]
  (let [cost (fn [ms] (select/estimate (str/join (mapcat (fn [m] (cons (:content m) (map :code (:tool-calls m)))) ms))))
        [newest & older] (rseq parts)
        ks (if-not newest
             []
             (->> older
                  (reduce (fn [[kept spent] p] (let [s (+ spent (cost p))] (if (<= s budget) [(conj kept p) s] (reduced [kept spent]))))
                          [[newest] (cost newest)])
                  first rseq vec))
        ks (vec (drop-while #(pos? (:call (first %) 0)) ks))]
    [ks (- (count parts) (count ks))]))

(defn records
  "What run's conversation is made of on db, in tx order: agent a's evals of the run (not the
  screen's or a context read's: those end only the request that ran them) and the messages the
  run took (written on its branch by the turn that took each), each [tx value schema-key]."
  {:malli/schema [:=> [:cat :seon.host/db :int :seon.run/id] [:vector [:tuple :int :map :qualified-keyword]]]}
  [db a run]
  (->> (concat (for [[e tx] (d/q '[:find ?e ?tx :in $ ?a :where [?e :seon.eval/agent ?a ?tx]] db a)
                     :let [v (d/pull db [:seon.eval/id :seon.eval/by :seon.eval/record] e)]
                     :when (and (= run (:run (:seon.eval/record v))) (not (#{"screen" "context"} (:seon.eval/by v))))]
                 [tx v :seon.db/eval-fact])
               (for [[e tx] (d/q '[:find ?m ?tx :in $ ?r :where [?ru :seon.run/id ?r] [?m :seon.message/to ?ru ?tx]] db run)]
                 [tx (d/pull db [:seon.message/id :seon.message/from :seon.message/body] e) :seon.message/fact]))
       (sort-by (fn [[tx v]] [tx (:seon.eval/id v (:seon.message/id v))]))
       vec))

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

(defn- screen-message
  "The screen: the turn sections of block (everything but the run's), printed under the
  context's budget, with how many earlier turns the history budget left out. Sent last, and
  replaced by the next request's."
  {:malli/schema [:=> [:cat :map :int] :string]}
  [block dropped]
  (str "The current state:\n" (print/text (cond-> (apply dissoc block :last-evals run-keys)
                                             (pos? dropped) (assoc :history {:left-out-turns dropped}))
                                           (:context print/budgets))
       (when (:last-evals block) (str "\n\nYour last evaluations:\n" (:last-evals block)))))

(defn request
  "The agent's request, a render of its record: agent a (its entity map, :db/id) for the turn
  basis b on db (opts :db :basis :block :help :run, and :help-ref :run-ref, the records those
  two texts render): the system prompt (the help page), the task story (opts :run, the run
  sections as its first turn was sent them, else block's), the conversation (each record of the
  run, `records`, by its own :seon.render/ai render, in tx order, whole records within the
  history budget, the newest kept), then the screen: the screen evals b names, each by its
  render, replaced each request, with how many earlier records the budget left out; with none
  (an outside agent's read), the block's turn sections (`screen-message`). Answers {:messages
  :sent}: provider-neutral messages (the provider's format function makes the wire shape,
  seon.model/wire-messages) and the records they render, in order (:seon.turn/sent, `sent`):
  [:section eid t], the help page's own section as of t; [:task eid t], the task as of its
  run's first turn; [:eval id]; [:message id]; [:history n], the n records the budget left out.
  :sent is nil for a read with no screen evals (an outside agent's): its last message is the
  block's turn sections, no record; every model turn has screen evals.
  Called by the turn, not declared as the agent fact's :seon.render/ai until the render cache
  makes it once per change (design section 10, step 4)."
  {:malli/schema [:=> [:cat [:maybe :map] :map] [:map [:messages [:vector :seon.model/message]] [:sent [:maybe [:vector :any]]]]]}
  [a {:keys [db basis block help run help-ref run-ref]}]
  (let [rs (if (and a (= :model (:origin basis))) (records db (:db/id a) (:run basis)) [])
        parts (mapv (fn [[_ v k]] (ai-messages v k)) rs)
        [ks dropped] (kept parts (cfg/value :seon.config.render/history-budget))
        kept-refs (mapv (fn [[_ v]] (if (:seon.eval/id v) [:eval (:seon.eval/id v)] [:message (:seon.message/id v)]))
                        (take-last (count ks) rs))
        screen (when-let [ids (seq (:screen basis))]
                 (vec (mapcat #(ai-messages (d/pull db [:seon.eval/id :seon.eval/by :seon.eval/record] [:seon.eval/id %]) :seon.db/eval-fact) ids)))
        note (when (and screen (pos? dropped)) {:role "user" :content (pr-str {:history {:left-out dropped}})})]
    {:messages (-> [{:role "system" :content help} {:role "user" :content (or run (run-message block))}]
                   (into cat ks)
                   (into (if screen (cond-> screen note (conj note)) [{:role "user" :content (screen-message block dropped)}])))
     :sent (when (and screen help-ref run-ref)
             (-> [help-ref run-ref] (into kept-refs) (into (map (fn [id] [:eval id])) (:screen basis))
                 (cond-> note (conj [:history dropped]))))}))

(defn messages
  "A request's messages without a record (a test's, a read before any eval): the help page, the
  run sections of block and its turn sections, as `request` closes a read with no screen evals."
  {:malli/schema [:=> [:cat :string :map] [:vector :seon.model/message]]}
  [help block] (:messages (request nil {:block block :help help :basis {}})))

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
  "The model turns of run on db before turn n, oldest first, each with the tx that recorded it (:tx)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.run/id :int] [:vector :map]]}
  [db run n]
  (->> (d/q '[:find ?e ?k ?tx :in $ ?a ?n :where [?r :seon.run/id ?a] [?e :seon.turn/run ?r] [?e :seon.turn/origin :model ?tx] [?e :seon.turn/n ?k] [(< ?k ?n)]]
            db run n)
       (sort-by second) (mapv (fn [[e _ tx]] (assoc (turn-record db e) :tx tx)))))

(declare render story)

(defn sent-parts
  "What turn entity e on db sent, from the records its :seon.turn/sent names: each ref beside its
  messages, each record by its own render: the help page's section as of its t (help-text), the
  task's run sections as of its t (story), an eval or message by its :seon.render/ai render, the
  history note by its count. No request is built again: the same records, rendered. nil for a
  turn that recorded none."
  {:malli/schema [:=> [:cat :seon.host/db :int] [:maybe [:vector [:tuple :any [:vector :seon.model/message]]]]]}
  [db e]
  (when-let [sent (:seon.turn/sent (d/pull db [:seon.turn/sent] e))]
    (mapv (fn [[kind x t :as ref]]
            [ref (case kind
                   :section [{:role "system" :content (help-text (d/as-of db t))}]
                   :task [{:role "user" :content (story db x t)}]
                   :eval (ai-messages (d/pull db [:seon.eval/id :seon.eval/by :seon.eval/record] [:seon.eval/id x]) :seon.db/eval-fact)
                   :message (ai-messages (d/pull db [:seon.message/id :seon.message/from :seon.message/body] [:seon.message/id x]) :seon.message/fact)
                   :history [{:role "user" :content (pr-str {:history {:left-out x}})}])])
          sent)))

(defn sent-messages
  "The messages turn entity e on db sent (sent-parts, in order), nil for a turn that recorded none."
  {:malli/schema [:=> [:cat :seon.host/db :int] [:maybe [:vector :seon.model/message]]]}
  [db e] (some->> (sent-parts db e) (into [] (mapcat second))))

(defn turn-text
  "What the reader of turn entity e on db got (reader-text), from the records it sent (sent-messages)."
  {:malli/schema [:=> [:cat :seon.host/db :int] [:maybe :string]]}
  [db e] (some-> (sent-messages db e) reader-text))

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

(defn- story
  "The task story: task entity e's run sections as of t on db (run-state, printed by
  run-message), what every turn of a run sends after the help page, as its first turn read them."
  {:malli/schema [:=> [:cat :seon.host/db :int :int] :string]}
  [db e t]
  (let [h (d/as-of db t)]
    (run-message (run-state h (task/task h (:seon.task/id (d/pull h [:seon.task/id] e)))))))

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
  the commit b names: the current-state block of its task with the observations b holds (with
  screen evals, the run's sections only: the turn's are theirs), and the messages, the agent's
  request (`request`, its :seon.render/ai render; the run sections the first turn's, made again
  from its own basis). The one maker of a context: the model's request is this, and so is
  every later reading of it. {:block :messages}."
  {:malli/schema [:=> [:cat :seon.host/db :seon.turn/basis] [:map [:block :map] [:messages [:vector :seon.model/message]] [:sent [:maybe [:vector :any]]]]]}
  [db {:keys [task run turn origin last-evals screen agent] :as basis}]
  (let [prev (if (= :model origin) (model-turns db run turn) [])
        t (task/task db task)
        block (cond-> (if screen (run-state db t) (current-state db t (turn-input db basis (peek prev))))
                last-evals (assoc :last-evals last-evals))
        a (when agent (d/pull db [:db/id :seon.agent/key] [:seon.agent/key agent]))
        ;; the task story is its run's first turn's: the task as of that turn's t
        story-t (if (seq prev) (:max-tx (d/commit-as-db db (:commit (:seon.turn/basis (first prev))))) (:max-tx db))
        task-e (pdb/eid db :seon.task/id task)
        r (request a {:db db :basis basis :block block :help (help-text db)
                      :run (when (seq prev) (story db task-e story-t))
                      :help-ref [:section (docs/page-top db help-page) (:max-tx db)]
                      :run-ref [:task task-e story-t]})]
    {:block block :messages (:messages r) :sent (:sent r)}))
