# Cache hit audit: zero- and low-hit `seon.cache` kinds

Date 2026-10-12. Default cluster at HEAD 015673f47, booted 01:58 (current-src commit `6acc3ec1`); adoption record
moved to `6acc3fd8` at 02:03. Evidence is `(seon.cache/status)` and `(seon.cache/retained kind)` in JVM mode, plus a
reproduction with fresh agents `probe-cache-1` and `probe-cache-2` in SCI mode (both branches retired afterwards).
Budget 512 MB, 252 MB weighed at the start.

A note on counting: `seon.cache/settle!` increments `::misses` for every claim, including a lookup whose derive returns
nil or that `retain?` refuses. Several callers use `lookup` as a get-if-present probe (`(constantly nil)`) or as a put
(`(constantly value)`); each such call counts a miss that did no work.

## Ranked by waste

### 1. `:seon.sci.eval/program-context`: 160 MB charged, two key defects plus a dead base

Owner `seon.sci.eval/program-context`, `seon.sci.eval/program-content-key`, `seon.sci.eval/derive-program-context`;
weights `seon.sci.eval/context-weight` (12,288 B x functions) and `seon.sci.eval/overlay-weight`.

Retained entries at the start (6 misses, 2 hits):

| key | functions | weight |
|---|---|---|
| base `[store 6acc3ec1]` (current-src at boot) | 6,505 | ~80 MB |
| base `[store 6acc3fd8]` (adopted 02:03) | 6,506 | ~80 MB |
| 4 overlays `[store loaded plan-digest projection-revisions dials]` | 1-2 installed each | ~25-37 KB each |

So the 160 MB is two bases at the *estimated* 80 MB each (`context-weight`, never measured; the hybrid
`hybrid-immutable-env.md` "Cache weighing" row asked for a measurement that has not happened). Overlays already charge
only their delta (35e906c38), and frozen forks share the base correctly.

Why 6 misses for 2 agents:
- **Two bases.** The loaded commit moved from `6acc3ec1` to `6acc3fd8` five minutes after boot (+1 function). The new
  key rebuilds the whole 6,506-function base (load-ms 1,576 over 6 misses, mostly the two bases). The old base and its
  overlay are dead (no branch names `6acc3ec1`: all four agent branches and `cluster-default` report loaded
  `6acc3fd8`) and stay charged until LRU pressure.
- **Four overlays**, one per eval request whose step ran tests over a changed program (one per defn).

Reproduction (counter deltas):
- `probe-cache-1` first form `(+ 1 2)`: no program-context, program-key or overlay-plan lookup at all. A fresh agent
  reuses the root's held base through `seon.sci.eval/acquire!` (`seon.cluster.agent` acquire path, `held-base`). No
  rebuild. (custody 178 ms.)
- `probe-cache-1` `(defn probe.cache1/twice ...)`: program-key 0/1, overlay-plan 0/1, loaded-digests 1/1,
  program-context 1 hit (base) / 1 miss (overlay).
- `probe-cache-2`, the **identical** ns and defn on another branch: again program-key 0/1, overlay-plan 0/1,
  loaded-digests 1/1, program-context 1/1. Equal program content did not share the overlay.

Diffing the two keys shows two needless parts (classification **(b)**):
1. **Plan digest hashes entity ids.** `seon.sci.eval/derive-overlay-plan` digests `::namespaces` rows pulled by
   `namespace-pattern`, which carry `:db/id` (170461 vs 170411 for `probe.cache1`) and `:seon.ns/steward
   [:db/id N]` (each agent's own `user` namespace row: steward 170454 vs 170385). Digests `8c34ccf4...` vs
   `0d5ed297...` for equal programs. Every agent's `user` and `my.agents.root` rows also enter every plan.
2. **Projection revisions are commit identities.** `program-content-key` conjoins
   `(db/content-revisions database schema/projection-attributes)`. `schema/projection-attributes` includes
   `:seon.fn/sym`, `:seon.fn/spec` and `:seon.schema.admission/source`, so any defn moves them, and the revision is the
   branch's own commit uuid (`6acc49e9...` vs `6acc49d3...`), unique per branch even for equal datoms. The plan digest
   already covers the definitions, so this part only defeats sharing.

Smallest fix: digest the plan's namespace rows without `:db/id`, and resolve refs to their identity value (steward as
the agent id) or drop rows that do not affect interpretation. Key the arming dependency by content: the digest of the
contract forms the plan's functions arm under, not the commit revisions of the contract attributes. When the adoption
record moves, call `seon.cache/evict!` on the old base key (the owner replaced that value, which is what `evict!` is
for). Then measure one base's real retained heap and replace the 12,288 B/function constant with that number.

### 2. Projection-sized weights on small values: ~41 MB of budget charged for keys

