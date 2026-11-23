(ns seon.render.tails
  "Pure `ns-map -> ns-map` hyperlinked Hiccup renderers for ephemeral tail blocks
   (branch status, scoped health, neighborhood activity, unread inbox, system health, live viewport).
   Emits `.ent-link` buttons so browser views stay 100% clickable while `render/hiccup->text`
   emits crisp plain text for the LLM wire."
  (:require [clojure.string :as str]
            [seon.render.links :as rlinks]))

(defn- clip [x n]
  (let [s (if (nil? x) "" (str x))]
    (if (> (count s) n) (str (subs s 0 (max 1 (dec n))) "…") s)))

(defn- one-line [x n]
  (clip (-> (if (nil? x) "" (str x)) (str/replace #"\s+" " ") str/trim) n))

(defn- kw-label [x fb]
  (cond (ident? x) (name x) (some? x) (str x) :else fb))

(defn- safe-el [a v lbl cls]
  (if (and (qualified-keyword? a) (some? v))
    (rlinks/ent-link [a v] lbl cls)
    [:span {:class (or cls "")} (str lbl)]))

(defn- form-key-link [fk]
  (if (string? fk)
    (rlinks/ent-link [:seon.code.form/key fk] fk "mono tiny")
    [:span {:class "mono tiny"} (str fk)]))

(defn- subject-link [e]
  (cond
    (qualified-symbol? (:var e)) (rlinks/ent-link [:seon.code.var/name (:var e)] (str (or (:subject e) (:var e))) "mono tiny")
    (symbol? (:ns e)) (rlinks/ent-link [:seon.code.ns/name (:ns e)] (str (or (:subject e) (:ns e))) "mono tiny")
    :else [:span {:class "mono tiny"} (str (:subject e))]))

(defn render-branch-status-tail
  "Pure `ns-map -> ns-map` Hiccup renderer for branch delta, turn budget, proofs, and branch errors."
  {:malli/schema [:=> [:cat [:map [:seon.tail/branch-status :map]]] [:maybe [:map [:seon.ui/hiccup :seon.ui/hiccup]]]]
   :cost/ticks 500000}
  [{{:keys [branch max-tx branch-edited main-drifted branch-errors last-proofs show-budget? turns-used max-turns rem-turns]}
    :seon.tail/branch-status}]
  (let [ed-v (when (and (coll? branch-edited) (not (map? branch-edited))) (vec (sort-by str branch-edited)))
        dr-v (when (and (coll? main-drifted) (not (map? main-drifted))) (vec (sort-by str main-drifted)))
        be-v (when (and (coll? branch-errors) (not (map? branch-errors))) (seq branch-errors))]
    (when (or (seq ed-v) be-v (map? last-proofs) (seq dr-v) show-budget?)
      (let [n (count ed-v)
            sync-s (if (empty? dr-v) "up-to-date" (str (count dr-v) " form(s) moved on :main"))]
        {:tag "branch-status"
         :seon.ui/hiccup
         [:section {:class "tail-block" :data-min-width 240}
          [:p "branch=" (if (keyword? branch) (safe-el :seon.branch/name branch (str branch) "mono") (str branch))
           (str " @ tx " max-tx " | edited-forms=" n)
           (when (pos? n)
             (concat [" ("] (interpose ", " (map form-key-link (take 6 ed-v)))
                     [(when (> n 6) (str " +" (- n 6) " more")) ")"]))
           (str " | main-sync=" sync-s)]
          (when show-budget?
            [:p (str "TURN BUDGET: turn " turns-used " / " max-turns " (" rem-turns " remaining).")])
          (when (seq dr-v)
            [:p "MAIN DRIFT: " (rlinks/ent-link [:seon.branch/name :main] ":main" "mono") " updated "
             (interpose ", " (map form-key-link (take 5 dr-v)))])
          (when (map? last-proofs)
            [:p (str "AUTO-PROOFS (tx " (:tx last-proofs) "): " (if (:ok? last-proofs) "PASS" "FAIL")
                     " (" (:passed last-proofs 0) "/" (:ran last-proofs 0) " in " (:ms last-proofs 0.0) " ms)")])
          (when be-v
            [:div [:p (str "MERGE-BLOCKING HEALTH ERRORS (" (count be-v) "):")]
             (into [:ul] (for [e (take 5 be-v) :when (map? e)]
                           [:li (str "[:" (name (or (:class e) :error)) "] ")
                            (subject-link e) (str " — " (:message e))]))])]}))))

(defn render-scoped-health-tail
  "Pure `ns-map -> ns-map` Hiccup renderer for scope health alerts (nil when clean)."
  {:malli/schema [:=> [:cat [:map [:seon.tail/scoped-health :map]]] [:maybe [:map [:seon.ui/hiccup :seon.ui/hiccup]]]]
   :cost/ticks 500000}
  [{{:keys [scope problems]} :seon.tail/scoped-health}]
  (when-let [ps (when (and (coll? problems) (not (map? problems))) (seq problems))]
    {:tag "scoped-health"
     :seon.ui/hiccup
     [:section {:class "tail-block" :data-min-width 220}
      [:p "Health & efficiency alerts for "
       (if (symbol? scope) (safe-el :seon.code.ns/name scope (str scope) "mono") (str scope)) ":"]
      (into [:ul] (for [p (take 6 ps) :when (map? p)]
                    [:li (str "[" (name (or (:severity p) :warning)) " :" (name (or (:class p) :issue)) "] ")
                     (subject-link p)
                     (str " — " (:message p)
                          (when (:fix p) " (auto-fixable via (user/autofix-health!))"))]))]}))

(defn- error-badge-mini [e]
  (let [id (:seon.error/id e) kind (:seon.error/kind e) sev (or (:seon.error/severity e) :error)
        lbl (str (if (= :warning sev) "WARN " "ERR ") (kw-label kind "error"))]
    [:span {:class (str "badge " (if (= :warning sev) "amber" "red"))}
     (if (uuid? id) (safe-el :seon.error/id id lbl "mono") lbl)]))

(defn render-activity-tail
  "Pure `ns-map -> ns-map` Hiccup renderer for open calls, sub-agents, errors, and live records."
  {:malli/schema [:=> [:cat [:map [:seon.tail/activity :map]]] [:maybe [:map [:seon.ui/hiccup :seon.ui/hiccup]]]]
   :cost/ticks 1000000}
  [{{:keys [scope incoming outgoing open-errors samples]} :seon.tail/activity}]
  (let [e-hs (when (and (coll? open-errors) (not (map? open-errors)))
               (keep #(when (and (map? %) (contains? % :seon.error/kind)) (error-badge-mini %))
                     (take 4 open-errors)))
        inc-v (when (and (coll? incoming) (not (map? incoming))) (seq incoming))
        out-v (when (and (coll? outgoing) (not (map? outgoing))) (seq outgoing))
        smp-v (when (and (coll? samples) (not (map? samples))) (seq samples))]
    (when (or inc-v out-v (seq e-hs) smp-v)
      {:tag "neighborhood-activity"
       :seon.ui/hiccup
       [:section {:class "tail-block" :data-min-width 240}
        (when inc-v
          [:div [:p "Open incoming calls to answer via (user/answer! call-id val):"]
           (into [:ul]
                 (for [c inc-v :when (map? c)
                       :let [cid (:seon.agent.call/id c)
                             caller (:seon.agent.call/caller c)
                             rs (:seon.agent.call/result-schema c)]]
                   [:li
                    (if (uuid? cid) (safe-el :seon.agent.call/id cid (pr-str cid) "mono tiny") (pr-str cid))
                    " from "
                    (if (keyword? caller) (safe-el :seon.agent/id caller (str caller) "mono tiny") (str caller))
                    (when (qualified-keyword? rs)
                      (list " schema=" (safe-el :seon.schema/key rs (str rs) "mono tiny")))
                    (str ": " (one-line (:seon.agent.call/prompt c) 100))]))])
        (when out-v
          [:div [:p "Outgoing delegated calls:"]
           (into [:ul]
                 (for [c out-v :when (map? c)
                       :let [callee (:seon.agent.call/callee c)]]
                   [:li
                    (if (keyword? callee) (safe-el :seon.agent/id callee (str callee) "mono tiny") (str callee))
                    (str " [" (kw-label (:seon.agent.call/status c) "pending") "]: "
                         (one-line (:seon.agent.call/prompt c) 80))]))])
        (when (seq e-hs) (into [:div [:p "Open errors:"]] e-hs))
        (when smp-v
          [:div [:p "Recent live data matching "
                 (if (symbol? scope) (safe-el :seon.code.ns/name scope (str scope) "mono") (str scope))
                 " schemas:"]
           (into [:ul]
                 (for [s smp-v :when (map? s)
                       :let [{:keys [attr tx eid preview]} s]]
                   [:li (str "tx " tx " (")
                    (if (qualified-keyword? attr) (safe-el :seon.schema/key attr (str attr) "mono tiny") (str attr))
                    "): "
                    (if (integer? eid) (safe-el :db/id eid (str preview) "mono tiny") (str preview))]))])]})))

(defn render-inbox-tail
  "Pure `ns-map -> ns-map` Hiccup renderer for unread inbox messages."
  {:malli/schema [:=> [:cat [:map [:seon.tail/inbox :map]]] [:maybe [:map [:seon.ui/hiccup :seon.ui/hiccup]]]]
   :cost/ticks 500000}
  [{{:keys [unread]} :seon.tail/inbox}]
  (when-let [us (when (and (coll? unread) (not (map? unread))) (seq unread))]
    {:tag "unread-inbox"
     :seon.ui/hiccup
     [:section {:class "tail-block" :data-min-width 200}
      [:p (str "Unread inbox (" (count us) "):")]
      (into [:ul] (for [m (take 6 us) :when (map? m)
                        :let [from (or (:seon.agent.message/from m) :user)]]
                    [:li
                     (if (qualified-keyword? from)
                       (safe-el :seon.agent/id from (str from) "mono tiny")
                       (str from))
                     (str " [" (kw-label (:seon.agent.message/type m) "chat") "]: "
                          (one-line (:seon.agent.message/body m) 120))]))]}))

(defn render-system-health-tail
  "Pure `ns-map -> ns-map` Hiccup renderer for ambient operational signals & system health."
  {:malli/schema [:=> [:cat [:map [:seon.tail/system-health :map]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 500000}
  [{snap :seon.tail/system-health}]
  (let [{:keys [level branch flow pressure context proof health]} snap
        sig-li (fn [k lbl m]
                 [:li (rlinks/ent-link [:seon.signal/kind k] lbl "mono strong")
                  (str ": " (:summary m))])]
    {:tag "system-health"
     :seon.ui/hiccup
     [:section {:class "tail-block" :data-min-width 240}
      [:p "System health ("
       (if (keyword? branch) (safe-el :seon.branch/name branch (kw-label branch "main") "mono") (kw-label branch "main"))
       (str "): " (if (:ok? health) "OK" "ERRORS")
            " · level=" (kw-label level "calm") " · " (:summary health))]
      [:ul
       (sig-li :flow "Flow" flow)
       (sig-li :pressure "Pressure" pressure)
       (sig-li :context "Context" context)
       (sig-li :proof "Proof" proof)]]}))

(defn render-viewport-tail
  "Pure `ns-map -> ns-map` Hiccup renderer for live WKWebView screenshot & DOM metadata."
  {:malli/schema [:=> [:cat [:map [:seon.tail/viewport :map]]] [:maybe [:map [:seon.ui/hiccup :seon.ui/hiccup]]]]
   :cost/ticks 500000}
  [{{:keys [snapshot enabled? vision?]} :seon.tail/viewport}]
  (when (and (map? snapshot) enabled?)
    (let [dom (:dom snapshot)
          dom-s (when (map? dom)
                  (not-empty (str/join " · "
                                       (remove nil? [(when-not (str/blank? (str (:viewing dom))) (str "viewing=" (:viewing dom)))
                                                     (when-not (str/blank? (str (:mode dom))) (str "mode=" (:mode dom)))
                                                     (when (:theme dom) (str "theme=" (:theme dom)))
                                                     (when (some? (:overflowX dom)) (str "overflowX=" (:overflowX dom)))
                                                     (when (some? (:svgCount dom)) (str "svgs=" (:svgCount dom)))]))))]
      (cond-> {:tag "live-viewport"
               :attrs {:route (:route snapshot "/") :size (str (:width snapshot 1280) "x" (:height snapshot 820))}
               :seon.ui/hiccup
               [:section {:class "tail-block" :data-min-width 220}
                [:p "Current live WKWebView screenshot (ephemeral end-of-context frame)."]
                (when dom-s [:p (str "DOM: " dom-s)])]}
        (and vision? (:base64 snapshot))
        (assoc :image {:mime (or (:mime snapshot) "image/jpeg") :base64 (:base64 snapshot) :raw snapshot})))))
