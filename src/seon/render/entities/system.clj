(ns seon.render.entities.system
  "Uniform pure `ns-map -> ns-map` chip (~180px) & card (~420px) Hiccup renderers for operational
   runtime entities (`:seon.config/entity`, `:seon.test.run/entity`, `:seon.user/entity`, `:seon.vector/entity`)."
  (:require [seon.render.links :as rlinks]
            [seon.render.text :refer [one-line]]
            [seon.render.entities.prim :as prim :refer [chip]]))

(defn- box [min-w cls left right & body]
  (apply prim/box "ent-card" min-w cls left right body))

(defn render-config-chip
  "Compact chip (~180px) for `:seon.config/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.config/entity :seon.config/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 400000}
  [{{:seon.config/keys [key ai-model]} :seon.config/entity}]
  (chip 180 "ent-config" (rlinks/badge "slate" "config")
        (rlinks/ent-link :seon.config/key key (str (or key :seon.config/root)) "mono strong")
        (when ai-model (rlinks/mut (one-line (str ai-model) 28)))))

(defn render-config-card
  "Detail card (~420px) for `:seon.config/entity` showing model, limits, and active knobs."
  {:malli/schema [:=> [:cat [:map [:seon.config/entity :seon.config/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 900000}
  [{{:seon.config/keys [key ai-model max-tool-turns max-os-procs eval-bytes auto-merge? semantic-min-score] :as cfg} :seon.config/entity}]
  (let [n (count (dissoc cfg :db/id :seon.config/key))]
    (box 420 "ent-config"
         [(rlinks/badge "slate" "config")
          (rlinks/ent-link :seon.config/key key (str (or key :seon.config/root)) "mono strong")]
         (str n " knobs")
         (rlinks/kv-dl [["model" ai-model]
                        ["max-tool-turns" max-tool-turns]
                        ["max-os-procs" max-os-procs]
                        ["eval-bytes" (when (number? eval-bytes) (str (quot (long eval-bytes) 1048576) " MB"))]
                        ["semantic-min-score" semantic-min-score]
                        ["auto-merge?" (when (some? auto-merge?) (str auto-merge?))]]))))

(defn render-test-run-chip
  "Compact chip (~180px) for `:seon.test.run/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.test.run/entity :seon.test.run/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 400000}
  [{{:seon.test.run/keys [branch pass? ran cached]} :seon.test.run/entity}]
  (chip 180 "ent-test-run"
        (rlinks/badge (if pass? "green" "red") (if pass? "tests green" "tests fail"))
        (rlinks/ent-link :seon.test.run/branch branch (str (or branch :main)) "mono strong")
        (rlinks/mut (str (or ran 0) " ran · " (or cached 0) " cached"))))

(defn render-test-run-card
  "Detail card (~420px) for `:seon.test.run/entity` with pass status, counts, and failed digests."
  {:malli/schema [:=> [:cat [:map [:seon.test.run/entity :seon.test.run/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 900000}
  [{{:seon.test.run/keys [branch agent pass? ran cached failed-digests]} :seon.test.run/entity}]
  (let [fails (when (coll? failed-digests) (vec (take 6 failed-digests)))]
    (box 420 "ent-test-run"
         [(rlinks/badge (if pass? "green" "red") (if pass? "PASS" "FAIL"))
          (rlinks/ent-link :seon.test.run/branch branch (str (or branch :main)) "mono strong")]
         (str (+ (long (or ran 0)) (long (or cached 0))) " total")
         (rlinks/kv-dl [["branch" (when branch (rlinks/ent-link :seon.branch/name branch (str branch) "mono"))]
                        ["agent" (when agent (rlinks/ent-link :seon.agent/id agent (str agent) "mono"))]
                        ["ran" (long (or ran 0))]
                        ["cached" (long (or cached 0))]
                        ["failed" (count fails)]])
         (when (seq fails)
           (rlinks/pill-list "Failed digests:" fails :seon.test/digest 6)))))

(defn render-user-chip
  "Compact chip (~180px) for `:seon.user/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.user/entity :seon.user/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 400000}
  [{{:seon.user/keys [id view-mode theme]} :seon.user/entity}]
  (chip 180 "ent-user" (rlinks/badge "blue" "user")
        (rlinks/ent-link :seon.user/id id (str (or id :user/default)) "mono strong")
        (rlinks/mut (str (name (or view-mode :session)) " · " (name (or theme :dark))))))

(defn render-user-card
  "Detail card (~420px) for `:seon.user/entity` showing active view mode, viewport, and theme."
  {:malli/schema [:=> [:cat [:map [:seon.user/entity :seon.user/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 900000}
  [{{:seon.user/keys [id view-mode theme viewport-w viewport-h sidebar-open? expert-mode?]} :seon.user/entity}]
  (box 420 "ent-user"
       [(rlinks/badge "blue" "user")
        (rlinks/ent-link :seon.user/id id (str (or id :user/default)) "mono strong")]
       (when (and viewport-w viewport-h) (str viewport-w "×" viewport-h))
       (rlinks/kv-dl [["mode" (name (or view-mode :session))]
                      ["theme" (name (or theme :dark))]
                      ["sidebar" (if (false? sidebar-open?) "closed" "open")]
                      ["expert-mode?" (str (boolean expert-mode?))]])))

(defn render-vector-chip
  "Compact chip (~180px) for `:seon.vector/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.vector/entity :seon.vector/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 400000}
  [{{:seon.vector/keys [of model]} :seon.vector/entity}]
  (chip 180 "ent-vector" (rlinks/badge "purple" "vec")
        [:span {:class "mono strong"} (str (or of "entity"))]
        (when model (rlinks/mut (one-line (str model) 24)))))

(defn render-vector-card
  "Detail card (~420px) for `:seon.vector/entity` showing target entity, model, and digest."
  {:malli/schema [:=> [:cat [:map [:seon.vector/entity :seon.vector/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 900000}
  [{{:seon.vector/keys [of model text coarse f16]} :seon.vector/entity}]
  (let [dims (when (bytes? f16) (quot (alength ^bytes f16) 2))]
    (box 420 "ent-vector"
         [(rlinks/badge "purple" "vector")
          [:span {:class "mono strong"} (str (or of "entity"))]]
         (when dims (str dims " dims"))
         (rlinks/kv-dl [["model" model]
                        ["dims" dims]
                        ["coarse-bytes" (when (bytes? coarse) (alength ^bytes coarse))]
                        ["digest" (when (string? text) (one-line text 20))]]))))