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
    :red :cost                      a task's red assertions, its cost proofs' verdicts
    :merge                          conflicts, unmerged keys, behind, a refused merge
    :dead                           a run whose agent died or was lost
    :error                          a run the turn loop ended on an error (the model)
    :waste                          forms charged to the producer of what was shown
    :task-conflict                  task content a branch and main both changed, differently,
                                    recorded at its agent's end (seon.task/carry-tx)
    :failed                         a task held for root with a failure: a run refused, its
                                    agent dead or its merge refused (seon.reconcile/hold-tx), or
                                    marked failed (my.task/fail!, root or a check): its reason, a
                                    counterexample when marked, its evidence; open while held
    :dead-citation                  a name a wiki section cites (:seon.doc/names) that names
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
  (:require [seon.contract :as contract :refer [defn defn-]] [seon.print.emit :as emit] [datahike.api :as d] [clojure.string :as str] [clojure.set :as set] [seon.merge :as pm] [seon.source :as source] [seon.ref :as ref] [seon.program :as program] [seon.db :as pdb] [seon.doc :as doc] [seon.task :as task] [seon.config :as config]))

(def counted-classes [:platform :eval :agent :effect])
(def ^:private death-statuses task/failed)
(def ended-classes
  "The classes of problem a task's ended work states (task-rows). See docs/reconcile.md#a-task-holds-how-its-work-ended."
  [:red :cost :merge :dead :error :waste :task-conflict :unrun])

(defn- es
  "The entities carrying attribute a (its aevt index: only the entities that have it)."
  {:malli/schema [:=> [:cat :seon.host/db :keyword] [:vector :int]]}
  [db a] (mapv :e (d/datoms db :aevt a)))

