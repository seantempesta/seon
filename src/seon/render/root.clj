(ns seon.render.root
  "The root view — the cluster looking at itself, as ordinary blocks.

  THE OWNER'S RULING MADE LITERAL: the root interface is really just
  different context blocks returning `:seon.render/ai` and
  `:seon.render/html`. So this namespace contains no page, no layout
  engine and no root-specific mechanism — it is four render functions
  and a vector naming them, and everything that makes them a PAGE
  already exists.

  It is worth saying what is NOT here, because the absence is the
  result: no root route, no root template, no root controller, no
  special-casing anywhere in `seon.render.block` or `seon.render.web`.
  Root is an agent; its page is its block set; `/agent/{id}` renders any
  other agent through the identical code. If a root-shaped branch ever
  appears downstream of this file, the design has regressed.

  THE AGENT LIST LINKS INTO `/data`, and that is a deliberate economy
  rather than a shortcut. The drill already renders any value with
  paging, breadcrumbs and a shareable cursor, so \"show me this agent's
  facts\" needs no page of its own — it needs a link. Every bespoke
  inspector page we do not write is one that cannot drift from the
  facts.

  Crash walk: pure renders over a database value. A kill loses a page
  that re-derives."
  (:require [datahike.api :as d]
            [seon.oversight :as oversight]
            [seon.problems :as problems]
            [seon.render.block :as block]))

;;; ---------------------------------------------------------------------------
;;; The renders
;;; ---------------------------------------------------------------------------

(defn heading
  "One section's channel header: a label, a rule, and its count.

  THE RULE IS THE PAGE'S ONE STRUCTURAL DEVICE, and it earns its place
  by carrying information rather than decorating: the count on the right
  is the section's real cardinality, so \"how many agents\" is answered
  before the eye reaches the list, and a section that has nothing to
  count omits the number rather than printing a zero it had to invent.
  The rule itself is a CSS pseudo-element — markup says label and count,
  which is all a reader of this code needs to know.

  A shared function rather than a copied vector, because three sections
  differing by a string is one section rendered three times."
  {:malli/schema [:function
                  [:=> [:cat :string] :seon.render/hiccup]
                  [:=> [:cat :string [:int {:min 0}]] :seon.render/hiccup]]}
  ([label] [:h2 {:class "seon-root-heading"}
            [:span {:class "seon-root-heading-label"} label]])
  ([label counted]
   [:h2 {:class "seon-root-heading"}
    [:span {:class "seon-root-heading-label"} label]
    [:span {:class "seon-root-heading-count"} (str counted)]]))

(defn- stat
  "One masthead readout: a dim label and a tabular value, in that order.
  Label first because the label is what you scan for; the value is what
  you read once you have found it."
  [label value]
  [:span {:class "seon-root-stat"}
   [:span {:class "seon-root-stat-label"} label]
   [:span {:class "seon-root-stat-value"} (str value)]])

