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
  (:require [seon.learn :as learn] [seon.doc :as doc] [seon.contract :as contract :refer [defn defn-]] [clojure.string :as str] [clojure.edn :as edn] [clojure.data.json :as json] [seon.config :as config]
            [seon.render :as render] [datahike.api :as d] [seon.context :as context] [seon.program :as program] [seon.print :as print] [seon.print.emit :as emit] [seon.print.select :as select] [seon.web.html :as h] [seon.reader :as reader] [seon.cost :as cost] [seon.task :as task] [seon.ctx] [seon.problems :as problems] [seon.edn :as sedn] [datahike.index.interface] [seon.ratom :as ratom] [seon.vector :as vector] [seon.db :as pdb] [seon.proofs :as proofs] [seon.merge :as pm] [seon.ref :as ref] [seon.reconcile :as reconcile] [clojure.set]))

;;; --- small formatting helpers -------------------------------------------------

(defn- cell "Tokens of a table cell or a stream row's field." {:malli/schema [:=> [:cat] pos-int?]} [] (config/value :seon.config.render/cell-budget))
(defn- part "Tokens of a value shown inside another." {:malli/schema [:=> [:cat] pos-int?]} [] (config/value :seon.config.render/part-budget))
(defn- form-budget "Tokens of a form echoed back." {:malli/schema [:=> [:cat] pos-int?]} [] (config/value :seon.config.render/form-budget))

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
  [b] (emit/bytes-text b))

(defn ms-str {:malli/schema [:=> [:cat :any] :string]}
  [ms] (emit/ms-text ms))

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
  becomes \"A long string\");  See docs/render.md#the-page."
  {:malli/schema [:=> [:cat :string] :string]}
  [id]
  (let [s (str/replace (name (symbol id)) "-" " ")]
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
  root stopped it after its work finished (done or merged), :killed for a kill, :died
  when its process died on its own (an agent entry carries :ended-as from its task's outcome)."
  {:malli/schema [:=> [:cat :map] :keyword]}
  [a]
  (cond (or (= :ended (:status a)) (:ended-as a)) :ended
        (= :dead (:status a)) :died
        :else (or (:status a) :unknown)))

;;; --- the load, as data -------------------------------------------------------------

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
  (its status, or the event's type, is gone) after its work finished (its task on main
  done or merged); else x as it is (identical: inputs compare O(1))."
  {:malli/schema [:=> [:cat [:maybe :map] :any :map] [:maybe :map]]}
  [x id agent-outcome]
  (let [st (get agent-outcome id)]
    (if (and x (gone (or (:status x) (:type x))) (finished st)) (assoc x :ended-as st) x)))

