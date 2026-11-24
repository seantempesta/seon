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

(defn- ^:seon.flow/edge lru-peek [k]
  (when-not (satisfies? cache/CacheProtocol @preview-lru)
    (reset! preview-lru (cache/lru-cache-factory {} :threshold 64)))
  (let [raw (get @preview-lru k ::miss)]
    (if (identical? ::miss raw)
      ::miss
      (do (swap! preview-lru cache/hit k)
          (if (and (map? raw) (contains? raw :seon.store/lru-val))
            (:seon.store/lru-val raw)
            raw)))))

(defn- ^:seon.flow/edge lru-store! [k v]
  (when-not (satisfies? cache/CacheProtocol @preview-lru)
    (reset! preview-lru (cache/lru-cache-factory {} :threshold 64)))
  (swap! preview-lru cache/miss k v)
  v)

(defn ^:seon.flow/edge cached-preview-frames
  "Looks up cached ephemeral frames in `preview-lru` for `[var-sym form-hash input-hash widths theme]` (0 DB reads/writes)."
  {:malli/schema [:=> [:cat :qualified-symbol :any :any [:sequential :int] :any] [:maybe [:vector :map]]]
   :cost/ticks 100000}
  [var-sym form-hash input-hash widths theme]
  (let [hit (lru-peek [var-sym form-hash input-hash (vec widths) theme])]
    (when-not (identical? ::miss hit) hit)))

(defn ^:seon.flow/edge cache-preview-frames!
  "Stores ephemeral rendered frames in bounded in-memory `preview-lru` (0 DB writes)."
  {:malli/schema [:=> [:cat :qualified-symbol :any :any [:sequential :int] :any [:vector :map]] [:vector :map]]
   :cost/ticks 100000}
  [var-sym form-hash input-hash widths theme frames]
  (lru-store! [var-sym form-hash input-hash (vec widths) theme] (vec frames)))

