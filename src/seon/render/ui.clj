(ns seon.render.ui
  "The HTML renders of root's web: render functions all the way up (DECISIONS, \"Renders,
  the agent UI and context\"; W1's proto.render.ui). Ported from proto/web's
  proto.web.view (session L2's dashboard and L4's polish).

  Every page is one layout, `page`: an entity and its neighbours (seon.render/walk) as its
  skeleton, with a slot where each part goes, and :seon.render/units: each entity part a
  component (`component`: read from its db, rendered once into the render proc's cache and again
  only when a transaction touches what it read), each value part its input and schema. The
  render proc (seon.render.proc) sends a patch only when a part's bytes changed. Each part
  renders by its own schema's declared :html render (`view`): an
  agent, a run, an evaluation, a turn, a task, a cost verdict; anything else by the one value
  printer. Layout is Tailwind's classes in the hiccup (resources/public/css/input.css).

  Every number shown is measured by the system and carried in the value; nothing here
  invents one."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.learn :as learn] [seon.docs :as docs] [seon.contract :as contract :refer [defn defn-]] [clojure.string :as str] [clojure.edn :as edn] [seon.config :as config]
            [seon.render :as render] [datahike.api :as d] [seon.context :as context] [seon.program :as program] [seon.print :as print] [seon.print.emit :as emit] [seon.print.select :as select] [seon.web.html :as h] [seon.reader :as reader] [seon.cost :as cost] [seon.task :as task]))

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
  (java.net.URLEncoder/encode ^String (cond (= :root view) "root" (keyword? view) (name view)
                                            ;; [:debug v]: what agent v's model was sent (seon.web/view-of)
                                            (vector? view) (str (java.net.URLDecoder/decode (view-param (second view)) "UTF-8") "/debug")
                                            :else (str view)) "UTF-8"))

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