These charge `schema/declaration-projection-bytes-per-form` (2,550 B) x the number of projection forms (~3,670), which
is the size of the projection's `forms` map. That map is the **key** and is shared with the projection (already charged
under `:seon.schema/declaration-projection`). The values are small.

| kind | owner | charged | value actually held |
|---|---|---|---|
| `:seon.task/file-grammar` | `seon.task/tasks-tx-in` | 9.36 MB | one 64-char digest |
| `:seon.cluster/derivation-world` | `seon.cluster/derivation-keys` | 10.30 MB | a digest per form (~0.6 MB) |
| `:seon.task/task-basis` | `seon.task/task-basis` | 10.39 MB | stored declarations of the files' namespaces |
| `:seon.task/tasks-tx` | `seon.task/tasks-tx` | 11.53 MB | one tx vector (1 hit is the `index-tasks!` check followed by the `:db.fn/call`) |

Classification: the derivations are mostly once per publication; the defect is the weight, which takes ~38 MB of
budget away from entries that are reused. `file-grammar` is also **(a)**: it caches `(id/digest 64 [forms])` only to
build the `file-declarations` key, and it is computed only on a `tasks-tx` miss, so it never hits. Smallest fix: weigh
the value (count of what it holds), never the key. Delete the `::file-grammar` lookup and digest inline, or key
`file-declarations` by the projection's existing content identity if it has one.

### 3. `:seon.sci.eval/loaded-digests`: whole-program digests per branch commit (b)

2 hits / 6 misses, 10 MB (6 entries x 6,500 functions x 256 B). Owner `seon.sci.eval/loaded-digests`, keyed
`[store commit-id]`. `derive-overlay-plan` calls it for the *branch* database too (`held`), so every defn on any
branch computes and keeps `function-digests` over all ~6,500 functions to find the one or two authored ones. That is
whole-program work per change, and the entry is never hit again because the next defn is a new commit. Smallest fix:
compute `held` digests only for the authored rows plus the functions absent from the loaded program (the plan's
`overridden` and `added`), and keep `loaded-digests` for the loaded commit only.

### 4. `:seon.cluster/file-schema-keys`: 1,080 misses = 2 x 540 is a probe, not double work (counting)

Owner `seon.cluster/stored-derivation-schema-keys`. Per file it first calls
`(cache/lookup ::file-schema-keys key (constantly 0) (constantly nil))` to ask "held?", which `settle!` counts as a miss
and does not retain; then, for the files that missed, it pulls their rows together and calls `lookup` again with the
real derive (second miss). The derivation runs once per file (26 ms total). Only one publication has run in this JVM, so
0 hits is expected. Smallest fix: a non-counting read in `seon.cache` (Caffeine `getIfPresent` of a completed entry,
the same thing `retained` already reads), or one lookup per file whose derive derefs a shared delay of the batch pull.
Either restores honest miss counts. The same probe/put idiom inflates `:seon.health/reads` and `:seon.health/derived`
(`seon.health` `::reads`, `(constantly ::unknown)` / `(constantly actual)`).

### 5. Once-per-publication kinds that pay only on an in-process republication (keep)

- `:seon.cluster/file-derivation-key` 0/540, 1.5 MB, 130 ms. Owner `seon.cluster/derivation-keys`. Key
  `[analyzer-code kondo file-digest world-key schema-key-start]` is all content. It pays only when a second publication
  runs in the same JVM (write-back); a new JVM starts empty. Keep. It is at most an (a) candidate if write-back
  publications prove rare.
- `:seon.task/file-declarations` 0/94 (= the 94 task files), 2 MB, 49 ms. Owner `seon.task/tasks-tx-in`. Same story:
  content key `[grammar path text]`, hits only on republication. Keep, but it inherits the `file-grammar` defect above.

### 6. Low traffic, correct (keep)

- `:seon.ref/document` 2/121, 67 KB, 56 ms. Owner `seon.ref/document`, keyed by the sha-256 of the text; 121 distinct
  intents and docstrings parsed once each (~0.46 ms each). Content key, tiny. Keep.
- `:seon.sci.eval/program-key` 0/4 (now 1/6) and `:seon.sci.eval/overlay-plan` 0/4. They inherit defect 1: keyed by
  `program-revisions` (commit identities), so every defn on every branch misses. They are fixed by the same key change.
- `:seon.health/problems` 1/3, `:seon.health/untested` 1/3, `:seon.health/replaced-roots` 0/4: keyed by the committed
  head (`committed`, `Var/rev`), so every write misses by design. Low bytes. Not audited further.

## Findings outside the assignment

- `(seon.db/branch-connection db :current-src)` and the `:base/agent-*` branches throw
  `Cannot invoke "java.util.concurrent.Future.get()" because "fut" is null` (an NPE escaping instead of an error map).
- A fresh agent's first eval request spends 165-178 ms in `:custody`.
