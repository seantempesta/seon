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
            [seon.render :as render] [datahike.api :as d] [seon.ctx :as ctx] [seon.ctx.search :as search] [seon.ctx.session :as session] [seon.ctx.status :as status] [seon.ctx.wire :as wire] [seon.program :as program] [seon.print :as print] [seon.print.emit :as emit] [seon.print.select :as select] [seon.web.html :as h] [seon.reader :as reader] [seon.cost :as cost] [seon.task :as task] [seon.ctx] [seon.problems :as problems] [seon.edn :as sedn] [seon.lib :as lib] [datahike.index.interface] [seon.ratom :as ratom] [seon.vector :as vector] [seon.db :as pdb] [seon.proofs :as proofs] [seon.merge :as pm] [seon.ref :as ref] [seon.reconcile :as reconcile] [clojure.set]))

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
                                              (vector? view) (str (of (second view)) "/debug")
                                              ;; an agent by its id or its branch: its debug view, every render of its ctx
                                              (keyword? view) (str (of view) "/ctx")
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

(defn plain
  "Text on the page as the one printer's text (seon.print/plain) under budget tokens: whole,
  or its shown part and the cut, which says what it omitted and where the rest is: a link
  to opts :href (the page that shows it whole); with opts :whole (a form that answers the whole
  value, e.g. its result handle) the cut names that form; with neither, the cut's title holds the
  whole text (a person's way to the rest; with a form, a copy the model would read twice). opts
  :one-line? as the printer's (a table cell, a stream row)."
  {:malli/schema [:function [:=> [:cat :any pos-int?] :any] [:=> [:cat :any pos-int? :map] :any]]}
  ([s budget] (plain s budget {}))
  ([s budget {:keys [href one-line? whole]}]
   (let [s (if (string? s) s (print/text s budget))
         {:keys [shown truncated? omitted lines]} (print/plain s budget {:one-line? (boolean one-line?)})]
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
  highlighted as Clojure); cut, the text cut (`text`), whose marker names what it left."
  {:malli/schema [:=> [:cat :string pos-int?] :any]}
  [s budget]
  (let [p (plain s budget)]
    ;; a cut holds its marker element, which highlighting (it rewrites the code's text) would remove
    (if (string? p) (emit/marked-hiccup :seon.print/code p) [:pre {:class "text-text-100"} [:code p]])))

(defn task-title
  "A task's id as a person reads it: its name in words, its first letter capital (seon.print/a-long-string
  becomes \"A long string\");  See docs/render.md#the-page."
  {:malli/schema [:=> [:cat :string] :string]}
  [id]
  (let [s (str/replace (name (symbol id)) "-" " ")]
    (str (str/upper-case (subs s 0 (min 1 (count s)))) (subs s (min 1 (count s))))))

(defn- inline
  "The hiccup of text between offsets from and to, a stretch of a Markdown block, by the one parse: spans, seon.ref/scan's, drawn as code and
  links, and what lies between by seon.ref/emphasis. A link goes only where a page may send a person (http, https, a path, a fragment); any other keeps its text. See docs/render.md#text-and-the-one-string-cutter."
  {:malli/schema [:=> [:cat :string :int :int [:sequential :map]] [:sequential :any]]}
  [text from to spans]
  (let [plain (fn [a b] (when (< a b) (for [p (ref/emphasis (subs text a b))] (if (vector? p) [(first p) (second p)] p))))
        inside (filter (fn [{f :seon.ref/from t :seon.ref/to}] (and f (<= from f) (<= t to))) spans)
        ;; a link's own spans are drawn inside it
        tops (reduce (fn [acc {f :seon.ref/from :as sp}] (if (and (seq acc) (< f (:seon.ref/to (peek acc)))) acc (conj acc sp))) [] inside)]
    (loop [i from [sp & more] tops out []]
      (if-not sp
        (into out (plain i to))
        (let [{f :seon.ref/from t :seon.ref/to :seon.ref/keys [code link text-from text-to]} sp
              out (into out (plain i f))]
          (recur t more
                 (conj out (cond code [:code code]
                                 (some #(str/starts-with? link %) ["http://" "https://" "/" "#"])
                                 (into [:a {:href link}] (inline text text-from text-to spans))
                                 :else (into [:span] (inline text text-from text-to spans))))))))))

(defn- quote-lines
  "The offsets of what each line of the quote at from..to says, past its > mark (a lazy line has none). See docs/render.md#text-and-the-one-string-cutter."
  {:malli/schema [:=> [:cat :string :int :int] [:vector [:tuple :int :int]]]}
  [text from to]
  (loop [a from out []]
    (let [nl (or (some-> (str/index-of text "\n" a) (min to)) to)
          sp (loop [i a] (if (and (< i nl) (= \space (nth text i))) (recur (inc i)) i))
          past (if (and (< sp nl) (= \> (nth text sp))) (inc sp) a)
          start (if (and (< past nl) (> past a) (= \space (nth text past))) (inc past) past)
          out (conj out [start nl])]
      (if (< nl to) (recur (inc nl) out) out))))

(defn markdown
  "Markdown text (a plan, an agent's message, a message) as a person reads it: its blocks by the one
  parse (seon.ref/scan): a heading at its level, a paragraph, a list of the scan's items (numbered
  when its first marker is a number), a code block highlighted (a fenced block's content as the scan gives it), a quote or a table as its lines. Each
  is kept as written, its backticks too, where the model reads the page: a plan's delimiters pick
  what its citations pull (docs/references.md#the-delimiter-picks-the-depth). opts :inline? is for what only a person reads: code spans, links and
  emphasis drawn (`inline`), and a quote as one. See docs/render.md#text-and-the-one-string-cutter."
  {:malli/schema [:function [:=> [:cat :string] :any] [:=> [:cat :string [:map [:inline? {:optional true} :boolean]]] :any]]}
  ([text] (markdown text {}))
  ([text {:keys [inline?]}]
   (let [{:seon.ref/keys [blocks spans]} (ref/scan text)
         run (fn [a b] (if inline? (inline text a b spans) [(subs text a b)]))]
     (into [:div {:class "seon-md flex flex-col gap-2 leading-relaxed [&_p]:whitespace-pre-wrap [&_li]:whitespace-pre-wrap"}]
           (for [{:seon.ref/keys [kind from to heading heading-level text-from text-to items ordered]} blocks
                 :let [body (subs text from to)]]
             (cond
               (= :heading kind) [(keyword (str "h" (min 6 (+ 2 (or heading-level 1))))) {:class "font-bold text-text-100"} heading]
               ;; a code block: indented, or fenced, whose content the scan gives (its marks are no text of it)
               (= :code kind) (code body (config/value :seon.config.render/value-budget))
               (= :fence kind) (code (subs text text-from text-to) (config/value :seon.config.render/value-budget))
               (= :list kind) (into [(if ordered :ol :ul) {:class (if ordered "list-decimal pl-5" "list-disc pl-5")}]
                                    (for [[a b] items] (into [:li] (run a b))))
               (and inline? (= :quote kind)) (into [:blockquote {:class "border-l-2 border-base-700 pl-3 text-text-300"}]
                                                  (for [[a b] (quote-lines text from to)] (into [:p] (run a b))))
               (#{:quote :table} kind) [:pre {:class "whitespace-pre-wrap text-text-300"} body]
               (= :break kind) [:hr]
               :else (into [:p] (run from to))))))))

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
  on main: every key grouped by its area, its value whole, its unit, the schema's description
  on hover, and the layer it came from (:seon.config/sources, docs/config.md#layers) as text
  beside it, when opts names it (:seon.config/sources, root's fact on main). It reads schemas in the walk's entity index (:seon.render/index) for the values it draws through render/hiccup: one render running another, the interim stage 2a of docs/users.md removes."
  {:malli/schema [:=> [:cat :seon.render/opts :seon.config/config] :any]}
  [opts c]
  (let [prop (fn [k p] (get (contract/properties k) p))
        sources (:seon.config/sources opts)
        groups (group-by (comp namespace key) (sort-by (comp str key) c))]
    [:section {:id "t-config" :class "tile wide config"}
     (kicker "config" (str (count c) " keys in force on main · resources/seon/config/default.edn · profile " (name (config/profile))))
     (when-let [w (not-empty (config/waiting))]
       [:p {:class "muted"} (str "main states these and this process waits for a rebuild: " (str/join ", " (map str (sort (keys w)))))])
     [:div {:class "cfg-groups"}
      (for [[g kvs] (sort-by key groups)]
        [:dl {:class "cfg"}
         [:dt {:class "cfg-area"} (str/replace g #"^seon\.config\.?" "")]
         (for [[k v] kvs]
           (let [src (get sources k)]
             [:div {:title (str (prop k :description))}
              [:dt (name k)]
              [:dd (render/hiccup {:seon.render/index (:seon.render/index opts) :budget (part)} v)
               (when-let [u (prop k :seon.config/unit)] [:span {:class "muted"} " " u])
               (when src [:span {:class "cfg-source muted"} (str " from " src)])]]))])]]))

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
      [:b (plain (:seon.error/message e) budget)]
      [:span {:class "muted"} (str " · " (kname (:seon.error/type e)) " in " (:seon.error/operation e))]
      (when-let [{:keys [line column]} (:seon.error/at e)]
        [:p {:class "muted"} (str "at line " line (when column (str ":" column)) (when-let [in (:seon.error/in e)] (str " of " in)))])
      (when (:seon.error/fix e) [:p {:class "fix"} "fix: " (plain (:seon.error/fix e) budget)])
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
       (let [base (if (= :root focus) "/root" (str "/agent/" address))]
         [:div {:class "ml-auto flex items-baseline gap-1 text-xs"}
          (when (or (= :root focus) (not= :chat tab)) [:span {:class "mr-1 font-semibold text-text-100"} label])
          (if (= :root focus)
            (list (link (= :chat tab) base "chat") (link (= :context tab) "/root/debug" "debug"))
            (list (link (= :chat tab) base "chat") (link (= :debug tab) (str base "/ctx") "@ctx") (link (= :context tab) (str base "/debug") "debug")))]))]))

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
    [:div {:style "display:none" :data-init (str "@get('" feed "', {retry: 'always', retryMaxCount: Infinity, openWhenHidden: true})")}]]])

;;; --- values' own renders: a task, a verdict, a cost report ---------------------------------

(defn task-box
  "A task: its kind and execution, its plan (accumulated along its needs), instructions, what
  is wrong (its failure, held for root), its needs and proofs, and its evidence. It reads schemas in the walk's entity index (:seon.render/index) for the values it draws through render/hiccup: one render running another, the interim stage 2a of docs/users.md removes."
  {:malli/schema [:=> [:cat :seon.render/opts :seon.task/task] :any]}
  [opts t]
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
        [:div {:class "muted"} "evidence " (render/print-hiccup {:budget (config/value :seon.config.render/part-budget) :seon.render/index (:seon.render/index opts)} ev)])]))

(defn verdict-box
  "A cost verdict (seon.cost/verdict-lines, the agent's words too): red when a size is over
  its estimate or grew steeper."
  {:malli/schema [:=> [:cat :seon.render/opts :seon.cost/verdict] :any]}
  [_ v]
  (let [[[_ head] & more] (cost/verdict-lines v nil)]
    [:div {:class (str "verdict " (if (:ok? v) "ok" "red"))} [:b head] (for [[c w] more] [:span {:class c} (str " " w)])]))

(defn cost-report
  "A cost proof's measurements against its estimate, row by size, then its verdict. It reads schemas in the walk's entity index (:seon.render/index) for the values it draws through render/hiccup: one render running another, the interim stage 2a of docs/users.md removes."
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
      (render/hiccup (assoc opts :schema :seon.cost/verdict) verdict)]))

