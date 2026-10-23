(ns seon.docs
  "The wiki as data in the store (task seon.docs/docs-are-data-in-the-store,
  docs/references.md#the-wiki-in-the-store): a section entity per heading of docs/*.md, read
  by the one parse (seon.ref/parse). A section's references are what its own text cites,
  resolved on the db (a link to a section, a name to a var), so its backlinks are who cites
  it. Loading writes only what differs from what main holds, as task files do. A citation is
  pulled here at its depth (cited: a var, a section, a file, a task or a namespace), for the
  model's context and every walk (seon.render/walk) alike."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.step :as step] [seon.contract :refer [defn defn-]] [seon.ref :as ref] [seon.source :as source] [seon.task :as task] [seon.db :as pdb]
            [datahike.api :as d] [clojure.string :as str] [clojure.java.io :as io]
            [seon.print.select :as select] [seon.config :as cfg]))

;; ---- one page, parsed into sections ----------------------------------------------------

(defn page-sections
  "The sections of the page at path, read once (seon.ref/parse): each its address, heading,
  lead, own text, its parent's address and the citations its own text makes, each once at its
  strongest depth (seon.ref/cited)."
  {:malli/schema [:=> [:cat :string :string] [:vector :seon.section/parsed]]}
  [path text]
  (let [{:seon.ref/keys [sections citations]} (ref/parse path text)
        starts (mapv :seon.ref/line sections)
        ;; the index of the last section whose heading is at or before line l, or nil
        owner (fn [l] (let [i (dec (count (take-while #(<= % l) starts)))] (when-not (neg? i) i)))
        by-section (group-by #(owner (:seon.ref/line %)) citations)]
    (vec (for [[i {:seon.ref/keys [address heading lead parent text]}] (map-indexed vector sections)]
           (cond-> {:seon.section/address address :seon.section/heading heading
                    :seon.ref/citations (ref/cited (vec (get by-section i [])))}
             lead (assoc :seon.section/lead lead)
             text (assoc :seon.section/text text)
             parent (assoc :seon.ref/parent parent))))))

;; ---- resolving a section's citations ----------------------------------------------------

(defn- under?
  "Is address a section of a page under dir?"
  {:malli/schema [:=> [:cat :string :string] :boolean]}
  [dir address] (str/starts-with? address (str dir "/")))

(defn- citation-target
  "What citation c of a section reaches: [:section address] (a section this load holds, or one
  stored outside dir), [:var entity-id], [:missing address] for a path#anchor that is no
  section (kept as a dead link), or nil (a schema key, a file, or a name that names nothing: not stored). stored:
  the db's sections, address -> entity id; firsts: each loaded page's path -> its first
  section's address, what a link with no anchor reaches."
  {:malli/schema [:=> [:cat :seon.host/db :string [:map-of :string :int] [:map-of :string :any] [:map-of :string :string] :seon.ref/citation]
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
  (into #{} (comp (map :seon.ref/name) (filter qualified-symbol?)) (ref/kept db (:seon.ref/citations s))))

(defn load-tx
  "The transaction making db hold exactly the sections of pages (path -> text), the wiki
  under dir: every section of those pages, each stating what its own text says now, and no
  section under dir that no page holds. A section that already states it writes nothing, so
  an unchanged wiki answers []. A link (or cited path#anchor) that reaches no section is kept
  on its section as :seon.section/dead-links, and the rest of the wiki loads as it is."
  {:malli/schema [:function [:=> [:cat :seon.host/db :string [:map-of :string :string]] [:vector :any]]
                  [:=> [:cat :seon.host/db :string [:map-of :string :string] :map] [:vector :any]]]}
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

(defn section
  "Section address on db, its parent as an address and its references as addresses or var
  names, or nil."
  {:malli/schema [:=> [:cat :seon.host/db :string] [:maybe :map]]}
  [db address]
  (when (pdb/eid db :seon.section/address address)
    (let [s (d/pull db [:seon.section/address :seon.section/heading :seon.section/lead :seon.section/text
                        {:seon.section/parent [:seon.section/address]}
                        {:seon.section/references [:seon.section/address :var/name]}]
                    [:seon.section/address address])]
      (cond-> s
        (:seon.section/parent s) (update :seon.section/parent :seon.section/address)
        (:seon.section/references s) (update :seon.section/references
                                             #(into #{} (map (fn [r] (or (:seon.section/address r) (:var/name r)))) %))))))

(defn- children
  "The sections whose parent is section entity e on db, in the order the wiki was written."
  {:malli/schema [:=> [:cat :seon.host/db :int] [:vector :int]]}
  [db e] (vec (sort (d/q '[:find [?c ...] :in $ ?p :where [?c :seon.section/parent ?p]] db e))))

(defn view
  "Section address as the store holds it, shown at depth by the one rule (seon.ref/shown): its
  lead is the stored lead, the sections below are those whose parent it is (in the order the
  wiki was written), and at :code its own text whole. nil when main holds no such section."
  {:malli/schema [:=> [:cat :seon.host/db :string :seon.ref/depth] [:maybe :seon.ref/view]]}
  [db address depth]
  (when-let [e (pdb/eid db :seon.section/address address)]
    (let [{:seon.section/keys [heading lead text]} (d/pull db [:seon.section/heading :seon.section/lead :seon.section/text] e)
          below (for [c (children db e)
                      :let [s (d/pull db [:seon.section/address :seon.section/heading] c)]]
                  {:seon.ref/address (:seon.section/address s) :seon.ref/heading (:seon.section/heading s)})]
      (ref/shown (cond-> {:seon.ref/address address :seon.ref/heading heading :seon.ref/below (vec below)}
                   lead (assoc :seon.ref/lead lead)
                   (and text (= :code depth)) (assoc :seon.ref/text text))
                 depth))))

(defn page
  "The page at path as db holds it, its sections in the order the wiki was written (as view
  shows the sections below): each its address, heading, level (1 for the page's own), lead
  and own text; nil when db holds no section of it."
  {:malli/schema [:=> [:cat :seon.host/db :string] [:maybe [:vector [:map [:address :string] [:heading :string] [:level pos-int?]]]]]}
  [db path]
  (when-let [top (d/q '[:find ?e . :in $ ?pre :where [?e :seon.section/address ?a] [(clojure.string/starts-with? ?a ?pre)]
                        (not [?e :seon.section/parent _])]
                      db (str path "#"))]
    (letfn [(walk [e level]
              (let [{:seon.section/keys [address heading lead text]}
                    (d/pull db [:seon.section/address :seon.section/heading :seon.section/lead :seon.section/text] e)]
                (into [(cond-> {:address address :heading heading :level level} lead (assoc :lead lead) text (assoc :text text))]
                      (mapcat #(walk % (inc level))) (children db e))))]
      (walk top 1))))

(defn page-text
  "The page at path as db holds it, made from its sections (page): each one's heading at its
  level, then its own text; nil when db holds none of it."
  {:malli/schema [:=> [:cat :seon.host/db :string] [:maybe :string]]}
  [db path]
  (some->> (page db path)
           (map (fn [{:keys [heading level text]}] (cond-> (str (apply str (repeat level "#")) " " heading) text (str "\n\n" text))))
           (str/join "\n\n")))

(defn pages
  "The wiki pages under dir of the checkout (docs by default), read: {path text}, each path
  relative to the checkout (docs/flow.md)."
  {:malli/schema [:=> [:cat :string :string] [:map-of :string :string]]}
  [checkout dir]
  (into (sorted-map)
        (for [f (sort-by #(.getName %) (or (seq (.listFiles (io/file checkout dir))) []))
              :let [n (.getName f)] :when (.endsWith n ".md")]
          [(str dir "/" n) (slurp f)])))

(defn load-dir-tx
  "The one load of the wiki under dir of the checkout onto db: load-tx over its pages (an
  unchanged wiki answers [], a link to no section kept on its section as a dead link). Root's
  boot and my.root/load-docs! both write it."
  {:malli/schema [:function [:=> [:cat :seon.host/db :string :string] [:vector :any]]
                  [:=> [:cat :seon.host/db :string :string :map] [:vector :any]]]}
  ([db checkout dir] (load-dir-tx db checkout dir {}))
  ([db checkout dir baseline] (load-tx db dir (pages checkout dir) baseline)))

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
    (vec (for [[p t] (sort pages) :let [lines (section-lines p t)] s (page-sections p t)
               :let [a (:seon.section/address s) e (stored a)]
               :when (not (and e (:seon.section/read (d/pull db [:seon.section/read] e))))
               :let [files (into {} (keep (fn [n] (when-let [ve (pdb/eid db :var/name n)]
                                                    (let [nsx (:var/ns (d/entity db ve))]
                                                      (when (:ns/file nsx) [n {:file (:ns/file nsx) :ns (:ns/name nsx)}])))))
                                 (section-names db s))]
               :when (seq files)]
           {:path p :address a :lines (lines a) :files files}))))

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
  seon.source/defining-ids, the id seon.program/account would record), or \"absent\" for a var the
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
                                   :else (try {:ids (source/defining-ids nsym text) :text text}
                                              (catch Throwable e {:error (str file " at " (subs sha 0 8) " does not read: " (step/error-msg e))})))]
                       (vswap! cache assoc [sha file] r)
                       r)))
        mentioned? (fn [text n] (boolean (re-find (re-pattern (str "[\\s(\\[{'#]" (java.util.regex.Pattern/quote (name n)) "[\\s)\\]}]")) text)))]
    (into {}
          (keep (fn [{:keys [address] :as r}]
                  (let [info (get sections address) sha (:commit info)
                        today (read-map db (set (keys (:files r))))]
                    (cond
                      (and info (not (:error info)) (nil? sha)) [address {:read today}]
                      (or (nil? info) (:error info)) [address {:read today :unread (or (:error info) "git gave no history for the section")}]
                      :else (let [per (for [[n {:keys [file ns]}] (:files r)] [n (ids-at sha file ns)])
                                  bad (or (some :error (map second per))
                                          (some (fn [[n res]] (when (and (not (contains? (:ids res) n)) (:text res) (mentioned? (:text res) n))
                                                                (str n " is in its file at " (subs sha 0 8) ", but no form this reading knows (one by its head) defines it")))
                                                per))]
                              (if bad
                                [address {:read today :unread bad}]
                                [address {:read (into {} (for [[n res] per] [n (get (:ids res) n "absent")]))}]))))))
          plan)))

;; ---- a citation as its depth shows it (docs/references.md#the-delimiter-picks-the-depth): ----
;; what the model's context and every walk (seon.render/walk) pull of what a text cites

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
  "What citation c reaches on db: :var, a qualified name that is no task (resolve-ref); :view, a
  namespace, a task, or a path with no problem (cited-view); nil, nothing pulled (a keyword, a
  path:N line reference)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.ref/citation] [:maybe [:enum :var :view]]]}
  [db {:seon.ref/keys [name path problem-kind]}]
  (cond (and (qualified-symbol? name) (not (ref/task? db name))) :var
        (or (symbol? name) (and path (not problem-kind))) :view))

(defn cited
  "Citation c pulled at its depth, as citation-kind reaches it, and what it reached: [:var view]
  by resolve-ref ([:unresolved view] when it resolves to nothing), anything else by reached; nil
  when it reaches nothing pulled (a keyword, a path:N line reference)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.ref/citation] [:maybe [:tuple :seon.render/kind :map]]]}
  [db c]
  (case (citation-kind db c)
    :var (let [v (resolve-ref db (:seon.ref/name c) (:seon.ref/depth c))] [(if (:unresolved v) :unresolved :var) v])
    :view (reached db c)
    nil))
