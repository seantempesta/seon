(ns seon.ctx.status
  "How a task and its work stand on the agent's branch, as data the renders draw: its proofs, failures, verdicts and next steps (docs/render.md#how-the-work-stands)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.task :as task]
            [seon.db :as pdb]
            [seon.merge :as pm]
            [seon.print :as print]
            [seon.ref :as ref]
            [seon.doc :as doc]
            [seon.ratom :as ratom]
            [seon.agent :as agent]
            [seon.problems :as problems]
            [seon.reconcile :as r]
            [seon.edn :as sedn]
            [seon.ctx :as ctx]
            [seon.ctx.search :as search]))

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
  assertions that each threw one NullPointerException are one cause, read once. See docs/render.md#a-component-answers-hiccup."
  {:malli/schema [:=> [:cat [:sequential :map]] [:vector :map]]}
  [fs]
  (let [ms (mapv failure fs)
        k (juxt :got :message)
        order (distinct (map k ms))
        by (group-by k ms)]
    (vec (for [g order :let [[m :as same] (by g)]]
           (if (next same) (-> m (dissoc :assert) (assoc :asserts (mapv :assert same))) m)))))

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
           (for [{:keys [reason symbol behind]} rs :when (not (#{:open :contract-gap :unmerged :open-problem} reason))]
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

(defn task-data
  "The task's data: task t's run sections as data, the same each turn of a run: the task (its
  plan as it accumulated along :needs, prerequisites first); its write set, each program var cites
  name with its source and its callers (doc/callers), the names that resolve to nothing said
  once; each proof to the form that runs it at the REPL, or, when it is not written, that it
  is not and what it should assert in the plan's words (whether it fails, and why, is the
  proofs part's: it changes each turn); the wiki sections and the tasks cites name (a task by its plan, seon.ref/task-view). cites: what the task cites, its cite entities and its needs'
  (seon.task/task-cites). What `my.task/data` answers at the agent's REPL, on demand; the page's task part is
  the pulled task's own renders (seon.render.ui/task, docs/render.md#a-component-answers-hiccup)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.task/task [:sequential :map]] :map]}
  [db t cites]
  (let [{:keys [plan proofs]} (:seon.task/accumulated t)
        refs (search/program-vars db cites)
        sections (into [] (keep (fn [{a :seon.cite/address n :seon.cite/name tk :seon.cite/task d :seon.cite/depth}]
                                  (cond a (doc/view db a d) tk (ref/task-view db n d)))) cites)]
    (cond-> {:task (assoc (select-keys t [:seon.task/id :seon.task/kind :seon.task/instructions])
                          :seon.task/plan plan)
             ;; a proof is shown with the proofs, not again in the write set; the names that
             ;; resolve to nothing are one entry, their problem said once
             :write-set (let [rs (into [] (comp (remove (set proofs)) (map #(ctx/cited db %))) refs)
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

(defn- verdicts
  "The verdicts on branch db of the tests the done rule reads (seon.proofs/result-tx: the verdict at its reach digest
  now): each of proofs, and every test whose verdict is not green, {test {:status :pass|:fail|:deferred :failures
  [assertion]}}: green is :pass, red and flaky :fail (a flaky test is no green), deferred left to root's gate. A seek of
  :seon.test/status per value that is not green (indexed) and one per proof: the greens outside the proofs, which no
  reason reads, are never read. See docs/render.md#how-the-work-stands."
  {:malli/schema [:=> [:cat :seon.host/db [:set :qualified-symbol]] [:map-of :qualified-symbol :map]]}
  [db proofs]
  (into {} (for [e (into (into #{} (keep #(pdb/eid db :var/name %)) proofs)
                         (comp (mapcat #(d/datoms db :avet :seon.test/status %)) (map :e))
                         [:red :flaky :deferred])
                 :let [v (d/entity db e) st (:seon.test/status v)]
                 :when st]
             [(:var/name v) (cond-> {:status (case st :green :pass :deferred :deferred :fail)}
                              (seq (:seon.test/failures v)) (assoc :failures (:seon.test/failures v)))])))

(defn task-of
  "The task agent record c holds (its :seon.agent/task, as ctx pulls it, docs/ctx.md#the-pattern), read for what its
  accumulated plan and proofs take (seon.task/pulled-accumulated): its id, kind, plan and proofs and its
  needs', and each proof's var as :proved holds it ({name var}: its verdict, failures and form), the
  task's and its needs'. Each read by its path, so a reader of it reads nothing else of the task."
  {:malli/schema [:=> [:cat :map] [:maybe [:map [:task :map] [:proved [:map-of :qualified-symbol :map]]]]]}
  [c]
  (when-let [t (:seon.agent/task c)]
    (let [own (fn [x] (into {} (for [k [:seon.task/id :seon.task/kind :seon.task/plan :seon.task/proofs] :let [v (get x k)] :when (some? v)] [k (ratom/realize v)])))
          needs (vals (:seon.task/needs t))]
      {:task (assoc (own t) :seon.task/needs (into {} (map-indexed (fn [i n] [i (own n)])) needs))
       :proved (into {} (for [ps (cons (:proved t) (map :proved needs)) v (vals ps) :let [n (:var/name v)] :when n]
                          ;; its verdict and failures, and its source (whether it is defined): its form's text
                          [n (cond-> (into {} (for [k [:var/name :seon.test/status :seon.test/failures :seon.test/ticks :seon.test/bytes] :let [x (get v k)] :when (some? x)] [k (ratom/realize x)]))
                               (get-in v [:var/form :form/src]) (assoc :var/form {:form/src (ratom/realize (get-in v [:var/form :form/src]))}))]))})))

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
          {tests true others false} (group-by #(contains? (set (:var/meta %)) :test) vs)
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
  seon.ctx/the-context-is-the-agents-evals-and-seons, R2b-1): each proof by the verdict its var
  entity holds (`verdicts`; none: :unknown, or :undefined while the branch defines no such test),
  a red or unknown one with its source and the program functions it calls (`callees`), a red one
  with its failing assertions (`failure`); the done rule's reasons (seon.reconcile/done-explain)
  and each as what to do (`refused`); what the branch's work did since its merge base, from one
  diff (seon.merge/delta-changes of :seon.agent/base-commit against db): the functions without a
  contract, the problems it caused and the sections it made stale (seon.problems/caused-split),
  what its merge carries, and whether it can merge now when the task merges itself; its
  unmerged entries; a test run that failed as a whole (:seon.agent/test-error). It reads facts
  about the work only, never the conversation (its turns, evals and messages), so a turn's record
  draws it again only when it changed the work; the turn number and a cut reply are the run's
  (seon.render.ui/agent-state). Nothing is held: each is a fact of db. :reasons is the done rule's
  own answer, the turn's check (seon.turn/work!), which the model and the page read as :refused. A design task's plan before its run (:no-design) is its run's, the run its newest model call names, so it waits for the first turn. opts :index, a function answering the reconciler's index of db (seon.reconcile/index; else
  one built cold), called only when a test outside the task's proofs is red (whether it is a
  regression or a stray red is main's claims: measured cold on a demo branch, 14.8M ticks and
  70 MB), and :deferred, the task's proofs a run leaves to root's gate (process
  tests: none while every proof has a verdict). See docs/render.md#how-the-work-stands."
  {:malli/schema [:=> [:cat :seon.host/db :map :map] :map]}
  [db rec {:keys [index deferred]}]
  (let [a (:db/id rec)
        ag (d/entity db a)
        ;; the task and its proofs as the record pulls them (task-of): the accumulated plan and proofs over its needs
        {t :task proved :proved} (task-of rec)
        tid (:seon.task/id t)
        {:keys [proofs plan]} (when tid (task/pulled-accumulated t))
        kind (:seon.task/kind t :work)
        ;; main at its last move of content, read only when the task merges itself: readiness reads main's content, so a commit that moved
        ;; none of it, as root's note of an agent's status does, reaches no reader of this
        main (when (task/merges? kind) (ratom/realize (:seon.agent/main-program rec)))
        ;; a test run that failed as a whole: present on its process only while it is so (the
        ;; process's other facts, written on every eval, are not read)
        test-error (:seon.agent/test-error ag)
        ;; while it is so no verdict counts: what the branch holds is from before the change that the run
        ;; failed to judge (every proof :not-run, as the run's own results were none)
        vs (if test-error {} (verdicts db (set proofs)))
        ;; a deferred proof is neither red nor unknown (seon.reconcile/done-explain)
        deferred (into (set deferred) (keep (fn [[t {st :status}]] (when (= :deferred st) t))) vs)
        results (into {} (keep (fn [[t {st :status}]] (when (not= :deferred st) [t st]))) vs)
        ;; what the branch did since its merge base is a diff of two commits' trees, and a db as of a t (a past turn past the store's keep, docs/storage.md#collection) has none: it leaves that out
        base (when (:store db) (some->> (:seon.agent/base-commit ag) (d/commit-as-db (:store db))))
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
        ;; a red here that main was red on too, at the branch's merge base: said as a fact, and the agent's to clear all the same (the owner, 2026-11-07)
        also-on-main (when (and base (some (fn [[_ st]] (= :fail st)) results))
                       (vec (sort-by str (keep (fn [[t {st :status}]] (when (and (= :fail st) (= :fail (get results t))) t)) (verdicts base (set proofs))))))
        input (cond-> (if claims
                        (r/done-input claims tid results (set gaps) (mapv #(sedn/encode (:key %)) unmerged) nil plan-before (set deferred))
                        (cond-> {:proofs (set proofs) :kind kind :results results :main-green #{} :allowed-red #{}
                                 :uncontracted (set gaps) :unmerged (mapv #(sedn/encode (:key %)) unmerged) :deferred (set deferred)}
                          (= :design kind) (assoc :plan plan)
                          (and (= :design kind) plan-before) (assoc :plan-before plan-before)))
                (seq (:caused split)) (assoc :caused (set (:caused split))))
        reasons (r/done-explain input)
        unjudged (when-let [ds (seq (sort-by str (keep (fn [[t {st :status}]] (when (= :deferred st) t)) vs)))]
                   (task/unjudged db t (vec ds)))
        reds (vec (for [[p {:keys [failures]}] vs f failures] (assoc f :test p)))
        blockers (refused reasons reds #(doc/source-of db %) #(ctx/callees db %)
                          #(or (nil? base) (not= (doc/source-of db %) (doc/source-of base %))))
        ;; the next step, from the task's own data and never its kind (docs/agent.md#working-for-a-person): none of these blocks a merge
        te (when tid (pdb/eid db :seon.task/id tid))
        plan-tx (when te (some-> (first (d/datoms db :eavt te :seon.task/plan)) :tx))
        me (:seon.agent/key ag)
        asked (when plan-tx
                (some (fn [[e m]] (let [from (:seon.message/from m)]
                                    (when (and (not (or (= "root" from) (= me from))) (> (ctx/first-delivered [db a e]) plan-tx)) from)))
                      (:seon.message/_to rec)))
        next (cond-> []
               asked (conj {:step :fold-message :from asked}))]
    (cond-> {:reasons reasons
             ;; each proof as :proved holds its var: its verdict (green :pass, red and flaky :fail, as `verdicts`), its source
             :proofs (vec (for [p (sort-by str proofs)
                                :let [v (get proved p)
                                      src (get-in v [:var/form :form/src])
                                      verdict (when-not test-error (case (:seon.test/status v) nil nil :green :pass :deferred :deferred :fail))
                                      st (cond (and verdict (not= :deferred verdict)) verdict
                                               (or (deferred p) (= :deferred verdict)) :deferred
                                               (nil? src) :undefined (some? test-error) :not-run :else :unknown)
                                      fs (when-not test-error (:seon.test/failures v))
                                      calls (when (and src (not= :pass st)) (ctx/callees db p))
                                      ;; its example from the merge base, so it doesn't move with what the agent writes, nor quote its own test back
                                      shape (when (= :undefined st) (or (some-> (:seon.agent/base-commit ag) (->> (d/commit-as-db (pdb/store-of db))) (test-shape p)) (test-shape db p)))]]
                            (cond-> {:test p :status st}
                              (and src (not= :pass st)) (assoc :source (print/code src))
                              shape (assoc :shape shape)
                              (seq fs) (assoc :failures (failures fs))
                              (seq calls) (assoc :calls calls))))}
      (seq blockers) (assoc :refused blockers)
      (seq next) (assoc :next next)
      (seq also-on-main) (assoc :also-red-on-main also-on-main)
      ;; a real field, on every page from the first, not only the line an eval's answer carries: its base is behind main
      (and main base (= :agent (:mode @agent/state))) (assoc :main-moved (agent/main-line base db (d/commit-as-db (pdb/store-of db) main)))
      ;; what no gate will run stays unjudged, said here because it ends no run (seon.task/unjudged)
      unjudged (assoc :unjudged unjudged)
      test-error (assoc :test-run {:failed [(:seon.error/message test-error)]})
      (seq unmerged) (assoc :unmerged (mapv #(select-keys % [:key :base :ours :theirs]) unmerged))
      (seq gaps) (assoc :without-a-contract gaps)
      (seq (:caused split)) (assoc :caused (:caused split))
      (seq (:attention split)) (assoc :attention (:attention split))
      (seq (pm/divergence ch)) (assoc :its-merge-carries (pm/divergence ch))
      (and main base) (assoc :merge (pm/readiness base db (d/commit-as-db (pdb/store-of db) main) moved)))))

