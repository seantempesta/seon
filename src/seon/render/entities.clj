(ns seon.render.entities
  "Uniform pure `ns-map -> ns-map` multi-level Hiccup renderers for Seon's core entities.
   Every renderer takes a namespaced entity map, returns `{:seon.ui/hiccup [...]}`, and tags
   the root element with integer `:data-min-width` for the Upgrade Ladder."
  (:require [clojure.string :as str]))

(defn- clip [x n]
  (let [s (if (nil? x) "" (str x))]
    (if (> (count s) n) (str (subs s 0 (max 1 (dec n))) "…") s)))

(defn- one-line [x n]
  (clip (-> (if (nil? x) "" (str x)) (str/replace #"\s+" " ") str/trim) n))

(defn- fmt-pr [x n] (clip (pr-str x) n))

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
      (cond
        (or (Double/isNaN d) (Double/isInfinite d)) "—"
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

(defn- kv-row [k v]
  (when (and (some? v) (not (str/blank? (str v))))
    [[:dt (str k)] [:dd (str v)]]))

(defn- kv-dl [pairs]
  (let [rows (mapcat (fn [[k v]] (kv-row k v)) pairs)]
    (when (seq rows) (into [:dl {:class "kv-grid"}] rows))))

(defn- code-block [txt]
  (when (and (some? txt) (not (str/blank? (str txt))))
    [:pre {:class "code-box ent-code tiny"} [:code (str txt)]]))

(defn- chip [min-w cls & xs]
  {:seon.ui/hiccup
   (into [:span {:class (str "ent-chip " cls) :data-min-width min-w}] (remove nil? xs))})

(defn- row [min-w cls & xs]
  {:seon.ui/hiccup
   (into [:div {:class (str "ent-row " cls) :data-min-width min-w}] (remove nil? xs))})

(defn- card [min-w cls head & body]
  {:seon.ui/hiccup
   (into [:div {:class (str "card ent-card " cls) :data-min-width min-w} head] (remove nil? body))})

(defn- full-card [min-w cls head & body]
  {:seon.ui/hiccup
   (into [:div {:class (str "card ent-full " cls) :data-min-width min-w} head] (remove nil? body))})

(defn- card-head [left right]
  [:div {:class "card-head ent-head"}
   (into [:div {:class "pill-row"}] (remove nil? left))
   (when (and (some? right) (not (str/blank? (str right))))
     [:span {:class "muted tiny mono ent-meta"} (str right)])])

(defn- var-badge [v]
  (cond
    (:seon.code.var/test? v) (badge "green" "test")
    (:seon.code.var/macro? v) (badge "purple" "macro")
    (:seon.code.var/flow-edge? v) (badge "amber" "edge")
    (:seon.code.var/private? v) (badge "muted" "defn-")
    :else (badge "blue" "var")))

;; Var (:seon.code.var/entity)

(defn render-var-chip
  "Compact chip (~160px) for `:seon.code.var/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.code.var/entity :seon.code.var/entity]]]
                  [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 500000}
  [{v :seon.code.var/entity}]
  (chip 160 "ent-var"
        (var-badge v)
        [:code {:class "mono strong"} (str (:seon.code.var/name v))]))

(defn render-var-sig
  "Medium signature card (~320px) for `:seon.code.var/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.code.var/entity :seon.code.var/entity]]]
                  [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1000000}
  [{v :seon.code.var/entity}]
  (let [args (:seon.code.var/arglists v)]
    (card 320 "ent-var"
          (card-head [(var-badge v) [:strong {:class "mono"} (str (:seon.code.var/name v))]]
                     (or (when (seq-items args) (fmt-pr args 64)) (str (:seon.code.var/ns v))))
          (when-let [doc (nonblank (:seon.code.var/doc v))]
            [:p {:class "sm-copy"} (one-line doc 160)])
          (when-some [sch (:seon.code.var/schema v)]
            (code-block (fmt-pr sch 220)))
          (kv-dl [["ns" (:seon.code.var/ns v)]
                  ["ticks" (fmt-num (:seon.code.var/ticks-budget v))]]))))

(defn render-var-full
  "Full detail card (~540px) for `:seon.code.var/entity` with optional `:seon.code.form/src`."
  {:malli/schema [:=> [:cat [:map
                             [:seon.code.var/entity :seon.code.var/entity]
                             [:seon.code.form/src {:optional true} :seon.code.form/src]]]
                  [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1500000}
  [{v :seon.code.var/entity src :seon.code.form/src}]
  (let [refs (seq-items (:seon.code.var/refs v))
        ops (seq-items (:seon.code.var/ops v))]
    (full-card 540 "ent-var"
               (card-head [(var-badge v)
                           [:strong {:class "mono"} (str (:seon.code.var/name v))]
                           (when (:seon.code.var/private? v) (badge "muted" "private"))]
                          (str (:seon.code.var/ns v)))
               (when-let [doc (nonblank (:seon.code.var/doc v))]
                 [:p {:class "doc-box sm-copy"} (clip doc 800)])
               (kv-dl [["arglists" (when (seq-items (:seon.code.var/arglists v))
                                     (fmt-pr (:seon.code.var/arglists v) 140))]
                       ["form" (:seon.code.var/form v)]
                       ["big-o" (:seon.code.var/big-o v)]
                       ["ticks-budget" (fmt-num (:seon.code.var/ticks-budget v))]
                       ["bytes-budget" (fmt-num (:seon.code.var/bytes-budget v))]
                       ["refs" (when refs (str (count refs) " ref(s)"))]
                       ["ops" (when ops (str/join ", " (map str ops)))]])
               (when-some [sch (:seon.code.var/schema v)]
                 (code-block (fmt-pr sch 600)))
               (when-let [s (nonblank src)]
                 (code-block (clip s 4000))))))

;; Error (:seon.error/entity + optional :seon.fix/entity)

(defn- err-sev-cls [sev] (case sev :warning "amber" "red"))

(defn- err-st-cls [st] (case st :resolved "green" :suppressed "muted" "amber"))

(defn render-error-badge
  "Compact badge (~200px) for `:seon.error/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.error/entity :seon.error/entity]]]
                  [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 500000}
  [{e :seon.error/entity}]
  (chip 200 "ent-error"
        (badge (err-sev-cls (:seon.error/severity e)) (kw-label (:seon.error/severity e) "error"))
        [:code {:class "mono strong"} (str (or (:seon.error/kind e) :error/unknown))]
        (badge (err-st-cls (:seon.error/status e)) (kw-label (:seon.error/status e) "open"))
        [:span {:class "muted tiny"} (one-line (:seon.error/message e) 48)]))

(defn- render-nested-fix [fix]
  (when (map? fix)
    [:div {:class "ent-nested"}
     [:div {:class "pill-row"}
      (badge "green" "fix")
      [:strong {:class "mono tiny"} (str (or (:seon.fix/error-class fix) (:seon.fix/id fix)))]]
     (when (some? (:seon.fix/root-cause fix))
       [:p {:class "sm-copy"} (clip (:seon.fix/root-cause fix) 260)])
     (kv-dl [["change" (clip (:seon.fix/change-summary fix) 260)]
             ["regression-test" (:seon.fix/regression-test fix)]
             ["before/after" (when (some? (:seon.fix/before-after fix))
                               (clip (:seon.fix/before-after fix) 180))]])]))

(defn render-error-card
  "Detail card (~440px) for `:seon.error/entity`, nesting optional `:seon.fix/entity`."
  {:malli/schema [:=> [:cat [:map
                             [:seon.error/entity :seon.error/entity]
                             [:seon.fix/entity {:optional true} :seon.fix/entity]]]
                  [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1500000}
  [{e :seon.error/entity fix :seon.fix/entity}]
  (let [sev (:seon.error/severity e)
        msg (clip (:seon.error/message e) 600)
        data (:seon.error/data e)]
    (card 440 "ent-error"
          (card-head [(badge (err-sev-cls sev) (kw-label sev "error"))
                      [:strong {:class "mono"} (str (or (:seon.error/kind e) :error/unknown))]
                      (badge "blue" (kw-label (:seon.error/source e) "system"))
                      (badge (err-st-cls (:seon.error/status e)) (kw-label (:seon.error/status e) "open"))]
                     (str (or (:seon.error/agent e) "")))
          [:div {:class (if (= :warning sev) "notice sm-copy" "notice red sm-copy")}
           (if (str/blank? msg) "(no message)" msg)]
          (kv-dl [["agent" (:seon.error/agent e)]
                  ["branch" (:seon.error/branch e)]
                  ["var" (:seon.error/var e)]
                  ["at" (fmt-num (:seon.error/at e))]
                  ["id" (:seon.error/id e)]])
          (when (and (map? data) (seq data)) (code-block (fmt-pr data 400)))
          (render-nested-fix fix))))

;; Task (:seon.task/entity)

(defn- task-badge [t]
  (cond
    (:seon.task/template? t) (badge "purple" "template")
    (:seon.task/done? t) (badge "green" "done")
    :else (badge "blue" "task")))

(defn render-task-chip
  "Compact chip (~180px) for `:seon.task/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.task/entity :seon.task/entity]]]
                  [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 500000}
  [{t :seon.task/entity}]
  (chip 180 "ent-task"
        (task-badge t)
        [:span {:class "strong"} (one-line (or (:seon.task/title t) (:seon.task/id t)) 44)]))

(defn render-task-card
  "Summary card (~360px) for `:seon.task/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.task/entity :seon.task/entity]]]
                  [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1000000}
  [{t :seon.task/entity}]
  (let [needs (seq-items (:seon.task/needs t))
        tests (seq-items (:seon.task/tests t))
        snip (or (nonblank (:seon.task/summary t)) (nonblank (:seon.task/plan t)))]
    (card 360 "ent-task"
          (card-head [(task-badge t)
                      [:strong (one-line (or (:seon.task/title t) (:seon.task/id t)) 90)]]
                     (str (:seon.task/id t)))
          [:div {:class "pill-row"}
           (when (:seon.task/scope t) (pill (str "scope " (:seon.task/scope t))))
           (when (:seon.task/owner t) (pill (str "owner " (:seon.task/owner t))))
           (when needs (pill (str (count needs) " needs")))
           (when tests (pill (str (count tests) " tests")))]
          (when snip [:p {:class "sm-copy"} (one-line snip 200)]))))

(defn render-task-full
  "Full task card (~560px) for `:seon.task/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.task/entity :seon.task/entity]]]
                  [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1500000}
  [{t :seon.task/entity}]
  (let [needs (seq-items (:seon.task/needs t))
        tests (seq-items (:seon.task/tests t))]
    (full-card 560 "ent-task"
               (card-head [(task-badge t)
                           [:strong (str (or (:seon.task/title t) (:seon.task/id t)))]]
                          (str (:seon.task/id t)))
               (kv-dl [["id" (:seon.task/id t)]
                       ["scope" (:seon.task/scope t)]
                       ["owner" (:seon.task/owner t)]
                       ["created-by" (:seon.task/created-by t)]
                       ["template" (:seon.task/template t)]
                       ["done?" (str (boolean (:seon.task/done? t)))]])
               (when-let [plan (nonblank (:seon.task/plan t))]
                 [:div {:class "doc-box sm-copy"} (clip plan 1200)])
               (when-let [sm (nonblank (:seon.task/summary t))]
                 [:p {:class "sm-copy muted"} (str "Summary: " (clip sm 500))])
               (when needs
                 (into [:div {:class "pill-row"} [:span {:class "muted tiny"} "needs:"]]
                       (map #(pill (str %)) (take 12 needs))))
               (when tests
                 (into [:div {:class "pill-row"} [:span {:class "muted tiny"} "tests:"]]
                       (map #(pill (str %)) (take 12 tests)))))))

;; Schema (:seon.schema/entity)

(defn render-schema-chip
  "Compact chip (~180px) for `:seon.schema/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.schema/entity :seon.schema/entity]]]
                  [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 500000}
  [{s :seon.schema/entity}]
  (chip 180 "ent-schema"
        (badge "purple" (kw-label (:seon.schema/type s) "schema"))
        [:code {:class "mono strong"} (str (:seon.schema/key s))]))

(defn render-schema-detail
  "Detail card (~460px) for `:seon.schema/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.schema/entity :seon.schema/entity]]]
                  [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1200000}
  [{s :seon.schema/entity}]
  (let [ks (seq-items (:seon.schema/keys s))
        oks (seq-items (:seon.schema/optional-keys s))
        refs (seq-items (:seon.schema/ref-targets s))]
    (card 460 "ent-schema"
          (card-head [(badge "purple" (kw-label (:seon.schema/type s) "schema"))
                      [:strong {:class "mono"} (str (:seon.schema/key s))]]
                     (when (:seon.schema/render s) (str (:seon.schema/render s))))
          (when-let [doc (nonblank (:seon.schema/doc s))]
            [:p {:class "doc-box sm-copy"} (clip doc 400)])
          (kv-dl [["key" (:seon.schema/key s)]
                  ["type" (:seon.schema/type s)]
                  ["keys" (when ks (str (count ks) " required"))]
                  ["optional-keys" (when oks (str (count oks) " optional"))]
                  ["ref-targets" (when refs (str (count refs) " target(s)"))]])
          (code-block (fmt-pr (:seon.schema/form s) 900)))))

;; Doc (:seon.code.doc/entity)

(defn render-doc-snippet
  "Compact snippet (~240px) for `:seon.code.doc/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.code.doc/entity :seon.code.doc/entity]]]
                  [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 500000}
  [{d :seon.code.doc/entity}]
  (row 240 "ent-doc"
       (badge "amber" "doc")
       [:strong (one-line (or (:seon.code.doc/heading d) (:seon.code.doc/id d)) 60)]
       [:span {:class "muted tiny"} (one-line (:seon.code.doc/body d) 96)]))

(defn render-doc-full
  "Full doc card (~520px) for `:seon.code.doc/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.code.doc/entity :seon.code.doc/entity]]]
                  [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1200000}
  [{d :seon.code.doc/entity}]
  (let [refs (seq-items (:seon.code.doc/references d))]
    (full-card 520 "ent-doc"
               (card-head [(badge "amber" "doc")
                           [:strong (str (or (:seon.code.doc/heading d) (:seon.code.doc/id d)))]]
                          (str (or (:seon.code.doc/path d) (:seon.code.doc/id d))))
               (kv-dl [["id" (:seon.code.doc/id d)]
                       ["path" (:seon.code.doc/path d)]
                       ["order" (fmt-num (:seon.code.doc/order d))]
                       ["references" (when refs (str (count refs) " linked ref(s)"))]])
               [:div {:class "doc-box sm-copy"} (clip (:seon.code.doc/body d) 2400)])))

;; Test Verdict (:seon.test/verdict)

(defn render-test-badge
  "Compact badge (~160px) for `:seon.test/verdict`."
  {:malli/schema [:=> [:cat [:map [:seon.test/verdict :seon.test/verdict]]]
                  [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 500000}
  [{tv :seon.test/verdict}]
  (let [ok? (boolean (:seon.test/pass? tv))]
    (chip 160 "ent-test"
          (badge (if ok? "green" "red") (if ok? "PASS" "FAIL"))
          [:code {:class "mono strong"} (str (:seon.test/var tv))]
          (when-let [ms (fmt-ms (:seon.test/ms tv))]
            [:span {:class "muted tiny mono"} ms]))))

(defn render-test-card
  "Detail card (~380px) for `:seon.test/verdict`."
  {:malli/schema [:=> [:cat [:map [:seon.test/verdict :seon.test/verdict]]]
                  [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1000000}
  [{tv :seon.test/verdict}]
  (let [ok? (boolean (:seon.test/pass? tv))]
    (card 380 "ent-test"
          (card-head [(badge (if ok? "green" "red") (if ok? "PASS" "FAIL"))
                      (when (:seon.test/kind tv) (badge "blue" (kw-label (:seon.test/kind tv) "test")))
                      [:strong {:class "mono"} (str (:seon.test/var tv))]]
                     (fmt-ms (:seon.test/ms tv)))
          (kv-dl [["var" (:seon.test/var tv)]
                  ["duration" (fmt-ms (:seon.test/ms tv))]
                  ["ticks" (fmt-num (:seon.test/ticks tv))]
                  ["bytes" (fmt-num (:seon.test/bytes tv))]
                  ["digest" (when (some? (:seon.test/digest tv)) (clip (:seon.test/digest tv) 24))]])
          (when-some [rep (:seon.test/report tv)]
            (code-block (fmt-pr rep 500))))))

;; Eval (:seon.agent.eval/entity)

(defn render-eval-row
  "Compact row (~220px) for `:seon.agent.eval/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.agent.eval/entity :seon.agent.eval/entity]]]
                  [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 600000}
  [{ev :seon.agent.eval/entity}]
  (let [err? (boolean (nonblank (:seon.agent.eval/error ev)))
        lead (or (nonblank (:seon.agent.eval/intent ev)) (:seon.agent.eval/code ev))]
    (row 220 "ent-eval"
         (badge (if err? "red" "purple")
                (str (or (:seon.agent.eval/handle ev) (str "t" (:seon.agent.eval/turn ev)))))
         [:span {:class "mono tiny"} (one-line lead 72)]
         (when-let [ms (fmt-ms (:seon.agent.eval/ms ev))]
           [:span {:class "muted tiny mono ent-meta"} ms]))))

(defn render-eval-card
  "Detail card (~480px) for `:seon.agent.eval/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.agent.eval/entity :seon.agent.eval/entity]]]
                  [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1500000}
  [{ev :seon.agent.eval/entity}]
  (let [err (nonblank (:seon.agent.eval/error ev))]
    (card 480 "ent-eval"
          (card-head [(badge (if err "red" "purple")
                             (str (or (:seon.agent.eval/handle ev) (str "turn " (:seon.agent.eval/turn ev)))))
                      [:strong {:class "mono"} (str (:seon.agent.eval/agent ev))]
                      (when (:seon.agent.eval/result-schema ev)
                        (badge "blue" (str (:seon.agent.eval/result-schema ev))))]
                     (fmt-ms (:seon.agent.eval/ms ev)))
          (when-let [intent (nonblank (:seon.agent.eval/intent ev))]
            [:p {:class "sm-copy strong"} (clip intent 240)])
          (code-block (clip (:seon.agent.eval/code ev) 800))
          (if err
            [:div {:class "notice red mono tiny"} (clip err 500)]
            (when-let [v (nonblank (:seon.agent.eval/value ev))] (code-block (clip v 600))))
          (when-let [out (nonblank (:seon.agent.eval/out ev))]
            [:pre {:class "code-box tiny muted"} [:code (clip out 300)]])
          (kv-dl [["turn" (fmt-num (:seon.agent.eval/turn ev))]
                  ["ticks" (fmt-num (:seon.agent.eval/ticks ev))]
                  ["bytes" (fmt-num (:seon.agent.eval/bytes ev))]
                  ["prompt-tokens" (fmt-num (:seon.agent.eval/prompt-tokens ev))]
                  ["cache-hit-tokens" (fmt-num (:seon.agent.eval/cache-hit-tokens ev))]]))))

;; Namespace (:seon.code.ns/entity)

(defn render-ns-chip
  "Compact chip (~160px) for `:seon.code.ns/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.code.ns/entity :seon.code.ns/entity]]]
                  [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 500000}
  [{n :seon.code.ns/entity}]
  (chip 160 "ent-ns"
        (badge "blue" "ns")
        [:code {:class "mono strong"} (str (:seon.code.ns/name n))]))

(defn render-ns-card
  "Summary card (~400px) for `:seon.code.ns/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.code.ns/entity :seon.code.ns/entity]]]
                  [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1000000}
  [{n :seon.code.ns/entity}]
  (let [reqs (seq-items (:seon.code.ns/requires n))]
    (card 400 "ent-ns"
          (card-head [(badge "blue" "ns") [:strong {:class "mono"} (str (:seon.code.ns/name n))]]
                     (str (or (:seon.code.ns/path n) "")))
          (when-let [doc (nonblank (:seon.code.ns/doc n))]
            [:p {:class "doc-box sm-copy"} (clip doc 500)])
          (kv-dl [["namespace" (:seon.code.ns/name n)]
                  ["path" (:seon.code.ns/path n)]
                  ["requires" (when reqs (str (count reqs) " ns"))]])
          (when reqs
            (into [:div {:class "pill-row"}] (map #(pill (str %)) (take 14 reqs)))))))

;; Agent (:seon.agent/entity)

(defn- agent-state-label [ag]
  (cond
    (:seon.agent/archived? ag) ["muted" "archived"]
    (:seon.agent/paused? ag) ["muted" "paused"]
    (:seon.agent/ready? ag) ["green" "ready"]
    (:seon.agent/waiting? ag) ["amber" "waiting"]
    :else ["blue" "active"]))

(defn render-agent-row
  "Compact row (~200px) for `:seon.agent/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.agent/entity :seon.agent/entity]]]
                  [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 500000}
  [{ag :seon.agent/entity}]
  (let [[scls slbl] (agent-state-label ag)]
    (row 200 "ent-agent"
         (badge "blue" (kw-label (:seon.agent/role ag) "worker"))
         [:strong {:class "mono"} (str (or (:seon.agent/label ag) (:seon.agent/id ag)))]
         (badge scls slbl))))

(defn render-agent-card
  "Detail card (~440px) for `:seon.agent/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.agent/entity :seon.agent/entity]]]
                  [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1200000}
  [{ag :seon.agent/entity}]
  (let [[scls slbl] (agent-state-label ag)
        pins (seq-items (:seon.agent/pinned ag))
        mem (seq-items (:seon.agent/memory ag))]
    (card 440 "ent-agent"
          (card-head [(badge "blue" (kw-label (:seon.agent/role ag) "worker"))
                      [:strong {:class "mono"} (str (:seon.agent/id ag))]
                      (badge scls slbl)]
                     (str (or (:seon.agent/branch ag) :main)))
          (when-let [goal (nonblank (:seon.agent/goal ag))]
            [:p {:class "sm-copy"} (clip goal 360)])
          (kv-dl [["label" (:seon.agent/label ag)]
                  ["branch" (:seon.agent/branch ag)]
                  ["scope" (:seon.agent/scope ag)]
                  ["task" (:seon.agent/task ag)]
                  ["parent" (:seon.agent/parent ag)]
                  ["pinned" (when pins (str (count pins) " ref(s)"))]
                  ["memory" (when mem (str (count mem) " note(s)"))]]))))

;; Rule (:seon.rule/entity)

(defn render-rule-chip
  "Compact chip (~180px) for `:seon.rule/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.rule/entity :seon.rule/entity]]]
                  [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 500000}
  [{r :seon.rule/entity}]
  (chip 180 "ent-rule"
        (badge (if (:seon.rule/enabled? r) "green" "muted") (str "s" (:seon.rule/stratum r)))
        [:code {:class "mono strong"} (str (:seon.rule/id r))]))

(defn render-rule-detail
  "Detail card (~480px) for `:seon.rule/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.rule/entity :seon.rule/entity]]]
                  [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1200000}
  [{r :seon.rule/entity}]
  (let [on? (boolean (:seon.rule/enabled? r))]
    (card 480 "ent-rule"
          (card-head [(badge (if on? "green" "muted") (if on? "enabled" "disabled"))
                      (badge "amber" (str "stratum " (:seon.rule/stratum r)))
                      (when (:seon.rule/category r)
                        (badge "blue" (kw-label (:seon.rule/category r) "rule")))
                      [:strong {:class "mono"} (str (:seon.rule/id r))]]
                     (when (:seon.rule/target r) (str "target " (:seon.rule/target r))))
          (when-let [doc (nonblank (:seon.rule/doc r))]
            [:p {:class "doc-box sm-copy"} (clip doc 400)])
          (kv-dl [["id" (:seon.rule/id r)]
                  ["stratum" (fmt-num (:seon.rule/stratum r))]
                  ["category" (:seon.rule/category r)]
                  ["target" (:seon.rule/target r)]])
          (when-some [q (:seon.rule/query r)] (code-block (fmt-pr q 700)))
          (when-some [a (:seon.rule/assert r)] (code-block (fmt-pr a 500))))))
