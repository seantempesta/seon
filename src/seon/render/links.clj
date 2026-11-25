(ns seon.render.links
  "Shared semantic `.ent-link` Hiccup primitives, DB/UI utilities, and minimal self-contained
   entity enrichment (`enrich-entity-params`: own `:seon.code.form/src` + explicit callers/refs)."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.render.data :as rdata]
            [seon.store :as store]
            [seon.web.router :as router]))

(defn db-schema
  {:malli/schema [:=> [:cat :any] :map] :cost/ticks 50000}
  [db] (store/db-schema db))

(defn live-cid
  {:malli/schema [:=> [:cat :any] :any] :cost/ticks 50000}
  [db] (store/live-cid db))

(defn db-cache-sig
  {:malli/schema [:=> [:cat :any] :any] :cost/ticks 50000}
  [db] (store/db-cache-sig db))

(defn ^:seon.flow/edge safe-as-of
  "Safely returns `(d/as-of db tx)` when `tx` is an integer, falling back to `db`."
  {:malli/schema [:=> [:cat :any :any] :any] :cost/ticks 500000}
  [db tx]
  (if (and db (integer? tx)) (try (d/as-of db tx) (catch Throwable _ db)) db))

(defn real-rule-id?
  "True when `x` is a real `:seon.rule/id` keyword (excludes synthetic `:seed/*` tags)."
  {:malli/schema [:=> [:cat :any] :boolean] :cost/ticks 50000}
  [x]
  (boolean (and (qualified-keyword? x) (str/starts-with? (str (namespace x)) "rule"))))

(defn ref-eid
  "Extracts an integer EID from an EID or Datahike pulled ref map `{:db/id n}`, else nil."
  {:malli/schema [:=> [:cat :any] [:maybe :int]] :cost/ticks 50000}
  [x]
  (cond (integer? x) (long x) (and (map? x) (integer? (:db/id x))) (long (:db/id x)) :else nil))

(defn ent-link
  "Renders a bookmarkable semantic `.ent-link` `<a href>` (optional leading `db` for DB-backed Reitit routes)."
  {:malli/schema [:function
                  [:=> [:cat :any :any] [:vector :any]]
                  [:=> [:cat :any :any [:maybe :string]] [:vector :any]]
                  [:=> [:cat :any :any :any [:maybe :string]] [:vector :any]]
                  [:=> [:cat :any :any :any :any [:maybe :string]] [:vector :any]]]
   :cost/ticks 500000}
  ([ref lbl] (ent-link nil ref lbl "mono"))
  ([ref lbl cls] (ent-link nil ref lbl cls))
  ([x y lbl cls]
   (cond
     (or (nil? x) (instance? datahike.db.interface.ISearch x))
     (if-let [r (rdata/normalize-inspect-ref y)]
       [:a {:href (router/entity-ref->path x r)
            :class (if cls (str "ent-link " cls) "ent-link")
            :data-ref (pr-str r) :data-preview-ref (pr-str r) :interestfor "seon-hovercard"
            :on-click (list (quote seon.web/inspect-entity!) r)}
        [:span (str lbl)]]
       [:span {:class (or cls "")} (str lbl)])
     (and (qualified-keyword? x) (some? y)) (ent-link nil [x y] lbl cls)
     :else [:span {:class (or cls "")} (str lbl)]))
  ([db a v lbl cls]
   (if (and (qualified-keyword? a) (some? v))
     (ent-link db [a v] lbl cls)
     [:span {:class (or cls "")} (str lbl)])))

(defn meta-link
  "Renders a muted right-aligned clickable metadata `.ent-link` when `v` is non-nil."
  {:malli/schema [:function [:=> [:cat :qualified-keyword :any] [:maybe [:vector :any]]]
                  [:=> [:cat :any :qualified-keyword :any] [:maybe [:vector :any]]]]
   :cost/ticks 500000}
  ([a v] (when (some? v) (ent-link nil [a v] (str v) "muted tiny mono ent-meta")))
  ([db a v] (when (some? v) (ent-link db [a v] (str v) "muted tiny mono ent-meta"))))

