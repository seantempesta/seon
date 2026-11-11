(ns seon.web
  "Root's web proc (:web, an :io step-fn, declared in resources/seon/system.edn): it owns
  the HTTP listener (ring-chez-adapter, 127.0.0.1 only, fibers) and the tabs' SSE
  channels, and renders nothing (W1 §5.1; proto/web's transport, session L2). Its parts:

    in :http        the adapter's handlers (edge fibers) put each request here as data,
                    with a promise when it waits for an answer
    in :from-render patches per view, keyframes, composed pages, the agents' index
    out :to-render  the views open in tabs; page, keyframe and context requests
    out :to-api     root's requests for UI calls ({:op :request ...}, answered to render)

  A patch is serialized once (Datastar's datastar-patch-elements) and offered to every tab
  of its view; a full channel marks the tab stale and it gets a keyframe (the view's
  composed page, morphed by id: inputs and DOM identity survive); a closed one is dropped.

  Callbacks are data (DECISIONS): one stateless /call route whose payload is the call
  (fn, agent, args, a form's fields). Root's functions (seon.root/spawn!, submit!, run-tests!,
  merge!, merge-main!, clone!, interrupt!, kill!) become the requests their fns send
  (seon.root/request), each recorded on main as root's evaluation, origin :ui; any other fn
  runs in the agent as an eval of (seon.web/ui-call 'fn args), origin :ui, refused as data
  unless the var is marked ^:seon/callable."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.edn :as sedn] [seon.profile :as profile] [seon.contract :refer [defn defn-]] [clojure.string :as str] [clojure.set] [clojure.edn :as edn]
            [clojure.core.async :as a] [clojure.core.async.flow :as flow] [ring-chez.adapter :as adapter]
            [seon.lib :as lib] [seon.contract :as contract] [seon.config :as config] [seon.render :as render] [seon.render.ui :as ui] [seon.web.html :as h] [seon.step :as step] [seon.root :as c] [seon.reader :as reader]))

;;; --- the files it serves ------------------------------------------------------------

(def public-dir
  "Where the page's scripts, datastar.js and highlight.js, are read (:seon.config.web/public-dir)."
  (config/value :seon.config.web/public-dir))

(def stylesheet
  "The page's stylesheet, bin/build's css step's output (:seon.config.web/stylesheet; read per
  request, so a build's new stylesheet shows on reload)."
  (config/value :seon.config.web/stylesheet))

;;; --- SSE framing ------------------------------------------------------------------------

(defn patch-event
  "One Datastar v1 datastar-patch-elements event. No selector and no mode: replace each
  top-level element by its id."
  {:malli/schema [:function [:=> [:cat :string] :string] [:=> [:cat :string [:maybe :string] [:maybe :string]] :string]]}
  ([html] (patch-event html nil nil))
  ([html selector mode]
   (str "event: datastar-patch-elements\n"
        (when selector (str "data: selector " selector "\n"))
        (when mode (str "data: mode " mode "\n"))
        (when-not (str/blank? html) (apply str (for [line (str/split-lines html)] (str "data: elements " line "\n"))))
        "\n")))

(defn chunk-of
  "A render patch as one SSE chunk, serialized once for every tab of its view."
  {:malli/schema [:=> [:cat :seon.render/patch] :string]}
  [{:keys [frags appends prepends afters moves removes]}]
  (str (apply str (for [[_ html] frags] (patch-event html)))
       (apply str (for [[sel html] appends] (patch-event html sel "append")))
       (apply str (for [[sel html] prepends] (patch-event html sel "prepend")))
       ;; after their siblings, once those are placed (in layout order)
       (apply str (for [[sel html] afters] (patch-event html sel "after")))
       ;; a unit that changed place: taken out, then put back by its new neighbour
       (apply str (for [[id sel mode html] moves] (str (patch-event "" (str "[id=\"" id "\"]") "remove") (patch-event html sel mode))))
       (apply str (for [id removes] (patch-event "" (str "[id=\"" id "\"]") "remove")))))

;;; --- UI calls ------------------------------------------------------------------------------

(defn ui-call
  "Run var sym on args in this process if the var is marked ^:seon/callable (the UI
  allowlist); otherwise the error as data. What a /call naming an agent's own function
  evaluates in that agent (recorded with origin :ui)."
  {:malli/schema [:=> [:cat :symbol [:maybe :map]] :any]}
  [sym args]
  (let [v (resolve sym)]
    (if (and v (:seon/callable (meta v)))
      (@v (or args {}))
      (contract/error :seon.render/not-callable #'seon.web/ui-call
                      (str sym " is not callable from the UI: mark it ^:seon/callable")
                      {:seon.render/fn sym}))))

(defn requests
  "A UI call as root's requests: [[req target-agent] ...]. Root's function is the request it
  sends (seon.root/request; its refused answer when root refuses it); any other fn is an eval of it in
  the agent, through the allowlist (ui-call). index {agent {:pid :branch :status}}."
  {:malli/schema [:=> [:cat :seon.render/call :map] [:vector [:tuple :map [:maybe :keyword]]]]}
  [{f :var :keys [agent args forms]} index]
  (let [one (fn [op id args] [[(c/request op id args (get index id)) id]])]
    (case f
      seon.root/spawn! (vec (repeat (min 100 (max 1 (or (:n args) 1))) [(c/request 'spawn! nil (cond-> {} (not (str/blank? (:task args))) (assoc :task (:task args))) nil) nil]))
      ;; what a person types: its words a message the agent reads, its forms its REPL's, told apart by the one reader (seon.reader/words-and-forms); to every agent, forms alone
      seon.root/submit! (cond (str/blank? forms) []
                              (= :all agent) (vec (for [[id a] (sort-by (comp str key) index) :when (not (c/gone-statuses (:status a)))] [(c/request 'submit! id {:forms forms :origin :ui} a) id]))
                              :else (let [{:keys [words] code :forms} (reader/words-and-forms forms)]
                                      (cond-> []
                                        (not (str/blank? words)) (into (one 'message! agent {:body words}))
                                        (not (str/blank? code)) (into (one 'submit! agent {:forms code :origin :ui})))))
      ;; seon.root/interrupt! is no request: an effect on a process, performed by this proc
      seon.root/interrupt! []
      (if (= "seon.root" (namespace f))
        (one (symbol (name f)) agent args)
        (one 'submit! agent {:origin :ui :forms (sedn/encode (list 'seon.web/ui-call (list 'quote f) (merge args (dissoc (:fields args) :forms))))})))))

;;; --- HTTP edge ----------------------------------------------------------------------------

(defn- form-params {:malli/schema [:=> [:cat [:maybe :string]] :map]}
  [s]
  (into {} (for [kv (str/split (or s "") #"&")
                 :let [[k v] (str/split kv #"=" 2)]
                 :when (seq k)]
             [(java.net.URLDecoder/decode k "UTF-8") (java.net.URLDecoder/decode (or v "") "UTF-8")])))

(defn- open-form
  "The form a toggled unit names after its row's id (\"row/(drop 1 (get-in result/a3f9c1d2 [:value]))\",
  a cut the page opened, seon.print.emit/elided-hiccup): that form; nil for a unit with none (a
  row itself); ::unread when the text after the slash does not read as EDN (the edge: the
  browser's text)."
  {:malli/schema [:=> [:cat :string] :any]}
  [unit]
  (when-let [p (second (str/split unit #"/" 2))]
    (try (sedn/decode p) (catch Exception _ ::unread))))

(defn- ask!
  "m asked of the web proc on its :http in-port (seon.step/ask-edge), and its one answer waited
  for: the edge's blocking read; the proc never waits. At stop every waiting promise answers
  nil, and a request after it, the channel closed, answers nil at once."
  {:malli/schema [:=> [:cat :seon.host/chan :map] :any]}
  [ch m] @(step/ask-edge ch m nil))

(defn- html-response
  "A page as the render proc answered it, {:html :status} (status absent: 200; an address naming no agent: 404)."
  {:malli/schema [:=> [:cat [:maybe :map]] :map]}
  [{:keys [html status]}]
  (if html
    {:status (or status 200) :headers {"Content-Type" "text/html; charset=utf-8" "Cache-Control" "no-store"} :body html}
    {:status 503 :headers {"Content-Type" "text/plain"} :body "not rendered yet: reload"}))

(defn- view-of
  "The view a path names, each a location the render proc resolves to an entity on main: :root, an
  entity's id (a number), or an agent by an address, `<agent>` ([:chat agent], its chat), which names it by its key (boot/id: a session,
  stable across a root's restarts, so what a link or a bookmark holds), its id in this boot, its task's id or its branch
  (seon.render.proc/session-of resolves it; the suffix is cut first, so a key's slash is no matter); `<agent>/ctx` the agent itself, every render of its ctx;
  `<agent>/debug` [:debug agent], what its model was sent; `root/debug` what root reads. The
  inverse of seon.render.ui/view-param. See docs/render.md#the-page."
  {:malli/schema [:=> [:cat [:maybe :string]] :any]}
  [s]
  (cond (= "root/debug" s) [:debug :root]
        ;; what agent v's model was sent
        (some->> s (re-matches #".+/debug")) [:debug (keyword (subs s 0 (- (count s) 6)))]
        ;; agent v's debug view: every render of its ctx
        (some->> s (re-matches #".+/ctx")) (keyword (subs s 0 (- (count s) 4)))
        (or (str/blank? s) (= "root" s)) :root
        (re-matches #"\d+" s) (parse-long s)
        ;; an agent by its id in this boot or its branch (main/task-x): its chat, the page a person reads
        :else [:chat (keyword s)]))

(defn same-origin?
  "Whether a request is the dashboard's own page's: true unless its Origin header is present and
  is not this dashboard's (scheme, host and port are `http://127.0.0.1:<:seon.config.web/port>`,
  as the listener binds), or its Sec-Fetch-Site says cross-site. A browser always names one of the
  two on a cross-origin POST; a client that sends neither (nc, a test, an older client) is not a
  page of another origin and is served as before. See
  docs/failure-points.md#failure-points-met-without-a-catch-the-edge-answers-data."
  {:malli/schema [:=> [:cat :map] :boolean]}
  [req]
  (let [h (:headers req)
        origin (some-> (get h "origin") str/lower-case str/trim not-empty)
        site (some-> (get h "sec-fetch-site") str/lower-case str/trim)]
    (and (not= "cross-site" site)
         (or (nil? origin)
             (= origin (str "http://127.0.0.1:" (config/value :seon.config.web/port)))))))

(defn feed-encoding
  "The Content-Encoding a tab's feed is sent with, for a request whose Accept-Encoding header is accept:
  :seon.config.web/feed-encoding's choice, :auto being deflate when the browser takes it; nil sends it plain. See
  docs/render.md#in-flight-output-streams."
  {:malli/schema [:=> [:cat [:maybe :string]] [:maybe [:enum "deflate"]]]}
  [accept]
  (case (config/value :seon.config.web/feed-encoding)
    :none nil
    :deflate "deflate"
    :auto (when (and accept (re-find #"(?i)(^|[\s,])deflate([\s;,]|$)" accept)) "deflate")))

(defn deflate-chunks
  "A transducer of a tab's feed: each chunk (a string) as the next bytes of one zlib stream (Content-Encoding deflate),
  flushed with Z_SYNC_FLUSH so the browser has every event as it is sent, its window (32 KB) shared across chunks, so text
  a chunk repeats from an earlier one costs a back-reference. Its Deflater is the tab's, ended with its channel. See
  docs/render.md#in-flight-output-streams."
  {:malli/schema [:=> [:cat] :any]}
  []
  (fn [rf]
    (let [d (java.util.zip.Deflater. (int (config/value :seon.config.web/feed-deflate-level)))
          buf (byte-array 65536)]
      (fn ([] (rf))
          ([r] (.end d) (rf r))
          ([r chunk]
           (.setInput d (.getBytes ^String chunk "UTF-8"))
           (let [out (java.io.ByteArrayOutputStream.)]
             (loop [] (let [n (.deflate d buf 0 (alength buf) java.util.zip.Deflater/SYNC_FLUSH)]
                        (when (pos? n) (.write out buf 0 n))
                        (when (= n (alength buf)) (recur))))
             (rf r (.toByteArray out))))))))

(defn handler
  "The routes, as an edge in front of the proc's :http in-port."
  {:malli/schema [:=> [:cat :seon.host/chan] :seon.host/fn]}
  [ch]
  ;; the page's scripts, each read once: Datastar, and highlight.js (its Clojure grammar) for code
  (let [js (into {} (for [f ["/datastar.js" "/highlight.js"]] [f (delay (slurp (str public-dir f)))]))]
    (fn [req]
      (let [uri (:uri req) m (:request-method req) q (form-params (:query-string req))]
        (cond
          ;; a POST is the dashboard's own page's, whichever route it names: one check before any route reads it
          (and (= m :post) (not (same-origin? req)))
          {:status 403 :headers {"Content-Type" "text/plain"} :body (str "a post is this dashboard's own page's: " (get-in req [:headers "origin"] (get-in req [:headers "sec-fetch-site"])) " is not")}
          (and (= m :get) (= uri "/")) (html-response (ask! ch {:op :page :view [:chat :home]}))
          ;; root's own page: every session on main, its process and its problems
          (and (= m :get) (= uri "/root")) (html-response (ask! ch {:op :page :view :root}))
          ;; what root reads: its page's other face, as an agent's /debug is
          (and (= m :get) (= uri "/root/debug")) (html-response (ask! ch {:op :page :view [:debug :root]}))
          ;; an agent's page, the render of its ctx, by its id in root's boot
          (and (= m :get) (str/starts-with? uri "/agent/")) (html-response (ask! ch {:op :page :view (view-of (java.net.URLDecoder/decode (str/replace (subs uri 7) "+" "%2B") "UTF-8"))}))
          ;; a user's page: an agent found by its :seon.user/name, by the same walk (seon.render.proc/session-of)
          (and (= m :get) (str/starts-with? uri "/user/")) (html-response (ask! ch {:op :page :view (view-of (java.net.URLDecoder/decode (str/replace (subs uri 6) "+" "%2B") "UTF-8"))}))
          ;; a page's list end scrolled into view: its next rows
          (and (= m :post) (= uri "/more")) (do (a/>!! ch {:op :more :view (view-of (get q "view"))}) {:status 204 :headers {}})
          ;; a row's <details> toggled (the debug page): its unit renders its whole content while open
          ;; a cut opened (unit "row/form"): its form must read as EDN, or the render proc never sees it
          (and (= m :post) (= uri "/open"))
          (if (= ::unread (open-form (get q "unit" "")))
            {:status 400 :headers {"Content-Type" "text/plain"} :body (str "a cut's form reads as EDN: " (get q "unit"))}
            (do (a/>!! ch {:op :toggle :view (view-of (get q "view")) :unit (get q "unit") :open (= "true" (get q "open"))})
                {:status 204 :headers {}}))
          ;; a step of the debug timeline: the request to show, a model call's eid or latest
          (and (= m :post) (= uri "/at"))
          (let [t (get q "turn" "")]
            (if-let [turn (if (= "latest" t) :latest (some-> (re-matches #"\d+" t) parse-long))]
              (do (a/>!! ch {:op :at :view (view-of (get q "view")) :turn turn}) {:status 204 :headers {}})
              {:status 400 :headers {"Content-Type" "text/plain"} :body (str "a step names a model call's id or latest: " t)}))
          (and (= m :get) (= uri "/feed"))
          ;; compressed when the browser takes it (feed-encoding): each tab's stream its own, so text an update repeats is
          ;; a back-reference on the wire
          (let [enc (feed-encoding (get-in req [:headers "accept-encoding"]))
                out (if enc (a/chan 64 (deflate-chunks)) (a/chan 64))]
            (a/>!! ch {:op :open :view (view-of (get q "view")) :ch out})
            {:status 200 :headers (cond-> {"Content-Type" "text/event-stream" "Cache-Control" "no-cache"} enc (assoc "Content-Encoding" enc)) :body out})
          (and (= m :get) (js uri)) {:status 200 :headers {"Content-Type" "text/javascript" "Cache-Control" "max-age=3600"} :body @(js uri)}
          (and (= m :get) (= uri "/app.css"))
          (if (.exists (java.io.File. ^String stylesheet))
            {:status 200 :headers {"Content-Type" "text/css" "Cache-Control" "no-cache"} :body (slurp stylesheet)}
            {:status 404 :headers {"Content-Type" "text/plain"} :body (str stylesheet " is not built: bin/build css")})
          (and (= m :get) (= uri "/context.txt"))
          {:status 200 :headers {"Content-Type" "text/plain; charset=utf-8" "Cache-Control" "no-store"} :body (str (ask! ch {:op :context}))}
          (and (= m :get) (= uri "/render.edn"))
          {:status 200 :headers {"Content-Type" "application/edn" "Cache-Control" "no-store"} :body (str (ask! ch {:op :render-stats}))}
          (and (= m :get) (= uri "/web.edn"))
          {:status 200 :headers {"Content-Type" "application/edn"} :body (sedn/encode (ask! ch {:op :stats}))}
          (and (= m :post) (= uri "/call"))
          (let [fields (into {} (for [[k v] (form-params (some-> (:body req) slurp))] [(keyword k) v]))
                args (some-> (get q "args") edn/read-string)
                agent (some-> (get q "agent") keyword)
                f (some-> (get q "fn") symbol)]
            (if f
              (do (a/>!! ch {:op :call :call {:var f :agent agent :forms (:forms fields)
                                             :args (merge args (dissoc fields :forms))}})
                  {:status 204 :headers {}})
              {:status 400 :headers {"Content-Type" "text/plain"} :body "a call names its fn"}))
          :else {:status 404 :headers {"Content-Type" "text/plain"} :body "not found"})))))

;;; --- the proc ------------------------------------------------------------------------------

(defn- views-msg {:malli/schema [:=> [:cat :map] :map]}
  [st] {:op :views :views (into #{} (map :view (vals (:tabs st)))) :tabs (count (:tabs st))})

(declare drawn)

(defn- id-sel
  "The CSS selector of the element whose id is (seon.render/key-id [x]): by attribute, since such an id may start with a
  digit (a boot's UUID) or hold a '.', which a #id selector misreads. See docs/render.md#in-flight-output-streams."
  {:malli/schema [:=> [:cat :string] :string]}
  [x] (str "[id=\"" (render/key-id [x]) "\"]"))

(defn- offer-all
  "Offer chunk to every fresh tab of view; full tabs turn stale, closed ones leave.
  Answers [st' bytes-sent]."
  {:malli/schema [:=> [:cat :map :any :string] [:tuple :map :int]]}
  [st view chunk]
  (reduce-kv (fn [[st sent] k {:keys [ch] :as tab}]
               (if (or (not= view (:view tab)) (contains? (:stale st) k) (str/blank? chunk))
                 [st sent]
                 (let [ok (a/offer! ch chunk)]
                   ;; a patch that draws a stream's in-flight element catches the tab up on it (drawn)
                   (cond (true? ok) [(drawn st k chunk false) (+ sent (count chunk))]
                         (false? ok) [(update st :tabs dissoc k) sent]
                         :else [(update st :stale conj k) sent]))))
             [st 0] (:tabs st)))

(defn- chat-agent
  "The stable key (:seon.agent/key) of the agent whose chat view shows ([:chat v], v its id, branch or key
  in root's agents index), else nil. See docs/render.md#in-flight-output-streams."
  {:malli/schema [:=> [:cat :map :any] [:maybe :string]]}
  [index view]
  (when (and (vector? view) (= :chat (first view)))
    (let [v (second view)]
      (some (fn [[id a]] (when (or (= v id) (= v (:branch a)) (= (ui/kname v) (:key a))) (:key a))) index))))

(defn- offer-tab
  "St after offering chunk to tab t: its bytes counted when taken, the tab gone when closed, stale when full."
  {:malli/schema [:=> [:cat :map :any :string] :map]}
  [st t chunk]
  (let [ok (a/offer! (get-in st [:tabs t :ch]) chunk)]
    (cond (true? ok) (update st :bytes + (count chunk))
          (false? ok) (update st :tabs dissoc t)
          :else (update st :stale conj t))))

(defn- container-frame
  "Entity in's in-flight element drawn whole with each open part's text so far (seon.render.ui/stream-container, parts in
  the order they began), as one Datastar event that replaces the element: replaced, not morphed, since the element is
  data-ignore-morph. See docs/render.md#in-flight-output-streams."
  {:malli/schema [:=> [:cat :map :string] :string]}
  [st in]
  (let [parts (sort-by :n (for [[[_ stream] e] (:streams st) :when (= in (:in e))] (assoc e :stream stream)))]
    (patch-event (h/html (ui/stream-container in (vec (for [{:keys [stream part text]} parts] [stream (or part "content") (if (string? text) text (apply str text))]))))
                 (id-sel in) "replace")))

(defn- drawn
  "St after chunk reached tab t: the in-flight elements chunk draws (data-stream-in) are the tab's from now (a keyframe's
  are all it has), and each one chunk first draws, or a keyframe draws again, is drawn whole at once with the text held
  (container-frame), so text that came before the page had the element is shown. Costs one search of chunk when it draws
  none. See docs/render.md#in-flight-output-streams."
  {:malli/schema [:=> [:cat :map :any :string :boolean] :map]}
  [st t chunk keyframe?]
  (if (or (not (get-in st [:tabs t])) (and (not keyframe?) (not (str/includes? chunk "data-stream-in="))))
    st
    (let [found (into #{} (map second) (re-seq #"data-stream-in=\"([^\"]*)\"" chunk))
          had (get-in st [:tabs t :has] #{})
          fresh (if keyframe? found (clojure.set/difference found had))
          st (assoc-in st [:tabs t :has] (if keyframe? found (into had found)))
          open (into #{} (map :in) (vals (:streams st)))]
      (reduce (fn [st in] (if (and (contains? open in) (get-in st [:tabs t])) (offer-tab st t (container-frame st in)) st))
              st fresh))))

(defn- stream-in
  "An effect's in-flight text, {:stream :in :part :agent :text} (:text all of that part so far, a string or its pieces in
  order, a vector the producer shares, joined only when a tab is sent it; :agent its stable key, :in
  the entity whose in-flight element the render drew): the part's latest text is held (memory only, gone at its end:
  in-flight text may be lost), and each tab of the agent's chat whose page has the element gets it drawn whole in its
  place (container-frame). A message that never arrived costs nothing, since the next carries all of it; a tab that
  cannot take one is stale and its keyframe brings the latest (drawn). {:stream :agent :end true} forgets the part.
  See docs/render.md#in-flight-output-streams."
  {:malli/schema [:=> [:cat :map [:map [:stream :string] [:agent :string]]] :map]}
  [st {:keys [stream in part agent text end]}]
  (let [k [agent stream]]
    (if end
      (let [in (get-in st [:streams k :in])
            st (update st :streams dissoc k)]
        (cond-> st (not-any? #(= in (:in %)) (vals (:streams st))) (update :tabs update-vals #(update % :has disj in))))
      (let [e (get-in st [:streams k])
            in (or in (:in e))
            st (-> st (assoc-in [:streams k] {:in in :part (or part (:part e)) :text (or text "") :n (or (:n e) (:stream-n st 0))})
                   (cond-> (nil? e) (update :stream-n (fnil inc 0))))
            frame (delay (container-frame st in))]
        (reduce-kv (fn [st t {:keys [view has]}]
                     (if (or (contains? (:stale st) t) (not= agent (chat-agent (:index st) view)) (not (contains? has in)))
                       st
                       (offer-tab st t @frame)))
                   st (:tabs st))))))

(defn- streams-of-live
  "St keeping only the streams, and the in-flight elements tabs hold, of agents whose process lives in root's index."
  {:malli/schema [:=> [:cat :map] :map]}
  [st]
  (let [live (into #{} (keep (fn [a] (when-not (c/gone-statuses (:status a)) (:key a)))) (vals (:index st)))
        st (update st :streams #(into {} (filter (fn [[[agent]]] (contains? live agent))) %))
        ins (into #{} (map :in) (vals (:streams st)))]
    (update st :tabs update-vals #(update % :has (fn [s] (into #{} (filter ins) s))))))

(defn- keyframe!
  "The view's keyframe (its composed body, morphed by id) to every stale tab of it; with all?, to every tab of
  it (the render proc's pass sends the page whole when a patch would replace an element no tab holds)."
  {:malli/schema [:function [:=> [:cat :map :any [:maybe :string]] :map]
                  [:=> [:cat :map :any [:maybe :string] :boolean] :map]]}
  ([st view html] (keyframe! st view html false))
  ([st view html all?]
  (if-not html
    st
    (let [chunk (patch-event html)]
      (reduce-kv (fn [st k {:keys [ch] :as tab}]
                   (if (and (= view (:view tab)) (or all? (contains? (:stale st) k)))
                     (let [ok (a/offer! ch chunk)]
                       ;; the keyframe draws every stream's element empty: the tab has seen none of them
                       ;; the keyframe redraws the view: a stream's in-flight element it draws is caught up (drawn)
                       (cond (true? ok) (-> st (update :stale disj k) (update :bytes + (count chunk)) (drawn k chunk true))
                             ;; closed, or still full since it fell behind: the tab is gone (a
                             ;; live one's Datastar reconnects and gets a fresh keyframe)
                             :else (do (a/close! ch) (-> st (update :tabs dissoc k) (update :stale disj k)))))
                     st))
                 st (:tabs st))))))

(defn- page-doc
  "A view's composed body as the whole document (title, styles, Datastar, its feed)."
  {:malli/schema [:=> [:cat :any [:maybe :string]] [:maybe :string]]}
  [view body]
  (when body
    (str "<!doctype html>"
         (h/html (ui/shell (cond (= :root view) "Seon on Jolt · root"
                                 ;; an agent's chat by its name; its model's context; its debug view
                                 (and (vector? view) (= :chat (first view))) (str "Seon on Jolt · " (ui/kname (second view)))
                                 (vector? view) (str "Seon on Jolt · " (ui/kname (second view)) " · context")
                                 :else (str "Seon on Jolt · " (ui/kname view) " · debug"))
                           (str "/feed?view=" (ui/view-param view))
                           (h/raw body))))))

(defn- stats
  "The web proc's own numbers: its :stats answer and its ping map."
  {:malli/schema [:=> [:cat :map] :map]}
  [st]
  {:tabs (count (:tabs st)) :stale (count (:stale st)) :sent-bytes (:bytes st 0) :patches (:patches st 0) :streams (count (:streams st))
   :chunk-p50-bytes (lib/p50 (:chunk-bytes st)) :listening (:listening st) :port (:port st) :calls (:calls st 0) :error (:error st)})

(defn- http-in
  "A request from the edge."
  {:malli/schema [:=> [:cat :map :seon.host/volatile :map] :map]}
  [st o m]
  (case (:op m)
    :listener (do (step/out! o :to-api {:op :web :listening (boolean (:listening st)) :port (:port st) :error (:error st)}) st)
    :open (let [[st k] (step/pend st nil)
                st (-> st (assoc-in [:tabs k] {:ch (:ch m) :view (:view m)}) (update :stale conj k))]
            (step/out! o :to-render (views-msg st))
            (step/out! o :to-render {:op :keyframe :view (:view m)})
            st)
    :page (let [[st k] (step/pend st [(:p m) (:view m)])]
            (step/out! o :to-render {:op :page :view (:view m) :k k})
            st)
    (:context :render-stats)
             (let [[st k] (step/pend st [(:p m) :text])]
               (step/out! o :to-render {:op ({:context :context :render-stats :stats} (:op m)) :k k})
               st)
    :more (do (step/out! o :to-render {:op :more :view (:view m)}) st)
    :toggle (do (step/out! o :to-render (select-keys m [:op :view :unit :open])) st)
    :at (do (step/out! o :to-render (select-keys m [:op :view :turn])) st)
    :stats (do (deliver (:p m) (stats st)) st)
    :call (let [c (:call m)
                reqs (requests c (:index st {}))]
            (when (= 'seon.root/interrupt! (:var c))
              (some-> (get-in st [:index (:agent c) :pid]) (lib/kill! lib/SIGUSR1)))
            ;; answered to the render proc, which shows each in the stream by its tag; root's
            ;; function is root's evaluation, its form in the tag: the render proc records it on
            ;; main (origin :ui) with what root answered, as root's REPL records one; an eval opened
            ;; on a page (opened!) is a reader's view of it, never root's work, so it is no eval of root's
            (doseq [[req target] reqs
                    :let [{f :var :keys [agent args forms]} c
                          form (when (and (= "seon.root" (namespace f)) (not (#{'seon.root/opened! 'seon.root/again!} f)))
                                 (sedn/encode (cond-> (list f) agent (concat [agent]) (seq args) (concat [args]) forms (concat [forms]))))]]
              (let [tag (cond-> [:web f target] form (conj form))]
                (if (contract/error? req)
                  (step/out! o :to-render {:op :refused :tag tag :r req})
                  (step/out! o :to-api {:op :request :req req :route [:render :answers] :tag tag}))))
            (update st :calls (fnil inc 0)))
    st))

(defn- render-in
  "A message from the render proc."
  {:malli/schema [:=> [:cat :map :seon.host/volatile :map] :map]}
  [st o m]
  (case (:op m)
    ;; an SSE comment with the ms the chunk left this proc: a client can tell transport
    ;; delay from render delay (Datastar ignores comments)
    :patch (let [chunk (str ": at " (System/currentTimeMillis) "\n" (chunk-of m))
                 [st sent] (offer-all st (:view m) chunk)
                 st (-> st (update :bytes + sent) (update :patches (fnil inc 0))
                        (update :chunk-bytes #(lib/push-window % (count chunk) 200)))]
             ;; a tab fallen behind gets the view's keyframe next
             (when (some #(= (:view m) (get-in st [:tabs % :view])) (:stale st))
               (step/out! o :to-render {:op :keyframe :view (:view m)}))
             st)
    :keyframe (keyframe! st (:view m) (:html m) (boolean (:whole m)))
    ;; the render proc has nothing in flight: an SSE comment on every feed (Datastar ignores
    ;; comments), the event a client or a check waits on before reading a current view
    :settled (reduce (fn [st view] (let [[st sent] (offer-all st view ": settled\n\n")] (update st :bytes + sent)))
                     st (distinct (map :view (vals (:tabs st)))))
    (:page :text) (let [[st [p view]] (step/answered st (:k m))]
                    (some-> p (deliver (if (= :page (:op m)) {:html (page-doc view (:html m)) :status (:status m)} (:text m))))
                    st)
    :index (streams-of-live (assoc st :index (:agents m)))
    st))

(defn web-proc
  "Root's web proc (see the namespace doc). Args :port (127.0.0.1 only; system.edn binds
  :seon.config.web/port; 0: no listener). A listener that cannot bind is data in
  its ping (:listening false :error), never a failed boot."
  {:malli/schema :seon.step/step-fn}
  ([] {:params {:port "the port on 127.0.0.1 (0: none)"}
       :ins {:from-render "patches, keyframes, pages, the agents' index"
             ;; any effect's in-flight text, addressed [:web :streams] (Flow's dynamic route, no conn): a sliding
             ;; buffer, so a put never waits (docs/render.md#in-flight-output-streams)
             :streams "in-flight text {:stream :in :part :agent :text}, :text all of the part so far, then {:stream :agent :end true}"}
       :outs {:to-render "views open, page and keyframe requests" :to-api "UI calls as root's requests"}
       ;; socket writes and offers only; rendering is the render proc's
       :workload :io
       :ping-map-fn stats})
  ([args]
   (let [port (:port args)
         http (a/chan 256)
         st {:port port :http http :tabs {} :stale #{} :rid 0 :pending {} :bytes 0 :index {}
             ::flow/in-ports {:http http}}
         ;; whether it listens is an event to the api's view (its first message, on its own
         ;; in-port: init has no outputs), never a ping (DECISIONS, If nothing has changed,
         ;; we do nothing; no clock or timer decides (three exceptions))
         _ (a/put! http {:op :listener})]
     (if (and port (pos? port))
       (let [server (try (adapter/run-server (handler http) {:port port :host "127.0.0.1" :strategy :fibers
                                                             :keep-alive-timeout-ms 600000 :write-timeout-ms 60000})
                         (catch Throwable e (contract/error :seon.web/unbound #'seon.web/web-proc (step/error-msg e) {:port port})))]
         (if (contract/error? server)
           (assoc st :listening false :error server)
           (assoc st :listening true :server server :port (:port server))))
       (assoc st :listening false))))
  ([st tr]
   (if (= tr ::flow/stop)
     (do (doseq [{:keys [ch]} (vals (:tabs st))] (a/close! ch))
         (doseq [[p] (vals (:pending st))] (deliver p nil))
         (when-let [s (:server st)] (adapter/stop-server s {:drain-timeout-ms 0}))
         (assoc st :tabs {} :listening false :server nil))
     st))
  ([st in m]
   (let [o (volatile! {})
         before (into #{} (map :view (vals (:tabs st))))
         st (cond
              (nil? m) st
              (= in :http) (http-in st o m)
              (= in :from-render) (render-in st o m)
              (= in :streams) (stream-in st m)
              :else st)
         ;; a tab whose channel closed (found at an offer) is gone: the views watched may change
         st (update st :stale (fn [s] (into #{} (filter #(contains? (:tabs st) %)) s)))]
     (when (and (not= :open (:op m)) (not= before (into #{} (map :view (vals (:tabs st))))))
       (step/out! o :to-render (views-msg st)))
     [st @o])))