(defn answers
  "The tasks on db whose evidence names a problem: {signature [task-id]} and
  {task-id [task-id]} under :signature and :from. A task's evidence about its own
  work (a hold's, seon.reconcile/hold-tx) answers nothing: it is that problem. Reads
  only the tasks that carry evidence (aevt :seon.task/evidence)."
  {:malli/schema [:=> [:cat :seon.host/db] [:map [:signature :map] [:from :map]]]}
  [db]
  (reduce (fn [acc {:keys [e v]}]
            (let [ev v
                  id (:seon.task/id (d/pull db [:seon.task/id] e))
                  own? (= (:from ev) id)]
              (cond-> acc
                (:signature ev) (update-in [:signature (:signature ev)] (fnil conj []) id)
                (and (:from ev) (not own?)) (update-in [:from (:from ev)] (fnil conj []) id))))
          {:signature {} :from {}} (d/datoms db :aevt :seon.task/evidence)))

;; ---- recorded errors ------------------------------------------------------------------

(defn- closed?
  "Is the recorded error r (entity e) closed: a subject of it has a fact written after the error's
  last one. The subjects: :seon.error/subject (a var's name, a section's address, or an attribute,
  whose facts are its datoms on any entity), and, for a :seon.doc/refused error from a store
  older than the type's removal, the link's target address and the citing section's (its example's). Such an error is closed too when the citing section
  exists and no longer links to the target (not a dead link of it, not among its references).
  Nothing is written to close it, and a subject that does not exist has changed nothing."
  {:malli/schema [:=> [:cat :seon.host/db :map :int] :boolean]}
  [db r e]
  (let [ex (:seon.error/example r)
        refused? (= :seon.doc/refused (:seon.error/type r))
        [target from] (when refused? [(:seon.ref/address ex) (:seon.doc/address ex)])
        facts (fn [s] (if (keyword? s)
                        (d/datoms db :aevt s)
                        (some->> (pdb/eid db (if (symbol? s) :var/name :seon.doc/address) s) (d/datoms db :eavt))))
        subjects (remove nil? [(:seon.error/subject r) target from])
        last-tx (reduce max (map :tx (d/datoms db :eavt e)))
        citing (some->> from (pdb/eid db :seon.doc/address) (d/pull db [:seon.doc/dead-links {:seon.doc/references [:seon.doc/address]}]))]
    (boolean (or (some (fn [s] (some #(> (:tx %) last-tx) (facts s))) subjects)
                 (and citing (not-any? #{target} (:seon.doc/dead-links citing))
                      (not-any? #(= target (:seon.doc/address %)) (:seon.doc/references citing)))))))

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
  "Each agent root observes stalled now (its current process's :seon.process/stall, seon.task/newest-process:
  a request in flight and no frame past the stall bound, seon.agent-proc), as a :stalled row naming
  what it waits on: open while the fact holds, closed by its next frame or its end, which retract
  it; a stall left on an earlier process of the agent is no row. Cost: the processes holding the
  fact (one AEVT range) and each one's agent's processes."
  {:malli/schema [:=> [:cat :seon.host/db :map] [:vector :seon.problems/row]]}
  [db ans]
  (vec (for [{e :e st :v} (d/datoms db :aevt :seon.process/stall)
             :let [p (d/entity db e)]
             :when (= e (:db/id (task/newest-process (seq (:seon.process/_agent (:seon.process/agent p))))))
             :let [{pid :seon.process/pid {id :seon.agent/id} :seon.process/agent} (d/pull db [:seon.process/pid {:seon.process/agent [:seon.agent/id]}] e)
                   {:keys [kind form]} (:last-op st)
                   s (str "stalled " (some-> id name))]]
         (derived-row ans s 'seon.agent-proc
                      (str "agent " (some-> id name) " sent nothing for " (:quiet-ms st) " ms with a request in flight"
                           (when kind (str ": " (name kind) (when form (str " " form)))))
                      {:kind :stalled :agent id :pid pid :quiet-ms (:quiet-ms st) :waits-on (:last-op st)}
                      {:agent id}))))

(defn dead-citations
  "Each name a wiki section cites (:seon.doc/names) that names nothing on db
  (seon.ref/names-on-db?, the one rule for a name), and each link it makes that reached no
  section at load (:seon.doc/dead-links) and still reaches none, as a :dead-citation row:
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
              (let [a (:seon.doc/address (d/pull db [:seon.doc/address] e))
                    s (str "dead-citation " a " " v)]
                (merge {:signature s :section a :open? true :message (message a)
                        :producer nil :owner-ns (when (symbol? v) (symbol (namespace v))) :proofs []
                        :evidence (assoc evidence :kind :dead-citation :section a) :answered-by (get-in ans [:signature s] []) :at-ms nil}
                       {k v})))
        datoms (fn [attr] (if only (mapcat #(d/datoms db :eavt % attr) only) (d/datoms db :aevt attr)))]
    (vec (concat
           (for [{:keys [e v]} (datoms :seon.doc/names)
                 :when (not (or (vars v) (ref/names-on-db? db v)))]
             (row e :name v #(str % " cites " v ", which names nothing") {:name v}))
           (for [{:keys [e v]} (datoms :seon.doc/dead-links)
                 :when (nil? (pdb/eid db :seon.doc/address v))]
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
  failure points: a deftest's `is` expands to a catch), whose name no section of the page cites (:seon.doc/names), as an
  :unnamed-site row. Cost: the forms carrying the fact (aevt) and the page's cited names, once.
  With only (a set of form entity ids) just those forms are read (what `caused` asks)."
  {:malli/schema [:function [:=> [:cat :seon.host/db :map] [:vector :seon.problems/row]]
                  [:=> [:cat :seon.host/db :map [:maybe [:set :int]]] [:vector :seon.problems/row]]]}
  ([db ans] (unnamed-sites db ans nil))
  ([db ans only]
  ;; the law is the program's own source's: its namespaces, read once, when a site asks
  (let [program (delay (program/program-nss db))]
  (vec (for [{:keys [law fact page except]} laws
             :let [;; the page's cited names, read when a candidate site asks (a branch that changed no catching form reads nothing)
                   named-set (delay (into #{} (comp (map :e) (mapcat #(d/datoms db :eavt % :seon.doc/names)) (map :v))
                                          (d/index-range db {:attrid :seon.doc/address :start (str page "#") :end (str page "#\uffff")})))
                   named (fn [v] (@named-set v))
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
           (for [{a :v e :e} (d/datoms db :aevt :seon.doc/address) :when (str/starts-with? a (str page "#"))
                 [_ path sha] (re-seq line (or (:seon.doc/text (d/pull db [:seon.doc/text] e)) ""))
                 :let [g (get recorded path)] :when (not (some-> g (str/starts-with? sha)))
                 :let [s (str "stale-pin " a " " path)]]
             (derived-row ans s 'seon.doc (str a " states " path " pinned at " sha ", its gitlink is " (or g "none"))
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
  the section's :seon.doc/read (seon.doc/load-tx) or its :seon.doc/reviewed
  (my.doc/reviewed!), read from history, against now. A body that changed while both stayed is
  not a change of what the section describes. A var that did not exist then (seon.doc/load-tx
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
   (let [;; with only, the sections it names and those that reference a var of its names are sought by entity
         ;; (a reference is an AVET entry), never every section scanned
         es (when only (into (set (:sections only))
                             (for [n (:names only) :let [ve (pdb/eid db :var/name n)] :when ve
                                   dt (d/datoms db :avet :seon.doc/references ve)]
                               (:e dt))))
         address (if only
                   (into {} (for [e es] [e (:v (first (d/datoms db :eavt e :seon.doc/address)))]))
                   (into {} (map (juxt :e :v)) (d/datoms db :aevt :seon.doc/address)))
         reads (if only
                 (keep (fn [{:keys [e v tx]}]
                         (let [vs (if ((:sections only) e) v (select-keys v (filter (:names only) (keys v))))]
                           (when (seq vs) {:e e :v vs :tx tx})))
                       (mapcat #(d/datoms db :eavt % :seon.doc/read) es))
                 (map (fn [{:keys [e v tx]}] {:e e :v v :tx tx}) (d/datoms db :aevt :seon.doc/read)))
         reviewed (if only
                    (into {} (for [e es dt (d/datoms db :eavt e :seon.doc/reviewed)] [e (:tx dt)]))
                    (into {} (map (juxt :e :tx)) (d/datoms db :aevt :seon.doc/reviewed)))
         ;; a contract or docstring changes only with its form's text, so only a recorded name whose
         ;; form moved has its history read
         now-form (if only (doc/read-map db (into #{} (mapcat (comp keys :v)) reads)) (current-forms db))
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
            (for [{:keys [e v]} (d/datoms db :aevt :seon.doc/baseline-unread)
                  :when (or (nil? only) ((:sections only) e))
                  :let [a (address e) s (str "stale-section " a " baseline")]]
              (derived-row ans s 'seon.doc (str a " was recorded as of today: its baseline could not be taken from git (" v ")")
                           {:kind :stale-section :section a :unread v} {:section a :name nil})))))))

(defn orphan-pages
  "Each page of the wiki that no other page links to, as an :orphan-page row: a page is its sections'
  common path (docs/x.md#heading); it is linked when a section of another page has a reference to one of
  its sections. The map (docs/README.md) is the root and is not judged; a db whose wiki has no map
  has no orphans (unknown). Open until a page or the map links it. Cost: the section and reference
  datoms, once."
  {:malli/schema [:=> [:cat :seon.host/db :map] [:vector :seon.problems/row]]}
  [db ans]
  (let [page-of (into {} (map (fn [{:keys [e v]}] [e (first (str/split v #"#" 2))])) (d/datoms db :aevt :seon.doc/address))
        map-page "docs/README.md"]
    (if-not (some #{map-page} (vals page-of))
      []
      (let [linked (into #{} (keep (fn [{:keys [e v]}] (let [from (page-of e) to (page-of v)] (when (and to (not= from to)) to))))
                         (d/datoms db :aevt :seon.doc/references))]
        (vec (for [p (sort (set (vals page-of))) :when (and (not= p map-page) (not (linked p)))
                   :let [s (str "orphan-page " p)]]
               (derived-row ans s 'seon.doc (str p " is linked from no other page, not even the map (docs/README.md)")
                            {:kind :orphan-page :page p} {:page p})))))))

(defn leadless-sections
  "Each section of the wiki whose first block is not a paragraph, so it has no lead, as a :leadless-section
  row: its summary is its heading alone and its embedding has no sentence saying what it is about
  (docs/README.md asks each section for a leading sentence that stands alone). Open until the section
  is given one. Cost: the section addresses and the lead datoms, once."
  {:malli/schema [:=> [:cat :seon.host/db :map] [:vector :seon.problems/row]]}
  [db ans]
  (let [led (into #{} (map :e) (d/datoms db :aevt :seon.doc/lead))]
    (vec (for [{:keys [e v]} (sort-by :v (d/datoms db :aevt :seon.doc/address)) :when (not (led e))
               :let [s (str "leadless-section " v)]]
           (derived-row ans s 'seon.doc (str v " has no lead: its first block is not a paragraph that says what the section is about")
                        {:kind :leadless-section :section v} {:section v})))))

(defn problem-unlinked
  "Each class of classes ({class {:rows ...}}) with an open row whose wiki section (:seon.problems/docs)
  is no stored section, or that declares none, as a :problem-unlinked row: an issue that points
  nowhere is itself a problem, and so is the class of those rows when its own section is missing.
  None while the db holds no wiki (unknown). Cost: one lookup per class with rows."
  {:malli/schema [:=> [:cat :seon.host/db :map :map] [:vector :seon.problems/row]]}
  [db ans classes]
  (if (empty? (d/datoms db :aevt :seon.doc/address))
    []
    (let [docs (:seon.problems/docs (contract/properties :seon.problems/class))
          unlinked? (fn [c] (let [page (docs c)] (or (nil? page) (nil? (pdb/eid db :seon.doc/address page)))))
          row (fn [c] (let [page (docs c) s (str "problem-unlinked " (name c))]
                        (derived-row ans s 'seon.problems
                                     (if page
                                       (str (name c) " has open problems, and its section " page " is no section of the wiki")
                                       (str (name c) " has open problems and no section of the wiki declared for it (:seon.problems/docs)"))
                                     {:kind :problem-unlinked :class c :page page} {:class c :page page})))
          rows (mapv row (for [[c {:keys [rows]}] (sort-by (comp str key) classes) :when (not= :problem-unlinked c) :when (not= :problem-unlinked c) :when (some #(not (false? (:open? %))) rows) :when (unlinked? c)] c))]
      (cond-> rows (and (seq rows) (unlinked? :problem-unlinked)) (conj (row :problem-unlinked))))))

;; ---- caused: what a branch's own work made true -----------------------------------------

(def ^:private section-attrs
  "The attributes `section-at` reads of a section: what a stale-section, dead-citation or old-word row of it depends on (docs/health.md#a-stale-section)."
  [:seon.doc/address :seon.doc/names :seon.doc/dead-links :seon.doc/read :seon.doc/reviewed
   :seon.doc/baseline-unread :seon.doc/text])

(defn- section-at
  "{:e :names :dead :read :reviewed :baseline :text} of the section addressed a on db, by entity seeks and no
  pull: what it cites, the links it made that reached nothing, and what closes or opens its stale-section rows
  (the code it read, who reviewed it, a baseline git could not give); nil when db holds none."
  {:malli/schema [:=> [:cat :seon.host/db :string] [:maybe :map]]}
  [db a]
  (when-let [e (pdb/eid db :seon.doc/address a)]
    (let [one (fn [attr] (:v (first (d/datoms db :eavt e attr))))
          many (fn [attr] (into #{} (map :v) (d/datoms db :eavt e attr)))]
      {:e e :names (many :seon.doc/names) :dead (many :seon.doc/dead-links) :read (one :seon.doc/read)
       :reviewed (one :seon.doc/reviewed) :baseline (one :seon.doc/baseline-unread) :text (one :seon.doc/text)})))

(defn- name-exists?
  "Whether db holds a var named n or a task whose id is n (a citation may name either)."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-symbol] :boolean]}
  [db n]
  (boolean (or (seq (d/datoms db :avet :var/name n)) (seq (d/datoms db :avet :seon.task/id (str n))))))

(defn- law-form-at
  "{:e :catches :src} of the form with id id on db when it carries the law's fact, else nil."
  {:malli/schema [:=> [:cat :seon.host/db :keyword :string] [:maybe :map]]}
  [db fact id]
  (when-let [e (pdb/eid db :form/id id)]
    (when-let [cs (seq (map :v (d/datoms db :eavt e fact)))]
      {:e e :catches (set cs) :src (:v (first (d/datoms db :eavt e :form/src)))})))

(def old-word-kept
  "Files whose text keeps an old word of AGENTS.md section 7a's table (it is the subject, a quoted word of the
  owner's, or another sense), each with its reason: a place no :old-word row is made for. The quoted
  span of an owner's sentence is kept too (`old-word-span`)."
  {"AGENTS.md" "the table names the old words"
   "docs/vocabulary.md" "its rows say `(was X)`"
   "docs/decisions.md" "the owner's rulings quote his words, and their titles stay as stated"
   "resources/seon/tasks/seon.doc.edn" "the rename's plan names the old words"
   "resources/seon/tasks/seon.db.edn" "the stored-names plan names the old words"
   "test/seon/doc_test.clj" "this test names the old words"
   "src/seon/problems.clj" "this class names the old words"
   "test/seon/problems_test.clj" "this class's tests name the old words"
   "src/seon/server.clj" "cites v1's namespace proto.agents.entrance"
   "BAGGAGE.md" "cites a decision by its title, \"No locks, no lane cap, no waiting\""
   "resources/seon/tasks/seon.context.edn" "t5.refusal/* is the name of a past agent's test namespace"})

(def old-word-span
  "The spans of a line that keep an old word: an owner's quoted sentence, Jolt's message and a past image name."
  #"(?i)owner,? [^\"]{0,40}?\"[^\"]*\"|Attempting to call unbound fn|zoom-a8-attempt|\(was attempt\)|collection landing")

(defn- old-word-rows
  "AGENTS.md section 7a's table (text agents) as [old-word say-instead] pairs, each old word hyphenated
  (\"merge train\" -> \"merge-train\"), leaving out a row still stored under its old name (\"stored as\")."
  {:malli/schema [:=> [:cat :string] [:vector [:tuple :string :string]]]}
  [agents]
  (let [table (->> (str/split-lines agents)
                   (drop-while #(not (str/starts-with? % "| Old house word")))
                   (drop 2)
                   (take-while #(str/starts-with? % "|")))]
    (vec (for [row table :when (not (str/includes? row "stored as"))
               :let [[_ olds instead] (str/split row #"\|")]
               w (str/split olds #",")]
           [(str/replace (str/trim w) " " "-") (str/trim (str instead))]))))

(defn old-words
  "The old house words of AGENTS.md section 7a's table (text agents), hyphenated (\"merge train\" ->
  \"merge-train\"), leaving out a row still stored under its old name (\"stored as\")."
  {:malli/schema [:=> [:cat :string] [:set :string]]}
  [agents]
  (set (map first (old-word-rows agents))))

(defn- spellings-of
  "The old words of the table's rows (`old-word-rows`) as the text spells them, [word [spelling ...]]
  (\"merge train\" either way); \"admit\" and \"land\" are also plain English and Seon's admission, so only names
  are read for them."
  {:malli/schema [:=> [:cat [:vector [:tuple :string :string]]] [:sequential [:tuple :string [:vector :string]]]]}
  [rows]
  (vec (for [w (sort (set (map first rows))) :when (not (#{"admit" "land"} w))]
         [w (if (= w "merge-train") ["merge train" "merge-train"] [w])])))

(defn old-word-spellings
  "The table's old words of AGENTS.md text agents as the text spells them, [word [spelling ...]] (`spellings-of`)."
  {:malli/schema [:=> [:cat :string] [:sequential [:tuple :string [:vector :string]]]]}
  [agents]
  (spellings-of (old-word-rows agents)))

(defn old-word-hits
  "The old words (of words, `old-word-spellings`) each text uses: [[key word] ...], key being the text's
  own, line by line without the spans `old-word-span` keeps. One contracted call however many texts: a
  whole-word match is what (?<!\\w)s s?(?!\\w) matches, found by indexOf (a regex costs ~140 us a line here).
  Each text is lowered once and searched whole; only a line a spelling occurs in is cut and checked, so
  the cost is the texts' length and the lines that hit, not a copy of every line. A text whose lowered
  form differs in length is read line by line, by the same check. See docs/health.md#cost."
  {:malli/schema [:=> [:cat [:sequential [:tuple :any :string]] [:sequential [:tuple :string [:vector :string]]]] [:vector [:tuple :any :string]]]}
  [texts words]
  (let [word-char? (fn [c] (let [c (int c)] (or (<= 97 c 122) (<= 48 c 57) (= c 95))))
        at? (fn [^String low ^String s i]
              (let [n (.length low) e (+ i (.length s))]
                (and (or (zero? i) (not (word-char? (.charAt low (dec i)))))
                     (or (>= e n) (not (word-char? (.charAt low e)))
                         (and (= \s (.charAt low e)) (or (>= (inc e) n) (not (word-char? (.charAt low (inc e))))))))))
        whole-word? (fn [^String low ^String s]
                      (loop [i (.indexOf low s 0)]
                        (when (<= 0 i) (or (at? low s i) (recur (.indexOf low s (inc i)))))))
        spellings (vec (distinct (mapcat second words)))
        ;; a line some spelling is in (low, lowered), then each word its kept text uses
        hits (fn [k ^String line ^String low]
               (when (some (fn [[_ ss]] (some #(whole-word? low %) ss)) words)
                 (let [kept (str/lower-case (str/replace line old-word-span ""))]
                   (for [[w ss] words :when (some #(whole-word? kept %) ss)] [k w]))))
        ;; the [start end] of each line of low a spelling occurs in, in order: a loop that allocates
        ;; only at a hit (a lazy seq a spelling cost ~46 KB a text, 194 MB over a program's forms)
        spans (fn [^String low]
                (let [n (.length low)
                      found (reduce (fn [acc ^String s]
                                      (loop [i (.indexOf low s 0) acc acc]
                                        (if (neg? i)
                                          acc
                                          (recur (.indexOf low s (inc i))
                                                 (if (at? low s i)
                                                   (let [st (inc (.lastIndexOf low "\n" (int i)))
                                                         e (let [e (.indexOf low "\n" (int i))] (if (neg? e) n e))
                                                         e (if (and (< st e) (= \return (.charAt low (dec e)))) (dec e) e)]
                                                     (conj acc [st e]))
                                                   acc)))))
                                    [] spellings)]
                  (if (seq found) (into (sorted-set) found) found)))]
    ;; each text lowered once and searched whole, a line cut only where a spelling occurs; a text whose
    ;; lowered form changes length (its offsets would not be the text's) read line by line
    (vec (distinct (for [[k ^String text] texts
                         :let [^String low (str/lower-case text)]
                         hit (if (= (.length low) (.length text))
                               (mapcat (fn [[st e]] (hits k (subs text st e) (subs low st e))) (spans low))
                               (mapcat #(hits k % (str/lower-case %)) (str/split-lines text)))]
                     hit)))))

(defn- place-texts
  "The texts of the places named, [[kind id path] text] in old-words-in's order (vars, sections, tasks,
  each sorted), `old-word-kept` files left out: a var's form source in its namespace's file, a section's
  text in its page, a task's plan in its file. all? (every place on db, health's read): each attribute
  scanned once and joined by entity (29 MB over a program's 4,017 vars, against 164 MB seeking each var's
  name, form and namespace in turn); else each named place sought (a move's few). See docs/health.md#cost."
  {:malli/schema [:=> [:cat :seon.host/db [:map [:vars [:set :symbol]] [:sections [:set :string]] [:tasks [:set :string]] [:all? {:optional true} :boolean]]] [:vector [:tuple :any [:maybe :string]]]]}
  [db {:keys [vars sections tasks all?]}]
  (let [;; {e v}, an attribute's first value an entity holds, and {v e}, the first entity holding a value
        by-e (fn [a] (reduce (fn [m dt] (if (contains? m (:e dt)) m (assoc m (:e dt) (:v dt)))) {} (d/datoms db :aevt a)))
        by-v (fn [a] (reduce (fn [m dt] (if (contains? m (:v dt)) m (assoc m (:v dt) (:e dt)))) {} (d/datoms db :aevt a)))
        [var-e form-of src ns-of file sec-e text task-e task-ns plan]
        (if all?
          [(by-v :var/name) (by-e :var/form) (by-e :form/src) (by-e :form/ns) (by-e :ns/file)
           (by-v :seon.doc/address) (by-e :seon.doc/text) (by-v :seon.task/id) (by-e :seon.task/ns) (by-e :seon.task/plan)]
          (let [one (fn [a] (fn [e] (:v (first (d/datoms db :eavt e a)))))
                eid (fn [a] (fn [v] (:e (first (d/datoms db :avet a v)))))]
            [(eid :var/name) (one :var/form) (one :form/src) (one :form/ns) (one :ns/file)
             (eid :seon.doc/address) (one :seon.doc/text) (eid :seon.task/id) (one :seon.task/ns) (one :seon.task/plan)]))]
    (vec (concat
           (for [v (sort vars)
                 :let [form (some-> (var-e v) form-of)
                       path (when form (or (some-> (ns-of form) file) (str "src/" (namespace v))))]
                 :when (and form (not (old-word-kept path)))]
             [[:var (str v) path] (src form)])
           (for [a (sort sections)
                 :let [e (sec-e a) path (first (str/split a #"#" 2))]
                 :when (and e (not (old-word-kept path)))]
             [[:section a path] (text e)])
           (for [t (sort tasks)
                 :let [e (task-e t) path (when e (str "resources/seon/tasks/" (task-ns e) ".edn"))]
                 :when (and e (not (old-word-kept path)))]
             [[:task t path] (plan e)])))))

(defn old-words-in
  "Each old word the places named use, as an :old-word row naming the place and the word: a var's
  form (its source and docstring), a wiki section's text and a task's plan, each in the file it
  is stored from (`old-word-kept` files are not read). places: {:vars #{var symbols} :sections
  #{addresses} :tasks #{task ids}}, and :all? when they are every place db holds (`place-texts`). The
  words are AGENTS.md section 7a's table, read from the checkout (what seon.doc-test reads). Open while
  true. Cost: the named places' texts, in one `old-word-hits` call."
  {:malli/schema [:=> [:cat :seon.host/db :map [:map [:vars [:set :symbol]] [:sections [:set :string]] [:tasks [:set :string]] [:all? {:optional true} :boolean]]] [:vector :seon.problems/row]]}
  [db ans {:keys [vars sections tasks] :as places}]
  (if (and (empty? vars) (empty? sections) (empty? tasks))
    []
  (let [rows (old-word-rows (slurp (str (config/checkout) "/AGENTS.md")))
        words (spellings-of rows)
        ;; what to say instead, so the row says it
        instead (into {} rows)
        texts (place-texts db places)]
    (vec (for [[[kind id path] w] (old-word-hits (filterv (comp some? second) texts) words)
               :let [s (str "old-word " path " " (name kind) " " id " " w)]]
           (derived-row ans s nil (str id " (" path ") uses the old word \"" w "\": say instead " (get instead w "what AGENTS.md section 7a's table says") " (AGENTS.md section 7a)")
                        {:kind :old-word :path path :word w :place kind :id id} {:path path :word w :place kind :id id}))))))

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
                          (contains? #{"defn" "defmacro" "def" "defmulti"} (some->> ent :var/form :form/src (source/first-head nsym))))
               :let [s (str "uncited-var " v)]]
           (derived-row ans s nsym (str v " is public and its docstring cites no docs/ section: cite the one that describes it")
                        {:kind :uncited-var :var v} {:var v})))))

(defn long-forms
  "Each of the named vars (a set of symbols, the vars a branch changed), in source or in a test, whose
  form's text is longer than :seon.config.shrink/form-tokens (as the model reads it: seon.print.emit/chars-tokens), as a :long-form row with its tokens and the
  bound: a long form is split by what it does (docs/health.md#a-form-too-long). Closed by splitting the
  form under the bound. Cost: one seek per named var and its form's text length."
  {:malli/schema [:=> [:cat :seon.host/db :map [:set :symbol]] [:vector :seon.problems/row]]}
  [db ans names]
  ;; a form's chars against the bound's (the printer's chars a token), so a short form costs no estimate
  (let [bound (config/value :seon.config.shrink/form-tokens)
        over (* bound emit/chars-per-token)]
    (vec (for [v (sort names)
               :let [e (pdb/eid db :var/name v) src (some-> e (as-> e (d/entity db e)) :var/form :form/src)]
               :when (> (count src) over)
               :let [n (emit/chars-tokens (count src)) s (str "long-form " v) nsym (some-> (namespace v) symbol)]]
           (derived-row ans s nsym (str v " is " n " tokens, over the bound of " bound ": split it by what it does")
                        {:kind :long-form :var v :tokens n :bound bound} {:var v :tokens n :bound bound})))))

(def caused-classes
  "The derived classes `caused-split` reads: the only ones a branch is judged on (the health step refuses any other). An attention class among them never fails a branch."
  #{:dead-citation :unnamed-site :stale-section :old-word :uncited-var :long-form})

(defn caused-datoms
  "The datoms that differ between base and head in the attributes `caused-split` reads (a section's, the var
  names, the task ids and plans, the catches and text of forms), one datahike.api/datoms-diff range each: what
  its 2- and 3-arity read when they are given none. A caller that read the whole :eavt diff already passes
  that instead (docs/cost.md#a-diff-per-attribute-costs-a-descent-each)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db] [:vector :seon.host/datom]]}
  [base head]
  (into [] (mapcat (fn [a] (apply concat (d/datoms-diff base head :aevt [a]))))
        (concat section-attrs [:var/name :seon.task/id :seon.task/plan :form/src] (map :fact laws))))

(defn- split-rows
  "{:made :closed} of the derived rows (`caused-classes`, with ans's answers) the branch at head has
  that base had not, and base had that head has not, by signature: the candidates `caused-split`
  explains, read on both. The candidates come from moved, the datoms that differ between base and head
  (`caused-datoms`, or the whole diff): the sections, names, plans and forms they touch, each looked up
  by entity on both sides; nothing is scanned."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :map :seon.merge/changes [:sequential :seon.host/datom]] [:map [:made [:vector :map]] [:closed [:vector :map]]]]}
  [base head ans ch moved]
  (let [by-attr (group-by :a moved)
        ents (fn [attrs] (into #{} (comp (mapcat by-attr) (map :e)) attrs))
        first-v (fn [db e a] (:v (first (d/datoms db :eavt e a))))
        either (fn [e a] (or (first-v head e a) (first-v base e a)))
        bs (memoize (fn [a] (section-at base a))) hs (memoize (fn [a] (section-at head a)))
        cand (into #{} (keep #(either % :seon.doc/address)) (ents section-attrs))
        changed (set (for [a cand :let [h (hs a)] :when (and h (not= (dissoc h :e) (dissoc (bs a) :e)))] a))
        new-sections (set (for [a cand :when (and (hs a) (not (bs a)))] a))
        gone (set (for [a cand :when (and (bs a) (not (hs a)))] a))
        ;; the names a var or task gained or lost
        named (set (concat (map :v (by-attr :var/name))
                           (for [{:keys [v]} (by-attr :seon.task/id) :when (str/includes? v "/")] (symbol v))))
        removed (set (filter #(and (name-exists? base %) (not (name-exists? head %))) named))
        added (set (filter #(and (name-exists? head %) (not (name-exists? base %))) named))
        address-of (fn [e] (first-v head e :seon.doc/address))
        ;; a section is a candidate when what it cites or links to appeared or vanished, or it is itself gone from head
        cited (when (or (seq removed) (seq added))
                (into #{} (comp (mapcat #(d/datoms head :avet :seon.doc/names %)) (keep #(address-of (:e %)))) (into removed added)))
        dead (when (seq new-sections)
               (into #{} (comp (filter #(new-sections (:v %))) (keep #(address-of (:e %)))) (d/datoms head :aevt :seon.doc/dead-links)))
        secs (-> changed (into cited) (into dead) (into gone))
        eids (fn [state] (into #{} (keep #(:e (state %))) secs))
        page? (fn [a] (some #(str/starts-with? a (str (:page %) "#")) laws))
        all-sites? (some page? changed)
        sites (fn [db state eids-of]
                (if all-sites?
                  (unnamed-sites db ans nil)
                  (mapcat (fn [{:keys [fact]}] (unnamed-sites db ans (eids-of fact))) laws)))
        ;; the forms carrying a law's fact whose catches or text differ
        changed-forms (memoize (fn [fact]
                                 (set (for [id (into #{} (keep #(either % :form/id)) (ents [fact :form/src]))
                                            :let [h (law-form-at head fact id)]
                                            :when (and h (not= (dissoc h :e) (dissoc (law-form-at base fact id) :e)))]
                                        id))))
        ;; the stale sections the branch's change can have made: those citing a var it changed, and the sections that differ
        moved-vars (pm/changed-vars ch)
        ;; the places whose text changed: sections whose text differs, tasks whose plan differs
        text-secs (set (for [a cand :let [h (hs a)] :when (and h (not= (:text h) (:text (bs a))))] a))
        task-ids (set (for [e (ents [:seon.task/plan])
                            :let [plan (first-v head e :seon.task/plan)]
                            :when (and plan (not= plan (first-v base e :seon.task/plan)))]
                        (first-v head e :seon.task/id)))
        rows (fn [db state forms-of]
               (concat (map #(assoc % :class :dead-citation) (dead-citations db ans (eids state)))
                       (map #(assoc % :class :unnamed-site) (sites db state forms-of))
                       (map #(assoc % :class :stale-section) (stale-sections db ans {:names moved-vars :sections (eids state)}))
                       (map #(assoc % :class :old-word) (old-words-in db ans {:vars moved-vars :sections text-secs :tasks task-ids}))
                       (map #(assoc % :class :uncited-var) (uncited-vars db ans moved-vars))
                       (map #(assoc % :class :long-form) (long-forms db ans moved-vars))))
        forms-in (fn [db] (fn [fact] (set (keep #(:e (law-form-at db fact %)) (changed-forms fact)))))
        had (vec (rows base bs (forms-in base)))
        has (vec (rows head hs (forms-in head)))
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
  section of a law's page names or unnames any site, so then every site is read. The candidates are found
  from moved, the datoms that differ between base and head (`caused-datoms` when the caller holds none, or the
  whole :eavt diff it read already): Cost: the datoms of moved and one entity seek per section, name, form and
  task they touch, on both sides, plus the rule for the candidates and one seek of the citations of each name
  that appeared or vanished; flat in the sections, citations, vars and sites the branch did not touch
  (seon.problems-cost-test/caused-follows-what-changed). Measured on a two-var branch of 18 evals on Seon's own
  program (docs/cost.md#a-diff-per-attribute-costs-a-descent-each)."
  {:malli/schema [:function [:=> [:cat :seon.host/db :seon.host/db] [:map [:caused [:vector :string]] [:attention [:vector :string]] [:rows [:vector [:map [:class :keyword] [:message :string]]]]
                                                                     [:closed [:vector [:map [:class :keyword] [:message :string]]]]]]
                  [:=> [:cat :seon.host/db :seon.host/db :seon.merge/changes] [:map [:caused [:vector :string]] [:attention [:vector :string]] [:rows [:vector [:map [:class :keyword] [:message :string]]]]
                                                                                    [:closed [:vector [:map [:class :keyword] [:message :string]]]]]]
                  [:=> [:cat :seon.host/db :seon.host/db :seon.merge/changes [:sequential :seon.host/datom]] [:map [:caused [:vector :string]] [:attention [:vector :string]] [:rows [:vector [:map [:class :keyword] [:message :string]]]]
                                                                                    [:closed [:vector [:map [:class :keyword] [:message :string]]]]]]]}
  ([base head] (caused-split base head (pm/delta-changes base head #{})))
  ([base head ch] (caused-split base head ch (caused-datoms base head)))
  ([base head ch moved]
  (let [{:keys [made closed]} (split-rows base head {} ch moved)
        attention? (:seon.problems/attention (contract/properties :seon.problems/class))
        messages (fn [pred] (vec (sort (into #{} (comp (filter pred) (map :message)) made))))
        shown (fn [rs] (vec (sort-by (juxt (comp str :class) :message) (into #{} (map #(select-keys % [:class :message])) rs))))]
    {:caused (messages (complement (comp attention? :class))) :attention (messages (comp attention? :class))
     :rows (shown made) :closed (shown closed)})))

(defn caused
  "The derived problems the branch at head has that base had not that refuse its done
  (`caused-split`'s :caused), with the branch's changes since base ch when its caller holds them, and the
  datoms that differ moved when it holds those."
  {:malli/schema [:function [:=> [:cat :seon.host/db :seon.host/db] [:set :string]]
                  [:=> [:cat :seon.host/db :seon.host/db :seon.merge/changes] [:set :string]]
                  [:=> [:cat :seon.host/db :seon.host/db :seon.merge/changes [:sequential :seon.host/datom]] [:set :string]]]}
  ([base head] (into (sorted-set) (:caused (caused-split base head))))
  ([base head ch] (into (sorted-set) (:caused (caused-split base head ch))))
  ([base head ch moved] (into (sorted-set) (:caused (caused-split base head ch moved)))))

;; ---- how a task's work ended -----------------------------------------------------------

(def ^:private task-pull
  "What a problem row reads of how a task's work ended: one pull. See docs/reconcile.md#a-task-holds-how-its-work-ended."
  [:db/id :seon.task/id :seon.task/ns :seon.task/proofs :seon.task/execution :seon.task/outcome :seon.task/reason
   :seon.task/unmerged :seon.task/failures :seon.task/costs :seon.task/error :seon.task/waste :seon.task/task-conflicts
   :seon.task/deferred :seon.task/gate-unread :seon.task/deferred-results :seon.task/conflicts
   {:seon.task/agent [:seon.agent/id :seon.agent/ended-ms]}])

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
  whose accumulated plan cites no program var (no cite of one along its needs, seon.task/cites-of: the
  context pulls no var for it), that names no proof, or one of whose proofs is still the placeholder
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
        ;; the program's vars among every task's cites, asked of the program once, when a ready implement step asks
        ;; (seon.program/program-names)
        cited (fn [e] (into [] (comp (map #(:seon.cite/name (d/entity db (:e %)))) (filter qualified-symbol?)) (d/datoms db :avet :seon.cite/of e)))
        program (delay (set (program/program-names db (vec (distinct (mapcat #(cited (:e %)) (d/datoms db :aevt :seon.task/id)))))))
        placeholder? (fn [sym] (some-> (pdb/eid db :var/name sym) (->> (d/entity db)) :var/form :form/src
                                      (str/includes? task/placeholder-assertion)))]
    (vec (for [{:keys [e v]} (sort-by :v (d/datoms db :aevt :seon.task/id))
               :when (and (= :implement (kinds e)) (task-ready? db e))
               :let [es (chain #{} e)
                     proofs (vals-of es :seon.task/proofs)
                     reasons (cond-> []
                               (not-any? @program (mapcat cited es))
                               (conj :no-references)
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
  (let [t (d/pull db [:seon.task/id :seon.task/ns :seon.task/failure :seon.task/evidence :seon.task/execution :seon.task/proofs {:seon.task/agent [:seon.agent/ended-ms]}] e)
        f (:seon.task/failure t)
        id (:seon.task/id t)
        ;; the measured evidence, when it is this failure's (hold-tx writes both)
        ev (let [ev (:seon.task/evidence t)] (when (and (:agent f) (= id (:from ev))) ev))]
    (merge (select-keys f [:reason :counterexample])
           {:task id :judged id :open? (= :held (:seon.task/execution t))
            :owner-ns (:seon.task/ns t) :producer nil :proofs (vec (sort-by str (:seon.task/proofs t)))
            :evidence (merge ev (select-keys f [:reason :counterexample]) {:kind :failed}) :answered-by (get-in ans [:from id] []) :at-ms (get-in t [:seon.task/agent :seon.agent/ended-ms])})))

(defn- task-rows
  "The rows of how task entity e's work ended, by class."
  {:malli/schema [:=> [:cat :seon.host/db :map :int] [:map-of :keyword [:vector :seon.problems/row]]]}
  [db ans e]
  (let [p (d/pull db task-pull e)
        id (:seon.task/id p)
        status (:seon.task/outcome p)
        conflicts (vec (:seon.task/conflicts p))
        unmerged (vec (sort (:seon.task/unmerged p)))
        end-ms (get-in p [:seon.task/agent :seon.agent/ended-ms])
        base {:task id :judged id :agent (get-in p [:seon.task/agent :seon.agent/id]) :status status
              :outcome status :reason (:seon.task/reason p) :end-ms end-ms
              :open? (and (not= :cancelled (:seon.task/execution p)) (not (task/done? db e))) :owner-ns (:seon.task/ns p) :producer nil :proofs []
              :answered-by (get-in ans [:from id] []) :at-ms end-ms}
        ;; a red proof is this task's problem when the task itself states it: an inherited
        ;; proof was green on main when the task started (its needs were done), so red now it
        ;; is a regression, its reason; another task's proof not green yet is
        ;; an allowed red. Both stay in its facts as evidence, never problem rows.
        own (set (:seon.task/proofs p))
        failures (select-keys (:seon.task/failures p) own)
        costs (select-keys (:seon.task/costs p) own)
        err (:seon.task/error p)
        waste (:seon.task/waste p)
        merge? (or (seq conflicts) (seq unmerged) (= :behind status)
                   (some-> err :seon.error/type namespace #{"seon.merge" "seon.writer"}))]
    (cond-> {}
      (seq failures)
      (assoc :red (vec (for [[test asserts] (sort-by (comp str key) failures)]
                         (assoc base :test test :assertions asserts :proofs [test]
                                :evidence {:from id :red [test] :failures {test asserts}}))))
      (seq (remove (fn [[_ {v :verdict}]] (and (:ok? v) (empty? (:under v)))) costs))
      (assoc :cost (vec (for [[test {v :verdict :as c}] (sort-by (comp str key) costs) :when (not (and (:ok? v) (empty? (:under v))))]
                          (assoc base :test test :verdict v :rows (:rows c) :proofs [test] :evidence {:from id :red [test] :cost {test c}}))))
      merge?
      (assoc :merge [(assoc base :conflicts (count conflicts) :unmerged unmerged :error err
                            :producer (:seon.error/operation err)
                            :evidence (cond-> {:from id :conflicts conflicts :unmerged unmerged
                                               :red []}
                                        err (assoc :error err)))])
      (seq (:seon.task/task-conflicts p))
      (assoc :task-conflict (vec (for [c (:seon.task/task-conflicts p)]
                                   (assoc base :task (:task c) :attr (:attr c) :evidence (assoc c :from id)))))
      (death-statuses status)
      (assoc :dead [(assoc base :error err :producer (:seon.error/operation err) :evidence (cond-> {:from id} err (assoc :error err)))])
      (and err (not merge?) (not (death-statuses status)))
      (assoc :error [(assoc base :error err :producer (:seon.error/operation err) :evidence {:from id :error err})])
      (seq waste)
      (assoc :waste [(assoc base :waste waste :evidence {:from id :waste waste})])
      ;; merged with its deferred process tests unasked (or no gate checkout declared): open until
      ;; each is recorded passing (my.root/gate!; DECISIONS, Process tests are not automatic)
      (and (= :merged status) (seq (:seon.task/deferred p))
           (not-every? #(= :pass (get (:seon.task/deferred-results p) %)) (:seon.task/deferred p)))
      (assoc :unrun [(cond-> (assoc base :open? true :tests (vec (sort-by str (:seon.task/deferred p)))
                            :reason (str "merged with " (count (:seon.task/deferred p)) " process tests unrun")
                            :evidence {:from id :deferred (vec (sort-by str (:seon.task/deferred p)))})
                       ;; the changed namespaces its gate could not read, and why
                       (:seon.task/gate-unread p) (assoc :unread (:seon.task/gate-unread p)))]))))

(defn- fold-counted
  "classes with each counted class's rows (`counted-classes`) at their true counts: a row's count
  raised to what fold (root's api's, seon.error/fold) saw of its signature, repeats main does not hold
  yet; the rows by count, most first, then in the order main holds their errors; :n their sum. The one
  place a fold is applied: a whole read (`report-with`) and a held one (`with-fold`). See docs/health.md#cost."
  {:malli/schema [:=> [:cat :map :seon.host/db :seon.error/fold] :map]}
  [classes db fold]
  (let [live (into {} (map (fn [{:keys [signature base] n :count}] [signature (+ (or base 0) n)])) (vals fold))
        at (delay (into {} (map-indexed (fn [i dt] [(:v dt) i])) (d/datoms db :aevt :seon.error/signature)))]
    (reduce (fn [cs c]
              (let [rows (vec (sort-by (juxt (comp - :count) #(get @at (:signature %) 0))
                                       (map #(update % :count max (get live (:signature %) 0)) (get-in cs [c :rows]))))]
                (assoc cs c {:n (reduce + 0 (map :count rows)) :rows rows})))
            classes counted-classes)))

(defn with-fold
  "Health report h of db at the true counts fold holds (`fold-counted`): what (health db fold) answers,
  from a health read without the fold (the render proc's, kept by what each move of main changed:
  seon.render.proc/health!). See docs/health.md#cost."
  {:malli/schema [:=> [:cat :seon.problems/report :seon.host/db :seon.error/fold] :seon.problems/report]}
  [h db fold] (update h :classes fold-counted db fold))

(defn- report-with
  "`report` over the answers its caller read; with only (task entity ids) the judged classes hold just
  those tasks' rows, found without scanning the tasks' attributes (`health-moved` carries the rest)."
  {:malli/schema [:=> [:cat :seon.host/db :map :seon.error/fold [:maybe [:set :int]]] :seon.problems/report]}
  [db ans fold only]
  (let [errors (group-by :class (map #(error-row db ans %) (es db :seon.error/signature)))
        done? (memoize #(task/done? db %))
        ;; a done task has no open problem but its unrun tests: its history is never pulled
        open-task? (fn [e] (or (seq (d/datoms db :eavt e :seon.task/deferred)) (not (done? e))))
        tasks (into (sorted-set)
                    (filter open-task?)
                    (or only
                        (concat (mapcat #(es db %) [:seon.task/failures :seon.task/costs :seon.task/error :seon.task/waste :seon.task/deferred
                                                    :seon.task/conflicts :seon.task/unmerged :seon.task/task-conflicts])
                                (mapcat (fn [s] (map :e (d/datoms db :avet :seon.task/outcome s))) (conj death-statuses :behind)))))
        by-class (apply merge-with into (map #(task-rows db ans %) tasks))
        failed (mapv #(failed-row db ans %) (es db :seon.task/failure))]
    ^{:seon.render/schema :seon.problems/report}
    {:t (:max-tx db)
     :classes (merge (fold-counted (into {} (for [c counted-classes] [c {:rows (vec (get errors c))}])) db fold)
                     (into {} (for [c ended-classes :let [rows (vec (sort-by #(- (or (:end-ms %) 0)) (get by-class c)))]]
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
                                                                     :sections (into #{} (map :v) (d/datoms db :aevt :seon.doc/address))
                                                                     :tasks (into #{} (map :v) (d/datoms db :aevt :seon.task/id))
                                                                     :all? true}))))))

(defn- touched-tasks
  "The task entities of head the move touched (moved: the datoms that differ between the dbs, as
  `d/datoms-diff` gives them): those with a datom that differs, and the tasks of an agent whose datoms differ (an
  agent's ending time is a row's)."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :any]] [:set :int]]}
  [head moved]
  (into #{} (comp (map :e) (mapcat (fn [e] (cond (seq (d/datoms head :eavt e :seon.task/id)) [e]
                                                 (seq (d/datoms head :eavt e :seon.agent/key)) (map :e (d/datoms head :avet :seon.task/agent e))))))
        moved))

(defn health-moved
  "Health of head, given prev, the health of base and moved, the datoms that differ between them (`d/datoms-diff`, which the render proc reads already): the classes `caused-split` reads
  (`caused-classes`) are prev's rows less those base had and head has not, plus those head has and
  base had not (`split-rows`, read where the two differ, each row's answers current), and a judged
  class holds prev's rows of the tasks the move left alone and the rows of those it touched
  (`touched-tasks`), so a move of main costs what it changed, not its closed history; the errors
  and the few cheap derived classes are read anew. Measured on main's own db (514 tasks, 3,472 vars)
  for a one-run change: 8.2 M ticks, 43.6 MB and 77 contracted calls, against 168 M, 462 MB and 3,674 for
  `health` of the head. Equal to `health` of head (seon.problems-test/health-moved-is-health). `health` is
  the same per-class readers over every place, so there is one reader of each class and two selections of
  its candidates, not two implementations."
  {:malli/schema [:=> [:cat :seon.problems/report :seon.host/db :seon.host/db [:sequential :any]] :seon.problems/report]}
  [prev base head moved]
  (let [ans (answers head)
        {:keys [made closed]} (split-rows base head ans (pm/delta-changes base head #{} moved) moved)
        gone (into #{} (map :signature) closed)
        current (fn [row] (assoc row :answered-by (get-in ans (if (:signature row) [:signature (:signature row)] [:from (:judged row)]) [])))
        carried (fn [c] (into (into [] (comp (remove #(gone (:signature %))) (map current)) (get-in prev [:classes c :rows]))
                              (comp (filter #(= c (:class %))) (map #(dissoc % :class))) made))
        touched (touched-tasks head moved)
        ids (into #{} (keep #(:v (first (d/datoms head :eavt % :seon.task/id)))) touched)
        recorded (:classes (report-with head ans {} touched))
        recorded (into recorded (for [c ended-classes
                                      :let [rows (into (into [] (comp (remove #(ids (:judged %))) (map current)) (get-in prev [:classes c :rows])) (get-in recorded [c :rows]))
                                            rows (vec (sort-by #(- (or (:end-ms %) 0)) rows))]]
                                  [c {:n (count rows) :rows rows}]))]
    (health-with head ans recorded (assoc (other-derived head ans) :dead-citation (carried :dead-citation)
                                          :unnamed-site (carried :unnamed-site) :stale-section (carried :stale-section)
                                          :old-word (carried :old-word)))))

(defn own
  "The open problems of one agent, by class and shaped as `report` (so the one render draws it, an
  agent's page and root's alike): the agent with entity aid on db (its branch): its task's
  (how its work ended: task-rows), its task's (:seon.agent/task) failure, and the recorded errors last
  raised in its task. Cost: the task's pull, and an index seek of the task's id on
  :seon.error/last-task with a pull for each error it finds; never main's history nor other tasks' errors. See docs/health.md#two-kinds-of-problem."
  {:malli/schema [:=> [:cat :seon.host/db :int] :seon.problems/report]}
  [db aid]
  (let [ans (answers db)
        te (:v (first (d/datoms db :eavt aid :seon.agent/task)))
        tid (when te (:v (first (d/datoms db :eavt te :seon.task/id))))
        open (fn [rows] (vec (remove #(false? (:open? %)) rows)))
        by-class (merge-with into
                             (when te (task-rows db ans te))
                             (when (and te (seq (d/datoms db :eavt te :seon.task/failure))) {:failed [(failed-row db ans te)]})
                             (group-by :class (for [{:keys [e]} (when tid (d/datoms db :avet :seon.error/last-task tid))] (error-row db ans e))))]
    (vary-meta {:t (:max-tx db)
                :classes (into {} (for [[c rows] by-class :let [rows (open rows)] :when (seq rows)] [c {:n (count rows) :rows rows}]))}
               assoc :seon.render/schema :seon.problems/report)))
