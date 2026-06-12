(ns seon.agent.turns
  "The agent's turn budget as CONTEXT — the `<turns>` countdown
   (`:turns`, substrate-default-ctx priority 90, just above the
   prompt tail for salience): ONE line naming the turn the agent is
   about to take and the cap the loop enforces
   (`seon.agent/run-agentic-loop!` halts at `seon.ctx/turns-cap`).
   An agent cannot converge on a budget it cannot see (opus-live-tests
   2026-06-12 limitation 11: s12's agent burned all 20 turns
   researching and judged 40 on an incomplete reply) — a visible
   meter is the affordance the model self-moderates on.

   Reactive-context principle: a pure function of the render's db
   value. The turn number and cap are DERIVED at render time
   (`seon.ctx/turns-since-inbound` over the message + turn log;
   `seon.ctx/turns-cap` reads `:seon.agent/turns-cap` entity data,
   default `seon.ctx/default-turns-cap`). Renders NOTHING when the
   agent is idle — no unanswered inbound message means no budget is
   burning, and the section vanishes; nothing stored, nothing to
   acknowledge."
  (:require
    [seon.ctx :as ctx]
    [seon.db :as db]))

(defn turns-block
  "The `<turns>` countdown for `agent-id` in db value `db` — \"\" when
   the agent is idle (no unanswered inbound message — `inbox 0` in the
   status line). MID-TASK = at least one inbound message newer than the
   agent's latest outbound reply: the same window
   `run-agentic-loop!`'s cap policy counts.

   The rendered turn number is the turn ABOUT to run:
   `turns-since-inbound` counts already-opened turns after the latest
   inbound, and the prompt renders before `with-turn!` opens the next
   one — so turn N's prompt sees N-1 counted turns."
  {:malli/schema [:=> [:catn [::db :seon.db/db-val] [::agent-id :string]]
                  :string]}
  [db agent-id]
  (let [input {:seon.agent/id agent-id :seon.db/db db}
        inbox (ctx/inbox-count (ctx/messages input) agent-id)]
    (if (pos? inbox)
      (let [n   (inc (ctx/turns-since-inbound input))
            cap (ctx/turns-cap agent-id db)]
        (str "<turns>\n"
             "turn " n " of " cap " — reply to the user before the cap; "
             "an incomplete honest answer beats a capped silence.\n"
             "</turns>"))
      "")))

(defn turns-section
  "Context-section fn (`:turns`, substrate-default-ctx priority 90):
   [[turns-block]] for the CALLING agent — absent `:seon.db/db`
   defaults to the current conn, the same convention as every sibling
   section fn."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (turns-block (or db @db/*conn*) id))
