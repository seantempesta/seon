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
            [seon.render :as render] [datahike.api :as d] [seon.context :as context] [seon.program :as program] [seon.print :as print] [seon.print.emit :as emit] [seon.print.select :as select] [seon.web.html :as h] [seon.reader :as reader] [seon.cost :as cost] [seon.task :as task] [seon.ctx] [seon.problems :as problems] [seon.edn :as sedn] [datahike.index.interface] [seon.ratom :as ratom] [seon.proofs :as proofs] [seon.merge :as pm] [seon.ref :as ref]))

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
  "A view as its feed's ?view= and its path name it (seon.web/view-of reads it back). See docs/render.md#the-page."
  {:malli/schema [:=> [:cat :any] :string]}
  [view]
  (let [;; a keyword's name with its namespace (main/task-x), else its text
        of (fn [v] (if (keyword? v) (str (some-> (namespace v) (str "/")) (name v)) (str v)))]
    (java.net.URLEncoder/encode ^String (cond (= :root view) "root"
                                              (= [:debug :root] view) "root/debug"
                                              ;; [:chat v]: agent v's chat, the page a person reads
                                              (and (vector? view) (= :chat (first view))) (of (second view))
                                              ;; [:debug v]: what agent v's model was sent (seon.web/view-of)
                                              (vector? view) (str (of (second view)) "/context")
                                              ;; an agent by its id or its branch: its debug view, every render of its ctx
                                              (keyword? view) (str (of view) "/debug")
                                              :else (str view)) "UTF-8")))

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

(defn task-title
  "A task's id as a person reads it: its name in words, its first letter capital (seon.print/a-long-string
  becomes \"A long string\"); a run's id names its task before its #. See docs/render.md#the-page."
  {:malli/schema [:=> [:cat :string] :string]}
  [id]
  (let [s (str/replace (name (symbol (first (str/split id #"#")))) "-" " ")]
    (str (str/upper-case (subs s 0 (min 1 (count s)))) (subs s (min 1 (count s))))))

(defn markdown
  "Markdown text (a plan, an agent's prose, a message) as a person reads it: its blocks by the one
  parse (seon.ref/scan): a heading at its level, a paragraph, a list of the scan's items (numbered
  when its first marker is a number), a code block highlighted (a fenced block's content as the scan gives it), a quote or a table as its lines. Each block's text
  is kept as written, its backticks too: the model reads this page, and a plan's delimiters pick
  what its citations pull (docs/references.md#the-delimiter-picks-the-depth). See docs/render.md#prose-and-the-one-string-cutter."
  {:malli/schema [:=> [:cat :string] :any]}
  [text]
  (into [:div {:class "seon-md flex flex-col gap-2 leading-relaxed [&_p]:whitespace-pre-wrap [&_li]:whitespace-pre-wrap"}]
        (for [{:seon.ref/keys [kind from to heading heading-level text-from text-to items ordered]} (:seon.ref/blocks (ref/scan text))
              :let [body (subs text from to)]]
          (cond
            (= :heading kind) [(keyword (str "h" (min 6 (+ 2 (or heading-level 1))))) {:class "font-bold text-text-100"} heading]
            ;; a code block: indented, or fenced, whose content the scan gives (its marks are no text of it)
            (= :code kind) (code body (config/value :seon.config.render/value-budget))
            (= :fence kind) (code (subs text text-from text-to) (config/value :seon.config.render/value-budget))
            (= :list kind) (into [(if ordered :ol :ul) {:class (if ordered "list-decimal pl-5" "list-disc pl-5")}]
                                 (for [[a b] items] [:li (subs text a b)]))
            (#{:quote :table} kind) [:pre {:class "whitespace-pre-wrap text-text-300"} body]
            (= :break kind) [:hr]
            :else [:p body]))))

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
  or its id), its tabs, tab the one shown: root's conversation and what its model reads; an
  agent's chat (the page a person reads), its debug view (every render of its ctx) and what its
  model was sent. See docs/render.md#the-page."
  {:malli/schema [:=> [:cat :any [:enum :chat :debug :context]] :any]}
  [focus tab]
  (let [link (fn [on? href label] [:a {:href href :class (str "rounded px-2 py-0.5 " (if on? "bg-base-800 text-text-50" "text-text-400 hover:text-text-100"))
                                      :aria-current (when on? "page")} label])]
    [:nav {:class "sticky top-0 z-10 -mx-3 flex items-baseline gap-3 border-b border-base-800 bg-base-950 px-3 py-2 sm:-mx-4 sm:px-4"}
     [:a {:href "/" :class "font-bold text-signal"} "◆ seon"] [:span {:class "hidden text-text-500 sm:inline"} "on jolt"]
     (when focus
       (let [base (if (= :root focus) "/" (str "/agent/" (kname focus)))]
         [:div {:class "ml-auto flex items-baseline gap-1 text-xs"}
          [:span {:class "mr-1 font-semibold text-text-100"} (kname focus)]
          (if (= :root focus)
            (list (link (= :chat tab) base "conversation") (link (= :context tab) "/root/debug" "context"))
            (list (link (= :chat tab) base "chat") (link (= :debug tab) (str base "/debug") "record") (link (= :context tab) (str base "/context") "sent to model")))]))]))

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
  the row shows beside it (cost, the costliest functions, its error)."
  {:malli/schema [:=> [:cat :map] :map]}
  [response]
  (dissoc response :handle :ms :ticks :bytes :fns :ex :text :record :recorded))

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
            (assoc :text (if data? (when (= :inline (:as opts)) (print/record-text rec)) (:printed rec))))
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
           [:table [:tbody (for [{:keys [var calls ticks self-ticks bytes]} (:fns r)]
                             [:tr [:td [:code (str var)]] [:td {:class "tabular-nums"} (str (group3 calls) " calls")]
                              [:td {:class "tabular-nums"} (str (group3 ticks) " ticks")] [:td {:class "tabular-nums"} (str (group3 self-ticks) " own")]
                              [:td {:class "tabular-nums"} (str (group3 bytes) " B")]])]]])]])))