(defn agent-input
  "The input at the top of an agent's page, each through root's requests (the server's ops send
  the same): words are a message its next turn reads (seon.root/message!), and a line that starts with
  ( evaluates in its REPL (seon.root/submit!, origin :ui), as every line does on an agent with no task; interrupt stops its running form;
  release runs its task's next run when the task is held (seon.root/release!). input {:id
  :address :live? :held :focus? :busy? :talks?}: address, its key, whom a word goes to in any boot; talks?, it has a task, so what's said wakes it, process or none; live, root's view holds its process in this boot; busy?, it runs a form (what interrupt stops); capped?, its task is
  focus?, its field takes focus as the page loads (the chat's:
  @ctx's page is long and the field ends it)."
  {:malli/schema [:=> [:cat :seon.render/opts :seon.render/agent-input] :any]}
  [opts {:keys [id address live? held focus? busy? talks?]}]
  [:section {:id (:unit opts) :class "seon-stack-2"}
   (if (or live? talks?)
     [:form {:class "seon-row" :data-seon-repl "" :seon/call 'seon.root/submit! :seon/agent (or address id)}
      [:span {:class "text-signal"} (str (kname id) "=>")]
      ;; Enter sends, Shift+Enter is a new line: a form may span lines
      [:textarea (cond-> {:name "forms" :class "flex-1 min-w-0" :rows 3 :autocomplete "off" :spellcheck "false"
                          (keyword "data-on:keydown") "if (evt.key==='Enter' && !evt.shiftKey) { evt.preventDefault(); el.form.requestSubmit() }"
                          :placeholder (cond (and talks? live?) "Say something (Enter; Shift+Enter: a new line)\n(+ 1 2) runs in its REPL"
                                       talks? "Say something and it starts again (Enter; Shift+Enter: a new line)"
                                       :else "(+ 1 2) evaluates in its REPL (Enter; Shift+Enter: a new line)")}
                   ;; the chat takes focus (it follows its tail); a browser scrolls a focused field into view, and this one ends the page
                   focus? (assoc :autofocus true))]
      ;; a form to interrupt only while its process runs one (status :busy)
      (when busy?
        [:button {:type "button" :class "rounded border border-base-700 px-2" :title "interrupt the running form; the agent keeps its state"
                  :seon/call 'seon.root/interrupt! :seon/agent id} "⏹ interrupt"])]
     [:p {:class "muted"} "This agent isn't running, so it can't answer. What it did is above."])
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
  it ran: the text beside it, its code, its response printed under its handle as it is shown
  (seon.print/record-text of the eval, through the model render of the schema it kept; a value in a row by that schema's
  html render; a live response's own :text; an error's by its error
  map's render; a value recorded as its basis, @ctx's, rendered from the branch at it, seon.ctx.wire/basis-value), and its cost and who evaluated it as recorded; :as :inline, its code and
  response in one line, the value's line printed now (seon.print/line: the one printer). Rendered from the eval, which never changes. It reads schemas in the walk's entity index (:seon.render/index) for the values it draws through render/hiccup: one render running another, the interim stage 2a of docs/users.md removes."
  {:malli/schema [:=> [:cat :seon.render/opts :map] :any]}
  [opts e]
  (let [recorded? (contains? e :seon.eval/response)
        resp (pdb/response-of (:seon.db/db opts) e)
        basis (:seon.eval/basis e)
        r (if recorded?
            (cond-> resp (:seon.eval/code e) (assoc :code (:seon.eval/code e)) (:seon.eval/intent e) (assoc :intent (:seon.eval/intent e))
                    (:seon.eval/fns e) (assoc :fns (:seon.eval/fns e)))
            e)
        ;; a value recorded as data: inline, printed as its reply reads under its handle; in a row,
        ;; rendered as structure (the printer's hiccup). A live reply's :text is text
        data? (and recorded? (nil? (:text r)) (nil? basis))
        r (cond-> r (and recorded? (nil? (:text r)) data? (= :inline (:as opts))) (assoc :text (print/record-text (assoc e :seon.eval/response resp) #(render/stored-model (:seon.render/index opts) %))))
        by (or (:seon.eval/by e) (:by r))
        err? (some? (:ex r))]
    (if (= :inline (:as opts))
      ;; its code's first line ⇒ its value's line, printed now by the one printer under the line budget, else its
      ;; text; in the chat (:chat true), the agent's message first: the chat is text and a line an arrow opens
      (let [shown (when (and recorded? (or (contains? resp :value) (:ex resp) basis))
                    (let [v (:value resp) k (:seon.eval/schema e)
                          m (when (and k (instance? clojure.lang.IObj v)) (render/model (:seon.render/index opts) (vary-meta v assoc :seon.render/schema k)))]
                      (print/line (cond (:ex resp) (or (get-in resp [:ex :seon.error/message]) (:ex resp))
                                        basis (when (:db opts) (wire/basis-value (:seon.render/index opts) (:db opts) e))
                                        (nil? v) "nil" m (:value m) :else v)
                                  (config/value :seon.config.render/line-budget) nil)))
            line [:span {:class "font-mono"} [:code (merge-with #(str %1 " " %2) emit/highlight {:class "text-text-100"}) (plain (or (first (str/split-lines (or (:code r) ""))) "") (cell) {:one-line? true})]
                  [:span {:class (if err? "text-error" "muted")} (if err? " ✕ " " ⇒ ")]
                  [:code {:class "text-text-400"} (plain (or shown (:text r) "") (cell) {:one-line? true})]]]
        (if (and (:chat opts) (not (str/blank? (:intent r))))
          [:span {:class "flex flex-col"} [:span {:class "whitespace-pre-wrap text-text-300 italic"} (plain (:intent r) (form-budget))] line]
          line))
      [:div {:class (str "flex flex-col gap-1 border-l-2 pl-3 py-1 " (if err? "border-error" "border-base-700"))}
       (when-not (str/blank? (:intent r)) [:p {:class "whitespace-pre-wrap text-text-300 italic"} (plain (:intent r) (form-budget))])
       (when (:code r) (code (:code r) (form-budget)))
       (if (map? (:ex r))
         (render/hiccup (cond-> {:seon.render/index (:seon.render/index opts)} (:handle r) (assoc :handle (list :ex (:handle r)))) (:ex r))
         (if data?
           ;; a value stored as data renders as structure, the printer's hiccup under its handle
           ;; a value of a schema with its own render is that value; any other, the response less what the row shows beside it
           (let [v (if (:seon.eval/schema e) (:value resp) (shown-value resp))]
             [:div {:class "flex flex-col gap-1 text-text-200"}
              ;; drawn by the html render of the schema the record kept (the value's metadata does not survive the store),
              ;; as the live response is: a prove's run by seon.render.ui/proved, not as a map
              [:div {:class "seon-row"} [:span {:class "muted"} "⇒"] (render/hiccup (cond-> {:seon.render/index (:seon.render/index opts) :handle (:handle r)} (:seon.eval/schema e) (assoc :schema (:seon.eval/schema e))) v)]
              ;; each cut opened: what its form reads, from the record, under that form (seon.web/open-form read it)
              (for [f (:opened opts) :let [form (edn/read-string f)]]
                [:div {:class "seon-row"}
                 ;; the form read, as the button that closes it
                 [:button {:type "button" :class "text-text-500 font-mono text-left" :data-seon-form f :title "close"
                           (keyword "data-on:click") (str "@post('/open?view=' + el.closest('[data-view]').dataset.view + '&unit=' + "
                                                          "encodeURIComponent(el.closest('li[id]').id + '/' + el.dataset.seonForm) + '&open=false')")} f]
                 (render/hiccup {:seon.render/index (:seon.render/index opts) :handle form} (print/read-rest form (:handle r) v))])])
           (if-let [v (when (and basis (:db opts)) (wire/basis-value (:seon.render/index opts) (:db opts) e))]
             ;; a value recorded as its basis (@ctx): rendered from what the branch holds at it
             [:div {:class "flex gap-2 text-text-200"} [:span {:class "muted"} "⇒"]
              (render/hiccup {:seon.render/index (:seon.render/index opts) :as :block :schema (:seon.eval/schema e) :handle (:handle r) :db (:db opts)} v)]
             [:pre {:class "text-text-200"} [:span {:class "muted"} "⇒ "] (plain (or (:text r) "") (part))])))
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
  its reply stopped and its model; its reply and reasoning as text, all of its model call
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
       (when-not (str/blank? reply) [:p {:class "seon-text"} (plain reply (part))])
       (when-not (str/blank? reasoning)
         [:details [:summary {:class "seon-note"} "its reasoning"] [:p {:class "seon-text text-text-300"} (plain reasoning (part))]])])))

(defn message
  "A message to a run (the declared html render of :seon.message/fact): who sent it and its body as text."
  {:malli/schema [:=> [:cat :seon.render/opts :map] :any]}
  [opts {:seon.message/keys [from body]}]
  (if (= :inline (:as opts))
    [:span (str from ": ") (plain body (cell) {:one-line? true})]
    [:div {:class "border-l-2 border-info pl-3 py-1"}
     [:span {:class "seon-note"} (str "message from " from)]
     [:p {:class "seon-text"} (plain body (part))]]))

(declare standing)

(defn task-view
  "A task (the declared html render of :seon.task/task-fact) as a person reads it, wherever the pattern joined one: inline its
  id, kind and execution; whole, its name as a title, its namespace and how its work stands above it (the task's outcome
  where it holds one: a capped task is not \"running\"), how its kind is worked and its plan accumulated over its needs
  as Markdown. The agent's own task part is `task`. See docs/render.md#a-component-answers-hiccup."
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
       (when instructions [:details [:summary "how the agent works it"] [:p {:class "seon-text"} instructions]])
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
     (when why (list ": " (plain why (cell) {:one-line? true})))
     (when-let [ts (seq (:answered-by r))] (str " (answered by " (str/join ", " ts) ")"))]))

(defn problems
  "Main's problems (the declared html render of :seon.problems/report): each class with rows,
  its count, and its rows a line each (`problem-row`), open ones first, rendered only when the
  tab opened the class (its id in :open, the /open toggle's; a report shown with no :open shows
  every class's rows). It reads schemas in the walk's entity index (:seon.render/index) for the values it draws through render/hiccup: one render running another, the interim stage 2a of docs/users.md removes."
  {:malli/schema [:=> [:cat :seon.render/opts :seon.problems/report] :any]}
  [opts {:keys [classes open]}]
  (let [cs (for [[c {:keys [n rows]}] (sort-by (comp str key) classes) :when (seq rows)] [c n rows])]
    [:div {:class "flex flex-col gap-1 text-xs"}
     (if (empty? cs)
       [:p "none open"]
       (for [[c n rows] cs :let [id (str "pc-" (name c)) shown? (or (nil? open) (contains? open id))]]
         [:details (cond-> (toggle id) (and open shown?) (assoc :open true))
          [:summary {:class "cursor-pointer"} (str (kname c) " " n)]
          (when shown? (for [r (sort-by #(if (false? (:open? %)) 1 0) rows)] (render/hiccup {:seon.render/index (:seon.render/index opts) :schema :seon.problems/row} (assoc r :class c))))]))]))

(defn problems-model
  "The problems report as the model is sent it (the declared model render of :seon.problems/report):
  each class with its count and its rows newest first, a row its count, type, its message's first line
  and, for a recorded error, the form that reads it whole ((my.agent/problem signature): its example and
  evidence), never the rows printed whole. So many errors are a few lines that say how many there are
  and where to read each. See docs/render.md#a-component-answers-hiccup."
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
  (render/hiccup with :schema the key), else the one printer's structure. Drawn where its renders
  are: in the agent, under its branch's schemas, so a render the branch defines draws its key
  (seon.repl/render-ctx); anywhere else, by main's. Beside each attribute, who made it as of t
  (seon.ctx/made-by): the eval whose code wrote it, else the branch it came from when not this one,
  and a catch-up's merge from main. It reads schemas in the walk's entity index (:seon.render/index) for the values it draws through render/hiccup: one render running another, the interim stage 2a of docs/users.md removes."
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
        cell (fn [k v] (render/hiccup (cond-> {:seon.render/index (:seon.render/index opts) :schema (when (qualified-keyword? k) k)} handle (assoc :handle (list k handle))) v))]
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
  made again from its records at its basis commit, seon.ctx.wire/wire-turn), or :latest, the request the agent's next model
  turn would send now (its run going on: drawn at db's head as the turn would draw it, with the turn it
  writes first), else its newest turn. calls, the branch's model calls in order; raw?, the body's JSON
  shown whole. Which messages are new: those of a record the request before it did not hold (the turns after its number).
  :differs? says the redraw is not what went out: the turn hashed the bytes it sent (:seon.model-call/sent-hash), and
  today's renders drew others. See docs/render.md#the-debug-page-is-the-wire-request."
  {:malli/schema [:=> [:cat :seon.host/db [:or pos-int? [:= :latest]] [:vector pos-int?] :boolean] [:maybe :map]]}
  [db at calls raw?]
  (let [pull #(d/pull db [:seon.model-call/basis :seon.model-call/sent-hash :seon.model-call/tokens :seon.model-call/cache-hit-tokens
                          :seon.model-call/cache-miss-tokens :seon.model-call/error] %)
        last-e (peek calls)
        lb (some-> last-e pull :seon.model-call/basis)
        ;; the turn to come: on latest, while the newest model turn's agent has no end
        to-come? (and (= :latest at) (to-come? db lb))
        e (cond to-come? nil (= :latest at) last-e :else at)
        m (some-> e pull)]
    (when (or to-come? (:seon.model-call/basis m))
      (let [[tdb b swept?] (if to-come?
                      ;; as seon.turn/act! draws it: the turn's number first, then the request at the head
                      (let [n (inc (:turn lb)) b (assoc lb :commit (d/commit-id db) :turn n)]
                        [db b])
                      (let [b (:seon.model-call/basis m)]
                        ;; a commit past the store's keep (seon.db/retain!) is the same records as of its t (docs/storage.md#collection); a basis with no t draws from the head, and the page says so
                        (let [at-commit (if (= (:commit b) (d/commit-id db)) db (d/commit-as-db (:store db) (:commit b)))
                              as-of (when (and (nil? at-commit) (:t b)) (d/as-of db (:t b)))]
                          [(or at-commit as-of db) b (and (nil? at-commit) (nil? as-of))])))
            i (if to-come? (count calls) (.indexOf ^java.util.List calls e))
            prev-e (when (pos? i) (nth calls (dec i)))
            ;; with no request before it, nothing is marked: every message is the first request's
            since (some-> prev-e pull :seon.model-call/basis :turn)
            w (wire/wire-turn tdb b since)
            sent-hash (:seon.model-call/sent-hash m)]
        {:at at :to-come? to-come? :turn (:turn b) :agent (:agent b) :origin (:origin b) :raw? raw? :swept? (boolean swept?)
         :body (:body w) :sources (:sources w) :new (:new w)
         :differs? (boolean (and sent-hash (not= sent-hash (lib/digest (sedn/encode (:messages w))))))
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

(defn page-rules
  "The CSS of this page's renders (a component of the walk, reading its :seon.render/drawn ratom as any tracked read), one style element a render, keyed by its name, or nothing while none states any. For the page alone: an agent reads a render's CSS at its REPL, where it lives. It moves only when a rule does. See docs/render.md#style."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.render/drawn :any]]]] :any]}
  [{d :seon.render/drawn}]
  (when-let [rs (seq (render/rules @d))]
    (into [:div] (for [[r css] rs] [:style {:key r} css]))))

(defn rules-list
  "The page's rules as a list of their own (p-rules), so a change of a rule draws nothing else: on the page, never in the model's request. See docs/render.md#style."
  {:seon.render/list "p-rules" :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]]] :any]}
  [_] [:div {:id "p-rules"} [#'page-rules {:wire/for "page"}]])

(defn task
  "The task its agent works (a component over ctx alone): its name as the heading, its plan accumulated over its needs
  (seon.task/pulled-accumulated), and under it one done line (a work or whole task's; the other kinds' instructions say what
  they are for). Not a map of keys: what the agent reads cold. For the person, beside it, the namespace and how the
  work stands (`standing`, from the task's outcome where the task holds one, else the agent's own end). The cites are
  their own parts (`citations`); which proofs are written is the status's. It reads no db. See
  docs/render.md#a-component-answers-hiccup."
  {:seon.render/style ":is(.task, .cited, .citations, .references, .searched, .status) { display: flex; flex-direction: column; gap: 0.5rem; border: 1px solid var(--color-base-800); border-radius: 0.375rem; padding: 0.5rem; }
:is(.task, .cited, .citations, .references, .searched, .status) > h2 { text-transform: uppercase; letter-spacing: 0.14em; font-size: 0.625rem; color: var(--color-text-400); }
.task h3 { font-size: 0.75rem; color: var(--color-text-300); }" :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]]] :any]}
  [{c :seon.ctx/ctx}]
  (when-let [t* (:seon.agent/task c)]
    (let [own (fn [x] (into {} (for [k (keys x) :when (not= :seon.cite/_of k)] [k (ratom/realize (get x k))])))
          t (assoc (own t*) :seon.task/needs (into {} (for [[k n] (:seon.task/needs t*)] [k (own n)])))
          id (:seon.task/id t)
          {:keys [plan]} (task/pulled-accumulated t)
          agent (into {} (for [k [:seon.agent/outcome :seon.agent/ended-turn :seon.agent/turn :seon.agent/max-turns :seon.agent/reason :seon.agent/status]
                               :let [v (ratom/realize (get c k))] :when (some? v)] [k v]))
          stands (when (or (:seon.agent/outcome agent) (:seon.task/outcome t)) (standing agent (select-keys t [:seon.task/outcome :seon.task/reason])))]
      [:section
       [:h2 "Your task"]
       [:p {:wire/for "page"} (str "task · " (namespace (symbol id)) (when stands (str " · " (:text stands) (when (:detail stands) (str " · " (:detail stands))))))]
       (when plan (markdown plan))
       ;; a work or whole task is done by the rule in the system prompt; the other kinds (design, merge, test) say what they are for
       (when-not (#{:work :whole} (:seon.task/kind t :work))
         (when-let [ins (:seon.task/instructions t)] [:p ins]))
       [:p (str "Task: " id)]])))

(def ^:private cite-joins
  "Where a cite's joins hold an entity, and the identity attribute that marks it held whole there (an entity
  is held once in ctx, {:db/id e} at any other path: docs/ctx.md#the-pattern): each join path below a cite,
  the ones that hold an entity by the cite's own pattern first."
  (into (mapv (fn [a] [[:seon.cite/target] a]) ref/target-attrs)
        [[[:seon.cite/target :var/form] :form/id] [[:seon.cite/target :seon.doc/_parent :*] :seon.doc/address]
         [[:seon.cite/target :callers :*] :form/id] [[:seon.cite/target :callers :* :form/defines :*] :var/name]]))

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

(declare page-cites held-whole)

(defn help-form
  "The form an agent runs to read more of x, a wiki address (a string), a namespace or a function (a symbol), a schema key: the
  one place it is written, so it changes in one place: `doc` takes a wiki address, a schema key or a name (docs/agent.md#finding-your-way)."
  {:malli/schema [:=> [:cat [:or :string :symbol :keyword]] :string]}
  [x] (cond (string? x) (str "(doc \"" x "\")") (keyword? x) (str "(doc " x ")") :else (str "(doc '" x ")")))

(defn- source-block
  "Source text src as the model and the page read code: a block the printer fences verbatim."
  {:malli/schema [:=> [:cat :any] :any]}
  [src] [:pre [:code src]])

(defn- names-line
  "A line naming every symbol of xs, `Called by: a, b, c`; nothing for none, named by its label so a change of a name sends this line, not the part (docs/render.md#a-change-is-edits-by-name). Which names there are is the pull's (ctx's callers window)."
  {:malli/schema [:=> [:cat :string [:sequential :any]] :any]}
  [label xs]
  (when (seq xs) [:p {:key label} (str label ": " (str/join ", " (map str xs)))]))

(defn call-in
  "One real call of sym in source src: the first form opening `(sym ` or `(name `, as written (`seon.ctx.status/form-at`), or nil
  when src has none or it is only the function's name and bare symbols (that is its arglist, which the source above says). What a
  test or a caller that reaches a function shows of how it is called."
  {:malli/schema [:=> [:cat [:maybe :string] :qualified-symbol] [:maybe :string]]}
  [src sym]
  (when src
    (let [starts (keep #(str/index-of src %) [(str "(" sym " ") (str "(" sym ")") (str "(" (name sym) " ") (str "(" (name sym) ")")])
          call (when (seq starts) (status/form-at src (reduce min starts)))]
      (when (and call (not (re-matches #"\([^\s()\"]+(\s+[A-Za-z][\w\-?!*.<>=/]*)*\)" call)) (not (re-find #"[a-z]{3,} [a-z]{3,} [a-z]{3,}" (str/replace call #"\"[^\"]*\"" "")))) call))))

(defn- section-hiccup
  "A wiki section as pulled (its :seon.doc/_parent joined) at depth: what it is in a sentence, its heading and address, its text at that
  depth (its stored lead at :summary, its lead and the headings below it at :doc, its text whole at :code), and how to read the rest."
  {:malli/schema [:=> [:cat :map :seon.ref/depth] :any]}
  [{:seon.doc/keys [address heading lead text] below :seon.doc/_parent} depth]
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
  calls or copies: its signature, its doc and one real call taken from a test or a caller. Nothing of who calls it."
  {:malli/schema [:=> [:cat :qualified-symbol :map :boolean] :any]}
  [sym v changing?]
  (let [src (get-in v [:var/form :form/src])
        forms (vals (:callers v))
        defs-of (fn [f] (for [d (let [ds (:form/defines f)] (if (map? ds) (vals ds) ds)) :when (and (:var/name d) (not= sym (:var/name d)))] d))
        test? (fn [f] (some #(contains? (set (:var/meta %)) :test) (defs-of f)))
        tests (sort-by str (distinct (for [f forms :when (test? f) d (defs-of f) :when (contains? (set (:var/meta d)) :test)] (:var/name d))))
        call (some #(call-in (:form/src %) sym) (concat (filter test? forms) (remove test? forms)))
        doc (:var/doc v)]
    [:section
     [:h3 (str sym)]
     (if changing?
       (list [:p (if (contains? (set (:var/meta v)) :test) "A test your plan names." "A function your plan names.")]
             (if src (source-block src) (list (when doc [:p doc]) [:p "Read it: " [:code (str "(source " sym ")")]]))
             (when (seq tests) [:p {:key "tests that reach it"} (str (count tests) " tests reach it: " "(callers '" sym ") lists them.")]))
       (list [:p (if (contains? (set (:var/meta v)) :test) "A test your plan names." "A function your plan names.")]
             (when src [:p [:code (str (reader/arglists src (symbol (namespace sym))))]])
             (when doc [:p doc])
             (when call [:p "A real call: " [:code call]])
             (when (and (contains? (set (:var/meta v)) :test) src) (source-block src))))]))

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

(defn script
  "A script, as hiccup: what it's for and how to call it, in its own words (docs/source.md#a-program-file)."
  {:malli/schema [:=> [:cat :seon.render/opts :map] :any]}
  [_ {:file/keys [path] :seon.script/keys [says usage]}]
  [:section [:h3 path] (when (seq says) [:p says]) (when (seq usage) [:pre usage])])

(defn cite-hiccup
  "A cite as pulled (and filled) with its target joined, as one hiccup by what the target is: a wiki section (`section-hiccup`),
  a function (`var-hiccup`), a schema key or setting (`key-hiccup`), a namespace (its doc and how to read it), a task (its plan)
  or a file (how to read it). The page and the model read it alike; `doc` answers it on demand. See
  docs/references.md#an-entity-cites."
  {:malli/schema [:function [:=> [:cat :seon.host/db :map] :any] [:=> [:cat :seon.host/db :map :map] :any]]}
  ([db c] (cite-hiccup db c {}))
  ([db {depth :seon.cite/depth t :seon.cite/target} {:keys [lookups changing?]}]
   (cond
     (:seon.doc/address t) (section-hiccup t depth)
     (:seon.task/id t) [:section [:h3 (:seon.task/id t)] [:p "Another task your plan names."]
                        (when-let [pl (:seon.task/plan t)] (markdown pl))]
     (:var/name t) (var-hiccup (:var/name t) t (boolean changing?))
     (:schema/key t) (key-hiccup db (:schema/key t) t (or lookups []))
     (:ns/name t) [:section [:h3 (str (:ns/name t))] [:p (str "A namespace your plan names." (when (:ns/doc t) (str " " (:ns/doc t))))] [:p "Read it: " [:code (help-form (:ns/name t))]]]
     (:file/path t) [:section [:h3 (:file/path t)] [:p "A file your plan names."] [:p "Read it: " [:code (str "(slurp \"" (:file/path t) "\")")]]])))

(defn cite
  "Cite c as pulled with its target joined (the declared html render of :seon.cite/cite-fact), by `cite-hiccup`: a wiki section, a
  function, a schema key, a namespace, a task or a file, at the cite's depth; opts' :lookups and :changing? are what the placement
  knows of the agent's question (`cited`). A pulled section or var alone is not rendered by this: it prints as the data it is. See
  docs/references.md#an-entity-cites."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.db/db :seon.host/db]]] :any] :any]}
  [{db :seon.db/db lookups :lookups changing? :changing?} c]
  (cite-hiccup db (ratom/realize c) {:lookups (or lookups []) :changing? (boolean changing?)}))

(defn cited
  "One thing the agent's task or its needs cite (a component over ctx and the db), named by its target k: the cite at ctx path :at
  as ctx holds it, each entity it joined that ctx holds at another path filled from there (:held, e to that path, which the layout
  found), drawn by `cite-hiccup`. A part of its own, so a cite that changes is sent alone. See docs/references.md#an-entity-cites."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db :seon.host/db] [:at [:vector :any]] [:held [:map-of :int [:vector :any]]] [:lookups {:optional true} [:sequential :any]] [:changing? {:optional true} :boolean]]]] :any]}
  [{c :seon.ctx/ctx db :seon.db/db at :at held :held lookups :lookups changing? :changing?}]
  (when-let [x (get-in c at)]
    (cite {:seon.db/db db :lookups lookups :changing? changing?} (fill c held (ratom/realize x)))))

(defn citations
  "The heading of what the task and its needs cite (a component over ctx): what it is and why it is here. The cites follow it, each a
  `cited` part of its own (`cite-parts`). Nothing when there is none. See docs/references.md#an-entity-cites."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]]] :any]}
  [{c :seon.ctx/ctx}]
  (when (seq (page-cites c))
    [:section
     [:h2 "What your plan mentions"]]))

(defn- cite-parts
  "The `cited` components of the agent's page, one for each thing its task and its needs cite (`page-cites`). Each is told what
  the agent will do with it: a function in the task's own namespace or reached by its proofs is one it will change (its source and
  tests are drawn), any other one it calls (its signature and doc); and the lookup refs an attribute's real value is taken from
  (this task's own entity, and the functions the cites name)."
  {:malli/schema [:=> [:cat :map] [:vector :any]]}
  [c]
  (let [cs (page-cites c)
        task-ns (some-> (ratom/realize (get-in c [:seon.agent/task :seon.task/ns])) str)
        reach (into #{} (for [[_ v] (get-in c [:seon.agent/task :proved]) r (ratom/realize (get-in v [:var/form :form/refs]))] r))
        lookups (into [[:seon.task/id (ratom/realize (get-in c [:seon.agent/task :seon.task/id]))]]
                      (keep #(when (re-matches #"[^\s:/#]+/[^\s:#]+" %) [:var/name (symbol %)])) (map second cs))]
    (vec (for [[p k held] cs
               :let [sym (when (re-matches #"[^\s:/#]+/[^\s:#]+" k) (symbol k))]]
           [#'cited {:key k :at p :held held :lookups lookups
                     :changing? (boolean (and sym (or (contains? reach sym) (= task-ns (namespace sym)))))}]))))

(defn references
  "Where to look for more, beside the task (a component over ctx): the agent's own cites (what every agent cites, the config's
  :seon.config.agent/cites, and what it adds with my.agent/cite!), each as one line: what it is, what it answers (the section's
  stored lead) and the form that reads it. Not shown whole: an agent reads one when it needs it. Nothing when it has none."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]]] :any]}
  [{c :seon.ctx/ctx}]
  (let [ks (sort-by #(get-in c [:seon.cite/_of % :seon.cite/order]) (keys (:seon.cite/_of c)))
        held (held-whole c (for [k ks] [:seon.cite/_of k]))
        cites (for [k ks] (fill c held (ratom/realize (get-in c [:seon.cite/_of k]))))]
    (when (seq cites)
      [:section
       [:h2 "If you need more"]
       (into [:ul]
             (for [{t :seon.cite/target} cites :let [x (ref/target-value t)]]
               [:li (or (:seon.doc/heading t) (str x)) ": " (:seon.doc/lead t) " " [:code (help-form x)]]))])))

(defn searched
  "Search s (the declared html render of :seon.search/search-fact, a component over the db), drawn as one compact hiccup that the
  page shows and the model is sent as it is: its query searched on the branch's
  db by seon.ctx.search/search (exact words, meaning once its query has a vector, and the names it holds;
  functions, wiki sections and schema keys, no relevance cut), its :seon.search/k hits (else
  :seon.config.render/search's), each a line: its name (its first sentence added nothing a model used, at
  ~90 tokens a hit against ~10: docs/vector.md#search); the hits left out are not drawn (my.agent/search answers them);
  nothing until its query has a vector, since words alone match as weakly when nothing is to be found. A change of its query (a swap! on ctx)
  redraws this part alone, and the model is sent what changed of it
  (docs/render.md#the-render-is-an-eval). What it finds for a request is measured in
  docs/vector.md#what-the-work-is-likely-to-need-as-an-eval."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.db/db :seon.host/db]]] :any] :any]}
  [{db :seon.db/db} s0]
  (when-let [s (some-> s0 ratom/realize)]
    (let [q (:seon.search/query s)
          ;; the search fact's own vector, once the pipeline has embedded its query (seon.vector's due texts)
          e (pdb/eid db :seon.search/id (:seon.search/id s))]
      ;; drawn once its query has a vector, never by words alone: words alone found the same weak matches for a
      ;; request with nothing to find as for one with something (owner, 2026-11-08: wait for its vector)
      (when (and e (vector/vector-of db e))
        (let [;; its own count when it names one, else :seon.config.render/search's; the hits left out are not
              ;; drawn: a count that moves each turn would resend the line (my.agent/search answers them)
              hits (remove #(= :more (:kind %))
                           (search/search db q (cond-> {:kinds #{:var :section :schema} :min 0.0} (:seon.search/k s) (assoc :k (:seon.search/k s))) e))]
          ;; the task's own request is on the page already: the heading names it rather than repeating it
          [:section [:h3 (if (= q (:seon.task/plan (:seon.search/of (d/pull db [{:seon.search/of [:seon.task/plan]}] e)))) "search: this task's request" (str "search: " q))]
           (into [:ol] (for [{:keys [name]} hits] [:li [:code (str name)]]))])))))

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
        found (concat (join-paths [] [[:seon.agent/task] :seon.task/id]) (join-paths [] [[:seon.agent/task :seon.task/needs :*] :seon.task/id])
                      (join-paths [] [[:seon.agent/task :proved :*] :var/name]) (join-paths [] [[:seon.agent/task :proved :* :var/form] :form/id])
                      (join-paths [] [[:seon.agent/task :seon.task/needs :* :proved :*] :var/name]) (join-paths [] [[:seon.agent/task :seon.task/needs :* :proved :* :var/form] :form/id])
                      (for [p ps j cite-joins q (join-paths p j)] q))]
    (reduce (fn [m [q whole? id]] (if (and whole? (not (contains? m id))) (assoc m id q) m)) {} found)))

(defn- page-cites
  "The cites agent record c's page draws, read from ctx alone: its task's (:seon.cite/_of), then each need's (one
  level, a merge task's left out), then the agent's own, each target once at the strongest depth any asks,
  the task's proofs left out (the status shows them), in the order the cites were written. Each
  [path target held]: the cite's path, its target as text and where ctx holds each entity it joined whole
  (an entity is held once: docs/ctx.md#the-pattern)."
  {:malli/schema [:=> [:cat :map] [:vector [:tuple [:vector :any] :string [:map-of :int [:vector :any]]]]]}
  [c]
  (let [task (:seon.agent/task c)
        proofs (into (set (get task :seon.task/proofs)) (mapcat (fn [n] (get-in task [:seon.task/needs n :seon.task/proofs]))) (keys (:seon.task/needs task)))
        sorted (fn [p] (sort-by #(get-in c (conj p % :seon.cite/order)) (keys (get-in c p))))
        groups (concat (when task [[:seon.agent/task :seon.cite/_of]])
                       (for [n (sort-by #(str (get-in task [:seon.task/needs % :seon.task/id])) (keys (:seon.task/needs task)))
                             :when (not= :merge (get-in task [:seon.task/needs n :seon.task/kind]))] [:seon.agent/task :seon.task/needs n :seon.cite/_of])
                       [])
        paths (for [g groups k (sorted g)] (conj (vec g) k))
        ;; each entity a cite joined is held once: a target held at another path is read from there
        held (held-whole c paths)
        all (for [p paths :let [t (fill c held (ratom/realize (get-in c (conj p :seon.cite/target))))]]
              {:path p :id (:db/id t) :target (str (ref/target-value t)) :name (:var/name t) :rank (ref/strength (get-in c (conj p :seon.cite/depth)))})
        all (remove #(proofs (:name %)) all)
        by (group-by :id all)
        chosen (vec (for [t (distinct (map :id all))] (reduce (fn [a b] (if (> (:rank b) (:rank a)) b a)) (by t))))]
    (vec (for [{:keys [path target]} chosen] [path target held]))))

(def ^:private reason-words
  "What each of the done rule's reasons (seon.reconcile/done-explain) says to a person reading the page (docs/agent.md#what-you-read: `:refused`)."
  {:proof-red "proof red" :proof-unknown "proof has no verdict" :regression "green on main, red here"
   :stray-red "red test that is no proof" :contract-gap "functions without a contract" :unmerged "unmerged entries"
   :open-problem "problems to clear" :no-proofs "no proof registered" :no-design "plan unchanged since the run began"
   :behind "judged before main moved"})

(def ^:private state-words
  "What a proof's verdict says in the status table."
  {:pass "green" :fail "failed" :error "failed" :undefined "not written yet" :unknown "no verdict yet" :not-run "not run" :deferred "left to root's gate"})

(defn- as-text
  "A value of the status as one line of source: marked code as its text, a thrown error as its message, any other value by the printer."
  {:malli/schema [:=> [:cat :any] :string]}
  [x]
  (cond (instance? Throwable x) (str "threw " (ex-message x))
        (and (map? x) (:via x)) (str "threw " (:message (first (:via x))))
        :else (let [t (cond (and (map? x) (string? (:seon.print/code x))) (:seon.print/code x)
                            (string? x) x
                            :else (str/replace (print/text x) #"#object\[java\.util\.regex\.Pattern 0x[0-9a-f]+\]" "a regex"))]
                ;; a thrown error is the failure's got (clojure.test/is reports it): say what it threw, not its trace
                (if-let [m (when (str/starts-with? t "#error") (second (re-find #":message\s+\"([^\"]*)\"" t)))] (str "threw " m) t))))

(defn- form-hiccup
  "A form to run, as code: inline when it is one line, a block when it spans several. A `do` of forms is the forms, one after
  another at the top level, as an agent types them at its REPL (a `do` with `in-ns` is a habit that records nothing)."
  {:malli/schema [:=> [:cat :any] :any]}
  [form]
  (let [one #(if (string? %) % (print/text (cond-> % (instance? clojure.lang.IObj %) (vary-meta assoc :seon.print/full true))))
        t (if (and (seq? form) (= 'do (first form))) (str/join "\n" (map one (rest form))) (one form))]
    (if (str/includes? t "\n") [:pre [:code t]] [:code t])))

(defn status-hiccup
  "How the work stands (seon.ctx.status/status's data s), written to the agent at the moment it reads it. Each proof answers the
  question its state asks: not written, where it goes and a test to copy; red, what it asserted and what it got; every proof
  green and something blocks merging, exactly what and the form that clears it; all green and nothing blocks, that it does not.
  Main's side only when something conflicts: both sides and the form that settles each. No table beside the same words: a list of
  the proofs' states only when there are several. See docs/render.md#how-the-work-stands."
  {:malli/schema [:=> [:cat :seon.render/opts :map] :any]}
  [_opts {:keys [proofs refused test-run unmerged without-a-contract caused attention merge unjudged also-red-on-main main-moved] steps :next}]
  (let [state #(:status %)
        unwritten (filter #(= :undefined (state %)) proofs)
        red (filter #(#{:fail :error} (state %)) proofs)
        unknown (filter #(#{:unknown :not-run :deferred} (state %)) proofs)
        green? (and (seq proofs) (every? #(= :pass (state %)) proofs))
        blocks (cond-> []
                 (seq refused) (into (for [r refused :when (not (and (= :proof-unknown (:reason r)) (some #(= (:test r) (:test %)) unwritten)))]
                                       [(get reason-words (:reason r) (kname (:reason r))) (or (:test r) (:vars r) (:keys r) (:problems r)) (:do r)]))
                 (seq without-a-contract) (conj ["functions without a contract" without-a-contract nil])
                 (seq caused) (conj ["problems to clear" caused nil])
                 (seq attention) (into (for [a attention] [a nil nil])))
        ;; two reasons can say one thing (a refusal and the status's own): said once, with what clears it if either has it
        blocks (vals (reduce (fn [m [what x do :as b]] (let [o (get m [what x])] (if (and o (or (nth o 2) (not do))) m (assoc m [what x] b))))
                             (array-map) blocks))
        block (fn [[what x do]]
                (let [items (when (coll? x) (map #(as-text (if (map? %) (or (:key %) (:symbol %) %) %)) x))]
                  (if items
                    ;; each named on its own, so a change of one says that one (docs/render.md#a-change-is-edits-by-name)
                    [:div [:p (str what ":")]
                     (into [:ul {:key :items}] (for [i (distinct items)] [:li i]))
                     (when do [:p "To clear it: " (form-hiccup do)])]
                    [:p what (when (some? x) (str ": " x)) (when do [:span ". To clear it: " (form-hiccup do)])])))]
    [:section
     [:h2 "Your proofs"]
     (when test-run [:p "The tests could not run: " (str/join "; " (:failed test-run))])
     (when (next proofs)
       (into [:ul {:key :proofs}] (for [{:keys [test status]} proofs] [:li {:key test} [:code (str test)] ": " (get state-words status (kname status))])))
     ;; not written: where it goes, and a namespace's example once, its own, so a proof written moves neither
     (for [[n us] (group-by #(:ns (:shape %)) unwritten) :let [{:keys [requires helpers example]} (:shape (first us))]]
       (concat
        (for [[i {:keys [test]}] (map-indexed vector us)]
          [:div {:key test}
           [:p "Write " [:code (str test)] "." (when n (list " It goes in " [:code (str n)] "."))]
           (when (zero? i) (when-let [form (some #(when (= test (:test %)) (:do %)) refused)] [:div [:p "Scaffold:"] (form-hiccup form)]))])
        (when (or requires example)
          [[:div {:key n}
            (when requires [:p "A test in " [:code (str n)] " requires:"])
            (when requires [:pre [:code requires]])
            (when (seq helpers) [:p "Helpers a test there uses: " (str/join ", " (map #(str "`" (name %) "`") helpers)) ". Every helper there: " [:code (str "(dir " n ")")]])
            (when example [:p "Example:"])
            (when example [:pre [:code example]])]])))
     ;; red: which, what it asserted, what it got
     (for [{:keys [test failures]} red]
       [:div {:key test}
        [:p [:code (str test)] " fails."]
        (for [{a :assert as :asserts g :got m :message} (take 1 failures)]
          [:p "It asserts " [:code (str/join ", " (map #(as-text %) (or as [a])))] " and got " [:code (as-text g)] (when m (str " (" m ")")) "."
           (when (next failures) (str " " (count (rest failures)) " more " (if (nnext failures) "assertions fail" "assertion fails") "."))])])
     (when (seq unknown) [:p {:key :not-run} "Not run yet: " (str/join ", " (map #(str "`" (:test %) "`") unknown)) ". " [:code "(prove)"] " runs them."])
     ;; merge: nothing until something conflicts; then both sides and the form that settles each
     (when (seq unmerged)
       [:div {:key :unmerged}
        [:p "Main changed these too, so they are unmerged. Settle each: keep yours, or take main's."]
        (for [{:keys [key ours theirs]} unmerged]
          [:div {:key key} [:p [:code (str key)] ". Yours:"] [:pre [:code (as-text ours)]] [:p "Main's:"] [:pre [:code (as-text theirs)]]
           [:p "Keep yours: " [:code (str "(my.branch/resolve! '" key ")")] ". Take main's: " [:code (str "(my.branch/resolve! '" key " :theirs)")] "."]])])
     ;; the next step, from the task's data (seon.ctx.status/status :next): none blocks merging
     (for [{:keys [step from]} steps]
       (case step
         :fold-message [:p {:key :fold-message} "A message from " from " came after your plan last changed. If it changes what's wanted, write that into the plan and its proofs; if it's a question or a remark, answer it."]))
     (cond
       (and green? (empty? blocks) (empty? unmerged))
       [:p {:key :merging} "Every proof passes and nothing blocks merging."]
       (and green? (seq blocks))
       (into [:div {:key :merging} [:p "Every proof passes, but your branch can't merge yet:"]] (map block) blocks)
       (seq blocks)
       (into [:div {:key :merging} [:p "Also blocking merging:"]] (map block) blocks))
     (when main-moved [:p {:key :main-moved} main-moved])
     (when (seq also-red-on-main) [:p {:key :also-red-on-main} (str "Also red on main at your merge base: " (str/join ", " also-red-on-main) ".")])
     (when unjudged [:p {:key :unjudged} (str "Not judged, so these say nothing about your change: " (str/join ", " (:tests unjudged)) ". " (:why unjudged) ".")])
     (when (seq (:conflicts merge)) [:p (str (count (:conflicts merge)) " things conflict with main; they are listed above.")])]))

(defn status
  "How the agent's work on its task stands (a component over ctx and the db): the data seon.ctx.status/status makes of its branch db, drawn by
  `status-hiccup`, the one hiccup the page and the model read, the data kept with it for the turn that decides from it (seon.render/kept-of). It reads main's last content move from ctx only when the task merges itself (the merge view), so a
  move of main redraws it only then. See docs/render.md#how-the-work-stands."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db :seon.host/db]]]] :any]}
  [{c :seon.ctx/ctx db :seon.db/db}]
  (let [s (status/status db c {})] (with-meta (status-hiccup {} s) {:seon.render/kept s})))

(declare standing)

(defn turn
  "The namespace the agent's REPL is in (a component over ctx), as its heading, which is all the model is sent of it. See
  docs/render.md#the-agents-turn."
  {:seon.render/style ":is(.turn, .own-problems, .ctx-key) { border-radius: 0.375rem; background: var(--color-base-900); padding: 0.5rem; min-width: 0; overflow-x: auto; }" :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]]] :any]}
  [{c :seon.ctx/ctx}]
  (when-let [in-ns (ctx/current-ns c)]
    [:section [:h2 (str "You're in " in-ns ".")]]))

(defn agent-state
  "The agent's state for the person (a component over ctx and the db), nothing of it for the model: how its work stands
  (`standing`, from the task's outcome where it holds one), its task, the form it is running and its proofs green."
  {:seon.render/style ".agent-state {border-radius: 0.375rem; background: var(--color-base-900); padding: 0.5rem; min-width: 0; overflow-x: auto; }" :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db :seon.host/db]]]] :any]}
  [{c :seon.ctx/ctx db :seon.db/db}]
  (let [task (:seon.agent/task c)
        t (into {} (for [k [:seon.task/id :seon.task/outcome :seon.task/reason] :let [v (ratom/realize (get task k))] :when (some? v)] [k v]))
        agent (into {} (for [k [:seon.agent/outcome :seon.agent/ended-turn :seon.agent/turn :seon.agent/max-turns :seon.agent/reason :seon.agent/status]
                             :let [v (ratom/realize (get c k))] :when (some? v)] [k v]))
        stands (standing agent t)
        verdicts (for [[_ ps] (concat [[nil (:proved task)]] (map (fn [[_ nd]] [nil (:proved nd)]) (:seon.task/needs task))) [_ v] ps] (ratom/realize (:seon.test/status v)))
        running (d/q '[:find ?r . :in $ ?a :where [?p :seon.process/agent ?a] [?p :seon.process/running ?r]] db (:db/id c))]
    [:div {:wire/for "page"}
     [:p (str "● " (:text stands) (when (:detail stands) (str " · " (:detail stands))) (when (:reason stands) (str " · " (:reason stands))))]
     (when-let [id (:seon.task/id t)] [:p "task " (task-title id)])
     (when running [:p "running " [:code running]])
     (when (seq verdicts) [:p (str (count (filter #{:green} verdicts)) " of " (count verdicts) " proofs green")])]))

(defn own-problems
  "The open problems of the agent (a component over its branch db, seon.problems/own: its newest run's,
  its task's failure and the errors raised in its task), each class with its count and each of its rows a line, as `problems-model`
  makes them: its count, type, first line and the form that reads it whole. Nothing when none is open. See docs/render.md#components."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db :seon.host/db]]]] :any]}
  [{c :seon.ctx/ctx db :seon.db/db}]
  (let [r (problems/own db (:db/id c))]
    (when (seq (:classes r))
      (into [:section [:h2 "Your open problems"]]
            (for [[c {:keys [n rows]}] (:classes (problems-model {} r))]
              [:div {:key c} [:h3 (str (kname c) " " n)]
               (into [:ul]
                     (for [{:keys [count type message read open?]} rows]
                       [:li (str count " x") (when type [:code (str type)]) (when message (str " " message)) (when (false? open?) " (closed)") (when read [:code (print/text read)])]))])))))

(defn ctx-key
  "A key the agent added to its ctx (a component over ctx), named by that key, which is its schema:
  its value by the key's render. It reads schemas in the walk's entity index (:seon.render/index) for the values it draws through render/hiccup: one render running another, the interim stage 2a of docs/users.md removes."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]] :qualified-keyword] :any]}
  [{c :seon.ctx/ctx ix :seon.render/index} k]
  [:section
   [:h2 {:class "kicker"} (kname k)]
   (render/hiccup {:seon.render/index ix :schema k} (ratom/realize (get c k)))])

(defn- added-keys
  "The keys the agent added to ctx c: any qualified key outside its own :seon.agent/* attributes,
  its pattern, the reverse joins its pattern holds (:seon.eval/_agent: an attribute read from the entity it points at) and
  the environment, in order. It reads ctx's own key set only."
  {:malli/schema [:=> [:cat :map] [:sequential :qualified-keyword]]}
  [c] (filter #(and (qualified-keyword? %) (not (#{"seon.agent" "db" "seon.ctx"} (namespace %))) (not (str/starts-with? (name %) "_"))) (sort-by str (keys c))))

(defn ctx-keys
  "The keys the agent added to its ctx (a component over ctx, placed when there is one), each a
  component of its own (ctx-key). It reads ctx's own key set, so it runs again when ctx's own
  attributes change, and its output is the same unless a key came or went."
  {:seon.render/style ".ctx-keys { display: flex; flex-direction: column; gap: 0.5rem; }" :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]]] :any]}
  [{c :seon.ctx/ctx}]
  [:div
   (for [k (added-keys c)] [#'ctx-key {} k])])

(declare eval-output eval-glance)

(defn eval-hiccup
  "Eval e of the conversation as a step of the chat and a call of the model's, one hiccup (docs/render.md#the-models-context-a-cached-prefix-then-the-turn): one quiet line, a mark only when it threw, what it set out
  to do (its intent as Markdown, else its code's first line); opened, its code and what the person alone wants of it (`eval-output`, a component the model does not read). The model reads the element as its call, by
  its marks: the id its wire goes by, its intent and code, then what came of it, the record itself as its, which the one printer prints when the model's reading reaches it; one with no intent and no call id of the model's is one user message
  {:by :code :response}. Seon's own is the model's alone, its code and no intent. opts :at, its path in ctx, for the person's components (an example's has none: its line and its code); :key and :agent, the agent's, whose page posts that
  it was opened (root's posts nothing). Its <details> carries its handle's anchor (eval-<id>). See docs/render.md#the-page."
  {:malli/schema [:=> [:cat :map :map] :any]}
  [{:keys [at key agent db]} {:seon.eval/keys [id by code intent call-id opened] :as e}]
  (let [mark (wire/result-mark db e)
        {:keys [ex value]} (:seon.eval/response (:record mark))
        err (or ex (when (contract/error? (ratom/realize value)) value))
        seon? (= "seon" by)
        said? (not (str/blank? intent))
        called? (or call-id said?)]
    [:li (cond-> (if called?
                   {}
                   {})
           true (assoc)
           seon? (assoc))
     [:details (cond-> {:class "seon-eval" :id (str "eval-" id)} opened (assoc :open true))
      ;; opening it is a fact on main (seon.root/opened!), which the agent takes before it draws again
      (into (if key [:summary {:seon/call 'seon.root/opened! :seon/agent agent :seon/args {:key key :eval id}}] [:summary])
            (remove nil?)
            [(when err [:span {:class "shrink-0 text-error"} "⚠"])
             (if said?
               ;; the agent's message is Markdown, rendered (owner, 2026-10-29); the model reads it as written
               [:div {:class "seon-text seon-md-inline min-w-0 flex-1"} [#'markdown intent {:inline? true}]]
               [:code {:class "seon-form min-w-0 flex-1 truncate"} (first (str/split-lines (or code "")))])])
      ;; inside the closed details, so opening it shows it at once, on a live agent's page and on one root draws from its branch
      [:div {:class "ml-4 flex min-w-0 max-w-full flex-col gap-1 overflow-x-auto py-1"}
       ;; the code as the agent wrote it, whole, highlighted
       [:div {:class "min-w-0"} [#'emit/marked-hiccup :seon.print/code (or code "")]]
       (when at [#'eval-output {:key id :at at :agent agent :wire/for "page"}])]]
     (when at [#'eval-glance {:key id :at at :wire/for "page"}])]))

(defn eval-item
  "An eval of the conversation (a component over ctx and the db), its one hiccup (`eval-hiccup`) (owner: \"I
  want the chat to look like a nice chat screen with just text unless I dig into it\"; \"show the eval intent markdown
  descriptions and collapse the eval statement and the output by default\"), the eval at ctx path :at; drawn open when a reader opened it
  (:seon.eval/opened, a fact the agent takes before it draws its page). See docs/render.md#the-page."
  {:seon.render/style ".eval-item > details > summary { display: flex; min-width: 0; align-items: baseline; column-gap: 0.5rem; }" :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db :seon.host/db] [:at [:vector :any]]]]] :any]}
  [{c :seon.ctx/ctx db :seon.db/db at :at :as opts}]
  (when-let [e (ratom/realize (get-in c at))]
    (eval-hiccup {:at at :key (:agent-key opts) :agent (:agent-id opts) :db db} e)))

(defn- eval-shown
  "Of the eval at ctx path at, what the person's pieces read: not whether it is open, so opening it runs the line alone. Its response
  whole (seon.db/response-of), its result read from db."
  {:malli/schema [:=> [:cat :map :seon.host/db [:vector :any]] [:maybe :map]]}
  [c db at]
  (when (get-in c (conj at :seon.eval/id))
    (let [e (into {} (for [k [:seon.eval/id :seon.eval/code :seon.eval/response :seon.eval/result :seon.eval/schema :seon.eval/basis]
                            :let [v (ratom/realize (get-in c (conj at k)))] :when (some? v)] [k v]))]
      (-> e (assoc :seon.eval/response (pdb/response-of db e)) (dissoc :seon.eval/result)))))

(defn eval-glance
  "What came of an eval, in the chat with no click (a component over ctx and the db, for the page alone): its value through the one render (render/hiccup: the render it has, else the value printer), whole once its step is opened; or, closed, what went wrong on a line, the opened step having the rest. See docs/render.md#a-value-draws-itself. It reads schemas in the walk's entity index (:seon.render/index) for the values it draws through render/hiccup: one render running another, the interim stage 2a of docs/users.md removes."
  {:seon.render/style ".eval-glance { margin: 0.25rem 0 0.25rem 1rem; min-width: 0; overflow-x: auto; font-size: 0.75rem; }\n.eval-item:has(> details[open]) .eval-glance-line { display: none; }" :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db :seon.host/db] [:at [:vector :any]]]]] :any]}
  [{c :seon.ctx/ctx db :seon.db/db at :at ix :seon.render/index}]
  (when-let [e (eval-shown c db at)]
    (let [{:keys [ex value handle out err warnings]} (:seon.eval/response e)
          r (:seon.eval/response e)
          v (ratom/realize value)
          k (:seon.eval/schema e)
          failed (or (ratom/realize ex) (when (contract/error? v) v))
          opened? (ratom/realize (get-in c (conj at :seon.eval/opened)))
          budget (if opened? (inc (render/tokens-of ix v)) (config/value :seon.config.render/value-budget))
          whole (cond-> v (and opened? (instance? clojure.lang.IObj v)) (vary-meta assoc :seon.print/full true))]
      (cond
        failed [:div [:span {:class "eval-glance-line text-error"} (print/line (or (:seon.error/message failed) failed) (config/value :seon.config.render/line-budget) nil)]]
        ;; a nil beside output is the output's whole answer (clojure.repl/source prints and answers nil)
        (and (contains? r :value) (not (and (nil? v) (or (ratom/realize out) (ratom/realize err) (seq (ratom/realize warnings))))))
        [:div (render/hiccup (cond-> {:seon.render/index ix :budget budget :handle handle :db db} k (assoc :as :block :schema k)) whole)]))))

(defn- step-tools
  "An opened step's buttons, for a live agent (the page's stylesheet shows them only beside its REPL's input, so no step is drawn again as its status moves): run it again in its place,
  run the agent's evals down to it, edit its code and run that, copy its code, go back to it. Each is a call of root's table (seon.root/request's again! and back!). See docs/turn.md#an-eval-can-be-run-again."
  {:malli/schema [:=> [:cat :any :map] :any]}
  [agent {:seon.eval/keys [id code]}]
  (let [button "rounded border border-base-700 px-2 py-0.5 hover:border-text-400"
        call (fn [args] {:seon/call 'seon.root/again! :seon/agent agent :seon/args (assoc args :eval id)})]
    [:div {:class "seon-step-tools flex flex-wrap items-baseline gap-1 text-2xs"}
     [:button (merge {:type "button" :class button :title "Run this again in its place. Its code runs for real, and the model reads the new result."} (call {})) "▶ Run"]
     [:button (merge {:type "button" :class button :title "Run the agent's steps from the start down to this one, stopping at the first that fails. Their code runs for real."} (call {:to-here true})) "⏭ To here"]
     [:button {:type "button" :class button :title "Copy its code" (keyword "data-on:click") "navigator.clipboard.writeText(el.closest('.seon-eval').querySelector('pre code').textContent)"} "⧉ Copy"]
     ;; what a person is typing is theirs, not the page's: a new eval landing morphs the list, and would close this.
     ;; what a person is typing stays, for this process: another one (seon.root/back!) is another element, whose calls reach it
     [:details {:id (str "step-edit-" (name agent) "-" id) :class "w-full" :data-ignore-morph ""}
      [:summary {:class "cursor-pointer text-text-400"} "✎ Edit"]
      [:form (merge {:class "mt-1 flex flex-col gap-1"} (call {}))
       [:textarea {:name "code" :rows 6 :spellcheck "false" :class "w-full font-mono text-xs"} code]
       [:button {:class (str button " self-start")} "Run this instead"]]]
     [:details {:id (str "step-back-" (name agent) "-" id) :class "w-full" :data-ignore-morph ""}
      [:summary {:class "cursor-pointer text-text-400"} "↩ Back to here"]
      [:div {:class "mt-1 flex flex-col gap-1 text-text-400"}
       [:p "The agent goes back to just after this step, and everything after it leaves this chat. Nothing is lost: the later steps stay on the old branch. Values it kept in memory don't come back on their own; To here runs its steps again to rebuild them. If it was working on a task, it waits here until you send it a message."]
       [:button (merge {:type "button" :class (str button " self-start")} {:seon/call 'seon.root/back! :seon/agent agent :seon/args {:eval id}}) "Go back"]]]]))

(defn eval-output
  "What came of an eval as the person reads it when it is opened (a component over ctx and the db; the model reads the same response as the one printer prints it, `seon.ctx.wire/tool-text`): how long it ran, what it costs the context in tokens and its handle, what its forms printed, then its error, or its
  value by the render its record names. See docs/render.md#cost-is-shown-one-way. It reads schemas in the walk's entity index (:seon.render/index) for the values it draws through render/hiccup: one render running another, the interim stage 2a of docs/users.md removes."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db :seon.host/db] [:at [:vector :any]]]]] :any]}
  [{c :seon.ctx/ctx db :seon.db/db at :at agent :agent ix :seon.render/index}]
  (when-let [e (eval-shown c db at)]
    (let [{:keys [ex value handle out warnings ms] errout :err} (:seon.eval/response e)
          v (ratom/realize value)
          ;; what its forms printed (*out*, *err*: seon.repl/response keeps each as seon.print/code), drawn before its value as a REPL shows it
          printed (into [] (keep (fn [[label k s]] (when-let [s (ratom/realize s)] [label k s]))) [["out" :out out] ["err" :err errout]
                                                                                                           ;; what its definitions warn (seon.repl's :warnings), in the words the model read
                                                                                                           ["note" :warnings (not-empty (str/join " " (keep :seon.repl/fix (ratom/realize warnings))))]])
          ;; an error answered as a value (a function that returns its error) is a failure too
          err (or ex (when (contract/error? v) v))
          ;; opened, it is whole, so there is nothing further to open: the budget is what the text costs
          opened? (ratom/realize (get-in c (conj at :seon.eval/opened)))
          budget (fn [x] (if opened? (inc (render/tokens-of ix x)) (config/value :seon.config.render/value-budget)))
          ;; a value prints whole by the printer's own mark
          whole (fn [x] (cond-> x (and opened? (instance? clojure.lang.IObj x)) (vary-meta assoc :seon.print/full true)))]
      [:div
       (when agent (step-tools agent e))
       [:div {:class "flex items-baseline justify-end gap-2 text-2xs text-text-500"}
        (when ms [:span {:class "seon-ms"} (ms-str ms)])
        [:span (print/tokens-text (+ (render/tokens-of ix (:seon.eval/code e)) (render/tokens-of ix (:seon.eval/response e))))]
        [:code {:class "seon-handle"} (str handle)]]
       ;; each by the one render under the value's budget; a cut reads the rest through its key of the handle
       (for [[label k s] printed]
         [:div {:class "seon-row"} [:span {:class (if (#{:err :warnings} k) "text-warning" "muted")} label]
          (render/hiccup {:seon.render/index ix :budget (budget s) :handle (when handle (list k handle))} (whole s))])
       (when err
         [:div {:class "text-error"} "✗ " (:seon.error/message err)
          (render/hiccup {:seon.render/index ix :budget (budget err) :handle handle} (whole (dissoc err :seon.error/message)))])])))

(defn proved
  "A prove's run as its eval's tile draws it (the declared html render of :seon.test/proved, from
  the eval's value): its one line (the schema's model render, my.agent/prove-summary), then each proof's
  status, each red test's first failing assertion by the one printer, and each test whose child died. It reads schemas in the walk's entity index (:seon.render/index) for the values it draws through render/hiccup: one render running another, the interim stage 2a of docs/users.md removes."
  {:malli/schema [:=> [:cat :seon.render/opts :map] :any]}
  [opts r]
  (let [;; its reports only when it still holds them: an agent's select-keys of a prove keeps the schema
        run (when (:reports r) r)
        failures (some-> run proofs/failures)
        died (into (sorted-set-by #(compare (str %1) (str %2))) (some-> run proofs/died))]
    [:section [:h2 "prove"]
     ;; its one line, by the one printer's hiccup: a marked text is drawn as itself, never its map
     (render/hiccup {:seon.render/index (:seon.render/index opts) } (:value (render/model (:seon.render/index opts) (with-meta r {:seon.render/schema :seon.test/proved}))))
     (when (seq (:proofs r))
       [:ul (for [[p s] (sort-by (comp str key) (:proofs r))] [:li [:code (str p)] " " (name s)])])
     (for [[t fs] (sort-by (comp str key) failures) :when (not (died t))]
       [:div [:p [:code (str t)]] (render/hiccup {:seon.render/index (:seon.render/index opts) } (first fs))])
     (when (seq died) [:p "died: " (for [t died] [:code (str t) " "])])]))

(defn- id-set
  "The keys of join m (a member map of ctx, read through its view) as a set: one read of its key set, its
  members not each made a view as a walk of its entries makes them (seon.ratom)."
  {:malli/schema [:=> [:cat [:maybe :map]] [:set :any]]}
  [m] (cond (nil? m) #{} (instance? java.util.Map m) (set (.keySet ^java.util.Map m)) :else (set (keys m))))

(declare stream-container)

(defn reply-hiccup
  "A model turn's reply and the steps it holds (children) as the chat shows it and the model reads it, one hiccup: what the agent said, in Markdown, its thinking folded, then each eval it ran. What the
  model is sent of it is what it said and its reasoning as returned (a reply cut at the token limit is no message unless its calls were kept: called?). Nothing to say and nothing run, nothing. See
  docs/render.md#the-models-context-a-cached-prefix-then-the-turn."
  {:malli/schema [:=> [:cat :map :boolean [:sequential :any]] :any]}
  [{:seon.model-call/keys [id n origin reply reasoning truncated] :as call} called? children]
  (let [spoke? (not (str/blank? reply))
        sent? (and (= :model origin) (or called? (and (not truncated) spoke?)) (or spoke? (some? reasoning)))]
    ;; in flight (the model's call until its evals have all run: :seon.model-call/forms or its error), one element its stream's
    ;; parts are appended into, kept as it is through the turn's redraws (data-ignore-morph: the reply's commit leaves an eval
    ;; still running its intent, code and output) and gone at the redraw that no longer draws it; the model never reads it
    ;; (docs/render.md#in-flight-output-streams)
    (when (or spoke? (not (str/blank? reasoning)) (seq children) (::in-flight call))
      (cond->
      [:section (cond-> {:class "flex w-full flex-col gap-1"} (pos? (or n 0)) (assoc))
       (if (str/blank? reasoning)
         (when (and sent? (some? reasoning)) [:span {} reasoning])
         [:details [:summary {:class "text-xs text-text-500" :wire/for "page"} "thinking"]
          [:div {} [#'markdown reasoning {:inline? true}]]])
       (when spoke? [:div {:class "seon-message max-w-[85%] self-start rounded-2xl rounded-tl-sm bg-base-900 px-4 py-2.5 text-text-100"} [#'markdown reply {:inline? true}]])
       (when (seq children) [:ol {:class "seon-turn flex flex-col"} (seq children)])]
        (::in-flight call) (conj (stream-container id []))))))


(defn stream-part
  "One part of an effect's in-flight text, part one of reasoning, content, message, intent, code, out or err: the element the
  web proc draws in its call's (or eval's) in-flight element (stream-container) with all its text so far (its id the
  stream's, seon.render/key-id of [id]). See docs/render.md#in-flight-output-streams."
  {:seon.render/style ".stream-part { white-space: pre-wrap; overflow-wrap: anywhere; } .stream-part[data-stream-part=\"reasoning\"] { color: var(--color-text-500); font-size: 0.75rem; } .stream-part[data-stream-part=\"intent\"] { color: var(--color-text-400); } .stream-part:is([data-stream-part=\"code\"], [data-stream-part=\"out\"], [data-stream-part=\"err\"]) { font-family: var(--font-mono); font-size: 0.75rem; background: var(--color-base-900); border-radius: 0.375rem; padding: 0.25rem 0.5rem; } section:has(> .seon-message, > details) > .seon-stream > .stream-part:is([data-stream-part=\"message\"], [data-stream-part=\"content\"], [data-stream-part=\"reasoning\"]) { display: none; } .stream-part[data-stream-part=\"err\"] { color: var(--color-error); } .seon-stream[aria-busy=\"true\"]::after { content: \"▍\"; animation: seon-stream-cursor 1s steps(2) infinite; } @keyframes seon-stream-cursor { 50% { opacity: 0; } }"
   :malli/schema [:=> [:cat :string :string :string] :any]}
  [id part text]
  [:div {:id (render/key-id [id]) :class "stream-part" :data-stream-part part} text])

(defn stream-container
  "Entity in's in-flight element with its parts, [[id part text] ...] (stream-part each): busy, kept as it is through the
  page's redraws (data-ignore-morph) and found by the web proc (data-stream-in), for the page alone. The turn draws it
  empty while its call is in flight (reply-hiccup); the web proc draws it whole, the text so far, in its place at each
  message. See docs/render.md#in-flight-output-streams."
  {:malli/schema [:=> [:cat :string [:sequential [:tuple :string :string :string]]] :any]}
  [in parts]
  (into [:div {:id (render/key-id [in]) :class "seon-stream" :aria-busy "true" :data-ignore-morph "" :data-stream-in in :wire/for "page"}]
        (for [[id part text] parts] (stream-part id part text))))

(defn chat-ask
  "What was asked, the chat's first message, a person's on the right (a component over ctx): the
  task's plan as it accumulated: its start, then the whole as Markdown when asked, so a long plan leaves the
  conversation to read. The model reads the plan in the task's part. See docs/render.md#the-page."
  {:seon.render/style ".chat-ask { max-width: 85%; align-self: flex-end; border-radius: 1rem; border-top-right-radius: 0.125rem; background: var(--color-base-800); padding: 0.625rem 1rem; color: var(--color-text-100); }" :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]]] :any]}
  [{c :seon.ctx/ctx}]
  (when-let [plan (some-> (:seon.agent/task c) ratom/realize :seon.task/plan)]
    (let [{:keys [shown truncated? omitted lines]} (print/plain plan (config/value :seon.config.render/line-budget))]
      [:section
       (if truncated?
         ;; the start, or the whole in its place, never both
         [:details {:class "group seon-message"}
          [:summary {:class "list-none"}
           [:div {:class "group-open:hidden"} (markdown shown {:inline? true})]
           [:span {:class "text-xs"} [:span {:class "muted group-open:hidden"} (str (emit/cut-words omitted lines nil) " ")] [:span {:class "underline group-open:hidden"} "Read all"] [:span {:class "hidden underline group-open:inline"} "Show less"]]]
          (markdown plan {:inline? true})]
         [:div {:class "seon-message"} (markdown plan {:inline? true})])])))

(defn message-hiccup
  "A message to the agent as the chat shows it and the model reads it, one user message: who sent it and its body. See docs/render.md#the-page."
  {:malli/schema [:=> [:cat :map] :any]}
  [{:seon.message/keys [id from body]}]
  [:section {}
   [:p {:class "seon-note"} (str from)]
   [:div {} [#'markdown (str body) {:inline? true}]]])

(defn chat-message
  "A message to the agent (a component over ctx, the message at ctx path :at), a person's on the right: `message-hiccup`. See docs/render.md#the-page."
  {:seon.render/style ".chat-message { max-width: 85%; width: fit-content; margin-left: auto; border-radius: 0.5rem; background: var(--color-base-800); padding: 0.5rem 0.75rem; font-size: 0.875rem; line-height: 1.25rem; color: var(--color-text-100); }" :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:at [:vector :any]]]]] :any]}
  [{c :seon.ctx/ctx at :at}]
  (let [m (get-in c at)]
    (message-hiccup {:seon.message/id (ratom/realize (:seon.message/id m)) :seon.message/from (ratom/realize (:seon.message/from m)) :seon.message/body (ratom/realize (:seon.message/body m))})))

(defn chat-end
  "How the work ended, the chat's last card (a component over ctx and the db): the end its agent wrote (:seon.agent/outcome and
  the rest, while the agent is still at the turn it ended on: a wake that took another turn has no end yet), the turns it
  took, done or why not; while it works, how the agent is. See docs/render.md#the-page."
  {:seon.render/style ".chat-end { align-self: center; border-radius: 0.75rem; border: 1px solid var(--color-base-800); padding: 0.5rem 1rem; font-size: 0.875rem; line-height: 1.25rem; }" :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db :seon.host/db]]]] :any]}
  [{c :seon.ctx/ctx db :seon.db/db}]
  (let [outcome (ratom/realize (:seon.agent/outcome c))
        ;; an end is its agent's while the agent is at the turn it ended on
        end? (and outcome (= (ratom/realize (:seon.agent/ended-turn c)) (or (ratom/realize (:seon.agent/turn c)) 0)))]
    (if end?
      (let [turns (ratom/realize (:seon.agent/ended-turn c))
            reason (ratom/realize (:seon.agent/reason c))]
        [:p {:class (if (= :green outcome) "text-success" "text-text-100")}
         (str (case outcome :green "✓ It finished its work" :waiting "Waiting for you" :cap "Stopped at its turn limit" (str "Ended: " (kname outcome)))
              (when (pos? turns) (str (if (= :green outcome) " in " " after ") turns (if (= 1 turns) " turn" " turns")))
              (when (and reason (not (#{:green :waiting} outcome))) (str " · " reason)))])
      (when-let [status (ratom/realize (:seon.agent/status c))]
        [:p {:class "muted"} (case status :busy "working…" (kname status))]))))

(defn story-rows
  "Each turn's reading of a proof, [n status failures first-failure] in order (status nil: not run yet; :gone: its commit was swept), as the runs of equal readings: {:from :to :status :failures :first}, so a change is a row and a stretch of the same is one. See docs/render.md#the-page."
  {:malli/schema [:=> [:cat [:sequential [:tuple :int :any :int :any]]] [:vector :map]]}
  [turns]
  (reduce (fn [acc [n status failures fst]]
            (let [p (peek acc)]
              (if (and p (= [status failures fst] [(:status p) (:failures p) (:first p)]))
                (conj (pop acc) (assoc p :to n))
                (conj acc {:from n :to n :status status :failures failures :first fst}))))
          [] turns))

(defn proof-story
  "How each proof of an agent's task went across its turns (a component over ctx and the db), once its work has ended: read from the commit each turn's basis names, where the test's status and failures stood, so it is what the agent read then and nothing is recorded for it. A row for each change. The person's: the model read each as it came. See docs/render.md#the-page. It reads schemas in the walk's entity index (:seon.render/index) for the values it draws through render/hiccup: one render running another, the interim stage 2a of docs/users.md removes."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db :seon.host/db]]]] :any]}
  [{c :seon.ctx/ctx db :seon.db/db ix :seon.render/index}]
  (let [proofs (ratom/realize (get (:seon.agent/task c) :seon.task/proofs))
        outcome (ratom/realize (:seon.agent/outcome c))
        turn (or (ratom/realize (:seon.agent/turn c)) 0)]
    (when (and (seq proofs) outcome (= (ratom/realize (:seon.agent/ended-turn c)) turn))
      (let [calls (sort-by first (d/q '[:find ?n ?b :in $ ?a :where [?c :seon.model-call/agent ?a] [?c :seon.model-call/n ?n] [?c :seon.model-call/basis ?b]] db (:db/id c)))
            commits (for [[n b] calls] [n (d/commit-as-db (:store db) (:commit b))])]
        [:details {:class "self-stretch"}
         [:summary {:class "muted"} "How the proof went"]
         (for [p (sort-by str proofs) :let [e (:db/id (d/entity db [:var/name p]))]]
           [:div {:class "mt-1"}
            [:code p]
            [:ul {:class "ml-2"}
             (for [{:keys [from to status failures first]} (story-rows (vec (for [[n at] commits
                                                                                   :let [t (when (and at e) (d/entity at e))
                                                                                         fs (:seon.test/failures t)]]
                                                                               [n (if at (:seon.test/status t) :gone) (count fs) (some-> (clojure.core/first fs) (select-keys [:message :expected :actual]))])))]
               (let [line (str (if (= from to) (str "turn " from) (str "turns " from "–" to)) " · "
                               (case status nil "not run yet" :gone "its commit was swept" (kname status))
                               (when (pos? failures) (str " · " failures (if (= 1 failures) " failure" " failures"))))]
                 [:li
                  (if first
                    [:details [:summary {:class "cursor-pointer text-text-400"} line " " [:code (print/line (or (:actual first) (:message first)) (config/value :seon.config.render/line-budget) nil)]]
                     (render/hiccup {:seon.render/index ix } first)]
                    [:span {:class "text-text-400"} line])]))]])]))))

(defn system-message
  "The agent's system message (a component over ctx and the db): the doc sections it links (:seon.agent/system), else the system prompt (seon.ctx.wire/agent-system-text), for the person a fold. See
  docs/render.md#the-models-context-a-cached-prefix-then-the-turn."
  {:seon.render/style ":is(.system-message, .example-session, .proof-story) {font-size: 0.75rem; line-height: 1rem; }" :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db :seon.host/db]]]] :any]}
  [{c :seon.ctx/ctx db :seon.db/db}]
  [:details {}
   [:summary {:class "muted" :wire/for "page"} "system message"]
   [#'markdown (wire/agent-system-text db c)]])

(defn example-session
  "Example session k (a component over the db, named by its key), as the model reads it and, folded, the person: its opening, what it asked, its records by the same hiccup as the agent's own, drawn as of its last t (the session never changes after it). See
  docs/tasks.md#a-task-names-example-sessions."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.db/db :seon.host/db] [:key :string]]]] :any]}
  [{db0 :seon.db/db k :key ix :seon.render/index}]
  (let [t (session/example-t db0 k)
        db (d/as-of db0 t)
        c (ctx/agent-ctx ix db (seon.db/eid db :seon.agent/key k))
        placed (ctx/placed-messages db c)]
    (into [:details {}
           [:summary {:class "muted" :wire/for "page"} (str "example session " k)]
           [:p {} (session/example-open k (:seon.agent/description c))]]
          (concat
            (for [[n cid] (ctx/turns c)
                  :let [messages (get placed n)
                        evals (map first (ctx/turn-evals c cid))
                        model? #(get-in c [:seon.eval/_agent % :seon.eval/call-id])
                        step #(eval-hiccup {:db db} (get-in c [:seon.eval/_agent %]))]]
              (into [:div {}]
                    (concat (for [[m] messages] (message-hiccup (get-in c [:seon.message/_to m])))
                            [(reply-hiccup (get-in c [:seon.model-call/_agent cid]) (boolean (seq (filter model? evals))) (map step (filter model? evals)))]
                            (map step (remove model? evals)))))
            [[:p {} "</session>"]]))))

(defn session-examples
  "The example sessions the agent reads before its own (a component over ctx and the db), each a component: `example-session`. See docs/tasks.md#a-task-names-example-sessions."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db :seon.host/db]]]] :any]}
  [{c :seon.ctx/ctx db :seon.db/db}]
  (when-let [ks (seq (session/session-examples db c))]
    (into [:div] (for [k ks] [#'example-session {:key k}]))))

(declare agent-parts conversation-list tiles)

(defn render-ctx
  "The render of an agent's ctx, the one tree the model and the person read (the declared html
  render of :seon.render/render-ctx, which an agent overrides on its branch like any render): components placed in its
  lists, each keyed by what it shows, each holding what the agent's pull holds, so a smaller pattern is a smaller tree. The agent's page parts (p-task, `agent-parts`, given the pull), which the model reads as
  parts; and the conversation (c-rows, `conversation-list`), which it reads as messages. Each
  list is a component of its own, and the layout reads nothing, so a change of one list's members reruns that list alone: an eval
  added never reruns the task's. See docs/render.md#the-page and docs/render.md#a-layout-places-its-lists."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]]] :any]}
  [{c :seon.ctx/ctx}]
  [:div {:id "render-ctx"} [#'agent-parts {} c] [#'conversation-list {}]
   [#'rules-list {}]])

(declare sessions)

(defn agent-parts
  "Agent pull c's page parts, all of its page but its conversation (p-task, read by the model as parts: seon.render/page-lists),
  in the order they are placed here: the task, its proofs, what it cites, each search, the references its agent cites, its added
  keys, and last its own problems, its turn and its state; what a turn's diff compares. A piece is here when the pull holds what it
  shows; :seon.ctx/pattern is what it reads of the agent, so a past point's parts are pulled by that alone (its added keys are
  the agent's own pattern's, which a point's pull by this one leaves out). See docs/render.md#the-page."
  {:seon.render/list "p-task"
   :seon.ctx/pattern [:seon.agent/key :seon.agent/status :seon.agent/outcome :seon.agent/reason :seon.agent/turn :seon.agent/max-turns
                      :seon.agent/ended-turn :seon.agent/ns {:seon.agent/task :seon.task/task-fact} {:seon.cite/_of :seon.cite/cite-fact}
                      {:seon.search/_of :seon.search/search-fact} {:seon.message/_to [:seon.message/id :seon.message/from]}]
   :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]] :any] :any]}
  [_ c]
  [:div {:id "p-task"}
   ;; root's sessions, when this record's pull holds root (root's session's pattern does: docs/users.md#roots-session)
   (when (:root c) [#'sessions {:at [:root]}])
   (when (:seon.agent/task c)
     (list [#'task {:at [:seon.agent/task]}] [#'status {:at [:seon.agent/task :proved]}] [#'citations {:at [:seon.agent/task :seon.cite/_of]}]
           (seq (cite-parts c))
           ;; each search its task and the agent hold, a part each, drawn on the branch's db
           (for [p (concat (for [e (keys (get-in c [:seon.agent/task :seon.search/_of]))] [:seon.agent/task :seon.search/_of e])
                           (for [e (keys (get c :seon.search/_of))] [:seon.search/_of e]))]
             (get-in c p))))
   (when (seq (:seon.cite/_of c)) [#'references {:at [:seon.cite/_of]}])
   (when (seq (added-keys c)) [#'ctx-keys {:at [:db/id]}])
   (when (:seon.agent/task c) [#'own-problems {:at [:seon.agent/task]}])
   [#'turn {:at [:seon.agent/ns]}]
   [#'agent-state {:at [:seon.agent/status] :wire/for "page"}]])

(defn conversation-turn
  "Turn :key as the chat shows it and the model reads it, found by its model call's id, one seek (agent -> call -> its records, so a record of another turn touches nothing this read and an earlier
  turn never runs again): the messages it took (:messages, [[m id]] as the list placed them: `seon.ctx/placed-messages`), its reply holding the evals it ran, and the evals of other hands after it, each eval and message a component of its own. A turn whose call is not made yet (the one now opening)
  holds the messages delivered for it alone. See docs/render.md#the-page."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db :seon.host/db] [:key :int] [:messages {:optional true} [:sequential [:tuple :int :string]]]]]] :any]}
  [{c :seon.ctx/ctx db :seon.db/db n :key messages :messages}]
  (if-let [cid (some->> (ratom/realize (:seon.agent/key c)) (ctx/call-of db n))]
    (let [evals (ctx/turn-evals c cid)
          model? (let [plain (ratom/untracked c)] (fn [e] (get-in plain [:seon.eval/_agent e :seon.eval/call-id])))
          ;; who posts that it was opened, read here once and not by each eval
          agent-key (ratom/realize (:seon.agent/key c)) agent-id (some-> agent-key keyword)
          item (fn [[e id]] [#'eval-item {:key id :at [:seon.eval/_agent e] :agent-key agent-key :agent-id agent-id}])
          call (get-in c [:seon.model-call/_agent cid])
          ours (filter (comp model? first) evals)]
      (when call
        ;; a turn nothing has come of yet (a call its messages or evals have not reached) is no element
        (when-let [kids (seq (remove nil? (concat (for [[m id] messages] [#'chat-message {:key id :at [:seon.message/_to m]}])
                                                  [(reply-hiccup (cond-> (into {} (for [k [:seon.model-call/id :seon.model-call/n :seon.model-call/origin :seon.model-call/reply :seon.model-call/reasoning :seon.model-call/truncated]
                                                                                        :let [v (ratom/realize (get call k))] :when (some? v)] [k v]))
                                                                   ;; in flight: the model's call until its evals have all run (:seon.model-call/forms, written by seon.turn after
                                                                   ;; the last) or it failed (:seon.model-call/error), of an agent whose process lives; its committed reply and
                                                                   ;; evals draw beside it all the while
                                                                   (and (= :model (ratom/realize (:seon.model-call/origin call)))
                                                                        (nil? (ratom/realize (:seon.model-call/forms call)))
                                                                        (nil? (ratom/realize (:seon.model-call/error call)))
                                                                        (not (gone (ratom/realize (:seon.agent/status c)))))
                                                                   (assoc ::in-flight true))
                                                                 (boolean (seq ours)) (map item ours))]
                                                  (map item (remove (comp model? first) evals)))))]
          (into [:div {:class "seon-stack"}] kids))))
    ;; the turn now opening has no call yet: what was delivered since the last turn's edge reads here, before the turn begins
    (when-let [ms (seq messages)]
      (into [:div {:class "seon-stack"}] (for [[m id] ms] [#'chat-message {:key id :at [:seon.message/_to m]}])))))

(defn conversation-list
  "The conversation of an agent's page (c-rows), what the model reads as messages (:seon.render/messages) and the person as the chat: its system message, the example sessions it reads, what opens its own session (every conversation opens on a user
  message), what was asked (the person's: the model reads the plan in the task's part), a component for each of its turns (`conversation-turn`), then how it stands (the person's). It reads the agent's turn number, the turns' bases and the index of the messages to the agent (`seon.ctx/placed-messages`: where each was read, derived from when it was delivered, handed to each turn as its :messages) and nothing else of its records: a new eval runs its turn alone, and a new turn or message this list and a lookup of each earlier turn's marker; a turn runs again for its own messages alone.
  See docs/render.md#the-models-context-a-cached-prefix-then-the-turn."
  {:seon.render/list "c-rows" :seon.render/messages true :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db :seon.host/db]]]] :any]}
  [{c :seon.ctx/ctx db :seon.db/db}]
  (let [k (ratom/realize (:seon.agent/key c))
        turn (or (ratom/realize (:seon.agent/turn c)) 0)
        ;; where each message was read, once for every turn (a turn is given its own, so a message delivered runs this list and the turn it reached)
        placed (when k (ctx/placed-messages db c))]
    [:ol {:id "c-rows"}
     [#'system-message {}]
     [#'session-examples {}]
     (when k [:li {} (session/session-open k)])
     (when (:seon.agent/task c) [#'chat-ask {:wire/for "page"}])
     ;; one past the turn in force: what was taken for the turn that reads it comes before that turn begins (seon.turn/take!, tell-error!); a turn no record names draws nothing
     (for [n (range 0 (+ 2 turn))] [#'conversation-turn (let [ms (get placed n (when (= n (inc turn)) (:pending placed)))] (cond-> {:key n} ms (assoc :messages ms)))])
     [#'chat-end {:wire/for "page"}]
     [#'proof-story {:wire/for "page"}]]))

(def ^:private task-stands
  "How a task's :seon.task/outcome reads to a person: [words tone]. :parked and :told are woken by a message (seon.reconcile/park-outcomes);
  :cap is not: it waits for its agent's turns to be raised and the task released."
  {:running ["working" :live] :parked ["parked" :ask] :told ["told" :ask] :cap ["stopped at its cap" :stopped]
   :done ["done" :done] :merged ["merged" :done] :ready ["ready to merge" :quiet] :gate ["at the merge gate" :quiet] :behind ["behind main" :stopped]
   :dead ["died" :failed] :lost ["stopped by a restart" :quiet] :killed ["killed" :failed] :refused ["refused" :failed] :closed ["closed" :quiet]})

(def ^:private end-stands
  "How the agent's own end (:seon.agent/outcome) reads where its task holds none: [words tone]."
  {:green ["done" :done] :waiting ["parked" :ask] :cap ["stopped at its cap" :stopped] :red ["ended red" :failed]
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
  gone is never working, and a message starts one that waits. {:state :text :detail :reason :tone :lit?}: the state (:task/cap, :end/green, :status/idle), the words, the
  turns a cap spent, the reason (the task's, else the agent's end's) and the tone (tone-class); lit while it works. See docs/render.md#a-sessions-address-and-the-agents-list."
  {:malli/schema [:=> [:cat [:maybe :map] [:maybe :map]] [:map [:state :keyword] [:text :string] [:tone :keyword] [:lit? :boolean]
                                                           [:detail {:optional true} :string] [:reason {:optional true} :string]]]}
  [a t]
  (let [status (:seon.agent/status a)
        o (:seon.task/outcome t)
        turn (:seon.agent/turn a)
        end (when (and (:seon.agent/outcome a) (or (nil? turn) (= turn (:seon.agent/ended-turn a)))) (:seon.agent/outcome a))
        gone? (contains? gone status)
        [from k] (cond (and gone? (= :running o)) [status-stands status]
                       (and o (not= :running o)) [task-stands o]
                       end [end-stands end]
                       (and (= :running o) (= :spawning status)) [status-stands :spawning]
                       o [task-stands o]
                       :else [status-stands status])
        [text tone] (get from k [(kname k) :quiet])
        turns (when (= "stopped at its cap" text) (or (:seon.agent/ended-turn a) (:seon.agent/max-turns a)))
        waiting? (or (= :waiting end) (#{:parked :told} o))
        reason (when-not waiting? (some #(when-not (str/blank? %) %) [(:seon.task/reason t) (:reason (:seon.task/failure t)) (when end (:seon.agent/reason a))]))]
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
  (let [{:seon.agent/keys [status branch description]} v
        id (:seon.agent/key v)
        ;; how its work stands: the task's outcome (root's pull names the task :task, an agent's its attribute), the agent's own end, else its status
        stands (standing v (ratom/realize (or (:task v) (:seon.agent/task v))))
        ;; its current process's pid while it runs: an ended process's pid names nothing live
        ;; a ctx join holds its members by id (a map); root's own pull, a vector
        proc (task/newest-process (let [ps (:processes v)] (if (map? ps) (vals ps) ps)))
        pid (when-not (:seon.process/ended-ms proc) (:seon.process/pid proc))]
    [:div {:class "seon-stack"}
     [:div {:class "flex items-baseline gap-3"}
      [:h1 {:class "text-base font-bold text-signal" :title (str/join " · " (remove nil? [(kname id) (when branch (str "on branch " (kname branch))) (when pid (str "pid " pid))]))}
       (if-let [t (some-> (ratom/realize (or (:task v) (:seon.agent/task v))) :seon.task/id)] (task-title t) (or (:seon.user/name v) (kname id)))]
      ;; how its work stands, at a glance and in a person's words (standing): working, waiting for you only where a message
      ;; is taken, stopped at its cap, merged; the reason it stands so beside it
      (when-not (:seon.user/name v)
        [:span {:class (str "shrink-0 " (tone-class (:tone stands)))}
         (str "● " (:text stands) (when (:detail stands) (str " · " (:detail stands))))])
      ;; what it evaluates now, while its process runs one: the newest thing it does, live
      (when-let [r (and pid (:seon.process/running proc))]
        [:code {:class "min-w-0 truncate text-text-300"} r])]
     (when-let [r (:reason stands)] [:p {:class "min-w-0 break-words text-text-300"} r])
     (when-not (str/blank? description) [:p {:class "text-text-300"} (plain description (part))])]))

(defn root
  "What root's head adds (the declared html render of :seon.root/root-fact): when it last went quiet, its root causes a line each."
  {:malli/schema [:=> [:cat :seon.render/opts :map] :any]}
  [_ v]
  (let [{:seon.root/keys [quiescent]} v]
    (when-let [cs (seq (:root-causes quiescent))]
      [:details [:summary {:class "cursor-pointer text-text-300"} (str (count cs) (if (= 1 (count cs)) " root cause" " root causes") " when it went quiet")]
       (for [{:keys [task cause run blocks]} cs]
         [:p (str task ": " cause (when run (str " (" run ")")) (when (seq blocks) (str " · blocks " (count blocks))))])])))

(defn root-input
  "Root's input on its page: a sentence asking for something (seon.root/ask!); under More, spawn agents (seon.root/spawn!) or evaluate forms in
  every live agent (seon.root/submit! to :all)."
  {:malli/schema [:=> [:cat :seon.render/opts :map] :any]}
  [opts _]
  [:section {:id (:unit opts) :class "flex flex-col gap-2 xl:flex-row"}
   ;; a sentence is a request: a task an agent starts on (seon.root/ask!)
   [:form {:class "flex min-w-0 flex-1 gap-2" :seon/call 'seon.root/ask! :seon/agent :root}
    [:input {:name "task" :class "flex-1 min-w-0" :autocomplete "off" :placeholder "What would you like done?"}]]
   ;; for whoever is working on Seon itself
   [:details {:data-preserve-attr "open" :class "text-xs"}
    [:summary {:class "seon-note cursor-pointer"} "More"]
    [:div {:class "mt-1 flex flex-wrap items-baseline gap-2"}
     [:button {:type "button" :class "rounded border border-base-700 px-2" :seon/call 'seon.root/spawn! :seon/agent :root :seon/args {:n 10}} "spawn 10"]
     [:form {:class "flex min-w-0 flex-1 items-baseline gap-2" :seon/call 'seon.root/submit! :seon/agent :all}
      [:span {:class "text-signal"} "all=>"]
      [:input {:name "forms" :class "flex-1 min-w-0" :autocomplete "off" :spellcheck "false" :placeholder "a form every live agent evaluates"}]]]]])

;;; --- root-ctx: root's ctx as components (docs/render.md#components) ---------------------------

(def ^:private pill-link
  "The one link of a pill: it fills the pill, so a click anywhere on it (its dot, its status, its task) opens the page."
  "flex min-w-0 flex-1 flex-col px-2 py-1 no-underline hover:no-underline lg:px-1")

(defn root-side
  "Root's own entry in its agents list (a component over ctx): lit, as root is the process answering
  the page; the whole pill links to root's page."
  {:seon.render/style ".root-side, .agent-side { display: flex; max-width: 15rem; flex-shrink: 0; border-radius: 0.375rem; background: var(--color-base-900); }
@media (min-width: 64rem) { .root-side, .agent-side { max-width: none; background: transparent; } }
.root-side:hover, .agent-side:hover { background: var(--color-base-850); }" :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]]] :any]}
  [_]
  [:li [:a {:href "/root" :class pill-link :title "root: main"}
        [:span {:class "flex items-baseline gap-2"} [:span {:class "text-signal"} "●"] [:span {:class "font-semibold text-text-100"} "root"] [:span {:class "seon-note"} "main"]]]])

(defn- task-name
  "What an agent works on, in words: its task's title, else its branch's name; nil for neither."
  {:malli/schema [:=> [:cat [:maybe :map] [:maybe :map]] [:maybe :string]]}
  [a t]
  (cond (:seon.task/id t) (task-title (:seon.task/id t))
        (:seon.agent/branch a) (kname (:seon.agent/branch a))))

(def ^:private session-attrs
  "What an entry of the agents list reads of an agent on main: its identity, its process's status, its branch and turns, and its task."
  [:seon.agent/key :seon.agent/started-ms :seon.agent/status :seon.agent/branch :seon.agent/max-turns
   {:seon.agent/task [:seon.task/id :seon.task/outcome :seon.task/reason :seon.task/failure :seon.task/plan {:seon.task/started-by [:seon.user/name]}]}])

(defn session-address
  "The name of an agent's page: its key (:seon.agent/key, its id's name), the one name of a session that survives a root's restart,
  so a link to it is a bookmark. An agent without a key (a map the page is shown) is nothing to link. See docs/render.md#a-sessions-address-and-the-agents-list."
  {:malli/schema [:=> [:cat :map] :string]}
  [a] (:seon.agent/key a))

(defn sessions
  "Root's sessions as its session reads them (a component over the db, drawn when its record holds root, :root, as root's
  session's pattern does): every session on main with a task, as of the main its last turn heard (:seon.agent/main-commit), newest first, as root's own work list reads them (`work-list`), each
  by its address, what it works on and how it stands, so root's session knows what is already being done before it starts more;
  itself left out. The newest :seon.config.ctx/window of them; the heading says once how to read the rest, with no count, which
  would move at every new session. Reads every :seon.agent/task datom and sorts them at each draw, O(the agents ever started):
  fine at today's sizes, and grouping the agents list by who started each is where it gets a seek. See docs/users.md#roots-session."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db :seon.host/db]]]] :any]}
  [{c :seon.ctx/ctx db0 :seon.db/db}]
  ;; main as the session's turn last heard it (:seon.agent/main-commit, seon.turn), not its branch: a branch takes main's program, not
  ;; its data, so a session started on main after this one forked is on main alone (Context's review, seen on a demo root)
  (let [db (or (when (:store db0) (some->> (ratom/realize (:seon.agent/main-commit c)) (d/commit-as-db (:store db0)))) db0)
        me (ratom/realize (:seon.agent/key c))
        ks (->> (d/datoms db :aevt :seon.agent/task) (sort-by (comp - :tx)) (keep #(:seon.agent/key (d/entity db (:e %)))) (remove #{me}) distinct)
        n (config/value :seon.config.ctx/window)]
    [:section
     [:h2 "Sessions, newest first (all of them: (sort-by :tx > (d/datoms (my.db/db) :aevt :seon.agent/task)), each datom's :e an agent)"]
     (if (seq ks)
       (into [:ul] (for [k (take n ks) :let [a (d/pull db session-attrs [:seon.agent/key k]) t (:seon.agent/task a) stands (standing a t)]]
                     [:li {:key k} [:code (str "/agent/" (session-address a))] " "
                      (or (some-> (:seon.task/plan t) str/split-lines first) (task-name a t)) ": " (:text stands)]))
       [:p "None yet."])]))

(defn agent-side
  "A session of main in the agents list (a component over the db, its agent's entity id: every agent on main, of any boot, a
  process or none): lit while it works, its id, how its work stands (standing) and what it works on (its task in words, else its
  branch, cut to the pill's width), the whole pill one link to its page by its stable address (session-address), the whole name in its title. See docs/render.md#a-sessions-address-and-the-agents-list."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.db/db :seon.host/db]]] :int] :any]}
  [{db :seon.db/db} eid]
  (let [a (d/pull db session-attrs eid)
        t (:seon.agent/task a)
        id (:seon.agent/key a)
        stands (standing a t)
        what (task-name a t)]
    [:li [:a {:href (str "/agent/" (session-address a)) :class pill-link
              :title (str/join " · " (remove nil? [id (:text stands) (:detail stands) what (:seon.task/id t)]))}
          [:span {:class "flex items-baseline gap-2"}
           [:span {:class (if (:lit? stands) "text-signal" "muted")} (if (:lit? stands) "●" "○")]
           [:span {:class "font-semibold text-text-100"} (kname id)]
           (when (:seon.agent/status a) [:span {:class (str "truncate text-2xs " (tone-class (:tone stands)))} (:text stands)])]
          (when what [:span {:class "truncate text-2xs text-text-400"} what])]]))

(defn- session-order
  "How a boot's sessions sort in the list: by when they started (oldest first)."
  {:malli/schema [:=> [:cat :seon.host/db :int] [:tuple :int :int]]}
  [db eid] [(or (:seon.agent/started-ms (d/entity db eid)) 0) eid])

(defn- shown-session?
  "Whether the agents list holds agent eid: one with a task, or a process running. A restart's person's session, with neither, is nothing to open. See docs/render.md#a-sessions-address-and-the-agents-list."
  {:malli/schema [:=> [:cat :seon.host/db :int] :boolean]}
  [db eid]
  (boolean (or (seq (d/datoms db :eavt eid :seon.agent/task))
               (when-let [dt (first (d/datoms db :eavt eid :seon.agent/status))] (not (contains? gone (:v dt)))))))

(defn boot-side
  "One boot's sessions in the agents list (a component over the db, the boot's id: its agents oldest first, so a new session redraws its boot's
  unit alone, whose element id is the boot's): the
  current boot's in the list itself, an earlier boot's inside `earlier-sessions`'s one fold, so a list of
  every session on main stays short to read. It reads its boot's agents alone, by the index of :seon.agent/boot, whatever other boots hold. See docs/render.md#a-sessions-address-and-the-agents-list."
  {:seon.render/style ".boot-side { display: contents; }" :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.db/db :seon.host/db]]] :string] :any]}
  [{db :seon.db/db} boot]
  (let [eids (->> (d/datoms db :avet :seon.agent/boot boot) (map :e) (sort-by #(session-order db %)) (filterv #(shown-session? db %)))
        pills (for [eid eids] [#'agent-side {} eid])]
    [:li {:class "contents"} [:ul {:class "contents"} pills]]))

(defn- sessions-by-boot
  "Every agent on main db grouped by the boot that started it (:seon.agent/boot): [[boot [eid ...]] ...], the current boot's first,
  then the others newest first (by the transaction that wrote their newest agent: transaction ids only grow, where entity ids are not an order), each boot's agents by start. See docs/render.md#a-sessions-address-and-the-agents-list."
  {:malli/schema [:=> [:cat :seon.host/db] [:sequential [:tuple :string [:sequential :int]]]]}
  [db]
  (let [now (task/boot-of db)
        by (group-by :v (d/datoms db :avet :seon.agent/boot))]
    (->> by
         (keep (fn [[boot dts]] (when-let [dts (seq (filter #(shown-session? db (:e %)) dts))]
                                  [boot (mapv :e (sort-by #(session-order db (:e %)) dts)) (apply max (map :tx dts))])))
         (sort-by (fn [[boot _ t]] [(if (= boot now) 0 1) (- t)]))
         (mapv (fn [[boot eids _]] [boot eids])))))

(defn root-head
  "Root's head (a component over its ctx and the db): what is going on in a sentence, by the tasks' states, and, when it last went quiet,
  its root causes, by root's own render (`root`). See docs/render.md#the-page. It reads schemas in the walk's entity index (:seon.render/index) for the values it draws through render/hiccup: one render running another, the interim stage 2a of docs/users.md removes."
  {:seon.render/style ".root-head { border-radius: 0.375rem; background: var(--color-base-900); padding: 0.75rem; font-size: 0.875rem; line-height: 1.25rem; color: var(--color-text-100); }" :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db :seon.host/db]]]] :any]}
  [{c :seon.ctx/ctx db :seon.db/db ix :seon.render/index}]
  ;; what an agent is doing is its task's outcome; :seon.task/execution stays :running once it has finished
  (let [held (count (d/datoms db :avet :seon.task/execution :held))
        running (count (d/datoms db :avet :seon.task/outcome :running))
        parked (count (d/datoms db :avet :seon.task/outcome :parked))]
    [:section
     ;; the tasks by their state, in plain words (owner, 2026-11-08: "Just show the status as parked"): a parked one wakes on a message
     [:h1 {:class "text-base font-semibold text-text-50"}
      (if (pos? (+ running parked))
        (str (str/join ", " (remove nil? [(when (pos? parked) (str parked " parked")) (when (pos? running) (str running " running"))])) ".")
        "Nothing running.")]
     (when (pos? held) [:p {:class "seon-note"} (str held (if (= 1 held) " task" " tasks") " not released yet.")])
     (render/hiccup {:seon.render/index ix :schema :seon.root/root-fact} (relevant :seon.root/root-fact c))]))

(defn problems-tile
  "Main's open problems (a component over root's ctx, its :health: the report the render proc keeps
  with root's process, seon.problems/health changed by each move of main, seon.render.proc/health!): what needs fixing by the report's render, and
  below it, folded, what is upkeep (:seon.problems/attention). It runs again only when :health changes. See docs/render.md#the-page. It reads schemas in the walk's entity index (:seon.render/index) for the values it draws through render/hiccup: one render running another, the interim stage 2a of docs/users.md removes."
  {:seon.render/style ":is(.problems-tile, .process-tile, .session-card) { display: flex; flex-direction: column; gap: 0.5rem; border: 1px solid var(--color-base-800); border-radius: 0.375rem; padding: 0.5rem; }
:is(.problems-tile, .process-tile, .session-card) > h2 { text-transform: uppercase; letter-spacing: 0.14em; font-size: 0.625rem; color: var(--color-text-400); }" :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]]] :any]}
  [{c :seon.ctx/ctx ix :seon.render/index}]
  (let [h (some-> (:health c) ratom/realize)
        upkeep (:seon.problems/attention (contract/properties :seon.problems/class))
        ;; a class's rows that still hold: a row whose cause has since passed (:open? false, seon.problems/closed?) is no problem,
        ;; and its class counts what remains (health is the absence of open problem facts)
        open-class (fn [{:keys [rows] :as cl}] (let [rows (vec (remove #(false? (:open? %)) rows))]
                                                 (cond-> (assoc cl :rows rows) (not= rows (:rows cl)) (assoc :n (reduce + 0 (map #(:count % 1) rows))))))
        of (fn [pred] (cond-> h (:classes h) (update :classes #(into {} (comp (filter (comp pred key)) (map (fn [[k v]] [k (open-class v)])) (filter (comp pos? :n second))) %))))
        total (fn [r] (reduce + 0 (map :n (vals (:classes r)))))]
    [:section [:h2 "problems"]
     (when h
       (list (render/hiccup {:seon.render/index ix :schema :seon.problems/report} (of (complement upkeep)))
             (when (pos? (total (of upkeep)))
               [:details {:data-preserve-attr "open"}
                [:summary {:class "seon-note cursor-pointer"} (str "Upkeep · " (total (of upkeep)))]
                (render/hiccup {:seon.render/index ix :schema :seon.problems/report} (of upkeep))])))]))

(defn process-tile
  "Root's process (a component over root's ctx, its :process: the render proc's numbers of the
  writer, the fork server and the store, each proc's defects and the newest events; root's own data
  beside its db pull, never written): a line of numbers, then the procs with defects, then the
  stream's newest rows. It runs again only when :process changes."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]]] :any]}
  [{c :seon.ctx/ctx}]
  (let [{:keys [writer fork-server root-errors-total health events]} (ratom/realize (:process c))
        stat (fn [label v] (when (some? v) [:span [:span {:class "muted"} (str label " ")] (str v)]))
        sick (filter (fn [[_ h]] (pos? (:defects h 0))) health)]
    [:section [:h2 "root's process"]
     [:p {:class "text-xs text-text-400"} (str/join " · " (remove nil? [(some->> (ratom/realize (:seon.root/boot c)) (str "boot ")) (some->> (ratom/realize (:seon.root/generation c)) :commit (str "generation "))]))]
     [:p {:class "flex flex-wrap gap-x-3 text-xs tabular-nums"}
      (stat "batch p50" (some-> (:batch-p50-ms writer) ms-str)) (stat "tx/commit" (:tx-per-commit writer)) (stat "commits" (:commits writer))
      (stat "fork p50" (some-> (:fork-ms-p50 fork-server) ms-str)) (stat "fork lag" (some-> (:lag-ms fork-server) ms-str))
      (stat "defects" root-errors-total)]
     (when (seq sick)
       [:ul {:class "text-xs"} (for [[pid {:keys [defects newest]}] sick]
                                 [:li [:span {:class "text-error"} (str (kname pid) " ✕" defects)] " " [:span {:class "text-text-300"} (some-> newest (print/line (cell)))]])])
     (when (seq events)
       ;; every event the proc keeps, newest first (its window, :seon.config.render.list/stream)
       [:details {:data-preserve-attr "open"}
        [:summary {:class "cursor-pointer text-xs text-text-400"} "What it just did"]
        [:ol {:class "text-xs"} (for [ev events]
                                  [:li {:class "seon-row"} [:span {:class "text-eval w-14 shrink-0"} (event-kind ev)]
                                   [:span {:class "text-text-400 shrink-0"} (some-> (:agent ev) kname)]
                                   [:span {:class "min-w-0 break-all text-text-300"} (event-detail ev)]])]])]))

(defn- last-exchange
  "The newest message a person sent agent e (a seek of :seon.message/to, the newest by eid, from a :seon.user/name) and its task
  t's reason when root wrote it after that message (by transaction), the agent's answer to it: {:asked :reply}, either nil. See
  docs/users.md#whats-on-screen."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe pos-int?] [:maybe :map]] [:map [:asked [:maybe :string]] [:reply [:maybe :string]]]]}
  [db e t]
  (let [m (when e (->> (d/datoms db :avet :seon.message/to e) (map :e)
                       (filter #(some->> (:seon.message/from (d/entity db %)) (vector :seon.user/name) (d/entity db)))
                       (reduce max -1)))
        m (when (and m (pos? m)) m)
        tx-of (fn [eid a] (some-> (first (d/datoms db :eavt eid a)) :tx))
        r (:seon.task/reason t)
        rid (:db/id (d/entity db [:seon.task/id (:seon.task/id t)]))]
    {:asked (some-> m (#(d/entity db %)) :seon.message/body)
     :reply (when (and (not (str/blank? r)) (or (nil? m) (< (tx-of m :seon.message/body) (or (some-> rid (tx-of :seon.task/reason)) 0)))) r)}))

(defn session-card
  "A session as a tile (a component over the db, by its agent's key), the whole tile one link to its page by its stable address:
  what it works on in words and how it stands (standing), then what it last told whoever started it, a few lines of it (the
  task's reason, which root's message to them carries: seon.reconcile/tell-tx), so a person can tell which to open. See
  docs/users.md#whats-on-screen."
  {:seon.render/style ".session-card > a { display: flex; flex-direction: column; gap: 0.5rem; height: 100%; min-height: 12rem; border: 1px solid var(--color-base-800); border-radius: 0.5rem; background: var(--color-base-900); padding: 0.75rem 0.875rem; color: inherit; text-decoration: none; } .session-card > a:hover { border-color: var(--color-base-600); } .session-card .said { max-height: 16rem; overflow: hidden; font-size: 0.875rem; line-height: 1.375rem; color: var(--color-text-300); -webkit-mask-image: linear-gradient(to bottom, black 70%, transparent); mask-image: linear-gradient(to bottom, black 70%, transparent); } .session-card .said p { margin: 0 0 0.375rem; } .session-card .said .asked { color: var(--color-text-400); border-left: 2px solid var(--color-base-700); padding-left: 0.5rem; }"
   :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.db/db :seon.host/db]]] :string] :any]}
  [{db :seon.db/db} k]
  (let [a (d/pull db session-attrs [:seon.agent/key k])
        t (:seon.agent/task a)
        stands (standing a t)
        {:keys [asked reply]} (last-exchange db (:db/id (d/entity db [:seon.agent/key k])) t)
        reply (or reply (when-not asked (:reason stands)))]
    [:section
     [:a {:href (str "/agent/" (session-address a))
          :title (str/join " · " (remove nil? [(:seon.agent/key a) (:seon.task/id t)]))}
      [:span {:class "flex items-baseline gap-2"}
       [:span {:class (if (:lit? stands) "text-signal" "muted")} (if (:lit? stands) "●" "○")]
       ;; what a person asked, its first line as they typed it, else the task's name in words
       [:span {:class "min-w-0 flex-1 truncate font-semibold text-text-100"}
        (or (when (get-in t [:seon.task/started-by :seon.user/name]) (some-> (:seon.task/plan t) str/split-lines first str/trim not-empty)) (task-name a t))]
       [:span {:class (str "shrink-0 text-2xs " (tone-class (:tone stands)))} (:text stands)]]
      ;; the person's newest message, then the agent's answer to it: what was asked and what came of it is the point
      [:div {:class "said"}
       (when asked [:p {:class "asked"} [#'markdown asked {:inline? true}]])
       (when reply [#'markdown reply {:inline? true}])]]]))

(defn- sessions-by-standing
  "Sessions ks (agent keys, newest first) as tiles grouped by how each stands, in its state's plain word: parked (a message wakes it),
  working, then finished, folded; empty as none. Root's work and a user's page both draw this. See docs/users.md#whats-on-screen."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :string]] :any]}
  [db ks]
  (let [sessions (for [k ks :let [a (d/pull db session-attrs [:seon.agent/key k])]] [k (standing a (:seon.agent/task a))])
        where (fn [[_ {:keys [state lit?]}]] (cond (#{:task/parked :task/told :status/idle} state) :parked lit? :working :else :finished))
        {:keys [parked working finished]} (group-by where sessions)
        tiles (fn [ss] (into [:div {:class "session-tiles"}] (for [[k _] ss] [#'session-card {} k])))
        heading (fn [text] [:h2 {:class "kicker"} text])]
    [:div {:class "seon-stack-2"}
     (when (seq parked) (list (heading "Parked") (tiles parked)))
     (when (seq working) (list (heading "Working") (tiles working)))
     (when (seq finished)
       [:details {:data-preserve-attr "open"}
        [:summary {:class "kicker cursor-pointer"} (str "Finished · " (count finished))]
        (into [:div {:class "mt-2"}] [(tiles finished)])])]))

(defn work-list
  "The work of root's page (a component over the db): every session on main with a task, by how it stands: what needs a person,
  what is working, then what finished, folded; newest first. See docs/render.md#the-page."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.db/db :seon.host/db]]]] :any]}
  [{db :seon.db/db}]
  (let [ks (keep #(:seon.agent/key (d/entity db (:e %))) (sort-by (comp - :tx) (d/datoms db :aevt :seon.agent/task)))]
    [:section (if (seq ks) (sessions-by-standing db ks) [:p {:class "muted"} "No work yet."])]))

(defn root-evals
  "The evals sent to root (a component over its ctx): one closed line that opens to them, in the order written. They are how sessions debug root, not its work. See docs/render.md#the-page."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]]] :any]}
  [{c :seon.ctx/ctx}]
  (let [es (sort (keys (:evals c)))]
    (when (seq es)
      [:details {:data-preserve-attr "open"}
       [:summary {:class "seon-note cursor-pointer"} (str (count es) (if (= 1 (count es)) " eval" " evals") " sent to root")]
       (into [:ol {:class "seon-stack mt-2"}] (for [eid es] [#'eval-item {:key (get-in c [:evals eid :seon.eval/id]) :at [:evals eid]}]))])))

(defn earlier-sessions
  "Sessions of earlier boots in the agents list (a component over the db, their boots): one fold, each boot's a unit of its own. See docs/render.md#a-sessions-address-and-the-agents-list."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.db/db :seon.host/db]]] [:sequential :string]] :any]}
  [_ boots]
  [:li {:class "shrink-0"}
   [:details {:data-preserve-attr "open"}
    [:summary {:class "px-1 text-2xs text-text-500"} "Earlier"]
    (into [:ul {:class "flex gap-2 lg:flex-col lg:gap-0.5"}] (for [boot boots] [#'boot-side {} boot]))]])

(defn- newest-started
  "The agents this user started (ctx c's :seon.agent/agents join), newest first by when each started. See docs/users.md#whats-on-screen."
  {:malli/schema [:=> [:cat :seon.host/db :map] [:sequential :int]]}
  [db c] (sort-by (fn [e] [(- (or (:seon.agent/started-ms (d/entity db e)) 0)) e]) (keys (:seon.agent/agents c))))

(defn sign-in
  "The page / is when no one is signed in (a render over the db): who could be, each a link to their page, and root's. Until there is a login, root signs in the OS account running it. See docs/users.md#whats-on-screen."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.db/db :seon.host/db]]]] :any]}
  [{db :seon.db/db}]
  [:section {:id "p-sign-in" :class "seon-stack"}
   [:h1 {:class "text-base font-semibold text-text-50"} "No one is signed in, so sign in to see your work."]
   [:p {:class "seon-note"} "Until there is a login, a root signs in the account that runs it."]
   (into [:ul {:class "seon-stack"}] (for [d (d/datoms db :aevt :seon.user/name)] [:li [:a {:href (str "/user/" (:v d))} (:v d)]]))
   [:p [:a {:href "/root"} "Root's page: every session on main"]]])

(defn users-side
  "The users in the agents list (a component over the db): each a link to its page by its :seon.user/name, then its agents of earlier boots, which the list's own boot leaves out, so a restart doesn't hide them. See docs/users.md#whats-on-screen."
  {:seon.render/style ".users-side { display: contents; }" :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.db/db :seon.host/db]]]] :any]}
  [{db :seon.db/db}]
  (when-let [names (seq (map :v (d/datoms db :aevt :seon.user/name)))]
    (let [now (task/boot-of db)
          earlier (fn [n] (->> (:seon.agent/agents (d/entity db [:seon.user/name n]))
                               (remove #(= now (:seon.agent/boot %)))
                               (sort-by (fn [a] (- (or (:seon.agent/started-ms a) 0))))
                               (map :seon.agent/key)))]
      [:li {:class "contents"} [:ul {:class "contents"}
       (for [n names]
         (list [:li [:a {:href (str "/user/" n) :class pill-link :title (str "user: " n)}
                     [:span {:class "flex items-baseline gap-2"} [:span {:class "text-signal"} "◆"] [:span {:class "font-semibold text-text-100"} n]]]]
               (for [k (earlier n)] [#'agent-side {} (:db/id (d/entity db [:seon.agent/key k]))])))]])))

(defn started-side
  "The agents this user started in its agents list (a component over ctx, by the pull's :seon.agent/agents join): each `agent-side`. See docs/users.md#whats-on-screen."
  {:seon.render/style ".started-side { display: contents; }" :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db :seon.host/db]]]] :any]}
  [{c :seon.ctx/ctx db :seon.db/db}]
  [:li {:class "contents"} [:ul {:class "contents"} (for [eid (newest-started db c)] [#'agent-side {} eid])]])

(defn started-cards
  "A tile for each agent this user started, grouped by what needs them (sessions-by-standing: `session-card` by each one's key), the
  home page's one list: an agent's latest words to them are on its tile, its whole conversation in its chat. See docs/users.md#whats-on-screen."
  {:seon.render/style ".started-cards .session-tiles, .work-list .session-tiles { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(100%, max(24rem, calc(50% - 0.5rem))), 1fr)); gap: 1rem; }"
   :malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db :seon.host/db]]]] :any]}
  [{c :seon.ctx/ctx db :seon.db/db}]
  (if-let [ks (seq (keep #(:seon.agent/key (d/entity db %)) (newest-started db c)))]
    [:section (sessions-by-standing db ks)]
    [:p {:class "muted"} "No agents yet: ask for something below."]))

(defn user-ctx
  "A user's page, the render of its ctx (the declared html render of :seon.render/user-ctx, which a branch overrides like any render): root and its agents in the agents list, its agents as cards. See docs/users.md#whats-on-screen."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]]] :any]}
  [_]
  [:div {:id "user-ctx"}
   [:ol {:id "c-side"} [#'root-side {}] [#'started-side {}]]
   [:ol {:id "c-rows"} [#'started-cards {}]]
   [#'rules-list {}]])

(defn root-ctx
  "Root's page, the render of root's ctx (the declared html render of :seon.render/root-ctx, which a
  branch overrides like any render), drawn by the walk as components in its lists: its agents list
  (c-side: root, then this boot's sessions with a task, then the earlier ones folded, `sessions-by-boot`), its head (p-head),
  its work and the evals sent to it (c-rows: `work-list`, `root-evals`) and its tiles (p-tiles: root's process, then main's
  problems). An agent's page shows the same agents list (seon.render.proc's agent page). See docs/render.md#the-page."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db :seon.host/db]]]] :any]}
  [{db :seon.db/db}]
  [:div {:id "root-ctx"}
   ;; every session on main, of any boot: root's ctx holds this boot's agents alone (a new boot unlinks the earlier ones from root)
   (let [now (task/boot-of db)
         boots (map first (sessions-by-boot db))
         earlier (vec (remove #{now} boots))]
     [:ol {:id "c-side"} [#'root-side {}] [#'users-side {}]
      (when (some #{now} boots) [#'boot-side {} now])
      (when (seq earlier) [#'earlier-sessions {} earlier])])
   [:div {:id "p-head"} [#'root-head {}]]
   [:ol {:id "c-rows"} [#'work-list {}] [#'root-evals {}]]
   [#'rules-list {}]
   [:aside {:id "p-tiles"} [#'process-tile {}] [#'problems-tile {}]]])

(defn page-chrome
  "A page in root's chrome (root's, an agent's): the top bar, then the units its walks drew, each
  placed in the list it was drawn in ({:id :container :rev :html}: the agents list, the head, the
  task's list, the conversation, the tiles), and its caption and input. Each unit's input is its
  revision, so the render proc sends one only when its output changed, and a new one into its list
  where its walk placed it (an append). opts {:units :view :input :caption :tab}; id, the page's
  focus; tab, which of its tabs it is (top-bar)."
  {:malli/schema [:=> [:cat :map :any] [:map [:seon.render/hiccup :any] [:seon.render/units [:vector :map]]]]}
  [{:keys [units view input caption tab]} id]
  (let [in (fn [sel] (filterv #(= sel (:container %)) units))
        unit (fn [u] {:id (:id u) :input (cond-> {:rev (:rev u)} (:own? u) (assoc :own? true)) :html (:html u) :container (:container u)})
        side? (and (not= :debug tab) (boolean (some #(#{"#p-task" "#p-tiles"} (:container %)) units)))]
    {:seon.render/hiccup
     [:div {:id "app" :class "seon-page" :data-view view}
      (top-bar id tab)
      ;; the agents on the left, the chat large in the middle, what is rendered in blocks on the right, each scrolling on
      ;; its own (docs/render.md#where-things-go). The right column is there when the page has parts for it: an agent's
      ;; chat its task's list, what its model reads as parts (owner, 2026-11-08: "What happened to the sidebar on the right
      ;; with all the rendered context parts?"), root's its tiles; a user's page has none. Root draws an agent's page from
      ;; its branch until its process answers, so the parts come with the first frame, and a page that gains its first is
      ;; sent whole (seon.render.proc/pass-view). @ctx (tab :debug) places nothing beside its record
      [:div {:class (if side? "seon-columns" "seon-columns seon-columns-wide")}
       [:aside {:class "seon-agents"}
        [:h2 {:class "kicker mb-1 hidden lg:block"} "agents"]
        [:ol {:id "c-side" :class "seon-strip flex gap-2 overflow-x-auto pb-1 text-xs lg:flex-col lg:gap-0.5"} (for [u (in "#c-side")] (slot (:id u)))]]
       [:main {:class "seon-chat"}
        [:div {:id "p-head"} (for [u (in "#p-head")] (slot (:id u)))]
        (slot "p-caption")
        [:div {:id "p-rules"} (for [u (in "#p-rules")] (slot (:id u)))]
        [:ol {:id "c-rows" :class "seon-chat-log" :data-view view} (for [u (in "#c-rows")] (slot (:id u)))]
        (when input (slot "p-input"))]
       (when side?
         [:aside {:class "seon-context"}
          [:div {:id "p-task" :class "seon-blocks"} (for [u (in "#p-task")] (slot (:id u)))]
          [:div {:id "p-tiles" :class "seon-blocks"} (for [u (in "#p-tiles")] (slot (:id u)))]])]]
     :seon.render/units
     (vec (concat [{:id "p-caption" :input {:caption caption} :html (h/html [:p {:id "p-caption" :class "seon-note"} (or caption "")])}]
                  (when input [{:id "p-input" :input (:value input) :schema (:schema input)}])
                  (map unit units)))}))

(defn ctx-parts
  "@ctx's parts, each a pair: what the model reads of the part (its data, or the hiccup its render returned, printed as
  seon.render/part-text prints it), beside the part as the page draws it (its unit's
  html). parts, {id content} of the task's list (seon.render/placed-hiccup); html, {id html} of its units; open, the
  units the tab opened (/open: d-part-<id>); rules, the html of the page's rules, for the parts they style. What the model reads is shown whole, so a part may be a million characters:
  one over the value budget (:seon.config.render/value-budget) is a closed <details> with its size in tokens, whose text
  is drawn once opened, and has the page's width where a short one shares the row with the part drawn. See docs/render.md#the-page."
  {:malli/schema [:=> [:cat :map [:map-of :string :string] [:set :string] :string] :any]}
  [parts html open rules]
  [:li {:id "d-parts" :class "seon-stack-2"}
   (h/raw rules)
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
  {:malli/schema [:=> [:cat :any [:maybe :map] [:maybe :map] [:maybe :map]] [:map [:seon.render/hiccup :any] [:seon.render/units [:vector :map]]]]}
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
    {:seon.render/hiccup
     [:div {:id "app" :class "mx-auto flex max-w-screen-xl flex-col gap-3 p-3 sm:p-4"}
      (top-bar focus :context)
      [:header {:class "seon-stack"}
       [:h1 {:class "text-base font-semibold text-text-50"} (if context (str "What " label " reads") (str "What " label "'s model is sent"))]
       [:p {:class "text-text-400"} (if context
                                      "Its page as it reads it: the render of its ctx, each component's hiccup by its id, the text /context.txt serves, drawn again when its ctx changes."
                                      "A bar is one request to the model: its height the tokens sent, blue what the provider's cache served. Below, the request chosen, as it went on the wire, each message by its role; open one to read its fields as sent.")]]
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
                                     (let [most (max 1 (reduce max 0 (keep :tokens turns)))]
                                       (for [[j {:keys [e turn origin tokens cached]}] (map-indexed vector turns)
                                             :let [on? (= j i) t (or tokens 0) c (min t (or cached 0))]]
                                         ;; a turn's whole context, its height against the largest turn's: what the provider's cache served below, what it did not above
                                         [:button {:type "button" (keyword "data-on:click") (go e) :aria-current (when on? "step")
                                                   :title (if tokens (str "turn " turn ": " (group3 tokens) " tokens" (when cached (str ", " (group3 cached) " from cache"))) (str "turn " turn))
                                                   :class (str "flex min-w-6 flex-col items-center gap-0.5 rounded px-1 pt-1 tabular-nums " (if on? "bg-base-700 text-text-50" "text-text-400 hover:text-text-50"))}
                                          [:span {:class "flex h-10 w-3 flex-col justify-end"}
                                           [:span {:class (if on? "bg-signal" "bg-text-400") :style (str "height:" (/ (* 100.0 (- t c)) most) "%")}]
                                           [:span {:class "bg-info" :style (str "height:" (/ (* 100.0 c) most) "%")}]]
                                          [:span (str turn (when (= :outside origin) " read"))]]))
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
  "What made part ref of a sent request, as a line: the help page as of a t, an eval, a message, a reply, an example session, the
  opening of the agent's own and the page whole a read ended with: every ref kind seon.ctx.wire/request names."
  {:malli/schema [:=> [:cat :any] :string]}
  [[kind x t]]
  (case kind
    :section (str "the system message: " x)
    :render "the page, whole (a read's last: Seon ran it as a call of its own)"
    :eval (str "eval " x)
    :reply (str "the reply of " x)
    :message (str "message " x)
    :example (str "example session " x " as of t " t)
    :session (str "the opening of session " x)))

;; a role's band: the wire's own types, in the order the bar lists them. See docs/render.md#the-debug-page-is-the-wire-request
(def ^:private role-looks
  "Each wire role's {role [border band]}."
  (array-map "system" ["border-info" "bg-info"]
             "user" ["border-signal" "bg-signal"]
             "assistant" ["border-eval" "bg-eval"]
             "tool" ["border-text-500" "bg-text-500"]))

(defn debug-turn
  "One request of the debug timeline as it went on the wire (the declared html render of
  :seon.render/debug-turn), nothing re-rendered or summarized (owner: \"I don't want to find out later
  that the envelope was different and you just made a fake page for me\"; \"I want the wire format types as they are\"): a bar of
  its tokens by role, then each message of its body in order, one row: its role, a line of its text, its size against
  :seon.config.render/tokens-warn and tokens-over. Open, the row's fields as sent replace its line, drawn only then (/open):
  its records' refs, content as Markdown or a tool's highlighted, each tool call's function, id and arguments, whole. The line where the provider's cached
  prefix ends (placed by its cache-hit count over the sizes, an estimate, said so), or, with none recorded, where
  the messages the request before sent the same way end; the envelope (every field but the messages) and,
  while opened, the whole body's JSON as sent. input as `debug-input` answers it. See docs/render.md#the-debug-page-is-the-wire-request. It reads schemas in the walk's entity index (:seon.render/index) for the values it draws through render/hiccup: one render running another, the interim stage 2a of docs/users.md removes."
  {:malli/schema [:=> [:cat :seon.render/opts :map] :any]}
  [opts {:keys [view to-come? turn agent origin body sources new raw? error differs? opened swept?]
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
        line-budget (config/value :seon.config.render/line-budget)
        opened (or opened #{})
        ;; each limit read once: a size reads quiet, amber or red, and only its colour changes at a bound
        size-class (let [warn (config/value :seon.config.render/tokens-warn) over (config/value :seon.config.render/tokens-over)]
                     (fn [n] (cond (>= n over) "font-semibold text-error" (>= n warn) "font-semibold text-warning" :else "text-text-400")))
        request-class (let [warn (config/value :seon.config.render/request-tokens-warn) over (config/value :seon.config.render/request-tokens-over)]
                        (fn [n] (cond (>= n over) "font-semibold text-error" (>= n warn) "font-semibold text-warning" :else "text-text-400")))
        line (fn [cls text] [:div {:class (str "my-1 flex items-center gap-2 text-2xs " cls)}
                             [:span {:class "h-px flex-1 bg-current opacity-40"}] text [:span {:class "h-px flex-1 bg-current opacity-40"}]])
        field (fn [k v] [:div {:class "flex flex-col"} [:span {:class "seon-note"} k] v])
        verbatim (fn [s] [:pre {:class "whitespace-pre-wrap break-words font-mono text-text-100"} s])
        highlighted (fn [s] (emit/marked-hiccup :seon.print/code s))
        ;; a tool call's arguments as the opened row reads them: seon.model/wire-message writes them from a map, always JSON
        args (fn [{:keys [function]}] (json/read-str (:arguments function)))
        ;; a row's one line: its text, else its calls as sent
        says (fn [{:keys [content tool_calls]}]
               (if (seq content) content (str/join " " (map (fn [{{:keys [name arguments]} :function}] (str name " " arguments)) tool_calls))))
        row (fn [i {:keys [role content tool_calls tool_call_id reasoning_content] :as m}]
              (let [rs (get sources i) new? (get new i) u (str unit "-o" i) open? (contains? opened u)
                    example (some (fn [[k x]] (when (= :example k) x)) rs)
                    [border] (get role-looks role)]
                [:details (cond-> {:class (str "group rounded-sm border-l-4 bg-base-950 px-2 py-1 " border)
                                   :data-role role :data-new (str new?) :data-session (when example example)
                                   (keyword "data-on:toggle") (str "@post('/open?view=" view "&unit=" u "&open=' + el.open)")}
                            open? (assoc :open true))
                 [:summary {:class "flex cursor-pointer items-baseline gap-2"}
                  [:b {:class "w-20 shrink-0 text-2xs text-text-200"} role]
                  [:span {:class "seon-text min-w-0 flex-1 break-words text-text-100 group-open:hidden"} (let [{:keys [shown truncated? omitted lines]} (print/plain (says m) line-budget {:one-line? true})]
                                     ;; the cut says what it left; the rest is the row, opened, so no copy is kept on its line
                                     (if truncated? [:span shown " " [:span {:class "seon-print-elision"} (emit/cut-words omitted lines nil)]] shown))]
                  (when new? [:span {:class "shrink-0 text-2xs text-signal"} "new"])
                  (when example [:span {:class "shrink-0 text-2xs text-info"} (str "example session " example)])
                  [:span {:class (str "ml-auto shrink-0 tabular-nums " (size-class (get sizes i)))} (print/tokens-text (get sizes i))]]
                 (when open?
                   [:div {:class "mt-1 flex min-w-0 flex-col gap-2"}
                    (when (seq rs) [:span {:class "seon-note"} (str/join " · " (map source rs))])
                    (when tool_call_id (field "tool_call_id" (verbatim tool_call_id)))
                    (when reasoning_content [:details [:summary {:class "seon-note"} "reasoning_content"] (markdown reasoning_content {:inline? true})])
                    (when (seq content) (field "content" ((if (= "system" role) #(markdown % {:inline? true}) (if (= "tool" role) highlighted verbatim)) content)))
                    (map-indexed (fn [j {:keys [id function] :as c}]
                                   (field (str "tool_calls[" j "] · " (:name function) " · " id)
                                          (into [:div {:class "flex flex-col gap-1"}]
                                                ;; the call's own fields by their wire names
                                                (map (fn [[k v]] (field (str "arguments." k) (if (= "code" k) (highlighted (str v)) (verbatim (str v)))))
                                                     (args c)))))
                                 tool_calls)])]))
        shares (for [[r [_ band]] role-looks
                     :let [n (reduce + 0 (keep-indexed (fn [i m] (when (= r (:role m)) (get sizes i))) ms))]
                     :when (pos? n)]
                 {:role r :band band :n n})]
    [:section {:id unit :class "flex flex-col gap-2 rounded-md bg-base-900 p-3 text-xs"}
     [:div {:class "flex flex-wrap items-baseline gap-3"}
      [:b {:class "text-signal"} (if to-come? (str "turn " turn ", to come: what it would send now") (str "turn " turn))]
      [:span {:class "text-text-400"} agent]
      [:span {:class (str "tabular-nums " (request-class (or tokens est)))}
       (if tokens (str (group3 tokens) " tokens") (print/tokens-text est))]
      (when (and prompt (pos? prompt))
        [:span {:class "tabular-nums text-text-400"} (str (group3 cache-hit-tokens) " of " (group3 prompt) " prompt tokens from cache (" (long (* 100 (/ cache-hit-tokens prompt))) "%)")])
      [:span {:class "muted"} (str (count ms) " messages · " (count (filter true? new)) " new")]
      (cond (= :outside origin) [:span {:class "muted"} "an outside read: no model call"]
            to-come? [:span {:class "muted"} "redrawn as the branch moves"]
            (not (or tokens error)) [:span {:class "muted"} "the call is in flight"])]
     (when (seq shares)
       [:div {:class "flex flex-col gap-1"}
        [:div {:class "flex h-2 overflow-hidden rounded-sm bg-base-800"}
         (for [{:keys [band n]} shares] [:span {:class band :style (str "width:" (/ (* 100.0 n) (max 1 est)) "%")}])]
        [:div {:class "flex flex-wrap gap-x-4 gap-y-1 text-text-200"}
         (for [{:keys [role band n]} shares]
           [:span {:class "inline-flex items-center gap-1.5 tabular-nums"} [:span {:class (str "inline-block h-2.5 w-2.5 " band)}]
            (str role " " (long (Math/round (/ (* 100.0 n) (max 1 est)))) "% " (print/tokens-text n))])]])
     (when error [:div {:class "rounded border border-error p-2"} (render/hiccup {:seon.render/index (:seon.render/index opts) } error)])
     (when swept?
       [:p {:class "text-warning"} "This turn's commit is gone from the store (seon.db/retain!), so this is drawn from the branch as it is now, not as it was sent."])
     (when (and differs? (not swept?))
       [:p {:class "seon-note"} "Drawn again with today's renders: its bytes differ from what the turn sent, because a render or a record changed since. The turn kept one hash of the whole request (:seon.model-call/sent-hash), so this page can't say where."])
     [:details [:summary {:class "cursor-pointer text-text-400"} "the envelope: every field but the messages, as sent"]
      (verbatim (json/write-str (dissoc body :messages)))]
     (into [:div {:class "flex flex-col gap-1"}]
           (mapcat (fn [i m]
                     (cond-> [(row i m)]
                       (= (inc i) cached) (conj (line "text-info" (str "the provider's cache ends here: " (group3 cache-hit-tokens) " tokens served from cache (placed by estimate)")))
                       (and same (pos? same) (= (inc i) same))
                       (conj (line "text-info" (str "sent the same by the request before down to here: " (group3 (long (nth upto same)))
                                                    " tokens a provider's prefix cache can serve (none recorded for this request)")))))
                   (range) ms))
     [:details (cond-> {(keyword "data-on:toggle") (str "@post('/open?view=" view "&unit=" unit "-raw&open=' + el.open)")} raw? (assoc :open true))
      [:summary {:class "cursor-pointer text-text-400"} "the body's JSON, whole, as sent"]
      (when raw? [:pre {:class "whitespace-pre-wrap break-all font-mono text-text-100" :data-wire "body"} (json/write-str body)])]]))
