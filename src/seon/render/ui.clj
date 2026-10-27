(ns seon.render.ui
  "The HTML renders of root's web: render functions all the way up (DECISIONS, \"Renders,
  the agent UI and context\"; W1's proto.render.ui). Ported from proto/web's
  proto.web.view (session L2's dashboard and L4's polish).

  Every page is the render of a ctx through the one walk (seon.render/walker): root's
  (`root-ctx`), an agent's (`render-ctx`), each a layout of components ([f opts & children]) over
  its ctx, placed in root's chrome (`page-chrome`) as units. The render proc (seon.render.proc)
  sends a unit only when its revision moved. A value draws by its schema's declared :html render
  (an agent, a run, an evaluation, a turn, a task, a cost verdict), anything else by the one value
  printer. Layout is Tailwind's classes in the hiccup (resources/public/css/input.css).

  Every number shown is measured by the system and carried in the value; nothing here
  invents one."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.learn :as learn] [seon.docs :as docs] [seon.contract :as contract :refer [defn defn-]] [clojure.string :as str] [clojure.edn :as edn] [seon.config :as config]
            [seon.render :as render] [datahike.api :as d] [seon.context :as context] [seon.program :as program] [seon.print :as print] [seon.print.emit :as emit] [seon.print.select :as select] [seon.web.html :as h] [seon.reader :as reader] [seon.cost :as cost] [seon.task :as task] [seon.ctx] [seon.problems :as problems] [seon.edn :as sedn] [datahike.index.interface] [seon.ratom :as ratom] [seon.proofs :as proofs] [seon.merge :as pm]))

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
  a string: every render's word for an id, a status or a kind."
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
  to opts :href (the page that shows it whole); with opts :whole (a form that answers the whole
  value, e.g. its result handle) the cut names that form; with neither, the cut's title holds the
  whole text (a person's way to the rest; with a form, a copy the model would read twice). opts
  :one-line? as the printer's (a table cell, a stream row)."
  {:malli/schema [:function [:=> [:cat :any pos-int?] :any] [:=> [:cat :any pos-int? :map] :any]]}
  ([s budget] (prose s budget {}))
  ([s budget {:keys [href one-line? whole]}]
   (let [s (if (string? s) s (print/text s budget))
         {:keys [shown truncated? omitted lines]} (print/prose s budget {:one-line? (boolean one-line?)})]
     (if-not truncated?
       shown
       (let [words (str (emit/cut-words omitted lines nil) (when whole (str " · the whole value: " (print/text whole (form-budget)) " at its REPL")))
             cut (cond href [:a {:class "seon-print-elision" :href href :title "the whole on this page"} words]
                       whole [:span {:class "seon-print-elision"} words]
                       :else [:span {:class "seon-print-elision" :title s} words])]
         (cond-> [:span shown]
           (not (or (empty? shown) (str/ends-with? shown "\n") (str/ends-with? shown " "))) (conj " ")
           true (conj cut)))))))

(defn code
  "Code on the page under budget tokens: whole, the printer's code block (seon.print.emit/marked-hiccup,
  highlighted as Clojure); cut, the prose cut (`prose`), whose marker names what it left."
  {:malli/schema [:=> [:cat :string pos-int?] :any]}
  [s budget]
  (let [p (prose s budget)]
    ;; a cut holds its marker element, which highlighting (it rewrites the code's text) would remove
    (if (string? p) (emit/marked-hiccup :seon.print/code p) [:pre {:class "text-text-100"} [:code p]])))

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

;;; --- status -------------------------------------------------------------------

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

(defn run-row
  "A run: its agent, status, turns, tokens and outcome (from main's facts)."
  {:malli/schema [:=> [:cat :seon.render/opts :map] :any]}
  [_ {:seon.run/keys [id agent status turns tokens outcome reason start-ms end-ms env]}]
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
  {:malli/schema [:=> [:cat :seon.render/opts :seon.config/config] :any]}
  [_ c]
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
             [:dd (render/html {:budget (part)} v) (when-let [u (prop k :seon.config/unit)] [:span {:class "muted"} " " u])]])])]]))

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
  "The keys an error's render shows in their own lines (`error-box`);
  every other key of the error map is printed after them.
  :jolt.error/suggestions is the fix's own line (\"did you mean ...\"), and :seon.error/in and
  :seon.error/at the \"at line 3:6 of ns/f\" line, so none is printed twice."
  [:seon.error/type :seon.error/operation :seon.error/message :seon.error/fix :seon.error/problems :jolt.error/suggestions
   :seon.error/in :seon.error/at])

(defn error-hidden
  "The keys of error map e its render does not print after the lines: `error-shown`, and the
  request-relative :jolt.error/ and :clojure.error/ line and column only when the form's own
  position (:seon.error/at) is stated, so a position is never dropped without its replacement."
  {:malli/schema [:=> [:cat :map] [:vector :keyword]]}
  [e]
  (cond-> error-shown
    (:seon.error/at e) (into [:jolt.error/line :jolt.error/column :clojure.error/line :clojure.error/column])))

(defn error-box
  "An error map (:seon.error/base, every variant): its message first, then its type and where
  it was raised, the form's own position (:seon.error/at, in :seon.error/in) when it has one, the
  fix when it carries one, and every other key it carries by the printer
  (`error-shown`), its cut read through opts
  :handle (a form that answers e) when it has one."
  {:malli/schema [:=> [:cat :seon.render/opts :seon.error/base] :any]}
  [{:keys [handle]} e]
  (let [own (apply dissoc e (error-hidden e))
        budget (config/value :seon.config.render/part-budget)]
         [:div {:class "err"}
      [:b (prose (:seon.error/message e) budget)]
      [:span {:class "muted"} (str " · " (kname (:seon.error/type e)) " in " (:seon.error/operation e))]
      (when-let [{:keys [line column]} (:seon.error/at e)]
        [:p {:class "muted"} (str "at line " line (when column (str ":" column)) (when-let [in (:seon.error/in e)] (str " of " in)))])
      (when (:seon.error/fix e) [:p {:class "fix"} "fix: " (prose (:seon.error/fix e) budget)])
      (for [{:keys [in expected value]} (:seon.error/problems e)]
        [:p {:class "muted"} "at " [:code (print/text in budget)] " expected " [:code (print/text expected budget)] ", got " [:code (:seon.print/code value)]])
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

(defn- top-bar
  "The bar above every page: Seon's mark, home, then, on root's or an agent's page (focus :root
  or its id), the toggle between its conversation and what its model reads (debug?, the debug
  page)."
  {:malli/schema [:=> [:cat :any :boolean] :any]}
  [focus debug?]
  (let [tab (fn [on? href label] [:a {:href href :class (str "rounded px-2 py-0.5 " (if on? "bg-base-800 text-text-50" "text-text-400 hover:text-text-100"))
                                     :aria-current (when on? "page")} label])]
    [:nav {:class "sticky top-0 z-10 -mx-3 flex items-baseline gap-3 border-b border-base-800 bg-base-950 px-3 py-2 sm:-mx-4 sm:px-4"}
     [:a {:href "/" :class "font-bold text-signal"} "◆ seon"] [:span {:class "hidden text-text-500 sm:inline"} "on jolt"]
     (when focus
       (let [base (if (= :root focus) "/" (str "/agent/" (kname focus)))]
         [:div {:class "ml-auto flex items-baseline gap-1 text-xs"}
          [:span {:class "mr-1 font-semibold text-text-100"} (kname focus)]
          (tab (not debug?) base "conversation")
          (tab debug? (str (if (= :root focus) "/root" base) "/debug") "context")]))]))

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
    ;; highlight.js, then one observer: Clojure code (seon.print.emit/highlight) is highlighted when
    ;; it arrives and again when a patch morphs it back to its plain text (a row re-rendered whole)
    [:script {:src "/highlight.js"}]
    [:script (h/raw (str "addEventListener('DOMContentLoaded', () => { const hl = () => document.querySelectorAll('code.language-clojure:not([data-highlighted])')"
                  ".forEach(e => hljs.highlightElement(e)); hl(); new MutationObserver(hl).observe(document.body, {childList: true, subtree: true}); });"))]
    [:script {:type "module" :src "/datastar.js"}]]
   [:body
    body
    [:div {:style "display:none" :data-init (str "@get('" feed "', {retryMaxCount: Infinity, openWhenHidden: true})")}]]])

;;; --- values' own renders: a task, a verdict, a cost report ---------------------------------

