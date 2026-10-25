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
  (:require [seon.contract :as contract :refer [defn defn-]] [datahike.api :as d] [clojure.string :as str] [clojure.set :as set] [seon.merge :as pm] [seon.ref :as ref] [seon.program :as program] [seon.db :as pdb] [seon.docs :as docs] [seon.task :as task]))

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
          :evidence {:kind :unnamed-site :law law :site v :page page} :answered-by (get-in ans [:signature s] []) :at-ms nil}))))

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

;; ---- the wiki kept true: code that moved under its text, pages nothing links, issues with no page ----

(defn- current-forms
  "{var name form id} of every var of db with a form: three aevt scans, no seek."
  {:malli/schema [:=> [:cat :seon.host/db] [:map-of :qualified-symbol :string]]}
  [db]
  (let [ids (into {} (map (juxt :e :v)) (d/datoms db :aevt :form/id))
        forms (into {} (map (juxt :e :v)) (d/datoms db :aevt :var/form))]
    (into {} (keep (fn [{:keys [e v]}] (some->> (forms e) ids (vector v)))) (d/datoms db :aevt :var/name))))

(defn stale-sections
  "Each section whose text cites a var the code has changed since the section was last written, as a
  :stale-section row: a var's form id (the form's own digest) now differs from the one the section
  recorded when its text was last loaded (:seon.section/read, seon.docs/load-tx; my.docs/reviewed!
  records a review). Open until the section is edited or reviewed; attention, never a reason to refuse done.
  A var that is gone is a dead citation, not this. Cost: the vars' and forms' datoms in one scan each
  and one lookup per recorded name. With only ({:names :sections}: var names and section entity ids)
  just the recorded names among those names, and every name of those sections, are read: a seek for
  each, no scan of the vars (what `caused-split` asks, of what a branch changed)."
  {:malli/schema [:function [:=> [:cat :seon.host/db :map] [:vector :seon.problems/row]]
                  [:=> [:cat :seon.host/db :map [:maybe :map]] [:vector :seon.problems/row]]]}
  ([db ans] (stale-sections db ans nil))
  ([db ans only]
   (let [address (into {} (map (juxt :e :v)) (d/datoms db :aevt :seon.section/address))
         reads (if only
                 (keep (fn [{:keys [e v]}]
                         (let [vs (if ((:sections only) e) v (select-keys v (filter (:names only) (keys v))))]
                           (when (seq vs) {:e e :v vs})))
                       (d/datoms db :aevt :seon.section/read))
                 (d/datoms db :aevt :seon.section/read))
         now (if only (docs/read-map db (into #{} (mapcat (comp keys :v)) reads)) (current-forms db))]
     (vec (concat
            (for [{:keys [e v]} reads
                  [n then] v
                  :let [cur (now n)] :when (and cur (not= cur then))
                  :let [a (address e) s (str "stale-section " a " " n)]]
              {:signature s :section a :name n :then then :now cur :open? true
               :message (if (= "absent" then)
                          (str a " cites " n ", which did not exist when the section was last written: it is code now, read it")
                          (str a " describes " n " as it was (form " then "), and it has changed (form " cur ")"))
               :producer nil :owner-ns (symbol (namespace n)) :proofs []
               :evidence {:kind :stale-section :section a :name n :then then :now cur}
               :answered-by (get-in ans [:signature s] []) :at-ms nil})
            ;; a section whose baseline git could not give is read as of today: said, as attention
            (for [{:keys [e v]} (d/datoms db :aevt :seon.section/baseline-unread)
                  :when (or (nil? only) ((:sections only) e))
                  :let [a (address e) s (str "stale-section " a " baseline")]]
              {:signature s :section a :name nil :open? true
               :message (str a " was recorded as of today: its baseline could not be taken from git (" v ")")
               :producer nil :owner-ns 'seon.docs :proofs []
               :evidence {:kind :stale-section :section a :unread v}
               :answered-by (get-in ans [:signature s] []) :at-ms nil}))))))

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
               {:signature s :page p :open? true
                :message (str p " is linked from no other page, not even the map (docs/README.md)")
                :producer nil :owner-ns 'seon.docs :proofs []
                :evidence {:kind :orphan-page :page p} :answered-by (get-in ans [:signature s] []) :at-ms nil}))))))

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
           {:signature s :section v :open? true
            :message (str v " has no lead: its first block is not a paragraph that says what the section is about")
            :producer nil :owner-ns 'seon.docs :proofs []
            :evidence {:kind :leadless-section :section v} :answered-by (get-in ans [:signature s] []) :at-ms nil}))))

