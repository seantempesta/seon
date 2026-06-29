(ns seon.diffusion.scaffold
  "SCAFFOLD leg of the diffusion buzzsaw — the Seon-side template generator that
   produces the clamp FRAME the GPU worker INFILLS. Where
   `seon.diffusion.retrieval` CORRECTS a hallucinated symbol mid-denoise, this
   ns CONSTRUCTS the frame a fn is generated INTO, before any GPU call.

   ## The `:defn-with-specs` MVP frame

   The roadmap's MVP target (`docs/prds/diffusion-dynamic-context/roadmap.md`
   §P2): a single `defn` plus its map-in/map-out `:malli/schema` contract,
   emitted as a partially-fixed canvas. Some character spans are FIXED
   structure the worker HOLDS (the `defn`/`schema/register!` forms, the
   `:=>` wiring, the `::request`/`::response` refs) so the map-in/map-out
   shape can't drift; the complementary spans are SLOTS the worker re-noises
   and generates (the request `:map` body, the response `:map` body, the
   arglist destructure, the fn body).

   The data-modeling skill is the A/B-proven (0→100%) authority on this shape:
   `(schema/register! ::foo-request [:map …])` + `(schema/register!
   ::foo-response [:map …])` + `(defn foo {:malli/schema [:=> [:cat
   ::foo-request] ::foo-response]} [{::keys [...]}] …)`. The frame is
   ns-relative (`::` expands in the TARGET ns at eval time) so the generated
   fn drops straight into `::ns`.

   ## Spans are the worker contract (NO GPU here)

   `::clamp-spans` and `::infill-spans` are absolute char offsets into
   `::frame-text`, the SAME `[start end)` span object `seon.diffusion.retrieval`
   emits — the worker maps a span → canvas token positions via its `offset_map`
   (the L linchpin). The two span sets TILE the frame exactly (no gap, no
   overlap), so `[0, (count frame-text))` is partitioned into HOLD vs GENERATE.
   `to-wire` flattens to the worker's `{op,span,…}` shape, op `:clamp` for the
   held structure and op `:infill` for the slots.

   ## Worker integration (once on GPU)

   The worker clamps every `::clamp-spans` position, infills every
   `::infill-spans` position. Generation order matters: the spec slots
   (`:request-body`, `:response-body`) infill FIRST so the fn body generates
   against a KNOWN contract — quality by construction. The result is a complete
   map-in/map-out fn that parses, registers, and instruments.

   PURE — string assembly over the request map. No DB, no GPU, no embeddings."
  (:require
    [clojure.string :as str]
    [seon.diffusion.retrieval :as retrieval]
    [seon.schema :as schema]))

;; ============================================================
;; Schemas — the span vocabulary is the worker contract, reused from
;; `seon.diffusion.retrieval` (register-once, reference-everywhere).
;; ============================================================

;; absolute char offsets [start end) into `::frame-text` — the SAME shape the
;; retrieval leg emits and the worker's offset_map consumes.
(schema/register! ::span :seon.diffusion.retrieval/span)

;; which generated slot a `::infill-span` covers.
(schema/register! ::slot-role
  [:enum :request-body :response-body :arglist :fn-body])

;; which fixed structural piece a `::clamp-span` holds.
(schema/register! ::clamp-role
  [:enum :register-request-open :register-response-open :map-close
   :defn-head :destructure-close :defn-close])

;; one INFILL slot — a span the worker re-noises + generates.
(schema/register! ::infill-span
  [:map
   [::role ::slot-role]
   [::span ::span]
   [::placeholder :string]])                       ; the valid-Clojure stand-in held in the frame

;; one CLAMP segment — a span of fixed structure the worker HOLDS.
(schema/register! ::clamp-span
  [:map
   [::role ::clamp-role]
   [::span ::span]
   [::text :string]])

(schema/register! ::frame-text :string)

(schema/register! ::fn-name [:string {:min 1}])
(schema/register! ::ns [:string {:min 1}])
(schema/register! ::intent :string)

