(ns seon.docs
  "The wiki as data in the store (task seon.docs/docs-are-data-in-the-store,
  docs/references.md#the-wiki-in-the-store): a section entity per heading of docs/*.md, read
  by the one parse (seon.ref/parse). A section's references are what its own text cites,
  resolved on the db (a link to a section, a name to a var), so its backlinks are who cites
  it. Loading writes only what differs from what main holds, as task files do."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [seon.ref :as ref] [seon.task :as task] [seon.db :as pdb]
            [datahike.api :as d] [clojure.string :as str] [clojure.java.io :as io]))

;; ---- one page, parsed into sections ----------------------------------------------------

(defn- own-text
  "Lines from (exclusive) to (exclusive) of lines, joined and trimmed; nil when blank."
  {:malli/schema [:=> [:cat [:vector :string] :int :int] [:maybe :string]]}
  [lines from to]
  (let [s (str/trim (str/join "\n" (subvec lines (min from (count lines)) (min (max from (dec to)) (count lines)))))]
    (when-not (str/blank? s) s)))

(defn page-sections
  "The sections of the page at path, read once: each its address, heading, lead, own text
  (the lines after its heading up to the next heading of any level), its parent's address
  and the citations its own text makes, each once at its strongest depth (seon.ref/cited)."
  {:malli/schema [:=> [:cat :string :string] [:vector :seon.section/parsed]]}
  [path text]
  (let [{:seon.ref/keys [sections citations]} (ref/parse path text)
        lines (str/split-lines text)
        starts (mapv :seon.ref/line sections)
        n (count sections)
        ;; the index of the last section whose heading is at or before line l, or nil
        owner (fn [l] (let [i (dec (count (take-while #(<= % l) starts)))] (when-not (neg? i) i)))
        by-section (group-by #(owner (:seon.ref/line %)) citations)]
    (vec (for [i (range n)
               :let [{:seon.ref/keys [address heading lead parent line]} (nth sections i)
                     next-line (if (< (inc i) n) (nth starts (inc i)) (inc (count lines)))
                     body (own-text lines line next-line)]]
           (cond-> {:seon.section/address address :seon.section/heading heading
                    :seon.ref/citations (ref/cited (vec (get by-section i [])))}
             lead (assoc :seon.section/lead lead)
             body (assoc :seon.section/text body)
             parent (assoc :seon.ref/parent parent))))))

;; ---- resolving a section's citations ----------------------------------------------------

(defn- under?
  "Is address a section of a page under dir?"
  {:malli/schema [:=> [:cat :string :string] :boolean]}
  [dir address] (str/starts-with? address (str dir "/")))

(defn- citation-target
  "What citation c of a section reaches: [:section address] (a section this load holds, or one
  stored outside dir), [:var entity-id], [:missing address] for a path#anchor that is no
  section, or nil (a schema key, a file, or a name that names nothing: not stored). stored:
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
  {:malli/schema [:=> [:cat [:map-of :string :int] :seon.section/parsed [:vector :any]] :map]}
  [stored s targets]
  (let [ref (fn [a] (get stored a a))]
    (cond-> (select-keys s [:seon.section/heading :seon.section/lead :seon.section/text])
      (:seon.ref/parent s) (assoc :seon.section/parent (ref (:seon.ref/parent s)))
      (seq targets) (assoc :seon.section/references (into #{} (map (fn [[k x]] (if (= :var k) x (ref x)))) targets)))))

(def ^:private section-attrs
  "A stored section's stated attributes (seon.task/entity-tx makes them exactly what its text says)."
  [:seon.section/heading :seon.section/lead :seon.section/text :seon.section/parent :seon.section/references])

;; ---- the load ---------------------------------------------------------------------------

(defn load-tx
  "The transaction making db hold exactly the sections of pages (path -> text), the wiki
  under dir: every section of those pages, each stating what its own text says now, and no
  section under dir that no page holds. A section that already states it writes nothing, so
  an unchanged wiki answers []. Or the :seon.docs/refused error naming the first link (or
  cited path#anchor) that reaches no section."
  {:malli/schema [:=> [:cat :seon.host/db :string [:map-of :string :string]]
                  [:or [:vector :any] :seon.docs/refused]]}
  [db dir pages]
  (let [by-page (into [] (map (fn [[p t]] [p (page-sections p t)])) (sort pages))
        parsed (into [] (mapcat second) by-page)
        loaded (into {} (map (juxt :seon.section/address identity)) parsed)
        firsts (into {} (for [[p ss] by-page :when (seq ss)] [p (:seon.section/address (first ss))]))
        stored (into {} (map (juxt :v :e)) (d/datoms db :avet :seon.section/address))
        targets (into {} (for [s parsed]
                           [(:seon.section/address s)
                            (into [] (keep #(citation-target db dir stored loaded firsts %)) (:seon.ref/citations s))]))
        missing (first (for [s parsed [k a] (targets (:seon.section/address s)) :when (= :missing k)]
                         [(:seon.section/address s) a]))]
    (if-let [[from a] missing]
      {:seon.error/type :seon.docs/refused :seon.error/operation 'seon.docs/load-tx
       :seon.error/message (str from " links to " a ", which is no section")
       :seon.ref/address a :seon.section/address from}
      (vec (concat
             (mapcat (fn [s]
                       (let [a (:seon.section/address s) want (wanted stored s (targets a))]
                         (if-let [e (stored a)]
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
  unchanged wiki answers [], a link to no section the :seon.docs/refused error). Root's boot
  and my.root/load-docs! both write it."
  {:malli/schema [:=> [:cat :seon.host/db :string :string] [:or [:vector :any] :seon.docs/refused]]}
  [db checkout dir] (load-tx db dir (pages checkout dir)))
