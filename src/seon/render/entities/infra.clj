(ns seon.render.entities.infra
  "Uniform pure `ns-map -> ns-map` multi-footprint Hiccup renderers for Seon infrastructure entities
   (`:seon.code.file/entity`, `:seon.git/entity`, `:seon.route/entity`) — each with a compact
   chip (~180px) and a richer card (~420px) footprint."
  (:require [clojure.string :as str]
            [seon.render.links :as rlinks]
            [seon.render.text :refer [nonblank one-line]]))

(defn- chip [min-w cls & xs]
  {:seon.ui/hiccup (into [:span {:class (str "ent-chip " cls) :data-min-width min-w}] (remove nil? xs))})

(defn- box [tag-cls min-w cls left right & body]
  {:seon.ui/hiccup
   (into [:div {:class (str "card " tag-cls " " cls) :data-min-width min-w}
          [:div {:class "card-head ent-head"}
           (into [:div {:class "pill-row"}] (remove nil? left))
           (cond (vector? right) right
                 (and (some? right) (not (str/blank? (str right)))) [:span {:class "muted tiny mono ent-meta"} (str right)]
                 :else nil)]]
         (remove nil? body))})

(defn- el
  ([a v] (rlinks/ent-link a v (str v) "mono"))
  ([a v lbl] (rlinks/ent-link a v lbl "mono strong")))

(defn render-file-chip
  "Compact chip (~180px) for `:seon.code.file/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.code.file/entity :seon.code.file/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 400000}
  [{{:seon.code.file/keys [path exec?]} :seon.code.file/entity}]
  (chip 180 "ent-file" (rlinks/badge "slate" "file")
        (el :seon.code.file/path path (one-line (or path "(unnamed)") 44))
        (when exec? (rlinks/badge "green" "exec"))))

(defn render-file-card
  "Rich card (~420px) for `:seon.code.file/entity` with a bounded head-of-file preview."
  {:malli/schema [:=> [:cat [:map [:seon.code.file/entity :seon.code.file/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 900000}
  [{{:seon.code.file/keys [path text exec?]} :seon.code.file/entity}]
  (let [lines (when (string? text) (vec (clojure.string/split-lines text)))
        n (count lines)
        preview (when (seq lines) (vec (take 12 lines)))]
    (box "ent-card" 420 "ent-file"
         [(rlinks/badge "slate" "file")
          (el :seon.code.file/path path (one-line (or path "(unnamed)") 48))
          (when exec? (rlinks/badge "green" "exec"))]
         (when n (str n " lines"))
         (rlinks/kv-dl [["path" (when path (el :seon.code.file/path path))]
                        ["lines" (when n n)]
                        ["exec?" (when exec? "true")]])
         (when (seq preview)
           [:pre {:class "code-block tiny"} (clojure.string/join "\n" preview)])
         (when (and n (> n (count preview)))
           [:div {:class "muted tiny"} (str "… " (- n (count preview)) " more lines")]))))

(defn- short-sha [commit] (when commit (let [s (str commit)] (subs s 0 (min 7 (count s))))))

(defn render-git-chip
  "Compact chip (~180px) for `:seon.git/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.git/entity :seon.git/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 400000}
  [{{:seon.git/keys [repo commit branch]} :seon.git/entity}]
  (chip 180 "ent-git" (rlinks/badge "slate" "git")
        (el :seon.git/repo repo (str repo))
        (rlinks/mut (str (or branch "main") (when commit (str "@" (short-sha commit)))))))

(defn render-git-card
  "Rich card (~420px) for `:seon.git/entity` with repo, branch and full commit sha."
  {:malli/schema [:=> [:cat [:map [:seon.git/entity :seon.git/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 900000}
  [{{:seon.git/keys [repo commit branch]} :seon.git/entity}]
  (box "ent-card" 420 "ent-git"
       [(rlinks/badge "slate" "git")
        (el :seon.git/repo repo (str repo))
        (rlinks/mut (or branch "main"))]
       (short-sha commit)
       (rlinks/kv-dl [["repo" (el :seon.git/repo repo (str repo))]
                      ["branch" (rlinks/mut (or branch "main"))]
                      ["commit" (when commit [:span {:class "mono tiny"} (str commit)])]])))

(defn render-route-chip
  "Compact chip (~180px) for `:seon.route/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.route/entity :seon.route/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 400000}
  [{{:seon.route/keys [name method pattern]} :seon.route/entity}]
  (chip 180 "ent-route" (rlinks/badge "teal" "route")
        (when method (rlinks/mut (str/upper-case (str (if (keyword? method) (name method) method)))))
        (el :seon.route/name name (one-line (or pattern (str name)) 40))))

(defn render-route-card
  "Rich card (~440px) for `:seon.route/entity` with pattern, method, handler and doc."
  {:malli/schema [:=> [:cat [:map [:seon.route/entity :seon.route/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 900000}
  [{{:seon.route/keys [name pattern method handler doc]} :seon.route/entity}]
  (box "ent-card" 440 "ent-route"
       [(rlinks/badge "teal" "route")
        (el :seon.route/name name (str (or name pattern)))
        (when method (rlinks/mut (str/upper-case (str (if (keyword? method) (name method) method)))))]
       (when pattern [:span {:class "mono tiny"} pattern])
       (rlinks/kv-dl [["name" (el :seon.route/name name (str name))]
                      ["pattern" (when pattern [:span {:class "mono tiny"} (str pattern)])]
                      ["method" (when method (str method))]
                      ["handler" (when (symbol? handler) [:span {:class "mono tiny"} (str handler)])]])
       (when (nonblank doc) [:div {:class "doc-box sm-copy"} [:p (str doc)]])))
