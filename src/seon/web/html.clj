(ns seon.web.html
  "Hiccup to HTML: the web's one serializer (proto/web proto.web.html, session L2). Attributes
  are written in sorted order so the same value always gives the same bytes (the render
  step compares bytes to decide what changed). Strings are escaped; `raw` opts out; nil and
  false render nothing; seqs splice; `true` attributes render bare; a map :style becomes
  CSS. A callback is data (DECISIONS, \"callbacks are data naming a var\"): an element with
  `:seon/call 'ns/fn` (and optionally `:seon/args {...}` and `:seon/agent id`) becomes a
  Datastar post to the one stateless `/call` route, its payload the call itself; a form
  posts its own fields with it. A vector headed by a function (or its var) is a call, as in
  Reagent: its result is written in its place, expanded the same way."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.edn :as sedn] [seon.contract :refer [defn defn-]] [clojure.string :as str] [seon.print :as print]))

(defn raw
  "s as already-serialized HTML (written as is): a tagged map, never mistaken for attrs."
  {:malli/schema [:=> [:cat :any] :map]}
  [s] {::raw (str s)})

(defn raw?
  "Is x an already-serialized fragment?"
  {:malli/schema [:=> [:cat :any] :boolean]}
  [x] (and (map? x) (contains? x ::raw)))

(def ^:private void-tags #{"area" "base" "br" "col" "embed" "hr" "img" "input" "link" "meta" "source" "wbr"})

(defn escape
  "s with & < > \" escaped (no copy when there is nothing to escape): one pass over its characters, each escaped as it is
  copied, from the first that needs it (streamed text is escaped whole at each message, docs/render.md#in-flight-output-streams)."
  {:malli/schema [:=> [:cat :any] :string]}
  [s]
  (let [^String s (str s) n (.length s)
        first-at (loop [i 0] (cond (= i n) -1
                                   (let [c (.charAt s i)] (or (= c \&) (= c \<) (= c \>) (= c \"))) i
                                   :else (recur (inc i))))]
    (if (neg? first-at)
      s
      (let [sb (StringBuilder. (+ n 16))]
        (.append sb s 0 first-at)
        (loop [i first-at]
          (if (= i n)
            (.toString sb)
            (let [c (.charAt s i)]
              (case c
                \& (.append sb "&amp;") \< (.append sb "&lt;") \> (.append sb "&gt;") \" (.append sb "&quot;")
                (.append sb c))
              (recur (inc i)))))))))

(defn- attr-name {:malli/schema [:=> [:cat :any] :string]}
  [k] (if (keyword? k) (if (namespace k) (str (namespace k) ":" (name k)) (name k)) (str k)))

(defn- style-str {:malli/schema [:=> [:cat :any] :string]}
  [m]
  (if (map? m)
    (str/join ";" (for [[k v] (sort-by (comp attr-name key) m) :when (some? v)] (str (attr-name k) ":" v)))
    (str m)))

(defn- url-enc {:malli/schema [:=> [:cat :string] :string]}
  [s] (java.net.URLEncoder/encode s "UTF-8"))

(defn call-url
  "The /call route's URL for a call: its payload is the call itself (agent, var, args as
  EDN). Nothing is registered: the route is stateless."
  {:malli/schema [:=> [:cat [:maybe :keyword] :symbol [:maybe :map]] :string]}
  [agent f args]
  (str "/call?fn=" (url-enc (str f))
       (when agent (str "&agent=" (url-enc (str (symbol agent)))))
       (when (seq args) (str "&args=" (url-enc (sedn/encode args))))))

(defn- callback
  "attrs with its :seon/call data turned into the Datastar action posting it (a form posts
  its own fields, and is reset after)."
  {:malli/schema [:=> [:cat :string :map] :map]}
  [tag attrs]
  (let [{f :seon/call args :seon/args agent :seon/agent} attrs
        attrs (dissoc attrs :seon/call :seon/args :seon/agent)]
    (if-not f
      attrs
      (if (= tag "form")
        (assoc attrs (keyword "data-on:submit") (str "@post('" (call-url agent f args) "', {contentType:'form'}); el.reset()"))
        (assoc attrs (keyword "data-on:click") (str "@post('" (call-url agent f args) "')"))))))

(defn- write-attrs! {:malli/schema [:=> [:cat :any :map] :any]}
  [^StringBuilder sb attrs]
  (doseq [[k v] (sort-by (comp attr-name key) attrs)
          ;; :wire/* is what seon.render/wire reads (docs/render.md#the-models-context-a-cached-prefix-then-the-turn): nothing of it reaches the browser, so nothing of it can name a Datastar attribute
          ;; and :key is Reagent's: what a child is named by, no attribute of the element
          :when (and (some? v) (not (false? v)) (not= :key k) (not (and (keyword? k) (= "wire" (namespace k)))))]
    (.append sb " ")
    (.append sb (attr-name k))
    (when-not (true? v)
      (.append sb "=\"")
      (.append sb (escape (case (attr-name k)
                            "style" (style-str v)
                            "class" (if (sequential? v) (str/join " " (remove nil? v)) v)
                            v)))
      (.append sb "\""))))

(defn- write! {:malli/schema [:=> [:cat :any :any :seon.host/fn] :any]}
  [^StringBuilder sb node on-throw]
  (cond
    (nil? node) nil
    (false? node) nil
    (raw? node) (.append sb ^String (::raw node))
    ;; a vector headed by a function (or its var) is a call, as in Reagent: [task-row {:as :inline}
    ;; task] writes what (task-row {:as :inline} task) answers, itself expanded
    ;; (a keyword or symbol head is an element: Jolt answers fn? true for a keyword)
    ;; one that throws is a component's render failing (docs/failure-points.md): written in its place as what on-throw answers for
    ;; it (a walk's: the error drawn by its render and recorded as a component's defect, seon.render/placed-units), and the page around it drawn
    (and (vector? node) (not (keyword? (first node))) (not (symbol? (first node))) (or (fn? (first node)) (var? (first node))))
    (write! sb (try (apply (first node) (rest node))
                    (catch Throwable e
                      (when (:interrupted (ex-data e)) (throw e))
                      (on-throw (first node) e)))
            on-throw)
    ;; an element for the model alone (docs/render.md#a-part-prints-as-its-render) is no part of the page
    (and (vector? node) (map? (second node)) (= "model" (:wire/for (second node)))) nil
    (vector? node)
    (let [[tag & more] node
          tag (name tag)
          [attrs children] (if (and (map? (first more)) (not (raw? (first more)))) [(first more) (rest more)] [nil more])
          attrs (when attrs (callback tag attrs))]
      (.append sb "<") (.append sb tag)
      (when attrs (write-attrs! sb attrs))
      (.append sb ">")
      (when-not (contains? void-tags tag)
        ;; a style element's text is CSS, HTML's raw text: a selector's > is no markup
        (if (= "style" tag)
          (doseq [c children] (.append sb (str c)))
          (doseq [c children] (write! sb c on-throw)))
        (.append sb "</") (.append sb tag) (.append sb ">")))
    (seq? node) (doseq [c node] (write! sb c on-throw))
    ;; a value that is not text is shown by the one value printer
    :else (.append sb (escape (if (or (string? node) (number? node) (char? node)) node (print/text node))))))

(defn html
  "node (hiccup) as its HTML text. on-throw (f e), given, answers the hiccup written where a call [f & args] threw e: a walk's records the
  defect and draws it (seon.render/placed-units); without it, what threw is written as a line saying so."
  {:malli/schema [:function [:=> [:cat :any] :string] [:=> [:cat :any :seon.host/fn] :string]]}
  ([node] (html node (fn [f e] [:span {:class "err"} (str (if (var? f) (symbol f) "a render") " threw: " (or (ex-message e) (str e)))])))
  ([node on-throw] (let [sb (StringBuilder.)] (write! sb node on-throw) (.toString sb))))
