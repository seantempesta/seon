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
    :unnamed-site                   a site of a law (`laws`: a catch) its page does not name:
                                    derived from the compiler's facts and the page's names
    :stale-pin                      a pin the wiki states (`pins`: docs/forks.md) that is not
                                    the gitlink root's source import recorded
    :unsynced                       a task that differs from its file: merged with its entry still
                                    there, or stated on main and changed or in no file (my.root/sync!)

  `report` reads what was recorded, at a cost that follows the problems. `health` adds the
  derived classes, read from the program's facts when asked (docs/health.md#cost)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [seon.print.emit :as emit] [datahike.api :as d] [clojure.string :as str] [clojure.set :as set] [seon.merge :as pm] [seon.source :as source] [seon.ref :as ref] [seon.program :as program] [seon.db :as pdb] [seon.doc :as doc] [seon.task :as task] [seon.config :as config] [seon.cost :as cost] [seon.reader :as reader] [seon.edn :as sedn]))

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
  "Is the recorded error r (entity e) closed: its subject (:seon.error/subject: a var's name, a section's address, or an
  attribute, whose facts are its datoms on any entity) has a fact written after the error's last one. Nothing is written
  to close it, and a subject that does not exist has changed nothing. See docs/health.md#two-kinds-of-problem."
  {:malli/schema [:=> [:cat :seon.host/db :map :int] :boolean]}
  [db r e]
  (let [s (:seon.error/subject r)
        facts (if (keyword? s)
                (d/datoms db :aevt s)
                (some->> s (pdb/eid db (if (symbol? s) :var/name :seon.doc/address)) (d/datoms db :eavt)))
        last-tx (reduce max (map :tx (d/datoms db :eavt e)))]
    (boolean (some #(> (:tx %) last-tx) facts))))

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
             :let [{pid :seon.process/pid {key :seon.agent/key} :seon.process/agent} (d/pull db [:seon.process/pid {:seon.process/agent [:seon.agent/key]}] e)
                   {:keys [kind form]} (:last-op st)
                   id (some-> key keyword)
                   s (str "stalled " (some-> id name))]]
         (derived-row ans s 'seon.agent-proc
                      (str "agent " (some-> id name) " sent nothing for " (:quiet-ms st) " ms with a request in flight"
                           (when kind (str ": " (name kind) (when form (str " " form)))))
                      {:kind :stalled :agent id :pid pid :quiet-ms (:quiet-ms st) :waits-on (:last-op st)}
                      {:agent id}))))

(def laws
  "The laws the compiler's facts answer exactly, as data: the fact that marks a site, the page
  that must name each site's function, and the namespaces the law leaves out (the check
  drivers: bin/laws' scope). The others stay bin/laws' text scan: clocks and measures (a host
  member is recorded as jolt.host/static-member, not by its name), resolution (a quoted
  symbol is a ref, so the facts over-count)."
  [{:law :catch :fact :form/catches :page "docs/failure-points.md" :except #{"seon.check" "seon.e2e"}}])

(defn unnamed-sites
  "Each site of a law (`laws`) its page does not name: a live form carrying the law's fact, by
  the var it defines, in a namespace of Seon's own src, and no test's (a form defining a var whose
  :var/meta holds :test, the compiler's fact of a deftest wherever it lives, an agent's REPL
  namespace included: its `is` expands to a catch), whose name no section of the page cites
  (:seon.doc/names), as an :unnamed-site row. Cost: the forms carrying the fact (aevt) and the
  page's cited names, once. With only (a set of form entity ids) just those forms are read (what `caused` asks).
  See docs/health.md#a-laws-unnamed-site."
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
             :let [f (d/entity db fe)]
             ;; a test is no failure point: a deftest's var holds :test (its definition's metadata), in test/ or in a
             ;; namespace an agent made at its REPL, which takes a file under src (seon.source/namespace-path)
             :when (not-any? #(contains? (:var/meta %) :test) (:form/defines f))
             :let [;; a catch is attributed to its form: the vars it defines, else the multimethod or
                   ;; protocol it extends, else the form's id: no site drops
                   sites (or (seq (map :var/name (:form/defines f))) (some-> (@owners fe) vector) [(:form/id f)])
                   nsym (:ns/name (:form/ns f))]
             v sites
             ;; the law is Seon's own source's (bin/laws scans src): a namespace with no file, and a
             ;; test helper's under test/, are not failure points of the system
             :when (and (not (except (str nsym))) (not (named v)) (contains? @program nsym))
             :let [s (str "unnamed-site " (name law) " " v)]]
         (derived-row ans s nsym (str v " has a " (name law) " site that " page " does not name")
                      {:kind :unnamed-site :law law :site v :page page} {:law law :site v :page page}))))))

