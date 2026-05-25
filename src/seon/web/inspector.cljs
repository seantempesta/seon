(ns seon.web.inspector
  "Agent inspector UI — what the agent sees, both as AI text and HTML.

   Two columns per agent:
     - LEFT  `:seon.render/ai`   — `(inspect/ctx-preview ...)` text, the
       exact bytes the LLM would receive on its next render.
     - RIGHT `:seon.render/html` — same set of entities; for each entity,
       resolve its `:seon.render/html` slot and call it (or fall back to
       the `:seon.render/ai` text wrapped in <pre>).

   Reactive: a single tx-listener subscribes to every commit. For each
   tx-report we read `:seon.db/agent-id` from the tx eid in db-after.
   Substrate tx (nil agent-id) fan out to ALL watching agents; per-agent
   tx fan out only to that agent's connections. Pushes are coalesced
   per-agent on a 100ms trailing timer so a burst of tx within one
   turn produces one render.

   Routes (mounted from seon.web.serve):
     GET /agents           — pick an agent
     GET /agent/<id>       — the inspector page for <id>
     GET /agent/<id>/sse   — SSE stream for that agent"
  (:require
    [clojure.string :as str]
    [datahike.api :as d]
    [seon.agent-view :as agent-view]
    [seon.db :as db]
    [seon.inspect :as inspect]
    [seon.log :as log]
    [seon.render :as render]
    [seon.render.default :as default]
    [seon.ui.components :as comp]
    [seon.ui.html :as html]))

;; ============================================================
;; SSE connection registry — per-agent. Distinct from the main
;; /sse registry in seon.web.serve so the existing broadcast keeps
;; behaving unchanged.
;;
;; Shape: atom of {agent-id -> [{:id <uuid> :res <ServerResponse>} ...]}
;; ============================================================

(defonce ^:private !sse-by-agent (atom {}))

(defn- add-conn! [agent-id conn]
  (swap! !sse-by-agent update agent-id (fnil conj []) conn))

