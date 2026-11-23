(ns seon.render.entities
  "Uniform pure `ns-map -> ns-map` multi-level Hiccup renderers for Seon's core entities.
   Each renderer accepts a namespaced input map, returns `{:seon.ui/hiccup [...]}`, tags the root
   element with integer `:data-min-width`, hyperlinks entities & nested refs via semantic
   `<button type=\"button\" class=\"ent-link\">`, and honors optional rule-emitted params
   (`:seon.ui/highlight-terms`, `:seon.ui/expand-section`, `:seon.ui/show-callers?`, `:seon.view/token-budget`)."
  (:require [clojure.string :as str])
  (:import [java.util.regex Pattern]))

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

(defn- budget-limit [m base-n]
  (let [tb (:seon.view/token-budget m)]
    (if (and (integer? tb) (pos? (long tb)))
      (long (max 80 (min (* 2 (long base-n)) (Math/round (* (double base-n) (/ (double tb) 2400.0))))))
      (long base-n))))

(defn- highlight-terms [m]
  (let [raw (:seon.ui/highlight-terms m)]
    (when (sequential? raw)
      (->> raw (filter string?) (map str/trim) (filter #(>= (count %) 2)) distinct (take 6) vec not-empty))))

(defn- highlight-str [s terms]
  (if (or (empty? terms) (str/blank? s))
    s
    (let [pat (re-pattern (str "(?i)(" (str/join "|" (map #(Pattern/quote ^String %) terms)) ")"))
          matcher (re-matcher pat s)
          out (loop [idx 0 acc []]
                (if (.find matcher)
                  (let [st (.start matcher) en (.end matcher)]
                    (recur en (cond-> acc
                                (> st idx) (conj (subs s idx st))
                                true (conj [:mark {:class "hl-term"} (subs s st en)]))))
                  (cond-> acc (< idx (count s)) (conj (subs s idx)))))]
      (if (= 1 (count out)) (first out) (into [:span] out)))))

(defn- ent-link
  ([ref label] (ent-link ref label nil))
  ([ref label cls]
   (if (and (vector? ref) (= 2 (count ref)) (qualified-keyword? (first ref)) (some? (second ref)))
     [:button {:type "button"
               :class (str "ent-link" (when cls (str " " cls)))
               :title (str "Inspect " (pr-str ref))
               :on-click (list 'seon.web/inspect-entity! ref)}
      (str label)]
     [:span {:class (or cls "")} (str label)])))

(defn- badge [cls text]
  [:span {:class (str "badge " cls)} (str text)])

(defn- pill [text]
  [:span {:class "pill mono"} (str text)])

(defn- link-pill [ref text]
  (if (and (vector? ref) (= 2 (count ref)) (qualified-keyword? (first ref)) (some? (second ref)))
    [:button {:type "button"
              :class "pill mono ent-link"
              :title (str "Inspect " (pr-str ref))
              :on-click (list 'seon.web/inspect-entity! ref)}
     (str text)]
    (pill text)))

(defn- kv-dl [pairs]
  (let [rows (mapcat (fn [[k v]]
                       (when (and (some? v) (or (vector? v) (not (str/blank? (str v)))))
                         [[:dt (str k)] [:dd (if (vector? v) v (str v))]]))
                     pairs)]
    (when (seq rows) (into [:dl {:class "kv-grid"}] rows))))

(defn- code-block
  ([txt] (code-block txt false))
  ([txt expanded?]
   (when (and (some? txt) (not (str/blank? (str txt))))
     [:pre {:class (str "code-box ent-code tiny" (when expanded? " is-expanded"))} [:code (str txt)]])))

(defn- doc-box
  ([x n] (doc-box x n nil))
  ([x n terms]
   (when-let [s (nonblank x)]
     [:p {:class "doc-box sm-copy"} (highlight-str (clip s n) terms)])))

(defn- linked-pill-list [label xs attr limit]
  (when-let [items (seq-items xs)]
    (into [:div {:class "pill-row"} (when label [:span {:class "muted tiny"} label])]
          (map (fn [it]
                 (if attr
                   (link-pill [attr it] (str it))
                   (pill (str it))))
               (take limit items)))))

(defn- chip [min-w cls & xs]
  {:seon.ui/hiccup (into [:span {:class (str "ent-chip " cls) :data-min-width min-w}] (remove nil? xs))})

(defn- row [min-w cls & xs]
  {:seon.ui/hiccup (into [:div {:class (str "ent-row " cls) :data-min-width min-w}] (remove nil? xs))})

(defn- box [tag-cls min-w cls left right & body]
  {:seon.ui/hiccup
   (into [:div {:class (str "card " tag-cls " " cls) :data-min-width min-w}
          [:div {:class "card-head ent-head"}
           (into [:div {:class "pill-row"}] (remove nil? left))
           (cond
             (vector? right) right
             (and (some? right) (not (str/blank? (str right))))
             [:span {:class "muted tiny mono ent-meta"} (str right)]
             :else nil)]]
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
  (let [vn (:seon.code.var/name v)]
    (chip 160 "ent-var" (var-badge v) (ent-link [:seon.code.var/name vn] (str vn) "mono strong"))))

(defn render-var-sig
  "Medium signature card (~320px) for `:seon.code.var/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.code.var/entity :seon.code.var/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1000000}
  [{{:seon.code.var/keys [name ns doc arglists schema ticks-budget] :as v} :seon.code.var/entity :as m}]
  (let [terms (highlight-terms m)
        callers (when (:seon.ui/show-callers? m) (seq-items (:seon.ui/callers m)))]
    (box "ent-card" 320 "ent-var"
         [(var-badge v) (ent-link [:seon.code.var/name name] (str name) "mono strong")]
         (or (when (seq-items arglists) (fmt-pr arglists 64))
             (when ns (ent-link [:seon.code.ns/name ns] (str ns) "muted tiny mono ent-meta")))
         (when-let [d (nonblank doc)] [:p {:class "sm-copy"} (highlight-str (one-line d (budget-limit m 160)) terms)])
         (when (some? schema) (code-block (fmt-pr schema (budget-limit m 220))))
         (kv-dl [["ns" (when ns (ent-link [:seon.code.ns/name ns] (str ns) "mono"))]
                 ["ticks" (fmt-num ticks-budget)]])
         (when callers (linked-pill-list "callers:" callers :seon.code.var/name 8)))))

(defn render-var-full
  "Full detail card (~540px) for `:seon.code.var/entity` with optional `:seon.code.form/src`."
  {:malli/schema [:=> [:cat [:map [:seon.code.var/entity :seon.code.var/entity]
                             [:seon.code.form/src {:optional true} :seon.code.form/src]]]
                  [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1500000}
  [{{:seon.code.var/keys [name ns doc arglists schema form big-o ticks-budget bytes-budget refs ops private?] :as v}
    :seon.code.var/entity src :seon.code.form/src :as m}]
  (let [rs (seq-items refs) os (seq-items ops)
        terms (highlight-terms m)
        exp-src? (= :source (:seon.ui/expand-section m))
        callers (when (:seon.ui/show-callers? m) (seq-items (:seon.ui/callers m)))]
    (box "ent-full" 540 "ent-var"
         [(var-badge v) (ent-link [:seon.code.var/name name] (str name) "mono strong")
          (when private? (badge "muted" "private"))]
         (when ns (ent-link [:seon.code.ns/name ns] (str ns) "muted tiny mono ent-meta"))
         (doc-box doc (budget-limit m 800) terms)
         (kv-dl [["arglists" (when (seq-items arglists) (fmt-pr arglists 140))] ["form" form] ["big-o" big-o]
                 ["ticks-budget" (fmt-num ticks-budget)] ["bytes-budget" (fmt-num bytes-budget)]
                 ["refs" (when rs (str (count rs) " ref(s)"))] ["ops" (when os (str/join ", " (map str os)))]])
         (when callers (linked-pill-list "callers:" callers :seon.code.var/name 12))
         (when (some? schema) (code-block (fmt-pr schema (budget-limit m 600))))
         (when-let [s (nonblank src)] (code-block (clip s (budget-limit m (if exp-src? 6000 4000))) exp-src?)))))

;; Error (:seon.error/entity + optional :seon.fix/entity)

(defn- err-sev-cls [sev] (case sev :warning "amber" "red"))
(defn- err-st-cls [st] (case st :resolved "green" :suppressed "muted" "amber"))

(defn render-error-badge
  "Compact badge (~200px) for `:seon.error/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.error/entity :seon.error/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 500000}
  [{{:seon.error/keys [id severity kind status message]} :seon.error/entity}]
  (let [eref (when id [:seon.error/id id])]
    (chip 200 "ent-error"
          (badge (err-sev-cls severity) (kw-label severity "error"))
          (ent-link eref (str (or kind :error/unknown)) "mono strong")
          (badge (err-st-cls status) (kw-label status "open"))
          [:span {:class "muted tiny"} (one-line message 48)])))

(defn- render-nested-fix [{:seon.fix/keys [id error-class root-cause change-summary regression-test before-after] :as fix} exp-fix? terms]
  (when (map? fix)
    [:div {:class (str "ent-nested" (when exp-fix? " is-expanded"))}
     [:div {:class "pill-row"}
      (badge "green" "fix")
      (ent-link (when id [:seon.fix/id id]) (str (or error-class id)) "mono tiny strong")]
     (when (some? root-cause) [:p {:class "sm-copy"} (highlight-str (clip root-cause (if exp-fix? 480 260)) terms)])
     (kv-dl [["change" (clip change-summary (if exp-fix? 480 260))]
             ["regression-test" (when regression-test (ent-link [:seon.code.var/name regression-test] (str regression-test) "mono"))]
             ["before/after" (when (some? before-after) (clip before-after 180))]])]))

(defn render-error-card
  "Detail card (~440px) for `:seon.error/entity`, nesting optional `:seon.fix/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.error/entity :seon.error/entity]
                             [:seon.fix/entity {:optional true} :seon.fix/entity]]]
                  [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1500000}
  [{{:seon.error/keys [id kind severity source status message agent branch at var data]} :seon.error/entity
    fix :seon.fix/entity :as m}]
  (let [terms (highlight-terms m)
        msg (clip message (budget-limit m 600))
        exp-fix? (= :fix (:seon.ui/expand-section m))]
    (box "ent-card" 440 "ent-error"
         [(badge (err-sev-cls severity) (kw-label severity "error"))
          (ent-link (when id [:seon.error/id id]) (str (or kind :error/unknown)) "mono strong")
          (badge "blue" (kw-label source "system"))
          (badge (err-st-cls status) (kw-label status "open"))]
         (when agent (ent-link [:seon.agent/id agent] (str agent) "muted tiny mono ent-meta"))
         [:div {:class (if (= :warning severity) "notice sm-copy" "notice red sm-copy")}
          (if (str/blank? msg) "(no message)" (highlight-str msg terms))]
         (kv-dl [["agent" (when agent (ent-link [:seon.agent/id agent] (str agent) "mono"))]
                 ["branch" (when branch (ent-link [:seon.branch/name branch] (str branch) "mono"))]
                 ["var" (when var (ent-link [:seon.code.var/name var] (str var) "mono"))]
                 ["at" (fmt-num at)] ["id" id]])
         (when (and (map? data) (seq data)) (code-block (fmt-pr data (budget-limit m 400))))
         (render-nested-fix fix exp-fix? terms))))

;; Task (:seon.task/entity)

(defn- task-badge [{:seon.task/keys [template? done?]}]
  (cond template? (badge "purple" "template") done? (badge "green" "done") :else (badge "blue" "task")))

(defn render-task-chip
  "Compact chip (~180px) for `:seon.task/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.task/entity :seon.task/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 500000}
  [{{:seon.task/keys [id title] :as t} :seon.task/entity}]
  (chip 180 "ent-task" (task-badge t) (ent-link [:seon.task/id id] (one-line (or title id) 44) "strong")))

(defn render-task-card
  "Summary card (~360px) for `:seon.task/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.task/entity :seon.task/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1000000}
  [{{:seon.task/keys [id title scope owner needs tests summary plan] :as t} :seon.task/entity :as m}]
  (let [ns' (seq-items needs) ts' (seq-items tests)
        snip (or (nonblank summary) (nonblank plan))
        terms (highlight-terms m)]
    (box "ent-card" 360 "ent-task"
         [(task-badge t) (ent-link [:seon.task/id id] (one-line (or title id) 90) "strong")]
         (ent-link [:seon.task/id id] (str id) "muted tiny mono ent-meta")
         [:div {:class "pill-row"}
          (when scope (link-pill [:seon.code.ns/name scope] (str "scope " scope)))
          (when owner (link-pill [:seon.agent/id owner] (str "owner " owner)))
          (when ns' (pill (str (count ns') " needs")))
          (when ts' (pill (str (count ts') " tests")))]
         (when snip [:p {:class "sm-copy"} (highlight-str (one-line snip (budget-limit m 200)) terms)]))))

(defn render-task-full
  "Full task card (~560px) for `:seon.task/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.task/entity :seon.task/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1500000}
  [{{:seon.task/keys [id title scope owner created-by template done? plan summary needs tests] :as t}
    :seon.task/entity :as m}]
  (let [terms (highlight-terms m)
        exp-plan? (= :plan (:seon.ui/expand-section m))]
    (box "ent-full" 560 "ent-task"
         [(task-badge t) (ent-link [:seon.task/id id] (str (or title id)) "strong")]
         (ent-link [:seon.task/id id] (str id) "muted tiny mono ent-meta")
         (kv-dl [["id" (ent-link [:seon.task/id id] (str id) "mono")]
                 ["scope" (when scope (ent-link [:seon.code.ns/name scope] (str scope) "mono"))]
                 ["owner" (when owner (ent-link [:seon.agent/id owner] (str owner) "mono"))]
                 ["created-by" (when created-by (ent-link [:seon.agent/id created-by] (str created-by) "mono"))]
                 ["template" (when template (ent-link [:seon.task/id template] (str template) "mono"))]
                 ["done?" (str (boolean done?))]])
         (doc-box plan (budget-limit m (if exp-plan? 2400 1200)) terms)
         (when-let [s (nonblank summary)]
           [:p {:class "sm-copy muted"} "Summary: " (highlight-str (clip s (budget-limit m 500)) terms)])
         (linked-pill-list "needs:" needs :seon.task/id 12)
         (linked-pill-list "tests:" tests :seon.code.var/name 12))))

;; Schema (:seon.schema/entity)

(defn render-schema-chip
  "Compact chip (~180px) for `:seon.schema/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.schema/entity :seon.schema/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 500000}
  [{{:seon.schema/keys [key type]} :seon.schema/entity}]
  (chip 180 "ent-schema" (badge "purple" (kw-label type "schema"))
        (ent-link [:seon.schema/key key] (str key) "mono strong")))

(defn render-schema-detail
  "Detail card (~460px) for `:seon.schema/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.schema/entity :seon.schema/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1200000}
  [{{:seon.schema/keys [key form doc render type keys optional-keys ref-targets]} :seon.schema/entity :as m}]
  (let [ks (seq-items keys) oks (seq-items optional-keys) refs (seq-items ref-targets)
        terms (highlight-terms m)]
    (box "ent-card" 460 "ent-schema"
         [(badge "purple" (kw-label type "schema")) (ent-link [:seon.schema/key key] (str key) "mono strong")]
         (when render (ent-link [:seon.code.var/name render] (str render) "muted tiny mono ent-meta"))
         (doc-box doc (budget-limit m 400) terms)
         (kv-dl [["key" (ent-link [:seon.schema/key key] (str key) "mono")] ["type" type]
                 ["keys" (when ks (str (count ks) " required"))]
                 ["optional-keys" (when oks (str (count oks) " optional"))]
                 ["ref-targets" (when refs (str (count refs) " target(s)"))]])
         (when refs (linked-pill-list "targets:" refs :seon.schema/key 8))
         (code-block (fmt-pr form (budget-limit m 900))))))

;; Doc (:seon.code.doc/entity)

(defn render-doc-snippet
  "Compact snippet (~240px) for `:seon.code.doc/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.code.doc/entity :seon.code.doc/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 500000}
  [{{:seon.code.doc/keys [id heading body]} :seon.code.doc/entity :as m}]
  (row 240 "ent-doc" (badge "amber" "doc")
       (ent-link [:seon.code.doc/id id] (one-line (or heading id) 60) "strong")
       [:span {:class "muted tiny"} (highlight-str (one-line body 96) (highlight-terms m))]))

(defn render-doc-full
  "Full doc card (~520px) for `:seon.code.doc/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.code.doc/entity :seon.code.doc/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1200000}
  [{{:seon.code.doc/keys [id path order heading body references]} :seon.code.doc/entity :as m}]
  (let [refs (seq-items references)
        terms (highlight-terms m)]
    (box "ent-full" 520 "ent-doc"
         [(badge "amber" "doc") (ent-link [:seon.code.doc/id id] (str (or heading id)) "strong")]
         (str (or path id))
         (kv-dl [["id" (ent-link [:seon.code.doc/id id] (str id) "mono")]
                 ["path" path] ["order" (fmt-num order)]
                 ["references" (when refs (str (count refs) " linked ref(s)"))]])
         [:div {:class "doc-box sm-copy"} (highlight-str (clip body (budget-limit m 2400)) terms)])))

;; Test Verdict (:seon.test/verdict)

(defn render-test-badge
  "Compact badge (~160px) for `:seon.test/verdict`."
  {:malli/schema [:=> [:cat [:map [:seon.test/verdict :seon.test/verdict]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 500000}
  [{{:seon.test/keys [pass? var ms digest]} :seon.test/verdict}]
  (chip 160 "ent-test"
        (if digest
          (ent-link [:seon.test/digest digest] (if pass? "PASS" "FAIL") (str "badge " (if pass? "green" "red")))
          (badge (if pass? "green" "red") (if pass? "PASS" "FAIL")))
        (ent-link [:seon.code.var/name var] (str var) "mono strong")
        (when-let [m (fmt-ms ms)] [:span {:class "muted tiny mono"} m])))

(defn render-test-card
  "Detail card (~380px) for `:seon.test/verdict`."
  {:malli/schema [:=> [:cat [:map [:seon.test/verdict :seon.test/verdict]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1000000}
  [{{:seon.test/keys [pass? var kind ms ticks bytes digest report]} :seon.test/verdict :as m}]
  (let [exp-rep? (= :report (:seon.ui/expand-section m))]
    (box "ent-card" 380 "ent-test"
         [(if digest
            (ent-link [:seon.test/digest digest] (if pass? "PASS" "FAIL") (str "badge " (if pass? "green" "red")))
            (badge (if pass? "green" "red") (if pass? "PASS" "FAIL")))
          (when kind (badge "blue" (kw-label kind "test")))
          (ent-link [:seon.code.var/name var] (str var) "mono strong")]
         (fmt-ms ms)
         (kv-dl [["var" (ent-link [:seon.code.var/name var] (str var) "mono")]
                 ["duration" (fmt-ms ms)] ["ticks" (fmt-num ticks)]
                 ["bytes" (fmt-num bytes)]
                 ["digest" (when (some? digest) (ent-link [:seon.test/digest digest] (clip digest 24) "mono"))]])
         (when (some? report) (code-block (fmt-pr report (budget-limit m (if exp-rep? 1400 500))) exp-rep?)))))

;; Eval (:seon.agent.eval/entity)

(defn render-eval-row
  "Compact row (~220px) for `:seon.agent.eval/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.agent.eval/entity :seon.agent.eval/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 600000}
  [{{:seon.agent.eval/keys [id turn handle intent code error ms]} :seon.agent.eval/entity}]
  (let [lbl (str (or handle (str "t" turn)))]
    (row 220 "ent-eval"
         (if id
           (ent-link [:seon.agent.eval/id id] lbl (str "badge " (if (nonblank error) "red" "purple")))
           (badge (if (nonblank error) "red" "purple") lbl))
         [:span {:class "mono tiny"} (one-line (or (nonblank intent) code) 72)]
         (when-let [m (fmt-ms ms)] [:span {:class "muted tiny mono ent-meta"} m]))))

(defn render-eval-card
  "Detail card (~480px) for `:seon.agent.eval/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.agent.eval/entity :seon.agent.eval/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1500000}
  [{{:seon.agent.eval/keys [id agent turn handle result-schema intent code value out error ms ticks bytes
                            prompt-tokens cache-hit-tokens]} :seon.agent.eval/entity :as m}]
  (let [err (nonblank error)
        lbl (str (or handle (str "turn " turn)))
        terms (highlight-terms m)]
    (box "ent-card" 480 "ent-eval"
         [(if id
            (ent-link [:seon.agent.eval/id id] lbl (str "badge " (if err "red" "purple")))
            (badge (if err "red" "purple") lbl))
          (ent-link (when agent [:seon.agent/id agent]) (str agent) "mono strong")
          (when result-schema (ent-link [:seon.schema/key result-schema] (str result-schema) "badge blue"))]
         (fmt-ms ms)
         (when-let [i (nonblank intent)] [:p {:class "sm-copy strong"} (highlight-str (clip i 240) terms)])
         (code-block (clip code (budget-limit m 800)))
         (if err
           [:div {:class "notice red mono tiny"} (clip err 500)]
           (when-let [v (nonblank value)] (code-block (clip v (budget-limit m 600)))))
         (when-let [o (nonblank out)] [:pre {:class "code-box tiny muted"} [:code (clip o 300)]])
         (kv-dl [["turn" (fmt-num turn)] ["ticks" (fmt-num ticks)] ["bytes" (fmt-num bytes)]
                 ["prompt-tokens" (fmt-num prompt-tokens)] ["cache-hit-tokens" (fmt-num cache-hit-tokens)]]))))

;; Namespace (:seon.code.ns/entity)

(defn render-ns-chip
  "Compact chip (~160px) for `:seon.code.ns/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.code.ns/entity :seon.code.ns/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 500000}
  [{{:seon.code.ns/keys [name]} :seon.code.ns/entity}]
  (chip 160 "ent-ns" (badge "blue" "ns") (ent-link [:seon.code.ns/name name] (str name) "mono strong")))

(defn render-ns-card
  "Summary card (~400px) for `:seon.code.ns/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.code.ns/entity :seon.code.ns/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1000000}
  [{{:seon.code.ns/keys [name path doc requires]} :seon.code.ns/entity :as m}]
  (let [reqs (seq-items requires)]
    (box "ent-card" 400 "ent-ns"
         [(badge "blue" "ns") (ent-link [:seon.code.ns/name name] (str name) "mono strong")]
         (str (or path ""))
         (doc-box doc (budget-limit m 500) (highlight-terms m))
         (kv-dl [["namespace" (ent-link [:seon.code.ns/name name] (str name) "mono")]
                 ["path" path] ["requires" (when reqs (str (count reqs) " ns"))]])
         (linked-pill-list nil requires :seon.code.ns/name 14))))

;; Agent (:seon.agent/entity)

(defn- agent-state-label [{:seon.agent/keys [archived? paused? ready? waiting? merged-tx]}]
  (cond archived? ["muted" "archived"]
        merged-tx ["green" "merged"]
        paused? ["muted" "paused"]
        ready? ["green" "ready"]
        waiting? ["amber" "waiting"]
        :else ["blue" "active"]))

(defn render-agent-row
  "Compact row (~200px) for `:seon.agent/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.agent/entity :seon.agent/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 500000}
  [{{:seon.agent/keys [id label role] :as ag} :seon.agent/entity}]
  (let [[scls slbl] (agent-state-label ag)]
    (row 200 "ent-agent" (badge "blue" (kw-label role "worker"))
         (ent-link [:seon.agent/id id] (str (or label id)) "mono strong") (badge scls slbl))))

(defn render-agent-card
  "Detail card (~440px) for `:seon.agent/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.agent/entity :seon.agent/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1200000}
  [{{:seon.agent/keys [id label role branch scope task parent goal pinned memory] :as ag} :seon.agent/entity :as m}]
  (let [[scls slbl] (agent-state-label ag) pins (seq-items pinned) mem (seq-items memory)]
    (box "ent-card" 440 "ent-agent"
         [(badge "blue" (kw-label role "worker"))
          (ent-link [:seon.agent/id id] (str id) "mono strong")
          (badge scls slbl)]
         (ent-link [:seon.branch/name (or branch :main)] (str (or branch :main)) "muted tiny mono ent-meta")
         (when-let [g (nonblank goal)] [:p {:class "sm-copy"} (highlight-str (clip g 360) (highlight-terms m))])
         (kv-dl [["label" label]
                 ["branch" (ent-link [:seon.branch/name (or branch :main)] (str (or branch :main)) "mono")]
                 ["scope" (when scope (ent-link [:seon.code.ns/name scope] (str scope) "mono"))]
                 ["task" (when task (ent-link [:seon.task/id task] (str task) "mono"))]
                 ["parent" (when parent (ent-link [:seon.agent/id parent] (str parent) "mono"))]
                 ["pinned" (when pins (str (count pins) " ref(s)"))]
                 ["memory" (when mem (str (count mem) " note(s)"))]]))))

;; Rule (:seon.rule/entity)

(defn render-rule-chip
  "Compact chip (~180px) for `:seon.rule/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.rule/entity :seon.rule/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 500000}
  [{{:seon.rule/keys [id stratum enabled?]} :seon.rule/entity}]
  (chip 180 "ent-rule" (badge (if enabled? "green" "muted") (str "s" stratum))
        (ent-link [:seon.rule/id id] (str id) "mono strong")))

(defn render-rule-detail
  "Detail card (~480px) for `:seon.rule/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.rule/entity :seon.rule/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1200000}
  [{{:seon.rule/keys [id doc category stratum enabled? target query assert]} :seon.rule/entity :as m}]
  (box "ent-card" 480 "ent-rule"
       [(badge (if enabled? "green" "muted") (if enabled? "enabled" "disabled"))
        (badge "amber" (str "stratum " stratum))
        (when category (badge "blue" (kw-label category "rule")))
        (ent-link [:seon.rule/id id] (str id) "mono strong")]
       (when target (str "target " target))
       (doc-box doc (budget-limit m 400) (highlight-terms m))
       (kv-dl [["id" (ent-link [:seon.rule/id id] (str id) "mono")]
               ["stratum" (fmt-num stratum)] ["category" category] ["target" target]])
       (when (some? query) (code-block (fmt-pr query (budget-limit m 700))))
       (when (some? assert) (code-block (fmt-pr assert (budget-limit m 500))))))

;; Ephemeral Tail Blocks (pure `ns-map -> ns-map` Hiccup renderers)

(defn render-branch-status-tail
  "Pure `ns-map -> ns-map` Hiccup renderer for branch delta, turn budget, proofs, and branch errors."
  {:malli/schema [:=> [:cat [:map [:seon.tail/branch-status :map]]]
                  [:maybe [:map [:seon.ui/hiccup :seon.ui/hiccup]]]]
   :cost/ticks 500000}
  [{{:keys [branch max-tx branch-edited main-drifted branch-errors last-proofs
            show-budget? turns-used max-turns rem-turns]} :seon.tail/branch-status}]
  (when (or (seq branch-edited) (seq branch-errors) last-proofs (seq main-drifted) show-budget?)
    (let [n (count branch-edited)
          ed-s (when (pos? n)
                 (str " (" (str/join ", " (take 6 (sort branch-edited)))
                      (when (> n 6) (str " +" (- n 6) " more")) ")"))
          sync-s (if (empty? main-drifted) "up-to-date" (str (count main-drifted) " form(s) moved on :main"))]
      {:tag "branch-status"
       :seon.ui/hiccup
       [:section {:class "tail-block" :data-min-width 240}
        [:p (str "branch=" branch " @ tx " max-tx " | edited-forms=" n ed-s " | main-sync=" sync-s)]
        (when show-budget?
          [:p (str "TURN BUDGET: turn " turns-used " / " max-turns " (" rem-turns " remaining).")])
        (when (seq main-drifted)
          [:p (str "MAIN DRIFT: :main updated " (str/join ", " (take 5 (sort main-drifted))))])
        (when last-proofs
          [:p (str "AUTO-PROOFS (tx " (:tx last-proofs) "): " (if (:ok? last-proofs) "PASS" "FAIL")
                   " (" (:passed last-proofs 0) "/" (:ran last-proofs 0) " in " (:ms last-proofs 0.0) " ms)")])
        (when (seq branch-errors)
          [:div
           [:p (str "MERGE-BLOCKING HEALTH ERRORS (" (count branch-errors) "):")]
           (into [:ul]
                 (for [e (take 5 branch-errors)]
                   [:li (str "[:" (name (or (:class e) :error)) "] " (:subject e) " — " (:message e))]))])]})))

(defn render-scoped-health-tail
  "Pure `ns-map -> ns-map` Hiccup renderer for scope health alerts (nil when clean)."
  {:malli/schema [:=> [:cat [:map [:seon.tail/scoped-health :map]]]
                  [:maybe [:map [:seon.ui/hiccup :seon.ui/hiccup]]]]
   :cost/ticks 500000}
  [{{:keys [scope problems]} :seon.tail/scoped-health}]
  (when (seq problems)
    {:tag "scoped-health"
     :seon.ui/hiccup
     [:section {:class "tail-block" :data-min-width 220}
      [:p (str "Health & efficiency alerts for " scope ":")]
      (into [:ul]
            (for [p (take 6 problems)]
              [:li (str "[" (name (or (:severity p) :warning)) " :" (name (or (:class p) :issue)) "] "
                        (:subject p) " — " (:message p)
                        (when (:fix p) " (auto-fixable via (user/autofix-health!))"))]))]}))

(defn render-activity-tail
  "Pure `ns-map -> ns-map` Hiccup renderer for open calls, sub-agents, errors, and live records."
  {:malli/schema [:=> [:cat [:map [:seon.tail/activity :map]]]
                  [:maybe [:map [:seon.ui/hiccup :seon.ui/hiccup]]]]
   :cost/ticks 1000000}
  [{{:keys [scope incoming outgoing open-errors samples]} :seon.tail/activity}]
  (let [e-hs (keep #(when (and (map? %) (contains? % :seon.error/kind))
                      (:seon.ui/hiccup (render-error-badge {:seon.error/entity %})))
                   (take 4 open-errors))]
    (when (or (seq incoming) (seq outgoing) (seq e-hs) (seq samples))
      {:tag "neighborhood-activity"
       :seon.ui/hiccup
       [:section {:class "tail-block" :data-min-width 240}
        (when (seq incoming)
          [:div
           [:p "Open incoming calls to answer via (user/answer! call-id val):"]
           (into [:ul]
                 (for [c incoming]
                   [:li (str (pr-str (:seon.agent.call/id c)) " from " (:seon.agent.call/caller c)
                             (when (:seon.agent.call/result-schema c) (str " schema=" (:seon.agent.call/result-schema c)))
                             ": " (one-line (:seon.agent.call/prompt c) 100))]))])
        (when (seq outgoing)
          [:div
           [:p "Outgoing delegated calls:"]
           (into [:ul]
                 (for [c outgoing]
                   [:li (str (:seon.agent.call/callee c) " [" (kw-label (:seon.agent.call/status c) "pending") "]: "
                             (one-line (:seon.agent.call/prompt c) 80))]))])
        (when (seq e-hs) (into [:div [:p "Open errors:"]] e-hs))
        (when (seq samples)
          [:div
           [:p (str "Recent live data matching " scope " schemas:")]
           (into [:ul]
                 (for [{:keys [attr tx preview]} samples]
                   [:li (str "tx " tx " (" attr "): " preview)]))])]})))

(defn render-inbox-tail
  "Pure `ns-map -> ns-map` Hiccup renderer for unread inbox messages."
  {:malli/schema [:=> [:cat [:map [:seon.tail/inbox :map]]]
                  [:maybe [:map [:seon.ui/hiccup :seon.ui/hiccup]]]]
   :cost/ticks 500000}
  [{{:keys [unread]} :seon.tail/inbox}]
  (when (seq unread)
    {:tag "unread-inbox"
     :seon.ui/hiccup
     [:section {:class "tail-block" :data-min-width 200}
      [:p (str "Unread inbox (" (count unread) "):")]
      (into [:ul]
            (for [m (take 6 unread)]
              [:li (str (or (:seon.agent.message/from m) :user)
                        " [" (kw-label (:seon.agent.message/type m) "chat") "]: "
                        (one-line (:seon.agent.message/body m) 120))]))]}))

(defn render-system-health-tail
  "Pure `ns-map -> ns-map` Hiccup renderer for ambient operational signals & system health."
  {:malli/schema [:=> [:cat [:map [:seon.tail/system-health :map]]]
                  [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 500000}
  [{snap :seon.tail/system-health}]
  (let [{:keys [level branch flow pressure context proof health]} snap]
    {:tag "system-health"
     :seon.ui/hiccup
     [:section {:class "tail-block" :data-min-width 240}
      [:p (str "System health (" (kw-label branch "main") "): " (if (:ok? health) "OK" "ERRORS")
               " · level=" (kw-label level "calm") " · " (:summary health))]
      [:ul
       [:li (str "Flow: " (:summary flow))]
       [:li (str "Pressure: " (:summary pressure))]
       [:li (str "Context: " (:summary context))]
       [:li (str "Proof: " (:summary proof))]]]}))

(defn render-viewport-tail
  "Pure `ns-map -> ns-map` Hiccup renderer for live WKWebView screenshot & DOM metadata."
  {:malli/schema [:=> [:cat [:map [:seon.tail/viewport :map]]]
                  [:maybe [:map [:seon.ui/hiccup :seon.ui/hiccup]]]]
   :cost/ticks 500000}
  [{{:keys [snapshot enabled? vision?]} :seon.tail/viewport}]
  (when (and (map? snapshot) enabled?)
    (let [dom (:dom snapshot)
          dom-s (when (map? dom)
                  (not-empty
                   (str/join " · "
                             (remove nil?
                                     [(when-not (str/blank? (str (:viewing dom))) (str "viewing=" (:viewing dom)))
                                      (when-not (str/blank? (str (:mode dom))) (str "mode=" (:mode dom)))
                                      (when (:theme dom) (str "theme=" (:theme dom)))
                                      (when (some? (:overflowX dom)) (str "overflowX=" (:overflowX dom)))
                                      (when (some? (:svgCount dom)) (str "svgs=" (:svgCount dom)))]))))]
      (cond-> {:tag "live-viewport"
               :attrs {:route (:route snapshot "/")
                       :size (str (:width snapshot 1280) "x" (:height snapshot 820))}
               :seon.ui/hiccup
               [:section {:class "tail-block" :data-min-width 220}
                [:p "Current live WKWebView screenshot (ephemeral end-of-context frame)."]
                (when dom-s [:p (str "DOM: " dom-s)])]}
        (and vision? (:base64 snapshot))
        (assoc :image {:mime (or (:mime snapshot) "image/jpeg")
                       :base64 (:base64 snapshot)
                       :raw snapshot})))))
