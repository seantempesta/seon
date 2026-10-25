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
                                          (assoc :failures (mapv failure (filter #(= symbol (:test %)) reds)))
                                          ;; a red test the agent wrote is a proof it has not registered, or one to delete
                                          (= :stray-red reason)
                                          (assoc :do (list 'my.task/update! :seon.task/proofs 'conj (list 'quote symbol))))
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

(defn- not-written
  "What proof p should assert, by the plan's own words: its sentences that name it, else the
  plan whole."
  {:malli/schema [:=> [:cat :qualified-symbol :string] :string]}
  [p plan]
  (let [ss (str/split plan #"(?<=[.!?])\s+")]
    (or (not-empty (str/join " " (filter #(str/includes? % (name p)) ss))) plan)))

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
        function? #(= :fn (:var/kind (d/entity db [:var/name %])))
        ;; every vector, every dim, every rank: the exact ranking (near's shortlist and fine level
        ;; over all of them; it sorts them all anyway), walked until k functions are found, as the
        ;; nearest forms are often tests
        all Long/MAX_VALUE]
    (cond
      (zero? k) nil
      (nil? (some->> te (vector/vector-of db))) {:not-embedded "this plan has no vector yet: (my.agent/similar \"text\") searches by any text"}
      :else {:nearest (into [] (comp (mapcat (fn [[f _]] (map :var/name (:var/_form (d/entity db f)))))
                                     (remove named) (filter function?) (distinct) (take k)
                                     (map (fn [s] (str s (some->> (:var/doc (d/entity db [:var/name s])) ref/lead-sentence (str ": "))))))
                            (vector/near db te all {:of :form/id :shortlist all :fine-dims all}))
             :more "(my.agent/similar 'ns/f) or (my.agent/similar \"text\"): more by meaning"})))

(defn- related-count
  "How many of each kind task entity te's screen shows: :seon.config.render/related through the
  one resolver (its task, its model, the config), as `similar` reads its count."
  {:malli/schema [:=> [:cat :seon.host/db :int] nat-int?]}
  [db te]
  (let [t (d/pull db [:seon.task/model] te)]
    (cfg/value :seon.config.render/related {:task t :model (:seon.task/model t)})))

(defn- by-frequency
  "xs best first by how often each occurs, the name breaking a tie."
  {:malli/schema [:=> [:cat [:sequential :any]] [:vector :any]]}
  [xs] (mapv peek (sort (map (fn [[x n]] [(- n) (str x) x]) (frequencies xs)))))

(defn related
  "What the work on task entity te's plan is likely to need beyond what the plan cites: the
  :seon.config.render/related wiki sections, program functions and schema keys nearest it, each
  kind ranked by the plan's references and by meaning fused by reciprocal rank (seon.vector/rrf):
  the graph from its citations (the cited vars' callers and callees, the sections naming a cited
  var, the sections a cited section references or is referenced by, the schema keys the plan
  names and the cited vars' forms name, :form/keywords) and seon.vector/near on the plan's vector. Fused, they found more of
  what 31 merged tasks changed than either alone (docs/vector.md#what-the-work-is-likely-to-need-as-an-eval: functions
  0.51 at 5 against 0.38 by meaning alone, sections 0.71 against 0.67, schema keys 0.56 against
  0.37). Answers {:sections [address] :functions [sym] :schemas [key]}, those the plan cites left
  out (the story shows them); nil when the count is 0 (none shown) or the plan has no vector yet."
  {:malli/schema [:=> [:cat :seon.host/db :int]
                  [:maybe [:map [:sections [:vector :string]] [:functions [:vector :qualified-symbol]] [:schemas [:vector :qualified-keyword]]]]]}
  [db te]
  (let [k (related-count db te)]
    (when (and (pos? k) (vector/vector-of db te))
      (let [{:keys [plan]} (:seon.task/accumulated (task/task db (:seon.task/id (d/pull db [:seon.task/id] te))))
            cites (ref/references db plan)
            vars (filterv #(= :var (docs/citation-kind db %)) cites)
            fns (into #{} (keep :seon.ref/name) vars)
            ;; a wiki page's section only: a cited file is read whole by its view
            secs (into #{} (comp (filter #(and (some-> (:seon.ref/path %) (str/ends-with? ".md")) (= :view (docs/citation-kind db %))))
                                 (keep #(:seon.ref/address (docs/cited-view db %))))
                       cites)
            schema? #(some? (pdb/eid db :schema/key %))
            ;; a program function: no test, no macro (every contracted form names seon.contract/defn),
            ;; nothing of a test namespace (its fixtures); the graph and the meaning halves alike
            function? #(and (not (#{:test :macro} (:var/kind (d/entity db [:var/name %]))))
                            (not (str/ends-with? (namespace %) "-test")))
            ;; a neighbour weighs 1/(1 + the forms naming it, counted to a cap): what every form names
            ;; (seon.contract/checked, on?, seon.db/eid) weighs nothing (docs/vector.md#fused-with-the-call-graph)
            cap (cfg/value :seon.config.render/related-rarity)
            by-rarity (fn [xs] (mapv peek (sort (for [[x n] (frequencies xs)]
                                                  [(- (/ n (inc (bounded-count cap (d/datoms db :avet :form/refs x))))) (str x) x]))))
            near (fn [of f] (into [] (comp (keep (comp f first)) (distinct)) (vector/near db te (* 4 k) {:of of})))
            graph {:functions (by-rarity (concat (mapcat #(docs/callers db %) fns) (mapcat #(map :var (callees db %)) fns)
                                                    (for [a secs n (:seon.section/names (d/entity db [:seon.section/address a]))] n)))
                   :sections (by-frequency (concat (for [n fns e (d/q '[:find [?e ...] :in $ ?n :where [?e :seon.section/names ?n]] db n)]
                                                     (:seon.section/address (d/entity db e)))
                                                   (for [a secs r (:seon.section/references (d/entity db [:seon.section/address a]))] (:seon.section/address r))
                                                   (for [a secs r (:seon.section/_references (d/entity db [:seon.section/address a]))] (:seon.section/address r))))
                   :schemas (by-frequency (filter schema? (concat (filter keyword? (keep :seon.ref/name cites))
                                                                     (mapcat #(:form/keywords (:var/form (d/entity db [:var/name %]))) fns))))}
            meaning {:functions (near :form/id #(some-> (d/entity db %) :var/_form first :var/name))
                     :sections (near :seon.section/address #(:seon.section/address (d/entity db %)))
                     :schemas (near :schema/key #(:schema/key (d/entity db %)))}
            cited {:functions fns :sections secs :schemas #{}}]
        (into {} (for [kind [:sections :functions :schemas]]
                   [kind (into [] (comp (remove (cited kind)) (remove nil?) (filter (if (= kind :functions) function? any?)) (take k))
                               (vector/rrf [(graph kind) (meaning kind)]))]))))))

(defn related-at
  "`related` for task entity te on db, as {:key :value}: held (an earlier answer) reused when its
  :key is the same: te, the count shown and mark, a value that changes whenever the vectors
  `related` reads do (an agent's: the transaction of its branch's last vector write, nil before
  one; every text it reads, the plan, the plans along :needs and the sections, is embedded by
  that write when it changes). So an unchanged plan computes nothing."
  {:malli/schema [:=> [:cat :seon.host/db :int :any [:maybe :map]] [:map [:key :any] [:value [:maybe :map]]]]}
  [db te mark held]
  (let [k [te (related-count db te) mark]]
    (if (= k (:key held)) held {:key k :value (related db te)})))

(defn related-view
  "What `related` answers, each a line at summary depth: a section its address and its lead's
  first sentence (seon.docs/view; its heading when it has no lead), a function its name and its
  doc's first sentence, beside the registry keys it operates on when it names any ([line keys],
  its :form/keywords), a schema key itself; with the forms that read one whole (naming the
  text's key: a step-6 run's model read the view with (:text ...) eight times over) and search
  further: what my.task/related and my.agent/related answer. nil when there is nothing."
  {:malli/schema [:=> [:cat :seon.host/db [:map [:sections {:optional true} [:vector :string]] [:functions {:optional true} [:vector :qualified-symbol]]
                                           [:schemas {:optional true} [:vector :qualified-keyword]]]]
                  [:maybe [:map [:sections {:optional true} [:vector :string]]
                           [:functions {:optional true} [:vector [:or :string [:tuple :string [:vector :qualified-keyword]]]]]
                           [:schemas {:optional true} [:vector :qualified-keyword]] [:read :string] [:more :string]]]]}
  [db {:keys [sections functions schemas]}]
  (when (or (seq sections) (seq functions) (seq schemas))
   (cond-> {}
    (seq sections) (assoc :sections (mapv #(let [v (docs/view db % :summary)] (str % ": " (or (:seon.ref/summary v) (:seon.ref/heading v)))) sections))
    ;; with each function the registry keys its code and contract name (its :form/keywords): the
    ;; data it operates on, which earned their tokens (docs/vector.md#what-the-work-is-likely-to-need-as-an-eval)
    (seq functions) (assoc :functions (mapv #(let [v (d/entity db [:var/name %])
                                                   ks (sort-by str (filter (fn [k] (pdb/eid db :schema/key k)) (:form/keywords (:var/form v))))]
                                               ;; its keys a vector beside its line, so the one printer cuts a long one
                                               (cond-> (str % (some->> (:var/doc v) ref/lead-sentence (str ": ")))
                                                 (seq ks) (vector (vec ks))))
                                            functions))
    (seq schemas) (assoc :schemas schemas)
    true (assoc :read "(:seon.ref/text (my.agent/help \"docs/page.md#anchor\")): one section's text whole; (source ns/f) a function; (my.schema/forms :ns/key) a schema's form"
                :more "(my.agent/related \"your words\" 10): sections by any text; (my.agent/similar \"your words\"): functions and tasks too; (my.agent/callers 'ns/f) or (my.agent/callers :ns/key): who calls it, who operates on it"))))

(defn run-state
  "The task story: task t's run sections as data, the same each turn of a run: the task (its
  plan as it accumulated along :needs, prerequisites first); its write set, each var the plan
  cites with its source and its callers (docs/callers), the names that resolve to nothing said
  once; each proof to the form that runs it at the REPL, or, when it is not written, that it
  is not and what it should assert in the plan's words (whether it fails, and why, is the
  screen's: it changes each turn); the wiki sections it cites; the functions nearest its plan
  by meaning that it does not name (`similar`)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.task/task] :map]}
  [db t]
  (let [{:keys [plan proofs]} (:seon.task/accumulated t)
        cites (ref/references db (str plan "\n" (:seon.task/instructions t)))
        refs (filterv #(= :var (docs/citation-kind db %)) cites)
        sections (into [] (comp (filter #(= :view (docs/citation-kind db %))) (map #(docs/cited-view db %)))
                       cites)
        near (similar db (some->> (:seon.task/id t) (pdb/eid db :seon.task/id)) (into (set proofs) (map :seon.ref/name) refs))]
    (cond-> {:task (assoc (select-keys t [:seon.task/id :seon.task/kind :seon.task/instructions])
                          :seon.task/plan plan)
             ;; a proof is shown with the proofs, not again in the write set; the names that
             ;; resolve to nothing are one entry, their problem said once
             :write-set (let [rs (into [] (comp (remove (comp (set proofs) :seon.ref/name))
                                                (map #(let [v (docs/resolve-ref db (:seon.ref/name %) :code)]
                                                        (cond-> v (not (:unresolved v)) (assoc :callers (docs/callers db (:seon.ref/name %)))))))
                                       refs)
                              {gone true there false} (group-by (comp boolean :unresolved) rs)]
                          (cond-> (vec there)
                            gone (conj {:unresolved (mapv :symbol gone) :problem (:unresolved ref/problem-messages)})))
             ;; (the turn sections' :proofs are each turn's statuses; these are how to run them)
             :to-prove (into (sorted-map-by #(compare (str %1) (str %2)))
                             (for [p proofs]
                               [p (if (docs/source-of db p)
                                    (list 'my.agent/prove #{p})
                                    (str "not written yet; it should assert what the plan says: " (not-written p plan)))]))}
      (seq sections) (assoc :sections sections)
      near (assoc :similar near))))

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
                                      ;; no result: not written yet, the run failed as a whole, or it never ran (unknown,
                                      ;; the judge's word: seon.reconcile/done-explain)
                                      st (get results p (cond (nil? src) :undefined (seq test-run-errors) :not-run :else :no-assertions))
                                      fs (filter #(= p (:test %)) reds)]]
                            (cond-> {:test p :status st}
                              ;; its source is code, printed as in its file (seon.print/code)
                              (and src (not= :pass st)) (assoc :source (print/code src))
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

(defn- folded
  "Response r with each of its members fold names (:value, :out: the handle of the earlier record
  that showed the same) as the form that reads it there, (:value result/e12)."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [r fold] (reduce (fn [r [k h]] (assoc r k (list k h))) r fold))

(defn eval-messages
  "An eval's part of the conversation (the :seon.render/ai render of :seon.db/eval-fact) as
  provider-neutral messages: a model's eval (its record names the provider's call id) is its
  assistant message, the reply's prose and reasoning as returned with the eval call (:call, the
  call's place in its reply: the provider's format folds a reply's calls into one message),
  then its tool message, the response printed now (seon.print/record-text, opts :fold: its value or
  output an earlier record showed, as the form that reads it there, `folded`), or with opts
  :collapsed its handle and its value in one line (:line, which a record with a value or an
  error holds); anyone else's (the user's, the screen's) is one user message
  {:by :code :response}, the response spliced as printed."
  {:malli/schema [:=> [:cat :map :any] [:vector :seon.model/message]]}
  [{:seon.eval/keys [by record]} opts]
  (let [{:keys [code prose content call-prose reasoning call-id call]} record
        ;; collapsed (opts :collapsed, an older one the history keeps small): its handle and its value in one line
        text (if (:collapsed opts)
               (str (get-in record [:response :handle]) " ⇒ " (:line record))
               ;; a screen eval's cost is root's (its forms run each turn), shown on the page, not the model's
               (print/record-text (cond-> record
                                    (seq (:fold opts)) (update :response folded (:fold opts))
                                    (= "screen" by) (update :response dissoc :ms :ticks :bytes))))]
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
  "Record v's part of the conversation: its schema k's declared :seon.render/ai render
  (seon.render/render-of), with opts (an eval's: :seen)."
  {:malli/schema [:function [:=> [:cat :map :qualified-keyword] [:vector :seon.model/message]]
                  [:=> [:cat :map :qualified-keyword :map] [:vector :seon.model/message]]]}
  ([v k] (ai-messages v k {}))
  ([v k opts] ((render/render-of v :ai k) v opts)))

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
  collapsed (its handle and one line), each with its cost. A record's text is a function of it and
  the records before it, which never change, so a request prints only its new records and every
  older message is the same bytes (the provider's cache). An item already printed is skipped.
  Answers the cache: {:ids :parts {id part} :seen :canon :tx}, each part {:ref :whole :collapsed
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
                (= :message kind) (let [ms (ai-messages v k)] (assoc-in c [:parts id] (part [:message id] ms ms)))
                :else
                (let [rec (:seon.eval/record v) r (:response rec)
                      has? (and (contains? r :value) (coll? (:value r)))
                      ;; an equal earlier value stands for this one (seen is by identity)
                      x (when has? (get canon [(type (:value r)) (:value r)] (:value r)))
                      r (cond-> r has? (assoc :value x))
                      fold (if (:printed rec) {} (fold-of r seen canon))]
                  (-> c
                      (assoc-in [:parts id] (cond-> (part (cond-> [:eval id] (seq fold) (conj fold))
                                                          (ai-messages v k {:fold fold}) (ai-messages v k {:collapsed true}))
                                              ;; and as it reads unfolded: sent so while what it folds to is not shown whole
                                              (seq fold) (assoc :fold fold :plain (ai-messages v k))))
                      (assoc :seen (if (:printed rec) seen (print/seen seen r))
                             :canon (cond-> canon has? (assoc [(type x) x] x))))))))
          (or cache {:ids [] :parts {} :seen nil :canon {}}) items))

(defn- as-sent
  "Part p (printed-run's) as a request sends it, whole-ids the evals it sends whole: folded (its
  value or output as the form that reads an earlier record's, :fold) only while each record it
  folds to is sent whole (folds point back, so those are known), else unfolded (:plain), its ref
  without the fold. Answers [ref messages]."
  {:malli/schema [:=> [:cat :map [:set :string]] [:tuple :any [:vector :seon.model/message]]]}
  [{:keys [ref whole fold plain]} whole-ids]
  (if (every? #(contains? whole-ids (name %)) (vals fold)) [ref whole] [[:eval (second ref)] plain]))

(defn- step-up
  "Index i raised to a multiple of step, at most to: the history's boundaries move in large steps."
  {:malli/schema [:=> [:cat :int pos-int? :int] :int]}
  [i step to] (min to (* step (quot (+ i (dec step)) step))))

(defn- kept
  "Which of the parts (each {:cost [whole collapsed] :call}, oldest first) the history budget sends:
  the newest whole, the newest before them whole while they fit, the older still collapsed (the
  result its handle and one line) while those fit, the rest left out; each boundary raised to a
  multiple of step (:seon.config.render/history-step), so it moves in large steps and the
  messages before it stay the same bytes for many turns; never starting inside a reply (a part
  continuing the call before it). Answers [picks dropped]: :whole or :line for each part from the
  first sent, and how many older ones were left out."
  {:malli/schema [:=> [:cat [:vector :map] :int pos-int?] [:tuple [:vector [:enum :whole :line]] nat-int?]]}
  [parts budget step]
  (let [n (count parts)
        whole (mapv (comp first :cost) parts) small (mapv (comp second :cost) parts)
        ;; the first index from which the parts fit whole (the newest always), and before it, the
        ;; first from which the rest fit collapsed
        from (fn [cs spent end] (loop [i (dec end) s spent] (if (and (>= i 0) (<= (+ s (nth cs i)) budget)) (recur (dec i) (+ s (nth cs i))) (inc i))))
        b (if (zero? n) 0 (step-up (min (dec n) (from whole 0 n)) step (dec n)))
        a (step-up (from small (reduce + 0 (subvec whole b)) b) step b)
        a (or (first (drop-while #(pos? (:call (nth parts %))) (range a n))) n)]
    [(into (vec (repeat (max 0 (- b a)) :line)) (repeat (- n (max a b)) :whole)) a]))

(defn records
  "What agent a's conversation is made of on db, in tx order: its evals (not the screen's or a
  context read's: those end only the request that ran them) and the messages its runs took
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
                     :when (not (#{"screen" "context"} (:seon.eval/by v)))]
                 [tx v :seon.db/eval-fact])
               (for [[e tx] (d/q '[:find ?m ?tx :in $ ?a ?since :where [?ru :seon.run/agent ?a] [?m :seon.message/to ?ru ?tx] [(>= ?tx ?since)]] db a (or since 0))]
                 [tx (d/pull db [:seon.message/id :seon.message/from :seon.message/body] e) :seon.message/fact]))
       (sort-by (fn [[tx v]] [tx (:seon.eval/id v (:seon.message/id v))]))
       vec)))

(def run-keys
  "The block's run sections: what the task is and what it cites, the same each turn of an
  run (made from its first turn's basis), so they are sent once, in the cached prefix."
  [:task :write-set :to-prove :sections :similar])

(defn- run-message
  "The run sections of block, printed by the one printer under the context's budget, its cuts
  reading their rest through (my.task/story), the form that answers them as data."
  {:malli/schema [:=> [:cat :map] :string]}
  [block]
  (let [r (print/print-value (select-keys block run-keys) {:handle '(my.task/story) :budget (:context print/budgets)})]
    (str "Your task and what it cites, the same for this whole run:\n" (or (:text r) (:seon.error/message r)))))

(defn- screen-message
  "The screen: the turn sections of block (everything but the run's), printed under the
  context's budget, with how many earlier turns the history budget left out. Sent last, and
  replaced by the next request's."
  {:malli/schema [:=> [:cat :map :int] :string]}
  [block dropped]
  (str "The current state:\n" (print/text (cond-> (apply dissoc block run-keys)
                                             (pos? dropped) (assoc :history {:left-out-turns dropped}))
                                           (:context print/budgets))))

(defn request
  "The agent's request, a render of its record: agent a (its entity map, :db/id) for the turn
  basis b on db (opts :db :basis :block :help :run, and :help-ref :run-ref, the records those
  two texts render): the system prompt (system-text), the task story (opts :run, the run
  sections as its first turn was sent them, else block's), the conversation (each record of the
  run, `records`, by its own :seon.render/ai render, in tx order, each printed once (printed-run,
  opts :printed the run's earlier requests' prints, answered again as :printed):
  the newest whole, older ones whole and then collapsed within the history budget, `kept`), then
  the screen: the screen evals b names, each by its
  render, replaced each request, with how many earlier records the budget left out; with none
  (an outside agent's read), the block's turn sections (`screen-message`). Answers {:messages
  :sent}: provider-neutral messages (the provider's format function makes the wire shape,
  seon.model/wire-messages) and the records they render, in order (:seon.turn/sent, `sent`):
  [:section eid t], the system prompt's own section as of t, the page's last change (so turns that
  sent the same page name the same ref); [:task eid t], the task as of its
  run's first turn; [:eval id]; [:line id], an eval sent collapsed; [:message id]; [:history n],
  the n records the budget left out.
  :sent is nil for a read with no screen evals (an outside agent's): its last message is the
  block's turn sections, no record; every model turn has screen evals.
  Called by the turn, not declared as the agent fact's :seon.render/ai until the render cache
  makes it once per change (design section 10, step 4)."
  {:malli/schema [:=> [:cat [:maybe :map] :map] [:map [:messages [:vector :seon.model/message]] [:sent [:maybe [:vector :any]]] [:printed :map]]]}
  [a {:keys [db basis block help run help-ref run-ref printed]}]
  (let [rs (if (and a (= :model (:origin basis))) (records db (:db/id a) (:tx printed)) [])
        ;; each record printed once (opts :printed, the run's earlier requests'), then which the
        ;; history budget sends, whole or collapsed
        cache (printed-run (mapv (fn [[tx v k]] [(ref-of v) v k tx]) rs) printed)
        parts (mapv #(get-in cache [:parts %]) (:ids cache))
        [picks dropped] (kept parts (cfg/value :seon.config.render/history-budget) (cfg/value :seon.config.render/history-step))
        whole-ids (into #{} (keep (fn [[{:keys [ref]} pick]] (when (= :whole pick) (second ref)))) (map vector (drop dropped parts) picks))
        sent (mapv (fn [{:keys [ref collapsed] :as p} pick] (if (= :line pick) [[:line (second ref)] collapsed] (as-sent p whole-ids)))
                   (drop dropped parts) picks)
        ;; the screen, printed now (it is new each request), its values folded after the records'
        screen-parts (when-let [ids (seq (:screen basis))]
                       (:parts (printed-run (for [id ids] [[:eval id] (d/pull db [:seon.eval/id :seon.eval/by :seon.eval/record] [:seon.eval/id id]) :seon.db/eval-fact nil])
                                            cache)))
        screen-sent (mapv #(as-sent (get screen-parts %) whole-ids) (:screen basis))
        screen (when (seq screen-sent) (into [] (mapcat second) screen-sent))
        note (when (and screen (pos? dropped)) {:role "user" :content (pr-str {:history {:left-out dropped}})})]
    {:messages (-> [{:role "system" :content help} {:role "user" :content (or run (run-message block))}]
                   (into (mapcat second) sent)
                   (into (if screen (cond-> screen note (conj note)) [{:role "user" :content (screen-message block dropped)}])))
     :printed cache
     :sent (when (and screen help-ref run-ref)
             (-> [help-ref run-ref] (into (map first) sent) (into (map first) screen-sent)
                 (cond-> note (conj [:history dropped]))))}))

(defn messages
  "A request's messages without a record (a test's, a read before any eval): the system prompt, the
  run sections of block and its turn sections, as `request` closes a read with no screen evals."
  {:malli/schema [:=> [:cat :string :map] [:vector :seon.model/message]]}
  [help block] (:messages (request nil {:block block :help help :basis {}})))

(defn reader-text
  "What a turn's reader got beyond the system prompt and the scrollback: its run sections and
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

(defn parts-of
  "Refs (as :seon.turn/sent names records) each beside its messages, each record by its own
  render: the system prompt's section as of its t (system-text), the task's run sections as of its t
  (story), an eval by its :seon.render/ai render with the fold its ref names (what it showed of an
  earlier record, as the form that reads it there) or collapsed ([:line id]), a message by its
  render, the history note by its count: what the request sent, the same bytes. No request is built again: the same records, rendered."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :any]] [:vector [:tuple :any [:vector :seon.model/message]]]]}
  [db refs]
  (let [ev #(d/pull db [:seon.eval/id :seon.eval/by :seon.eval/record] [:seon.eval/id %])]
    (mapv (fn [[kind x t :as ref]]
            [ref (case kind
                   :section [{:role "system" :content (system-text (d/as-of db t))}]
                   :task [{:role "user" :content (story db x t)}]
                   :eval (ai-messages (ev x) :seon.db/eval-fact {:fold (if (map? t) t {})})
                   :line (ai-messages (ev x) :seon.db/eval-fact {:collapsed true})
                   :message (ai-messages (d/pull db [:seon.message/id :seon.message/from :seon.message/body] [:seon.message/id x]) :seon.message/fact)
                   :history [{:role "user" :content (pr-str {:history {:left-out x}})}])])
          refs)))

(defn sent-split
  "Where a turn's request went, in tokens (the printer's sizer over each part's content and its
  calls' code), from its parts as parts-of reads them back from the refs the turn recorded: the
  system prompt (:section), the task story (:task), the screen (the evals whose ids screen holds:
  the turn basis's :screen) and the conversation (every other eval, whole or collapsed, each
  message, the history note). {:system :story :conversation :screen}."
  {:malli/schema [:=> [:cat [:vector [:tuple :any [:vector :seon.model/message]]] [:set :string]]
                  [:map [:system nat-int?] [:story nat-int?] [:conversation nat-int?] [:screen nat-int?]]]}
  [parts screen]
  (let [part (fn [[kind x]] (case kind :section :system :task :story (:eval :line) (if (screen x) :screen :conversation) :conversation))
        tok (fn [ms] (select/estimate (apply str (mapcat (fn [m] (cons (:content m) (map :code (:tool-calls m)))) ms))))]
    ;; each part's messages sized together (the sizer's estimate is not additive across pieces)
    (merge {:system 0 :story 0 :conversation 0 :screen 0}
           (update-vals (group-by (comp part first) parts) #(tok (into [] (mapcat second) %))))))

(defn- story
  "The task story: task entity e's run sections as of t on db (run-state, printed by
  run-message), what every turn of a run sends after the system prompt, as its first turn read them."
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
  every later reading of it. opts :printed, what the run's earlier requests printed (the cache
  `printed-run` answers, with the task story's text by its ref): only what is new is printed.
  {:block :messages :sent :printed}."
  {:malli/schema [:function [:=> [:cat :seon.host/db :seon.turn/basis] [:map [:block :map] [:messages [:vector :seon.model/message]] [:sent [:maybe [:vector :any]]] [:printed :map]]]
                  [:=> [:cat :seon.host/db :seon.turn/basis :map] [:map [:block :map] [:messages [:vector :seon.model/message]] [:sent [:maybe [:vector :any]]] [:printed :map]]]]}
  ([db basis] (render db basis {}))
  ([db {:keys [task run turn origin screen agent] :as basis} {:keys [printed]}]
  (let [prev (if (= :model origin) (model-turns db run turn) [])
        t (task/task db task)
        block (if screen (run-state db t) (current-state db t (turn-input db basis (peek prev))))
        a (when agent (d/pull db [:db/id :seon.agent/key] [:seon.agent/key agent]))
        ;; the task story is its run's first turn's: the task as of that turn's t
        story-t (if (seq prev) (:max-tx (d/commit-as-db db (:commit (:seon.turn/basis (first prev))))) (:max-tx db))
        task-e (pdb/eid db :seon.task/id task)
        run-ref [:task task-e story-t]
        ;; the story is the same each turn of a run: printed once, by its ref
        story-text (when (seq prev) (or (get-in printed [:story run-ref]) (story db task-e story-t)))
        r (request a {:db db :basis basis :block block :help (system-text db) :run story-text :printed printed
                      :help-ref [:section (docs/page-top db system-page) (docs/page-t db system-page)]
                      :run-ref run-ref})]
    {:block block :messages (:messages r) :sent (:sent r)
     :printed (cond-> (:printed r) story-text (assoc :story {run-ref story-text}))})))
