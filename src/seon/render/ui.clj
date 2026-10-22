(ns seon.render.ui
  "The HTML renders of root's web: render functions all the way up (DECISIONS, \"Renders,
  the agent UI and context\"; W1's proto.render.ui). Ported from proto/web's
  proto.web.view (session L2's dashboard and L4's polish): layout, tiles, swarm grid, cards,
  event stream, the REPL box per card, the Phosphor theme's classes (resources/public/app.css).

  Two layouts are declared renders: `system-page` (:seon.render/system, root's whole
  system) and `agent-page` (:seon.render/agent-page, one agent zoomed). A layout answers
  its skeleton, with a slot where each part goes, and :seon.render/units: each part's id,
  the exact value it reads (its input) and the render that shows it. The render proc
  (seon.render.proc) renders a unit only when its input changed, and sends a patch only
  when its bytes changed. Values inside a part render by their own schema's declared
  render (seon.render/html): an agent's card, a task, an attempt, an evaluation, a proc's
  health, a cost verdict; anything else by the one value printer.

  Every number shown is measured by the system and carried in the value; nothing here
  invents one."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [clojure.string :as str] [clojure.edn :as edn] [seon.config :as config]
            [seon.render :as render] [seon.print :as print] [seon.print.select :as select] [seon.web.html :as h] [seon.reader :as reader] [seon.proofs :as proofs] [seon.cost :as cost]))

;;; --- small formatting helpers -------------------------------------------------

(defn- cell "Tokens of a table cell or a stream row's field." {:malli/schema [:=> [:cat] pos-int?]} [] (config/value :seon.config.render/cell-budget))
(defn- one-line "Tokens of a message or reason in one line." {:malli/schema [:=> [:cat] pos-int?]} [] (config/value :seon.config.render/line-budget))
(defn- part "Tokens of a value shown inside another." {:malli/schema [:=> [:cat] pos-int?]} [] (config/value :seon.config.render/part-budget))
(defn- form-budget "Tokens of a form echoed back." {:malli/schema [:=> [:cat] pos-int?]} [] (config/value :seon.config.render/form-budget))

