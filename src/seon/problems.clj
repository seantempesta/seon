(ns seon.problems
  "The one read of every problem Seon recorded (session D2, the session design papers in git history):
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
    :stale-pin                      a pin the wiki states (`pins`: docs/forks.md) that is not
                                    the gitlink root's source import recorded

  `report` reads what was recorded, at a cost that follows the problems. `health` adds the
  derived classes, which read the program's names once (a set): their cost follows the
  citations (seon.problems-test/dead-citations-follow-the-citations)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [datahike.api :as d] [clojure.string :as str] [clojure.set :as set] [seon.merge :as pm] [seon.ref :as ref] [seon.program :as program] [seon.db :as pdb] [seon.docs :as docs] [seon.task :as task] [seon.config :as config]))

(def counted-classes [:platform :eval :agent :effect])
(def ^:private death-statuses task/failed)
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
  last one. The subjects: :seon.error/subject (a var's name, a section's address, or an attribute,
  whose facts are its datoms on any entity), and, for a :seon.docs/refused error from a store
  older than the type's removal, the link's target address and the citing section's (its example's). Such an error is closed too when the citing section
  exists and no longer links to the target (not a dead link of it, not among its references).
  Nothing is written to close it, and a subject that does not exist has changed nothing."
  {:malli/schema [:=> [:cat :seon.host/db :map :int] :boolean]}
  [db r e]
  (let [ex (:seon.error/example r)
        refused? (= :seon.docs/refused (:seon.error/type r))
        [target from] (when refused? [(:seon.ref/address ex) (:seon.section/address ex)])
        facts (fn [s] (if (keyword? s)
                        (d/datoms db :aevt s)
                        (some->> (pdb/eid db (if (symbol? s) :var/name :seon.section/address) s) (d/datoms db :eavt))))
        subjects (remove nil? [(:seon.error/subject r) target from])
        last-tx (reduce max (map :tx (d/datoms db :eavt e)))
        citing (some->> from (pdb/eid db :seon.section/address) (d/pull db [:seon.section/dead-links {:seon.section/references [:seon.section/address]}]))]
    (boolean (or (some (fn [s] (some #(> (:tx %) last-tx) (facts s))) subjects)
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

(defn- derived-row
  "The open row of a derived problem: its signature s, the namespace that owns the fix, its message, its
  evidence (:kind names the class) and the fields of its own, with the answers recorded for the signature."
  {:malli/schema [:=> [:cat :map :string [:maybe :symbol] :string :map :map] :map]}
  [ans s owner message evidence fields]
  (assoc fields :signature s :open? true :message message :producer nil :owner-ns owner :proofs [] :evidence evidence
         :answered-by (get-in ans [:signature s] []) :at-ms nil))

(defn stalled-agents
  "Each agent root observes stalled now (its :seon.agent/stall: a request in flight and no frame
  past the stall bound, seon.agent-proc), as a :stalled row naming what it waits on: open while
  the fact holds, closed by its next frame or its end, which retract it. Cost: the agents
  holding the fact (one AEVT range)."
  {:malli/schema [:=> [:cat :seon.host/db :map] [:vector :seon.problems/row]]}
  [db ans]
  (vec (for [{e :e st :v} (d/datoms db :aevt :seon.agent/stall)
             :let [{id :seon.agent/id pid :seon.agent/pid} (d/pull db [:seon.agent/id :seon.agent/pid] e)
                   {:keys [kind form]} (:last-op st)
                   s (str "stalled " (some-> id name))]]
         (derived-row ans s 'seon.agent-proc
                      (str "agent " (some-> id name) " sent nothing for " (:quiet-ms st) " ms with a request in flight"
                           (when kind (str ": " (name kind) (when form (str " " form)))))
                      {:kind :stalled :agent id :pid pid :quiet-ms (:quiet-ms st) :waits-on (:last-op st)}
                      {:agent id}))))

(defn dead-citations
  "Each name a wiki section cites (:seon.section/names) that names nothing on db
  (seon.ref/names-on-db?, the one rule for a name), and each link it makes that reached no
  section at load (:seon.section/dead-links) and still reaches none, as a :dead-citation row:
  open while it is true, since nothing writes it. Cost: main's var names read once into a set
  and one set lookup per cited name; the rule runs only on a name the set misses; one seek per
  dead link. With only (a set of section entity ids) just those sections are read, a seek per
  cited name and no set (what `caused` asks)."
  {:malli/schema [:function [:=> [:cat :seon.host/db :map] [:vector :seon.problems/row]]
                  [:=> [:cat :seon.host/db :map [:maybe [:set :int]]] [:vector :seon.problems/row]]]}
  ([db ans] (dead-citations db ans nil))
  ([db ans only]
  (let [vars (if only
               (fn [v] (seq (d/datoms db :avet :var/name v)))
               (let [vs (into #{} (map :v) (d/datoms db :aevt :var/name))] #(vs %)))
        row (fn [e k v message evidence]
              (let [a (:seon.section/address (d/pull db [:seon.section/address] e))
                    s (str "dead-citation " a " " v)]
                (merge {:signature s :section a :open? true :message (message a)
                        :producer nil :owner-ns (when (symbol? v) (symbol (namespace v))) :proofs []
                        :evidence (assoc evidence :kind :dead-citation :section a) :answered-by (get-in ans [:signature s] []) :at-ms nil}
                       {k v})))
        datoms (fn [attr] (if only (mapcat #(d/datoms db :eavt % attr) only) (d/datoms db :aevt attr)))]
    (vec (concat
           (for [{:keys [e v]} (datoms :seon.section/names)
                 :when (not (or (vars v) (ref/names-on-db? db v)))]
             (row e :name v #(str % " cites " v ", which names nothing") {:name v}))
           (for [{:keys [e v]} (datoms :seon.section/dead-links)
                 :when (nil? (pdb/eid db :seon.section/address v))]
             (row e :target v #(str % " links to " v ", which is no section") {:target v})))))))

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
  :unnamed-site row. Cost: the forms carrying the fact (aevt) and the page's cited names, once.
  With only (a set of form entity ids) just those forms are read (what `caused` asks)."
  {:malli/schema [:function [:=> [:cat :seon.host/db :map] [:vector :seon.problems/row]]
                  [:=> [:cat :seon.host/db :map [:maybe [:set :int]]] [:vector :seon.problems/row]]]}
  ([db ans] (unnamed-sites db ans nil))
  ([db ans only]
  ;; the law is the program's own source's: its namespaces, read once, when a site asks
  (let [program (delay (program/program-nss db))]
  (vec (for [{:keys [law fact page except]} laws
             :let [page-eids (into #{} (keep (fn [{:keys [e v]}] (when (str/starts-with? v (str page "#")) e))) (d/datoms db :aevt :seon.section/address))
                   named (into #{} (keep (fn [{:keys [e v]}] (when (page-eids e) v))) (d/datoms db :aevt :seon.section/names))
                   ;; the multimethod or protocol a form without a var extends: read only if one asks
                   owners (delay (merge (into {} (map (fn [{:keys [e v]}] [v (:method/multi (d/entity db e))])) (d/datoms db :aevt :method/form))
                                        (into {} (map (fn [{:keys [e v]}] [v (:impl/protocol (d/entity db e))])) (d/datoms db :aevt :impl/form))))]
             fe (distinct (if only (filter (fn [e] (seq (d/datoms db :eavt e fact))) only) (map :e (d/datoms db :aevt fact))))
             :when (program/live? db fe)
             :let [f (d/entity db fe)
                   ;; a catch is attributed to its form: the vars it defines, else the multimethod or
                   ;; protocol it extends, else the form's id: no site drops
                   sites (or (seq (map :var/name (:form/defines f))) (some-> (@owners fe) vector) [(:form/id f)])
                   nsym (:ns/name (:form/ns f))]
             v sites
             ;; the law is Seon's own source's (bin/laws scans src): an agent's app, and a test's
             ;; `is` (which expands to a catch), are not failure points of the system
             :when (and (not (except (str nsym))) (not (named v)) (contains? @program nsym))
             :let [s (str "unnamed-site " (name law) " " v)]]
         (derived-row ans s nsym (str v " has a " (name law) " site that " page " does not name")
                      {:kind :unnamed-site :law law :site v :page page} {:law law :site v :page page}))))))

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
             (derived-row ans s 'seon.docs (str a " states " path " pinned at " sha ", its gitlink is " (or g "none"))
                          {:kind :stale-pin :section a :path path :stated sha :recorded g} {:section a :path path :stated sha :recorded g}))))))

;; ---- the wiki kept true: code that moved under its text, pages nothing links, issues with no page ----

(defn- current-forms
  "{var name form id} of every var of db with a form: three aevt scans, no seek."
  {:malli/schema [:=> [:cat :seon.host/db] [:map-of :qualified-symbol :string]]}
  [db]
  (let [ids (into {} (map (juxt :e :v)) (d/datoms db :aevt :form/id))
        forms (into {} (map (juxt :e :v)) (d/datoms db :aevt :var/form))]
    (into {} (keep (fn [{:keys [e v]}] (some->> (forms e) ids (vector v)))) (d/datoms db :aevt :var/name))))

(defn- value-at
  "The value of attribute a of entity e as of transaction t, or nil when it had none: its datoms of
  [e a] in history db hist (d/history db, which the :seon.host/db schema does not admit), in
  transaction order, a retraction before the assertion of one transaction."
  {:malli/schema [:=> [:cat :any :int :keyword :int] :any]}
  [hist e a t]
  (reduce (fn [v {:keys [added] dv :v}] (if added dv (when (not= v dv) v))) nil
          (sort-by (juxt :tx :added) (filter #(<= (:tx %) t) (d/datoms hist :eavt e a)))))

(defn stale-sections
  "Each section whose text cites a var whose contract or docstring changed since the section was
  last written or reviewed, as a :stale-section row naming what changed (:changed), what it was as
  of then (:was) and what it is (:now): the var's facts (seon.program/meaning) as of the transaction that wrote
  the section's :seon.section/read (seon.docs/load-tx) or its :seon.section/reviewed
  (my.docs/reviewed!), read from history, against now. A body that changed while both stayed is
  not a change of what the section describes. A var that did not exist then (seon.docs/load-tx
  recorded \"absent\", from git) is stale: code now; one whose docstring or contract changed
  after the section's last commit (\"changed\", from git) is stale; one imported after the section was
  read is not. A var that is gone is a dead citation, not this. Open until the section is edited or
  reviewed; attention, never a reason to refuse done. Cost: the read datoms and the vars' and forms'
  datoms in one scan each (a recorded name whose form id is unchanged cannot have changed what it
  says), then per name whose form moved a seek of its var and of its name's and meaning's history. With only
  ({:names :sections}: var names and section entity ids) just the recorded names among those
  names, and every name of those sections, are read (what `caused-split` asks, of what a branch
  changed)."
  {:malli/schema [:function [:=> [:cat :seon.host/db :map] [:vector :seon.problems/row]]
                  [:=> [:cat :seon.host/db :map [:maybe :map]] [:vector :seon.problems/row]]]}
  ([db ans] (stale-sections db ans nil))
  ([db ans only]
   (let [address (into {} (map (juxt :e :v)) (d/datoms db :aevt :seon.section/address))
         reads (if only
                 (keep (fn [{:keys [e v tx]}]
                         (let [vs (if ((:sections only) e) v (select-keys v (filter (:names only) (keys v))))]
                           (when (seq vs) {:e e :v vs :tx tx})))
                       (d/datoms db :aevt :seon.section/read))
                 (map (fn [{:keys [e v tx]}] {:e e :v v :tx tx}) (d/datoms db :aevt :seon.section/read)))
         reviewed (into {} (map (juxt :e :tx)) (d/datoms db :aevt :seon.section/reviewed))
         ;; a contract or docstring changes only with its form's text, so only a recorded name whose
         ;; form moved has its history read
         now-form (if only (docs/read-map db (into #{} (mapcat (comp keys :v)) reads)) (current-forms db))
         hist (d/history db)]
     (vec (concat
            (for [{:keys [e v tx]} reads
                  :let [t (max tx (reviewed e 0))]
                  [n then] v
                  :when (and (now-form n) (not= then (now-form n)))
                  :let [ve (pdb/eid db :var/name n)]
                  :let [absent? (= :absent then)
                        before? (= :changed then)
                        existed? (some #(and (:added %) (<= (:tx %) t)) (d/datoms hist :eavt ve :var/name))
                        was (when existed? (into {} (keep (fn [a] (some->> (value-at hist ve a t) (vector a)))) program/meaning))
                        now (into {} (keep (fn [a] (some->> (get (d/entity db ve) a) (vector a)))) program/meaning)
                        changed (when existed? (filterv #(not= (was %) (now %)) program/meaning))]
                  :when (or absent? before? (seq changed))
                  :let [a (address e) s (str "stale-section " a " " n)
                        fields (cond-> {:section a :name n}
                                 (or absent? before?) (assoc :then then)
                                 (seq changed) (assoc :changed changed :was (select-keys was changed) :now (select-keys now changed)))]]
              (derived-row ans s (symbol (namespace n))
                           (cond
                             absent? (str a " cites " n ", which did not exist when the section was last written: it is code now, read it")
                             before? (str a " describes " n ", whose docstring or contract changed after the section's last commit (git): read it")
                             :else (str a " describes " n ", whose " (str/join " and " (map #(case % :var/contract "contract" :var/doc "docstring") changed))
                                  " changed since: read it, then edit the section or review it"))
                           (assoc fields :kind :stale-section) fields))
            ;; a section whose baseline git could not give is read as of today: said, as attention
            (for [{:keys [e v]} (d/datoms db :aevt :seon.section/baseline-unread)
                  :when (or (nil? only) ((:sections only) e))
                  :let [a (address e) s (str "stale-section " a " baseline")]]
              (derived-row ans s 'seon.docs (str a " was recorded as of today: its baseline could not be taken from git (" v ")")
                           {:kind :stale-section :section a :unread v} {:section a :name nil})))))))

(defn orphan-pages
  "Each page of the wiki that no other page links to, as an :orphan-page row: a page is its sections'
  common path (docs/x.md#heading); it is linked when a section of another page has a reference to one of
  its sections. The map (docs/README.md) is the root and is not judged; a db whose wiki has no map
  has no orphans (unknown). Open until a page or the map links it. Cost: the section and reference
  datoms, once."
  {:malli/schema [:=> [:cat :seon.host/db :map] [:vector :seon.problems/row]]}
  [db ans]
  (let [page-of (into {} (map (fn [{:keys [e v]}] [e (first (str/split v #"#" 2))])) (d/datoms db :aevt :seon.section/address))
        map-page "docs/README.md"]
    (if-not (some #{map-page} (vals page-of))
      []
      (let [linked (into #{} (keep (fn [{:keys [e v]}] (let [from (page-of e) to (page-of v)] (when (and to (not= from to)) to))))
                         (d/datoms db :aevt :seon.section/references))]
        (vec (for [p (sort (set (vals page-of))) :when (and (not= p map-page) (not (linked p)))
                   :let [s (str "orphan-page " p)]]
               (derived-row ans s 'seon.docs (str p " is linked from no other page, not even the map (docs/README.md)")
                            {:kind :orphan-page :page p} {:page p})))))))

(defn leadless-sections
  "Each section of the wiki whose first block is not a paragraph, so it has no lead, as a :leadless-section
  row: its summary is its heading alone and its embedding has no sentence saying what it is about
  (docs/README.md asks each section for a leading sentence that stands alone). Open until the section
  is given one. Cost: the section addresses and the lead datoms, once."
  {:malli/schema [:=> [:cat :seon.host/db :map] [:vector :seon.problems/row]]}
  [db ans]
  (let [led (into #{} (map :e) (d/datoms db :aevt :seon.section/lead))]
    (vec (for [{:keys [e v]} (sort-by :v (d/datoms db :aevt :seon.section/address)) :when (not (led e))
               :let [s (str "leadless-section " v)]]
           (derived-row ans s 'seon.docs (str v " has no lead: its first block is not a paragraph that says what the section is about")
                        {:kind :leadless-section :section v} {:section v})))))

(defn problem-unlinked
  "Each class of classes ({class {:rows ...}}) with an open row whose wiki section (:seon.problems/docs)
  is no stored section, or that declares none, as a :problem-unlinked row: an issue that points
  nowhere is itself a problem, and so is the class of those rows when its own section is missing.
  None while the db holds no wiki (unknown). Cost: one lookup per class with rows."
  {:malli/schema [:=> [:cat :seon.host/db :map :map] [:vector :seon.problems/row]]}
  [db ans classes]
  (if (empty? (d/datoms db :aevt :seon.section/address))
    []
    (let [docs (:seon.problems/docs (contract/properties :seon.problems/class))
          unlinked? (fn [c] (let [page (docs c)] (or (nil? page) (nil? (pdb/eid db :seon.section/address page)))))
          row (fn [c] (let [page (docs c) s (str "problem-unlinked " (name c))]
                        (derived-row ans s 'seon.problems
                                     (if page
                                       (str (name c) " has open problems, and its section " page " is no section of the wiki")
                                       (str (name c) " has open problems and no section of the wiki declared for it (:seon.problems/docs)"))
                                     {:kind :problem-unlinked :class c :page page} {:class c :page page})))
          rows (mapv row (for [[c {:keys [rows]}] (sort-by (comp str key) classes) :when (not= :problem-unlinked c) :when (not= :problem-unlinked c) :when (some #(not (false? (:open? %))) rows) :when (unlinked? c)] c))]
      (cond-> rows (and (seq rows) (unlinked? :problem-unlinked)) (conj (row :problem-unlinked))))))

;; ---- caused: what a branch's own work made true -----------------------------------------

(defn- section-state
  "{address {:e :names :dead :read :reviewed :baseline}} of db's sections, by aevt scans and no pull:
  what a section cites, the links it made that reached nothing, and what closes or opens its
  stale-section rows (the code it read, who reviewed it, a baseline git could not give)."
  {:malli/schema [:=> [:cat :seon.host/db] [:map-of :string :map]]}
  [db]
  (let [group (fn [attr] (reduce (fn [m {:keys [e v]}] (update m e (fnil conj #{}) v)) {} (d/datoms db :aevt attr)))
        one (fn [attr] (into {} (map (juxt :e :v)) (d/datoms db :aevt attr)))
        names (group :seon.section/names) dead (group :seon.section/dead-links)
        reads (one :seon.section/read) reviewed (one :seon.section/reviewed) baseline (one :seon.section/baseline-unread)
        text (one :seon.section/text)]
    (into {} (for [{:keys [e v]} (d/datoms db :aevt :seon.section/address)]
               [v {:e e :names (get names e #{}) :dead (get dead e #{}) :read (reads e) :reviewed (reviewed e) :baseline (baseline e) :text (text e)}]))))

(defn- existing-names
  "The names db's vars and tasks hold (a task id as a symbol): what a citation may name."
  {:malli/schema [:=> [:cat :seon.host/db] [:set :symbol]]}
  [db]
  (into (into #{} (map :v) (d/datoms db :aevt :var/name)) (comp (map :v) (filter #(str/includes? % "/")) (map symbol)) (d/datoms db :aevt :seon.task/id)))

(defn- law-forms
  "{form id {:e :catches :src}} of the forms db holds that carry a law's fact: the facts' datoms in
  one scan each (an AVET seek per form would cost ~33 KB a site)."
  {:malli/schema [:=> [:cat :seon.host/db :keyword] [:map-of :string :map]]}
  [db fact]
  (let [carrying (reduce (fn [m {:keys [e v]}] (update m e (fnil conj #{}) v)) {} (d/datoms db :aevt fact))
        attr (fn [a] (into {} (comp (filter #(contains? carrying (:e %))) (map (juxt :e :v))) (d/datoms db :aevt a)))
        ids (attr :form/id) srcs (attr :form/src)]
    (into {} (for [[e cs] carrying] [(ids e) {:e e :catches cs :src (srcs e)}]))))

(def old-word-kept
  "Files whose text keeps an old word of AGENTS.md section 7a's table (it is the subject, a quoted word of the
  owner's, or another sense), each with its reason: a place no :old-word row is made for. The quoted
  span of an owner's sentence is kept too (`old-word-span`)."
  {"AGENTS.md" "the table names the old words"
   "docs/vocabulary.md" "its rows say `(was X)`"
   "docs/decisions.md" "the owner's rulings quote his words, and their titles stay as stated"
   "resources/seon/tasks/seon.docs.edn" "the rename's plan names the old words"
   "resources/seon/tasks/seon.db.edn" "the stored-names plan names the old words"
   "test/seon/docs_test.clj" "this test names the old words"
   "src/seon/problems.clj" "this class names the old words"
   "test/seon/problems_test.clj" "this class's tests name the old words"
   "src/seon/reader.clj" "a Markdown code fence, CommonMark's word"
   "src/seon/ref.clj" "a Markdown code fence, CommonMark's word"
   "resources/seon/schemas/seon.ref.edn" "the :fence block kind is CommonMark's"
   "test/seon/reader_test.clj" "a Markdown code fence"
   "test/seon/ref_test.clj" "a Markdown code fence"
   "test/seon/agent_test.clj" "the named reader test is about a Markdown code fence"
   "test/seon/program_test.clj" "the named reader test is about a Markdown code fence"
   "test/seon/fixtures.clj" "a reply in a Markdown code fence"
   "src/seon/server.clj" "cites v1's namespace proto.agents.entrance"
   "BAGGAGE.md" "cites a decision by its title, \"No locks, no lane cap, no waiting\""
   "resources/seon/tasks/seon.context.edn" "t5.refusal/* is the name of a past agent's test namespace"})

(def old-word-span
  "The spans of a line that keep an old word: an owner's quoted sentence, Jolt's message and a past image name."
  #"(?i)owner,? [^\"]{0,40}?\"[^\"]*\"|Attempting to call unbound fn|zoom-a8-attempt|\(was attempt\)|collection landing")

(defn old-words
  "The old house words of AGENTS.md section 7a's table (text agents), hyphenated (\"merge train\" ->
  \"merge-train\"), leaving out a row still stored under its old name (\"stored as\")."
  {:malli/schema [:=> [:cat :string] [:set :string]]}
  [agents]
  (let [table (->> (str/split-lines agents)
                   (drop-while #(not (str/starts-with? % "| Old house word")))
                   (drop 2)
                   (take-while #(str/starts-with? % "|")))]
    (set (for [row table :when (not (str/includes? row "stored as"))
               w (str/split (second (str/split row #"\|")) #",")]
           (str/replace (str/trim w) " " "-")))))

(defn old-word-spellings
  "The table's old words as the text spells them, [word [spelling ...]] (\"merge train\" either way); \"admit\"
  and \"land\" are also plain English and Seon's admission, so only names are read for them."
  {:malli/schema [:=> [:cat :string] [:sequential [:tuple :string [:vector :string]]]]}
  [agents]
  (vec (for [w (sort (old-words agents)) :when (not (#{"admit" "land"} w))]
         [w (if (= w "merge-train") ["merge train" "merge-train"] [w])])))

(defn old-word-hits
  "The old words (of words, `old-word-spellings`) each text uses: [[key word] ...], key being the text's
  own, line by line without the spans `old-word-span` keeps. One contracted call however many texts: a
  whole-word match is what (?<!\\w)s s?(?!\\w) matches, found by indexOf (a regex costs ~140 us a line here)."
  {:malli/schema [:=> [:cat [:sequential [:tuple :any :string]] [:sequential [:tuple :string [:vector :string]]]] [:vector [:tuple :any :string]]]}
  [texts words]
  (let [word-char? (fn [c] (let [c (int c)] (or (<= 97 c 122) (<= 48 c 57) (= c 95))))
        whole-word? (fn [^String low ^String s]
                      (let [n (.length low) k (.length s)]
                        (loop [i (.indexOf low s 0)]
                          (when (<= 0 i)
                            (let [e (+ i k)]
                              (or (and (or (zero? i) (not (word-char? (.charAt low (dec i)))))
                                       (or (>= e n) (not (word-char? (.charAt low e)))
                                           (and (= \s (.charAt low e)) (or (>= (inc e) n) (not (word-char? (.charAt low (inc e))))))))
                                  (recur (.indexOf low s (inc i)))))))))]
    (vec (distinct (for [[k text] texts
                         line (str/split-lines text)
                         :let [low (str/lower-case line)]
                         :when (some (fn [[_ ss]] (some #(whole-word? low %) ss)) words)
                         :let [kept (str/lower-case (str/replace line old-word-span ""))]
                         [w ss] words
                         :when (some #(whole-word? kept %) ss)]
                     [k w])))))

(defn old-words-in
  "Each old word the places named use, as an :old-word row naming the place and the word: a var's
  form (its source and docstring), a wiki section's text and a task's plan, each in the file it
  is stored from (`old-word-kept` files are not read). places: {:vars #{var symbols} :sections
  #{addresses} :tasks #{task ids}}. The words are AGENTS.md section 7a's table, read from the checkout
  (what seon.docs-test reads). Open while true. Cost: the named places' texts, a line at a time, in
  one `old-word-hits` call."
  {:malli/schema [:=> [:cat :seon.host/db :map [:map [:vars [:set :symbol]] [:sections [:set :string]] [:tasks [:set :string]]]] [:vector :seon.problems/row]]}
  [db ans {:keys [vars sections tasks]}]
  (let [words (old-word-spellings (slurp (str (config/checkout) "/AGENTS.md")))
        texts (vec (concat
                     (for [v (sort vars)
                           :let [e (:e (first (d/datoms db :avet :var/name v))) form (some-> e (as-> e (:var/form (d/entity db e))))
                                 path (when form (or (:ns/file (:form/ns form)) (str "src/" (namespace v))))]
                           :when (and form (not (old-word-kept path)))]
                       [[:var (str v) path] (:form/src form)])
                     (for [a (sort sections)
                           :let [e (:e (first (d/datoms db :avet :seon.section/address a)))
                                 path (first (str/split a #"#" 2))]
                           :when (and e (not (old-word-kept path)))]
                       [[:section a path] (:seon.section/text (d/pull db [:seon.section/text] e))])
                     (for [t (sort tasks)
                           :let [e (:e (first (d/datoms db :avet :seon.task/id t))) tk (when e (d/pull db [:seon.task/ns :seon.task/plan] e))
                                 path (str "resources/seon/tasks/" (:seon.task/ns tk) ".edn")]
                           :when (and tk (not (old-word-kept path)))]
                       [[:task t path] (:seon.task/plan tk)])))]
    (vec (for [[[kind id path] w] (old-word-hits (filterv (comp some? second) texts) words)
               :let [s (str "old-word " path " " (name kind) " " id " " w)]]
           (derived-row ans s nil (str id " (" path ") uses the old word \"" w "\": AGENTS.md section 7a says what to say instead")
                        {:kind :old-word :path path :word w :place kind :id id} {:path path :word w :place kind :id id})))))

(defn uncited-vars
  "Each of the named vars (a set of symbols, the vars a branch changed) that is public in a
  namespace of the program's own source and has a docstring citing no docs/ section, or none, as
  an :uncited-var row: a function states its mechanism by citing the wiki section that describes
  it (docs/references.md#the-delimiter-picks-the-depth), and does not restate it. A private var
  (defn-) and a test are not judged. Closed by citing a section in the docstring.
  Cost: one seek per named var, and its form's text for a var with no citation."
  {:malli/schema [:=> [:cat :seon.host/db :map [:set :symbol]] [:vector :seon.problems/row]]}
  [db ans names]
  (let [program (delay (program/program-nss db))]
    (vec (for [v (sort names)
               :let [e (pdb/eid db :var/name v) ent (when e (d/entity db e))
                     doc (:var/doc ent) nsym (some-> (namespace v) symbol)]
               :when (and ent (not= :test (:var/kind ent)) (not (and doc (str/includes? doc "docs/")))
                          (contains? @program nsym)
                          (some->> ent :var/form :form/src (re-find #"^\((?:defn|defmacro|def|defmulti)\s")))
               :let [s (str "uncited-var " v)]]
           (derived-row ans s nsym (str v " is public and its docstring cites no docs/ section: cite the one that describes it")
                        {:kind :uncited-var :var v} {:var v})))))

(def caused-classes
  "The derived classes `caused-split` reads: the only ones a branch is judged on (the health step refuses any other). An attention class among them never fails a branch."
  #{:dead-citation :unnamed-site :stale-section :old-word :uncited-var})

(defn- split-rows
  "{:made :closed} of the derived rows (`caused-classes`, with ans's answers) the branch at head has
  that base had not, and base had that head has not, by signature: the candidates `caused-split`
  explains, read on both."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :map :seon.merge/changes] [:map [:made [:vector :map]] [:closed [:vector :map]]]]}
  [base head ans ch]
  (let [bs (section-state base) hs (section-state head)
        bn (existing-names base) hn (existing-names head)
        removed (set/difference bn hn)
        changed (set (for [[a st] hs :when (not= (dissoc st :e) (dissoc (bs a) :e))] a))
        added (set/difference hn bn)
        new-sections (set/difference (set (keys hs)) (set (keys bs)))
        ;; a section is a candidate when what it cites or links to appeared or vanished, or it is itself gone from head
        secs (-> changed
                 (into (for [[a st] hs :when (or (some removed (:names st)) (some added (:names st)) (some new-sections (:dead st)))] a))
                 (into (for [a (keys bs) :when (not (contains? hs a))] a)))
        eids (fn [state] (set (keep #(:e (state %)) secs)))
        page? (fn [a] (some #(str/starts-with? a (str (:page %) "#")) laws))
        all-sites? (some page? changed)
        sites (fn [db state eids-of]
                (if all-sites?
                  (unnamed-sites db ans nil)
                  (mapcat (fn [{:keys [fact]}] (unnamed-sites db ans (eids-of fact))) laws)))
        lf (memoize (fn [db fact] (law-forms db fact)))
        changed-forms (memoize (fn [fact] (let [h (lf head fact) b (lf base fact)]
                                            (set (for [[id st] h :when (not= (dissoc st :e) (dissoc (b id) :e))] id)))))
        ;; the stale sections the branch's change can have made: those citing a var it changed, and the sections that differ
        moved (pm/changed-vars ch)
        ;; the places whose text changed: sections whose text differs, tasks whose plan differs
        plans (fn [db] (into {} (map (juxt :e :v)) (d/datoms db :aevt :seon.task/plan)))
        text-secs (set (for [[a st] hs :when (not= (:text st) (:text (bs a)))] a))
        bp (plans base) hp (plans head)
        task-ids (set (for [[e plan] hp :when (not= plan (bp e))] (:v (first (d/datoms head :eavt e :seon.task/id)))))
        rows (fn [db state forms-of]
               (concat (map #(assoc % :class :dead-citation) (dead-citations db ans (eids state)))
                       (map #(assoc % :class :unnamed-site) (sites db state forms-of))
                       (map #(assoc % :class :stale-section) (stale-sections db ans {:names moved :sections (eids state)}))
                       (map #(assoc % :class :old-word) (old-words-in db ans {:vars moved :sections text-secs :tasks task-ids}))
                       (map #(assoc % :class :uncited-var) (uncited-vars db ans moved))))
        had (vec (rows base bs (fn [fact] (set (keep #(:e ((lf base fact) %)) (changed-forms fact))))))
        has (vec (rows head hs (fn [fact] (set (keep #(:e ((lf head fact) %)) (changed-forms fact))))))
        sigs (fn [rs] (into #{} (map :signature) rs))]
    {:made (vec (remove (comp (sigs had) :signature) has)) :closed (vec (remove (comp (sigs has) :signature) had))}))

(defn caused-split
  "{:caused :attention :rows :closed}: the derived problems the branch at head has that base (its merge base) had not, by
  message (:rows: each with its class, both kinds; :closed: the rows base had and head has not, the same shape): what the branch's own work broke (:caused: the done rule refuses while any is open,
  seon.reconcile/done-explain :open-problem) and what it changed under the wiki (:attention: the
  sections it made stale, which the agent reads and never a reason to refuse; the classes
  :seon.problems/attention declares). An evaluation is never refused (AGENTS: get out of an agent's way).
  Base and head are compared by value, never by tx (a branch's tx counter is its own): the
  sections whose citations or dead links differ, the sections citing a name a var or task lost,
  and the forms carrying a law's fact whose catches or text differ are the candidates, and
  `dead-citations` and `unnamed-sites` read only those, on head and on base; a change to a
  section of a law's page names or unnames any site, so then every site is read. Cost: a scan of
  the sections', var names', task ids' and catch forms' datoms (no pull, no rule), and the rule
  for the candidates only: flat in the rows, citations and sites the branch did not touch
  (seon.problems-test/caused-follows-what-changed). Measured on Seon's own program (300 sections,
  880 citations, 1,735 vars, 30 catch forms) with a var every cite deleted: 11-12 ms against 26-29 ms
  for the two full readings; an unchanged branch 7 ms, the scan's floor."
  {:malli/schema [:function [:=> [:cat :seon.host/db :seon.host/db] [:map [:caused [:vector :string]] [:attention [:vector :string]] [:rows [:vector [:map [:class :keyword] [:message :string]]]]
                                                                     [:closed [:vector [:map [:class :keyword] [:message :string]]]]]]
                  [:=> [:cat :seon.host/db :seon.host/db :seon.merge/changes] [:map [:caused [:vector :string]] [:attention [:vector :string]] [:rows [:vector [:map [:class :keyword] [:message :string]]]]
                                                                                    [:closed [:vector [:map [:class :keyword] [:message :string]]]]]]]}
  ([base head] (caused-split base head (pm/delta-changes base head #{})))
  ([base head ch]
  (let [{:keys [made closed]} (split-rows base head {} ch)
        attention? (:seon.problems/attention (contract/properties :seon.problems/class))
        messages (fn [pred] (vec (sort (into #{} (comp (filter pred) (map :message)) made))))
        shown (fn [rs] (vec (sort-by (juxt (comp str :class) :message) (into #{} (map #(select-keys % [:class :message])) rs))))]
    {:caused (messages (complement (comp attention? :class))) :attention (messages (comp attention? :class))
     :rows (shown made) :closed (shown closed)})))

(defn caused
  "The derived problems the branch at head has that base had not that refuse its done
  (`caused-split`'s :caused), with the branch's changes since base ch when its caller holds them."
  {:malli/schema [:function [:=> [:cat :seon.host/db :seon.host/db] [:set :string]]
                  [:=> [:cat :seon.host/db :seon.host/db :seon.merge/changes] [:set :string]]]}
  ([base head] (into (sorted-set) (:caused (caused-split base head))))
  ([base head ch] (into (sorted-set) (:caused (caused-split base head ch)))))

;; ---- runs ------------------------------------------------------------------------

(def ^:private run-pull
  "What a problem row reads of a run: one pull (its conflicts are read only when it has
  them)."
  [:seon.run/id :seon.run/status :seon.run/outcome :seon.run/reason :seon.run/end-ms {:seon.run/agent [:seon.agent/id]}
   :seon.run/unmerged :seon.run/failures :seon.run/costs :seon.run/error :seon.run/waste :seon.run/task-conflicts
   :seon.run/deferred :seon.run/gate-unread {:seon.run/deferred-results [:seon.proof/test :seon.proof/status]}
   {:seon.run/task [:db/id :seon.task/id :seon.task/ns :seon.task/proofs :seon.task/execution]}
   {:seon.run/conflicts [:seon.merge/key :seon.merge/kind :seon.merge/base :seon.merge/ours :seon.merge/theirs]}])

(defn- task-ready?
  "Is task te one not done whose needs are all done? Here ready deliberately means only that: the
  question is whether its plan can be worked, not whether the reconciler would start it now
  (seon.reconcile's index also asks for a release and no live run, which a db alone does not
  hold). A task waiting on another is judged when its needs are done."
  {:malli/schema [:=> [:cat :seon.host/db :int] :boolean]}
  [db te]
  (and (not (task/done? db te))
       (every? #(task/done? db (:v %)) (d/datoms db :eavt te :seon.task/needs))))

(defn uncited-tasks
  "Each ready :implement task, which holds its design step's plan along :needs (seon.task/accumulate),
  whose accumulated plan cites nothing (no :seon.task/references: the context pulls no var, attribute
  or section for it), that names no proof, or one of whose proofs is still the placeholder
  seon.context writes, as an :uncited-task row with its reason (:no-references, :no-proofs,
  :placeholder-proof). A design step's plan is prose, its input: design writes the cited plan,
  so only the implement step is judged. Closed when the design step's plan cites a name, the
  proofs are named and written. Cost: the task, kind, reference and proof datoms once, then a
  walk of each implement task's needs."
  {:malli/schema [:=> [:cat :seon.host/db :map] [:vector :seon.problems/row]]}
  [db ans]
  (let [kinds (into {} (map (juxt :e :v)) (d/datoms db :aevt :seon.task/kind))
        chain (fn chain [seen e]
                (if (seen e) seen
                    (reduce chain (conj seen e) (map :v (d/datoms db :eavt e :seon.task/needs)))))
        vals-of (fn [es a] (into #{} (mapcat #(map :v (d/datoms db :eavt % a))) es))
        placeholder? (fn [sym] (some-> (pdb/eid db :var/name sym) (->> (d/entity db)) :var/form :form/src
                                      (str/includes? task/placeholder-assertion)))]
    (vec (for [{:keys [e v]} (sort-by :v (d/datoms db :aevt :seon.task/id))
               :when (and (= :implement (kinds e)) (task-ready? db e))
               :let [es (chain #{} e)
                     proofs (vals-of es :seon.task/proofs)
                     reasons (cond-> []
                               (empty? (vals-of es :seon.task/references)) (conj :no-references)
                               (empty? proofs) (conj :no-proofs)
                               (some placeholder? proofs) (conj :placeholder-proof))]
               reason reasons
               :let [s (str "uncited-task " v " " (name reason))]]
           (derived-row ans s 'seon.task
                        (case reason
                          :no-references (str v " is ready and its plan cites no var, attribute or section: the context pulls nothing for it")
                          :no-proofs (str v " is ready and names no proof")
                          :placeholder-proof (str v " is ready and a proof of it is still the placeholder assertion (seon.task/placeholder-assertion)"))
                        {:kind :uncited-task :task v :reason reason} {:task v :reason reason})))))

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
        base {:run aid :task (get-in p [:seon.run/task :seon.task/id]) :agent (get-in p [:seon.run/agent :seon.agent/id]) :status status
              :outcome (:seon.run/outcome p) :reason (:seon.run/reason p) :end-ms (:seon.run/end-ms p)
              :open? (and (not= :cancelled (get-in p [:seon.run/task :seon.task/execution])) (not (task/done? db te))) :owner-ns (get-in p [:seon.run/task :seon.task/ns]) :producer nil :proofs []
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
      ;; merged with its deferred process tests unasked (or no gate checkout declared): open until
      ;; each is recorded passing (my.root/gate!; DECISIONS, Process tests are not automatic)
      (and (= :merged status) (seq (:seon.run/deferred p))
           (not-every? (into #{} (keep #(when (= :pass (:seon.proof/status %)) (:seon.proof/test %))) (:seon.run/deferred-results p))
                       (:seon.run/deferred p)))
      (assoc :unrun [(cond-> (assoc base :open? true :tests (vec (sort-by str (:seon.run/deferred p)))
                            :reason (str "merged with " (count (:seon.run/deferred p)) " process tests unrun")
                            :evidence {:from aid :deferred (vec (sort-by str (:seon.run/deferred p)))})
                       ;; the changed namespaces its gate could not read, and why
                       (:seon.run/gate-unread p) (assoc :unread (:seon.run/gate-unread p)))]))))

(defn- report-with
  "`report` over the answers its caller read; with only (run entity ids) the run classes hold just
  those runs' rows, found without scanning the runs' attributes (`health-moved` carries the rest)."
  {:malli/schema [:=> [:cat :seon.host/db :map :seon.error/fold [:maybe [:set :int]]] :seon.problems/report]}
  [db ans fold only]
  (let [live (into {} (map (fn [{:keys [signature base] n :count}] [signature (+ (or base 0) n)])) (vals fold))
        errors (group-by :class (map #(let [r (error-row db ans %)] (update r :count max (get live (:signature r) 0)))
                                     (es db :seon.error/signature)))
        done? (memoize #(task/done? db %))
        ;; a run of a done task has no open problem but its unrun tests: its history is never pulled
        open-run? (fn [e] (or (seq (d/datoms db :eavt e :seon.run/deferred))
                              (not (done? (:v (first (d/datoms db :eavt e :seon.run/task)))))))
        runs (into (sorted-set)
                       (filter open-run?)
                       (or only
                           (concat (mapcat #(es db %) [:seon.run/failures :seon.run/costs :seon.run/error :seon.run/waste :seon.run/deferred
                                                       :seon.run/conflicts :seon.run/unmerged :seon.run/task-conflicts])
                                   (mapcat (fn [s] (map :e (d/datoms db :avet :seon.run/status s))) (conj death-statuses :behind)))))
        by-class (apply merge-with into (map #(run-rows db ans %) runs))
        failed (mapv #(failed-row db ans %) (es db :seon.task/failure))]
    ^{:seon.render/schema :seon.problems/report}
    {:t (:max-tx db)
     :classes (merge (into {} (for [c counted-classes :let [rows (vec (sort-by (comp - :count) (get errors c)))]]
                                [c {:n (reduce + 0 (map :count rows)) :rows rows}]))
                     (into {} (for [c run-classes :let [rows (vec (sort-by #(- (or (:end-ms %) 0)) (get by-class c)))]]
                                [c {:n (count rows) :rows rows}]))
                     {:failed {:n (count failed) :rows (vec (sort-by #(- (or (:at-ms %) 0)) failed))}})}))

(defn report
  "Every problem recorded on main db, by class: {:t :classes {class {:n :rows}}}. Counted
  classes' :n is their occurrences and their rows run by count; run classes' :n is
  their rows, newest first. With an observer's fold (seon.error/fold), a recorded error's
  count is the true one: a repeat of a written signature is counted in the fold and not
  yet on main."
  {:malli/schema [:function [:=> [:cat :seon.host/db] :seon.problems/report]
                  [:=> [:cat :seon.host/db :seon.error/fold] :seon.problems/report]]}
  ([db] (report db {}))
  ([db fold] (report-with db (answers db) fold nil)))

(defn- health-with
  "db's health from its recorded problems (`report`'s classes) and `derived`, the derived classes' rows by class."
  {:malli/schema [:=> [:cat :seon.host/db :map :map :map] :seon.problems/report]}
  [db ans recorded derived]
  (let [classes (merge recorded (update-vals derived (fn [rows] {:n (count rows) :rows rows})))
        unlinked (problem-unlinked db ans classes)]
    (vary-meta (assoc {:t (:max-tx db)} :classes (assoc classes :problem-unlinked {:n (count unlinked) :rows unlinked}))
               assoc :seon.render/schema :seon.problems/report)))

(defn- other-derived
  "The derived classes `caused-split` does not read, their rows on db."
  {:malli/schema [:=> [:cat :seon.host/db :map] :map]}
  [db ans]
  {:stalled (stalled-agents db ans) :stale-pin (stale-pins db ans) :orphan-page (orphan-pages db ans)
   :leadless-section (leadless-sections db ans) :uncited-task (uncited-tasks db ans)})

(defn health
  "Every open problem on db, one value by class: what was recorded (`report`) and what the
  program's facts make true now (`dead-citations`, `unnamed-sites`, `stale-pins`, `stale-sections`,
  `orphan-pages`, `leadless-sections`, `uncited-tasks`, and `problem-unlinked` over all of them). What the page, an agent's context and
  the done and merge gates read: computed when read, never stored, never polled."
  {:malli/schema [:function [:=> [:cat :seon.host/db] :seon.problems/report]
                  [:=> [:cat :seon.host/db :seon.error/fold] :seon.problems/report]]}
  ([db] (health db {}))
  ([db fold]
   (let [ans (answers db)]
     (health-with db ans (:classes (report-with db ans fold nil)) (assoc (other-derived db ans) :dead-citation (dead-citations db ans)
                                     :unnamed-site (unnamed-sites db ans) :stale-section (stale-sections db ans)
                                     :old-word (old-words-in db ans {:vars (into #{} (map :v) (d/datoms db :aevt :var/name))
                                                                     :sections (into #{} (map :v) (d/datoms db :aevt :seon.section/address))
                                                                     :tasks (into #{} (map :v) (d/datoms db :aevt :seon.task/id))}))))))

(defn- touched-runs
  "The run entities of head the move touched (moved: the datoms that differ between the dbs, as
  `d/datoms-diff` gives them): those with a datom that differs, and every run of a task whose own datoms
  or a run's differ (a task's runs share its done-ness and its cancel)."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :any]] [:set :int]]}
  [head moved]
  (let [moved (into #{} (map :e) moved)
        task-of (fn [e] (cond (seq (d/datoms head :eavt e :seon.task/id)) e
                              (seq (d/datoms head :eavt e :seon.run/id)) (:v (first (d/datoms head :eavt e :seon.run/task)))))]
    (into #{} (comp (keep task-of) (mapcat #(map :e (d/datoms head :avet :seon.run/task %)))) moved)))

(defn health-moved
  "Health of head, given prev, the health of base and moved, the datoms that differ between them (`d/datoms-diff`, which the render proc reads already): the classes `caused-split` reads
  (`caused-classes`) are prev's rows less those base had and head has not, plus those head has and
  base had not (`split-rows`, read where the two differ, each row's answers current), and a run
  class holds prev's rows of the runs the move left alone and the rows of those it touched
  (`touched-runs`), so a move of main costs what it changed, not its closed history; the errors
  and the few cheap derived classes are read anew. Measured on main's own db (514 tasks, 3,472 vars)
  for a one-run change: 8.2 M ticks, 43.6 MB and 77 contracted calls, against 168 M, 462 MB and 3,674 for
  `health` of the head. Equal to `health` of head (seon.problems-test/health-moved-is-health). `health` is
  the same per-class readers over every place, so there is one reader of each class and two selections of
  its candidates, not two implementations."
  {:malli/schema [:=> [:cat :seon.problems/report :seon.host/db :seon.host/db [:sequential :any]] :seon.problems/report]}
  [prev base head moved]
  (let [ans (answers head)
        {:keys [made closed]} (split-rows base head ans (pm/delta-changes base head #{} moved))
        gone (into #{} (map :signature) closed)
        current (fn [row] (assoc row :answered-by (get-in ans (if (:run row) [:from (:run row)] [:signature (:signature row)]) [])))
        carried (fn [c] (into (into [] (comp (remove #(gone (:signature %))) (map current)) (get-in prev [:classes c :rows]))
                              (comp (filter #(= c (:class %))) (map #(dissoc % :class))) made))
        touched (touched-runs head moved)
        ids (into #{} (keep #(:v (first (d/datoms head :eavt % :seon.run/id)))) touched)
        recorded (:classes (report-with head ans {} touched))
        recorded (into recorded (for [c run-classes
                                      :let [rows (into (into [] (comp (remove #(ids (:run %))) (map current)) (get-in prev [:classes c :rows])) (get-in recorded [c :rows]))
                                            rows (vec (sort-by #(- (or (:end-ms %) 0)) rows))]]
                                  [c {:n (count rows) :rows rows}]))]
    (health-with head ans recorded (assoc (other-derived head ans) :dead-citation (carried :dead-citation)
                                          :unnamed-site (carried :unnamed-site) :stale-section (carried :stale-section)
                                          :old-word (carried :old-word)))))

(defn own
  "The open problems of one run, by class and shaped as `report` (so the one render draws it, an
  agent's page and root's alike): the run with entity run-e on db (an agent's branch), its task's
  failure, and the recorded errors last raised in its task. Cost: the run's pull, the task's, and the
  error signatures' last-task datoms with a pull for each match; never main's history."
  {:malli/schema [:=> [:cat :seon.host/db :int] :seon.problems/report]}
  [db run-e]
  (let [ans (answers db)
        te (:v (first (d/datoms db :eavt run-e :seon.run/task)))
        tid (:v (first (d/datoms db :eavt te :seon.task/id)))
        open (fn [rows] (vec (remove #(false? (:open? %)) rows)))
        by-class (merge-with into
                             (run-rows db ans run-e)
                             (when (seq (d/datoms db :eavt te :seon.task/failure)) {:failed [(failed-row db ans te)]})
                             (group-by :class (for [{:keys [e v]} (d/datoms db :aevt :seon.error/last-task) :when (= tid v)] (error-row db ans e))))]
    (vary-meta {:t (:max-tx db)
                :classes (into {} (for [[c rows] by-class :let [rows (open rows)] :when (seq rows)] [c {:n (count rows) :rows rows}]))}
               assoc :seon.render/schema :seon.problems/report)))
