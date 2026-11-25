(ns seon.render.entities.diagnostics
  "Pure `ns-map -> ns-map` dual-footprint Hiccup renderers for `:seon.health/friction` and `:seon.bench/scoreboard`."
  (:require [clojure.string :as str]
            [seon.render.links :as rlinks]
            [seon.render.text :refer [one-line round1]]
            [seon.render.entities.prim :as prim :refer [chip ent-box]]))

(defn- tone [lvl]
  (case lvl :hot "red" :warm "amber" :calm "green" "slate"))

(defn- fmt-delta [v suffix lower-better?]
  (when (number? v)
    (let [d (round1 (double v))
          sgn (if (pos? d) "+" "")
          t (cond (zero? d) "slate"
                  (if lower-better? (neg? d) (pos? d)) "green"
                  :else "red")]
      (rlinks/badge t (str sgn d suffix)))))

(defn- short-sha [s]
  (when (and s (not (str/blank? (str s))))
    (let [x (str s)] (subs x 0 (min 7 (count x))))))

(defn render-friction-chip
  "Compact chip (~180px) for `:seon.health/friction`."
  {:malli/schema [:=> [:cat [:map [:seon.health/friction :seon.health/friction]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 400000}
  [{f :seon.health/friction}]
  (let [{:keys [level agents-scanned totals findings]} f
        kv (:kv-cache-hit-pct totals)
        ooms (long (or (:ooms totals) 0))
        ovs (long (or (:turn-overruns totals) 0))]
    (chip 180 "ent-friction"
          (rlinks/badge (tone level) (str "friction " (name (or level :calm))))
          (rlinks/mut (str (or agents-scanned 0) "ag"))
          (when (number? kv) (rlinks/mut (str (round1 kv) "% KV")))
          (when (pos? ooms) (rlinks/badge "red" (str ooms " OOM")))
          (when (pos? ovs) (rlinks/badge "red" (str ovs " overrun")))
          (when (seq findings) (rlinks/mut (str (count findings) "f"))))))

(defn render-friction-card
  "Detailed card (~440px) for `:seon.health/friction` showing totals, top agent friction rows, and findings."
  {:malli/schema [:=> [:cat [:map [:seon.health/friction :seon.health/friction]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1200000}
  [{f :seon.health/friction}]
  (let [{:keys [level summary agents-scanned totals agents findings ms]} f
        t (or totals {})
        kv (:kv-cache-hit-pct t)
        f-prev (take 10 findings)
        more-f (- (count findings) (count f-prev))]
    (ent-box 440 "ent-friction"
         [(rlinks/badge (tone level) (str "friction · " (name (or level :calm))))
          [:span {:class "strong"} (str (or agents-scanned 0) " agents")]
          (when (pos? (long (or (:ooms t) 0))) (rlinks/badge "red" (str (:ooms t) " OOM")))
          (when (pos? (long (or (:turn-overruns t) 0))) (rlinks/badge "red" (str (:turn-overruns t) " overrun")))]
         (when (number? ms) (str (round1 ms) " ms"))
         (when (and summary (not (str/blank? summary)))
           [:div {:class "sm-copy muted"} summary])
         (rlinks/kv-dl
          [["evals / turns" (str (or (:evals t) 0) " / " (or (:turns t) 0))]
           ["KV hit %" (if (number? kv) (str (round1 kv) "%") "—")]
           ["OOM / overruns" (str (or (:ooms t) 0) " / " (or (:turn-overruns t) 0))]
           ["empty / busts" (str (or (:empty-code-turns t) 0) " / " (or (:prefix-busts t) 0))]
           ["upstream / rogue" (str (or (:upstream-errors t) 0) " / " (or (:rogue-fs-count t) 0))]
           ["no-match / CST" (str (or (:no-match-count t) 0) " / " (or (:cst-errors t) 0))]
           ["renderers / debt" (str (or (:missing-renderers t) 0) " / " (or (:static-debt t) 0))]])
         (when (seq agents)
           [:div {:class "stack-xs"}
            [:div {:class "kicker tiny muted"} (str "Agents (" (count agents) ")")]
            (into [:div {:class "stack-xs"}]
                  (for [a agents]
                    [:div {:class "pill-row tiny"}
                     (rlinks/badge (tone (:level a)) (str (:friction-score a)))
                     (rlinks/ent-link :seon.agent/id (:agent a) (str (:agent a)) "mono")
                     (rlinks/mut (str (:turns a) "t/" (:evals a) "e"))
                     (when (number? (:kv-cache-hit-pct a)) (rlinks/mut (str (round1 (:kv-cache-hit-pct a)) "%")))
                     (when (:turn-overrun? a) (rlinks/badge "red" "overrun"))
                     (when (pos? (long (or (:ooms a) 0))) (rlinks/badge "red" (str (:ooms a) " OOM")))
                     (when (pos? (long (or (:rogue-fs-count a) 0))) (rlinks/badge "red" "rogue"))
                     (when (pos? (long (or (:eval-errors a) 0))) (rlinks/badge "amber" (str (:eval-errors a) " err")))
                     (when (:last-error a) [:span {:class "muted mono tiny"} (one-line (:last-error a) 64)])]))])
         (when (seq f-prev)
           [:div {:class "stack-xs"}
            [:div {:class "kicker tiny muted"} (str "Findings (" (count findings) ")")]
            (into [:div {:class "stack-xs"}]
                  (for [fd f-prev]
                    [:div {:class "pill-row tiny"}
                     (rlinks/badge "slate" (name (or (:kind fd) :finding)))
                     [:span {:class "mono tiny"} (str "i" (or (:impact fd) 0))]
                     [:span (one-line (or (:title fd) (:summary fd) (str (:id fd))) 72)]]))
            (when (pos? more-f)
              [:div {:class "muted tiny"} (str "… " more-f " more findings")])]))))

(defn render-golden-scoreboard-chip
  "Compact chip (~180px) for `:seon.bench/scoreboard`."
  {:malli/schema [:=> [:cat [:map [:seon.bench/scoreboard :seon.bench/scoreboard]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 400000}
  [{sb :seon.bench/scoreboard}]
  (let [{:keys [suite-id level runs-count merge-rate-pct median-turns-to-merge kv-cache-hit-pct delta-vs-prev]} sb]
    (chip 180 "ent-scoreboard"
          (rlinks/badge (tone level) (str (or (some-> suite-id name) "golden")))
          (if (pos? (long (or runs-count 0)))
            (rlinks/mut (str (round1 (or merge-rate-pct 0.0)) "% merge"))
            (rlinks/mut "0 runs"))
          (when (number? median-turns-to-merge) (rlinks/mut (str (round1 median-turns-to-merge) "t")))
          (when (number? kv-cache-hit-pct) (rlinks/mut (str (round1 kv-cache-hit-pct) "% KV")))
          (fmt-delta (:merge-rate-pct delta-vs-prev) "%" false))))

(defn render-golden-scoreboard-card
  "Detailed card (~460px) for `:seon.bench/scoreboard` with KPIs, delta vs previous run, and commit history."
  {:malli/schema [:=> [:cat [:map [:seon.bench/scoreboard :seon.bench/scoreboard]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1200000}
  [{sb :seon.bench/scoreboard}]
  (let [{:keys [suite-id level summary runs-count merge-rate-pct overall-pass-rate-pct
                median-turns-to-merge median-wall-s kv-cache-hit-pct upstream-errors
                livelocks timeouts ooms delta-vs-prev history by-category]} sb
        deltas (when (map? delta-vs-prev)
                 (remove nil?
                         [(when (number? (:merge-rate-pct delta-vs-prev))
                            [:span {:class "pill-row tiny"} (rlinks/mut "Δmerge") (fmt-delta (:merge-rate-pct delta-vs-prev) "%" false)])
                          (when (number? (:median-turns-to-merge delta-vs-prev))
                            [:span {:class "pill-row tiny"} (rlinks/mut "Δturns") (fmt-delta (:median-turns-to-merge delta-vs-prev) "t" true)])
                          (when (number? (:kv-cache-hit-pct delta-vs-prev))
                            [:span {:class "pill-row tiny"} (rlinks/mut "ΔKV") (fmt-delta (:kv-cache-hit-pct delta-vs-prev) "%" false)])
                          (when (number? (:ooms delta-vs-prev))
                            [:span {:class "pill-row tiny"} (rlinks/mut "ΔOOM") (fmt-delta (:ooms delta-vs-prev) "" true)])
                          (when (number? (:livelocks delta-vs-prev))
                            [:span {:class "pill-row tiny"} (rlinks/mut "Δlive") (fmt-delta (:livelocks delta-vs-prev) "" true)])
                          (when (number? (:timeouts delta-vs-prev))
                            [:span {:class "pill-row tiny"} (rlinks/mut "Δtimeout") (fmt-delta (:timeouts delta-vs-prev) "" true)])]))]
    (ent-box 460 "ent-scoreboard"
         [(rlinks/badge (tone level) (str "scoreboard · " (or suite-id :golden/v1)))
          (rlinks/badge (if (>= (double (or merge-rate-pct 0.0)) 80.0) "green" "amber")
                        (str (round1 (or merge-rate-pct 0.0)) "% merge"))
          (when (pos? (long (or ooms 0))) (rlinks/badge "red" (str ooms " OOM")))
          (when (pos? (long (or livelocks 0))) (rlinks/badge "red" (str livelocks " livelock")))
          (when (pos? (long (or timeouts 0))) (rlinks/badge "amber" (str timeouts " timeout")))]
         (str (or runs-count 0) " runs")
         (when (and summary (not (str/blank? summary)))
           [:div {:class "sm-copy muted"} summary])
         (rlinks/kv-dl
          [["merge / pass %" (str (round1 (or merge-rate-pct 0.0)) "% / " (round1 (or overall-pass-rate-pct 0.0)) "%")]
           ["median turns" (if (number? median-turns-to-merge) (str (round1 median-turns-to-merge) " t") "—")]
           ["median wall" (if (number? median-wall-s) (str (round1 median-wall-s) " s") "—")]
           ["KV cache hit %" (if (number? kv-cache-hit-pct) (str (round1 kv-cache-hit-pct) "%") "—")]
           ["upstream / live / to / OOM" (str (or upstream-errors 0) " / " (or livelocks 0) " / " (or timeouts 0) " / " (or ooms 0))]])
         (when (seq deltas)
           (into [:div {:class "pill-row tiny"} [:span {:class "kicker tiny muted"} "vs prev"]] deltas))
         (when (seq by-category)
           [:div {:class "stack-xs"}
            [:div {:class "kicker tiny muted"} "By category"]
            (into [:div {:class "pill-row tiny"}]
                  (for [[cat m] (sort-by (comp str key) by-category)]
                    (rlinks/badge (if (= (:passed m) (:total m)) "green" "slate")
                                  (str (name cat) " " (or (:passed m) 0) "/" (or (:total m) 0)))))])
         (when (seq history)
           [:div {:class "stack-xs"}
            [:div {:class "kicker tiny muted"} (str "Commit history (" (count history) ")")]
            (into [:div {:class "stack-xs"}]
                  (for [h (reverse history)]
                    [:div {:class "pill-row tiny mono"}
                     (rlinks/badge (case (:status h) :passed "green" :partial "amber" "red")
                                   (or (short-sha (:git-commit h)) (str "tx" (or (:main-tx h) "?"))))
                     [:span (str (round1 (or (:merge-rate-pct h) 0.0)) "%m/" (round1 (or (:overall-pass-rate-pct h) 0.0)) "%p")]
                     (when (number? (:median-turns-to-merge h)) (rlinks/mut (str (round1 (:median-turns-to-merge h)) "t")))
                     (when (number? (:kv-cache-hit-pct h)) (rlinks/mut (str (round1 (:kv-cache-hit-pct h)) "%KV")))
                     (when (:model h) (rlinks/mut (one-line (:model h) 20)))
                     (when (pos? (long (or (:ooms h) 0))) (rlinks/badge "red" (str (:ooms h) " OOM")))
                     (when (pos? (long (or (:livelocks h) 0))) (rlinks/badge "red" (str (:livelocks h) " live")))
                     (when (pos? (long (or (:timeouts h) 0))) (rlinks/badge "amber" (str (:timeouts h) " to")))]))]))))