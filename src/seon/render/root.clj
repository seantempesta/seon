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
            [seon.problems :as problems]
            [seon.render.block :as block]))

;;; ---------------------------------------------------------------------------
;;; The renders
;;; ---------------------------------------------------------------------------

(defn header-html
  "Cluster identity and a live count. The quiet strip at the top."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  (let [db (:seon.db/db unit)
        agents (count (d/q '[:find ?a :where [?a :seon.cluster.agent/id _]] db))
        runs (count (d/q '[:find ?r :where [?r :seon.cluster.run/id _]] db))]
    [:header {:id (block/surface-id :header) :class "seon-root-header"}
     [:span {:class "seon-root-mark"} "◆"]
     [:span {:class "seon-root-name"} "seon"]
     [:span {:class "seon-root-stat"} (str agents " agents")]
     [:span {:class "seon-root-stat"} (str runs " runs")]]))

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
     [:h2 {:class "seon-root-heading"} "agents"]
     (if (empty? agents)
       [:p {:class "seon-root-empty"} "no agents yet"]
       [:ul {:class "seon-root-list"}
        (for [id agents]
          [:li {:class "seon-root-agent"}
           [:a {:class "seon-root-link" :href (str "/agent/" id)} id]
           [:a {:class "seon-root-drill"
                :href (str "/data?path="
                           (java.net.URLEncoder/encode
                            (pr-str [:seon.cluster.agent/id id]) "UTF-8")
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
     [:h2 {:class "seon-root-heading"} "messages"]
     (if (empty? messages)
       [:p {:class "seon-root-empty"} "nothing said yet"]
       [:ul {:class "seon-root-list"}
        (for [[_ content to-id] messages]
          [:li {:class "seon-root-message"}
           [:span {:class "seon-root-to"} (str "→ " to-id)]
           [:span {:class "seon-root-said"} content]])])]))

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
   [:h2 {:class "seon-root-heading"} "problems"]
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
  [{:seon.block/name :header
    :seon.block/priority 0
    :seon.render/html `header-html}
   {:seon.block/name :problems
    :seon.block/priority 10
    :seon.render/html `problems-html}
   {:seon.block/name :agents
    :seon.block/priority 20
    :seon.render/html `agents-html}
   {:seon.block/name :messages
    :seon.block/priority 30
    :seon.render/html `messages-html}])

(defn seed-tx
  "Transaction data installing root's block set. PURE, and IDEMPOTENT.

  Upsert by name, so a reboot rewrites the same four blocks rather than
  accumulating them, and an agent's own edits to any OTHER block survive
  untouched. Returns empty tx-data when nothing would change, which is
  the converged-means-zero-writes rule the reconciler already proved."
  {:malli/schema [:=> [:cat :any :seon.cluster.agent/id] [:vector :any]]}
  [db agent-id]
  (let [installed (into {}
                        (map (juxt :seon.block/name identity))
                        (block/blocks db agent-id))]
    (if (every? (fn [wanted]
                  (= wanted (get installed (:seon.block/name wanted))))
                blocks)
      []
      (block/install-tx db agent-id blocks))))
