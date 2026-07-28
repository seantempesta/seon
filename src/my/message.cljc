(ns my.message
  "What an agent says to another agent: one value, nothing else.

  THE HANDS ON A SUBSTRATE THAT ALREADY WORKS. Agent-to-agent messaging
  is not new machinery here — the loop has woken on
  `:seon.cluster.message/to` since N3, and `seon.error/commit-tx`
  already commits messages that open real runs. What was missing was
  the agent-facing half: an agent could be messaged and could not
  message. This namespace is that half, and it is deliberately the
  smallest thing that could be it.

  A VALUE, NOT AN EFFECT — the same shape as `my.run`'s two
  dispositions, and for the same reason. `send` commits nothing, reads
  nothing, and needs no capability: it returns a map, the form's value
  carries it out through the one admission gate, and the LOOP commits
  the fact in its own terminal transaction. Nothing about messaging
  happens inside an eval.

  WHY NOT THE GUARDED DOOR. The three agent-facing shapes are values
  the driver interprets, capability REQUESTS through one door, and
  durable FACTS the driver commits. A message is the third: its entire
  effect is a transaction the loop is already making, against the
  database the loop already holds. Routing it through a door that does
  not exist yet (`seon.effect`) would buy nothing and cost the door's
  whole design — and the quarry shows what the effectful shape costs
  when you take it: `src-old/seon/agent/message.cljc` is 590 lines of
  `^:async message!`, ALS-derived sender identity, per-call authority
  queries and a stored hop counter, all of it inside the eval. This is
  eleven lines of pure function.

  ONE FUNCTION, AND FAN-OUT IS THE VECTOR. `(send \"bob\" \"…\")` is one
  message; `[(send \"bob\" \"…\") (send \"carol\" \"…\")]` is two, because a
  form's VALUE is the only channel and a vector is what Clojure already
  says for several. There is no `send-many`.

  COMPOSITION: a form may send, and a LATER form may complete. They are
  different forms with different values, so the question \"can a turn
  both send and finish?\" answers itself — the fold reads every form's
  value, not only the last one. What a single form cannot do is both,
  and that is the shape being honest rather than a limitation: one
  value means one instruction.

  ERRORS ARE VALUES HERE TOO. A blank recipient or blank content
  returns the ONE registered flat error value rather than throwing.
  Whether the recipient EXISTS is not asked here — that is a fact about
  the database, this function is pure, and the driver answers it where
  the answer lives.

  The error value carries its `:seon.error/kind`, because
  `:seon.error/value` REQUIRES one and a declared output schema that
  the error path violates is a contract nobody is keeping. `my.run`'s
  two dispositions return a bare `:seon.error/message` map and are
  therefore outside their own declared output today; that is filed
  (`docs/seon/issues/`), not copied.

  Crash walk: no durable state. A kill loses a map on a dead thread;
  nothing was sent, because nothing is ever sent from in here."
  (:refer-clojure :exclude [send])
  (:require [clojure.string :as str]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — src/seon/schema/message.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; The one function
;;; ---------------------------------------------------------------------------

(defn send
  "Address `content` to the agent named `to`. Returns the message VALUE.
  Nothing is delivered by calling this. Return the value — as a form's
  result, alone or in a vector with others — and the run loop commits
  it, which wakes the recipient. Shadowing `clojure.core/send` is
  deliberate: that function addresses a Clojure agent, this one
  addresses a Seon agent, and having both callable under one plain verb
  in an agent's namespace is the confusion, not the fix.
  A blank or non-string argument returns the ONE registered flat error
  value — an agent mistake answers, never throws."
  {:malli/schema [:=> [:cat :my.message/to :my.message/content]
                  [:or :my.message/message :seon.error/value]]}
  [to content]
  (cond
    ;; agent-facing: a wrong TYPE is an agent mistake too, and
    ;; `str/blank?` on a non-string would throw out of the one place
    ;; that must not throw
    (or (not (string? to)) (str/blank? to))
    {:seon.error/kind ::no-recipient
     :seon.error/message
     "send needs the id of the agent to message, as a string."}

    (or (not (string? content)) (str/blank? content))
    {:seon.error/kind ::no-content
     :seon.error/message
     "send needs the message to deliver, as a string."}

    :else {:my.message/to to
           :my.message/content content}))
