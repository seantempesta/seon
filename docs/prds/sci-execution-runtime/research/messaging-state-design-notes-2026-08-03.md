---
type: research
status: active
tags: [research, agent, messaging, data-model]
---

# Messaging, state, and reply-norm design — owner-converged notes

Owner-iterated conversation, 2026-08-03 late night. These are converged
design decisions awaiting one PRD; the grounding audit is
[work-identity-messaging-2026-08-03.md](work-identity-messaging-2026-08-03.md).

## Decisions

1. **Explicit send, pure complete — the derived-reply machinery is
   deleted.** `my.message/send` is the ONE delivery mechanism, to agents
   and (once the user entity lands) users, visible in the transcript.
   `my.run/complete note` becomes pure lifecycle + status: it ends the run
   and the note is the agent's public "where I left off". The hidden
   trigger-sender reply derivation, its agent-vs-human branch, and the
   bounce-suppression logic are removed (owner: "the reply stuff is hacky
   and likely complex and not going to work reliably in practice").
   `wait` and `complete` become symmetric: both end the run with an honest
   declared note.

2. **Agent state, two layers, one board.** Live status is DERIVED and
   never stored (working = holds a run; waiting = last disposition
   `:wait`; idle; crashed/stuck = interrupted receipts / fault facts /
   unresponsive ping). Declared state is a fact with provenance: the
   agent's own declaration rides the run boundary (`complete`/`wait`
   values, schema-validated against a DECLARED value set — meaningful
   states, never free-form; growing the vocabulary is schema accretion);
   root's override (`reset`, `disabled`, …) is a transacted decision fact
   with `:seon.db/user` provenance plus the operational repair it implies
   (settle interrupted run, re-arm graph — flow's own verbs). The board is
   one projection over both layers + notes + pending counts, rendered to
   root's context, the web page, and each agent's own prompt line.

3. **The unanswered-asker derivation replaces reply enforcement.** A
   user-triggered run that closes `:completed` with zero outbound messages
   to that user is one query; it renders (a) in the agent's next context
   as reactive derived context (omits itself when a message exists),
   (b) on the board, (c) as the inspect-ai scorer's target once users
   exist (score "did a message reach the asking user", not receipt
   archaeology). Escalation only on live-drive evidence: inline
   run-terminal note first, declared send-or-say-why disposition shape at
   strongest. No hidden auto-reply returns; no moralizing prompt prose.

4. **The reply norm is taught by a self-describing bootstrap form.** The
   bootstrap includes the unreplied-messages query — `;;` comments carry
   the norm and the empty-vs-rows meaning (empty = caught up; rows =
   people waiting, reply before completing). REPLIED? is derived per
   display: an outbound message from me to that sender committed after the
   inbound one — one reply covers all earlier messages from that sender;
   never a stored flag, never resurrected threading. `my.message/reason`
   marks FYI traffic the norm exempts. The message render gains the
   replied column; the same derivation feeds the board and the advisory —
   one definition, three surfaces. One line of db-resident `(help)` prose
   states the norm.

5. **Messages show as last-N back-and-forth** (owner, earlier tonight): no
   forced reply-to-message semantics; the transcript interleaves messages
   and eval results; the full-detail tail (currently hardcoded 6 in
   `seon.render.transcript`) becomes a config dial the bootstrap can name.
   Convergence direction: one run attaches ALL currently-unanswered
   inbound messages (the proven background-results pattern), not one run
   per oldest message. `run/trigger` survives as invisible bookkeeping
   deriving answeredness — never agent-facing reply semantics.

6. **Inspect finding explained**: "agents never answered" evals were a
   mechanism gap, not agent failure — human-triggered runs had NO reply
   path; the completion lived only in the terminal eval receipt
   (work-identity research Part 2). The user entity + explicit send is the
   fix; the scorer moves to message-reaching-user.

7. **Message context is one bootstrap form with the replied column**
   (owner, same night): the agent's conversation view = a single query
   form in its bootstrap — last X messages (inbound AND its own outbound,
   sorted, bounded previews) with derived `:replied?` — where X is a
   LITERAL in the form source, so an agent tunes its own window by editing
   its own starting form (self-modifying context, no config machinery).
   Output is a seq of maps (honest REPL printing). Direction this implies:
   the hand-built transcript renderer's message section becomes redundant —
   message context migrates to form output the agent can see, re-run, and
   modify; eval receipts stay renderer-owned as the REPL session itself.

8. **NO HAND-BUILT CONTEXT SECTIONS — get by on bootstrap forms alone**
   (owner, same night: "I want to get rid of all sections and just see if
   we can get by on the bootstrap forms. hence all the research"). The
   context = `(help)` (itself a form rendering db instruction facts) +
   stable lesson forms with outputs (the cacheable prefix) + volatile
   forms (messages/replied, status, work, unreplied-check) + the agent's
   own append-only REPL session. The transcript renderer's hand-built
   sections (messages, status, waiting-on, work) all dissolve into form
   outputs; the one non-form survivor is the session structure itself
   (prior forms + results ARE the provider conversation). Cache
   consequence: append-only session + stable lesson prefix should RAISE
   the measured 67% hit rate. Risks assigned to live-drive evidence, not
   argument: per-form output sizes vs a global token budget, and model
   orientation without section headers — a failed drive fixes forward via
   a better form or one line of db-resident (help) prose, never a
   renderer resurrection.

9. **THE AGENT SETS ITS OWN PROMPT LINE (readline)** (owner, same night):
   in the bootstrap forms the agent queries its current plan — previous
   1-2 completed items plus what's ahead, nicely formatted through
   declared `:seon.render/ai` and `:seon.render/html` producers — and
   then SETS its readline: everything it always wants visible on every
   output, rendered at the END of the prompt. Cache posture: the readline
   sits after the cache boundary so its churn is free ("it's at the end
   of the prompt so it's fine to bust"), unlike the re-evaluated
   bootstrap forms whose instability busts prefix. Analogy: a
   self-authored PS1 — the agent owns its own prompt decoration through
   an ordinary settable mechanism in its starting forms.

10. **THE ACTIONABLE-OUTPUT FILTER for bootstrap forms** (owner, same
   night, reviewing the curriculum census form — "how is this useful?"):
   every bootstrap form must pass "what would the agent DO differently
   having seen this output?" A wall of counts fails; the capability
   query passes (ten rows you immediately `doc` into). The census lesson
   is cut. Owner also wants recipes to show idiomatic Clojure composed
   on query outputs (sort/take-last/->>), and query forms upgraded to
   the most efficient shapes the Datahike source itself supports (pull
   in :find, :keys, aggregates, index access) with file:line evidence.
   The curriculum agent was resumed against the restored default cluster
   to dramatically revise the report under this filter.

## Still open (dismissed question set — owner will rule when ready)

- The seven work/identity recommendations (user entity shape, from
  generalization, work facts, my.plan scope, vocabulary user=human
  reversing ruling #24 prose).
- The two curriculum helper functions (namespace overview, function
  contract view).
- The bootstrap + work PRD write.
