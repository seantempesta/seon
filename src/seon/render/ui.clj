(ns seon.render.ui
  "The HTML renders of root's web: render functions all the way up (DECISIONS, \"Renders,
  the agent UI and context\"; W1's proto.render.ui). Ported from proto/web's
  proto.web.view (lane L2's dashboard and L4's polish): layout, tiles, swarm grid, cards,
  event stream, the REPL box per card, the Phosphor theme's classes (resources/public/app.css).

  Two layouts are declared renders: `system-page` (:seon.render/system, root's whole
  system) and `agent-page` (:seon.render/agent-page, one agent zoomed). A layout answers
  its skeleton, with a slot where each part goes, and :seon.render/units: each part's id,
  the exact value it reads (its input) and the render that shows it. The render proc
  (seon.render.proc) renders a unit only when its input changed, and sends a patch only
  when its bytes changed. Values inside a part render by their own schema's declared
  render (seon.render/render): an agent's card, a task, an attempt, an evaluation, a proc's
  health, a cost verdict; anything else by the one value printer.

  Every number shown is measured by the system and carried in the value; nothing here
  invents one."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [clojure.string :as str] [clojure.edn :as edn] [clojure.walk]
            [seon.render :as render] [seon.print :as print] [seon.print.select :as select] [seon.web.html :as h] [seon.reader :as reader]))

;;; --- small formatting helpers -------------------------------------------------