(defn problem-unlinked
  "Each class of classes ({class {:rows ...}}) with an open row whose wiki section (:seon.problems/docs)
  is no stored section, or that declares none, as a :problem-unlinked row: an issue that points
  nowhere is itself a problem. None while the db holds no wiki (unknown). Cost: one lookup per
  class with rows."
  {:malli/schema [:=> [:cat :seon.host/db :map :map] [:vector :seon.problems/row]]}
  [db ans classes]
  (if (empty? (d/datoms db :aevt :seon.section/address))
    []
    (let [docs (:seon.problems/docs (contract/properties :seon.problems/class))]
      (vec (for [[c {:keys [rows]}] (sort-by (comp str key) classes) :when (some #(not (false? (:open? %))) rows)
                 :let [page (docs c)] :when (or (nil? page) (nil? (pdb/eid db :seon.section/address page)))
                 :let [s (str "problem-unlinked " (name c))]]
             {:signature s :class c :page page :open? true
              :message (if page
                         (str (name c) " has open problems, and its section " page " is no section of the wiki")
                         (str (name c) " has open problems and no section of the wiki declared for it (:seon.problems/docs)"))
              :producer nil :owner-ns 'seon.problems :proofs []
              :evidence {:kind :problem-unlinked :class c :page page} :answered-by (get-in ans [:signature s] []) :at-ms nil})))))

;; ---- caused: what a branch's own work made true -----------------------------------------

(defn- section-state
  "{address {:e :names :dead}} of db's sections, by three aevt scans and no pull: what a section
  cites and the links it made that reached nothing."
  {:malli/schema [:=> [:cat :seon.host/db] [:map-of :string :map]]}
  [db]
  (let [group (fn [attr] (reduce (fn [m {:keys [e v]}] (update m e (fnil conj #{}) v)) {} (d/datoms db :aevt attr)))
        names (group :seon.section/names) dead (group :seon.section/dead-links)]
    (into {} (for [{:keys [e v]} (d/datoms db :aevt :seon.section/address)] [v {:e e :names (get names e #{}) :dead (get dead e #{})}]))))

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

(defn caused-split
  "{:caused :attention :rows}: the derived problems the branch at head has that base (its merge base) had not, by
  message (:rows: each with its class, both kinds): what the branch's own work broke (:caused: the done rule refuses while any is open,
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
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db] [:map [:caused [:vector :string]] [:attention [:vector :string]]
                                                             [:rows [:vector [:map [:class :keyword] [:message :string]]]]]]}
  [base head]
  (let [bs (section-state base) hs (section-state head)
        removed (set/difference (existing-names base) (existing-names head))
        changed (set (for [[a st] hs :when (not= (dissoc st :e) (dissoc (bs a) :e))] a))
        secs (into changed (for [[a st] hs :when (some removed (:names st))] a))
        eids (fn [state] (set (keep #(:e (state %)) secs)))
        page? (fn [a] (some #(str/starts-with? a (str (:page %) "#")) laws))
        all-sites? (some page? changed)
        sites (fn [db state eids-of]
                (if all-sites?
                  (unnamed-sites db {} nil)
                  (mapcat (fn [{:keys [fact]}] (unnamed-sites db {} (eids-of fact))) laws)))
        lf (memoize (fn [db fact] (law-forms db fact)))
        changed-forms (memoize (fn [fact] (let [h (lf head fact) b (lf base fact)]
                                            (set (for [[id st] h :when (not= (dissoc st :e) (dissoc (b id) :e))] id)))))
        ;; the stale sections the branch's change can have made: those citing a var it changed, and the sections that differ
        moved (program/changed-vars base head)
        rows (fn [db state forms-of]
               (concat (map #(assoc % :class :dead-citation) (dead-citations db {} (eids state)))
                       (map #(assoc % :class :unnamed-site) (sites db state forms-of))
                       (map #(assoc % :class :stale-section) (stale-sections db {} {:names moved :sections (eids state)}))))
        before (into #{} (map :signature)
                     (rows base bs (fn [fact] (set (keep #(:e ((lf base fact) %)) (changed-forms fact))))))]
    (let [attention? (:seon.problems/attention (contract/properties :seon.problems/class))
          made (remove (comp before :signature) (rows head hs (fn [fact] (set (keep #(:e ((lf head fact) %)) (changed-forms fact))))))
          messages (fn [pred] (vec (sort (into #{} (comp (filter pred) (map :message)) made))))]
      {:caused (messages (complement (comp attention? :class))) :attention (messages (comp attention? :class))
       :rows (vec (sort-by (juxt (comp str :class) :message) (into #{} (map #(select-keys % [:class :message])) made)))})))

(defn caused
  "The derived problems the branch at head has that base had not that refuse its done (`caused-split`'s :caused)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db] [:set :string]]}
  [base head] (into (sorted-set) (:caused (caused-split base head))))

;; ---- runs ------------------------------------------------------------------------

(def ^:private run-pull
  "What a problem row reads of a run: one pull (its conflicts are read only when it has
  them)."
  [:seon.run/id :seon.run/status :seon.run/outcome :seon.run/reason :seon.run/end-ms {:seon.run/agent [:seon.agent/id]}
   :seon.run/unmerged :seon.run/failures :seon.run/costs :seon.run/error :seon.run/waste :seon.run/task-conflicts
   :seon.run/deferred :seon.run/gate-unread {:seon.run/deferred-results [:seon.proof/test :seon.proof/status]}
   {:seon.run/task [:db/id :seon.task/id :seon.task/ns :seon.task/proofs]}
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
           {:signature s :task v :reason reason :open? true
            :message (case reason
                       :no-references (str v " is ready and its plan cites no var, attribute or section: the context pulls nothing for it")
                       :no-proofs (str v " is ready and names no proof")
                       :placeholder-proof (str v " is ready and a proof of it is still the placeholder assertion (seon.task/placeholder-assertion)"))
            :producer nil :owner-ns 'seon.task :proofs []
            :evidence {:kind :uncited-task :task v :reason reason} :answered-by (get-in ans [:signature s] []) :at-ms nil}))))

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
              :open? (not (task/done? db te)) :owner-ns (get-in p [:seon.run/task :seon.task/ns]) :producer nil :proofs []
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
  program's facts make true now (`dead-citations`, `unnamed-sites`, `stale-pins`, `stale-sections`,
  `orphan-pages`, `leadless-sections`, `uncited-tasks`, and `problem-unlinked` over all of them). What the page, an agent's context and
  the done and merge gates read: computed when read, never stored, never polled."
  {:malli/schema [:function [:=> [:cat :seon.host/db] :seon.problems/report]
                  [:=> [:cat :seon.host/db :seon.error/fold] :seon.problems/report]]}
  ([db] (health db {}))
  ([db fold]
   (let [ans (answers db)
         derived {:dead-citation (dead-citations db ans) :unnamed-site (unnamed-sites db ans) :stale-pin (stale-pins db ans)
                  :stale-section (stale-sections db ans) :orphan-page (orphan-pages db ans)
                  :leadless-section (leadless-sections db ans)
                  :uncited-task (uncited-tasks db ans)}
         classes (merge (:classes (report db fold)) (update-vals derived (fn [rows] {:n (count rows) :rows rows})))
         ;; its own class last: a missing section of the unlinked class is one more row
         unlinked (problem-unlinked db ans classes)
         unlinked (into unlinked (problem-unlinked db ans (assoc classes :problem-unlinked {:rows unlinked})))
         unlinked (vec (distinct unlinked))]
     (vary-meta (assoc {:t (:max-tx db)} :classes (assoc classes :problem-unlinked {:n (count unlinked) :rows unlinked}))
                assoc :seon.render/schema :seon.problems/report))))
