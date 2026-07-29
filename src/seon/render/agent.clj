(ns seon.render.agent
  "The agent view — an agent's context AS the rendered view of its own
  namespace, at a distance.

  THE OWNER'S RULING, 2026-07-28 post-midnight #2, verbatim: \"I want to
  try building up the WHOLE render system this way. Namespace and
  distance centric context for agents.\" An agent's context IS
  `render(its namespace, distance N)`. This namespace is the PILOT of
  that principle at entity-graph distance — the staged half; code-graph
  hops arrive with N5's derived membership.

  It mirrors `seon.render.root` exactly, and the symmetry is the result
  rather than a coincidence: root is an agent, its page is its block
  set, and an ordinary agent's PROMPT is its block set asked for the
  other kind. So this file is a render function and a vector naming the
  blocks, and everything that makes them a PROMPT already exists —
  `seon.cluster.prompt` is untouched, because the neighbourhood view is
  simply one more block in the membership it already reduces.

  WHAT SHRANK, and this is the funeral (owner ruling: static context
  blocks shrink toward the scaffold). Two prompt blocks were restating
  neighbourhood facts and are gone, their doctrine moved into the family
  lenses where every consumer gets it:

  - `:interruption` → `seon.cluster.run/render-ai`. \"Interrupted at
    form N, k results missing, nothing was retried\" is a fact about a
    RUN. As a context block only the prompt ever saw it; as the run
    family's lens, a page, a debug view and another agent's
    neighbourhood are told the same true thing by the same function.
  - `:continuity` → `seon.cluster.run/render-receipt-ai`. The pause note
    IS the last form's admitted value, already durable in that receipt,
    so reading it back belongs to the receipt rather than to a block
    that queried around it.

  WHAT STAYED, and why it is not the same class: `:identity`,
  `:execution` and `:peers` are SCAFFOLD — who you are, how a reply is
  evaluated, how to address a peer. None is a fact reachable by
  following this agent's connections, and none goes stale, so none is
  something the walk could ever render.

  `:trigger` stayed for a narrower reason, and the derivation corrected
  the guess that produced it. The walk DOES reach the messages sent to
  this agent, so at distance 1 the triggering message's text is already
  in the neighbourhood. What the walk cannot say is WHICH of them this
  run is answering: the cause is the run's creating transaction's
  `:seon.db/trigger` meta, and a transaction is apparatus rather than a
  neighbour (`seon.render.walk/apparatus?`). So the block survives as
  one sentence of selection over facts the view already shows, which is
  a real job, and the overlap is recorded in the pilot's research note
  rather than argued away.

  Crash walk: pure renders over a database value. A kill loses a prompt
  that re-derives."
  (:require [seon.render.block :as block]
            [seon.render.walk :as walk]))

;;; ---------------------------------------------------------------------------
;;; The renders
;;; ---------------------------------------------------------------------------

(defn agent-ai
  "`:seon.render/ai` — one agent, as its neighbours see it.

  The agent family's own lens, declared on `:seon.cluster.agent/agent`
  in `src/seon/schema/run.edn`. Deliberately ONE sentence: an agent
  reached as a neighbour wants a name and whether it is busy, and
  everything else about it is its own neighbourhood's business, one hop
  further out.

  Presence is the state — an agent with no `/run` is idle, and there is
  no status attribute to read."
  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :string]]}
  [unit]
  (when-let [id (get unit :seon.cluster.agent/id)]
    (str "Agent " id
         (if (get unit :seon.cluster.agent/run)
           " is running now."
           " is idle."))))

(defn namespace-ai
  "`:seon.render/ai` — THE PILOT: this agent's world, at a distance.

  One block, and it replaces a growing family of hand-written
  \"tell the agent about X\" projections with the general mechanism: the
  agent's own entity is rendered by its lens, its connections are
  followed one hop per distance, and every neighbour is rendered by ITS
  owner's lens. A run says what a run says, a receipt says what a
  receipt says, an error says what an error says — here and on the page
  and in a debug view, by the same functions.

  THE BAR, and the reason this block is four lines of body: \"write a new
  function to change it.\" To change what an agent sees about its runs,
  an agent writes `seon.cluster.run/render-ai` — one defn, resolved late
  on the next render. To change how far it sees, it puts a different
  `:seon.render/distance` on the request. There is nothing to register
  and no API to learn, and if either statement stops being true the
  design has regressed.

  DISTANCE IS READ HERE and spent by the walk, which is exactly the call
  convention: this projection was CALLED with a hop budget and chose to
  spend it on its neighbourhood. A projection that ignored it would be
  equally correct and would simply reach nothing.

  Nil when the agent has no connections worth a sentence — the walk
  returns a node whose prose is blank, and an agent alone in a fresh
  cluster costs its prompt nothing."
  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :string]]}
  [unit]
  (let [db (get unit :seon.db/db)
        agent-id (get unit :seon.cluster.agent/id)
        caps (get unit :seon.sci.admit/caps)]
    (when (and db agent-id caps)
      (when-let [text
                 (walk/prose
                  (walk/neighborhood
                   (cond-> {:seon.db/db db
                            :seon.render.walk/lookup
                            [:seon.cluster.agent/id agent-id]
                            :seon.render/kind :seon.render/ai
                            :seon.render/floor `block/data-prose
                            :seon.sci.admit/caps caps}
                     (get unit :seon.render/distance)
                     (assoc :seon.render/distance
                            (get unit :seon.render/distance)))))]
        (str "Your namespace, as it stands right now:\n" text)))))

;;; ---------------------------------------------------------------------------
;;; The seed
;;; ---------------------------------------------------------------------------

(def blocks
  "An ordinary agent's default block set — CONTENT, not a classification
  rule, and the same vector-of-block-data shape root's seed uses.

  THE SCAFFOLD PLUS THE VIEW. Three anchor blocks say what the walk can
  never derive (who you are, how your reply is evaluated, how to address
  a peer), one dynamic block says what you were asked, and `:namespace`
  is everything else — your runs, your receipts, your messages, your
  errors — rendered by their owners at the requested distance.

  Priorities leave gaps so a block can be inserted between two without
  renumbering anything. `:namespace` sits in the `:dynamic` band ahead
  of the trigger, so the agent reads its world and then the question."
  [{:seon.render.block/name :identity
    :seon.render.block/band :anchor
    :seon.render.block/priority 0
    :seon.render/ai 'seon.context/identity-ai}
   {:seon.render.block/name :execution
    :seon.render.block/band :anchor
    :seon.render.block/priority 10
    :seon.render/ai 'seon.context/execution-ai}
   {:seon.render.block/name :peers
    :seon.render.block/band :anchor
    :seon.render.block/priority 20
    :seon.render/ai 'seon.context/peers-ai}
   {:seon.render.block/name :namespace
    :seon.render.block/band :dynamic
    :seon.render.block/priority 80
    :seon.render/ai `namespace-ai}
   {:seon.render.block/name :trigger
    :seon.render.block/band :dynamic
    :seon.render.block/priority 90
    :seon.render/ai 'seon.context/trigger-ai}])

(defn seed-tx
  "Transaction data installing an ordinary agent's block set. PURE, and
  IDEMPOTENT — upsert by name, so a reboot rewrites the same blocks and
  any block the agent added itself survives untouched. Empty when
  nothing would change, the converged-means-zero-writes rule."
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
