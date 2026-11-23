(ns seon.render.print.select
  "Stage 3 of 4-Stage Printer: Breadth-before-depth token-budgeted Selection Tree with explicit `:elision` nodes."
  (:require [clojure.string :as str]
            [seon.render.print.expand :as pexp]
            [seon.render.print.policy :as ppol]
            [seon.render.print.schema :as pschema]
            [seon.schema :as schema]))

(defn- safe-count [x]
  (cond
    (or (string? x) (map? x) (set? x) (instance? clojure.lang.Counted x)) {:total (count x) :exact? true}
    (coll? x) (let [c (bounded-count 20001 x)] {:total (min c 20000) :exact? (<= c 20000)})
    :else {:total 1 :exact? true}))

(defn- layout-for-depth [policy depth]
  (let [dl (:depth-layouts policy) max-d (long (:max-depth policy 2))]
    (cond (zero? depth) (get dl 0 :expanded-card)
          (< depth max-d) (get dl 1 :compact-row)
          :else (get dl :deep :ref-pill))))

(defn- leaf-value? [x]
  (or (nil? x) (boolean? x) (number? x) (keyword? x) (symbol? x) (uuid? x) (char? x)
      (and (string? x) (<= (count x) 80)) (and (coll? x) (empty? x))))

(defn- est-node-tokens [node]
  (case (:kind node)
    :nil 1
    :scalar (max 1 (quot (count (str (:text node))) 4))
    :string (if (string? (:preview node)) (max 1 (quot (count (:preview node)) 4)) 24)
    :elision 10 :cycle-ref 6 :ref-pill 8 :rendered-sub 32 :lookup-ref 12
    (:datahike/db :datahike/conn) 14
    (long (or (:tokens node) 16))))

(defn- make-elision [opts shown total lim unit]
  (let [next-off (+ (long (:offset opts 0)) (long shown)) l (long lim)]
    {:kind :elision :total (long total) :shown (long shown)
     :remaining (max 0 (- (long total) next-off)) :unit unit :path (vec (:path opts))
     :expand-form (pexp/expand-form-data opts next-off l)
     :expand-hint (pexp/expand-hint-str opts next-off l)}))

