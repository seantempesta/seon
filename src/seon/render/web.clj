(ns seon.render.web
  "The page on a socket — http-kit, one Datastar SSE per tab, one morph
  per block, ONE derivation per cluster.

  F2 §1: the render pipeline is one derivation → equality suppression →
  `mult` → per-tab sliding-1 taps. The RENDER PROC (a proc of the
  cluster's own graph, pinned `:io`, built through
  `seon.flow/var-process`) runs one pass per wake over ONE database
  value, derives every WATCHED agent's page, retains each fragment's
  evidence and bytes, and puts one revisioned package per changed page
  on the mult. Every package carries both its delta and a complete
  keyframe assembled from those retained bytes, so a revision gap can
  always recover from the newest package.

  ONE MORPH PER BLOCK is preserved AT THE PROC: a contiguous revision
  sends the retained delta bytes and a gap sends the retained keyframe
  bytes. A new connection derives its first paint directly from current facts.
  A tab then remembers only its delivered revision. DELETED: per-tab
  page maps after first paint, the old
  `d/listen` registration, and the hand-rolled latest-wins mailbox.

  THE WAKE SOURCE is `wake/route!` — the cluster's ONE listener — which
  offers one payload-free render wake per transaction report; a freshly
  opened tab offers its own. The COALESCE FLOOR
  (`:seon.config.render/coalesce-ms`, a config fact) is honored at the
  proc: a burst of commits costs one derivation for the whole cluster,
  and the per-tab writer only selects retained package bytes. It remains a coalescing floor
  over an observed event (the commit), never a poll.

  THE FEED OPENER IS A SIBLING OF THE MORPH TARGETS, never a child. A
  `data-init` inside a morphed element is stripped by the first
  whole-element morph and the connection never reopens — a lesson the
  quarry paid for and wrote down (`src-old/seon/web/datastar.cljs:611-620`),
  and the reason the shell puts the opener in its own hidden div beside
  the surfaces rather than on the container.

  Crash walk. Everything here is channel contents or process-local
  disposable state — retained fragments/packages, delivered revisions, the
  watched registration, pending packages on taps — all free to lose by
  the transport law: every tab reconnects and repaints from current
  facts (reconnect = repaint), the registration re-fills from
  `on-open`, and the next commit re-offers the wake."
  (:require [seon.error.refusal]
            [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [clojure.data.json :as json]
            [clojure.tools.reader.edn :as reader.edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [clojure.walk :as cwalk]
            [org.httpkit.server :as http]
            [reitit.ring :as ring]
            [seon.await :as await]
            [seon.blob :as blob]
            [seon.cluster.agent :as cluster.agent]
            [seon.cluster.process :as process]
            [seon.cluster.message :as message]
            [seon.turn :as turn]

            [seon.config :as config]
            [seon.context :as context]
            [seon.db :as db]
            [seon.env :as env]
            [seon.health :as health]
            [seon.error :as error]
            [seon.fault :as fault]
            [seon.render :as render]
            [seon.render.block :as block]
            [seon.render.data :as data]
            [seon.render.hiccup :as hiccup]
            [seon.render.route :as route]
            [seon.render.transcript :as transcript]
            [seon.print :as print]
            [seon.render.value :as value]
            [seon.render.walk :as render.walk]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [malli.registry :as mr]
            [seon.schema.internal :as internal]
            [malli.core :as m]
            [seon.sci.admit :as admit]
            [seon.sci.kernel :as sci.kernel]
            [starfederation.datastar.clojure.adapter.common :as datastar.common]
            [starfederation.datastar.clojure.adapter.http-kit :as datastar.http-kit]
            [starfederation.datastar.clojure.api :as datastar]
            [starfederation.datastar.clojure.api.elements :as datastar.elements]
            [starfederation.datastar.clojure.consts :as datastar.consts]
            [taoensso.timbre :as log])
  (:import [java.net URI URLDecoder]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent Executors]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schema.edn
;;; ---------------------------------------------------------------------------

;;; LOAD-CYCLE BOUNDARY. `seon.cluster` requires `seon.render.web`, so this
;;; namespace cannot require it back. One resolution, realized at first use,
;;; instead of a `requiring-resolve` on every call (AGENTS §2.1).
(defonce ^:private cluster-ensure-entity!
  (delay (requiring-resolve 'seon.cluster/ensure-entity!)))

(defn server?
  "True for an http-kit server object.

  The gate resolves and runs this, so it is real code rather than a contract
  stub."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [x]
  (instance? org.httpkit.server.HttpServer x))

(def server-generator
  "An honest generator: a real loopback http-kit server per sample, stopped
  before it is returned (as `seon.flow/graph-generator` stops its graph).
  http-kit's constructors always bind (`HttpServer.java:112-190`), so there
  is no unbound sample; a stopped one keeps no port or selector thread,
  where the once-created shared server stayed bound for the JVM's life."
  (gen/fmap (fn [_]
              (let [server (http/run-server (fn [_] {:status 200 :body ""})
                                            {:ip "127.0.0.1" :port 0 :legacy-return-value? false})]
                ;; the promise is delivered once the server thread exits
                (some-> (http/server-stop! server {:timeout 0}) (deref 1000 nil))
                server))
            (gen/return nil)))

;; the gate refuses a `[:fn]` naming anything that is not a REGISTERED
;; core predicate — resolvable is not the same as vouched for
(schema/register-core-predicate! 'seon.render.web/server? server?)

(defn mult?
  "True for a core.async mult — the fan-out the render packages ride."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (satisfies? clojure.core.async/Mult value))

;; The mult's go-loop starts where every Var has its root binding: the delay
;; may be forced inside an evaluation, whose frame the loop would keep.
(defonce ^:private generator-mult
  (delay (db/call-with-root-bindings #(async/mult (async/chan (async/sliding-buffer 1))))))

(def mult-generator
  "An honest generator: a real mult over a real channel, created once."
  (gen/fmap (fn [_] @generator-mult) (gen/return nil)))

(def byte-array-generator
  "Platform byte arrays for process-local package schemas."
  gen/bytes)

(schema/register-core-predicate! 'seon.render.web/mult? mult?)

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; The shell
;;; ---------------------------------------------------------------------------

(defn- form-pairs
  "One URL-encoded `k=v&k=v` string as string keys and values."
  [encoded]
  (into {}
        (keep (fn [pair]
                (when-not (str/blank? pair)
                  (let [[k v] (str/split pair #"=" 2)]
                    [(URLDecoder/decode ^String k "UTF-8")
                     (URLDecoder/decode ^String (or v "") "UTF-8")]))))
        (str/split encoded #"&")))

(defn- query-params
  [request]
  (form-pairs (or (:query-string request) "")))

(defn- read-browser-edn
  "Read one browser-supplied EDN string, or nil when it is not readable EDN.
  Only tools.reader's declared `:reader-exception` (malformed browser input)
  is absence; every other failure propagates with its complete cause."
  {:malli/schema [:=> [:cat :string] :seon.schema/value]}
  [setting]
  (try
    (reader.edn/read-string setting)
    (catch clojure.lang.ExceptionInfo failure
      (if (= :reader-exception (:type (ex-data failure)))
        nil
        (throw failure)))))

(defn- read-query-value
  {:malli/schema [:=> [:cat [:maybe :string]] :seon.schema/value]}
  [setting]
  (when-not (str/blank? setting)
    (read-browser-edn setting)))

(defn- positive-query-long
  [setting fallback maximum]
  (let [parsed (some-> setting parse-long)]
    (if (and parsed (pos? parsed))
      (min parsed maximum)
      fallback)))

(declare query-entity route-namespace agent-namespace render-source-call)

(defn- carry
  "`m` with each of `ks` that `source` holds truthy."
  [m source ks]
  (into m (filter val) (select-keys source ks)))

(defn- debug-key?
  "True for a debug page's registration key `[::debug-tab request]`."
  [registration-key]
  (and (vector? registration-key) (= ::debug-tab (first registration-key))))

(defn- key-agent-id
  "The agent a page registration key renders: a debug key's request names it."
  [registration-key]
  (if (debug-key? registration-key)
    (:seon.agent/id (second registration-key))
    registration-key))

(def ^:private debug-defaults
  {:seon.render.data/limit 40
   :seon.render.data/max-ref-attributes 40
   :seon.render.data/max-result-weight 4000
   ::pull-max-work 4000})

(defn- debug-query
  "The debug request a query-parameter map asks for, defaults filled."
  {:malli/schema [:=> [:cat :map :seon.schema/value [:maybe :seon.ns/name] [:maybe :seon.agent/id]] :map]}
  [query default-subject viewer-namespace agent-id]
  (let [subject (or (query-entity (get query "subject")) default-subject)
        output (read-query-value (get query "output"))]
    (cond->
     {:seon.render.debug/viewer-namespace viewer-namespace
      :seon.render.debug/subject subject
      :seon.render/output
      ;; Ruling 44 retired the form projection: the page asks for the two
      ;; authored projections and nothing else.
      (if (#{:seon.render/ai :seon.render/html} output)
        output
        :seon.render/html)
      :seon.render.debug/details? (= "true" (get query "details"))
      :seon.render.data/limit
      (positive-query-long (get query "limit")
                           (:seon.render.data/limit debug-defaults) 200)
      :seon.render.data/max-ref-attributes
      (positive-query-long
       (get query "maxRefAttributes")
       (:seon.render.data/max-ref-attributes debug-defaults) 200)
     :seon.render.data/max-result-weight
      (positive-query-long
       (get query "maxResultWeight")
       (:seon.render.data/max-result-weight debug-defaults) 1000000)
      ::pull-max-work
      (positive-query-long (get query "maxWork")
                           (::pull-max-work debug-defaults) 1000000)
      :seon.render.data/cursor
      (data/parse-cursor (get query "path") (get query "offset"))}
      agent-id
      (assoc :seon.agent/id agent-id)

      (read-query-value (get query "outgoingCursor"))
      (assoc :seon.render.data/outgoing-cursor
             (read-query-value (get query "outgoingCursor")))

      (read-query-value (get query "incomingCursor"))
      (assoc :seon.render.data/incoming-cursor
             (read-query-value (get query "incomingCursor"))))))

(defn- debug-query-strings
  "The query-string parameters that round-trip `debug-request`."
  {:malli/schema [:=> [:cat :map] [:map-of :keyword :string]]}
  [debug-request]
  (cond->
   {:debug "true"
    :viewer (str (:seon.render.debug/viewer-namespace debug-request))
    :subject (pr-str (:seon.render.debug/subject debug-request))
    :output (pr-str (:seon.render/output debug-request))
    :details (str (boolean (:seon.render.debug/details? debug-request)))
    :limit (str (:seon.render.data/limit debug-request))
    :maxRefAttributes
    (str (:seon.render.data/max-ref-attributes debug-request))
    :maxResultWeight
    (str (:seon.render.data/max-result-weight debug-request))
    :maxWork (str (::pull-max-work debug-request))
    :path (pr-str (get-in debug-request
                          [:seon.render.data/cursor :seon.render.data/path] []))
    :offset (str (get-in debug-request
                         [:seon.render.data/cursor :seon.render.data/offset] 0))}
    (:seon.render.data/outgoing-cursor debug-request)
    (assoc :outgoingCursor
           (pr-str (:seon.render.data/outgoing-cursor debug-request)))

    (:seon.render.data/incoming-cursor debug-request)
    (assoc :incomingCursor
           (pr-str (:seon.render.data/incoming-cursor debug-request)))))

(defn- with-database
  "`unit` carrying the database `request` renders against, when it has one: a
  web render runs in no evaluation world, so the unit's required
  `:seon.db/db` is handed, never supplied."
  [unit request]
  (cond-> unit (:seon.db/db request) (assoc :seon.db/db (:seon.db/db request))))

(defn message-bar-html
  "`:seon.render/html` — the constant human-to-agent message bar.

  Every transient value lives in a Datastar signal: typed text,
  request progress, and refusal prose. The render depends only on the
  agent id, so unrelated facts retain identical bytes and the render proc
  never includes this surface in the delta or disturbs its caret."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  (let [agent-id (:seon.agent/id unit)]
    [:form {:id (block/surface-id :message-bar)
            :class "seon-bar"
            :data-signals "{text:'',refusal:''}"
            (keyword "data-on:submit")
            (str "$refusal=''; @post('"
                 (route/path ::route/agent-message {:id agent-id})
                 "', {contentType:'form'}); $text=''")
            (keyword "data-on:datastar-fetch")
            (str "evt.detail.el===el && ("
                 "evt.detail.type==='started' ? $refusal='' : "
                 "evt.detail.type==='error' ? $refusal="
                 "'Message not sent (HTTP '+evt.detail.argsRaw.status+'). "
                 "Correct the message and retry.' : null)")}
     [:input {:class "seon-bar-field"
              :type "text"
              :name "content"
              :data-bind "text"
              :required true
              :autocomplete "off"
              :autofocus (not= false (::message-autofocus? unit))
              :placeholder (str "message agent " agent-id " …")}]
     [:button {:class "seon-bar-send" :type "submit"} "send"]
     [:span {:class "seon-bar-refusal"
             :role "status"
             :aria-live "polite"
             :data-show "$refusal"
             :data-text "$refusal"}]]))

(defn shell
  "The HTML document for one agent's page.

  Every surface is placed at its own `surface-id`, because that id is
  what a later morph targets — the document and the patch agree by
  construction, since both call `seon.render.block/surface-id`.

  The feed opener is a hidden SIBLING of the surfaces. `retryMaxCount:
  Infinity` and `openWhenHidden: false` are the quarry's measured
  settings: reconnect forever, and do not hold a socket open for a
  backgrounded tab."
  {:malli/schema [:=> [:cat :seon.render.web/page-request] :string]}
  [{:keys [:seon.agent/id :seon.render/page :seon.render.web/feed-url
           :seon.render.debug/viewer-namespace] :as request}]
  (str
   "<!doctype html>"
   (hiccup/->string
    [:html {:lang "en" :data-theme "phosphor"}
     [:head
      [:meta {:charset "utf-8"}]
      [:meta {:name "viewport"
              :content "width=device-width, initial-scale=1.0"}]
      [:title (str "seon · " (or viewer-namespace id))]
      [:link {:rel "stylesheet"
              :href (route/path ::route/css {:path "output.css"})}]
      (when viewer-namespace
        [:script {:src (route/path ::route/js {:path "cytoscape.min.js"})}])
      [:script {:type "module"
                :src (route/path ::route/js {:path "datastar.js"})}]
      (when viewer-namespace
        [:script {:type "module"
                  :src (route/path ::route/js {:path "seon-graph.js"})}])]
     ;; SEMANTIC CLASSES, not utility strings, for the same reason the
     ;; render surfaces already use them (the note above `.seon-problems`
     ;; in `input.css`): the document frame is one thing, it is styled in
     ;; one place, and restyling the page does not mean editing Clojure.
     [:body {:class "seon-body"}
      ;; `seq`, not the vector: a page is a VECTOR OF ELEMENTS, and a
      ;; vector whose head is a vector is not hiccup — the grammar
      ;; refuses it, correctly, and the first live page came back empty
      ;; until this said `seq`. A seq is a fragment and splices.
      [:main {:class "seon-main"}
       (when (and id (not (::session? request)) (not (::agent-header? request)))
         [:nav {:class "seon-agent-routes"}
          [:a {:href (route/path ::route/agent {:id id})} "agent"]
          [:a {:href (route/path ::route/agent-debug {:id id})} "debug"]])
       (when (and id (not (::session? request)) (not (::agent-header? request))) (message-bar-html (with-database {:seon.agent/id id} request)))
       (seq page)]
      ;; OUTSIDE every morph target. A data-init inside one is stripped
      ;; by that element's first whole-element morph, and the tab then
      ;; looks alive while receiving nothing.
      (when (and feed-url (not (::session? request)))
       [:div {:style "display:none"
             :data-init (str "@get('" feed-url
                             "', {retryMaxCount: Infinity, "
                             "openWhenHidden: false})")}])]])))

;;; ---------------------------------------------------------------------------
;;; Painting
;;; ---------------------------------------------------------------------------

(declare walk-request root-call-id acquire-root cluster-effective! current-cluster-name)

(defn- unit-id
  [agent-id unit]
  (value/node-id
   (assoc unit
          :seon.agent/id agent-id
          :seon.render.value/root [:seon.agent/id agent-id])
   (:seon.render.walk/path unit)))

(defn surface-html
  "Serialize one walked HTML unit into its stable morph wrapper.

  `placement` lays the unit out by recency (`unit-placements`): the focus unit takes the main panel and spans
  the rail's rows, every other unit sits in the rail ordered by the transaction its content last changed at."
  {:malli/schema [:=> [:cat :seon.agent/id
                       :seon.render.walk/unit
                       [:int {:min 0}]
                       [:map [:seon.render.web/changed-t :int] [:seon.render.web/focus? {:optional true} :boolean] [:seon.render.web/rail {:optional true} [:int {:min 0}]]]]
                  :string]}
  [agent-id unit rank {changed-t :seon.render.web/changed-t focus? :seon.render.web/focus? rail :seon.render.web/rail}]
  (let [failure (:seon.error/value unit)
        output (:seon.render/output unit)
        id (unit-id agent-id unit)
        attributes {:id id
                    :class (if focus? "seon-walk-unit seon-focus-unit" "seon-walk-unit")
                    ;; CSS order is a 32-bit integer; a transaction id fits, so the rail needs no position of its own
                    :style (if focus?
                             (str "grid-row:1 / span " (max 1 rail))
                             (str "order:" (- changed-t)))
                    :data-rank rank
                    :data-changed-t changed-t
                    :data-unit-id id
                    :data-walk-path (pr-str (:seon.render.walk/path unit))}]
    (hiccup/->string
     [:article attributes
      (if failure
        (or output
            [:div {:class "seon-render-unavailable"}
             "renderer unavailable"])
        output)])))

(def ^:private stream-strip-id
  (block/surface-id :stream))

(defn- stream-strip-html
  [stream-partial]
  (hiccup/->string
   [:div {:id stream-strip-id
          :class "seon-stream-strip"}
    (when stream-partial
      [:div {:class "seon-stream-live"}
       (when-some [reasoning (:seon.ai/reasoning-partial stream-partial)]
         (transcript/reasoning-disclosure reasoning))
       [:div {:class "seon-stream-reply"}
        [:span {:class "seon-stream-label"} "tokens"]
        [:span {:class "seon-stream-count"}
         (str (or (:seon.ai/tokens stream-partial) 0))]
        [:pre [:code {:class "seon-stream-text"}
               (:seon.ai/text stream-partial)]]]
       [:span {:class "seon-stream-cursor"} ""]])]))

(defn- path-compare
  "Lexicographic walk-path order; vector comparison orders by length first."
  [left right]
  (loop [index 0]
    (cond
      (= index (count left)) (compare (count left) (count right))
      (= index (count right)) 1
      :else
      (let [step-order (compare (nth left index) (nth right index))]
        (if (zero? step-order)
          (recur (inc index))
          step-order)))))

(defn- unit-content
  "What a unit shows: its render's output or its failure, the data its recency compares."
  {:malli/schema [:=> [:cat :seon.render.walk/unit] :map]}
  [unit]
  (select-keys unit [:seon.render/output :seon.error/value]))

(defn- unit-placements
  "Each unit's placement by element id: the transaction its content last changed at (the retained fragment's when
  its content is unchanged, else the database's basis: a unit this process has not rendered yet counts as changed
  now); the focus (the most recent, the later walk rank on a tie) also carries the rail's size."
  {:malli/schema [:=> [:cat :seon.db/database-value [:map-of :string :map]
                       [:vector [:tuple :string :seon.render.walk/unit]]]
                  [:map-of :string [:map [:seon.render.web/changed-t :int] [:seon.render.web/focus? {:optional true} :boolean] [:seon.render.web/rail {:optional true} [:int {:min 0}]]]]]}
  [database retained identified]
  (let [changed (mapv (fn [[element-id unit]]
                        (let [previous (get retained element-id)]
                          [element-id
                           ;; the retained evidence holds the unit it was serialized from
                           (if (and previous (= (unit-content unit) (unit-content (first (:seon.render.fragment/evidence previous)))))
                             (:seon.render.web/changed-t previous)
                             (db/basis-t database))]))
                      identified)
        focus (first (reduce (fn [best entry] (if (<= (second best) (second entry)) entry best))
                             [nil -1] changed))
        rail (dec (count changed))]
    (into {} (map (fn [[element-id t]]
                    [element-id (cond-> {:seon.render.web/changed-t t}
                                  ;; only the focus spans the rail, so a new unit re-serializes it alone
                                  (= focus element-id) (assoc :seon.render.web/focus? true :seon.render.web/rail rail))]))
          changed)))

(defn- page-result
  "One namespace page and the durable owner messages its failures require.

  Derives the agent's flat HTML units at `db` and serializes each through
  `surface-html`. GET, first SSE paint, and the
  render proc share this derivation. The proc retains fragments for deltas.

  Process provenance rides the service request."
  {:malli/schema [:=> [:cat :map] :map]}
  [{:keys [:seon.db/db :seon.agent/id
           :seon.db/connection] caps :seon.sci.admit/caps
    stream-partial :seon.ai/partial
    retained :seon.render.web/retained-fragments
    retained-calls :seon.render/retained-calls
    :as request}]
  (let [captured-calls (atom {})
        render-request
        (-> (assoc (walk-request db caps id :seon.render/html
                                 connection request)
                   :seon.render/retained-calls retained-calls
                   :seon.render/captured-calls captured-calls)
            (carry request [:seon.render/candidate-call-ids
                            :seon.render.walk/root-acquisition]))
        walked-units (render.walk/neighborhood render-request)
        units (mapv #(with-database % {:seon.db/db db})
                    walked-units)
        ranks (into {}
                    (map-indexed (fn [rank unit]
                                   [(:seon.render.walk/path unit) rank]))
                    units)
        placements (unit-placements db retained (mapv #(vector (unit-id id %) %) units))
        rows (mapv (fn [unit]
                     (let [element-id (unit-id id unit)
                           rank (get ranks (:seon.render.walk/path unit))
                           placement (get placements element-id)
                           evidence [unit rank placement]
                           previous (get retained element-id)]
                       (merge placement
                              {:seon.render.web/element-id element-id
                               :seon.render.walk/path (:seon.render.walk/path unit)
                               :seon.render.fragment/evidence evidence
                               :seon.render.web/html
                               (if (= evidence (:seon.render.fragment/evidence previous))
                                 (:seon.render.web/html previous)
                                 (surface-html id unit rank placement))})))
                   units)
        stream-evidence [stream-partial]
        previous-stream (get retained stream-strip-id)
        stream-row
        {:seon.render.web/element-id stream-strip-id
         :seon.render.walk/path [::stream-strip]
         :seon.render.fragment/evidence stream-evidence
         :seon.render.web/html
         (if (= stream-evidence
                (:seon.render.fragment/evidence previous-stream))
           (:seon.render.web/html previous-stream)
           (stream-strip-html stream-partial))}
        rows (conj rows stream-row)
        paths (into {stream-strip-id [::stream-strip]}
                    (map (juxt :seon.render.web/element-id
                               :seon.render.walk/path))
                    rows)
        orders (into {stream-strip-id Long/MAX_VALUE}
                     (map (fn [row]
                            [(:seon.render.web/element-id row)
                             (get ranks (:seon.render.walk/path row) Long/MAX_VALUE)]))
                     rows)
        page (into (sorted-map-by
                    (fn [left right]
                      (let [order (compare (get orders left) (get orders right))]
                        (if (zero? order)
                          (let [path-order (path-compare (get paths left) (get paths right))]
                            (if (zero? path-order) (compare left right) path-order))
                          order))))
                   (map (juxt :seon.render.web/element-id
                              :seon.render.web/html))
                   rows)
        fragments (into {}
                        (map (juxt :seon.render.web/element-id identity))
                        rows)]
    {:seon.render.web/page page
     :seon.render.web/fragments fragments
     :seon.render/captured-calls @captured-calls
     :seon.db/tx-data
     (into [] (mapcat :seon.db/tx-data) units)}))

;;; ---------------------------------------------------------------------------
;;; The per-agent debug value
;;; ---------------------------------------------------------------------------

(defn- ref-attribute?
  [db attribute]
  (= :db.type/ref (get-in db [:schema attribute :db/valueType])))

(defn- many-attribute?
  [db attribute]
  (= :db.cardinality/many
     (get-in db [:schema attribute :db/cardinality])))

(defn- direct-attribute
  [db eid attribute]
  (let [values (map (fn [datom]
                      (let [setting (:v datom)]
                        (if (ref-attribute? db attribute)
                          {:db/id setting}
                          setting)))
                    (db/datoms db :eavt eid attribute))]
    (if (many-attribute? db attribute)
      values
      (first values))))

(defn- generic-entity
  "Every direct attribute of one entity, with refs left as drill handles.
  The one permitted generic read: it runs only for an open debug tab and
  pays that wider read cost honestly."
  [db eid]
  (into {:db/id eid}
        (map (fn [attribute]
               [attribute (direct-attribute db eid attribute)]))
        (into (sorted-set-by #(compare (str %1) (str %2)))
              (map :a)
              (db/datoms db :eavt eid))))

(defn- reverse-attribute?
  [attribute]
  (and (qualified-keyword? attribute)
       (str/starts-with? (name attribute) "_")))

(defn- forward-attribute
  [attribute]
  (if (reverse-attribute? attribute)
    (keyword (namespace attribute) (subs (name attribute) 1))
    attribute))

(defn- projection-form
  "The authored Malli form one schema key carries in `projection`."
  [projection schema-key]
  (get-in projection [:seon.schema.projection/forms schema-key]))

(defn- declared-unit?
  "Does the registry declare this attribute in its own right?

  A UNIT IS AN ATTRIBUTE THE REGISTRY DECLARES, NOT EVERY QUALIFIED KEY A
  MATCHED SHAPE MENTIONS. Maps are open (AGENTS.md §2.5), so the small
  `:seon.render.transcript/pulled-transaction` shape — `[:map [:db/id :int]
  [:db/txInstant …]]` — is satisfied by EVERY entity a `'[*]` pull returns,
  and its entries were folded into every page's unit list. `:db/id` is the
  database's address for the entity, not one of the entity's units, and the
  registry says so itself: it carries no form. So the rule is a derivation
  over data already at hand, never a name-based exclusion or a reserved list."
  [projection attribute]
  (some? (projection-form projection (forward-attribute attribute))))

(defn- declared-entity-units
  "Every attribute declared by the entity's matching schemas, in schema order."
  [projection database value]
  (if (map? value)
    (schema/call-with-projection
     projection
     (fn []
       (into []
             (comp (map :seon.schema/key)
                   (distinct)
                   (keep #(some->> (mr/schema (:seon.schema.projection/registry projection) %)
                                   internal/entity-entries
                                   (map first)
                                   (filter qualified-keyword?)
                                   (filter (partial declared-unit? projection))))
                   cat
                   (distinct))
             (concat (schema/matching-shapes-in
                      projection (value/transacted value database))
                     (schema/matching-shapes-in projection value)))))
    []))

(defn- attribute-description
  "One attribute's authored Malli `:description`, or nil when undeclared."
  [projection attribute]
  (some-> (mr/schema (:seon.schema.projection/registry projection) (forward-attribute attribute))
          m/properties
          :description))

(defn- debug-diagnostic
  {:malli/schema [:=> [:cat :string :qualified-symbol :qualified-keyword
                       :seon.schema/value :seon.schema/value :seon.render.web/refusal-reason [:maybe :map]]
                  :seon.render.web/request-error]}
  [message operation member expected offending cause evidence]
  (merge evidence (seon.error.refusal/diagnostic (java.util.Date.) :seon.render.web/debug operation
                                                 {:seon.render.web/refused-member member
                                                  :seon.error/message message
                                                  :seon.error/fix "Repair the unavailable page input and repeat the observation."
                                                  :seon.error/expected expected
                                                  :seon.error/offending offending
                                                  :seon.render.web/refusal-reason cause})))

(defn- debug-turn-request
  [database connection agent-id caps render-context]
  (merge render-context
         {:seon.db/db database
          :seon.cluster/name (or (:seon.cluster/name render-context)
                                 (get-in render-context [:seon.turn.loop/cluster :seon.cluster/name]))
          :seon.db/connection connection
          :seon.agent/id agent-id
          :seon.sci.eval/time-limit-ms
          (:seon.config.eval/time-limit-ms render-context)
          :seon.render.value/root [:seon.agent/id agent-id]
          :seon.sci.admit/caps caps}))

(defn- session-controls [request]
  (let [agent-id (:seon.agent/id request)]
    (assoc request
           ::transcript/message-form
           (message-bar-html (with-database {:seon.agent/id agent-id ::message-autofocus? false} request)))))

(defn- scalar-label
  "A graph label for an identity value: a scalar's own text, else nil."
  {:malli/schema [:=> [:cat :seon.schema/value] [:maybe :string]]}
  [v]
  (when (or (string? v) (ident? v) (number? v) (uuid? v)) (str v)))

(defn- debug-page-url
  [debug-request changes]
  (let [request (merge debug-request changes)
        defaults (debug-query {} (:seon.render.debug/subject request)
                              (:seon.render.debug/viewer-namespace request)
                              (:seon.agent/id request))
        default-query (debug-query-strings defaults)
        query (debug-query-strings (merge defaults request))]
    (route/path
     ::route/namespace-debug
     {:namespace (str (:seon.render.debug/viewer-namespace request))}
     (into {}
           (remove (fn [[parameter setting]]
                     (and (not= :subject parameter)
                          (= setting (get default-query parameter)))))
           query))))

(defn- debug-subject-link
  [debug-request subject label]
  [:a {:href (debug-page-url
              debug-request
              {:seon.render.debug/subject subject
               :seon.render.data/cursor (data/parse-cursor nil nil)
               :seon.render.data/outgoing-cursor nil
               :seon.render.data/incoming-cursor nil})}
   [:code (pr-str label)]])

(defn- debug-details-link
  [debug-request]
  [:a {:href (debug-page-url debug-request
                            {:seon.render.debug/details? true})}
   "Load full raw data and render evidence"])

(defn- debug-program-identity
  [database ctx]
  (let [projection (sci.kernel/context-projection ctx)]
    {:seon.source/digest
     (db/q '[:find ?digest .
             :where [_ :seon.source/digest ?digest]]
           database)
     :seon.schema.projection/fingerprint
     (:seon.schema.projection/fingerprint projection)}))

(defn- debug-header-html
  [database debug-request observation acquisition acquisition-ms program-identity]
  (let [cursor (or (:seon.render.data/cursor debug-request)
                   (data/parse-cursor nil nil))
        path (:seon.render.data/path cursor)
        identities
        (when (map? acquisition)
          (into (sorted-map-by #(compare (str %1) (str %2)))
                (keep (fn [attribute]
                        (when-let [entry (find acquisition attribute)]
                          [attribute (val entry)])))
                (db/identity-attributes database)))
        output-link
        (fn [output label]
          [:a (cond-> {:href (debug-page-url
                              debug-request
                              {:seon.render/output output})}
                (= output (:seon.render/output debug-request))
                (assoc :aria-current "page"))
           label])]
    (hiccup/->string
   [:header {:id "debug-inspection-header" :class "seon-debug-header"}
    [:div
     [:h1 "Selected entity"]
     [:code (pr-str (:seon.render.debug/subject debug-request))]
     [:span "Database ID"]
     [:code (str (:seon.render.data/eid observation))]
     (when (seq identities)
       [:span "Declared identities"])
     (when (seq identities)
       (into [:span {:class "seon-debug-identities"}]
             (map (fn [[attribute value]]
                    [:code (pr-str [attribute value])]))
             identities))
     (when-let [agent-id (:seon.agent/id debug-request)]
       [:a {:href (debug-page-url
                    debug-request
                    {:seon.render.debug/subject [:seon.agent/id agent-id]
                     :seon.render.data/cursor (data/parse-cursor nil nil)})}
        "View agent entity"])]
    [:div
     [:span "Rendering from namespace"]
     [:code (str (:seon.render.debug/viewer-namespace debug-request))]]
    (when (seq path)
      [:div
       [:span "selected path"]
       (value/html {:seon.render/value path :seon.db/db database :seon.render.value/root [::selected-path]})
       [:a {:href (debug-page-url
                   debug-request
                   {:seon.render.data/cursor (data/parse-cursor nil nil)})}
        "return to entity"]])
    [:details {:class "seon-debug-evidence"}
     [:summary "Database and render diagnostics"]
     [:div
     [:span "snapshot"]
     (value/html {:seon.render/value (:seon.render.data/snapshot observation) :seon.db/db database :seon.render.value/root [::snapshot]})]
    [:div
     [:span "indexed source digest"]
     (value/html {:seon.render/value (:seon.source/digest program-identity) :seon.db/db database :seon.render.value/root [::source-digest]})]
    [:div
     [:span "schema projection fingerprint"]
     (value/html {:seon.render/value (:seon.schema.projection/fingerprint program-identity) :seon.db/db database :seon.render.value/root [::projection-fingerprint]})]
    [:div
     [:span "render value acquisition"]
     [:code (str acquisition-ms " ms · pull work ≤ "
                (::pull-max-work debug-request)
                " · index page weight ≤ "
                (:seon.render.data/max-result-weight debug-request))]]]
    [:div
     [:span "output"]
     [:nav {:aria-label "Rendered output"}
      (output-link :seon.render/html "HTML")
      (output-link :seon.render/ai "AI")]]])))

(defn- installed-ref-attributes
  [database]
  (into #{}
        (keep (fn [[attribute definition]]
                (when (= :db.type/ref (:db/valueType definition)) attribute)))
        (:schema database)))

(defn- graph-node-id
  [branch eid]
  (pr-str [branch eid]))

(defn- graph-model
  {:malli/schema [:=> [:cat :map [:set :qualified-keyword] :map] [:or :nil :map]]}
  [debug-request ref-attributes observation]
  (when-not (or (:seon.db/invalid-read observation) (:seon.schema/expected-value observation) (:seon.render.data/refused-member observation))
    (let [snapshot (:seon.render.data/snapshot observation)
          branch (:db-name snapshot)
          selected-eid (:seon.render.data/eid observation)
          selected-id (graph-node-id branch selected-eid)
          datoms (concat (get-in observation [:seon.render.data/outgoing
                                              :seon.render.data/datoms])
                         (get-in observation [:seon.render.data/incoming
                                              :seon.render.data/datoms]))
          edges (into (sorted-map)
                      (keep (fn [{:keys [e a v tx]}]
                              (when (ref-attributes a)
                                (let [id (pr-str [branch e a v tx])]
                                  [id {:data {:id id
                                              :source (graph-node-id branch e)
                                              :target (graph-node-id branch v)
                                              :attribute (str a)}}]))))
                      datoms)
          eids (into #{selected-eid}
                     (mapcat (juxt :e :v))
                     (filter #(ref-attributes (:a %)) datoms))
          selected-label
          (or (some->> (:seon.render.data/identities observation)
                       (sort-by (juxt :a :v) print/compare-values)
                       first :v scalar-label)
              (str selected-eid))
          nodes (mapv (fn [eid]
                        {:data {:id (graph-node-id branch eid)
                                :label (if (= eid selected-eid)
                                         selected-label (str eid))
                                :href (debug-page-url
                                       debug-request
                                       {:seon.render.debug/subject eid
                                        :seon.render.data/cursor
                                        (data/parse-cursor nil nil)
                                        :seon.render.data/outgoing-cursor nil
                                        :seon.render.data/incoming-cursor nil})}})
                      (sort eids))]
      {:elements {:nodes nodes :edges (vec (vals edges))}
       "seon.graph/selected" selected-id
       "seon.graph/snapshot" snapshot})))

(defn- json-script-text
  [value]
  (str/replace (json/write-str value) "<" "\\u003c"))

(defn- debug-graph-html
  [debug-request ref-attributes observation]
  (hiccup/->string
   (if-let [model (graph-model debug-request ref-attributes observation)]
     [:section {:id "debug-graph" :class "seon-debug-graph"
                :data-seon-graph ""}
      [:h2 {:class "seon-debug-graph-heading"} "reference graph"]
      [:div {:class "seon-debug-graph-canvas"
             :data-graph-canvas "" :data-ignore-morph ""
             :role "img"
             :aria-label "Reference graph around the selected entity"}]
      [:script {:type "application/json" :data-graph-model ""}
       (hiccup/raw (json-script-text model))]
      [:p {:class "seon-debug-graph-status" :data-graph-status ""}
       (str "Loaded " (count (get-in model [:elements :edges]))
            " reference assertions; outgoing "
            (if (get-in observation [:seon.render.data/outgoing
                                     :seon.render.data/complete?])
              "complete" "partial")
            ", incoming "
            (if (get-in observation [:seon.render.data/incoming
                                     :seon.render.data/complete?])
              "complete" "partial") ".")]
      [:p {:class "seon-debug-graph-status" :data-graph-detail ""
           :aria-live "polite"}
       "Select a reference assertion for details."]]
     [:section {:id "debug-graph" :class "seon-debug-graph"}
      [:h2 {:class "seon-debug-graph-heading"} "reference graph"]
      [:p {:class "seon-debug-graph-status" :data-graph-status ""}
       "Graph unavailable because the bounded observation is unavailable."]])))

(defn- debug-renderer-link
  [debug-request producer]
  (debug-subject-link debug-request [:seon.fn/sym producer] producer))

(defn- debug-renderer-contract
  [database call-entry producer]
  (let [contract
        (get-in call-entry
                [:seon.render/projection
                 :seon.schema.projection/function-contracts producer])]
    (when contract
      [:details {:class "seon-debug-renderer-evidence"}
       [:summary "declared contract and arities"]
       (value/html {:seon.render/value contract :seon.db/db database :seon.render.value/root [::renderer-contract producer]})])))

(defn- debug-renderer-definition
  [call-entry]
  (let [source (get-in call-entry
                        [:seon.render.call/static-evidence
                         :seon.render.call/declaration-row
                        :seon.sci.eval/function-source])]
    (if (string? source)
      [:details {:class "seon-debug-function-definition"}
       [:summary "function definition"]
       [:pre [:code source]]]
      [:p {:class "seon-debug-renderer-source-missing"}
       "No source is stored for this function."])))

(defn- debug-preview-html
  [output rendered database]
  [:div {:class "seon-debug-candidate-preview"}
   (cond
     (:seon.render/refused-member rendered) (error/render-html (with-database rendered {:seon.db/db database}))
     (= output :seon.render/html) rendered
     :else [:pre (str rendered)])])

(defn- debug-read-dependencies-html
  [database entry]
  (if (contains? entry :seon.render.call/read-evidence)
    (let [evidence (:seon.render.call/read-evidence entry)]
      [:details {:class "seon-debug-read-dependencies"}
       [:summary (str "database read dependencies · " (count evidence)
                      (if (= 1 (count evidence))
                        " evidence entry"
                        " evidence entries"))]
       [:p {:class "seon-debug-read-basis"}
        "render basis transaction "
        (pr-str (:seon.render.call/basis-transaction entry))]
       (if (seq evidence)
         (into [:ol]
               (map-indexed
                (fn [index read]
                  [:li
                   [:details
                    [:summary (str "evidence entry " (inc index))]
                    (value/html {:seon.render/value (select-keys
                      read
                      [:seon.db/source-argument-position
                       :datahike.read/dependency-plan
                       :datahike.read/revision])
                                 :seon.db/db database :seon.render.value/root [::read-evidence index]})]]))
               evidence)
         [:p "No database read evidence was retained."])])
    [:details {:class "seon-debug-read-dependencies"}
     [:summary "database read dependencies unavailable"]
     [:p "Read evidence is absent from this retained render entry."]]))

(defn- debug-applicable-candidates
  [experiment]
  (let [selected (get-in experiment
                         [:seon.render/selection
                          :seon.render.selection/selected])]
    (into []
          (comp
           (map-indexed vector)
           (mapcat
            (fn [[priority stage]]
              (map #(assoc % ::stage stage ::priority priority)
                   (:seon.render.selection.stage/candidates stage))))
           (filter #(= :compatible
                       (:seon.render.selection.candidate/status %)))
           (remove #(= selected
                       (:seon.render.selection.candidate/producer %))))
          (get-in experiment
                  [:seon.render/selection
                   :seon.render.selection/stages]))))

(defn- experiment-preview-html
  "The value one experiment's selected render function produced.

  A refused selection and an absent render function are both shown as such;
  this boundary never omits an unavailable projection."
  [output experiment database]
  (let [selection (:seon.render/selection experiment)
        selected (:seon.render.selection/selected selection)
        previews (:seon.render/previews experiment)
        selected-stage
        (some #(when (= :selected (:seon.render.selection.stage/status %)) %)
              (:seon.render.selection/stages selection))
        rendered (if (contains? selected-stage :seon.render.selection.stage/value)
                   (:seon.render.selection.stage/value selected-stage)
                   (get previews selected))]
    (cond
      (or (:seon.render/refused-member selection) (:seon.render/candidates selection))
      (error/render-html (with-database selection {:seon.db/db database}))
      (or (:seon.render/refused-member selected) (:seon.render/candidates selected))
      (error/render-html (with-database selected {:seon.db/db database}))
      (nil? rendered)
      [:p {:class "seon-debug-empty"}
       "No render function produced a value for this projection."]
      :else (debug-preview-html output rendered database))))

(defn- debug-selected-renderer-details
  [database debug-request output experiment]
  (let [selection (:seon.render/selection experiment)
        selected (:seon.render.selection/selected selection)
        entry (get (:seon.render/entries experiment) selected)]
    (when (and (not (or (:seon.render/refused-member selection) (:seon.render/candidates selection)))
               (qualified-symbol? selected))
      [:section {:class "seon-debug-selected-renderer"}
       [:h4 (if (= output :seon.render/ai) "AI" "HTML")]
       [:p
        "renderer "
        (debug-renderer-link
         (assoc debug-request :seon.render/output output) selected)]
       (when (and entry (:seon.render.debug/details? debug-request))
         [:div
          (debug-read-dependencies-html database entry)
          (debug-renderer-contract database entry selected)
          (debug-renderer-definition entry)])])))

(declare debug-render-experiment)

(defn- referenced-entity-ids
  [value]
  (letfn [(ids [node]
            (cond
              (map? node)
              (into (if-let [eid (:db/id node)] [eid] [])
                    (mapcat ids)
                    (vals (dissoc node :db/id)))

              (coll? node) (into [] (mapcat ids) node)
              :else []))]
    (into [] (distinct) (ids value))))

(defn- referenced-identities
  "Resolve referenced entities through the installed identity attributes."
  [database value]
  (let [attributes (filterv #(not= :db/id %) (db/identity-attributes database))]
    (letfn [(identities [eid visited]
              (when-not (visited eid)
                (let [entity (db/pull database attributes eid)]
                  (mapcat
                   (fn [attribute]
                     (when-let [[_ identity-value] (find entity attribute)]
                       (if-let [ref-id (and (map? identity-value)
                                           (:db/id identity-value))]
                         (when-let [[_ label] (first (identities ref-id (conj visited eid)))]
                           [[[attribute ref-id] label]])
                         [[[attribute identity-value] identity-value]])))
                   attributes))))]
      (into [] (comp (mapcat #(identities % #{})) (distinct))
            (referenced-entity-ids value)))))

(defn- connected-value
  "One unit's value with its acquired referenced entities in place.

  A pull returns a ref as `{:db/id n}`, which no render function can say
  anything about. The page already acquired the connected entities for this
  observation, so a unit renders the entity it points at; an entity outside
  the bounded acquisition keeps its honest `{:db/id n}`."
  [related value]
  (cwalk/postwalk
   (fn [node]
     (if (and (map? node) (= [:db/id] (keys node)))
       (get related (:db/id node) node)
       node))
   value))

(defn- selected-unit-experiment
  [render-request attribute output value root cursor]
  (let [call-id [::unit-render attribute output]
        request (assoc render-request
                       :seon.render/value value
                       :seon.render.walk/attribute (forward-attribute attribute)
                       :seon.render.value/root root
                       :seon.render.data/cursor cursor
                       :seon.render/output output
                       :seon.render.call/id call-id)
        rendered (render-source-call request)
        entry (get @(:seon.render/captured-calls render-request) call-id)
        selected (get-in entry [:seon.render.call/static-evidence
                                :seon.render.call/producer])
        preview (if (and (nil? rendered) (:seon.render.call/source entry))
                  "The selected source is running through the agent's ordinary episode."
                  rendered)]
    (debug-render-experiment
     request output [attribute cursor]
     {:seon.render.call/producer selected
      :seon.render.call/output preview
      :seon.render.call/entry entry})))

(defn- applicable-renderers-html
  "The selected render functions for one unit and their compatible alternatives."
  [database debug-request experiments]
  (let [alternatives
        (into []
              (comp
               (mapcat
                (fn [output]
                  (map (fn [candidate]
                         [output
                          (:seon.render.selection.candidate/producer candidate)])
                       (debug-applicable-candidates (get experiments output)))))
               (distinct))
              [:seon.render/ai :seon.render/html])
        selected
        (into []
              (keep (fn [output]
                      (debug-selected-renderer-details
                       database debug-request output (get experiments output))))
              [:seon.render/ai :seon.render/html])]
    [:details {:class "seon-debug-renderers"}
     [:summary "Applicable render functions"]
     (if (seq selected)
       (into [:div {:class "seon-debug-projection-grid"}] selected)
       [:p "No render function was selected for this attribute."])
     (when-not (:seon.render.debug/details? debug-request)
       (debug-details-link debug-request))
     (when (seq alternatives)
       [:details {:class "seon-debug-alternative-renderers"}
        [:summary (str "compatible alternatives · " (count alternatives))]
        (into [:ul]
              (map (fn [[output producer]]
                     [:li [:span (if (= output :seon.render/ai) "AI " "HTML ")]
                      (debug-renderer-link
                       (assoc debug-request :seon.render/output output)
                       producer)]))
              alternatives)])]))

(defn- block-metadata
  "Use the rendered value's matching schema and its authored documentation."
  [projection database value attribute producer]
  (let [matches (concat
                 (when (map? value)
                   (schema/matching-shapes-in projection (value/transacted value database)))
                 (schema/matching-shapes-in projection value))
        matching (or (some #(when (and producer
                                       (or (= producer (:seon.render/ai %))
                                           (= producer (:seon.render/html %))))
                              %) matches)
                     (some #(when (:seon.db/attributes
                                   (m/properties (mr/schema (:seon.schema.projection/registry projection) (:seon.schema/key %))))
                              %) matches))
        schema-key (:seon.schema/key matching)
        form (projection-form projection schema-key)
        properties (some-> (mr/schema (:seon.schema.projection/registry projection) schema-key) m/properties)
        attribute-properties (some-> (mr/schema (:seon.schema.projection/registry projection) (forward-attribute attribute))
                                     m/properties)]
    {:seon.schema/key schema-key
     :seon.schema/form form
     :seon.render.web/block-title (or (:title properties) (:title attribute-properties)
                                     (some-> schema-key str) (str attribute))
     :seon.render.web/block-description
     (or (:description properties)
         (attribute-description projection attribute)
         (some #(attribute-description projection %)
               (:seon.schema/required-attrs matching)))}))

(defn- debug-found-value
  "One declared unit: its key, description, references, and paired outputs."
  [projection render-request debug-request attribute value present?
   related]
  (let [attribute-schema (projection-form projection
                                          (forward-attribute attribute))
        root (:seon.render.debug/subject debug-request)
        cursor {:seon.render.data/path
                (if (or (reverse-attribute? attribute)
                        (= attribute :seon.agent/agent)) [] [attribute])
                :seon.render.data/offset 0}
        value (if present? (connected-value related value) value)
        experiments
        (when present?
          {:seon.render/ai
           (selected-unit-experiment render-request attribute :seon.render/ai
                                     value root cursor)
           :seon.render/html
           (selected-unit-experiment render-request attribute :seon.render/html
                                     value root cursor)})
        producer (first (keys (get-in experiments [:seon.render/ai :seon.render/previews])))
        metadata (block-metadata projection (:seon.db/db render-request)
                                 value attribute producer)
        description (:seon.render.web/block-description metadata)
        references (when present?
                     (referenced-identities (:seon.db/db render-request) value))]
    [:article {:class "seon-debug-found-value seon-debug-attribute-unit"
               :data-seon-unit (str attribute)}
     [:header {:class "seon-debug-value-header"}
      [:div
       [:h2 (:seon.render.web/block-title metadata)]
       (when (not= (str attribute) (:seon.render.web/block-title metadata))
         [:div {:class "seon-debug-value-label"}
          [:code (str attribute)]])
       (when description
         [:p {:class "seon-debug-description"} description])]
      (when (seq references)
        (into [:div {:class "seon-debug-stored-value"}]
              (interpose " "
                         (map (fn [[subject identity-value]]
                                (assoc (debug-subject-link
                                        debug-request subject
                                        identity-value)
                                       2 [:code (str identity-value)]))
                              references))))]
     [:div {:class "seon-debug-projection-grid seon-debug-selected-previews"}
      [:section {:class "seon-debug-projection-column"}
       [:h4 "AI"]
       (if present?
         (experiment-preview-html :seon.render/ai (:seon.render/ai experiments)
                                  (:seon.db/db render-request))
         [:p {:class "seon-debug-empty"}
          "No value is stored or connected for this attribute."])]
      [:section {:class "seon-debug-projection-column"}
       [:h4 "HTML"]
       [:div
        (if present?
          (experiment-preview-html :seon.render/html
                                   (:seon.render/html experiments)
                                   (:seon.db/db render-request))
          [:p {:class "seon-debug-empty"}
           "No value is stored or connected for this attribute."])]]]
     [:details {:class "seon-debug-data-details"}
      [:summary "Raw data and schema"]
      (if (:seon.render.debug/details? debug-request)
       [:div
       [:h4 "Raw data"]
      (if present?
        (value/html {:seon.render/value value :seon.db/db (:seon.db/db render-request) :seon.render.value/root [::raw-data]})
        [:p "This attribute is absent on the selected entity."])
      [:h4 "Malli schema"]
      (if-let [form (or (:seon.schema/form metadata) attribute-schema)]
        (value/html {:seon.render/value form :seon.db/db (:seon.db/db render-request) :seon.render.value/root [::malli-schema]})
        [:p "No schema is registered for this attribute."])]
       (debug-details-link debug-request))]
     (if present?
       (applicable-renderers-html (:seon.db/db render-request) debug-request experiments)
       [:details {:class "seon-debug-renderers"}
        [:summary "Applicable render functions"]
        [:p "No value is available for render-function selection."]])]))

(defn- debug-found-values-html
  "Every declared attribute, then other stored attributes and reverse concerns."
  {:malli/schema [:=> [:cat :seon.schema/projection :map :map :map [:sequential :qualified-keyword] :map :map :map] :seon.render/hiccup]}
  [projection render-request debug-request acquisition declared-units
   observation reverse-values related]
  (let [declared (set declared-units)
        additional (->> (concat (keys acquisition) (keys reverse-values))
                        (filter qualified-keyword?)
                        (remove #{:db/id})
                        (remove declared)
                        distinct
                        (sort-by str))
        agent? (some? (:seon.agent/id acquisition))
        components (when agent?
                     (into #{}
                           (keep (fn [[attribute properties]]
                                   (when (:db/isComponent properties) attribute)))
                           (:schema (db/schema-database
                                     (:seon.db/db render-request)))))
        identity-value (when agent?
                         (into {}
                               (remove (fn [[attribute _]]
                                         (or (get components attribute)
                                             (reverse-attribute? attribute))))
                               acquisition))
        units (cond->> (concat declared-units additional)
                agent? (filter #(or (reverse-attribute? %)
                                    (get components %))))]
  [:section {:id "debug-units" :class "seon-debug-found-values"}
   [:h1 {:class "seon-debug-caption"} "Entity attributes and connections"]
   (if (or (:seon.db/invalid-read acquisition) (:seon.schema/expected-value acquisition))
     (error/render-html acquisition)
     (into (cond-> [:div]
             agent?
             (conj (debug-found-value
                    projection render-request debug-request
                    :seon.agent/agent identity-value true related)))
           (map (fn [attribute]
                  (let [entry (find (if (reverse-attribute? attribute)
                                     reverse-values acquisition) attribute)]
                     (debug-found-value
                      projection render-request debug-request attribute
                      (when entry (val entry)) (some? entry) related))))
           units))
   (when-not (get-in observation [:seon.render.data/incoming
                                  :seon.render.data/complete?])
     [:p {:class "seon-debug-empty"}
      "Incoming connections are a bounded query page; the reference graph shows its continuation."])]))

(declare refresh-retained-read-evidence)

(defn- assigned-agent-namespace
  [database agent-id]
  (get-in (db/pull database
                   [{:seon.agent/namespace [:seon.ns/name]}]
                   [:seon.agent/id agent-id])
          [:seon.agent/namespace :seon.ns/name]))

(defn- render-source-call
  "Render one authored source slot, evaluating it through the run loop's one path.

  THE PAGE HAS NO EVALUATOR. `loop/preview-sources` is the same fork, parse
  and evaluation an ordinary turn runs; this function only decides whether the
  ONE cache keyed by code and input already holds the answer — and it does not
  decide that itself either: `render/render-call` consulted the invocation
  cache with `same-invocation-evidence?` and the retained read evidence before
  returning, and an entry carrying an output is that cache's hit."
  {:malli/schema [:=> [:cat :map] [:or :seon.render/rendered :seon.render.web/request-error :seon.render/error-result :seon.turn/error :seon.db/error-result :seon.cluster.reply/no-forms-error]]}

  [request]
  (let [request (cond-> request
                  (= :seon.render/ai (:seon.render/output request))
                  (assoc :seon.render.call/source-output? true))
        rendered (render/render-call request)
        call-id (:seon.render.call/id request)
        call-entry (get @(:seon.render/captured-calls request) call-id)
        invocation-key (:seon.render.call/invocation-key call-entry)
        source (:seon.render.call/source call-entry)]
    (if (or (not source) (:seon.render.call/output call-entry))
      rendered
      (let [database (:seon.db/db request)
            agent-id (:seon.agent/id request)
            observed (atom [])
            namespace-name
            (or (:seon.render/namespace request)
                (when agent-id (assigned-agent-namespace database agent-id)))
            preview
            (if-not (and agent-id namespace-name)
              (debug-diagnostic
               "Evaluating this preview requires an agent assigned to the viewing namespace."
               'seon.render.web/render-source-call
               :seon.agent/id :seon.agent/id
               (select-keys request [:seon.render/namespace :seon.render.value/root])
               ::owner-not-ensured nil)
              (binding [db/*read-evidence-sink* observed]
                (let [evaluated
                      (turn/preview-sources
                       {:seon.turn.loop/cluster (:seon.turn.loop/cluster request)
                        :seon.db/db database
                        :seon.sci.eval/ctx (:seon.sci.eval/ctx request)
                        :seon.agent/id agent-id
                        :seon.ns/name namespace-name
                        :seon.cluster.reply/text source
                        :seon.sci.admit/caps (:seon.sci.admit/caps request)})]
                  (cond-> evaluated
                    (not (or (:seon.cluster.reply/no-forms evaluated)))
                    (assoc :seon.render.call/source-run-id (str (random-uuid)))))))
            output
            (if (or (:seon.cluster.reply/no-forms preview) (:seon.render.web/refused-member preview))
              preview
              (binding [db/*read-evidence-sink* observed]
                (transcript/render-ai
                 (-> request
                     (merge preview)
                     (assoc :seon.turn/id (:seon.render.call/source-run-id preview))))))
            evidence
            (into (db/read-evidence @observed)
                  (mapcat #(get-in % [:seon.sci.eval/evaluation :seon.cluster.eval/read-evidence]))
                  (:seon.turn.loop/evaluated-sources preview))
            ;; ONE STORE OF THE EVALUATION. The invocation entry is the cache
            ;; keyed by code and input (ruling 71), so the evaluated forms and
            ;; their rendered bytes live there and nowhere else; the call entry
            ;; keeps only what every other page slot keeps — the presented
            ;; output, its evidence, and the pointer to that invocation.
            enrich-invocation
            (fn [entry]
              (cond-> (assoc entry :seon.render.call/output output)
                (not (or (:seon.cluster.reply/no-forms preview) (:seon.render.web/refused-member preview))) (merge preview)
                (seq evidence) (update :seon.render.call/read-evidence into evidence)))
            enrich-call
            (fn [entry]
              (cond-> (assoc entry :seon.render.call/output output)
                (not (or (:seon.cluster.reply/no-forms preview) (:seon.render.web/refused-member preview)))
                (assoc :seon.render.call/source-run-id
                       (:seon.render.call/source-run-id preview))
                (seq evidence) (update :seon.render.call/read-evidence into evidence)))]
        (render/retain-invocation! invocation-key
                                   (enrich-invocation (merge (render/invocation invocation-key call-entry)
                                                             call-entry)))
        (swap! (:seon.render/captured-calls request) update call-id enrich-call)
        output))))

(defn- debug-render-experiment
  [render-request output subject selected-call]
  (let [request (assoc render-request :seon.render/output output)
        selection-call-id [::inspection-selection subject output]
        ctx (:seon.sci.eval/ctx request)
        static-evidence
        {:seon.render.call/producer 'seon.render/selection-inspection
         :seon.render.call/argument
         [(:seon.render/value request) output
          (render/source-generation (:seon.db/db request))
          (some-> (:seon.sci.kernel/program-snapshot ctx) deref)
          (get (sci.kernel/context-projection ctx)
               :seon.schema.projection/fingerprint)]}
        previous (get (:seon.render/retained-calls request) selection-call-id)
        reusable? (and previous
                       (= static-evidence
                          (:seon.render.call/static-evidence previous))
                       (db/read-evidence-current?
                        (:seon.db/db request)
                        (:seon.render.call/read-evidence previous)))
        captured (atom [])
        inspection
        (if reusable?
          (:seon.render.call/output previous)
          (binding [db/*read-evidence-sink* captured]
            (render/selection-inspection request)))
        selection-entry
        (if reusable?
          (update previous :seon.render.call/read-evidence
                  #(refresh-retained-read-evidence (:seon.db/db request) %))
          {:seon.render.call/static-evidence static-evidence
           :seon.render.call/read-evidence
           (db/read-evidence @captured)
           :seon.render.call/basis-transaction
           (db/basis-t (:seon.db/db request))
           :seon.render.call/output inspection})
        _ (swap! (:seon.render/captured-calls request)
                 assoc selection-call-id selection-entry)
        ;; A refused or ambiguous selection is not a producer: the request's
        ;; declared `:seon.render.call/selected-producer` is a qualified
        ;; symbol, and handing it anything else refuses the whole page.
        selected (let [decision (:seon.render.selection/selected inspection)]
                   (when (qualified-symbol? decision) decision))
        call-id (fn [producer]
                  [::inspection-candidate subject output producer])
        ;; ONE render per unit and output: the selected producer's. The
        ;; compatible alternatives are listed by name, so rendering them
        ;; would multiply every page load by the candidate count — and for
        ;; the AI projection each render is an evaluation.
        reusable-selected?
        (and selected-call
             (= selected (:seon.render.call/producer selected-call)))
        preview
        (when selected
          (if reusable-selected?
            (:seon.render.call/output selected-call)
            (render-source-call
             (assoc request
                    :seon.render/selection-inspection inspection
                    :seon.render.call/selected-producer selected
                    :seon.render.call/id (call-id selected)))))
        entry
        (when selected
          (if reusable-selected?
            (:seon.render.call/entry selected-call)
            (get @(:seon.render/captured-calls request) (call-id selected))))
        previews (if selected {selected preview} {})
        entries (if (and selected entry) {selected entry} {})]
    {:seon.render/selection inspection
     :seon.render/previews previews
     :seon.render/entries entries}))

(defn- debug-experiment-html
  [page]
  [:div {:class "seon-debug-experiment"}
   (hiccup/raw (get page "debug-units"))
   (hiccup/raw (get page "debug-graph"))])

(defn- debug-data-call-id
  [debug-request]
  [::debug-data
   (select-keys debug-request
                [:seon.render.debug/subject
                 :seon.agent/id
                 :seon.render.data/limit
                 :seon.render.data/max-ref-attributes
                 :seon.render.data/max-result-weight
                 :seon.render.data/outgoing-cursor
                 :seon.render.data/incoming-cursor
                 ::pull-max-work])])

(defn- refresh-retained-read-evidence
  [database retained]
  (mapv (fn [previous current]
          (assoc previous :datahike.read/revision
                 (:datahike.read/revision current)))
        retained
        (db/read-evidence
         (mapv (fn [evidence]
                 {:seon.db/db database
                  :seon.db/source-argument-position
                  (:seon.db/source-argument-position evidence)
                  :datahike.read/dependency-plan
                  (:datahike.read/dependency-plan evidence)})
               retained))))

(defn- acquire-debug-data
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.db/database-value :map [:or :nil :map]] :map]}
  [projection database debug-request retained-calls]
  (let [call-id (debug-data-call-id debug-request)
        static-evidence
        {:seon.render.call/producer 'seon.render.data/entity-observation
         :seon.render.call/argument (second call-id)}
        previous (get retained-calls call-id)
        reusable? (and previous
                       (= static-evidence
                          (:seon.render.call/static-evidence previous))
                       (db/read-evidence-current?
                        database
                        (:seon.render.call/read-evidence previous)))
        captured (atom [])
        output
        (if reusable?
          (:seon.render.call/output previous)
          (binding [db/*read-evidence-sink* captured]
            (let [observation-request
                  (carry
                   {:seon.db/db database
                    :seon.render.data/subject
                    (:seon.render.debug/subject debug-request)
                    :seon.render.data/limit
                    (:seon.render.data/limit debug-request)
                    :seon.render.data/max-ref-attributes
                    (:seon.render.data/max-ref-attributes debug-request)
                    :seon.render.data/max-result-weight
                    (:seon.render.data/max-result-weight debug-request)}
                   debug-request
                   [:seon.render.data/outgoing-cursor
                    :seon.render.data/incoming-cursor])
                  first-observation (data/entity-observation
                                     observation-request)
                  restarted? (= :seon.render.data/continuation
                                (:seon.render.data/refused-member first-observation))
                  effective-request (if restarted?
                                      (dissoc debug-request
                                              :seon.render.data/outgoing-cursor
                                              :seon.render.data/incoming-cursor)
                                      debug-request)
                  observation (if restarted?
                                (data/entity-observation
                                 (dissoc observation-request
                                         :seon.render.data/outgoing-cursor
                                         :seon.render.data/incoming-cursor))
                                first-observation)
                  started (System/nanoTime)
                  acquisition
                  (db/pull database
                           {:selector '[*]
                            :eid (:seon.render.debug/subject effective-request)
                            :max-work (::pull-max-work effective-request)})
                  declared-units
                  (declared-entity-units projection database acquisition)
                  reverse-units
                  (into []
                        (comp
                         (map :seon.schema/key)
                         (keep #(mr/schema (:seon.schema.projection/registry projection) %))
                         (map m/properties)
                         (mapcat :seon.render/units)
                         (filter reverse-attribute?)
                         (distinct))
                        (when (map? acquisition)
                          (schema/matching-shapes-in
                           projection (value/transacted acquisition database))))
                  ;; ONE pull per declared reverse relationship, each asking
                  ;; for the connected entities themselves: a relationship
                  ;; too large for the declared work bound refuses as that
                  ;; unit's own typed value instead of poisoning the others
                  ;; or painting a wall of bare entity ids.
                  reverse-values
                  (if (or (:seon.db/invalid-read acquisition) (:seon.schema/expected-value acquisition))
                    {}
                    (into {}
                          (map (fn [unit]
                                 (let [pulled
                                       (db/pull
                                        database
                                        {:selector [{unit '[*]}]
                                         :eid (:seon.render.debug/subject
                                               effective-request)
                                         :max-work
                                         (::pull-max-work effective-request)})]
                                   [unit (if (or (:seon.db/invalid-read pulled) (:seon.schema/expected-value pulled))
                                           pulled
                                           (get pulled unit))])))
                          reverse-units))
                  reverse-values
                  (into {} (remove (comp nil? val)) reverse-values)
                  acquisition (merge acquisition reverse-values)
                  ref-attributes (installed-ref-attributes database)
                  outgoing (get-in observation
                                   [:seon.render.data/outgoing
                                    :seon.render.data/datoms])
                  incoming (get-in observation
                                   [:seon.render.data/incoming
                                    :seon.render.data/datoms])
                  related-eids
                  (into []
                        (comp
                         (distinct))
                        (concat
                         (keep (fn [{:keys [a v]}]
                                 (when (ref-attributes a) v))
                               outgoing)
                         (map :e incoming)))
                  related-values
                  (when (seq related-eids)
                    (db/pull-many
                     database
                     {:selector '[*]
                     :eids related-eids
                      :max-work (::pull-max-work effective-request)}))]
              {::debug-request effective-request
               ::declared-units declared-units
               ::reverse-values reverse-values
               ::observation observation
               ::related-entities
               (if (or (:seon.db/invalid-read related-values) (:seon.schema/expected-value related-values))
                 related-values
                 (zipmap related-eids related-values))
               ::restarted? restarted?
               ::acquisition acquisition
               ::acquisition-ms
               (/ (double (- (System/nanoTime) started)) 1000000.0)})))
        entry (if reusable?
                (update previous :seon.render.call/read-evidence
                        #(refresh-retained-read-evidence database %))
                {:seon.render.call/static-evidence static-evidence
                 :seon.render.call/read-evidence
                 (db/read-evidence @captured)
                 :seon.render.call/basis-transaction (db/basis-t database)
                 :seon.render.call/output output})]
    {::debug-data-output output
     ::debug-data-call-id call-id
     ::debug-data-entry entry}))

(defn- debug-page-result
  "One debug page at `db`: the selected entity's header, its units through the
  viewer's render functions and its reference graph."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.schema/value :map :seon.sci.admit/caps :map :map
                       :map]
                  :map]}
  [db connection debug-request caps profile handle retained-calls]
  (let [projection (sci.kernel/context-projection (:seon.sci.eval/ctx handle))
        {debug-data-output ::debug-data-output
         data-call-id ::debug-data-call-id
         debug-data-entry ::debug-data-entry}
        (acquire-debug-data projection db debug-request retained-calls)
        debug-request (::debug-request debug-data-output)
        observation (::observation debug-data-output)
        acquisition (::acquisition debug-data-output)
        acquisition-ms (::acquisition-ms debug-data-output)
        declared-units (::declared-units debug-data-output)
        reverse-values (::reverse-values debug-data-output)
        captured-calls (atom {data-call-id debug-data-entry})
        render-request
        (when (map? acquisition)
          (carry
           {:seon.db/db db
            :seon.render/namespace
            (:seon.render.debug/viewer-namespace debug-request)
            :seon.render/profile profile
            :seon.render.value/root (:seon.render.debug/subject debug-request)
            :seon.render.data/cursor (data/parse-cursor nil nil)
            :seon.render/value acquisition
            :seon.render/output :seon.render/ai
            :seon.sci.eval/ctx (:seon.sci.eval/ctx handle)
            :seon.sci.admit/caps caps
            :seon.sci.eval/time-limit-ms
            (:seon.config.eval/time-limit-ms handle)
            :seon.turn.loop/cluster handle
            :seon.agent/routing
            (:seon.agent/routing handle)
            :seon.render/retained-calls retained-calls
            :seon.render/captured-calls captured-calls}
           debug-request [:seon.agent/id]))
        units-html
        (if render-request
          (debug-found-values-html
           projection render-request debug-request acquisition declared-units
           observation reverse-values
           (let [related (::related-entities debug-data-output)]
             (if (map? related) related {})))
          [:section {:id "debug-units" :class "seon-debug-found-values"}
           [:h1 {:class "seon-debug-caption"}
            "Attributes of the selected entity"]
           (error/render-html
            (or acquisition
                {:seon.error/at (java.util.Date.)
                 :seon.error/layer :seon.render.web/render
                 :seon.error/operation 'seon.render.web/debug-page-result
                 :seon.error/message "The selected entity does not exist."
                 :seon.error/fix "Supply the expected member and repeat the requested operation."
                 :seon.render.web/refused-member :seon.render/value
                 :seon.error/expected "an existing selected entity"
                 :seon.error/offending debug-request}))])
        program-identity
        (debug-program-identity db (:seon.sci.eval/ctx handle))
        page
        {"debug-inspection-header"
          (debug-header-html db debug-request observation acquisition acquisition-ms
                             program-identity)
          "debug-units" (hiccup/->string units-html)
          "debug-graph"
          (debug-graph-html debug-request (installed-ref-attributes db)
                            observation)}]
    {:seon.render.web/page page
     :seon.render.web/fragments {}
     :seon.render/captured-calls @captured-calls}))

(defn join-package
  "Return the render proc's settled package unchanged.

  A page or feed join consumes retained serialized bytes. It never derives a
  page and never serializes a fragment; identity is therefore part of this
  deliberately tiny boundary."
  {:malli/schema [:=> [:cat :seon.render/package] :seon.render/package]}
  [package]
  package)

(defn- package-bytes
  [frame]
  (alength ^bytes frame))

(def ^:private build-event
  (datastar.common/->build-event-str))

(defn- frame-bytes
  "Build one complete Datastar patch event from retained fragment strings."
  [fragments]
  (.getBytes
   ^String
   (build-event datastar.consts/event-type-patch-elements
                (datastar.elements/->patch-elements-seq
                 (mapv val (sort-by key fragments)) {})
                {})
   StandardCharsets/UTF_8))

(defn- next-package
  "Advance retained package bytes and their current-facts evidence."
  [previous page basis-transaction streaming?]
  (let [keyframe (:seon.render.package/keyframe previous)]
    (if (= keyframe page)
      [(assoc previous
              :seon.render.package/basis-transaction basis-transaction
              :seon.render.package/streaming? streaming?)
       false]
      (let [revision (inc (long (:seon.render.package/revision previous 0)))
            delta (into (sorted-map)
                        (filter (fn [[id html]]
                                  (not= html (get keyframe id))))
                        page)
            keyframe-bytes (frame-bytes page)
            delta-bytes (frame-bytes delta)]
        [{:seon.render.package/revision revision
          :seon.render.package/base-revision (dec revision)
          :seon.render.package/basis-transaction basis-transaction
          :seon.render.package/streaming? streaming?
          :seon.render.package/keyframe page
          :seon.render.package/keyframe-bytes keyframe-bytes
          :seon.render.package/keyframe-size (package-bytes keyframe-bytes)
          :seon.render.package/delta delta
          :seon.render.package/delta-bytes delta-bytes
          :seon.render.package/delta-size (package-bytes delta-bytes)}
         true]))))

(defn- package-patches
  "Select a contiguous smaller delta, otherwise the repair keyframe."
  [delivered-revision package]
  (let [keyframe (:seon.render.package/keyframe-bytes package)
        delta (:seon.render.package/delta-bytes package)]
    (if (and (= delivered-revision
                (:seon.render.package/base-revision package))
             (< (:seon.render.package/delta-size package)
                (:seon.render.package/keyframe-size package)))
      delta
      keyframe)))

;;; ---------------------------------------------------------------------------
;;; The render proc — the cluster graph's second proc (F2 §1)
;;; ---------------------------------------------------------------------------

(def ^:private root-call ::root-acquisition)

(defn- read-attributes
  [call]
  (reduce
   (fn [attributes evidence]
     (let [read-attributes
           (get-in evidence
                   [:datahike.read/revision :datahike.read/attributes])]
       (cond
         (= :all attributes) (reduced :all)
         (= :all read-attributes) (reduced :all)
         :else (into attributes read-attributes))))
   #{}
   (:seon.render.call/read-evidence call)))

(defn- calls-by-attribute
  [calls]
  (reduce-kv
   (fn [index call-id call]
     (let [attributes (read-attributes call)]
       (if (= :all attributes)
         (update index :all (fnil conj #{}) call-id)
         (reduce (fn [index attribute]
                   (update index attribute (fnil conj #{}) call-id))
                 index
                 attributes))))
   {}
   calls))

(defn- calls-interest
  [calls]
  (let [attributes (keys (calls-by-attribute calls))]
    (if (some #{:all} attributes)
      :all
      (into #{} attributes))))

(defn- merge-interest
  [left right]
  (if (or (= :all left) (= :all right))
    :all
    (into (or left #{}) right)))

(defn- retained-interest
  [state]
  (let [cache @(render/shared-cache (get-in state [:seon.turn.loop/cluster :seon.sci.eval/ctx]))
        calls (vals (::calls cache))
        interest (reduce (fn [interest retained]
                           (merge-interest interest
                                           (calls-interest retained)))
                         #{}
                         calls)]
    interest))

(defn- candidate-call-ids
  "The retained calls `database` may no longer satisfy.

  `confirmed?` states that every retained call was already derived or checked
  current at this exact committed value (`render/same-committed-database?`),
  so its read evidence is a function of that value and is not asked again. A
  source preview with no output stays a candidate either way."
  {:malli/schema [:=> [:cat [:map-of :seon.render.call/id :seon.render.call/entry]
                       :seon.db/database-value :boolean]
                  [:set :seon.render.call/id]]}
  [calls database confirmed?]
  (into #{}
        (keep (fn [[call-id call]]
                (when (or (and (:seon.render.call/source call)
                               (nil? (:seon.render.call/output call)))
                          (and (not confirmed?)
                               (not (true? (db/read-evidence-current?
                                            database (:seon.render.call/read-evidence call))))))
                  call-id)))
        calls))

(defn- program-key
  "What the page's code is: the context's acquired program and the adopted
  source commit, never the database value the snapshot last advanced to.

  `acquire!` swaps the newest `:seon.db/db` into the snapshot on every
  same-program acquisition (`src/seon/sci/eval.clj` `acquire!`); keying on it
  discarded every retained call at each acquisition. The remaining members
  change exactly when a program is acquired or a definition installed."
  {:malli/schema [:=> [:cat :map :seon.db/database-value]
                  [:tuple [:or :nil :map]
                   [:or [:vector :seon.source/commit-id] :seon.db/error-result]]]}
  [handle database]
  [(some-> handle :seon.sci.eval/ctx :seon.sci.kernel/program-snapshot deref
           (dissoc :seon.db/db))
   (render/source-generation database)])

(defn- root-call-id
  [projection registration-key]
  [root-call projection registration-key])

(defn- acquire-root
  [request call-id]
  (let [pull-plan
        (render.walk/root-pull-plan request)
        request (assoc request :seon.render.walk/root-pull-plan pull-plan)
        captured (atom [])
        acquisition
        (binding [db/*read-evidence-sink* captured]
          (render.walk/root-acquisition request))]
    [acquisition
     {:seon.render.call/static-evidence
      {:seon.render.call/producer 'seon.render.walk/root-acquisition
       :seon.render.call/argument
       (select-keys request [:seon.render.walk/lookup
                             :seon.render/distance])}
      :seon.render.call/read-evidence
      (or (:seon.render.call/read-evidence acquisition)
          (db/read-evidence @captured))
      :seon.render.call/basis-transaction
      (db/basis-t (:seon.db/db request))
      :seon.render.call/output acquisition
      :seon.render.call/id call-id}]))

(defn- refresh-root
  [request retained call-id candidates]
  (let [previous (get retained call-id)
        previous-acquisition (:seon.render.call/output previous)]
    (if (or (nil? previous) (contains? candidates call-id))
      (let [[acquisition entry]
            (acquire-root
             (cond-> request
               previous-acquisition
               (assoc :seon.render.walk/root-acquisition
                      previous-acquisition))
             call-id)]
        {:acquisition acquisition
         :entry entry
         :changed? (not= (select-keys previous-acquisition [:seon.render.walk/root :seon.render.walk/members :seon.render.walk/order])
                          (select-keys acquisition [:seon.render.walk/root :seon.render.walk/members :seon.render.walk/order]))})
      {:acquisition previous-acquisition
       :entry previous
       :changed? false})))

(defn- publish-interest!
  [state _database]
  (let [interest-reference (:seon.render.web/interest state)
        old-interest @interest-reference
        new-interest (retained-interest state)
        replacement-interest (merge-interest old-interest new-interest)]
    ;; Replacement is a handoff, not an assignment: keep every old route
    ;; visible while the new index is becoming current.
    (reset! interest-reference replacement-interest)
    ;; A commit during the pass observes the union and queues the next wake.
    ;; After the complete replacement, the new index alone owns routing.
    (reset! interest-reference new-interest)
    state))

(defn- coalesce-floor
  "The coalescing floor, read from the config facts at `db` — a live
  dial change applies at the very next pass. 0 when absent."
  [db]
  (or (db/q '[:find ?value .
             :where [_ :seon.config.render/coalesce-ms ?value]]
           db)
      0))

(defn- unsettled-stream?
  "True when a stream entry's run has no settled terminal fact at `db`.

  The provider reply settles as a frozen plan (`:seon.turn/reply-size`) or
  a durable error fact; `:seon.turn/closed-tx` covers a run terminated by
  another path. A missing run is not live. This presence gate makes a
  delayed partial incapable of repainting over its settled facts."
  [db stream]
  (when-let [run-id (:seon.turn/id stream)]
    (let [row (db/pull db [:db/id :seon.turn/reply-size :seon.turn/closed-tx]
                      [:seon.turn/id run-id])]
      (and (some? row)
           (not-any? #(contains? row %)
                     [:seon.turn/reply-size :seon.turn/closed-tx])))))

(def ^:private health-surface
  "The health page's one morph target."
  "seon-health")

(def ^:private no-retained-calls
  "A page result that retains no fragments or calls."
  {:seon.render.web/fragments {}
   :seon.render/captured-calls {}})

(defn- health-page-result
  "The health page at `database`: the cluster's `seon.health/report` through
  its HTML face, as one block. The report waits at most the paint deadline
  for a unit; a pending one completes off this pass and offers the render
  proc a payload-free wake, so the page never blocks a pass."
  {:malli/schema [:=> [:cat :map :seon.db/database-value] :map]}
  [handle database]
  (let [report (health/report {:seon.boot/cluster-name (current-cluster-name handle)
                               :seon.db/db database
                               :seon.health/deadline-ms 50
                               :seon.health/wake #(async/offer! (:seon.render.web/render-channel handle) {})})]
    (assoc no-retained-calls
           :seon.render.web/page
           {health-surface (hiccup/->string [:div {:id health-surface}
                                             (health/render-html {:seon.render/value report :seon.db/db database})])})))

(defn- derive-page
  "One page at `database`: derive the blocks whose retained calls are
  candidates, or return the retained calls alone."
  {:malli/schema [:=> [:cat :map :seon.db/database-value :map :map
                       [:or :seon.agent/id [:tuple [:enum ::debug-tab ::health-tab] :map]] :map :boolean :boolean :boolean]
                  :map]}
  [handle database streams profile registration-key retained-values derive-all? invalidate-calls? confirmed?]
  (let [connection (:seon.db/connection handle)
        caps (:seon.sci.admit/caps handle)
        debug? (debug-key? registration-key)
        debug-request (when debug? (second registration-key))
        agent-id (key-agent-id registration-key)]
    (cond
      (= [::health-tab {}] registration-key)
      (health-page-result handle database)
      debug?
      (let [retained (get-in retained-values [::calls registration-key] {})
            candidates (candidate-call-ids retained database confirmed?)
            package (get-in retained-values [::packages registration-key])]
        ;; A join marker shares the newest-only interest channel with database
        ;; and evaluation wakes. A missing exact package is the durable level
        ;; condition. For a retained package, the same read-evidence mechanism
        ;; as every render call decides whether to derive; an unchanged page
        ;; only advances its observed database basis below.
        (if (or invalidate-calls? (nil? (get-in retained-values [::page-results registration-key])) (seq candidates))
          (debug-page-result database connection debug-request caps profile
                             handle
                             retained)
          {::retained-only? true
           ::advance-basis? (not= (db/basis-t database)
                                  (:seon.render.package/basis-transaction
                                   package))
           :seon.render/captured-calls retained}))
      :else
      (let [retained (get-in retained-values [::calls registration-key] {})
            candidates (candidate-call-ids retained database confirmed?)
            call-id (root-call-id :seon.render/html registration-key)
            request
            (carry {:seon.db/db database
                     :seon.agent/id agent-id
                     :seon.render.web/root-agent-id
                     (:seon.render.web/root-agent-id handle)
                     :seon.sci.admit/caps caps
                     :seon.sci.eval/ctx (:seon.sci.eval/ctx handle)
                     :seon.config.eval/time-limit-ms
                     (:seon.config.eval/time-limit-ms handle)
                     :seon.render/profile profile
                     :seon.db/connection connection
                     :seon.render.web/retained-fragments
                     (get-in retained-values [::fragments registration-key] {})
                     :seon.render/retained-calls retained
                     :seon.render/candidate-call-ids candidates
                     :seon.render.walk/lookup
                     [:seon.agent/id agent-id]
                     :seon.render/distance 2}
                    (assoc handle :seon.ai/partial
                           (get-in streams [agent-id :seon.ai/partial]))
                    [:seon.ai/partial :seon.agent/routing])
            root (refresh-root request retained call-id candidates)
            derive? (or derive-all?
                        (not= [(get-in streams [agent-id :seon.ai/partial])]
                              (get-in retained-values [::fragments registration-key
                                                       stream-strip-id :seon.render.fragment/evidence]))
                        (:changed? root)
                        (seq (disj candidates call-id)))]
        (if derive?
          (let [result (page-result
                        (assoc request
                               :seon.render.walk/root-acquisition
                               (:acquisition root)))
                calls (assoc (:seon.render/captured-calls result)
                             call-id (:entry root))]
            (assoc result :seon.render/captured-calls calls))
          (let [calls (assoc retained call-id (:entry root))]
            {::retained-only? true
             :seon.render/captured-calls calls}))))))

(defn- newer-page?
  "True when the retained page was derived at a later value of this same
  branch. Every fork of a cluster context shares one render cache, so a basis
  from another branch's connection orders nothing here."
  {:malli/schema [:=> [:cat [:or :nil :seon.db/database-value] :seon.db/database-value]
                  :boolean]}
  [retained database]
  (let [lineage #(:datahike.value/connection-id (db/committed-value-identity %))]
    (boolean
     (and retained
          (= (lineage retained) (lineage database))
          (> (long (db/basis-t retained)) (long (db/basis-t database)))))))

(defn- agent-connection
  "The connection an agent's page renders through: the branch the agent runs on, where its evaluations, test runs
  and task rows are written, since a render reads the database from its bound connection. A running agent's own
  (`seon.cluster.agent/started-custody`), else this process's open connection to the branch `database` names for
  it (`seon.db/branch-connection`); nil when neither is open."
  {:malli/schema [:=> [:cat [:maybe :seon.agent/routing] :seon.db/database-value :seon.agent/id]
                  [:maybe :seon.db/connection]]}
  [routing database agent-id]
  (or (some-> routing (cluster.agent/started-custody agent-id) (get-in [:seon.turn.loop/cluster :seon.db/connection]))
      (when-let [branch (db/q '[:find ?branch . :in $ ?id
                                :where [?agent :seon.agent/id ?id] [?agent :seon.agent/branch ?branch]]
                              database agent-id)]
        (db/branch-connection database branch))))

(defn- agent-database
  "The database an agent's page renders: its branch's (`agent-connection`), else `database`."
  {:malli/schema [:=> [:cat [:maybe :seon.agent/routing] :seon.db/database-value :seon.agent/id]
                  :seon.db/database-value]}
  [routing database agent-id]
  (or (some-> (agent-connection routing database agent-id) db/db) database))

(defn- derive-page!
  "Derive on this caller, sharing immutable call evidence with every reader.

  Retained calls are keyed on the program (`program-key`) and confirmed by the
  committed value they were last derived or checked at: an unchanged head asks
  no read evidence and derives no block."
  {:malli/schema [:=> [:cat :map :seon.db/database-value :map :map
                       [:or :seon.agent/id [:tuple [:enum ::debug-tab ::health-tab] :map]] :boolean :boolean]
                  :map]}
  [handle database streams profile registration-key derive-all? invalidate-calls?]
  (let [;; an agent's page reads its own world: the branch it runs on, through that branch's connection
        connection (when (string? registration-key)
                     (agent-connection (:seon.agent/routing handle) database registration-key))
        database (if connection (db/db connection) database)
        handle (cond-> handle connection (assoc :seon.db/connection connection))
        cache (render/shared-cache (:seon.sci.eval/ctx handle))
        retained @cache
        program (program-key handle database)
        changed-code? (not= program (get-in retained [::programs registration-key]))
        confirmed? (and (not changed-code?)
                        (some? (get-in retained [::page-databases registration-key]))
                        (render/same-committed-database?
                         (get-in retained [::page-databases registration-key]) database))
        retained (if changed-code?
                   (-> retained
                       (update ::calls dissoc registration-key)
                       (update ::fragments dissoc registration-key))
                   retained)
        result (derive-page handle database streams profile registration-key retained
                            (or derive-all? changed-code?)
                            (or invalidate-calls? changed-code?)
                            confirmed?)
        result (if (::retained-only? result)
                 (merge (get-in retained [::page-results registration-key]) result)
                 result)]
    (swap! cache
           (fn [current]
             (if (newer-page? (get-in current [::page-databases registration-key]) database)
               current
               (-> current
                   (assoc-in [::programs registration-key] program)
                   (assoc-in [::page-databases registration-key] database)
                   (assoc-in [::page-results registration-key] result)
                   (assoc-in [::calls registration-key] (:seon.render/captured-calls result))
                   (assoc-in [::fragments registration-key] (:seon.render.web/fragments result))))))
    result))

(defn- current-page
  "GET and first SSE paint derive on their caller from one database value."
  [service database registration-key]
  (let [projection (sci.kernel/context-projection (:seon.sci.eval/ctx service))
        connection (:seon.store/connection-object service)
        handle (merge service (:seon.turn.loop/cluster service)
                      {:seon.db/connection connection
                       :seon.render.web/root-agent-id
                       (:seon.render.web/root-agent-id service
                                                      (:seon.agent/id service))})]
    (schema/call-with-projection
     projection
     (fn []
       (let [effective (cluster-effective! service database)
             profile (render/agent-render-profile effective)]
         (:seon.render.web/page
          (derive-page! handle database {} profile registration-key false false)))))))

(defn- watched-registration-keys
  [registration]
  (sort print/compare-values
        (keep (fn [[registration-key tabs]]
                (when (pos? (long tabs)) registration-key))
              @registration)))

(defn- invalidate-runtime-derived-state
  "Drop code-derived values a runtime evaluation could no longer refresh.

  Active packages remain as revision predecessors for their immediate repaint.
  A closed page has no consumer to refresh during that pass, so retaining its
  package would let a later reopen accept old code solely because the database
  basis is unchanged. Calls, fragments, and generated agent context are also
  code-derived and must be derived again after an evaluation.

  THE ONE CACHE IS NOT DROPPED HERE. An invocation entry already states the
  code it ran under — the program snapshot and the projection it was rendered
  with, compared by identity — and the reads it depended on. An evaluation
  that installed a definition replaces the snapshot, so every entry misses on
  its own evidence; an evaluation that installed nothing leaves entries that
  are genuinely still current. Emptying the store instead re-evaluated every
  page preview after any turn, which is what the second hand-written preview
  predicate existed to paper over."
  [state]
  (let [watched (watched-registration-keys
                 (:seon.render.web/registration state))
        cache (render/shared-cache
               (get-in state [:seon.turn.loop/cluster :seon.sci.eval/ctx]))]
    (swap! cache
           (fn [retained]
             (-> retained
                 (update ::packages select-keys watched)
                 (dissoc ::page-results ::calls ::fragments ::page-databases))))
    state))

(defn- entrance-fault!
  "Record one core fault from a web entrance whose thread is its own execution
  owner (a feed writer, one HTTP request): there is no graph to stop, and the
  entrance ends with its declared response. An unrecorded fault's throw
  carrying its receipt is therefore disposed here; any other throw propagates."
  {:malli/schema [:=> [:cat :seon.env/environment :seon.error/throwable :qualified-keyword :qualified-symbol]
                  [:or :nil :seon.fault/recorded]]}
  [environment failure layer operation]
  (try (fault/fault! environment failure
                     {:seon.error/layer layer :seon.error/operation operation
                      :seon.db.process/id (process/id (process/current-identity))})
       (catch clojure.lang.ExceptionInfo unrecorded
         (when-not (fault/carried-receipt unrecorded) (throw unrecorded)))))

(defn- failed-page-result
  "One page whose derivation threw: a committed fault and a visible section.

  A Throwable escaping one page used to end the cluster's render proc, after
  which every page returned its shell and no feed ever painted (the silent
  wedge filed as a blocker on 2026-09-07). The fault still rides the one
  committer inbox; the page shows the flat diagnostic where its content would
  have been; and the proc goes on to the next page. The fault is recorded on
  the render proc's own thread through `seon.fault/fault!`, which delivers it;
  a fault it cannot store throws to `var-process`, which stops the graph.

  ONE FAULT PER FAILURE, NOT ONE PER PASS. A page that throws every pass used
  to commit an identical fault every pass — six in ninety seconds, observed
  live on 2026-09-07 — which is the overloaded-queue shape, not information.
  `last-signature` is what this proc offered for THIS page on the previous
  pass, carried in the proc's own state map; an unchanged signature offers
  nothing. The returned `::fault-signature` is what the pass folds back."
  ([state registration-key failure last-signature]
   (let [debug? (debug-key? registration-key)
         message (str "Deriving this page threw " (.getName (class failure))
                      ": " (.getMessage ^Throwable failure))
         signature [registration-key message]
         diagnostic
         (debug-diagnostic
          message
          'seon.render.web/render-pass
          :seon.render.web/page :seon.render.web/page
          registration-key ::page-derivation-failed
          {:seon.render.web/page-exception-class (symbol (.getName (class failure)))})]
     (when (not= signature last-signature)
       (fault/fault! (env/of state) (ex-info "A page derivation threw." diagnostic failure)
                     {:seon.error/layer :seon.render.web/page
                      :seon.error/operation 'seon.render.web/render-pass
                      :seon.db.process/id (process/id (process/current-identity))}))
     (assoc no-retained-calls
            :seon.render.web/page
            {(if debug? "debug-units" (str "seon-page-" registration-key))
             (hiccup/->string
              [:section {:class "seon-debug-body"}
               [:h2 {:class "seon-debug-caption"} "This page could not be derived"]
               (error/render-html diagnostic)])}
            ::fault-signature signature))))

(defn- unreportable-page-result
  "The floor when building a failed page's own diagnostic also threw.

  `failed-page-result` derefs routing, renders Hiccup and builds a
  diagnostic — every one of those can throw, and it used to sit OUTSIDE the
  pass's protection, so a throw there ended the proc exactly as the failure it
  was reporting would have. This floor allocates nothing but strings, so the
  proc always has a result to carry on to the next page."
  [registration-key failure diagnostic-failure]
  (log/warn (str "seon.render.web: a page derivation threw "
                 (.getName (class ^Throwable failure))
                 " and reporting it threw "
                 (.getName (class ^Throwable diagnostic-failure))
                 " — page of agent " (key-agent-id registration-key)))
  (assoc no-retained-calls
         :seon.render.web/page
         {(str "seon-page-" registration-key)
          (str "<section class=\"seon-debug-body\">"
               "<h2 class=\"seon-debug-caption\">"
               "This page could not be derived, and the failure could not be"
               " reported</h2></section>")}))

(defn- render-pass
  "Derive every registered page and retain its serialized package.

  Every emitted value is the complete latest-package map, so channel
  displacement loses nothing. A package carries both its predecessor delta
  and a repair keyframe assembled from bytes already serialized by this proc."
  ([state]
   (render-pass state
                (db/db (-> state :seon.turn.loop/cluster :seon.db/connection))
                true false))
  ([state database derive-all?]
   (render-pass state database derive-all? false))
  ([state database derive-all? invalidate-calls?]
  (schema/call-with-projection
   (sci.kernel/context-projection
    (get-in state [:seon.turn.loop/cluster :seon.sci.eval/ctx]))
   (fn []
  (let [handle (:seon.turn.loop/cluster state)
        cache (render/shared-cache (:seon.sci.eval/ctx handle))
        state (merge state (select-keys @cache [::calls ::packages]))
        registration (:seon.render.web/registration state)
        watched (watched-registration-keys registration)
        profile (or (::profile state)
                    (when (seq watched)
                      (render/agent-render-profile
                       (config/effective database
                                         (:seon.cluster/name handle)))))
        ;; The run id makes each partial self-describing. Keep only
        ;; entries whose run is still unsettled at THIS immutable
        ;; database value; terminal facts supersede and remove them.
        streams (into {}
                      (filter (fn [[_agent-id stream]]
                                (unsettled-stream? database stream)))
                      (::streams state))
        ;; THE WHOLE FAILURE PATH IS INSIDE THE PROTECTION. Building a failed
        ;; page's diagnostic derefs routing and renders Hiccup, so it can
        ;; throw too; when it does, the string-only floor keeps the proc alive
        ;; instead of ending it with the failure it was reporting.
        previous-signatures (::fault-signatures state)
        [results pass-signatures]
        (reduce
         (fn [[results signatures] registration-key]
           (let [result (try
                          (derive-page! (merge state handle) database streams profile registration-key
                                        derive-all? invalidate-calls?)
                          (catch Throwable failure
                            (try
                              (failed-page-result
                               state registration-key failure
                               (get previous-signatures registration-key))
                              (catch Throwable diagnostic-failure
                                (unreportable-page-result
                                 registration-key failure
                                 diagnostic-failure)))))]
             [(assoc results registration-key result)
              (if-let [signature (::fault-signature result)]
                (assoc signatures registration-key signature)
                signatures)]))
         [{} {}]
         watched)
        advanced
        (reduce-kv
         (fn [{latest :packages changed? :changed?}
              registration-key result]
           (let [debug? (debug-key? registration-key)
                 [package package-changed?]
                 (next-package
                  (get latest registration-key)
                  (:seon.render.web/page result)
                  (db/basis-t database)
                  (boolean (and (not debug?) (get streams (key-agent-id registration-key)))))]
             {:packages (assoc latest registration-key package)
              :changed? (or changed? package-changed?)}))
         {:packages (::packages state) :changed? false}
         results)
        packages
        (reduce-kv
         (fn [packages registration-key result]
           (if (::advance-basis? result)
             (assoc-in packages
                       [registration-key
                        :seon.render.package/basis-transaction]
                       (db/basis-t database))
             packages))
         (:packages advanced)
         results)
        changed? (:changed? advanced)
        _ (swap! cache assoc ::packages packages)
        _ (when (or invalidate-calls?
                    (not= packages (::packages state)))
            (reset! (:seon.render.web/latest-packages state) packages))
        state (-> state
                  (update ::passes inc)
                  (assoc ::watched (count watched)
                         ::streams streams
                         ::profile profile))]
    [(assoc (dissoc state ::packages ::fragments ::calls
                           ::ai-entries ::page-results ::page-databases
                           ::context-bases ::context-programs ::programs)
            ;; DERIVED, NOT ACCUMULATED: only the pages that failed THIS pass
            ;; carry a signature, so a page that starts rendering again drops
            ;; out and its next failure is offered afresh.
            ::fault-signatures pass-signatures)
     (when changed? packages)])))))

(defn render-step
  "The render proc's transform, in Flow's four arities (F2 §1.1).

  Three in-ports: `::interest`, `::runtime-eval`, and `::stream` use
  `(sliding-buffer 1)`. The proc only publishes deltas for open tabs. `::interest` is the database render wake, a payload-free
  \"look\"; `::runtime-eval` announces that evaluated code may have changed;
  `::stream` is the cluster's one
  stream conn carrying `{agent-id + run-id + :seon.ai/partial snapshot}`
  entries. There is NO clear entry: the frozen-plan/error/close fact is
  the stream terminal. One out-port, `::pages`, feeding the mult;
  sliding-1 everywhere means the proc never parks.

  THE COALESCE FLOOR is honored HERE: a wake arriving inside the floor
  waits out the remainder before the next derivation (this proc is
  `:io`; the wait coalesces further wakes on the sliding-1 in-ports),
  so a burst of commits costs one derivation for the whole cluster and
  the per-tab writer just writes. The one surviving render clock, and
  it remains a coalescing floor over an observed event — the commit,
  delivered by the routing listener — never a poll.

  All state is disposable by the transport law: the produced-memory is
  rebuilt by one re-render after any restart, and a partial is admitted
  only while its run lacks a terminal fact. An interest pass is a
  repaint from facts, so it drops every cached partial before deriving;
  reconnect can never restore one."
  {:malli/schema [:function [:=> [:cat] [:map]] [:=> [:cat :map] :map] [:=> [:cat :map :keyword] :map] [:=> [:cat :map :keyword [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The value renderer and its projections operate on arbitrary Clojure results, including scalar and nil results; the render profile owns presentation bounds.", :gen/elements [nil false 0 "" :k [] {}]}]] [:tuple :map [:or :nil :map]]]]}
  ([]
   {:ins {}
    :outs {}
    :workload :io
    :ping-map-fn (fn [state]
                   {::passes (::passes state 0)
                    ::watched-agents (::watched state 0)
                    ::tap-count (transduce (map long) + 0
                                           (vals @(:seon.render.web/registration
                                                   state)))
                    ::streaming-agents (count (::streams state))})})
  ([args]
   ;; THE PORTS ARE A DEPENDENCY, SO SAY SO. A nil in-port does not
   ;; fail: Flow leaves the proc :running with an unreadable port, it
   ;; takes nothing forever, and — measured — its stop transition never
   ;; runs either, so `stop-flows!` waits on a completion that can
   ;; never arrive. A silent wedge that turns into a hang at shutdown
   ;; is exactly the class the readiness rule exists to kill, so the
   ;; proc refuses to be built without the channels it reads.
   (let [ports {::interest (:seon.render.web/render-channel args)
                ::runtime-eval
                (:seon.render.web/runtime-eval-channel args)
                ::stream (:seon.turn.loop/stream-channel
                          (:seon.turn.loop/cluster args))
                ::pages (:seon.render.web/pages-channel args)
                ::latest-packages (:seon.render.web/latest-packages args)
                ::render-interest (:seon.render.web/interest args)}]
     (when-let [missing (seq (sort (keep (fn [[port channel]]
                                           (when-not channel port))
                                         ports)))]
       (throw (ex-info "the render proc is missing an in/out port"
                       {:seon.error/at (java.util.Date.)
                        :seon.error/layer :seon.render.web/render
                        :seon.error/operation 'seon.render.web/render-step
                        :seon.error/message "The requested render observation is unavailable."
                        :seon.error/fix "Supply the expected member and repeat the requested operation."
                        :seon.render.web/missing-port (first missing)
                        ::missing (vec missing)
                        :seon.error/member :seon.render.web/render-channel
                        :seon.error/expected "all declared render ports"
                        :seon.error/data {:seon.render.web/missing (vec missing)}})))
     (assoc args
            ::flow/in-ports (select-keys ports [::interest ::runtime-eval ::stream])
            ::flow/out-ports (select-keys ports [::pages])
            ::streams {}
            ::passes 0
            ::watched 0
            ::coalesce-ms 0
            ::last-pass-nanos 0)))
  ([state transition]
   (when (= ::flow/stop transition)
     ;; its OWN completion, not the cluster handle's: disarm must join
     ;; BOTH cluster-graph procs' active transforms before the branch
     ;; connection is released
     (async/put! (:seon.render.web/completion state) ::stopped))
   state)
  ([state input message]
     (let [runtime-eval? (= ::runtime-eval input)
           settlement
         (when (and (= ::interest input) (map? message))
           (::settlement message))
         join? (and (= ::interest input) (map? message) (::join message))
         state (case input
                 ;; A repaint is facts only. Partials were never facts,
                 ;; so a commit wake or reconnect wake cannot restore
                 ;; process-local stream memory.
                 ::interest (assoc state ::streams {})
                 ::stream
                 (assoc-in state
                           [::streams (:seon.agent/id message)]
                           message)
                 ::runtime-eval (invalidate-runtime-derived-state state)
                 state)
         connection (:seon.db/connection
                     (:seon.turn.loop/cluster state))
         floor (::coalesce-ms state 0)
         elapsed-ms (quot (- (System/nanoTime)
                             (long (::last-pass-nanos state)))
                          1000000)
         remainder (- floor elapsed-ms)]
     (when (pos? remainder)
       ;; the floor: wait out the remainder BEFORE deriving, so the
       ;; sliding-1 in-ports coalesce the burst and one derivation
       ;; serves it whole
       (Thread/sleep (long remainder)))
     (let [database (db/db connection)
           derive-all? (or join? settlement runtime-eval? (= ::stream input))
           ;; Evaluated code can change a selected render function or any
           ;; helper below it without changing database facts. Reuse the one
           ;; render pass, but refuse every retained call on this distinct
           ;; signal so a database wake cannot overwrite code invalidation.
           [state packages] (render-pass state database derive-all?
                                         runtime-eval?)
           state (-> state
                     (assoc ::coalesce-ms (coalesce-floor database))
                     (publish-interest! database))
           published (if join? @(:seon.render.web/latest-packages state) packages)]
       ;; A settlement request rides the same sliding-1 in-port as the
       ;; wakes it fences. Its reply is the pass count produced by this
       ;; exact derivation, so a proof need not sample Flow's published
       ;; state and accidentally count an earlier pass that finished
       ;; after the sample.
       (when settlement
         (async/put! settlement (::passes state)))
       [(assoc state ::last-pass-nanos (System/nanoTime))
        (when (seq published) {::pages [published]})]))))

;;; ---------------------------------------------------------------------------
;;; The feed — per tab: a tap and a virtual thread (F2 §1.3)
;;; ---------------------------------------------------------------------------

(defn- register-tab!
  "Count one open tab at its page registration key."
  [registration registration-key]
  (swap! registration update registration-key (fnil inc 0)))

(defn- deregister-tab!
  "Drop one open tab; the last tab out removes its page key entirely."
  [registration registration-key]
  (swap! registration
         (fn [watched]
           (let [remaining (dec (long (get watched registration-key 1)))]
             (if (pos? remaining)
               (assoc watched registration-key remaining)
               (dissoc watched registration-key))))))

(def ^:private socket-leaf
  "The socket leaf a page's delivery and binding go through: http-kit's
  send, write state and server binding, and Datastar's SSE close. A service
  names another as :seon.render.web/http, member by member; a test hands its
  own values instead of redefining these Vars."
  {::send! http/send!
   ::write-state http/write-state
   ::close-sse! datastar/close-sse!
   ::run-server http/run-server})

(defn- socket
  "The service's socket leaf: its :seon.render.web/http members over the defaults."
  {:malli/schema [:=> [:cat :map] :seon.render.web/http]}
  [service]
  (merge socket-leaf (::http service)))

(defn- write-package!
  "Write one already-framed package event, then park for queue drain.

  `send!` retains its established meaning: `false` says the channel was
  already closed, so that inherited seam still closes the generator.
  After every accepted Datastar event, read http-kit's atomic write
  state. When bytes are pending, park this connection-owned virtual
  thread on that exact drain-or-close completion before the next event.

  While parked, the per-tab `(sliding-buffer 1)` retains only the newest
  complete package. Queue drain is permission for another write, never
  remote-delivery acknowledgement."
  {:seon.fn/external-sink :html-response
   :seon.fn/projection-boundary :seon.render/html
   :malli/schema
   [:=> [:cat :seon.render.web/http :seon.schema/value :seon.schema/value :seon.blob/octet-array
         :seon.config.eval/time-limit-ms :seon.schema/value]
    [:or :boolean :seon.render.web/request-error]]}
  [{send! ::send! write-state ::write-state close-sse! ::close-sse!}
   channel generator frame backstop-ms member]
  (if (send! channel frame false)
    (let [{pending-bytes :http-kit.write/pending-bytes
           drained :http-kit.write/drained}
          (write-state channel)]
      (if (pos? pending-bytes)
        (let [result
              (await/await!
               {:seon.await/bound
                {:seon.await/config-attribute
                 :seon.config.eval/time-limit-ms
                 :seon.await/config-value backstop-ms}
                :seon.await/diagnostic
                {:seon.error/at (java.util.Date.)
                 :seon.error/layer :seon.render.web/delivery
                 :seon.error/operation 'seon.render.web/write-package!
                 :seon.error/expected ::drained-or-closed
                 :seon.error/offending ::pending
                 :seon.await/subject {:http-kit.write/pending-bytes pending-bytes}
             :seon.error/member member}
                :seon.await/future drained})]
          (if (or (:seon.await/elapsed-ms result) (:seon.await/closed-operation result))
            (do
              (close-sse! generator)
              (assoc result
                     :seon.error/at (java.util.Date.)
                     :seon.render.web/refused-member :seon.await/future
                     :seon.error/fix "Observe the socket drain or closure before writing another package."))
            true))
        true))
    (do
      (close-sse! generator)
      false)))

(defn- await-feed-package!
  "Wait for this page's next package under the carried delivery backstop."
  {:malli/schema
   [:=> [:cat :seon.flow/channel :seon.schema/value :seon.config.eval/time-limit-ms]
    [:or :map :seon.render.web/request-error]]}
  [tap registration-key backstop-ms]
  (let [result
        (await/await!
         {:seon.await/bound
          {:seon.await/config-attribute :seon.config.eval/time-limit-ms
           :seon.await/config-value backstop-ms}
          :seon.await/diagnostic
          {:seon.error/at (java.util.Date.)
           :seon.error/layer :seon.render.web/delivery
           :seon.error/operation 'seon.render.web/await-feed-package!
           :seon.error/expected ::registered-package
           :seon.error/offending ::pending
           :seon.error/member registration-key}
          :seon.await/port-operations [tap]
          :seon.await/accept? #(get % registration-key)})]

    (if (or (:seon.await/elapsed-ms result) (:seon.await/closed-operation result))
      (assoc result
             :seon.error/at (java.util.Date.)
             :seon.render.web/refused-member :seon.await/port-operations
             :seon.error/fix "Observe a complete package for this registered page before delivery.")
      result)))

(defn feed
  "Paint current facts on the connection's virtual thread, then consume deltas.

  Tap before capturing the database so a concurrent write cannot be missed.
  The first proc package is a keyframe because the direct paint has no proc
  revision. Later contiguous packages may use deltas; gaps use keyframes.
  A missing package reaches the client as a typed signal before close."
  {:malli/schema [:=> [:cat :map :seon.render.web/feed-request] :map]}
  [request {:keys [:seon.agent/id]
            connection :seon.store/connection-object
            pages-mult :seon.render.web/pages-mult
            registration :seon.render.web/registration
            render-channel :seon.render.web/render-channel
            backstop-ms :seon.config.eval/time-limit-ms
            :as service}]
  (let [query (query-params request)
        debug? (= "true" (get query "debug"))
        health? (= "true" (get query "health"))
        viewer-namespace (when debug?
                           (or (some-> (get query "viewer") route-namespace)
                               (agent-namespace (db/db connection) id)))
        registration-key
        (cond
          health? [::health-tab {}]
          debug?
          [::debug-tab
           (debug-query query [:seon.agent/id id] viewer-namespace
                        (when (agent-namespace (db/db connection) id) id))]
          :else id)
        channel (:async-channel request)
        tap (async/chan (async/sliding-buffer 1))
        tab-id (str (random-uuid))
        leaf (socket service)
        painting (volatile! true)]
    (datastar.http-kit/->sse-response
     request
     {datastar.http-kit/on-open
      (fn [generator]
        (register-tab! registration registration-key)
        (async/tap pages-mult tap)
        (.start
         (Thread/ofVirtual)
         (fn []
           (try
             (let [database (db/db connection)
                   opening-basis (long (db/basis-t database))
                   page (current-page service database registration-key)
                   written (write-package!
                            leaf channel generator (frame-bytes page) backstop-ms
                            {:seon.render.web/tab-id tab-id
                             :seon.render.web/page registration-key})]
               (when (:seon.render.web/refused-member written)
                 (throw (ex-info (:seon.error/message written) written)))
               (when written
                 (async/offer! render-channel {::join true})
                 (loop [delivered-revision -1]
                   (when @painting
                     (let [packages (await-feed-package! tap registration-key backstop-ms)]
                       (when (:seon.render.web/refused-member packages)
                         (when @painting
                           (datastar/patch-signals!
                            generator
                            (json/write-str
                             {"seonFeedError"
                              {"seon.error/operation" (str (:seon.error/operation packages))
                               "seon.error/layer" (str (:seon.error/layer packages))
                               "seon.error/message" (:seon.error/message packages)}}
                             :escape-slash false)))
                         (throw (ex-info (:seon.error/message packages) packages)))
                       (let [package (get packages registration-key)
                             revision (:seon.render.package/revision package)]
                         (if (or (< (long (:seon.render.package/basis-transaction package))
                                    opening-basis)
                                 (<= (long revision) delivered-revision))
                           (recur delivered-revision)
                           (let [written
                                 (write-package!
                                  leaf channel generator (package-patches delivered-revision package)
                                  backstop-ms
                                  {:seon.render.web/tab-id tab-id
                                   :seon.render.web/page registration-key
                                   :seon.render.package/revision revision})]
                             (when (:seon.render.web/refused-member written)
                               (throw (ex-info (:seon.error/message written) written)))
                             (when written (recur revision))))))))))
             (catch Throwable failure
               ;; a closed tab ends painting first: that close is declared,
               ;; every other writer failure is a delivered core fault
               (when @painting
                 (entrance-fault! (env/of (:seon.sci.eval/ctx service))
                                  (ex-info "The browser feed writer failed."
                                           {:seon.render.web/tab-id tab-id
                                            :seon.render.web/page registration-key
                                            :seon.render/output :seon.render/html}
                                           failure)
                                  :seon.render.web/feed 'seon.render.web/feed-response)))
             (finally
               ((::close-sse! leaf) generator)
               ;; the tab leaves the registration when its writer EXITS, so
               ;; the registration counts live feed threads and `stop!`
               ;; joins them by awaiting it empty
               (deregister-tab! registration registration-key))))))
      datastar.http-kit/on-close
      (fn [_generator _status]
        ;; ends painting: the writer's next await sees its tap closed
        (vreset! painting false)
        (async/untap pages-mult tap)
        (async/close! tap))})))

;;; ---------------------------------------------------------------------------
;;; Routes
;;; ---------------------------------------------------------------------------

(def ^:private content-types
  {"css" "text/css" "js" "text/javascript" "woff2" "font/woff2"
   "svg" "image/svg+xml" "png" "image/png" "ico" "image/x-icon"})

(defn- resource
  "A file under `resources/public`, served from the CLASSPATH.
  Path traversal is refused by construction rather than by sanitising:
  the path must match a conservative pattern, so `..` never reaches
  `io/resource` at all."
  [path]
  (when (re-matches #"[A-Za-z0-9._/-]+" path)
    (when-not (str/includes? path "..")
      (when-let [found (io/resource (str "public/" path))]
        {:status 200
         :headers {"content-type"
                   (get content-types
                        (last (str/split path #"\."))
                        "application/octet-stream")}
         :body (io/input-stream found)}))))

(def ^:private loopback-hosts
  #{"127.0.0.1" "localhost" "::1"})

(defn- same-origin?
  "True when a browser POST is same-origin, or supplies no Origin."
  [request]
  (let [headers (:headers request)
        origin (get headers "origin")]
    (boolean
     (or (str/blank? origin)
         (try
           (let [uri (URI. origin)
                 origin-host (.getHost uri)
                 origin-authority (.getAuthority uri)
                 request-host (get headers "host")
                 request-scheme (some-> (:scheme request) name)]
             (and (= (.getScheme uri) request-scheme)
                  (or (and request-host (= origin-authority request-host))
                 (and (nil? request-host)
                      (contains? loopback-hosts origin-host)))))
           ;; A malformed Origin header is a cross-origin refusal; any other
           ;; failure propagates.
           (catch java.net.URISyntaxException _
             false))))))

(defn- decode-form
  "One URL-encoded form body as string keys and values."
  [request]
  (let [body (:body request)
        encoded (cond
                  (nil? body) ""
                  (string? body) body
                  :else (slurp body))]
    (form-pairs encoded)))

(defn- text-response
  "A plain-text Ring response."
  [status body]
  {:status status :headers {"content-type" "text/plain; charset=utf-8"} :body body})

(defn- html-response
  "A 200 HTML Ring response; a Datastar fragment reply adds its `datastar-mode`."
  [body & [headers]]
  {:status 200 :headers (into {"content-type" "text/html; charset=utf-8"} headers) :body body})

(defn- no-content [] {:status 204 :headers {} :body nil})

(defn- not-found
  [_request]
  (text-response 404 "not found"))

(defn- agent-exists?
  [db agent-id]
  (some?
   (db/q '[:find ?agent .
          :in $ ?agent-id
          :where [?agent :seon.agent/id ?agent-id]]
        db agent-id)))

(defn- inbound-tx-meta
  [db process user-id]
  (cond-> {:seon.db/process [:seon.db.process/id process]}
    (agent-exists? db user-id)
    (assoc :seon.db/user [:seon.agent/id user-id])))

(defn inbound
  "Commit one admitted inbound message and return its Ring response.

  The response never paints. A successful POST is 204 with no body;
  the existing commit → route → render path paints the message and
  wakes the recipient. Refusals are 422 text values and commit nothing."
  {:malli/schema [:=> [:cat :seon.render.web/service :seon.render.web/inbound] [:map [:status :int] [:headers [:map-of :string :string]] [:body [:maybe :string]]]]}
  [{connection :seon.store/connection-object
    :keys [:seon.agent/id]
    caps :seon.sci.admit/caps
    process :seon.db.process/id
    write! :seon.db/writer
    :or {write! db/transact!}}
   inbound]
  (let [request
        {:seon.agent/id (:seon.agent/id inbound)
         :seon.message/inbound-content
         (or (:seon.message/inbound-content inbound) "")
         :seon.config.eval.result/max-string
         (:seon.config.eval.result/max-string caps)}
        decision (message/inbound-tx (db/db connection) request)]
    (if (vector? decision)
      (let [result
            (write!
             connection
             {:tx-data [[:db.fn/call #'message/inbound-tx request]]
              :tx-meta (inbound-tx-meta (db/db connection) process id)})]
        ;; `db/transact!` answers a report (it has `:db-after`) or its
        ;; declared refusal; nothing else is success.
        (cond
          (:db-after result)
          (no-content)

          (get-in result [:seon.db.write/attempt :seon.db.write.attempt/completion-unavailable])
          (text-response 500 (:seon.error/message result))

          :else
          (text-response 422 (:seon.error/message result))))
      (text-response 422 (:seon.error/message decision)))))

(defn- query-entity
  {:malli/schema [:=> [:cat [:maybe :string]] :seon.schema/value]}
  [encoded]
  (let [value (some-> encoded read-browser-edn)]
    (when (or (int? value)
              (keyword? value)
              (and (vector? value)
                   (= 2 (count value))
                   (qualified-keyword? (first value))))
      value)))

(defn- route-namespace
  "Read one route segment as a round-tripping simple Clojure symbol."
  {:malli/schema [:=> [:cat :string] [:maybe :seon.ns/name]]}
  [segment]
  (let [value (read-browser-edn segment)]
    (when (and (simple-symbol? value)
               (= segment (str value)))
      value)))

(defn- namespace-exists?
  [db namespace-name]
  (some?
   (db/q '[:find ?namespace .
          :in $ ?namespace-name
          :where [?namespace :seon.ns/name ?namespace-name]]
        db namespace-name)))

(defn- agent-namespace
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id] [:or :nil :seon.render/namespace :seon.db/error-result]]}
  [db agent-id]
  (db/q '[:find ?namespace-name .
         :in $ ?agent-id
         :where
         [?agent :seon.agent/id ?agent-id]
         [?agent :seon.agent/namespace ?namespace]
         [?namespace :seon.ns/name ?namespace-name]]
       db agent-id))

(defn- current-cluster-name
  "The cluster the service's context environment names, or nil.
  A forked branch carries its parent's cluster rows, so no row can name it."
  {:malli/schema [:=> [:cat :map] [:maybe :seon.boot/cluster-name]]}
  [service]
  (:seon.boot/cluster-name (env/of (:seon.sci.eval/ctx service))))

(defn- cluster-effective!
  "The named cluster's effective configuration; its refusal is thrown whole."
  {:malli/schema [:=> [:cat :map :seon.db/database-value] :seon.config/effective]}
  [service database]
  (let [cluster-name (current-cluster-name service)
        effective (if cluster-name
                    (config/effective database cluster-name)
                    (seon.error.refusal/diagnostic (java.util.Date.) :seon.render.web/render 'seon.render.web/cluster-effective!
                     {:seon.error/message "The web service's context environment names no cluster."
                      :seon.error/fix "Start the web service with its cluster's context."
                      :seon.error/member :seon.boot/cluster-name
                      :seon.error/expected :seon.boot/cluster-name}))]
    (if (:seon.error/at effective)
      (throw (ex-info (:seon.error/message effective "The cluster's configuration refused.")
                      effective))
      effective)))

(defn- ensure-namespace-owner!
  "Create the namespace's owner agent unless one is assigned; its id or refusal.

  A WRITE, reached only from the explicit create-owner POST
  (`context-response`). A page GET never calls it: a read path never writes a
  transaction, and the agent it creates runs provider turns (issue 24w)."
  {:malli/schema [:=> [:cat [:map [:seon.store/connection-object :seon.db/connection] [:seon.db.process/id :seon.db.process/id]] :seon.render/namespace] [:or :seon.agent/id :seon.render.web/request-error :seon.agent/error :seon.db/error-result]]}
  [{connection :seon.store/connection-object
    process :seon.db.process/id
    :as service}
   namespace-name]
  ;; Any agent assigned to the namespace evaluates there; stewardship only
  ;; routes that namespace's faults and requests.
  (or (first (cluster.agent/assigned-to (db/db connection) namespace-name))
      (let [ensure! @cluster-ensure-entity!
            result (ensure!
                    connection process
                    {:seon.agent/id (str namespace-name)
                     :seon.cluster/name (current-cluster-name service)
                     :seon.ns/name namespace-name})]
        (if (or (:seon.db/invalid-read result) (:seon.schema/expected-value result) (:seon.db.write.attempt/request-id result))
          result
          (or (first (cluster.agent/assigned-to (db/db connection) namespace-name))
              (seon.error.refusal/diagnostic (java.util.Date.) :seon.render.web/render 'seon.render.web/ensure-namespace-owner!
                                             {:seon.error/message (str "The namespace owner for " namespace-name
                                                                       " was not created.")
                                              :seon.error/fix "Supply the expected member and repeat the requested operation."
                                              :seon.render.web/refused-member :seon.ns/name
                                              :seon.error/expected "a namespace with an assigned agent"
                                              :seon.error/offending namespace-name}))))))

(defn- create-owner-form
  "`:seon.render/html` — the explicit control that creates a namespace's agent.

  A plain form POST to the agent context route; the created agent's id is the
  namespace name, so the route id names both. The response redirects to the
  namespace page, which then renders the new owner."
  {:malli/schema [:=> [:cat :seon.render/namespace] :seon.render/hiccup]}
  [namespace-name]
  [:form {:id "create-namespace-owner"
          :class "seon-debug-header"
          :method "post"
          :action (route/path ::route/agent-context {:id (str namespace-name)})}
   [:input {:type "hidden" :name "action" :value "create-owner"}]
   [:div [:span "agents"] [:code "none assigned"]]
   [:button {:type "submit"}
    (str "Create an agent for " namespace-name " (it starts taking turns)")]])

(def ^:private namespace-walk-options
  {:depth 2})

(defn- walk-request
  [db caps agent-id output connection render-context]
  (cond-> {:seon.db/db db
             :seon.agent/id agent-id
             :seon.render.walk/lookup [:seon.agent/id agent-id]
             :seon.render/output output
             :seon.render/distance (:depth namespace-walk-options)
             :seon.sci.admit/caps caps
             :seon.sci.eval/ctx (:seon.sci.eval/ctx render-context)
             :seon.sci.eval/time-limit-ms
             (:seon.config.eval/time-limit-ms render-context)}
      (contains? render-context :seon.render/profile)
      (assoc :seon.render/profile (:seon.render/profile render-context))

      connection
      (assoc :seon.db/connection connection)))

(defn- page-response
  {:seon/tasks #{'seon.render.web/a-page-renders-with-a-refused-database}
   :seon.fn/external-sink :html-response
   :seon.fn/projection-boundary :seon.render/html}
  [service
   agent-id]
  (let [database (db/db (:seon.store/connection-object service))]
    (if (:seon.error/at database)
      ;; a refused read answers as that refusal, never as a page's database
      (text-response 503 (:seon.error/message database))
      (let [database (agent-database (:seon.agent/routing service) database agent-id)
            page (current-page service database agent-id)
            stream-html (get page stream-strip-id)
            unit-html (vals (dissoc page stream-strip-id))]
        (html-response
         (shell {:seon.agent/id agent-id
                 :seon.db/db database
                 ::agent-header? true
                 :seon.render/page
                 [(transcript/render-agent-header
                   (session-controls
                    (debug-turn-request database
                                        (:seon.store/connection-object service) agent-id
                                        (:seon.sci.admit/caps service)
                                        (assoc service ::transcript/debug? false))))
                  [:section {:class "seon-namespace-page"}
                   (into [:section {:class "seon-rank-layout"}]
                         (map hiccup/raw)
                         unit-html)
                   (hiccup/raw stream-html)]]
                 :seon.render.web/feed-url
                 (route/path ::route/feed {:id agent-id})}))))))

(defn- sent-context
  "The agent's context as its next model call is sent it, derived at the
  query's `basis` (else the head), and the value at the query's cursor."
  {:malli/schema [:=> [:cat :seon.render.web/service :seon.agent/id :map] :map]}
  [{connection :seon.store/connection-object :as service} agent-id query]
  (let [head (db/db connection)
        basis (or (some-> (get query "basis") parse-long) (db/basis-t head))
        database (db/as-of head basis)
        composed (context/model-context database
                                        (debug-turn-request database connection agent-id (:seon.sci.admit/caps service)
                                                            (dissoc service :seon.turn/id))
                                        agent-id)
        cursor (data/parse-cursor (get query "path") (get query "offset"))]
    {::database database ::basis basis ::cursor cursor ::found (data/at composed cursor)}))

(defn- sent-context-html
  "The agent's context as its next model call is sent it, through the value
  printer's HTML face; every cut pages it at the same basis."
  {:malli/schema [:=> [:cat :seon.render.web/service :seon.agent/id :map] :seon.render/hiccup]}
  [service agent-id query]
  (let [{::keys [database basis cursor found]} (sent-context service agent-id query)]
    [:div {:id (block/surface-id (keyword "graph-sent" agent-id))}
     (value/html {:seon.render/value (if (contains? found :seon.render.data/value) (:seon.render.data/value found) found)
                  :seon.db/db database
                  :seon.render.value/root [::context agent-id]
                  :seon.render.data/cursor cursor
                  :seon.render.value/route-base (route/path ::route/agent-debug {:id agent-id} {:sent "true"})
                  :seon.render.value/basis basis})]))

(defn- debug-response
  "The ring response for one agent's or namespace's debug page, or for its record or context fragment."
  {:malli/schema [:=> [:cat :seon.render.web/service :seon.ns/name [:maybe :seon.agent/id] :map]
                  [:map [:status :int] [:headers [:map-of :string :string]] [:body [:or :nil :string]]]]}
  [{connection :seon.store/connection-object
    :as service}
   viewer-namespace agent-id request]
  (let [query (query-params request)
        datastar? (= "true" (get-in request [:headers "datastar-request"]))]
    (cond
      (and agent-id (= "true" (get query "record"))
           datastar?)
      (let [page (current-page service (db/db connection) agent-id)]
        (html-response
         (hiccup/->string
          (into [:div {:id (block/surface-id (keyword "session-record" agent-id))
                       :data-signals__ifmissing "{showEverything:true}"}]
                (map hiccup/raw)
                (vals (dissoc page stream-strip-id))))
         {"datastar-mode" "replace"}))

      ;; A string cut's whole text: the string itself, no printing step.
      (and agent-id (= "true" (get query "sent")) (= "1" (get query "text")))
      (let [{value :seon.render.data/value :as found} (::found (sent-context service agent-id query))]
        (if (string? value)
          (text-response 200 value)
          (text-response 422 (or (:seon.error/message found) "The path does not name a string."))))

      (and agent-id (= "true" (get query "sent")) datastar?)
      (html-response (hiccup/->string (sent-context-html service agent-id query))
                     {"datastar-mode" "replace"})

      (and agent-id (not (get query "subject")))
      (html-response
       (shell
        {:seon.agent/id agent-id
         ::session? true
         :seon.render.web/feed-url (route/path ::route/feed {:id agent-id})
         :seon.render.debug/viewer-namespace viewer-namespace
         :seon.render/page
         [[:section {:class "seon-session-page"}
           (transcript/render-agent-header
            (session-controls (debug-turn-request (db/db connection) connection agent-id
                                                  (:seon.sci.admit/caps service) service)))
           [:details {:class "seon-session-context"
                      (keyword "data-on:toggle")
                      (str "el.open && @get('" (route/path ::route/agent-debug {:id agent-id} {:sent "true"}) "')")}
            [:summary "Context"]
            [:div {:id (block/surface-id (keyword "graph-sent" agent-id))}
             "Loading the agent’s context…"]]
           [:details {:class "seon-session-record"
                      (keyword "data-on:toggle")
                      (str "el.open && @get('" (route/path ::route/agent-debug {:id agent-id} {:record "true"}) "')")}
            [:summary "Record"]
            [:div {:id (block/surface-id (keyword "session-record" agent-id))}
             "Loading the agent’s current record…"]]]]}))

      :else
    (let [db (db/db connection)
          default-subject (if agent-id
                            [:seon.agent/id agent-id]
                            [:seon.ns/name viewer-namespace])
          debug-request (debug-query query default-subject viewer-namespace
                                     agent-id)
          projection (sci.kernel/context-projection
                      (:seon.sci.eval/ctx service))
          effective (schema/call-with-projection
                     projection
                     #(cluster-effective! service db))
          render-context
          (assoc service :seon.render/profile
                 (render/agent-render-profile effective))
          rendered-page (current-page render-context db [::debug-tab debug-request])
          feed-id (or agent-id (str viewer-namespace))
          page
          [[:section {:class "seon-debug"
                      :data-signals__ifmissing
                      "{showEverything:true,selectedUnit:''}"}
            (when-let [unowned (::unowned-namespace service)]
              (create-owner-form unowned))
            [:header {:id "debug-inspection-header" :class "seon-debug-header"}
             [:div [:span "viewer"] [:code (pr-str viewer-namespace)]]
             [:div [:span "subject"]
              [:code (pr-str (:seon.render.debug/subject debug-request))]]
             [:div [:span "snapshot"]
              [:code (pr-str (db/database-value-identity db))]]
             [:div [:span "output"]
              [:code (pr-str (:seon.render/output debug-request))]]]
            [:div {:class "seon-debug-grid"}
             (debug-experiment-html rendered-page)]]]]
      (html-response
       (shell
        (cond->
         {:seon.render.debug/viewer-namespace viewer-namespace
           ::agent-header? (boolean agent-id)
           :seon.render/page page
           :seon.render.web/feed-url
           (route/path ::route/feed
                       {:id feed-id}
                       (debug-query-strings debug-request))}
          agent-id
          (assoc :seon.agent/id agent-id))))))))

(defn- canonical-namespace-response
  [{connection :seon.store/connection-object :as service} debug? request]
  (let [namespace-name (some-> (get-in request [:path-params :namespace])
                               route-namespace)]
    (if-not (and namespace-name (namespace-exists? (db/db connection) namespace-name))
      (not-found request)
      (if debug?
        (debug-response service namespace-name
                        (first (cluster.agent/assigned-to (db/db connection) namespace-name))
                        request)
        ;; A GET NEVER WRITES (24w). An owned namespace renders its first
        ;; assigned agent's page; an unowned one renders the agentless
        ;; namespace inspection with the explicit create control.
        (if-let [owner (first (cluster.agent/assigned-to (db/db connection) namespace-name))]
          (page-response service owner)
          (debug-response (assoc service ::unowned-namespace namespace-name)
                          namespace-name nil request))))))

(defn- agent-alias-response
  [{connection :seon.store/connection-object :as service} debug? request]
  (let [agent-id (get-in request [:path-params :id])
        namespace-name (agent-namespace (agent-database (:seon.agent/routing service) (db/db connection) agent-id)
                                        agent-id)]
    (if namespace-name
      (if debug?
        (debug-response service namespace-name agent-id request)
        (page-response service agent-id))
      (not-found request))))

(defn- health-response
  "The health page: the cluster's health report through its HTML face, then
  the render proc's packages on the feed. Painted from one database value on
  the request's thread; a unit still deriving shows pending and repaints when
  it completes."
  {:malli/schema [:=> [:cat :seon.render.web/service :map]
                  [:map [:status :int] [:headers [:map-of :string :string]] [:body :string]]]}
  [{connection :seon.store/connection-object root :seon.agent/id :as service} _request]
  (let [database (db/db connection)]
    (if (:seon.error/at database)
      (text-response 503 (:seon.error/message database))
      (let [page (current-page service database [::health-tab {}])]
        (html-response
         (shell {:seon.agent/id root
                 ::agent-header? true
                 :seon.render/page [(hiccup/raw (get page health-surface))]
                 :seon.render.web/feed-url (route/path ::route/feed {:id root} {:health "true"})}))))))

(defn- root-alias-response
  [service request]
  (agent-alias-response service false
                        (assoc-in request [:path-params :id]
                                  (:seon.agent/id service))))

(defn- inbound-response
  [service request]
  (let [agent-id (get-in request [:path-params :id])
        params (decode-form request)]
    (inbound service
             (cond-> {:seon.agent/id agent-id}
               (contains? params "content")
               (assoc :seon.message/inbound-content
                      (get params "content"))))))

(defn- create-owner-response
  "The explicit owner creation: one agent for the namespace the route id names.

  Redirects to the namespace page on success; refusals are 422 values."
  {:malli/schema [:=> [:cat :seon.render.web/service :map] [:map [:status :int] [:headers [:map-of :string :string]] [:body [:or :nil :string]]]]}
  [{connection :seon.store/connection-object :as service} request]
  (let [namespace-name (some-> (get-in request [:path-params :id]) route-namespace)]
    (if-not (and namespace-name (namespace-exists? (db/db connection) namespace-name))
      (not-found request)
      (let [owner (ensure-namespace-owner! service namespace-name)]
        (if (string? owner)
          {:status 303
           :headers {"location" (route/path ::route/namespace {:namespace (str namespace-name)})}
           :body nil}
          (text-response 422 (value/text {:seon.render/value owner :seon.db/db (db/db connection) :seon.render.value/root [::create-owner namespace-name]})))))))

(defn- received-response
  "Acknowledge one message for the page's agent: the inbox's offered
  `(my.message/received! id)`, posted by its button. The writer decides;
  a missing or unknown message answers its refusal as text."
  {:malli/schema [:=> [:cat :seon.render.web/service :map [:map-of :string :string]] [:map [:status :int] [:headers [:map-of :string :string]] [:body [:or :nil :string]]]]}
  [service request params]
  (let [message-id (get params "message-id")
        result (if (seq message-id)
                 (message/received! message-id (:seon.store/connection-object service)
                                    (get-in request [:path-params :id]))
                 (seon.error.refusal/diagnostic (java.util.Date.) :seon.render.web/render 'seon.render.web/received-response
                  {:seon.error/message "A received action names the message it acknowledges."
                   :seon.error/member :seon.message/id :seon.error/expected :seon.message/id
                   :seon.error/offending params}))]
    (if (:seon.message/id result)
      (no-content)
      (text-response 422 (value/text {:seon.render/value result :seon.db/db (db/db (:seon.store/connection-object service)) :seon.render.value/root [::received]})))))

(defn- context-response
  "The agent context route: the explicit create-owner action or a message
  acknowledgement; any other action is not found.

  The form body is a stream, so it is decoded once here."
  {:malli/schema [:=> [:cat :seon.render.web/service :map] [:map [:status :int] [:headers [:map-of :string :string]] [:body [:or :nil :string]]]]}
  [service request]
  (let [params (decode-form request)]
    (case (get params "action")
      "create-owner" (create-owner-response service request)
      "received" (received-response service request params)
      (not-found request))))

(defn- feed-response
  [service request]
  (feed request
        (merge service
               {:seon.agent/id (get-in request [:path-params :id])
                :seon.render.web/root-agent-id
                (:seon.agent/id service)})))

(defn- data-response
  [{connection :seon.store/connection-object
    :keys [:seon.agent/id]
    caps :seon.sci.admit/caps
    :as service}
   request]
  (let [db (db/db connection)
        query (query-params request)
        value? (contains? query "value")
        value-digest (get query "value")
        entity? (contains? query "entity")
        entity (query-entity (get query "entity"))
        ;; A stored value's page is its continuation (`seon.render.walk/continue`,
        ;; design §3g): the one owner reads the artifact the first page was
        ;; selected from, navigates and selects; this route only adapts it.
        root-value
        (cond
          value? nil
          entity?
          (when-let [eid (some-> (when entity
                                   (db/pull db [:db/id] entity))
                                 :db/id)]
            (generic-entity db eid))

          :else
          (schema/canonical-database-attributes
           (sci.kernel/context-projection (:seon.sci.eval/ctx service))))
        projection (sci.kernel/context-projection
                    (:seon.sci.eval/ctx service))
        effective (schema/call-with-projection
                   projection
                   #(cluster-effective! service db))
        options
        (select-keys
         effective
         [:seon.render.value/max-collection])
        cursor (data/parse-cursor (get query "path") (get query "offset"))
        found (when-not value? (data/at root-value cursor))
        opened-value (if (contains? found :seon.render.data/value)
                       (:seon.render.data/value found)
                       found)
        route-base (route/path ::route/data
                               {}
                               (cond-> {}
                                 value? (assoc :value value-digest)
                                 entity? (assoc :entity (get query "entity"))))
        unit (cond->
              {:seon.render.value/root
               (cond
                 value? [:seon.blob/digest value-digest]
                 entity?
                 (or entity [:seon.render.data/entity (get query "entity")])
                 :else :seon.render.data/schema)
               :seon.render.value/route-base route-base
               :seon.render.value/options options
               :seon.render.data/cursor cursor
               :seon.sci.admit/caps caps
               ;; THE UNIT CARRIES ITS WORLD (AGENTS §2.1). The response
               ;; already holds the database value it derived every part of
               ;; this unit from, and producer selection needs it: installed
               ;; value type and cardinality are what decide a pulled
               ;; entity's transaction shape. Omitting it made every `/data`
               ;; page answer 500 with a contract refusal as its body.
               :seon.db/db db
               :seon.sci.eval/ctx (:seon.sci.eval/ctx service)
               :seon.sci.eval/time-limit-ms
               (:seon.config.eval/time-limit-ms service)}
               (not (:seon.config/missing-effective effective))
               (assoc :seon.render/profile
                      (render/agent-render-profile effective)))
        page (if value?
               (render.walk/continue (assoc unit :seon.render.walk/continuation-node [:seon.blob/digest value-digest]
                                            :seon.db/connection connection))
               (value/prepare (assoc unit :seon.render/value opened-value) :seon.render/html))]
    (html-response
     (shell {:seon.agent/id id
             :seon.db/db db
             ;; A refused page (gone, malformed, over budget) is shown
             ;; as the refusal value it is, at the root.
             :seon.render/page
             [(if (:seon.error/at page)
                (value/render-html (assoc unit :seon.render/value page
                                          :seon.render.data/cursor (data/parse-cursor nil nil)))
                (value/render-html-data page))]
             :seon.render.web/feed-url
             (route/path ::route/feed {:id id})}))))

(defn- static-response
  [directory request]
  (or (resource (str directory "/" (get-in request [:path-params :path])))
      (not-found request)))

(defn- bind-handlers
  [handlers]
  (cwalk/postwalk
   (fn [node]
     (if-let [handler-name (and (map? node) (:handler node))]
       (assoc node :handler
              (or (get handlers handler-name)
                  (throw
                   (ex-info "Route handler is not bound."
                            {:seon.render.route/name handler-name}))))
       node))
   route/routes))

(defn- same-origin-middleware
  [next-handler]
  (fn [request]
    (if (same-origin? request)
      (next-handler request)
      (text-response 403 "cross-origin POST refused"))))

(defn- failure-response
  "The response to a request whose handling threw: the failure as one error
  value, shown by the error pair's HTML face, status 500. The response never
  throws: a face that fails answers the value printed by the core printer."
  {:malli/schema [:=> [:cat :seon.render.web/service :seon.error/throwable]
                  [:map [:status :int] [:headers [:map-of :string :string]] [:body :string]]]}
  [service failure]
  (let [value (seon.error.refusal/diagnostic
               {:seon.error/at (java.util.Date.) :seon.error/layer :seon.render.web/request
                :seon.error/operation 'seon.render.web/handler :seon.error/throwable failure})]
    (try
      (assoc (html-response
              (hiccup/->string (error/render-html {:seon.render/value value
                                                   :seon.db/db (db/db (:seon.store/connection-object service))})))
             :status 500)
      ;; Declared case: the face itself failed; the value still answers,
      ;; printed by the value printer's core (no render, database or config).
      (catch Throwable _
        (text-response 500 (value/core-text value))))))

(defn handler
  "Build the one Reitit Ring handler from the route table and service."
  {:malli/schema [:=> [:cat :seon.render.web/service]
                  [:fn clojure.core/fn?]]}
  [service]
  (let [handlers {::route/root #(root-alias-response service %)
                  ::route/namespace #(canonical-namespace-response
                                      service false %)
                  ::route/namespace-debug #(canonical-namespace-response
                                            service true %)
                  ::route/agent #(agent-alias-response service false %)
                  ::route/agent-debug #(agent-alias-response service true %)
                  ::route/agent-message #(inbound-response service %)
                  ::route/agent-context #(context-response service %)
                  ::route/feed #(feed-response service %)
                  ::route/health #(health-response service %)
                  ::route/data #(data-response service %)
                  ::route/css #(static-response "css" %)
                  ::route/js #(static-response "js" %)}
        router (ring/router
                (bind-handlers handlers)
                {:reitit.middleware/registry
                 {::route/same-origin {:name ::route/same-origin
                                       :wrap same-origin-middleware}}})
        route-handler (ring/ring-handler router not-found)]
    (fn [request]
      ;; EVERY THROW ANSWERS HERE. One escaping to http-kit is logged by its
      ;; printStackTrace, which prints the whole ex-data unbounded (the
      ;; 2026-09-26 OOM, task 80f54dd48aef). The request is this entrance's
      ;; own execution: record the fault and answer it as the error value it
      ;; is, through the error pair's HTML face (errors are values; a stack
      ;; trace printed as the body is not one).
      (try
        (schema/call-with-projection
         (sci.kernel/context-projection (:seon.sci.eval/ctx service))
         #(route-handler request))
        (catch Throwable failure
          (try (entrance-fault! (env/of (:seon.sci.eval/ctx service)) failure
                                :seon.render.web/request 'seon.render.web/handler)
               (catch Throwable unrecorded
                 (log/warn (str "seon.render.web: a request fault could not be recorded:\n"
                                (value/core-text unrecorded)))))
          (failure-response service failure))))))

;;; ---------------------------------------------------------------------------
;;; Lifecycle
;;; ---------------------------------------------------------------------------

(def port-floor
  "7700. The bottom of the derived range.

  GROUNDED, not picked from the air: 7700-7999 carries no IANA
  assignment, sits clear of the crowded 3000/4000/5000/8000/8080 block
  every other dev server reaches for, and is comfortably below the
  ephemeral range the operating system allocates from (49152+ on this
  platform), so a derived port can never collide with one the OS was
  about to hand out."
  7700)

(def port-ceiling
  "8000, exclusive. Three hundred ports — enough that a handful of named
  clusters on one machine rarely collide, small enough that the whole
  range is greppable when one does."
  8000)

(defn derived-port
  "The default port for a cluster NAME. Pure, and stable forever.

  THE POINT IS BOOKMARKABILITY: a named cluster answers on the same port
  after every restart, so a browser tab keeps working and nobody reads a
  log to find their own cluster. The derivation is not magic — it is
  FNV-1a over the name's UTF-8 bytes, folded into the range — and it is
  written out here rather than delegated to `clojure.core/hash` on
  purpose: `hash` is stable in practice but its stability across JVM
  versions is not a contract, and a bookmark that silently moves is
  worse than one that never existed.

  Collisions are expected and handled, not prevented: two names can land
  on one port, and `start!` falls back to an ephemeral port and SAYS SO
  rather than refusing to serve."
  {:malli/schema [:=> [:cat :seon.boot/cluster-name] :seon.render.web/port]}
  [cluster-name]
  (let [;; FNV-1a, 32-bit: offset basis 2166136261, prime 16777619.
        ;; Unsigned arithmetic by construction — the mask keeps it in
        ;; 32 bits so the JVM's signed longs cannot change the answer.
        hashed (reduce (fn [accumulated byte-value]
                         (-> (bit-xor accumulated (bit-and byte-value 0xff))
                             (* 16777619)
                             (bit-and 0xffffffff)))
                       2166136261
                       (.getBytes ^String cluster-name "UTF-8"))]
    (+ port-floor (mod hashed (- port-ceiling port-floor)))))

(defn start!
  "Bind an http-kit server on LOOPBACK and return its descriptor.

  Port 0 means the operating system chooses, and the chosen port is
  reported in the return value rather than written to a file: the
  interface publishes its own readiness, which is the standing rule for
  anything a caller would otherwise poll for.

  Loopback only. This serves an agent's page and, later, its
  interactions; exposing it on every interface would be a decision, and
  a decision like that does not belong in a default."
  {:malli/schema [:=> [:cat :seon.render.web/service] :seon.render.web/server]}
  [service]
  (let [connection (:seon.store/connection-object service)
        process (:seon.db.process/id service)
        write! (or (:seon.db/writer service) db/transact!)
        run-server (::run-server (socket service))
        ;; Transaction provenance resolves to a durable process entity.
        ;; This is convergent: a running service creates its row once,
        ;; and every later start observes it.
        _ (when-not
           (db/q '[:find ?process .
                  :in $ ?id
                  :where [?process :seon.db.process/id ?id]]
                (db/db connection) process)
            (let [result
                  (write! connection [{:seon.db.process/id process}])]
              ;; The writer answers a report (it has :db-after) or its
              ;; declared refusal; nothing else is success.
              (when-not (:db-after result)
                (throw
                 (ex-info "The web service process transaction was refused."
                          result)))))
        workers (Executors/newVirtualThreadPerTaskExecutor)
        wanted (or (:seon.render.web/port service) 0)
        bind! (fn [port]
                ;; Keep the listening socket through development adoption.
                ;; Resolve the handler Var on each request so reload and
                ;; re-instrumentation take effect without rebinding the server.
                (run-server (fn [request] ((handler service) request))
                                 {:ip "127.0.0.1"
                                  :port port
                                  :worker-pool workers
                                  :legacy-return-value? false}))
        ;; A TAKEN PORT MUST NOT COST THE VIEW. Two clusters whose names
        ;; derive the same port, or a stale process still holding one,
        ;; are ordinary situations — so the second one serves anyway, on
        ;; an ephemeral port, and REPORTS both numbers. Refusing to serve
        ;; would make a name collision look like a broken build; serving
        ;; silently on a different port would make a bookmark fail with
        ;; no explanation. Saying so is the only honest option.
        [server fell-back?]
        (try
          [(bind! wanted) false]
          (catch java.net.BindException cause
            (if (zero? wanted)
              (do
                (.shutdownNow workers)
                (throw
                 (ex-info
                  "The web server could not bind its ephemeral port."
                  {:seon.render.web/attempted-port wanted}
                  cause)))
              [(bind! 0) true])))
        bound (http/server-port server)]
    (cond-> {:seon.render.web/server server
             :seon.render.web/port bound
             :seon.render.web/url (str "http://127.0.0.1:" bound)
             ;; http-kit does not own the pool it is given, so the descriptor
             ;; keeps it for stop! to shut down
             :seon.render.web/executor workers}
      ;; present exactly when the wanted port was not the bound one, so
      ;; "did this fall back?" is key presence rather than a comparison
      ;; every reader has to remember to make
      fell-back? (assoc :seon.render.web/wanted-port wanted))))

(defn- await-exit!
  "Await one exit fact (`completion`: the await owner's `:seon.await/blocking-deref`
  or `:seon.await/port-operations`) under the delivery bound; nil once it
  arrived, else the timeout naming `member`."
  {:malli/schema [:=> [:cat :map :seon.config.eval/time-limit-ms :qualified-keyword]
                  [:or :nil :seon.await/timeout-error]]}
  [completion backstop-ms member]
  (let [result (await/await!
                (merge {:seon.await/bound {:seon.await/config-attribute :seon.config.eval/time-limit-ms
                                           :seon.await/config-value backstop-ms}
                        :seon.await/diagnostic {:seon.error/layer :seon.render.web/delivery
                                                :seon.error/operation 'seon.render.web/stop!
                                                :seon.error/expected ::exited
                                                :seon.error/offending ::pending
                                                :seon.error/member member}}
                       completion))]
    (when (:seon.await/elapsed-ms result) result)))

(defn stop!
  "Close the server and await the exit of everything it ran.

  The request is the served descriptor with the view's tab registration and
  delivery bound (the caller holds both: the view it served). http-kit's stop
  closes every connection, so each tab's `on-close` ends its painting and
  each writer leaves the registration in its own `finally`: the server
  thread's exit (http-kit's stop promise) and an empty registration are the
  two exit facts, each awaited under the delivery bound. Nil once both
  arrived, else the first timeout, naming the pages still held.

  The worker executor http-kit was given is the server's to own: it is shut
  down on every path, and its termination is the third exit fact, awaited
  under the same bound.

  ## Costs

  - Scratch probe on default 2026-09-28 (real http-kit + Datastar SSE, three
    curl tabs, writers doing 150 ms of close work): returns at 152 ms with
    every writer exited; a writer that never exits returns the timeout at
    the bound (300 ms bound: 305 ms), naming its page."
  {:malli/schema [:=> [:cat :seon.render.web/stop-request] [:or :nil :seon.await/timeout-error]]}
  [{server :seon.render.web/server registration :seon.render.web/registration
    backstop-ms :seon.config.eval/time-limit-ms
    ^java.util.concurrent.ExecutorService executor :seon.render.web/executor}]
  (let [stopped (http/server-stop! server)
        feeds-exited (async/promise-chan)
        exited! (fn [_ _ _ open] (when (empty? open) (async/offer! feeds-exited true)))]
    (add-watch registration ::stop! exited!)
    (exited! nil nil nil @registration)
    (try
      ;; http-kit answers nil when the server was already stopping
      (or (when stopped (await-exit! {:seon.await/blocking-deref stopped} backstop-ms ::server-thread))
          (some-> (await-exit! {:seon.await/port-operations [feeds-exited]} backstop-ms ::feed-writers)
                  (assoc-in [:seon.error/data ::open-pages] (vec (keys @registration))))
          ;; close shuts down and returns only once every task ended, so the
          ;; promise is delivered on termination and never on a timeout
          (let [terminated (promise)]
            (Thread/startVirtualThread #(do (.close executor) (deliver terminated true)))
            (await-exit! {:seon.await/blocking-deref terminated} backstop-ms ::worker-executor)))
      (finally
        (remove-watch registration ::stop!)
        (.shutdown executor)))))
