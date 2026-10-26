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
  "s with & < > \" escaped (no copy when there is nothing to escape)."
  {:malli/schema [:=> [:cat :any] :string]}
  [s]
  (let [s (str s)]
    (if (some #(contains? #{\& \< \> \"} %) s)
      (-> s (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;") (str/replace "\"" "&quot;"))
      s)))

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
       (when agent (str "&agent=" (url-enc (name agent))))
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
          :when (and (some? v) (not (false? v)))]
    (.append sb " ")
    (.append sb (attr-name k))
    (when-not (true? v)
      (.append sb "=\"")
      (.append sb (escape (case (attr-name k)
                            "style" (style-str v)
                            "class" (if (sequential? v) (str/join " " (remove nil? v)) v)
                            v)))
      (.append sb "\""))))

(defn- write! {:malli/schema [:=> [:cat :any :any] :any]}
  [^StringBuilder sb node]
  (cond
    (nil? node) nil
    (false? node) nil
    (raw? node) (.append sb ^String (::raw node))
    ;; a vector headed by a function (or its var) is a call, as in Reagent: [task-row {:as :inline}
    ;; task] writes what (task-row {:as :inline} task) answers, itself expanded
    ;; (a keyword or symbol head is an element: Jolt answers fn? true for a keyword)
    (and (vector? node) (not (keyword? (first node))) (not (symbol? (first node))) (or (fn? (first node)) (var? (first node))))
    (write! sb (apply (first node) (rest node)))
    (vector? node)
    (let [[tag & more] node
          tag (name tag)
          [attrs children] (if (and (map? (first more)) (not (raw? (first more)))) [(first more) (rest more)] [nil more])
          attrs (when attrs (callback tag attrs))]
      (.append sb "<") (.append sb tag)
      (when attrs (write-attrs! sb attrs))
      (.append sb ">")
      (when-not (contains? void-tags tag)
        (doseq [c children] (write! sb c))
        (.append sb "</") (.append sb tag) (.append sb ">")))
    (seq? node) (doseq [c node] (write! sb c))
    ;; a value that is not text is shown by the one value printer
    :else (.append sb (escape (if (or (string? node) (number? node) (char? node)) node (print/text node))))))

(defn html
  "node (hiccup) as its HTML text."
  {:malli/schema [:=> [:cat :any] :string]}
  [node]
  (let [sb (StringBuilder.)] (write! sb node) (.toString sb)))
