(ns seon.render.ui
  "The HTML renders of root's web: render functions all the way up (DECISIONS, \"Renders,
  the agent UI and context\"; W1's proto.render.ui). Ported from proto/web's
  proto.web.view (session L2's dashboard and L4's polish).

  Every page is one layout, `page`: an entity and its neighbours (seon.render/page) as its
  skeleton, with a slot where each part goes, and :seon.render/units: each part's id, the exact
  value it reads (its input) and its input's schema, whose render shows it. The render proc
  (seon.render.proc) renders a unit only when its input changed, and sends a patch only when its
  bytes changed. Each part renders by its own schema's declared render (seon.render/shown): an
  agent, a run, an evaluation, a turn, a task, a cost verdict; anything else by the one value
  printer. Layout is Tailwind's classes in the hiccup (resources/public/css/input.css).

  Every number shown is measured by the system and carried in the value; nothing here
  invents one."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [clojure.string :as str] [clojure.edn :as edn] [seon.config :as config]
            [seon.render :as render] [seon.print :as print] [seon.print.emit :as emit] [seon.print.select :as select] [seon.web.html :as h] [seon.reader :as reader] [seon.proofs :as proofs] [seon.cost :as cost]))

;;; --- small formatting helpers -------------------------------------------------

(defn- cell "Tokens of a table cell or a stream row's field." {:malli/schema [:=> [:cat] pos-int?]} [] (config/value :seon.config.render/cell-budget))
(defn- part "Tokens of a value shown inside another." {:malli/schema [:=> [:cat] pos-int?]} [] (config/value :seon.config.render/part-budget))
(defn- form-budget "Tokens of a form echoed back." {:malli/schema [:=> [:cat] pos-int?]} [] (config/value :seon.config.render/form-budget))

