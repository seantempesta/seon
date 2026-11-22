(ns seon.render.entities
  "Uniform pure `ns-map -> ns-map` multi-level Hiccup renderers for Seon's core entities.
   Each renderer accepts a namespaced input map, returns `{:seon.ui/hiccup [...]}`, and tags
   the root element with integer `:data-min-width` for the Upgrade Ladder."
  (:require [clojure.string :as str]))

(defn- clip [x n]
  (let [s (if (nil? x) "" (str x))]
    (if (> (count s) n) (str (subs s 0 (max 1 (dec n))) "…") s)))

(defn- one-line [x n]
  (clip (-> (if (nil? x) "" (str x)) (str/replace #"\s+" " ") str/trim) n))

(defn- fmt-pr [x n]
  (clip (pr-str x) n))

(defn- kw-label [x fallback]
  (cond (ident? x) (name x) (some? x) (str x) :else fallback))

(defn- fmt-ms [x]
  (when (number? x)
    (let [d (double x)]
      (if (or (Double/isNaN d) (Double/isInfinite d))
        "—"
        (str (/ (Math/round (* d 10.0)) 10.0) " ms")))))

(defn- fmt-num [x]
  (when (number? x)
    (let [d (double x)]
      (cond (or (Double/isNaN d) (Double/isInfinite d)) "—"
            (integer? x) (str x)
            :else (str (/ (Math/round (* d 10.0)) 10.0))))))

(defn- seq-items [x]
  (when (and (coll? x) (not (map? x))) (seq x)))

(defn- nonblank [x]
  (when (and (string? x) (not (str/blank? x))) x))

(defn- badge [cls text]
  [:span {:class (str "badge " cls)} (str text)])

(defn- pill [text]
  [:span {:class "pill mono"} (str text)])

(defn- kv-dl [pairs]
  (let [rows (mapcat (fn [[k v]]
                       (when (and (some? v) (not (str/blank? (str v))))
                         [[:dt (str k)] [:dd (str v)]]))
                     pairs)]
    (when (seq rows) (into [:dl {:class "kv-grid"}] rows))))

(defn- code-block [txt]
  (when (and (some? txt) (not (str/blank? (str txt))))
    [:pre {:class "code-box ent-code tiny"} [:code (str txt)]]))

(defn- doc-box [x n]
  (when-let [s (nonblank x)] [:p {:class "doc-box sm-copy"} (clip s n)]))

(defn- pill-list [label xs limit]
  (when-let [items (seq-items xs)]
    (into [:div {:class "pill-row"} (when label [:span {:class "muted tiny"} label])]
          (map #(pill (str %)) (take limit items)))))

(defn- chip [min-w cls & xs]
  {:seon.ui/hiccup (into [:span {:class (str "ent-chip " cls) :data-min-width min-w}] (remove nil? xs))})

(defn- row [min-w cls & xs]
  {:seon.ui/hiccup (into [:div {:class (str "ent-row " cls) :data-min-width min-w}] (remove nil? xs))})

(defn- box [tag-cls min-w cls left right & body]
  {:seon.ui/hiccup
   (into [:div {:class (str "card " tag-cls " " cls) :data-min-width min-w}
          [:div {:class "card-head ent-head"}
           (into [:div {:class "pill-row"}] (remove nil? left))
           (when (and (some? right) (not (str/blank? (str right))))
             [:span {:class "muted tiny mono ent-meta"} (str right)])]]
         (remove nil? body))})

(defn- var-badge [{:seon.code.var/keys [test? macro? flow-edge? private?]}]
  (cond test? (badge "green" "test") macro? (badge "purple" "macro")
        flow-edge? (badge "amber" "edge") private? (badge "muted" "defn-")
        :else (badge "blue" "var")))

;; Var (:seon.code.var/entity)

(defn render-var-chip
  "Compact chip (~160px) for `:seon.code.var/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.code.var/entity :seon.code.var/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 500000}
  [{v :seon.code.var/entity}]
  (chip 160 "ent-var" (var-badge v) [:code {:class "mono strong"} (str (:seon.code.var/name v))]))

(defn render-var-sig
  "Medium signature card (~320px) for `:seon.code.var/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.code.var/entity :seon.code.var/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1000000}
  [{{:seon.code.var/keys [name ns doc arglists schema ticks-budget] :as v} :seon.code.var/entity}]
  (box "ent-card" 320 "ent-var"
       [(var-badge v) [:strong {:class "mono"} (str name)]]
       (or (when (seq-items arglists) (fmt-pr arglists 64)) (str ns))
       (when-let [d (nonblank doc)] [:p {:class "sm-copy"} (one-line d 160)])
       (when (some? schema) (code-block (fmt-pr schema 220)))
       (kv-dl [["ns" ns] ["ticks" (fmt-num ticks-budget)]])))

(defn render-var-full
  "Full detail card (~540px) for `:seon.code.var/entity` with optional `:seon.code.form/src`."
  {:malli/schema [:=> [:cat [:map [:seon.code.var/entity :seon.code.var/entity]
                             [:seon.code.form/src {:optional true} :seon.code.form/src]]]
                  [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1500000}
  [{{:seon.code.var/keys [name ns doc arglists schema form big-o ticks-budget bytes-budget refs ops private?] :as v}
    :seon.code.var/entity src :seon.code.form/src}]
  (let [rs (seq-items refs) os (seq-items ops)]
    (box "ent-full" 540 "ent-var"
         [(var-badge v) [:strong {:class "mono"} (str name)] (when private? (badge "muted" "private"))]
         (str ns)
         (doc-box doc 800)
         (kv-dl [["arglists" (when (seq-items arglists) (fmt-pr arglists 140))] ["form" form] ["big-o" big-o]
                 ["ticks-budget" (fmt-num ticks-budget)] ["bytes-budget" (fmt-num bytes-budget)]
                 ["refs" (when rs (str (count rs) " ref(s)"))] ["ops" (when os (str/join ", " (map str os)))]])
         (when (some? schema) (code-block (fmt-pr schema 600)))
         (when-let [s (nonblank src)] (code-block (clip s 4000))))))

;; Error (:seon.error/entity + optional :seon.fix/entity)

(defn- err-sev-cls [sev] (case sev :warning "amber" "red"))
(defn- err-st-cls [st] (case st :resolved "green" :suppressed "muted" "amber"))

(defn render-error-badge
  "Compact badge (~200px) for `:seon.error/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.error/entity :seon.error/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 500000}
  [{{:seon.error/keys [severity kind status message]} :seon.error/entity}]
  (chip 200 "ent-error"
        (badge (err-sev-cls severity) (kw-label severity "error"))
        [:code {:class "mono strong"} (str (or kind :error/unknown))]
        (badge (err-st-cls status) (kw-label status "open"))
        [:span {:class "muted tiny"} (one-line message 48)]))

(defn- render-nested-fix [{:seon.fix/keys [id error-class root-cause change-summary regression-test before-after] :as fix}]
  (when (map? fix)
    [:div {:class "ent-nested"}
     [:div {:class "pill-row"} (badge "green" "fix") [:strong {:class "mono tiny"} (str (or error-class id))]]
     (when (some? root-cause) [:p {:class "sm-copy"} (clip root-cause 260)])
     (kv-dl [["change" (clip change-summary 260)] ["regression-test" regression-test]
             ["before/after" (when (some? before-after) (clip before-after 180))]])]))

(defn render-error-card
  "Detail card (~440px) for `:seon.error/entity`, nesting optional `:seon.fix/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.error/entity :seon.error/entity]
                             [:seon.fix/entity {:optional true} :seon.fix/entity]]]
                  [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1500000}
  [{{:seon.error/keys [id kind severity source status message agent branch at var data]} :seon.error/entity
    fix :seon.fix/entity}]
  (let [msg (clip message 600)]
    (box "ent-card" 440 "ent-error"
         [(badge (err-sev-cls severity) (kw-label severity "error"))
          [:strong {:class "mono"} (str (or kind :error/unknown))]
          (badge "blue" (kw-label source "system"))
          (badge (err-st-cls status) (kw-label status "open"))]
         (str (or agent ""))
         [:div {:class (if (= :warning severity) "notice sm-copy" "notice red sm-copy")}
          (if (str/blank? msg) "(no message)" msg)]
         (kv-dl [["agent" agent] ["branch" branch] ["var" var] ["at" (fmt-num at)] ["id" id]])
         (when (and (map? data) (seq data)) (code-block (fmt-pr data 400)))
         (render-nested-fix fix))))

;; Task (:seon.task/entity)

(defn- task-badge [{:seon.task/keys [template? done?]}]
  (cond template? (badge "purple" "template") done? (badge "green" "done") :else (badge "blue" "task")))

(defn render-task-chip
  "Compact chip (~180px) for `:seon.task/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.task/entity :seon.task/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 500000}
  [{{:seon.task/keys [id title] :as t} :seon.task/entity}]
  (chip 180 "ent-task" (task-badge t) [:span {:class "strong"} (one-line (or title id) 44)]))

(defn render-task-card
  "Summary card (~360px) for `:seon.task/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.task/entity :seon.task/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1000000}
  [{{:seon.task/keys [id title scope owner needs tests summary plan] :as t} :seon.task/entity}]
  (let [ns' (seq-items needs) ts' (seq-items tests) snip (or (nonblank summary) (nonblank plan))]
    (box "ent-card" 360 "ent-task"
         [(task-badge t) [:strong (one-line (or title id) 90)]]
         (str id)
         [:div {:class "pill-row"}
          (when scope (pill (str "scope " scope))) (when owner (pill (str "owner " owner)))
          (when ns' (pill (str (count ns') " needs"))) (when ts' (pill (str (count ts') " tests")))]
         (when snip [:p {:class "sm-copy"} (one-line snip 200)]))))

(defn render-task-full
  "Full task card (~560px) for `:seon.task/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.task/entity :seon.task/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1500000}
  [{{:seon.task/keys [id title scope owner created-by template done? plan summary needs tests] :as t}
    :seon.task/entity}]
  (box "ent-full" 560 "ent-task"
       [(task-badge t) [:strong (str (or title id))]]
       (str id)
       (kv-dl [["id" id] ["scope" scope] ["owner" owner] ["created-by" created-by]
               ["template" template] ["done?" (str (boolean done?))]])
       (doc-box plan 1200)
       (when-let [s (nonblank summary)] [:p {:class "sm-copy muted"} (str "Summary: " (clip s 500))])
       (pill-list "needs:" needs 12)
       (pill-list "tests:" tests 12)))

;; Schema (:seon.schema/entity)

(defn render-schema-chip
  "Compact chip (~180px) for `:seon.schema/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.schema/entity :seon.schema/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 500000}
  [{{:seon.schema/keys [key type]} :seon.schema/entity}]
  (chip 180 "ent-schema" (badge "purple" (kw-label type "schema")) [:code {:class "mono strong"} (str key)]))

(defn render-schema-detail
  "Detail card (~460px) for `:seon.schema/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.schema/entity :seon.schema/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1200000}
  [{{:seon.schema/keys [key form doc render type keys optional-keys ref-targets]} :seon.schema/entity}]
  (let [ks (seq-items keys) oks (seq-items optional-keys) refs (seq-items ref-targets)]
    (box "ent-card" 460 "ent-schema"
         [(badge "purple" (kw-label type "schema")) [:strong {:class "mono"} (str key)]]
         (when render (str render))
         (doc-box doc 400)
         (kv-dl [["key" key] ["type" type] ["keys" (when ks (str (count ks) " required"))]
                 ["optional-keys" (when oks (str (count oks) " optional"))]
                 ["ref-targets" (when refs (str (count refs) " target(s)"))]])
         (code-block (fmt-pr form 900)))))

;; Doc (:seon.code.doc/entity)

(defn render-doc-snippet
  "Compact snippet (~240px) for `:seon.code.doc/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.code.doc/entity :seon.code.doc/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 500000}
  [{{:seon.code.doc/keys [id heading body]} :seon.code.doc/entity}]
  (row 240 "ent-doc" (badge "amber" "doc") [:strong (one-line (or heading id) 60)]
       [:span {:class "muted tiny"} (one-line body 96)]))

(defn render-doc-full
  "Full doc card (~520px) for `:seon.code.doc/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.code.doc/entity :seon.code.doc/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1200000}
  [{{:seon.code.doc/keys [id path order heading body references]} :seon.code.doc/entity}]
  (let [refs (seq-items references)]
    (box "ent-full" 520 "ent-doc"
         [(badge "amber" "doc") [:strong (str (or heading id))]]
         (str (or path id))
         (kv-dl [["id" id] ["path" path] ["order" (fmt-num order)]
                 ["references" (when refs (str (count refs) " linked ref(s)"))]])
         [:div {:class "doc-box sm-copy"} (clip body 2400)])))

;; Test Verdict (:seon.test/verdict)

(defn render-test-badge
  "Compact badge (~160px) for `:seon.test/verdict`."
  {:malli/schema [:=> [:cat [:map [:seon.test/verdict :seon.test/verdict]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 500000}
  [{{:seon.test/keys [pass? var ms]} :seon.test/verdict}]
  (chip 160 "ent-test" (badge (if pass? "green" "red") (if pass? "PASS" "FAIL"))
        [:code {:class "mono strong"} (str var)]
        (when-let [m (fmt-ms ms)] [:span {:class "muted tiny mono"} m])))

(defn render-test-card
  "Detail card (~380px) for `:seon.test/verdict`."
  {:malli/schema [:=> [:cat [:map [:seon.test/verdict :seon.test/verdict]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1000000}
  [{{:seon.test/keys [pass? var kind ms ticks bytes digest report]} :seon.test/verdict}]
  (box "ent-card" 380 "ent-test"
       [(badge (if pass? "green" "red") (if pass? "PASS" "FAIL"))
        (when kind (badge "blue" (kw-label kind "test")))
        [:strong {:class "mono"} (str var)]]
       (fmt-ms ms)
       (kv-dl [["var" var] ["duration" (fmt-ms ms)] ["ticks" (fmt-num ticks)]
               ["bytes" (fmt-num bytes)] ["digest" (when (some? digest) (clip digest 24))]])
       (when (some? report) (code-block (fmt-pr report 500)))))

;; Eval (:seon.agent.eval/entity)

(defn render-eval-row
  "Compact row (~220px) for `:seon.agent.eval/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.agent.eval/entity :seon.agent.eval/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 600000}
  [{{:seon.agent.eval/keys [turn handle intent code error ms]} :seon.agent.eval/entity}]
  (row 220 "ent-eval"
       (badge (if (nonblank error) "red" "purple") (str (or handle (str "t" turn))))
       [:span {:class "mono tiny"} (one-line (or (nonblank intent) code) 72)]
       (when-let [m (fmt-ms ms)] [:span {:class "muted tiny mono ent-meta"} m])))

(defn render-eval-card
  "Detail card (~480px) for `:seon.agent.eval/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.agent.eval/entity :seon.agent.eval/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1500000}
  [{{:seon.agent.eval/keys [agent turn handle result-schema intent code value out error ms ticks bytes
                            prompt-tokens cache-hit-tokens]} :seon.agent.eval/entity}]
  (let [err (nonblank error)]
    (box "ent-card" 480 "ent-eval"
         [(badge (if err "red" "purple") (str (or handle (str "turn " turn))))
          [:strong {:class "mono"} (str agent)]
          (when result-schema (badge "blue" (str result-schema)))]
         (fmt-ms ms)
         (when-let [i (nonblank intent)] [:p {:class "sm-copy strong"} (clip i 240)])
         (code-block (clip code 800))
         (if err
           [:div {:class "notice red mono tiny"} (clip err 500)]
           (when-let [v (nonblank value)] (code-block (clip v 600))))
         (when-let [o (nonblank out)] [:pre {:class "code-box tiny muted"} [:code (clip o 300)]])
         (kv-dl [["turn" (fmt-num turn)] ["ticks" (fmt-num ticks)] ["bytes" (fmt-num bytes)]
                 ["prompt-tokens" (fmt-num prompt-tokens)] ["cache-hit-tokens" (fmt-num cache-hit-tokens)]]))))

;; Namespace (:seon.code.ns/entity)

(defn render-ns-chip
  "Compact chip (~160px) for `:seon.code.ns/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.code.ns/entity :seon.code.ns/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 500000}
  [{{:seon.code.ns/keys [name]} :seon.code.ns/entity}]
  (chip 160 "ent-ns" (badge "blue" "ns") [:code {:class "mono strong"} (str name)]))

(defn render-ns-card
  "Summary card (~400px) for `:seon.code.ns/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.code.ns/entity :seon.code.ns/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1000000}
  [{{:seon.code.ns/keys [name path doc requires]} :seon.code.ns/entity}]
  (let [reqs (seq-items requires)]
    (box "ent-card" 400 "ent-ns"
         [(badge "blue" "ns") [:strong {:class "mono"} (str name)]]
         (str (or path ""))
         (doc-box doc 500)
         (kv-dl [["namespace" name] ["path" path] ["requires" (when reqs (str (count reqs) " ns"))]])
         (pill-list nil requires 14))))

;; Agent (:seon.agent/entity)

(defn- agent-state-label [{:seon.agent/keys [archived? paused? ready? waiting?]}]
  (cond archived? ["muted" "archived"] paused? ["muted" "paused"]
        ready? ["green" "ready"] waiting? ["amber" "waiting"] :else ["blue" "active"]))

(defn render-agent-row
  "Compact row (~200px) for `:seon.agent/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.agent/entity :seon.agent/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 500000}
  [{{:seon.agent/keys [id label role] :as ag} :seon.agent/entity}]
  (let [[scls slbl] (agent-state-label ag)]
    (row 200 "ent-agent" (badge "blue" (kw-label role "worker"))
         [:strong {:class "mono"} (str (or label id))] (badge scls slbl))))

(defn render-agent-card
  "Detail card (~440px) for `:seon.agent/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.agent/entity :seon.agent/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1200000}
  [{{:seon.agent/keys [id label role branch scope task parent goal pinned memory] :as ag} :seon.agent/entity}]
  (let [[scls slbl] (agent-state-label ag) pins (seq-items pinned) mem (seq-items memory)]
    (box "ent-card" 440 "ent-agent"
         [(badge "blue" (kw-label role "worker")) [:strong {:class "mono"} (str id)] (badge scls slbl)]
         (str (or branch :main))
         (when-let [g (nonblank goal)] [:p {:class "sm-copy"} (clip g 360)])
         (kv-dl [["label" label] ["branch" branch] ["scope" scope] ["task" task] ["parent" parent]
                 ["pinned" (when pins (str (count pins) " ref(s)"))]
                 ["memory" (when mem (str (count mem) " note(s)"))]]))))

;; Rule (:seon.rule/entity)

(defn render-rule-chip
  "Compact chip (~180px) for `:seon.rule/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.rule/entity :seon.rule/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 500000}
  [{{:seon.rule/keys [id stratum enabled?]} :seon.rule/entity}]
  (chip 180 "ent-rule" (badge (if enabled? "green" "muted") (str "s" stratum))
        [:code {:class "mono strong"} (str id)]))

(defn render-rule-detail
  "Detail card (~480px) for `:seon.rule/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.rule/entity :seon.rule/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1200000}
  [{{:seon.rule/keys [id doc category stratum enabled? target query assert]} :seon.rule/entity}]
  (box "ent-card" 480 "ent-rule"
       [(badge (if enabled? "green" "muted") (if enabled? "enabled" "disabled"))
        (badge "amber" (str "stratum " stratum))
        (when category (badge "blue" (kw-label category "rule")))
        [:strong {:class "mono"} (str id)]]
       (when target (str "target " target))
       (doc-box doc 400)
       (kv-dl [["id" id] ["stratum" (fmt-num stratum)] ["category" category] ["target" target]])
       (when (some? query) (code-block (fmt-pr query 700)))
       (when (some? assert) (code-block (fmt-pr assert 500)))))
