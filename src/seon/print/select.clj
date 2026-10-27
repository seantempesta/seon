(ns seon.print.select
  "Seon's value printer: a value selected under one token budget, breadth before depth, every
  cut carrying the form that reads its rest on the result handle; one selection for text and
  hiccup.

  Need: every agent reply's printed value and the dashboard's value panel. Taken from Seon's
  `seon.render.value` (clone a780860c3; its Rules 1-10 hold here unchanged) and cut to the
  path proto reaches: a plain value, a result handle, a budget and a width. Render pairs,
  databases, routes, cursors, the core printer's fallback text and the stored artifacts
  stay behind (notes BAGGAGE (proto/BAGGAGE.md))."
  (:require [seon.print.emit :as emit] [seon.config :as config]))

;;; Tokens (Seon's `seon.ai.tokens`, shipped prior): the one sizer.

(def ^:private chars-per-token (config/value :seon.config.ai/chars-per-token-prior))

(defn chars-tokens
  "Estimated tokens of n characters: the one sizer's rule (`estimate` is it over a text's length)."
  {:malli/schema [:=> [:cat nat-int?] :int]}
  [n]
  (long (Math/floor (/ n chars-per-token))))

(defn estimate
  "Estimated tokens of `text`."
  {:malli/schema [:=> [:cat :string] :int]}
  [text]
  (chars-tokens (count text)))

(defn estimate-chars
  "Characters a token budget holds."
  {:malli/schema [:=> [:cat :int] :int]}
  [tokens]
  (long (Math/floor (* tokens chars-per-token))))

;;; Allocation config keys (the config's `:seon.config.render.agent/*`, ported from Seon's).

(def ^:private limits
  {:whole-width (config/value :seon.config.render.agent/whole-width) :max-name (config/value :seon.config.render.agent/max-name)
   :max-number-digits (config/value :seon.config.render.agent/max-number-digits)
   :sort-bound (config/value :seon.config.render.agent/sort-bound)})

;;; Exact classes, by row of the table. Named once: on Jolt a set literal of class symbols is
;;; built on every evaluation (1,072 B a call; the JVM makes it a constant), and classify runs
;;; several per value.

(def ^:private sortable-classes #{clojure.lang.PersistentHashMap clojure.lang.PersistentHashSet clojure.lang.PersistentArrayMap})
(def ^:private integer-classes #{Long Integer Short Byte})
(def ^:private float-classes #{Double Float})
(def ^:private map-classes #{clojure.lang.PersistentArrayMap clojure.lang.PersistentHashMap clojure.lang.PersistentTreeMap})
(def ^:private vector-classes #{clojure.lang.PersistentVector clojure.lang.MapEntry})
(def ^:private set-classes #{clojure.lang.PersistentHashSet clojure.lang.PersistentTreeSet})


(defn keyword-lookup?
  "May `value` be asked for a keyword key without running foreign code: an exact array or hash
  map, or a sorted map whose keys are keywords (Jolt cannot tell a sorted map's comparator)."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [value]
  (let [c (class value)]
    (or (identical? c clojure.lang.PersistentArrayMap)
        (identical? c clojure.lang.PersistentHashMap)
        (and (identical? c clojure.lang.PersistentTreeMap)
             (or (zero? (count value)) (identical? (class (key (first value))) clojure.lang.Keyword))))))

(defn code?
  "Is x marked text, read as itself: code (seon.print/code), a map of the one key
  :seon.print/code to its text, or prose (seon.print/prose), of :seon.print/prose; plain data
  that stores and reads back as it is. Its mark's key, else nil."
  {:malli/schema [:=> [:cat :any] [:maybe [:enum :seon.print/code :seon.print/prose]]]}
  [x]
  (when (and (map? x) (== 1 (count x)) (keyword-lookup? x))
    (cond (string? (:seon.print/code x)) :seon.print/code
          (string? (:seon.print/prose x)) :seon.print/prose)))

(defn- escaped-width
  "Characters the string literal spends on chars [from, to) of `s`; a newline stays a line break."
  {:malli/schema [:=> [:cat :string :int :int] :int]}
  [^String s from to]
  (loop [i (long from) width 0]
    (if (< i to)
      (let [c (.charAt s i)]
        (recur (inc i) (+ width (if-let [e (when-not (= \newline c) (char-escape-string c))] (count e) 1))))
      width)))

(defn- integer-digits
  "An upper bound on a BigInteger's decimal spelling, from its bit length."
  {:malli/schema [:=> [:cat :any] :int]}
  [^java.math.BigInteger b]
  (+ 2 (long (Math/ceil (* (.bitLength b) (Math/log10 2))))))

(defn- throwable-value
  "A Throwable as plain data: Throwable->map's :via (type, message and ex-data per link), without
  each link's frame."
  {:malli/schema [:=> [:cat :seon.host/throwable] [:map [:via [:vector :any]]]]}
  [t] {:via (mapv #(cond-> (dissoc % :at) (nil? (:message %)) (dissoc :message)) (:via (Throwable->map t)))})

(defn- seq-cells
  "A seq's first `limit` members, realizing a lazy seq only as far as it prints."
  {:malli/schema [:=> [:cat :any number?] :map]}
  [x limit]
  (loop [cell x out (transient [])]
    (cond
      (or (nil? cell) (identical? (class cell) clojure.lang.PersistentList$EmptyList)) {:members (persistent! out)}
      (>= (count out) limit) {:members (persistent! out) :more? true}
      (identical? (class cell) clojure.lang.LazySeq) (recur (seq cell) out)
      (instance? clojure.lang.ISeq cell) (recur (rest cell) (conj! out (first cell)))
      :else {:members (persistent! out) :pending? true})))

(defn- list-row
  "A sequential value as a list, with a total when every member was reached or it is Counted."
  {:malli/schema [:=> [:cat :any :any number?] :map]}
  [x cells limit]
  (let [{:keys [members pending? more?]} (seq-cells cells limit)]
    (cond-> {:kind :coll :face :seon.print/list :open "(" :close ")" :members (seq members)}
      (not (or pending? more?)) (assoc :total (count members))
      (and more? (counted? x)) (assoc :total (count x))
      (or pending? (and more? (not (counted? x)))) (assoc :pending? true))))

(defn- member-rank
  "Where one keyword-keyed member sorts: a scalar, short string or pair of scalars before a long
  string before a collection."
  {:malli/schema [:=> [:cat :any] :int]}
  [member]
  (let [v (when (map-entry? member) (val member))]
    (cond (and (coll? v) (counted? v) (<= (count v) 2) (not-any? coll? (if (map? v) (vals v) v))) 0
          (coll? v) 2
          (and (string? v) (> (count v) (:whole-width limits))) 1
          :else 0)))

(defn- sorts?
  "True when an exact map or hash set of at most `:sort-bound` exact keywords shows its members in
  an order of its own."
  {:malli/schema [:=> [:cat :any :seon.host/fn] :boolean]}
  [x key-of]
  (boolean
   (and (sortable-classes (class x))
        (<= (count x) (:sort-bound limits))
        (every? #(identical? (class (key-of %)) clojure.lang.Keyword) x))))

(defn- held-order
  "Members in the order they show. When `x` sorts, as `[index member]`, `index` their place in
  `seq`: short members first, then a hash map or set alphabetically, an array map in its own
  order. Otherwise `seq`'s own order, each member's index its place in it."
  {:malli/schema [:=> [:cat :any :seon.host/fn] [:maybe [:sequential :any]]]}
  [x key-of]
  (if (sorts? x key-of)
    ;; each member's sort key made once, not once per comparison as sort-by would (the keys are
    ;; distinct, so the order is the same)
    (let [array? (identical? (class x) clojure.lang.PersistentArrayMap)]
      (->> x
           (into [] (map-indexed (fn [index member]
                                   (let [k (key-of member)]
                                     [(if array? [(member-rank member) index] [(member-rank member) (namespace k) (name k)])
                                      [index member]]))))
           (sort-by first)
           (mapv second)))
    (seq x)))

(defn- scalar
  "A value shown whole as `text`: its leaf (`leaf`), whose width is the text's."
  {:malli/schema [:=> [:cat :qualified-keyword :any :string] :map]}
  [face value text]
  (let [width (count text)]
    {:seon.print/face face :seon.print/value value :seon.print/text text :kind :leaf :flat width :ub width}))

(defn- big-number
  "A big number's leaf: its text when fits?, else its tag and how many digits it holds."
  {:malli/schema [:=> [:cat :any :boolean :string :string nat-int?] :map]}
  [x fits? text tag digits]
  (scalar :seon.print/number x (if fits? text (str "#" tag "[≈" digits " digits]"))))

(defn- seen-form
  "The form that reads x again when an earlier response showed it (the context's :seen), else
  nil: a string by its text, a map, set or vector by identity (the same value object), so no
  lookup hashes a collection's contents (a member may be lazy and endless); the printed value
  itself is never its own handle."
  {:malli/schema [:=> [:cat :any :any :map] :any]}
  [x c context]
  (when-let [{:keys [strings colls]} (:seen context)]
    (when-not (identical? x (:root context))
      (cond (identical? c String) (get strings x)
            (or (map-classes c) (set-classes c) (vector-classes c))
            (some (fn [[v form]] (when (identical? v x) form)) (get colls (System/identityHashCode x)))))))

(declare show)

(defn- classify
  "One row of the permitted-operation table: exact classes for scalars, maps and sets; seqs and
  other sequentials by interface, stepped to the context's `:max-children`; anything else is its
  class and identity. A scalar with its text, and a name of at most `:max-name` characters, is
  its leaf already."
  {:malli/schema [:=> [:cat :any :seon.print/context] :map]}
  [x context]
  (let [c (class x) limit (:max-children context)
        ;; a nested map whose schema declares a render shows as that render in its place: its text,
        ;; one line (:text), or a value the render chooses to show (:value), printed here as any value
        shown (when (and (:render context) (map-classes c) (not (identical? x (:root context)))) ((:render context) x))
        rendered (when (contains? shown :text) shown)
        summary (when (contains? shown :value) shown)
        ;; a call ([#'f opts value], a vector headed by a var, as seon.web.html expands it, whose opts
        ;; carry a budget): its value printed at that budget and handle, so a reader sees the cut the page shows
        ;; and the form that reads the rest. Its budget is its own, beside the enclosing value's, as the
        ;; page's writer gives it; a plain vector headed by a var, with no budgeted opts, prints as data
        call (when (and (vector? x) (var? (first x)) (pos-int? (:budget (nth x 1 nil)))) x)
        ;; a value an earlier response showed is the form that reads it again (seen-form); a
        ;; print given no earlier responses looks nothing up
        again (when (:seen context) (seen-form x c context))]
    (cond
      again (scalar :seon.print/rendered x (pr-str again))
      summary (classify (:value summary) context)
      call (let [[f o v] call
                 r (show v (cond-> {:handle (:handle o) :budget (:budget o) :max-depth (:max-depth context)
                                    :width (:width context) :outputs #{:text}}
                             (:render context) (assoc :render (:render context))))]
             (scalar :seon.print/rendered x (str "[" (pr-str f) " " (pr-str o) " " (or (:text r) (:seon.error/message r)) "]")))
      rendered (assoc (scalar :seon.print/rendered x (:text rendered)) :seon.print/html (:html rendered))
      (nil? x) (scalar :seon.print/nil nil "nil")
      (identical? c Boolean) (scalar :seon.print/boolean x (str x))
      (integer-classes c) (scalar :seon.print/number x (str x))
      (float-classes c)
      (scalar :seon.print/number x (let [d (double x)]
                                     (cond (Double/isNaN d) "##NaN"
                                           (= d Double/POSITIVE_INFINITY) "##Inf"
                                           (= d Double/NEGATIVE_INFINITY) "##-Inf"
                                           :else (str x))))
      (identical? c Character) (scalar :seon.print/char x (if-let [n (char-name-string x)] (str "\\" n) (str "\\" x)))
      (identical? c String) {:kind :string :value x :total (.length ^String x)}
      ;; code (seon.print/code): its text verbatim, cut by the one string cutter
      ;; the count first: only a one-entry map asks code? (a contracted call)
      (and (map-classes c) (== 1 (count x)) (code? x)) (let [k (code? x) s (get x k)] {:kind :string :value s :total (.length ^String s) :code? k})
      ;; a name's node carries its text, which a cut of it replaces
      (or (identical? c clojure.lang.Keyword) (identical? c clojure.lang.Symbol))
      (let [face (if (keyword? x) :seon.print/keyword :seon.print/symbol)
            text (str (when (keyword? x) ":") (when (namespace x) (str (namespace x) "/")) (name x))]
        (if (<= (count text) (:max-name context))
          (scalar face x text)
          {:kind :name :node {:seon.print/face face :seon.print/value x} :text text}))
      (identical? c java.util.UUID) (scalar :seon.print/uuid x (str "#uuid \"" x "\""))
      (identical? c java.util.Date) (scalar :seon.print/inst x (str "#inst \"" (.toInstant ^java.util.Date x) "\""))
      ;; Jolt unifies a small BigInteger with Long: a BigInt that fits prints whole
      (identical? c clojure.lang.BigInt)
      (let [b (biginteger x)] (big-number x (or (< (.bitLength b) 64) (<= (integer-digits b) (:max-number-digits limits))) (str b "N") "bigint" (integer-digits b)))
      (identical? c clojure.lang.Ratio)
      (let [d (+ (integer-digits (biginteger (numerator x))) (integer-digits (biginteger (denominator x))))]
        (big-number x (<= d (:max-number-digits limits)) (str (numerator x) "/" (denominator x)) "ratio" d))
      (identical? c java.math.BigDecimal)
      (let [u (integer-digits (biginteger (.unscaledValue ^java.math.BigDecimal x)))]
        (big-number x (<= (+ u (count (str (.scale ^java.math.BigDecimal x)))) (:max-number-digits limits)) (str x "M") "bigdec" u))
      (map-classes c)
      {:kind :map :face :seon.print/map :open "{" :close "}" :total (count x) :indexed? true
       :lookup? (keyword-lookup? x) :sorted? (sorts? x key) :members (held-order x key)}
      (set-classes c)
      {:kind :coll :face :seon.print/set :open "#{" :close "}" :total (count x) :ordered? true :indexed? true
       :sorted? (sorts? x identity) :members (held-order x identity)}
      ;; a vector type prints as a vector, every seq and other Sequential as a list
      (or (vector-classes c) (instance? clojure.lang.IPersistentVector x))
      {:kind :coll :face :seon.print/vector :open "[" :close "]" :total (count x) :members (seq x)}
      (instance? clojure.lang.ISeq x) (list-row x x limit)
      (instance? clojure.lang.Sequential x) (list-row x (seq x) limit)
      (identical? c clojure.lang.Var)
      {:kind :scalar :node {:seon.print/face :seon.print/var
                            :seon.print/name (str (.name (.ns ^clojure.lang.Var x)) "/" (.sym ^clojure.lang.Var x))}}
      (identical? c clojure.lang.Namespace) (scalar :seon.print/symbol (ns-name x) (str "#namespace[" (ns-name x) "]"))
      (identical? c Class) {:kind :scalar :node {:seon.print/face :seon.print/class :seon.print/name (.getName ^Class x)}}
      (instance? Throwable x) (assoc (classify (throwable-value x) context) :prefix "#error " :face :seon.print/throwable)
      :else
      {:kind :scalar :node {:seon.print/face :seon.print/object
                            ;; a TaggedLiteral's class is opaque on Jolt
                            :seon.print/class (if (tagged-literal? x) "clojure.lang.TaggedLiteral" (clojure.lang.Compiler/demunge (.getName c)))
                            :seon.print/rep (str "0x" (Integer/toHexString (System/identityHashCode x)))}})))

(defn- leaf-width
  "The flat width of a var, class or object node (every other scalar is `scalar`'s, its
  width its text's)."
  {:malli/schema [:=> [:cat :map] :int]}
  [node]
  (case (:seon.print/face node)
    :seon.print/var (+ 2 (count (:seon.print/name node)))
    :seon.print/class (count (:seon.print/name node))
    (+ 9 (count (:seon.print/class node)) (if-some [rep (:seon.print/rep node)] (inc (count rep)) 0))))

(defn- node-continuation
  "The form a cut of `node` prints to read its rest; the whole collection for a map or set shown
  sorted; the rest of a long keyword's or symbol's printed text. Nil without a handle."
  {:malli/schema [:=> [:cat :seon.print/alloc] :any]}
  [node]
  (when (:requery-id node)
    (let [text? (#{:string :name} (:kind node))]
      (emit/continuation
       {:seon.print/requery-id (:requery-id node)
        :seon.print/code? (:code? node)
        :seon.print/name? (= :name (:kind node))
        :seon.print/elision-unit (cond text? :characters (:pending? node) :pending :else :children)
        :seon.render.data/path (:path node)
        :seon.render.data/next-offset (cond (:sorted? node) 0
                                            text? (:shown node)
                                            :else (:n node))}))))

;;; The one string cutter: a string in a value (a literal) and prose (text shown as itself,
;;; seon.print/prose) end their cut at the same place and say the same words (emit/cut-words).

(defn break-at
  "Where a cut of s that may keep `room` characters ends: after the last line break in the
  room's second half, else after the last space in its final quarter, else at the room."
  {:malli/schema [:=> [:cat :string nat-int?] nat-int?]}
  [^String s room]
  (let [nl (.lastIndexOf s "\n" (int (dec room)))
        sp (.lastIndexOf s " " (int (dec room)))]
    (cond (and (pos? room) (>= nl (quot room 2))) (inc nl)
          (and (pos? room) (>= sp (- room (quot room 4)))) (inc sp)
          :else room)))

(defn line-breaks
  "Where s's line breaks are, ascending: found once per string, so a cut counts its lines
  (`lines-after`) without reading the text again."
  {:malli/schema [:=> [:cat :string] [:vector nat-int?]]}
  [^String s]
  (loop [i 0 breaks (transient [])]
    (let [j (.indexOf s "\n" (int i))]
      (if (neg? j) (persistent! breaks) (recur (inc j) (conj! breaks j))))))

(defn lines-after
  "Lines that begin after offset at of a text total characters long whose line breaks are
  breaks (`line-breaks`): each line break at or past it starts one (but one that ends the
  text), and the offset itself starts one when it sits at a line's start. A binary search."
  {:malli/schema [:=> [:cat [:vector nat-int?] nat-int? nat-int?] nat-int?]}
  [breaks total at]
  (let [k (loop [lo 0 hi (count breaks)]
            (if (< lo hi)
              (let [mid (quot (+ lo hi) 2)] (if (< (nth breaks mid) at) (recur (inc mid) hi) (recur lo mid)))
              lo))
        after (- (count breaks) k (if (and (< k (count breaks)) (= (peek breaks) (dec total))) 1 0))
        start? (and (< at total) (or (zero? at) (and (pos? k) (= (nth breaks (dec k)) (dec at)))))]
    (+ after (if start? 1 0))))

(defn- cut-text
  "The cut's text after a node's shown part, as `emit/elided-text` prints it."
  {:malli/schema [:=> [:cat :seon.print/alloc] :string]}
  [node]
  (let [form (node-continuation node)]
    (case (:kind node)
      ;; code's cut stands a space after its shown text (emit/elided-text)
      :string (str (when (:code? node) " ") (emit/cut-words (- (:total node) (:shown node)) (lines-after (:breaks node) (:total node) (:shown node)) form))
      ;; a name has no line breaks: the same cut words, its unit and the form that reads the rest
      :name (emit/cut-words (- (:total node) (:shown node)) 0 form)
      (cond-> (if (:pending? node) "…?" (str "…" (- (:total node) (:n node)))) form (str " " (emit/form-text form))))))

(defn- more? {:malli/schema [:=> [:cat :seon.print/alloc] :boolean]} [node]
  (case (:kind node)
    (:string :name) (< (:shown node) (:total node))
    (:map :coll) (boolean (or (:pending? node) (seq (:pending node)) (< (:n node) (:total node))))
    false))

(defn- flat-width
  "A node's flat width from its own fields and its children's widths."
  {:malli/schema [:=> [:cat :seon.print/alloc] :int]}
  [node]
  (case (:kind node)
    :leaf (:flat node)
    ;; a string literal's quotes; code has none
    :string (+ (if (:code? node) 0 2) (:shown-width node) (if (more? node) (count (cut-text node)) 0))
    :name (+ (:shown-width node) (if (more? node) (count (cut-text node)) 0))
    (:map :coll) (let [lines (+ (:n node) (if (more? node) 1 0))]
                   (+ (count (str (:prefix node) (:open node) (:close node))) (:sum node)
                      (* (if (= :map (:kind node)) 2 1) (max 0 (dec lines)))
                      (if (more? node) (count (cut-text node)) 0)))
    :entry (inc (+ (:flat (nth (:kids node) 0)) (:flat (nth (:kids node) 1))))))

(defn- fill-end
  "Where a cut string grown into `room` more characters of literal ends: at most `room` wide,
  back to where the one cutter breaks it (`break-at`) inside the growth."
  {:malli/schema [:=> [:cat :seon.print/alloc number?] :int]}
  [node room]
  (let [^String text (:value node)
        limit (:total node)
        to (loop [i (long (:shown node)) width 0]
             (if (< i limit)
               (let [c (.charAt text i)
                     width (+ width (cond (:code? node) (if (= \newline c) (inc (:indent node)) 1)
                                          :else (if-let [e (when-not (= \newline c) (char-escape-string c))] (count e) 1)))]
                 (if (<= width room) (recur (inc i) width) i))
               i))
        at (break-at text to)]
    (if (and (< to limit) (> at (:shown node))) at to)))

(defn- insufficient-budget
  "The declared error when the budget cannot show the root or its first member."
  {:malli/schema [:=> [:cat :seon.print/context number?] :seon.error/base]}
  [context needed]
  {:seon.error/type :seon.render.value/insufficient-floor-budget-error
   :seon.error/operation (symbol #'seon.print.select/insufficient-budget)
   :seon.error/message (str "The value printer's budget of " (:budget context) " characters cannot show this value's "
                            "first part, which needs " needed ".")
   :seon.error/member :seon.render.profile/token-budget
   :seon.error/expected "a budget covering the root and its first member"
   :seon.error/offending (:budget context)
   :seon.error/data {:seon.render.data/path []}
   :seon.error/fix "Raise :seon.render.profile/token-budget, or read a deeper path."
   :seon.render.value/budget (:budget context)
   :seon.render.value/needed needed})

(defn- leaf
  "Print node `node` as a leaf: itself with its width, its flat width `:flat` and its charge `:ub`.
  A leaf is its own print node (the tree takes it as it is; the emitter reads only the print
  keys), and nothing reads its place (depth, indentation, path): a leaf is never cut."
  {:malli/schema [:=> [:cat :map] :seon.print/alloc]}
  [node]
  (let [width (leaf-width node)]
    (assoc node :kind :leaf :flat width :ub width)))

(defn- placed
  "The fields of a node that can be cut: its place, and the handle its cut reads through."
  {:malli/schema [:=> [:cat :seon.print/context :int [:maybe [:vector :any]] :int] :map]}
  [context depth path indent]
  (cond-> {:depth depth :path path :indent indent :kids []}
    (:requery-id context) (assoc :requery-id (:requery-id context))))

(defn- node-of
  "A node for classified `c` reached at `path` and `depth`, its lines indented `indent`: nothing
  yet shown. A leaf is `c` itself; `path` is read only for a node that can be cut (any other)."
  {:malli/schema [:=> [:cat :seon.print/context :map :int [:maybe [:vector :any]] :int] :seon.print/alloc]}
  [context c depth path indent]
  (case (:kind c)
    :leaf c
    :scalar (leaf (:node c))
    ;; a name longer than :max-name
    :name (assoc (placed context depth path indent) :kind :name :node (:node c) :text (:text c) :value (:text c) :total (count (:text c)))
    :string (cond-> (assoc (placed context depth path indent) :kind :string :value (:value c) :total (:total c) :breaks (line-breaks (:value c)))
              (:code? c) (assoc :code? (:code? c)))
    (merge (placed context depth path indent)
           (select-keys c [:kind :face :open :close :total :pending? :prefix :ordered? :indexed? :sorted? :lookup?])
           {:pending (:members c)})))

(defn- make-node
  "A node for `value` reached at `path` and `depth`, its lines indented `indent`: classified,
  nothing yet shown."
  {:malli/schema [:=> [:cat :seon.print/context :any :int [:vector :any] :int] :seon.print/alloc]}
  [context value depth path indent]
  (node-of context (classify value context) depth path indent))

(defn- member-indent {:malli/schema [:=> [:cat :seon.print/alloc] :int]} [node]
  (+ (:indent node) (if (= :seon.print/set (:face node)) 2 1)))

(defn- breaks-before
  "How many of a text's line breaks (`line-breaks`) sit before offset at: a binary search."
  {:malli/schema [:=> [:cat [:vector nat-int?] nat-int?] nat-int?]}
  [breaks at]
  (loop [lo 0 hi (count breaks)]
    (if (< lo hi)
      (let [mid (quot (+ lo hi) 2)] (if (< (nth breaks mid) at) (recur (inc mid) hi) (recur lo mid)))
      lo)))

(defn- code-width
  "Characters code `node`'s first `to` characters spend: each itself, and each line break its
  indentation more (every later line starts at the node's place)."
  {:malli/schema [:=> [:cat :seon.print/alloc :int] :int]}
  [node to]
  (+ to (* (:indent node) (breaks-before (:breaks node) to))))

(defn- shown-to
  "String or name `node` showing its first `to` characters."
  {:malli/schema [:=> [:cat :seon.print/alloc :int] :seon.print/alloc]}
  [node to]
  (assoc node :shown to :shown-width (cond (= :name (:kind node)) to
                                           (:code? node) (code-width node to)
                                           :else (escaped-width (:value node) 0 to))))

(defn- entry
  "A map's entry of key and value nodes `kids`, its lines indented `indent`; sized when both kids
  are (flat-width's :entry row, and the charge's). An entry is never cut, so it carries no
  depth or place."
  {:malli/schema [:=> [:cat :seon.print/context :int [:vector :seon.print/alloc]] :seon.print/alloc]}
  [context indent kids]
  (let [k (nth kids 0) v (nth kids 1)]
    (if (and (:ub k) (:ub v))
      (let [flat (+ 1 (:flat k) (:flat v))]
        {:kind :entry :indent indent :kids kids :flat flat
         ;; a break between key and value costs the value's line its indentation; an entry of two
         ;; leaves that fits the line from its indentation never breaks (the layout's group fits)
         :ub (+ flat (- (:ub k) (:flat k)) (- (:ub v) (:flat v))
                (if (and (:breaks? context)
                         (not (and (= :leaf (:kind k)) (= :leaf (:kind v)) (< (+ indent 2 flat) (:width context)))))
                  (+ indent 2) 0))})
      {:kind :entry :indent indent :kids kids})))

(defn- sized
  "`node` with its flat width `:flat` and its charge `:ub`: the flat width plus, when lines may
  break, the indentation of every line it holds, an upper bound on its laid-out length."
  {:malli/schema [:=> [:cat :seon.print/context :seon.print/alloc] :seon.print/alloc]}
  [context node]
  (case (:kind node)
    ;; a leaf is made sized
    :leaf node
    (:string :name) (let [flat (flat-width node)] (assoc node :flat flat :ub flat))
    :entry (entry context (:indent node) (:kids node))
    ;; a map or collection
    (let [kids (:kids node)
          node (assoc node :n (count kids) :sum (reduce (fn [n kid] (+ n (:flat kid))) 0 kids))
          flat (flat-width node)
          breaks? (:breaks? context)
          nested (+ (- flat (:sum node)) (reduce (fn [n kid] (+ n (:ub kid))) 0 kids))]
      (assoc node :flat flat
             :ub (+ nested (if breaks? (* (member-indent node) (max 0 (dec (+ (:n node) (if (more? node) 1 0))))) 0))))))

(defn- separator
  "What one more member line costs at most: its separator and, when it breaks, its indentation."
  {:malli/schema [:=> [:cat :seon.print/context :seon.print/alloc] :int]}
  [context node]
  (+ (if (= :map (:kind node)) 2 1) (if (:breaks? context) (member-indent node) 0)))

(defn- bound? {:malli/schema [:=> [:cat :seon.print/context :seon.print/alloc] :boolean]} [context node]
  (>= (:depth node) (:max-depth context)))

(defn- child
  "Member `held` of collection `parent`, shown `i`th; a map's member is its entry of key and value."
  {:malli/schema [:=> [:cat :seon.print/context :seon.print/alloc :any :int] :seon.print/alloc]}
  [context parent held i]
  (let [depth (inc (:depth parent))
        indent (member-indent parent)
        path (:path parent)
        ;; a sorted map's or set's member carries its iteration index; any other member is
        ;; shown in iteration order, `i`th (held-order)
        paired? (and (:indexed? parent) (:sorted? parent))
        index (if paired? (nth held 0) i)
        member (if paired? (nth held 1) held)]
    ;; a classified leaf is its node; any other member's place is made, since it can be cut
    (if (= :map (:kind parent))
      (let [k (classify (key member) context) v (classify (val member) context)]
        (entry context indent
               [(case (:kind k)
                  :leaf k
                  :scalar (node-of context k depth nil indent)
                  (node-of context k depth (conj path [:seon.render.data/entry index :seon.render.data/key]) indent))
                (case (:kind v)
                  :leaf v
                  :scalar (node-of context v depth nil (+ indent 2))
                  (node-of context v depth
                           (conj path (if (and (:lookup? parent) (keyword? (key member)))
                                        (key member)
                                        [:seon.render.data/entry index :seon.render.data/val]))
                           (+ indent 2)))]))
      ;; `get-in` reads an index of a vector only: a seq's member is read with `nth`
      (let [c (classify member context)]
        (case (:kind c)
          :leaf c
          :scalar (node-of context c depth nil indent)
          (node-of context c depth
                   (conj path (if (or (:indexed? parent) (not= :seon.print/vector (:face parent))) [:seon.render.data/nth index] index))
                   indent))))))

(defn- whole
  "`node` shown whole when its charge is at most `cap`, else nil; the work stops once the charge
  passes `cap`."
  {:malli/schema [:=> [:cat :seon.print/context :seon.print/alloc number?] [:maybe :seon.print/alloc]]}
  [context node cap]
  (case (:kind node)
    ;; a leaf is sized
    :leaf (when (<= (:flat node) cap) node)
    (:string :name)
    (when (and (<= (:total node) cap) (or (= :string (:kind node)) (<= (:total node) (:max-name context))))
      (let [shown (sized context (shown-to node (:total node)))]
        (when (<= (:ub shown) cap) shown)))
    :entry
    ;; nth, not first: first of a vector makes a seq
    (when-let [k (whole context (nth (:kids node) 0) cap)]
      (when-let [v (whole context (nth (:kids node) 1) (- cap (:ub k)))]
        ;; an entry made of sized kids is sized already
        (let [kids (:kids node)
              shown (if (and (identical? k (nth kids 0)) (identical? v (nth kids 1)) (:ub node))
                      node
                      (sized context (assoc node :kids [k v])))]
          (when (<= (:ub shown) cap) shown))))
    (:map :coll)
    (when (and (not (:pending? node)) (or (not (bound? context node)) (not (seq (:pending node)))))
      (let [sep (separator context node)
            open (count (str (:prefix node) (:open node) (:close node)))]
        (loop [kids (transient []) spent open members (:pending node)]
          (if-let [[held & more] (seq members)]
            (let [cost (if (pos? (count kids)) sep 0)]
              (when-let [shown (whole context (child context node held (count kids)) (- cap spent cost))]
                (recur (conj! kids shown) (+ spent cost (:ub shown)) more)))
            (let [shown (sized context (assoc node :kids (persistent! kids) :pending nil))]
              (when (and (not (more? shown)) (<= (:ub shown) cap)) shown))))))))

(defn- collapsed
  "`node` at its least: a collection with no member, a string with no character, a name with
  `:whole-width` characters."
  {:malli/schema [:=> [:cat :seon.print/context :seon.print/alloc] :seon.print/alloc]}
  [context node]
  (sized context (case (:kind node)
                   :string (shown-to node 0)
                   :name (shown-to node (min (:whole-width limits) (:total node)))
                   (:map :coll) (assoc node :kids [])
                   node)))

(defn- minimal
  "`node`'s least form: whole when that is at most `:whole-width` wide or no wider than its cut,
  else collapsed; an entry is its key and value at their least."
  {:malli/schema [:=> [:cat :seon.print/context :seon.print/alloc] :seon.print/alloc]}
  [context node]
  (if (= :entry (:kind node))
    (sized context (update node :kids (fn [kids] (mapv #(minimal context %) kids))))
    (let [least (collapsed context node)]
      (or (whole context node (max (:whole-width limits) (:ub least))) least))))

(declare allot)

(defn- fill-string
  "String or name `node` showing as many characters as `budget` holds beside its cut."
  {:malli/schema [:=> [:cat :seon.print/context :seon.print/alloc number?] :seon.print/alloc]}
  [context node budget]
  (let [least (collapsed context node)]
    (loop [room (- budget (:ub least))]
      (let [to (if (pos? room) (fill-end least room) (:shown least))
            grown (if (= to (:shown least)) least (sized context (shown-to node to)))
            over (- (:ub grown) budget)]
        (if (or (not (pos? over)) (= to (:shown least)))
          grown
          (recur (- room over)))))))

(defn- shows? {:malli/schema [:=> [:cat :seon.print/alloc] :boolean]} [node]
  (case (:kind node)
    (:map :coll) (pos? (:n node))
    (:string :name) (pos? (:shown node))
    true))

(defn- cut-reserve
  "An upper bound on what a collection's one cut costs once members show."
  {:malli/schema [:=> [:cat :seon.print/alloc] :int]}
  [node]
  (+ (count " (drop  )") (count (str (:total node 0)))))

(defn- first-at-least
  "Collection `node` showing only its first member, at its least, before its cut: the floor
  fill-members holds to and select's error measures."
  {:malli/schema [:=> [:cat :seon.print/context :seon.print/alloc] :seon.print/alloc]}
  [context node]
  (let [[held & more] (seq (:pending node))]
    (sized context (assoc node :kids [(minimal context (child context node held 0))] :pending more))))

(defn- elisions
  "How many nodes of the allotted tree under `node` show less than they hold, walked once."
  {:malli/schema [:=> [:cat :seon.print/alloc] nat-int?]}
  [node]
  (loop [stack (list node) k 0]
    (if-let [n (first stack)]
      ;; a leaf shows all it holds
      (recur (into (rest stack) (:kids n)) (if (and (not= :leaf (:kind n)) (more? n)) (inc k) k))
      k)))

(defn- elides? {:malli/schema [:=> [:cat :seon.print/alloc] :boolean]} [node]
  (pos? (elisions node)))

(defn- grow-values
  "`entries` at their least grown into `extra` characters: each cut one whole when its growth
  fits, else filled to its room. A map's (map? true: its entries' values) strings before
  collections, smallest first. A sequence's members in their order, breadth before depth: the
  extra is split in equal shares, one for each member that does not fit whole in it and one more
  for layout order, and each member's room keeps back a share for every later such member, so
  each shows its first part and the first grows most. One allot a member, at every depth."
  {:malli/schema [:=> [:cat :seon.print/context [:vector :seon.print/alloc] number? :boolean] [:vector :seon.print/alloc]]}
  [context entries extra map?]
  (let [value-of (if map? #(second (:kids (nth entries %))) #(nth entries %))
        grown (for [i (range (count entries))
                    :let [v (value-of i)]
                    :when (elides? v)
                    :let [w (whole context v (+ (:ub v) extra))]]
                {:i i :grown w :string-rank (if (#{:string :name} (:kind v)) 0 1)
                 :width (if w (:ub w) Long/MAX_VALUE)})
        order (vec (if map? (sort-by (juxt :string-rank :width :i) grown) grown))
        ;; the members that will show less than they hold: no whole form fits the extra
        cut? (fn [g] (nil? (:grown g)))
        share (if map? 0 (quot extra (inc (count (filter cut? order)))))
        with-value (if map?
                     (fn [entries i v] (update entries i #(sized context (assoc-in % [:kids 1] v))))
                     (fn [entries i v] (assoc entries i v)))]
    (loop [entries entries extra extra [{:keys [i grown]} & more] order]
      (if (nil? i)
        entries
        (let [least (value-of i)
              room (max 0 (- extra (* share (count (filter cut? more)))))]
          (if (and grown (<= (- (:ub grown) (:ub least)) room))
            (recur (with-value entries i grown) (- extra (- (:ub grown) (:ub least))) more)
            (let [allotted (allot context least (+ (:ub least) room))]
              (if (< (:ub least) (:ub allotted) (+ (:ub least) room 1))
                (recur (with-value entries i allotted) (- extra (- (:ub allotted) (:ub least))) more)
                (recur entries extra more)))))))))

(defn- row-class
  "The kind that makes `held`, a member of `node`, a row: :map, :vector or :set (an array map and
  a hash map are one kind), or in a sequence :string; else nil. A map's member is a row by its value when its key is no keyword
  (an id's row; a keyword names an attribute, a part of one record, which shows breadth first).
  A row at its least is a cut that shows nothing, a line and a form that say no more than the
  collection's own cut."
  {:malli/schema [:=> [:cat :seon.print/alloc :any] :any]}
  [node held]
  (let [map? (= :map (:kind node))
        m (if (and (:indexed? node) (:sorted? node)) (nth held 1) held)
        c (class (if map? (val m) m))]
    (when-not (and map? (keyword? (key m)))
      (cond (map-classes c) :map (vector-classes c) :vector (set-classes c) :set (and (not map?) (identical? c String)) :string))))

(defn- alike?
  "Are `node`'s members rows of one kind (`row-class`), as far as `:max-children` members reach
  (the most a budget shows)."
  {:malli/schema [:=> [:cat :seon.print/context :seon.print/alloc] :boolean]}
  [context node]
  (let [c (row-class node (first (:pending node)))]
    (loop [members (next (:pending node)) k 1]
      (cond (or (nil? c) (nil? members) (>= k (:max-children context))) (some? c)
            (= c (row-class node (first members))) (recur (next members) (inc k))
            :else false))))

(defn- fill-members
  "Collection `node` within `budget`, breadth before depth: every member at its least while they
  fit (a map's key with its value at its least), then one cut; the budget left grows the members
  (grow-values), so a long sequence or a page shows every member at its least before any one
  deepens. Rows (`alike?`) show whole in their order instead (`fill-rows`)."
  {:malli/schema [:=> [:cat :seon.print/context :seon.print/alloc number?] :seon.print/alloc]}
  [context node budget]
  (let [sep (separator context node)
        [entries members]
        (loop [entries [] spent (+ (:ub (collapsed context node)) (cut-reserve node)) members (:pending node)]
          (let [[held & more :as remaining] (seq members)
                entry (when remaining (minimal context (child context node held (count entries))))]
            (if (and entry (<= (+ spent sep (:ub entry)) budget))
              (recur (conj entries entry) (+ spent sep (:ub entry)) more)
              [entries members])))
        skeleton (sized context (assoc node :kids entries :pending members))
        ;; no member fits beside the cut's reserve (an upper bound): the first at its least, as
        ;; it prints, when that fits (the floor select holds every collection to)
        first-only (when (and (empty? entries) (seq members)) (first-at-least context node))]
    (if (and first-only (<= (:ub first-only) budget))
      first-only
      (sized context (assoc skeleton :kids (grow-values context entries (- budget (:ub skeleton)) (= :map (:kind node))))))))

(defn- fill-rows
  "Collection `node` within `budget`, its first member a row (`row-class`): each member whole in
  its order while it fits, then one cut for the rest. When not even the first fits whole, it is
  filled to the room if the members are rows of its kind (`alike?`) and its first part shows;
  otherwise, and at a member of another kind, the members share the room breadth before depth
  (`fill-members`). It reads no member past the cut."
  {:malli/schema [:=> [:cat :seon.print/context :seon.print/alloc number?] :seon.print/alloc]}
  [context node budget]
  (let [sep (separator context node)
        c0 (row-class node (first (:pending node)))
        ;; the cut as it prints, its form's path included, at its widest (both counts at the total's digits)
        reserve (+ sep (count (cut-text (assoc node :n 0))) (count (str (:total node 0))))]
    (loop [kids [] spent (+ (:ub (collapsed context node)) reserve) members (:pending node)]
      (if-let [[held & more] (seq members)]
        (let [cost (if (seq kids) sep 0)
              c (child context node held (count kids))
              room (- budget spent cost)
              w (when (= c0 (row-class node held)) (whole context c room))]
          (cond
            w (recur (conj kids w) (+ spent cost (:ub w)) more)
            ;; a row that does not fit ends the rows; a member of another kind makes the collection
            ;; mixed, whose members share the room (a vector of a string, rows and a keyword)
            (and (seq kids) (= c0 (row-class node held))) (sized context (assoc node :kids kids :pending members))
            (or (seq kids) (not (alike? context node))) (fill-members context node budget)
            :else
            (let [part (when (pos? room)
                         (if (= :entry (:kind c))
                           ;; a map's member: its key at its least, its value filled to what is left
                           (let [least (minimal context c) v (nth (:kids least) 1)]
                             (sized context (assoc-in least [:kids 1] (allot context v (- room (- (:ub least) (:ub v)))))))
                           (allot context c room)))]
              (if (and part (shows? (if (= :entry (:kind part)) (nth (:kids part) 1) part)) (<= (:ub part) room))
                (sized context (assoc node :kids [part] :pending more))
                (fill-members context node budget)))))
        (sized context (assoc node :kids kids :pending nil))))))

(defn- allot
  "`node` within `budget` characters: whole when it fits, else filled to the budget (a collection
  breadth before depth, fill-members); a collection at the depth bound collapsed."
  {:malli/schema [:=> [:cat :seon.print/context :seon.print/alloc number?] :seon.print/alloc]}
  [context node budget]
  (or (whole context node budget)
      (case (:kind node)
        (:string :name) (fill-string context node budget)
        (:map :coll) (cond (bound? context node) (collapsed context node)
                           (row-class node (first (:pending node))) (fill-rows context node budget)
                           :else (fill-members context node budget))
        (sized context node))))

(defn- select
  "`value` allotted under the context's budget, or the insufficient-budget error when its least
  form, or its first member's, cannot fit."
  {:malli/schema [:=> [:cat :any :seon.print/context] :map]}
  [value context]
  (let [root (make-node context value 0 [] 0)
        budget (:budget context)
        least (minimal context root)
        shown (when (<= (:ub least) budget) (allot context root budget))
        floor (when (and shown (#{:map :coll} (:kind shown)) (zero? (:n shown)) (not (bound? context shown))
                         (seq (:pending root)))
                (first-at-least context root))]
    (cond
      (nil? shown) (insufficient-budget context (:ub least))
      floor (insufficient-budget context (:ub floor))
      :else {:root shown})))

(defn- cut-node
  "The one tail cut of a node that shows less than it holds: what it omitted (a string's shown
  prefix and the lines after it) and the form that reads the rest, or why there is none."
  {:malli/schema [:=> [:cat :seon.print/alloc :seon.print/context] :seon.print/node]}
  [node context]
  (let [form (node-continuation node)
        text? (#{:string :name} (:kind node))]
    (cond-> {:seon.print/face :seon.print/elided
             :seon.print/elision-unit (cond text? :characters (:pending? node) :pending :else :children)}
      (not (:pending? node)) (assoc :seon.print/omitted (max 1 (- (:total node) (if text? (:shown node) (:n node)))))
      text? (assoc :seon.print/prefix (subs (if (= :name (:kind node)) (:text node) (:value node)) 0 (:shown node)))
      (= :string (:kind node)) (assoc :seon.print/lines (lines-after (:breaks node) (:total node) (:shown node)))
      (:code? node) (assoc :seon.print/code? (:code? node) :seon.print/indent (:indent node))
      form (assoc :seon.print/continuation form)
      (nil? form) (assoc :seon.print/requery-refused (get-in context [:requery :seon.print/requery-refused] "the source has no stable requery identity")))))

(defn- tree-node
  "The print node of allotted `node`, its kids' first; each node that shows less than it holds
  counted in `elided` (a volatile count)."
  {:malli/schema [:=> [:cat :seon.print/alloc :seon.print/context :any] :any]}
  [node context elided]
  (if (= :leaf (:kind node))
    ;; a leaf is its own print node, never cut
    node
    (let [ks (:kids node)
          kids (if (= :entry (:kind node))
                 ;; an entry of two leaves is its kids as they are
                 (let [k (nth ks 0) v (nth ks 1)
                       k' (tree-node k context elided) v' (tree-node v context elided)]
                   (if (and (identical? k k') (identical? v v')) ks [k' v']))
                 (mapv #(tree-node % context elided) ks))
          ;; an entry shows all it holds (its key and value are cut, if anything)
          more (and (not= :entry (:kind node)) (more? node))
          _ (when more (vswap! elided inc))
          cut (when more (cut-node node context))]
      (case (:kind node)
        :entry kids
        :name (assoc (:node node) :seon.print/text (if cut
                                                     (str (:seon.print/prefix cut) (emit/cut-words (:seon.print/omitted cut) 0 (:seon.print/continuation cut)))
                                                     (:text node)))
        :string (or cut (if (:code? node)
                          {:seon.print/face :seon.print/code :seon.print/value (:value node) :seon.print/indent (:indent node) :seon.print/code? (:code? node)}
                          {:seon.print/face :seon.print/string :seon.print/value (:value node)}))
        (:map :coll)
        (let [children (cond-> kids cut (conj cut))
              collection (cond-> {:seon.print/face (if (= :seon.print/throwable (:face node)) :seon.print/map (:face node))}
                           (= :map (:kind node)) (assoc :seon.print/entries children)
                           (= :coll (:kind node)) (assoc :seon.print/items children)
                           (:ordered? node) (assoc :seon.print/ordered? true)
                           (:total node) (assoc :seon.render.data/total (:total node)))]
          (if (= :seon.print/throwable (:face node))
            {:seon.print/face :seon.print/throwable :seon.print/value collection}
            collection))))))

(defn- tree
  "The allotted `root` as one print-node tree, and how many nodes show less than they hold."
  {:malli/schema [:=> [:cat :seon.print/alloc :seon.print/context] [:map [:tree :any] [:elisions nat-int?]]]}
  [root context]
  (let [elided (volatile! 0)
        built (tree-node root context elided)]
    {:tree built :elisions @elided}))

(defn show
  "`value` selected under `opts` and emitted: `{:text :tokens :truncated? :elisions}` plus
  `:html` (hiccup) when `outputs` holds `:hiccup`, or the insufficient-budget error.
  opts: `:handle` (what cuts read through: a `result/…` symbol, or a form that reads the value), `:budget` tokens, `:max-depth`, `:width`,
  `:outputs`, `:seen` (values earlier responses showed, each the form that reads it again: such a
  nested value prints as its form). A value whose metadata holds `:seon.print/full true` prints whole, and
  `:seon.print/budget n` raises its own budget."
  {:malli/schema [:=> [:cat :any :seon.print/opts] :seon.print/result]}
  [value {:keys [handle budget max-depth width outputs render seen]}]
  (let [requery (if (or (and (qualified-symbol? handle) (= "result" (namespace handle))) (seq? handle))
                  {:seon.print/requery-id handle}
                  {:seon.print/requery-refused "the value has no result handle"})
        own (when (instance? clojure.lang.IMeta value) (:seon.print/budget (meta value)))
        budget-tokens (if (pos-int? own) own budget)
        full? (and (instance? clojure.lang.IMeta value) (true? (:seon.print/full (meta value))))
        options (assoc emit/default-options :seon.print/width width)
        chars (estimate-chars budget-tokens)
        context (cond-> (merge limits
                               {:breaks? (pos? width) :requery requery :requery-id (:seon.print/requery-id requery)
                                :width width :budget chars :max-children (inc (quot chars 2)) :max-depth max-depth}
                               (when render {:render render :root value})
                               (when seen {:seen seen :root value}))
                  full? (assoc :budget Long/MAX_VALUE :max-children Long/MAX_VALUE :max-depth Long/MAX_VALUE
                               :max-name Long/MAX_VALUE))
        {root :root :as state} (select value context)]
    (if-not root
      state
      (let [{selected :tree k :elisions} (tree root context)
            emitted (emit/emit-both selected options (contains? outputs :hiccup))
            text (:text emitted)
            ;; one line holds the root when its flat width fits it
            limit (if (<= (:flat root) width) (:flat root) (:ub root))]
        ;; the charge bounds the laid-out length: a longer text is the allocator's defect
        (when (> (count text) limit)
          (throw (ex-info "The value printer's text exceeded its charge."
                          {:seon.error/type :seon.render.value/charge-exceeded :seon.error/operation (symbol #'seon.print.select/show)
                           :seon.error/message "The value printer's text exceeded its charge."
                           :seon.print/charge limit :seon.error/offending (count text)})))
        (cond-> {:text text :tokens (estimate text) :truncated? (pos? k) :elisions k}
          (contains? outputs :hiccup) (assoc :html [:div {:class "seon-data-panel"} (:hiccup emitted)]))))))