(defn task-box
  "A task: its kind and execution, its plan (accumulated along its needs), instructions, what
  is wrong (its failure, held for root), its needs and proofs, and its evidence."
  {:malli/schema [:=> [:cat :seon.render/opts :seon.task/task] :any]}
  [_ t]
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
        [:div {:class "muted"} "evidence " (render/print-html {:budget (config/value :seon.config.render/part-budget)} ev)])]))

(defn verdict-box
  "A cost verdict (seon.cost/verdict-lines, the agent's words too): red when a size is over
  its estimate or grew steeper."
  {:malli/schema [:=> [:cat :seon.render/opts :seon.cost/verdict] :any]}
  [_ v]
  (let [[[_ head] & more] (cost/verdict-lines v nil)]
    [:div {:class (str "verdict " (if (:ok? v) "ok" "red"))} [:b head] (for [[c w] more] [:span {:class c} (str " " w)])]))

(defn cost-report
  "A cost proof's measurements against its estimate, row by size, then its verdict."
  {:malli/schema [:=> [:cat :seon.render/opts :seon.render/cost-report] :any]}
  [opts {:keys [rows verdict]}]
  (let [dims (filter (set (mapcat (comp keys :expected) rows)) [:ns :bytes :calls])]
         [:div {:class "cost"}
      [:table {:class "costrows"}
       [:thead [:tr [:th "n"] (for [d dims] [:th (str (name d) " measured / estimate")])]]
       [:tbody (for [{:keys [n measured expected]} rows]
                 [:tr [:td (group3 n)]
                  (for [d dims :let [m (get measured d) x (get expected d)]]
                    [:td {:class (when (and m x (pos? x) (> (/ m x) 3)) "bad")}
                     (str (group3 m) " / " (group3 x) (when (and m x (pos? x)) (str " (" (fmt2 (/ m x)) "×)")))])])]]
      (render/html (assoc opts :schema :seon.cost/verdict) verdict)]))