(def pins
  "Where the wiki states each customized library's pin: the page, and the line that states one
  (`reference-code/x` pinned at `sha`), compared with the gitlinks root's last index of its source
  recorded (:seon.system/source :pins, seon.system/import-tree!)."
  {:page "docs/forks.md" :line #"(?m)^`(reference-code/[^`]+)` pinned at `([0-9a-f]+)`"})

(defn stale-pins
  "Each pin the wiki states (`pins`) that is not the gitlink root's last index of its source recorded, as a
  :stale-pin row; none while root has recorded no import (unknown, not a mismatch). Cost: the
  page's sections (one AVET range of addresses) and their text."
  {:malli/schema [:=> [:cat :seon.host/db :map] [:vector :seon.problems/row]]}
  [db ans]
  (let [{:keys [page line]} pins
        recorded (some->> (pdb/eid db :seon.system/id :root) (d/pull db [:seon.system/source]) :seon.system/source :pins)]
    (vec (when recorded
           (for [{a :v e :e} (d/datoms db :aevt :seon.doc/address) :when (str/starts-with? a (str page "#"))
                 [_ path sha] (re-seq line (or (:seon.doc/text (d/pull db [:seon.doc/text] e)) ""))
                 :let [g (get recorded path)] :when (not (some-> g (str/starts-with? sha)))
                 :let [s (str "stale-pin " a " " path)]]
             (derived-row ans s 'seon.doc (str a " states " path " pinned at " sha ", its gitlink is " (or g "none"))
                          {:kind :stale-pin :section a :path path :stated sha :recorded g} {:section a :path path :stated sha :recorded g}))))))

(def stale-binary-acts
  "What brings a root's binary and its main into agreement, each then a restart of root: what the :stale-binary row, root's
  refused spawn and the last line of bin/system start and bin/update say. See docs/source.md#files-and-the-db-two-directions."
  (str "Bring them into agreement, then restart root: erase the store and seed it from the tree (bin/system stop; bin/system start --reindex, "
       "which is bin/remove-tree runs/store and bin/system start), or reindex main from disk at root ((my.root/import! sys), (my.root/load-docs! sys), "
       "(my.root/load-tasks! sys); then bin/system stop and bin/system start), or start the binary main's program was built from (bin/system next)"))

(defn stale-binary
  "A :stale-binary row while root's binary is not current for its main: seon.system/stale-binary's answer as its boot held it on
  root's :seon.system/source fact (:stale-binary, seon.system/booted-source), naming the binary's tree, main's and what between
  them reaches root's own code or a build input (or what is missing). What agents merged into main since is main's own, never a
  row. None on a boot whose binary is current, or with no source fact; gone at the next boot on a current binary. While it
  shows, root forks no agent. Derived from the fact, never computed here, no clock; its message names the acts
  (`stale-binary-acts`). Cost: one pull. See docs/health.md#a-stale-binary."
  {:malli/schema [:=> [:cat :seon.host/db :map] [:vector :seon.problems/row]]}
  [db ans]
  (let [{:keys [boot stale-binary]} (some->> (pdb/eid db :seon.system/id :root) (d/pull db [:seon.system/source]) :seon.system/source)
        {:keys [binary tree reaches missing error]} stale-binary]
    (if stale-binary
      [(derived-row ans (str "stale-binary " boot) 'seon.system
                    (str "root's binary was built from " (if binary (str "tree " binary) "a tree its build did not record")
                         ", and main was last brought from tree " tree
                         (cond missing (str ": " (name missing) " is unknown")
                               error (str ": " error)
                               :else (str ", and what differs reaches root's own code: " (str/join ", " reaches)))
                         ". Root forks no agent. " stale-binary-acts)
                    {:kind :stale-binary :binary binary :tree tree} {:binary binary :tree tree})]
      [])))

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
  read is not. A var that is gone is not this. Open until the section is edited or
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
  is given one. only, a set of section entities, reads those alone (a branch's candidates, `split-rows`). Cost: the section addresses and the lead datoms once, or
  with only, two seeks a section."
  {:malli/schema [:function [:=> [:cat :seon.host/db :map] [:vector :seon.problems/row]] [:=> [:cat :seon.host/db :map [:maybe [:set :int]]] [:vector :seon.problems/row]]]}
  ([db ans] (leadless-sections db ans nil))
  ([db ans only]
  (let [led (if only (fn [e] (seq (d/datoms db :eavt e :seon.doc/lead))) (into #{} (map :e) (d/datoms db :aevt :seon.doc/lead)))]
    (vec (for [{:keys [e v]} (sort-by :v (if only (keep #(first (d/datoms db :eavt % :seon.doc/address)) only) (d/datoms db :aevt :seon.doc/address)))
               :when (not (led e))
               :let [s (str "leadless-section " v)]]
           (derived-row ans s 'seon.doc (str v " has no lead: its first block is not a paragraph that says what the section is about")
                        {:kind :leadless-section :section v} {:section v}))))))

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
  "The attributes `section-at` reads of a section: what a stale-section row of it depends on (docs/health.md#a-stale-section)."
  [:seon.doc/address :seon.doc/names :seon.doc/read :seon.doc/reviewed
   :seon.doc/baseline-unread :seon.doc/text])

(def ^:private content-attrs
  "The attributes the content classes a branch can make are read through, beside a section's: a section's lead and links
  (:leadless-section, :orphan-page), a form's refs and a var's form (:waived-proof, :stale-plan), a task's needs, outcome and
  proofs and its cites (:uncited-task, :stale-plan). What `caused-datoms` diffs for them. See docs/health.md#two-kinds-of-problem."
  [:seon.doc/lead :seon.doc/references :form/refs :form/keywords :var/form :seon.task/needs :seon.task/outcome :seon.task/proofs
   :seon.cite/of :seon.cite/target :schema/key :schema/form])

(defn- section-at
  "{:e :names :read :reviewed :baseline :text} of the section addressed a on db, by entity seeks and no
  pull: what it cites, and what closes or opens its stale-section rows
  (the code it read, who reviewed it, a baseline git could not give); nil when db holds none."
  {:malli/schema [:=> [:cat :seon.host/db :string] [:maybe :map]]}
  [db a]
  (when-let [e (pdb/eid db :seon.doc/address a)]
    (let [one (fn [attr] (:v (first (d/datoms db :eavt e attr))))
          many (fn [attr] (into #{} (map :v) (d/datoms db :eavt e attr)))]
      {:e e :names (many :seon.doc/names) :read (one :seon.doc/read)
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

(defn uncited-vars
  "Each of the named vars (a set of symbols, the vars a branch changed) that is public in a
  namespace of the program's own source that the wiki describes (its ns docstring cites a docs/ section: a person's
  namespace, written back under src/ too, cites none and is not judged) and has a docstring citing no docs/ section, or none, as
  an :uncited-var row: a function states its mechanism by citing the wiki section that describes
  it (docs/references.md#the-delimiter-picks-the-depth), and does not restate it. A private var
  (defn-) and a test are not judged. Closed by citing a section in the docstring.
  Cost: one seek per named var, its namespace's docstring, and its form's text for a var with no citation."
  {:malli/schema [:=> [:cat :seon.host/db :map [:set :symbol]] [:vector :seon.problems/row]]}
  [db ans names]
  (let [program (delay (program/program-nss db))
        ;; a namespace the wiki describes: its own docstring cites a docs/ section, as every one of Seon's does (AGENTS.md section 7);
        ;; one made for a person's work, written back under src/ as the program's are, cites none, and its code owes no citation
        described? (fn [nsym] (some-> (d/entity db [:ns/name nsym]) :ns/doc (str/includes? "docs/")))]
    (vec (for [v (sort names)
               :let [e (pdb/eid db :var/name v) ent (when e (d/entity db e))
                     doc (:var/doc ent) nsym (some-> (namespace v) symbol)]
               :when (and ent (not (contains? (set (:var/meta ent)) :test)) (not (and doc (str/includes? doc "docs/")))
                          (contains? @program nsym) (described? nsym)
                          (contains? #{"defn" "defmacro" "def" "defmulti"} (some->> ent :var/form :form/src (source/first-head nsym))))
               :let [s (str "uncited-var " v)]]
           (derived-row ans s nsym (str v " is public and its docstring cites no docs/ section: cite the one that describes it")
                        {:kind :uncited-var :var v} {:var v})))))

(defn unestimated-tests
  "Each of the named vars (a set of symbols, the vars a branch changed) that is a test stating no estimate on its deftest
  (no :seon.test/estimate fact on its var: docs/testing.md#test-types), as an :unestimated-test row: a new or changed test states what it should cost, worked from
  what it must do, and runs under the cap until it does (docs/merging.md#a-tests-tick-budget). Closed by stating
  ^{:seon.test/estimate {:ticks n}}. Cost: one seek per named var."
  {:malli/schema [:=> [:cat :seon.host/db :map [:set :symbol]] [:vector :seon.problems/row]]}
  [db ans names]
  (vec (for [v (sort names)
             :let [e (pdb/eid db :var/name v) ent (when e (d/entity db e)) nsym (some-> (namespace v) symbol)]
             :when (and ent (contains? (set (:var/meta ent)) :test) (nil? (:seon.test/estimate ent)))
             :let [s (str "unestimated-test " v)]]
         (derived-row ans s nsym (str v " states no estimate: state ^{:seon.test/estimate {:ticks n}} on its deftest, worked from what it must do")
                      {:kind :unestimated-test :var v} {:var v}))))

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

(defn unfocused-docstrings
  "An :unfocused-docstring row for each named Seon var whose docstring is over :seon.config.shrink/docstring-words words or
  holds a measurement, a date or a wiki page cited twice. One seek a var. See docs/health.md#an-unfocused-docstring."
  {:malli/schema [:=> [:cat :seon.host/db :map [:set :symbol]] [:vector :seon.problems/row]]}
  [db ans names]
  (let [bound (config/value :seon.config.shrink/docstring-words)]
    (vec (for [v (sort names)
               :when (re-find #"^(seon|my)\." (str (namespace v)))
               :let [doc (some->> (pdb/eid db :var/name v) (d/entity db) :var/doc)]
               :when doc
               :let [words (count (re-seq #"\S+" doc))
                     why (cond-> []
                           (> words bound) (conj :words)
                           (re-find #"\d[\d.,]*\s?(ms|µs|us|ticks|KB|MB|GB|bytes)\b" doc) (conj :measurement)
                           (re-find #"20\d\d-\d\d-\d\d" doc) (conj :date)
                           (some #(< 1 %) (vals (frequencies (re-seq #"docs/[a-z-]+\.md" doc)))) (conj :wiki-twice))]
               :when (seq why)
               :let [nsym (symbol (namespace v))]]
           (derived-row ans (str "unfocused-docstring " v) nsym
                        (str v "'s docstring is " words " words (" (str/join ", " (map name why)) "): say what it answers and the one thing a caller must know, and cite the wiki once")
                        {:kind :unfocused-docstring :var v :words words :why why :bound bound} {:var v :words words :why why})))))

(defn refused-values
  "Each stored datom of the attributes attrs whose value db's own entry for its attribute refuses, as a :refused-value row naming
  the attribute, the entity and why: a value written before its entry tightened, held to the entry now (seon.db/invalid-datom, with
  db's own schema changes, seon.program/schema-changes, as a write on db would be). Read only for the attributes a branch's
  move changed the entry of (`caused-split`), so it costs the datoms of those attributes, never the store; never in a full health
  read, which has no base to say what moved. Closed by fixing the value or loosening the entry. See
  docs/health.md#a-stored-value-its-entry-refuses."
  {:malli/schema [:=> [:cat :seon.host/db :map [:set :qualified-keyword]] [:vector :seon.problems/row]]}
  [db ans attrs]
  (if (empty? attrs)
    []
    (let [k (program/schema-changes db)
          own (when (seq k) {:forms k :registry (program/schema-registry k)})]
      (vec (for [a (sort attrs) {:keys [e v]} (d/datoms db :aevt a)
                 :let [err (pdb/invalid-datom db [[:db/add e a v]] own)] :when err
                 :let [s (str "refused-value " a " " e)]]
             (derived-row ans s (symbol (namespace a)) (str a " of entity " e " holds a value its entry refuses: " (:seon.error/message err))
                          {:kind :refused-value :attribute a :entity e} {:attribute a :entity e}))))))

(def ^:private enum-members
  "Every keyword an [:enum ...] of the process's registry (seon.contract/forms) lists: values, not keys, so `unschemed-keys` asks no
  entry of them. Read once a process, as the registry is."
  (delay (into #{} (for [f (vals contract/forms) x (tree-seq coll? seq f) :when (and (vector? x) (= :enum (first x))) k (rest x) :when (keyword? k)] k))))

(defn unschemed-keys
  "Each of Seon's own qualified keywords (a seon or my namespace) that a source form names (:form/keywords of a form whose namespace's
  file is under src/) and that no schema entry on db defines (:schema/key), as an :unschemed-key row naming the key and the forms that
  name it: every key is one schema entry (the owner: \"proper schemas for all fully namespaced keywords\"). A reverse
  ref (its name starting with _), a destructuring directive (:ns/keys) and an enum's value are not keys; a host type (seon.contract/host-schemas, a predicate defined in
  code) is its entry. Closed by writing the key's entry, or by naming a key that has one.
  only, a set of form entities (a branch's candidates, `split-rows`), reads the keywords of those forms alone. Cost: the :form/keywords
  datoms read (all, or only's), one :schema/key seek per distinct key. See docs/health.md#a-key-with-no-schema."
  {:malli/schema [:function [:=> [:cat :seon.host/db :map] [:vector :seon.problems/row]] [:=> [:cat :seon.host/db :map [:maybe [:set :int]]] [:vector :seon.problems/row]]]}
  ([db ans] (unschemed-keys db ans nil))
  ([db ans only]
   (let [src? (memoize (fn [ns-e] (some-> (:v (first (d/datoms db :eavt ns-e :ns/file))) (str/starts-with? "src/"))))
         ours? (fn [k] (let [n (namespace k)] (or (str/starts-with? n "seon") (str/starts-with? n "my."))))
         named (if only (mapcat #(d/datoms db :eavt % :form/keywords) only) (d/datoms db :aevt :form/keywords))
         by-key (reduce (fn [m {:keys [e v]}]
                          ;; a destructuring directive ({:ns/keys [...]}) names keys, it is none
                          (if (and (ours? v) (not (str/starts-with? (name v) "_")) (not (@enum-members v)) (not (#{"keys" "syms" "strs"} (name v)))
                                   (some-> (:v (first (d/datoms db :eavt e :form/ns))) src?))
                            (update m v (fnil conj (sorted-set)) e) m))
                        {} named)]
     (vec (for [[k es] (sort-by (comp str key) by-key)
                :when (and (empty? (d/datoms db :avet :schema/key k)) (not (contains? contract/host-schemas k)))
                :let [nsym (symbol (namespace k)) s (str "unschemed-key " k)
                      vars (vec (sort (keep #(some-> (first (d/datoms db :eavt % :form/defines)) :v (->> (d/entity db)) :var/name) es)))]]
            (derived-row ans s nsym (str k " is named in src by " (count vars) " vars (the row's :vars) and no schema entry defines it: write its entry, with a :description")
                         {:kind :unschemed-key :key k :vars vars} {:key k :vars vars}))))))

(defn waived-proofs
  "Each cost proof of a test form of the program that carries an `:overrun` (a waiver: seon.cost/proof), as a
  :waived-proof row with its test, the task it names, the size it was taken at (:n), the numbers it recorded and
  `multiple`, the greatest of the counted dimensions' recorded multiples of the proof's own bound at that size (the
  arithmetic of a verdict: seon.cost/expected, seon.cost/row-ratio; nil when the record states no size or the
  estimate is not literal). Found by the forms that reference seon.cost/proof (one :form/refs seek), the
  `:overrun` map read out of the form by the reader. Closed when the proof's `:overrun` is deleted. Cost: the forms that call
  seon.cost/proof, each read once; with only, a set of form entities (a branch's candidates, `split-rows`), those of them
  alone. See docs/health.md#a-proof-that-passes-by-waiver."
  {:malli/schema [:function [:=> [:cat :seon.host/db :map] [:vector :seon.problems/row]] [:=> [:cat :seon.host/db :map [:maybe [:set :int]]] [:vector :seon.problems/row]]]}
  ([db ans] (waived-proofs db ans nil))
  ([db ans only]
  (vec (for [{:keys [e]} (d/datoms db :avet :form/refs 'seon.cost/proof)
             :when (or (nil? only) (only e))
             :let [f (d/entity db e) nsym (:ns/name (:form/ns f))]
             v (:form/defines f) :when (contains? (set (:var/meta v)) :test)
             :let [t (:var/name v)
                   proofs (filter #(and (map? %) (map? (:overrun %))) (tree-seq coll? seq (reader/read-all (:form/src f) nsym)))]
             [i m] (map-indexed vector proofs)
             :let [{:keys [task n measured]} (:overrun m)
                   estimate (select-keys m [:ops :per-n :fixed :growth])
                   literal? (and (every? map? (vals (dissoc estimate :growth))) (keyword? (:growth estimate)) (pos-int? n))
                   bound (when literal? (cost/expected estimate cost/units n))
                   multiple (when bound (some->> (for [d (keys measured) :when (and (cost/exact d) (contains? bound d))]
                                                   (cost/row-ratio {:measured measured :expected bound} d))
                                                 seq (apply max)))
                   s (str "waived-proof " t " " i)]]
         (derived-row ans s nsym (str t " passes by a waiver for task " task (when multiple (format ", at %.2fx its bound" multiple)) ": fix the task, then delete the :overrun")
                      {:kind :waived-proof :test t :task task :n n :measured measured :multiple multiple}
                      {:test t :task task :n n :measured measured :multiple multiple})))))

(defn- test-reach
  "{name local?} of what test var t reaches through the test namespaces' own code, by the compiler's facts: its form's
  :form/refs and, followed, those of each var they name that a namespace under test/ defines and that is none of stop (a src var,
  the shared world's among them, is not entered); local? when a form of t's own namespace names it. Cost: one seek and one
  form's refs a test-tree var reached. See docs/testing.md#test-types."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-symbol [:set :qualified-symbol]] [:map-of :symbol :boolean]]}
  [db t stop]
  (let [nsym (namespace t)
        test-var? (fn [v] (when-let [e (pdb/eid db :var/name v)] (some-> (d/entity db e) :var/ns :ns/file (str/starts-with? "test/"))))]
    (loop [todo [t] seen #{t} out {}]
      (if-let [v (peek todo)]
        (let [refs (some-> (pdb/eid db :var/name v) (as-> e (d/entity db e)) :var/form :form/refs)
              local? (= nsym (namespace v))
              nxt (filterv #(and (qualified-symbol? %) (not (seen %)) (not (stop %)) (test-var? %)) refs)]
          (recur (into (pop todo) nxt) (into seen nxt) (reduce (fn [m r] (update m r #(boolean (or % local?)))) out refs)))
        out))))

(defn undeclared-tests
  "Each of the named vars (the vars a branch changed) that is a test and breaks its law, one :undeclared-test row a
  lack (one stating no estimate is `unestimated-tests`'): its estimate is over its cap (:seon.config.proofs/test-ticks-cap, a system test's
  asks-cap, a binary test's execs-cap); it reaches a running system (:seon.config.proofs/system-vars) and names no
  :seon.test/root, or runs a command (exec-vars) and names no :seon.test/execs; or its namespace makes a store of its own
  (own-store-vars). Read from the compiler's facts (`test-reach`); a test the branch did not change is not judged.
  Closed by stating what it lacks on the deftest. See docs/testing.md#test-types."
  {:malli/schema [:=> [:cat :seon.host/db :map [:set :symbol]] [:vector :seon.problems/row]]}
  [db ans names]
  (let [c (delay (into {} (for [k [:test-ticks-cap :system-test-asks-cap :binary-test-execs-cap :system-vars :exec-vars :own-store-vars]]
                            [k (config/value (keyword "seon.config.proofs" (name k)))])))]
    (vec (for [v (sort names)
               :let [e (pdb/eid db :var/name v) ent (when e (d/entity db e))]
               :when (and ent (contains? (set (:var/meta ent)) :test))
               :let [{:keys [test-ticks-cap system-test-asks-cap binary-test-execs-cap system-vars exec-vars own-store-vars]} @c
                     {:keys [ticks asks execs] :as est} (:seon.test/estimate ent)
                     root (:seon.test/root ent) cmds (:seon.test/execs ent)
                     ;; a store is judged where the test's own namespace calls it (local?): the shared world's helpers make theirs
                     reach (test-reach db v (into (set system-vars) exec-vars))
                     hit (fn [vars] (first (sort (filter #(contains? reach %) vars))))
                     lacks (cond-> []
                             (and ticks (> ticks test-ticks-cap)) (conj [:ticks (str "estimates " ticks " ticks, over the cap of " test-ticks-cap)])
                             (and root asks (> asks system-test-asks-cap)) (conj [:asks (str "estimates " asks " asks, over the cap of " system-test-asks-cap)])
                             (and cmds execs (> execs binary-test-execs-cap)) (conj [:execs (str "estimates " execs " execs, over the cap of " binary-test-execs-cap)])
                             (and (nil? root) (hit system-vars)) (conj [:root (str "reaches " (hit system-vars) " and names no :seon.test/root")])
                             (and (nil? cmds) (hit exec-vars)) (conj [:execs-named (str "reaches " (hit exec-vars) " and names no :seon.test/execs")])
                             (some #(get reach %) own-store-vars) (conj [:store (str "calls " (first (sort (filter #(get reach %) own-store-vars))) ", not the shared world (my.test/world, seon.test-store/test-db)")]))]
               [lack text] lacks
               :let [nsym (symbol (namespace v))]]
           (derived-row ans (str "undeclared-test " v " " (name lack)) nsym (str v " " text)
                        {:kind :undeclared-test :test v :lack lack} {:test v :lack lack})))))

(def caused-classes
  "The derived classes a branch is judged on, as :seon.problems/class declares them (:seon.problems/caused): the content a branch
  makes, each read by `caused-split` from what the branch moved; an attention class among them never fails a branch. The other
  derived classes (a stalled agent, a stale pin or binary, an unsynced task, a problem that points nowhere) are the running
  system's state, never a branch's content, so no branch causes them. See docs/health.md#two-kinds-of-problem."
  (:seon.problems/caused (contract/properties :seon.problems/class)))

(defn caused-datoms
  "The datoms that differ between base and head in the attributes `caused-split` reads (a section's, the var
  names, the task ids and plans, the catches and text of forms, and `content-attrs`), one datahike.api/datoms-diff range each: what
  its 2- and 3-arity read when they are given none. A caller that read the whole :eavt diff already passes
  that instead (docs/cost.md#a-diff-per-attribute-costs-a-descent-each)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db] [:vector :seon.host/datom]]}
  [base head]
  (into [] (mapcat (fn [a] (apply concat (d/datoms-diff base head :aevt [a]))))
        (distinct (concat section-attrs content-attrs [:var/name :seon.task/id :seon.task/plan :form/src] (map :fact laws)))))

(declare task-reads uncited-tasks stale-plans)

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
        gone (set (for [a cand :when (and (bs a) (not (hs a)))] a))
        ;; the names a var or task gained or lost
        named (set (concat (map :v (by-attr :var/name))
                           (for [{:keys [v]} (by-attr :seon.task/id) :when (str/includes? v "/")] (symbol v))))
        removed (set (filter #(and (name-exists? base %) (not (name-exists? head %))) named))
        added (set (filter #(and (name-exists? head %) (not (name-exists? base %))) named))
        address-of (fn [e] (first-v head e :seon.doc/address))
        ;; a section is a candidate when what it cites appeared or vanished, or it is itself gone from head
        cited (when (or (seq removed) (seq added))
                (into #{} (comp (mapcat #(d/datoms head :avet :seon.doc/names %)) (keep #(address-of (:e %)))) (into removed added)))
        secs (-> changed (into cited) (into gone))
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
        ;; the content classes a branch can make, each read for what the move touched: the sections whose address or lead moved;
        ;; every page when an address or a link moved (which page is linked is the whole wiki's); the forms whose text or refs
        ;; moved; the tasks whose own datoms moved, those a moved cite is of, those citing a var whose form moved, those needing a
        ;; task whose outcome moved, and those naming a proof a moved form defines
        led-cands (ents [:seon.doc/address :seon.doc/lead])
        linkage? (some by-attr [:seon.doc/address :seon.doc/references])
        form-cands (ents [:form/src :form/refs])
        ;; the forms whose keywords moved, and those naming a key whose entry the branch added or removed
        ;; the stored attributes whose entry the branch changed: their datoms are held to the new entry
        moved-attrs (into #{} (comp (keep #(:v (first (d/datoms head :eavt % :schema/key)))) (filter #(contains? (:schema head) %))) (ents [:schema/form]))
        key-cands (into (ents [:form/keywords])
                        (for [db [base head] {k :v} (by-attr :schema/key) {e :e} (d/datoms db :avet :form/keywords k)] e))
        task-cands (fn [db]
                     (let [tasks (fn [es] (filter #(seq (d/datoms db :eavt % :seon.task/id)) es))
                           of-cite (fn [c] (:v (first (d/datoms db :eavt c :seon.cite/of))))
                           var-es (into (ents [:var/form]) (keep #(:v (first (d/datoms db :eavt % :form/defines)))) (ents [:form/src]))
                           defined (into #{} (keep #(:v (first (d/datoms db :eavt % :var/name)))) var-es)]
                       (-> #{}
                           (into (tasks (map :e moved)))
                           (into (keep of-cite) (ents [:seon.cite/of :seon.cite/target]))
                           (into (comp (mapcat #(d/datoms db :avet :seon.cite/target %)) (keep (comp of-cite :e))) var-es)
                           (into (comp (mapcat #(d/datoms db :avet :seon.task/needs %)) (map :e)) (map :e (by-attr :seon.task/outcome)))
                           (cond-> (seq defined) (into (comp (filter #(defined (:v %))) (map :e)) (d/datoms db :aevt :seon.task/proofs))))))
        task-only (into (task-cands base) (task-cands head))
        rows (fn [db state forms-of]
               (let [tr (task-reads db task-only)]
                 (concat (map #(assoc % :class :unnamed-site) (sites db state forms-of))
                         (map #(assoc % :class :stale-section) (stale-sections db ans {:names moved-vars :sections (eids state)}))
                         (map #(assoc % :class :uncited-var) (uncited-vars db ans moved-vars))
                         (map #(assoc % :class :long-form) (long-forms db ans moved-vars))
                         (map #(assoc % :class :unfocused-docstring) (unfocused-docstrings db ans moved-vars))
                         (map #(assoc % :class :unestimated-test) (unestimated-tests db ans moved-vars))
                         (map #(assoc % :class :undeclared-test) (undeclared-tests db ans moved-vars))
                         (map #(assoc % :class :leadless-section) (leadless-sections db ans led-cands))
                         (when linkage? (map #(assoc % :class :orphan-page) (orphan-pages db ans)))
                         (map #(assoc % :class :waived-proof) (waived-proofs db ans form-cands))
                         (map #(assoc % :class :unschemed-key) (unschemed-keys db ans key-cands))
                         (map #(assoc % :class :refused-value) (refused-values db ans moved-attrs))
                         (map #(assoc % :class :uncited-task) (uncited-tasks db ans tr))
                         (map #(assoc % :class :stale-plan) (stale-plans db ans tr)))))
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
  :seon.problems/attention declares). An evaluation is never refused (get out of an agent's way: docs/processes.md#an-agent-is-never-gated).
  Base and head are compared by value, never by tx (a branch's tx counter is its own): the
  sections whose citations differ, the sections citing a name a var or task lost,
  and the forms carrying a law's fact whose catches or text differ are the candidates, and
  `stale-sections` and `unnamed-sites` read only those, on head and on base; a change to a
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
   {:seon.task/agent [:seon.agent/key :seon.agent/ended-ms]}])

(defn unsynced?
  "Whether task entity e on db differs from its file: merged with main's files still declaring it (its :seon.task/declared, which
  a landing that removes its entry retracts: seon.task/load-tx), or live, stated (not seon.task/derived?) and either never filed
  (no declared) or changed on main since its file was read: load-tx writes a task's members (seon.task/stated-attributes, its
  needs) in the transaction that writes :seon.task/declared, so a member datom newer than it is an edit made since. Seeks only,
  no member read or decoded. The store is the source and its files follow. See docs/source.md#a-merged-task-reaches-mains-files."
  {:malli/schema [:=> [:cat :seon.host/db :int] :boolean]}
  [db e]
  (let [declared (first (d/datoms db :eavt e :seon.task/declared))]
    (boolean
      (if (= :merged (:v (first (d/datoms db :eavt e :seon.task/outcome))))
        (some? declared)
        (and (not (task/finished (:v (first (d/datoms db :eavt e :seon.task/outcome)))))
             (not (#{:done :cancelled} (:v (first (d/datoms db :eavt e :seon.task/execution)))))
             (or (nil? declared)
                 (some (fn [a] (some #(> (:tx %) (:tx declared)) (d/datoms db :eavt e a))) (conj task/stated-attributes :seon.task/needs)))
             (not (task/derived? db e)))))))

(defn- task-reads
  "The one reading of which tasks are ready and what they cite, on db: {:ready? :cited}, `ready?` a function of a task
  entity (not done, every task it needs done; every task's outcome read once, so readiness is a lookup) and `cited`
  one of a task entity giving the var names its cites target. What `uncited-tasks` and `stale-plans` share. With only, a set
  of task entities (a branch's candidates, `split-rows`), the readers judge those alone and a task's outcome is a seek, so
  nothing reads every task. See docs/health.md#a-task-that-cites-nothing."
  {:malli/schema [:function [:=> [:cat :seon.host/db] :map] [:=> [:cat :seon.host/db [:maybe [:set :int]]] :map]]}
  ([db] (task-reads db nil))
  ([db only]
  (let [done (if only
               (fn [e] (boolean (some-> (first (d/datoms db :eavt e :seon.task/outcome)) :v task/finished)))
               (into #{} (comp (filter #(task/finished (:v %))) (map :e)) (d/datoms db :aevt :seon.task/outcome)))]
    {:ready? (fn [e] (and (not (done e)) (every? #(done (:v %)) (d/datoms db :eavt e :seon.task/needs))))
             :cited (fn [e] (into [] (comp (map #(:var/name (:seon.cite/target (d/entity db (:e %))))) (filter qualified-symbol?)) (d/datoms db :avet :seon.cite/of e)))
             ;; the tasks the readers judge: every task, or only's
             :tasks (if only (keep #(first (d/datoms db :eavt % :seon.task/id)) only) (d/datoms db :aevt :seon.task/id))})))

(defn changed-paths
  "The files task entity e's merged changes touch (its :seon.task/changed keys), as db holds them now: seon.source/changed-files
  of each key with its content on db, a form gone from db by its id's namespace. See docs/source.md#a-merged-task-reaches-mains-files."
  {:malli/schema [:=> [:cat :seon.host/db :int] [:vector :string]]}
  [db e]
  (let [ks (mapv (comp sedn/decode :v) (d/datoms db :eavt e :seon.task/changed))
        now (pm/program-of db ks #{})]
    (source/changed-files db (into {} (for [[kind x :as k] ks]
                                        [k [nil (or (now k) (when (= :form kind) {:ns (symbol (first (str/split x #"/" 2)))}))]])))))

(defn- unsynced-candidates
  "The task entities among es that may differ from their files (`unsynced?` judges them): a merged one its file still declares,
  or any not finished; a finished one costs a seek or two and no call."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :int]] [:vector :int]]}
  [db es]
  (filterv #(let [o (:v (first (d/datoms db :eavt % :seon.task/outcome)))]
              (if (= :merged o) (seq (d/datoms db :eavt % :seon.task/declared))
                  (not (or (task/finished o) (= :cancelled (:v (first (d/datoms db :eavt % :seon.task/execution))))))))
           es))

(defn unsynced-tasks
  "Each task that differs from its file (`unsynced?`), as an :unsynced row naming the task, the files a merged one's changes touch
  (`changed-paths`) and the act that syncs it: my.root/sync! writes them from main, with its entry removed (merged) or written as
  main holds it (seon.task/declaration), onto a branch a session reviews and lands (Completes: for a merged one). The row's owner
  is the task's own namespace: no data names which session's area a namespace is in. Closed by that landing, whose load-tx reads
  the entry back. es, the task entities to judge (`unsynced-candidates`): every task's in `health`, the touched ones in
  `health-moved`. By task id. See docs/source.md#a-merged-task-reaches-mains-files."
  {:malli/schema [:=> [:cat :seon.host/db :map [:sequential :int]] [:vector :seon.problems/row]]}
  [db ans es]
  (vec (for [e (sort-by #(:v (first (d/datoms db :eavt % :seon.task/id))) (unsynced-candidates db es))
             :when (unsynced? db e)
             :let [{id :seon.task/id nsym :seon.task/ns t :seon.task/merged-t o :seon.task/outcome d :seon.task/declared}
                   (d/pull db [:seon.task/id :seon.task/ns :seon.task/merged-t :seon.task/outcome :seon.task/declared] e)
                   merged? (= :merged o)
                   paths (if merged? (changed-paths db e) [])]]
         (derived-row ans (str "unsynced " id) nsym
                      (if merged?
                        (str id " merged on main" (when t (str " at t " t)) ", and main's files still hold its entry"
                             (when (seq paths) (str " and not its changes to " (str/join ", " paths)))
                             ": (my.root/sync! sys [\"" id "\"]) writes them from main, its entry removed, onto a branch to review and land (Completes: " id ")")
                        (str id (if d " changed on main since its file was read" " is on main and in no file")
                             ": (my.root/sync! sys [\"" id "\"]) writes its entry as main holds it onto a branch to review and land"))
                      {:kind :unsynced :task id :paths paths} {:task id :paths paths}))))

(defn uncited-tasks
  "Each ready task, which holds the plans along its :needs (seon.task/accumulate), whatever its kind, whose
  accumulated plan cites nothing (no cite along its needs, seon.task/cites-of, of a var, a section, a schema key, a file,
  a task or a namespace: the context pulls nothing for it), that names no proof, one of whose proofs is still the
  placeholder seon.ctx writes, or whose own text cites what db no longer holds (:seon.task/gone, which the write that
  ended those cites left), as an :uncited-task row with its reason (:no-references, :no-proofs, :placeholder-proof,
  :gone). Closed when its plan cites a name that exists and its proofs are named and written. Cost: the task, reference and proof datoms once, then a
  walk of each ready task's needs."
  {:malli/schema [:function [:=> [:cat :seon.host/db :map] [:vector :seon.problems/row]] [:=> [:cat :seon.host/db :map :map] [:vector :seon.problems/row]]]}
  ([db ans] (uncited-tasks db ans (task-reads db)))
  ([db ans {:keys [ready? tasks]}]
  (let [chain (fn chain [seen e]
                (if (seen e) seen
                    (reduce chain (conj seen e) (map :v (d/datoms db :eavt e :seon.task/needs)))))
        vals-of (fn [es a] (into #{} (mapcat #(map :v (d/datoms db :eavt % a))) es))
        placeholder? (fn [sym] (some-> (pdb/eid db :var/name sym) (->> (d/entity db)) :var/form :form/src
                                      (str/includes? task/placeholder-assertion)))]
    (vec (for [{:keys [e v]} (sort-by :v tasks)
               :when (ready? e)
               :let [es (chain #{} e)
                     proofs (vals-of es :seon.task/proofs)
                     ;; what its text cites whose cites ended with their targets, as the write that ended them left it
                     gone (:v (first (d/datoms db :eavt e :seon.task/gone)))
                     reasons (cond-> []
                               ;; any cite: each names what exists (a dead one is refused at the write), and the context pulls it,
                               ;; a section, a schema key, a file, a task or a dependency's var as much as one of the program
                               (not-any? #(seq (d/datoms db :avet :seon.cite/of %)) es)
                               (conj :no-references)
                               (empty? proofs) (conj :no-proofs)
                               (some placeholder? proofs) (conj :placeholder-proof)
                               (seq gone) (conj :gone))]
               reason reasons
               :let [s (str "uncited-task " v " " (name reason))
                     fields (cond-> {:task v :reason reason} (= :gone reason) (assoc :gone gone))]]
           (derived-row ans s 'seon.task
                        (case reason
                          :no-references (str v " is ready and its plan cites nothing (no var, section, schema key, file or task): the context pulls nothing for it")
                          :no-proofs (str v " is ready and names no proof")
                          :placeholder-proof (str v " is ready and a proof of it is still the placeholder assertion (seon.task/placeholder-assertion)")
                          :gone (str v " is ready and its plan cites " (str/join ", " gone) ", which no longer exist: their cites ended with them"))
                        (assoc fields :kind :uncited-task) fields))))))

(defn stale-plans
  "Each ready task (its own plan, not its needs') citing a var whose form was written after the task's plan, as a
  :stale-plan row naming the task and the var: the plan was written before the var took its present form, so its
  premise (a function it says is missing, code it describes) may have moved. Closed when the plan is written again
  (a new :seon.task/plan datom). Cost: the ready tasks, then two datom reads a cite (the plan's and the var form's
  transaction). tr, the reading of readiness and cites it shares with `uncited-tasks` (`task-reads`), made once a health read
  (`other-derived`). See docs/health.md#a-plan-that-went-stale."
  {:malli/schema [:function [:=> [:cat :seon.host/db :map] [:vector :seon.problems/row]] [:=> [:cat :seon.host/db :map :map] [:vector :seon.problems/row]]]}
  ([db ans] (stale-plans db ans (task-reads db)))
  ([db ans {:keys [ready? cited tasks]}]
    (vec (for [{:keys [e v]} (sort-by :v tasks)
               :when (ready? e)
               :let [plan-tx (:tx (first (d/datoms db :eavt e :seon.task/plan)))]
               :when plan-tx
               var (distinct (cited e))
               :let [ve (pdb/eid db :var/name var)]
               :when (and ve (> (or (:tx (first (d/datoms db :eavt ve :var/form))) 0) plan-tx))]
           (derived-row ans (str "stale-plan " v " " var) 'seon.task
                        (str v " cites " var ", which changed after the plan was written, so what the plan says of it may no longer hold. To close it: read "
                             var " against the plan and rewrite what no longer holds, or, if it all holds, add the line \"Checked <date> against "
                             var ": holds.\" (a plan written again with the same text is no change, so it closes nothing)")
                        {:kind :stale-plan :task v :var var} {:task v :var var})))))

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
        base {:task id :judged id :agent (some-> (get-in p [:seon.task/agent :seon.agent/key]) keyword) :status status
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
      ;; each is recorded passing (the reconciler's :gate, docs/merging.md#a-merge-runs-what-the-change-reaches)
      (and (= :merged status) (seq (:seon.task/deferred p))
           (not-every? #(= :pass (get (:seon.task/deferred-results p) %)) (keys (:seon.task/deferred p))))
      (assoc :unrun [(cond-> (assoc base :open? true :tests (vec (sort-by str (keys (:seon.task/deferred p))))
                            :reason (str "merged with " (count (:seon.task/deferred p)) " process tests unrun")
                            :evidence {:from id :deferred (vec (sort-by str (keys (:seon.task/deferred p))))})
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
  "The derived classes `caused-split` does not read, their rows on db; :unsynced judges the task entities es (`unsynced-tasks`)."
  {:malli/schema [:=> [:cat :seon.host/db :map [:sequential :int]] :map]}
  [db ans es]
  (let [tr (task-reads db)]
  {:stalled (stalled-agents db ans) :stale-pin (stale-pins db ans) :stale-binary (stale-binary db ans) :orphan-page (orphan-pages db ans)
   :leadless-section (leadless-sections db ans)
   ;; the tasks' readiness and cites read once for the two classes that judge them
   :uncited-task (uncited-tasks db ans tr) :stale-plan (stale-plans db ans tr) :unsynced (unsynced-tasks db ans es)
   :waived-proof (waived-proofs db ans) :unschemed-key (unschemed-keys db ans)}))

(defn health
  "Every open problem on db, one value by class: what was recorded (`report`) and what the
  program's facts make true now (`unnamed-sites`, `stale-pins`, `stale-binary`, `stale-sections`,
  `orphan-pages`, `leadless-sections`, `uncited-tasks`, `stale-plans`, `unsynced-tasks`, and `problem-unlinked` over all of them). What the page, an agent's context and
  the done and merge gates read: computed when read, never stored, never polled."
  {:malli/schema [:function [:=> [:cat :seon.host/db] :seon.problems/report]
                  [:=> [:cat :seon.host/db :seon.error/fold] :seon.problems/report]]}
  ([db] (health db {}))
  ([db fold]
   (let [ans (answers db)]
     (health-with db ans (:classes (report-with db ans fold nil)) (assoc (other-derived db ans (mapv :e (d/datoms db :aevt :seon.task/id)))
                                     :unnamed-site (unnamed-sites db ans) :stale-section (stale-sections db ans))))))

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
    (health-with head ans recorded (-> (other-derived head ans (vec touched))
                                       ;; the tasks the move left alone keep prev's rows; the touched ones were judged anew
                                       (update :unsynced #(vec (sort-by :task (into (into [] (comp (remove (fn [r] (ids (:task r)))) (map current))
                                                                                          (get-in prev [:classes :unsynced :rows]))
                                                                                    %))))
                                       (assoc :unnamed-site (carried :unnamed-site) :stale-section (carried :stale-section))))))

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
    (vary-meta {:t (or (:max-tx db) (:time-point db))
                :classes (into {} (for [[c rows] by-class :let [rows (open rows)] :when (seq rows)] [c {:n (count rows) :rows rows}]))}
               assoc :seon.render/schema :seon.problems/report)))
