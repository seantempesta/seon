---
type: landing
lane: custody-authority-opus
status: "stopped before production edits: owner decision needed (three priced options below)"
created: 2026-09-26
scope: bundle B2, custody half (loose-threads §1 B5a); the REPL-entry render half was not touched
---
# Custody authority: stopped for an owner ruling

**What exists.** Agent evaluations bind `seon.db/*conn*` through `db/call-with-custody`
(`sci/eval.clj:4128`). The writer already refuses a foreign branch when that binding is present
(`db.clj:485-520 foreign-connection-error`). Connections are conjured from global state by
`db/branch-connection` (`db.clj:347`, Datahike's `connections/active-connection`),
`seon.cluster.boot/connection` (`boot.clj:549`, `running-instances`) and
`seon.cluster.registry/active-branch-connection`. All three are reachable from agent SCI, and so
are the custody scopes `db/call-with-custody` and `db/call-without-custody`.

**Principal approach (not yet landed).** Held custody becomes a positive value that no nested
scope can widen. The writer and the connection owners decide against that value.

## Broken things first

1. **A branch agent widens its own custody to the cluster's branch** (verified live, see Evidence).
   `call-with-custody` rebinds `*conn*` to any connection, including a conjured one. After that,
   the elided `(seon.db/db)` answers `:cluster-default` and `foreign-connection-error` compares the
   cluster with itself. So the existing writer check cannot hold. The design's claim that "the blob
   and registry writers close transitively" (round 3, Option 2) is false while this scope widens.
2. **A check keyed on `*conn*` cannot tell agent code from the host.** `call-without-custody` is
   callable from agent SCI and clears `*conn*`. Under it, "no custody" looks exactly like a JVM REPL,
   so a refusal in `branch-connection` or `boot/connection` that reads `*conn*` is bypassed in one
   form. The owner-level rule needs a separate held marker that is bound by the evaluation and never
   cleared by a nested scope. That marker needs a one-line hunk in `sci/eval.clj:4128`, which goes
   through root.
3. **Delivery conflicts with the law.** The only `src` callers of `branch-connection` are
   cross-branch delivery owners, and all three widen custody on purpose:
   - `cluster/message.clj:727,746`: `send!` goes to the recipient's home (fdd9926e8).
   - `my/issue.clj:16,45-60`: a branch agent's `add!`/`tests!`/`status` go to root's home. This is
     e423e41c7, committed 29 s after the law (671037781).
   - `fault.clj:142-152,169`: fault copies to the cluster branch.

   The law says an isolated agent's database changes land "only on its own private fork, so nothing
   escapes by default". These owners exist so that things do escape. Making `branch-connection` or
   `call-with-custody` refuse outside held custody breaks all three, and with them the dogfood
   propose-merge path (`my.program/propose-merge!` asks root through a message). Choosing which way
   this goes is a genuinely new decision that crosses owners (message.clj, my/issue.clj and fault.clj
   are not this lane's files). CLAUDE.md step 4 says to stop and price three options.
4. **Capability handlers run without admission.** `seon.shell.jvm/run` is private, but it resolves
   and runs from branch SCI: it reached its contract check (Evidence). There are four handler owners:
   `fs/jvm`, `shell/jvm`, `edit/jvm` (B4 retires it) and `web/jvm`. Only `shell/jvm` is this lane's
   file. Inside an agent evaluation, `effect/*request-context*` is already bound (`sci/eval.clj:4139`),
   so "the context `dispatch` sets" does not by itself separate an admitted call from a direct one.
   Admission must mark the context with its opened receipt id (`effect.clj:813`), and each handler
   must refuse without it. Alternatively, declared handlers (`:seon.program/capability-of`) could be
   left out of agent SCI at the kernel, which is a held file.
5. **The refusal teaches the hole.** `db.clj:325` tells every elided read with no connection to call
   `(seon.cluster.boot/connection "default")`. That text reaches agents.
6. **Test members conjure the live cluster.** `clean_world_test.clj:22` calls `boot/connection`
   inside a member. Under any option this refuses when the member runs from an agent's custody.

## Three priced options (simplest first)

Every option includes:
- the held marker: `db/*held*`, bound once by the evaluation; `call-without-custody` narrows `*conn*`
  but leaves it set;
- `call-with-custody` refusing a different connection while one is held;
- `foreign-connection-error` and the blob writer checking against the held marker;
- `boot/connection` refusing outside held custody, with root's held cluster still answering;
- the admission marker for handlers;
- the `db.clj:325` text fixed.

The common core is about +35 src (db ≈20, boot ≈6, effect ≈5, shell ≈4), 1 line in `sci/eval.clj`
via root, and about +45 test. Each refusal is a declared `:seon.error` mistake that names the branch
and the held custody.

**Option 1: refuse delivery out of a branch; root reads its branches.**
- Changes: branch-agent `send!` to another home, `my.issue` home writes and fault copies refuse by
  name, or copy only from the host with no custody. Root's custody covers its cluster's branches, so
  merge-time reads still work. `branch-connection` becomes private to `db.clj`.
- Deletes: the custody switches (message.clj:746, my/issue.clj:45-60, fault.clj:147-150), about −30.
- Guarantee: the law holds literally; nothing escapes an isolated fork.
- Cost: core plus about +5 net in three foreign files (message.clj and my/issue.clj are B3's or a
  running lane's).
- Sacrifice: the open issue `a-message-sent-from-a-branch-agent-never-reaches-the-cluster` resolves
  as "refused by name", and propose-merge from a branch has to reach root some other way (root reads
  the branch at merge). This reverses the cross-branch halves of fdd9926e8 and e423e41c7.

**Option 2: declared delivery, decided at the writer.**
- Changes: entity schemas for message, issue and fault occurrence declare themselves as deliveries.
  The writer admits a write outside held custody only when every entity in the transaction is a
  declared delivery bound for the held cluster's home branch. The writer resolves that connection
  itself. Delivery owners hand over a branch name, never a connection, and `branch-connection`
  leaves the public API.
- Deletes: the three custody switches and the public conjurer, about −35.
- Guarantee: an isolated agent's writes stay on its fork, except declared deliveries.
- Cost: core plus about +30 (writer admission, schema flags in three schema files).
- Sacrifice: "nothing escapes" becomes "nothing but declared deliveries", which needs the owner's
  wording in AGENTS.

**Option 3: delivery is root pulling.**
- Changes: branches write messages, issues and faults on their own fork. Root's execution, which holds
  its branches' roster, listens with `d/listen` on each held branch and copies the facts addressed to
  cluster agents. That is the "ordinary route" the law names.
- Deletes: the same switches, about −35.
- Guarantee: the purest custody model; root takes only what it wants.
- Cost: core plus about +80 in wake.clj, message.clj and agent.clj (bundle B3 files). Hours of work,
  plus one listener per held branch.
- Sacrifice: delivery latency, and a listener lifecycle to own.

**Recommendation: Option 1.** It is the smallest, it is the owner's 2026-09-26 wording taken
literally, and the message issue already accepts "refused by name". Option 2 is next best if the
dogfood propose-merge path has to keep working from a branch before merge.

**Correction to the design doc** (not edited here): round 3's Option 2 says "the blob and registry
writers take a connection or store, so they close transitively". That holds only once the custody
scopes stop widening (items 1 and 2). As written, a single check in `branch-connection` or
`boot/connection` closes nothing.

## Evidence (MCP SCI on disposable branch `custody-authority-opus`, default pid 7000, HEAD 5bed888d0)

```clojure
;; own branch, conjured cluster connection, boot connection
{:own :custody-authority-opus, :conn-var false, :other-branch :cluster-default,
 :boot :cluster-default, :same-as-other true}
;; seon.db/*conn* reads nil in SCI (a copied var) although the host binding exists: SCI `binding`
;; cannot forge host custody, so a host-side held marker is sound.

(seon.shell.jvm/run {:my.shell/argv ["/usr/bin/true"]} {})
;; => refused only by its contract at [:my.shell/cwd]: the private handler resolves and is invoked.

(let [c (seon.db/branch-connection (seon.db/db) :cluster-default)]
  {:without (seon.db/call-without-custody #(seon.db/q '[...]))            ; refused: no connection
   :widened-read (seon.db/call-with-custody {:seon.db/connection c}
                   #(get-in (seon.db/db) [:config :branch]))              ; => :cluster-default
   :home-agents (count (seon.db/q '[:find ?id :where [_ :seon.agent/id ?id]] (seon.db/db c)))}) ; => 23
```

No write to the cluster branch was attempted, to avoid polluting default. The widened read shows
that elided writes would target the cluster: `foreign-connection-error` compares `*conn*` with
itself. Each probe completed in under a second, and none looped.

## Changed paths, sizes, verification

- Source: 0 lines. Tests: 0 lines. Only this note is committed.
- Health at start: `default` was `faulted`, with one open fault at `seon.flow/release-admission!`
  (ClassCastException, 03:25Z). That fault predates this lane.
- Nothing needs a root test run. No RESET NEEDED.
- After the ruling, the lane lands:
  - `db_test` members for the held marker;
  - `boot_test` for root answering and a branch agent refused;
  - an `effect_test` member for a direct handler refused;
  - the delivery regression of the chosen option.

  Root then runs: `bin/test-check default --ns seon.db-test`, `--ns seon.cluster.boot-test`,
  `--ns seon.effect-test`, `--ns seon.shell.jvm-test`, `--ns seon.custody-stability-test`, and the
  owning message and issue namespaces.
- What this lets us delete, depending on the option: the three custody switches (about −30), the
  public `branch-connection`, and the census rows for `seon.db/branch-connection` and
  `seon.cluster.boot/connection` in `custody_stability_test.clj:52,57` (B3's file).
- The MCP branch `custody-authority-opus` was retired after the probes.
