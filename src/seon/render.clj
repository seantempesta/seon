(ns seon.render
  "Unified Reagent-style Hiccup renderer, incremental `datahike.reads` kept walk (> 60 fps!),
   and single-source Web (Datastar HTML) + Agent (compact Markdown text) projection:
   1. Components are plain Clojure functions `[comp-fn opts & children]`.
   2. Schemas attach renders via `{:seon.render/hiccup ns/fn}` (looked up in O(1)); values without
      one render cleanly as structured data (`<dl>`, `<table>`, `<pre><code>`).
   3. Standard Reagent event attributes (`:on-click`, `:on-submit`, `:on-change`) accept ALL FOUR
      Clojure idioms seamlessly — zero custom DSL required:
      - Reagent closure:        `{:on-click #(seon.web/select-tab! :health)}`
      - Reagent/re-frame vec:   `{:on-click [seon.web/select-tab! :health]}` (no quote needed!)
      - Bare named fn / var:    `{:on-click seon.web/power-loss-reboot!}` (no quote needed!)
      - Quoted call form:       `{:on-click '(seon.web/select-tab! :health)}`
   4. `step-walk` tracks `datahike.reads/reads-index` per mounted unit: untouched panels do ZERO
      work on commits/streams, emitting targeted Datastar `datastar-patch-elements` fragments in < 0.2 ms."
  (:require [clojure.repl :as repl]
            [clojure.string :as str]
            [datahike.api :as d]
            [datahike.reads :as reads]
            [seon.rule.context :as rctx]
            [seon.render.print :as sprint]
            [seon.render.breadcrumbs :as crumbs]
            [seon.render.print.schema :as pschema]))

