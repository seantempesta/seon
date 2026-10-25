(ns seon.screen
  "The agent's screen: what the user's screen shows of it, as forms the system evaluates in the
  agent's REPL just before each model request (`forms`). Each is an eval like any other,
  recorded by the screen (:seon.eval/by \"screen\"), and sent as the request's last user
  messages, replaced by the next request's (seon.turn, seon.context/request), so they
  never repeat in the conversation. The agent's page joins them as a read of root's cached
  view (notes/design-agent-view-2026-10-24.md section 10, step 4)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [seon.agent :as agent]))

(def forms
  "The screen's forms, evaluated in order before each model request: the agent's page, as root
  shows it (`show`: its peers, status, plan and this run), then its task's live state run fresh
  (my.task/status: the turn of how many, each proof's status, first failing assertion and how to
  run it), then what its branch changes against main (my.branch/changes), then the wiki sections
  nearest its plan as it is now (my.task/related: a line each, and the forms that read one and
  search further) (owner, 2026-10-23: the turn's live state is REPL forms, run fresh each turn;
  2026-10-25: embedding references as explicit evals, refined as the session goes). An agent
  changes what it is shown by redefining this var: rewriting its own interface is an eval, so
  (my.agent/related \"its words\") in place of the last form follows its own query."
  ["(seon.screen/show)" "(my.task/status)" "(my.branch/changes)" "(my.task/related)"])

(defn show
  "The agent's screen: its page as root's render proc holds it, without its conversation (the model
  has it as its messages) and without presentation, at main's t (seon.agent/screen): its task, its
  proofs and its branch's changes as the page's tiles, root's agents. Asked of root, never drawn
  here."
  {:malli/schema [:=> [:cat] :any]}
  [] (agent/screen))

(defn input
  "What this turn's screen forms read (my.task/status): {:task :input}, the task and the turn
  sections' input as this turn's observe measured it (seon.context/turn-input), as
  seon.turn/act! holds them; nil before any turn of this process observed its task."
  {:malli/schema [:=> [:cat] [:maybe :map]]}
  [] (get-in @agent/state [:turn :screen]))