(defn header-html
  "Cluster identity and a live count. The quiet strip at the top.

  IT NAMES ITS CLUSTER, which is not decoration: several clusters run in
  one JVM and each answers on its own derived port, so a tab that only
  said `seon` left the one question a second tab raises — which cluster
  am I looking at? — answerable only by reading the URL. The name is an
  ordinary config fact, read at the same basis as everything else."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  (let [db (:seon.db/db unit)
        cluster (d/q '[:find ?cluster . :where [_ :seon.config/cluster ?cluster]]
                     db)
        agents (count (d/q '[:find ?a :where [?a :seon.cluster.agent/id _]] db))
        runs (count (d/q '[:find ?r :where [?r :seon.cluster.run/id _]] db))]
    [:header {:id (block/surface-id :header) :class "seon-root-header"}
     [:span {:class "seon-root-mark"} "◆"]
     [:span {:class "seon-root-name"} "seon"]
     ;; absent rather than empty: a cluster whose name is not yet a fact
     ;; prints no separator and no blank, it just says less
     (when cluster [:span {:class "seon-root-cluster"} cluster])
     [:span {:class "seon-root-stats"}
      (stat "agents" agents)
      (stat "runs" runs)]]))

(defn agents-html
  "Every agent in the cluster, each linking to its page and its facts.

  TWO LINKS PER AGENT and no third: its own page — the same
  `/agent/{id}` any agent gets — and a `/data` drill cursored at that
  agent's entity. The drill is why there is no inspector here.

  Ordered by id so two derivations of one database value are the same
  page. An empty cluster says so rather than rendering an empty list,
  because a bare heading with nothing under it reads as broken."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  (let [db (:seon.db/db unit)
        agents (sort (map first
                          (d/q '[:find ?id :where [?a :seon.cluster.agent/id ?id]]
                               db)))]
    [:section {:id (block/surface-id :agents) :class "seon-root-agents"}
     (heading "agents" (count agents))
     (if (empty? agents)
       [:p {:class "seon-root-empty"} "No agents yet. Create one to begin."]
       [:ul {:class "seon-root-list"}
        (for [id agents]
          [:li {:class "seon-root-agent"}
           [:a {:class "seon-root-link" :href (str "/agent/" id)} id]
           [:a {:class "seon-root-drill"
                :href (str "/data?entity="
                           (java.net.URLEncoder/encode
                            (pr-str [:seon.cluster.agent/id id]) "UTF-8")
                           "&path="
                           (java.net.URLEncoder/encode (pr-str []) "UTF-8")
                           "&offset=0")}
            "facts"]])])]))

(defn messages-html
  "The cluster's conversation, newest last.

  DERIVED, never a stored thread — the messaging owner's own rule. This
  reads the message facts in commit order and shows who said what to
  whom, which is the whole conversation without anybody maintaining one.

  It is deliberately a FLAT list here rather than the grouped transcript
  the agent page will want. Root is looking at the cluster, not at one
  conversation, and the transcript's grouping problem is its own rung."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  (let [db (:seon.db/db unit)
        messages (->> (d/q '[:find ?message ?content ?to-id
                             :where
                             [?message :seon.cluster.message/content ?content]
                             [?message :seon.cluster.message/to ?to]
                             [?to :seon.cluster.agent/id ?to-id]]
                           db)
                      ;; entity id ascending IS commit order for facts
                      ;; committed in sequence, and it is stable
                      (sort-by first))]
    [:section {:id (block/surface-id :messages) :class "seon-root-messages"}
     (heading "messages" (count messages))
     (if (empty? messages)
       [:p {:class "seon-root-empty"} "No messages yet. Send one to an agent."]
       [:ul {:class "seon-root-list"}
        (for [[_ content to-id] messages]
          [:li {:class "seon-root-message"}
           [:span {:class "seon-root-to"} (str "→ " to-id)]
           [:span {:class "seon-root-said"} content]])])]))

(defn tokens-html
  "A per-run token counter updating live while a model call streams.

  An ordinary block reading the TRANSIENT `:seon.ai/partial` the render
  pass admitted for an unsettled run — channel-borne presentation,
  never a fact (F2 §2, seal revision 2026-07-29). The fact that its
  value changes twenty times a second is the pipeline's problem rather
  than its own, which is the point of the exercise: nothing here knows
  it is high-churn, and nothing here queries, because there is no row
  to query."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  (let [snapshot (:seon.ai/partial unit)]
    ;; `seon-stream-strip` is carried by BOTH streaming blocks so the
    ;; counter and the reply read as one instrument strip at the foot of
    ;; the page. They remain two independent morph targets — the shared
    ;; class is presentation, never a container, because a container
    ;; would make one morph carry the other's bytes.
    [:div {:id (block/surface-id :tokens)
           :class "seon-stream-strip seon-stream-tokens"}
     [:span {:class "seon-stream-label"} "tokens"]
     [:span {:class "seon-stream-count"}
      (str (or (:seon.ai/tokens snapshot) 0))]]))

