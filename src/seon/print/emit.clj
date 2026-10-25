(ns seon.print.emit
  "Print nodes to laid-out text and structural hiccup, from one operation stream. The stream is
  fipp's (begin/end/nest/outdent/line/text) and the layout makes fipp's choices, over the
  vector in two passes (`group-ends`, `layout`); fipp itself is the layout's test oracle
  (seon.print-test/the-layout-is-fipps), no longer on the print path.

  Need: the agents' reply text and the dashboard's value panel. Taken from Seon's
  `seon.print` (clone a780860c3) and cut to what the value printer (`seon.print.select`)
  reaches: the node kinds it builds, its cuts and their continuation forms. Keys keep
  Seon's names (`:seon.print/*`, `:seon.render.data/*`); a cut is read as its text.
  Dropped mechanisms are listed in notes BAGGAGE (proto/BAGGAGE.md)."
  (:require [clojure.string :as str]))

(def default-options
  "Seon's shipped print options (the `:seon.print/default` properties of `:seon.print/options`
  in the packaged schemas, read once there; a constant here)."
  {:seon.print/width 72 :seon.print/namespace-maps? true})

(defn literal
  "`value` as readable EDN under fixed print bindings."
  {:malli/schema [:=> [:cat :any] :string]}
  [value]
  (if (and (true? *print-readably*) (not *print-dup*) (nil? *print-length*) (nil? *print-level*) (not *print-meta*))
    (pr-str value)
    (binding [*print-readably* true *print-dup* false *print-length* nil *print-level* nil *print-meta* false]
      (pr-str value))))

