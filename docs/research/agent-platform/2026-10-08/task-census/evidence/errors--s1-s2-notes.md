# errors S1/S2 design check (lane errors-s1, 2026-10-07)

Spec: docs/seon/specs/errors.md (reviewed 2026-10-07).

## S1 type key: the design held

- `:seon.error/type` (optional, `:qualified-keyword`) added to `:seon.error/base`. Because the base is
  `:seon.db/attributes true`, it is also a Datahike attribute (adoption installs it). Nothing else needed.
- Producers: 64 stamp sites `(vary-meta x assoc :seon.error/declared-schema y)`,
  `(vary-meta x merge {...})`, `(with-meta x {...})` rewritten by ONE rewrite-clj script (bb) to
  `(assoc x :seon.error/type y)`, plus 1 multi-line site in `error/recording` by hand. The script
  leaves some `(assoc (assoc x ...) :seon.error/type y)` nests; harmless, and S2 rewrites those sites.
- Readers (by hand): `render/declared-producers`, `fault/fault!` cause-link scan, three
  `error/recording` calls in `turn.clj`, `db` validator-schema provenance (reads the old type before the
  outer assoc overwrites it), `render/request-profile`.
- NOT changed (correctly out of S1): the stored fault attribute `:seon.error/declared-schema` and the
  recorder request key of the same name (schedule.clj, sci/eval.clj, cluster/agent.clj, fault.clj
  request). That is recorder input, not the metadata stamp; it folds into `:seon.error/type` with S6.
- Behaviour change to watch: the key survives `merge`/`into`, where metadata did not. A producer that
  merges an inner typed error into a new map without re-typing now carries the INNER type (render and
  recording follow it). Previously such values were "undeclared". No test showed a case; reviewer may
  want to grep `(merge ... (error/refusal ...))`-style sites (e.g. turn.clj phase-failed re-types, fine).
- Tests: stamp assertions read the key; "an undeclared value" is now `(dissoc v :seon.error/type)`.
  T1 added on `seon.fs.jvm/read` (type on a returned not-found error, survives merge, validates).
- Proof limits: named namespaces contain pre-existing reds on HEAD a5a21fd58 (host-test x5 incl. the
  long deletion-association test, entity-pairs x3, value-test x2, rereads x2; baseline run
  940885360cdd and 4f4d5824dfe8 show the same reds without this diff), so the commit names the
  individual tests reaching the change.
- Single commit, not split mechanical/hand: the readers and producers must move together or render
  selection is red between the commits. The scripted part is exactly the hunks replacing
  `vary-meta`/`with-meta` with `assoc`; everything else is hand.

## S2 constructor sweep: STOPPED — the slice is larger than the spec says

Measured on HEAD (src only):

1. `diagnostic` is not just a literal builder. 52 call sites pass `:seon.error/throwable`, from which it
   derives `:seon.error/chain`, `:seon.error/frame`, `:seon.error/exception-class` and a fallback
   message. The spec's `(error type op message data)` has no throwable path (that is S3's `caught`).
   Sweeping these 52 sites in S2 either loses the chain until S3 or needs a temporary throwable arm on
   `error` — machinery the spec did not foresee. Proposal: S2 sweeps only throwable-free sites; the 52
   move with S3.
2. Hand-built error literals: 120 `{:seon.error/at (Date.) :seon.error/layer .. :seon.error/operation ..}`
   maps in src that never call `diagnostic`/`error`. Making `:seon.error/type` required means these are
   in the sweep too (the spec counts only 225 + 14).
3. `:seon.error/at` / `:seon.error/layer` are load-bearing far beyond the base schema:
   - the fault SIGNATURE is `(id/id [(:seon.error/layer observation) ...])` (error.clj:171);
   - the stored fault fact and `:seon.error/error` entity require `:layer` and `:at` (seon.error.edn);
   - "is this an error?" is spelled `(:seon.error/at x)` in ~20 places in error.clj alone
     (1094, 1211, 1236, 1303, 1328, 1346, 2102, 2209, 2334 ...), plus `refusal/error?`,
     `refusal/refusal` and `sci/kernel.clj:827`, which test at+layer+operation.
   Dropping at/layer from produced maps in S2 therefore drags in the S6 signature/fact rewrite and the
   S3/S4 predicates. 441 lines read `:seon.error/at`, 273 read `:layer`, across 51 files.
4. Type for sites whose function's output alternative is only `:seon.error/base`,
   `:seon.db/error-result` or a union with several error alternatives: the spec's "take the type from
   the declared output alternative" has no rule for these. Needs a ruling (e.g. `:seon.error/base`
   as the generic type, or every such function gains a named variant first).

Suggested re-slicing for the owner:
- S2a: new `error`/`fail!` signature; sweep throwable-free `diagnostic`/`error` sites that have exactly
  one declared error alternative; keep `:at`/`:layer` produced and in the base. `error?` becomes the
  type key only after S2b.
- S2b: type every remaining producer (hand literals, multi-alternative sites) under the ruling of (4).
- Drop `:at`/`:layer` together with S6 (signature, fact entity, recorder stamps time), and convert the
  `(:seon.error/at x)` predicates to `error?` there.