(defn slot-id
  "The element id slot marker x holds (`slot`), or nil when x is no slot."
  {:malli/schema [:=> [:cat :any] [:maybe :string]]}
  [x] (when (h/raw? x) (second (re-matches #"<!--s:(.*)-->" (:seon.web.html/raw x)))))

(def wrapper-tags
  "The elements that only lay a page out (seon.render.proc/screen-of shows their children in
  their place)."
  #{:div :span :a})

(defn off-screen?
  "Whether an element's attrs mark it left off the screen ({:seon/off-screen true}, seon.render.proc/
  screen-of): chrome (a link or label to another page, the navigation), or what the model already
  reads in its messages (the task's plan, in its task story)."
  {:malli/schema [:=> [:cat [:maybe :map]] :boolean]}
  [attrs] (boolean (:seon/off-screen attrs)))

(def chat-parts
  "The element ids of an agent's page (`chat`) its screen leaves out (seon.render.proc/screen-of):
  its conversation (the model has it as its messages), its older end, and the input."
  #{"c-rows" "c-older" "p-input"})

(defn- kicker {:malli/schema [:=> [:cat :any :any] :any]}
  [k sub] [:header [:span {:class "kicker"} k] (if (vector? sub) sub [:span {:class "sub"} sub])])

(defn context-read?
  "Is eval record e a context read: an outside agent reading its current-state block through
  root's server (by \"context\"; seon.server)? Its output is the block itself."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [e] (and (map? e) (= "context" (:by e))))

;;; --- status -------------------------------------------------------------------

(def status-order "Agent statuses in the swarm's order (:seon.agent/status)." (contract/enum-values :seon.agent/status))
(def live "The live agent statuses (:seon.agent/status)." (:seon.status/live (contract/properties :seon.agent/status)))
(def ^:private gone (:seon.status/gone (contract/properties :seon.agent/status)))
(def ^:private finished task/finished)

(defn shown-status
  "The word for how an agent is: its live status, or how its process ended: :ended when
  root stopped it after its run finished (done or merged), :killed for a kill, :died
  when its process died on its own (an agent entry carries :ended-as from main's run)."
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

(defn run-row
  "A run: its agent, status, turns, tokens and outcome (from main's facts)."
  {:malli/schema [:=> [:cat :map :seon.render/opts] :any]}
  [{:seon.run/keys [id agent status turns tokens outcome reason start-ms end-ms env]} _]
     [:tr {:class (str "at s-" (kname status) (when outcome (str " o-" (kname outcome))))}
    [:td [:code id]]
    [:td (kname agent)
     (when (= "outside" (:model env)) [:span {:class "muted" :title "worked from outside (an outside agent through root's server): no turn loop, its turns and tokens are not counted here"} " · outside"])]
    [:td [:span {:class (str "pill s-" (kname status))} (kname status)]]
    [:td (str (or turns "–"))] [:td (group3 tokens)]
    [:td {:title (str reason)} (kname outcome)]
    [:td {:title "the run's wall time, start to end: its fork, every turn (model, evaluations, test runs in forks) and its end; its agent's page shows where it went"}
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
  (`error-shown`: the AI face's lines and this one's are the same), its cut read through opts
  :handle (a form that answers e) when it has one."
  {:malli/schema [:=> [:cat :seon.error/base :seon.render/opts] :any]}
  [e {:keys [handle]}]
  (let [own (apply dissoc e (error-hidden e))
        budget (config/value :seon.config.render/part-budget)]
         [:div {:class "err"}
      [:b (prose (:seon.error/message e) budget)]
      [:span {:class "muted"} (str " · " (kname (:seon.error/type e)) " in " (:seon.error/operation e))]
      (when (:seon.error/fix e) [:p {:class "fix"} "fix: " (prose (:seon.error/fix e) budget)])
      (for [{:keys [in expected value]} (:seon.error/problems e)]
        [:p {:class "muted"} "at " [:code (print/text in budget)] " expected " [:code (print/text expected budget)] ", got " [:code value]])
      (when (seq own) [:code {:class "muted"} " " (let [p (print/print-value own {:handle handle :budget budget})] (or (:text p) (:seon.error/message p)))])]))

;;; --- events ---------------------------------------------------------------------

(defn event-kind
  "An event's kind as the stream says it: ended for a stop after its run finished."
  {:malli/schema [:=> [:cat :map] :string]}
  [ev] (if (:ended-as ev) "ended" (kname (:type ev))))

(defn event-detail
  "What an event says, in one line: the stream's row and root's context's newest events."
  {:malli/schema [:=> [:cat :map] :string]}
  [ev]
  (case (:type ev)
    :reply (let [r (:response ev)]
             (str (when (:ex r) "error · ")
                  (print/line (:text r) (cell))
                  (when (:ms r) (str " · " (ms-str (:ms r))))))
    :commit (str (kname (:branch ev)) " t " (:t ev) (when (:txs ev) (str " · " (:txs ev) " tx")))
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
    :killed (if (:ended-as ev) (str "stopped by root after its run ended " (kname (:ended-as ev))) "KILLED")
    :ended (str "stopped by root: its run ended" (when (:ended-as ev) (str " " (kname (:ended-as ev)))))
    :dead (if (:ended-as ev) (str "exited after its run ended " (kname (:ended-as ev))) "its process died")
    :ui (str (:call ev) (when (:detail ev) (str " · " (print/line (:detail ev) (cell)))))
    (print/text (dissoc ev :type :at-ms :agent) (cell))))

;;; --- root's layout ------------------------------------------------------------------

(defn ended-as
  "Agent id's entry, or an event of its stream (x), with :ended-as when its process ended
  (its status, or the event's type, is gone) after its run finished (main's run
  done or merged); else x as it is (identical: inputs compare O(1))."
  {:malli/schema [:=> [:cat [:maybe :map] :any :map] [:maybe :map]]}
  [x id agent-run]
  (let [st (:seon.run/status (get agent-run id))]
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
      (when-let [{:keys [reason counterexample run]} (:seon.task/failure t)]
        [:p {:class "err"} "failed" (when run (str " in " run)) ": " reason (when counterexample (list " · counterexample " [:code counterexample]))])
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
  release runs its task's next run when the task is held (seon.root/release!). input {:id
  :live? :held}: live, root's view holds its process in this boot."
  {:malli/schema [:=> [:cat :seon.render/agent-input :seon.render/opts] :any]}
  [{:keys [id live? held]} opts]
  [:section {:id (:unit opts) :class "flex flex-col gap-2"}
   [:a {:class "text-xs text-text-400 underline" :href (str "/agent/" (kname id) "/debug")} "what its model was sent →"]
   (if live?
     [:form {:class "flex gap-2" :seon/call 'seon.root/submit! :seon/agent id}
      [:span {:class "text-signal"} (str (kname id) "=>")]
      ;; Enter sends, Shift+Enter is a new line: a form may span lines
      [:textarea {:name "forms" :class "flex-1 min-w-0" :rows 3 :autocomplete "off" :spellcheck "false" :autofocus true
                  (keyword "data-on:keydown") "if (evt.key==='Enter' && !evt.shiftKey) { evt.preventDefault(); el.form.requestSubmit() }"
                  :placeholder "(+ 1 2) evaluates in its REPL (Enter; Shift+Enter: a new line)\n> a message, read at its next turn"}]
      [:button {:type "button" :class "rounded border border-base-700 px-2" :title "interrupt the running form; the agent keeps its state"
                :seon/call 'seon.root/interrupt! :seon/agent id} "⏹ interrupt"]]
     [:p {:class "text-text-500"} "no process of it in root's view: no REPL to evaluate in, no turn to read a message; what it did is below, read from the store"])
   (when held
     [:button {:class "self-start rounded border border-base-700 px-2" :title (str "release " held ": the reconciler runs its next run (my.root/run!)")
               :seon/call 'seon.root/release! :seon/agent id :seon/args {:task held}}
      (str "▶ release " held)])])

;;; --- the page: an entity and its neighbours (seon.render/walk), laid out --------------

(defn- cost-line
  "What an evaluation cost, as recorded when it ran: ticks, ms and bytes allocated, each with its
  unit; one not recorded is left out, never shown as 0."
  {:malli/schema [:=> [:cat :map] :any]}
  [r]
  (when-let [parts (seq (remove nil? [(when (:ticks r) (str (group3 (:ticks r)) " ticks")) (when (:ms r) (ms-str (:ms r)))
                                       (when (:bytes r) (alloc-span (:bytes r)))]))]
    [:span {:class "tabular-nums text-text-400"} (interpose " · " parts)]))

(def ^:private asked-by
  "Who evaluated, by its :seon.eval/by (any other: the agent's own, its model's turn)."
  {"user" "the owner, from this page" "outside" "an outside agent, through root's server" "context" "the context, read for a turn"})

(defn evaluation
  "One eval (the declared html render of :seon.db/eval-fact, its record, and of a response), as
  it ran: the prose beside it, its code, its response printed under its handle as it is shown
  (seon.print/record-text of its record; a live response's own :text; an error's by its error
  map's render), and its cost and who evaluated it as recorded; :as :inline, its code and
  response in one line. Rendered from the record, which never changes."
  {:malli/schema [:=> [:cat :map :seon.render/opts] :any]}
  [e opts]
  (let [rec (or (:seon.eval/record e) e)
        r (merge (:response rec) (select-keys rec [:code :prose :fns]) (dissoc e :seon.eval/record))
        ;; a recorded value printed as its reply reads (the reply's budget, one budget for an eval's
        ;; value wherever it is shown) under its handle, its cut the printer's, not cut again; text
        ;; with no value behind it (a host value's :printed, a live reply's :text) is prose
        printed (when (and (:seon.eval/record e) (nil? (:text r)) (not (:printed rec))) (print/record-text rec nil))
        r (cond-> r (and (:seon.eval/record e) (nil? (:text r))) (assoc :text (or printed (:printed rec))))
        by (or (:seon.eval/by e) (:by r))
        err? (some? (:ex r))]
    (if (= :inline (:as opts))
      [:span {:class "font-mono"} [:code (prose (or (:code r) "") (cell) {:one-line? true})] (if err? " ✕ " " ⇒ ")
       [:code (prose (or (:text r) "") (cell) {:one-line? true})]]
      [:div {:class (str "flex flex-col gap-1 border-l-2 pl-3 py-1 " (if err? "border-error" "border-base-700"))}
       (when-not (str/blank? (:prose r)) [:p {:class "whitespace-pre-wrap text-text-300 italic"} (prose (:prose r) (form-budget))])
       (when (:code r) [:pre {:class "text-text-100"} [:code (prose (:code r) (form-budget))]])
       (if (map? (:ex r))
         (render/html (:ex r) (cond-> {} (:handle r) (assoc :handle (list :ex (:handle r)))))
         [:pre {:class "text-text-200"} [:span {:class "text-text-500"} "⇒ "] (if printed (:text r) (prose (or (:text r) "") (part)))])
       [:div {:class "flex flex-wrap gap-3 text-2xs text-text-500"}
        (cost-line r)
        [:span (get asked-by by "its own turn")]
        (when (seq (:fns r))
          [:details [:summary (str "costliest: " (:var (first (:fns r))) " · " (count (:fns r)) (if (= 1 (count (:fns r))) " function" " functions"))]
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

(defn run
  "A run (the declared html render of :seon.run/run-fact) as a person reads it: its id, status,
  turn n of its max, its model, and how it ended and why."
  {:malli/schema [:=> [:cat :map :seon.render/opts] :any]}
  [{:seon.run/keys [id status outcome reason model turns max-turns]} opts]
  ;; main records :seon.run/turns at the run's end; a running run says its cap (its newest turn's
  ;; row in the conversation names the turn it is on)
  (let [line (str id " · " (kname status) (cond turns (str " · turn " turns (when max-turns (str " of " max-turns)))
                                                       max-turns (str " · up to " max-turns " turns"))
                  (when model (str " · " model)) (when outcome (str " · " (kname outcome))))]
    (if (= :inline (:as opts))
      [:span (str "run " line)]
      [:section {:class "flex flex-col gap-1 text-xs"}
       [:h2 {:class "text-2xs uppercase tracking-wider text-text-500"} "run"]
       [:p {:class "text-text-100"} line]
       (when-not (str/blank? reason) [:p {:class "whitespace-pre-wrap text-text-400"} (prose reason (part))])])))

(defn task
  "A task (the declared html render of :seon.task/task-fact) as a person reads it: its id, kind
  and execution, then its plan as prose; its proofs are its unit, shown beside it."
  {:malli/schema [:=> [:cat :map :seon.render/opts] :any]}
  [{:seon.task/keys [id kind execution plan]} opts]
  (let [line (str id (when kind (str " · " (kname kind))) (when execution (str " · " (kname execution))))]
    (if (= :inline (:as opts))
      [:span (str "task " line)]
      [:section {:class "flex flex-col gap-1 text-xs"}
       [:h2 {:class "text-2xs uppercase tracking-wider text-text-500"} "task"]
       [:p {:class "text-text-100"} line]
       ;; the model reads the plan in its task story: not on its screen too
       (when-not (str/blank? plan) [:p {:class "whitespace-pre-wrap text-text-300" :seon/off-screen true} (prose plan (part))])])))

(defn did
  "What a run did, from its eval records (evals: eval facts, the screen's left out): the vars
  it read (a form that looks for code, seon.learn/form-reads) with how many times, so a
  repeated read shows as one; the vars it defined (each record's :recorded); the program
  functions it evaluated (its :fns, clojure.* left out); the forms that ran its tests, by their
  handle. Cost: the run's evals, read once each when the agent's page is laid out again, which
  its walk already reads (they are its nodes); never per component render."
  {:malli/schema [:=> [:cat [:sequential :map]] :seon.render/this-run]}
  [evals]
  (let [mine (remove #(= "screen" (:seon.eval/by %)) evals)
        recs (map :seon.eval/record mine)
        as (learn/aliases (map :code recs))
        reads (map #(learn/form-reads (:code %) as) recs)
        lib? #(str/starts-with? (str (namespace %)) "clojure.")]
    {:read (into (sorted-map-by #(compare (str %1) (str %2))) (frequencies (mapcat #(when (:explore? %) (:targets %)) reads)))
     :defined (vec (distinct (mapcat :recorded recs)))
     :evaluated (vec (distinct (remove lib? (keep :var (mapcat :fns recs)))))
     ;; a form that ran its tests names my.agent/prove: compared by its name as text, since the reach
     ;; walk follows a quoted symbol (seon.program/reach-graph) and this render runs no tests (a
     ;; quoted one made every test reach seon.lib/fork! through every schema's render: render-test/
     ;; a-render-reaches-no-process)
     :tests (vec (for [[r rd] (map vector mine reads) :when (some #(= "my.agent/prove" (str %)) (:targets rd))]
                   {:form (:code (:seon.eval/record r)) :handle (:seon.eval/id r)}))}))

(defn this-run
  "What a run did (the declared html render of :seon.render/this-run, `did`): the vars it read,
  a repeat with its count; what it defined and evaluated; the forms that ran its tests."
  {:malli/schema [:=> [:cat :seon.render/this-run :seon.render/opts] :any]}
  [{:keys [read defined evaluated tests]} _]
  [:ul {:class "text-xs"}
   (when (seq read) [:li "read " (str/join ", " (for [[s n] read] (str s (when (< 1 n) (str " ×" n)))))])
   (when (seq defined) [:li "defined " (str/join ", " defined)])
   (when (seq evaluated) [:li "evaluated " (str/join ", " evaluated)])
   (for [{:keys [form handle]} tests] [:li (str "ran " form " (result/" handle ")")])
   (when-not (or (seq read) (seq defined) (seq evaluated) (seq tests)) [:li "nothing read, defined or tested yet"])])

(defn cited
  "What the run's prose cites (the declared html render of :seon.render/cited): each cited thing
  as seon.docs/cited-code gives it (a var's definition head and a namespace's head as code, a
  task or a wiki section as its view), as data in the hiccup, the serializer and the screen's
  printer printing it once (no text printed before them)."
  {:malli/schema [:=> [:cat :seon.render/cited :seon.render/opts] :any]}
  [cs _]
  [:div {:class "flex flex-col gap-1 text-xs"}
   (for [view cs] [:pre {:class "whitespace-pre-wrap break-words text-text-200"} view])])

(defn- toggle
  "A <details>' attrs that tell the render proc it opened or closed (/open), for the conversation's
  part with element id: its view read from the list it sits in (data-view)."
  {:malli/schema [:=> [:cat :string] :map]}
  [id] {(keyword "data-on:toggle") (str "@post('/open?view=' + el.closest('[data-view]').dataset.view + '&unit=" id "&open=' + el.open)")})

(defn problem-about
  "What problem row r is about, as words: its task, run, test and attribute, those it has (the
  one wording seon.render.ai/problem-row and `problem-row` share)."
  {:malli/schema [:=> [:cat :map] [:sequential :string]]}
  [r] (remove nil? [(:task r) (:run r) (some-> (:test r) str) (some-> (:attr r) kname)]))

(defn problem-row
  "One problem (the declared html render of :seon.problems/row, :class assoc'd by its reader) as
  one line: its class and count, what it is about, then why (its message, its error's, its
  reason, else its outcome) and what answered it."
  {:malli/schema [:=> [:cat :map :seon.render/opts] :any]}
  [r _]
  (let [why (or (:message r) (get-in r [:error :seon.error/message]) (:reason r) (some-> (:outcome r) kname))]
    [:p {:class "text-xs"}
     (str (kname (:class r)) (some->> (:count r) (str " ×")) (when-let [w (seq (problem-about r))] (str " " (str/join " " w))))
     (when why (list ": " (prose why (cell) {:one-line? true})))
     (when-let [ts (seq (:answered-by r))] (str " (answered by " (str/join ", " ts) ")"))]))

(defn problems
  "Main's problems (the declared html render of :seon.problems/report): each class with rows,
  its count, and its rows a line each (`problem-row`), open ones first, rendered only when the
  tab opened the class (its id in :open, the /open toggle's; a report shown with no :open shows
  every class's rows)."
  {:malli/schema [:=> [:cat :seon.problems/report :seon.render/opts] :any]}
  [{:keys [classes open]} _]
  (let [cs (for [[c {:keys [n rows]}] (sort-by (comp str key) classes) :when (seq rows)] [c n rows])]
    [:div {:class "flex flex-col gap-1 text-xs"}
     (if (empty? cs)
       [:p "none open"]
       (for [[c n rows] cs :let [id (str "pc-" (name c)) shown? (or (nil? open) (contains? open id))]]
         [:details (cond-> (toggle id) (and open shown?) (assoc :open true))
          [:summary {:class "cursor-pointer"} (str (kname c) " " n)]
          (when shown? (for [r (sort-by #(if (false? (:open? %)) 1 0) rows)] (render/html (assoc r :class c) {:schema :seon.problems/row})))]))]))

(defn runs
  "Main's runs (the declared html render of :seon.render/runs): how many by status, and each
  running one by its own view (`run`, a line)."
  {:malli/schema [:=> [:cat :seon.render/runs :seon.render/opts] :any]}
  [{:keys [by-status running]} _]
  [:div {:class "flex flex-col gap-1 text-xs"}
   [:p (if (empty? by-status) "none yet" (str/join " · " (for [[s n] (sort-by (comp str key) by-status)] (str n " " (kname s)))))]
   (for [r running] [:p (render/html r {:schema :seon.run/run-fact :as :inline})])])

(defn proofs
  "An agent's proofs as its newest turn measured them (the declared html render of
  :seon.render/proofs): how many are green, red, not run or not written, each test's name,
  status and first failing assertion on open."
  {:malli/schema [:=> [:cat :seon.render/proofs :seon.render/opts] :any]}
  [ps _]
  (let [by (group-by (comp :status val) ps)]
    [:details {:class "text-xs"}
     [:summary {:class "cursor-pointer text-text-100"}
      (str (count ps) (if (= 1 (count ps)) " proof: " " proofs: ") (str/join ", " (for [[s xs] (sort-by (comp str key) by)] (str (count xs) " " (kname s)))))]
     (for [[t {:keys [status failure]}] ps]
       [:p (str (kname status) " " t) (when failure (str ": " (:assert failure) " got " (:got failure)))])]))

(defn changes
  "What an agent's branch changes against main (the declared html render of :seon.render/changes):
  how many vars and schemas per kind, and what it is compared against; their names on open."
  {:malli/schema [:=> [:cat :seon.render/changes :seon.render/opts] :any]}
  [{:keys [against] by-kind :changes} _]
  (let [n (reduce + (map count (vals by-kind)))]
    [:details {:class "text-xs"}
     [:summary {:class "cursor-pointer text-text-100"}
      (if (zero? n) "no changes against main" (str n " changes: " (str/join ", " (for [[k xs] by-kind] (str (count xs) " " (name k))))))]
     [:p {:class "text-text-500"} (str "against " against)]
     (for [[k xs] by-kind] [:p [:span {:class "text-text-400"} (str (name k) ": ")] (str/join " " xs)])]))

(defn- shown-as
  "How node nd's schema says it is shown on a page (:seon.render/as on its entity schema), else as a tile."
  {:malli/schema [:=> [:cat :map] :seon.render/as]}
  [nd] (or (:seon.render/as (contract/properties (:seon.render/schema nd))) :tile))

(defn- view
  "Node nd's value by its schema's :html render as as (seon.render/html: none declared, the one
  printer): the one site a page part is drawn at. Its :ai face is the model's, never made here."
  {:malli/schema [:=> [:cat :map :seon.render/as] :any]}
  [nd as] (render/html (:seon.render/value nd) {:as as :schema (:seon.render/schema nd)}))

(defn comp-id
  "The element id of entity eid's component shown as as: the main pane p-head, a row r-<eid>, a
  tile t-<eid>, a debug-page turn d-<eid>, a part of an agent's conversation c-<eid>, an agent
  in the sidebar s-<eid>. The same on every page, so the cache's key names it."
  {:malli/schema [:=> [:cat pos-int? :any] :string]}
  [eid as]
  (if (vector? as)
    (str (case (first as) :debug "d-" :chat "c-") eid)
    (case as :block "p-head" :row (str "r-" eid) :tile (str "t-" eid) :chat (str "c-" eid) :side (str "s-" eid))))

(defn rows
  "A page's list (node nds that show :as :row), in the walk's order (newest first), each row a component: a turn, whose component shows the evals its calls name (:eval)
  under it; an evaluation no turn names (the owner's, root's, one of a turn still running) or a
  message, alone. Each evaluation is in one row only. Answers {:id :eid :db} each."
  {:malli/schema [:=> [:cat [:sequential :map]] [:vector [:map [:id :string] [:eid pos-int?] [:db nat-int?]]]]}
  [nds]
  (let [taken (set (for [t nds :when (= :seon.turn/turn-fact (:seon.render/schema t)) c (get-in t [:seon.render/value :seon.turn/calls]) :when (:eval c)] (:eval c)))]
    (->> nds
         (remove #(taken (get-in % [:seon.render/value :seon.eval/id])))
         (mapv (fn [nd] {:id (comp-id (:seon.render/eid nd) :row) :eid (:seon.render/eid nd) :db (:seon.render/db nd 0)})))))

(declare sent-request)

(defn debug-input
  "Turn eid on db as the debug page shows it, as data (sent-request's input): what it added to the
  request (the refs the turn before it in its run did not send, read back by
  seon.context/parts-of), what it shares with that turn, its whole request when open?, its usage
  or error; view, its page's feed param."
  {:malli/schema [:=> [:cat :seon.host/db pos-int? :boolean :string] :map]}
  [db eid open? view]
  (let [t (d/pull db [:seon.turn/n :seon.turn/sent :seon.turn/basis :seon.turn/tokens :seon.turn/cache-hit-tokens :seon.turn/cache-miss-tokens :seon.turn/error
                      {:seon.turn/run [:seon.run/id]}] eid)
        n (:seon.turn/n t) r (get-in t [:seon.turn/run :seon.run/id])
        refs (:seon.turn/sent t)
        prev (when (pos? n) (:seon.turn/sent (d/pull db [:seon.turn/sent] [:seon.turn/id (str r "/" (dec n))])))
        added (filterv (complement (set prev)) refs)
        ;; its whole request read back from the records its refs name, once: the row is a component,
        ;; drawn when its turn is first shown and cached after (a finished turn never changes)
        whole (context/parts-of db refs)]
    (cond-> (merge (select-keys t [:seon.turn/tokens :seon.turn/cache-hit-tokens :seon.turn/cache-miss-tokens :seon.turn/error])
                   {:turn n :run r :view view :refs (count refs)
                    :added (filterv (comp (set added) first) whole)
                    ;; where its tokens went, sized from those parts
                    :split (context/sent-split whole (set (get-in t [:seon.turn/basis :screen])))})
      prev (assoc :same {:turn (dec n) :parts (- (count refs) (count added))})
      open? (assoc :whole whole))))

(defn- tile-id
  "The element id of tile nd: an entity's, its component's (comp-id); a value's, its entity and
  the attribute it came by, else its schema (a branch's read error, root's open problems)."
  {:malli/schema [:=> [:cat :map] :string]}
  [nd]
  (if (= :value (:seon.render/kind nd))
    (str "t-" (:seon.render/eid nd) "-" (dom-id (or (get-in nd [:seon.render/from :seon.render/attr]) (:seon.render/schema nd) :value)))
    (comp-id (:seon.render/eid nd) :tile)))

(defn- chat-part
  "Entity eid on db as a part of an agent's conversation (element id): a model turn, its header
  and its reasoning folded; an eval, its prose (what the model or the user said it does), then one
  line, its code's first line, its cost and its response's first line, that opens (opts [open?]:
  posting /open with the view its list names in data-view, so the whole of it is rendered only
  while open, and its html is the same on every page) to its form and response;
  a message, who sent it and its body."
  {:malli/schema [:=> [:cat :seon.host/db pos-int? [:maybe [:sequential :any]] :string] :any]}
  [db eid [open?] id]
  (let [own (d/pull db '[*] eid)
        schema (render/entity-schema own :seon.eval/agent)]
    (case schema
      ;; a turn's header, its screen evals folded under it (the system's, not the agent's work):
      ;; rendered only while open, as sent (the debug page shows them in its requests)
      :seon.turn/turn-fact
      (let [screens (get-in own [:seon.turn/basis :screen])]
        [:li {:id id :class "flex flex-col gap-1 py-1"}
         (turn (dissoc own :seon.turn/reply) {})
         (when (seq screens)
           [:details (cond-> (toggle id)
                       open? (assoc :open true))
            [:summary {:class "cursor-pointer text-2xs text-text-500"} "screen"]
            (when open? (for [sid screens :let [e (d/pull db '[*] [:seon.eval/id sid])]]
                          (if (:db/id e) (evaluation e {}) [:p {:class "text-error"} (str "screen eval " sid " is not on this branch")])))])])
      :seon.db/eval-fact
      (let [rec (:seon.eval/record own)
            r (merge (:response rec) (select-keys rec [:code :prose]))
            line #(first (str/split-lines (or % "")))]
        [:li {:id id :class "flex flex-col gap-1 py-1"}
         (when-not (str/blank? (:prose r)) [:p {:class "whitespace-pre-wrap text-text-200"} (prose (:prose r) (part))])
         [:details (cond-> (toggle id)
                     open? (assoc :open true))
          ;; collapsed: code ⇒ its value in one line · ms (an error: its message); open: the whole
          [:summary {:class "flex flex-wrap items-baseline gap-2 cursor-pointer text-xs"}
           [:code {:class "text-text-100"} (line (:code r))]
           [:span {:class (if (:ex r) "text-error" "text-text-500")} (if (:ex r) "✕" "⇒")]
           [:code {:class "text-text-400"} (:line rec)]
           (when (:ms r) [:span {:class "tabular-nums text-text-500"} (ms-str (:ms r))])]
          (when open? (evaluation own {}))]])
      [:li {:id id :class "py-1"} (render/html (dissoc own :db/id) {:schema schema})])))

(defn component
  "Entity eid on db as a page part shows it, as as (:row, :tile, :block; [:debug open? view], a
  turn on the debug page), its element id comp-id: what the render proc caches by what it read
  (d/with-reads) and renders again only when a transaction touches that. Its value by its
  schema's :html render (`view`; its relevant attributes); a turn's row with the evals its calls
  name under it; a tile with a link to its own page; the main pane."
  {:malli/schema [:=> [:cat :seon.host/db pos-int? :any] :any]}
  [db eid as]
  (let [id (comp-id eid as)]
  (cond
    (and (vector? as) (= :debug (first as)))
    (let [[_ open? view] as] (sent-request (debug-input db eid open? view) {:unit id}))
    (or (= :chat as) (and (vector? as) (= :chat (first as))))
    (chat-part db eid (when (vector? as) (rest as)) id)
    (= :side as)
    (let [{:seon.agent/keys [id status]} (d/pull db [:seon.agent/id :seon.agent/status] eid)]
      ;; the user's own entry links to its page: off a screen
      [:li (cond-> {:id (comp-id eid as) :class "flex items-baseline gap-2"} (= :user id) (assoc :seon/off-screen true))
       [:span {:class (if (#{:busy :running} status) "text-signal" "text-text-500")} (if (#{:busy :running} status) "●" "○")]
       [:a {:href (str "/agent/" (kname id)) :class "text-text-200 underline"} (kname id)]
       [:span {:class "text-2xs text-text-500"} (some-> status name)]])
    :else
  (let [own (d/pull db '[*] eid)
        schema (render/entity-schema own nil)
        units (:seon.render/units (contract/properties schema))
        nd {:seon.render/kind :entity :seon.render/eid eid :seon.render/schema schema
            :seon.render/value (let [m (apply dissoc own :db/id units)] (if-let [ks (some-> schema contract/properties :seon.render/keys)] (select-keys m ks) m))}]
    (case as
      :row (let [evals (for [c (:seon.turn/calls own) :when (:eval c) :let [e (d/pull db '[*] [:seon.eval/id (:eval c)])] :when e]
                         {:seon.render/kind :entity :seon.render/eid (:db/id e) :seon.render/schema (render/entity-schema e :seon.eval/agent)
                          :seon.render/value (dissoc e :db/id)})]
             [:li {:id id :class "flex flex-col gap-2 rounded-md bg-base-900 p-3"}
              (view nd :row) (when (seq evals) [:ol {:class "flex flex-col gap-2 pl-2"} (for [e evals] [:li (view e :row)])])])
      :tile [:div {:id id :class "rounded-md bg-base-900 p-2 min-w-0 overflow-x-auto"}
             [:a {:href (str "/walk/" eid) :class "text-2xs text-text-500 float-right" :seon/off-screen true} "its page →"]
             (view nd :tile)]
      [:section {:id id :class "rounded-md bg-base-900 p-3 text-sm text-text-100"} (view nd :block)])))))

(defn tile
  "One tile of a page's column that is a value, not an entity (a task's plan, root's open
  problems, a branch's read error), the unit's element (opts :unit): by its own render."
  {:malli/schema [:=> [:cat :map :seon.render/opts] :any]}
  [nd opts]
  [:section (cond-> {:id (:unit opts) :class "rounded-md bg-base-900 p-2 min-w-0 overflow-x-auto"} (:seon/off-screen nd) (assoc :seon/off-screen true))
   [:h2 {:class "text-2xs uppercase tracking-wider text-text-500"}
    (name (or (:seon.render/schema nd) (get-in nd [:seon.render/from :seon.render/attr]) (render/schema-of (:seon.render/value nd) nil) :value))]
   (view nd :tile)])

(defn more
  "The list's end, the unit's element (opts :unit): how many older rows there are, unrendered; it
  asks for the next ones when it scrolls into view (Datastar's data-on-intersect posting /more).
  Its id carries how many rows are shown, so each next is a new element whose view is watched anew.
  input {:older n :view param :click? b}: click?, the next come when it is clicked (a list whose
  older end is at its top, an agent's conversation: in view as the page opens, it would ask for
  every row)."
  {:malli/schema [:=> [:cat [:map [:older nat-int?] [:view :string] [:click? {:optional true} :boolean]] :seon.render/opts] :any]}
  [{:keys [older view click?]} opts]
  (if (pos? older)
    (if click?
      [:button {:id (:unit opts) :type "button" :class "py-2 text-center text-text-500 underline" (keyword "data-on:click") (str "@post('/more?view=" view "')")}
       (str older (if (= 1 older) " older part" " older parts") ", shown when this is clicked")]
      [:p {:id (:unit opts) :class "py-3 text-center text-text-500" :data-on-intersect (str "@post('/more?view=" view "')")}
       (str older (if (= 1 older) " older row" " older rows") ", rendered as this line scrolls into view")])
    [:p {:id (:unit opts) :class "py-3 text-center text-text-500"} "nothing older"]))

(defn chat
  "An agent's page as its conversation (design section 5): the sidebar, root's agents (sides,
  each a component); the agent's head; its conversation oldest first, its newest opts :shown
  parts rendered and the older counted at the top (they load as that end scrolls into view),
  each a component (a turn, an eval, a message: chat-part); the input under it; its other units
  as tiles beside it. opts {:shown n :view param :input {:schema k :value v} :open #{ids}
  :sides [{:eid :db}]}."
  {:malli/schema [:=> [:cat :seon.render/walk :map] [:map [:seon.render/html :any] [:seon.render/units [:vector :map]]]]}
  [{:seon.render/keys [root nodes]} {:keys [shown view input open sides folded]}]
  ;; the screen's evals are the system's, folded into their turn (chat-part), not parts of their own:
  ;; opts :folded, their entities (by their :seon.eval/by, read from its index: every row alike,
  ;; those the walk listed without reading them too)
  (let [listed (filterv #(and (= :entity (:seon.render/kind %)) (= :row (shown-as %))
                              (not (contains? (or folded #{}) (:seon.render/eid %))))
                        nodes)
        tiles (remove #(and (= :entity (:seon.render/kind %)) (= :row (shown-as %))) nodes)
        on (vec (reverse (take shown listed)))
        older (- (count listed) (count on))
        as (fn [nd] (if (#{:seon.db/eval-fact :seon.turn/turn-fact} (:seon.render/schema nd))
                      (let [id (comp-id (:seon.render/eid nd) :chat)] [:chat (contains? (or open #{}) id)])
                      :chat))
]
    {:seon.render/html
     ;; the view every toggle on the page posts for (`toggle`), a tile's as a part's
     [:div {:id "app" :class "mx-auto flex max-w-screen-2xl flex-col gap-3 p-3 sm:p-4" :data-view view}
      [:nav {:class "flex items-baseline gap-3" :seon/off-screen true}
       [:a {:href "/" :class "font-bold text-signal"} "◆ seon"] [:span {:class "text-text-500"} "on jolt"]]
      [:div {:class "grid gap-4 lg:grid-cols-[12rem_minmax(0,1fr)_20rem]"}
       [:aside {:class "hidden lg:block"} [:ol {:id "c-side" :class "flex flex-col gap-1 text-xs"} (for [a sides] (slot (comp-id (:eid a) :side)))]]
       [:main {:class "flex min-w-0 flex-col gap-3"}
        (slot "p-head")
        [:div {:id "c-older"} (slot (str "c-more-" (count on)))]
        [:ol {:id "c-rows" :class "flex flex-col gap-1" :data-view view} (for [nd on] (slot (comp-id (:seon.render/eid nd) :chat)))]
        (when input (slot "p-input"))]
       [:aside {:id "p-tiles" :class "grid content-start gap-2"} (for [t tiles] (slot (tile-id t)))]]]
     :seon.render/units
     (vec (concat [{:id "p-head" :comp [(:seon.render/db root 0) (:seon.render/eid root) :block]}]
                  (for [a sides] {:id (comp-id (:eid a) :side) :comp [(:db a 0) (:eid a) :side] :container "#c-side"})
                  [{:id (str "c-more-" (count on)) :input {:older older :view view :click? true} :schema :seon.render/page-more :container "#c-older"}]
                  (for [nd on] {:id (comp-id (:seon.render/eid nd) :chat) :comp [(:seon.render/db nd 0) (:seon.render/eid nd) (as nd)] :container "#c-rows"})
                  (when input [{:id "p-input" :input (:value input) :schema (:schema input)}])
                  (for [t tiles] (if (= :entity (:seon.render/kind t))
                                   {:id (tile-id t) :comp [(:seon.render/db t 0) (:seon.render/eid t) :tile] :container "#p-tiles"}
                                   {:id (tile-id t) :input t :schema :seon.render/page-tile :container "#p-tiles"}))))}))

(defn agent
  "An agent's view (the declared html render of :seon.db/agent-fact). Shown as its page (:as :page,
  opts :page {:walk :dbs ...}): its conversation (chat), its sidebar root's agents, read on main;
  at any other size, its head: its id, status, what it runs and on which branch."
  {:malli/schema [:=> [:cat :map :seon.render/opts] :any]}
  [v opts]
  (if-let [{:keys [walk dbs] :as page} (when (= :page (:as opts)) (:page opts))]
    (let [[main branch] dbs
          root (:db/id (d/entity main [:seon.root/id :root]))
          eid (get-in walk [:seon.render/root :seon.render/eid])
          ;; its newest run (the walk's nodes are newest first) and the evals of it the page reads whole
          ;; (the rows it shows: the older are listed without their records, seon.render/walk's :limit)
          run (some #(when (= :seon.run/run-fact (:seon.render/schema %)) (:seon.render/value %)) (:seon.render/nodes walk))
          rid (:seon.run/id run)
          evals (when rid (for [nd (:seon.render/nodes walk) :let [e (:seon.render/value nd)]
                                :when (and (= :seon.db/eval-fact (:seon.render/schema nd)) (= rid (:run (:seon.eval/record e))))]
                            e))
          ;; its proofs as its newest turn measured them (that turn's basis, on its branch): each
          ;; test's status and its first failing assertion; how to run each is the task story's
          task-eid (some #(when (= :seon.task/task-fact (:seon.render/schema %)) (:seon.render/eid %)) (:seon.render/nodes walk))
          proofs (when (and branch rid task-eid)
                   (let [n (d/q '[:find (max ?n) . :in $ ?rid :where [?r :seon.run/id ?rid] [?t :seon.turn/run ?r] [?t :seon.turn/n ?n] [?t :seon.turn/basis _]] branch rid)
                         ;; the newest turn's basis alone (each basis is data: reading every one cost every turn)
                         {:keys [results reds]} (when n (:observed (d/q '[:find ?b . :in $ ?rid ?n :where [?r :seon.run/id ?rid] [?t :seon.turn/run ?r] [?t :seon.turn/n ?n] [?t :seon.turn/basis ?b]] branch rid n)))]
                     (into (sorted-map-by #(compare (str %1) (str %2)))
                           (for [t (:seon.task/proofs (d/pull main [:seon.task/proofs] task-eid))
                                 :let [r (first (filter #(= t (:test %)) reds))]]
                             [t (cond-> {:status (or (get results t) (if (docs/source-of branch t) :not-run :not-written))}
                                  r (assoc :failure {:assert (:expected r) :got (or (:got r) (:actual r))}))]))))
          this-run (when (seq evals) (did evals))
          ;; what the run's prose cites, as each eval's record holds it (parsed once, as it was
          ;; recorded: seon.repl/record-details), newest first, each once, read on its branch
          cites (when branch (into [] (comp (mapcat (comp :cites :seon.eval/record)) (distinct) (keep #(docs/cited-code branch %)))
                                   evals))
          ;; what its branch changes against main now (seon.program/divergence of main's head, not the
          ;; branch's fork point: what main gained since is shown too, as the branch not having it)
          changes (when branch {:against "main now: what main gained since this branch forked shows as missing here"
                                :changes (program/divergence main branch)})
          tile (fn [k x] {:seon.render/kind :value :seon.render/value x :seon.render/schema k :seon.render/eid eid})
          ;; the proofs and the changes are the page's tiles and, for the model, the turn's own forms
          ;; (my.task/status, my.branch/changes: seon.screen/forms), run fresh each turn: off its screen,
          ;; so nothing is shown twice (owner, 2026-10-23)
          off (fn [nd] (assoc nd :seon/off-screen true))
          walk (update walk :seon.render/nodes into (remove nil? [(when (seq proofs) (off (tile :seon.render/proofs proofs)))
                                                                  (when this-run (tile :seon.render/this-run this-run))
                                                                  (when (seq cites) (tile :seon.render/cited cites))
                                                                  (when branch (off (tile :seon.render/changes changes)))]))]
      (chat walk (assoc page
                        ;; its input: its REPL and its messages, live while root's view holds its process in
                        ;; this boot (an agent of another boot has none), its held task's release
                        :input {:schema :seon.render/agent-input
                                :value {:id (:seon.agent/id v)
                                        :live? (boolean (when-let [id (task/this-boots main (d/entity main eid))] (live (shown-status (get-in page [:agents id] {})))))
                                        :held (let [t (some #(when (= :seon.task/task-fact (:seon.render/schema %)) (:seon.render/value %)) (:seon.render/nodes walk))]
                                                (when (= :held (:seon.task/execution t)) (:seon.task/id t)))}}
                        :folded (when branch (into #{} (map :e) (d/datoms branch :avet :seon.eval/by "screen")))
                        :sides (vec (when root (for [nd (:seon.render/nodes (render/walk [main] root))
                                                          :when (= :seon.db/agent-fact (:seon.render/schema nd))]
                                                      {:eid (:seon.render/eid nd) :db 0}))))))
    ;; its head, as a person reads it: its id, status, what it runs and where
    ;; (what it evaluates now, :seon.agent/running, is its conversation's newest part)
    (let [{:seon.agent/keys [id status branch pid description]} v]
      [:div {:class "flex flex-col gap-1"}
       [:h1 {:class "text-base font-bold text-signal"} (kname id)]
       [:p {:class "text-text-300"} (str/join " · " (remove nil? [(kname status) (when branch (str "on branch " (kname branch))) (when pid (str "pid " pid))]))]
       (when-not (str/blank? description) [:p {:class "text-text-300"} (prose description (part))])])))

(defn- runs-of
  "Main's runs as root's page shows them: how many by status (the status index, counted), and
  each running one (its status's index entries, pulled)."
  {:malli/schema [:=> [:cat :seon.host/db] :seon.render/runs]}
  [db]
  {:by-status (frequencies (map :v (d/datoms db :aevt :seon.run/status)))
   :running (vec (for [dt (d/datoms db :avet :seon.run/status :running)]
                   (d/pull db [:seon.run/id :seon.run/status :seon.run/turns :seon.run/max-turns :seon.run/model] (:e dt))))})


(defn root
  "Root's view (the declared html render of :seon.root/root-fact). Shown as its page (:as :page,
  opts :page {:walk :dbs ...}): its conversation (chat: its evals), its agents as the sidebar,
  its walk's other nodes and main's problems and runs as tiles (page opts :recorded, main's problems
  as the render proc holds them), its input (root-input); at any other size, its head: its boot, its generation and,
  when it last went quiet, its root causes a line each."
  {:malli/schema [:=> [:cat :map :seon.render/opts] :any]}
  [v opts]
  (if-let [{:keys [walk dbs recorded open] :as page} (when (= :page (:as opts)) (:page opts))]
    (let [agent? #(= :seon.db/agent-fact (:seon.render/schema %))
          eid (get-in walk [:seon.render/root :seon.render/eid])
          ;; its focus beside its conversation: main's open problems (the classes its tab opened
          ;; render their rows) and main's runs, each by its own render
          tiles [{:seon.render/kind :value :seon.render/value (assoc recorded :open (or open #{})) :seon.render/schema :seon.render/problems :seon.render/eid eid}
                 {:seon.render/kind :value :seon.render/value (runs-of (first dbs)) :seon.render/schema :seon.render/runs :seon.render/eid eid}]]
      (chat (update walk :seon.render/nodes #(into (vec (remove agent? %)) tiles))
            (assoc page :input {:schema :seon.render/root-input :value {}}
                   :sides (vec (for [nd (:seon.render/nodes walk) :when (agent? nd)] {:eid (:seon.render/eid nd) :db 0})))))
    (let [{:seon.root/keys [boot generation quiescent]} v]
      [:div {:class "flex flex-col gap-1"}
       [:h1 {:class "text-base font-bold text-signal"} "root"]
       [:p {:class "text-text-300"} (str/join " · " (remove nil? [(str "boot " boot) (some->> (:commit generation) (str "generation "))]))]
       (when-let [cs (seq (:root-causes quiescent))]
         [:details [:summary {:class "cursor-pointer text-text-300"} (str (count cs) " root causes when it went quiet at t " (:t quiescent))]
          (for [{:keys [task cause run blocks]} cs]
            [:p (str task ": " cause (when run (str " (" run ")")) (when (seq blocks) (str " · blocks " (count blocks))))])])])))

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
  "Page walk w (seon.render/walk) laid out: the entity as the main pane, the input at the top, its
  list (the units whose schema shows them :as :row) newest first, only the newest opts :shown rows
  and the rest counted, and its other units as tiles beside it; responsive by Tailwind (one
  column, the tiles above the list's end on a narrow screen). Each entity part is a component the
  render proc renders once and again only when what it read changes ({:comp [db eid as]}); a value
  part (a task's plan, root's open problems) renders when it changes. opts {:shown n :view param
  :input {:schema k :value v}}, the input for the page's focus (root's, an agent's)."
  {:malli/schema [:=> [:cat :seon.render/walk :map] [:map [:seon.render/html :any] [:seon.render/units [:vector :map]]]]}
  [{:seon.render/keys [root nodes]} {:keys [shown view input]}]
  (let [listed (filterv #(and (= :entity (:seon.render/kind %)) (= :row (shown-as %))) nodes)
        tiles (remove #(and (= :entity (:seon.render/kind %)) (= :row (shown-as %))) nodes)
        rs (rows listed)
        on (take shown rs)
        older (- (count rs) (count on))
]
    {:seon.render/html
     [:div {:id "app" :class "mx-auto flex max-w-screen-2xl flex-col gap-3 p-3 sm:p-4"}
      [:nav {:class "flex items-baseline gap-3"}
       [:a {:href "/" :class "font-bold text-signal"} "◆ seon"] [:span {:class "text-text-500"} "on jolt"]]
      [:div {:class "grid gap-4 lg:grid-cols-[minmax(0,1fr)_22rem]"}
       [:main {:class "flex min-w-0 flex-col gap-3"}
        (slot "p-head") (when input (slot "p-input"))
        [:h2 {:class "text-2xs uppercase tracking-wider text-text-500"} "what it ran, newest first"]
        [:ol {:id "p-rows" :class "flex flex-col gap-2"} (for [r on] (slot (:id r)))]
        [:div {:id "p-foot"} (slot (str "p-more-" (count on)))]]
       [:aside {:id "p-tiles" :class "grid content-start gap-2 sm:grid-cols-2 lg:grid-cols-1"} (for [t tiles] (slot (tile-id t)))]]]
     :seon.render/units
     (vec (concat [{:id "p-head" :comp [(:seon.render/db root 0) (:seon.render/eid root) :block]}]
                  (when input [{:id "p-input" :input (:value input) :schema (:schema input)}])
                  (for [r on] {:id (:id r) :comp [(:db r) (:eid r) :row] :container "#p-rows"})
                  [{:id (str "p-more-" (count on)) :input {:older older :view view} :schema :seon.render/page-more :container "#p-foot"}]
                  (for [t tiles] (if (= :entity (:seon.render/kind t))
                                   {:id (tile-id t) :comp [(:seon.render/db t 0) (:seon.render/eid t) :tile] :container "#p-tiles"}
                                   {:id (tile-id t) :input t :schema :seon.render/page-tile :container "#p-tiles"}))))}))

;;; --- the debug page: what each turn sent, from its records ---------------------------------------

(defn debug
  "Agent id's debug page laid out: each model turn's request as it recorded it (turns: units,
  newest first, each a :seon.render/sent-request input), only the rows its tab scrolled to and
  older ones counted (the list's end, `more`, loads the next as it scrolls into view), a link back
  to its page. The render proc renders a turn's unit once: a turn is written once."
  {:malli/schema [:=> [:cat :any [:vector :map] nat-int?] [:map [:seon.render/html :any] [:seon.render/units [:vector :map]]]]}
  [id turns older]
  (let [view (view-param [:debug id])]
    {:seon.render/html
     [:div {:id "app" :class "mx-auto flex max-w-screen-lg flex-col gap-3 p-3 sm:p-4"}
      [:nav {:class "flex items-baseline gap-3"}
       [:a {:href "/" :class "font-bold text-signal"} "◆ seon"] [:a {:href (str "/agent/" (kname id)) :class "text-text-400 underline"} (str "← " (kname id) "'s page")]]
      [:h1 {:class "text-sm text-text-200"} (str "What " (kname id) "'s model was sent, newest first: each turn what it added to the request; its whole request opens on demand")]
      [:ol {:id "d-turns" :class "flex flex-col gap-3"} (for [u turns] (slot (:id u)))]
      [:div {:id "d-foot"} (slot (str "d-more-" (count turns)))]]
     :seon.render/units (conj (mapv #(assoc % :container "#d-turns") turns)
                              {:id (str "d-more-" (count turns)) :input {:older older :view view} :schema :seon.render/page-more :container "#d-foot"})}))

(defn- source
  "What made part ref of a sent request, as a line: the help page or the task as of a t, an eval,
  a message, the history the budget left out."
  {:malli/schema [:=> [:cat :any] :string]}
  [[kind x t]]
  (case kind
    :section (str "the help page (its section, entity " x ") as of t " t)
    :task (str "the task story: task entity " x "'s run sections as of t " t)
    :eval (str "eval " x)
    :message (str "message " x)
    :history (str x " earlier records the history budget left out")))

(defn- sent-parts-html
  "Parts [[ref messages]] of a sent request, each beside the record that made it, each message
  whole: the system prompt folded, an assistant message's prose, reasoning (folded) and eval
  calls, a tool message under the call it answers."
  {:malli/schema [:=> [:cat [:sequential :any]] :any]}
  [parts]
  (let [text (fn [s] [:pre {:class "whitespace-pre-wrap break-words text-text-100"} s])
        fold (fn [label s] [:details [:summary {:class "text-2xs text-text-500"} (str label ", " (count s) " characters")] (text s)])
        msg (fn [{:keys [role content reasoning tool-calls tool-call-id]}]
              [:div {:class (str "flex flex-col gap-1 rounded border-l-2 pl-2 py-1 "
                                 (case role "assistant" "border-signal" "tool" "border-base-600" "user" "border-text-400" "border-base-700"))}
               [:div {:class "text-2xs uppercase text-text-500"} role (when tool-call-id (str " · answers " tool-call-id))]
               ;; the provider's format sends it back unless :seon.config.ai/replay-reasoning is false
               (when reasoning (fold "reasoning, as returned (sent back unless replay-reasoning is off)" reasoning))
               (when-not (str/blank? content) (if (= "system" role) (fold "the help page" content) (text content)))
               (for [{:keys [id code prose]} tool-calls]
                 [:div {:class "flex flex-col gap-1"}
                  [:div {:class "text-2xs text-text-500"} (str "eval call " id)]
                  (when prose [:p {:class "whitespace-pre-wrap italic text-text-300"} prose])
                  [:pre {:class "whitespace-pre-wrap break-words text-text-100"} [:code code]]])])]
    (for [[ref ms] parts]
      [:div {:class "grid gap-2 sm:grid-cols-[12rem_minmax(0,1fr)]"}
       [:div {:class "text-2xs text-text-500 break-words"} (source ref)]
       (into [:div {:class "flex flex-col gap-1 min-w-0"}] (map msg ms))])))

(defn sent-request
  "A model turn's request as it recorded it (the declared html render of :seon.render/sent-request):
  its turn, tokens and the share its provider served from cache, then what this turn added to the
  request (:added, each part beside the record that made it), a line naming what it sends as the
  turn before it in its run did (:same), and its whole request in a <details> that is rendered
  only while open (:whole: opening it posts /open, and the unit comes back with it), and where its
  tokens went (:split, seon.context/sent-split of its parts: system, story, conversation, screen). input {:turn
  :run :view :refs :added [[ref messages]] :same {:turn :parts} :whole :split} and the turn's usage
  (:seon.turn/tokens, cache-hit and cache-miss tokens) or its call's error (:seon.turn/error)."
  {:malli/schema [:=> [:cat :map :seon.render/opts] :any]}
  [{:keys [turn run view refs added same whole split] :seon.turn/keys [tokens cache-hit-tokens cache-miss-tokens error]} opts]
  (let [prompt (when (and cache-hit-tokens cache-miss-tokens) (+ cache-hit-tokens cache-miss-tokens))
        unit (:unit opts)]
    [:li {:id unit :class "flex flex-col gap-2 rounded-md bg-base-900 p-3 text-xs"}
     [:div {:class "flex flex-wrap items-baseline gap-3"}
      [:b {:class "text-signal"} (str "turn " (inc turn))] [:span {:class "text-text-400"} run]
      (when tokens [:span {:class "tabular-nums text-text-400"} (str (group3 tokens) " tokens")])
      (when (and prompt (pos? prompt)) [:span {:class "tabular-nums text-text-400"} (str (group3 cache-hit-tokens) " of " (group3 prompt) " prompt tokens cached (" (long (* 100 (/ cache-hit-tokens prompt))) "%)")])
      [:span {:class "text-text-500"} (str refs " records sent")]
      (when-not (or tokens error) [:span {:class "text-text-500"} "the call is in flight"])]
     ;; where its tokens went, as the printer sizes what it sent
     (when split
       [:p {:class "tabular-nums text-text-400"}
        (str/join " · " (for [[k w] [[:system "system"] [:story "story"] [:conversation "conversation"] [:screen "screen"]]]
                          (str w " " (group3 (get split k 0)))))])
     ;; a call that failed: its typed error, by its render
     (when error [:div {:class "rounded border border-error p-2"} (render/html error {})])
     [:div {:class "text-2xs uppercase text-text-500"} (if same "what this turn added" "what this turn sent")]
     (sent-parts-html added)
     (when same [:p {:class "text-text-500"} (str "and " (:parts same) " records as turn " (inc (:turn same)) " sent them")])
     [:details (cond-> {(keyword "data-on:toggle") (str "@post('/open?view=" view "&unit=" unit "&open=' + el.open)")}
                 whole (assoc :open true))
      [:summary {:class "text-2xs text-text-500 cursor-pointer"} (str "its whole request, " refs " records")]
      (when whole (into [:div {:class "flex flex-col gap-2 pt-2"}] (sent-parts-html whole)))]]))
