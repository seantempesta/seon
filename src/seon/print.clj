(ns seon.print
  "The one value printer. Every value a person or a model reads goes through `print-value`:
  an agent's reply, a refused value inside a contract error, a test's expected and actual,
  the context block, the dashboard's value panels. It is Seon's printer (seon.render.value
  and seon.print, through session P1's proto/seon-min: identical output on L3's 1,617-line
  corpus): one token budget allotted breadth before depth, identity keys first, every cut
  carrying the form that reads its rest through the result handle, and text and hiccup
  from one selection.

  Storage and the wire are not display: EDN written to the db or a socket stays `pr-str`
  (readable, whole). Renders (seon.render) decide what to show; this decides how a value
  looks within a budget.

  A foundational namespace, like seon.contract (which calls it for errors): its functions
  carry contracts in their metadata and are armed at boot."
  (:require [clojure.string :as str] [seon.print.select :as select] [seon.print.emit :as emit] [seon.config :as config]))

(def budgets
  "Tokens each kind of shown value may spend: a reply's value (Seon's shipped agent config key),
  a value shown inside another (a refused argument, a test's expected and actual, ex-data),
  a form echoed back, and the whole context block (breadth first, so every section shows
  before any one deepens). The config's :seon.config.render.agent/token-budget and
  :seon.config.render/{part,form,context}-budget."
  {:reply (config/value :seon.config.render.agent/token-budget) :part (config/value :seon.config.render/part-budget)
   :form (config/value :seon.config.render/form-budget) :context (config/value :seon.config.render/context-budget)})

(def default-budget (:reply budgets))

(defn print-value
  "`v` printed under opts (all optional): :handle, what cuts read through: the result
  symbol (result/h3) or a form that reads v ((nth (my.turn/evals) 3)); none: a cut says
  what it omitted and names no form; :budget tokens (default-budget); :max-depth
  (:seon.config.render.agent/max-depth); :width (:seon.config.render/width); :outputs, a
  subset of #{:text :hiccup} (#{:text}). Answers {:text :tokens :truncated? :elisions}
  plus :html (hiccup) when asked, or the insufficient-budget error (an error map) when the
  budget cannot show the value's first part."
  {:malli/schema [:function [:=> [:cat :any] :seon.print/result] [:=> [:cat :any :seon.print/opts] :seon.print/result]]}
  ([v] (print-value v {}))
  ([v {:keys [handle budget max-depth width outputs]
       :or {budget default-budget max-depth (config/value :seon.config.render.agent/max-depth)
            width (config/value :seon.config.render/width) outputs #{:text}}}]
   ;; without a handle a cut names no form (its reader would hold no such result)
   (select/show v {:handle handle :budget budget :max-depth max-depth :width width :outputs outputs})))

(defn text
  "`v` as text under budget tokens: the printed text, or the error's message when even
  its first part does not fit."
  {:malli/schema [:function [:=> [:cat :any] :string] [:=> [:cat :any pos-int?] :string]]}
  ([v] (text v default-budget))
  ([v budget]
   (let [r (print-value v {:budget budget})]
     (or (:text r) (:seon.error/message r)))))

;;; Prose: text read as itself (a message, a form's source, a plan, a printed value), not as
;;; a string literal. The one string cutter's (seon.print.select/break-at, its words
;;; seon.print.emit/cut-words), on the printer's sizer.

(defn- source-offset
  "Where in s the character at offset at of s's one-line reading (str/trim of every
  whitespace run as one space) is."
  {:malli/schema [:=> [:cat :string nat-int?] nat-int?]}
  [^String s at]
  (let [n (count s)
        ws? #(Character/isWhitespace (.charAt s (int %)))]
    (loop [i (long (loop [i 0] (if (and (< i n) (ws? i)) (recur (inc i)) i))) c 0]
      (cond (or (>= c at) (>= i n)) i
            (ws? i) (recur (loop [j i] (if (and (< j n) (ws? j)) (recur (inc j)) j)) (inc c))
            :else (recur (inc i) (inc c))))))

(defn prose
  "s as prose under budget tokens, shown as itself (unquoted). It fits: s whole. It does
  not: the longest prefix the budget holds with room for the cut, ended where the one string
  cutter ends a cut (`seon.print.select/break-at`: a line break in its second half, else a
  space in its final quarter), then the cut (`seon.print.emit/cut-words`): how many characters it
  omitted and in how many more lines, and, with opts :source (a form that answers the whole
  text), `(subs source offset)`, the form that reads the rest. A cut after a line break
  stands on its own line, after a space follows it. opts :one-line? reads every whitespace run as one space first (a
  table cell, a stream row); its cut's :offset and :omitted are then the source's (what
  the form reads), and :shown the one line's.
  Answers {:text :shown :truncated?}, and a cut's :omitted :lines :offset and :continuation."
  {:malli/schema [:function [:=> [:cat [:maybe :string] pos-int?] :seon.print/prose]
                  [:=> [:cat [:maybe :string] pos-int? :seon.print/prose-opts] :seon.print/prose]]}
  ([s budget] (prose s budget {}))
  ([s budget {:keys [source one-line?]}]
   (let [whole (or s "")
         ^String s (cond-> whole one-line? (-> (str/replace #"\s+" " ") str/trim))
         cap (select/estimate-chars budget)
         total (count s)]
     (if (<= total cap)
       {:text s :shown s :truncated? false}
       (let [form (fn [at] (when source (list 'subs source at)))
             ;; the cut's words at their widest (every character omitted) bound its room
             breaks (select/line-breaks whole)
             room (max 0 (- cap 1 (count (emit/cut-words (count whole) (select/lines-after breaks (count whole) 0) (form (count whole))))))
             at (select/break-at s (min room total))
             shown (subs s 0 at)
             ;; in the source's own offsets: what (subs source at) reads
             at (if one-line? (source-offset whole at) at)
             omitted (- (count whole) at)
             lines (select/lines-after breaks (count whole) at)
             cont (form at)
             words (emit/cut-words omitted lines cont)]
         (cond-> {:text (if (str/ends-with? shown "\n") (str shown words) (str shown (when-not (or (empty? shown) (str/ends-with? shown " ")) " ") words))
                  :shown shown :truncated? true :omitted omitted :lines lines :offset at}
           cont (assoc :continuation cont)))))))

(defn line
  "Text in one line under budget tokens (a value that is not text printed first): `prose`
  with every whitespace run as one space;
  its cut says what it omitted and, with source (a form that answers the whole text), the
  form that reads the rest. Root's context and the page's one-line rows (the stream)."
  {:malli/schema [:function [:=> [:cat :any pos-int?] :string] [:=> [:cat :any pos-int? :any] :string]]}
  ([s budget] (line s budget nil))
  ([s budget source] (:text (prose (cond-> s (and (some? s) (not (string? s))) (text budget)) budget (cond-> {:one-line? true} source (assoc :source source))))))

(defn more
  "What a bounded list leaves: nil when its shown members are all of total, else `+N more`
  and, with where (the form or place that has them all), where they are."
  {:malli/schema [:=> [:cat nat-int? nat-int? [:maybe :string]] [:maybe :string]]}
  [total shown where]
  (when (> total shown)
    (str "+" (- total shown) " more" (when where (str ": " where)))))

(defn bounded
  "A list shown in part: its first n items, n the config key k's bound (a
  :seon.config.render.list/* key), and the `more` line saying how many of total (default:
  all of items) it left and where they are, nil when it left none. Answers [shown more]."
  {:malli/schema [:function [:=> [:cat [:maybe [:sequential :any]] :keyword [:maybe :string]] [:tuple [:sequential :any] [:maybe :string]]]
                  [:=> [:cat [:maybe [:sequential :any]] :keyword [:maybe :string] nat-int?] [:tuple [:sequential :any] [:maybe :string]]]]}
  ([items k where] (bounded items k where (count items)))
  ([items k where total]
   (let [shown (vec (take (config/value k) items))]
     [shown (more total (count shown) where)])))
