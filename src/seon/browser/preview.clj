(ns seon.browser.preview
  "Zero-DB-bloat derived auto-preview engine for `:seon.ui/hiccup` renderers:
   derives sample inputs strictly from (1) live Datahike DB entities, (2) Malli `:examples`,
   or (3) deterministic `seed=42` Malli generation; evaluates and renders multi-width frames
   into a bounded in-memory LRU cache (`preview-lru`, 0 bytes written to LMDB)."
  (:require [clojure.core.cache :as cache]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [datahike.api :as d]
            [malli.core :as m]
            [malli.generator :as mg]
            [seon.browser :as browser]
            [seon.ctx.touches :as ctouches]
            [seon.model.wire :as mwire]
            [seon.render :as render]
            [seon.render.fit :as fit]
            [seon.schema :as schema]
            [seon.store :as store]
            [seon.browser.measure :as measure]
            [seon.render.sanitize :as sanitize]))

(defonce ^:private ^:seon.flow/edge preview-lru (store/lru-cache 64))

(defn ^:seon.flow/edge clear-preview-cache!
  "Clears the bounded in-memory derived preview LRU cache (0 DB writes)."
  {:malli/schema [:=> [:cat] :boolean]
   :cost/ticks 50000}
  []
  (reset! preview-lru (cache/lru-cache-factory {} :threshold 64))
  true)

(defn- ^:seon.flow/edge ensure-lru! []
  (when-not (satisfies? cache/CacheProtocol @preview-lru)
    (reset! preview-lru (cache/lru-cache-factory {} :threshold 64))))

(defn- ^:seon.flow/edge lru-peek [k]
  (ensure-lru!)
  (let [raw (get @preview-lru k ::miss)]
    (if (identical? ::miss raw) ::miss
        (do (swap! preview-lru cache/hit k)
            (if (and (map? raw) (contains? raw :seon.store/lru-val)) (:seon.store/lru-val raw) raw)))))

(defn- ^:seon.flow/edge lru-store! [k v]
  (ensure-lru!)
  (swap! preview-lru cache/miss k v)
  v)

(defn ^:seon.flow/edge cached-preview-frames
  "Looks up cached ephemeral frames in `preview-lru` (0 DB reads/writes)."
  {:malli/schema [:=> [:cat :qualified-symbol :any :any [:sequential :int] :any] [:maybe [:vector :map]]] :cost/ticks 100000}
  [var-sym form-hash input-hash widths theme]
  (let [hit (lru-peek [var-sym form-hash input-hash (vec widths) theme])]
    (when-not (identical? ::miss hit) hit)))

(defn ^:seon.flow/edge cache-preview-frames!
  "Stores ephemeral rendered frames in bounded `preview-lru` (0 DB writes)."
  {:malli/schema [:=> [:cat :qualified-symbol :any :any [:sequential :int] :any [:vector :map]] [:vector :map]] :cost/ticks 100000}
  [var-sym form-hash input-hash widths theme frames]
  (lru-store! [var-sym form-hash input-hash (vec widths) theme] (vec frames)))

