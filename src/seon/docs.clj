(ns seon.docs
  "The wiki as data in the store (task seon.docs/docs-are-data-in-the-store,
  docs/references.md#the-wiki-in-the-store): a section entity per heading of docs/*.md, read
  by the one parse (seon.ref/parse). A section's references are what its own text cites,
  resolved on the db (a link to a section, a name to a var), so its backlinks are who cites
  it. Loading writes only what differs from what main holds, as task files do. A citation is
  pulled here at its depth (cited: a var, a section, a file, a task or a namespace), for the
  model's context and every page alike."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.step :as step] [seon.contract :refer [defn defn-]] [seon.ref :as ref] [seon.source :as source] [seon.task :as task] [seon.db :as pdb]
            [datahike.api :as d] [clojure.string :as str] [clojure.java.io :as io]
            [seon.config :as cfg] [seon.lib :as lib] [seon.reader :as reader] [seon.edn :as sedn] [seon.print :as print] [seon.print.emit :as emit]))

;; ---- one page, parsed into sections ----------------------------------------------------

(defn page-sections
  "The sections of the page at path, read once (seon.ref/parse): each its address, heading,
  lead, own text, its parent's address and the citations its own text makes, each once at its
  strongest depth (seon.ref/cited)."
  {:malli/schema [:=> [:cat :string :string] [:vector :seon.section/parsed]]}
  [path text]
  (let [{:seon.ref/keys [sections citations]} (ref/parse path text)
        starts (mapv :seon.ref/line sections)
        ;; the index of the last section whose heading is at or before line l, or nil (a binary search)
        owner (fn [l] (loop [lo 0 hi (count starts)]
                        (if (< lo hi)
                          (let [mid (quot (+ lo hi) 2)] (if (<= (nth starts mid) l) (recur (inc mid) hi) (recur lo mid)))
                          (when (pos? lo) (dec lo)))))
        by-section (group-by #(owner (:seon.ref/line %)) citations)]
    (vec (for [[i {:seon.ref/keys [address heading lead parent text]}] (map-indexed vector sections)]
           (cond-> {:seon.section/address address :seon.section/heading heading
                    :seon.ref/citations (ref/cited (vec (get by-section i [])))}
             lead (assoc :seon.section/lead lead)
             text (assoc :seon.section/text text)
             parent (assoc :seon.ref/parent parent))))))

;; ---- resolving a section's citations ----------------------------------------------------

(defn- under?
  "Is address a section of a page under dir (or any of dirs)? \".\" is the checkout's top level: a page with no directory."
  {:malli/schema [:=> [:cat [:or :string [:vector :string]] :string] :boolean]}
  [dir address]
  (boolean (some (fn [d] (if (= "." d) (re-find #"^[^/#]+#" address) (str/starts-with? address (str d "/"))))
                 (if (string? dir) [dir] dir))))

(defn- citation-target
  "What citation c of a section reaches: [:section address] (a section this load holds, or one
  stored outside dir), [:var entity-id], [:missing address] for a path#anchor that is no
  section (kept as a dead link), or nil (a schema key, a file, or a name that names nothing: not stored). stored:
  the db's sections, address -> entity id; firsts: each loaded page's path -> its first
  section's address, what a link with no anchor reaches."
  {:malli/schema [:=> [:cat :seon.host/db [:or :string [:vector :string]] [:map-of :string :int] [:map-of :string :any] [:map-of :string :string] :seon.ref/citation]
                  [:maybe [:tuple :keyword :any]]]}
  [db dir stored loaded firsts {:seon.ref/keys [name path anchor]}]
  (cond
    (symbol? name) (when-let [e (pdb/eid db :var/name name)] [:var e])
    (and path anchor) (let [a (str path "#" anchor)]
                        (if (or (loaded a) (and (not (under? dir a)) (stored a))) [:section a] [:missing a]))
    path (when-let [a (firsts path)] [:section a])))

(defn- wanted
  "Section s as the attributes it should state: {attribute value}, a section named by its
  stored entity id or, when this load creates it, its tempid (its address)."
  {:malli/schema [:=> [:cat [:map-of :string :int] :seon.section/parsed [:vector :any] [:set :qualified-symbol]] :map]}
  [stored s targets names]
  (let [ref (fn [a] (get stored a a))]
    (cond-> (select-keys s [:seon.section/heading :seon.section/lead :seon.section/text])
      (seq names) (assoc :seon.section/names names)
      (:seon.ref/parent s) (assoc :seon.section/parent (ref (:seon.ref/parent s)))
      (seq (remove (comp #{:missing} first) targets))
      (assoc :seon.section/references (into #{} (comp (remove (comp #{:missing} first)) (map (fn [[k x]] (if (= :var k) x (ref x))))) targets))
      (seq (filter (comp #{:missing} first) targets))
      (assoc :seon.section/dead-links (into #{} (comp (filter (comp #{:missing} first)) (map second)) targets)))))

(def ^:private section-attrs
  "A stored section's stated attributes (seon.task/entity-tx makes them exactly what its text says).
  :seon.section/read is stated too, but by the load: it keeps the stored value while the text is unchanged."
  [:seon.section/heading :seon.section/lead :seon.section/text :seon.section/parent :seon.section/references :seon.section/names :seon.section/dead-links :seon.section/read :seon.section/baseline-unread])

(defn read-map
  "The form id of each of names that is a var of db with a form, {name form-id}: the code as it
  stands, which a section citing the names describes (:seon.section/read). The form id is the form's
  own digest, so it moves exactly when the form's text does."
  {:malli/schema [:=> [:cat :seon.host/db [:set :qualified-symbol]] [:map-of :qualified-symbol :string]]}
  [db names]
  (into {} (keep (fn [n] (when-let [e (pdb/eid db :var/name n)]
                           (when-let [id (some-> (d/entity db e) :var/form :form/id)] [n id]))))
        names))

(defn review-message
  "The message of the empty commit that records a review in git: a line, and the trailer
  `Reviewed: <address>` the wiki's baseline finds with one git log (seon.fx/wiki-history), so the
  review survives an erased store and a fresh clone."
  {:malli/schema [:=> [:cat :string :string] :string]}
  [address who]
  (str "Review " address " (" who ")\n\nReviewed: " address "\n"))

(defn reviewed-tx
  "The transaction recording that who read section address as it is though its code changed: its
  :seon.section/read made the current form ids, the text untouched, and the review (who, and the form
  ids it read at) kept as a fact. nil when no such section."
  {:malli/schema [:=> [:cat :seon.host/db :string :string] [:maybe [:vector :map]]]}
  [db address who]
  (when-let [e (pdb/eid db :seon.section/address address)]
    (let [now (read-map db (set (:seon.section/names (d/pull db [:seon.section/names] e))))]
      [{:db/id e :seon.section/read now :seon.section/reviewed {:by who :forms now}}])))

;; ---- the load ---------------------------------------------------------------------------

(defn- section-names
  "The qualified names section s (parsed) cites that name something on db (seon.ref/kept)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.section/parsed] [:set :qualified-symbol]]}
  [db s]
  ;; only a name can be one of these: kept would resolve each path citation too (a page's anchors, read from the file)
  (into #{} (comp (map :seon.ref/name) (filter qualified-symbol?))
        (ref/kept db (filterv (comp qualified-symbol? :seon.ref/name) (:seon.ref/citations s)))))

(defn load-tx
  "The transaction making db hold exactly the sections of pages (path -> text), the wiki
  under dir: every section of those pages, each stating what its own text says now, and no
  section under dir that no page holds. A section that already states it writes nothing, so
  an unchanged wiki answers []. A link (or cited path#anchor) that reaches no section is kept
  on its section as :seon.section/dead-links, and the rest of the wiki loads as it is."
  {:malli/schema [:function [:=> [:cat :seon.host/db [:or :string [:vector :string]] [:map-of :string :string]] [:vector :any]]
                  [:=> [:cat :seon.host/db [:or :string [:vector :string]] [:map-of :string :string] :map] [:vector :any]]]}
  ([db dir pages] (load-tx db dir pages {}))
  ([db dir pages baseline]
  (let [baseline (fn [a] (get baseline a))
        by-page (into [] (map (fn [[p t]] [p (page-sections p t)])) (sort pages))
        parsed (into [] (mapcat second) by-page)
        loaded (into {} (map (juxt :seon.section/address identity)) parsed)
        firsts (into {} (for [[p ss] by-page :when (seq ss)] [p (:seon.section/address (first ss))]))
        stored (into {} (map (juxt :v :e)) (d/datoms db :avet :seon.section/address))
        targets (into {} (for [s parsed]
                           [(:seon.section/address s)
                            (into [] (keep #(citation-target db dir stored loaded firsts %)) (:seon.ref/citations s))]))]
    (vec (concat
           (mapcat (fn [s]
                     (let [a (:seon.section/address s)
                           names (section-names db s)
                           e (stored a)
                           held (when e (d/pull db [:seon.section/text :seon.section/read :seon.section/baseline-unread] e))
                           keep? (and held (= (:seon.section/text s) (:seon.section/text held)) (:seon.section/read held))
                           base (when-not keep? (when (nil? (:seon.section/read held)) (baseline a)))
                           ;; what the section read is kept while its text is unchanged (the code as it stood when it was
                           ;; written); a section with none yet takes the baseline git gives (the code as of the commit that last
                           ;; changed it), else today's
                           read (cond keep? (:seon.section/read held)
                                      (:read base) (:read base)
                                      :else (read-map db names))
                           unread (cond keep? (:seon.section/baseline-unread held) :else (:unread base))
                           want (cond-> (wanted stored s (targets a) names)
                                  (seq read) (assoc :seon.section/read read)
                                  unread (assoc :seon.section/baseline-unread unread))]
                       (if e
                         (task/entity-tx db e section-attrs want)
                         [(assoc want :db/id a :seon.section/address a)])))
                   parsed)
           (for [[a e] stored :when (and (under? dir a) (not (loaded a)))]
             [:db/retractEntity e]))))))

;; ---- reading ----------------------------------------------------------------------------

(defn- children
  "The sections whose parent is section entity e on db, in the order the wiki was written."
  {:malli/schema [:=> [:cat :seon.host/db :int] [:vector :int]]}
  [db e] (vec (sort (d/q '[:find [?c ...] :in $ ?p :where [?c :seon.section/parent ?p]] db e))))

(declare resolve-ref)

(defn- edge
  "Entity e on db, a section or a var, as an edge of a view shows it: its address, heading and
  summary (a section's lead's first sentence, a var's docstring's)."
  {:malli/schema [:=> [:cat :seon.host/db :int] :map]}
  [db e]
  (let [{:seon.section/keys [address heading lead] n :var/name} (d/pull db [:seon.section/address :seon.section/heading :seon.section/lead :var/name] e)
        summary (if n (:summary (resolve-ref db n :summary)) (some-> lead ref/lead-sentence))]
    (cond-> {:seon.ref/address (or address (str n)) :seon.ref/heading (or heading (name n))}
      summary (assoc :seon.ref/summary summary))))

(defn view
  "Section address as the store holds it, shown at depth by the one rule (seon.ref/shown): its
  lead is the stored lead, the sections below are those whose parent it is (in the order the
  wiki was written), and at :code its own text whole. Whatever the depth, its edges from the
  stored facts, each at summary depth: :references, what its text cites, and :cited-by, the
  sections that cite it (unless edges? is false, as for a text made whole, page-text). nil when main holds no such section."
  {:malli/schema [:function [:=> [:cat :seon.host/db :string :seon.ref/depth] [:maybe :seon.ref/view]]
                  [:=> [:cat :seon.host/db :string :seon.ref/depth :boolean] [:maybe :seon.ref/view]]]}
  ([db address depth] (view db address depth true))
  ([db address depth edges?]
  (when-let [e (pdb/eid db :seon.section/address address)]
    (let [{:seon.section/keys [heading lead text references]} (d/pull db [:seon.section/heading :seon.section/lead :seon.section/text
                                                                           {:seon.section/references [:db/id]}] e)
          below (for [c (children db e)
                      :let [s (d/pull db [:seon.section/address :seon.section/heading] c)]]
                  {:seon.ref/address (:seon.section/address s) :seon.ref/heading (:seon.section/heading s)})
          edges (fn [es] (vec (sort-by :seon.ref/address (map #(edge db %) es))))
          cited-by (map :e (d/datoms db :avet :seon.section/references e))
          references (map :db/id references)]
      (cond-> (ref/shown (cond-> {:seon.ref/address address :seon.ref/heading heading :seon.ref/below (vec below)}
                           lead (assoc :seon.ref/lead lead)
                           (and text (= :code depth)) (assoc :seon.ref/text text))
                         depth)
        (and edges? (seq references)) (assoc :seon.ref/references (edges references))
        (and edges? (seq cited-by)) (assoc :seon.ref/cited-by (edges cited-by)))))))

(defn page-top
  "The entity of the page at path's own section (the one with no parent), or nil."
  {:malli/schema [:=> [:cat :seon.host/db :string] [:maybe pos-int?]]}
  [db path]
  (d/q '[:find ?e . :in $ ?pre :where [?e :seon.section/address ?a] [(clojure.string/starts-with? ?a ?pre)]
         (not [?e :seon.section/parent _])]
       db (str path "#")))

(defn page-t
  "The last transaction that changed the page at path on db: the newest datom, asserted or
  retracted (db's history), of any section whose address is the page's; nil when db holds none.
  The page as of it is the page as db holds it."
  {:malli/schema [:=> [:cat :seon.host/db :string] [:maybe :int]]}
  [db path]
  (let [h (d/history db)
        es (into #{} (comp (filter #(str/starts-with? (:v %) (str path "#"))) (map :e)) (d/datoms h :aevt :seon.section/address))]
    (when (seq es) (reduce max (for [e es dt (d/datoms h :eavt e)] (:tx dt))))))

(defn page
  "The page at path as db holds it, its sections in the order the wiki was written (as view
  shows the sections below): each its view at depth (:code by default) and its :seon.ref/level (1 for the
  page's own); nil when db holds no section of it."
  {:malli/schema [:function [:=> [:cat :seon.host/db :string] [:maybe [:vector :seon.ref/view]]]
                  [:=> [:cat :seon.host/db :string :seon.ref/depth] [:maybe [:vector :seon.ref/view]]]
                  [:=> [:cat :seon.host/db :string :seon.ref/depth :boolean] [:maybe [:vector :seon.ref/view]]]]}
  ([db path] (page db path :code))
  ([db path depth] (page db path depth true))
  ([db path depth edges?]
   (when-let [top (page-top db path)]
     (letfn [(walk [e level]
               (into [(assoc (view db (:seon.section/address (d/pull db [:seon.section/address] e)) depth edges?) :seon.ref/level level)]
                     (mapcat #(walk % (inc level))) (children db e)))]
       (walk top 1)))))

(defn page-text
  "The page at path as db holds it, made from its sections (page): each one's heading at its
  level, then its own text; nil when db holds none of it."
  {:malli/schema [:=> [:cat :seon.host/db :string] [:maybe :string]]}
  [db path]
  (some->> (page db path :code false)
           (map (fn [{:seon.ref/keys [heading text level]}] (cond-> (str (apply str (repeat level "#")) " " heading) text (str "\n\n" text))))
           (str/join "\n\n")))

(defn page-paths
  "The paths of the pages db holds, sorted: the sections with no parent."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :string]]}
  [db]
  (vec (sort (d/q '[:find [?p ...] :where [?e :seon.section/address ?a] (not [?e :seon.section/parent _])
                    [(clojure.string/index-of ?a "#") ?i] [(subs ?a 0 ?i) ?p]] db))))

(defn pages
  "The wiki pages under dir of the checkout (docs by default), read: {path text}, each path
  relative to the checkout (docs/flow.md; AGENTS.md for dir \".\", the top level)."
  {:malli/schema [:=> [:cat :string :string] [:map-of :string :string]]}
  [checkout dir]
  (into (sorted-map) (for [[n text] (lib/dir-texts (str checkout "/" dir) ".md")] [(if (= "." dir) n (str dir "/" n)) text])))

(defn load-dir-tx
  "The one load of the wiki under dir of the checkout (or under each of dirs, in one parse and one
  transaction) onto db: load-tx over its pages (an unchanged wiki answers [], a link to no section
  kept on its section as a dead link). Root's boot and my.root/load-docs! both write it."
  {:malli/schema [:function [:=> [:cat :seon.host/db :string [:or :string [:vector :string]]] [:vector :any]]
                  [:=> [:cat :seon.host/db :string [:or :string [:vector :string]] :map] [:vector :any]]]}
  ([db checkout dir] (load-dir-tx db checkout dir {}))
  ([db checkout dir baseline]
   (load-tx db dir (into (sorted-map) (mapcat #(pages checkout %)) (if (string? dir) [dir] dir)) baseline)))

;; ---- the baseline: what a section was written against, from git ---------------------------

(defn section-lines
  "{address [from to]} of the page at path: the lines each section's own text spans, from its heading
  to the line before the next heading (1-based, inclusive)."
  {:malli/schema [:=> [:cat :string :string] [:map-of :string [:tuple :int :int]]]}
  [path text]
  (let [ss (vec (:seon.ref/sections (ref/parse path text)))
        n (count (str/split-lines text))]
    (into {} (map-indexed (fn [i s] [(:seon.ref/address s)
                                     [(:seon.ref/line s) (if-let [nx (get ss (inc i))] (max (:seon.ref/line s) (dec (:seon.ref/line nx))) n)]]))
          ss)))

(defn baseline-plan
  "What the wiki's history must answer (the :history effect, seon.fx/wiki-history): for each section
  of pages (path -> text) with no :seon.section/read stored on db, its page, its address, its lines
  and the file and namespace of each var it cites that db has a file for. A section citing none is
  left out: nothing to record."
  {:malli/schema [:=> [:cat :seon.host/db [:map-of :string :string]] [:vector :map]]}
  [db pages]
  (let [stored (into {} (map (juxt :v :e)) (d/datoms db :avet :seon.section/address))]
    (vec (for [[p t] (sort pages) :let [lines (delay (section-lines p t))] s (page-sections p t)
               :let [a (:seon.section/address s) e (stored a)]
               :when (not (and e (:seon.section/read (d/pull db [:seon.section/read] e))))
               :let [files (into {} (keep (fn [n] (when-let [ve (pdb/eid db :var/name n)]
                                                    (let [nsx (:var/ns (d/entity db ve))]
                                                      (when (:ns/file nsx) [n {:file (:ns/file nsx) :ns (:ns/name nsx)}])))))
                                 (section-names db s))]
               :when (seq files)]
           {:path p :address a :lines (@lines a) :files files}))))

(defn baseline-failed
  "The baseline of plan when the history could not be asked at all (the :history effect failed with
  message): every section read as of today, with why."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :map] :string] :map]}
  [db plan message]
  (into {} (for [{:keys [address files]} plan] [address {:read (read-map db (set (keys files))) :unread message}])))

(defn baseline-reads
  "The baseline of the sections of plan (baseline-plan) from the history answer (seon.fx/wiki-history's
  value {:sections :files :errors}): {address {:read {name form-id} :unread reason}}. A section's :read
  is each cited var's form id in its file as of the commit that last changed the section (found by
  seon.source/defining-ids, the id seon.program/account would record) when what the section describes
  of it (seon.source/defining-meanings: its docstring and contract as written) is what its form
  says today, \"changed\" when that changed since, or \"absent\" for a var the
  file at that commit does not mention at all, or a file that did not exist: a citation that was ahead
  of the code, stale once the code exists. A section with an uncommitted line is read as of today
  (:read, nothing unread): its text is newer than git's. A section git could not answer for, whose
  old file git could not show or does not read, or that cites a var the old file mentions but no form
  this reading knows (defining-ids finds a form by its head) defines, is read as of today with :unread
  naming why (attention, seon.problems/stale-sections), never a guess of \"absent\". The failure
  point: an old file that does not read."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :map] :map] :map]}
  [db plan {:keys [sections files errors]}]
  (let [cache (volatile! {})
        ids-at (fn [sha file nsym]
                 (or (@cache [sha file])
                     (let [text (get files [sha file])
                           r (cond (get errors [sha file]) {:error (str file " at " (subs sha 0 8) " could not be read from git: " (get errors [sha file]))}
                                   (nil? text) {:ids {}}
                                   :else (try {:ids (source/defining-ids nsym text) :meanings (source/defining-meanings nsym text) :text text :ns nsym}
                                              (catch Throwable e {:error (str file " at " (subs sha 0 8) " does not read: " (step/error-msg e))})))]
                       (vswap! cache assoc [sha file] r)
                       r)))
        ;; what a var's form today says of it, read as the old file is (the same reader, both sides as written)
        meaning-now (fn [n] (when-let [f (some->> (pdb/eid db :var/name n) (d/entity db) :var/form)]
                              (get (source/defining-meanings (:ns/name (:form/ns f)) (:form/src f)) n)))]
    (into {}
          (keep (fn [{:keys [address] :as r}]
                  (let [info (get sections address) sha (:commit info)
                        today (read-map db (set (keys (:files r))))]
                    (cond
                      (and info (not (:error info)) (nil? sha)) [address {:read today}]
                      (or (nil? info) (:error info)) [address {:read today :unread (or (:error info) "git gave no history for the section")}]
                      :else (let [per (for [[n {:keys [file ns]}] (:files r)] [n (ids-at sha file ns)])
                                  bad (or (some :error (map second per))
                                          (some (fn [[n res]] (when (and (not (contains? (:ids res) n)) (:text res) (source/mentions? (:ns res) (:text res) (symbol (name n))))
                                                                (str n " is in its file at " (subs sha 0 8) ", but no form this reading knows (one by its head) defines it")))
                                                per))]
                              (if bad
                                [address {:read today :unread bad}]
                                ;; a var the old file has no form for is :absent; one whose form changed
                                ;; since is :changed when what the section describes of it (its docstring
                                ;; or contract) changed, else today's form id
                                [address {:read (into {} (for [[n res] per :let [then (get (:ids res) n :absent)]]
                                                           [n (cond (or (= :absent then) (= then (today n))) then
                                                                    (= (get (:meanings res) n) (meaning-now n)) (today n)
                                                                    :else :changed)]))}]))))))
          plan)))

;; ---- a citation as its depth shows it (docs/references.md#resolving-and-showing): ----
;; what the model's context and every page pull of what a text cites

(defn source-of
  "The recorded source of var sym on db (the text of the form that last defined it), or nil:
  one AVET seek (seon.db/eid), never a query."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-symbol] [:maybe :string]]}
  [db sym] (some->> (pdb/eid db :var/name sym) (d/entity db) :var/form :form/src))

(defn callers
  "The program vars whose recorded definition names x on db, sorted, x itself left out: a var by
  its :form/refs (calls it) or its :form/quoted (names it as data: a render, a proc, a form sent
  to an agent), a qualified keyword (an attribute, config key or schema entry) by its
  :form/keywords, the data it operates on. The one query my.agent/callers and the task's data ask."
  {:malli/schema [:=> [:cat :seon.host/db [:or :qualified-symbol :qualified-keyword]] [:vector :qualified-symbol]]}
  [db x]
  (vec (sort (disj (into #{} (mapcat #(d/q '[:find [?v ...] :in $ ?a ?s :where [?f ?a ?s] [?f :form/defines ?e] [?e :var/name ?v]] db % x))
                         (if (keyword? x) [:form/keywords] [:form/refs :form/quoted]))
                   x))))

(defn citing-sections
  "The addresses of the wiki sections that cite name x (a var or a keyword: :seon.section/names),
  at most :seon.config.ctx/cited-by of them, those of the decisions page last (a ruling cites what
  it concerns; a page about the mechanism says what x does). So a var a task cites carries the
  sections it owes an edit to and the sections that explain it. See docs/references.md#the-wiki-in-the-store."
  {:malli/schema [:=> [:cat :seon.host/db [:or :qualified-symbol :qualified-keyword]] [:vector :string]]}
  [db x]
  (->> (d/q '[:find [?a ...] :in $ ?n :where [?e :seon.section/names ?n] [?e :seon.section/address ?a]] db x)
       (sort-by (juxt #(str/starts-with? % "docs/decisions.md") str))
       (take (cfg/value :seon.config.ctx/cited-by))
       vec))

(def text-attrs
  "Each searchable kind of text and the attribute holding it, the kinds seon.vector embeds: a form's
  source names a :var (or a :test), a wiki section's text a :section, a task's plan a :task, a
  schema entry's form a :schema."
  {:var :form/src :test :form/src :section :seon.section/text :task :seon.task/plan :schema :schema/form})

(defn line-at
  "Where index i of text v falls: {:at the 1-based number of its line, :line the whole line it begins in, trimmed
  (a match may itself hold a line break)}. The one line-at-an-offset computation of a search hit, context's repairs and
  repl's edit! (docs/references.md#resolving-and-showing)."
  {:malli/schema [:=> [:cat :string :int] [:map [:at pos-int?] [:line :string]]]}
  [v i]
  {:at (inc (count (re-seq #"\n" (subs v 0 i))))
   :line (str/trim (subs v (inc (or (str/last-index-of v "\n" (max 0 (dec i))) -1)) (or (str/index-of v "\n" i) (count v))))})

(defn- named-hits
  "The hits of text v of entity e under attribute a, naming it as its kind does: [{:kind :name :rank}]
  (rank 0 when the name holds s, 1 when its doc or heading does, 2 else), kinds the ones asked; has? asks whether a text holds s."
  {:malli/schema [:=> [:cat :seon.host/db :int :qualified-keyword [:set :keyword] ifn?] [:vector :map]]}
  [db e a kinds has?]
  (let [x (d/entity db e)
        in? (fn [t] (and t (has? (str t))))]
    (case a
      :form/src (vec (for [dv (d/datoms db :eavt e :form/defines)
                           :let [v (d/entity db (:v dv)) n (:var/name v) kind (if (= :test (:var/kind v)) :test :var)]
                           :when (and n (kinds kind))]
                       {:kind kind :name n :rank (cond (in? (name n)) 0 (in? (:var/doc v)) 1 :else 2)}))
      :seon.section/text (when (kinds :section)
                           [{:kind :section :name (:seon.section/address x)
                             :rank (cond (in? (:seon.section/address x)) 0 (in? (:seon.section/heading x)) 1 :else 2)}])
      :seon.task/plan (when (kinds :task) [{:kind :task :name (:seon.task/id x) :rank (if (in? (:seon.task/id x)) 0 2)}])
      :schema/form (when (kinds :schema) [{:kind :schema :name (:schema/key x) :rank (if (in? (str (:schema/key x))) 0 2)}]))))

(defn terms
  "Text s's terms, its identifier tokens, lower case: s split at every character that is no letter or digit
  and between a lower and an upper case letter, those of two characters or more. What search and relevance
  match (`words`, seon.context/relevance's lexical feature), so `seon.vector/near` and `near-of` match the words near and of. One
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


(defn words
  "The distinct `terms` of text s, in order: an identifier (datoms-diff, datomsDiff, :seon.run/outcome) is
  its parts; a one-letter part is no word (search counts a word wherever it occurs in a text)."
  {:malli/schema [:=> [:cat :string] [:vector :string]]}
  [s] (vec (distinct (terms s))))

(defn- idf-of
  "BM25's idf of a word held by df of n texts."
  {:malli/schema [:=> [:cat :int :int] :double]}
  [n df]
  (Math/log (+ 1.0 (/ (+ (- n df) 0.5) (+ df 0.5)))))

(defn- bm25-scores
  "Each text's BM25 term, fs its word counts and lens its lengths, against the corpus' average length avg
  and each word's idf (the parameters of `bm25`)."
  {:malli/schema [:=> [:cat :map :double :map [:sequential [:map-of :string :int]] [:sequential number?]] [:vector number?]]}
  [{k1 :bm25-k1 b :bm25-b} avg idf fs lens]
  ;; over each text's own counts: a text holding no query word (an empty map) allocates nothing
  (mapv (fn [f len] (reduce-kv (fn [acc w c] (if-let [i (when (pos? c) (idf w))]
                                              (+ acc (* i (/ (* c (inc k1)) (+ c (* k1 (+ (- 1 b) (* b (/ len avg)))))))) acc))
                               0.0 f))
        fs lens))

(defn bm25
  "Each text's BM25 score (Robertson and Zaragoza 2009; k1 and b :seon.config.render/relevance-features's
  :bm25-k1 and :bm25-b) for query words qs, fs each text's word counts and lens its length (in whatever
  unit, the same for every text); its idf counted over fs, so the texts scored are their own corpus and no
  index is read. In fs's order. The one text ranking: relevance's (seon.context/relevance) pool, and the
  term `tokens` scores the store's texts by from their index (`text-index`)."
  {:malli/schema [:=> [:cat [:set :string] [:sequential [:map-of :string :int]] [:sequential number?]] [:vector number?]]}
  [qs fs lens]
  (let [feat (cfg/value :seon.config.render/relevance-features)
        n (count fs) avg (max 1.0 (/ (reduce + 0.0 lens) (max 1 n)))
        idf (into {} (for [w qs :let [df (reduce #(if (pos? (get %2 w 0)) (inc %1) %1) 0 fs)]] [w (idf-of n df)]))]
    (bm25-scores feat avg idf fs lens)))

(defonce ^:private indexes
  ;; attribute -> [the db's [:hash :max-tx] it was checked at, the fingerprint of its texts, their text-index]; one slot an attribute, replaced by the next change to its texts
  (volatile! {}))

(defn- fingerprint
  "What the texts of attribute a on db are, as a number: how many, and the sum of the hashes of their entities and
  values (a string caches its hash), so it moves when a text is added, removed or changed and not when another
  attribute is written. One read of the attribute's datoms."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-keyword] [:tuple :int :int]]}
  [db a]
  (reduce (fn [[n h] d] [(inc n) (+ h (* 31 (hash (:e d))) (hash (:v d)))]) [0 0] (d/datoms db :aevt a)))

(defn- build-index
  "The postings of attribute a's texts on db: {:n texts :total characters :lens {e length} :post {term {e count}}}."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-keyword] :map]}
  [db a]
  (let [post (volatile! (transient {})) lens (volatile! (transient {}))
        total (volatile! 0)]
    (doseq [{:keys [e v]} (d/datoms db :aevt a)]
      (vswap! lens assoc! e (+ (count v) (get @lens e 0)))
      (vswap! total + (count v))
      (doseq [[w c] (frequencies (terms v))]
        (vswap! post assoc! w (assoc (get @post w {}) e c))))
    (let [lens (persistent! @lens)]
      {:n (count lens) :total @total :lens lens :post (persistent! @post)})))

(defn text-index
  "The postings of attribute a's texts on db, {:n :total :lens :post}: for each `terms` term, the texts holding it
  and how many times. Derived data (AGENTS section 5): built once for the texts as they are (the db's :hash and :max-tx say it is the same value; when it moved, their `fingerprint`: a db value
  is read again at every deref, so its identity says nothing) and reused until one of them changes,
  regenerated from db whenever it is gone, never relied on. A race of two readers only builds one twice.
  See docs/vector.md#search."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-keyword] :map]}
  [db a]
  (let [at [(:hash db) (:max-tx db)] [was fp ix] (get @indexes a)]
    (if (= at was)
      ix
      ;; the db moved (a write to any attribute): its texts are read once for their fingerprint; the index stands when they are the same
      (let [now (fingerprint db a)
            ix (if (= now fp) ix (build-index db a))]
        (vswap! indexes assoc a [at now ix])
        ix))))

(defn- counts-in
  "The texts of postings ix holding a query word: {e {word count}}, a word counted in every term that contains it
  (a scan of the vocabulary, not of the texts)."
  {:malli/schema [:=> [:cat :map [:sequential :string]] [:map-of :any [:map-of :string :int]]]}
  [ix ws]
  (reduce-kv (fn [m term es]
               (reduce (fn [m w]
                         (if (str/includes? term w)
                           (reduce-kv (fn [m e c] (assoc m e (assoc (get m e {}) w (+ c (get-in m [e w] 0))))) m es)
                           m))
                       m ws))
             {} (:post ix)))

(defn tokens
  "The texts on db that hold the words of query s, of kinds, best first, each {:kind :name :z :line :at}: `bm25`'s
  term over each text's count of each word of `words` (its length in characters), scored against every text of
  those kinds, z its score in standard deviations above their mean (every text, those holding no word scoring 0). A word is
  counted in every `terms` term that contains it. Only the texts with z >= z-min are answered. The line is the
  first holding a word. Cost: a scan of the vocabulary of `text-index` (built once a change of the store, not a
  query) and the postings of the words, and per hit its text and names; the one text ranking of my.agent/search.
  See docs/vector.md#search."
  {:malli/schema [:=> [:cat :seon.host/db :string [:set :keyword] :double] [:vector :map]]}
  [db s kinds z-min]
  (let [ws (words s)
        attrs (distinct (keep text-attrs kinds))
        ixs (map #(vector % (text-index db %)) attrs)
        n (reduce + (map (comp :n second) ixs))]
    (if (or (empty? ws) (zero? n))
      []
      (let [docs (for [[a ix] ixs [e tf] (counts-in ix ws)] {:a a :e e :tf tf :len (get (:lens ix) e)})
            avg (max 1.0 (/ (reduce + 0.0 (map (comp :total second) ixs)) n))
            idf (into {} (for [w ws] [w (idf-of n (count (filter #(pos? (get (:tf %) w 0)) docs)))]))
            feat (cfg/value :seon.config.render/relevance-features)
            scored (map #(assoc %1 :score %2) docs (bm25-scores feat avg idf (map :tf docs) (map :len docs)))
            sum (reduce + (map :score scored))
            sumsq (reduce + (map #(* (:score %) (:score %)) scored))
            mean (/ sum n)
            sd (Math/sqrt (max 0.0 (- (/ sumsq n) (* mean mean))))]
        (if (zero? sd)
          []
          (let [hits (for [d scored :let [z (/ (- (:score d) mean) sd)] :when (>= z z-min)
                           h (named-hits db (:e d) (:a d) kinds (fn [t] (some #(str/includes? (str/lower-case t) %) ws)))
                           :let [v (:v (first (d/datoms db :eavt (:e d) (:a d)))) low (str/lower-case v)
                                 i (some->> ws (keep #(str/index-of low %)) seq (apply min))]]
                       (cond-> (assoc h :z z :bm25 (:score d))
                         (and i (= (count low) (count v)))
                         (merge (line-at v i))))]
            (->> hits (sort-by (juxt (comp - :z) (comp str :name)))
                 (reduce (fn [[seen out :as acc] h] (let [k ((juxt :kind :name) h)] (if (seen k) acc [(conj seen k) (conj out (dissoc h :rank))])))
                         [#{} []])
                 second)))))))

(defn resolve-ref
  "Var sym as its citation's depth shows it (docs/references.md#resolving-and-showing):
  :summary its arglists and its doc's first sentence, :doc its arglists, doc and contract,
  :code its source (from the db), whole: the printer cuts it where it is shown, with the form
  that reads its rest, and the form that changes a part of it (seon.repl/edit!); at :doc a var with no doc shows its source. A name that resolves to
  nothing says so. A var the db holds is read from its facts (its doc, its contract, its
  recorded source and the arglists it declares), so what it shows changes as the db does; a
  library var the db does not hold, from the process's var meta, which its build fixes."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-symbol :seon.ref/depth] :map]}
  [db sym depth]
  (let [v (some->> (pdb/eid db :var/name sym) (d/entity db))
        src (source-of db sym)
        ;; arglists only where they are shown: a source read once per such citation
        m (if v
            (cond-> {}
              (and src (#{:summary :doc} depth)) (assoc :arglists (reader/arglists src (symbol (namespace sym))))
              (:var/doc v) (assoc :doc (:var/doc v))
              ;; as the import wrote it (seon.edn/encode): a contract that does not decode throws
              (:var/contract v) (assoc :malli/schema (sedn/decode (:var/contract v))))
            (some-> (find-ns (symbol (namespace sym))) ns-interns (get (symbol (name sym))) meta))
        doc (cond-> {:symbol sym}
              (:arglists m) (assoc :arglists (:arglists m))
              ;; prose, read as itself: unquoted, no escaped quotes (seon.print's prose mark)
              (:doc m) (assoc :doc {:seon.print/prose (:doc m)})
              (:malli/schema m) (assoc :contract (:malli/schema m)))]
    (cond
      (and (nil? v) (nil? m)) {:symbol sym :unresolved true :problem (:unresolved ref/problem-messages)}
      (= :summary depth) (cond-> (select-keys doc [:symbol :arglists]) (:doc m) (assoc :summary (ref/lead-sentence (:doc m))))
      (or (nil? src) (and (= :doc depth) (:doc m))) doc
      ;; its source is code, printed as in its file (seon.print/code), and how a part of it changes
      :else {:symbol sym :source (print/code src)
             :change (print/code (str "(seon.repl/edit! '" sym " \"old text, exactly once\" \"new text\")"))})))

(defn- reached
  "What citation c asks to see, at its depth, and what that is: a wiki section from main's
  sections (:section), else a file of the checkout read now, never stored (:file), else a task by
  its ns/name (:task), else a namespace (:namespace); a citation that reaches nothing says so, as
  an unresolved name does (:unresolved). Answers [kind view]."
  {:malli/schema [:=> [:cat :seon.host/db :seon.ref/citation] [:tuple :seon.render/kind :map]]}
  [db {:seon.ref/keys [name path anchor depth]}]
  (let [a (or (some-> name str) (str path (when anchor (str "#" anchor))))
        [kind v] (cond (qualified-symbol? name) [:task (ref/task-view db name depth)]
                       name [:namespace (ref/namespace-view db name depth)]
                       :else (if-let [sv (when anchor (view db a depth))]
                               [:section sv]
                               [:file (ref/file-view db (cfg/checkout) path anchor depth)]))]
    (if v
      [kind (assoc v :seon.ref/depth depth)]
      [:unresolved {:seon.ref/address a :unresolved true :problem (:unresolved ref/problem-messages)}])))

(defn cited-view
  "What citation c asks to see, at its depth (reached's view): a section, a file, a task, a
  namespace, or that it reaches nothing."
  {:malli/schema [:=> [:cat :seon.host/db :seon.ref/citation] :map]}
  [db c] (second (reached db c)))

(defn citation-kind
  "What citation c reaches on db: :var, a qualified name that is no task (resolve-ref); :schema, a
  qualified keyword the db holds a schema entry of (schema-view); :view, a namespace, a task, or a
  path with no problem (cited-view); nil, nothing pulled (any other keyword, a path:N line reference)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.ref/citation] [:maybe [:enum :var :schema :view]]]}
  [db {:seon.ref/keys [name path problem-kind]}]
  (cond (and (qualified-symbol? name) (not (ref/task? db name))) :var
        (and (qualified-keyword? name) (pdb/eid db :schema/key name)) :schema
        (or (symbol? name) (and path (not problem-kind))) :view))

(defn schema-view
  "Schema key k as its citation's depth shows it (docs/references.md#resolving-and-showing),
  read from the db's schema entry (:schema/form), so it changes as the db does: :summary the form
  that reads it; :doc and :code its form as written (code), the form of each other key it names
  (one level: the agent of the md-scan bench read them one a turn), and how it changes
  at the REPL (my.schema/register!). Never the resource file it merges into: an agent's working
  directory holds no checkout, and the file sent three runs to slurp it."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-keyword :seon.ref/depth] :map]}
  [db k depth]
  (let [form (:schema/form (d/entity db (pdb/eid db :schema/key k)))
        named (when form
                (->> (tree-seq coll? seq (sedn/decode form))
                     (filter qualified-keyword?) distinct
                     (keep #(when-let [f (and (not= k %) (:schema/form (d/entity db (pdb/eid db :schema/key %))))] [% (print/code f)]))
                     (into (sorted-map))))]
    (if (or (= :summary depth) (nil? form))
      {:schema k :read (list 'my.schema/forms k)}
      (cond-> {:schema k :form (print/code form) :change (print/code (str "(my.schema/register! " k " '[...its new form])"))}
        (seq named) (assoc :keys-it-names named)))))

(defn cited
  "Citation c pulled at its depth, as citation-kind reaches it, and what it reached: [:var view]
  by resolve-ref ([:unresolved view] when it resolves to nothing), [:schema view] by schema-view,
  anything else by reached; nil when it reaches nothing pulled (a keyword the db holds no schema of,
  a path:N line reference)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.ref/citation] [:maybe [:tuple :seon.render/kind :map]]]}
  [db c]
  (case (citation-kind db c)
    :var (let [v (resolve-ref db (:seon.ref/name c) (:seon.ref/depth c))] [(if (:unresolved v) :unresolved :var) v])
    :schema [:schema (schema-view db (:seon.ref/name c) (:seon.ref/depth c))]
    :view (reached db c)
    nil))

(defn- names-lines
  "Words (a namespace's public names, a docstring's words) as comment lines of at most
  :seon.config.render/width characters: `;; a b c`."
  {:malli/schema [:=> [:cat [:sequential :string]] :string]}
  [names]
  (let [width (cfg/value :seon.config.render/width)]
    (str/join "\n" (map #(str ";; " (str/join " " %))
                        (reduce (fn [lines n] (let [l (peek lines)]
                                                (if (and l (<= (+ 4 (count (str/join " " l)) (count n)) width))
                                                  (conj (pop lines) (conj l n))
                                                  (conj lines [n]))))
                                [] names)))))

(defn definition-lines
  "Namespace view nv's definitions as comment lines, one each in file order: its name and its doc's
  first sentence (seon.ref/namespace-view's headings), or nil when it lists none. What a namespace's
  citation and `(source ns)` show below its head."
  {:malli/schema [:=> [:cat [:maybe :seon.ref/view]] [:maybe :string]]}
  [nv]
  (when-let [b (seq (:seon.ref/below nv))] (str/join "\n" (map #(str ";; " (:seon.ref/heading %)) b))))

(defn namespace-source
  "Namespace sym as db records it: its ns form as written (its requires and aliases), then each
  definition, privates included, a line each in file order (`definition-lines`); nil when db
  records no ns form for it. What `(source ns)` and a namespace's citation at code depth show
  (docs/references.md#resolving-and-showing)."
  {:malli/schema [:=> [:cat :seon.host/db :symbol] [:maybe :string]]}
  [db sym]
  (when-let [head (d/q '[:find ?src . :in $ ?s :where [?n :ns/name ?s] [?f :form/defines ?n] [?f :form/src ?src]] db sym)]
    (str head (some->> (definition-lines (ref/namespace-view db sym :doc)) (str "\n")))))

(defn cited-code
  "What the page shows of citation c on db, as Clojure is written in a file (DECISIONS,
  code is shown as code): a var as its definition's head (seon.reader/definition-head: name, docstring as
  written, attribute map, arglist; its body marked `…)` with the form that reads it whole), a
  namespace as its ns form as recorded (`namespace-source`), then its definitions; each as code
  (seon.print/code), printed verbatim; a var whose source db does not record, as a `declare` with
  its arglists and doc as comments; a task, a section, a file or a citation that reaches nothing as
  its address and lead in comment lines, then the form that reads it whole (never its view's map). nil when it reaches nothing pulled."
  {:malli/schema [:=> [:cat :seon.host/db :seon.ref/citation] :any]}
  [db c]
  (let [[kind v :as hit] (cited db c)
        nm (:seon.ref/name c)]
    (cond
      (and (= :var kind) (qualified-symbol? nm))
      (let [src (source-of db nm)
            head (some-> src (reader/definition-head (symbol (namespace nm))))]
        (cond head (print/code (str head "  ; the body: (source " nm ")"))
              src (print/code src)
              ;; no source recorded: what the var's facts say, as Clojure (its arglists and doc as comments)
              :else (print/code (str "(declare " (name nm) ")  ; no source recorded: (doc " nm ")"
                                     (when-let [as (seq (:arglists v))] (str "\n;; " (str/join " " (map str as))))
                                     (when-let [d (:seon.print/prose (:doc v))] (str "\n" (names-lines (str/split d #"\s+"))))))))
      (and (= :namespace kind) (symbol? nm))
      ;; its ns form as recorded; a namespace only this process has loaded, its name, doc and public names
      (print/code (or (namespace-source db nm)
                      (let [nv (ref/namespace-view db nm :doc)]
                        (str "(ns " nm (when-let [d (:seon.ref/lead nv)] (str "\n  " (emit/string-literal d))) ")"
                             (some->> (definition-lines nv) (str "\n"))))))
      ;; a task, a section, a file, or what reaches nothing: its address and lead as prose (comment
      ;; lines), then the form that reads it whole; never its view's map
      hit
      (let [a (or (:seon.ref/address v) (some-> nm str) (:seon.ref/path c))
            say (if (= :unresolved kind)
                  (str (or (:problem v) "reaches nothing"))
                  (or (some-> (:seon.ref/lead v) ref/lead-sentence) (:seon.ref/summary v)
                      (some->> (:seon.ref/lines v) (str "a file of ") (#(str % " lines"))) (:seon.ref/heading v)))
            read (case kind
                   :task (str "(:seon.task/plan (seon.task/task (seon.agent/db) " (emit/string-literal a) "))")
                   :section (str "(my.agent/help " (emit/string-literal a) ")")
                   :file (str "(slurp " (emit/string-literal (str (cfg/checkout) "/" (:seon.ref/path c))) ")")
                   nil)]
        (print/code (str (names-lines (str/split (str a ": " say) #"\s+")) (when read (str "\n" read))))))))
