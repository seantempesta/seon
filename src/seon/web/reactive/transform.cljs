(ns seon.web.reactive.transform
  "Render-time rewrite — agent fn-calls in handler slots → standard Datastar.

   Agents make a tile interactive by writing a NORMAL Clojure fn-call (or a
   bare fn-ref) in an event-handler slot of their hiccup. The browser only
   ever sees STANDARD Datastar — there is no bespoke client macro. This is a
   server-side postwalk that runs at render time over AGENT-authored hiccup
   (see `seon.render/render-agent-tile`); trusted/core hiccup is unaffected.

   ## Two authoring shapes

   - fn-CALL — a seq whose head is a symbol, args bound at RENDER time:
       [:button {:on-click (list 'cancel-order! id)} \"Cancel\"]
     `id` is the live value captured when the tile rendered. Rewritten to a
     POST that carries those args transit-serialized.

   - fn-REF — a bare (or qualified) symbol, args from CLICK-time signals:
       [:button {:on-click 'submit-order!} \"Submit\"]
     No render-time args; Datastar sends the current signals as the POST
     body, and `/call` passes them to the fn as a single map argument.

   ## The rewrite (both shapes → one standard Datastar attribute)

     {:on-click (list 'cancel-order! \"o-1\")}
       => {:data-on:click \"@post('/call?fn=my.agent.X%2Fcancel-order%21&args=…')\"}
     {:on-click 'submit-order!}
       => {:data-on:click \"@post('/call?fn=my.agent.X%2Fsubmit-order%21')\"}

   The fn symbol's NAMESPACE is the route: `/call` resolves the owning agent
   from it (`seon.web.reactive.call`). A bare handler symbol is qualified to
   `ns-sym` (the authoring namespace the rewrite is bound to) — Clojure
   semantics: a bare name means the current ns. An already-qualified symbol
   passes through unchanged.

   The descriptor rides the URL query string (not Datastar's `@post` second
   arg — in Datastar v1 that arg is fetch OPTIONS, and the POST body is the
   signals). `fn` + the transit-encoded `args` vector are URL-encoded; the
   apostrophe is additionally `%27`-escaped so it can never break the
   single-quoted Datastar expression.

   Pure data transform — no side effects, no state. Positional args
   (`(transform-hiccup ns hiccup)`) rather than map-in/map-out: it's a pure
   transformation library where that call shape is the natural one."
  (:require
    [clojure.string :as str]
    [clojure.walk :as walk]
    [cognitect.transit :as t]))

;; ============================================================
;; Args codec — transit-JSON, byte-compatible with the wire codec.
;; The rewrite WRITES (render-time arg values → query string); the
;; /call route READS (query string → arg values). One place owns the
;; shape so the two sides can never drift.
;; ============================================================

(defn encode-args
  "Transit-JSON encode the render-time `args` (a sequence) to a string for
   the `/call` URL. The caller URL-encodes the result."
  {:malli/schema [:=> [:catn [::args [:sequential :any]]] :string]}
  [args]
  (t/write (t/writer :json) (vec args)))

(defn decode-args
  "Inverse of [[encode-args]] — read a transit-JSON `s` back to the args
   vector. `s` is the already-URL-decoded query value (third-party input)."
  {:malli/schema [:=> [:catn [::s :string]] :any]}
  [s]
  (t/read (t/reader :json) s))

;; ============================================================
;; URL encoding — encodeURIComponent plus an apostrophe escape so the
;; value is safe inside the single-quoted `@post('…')` Datastar string.
;; ============================================================

(defn- url-enc [s]
  (str/replace (js/encodeURIComponent (str s)) "'" "%27"))

(defn- call-action
  "The standard Datastar `@post('/call?…')` expression for a resolved fn
   symbol + optional render-time args. fn-CALL passes `args` (transit in the
   query); fn-REF passes none (the body's signals become the fn's arg)."
  [fn-sym args]
  (let [base (str "@post('/call?fn=" (url-enc (str fn-sym)))]
    (str (if (seq args)
           (str base "&args=" (url-enc (encode-args args)))
           base)
         "')")))

;; ============================================================
;; Handler-slot detection + rewrite.
;; ============================================================

(defn- event-attr?
  "True for an event-handler key — `:on-click`, `:on:submit`, … . The name
   matches `^on[:-]<event>`; the captured `<event>` becomes the Datastar
   `data-on:<event>` key."
  [k]
  (and (keyword? k)
       (some? (re-matches #"on[:-].+" (name k)))))

(defn- event-name [k]
  (second (re-matches #"on[:-](.+)" (name k))))

(defn- qualify
  "Qualify a bare handler symbol to the authoring `ns-sym`; pass an
   already-qualified symbol through unchanged."
  [ns-sym sym]
  (if (qualified-symbol? sym)
    sym
    (symbol (str ns-sym) (name sym))))

(defn- call-or-ref
  "Interpret a handler-slot VALUE. Returns `[fn-sym args]` for a fn-CALL
   (a seq with a symbol head — args are the rest), `[fn-sym nil]` for a
   fn-REF (a bare/qualified symbol), or nil for anything else (a plain
   Datastar string, a number, …) which is left untouched."
  [ns-sym v]
  (cond
    (and (seq? v) (symbol? (first v)))
    [(qualify ns-sym (first v)) (vec (rest v))]

    (symbol? v)
    [(qualify ns-sym v) nil]

    :else nil))

(defn- rewrite-attr
  "Rewrite one [k v] pair. An event-handler key whose value is a fn-call /
   fn-ref becomes `[:data-on:<event> \"@post('/call?…')\"]`; everything else
   passes through unchanged."
  [ns-sym k v]
  (if-let [[fn-sym args] (and (event-attr? k) (call-or-ref ns-sym v))]
    [(keyword (str "data-on:" (event-name k))) (call-action fn-sym args)]
    [k v]))

(defn- rewrite-attrs [ns-sym attrs]
  (if (map? attrs)
    (into {} (map (fn [[k v]] (rewrite-attr ns-sym k v))) attrs)
    attrs))

(defn- hiccup-element? [form]
  (and (vector? form)
       (not (map-entry? form))
       (keyword? (first form))
       (map? (second form))))

(defn transform-hiccup
  "Postwalk `hiccup`, rewriting agent fn-call / fn-ref handler slots into
   standard Datastar `@post('/call?…')` attributes. Bare handler symbols are
   qualified to `ns-sym` (the authoring namespace). Elements without an attr
   map, and attrs that aren't fn-call/fn-ref handler slots, are untouched —
   so it is a no-op on hiccup that uses no interactive handlers."
  {:malli/schema [:=> [:catn [::ns-sym :symbol] [::hiccup :any]] :any]}
  [ns-sym hiccup]
  (walk/postwalk
    (fn [form]
      (if (hiccup-element? form)
        (let [[tag attrs & children] form]
          (into [tag (rewrite-attrs ns-sym attrs)] children))
        form))
    hiccup))
