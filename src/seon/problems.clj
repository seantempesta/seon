(ns seon.problems
  "The one read of every problem Seon recorded (session D2, notes/archive/option3-D2-problems.md):
  main's db value in, one report out, by class. Root and the owner mine tasks from it by
  hand: each row carries what a task-file declaration needs (its producer and owning
  namespace, its evidence as data, the proofs that reproduce it) and the tasks that already
  answer it.

  Its cost follows the problems, never the program: each class is read through an index
  that holds only problem rows (aevt of an attribute only a problem carries, avet of an
  indexed status), then one pull per row. Nothing walks the tasks, runs or vars.

  Classes:
    :platform :eval :agent :effect  recorded errors (seon.error), counted by signature
    :red :cost                      a run's red assertions, its cost proofs' verdicts
    :merge                          conflicts, unmerged keys, behind, a refused merge
    :dead                           a run whose agent died or was lost
    :error                          a run the turn loop ended on an error (the model)
    :waste                          forms charged to the producer of what was shown
    :task-conflict                  task content a branch and main both changed, differently,
                                    recorded at its run's end (seon.task/carry-tx)
    :failed                         a task held for root with a failure: a run refused, its
                                    agent dead or its merge refused (seon.reconcile/hold-tx), or
                                    marked failed (my.task/fail!, root or a check): its reason, a
                                    counterexample when marked, its evidence; open while held
    :dead-citation                  a name a wiki section cites (:seon.section/names) that names
                                    nothing now (seon.ref/names-on-db?): derived when read,
                                    never written, so it is open exactly while it is true
    :unnamed-site                   a site of a law (`laws`: a catch) its page does not name:
                                    derived from the compiler's facts and the page's names
    :test-red                       a test whose newest result the test service recorded is red
                                    (seon.prove): closed by a later green, written by nothing
    :stale-pin                      a pin the wiki states (`pins`: docs/forks.md) that is not
                                    the gitlink root's source import recorded

  `report` reads what was recorded, at a cost that follows the problems. `health` adds the
  derived classes, which read the program's names once (a set): their cost follows the
  citations (seon.problems-test/dead-citations-follow-the-citations)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [datahike.api :as d] [clojure.string :as str] [seon.merge :as pm] [seon.ref :as ref] [seon.program :as program] [seon.db :as pdb]))

(def counted-classes [:platform :eval :agent :effect])
(def ^:private death-statuses "Run statuses of a death (:seon.run/status)." (:seon.status/failed (contract/properties :seon.run/status)))
(def ^:private finished "Run statuses that make its task done (:seon.run/status, as seon.reconcile/finished)." (:seon.status/finished (contract/properties :seon.run/status)))
(def run-classes [:red :cost :merge :dead :error :waste :task-conflict :unrun])

(defn- es
  "The entities carrying attribute a (its aevt index: only the entities that have it)."
  {:malli/schema [:=> [:cat :seon.host/db :keyword] [:vector :int]]}
  [db a] (mapv :e (d/datoms db :aevt a)))

