(ns seon.print.emit
  "Print nodes to fipp-laid-out text and structural hiccup, from one operation stream.

  Need: the agents' reply text and the dashboard's value panel. Taken from Seon's
  `seon.print` (clone a780860c3) and cut to what the value printer (`seon.print.select`)
  reaches: the node kinds it builds, its cuts and their continuation forms. Keys keep
  Seon's names (`:seon.print/*`, `:seon.render.data/*`): the cuts are data agents read.
  Dropped mechanisms are listed in notes BAGGAGE (proto/BAGGAGE.md)."
  (:require [clojure.string :as str]
            [fipp.engine :as fipp]))

(def default-options
  "Seon's shipped print options (the `:seon.print/default` properties of `:seon.print/options`
  in the packaged schemas, read once there; a constant here)."
  {:seon.print/length 32 :seon.print/level 8 :seon.print/width 72
   :seon.print/namespace-maps? true :seon.print/table? :derived})

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
  [{:seon.print/keys [requery-id elision-unit] path :seon.render.data/path next-offset :seon.render.data/next-offset}]
  (when (symbol? requery-id)
    (let [target (path-form requery-id (vec path))
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

(defn elided-text
  "The one text of a cut: `…N`, `\"prefix\"…+N` or `…?`, then its continuation form."
  {:malli/schema [:=> [:cat :seon.print/node] :string]}
  [node]
  (let [prefix (or (:seon.print/prefix node) "")
        text (case (:seon.print/elision-unit node)
               :characters (str (string-literal prefix) "…+" (:seon.print/omitted node))
               :pending "…?"
               (str "…" (:seon.print/omitted node "?")))]
    (if-some [form (:seon.print/continuation node)]
      (str text " " (form-text form))
      text)))

(defn elision
  "One cut node: what was omitted, where, and the form that reads the rest."
  {:malli/schema [:=> [:cat :map] :seon.print/node]}
  [{:seon.print/keys [requery-id requery-refusal omitted elision-unit prefix bound-by]
    supplied :seon.print/continuation profile-id :seon.render.profile/id path :seon.render.data/path
    next-offset :seon.render.data/next-offset total :seon.render.data/total}]
  (let [requery-form (when (symbol? requery-id)
                       (let [form (path-form requery-id (vec (or path [])))]
                         (if (seq? form) form (list 'get-in form []))))]
    (cond-> {:seon.print/face :seon.print/elided}
      (some? omitted) (assoc :seon.print/omitted (max 1 (long omitted)))
      (some? elision-unit) (assoc :seon.print/elision-unit elision-unit)
      (some? path) (assoc :seon.render.data/path (vec path))
      (some? next-offset) (assoc :seon.render.data/next-offset (long next-offset))
      (some? profile-id) (assoc :seon.render.profile/id profile-id)
      (some? total) (assoc :seon.render.data/total (long total))
      (some? prefix) (assoc :seon.print/prefix prefix)
      (some? bound-by) (assoc :seon.print/bound-by bound-by)
      (some? requery-id) (assoc :seon.print/requery-id requery-id)
      requery-form (assoc :seon.print/requery-form requery-form)
      (some? supplied) (assoc :seon.print/continuation supplied)
      (and (nil? supplied) (symbol? requery-id))
      (assoc :seon.print/continuation (continuation {:seon.print/requery-id requery-id :seon.print/elision-unit elision-unit
                                                     :seon.render.data/path (or path [])
                                                     :seon.render.data/next-offset next-offset}))
      (and (nil? requery-id) (some? requery-refusal)) (assoc :seon.print/requery-refusal requery-refusal)
      (and (nil? requery-id) (nil? requery-refusal))
      (assoc :seon.print/requery-refusal "the source has no stable requery identity"))))

;;; Emission: one explicit-stack walk turns a node tree into fipp's operation maps; text is
;;; fipp's layout of that stream, hiccup a fold over the same stream.

(def ^:private scalar-kinds
  #{:seon.print/nil :seon.print/boolean :seon.print/number :seon.print/keyword :seon.print/symbol
    :seon.print/char :seon.print/string :seon.print/inst :seon.print/uuid :seon.print/var
    :seon.print/class :seon.print/object :seon.print/elided})

(def ^:private structural-kinds
  #{:seon.print/vector :seon.print/list :seon.print/set :seon.print/map :seon.print/throwable})

;; Indentation stops growing past this many open collections.
(def ^:private nest-depth 6)

(def ^:private line {:op :line :inline " " :terminate ""})

(defn- css-class {:malli/schema [:=> [:cat :keyword] :string]} [kind] (str "seon-print-" (name kind)))

(defn- text-op
  {:malli/schema [:function [:=> [:cat :keyword :string] :seon.print/op] [:=> [:cat :keyword :string :any] :seon.print/op]]}
  ([css text] {:op :text :text text :seon.print/class css})
  ([css text hiccup] {:op :text :text text :seon.print/class css :seon.print/hiccup hiccup}))

(defn- elided-hiccup
  "A cut as HTML: a string cut's shown characters, then its marker and continuation form; a cut
  with no continuation names why in its title."
  {:malli/schema [:=> [:cat :seon.print/node :string] :seon.print/hiccup]}
  [node text]
  (let [prefix (or (:seon.print/prefix node) "")
        shown (when (= :characters (:seon.print/elision-unit node)) prefix)
        label (if shown (subs text (count (string-literal prefix))) text)
        reason (when-not (:seon.print/continuation node) (:seon.print/requery-refusal node))
        marker [:span (cond-> {:class (css-class :seon.print/elision)} reason (assoc :title reason)) label]]
    (if shown
      [:span [:span {:class (css-class :seon.print/string)} shown] marker]
      marker)))

(defn- leaf-ops
  "The text ops of one leaf node."
  {:malli/schema [:=> [:cat :seon.print/node] [:vector :seon.print/op]]}
  [node]
  (let [value (:seon.print/value node)]
    (case (:seon.print/face node)
      :seon.print/nil [(text-op :seon.print/nil "nil")]
      (:seon.print/boolean :seon.print/number :seon.print/keyword :seon.print/symbol :seon.print/char)
      [(text-op (:seon.print/face node) (or (:seon.print/text node) (literal value)))]
      :seon.print/string [(text-op :seon.print/string (or (:seon.print/text node) (string-literal value))
                                   [:span {:class (css-class :seon.print/string)} (or (:seon.print/text node) value)])]
      (:seon.print/inst :seon.print/uuid) [(text-op :seon.print/tag (or (:seon.print/text node) (literal value)))]
      :seon.print/var [(text-op :seon.print/symbol (str "#'" (:seon.print/name node)))]
      :seon.print/class [(text-op :seon.print/symbol (:seon.print/name node))]
      :seon.print/object [(text-op :seon.print/object (str "#object[" (:seon.print/class node)
                                                           (when-some [rep (:seon.print/rep node)] (str " " rep)) "]"))]
      :seon.print/elided (let [text (elided-text node)]
                           [(text-op :seon.print/elision text (elided-hiccup node text))]))))

(defn- same-key-namespace
  {:malli/schema [:=> [:cat [:sequential :any]] [:maybe :string]]}
  [entries]
  (when (< 1 (count entries))
    (let [key-nodes (map first entries)
          namespaces (map (comp namespace :seon.print/value) key-nodes)]
      (when (and (every? #(contains? #{:seon.print/keyword :seon.print/symbol} (:seon.print/face %)) key-nodes)
                 ;; a key shown as a cut name keeps its own spelling
                 (every? #(or (nil? (:seon.print/text %)) (= (:seon.print/text %) (literal (:seon.print/value %)))) key-nodes)
                 (every? some? namespaces)
                 (apply = namespaces))
        (first namespaces)))))

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

(defn- collection-work
  "The work one collection expands to: its open group, members and close."
  {:malli/schema [:=> [:cat :seon.print/node :map :int [:vector :any]] [:vector :any]]}
  [node options depth path]
  (let [children (vec (or (:seon.print/items node) (:seon.print/entries node)))
        ;; a quoted form prints as the reader writes it, `'x`
        quoted? (and (= :seon.print/list (:seon.print/face node)) (= 2 (count children))
                     (= :seon.print/symbol (:seon.print/face (first children))) (= 'quote (:seon.print/value (first children))))
        children (if quoted? (subvec children 1) children)
        map-like? (= :seon.print/map (:seon.print/face node))
        lifted (when (and map-like? (:seon.print/namespace-maps? options)) (same-key-namespace (filterv vector? children)))
        shown (if lifted (mapv #(if (vector? %) (update % 0 unqualified-key-node) %) children) children)
        open (case (:seon.print/face node)
               :seon.print/vector "[" :seon.print/list (if quoted? "'" "(") :seon.print/set "#{"
               :seon.print/map (str (when lifted (str "#:" lifted)) "{"))
        close (case (:seon.print/face node) :seon.print/vector "]" :seon.print/list (if quoted? "" ")") "}")
        indent (fn [offset] {:op :nest :offset (if (< depth nest-depth) offset 0)})
        d (inc depth)
        fill? (and (not map-like?) (every? (comp scalar-kinds :seon.print/face) shown))
        member (fn [index child]
                 (let [child-path (conj path index)]
                   (cond
                     (and map-like? (vector? child))
                     [{:op :begin} [(first child) d (conj child-path 0)] (indent 2)
                      line [(second child) d (conj child-path 1)] {:op :outdent} {:op :end}]
                     (or (zero? index) map-like? (not fill?)) [[child d child-path]]
                     :else [{:op :begin} line [child d child-path] {:op :end}])))
        separator (fn [index]
                    (cond (zero? index) []
                          map-like? [(text-op :seon.print/separator ",") line]
                          fill? []
                          :else [line]))]
    (-> (reduce-kv (fn [work index child] (reduce conj! work (into (separator index) (member index child))))
                   (transient [{:op :begin :seon.print/open (assoc node :seon.print/path path)}
                               (text-op :seon.print/delimiter open)
                               (indent (if (= :seon.print/set (:seon.print/face node)) 2 1))])
                   shown)
        (conj! {:op :outdent})
        (conj! (text-op :seon.print/delimiter close))
        (conj! {:op :end :seon.print/close node})
        persistent!)))

(defn- node-work
  {:malli/schema [:=> [:cat :seon.print/node :map :int [:vector :any]] [:vector :any]]}
  [node options depth path]
  (cond
    (= :seon.print/throwable (:seon.print/face node))
    [(text-op :seon.print/tag "#error ") [(:seon.print/value node) depth (conj path :seon.print/throwable)]]
    (contains? structural-kinds (:seon.print/face node)) (collection-work node options depth path)
    :else (leaf-ops node)))

(defn- node-ops
  "One node tree as fipp operation maps, walked with an explicit stack."
  {:malli/schema [:=> [:cat :seon.print/node :map] [:vector :seon.print/op]]}
  [node options]
  (loop [stack (list [node 0 []]) ops (transient [])]
    (if-some [work (first stack)]
      (if (map? work)
        (recur (rest stack) (conj! ops work))
        (let [[node depth path] work]
          (recur (into (rest stack) (rseq (node-work node options depth path))) ops)))
      (persistent! ops))))

(defn- flat-width
  "The length of the operations laid out on one line: their texts and their lines' inline forms."
  {:malli/schema [:=> [:cat [:sequential :seon.print/op]] :int]}
  [ops]
  (reduce (fn [n op] (case (:op op) :text (+ n (count (:text op))) :line (+ n (count (:inline op))) n)) 0 ops))

(defn- ops-text
  "Lay operations out to `width` with fipp's own stages; width 0 is one line. Operations that
  fit `width` on one line are that line: fipp would break none of their groups, so its layout
  stages are skipped."
  {:malli/schema [:=> [:cat [:vector :seon.print/op] :int] :string]}
  [ops width]
  (let [out (StringBuilder.)
        append (fn ([result] result) ([^StringBuilder result ^String text] (.append result text)))]
    (if (and (pos? width) (> (flat-width ops) width))
      (let [layout {:width width}]
        (transduce (comp fipp/annotate-rights (fipp/annotate-begins layout) (fipp/format-nodes layout)) append out ops))
      (transduce (keep #(case (:op %) :text (:text %) :line (:inline %) nil)) append out ops))
    (str out)))

(defn- close-frame
  {:malli/schema [:=> [:cat :map] :seon.print/hiccup]}
  [{:seon.print/keys [node children]}]
  [:details {:class (str "seon-print-node " (css-class (:seon.print/face node)))
             :data-seon-path (literal (:seon.print/path node))}
   [:summary {:class "seon-print-summary"} (summary node (:seon.print/path node))]
   (into [:span {:class "seon-print-content"}] children)])

(defn- ops-hiccup
  "The same operations as structural hiccup: a `<details>` per collection, the root one open."
  {:malli/schema [:=> [:cat [:vector :seon.print/op]] :seon.print/hiccup]}
  [ops]
  (let [append (fn [{:seon.print/keys [stack] :as state} child]
                 (if (seq stack)
                   (update-in state [:seon.print/stack (dec (count stack)) :seon.print/children] conj child)
                   (update state :seon.print/roots conj child)))
        {:seon.print/keys [roots]}
        (reduce (fn [state {:keys [op] :as event}]
                  (cond
                    (:seon.print/open event) (update state :seon.print/stack conj {:seon.print/node (:seon.print/open event) :seon.print/children []})
                    (:seon.print/close event) (let [frame (peek (:seon.print/stack state))]
                                                (append (update state :seon.print/stack pop) (close-frame frame)))
                    (= :text op) (append state (or (:seon.print/hiccup event)
                                                   [:span {:class (css-class (:seon.print/class event))} (:text event)]))
                    (= :line op) (append state [:span {:class (css-class :seon.print/separator)} (:inline event)])
                    :else state))
                {:seon.print/stack [] :seon.print/roots []}
                ops)
        hiccup (case (count roots)
                 0 [:span {:class "seon-print-empty"} ""]
                 1 (first roots)
                 (into [:span {:class "seon-print-root"}] roots))]
    (cond-> hiccup (and (vector? hiccup) (= :details (first hiccup))) (assoc-in [1 :open] true))))

(defn emit-text
  "One node as text laid out to its width."
  {:malli/schema [:=> [:cat :seon.print/node :map] :string]}
  [node options]
  (let [options (merge default-options options)]
    (ops-text (node-ops node options) (:seon.print/width options))))

(defn emit-both
  "One node as text, and as hiccup when `hiccup?`, from one operation stream."
  {:malli/schema [:function [:=> [:cat :seon.print/node :map] :map] [:=> [:cat :seon.print/node :map :boolean] :map]]}
  ([node options] (emit-both node options true))
  ([node options hiccup?]
   (let [options (merge default-options options)
         ops (node-ops node options)]
     (cond-> {:text (ops-text ops (:seon.print/width options))}
       hiccup? (assoc :hiccup (ops-hiccup ops))))))