(defn- remove-conn! [agent-id conn-id]
  (swap! !sse-by-agent update agent-id
         (fn [conns] (vec (remove #(= conn-id (:id %)) conns)))))

(defn- watching-agents
  "Set of agent-ids that currently have at least one open SSE stream."
  []
  (->> @!sse-by-agent
       (filter (fn [[_ conns]] (seq conns)))
       (map first)
       set))

;; ============================================================
;; Data sources — one snapshot per render. Returns the AI text + the
;; per-entity hiccup list.
;; ============================================================

(defn- list-agents-data
  "Pull `[id state turn-count]` rows for every `:seon.agent/id` entity.
   Sorted by id asc."
  []
  (let [conn db/*conn*
        db   @conn
        rows (db/query
               {:seon.db/db db
                :seon.db/query
                '[:find ?id ?state ?turns
                  :where
                  [?a :seon.agent/id ?id]
                  [(get-else $ ?a :seon.agent/state :unknown) ?state]
                  [(get-else $ ?a :seon.agent/turn-count 0) ?turns]]})]
    (->> rows
         (sort-by first)
         (mapv (fn [[id state turns]]
                 {:seon.agent/id         id
                  :seon.agent/state      state
                  :seon.agent/turn-count turns})))))

(defn- render-entity-hiccup
  "Resolve the entity's `:seon.render/html` slot (if any) and return its
   hiccup. Falls back to rendering the AI text (if any) in a <pre>.
   Returns nil when neither slot is present (caller filters)."
  [db agent-id entity]
  (let [html-slot (:seon.render/html entity)
        ai-slot   (:seon.render/ai   entity)
        input     {:seon.db/db db
                   :seon.agent/id agent-id
                   :seon.render/entity entity}]
    (cond
      html-slot
      (try
        (let [{:seon.render/keys [hiccup]} (render/html-render html-slot input)]
          hiccup)
        (catch :default e
          [:div {:class "text-error text-xs font-mono"}
           "render error: " (or (.-message e) (str e))]))

      ai-slot
      (let [text (try
                   (:seon.render/text (render/ai-render ai-slot input))
                   (catch :default e (str "render error: " e)))]
        [:pre {:class "text-xs font-mono whitespace-pre-wrap text-text-200"} text])

      :else nil)))

(defn- snapshot
  "Compute one render snapshot for `agent-id`:
     {:ai-text <string>
      :token-est <int>
      :char-count <int>
      :html-cards [<hiccup> ...]   ; one per visible entity, in render order
      :agent <pulled entity or nil>}"
  [agent-id]
  (let [{:seon.db/keys [db]} (agent-view/agent-view {:seon.agent/id agent-id})
        {:seon.render/keys [text entities token-estimate]}
        (render/assemble-ai-context {:seon.agent/id agent-id :seon.db/db db})
        cards (->> entities
                   (keep (fn [e]
                           (when-let [h (render-entity-hiccup db agent-id e)]
                             [:div {:class (str "border-l-2 border-amber-700/40 "
                                                "pl-2 py-1 mb-1")}
                              h])))
                   vec)
        ent   (default/all-running-agents db)
        agent (some #(when (= agent-id (:seon.agent/id %)) %) ent)]
    {:ai-text   (or text "")
     :char-count (count (or text ""))
     :token-est  (or token-estimate 0)
     :html-cards cards
     :agent      agent
     :handler-count
     (count (try (:seon.handler/list (inspect/handlers {:seon.agent/id agent-id}))
                 (catch :default _ [])))}))

;; ============================================================
;; Page rendering — full page (for initial GET) AND morph fragments
;; (for SSE pushes).
;;
;; The two panes use stable ids `#inspect-ai-<id>` and
;; `#inspect-html-<id>` so datastar morphs by id when the SSE event
;; contains a re-rendered fragment.
;; ============================================================

(defn- ai-pane-id   [agent-id] (str "inspect-ai-" agent-id))
(defn- html-pane-id [agent-id] (str "inspect-html-" agent-id))
(defn- header-id    [agent-id] (str "inspect-header-" agent-id))

(defn- header-fragment
  [agent-id {:keys [agent char-count token-est handler-count]}]
  (let [state (or (:seon.agent/state agent) :unknown)
        turns (or (:seon.agent/turn-count agent) 0)]
    [:header {:id (header-id agent-id)
              :class "flex items-center gap-3 p-2 border-b border-base-800 bg-base-900"}
     [:span {:class "text-xs font-mono text-text-200"} "agent " agent-id]
     (comp/status-dot state)
     [:span {:class "text-xs text-text-400"} (str "turn " turns)]
     [:span {:class "text-xs text-text-400"} (str handler-count " handlers")]
     [:span {:class "text-xs text-text-500 ml-auto"}
      (str "~" token-est " tokens · " char-count " chars")]
     [:a {:href "/agents"
          :class "text-xs text-amber-500 hover:text-amber-300"} "← all agents"]]))

(defn- ai-pane-fragment
  [agent-id {:keys [ai-text]}]
  [:div {:id (ai-pane-id agent-id)
         :class "flex flex-col h-full overflow-hidden border-r border-base-800"}
   [:div {:class "px-2 py-1 text-xs font-mono text-text-400 bg-base-900 border-b border-base-800"}
    ":seon.render/ai  (what the LLM sees)"]
   [:pre {:class (str "flex-1 overflow-auto p-3 text-xs font-mono "
                      "whitespace-pre-wrap text-text-100 bg-base-950")}
    (if (str/blank? ai-text) "(empty context)" ai-text)]])

(defn- html-pane-fragment
  [agent-id {:keys [html-cards]}]
  [:div {:id (html-pane-id agent-id)
         :class "flex flex-col h-full overflow-hidden"}
   [:div {:class "px-2 py-1 text-xs font-mono text-text-400 bg-base-900 border-b border-base-800"}
    ":seon.render/html  (rendered view)"]
   [:div {:class "flex-1 overflow-auto p-2 text-xs bg-base-950"}
    (if (seq html-cards)
      (into [:div {:class "flex flex-col"}] html-cards)
      [:div {:class "text-text-500 italic p-2"} "no renderable entities"])]])

(defn- chat-bar-fragment
  "Sticky bottom bar spanning both panes. Submits as a regular
   `application/x-www-form-urlencoded` POST to `/chat?agent=<id>` —
   the same endpoint Datastar `data-on-click__post` would call. We
   intercept the submit in inline JS, fetch() the form data, clear
   the input on success, and let the existing SSE registry push the
   re-rendered panes once the user message lands in the DB.

   No reload on submit; SSE fans the user-message tx out and the
   inspector's tx-listener re-renders both panes within ~100ms."
  [agent-id]
  [:form {:id "seon-chat-form"
          :action (str "/chat?agent=" agent-id)
          :method "post"
          :class (str "shrink-0 flex items-center gap-2 "
                      "border-t border-base-800 bg-base-900 px-2 py-1.5")
          :onsubmit (str "event.preventDefault();"
                        "var f=this;var i=f.elements['text'];"
                        "var text=i.value;if(!text||!text.trim())return false;"
                        "fetch(f.action,{method:'POST',"
                        "headers:{'Content-Type':'application/x-www-form-urlencoded'},"
                        "body:'text='+encodeURIComponent(text)})"
                        ".then(function(r){if(r.ok){i.value='';i.focus();}});"
                        "return false;")}
   [:input {:type "text"
            :name "text"
            :placeholder (str "message agent " agent-id " …  (Cmd/Ctrl+Enter to send)")
            :autocomplete "off"
            :autofocus true
            :class (str "flex-1 bg-base-950 border border-base-800 rounded "
                        "px-2 py-1 text-amber-50 text-xs font-mono "
                        "placeholder:text-text-500 "
                        "focus:outline-none focus:border-amber-700")}]
   [:button {:type "submit"
             :class (str "bg-amber-900/70 hover:bg-amber-800 text-amber-50 "
                         "px-3 py-1 rounded text-xs font-mono")}
    "send"]])

(defn- inspector-shell
  "The full page for `/agent/<id>`. The body contains the two-pane grid;
   the header is inside the morph zone so it updates too."
  [agent-id snap]
  (str
    "<!DOCTYPE html>"
    (html/->string
      [:html {:lang "en" :data-theme "phosphor"}
       [:head
        [:meta {:charset "utf-8"}]
        [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
        [:title (str "seon · agent " agent-id)]
        [:link {:rel "stylesheet" :href "/css/output.css"}]
        ;; highlight.js — atom-one-dark theme. Loaded from CDN; no
        ;; server-side dep. After SSE morphs swap in new <code> blocks,
        ;; we re-run `hljs.highlightAll()` via a MutationObserver on
        ;; the html pane (see inline script below).
        [:link {:rel "stylesheet"
                :href "https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@11.9.0/build/styles/atom-one-dark.min.css"}]
        [:script {:src "https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@11.9.0/build/highlight.min.js"}]
        ;; marked.js — renders `:seon.eval/narration` markdown into HTML
        ;; in the right pane only (the AI pane keeps raw markdown — LLMs
        ;; read it fine as text). Loaded from CDN; no server-side dep.
        [:script {:src "https://cdn.jsdelivr.net/npm/marked/marked.min.js"}]
        ;; Inline override: bend a couple of atom-one-dark colors toward
        ;; the Phosphor Terminal palette (amber emphasis), plus minimal
        ;; markdown body styling for narration (Tailwind's `prose` plugin
        ;; isn't loaded via CDN).
        [:style (html/raw
                  (str "code.hljs{background:transparent !important;padding:0 !important;}"
                       ".hljs-keyword,.hljs-built_in{color:#fbbf24;}"
                       ".hljs-string{color:#fde68a;}"
                       ".hljs-symbol,.hljs-literal{color:#fcd34d;}"
                       ".hljs-comment{color:#78716c;font-style:italic;}"
                       ".markdown{color:#d6d3d1;font-size:0.75rem;line-height:1.4;}"
                       ".markdown h1{font-size:0.95rem;color:#fbbf24;margin:0.4rem 0 0.2rem;font-weight:600;}"
                       ".markdown h2{font-size:0.85rem;color:#fcd34d;margin:0.35rem 0 0.15rem;font-weight:600;}"
                       ".markdown h3{font-size:0.8rem;color:#fde68a;margin:0.3rem 0 0.1rem;font-weight:600;}"
                       ".markdown p{margin:0.2rem 0;}"
                       ".markdown ul,.markdown ol{margin:0.2rem 0 0.2rem 1rem;}"
                       ".markdown li{margin:0.05rem 0;list-style:disc;}"
                       ".markdown ol li{list-style:decimal;}"
                       ".markdown code{font-family:ui-monospace,monospace;color:#fde68a;background:#1c1917;padding:0 0.2rem;border-radius:0.15rem;}"
                       ".markdown pre{background:#1c1917;padding:0.3rem 0.4rem;border-radius:0.2rem;overflow-x:auto;margin:0.2rem 0;}"
                       ".markdown pre code{background:transparent;padding:0;}"
                       ".markdown strong{color:#fef3c7;font-weight:600;}"
                       ".markdown em{color:#fde68a;font-style:italic;}"
                       ".markdown a{color:#fbbf24;text-decoration:underline;}"
                       ".markdown blockquote{border-left:2px solid #57534e;padding-left:0.5rem;color:#a8a29e;margin:0.2rem 0;}"))]
        [:script {:type "module" :src "/js/datastar.js"}]]
       [:body {:class "h-screen bg-base-950 text-text-50 font-sans antialiased flex flex-col"}
        [:div {:data-init (str "@get('/agent/" agent-id "/sse')")
               :data-on:online__window (str "@get('/agent/" agent-id "/sse')")}]
        (header-fragment agent-id snap)
        [:div {:class "flex-1 grid h-0 min-h-0"
               :style "grid-template-columns: 1fr 1fr;"}
         (ai-pane-fragment   agent-id snap)
         (html-pane-fragment agent-id snap)]
        (chat-bar-fragment agent-id)
        ;; Re-highlight on every Datastar morph. The patched HTML lands
        ;; via `datastar-patch-elements`; we listen for any added <code>
        ;; node anywhere in the document and call hljs.highlightElement
        ;; on it (cheaper than highlightAll on every mutation).
        [:script (html/raw
                   (str "function seonHighlightAll(){"
                        "if(!window.hljs)return;"
                        "document.querySelectorAll('pre code.language-clojure').forEach(function(el){"
                        "el.removeAttribute('data-highlighted');"
                        "window.hljs.highlightElement(el);});}"
                        ;; marked.js: walk every [data-markdown] container,
                        ;; render its raw text into innerHTML once (then mark
                        ;; it done so re-runs on subsequent mutations skip).
                        "function seonMarkdownAll(){"
                        "if(!window.marked)return;"
                        "document.querySelectorAll('[data-markdown]:not([data-md-done])').forEach(function(el){"
                        "var src=el.getAttribute('data-markdown')||'';"
                        "el.innerHTML=window.marked.parse(src);"
                        "el.setAttribute('data-md-done','1');});}"
                        "document.addEventListener('DOMContentLoaded',function(){"
                        "seonHighlightAll();seonMarkdownAll();});"
                        "new MutationObserver(function(muts){"
                        "var any=false;for(var i=0;i<muts.length;i++){"
                        "if(muts[i].addedNodes && muts[i].addedNodes.length){any=true;break;}}"
                        "if(any){seonHighlightAll();seonMarkdownAll();}"
                        "}).observe(document.body,{subtree:true,childList:true});"
                        ;; Cmd/Ctrl+Enter submits the chat form from anywhere.
                        "document.addEventListener('keydown',function(e){"
                        "if((e.metaKey||e.ctrlKey)&&e.key==='Enter'){"
                        "var f=document.getElementById('seon-chat-form');"
                        "if(f){e.preventDefault();f.requestSubmit();}}});"))]]])))

(defn- agents-index-page
  []
  (let [rows (list-agents-data)]
    (str
      "<!DOCTYPE html>"
      (html/->string
        [:html {:lang "en" :data-theme "phosphor"}
         [:head
          [:meta {:charset "utf-8"}]
          [:title "seon · agents"]
          [:link {:rel "stylesheet" :href "/css/output.css"}]]
         [:body {:class "min-h-screen bg-base-950 text-text-50 font-sans p-4"}
          [:h1 {:class "text-lg font-semibold mb-3"} "agents"]
          (if (seq rows)
            [:table {:class "w-full max-w-3xl text-xs font-mono"}
             [:thead
              [:tr {:class "text-text-400 text-left"}
               [:th {:class "py-1.5 px-2"} "id"]
               [:th {:class "py-1.5 px-2"} "state"]
               [:th {:class "py-1.5 px-2"} "turns"]
               [:th {:class "py-1.5 px-2"} ""]]]
             (into [:tbody]
                   (for [{:seon.agent/keys [id state turn-count]} rows]
                     [:tr {:class "border-t border-base-800 hover:bg-base-900"}
                      [:td {:class "py-1.5 px-2 text-text-100"} id]
                      [:td {:class "py-1.5 px-2"} (comp/status-dot state)]
                      [:td {:class "py-1.5 px-2 text-text-300"} (str turn-count)]
                      [:td {:class "py-1.5 px-2"}
                       [:a {:href (str "/agent/" id)
                            :class "text-amber-500 hover:text-amber-300"} "inspect →"]]]))]
            [:p {:class "text-text-500 italic"} "no agents yet — boot one via the REPL"])]]))))

;; ============================================================
;; Routing — called from serve.cljs router via `route?` + `handle!`.
;; ============================================================

(defn- write-status! [^js res code mime body]
  (.writeHead res code #js {"Content-Type"  mime
                            "Cache-Control" "no-store, no-cache, must-revalidate"})
  (.end res body))

(defn- parse-agent-id
  "Pull the `<id>` out of `/agent/<id>` and `/agent/<id>/sse`. Returns
   `[id rest-of-path]`."
  [path]
  (let [trimmed (subs path (count "/agent/"))]
    (if-let [slash (str/index-of trimmed "/")]
      [(subs trimmed 0 slash) (subs trimmed slash)]
      [trimmed ""])))

(defn route?
  "True iff this `path` is an inspector route. Called from
   `seon.web.serve`'s GET branch to delegate."
  [path]
  (or (= path "/agents")
      (and (str/starts-with? path "/agent/")
           (> (count path) (count "/agent/")))))

(defn- patch-fragment
  "Wrap a hiccup fragment in a datastar-patch-elements SSE payload.
   Inlined here (rather than calling `seon.web.sse/patch-elements`) so
   this ns has no edge into `seon.web.serve` — keeping the require graph
   acyclic since `serve` now requires `inspector`."
  [hiccup]
  (let [html-str (html/->string hiccup)]
    (str "event: datastar-patch-elements\n"
         "data: elements " (str/replace html-str "\n" "\ndata: elements ")
         "\n\n")))

(defn- push-agent!
  "Re-render and write the three morph fragments (header + both panes)
   to every connection watching `agent-id`. Best-effort per-connection."
  [agent-id]
  (try
    (let [snap (snapshot agent-id)
          payloads (str (patch-fragment (header-fragment agent-id snap))
                        (patch-fragment (ai-pane-fragment agent-id snap))
                        (patch-fragment (html-pane-fragment agent-id snap)))
          conns (get @!sse-by-agent agent-id)]
      (doseq [{:keys [res]} conns]
        (try (.write res payloads)
             (catch :default e
               (log/error-console! "seon.web.inspector" "write failed" e))))
      (log/info-console! "seon.web.inspector" "push"
                         {:agent agent-id :conns (count conns)
                          :chars (:char-count snap)}))
    (catch :default e
      (log/error-console! "seon.web.inspector" "push! threw" e))))

;; ============================================================
;; Coalescing — one trailing 100ms timer per agent-id. Bursts within
;; the window collapse into one render.
;; ============================================================

(defonce ^:private !pending (atom {}))

(defn- schedule-push! [agent-id]
  ;; First arrival in the window starts a 100ms trailing timer. Later
  ;; arrivals inside the window just keep `!pending` set; the timer's
  ;; callback drains it.
  (let [was-pending? (get @!pending agent-id)]
    (swap! !pending assoc agent-id true)
    (when-not was-pending?
      (js/setTimeout
        (fn []
          (swap! !pending dissoc agent-id)
          (push-agent! agent-id))
        100))))

;; ============================================================
;; Tx-listener — figure out which agents this tx affects, schedule
;; pushes for the watching ones.
;; ============================================================

(defn- tx-agent-id
  "Read `:seon.db/agent-id` off the tx eid in db-after. Multiple datoms
   may have been emitted but they all share the same tx in a single
   commit, so we just look at the first one."
  [db datoms]
  (when-let [tx (some (fn [d] (:seon.db/tx d)) datoms)]
    (:seon.db/agent-id (d/entity db tx))))

(defn- on-tx
  [{:seon.db/keys [db datoms]}]
  (let [scope-id (tx-agent-id db datoms)
        watching (watching-agents)
        targets  (if (nil? scope-id)
                   watching
                   (filter #(= % scope-id) watching))]
    (doseq [aid targets]
      (schedule-push! aid))))

(defn install!
  "Install the inspector tx-listener. Idempotent — re-installing
   replaces the prior handler."
  []
  (db/listen! {:seon.db/key     ::inspector
               :seon.db/handler on-tx}))

(defn uninstall!
  []
  (db/unlisten! {:seon.db/key ::inspector}))

(defn ^:dev/before-load before-reload []
  (try (uninstall!) (catch :default _ nil)))

(defn ^:dev/after-load after-reload []
  (try (install!) (catch :default _ nil)))

;; ============================================================
;; HTTP handlers — called from seon.web.serve when route? matched.
;; ============================================================

(defn- open-agent-sse! [^js req ^js res agent-id]
  (.writeHead res 200 #js {"Content-Type"      "text/event-stream"
                           "Cache-Control"     "no-cache"
                           "Connection"        "keep-alive"
                           "X-Accel-Buffering" "no"})
  (.write res ": connected\n\n")
  (let [conn {:id (random-uuid) :res res :opened-at (js/Date.)}]
    (add-conn! agent-id conn)
    (log/info-console! "seon.web.inspector" "SSE OPEN"
                       {:agent agent-id
                        :conn-id (str (:id conn))
                        :total (count (get @!sse-by-agent agent-id))})
    (.on req "close"
         (fn []
           (remove-conn! agent-id (:id conn))
           (log/info-console! "seon.web.inspector" "SSE CLOSE"
                              {:agent agent-id :conn-id (str (:id conn))})))
    ;; Send an initial render immediately so the page populates without
    ;; waiting for the next tx.
    (try
      (let [snap (snapshot agent-id)]
        (.write res (patch-fragment (header-fragment agent-id snap)))
        (.write res (patch-fragment (ai-pane-fragment agent-id snap)))
        (.write res (patch-fragment (html-pane-fragment agent-id snap))))
      (catch :default e
        (log/error-console! "seon.web.inspector" "initial render failed" e)))))

(defn handle!
  "Inspector route dispatcher. Returns true if handled."
  [^js req ^js res path]
  (cond
    (= path "/agents")
    (do (write-status! res 200 "text/html; charset=utf-8" (agents-index-page))
        true)

    (str/starts-with? path "/agent/")
    (let [[agent-id rest-path] (parse-agent-id path)]
      (cond
        (str/blank? agent-id)
        (do (write-status! res 404 "text/plain; charset=utf-8" "missing agent id")
            true)

        (= rest-path "/sse")
        (do (open-agent-sse! req res agent-id) true)

        (or (= rest-path "") (= rest-path "/"))
        (do (write-status! res 200 "text/html; charset=utf-8"
                           (inspector-shell agent-id (snapshot agent-id)))
            true)

        :else
        (do (write-status! res 404 "text/plain; charset=utf-8"
                           (str "Not found: " path))
            true)))

    :else false))