(defn link-pill
  "Renders a clickable `.ent-link.pill` chip."
  {:malli/schema [:function [:=> [:cat :qualified-keyword :any :any] [:vector :any]]
                  [:=> [:cat :any :qualified-keyword :any :any] [:vector :any]]]
   :cost/ticks 500000}
  ([a v lbl] (ent-link nil [a v] lbl "pill mono"))
  ([db a v lbl] (ent-link db [a v] lbl "pill mono")))

(defn badge
  "Renders a colored status badge."
  {:malli/schema [:=> [:cat :string :any] [:vector :any]] :cost/ticks 50000}
  [cls text] [:span {:class (str "badge " cls)} (str text)])

(defn pill
  "Renders a monospace pill."
  {:malli/schema [:=> [:cat :any] [:vector :any]] :cost/ticks 50000}
  [text] [:span {:class "pill mono"} (str text)])

(defn mut
  "Renders tiny muted monospace text."
  {:malli/schema [:=> [:cat :any] [:vector :any]] :cost/ticks 50000}
  [text] [:span {:class "mono tiny muted"} (str text)])

(defn kicker
  "Renders a section kicker label."
  {:malli/schema [:=> [:cat :any] [:vector :any]] :cost/ticks 50000}
  [text] [:span {:class "kicker"} (str text)])

(defn btn
  "Renders a standard Reagent action button with optional disabled state."
  {:malli/schema [:function [:=> [:cat :string :any :any] [:vector :any]]
                  [:=> [:cat :string :any :any :any] [:vector :any]]]
   :cost/ticks 50000}
  ([cls oc lbl] (btn cls oc lbl false))
  ([cls oc lbl dis?]
   [:button (cond-> {:type "button" :class cls :on-click oc} dis? (assoc :disabled true)) lbl]))

(defn card
  "Renders a standard titled `.card.stack-sm` section with optional pill row and body nodes."
  {:malli/schema [:=> [:cat [:maybe :string] :any [:maybe [:sequential :any]] [:* :any]] [:vector :any]]
   :cost/ticks 100000}
  [id title pills & body]
  (into [:section (cond-> {:class "card stack-sm"} id (assoc :id id))
         [:div {:class "card-head"} [:h2 title]
          (when (seq pills) (into [:div {:class "pill-row"}] (remove nil? pills)))]]
        (remove nil? body)))