(defn string-literal
  "`s` as a readable string literal whose newlines stay line breaks."
  {:malli/schema [:=> [:cat :string] :string]}
  [s]
  (str "\"" (str/escape s #(when-not (= \newline %) (char-escape-string %))) "\""))

(defn code-text
  "Code text `s` at its place: verbatim, every line after the first indented `indent` columns."
  {:malli/schema [:=> [:cat :string nat-int?] :string]}
  [s indent]
  (if (pos? indent) (str/replace s "\n" (str "\n" (apply str (repeat indent \space)))) s))

(defn path-form
  "The plain clojure.core form that reads `path` from `source`: runs of keys and indices as one
  `get-in`, a map's entry `i` as `(key|val (nth (seq x) i))`, a set's or seq's member as `(nth (seq x) i)`."
  {:malli/schema [:=> [:cat :any [:sequential :any]] :any]}
  [source path]
  (let [typed? (fn [step] (and (vector? step) (contains? #{:seon.render.data/entry :seon.render.data/nth} (first step))))
        flush (fn [[target run]] (if (seq run) (list 'get-in target run) target))]
    (flush
     (reduce (fn [[target run :as state] step]
               (if-not (typed? step)
                 [target (conj run step)]
                 (let [target (flush state)
                       [tag index part] step
                       member (list 'nth (list 'seq target) index)]
                   [(if (= tag :seon.render.data/entry)
                      (list (if (= part :seon.render.data/key) 'key 'val) member)
                      member)
                    []])))
             [source []]
             path))))

(defn continuation
  "The form that reads what one cut omitted, from its next offset: `(drop n …)` for members,
  `(subs … n)` for characters; nil without a requery symbol."
  {:malli/schema [:=> [:cat :map] :any]}
  [{:seon.print/keys [requery-id elision-unit code?] path :seon.render.data/path next-offset :seon.render.data/next-offset}]
  (when (or (symbol? requery-id) (seq? requery-id))
    (let [target (cond->> (path-form requery-id (vec path)) code? (list 'str))
          offset (or next-offset 0)]
      (cond (zero? offset) target
            (= :characters elision-unit) (list 'subs target offset)
            :else (list 'drop offset target)))))

(defn form-text
  "A generated form as an agent would type it: `'x` for `(quote x)`."
  {:malli/schema [:=> [:cat :any] :string]}
  [form]
  (cond (and (seq? form) (= 'quote (first form)) (= 2 (count form))) (str "'" (form-text (second form)))
        (seq? form) (str "(" (str/join " " (map form-text form)) ")")
        :else (literal form)))

(defn cut-words
  "The words of a text's cut, the same in a value (after its string's shown prefix) and in prose:
  `…+N chars` and, when lines begin in what it omitted, `in M more lines`, then the form that
  reads the rest when there is one."
  {:malli/schema [:=> [:cat nat-int? nat-int? :any] :string]}
  [omitted lines continuation]
  (str "…+" omitted " chars" (when (pos? lines) (str " in " lines " more line" (when (not= 1 lines) "s")))
       (when continuation (str " " (form-text continuation)))))

(defn elided-text
  "The one text of a cut: `…N` or `…?`, then its continuation form; a string's
  `\"prefix\"`, then its `cut-words`."
  {:malli/schema [:=> [:cat :seon.print/node] :string]}
  [node]
  (let [form (:seon.print/continuation node)]
    (case (:seon.print/elision-unit node)
      :characters (str (if (:seon.print/code? node)
                         (str (code-text (or (:seon.print/prefix node) "") (:seon.print/indent node 0)) " ")
                         (string-literal (or (:seon.print/prefix node) "")))
                       (cut-words (:seon.print/omitted node) (:seon.print/lines node) form))
      (cond-> (if (= :pending (:seon.print/elision-unit node)) "…?" (str "…" (:seon.print/omitted node "?")))
        form (str " " (form-text form))))))

;;; Emission: one walk appends a node tree's fipp-style operation maps to one vector; text is
;;; the layout of that stream, hiccup a fold over the same stream.

(def ^:private scalar-kinds
  #{:seon.print/nil :seon.print/boolean :seon.print/number :seon.print/keyword :seon.print/symbol
    :seon.print/char :seon.print/string :seon.print/code :seon.print/inst :seon.print/uuid :seon.print/var
    :seon.print/class :seon.print/object :seon.print/elided})

(def ^:private structural-kinds
  #{:seon.print/vector :seon.print/list :seon.print/set :seon.print/map :seon.print/throwable})

;; The ops every collection shares, made once.
(def ^:private line {:op :line :inline " " :terminate ""})
(def ^:private begin {:op :begin})
(def ^:private end {:op :end})
(def ^:private outdent {:op :outdent})
(def ^:private nests [{:op :nest :offset 0} {:op :nest :offset 1} {:op :nest :offset 2}])

(defn- css-class {:malli/schema [:=> [:cat :keyword] :string]} [kind] (str "seon-print-" (name kind)))

(defn- text-op
  {:malli/schema [:function [:=> [:cat :keyword :string] :seon.print/op] [:=> [:cat :keyword :string :any] :seon.print/op]]}
  ([css text] {:op :text :text text :seon.print/class css})
  ([css text hiccup] {:op :text :text text :seon.print/class css :seon.print/hiccup hiccup}))

(def ^:private comma (text-op :seon.print/separator ","))

(defn- elided-hiccup
  "A cut as HTML: a string cut's shown characters, then its marker and continuation form; a cut
  with no continuation names why in its title."
  {:malli/schema [:=> [:cat :seon.print/node :string] :seon.print/hiccup]}
  [node text]
  (let [prefix (or (:seon.print/prefix node) "")
        shown (when (= :characters (:seon.print/elision-unit node)) prefix)
        ;; a string's label is its cut's words (the text after its prefix's literal)
        label (if shown (cut-words (:seon.print/omitted node) (:seon.print/lines node) (:seon.print/continuation node)) text)
        reason (when-not (:seon.print/continuation node) (:seon.print/requery-refused node))
        marker [:span (cond-> {:class (css-class :seon.print/elision)} reason (assoc :title reason)) label]]
    (if shown
      [:span [:span {:class (css-class :seon.print/string)} shown] marker]
      marker)))

(defn- leaf-op
  "The text op of one leaf node."
  {:malli/schema [:=> [:cat :seon.print/node] :seon.print/op]}
  [node]
  (let [value (:seon.print/value node)]
    (case (:seon.print/face node)
      ;; the common rows build their op here (text-op's shape), one call fewer per leaf
      :seon.print/nil {:op :text :text "nil" :seon.print/class :seon.print/nil}
      (:seon.print/boolean :seon.print/number :seon.print/keyword :seon.print/symbol :seon.print/char)
      {:op :text :text (or (:seon.print/text node) (literal value)) :seon.print/class (:seon.print/face node)}
      :seon.print/string (text-op :seon.print/string (or (:seon.print/text node) (string-literal value))
                                  [:span {:class (css-class :seon.print/string)} (or (:seon.print/text node) value)])
      ;; code (seon.print/code) verbatim at its place, as in its file
      :seon.print/code (text-op :seon.print/code (code-text value (:seon.print/indent node 0)) [:code {:class (css-class :seon.print/code)} value])
      (:seon.print/inst :seon.print/uuid) (text-op :seon.print/tag (or (:seon.print/text node) (literal value)))
      :seon.print/var (text-op :seon.print/symbol (str "#'" (:seon.print/name node)))
      :seon.print/class (text-op :seon.print/symbol (:seon.print/name node))
      :seon.print/object (text-op :seon.print/object (str "#object[" (:seon.print/class node)
                                                          (when-some [rep (:seon.print/rep node)] (str " " rep)) "]"))
      ;; a nested value shown by its own render (seon.print/print-value's :render): its text, and its hiccup
      :seon.print/rendered (text-op :seon.print/rendered (:seon.print/text node) (:seon.print/html node))
      :seon.print/elided (let [text (elided-text node)]
                           (text-op :seon.print/elision text (elided-hiccup node text))))))

(defn- same-key-namespace
  "The one namespace every key of a map's `entries` (its `[key value]` members; cuts are skipped)
  shares, when there are two or more and each is a keyword or symbol shown in its own spelling;
  else nil. One pass, stopping at the first key that differs."
  {:malli/schema [:=> [:cat [:sequential :any]] [:maybe :string]]}
  [entries]
  (loop [[e & more :as remaining] (seq entries) shared nil k 0]
    (cond
      (nil? remaining) (when (< 1 k) shared)
      (not (vector? e)) (recur more shared k)
      :else (let [node (nth e 0)]
              (when (contains? #{:seon.print/keyword :seon.print/symbol} (:seon.print/face node))
                (let [ns (namespace (:seon.print/value node))]
                  ;; a key shown as a cut name keeps its own spelling
                  (when (and (some? ns) (or (nil? shared) (= shared ns))
                             (or (nil? (:seon.print/text node)) (= (:seon.print/text node) (literal (:seon.print/value node)))))
                    (recur more ns (inc k)))))))))

(defn- unqualified-key-node
  {:malli/schema [:=> [:cat :seon.print/node] :seon.print/node]}
  [node]
  (-> node
      (dissoc :seon.print/text)
      (update :seon.print/value (if (= :seon.print/keyword (:seon.print/face node)) #(keyword (name %)) #(symbol (name %))))))

(defn- summary
  {:malli/schema [:=> [:cat :seon.print/node [:vector :any]] :string]}
  [node path]
  (str (name (:seon.print/face node)) " "
       (or (:seon.render.data/total node) (count (or (:seon.print/items node) (:seon.print/entries node))))
       " items, depth " (count path)))

(declare emit-node)

(defn- emit-collection
  "`ops` (a transient vector) with one collection's ops appended: its open group, members and close.
  A member's path is made only for a member that is itself a collection (a leaf's is never read)."
  {:malli/schema [:=> [:cat :any :seon.print/node :map :int [:vector :any]] :any]}
  [ops node options depth path]
  (let [children (vec (or (:seon.print/items node) (:seon.print/entries node)))
        ;; a quoted form prints as the reader writes it, `'x`
        quoted? (and (= :seon.print/list (:seon.print/face node)) (= 2 (count children))
                     (= :seon.print/symbol (:seon.print/face (first children))) (= 'quote (:seon.print/value (first children))))
        children (if quoted? (subvec children 1) children)
        map-like? (= :seon.print/map (:seon.print/face node))
        lifted (when (and map-like? (:seon.print/namespace-maps? options)) (same-key-namespace children))
        shown (if lifted (mapv #(if (vector? %) (update % 0 unqualified-key-node) %) children) children)
        open (case (:seon.print/face node)
               :seon.print/vector "[" :seon.print/list (if quoted? "'" "(") :seon.print/set "#{"
               :seon.print/map (str (when lifted (str "#:" lifted)) "{"))
        close (case (:seon.print/face node) :seon.print/vector "]" :seon.print/list (if quoted? "" ")") "}")
                d (inc depth)
        fill? (and (not map-like?) (every? (comp scalar-kinds :seon.print/face) shown))
        ;; a member's path, `path` then its index (and 0 for a key, 1 for a value)
        member (fn [ops child index part]
                 (if (contains? structural-kinds (:seon.print/face child))
                   (emit-node ops child options d (if part (conj path index part) (conj path index)))
                   (conj! ops (leaf-op child))))
        ops (-> ops
                (conj! {:op :begin :seon.print/open (assoc node :seon.print/path path)})
                (conj! (text-op :seon.print/delimiter open))
                (conj! (nests (if (= :seon.print/set (:seon.print/face node)) 2 1))))
        ops (reduce-kv
             (fn [ops index child]
               (let [ops (cond (zero? index) ops
                               map-like? (-> ops (conj! comma) (conj! line))
                               fill? ops
                               :else (conj! ops line))]
                 (cond
                   (and map-like? (vector? child))
                   (-> ops (conj! begin)
                       (member (nth child 0) index 0) (conj! (nests 2)) (conj! line)
                       (member (nth child 1) index 1) (conj! outdent) (conj! end))
                   (or (zero? index) map-like? (not fill?)) (member ops child index nil)
                   :else (-> ops (conj! begin) (conj! line) (member child index nil) (conj! end)))))
             ops shown)]
    (-> ops
        (conj! outdent)
        (conj! (text-op :seon.print/delimiter close))
        (conj! {:op :end :seon.print/close node}))))

(defn- emit-node
  "`ops` (a transient vector) with one node's ops appended; `path` is its place, read only for a
  collection."
  {:malli/schema [:=> [:cat :any :seon.print/node :map :int [:maybe [:vector :any]]] :any]}
  [ops node options depth path]
  (cond
    (= :seon.print/throwable (:seon.print/face node))
    (emit-node (conj! ops (text-op :seon.print/tag "#error ")) (:seon.print/value node) options depth
               (conj path :seon.print/throwable))
    (contains? structural-kinds (:seon.print/face node)) (emit-collection ops node options depth path)
    :else (conj! ops (leaf-op node))))

(defn- node-ops
  "One node tree as operation maps, in order."
  {:malli/schema [:=> [:cat :seon.print/node :map] [:vector :seon.print/op]]}
  [node options]
  (persistent! (emit-node (transient []) node options 0 [])))

(defn- flat-width
  "The length of the operations laid out on one line: their texts and their lines' inline forms."
  {:malli/schema [:=> [:cat [:sequential :seon.print/op]] :int]}
  [ops]
  (reduce (fn [n op] (case (:op op) :text (+ n (count (:text op))) :line (+ n (count (:inline op))) n)) 0 ops))

;;; Layout: fipp's (Kiselyov, Peyton-Jones and Sabry's "Lazy v. Yield") decisions, made over
;;; the whole op vector in two linear passes instead of fipp's streaming buffers. fipp
;;; annotates each :begin with where its group ends on one line, or :too-far when it prunes the
;;; group unseen; it prunes a group wider than the width (which the fits test would refuse
;;; anyway) and a group holding more than `width` open groups at once (which it would not).
;;; Holding the vector, `group-ends` finds both in one pass and `layout` makes fipp's choices
;;; in a second: the same text, without a buffer per group, a concatenation per close and a
;;; map per op.

(defn- group-ends
  "Per :begin of `ops`, in order: where its group ends on one line (the flat position after its
  last op), or -1 for a group fipp prunes as too far: one that holds more than `width` open
  groups at once."
  {:malli/schema [:=> [:cat [:vector :seon.print/op] :int] [:vector :int]]}
  [ops width]
  (let [n (count ops)]
    ;; open: the ordinals of the open groups, innermost first (a group's depth is the number
    ;; outside it); most: per open group, the most groups open at once while it was
    (loop [i 0 pos 0 open () most () ends (transient [])]
      (if (< i n)
        (let [op (nth ops i)]
          (case (:op op)
            :text (recur (inc i) (+ pos (count (:text op))) open most ends)
            :line (recur (inc i) (+ pos (count (:inline op))) open most ends)
            :begin (recur (inc i) pos (conj open (count ends)) (conj most (inc (count open))) (conj! ends pos))
            :end (let [m (first most) outer (rest most) depth (dec (count open))]
                   (recur (inc i) pos (rest open)
                          (if (and (seq outer) (> m (first outer))) (conj (rest outer) m) outer)
                          (assoc! ends (first open) (if (> (- m depth) width) -1 pos))))
            (recur (inc i) pos open most ends)))
        (persistent! ends)))))

(def ^:private spaces "                                                                ")

(defn- indentation
  "`k` spaces."
  {:malli/schema [:=> [:cat nat-int?] :string]}
  [k]
  (apply str (repeat k \space)))

(defn- layout
  "`ops` laid out to `width` as fipp lays them out: a group whose one-line end fits what is left
  of the line stays on it, and every :line outside such a group breaks to its nest's indentation."
  {:malli/schema [:=> [:cat [:vector :seon.print/op] pos-int?] :string]}
  [ops width]
  (let [ends (group-ends ops width)
        n (count ops)
        ^StringBuilder out (StringBuilder.)]
    ;; fits: open groups since the outermost that fits (0: lines break); length: the flat
    ;; position the line ends at; stops: the nest's indentations
    (loop [i 0 pos 0 fits 0 length width stops '(0) column 0 begun 0]
      (if (< i n)
        (let [op (nth ops i) indent (first stops)]
          (case (:op op)
            :text (let [text (:text op) k (count text)]
                    (when (zero? column)
                      (if (<= indent (count spaces)) (.append out ^String spaces 0 (int indent)) (.append out (indentation indent))))
                    (.append out ^String text)
                    (recur (inc i) (+ pos k) fits length stops (+ (if (zero? column) indent column) k) begun))
            :line (let [inline (:inline op) at (+ pos (count inline))]
                    (if (zero? fits)
                      (let [terminate (:terminate op)]
                        (when (pos? (count terminate)) (.append out ^String terminate))
                        (.append out "\n")
                        (recur (inc i) at fits (- (+ at width) indent) stops 0 begun))
                      (do (.append out ^String inline)
                          (recur (inc i) at fits length stops (+ column (count inline)) begun))))
            :nest (recur (inc i) pos fits length (conj stops (+ indent (:offset op))) column begun)
            :outdent (recur (inc i) pos fits length (rest stops) column begun)
            :begin (let [end (nth ends begun)]
                     (recur (inc i) pos (cond (pos? fits) (inc fits) (neg? end) 0 (<= end length) 1 :else 0)
                            length stops column (inc begun)))
            :end (recur (inc i) pos (max 0 (dec fits)) length stops column begun)))
        (str out)))))

(defn- ops-text
  "Lay operations out to `width`; width 0 is one line. Operations that fit `width` on one line
  are that line: no group of theirs breaks, so the layout is skipped."
  {:malli/schema [:=> [:cat [:vector :seon.print/op] :int] :string]}
  [ops width]
  (if (and (pos? width) (> (flat-width ops) width))
    (layout ops width)
    (let [out (StringBuilder.)]
      (run! #(case (:op %) :text (.append out ^String (:text %)) :line (.append out ^String (:inline %)) nil) ops)
      (str out))))

(defn- flat?
  "True when collection `node` holds only scalars (a map: scalar keys and values): it reads at a
  glance, so nothing folds it."
  {:malli/schema [:=> [:cat :map] :boolean]}
  [node]
  (every? (fn [c] (if (vector? c) (every? #(contains? scalar-kinds (:seon.print/face %)) c) (contains? scalar-kinds (:seon.print/face c))))
          (or (:seon.print/items node) (:seon.print/entries node))))

(defn- close-frame
  "One collection's hiccup: the value printed (its root) or a collection of scalars inline, as
  text reads them; any other nested collection a `<details>` that folds it, its summary saying
  what it holds."
  {:malli/schema [:=> [:cat :map [:sequential :any]] :seon.print/hiccup]}
  [node children]
  (if (or (empty? (:seon.print/path node)) (flat? node))
    (into [:span {:class (str "seon-print-node seon-print-inline " (css-class (:seon.print/face node)))}] children)
    ;; open: a closed <details> hides its content however it is styled
    [:details {:class (str "seon-print-node " (css-class (:seon.print/face node))) :open true
               :data-seon-path (literal (:seon.print/path node))}
     [:summary {:class "seon-print-summary"} (summary node (:seon.print/path node))]
     (into [:span {:class "seon-print-content"}] children)]))

;; A span's attributes for each class a text op carries, made once (hiccup is immutable data,
;; so every span of a class shares its map).
(def ^:private span-attrs
  (into {} (map (fn [k] [k {:class (css-class k)}]))
        (concat scalar-kinds [:seon.print/tag :seon.print/separator :seon.print/delimiter :seon.print/elision])))

(defn- ops-hiccup
  "The same operations as structural hiccup: a `<details>` per nested collection that holds
  collections, every one open; the root and a collection of scalars inline (close-frame).
  One pass: `stack` holds each open collection's hiccup so far, its node first."
  {:malli/schema [:=> [:cat [:vector :seon.print/op]] :seon.print/hiccup]}
  [ops]
  (let [n (count ops)
        roots (loop [i 0 stack () roots (transient [])]
                (if (< i n)
                  (let [event (nth ops i)
                        op (:op event)
                        child (cond
                                (:seon.print/open event) nil
                                (:seon.print/close event) (let [frame (persistent! (first stack))]
                                                            (close-frame (nth frame 0) (subvec frame 1)))
                                (= :text op) (or (:seon.print/hiccup event)
                                                 (let [k (:seon.print/class event)]
                                                   [:span (or (span-attrs k) {:class (css-class k)}) (:text event)]))
                                (= :line op) [:span (span-attrs :seon.print/separator) (:inline event)]
                                :else nil)
                        stack (cond (:seon.print/open event) (conj stack (transient [(:seon.print/open event)]))
                                    (:seon.print/close event) (rest stack)
                                    :else stack)]
                    (cond (nil? child) (recur (inc i) stack roots)
                          (seq stack) (recur (inc i) (conj (rest stack) (conj! (first stack) child)) roots)
                          :else (recur (inc i) stack (conj! roots child))))
                  (persistent! roots)))]
    (case (count roots)
      0 [:span {:class "seon-print-empty"} ""]
      1 (nth roots 0)
      (into [:span {:class "seon-print-root"}] roots))))

(defn emit-both
  "One node as text, and as hiccup when `hiccup?`, from one operation stream."
  {:malli/schema [:function [:=> [:cat :seon.print/node :map] :map] [:=> [:cat :seon.print/node :map :boolean] :map]]}
  ([node options] (emit-both node options true))
  ([node options hiccup?]
   (let [options (merge default-options options)
         ops (node-ops node options)]
     (cond-> {:text (ops-text ops (:seon.print/width options))}
       hiccup? (assoc :hiccup (ops-hiccup ops))))))