(defn dom-id
  "A DOM-safe id for an id keyword or string: :a12 -> \"a12\", :fx/model -> \"fx_model\"."
  {:malli/schema [:=> [:cat :any] :string]}
  [id]
  (let [s (if (keyword? id) (subs (str id) 1) (str id))]
    (apply str (map #(if (or (Character/isLetterOrDigit ^char %) (= \_ %) (= \- %)) % \_) s))))

(defn kname
  "A keyword's name with its namespace (:fx/model -> \"fx/model\"), \"–\" for nil, else as
  a string: every render's word for an id, a status or a kind (render.ui and render.ai)."
  {:malli/schema [:=> [:cat :any] :string]}
  [k] (cond (keyword? k) (subs (str k) 1) (nil? k) "–" :else (str k)))

(defn fmt1 {:malli/schema [:=> [:cat :any] :string]} [x] (if (number? x) (format "%.1f" (double x)) "–"))
(defn fmt2 {:malli/schema [:=> [:cat :any] :string]} [x] (if (number? x) (format "%.2f" (double x)) "–"))

(defn group3
  "n with thousands separated: 120000 -> \"120,000\"."
  {:malli/schema [:=> [:cat :any] :string]}
  [n] (if (number? n) (format "%,d" (long n)) "–"))

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
  to opts :href (the page that shows it whole), else the cut's title holds the whole text;
  with opts :whole (a form that answers the whole value, e.g. its result handle) the cut
  names that form too. opts :one-line? as the printer's (a table cell, a stream row)."
  {:malli/schema [:function [:=> [:cat :any pos-int?] :any] [:=> [:cat :any pos-int? :map] :any]]}
  ([s budget] (prose s budget {}))
  ([s budget {:keys [href one-line? whole]}]
   (let [s (if (string? s) s (print/text s budget))
         {:keys [shown truncated? omitted lines]} (print/prose s budget {:one-line? (boolean one-line?)})]
     (if-not truncated?
       shown
       (let [words (str (print/cut-words omitted lines nil) (when whole (str " · the whole value: " (print/text whole (form-budget)) " at its REPL")))
             cut (if href
                   [:a {:class "seon-print-elision" :href href :title "the whole on this page"} words]
                   [:span {:class "seon-print-elision" :title s} words])]
         [:span shown (when-not (or (empty? shown) (str/ends-with? shown "\n") (str/ends-with? shown " ")) " ") cut])))))

(def utc-offset-ms
  "Jolt has no time zones: the local offset is :seon.config.web/utc-offset (`date +%z`)."
  (let [z (config/value :seon.config.web/utc-offset)
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
  [k sub] [:header [:span {:class "kicker"} k] (if (vector? sub) sub [:span {:class "sub"} sub])])

(defn sparkline
  "An inline SVG sparkline over xs (oldest first), an area fill and a dot on the newest."
  {:malli/schema [:=> [:cat [:maybe [:sequential :any]] :string] :any]}
  [xs tone]
  (let [w 160 hgt 34 pad 2
        ;; the series as the render proc keeps it (:seon.config.render.list/series points)
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
  (let [plain? (and (some? txt) (not (str/includes? txt "…"))
                    (contains? #{\{ \[ \( \:} (first txt)))
        ;; through the one reader edge: text that does not read is an answer (nil), not a catch here
        v (when plain? (let [fs (reader/read-text txt 'user)] (when (= 1 (count fs)) (first fs))))]
    (if (and plain? (coll? v))
      (:seon.render/html (render/print-out :html v {:seon.render/budget budget :seon.render/handle (some->> handle name (symbol "result"))}))
      [:code {:class "printed"} (prose txt budget (cond-> opts handle (assoc :whole (symbol "result" (name handle)))))])))

(defn context-read?
  "Is evaluation e a context read: an outside agent reading its current-state block through
  root's server (origin :context; seon.server)? Its output is the block itself."
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

(def status-order "Agent statuses in the swarm's order (:seon.agent/status)." (contract/enum-values :seon.agent/status))
(def live "The live agent statuses (:seon.agent/status)." (:seon.status/live (contract/properties :seon.agent/status)))
(def ^:private gone (:seon.status/gone (contract/properties :seon.agent/status)))
(def ^:private finished (:seon.status/finished (contract/properties :seon.attempt/status)))

(defn shown-status
  "The word for how an agent is: its live status, or how its process ended: :ended when
  root stopped it after its attempt finished (done or merged), :killed for a kill, :died
  when its process died on its own (an agent entry carries :ended-as from main's attempt)."
  {:malli/schema [:=> [:cat :map] :keyword]}
  [a]
  (cond (or (= :ended (:status a)) (:ended-as a)) :ended
        (= :dead (:status a)) :died
        :else (or (:status a) :unknown)))

;;; --- metric tiles -------------------------------------------------------------

(defn metric-tile
  "Every tile, from data: kicker and sub (or a pill [class word]), the big number and its
  unit, a sparkline over series (oldest first) in tone, a body (hiccup: a list, a grid),
  the rows of a key-value list ([dt dd title class]) and an explaining line; each part
  only when given. A new tile is a map (`load-tiles`); root's context reads the same maps.
  input {:id :class :kicker :sub :pill :big :unit :series :tone :series-title :body :rows :explain}."
  {:malli/schema [:=> [:cat :map :seon.render/env] :seon.render/out]}
  [{:keys [id class sub pill big unit series tone series-title body rows explain] :as t} _]
  {:seon.render/html
   [:section {:id id :class (into ["tile"] (flatten [class]))}
    (kicker (:kicker t) (if pill [:span {:class (str "pill " (first pill))} (second pill)] sub))
    (when big [:div {:class "big"} [:span {:class "num"} big] [:span {:class "unit"} unit]])
    (when tone [:div {:title series-title} (sparkline series tone)])
    body
    (when rows [:dl {:class "kv"} (for [[dt dd title cls] rows] [:div {:title title :class cls} [:dt dt] [:dd dd]])])
    (when explain [:p {:class "muted explain"} explain])]})

(defn tile-agents
  "The swarm: one cell per agent colored by status (a cell flashes when its agent
  evaluates; clicking one zooms the agent), a status bar and the counts.
  input {:cells [[id status evals-seen] ...] in grid order, :off agents ended more than
  :retire-ms ago and left off the grid}."
  {:malli/schema [:=> [:cat :map :seon.render/env] :seon.render/out]}
  [{:keys [cells off retire-ms]} env]
  (let [freq (frequencies (map second cells))
        alive (reduce + 0 (map val (filter (comp live key) freq)))
        segs (for [s (concat status-order (sort-by str (remove (set status-order) (keys freq)))) :let [n (get freq s 0)] :when (pos? n)] [s n])]
    (metric-tile
      {:id "t-agents" :kicker "agents" :big (str alive) :unit "live"
       :sub (str (count cells) " on the grid" (when (pos? (or off 0)) (str " · +" off " ended over " (quot (or retire-ms 0) 1000) " s ago, off it")))
       :body (list [:div {:class "swarm"}
                    (for [[id st n] cells]
                      [:a {:class (str "cell s-" (kname st) " f" (mod (or n 0) 2)) :href (str "/agent/" (dom-id id)) :title (str (kname id) " · " (kname st))}])]
                   [:div {:class "statusbar"} (for [[s n] segs] [:span {:class (str "seg s-" (kname s)) :style {"flex-grow" n} :title (str (kname s) " " n)}])]
                   [:ul {:class "legend"} (for [[s n] segs] [:li [:i {:class (str "dot s-" (kname s))}] (kname s) " " [:b (str n)]])])}
      env)))

(defn load-tiles
  "The writer, the fork server and main as `metric-tile` data, every number from what its
  owner reports when it changes (the writer when it commits or flushes, the fork server
  when it forks, main when it commits); series: one point per change."
  {:malli/schema [:=> [:cat :map :map :map :map] [:vector :map]]}
  [{:keys [commits flushes] :as writer} fork-server main series]
  (let [st (or (:status fork-server) :up)]
    [{:id "t-writer" :kicker "writer" :sub "the only d/transact caller" :big (group3 (:tx-count writer)) :unit "txs"
      :series (:tx series) :tone "tone-signal" :series-title "transactions per writer batch, the newest batches"
      :rows [["batch" (ms-str (:batch-p50-ms writer)) "batch p50: one writer step (a group commit and its flush, when one is due)"]
             ["tx/commit" (fmt2 (:tx-per-commit writer)) "main's transactions per group commit: one commit takes every transaction queued (at least 1)"]
             ["commits/flush" (if (and (number? commits) (number? flushes) (pos? flushes)) (fmt1 (/ (double commits) flushes)) "–")
              "main's group commits per flush: one flush makes every commit since the last durable"]
             ["flushes" (group3 flushes) "flushes to disk so far"]]
      :explain "a transaction is one write to main; a commit takes every queued transaction at once; a flush makes the commits since the last one durable on disk"}
     {:id "t-fork-server" :class (str "srv-" (kname st) " restarts-" (:restarts fork-server 0)) :kicker "fork server"
      :pill [(str "srv " (if (= st :up) "s-up" "s-down")) (kname st)]
      :big (fmt2 (:fork-ms-p50 fork-server)) :unit "ms fork p50" :series (:fork series) :tone "tone-info"
      :rows [["forked" (group3 (:agents-forked fork-server))]
             ["lag" (ms-str (:lag-ms fork-server)) "commit applied in the fork server after main took it, p50"]
             ["pid" (str (or (:pid fork-server) "–"))]
             ["restarts" (str (:restarts fork-server 0)) nil (when (pos? (:restarts fork-server 0)) "hot")]]}
     {:id "t-main" :class (str "pulse-" (mod (or (:merges main) 0) 2)) :kicker "main" :sub "every write to main moves t"
      :big (group3 (:datoms main)) :unit "datoms" :series (:datoms series) :tone "tone-success"
      :rows [["t" (group3 (:t main))] ["merges" (group3 (:merges main 0))]
             ["vars" (group3 (:vars main)) "definitions on main now: its :var/name facts (boot loaded those with source; tasks' merges add more)"]]}]))

(defn proc-health
  "A declared proc's health as one line: no defect recorded, or how many and the newest
  (a step that threw; its error map is in root's errors). The one defect line: the health
  tile lists every proc, the effect owners among them, by it."
  {:malli/schema [:=> [:cat :seon.render/proc-health :seon.render/env] :seon.render/out]}
  [{:keys [pid defects newest]} _]
  (let [bad? (pos? (or defects 0))]
    {:seon.render/html
     [:li {:class (str "ph" (when bad? " abnormal s-failed"))}
      [:i {:class (str "dot " (if bad? "s-failed" "s-running"))}] [:code (kname pid)]
      (if bad?
        (list [:b (str defects " defect" (when (> defects 1) "s"))] " " [:span {:class "bad"} (prose newest (cell) {:one-line? true})])
        [:span {:class "muted"} "no defect recorded"])]}))

(defn tile-health
  "Every declared root proc, the effect owners (FX1) among them, healthy unless a problem
  was recorded of it: what flows, no ping (a proc's defect arrives when it happens; root's
  view keeps the newest 20). input {:health {pid {:defects :newest}}}."
  {:malli/schema [:=> [:cat :map :seon.render/env] :seon.render/out]}
  [{:keys [health]} env]
  (let [bad (count (filter (comp pos? :defects val) health))]
    (metric-tile
      {:id "t-health" :class ["wide" (when (pos? bad) "hot")] :kicker "health"
       :sub (str (count health) " procs · " (if (pos? bad) (str bad " with a defect recorded") "no defect recorded") " · from what they report, never pinged")
       :body [:ul {:class "health"}
              (for [[pid h] (sort-by (fn [[pid h]] [(if (pos? (:defects h 0)) 0 1) (str pid)]) health)]
                (render/html (assoc h :pid pid) (assoc env :seon.render/schema :seon.render/proc-health)))]}
      env)))

(def task-state-order "A task's observed states in the tile's order (:seon.task/state)." (contract/enum-values :seon.task/state))
(def state-title "What each observed state means, for its pill's title (:seon.task/state)." (:seon.status/title (contract/properties :seon.task/state)))

(defn waiting-on
  "What blocked tasks wait on: each blocker once with how many tasks it holds up, the one
  holding most up first (the root cause first, not one line per blocked task)."
  {:malli/schema [:=> [:cat :map] [:vector [:tuple :any pos-int?]]]}
  [rows]
  (vec (sort-by (fn [[b n]] [(- n) (str b)]) (frequencies (for [[_ r] rows b (:blocked-by r)] b)))))

(defn- attempts-table
  "Attempts as table rows by their declared render (`attempt-row`), under a header whose
  first column is label, then more rows (what the table left)."
  {:malli/schema [:=> [:cat :string [:sequential :map] :seon.render/env :any] :any]}
  [label attempts env more]
  [:table {:class "attempts"}
   [:thead [:tr [:th label] [:th "agent"] [:th "status"] [:th "turns"] [:th "tokens"] [:th "outcome"] [:th "ms"]]]
   [:tbody (for [a attempts] (render/html a (assoc env :seon.render/schema :seon.attempt/attempt))) more]])

(defn tile-tasks
  "The work as it is: every task by its observed state (what happened, not the desired
  execution), the tasks list (state, what blocks it, proofs green/red on its newest
  attempt, attempts so far, tokens; each links to its newest attempt's agent), then the
  newest attempts. input {:rows (seon.render.proc/task-rows) :attempts :main-t}."
  {:malli/schema [:=> [:cat :map :seon.render/env] :seon.render/out]}
  [{:keys [rows attempts main-t]} env]
  (let [freq (frequencies (map :state (vals rows)))
        [newest older] (print/bounded (sort-by (comp - #(or (:seon.attempt/start-ms %) 0)) (vals attempts)) :seon.config.render.list/rows
                                      "older, on main: (seon.task/attempts (deref (:conn sys))) at root's REPL")
        tokens (reduce + 0 (map :tokens (vals rows)))
        running (count (filter #(= :running (:seon.attempt/status %)) (vals attempts)))
        rank (zipmap task-state-order (range))]
    {:seon.render/html
     [:section {:id "t-tasks" :class "tile wide"}
      (kicker "reconciler · tasks" (str "main t " (group3 main-t) " · " running " attempt" (when (not= 1 running) "s") " running"))
      [:div {:class "states"}
       [:span [:b (str (count rows))] " tasks"]
       (for [k task-state-order :let [n (get freq k 0)] :when (pos? n)]
         [:span {:class (str "pill s-" (name k)) :title (state-title k)} (str (name k) " " n)])
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
               [:td {:title (str "its own proofs green / red on its newest attempt"
                                 (when-let [a (get-in r [:newest :seon.attempt/id])] (str ", " a))
                                 "; allowed red: other tasks' proofs red on that attempt's branch (not done yet), which do not block it")}
                (if (pos? (+ (:green r) (:red r)))
                  (list [:span {:class "ok"} (str (:green r) " ✓")] (when (pos? (:red r)) [:span {:class "bad"} (str " " (:red r) " ✕")])
                        (when (pos? (:allowed-red r 0)) [:span {:class "muted"} (str " · " (:allowed-red r) " allowed red")]))
                  [:span {:class "muted"} (str (:proofs r) " to run")])]
               [:td (str (:attempts r))] [:td (group3 (:tokens r))]])]]
          (attempts-table "newest attempts" newest env (when older [:tr {:class "more"} [:td {:colspan 7 :class "muted"} older]]))))]}))

(defn attempt-row
  "An attempt: its agent, status, turns, tokens and outcome (from main's facts)."
  {:malli/schema [:=> [:cat :map :seon.render/env] :seon.render/out]}
  [{:seon.attempt/keys [id agent status turns tokens outcome reason start-ms end-ms env]} _]
  {:seon.render/html
   [:tr {:class (str "at s-" (kname status) (when outcome (str " o-" (kname outcome))))}
    [:td [:code id]]
    [:td (if agent [:a {:href (str "/agent/" (dom-id agent))} (kname agent)] "–")
     (when (= "outside" (:model env)) [:span {:class "muted" :title "worked from outside (an outside agent through root's server): no turn loop, its turns and tokens are not counted here"} " · outside"])]
    [:td [:span {:class (str "pill s-" (kname status))} (kname status)]]
    [:td (str (or turns "–"))] [:td (group3 tokens)]
    [:td {:title (str reason)} (kname outcome)]
    [:td {:title "the attempt's wall time, start to end: its fork, every turn (model, evaluations, test runs in forks) and its end; its agent's page shows where it went"}
     (if (and start-ms end-ms) (group3 (- end-ms start-ms)) "–")]]})

(defn tile-errors
  "Root's own defects (a step that threw, recorded as data by the error reader), the
  boot's numbers and the store."
  {:malli/schema [:=> [:cat :map :seon.render/env] :seon.render/out]}
  [{:keys [root-errors root-errors-total boot store]} env]
  (let [total (or root-errors-total (count root-errors))
        [shown more] (print/bounded (rseq (vec root-errors)) :seon.config.render.list/newest
                                    (str "older: (:root-errors (seon.root/state sys)) keeps the newest " (count root-errors)) total)]
    (metric-tile
      {:id "t-errors" :class (when (seq root-errors) "hot") :kicker "root" :sub (str total " defects in root's own procs (agents' problems: the banner)")
       :body (if (seq root-errors)
               [:ul {:class "errs"} (for [e shown] [:li [:code (kname (:pid e))] " " (prose (:message e) (one-line))]) (when more [:li {:class "more muted"} more])]
               [:p {:class "muted"} "no proc has thrown"])
       :rows [["boot" (ms-str (:load-ms boot)) "boot: open the store, load main's program, arm contracts, fork the fork server"]
              ["program" (str (get-in boot [:program :loaded] "–") " defs") "definitions of main's program loaded at boot (seon.program/load-all!: :var/name facts with source)"]
              ["contracts" (str (get-in boot [:contracts :armed] "–") " armed")]
              ["store" (kname (:backend store))]
              ["commits" (group3 (:commits store)) "main's group commits so far (the writer tile has transactions per commit and commits per flush)"]]}
      env)))

;;; --- the config ------------------------------------------------------------------

(defn config-panel
  "The config in force (the declared HTML render of :seon.config/config), as root wrote it
  on main: every key grouped by its area, its value whole, its unit, and its schema's
  description on hover. Where each value comes from (unit, provenance, its bound) is
  written beside it in resources/seon/config/default.edn."
  {:malli/schema [:=> [:cat :seon.config/config :seon.render/env] :seon.render/out]}
  [c _]
  (let [prop (fn [k p] (get (contract/properties k) p))
        groups (group-by (comp namespace key) (sort-by (comp str key) c))]
    {:seon.render/html
     [:section {:id "t-config" :class "tile wide config"}
      (kicker "config" (str (count c) " keys in force on main · resources/seon/config/default.edn · profile " (name (config/profile))))
      [:div {:class "cfg-groups"}
       (for [[g kvs] (sort-by key groups)]
         [:dl {:class "cfg"}
          [:dt {:class "cfg-area"} (str/replace g #"^seon\.config\.?" "")]
          (for [[k v] kvs]
            [:div {:title (str (prop k :description))}
             [:dt (name k)]
             [:dd (render/html v {:seon.render/budget (part)}) (when-let [u (prop k :seon.config/unit)] [:span {:class "muted"} " " u])]])])]]}))

;;; --- agent cards --------------------------------------------------------------

(defn run-words
  "A test run's counts in words: the card's, the stream's and the proofs box's one line."
  {:malli/schema [:=> [:cat [:maybe :map]] :string]}
  [t] (str (:pass t 0) " pass · " (:fail t 0) " fail · " (:error t 0) " error"))

(defn- test-dots {:malli/schema [:=> [:cat [:maybe :map]] :any]}
  [tests]
  (if-not tests
    [:div {:class "tests none"} [:span {:class "label"} "tests"] [:span {:class "muted"} "not run"]]
    (let [{:keys [pass fail error reports]} tests
          total (+ (or pass 0) (or fail 0) (or error 0))]
      [:div {:class (str "tests " (if (proofs/red? tests) "red" "green"))
             :title "its last test run: every test of its branch's program, other tasks' proofs included; its task is judged on its own proofs (its page labels each red blocking or allowed)"}
       [:span {:class "label"} "tests"]
       [:span {:class "dots"}
        (if (seq reports)
          (for [r (sort-by #(if (= :pass (:type %)) 1 0) reports)]
            [:i {:class (str "t-" (kname (:type r))) :title (str (:var r) (when (:message r) (str " · " (:text (print/prose (:message r) (one-line))))))}])
          (concat (repeat (or pass 0) [:i {:class "t-pass"}]) (repeat (- total (or pass 0)) [:i {:class "t-fail"}])))]
       [:span {:class "score"} (str pass "/" total)]
       (when (:ms tests) [:span {:class "muted"} (ms-str (:ms tests))])])))

(defn fns-line
  "An evaluation's costliest contracted functions (seon.profile: calls exact, ms estimated
  from 1 in 64 timed), the top few (:seon.config.render.list/top), and how many more its
  row on the agent's page shows."
  {:malli/schema [:=> [:cat [:maybe [:sequential :map]]] :any]}
  [fns]
  (when (seq fns)
    (let [[shown more] (print/bounded fns :seon.config.render.list/top "on its page")]
      [:span {:class "fns" :title "costliest contracted functions: calls (exact) · ms (estimated)"}
       (for [{:keys [var calls ms]} shown]
         [:span {:class "fn"} [:code (name var)] [:i (str (group3 calls) "×")] (when (and ms (pos? ms)) [:i (ms-str ms)])])
       (when more [:span {:class "muted"} more])])))

(defn alloc-span
  "An evaluation's allocated bytes, flagged when large (over :seon.config.render/big-alloc)."
  {:malli/schema [:=> [:cat :any] :any]}
  [b]
  (if (and (number? b) (> b (config/value :seon.config.render/big-alloc)))
    [:span {:class "big-alloc" :title "this evaluation allocated a lot: a cost to look at"} (str "⚠ " (bytes-str b) " alloc")]
    [:span (bytes-str b)]))

(defn merge-words
  "A merge's result as [how why]: :merged (where main is now), :conflict (the keys both
  sides changed), or :refused (its red tests, else its error's message). The card's pill
  and the stream's row say it so."
  {:malli/schema [:=> [:cat :map] [:tuple :keyword :string]]}
  [r]
  (cond (:merged? r) [:merged (str "→ main" (when (:t r) (str " · t " (:t r))) (when (:tx r) (str " · tx " (:tx r))))]
        (seq (:conflicts r)) [:conflict (str (count (:conflicts r)) " keys changed on both sides")]
        (proofs/red? (:tests r)) [:refused (str "tests " (run-words (:tests r)))]
        :else [:refused (or (:seon.error/message r) "refused")]))

(def error-shown
  "The keys an error's renders show in their own lines (seon.render.ai/error, `error-box`);
  every other key of the error map is printed after them, so both faces show the same facts."
  [:seon.error/type :seon.error/operation :seon.error/message :seon.error/fix :seon.error/problems])

(defn error-box
  "An error map (:seon.error/base, every variant): its message first, then its type and where
  it was raised, the fix when it carries one, and every other key it carries by the printer
  (`error-shown`: the AI face's lines and this one's are the same)."
  {:malli/schema [:=> [:cat :seon.error/base :seon.render/env] :seon.render/out]}
  [e _]
  (let [own (apply dissoc e error-shown)
        budget (config/value :seon.config.render/part-budget)]
    {:seon.render/html
     [:div {:class "err"}
      [:b (prose (:seon.error/message e) budget)]
      [:span {:class "muted"} (str " · " (kname (:seon.error/type e)) " in " (:seon.error/operation e))]
      (when (:seon.error/fix e) [:p {:class "fix"} "fix: " (prose (:seon.error/fix e) budget)])
      (for [{:keys [in expected value]} (:seon.error/problems e)]
        [:p {:class "muted"} "at " [:code (print/text in budget)] " expected " [:code (print/text expected budget)] ", got " [:code value]])
      (when (seq own) [:code {:class "muted"} " " (print/text own budget)])]}))

(defn- status-pill
  "How an agent is, as its pill: its status word, or how it ended once its attempt did."
  {:malli/schema [:=> [:cat :map :string] :any]}
  [ag st]
  [:span {:class (str "pill s-" st) :title (when (:ended-as ag) (str "root stopped it after its attempt ended " (kname (:ended-as ag))))}
   (if (:ended-as ag) (str "ended · " (kname (:ended-as ag))) st)])

(defn- agent-stats
  "An agent's numbers, on its card and its zoomed head: pid, fork to ready, memory, its
  evaluations (n), its branch's t, then more (the zoomed head's running form)."
  {:malli/schema [:=> [:cat :map nat-int? :any] :any]}
  [ag n more]
  [:div {:class "stats"}
   [:span [:em "pid"] (str (or (:pid ag) "–"))]
   [:span {:title "fork to ready"} [:em "fork"] (ms-str (:forked-ms ag))]
   [:span [:em "mem"] (if (:mem-mb ag) (str (fmt1 (:mem-mb ag)) " MB") "–")]
   [:span [:em "evals"] (str n)]
   (when (:t ag) [:span {:title "its branch's t"} [:em "t"] (str (:t ag))])
   more])

(defn- eval-meta
  "An evaluation's time, allocation, who asked (origin), what it recorded, its first
  warning and its result handle: a card's row and a history row alike."
  {:malli/schema [:=> [:cat :map] :any]}
  [e]
  [:div {:class "meta"}
   [:span (ms-str (:ms e))] (alloc-span (:alloc-bytes e))
   [:span {:class "origin"} (kname (or (:origin e) :agent))]
   (when (:recorded e) [:span {:class "rec" :title "recorded as a program fact on this branch"} (str "＋" (:recorded e))])
   (let [[ws more] (print/bounded (:warnings e) :seon.config.render.list/top "on its page")]
     (list (for [w ws] [:span {:class "warn" :title (str (:seon.repl/fix w))} (str "⚠ " (kname (:seon.repl/finding w)))])
           (when more [:span {:class "muted"} more])))
   (when (:handle e) [:span {:class "handle"} (kname (:handle e))])])

(defn- eval-out
  "An evaluation's arrow and value, a card's row and a history row alike: its text (a reply
  item's :printed, a record's :shown), a context read as its reference, and a value printed
  structurally (value-html) under value-budget when structural?, else as prose under
  text-budget; a cut links to href."
  {:malli/schema [:=> [:cat :map pos-int? pos-int? [:maybe :string] :boolean] :any]}
  [e value-budget text-budget href structural?]
  (let [err? (= :err (:tag e)) txt (or (:printed e) (:shown e))]
    (list [:span {:class "arrow"} (if err? "✕" "⇒")]
          (cond (and (context-read? e) (not err?)) (context-ref e href)
                (map? (:err e)) (render/html (:err e) {})
                (and structural? (not err?)) [:div {:class "printed seon"} (value-html txt value-budget (:handle e) {:href href})]
                :else [:code {:class "printed"} (prose txt text-budget {:href href})]))))

(defn- eval-row
  "One evaluation inside a card: its form, value, time, allocation and top functions. Only
  the newest is printed structurally (the printer's cost: inputs before render); a cut
  links to the agent's page (href), which shows its evaluations whole."
  {:malli/schema [:=> [:cat :map :boolean :string] :any]}
  [e newest? href]
  [:li {:class (str "ev tag-" (kname (or (:tag e) :ret)))}
   (when-not (str/blank? (:form e)) [:code {:class "form"} (prose (:form e) (cell) {:one-line? true :href href})])
   [:div {:class "out"} (eval-out e (part) (one-line) href newest?)]
   (eval-meta e)
   (when newest? (fns-line (:fns e)))])

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
         (let [[k why] (merge-words m)]
           [:span {:class (str "pill s-" (name k)) :title why} ({:merged "⇡ merged" :conflict "⚔ conflict" :refused "✕ refused"} k)]))
       (status-pill ag st)]
      (when-let [t (:task ag)] [:p {:class "task"} (prose t (one-line) {:href href})])
      (agent-stats ag total nil)
      (when (:error ag) [:p {:class "err"} (prose (:seon.error/message (:error ag)) (one-line))])
      (when running [:p {:class "running" :title "evaluating now"} "▶ " [:code (prose (:form running) (cell) {:one-line? true :href href})]])
      (if last-e
        (let [[shown more] (print/bounded (rseq (vec evals)) :seon.config.render.list/newest "earlier evaluations on its page" total)]
          [:ol {:class "evals"} (map-indexed (fn [i e] (eval-row e (zero? i) href)) shown)
           ;; closed, the card shows the newest; open (⤢), the newest few
           (when-let [m (print/more total 1 (str "earlier; ⤢ shows the newest " (count shown) ", its page all " total))] [:li {:class "more muted closed"} m])
           (when more [:li {:class "more muted opened"} more])])
        [:ol {:class "evals empty"} [:li {:class "ev"} [:span {:class "muted"} "no evaluations yet"]]])
      (test-dots (:tests ag))
      ;; its attempt passed the done rule, so every red above is another task's proof
      (when (and (:ended-as ag) (proofs/red? (:tests ag)))
        [:p {:class "muted allowed"} (str "its attempt ended " (kname (:ended-as ag)) ": its own proofs passed; these reds are other tasks' proofs, allowed")])]}))

(defn card-shell
  "One whole agent card around its body's slot: appended to the grid once, when the agent
  first appears. Its REPL box posts the forms as a UI call (an eval recorded with origin
  :ui); its buttons are UI calls of root's functions. Its functions that need a live process (and
  its REPL box) carry the class `live`: an ended agent's card hides them (app.css, by the
  body's state class), keeping only its history."
  {:malli/schema [:=> [:cat :keyword] :any]}
  [id]
  (let [d (dom-id id)
        button (fn [f label title & [cls]]
               [:button {:class (str "act live" (when cls (str " " cls))) :title title :seon/call f :seon/agent id} label])]
    [:article {:id (str "card-" d) :class "card" (keyword "data-class:open") (str "$sel==='" d "'")}
     (slot (str "cb-" d))
     [:form {:class "repl live" :seon/call 'seon.root/submit! :seon/agent id}
      [:span {:class "prompt"} (str (kname id) "=>")]
      [:input {:id (str "ri-" d) :name "forms" :autocomplete "off" :spellcheck "false" :placeholder "(+ 1 2)"}]]
     [:div {:class "actions"}
      [:button {:class "act" :title "show history" (keyword "data-on:click") (str "$sel = $sel==='" d "' ? '' : '" d "'")} "⤢"]
      (button 'seon.root/run-tests! "▶ tests" "run its deftests in a fork of it")
      (button 'seon.root/merge! "⇡ merge" "root merges this branch into main, gated by its tests")
      (button 'seon.root/merge-main! "⇣ main" "git merge main: main's changes into this branch")
      (button 'seon.root/clone! "⑂ clone" "fork this live agent, values and all")
      (button 'seon.root/interrupt! "⏹" "interrupt the running form; the agent keeps its state")
      (button 'seon.root/kill! "✕ kill" "SIGKILL its process" "danger")]]))

;;; --- events ---------------------------------------------------------------------

(defn event-kind
  "An event's kind as the stream says it: ended for a stop after its attempt finished."
  {:malli/schema [:=> [:cat :map] :string]}
  [ev] (if (:ended-as ev) "ended" (kname (:type ev))))

(defn event-detail
  "What an event says, in one line: the stream's row and root's context's newest events."
  {:malli/schema [:=> [:cat :map] :string]}
  [ev]
  (case (:type ev)
    :reply (let [items (:items ev) r (last items)]
             (if (context-read? r)
               (str "read its current-state block · ≈" (select/estimate (or (:out r) "")) " tokens")
             (str (when (> (count items) 1) (str (count items) " forms · "))
                  (let [n (count (filter #(= :err (:tag %)) items))] (when (pos? n) (str n " error" (when (> n 1) "s") " · ")))
                  (when-not (str/blank? (:form r)) (str (print/line (:form r) (cell)) " ⇒ "))
                  (print/line (:printed r) (cell))
                  (when (:ms r) (str " · " (ms-str (:ms r)))))))
    :commit (str (kname (:branch ev)) " t " (:t ev) (when (:txs ev) (str " · " (:txs ev) " tx")))
    :merged (let [[k why] (merge-words (:result ev))] (if (= :merged k) why (str (str/upper-case (name k)) " · " (print/line why (cell)))))
    :tests (str (run-words ev) (when (:ms ev) (str " · " (ms-str (:ms ev)))))
    :merged-main (let [r (:result ev)]
                   (if-not (contract/error? r) (str "main → branch · " (count (:applied r)) " applied · " (count (:conflicts r)) " conflicts")
                       (str "refused: " (print/line (:seon.error/message r) (cell)))))
    :fork-server-down (str "fork server pid " (:pid ev) " is gone · agents keep running")
    :fork-server-up (str "root forked a new fork server · pid " (:pid ev))
    :spawned (if (:cloned-from ev)
               (str "cloned from " (kname (:cloned-from ev)) " · pid " (:pid ev))
               (str "forked in " (ms-str (:fork-ms ev)) (when (:pid ev) (str " · pid " (:pid ev)))))
    :ready (str "ready" (when (:start-ms ev) (str " in " (ms-str (:start-ms ev)))))
    :started (str "began " (print/line (or (:form ev) "") 30))
    :tests-started (str "test child forked · pid " (:pid ev))
    :killed (if (:ended-as ev) (str "stopped by root after its attempt ended " (kname (:ended-as ev))) "KILLED")
    :ended (str "stopped by root: its attempt ended" (when (:ended-as ev) (str " " (kname (:ended-as ev)))))
    :dead (if (:ended-as ev) (str "exited after its attempt ended " (kname (:ended-as ev))) "its process died")
    :ui (str (:call ev) (when (:detail ev) (str " · " (print/line (:detail ev) (cell)))))
    (print/text (dissoc ev :type :at-ms :agent) (cell))))

(defn- bad-event? {:malli/schema [:=> [:cat :map] :boolean]}
  [ev]
  (boolean (or (some #(= :err (:tag %)) (:items ev))
               (and (= :tests (:type ev)) (proofs/red? ev))
               (and (= :dead (:type ev)) (not (:ended-as ev)))
               (#{:fork-server-down :fork-server-lost} (:type ev))
               (contract/error? (:result ev))
               (and (= :ui (:type ev)) (some? (:error ev))))))

(defn event-row
  "One event of root's stream: time, kind, who, what."
  {:malli/schema [:=> [:cat :seon.render/event :seon.render/env] :seon.render/out]}
  [ev _]
  {:seon.render/html
   [:li {:id (when (:seq ev) (str "ev-" (:seq ev))) :class (str "e e-" (event-kind ev) (when (bad-event? ev) " e-err"))}
    [:time (clock (:at-ms ev))]
    [:span {:class "etype"} (event-kind ev)]
    [:span {:class "eagent"} (if (:agent ev) [:a {:href (str "/agent/" (dom-id (:agent ev)))} (kname (:agent ev))] "root")]
    [:span {:class "edetail"} (event-detail ev)]]})

(defn events-list
  "The stream around rows already serialized (each event's row is rendered once)."
  {:malli/schema [:=> [:cat [:sequential :string] :seon.render/env] :seon.render/out]}
  [rows _]
  {:seon.render/html [:ol {:id "events" :class "events"} (map h/raw rows)]})

(defn events-count
  "The stream's header: newest first, and how many of the events since root's boot it
  keeps (:seon.config.render.list/stream); the older are not kept anywhere."
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
  "The tabs open. The render step's own numbers (passes, ms, patches) are not shown here: a
  meter that changes with every pass it is shown by would patch itself (AGENTS: a
  measurement that disturbs itself); they are at /render.edn."
  {:malli/schema [:=> [:cat :map :seon.render/env] :seon.render/out]}
  [w _]
  {:seon.render/html
   [:div {:id "webstats" :class "webstats"}
    [:span {:class "live-dot"}]
    [:span (str (:tabs w 0) " tab" (when (not= 1 (:tabs w)) "s") " open")]
    [:a {:href "/render.edn" :title "the render step's own numbers: passes (one per change, never on a clock), ms per pass, patches"} "render stats"]]})

;;; --- root's layout ------------------------------------------------------------------

(defn ended-as
  "Agent id's entry, or an event of its stream (x), with :ended-as when its process ended
  (its status, or the event's type, is gone) after its attempt finished (main's attempt
  done or merged); else x as it is (identical: inputs compare O(1))."
  {:malli/schema [:=> [:cat [:maybe :map] :any :map] [:maybe :map]]}
  [x id agent-attempt]
  (let [st (:seon.attempt/status (get agent-attempt id))]
    (if (and x (gone (or (:status x) (:type x))) (finished st)) (assoc x :ended-as st) x)))

(defn tile-problems
  "What is wrong now, at the top of the page: the same lines root's context leads with."
  {:malli/schema [:=> [:cat [:sequential :string] :seon.render/env] :seon.render/out]}
  [lines _]
  {:seon.render/html
   (if (empty? lines)
     [:section {:id "problems" :class "problems ok"} [:span {:class "dot s-running"}] [:b "no problems"]
      [:span {:class "muted"} " no proc defect · no problem recorded on main · no red live agent · no agent died"]]
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
  [{:keys [agents health series writer fork-server main attempts root-errors boot store web agent-attempt] :as sys} _]
  (let [order (:order sys)
        procs (into {} (remove (fn [[pid]] (and (keyword? pid) (str/starts-with? (str (namespace pid)) "fx.")))) health)
        u (fn [id input render] {:id id :input input :render render})]
    {:seon.render/html
     [:div {:id "app"}
      (topbar false)
      [:main {:class "layout"}
       [:div {:class "left"}
        (slot "problems")
        [:div {:class "tiles"} (slot "t-agents") (slot "t-writer") (slot "t-fork-server") (slot "t-main")]
        [:div {:class "tiles two"} (slot "t-health") (slot "t-tasks")]
        [:div {:class "tiles three"} (slot "t-errors")]
        (slot "t-config")
        [:div {:id "grid" :class "grid"} (for [id order] (slot (str "card-" (dom-id id))))]]
       [:aside {:class "side"}
        (slot "ctx")
        [:div {:class "stream"}
         [:header [:span {:class "kicker"} "event stream"] (slot "events-n")]
         (slot "events")]]]]
     :seon.render/units
     (vec (concat
           [(u "problems" (:problems sys []) `tile-problems)
            (u "t-agents" {:cells (mapv (fn [id] (let [a (get agents id)] [id (shown-status (ended-as a id agent-attempt)) (count (:evals a))])) order)
                           :off (- (count agents) (count order)) :retire-ms (:retire-ms sys)} `tile-agents)]
           (for [t (load-tiles writer fork-server main series)] (u (:id t) t `metric-tile))
           [(u "t-health" {:health procs} `tile-health)
            (u "t-tasks" {:rows (:task-rows sys) :attempts attempts :main-t (:t main)} `tile-tasks)
            (u "t-errors" {:root-errors root-errors :root-errors-total (:root-errors-total sys) :boot boot :store store} `tile-errors)
            (u "ctx" (:context sys) `context-panel)
            ;; the stream's patches are the render proc's prepends; this unit keeps the list
            ;; for pages and keyframes
            (assoc (u "events" (:event-rows sys []) `events-list) :keyframe-only true)
            (u "events-n" {:kept (count (:events sys)) :seen (:events-seen sys 0)} `events-count)
            (u "webstats" web `web-stats)]
           ;; the config root wrote on main, by its schema's declared render (none before boot's fact)
           (when-let [c (:config sys)] [{:id "t-config" :input c :schema :seon.config/config}])
           (for [id order]
             {:id (str "cb-" (dom-id id)) :input (ended-as (get agents id) id agent-attempt) :agent id :schema :seon.render/agent
              :shell (str "card-" (dom-id id)) :container "#grid"})))}))

;;; --- one agent, zoomed -----------------------------------------------------------------

(defn task-box
  "A task: its kind and execution, its plan (accumulated along its needs), instructions, what
  is wrong (its problem, failure, stuck fact), its needs and proofs, and its evidence."
  {:malli/schema [:=> [:cat :seon.task/task :seon.render/env] :seon.render/out]}
  [t _]
  (let [{:keys [plan proofs]} (:seon.task/accumulated t)]
    {:seon.render/html
     [:section {:class "box"}
      (kicker (str "task · " (:seon.task/id t)) (str (kname (:seon.task/kind t)) " · " (kname (:seon.task/execution t))))
      [:pre {:class "plan"} plan]
      (when (:seon.task/instructions t) [:p {:class "muted"} (:seon.task/instructions t)])
      (when (:seon.task/problem t) [:p {:class "err"} (:seon.task/problem t)])
      (when-let [{:keys [reason counterexample attempt]} (:seon.task/failure t)]
        [:p {:class "err"} "failed" (when attempt (str " in " attempt)) ": " reason (when counterexample (list " · counterexample " [:code counterexample]))])
      (when-let [{:keys [cause blocks]} (:seon.task/stuck t)]
        [:p {:class "warn"} "budget spent: " cause (when (seq blocks) (str " · blocks " (str/join ", " (sort blocks))))])
      (when (seq (:seon.task/needs t)) [:p {:class "muted"} "needs " (str/join ", " (sort (:seon.task/needs t)))])
      [:p {:class "muted"} (str (count proofs) (if (= 1 (count proofs)) " proof: " " proofs: ")) (str/join " " (map str (sort-by str proofs)))]
      (when-let [ev (:seon.task/evidence t)]
        [:div {:class "muted"} "evidence " (:seon.render/html (render/print-out :html ev {:seon.render/budget (config/value :seon.config.render/part-budget)}))])]}))

(defn verdict-box
  "A cost verdict (seon.cost/verdict-lines, the agent's words too): red when a size is over
  its estimate or grew steeper."
  {:malli/schema [:=> [:cat :seon.cost/verdict :seon.render/env] :seon.render/out]}
  [v _]
  (let [[[_ head] & more] (cost/verdict-lines v nil)]
    {:seon.render/html [:div {:class (str "verdict " (if (:ok? v) "ok" "red"))} [:b head] (for [[c w] more] [:span {:class c} (str " " w)])]}))

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
     (when (and (:message r) (not cost))
       (if pass?
         [:span {:class "muted"} " " (prose (:message r) (config/value :seon.config.render/line-budget) {:one-line? true})]
         [:pre {:class "msg"} (prose (:message r) (config/value :seon.config.render/form-budget))]))
     (cond cost (render/html cost (assoc env :seon.render/schema :seon.render/cost-report))
           ;; :expected and :actual are the test child's printed text: shown as it is
           (not= :pass (:type r)) [:div {:class "fail"} [:div [:em "expected "] [:code (prose (:expected r) (config/value :seon.config.render/part-budget))]]
                                   [:div [:em "actual "] [:code (prose (:actual r) (config/value :seon.config.render/part-budget))]]])]))

(defn proofs-box
  "The agent's last test run: every report, the red ones first, cost proofs with their
  measurements. input {:tests :results} (results: main's attempt results by proof)."
  {:malli/schema [:=> [:cat :map :seon.render/env] :seon.render/out]}
  [{:keys [tests results own owners]} env]
  {:seon.render/html
   [:section {:class "box" :id "z-proofs"}
    (kicker "proofs" (if tests (str (run-words tests) " · " (ms-str (:ms tests))) "not run"))
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
  {:seon.render/html
     [:li {:class (str "hr tag-" (kname (or (:tag e) :ret)))}
      [:div {:class "hf"} [:code (prose (:form e) (form-budget))]]
      [:div {:class "hv"} (eval-out e (or (:seon.render/budget env) (config/value :seon.config.render/value-budget)) (part) nil true)]
      (when (and (seq (:out e)) (not (context-read? e))) [:pre {:class "out"} (prose (:out e) (part))])
      (eval-meta e)
      (when (seq (:fns e))
        [:table {:class "fnt"}
         [:tbody (for [{:keys [var calls ms]} (:fns e)]
                   [:tr [:td [:code (str var)]] [:td {:class "n"} (str (group3 calls) " calls")] [:td {:class "n"} (ms-str ms)]])]])]})

(defn history-list
  "The agent's evaluations, newest first, around rows already serialized (an evaluation is
  written once, so its row is rendered once), and how many older ones its branch records
  beyond the page's (:seon.config.render.list/history). input {:rows :total}."
  {:malli/schema [:=> [:cat [:map [:rows [:sequential :string]] [:total nat-int?]] :seon.render/env] :seon.render/out]}
  [{:keys [rows total]} _]
  {:seon.render/html [:ol {:id "z-history" :class "history"}
                      (if (seq rows) (map h/raw rows) [:li {:class "muted"} "no evaluations yet"])
                      (when-let [m (print/more total (count rows) "older evaluations, recorded on its branch (:seon.cluster.eval/record)")] [:li {:class "more muted"} m])]})

(defn zoom-head
  "The zoomed agent's head: its card's numbers, its process and its branch."
  {:malli/schema [:=> [:cat :seon.render/agent :seon.render/env] :seon.render/out]}
  [{:keys [id] :as ag} _]
  (let [st (kname (shown-status ag))]
    {:seon.render/html
     [:section {:id "z-head" :class (str "zhead cb s-" st)}
      [:h1 [:span {:class (str "dot s-" st)}] (kname id) " " (status-pill ag st)
       [:span {:class "branch"} (str " branch " (kname (:branch ag)))]]
      [:p {:class "task"} (str (:task ag))]
      (agent-stats ag (count (:evals ag)) (when (:running ag) [:span {:class "running"} "▶ " [:code (prose (:form (:running ag)) (one-line))]]))]}))

(defn zoom-work
  "The zoomed agent's work on main: its attempt (status, turns, tokens, outcome) and its
  task, each by its own schema's render. input {:attempt :task}."
  {:malli/schema [:=> [:cat :map :seon.render/env] :seon.render/out]}
  [{:keys [attempt task]} env]
  {:seon.render/html
   [:section {:id "z-work" :class "zwork"}
    (if attempt
      (attempts-table "attempt" [attempt] env nil)
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
                                    ", rebuilt now from main's facts and its last test run"
                                    (case from
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