(defn- fn-schema-arities [sch]
  (cond (and (vector? sch) (= :=> (first sch))) [sch]
        (and (vector? sch) (= :function (first sch))) (filterv #(and (vector? %) (= :=> (first %))) (rest sch))
        :else []))

(defn- arity-parts [ar]
  (when (and (vector? ar) (= :=> (first ar)))
    (if (map? (second ar)) {:props (second ar) :in (nth ar 2 nil) :out (nth ar 3 nil)} {:props {} :in (nth ar 1 nil) :out (nth ar 2 nil)})))

(defn- mentions-hiccup? [form]
  (boolean
   (when (some? form)
     (some #(= :seon.ui/hiccup %) (tree-seq coll? seq form)))))

(defn hiccup-renderer-var?
  "Returns true when `var-ent` is a public pure `:seon.ui/hiccup` renderer."
  {:malli/schema [:=> {:examples [[[{:seon.code.var/name (quote demo/render-card) :seon.code.var/output [:seon.ui/hiccup]}] true]
                                  [[{:seon.code.var/name (quote demo/helper) :seon.code.var/schema [:=> [:cat :int] :int]}] false]
                                  [[{:seon.code.var/name (quote demo/priv) :seon.code.var/private? true :seon.code.var/output [:seon.ui/hiccup]}] false]]}
                  [:cat :any] :boolean]
   :cost/ticks 500000 :cost/bytes 131072}
  [var-ent]
  (boolean
   (when (and (map? var-ent) (not (:seon.code.var/private? var-ent)) (not (:seon.code.var/test? var-ent))
              (not (:seon.code.var/macro? var-ent)) (not (:seon.code.var/flow-edge? var-ent)) (empty? (:seon.code.var/ops var-ent)))
     (let [outs (:seon.code.var/output var-ent)
           sch (or (:seon.code.var/schema var-ent) (:malli/schema var-ent))]
       (or (and (coll? outs) (some #(or (= :seon.ui/hiccup %) (and (map? %) (= :seon.ui/hiccup (:seon.schema/key %)))) outs))
           (some #(mentions-hiccup? (:out (arity-parts %))) (fn-schema-arities sch)))))))

(defn- cat-arg-schemas [cat-form]
  (when (and (vector? cat-form) (= :cat (first cat-form)))
    (if (map? (second cat-form))
      (vec (drop 2 cat-form))
      (vec (rest cat-form)))))

(defn- map-schema-entries [form]
  (when (and (vector? form) (= :map (first form)))
    (vec (keep #(when (and (vector? %) (>= (count %) 2))
                  (let [hp (and (>= (count %) 3) (map? (second %)))]
                    {:key (first %) :optional? (boolean (when hp (:optional (second %)))) :schema (nth % (if hp 2 1))}))
               (if (map? (second form)) (drop 2 form) (rest form))))))

(defn- valid-db? [db]
  (and db (instance? datahike.db.interface.ISearch db) (map? (:schema db))))

(defn- set-schema-form? [f]
  (and (vector? f)
       (or (= :set (first f))
           (and (= :and (first f)) (some set-schema-form? (rest f))))))

(defn- normalize-entity-for-malli [db ent forms]
  (if-not (map? ent) ent
    (let [dh (store/db-schema db)
          uw #(if (and (map? %) (= 1 (count %)) (integer? (:db/id %))) (:db/id %) %)]
      (into {} (map (fn [[k v]]
                      (let [sp (get dh k) many? (or (= :db.cardinality/many (:db/cardinality sp)) (set-schema-form? (get forms k)))
                            ref? (= :db.type/ref (:db/valueType sp))]
                        [k (cond (and many? (sequential? v)) (into #{} (if ref? (map uw) identity) v)
                                 (and many? (set? v) ref?) (into #{} (map uw) v)
                                 (and many? (some? v) (not (set? v))) #{(if ref? (uw v) v)}
                                 ref? (uw v) :else v)]))) ent))))

(defn- resolve-var-input-keys [db var-ent]
  (let [raw (:seon.code.var/input var-ent)]
    (if-not (coll? raw) []
      (->> raw
           (keep #(cond (qualified-keyword? %) %
                        (and (map? %) (qualified-keyword? (:seon.schema/key %))) (:seon.schema/key %)
                        (and (valid-db? db) (map? %) (integer? (:db/id %))) (:seon.schema/key (d/pull db (quote [:seon.schema/key]) (:db/id %)))
                        (and (valid-db? db) (integer? %)) (:seon.schema/key (d/pull db (quote [:seon.schema/key]) %))))
           distinct vec))))

(defn- pull-live-entity-for-schema [db reg-info sk]
  (when (and (valid-db? db) (qualified-keyword? sk))
    (let [dh (store/db-schema db) ix (:entity-index reg-info) forms (:forms reg-info) reg (:registry reg-info)
          id-a (or (get-in ix [:schema-identities sk]) (first (filter #(contains? dh %) (get-in ix [:required-keys sk]))) (when (contains? dh sk) sk))
          valid? #(or (not (contains? forms sk)) (try (boolean (m/validate sk % {:registry reg})) (catch Throwable _ false)))]
      (when (and id-a (contains? dh id-a))
        (some #(let [norm (normalize-entity-for-malli db (d/pull db (quote [*]) (:e %)) forms)]
                 (when (and (map? norm) (seq (dissoc norm :db/id)) (valid? norm)) norm))
              (take 8 (d/datoms db :aevt id-a)))))))

(defn- sample-from-db [db reg-info var-ent cat-form cat-args]
  (when (valid-db? db)
    (let [reg (:registry reg-info) in-ks (resolve-var-input-keys db var-ent)
          ok? #(or (nil? cat-form) (try (boolean (m/validate cat-form % {:registry reg})) (catch Throwable _ false)))
          pull-p (fn [kf xs]
                   (let [ps (mapv #(let [[k sk] (kf %)] [k (pull-live-entity-for-schema db reg-info sk)]) xs)]
                     (when (and (seq ps) (every? (comp some? second) ps))
                       (let [a [(into {} ps)]] (when (ok? a) a)))))]
      (cond
        (and (= 1 (count cat-args)) (vector? (first cat-args)) (= :map (first (first cat-args))))
        (let [req (filterv (comp not :optional?) (map-schema-entries (first cat-args)))]
          (if (seq req)
            (pull-p #(vector (:key %) (cond (qualified-keyword? (:schema %)) (:schema %) (qualified-keyword? (:key %)) (:key %))) req)
            (pull-p #(vector % %) in-ks)))
        (and (= 1 (count cat-args)) (qualified-keyword? (first cat-args)))
        (when-let [e (pull-live-entity-for-schema db reg-info (first cat-args))] (when (ok? [e]) [e]))
        (and (nil? cat-form) (seq in-ks)) (pull-p #(vector % %) in-ks)))))

(defn- sample-from-examples [arities sch]
  (let [tp (when (and (vector? sch) (map? (second sch))) (second sch))]
    (some #(when (and (coll? %) (not (map? %)))
             (some (fn [ex] (when (and (vector? ex) (seq ex) (vector? (first ex))) (first ex))) %))
          (keep :examples (cons tp (map (comp :props arity-parts) arities))))))

(defn- sample-from-generator [reg-info ar cat-form]
  (when (and ar cat-form (schema/generatable-function-schema? ar))
    (try
      (let [reg (:registry reg-info) g (mg/generate (m/schema cat-form {:registry reg}) {:registry reg :seed 42 :size 4})]
        (when (sequential? g) (vec g)))
      (catch Throwable _ nil))))

(defn derive-renderer-sample-input
  "Synthesizes sample input in strict order: `:db`, `:example`, `:generated` (`seed=42`)."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :any] [:maybe :map]] :cost/ticks 25000000}
  [db var-ent]
  (let [ve (cond (map? var-ent) var-ent
                 (and (qualified-symbol? var-ent) (valid-db? db) (contains? (store/db-schema db) :seon.code.var/name))
                 (d/pull db (quote [* {:seon.code.var/input [:seon.schema/key]} {:seon.code.var/output [:seon.schema/key]}]) [:seon.code.var/name var-ent]))]
    (when (map? ve)
      (let [sch (or (:seon.code.var/schema ve) (:malli/schema ve))
            ars (fn-schema-arities sch)
            ar (or (some #(when (= 1 (count (cat-arg-schemas (:in (arity-parts %))))) %) ars) (first ars))
            cf (when ar (:in (arity-parts ar)))
            ri (schema/registry-for-db (when (valid-db? db) db))
            mk #(let [av (vec %2)] {:source %1 :args av :input-hash (store/sha256-hex (pr-str av) 16)})]
        (or (when-let [a (sample-from-db db ri ve cf (cat-arg-schemas cf))] (mk :db a))
            (when-let [a (sample-from-examples ars sch)] (mk :example a))
            (when-let [a (sample-from-generator ri ar cf)] (mk :generated a)))))))

(defn ^:seon.flow/edge eval-renderer-preview
  "Resolves `var-sym`, derives sample input, evaluates `:seon.ui/hiccup` + HTML, and caches in `preview-lru` (0 DB writes)."
  {:malli/schema [:function [:=> [:cat [:maybe :seon.host/db] :qualified-symbol] :map] [:=> [:cat [:maybe :seon.host/db] :qualified-symbol [:maybe :map]] :map]]
   :cost/ticks 35000000}
  ([db var-sym] (eval-renderer-preview db var-sym nil))
  ([db var-sym opts]
   (let [o (if (map? opts) opts {}) v (fit/resolve-renderer-var var-sym) dh (when (valid-db? db) (store/db-schema db))
         ve (or (:var-ent o)
                (when (and dh (contains? dh :seon.code.var/name))
                  (let [p (d/pull db (quote [* {:seon.code.var/input [:seon.schema/key]} {:seon.code.var/output [:seon.schema/key]}]) [:seon.code.var/name var-sym])]
                    (when (:seon.code.var/name p) p)))
                (when v {:seon.code.var/name var-sym :malli/schema (:malli/schema (meta v))}))
         fh (or (when (and dh (contains? dh :seon.code.form/hash))
                  (:seon.code.form/hash (d/pull db (quote [:seon.code.form/hash]) [:seon.code.form/key (or (:seon.code.var/form ve) (str var-sym))])))
                (str (hash (when v (:malli/schema (meta v))))))
         mk #(hash-map :source :explicit :args % :input-hash (store/sha256-hex (pr-str %) 16))
         smp (cond (contains? o :args) (mk (vec (:args o))) (contains? o :input) (mk [(:input o)]) :else (derive-renderer-sample-input db ve))]
     (cond
       (nil? v) {:ok? false :var var-sym :error :var-not-resolved :message (str "Could not resolve " var-sym)}
       (nil? smp) {:ok? false :var var-sym :error :no-sample-input :message (str "No derivable sample input for " var-sym)}
       :else
       (let [ck [var-sym fh (:input-hash smp) :eval nil]
             base {:var var-sym :form-hash fh :source (:source smp) :args (:args smp) :input-hash (:input-hash smp)}
             hit (when-not (:no-cache? o) (lru-peek ck))]
         (if (and (not (:no-cache? o)) (not (identical? ::miss hit))) (assoc hit :cached? true)
           (let [r (try
                     (let [res (apply @v (:args smp)) h (cond (and (map? res) (contains? res :seon.ui/hiccup)) (:seon.ui/hiccup res) (vector? res) res)]
                       (if (and (vector? h) (seq h) (keyword? (first h)))
                         (assoc base :ok? true :hiccup h :html (str (render/hiccup->html h)))
                         (assoc base :ok? false :error (if (nil? h) :empty-hiccup :invalid-hiccup) :message (str var-sym " did not return Hiccup"))))
                     (catch Throwable ex (assoc base :ok? false :error :eval-failed :message (or (.getMessage ex) (str ex)))))]
             (when-not (:no-cache? o) (lru-store! ck r)) (assoc r :cached? false))))))))

(defn normalize-ephemeral-frame
  "Normalizes a raw WKWebView frame map into a compact ephemeral preview map with `:visual-defects` & `:visual-ok?`."
  {:malli/schema [:=> {:examples [[['demo/card :db {:seon.media/mime "image/jpeg" :seon.media/base64 "AA==" :seon.media/width 360 :seon.media/height 120 :seon.media/label "card · 360px" :seon.media/dom {:overflow-x? false}}]
                                   {:mime "image/jpeg" :base64 "AA==" :label "card · 360px" :width 360 :height 120 :dom {:overflow-x? false} :visual-defects [] :visual-ok? true :var 'demo/card :source :db}]]}
                  [:cat :qualified-symbol [:maybe :keyword] :map] :map]
   :cost/ticks 500000 :cost/bytes 131072}
  [var-sym source f]
  (let [px #(if (number? %) (max 0 (long %)) 0)
        ns #(when (and (string? %) (not (str/blank? %))) (str/trim %))
        w (px (or (:seon.media/width f) (:width f)))
        h (px (or (:seon.media/height f) (:height f)))
        dom (let [d (or (:seon.media/dom f) (:dom f))] (if (map? d) d {}))
        defs (vec (keep ns (let [v (or (:visual-defects dom) (:visual-defects f))] (when (sequential? v) v))))
        ov? (boolean (or (:overflow-x? dom) (:overflowX dom)))]
    {:mime (or (ns (or (:seon.media/mime f) (:mime f))) "image/jpeg")
     :base64 (str (or (:seon.media/base64 f) (:base64 f) ""))
     :label (or (ns (or (:seon.media/label f) (:label f))) (str (name var-sym) " · " w "px"))
     :width w :height h :dom dom :visual-defects defs :visual-ok? (and (not ov?) (empty? defs))
     :var var-sym :source source}))

(defn- extract-eval-edited-syms [ev]
  (let [parse-s #(when (and (string? %) (str/includes? % "/") (< (count %) 128))
                   (let [[n nm] (str/split % #"/" 2)] (when (and (seq n) (seq nm) (not= n "result")) (symbol n nm))))
        cs (str (or (:seon.agent.eval/code ev) (:code ev) ""))
        vs (str (or (:seon.agent.eval/value ev) (:value ev) ""))]
    (filter qualified-symbol?
            (concat (when (coll? (:seon.agent.eval/touched-vars ev)) (:seon.agent.eval/touched-vars ev))
                    (when (coll? (:touched-vars ev)) (:touched-vars ev))
                    (when (re-find #"(?:edit-fn!|add-fn!|set-meta!|ui-patch!|\(defn\s)" cs)
                      (keep #(parse-s (or (nth % 1) (nth % 2))) (re-seq #"(?:\u0027([a-zA-Z][\w.-]*/[\w.*?!+-]+)|\(quote\s+([a-zA-Z][\w.-]*/[\w.*?!+-]+)\))" cs)))
                    (when (and (not (str/blank? vs)) (or (str/includes? vs ":var ") (str/includes? vs ":target ")))
                      (keep #(parse-s (second %)) (re-seq #"(?::var|:target)\s+([a-zA-Z][\w.-]*/[\w.*?!+-]+)" vs)))))))

(defn recent-edited-renderer-vars
  "Inspects latest-turn `evals` and `branch-db` writes, returning touched `:seon.ui/hiccup` renderer vars."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] [:maybe [:sequential :map]]] [:vector :qualified-symbol]] :cost/ticks 20000000}
  [main-db branch-db evals]
  (let [bdb (or branch-db main-db) mdb (or main-db bdb)]
    (if-not (valid-db? bdb) []
      (let [evs (if (sequential? evals) (filterv map? evals) [])
            to #(long (or (:seon.agent.eval/turn %) (:turn %) 0))
            mt (reduce max 0 (map to evs))
            ptx (reduce max 0 (map #(or (when (integer? (:seon.agent.eval/tx %)) (long (:seon.agent.eval/tx %)))
                                        (when (integer? (:tx %)) (long (:tx %)))
                                        (when (integer? (:db/id %)) (some-> (first (d/datoms bdb :eavt (:db/id %) :seon.agent.eval/turn)) :tx long)) 0)
                                   (filter #(< (to %) mt) evs)))
            bdh (store/db-schema bdb) mdh (store/db-schema mdb)
            ws (when (and (valid-db? mdb) (not (identical? mdb bdb))) (ctouches/branch-write-eids mdb bdb nil))
            fw (when (and (map? ws) (contains? bdh :seon.code.var/name))
                 (keep (fn [[e {:keys [max-tx]}]] (when (or (empty? evs) (> (long (or max-tx 0)) ptx)) (:seon.code.var/name (d/pull bdb (quote [:seon.code.var/name]) e)))) ws))
            pat (quote [* {:seon.code.var/output [:seon.schema/key]}])
            pv #(or (when (contains? bdh :seon.code.var/name) (let [p (d/pull bdb pat [:seon.code.var/name %])] (when (:seon.code.var/name p) p)))
                    (when (and (valid-db? mdb) (not (identical? mdb bdb)) (contains? mdh :seon.code.var/name)) (let [p (d/pull mdb pat [:seon.code.var/name %])] (when (:seon.code.var/name p) p))))]
        (->> (concat (mapcat extract-eval-edited-syms (filter #(= mt (to %)) evs)) fw)
             (filter qualified-symbol?) distinct (filter #(hiccup-renderer-var? (pv %))) sort vec)))))

(defn format-preview-summary
  "Formats a compact `<derived-preview>` text summary from ephemeral preview frame maps."
  {:malli/schema [:=> {:examples [[[[{:var (quote demo/render-card) :source :db :label "render-card · 360px" :width 360 :height 180 :dom {:overflow-x? false}}]]
                                   "<derived-preview ephemeral=\"true\">\nAuto-derived preview of edited `:seon.ui/hiccup` renderer(s) (cached in memory, 0 DB writes):\n- demo/render-card (input=db) [render-card · 360px]: 360×180px · overflowX=false · 0 defects\n</derived-preview>"]]}
                  [:cat [:sequential :map]] [:maybe :string]]
   :cost/ticks 1000000 :cost/bytes 262144}
  [frames]
  (when (seq frames)
    (let [lines (mapv (fn [{:keys [var source label width height dom visual-defects]}]
                        (let [w (if (number? width) (max 0 (long width)) 0) h (if (number? height) (max 0 (long height)) 0)
                              ov? (boolean (when (map? dom) (or (:overflow-x? dom) (:overflowX dom))))
                              ds (vec (keep #(when (and (string? %) (not (str/blank? %))) (str/trim %))
                                            (let [v (or visual-defects (when (map? dom) (:visual-defects dom)))] (when (sequential? v) v))))
                              all (cond-> ds (and ov? (not (some #(str/includes? (str/lower-case %) "overflow") ds))) (conj (str "Horizontal overflow at " w "px")))
                              lbl (or (when (and (string? label) (not (str/blank? label))) (str/trim label)) (str w "px"))]
                          (str "- " (or var :renderer) (when source (str " (input=" (name source) ")")) " [" lbl "]: " w "×" h "px · overflowX=" ov?
                               (if (seq all) (str " · DEFECTS(" (count all) "): " (str/join "; " (take 3 all))) " · 0 defects"))))
                      frames)]
      (str "<derived-preview ephemeral=\"true\">\nAuto-derived preview of edited `:seon.ui/hiccup` renderer(s) (cached in memory, 0 DB writes):\n"
           (str/join "\n" lines) "\n</derived-preview>"))))

(defn attach-preview-frames-to-messages
  "Attaches ephemeral derived preview frames + DOM telemetry to the trailing `user` message."
  {:malli/schema [:=> {:examples [[[[{:role "system" :content "S"} {:role "user" :content "Tail"}]
                                    [{:mime "image/jpeg" :base64 "AA==" :label "c · 360px" :width 360 :height 120 :dom {:overflow-x? false} :var (quote demo/c) :source :db}] false]
                                   [{:role "system" :content "S"}
                                    {:role "user" :content "Tail\n\n<derived-preview ephemeral=\"true\">\nAuto-derived preview of edited `:seon.ui/hiccup` renderer(s) (cached in memory, 0 DB writes):\n- demo/c (input=db) [c · 360px]: 360×120px · overflowX=false · 0 defects\n</derived-preview>"}]]]}
                  [:cat [:sequential :map] [:maybe [:sequential :map]] :boolean] [:vector :map]]
   :cost/ticks 2000000 :cost/bytes 1048576}
  [messages preview-frames vision?]
  (let [msgs (vec messages)
        fs (when (sequential? preview-frames) (vec (filter map? preview-frames)))]
    (if (empty? fs) msgs
      (if-let [tail (mwire/build-ephemeral-tail-msg (format-preview-summary fs) nil fs (boolean vision?))]
        (if (and (seq msgs) (= "user" (:role (peek msgs))))
          (let [lm (peek msgs) bc (:content lm) nc (:content tail)
                to-p #(cond (sequential? %) (vec %) (and (string? %) (not (str/blank? %))) [{:type "text" :text %}] :else [])
                mc (if (and (string? bc) (string? nc)) (if (str/blank? bc) nc (str bc "\n\n" nc)) (into (to-p bc) (to-p nc)))]
            (assoc msgs (dec (count msgs)) (assoc lm :content mc)))
          (conj msgs tail))
        msgs))))

(defn ^:seon.flow/edge render-derived-preview-card
  "Renders a safe Live Derived Preview card for a `:seon.ui/hiccup` renderer var entity."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :map] [:vector :any]] :cost/ticks 35000000}
  [eff-db ent]
  (let [vsym (:seon.code.var/name ent) prev (eval-renderer-preview eff-db vsym {:var-ent ent})
        [scls slbl] (case (:source prev) :db ["green" "live DB"] :example ["blue" "Malli :example"] :generated ["purple" "seed=42 generator"] ["muted" (name (or (:source prev) :derived))])
        clip-s #(if (> (count %) 2400) (str (subs % 0 2400) "…") %)]
    [:section {:id "insp-derived-preview" :class "card stack-sm"}
     [:div {:class "card-head"}
      [:div {:class "pill-row"} [:h2 "Live Derived Preview"]
       (if (:ok? prev) [:span {:class (str "badge " scls)} slbl] [:span {:class "badge red"} (name (or (:error prev) :error))])
       (when (and (:ok? prev) (:cached? prev)) [:span {:class "badge muted"} "cached"])]
      [:span {:class "muted tiny mono"} (str vsym)]]
     (if (:ok? prev)
       [:div {:class "derived-preview-stage" :style {:padding "12px" :border-radius "8px" :background "var(--base-950,#0d0d0c)" :border "1px solid var(--base-800,#302e2b)"}} (:hiccup prev)]
       [:div {:class "notice red mono tiny"} (str "Preview error: " (or (:message prev) (pr-str (:error prev))))])
     (when (or (:ok? prev) (seq (:args prev)))
       [:details {:class "activity-fold"}
        [:summary {:class "mono tiny"} (if (:ok? prev) (str "Sample Input EDN (" slbl " · #" (:input-hash prev) ")") "Sample Input EDN")]
        [:pre {:class "code-box tiny"} (clip-s (pr-str (:args prev)))]])]))

(defn- ^:seon.flow/edge post-op-frames! [port url html cap-opts]
  (try
    (let [resp (mwire/http-post! (str "http://127.0.0.1:" port "/op")
                                 [["Content-Type" "application/edn"]]
                                 (pr-str (assoc cap-opts :op :render-hiccup-frames :html html :url url)))]
      (when (= 200 (:status resp)) (edn/read-string (:body resp))))
    (catch Throwable _ nil)))

(defn ^:seon.flow/edge capture-html-frames!
  "Captures multi-width WKWebView frames for `html` via fn, `:browser` chan, warm `/op`, or `seon.browser/render-hiccup-frames!`."
  {:malli/schema [:=> [:cat :any :string :map :map] :map] :cost/ticks 45000000}
  [target html cap-opts o]
  (cond
    (fn? target) (target html cap-opts)
    (instance? clojure.core.async.impl.channels.ManyToManyChannel target) (browser/render-hiccup-frames! target html cap-opts)
    (string? target) (browser/render-hiccup-frames! target html cap-opts)
    (and (map? target) (fn? (:render-fn target))) ((:render-fn target) html cap-opts)
    (and (map? target) (:browser-ch target)) (browser/render-hiccup-frames! (:browser-ch target) html cap-opts)
    :else
    (let [port (or (when (map? target) (:port target)) (:port o) (System/getenv "SEON_PORT") 9998)
          url (or (when (map? target) (:url target)) (:url o) (str "http://127.0.0.1:" port "/"))
          via-op (when-not (:direct? o) (post-op-frames! port url html cap-opts))]
      (if (and (map? via-op) (:ok? via-op) (seq (:frames via-op))) via-op (browser/render-hiccup-frames! url html cap-opts)))))

(defn ^:seon.flow/edge render-var-preview-frames!
  "Renders ephemeral multi-width preview frames for `var-sym` using `preview-lru` (0 DB writes) + warm `:browser`."
  {:malli/schema [:function [:=> [:cat :any [:maybe :seon.host/db] :qualified-symbol] :map] [:=> [:cat :any [:maybe :seon.host/db] :qualified-symbol [:maybe :map]] :map]]
   :cost/ticks 45000000}
  ([srv db var-sym] (render-var-preview-frames! srv db var-sym nil))
  ([srv db var-sym opts]
   (let [o (if (map? opts) opts {}) ws (vec (or (seq (:widths o)) [360 640])) th (or (:theme o) :dark)
         ev (eval-renderer-preview db var-sym o)
         mm {:var var-sym :source (:source ev) :input-hash (:input-hash ev) :form-hash (:form-hash ev) :widths ws :theme th}]
     (if-not (:ok? ev)
       (assoc ev :frames [] :widths ws :theme th :cached? false :visual-ok? false :visual-defects [(or (:message ev) (str (:error ev)))])
       (let [ck [var-sym (:form-hash ev) (:input-hash ev) ws th] hit (when-not (:no-cache? o) (lru-peek ck))]
         (if (and (not (:no-cache? o)) (not (identical? ::miss hit)))
           (merge mm {:ok? true :frames hit :cached? true} (measure/aggregate-frame-defects hit))
           (let [co (merge {:widths ws :theme th :label-prefix (name var-sym)} (select-keys o [:height :quality :max-width :label-prefix :timeout-ms :settle-ms :binary-path]))
                 cap (capture-html-frames! srv (:html ev) co o)
                 fs (if (and (map? cap) (:ok? cap) (seq (:frames cap))) (mapv #(normalize-ephemeral-frame var-sym (:source ev) %) (:frames cap)) [])]
             (if (seq fs)
               (do (when-not (:no-cache? o) (lru-store! ck fs)) (merge mm {:ok? true :frames fs :cached? false} (measure/aggregate-frame-defects fs)))
               (assoc mm :ok? false :frames [] :cached? false :visual-ok? false :visual-defects [] :error (or (:error cap) :render-preview-frames-failed))))))))))

(defn ^:seon.flow/edge audit-html-visual!
  "Fast zero-DB-write multi-width visual audit over `html` via warm `/op` (or `:render-fn`), with graceful offline fallback."
  {:malli/schema [:function [:=> [:cat [:maybe :string]] :map] [:=> [:cat [:maybe :string] [:maybe :map]] :map]] :cost/ticks 35000000}
  ([html] (audit-html-visual! html nil))
  ([html opts]
   (let [o (if (map? opts) opts {}) ws (vec (or (seq (:widths o)) [360 960])) th (or (:theme o) :dark)]
     (if (or (false? (:audit? o)) (false? (:browser-audit? o)) (not (string? html)) (str/blank? html))
       {:visual-checked? false :visual-ok? true :visual-defects [] :widths ws}
       (let [co (merge {:widths ws :theme th :label-prefix (or (:label-prefix o) "audit")}
                       (select-keys o [:height :quality :max-width :timeout-ms :settle-ms :binary-path]))
             port (or (:port o) (System/getenv "SEON_PORT") 9998)
             url (or (:url o) (str "http://127.0.0.1:" port "/"))
             cap (cond (fn? (:render-fn o)) (try ((:render-fn o) html co) (catch Throwable _ nil))
                       (:browser-ch o) (try (browser/render-hiccup-frames! (:browser-ch o) html co) (catch Throwable _ nil))
                       (true? (:direct? o)) (try (browser/render-hiccup-frames! url html co) (catch Throwable _ nil))
                       :else (post-op-frames! port url html co))]
         (if (and (map? cap) (:ok? cap) (seq (:frames cap)))
           (assoc (measure/aggregate-frame-defects (:frames cap)) :visual-checked? true :widths ws)
           {:visual-checked? false :visual-ok? true :visual-defects [] :widths ws}))))))

(defn ^:seon.flow/edge audit-svg-visual!
  "Audits inline SVG (`raw-svg` + `sanitized-svg`) with static defect cues plus multi-width WKWebView check."
  {:malli/schema [:function [:=> [:cat [:maybe :string] [:maybe :string]] :map] [:=> [:cat [:maybe :string] [:maybe :string] [:maybe :map]] :map]]
   :cost/ticks 35000000}
  ([raw-svg sanitized-svg] (audit-svg-visual! raw-svg sanitized-svg nil))
  ([raw-svg sanitized-svg opts]
   (let [{:keys [defects svg-normalized?]} (sanitize/svg-static-defects raw-svg sanitized-svg)
         san-s (when (string? sanitized-svg) (str/trim sanitized-svg))
         br (if (seq san-s)
              (audit-html-visual! (str "<div class=\"canvas-surface\">" san-s "</div>") (merge {:widths [360 960] :label-prefix "status-svg"} opts))
              {:visual-checked? false :visual-ok? true :visual-defects []})
         all-defs (vec (distinct (concat defects (:visual-defects br))))]
     {:visual-checked? (boolean (:visual-checked? br)) :visual-ok? (empty? all-defs)
      :visual-defects all-defs :svg-normalized? svg-normalized?})))

(defn ^:seon.flow/edge audit-canvas-entry!
  "Audits a Live Agent Canvas vector or renderer symbol for eval errors, sanitizer drops, and visual defects."
  {:malli/schema [:function [:=> [:cat [:maybe :seon.host/db] [:maybe :keyword] :any] :map]
                  [:=> [:cat [:maybe :seon.host/db] [:maybe :keyword] :any [:maybe :map]] :map]]
   :cost/ticks 40000000}
  ([db agent-id hiccup-or-fn] (audit-canvas-entry! db agent-id hiccup-or-fn nil))
  ([db agent-id hiccup-or-fn opts]
   (let [o (if (map? opts) opts {})
         dh (when (valid-db? db) (store/db-schema db))
         theme (or (:theme o) (when (and dh (contains? dh :seon.user/theme))
                                (:seon.user/theme (d/pull db (quote [:seon.user/theme]) [:seon.user/id :user/default]))) :dark)
         ag (or (when (and dh agent-id (contains? dh :seon.agent/id))
                  (d/pull db (quote [*]) [:seon.agent/id agent-id])) {:seon.agent/id agent-id})
         {:keys [ok? eval-error hiccup dropped]} (sanitize/eval-and-sanitize-canvas db ag hiccup-or-fn)]
     (if-not ok?
       {:visual-checked? false :visual-ok? false :visual-defects [(str "Canvas error: " eval-error)] :dropped [] :eval-error eval-error}
       (let [html (when hiccup (str (render/hiccup->html [:section {:class "canvas-surface stack-sm"} hiccup])))
             br (if html (audit-html-visual! html (merge {:widths [360 960] :theme theme :label-prefix "canvas"} o))
                    {:visual-checked? false :visual-ok? false :visual-defects ["Canvas hiccup stripped to empty by sanitizer"]})
             defs (vec (distinct (concat (when (nil? hiccup) ["Canvas hiccup stripped to empty by sanitizer"]) (:visual-defects br))))]
         {:visual-checked? (boolean (:visual-checked? br)) :visual-ok? (and (empty? defs) (empty? dropped) (some? hiccup))
          :visual-defects defs :dropped dropped})))))

(defn summarize-preview-frames
  "Merges `:visual-ok?` and `:visual-defects` onto a preview result map from `frames` (zero DB writes)."
  {:malli/schema [:=> [:cat :map [:maybe [:sequential :map]]] :map]
   :cost/ticks 500000 :cost/bytes 131072}
  [res frames]
  (let [agg (measure/aggregate-frame-defects frames)]
    (assoc res :visual-ok? (:visual-ok? agg) :visual-defects (:visual-defects agg))))