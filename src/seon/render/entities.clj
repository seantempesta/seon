(ns seon.render.entities
  "Uniform pure `ns-map -> ns-map` multi-level Hiccup renderers for Seon's core entities.
   Hyperlinks entities & nested refs via semantic `<button type=\"button\" class=\"ent-link\">`
   and honors optional rule-emitted params (`:seon.ui/highlight-terms`, `:seon.ui/expand-section`,
   `:seon.ui/show-callers?`, `:seon.view/token-budget`)."
  (:require [clojure.string :as str]
            [seon.render.links :as rlinks]
            [seon.render.tails :as rtails])
  (:import [java.util.regex Pattern]))

(defn- clip [x n] (let [s (if (nil? x) "" (str x))] (if (> (count s) n) (str (subs s 0 (max 1 (dec n))) "…") s)))
(defn- one-line [x n] (clip (-> (if (nil? x) "" (str x)) (str/replace #"\s+" " ") str/trim) n))
(defn- fmt-pr [x n] (clip (pr-str x) n))
(defn- kw-label [x fb] (cond (ident? x) (name x) (some? x) (str x) :else fb))
(defn- seq-items [x] (when (and (coll? x) (not (map? x))) (seq x)))
(defn- nonblank [x] (when (and (string? x) (not (str/blank? x))) x))

(defn- fmt-ms [x]
  (when (number? x)
    (let [d (double x)] (if (or (Double/isNaN d) (Double/isInfinite d)) "—" (str (/ (Math/round (* d 10.0)) 10.0) " ms")))))

(defn- fmt-num [x]
  (when (number? x)
    (let [d (double x)]
      (cond (or (Double/isNaN d) (Double/isInfinite d)) "—" (integer? x) (str x) :else (str (/ (Math/round (* d 10.0)) 10.0))))))

(defn- bl [m n]
  (let [tb (:seon.view/token-budget m)]
    (if (and (integer? tb) (pos? (long tb)))
      (long (max 80 (min (* 2 (long n)) (Math/round (* (double n) (/ (double tb) 2400.0))))))
      (long n))))

(defn- hl-terms [m]
  (let [r (:seon.ui/highlight-terms m)]
    (when (sequential? r) (->> r (filter string?) (map str/trim) (filter #(>= (count %) 2)) distinct (take 6) vec not-empty))))

(defn- hl [s terms]
  (if (or (empty? terms) (str/blank? s))
    s
    (let [pat (re-pattern (str "(?i)(" (str/join "|" (map #(Pattern/quote ^String %) terms)) ")"))
          mt (re-matcher pat s)
          out (loop [i 0 acc []]
                (if (.find mt)
                  (let [st (.start mt) en (.end mt)]
                    (recur en (cond-> acc (> st i) (conj (subs s i st)) true (conj [:mark {:class "hl-term"} (subs s st en)]))))
                  (cond-> acc (< i (count s)) (conj (subs s i)))))]
      (if (= 1 (count out)) (first out) (into [:span] out)))))

(defn- el
  ([a v] (rlinks/ent-link a v (str v) "mono"))
  ([a v lbl] (rlinks/ent-link a v lbl "mono strong"))
  ([a v lbl cls] (rlinks/ent-link a v lbl cls)))

(defn- ml [a v] (rlinks/meta-link a v))
(defn- badge [cls text] (rlinks/badge cls text))
(defn- lp [a v lbl] (rlinks/link-pill a v lbl))
(defn- kv-dl [pairs] (rlinks/kv-dl pairs))
(defn- pill-list [label xs attr limit] (rlinks/pill-list label xs attr limit))

(defn- code-block
  ([txt] (code-block txt false))
  ([txt exp?] (when (and (some? txt) (not (str/blank? (str txt))))
                [:pre {:class (str "code-box ent-code tiny" (when exp? " is-expanded"))} [:code (str txt)]])))

(defn- doc-box
  ([x n] (doc-box x n nil))
  ([x n terms] (when-let [s (nonblank x)] [:p {:class "doc-box sm-copy"} (hl (clip s n) terms)])))

(defn- chip [min-w cls & xs]
  {:seon.ui/hiccup (into [:span {:class (str "ent-chip " cls) :data-min-width min-w}] (remove nil? xs))})

(defn- row [min-w cls & xs]
  {:seon.ui/hiccup (into [:div {:class (str "ent-row " cls) :data-min-width min-w}] (remove nil? xs))})

(defn- box [tag-cls min-w cls left right & body]
  {:seon.ui/hiccup
   (into [:div {:class (str "card " tag-cls " " cls) :data-min-width min-w}
          [:div {:class "card-head ent-head"}
           (into [:div {:class "pill-row"}] (remove nil? left))
           (cond (vector? right) right
                 (and (some? right) (not (str/blank? (str right)))) [:span {:class "muted tiny mono ent-meta"} (str right)]
                 :else nil)]]
         (remove nil? body))})

(defn- var-badge [{:seon.code.var/keys [test? macro? flow-edge? private?]}]
  (cond test? (badge "green" "test") macro? (badge "purple" "macro")
        flow-edge? (badge "amber" "edge") private? (badge "muted" "defn-") :else (badge "blue" "var")))

;; Var (:seon.code.var/entity)

(defn render-var-chip
  "Compact chip (~160px) for `:seon.code.var/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.code.var/entity :seon.code.var/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 500000}
  [{v :seon.code.var/entity}]
  (chip 160 "ent-var" (var-badge v) (el :seon.code.var/name (:seon.code.var/name v) (str (:seon.code.var/name v)))))

(defn render-var-sig
  "Medium signature card (~320px) for `:seon.code.var/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.code.var/entity :seon.code.var/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1000000}
  [{{:seon.code.var/keys [name ns doc arglists schema ticks-budget] :as v} :seon.code.var/entity :as m}]
  (let [cs (when (:seon.ui/show-callers? m) (seq-items (:seon.ui/callers m)))]
    (box "ent-card" 320 "ent-var"
         [(var-badge v) (el :seon.code.var/name name (str name))]
         (or (when (seq-items arglists) (fmt-pr arglists 64)) (ml :seon.code.ns/name ns))
         (when-let [d (nonblank doc)] [:p {:class "sm-copy"} (hl (one-line d (bl m 160)) (hl-terms m))])
         (when (some? schema) (code-block (fmt-pr schema (bl m 220))))
         (kv-dl [["ns" (when ns (el :seon.code.ns/name ns))] ["ticks" (fmt-num ticks-budget)]])
         (when cs (pill-list "callers:" cs :seon.code.var/name 8)))))

(defn render-var-full
  "Full detail card (~540px) for `:seon.code.var/entity` with optional `:seon.code.form/src`."
  {:malli/schema [:=> [:cat [:map [:seon.code.var/entity :seon.code.var/entity]
                             [:seon.code.form/src {:optional true} :seon.code.form/src]]]
                  [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1500000}
  [{{:seon.code.var/keys [name ns doc arglists schema form big-o ticks-budget bytes-budget refs ops private?] :as v}
    :seon.code.var/entity src :seon.code.form/src :as m}]
  (let [rs (seq-items refs) os (seq-items ops) exp? (= :source (:seon.ui/expand-section m))
        cs (when (:seon.ui/show-callers? m) (seq-items (:seon.ui/callers m)))]
    (box "ent-full" 540 "ent-var"
         [(var-badge v) (el :seon.code.var/name name (str name)) (when private? (badge "muted" "private"))]
         (ml :seon.code.ns/name ns)
         (doc-box doc (bl m 800) (hl-terms m))
         (kv-dl [["ns" (when ns (el :seon.code.ns/name ns))]
                 ["arglists" (when (seq-items arglists) (fmt-pr arglists 140))]
                 ["form" (when form (el :seon.code.form/key form))]
                 ["big-o" big-o]
                 ["ticks-budget" (fmt-num ticks-budget)] ["bytes-budget" (fmt-num bytes-budget)]
                 ["ops" (when os (str/join ", " (map str os)))]])
         (when rs (pill-list "refs:" rs :seon.code.var/name 12))
         (when cs (pill-list "callers:" cs :seon.code.var/name 12))
         (when (some? schema) (code-block (fmt-pr schema (bl m 600))))
         (when-let [s (nonblank src)] (code-block (clip s (bl m (if exp? 6000 4000))) exp?)))))

;; Error (:seon.error/entity + optional :seon.fix/entity) & Fix (:seon.fix/entity)

(defn- err-sev-cls [sev] (case sev :warning "amber" "red"))
(defn- err-st-cls [st] (case st :resolved "green" :suppressed "muted" "amber"))

(defn render-error-badge
  "Compact badge (~200px) for `:seon.error/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.error/entity :seon.error/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 500000}
  [{{:seon.error/keys [id severity kind status message]} :seon.error/entity}]
  (chip 200 "ent-error" (badge (err-sev-cls severity) (kw-label severity "error"))
        (el :seon.error/id id (str (or kind :error/unknown)))
        (badge (err-st-cls status) (kw-label status "open"))
        [:span {:class "muted tiny"} (one-line message 48)]))

(defn- render-nested-fix [{:seon.fix/keys [id error-class root-cause change-summary regression-test before-after] :as fix} exp? terms]
  (when (map? fix)
    [:div {:class (str "ent-nested" (when exp? " is-expanded"))}
     [:div {:class "pill-row"} (badge "green" "fix") (el :seon.fix/id id (str (or error-class id)) "mono tiny strong")]
     (when (some? root-cause) [:p {:class "sm-copy"} (hl (clip root-cause (if exp? 480 260)) terms)])
     (kv-dl [["id" (when id (el :seon.fix/id id))]
             ["error-class" (when error-class (if id (el :seon.fix/id id (str error-class)) (str error-class)))]
             ["change" (clip change-summary (if exp? 480 260))]
             ["regression-test" (when (qualified-symbol? regression-test) (el :seon.code.var/name regression-test))]
             ["before/after" (when (some? before-after) (clip before-after 180))]])]))

(defn render-error-card
  "Detail card (~440px) for `:seon.error/entity`, nesting optional `:seon.fix/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.error/entity :seon.error/entity]
                             [:seon.fix/entity {:optional true} :seon.fix/entity]]]
                  [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1500000}
  [{{:seon.error/keys [id kind severity source status message agent branch at var eval data]} :seon.error/entity
    fix :seon.fix/entity :as m}]
  (let [terms (hl-terms m) msg (clip message (bl m 600))]
    (box "ent-card" 440 "ent-error"
         [(badge (err-sev-cls severity) (kw-label severity "error"))
          (el :seon.error/id id (str (or kind :error/unknown)))
          (badge "blue" (kw-label source "system")) (badge (err-st-cls status) (kw-label status "open"))]
         (ml :seon.agent/id agent)
         [:div {:class (if (= :warning severity) "notice sm-copy" "notice red sm-copy")}
          (if (str/blank? msg) "(no message)" (hl msg terms))]
         (kv-dl [["agent" (when agent (el :seon.agent/id agent))]
                 ["branch" (when branch (el :seon.branch/name branch))]
                 ["var" (when var (el :seon.code.var/name var))]
                 ["eval" (when eval (el :seon.agent.eval/id eval))]
                 ["at" (fmt-num at)]
                 ["id" (when id (el :seon.error/id id))]])
         (when (and (map? data) (seq data)) (code-block (fmt-pr data (bl m 400))))
         (render-nested-fix fix (= :fix (:seon.ui/expand-section m)) terms))))

(defn render-fix-chip
  "Compact chip (~180px) for `:seon.fix/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.fix/entity :seon.fix/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 500000}
  [{{:seon.fix/keys [id error-class regression-test]} :seon.fix/entity}]
  (chip 180 "ent-fix" (badge "green" "fix")
        (el :seon.fix/id id (str (or error-class id)))
        (when (qualified-symbol? regression-test) (el :seon.code.var/name regression-test (name regression-test) "muted tiny mono"))))

(defn render-fix-card
  "Detail card (~440px) for `:seon.fix/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.fix/entity :seon.fix/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1200000}
  [{{:seon.fix/keys [id error-class symptom root-cause change-summary regression-test before-after merge-tx]} :seon.fix/entity :as m}]
  (let [terms (hl-terms m)]
    (box "ent-card" 440 "ent-fix"
         [(badge "green" "fix") (el :seon.fix/id id (str (or error-class id)))]
         (when (qualified-symbol? regression-test) (ml :seon.code.var/name regression-test))
         (doc-box symptom (bl m 360) terms)
         (doc-box root-cause (bl m 480) terms)
         (kv-dl [["id" (el :seon.fix/id id)]
                 ["error-class" (el :seon.fix/id id (str error-class))]
                 ["change" (clip change-summary (bl m 480))]
                 ["regression-test" (when (qualified-symbol? regression-test) (el :seon.code.var/name regression-test))]
                 ["before/after" (when (some? before-after) (clip before-after 240))]
                 ["merge-tx" (fmt-num merge-tx)]]))))

;; Task (:seon.task/entity)

(defn- task-badge [{:seon.task/keys [template? done?]}]
  (cond template? (badge "purple" "template") done? (badge "green" "done") :else (badge "blue" "task")))

(defn render-task-chip
  "Compact chip (~180px) for `:seon.task/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.task/entity :seon.task/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 500000}
  [{{:seon.task/keys [id title] :as t} :seon.task/entity}]
  (chip 180 "ent-task" (task-badge t) (el :seon.task/id id (one-line (or title id) 44) "strong")))

(defn render-task-card
  "Summary card (~360px) for `:seon.task/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.task/entity :seon.task/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1000000}
  [{{:seon.task/keys [id title scope owner needs tests summary plan] :as t} :seon.task/entity :as m}]
  (let [ns' (seq-items needs) ts' (seq-items tests) snip (or (nonblank summary) (nonblank plan))]
    (box "ent-card" 360 "ent-task"
         [(task-badge t) (el :seon.task/id id (one-line (or title id) 90) "strong")]
         (ml :seon.task/id id)
         [:div {:class "pill-row"}
          (when scope (lp :seon.code.ns/name scope (str "scope " scope)))
          (when owner (lp :seon.agent/id owner (str "owner " owner)))]
         (when ns' (pill-list "needs:" ns' :seon.task/id 6))
         (when ts' (pill-list "tests:" ts' :seon.code.var/name 6))
         (when snip [:p {:class "sm-copy"} (hl (one-line snip (bl m 200)) (hl-terms m))]))))

(defn render-task-full
  "Full task card (~560px) for `:seon.task/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.task/entity :seon.task/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1500000}
  [{{:seon.task/keys [id title scope owner created-by template done? plan summary needs tests] :as t}
    :seon.task/entity :as m}]
  (let [terms (hl-terms m) exp? (= :plan (:seon.ui/expand-section m))]
    (box "ent-full" 560 "ent-task"
         [(task-badge t) (el :seon.task/id id (str (or title id)) "strong")]
         (ml :seon.task/id id)
         (kv-dl [["id" (el :seon.task/id id)] ["scope" (when scope (el :seon.code.ns/name scope))]
                 ["owner" (when owner (el :seon.agent/id owner))]
                 ["created-by" (when created-by (if (qualified-keyword? created-by) (el :seon.agent/id created-by) (str created-by)))]
                 ["template" (when template (el :seon.task/id template))] ["done?" (str (boolean done?))]])
         (doc-box plan (bl m (if exp? 2400 1200)) terms)
         (when-let [s (nonblank summary)] [:p {:class "sm-copy muted"} "Summary: " (hl (clip s (bl m 500)) terms)])
         (pill-list "needs:" needs :seon.task/id 12)
         (pill-list "tests:" tests :seon.code.var/name 12))))

;; Schema (:seon.schema/entity)

(defn render-schema-chip
  "Compact chip (~180px) for `:seon.schema/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.schema/entity :seon.schema/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 500000}
  [{{:seon.schema/keys [key type]} :seon.schema/entity}]
  (chip 180 "ent-schema" (badge "purple" (kw-label type "schema")) (el :seon.schema/key key (str key))))

(defn- schema-form-renderer [form]
  (when (and (vector? form) (map? (second form)))
    (let [r (:seon.render/hiccup (second form))]
      (when (qualified-symbol? r) r))))

(defn render-schema-detail
  "Detail card (~460px) for `:seon.schema/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.schema/entity :seon.schema/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1200000}
  [{{:seon.schema/keys [key form doc render type keys optional-keys ref-targets]} :seon.schema/entity :as m}]
  (let [rsym (or (when (qualified-symbol? render) render) (schema-form-renderer form))
        ks (or (seq-items (:seon.ui/schema-keys m)) (seq-items keys))
        oks (or (seq-items (:seon.ui/schema-optional-keys m)) (seq-items optional-keys))
        refs (or (seq-items (:seon.ui/schema-ref-targets m)) (seq-items ref-targets))
        attr-for (fn [xs] (if (every? qualified-keyword? xs) :seon.schema/key :db/id))]
    (box "ent-card" 460 "ent-schema"
         [(badge "purple" (kw-label type "schema")) (el :seon.schema/key key (str key))]
         (ml :seon.code.var/name rsym)
         (doc-box doc (bl m 400) (hl-terms m))
         (kv-dl [["key" (el :seon.schema/key key)] ["type" type]
                 ["renderer" (when rsym (el :seon.code.var/name rsym))]])
         (when ks (pill-list "keys:" ks (attr-for ks) 14))
         (when oks (pill-list "optional-keys:" oks (attr-for oks) 14))
         (when refs (pill-list "ref-targets:" refs (attr-for refs) 10))
         (code-block (fmt-pr form (bl m 900))))))

;; Doc (:seon.code.doc/entity)

(defn render-doc-snippet
  "Compact snippet (~240px) for `:seon.code.doc/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.code.doc/entity :seon.code.doc/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 500000}
  [{{:seon.code.doc/keys [id heading body]} :seon.code.doc/entity :as m}]
  (row 240 "ent-doc" (badge "amber" "doc") (el :seon.code.doc/id id (one-line (or heading id) 60) "strong")
       [:span {:class "muted tiny"} (hl (one-line body 96) (hl-terms m))]))

(defn render-doc-full
  "Full doc card (~520px) for `:seon.code.doc/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.code.doc/entity :seon.code.doc/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1200000}
  [{{:seon.code.doc/keys [id path order heading body references]} :seon.code.doc/entity :as m}]
  (let [drefs (seq-items (:seon.ui/doc-refs m))
        raw-refs (seq-items references)]
    (box "ent-full" 520 "ent-doc"
         [(badge "amber" "doc") (el :seon.code.doc/id id (str (or heading id)) "strong")]
         (el :seon.code.doc/id id (str (or path id)) "muted tiny mono ent-meta")
         (kv-dl [["id" (el :seon.code.doc/id id)]
                 ["path" (when path (el :seon.code.doc/id id path))]
                 ["order" (fmt-num order)]])
         (cond
           drefs (pill-list "references:" drefs :ref-pair 12)
           raw-refs (pill-list "references:" raw-refs :db/id 12)
           :else nil)
         [:div {:class "doc-box sm-copy"} (hl (clip body (bl m 2400)) (hl-terms m))])))

;; Test Verdict (:seon.test/verdict)

(defn render-test-badge
  "Compact badge (~160px) for `:seon.test/verdict`."
  {:malli/schema [:=> [:cat [:map [:seon.test/verdict :seon.test/verdict]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 500000}
  [{{:seon.test/keys [pass? var ms digest]} :seon.test/verdict}]
  (chip 160 "ent-test"
        (el :seon.test/digest digest (if pass? "PASS" "FAIL") (str "badge " (if pass? "green" "red")))
        (el :seon.code.var/name var (str var))
        (when-let [m (fmt-ms ms)] [:span {:class "muted tiny mono"} m])))

(defn render-test-card
  "Detail card (~380px) for `:seon.test/verdict`."
  {:malli/schema [:=> [:cat [:map [:seon.test/verdict :seon.test/verdict]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1000000}
  [{{:seon.test/keys [pass? var kind ms ticks bytes digest report]} :seon.test/verdict :as m}]
  (let [exp? (= :report (:seon.ui/expand-section m))]
    (box "ent-card" 380 "ent-test"
         [(el :seon.test/digest digest (if pass? "PASS" "FAIL") (str "badge " (if pass? "green" "red")))
          (when kind (badge "blue" (kw-label kind "test")))
          (el :seon.code.var/name var (str var))]
         (fmt-ms ms)
         (kv-dl [["var" (el :seon.code.var/name var)] ["duration" (fmt-ms ms)] ["ticks" (fmt-num ticks)]
                 ["bytes" (fmt-num bytes)] ["digest" (when (some? digest) (el :seon.test/digest digest (clip digest 24) "mono"))]])
         (when (some? report) (code-block (fmt-pr report (bl m (if exp? 1400 500))) exp?)))))

;; Eval (:seon.agent.eval/entity)

(defn render-eval-row
  "Compact row (~220px) for `:seon.agent.eval/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.agent.eval/entity :seon.agent.eval/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 600000}
  [{{:seon.agent.eval/keys [id turn handle intent code error ms]} :seon.agent.eval/entity}]
  (row 220 "ent-eval"
       (el :seon.agent.eval/id id (str (or handle (str "t" turn))) (str "badge " (if (nonblank error) "red" "purple")))
       [:span {:class "mono tiny"} (one-line (or (nonblank intent) code) 72)]
       (when-let [m (fmt-ms ms)] [:span {:class "muted tiny mono ent-meta"} m])))

(defn render-eval-card
  "Detail card (~480px) for `:seon.agent.eval/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.agent.eval/entity :seon.agent.eval/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1500000}
  [{{:seon.agent.eval/keys [id agent turn handle result-schema intent code value out error ms ticks bytes
                            prompt-tokens cache-hit-tokens touched]} :seon.agent.eval/entity :as m}]
  (let [err (nonblank error)
        t-refs (seq-items touched)]
    (box "ent-card" 480 "ent-eval"
         [(el :seon.agent.eval/id id (str (or handle (str "turn " turn))) (str "badge " (if err "red" "purple")))
          (el :seon.agent/id agent (str agent))
          (when result-schema (el :seon.schema/key result-schema (str result-schema) "badge blue"))]
         (fmt-ms ms)
         (when-let [i (nonblank intent)] [:p {:class "sm-copy strong"} (hl (clip i 240) (hl-terms m))])
         (code-block (clip code (bl m 800)))
         (if err [:div {:class "notice red mono tiny"} (clip err 500)]
             (when-let [v (nonblank value)] (code-block (clip v (bl m 600)))))
         (when-let [o (nonblank out)] [:pre {:class "code-box tiny muted"} [:code (clip o 300)]])
         (kv-dl [["id" (el :seon.agent.eval/id id)]
                 ["agent" (el :seon.agent/id agent)]
                 ["result-schema" (when result-schema (el :seon.schema/key result-schema))]
                 ["turn" (el :seon.agent.eval/id id (str "T" turn))]
                 ["ticks" (fmt-num ticks)] ["bytes" (fmt-num bytes)]
                 ["prompt-tokens" (fmt-num prompt-tokens)] ["cache-hit-tokens" (fmt-num cache-hit-tokens)]])
         (when t-refs (pill-list "touched:" t-refs :ref 10)))))

;; Delegation Call (:seon.agent.call/entity)

(defn- call-st-cls [st] (case st :resolved "green" :failed "red" :cancelled "muted" "amber"))

(defn render-call-row
  "Compact row (~220px) for `:seon.agent.call/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.agent.call/entity :seon.agent.call/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 500000}
  [{{:seon.agent.call/keys [id caller callee status prompt]} :seon.agent.call/entity}]
  (row 220 "ent-call"
       (el :seon.agent.call/id id (kw-label status "pending") (str "badge " (call-st-cls status)))
       (el :seon.agent/id caller (str caller) "mono tiny strong")
       [:span {:class "muted tiny"} "→"]
       (el :seon.agent/id callee (str callee) "mono tiny strong")
       [:span {:class "muted tiny"} (one-line prompt 60)]))

(defn render-call-card
  "Detail card (~440px) for `:seon.agent.call/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.agent.call/entity :seon.agent.call/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1000000}
  [{{:seon.agent.call/keys [id caller callee status prompt result-schema result error parent-call]} :seon.agent.call/entity :as m}]
  (box "ent-card" 440 "ent-call"
       [(el :seon.agent.call/id id (kw-label status "pending") (str "badge " (call-st-cls status)))
        (el :seon.agent/id caller (str caller)) [:span {:class "muted"} "→"] (el :seon.agent/id callee (str callee))]
       (when result-schema (ml :seon.schema/key result-schema))
       (doc-box prompt (bl m 480) (hl-terms m))
       (kv-dl [["id" (el :seon.agent.call/id id)]
               ["caller" (el :seon.agent/id caller)]
               ["callee" (el :seon.agent/id callee)]
               ["result-schema" (when result-schema (el :seon.schema/key result-schema))]
               ["parent-call" (when parent-call (el :seon.agent.call/id parent-call))]])
       (when (some? result) (code-block (fmt-pr result (bl m 480))))
       (when (some? error) [:div {:class "notice red mono tiny"} (fmt-pr error 320)])))

;; Namespace (:seon.code.ns/entity)

(defn render-ns-chip
  "Compact chip (~160px) for `:seon.code.ns/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.code.ns/entity :seon.code.ns/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 500000}
  [{{:seon.code.ns/keys [name]} :seon.code.ns/entity}]
  (chip 160 "ent-ns" (badge "blue" "ns") (el :seon.code.ns/name name (str name))))

(defn render-ns-card
  "Summary card (~400px) for `:seon.code.ns/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.code.ns/entity :seon.code.ns/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1000000}
  [{{:seon.code.ns/keys [name path doc requires]} :seon.code.ns/entity :as m}]
  (let [reqs (seq-items requires)]
    (box "ent-card" 400 "ent-ns"
         [(badge "blue" "ns") (el :seon.code.ns/name name (str name))]
         (when path (el :seon.code.ns/name name (str path) "muted tiny mono ent-meta"))
         (doc-box doc (bl m 500) (hl-terms m))
         (kv-dl [["namespace" (el :seon.code.ns/name name)]
                 ["path" (when path (el :seon.code.ns/name name (str path)))]])
         (when reqs (pill-list "requires:" reqs :seon.code.ns/name 14)))))

;; Agent (:seon.agent/entity)

(defn- agent-state-label [{:seon.agent/keys [archived? paused? ready? waiting? merged-tx]}]
  (cond archived? ["muted" "archived"] merged-tx ["green" "merged"] paused? ["muted" "paused"]
        ready? ["green" "ready"] waiting? ["amber" "waiting"] :else ["blue" "active"]))

(defn render-agent-row
  "Compact row (~200px) for `:seon.agent/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.agent/entity :seon.agent/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 500000}
  [{{:seon.agent/keys [id label role] :as ag} :seon.agent/entity}]
  (let [[scls slbl] (agent-state-label ag)]
    (row 200 "ent-agent" (badge "blue" (kw-label role "worker"))
         (el :seon.agent/id id (str (or label id))) (badge scls slbl))))

(defn render-agent-card
  "Detail card (~440px) for `:seon.agent/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.agent/entity :seon.agent/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1200000}
  [{{:seon.agent/keys [id label role branch scope task parent goal pinned memory] :as ag} :seon.agent/entity :as m}]
  (let [[scls slbl] (agent-state-label ag) pins (seq-items pinned) mem (seq-items memory) br (or branch :main)]
    (box "ent-card" 440 "ent-agent"
         [(badge "blue" (kw-label role "worker")) (el :seon.agent/id id (str id)) (badge scls slbl)]
         (ml :seon.branch/name br)
         (when-let [g (nonblank goal)] [:p {:class "sm-copy"} (hl (clip g 360) (hl-terms m))])
         (kv-dl [["label" (when label (el :seon.agent/id id label))]
                 ["branch" (el :seon.branch/name br)]
                 ["scope" (when scope (el :seon.code.ns/name scope))]
                 ["task" (when task (el :seon.task/id task))]
                 ["parent" (when parent (el :seon.agent/id parent))]
                 ["memory" (when mem (str (count mem) " note(s)"))]])
         (when pins (pill-list "pinned:" pins :ref 10)))))

;; Rule (:seon.rule/entity)

(defn render-rule-chip
  "Compact chip (~180px) for `:seon.rule/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.rule/entity :seon.rule/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 500000}
  [{{:seon.rule/keys [id stratum enabled?]} :seon.rule/entity}]
  (chip 180 "ent-rule" (badge (if enabled? "green" "muted") (str "s" stratum)) (el :seon.rule/id id (str id))))

(defn render-rule-detail
  "Detail card (~480px) for `:seon.rule/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.rule/entity :seon.rule/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1200000}
  [{{:seon.rule/keys [id doc category stratum enabled? target query assert]} :seon.rule/entity :as m}]
  (let [r-schs (seq-items (:seon.ui/rule-schemas m))
        r-fns (seq-items (:seon.ui/rule-fns m))]
    (box "ent-card" 480 "ent-rule"
         [(badge (if enabled? "green" "muted") (if enabled? "enabled" "disabled"))
          (badge "amber" (str "stratum " stratum))
          (when category (badge "blue" (kw-label category "rule")))
          (el :seon.rule/id id (str id))]
         (when target (str "target " target))
         (doc-box doc (bl m 400) (hl-terms m))
         (kv-dl [["id" (el :seon.rule/id id)] ["stratum" (fmt-num stratum)] ["category" category] ["target" target]])
         (when r-schs (pill-list "schemas:" r-schs :seon.schema/key 10))
         (when r-fns (pill-list "fns:" r-fns :seon.code.var/name 10))
         (when (some? query) (code-block (fmt-pr query (bl m 700))))
         (when (some? assert) (code-block (fmt-pr assert (bl m 500)))))))

;; Ephemeral Tail Blocks (delegating to `seon.render.tails`)

(defn render-branch-status-tail
  "Pure `ns-map -> ns-map` Hiccup renderer for branch delta, turn budget, proofs, and branch errors."
  {:malli/schema [:=> [:cat [:map [:seon.tail/branch-status :map]]] [:maybe [:map [:seon.ui/hiccup :seon.ui/hiccup]]]]
   :cost/ticks 500000}
  [m]
  (rtails/render-branch-status-tail m))

(defn render-scoped-health-tail
  "Pure `ns-map -> ns-map` Hiccup renderer for scope health alerts (nil when clean)."
  {:malli/schema [:=> [:cat [:map [:seon.tail/scoped-health :map]]] [:maybe [:map [:seon.ui/hiccup :seon.ui/hiccup]]]]
   :cost/ticks 500000}
  [m]
  (rtails/render-scoped-health-tail m))

(defn render-activity-tail
  "Pure `ns-map -> ns-map` Hiccup renderer for open calls, sub-agents, errors, and live records."
  {:malli/schema [:=> [:cat [:map [:seon.tail/activity :map]]] [:maybe [:map [:seon.ui/hiccup :seon.ui/hiccup]]]]
   :cost/ticks 1000000}
  [m]
  (rtails/render-activity-tail m render-error-badge))

(defn render-inbox-tail
  "Pure `ns-map -> ns-map` Hiccup renderer for unread inbox messages."
  {:malli/schema [:=> [:cat [:map [:seon.tail/inbox :map]]] [:maybe [:map [:seon.ui/hiccup :seon.ui/hiccup]]]]
   :cost/ticks 500000}
  [m]
  (rtails/render-inbox-tail m))

(defn render-system-health-tail
  "Pure `ns-map -> ns-map` Hiccup renderer for ambient operational signals & system health."
  {:malli/schema [:=> [:cat [:map [:seon.tail/system-health :map]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 500000}
  [m]
  (rtails/render-system-health-tail m))

(defn render-viewport-tail
  "Pure `ns-map -> ns-map` Hiccup renderer for live WKWebView screenshot & DOM metadata."
  {:malli/schema [:=> [:cat [:map [:seon.tail/viewport :map]]] [:maybe [:map [:seon.ui/hiccup :seon.ui/hiccup]]]]
   :cost/ticks 500000}
  [m]
  (rtails/render-viewport-tail m))