(defn agent-input
  "The input at the top of an agent's page, each through root's requests (the server's ops send
  the same): a line evaluates in its REPL (seon.root/submit!, origin :ui); a line that starts with
  > is a message its next turn reads (seon.root/message!); interrupt stops its running form;
  release runs its task's next run when the task is held (seon.root/release!). input {:id
  :live? :held}: live, root's view holds its process in this boot."
  {:malli/schema [:=> [:cat :seon.render/opts :seon.render/agent-input] :any]}
  [opts {:keys [id live? held]}]
  [:section {:id (:unit opts) :class "flex flex-col gap-2"}
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

;;; --- an eval's parts ---------------------------------------------------------------

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

(defn- shown-value
  "What an eval's recorded response shows as its value: the response less its handle and what
  the row shows beside it (cost, the costliest functions, its error), each form's value when it
  has them (its last is :value)."
  {:malli/schema [:=> [:cat :map] :map]}
  [response]
  (cond-> (dissoc response :handle :ms :ticks :bytes :fns :ex :text :record :recorded)
    (contains? response :values) (dissoc :value)))

(defn- based-value
  "The value eval e's record names by its basis (@ctx: the record of e's agent on branch db as of
  t, seon.ctx/build), or nil: no basis, or no db to read it on."
  {:malli/schema [:=> [:cat :map :map [:maybe :seon.host/db]] [:maybe :map]]}
  [rec e db]
  (when-let [{:keys [t branch]} (when db (:basis rec))]
    (let [a (:db/id (:seon.eval/agent e))]
      ;; a value as of t never changes: its reads are not the component's (no later transaction
      ;; touches them), so it is built when the component renders and never because the branch moved
      (with-meta (assoc (:tree (binding [datahike.index.interface/*reads* nil] (seon.ctx/build (d/as-of db t) a branch))) :t t)
        {:seon.render/schema (:schema rec)}))))

(defn evaluation
  "One eval (the declared html render of :seon.db/eval-fact, its record, and of a response), as
  it ran: the prose beside it, its code, its response printed under its handle as it is shown
  (seon.print/record-text of its record; a live response's own :text; an error's by its error
  map's render; a value recorded as its basis, @ctx's, rendered from the branch at it), and its cost and who evaluated it as recorded; :as :inline, its code and
  response in one line. Rendered from the record, which never changes."
  {:malli/schema [:=> [:cat :seon.render/opts :map] :any]}
  [opts e]
  (let [rec (or (:seon.eval/record e) e)
        r (merge (:response rec) (select-keys rec [:code :prose :fns]) (dissoc e :seon.eval/record))
        ;; a value recorded as data: inline, printed as its reply reads under its handle; in a row,
        ;; rendered as structure (the printer's hiccup). Text with no value behind it (a host
        ;; value's :printed, a live reply's :text) is prose
        data? (and (:seon.eval/record e) (nil? (:text r)) (not (:printed rec)))
        r (cond-> r (and (:seon.eval/record e) (nil? (:text r)))
            (assoc :text (if data? (when (= :inline (:as opts)) (print/record-text rec nil)) (:printed rec))))
        by (or (:seon.eval/by e) (:by r))
        err? (some? (:ex r))]
    (if (= :inline (:as opts))
      ;; its code's first line ⇒ its value's line as recorded (seon.repl/record-details' :line), else its
      ;; text; in the chat (:chat true), the agent's prose first: the chat is prose and a line an arrow opens
      (let [line [:span {:class "font-mono"} [:code (merge-with #(str %1 " " %2) emit/highlight {:class "text-text-100"}) (prose (or (first (str/split-lines (or (:code r) ""))) "") (cell) {:one-line? true})]
                  [:span {:class (if err? "text-error" "text-text-500")} (if err? " ✕ " " ⇒ ")]
                  [:code {:class "text-text-400"} (prose (or (:line rec) (:text r) "") (cell) {:one-line? true})]]]
        (if (and (:chat opts) (not (str/blank? (:prose r))))
          [:span {:class "flex flex-col"} [:span {:class "whitespace-pre-wrap text-text-300 italic"} (prose (:prose r) (form-budget))] line]
          line))
      [:div {:class (str "flex flex-col gap-1 border-l-2 pl-3 py-1 " (if err? "border-error" "border-base-700"))}
       (when-not (str/blank? (:prose r)) [:p {:class "whitespace-pre-wrap text-text-300 italic"} (prose (:prose r) (form-budget))])
       (when (:code r) (code (:code r) (form-budget)))
       (if (map? (:ex r))
         (render/html (cond-> {} (:handle r) (assoc :handle (list :ex (:handle r)))) (:ex r))
         (if data?
           ;; a value stored as data renders as structure, the printer's hiccup under its handle
           (let [v (shown-value (:response rec))]
             [:div {:class "flex flex-col gap-1 text-text-200"}
              [:div {:class "flex gap-2"} [:span {:class "text-text-500"} "⇒"] (render/html {:handle (:handle r)} v)]
              ;; each cut opened: what its form reads, from the record, under that form (seon.web/open-form read it)
              (for [f (:opened opts) :let [form (edn/read-string f)]]
                [:div {:class "flex gap-2"}
                 ;; the form read, as the button that closes it
                 [:button {:type "button" :class "text-text-500 font-mono text-left" :data-seon-form f :title "close"
                           (keyword "data-on:click") (str "@post('/open?view=' + el.closest('[data-view]').dataset.view + '&unit=' + "
                                                          "encodeURIComponent(el.closest('li[id]').id + '/' + el.dataset.seonForm) + '&open=false')")} f]
                 (render/html {:handle form} (print/read-rest form (:handle r) v))])])
           (if-let [v (based-value rec e (:db opts))]
             ;; a value recorded as its basis (@ctx): rendered from what the branch holds at it
             [:div {:class "flex gap-2 text-text-200"} [:span {:class "text-text-500"} "⇒"]
              (render/html {:as :block :schema (:schema rec) :handle (:handle r) :db (:db opts)} v)]
             [:pre {:class "text-text-200"} [:span {:class "text-text-500"} "⇒ "] (prose (or (:text r) "") (part))])))
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
  {:malli/schema [:=> [:cat :seon.render/opts :map] :any]}
  [opts {:seon.turn/keys [n origin reply reasoning tokens cache-hit-tokens cache-miss-tokens finish model errors forms truncated basis]}]
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
       (when-not (str/blank? reply) [:p {:class "seon-prose"} (prose reply (part))])
       (when-not (str/blank? reasoning)
         [:details [:summary {:class "text-2xs text-text-500"} "its reasoning"] [:p {:class "seon-prose text-text-300"} (prose reasoning (part))]])])))

(defn message
  "A message to a run (the declared html render of :seon.message/fact): who sent it and its body as prose."
  {:malli/schema [:=> [:cat :seon.render/opts :map] :any]}
  [opts {:seon.message/keys [from body]}]
  (if (= :inline (:as opts))
    [:span (str from ": ") (prose body (cell) {:one-line? true})]
    [:div {:class "border-l-2 border-info pl-3 py-1"}
     [:span {:class "text-2xs text-text-500"} (str "message from " from)]
     [:p {:class "seon-prose"} (prose body (part))]]))

(defn run
  "A run (the declared html render of :seon.run/run-fact) as a person reads it: its id, status,
  turn n of its max, its model, and how it ended and why."
  {:malli/schema [:=> [:cat :seon.render/opts :map] :any]}
  [opts {:seon.run/keys [id status outcome reason model turns max-turns]}]
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
  {:malli/schema [:=> [:cat :seon.render/opts :map] :any]}
  [opts {:seon.task/keys [id kind execution plan]}]
  (let [line (str id (when kind (str " · " (kname kind))) (when execution (str " · " (kname execution))))]
    (if (= :inline (:as opts))
      [:span (str "task " line)]
      [:section {:class "flex flex-col gap-1 text-xs"}
       [:h2 {:class "text-2xs uppercase tracking-wider text-text-500"} "task"]
       [:p {:class "text-text-100"} line]
       (when-not (str/blank? plan) [:p {:class "whitespace-pre-wrap text-text-300"} (prose plan (part))])])))

(defn- toggle
  "A <details>' attrs that tell the render proc it opened or closed (/open), for the conversation's
  part with element id: its view read from the list it sits in (data-view)."
  {:malli/schema [:=> [:cat :string] :map]}
  [id] {(keyword "data-on:toggle") (str "@post('/open?view=' + el.closest('[data-view]').dataset.view + '&unit=" id "&open=' + el.open)")})

(defn problem-about
  "What problem row r is about, as words: its task, run, test and attribute, those it has (the
  wording `problem-row` uses)."
  {:malli/schema [:=> [:cat :map] [:sequential :string]]}
  [r] (remove nil? [(:task r) (:run r) (some-> (:test r) str) (some-> (:attr r) kname)]))

(defn problem-row
  "One problem (the declared html render of :seon.problems/row, :class assoc'd by its reader) as
  one line: its class and count, what it is about, then why (its message, its error's, its
  reason, else its outcome) and what answered it."
  {:malli/schema [:=> [:cat :seon.render/opts :map] :any]}
  [_ r]
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
  {:malli/schema [:=> [:cat :seon.render/opts :seon.problems/report] :any]}
  [_ {:keys [classes open]}]
  (let [cs (for [[c {:keys [n rows]}] (sort-by (comp str key) classes) :when (seq rows)] [c n rows])]
    [:div {:class "flex flex-col gap-1 text-xs"}
     (if (empty? cs)
       [:p "none open"]
       (for [[c n rows] cs :let [id (str "pc-" (name c)) shown? (or (nil? open) (contains? open id))]]
         [:details (cond-> (toggle id) (and open shown?) (assoc :open true))
          [:summary {:class "cursor-pointer"} (str (kname c) " " n)]
          (when shown? (for [r (sort-by #(if (false? (:open? %)) 1 0) rows)] (render/html {:schema :seon.problems/row} (assoc r :class c))))]))]))

(defn runs
  "Main's runs (the declared html render of :seon.render/runs): how many by status, and each
  running one by its own view (`run`, a line)."
  {:malli/schema [:=> [:cat :seon.render/opts :seon.render/runs] :any]}
  [_ {:keys [by-status running]}]
  [:div {:class "flex flex-col gap-1 text-xs"}
   [:p (if (empty? by-status) "none yet" (str/join " · " (for [[s n] (sort-by (comp str key) by-status)] (str n " " (kname s)))))]
   (for [r running] [:p (render/html {:schema :seon.run/run-fact :as :inline} r)])])

(defn proofs
  "An agent's proofs as its newest turn measured them (the declared html render of
  :seon.render/proofs): how many are green, red, not run or not written, each test's name,
  status and first failing assertion on open."
  {:malli/schema [:=> [:cat :seon.render/opts :seon.render/proofs] :any]}
  [_ ps]
  (let [by (group-by (comp :status val) ps)]
    [:details {:class "text-xs"}
     [:summary {:class "cursor-pointer text-text-100"}
      (str (count ps) (if (= 1 (count ps)) " proof: " " proofs: ") (str/join ", " (for [[s xs] (sort-by (comp str key) by)] (str (count xs) " " (kname s)))))]
     (for [[t {:keys [status failure]}] ps]
       [:p (str (kname status) " " t) (when failure (str ": " (:assert failure) " got " (:got failure)))])]))

(defn ctx
  "The agent's record, @ctx (the declared html render of :seon.ctx/ctx), as one block: its
  environment on a line, then a row per key, each value by the render its key's schema declares
  (render/html with :schema the key), else the one printer's structure. Drawn where its renders
  are: in the agent, under its branch's schemas, so a render the branch defines draws its key
  (seon.repl/render-ctx); anywhere else, by main's. Beside each attribute, who made it as of t
  (seon.ctx/made-by): the eval whose code wrote it, else the branch it came from when not this one,
  and a catch-up's merge from main."
  {:malli/schema [:=> [:cat :seon.render/opts :seon.ctx/ctx] :any]}
  [opts {:keys [t branch commit] :as c}]
  (let [ks (sort-by str (keys (dissoc c :t :branch :commit :db/id)))
        ;; read as of t, as the map is: no later transaction changes them, so they are left out of
        ;; the component's read set (*reads* nil), and a move of the branch never renders it again
        at (when-let [db (when (:db/id c) (:db opts))] (d/as-of db t))
        made (fn [k] (when (and at (qualified-keyword? k))
                       (when-let [{:seon.tx/keys [eval merged] b :seon.tx/branch} (binding [datahike.index.interface/*reads* nil] (seon.ctx/made-by at (:db/id c) k))]
                         (str/join " · " (remove nil? [eval (when (and b (not= b branch)) (str "from " b)) (when merged (str "merged from " merged))])))))
        handle (:handle opts)
        cell (fn [k v] (render/html (cond-> {:schema (when (qualified-keyword? k) k)} handle (assoc :handle (list k handle))) v))]
    (if (= :inline (:as opts))
      [:span {:class "font-mono"} (str "ctx " branch " t " t ", " (count ks) " keys")]
      [:div {:class "flex flex-col gap-1 border border-base-700 rounded p-2 text-xs"}
       [:div {:class "text-text-500"} (str "ctx · " branch " · t " t (when commit (str " · commit " commit)))]
       [:table [:tbody (for [k ks :let [v (get c k)]]
                         [:tr {:class "align-top"}
                          [:td {:class "pr-3 font-mono text-text-400 whitespace-nowrap"} (str k)]
                          [:td (cell k v)]
                          [:td {:class "pl-3 text-2xs text-text-500 whitespace-nowrap"} (made k)]])]]])))

(defn comp-id
  "The element id of entity eid's component shown as as: the main pane p-head, a row r-<eid>, a
  tile t-<eid>, a debug-page turn d-<eid>, a part of an agent's conversation c-<eid>, an agent
  in the sidebar s-<eid>. The same on every page, so the cache's key names it."
  {:malli/schema [:=> [:cat pos-int? :any] :string]}
  [eid as]
  (if (vector? as)
    (str (case (first as) :debug "d-" :chat "c-") eid)
    (case as :block "p-head" :row (str "r-" eid) :tile (str "t-" eid) :chat (str "c-" eid) :side (str "s-" eid))))

(declare sent-request)

(defn debug-input
  "Turn eid on db as the debug page shows it, as data (sent-request's input): what it added to the
  request (the refs the turn before it in its run did not send, read back by
  seon.context/parts-of), what it shares with that turn, its whole request when open?, its usage
  or error; view, its page's feed param."
  {:malli/schema [:=> [:cat :seon.host/db pos-int? :boolean :string] :map]}
  [db eid open? view]
  (let [t (d/pull db [:seon.turn/n :seon.turn/sent :seon.turn/basis :seon.turn/origin :seon.turn/tokens :seon.turn/cache-hit-tokens :seon.turn/cache-miss-tokens :seon.turn/error
                      {:seon.turn/run [:seon.run/id]}] eid)
        n (:seon.turn/n t) r (get-in t [:seon.turn/run :seon.run/id])
        refs (:seon.turn/sent t)
        prev (when (pos? n) (:seon.turn/sent (d/pull db [:seon.turn/sent] [:seon.turn/id (str r "/" (dec n))])))
        added (filterv (complement (set prev)) refs)
        ;; its whole request read back from the records its refs name, once: the row is a component,
        ;; drawn when its turn is first shown and cached after (a finished turn never changes)
        whole (context/parts-of db refs)]
    (cond-> (merge (select-keys t [:seon.turn/origin :seon.turn/tokens :seon.turn/cache-hit-tokens :seon.turn/cache-miss-tokens :seon.turn/error])
                   {:turn n :run r :view view :refs (count refs)
                    :added (filterv (comp (set added) first) whole)
                    ;; where its tokens went, sized from those parts
                    :split (context/sent-split whole)})
      prev (assoc :same {:turn (dec n) :parts (- (count refs) (count added))})
      open? (assoc :whole whole))))

(defn more
  "The list's end, the unit's element (opts :unit): how many older rows there are, unrendered; it
  asks for the next ones when it scrolls into view (Datastar's data-on-intersect posting /more).
  Its id carries how many rows are shown, so each next is a new element whose view is watched anew.
  input {:older n :view param :click? b}: click?, the next come when it is clicked (a list whose
  older end is at its top, an agent's conversation: in view as the page opens, it would ask for
  every row)."
  {:malli/schema [:=> [:cat :seon.render/opts [:map [:older nat-int?] [:view :string] [:click? {:optional true} :boolean]]] :any]}
  [opts {:keys [older view click?]}]
  (if (pos? older)
    (if click?
      [:button {:id (:unit opts) :type "button" :class "py-2 text-center text-text-500 underline" (keyword "data-on:click") (str "@post('/more?view=" view "')")}
       (str older (if (= 1 older) " older part" " older parts") ", shown when this is clicked")]
      [:p {:id (:unit opts) :class "py-3 text-center text-text-500" :data-on-intersect (str "@post('/more?view=" view "')")}
       (str older (if (= 1 older) " older row" " older rows") ", rendered as this line scrolls into view")])
    [:p {:id (:unit opts) :class "py-3 text-center text-text-500"} "nothing older"]))

;;; --- render-ctx: an agent's ctx as components (docs/render.md#components) ----------------------

(defn- relevant
  "Entity m as its schema s shows it: its declared relevant keys (:seon.render/keys), else every
  key, never its :db/id."
  {:malli/schema [:=> [:cat [:maybe :qualified-keyword] :map] :map]}
  [s m]
  ;; through a view, each relevant key read alone (its other keys, and its joins, unread)
  (if-let [ks (some-> s contract/properties :seon.render/keys)]
    (into {} (for [k ks :let [v (get m k)] :when (some? v)] [k (ratom/realize v)]))
    (dissoc (ratom/realize m) :db/id)))

;; render-ctx's components' styles (seon.render/styled): each render's var states its Tailwind classes
;; as literal text (the stylesheet's scan builds them), and its root element carries only its name
(def ^:private panel-style
  "A panel of the task's list: a bordered column under a small heading."
  "flex flex-col gap-2 rounded-md border border-base-800 p-2 [&>h2]:uppercase [&>h2]:tracking-[.14em] [&>h2]:text-[0.625rem] [&>h2]:text-text-400")

(def ^:private tile-style
  "A tile: a value on its own, scrolled when wide."
  "rounded-md bg-base-900 p-2 min-w-0 overflow-x-auto")

(defn- form-src
  "The source of form f as ctx holds it: its :form/src, or, where ctx holds the form whole at another
  path and {:db/id e} at this one (an entity is held once: docs/ctx.md), read on db by its id; nil
  with no form."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :map]] [:maybe :string]]}
  [db f] (or (:form/src f) (some->> (:db/id f) (d/pull db [:form/src]) :form/src)))

(defn- the-run
  "ctx c's newest run, [eid run] (its runs by eid, newest first), or nil."
  {:malli/schema [:=> [:cat :map] [:maybe [:tuple :int :map]]]}
  [c] (when-let [rs (:runs c)] (when-let [[rid run] (first rs)] [rid run])))

(defn- held-task
  "The task run rid of ctx c works, as ctx holds it: every entity is held once (docs/ctx.md), so
  the task a session's runs share is whole under one of them and {:db/id e} under the others."
  {:malli/schema [:=> [:cat :map :int] [:maybe :map]]}
  [c rid]
  (let [t (get-in c [:runs rid :task])]
    ;; a stub is {:db/id e} alone (seon.ctx's)
    (if (not= [:db/id] (keys t))
      t
      (some (fn [[_ r]] (let [x (:task r)] (when (and (= (:db/id t) (:db/id x)) (not= [:db/id] (keys x))) x))) (:runs c)))))

(defn task-data
  "The task its run works (a component over ctx and the db): its id, kind and execution from ctx,
  then the task's data (seon.context/task-data over the db: its instructions and its plan as prose, then what to prove,
  each written proof with its source from ctx, the sections cited with their text and the similar
  as a call of the one printer, [#'seon.render/html opts data],
  expanded when the page is written, so the model reads the data as data). It runs again when
  the task's own attributes in ctx change or a transaction touches what task-data read. Each var
  the plan cites is a component of its own (`cited`)."
  {:seon.render/style (str panel-style " [&_h3]:text-xs [&_h3]:text-text-300") :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db :seon.host/db]]] :int] :any]}
  [{c :seon.ctx/ctx db :seon.db/db} rid]
  (let [{:seon.task/keys [id kind execution] :as t} (held-task c rid)
        ;; each proof written, its source as ctx holds it (the task's :proved): what the test asserts
        sources (into {} (for [v (vals (:proved t)) :let [src (form-src db (:form v))] :when src] [(:var/name v) (print/code src)]))]
    [:section
     [:h2 "task"]
     [:p (str id (when kind (str " · " (kname kind))) (when execution (str " · " (kname execution))))]
     ;; its instructions (how its kind is worked: the template's text, whole under the value budget)
     ;; and its plan as prose here, then each section it cites, its text as prose (ctx's :sections), and
     ;; the rest of its data by the printer (each cited var is its own component); each proof how to
     ;; run it and, written, its source (its last result is the status's)
     (let [data (cond-> (context/task-data db (task/task db id) false)
                  (seq sources) (update :to-prove #(into (empty %) (for [[s run] %] [s (if-let [src (sources s)] {:run run :source src} run)]))))]
       ;; a cut names the form that answers the whole at the agent's REPL
       (remove nil?
         (list (when-let [ins (get-in data [:task :seon.task/instructions])]
               [:p {:class "seon-prose"} (prose ins (config/value :seon.config.render/value-budget) {:whole '(:seon.task/instructions (:task (my.task/data)))})])
             (when-let [plan (get-in data [:task :seon.task/plan])] [:p {:class "seon-prose"} (prose plan (part) {:whole '(:seon.task/plan (:task (my.task/data)))})])
             (seq (for [{:seon.section/keys [address heading text]} (vals (:sections t))]
                    (list [:h3 heading " " [:code address]]
                          [:p {:class "seon-prose"} (prose (str text) (part) {:whole (list :seon.ref/text (list 'my.agent/help address))})])))
             ;; the rest of its data, an empty entry left out (no proof yet is no :to-prove)
             (let [shown (into {} (remove (fn [[_ v]] (and (coll? v) (empty? v)))) (dissoc data :task :sections))]
               (when (seq shown)
                 [#'render/html {:budget (config/value :seon.config.render/value-budget) :handle '(dissoc (my.task/data) :task :write-set :sections)} shown])))))]))

(defn cited-of
  "Var v as the newest run's task holds it in ctx c (its :cited, seon.ctx's pattern: its form and
  its newest callers): its source; its tests, the newest :seon.config.ctx/callers deftests whose
  form names v, read here by one query (ctx's callers window holds callers of any kind, so newer
  callers would push them out), each with its last result on main as db holds it (its
  :seon.proof/latest's status, which only main's runs write, so a branch holds main's as of its
  base; :none when there is none), and the form that reads every test whose reach includes v
  (:reaching, my.agent/tests-reaching); then ctx's other callers, v itself left out. nil when the
  task cites no v. What the cited component draws, and the form its cut names."
  {:malli/schema [:=> [:cat :seon.host/db :map :qualified-symbol] [:maybe :map]]}
  [db c v]
  (when-let [cv (some #(when (= v (:var/name %)) %) (vals (:cited (some->> (the-run c) first (held-task c)))))]
    (let [;; each caller form's var: the var whose :var/form it is (a ref, in :avet)
          vars (for [f (vals (:callers cv)) dt (d/datoms db :avet :var/form (:db/id f))
                     :let [m (d/pull db [:var/name :var/kind] (:e dt))] :when (not= v (:var/name m))] m)
          others (remove #(= :test (:var/kind %)) vars)
          ;; its tests by one query, the join reaching them through :var/kind (176k ticks at 60 newer
          ;; callers, 330k at 600; ctx's window filtered by kind read 3.8M at 600), newest first
          ;; (forms written in one transaction tie on it: their names break the tie, so the cut is fixed)
          tests (->> (d/q '[:find ?n ?tx :in $ ?s :where [?f :form/refs ?s ?tx] [?e :var/form ?f] [?e :var/kind :test] [?e :var/name ?n] [(not= ?n ?s)]] db v)
                     (sort-by (juxt (comp - second) (comp str first))) (take (config/value :seon.config.ctx/callers)) (map first))
          status #(proofs/latest-status db %)
          src (form-src db (:form cv))]
      (cond-> {;; the direct tests only: every test whose reach includes v is the agent's to ask
               :reaching (list 'my.agent/tests-reaching (list 'quote v))}
        src (assoc :source (print/code src))
        (seq tests) (assoc :tests (into (sorted-map-by #(compare (str %1) (str %2))) (for [n tests] [n (status n)])))
        (seq others) (assoc :callers (vec (distinct (map :var/name others))))))))

(defn cited
  "A var the task's plan cites (a component over ctx and the db): its source, then its tests with
  their last results and its other callers (`cited-of`), each a call of the one printer under its
  own budget, so a long source never crowds the tests out (the model reads the data)."
  {:seon.render/style panel-style :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db :seon.host/db]]] :int :symbol] :any]}
  [{c :seon.ctx/ctx db :seon.db/db} _ var]
  (let [whole (list 'seon.render.ui/cited-of '(seon.agent/db) '@seon.ctx/ctx (list 'quote var))
        {:keys [source] :as data} (cited-of db c var)
        others (dissoc data :source)
        budget (config/value :seon.config.render/value-budget)]
    (cond-> [:section [:h2 (str "cites " var)]]
      source (conj [#'render/html {:budget budget :handle (list :source whole)} source])
      (seq others) (conj [#'render/html {:budget budget :handle (list 'dissoc whole :source)} others]))))

(defn status
  "What the newest turn of the run observed, a model's or an outside read's (a component over ctx
  and the db), as (my.task/status) answers it: seon.context/turn-state over the turn's recorded
  basis (seon.context/turn-input, with the model turn before it for its last reply), as a call of
  the one printer (the model reads the data)."
  {:seon.render/style panel-style :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db :seon.host/db]]] :int] :any]}
  [{c :seon.ctx/ctx db :seon.db/db} rid]
  (let [run (get-in c [:runs rid])
        ;; its turns by eid, newest first: the newest of either origin, and the model turn before it
        [turn & older] (vals (:turns run))
        lst (some #(when (= :model (:seon.turn/origin %)) %) older)
        task (:seon.task/id (held-task c rid))]
    (when turn
      (let [b (:seon.turn/basis (d/pull db [:seon.turn/basis] (:db/id turn)))
            l (when lst (d/pull db [:seon.turn/truncated :seon.turn/calls] (:db/id lst)))]
        [:section [:h2 "status"]
         [#'render/html {:budget (config/value :seon.config.render/value-budget) :handle '(my.task/status)}
          (context/turn-state db (task/task db task) (context/turn-input db b l))]]))))

(defn- commit-db
  "The db at commit cid of the store branch db reads (datahike.api/commit-as-db): main's or the
  merge base's value as a render's child names it, read from the branch's own store, so a render
  of them is a function of its args wherever it is drawn (an agent's walk, root's cold draw)."
  {:malli/schema [:=> [:cat :seon.host/db :any] :seon.host/db]}
  [db cid] (d/commit-as-db (:store db) cid))

(defn merge-view
  "Whether the branch can merge into main now (a component over the db; main's and its merge
  base's commits its children, ctx's environment): what main changed since the base and which of
  those the branch changed too (seon.merge/readiness, the three-way a merge of main runs). It runs
  again when main or the base moves, or the branch's db reads change."
  {:seon.render/style panel-style :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.db/db :seon.host/db]]] :any :any] :any]}
  [{db :seon.db/db} main base]
  (let [{:keys [main-moved conflicts]} (pm/readiness (commit-db db base) db (commit-db db main))]
    [:section [:h2 "merge"]
     [#'render/html {:budget (config/value :seon.config.render/value-budget) :handle '(seon.merge/readiness (:base @seon.agent/state) (seon.agent/db) (seon.agent/heard-main))}
      {:main-moved main-moved :conflicts conflicts}]]))

(defn caused-view
  "What the branch's own work made wrong since its merge base (a component over the db; the base's
  commit its child): seon.problems/caused-split's open problems it caused, which the done rule
  refuses, and what it changed under the wiki (:attention)."
  {:seon.render/style panel-style :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.db/db :seon.host/db]]] :any] :any]}
  [{db :seon.db/db} base]
  (let [{:keys [caused attention]} (problems/caused-split (commit-db db base) db)]
    [:section [:h2 "caused"]
     [#'render/html {:budget (config/value :seon.config.render/value-budget) :handle '(seon.problems/caused-split (:base @seon.agent/state) (seon.agent/db))}
      {:caused caused :attention attention}]]))

(defn gaps-view
  "The functions the branch defines without a contract since its merge base (a component over the
  db; the base's commit its child): seon.merge/uncontracted over the branch's own changes, what
  root's judge and the merge gate ask."
  {:seon.render/style panel-style :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.db/db :seon.host/db]]] :any] :any]}
  [{db :seon.db/db} base]
  [:section [:h2 "without a contract"]
   [#'render/html {:budget (config/value :seon.config.render/value-budget)}
    (vec (sort-by str (pm/uncontracted (pm/delta-changes (commit-db db base) db #{}))))]])

(defn divergence-view
  "What the branch's merge carries to main (a component over the db; the base's commit its child):
  seon.merge/divergence, the vars it added, redefined and removed and the schema keys it changed."
  {:seon.render/style panel-style :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.db/db :seon.host/db]]] :any] :any]}
  [{db :seon.db/db} base]
  [:section [:h2 "its merge carries"]
   [#'render/html {:budget (config/value :seon.config.render/value-budget)} (pm/divergence (pm/delta-changes (commit-db db base) db #{}))]])

(defn related
  "What the task's plan is likely to need beyond what it cites (a component over ctx and the db):
  seon.context/related over the db as seon.context/related-view lines it (each section its first
  sentence, each function its doc's, with the forms that read more), what (my.task/related)
  answers, as a call of the one printer; a line saying so when there is nothing yet."
  {:seon.render/style panel-style :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db :seon.host/db]]] :int] :any]}
  [{c :seon.ctx/ctx db :seon.db/db} rid]
  (let [r (some->> (context/related db (get-in c [:runs rid :task :db/id])) (context/related-view db))]
    [:section [:h2 "related"]
     (if r [#'render/html {:budget (config/value :seon.config.render/value-budget) :handle '(my.task/related)} r] [:p {:class "muted"} "nothing related yet"])]))

(defn run-head
  "The run (a component over ctx): its relevant attributes and the turns it took, by its schema's
  render."
  {:seon.render/style tile-style :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]] :int] :any]}
  [{c :seon.ctx/ctx} rid]
  (let [run (get-in c [:runs rid])]
    [:section
     (render/html {:schema :seon.run/run-fact} (assoc (relevant :seon.run/run-fact run) :turns-taken (count (:turns run))))]))

(defn own-problems
  "The open problems of the agent's run (a component over its branch db, seon.problems/own: its run's,
  its task's failure and the errors raised in its task), by the report's render, the one root's tile
  draws; nothing when none is open."
  {:seon.render/style tile-style :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.db/db :seon.host/db]]] :int] :any]}
  [{db :seon.db/db} rid]
  (let [r (problems/own db rid)]
    (when (seq (:classes r)) [:section [:h2 "problems"] (render/html {:schema :seon.problems/report} r)])))

(defn ctx-key
  "A key the agent added to its ctx (a component over ctx), named by that key, which is its schema:
  its value by the key's render."
  {:seon.render/style tile-style :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]] :qualified-keyword] :any]}
  [{c :seon.ctx/ctx} k]
  [:section
   [:h2 {:class "kicker"} (kname k)]
   (render/html {:schema k} (ratom/realize (get c k)))])

(defn- added-keys
  "The keys the agent added to ctx c: any qualified key outside its own :seon.agent/* attributes,
  its pattern and the environment, in order. It reads ctx's own key set only."
  {:malli/schema [:=> [:cat :map] [:sequential :qualified-keyword]]}
  [c] (filter #(and (qualified-keyword? %) (not (#{"seon.agent" "db" "seon.ctx"} (namespace %)))) (sort-by str (keys c))))

(defn ctx-keys
  "The keys the agent added to its ctx (a component over ctx, placed when there is one), each a
  component of its own (ctx-key). It reads ctx's own key set, so it runs again when ctx's own
  attributes change, and its output is the same unless a key came or went."
  {:seon.render/style "flex flex-col gap-2" :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]]] :any]}
  [{c :seon.ctx/ctx}]
  [:div
   (for [k (added-keys c)] [#'ctx-key {} k])])

(defn eval-item
  "An eval of the conversation (a component over ctx), as a reference, never a copy (owner: \"the
  render should be quite different really from the eval data as the focus for the ui is on the
  prose\"): the prose the agent wrote beside it and its handle (result/e12), which names its value
  wherever the transcript and a printed cut name it; the model reads its content once, in its
  transcript. Opened (:seon.eval/opened: a reader opened it, a fact that reaches the branch by
  take!), its value is drawn under it. Root's evals (a ctx with no agent key) post nothing: each
  holds its value, which the browser shows when it is opened. Its <details> carries its handle's
  anchor (eval-<id>): a handle printed anywhere links here, open or not."
  {:seon.render/style "[&>details>summary]:flex [&>details>summary]:min-w-0 [&>details>summary]:items-baseline [&>details>summary]:gap-2" :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]] :int] :any]}
  [{c :seon.ctx/ctx} eid]
  (let [e (ratom/realize (get-in c [:evals eid]))
        rec (:seon.eval/record e)
        opened? (:seon.eval/opened e)
        k (:seon.agent/key c)]
    [:li
     [:details (cond-> {:class "seon-eval" :id (str "eval-" (:seon.eval/id e))} opened? (assoc :open true))
      ;; opening it is a fact on main (seon.root/opened!), which the agent takes before it draws again
      (into (if k [:summary {:seon/call 'seon.root/opened! :seon/agent (:seon.agent/id c) :seon/args {:key k :eval (:seon.eval/id e)}}] [:summary])
            (remove nil?)
            [(when-not (str/blank? (:prose rec)) [:span {:class "seon-prose"} (:prose rec)])
             ;; the mockup's collapsed line (notes/archive/design-agent-view-2026-10-24.md §5): its code's first
             ;; line, its time, its handle; the value only when opened
             ;; the one printer's one-line cut, which names what it left
             [:code {:class "seon-form"} (prose (or (:code rec) "") (cell) {:one-line? true})]
             (when-let [ms (get-in rec [:response :ms])] [:span {:class "seon-ms"} (ms-str ms)])
             [:code {:class "seon-handle"} (str (get-in rec [:response :handle]))]])
      (when (or opened? (nil? k)) (render/html {:schema (render/entity-schema e :seon.eval/agent)} (dissoc e :db/id :seon.eval/agent)))]]))

(defn turn-item
  "A model turn or an outside read of the run (a component over ctx): its header by its schema's
  render, its basis but its turn cap left out."
  {:seon.render/style "py-1" :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]] :int :int] :any]}
  [{c :seon.ctx/ctx} rid eid]
  (let [t (ratom/realize (get-in c [:runs rid :turns eid]))]
    [:li (render/html {:schema :seon.turn/turn-fact}
                                      (-> (relevant :seon.turn/turn-fact t) (dissoc :seon.turn/run) (update :seon.turn/basis select-keys [:max-turns])))]))

(defn message-item
  "A message to the run (a component over ctx), by its schema's render."
  {:seon.render/style "py-1" :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]] :int :int] :any]}
  [{c :seon.ctx/ctx} rid eid]
  [:li (render/html {:schema :seon.message/fact} (dissoc (ratom/realize (get-in c [:runs rid :messages eid])) :db/id :seon.message/to))])

(defn eval-tile
  "An eval whose value has a render of its own (a component over ctx): that value, by it."
  {:seon.render/style tile-style :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]] :int] :any]}
  [{c :seon.ctx/ctx} eid]
  [:section
   (render/html {} (ratom/realize (get-in c [:evals eid :seon.eval/record :response :value])))])

(defn render-ctx
  "The render of an agent's ctx, its layout (the declared html render of :seon.render/render-ctx,
  which an agent overrides on its branch like any render): its ctx, read through the view, as components placed in the
  render's three lists, each keyed by what it shows (an eid, a var, a key). The task's list (p-task):
  the newest run's task, each var its plan cites, what is related, what its newest turn observed,
  the run once it took a turn, then each key the agent added to its ctx. The conversation (c-rows): its evals, the run's
  turns and its messages, in the order they were written (eids are issued in that order). The
  tiles (p-tiles): each eval whose value has a render of its own, newest first. It reads the
  lists' members' ids and its task's cited names, and on the db the transaction of each member's
  identity (when it was written: some ids are content-derived, so an id is no order), which is never
  written again, so it runs again only when a list gains or loses a member, the newest run
  changes, or the plan cites another var."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db :seon.host/db]]]] :any]}
  [{c :seon.ctx/ctx db :seon.db/db}]
  (let [[rid run] (the-run c)
        task (when rid (held-task c rid))
        evals (:evals c)
        ;; in the order written: the transaction of each member's identity datom, then its id
        born (fn [a e] [(:tx (first (d/datoms db :eavt e a))) e])
        conv (map second (sort (concat (map #(born :seon.eval/id %) (keys evals)) (map #(born :seon.turn/id %) (keys (:turns run)))
                                       (map #(born :seon.message/id %) (keys (:messages run))))))
        ev? (set (keys evals))
        tu? (set (keys (:turns run)))]
    [:div {:id "render-ctx"}
     [:div {:id "p-task"}
      (when task (list [#'task-data {} rid]
                       ;; each var the plan cites beside its proofs (the proofs are the task's data)
                       (let [proved (set (map :var/name (vals (:proved task))))]
                         (for [n (distinct (map :var/name (vals (:cited task)))) :when (and n (not (proved n)))] [#'cited {} rid n]))
                       [#'related {} rid]
                       (when (seq (:turns run)) [#'status {} rid])
                       ;; what the turn no longer computes, drawn when what it reads moves: the merge
                       ;; base's and main's commits (ctx's :base-commit :main-commit) are their children. A
                       ;; record that has not heard one yet has no view of it: nothing measured, nothing drawn
                       (when-let [b (:base-commit c)]
                         (list [#'caused-view {} b] [#'gaps-view {} b] [#'divergence-view {} b]
                               ;; a task that merges itself: whether it can merge now
                               (when (and (:main-commit c) (task/merges? (:seon.task/kind task))) [#'merge-view {} (:main-commit c) b])))))
      ;; the run once it took a turn: before that it says only its id, which the task's names
      (when (seq (:turns run)) [#'run-head {} rid])
      (when rid [#'own-problems {} rid])
      (when (seq (added-keys c)) [#'ctx-keys {}])]
     [:ol {:id "c-rows"}
      (for [id conv] (cond (ev? id) [#'eval-item {} id] (tu? id) [#'turn-item {} rid id] :else [#'message-item {} rid id]))]
     [:aside {:id "p-tiles"}
      (for [[id e] evals :let [v (get-in e [:seon.eval/record :response :value])] :when (and (some? v) (render/render-of (ratom/realize v) nil))]
        [#'eval-tile {} id])]]))

(defn agent
  "An agent's view (the declared html render of :seon.db/agent-fact), its head: its id, status,
  what it runs and on which branch. Its page is its parts (seon.render.proc's agent page,
  parts-page)."
  {:malli/schema [:=> [:cat :seon.render/opts :map] :any]}
  [opts v]
  ;; what it evaluates now (:seon.agent/running) is its conversation's newest part
  (let [{:seon.agent/keys [id status branch pid description]} v]
    [:div {:class "flex flex-col gap-1"}
     [:h1 {:class "text-base font-bold text-signal"} (kname id)]
     [:p {:class "text-text-300"} (str/join " · " (remove nil? [(kname status) (when branch (str "on branch " (kname branch))) (when pid (str "pid " pid))]))]
     (when-not (str/blank? description) [:p {:class "text-text-300"} (prose description (part))])]))

(defn- runs-of
  "Main's runs as root's page shows them: how many by status (the status index, counted), and
  each running one (its status's index entries, pulled)."
  {:malli/schema [:=> [:cat :seon.host/db] :seon.render/runs]}
  [db]
  {:by-status (frequencies (map :v (d/datoms db :aevt :seon.run/status)))
   :running (vec (for [dt (d/datoms db :avet :seon.run/status :running)]
                   (d/pull db [:seon.run/id :seon.run/status :seon.run/turns :seon.run/max-turns :seon.run/model] (:e dt))))})


(defn root
  "Root's head (the declared html render of :seon.root/root-fact): its boot, its generation and,
  when it last went quiet, its root causes a line each."
  {:malli/schema [:=> [:cat :seon.render/opts :map] :any]}
  [_ v]
  (let [{:seon.root/keys [boot generation quiescent]} v]
    [:div {:class "flex flex-col gap-1"}
     [:h1 {:class "text-base font-bold text-signal"} "root"]
     [:p {:class "text-text-300"} (str/join " · " (remove nil? [(str "boot " boot) (some->> (:commit generation) (str "generation "))]))]
     (when-let [cs (seq (:root-causes quiescent))]
       [:details [:summary {:class "cursor-pointer text-text-300"} (str (count cs) " root causes when it went quiet at t " (:t quiescent))]
        (for [{:keys [task cause run blocks]} cs]
          [:p (str task ": " cause (when run (str " (" run ")")) (when (seq blocks) (str " · blocks " (count blocks))))])])]))

(defn root-input
  "Root's input at the top of its page: spawn agents (seon.root/spawn!), or evaluate forms in
  every live agent (seon.root/submit! to :all)."
  {:malli/schema [:=> [:cat :seon.render/opts :map] :any]}
  [opts _]
  [:section {:id (:unit opts) :class "flex flex-col gap-2 xl:flex-row"}
   [:form {:class "flex min-w-0 flex-1 gap-2" :seon/call 'seon.root/spawn! :seon/agent :root}
    [:input {:name "task" :class "flex-1 min-w-0" :autocomplete "off" :placeholder "a task for a new agent (Enter spawns it)"}]
    [:button {:type "button" :class "rounded border border-base-700 px-2" :seon/call 'seon.root/spawn! :seon/agent :root :seon/args {:n 10}} "spawn 10"]]
   [:form {:class "flex min-w-0 flex-1 items-baseline gap-2" :seon/call 'seon.root/submit! :seon/agent :all}
    [:span {:class "text-signal"} "all=>"]
    [:input {:name "forms" :class "flex-1 min-w-0" :autocomplete "off" :spellcheck "false" :placeholder "a form every live agent evaluates"}]]])

;;; --- root-ctx: root's ctx as components (docs/render.md#components) ---------------------------

(def ^:private side-style
  "An entry of the agents list: a dot lit while it works, its name linking to its page, its status."
  "flex shrink-0 items-baseline gap-2 rounded-md bg-base-900 px-2 py-1 lg:bg-transparent lg:px-1 hover:bg-base-850")

(defn root-side
  "Root's own entry in its agents list (a component over ctx): lit, as root is the process answering
  the page, linking to root's page."
  {:seon.render/style side-style :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]]] :any]}
  [_]
  [:li [:span {:class "text-signal"} "●"] [:a {:href "/" :class "font-semibold text-text-100"} "root"] [:span {:class "text-2xs text-text-500"} "main"]])

(defn agent-side
  "An agent of root's in its agents list (a component over root's ctx, its :agents member eid): lit
  while busy or running, its id linking to its page, its status."
  {:seon.render/style side-style :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]] :int] :any]}
  [{c :seon.ctx/ctx} eid]
  (let [{:seon.agent/keys [id status]} (relevant :seon.db/agent-fact (get-in c [:agents eid]))
        on? (contains? #{:busy :running} status)]
    [:li [:span {:class (if on? "text-signal" "text-text-500")} (if on? "●" "○")]
     [:a {:href (str "/agent/" (kname id)) :class "font-semibold text-text-100"} (kname id)]
     (when status [:span {:class "text-2xs text-text-500"} (name status)])]))

(defn root-head
  "Root's head (a component over its ctx): its boot, its generation and, when it last went quiet,
  its root causes, by root's own render (`root`)."
  {:seon.render/style "rounded-md bg-base-900 p-3 text-sm text-text-100" :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]]] :any]}
  [{c :seon.ctx/ctx}]
  [:section (render/html {:schema :seon.root/root-fact} (relevant :seon.root/root-fact c))])

(defn problems-tile
  "Main's open problems (a component over root's ctx, its :health: the report the render proc keeps
  with root's process, seon.problems/health changed by each move of main, seon.render.proc/health!):
  every class's rows by the report's render. It runs again only when :health changes."
  {:seon.render/style panel-style :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]]] :any]}
  [{c :seon.ctx/ctx}]
  [:section [:h2 "problems"] (when-let [h (:health c)] (render/html {:schema :seon.problems/report} (ratom/realize h)))])

(defn runs-tile
  "Main's runs (a component over the db): how many by status and each running one (runs-of)."
  {:seon.render/style panel-style :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.db/db :seon.host/db]]]] :any]}
  [{db :seon.db/db}]
  [:section [:h2 "runs"] (render/html {:schema :seon.render/runs} (runs-of db))])

(defn process-tile
  "Root's process (a component over root's ctx, its :process: the render proc's numbers of the
  writer, the fork server and the store, each proc's defects and the newest events; root's own data
  beside its db pull, never written): a line of numbers, then the procs with defects, then the
  stream's newest rows. It runs again only when :process changes."
  {:seon.render/style panel-style :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]]] :any]}
  [{c :seon.ctx/ctx}]
  (let [{:keys [writer fork-server root-errors-total health events]} (ratom/realize (:process c))
        stat (fn [label v] (when (some? v) [:span [:span {:class "text-text-500"} (str label " ")] (str v)]))
        sick (filter (fn [[_ h]] (pos? (:defects h 0))) health)]
    [:section [:h2 "root's process"]
     [:p {:class "flex flex-wrap gap-x-3 text-xs tabular-nums"}
      (stat "batch p50" (some-> (:batch-p50-ms writer) ms-str)) (stat "tx/commit" (:tx-per-commit writer)) (stat "commits" (:commits writer))
      (stat "fork p50" (some-> (:fork-ms-p50 fork-server) ms-str)) (stat "fork lag" (some-> (:lag-ms fork-server) ms-str))
      (stat "defects" root-errors-total)]
     (when (seq sick)
       [:ul {:class "text-xs"} (for [[pid {:keys [defects newest]}] sick]
                                 [:li [:span {:class "text-error"} (str (kname pid) " ✕" defects)] " " [:span {:class "text-text-300"} (some-> newest (print/line (cell)))]])])
     (when (seq events)
       ;; every event the proc keeps, newest first (its window, :seon.config.render.list/stream)
       [:ol {:class "text-xs"} (for [ev events]
                                 [:li {:class "flex gap-2"} [:span {:class "text-eval w-14 shrink-0"} (event-kind ev)]
                                  [:span {:class "text-text-400 shrink-0"} (some-> (:agent ev) kname)]
                                  [:span {:class "min-w-0 break-all text-text-300"} (event-detail ev)]])])]))

(defn root-ctx
  "Root's page, the render of root's ctx (the declared html render of :seon.render/root-ctx, which a
  branch overrides like any render), drawn by the walk as components in its lists: its agents list
  (c-side: root, then each agent its ctx holds, by entity id), its head (p-head), its conversation
  (c-rows: its evals, in the order written) and its tiles (p-tiles: root's process, then main's
  problems and runs). An
  agent's page shows the same agents list (seon.render.proc's agent page). It reads the ids of its
  ctx's agents and evals, so it runs again only when one comes or goes."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]]] :any]}
  [{c :seon.ctx/ctx}]
  [:div {:id "root-ctx"}
   [:ol {:id "c-side"} [#'root-side {}] (for [eid (sort (keys (:agents c)))] [#'agent-side {} eid])]
   [:div {:id "p-head"} [#'root-head {}]]
   [:ol {:id "c-rows"} (for [eid (sort (keys (:evals c)))] [#'eval-item {} eid])]
   [:aside {:id "p-tiles"} [#'process-tile {}] [#'problems-tile {}] [#'runs-tile {}]]])

(defn page-chrome
  "A page in root's chrome (root's, an agent's): the top bar, then the units its walks drew, each
  placed in the list it was drawn in ({:id :container :rev :html}: the agents list, the head, the
  task's list, the conversation, the tiles), and its caption and input. Each unit's input is its
  revision, so the render proc sends one only when its output changed, and a new one into its list
  where its walk placed it (an append). opts {:units :view :input :caption}; id, the page's focus."
  {:malli/schema [:=> [:cat :map :keyword] [:map [:seon.render/html :any] [:seon.render/units [:vector :map]]]]}
  [{:keys [units view input caption]} id]
  (let [in (fn [sel] (filterv #(= sel (:container %)) units))
        unit (fn [u] {:id (:id u) :input {:rev (:rev u)} :html (:html u) :container (:container u)})]
    {:seon.render/html
     [:div {:id "app" :class "mx-auto flex max-w-screen-2xl flex-col gap-3 p-3 sm:p-4" :data-view view}
      (top-bar id false)
      [:div {:class "grid grid-cols-1 gap-4 lg:grid-cols-[12rem_minmax(0,1fr)_20rem]"}
       [:aside {:class "min-w-0 lg:sticky lg:top-14 lg:self-start"}
        [:h2 {:class "kicker mb-1 hidden lg:block"} "agents"]
        [:ol {:id "c-side" :class "flex gap-2 overflow-x-auto pb-1 text-xs lg:flex-col lg:gap-0.5"} (for [u (in "#c-side")] (slot (:id u)))]]
       [:main {:class "flex min-w-0 flex-col gap-3"}
        [:div {:id "p-head"} (for [u (in "#p-head")] (slot (:id u)))]
        (slot "p-caption")
        [:div {:id "p-task" :class "flex flex-col gap-2"} (for [u (in "#p-task")] (slot (:id u)))]
        [:ol {:id "c-rows" :class "flex flex-col gap-1" :data-view view} (for [u (in "#c-rows")] (slot (:id u)))]
        (when input (slot "p-input"))]
       [:aside {:id "p-tiles" :class "grid content-start gap-2 lg:sticky lg:top-14 lg:max-h-[calc(100vh-4rem)] lg:overflow-y-auto"} (for [u (in "#p-tiles")] (slot (:id u)))]]]
     :seon.render/units
     (vec (concat [{:id "p-caption" :input {:caption caption} :html (h/html [:p {:id "p-caption" :class "text-2xs text-text-500"} (or caption "")])}]
                  (when input [{:id "p-input" :input (:value input) :schema (:schema input)}])
                  (map unit units)))}))

;;; --- the debug page: what each turn sent, from its records ---------------------------------------

(defn debug
  "The debug page of id (an agent's id, :root) laid out: what its model reads. Each model turn's
  request as it recorded it (turns: units, newest first, each a :seon.render/sent-request input),
  only the rows its tab scrolled to and older ones counted (the list's end, `more`, loads the next
  as it scrolls into view); for root, which has no model turn, the context it reads now (context:
  a unit, :seon.render/root-context). The render proc renders a turn's unit once: a turn is
  written once."
  {:malli/schema [:=> [:cat :any [:vector :map] nat-int? [:maybe :map]] [:map [:seon.render/html :any] [:seon.render/units [:vector :map]]]]}
  [id turns older context]
  (let [view (view-param [:debug id])]
    {:seon.render/html
     [:div {:id "app" :class "mx-auto flex max-w-screen-lg flex-col gap-3 p-3 sm:p-4"}
      (top-bar id true)
      [:header {:class "flex flex-col gap-1"}
       [:h1 {:class "text-base font-semibold text-text-50"} (if context (str "What " (kname id) " reads") (str "What " (kname id) "'s model was sent"))]
       [:p {:class "text-text-400"} (if context
                                      "Its page as it reads it: the render of its ctx, each component's hiccup by its id, the text /context.txt serves, drawn again when its ctx changes."
                                      "Newest first: each turn, what it added to the request; its whole request opens on demand.")]]
      (when context (slot (:id context)))
      [:ol {:id "d-turns" :class "flex flex-col gap-3"} (for [u turns] (slot (:id u)))]
      [:div {:id "d-foot"} (slot (str "d-more-" (count turns)))]]
     :seon.render/units (cond-> (conj (mapv #(assoc % :container "#d-turns") turns)
                                      {:id (str "d-more-" (count turns)) :input {:older older :view view} :schema :seon.render/page-more :container "#d-foot"})
                          context (conj context))}))

(defn root-context
  "What root reads (the declared html render of :seon.render/root-context): its size, then the
  text whole, as it is sent."
  {:malli/schema [:=> [:cat :seon.render/opts :seon.render/root-context] :any]}
  [opts {:keys [text tokens]}]
  [:section {:id (:unit opts) :class "flex flex-col gap-2 rounded-md bg-base-900 p-3 text-xs"}
   [:div {:class "flex flex-wrap items-baseline gap-3"}
    [:b {:class "text-signal"} "root's context"] [:span {:class "tabular-nums text-text-400"} (str (group3 tokens) " tokens (estimated)")]]
   [:pre {:class "whitespace-pre-wrap break-words text-text-100"} text]])

(defn- source
  "What made part ref of a sent request, as a line: the help page as of a t, the page at a turn
  (whole, or what changed since the turn before), an eval, an eval sent collapsed to its line, a
  message, the history the budget left out: every ref kind
  seon.context/request names (:seon.turn/sent)."
  {:malli/schema [:=> [:cat :any] :string]}
  [[kind x t c]]
  (case kind
    :section (str "the help page (its section, entity " x ") as of t " t)
    :page (if t (str "what changed of the page from commit " t " to " c) (str "the page whole at commit " c))
    :eval (str "eval " x)
    :line (str "eval " x ", sent as its one line")
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
  tokens went (:split, seon.context/sent-split of its parts: system, conversation, page). input {:turn
  :run :view :refs :added [[ref messages]] :same {:turn :parts} :whole :split}, the turn's origin
  (:seon.turn/origin: an :outside read calls no model) and its usage (:seon.turn/tokens, cache-hit and
  cache-miss tokens) or its call's error (:seon.turn/error)."
  {:malli/schema [:=> [:cat :seon.render/opts :map] :any]}
  [opts {:keys [turn run view refs added same whole split] :seon.turn/keys [origin tokens cache-hit-tokens cache-miss-tokens error]}]
  (let [prompt (when (and cache-hit-tokens cache-miss-tokens) (+ cache-hit-tokens cache-miss-tokens))
        unit (:unit opts)]
    [:li {:id unit :class "flex flex-col gap-2 rounded-md bg-base-900 p-3 text-xs"}
     [:div {:class "flex flex-wrap items-baseline gap-3"}
      [:b {:class "text-signal"} (str "turn " (inc turn))] [:span {:class "text-text-400"} run]
      (when tokens [:span {:class "tabular-nums text-text-400"} (str (group3 tokens) " tokens")])
      (when (and prompt (pos? prompt)) [:span {:class "tabular-nums text-text-400"} (str (group3 cache-hit-tokens) " of " (group3 prompt) " prompt tokens cached (" (long (* 100 (/ cache-hit-tokens prompt))) "%)")])
      [:span {:class "text-text-500"} (str refs " records sent")]
      ;; an outside read (seon_context, an outside agent) is sent to no model: it has no call to wait on
      (cond (= :outside origin) [:span {:class "text-text-500"} "an outside read: no model call"]
            (not (or tokens error)) [:span {:class "text-text-500"} "the call is in flight"])]
     ;; where its tokens went, as the printer sizes what it sent
     (when split
       [:p {:class "tabular-nums text-text-400"}
        (str/join " · " (for [[k w] [[:system "system"] [:conversation "conversation"] [:page "page"]]]
                          (str w " " (group3 (get split k 0)))))])
     ;; a call that failed: its typed error, by its render
     (when error [:div {:class "rounded border border-error p-2"} (render/html {} error)])
     [:div {:class "text-2xs uppercase text-text-500"} (if same "what this turn added" "what this turn sent")]
     (sent-parts-html added)
     (when same [:p {:class "text-text-500"} (str "and " (:parts same) " records as turn " (inc (:turn same)) " sent them")])
     [:details (cond-> {(keyword "data-on:toggle") (str "@post('/open?view=" view "&unit=" unit "&open=' + el.open)")}
                 whole (assoc :open true))
      [:summary {:class "text-2xs text-text-500 cursor-pointer"} (str "its whole request, " refs " records")]
      (when whole (into [:div {:class "flex flex-col gap-2 pt-2"}] (sent-parts-html whole)))]]))