(defn focus-of
  "A page's focus as its bar and its head name it: {:address :label :view}. Root's is :root. An agent's is a map of its address
  (its key, boot/id, the one name of a session that survives a root's restart: every link of its pages is /agent/<address>), its label
  (what a person calls it) and the view's own address (the name this page was asked by, which its feed and toggles name); anything
  else is its name for each. See docs/render.md#a-sessions-address-and-the-agents-list."
  {:malli/schema [:=> [:cat :any] [:map [:address :string] [:label :string]]]}
  [focus]
  (cond (map? focus) (merge {:view (keyword (:address focus))} focus)
        (= :root focus) {:address "root" :label "root" :view :root}
        :else {:address (kname focus) :label (kname focus) :view focus}))

(defn- top-bar
  "The bar above every page: Seon's mark, home, then, on root's or an agent's page (focus :root
  or its id), its tabs, tab the one shown: root's conversation and what its model reads; an
  agent's chat (the page a person reads), @ctx (every render of its ctx) and debug (the timeline of
  what its model is sent). See docs/render.md#the-page."
  {:malli/schema [:=> [:cat :any [:enum :chat :debug :context]] :any]}
  [focus tab]
  (let [{:keys [address label]} (focus-of focus)
        link (fn [on? href label] [:a {:href href :class (str "rounded px-2 py-0.5 " (if on? "bg-base-800 text-text-50" "text-text-400 hover:text-text-100"))
                                      :aria-current (when on? "page")} label])]
    [:nav {:class "seon-bar"}
     [:a {:href "/" :class "font-bold text-signal"} "◆ seon"] [:span {:class "hidden text-text-500 sm:inline"} "on jolt"]
     (when focus
       (let [base (if (= :root focus) "/" (str "/agent/" address))]
         [:div {:class "ml-auto flex items-baseline gap-1 text-xs"}
          [:span {:class "mr-1 font-semibold text-text-100"} label]
          (if (= :root focus)
            (list (link (= :chat tab) base "conversation") (link (= :context tab) "/root/debug" "context"))
            (list (link (= :chat tab) base "chat") (link (= :debug tab) (str base "/debug") "@ctx") (link (= :context tab) (str base "/context") "debug")))]))]))

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
      (when-let [{:keys [reason counterexample agent]} (:seon.task/failure t)]
        [:p {:class "err"} "failed" (when agent (str " in " agent)) ": " reason (when counterexample (list " · counterexample " [:code counterexample]))])
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
  :live? :held :focus? :busy? :capped?}: live, root's view holds its process in this boot; busy?, it runs a form (what interrupt stops); capped?, its task is
  at its cap (no message wakes it); focus?, its field takes focus as the page loads (the chat's:
  @ctx's page is long and the field ends it)."
  {:malli/schema [:=> [:cat :seon.render/opts :seon.render/agent-input] :any]}
  [opts {:keys [id live? held focus? busy? capped?]}]
  [:section {:id (:unit opts) :class "seon-stack-2"}
   (if live?
     [:form {:class "seon-row" :seon/call 'seon.root/submit! :seon/agent id}
      [:span {:class "text-signal"} (str (kname id) "=>")]
      ;; Enter sends, Shift+Enter is a new line: a form may span lines
      [:textarea (cond-> {:name "forms" :class "flex-1 min-w-0" :rows 3 :autocomplete "off" :spellcheck "false"
                          (keyword "data-on:keydown") "if (evt.key==='Enter' && !evt.shiftKey) { evt.preventDefault(); el.form.requestSubmit() }"
                          :placeholder (str "(+ 1 2) evaluates in its REPL (Enter; Shift+Enter: a new line)" (when-not capped? "\n> a message, read at its next turn"))}
                   ;; the chat takes focus (it follows its tail); a browser scrolls a focused field into view, and this one ends the page
                   focus? (assoc :autofocus true))]
      ;; a form to interrupt only while its process runs one (status :busy); a capped task's agent answers forms but takes no message
      (when busy?
        [:button {:type "button" :class "rounded border border-base-700 px-2" :title "interrupt the running form; the agent keeps its state"
                  :seon/call 'seon.root/interrupt! :seon/agent id} "⏹ interrupt"])]
     [:p {:class "muted"} "no process of it in root's view: no REPL to evaluate in, no turn to read a message; what it did is below, read from the store"])
   (when (and live? capped?)
     [:p {:class "seon-note"} "stopped at its cap: its process answers forms, not messages; raise its turns and release the task to go on"])
   (when held
     [:button {:class "self-start rounded border border-base-700 px-2" :title (str "release " held ": the reconciler runs its next run (my.root/run!)")
               :seon/call 'seon.root/release! :seon/agent id :seon/args {:task held}}
      (str "▶ release " held)])])

;;; --- an eval's parts ---------------------------------------------------------------

(defn- cost-line
  "What an evaluation cost, as recorded when it ran, in the one wording (`seon.print/cost-text`), its result's cost to the context
  among it; one not recorded is left out, never shown as 0, and an eval never run says so."
  {:malli/schema [:=> [:cat :map] :any]}
  [r]
  [:span {:class "tabular-nums text-text-400"}
   (if (some r [:ms :ticks :bytes]) (print/cost-text (select-keys r [:ms :ticks :bytes])) "not run")])

(def ^:private asked-by
  "Who evaluated, by its :seon.eval/by (any other: the agent's own, its model's turn)."
  {"user" "the owner, from this page" "outside" "an outside agent, through root's server" "context" "the context, read for a turn"})

(defn- shown-value
  "What an eval's recorded response shows as its value: the response less its handle and what
  the row shows beside it (cost, the costliest functions, its error)."
  {:malli/schema [:=> [:cat :map] :map]}
  [response]
  (dissoc response :handle :ms :ticks :bytes :fns :ex :text :record :recorded))

(defn evaluation
  "One eval (the declared html render of :seon.db/eval-fact, its attributes, and of a response), as
  it ran: the prose beside it, its code, its response printed under its handle as it is shown
  (seon.print/record-text of the eval, through the model render of the schema it kept; a value in a row by that schema's
  html render; a live response's own :text; an error's by its error
  map's render; a value recorded as its basis, @ctx's, rendered from the branch at it, seon.context/basis-value), and its cost and who evaluated it as recorded; :as :inline, its code and
  response in one line, the value's line printed now (seon.print/line: the one printer). Rendered from the eval, which never changes."
  {:malli/schema [:=> [:cat :seon.render/opts :map] :any]}
  [opts e]
  (let [recorded? (contains? e :seon.eval/response)
        resp (:seon.eval/response e)
        basis (:seon.eval/basis e)
        r (if recorded?
            (cond-> resp (:seon.eval/code e) (assoc :code (:seon.eval/code e)) (:seon.eval/intent e) (assoc :intent (:seon.eval/intent e))
                    (:seon.eval/fns e) (assoc :fns (:seon.eval/fns e)))
            e)
        ;; a value recorded as data: inline, printed as its reply reads under its handle; in a row,
        ;; rendered as structure (the printer's hiccup). A live reply's :text is prose
        data? (and recorded? (nil? (:text r)) (nil? basis))
        r (cond-> r (and recorded? (nil? (:text r)) data? (= :inline (:as opts))) (assoc :text (print/record-text e render/model)))
        by (or (:seon.eval/by e) (:by r))
        err? (some? (:ex r))]
    (if (= :inline (:as opts))
      ;; its code's first line ⇒ its value's line, printed now by the one printer under the line budget, else its
      ;; text; in the chat (:chat true), the agent's prose first: the chat is prose and a line an arrow opens
      (let [shown (when (and recorded? (or (contains? resp :value) (:ex resp) basis))
                    (let [v (:value resp) k (:seon.eval/schema e)
                          m (when (and k (instance? clojure.lang.IObj v)) (render/model (vary-meta v assoc :seon.render/schema k)))]
                      (print/line (cond (:ex resp) (or (get-in resp [:ex :seon.error/message]) (:ex resp))
                                        basis (when (:db opts) (context/basis-value (:db opts) e))
                                        (nil? v) "nil" m (:value m) :else v)
                                  (config/value :seon.config.render/line-budget) nil)))
            line [:span {:class "font-mono"} [:code (merge-with #(str %1 " " %2) emit/highlight {:class "text-text-100"}) (prose (or (first (str/split-lines (or (:code r) ""))) "") (cell) {:one-line? true})]
                  [:span {:class (if err? "text-error" "muted")} (if err? " ✕ " " ⇒ ")]
                  [:code {:class "text-text-400"} (prose (or shown (:text r) "") (cell) {:one-line? true})]]]
        (if (and (:chat opts) (not (str/blank? (:intent r))))
          [:span {:class "flex flex-col"} [:span {:class "whitespace-pre-wrap text-text-300 italic"} (prose (:intent r) (form-budget))] line]
          line))
      [:div {:class (str "flex flex-col gap-1 border-l-2 pl-3 py-1 " (if err? "border-error" "border-base-700"))}
       (when-not (str/blank? (:intent r)) [:p {:class "whitespace-pre-wrap text-text-300 italic"} (prose (:intent r) (form-budget))])
       (when (:code r) (code (:code r) (form-budget)))
       (if (map? (:ex r))
         (render/html (cond-> {} (:handle r) (assoc :handle (list :ex (:handle r)))) (:ex r))
         (if data?
           ;; a value stored as data renders as structure, the printer's hiccup under its handle
           ;; a value of a schema with its own render is that value; any other, the response less what the row shows beside it
           (let [v (if (:seon.eval/schema e) (:value resp) (shown-value resp))]
             [:div {:class "flex flex-col gap-1 text-text-200"}
              ;; drawn by the html render of the schema the record kept (the value's metadata does not survive the store),
              ;; as the live response is: a prove's run by seon.render.ui/proved, not as a map
              [:div {:class "seon-row"} [:span {:class "muted"} "⇒"] (render/html (cond-> {:handle (:handle r)} (:seon.eval/schema e) (assoc :schema (:seon.eval/schema e))) v)]
              ;; each cut opened: what its form reads, from the record, under that form (seon.web/open-form read it)
              (for [f (:opened opts) :let [form (edn/read-string f)]]
                [:div {:class "seon-row"}
                 ;; the form read, as the button that closes it
                 [:button {:type "button" :class "text-text-500 font-mono text-left" :data-seon-form f :title "close"
                           (keyword "data-on:click") (str "@post('/open?view=' + el.closest('[data-view]').dataset.view + '&unit=' + "
                                                          "encodeURIComponent(el.closest('li[id]').id + '/' + el.dataset.seonForm) + '&open=false')")} f]
                 (render/html {:handle form} (print/read-rest form (:handle r) v))])])
           (if-let [v (when (and basis (:db opts)) (context/basis-value (:db opts) e))]
             ;; a value recorded as its basis (@ctx): rendered from what the branch holds at it
             [:div {:class "flex gap-2 text-text-200"} [:span {:class "muted"} "⇒"]
              (render/html {:as :block :schema (:seon.eval/schema e) :handle (:handle r) :db (:db opts)} v)]
             [:pre {:class "text-text-200"} [:span {:class "muted"} "⇒ "] (prose (or (:text r) "") (part))])))
       [:div {:class "flex flex-wrap gap-3 text-2xs text-text-500"}
        (cost-line r)
        [:span (get asked-by by "its own turn")]
        (when (seq (:fns r))
          [:details [:summary (str "costliest: " (:var (first (:fns r))) " · " (count (:fns r)) (if (= 1 (count (:fns r))) " function" " functions"))]
           [:table [:tbody (for [{:keys [var calls ticks self-ticks bytes]} (:fns r)]
                             [:tr [:td [:code (str var)]] [:td {:class "tabular-nums"} (str (group3 calls) " calls")]
                              [:td {:class "tabular-nums"} (str (group3 ticks) " ticks")] [:td {:class "tabular-nums"} (str (group3 self-ticks) " own")]
                              [:td {:class "tabular-nums"} (str (group3 bytes) " B")]])]]])]])))

(defn model-call-view
  "A model turn's header (the declared html render of :seon.model-call/fact), above the evaluations
  it ran: which turn of how many, its tokens and the share its provider served from cache, how
  its reply stopped and its model; its reply and reasoning as prose, all of its model call
  (:seon.model-call/*). Nothing it did not record is shown."
  {:malli/schema [:=> [:cat :seon.render/opts :map] :any]}
  [opts {:seon.model-call/keys [n origin reply reasoning finish truncated tokens cache-hit-tokens cache-miss-tokens model errors forms basis]}]
  (let [prompt (when (and cache-hit-tokens cache-miss-tokens) (+ cache-hit-tokens cache-miss-tokens))
        head (str (if (= :outside origin) (str "context read " n) (str "turn " n (when-let [m (:max-turns basis)] (str " of " m)))))]
    (if (= :inline (:as opts))
      [:span head]
      [:div {:class "seon-stack"}
       [:div {:class "flex flex-wrap items-baseline gap-3 text-xs"}
        [:b {:class "text-signal"} head]
        (when tokens [:span {:class "tabular-nums text-text-400"} (str (group3 tokens) " tokens")])
        (when (and prompt (pos? prompt)) [:span {:class "tabular-nums text-text-400" :title "the share of the prompt its provider served from its prefix cache"}
                      (str (long (* 100 (/ cache-hit-tokens prompt))) "% of the prompt cached")])
        (when finish [:span {:class "muted" :title "how its reply stopped"} (str "stopped: " finish)])
        (when model [:span {:class "muted"} model])
        (when truncated [:span {:class "text-error"} "cut at the token limit"])
        (when (and errors (pos? errors)) [:span {:class "text-error"} (str errors " of " forms " forms failed")])]
       (when-not (str/blank? reply) [:p {:class "seon-prose"} (prose reply (part))])
       (when-not (str/blank? reasoning)
         [:details [:summary {:class "seon-note"} "its reasoning"] [:p {:class "seon-prose text-text-300"} (prose reasoning (part))]])])))

(defn message
  "A message to a run (the declared html render of :seon.message/fact): who sent it and its body as prose."
  {:malli/schema [:=> [:cat :seon.render/opts :map] :any]}
  [opts {:seon.message/keys [from body]}]
  (if (= :inline (:as opts))
    [:span (str from ": ") (prose body (cell) {:one-line? true})]
    [:div {:class "border-l-2 border-info pl-3 py-1"}
     [:span {:class "seon-note"} (str "message from " from)]
     [:p {:class "seon-prose"} (prose body (part))]]))

(declare standing)

(defn task-view
  "A task (the declared html render of :seon.task/task-fact) as a person reads it, wherever the pattern joined one: inline its
  id, kind and execution; whole, its name as a title, its namespace and how its work stands above it (the task's outcome
  where it holds one: a capped task is not \"running\"), how its kind is worked and its plan accumulated over its needs
  as Markdown. The agent's own task part is `task`. See docs/render.md#a-part-answers-data."
  {:malli/schema [:=> [:cat :seon.render/opts :map] :any]}
  [opts {:seon.task/keys [id kind execution instructions] :as t}]
  (if (= :inline (:as opts))
    [:span (str "task " id (when kind (str " · " (kname kind))) (when execution (str " · " (kname execution))))]
    (let [{:keys [plan]} (task/pulled-accumulated t)]
      [:section
       [:p {:class "text-2xs uppercase tracking-wider text-text-500"}
        (str "task · " (namespace (symbol id))
             (cond (:seon.task/outcome t) (str " · " (:text (standing nil {:seon.task/outcome (:seon.task/outcome t)})))
                   execution (str " · " (kname execution))))]
       [:p {:class "text-lg font-bold text-text-100"} (task-title id)]
       (when instructions [:details [:summary "how the agent works it"] [:p {:class "seon-prose"} instructions]])
       (when plan (markdown plan))])))

(defn- toggle
  "A <details>' attrs that tell the render proc it opened or closed (/open), for the conversation's
  part with element id: its view read from the list it sits in (data-view)."
  {:malli/schema [:=> [:cat :string] :map]}
  [id] {(keyword "data-on:toggle") (str "@post('/open?view=' + el.closest('[data-view]').dataset.view + '&unit=" id "&open=' + el.open)")})

(defn problem-about
  "What problem row r is about, as words: its task, test and attribute, those it has (the
  wording `problem-row` uses)."
  {:malli/schema [:=> [:cat :map] [:sequential :string]]}
  [r] (remove nil? [(:task r) (some-> (:test r) str) (some-> (:attr r) kname)]))

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

(defn problems-model
  "The problems report as the model is sent it (the declared model render of :seon.problems/report):
  each class with its count and its rows newest first, a row its count, type, its message's first line
  and, for a recorded error, the form that reads it whole ((my.agent/problem signature): its example and
  evidence), never the rows printed whole. So many errors are a few lines that say how many there are
  and where to read each. See docs/render.md#a-part-answers-data."
  {:malli/schema [:=> [:cat :map :seon.problems/report] :map]}
  [_opts {:keys [t classes]}]
  {:t t
   :classes (into (sorted-map)
                  (for [[c {:keys [n rows]}] classes]
                    [c {:n n
                        :rows (vec (for [r (sort-by #(- (or (:at-ms %) 0)) rows)
                                         :let [line (some-> (or (:message r) (:reason r)) str str/split-lines first)]]
                                     (cond-> {:count (:count r 1)}
                                       (:type r) (assoc :type (:type r))
                                       line (assoc :message line)
                                       (false? (:open? r)) (assoc :open? false)
                                       (:signature r) (assoc :read (list 'my.agent/problem (:signature r))))))}]))})

(defn work
  "Main's work (the declared html render of :seon.render/work): how many tasks by outcome, and each
  one being worked, a line. See docs/render.md#the-page."
  {:malli/schema [:=> [:cat :seon.render/opts :seon.render/work] :any]}
  [_ {:keys [by-outcome working]}]
  [:div {:class "flex flex-col gap-1 text-xs"}
   [:p (if (empty? by-outcome) "none yet" (str/join " · " (for [[s n] (sort-by (comp str key) by-outcome)] (str n " " (kname s)))))]
   (for [{:seon.task/keys [id reason]} working] [:p (str id " · running" (when-not (str/blank? reason) (str " · " reason)))])])

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
       [:div {:class "muted"} (str "ctx · " branch " · t " t (when commit (str " · commit " commit)))]
       [:table [:tbody (for [k ks :let [v (get c k)]]
                         [:tr {:class "align-top"}
                          [:td {:class "pr-3 font-mono text-text-400 whitespace-nowrap"} (str k)]
                          [:td (cell k v)]
                          [:td {:class "pl-3 text-2xs text-text-500 whitespace-nowrap"} (made k)]])]]])))

(defn to-come?
  "Whether a request is to come after the turn of basis b on branch db: b is a model turn's and its agent has
  written no end on the branch at its turn (seon.reconcile/pull-end). See
  docs/render.md#the-debug-page-is-the-wire-request."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :seon.model-call/basis]] :boolean]}
  [db b]
  (boolean (and b (= :model (:origin b)) (nil? (reconcile/pull-end db (:agent b))))))

(defn debug-input
  "Request at of the debug timeline on branch db, as data `debug-turn` draws: a model call's eid (its turn,
  made again from its basis commit, seon.context/wire-turn), or :latest, the request the agent's next model
  turn would send now (its run going on: drawn at db's head as the turn would draw it, with the turn it
  writes first), else its newest turn. calls, the branch's model calls in order; raw?, the body's JSON
  shown whole; printed, the :printed an earlier answer holds (the print cache seon.context/wire-turn reuses),
  answered again as :printed. Which messages are new: those made by a record the request before it did not send (its
  recorded :seon.model-call/sent), so a part that changed is new by its ref. See
  docs/render.md#the-debug-page-is-the-wire-request."
  {:malli/schema [:=> [:cat :seon.host/db [:or pos-int? [:= :latest]] [:vector pos-int?] :boolean [:maybe :map]] [:maybe :map]]}
  [db at calls raw? printed]
  (let [pull #(d/pull db [:seon.model-call/basis :seon.model-call/sent :seon.model-call/tokens :seon.model-call/cache-hit-tokens
                          :seon.model-call/cache-miss-tokens :seon.model-call/error] %)
        last-e (peek calls)
        lb (some-> last-e pull :seon.model-call/basis)
        ;; the turn to come: on latest, while the newest model turn's agent has no end
        to-come? (and (= :latest at) (to-come? db lb))
        e (cond to-come? nil (= :latest at) last-e :else at)
        m (some-> e pull)]
    (when (or to-come? (:seon.model-call/basis m))
      (let [[tdb b] (if to-come?
                      ;; as seon.turn/act! draws it: the turn's number first, then the request at the head
                      (let [n (inc (:turn lb)) b (assoc lb :commit (d/commit-id db) :turn n)]
                        [db b])
                      (let [b (:seon.model-call/basis m)]
                        [(if (= (:commit b) (d/commit-id db)) db (d/commit-as-db (:store db) (:commit b))) b]))
            i (if to-come? (count calls) (.indexOf ^java.util.List calls e))
            prev-e (when (pos? i) (nth calls (dec i)))
            prev (set (remove #(= :hashes (first %)) (:seon.model-call/sent (some-> prev-e pull))))
            w (context/wire-turn tdb b printed)]
        {:at at :to-come? to-come? :turn (:turn b) :agent (:agent b) :origin (:origin b) :raw? raw?
         :body (:body w) :sources (:sources w) :printed (:printed w)
         ;; with no request before it, nothing is marked: every message is the first request's
         :new (mapv (fn [rs] (boolean (and prev-e (some (complement prev) rs)))) (:sources w))
         :usage (select-keys m [:seon.model-call/tokens :seon.model-call/cache-hit-tokens :seon.model-call/cache-miss-tokens])
         :error (:seon.model-call/error m)}))))

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

(defn task
  "The task its agent works (a component over ctx alone): its name as the heading, its plan accumulated over its needs
  (seon.task/pulled-accumulated), and under it one done line (a work or whole task's; the other kinds' instructions say what
  they are for). Not a map of keys: what the agent reads cold. For the person, beside it, the namespace and how the
  work stands (`standing`, from the task's outcome where the task holds one, else the agent's own end). The cites are
  their own parts (`citations`); which proofs are written is the status's. It reads no db. See
  docs/render.md#a-part-answers-data."
  {:seon.render/style (str panel-style " [&_h3]:text-xs [&_h3]:text-text-300")
   :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]] :int] :any]}
  [{c :seon.ctx/ctx} _aid]
  (when-let [t* (:task c)]
    (let [own (fn [x] (into {} (for [k (keys x) :when (not= :cites k)] [k (ratom/realize (get x k))])))
          t (assoc (own t*) :needs (into {} (for [[k n] (:needs t*)] [k (own n)])))
          id (:seon.task/id t)
          {:keys [plan]} (task/pulled-accumulated t)
          agent (into {} (for [k [:seon.agent/outcome :seon.agent/ended-turn :seon.agent/turn :seon.agent/max-turns :seon.agent/reason :seon.agent/status]
                               :let [v (ratom/realize (get c k))] :when (some? v)] [k v]))
          stands (when (or (:seon.agent/outcome agent) (:seon.task/outcome t)) (standing agent (select-keys t [:seon.task/outcome :seon.task/reason])))]
      [:section
       [:h2 "Your task"]
       [:p {:data-for "page"} (str "task · " (namespace (symbol id)) (when stands (str " · " (:text stands) (when (:detail stands) (str " · " (:detail stands))))))]
       [:p "You have one task, and your job is to finish it. Everything else here is to help you do that."]
       (when plan (markdown plan))
       ;; a work or whole task is done when its proofs pass; the other kinds (design, merge, test) say what they are for
       (if (#{:work :whole} (:seon.task/kind t))
         [:p "You're done when every proof under \"Your proofs\" passes on your branch. Then reply saying what you changed and how it's proved, and your branch is merged."]
         (when-let [ins (:seon.task/instructions t)] [:p ins]))
       [:p (str "Task: " id)]])))

(def ^:private cite-joins
  "Where a cite's joins hold an entity, and the identity attribute that marks it held whole there (an entity
  is held once in ctx, {:db/id e} at any other path: docs/ctx.md#the-pattern): each join path below a cite,
  the ones that hold an entity by the cite's own pattern first."
  [[[:section] :seon.doc/address] [[:var] :var/name] [[:var :form] :form/id] [[:schema] :schema/key] [[:ns] :ns/name]
   [[:task] :seon.task/id] [[:section :below :*] :seon.doc/address] [[:var :callers :*] :form/id] [[:var :callers :* :defines :*] :var/name]])

(defn- fill
  "Node x with each {:db/id e} held whole at another path of ctx c (held: e to that path) replaced by the node
  there, at any depth, each entity once: a cite drawn as the walk holds what it joined."
  {:malli/schema [:=> [:cat :map [:map-of :int [:vector :any]] :any] :any]}
  [c held x]
  (let [go (fn go [seen x]
             (cond (and (map? x) (= [:db/id] (keys x)) (held (:db/id x)) (not (seen (:db/id x))))
                   (go (conj seen (:db/id x)) (ratom/realize (get-in c (held (:db/id x)))))
                   (map? x) (into (if (sorted? x) (empty x) {}) (map (fn [[k v]] [k (go seen v)])) x)
                   (set? x) (into #{} (map #(go seen %)) x)
                   :else x))]
    (go #{} x)))

(declare page-cites)

(defn help-form
  "The form an agent runs to read more of x, a wiki address (a string), a namespace or a function (a symbol): the one place it is
  written, so it changes in one place when `clojure.repl/doc` takes these (jolt/forgiving-tools)."
  {:malli/schema [:=> [:cat [:or :string :symbol]] :string]}
  [x] (if (string? x) (str "(my.agent/help \"" x "\")") (str "(my.agent/help '" x ")")))

(defn- source-block
  "Source text src as the model and the page read code: a block the printer fences verbatim."
  {:malli/schema [:=> [:cat :any] :any]}
  [src] [:pre [:code src]])

(defn- names-line
  "A line naming every symbol of xs, `Called by: a, b, c`; nothing for none. Which names there are is the pull's (ctx's callers window)."
  {:malli/schema [:=> [:cat :string [:sequential :any]] :any]}
  [label xs]
  (when (seq xs) [:p (str label ": " (str/join ", " (map str xs)))]))

(defn call-in
  "One real call of sym in source src: the first form opening `(sym ` or `(name `, as written (`seon.context/form-at`), or nil
  when src has none or it is only the function's name and bare symbols (that is its arglist, which the source above says). What a
  test or a caller that reaches a function shows of how it is called."
  {:malli/schema [:=> [:cat [:maybe :string] :qualified-symbol] [:maybe :string]]}
  [src sym]
  (when src
    (let [starts (keep #(str/index-of src %) [(str "(" sym " ") (str "(" sym ")") (str "(" (name sym) " ") (str "(" (name sym) ")")])
          call (when (seq starts) (context/form-at src (reduce min starts)))]
      (when (and call (not (re-matches #"\([^\s()\"]+(\s+[A-Za-z][\w\-?!*.<>=/]*)*\)" call)) (not (re-find #"[a-z]{3,} [a-z]{3,} [a-z]{3,}" (str/replace call #"\"[^\"]*\"" "")))) call))))

(defn- section-hiccup
  "A wiki section as pulled (its :below joined) at depth: what it is in a sentence, its heading and address, its text at that
  depth (its stored lead at :summary, its lead and the headings below it at :doc, its text whole at :code), and how to read the rest."
  {:malli/schema [:=> [:cat :map :seon.ref/depth] :any]}
  [{:seon.doc/keys [address heading lead text] below :below} depth]
  [:section
   [:h3 (str heading " (" address ")")]
   [:p "A section of the docs your plan names."]
   (case depth
     :summary (when lead [:p lead])
     :doc (list (when lead (markdown lead))
                (when (seq below) (into [:ul] (for [b (sort-by :seon.doc/address (vals below))] [:li (str (:seon.doc/heading b) " (" (:seon.doc/address b) ")")]))))
     (when text (markdown text)))
   (when-not (= :code depth) [:p "Read the rest: " [:code (help-form address)]])])

(defn- var-hiccup
  "A cited function, drawn for the question the agent has of it. One it will change (the plan's task is in its namespace, or its
  proofs reach it): its source whole, which holds its doc and contract, and the tests that will judge the change. One it only
  calls or copies: its signature, its doc and one real call taken from a test or a caller. A library var (v nil) from the
  process's own metadata. Nothing of who calls it."
  {:malli/schema [:=> [:cat :qualified-symbol [:maybe :map] :boolean] :any]}
  [sym v changing?]
  (let [src (get-in v [:form :form/src])
        m (when-not v (some-> (find-ns (symbol (namespace sym))) ns-interns (get (symbol (name sym))) meta))
        forms (vals (:callers v))
        defs-of (fn [f] (for [d (or (vals (:defines f)) (:form/defines f)) :when (and (:var/name d) (not= sym (:var/name d)))] d))
        test? (fn [f] (some #(= :test (:var/kind %)) (defs-of f)))
        tests (sort-by str (distinct (for [f forms :when (test? f) d (defs-of f) :when (= :test (:var/kind d))] (:var/name d))))
        call (some #(call-in (:form/src %) sym) (concat (filter test? forms) (remove test? forms)))
        doc (or (:var/doc v) (:doc m))]
    [:section
     [:h3 (str sym)]
     (if changing?
       (list [:p (if (= :test (:var/kind v)) "A test your plan names." "A function your plan names, in the code you will change. Here is its source, and the tests that will judge your change.")]
             (if src (source-block src) (list (when m [:p (str (:arglists m))]) (when doc [:p doc])))
             (names-line "Tests that reach it" tests))
       (list [:p (if (= :test (:var/kind v)) "A test your plan names." "A function your plan names. You call it; here is how.")]
             (when src [:p [:code (str (reader/arglists src (symbol (namespace sym))))]])
             (when m [:p [:code (str (:arglists m))]])
             (when doc [:p doc])
             (when call [:p "A real call: " [:code call]])
             (when (and (:var/kind v) (= :test (:var/kind v)) src) (source-block src))))]))

(defn- key-hiccup
  "A cited attribute or setting by its schema entry. A setting: what it is for (its :description) and its value now. An
  attribute: what it is (its :description, else its type) and a real value taken from this task's own data where it holds one
  (lookups: lookup refs of the task and the functions it names), else the first the db has; for a ref, each attribute of the entity
  it refers to, since that is what the agent reads next. Nothing of which entities hold it."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-keyword :map [:sequential :any]] :any]}
  [db k sch lookups]
  (let [desc (:description (contract/properties k))
        setting? (str/starts-with? (str (namespace k)) "seon.config")]
    (if setting?
      [:section [:h3 (str k)] [:p (str "A setting your plan names." (when desc (str " " desc)))] [:p "Its value now: " [:code (print/text (config/value k))]]]
      (let [schema (get (d/schema db) k)
            ref? (= :db.type/ref (:db/valueType schema))
            typ (or (last (re-seq #":[^\s\]\}\)]+" (str/replace (str (:schema/form sch)) #"\{[^}]*\}" ""))) (:schema/form sch))
            datom (when schema
                    (or (some #(when-let [e (d/entity db %)] (first (d/datoms db :eavt (:db/id e) k))) lookups)
                        (first (d/datoms db :aevt k))))
            target (when (and datom ref?) (d/pull db '[*] (:v datom)))]
        [:section
         [:h3 (str k)]
         [:p (str "An attribute your plan names." (if desc (str " " desc) (str " It holds " typ ".")))]
         (when (and datom (not ref?)) [:p "A real value: " [:code (print/text (:v datom))]])
         (when target
           (list [:p "Each entity points to another with it, which holds:"]
                 (into [:ul] (for [[tk tv] (remove (comp #{:db/id} key) target)] [:li [:code (str tk " " (print/text tv))]]))))]))))

(defn cite-hiccup
  "A cite as pulled (and filled) with its joins, as one hiccup by what it cites: a wiki section (`section-hiccup`), a function
  (`var-hiccup`), a schema key or setting (`key-hiccup`), a namespace (its doc and how to read it), a task (its plan), or what
  resolves to nothing, said so. The page and the model read it alike; `my.agent/help` answers it on demand. See
  docs/references.md#an-entity-cites."
  {:malli/schema [:function [:=> [:cat :seon.host/db :map] :any] [:=> [:cat :seon.host/db :map :map] :any]]}
  ([db c] (cite-hiccup db c {}))
  ([db {:seon.cite/keys [address name depth] sec :section v :var tk :task sch :schema n :ns} {:keys [lookups changing?]}]
   (let [x (or address name)]
     (cond
       (:seon.doc/address sec) (section-hiccup sec depth)
       (:seon.task/id tk) [:section [:h3 (str (:seon.task/id tk))] [:p "Another task your plan names."]
                           (when-let [pl (:seon.task/plan tk)] (markdown pl))]
       (qualified-symbol? name) (if (and (nil? (:var/name v)) (nil? (some-> (find-ns (symbol (namespace name))) ns-interns (get (symbol (clojure.core/name name))))))
                                  [:section [:h3 (str name)] [:p (:unresolved ref/problem-messages)]]
                                  (var-hiccup name (when (:var/name v) v) (boolean changing?)))
       (:schema/key sch) (key-hiccup db name sch (or lookups []))
       (:ns/name n) [:section [:h3 (str name)] [:p (str "A namespace your plan names." (when (:ns/doc n) (str " " (:ns/doc n))))] [:p "Read it: " [:code (help-form name)]]]
       :else [:section [:h3 (str x)] [:p (str "Your plan names this. " (:unresolved ref/problem-messages))]]))))

(defn cited
  "One thing the agent's task or its needs cite (a component over ctx and the db), named by its target k: the cite at ctx path :at
  as ctx holds it, each entity it joined that ctx holds at another path filled from there (:held, e to that path, which the layout
  found), drawn by `cite-hiccup`. A part of its own, so a cite that changes is sent alone. See docs/references.md#an-entity-cites."
  {:seon.render/style panel-style
   :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db :seon.host/db] [:at [:vector :any]] [:held [:map-of :int [:vector :any]]] [:lookups {:optional true} [:sequential :any]] [:changing? {:optional true} :boolean]]] :int :string] :any]}
  [{c :seon.ctx/ctx db :seon.db/db at :at held :held lookups :lookups changing? :changing?} _aid _k]
  (when-let [x (get-in c at)]
    (cite-hiccup db (fill c held (ratom/realize x)) {:lookups (or lookups []) :changing? (boolean changing?)})))

(defn citations
  "The heading of what the task and its needs cite (a component over ctx): what it is and why it is here. The cites follow it, each a
  `cited` part of its own (`cite-parts`). Nothing when there is none. See docs/references.md#an-entity-cites."
  {:seon.render/style panel-style :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]] :int] :any]}
  [{c :seon.ctx/ctx} _aid]
  (when (seq (page-cites c))
    [:section
     [:h2 "What your plan mentions"]
     [:p "Your plan names each of these, so here's what they are. Use what helps; you don't need to read them all."]]))

(defn- cite-parts
  "The `cited` components of agent aid's page, one for each thing its task and its needs cite (`page-cites`). Each is told what
  the agent will do with it: a function in the task's own namespace or reached by its proofs is one it will change (its source and
  tests are drawn), any other one it calls (its signature and doc); and the lookup refs an attribute's real value is taken from
  (this task's own entity, and the functions the cites name)."
  {:malli/schema [:=> [:cat :map [:maybe :int]] [:vector :any]]}
  [c aid]
  (let [cs (page-cites c)
        task-ns (some-> (ratom/realize (get-in c [:task :seon.task/ns])) str)
        reach (into #{} (for [[_ v] (get-in c [:task :proved]) r (ratom/realize (get-in v [:form :form/refs]))] r))
        lookups (into [[:seon.task/id (ratom/realize (get-in c [:task :seon.task/id]))]]
                      (keep #(when (re-matches #"[^\s:/#]+/[^\s:#]+" %) [:var/name (symbol %)])) (map second cs))]
    (vec (for [[p k held] cs
               :let [sym (when (re-matches #"[^\s:/#]+/[^\s:#]+" k) (symbol k))]]
           [#'cited {:at p :held held :lookups lookups
                     :changing? (boolean (and sym (or (contains? reach sym) (= task-ns (namespace sym)))))} aid k]))))

(defn references
  "Where to look for more, beside the task (a component over ctx): the agent's own cites (what every agent cites, the config's
  :seon.config.agent/cites, and what it adds with my.agent/cite!), each as one line: what it is, what it answers (the section's
  stored lead) and the form that reads it. Not shown whole: an agent reads one when it needs it. Nothing when it has none."
  {:seon.render/style panel-style :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]] :int] :any]}
  [{c :seon.ctx/ctx} _aid]
  (let [cites (for [k (sort-by #(get-in c [:cites % :seon.cite/order]) (keys (:cites c)))] (ratom/realize (get-in c [:cites k])))]
    (when (seq cites)
      [:section
       [:h2 "If you need more"]
       [:p "You don't need these now. Here's where to look when you do."]
       (into [:ul]
             (for [{:seon.cite/keys [address name] sec :section} cites :let [x (or address name)]]
               [:li (or (:seon.doc/heading sec) (str x)) ": " (:seon.doc/lead sec) " " [:code (help-form x)]]))])))

(defn searched
  "One search the agent's context holds (a component over ctx and the db), drawn as one compact hiccup that the
  page shows and the model is sent as it is: the search fact at ctx path :at, its query searched on the branch's
  db by seon.context/search (exact words, meaning once its query has a vector, and the names it holds;
  functions, wiki sections and schema keys, no relevance cut), its :seon.search/k hits (else
  :seon.config.render/search's), each a line: its name (its first sentence added nothing a model used, at
  ~90 tokens a hit against ~10: docs/vector.md#search); the hits left out are one last line, how many and the call
  that answers them; a query with no vector yet says it was searched without meaning. A change of its query (a swap! on ctx)
  redraws this part alone, and the model is sent what changed of it
  (docs/render.md#the-page-is-sent-as-what-changed). What it finds for a request is measured in
  docs/vector.md#what-the-work-is-likely-to-need-as-an-eval."
  {:seon.render/style panel-style :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db :seon.host/db] [:at [:vector :any]]]] :int :string] :any]}
  [{c :seon.ctx/ctx db :seon.db/db at :at} _aid _k]
  (when-let [s (some-> (get-in c at) ratom/realize)]
    (let [q (:seon.search/query s)
          ;; the search fact's own vector, once the pipeline has embedded its query (seon.vector's due texts)
          e (pdb/eid db :seon.search/id (:seon.search/id s))
          qv (when (and e (vector/vector-of db e)) e)
          ;; its own count when it names one, else :seon.config.render/search's; the hits it leaves out are
          ;; said by search's last entry ({:kind :more :left n :more \"the call that answers them\"}), drawn as such
          hits (context/search db q (cond-> {:kinds #{:var :section :schema} :min 0.0} (:seon.search/k s) (assoc :k (:seon.search/k s))) qv)]
      ;; the task's own request is on the page already: the heading names it rather than repeating it
      [:section [:h3 (str (if (= q (get-in c [:task :seon.task/plan])) "search: this task's request" (str "search: " q))
                          (when-not qv " (by its words: its query has no vector yet)"))]
       (into [:ol] (for [{:keys [kind name left more]} hits]
                     (if (= :more kind)
                       [:li (str left " more: ") [:code more]]
                       [:li [:code (str name)]])))])))

(defn- held-whole
  "Where ctx c holds whole each entity the cites at paths ps joined, {e path}: the agent's task and its needs, the
  task's proofs (each held first, by the agent's and the task's patterns), a cite's own joins, then a section's below and a var's callers (cite-joins), the first
  path at which its identity attribute is present (an entity is held once: docs/ctx.md#the-pattern)."
  {:malli/schema [:=> [:cat :map [:sequential [:vector :any]]] [:map-of :int [:vector :any]]]}
  [c ps]
  (let [;; the paths below p that join [steps a] names (a :* step each member of the collection there), with
        ;; whether ctx holds the entity whole at each (its identity attribute a present) and its id
        join-paths (fn [p [steps a]]
                     (let [paths (reduce (fn [qs step] (if (= :* step) (for [q qs k (keys (get-in c q))] (conj q k)) (map #(conj % step) qs))) [p] steps)]
                       (for [q paths :let [id (get-in c (conj q :db/id))] :when id] [q (some? (get-in c (conj q a))) id])))
        found (concat (join-paths [] [[:task] :seon.task/id]) (join-paths [] [[:task :needs :*] :seon.task/id])
                      (join-paths [] [[:task :proved :*] :var/name]) (join-paths [] [[:task :proved :* :form] :form/id])
                      (join-paths [] [[:task :needs :* :proved :*] :var/name]) (join-paths [] [[:task :needs :* :proved :* :form] :form/id])
                      (for [p ps j cite-joins q (join-paths p j)] q))]
    (reduce (fn [m [q whole? id]] (if (and whole? (not (contains? m id))) (assoc m id q) m)) {} found)))

(defn- page-cites
  "The cites agent record c's page draws, read from ctx alone: its task's (:cites), then each need's (one
  level, a merge task's left out), then the agent's own, each target once at the strongest depth any asks,
  the task's proofs left out (the status shows them), in the order the cites were written. Each
  [path target held]: the cite's path, its target as text and where ctx holds each entity it joined whole
  (an entity is held once: docs/ctx.md#the-pattern)."
  {:malli/schema [:=> [:cat :map] [:vector [:tuple [:vector :any] :string [:map-of :int [:vector :any]]]]]}
  [c]
  (let [task (:task c)
        proofs (into (set (get task :seon.task/proofs)) (mapcat (fn [n] (get-in task [:needs n :seon.task/proofs]))) (keys (:needs task)))
        sorted (fn [p] (sort-by #(get-in c (conj p % :seon.cite/order)) (keys (get-in c p))))
        groups (concat (when task [[:task :cites]])
                       (for [n (sort-by #(str (get-in task [:needs % :seon.task/id])) (keys (:needs task)))
                             :when (not= :merge (get-in task [:needs n :seon.task/kind]))] [:task :needs n :cites])
                       [])
        all (for [g groups k (sorted g) :let [p (conj (vec g) k)]]
              {:path p :target (str (or (get-in c (conj p :seon.cite/address)) (get-in c (conj p :seon.cite/name))))
               :name (get-in c (conj p :seon.cite/name)) :rank (ref/strength (get-in c (conj p :seon.cite/depth)))})
        all (remove #(proofs (:name %)) all)
        by (group-by :target all)
        chosen (vec (for [t (distinct (map :target all))] (reduce (fn [a b] (if (> (:rank b) (:rank a)) b a)) (by t))))
        held (held-whole c (map :path all))]
    (vec (for [{:keys [path target]} chosen] [path target held]))))

(def ^:private reason-words
  "What each of the done rule's reasons (seon.reconcile/done-explain) says to a person reading the page (docs/agent.md#what-you-read: `:refused`)."
  {:proof-red "proof red" :proof-unknown "proof has no verdict" :regression "green on main, red here"
   :stray-red "red test that is no proof" :contract-gap "functions without a contract" :unmerged "unmerged entries"
   :open-problem "problems the work caused" :no-proofs "no proof registered" :no-design "plan unchanged since the run began"
   :behind "judged before main moved"})

(def ^:private state-words
  "What a proof's verdict says in the status table."
  {:pass "green" :fail "failed" :error "failed" :undefined "not written yet" :unknown "no verdict yet" :not-run "not run" :deferred "left to root's gate"})

(defn- as-text
  "A value of the status as one line of source: marked code as its text, a thrown error as its message, any other value by the printer."
  {:malli/schema [:=> [:cat :any] :string]}
  [x]
  (cond (and (map? x) (string? (:seon.print/code x))) (:seon.print/code x)
        (instance? Throwable x) (str "threw " (ex-message x))
        (and (map? x) (:via x)) (str "threw " (:message (first (:via x))))
        (string? x) x
        :else (let [t (str/replace (print/text x) #"#object\[java\.util\.regex\.Pattern 0x[0-9a-f]+\]" "a regex")]
                ;; a thrown error is the failure's got (clojure.test/is reports it): say what it threw, not its trace
                (if-let [m (when (str/starts-with? t "#error") (second (re-find #":message \"([^\"]*)\"" t)))] (str "threw " m) t))))

(defn- form-hiccup
  "A form to run, as code: inline when it is one line, a block when it spans several. A `do` of forms is the forms, one after
  another at the top level, as an agent types them at its REPL (a `do` with `in-ns` is a habit that records nothing)."
  {:malli/schema [:=> [:cat :any] :any]}
  [form]
  (let [one #(if (string? %) % (print/text (cond-> % (instance? clojure.lang.IObj %) (vary-meta assoc :seon.print/full true))))
        t (if (and (seq? form) (= 'do (first form))) (str/join "\n" (map one (rest form))) (one form))]
    (if (str/includes? t "\n") [:pre [:code t]] [:code t])))

(defn status-hiccup
  "How the work stands (seon.context/status's data s), written to the agent at the moment it reads it. Each proof answers the
  question its state asks: not written, `write it here, like this`; red, what it asserted, what it got and what to try; every proof
  green and something blocks merging, exactly what and the form that clears it; all green and nothing blocks, `you're done`.
  Main's side only when something conflicts: both sides and the form that settles each. No table beside the same words: a list of
  the proofs' states only when there are several. See docs/render.md#how-the-work-stands."
  {:malli/schema [:=> [:cat :map] :any]}
  [{:keys [proofs refused test-run unmerged without-a-contract caused attention merge reasons]}]
  (let [state #(:status %)
        unwritten (filter #(= :undefined (state %)) proofs)
        red (filter #(#{:fail :error} (state %)) proofs)
        unknown (filter #(#{:unknown :not-run :deferred} (state %)) proofs)
        green? (and (seq proofs) (every? #(= :pass (state %)) proofs))
        blocks (cond-> []
                 (seq refused) (into (for [r refused :when (not (and (= :proof-unknown (:reason r)) (some #(= (:test r) (:test %)) unwritten)))]
                                       [(get reason-words (:reason r) (kname (:reason r))) (or (:test r) (:vars r) (:keys r) (:problems r)) (:do r)]))
                 (seq without-a-contract) (conj ["functions without a contract" without-a-contract nil])
                 (seq caused) (conj ["problems the work caused" caused nil])
                 (seq attention) (into (for [a attention] [a nil nil])))
        scaffold (some :do refused)]
    [:section
     [:h2 "Your proofs"]
     [:p "These tests decide when you're done."]
     (when test-run [:p "The tests could not run: " (str/join "; " (:failed test-run))])
     (when (next proofs)
       (into [:ul] (for [{:keys [test status cost]} proofs] [:li [:code (str test)] ": " (get state-words status (kname status)) (when cost (str " · " (print/cost-text cost)))])))
     ;; not written: where it goes and a test to copy
     (for [{:keys [test shape]} unwritten :let [{:keys [ns requires helpers example]} shape]]
       [:div
        [:p "Write " [:code (str test)] "." (when ns (list " It goes in " [:code (str ns)] (if requires ", which requires:" ".")))]
        (when requires [:pre [:code requires]])
        (when (seq helpers) [:p "Helpers a test there uses: " (str/join ", " (map #(str "`" (name %) "`") helpers)) ". Every helper there: " [:code (str "(dir " ns ")")]])
        (when example [:p "Write it like this one:"])
        (when example [:pre [:code example]])
        (when scaffold [:div [:p "Start with this, then replace the placeholder assertion with what your plan says should hold:"] (form-hiccup scaffold)])])
     ;; red: which, what it asserted, what it got, what to try
     (for [{:keys [test failures cost]} red]
       [:div
        [:p [:code (str test)] " fails." (when cost (str " (" (print/cost-text cost) ")"))]
        (for [{a :assert as :asserts g :got m :message} failures]
          [:p "It asserts " [:code (str/join ", " (map #(as-text %) (or as [a])))] " and got " [:code (as-text g)] (when m (str " (" m ")")) "."])
        [:p "This is the result of your last run: it changes when you change what the test reaches. Evaluate the assertion's own expression at your REPL to see the difference, change the code, then " [:code "(prove)"] "."]])
     (when (seq unknown) [:p "Not run yet: " (str/join ", " (map #(str "`" (:test %) "`") unknown)) ". " [:code "(prove)"] " runs them."])
     ;; merge: nothing until something conflicts; then both sides and the form that settles each
     (when (seq unmerged)
       [:div
        [:p "Main changed these too, so they are unmerged. Settle each: keep yours, or take main's."]
        (for [{:keys [key ours theirs]} unmerged]
          [:div [:p [:code (str key)] ". Yours:"] [:pre [:code (as-text ours)]] [:p "Main's:"] [:pre [:code (as-text theirs)]]
           [:p "Keep yours: " [:code (str "(my.branch/resolve! '" key ")")] ". Take main's: " [:code (str "(my.branch/resolve! '" key " :theirs)")] "."]])])
     (cond
       (and green? (empty? blocks) (empty? unmerged))
       [:p "You're done: every proof passes and nothing blocks merging. Reply saying what you changed and how it's proved."]
       (and green? (seq blocks))
       [:div [:p "Every proof passes, but your branch can't merge yet:"]
        (into [:ul] (for [[what x do] blocks]
                      [:li what (when (some? x) (str ": " (if (coll? x) (str/join ", " (map #(as-text (if (map? %) (or (:key %) (:symbol %) %) %)) x)) x)))
                       (when do [:span ". To clear it: " (form-hiccup do)])]))]
       (seq blocks)
       [:div [:p "Also blocking merging:"]
        (into [:ul] (for [[what x do] blocks]
                      [:li what (when (some? x) (str ": " (if (coll? x) (str/join ", " (map #(as-text (if (map? %) (or (:key %) (:symbol %) %) %)) x)) x)))
                       (when do [:span ". To clear it: " (form-hiccup do)])]))])
     (when (seq (:conflicts merge)) [:p (str (count (:conflicts merge)) " things conflict with main; they are listed above.")])]))

(defn status
  "How the agent's work on its task stands (a component over ctx and the db): seon.context/status of its branch db, drawn
  as one hiccup (`status-hiccup`) for the page and the model alike; the data it was drawn from (:seon.context/status) is its
  answer, what the turn decides done from (seon.render/placed-data). It reads main's commit from ctx only when the task
  merges itself (the merge view), so a move of main redraws it only then."
  {:seon.render/style panel-style :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db :seon.host/db]]] :int] :any]}
  [{c :seon.ctx/ctx db :seon.db/db} aid]
  (let [main (when (task/merges? (:seon.task/kind (:task c))) (ratom/realize (:seon.agent/main-commit c)))
        s (context/status db c {:main main})]
    (with-meta (status-hiccup s) {:seon.render/answer (with-meta s {:seon.render/schema :seon.context/status})})))

(declare standing)

(defn turn-line
  "The agent's turn as one line, the same words on the page and in the model's request: `Turn 12 of 40. You're in seon.program. 13.5k
  tokens sent so far.`: the model calls it has made of its cap, the namespace its REPL is in (seon.context/current-ns) and the tokens
  its requests sent in all (the provider's counts, summed). Parts that are not known are left out. See docs/render.md#the-agents-turn."
  {:malli/schema [:function [:=> [:cat [:maybe :int] [:maybe :int] [:maybe :symbol] [:maybe nat-int?]] :string]
                  [:=> [:cat [:maybe :int] [:maybe :int] [:maybe :symbol] [:maybe nat-int?] [:maybe :string]] :string]]}
  ([n cap in-ns sent] (turn-line n cap in-ns sent nil))
  ([n cap in-ns sent session]
  (str "Turn " n (when cap (str " of " cap)) "."
       (when (and cap n (<= 0 (- cap n) 5)) (if (zero? (- cap n)) " This is your last turn." (str " " (- cap n) (if (= 1 (- cap n)) " turn" " turns") " left.")))
       (when in-ns (str " You're in " in-ns "."))
       (when session (str " " session "."))
       (when (pos? (or sent 0)) (str " " (str/replace (str/replace (print/tokens-text sent) "~" "") " tok" " tokens") " sent so far.")))))

(defn turn
  "The agent's turn (a component over ctx and the db): its turn line as the part's heading, which is all the model is sent
  of it, and under it, when its last reply was cut at the model's token limit (the reply of the model call that answered
  last: this turn's, else the turn before's, which the request being made follows), what to do about that. See
  docs/render.md#the-agents-turn."
  {:seon.render/style tile-style
   :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db :seon.host/db]]] :int] :any]}
  [{c :seon.ctx/ctx db :seon.db/db} aid]
  (let [sent (d/q '[:find (sum ?k) . :with ?m :in $ ?a :where [?m :seon.model-call/agent ?a] [?m :seon.model-call/tokens ?k]] db aid)
        ;; the model and its thinking, as the turn's own resolver reads them (task, model, provider, config)
        p (config/provider-at db db (ratom/realize (get-in c [:task :seon.task/id])) (ratom/realize (:seon.agent/model c)))
        session (str (if (= :scripted (:kind p)) "A scripted model, not a real one" (or (:model p) "The provider's default model"))
                     (case (:thinking p) :enabled ", thinking on" :disabled ", thinking off" ""))
        n (or (ratom/realize (:seon.agent/turn c)) 0)
        k (ratom/realize (:seon.agent/key c))
        call-of (fn [i] (when k (pdb/eid db :seon.model-call/id (str k "/" i))))
        ;; its last reply: this turn's call once it answered (:seon.model-call/ms is written with its reply), else the turn before's
        call (let [now (call-of n)] (if (and now (seq (d/datoms db :eavt now :seon.model-call/ms))) now (call-of (dec n))))]
    [:section
     [:h2 (turn-line n (ratom/realize (:seon.agent/max-turns c)) (context/current-ns c) sent session)]
     (when (and call (seq (d/datoms db :eavt call :seon.model-call/truncated)))
       [:p "Your last reply was cut at the model's token limit: what followed the cut was lost. Answer with less: one change at a time."])]))

(defn agent-state
  "The agent's state for the person (a component over ctx and the db), nothing of it for the model: how its work stands
  (`standing`, from the task's outcome where it holds one), its task, the form it is running, its proofs green, the tokens its
  requests sent (cached and not) and the time they took."
  {:seon.render/style tile-style
   :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db :seon.host/db]]] :int] :any]}
  [{c :seon.ctx/ctx db :seon.db/db} aid]
  (let [sum (fn [a] (d/q '[:find (sum ?k) . :with ?m :in $ ?a ?at :where [?m :seon.model-call/agent ?a] [?m ?at ?k]] db aid a))
        sent (sum :seon.model-call/tokens)
        cached (sum :seon.model-call/cache-hit-tokens)
        ms (sum :seon.model-call/ms)
        task (:task c)
        t (into {} (for [k [:seon.task/id :seon.task/outcome :seon.task/reason] :let [v (ratom/realize (get task k))] :when (some? v)] [k v]))
        agent (into {} (for [k [:seon.agent/outcome :seon.agent/ended-turn :seon.agent/turn :seon.agent/max-turns :seon.agent/reason :seon.agent/status]
                             :let [v (ratom/realize (get c k))] :when (some? v)] [k v]))
        stands (standing agent t)
        verdicts (for [[_ ps] (concat [[nil (:proved task)]] (map (fn [[_ nd]] [nil (:proved nd)]) (:needs task))) [_ v] ps] (ratom/realize (:seon.test/status v)))
        running (d/q '[:find ?r . :in $ ?a :where [?p :seon.process/agent ?a] [?p :seon.process/running ?r]] db aid)]
    [:div {:data-for "page"}
     [:p (str "● " (:text stands) (when (:detail stands) (str " · " (:detail stands))) (when (:reason stands) (str " · " (:reason stands))))]
     (when-let [id (:seon.task/id t)] [:p "task " (task-title id)])
     (when running [:p "running " [:code running]])
     (when (seq verdicts) [:p (str (count (filter #{:green} verdicts)) " of " (count verdicts) " proofs green")])
     (when sent [:p (str (print/tokens-text sent) " sent in all" (when cached (str ", " (print/tokens-text cached) " of them cached, " (print/tokens-text (- sent cached)) " not")))])
     (when (pos? (or ms 0)) [:p (str (ms-str ms) " in the model")])]))

(defn own-problems
  "The open problems of the agent (a component over its branch db, seon.problems/own: its newest run's,
  its task's failure and the errors raised in its task), by the report's render, the one root's tile
  draws; nothing when none is open."
  {:seon.render/style tile-style :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.db/db :seon.host/db]]] :int] :any]}
  [{db :seon.db/db} aid]
  (let [r (problems/own db aid)]
    ;; a part: the report's own render draws it, the model is sent the report
    (when (seq (:classes r)) (with-meta r {:seon.render/schema :seon.problems/report}))))

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
  {:seon.render/style "seon-stack-2" :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]]] :any]}
  [{c :seon.ctx/ctx}]
  [:div
   (for [k (added-keys c)] [#'ctx-key {} k])])

(defn eval-item
  "An eval of the conversation (a component over ctx), as a chat reads it (owner: \"I
  want the chat to look like a nice chat screen with just prose unless I dig into it\"): one quiet
  line, whether it came out well (✓) or threw (✗), the prose the agent wrote beside it (else its code,
  cut by the page's width only), how long it ran and the tokens it puts in the model's context (its
  messages as seon.context/eval-messages makes them, by the one sizer); opened, its code and what
  came of it, each whole, once, in place (drawn inside the closed details once a revision). Opened (:seon.eval/opened: a reader opened it, a fact the
  agent takes before it draws its page) it is drawn open. Root's evals (a ctx with no agent key) post
  nothing. Its <details> carries its handle's anchor (eval-<id>): a handle printed anywhere links here.
  See docs/render.md#the-page."
  {:seon.render/style "[&>details>summary]:flex [&>details>summary]:min-w-0 [&>details>summary]:items-baseline [&>details>summary]:gap-x-2" :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db :seon.host/db]]] :int] :any]}
  [{c :seon.ctx/ctx db :seon.db/db step? :step} eid]
  (let [e (ratom/realize (get-in c [:evals eid]))
        opened? (:seon.eval/opened e)
        k (:seon.agent/key c)
        {:keys [ex value ms handle out warnings] errout :err} (:seon.eval/response e)
        v (ratom/realize value)
        ;; what its forms printed (*out*, *err*: seon.repl/response keeps each as seon.print/code), drawn before its value as a REPL shows it
        printed (into [] (keep (fn [[label k s]] (when-let [s (ratom/realize s)] [label k s]))) [["out" :out out] ["err" :err errout]
                                                                                                         ;; what its definitions warn (seon.repl's :warnings), in the words the model read
                                                                                                         ["note" :warnings (not-empty (str/join " " (keep :seon.repl/fix (ratom/realize warnings))))]])
        ;; an error answered as a value (a function that returns its error) is a failure too
        err (or ex (when (contract/error? v) v))
        tokens (context/messages-tokens (context/eval-messages {:db db} e))]
    [:li
     ;; a step of a turn (opts :step) is drawn open: its turn's one fold opens them all, never a second click
     [:details (cond-> {:class "seon-eval" :id (str "eval-" (:seon.eval/id e))} (or opened? step?) (assoc :open true))
      ;; opening it is a fact on main (seon.root/opened!), which the agent takes before it draws again
      (into (if k [:summary {:seon/call 'seon.root/opened! :seon/agent (:seon.agent/id c) :seon/args {:key k :eval (:seon.eval/id e)}}] [:summary])
            (remove nil?)
            [(when err [:span {:class "shrink-0 text-error"} "⚠"])
             (if (str/blank? (:seon.eval/intent e))
               [:code {:class "seon-form min-w-0 flex-1 truncate"} (first (str/split-lines (or (:seon.eval/code e) "")))]
               ;; the agent's prose is Markdown, rendered (owner, 2026-10-29); the model reads it as written
               [:div {:class "seon-prose seon-md-inline min-w-0 flex-1"} (markdown (:seon.eval/intent e))])
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
          (emit/marked-hiccup :seon.print/code (or (:seon.eval/code e) ""))
          [:code {:class "seon-handle shrink-0"} (str handle)]]
         ;; each by the one render under the value's budget; a cut reads the rest through its key of the handle
         (for [[label k s] printed]
           [:div {:class "seon-row"} [:span {:class (if (#{:err :warnings} k) "text-warning" "muted")} label]
            (render/html {:budget (config/value :seon.config.render/value-budget) :handle (when handle (list k handle))} s)])
         (if err
           [:div {:class "text-error"} "✗ " (:seon.error/message err)
            (render/html {:budget (config/value :seon.config.render/value-budget) :handle handle} (dissoc err :seon.error/message))]
           ;; a nil beside output is the output's whole answer (clojure.repl/source prints and answers nil)
           (when (and (contains? (:seon.eval/response e) :value) (not (and (nil? v) (seq printed))))
             [:div {:class "seon-row"} [:span {:class "muted"} "→"]
              (render/html {:budget (config/value :seon.config.render/value-budget) :handle handle} v)]))])]]))

(defn proved
  "A prove's run as its eval's tile draws it (the declared html render of :seon.test/proved, from
  the eval's value): its one line (the schema's model render, my.agent/prove-summary), then each proof's
  status, each red test's first failing assertion by the one printer, and each test whose child died."
  {:malli/schema [:=> [:cat :seon.render/opts :map] :any]}
  [_ r]
  (let [;; its reports only when it still holds them: an agent's select-keys of a prove keeps the schema
        run (when (:reports r) r)
        failures (some-> run proofs/failures)
        died (into (sorted-set-by #(compare (str %1) (str %2))) (some-> run proofs/died))]
    [:section [:h2 "prove"]
     [:p (str (:value (render/model (with-meta r {:seon.render/schema :seon.test/proved}))))]
     (when (seq (:proofs r))
       [:ul (for [[p s] (sort-by (comp str key) (:proofs r))] [:li [:code (str p)] " " (name s)])])
     (for [[t fs] (sort-by (comp str key) failures) :when (not (died t))]
       [:div [:p [:code (str t)]] (render/html {} (first fs))])
     (when (seq died) [:p "died: " (for [t died] [:code (str t) " "])])]))

(defn- id-set
  "The keys of join m (a member map of ctx, read through its view) as a set: one read of its key set, its
  members not each made a view as a walk of its entries makes them (seon.ratom)."
  {:malli/schema [:=> [:cat [:maybe :map]] [:set :any]]}
  [m] (cond (nil? m) #{} (instance? java.util.Map m) (set (.keySet ^java.util.Map m)) :else (set (keys m))))

(defn- conversation
  "The conversation of ctx c's agent: its messages, its model calls and its evals, in the order of their turn numbers, the
  one order of `seon.context/records` (docs/turn.md#the-turn-is-a-number): per turn its messages, then its model call (the
  reply and the evals it ran, folded into one row: `turn-evals`) and the evals of other hands (an outside eval, a session's) of
  that turn by their issue number. A context read (a call of origin :outside) is no
  conversation. See docs/render.md#the-page."
  {:malli/schema [:=> [:cat :map :seon.host/db] [:sequential :int]]}
  [c db]
  (let [num (fn [x] (or x 0))
        es (vec (:evals c))
        ;; an eval's issue number, as seon.context/records has it: its id's trailing digits (e9 before e10)
        issued (map (fn [[_ v]] (or (some-> (re-find #"\d+$" (:seon.eval/id v)) parse-long) 0)) es)
        evals (map (fn [[e v] n] [[(num (:seon.eval/turn v)) 2 n] e]) es issued)
        msgs (for [[e v] (:messages c)] [[(num (:seon.message/turn v)) 0 (or (:tx (first (d/datoms db :eavt e :seon.message/agent))) 0)] e])
        calls (for [[e v] (:calls c) :when (= :model (:seon.model-call/origin v))
                    :let [n (num (:seon.model-call/n v))]]
                ;; a reply heads its turn's evals, as seon.context/records ranks it
                [[n 1 0] e])]
    (map second (sort-by first (concat evals msgs calls)))))

(defn chat-turn
  "A model turn as the chat shows it (a component over ctx and the db, given its evals), a message on the left as a
  person reads a chat (owner: \"Does that look like a friendly chat window that my mom could
  use?\"): its reply's prose, then its evals folded into one quiet line (how many steps, how long they
  ran, any that failed), opened to one line each (`eval-item`); its thinking folded; a small footer
  with its turn and the tokens its request sent, the provider's count on its model call
  (:seon.model-call/tokens: the whole context at that turn). See docs/render.md#the-page."
  {:seon.render/style "max-w-[85%] self-start rounded-2xl rounded-tl-sm bg-base-900 px-4 py-2.5 text-text-100" :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db :seon.host/db]]] :int :int [:sequential :int]] :any]}
  [{c :seon.ctx/ctx db :seon.db/db} _aid eid evals]
  (let [t (get-in c [:calls eid])
        [reply reasoning n] (map #(ratom/realize (get t %)) [:seon.model-call/reply :seon.model-call/reasoning :seon.model-call/n])
        recs (map #(some-> (get-in c [:evals % :seon.eval/response]) ratom/realize) evals)
        failed (count (filter #(or (:ex %) (contract/error? (ratom/realize (:value %)))) recs))
        ms (reduce + 0 (keep :ms recs))
        tokens (d/q '[:find ?k . :in $ ?m :where [?m :seon.model-call/tokens ?k]] db eid)]
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
       [:p {:class "seon-note"} (str/join " · " (remove nil? [(when n (str "turn " n)) (when tokens (str (print/tokens-text tokens) " of context"))]))]])))

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
     [:p {:class "seon-note"} (str from)]
     (markdown (str body))]))

(defn chat-start
  "Where the work began, the chat's line after what was asked (a component over ctx and the db): its
  turn budget, the agent's own (:seon.agent/max-turns); before its first turn and with no budget of its own, nothing. The agent's
  loop eval (seon.turn/work!) is not drawn: this line and `chat-end` say what
  it did. See docs/render.md#the-page."
  {:seon.render/style "self-center text-xs text-text-500" :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db :seon.host/db]]] :int] :any]}
  [{c :seon.ctx/ctx} _aid]
  (when-let [m (ratom/realize (:seon.agent/max-turns c))]
    [:p (str "started working · a budget of " m " turns")]))

(defn chat-end
  "How the work ended, the chat's last card (a component over ctx and the db): the end its agent wrote (:seon.agent/outcome and
  the rest, while the agent is still at the turn it ended on: a wake that took another turn has no end yet), the turns it
  took and the tokens it spent, done or why not; while it works, how the agent is. See docs/render.md#the-page."
  {:seon.render/style "self-center rounded-xl border border-base-800 px-4 py-2 text-sm" :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db :seon.host/db]]] :int] :any]}
  [{c :seon.ctx/ctx db :seon.db/db} aid]
  (let [outcome (ratom/realize (:seon.agent/outcome c))
        ;; an end is its agent's while the agent is at the turn it ended on
        end? (and outcome (= (ratom/realize (:seon.agent/ended-turn c)) (or (ratom/realize (:seon.agent/turn c)) 0)))]
    (if end?
      (let [turns (ratom/realize (:seon.agent/ended-turn c))
            tokens (d/q '[:find (sum ?k) . :with ?m :in $ ?a :where [?m :seon.model-call/agent ?a] [?m :seon.model-call/tokens ?k]] db aid)
            reason (ratom/realize (:seon.agent/reason c))]
        [:p {:class (if (= :green outcome) "text-success" "text-text-100")}
         (str (if (= :green outcome) "✓ Done" (str "Ended: " (kname outcome)))
              (when (pos? turns) (str " in " turns (if (= 1 turns) " turn" " turns")))
              (when tokens (str " · " (print/tokens-text tokens)))
              (when (and reason (not= :green outcome)) (str " · " reason)))])
      (when-let [status (ratom/realize (:seon.agent/status c))]
        [:p {:class "muted"} (case status :busy "working…" :idle "waiting for you" (kname status))]))))

(defn examples
  "The example sessions its task names (a component over ctx and the db), or nothing when it names none. It is placed
  on every page, for the render cache runs a component once and again only when what it reads changes, so it costs
  nothing on an idle page; placing it only when there are examples would cost 3 contracted calls on every list draw,
  because the layout builds the whole page whatever list is drawn. It draws, under a heading naming each session, its evals as one line each in the order the agent's own
  conversation orders them (`conversation`), drawn by `evaluation` as the agent's own are. The model
  reads the same sessions as messages (seon.context/example-messages), so this is beside its page, not in it.
  See docs/tasks.md#a-task-names-example-sessions and docs/render.md#the-page."
  {:seon.render/style panel-style :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db :seon.host/db]]] :int] :any]}
  [{c :seon.ctx/ctx db :seon.db/db} _aid]
  (when-let [xs (seq (get-in c [:task :examples]))]
    [:section
     (for [[_ x] (sort-by key xs) :let [x (ratom/realize x)]]
       [:details [:summary (str "example session " (:seon.agent/key x))]
        [:ol (for [e (conversation x db)]
               [:li (render/html {:schema :seon.db/eval-fact :as :inline} (ratom/realize (get-in x [:evals e])))])]])]))

(declare task-list conversation-list tiles)

(defn render-ctx
  "The render of an agent's ctx, the one layout the model and the person read (the declared html
  render of :seon.render/render-ctx, which an agent overrides on its branch like any render): its ctx,
  read through the view, as components placed in the render's three lists, each keyed by what it
  shows (an eid, a var, a key). The task's list (p-task, the parts the model is sent: seon.context/page-lists):
  the newest run's task, each var its plan cites, what its newest turn observed,
  the run once it took a turn, then each key the agent added to its ctx. The conversation (c-rows, the chat a person reads): the run's turns as
  their replies (chat-turn), its evals as lines that open (eval-item) and its messages as bubbles
  (chat-message), in the order they were written (eids are issued in that order), then how the run
  stands (chat-end). The tiles (p-tiles): each eval whose value has a render of its own, newest first. Each
  list is a component of its own (`task-list`, `conversation-list`, `tiles`: a list, :seon.render/list), and
  the layout reads only the agent's id, so a change of one list's members reruns that list alone: an eval
  added never reruns the task's. See docs/render.md#the-page and docs/render.md#a-layout-places-its-lists."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]]] :any]}
  [{c :seon.ctx/ctx}]
  (let [aid (:db/id c)]
    [:div {:id "render-ctx"} [#'task-list {} aid] [#'conversation-list {} aid] [#'tiles {} aid]]))

(defn task-list
  "The task's list of an agent's page (p-task, the parts its model is sent: seon.context/page-lists), agent aid's, in the order
  they are placed here: the task, its proofs, what it cites, each search, the references its agent cites, its added keys, and
  last its own problems, its turn and its state. See docs/render.md#the-page."
  {:seon.render/list "p-task" :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]] [:maybe :int]] :any]}
  [{c :seon.ctx/ctx} aid]
  [:div {:id "p-task"}
   (when (:task c)
     (list [#'task {:at [:task]} aid] [#'status {:at [:task :proved]} aid] [#'citations {:at [:task :cites]} aid]
           (cite-parts c aid)
           ;; each search its task and the agent hold, a part each, drawn on the branch's db
           (for [[p k] (concat (for [e (keys (get-in c [:task :searches]))] [[:task :searches e] (str "task-" e)])
                               (for [e (keys (get c :searches))] [[:searches e] (str "agent-" e)]))]
             [#'searched {:at p} aid k])))
   (when aid (list [#'references {:at [:cites]} aid]))
   (when (seq (added-keys c)) [#'ctx-keys {:at [:db/id]}])
   (when aid (list [#'own-problems {:at [:calls]} aid] [#'turn {:at [:calls]} aid] [#'agent-state {:at [:calls]} aid]))])

(defn conversation-turn
  "Turn n of agent aid as the chat shows it: the messages it took, its model call with the evals it ran, and the evals of
  other hands, found by their turn number in the index, so a record of another turn touches nothing this read and an
  earlier turn never runs again. Each record says which agent's it is, for a store that holds several. See
  docs/render.md#the-page."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db :seon.host/db]]] :int :int] :any]}
  [{c :seon.ctx/ctx db :seon.db/db} aid n]
  (let [mine (fn [turn-attr agent-attr] (vec (for [d (d/datoms db :avet turn-attr n) :when (seq (d/datoms db :eavt (:e d) agent-attr aid))] (:e d))))
        msgs (mine :seon.message/turn :seon.message/agent)
        issue (fn [e] (or (some-> (re-find #"\d+$" (:v (first (d/datoms db :eavt e :seon.eval/id)))) parse-long) 0))
        evs (sort-by issue (mine :seon.eval/turn :seon.eval/agent))
        model? (fn [e] (seq (d/datoms db :eavt e :seon.eval/call-id)))
        call (when-let [k (ratom/realize (:seon.agent/key c))] (seon.db/eid db :seon.model-call/id (str k "/" n)))
        others (if call (remove model? evs) evs)
        loop? (fn [e] (context/loop-eval? (:seon.agent/id c) (get-in c [:evals e])))]
    (when (or (seq msgs) call (seq evs))
      (into [:div {:class "seon-stack"}]
            (concat (for [m msgs] [#'chat-message {} aid m])
                    (when call [[#'chat-turn {} aid call (filterv model? evs)]])
                    (for [e others :when (not (loop? e))] [#'eval-item {} e]))))))

(defn conversation-list
  "The conversation of an agent's page (c-rows, the chat a person reads), agent aid's: what was asked, a component for each
  of its turn numbers (`conversation-turn`), then how it stands (chat-end) (`render-ctx`). It reads the agent's turn and
  nothing of its records, so a new eval runs its turn alone. See docs/render.md#the-page."
  {:seon.render/list "c-rows" :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]] [:maybe :int]] :any]}
  [{c :seon.ctx/ctx} aid]
  [:ol {:id "c-rows"}
   (when (:task c) [#'chat-ask {} aid])
   (when aid [#'chat-start {} aid])
   (when aid (for [n (range 0 (inc (or (ratom/realize (:seon.agent/turn c)) 0)))] [#'conversation-turn {} aid n]))
   (when aid [#'chat-end {} aid])])

(defn eval-tile
  "An eval whose value has a render of its own (a component over ctx): that value, by it (its own,
  or the schema its record names: seon.repl/record-details)."
  {:seon.render/style tile-style :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]] :int] :any]}
  [{c :seon.ctx/ctx} eid]
  (let [k (ratom/realize (get-in c [:evals eid :seon.eval/schema]))]
    [:section
     (render/html (if k {:schema k} {}) (ratom/realize (get-in c [:evals eid :seon.eval/response :value])))]))

(defn turn-tiles
  "The tiles of turn n of agent aid: each of its evals whose value has a render of its own, newest first, found by the turn's
  number, so another turn's eval reads nothing this read, and decided by the value as it is drawn, so a render defined later
  counts. See docs/render.md#the-page."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db :seon.host/db]]] :int :int] :any]}
  [{c :seon.ctx/ctx db :seon.db/db} aid n]
  (let [tiled (for [d (reverse (d/datoms db :avet :seon.eval/turn n))
                    :when (seq (d/datoms db :eavt (:e d) :seon.eval/agent aid))
                    :let [v (ratom/realize (get-in c [:evals (:e d) :seon.eval/response :value]))
                          k (ratom/realize (get-in c [:evals (:e d) :seon.eval/schema]))]
                    :when (and (some? v) (render/render-of v k))]
                [#'eval-tile {} (:e d)])]
    (when (seq tiled) (into [:div] tiled))))

(defn tiles
  "The tiles of an agent's page (p-tiles), agent aid's: its task's example sessions, then each turn's, newest turn first
  (`turn-tiles`, `render-ctx`). See docs/render.md#the-page."
  {:seon.render/list "p-tiles" :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]] [:maybe :int]] :any]}
  [{c :seon.ctx/ctx} aid]
  [:aside {:id "p-tiles"}
   ;; placed on every page: the render cache runs a component once and again only when what it reads changes, so a page whose task names no examples pays nothing
   [#'examples {} aid]
   (when aid (for [n (range (or (ratom/realize (:seon.agent/turn c)) 0) -1 -1)] [#'turn-tiles {} aid n]))])

(def ^:private task-stands
  "How a task's :seon.task/outcome reads to a person: [words tone]. :parked and :told are woken by a message (seon.reconcile/park-outcomes);
  :cap is not: it waits for its agent's turns to be raised and the task released."
  {:running ["working" :live] :parked ["waiting for you" :ask] :told ["waiting for you" :ask] :cap ["stopped at its cap" :stopped]
   :done ["done" :done] :merged ["merged" :done] :ready ["ready to merge" :quiet] :gate ["at the merge gate" :quiet] :behind ["behind main" :stopped]
   :dead ["died" :failed] :lost ["lost" :failed] :killed ["killed" :failed] :refused ["refused" :failed] :closed ["closed" :quiet]})

(def ^:private end-stands
  "How the agent's own end (:seon.agent/outcome) reads where its task holds none: [words tone]."
  {:green ["done" :done] :waiting ["waiting for you" :ask] :cap ["stopped at its cap" :stopped] :red ["ended red" :failed]
   :failed ["failed" :failed] :merge-failed ["merge failed" :failed] :error ["ended in error" :failed]})

(def ^:private status-stands
  "How an agent's process reads where nothing else says how its work stands (:seon.agent/status): [words tone]."
  {:spawning ["starting" :live] :busy ["working" :live] :idle ["idle" :quiet] :ended ["finished" :quiet] :killed ["killed" :quiet] :dead ["stopped" :quiet]})

(def ^:private tone-class
  "A standing's tone as the page's text colour."
  {:live "text-signal" :ask "text-info" :done "text-success" :failed "text-error" :stopped "text-warning" :quiet "muted"})

(defn standing
  "How an agent's work stands, one answer for its head and its lists, from data: the agent's attributes a and its
  task's t (:seon.task/outcome, :seon.task/reason), each a map of the attributes it holds (a missing one is nothing).
  The task's outcome says it where a judge has written one (a capped task is stopped, a parked one waits for a
  message); else the agent's own end (:seon.agent/outcome, counted while the agent is at the turn it ended on); else its
  process's status (an agent with none of them, a person's like the user, reads nothing: its state is :status/none). A process that is
  gone is never working or waiting. {:state :text :detail :reason :tone :lit?}: the state (:task/cap, :end/green, :status/idle), the words, the
  turns a cap spent, the reason (the task's, else the agent's end's) and the tone (tone-class); lit while it works. See docs/render.md#a-sessions-address-and-the-agents-list."
  {:malli/schema [:=> [:cat [:maybe :map] [:maybe :map]] [:map [:state :keyword] [:text :string] [:tone :keyword] [:lit? :boolean]
                                                           [:detail {:optional true} :string] [:reason {:optional true} :string]]]}
  [a t]
  (let [status (:seon.agent/status a)
        o (:seon.task/outcome t)
        turn (:seon.agent/turn a)
        end (when (and (:seon.agent/outcome a) (or (nil? turn) (= turn (:seon.agent/ended-turn a)))) (:seon.agent/outcome a))
        gone? (contains? gone status)
        [from k] (cond (and gone? (#{:running :parked :told} o)) [status-stands status]
                       (and o (not= :running o)) [task-stands o]
                       end [end-stands end]
                       (and (= :running o) (= :spawning status)) [status-stands :spawning]
                       o [task-stands o]
                       :else [status-stands status])
        [text tone] (get from k [(kname k) :quiet])
        turns (when (= "stopped at its cap" text) (or (:seon.agent/ended-turn a) (:seon.agent/max-turns a)))
        reason (some #(when-not (str/blank? %) %) [(:seon.task/reason t) (when end (:seon.agent/reason a))])]
    (cond-> {:state (keyword (cond (= from task-stands) "task" (= from end-stands) "end" :else "status") (name (or k :none)))
             :text text :tone tone :lit? (= :live tone)}
      turns (assoc :detail (str turns (if (= 1 turns) " turn" " turns")))
      reason (assoc :reason reason))))

(defn agent
  "An agent's view (the declared html render of :seon.db/agent-fact), its head: its id, status,
  what it runs and on which branch. Its page is its parts (seon.render.proc's agent page,
  parts-page)."
  {:malli/schema [:=> [:cat :seon.render/opts :map] :any]}
  [opts v]
  ;; what it evaluates now (its process's :seon.process/running) is its conversation's newest part
  (let [{:seon.agent/keys [id status branch description]} v
        ;; how its work stands: the task's outcome (a ctx's pull names the task :task), the agent's own end, else its status
        stands (standing v (ratom/realize (or (:task v) (:seon.agent/task v))))
        ;; its current process's pid while it runs: an ended process's pid names nothing live
        ;; a ctx join holds its members by id (a map); root's own pull, a vector
        proc (task/newest-process (let [ps (:processes v)] (if (map? ps) (vals ps) ps)))
        pid (when-not (:seon.process/ended-ms proc) (:seon.process/pid proc))]
    [:div {:class "seon-stack"}
     [:div {:class "flex items-baseline gap-3"}
      [:h1 {:class "text-base font-bold text-signal"} (kname id)]
      ;; how its work stands, at a glance and in a person's words (standing): working, waiting for you only where a message
      ;; is taken, stopped at its cap, merged; the reason it stands so beside it
      [:span {:class (str "shrink-0 " (tone-class (:tone stands)))}
       (str "● " (:text stands) (when (:detail stands) (str " · " (:detail stands))))]
      (when-let [r (:reason stands)]
        [:span {:class "min-w-0 truncate text-text-400" :title r} r])
      ;; what it evaluates now, while its process runs one: the newest thing it does, live
      (when-let [r (and pid (:seon.process/running proc))]
        [:code {:class "min-w-0 truncate text-text-300"} r])]
     [:p {:class "text-text-500 text-xs"} (str/join " · " (remove nil? [(when branch (str "on branch " (kname branch))) (when pid (str "pid " pid))]))]
     (when-not (str/blank? description) [:p {:class "text-text-300"} (prose description (part))])]))

(defn- work-of
  "Main's work as root's page shows it: how many tasks by outcome (the outcome index, counted), and
  each one being worked (its outcome's index entries, pulled)."
  {:malli/schema [:=> [:cat :seon.host/db] :seon.render/work]}
  [db]
  {:by-outcome (frequencies (map :v (d/datoms db :aevt :seon.task/outcome)))
   :working (vec (for [dt (d/datoms db :avet :seon.task/outcome :running)]
                   (d/pull db [:seon.task/id :seon.task/reason] (:e dt))))})


(defn root
  "Root's head (the declared html render of :seon.root/root-fact): its boot, its generation and,
  when it last went quiet, its root causes a line each."
  {:malli/schema [:=> [:cat :seon.render/opts :map] :any]}
  [_ v]
  (let [{:seon.root/keys [boot generation quiescent]} v]
    [:div {:class "seon-stack"}
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
  "An entry of the agents list: the pill, which its link fills."
  "flex max-w-[15rem] shrink-0 rounded-md bg-base-900 lg:max-w-none lg:bg-transparent hover:bg-base-850")

(def ^:private pill-link
  "The one link of a pill: it fills the pill, so a click anywhere on it (its dot, its status, its task) opens the page."
  "flex min-w-0 flex-1 flex-col px-2 py-1 no-underline hover:no-underline lg:px-1")

(defn root-side
  "Root's own entry in its agents list (a component over ctx): lit, as root is the process answering
  the page; the whole pill links to root's page."
  {:seon.render/style side-style :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]]] :any]}
  [_]
  [:li [:a {:href "/" :class pill-link :title "root: main"}
        [:span {:class "flex items-baseline gap-2"} [:span {:class "text-signal"} "●"] [:span {:class "font-semibold text-text-100"} "root"] [:span {:class "seon-note"} "main"]]]])

(defn- task-name
  "What an agent works on, in words: its task's title, else its branch's name; nil for neither."
  {:malli/schema [:=> [:cat [:maybe :map] [:maybe :map]] [:maybe :string]]}
  [a t]
  (cond (:seon.task/id t) (task-title (:seon.task/id t))
        (:seon.agent/branch a) (kname (:seon.agent/branch a))))

(def ^:private session-attrs
  "What an entry of the agents list reads of an agent on main: its identity, its process's status, its branch and turns, and its task."
  [:seon.agent/key :seon.agent/id :seon.agent/status :seon.agent/branch :seon.agent/max-turns
   {:seon.agent/task [:seon.task/id :seon.task/outcome :seon.task/reason]}])

(defn session-address
  "The name of an agent's page: its key (:seon.agent/key, boot/id), the one name of a session that survives a root's restart,
  so a link to it is a bookmark. An agent without a key (a map the page is shown) is its id. See docs/render.md#a-sessions-address-and-the-agents-list."
  {:malli/schema [:=> [:cat :map] :string]}
  [a] (or (:seon.agent/key a) (kname (:seon.agent/id a))))

(defn agent-side
  "A session of main in the agents list (a component over the db, its agent's entity id: every agent on main, of any boot, a
  process or none): lit while it works, its id, how its work stands (standing) and what it works on (its task in words, else its
  branch, cut to the pill's width), the whole pill one link to its page by its stable address (session-address), the whole name in its title. See docs/render.md#a-sessions-address-and-the-agents-list."
  {:seon.render/style side-style :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.db/db :seon.host/db]]] :int] :any]}
  [{db :seon.db/db} eid]
  (let [a (d/pull db session-attrs eid)
        t (:seon.agent/task a)
        id (:seon.agent/id a)
        stands (standing a t)
        what (task-name a t)]
    [:li [:a {:href (str "/agent/" (session-address a)) :class pill-link
              :title (str/join " · " (remove nil? [(kname id) (:text stands) (:detail stands) what (:seon.task/id t)]))}
          [:span {:class "flex items-baseline gap-2"}
           [:span {:class (if (:lit? stands) "text-signal" "muted")} (if (:lit? stands) "●" "○")]
           [:span {:class "font-semibold text-text-100"} (kname id)]
           (when (:seon.agent/status a) [:span {:class (str "truncate text-2xs " (tone-class (:tone stands)))} (:text stands)])]
          (when what [:span {:class "truncate text-2xs text-text-400"} what])]]))

(defn- session-order
  "How a boot's sessions sort in the list: the numbered ids in their numbers (a2 before a10), a person's (user) after them."
  {:malli/schema [:=> [:cat :string] [:tuple :int :string :int :string]]}
  [k]
  (let [id (or (second (str/split k #"/" 2)) k)
        [_ p n] (re-matches #"(\D+)(\d+)" id)]
    [(if n 0 1) (or p "") (if n (parse-long n) 0) id]))

(defn boot-short
  "A boot's id, or a key (boot/id), as a person reads the boot: its first group of characters. See docs/render.md#a-sessions-address-and-the-agents-list."
  {:malli/schema [:=> [:cat :string] :string]}
  [boot-or-key] (first (str/split boot-or-key #"[-/]")))

(defn boot-side
  "One boot's sessions in the agents list (a component over the db, the boot's id: its agents oldest first, so a new session redraws its boot's
  unit alone, whose element id is the boot's): the
  current boot's, in the list itself; an earlier boot's, folded under the boot's id and how many it held, so a list of
  every session on main stays short to read. It reads its boot's keys alone, by their index range, whatever other boots hold. See docs/render.md#a-sessions-address-and-the-agents-list."
  {:seon.render/style "contents" :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.db/db :seon.host/db]]] :string] :any]}
  [{db :seon.db/db} boot]
  (let [eids (->> (d/index-range db {:attrid :seon.agent/key :start (str boot "/") :end (str boot "/\uffff")}) (sort-by #(session-order (:v %))) (mapv :e))
        pills (for [eid eids] [#'agent-side {} eid])]
    (if (= boot (task/boot-of db))
      [:li {:class "contents"} [:ul {:class "contents"} pills]]
      [:li {:class "shrink-0"}
       [:details [:summary {:class "px-1 text-2xs text-text-500" :title (str "boot " boot)} (str "boot " (boot-short boot) " · " (count eids) (if (= 1 (count eids)) " session" " sessions"))]
        [:ul {:class "flex gap-2 lg:flex-col lg:gap-0.5"} pills]]])))

(defn- sessions-by-boot
  "Every agent on main db grouped by the boot that started it (the front of its key): [[boot [eid ...]] ...], the current boot's first,
  then the others newest first (by the transaction that wrote their newest agent: transaction ids only grow, where entity ids are not an order), each boot's agents by id number. See docs/render.md#a-sessions-address-and-the-agents-list."
  {:malli/schema [:=> [:cat :seon.host/db] [:sequential [:tuple :string [:sequential :int]]]]}
  [db]
  (let [now (task/boot-of db)
        by (group-by #(first (str/split (:v %) #"/" 2)) (d/datoms db :avet :seon.agent/key))]
    (->> by
         (map (fn [[boot dts]] [boot (mapv :e (sort-by #(session-order (:v %)) dts)) (apply max (map :tx dts))]))
         (sort-by (fn [[boot _ t]] [(if (= boot now) 0 1) (- t)]))
         (mapv (fn [[boot eids _]] [boot eids])))))

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

(defn work-tile
  "Main's work (a component over the db): how many tasks by outcome and each one being worked (work-of). See docs/render.md#the-page."
  {:seon.render/style panel-style :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.db/db :seon.host/db]]]] :any]}
  [{db :seon.db/db}]
  [:section [:h2 "work"] (render/html {:schema :seon.render/work} (work-of db))])

(defn process-tile
  "Root's process (a component over root's ctx, its :process: the render proc's numbers of the
  writer, the fork server and the store, each proc's defects and the newest events; root's own data
  beside its db pull, never written): a line of numbers, then the procs with defects, then the
  stream's newest rows. It runs again only when :process changes."
  {:seon.render/style panel-style :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]]] :any]}
  [{c :seon.ctx/ctx}]
  (let [{:keys [writer fork-server root-errors-total health events]} (ratom/realize (:process c))
        stat (fn [label v] (when (some? v) [:span [:span {:class "muted"} (str label " ")] (str v)]))
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
                                 [:li {:class "seon-row"} [:span {:class "text-eval w-14 shrink-0"} (event-kind ev)]
                                  [:span {:class "text-text-400 shrink-0"} (some-> (:agent ev) kname)]
                                  [:span {:class "min-w-0 break-all text-text-300"} (event-detail ev)]])])]))

(defn agent-card
  "An agent of root's as its board shows it (a component over root's ctx, its :agents member eid):
  its line (dot, id, how its work stands, model) one link to its page, then its task: the task in words, its model, its budget of turns,
  how its work stands or why it waits. See docs/render.md#the-page."
  {:seon.render/style panel-style :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]] :int] :any]}
  [{c :seon.ctx/ctx} eid]
  (let [a (get-in c [:agents eid])
        ;; its id, status and task alone, so another agent's eval redraws no card
        id (get a :seon.agent/id) status (get a :seon.agent/status)
        t (ratom/realize (get a :task))
        {:seon.task/keys [outcome reason] task-id :seon.task/id} t
        model (get a :seon.agent/model) max-turns (get a :seon.agent/max-turns)
        stands (standing {:seon.agent/status status :seon.agent/max-turns max-turns} t)
        on? (:lit? stands)]
    [:section
     ;; the whole line is the link, as a pill of the agents list is
     [:a {:href (str "/agent/" (session-address a)) :class "flex items-baseline gap-2 no-underline hover:no-underline"
          :title (str/join " · " (remove nil? [(kname id) (:text stands) (:detail stands) (some-> task-id task-title)]))}
      [:span {:class (if on? "text-signal" "muted")} (if on? "●" "○")]
      [:span {:class "font-bold text-text-100"} (kname id)]
      (when status [:span {:class (str "text-2xs " (tone-class (:tone stands)))} (:text stands)])
      (when model [:span {:class "ml-auto text-2xs text-text-500"} model])]
     (if-not task-id
       [:p {:class "text-xs text-text-500"} "no task yet"]
       (list
        [:p {:class "text-sm text-text-100"} (task-title task-id)]
        [:p {:class "text-xs text-text-300"}
         (str (when max-turns (str "up to " max-turns " turns · ")) (if outcome (:text stands) "no outcome yet"))
         (when-not (str/blank? reason) (list " · " [:span {:class "muted"} (prose reason (cell) {:one-line? true})]))]))]))

(defn root-ctx
  "Root's page, the render of root's ctx (the declared html render of :seon.render/root-ctx, which a
  branch overrides like any render), drawn by the walk as components in its lists: its agents list
  (c-side: root, then every session on main by boot, `sessions-by-boot`), its head (p-head), each agent's card
  (p-task: what it works on and how its run stands, newest first; the user, a person, has none), its conversation
  (c-rows: its evals, in the order written) and its tiles (p-tiles: root's process, then main's
  problems and work). An
  agent's page shows the same agents list (seon.render.proc's agent page). It reads the ids of its
  ctx's agents and evals, so it runs again only when one comes or goes."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db :seon.host/db]]]] :any]}
  [{c :seon.ctx/ctx db :seon.db/db}]
  [:div {:id "root-ctx"}
   ;; every session on main, of any boot: root's ctx holds this boot's agents alone (a new boot unlinks the earlier ones from root)
   [:ol {:id "c-side"} [#'root-side {}] (for [[boot _] (sessions-by-boot db)] [#'boot-side {} boot])]
   [:div {:id "p-head"} [#'root-head {}]]
   ;; what each agent is doing, newest first
   [:div {:id "p-task"} (for [eid (sort > (keys (:agents c))) :when (not= :user (get-in c [:agents eid :seon.agent/id]))] [#'agent-card {} eid])]
   [:ol {:id "c-rows"} (for [eid (sort (keys (:evals c)))] [#'eval-item {} eid])]
   [:aside {:id "p-tiles"} [#'process-tile {}] [#'problems-tile {}] [#'work-tile {}]]])

(defn page-chrome
  "A page in root's chrome (root's, an agent's): the top bar, then the units its walks drew, each
  placed in the list it was drawn in ({:id :container :rev :html}: the agents list, the head, the
  task's list, the conversation, the tiles), and its caption and input. Each unit's input is its
  revision, so the render proc sends one only when its output changed, and a new one into its list
  where its walk placed it (an append). opts {:units :view :input :caption :tab}; id, the page's
  focus; tab, which of its tabs it is (top-bar)."
  {:malli/schema [:=> [:cat :map :any] [:map [:seon.render/html :any] [:seon.render/units [:vector :map]]]]}
  [{:keys [units view input caption tab]} id]
  (let [in (fn [sel] (filterv #(= sel (:container %)) units))
        unit (fn [u] {:id (:id u) :input {:rev (:rev u)} :html (:html u) :container (:container u)})]
    {:seon.render/html
     [:div {:id "app" :class "seon-page" :data-view view}
      (top-bar id tab)
      ;; the agents on the left, the chat large in the middle, what is rendered in blocks on the right, each scrolling on
      ;; its own (docs/render.md#where-things-go). Three columns always: a page's side parts may arrive after its first
      ;; frame (an agent's walk answers later). @ctx (tab :debug) places nothing beside its record
      [:div {:class (if (= :debug tab) "seon-columns seon-columns-wide" "seon-columns")}
       [:aside {:class "seon-agents"}
        [:h2 {:class "kicker mb-1 hidden lg:block"} "agents"]
        [:ol {:id "c-side" :class "seon-strip flex gap-2 overflow-x-auto pb-1 text-xs lg:flex-col lg:gap-0.5"} (for [u (in "#c-side")] (slot (:id u)))]]
       [:main {:class "seon-chat"}
        [:div {:id "p-head"} (for [u (in "#p-head")] (slot (:id u)))]
        (slot "p-caption")
        [:ol {:id "c-rows" :class "seon-chat-log" :data-view view} (for [u (in "#c-rows")] (slot (:id u)))]
        (when input (slot "p-input"))]
       (when-not (= :debug tab)
         [:aside {:class "seon-context"}
          [:div {:id "p-task" :class "seon-blocks"} (for [u (in "#p-task")] (slot (:id u)))]
          [:div {:id "p-tiles" :class "seon-blocks"} (for [u (in "#p-tiles")] (slot (:id u)))]])]]
     :seon.render/units
     (vec (concat [{:id "p-caption" :input {:caption caption} :html (h/html [:p {:id "p-caption" :class "seon-note"} (or caption "")])}]
                  (when input [{:id "p-input" :input (:value input) :schema (:schema input)}])
                  (map unit units)))}))

(defn ctx-parts
  "@ctx's parts, each a pair: what the model reads of the part (its data, or the hiccup its render returned, printed as a
  request's parts message prints it, seon.context's parts-message), beside the part as the page draws it (its unit's
  html). parts, {id content} of the task's list (seon.render/placed-hiccup); html, {id html} of its units; open, the
  units the tab opened (/open: d-part-<id>). What the model reads is shown whole, so a part may be a million characters:
  one over the value budget (:seon.config.render/value-budget) is a closed <details> with its size in tokens, whose text
  is drawn once opened, and has the page's width where a short one shares the row with the part drawn. See docs/render.md#the-page."
  {:malli/schema [:=> [:cat :map [:map-of :string :string] [:set :string]] :any]}
  [parts html open]
  [:li {:id "d-parts" :class "seon-stack-2"}
   [:h2 {:class "kicker"} "the task's parts: what the model reads, and the part drawn"]
   (for [[id content] parts
         :let [text (render/part-text content)
               tokens (emit/chars-tokens (count text))
               long? (> tokens (config/value :seon.config.render/value-budget))
               unit (str "d-part-" id)
               opened? (contains? open unit)
               pre [:pre {:class "whitespace-pre-wrap break-words font-mono text-xs text-text-100"} text]]]
     [:div {:class (str "grid grid-cols-1 gap-3 rounded-md border border-base-800 p-2" (when-not long? " md:grid-cols-2")) :data-part id}
      [:div {:class "flex min-w-0 flex-col gap-1"}
       [:span {:class "seon-note"} (str id " · sent to the model")]
       (if long?
         [:details (cond-> (toggle unit) opened? (assoc :open true))
          [:summary {:class "cursor-pointer text-text-300"} (str (print/tokens-text tokens) " read whole: open to read it")]
          (when opened? pre)]
         pre)]
      [:div {:class "flex min-w-0 flex-col gap-1"}
       [:span {:class "seon-note"} "drawn"]
       (h/raw (get html id ""))]])])

;;; --- the debug page: what each turn sent, from its records ---------------------------------------

(defn debug
  "The debug page of id (an agent's id, :root) laid out: what its model is sent. An agent's is a timeline
  of its requests, live on latest or fixed on a turn chosen: steps, each request a button (strip: {:turns [{:e :turn
  :origin}] :at e-or-:latest :to-come? b :gone e-chosen-but-no-request-of-the-branch}), previous, next and latest moving the view (/at), and the one
  request shown (turn: a unit, :seon.render/debug-turn); for root, which has no model turn, the context it
  reads now (context: a unit, :seon.render/root-context). See docs/render.md#the-debug-page-is-the-wire-request."
  {:malli/schema [:=> [:cat :any [:maybe :map] [:maybe :map] [:maybe :map]] [:map [:seon.render/html :any] [:seon.render/units [:vector :map]]]]}
  [focus strip turn context]
  (let [{:keys [label] :as id} (focus-of focus)
        view (view-param (if (= :root focus) [:debug :root] [:debug (:view id)]))
        {:keys [turns at to-come?]} strip
        es (mapv :e turns)
        ;; where the view stands: a turn's eid, or past the newest (latest, while a turn is to come)
        i (if (= :latest at) (if to-come? (count es) (dec (count es))) (.indexOf ^java.util.List es at))
        go (fn [to] (str "@post('/at?view=" view "&turn=" to "')"))
        btn (fn [on? to label] [:button {:type "button" (keyword "data-on:click") (go to)
                                         :class (str "rounded px-2 py-0.5 tabular-nums " (if on? "bg-signal text-base-950" "bg-base-800 text-text-300 hover:text-text-50"))
                                         :aria-current (when on? "step")} label])]
    {:seon.render/html
     [:div {:id "app" :class "mx-auto flex max-w-screen-xl flex-col gap-3 p-3 sm:p-4"}
      (top-bar focus :context)
      [:header {:class "seon-stack"}
       [:h1 {:class "text-base font-semibold text-text-50"} (if context (str "What " label " reads") (str "What " label "'s model is sent"))]
       [:p {:class "text-text-400"} (if context
                                      "Its page as it reads it: the render of its ctx, each component's hiccup by its id, the text /context.txt serves, drawn again when its ctx changes."
                                      "Each request as it went on the wire, made again from its turn's record; latest follows the branch live, a turn chosen stays as it was.")]]
      (when context (slot (:id context)))
      ;; an agent's: its strip and its turn are units, so its layout never moves: a step, a turn added or the first
      ;; turn patches them in place (a patch reaches only the elements the tab holds)
      (when-not context (list (slot "d-steps") (slot "d-turn")))]
     :seon.render/units
     (cond-> []
       (not context)
       (conj {:id "d-steps" :input strip
              :html (h/html [:nav {:id "d-steps" :class "flex flex-wrap items-center gap-1 text-xs"}
                             (when (seq turns)
                               (list (btn false (if (pos? i) (nth es (dec i)) (first es)) "◀ previous")
                                     (for [[j {:keys [e turn origin]}] (map-indexed vector turns)]
                                       (btn (= j i) e (str turn (when (= :outside origin) " read"))))
                                     (when to-come? (btn (= :latest at) "latest" (str (inc (count es)) " to come")))
                                     (btn false (if (< (inc i) (count es)) (nth es (inc i)) "latest") "next ▶")
                                     (btn (= :latest at) "latest" "latest")))
                             ;; a turn chosen that is no request of this branch any more (the branch replaced): said, then latest shown
                             (when-let [g (:gone strip)] [:span {:class "text-warning"} (str "the turn chosen (" g ") is no request of this branch: latest is shown")])])}
             (or turn {:id "d-turn" :input :none :html (h/html [:p {:id "d-turn" :class "muted"} "no model turn yet"])}))
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
  (whole, or what changed since the turn before), an eval, a message, an example session and the opening
  of the agent's own: every ref kind
  seon.context/request names (:seon.model-call/sent)."
  {:malli/schema [:=> [:cat :any] :string]}
  [[kind x t c :as ref]]
  (case kind
    :section (str "the help page (its section, entity " x ") as of t " t)
    :parts (str (if c "the page's parts whole" "the page's parts that changed") " at commit " t ": " (str/join ", " (nth ref 4)))
    :tail (str "the turn line (last, made again each request) at commit " t ": " (str/join ", " (nth ref 4)))
    :eval (str "eval " x)
    :reply (str "the reply of " x)
    :message (str "message " x)
    :example (str "example session " x " as of t " t)
    :session (str "the opening of session " x)))

(defn debug-turn
  "One request of the debug timeline as it went on the wire (the declared html render of
  :seon.render/debug-turn), nothing re-rendered or summarized (owner: \"I don't want to find out later
  that the envelope was different and you just made a fake page for me\"): each message of the body in
  order, its JSON fields as sent (content, tool_calls' arguments, tool_call_id, reasoning_content) beside
  a gutter of its role, the records that made it (their refs, `source`) and its size (the one sizer over
  its JSON); a message new since the request before it marked; the line where the provider's cached
  prefix ends (placed by its cache-hit count over the sizes, an estimate, said so), or, with none recorded, where
  the messages the request before sent the same way end; the envelope (every
  field but the messages) and, while opened, the whole body's JSON as sent. input as `debug-input`
  answers it. See docs/render.md#the-debug-page-is-the-wire-request."
  {:malli/schema [:=> [:cat :seon.render/opts :map] :any]}
  [opts {:keys [view to-come? turn agent origin body sources new raw? error]
         {:seon.model-call/keys [tokens cache-hit-tokens cache-miss-tokens]} :usage}]
  (let [unit (:unit opts)
        ms (:messages body)
        sizes (mapv #(select/estimate (json/write-str %)) ms)
        prompt (when (and cache-hit-tokens cache-miss-tokens) (+ cache-hit-tokens cache-miss-tokens))
        est (reduce + 0 sizes)
        scale (if (and prompt (pos? est)) (/ (double prompt) est) 1.0)
        cached (when (and cache-hit-tokens (pos? cache-hit-tokens))
                 (count (take-while #(<= % cache-hit-tokens) (reductions + (map #(* scale %) sizes)))))
        ;; with no cache count recorded (a turn to come, a provider that reports none): where the messages the
        ;; request before sent the same way end, the prefix a provider's cache can serve
        same (when (nil? cached) (first (keep-indexed (fn [i n?] (when n? i)) new)))
        upto (vec (reductions + 0 sizes))
        line (fn [cls text] [:div {:class (str "my-1 flex items-center gap-2 text-2xs " cls)}
                             [:span {:class "h-px flex-1 bg-current opacity-40"}] text [:span {:class "h-px flex-1 bg-current opacity-40"}]])
        field (fn [k v] [:div {:class "flex flex-col"} [:span {:class "seon-note"} k]
                         [:pre {:class "whitespace-pre-wrap break-words font-mono text-text-100"} v]])
        row (fn [i {:keys [role content tool_calls tool_call_id reasoning_content]}]
              (let [rs (get sources i) new? (get new i)
                    example (some (fn [[kind x]] (when (= :example kind) x)) rs)]
                [:div {:class (str "grid grid-cols-[minmax(0,11rem)_minmax(0,1fr)] gap-3 rounded-md border-l-2 px-2 py-1 "
                                   (if new? "border-signal bg-signal/10" "border-base-700 bg-base-950"))
                       :data-new (str new?) :data-session (when example example)}
                 [:div {:class "flex flex-col gap-0.5 text-2xs text-text-400"}
                  [:b {:class "text-text-200"} role] (when new? [:span {:class "text-signal"} "new since the turn before"])
                  (when example [:span {:class "text-info"} (str "example session " example)])
                  (for [r rs] [:span (source r)])
                  [:span {:class "tabular-nums"} (print/tokens-text (get sizes i))]]
                 [:div {:class "flex min-w-0 flex-col gap-1"}
                  (when tool_call_id (field "tool_call_id" tool_call_id))
                  (when reasoning_content [:details [:summary {:class "seon-note"} "reasoning_content"]
                                           [:pre {:class "whitespace-pre-wrap break-words font-mono"} reasoning_content]])
                  (when (seq content) (field "content" content))
                  (for [{:keys [id function]} tool_calls]
                    (field (str "tool_calls · " (:name function) " · " id " · arguments") (:arguments function)))]]))]
    [:section {:id unit :class "flex flex-col gap-2 rounded-md bg-base-900 p-3 text-xs"}
     [:div {:class "flex flex-wrap items-baseline gap-3"}
      [:b {:class "text-signal"} (if to-come? (str "turn " (inc turn) ", to come: what it would send now") (str "turn " (inc turn)))]
      [:span {:class "text-text-400"} agent]
      (when tokens [:span {:class "tabular-nums text-text-300"} (str (group3 tokens) " tokens")])
      (when (and prompt (pos? prompt))
        [:span {:class "tabular-nums text-text-400"} (str (group3 cache-hit-tokens) " of " (group3 prompt) " prompt tokens from cache (" (long (* 100 (/ cache-hit-tokens prompt))) "%)")])
      [:span {:class "muted"} (str (count ms) " messages · " (count (filter true? new)) " new")]
      (cond (= :outside origin) [:span {:class "muted"} "an outside read: no model call"]
            to-come? [:span {:class "muted"} "redrawn as the branch moves"]
            (not (or tokens error)) [:span {:class "muted"} "the call is in flight"])]
     (when error [:div {:class "rounded border border-error p-2"} (render/html {} error)])
     [:details [:summary {:class "cursor-pointer text-text-400"} "the envelope: every field but the messages, as sent"]
      [:pre {:class "whitespace-pre-wrap break-words font-mono text-text-100"} (json/write-str (dissoc body :messages))]]
     (into [:div {:class "seon-stack"}]
           (mapcat (fn [i m] (cond-> [(row i m)]
                               (= (inc i) cached) (conj (line "text-info" (str "the provider's cache ends here: " (group3 cache-hit-tokens) " tokens served from cache (placed by estimate)")))
                               (and same (pos? same) (= (inc i) same))
                               (conj (line "text-info" (str "sent the same by the request before down to here: " (group3 (long (nth upto same)))
                                                            " tokens a provider's prefix cache can serve (none recorded for this request)")))))
                   (range) ms))
     [:details (cond-> {(keyword "data-on:toggle") (str "@post('/open?view=" view "&unit=" unit "-raw&open=' + el.open)")} raw? (assoc :open true))
      [:summary {:class "cursor-pointer text-text-400"} "the body's JSON, whole, as sent"]
      (when raw? [:pre {:class "whitespace-pre-wrap break-all font-mono text-text-100" :data-wire "body"} (json/write-str body)])]]))
