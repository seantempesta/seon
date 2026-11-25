(ns seon.render.entities.infra
  "Uniform pure `ns-map -> ns-map` multi-footprint Hiccup renderers for Seon infrastructure entities
   (`:seon.code.file/entity`, `:seon.git/entity`, `:seon.route/entity`) — each with a compact
   chip (~180px) and a richer card (~420px) footprint."
  (:require [clojure.string :as str]
            [seon.render.links :as rlinks]
            [seon.render.text :refer [nonblank one-line]]
            [seon.render.entities.prim :refer [chip box el]]))

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

(defn render-form-chip
  "Compact chip (~160px) for `:seon.code.form/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.code.form/entity :seon.code.form/entity]]]
                  [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 500000}
  [{{:seon.code.form/keys [key]} :seon.code.form/entity}]
  (chip 160 "ent-form" (rlinks/badge "blue" "form") (el :seon.code.form/key key (one-line (or key "(form)") 44))))

(defn render-form-card
  "Summary card (~360px) for `:seon.code.form/entity` with bounded source preview."
  {:malli/schema [:=> [:cat [:map [:seon.code.form/entity :seon.code.form/entity]]]
                  [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1200000}
  [{{:seon.code.form/keys [key ns lines chars src test?]} :seon.code.form/entity}]
  (let [ls (when (string? src) (vec (str/split-lines src)))
        n (or lines (count ls))
        preview (when (seq ls) (str/join "\n" (take 8 ls)))]
    (box "ent-card" 360 "ent-form"
         [(rlinks/badge (if test? "green" "blue") (if test? "test-form" "form"))
          (el :seon.code.form/key key (one-line (or key "(form)") 48))]
         (str (when n (str n " lines")) (when chars (str " · " chars " ch")))
         (rlinks/kv-dl [["ns" (when ns (el :seon.code.ns/name ns))]
                        ["key" (when key (el :seon.code.form/key key))]])
         (when preview [:pre {:class "code-box tiny"} [:code preview]])
         (when (and ls (> (count ls) 8))
           [:div {:class "muted tiny"} (str "… " (- (count ls) 8) " more lines")]))))

(defn render-form-full
  "Full detail card (~560px) for `:seon.code.form/entity` with metadata, deps, and full source."
  {:malli/schema [:=> [:cat [:map [:seon.code.form/entity :seon.code.form/entity]]]
                  [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1800000}
  [{{:seon.code.form/keys [key ns order lines chars hash deps src test?]} :seon.code.form/entity}]
  (let [ds (when (and (coll? deps) (not (map? deps))) (seq deps))]
    (box "ent-full" 560 "ent-form"
         [(rlinks/badge (if test? "green" "blue") (if test? "test-form" "form"))
          (el :seon.code.form/key key (str (or key "(form)")))]
         (when ns (rlinks/meta-link :seon.code.ns/name ns))
         (rlinks/kv-dl [["ns" (when ns (el :seon.code.ns/name ns))]
                        ["order" order]
                        ["lines" lines]
                        ["chars" chars]
                        ["hash" (when hash (short-sha hash))]])
         (when ds (rlinks/pill-list "deps:" ds :seon.code.var/name 12))
         (when (nonblank src) [:pre {:class "code-box ent-code tiny"} [:code (str src)]]))))

(defn render-message-chip
  "Compact chip (~180px) for `:seon.agent.message/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.agent.message/entity :seon.agent.message/entity]]]
                  [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 500000}
  [{{:seon.agent.message/keys [id from type body]} :seon.agent.message/entity}]
  (chip 180 "ent-message"
        (rlinks/badge "purple" (if (ident? type) (name type) "msg"))
        (el :seon.agent.message/id id (str (or from :user)))
        [:span {:class "muted tiny"} (one-line (or body "") 48)]))

(defn render-message-card
  "Full message card (~420px) for `:seon.agent.message/entity` preserving full body."
  {:malli/schema [:=> [:cat [:map [:seon.agent.message/entity :seon.agent.message/entity]]]
                  [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1200000}
  [{{:seon.agent.message/keys [id seq from to type body]} :seon.agent.message/entity}]
  (box "ent-card" 420 "ent-message"
       [(rlinks/badge "purple" (if (ident? type) (name type) "chat"))
        (if (qualified-keyword? from) (el :seon.agent/id from (str from)) [:span {:class "mono strong"} (str (or from :user))])
        [:span {:class "muted tiny"} "→"]
        (if (qualified-keyword? to) (el :seon.agent/id to (str to)) [:span {:class "mono strong"} (str (or to :agent))])]
       (when (integer? seq) (str "#" seq))
       (rlinks/kv-dl [["id" (when id (el :seon.agent.message/id id))]
                      ["type" (when type (if (ident? type) (name type) (str type)))]])
       [:div {:class "doc-box sm-copy"} (str (or body ""))]))