(defn esc [s]
  (let [^String s (str s)
        n (.length s)]
    (loop [i 0]
      (cond
        (= i n) s
        (let [c (.charAt s i)] (or (= c \&) (= c \<) (= c \>) (= c \") (= c \')))
        (let [sb (StringBuilder. (+ n 16))]
          (.append sb s 0 i)
          (loop [j i]
            (if (= j n)
              (.toString sb)
              (let [c (.charAt s j)]
                (case c
                  \& (.append sb "&amp;")
                  \< (.append sb "&lt;")
                  \> (.append sb "&gt;")
                  \" (.append sb "&quot;")
                  \' (.append sb "&#39;")
                  (.append sb c))
                (recur (inc j))))))
        :else (recur (inc i))))))

(defn url-enc [^String s]
  (java.net.URLEncoder/encode (str s) "UTF-8"))

(defn raw-html [s] {::raw (str s)})

(defn fn->qualified-sym
  "Extracts the qualified Clojure symbol of a named top-level function or Var (no quote needed!).
   Returns nil for anonymous closures (`#(...)` / `(fn [...] ...)`)."
  [f]
  (cond
    (var? f) (symbol f)
    (qualified-symbol? f) f
    (fn? f)
    (let [cname (.getName (class f))]
      (when (and (not (str/starts-with? cname "clojure.lang.AFunction$"))
                 (not (re-find #"\$fn__\d+" cname))
                 (str/includes? cname "$"))
        (let [dem (repl/demunge cname)
              idx (.indexOf dem "/")]
          (when (pos? idx)
            (let [sym (symbol (subs dem 0 idx) (subs dem (inc idx)))]
              (when (resolve sym) sym))))))
    :else nil))

(def ^:private event-attr->datastar
  {:on-click  {:ds "data-on:click"  :form? false}
   :on-submit {:ds "data-on:submit" :form? true}
   :on-change {:ds "data-on:change" :form? false}})

(defn- normalize-handler->action
  "Turns any Reagent/Clojure event handler value into `{:action str :closure [key fn]}`:
   - Anonymous closure `#(...)` -> registered in walk `:handlers` under deterministic key `hkey`.
   - `[fn-or-var-or-sym & args]`, bare named `fn`/`var`, or `'(ns/fn ...)` -> stateless `/call?form=...`."
  [handler-val form? hkey]
  (let [post-expr (fn [url]
                    (if form?
                      (str "evt.preventDefault(); const _f = evt.currentTarget || evt.target; @post('" url "', {contentType: 'form'}); setTimeout(() => _f && _f.reset && _f.reset(), 0)")
                      (str "@post('" url "')")))]
    (cond
      ;; 1. Quoted call form `'(ns/fn arg1 ...)`
      (and (seq? handler-val) (qualified-symbol? (first handler-val)))
      {:action (post-expr (str "/call?form=" (url-enc (pr-str handler-val))))}

      ;; 2. Reagent/re-frame vector `[fn-or-var-or-sym arg1 ...]` (no quote needed!)
      (and (vector? handler-val) (seq handler-val))
      (if-let [sym (fn->qualified-sym (first handler-val))]
        (let [call-form (apply list sym (rest handler-val))]
          {:action (post-expr (str "/call?form=" (url-enc (pr-str call-form))))})
        (when (fn? (first handler-val))
          (let [f (first handler-val)
                args (vec (rest handler-val))]
            {:action (post-expr (str "/call?h=" (url-enc hkey)))
             :closure [hkey (fn [ctx-arg]
                              (if (seq args)
                                (apply f ctx-arg args)
                                (f ctx-arg)))]})))

      ;; 3. Bare named function or Var (`seon.web/power-loss-reboot!` or `#'...`)
      (fn->qualified-sym handler-val)
      {:action (post-expr (str "/call?form=" (url-enc (pr-str (list (fn->qualified-sym handler-val))))))}

      ;; 4. Anonymous Reagent closure `#(...)` or `(fn [fields] ...)`!
      (fn? handler-val)
      {:action (post-expr (str "/call?h=" (url-enc hkey)))
       :closure [hkey handler-val]}

      ;; 5. Plain JS/Datastar string passthrough
      (string? handler-val)
      {:action handler-val}

      :else nil)))

(def ^:private void-tags
  #{"area" "base" "br" "col" "embed" "hr" "img" "input" "link" "meta" "param" "source" "track" "wbr"})

(defn- expand-event-attrs [tag attrs unit-id handler-acc counter-vol]
  (let [;; Back-compat for `:seon/call` alongside Reagent `:on-click` / `:on-submit` / `:on-change`
        attrs1 (if-let [c (:seon/call attrs)]
                 (assoc (dissoc attrs :seon/call)
                        (if (= tag "form") :on-submit :on-click)
                        c)
                 attrs)]
    (reduce-kv
     (fn [acc k v]
       (if-let [{:keys [ds form?]} (get event-attr->datastar k)]
         (let [idx (vswap! counter-vol inc)
               hkey (str (or (:id attrs1) unit-id "u") ":" (name k) ":" idx)
               {:keys [action closure]} (normalize-handler->action v (or form? (= tag "form")) hkey)]
           (when closure
             (vswap! handler-acc assoc (first closure) (second closure)))
           (if action
             (assoc acc (keyword ds) action)
             acc))
         (if (= k :key)
           acc
           (assoc acc k v))))
     {}
     attrs1)))

(defn style-map->css
  "Converts a Clojure `:style` map (e.g. `{:color \"red\" :font-size \"20px\"}`) into a valid CSS declaration string."
  [m]
  (str/join ";"
            (keep (fn [[k v]]
                    (when (and (some? k) (some? v))
                      (let [prop (name k)
                            val-s (cond
                                    (ratio? v) (str (/ (Math/round (* (double v) 1000.0)) 1000.0))
                                    (keyword? v) (name v)
                                    :else (str v))]
                        (str prop ":" val-s))))
                  m)))

(defn hiccup->html-with-handlers
  "Serializes Reagent-style Hiccup into `{:html str :handlers {hkey fn}}`.
   Expands component vectors `[fn opts & children]`, wires `:on-click`/`:on-submit`/`:on-change`,
   and automatically attaches Datastar `data-preserve-attr=\"open\"` to `<details>` elements
   (unless `:data-preserve-attr false`) so disclosure state survives live SSE morphing natively."
  {:malli/schema [:function
                  [:=> [:cat :any] [:map [:html :string] [:handlers :map]]]
                  [:=> [:cat :any :string] [:map [:html :string] [:handlers :map]]]]
   :cost/ticks 5000000
   :cost/bytes 16777216}
  ([node] (hiccup->html-with-handlers node "root"))
  ([node unit-id]
   (let [sb (StringBuilder. 512)
         handlers (volatile! {})
         counter (volatile! 0)]
     ((fn write! [n]
        (cond
          (or (nil? n) (false? n)) nil
          (and (map? n) (contains? n ::raw)) (.append sb ^String (::raw n))
          (map? n) (.append sb (esc (pr-str n)))
          (or (string? n) (number? n) (keyword? n) (symbol? n) (boolean? n)) (.append sb (esc (str n)))
          (seq? n) (doseq [item n] (write! item))
          (vector? n)
          (let [hd (first n)]
            (cond
              (and (not (keyword? hd)) (not (symbol? hd)) (or (fn? hd) (var? hd)))
              (write! (apply hd (rest n)))

              ;; Vector-of-elements fragment (e.g. from `(mapv (fn [...] [:div ...]) xs)`)
              (not (or (keyword? hd) (symbol? hd) (string? hd)))
              (doseq [item n] (write! item))

              :else
              (let [tag (name hd)
                    has-attrs? (and (map? (second n)) (not (contains? (second n) ::raw)))
                    raw-attrs (when has-attrs? (second n))
                    attrs0 (if (= tag "details")
                             (let [pa (:data-preserve-attr raw-attrs)]
                               (cond
                                 (false? pa) (dissoc raw-attrs :data-preserve-attr)
                                 (nil? pa) (assoc (or raw-attrs {}) :data-preserve-attr "open")
                                 (and (string? pa) (not (re-find #"(?:^|\s)open(?:\s|$)" pa)))
                                 (assoc raw-attrs :data-preserve-attr (str pa " open"))
                                 :else raw-attrs))
                             raw-attrs)
                    attrs (when (seq attrs0) (expand-event-attrs tag attrs0 unit-id handlers counter))
                    children (if has-attrs? (drop 2 n) (rest n))]
                (.append sb "<")
                (.append sb tag)
                (when (seq attrs)
                  (doseq [[k v] (sort-by (comp str key) attrs)
                          :when (and (some? v) (not (false? v)))]
                    (let [aname (if (namespace k) (str (namespace k) ":" (name k)) (name k))]
                      (.append sb " ")
                      (.append sb aname)
                      (when-not (true? v)
                        (.append sb "=\"")
                        (.append sb (esc (cond
                                         (and (= k :style) (map? v)) (style-map->css v)
                                         (ratio? v) (str (/ (Math/round (* (double v) 1000.0)) 1000.0))
                                         (keyword? v) (name v)
                                         :else v)))
                        (.append sb "\"")))))
                (.append sb ">")
                (when-not (contains? void-tags tag)
                  (doseq [c children] (write! c))
                  (.append sb "</")
                  (.append sb tag)
                  (.append sb ">")))))
          :else (.append sb (esc (pr-str n)))))
      node)
     {:html (.toString sb)
      :handlers @handlers})))

(defn hiccup->html [node]
  (:html (hiccup->html-with-handlers node "root")))

(defn hiccup->text
  "Projects any Reagent-style Hiccup tree into crisp compact Markdown for an agent's LLM context.
   Zero separate model-render functions — one Hiccup component feeds both Web and Agent!"
  [node]
  (let [sb (StringBuilder. 256)]
    ((fn walk! [n]
       (cond
         (or (nil? n) (false? n)) nil
         (and (map? n) (contains? n ::raw)) nil
         (or (string? n) (number? n) (keyword? n) (symbol? n) (boolean? n)) (.append sb (str n))
         (seq? n) (doseq [x n] (walk! x))
         (vector? n)
         (let [hd (first n)]
           (cond
             (and (not (keyword? hd)) (not (symbol? hd)) (or (fn? hd) (var? hd)))
             (walk! (apply hd (rest n)))

             (not (or (keyword? hd) (symbol? hd) (string? hd)))
             (doseq [item n] (walk! item))

             :else
             (let [tag (name hd)
                   has-attrs? (and (map? (second n)) (not (contains? (second n) ::raw)))
                   children (if has-attrs? (drop 2 n) (rest n))]
               (case tag
                 ("script" "style" "svg" "button" "form" "input" "textarea" "select") nil
                 ("h1" "h2" "h3") (do (.append sb "\n## ") (doseq [c children] (walk! c)) (.append sb "\n"))
                 ("p" "div" "section" "header" "main") (do (doseq [c children] (walk! c)) (.append sb "\n"))
                 ("li") (do (.append sb "- ") (doseq [c children] (walk! c)) (.append sb "\n"))
                 ("tr") (do (.append sb "| ") (doseq [c children] (walk! c) (.append sb " | ")) (.append sb "\n"))
                 ("dt") (do (.append sb "- ") (doseq [c children] (walk! c)) (.append sb ": "))
                 ("dd") (do (doseq [c children] (walk! c)) (.append sb "\n"))
                 ("pre") (do (.append sb "\n```clojure\n") (doseq [c children] (walk! c)) (.append sb "\n```\n"))
                 ("span" "strong" "code" "em" "del")
                 (let [before (.length sb)]
                   (doseq [c children] (walk! c))
                   (when (> (.length sb) before)
                     (.append sb " ")))
                 (doseq [c children] (walk! c))))))
         :else (.append sb (pr-str n))))
     node)
    (-> (.toString sb)
        (str/replace #"[ \t]+\n" "\n")
        (str/replace #" {2,}" " ")
        (str/replace #"\n{3,}" "\n\n")
        str/trim)))

(defn schema-render-sym
  "Looks up the `:seon.render/hiccup` (or `:seon.schema/render`) component symbol for `schema-key` in `db`."
  [db schema-key]
  (when (and db schema-key)
    (let [ent (d/pull db '[:seon.schema/render :seon.schema/form] [:seon.schema/key schema-key])]
      (or (:seon.schema/render ent)
          (let [form (:seon.schema/form ent)]
            (when (and (vector? form) (map? (second form)))
              (:seon.render/hiccup (second form))))))))

(defn sanitize-id [s]
  (-> (str s)
      (str/replace #"[^A-Za-z0-9_-]+" "-")
      (str/replace #"^-+|-+$" "")))

(defn ensure-root-id
  "Auto-injects deterministic `:id` onto the root Hiccup element if the component author omitted `:id`
   so Datastar Idiomorph always morphs stably without manual `:id` boilerplate."
  [node id]
  (if (and id (vector? node) (keyword? (first node)))
    (let [[tag & more] node
          has-attrs? (map? (first more))
          attrs (if has-attrs? (first more) {})
          children (if has-attrs? (rest more) more)]
      (if (:id attrs)
        node
        (into [tag (assoc attrs :id (str id))] children)))
    node))

(defn render-value
  "Renders any Clojure value `v`:
   1. If `v`'s `:seon/schema` metadata names a schema with `:seon.render/hiccup` (in `db` or registry),
      calls that Reagent component `[rfn {:db db} v]`.
   2. Otherwise renders cleanly as structure (`<table>`, `<dl>`, `<pre><code>`, pills)."
  {:malli/schema [:function [:=> [:cat [:maybe :seon.host/db] :any] :any] [:=> [:cat [:maybe :seon.host/db] :any :map] :any]], :cost/ticks 10000000}
  ([db v] (render-value db v {}))
  ([db v opts]
   (cond
     (nil? v) [:span {:class "muted"} "nil"]

     (and (map? v) (:seon/code v))
     [:pre {:class "code-box"} [:code (:seon/code v)]]

     (and db (instance? clojure.lang.IObj v) (:seon/schema (meta v)))
     (let [sk (:seon/schema (meta v))
           rsym (or (schema-render-sym db sk)
                    (when-let [mdb (:main-db opts)] (schema-render-sym mdb sk)))
           rfn (when rsym
                 (or (try (resolve rsym) (catch Throwable _ nil))
                     (try (requiring-resolve rsym) (catch Throwable _ nil))))]
       (if rfn
         (try (rfn (assoc opts :db db :schema sk) v)
              (catch Throwable ex
                [:div {:class "card notice red"}
                 [:strong (str "Render fault in " rsym ": ")]
                 [:span {:class "mono"} (or (.getMessage ex) (str ex))]]))
         (sprint/format-value-hiccup db v opts)))

     :else
     (sprint/format-value-hiccup db v opts))))

(defn discover-renderable-tiles
"Single source of truth for BOTH Workspace live tiles AND the agent's model context.
 Delegates 100% to the datom-native rule engine (`seon.rule.context/discover-tiles`)."
{:malli/schema [:function
                [:=> [:cat :seon.host/db :seon.host/db :map :any :any] [:vector :map]]
                [:=> [:cat :seon.host/db :seon.host/db :map :any :any :map] [:vector :map]]]}
([main-db branch-db agent-ent evals health-fn]
 (rctx/discover-tiles main-db branch-db agent-ent evals health-fn {}))
([main-db branch-db agent-ent evals health-fn opts]
 (rctx/discover-tiles main-db branch-db agent-ent evals health-fn opts)))

(defn datastar-patch-event
  ([html] (datastar-patch-event html nil nil))
  ([html selector mode]
   (let [s (if (string? html) html (hiccup->html html))]
     (str "event: datastar-patch-elements\n"
          (when selector (str "data: selector " selector "\n"))
          (when mode (str "data: mode " mode "\n"))
          (apply str (for [line (str/split-lines s) :when (not (str/blank? line))]
                       (str "data: elements " line "\n")))
          "\n"))))

(defn empty-walk
  "Initial empty incremental render walk value (held inside `:web` Flow proc state — zero atoms!)."
  []
  {:units {} :handlers {} :order []})

(defn step-walk
  "Advances kept `walk` across `unit-specs` (`[{:id \"...\" :comp f :args [...]}]`) given `db` and
   `tx-datoms` (nil forces check of all units; vector of datoms skips every unit whose
   `datahike.reads/reads-index` wasn't touched AND whose `[comp args]` didn't change!).
   Returns `{:walk walk' :patches [sse-str ...] :rerendered n :skipped m}`."
  {:malli/schema [:=> [:cat :map :seon.host/db [:maybe [:sequential :any]] [:vector :map]] :map]}
  [walk db tx-datoms unit-specs]
  (let [prev-units (:units walk)
        order (mapv :id unit-specs)]
    (reduce
     (fn [acc {:keys [id comp args]}]
       (let [prev (get prev-units id)
             comp-fn (if (var? comp) @comp comp)
             same-inputs? (and prev (= comp-fn (:comp prev)) (= args (:args prev)))
             db-clean? (and same-inputs?
                            (some? tx-datoms)
                            (not (reads/reads-touched? (:reads-idx prev) tx-datoms)))]
         (if db-clean?
           (-> acc
               (assoc-in [:walk :units id] prev)
               (update-in [:walk :handlers] merge (:handlers prev))
               (update :skipped inc))
           (let [outcome (try
                           (let [[{:keys [html handlers]} rs]
                               (reads/with-reads
                                 #(let [raw-hiccup (apply comp-fn db args)
                                        hiccup (ensure-root-id raw-hiccup id)]
                                    (hiccup->html-with-handlers hiccup id)))]
                           {:ok? true :html html :handlers handlers
                            :ridx (reads/reads-index rs) :reads-sample (vec (take 12 rs)) :reads-count (count rs)})
                           (catch Throwable ex
                             ;; Fault isolation: a broken render component NEVER crashes `:web` or sibling panels!
                             (let [err-hiccup [:div {:id id :class "card notice red"}
                                               [:strong (str "Render fault in #" id ": ")]
                                               [:span {:class "mono"} (or (.getMessage ex) (str ex))]]
                                   {:keys [html]} (hiccup->html-with-handlers err-hiccup id)]
                               {:ok? false :html html :handlers {} :ridx #{:any} :reads-sample [] :reads-count 0
                                :error {:unit id :error (or (.getMessage ex) (str ex))}})))
                 {:keys [html handlers ridx reads-sample reads-count error]} outcome
                 entry {:id id :comp comp-fn :args args :html html
                        :reads-idx ridx :reads-sample reads-sample :reads-count reads-count
                        :handlers handlers}
                 changed-bytes? (not= html (:html prev))]
             (cond-> (-> acc
                         (assoc-in [:walk :units id] entry)
                         (update-in [:walk :handlers] merge handlers)
                         (update :rerendered inc))
               error (update :render-errors conj error)
               changed-bytes? (update :patches conj (datastar-patch-event html)))))))
     {:walk {:units {} :handlers {} :order order}
      :patches []
      :render-errors []
      :rerendered 0
      :skipped 0}
     unit-specs)))

(defn compose-keyframe
  "Composes the full cached HTML of all mounted units in `walk` without re-running any component!"
  [walk]
  (apply str (keep #(get-in walk [:units % :html]) (:order walk))))

(defn render-tile-node
  "Renders a rule-projected tile node (honoring `:view-fn`, `:layout`, `:params`, `:tags`, `:why`, and
   recursively rendering `:children` attached via `:view/parent`) for both `:mode :human` and `:mode :agent`."
  {:malli/schema [:=> [:cat :seon.host/db :map :map] :any]
   :cost/ticks 10000000}
  [db tile opts]
  (let [eff-opts (merge opts
                      (select-keys tile [:layout :params :tags :why :badges :fired-rules :zone :priority :source :eid]))
      vsym (:view-fn tile)
      sk (:schema tile)
      rfn (pschema/resolve-sym vsym)
      main-h (if rfn
               (try (rfn (assoc eff-opts :db db :schema sk) (:val tile))
                    (catch Throwable ex
                      [:div {:class "card notice red"}
                       [:strong (str "Render fault in " vsym ": ")]
                       [:span {:class "mono"} (or (.getMessage ex) (str ex))]]))
               (render-value db (:val tile) eff-opts))
      crumb-h (when (and (get-in tile [:params :show-breadcrumbs?])
                         (qualified-keyword? sk)
                         (not (contains? #{:seon.ctx/snapshot :seon.task/lineage} sk)))
                (crumbs/render-breadcrumbs-hiccup db sk (:val tile)
                                                  (assoc eff-opts :main-db (:main-db opts)
                                                         :lookup-ref (:ref tile)
                                                         :suppress-pin? (= :pinned (:source tile)))))
      body-h (if crumb-h
               [:div {:class "tile-with-crumbs stack-xs"} main-h crumb-h]
               main-h)
      kids (:children tile)]
  (if (seq kids)
    [:div {:class "tile-tree-node" :data-eid (str (:eid tile))}
     body-h
     [:div {:class "tile-tree-children" :style "margin-left:14px;padding-left:10px;border-left:2px solid var(--border-subtle)"}
      (for [k kids]
        (render-tile-node db k opts))]]
    body-h)))

(defn render-entity-value
  "Universal fallback tile renderer for any schema without a custom `:seon.render/hiccup` component.
   Delegates directly to `render-value` -> 4-stage zero-clipping schema self-printer."
  {:malli/schema [:=> [:cat [:map [:db {:optional true} [:maybe :seon.host/db]] [:main-db {:optional true} [:maybe :seon.host/db]]] :any] :any], :cost/ticks 10000000}
  [opts val]
  (render-value (or (:db opts) (:main-db opts)) val opts))