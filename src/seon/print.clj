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
  subset of #{:text :hiccup} (#{:text}); :render, a nested value's own render (seon.render's
  default renders pass it: a value inside the printed one whose schema declares a render shows
  as that render, one line, in its place). Answers {:text :tokens :truncated? :elisions}
  plus :html (hiccup) when asked, or the insufficient-budget error (an error map) when the
  budget cannot show the value's first part."
  {:malli/schema [:function [:=> [:cat :any] :seon.print/result] [:=> [:cat :any :seon.print/opts] :seon.print/result]]}
  ([v] (print-value v {}))
  ([v {:keys [handle budget max-depth width outputs render]
       :or {budget default-budget max-depth (config/value :seon.config.render.agent/max-depth)
            width (config/value :seon.config.render/width) outputs #{:text}}}]
   ;; without a handle a cut names no form (its reader would hold no such result)
   (select/show v (cond-> {:handle handle :budget budget :max-depth max-depth :width width :outputs outputs}
                    render (assoc :render render)))))

(defn code
  "Source text s (a form's :form/src, a test's source, an assertion as written) as code: the
  printer prints it verbatim at its place, as in its file (comments, line breaks and quotes as
  written, every later line indented to where it stands), never as a string literal; cut, it is
  cut by the one string cutter and its cut reads the rest, `(subs (:seon.print/code (get-in
  result/e3 [...])) 1200)`. Plain data, {:seon.print/code s}: a record stores it and reads it
  back as it is. The producer says it is code; the printer never guesses from a string's text.
  Prose (a docstring, a plan, a section's lead) is marked the same way, {:seon.print/prose s}:
  printed as code is, and on the page a paragraph."
  {:malli/schema [:=> [:cat :string] [:map [:seon.print/code :string]]]}
  [s] {:seon.print/code s})

(defn tokens-text
  "A count of tokens as a reader is shown it: seon.print.emit/tokens-text, the printer's one wording of a size (docs/render.md#cut-where-a-value-is-made)."
  {:malli/schema [:=> [:cat nat-int?] :string]}
  [n] (emit/tokens-text n))

(defn cost-text
  "seon.print.emit/cost-text, the one wording of what work cost (docs/render.md#cost-is-shown-one-way)."
  {:malli/schema [:=> [:cat :map] :string]}
  [m] (emit/cost-text m))

(defn text
  "`v` as text under budget tokens: the printed text, or the error's message when even
  its first part does not fit."
  {:malli/schema [:function [:=> [:cat :any] :string] [:=> [:cat :any pos-int?] :string]]}
  ([v] (text v default-budget))
  ([v budget]
   (let [r (print-value v {:budget budget})]
     (or (:text r) (:seon.error/message r)))))

(defn response-text
  "Eval response r as every reader reads it (the model's tool message, the page, an outside
  agent): printed by the one printer under its handle, so a cut names the form that reads its
  rest, `(get-in result/e43 [:value ..])`. Made when r is
  sent or shown, never stored (seon.repl/record-details keeps the value). render (seon.render/model):
  a value whose schema declares a model render prints as that render's value, the printer's own values."
  {:malli/schema [:function [:=> [:cat :map] :string] [:=> [:cat :map [:maybe :seon.host/fn]] :string]]}
  ([r] (response-text r nil))
  ([r render]
   ;; a value that is a page prints whole: its metadata says so as it is made, and its schema's (record-text) when it is read back
   (let [r (cond-> r (:seon.print/full (meta (:value r))) (vary-meta assoc :seon.print/full true))
         shown (cond-> r (:handle r) (-> (dissoc :ms :ticks :bytes) (with-meta (meta r))))
         ;; an eval's cost is one line with its result, what the result costs the context among it; its numbers stay data on the
         ;; record. An eval that recorded none was not run
         cost (fn [tok] (str ", :cost " (pr-str (if (some r [:ms :ticks :bytes]) (emit/cost-text (assoc (select-keys r [:ms :ticks :bytes]) :tok tok)) "not run"))))
         ;; the line is inside the budget: the printer is given what is left once its longest wording is set aside
         room (if (:handle r) (inc (select/chars-tokens (count (cost default-budget)))) 0)
         p (print-value shown (cond-> {:handle (:handle r) :budget (- default-budget room)} render (assoc :render render)))
         text (or (:text p) (:seon.error/message p))]
     ;; spliced after the printed map's last entry: the text is the only place its own size is known
     (if (and (:handle r) (:text p) (str/ends-with? text "}"))
       (str (subs text 0 (dec (count text))) (cost (select/chars-tokens (count text))) "}")
       text))))

(defn read-rest
  "What a cut's continuation form (seon.print.emit/continuation: get-in, nth, seq, key, val, a
  mark's keyword, subs and drop over its handle) reads from root, the value its handle names, so
  the page shows a cut's rest as the model would read it, from the record (a marked text's rest
  keeps its mark). A form of any other operator reads nil."
  {:malli/schema [:=> [:cat :any :any :any] :any]}
  [form handle root]
  (cond (= form handle) root
        (not (seq? form)) form
        :else (let [[op & args] form
                    [x y] (map #(read-rest % handle root) args)]
                (case op
                  get-in (get-in x y) nth (nth x y nil) seq (seq x) key (key x) val (val x)
                  ;; a marked text's rest keeps its mark: code reads on as code
                  subs (let [k (first (first args))] (if (and (seq? (first args)) (#{:seon.print/code :seon.print/prose} k)) {k (subs x y)} (subs x y)))
                  drop (vec (drop x y))
                  (when (keyword? op) (get x op))))))

(defn record-text
  "An eval fact's response (:seon.eval/response) as it reads, printed now (`response-text`): its stored value printed,
  through render (seon.render/model) under the schema the eval kept (:seon.eval/schema: the value's
  :seon.render/schema, which metadata does not keep through the store), so a record reads as its live response did
  (a prove's one line, not its whole map). A value too large to keep prints as the marker of what it was
  (:seon.value/gone). See docs/render.md#a-render-is-a-value-printed-once."
  {:malli/schema [:function [:=> [:cat :map] :string] [:=> [:cat :map [:maybe :seon.host/fn]] :string]]}
  ([e] (record-text e nil))
  ([e render]
   (let [r (:seon.eval/response e) v (:value r) k (:seon.eval/schema e)]
     ;; only an eval that kept a schema can render: a stored value's nested metadata is gone, so a render
     ;; passed for any other would be asked of every value printed and answer nothing
     (if (and k render (instance? clojure.lang.IObj v))
       (let [v (vary-meta v assoc :seon.render/schema k)
             ;; what the store does not keep of a value, its schema says (render answers :full for a schema that declares it)
             v (cond-> v (:full (render v)) (vary-meta assoc :seon.print/full true))]
         (response-text (assoc r :value v) render))
       (response-text r)))))

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

(defn line-prose
  "`line`'s prose ({:text :shown :truncated?} and a cut's members, `prose`): what a reader that
  says whether the line was cut takes."
  {:malli/schema [:=> [:cat :any pos-int? :any] :seon.print/prose]}
  [s budget source] (prose (cond-> s (and (some? s) (not (string? s))) (text budget)) budget (cond-> {:one-line? true} source (assoc :source source))))

(defn line
  "Text in one line under budget tokens (a value that is not text printed first): `prose`
  with every whitespace run as one space;
  its cut says what it omitted and, with source (a form that answers the whole text), the
  form that reads the rest. Root's context and the page's one-line rows (the stream)."
  {:malli/schema [:function [:=> [:cat :any pos-int?] :string] [:=> [:cat :any pos-int? :any] :string]]}
  ([s budget] (line s budget nil))
  ([s budget source] (:text (line-prose s budget source))))

