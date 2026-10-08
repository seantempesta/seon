# Context, storage and pass-budget cards (lane cards-context, 2026-10-08)

Implementation cards for the ledger rows S4-01 … S4-07b3, CS-03a, DBG-01, SR-C/E/F/H/I, PB-03, PB-04 and the new
BLOB-K. Each card names files, functions with contracts, seams with `file:line` at HEAD `65cf8c26d`, the proof, the
commit line and dependencies. Line numbers in `turn.clj`, `context.clj` and `sci/eval.clj` drift as the group's
earlier rows land: delete and change by function name.

## Order (group B/D context chain, then storage)
S4-01 → S4-02a (Q1) → S4-02b1 → S4-02b2 → S4-03 → S4-04 → S4-06 → S4-07a → S4-07b1 → CS-03a (after CS-02) →
DBG-01 → S4-07b2 → S4-07b3. Change from the ledger: **DBG-01 lands before S4-07b2**, because the existing debug view
(`seon.render.web/sent-context`, `src/seon/render/web.clj:3180`) calls `seon.cluster.prompt/prompt`, which S4-07b2
deletes. S4-02b split into b1/b2; S4-07b3 may split into b3a/b3b; PB-03 into a (probe) / b; PB-04 into a (probe) / b.
Storage: SR-H → SR-I; SR-E after SR-D; SR-C any time in group C order; SR-F after S4-07b3 (prefer deleting the
`turn.clj` readers to converting them). BLOB-K waits on file releases and the fork push.

## Owner questions

**Q1 (blocks S4-02a). Where does a pass learn which assignment it belongs to?** The history (S4-03) is "call rows of
the active assignment", but no pass knows its assignment today: the world carries an optional task id only
(`src/seon/turn/world.clj:49-67`), and the assignment is written at the task home, which an agent's branch may not
hold.
1. (simplest, recommended) Read it once per pass when the world is built (`seon.task/assignment` on the task home,
   ~1 ms) and record the 12-character assignment key as a plain value on the call row. Costs one indexed read per pass.
2. Record nothing; derive the boundary at read time from the assignment's transaction time compared with each call
   row's. No write, but it compares times across two branches, which is fragile.
3. Store the key on the agent entity when root assigns, and copy it from there. One more fact that can go stale.

**Q2 (blocks PB-03b). After a reload, should the shared base be advanced in place or rebuilt once?** Today the first
pass after an adoption rebuilds the whole base (measured 428 MB / 180 ms), once per adoption, not per pass.
1. (simplest) Keep the rebuild once per adoption, and only release the previous base at once. Meets "never per pass",
   not "incrementally built up". No new code path.
2. (recommended, matches your shared-base ruling) Advance: fork the previous base and re-install only the reloaded
   namespaces' Vars and program rows. Cost follows the change; it adds a second way to reach a base, which the
   stale-caller tests must cover. Decide after PB-03a's measurement.
3. Rebuild in the background right after adoption, so no agent pass waits on it. Hides the cost without removing it.

**Q3 (does not block BLOB-K; card uses 1024). Blob compression floor: 1 KB or 256 bytes?** Your approved defaults say
"blob compression floor ~1 KB"; lane blob-konserve wrote 256 B ("below it the LZ4 frame and marker cost about what
compression saves").
1. 1024 B, as approved (card default). 2. 256 B, as the lane measured. 3. 1024 B now, revisit with a measured table.

## Findings recorded while carding (out of scope)
- The blob-konserve work sits uncommitted in the shared tree inside files claimed by `led-storage` (`src/seon/blob.clj`)
  and with an unclaimed untracked schema (`resources/seon/schemas/seon.config.blob.edn`); `deps.edn` (claimed by
  `dials-16gb`) still pins the old konserve sha while the submodule points at `e4334756`. BLOB-K lands it.
- `seon.sci.eval/rendered-evaluation`'s only caller is inside `system-turn`: S4-07b1 deletes both.