(defn dom-id
  "A DOM-safe id for an id keyword or string: :a12 -> \"a12\", :fx/model -> \"fx_model\"."
  {:malli/schema [:=> [:cat :any] :string]}
  [id]
  (let [s (if (keyword? id) (subs (str id) 1) (str id))]
    (apply str (map #(if (or (Character/isLetterOrDigit ^char %) (= \_ %) (= \- %)) % \_) s))))

(defn view-param
  "A view as its feed's ?view= names it (view-of reads it back)."
  {:malli/schema [:=> [:cat :any] :string]}
  [view]
  (java.net.URLEncoder/encode ^String (cond (= :root view) "root" (keyword? view) (name view) :else (str view)) "UTF-8"))

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
       (let [words (str (emit/cut-words omitted lines nil) (when whole (str " · the whole value: " (print/text whole (form-budget)) " at its REPL")))
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

(defn context-read?
  "Is evaluation e a context read: an outside agent reading its current-state block through
  root's server (origin :context; seon.server)? Its output is the block itself."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [e] (and (map? e) (= :context (:origin e))))

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

;;; --- the load, as data -------------------------------------------------------------

(defn load-tiles
  "The writer, the fork server and main as data root's context prints, one line each (seon.render.ai),
  every number from what its owner reports when it changes (the writer when it commits or flushes,
  the fork server when it forks, main when it commits)."
  {:malli/schema [:=> [:cat :map :map :map] [:vector :map]]}
  [{:keys [commits flushes] :as writer} fork-server main]
  (let [st (or (:status fork-server) :up)]
    [{:id "t-writer" :kicker "writer" :sub "the only d/transact caller" :big (group3 (:tx-count writer)) :unit "txs"
      :rows [["batch" (ms-str (:batch-p50-ms writer)) "batch p50: one writer step (a group commit and its flush, when one is due)"]
             ["tx/commit" (fmt2 (:tx-per-commit writer)) "main's transactions per group commit: one commit takes every transaction queued (at least 1)"]
             ["commits/flush" (if (and (number? commits) (number? flushes) (pos? flushes)) (fmt1 (/ (double commits) flushes)) "–")
              "main's group commits per flush: one flush makes every commit since the last durable"]
             ["flushes" (group3 flushes) "flushes to disk so far"]]
      :explain "a transaction is one write to main; a commit takes every queued transaction at once; a flush makes the commits since the last one durable on disk"}
     {:id "t-fork-server" :class (str "srv-" (kname st) " restarts-" (:restarts fork-server 0)) :kicker "fork server"
      :pill [(str "srv " (if (= st :up) "s-up" "s-down")) (kname st)]
      :big (fmt2 (:fork-ms-p50 fork-server)) :unit "ms fork p50"
      :rows [["forked" (group3 (:agents-forked fork-server))]
             ["lag" (ms-str (:lag-ms fork-server)) "commit applied in the fork server after main took it, p50"]
             ["pid" (str (or (:pid fork-server) "–"))]
             ["restarts" (str (:restarts fork-server 0)) nil (when (pos? (:restarts fork-server 0)) "hot")]]}
     {:id "t-main" :class (str "pulse-" (mod (or (:merges main) 0) 2)) :kicker "main" :sub "every write to main moves t"
      :big (group3 (:vars main)) :unit "definitions"
      :rows [["t" (group3 (:t main))] ["merges" (group3 (:merges main 0))]
             ["datoms" "on demand" "main's datom count is a walk of every datom: (:main (c/state sys {:datoms true})) at root's REPL"]]
      :explain "definitions on main now: its :var/name facts (boot loaded those with source; tasks' merges add more)"}]))

(def state-title "What each observed state means, for its pill's title (:seon.task/state)." (:seon.status/title (contract/properties :seon.task/state)))

(defn waiting-on
  "What blocked tasks wait on: each blocker once with how many tasks it holds up, the one
  holding most up first (the root cause first, not one line per blocked task)."
  {:malli/schema [:=> [:cat :map] [:vector [:tuple :any pos-int?]]]}
  [rows]
  (vec (sort-by (fn [[b n]] [(- n) (str b)]) (frequencies (for [[_ r] rows b (:blocked-by r)] b)))))

(defn attempt-row
  "An attempt: its agent, status, turns, tokens and outcome (from main's facts)."
  {:malli/schema [:=> [:cat :map :seon.render/opts] :any]}
  [{:seon.attempt/keys [id agent status turns tokens outcome reason start-ms end-ms env]} _]
     [:tr {:class (str "at s-" (kname status) (when outcome (str " o-" (kname outcome))))}
    [:td [:code id]]
    [:td (kname agent)
     (when (= "outside" (:model env)) [:span {:class "muted" :title "worked from outside (an outside agent through root's server): no turn loop, its turns and tokens are not counted here"} " · outside"])]
    [:td [:span {:class (str "pill s-" (kname status))} (kname status)]]
    [:td (str (or turns "–"))] [:td (group3 tokens)]
    [:td {:title (str reason)} (kname outcome)]
    [:td {:title "the attempt's wall time, start to end: its fork, every turn (model, evaluations, test runs in forks) and its end; its agent's page shows where it went"}
     (if (and start-ms end-ms) (group3 (- end-ms start-ms)) "–")]])

;;; --- the config ------------------------------------------------------------------

(defn config-panel
  "The config in force (the declared HTML render of :seon.config/config), as root wrote it
  on main: every key grouped by its area, its value whole, its unit, and its schema's
  description on hover. Where each value comes from (unit, provenance, its bound) is
  written beside it in resources/seon/config/default.edn."
  {:malli/schema [:=> [:cat :seon.config/config :seon.render/opts] :any]}
  [c _]
  (let [prop (fn [k p] (get (contract/properties k) p))
        groups (group-by (comp namespace key) (sort-by (comp str key) c))]
         [:section {:id "t-config" :class "tile wide config"}
      (kicker "config" (str (count c) " keys in force on main · resources/seon/config/default.edn · profile " (name (config/profile))))
      [:div {:class "cfg-groups"}
       (for [[g kvs] (sort-by key groups)]
         [:dl {:class "cfg"}
          [:dt {:class "cfg-area"} (str/replace g #"^seon\.config\.?" "")]
          (for [[k v] kvs]
            [:div {:title (str (prop k :description))}
             [:dt (name k)]
             [:dd (render/html v {:budget (part)}) (when-let [u (prop k :seon.config/unit)] [:span {:class "muted"} " " u])]])])]]))

;;; --- agent cards --------------------------------------------------------------

(defn run-words
  "A test run's counts in words: the card's, the stream's and the proofs box's one line."
  {:malli/schema [:=> [:cat [:maybe :map]] :string]}
  [t] (str (:pass t 0) " pass · " (:fail t 0) " fail · " (:error t 0) " error"))

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
  every other key of the error map is printed after them, so both faces show the same facts.
  :jolt.error/suggestions is the fix's own line (\"did you mean ...\"), and :seon.error/in and
  :seon.error/at the \"at line 3:6 of ns/f\" line, so none is printed twice."
  [:seon.error/type :seon.error/operation :seon.error/message :seon.error/fix :seon.error/problems :jolt.error/suggestions
   :seon.error/in :seon.error/at])

(defn error-hidden
  "The keys of error map e its renders do not print after the lines: `error-shown`, and the
  request-relative :jolt.error/ and :clojure.error/ line and column only when the form's own
  position (:seon.error/at) is stated, so a position is never dropped without its replacement."
  {:malli/schema [:=> [:cat :map] [:vector :keyword]]}
  [e]
  (cond-> error-shown
    (:seon.error/at e) (into [:jolt.error/line :jolt.error/column :clojure.error/line :clojure.error/column])))

(defn error-box
  "An error map (:seon.error/base, every variant): its message first, then its type and where
  it was raised, the fix when it carries one, and every other key it carries by the printer
  (`error-shown`: the AI face's lines and this one's are the same)."
  {:malli/schema [:=> [:cat :seon.error/base :seon.render/opts] :any]}
  [e _]
  (let [own (apply dissoc e (error-hidden e))
        budget (config/value :seon.config.render/part-budget)]
         [:div {:class "err"}
      [:b (prose (:seon.error/message e) budget)]
      [:span {:class "muted"} (str " · " (kname (:seon.error/type e)) " in " (:seon.error/operation e))]
      (when (:seon.error/fix e) [:p {:class "fix"} "fix: " (prose (:seon.error/fix e) budget)])
      (for [{:keys [in expected value]} (:seon.error/problems e)]
        [:p {:class "muted"} "at " [:code (print/text in budget)] " expected " [:code (print/text expected budget)] ", got " [:code value]])
      (when (seq own) [:code {:class "muted"} " " (print/text own budget)])]))

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

;;; --- root's layout ------------------------------------------------------------------

(defn ended-as
  "Agent id's entry, or an event of its stream (x), with :ended-as when its process ended
  (its status, or the event's type, is gone) after its attempt finished (main's attempt
  done or merged); else x as it is (identical: inputs compare O(1))."
  {:malli/schema [:=> [:cat [:maybe :map] :any :map] [:maybe :map]]}
  [x id agent-attempt]
  (let [st (:seon.attempt/status (get agent-attempt id))]
    (if (and x (gone (or (:status x) (:type x))) (finished st)) (assoc x :ended-as st) x)))

(defn shell
  "The document around a view's body: head, the stylesheet, Datastar, and the feed of that view's
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
    [:script {:type "module" :src "/datastar.js"}]]
   [:body
    body
    [:div {:style "display:none" :data-init (str "@get('" feed "', {retryMaxCount: Infinity, openWhenHidden: true})")}]]])

;;; --- values' own renders: a task, a verdict, a cost report ---------------------------------

(defn task-box
  "A task: its kind and execution, its plan (accumulated along its needs), instructions, what
  is wrong (its failure, held for root), its needs and proofs, and its evidence."
  {:malli/schema [:=> [:cat :seon.task/task :seon.render/opts] :any]}
  [t _]
  (let [{:keys [plan proofs]} (:seon.task/accumulated t)]
         [:section {:class "box"}
      (kicker (str "task · " (:seon.task/id t))
              (str (kname (:seon.task/kind t)) " · "
                   ;; what happened (the reconciler's observed state, when the producer carries it), else the desired execution
                   (if-let [st (:seon.task/state t)] (kname st) (kname (:seon.task/execution t)))))
      [:pre {:class "plan" :title "its plan, accumulated along its needs: theirs first"} plan]
      (when (:seon.task/instructions t) [:p {:class "muted"} (:seon.task/instructions t)])
      (when-let [{:keys [reason counterexample attempt]} (:seon.task/failure t)]
        [:p {:class "err"} "failed" (when attempt (str " in " attempt)) ": " reason (when counterexample (list " · counterexample " [:code counterexample]))])
      (when (seq (:seon.task/needs t))
        [:p {:class "muted"} "needs "
         ;; met or not, when the producer carries what blocks it (seon.reconcile/blocked-by)
         (interpose ", " (for [n (sort (:seon.task/needs t)) :let [b (:seon.task/blocked-by t)]]
                           (cond (nil? b) n (contains? b n) [:span {:class "warn"} (str n " (not done)")] :else (str n " (done)"))))])
      [:p {:class "muted"} (str (count proofs) (if (= 1 (count proofs)) " proof: " " proofs: ")) (str/join " " (map str (sort-by str proofs)))]
      (when-let [ev (:seon.task/evidence t)]
        [:div {:class "muted"} "evidence " (render/print-html ev {:budget (config/value :seon.config.render/part-budget)})])]))

(defn verdict-box
  "A cost verdict (seon.cost/verdict-lines, the agent's words too): red when a size is over
  its estimate or grew steeper."
  {:malli/schema [:=> [:cat :seon.cost/verdict :seon.render/opts] :any]}
  [v _]
  (let [[[_ head] & more] (cost/verdict-lines v nil)]
    [:div {:class (str "verdict " (if (:ok? v) "ok" "red"))} [:b head] (for [[c w] more] [:span {:class c} (str " " w)])]))

(defn cost-report
  "A cost proof's measurements against its estimate, row by size, then its verdict."
  {:malli/schema [:=> [:cat :seon.render/cost-report :seon.render/opts] :any]}
  [{:keys [rows verdict]} opts]
  (let [dims (filter (set (mapcat (comp keys :expected) rows)) [:ns :bytes :calls])]
         [:div {:class "cost"}
      [:table {:class "costrows"}
       [:thead [:tr [:th "n"] (for [d dims] [:th (str (name d) " measured / estimate")])]]
       [:tbody (for [{:keys [n measured expected]} rows]
                 [:tr [:td (group3 n)]
                  (for [d dims :let [m (get measured d) x (get expected d)]]
                    [:td {:class (when (and m x (pos? x) (> (/ m x) 3)) "bad")}
                     (str (group3 m) " / " (group3 x) (when (and m x (pos? x)) (str " (" (fmt2 (/ m x)) "×)")))])])]]
      (render/html verdict (assoc opts :schema :seon.cost/verdict))]))

(defn agent-input
  "The input at the top of an agent's page, each through root's requests (the server's ops send
  the same): a line evaluates in its REPL (seon.root/submit!, origin :ui); a line that starts with
  > is a message its next turn reads (seon.root/message!); interrupt stops its running form;
  release runs its task's next attempt when the task is held (seon.root/release!). input {:id
  :live? :held}: live, root's view holds its process in this boot."
  {:malli/schema [:=> [:cat :seon.render/agent-input :seon.render/opts] :any]}
  [{:keys [id live? held]} opts]
  [:section {:id (:unit opts) :class "flex flex-col gap-2"}
   (if live?
     [:form {:class "flex gap-2" :seon/call 'seon.root/submit! :seon/agent id}
      [:span {:class "text-signal"} (str (kname id) "=>")]
      ;; Enter sends, Shift+Enter is a new line: a form with its ;; thought spans lines
      [:textarea {:name "forms" :class "flex-1 min-w-0" :rows 2 :autocomplete "off" :spellcheck "false" :autofocus true
                  (keyword "data-on:keydown") "if (evt.key==='Enter' && !evt.shiftKey) { evt.preventDefault(); el.form.requestSubmit() }"
                  :placeholder ";; your thought\n(+ 1 2)  evaluates in its REPL (Enter; Shift+Enter for a new line) · > a message, read at its next turn"}]
      [:button {:type "button" :class "rounded border border-base-700 px-2" :title "interrupt the running form; the agent keeps its state"
                :seon/call 'seon.root/interrupt! :seon/agent id} "⏹ interrupt"]]
     [:p {:class "text-text-500"} "no process of it in root's view: no REPL to evaluate in, no turn to read a message; what it did is below, read from the store"])
   (when held
     [:button {:class "self-start rounded border border-base-700 px-2" :title (str "release " held ": the reconciler runs its next attempt (my.root/run!)")
               :seon/call 'seon.root/release! :seon/agent id :seon/args {:task held}}
      (str "▶ release " held)])])

;;; --- the page: an entity and its neighbours (seon.render/page), laid out --------------

(defn- thought
  "A form's leading ;; comment as the prose it is (its comment marks gone)."
  {:malli/schema [:=> [:cat [:maybe :string]] [:maybe :string]]}
  [lead] (when-not (str/blank? lead) (str/trim (str/replace lead #"(?m)^\s*;+ ?" ""))))

(defn- cost-line
  "What an evaluation cost, as recorded when it ran: ticks, ms and bytes allocated, each with its
  unit; one not recorded is left out, never shown as 0."
  {:malli/schema [:=> [:cat :map] :any]}
  [r]
  (when-let [parts (seq (remove nil? [(when (:ticks r) (str (group3 (:ticks r)) " ticks")) (when (:ms r) (ms-str (:ms r)))
                                       (when (:alloc-bytes r) (alloc-span (:alloc-bytes r)))]))]
    [:span {:class "tabular-nums text-text-400"} (interpose " · " parts)]))

(def ^:private asked-by
  "Who asked for an evaluation, by its record's :origin (absent: the agent's own, its model's turn)."
  {:ui "the owner, from this page" :outside "an outside agent, through root's server" :context "the context, read for a turn"})

(defn evaluation
  "One evaluation (the declared html render of :seon.db/eval-fact, its record and a reply item), as
  it ran: its ;; thought as prose, its form, its result as the REPL printed it when it ran (the
  result's own render then; an error by its error map's render), what it printed, and its cost as
  recorded; :as :inline, its form and result in one line. Rendered from the record, which never
  changes; a reply item names its text :printed and its error :err, as the REPL answered it."
  {:malli/schema [:=> [:cat :map :seon.render/opts] :any]}
  [e opts]
  (let [it (or (:seon.cluster.eval/record e) e)
        r (cond-> it (:printed it) (assoc :shown (:printed it)) (:err it) (assoc :error (:err it)))
        err? (= :err (:tag r))]
    (if (= :inline (:as opts))
      [:span {:class "font-mono"} [:code (prose (:form r) (cell) {:one-line? true})] (if err? " ✕ " " ⇒ ")
       [:code (prose (or (:shown r) (:seon.error/message (:error r)) "") (cell) {:one-line? true})]]
      [:div {:class (str "flex flex-col gap-1 border-l-2 pl-3 py-1 " (if err? "border-error" "border-base-700"))}
       (when-let [t (thought (:lead r))] [:p {:class "whitespace-pre-wrap text-text-300 italic"} (prose t (form-budget))])
       [:pre {:class "text-text-100"} [:code (prose (:form r) (form-budget))]]
       (cond (map? (:error r)) (render/html (:error r) {})
             err? [:pre {:class "text-error"} (prose (str (:error r)) (part))]
             :else [:pre {:class "text-text-200"} [:span {:class "text-text-500"} "⇒ "] (prose (or (:shown r) "") (part))])
       (when-not (str/blank? (:out r)) [:pre {:class "text-text-400"} (prose (:out r) (part))])
       [:div {:class "flex flex-wrap gap-3 text-2xs text-text-500"}
        (cost-line r)
        [:span (if (:origin r) (get asked-by (:origin r) (kname (:origin r))) "its own turn")]
        (when (seq (:fns r))
          [:details [:summary (str "costliest: " (:var (first (:fns r))) " · " (count (:fns r)) " functions")]
           [:table [:tbody (for [{:keys [var calls ms]} (:fns r)]
                             [:tr [:td [:code (str var)]] [:td {:class "tabular-nums"} (str (group3 calls) " calls")] [:td {:class "tabular-nums"} (ms-str ms)]])]]])]])))

(defn turn
  "A model turn's header (the declared html render of :seon.turn/turn-fact), above the evaluations
  it ran: which turn of how many, its tokens and the share its provider served from cache, how
  its reply stopped and its model; its reply and reasoning as prose. Nothing it did not record is
  shown."
  {:malli/schema [:=> [:cat :map :seon.render/opts] :any]}
  [{:seon.turn/keys [n origin reply reasoning tokens cache-hit-tokens cache-miss-tokens finish model errors forms truncated basis]} opts]
  (let [prompt (when (and cache-hit-tokens cache-miss-tokens) (+ cache-hit-tokens cache-miss-tokens))
        head (str (if (= :outside origin) (str "context read " n) (str "turn " (inc n) (when-let [m (:max-turns basis)] (str " of " m)))))]
    (if (= :inline (:as opts))
      [:span head]
      [:div {:class "flex flex-col gap-1"}
       [:div {:class "flex flex-wrap items-baseline gap-3 text-xs"}
        [:b {:class "text-signal"} head]
        (when tokens [:span {:class "tabular-nums text-text-400"} (str (group3 tokens) " tokens")])
        (when (and prompt (pos? prompt)) [:span {:class "tabular-nums text-text-400" :title "the share of the prompt its provider served from its prefix cache"}
                      (str (long (* 100 (/ cache-hit-tokens prompt))) "% of the prompt cached")])
        (when finish [:span {:class "text-text-500" :title "how its reply stopped"} (str "stopped: " finish)])
        (when model [:span {:class "text-text-500"} model])
        (when truncated [:span {:class "text-error"} "cut at the token limit"])
        (when (and errors (pos? errors)) [:span {:class "text-error"} (str errors " of " forms " forms failed")])]
       (when-not (str/blank? reply) [:p {:class "whitespace-pre-wrap text-text-200"} (prose reply (part))])
       (when-not (str/blank? reasoning)
         [:details [:summary "its reasoning"] [:p {:class "whitespace-pre-wrap text-text-300"} (prose reasoning (part))]])])))

(defn message
  "A message to a run (the declared html render of :seon.message/fact): who sent it and its body as prose."
  {:malli/schema [:=> [:cat :map :seon.render/opts] :any]}
  [{:seon.message/keys [from body]} opts]
  (if (= :inline (:as opts))
    [:span (str from ": ") (prose body (cell) {:one-line? true})]
    [:div {:class "border-l-2 border-info pl-3 py-1"}
     [:span {:class "text-2xs text-text-500"} (str "message from " from)]
     [:p {:class "whitespace-pre-wrap text-text-100"} (prose body (part))]]))

(defn- shown-as
  "How node nd's schema says it is shown on a page (:seon.render/as on its entity schema), else as a tile."
  {:malli/schema [:=> [:cat :map] :seon.render/as]}
  [nd] (or (:seon.render/as (contract/properties (:seon.render/schema nd))) :tile))

(defn rows
  "A page's list (node nds that show :as :row), newest first, each row a unit: a turn with the
  evaluations its calls name (:items) under it, in the order it ran them; an evaluation no turn
  names (the owner's, root's, one of a turn still running) or a message, alone. Each evaluation is
  in one row only. A row is ordered by the newest transaction that made one of its entities
  (:seon.render/t)."
  {:malli/schema [:=> [:cat [:sequential :map]] [:vector [:map [:id :string] [:parts [:vector :map]] [:newest nat-int?]]]]}
  [nds]
  (let [evals (into {} (for [nd nds :let [id (get-in nd [:seon.render/value :seon.cluster.eval/id])] :when id] [id nd]))
        turns (filter #(= :seon.turn/turn-fact (:seon.render/schema %)) nds)
        in-turn (fn [t] (vec (keep evals (for [c (get-in t [:seon.render/value :seon.turn/calls]) i (:items c)] i))))
        taken (set (for [t turns e (in-turn t)] (:seon.render/eid e)))
        row (fn [parts] {:id (str "r-" (:seon.render/eid (first parts))) :parts parts :newest (reduce max (map #(:seon.render/t % 0) parts))})]
    (->> (concat (for [t turns] (row (into [t] (in-turn t))))
                 (for [nd nds :when (and (not= :seon.turn/turn-fact (:seon.render/schema nd)) (not (taken (:seon.render/eid nd))))] (row [nd])))
         (sort-by (juxt (comp - :newest) :id))
         vec)))

(defn row
  "One row of a page's list, the unit's element (opts :unit): its parts each by its own render
  (seon.render/shown, :as :row), a turn's evaluations under its header."
  {:malli/schema [:=> [:cat [:vector :map] :seon.render/opts] :any]}
  [parts opts]
  (let [[head & more] (map #(:seon.render/hiccup (render/shown % :row {})) parts)]
    [:li {:id (:unit opts) :class "flex flex-col gap-2 rounded-md bg-base-900 p-3"}
     head (when (seq more) [:ol {:class "flex flex-col gap-2 pl-2"} (for [h more] [:li h])])]))

(defn tile
  "One tile of a page's column, the unit's element (opts :unit): node nd by its own render
  (seon.render/shown, :as :tile), a link to its own page when it is an entity."
  {:malli/schema [:=> [:cat :map :seon.render/opts] :any]}
  [nd opts]
  (let [h (:seon.render/hiccup (render/shown nd :tile {}))
        what (or (:seon.render/schema nd) (get-in nd [:seon.render/from :seon.render/attr]))]
    [:div {:id (:unit opts) :class "rounded-md bg-base-900 p-2 min-w-0 overflow-x-auto"}
     [:div {:class "text-2xs uppercase tracking-wider text-text-500"} (name what)]
     (if (= :entity (:seon.render/kind nd)) [:a {:href (str "/walk/" (:seon.render/eid nd)) :class "block text-text-200 hover:no-underline"} h] h)]))

(defn head
  "A page's main pane, the unit's element (opts :unit): its entity by its own render
  (seon.render/shown, :as :block)."
  {:malli/schema [:=> [:cat :map :seon.render/opts] :any]}
  [nd opts]
  [:section {:id (:unit opts) :class "rounded-md bg-base-900 p-3 text-sm text-text-100"} (:seon.render/hiccup (render/shown nd :block {}))])

(defn more
  "The list's end, the unit's element (opts :unit): how many older rows there are, unrendered; it
  asks for the next ones when it scrolls into view (Datastar's data-on-intersect posting /more).
  Its id carries how many rows are shown, so each next is a new element whose view is watched anew.
  input {:older n :view param}."
  {:malli/schema [:=> [:cat [:map [:older nat-int?] [:view :string]] :seon.render/opts] :any]}
  [{:keys [older view]} opts]
  (if (pos? older)
    [:p {:id (:unit opts) :class "py-3 text-center text-text-500" :data-on-intersect (str "@post('/more?view=" view "')")}
     (str older (if (= 1 older) " older row" " older rows") ", rendered as this line scrolls into view")]
    [:p {:id (:unit opts) :class "py-3 text-center text-text-500"} "nothing older"]))

(defn root-input
  "Root's input at the top of its page: spawn agents (seon.root/spawn!), or evaluate forms in
  every live agent (seon.root/submit! to :all)."
  {:malli/schema [:=> [:cat :map :seon.render/opts] :any]}
  [_ opts]
  [:section {:id (:unit opts) :class "flex flex-col gap-2 sm:flex-row"}
   [:form {:class "flex flex-1 gap-2" :seon/call 'seon.root/spawn! :seon/agent :root}
    [:input {:name "task" :class "flex-1 min-w-0" :autocomplete "off" :placeholder "a task for a new agent (Enter spawns it)"}]
    [:button {:type "button" :class "rounded border border-base-700 px-2" :seon/call 'seon.root/spawn! :seon/agent :root :seon/args {:n 10}} "spawn 10"]]
   [:form {:class "flex flex-1 gap-2" :seon/call 'seon.root/submit! :seon/agent :all}
    [:span {:class "text-signal"} "all=>"]
    [:input {:name "forms" :class "flex-1 min-w-0" :autocomplete "off" :spellcheck "false" :placeholder "a form every live agent evaluates"}]]])

(defn page
  "Page walk w (seon.render/page) laid out: the entity as the main pane, the input at the top, its
  list (the units whose schema shows them :as :row) newest first, only the newest opts :shown rows
  rendered and the rest counted, and its other units as tiles beside it, in their walk's order;
  responsive by Tailwind (one column, the tiles above the list's end on a narrow screen). Each part
  is a unit the render proc renders when its input changes. opts {:shown n :view param :input
  {:schema k :value v}}, the input for the page's focus (root's, an agent's), by its schema's render."
  {:malli/schema [:=> [:cat :seon.render/walk :map] [:map [:seon.render/html :any] [:seon.render/units [:vector :map]]]]}
  [{:seon.render/keys [root nodes]} {:keys [shown view input]}]
  (let [listed (filterv #(= :row (shown-as %)) nodes)
        tiles (remove #(= :row (shown-as %)) nodes)
        rs (rows listed)
        on (take shown rs)
        older (- (count rs) (count on))
        tile-id #(str "t-" (:seon.render/eid %) (when (= :value (:seon.render/kind %)) (str "-" (dom-id (get-in % [:seon.render/from :seon.render/attr])))))]
    {:seon.render/html
     [:div {:id "app" :class "mx-auto flex max-w-screen-2xl flex-col gap-3 p-3 sm:p-4"}
      [:nav {:class "flex items-baseline gap-3"}
       [:a {:href "/" :class "font-bold text-signal"} "◆ seon"] [:span {:class "text-text-500"} "on jolt"]]
      [:div {:class "grid gap-4 lg:grid-cols-[minmax(0,1fr)_22rem]"}
       [:main {:class "flex min-w-0 flex-col gap-3"}
        (slot "p-head") (when input (slot "p-input"))
        [:ol {:id "p-rows" :class "flex flex-col gap-2"} (for [r on] (slot (:id r)))]
        [:div {:id "p-foot"} (slot (str "p-more-" (count on)))]]
       [:aside {:id "p-tiles" :class "grid content-start gap-2 sm:grid-cols-2 lg:grid-cols-1"} (for [t tiles] (slot (tile-id t)))]]]
     :seon.render/units
     (vec (concat [{:id "p-head" :input root :schema :seon.render/page-head}]
                  (when input [{:id "p-input" :input (:value input) :schema (:schema input)}])
                  (for [r on] {:id (:id r) :input (:parts r) :schema :seon.render/page-row :container "#p-rows"})
                  [{:id (str "p-more-" (count on)) :input {:older older :view view} :schema :seon.render/page-more :container "#p-foot"}]
                  (for [t tiles] {:id (tile-id t) :input t :schema :seon.render/page-tile :container "#p-tiles"})))}))