(defn text-html
  "The model's reply streaming into the interface as it generates.

  Deliberately the same shape as the counter: two exercises, one
  mechanism, no streaming-specific render path. The blinking cursor is
  CSS on an empty span rather than a character in the text, so the
  reply's bytes are exactly the model's and a copy-paste does not pick
  up decoration. When nothing streams—or the run's terminal fact has
  superseded its channel presentation—the unit carries no
  `:seon.ai/partial` and the block says idle. No channel value means
  done; the settled fact's repaint replaces the temporary text."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  (let [text (:seon.ai/text (:seon.ai/partial unit))]
    [:div {:id (block/surface-id :reply)
           :class "seon-stream-strip seon-stream-reply"}
     (if text
       [:span {:class "seon-stream-text"} text]
       [:span {:class "seon-stream-idle"} "idle"])
     (when text [:span {:class "seon-stream-cursor"} ""])]))

(defn problems-html
  "The problems strip. COMPOSES the landed owner rather than re-deriving.

  Liveness rides on the unit: which processes are alive is the one thing
  a database cannot answer, and `seon.problems/block` refuses legibly
  rather than guessing. Boot puts the current process there, which is
  the honest answer for a single-process cluster and becomes a real set
  when a supervisor knows more."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  [:section {:id (block/surface-id :problems) :class "seon-root-problems"}
   (heading "problems")
   (problems/block unit)])

;;; ---------------------------------------------------------------------------
;;; The seed
;;; ---------------------------------------------------------------------------

(def blocks
  "Root's default block set — CONTENT, not a classification rule.

  A vector of block data, installed at boot through the ordinary
  `install-tx` upsert, so an agent that later removes or reorders one is
  editing the same collection boot wrote. Priorities leave gaps so a
  block can be inserted between two without renumbering anything.

  This lives in code rather than in the cluster manifest for one honest
  reason: the config reconciler carries scalar DIALS into facts, and a
  block set is a collection of entities. `ui.md` says the manifest
  declares the initial block data, and it should — that is an accretion
  to the reconciler, not something to fake with an unstorable dial."
  [{:seon.render.block/name :header
    :seon.render.block/priority 0
    :seon.render/html `header-html}
   {:seon.render.block/name :problems
    :seon.render.block/priority 10
    :seon.render/html `problems-html}
   {:seon.render.block/name :fleet-oversight
    :seon.render.block/priority 15
    :seon.render.block/band :dynamic
    :seon.render/ai `oversight/block-ai
    :seon.render/html `oversight/block-html}
   {:seon.render.block/name :agents
    :seon.render.block/priority 20
    :seon.render/html `agents-html}
   {:seon.render.block/name :messages
    :seon.render.block/priority 30
    :seon.render/html `messages-html}
   ;; the two streaming exercises, seeded rather than dark (F2 R3, the
   ;; block-seed decision): the highest-churn thing in the system rides
   ;; the same per-block morph every other surface gets, and now that it
   ;; needs no facts either, the live page is the standing proof
   {:seon.render.block/name :tokens
    :seon.render.block/priority 40
    :seon.render/html `tokens-html}
   {:seon.render.block/name :reply
    :seon.render.block/priority 50
    :seon.render/html `text-html}])

(defn seed-tx
  "Transaction data installing root's block set. PURE, and IDEMPOTENT.

  Upsert by name, so a reboot rewrites the same blocks rather than
  accumulating them, and an agent's own edits to any OTHER block survive
  untouched. Returns empty tx-data when nothing would change, which is
  the converged-means-zero-writes rule the reconciler already proved."
  {:malli/schema [:=> [:cat :any :seon.cluster.agent/id] [:vector :any]]}
  [db agent-id]
  (let [installed (into {}
                        (map (juxt :seon.render.block/name identity))
                        (block/blocks db agent-id))]
    (if (every? (fn [wanted]
                  (= wanted (get installed (:seon.render.block/name wanted))))
                blocks)
      []
      (block/install-tx db agent-id blocks))))