(schema/register! ::scaffold-request
  [:map
   [::fn-name ::fn-name]                            ; the desired fn name (symbol name)
   [::ns ::ns]                                      ; the ns the fn belongs to (frame is ns-relative)
   [::intent ::intent]])                            ; one-line description → the clamped docstring

(schema/register! ::scaffold-response
  [:map
   [::frame-text ::frame-text]
   [::infill-spans [:vector ::infill-span]]
   [::clamp-spans [:vector ::clamp-span]]])

;; ============================================================
;; Frame assembly — a vector of segments, each CLAMP (fixed) or INFILL (slot).
;; Concatenating in order yields `::frame-text`; tracking the running offset
;; yields spans that TILE the frame exactly (no gap, no overlap) by
;; construction.
;; ============================================================

(defn- segments
  "The ordered `:defn-with-specs` segment list for one fn. Each entry is either
   `[:clamp <role> <text>]` (fixed structure) or `[:infill <role> <placeholder>]`
   (a generated slot). `name` is the fn-name string; `intent` is escaped into a
   docstring literal via `pr-str`."
  [name intent]
  [[:clamp  :register-request-open
    (str "(schema/register! ::" name "-request\n  [:map\n   ")]
   [:infill :request-body  "[::input :string]"]
   [:clamp  :map-close      "\n  ])\n\n"]
   [:clamp  :register-response-open
    (str "(schema/register! ::" name "-response\n  [:map\n   ")]
   [:infill :response-body "[::result :string]"]
   [:clamp  :map-close      "\n  ])\n\n"]
   [:clamp  :defn-head
    (str "(defn " name "\n  " (pr-str intent)
         "\n  {:malli/schema [:=> [:cat ::" name "-request] ::" name "-response]}"
         "\n  [{::keys [")]
   [:infill :arglist       "input"]
   [:clamp  :destructure-close "]}]\n  "]
   [:infill :fn-body        "nil"]
   [:clamp  :defn-close     ")\n"]])

(defn build-scaffold
  "Emit the `:defn-with-specs` clamp FRAME for the fn described by
   `::scaffold-request`. Returns `::frame-text` (valid Clojure with placeholder
   slots), `::infill-spans` (the slots the worker generates), and `::clamp-spans`
   (the fixed structure the worker holds). The two span sets tile the frame."
  {:malli/schema [:=> [:cat ::scaffold-request] ::scaffold-response]}
  [{::keys [fn-name intent]}]
  (let [segs (segments fn-name intent)]
    (loop [offset 0
           segs   segs
           parts  []
           infills []
           clamps  []]
      (if-let [[kind role text] (first segs)]
        (let [end  (+ offset (count text))
              span [offset end]]
          (recur end (rest segs) (conj parts text)
                 (if (= kind :infill)
                   (conj infills {::role role ::span span ::placeholder text})
                   infills)
                 (if (= kind :clamp)
                   (conj clamps {::role role ::span span ::text text})
                   clamps)))
        {::frame-text   (str/join parts)
         ::infill-spans infills
         ::clamp-spans  clamps}))))

;; ============================================================
;; Wire boundary — flatten to the worker's `{op,span,…}` shape, matching
;; `seon.diffusion.retrieval/to-wire`. Clamp spans get op "clamp", infill
;; slots op "infill".
;; ============================================================

(defn- span->wire
  [op role {::keys [span]}]
  #js {:op   (name op)
       :role (name role)
       :span (clj->js span)})

(defn to-wire
  "Flatten a `::scaffold-response` to the worker's JSON-ready frame object:
   `frame_text`, `clamp_spans` (op \"clamp\"), and `infill_spans` (op
   \"infill\"). The worker clamps every `clamp_spans` position and infills every
   `infill_spans` position; spec slots are listed before the body so generation
   order favours the contract."
  {:malli/schema [:=> [:cat [:map [::scaffold ::scaffold-response]]] :any]}
  [{::keys [scaffold]}]
  #js {:frame_text   (::frame-text scaffold)
       :clamp_spans  (clj->js (mapv (fn [c] (span->wire :clamp (::role c) c))
                                     (::clamp-spans scaffold)))
       :infill_spans (clj->js (mapv (fn [i] (span->wire :infill (::role i) i))
                                    (::infill-spans scaffold)))})