(defn feedback-banner
  "Renders a dismissible branch/action feedback notice when `branch-feedback` is non-nil."
  {:malli/schema [:=> [:cat [:maybe :map]] [:maybe [:vector :any]]] :cost/ticks 50000}
  [branch-feedback]
  (when (map? branch-feedback)
    [:div {:class (str "notice row-between " (if (:ok? branch-feedback) "green" "red"))}
     [:span (str (:text branch-feedback))]
     (btn "link-muted tiny" '(seon.web/dismiss-feedback!) "×")]))

(defn kv-dl
  "Renders a `.kv-grid` definition list from `[label val]` pairs, omitting nil or blank entries."
  {:malli/schema [:=> [:cat [:sequential [:maybe [:tuple :any :any]]]] [:maybe [:vector :any]]]
   :cost/ticks 200000}
  [pairs]
  (let [rows (mapcat (fn [pair]
                       (when (and (sequential? pair) (>= (count pair) 2))
                         (let [[k v] pair]
                           (when (and (some? v) (or (vector? v) (not (str/blank? (str v)))))
                             [[:dt (str k)] [:dd (if (vector? v) v (str v))]]))))
                     pairs)]
    (when (seq rows) (into [:dl {:class "kv-grid"}] rows))))

(defn pill-list
  "Renders a labeled `.pill-row` of up to `limit` items."
  {:malli/schema [:=> [:cat [:maybe :string] :any :any :int] [:maybe [:vector :any]]] :cost/ticks 300000}
  [label xs attr limit]
  (when-let [items (when (and (coll? xs) (not (map? xs))) (seq xs))]
    (into [:div {:class "pill-row"} (when label [:span {:class "muted tiny"} label])]
          (keep (fn [it]
                  (cond
                    (= attr :ref-pair) (when (and (vector? it) (= 2 (count it))) (ent-link (first it) (second it) "pill mono"))
                    (= attr :ref) (when-let [r (rdata/normalize-inspect-ref it)] (ent-link r (rdata/entity-short-label r) "pill mono"))
                    (qualified-keyword? attr)
                    (cond (and (map? it) (integer? (:db/id it))) (ent-link [:db/id (:db/id it)] (str "#" (:db/id it)) "pill mono")
                          (some? it) (link-pill attr it (str it)) :else nil)
                    :else (pill (str it))))
                (take (max 0 (long limit)) items)))))

(defn ^:seon.flow/edge ns-require-aliases
  "Parses a namespace's `(ns ... (:require ...))` source into sorted compact require specs."
  {:malli/schema [:=> [:cat [:maybe :string]] [:vector :string]] :cost/ticks 50000}
  [ns-src]
  (if (or (not (string? ns-src)) (str/blank? ns-src))
    []
    (try
      (let [form (binding [*read-eval* false] (read-string ns-src))
            req-clauses (when (and (seq? form) (= 'ns (first form)))
                          (filter #(and (seq? %) (= :require (first %))) (rest form)))
            specs (mapcat rest req-clauses)]
        (->> specs
             (keep (fn [sp]
                     (cond
                       (vector? sp)
                       (let [lib (first sp) tail (rest sp)
                             opts (if (even? (count tail)) (apply hash-map tail) {})
                             al (:as opts) rf (:refer opts)]
                         (cond (and lib al) (str "[" lib " :as " al "]")
                               (and lib rf) (str "[" lib " :refer " (pr-str rf) "]")
                               lib (str lib)))
                       (symbol? sp) (str sp))))
             distinct sort vec))
      (catch Throwable _ []))))

(defn- resolve-attr-values [db refs target-attr limit]
  (when (and db (coll? refs) (seq refs) ((db-schema db) target-attr))
    (not-empty
     (vec (distinct
           (keep (fn [x]
                   (cond
                     (and (= target-attr :seon.schema/key) (qualified-keyword? x)) x
                     (and (= target-attr :seon.code.var/name) (qualified-symbol? x)) x
                     :else (when-let [e (ref-eid x)] (get (d/pull db [target-attr] e) target-attr))))
                 (take limit refs)))))))

(defn- schema-field-tags [db form]
  (when (and db (vector? form) (= :map (first form)))
    (let [dh (db-schema db)
          entries (if (map? (second form)) (drop 2 form) (rest form))]
      (not-empty
       (into {}
             (keep (fn [entry]
                     (when (and (vector? entry) (keyword? (first entry)))
                       (let [f (first entry) fdh (get dh f)]
                         (cond (:db/unique fdh) [f "[unique]"]
                               (:db/index fdh) [f "[indexed]"]
                               :else nil)))))
             entries)))))

(defn- enrich-ns-subtree [db dh ent]
  (when-let [nsym (and (dh :seon.code.ns/name) (:seon.code.ns/name ent))]
    (let [ne (:e (first (d/datoms db :avet :seon.code.ns/name nsym)))
          vc (if (dh :seon.code.var/ns) (count (d/datoms db :avet :seon.code.var/ns nsym)) 0)
          kids (when (and ne (dh :seon.code.ns/parent))
                 (->> (d/datoms db :avet :seon.code.ns/parent ne)
                      (keep (fn [dt]
                              (let [ce (:e dt)
                                    p (d/pull db '[:seon.code.ns/name :seon.code.ns/doc] ce)
                                    cn (:seon.code.ns/name p)]
                                (when cn
                                  (let [cvc (if (dh :seon.code.var/ns) (count (d/datoms db :avet :seon.code.var/ns cn)) 0)
                                        ot (if (dh :seon.task/scope)
                                             (count (filter (fn [tdt]
                                                              (let [tp (d/pull db '[:seon.task/done? :seon.task/template?] (:e tdt))]
                                                                (and (not (:seon.task/done? tp)) (not (:seon.task/template? tp)))))
                                                            (d/datoms db :avet :seon.task/scope cn)))
                                             0)
                                        skw (keyword "ns" (name cn))
                                        spec (when (and (dh :seon.agent/id) (first (d/datoms db :avet :seon.agent/id skw))) skw)]
                                    (cond-> {:seon.code.ns/name cn :var-count cvc :open-tasks ot}
                                      (:seon.code.ns/doc p) (assoc :seon.code.ns/doc (:seon.code.ns/doc p))
                                      spec (assoc :specialist spec)))))))
                      (sort-by #(str (:seon.code.ns/name %)))
                      vec))]
      (cond-> {:seon.ui/ns-var-count vc}
        (seq kids) (assoc :seon.ui/ns-children kids)))))

(defn enrich-entity-params
  "Tiny self-contained entity enrichment: attaches own `:seon.code.form/src` on a var from its
   `:seon.code.var/form` ref, own schema/rule direct ref labels, matching `:seon.fix/entity` for
   errors, and `:seon.ui/callers` ONLY when explicitly requested via `:seon.ui/show-callers?` / `:show-callers?` (or focal inspect)."
  {:malli/schema [:function
                  [:=> [:cat [:maybe :seon.host/db] [:maybe :map]] :map]
                  [:=> [:cat [:maybe :seon.host/db] [:maybe :map] :any] :map]]
   :cost/ticks 5000000}
  ([db ent] (enrich-entity-params db ent false))
  ([db ent focal-or-opts]
   (if-not (and db (map? ent))
     {}
     (let [opts (if (map? focal-or-opts) focal-or-opts {:focal? (boolean focal-or-opts)})
           want-callers? (boolean (or (:seon.ui/show-callers? opts) (:show-callers? opts) (:focal? opts)))
           dh (db-schema db)
           src (when-let [fk (and (dh :seon.code.form/key) (:seon.code.var/form ent))]
                 (:seon.code.form/src (d/pull db '[:seon.code.form/src] [:seon.code.form/key fk])))
           callers (when (and want-callers? (dh :seon.code.var/refs) (:seon.code.var/name ent))
                     (->> (take 12 (d/datoms db :avet :seon.code.var/refs (:seon.code.var/name ent)))
                          (keep #(:seon.code.var/name (d/pull db '[:seon.code.var/name] (:e %))))
                          distinct sort vec not-empty))
           sks (when-let [sk (:seon.schema/key ent)]
                 (let [sdh (get dh sk)]
                   {:keys (resolve-attr-values db (:seon.schema/keys ent) :seon.schema/key 16)
                    :opt (resolve-attr-values db (:seon.schema/optional-keys ent) :seon.schema/key 16)
                    :refs (resolve-attr-values db (:seon.schema/ref-targets ent) :seon.schema/key 12)
                    :field-tags (schema-field-tags db (:seon.schema/form ent))
                    :dh-meta (when sdh {:value-type (:db/valueType sdh) :cardinality (:db/cardinality sdh)})}))
           r-schs (when (:seon.rule/id ent) (resolve-attr-values db (:seon.rule/schema ent) :seon.schema/key 12))
           r-fns (when (:seon.rule/id ent) (resolve-attr-values db (:seon.rule/fn ent) :seon.code.var/name 12))
           err-fix (when-let [k (and (dh :seon.fix/error-class) (:seon.error/kind ent))]
                     (when-let [fe (:e (first (d/datoms db :avet :seon.fix/error-class k)))]
                       (dissoc (d/pull db '[:seon.fix/id :seon.fix/error-class :seon.fix/symptom :seon.fix/root-cause
                                            :seon.fix/change-summary :seon.fix/regression-test :seon.fix/before-after] fe)
                               :db/id)))]
       (let [ns-sub (enrich-ns-subtree db dh ent)]
       (cond-> {}
         (and (string? src) (not (str/blank? src))) (assoc :seon.code.form/src src)
         callers (assoc :seon.ui/show-callers? true :seon.ui/callers callers)
         (:keys sks) (assoc :seon.ui/schema-keys (:keys sks))
         (:opt sks) (assoc :seon.ui/schema-optional-keys (:opt sks))
         (:refs sks) (assoc :seon.ui/schema-ref-targets (:refs sks))
         (:field-tags sks) (assoc :seon.ui/schema-field-tags (:field-tags sks))
         (:dh-meta sks) (assoc :seon.ui/schema-dh-meta (:dh-meta sks))
         r-schs (assoc :seon.ui/rule-schemas r-schs)
         r-fns (assoc :seon.ui/rule-fns r-fns)
         (and (map? err-fix) (:seon.fix/id err-fix)) (assoc :seon.fix/entity err-fix)
         (seq ns-sub) (merge ns-sub)))))))
