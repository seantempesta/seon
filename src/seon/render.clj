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
  (:require [clojure.edn :as edn]
            [clojure.repl :as repl]
            [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]
            [datahike.reads :as reads]))

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
   Expands component vectors `[fn opts & children]` and wires `:on-click`/`:on-submit`/`:on-change`."
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
                    attrs (when has-attrs? (expand-event-attrs tag raw-attrs unit-id handlers counter))
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
         (render-value nil (with-meta v nil) opts)))

     (and (sequential? v) (>= (count v) 2) (every? map? v)
          (>= (count (apply set/intersection (map #(set (keys %)) (take 5 v)))) 2))
     (let [cols (vec (sort-by str (distinct (mapcat keys (take 10 v)))))]
       [:table {:class "data-table"}
        [:thead [:tr (for [c cols] [:th (str c)])]]
        [:tbody
         (for [row (take 50 v)]
           [:tr (for [c cols] [:td (render-value db (get row c))])])]])

     (map? v)
     [:dl {:class "kv-grid"}
      (for [[k val] (sort-by (comp str key) v)]
        (list [:dt (str k)] [:dd (render-value db val)]))]

     (or (vector? v) (set? v) (seq? v))
     (if (empty? v)
       [:span {:class "muted"} (pr-str v)]
       [:div {:class "pill-row"}
        (for [item (take 40 v)]
          [:span {:class "pill"} (pr-str item)])])

     :else
     [:span (str v)])))

(defn- resolve-ref-entity [db item]
  (when (and (vector? item) (= 2 (count item)) (qualified-keyword? (first item)))
    (let [[attr val] item]
      (case attr
        :seon.health/summary {:schema :seon.health/report :id (str "tile-health-" (sanitize-id val))
                              :source :pinned :label "System Health" :ref item :val nil :health-summary? true}
        :seon.code.var/name  (when-let [e (first (d/datoms db :avet attr val))]
                               {:schema :seon.code.var/entity :id (str "tile-var-" (sanitize-id val))
                                :ref item :val (with-meta (d/pull db '[*] (:e e)) {:seon/schema :seon.code.var/entity})})
        :seon.code.ns/name   (when-let [e (first (d/datoms db :avet attr val))]
                               {:schema :seon.code.ns/entity :id (str "tile-ns-" (sanitize-id val))
                                :ref item :val (with-meta (d/pull db '[*] (:e e)) {:seon/schema :seon.code.ns/entity})})
        :seon.code.doc/id    (when-let [e (first (d/datoms db :avet attr val))]
                               {:schema :seon.code.doc/entity :id (str "tile-doc-" (sanitize-id val))
                                :ref item :val (with-meta (d/pull db '[*] (:e e)) {:seon/schema :seon.code.doc/entity})})
        :seon.schema/key     (when-let [e (first (d/datoms db :avet attr val))]
                               {:schema :seon.schema/entity :id (str "tile-schema-" (sanitize-id val))
                                :ref item :val (with-meta (d/pull db '[*] (:e e)) {:seon/schema :seon.schema/entity})})
        :seon.task/id        (when-let [e (first (d/datoms db :avet attr val))]
                               {:schema :seon.task/entity :id (str "tile-task-" (sanitize-id val))
                                :ref item :val (with-meta (d/pull db '[*] (:e e)) {:seon/schema :seon.task/entity})})
        :seon.agent/id       (when-let [e (first (d/datoms db :avet attr val))]
                               {:schema :seon.agent/entity :id (str "tile-agent-" (sanitize-id val))
                                :ref item :val (with-meta (d/pull db '[*] (:e e)) {:seon/schema :seon.agent/entity})})
        nil))))

(defn- safe-read-edn [^String s]
  (when (and (string? s) (not (str/blank? s)) (< (count s) 12000))
    (try (edn/read-string s) (catch Throwable _ nil))))

(defn discover-renderable-tiles
  "Single source of truth for BOTH Level 2's right-column live tiles AND the agent's model context!
   Auto-discovers every entity/value whose schema has a `:seon.render/hiccup` render across:
   1. `:seon.agent/pinned` refs (e.g. `[:seon.health/summary :main]`, `[:seon.code.doc/id ...]`)
   2. Walked agent refs (`:seon.agent/task`, `:seon.agent/scope` namespace, child agents)
   3. Recent evals (`evals`) whose `:seon.agent.eval/result-schema` has a schema render function.
   Options map (6th arg): `{:include-evals? true :include-children? true :task-db nil}`."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db :seon.host/db :map :any :any] [:vector :map]]
                  [:=> [:cat :seon.host/db :seon.host/db :map :any :any :map] [:vector :map]]]}
  ([main-db branch-db agent-ent evals health-fn]
   (discover-renderable-tiles main-db branch-db agent-ent evals health-fn {}))
  ([main-db branch-db agent-ent evals health-fn
    {:keys [include-evals? include-children? task-db]
     :or {include-evals? true include-children? true}}]
   (let [seen (volatile! #{})
         tiles (volatile! [])
         add! (fn [{:keys [id schema] :as tile}]
                (when (and id schema
                           (not (contains? @seen id))
                           (or (schema-render-sym branch-db schema)
                               (schema-render-sym main-db schema)))
                  (vswap! seen conj id)
                  (vswap! tiles conj tile)))]
     (when (:seon.agent/id agent-ent)
       (add! {:id (str "tile-ctx-" (sanitize-id (:seon.agent/id agent-ent)))
              :schema :seon.ctx/snapshot
              :source :ctx
              :val (with-meta (assoc (select-keys agent-ent
                                                  [:seon.agent/id :seon.agent/branch :seon.agent/role
                                                   :seon.agent/scope :seon.agent/goal :seon.agent/render-fn
                                                   :seon.agent/pinned :seon.agent/stage :seon.agent/canvas
                                                   :seon.agent/memory :seon.agent/context-prefs])
                                     :seon.ctx/tx (:max-tx branch-db))
                     {:seon/schema :seon.ctx/snapshot})}))
     (let [read-main? (or (= :main (:seon.agent/read-branch agent-ent))
                          (:seon.agent/router? agent-ent)
                          (= :coordinator (:seon.agent/role agent-ent)))
           code-db (if read-main? main-db branch-db)]
       (doseq [p (:seon.agent/pinned agent-ent)]
         (when-let [t (resolve-ref-entity code-db p)]
           (add! (cond-> (assoc t :source :pinned)
                   (:health-summary? t) (assoc :val (health-fn (if (= :main (second p)) main-db branch-db)))))))
       (when-let [tid (:seon.agent/task agent-ent)]
         (when-let [t (or (resolve-ref-entity branch-db [:seon.task/id tid])
                          (when task-db (resolve-ref-entity task-db [:seon.task/id tid])))]
           (add! (assoc t :source :walk))))
       (when-let [scope (:seon.agent/scope agent-ent)]
         (when-let [t (resolve-ref-entity code-db [:seon.code.ns/name scope])]
           (add! (assoc t :source :walk))))
       (doseq [extra-ns (get-in agent-ent [:seon.agent/context-prefs :extra-namespaces])]
         (when-let [t (resolve-ref-entity code-db [:seon.code.ns/name extra-ns])]
           (add! (assoc t :source :tuned)))))
     (when include-children?
       (doseq [dt (d/datoms main-db :aevt :seon.agent/id)
               :let [child (d/pull main-db '[*] (:e dt))]
               :when (and (= (:seon.agent/id agent-ent) (:seon.agent/parent child))
                          (not (:seon.agent/archived? child)))]
         (add! {:id (str "tile-agent-" (sanitize-id (:seon.agent/id child)))
                :schema :seon.agent/entity
                :source :child
                :ref [:seon.agent/id (:seon.agent/id child)]
                :val (with-meta child {:seon/schema :seon.agent/entity})})))
     (when include-evals?
       (doseq [ev (reverse (take-last 16 evals))
               :let [rschema (:seon.agent.eval/result-schema ev)
                     handle (:seon.agent.eval/handle ev)]
               :when (and rschema (schema-render-sym branch-db rschema))]
         (let [parsed (safe-read-edn (:seon.agent.eval/value ev))]
           (cond
             (and (vector? parsed) (= 2 (count parsed)) (qualified-keyword? (first parsed)))
             (when-let [t (resolve-ref-entity branch-db parsed)]
               (add! (assoc t :source :eval :handle handle :eval-turn (:seon.agent.eval/turn ev))))

             (map? parsed)
             (let [tid (str "tile-eval-" (sanitize-id (or handle (:seon.agent.eval/id ev))))]
               (add! {:id tid
                      :schema rschema
                      :source :eval
                      :handle handle
                      :eval-turn (:seon.agent.eval/turn ev)
                      :val (with-meta parsed {:seon/schema rschema})}))))))
     @tiles)))

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
                           (let [[raw-hiccup rs] (reads/with-reads #(apply comp-fn db args))
                                 hiccup (ensure-root-id raw-hiccup id)
                                 {:keys [html handlers]} (hiccup->html-with-handlers hiccup id)]
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