(defn- fn-schema-arities [sch]
  (cond
    (and (vector? sch) (= :=> (first sch))) [sch]
    (and (vector? sch) (= :function (first sch)))
    (vec (filter #(and (vector? %) (= :=> (first %))) (rest sch)))
    :else []))

(defn- arity-parts [ar]
  (when (and (vector? ar) (= :=> (first ar)))
    (if (map? (second ar))
      {:props (second ar) :in (nth ar 2 nil) :out (nth ar 3 nil)}
      {:props {} :in (nth ar 1 nil) :out (nth ar 2 nil)})))

(defn- mentions-hiccup? [form]
  (boolean
   (when (some? form)
     (some #(= :seon.ui/hiccup %) (tree-seq coll? seq form)))))

(defn hiccup-renderer-var?
  "Returns true when `var-ent` is a public pure `:seon.ui/hiccup` renderer (via `:seon.code.var/output`
   containing `:seon.ui/hiccup` or `:seon.code.var/schema` / `:malli/schema` return mentioning `:seon.ui/hiccup`)."
  {:malli/schema [:=> {:examples [[[{:seon.code.var/name (quote demo/render-card)
                                     :seon.code.var/output [:seon.ui/hiccup]}] true]
                                  [[{:seon.code.var/name (quote demo/render-chip)
                                     :malli/schema [:=> [:cat [:map [:seon.task/entity :seon.task/entity]]]
                                                    [:map [:seon.ui/hiccup :seon.ui/hiccup]]]}] true]
                                  [[{:seon.code.var/name (quote demo/helper)
                                     :seon.code.var/schema [:=> [:cat :int] :int]}] false]
                                  [[{:seon.code.var/name (quote demo/priv)
                                     :seon.code.var/private? true
                                     :seon.code.var/output [:seon.ui/hiccup]}] false]]}
                  [:cat :any] :boolean]
   :cost/ticks 500000 :cost/bytes 131072}
  [var-ent]
  (boolean
   (when (and (map? var-ent)
              (not (:seon.code.var/private? var-ent))
              (not (:seon.code.var/test? var-ent))
              (not (:seon.code.var/macro? var-ent))
              (not (:seon.code.var/flow-edge? var-ent))
              (empty? (:seon.code.var/ops var-ent)))
     (let [outs (:seon.code.var/output var-ent)
           out-hit? (and (coll? outs)
                         (some (fn [x]
                                 (or (= :seon.ui/hiccup x)
                                     (and (map? x) (= :seon.ui/hiccup (:seon.schema/key x)))))
                               outs))
           sch (or (:seon.code.var/schema var-ent) (:malli/schema var-ent))
           ret-hit? (some #(mentions-hiccup? (:out (arity-parts %)))
                          (fn-schema-arities sch))]
       (or out-hit? ret-hit?)))))

(defn- cat-arg-schemas [cat-form]
  (when (and (vector? cat-form) (= :cat (first cat-form)))
    (if (map? (second cat-form))
      (vec (drop 2 cat-form))
      (vec (rest cat-form)))))

(defn- map-schema-entries [form]
  (when (and (vector? form) (= :map (first form)))
    (let [raw (if (map? (second form)) (drop 2 form) (rest form))]
      (vec (keep (fn [entry]
                   (when (and (vector? entry) (>= (count entry) 2))
                     (let [k (first entry)
                           has-p? (and (>= (count entry) 3) (map? (second entry)))
                           p (if has-p? (second entry) {})
                           s (if has-p? (nth entry 2) (second entry))]
                       {:key k :optional? (boolean (:optional p)) :schema s})))
                 raw)))))

(defn- valid-db? [db]
  (and db (instance? datahike.db.interface.ISearch db) (map? (:schema db))))

(defn- set-schema-form? [f]
  (and (vector? f)
       (or (= :set (first f))
           (and (= :and (first f)) (some set-schema-form? (rest f))))))

(defn- normalize-entity-for-malli [db ent forms]
  (if-not (map? ent)
    ent
    (let [dh (store/db-schema db)
          unwrap (fn [x] (if (and (map? x) (= 1 (count x)) (integer? (:db/id x))) (:db/id x) x))]
      (into {}
            (map (fn [[k v]]
                   (let [spec (get dh k)
                         many? (or (= :db.cardinality/many (:db/cardinality spec))
                                   (set-schema-form? (get forms k)))
                         ref? (= :db.type/ref (:db/valueType spec))
                         v1 (cond
                              (and many? (sequential? v)) (into #{} (if ref? (map unwrap) identity) v)
                              (and many? (set? v) ref?) (into #{} (map unwrap) v)
                              (and many? (some? v) (not (set? v))) #{(if ref? (unwrap v) v)}
                              ref? (unwrap v)
                              :else v)]
                     [k v1])))
            ent))))

(defn- resolve-var-input-keys [db var-ent]
  (let [raw (:seon.code.var/input var-ent)]
    (if-not (coll? raw)
      []
      (vec (distinct
            (keep (fn [x]
                    (cond
                      (qualified-keyword? x) x
                      (and (map? x) (qualified-keyword? (:seon.schema/key x))) (:seon.schema/key x)
                      (and (valid-db? db) (map? x) (integer? (:db/id x)))
                      (:seon.schema/key (d/pull db (quote [:seon.schema/key]) (:db/id x)))
                      (and (valid-db? db) (integer? x))
                      (:seon.schema/key (d/pull db (quote [:seon.schema/key]) x))
                      :else nil))
                  raw))))))

(defn- pull-live-entity-for-schema [db reg-info sk]
  (when (and (valid-db? db) (qualified-keyword? sk))
    (let [dh (store/db-schema db)
          ix (:entity-index reg-info)
          forms (:forms reg-info)
          reg (:registry reg-info)
          id-attr (or (get-in ix [:schema-identities sk])
                      (first (filter #(contains? dh %) (get-in ix [:required-keys sk])))
                      (when (contains? dh sk) sk))
          known-sch? (contains? forms sk)
          valid? (fn [cand]
                   (if-not known-sch?
                     true
                     (let [r (try {:ok? (boolean (m/validate sk cand {:registry reg}))}
                                  (catch Throwable ex {:ok? false :error (.getMessage ex)}))]
                       (true? (:ok? r)))))]
      (when (and id-attr (contains? dh id-attr))
        (some (fn [dt]
                (let [raw (d/pull db (quote [*]) (:e dt))
                      norm (normalize-entity-for-malli db raw forms)]
                  (when (and (map? norm) (seq (dissoc norm :db/id)) (valid? norm))
                    norm)))
              (take 8 (d/datoms db :aevt id-attr)))))))

(defn- sample-from-db [db reg-info var-ent cat-form cat-args]
  (when (valid-db? db)
    (let [reg (:registry reg-info)
          in-ks (resolve-var-input-keys db var-ent)
          ok? (fn [args]
                (or (nil? cat-form)
                    (true? (:ok? (try {:ok? (boolean (m/validate cat-form args {:registry reg}))}
                                      (catch Throwable ex {:ok? false :error (.getMessage ex)}))))))
          pull-pairs (fn [ks-fn xs]
                       (let [ps (mapv (fn [x] (let [[k sk] (ks-fn x)] [k (pull-live-entity-for-schema db reg-info sk)])) xs)]
                         (when (and (seq ps) (every? (comp some? second) ps))
                           (let [args [(into {} ps)]] (when (ok? args) args)))))]
      (cond
        (and (= 1 (count cat-args)) (vector? (first cat-args)) (= :map (first (first cat-args))))
        (let [req (filterv (comp not :optional?) (map-schema-entries (first cat-args)))]
          (if (seq req)
            (pull-pairs #(vector (:key %) (cond (qualified-keyword? (:schema %)) (:schema %) (qualified-keyword? (:key %)) (:key %) :else nil)) req)
            (pull-pairs #(vector % %) in-ks)))
        (and (= 1 (count cat-args)) (qualified-keyword? (first cat-args)))
        (when-let [ent (pull-live-entity-for-schema db reg-info (first cat-args))] (when (ok? [ent]) [ent]))
        (and (nil? cat-form) (seq in-ks)) (pull-pairs #(vector % %) in-ks)
        :else nil))))

(defn- sample-from-examples [arities sch]
  (let [top-props (when (and (vector? sch) (map? (second sch))) (second sch))
        ex-lists (keep :examples (cons top-props (map (comp :props arity-parts) arities)))]
    (some (fn [exs]
            (when (and (coll? exs) (not (map? exs)))
              (some (fn [ex]
                      (when (and (vector? ex) (>= (count ex) 1) (vector? (first ex)))
                        (first ex)))
                    exs)))
          ex-lists)))

(defn- sample-from-generator [reg-info ar cat-form]
  (when (and ar cat-form (schema/generatable-function-schema? ar))
    (let [reg (:registry reg-info)
          r (try
              (let [s (m/schema cat-form {:registry reg})
                    g (mg/generate s {:registry reg :seed 42 :size 4})]
                (if (sequential? g) {:ok? true :args (vec g)} {:ok? false :error :non-seq}))
              (catch Throwable ex {:ok? false :error (or (.getMessage ex) (str ex))}))]
      (when (:ok? r) (:args r)))))

(defn derive-renderer-sample-input
  "Synthesizes sample input for a `:seon.ui/hiccup` renderer in strict priority order:
   (1) `:db` live matching entity in Datahike via `:seon.code.var/input` / schema,
   (2) `:example` Malli `:examples` attached to the var schema,
   (3) `:generated` deterministic `seed=42` `malli.generator/generate` via `seon.schema` registry.
   Returns `{:source :db|:example|:generated :args [...] :input-hash ...}` or nil."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :any] [:maybe :map]]
   :cost/ticks 25000000}
  [db var-ent]
  (let [ve (cond
             (map? var-ent) var-ent
             (and (qualified-symbol? var-ent) (valid-db? db) (contains? (store/db-schema db) :seon.code.var/name))
             (d/pull db (quote [* {:seon.code.var/input [:seon.schema/key]}
                                {:seon.code.var/output [:seon.schema/key]}])
                     [:seon.code.var/name var-ent])
             :else nil)]
    (when (map? ve)
      (let [sch (or (:seon.code.var/schema ve) (:malli/schema ve))
            arities (fn-schema-arities sch)
            ar (or (some #(when (= 1 (count (cat-arg-schemas (:in (arity-parts %))))) %) arities)
                   (first arities))
            cat-form (when ar (:in (arity-parts ar)))
            cat-args (cat-arg-schemas cat-form)
            reg-info (schema/registry-for-db (when (valid-db? db) db))
            mk (fn [src args]
                 (let [av (vec args)]
                   {:source src :args av :input-hash (store/sha256-hex (pr-str av) 16)}))]
        (or (when-let [a (sample-from-db db reg-info ve cat-form cat-args)] (mk :db a))
            (when-let [a (sample-from-examples arities sch)] (mk :example a))
            (when-let [a (sample-from-generator reg-info ar cat-form)] (mk :generated a)))))))

(defn ^:seon.flow/edge eval-renderer-preview
  "Resolves `var-sym`, derives sample input, evaluates `:seon.ui/hiccup` + HTML safely, and caches in bounded `preview-lru` (0 DB writes)."
  {:malli/schema [:function
                  [:=> [:cat [:maybe :seon.host/db] :qualified-symbol] :map]
                  [:=> [:cat [:maybe :seon.host/db] :qualified-symbol [:maybe :map]] :map]]
   :cost/ticks 35000000}
  ([db var-sym] (eval-renderer-preview db var-sym nil))
  ([db var-sym opts]
   (let [o (if (map? opts) opts {})
         v (fit/resolve-renderer-var var-sym)
         dh (when (valid-db? db) (store/db-schema db))
         ve (or (:var-ent o)
                (when (and dh (contains? dh :seon.code.var/name))
                  (let [p (d/pull db (quote [* {:seon.code.var/input [:seon.schema/key]}
                                             {:seon.code.var/output [:seon.schema/key]}])
                                  [:seon.code.var/name var-sym])]
                    (when (:seon.code.var/name p) p)))
                (when v {:seon.code.var/name var-sym :malli/schema (:malli/schema (meta v))}))
         fh (or (when (and dh (contains? dh :seon.code.form/hash))
                  (:seon.code.form/hash
                   (d/pull db (quote [:seon.code.form/hash])
                           [:seon.code.form/key (or (:seon.code.var/form ve) (str var-sym))])))
                (str (hash (when v (:malli/schema (meta v))))))
         mk-exp (fn [av] {:source :explicit :args av :input-hash (store/sha256-hex (pr-str av) 16)})
         sample (cond (contains? o :args) (mk-exp (vec (:args o)))
                      (contains? o :input) (mk-exp [(:input o)])
                      :else (derive-renderer-sample-input db ve))]
     (cond
       (nil? v) {:ok? false :var var-sym :error :var-not-resolved :message (str "Could not resolve " var-sym)}
       (nil? sample) {:ok? false :var var-sym :error :no-sample-input :message (str "No derivable sample input for " var-sym)}
       :else
       (let [ck [var-sym fh (:input-hash sample) :eval nil]
             base {:var var-sym :form-hash fh :source (:source sample) :args (:args sample) :input-hash (:input-hash sample)}
             compute (fn []
                       (try
                         (let [res (apply @v (:args sample))
                               h (cond (and (map? res) (contains? res :seon.ui/hiccup)) (:seon.ui/hiccup res)
                                       (vector? res) res :else nil)]
                           (if (and (vector? h) (seq h) (keyword? (first h)))
                             (assoc base :ok? true :hiccup h :html (str (render/hiccup->html h)))
                             (assoc base :ok? false :error (if (nil? h) :empty-hiccup :invalid-hiccup)
                                    :message (str var-sym " did not return a Hiccup vector"))))
                         (catch Throwable ex
                           (assoc base :ok? false :error :eval-failed :message (or (.getMessage ex) (str ex))))))
             hit (when-not (:no-cache? o) (lru-peek ck))]
         (if (and (not (:no-cache? o)) (not (identical? ::miss hit)))
           (assoc hit :cached? true)
           (let [r (compute)]
             (when-not (:no-cache? o) (lru-store! ck r))
             (assoc r :cached? false))))))))

(defn- normalize-ephemeral-frame [var-sym source f]
  (let [w (let [v (or (:seon.media/width f) (:width f))] (if (number? v) (max 0 (long v)) 0))
        h (let [v (or (:seon.media/height f) (:height f))] (if (number? v) (max 0 (long v)) 0))
        mime (let [v (or (:seon.media/mime f) (:mime f))]
               (if (and (string? v) (not (str/blank? v))) (str/trim v) "image/jpeg"))
        lbl (let [v (or (:seon.media/label f) (:label f))]
              (if (and (string? v) (not (str/blank? v))) (str/trim v) (str (name var-sym) " · " w "px")))
        dom (let [d (or (:seon.media/dom f) (:dom f))] (if (map? d) d {}))]
    {:mime mime
     :base64 (str (or (:seon.media/base64 f) (:base64 f) ""))
     :label lbl
     :width w
     :height h
     :dom dom
     :var var-sym
     :source source}))

(defn- extract-eval-edited-syms [ev]
  (let [touched (concat (when (coll? (:seon.agent.eval/touched-vars ev)) (:seon.agent.eval/touched-vars ev))
                        (when (coll? (:touched-vars ev)) (:touched-vars ev)))
        code-s (str (or (:seon.agent.eval/code ev) (:code ev) ""))
        val-s (str (or (:seon.agent.eval/value ev) (:value ev) ""))
        edit-code? (boolean (re-find #"(?:edit-fn!|add-fn!|set-meta!|ui-patch!|\(defn\s)" code-s))
        from-code (when edit-code?
                    (keep (fn [[_ a b]]
                            (let [s (or a b)]
                              (when (and (string? s) (str/includes? s "/") (< (count s) 128))
                                (let [[n nm] (str/split s #"/" 2)]
                                  (when (and (seq n) (seq nm) (not= n "result")) (symbol n nm))))))
                          (re-seq #"(?:\u0027([a-zA-Z][\w.-]*/[\w.*?!+-]+)|\(quote\s+([a-zA-Z][\w.-]*/[\w.*?!+-]+)\))" code-s)))
        from-val (when (and (not (str/blank? val-s))
                            (or (str/includes? val-s ":var ") (str/includes? val-s ":target ")))
                   (keep (fn [[_ s]]
                           (when (and (string? s) (str/includes? s "/"))
                             (let [[n nm] (str/split s #"/" 2)]
                               (when (and (seq n) (seq nm)) (symbol n nm)))))
                         (re-seq #"(?::var|:target)\s+([a-zA-Z][\w.-]*/[\w.*?!+-]+)" val-s)))]
    (filter qualified-symbol? (concat touched from-code from-val))))

(defn recent-edited-renderer-vars
  "Inspects latest-turn `evals` (`:seon.agent.eval/touched-vars` or edit calls) and `branch-db` writes, returning touched `:seon.ui/hiccup` renderer vars."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] [:maybe [:sequential :map]]]
                  [:vector :qualified-symbol]]
   :cost/ticks 20000000}
  [main-db branch-db evals]
  (let [bdb (or branch-db main-db) mdb (or main-db bdb)]
    (if-not (valid-db? bdb)
      []
      (let [evs (if (sequential? evals) (vec (filter map? evals)) [])
            turn-of #(long (or (:seon.agent.eval/turn %) (:turn %) 0))
            max-t (if (seq evs) (reduce max 0 (map turn-of evs)) 0)
            latest-evs (if (seq evs) (filterv #(= max-t (turn-of %)) evs) [])
            prev-evs (if (seq evs) (filterv #(< (turn-of %) max-t) evs) [])
            eval-tx #(or (when (integer? (:seon.agent.eval/tx %)) (long (:seon.agent.eval/tx %)))
                         (when (integer? (:tx %)) (long (:tx %)))
                         (when (integer? (:db/id %))
                           (some-> (first (d/datoms bdb :eavt (:db/id %) :seon.agent.eval/turn)) :tx long))
                         0)
            prev-tx (if (seq prev-evs) (reduce max 0 (map eval-tx prev-evs)) 0)
            from-evals (mapcat extract-eval-edited-syms latest-evs)
            writes (when (and (valid-db? mdb) (not (identical? mdb bdb))) (ctouches/branch-write-eids mdb bdb nil))
            bdh (store/db-schema bdb) mdh (store/db-schema mdb)
            from-writes (when (and (map? writes) (contains? bdh :seon.code.var/name))
                          (keep (fn [[eid {:keys [max-tx]}]]
                                  (when (or (empty? evs) (> (long (or max-tx 0)) prev-tx))
                                    (:seon.code.var/name (d/pull bdb (quote [:seon.code.var/name]) eid))))
                                writes))
            pat (quote [* {:seon.code.var/output [:seon.schema/key]}])
            pull-var (fn [vsym]
                       (or (when (contains? bdh :seon.code.var/name)
                             (let [p (d/pull bdb pat [:seon.code.var/name vsym])] (when (:seon.code.var/name p) p)))
                           (when (and (valid-db? mdb) (not (identical? mdb bdb)) (contains? mdh :seon.code.var/name))
                             (let [p (d/pull mdb pat [:seon.code.var/name vsym])] (when (:seon.code.var/name p) p)))))]
        (->> (concat from-evals from-writes) (filter qualified-symbol?) distinct
             (filter #(hiccup-renderer-var? (pull-var %))) sort vec)))))

(defn format-preview-summary
  "Formats a compact `<derived-preview>` text summary from ephemeral derived preview frame maps."
  {:malli/schema [:=> {:examples [[[[{:var (quote demo/render-card) :source :db :label "render-card · 360px" :width 360 :height 180 :dom {:overflow-x? false}}]]
                                   "<derived-preview ephemeral=\"true\">\nAuto-derived preview of edited `:seon.ui/hiccup` renderer(s) (cached in memory, 0 DB writes):\n- demo/render-card (input=db) [render-card · 360px]: 360×180px · overflowX=false\n</derived-preview>"]]}
                  [:cat [:sequential :map]] [:maybe :string]]
   :cost/ticks 1000000 :cost/bytes 262144}
  [frames]
  (when (seq frames)
    (let [lines (mapv (fn [{:keys [var source label width height dom]}]
                        (let [w (if (number? width) (max 0 (long width)) 0)
                              h (if (number? height) (max 0 (long height)) 0)
                              ov-x? (boolean (when (map? dom) (or (:overflow-x? dom) (:overflowX dom))))
                              lbl (or (when (and (string? label) (not (str/blank? label))) (str/trim label))
                                      (str w "px"))]
                          (str "- " (or var :renderer)
                               (when source (str " (input=" (name source) ")"))
                               " [" lbl "]: " w "×" h "px · overflowX=" ov-x?)))
                      frames)]
      (str "<derived-preview ephemeral=\"true\">\n"
           "Auto-derived preview of edited `:seon.ui/hiccup` renderer(s) (cached in memory, 0 DB writes):\n"
           (str/join "\n" lines)
           "\n</derived-preview>"))))

(defn attach-preview-frames-to-messages
  "Attaches ephemeral derived preview frames + DOM telemetry to the trailing `user` message via `seon.model.wire/build-ephemeral-tail-msg`."
  {:malli/schema [:=> {:examples [[[[{:role "system" :content "S"} {:role "user" :content "Tail"}]
                                    [{:mime "image/jpeg" :base64 "AA==" :label "c · 360px" :width 360 :height 120 :dom {:overflow-x? false} :var (quote demo/c) :source :db}]
                                    false]
                                   [{:role "system" :content "S"}
                                    {:role "user"
                                     :content "Tail\n\n<derived-preview ephemeral=\"true\">\nAuto-derived preview of edited `:seon.ui/hiccup` renderer(s) (cached in memory, 0 DB writes):\n- demo/c (input=db) [c · 360px]: 360×120px · overflowX=false\n</derived-preview>"}]]]}
                  [:cat [:sequential :map] [:maybe [:sequential :map]] :boolean] [:vector :map]]
   :cost/ticks 2000000 :cost/bytes 1048576}
  [messages preview-frames vision?]
  (let [msgs (vec messages)
        fs (when (sequential? preview-frames) (vec (filter map? preview-frames)))]
    (if (empty? fs)
      msgs
      (if-let [tail (mwire/build-ephemeral-tail-msg (format-preview-summary fs) nil fs (boolean vision?))]
        (if (and (seq msgs) (= "user" (:role (peek msgs))))
          (let [lm (peek msgs) bc (:content lm) nc (:content tail)
                to-parts #(cond (sequential? %) (vec %) (and (string? %) (not (str/blank? %))) [{:type "text" :text %}] :else [])
                mc (if (and (string? bc) (string? nc))
                     (if (str/blank? bc) nc (str bc "\n\n" nc))
                     (into (to-parts bc) (to-parts nc)))]
            (assoc msgs (dec (count msgs)) (assoc lm :content mc)))
          (conj msgs tail))
        msgs))))

(defn ^:seon.flow/edge render-derived-preview-card
  "Renders a safe Live Derived Preview Hiccup card for a `:seon.ui/hiccup` renderer var entity (showing live Hiccup, input source badge, and collapsible sample EDN, or a clean error notice)."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :map] [:vector :any]]
   :cost/ticks 35000000}
  [eff-db ent]
  (let [vsym (:seon.code.var/name ent)
        prev (eval-renderer-preview eff-db vsym {:var-ent ent})
        [src-cls src-lbl] (case (:source prev)
                            :db ["green" "live DB"]
                            :example ["blue" "Malli :example"]
                            :generated ["purple" "seed=42 generator"]
                            ["muted" (name (or (:source prev) :derived))])
        clip-s (fn [s n] (if (> (count s) n) (str (subs s 0 n) "…") s))]
    (if (:ok? prev)
      [:section {:id "insp-derived-preview" :class "card stack-sm"}
       [:div {:class "card-head"}
        [:div {:class "pill-row"}
         [:h2 "Live Derived Preview"]
         [:span {:class (str "badge " src-cls)} src-lbl]
         (when (:cached? prev) [:span {:class "badge muted"} "cached"])]
        [:span {:class "muted tiny mono"} (str vsym)]]
       [:div {:class "derived-preview-stage"
              :style {:padding "12px" :border-radius "8px"
                      :background "var(--base-950,#0d0d0c)"
                      :border "1px solid var(--base-800,#302e2b)"}}
        (:hiccup prev)]
       [:details {:class "activity-fold"}
        [:summary {:class "mono tiny"}
         (str "Sample Input EDN (" src-lbl " · #" (:input-hash prev) ")")]
        [:pre {:class "code-box tiny"} (clip-s (pr-str (:args prev)) 2400)]]]
      [:section {:id "insp-derived-preview" :class "card stack-sm"}
       [:div {:class "card-head"}
        [:div {:class "pill-row"}
         [:h2 "Live Derived Preview"]
         [:span {:class "badge red"} (name (or (:error prev) :error))]]
        [:span {:class "muted tiny mono"} (str vsym)]]
       [:div {:class "notice red mono tiny"}
        (str "Preview error: " (or (:message prev) (pr-str (:error prev))))]
       (when (seq (:args prev))
         [:details {:class "activity-fold"}
          [:summary {:class "mono tiny"} "Sample Input EDN"]
          [:pre {:class "code-box tiny"} (clip-s (pr-str (:args prev)) 2400)]])])))

(defn ^:seon.flow/edge capture-html-frames!
  "Captures multi-width WKWebView frames for `html` via fn, `:browser` chan, warm `/op`, or `seon.browser/render-hiccup-frames!`."
  {:malli/schema [:=> [:cat :any :string :map :map] :map]
   :cost/ticks 45000000}
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
          via-op (when-not (:direct? o)
                   (try
                     (let [resp (mwire/http-post! (str "http://127.0.0.1:" port "/op")
                                                  [["Content-Type" "application/edn"]]
                                                  (pr-str (assoc cap-opts :op :render-hiccup-frames :html html :url url)))]
                       (when (= 200 (:status resp)) (edn/read-string (:body resp))))
                     (catch Throwable _ nil)))]
      (if (and (map? via-op) (:ok? via-op) (seq (:frames via-op)))
        via-op
        (browser/render-hiccup-frames! url html cap-opts)))))

(defn ^:seon.flow/edge render-var-preview-frames!
  "Renders ephemeral multi-width preview frames for `var-sym` using `preview-lru` (0 DB writes) + warm `:browser`."
  {:malli/schema [:function
                  [:=> [:cat :any [:maybe :seon.host/db] :qualified-symbol] :map]
                  [:=> [:cat :any [:maybe :seon.host/db] :qualified-symbol [:maybe :map]] :map]]
   :cost/ticks 45000000}
  ([server-or-browser-fn db var-sym] (render-var-preview-frames! server-or-browser-fn db var-sym nil))
  ([server-or-browser-fn db var-sym opts]
   (let [o (if (map? opts) opts {})
         widths (vec (or (seq (:widths o)) [360 640]))
         theme (or (:theme o) :dark)
         ev (eval-renderer-preview db var-sym o)
         meta-m {:var var-sym :source (:source ev) :input-hash (:input-hash ev)
                 :form-hash (:form-hash ev) :widths widths :theme theme}]
     (if-not (:ok? ev)
       (assoc ev :frames [] :widths widths :theme theme :cached? false)
       (let [ck [var-sym (:form-hash ev) (:input-hash ev) widths theme]
             hit (when-not (:no-cache? o) (lru-peek ck))]
         (if (and (not (:no-cache? o)) (not (identical? ::miss hit)))
           (assoc meta-m :ok? true :frames hit :cached? true)
           (let [cap-opts (merge {:widths widths :theme theme :label-prefix (name var-sym)}
                                 (select-keys o [:height :quality :max-width :label-prefix :timeout-ms :settle-ms :binary-path]))
                 cap (capture-html-frames! server-or-browser-fn (:html ev) cap-opts o)
                 frames (if (and (map? cap) (:ok? cap) (seq (:frames cap)))
                          (mapv #(normalize-ephemeral-frame var-sym (:source ev) %) (:frames cap))
                          [])]
             (if (seq frames)
               (do (when-not (:no-cache? o) (lru-store! ck frames))
                   (assoc meta-m :ok? true :frames frames :cached? false))
               (assoc meta-m :ok? false :frames [] :cached? false
                      :error (or (:error cap) :render-preview-frames-failed))))))))))