(defn turn
  "A model turn's header (the declared html render of :seon.turn/turn-fact), above the evaluations
  it ran: which turn of how many, its tokens and the share its provider served from cache, how
  its reply stopped and its model; its reply and reasoning as prose. The turn's model call
  (:seon.model-call/*, merged in with the turn) holds the tokens, model, forms and basis. Nothing
  it did not record is shown."
  {:malli/schema [:=> [:cat :seon.render/opts :map] :any]}
  [opts {:seon.turn/keys [n origin reply reasoning finish truncated] :seon.model-call/keys [tokens cache-hit-tokens cache-miss-tokens model errors forms basis]}]
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
  "Model call eid on db (a turn's :seon.model-call/*) as the debug page shows its turn, as data (sent-request's input): what it added to the
  request (the refs the turn before it in its run did not send, read back by
  seon.context/parts-of), what it shares with that turn, its whole request when open?, its usage
  or error; view, its page's feed param."
  {:malli/schema [:=> [:cat :seon.host/db pos-int? :boolean :string] :map]}
  [db eid open? view]
  (let [m (d/pull db [:seon.model-call/sent :seon.model-call/basis :seon.model-call/tokens :seon.model-call/cache-hit-tokens :seon.model-call/cache-miss-tokens
                      {:seon.model-call/turn [:seon.turn/n :seon.turn/origin :seon.turn/error {:seon.turn/run [:seon.run/id]}]}] eid)
        t (:seon.model-call/turn m)
        n (:seon.turn/n t) r (get-in t [:seon.turn/run :seon.run/id])
        ;; the hashes a request records for the next are no part of it
        refs (remove #(= :hashes (first %)) (:seon.model-call/sent m))
        prev (when (pos? n) (remove #(= :hashes (first %)) (:seon.model-call/sent (d/pull db [:seon.model-call/sent] [:seon.model-call/id (str r "/" (dec n))]))))
        added (filterv (complement (set prev)) refs)
        ;; its whole request read back from the records its refs name, once: the row is a component,
        ;; drawn when its turn is first shown and cached after (a finished turn never changes)
        whole (context/parts-of db refs)]
    (cond-> (merge (select-keys t [:seon.turn/origin :seon.turn/error])
                   (select-keys m [:seon.model-call/tokens :seon.model-call/cache-hit-tokens :seon.model-call/cache-miss-tokens])
                   {:turn n :run r :view view :refs (count refs)
                    :added (filterv (comp (set added) first) whole)
                    ;; where its tokens went, sized from those parts
                    :split (context/sent-split whole)})
      prev (assoc :same {:turn (dec n) :parts (- (count refs) (count added))})
      ;; the whole request, always (owner, 2026-10-29: "I don't want anything hidden"): a finished turn's
      ;; row is drawn once and cached
      true (assoc :whole whole :added-refs (set added)))))

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

(defn task-model
  "The task as the model is sent it (the declared model render of :seon.context/task): its data
  without what only the page draws (the cited sections' whole text, which the model reads at the depth
  its plan's delimiters ask under :sections; the id and kind, which :task holds). See
  docs/render.md#a-part-answers-data."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [_opts v] (dissoc v :id :kind :execution))

(defn task-html
  "The task as the page shows it (the declared html render of :seon.context/task; the model is sent the
  value): its name as a title, its namespace above it, how its kind is worked (folded), its plan as
  Markdown, the rest of its data folded, the sections it cites. See docs/render.md#a-part-answers-data."
  {:malli/schema [:=> [:cat :seon.render/opts :map] :any]}
  [_opts {:keys [id] :as v}]
  (let []
    [:section
     ;; its name as a title (the id's name, words), its namespace and state above it
     [:p {:class "text-2xs uppercase tracking-wider text-text-500"}
      (str "task · " (namespace (symbol id)))]
     [:p {:class "text-lg font-bold text-text-100"} (task-title id)]
     ;; its instructions (how its kind is worked: the template's text) and its plan (what the task is;
     ;; cut at a part's budget an agent read "+86 chars"), each whole under the value budget, as
     ;; prose here, then the rest of its data by the printer (each thing it cites is its own part,
     ;; `cited`); each proof how to
     ;; run it and, written, its source (its last result is the status's)
     ;; a cut names the form that answers the whole at the agent's REPL
    (let [data v]
       (remove nil?
         (list ;; a premise main may already answer comes first, so it is checked in turn 1
               (when-let [g (seq (:green-on-main data))]
                 [:p {:class "seon-prose"} (str "Already green on main: " (str/join ", " g) ". The premise may already hold: reproduce it first, and if the output is right, fail the task with that output now (my.task/fail! \"why\" evidence).")])
             ;; how its kind is worked (read first, so the model reads it before the plan), folded for a person
             (when-let [ins (get-in data [:task :seon.task/instructions])]
               [:details [:summary "how the agent works it"]
                [:p {:class "seon-prose"} (prose ins (config/value :seon.config.render/value-budget) {:whole '(:seon.task/instructions (:task (my.task/data)))})]])
             ;; what the task is: its plan, as the Markdown it is written in
             ;; drawn as Markdown when it fits its budget, else the printer's cut, which names the form for the rest
             (when-let [plan (get-in data [:task :seon.task/plan])]
               (let [p (prose plan (config/value :seon.config.render/value-budget) {:whole '(:seon.task/plan (:task (my.task/data)))})]
                 (if (string? p) (markdown plan) [:p {:class "seon-prose"} p])))
             ;; the rest folds: a person opens what they want, the model reads it all
             (let [shown (into {} (remove (fn [[_ v]] (and (coll? v) (empty? v)))) (dissoc data :task :sections :green-on-main :id :kind :execution))]
               (when (seq shown)
                 [:details [:summary "proofs and data"]
                  [#'render/html {:budget (config/value :seon.config.render/value-budget) :handle '(dissoc (my.task/data) :task :write-set :sections)} shown]])))))]))

(defn task-data
  "The task its agent works (a component over ctx and the db): its id, kind and execution from ctx,
  then the task's data (seon.context/task-data over the db: its instructions and its plan as prose,
  then each proof and the form that proves it, the similar), as a call of the one printer,
  [#'seon.render/html opts data], expanded when the page is written, so the model reads the data as
  data. Each proof's source and verdict are the status's (`status`), and each thing the plan cites
  a part of its own (`cited`): neither is here again (a run was sent each proof's source and
  failures twice, 2026-10-29). It runs again when the task's own attributes in ctx change or a
  transaction touches what task-data read."
  {:seon.render/style (str panel-style " [&_h3]:text-xs [&_h3]:text-text-300") :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db :seon.host/db]]] :int] :any]}
  [{c :seon.ctx/ctx db :seon.db/db} _aid]
  (let [{:seon.task/keys [id kind execution]} (:task c)]
    ;; a part: the page draws it by task-html, the model is sent the value (docs/render.md#a-part-answers-data)
    (with-meta (assoc (dissoc (context/task-data db (task/task db id) false) :sections) :id id :kind kind :execution execution)
      {:seon.render/schema :seon.context/task})))

(defn cited-of
  "Var v as the newest run's task holds it in ctx c (its :cited, seon.ctx's pattern: its form and
  its newest callers): its source; its tests, the newest :seon.config.ctx/callers deftests whose
  form names v, read here by one query (ctx's callers window holds callers of any kind, so newer
  callers would push them out), and the form that reads every test whose reach includes v
  (:reaching, my.agent/tests-reaching); then ctx's other callers, v itself left out. nil when the
  task cites no v. What the cited component draws, and the form its cut names."
  {:malli/schema [:=> [:cat :seon.host/db :map :qualified-symbol] [:maybe :map]]}
  [db c v]
  (when-let [cv (some #(when (= v (:var/name %)) %) (vals (:cited (:task c))))]
    (let [;; each caller form's var: the var whose :var/form it is (a ref, in :avet)
          vars (for [f (vals (:callers cv)) dt (d/datoms db :avet :var/form (:db/id f))
                     :let [m (d/pull db [:var/name :var/kind] (:e dt))] :when (not= v (:var/name m))] m)
          others (remove #(= :test (:var/kind %)) vars)
          ;; its tests by one query, the join reaching them through :var/kind (176k ticks at 60 newer
          ;; callers, 330k at 600; ctx's window filtered by kind read 3.8M at 600), newest first
          ;; (forms written in one transaction tie on it: their names break the tie, so the cut is fixed)
          tests (->> (d/q '[:find ?n ?tx :in $ ?s :where [?f :form/refs ?s ?tx] [?e :var/form ?f] [?e :var/kind :test] [?e :var/name ?n] [(not= ?n ?s)]] db v)
                     (sort-by (juxt (comp - second) (comp str first))) (take (config/value :seon.config.ctx/callers)) (map first))
          src (form-src db (:form cv))]
      (cond-> {;; the direct tests only: every test whose reach includes v is the agent's to ask
               :reaching (list 'my.agent/tests-reaching (list 'quote v))}
        src (assoc :source (print/code src))
        (seq tests) (assoc :tests (vec (sort-by str tests)))
        (seq others) (assoc :callers (vec (distinct (map :var/name others))))))))

(defn cited
  "One thing the task's plan cites (a component over ctx and the db), named by its key
  (seon.context/cite-key): a function, a wiki section, a schema key, a namespace, a file or a task, at
  the depth its citation's delimiter asks (docs/references.md#the-delimiter-picks-the-depth), as the
  reference system shows it (seon.docs/cited); a function at :doc or :code with its direct tests and
  callers (`cited-of`). A part: the page draws it by cited-html, the model is sent the value
  (docs/render.md#a-part-answers-data)."
  {:seon.render/style panel-style :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db :seon.host/db]]] :int :string] :any]}
  [{c :seon.ctx/ctx db :seon.db/db} _ k]
  (when-let [cite (some #(when (= k (context/cite-key %)) %) (some->> (:task c) :seon.task/id (task/task db) (context/citations db)))]
    (when-let [[kind view] (docs/cited db cite)]
      (let [depth (:seon.ref/depth cite)
            fn-extra (when (and (= :var kind) (not= :summary depth)) (dissoc (cited-of db c (:seon.ref/name cite)) :source))]
        (with-meta (merge {:cites k :kind kind :depth depth} view fn-extra) {:seon.render/schema :seon.context/cited})))))

(defn- cited-call
  "The one printer's call for a cited part's value v (shown without the part's schema mark, or html would draw
  it by its render again), its cuts reading the rest through the form that makes the part whole again
  (`cited`, the same ctx and db the page was drawn from). See docs/render.md#a-part-answers-data."
  {:malli/schema [:=> [:cat :map :map] [:vector :any]]}
  [{:keys [cites]} shown]
  [#'render/html {:budget (config/value :seon.config.render/value-budget)
                  :handle (list 'seon.render.ui/cited {:seon.ctx/ctx '(deref seon.ctx/ctx) :seon.db/db '(seon.agent/db)} 0 cites)}
   (with-meta shown nil)])

(defn cited-html
  "A thing the plan cites as the page shows it (the declared html render of :seon.context/cited; the
  model is sent `cited-model`): what it is and the depth it is shown at, then its view by the one
  printer. See docs/render.md#a-part-answers-data."
  {:malli/schema [:=> [:cat :seon.render/opts :map] :any]}
  [_opts {:keys [cites kind depth] :as v}]
  [:details (when (not= :summary depth) {:open true})
   [:summary {:class "kicker"} (str (name kind) " · " cites " · " (name depth))]
   (cited-call v (dissoc v :cites :kind :depth))])

(defn cited-model
  "A thing the plan cites as the model is sent it (the declared model render of :seon.context/cited):
  its value (the name it cites, its kind and depth, then its view) as a call of the one printer,
  whose cuts name the form that reads the rest (`cited-call`). See docs/render.md#a-part-answers-data."
  {:malli/schema [:=> [:cat :map :map] [:vector :any]]}
  [_opts v]
  (cited-call v v))

(def ^:private reason-words
  "What each of the done rule's reasons (seon.reconcile/done-explain) says to a person reading the page (docs/agent.md#what-you-read: `:refused`)."
  {:proof-red "proof red" :proof-unknown "proof has no verdict" :regression "green on main, red here"
   :stray-red "red test that is no proof" :contract-gap "functions without a contract" :unmerged "unmerged entries"
   :open-problem "problems the work caused" :no-proofs "no proof registered" :no-design "plan unchanged since the run began"
   :behind "judged before main moved"})

(defn- verdict-badge
  "A proof's verdict as one mark and its colour: green, red, deferred to root's gate, or none yet."
  {:malli/schema [:=> [:cat :keyword] :any]}
  [st]
  (case st
    :pass [:span {:class "text-success"} "✓"]
    (:fail :error) [:span {:class "text-error"} "✗"]
    :deferred [:span {:class "text-text-500"} "⋯"]
    [:span {:class "text-text-500"} "○"]))

(defn status-html
  "How the work stands as the page shows it (the declared html render of :seon.context/status; the
  model reads the same value by its model render): a line of the proofs green of all and whether
  anything blocks done, then each proof with its verdict and, while red, each failing assertion as
  written beside what it got; what blocks done, each with the form that does something about it;
  whether the branch can merge and what its merge carries; what it left without a contract, what it
  caused and the sections it made stale. Values by the one printer, whole. See
  docs/render.md#how-the-work-stands."
  {:malli/schema [:=> [:cat :seon.render/opts :map] :any]}
  [_opts {:keys [proofs refused test-run unmerged without-a-contract caused attention its-merge-carries merge reasons]}]
  (let [green (count (filter #(= :pass (:status %)) proofs))
        done? (and (empty? reasons) (seq proofs))
        value (fn [v] [#'render/html {:budget (config/value :seon.config.render/value-budget)} v])]
    [:section {:class "flex flex-col gap-2"}
     ;; the one line a person reads first: done, or what is left
     [:p {:class (str "font-[var(--font-prose)] text-sm " (if done? "text-success" "text-text-100"))}
      (cond done? "✓ Done: every check passes"
            test-run "⚠ The tests could not run"
            (seq refused) (str "Not done yet: " (get reason-words (:reason (first refused)) (kname (:reason (first refused))))
                               (when (next refused) (str " and " (dec (count refused)) " more")))
            :else (str green " of " (count proofs) " checks pass"))]
     [:p {:class "text-2xs text-text-500"} (str green "/" (count proofs) " proofs green")]
     [:ul {:class "flex flex-col gap-1"}
      (for [{:keys [test status failures]} proofs]
        [:li {:class "flex flex-col gap-0.5"}
         [:div {:class "flex gap-2"} (verdict-badge status) [:code {:class "text-text-100"} (str test)]
          (when-not (#{:pass :fail :error} status) [:span {:class "text-text-500"} (kname status)])]
         ;; several assertions that got the same value are one entry (seon.context/failures)
         (for [{a :assert as :asserts g :got m :message} failures]
           [:div {:class "ml-5 grid grid-cols-[3.5rem_minmax(0,1fr)] gap-x-2 text-xs"}
            (when m [:span {:class "col-span-2 text-text-300"} m])
            (for [a (or as [a])] (list [:span {:class "text-text-500"} "assert"] (value a)))
            [:span {:class "text-text-500"} (if as (str "each got") "got")] (value g)])])]
     (when test-run [:p {:class "text-error"} (str "the test run failed: " (str/join "; " (:failed test-run)))])
     (when (seq refused)
       [:div {:class "flex flex-col gap-1"}
        [:h3 {:class "kicker"} "blocks done"]
        (for [{:keys [reason do] :as r} refused]
          [:div {:class "flex flex-col gap-0.5"}
           [:span {:class "text-text-300"} (get reason-words reason (kname reason))
            (when-let [x (or (:test r) (:vars r) (:keys r) (:problems r))] [:span " " (value x)])]
           (when do [:div {:class "ml-3"} (value do)])])])
     (when merge
       [:p {:class "text-text-300"} "merge: "
        (if (seq (:conflicts merge)) [:span {:class "text-error"} (str (count (:conflicts merge)) " conflicts")] "no conflicts")
        (when (seq (:main-moved merge)) (str " · main moved " (count (:main-moved merge))))])
     (when (seq unmerged) [:div [:h3 {:class "kicker"} "unmerged"] (value unmerged)])
     (when (seq its-merge-carries)
       [:details [:summary {:class "text-text-300"} (str "its merge carries " (str/join ", " (for [[k vs] its-merge-carries] (str (count vs) " " (name k)))))]
        (value its-merge-carries)])
     (when (seq without-a-contract) [:div [:h3 {:class "kicker"} "without a contract"] (value without-a-contract)])
     (when (seq caused) [:div [:h3 {:class "kicker text-error"} "caused"] (value caused)])
     (when (seq attention) [:details [:summary {:class "text-text-300"} (str (count attention) " sections made stale")] (value attention)])]))

(defn status
  "How the agent's work on its task stands (a component over ctx and the db): seon.context/status
  of its branch db, a part (its data, :seon.context/status, drawn by its schema's html render for
  the page and its model render for the model: docs/render.md#a-part-answers-data). It reads main's
  commit from ctx only when the task merges itself (the merge view), so a move of main redraws it
  only then."
  {:seon.render/style panel-style :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db :seon.host/db]]] :int] :any]}
  [{c :seon.ctx/ctx db :seon.db/db} aid]
  (let [main (when (task/merges? (:seon.task/kind (:task c))) (ratom/realize (:seon.agent/main-commit c)))]
    (with-meta (context/status db aid {:main main}) {:seon.render/schema :seon.context/status})))

(defn related-html
  "What is related as the page shows it (the declared html render of :seon.context/related; the model
  reads the same value, the forms that read more among it, by its model render, the value itself):
  folded to how many of each kind, opened to one line each, as related-view lines them. See
  docs/render.md#a-part-answers-data."
  {:malli/schema [:=> [:cat :seon.render/opts :map] :any]}
  [_opts v]
  (let [kinds (filter (comp sequential? val) (dissoc v :read :more))]
    [:details
     [:summary {:class "kicker"} (str "related · " (str/join ", " (for [[k xs] kinds] (str (count xs) " " (name k)))))]
     [:ul {:class "mt-1 flex flex-col gap-1"}
      (for [[_ xs] kinds x xs]
        [:li {:class "text-text-300"} (if (string? x) x [#'render/html {:budget (config/value :seon.config.render/value-budget)} x])])]]))

(defn related
  "What the agent's work on its task is likely to need beyond what the plan cites (a component over
  ctx and the db): the units its last turn included (:seon.agent/included, seon.context/selected:
  what its plan and recent evals point to, each whole with its chance, in the order they entered), as
  seon.context/related-view lines them (with the forms that read more), a part (its data,
  :seon.context/related: docs/render.md#a-part-answers-data); a line saying so when there is nothing
  yet. No relevance call here: the turn decides, the page draws."
  {:seon.render/style panel-style :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db :seon.host/db]]] :int] :any]}
  [{c :seon.ctx/ctx db :seon.db/db} _aid]
  (if-let [r (some->> (not-empty (:seon.agent/included c)) context/shown (context/related-view db))]
    (with-meta r {:seon.render/schema :seon.context/related})
    [:section [:h2 "related"] [:p {:class "muted"} "nothing related yet"]]))

(defn namespaces-html
  "The namespaces the work touches as the page shows them (the declared html render of
  :seon.context/namespaces; the model is sent the value): folded to their names, opened to each one's
  aliases, definitions and tests by the one printer. See docs/render.md#a-part-answers-data."
  {:malli/schema [:=> [:cat :seon.render/opts [:map-of :symbol :map]] :any]}
  [_opts v]
  [:details
   [:summary {:class "kicker"} (str "namespaces · " (str/join ", " (keys v)))]
   (into [:div {:class "mt-1 flex flex-col gap-1"}]
         (for [[n d] v] [#'render/html {:budget (config/value :seon.config.render/value-budget)} {n d}]))])

(defn namespaces
  "The namespaces the agent's work touches (a component over ctx and the db): the one its REPL is in,
  those of what the plan cites and of its proofs, each its aliases, definitions and deftests
  (seon.context/namespaces-of), a part (its data, :seon.context/namespaces:
  docs/render.md#a-part-answers-data). Nothing before the task's record is read."
  {:seon.render/style panel-style :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db :seon.host/db]]] :int] :any]}
  [{c :seon.ctx/ctx db :seon.db/db} aid]
  (when-let [t (some->> (:task c) :seon.task/id (task/task db))]
    (with-meta (context/namespaces-of db t aid) {:seon.render/schema :seon.context/namespaces})))

(defn run-head
  "The agent's run (a component over ctx): its relevant attributes and the turns it took, by the
  run schema's render, from the agent's record (its status, branch and turn cap, its turns; the cap
  the run's when the agent raised none, from the db), and
  that its last reply was cut, when it was: the conversation's part of where the agent stands (the
  work's is seon.render.ui/status)."
  {:seon.render/style tile-style :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db :seon.host/db]]] :int] :any]}
  [{c :seon.ctx/ctx db :seon.db/db} aid]
  (let [;; its cap: the agent's own (raised to extend it), else its run's, as the turn loop reads it; the run by
        ;; the agent, a fact written when a run begins
        run (some->> (d/q '[:find [?r ...] :in $ ?a :where [?r :seon.run/agent ?a]] db aid) seq (apply max) (d/entity db))
        cap (or (ratom/realize (:seon.agent/max-turns c)) (:seon.run/max-turns run))
        n (count (:turns c))
        ;; the tokens its requests sent so far, the provider's counts summed (each request the whole context then)
        sent (d/q '[:find (sum ?k) . :with ?m :in $ ?a :where [?t :seon.turn/agent ?a] [?m :seon.model-call/turn ?t] [?m :seon.model-call/tokens ?k]] db aid)
        ;; the namespace the REPL is in: the one its newest eval left it in
        in-ns (context/current-ns db aid)]
    ;; a part: the page draws it by run-html, the model is sent the value (docs/render.md#a-part-answers-data); this
    ;; turn's number (its record is written before its page is drawn), of the cap, and a cut reply
    (with-meta (cond-> {:turn (if cap (str n " of " cap) n)}
                 in-ns (assoc :ns in-ns)
                 sent (assoc :tokens-sent sent)
                 (some-> (:turns c) vals first ratom/realize :seon.turn/truncated)
                 (assoc :your-last-reply "cut at the model's token limit: what followed the cut was lost. Answer with less: one change at a time."))
      {:seon.render/schema :seon.context/run})))

(defn run-html
  "The run as the page shows it (the declared html render of :seon.context/run; the model is sent the
  value): its turn of its cap, the namespace its REPL is in, the tokens it sent in all, a cut reply. See docs/render.md#a-part-answers-data."
  {:malli/schema [:=> [:cat :seon.render/opts :map] :any]}
  [_opts {:keys [turn ns tokens-sent your-last-reply]}]
  [:section
   [:p (str "turn " turn (when ns (str " · in " ns)) (when tokens-sent (str " · " (print/tokens-text tokens-sent) " sent in all")))]
   (when your-last-reply [:p {:class "err"} (str "your last reply was " your-last-reply)])])

(defn own-problems
  "The open problems of the agent's newest run (a component over its branch db, seon.problems/own: its run's,
  its task's failure and the errors raised in its task), by the report's render, the one root's tile
  draws; nothing when none is open or the branch holds no run of the agent yet."
  {:seon.render/style tile-style :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.db/db :seon.host/db]]] :int] :any]}
  [{db :seon.db/db} aid]
  (when-let [run-e (d/q '[:find (max ?r) . :in $ ?a :where [?r :seon.run/agent ?a]] db aid)]
    (let [r (problems/own db run-e)]
      ;; a part: the report's own render draws it, the model is sent the report
      (when (seq (:classes r)) (with-meta r {:seon.render/schema :seon.problems/report})))))

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
  "An eval of the conversation (a component over ctx), as a chat reads it (owner, 2026-10-29: \"I
  want the chat to look like a nice chat screen with just prose unless I dig into it\"): one quiet
  line, whether it came out well (✓) or threw (✗), the prose the agent wrote beside it (else its code,
  cut by the page's width only), how long it ran and the tokens it puts in the model's context (its
  messages as seon.context/eval-messages makes them, by the one sizer); opened, its code and what
  came of it, each whole, once, in place (drawn inside the closed details once a revision). Opened (:seon.eval/opened: a reader opened it, a fact the
  agent takes before it draws its page) it is drawn open. Root's evals (a ctx with no agent key) post
  nothing. Its <details> carries its handle's anchor (eval-<id>): a handle printed anywhere links here.
  See docs/render.md#the-page."
  {:seon.render/style "[&>details>summary]:flex [&>details>summary]:min-w-0 [&>details>summary]:items-baseline [&>details>summary]:gap-x-2" :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]] :int] :any]}
  [{c :seon.ctx/ctx step? :step} eid]
  (let [e (ratom/realize (get-in c [:evals eid]))
        rec (:seon.eval/record e)
        opened? (:seon.eval/opened e)
        k (:seon.agent/key c)
        {:keys [ex value ms handle]} (:response rec)
        v (ratom/realize value)
        ;; an error answered as a value (a function that returns its error) is a failure too
        err (or ex (when (contract/error? v) v))
        tokens (context/messages-tokens (context/eval-messages e nil))]
    [:li
     ;; a step of a turn (opts :step) is drawn open: its turn's one fold opens them all, never a second click
     [:details (cond-> {:class "seon-eval" :id (str "eval-" (:seon.eval/id e))} (or opened? step?) (assoc :open true))
      ;; opening it is a fact on main (seon.root/opened!), which the agent takes before it draws again
      (into (if k [:summary {:seon/call 'seon.root/opened! :seon/agent (:seon.agent/id c) :seon/args {:key k :eval (:seon.eval/id e)}}] [:summary])
            (remove nil?)
            [(when err [:span {:class "shrink-0 text-error"} "⚠"])
             (if (str/blank? (:prose rec))
               [:code {:class "seon-form min-w-0 flex-1 truncate"} (first (str/split-lines (or (:code rec) "")))]
               ;; the agent's prose is Markdown, rendered (owner, 2026-10-29); the model reads it as written
               [:div {:class "seon-prose seon-md-inline min-w-0 flex-1"} (markdown (:prose rec))])
             ;; how responsive it was and what it costs the context: quiet, at the line's end
             [:span {:class "ml-auto flex shrink-0 gap-2 text-2xs text-text-500"}
              (when ms [:span {:class "seon-ms"} (ms-str ms)])
              [:span (print/tokens-text tokens)]]])
      ;; drawn once a revision, inside the closed details, so opening it shows it at once, on a live agent's
      ;; page and on one root draws from its branch (whose opened facts no process takes)
      (do
        [:div {:class "ml-4 flex min-w-0 max-w-full flex-col gap-1 overflow-x-auto py-1"}
         ;; the code as the agent wrote it, whole, highlighted; its handle, which names its value anywhere
         [:div {:class "flex items-baseline justify-between gap-2"}
          (emit/marked-hiccup :seon.print/code (or (:code rec) ""))
          [:code {:class "seon-handle shrink-0"} (str handle)]]
         (if err
           [:div {:class "text-error"} "✗ " (:seon.error/message err)
            (render/html {:budget (config/value :seon.config.render/value-budget) :handle handle} (dissoc err :seon.error/message))]
           (when (contains? (:response rec) :value)
             [:div {:class "flex gap-2"} [:span {:class "text-text-500"} "→"]
              (render/html {:budget (config/value :seon.config.render/value-budget) :handle handle} v)]))])]]))

(defn proved
  "A prove's run as its eval's tile draws it (the declared html render of :seon.test/proved, from
  the eval's value): its one line (the schema's summary, my.agent/prove-summary), then each proof's
  status, each red test's first failing assertion by the one printer, and each test whose child died."
  {:malli/schema [:=> [:cat :seon.render/opts :map] :any]}
  [_ r]
  (let [;; its reports only when it still holds them: an agent's select-keys of a prove keeps the schema
        run (when (:reports r) r)
        failures (some-> run proofs/failures)
        died (into (sorted-set-by #(compare (str %1) (str %2))) (some-> run proofs/died))]
    [:section [:h2 "prove"]
     [:p (str (:value (render/summarized (with-meta r {:seon.render/schema :seon.test/proved}))))]
     (when (seq (:proofs r))
       [:ul (for [[p s] (sort-by (comp str key) (:proofs r))] [:li [:code (str p)] " " (name s)])])
     (for [[t fs] (sort-by (comp str key) failures) :when (not (died t))]
       [:div [:p [:code (str t)]] (render/html {} (first fs))])
     (when (seq died) [:p "died: " (for [t died] [:code (str t) " "])])]))

(defn eval-tile
  "An eval whose value has a render of its own (a component over ctx): that value, by it (its own,
  or the schema its record names: seon.repl/record-details)."
  {:seon.render/style tile-style :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]] :int] :any]}
  [{c :seon.ctx/ctx} eid]
  (let [r (get-in c [:evals eid :seon.eval/record])]
    [:section
     (render/html (if-let [k (:schema r)] {:schema k} {}) (ratom/realize (get-in r [:response :value])))]))

(defn- conversation
  "The conversation of ctx c's agent: its evals, its turns and messages, in the order they were
  written (the transaction of each member's identity datom, then its id: some ids are
  content-derived, so an id is no order). See docs/render.md#the-page."
  {:malli/schema [:=> [:cat :map :seon.host/db] [:sequential :int]]}
  [c db]
  (let [born (fn [a e] [(:tx (first (d/datoms db :eavt e a))) e])]
    (map second (sort (concat (map #(born :seon.eval/id %) (keys (:evals c))) (map #(born :seon.turn/id %) (keys (:turns c)))
                              (map #(born :seon.message/id %) (keys (:messages c))))))))

(defn- turn-evals
  "The evals of each model turn of ctx c, {turn-eid [eval-eid ...]}: a model's eval (its record names
  the provider's call id) is its turn's, the turn written just before it (a turn's record is written
  before its evals land: seon.turn/act!), so a turn holds its evals as they arrive, live. See
  docs/render.md#the-page."
  {:malli/schema [:=> [:cat :map :seon.host/db] [:map-of :int [:vector :int]]]}
  [c db]
  (let [tu? (set (keys (:turns c)))
        ;; the record's one key, read by its path (a realize reads the whole eval, its opened flag too: the layout would run again for an eval opened)
        model? (fn [id] (get-in c [:evals id :seon.eval/record :call-id]))]
    (first (reduce (fn [[m cur] id]
                     (cond (tu? id) [(assoc m id []) id]
                           (and cur (model? id)) [(update m cur conj id) cur]
                           :else [m cur]))
                   [{} nil] (conversation c db)))))

(defn chat-turn
  "A model turn as the chat shows it (a component over ctx and the db), a message on the left as a
  person reads a chat (owner, 2026-10-29: \"Does that look like a friendly chat window that my mom could
  use?\"): its reply's prose, then its evals folded into one quiet line (how many steps, how long they
  ran, any that failed), opened to one line each (`eval-item`); its thinking folded; a small footer
  with its turn and the tokens its request sent, the provider's count on its model call
  (:seon.model-call/tokens: the whole context at that turn). See docs/render.md#the-page."
  {:seon.render/style "max-w-[85%] self-start rounded-2xl rounded-tl-sm bg-base-900 px-4 py-2.5 text-text-100" :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db :seon.host/db]]] :int :int] :any]}
  [{c :seon.ctx/ctx db :seon.db/db} _aid eid]
  (let [t (get-in c [:turns eid])
        [reply reasoning n] (map #(ratom/realize (get t %)) [:seon.turn/reply :seon.turn/reasoning :seon.turn/n])
        evals (get (turn-evals c db) eid [])
        recs (map #(some-> (get-in c [:evals %]) ratom/realize :seon.eval/record) evals)
        failed (count (filter #(or (get-in % [:response :ex]) (contract/error? (ratom/realize (get-in % [:response :value])))) recs))
        ms (reduce + 0 (keep #(get-in % [:response :ms]) recs))
        tokens (d/q '[:find ?k . :in $ ?t :where [?m :seon.model-call/turn ?t] [?m :seon.model-call/tokens ?k]] db eid)]
    (when (or (not (str/blank? reply)) (not (str/blank? reasoning)) (seq evals))
      [:section {:class "flex flex-col gap-1.5"}
       (when-not (str/blank? reasoning) [:details [:summary {:class "text-xs text-text-500"} "thinking"] (markdown reasoning)])
       (when-not (str/blank? reply) [:div {:class "seon-message"} (markdown reply)])
       (when (seq evals)
         [:details {:class "seon-steps"}
          [:summary {:class "text-xs text-text-500"}
           (str (count evals) (if (= 1 (count evals)) " step" " steps") " · " (ms-str ms))
           (when (pos? failed) [:span {:class "text-error"} (str " · ⚠ " failed " failed")])]
          [:ol {:class "seon-turn mt-1 flex flex-col"} (for [e evals] [#'eval-item {:step true} e])]])
       [:p {:class "text-2xs text-text-500"} (str/join " · " (remove nil? [(when n (str "turn " n)) (when tokens (str (print/tokens-text tokens) " of context"))]))]])))

(defn chat-ask
  "What was asked, the chat's first message, a person's on the right (a component over ctx): the
  task's plan as it accumulated, as Markdown. The model reads the plan in the task's part; the chat
  opens with it so a person reads the conversation from its start. See docs/render.md#the-page."
  {:seon.render/style "max-w-[85%] self-end rounded-2xl rounded-tr-sm bg-base-800 px-4 py-2.5 text-text-100" :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]] :int] :any]}
  [{c :seon.ctx/ctx} _aid]
  (when-let [plan (some-> (:task c) ratom/realize :seon.task/plan)]
    [:section [:div {:class "seon-message"} (markdown plan)]]))

(defn chat-message
  "A message to the agent as the chat shows it (a component over ctx): who sent it, its body as
  Markdown, a person's on the right. See docs/render.md#the-page."
  {:seon.render/style "max-w-[85%] self-end rounded-lg bg-base-800 px-3 py-2 text-sm text-text-100" :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]] :int :int] :any]}
  [{c :seon.ctx/ctx} _aid eid]
  (let [m (get-in c [:messages eid])
        [from body] (map #(ratom/realize (get m %)) [:seon.message/from :seon.message/body])]
    [:section
     [:p {:class "text-2xs text-text-500"} (str from)]
     (markdown (str body))]))

(defn- newest-run
  "The newest run of agent aid on db (its :seon.run/agent), or nil."
  {:malli/schema [:=> [:cat :seon.host/db :int] :any]}
  [db aid] (some->> (d/q '[:find [?r ...] :in $ ?a :where [?r :seon.run/agent ?a]] db aid) seq (apply max) (d/entity db)))

(defn chat-start
  "Where the work began, the chat's line after what was asked (a component over the db): its run's
  turn budget. The agent's loop eval (seon.turn/work!) is not drawn: this line and `chat-end` say what
  it did. See docs/render.md#the-page."
  {:seon.render/style "self-center text-xs text-text-500" :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.db/db :seon.host/db]]] :int] :any]}
  [{db :seon.db/db} aid]
  (when-let [run (newest-run db aid)]
    [:p (str "started working" (when-let [m (:seon.run/max-turns run)] (str " · a budget of " m " turns")))]))

(defn chat-end
  "How the work ended, the chat's last card (a component over ctx and the db): its run's end
  (:seon.end/*: its outcome, its turns, the tokens it spent), done or why not; while it runs, how the
  agent is. See docs/render.md#the-page."
  {:seon.render/style "self-center rounded-xl border border-base-800 px-4 py-2 text-sm" :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db :seon.host/db]]] :int] :any]}
  [{c :seon.ctx/ctx db :seon.db/db} aid]
  (let [run (newest-run db aid)
        end (some->> (:seon.run/id run) (vector :seon.end/run) (d/entity db))
        status (ratom/realize (:seon.agent/status c))]
    (cond
      end (let [{:seon.end/keys [outcome turns tokens reason]} end]
            [:p {:class (if (= :green outcome) "text-success" "text-text-100")}
             (str (if (= :green outcome) "✓ Done" (str "Ended: " (kname outcome)))
                  (when turns (str " in " turns (if (= 1 turns) " turn" " turns")))
                  (when tokens (str " · " (print/tokens-text tokens)))
                  (when (and reason (not= :green outcome)) (str " · " reason)))])
      status [:p {:class "text-text-500"} (case status :busy "working…" :idle "waiting for you" (kname status))])))

(defn render-ctx
  "The render of an agent's ctx, the one layout the model and the person read (the declared html
  render of :seon.render/render-ctx, which an agent overrides on its branch like any render): its ctx,
  read through the view, as components placed in the render's three lists, each keyed by what it
  shows (an eid, a var, a key). The task's list (p-task, the parts the model is sent: seon.context/page-lists):
  the newest run's task, each var its plan cites, what is related, what its newest turn observed,
  the run once it took a turn, then each key the agent added to its ctx. The conversation (c-rows, the chat a person reads): the run's turns as
  their replies (chat-turn), its evals as lines that open (eval-item) and its messages as bubbles
  (chat-message), in the order they were written (eids are issued in that order), then how the run
  stands (chat-end). See docs/render.md#the-page. The
  tiles (p-tiles): each eval whose value has a render of its own, newest first. It reads the
  lists' members' ids and its task's cited names, and on the db the transaction of each member's
  identity (when it was written: some ids are content-derived, so an id is no order), which is never
  written again, so it runs again only when a list gains or loses a member, the newest run
  changes, or the plan cites another var."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db :seon.host/db]]]] :any]}
  [{c :seon.ctx/ctx db :seon.db/db}]
  (let [aid (:db/id c)
        task (:task c)
        evals (:evals c)
        conv (conversation c db)
        ev? (set (keys evals))
        tu? (set (keys (:turns c)))
        ;; a model's evals are drawn in their turn's message, not again in the list
        in-turn (into #{} cat (vals (turn-evals c db)))
        ;; the agent's own loop (its eval with no model call that names a run: seon.turn/work!) is the chat's
        ;; start and end cards, not a row
        ;; read by its paths, never realized whole: the layout reads no eval's opened flag
        loop? (fn [id] (let [e (get-in c [:evals id])]
                         (and (= (:seon.eval/by e) (some-> (:seon.agent/id c) name)) (get-in e [:seon.eval/record :run]) (not (get-in e [:seon.eval/record :call-id])))))]
    [:div {:id "render-ctx"}
     [:div {:id "p-task"}
      (when task (list [#'task-data {} aid]
                       ;; each var the plan cites beside its proofs (the proofs are the task's data)
                       ;; each thing its plan cites, a part each, at the depth its citation asks
                       (for [cite (context/citations db (task/task db (:seon.task/id task)))] [#'cited {} aid (context/cite-key cite)])
                       ;; where the code lives: each namespace the work touches, its aliases, definitions and tests
                       [#'namespaces {} aid]
                       [#'related {} aid]
                       ;; how the work stands: the proofs, the done rule's reasons, what the branch did (one diff)
                       [#'status {} aid]))
      ;; the run once it took a turn: before that it says only its id, which the task's names
      (when (seq (:turns c)) [#'run-head {} aid])
      (when aid [#'own-problems {} aid])
      (when (seq (added-keys c)) [#'ctx-keys {}])]
     [:ol {:id "c-rows"}
      (when (:task c) [#'chat-ask {} aid])
      (when aid [#'chat-start {} aid])
      (for [id conv :when (and (not (in-turn id)) (not (loop? id)))] (cond (ev? id) [#'eval-item {} id] (tu? id) [#'chat-turn {} aid id] :else [#'chat-message {} aid id]))
      (when aid [#'chat-end {} aid])]
     [:aside {:id "p-tiles"}
      (for [[id e] evals :let [{{v :value} :response k :schema} (:seon.eval/record e)] :when (and (some? v) (render/render-of (ratom/realize v) k))]
        [#'eval-tile {} id])]]))

(defn agent
  "An agent's view (the declared html render of :seon.db/agent-fact), its head: its id, status,
  what it runs and on which branch. Its page is its parts (seon.render.proc's agent page,
  parts-page)."
  {:malli/schema [:=> [:cat :seon.render/opts :map] :any]}
  [opts v]
  ;; what it evaluates now (its process's :seon.process/running) is its conversation's newest part
  (let [{:seon.agent/keys [id status branch description]} v
        ;; its current process's pid while it runs: an ended process's pid names nothing live
        ;; a ctx join holds its members by id (a map); root's own pull, a vector
        proc (task/newest-process (let [ps (:processes v)] (if (map? ps) (vals ps) ps)))
        pid (when-not (:seon.process/ended-ms proc) (:seon.process/pid proc))]
    [:div {:class "flex flex-col gap-1"}
     [:div {:class "flex items-baseline gap-3"}
      [:h1 {:class "text-base font-bold text-signal"} (kname id)]
      ;; how it is now, at a glance: working (its process runs a form), waiting, or ended
      [:span {:class (case status :busy "text-signal" (:green :done) "text-success" (:red :failed :error) "text-error" "text-text-500")}
       ;; in a person's words: working while its process runs a form, waiting for you, finished
       (str "● " (case status :busy "working" :idle "waiting" :ended "finished" :dead "stopped" (kname status)))]
      ;; what it evaluates now, while its process runs one: the newest thing it does, live
      (when-let [r (and pid (:seon.process/running proc))]
        [:code {:class "min-w-0 truncate text-text-300"} r])]
     [:p {:class "text-text-500 text-xs"} (str/join " · " (remove nil? [(when branch (str "on branch " (kname branch))) (when pid (str "pid " pid))]))]
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

(defn agent-card
  "An agent of root's as its board shows it (a component over root's ctx, its :agents member eid):
  its id linking to its page, its status, then its newest run: the task in words, its model, the turns
  it took of its cap, how it ended or why it waits, and each proof's result. See docs/render.md#the-page."
  {:seon.render/style panel-style :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]] :int] :any]}
  [{c :seon.ctx/ctx} eid]
  (let [a (get-in c [:agents eid])
        ;; its id, status and runs alone, so another agent's eval redraws no card
        id (get a :seon.agent/id) status (get a :seon.agent/status)
        on? (contains? #{:busy :running} status)
        r (last (sort-by :seon.run/start-ms (vals (ratom/realize (get a :runs)))))
        {:seon.run/keys [model turns max-turns outcome reason results] run-id :seon.run/id run-status :seon.run/status} r]
    [:section
     [:p {:class "flex items-baseline gap-2"}
      [:span {:class (if on? "text-signal" "text-text-500")} (if on? "●" "○")]
      [:a {:href (str "/agent/" (kname id)) :class "font-bold text-text-100"} (kname id)]
      (when status [:span {:class "text-2xs text-text-500"} (name status)])
      (when model [:span {:class "ml-auto text-2xs text-text-500"} model])]
     (if-not r
       [:p {:class "text-xs text-text-500"} "no task yet"]
       (list
        [:p {:class "text-sm text-text-100"} (task-title run-id)]
        [:p {:class "text-xs text-text-300"}
         (str (when turns (str "turn " turns (when max-turns (str " of " max-turns)) " · "))
              (kname (or outcome run-status)))
         (when-not (str/blank? reason) (list " · " [:span {:class "text-text-500"} (prose reason (cell) {:one-line? true})]))]
        (when (seq results)
          [:p {:class "flex flex-wrap gap-1 text-2xs"}
           (for [{:seon.proof/keys [status test]} (sort-by (comp str :seon.proof/test) results)]
             [:span {:class (str "rounded px-1 " (if (= :pass status) "bg-base-800 text-success" "bg-base-800 text-error")) :title (str test)}
              (str (if (= :pass status) "✓ " "✗ ") (name (symbol (str test))))])])))]))

(defn root-ctx
  "Root's page, the render of root's ctx (the declared html render of :seon.render/root-ctx, which a
  branch overrides like any render), drawn by the walk as components in its lists: its agents list
  (c-side: root, then each agent its ctx holds, by entity id), its head (p-head), each agent's card
  (p-task: what it works on and how its run stands, newest first; the user, a person, has none), its conversation
  (c-rows: its evals, in the order written) and its tiles (p-tiles: root's process, then main's
  problems and runs). An
  agent's page shows the same agents list (seon.render.proc's agent page). It reads the ids of its
  ctx's agents and evals, so it runs again only when one comes or goes."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]]] :any]}
  [{c :seon.ctx/ctx}]
  [:div {:id "root-ctx"}
   [:ol {:id "c-side"} [#'root-side {}] (for [eid (sort (keys (:agents c)))] [#'agent-side {} eid])]
   [:div {:id "p-head"} [#'root-head {}]]
   ;; what each agent is doing, newest first
   [:div {:id "p-task"} (for [eid (sort > (keys (:agents c))) :when (not= :user (get-in c [:agents eid :seon.agent/id]))] [#'agent-card {} eid])]
   [:ol {:id "c-rows"} (for [eid (sort (keys (:evals c)))] [#'eval-item {} eid])]
   [:aside {:id "p-tiles"} [#'process-tile {}] [#'problems-tile {}] [#'runs-tile {}]]])

(defn page-chrome
  "A page in root's chrome (root's, an agent's): the top bar, then the units its walks drew, each
  placed in the list it was drawn in ({:id :container :rev :html}: the agents list, the head, the
  task's list, the conversation, the tiles), and its caption and input. Each unit's input is its
  revision, so the render proc sends one only when its output changed, and a new one into its list
  where its walk placed it (an append). opts {:units :view :input :caption :tab}; id, the page's
  focus; tab, which of its tabs it is (top-bar)."
  {:malli/schema [:=> [:cat :map :keyword] [:map [:seon.render/html :any] [:seon.render/units [:vector :map]]]]}
  [{:keys [units view input caption tab]} id]
  (let [in (fn [sel] (filterv #(= sel (:container %)) units))
        unit (fn [u] {:id (:id u) :input {:rev (:rev u)} :html (:html u) :container (:container u)})]
    {:seon.render/html
     [:div {:id "app" :class "mx-auto flex max-w-screen-2xl flex-col gap-3 p-3 sm:p-4" :data-view view}
      (top-bar id tab)
      ;; the conversation is what a person follows: it has the middle column; how the work stands and the
      ;; task (#p-task) and the tiles sit beside it, sticky, so they stay in view while the chat scrolls;
      ;; on a narrow screen they follow the conversation
      ;; three columns always: a page's side parts may arrive after its first frame (an agent's walk answers
      ;; later), and a layout chosen from the first frame's units wrapped them under the agents list
      [:div {:class "grid grid-cols-1 gap-4 lg:grid-cols-[12rem_minmax(0,1fr)_26rem]"}
       [:aside {:class "min-w-0 lg:sticky lg:top-14 lg:self-start"}
        [:h2 {:class "kicker mb-1 hidden lg:block"} "agents"]
        [:ol {:id "c-side" :class "flex gap-2 overflow-x-auto pb-1 text-xs lg:flex-col lg:gap-0.5"} (for [u (in "#c-side")] (slot (:id u)))]]
       [:main {:class "flex min-w-0 flex-col gap-3"}
        [:div {:id "p-head" :class "lg:sticky lg:top-12 lg:z-10 lg:bg-base-950"} (for [u (in "#p-head")] (slot (:id u)))]
        (slot "p-caption")
        [:ol {:id "c-rows" :class "flex flex-col gap-1" :data-view view} (for [u (in "#c-rows")] (slot (:id u)))]
        (when input (slot "p-input"))]
       [:aside {:class "flex min-w-0 flex-col gap-2 lg:sticky lg:top-14 lg:max-h-[calc(100vh-4rem)] lg:self-start lg:overflow-y-auto"}
        [:div {:id "p-task" :class "flex flex-col gap-2"} (for [u (in "#p-task")] (slot (:id u)))]
        [:div {:id "p-tiles" :class "grid content-start gap-2"} (for [u (in "#p-tiles")] (slot (:id u)))]]]]
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
      (top-bar id :context)
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
  "What made part ref of a sent request, as a line: the help page as of a t, the page's parts at a turn
  (whole, or what changed since the turn before), an eval, a message: every ref kind
  seon.context/request names (:seon.model-call/sent)."
  {:malli/schema [:=> [:cat :any] :string]}
  [[kind x t c :as ref]]
  (case kind
    :section (str "the help page (its section, entity " x ") as of t " t)
    :parts (str (if c "the page's parts whole" "the page's parts that changed") " at commit " t ": " (str/join ", " (nth ref 4)))
    :eval (str "eval " x)
    :turn (str "the reply of turn " x)
    :message (str "message " x)))

(defn- sent-parts-html
  "Parts [[ref messages]] of a sent request, each beside the record that made it, each message
  whole: the system prompt folded, an assistant message's prose, reasoning (folded) and eval
  calls, a tool message under the call it answers."
  {:malli/schema [:=> [:cat [:sequential :any]] :any]}
  [parts]
  (let [text (fn [s] [:pre {:class "whitespace-pre-wrap break-words text-text-100"} s])
        fold (fn [label s] [:details [:summary {:class "text-2xs text-text-500"} (str label ", " (print/tokens-text (select/chars-tokens (count s))))] (text s)])
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

(defn- request-messages
  "A sent request's messages in order, from its parts [[ref messages]], each [ref message tokens new?]:
  tokens by the one sizer (seon.context/messages-tokens), new? whether its record is one this turn
  added (added, its refs)."
  {:malli/schema [:=> [:cat [:sequential :any] [:set :any]] [:vector :any]]}
  [parts added]
  (vec (for [[ref ms] parts m ms] [ref m (context/messages-tokens [m]) (contains? added ref)])))

(defn sent-request
  "A model turn's request as it recorded it (the declared html render of :seon.render/sent-request),
  whole, nothing hidden (owner, 2026-10-29: \"I want to understand what the agent was sent cleanly ...
  I don't want anything hidden\"): its turn and the provider's numbers (its tokens, the prompt tokens it
  served from cache), then every message it sent in order, each a card with its role, the record that
  made it and its tokens (the one sizer's estimate): what it sends as the turn before it did, grouped
  under one line that opens to it, then what this turn added, whole; and the cache point, where the
  provider's cached prefix ends, placed by its cache-hit count over the messages' estimates (an
  estimate, said so). input {:turn :run :view :refs :whole :added-refs :same :split}, the turn's origin,
  usage or error. See docs/render.md#the-models-context-a-cached-prefix-then-the-turn."
  {:malli/schema [:=> [:cat :seon.render/opts :map] :any]}
  [opts {:keys [turn run refs whole added-refs same split] :seon.turn/keys [origin error] :seon.model-call/keys [tokens cache-hit-tokens cache-miss-tokens]}]
  (let [prompt (when (and cache-hit-tokens cache-miss-tokens) (+ cache-hit-tokens cache-miss-tokens))
        unit (:unit opts)
        ms (request-messages whole (or added-refs #{}))
        est (reduce + 0 (map #(nth % 2) ms))
        ;; the messages the provider's cached prefix covers: its cache-hit count apportioned over the estimates
        scale (if (and prompt (pos? est)) (/ (double prompt) est) 1.0)
        cached (when (and cache-hit-tokens (pos? cache-hit-tokens))
                 (count (take-while #(<= % cache-hit-tokens) (reductions + (map #(* scale (nth % 2)) ms)))))
        cache-line [:div {:class "my-1 flex items-center gap-2 text-2xs text-info"}
                    [:span {:class "h-px flex-1 bg-info/40"}]
                    (str "the provider's cache ends here: " (group3 (or cache-hit-tokens 0)) " tokens served from cache (placed by estimate)")
                    [:span {:class "h-px flex-1 bg-info/40"}]]
        card (fn [i [ref {:keys [role content reasoning tool-calls tool-call-id]} tk]]
               (list
                 [:div {:class (str "flex flex-col gap-1 rounded-md border-l-2 bg-base-950 px-2 py-1 "
                                    (case role "assistant" "border-signal" "tool" "border-base-600" "user" "border-text-400" "border-info"))}
                  [:div {:class "flex items-baseline justify-between gap-2 text-2xs text-text-500"}
                   [:span (str role (when tool-call-id " · result") " · ") (source ref)] [:span (print/tokens-text tk)]]
                  (when reasoning [:details [:summary {:class "text-2xs text-text-500"} "reasoning, as returned"] [:pre {:class "whitespace-pre-wrap break-words"} reasoning]])
                  (when-not (str/blank? content) [:pre {:class "whitespace-pre-wrap break-words text-text-100"} content])
                  (for [{:keys [code prose]} tool-calls]
                    [:div {:class "flex flex-col gap-0.5"}
                     (when prose [:p {:class "italic text-text-300"} prose])
                     (emit/marked-hiccup :seon.print/code code)])]
                 (when (= (inc i) cached) cache-line)))
        shared (vec (take-while #(not (nth % 3)) ms))
        n-shared (if same (count shared) 0)]
    [:li {:id unit :class "flex flex-col gap-2 rounded-md bg-base-900 p-3 text-xs"}
     [:div {:class "flex flex-wrap items-baseline gap-3"}
      [:b {:class "text-signal"} (str "turn " (inc turn))] [:span {:class "text-text-400"} run]
      (when tokens [:span {:class "tabular-nums text-text-300"} (str (group3 tokens) " tokens")])
      (when (and prompt (pos? prompt)) [:span {:class "tabular-nums text-text-400"} (str (group3 cache-hit-tokens) " of " (group3 prompt) " prompt tokens from cache (" (long (* 100 (/ cache-hit-tokens prompt))) "%)")])
      [:span {:class "text-text-500"} (str (count ms) " messages · " refs " records")]
      (cond (= :outside origin) [:span {:class "text-text-500"} "an outside read: no model call"]
            (not (or tokens error)) [:span {:class "text-text-500"} "the call is in flight"])]
     (when split
       [:p {:class "tabular-nums text-text-500"}
        (str/join " · " (for [[k w] [[:system "system"] [:conversation "conversation"] [:page "page"]]]
                          (str w " " (print/tokens-text (get split k 0)))))])
     (when error [:div {:class "rounded border border-error p-2"} (render/html {} error)])
     ;; what it sends as the turn before it did: one line that opens to it, its cache point inside
     (when (pos? n-shared)
       [:details
        [:summary {:class "cursor-pointer text-text-400"}
         (str "same as turn " (inc (:turn same)) " · " n-shared " messages · " (print/tokens-text (reduce + 0 (map #(nth % 2) shared)))
              (when (and cached (<= cached n-shared)) " · from cache up to the line inside"))]
        (into [:div {:class "flex flex-col gap-1 pt-1"}] (mapcat card (range) shared))])
     [:div {:class "text-2xs uppercase text-text-500"} (if (pos? n-shared) "new this turn" "what this turn sent")]
     (into [:div {:class "flex flex-col gap-1"}]
           (mapcat card (map #(+ % n-shared) (range)) (drop n-shared ms)))]))