(defn dom-id
  "A DOM-safe id for an id keyword or string: :a12 -> \"a12\", :fx/model -> \"fx_model\"."
  {:malli/schema [:=> [:cat :any] :string]}
  [id]
  (let [s (if (keyword? id) (subs (str id) 1) (str id))]
    (apply str (map #(if (or (Character/isLetterOrDigit ^char %) (= \_ %) (= \- %)) % \_) s))))

(defn- kname {:malli/schema [:=> [:cat :any] :string]}
  [k] (cond (keyword? k) (subs (str k) 1) (nil? k) "–" :else (str k)))

(defn fmt1 {:malli/schema [:=> [:cat :any] :string]} [x] (if (number? x) (format "%.1f" (double x)) "–"))
(defn fmt2 {:malli/schema [:=> [:cat :any] :string]} [x] (if (number? x) (format "%.2f" (double x)) "–"))

(defn group3
  "n with thousands separated: 120000 -> \"120,000\"."
  {:malli/schema [:=> [:cat :any] :string]}
  [n]
  (if (number? n)
    (let [s (str (long n))
          neg? (str/starts-with? s "-")
          s (if neg? (subs s 1) s)
          g (->> (reverse s) (partition-all 3) (map (comp str/join reverse)) reverse (str/join ","))]
      (str (when neg? "-") g))
    "–"))

(defn bytes-str {:malli/schema [:=> [:cat :any] :string]}
  [b]
  (cond (not (number? b)) "–"
        (< b 1024) (str (long b) " B")
        (< b (* 1024 1024)) (str (fmt1 (/ b 1024.0)) " KB")
        :else (str (fmt1 (/ b 1048576.0)) " MB")))

(defn ms-str {:malli/schema [:=> [:cat :any] :string]}
  [ms] (cond (not (number? ms)) "–" (< ms 1) (str (fmt2 ms) " ms") (< ms 100) (str (fmt1 ms) " ms") :else (str (long ms) " ms")))

(defn prose
  "Text on the page as the one printer's prose (seon.print/prose) under budget tokens: whole,
  or its shown part and the cut, which says what it omitted and where the rest is: a link
  to opts :href (the page that shows it whole), else the cut's title holds the whole text.
  opts :one-line? as the printer's (a table cell, a stream row)."
  {:malli/schema [:function [:=> [:cat :any pos-int?] :any] [:=> [:cat :any pos-int? :map] :any]]}
  ([s budget] (prose s budget {}))
  ([s budget {:keys [href one-line?]}]
   (let [s (if (string? s) s (str s))
         {:keys [shown truncated? omitted lines]} (print/prose s budget {:one-line? (boolean one-line?)})]
     (if-not truncated?
       shown
       (let [words (print/cut-words omitted lines nil)
             cut (if href
                   [:a {:class "seon-print-elision" :href href :title "the whole on this page"} words]
                   [:span {:class "seon-print-elision" :title s} words])]
         [:span shown (when-not (or (empty? shown) (str/ends-with? shown "\n") (str/ends-with? shown " ")) " ") cut])))))

(defn more-li
  "A bounded list's last item when it left some: how many more, and where they are."
  {:malli/schema [:=> [:cat nat-int? nat-int? [:maybe :string]] :any]}
  [total shown where]
  (when-let [m (print/more total shown where)] [:li {:class "more muted"} m]))

(def utc-offset-ms
  "Jolt has no time zones: the local offset comes from SEON_UTC_OFFSET (`date +%z`)."
  (let [z (or (System/getenv "SEON_UTC_OFFSET") "+0000")
        sign (if (str/starts-with? z "-") -1 1)]
    (* sign (+ (* (parse-long (subs z 1 3)) 3600000) (* (parse-long (subs z 3 5)) 60000)))))

(defn clock
  "hh:mm:ss.mmm local, by arithmetic on epoch ms."
  {:malli/schema [:=> [:cat :any] :string]}
  [ms]
  (if-not (number? ms)
    "--:--:--"
    (let [day-ms (mod (+ (long ms) utc-offset-ms) 86400000)]
      (format "%02d:%02d:%02d.%03d" (quot day-ms 3600000) (mod (quot day-ms 60000) 60) (mod (quot day-ms 1000) 60) (mod day-ms 1000)))))

(defn slot
  "Where the part with element id goes: a marker the render proc fills with the part's
  retained HTML when it composes a page or a keyframe."
  {:malli/schema [:=> [:cat :string] :any]}
  [id] (h/raw (str "<!--s:" id "-->")))

(defn- kicker {:malli/schema [:=> [:cat :any :any] :any]}
  [k sub] [:header [:span {:class "kicker"} k] [:span {:class "sub"} sub]])

(defn sparkline
  "An inline SVG sparkline over xs (oldest first), an area fill and a dot on the newest."
  {:malli/schema [:=> [:cat [:maybe [:sequential :any]] :string] :any]}
  [xs tone]
  (let [w 160 hgt 34 pad 2
        ;; the series as the render proc keeps it (seon.render.proc/SERIES-CAP points)
        xs (filterv number? xs)
        n (count xs)]
    (if (< n 2)
      [:svg {:class (str "spark " tone) :viewBox (str "0 0 " w " " hgt) :preserveAspectRatio "none"}]
      (let [hi (max 1e-9 (apply max xs))
            px (fn [i] (+ pad (* (- w (* 2 pad)) (/ i (double (dec n))))))
            py (fn [v] (- hgt pad (* (- hgt (* 2 pad)) (/ v hi))))
            pts (str/join " " (map-indexed (fn [i v] (str (fmt1 (px i)) "," (fmt1 (py v)))) xs))]
        [:svg {:class (str "spark " tone) :viewBox (str "0 0 " w " " hgt) :preserveAspectRatio "none"}
         [:polygon {:class "spark-area" :points (str (fmt1 (px 0)) "," hgt " " pts " " (fmt1 (px (dec n))) "," hgt)}]
         [:polyline {:class "spark-line" :points pts}]
         [:circle {:class "spark-dot" :cx (fmt1 (px (dec n))) :cy (fmt1 (py (peek xs))) :r "2.2"}]]))))

(defn value-html
  "A printed value (an agent's reply carries its printed text; the live value stays in the
  agent under its result handle) as the one value printer's hiccup: text that reads back as
  plain data and was not cut is printed structurally under budget, its cuts reading the rest
  through its handle (:h3 is result/h3); anything else shows as its text, as prose under budget (opts :href, where
  it shows whole)."
  {:malli/schema [:=> [:cat [:maybe :string] pos-int? [:maybe :keyword] :map] :any]}
  [txt budget handle opts]
  (let [plain? (and (some? txt) (< (count txt) 4000) (not (str/includes? txt "…"))
                    (contains? #{\{ \[ \( \:} (first txt)))
        ;; through the one reader edge: text that does not read is an answer (nil), not a catch here
        v (when plain? (let [fs (reader/read-text txt 'user)] (when (= 1 (count fs)) (first fs))))]
    (if (and plain? (coll? v))
      (let [hic (:seon.render/html (render/print-out :html v {:seon.render/budget budget :seon.render/handle (some->> handle name (symbol "result"))}))]
        ;; the printer's panel id is per value, not per part (ids drive Datastar's morph)
        ;; and every node open: a closed <details> hides its content however it is styled
        (clojure.walk/postwalk (fn [x] (if (and (vector? x) (= :details (first x)) (map? (second x))) (assoc-in x [1 :open] true) x))
                               (if (and (vector? hic) (map? (second hic))) (update hic 1 dissoc :id) hic)))
      [:code {:class "printed"} (prose txt budget opts)])))

(defn context-read?
  "Is evaluation e a context read: an outside agent reading its current-state block through
  root's entrance (origin :context; seon.entrance)? Its output is the block itself."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [e] (and (map? e) (= :context (:origin e))))

(defn context-ref
  "A context read, shown as what it is: a reference to the block it read (on the agent's
  page, its context panel shows that text as is), never the block again."
  {:malli/schema [:=> [:cat :map [:maybe :string]] :any]}
  [e href]
  (let [n (select/estimate (or (:out e) ""))]
    [:span {:class "ctxref"} "read its current-state block · ≈" (str n) " tokens · "
     [:a {:href (str (or href "") "#z-context")} "its context panel shows it"]]))

;;; --- status -------------------------------------------------------------------

(def status-order [:spawning :busy :idle :ended :killed :dead])

(defn shown-status
  "The word for how an agent is: its live status, or how its process ended: :ended when
  root stopped it after its attempt finished (done or merged), :killed for a kill, :died
  when its process died on its own (an agent entry carries :ended-as from main's attempt)."
  {:malli/schema [:=> [:cat :map] :keyword]}
  [a]
  (cond (and (#{:killed :dead} (:status a)) (:ended-as a)) :ended
        (= :dead (:status a)) :died
        :else (or (:status a) :unknown)))
(def ended-statuses #{:killed :dead :ended :died})

;;; --- metric tiles -------------------------------------------------------------

(defn tile-agents
  "The swarm: one cell per agent colored by status (a cell flashes when its agent
  evaluates; clicking one zooms the agent), a status bar and the counts.
  input {:cells [[id status evals-seen] ...] in grid order, :off agents ended long ago and
  left off the grid}."
  {:malli/schema [:=> [:cat :map :seon.render/env] :seon.render/out]}
  [{:keys [cells off]} _]
  (let [freq (frequencies (map second cells))
        alive (reduce + 0 (map val (remove (comp ended-statuses key) freq)))
        order (concat status-order (sort-by str (remove (set status-order) (keys freq))))]
    {:seon.render/html
     [:section {:id "t-agents" :class "tile"}
      (kicker "agents" (str (count cells) " on the grid" (when (pos? (or off 0)) (str " · +" off " ended earlier, off it"))))
      [:div {:class "big"} [:span {:class "num"} (str alive)] [:span {:class "unit"} "live"]]
      [:div {:class "swarm"}
       (for [[id st n] cells]
         [:a {:class (str "cell s-" (kname st) " f" (mod (or n 0) 2)) :href (str "/agent/" (dom-id id))
              :title (str (kname id) " · " (kname st))}])]
      [:div {:class "statusbar"}
       (for [s order :let [n (get freq s 0)] :when (pos? n)]
         [:span {:class (str "seg s-" (kname s)) :style {"flex-grow" n} :title (str (kname s) " " n)}])]
      [:ul {:class "legend"}
       (for [s order :let [n (get freq s 0)] :when (pos? n)]
         [:li [:i {:class (str "dot s-" (kname s))}] (kname s) " " [:b (str n)]])]]}))

(defn tile-writer
  "The writer: tx/s (sparkline), group commit, flushes. input {:writer :series :health}."
  {:malli/schema [:=> [:cat :map :seon.render/env] :seon.render/out]}
  [{:keys [writer series health]} _]
  (let [st (when (map? health) (:state health))]
    {:seon.render/html
     [:section {:id "t-writer" :class "tile"}
      (kicker "writer" "the only d/transact caller")
      [:div {:class "big"} [:span {:class "num"} (fmt1 (:tx-per-s writer))] [:span {:class "unit"} "tx/s"]]
      (sparkline (:tx series) "tone-signal")
      [:dl {:class "kv"}
       [:div {:title "batch p50: one group commit"} [:dt "batch"] [:dd (ms-str (:batch-p50-ms writer))]]
       [:div [:dt "txs"] [:dd (group3 (:tx-count writer))]]
       [:div {:title "transactions per group commit"} [:dt "tx/batch"] [:dd (fmt1 (:tx-per-batch writer))]]
       [:div {:title "group flushes (durable)"} [:dt "flushes"] [:dd (group3 (:flushes st))]]]]}))

(defn tile-server
  "The fork server: fork p50 (sparkline), lag, forked, its pid, standby and restarts.
  input {:server :series :health}."
  {:malli/schema [:=> [:cat :map :seon.render/env] :seon.render/out]}
  [{:keys [server series health]} _]
  (let [st (or (:status server) :up) ps (when (map? health) (:state health))]
    {:seon.render/html
     [:section {:id "t-server" :class (str "tile srv-" (kname st) " restarts-" (:restarts server 0))}
      [:header [:span {:class "kicker"} "fork server"]
       [:span {:class (str "pill srv " (if (= st :up) "s-up" "s-down"))} (kname st)]]
      [:div {:class "big"} [:span {:class "num"} (fmt2 (:fork-ms-p50 server))] [:span {:class "unit"} "ms fork p50"]]
      (sparkline (:fork series) "tone-info")
      [:dl {:class "kv"}
       [:div [:dt "forked"] [:dd (group3 (:agents-forked server))]]
       [:div {:title "commit applied in the fork server after main took it, p50"} [:dt "lag"] [:dd (ms-str (:lag-ms server))]]
       [:div [:dt "pid"] [:dd (str (or (:pid server) "–"))]]
       [:div {:title "a warm fork server forked in advance"} [:dt "standby"] [:dd (str (or (:standby ps) "–"))]]
       [:div {:class (when (pos? (:restarts server 0)) "hot")} [:dt "restarts"] [:dd (str (:restarts server 0))]]]]}))

(defn tile-main
  "Main: datoms (sparkline), t, merges. input {:main :series}."
  {:malli/schema [:=> [:cat :map :seon.render/env] :seon.render/out]}
  [{:keys [main series]} _]
  {:seon.render/html
   [:section {:id "t-main" :class (str "tile pulse-" (mod (or (:merges main) 0) 2))}
    (kicker "main" "t advances with every write to main: merges, tasks, attempts")
    [:div {:class "big"} [:span {:class "num"} (group3 (:datoms main))] [:span {:class "unit"} "datoms"]]
    (sparkline (:datoms series) "tone-success")
    [:dl {:class "kv"}
     [:div [:dt "t"] [:dd (group3 (:t main))]]
     [:div [:dt "merges"] [:dd (group3 (:merges main 0))]]
     [:div {:title "main's program as facts"} [:dt "vars"] [:dd (group3 (:vars main))]]]]})

(defn health-cell
  "One proc's health, as Flow observes it (its ping-map-fn): a dot by status and its own
  numbers; :unknown (it did not answer within the bound) is shown as what it is."
  {:malli/schema [:=> [:cat :seon.root/health :seon.render/env] :seon.render/out]}
  [h _]
  {:seon.render/html
   (if (= :unknown h)
     [:span {:class "hc s-unknown"} [:i {:class "dot s-unknown"}] [:b "unknown"] [:span {:class "muted"} " no answer to its ping"]]
     (let [st (:status h)]
       [:span {:class (str "hc s-" (kname st))} [:i {:class (str "dot s-" (kname st))}] [:b (kname st)]
        [:span {:class "muted"} (str " " (group3 (:count h)) " steps")]
        (when (map? (:state h))
          [:span {:class "hs"} (for [[k v] (sort-by (comp str key) (:state h))
                                     :when (or (number? v) (keyword? v) (boolean? v) (nil? v))]
                                 [:span [:em (name k)] (if (number? v) (str v) (kname v))])])]))})

(def ^:private key-numbers
  "The numbers worth a glance in a proc's ping map, first found wins (all on hover)."
  [:pending :queued :inflight :waiting :flights :busy :tabs :agents :tx-count :passes])

(defn proc-health
  "A proc's health as one line: status, steps per second, its key number; its whole ping
  map on hover."
  {:malli/schema [:=> [:cat :seon.render/proc-health :seon.render/env] :seon.render/out]}
  [{:keys [pid health]} _]
  (let [unknown? (= :unknown health)
        st (if unknown? :unknown (:status health))
        state (when (map? health) (:state health))
        k (some #(when (number? (get state %)) %) key-numbers)
        title (if unknown? "no answer to its ping within 250 ms"
                  (str/join "\n" (for [[kk v] (sort-by (comp str key) state)] (str (name kk) " " (print/text v 30)))))]
    {:seon.render/html
     [:li {:class (str "ph s-" (kname st) (when (or unknown? (not= :running st)) " abnormal")) :title title}
      [:i {:class (str "dot s-" (kname st))}] [:code (kname pid)]
      [:b (kname st)]
      [:span {:class "n"} (if (number? (:rate health)) (str (fmt1 (:rate health)) " steps/s") "")]
      [:span {:class "n"} (if k (str (name k) " " (get state k)) "")]
      (when (:error state) [:span {:class "bad"} (prose (:error state) 20 {:one-line? true})])]}))

(defn tile-health
  "Every root proc's health (Flow's ping of each, effect owners and lanes included), the
  agents' stand-ins counted, not listed. input {:health {pid h} :sampled-ms ms}."
  {:malli/schema [:=> [:cat :map :seon.render/env] :seon.render/out]}
  [{:keys [health sampled-ms]} env]
  (let [bad (count (filter (fn [[_ h]] (or (= :unknown h) (not= :running (:status h)))) health))]
    {:seon.render/html
     [:section {:id "t-health" :class (str "tile wide" (when (pos? bad) " hot"))}
      (kicker "health" (str (count health) " procs pinged · " (if (pos? bad) (str bad " not running") "all running")
                            " · " (clock sampled-ms)))
      [:ul {:class "health"}
       ;; anything not running first
       (for [[pid h] (sort-by (fn [[pid h]] [(if (and (map? h) (= :running (:status h))) 1 0) (str pid)]) health)]
         (render/html {:pid pid :health h} (assoc env :seon.render/schema :seon.render/proc-health)))]]}))

(def task-state-order [:running :blocked :open :held :done :merged :cancelled])

(defn state-title
  "What a task's observed state means, for its pill's title."
  {:malli/schema [:=> [:cat :any] :string]}
  [st]
  (case st
    :held "held: loaded, not released; (my.root/run! sys [id]) releases it"
    :blocked "blocked: a task it needs is not done (its blocked-by)"
    :open "open: released, no attempt running yet"
    :running "running: an attempt works it now"
    :done "done: its proofs pass on its branch; its merge task merges it"
    :merged "merged into main"
    :cancelled "cancelled: no task file declares it any more"
    (str st)))

(defn waiting-on
  "What blocked tasks wait on: each blocker once with how many tasks it holds up, the one
  holding most up first (the root cause first, not one line per blocked task)."
  {:malli/schema [:=> [:cat :map] [:vector [:tuple :any pos-int?]]]}
  [rows]
  (vec (sort-by (fn [[b n]] [(- n) (str b)]) (frequencies (for [[_ r] rows b (:blocked-by r)] b)))))

(defn tile-tasks
  "The work as it is: every task by its observed state (what happened, not the desired
  execution), the tasks list (state, what blocks it, proofs green/red on its newest
  attempt, attempts so far, tokens; each links to its newest attempt's agent), then the
  newest attempts. input {:rows (seon.render.proc/task-rows) :attempts :reconciler}."
  {:malli/schema [:=> [:cat :map :seon.render/env] :seon.render/out]}
  [{:keys [rows attempts reconciler]} env]
  (let [freq (frequencies (map :state (vals rows)))
        newest (take 8 (sort-by (comp - #(or (:seon.attempt/start-ms %) 0)) (vals attempts)))
        older (print/more (count attempts) (count newest) "older, on main: (seon.task/attempts (deref (:conn sys))) at root's REPL")
        tokens (reduce + 0 (map :tokens (vals rows)))
        st (when (map? reconciler) (:state reconciler))
        rank (zipmap task-state-order (range))]
    {:seon.render/html
     [:section {:id "t-tasks" :class "tile wide"}
      (kicker "reconciler · tasks" (str "main t " (group3 (:t st)) " · " (group3 (:seen st)) " commits seen · "
                                        (:flights st 0) " in flight"))
      [:div {:class "states"}
       [:span [:b (str (count rows))] " tasks"]
       (for [k task-state-order :let [n (get freq k 0)] :when (pos? n)]
         [:span {:class (str "pill s-" (name k))} (str (name k) " " n)])
       [:span {:class "muted" :title "model tokens spent by every attempt"} (str (group3 tokens) " tokens")]]
      (when-let [w (seq (waiting-on rows))]
        [:p {:class "waiting" :title "the tasks blocked tasks wait on, each once, the one holding most up first"}
         [:span {:class "muted"} (str (count (filter (comp seq :blocked-by val) rows)) " blocked, waiting on: ")]
         (interpose ", " (for [[b n] w] [:span [:code b] (str " (" n ")")]))])
      (if (empty? rows)
        [:p {:class "muted"} "no tasks on main yet: the reconciler starts an attempt per ready task"]
        (list
          [:table {:class "attempts tasks"}
           [:thead [:tr [:th "task"] [:th "state"] [:th "blocked by"] [:th "proofs"] [:th "attempts"] [:th "tokens"]]]
           [:tbody
            (for [[id r] (sort-by (fn [[id r]] [(rank (:state r) 9) id]) rows)
                  :let [ag (get-in r [:newest :seon.attempt/agent])]]
              [:tr {:class (str "s-" (name (:state r)))}
               [:td (if ag [:a {:href (str "/agent/" (dom-id ag)) :title (str "its newest attempt, " (get-in r [:newest :seon.attempt/id]))} id] id)]
               [:td [:span {:class (str "pill s-" (name (:state r))) :title (state-title (:state r))} (name (:state r))]]
               [:td (if (seq (:blocked-by r)) [:span {:class "warn"} (str/join " " (sort (:blocked-by r)))] [:span {:class "muted"} "–"])]
               [:td {:title "its own proofs green / red on its newest attempt; other tasks' reds are allowed"}
                (if (pos? (+ (:green r) (:red r)))
                  (list [:span {:class "ok"} (str (:green r) " ✓")] (when (pos? (:red r)) [:span {:class "bad"} (str " " (:red r) " ✕")])
                        (when (pos? (:allowed-red r 0)) [:span {:class "muted"} (str " · " (:allowed-red r) " allowed red")]))
                  [:span {:class "muted"} (str (:proofs r) " to run")])]
               [:td (str (:attempts r))] [:td (group3 (:tokens r))]])]]
          [:table {:class "attempts"}
           [:thead [:tr [:th "newest attempts"] [:th "agent"] [:th "status"] [:th "turns"] [:th "tokens"] [:th "outcome"] [:th "ms"]]]
           [:tbody (for [a newest] (render/html a (assoc env :seon.render/schema :seon.attempt/attempt)))
            (when older [:tr {:class "more"} [:td {:colspan 7 :class "muted"} older]])]]))]}))

(defn attempt-row
  "An attempt: its agent, status, turns, tokens and outcome (from main's facts)."
  {:malli/schema [:=> [:cat :map :seon.render/env] :seon.render/out]}
  [{:seon.attempt/keys [id agent status turns tokens outcome reason start-ms end-ms env]} _]
  {:seon.render/html
   [:tr {:class (str "at s-" (kname status) (when outcome (str " o-" (kname outcome))))}
    [:td [:code id]]
    [:td (if agent [:a {:href (str "/agent/" (dom-id agent))} (kname agent)] "–")
     (when (= "outside" (:model env)) [:span {:class "muted" :title "worked from outside (a lane through root's entrance): no turn loop, its turns and tokens are not counted here"} " · outside"])]
    [:td [:span {:class (str "pill s-" (kname status))} (kname status)]]
    [:td (str (or turns "–"))] [:td (group3 tokens)]
    [:td {:title (str reason)} (kname outcome)]
    [:td (if (and start-ms end-ms) (group3 (- end-ms start-ms)) "–")]]})

(defn tile-fx
  "The effect owners (FX1): each kind's lanes, busy and done, its queue, and what was
  spent by budget key (the model owner's admission and tokens). input {owner-pid health}."
  {:malli/schema [:=> [:cat :map :seon.render/env] :seon.render/out]}
  [owners _]
  {:seon.render/html
   [:section {:id "t-fx" :class "tile"}
    (kicker "effects" "every effect through its owner proc")
    (if (empty? owners)
      [:p {:class "muted"} "no effect owners declared"]
      [:ul {:class "fx"}
       (for [[pid h] (sort-by (comp str key) owners) :let [s (when (map? h) (:state h))]]
         [:li [:code (kname pid)]
          (if (= :unknown h)
            [:span {:class "bad"} "unknown"]
            [:span {:class "muted"} (str (:busy s 0) "/" (:lanes s 0) " busy · " (group3 (:done s)) " done · "
                                         (:queued s 0) " queued")])
          (when (seq (:spent s))
            [:span {:class "spent" :title "spent by budget key"}
             (for [[k v] (sort-by (comp - #(if (number? %) % 0) val) (:spent s))] [:span [:em (kname k)] (str v)])])])])]})

(defn tile-errors
  "Root's own defects (a step that threw, recorded as data by the error reader), the
  boot's numbers and the store."
  {:malli/schema [:=> [:cat :map :seon.render/env] :seon.render/out]}
  [{:keys [root-errors root-errors-total boot store]} _]
  {:seon.render/html
   [:section {:id "t-errors" :class (str "tile" (when (seq root-errors) " hot"))}
    (kicker "root" (str (or root-errors-total (count root-errors)) " defects in root's own procs (agents' problems: the banner)"))
    (if (seq root-errors)
      [:ul {:class "errs"}
       (for [e (reverse (take-last 6 root-errors))] [:li [:code (kname (:pid e))] " " (prose (:message e) 50)])
       (more-li (or root-errors-total (count root-errors)) (min 6 (count root-errors))
                (str "older: (:root-errors (seon.root/state sys)) keeps the newest " (count root-errors)))]
      [:p {:class "muted"} "no proc has thrown"])
    [:dl {:class "kv"}
     [:div {:title "boot: open the store, load main's program, arm contracts, fork the fork server"} [:dt "boot"] [:dd (ms-str (:load-ms boot))]]
     [:div {:title "main's program loaded at boot"} [:dt "program"] [:dd (str (get-in boot [:program :loaded] "–") " namespaces")]]
     [:div [:dt "contracts"] [:dd (str (get-in boot [:contracts :armed] "–") " armed")]]
     [:div [:dt "store"] [:dd (kname (:backend store))]]
     [:div {:title "commits per group flush"} [:dt "tx/batch"] [:dd (fmt1 (:tx-per-batch store))]]]]})

;;; --- agent cards --------------------------------------------------------------

(defn- test-dots {:malli/schema [:=> [:cat [:maybe :map]] :any]}
  [tests]
  (if-not tests
    [:div {:class "tests none"} [:span {:class "label"} "tests"] [:span {:class "muted"} "not run"]]
    (let [{:keys [pass fail error reports]} tests
          total (+ (or pass 0) (or fail 0) (or error 0))]
      [:div {:class (str "tests " (if (pos? (+ (or fail 0) (or error 0))) "red" "green"))}
       [:span {:class "label"} "tests"]
       [:span {:class "dots"}
        (if (seq reports)
          (for [r (sort-by #(if (= :pass (:type %)) 1 0) reports)]
            [:i {:class (str "t-" (kname (:type r))) :title (str (:var r) (when (:message r) (str " · " (:text (print/prose (:message r) 60)))))}])
          (concat (repeat (or pass 0) [:i {:class "t-pass"}]) (repeat (- total (or pass 0)) [:i {:class "t-fail"}])))]
       [:span {:class "score"} (str pass "/" total)]
       (when (:ms tests) [:span {:class "muted"} (ms-str (:ms tests))])])))

(defn fns-line
  "An evaluation's costliest contracted functions (seon.profile: calls exact, ms estimated
  from 1 in 64 timed), the top k, and how many more its row on the agent's page shows."
  {:malli/schema [:=> [:cat [:maybe [:sequential :map]] pos-int?] :any]}
  [fns k]
  (when (seq fns)
    [:span {:class "fns" :title "costliest contracted functions: calls (exact) · ms (estimated)"}
     (for [{:keys [var calls ms]} (take k fns)]
       [:span {:class "fn"} [:code (name var)] [:i (str (group3 calls) "×")] (when (and ms (pos? ms)) [:i (ms-str ms)])])
     (when-let [m (print/more (count fns) (min k (count fns)) "on its page")] [:span {:class "muted"} m])]))

(def BIG-ALLOC "An evaluation that allocated more than this is flagged." (* 10 1024 1024))

(defn alloc-span
  "An evaluation's allocated bytes, flagged when large (over BIG-ALLOC)."
  {:malli/schema [:=> [:cat :any] :any]}
  [b]
  (if (and (number? b) (> b BIG-ALLOC))
    [:span {:class "big-alloc" :title "this evaluation allocated a lot: a cost to look at"} (str "⚠ " (bytes-str b) " alloc")]
    [:span (bytes-str b)]))

(defn- eval-row
  "One evaluation inside a card: its form, value, time, allocation and top functions. Only
  the newest is printed structurally (the printer's cost: inputs before render); a cut
  links to the agent's page (href), which shows its evaluations whole."
  {:malli/schema [:=> [:cat :map :boolean :string] :any]}
  [e newest? href]
  (let [err? (= :err (:tag e))
        txt (or (:printed e) (:shown e) (:error e))]
    [:li {:class (str "ev tag-" (kname (or (:tag e) :ret)))}
     (when-not (str/blank? (:form e)) [:code {:class "form"} (prose (:form e) 28 {:one-line? true :href href})])
     [:div {:class "out"}
      [:span {:class "arrow"} (if err? "✕" "⇒")]
      (cond
        (and (context-read? e) (not err?)) (context-ref e href)
        (and newest? (not err?))
        [:div {:class "printed seon"} (value-html txt 120 (:handle e) {:href href})]
        :else [:code {:class "printed"} (prose txt 50 {:href href})])]
     [:div {:class "meta"}
      [:span (ms-str (:ms e))] (alloc-span (:alloc-bytes e))
      (when (:origin e) [:span {:class "origin"} (kname (:origin e))])
      (when (:recorded e) [:span {:class "rec" :title "recorded as a program fact on this branch"} (str "＋" (:recorded e))])
      (when (seq (:warnings e)) [:span {:class "warn" :title (str (:seon.repl/fix (first (:warnings e))))} (str "⚠ " (kname (:seon.repl/finding (first (:warnings e)))))])
      (when (:handle e) [:span {:class "handle"} (kname (:handle e))])]
     (when newest? (fns-line (:fns e) 3))]))

(defn agent-card
  "One agent's card body: the patched part (its REPL box and buttons stay outside every
  morph target so typing survives). Status, branch, task, pid, fork ms, memory, the
  running form, the newest evaluations with their costliest functions, and tests."
  {:malli/schema [:=> [:cat :seon.render/agent :seon.render/env] :seon.render/out]}
  [{:keys [id evals running] :as ag} _]
  (let [st (kname (shown-status ag))
        last-e (peek (vec evals))
        href (str "/agent/" (dom-id id))
        ;; root's view keeps the newest evaluations; their ids count them all
        total (max (count evals) (inc (or (:id last-e) -1)))]
    {:seon.render/html
     [:div {:id (str "cb-" (dom-id id)) :class (str "cb s-" st " flash-" (mod (count evals) 2))}
      [:header
       [:span {:class (str "dot s-" st)}]
       [:a {:class "aid" :href (str "/agent/" (dom-id id)) :title "zoom"} (kname id)]
       [:span {:class "branch"} (kname (:branch ag))]
       (when (:cloned-from ag) [:span {:class "pill s-clone" :title "forked from a live agent, values and all"} (str "⑂ " (kname (:cloned-from ag)))])
       (when-let [m (:merged ag)]
         (cond (:merged? m) [:span {:class "pill s-merged" :title (str "merged at t " (:tx m) (:t m))} "⇡ merged"]
               (seq (:conflicts m)) [:span {:class "pill s-conflict" :title (str (count (:conflicts m)) " keys changed on both sides")} "⚔ conflict"]
               :else [:span {:class "pill s-refused" :title (str (or (:error m) "its tests were red"))} "✕ refused"]))
       [:span {:class (str "pill s-" st) :title (when (:ended-as ag) (str "root stopped it after its attempt ended " (kname (:ended-as ag))))}
        (if (:ended-as ag) (str "ended · " (kname (:ended-as ag))) st)]]
      [:p {:class "task"} (prose (or (:task ag) "") 40 {:href href})]
      [:div {:class "stats"}
       [:span [:em "pid"] (str (or (:pid ag) "–"))]
       [:span {:title "fork to ready"} [:em "fork"] (ms-str (:forked-ms ag))]
       [:span [:em "mem"] (if (:mem-mb ag) (str (fmt1 (:mem-mb ag)) " MB") "–")]
       [:span [:em "evals"] (str total)]
       (when (:t ag) [:span {:title "its branch's t"} [:em "t"] (str (:t ag))])]
      (when (:error ag) [:p {:class "err"} (prose (:error ag) 60)])
      (when running [:p {:class "running" :title "evaluating now"} "▶ " [:code (prose (:form running) 40 {:one-line? true :href href})]])
      (if last-e
        [:ol {:class "evals"} (map-indexed (fn [i e] (eval-row e (zero? i) href)) (reverse (take-last 6 evals)))
         ;; closed, the card shows the newest; open (⤢), the newest six
         (when-let [m (print/more total 1 "⤢ shows six, its page every one")] [:li {:class "more muted closed"} m])
         (when-let [m (print/more total (min 6 (count evals)) "earlier evaluations on its page")] [:li {:class "more muted opened"} m])]
        [:ol {:class "evals empty"} [:li {:class "ev"} [:span {:class "muted"} "no evaluations yet"]]])
      (test-dots (:tests ag))]}))

(defn card-shell
  "One whole agent card around its body's slot: appended to the grid once, when the agent
  first appears. Its REPL box posts the forms as a UI call (an eval recorded with origin
  :ui); its buttons are UI calls of root's verbs."
  {:malli/schema [:=> [:cat :keyword] :any]}
  [id]
  (let [d (dom-id id)
        verb (fn [f label title & [cls]]
               [:button {:class (str "act" (when cls (str " " cls))) :title title :seon/call f :seon/agent id} label])]
    [:article {:id (str "card-" d) :class "card" (keyword "data-class:open") (str "$sel==='" d "'")}
     (slot (str "cb-" d))
     [:form {:class "repl" :seon/call 'seon.root/submit! :seon/agent id}
      [:span {:class "prompt"} (str (kname id) "=>")]
      [:input {:id (str "ri-" d) :name "forms" :autocomplete "off" :spellcheck "false" :placeholder "(+ 1 2)"}]]
     [:div {:class "actions"}
      [:button {:class "act" :title "show history" (keyword "data-on:click") (str "$sel = $sel==='" d "' ? '' : '" d "'")} "⤢"]
      (verb 'seon.root/run-tests! "▶ tests" "run its deftests in a fork of it")
      (verb 'seon.root/merge! "⇡ merge" "root merges this branch into main, gated by its tests")
      (verb 'seon.root/merge-main! "⇣ main" "git merge main: main's changes into this branch")
      (verb 'seon.root/clone! "⑂ clone" "fork this live agent, values and all")
      (verb 'seon.root/interrupt! "⏹" "interrupt the running form; the agent keeps its state")
      (verb 'seon.root/kill! "✕ kill" "SIGKILL its process" "danger")]]))

;;; --- events ---------------------------------------------------------------------

(defn- event-detail {:malli/schema [:=> [:cat :map] :string]}
  [ev]
  (case (:type ev)
    :reply (let [items (:items ev) r (last items)]
             (if (context-read? r)
               (str "read its current-state block · ≈" (select/estimate (or (:out r) "")) " tokens")
             (str (when (> (count items) 1) (str (count items) " forms · "))
                  (let [n (count (filter #(= :err (:tag %)) items))] (when (pos? n) (str n " error" (when (> n 1) "s") " · ")))
                  (when-not (str/blank? (:form r)) (str (:text (print/prose (:form r) 12 {:one-line? true})) " ⇒ "))
                  (:text (print/prose (:printed r) 22 {:one-line? true}))
                  (when (:ms r) (str " · " (ms-str (:ms r)))))))
    :commit (str (kname (:branch ev)) " t " (:t ev) (when (:txs ev) (str " · " (:txs ev) " tx")))
    :merged (let [r (:result ev)]
              (if (false? (:merged? r))
                (cond (seq (:conflicts r)) (str "CONFLICT · " (count (:conflicts r)) " keys changed on both sides")
                      (:tests r) (str "REFUSED · tests " (get-in r [:tests :pass] 0) " pass " (get-in r [:tests :fail] 0) " fail "
                                      (get-in r [:tests :error] 0) " error")
                      :else (str "refused" (when (:error r) (str ": " (:text (print/prose (:error r) 25 {:one-line? true}))))
                                 (when (:seon.error/message r) (str ": " (:text (print/prose (:seon.error/message r) 25 {:one-line? true}))))))
                (str "→ main" (when (:t r) (str " · t " (:t r))) (when (:tx r) (str " · tx " (:tx r))))))
    :tests (str (or (:pass ev) 0) " pass · " (or (:fail ev) 0) " fail · " (or (:error ev) 0) " error" (when (:ms ev) (str " · " (ms-str (:ms ev)))))
    :merged-main (let [r (:result ev)]
                   (if (:ok r) (str "main → branch · " (count (:applied r)) " applied · " (count (:conflicts r)) " conflicts")
                       (str "refused: " (:text (print/prose (or (:error r) (:seon.error/message r)) 20 {:one-line? true})))))
    :server-down (str "fork server pid " (:pid ev) " is gone · agents keep running")
    :server-up (str "root forked a new fork server · pid " (:pid ev))
    :spawned (if (:cloned-from ev)
               (str "cloned from " (kname (:cloned-from ev)) " · pid " (:pid ev))
               (str "forked in " (ms-str (:fork-ms ev)) (when (:pid ev) (str " · pid " (:pid ev)))))
    :ready (str "ready" (when (:start-ms ev) (str " in " (ms-str (:start-ms ev)))))
    :killed (if (:ended-as ev) (str "stopped by root after its attempt ended " (kname (:ended-as ev))) "KILLED")
    :dead (if (:ended-as ev) (str "exited after its attempt ended " (kname (:ended-as ev))) "its process died")
    :ui (str (:call ev) (when (:detail ev) (str " · " (:text (print/prose (str (:detail ev)) 20 {:one-line? true})))))
    (print/text (dissoc ev :type :at-ms :agent) 25)))

(defn- bad-event? {:malli/schema [:=> [:cat :map] :boolean]}
  [ev]
  (boolean (or (some #(= :err (:tag %)) (:items ev))
               (and (= :tests (:type ev)) (pos? (+ (or (:fail ev) 0) (or (:error ev) 0))))
               (and (= :dead (:type ev)) (not (:ended-as ev)))
               (#{:server-down :server-lost} (:type ev))
               (false? (get-in ev [:result :merged?]))
               (and (= :ui (:type ev)) (some? (:error ev))))))

(defn event-row
  "One event of root's stream: time, kind, who, what."
  {:malli/schema [:=> [:cat :seon.render/event :seon.render/env] :seon.render/out]}
  [ev _]
  {:seon.render/html
   [:li {:id (when (:seq ev) (str "ev-" (:seq ev))) :class (str "e e-" (if (:ended-as ev) "ended" (kname (:type ev))) (when (bad-event? ev) " e-err"))}
    [:time (clock (:at-ms ev))]
    [:span {:class "etype"} (if (:ended-as ev) "ended" (kname (:type ev)))]
    [:span {:class "eagent"} (if (:agent ev) [:a {:href (str "/agent/" (dom-id (:agent ev)))} (kname (:agent ev))] "root")]
    [:span {:class "edetail"} (event-detail ev)]]})

(defn events-list
  "The stream around rows already serialized (each event's row is rendered once)."
  {:malli/schema [:=> [:cat [:sequential :string] :seon.render/env] :seon.render/out]}
  [rows _]
  {:seon.render/html [:ol {:id "events" :class "events"} (map h/raw rows)]})

(defn events-count
  "The stream's header: newest first, and how many of the events since root's boot it
  keeps (seon.render.proc/EVENT-CAP); the older are not kept anywhere."
  {:malli/schema [:=> [:cat :map :seon.render/env] :seon.render/out]}
  [{:keys [kept seen]} _]
  {:seon.render/html
   [:span {:id "events-n" :class "sub"}
    (str "newest first · " kept " kept of " seen " since root's boot"
         (when (> seen kept) (str " · " (- seen kept) " older not kept")))]})

(defn context-panel
  "Root's context: the system's AI face, the text root (the orchestrator) reads, shown so
  the owner sees what root sees. input {:text :tokens}."
  {:malli/schema [:=> [:cat :map :seon.render/env] :seon.render/out]}
  [{:keys [text tokens]} _]
  {:seon.render/html
   [:section {:id "ctx" :class "ctxp"}
    [:header [:span {:class "kicker"} "root's context"]
     [:span {:class "sub"} (str "≈" tokens " tokens · the system's AI face · ") [:a {:href "/context.txt"} "/context.txt"]]]
    [:pre (or text "")]]})

(defn web-stats
  "The render step's own numbers: passes, tabs, ms per pass, patches, bytes out."
  {:malli/schema [:=> [:cat :map :seon.render/env] :seon.render/out]}
  [w _]
  {:seon.render/html
   [:div {:id "webstats" :class "webstats"}
    [:span {:class "live-dot"}]
    [:span {:title "render passes"} (str "pass " (:passes w 0))]
    [:span (str (:tabs w 0) " tab" (when (not= 1 (:tabs w)) "s"))]
    [:span {:title "the last pass that changed something: render ms"} (str "render " (ms-str (:render-ms w)))]
    [:span (str (:patches w 0) " patches")]
    [:span (str (bytes-str (:bytes-per-s w)) "/s out")]]})

;;; --- root's layout ------------------------------------------------------------------

(defn ended-as
  "Agent entry a, with :ended-as when its process ended after its attempt finished (main's
  attempt done or merged); a live agent's entry as it is (identical: inputs compare O(1))."
  {:malli/schema [:=> [:cat [:maybe :map] :any :map] [:maybe :map]]}
  [a id agent-attempt]
  (let [st (:seon.attempt/status (get agent-attempt id))]
    (if (and a (#{:killed :dead} (:status a)) (#{:done :merged} st)) (assoc a :ended-as st) a)))

(defn tile-problems
  "What is wrong now, at the top of the page: the same lines root's context leads with."
  {:malli/schema [:=> [:cat [:sequential :string] :seon.render/env] :seon.render/out]}
  [lines _]
  {:seon.render/html
   (if (empty? lines)
     [:section {:id "problems" :class "problems ok"} [:span {:class "dot s-running"}] [:b "no problems"]
      [:span {:class "muted"} " every proc running · no defects · no red live agents · no agent died"]]
     [:section {:id "problems" :class "problems bad"}
      (kicker (str (count lines) " problem" (when (not= 1 (count lines)) "s")) "most urgent first · the same lines root's context leads with")
      [:ul (for [l lines] [:li l])]])})

(defn- topbar {:malli/schema [:=> [:cat :any] :any]}
  [back?]
  [:div {:class "topbar"}
   [:a {:class "brand" :href "/"} [:span {:class "mark"} "◆"] [:span {:class "name"} "seon"] [:span {:class "on"} "on"]
    [:span {:class "jolt"} "jolt"] [:span {:class "mode mode-live"} "root"]]
   (if back?
     [:a {:class "btn" :href "/" :title "back to root's view (Esc)"} "← root"]
     (list
       [:form {:class "spawn" :seon/call 'seon.root/spawn! :seon/agent :root}
        [:input {:name "task" :autocomplete "off" :placeholder "Task for a new agent…  (enter to spawn)"}]
        [:button {:type "submit" :class "btn primary"} "Spawn agent"]
        [:button {:type "button" :class "btn" :seon/call 'seon.root/spawn! :seon/agent :root :seon/args {:n 10}} "Spawn 10"]]
       [:form {:class "broadcast" :seon/call 'seon.root/submit! :seon/agent :all :title "evaluate in every live agent"}
        [:span {:class "prompt"} "all=>"]
        [:input {:name "forms" :autocomplete "off" :spellcheck "false" :placeholder "(System/getenv \"USER\")  — eval in every agent"}]]))
   [:div {:class "tools"}
    (slot "webstats")
    [:button {:class "btn ghost small" :title "compact cards" (keyword "data-on:click") "$dense = !$dense"
              (keyword "data-text") "$dense ? '▤ roomy' : '▦ compact'"} "▦ compact"]
    [:button {:class "btn ghost" :id "theme" :title "toggle theme"
              :onclick "var d=document.documentElement,c=getComputedStyle(d).getPropertyValue('--scheme').trim(),n=c==='dark'?'light':'dark';d.dataset.theme=n;try{localStorage.setItem('seon-theme',n)}catch(e){}"}
     "◐"]]])

(defn shell
  "The document around a view's body: head, theme, Datastar, and the feed of that view's
  patches (outside every morph target, so no patch can strip it)."
  {:malli/schema [:=> [:cat :string :string :any] :any]}
  [title feed body]
  [:html {:lang "en"}
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:title title]
    [:link {:rel "icon" :href "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16'%3E%3Ccircle cx='8' cy='8' r='6' fill='%23f0b429'/%3E%3C/svg%3E"}]
    [:link {:rel "stylesheet" :href "/app.css"}]
    [:script (h/raw "try{var t=localStorage.getItem('seon-theme');if(t)document.documentElement.dataset.theme=t}catch(e){}")]
    [:script {:type "module" :src "/datastar.js"}]
    ;; Esc goes back to root's view from a zoomed agent
    [:script (h/raw "document.addEventListener('keydown',function(e){if(e.key==='Escape'&&location.pathname!=='/')location='/'})")]]
   [:body {:data-signals "{sel:'', dense:false}" (keyword "data-class:dense") "$dense"}
    body
    [:div {:style "display:none" :data-init (str "@get('" feed "', {retryMaxCount: Infinity, openWhenHidden: true})")}]]])

(defn system-page
  "Root's layout of the whole system (the declared HTML render of :seon.render/system): the
  top bar, the metric tiles, the grid of agent cards, the side column (root's context,
  the event stream), each part a unit reading exactly what it shows."
  {:malli/schema [:=> [:cat :seon.render/system :seon.render/env] :seon.render/out]}
  [{:keys [agents health series writer server main attempts root-errors boot store web agent-attempt] :as sys} _]
  (let [order (:order sys)
        fx (into {} (filter (fn [[pid]] (= "fx" (namespace pid)))) health)
        procs (into {} (remove (fn [[pid]] (and (keyword? pid) (str/starts-with? (str (namespace pid)) "fx.")))) health)
        u (fn [id input render] {:id id :input input :render render})]
    {:seon.render/html
     [:div {:id "app"}
      (topbar false)
      [:main {:class "layout"}
       [:div {:class "left"}
        (slot "problems")
        [:div {:class "tiles"} (slot "t-agents") (slot "t-writer") (slot "t-server") (slot "t-main")]
        [:div {:class "tiles two"} (slot "t-health") (slot "t-tasks")]
        [:div {:class "tiles three"} (slot "t-fx") (slot "t-errors")]
        [:div {:id "grid" :class "grid"} (for [id order] (slot (str "card-" (dom-id id))))]]
       [:aside {:class "side"}
        (slot "ctx")
        [:div {:class "stream"}
         [:header [:span {:class "kicker"} "event stream"] (slot "events-n")]
         (slot "events")]]]]
     :seon.render/units
     (into [(u "problems" (:problems sys []) `tile-problems)
            (u "t-agents" {:cells (mapv (fn [id] (let [a (get agents id)] [id (shown-status (ended-as a id agent-attempt)) (count (:evals a))])) order)
                           :off (- (count agents) (count order))} `tile-agents)
            (u "t-writer" {:writer writer :series (select-keys series [:tx]) :health (get health :writer)} `tile-writer)
            (u "t-server" {:server server :series (select-keys series [:fork]) :health (get health :fork-server)} `tile-server)
            (u "t-main" {:main main :series (select-keys series [:datoms])} `tile-main)
            (u "t-health" {:health procs :sampled-ms (:health-ms sys)} `tile-health)
            (u "t-tasks" {:rows (:task-rows sys) :attempts attempts :reconciler (get health :reconciler)} `tile-tasks)
            (u "t-fx" fx `tile-fx)
            (u "t-errors" {:root-errors root-errors :root-errors-total (:root-errors-total sys) :boot boot :store store} `tile-errors)
            (u "ctx" (:context sys) `context-panel)
            ;; the stream's patches are the render proc's prepends; this unit keeps the list
            ;; for pages and keyframes
            (assoc (u "events" (:event-rows sys []) `events-list) :keyframe-only true)
            (u "events-n" {:kept (count (:events sys)) :seen (:events-seen sys 0)} `events-count)
            (u "webstats" web `web-stats)]
           (for [id order]
             {:id (str "cb-" (dom-id id)) :input (ended-as (get agents id) id agent-attempt) :agent id :schema :seon.render/agent
              :shell (str "card-" (dom-id id)) :container "#grid"}))}))

;;; --- one agent, zoomed -----------------------------------------------------------------

(defn task-box
  "A task: its plan (accumulated along its needs), instructions, its proofs, its kind,
  execution and problem."
  {:malli/schema [:=> [:cat :seon.task/task :seon.render/env] :seon.render/out]}
  [t _]
  (let [{:keys [plan proofs]} (:seon.task/accumulated t)]
    {:seon.render/html
     [:section {:class "box"}
      (kicker (str "task · " (:seon.task/id t)) (str (kname (:seon.task/kind t)) " · " (kname (:seon.task/execution t))))
      [:pre {:class "plan"} plan]
      (when (:seon.task/instructions t) [:p {:class "muted"} (:seon.task/instructions t)])
      (when (:seon.task/problem t) [:p {:class "err"} (:seon.task/problem t)])
      (when (seq (:seon.task/needs t)) [:p {:class "muted"} "needs " (str/join ", " (sort (:seon.task/needs t)))])
      [:p {:class "muted"} (str (count proofs) " proofs: ") (str/join " " (map str (sort-by str proofs)))]]}))

(defn verdict-box
  "A cost verdict: red when a size is over its estimate or grew steeper than the work
  needs; loose estimates and unmeasured dimensions as findings."
  {:malli/schema [:=> [:cat :seon.cost/verdict :seon.render/env] :seon.render/out]}
  [{:keys [ok? over steeper under unmeasured]} _]
  {:seon.render/html
   [:div {:class (str "verdict " (if ok? "ok" "red"))}
    [:b (if ok? "within estimate" "over estimate")]
    (for [{:keys [dim n ratio]} over] [:span {:class "bad"} (str " OVER " (name dim) " at n=" (group3 n) " " (fmt2 ratio) "×")])
    (for [{:keys [dim exponent allowed]} steeper] [:span {:class "bad"} (str " STEEPER " (name dim) " n^" (fmt2 exponent) " > n^" (fmt2 allowed))])
    (for [{:keys [dim n ratio]} under] [:span {:class "muted"} (str " loose " (name dim) " at n=" (group3 n) " " (fmt2 ratio) "×")])
    (for [d unmeasured] [:span {:class "muted"} (str " unmeasured " (name d))])]})

(defn cost-report
  "A cost proof's measurements against its estimate, row by size, then its verdict."
  {:malli/schema [:=> [:cat :seon.render/cost-report :seon.render/env] :seon.render/out]}
  [{:keys [rows verdict]} env]
  (let [dims (filter (set (mapcat (comp keys :expected) rows)) [:ns :bytes :calls])]
    {:seon.render/html
     [:div {:class "cost"}
      [:table {:class "costrows"}
       [:thead [:tr [:th "n"] (for [d dims] [:th (str (name d) " measured / estimate")])]]
       [:tbody (for [{:keys [n measured expected]} rows]
                 [:tr [:td (group3 n)]
                  (for [d dims :let [m (get measured d) x (get expected d)]]
                    [:td {:class (when (and m x (pos? x) (> (/ m x) 3)) "bad")}
                     (str (group3 m) " / " (group3 x) (when (and m x (pos? x)) (str " (" (fmt2 (/ m x)) "×)")))])])]]
      (render/html verdict (assoc env :seon.render/schema :seon.cost/verdict))]}))

(defn red-label
  "Why a red proof does or does not block: a proof of this attempt's own task blocks; a
  proof of another task not done yet is an allowed red (whose task)."
  {:malli/schema [:=> [:cat :any [:maybe [:set :any]] [:maybe :map]] :any]}
  [p own owners]
  (let [p (symbol (str/replace (str p) #"^#'" ""))]
    (cond (contains? (or own #{}) p) [:span {:class "blocking"} "blocking: its own task's proof"]
          (get owners p) [:span {:class "allowed"} (str "allowed: a proof of " (get owners p))]
          :else [:span {:class "muted"} "not a task's proof"])))

(defn- proof-row
  "One test report of the agent's last run: pass, or its red assertion with expected and
  actual, labelled blocking or allowed; a cost proof shows its rows and verdict by their
  own render."
  {:malli/schema [:=> [:cat :map :seon.render/env [:maybe [:set :any]] [:maybe :map]] :any]}
  [r env own owners]
  (let [cost (or (:cost r) (when (and (map? (:actual r)) (:verdict (:actual r))) (:actual r)))
        pass? (= :pass (:type r))]
    [:li {:class (str "pr t-" (kname (:type r)))}
     [:i {:class (str "sq t-" (kname (:type r)))}] " " [:code (str (:var r))]
     (when (and (not pass?) (or own (seq owners))) [:span " " (red-label (:var r) own owners)])
     (when (:message r)
       (if pass?
         [:span {:class "muted"} " " (prose (:message r) 50 {:one-line? true})]
         ;; a red report's message: for a cost proof, its estimate against what was measured
         [:pre {:class "msg"} (prose (:message r) 600)]))
     (cond cost (render/html cost (assoc env :seon.render/schema :seon.render/cost-report))
           (not= :pass (:type r)) [:div {:class "fail"} [:div [:em "expected "] [:code (print/text (:expected r) 120)]]
                                   [:div [:em "actual "] [:code (print/text (:actual r) 120)]]])]))

(defn proofs-box
  "The agent's last test run: every report, the red ones first, cost proofs with their
  measurements. input {:tests :results} (results: main's attempt results by proof)."
  {:malli/schema [:=> [:cat :map :seon.render/env] :seon.render/out]}
  [{:keys [tests results own owners]} env]
  {:seon.render/html
   [:section {:class "box" :id "z-proofs"}
    (kicker "proofs" (if tests (str (:pass tests 0) " pass · " (:fail tests 0) " fail · " (:error tests 0) " error · " (ms-str (:ms tests)))
                         "not run"))
    (when (seq results)
      [:div {:class "judged"} [:span {:class "muted"} "judged on main: "]
       [:ul (for [[p s] (sort-by (fn [[p s]] [(if (= :pass s) 1 0) (str p)]) results)]
              [:li [:span {:class (str "res s-" (kname s))} (str p " " (kname s))]
               (when (not= :pass s) [:span " " (red-label p own owners)])])]])
    [:ul {:class "proofs"}
     (for [r (sort-by #(if (= :pass (:type %)) 1 0) (:reports tests))] (proof-row r env own owners))]]})

(defn history-row
  "One evaluation, as a row of the agent's history: its form, its value (the printer's
  HTML), its time and allocation, who asked (origin), and its costliest functions with
  exact calls and estimated ms. Takes a reply item or the recorded evaluation."
  {:malli/schema [:=> [:cat :map :seon.render/env] :seon.render/out]}
  [e env]
  (let [err? (= :err (:tag e))
        txt (or (:printed e) (:shown e) (:error e))]
    {:seon.render/html
     [:li {:class (str "hr tag-" (kname (or (:tag e) :ret)))}
      [:div {:class "hf"} [:code (prose (:form e) 400)]]
      [:div {:class "hv"} [:span {:class "arrow"} (if err? "✕" "⇒")]
       (cond err? [:code {:class "printed"} (prose txt 200)]
             (context-read? e) (context-ref e nil)
             :else [:div {:class "printed seon"} (value-html txt (or (:seon.render/budget env) 600) (:handle e) {})])]
      (when (and (seq (:out e)) (not (context-read? e))) [:pre {:class "out"} (prose (:out e) 200)])
      [:div {:class "meta"}
       [:span (ms-str (:ms e))] (alloc-span (:alloc-bytes e))
       [:span {:class "origin"} (kname (or (:origin e) :agent))]
       (when (:recorded e) [:span {:class "rec"} (str "＋" (:recorded e))])
       (when (:handle e) [:span {:class "handle"} (kname (:handle e))])]
      (when (seq (:fns e))
        [:table {:class "fnt"}
         [:tbody (for [{:keys [var calls ms]} (:fns e)]
                   [:tr [:td [:code (str var)]] [:td {:class "n"} (str (group3 calls) " calls")] [:td {:class "n"} (ms-str ms)]])]])]}))

(defn history-list
  "The agent's evaluations, newest first, around rows already serialized (an evaluation is
  written once, so its row is rendered once), and how many older ones its branch records
  beyond the page's (seon.render.proc/HISTORY-CAP). input {:rows :total}."
  {:malli/schema [:=> [:cat [:map [:rows [:sequential :string]] [:total nat-int?]] :seon.render/env] :seon.render/out]}
  [{:keys [rows total]} _]
  {:seon.render/html [:ol {:id "z-history" :class "history"}
                      (if (seq rows) (map h/raw rows) [:li {:class "muted"} "no evaluations yet"])
                      (more-li total (count rows) "older evaluations, recorded on its branch (:seon.cluster.eval/record)")]})

(defn zoom-head
  "The zoomed agent's head: its card's numbers, its process and its branch."
  {:malli/schema [:=> [:cat :seon.render/agent :seon.render/env] :seon.render/out]}
  [{:keys [id] :as ag} _]
  (let [st (kname (shown-status ag))]
    {:seon.render/html
     [:section {:id "z-head" :class (str "zhead cb s-" st)}
      [:h1 [:span {:class (str "dot s-" st)}] (kname id) " " [:span {:class (str "pill s-" st)} (if (:ended-as ag) (str "ended · " (kname (:ended-as ag))) st)]
       [:span {:class "branch"} (str " branch " (kname (:branch ag)))]]
      [:p {:class "task"} (str (:task ag))]
      [:div {:class "stats"}
       [:span [:em "pid"] (str (or (:pid ag) "–"))] [:span [:em "fork"] (ms-str (:forked-ms ag))]
       [:span [:em "mem"] (if (:mem-mb ag) (str (fmt1 (:mem-mb ag)) " MB") "–")]
       [:span [:em "evals"] (str (count (:evals ag)))] [:span [:em "t"] (str (or (:t ag) "–"))]
       (when (:running ag) [:span {:class "running"} "▶ " [:code (prose (:form (:running ag)) 60)]])]]}))

(defn zoom-work
  "The zoomed agent's work on main: its attempt (status, turns, tokens, outcome) and its
  task, each by its own schema's render. input {:attempt :task}."
  {:malli/schema [:=> [:cat :map :seon.render/env] :seon.render/out]}
  [{:keys [attempt task]} env]
  {:seon.render/html
   [:section {:id "z-work" :class "zwork"}
    (if attempt
      [:table {:class "attempts"}
       [:thead [:tr [:th "attempt"] [:th "agent"] [:th "status"] [:th "turns"] [:th "tokens"] [:th "outcome"] [:th "ms"]]]
       [:tbody (render/html attempt (assoc env :seon.render/schema :seon.attempt/attempt))]]
      [:p {:class "muted"} "no attempt on main: this agent was spawned by hand, not by the reconciler"])
    (when task (render/html task (assoc env :seon.render/schema :seon.task/task)))]})

(defn zoom-context
  "The current-state block this agent's model reads (seon.context/current-state, printed
  by the one value printer under the context's budget), or why there is none."
  {:malli/schema [:=> [:cat :map :seon.render/env] :seon.render/out]}
  [{:keys [text tokens why from]} _]
  {:seon.render/html
   [:section {:id "z-context" :class "box"}
    (kicker "context" (if text (str "≈" tokens " tokens · its model's current-state block"
                                    (when-not (= :door from) ", rebuilt now from main's facts and its last test run")
                                    (case from
                                      :door " · the text it last read through root's entrance, as is"
                                      :main-gone " · read on main (its branch is gone)"
                                      :main " · read on main (its branch has no commit this page has read)"
                                      nil))
                          "none"))
    (if text [:pre {:class "ctx"} text] [:p {:class "muted"} why])]})

(defn agent-page
  "One agent zoomed to the whole screen (the declared HTML render of
  :seon.render/agent-page): its head, its work (attempt and task), its proofs, its
  evaluations as rows, the context its model reads, and its REPL."
  {:malli/schema [:=> [:cat :seon.render/agent-page :seon.render/env] :seon.render/out]}
  [{:keys [agent attempt task context history] :as page} _]
  (let [id (:id agent)]
    {:seon.render/html
     [:div {:id "app" :class "zoom"}
      (topbar true)
      [:main {:class "zlayout"}
       [:div {:class "zmain"}
        (slot "z-head")
        [:form {:class "repl big" :seon/call 'seon.root/submit! :seon/agent id}
         [:span {:class "prompt"} (str (kname id) "=>")]
         [:input {:id "zi" :name "forms" :autocomplete "off" :spellcheck "false" :autofocus true
                  :placeholder "evaluate in this agent's REPL (recorded with origin :ui)"}]]
        (slot "z-history")]
       [:aside {:class "zside"} (slot "z-work") (slot "z-proofs") (slot "z-context")]]]
     :seon.render/units
     [{:id "z-head" :input agent :render `zoom-head}
      {:id "z-work" :input {:attempt attempt :task task} :render `zoom-work}
      {:id "z-proofs" :input {:tests (:tests agent) :results (:seon.attempt/results attempt)
                              :own (get-in task [:seon.task/accumulated :proofs]) :owners (:owners page)} :render `proofs-box}
      {:id "z-context" :input context :render `zoom-context}
      {:id "z-history" :input {:rows history :total (:history-total page (count history))} :render `history-list}]}))