(defn- prioritize-map-keys [ix policy m facets offset limit]
  (let [id-ks (when (pschema/valid-ix? ix) (keep #(get-in ix [:schema-identities (:schema %)]) facets))
        pks (distinct (concat id-ks [:db/id :id] (:priority-keys policy) [:name :branch]))]
    (if (<= (count m) 200)
      (let [pset (set pks)]
        (->> (concat (filter #(contains? m %) pks) (->> (keys m) (remove pset) (sort-by str)))
             (drop offset) (take limit) (mapv (fn [k] [k (get m k)]))))
      (let [safe-lk? (or (not (sorted? m)) (empty? m) (keyword? (key (first m))))
            prios (if (and (zero? offset) safe-lk?) (vec (keep #(when-let [e (find m %)] [(key e) (val e)]) pks)) [])
            pset (into #{} (map first) prios)
            rest-es (if (zero? offset)
                      (->> (seq m) (remove #(contains? pset (key %))) (take (max 0 (- limit (count prios)))))
                      (->> (seq m) (drop offset) (take limit)))]
        (into prios (map (fn [e] [(key e) (val e)])) rest-es)))))

(defn- budget-siblings [build-fn items avail-tokens]
  (let [n (count items)
        cheap-flags (mapv (fn [[_ v]] (leaf-value? v)) items)
        heavy-n (count (remove true? cheap-flags))
        cheap-res (reduce (fn [{:keys [nodes rem]} [idx [k v]]]
                            (if (nth cheap-flags idx)
                              (let [nd (build-fn k v 16)]
                                {:nodes (assoc nodes idx nd) :rem (max 0 (- rem (est-node-tokens nd)))})
                              {:nodes nodes :rem rem}))
                          {:nodes (vec (repeat n nil)) :rem (long avail-tokens)}
                          (map-indexed vector items))]
    (loop [idx 0 rem-heavy heavy-n rem-toks (:rem cheap-res) nodes (:nodes cheap-res)]
      (if (>= idx n) nodes
          (if (nth cheap-flags idx)
            (recur (inc idx) rem-heavy rem-toks nodes)
            (let [[k v] (nth items idx)
                  share (if (pos? rem-heavy) (max 12 (quot rem-toks rem-heavy)) 12)
                  nd (build-fn k v share)]
              (recur (inc idx) (dec rem-heavy) (max 0 (- rem-toks (min share (est-node-tokens nd)))) (assoc nodes idx nd))))))))

(defn- select-string [^String s opts]
  (let [{:keys [max-chars limit offset budget depth]} opts
        nc (count s) ls (str/split-lines s) nl (count ls)
        top? (zero? (long (or depth 0)))
        char-cap (if top? (long max-chars) (min (long max-chars) (max 160 (* 4 (long budget)))))
        exp? (or (pos? (long (or offset 0))) (and top? (some? (:string-limit opts))))]
    (if (and (not exp?) (<= nc char-cap) (<= nc (long max-chars)))
      {:kind :string :schema :string :facets [] :total nc :lines nl :offset 0 :limit nc :preview s :more? false :tokens (max 1 (quot nc 4))}
      (let [lines? (and (> nl 1) (not (and exp? (= 1 nl))))
            slim (long (or (:string-limit opts) limit))
            lim (if exp? slim (if lines? (min 40 (max 6 (quot budget 18))) (quot char-cap 2)))
            tn (if exp? 0 (if lines? (min 15 (max 0 (- nl lim))) (min (quot char-cap 4) (max 0 (- nc (+ offset lim))))))
            [hd tl om] (if lines?
                         (let [h (vec (take lim (drop offset ls))) t (when (pos? tn) (vec (take-last tn ls)))]
                           [h (or t []) (max 0 (- nl offset (count h) (count t)))])
                         (let [h (subs s (min offset nc) (min nc (+ offset lim))) t (if (pos? tn) (subs s (- nc tn)) "")]
                           [h t (max 0 (- nc offset (count h) (count t)))]))
            el (when (pos? om)
                 (assoc (make-elision opts (count hd) (if lines? nl nc) lim (if lines? :lines :chars)) :remaining om))]
        (cond-> {:kind :string :schema :string :facets [] :total nc :lines nl :offset offset :limit lim
                 :preview (if lines? {:head hd :tail tl :omitted-lines om} {:head hd :tail tl :omitted-chars om})
                 :more? (pos? om) :remaining om :tokens (min (long budget) 120)}
          el (assoc :elision el :expand-hint (:expand-hint el)))))))

(defn- child-opts [opts depth budget path vis]
  (let [o (assoc opts :depth (inc (long depth)) :offset 0
                 :limit (long (:nested-limit opts 5)) :budget budget :path path)]
    (if (some? vis) (assoc o :visited vis) o)))

(defn- select-lookup-ref [db ix policy [attr id-val] {:keys [depth visited] :as opts}]
  (let [target-sch (pschema/lookup-ref-schema db ix attr) cid [attr id-val]]
    (if (contains? visited cid)
      {:kind :cycle-ref :ref cid :schema target-sch :tokens 6}
      (let [{:keys [indexed? eid pulled]} (pschema/resolve-lookup-ref db attr id-val)
            max-d (long (:max-depth policy 2))
            budget (long (:budget opts 2000))
            rsym (when (and (or (and (pos? depth) (<= depth max-d)) (:render-root? opts)) (>= budget 14) (seq pulled) target-sch)
                 (pschema/schema-renderer-sym db ix target-sch))
            layout (layout-for-depth policy depth)]
        (if rsym
          (let [inv (pschema/invoke-sub-renderer db target-sch rsym {:layout layout :mode (:mode opts :model)} pulled)]
            (if (:ok? inv)
              {:kind :rendered-sub :schema target-sch :facets [target-sch] :layout layout :mode (:mode opts :model)
               :renderer rsym :hiccup (:hiccup inv) :total 2 :more? false :tokens 28}
              {:kind :lookup-ref :schema target-sch :facets [target-sch] :total 2
               :preview {:attr attr :id id-val :exists? (boolean eid) :eid eid :error (:error inv)} :more? false :tokens 14}))
          (cond-> {:kind (if (and (pos? depth) (> depth max-d)) :ref-pill :lookup-ref)
                   :schema target-sch :facets (if target-sch [target-sch] []) :layout layout :total 2 :more? false
                   :preview (cond-> {:attr attr :id id-val}
                              eid (assoc :exists? true :eid eid)
                              (and indexed? (nil? eid)) (assoc :exists? false)
                              (seq pulled) (assoc :summary (into {} (take 6 (sort-by (comp str key) pulled))))) :tokens 12}
            (and eid (not= attr :seon.health/summary))
            (assoc :expand-hint (str "(d/pull (user/db) '[*] [" (pr-str attr) " " (pr-str id-val) "])"))))))))

(declare select-node)

(defn- select-map-node [db ix policy val {:keys [depth visited] :as opts}]
  (let [{:keys [raw-fs ms sch all-fs]} (pschema/fingerprint-map ix val (when (zero? depth) (:schema opts)))
        fs (if (:annotate-multi-facet? policy true) all-fs (if sch [sch] []))
        cid (pschema/entity-cycle-id ix sch val)
        max-d (long (:max-depth policy 2))
        budget (long (:budget opts 2000))
        layout (layout-for-depth policy depth)
        rsym (when (and (or (and (pos? depth) (<= depth max-d)) (:render-root? opts)) (>= budget 14) sch)
               (pschema/schema-renderer-sym db ix sch))]
    (cond
      (and cid (contains? visited cid))
      {:kind :cycle-ref :ref cid :schema sch :tokens 6}

      rsym
      (let [inv (pschema/invoke-sub-renderer db sch rsym {:layout layout :mode (:mode opts :model)} val)]
        (if (:ok? inv)
          {:kind :rendered-sub :schema sch :facets fs :layout layout :mode (:mode opts :model)
           :renderer rsym :hiccup (:hiccup inv) :total (count val) :more? false :tokens 32}
          {:kind :error :schema sch :preview {:class :render-fault :message (:error inv)} :tokens 16}))

      (and (pos? depth) (or (>= depth max-d) (< budget 14)) (> (count val) 0))
      {:kind :ref-pill :schema sch :facets fs :layout layout :total (count val)
       :preview (into {} (take 3 (prioritize-map-keys ix policy val raw-fs 0 3)))
       :elision (make-elision opts 0 (count val) (:nested-limit opts 5) :keys) :more? true :tokens 10}

      :else
      (let [tot (count val) off (long (:offset opts 0))
            lim (long (if (contains? opts :limit) (:limit opts) (:collection-limit policy 20)))
            sliced (prioritize-map-keys ix policy val raw-fs off lim)
            vis' (cond-> visited cid (conj cid))
            hdr-cost (+ 12 (* 4 (count sliced)))
            c-nodes (budget-siblings
                     (fn [k cv share]
                       (select-node db ix policy cv (child-opts opts depth share (conj (vec (:path opts)) k) vis')))
                     sliced (max 16 (- budget hdr-cost)))
            entries (mapv (fn [[k _] nd] [k nd]) sliced c-nodes)
            shown (count entries) more? (> tot (+ off shown))
            el (when more? (make-elision opts shown tot lim :keys))]
        (cond-> {:kind (cond (seq raw-fs) (:kind (first raw-fs)) sch :value :else :map)
                 :schema sch :facets fs :total tot :offset off :limit lim :entries entries
                 :preview (into {} (map (fn [[k nd]] [k (or (:preview nd) (:text nd))]) entries))
                 :key-order (mapv first sliced) :more? more? :remaining (max 0 (- tot (+ off shown)))
                 :tokens (reduce + hdr-cost (map est-node-tokens c-nodes))}
          (and (:annotate-multi-facet? policy true) (seq raw-fs)) (assoc :facet-details raw-fs)
          (map? ms) (assoc :ambiguous (:ambiguous ms))
          el (assoc :elision el :expand-hint (:expand-hint el)))))))

(defn- select-grouped-errors [db ix policy val total opts]
  (let [groups (frequencies (map #(or (:seon.error/kind %) :unknown) val))
        newest-n (long (:errors-newest policy 5))
        newest (->> val (sort-by #(long (or (:seon.error/at %) 0)) >) (take newest-n) vec)
        budget (long (:budget opts 2000))
        depth (long (:depth opts 0))
        share (max 16 (quot (max 32 (- budget 24)) (max 1 (count newest))))
        c-nodes (mapv (fn [idx item]
                        (select-node db ix policy item (child-opts opts depth share (conj (vec (:path opts)) idx) nil)))
                      (range) newest)
        el (make-elision opts (count newest) total (long (:limit opts (:collection-limit policy 20))) :items)]
    {:kind :grouped-errors :element-schema :seon.error/entity :total total :groups groups
     :items c-nodes :preview (mapv #(or (:preview %) %) newest) :more? true
     :remaining (- total (count newest)) :elision el :expand-hint (:expand-hint el)
     :tokens (+ 24 (reduce + 0 (map est-node-tokens c-nodes)))}))

(defn- select-coll-node [db ix policy val {:keys [depth] :as opts}]
  (let [{:keys [total exact?]} (safe-count val)
        kind (cond (pexp/real-vec? val) :vector (set? val) :set :else :seq)
        off (long (:offset opts 0))
        lim (long (if (contains? opts :limit) (:limit opts) (:collection-limit policy 20)))
        raw-slice (cond (zero? lim) []
                        (and (pexp/real-vec? val) (< off total)) (subvec val off (min total (+ off lim)))
                        :else (->> val (drop off) (take lim) vec))
        {:keys [element-schema tabular? columns]} (pschema/detect-elem-info ix (take 5 raw-slice))
        max-d (long (:max-depth policy 2))
        budget (long (:budget opts 2000))]
    (cond
      (and (zero? off) (= :seon.error/entity element-schema) (:errors-by-kind? policy)
           (> total (long (:errors-threshold policy 10))))
      (select-grouped-errors db ix policy val total opts)

      (and (pos? depth) (or (>= depth max-d) (< budget 16)) (> total 0))
      {:kind :ref-pill :schema element-schema :facets [] :layout (layout-for-depth policy depth) :total total
       :preview (vec (take 2 raw-slice)) :elision (make-elision opts 0 total (long (:nested-limit opts 5)) :items)
       :more? true :tokens 10}

      :else
      (let [indexed (vec (map-indexed (fn [i it] [(+ off i) it]) raw-slice))
            hdr-cost (+ 10 (* 2 (count indexed)))
            c-nodes (budget-siblings
                     (fn [idx it share]
                       (select-node db ix policy it
                                    (child-opts opts depth share
                                                (if (= kind :vector) (conj (vec (:path opts)) idx) (vec (:path opts))) nil)))
                     indexed (max 16 (- budget hdr-cost)))
            shown (count c-nodes)
            more? (or (not exact?) (> total (+ off shown)))
            el (when more? (make-elision opts shown total lim :items))
            msch (when (instance? clojure.lang.IMeta val) (:seon/schema (meta val)))]
        (cond-> {:kind kind :schema msch :facets (if msch [msch] []) :total total :exact? exact?
                 :offset off :limit lim :items c-nodes :preview (mapv #(or (:preview %) (:text %)) c-nodes)
                 :more? more? :remaining (max 0 (- total (+ off shown)))
                 :tokens (reduce + hdr-cost (map est-node-tokens c-nodes))}
          element-schema (assoc :element-schema element-schema)
          tabular? (assoc :tabular? true :columns columns)
          el (assoc :elision el :expand-hint (:expand-hint el)))))))

(defn- select-node [db ix policy val opts]
  (if-let [{:keys [kind schema summary]} (pschema/handle-info val)]
    {:kind kind :schema schema :facets [] :total 1 :offset 0 :limit 1 :preview summary
     :more? false :expand-hint "(user/expand! [nil :seon.task/id nil] {:limit 20})" :tokens 16}
    (cond
      (nil? val) {:kind :nil :schema nil :facets [] :total 0 :preview nil :more? false :tokens 1}
      (or (boolean? val) (number? val) (keyword? val) (symbol? val) (uuid? val) (char? val))
      {:kind :scalar :schema nil :facets [] :total 1 :preview val :text (pr-str val) :more? false :tokens 2}
      (string? val) (select-string val opts)
      (instance? datahike.datom.Datom val)
      {:kind :scalar :schema :datahike/datom :facets [] :total 1 :preview [(:e val) (:a val) (:v val) (:tx val)]
       :text (pr-str [(:e val) (:a val) (:v val) (:tx val)]) :more? false :tokens 8}
      (instance? Throwable val)
      {:kind :error :schema :seon.host/throwable :facets [] :total 1
       :preview {:class (.getName (class val)) :message (or (.getMessage val) (str val)) :data (ex-data val)}
       :more? false :tokens 18}
      (and (pexp/real-vec? val) (= 2 (count val)) (pschema/lookup-ref-schema db ix (first val)))
      (select-lookup-ref db ix policy val opts)
      (map? val) (select-map-node db ix policy val opts)
      (or (pexp/real-vec? val) (set? val) (seq? val) (sequential? val)) (select-coll-node db ix policy val opts)
      :else
      {:kind :object :schema nil :facets [] :total 1 :type (.getName (class val))
       :preview (let [s (pr-str val)] (if (> (count s) 240) (str (subs s 0 240) "…") s)) :more? false :tokens 16})))

(defn select-with-index
  "Pure Stage 3 budgeted Selection Tree builder using precompiled index `ix` (no DB handle required)."
  {:malli/schema [:=> [:cat :map :any :map] :map]
   :cost/ticks 10000000
   :cost/bytes 8388608}
  [ix val opts]
  (let [nopts (pexp/norm-opts opts)
        policy (ppol/compute-print-policy (:rules nopts))]
    (select-node nil ix policy val (assoc nopts :visited #{}))))

(defn select-tree
  "Runs Stages 1-3: derives rule print policy, fingerprints sub-schemas with cycle guard, and builds breadth-first Selection Tree."
  {:malli/schema [:function [:=> [:cat [:maybe :seon.host/db] :any] :map] [:=> [:cat [:maybe :seon.host/db] :any :map] :map]]
   :cost/ticks 15000000
   :cost/bytes 8388608}
  ([db val] (select-tree db val {}))
  ([db val opts]
   (let [nopts (pexp/norm-opts opts)
         ix (schema/entity-index db)
         policy (ppol/derive-print-policy db (:rules nopts))]
     (select-node db ix policy val (assoc nopts :visited #{})))))
