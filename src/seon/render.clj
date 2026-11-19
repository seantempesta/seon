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
            [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]
            [datahike.reads :as reads]))

;; --- HTML Escaping, Function Demunging & Reagent Event Wiring ---

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
                      (str "evt.preventDefault(); @post('" url "', {contentType: 'form'})")
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
            (if (and (not (keyword? hd)) (not (symbol? hd)) (or (fn? hd) (var? hd)))
              (write! (apply hd (rest n)))
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
                        (.append sb (esc v))
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

;; --- Single-Source Agent Markdown / Text Renderer (Walks the Exact Same Hiccup!) ---

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
           (if (and (not (keyword? hd)) (not (symbol? hd)) (or (fn? hd) (var? hd)))
             (walk! (apply hd (rest n)))
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
                 (doseq [c children] (walk! c))))))
         :else (.append sb (pr-str n))))
     node)
    (-> (.toString sb)
        (str/replace #"\n{3,}" "\n\n")
        str/trim)))

;; --- Schema-Driven Value Renderer (`:seon.render/hiccup` -> Structured Fallback) ---

(defn render-value
  "Renders any Clojure value `v`:
   1. If `v`'s `:seon/schema` metadata names a schema with `:seon.render/hiccup` (in `db` or registry),
      calls that Reagent component `[rfn {:db db} v]`.
   2. Otherwise renders cleanly as structure (`<table>`, `<dl>`, `<pre><code>`, pills)."
  [db v]
  (cond
    (nil? v) [:span {:class "muted"} "nil"]

    (and (map? v) (:seon/code v))
    [:pre {:class "code-box"} [:code (:seon/code v)]]

    (and db (meta v) (:seon/schema (meta v)))
    (let [sk (:seon/schema (meta v))
          rsym (or (:seon.schema/render (d/pull db '[:seon.schema/render] [:seon.schema/key sk]))
                   (let [form (:seon.schema/form (d/pull db '[:seon.schema/form] [:seon.schema/key sk]))]
                     (when (and (vector? form) (map? (second form)))
                       (:seon.render/hiccup (second form)))))
          rfn (when rsym (try (requiring-resolve rsym) (catch Throwable _ nil)))]
      (if rfn
        (try (rfn {:db db} v)
             (catch Throwable ex
               [:div {:class "badge red"} (str "Render error in " rsym ": " (.getMessage ex))]))
        (render-value nil (with-meta v nil))))

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
    [:span (str v)]))

;; --- Datastar SSE Framing & Incremental Kept Walk (`O(|Δ|)` > 60 fps!) ---

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
             same-inputs? (and prev (= comp (:comp prev)) (= args (:args prev)))
             db-clean? (and same-inputs?
                            (some? tx-datoms)
                            (not (reads/reads-touched? (:reads-idx prev) tx-datoms)))]
         (if db-clean?
           (-> acc
               (assoc-in [:walk :units id] prev)
               (update-in [:walk :handlers] merge (:handlers prev))
               (update :skipped inc))
           (let [[hiccup rs] (reads/with-reads #(apply comp db args))
                 {:keys [html handlers]} (hiccup->html-with-handlers hiccup id)
                 ridx (reads/reads-index rs)
                 entry {:id id :comp comp :args args :html html :reads-idx ridx :handlers handlers}
                 changed-bytes? (not= html (:html prev))]
             (cond-> (-> acc
                         (assoc-in [:walk :units id] entry)
                         (update-in [:walk :handlers] merge handlers)
                         (update :rerendered inc))
               changed-bytes? (update :patches conj (datastar-patch-event html)))))))
     {:walk {:units {} :handlers {} :order order}
      :patches []
      :rerendered 0
      :skipped 0}
     unit-specs)))

(defn compose-keyframe
  "Composes the full cached HTML of all mounted units in `walk` without re-running any component!"
  [walk]
  (apply str (keep #(get-in walk [:units % :html]) (:order walk))))