(defn answers
  "The tasks on db whose evidence names a problem: {signature [task-id]} and
  {run-id [task-id]} under :signature and :from. A task's evidence about its own
  run (a hold's, seon.reconcile/hold-tx) answers nothing: it is that problem. Reads
  only the tasks that carry evidence (aevt :seon.task/evidence)."
  {:malli/schema [:=> [:cat :seon.host/db] [:map [:signature :map] [:from :map]]]}
  [db]
  (reduce (fn [acc {:keys [e v]}]
            (let [ev v
                  id (:seon.task/id (d/pull db [:seon.task/id] e))
                  own? (some->> (:from ev) (pdb/eid db :seon.run/id) (d/pull db [{:seon.run/task [:seon.task/id]}])
                                :seon.run/task :seon.task/id (= id))]
              (cond-> acc
                (:signature ev) (update-in [:signature (:signature ev)] (fnil conj []) id)
                (and (:from ev) (not own?)) (update-in [:from (:from ev)] (fnil conj []) id))))
          {:signature {} :from {}} (d/datoms db :aevt :seon.task/evidence)))

;; ---- recorded errors ------------------------------------------------------------------

(defn- closed?
  "Is the recorded error r (entity e) closed: a subject of it has a fact written after the error's
  last one. The subjects: :seon.error/subject (a var's name or a section's address), and, for a
  :seon.docs/refused error from a store older than the type's removal, the link's target address
  and the citing section's (its example's). Such an error is closed too when the citing section
  exists and no longer links to the target (not a dead link of it, not among its references).
  Nothing is written to close it, and a subject that does not exist has changed nothing."
  {:malli/schema [:=> [:cat :seon.host/db :map :int] :boolean]}
  [db r e]
  (let [ex (:seon.error/example r)
        refused? (= :seon.docs/refused (:seon.error/type r))
        [target from] (when refused? [(:seon.ref/address ex) (:seon.section/address ex)])
        subjects (keep (fn [s] (when s (pdb/eid db (if (symbol? s) :var/name :seon.section/address) s)))
                       [(:seon.error/subject r) target from])
        last-tx (reduce max (map :tx (d/datoms db :eavt e)))
        citing (some->> from (pdb/eid db :seon.section/address) (d/pull db [:seon.section/dead-links {:seon.section/references [:seon.section/address]}]))]
    (boolean (or (some (fn [s] (some #(> (:tx %) last-tx) (d/datoms db :eavt s))) subjects)
                 (and citing (not-any? #{target} (:seon.section/dead-links citing))
                      (not-any? #(= target (:seon.section/address %)) (:seon.section/references citing)))))))

(defn- error-row
  "One recorded error (entity e) as a row, :open? false once its subject changed (`closed?`)."
  {:malli/schema [:=> [:cat :seon.host/db :map :int] :seon.problems/row]}
  [db ans e]
  (let [r (d/pull db '[*] e)
        s (:seon.error/signature r)
        op (:seon.error/operation r)
        ex (:seon.error/example r)]
    (cond-> {:class (:seon.error/class r) :signature s :type (:seon.error/type r) :operation op :message (:seon.error/message r)
             :count (:seon.error/count r) :agents (:seon.error/agents r 0)
             :first-ms (:seon.error/first-ms r) :last-ms (:seon.error/last-ms r)
             :last-agent (:seon.error/last-agent r) :last-task (:seon.error/last-task r) :example ex
             :producer op :owner-ns (some-> op namespace symbol)
             :evidence (cond-> {:kind :error :signature s :count (:seon.error/count r) :agents (:seon.error/agents r 0) :example ex}
                         (:seon.error/form r) (assoc :form (:seon.error/form r)))
             :proofs [] :answered-by (get-in ans [:signature s] []) :at-ms (:seon.error/last-ms r)}
      (:seon.error/form r) (assoc :form (:seon.error/form r))
      (closed? db r e) (assoc :open? false))))

;; ---- derived: what the program's facts make true now ------------------------------------

(defn dead-citations
  "Each name a wiki section cites (:seon.section/names) that names nothing on db
  (seon.ref/names-on-db?, the one rule for a name), and each link it makes that reached no
  section at load (:seon.section/dead-links) and still reaches none, as a :dead-citation row:
  open while it is true, since nothing writes it. Cost: main's var names read once into a set
  and one set lookup per cited name; the rule runs only on a name the set misses; one seek per
  dead link."
  {:malli/schema [:=> [:cat :seon.host/db :map] [:vector :seon.problems/row]]}
  [db ans]
  (let [vars (into #{} (map :v) (d/datoms db :aevt :var/name))
        row (fn [e k v message evidence]
              (let [a (:seon.section/address (d/pull db [:seon.section/address] e))
                    s (str "dead-citation " a " " v)]
                (merge {:signature s :section a :open? true :message (message a)
                        :producer nil :owner-ns (when (symbol? v) (symbol (namespace v))) :proofs []
                        :evidence (assoc evidence :kind :dead-citation :section a) :answered-by (get-in ans [:signature s] []) :at-ms nil}
                       {k v})))]
    (vec (concat
           (for [{:keys [e v]} (d/datoms db :aevt :seon.section/names)
                 :when (not (or (vars v) (ref/names-on-db? db v)))]
             (row e :name v #(str % " cites " v ", which names nothing") {:name v}))
           (for [{:keys [e v]} (d/datoms db :aevt :seon.section/dead-links)
                 :when (nil? (pdb/eid db :seon.section/address v))]
             (row e :target v #(str % " links to " v ", which is no section") {:target v}))))))

(def laws
  "The laws the compiler's facts answer exactly, as data: the fact that marks a site, the page
  that must name each site's function, and the namespaces the law leaves out (the check
  drivers: bin/laws' scope). The others stay bin/laws' text scan: clocks and measures (a host
  member is recorded as jolt.host/static-member, not by its name), resolution (a quoted
  symbol is a ref, so the facts over-count)."
  [{:law :catch :fact :form/catches :page "docs/failure-points.md" :except #{"seon.check" "seon.e2e"}}])

(defn unnamed-sites
  "Each site of a law (`laws`) its page does not name: a live form carrying the law's fact,
  by the var it defines, in a namespace of Seon's own src (an agent's app and any test are not
  failure points: a deftest's `is` expands to a catch), whose name no section of the page cites (:seon.section/names), as an
  :unnamed-site row. Cost: the forms carrying the fact (aevt) and the wiki's cited names, once."
  {:malli/schema [:=> [:cat :seon.host/db :map] [:vector :seon.problems/row]]}
  [db ans]
  (vec (for [{:keys [law fact page except]} laws
             :let [named (into #{} (keep (fn [{:keys [e v]}]
                                           (when (str/starts-with? (:seon.section/address (d/pull db [:seon.section/address] e)) (str page "#")) v)))
                               (d/datoms db :aevt :seon.section/names))
                   owners (merge (into {} (map (fn [{:keys [e v]}] [v (:method/multi (d/entity db e))])) (d/datoms db :aevt :method/form))
                                 (into {} (map (fn [{:keys [e v]}] [v (:impl/protocol (d/entity db e))])) (d/datoms db :aevt :impl/form)))]
             fe (distinct (map :e (d/datoms db :aevt fact)))
             :when (program/live? db fe)
             :let [f (d/entity db fe)
                   ;; a catch is attributed to its form: the vars it defines, else the multimethod or
                   ;; protocol it extends, else the form's id: no site drops
                   sites (or (seq (map :var/name (:form/defines f))) (some-> (owners fe) vector) [(:form/id f)])
                   file (some-> f :form/ns :ns/file)
                   nsym (:ns/name (:form/ns f))]
             v sites
             ;; the law is Seon's own source's (bin/laws scans src): an agent's app, and a test's
             ;; `is` (which expands to a catch), are not failure points of the system
             :when (and (not (except (str nsym))) (not (named v)) (some-> file (str/starts-with? "src/")))
             :let [s (str "unnamed-site " (name law) " " v)]]
         {:signature s :law law :site v :page page :open? true
          :message (str v " has a " (name law) " site that " page " does not name")
          :producer nil :owner-ns nsym :proofs []
          :evidence {:kind :unnamed-site :law law :site v :page page} :answered-by (get-in ans [:signature s] []) :at-ms nil})))

(def pins
  "Where the wiki states each customized library's pin: the page, and the line that states one
  (`reference-code/x` pinned at `sha`), compared with the gitlinks root's source import
  recorded (:seon.root/source :pins, seon.root/import-source!)."
  {:page "docs/forks.md" :line #"(?m)^`(reference-code/[^`]+)` pinned at `([0-9a-f]+)`"})

(defn stale-pins
  "Each pin the wiki states (`pins`) that is not the gitlink root's source import recorded, as a
  :stale-pin row; none while root has recorded no import (unknown, not a mismatch). Cost: the
  page's sections (one AVET range of addresses) and their text."
  {:malli/schema [:=> [:cat :seon.host/db :map] [:vector :seon.problems/row]]}
  [db ans]
  (let [{:keys [page line]} pins
        recorded (some->> (pdb/eid db :seon.root/id :root) (d/pull db [:seon.root/source]) :seon.root/source :pins)]
    (vec (when recorded
           (for [{a :v e :e} (d/datoms db :aevt :seon.section/address) :when (str/starts-with? a (str page "#"))
                 [_ path sha] (re-seq line (or (:seon.section/text (d/pull db [:seon.section/text] e)) ""))
                 :let [g (get recorded path)] :when (not (some-> g (str/starts-with? sha)))
                 :let [s (str "stale-pin " a " " path)]]
             {:signature s :section a :path path :stated sha :recorded g :open? true
              :message (str a " states " path " pinned at " sha ", its gitlink is " (or g "none"))
              :producer nil :owner-ns 'seon.docs :proofs []
              :evidence {:kind :stale-pin :section a :path path :stated sha :recorded g} :answered-by (get-in ans [:signature s] []) :at-ms nil})))))

;; ---- caused: what a branch's own work made true -----------------------------------------

(defn caused
  "The derived problems the branch at head has that base (its merge base) had not, by
  message: what the branch's own work broke. The done rule refuses while any is open
  (seon.reconcile/done-explain :open-problem), and the agent reads them each turn; an
  evaluation is never refused (AGENTS: get out of an agent's way). Cost: the two derived reads
  over base and over head, O(the program's citations and catch forms) twice, not O(changed): about
  75 KB per catch site per read (seon.problems-test/caused-reads-both-dbs-in-full bounds it)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db] [:set :string]]}
  [base head]
  (let [derived #(concat (dead-citations % {}) (unnamed-sites % {}))
        before (into #{} (map :signature) (derived base))]
    (into (sorted-set) (comp (remove (comp before :signature)) (map :message)) (derived head))))

;; ---- runs ------------------------------------------------------------------------

(def ^:private run-pull
  "What a problem row reads of a run: one pull (its conflicts are read only when it has
  them)."
  [:seon.run/id :seon.run/status :seon.run/outcome :seon.run/reason :seon.run/end-ms :seon.run/agent
   :seon.run/unmerged :seon.run/failures :seon.run/costs :seon.run/error :seon.run/waste :seon.run/task-conflicts
   :seon.run/deferred :seon.run/gate-unread {:seon.run/deferred-results [:seon.proof/test :seon.proof/status]}
   {:seon.run/task [:db/id :seon.task/id :seon.task/ns :seon.task/proofs]}
   {:seon.run/conflicts [:seon.merge/key :seon.merge/kind :seon.merge/base :seon.merge/ours :seon.merge/theirs]}])

(defn- task-done?
  "Has task te a run finished (`finished`)? One avet seek, then its runs."
  {:malli/schema [:=> [:cat :seon.host/db :int] :boolean]}
  [db te]
  (boolean (some #(finished (:seon.run/status (d/pull db [:seon.run/status] (:e %))))
                 (d/datoms db :avet :seon.run/task te))))

;; ---- tasks held for root -----------------------------------------------------------------

(defn- failed-row
  "Task entity e, which carries a failure, as a row: open while the task is held for root."
  {:malli/schema [:=> [:cat :seon.host/db :map :int] :seon.problems/row]}
  [db ans e]
  (let [t (d/pull db [:seon.task/id :seon.task/ns :seon.task/failure :seon.task/evidence :seon.task/execution :seon.task/proofs] e)
        {:keys [run] :as f} (:seon.task/failure t)
        ;; the measured evidence, when it is this failure's (hold-tx writes both)
        ev (let [ev (:seon.task/evidence t)] (when (and run (= run (:from ev))) ev))
        end-ms (some-> run (as-> a (d/pull db [:seon.run/end-ms] [:seon.run/id a])) :seon.run/end-ms)]
    (merge (select-keys f [:reason :counterexample])
           {:task (:seon.task/id t) :run run :open? (= :held (:seon.task/execution t))
            :owner-ns (:seon.task/ns t) :producer nil :proofs (vec (sort-by str (:seon.task/proofs t)))
            :evidence (merge ev f {:kind :failed}) :answered-by (get-in ans [:from run] []) :at-ms end-ms})))

(defn- run-rows
  "The rows of run entity e, by class."
  {:malli/schema [:=> [:cat :seon.host/db :map :int] [:map-of :keyword [:vector :seon.problems/row]]]}
  [db ans e]
  (let [p (d/pull db run-pull e)
        aid (:seon.run/id p)
        te (get-in p [:seon.run/task :db/id])
        status (:seon.run/status p)
        conflicts (mapv pm/conflict-value (:seon.run/conflicts p))
        unmerged (vec (sort (:seon.run/unmerged p)))
        base {:run aid :task (get-in p [:seon.run/task :seon.task/id]) :agent (:seon.run/agent p) :status status
              :outcome (:seon.run/outcome p) :reason (:seon.run/reason p) :end-ms (:seon.run/end-ms p)
              :open? (not (task-done? db te)) :owner-ns (get-in p [:seon.run/task :seon.task/ns]) :producer nil :proofs []
              :answered-by (get-in ans [:from aid] []) :at-ms (:seon.run/end-ms p)}
        ;; a red proof is this run's problem when the task itself states it: an inherited
        ;; proof was green on main when the task started (its needs were done), so red now it
        ;; is a regression, the run's reason; another task's proof not green yet is
        ;; an allowed red. Both stay in the run's facts as evidence, never problem rows.
        own (set (get-in p [:seon.run/task :seon.task/proofs]))
        failures (select-keys (:seon.run/failures p) own)
        costs (select-keys (:seon.run/costs p) own)
        err (:seon.run/error p)
        waste (:seon.run/waste p)
        merge? (or (seq conflicts) (seq unmerged) (= :behind status) (= :merge-failed (:outcome base))
                   (some-> err :seon.error/type namespace #{"seon.merge" "seon.writer"}))]
    (cond-> {}
      (seq failures)
      (assoc :red (vec (for [[test asserts] (sort-by (comp str key) failures)]
                         (assoc base :test test :assertions asserts :proofs [test]
                                :evidence {:from aid :red [test] :failures {test asserts}}))))
      (seq (remove (fn [[_ {v :verdict}]] (and (:ok? v) (empty? (:under v)))) costs))
      (assoc :cost (vec (for [[test {v :verdict :as c}] (sort-by (comp str key) costs) :when (not (and (:ok? v) (empty? (:under v))))]
                          (assoc base :test test :verdict v :rows (:rows c) :proofs [test] :evidence {:from aid :red [test] :cost {test c}}))))
      merge?
      (assoc :merge [(assoc base :conflicts (count conflicts) :unmerged unmerged :error err
                            :producer (:seon.error/operation err)
                            :evidence (cond-> {:from aid :conflicts conflicts :unmerged unmerged
                                               :red []}
                                        err (assoc :error err)))])
      (seq (:seon.run/task-conflicts p))
      (assoc :task-conflict (vec (for [c (:seon.run/task-conflicts p)]
                                   (assoc base :task (:task c) :attr (:attr c) :evidence (assoc c :from aid)))))
      (death-statuses status)
      (assoc :dead [(assoc base :error err :producer (:seon.error/operation err) :evidence (cond-> {:from aid} err (assoc :error err)))])
      (and err (not merge?) (not (death-statuses status)))
      (assoc :error [(assoc base :error err :producer (:seon.error/operation err) :evidence {:from aid :error err})])
      (seq waste)
      (assoc :waste [(assoc base :waste waste :evidence {:from aid :waste waste})])
      ;; owner's ruling, 2026-10-23: merged with its deferred process tests unasked (or no
      ;; gate checkout declared): open until each is recorded passing (my.root/gate!)
      (and (= :merged status) (seq (:seon.run/deferred p))
           (not-every? (into #{} (keep #(when (= :pass (:seon.proof/status %)) (:seon.proof/test %))) (:seon.run/deferred-results p))
                       (:seon.run/deferred p)))
      (assoc :unrun [(cond-> (assoc base :open? true :tests (vec (sort-by str (:seon.run/deferred p)))
                            :reason (str "merged with " (count (:seon.run/deferred p)) " process tests unrun")
                            :evidence {:from aid :deferred (vec (sort-by str (:seon.run/deferred p)))})
                       ;; the changed namespaces its gate could not read, and why
                       (:seon.run/gate-unread p) (assoc :unread (:seon.run/gate-unread p)))]))))

(defn test-reds
  "Each test whose newest result on main (the test service's, seon.prove/result-tx: the entity per
  test under :seon.proof/latest) is red, as a :test-red row naming the test, the branch and tx it
  ran at and the change that asked. A later green is the newer result of that entity, so it closes
  the row with nothing written; the older result stays in the history. A red on an agent's branch is
  not on main, so it is not here. Cost: one datom per test, and a pull per red one."
  {:malli/schema [:=> [:cat :seon.host/db :map] [:vector :seon.problems/row]]}
  [db ans]
  (vec (for [{:keys [e v]} (d/datoms db :aevt :seon.proof/latest)
             :when (not= :pass (:v (first (d/datoms db :eavt e :seon.proof/status))))
             :let [r (d/pull db '[*] e) t (symbol v) s (str "test-red " v)]]
         {:signature s :test t :status (:seon.proof/status r) :open? true :branch (:seon.proof/branch r) :t (:seon.proof/t r)
          :run (:seon.proof/run r) :change (:seon.proof/change r) :failures (:seon.proof/failures r)
          :message (str t " is " (name (:seon.proof/status r)) " at " (:seon.proof/branch r) " t " (:seon.proof/t r)
                        (when-let [a (get-in r [:seon.proof/change :agent])] (str ", asked by " (name a))))
          :producer nil :owner-ns (symbol (namespace t)) :proofs [t]
          :evidence {:kind :test-red :test t :run (:seon.proof/run r) :failures (:seon.proof/failures r)}
          :answered-by (get-in ans [:signature s] []) :at-ms nil})))

(defn report
  "Every problem recorded on main db, by class: {:t :classes {class {:n :rows}}}. Counted
  classes' :n is their occurrences and their rows run by count; run classes' :n is
  their rows, newest first. With an observer's fold (seon.error/fold), a recorded error's
  count is the true one: a repeat of a written signature is counted in the fold and not
  yet on main."
  {:malli/schema [:function [:=> [:cat :seon.host/db] :seon.problems/report]
                  [:=> [:cat :seon.host/db :seon.error/fold] :seon.problems/report]]}
  ([db] (report db {}))
  ([db fold]
  (let [ans (answers db)
        live (into {} (map (fn [{:keys [signature base] n :count}] [signature (+ (or base 0) n)])) (vals fold))
        errors (group-by :class (map #(let [r (error-row db ans %)] (update r :count max (get live (:signature r) 0)))
                                     (es db :seon.error/signature)))
        runs (into (sorted-set)
                       (concat (mapcat #(es db %) [:seon.run/failures :seon.run/costs :seon.run/error :seon.run/waste :seon.run/deferred
                                                   :seon.run/conflicts :seon.run/unmerged :seon.run/task-conflicts])
                               (mapcat (fn [s] (map :e (d/datoms db :avet :seon.run/status s))) (conj death-statuses :behind))))
        by-class (apply merge-with into (map #(run-rows db ans %) runs))
        failed (mapv #(failed-row db ans %) (es db :seon.task/failure))
        reds (test-reds db ans)]
    ^{:seon.render/schema :seon.problems/report}
    {:t (:max-tx db)
     :classes (merge (into {} (for [c counted-classes :let [rows (vec (sort-by (comp - :count) (get errors c)))]]
                                [c {:n (reduce + 0 (map :count rows)) :rows rows}]))
                     (into {} (for [c run-classes :let [rows (vec (sort-by #(- (or (:end-ms %) 0)) (get by-class c)))]]
                                [c {:n (count rows) :rows rows}]))
                     {:failed {:n (count failed) :rows (vec (sort-by #(- (or (:at-ms %) 0)) failed))}
                      :test-red {:n (count reds) :rows reds}})})))

(defn health
  "Every open problem on db, one value by class: what was recorded (`report`) and what the
  program's facts make true now (`dead-citations`, `unnamed-sites`, `stale-pins`). What the page, an agent's context and
  the done and merge gates read: computed when read, never stored, never polled."
  {:malli/schema [:function [:=> [:cat :seon.host/db] :seon.problems/report]
                  [:=> [:cat :seon.host/db :seon.error/fold] :seon.problems/report]]}
  ([db] (health db {}))
  ([db fold]
   (let [ans (answers db) dead (dead-citations db ans) unnamed (unnamed-sites db ans) pinned (stale-pins db ans)]
     (vary-meta (update (report db fold) :classes assoc
                        :dead-citation {:n (count dead) :rows dead}
                        :unnamed-site {:n (count unnamed) :rows unnamed}
                        :stale-pin {:n (count pinned) :rows pinned})
                assoc :seon.render/schema :seon.problems/report))))
