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
            [seon.store :as store]))

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
        (some #(let [norm (fit/normalize-entity-for-malli db (d/pull db (quote [*]) (:e %)) forms)]
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

(defn- ^:seon.flow/edge post-op-frames! [port url html cap-opts]
  (try
    (let [resp (mwire/http-post! (str "http://127.0.0.1:" port "/op")
                                 [["Content-Type" "application/edn"]]
                                 (pr-str (cond-> (assoc cap-opts :op :render-hiccup-frames :html html) url (assoc :url url))))]
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
        eu (or (when (map? target) (:url target)) (:url o))
        url (or eu (str "http://127.0.0.1:" port "/"))
        via-op (when-not (:direct? o) (post-op-frames! port eu html cap-opts))]
    (if (and (map? via-op) (:ok? via-op) (seq (:frames via-op))) via-op (browser/render-hiccup-frames! url html cap-opts)))))

(defn- var-input-schemas [db ve]
  (or (seq (resolve-var-input-keys db ve))
      (let [sch (or (:seon.code.var/schema ve) (:malli/schema ve))
            ars (fn-schema-arities sch)
            ar (or (some #(when (= 1 (count (cat-arg-schemas (:in (arity-parts %))))) %) ars) (first ars))
            [a0 :as cargs] (cat-arg-schemas (when ar (:in (arity-parts ar))))]
        (cond
          (and (= 1 (count cargs)) (vector? a0) (= :map (first a0)))
          (->> (map-schema-entries a0) (remove :optional?)
               (keep #(or (when (qualified-keyword? (:schema %)) (:schema %))
                          (when (qualified-keyword? (:key %)) (:key %))))
               distinct vec)
          (and (= 1 (count cargs)) (qualified-keyword? a0)) [a0]
          :else []))))

(defn- form-hash-for [db var-sym ve v]
  (or (when (and (valid-db? db) (contains? (store/db-schema db) :seon.code.form/hash))
        (:seon.code.form/hash (d/pull db (quote [:seon.code.form/hash]) [:seon.code.form/key (or (:seon.code.var/form ve) (str var-sym))])))
      (str (hash (when v (:malli/schema (meta v)))))))

(defn- ^:seon.flow/edge collect-siblings [db var-sym var-tokens var-fh in-sks args]
  (if (empty? in-sks)
    []
    (let [disc (when (valid-db? db) (fit/discover-renderers db in-sks))
          rsyms (vec (distinct (if (some #(= var-sym %) disc) disc (cons var-sym disc))))]
      (mapv (fn [rsym]
              (let [rv (fit/resolve-renderer-var rsym)
                    rfh (if (= rsym var-sym) var-fh (form-hash-for db rsym nil rv))
                    tok (if (= rsym var-sym)
                          var-tokens
                          (try (when rv
                                 (let [r (apply @rv args)
                                       h (if (and (map? r) (contains? r :seon.ui/hiccup)) (:seon.ui/hiccup r) r)]
                                   (when (and (vector? h) (keyword? (first h))) (:tokens (fit/measure-hiccup h)))))
                               (catch Throwable _ nil)))
                    bbox (fit/cached-browser-box rsym rfh 640)]
                (cond-> {:var rsym} (integer? tok) (assoc :tokens tok) (map? bbox) (assoc :browser-box bbox))))
            rsyms))))

(defn- pull-var-ent [db var-sym v]
  (or (when (and (valid-db? db) (contains? (store/db-schema db) :seon.code.var/name))
        (let [p (d/pull db (quote [* {:seon.code.var/input [:seon.schema/key]} {:seon.code.var/output [:seon.schema/key]}])
                        [:seon.code.var/name var-sym])]
          (when (:seon.code.var/name p) p)))
      (when-let [rv (or v (fit/resolve-renderer-var var-sym))]
        {:seon.code.var/name var-sym :malli/schema (:malli/schema (meta rv))})))

(defn derive-renderer-sample-input
  "Synthesizes sample input in strict order: `:db`, `:example`, `:generated` (`seed=42`), always computing `:has-examples?`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :any] [:maybe :map]] :cost/ticks 25000000}
  [db var-ent]
  (let [ve (cond (map? var-ent) var-ent (qualified-symbol? var-ent) (pull-var-ent db var-ent nil))]
    (when (map? ve)
      (let [sch (or (:seon.code.var/schema ve) (:malli/schema ve)
                    (some-> (:seon.code.var/name ve) fit/resolve-renderer-var meta :malli/schema))
            ars (fn-schema-arities sch)
            has-ex? (boolean (sample-from-examples ars sch))
            ar (or (some #(when (= 1 (count (cat-arg-schemas (:in (arity-parts %))))) %) ars) (first ars))
            cf (when ar (:in (arity-parts ar)))
            ri (schema/registry-for-db (when (valid-db? db) db))
            mk #(let [av (vec %2)] {:source %1 :args av :input-hash (store/sha256-hex (pr-str av) 16) :has-examples? has-ex?})]
        (or (when-let [a (sample-from-db db ri ve cf (cat-arg-schemas cf))] (mk :db a))
            (when-let [a (sample-from-examples ars sch)] (mk :example a))
            (when-let [a (sample-from-generator ri ar cf)] (mk :generated a)))))))

(defn ^:seon.flow/edge eval-renderer-preview
  "Resolves `var-sym`, derives sample input, evaluates `:seon.ui/hiccup` + HTML + authoring metadata, and caches in `preview-lru`."
  {:malli/schema [:function [:=> [:cat [:maybe :seon.host/db] :qualified-symbol] :map] [:=> [:cat [:maybe :seon.host/db] :qualified-symbol [:maybe :map]] :map]]
   :cost/ticks 35000000}
  ([db var-sym] (eval-renderer-preview db var-sym nil))
  ([db var-sym opts]
   (let [o (if (map? opts) opts {}) v (fit/resolve-renderer-var var-sym)
         ve (or (:var-ent o) (pull-var-ent db var-sym v))
         sch (or (:seon.code.var/schema ve) (:malli/schema ve) (when v (:malli/schema (meta v))))
         has-ex? (boolean (sample-from-examples (fn-schema-arities sch) sch))
         in-sks (vec (var-input-schemas db ve))
         fh (form-hash-for db var-sym ve v)
         mk #(hash-map :source (or (:source o) :explicit) :args % :input-hash (store/sha256-hex (pr-str %) 16) :has-examples? has-ex?)
         smp (cond (contains? o :args) (mk (vec (:args o))) (contains? o :input) (mk [(:input o)]) :else (derive-renderer-sample-input db ve))]
     (cond
       (nil? v) {:ok? false :var var-sym :error :var-not-resolved :message (str "Could not resolve " var-sym)}
       (nil? smp) {:ok? false :var var-sym :error :no-sample-input :has-examples? has-ex? :input-schemas in-sks :message (str "No derivable sample input for " var-sym)}
       :else
       (let [ck [var-sym fh (:input-hash smp) :eval nil]
             base {:var var-sym :form-hash fh :source (:source smp) :args (:args smp) :input-hash (:input-hash smp)
                   :has-examples? (if (boolean? (:has-examples? smp)) (:has-examples? smp) has-ex?) :input-schemas in-sks}
             hit (when-not (:no-cache? o) (lru-peek ck))]
         (if (and (not (:no-cache? o)) (not (identical? ::miss hit)))
           (assoc hit :cached? true)
           (let [r (try
                     (let [res (apply @v (:args smp)) h (if (and (map? res) (contains? res :seon.ui/hiccup)) (:seon.ui/hiccup res) res)]
                       (if (and (vector? h) (keyword? (first h)))
                         (let [tok (:tokens (fit/measure-hiccup h))]
                           (assoc base :ok? true :hiccup h :html (str (render/hiccup->html h)) :tokens tok :siblings (collect-siblings db var-sym tok fh in-sks (:args smp))))
                         (assoc base :ok? false :error (if (nil? h) :empty-hiccup :invalid-hiccup) :message (str var-sym " did not return Hiccup"))))
                     (catch Throwable ex (assoc base :ok? false :error :eval-failed :message (or (.getMessage ex) (str ex)))))]
             (when-not (:no-cache? o) (lru-store! ck r)) (assoc r :cached? false))))))))

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

(defn- px-int [x] (if (number? x) (max 0 (long x)) 0))

(defn- nb-str [x] (when (and (string? x) (not (str/blank? x))) (str/trim x)))

(defn format-preview-summary
  "Formats `<derived-preview>` text with missing `:examples` and multi-footprint sibling coaching."
  {:malli/schema [:=> {:examples [[[[{:var (quote demo/c) :source :db :tokens 42 :has-examples? true :label "c · 360px" :width 360 :height 180 :dom {:overflow-x? false}}]]
                                   "<derived-preview ephemeral=\"true\">
Rendered edited `:seon.ui/hiccup` component(s) in WKWebView (0 DB writes) — inspect the attached image(s) visually:
- demo/c (input=db, 42 tok) [c · 360px]: 360×180px · overflowX=false
</derived-preview>"]]}
                  [:cat [:sequential :map]] [:maybe :string]]
   :cost/ticks 1500000 :cost/bytes 262144}
  [frames]
  (when (seq frames)
    (let [lines (mapv (fn [{:keys [var source tokens label width height dom]}]
                        (let [w (px-int width) h (px-int height)
                              ov? (boolean (when (map? dom) (or (:overflow-x? dom) (:overflowX dom))))
                              lbl (or (nb-str label) (str w "px"))
                              mp (cond-> [] (keyword? source) (conj (str "input=" (name source))) (number? tokens) (conj (str (px-int tokens) " tok")))]
                          (str "- " (or var :renderer) (when (seq mp) (str " (" (str/join ", " mp) ")")) " [" lbl "]: " w "×" h "px · overflowX=" ov?)))
                      frames)
          ex-notes (mapv #(str "Note: `" % "` is missing `:examples` (example inputs & outputs) in `:malli/schema` — synthesized input via seed=42 generative testing (or live DB) so you can see how it renders. Add `:examples` to `:malli/schema`.")
                         (distinct (keep #(when (false? (:has-examples? %)) (:var %)) frames)))
          max-h (reduce (fn [m f] (if-let [v (:var f)] (update m v #(max (long (or % 0)) (px-int (:height f)) (px-int (when (map? (:dom f)) (:h (:dom f)))))) m)) {} frames)
          sk-notes (loop [fs frames seen #{} out []]
                     (if-not (seq fs) out
                       (let [{:keys [var tokens input-schemas siblings]} (first fs)
                             sks (when (sequential? input-schemas) (filterv #(and (qualified-keyword? %) (not (seen %))) input-schemas))
                             sibs (when (sequential? siblings) (filterv #(and (map? %) (:var %)) siblings))
                             mk (fn [sk]
                                  (if (> (count sibs) 1)
                                    (let [ss (sort-by #(vector (px-int (:tokens %)) (str (:var %))) sibs)]
                                      (str "Footprints for `" sk "`: " (str/join ", " (map #(str (:var %) (when (number? (:tokens %)) (str " (" (px-int (:tokens %)) " tok)"))) ss)) "."))
                                    (let [rv (or (:var (first sibs)) var)
                                          tk (when (number? (or (:tokens (first sibs)) tokens)) (px-int (or (:tokens (first sibs)) tokens)))]
                                      (when (and rv (or (and tk (> tk 80)) (> (long (get max-h rv 0)) 120)))
                                        (str "Footprints for `" sk "`: 1 renderer (`" rv "`" (when tk (str " " tk " tok"))
                                             "). Consider adding a compact variation (`-chip`/`-row`) so the Upgrade Ladder can fit narrow slots and tight budgets.")))))]
                         (recur (rest fs) (into seen sks) (into out (keep mk sks))))))]
      (str "<derived-preview ephemeral=\"true\">
Rendered edited `:seon.ui/hiccup` component(s) in WKWebView (0 DB writes) — inspect the attached image(s) visually:
"
           (str/join "
" (concat lines ex-notes sk-notes)) "
</derived-preview>"))))

(defn attach-preview-frames-to-messages
  "Attaches ephemeral derived preview frames + DOM telemetry to the trailing `user` message."
  {:malli/schema [:=> [:cat [:sequential :map] [:maybe [:sequential :map]] :boolean] [:vector :map]]
   :cost/ticks 2000000 :cost/bytes 1048576}
  [messages preview-frames vision?]
  (let [msgs (vec messages)
        fs (when (sequential? preview-frames) (vec (filter map? preview-frames)))]
    (if (empty? fs) msgs
      (if-let [tail (mwire/build-ephemeral-tail-msg (format-preview-summary fs) nil fs (boolean vision?))]
        (if (and (seq msgs) (= "user" (:role (peek msgs))))
          (let [lm (peek msgs) bc (:content lm) nc (:content tail)
                to-p #(cond (sequential? %) (vec %) (and (string? %) (not (str/blank? %))) [{:type "text" :text %}] :else [])
                mc (if (and (string? bc) (string? nc)) (if (str/blank? bc) nc (str bc "

" nc)) (into (to-p bc) (to-p nc)))]
            (assoc msgs (dec (count msgs)) (assoc lm :content mc)))
          (conj msgs tail))
        msgs))))

(defn normalize-ephemeral-frame
  "Normalizes a WKWebView frame map into a factual ephemeral preview map."
  {:malli/schema [:=> {:examples [[[(quote demo/c) :db {:seon.media/mime "image/jpeg" :seon.media/base64 "AA==" :seon.media/width 360 :seon.media/height 120 :seon.media/label "c · 360px" :seon.media/dom {:overflow-x? false}}]
                                   {:mime "image/jpeg" :base64 "AA==" :label "c · 360px" :width 360 :height 120 :dom {:overflow-x? false} :var (quote demo/c) :source :db}]]}
                  [:cat :qualified-symbol [:maybe :keyword] :map [:? [:maybe :map]]] :map]
   :cost/ticks 500000 :cost/bytes 131072}
  ([var-sym source f] (normalize-ephemeral-frame var-sym source f nil))
  ([var-sym source f meta-info]
   (let [w (px-int (or (:seon.media/width f) (:width f)))
         h (px-int (or (:seon.media/height f) (:height f)))
         dom (let [d (or (:seon.media/dom f) (:dom f))] (if (map? d) d {}))
         m (if (map? meta-info) (merge f meta-info) f)]
     (cond-> {:mime (or (nb-str (or (:seon.media/mime f) (:mime f))) "image/jpeg")
              :base64 (str (or (:seon.media/base64 f) (:base64 f) ""))
              :label (or (nb-str (or (:seon.media/label f) (:label f))) (str (name var-sym) " · " w "px"))
              :width w :height h :dom dom :var var-sym :source source}
       (number? (:tokens m)) (assoc :tokens (px-int (:tokens m)))
       (boolean? (:has-examples? m)) (assoc :has-examples? (:has-examples? m))
       (sequential? (:input-schemas m)) (assoc :input-schemas (vec (:input-schemas m)))
       (sequential? (:siblings m)) (assoc :siblings (vec (:siblings m)))))))

(defn ^:seon.flow/edge render-var-preview-frames!
  "Renders ephemeral multi-width preview frames for `var-sym` (`preview-lru`, 0 DB writes) and caches boxes."
  {:malli/schema [:function [:=> [:cat :any [:maybe :seon.host/db] :qualified-symbol] :map] [:=> [:cat :any [:maybe :seon.host/db] :qualified-symbol [:maybe :map]] :map]]
   :cost/ticks 45000000}
  ([srv db var-sym] (render-var-preview-frames! srv db var-sym nil))
  ([srv db var-sym opts]
   (let [o (if (map? opts) opts {}) ws (vec (or (seq (:widths o)) (when (valid-db? db) (store/config-val db :seon.config/preview-widths)) [360 640])) th (or (:theme o) :dark)
         ev (eval-renderer-preview db var-sym o) fh (:form-hash ev)
         mm (assoc (select-keys ev [:var :source :input-hash :form-hash :tokens :has-examples? :input-schemas :siblings]) :widths ws :theme th)
         rec! (fn [fs] (mapv #(fit/record-browser-box! var-sym fh (long (:width %)) (if (map? (:dom %)) (:dom %) {:w (:width %) :h (:height %)})) fs))]
     (if-not (:ok? ev)
       (assoc ev :frames [] :boxes [] :widths ws :theme th :cached? false)
       (let [ck [var-sym fh (:input-hash ev) ws th] hit (when-not (:no-cache? o) (lru-peek ck))]
         (if (and (not (:no-cache? o)) (not (identical? ::miss hit)))
           (assoc mm :ok? true :frames hit :boxes (rec! hit) :cached? true)
           (let [co (merge {:widths ws :theme th :label-prefix (name var-sym) :var-sym var-sym :form-hash fh} (select-keys o [:height :quality :max-width :label-prefix :timeout-ms :settle-ms :binary-path]))
                 cap (capture-html-frames! srv (:html ev) co o)
                 fs (if (and (map? cap) (:ok? cap) (seq (:frames cap))) (mapv #(normalize-ephemeral-frame var-sym (:source ev) % ev) (:frames cap)) [])]
             (if (seq fs)
               (let [boxes (rec! fs)]
                 (when-not (:no-cache? o) (lru-store! ck fs))
                 (assoc mm :ok? true :frames fs :boxes boxes :cached? false))
               (assoc mm :ok? false :frames [] :boxes [] :cached? false :error (or (:error cap) :render-preview-frames-failed))))))))))

(defn ^:seon.flow/edge measure-schema-renderers!
  "Measures all `:seon.ui/hiccup` renderers for `schema-key` on one shared input and caches boxes in `fit`."
  {:malli/schema [:function [:=> [:cat :any [:maybe :seon.host/db] :qualified-keyword] :map]
                  [:=> [:cat :any [:maybe :seon.host/db] :qualified-keyword [:maybe :map]] :map]]
   :cost/ticks 60000000}
  ([srv db schema-key] (measure-schema-renderers! srv db schema-key nil))
  ([srv db schema-key opts]
   (let [o (if (map? opts) opts {}) ws (vec (or (seq (:widths o)) (when (valid-db? db) (store/config-val db :seon.config/preview-widths)) [360 640])) th (or (:theme o) :dark)
         rsyms (vec (or (seq (:renderers o)) (when (valid-db? db) (fit/discover-renderers db schema-key))))]
     (if (empty? rsyms)
       {:ok? false :schema schema-key :error :no-renderers :widths ws :theme th :renderers [] :frames []}
       (let [shared (cond (contains? o :args) {:source :explicit :args (vec (:args o))}
                          (contains? o :input) {:source :explicit :args [(:input o)]}
                          :else (first (sort-by #({:db 0 :example 1 :generated 2} (:source %) 9) (keep #(derive-renderer-sample-input db %) rsyms))))]
         (if-not shared
           {:ok? false :schema schema-key :error :no-sample-input :widths ws :theme th :renderers [] :frames []}
           (let [ropts (merge o {:widths ws :theme th :args (:args shared) :source (:source shared)})
                 rs (mapv #(render-var-preview-frames! srv db % ropts) rsyms)
                 rows (mapv (fn [rsym r]
                              (cond-> {:var rsym :ok? (boolean (:ok? r)) :tokens (:tokens r)
                                       :has-examples? (boolean (:has-examples? r)) :boxes (vec (:boxes r))}
                                (:error r) (assoc :error (:error r))))
                            rsyms rs)]
             {:ok? (boolean (some :ok? rows)) :schema schema-key :source (:source shared)
              :widths ws :theme th :renderers rows :frames (vec (mapcat :frames rs))})))))))