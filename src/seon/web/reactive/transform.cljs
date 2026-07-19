(ns seon.web.reactive.transform
  "Rewrite agent event handlers into Datastar actions.

   Agents make a canvas interactive by writing a normal Clojure fn-call (or a
   bare fn-ref) in an event-handler slot of their hiccup. The browser only
   ever sees STANDARD Datastar — there is no bespoke client macro. This is a
   server-side postwalk that runs at render time over AGENT-authored hiccup
   acquired by `seon.agent.ctx.canvas`; trusted/core hiccup is unaffected.

   ## Two authoring shapes

   - fn-CALL — a seq whose head is a symbol, args bound at RENDER time:
       [:button {:on-click (list 'cancel-order! id)} \"Cancel\"]
     `id` is the live value captured when the canvas rendered. Rewritten to a
     POST that carries those args transit-serialized.

   - fn-REF — a bare (or qualified) symbol, args from CLICK-time signals:
       [:button {:on-click 'submit-order!} \"Submit\"]
     No render-time args; Datastar sends the current signals as the POST
     body, and the agent action door passes them to the fn as one map argument.

   ## The rewrite (both shapes → one standard Datastar attribute)

     {:on-click (list 'cancel-order! \"o-1\")}
       => {:data-on:click \"@post('/agent/X/call?fn=my.agent.X%2Fcancel-order%21&args=…')\"}
     {:on-click 'submit-order!}
       => {:data-on:click \"@post('/agent/X/call?fn=my.agent.X%2Fsubmit-order%21')\"}

   The rendering agent supplies the agent id for `/agent/<id>/call`; it is
   independent from the function namespace. The capability gate proves the
   route agent is live and the function is an agent-authored fact in the shared
   program graph.
   A bare handler symbol is qualified to `ns-sym` (the authoring namespace the
   rewrite is bound to) — Clojure
   semantics: a bare name means the current ns. An already-qualified symbol
   passes through unchanged.

   The descriptor rides the URL query string (not Datastar's `@post` second
   arg — in Datastar v1 that arg is fetch OPTIONS, and the POST body is the
   signals). `fn` + the transit-encoded `args` vector are URL-encoded; the
   apostrophe is additionally `%27`-escaped so it can never break the
   single-quoted Datastar expression.

   Pure data transform — no side effects, no state. Positional args
   (`(transform-hiccup agent-id ns hiccup)`) rather than map-in/map-out: it is a pure
   transformation library where that call shape is the natural one."
  (:require
    [clojure.string :as str]
    [clojure.walk :as walk]
    [cognitect.transit :as t]))

;; ============================================================
;; Args codec — transit-JSON, byte-compatible with the wire codec.
;; The rewrite WRITES (render-time arg values → query string); the
;; agent action route READS (query string → arg values). One place owns the
;; shape so the two sides can never drift.
;; ============================================================

(defn encode-args
  "Transit-JSON encode the render-time `args` to a URL string.

   The `args` sequence encodes for the agent action URL. The caller URL-encodes
   the result."
  {:malli/schema [:=> [:catn [::args [:sequential :any]]] :string]}
  [args]
  (t/write (t/writer :json) (vec args)))

(defn- data-value?
  "True when `v` is PURE DATA — a scalar (nil / boolean / number / string /
   keyword / inst / uuid) or a vector/set/map recursively of pure data. A
   SYMBOL, a LIST/SEQ (non-vector), or a transit TaggedValue is NOT pure data.
   This is the whitelist [[decode-args]] enforces: the render-time args of a
   an action call are VALUES, never code. Transit-JSON decodes
   `[\"~#list\",[…]]` into a real seq and `\"~$sym\"` into a real symbol — both are refused here so a
   code-shaped arg can never enter the invoke path (belt-and-suspenders behind
   the resolve-and-apply gate in `seon.web.reactive.call`)."
  [v]
  (cond
    (nil? v)     true
    (boolean? v) true
    (number? v)  true
    (string? v)  true
    (keyword? v) true
    (inst? v)    true                          ; transit ~t → js/Date — a value
    (uuid? v)    true                          ; transit ~u → UUID — a value
    (vector? v)  (every? data-value? v)
    (set? v)     (every? data-value? v)
    (map? v)     (reduce-kv (fn [acc k val] (and acc (data-value? k) (data-value? val)))
                            true v)
    :else        false))                       ; symbol, seq/list, TaggedValue, fn, obj

(defn decode-args
  "Inverse of [[encode-args]] — decode a transit-JSON string to args.

   Reads the already-URL-decoded `s` (third-party input) back to the args
   vector. DATA-ONLY: the
   decoded value must be a VECTOR whose elements are pure data
   ([[data-value?]]). A non-vector, a symbol, a list/seq, or a tagged value is
   REFUSED with an `ex-info` (`:seon.error/kind :user-input`) BEFORE it can
   reach the invoke path — render-time args are values, never code. Callers run
   this inside a try/catch and surface a refusal envelope."
  {:malli/schema [:=> [:catn [::s :string]] [:vector :any]]}
  [s]
  (let [decoded (t/read (t/reader :json) s)]
    (when-not (vector? decoded)
      (throw (ex-info "args must be a transit-encoded vector of data"
                      {:seon.error/kind :user-input})))
    (when-not (every? data-value? decoded)
      (throw (ex-info "args must be pure data — a symbol/list/tagged value is refused"
                      {:seon.error/kind :user-input})))
    decoded))

;; ============================================================
;; URL encoding — encodeURIComponent plus an apostrophe escape so the
;; value is safe inside the single-quoted `@post('…')` Datastar string.
;; ============================================================

(defn- url-enc [s]
  (str/replace (js/encodeURIComponent (str s)) "'" "%27"))

(defn- call-action
  "The standard Datastar action for one rendering agent and function.

   `agent-id` selects the supervised runtime; `fn-sym` retains its ordinary
   application namespace. fn-CALL carries render-time values as transit;
   fn-REF sends current browser signals as one map argument."
  [agent-id fn-sym args]
  (let [base (str "@post('/agent/" (url-enc agent-id) "/call?fn="
                  (url-enc (str fn-sym)))]
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
   fn-ref becomes `[:data-on:<event> \"@post('/agent/<id>/call?…')\"]` when
   the rendering agent supplies the action route independently from namespace.
   Everything else passes through unchanged."
  [agent-id ns-sym k v]
  (if-let [[fn-sym args] (and (event-attr? k) (call-or-ref ns-sym v))]
    [(keyword (str "data-on:" (event-name k)))
     (call-action agent-id fn-sym args)]
    [k v]))

(defn- rewrite-attrs [agent-id ns-sym attrs]
  (if (map? attrs)
    (into {} (keep (fn [[k v]] (rewrite-attr agent-id ns-sym k v))) attrs)
    attrs))

(defn- hiccup-element? [form]
  (and (vector? form)
       (not (map-entry? form))
       (keyword? (first form))
       (map? (second form))))

(defn transform-hiccup
  "Rewrite agent handler slots in `hiccup` into Datastar attrs.

   Postwalks `hiccup`, rewriting agent fn-call / fn-ref handler slots into
   standard Datastar `@post('/agent/<id>/call?…')` attributes. Bare handler
   symbols are qualified to `ns-sym` (the authoring namespace). A qualified
   function outside `my.agent.*` has no action door, so its handler is omitted.
   Elements without an attr map and attrs that aren't fn-call/fn-ref handler
   slots are untouched, so this is a no-op on non-interactive hiccup."
  {:malli/schema [:=> [:catn [::agent-id :string]
                       [::ns-sym :symbol]
                       [::hiccup :any]] :any]}
  [agent-id ns-sym hiccup]
  (walk/postwalk
    (fn [form]
      (if (hiccup-element? form)
        (let [[tag attrs & children] form]
          (into [tag (rewrite-attrs agent-id ns-sym attrs)] children))
        form))
    hiccup))
