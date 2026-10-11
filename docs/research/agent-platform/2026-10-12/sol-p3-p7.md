# P3/P7 lane landing

Sending previously opened the recipient's branch and transacted there. P3 now prepares a message against the sender's held database and offers that value to the roster's inbox without parking. The recipient owns its write; the sender writes only a declared kept copy. The simpler composition is core.async's existing nonblocking offer and the existing writer, with no transport store or relay.

## Boundaries

`src/seon/cluster/agent.clj` is held by `p4-branch-writes`. It was not edited by this lane. `sol-p3-p7-mailbox.patch` carries the mailbox's inbox, receipt write, episode signal, configured buffer and lifecycle closure. Receipt uses the message's existing listened datom to wake the same mailbox and signal the episode once, instead of emitting a second episode alongside the listener. It is a handoff, not installed evidence: P7, root receipt, repeated receipt and the offer-to-write 30 ms bound remain unproven. Until the owner applies P7, existing hosted graphs have no inbox and P3 returns undeliverable. `db<->flow`'s running/no-graph rehosting belongs to that owner too.

The transport's `:seon.message/undeliverable` names recipient, task execution intent (typed `:unknown` when this database has no open attempt), and `:no-graph`, `:closed` or `:full`. `:seon.message/accepted-result` means offered, not durably received. Input identity is preserved for a resend. Existing inbound-message unknown-recipient declarations remain for the outside-message entrance.

The received envelope still carries existing peer refs (sender and caused-by). A recipient branch that lacks these referenced identities needs a reaching receipt proof before P7 lands; this lane does not infer successful storage from a successful offer.

## Evidence

Default remained running at pid 94708, start instant 2026-10-11T21:22:23.023Z. Operator and MCP both reported degraded: one errored receipt, one unowned namespace and ten untested-hot warnings. The lane's first SCI current-state form took 24 ms / 8.9 MB; the full request took 367.4 ms. No provider run or default reload was used.

Red behavioral run `357609a18ac7`, candidate `ee907d30ca5be4f565ced4d193ea70a707d62abf`: two tests, 17 failures, zero errors, 2301 ms. The failures show ordinary sends advancing the basis, no offered value and no undeliverable outcome. Earlier preparation attempts were refused for a wrong test-call arity and an unquoted task annotation; those were corrected before the behavioral red.

First green run `def8a66f9672`, candidate `8524f38a504e584532dce625a105e9f56747efab`: two tests, 19 passes, zero failures/errors, 2225 ms. Publication 11338 ms, adoption 1179 ms, throwaway boot 22923 ms (readiness 15645 ms), whole check 36373 ms. These are candidate/platform acquisition costs, not a message-offer latency claim. Final gate run `25f02c809057`, candidate `67b2740e3e8a2f07af455605cdbe14e82e0e9732`: three tests, 35 passes, zero failures/errors, 2892 ms; warm resend under 2 ms with zero writer and recipient-connection calls. Publication 13230 ms, adoption 1367 ms, throwaway boot 25970 ms, whole check 42370 ms. Landed as `c1e0c97c9`.

Full lint `src script test --cache false`: zero errors, 1552 warnings (6470 ms) before the final measurement assertion; the shared tree subsequently had seven unresolved references after another lane removed task/main and task/main-connection. The HEAD-plus-slice candidate lint passed with zero errors / 1552 warnings, 5914 ms in the commit gate and 7509 ms in a separate archive. That foreign cut was excluded from the candidate. No foreign source or shared index was changed.

## Dependency seams and work

Pinned core.async `dc35f3e0d7bc2eef502e77982f48641f025c8051`: `clojure.core.async/offer!` never blocks, channel `put!` returns false when closed and nil when it cannot immediately accept. Flow's in-ports join its proc's read set; its transition hook owns stop cleanup. Malli `dd13ff81139159778edb0a67b491eccc0baa991d`: named contracts compile through the packaged projection (`malli.core/validator`); error construction uses `seon.error.refusal/error`. Datahike `61bca1edd6bcef079f5c47e4f5f75df30549934d`: the existing `seon.db/transact!` writer remains the only durable operation. Sender preparation makes indexed reads of config, active turn and recipient; roster lookup is proportional to hosted branch rosters, offer is constant work, receipt/kept-copy work is proportional to one message. No recipient connection is acquired.
