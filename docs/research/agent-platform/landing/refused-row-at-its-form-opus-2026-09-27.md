---
type: design
lane: one-commit-a-opus (audit cut row 2, outcome classification)
date: 2026-09-27
status: revision 3, after Astra REVISE rounds 1 (ac4e6c50b) and 2 (2bf319f21); awaiting round 3
---

# A refused deletion names the one form that proposed it, or says why none

Round 2 resolved two things, and both stay as they are:

- The run keeps its single ordered transaction and whole-run refusal.
- Every evaluated value stays whole, and the forms are restored in reverse (`restore-evaluations!`,
  turn.clj:5084-5101).

The per-form trial is withdrawn (round 1). This revision answers only round 2's two open points:

- **(A)** The raw refusal's declared identity, fixed at its producer.
- **(B)** An attribution claim the writer's data can support.

## (A) The producer declares what it refused (db.clj patch for shrink-db, plus schemas)

**What exists** (review round 2 §1, all verified from source):

- `seon.db/removed-definition-error` (db.clj:4150-4208) builds `:seon.program/referrers`, each entry
  `{subject, relation, referrer, db/id}`.
  - `subject` is an untyped symbol or keyword.
  - The function does not stamp a declared schema.
- `write-observation` (db.clj:4727-4742) stamps every validator refusal as `:seon.db.write/validation-refusal`.
- `error/recording` (error.clj:587-612) projects that into `:seon.db.write/error`. Neither schema declares the
  referrers, so the recorded diagnostic loses them. The `oca-attr` probe (2026-09-27T01:16Z) confirms this.

**Change.** It has three parts, all at their owners, and adds no wrapper and no retag.

1. **Typed subject.** Each referrer entry gains `:seon.program/identity`, the removed identity as
   `[identity-attribute value]` (for example `[:seon.fn/sym my.ns/h]`).
   - `:seon.program/identity` is the registered tuple (`seon.program.edn:115`), used as its own member name.
   - The producer knows the family, because it builds each obligation from `:seon.fn/sym`, `:seon.ns/name`,
     `:seon.schema/key` or `:db/ident`. Nothing downstream infers it from spelling.
   - `seon.program.edn`: the referrer entry requires `:seon.program/identity`.
2. **The producer's declared identity survives the write boundary.**
   - `removed-definition-error` stamps its result's metadata as `:seon.error/declared-schema
     :seon.program/deletion-refused-error`, the transient schema that already exists (`seon.program.edn:179`).
   - `write-observation` keeps the write-attempt identity (`:seon.db.write/validation-refusal`, so the
     recorder's attempt normalization at error.clj:589 is unchanged). It records the validator's own declared
     schema as a member, `:seon.db.write/refused-schema`, read from the validator value's metadata and never
     guessed from its keys.
   - When that member names a schema, the raw value must also validate against it. The recorder already
     validates declared shapes (`validate-declaration!`), so this is checked there.
3. **Durable.**
   - `seon.db.write.edn`: `:seon.db.write/validation-refusal` gains an optional
     `[:seon.db.write/refused-schema :qualified-keyword]`. `:seon.db.write/error` gains the same member as a
     stored attribute, so the durable error says which invariant refused. The recorder carries it like any
     other declared member.
   - The referrers themselves stay transient on the raw value, as `seon.program.edn:181` declares. Their
     durable trace is the recorder's bounded source evidence, as today.
   - What (B) derives from them is stored on the receipts; see B4.

Before integrating, I will verify through the real writer, on a disposable branch, the exact raw value that
`settle-batch-refusal!` receives: its metadata, `refused-schema` and typed referrers. I will also verify the
recorded value. If Datahike's `:datahike/validation-refusal` loses the value's metadata, the member is set by
`removed-definition-error` on the value itself, never re-derived.

## (B) Claim only what the data establishes: one proposing form, else none

The writer reports broken final relations: removed identity H, surviving referrer C, relation R. It carries no
ordinal and no operation history (review round 2 §2). So the claim is an **association with a proposed
deletion**, never a cause. It is made only when unambiguous. This is the coordinator's fallback, and it is the
whole rule.

1. **Inputs.**
   - The raw refusal, only when `:seon.db.write/refused-schema` is `:seon.program/deletion-refused-error`. Every
     other refusal family attributes nothing.
   - The settled requests: each request's `:seon.cluster.eval/ordinal` and its
     `[:seon.sci.eval/evaluation :seon.program/row]`, the row actually carried into settlement.
   - Never the vector index, the source text or a candidate row an admission refused.
2. **For each typed identity H named by the refusal:**
   - `deleters` = requests whose row's `:seon.program/delete-identities` contains H.
   - `declarers` = requests whose row declares H (the row's identity tuple equals H).
   - H is attributed to form N only if `deleters` is exactly `{N}` and `declarers` is empty.
   - Otherwise H is unattributed, with its reason:
     - `:several-deletions`, for delete, redeclare and delete again;
     - `:declared-in-run`, for a redefinition next to the deletion;
     - `:no-deleting-form`, when the deletion came from outside this run's rows.
   - Repaired identities are not named by the writer, so they never enter. Typed tuples keep a function and a
     test of the same spelling apart.
3. **Cost.** One pass builds `{identity → ordinals}` from the rows' identities, and one pass looks up the named
   identities: O(S + row identities). It runs only in the refusal arm, adds nothing to success, and involves no
   writer trial, replay or cache.
4. **As data first, rendered second.**
   - The association is a value, `[{:seon.program/identity H :seon.cluster.eval/ordinal N
     :seon.program/referrers [...]}]` for each attributed H, plus `{:seon.program/identity H
     :seon.turn/unattributed reason}` for the rest.
   - It is merged into the refusal handed to `error/recording`, as a declared optional member of
     `:seon.db.write/error`. So the durable error carries which form proposed which deletion. No second error or
     attribution store is created.
   - The member name is pending a registry search before implementing. If `:seon.turn/unattributed` or a
     container key already has an owner, that owner wins.
   - The receipts' `:seon.cluster.eval/error` text is rendered from that value:
     - form N: "Not settled: form N proposed deleting H; the final program still references it from C (R)."
     - other forms: "Not settled: this run's settlement was refused: …"
     - When nothing is attributable, the text says so and why: "no single form's change is identified:
       several forms change H".
   - Ordinals are the run's evaluation ordinals, labelled as such, and need not start at 0.
5. **Total.** Attribution is a pure function over values already in hand. Its failure (an unexpected shape) is
   caught at declared cases only. It yields the unattributed batch text and the existing refusal, closure and
   `restore-evaluations!`, and it never throws past them. Values, result handles and a form's own body error
   are untouched.

## Size (revised)

This is an estimate; it will be measured at landing.

| Location | Change |
| --- | --- |
| db.clj (shrink-db patch) | about 8 lines: typed identity per break, metadata stamp, refused-schema member |
| two schema EDN files | about 5 lines |
| turn.clj | about 30 lines: the association function, merging it into recording, receipt text |
| error.clj | likely 0 lines, since the recorder carries declared members; confirmed at the probe |

Total is about 45 added lines and no deletions. This is under the 100-line bar, and it adds no new machinery
beside an owner.

## Regressions (one per behaviour class; canonical branch fixtures, real SCI, armed contracts)

Most classes go in one new deftest in `test/seon/cluster/host_test.clj`, plus one `seon.db` test.

1. **Writer evidence** (`seon.db` test).
   - A real `db/transact!` deletion refusal carries typed `:seon.program/identity` referrers and `refused-schema
     :seon.program/deletion-refused-error`, with request id and basis.
   - The recorded error keeps `refused-schema`.
   - A non-deletion validator refusal has no `refused-schema` or a different one, and attributes nothing.
2. **Unambiguous association.** Arithmetic, deletion, arithmetic keeps `"3"`, `nil` and `"7"`, with the handles
   intact.
   - Form 2's error names it as proposing the deletion. A form with its own body error keeps that error.
   - The run starts at a nonzero ordinal (a second run of the agent).
   - Restoration is shown by a pull of the stored rows and an SCI call in the same test.
   - This revises `a-refused-settlement-is-its-forms-refusal-and-the-run-closes` (nil value; the association
     read from data and from text separately).
3. **Repeated identity changes.** Each case asserts exact associations or their absence, with the reason:
   - redefine H, then delete H: `:declared-in-run`, no blame;
   - delete, redeclare and delete, if admission admits it: `:several-deletions`;
   - two subjects in one refusal: each judged on its own.
4. **Identity boundaries.** An `ns-unmap` removes both `[:seon.fn/sym h]` and `[:seon.test/sym h]`; only the
   typed identity the writer names matches. The rows actually produced are asserted first.
5. **Repairs.**
   - Delete H, then delete its caller C: the run commits and neither remains.
   - Separate run: H's obligations are repaired while K's stay broken. Only K appears, and the text claims a
     proposal, not sole cause.
6. **Totality.** An unsupported refusal family and an unattributable refusal both keep the canonical refusal,
   values, one terminal and reverse restoration. There is no second evaluation and no writer trial.
   - `seon.turn-test/a-refused-settlement-keeps-each-evaluated-value` stays as it is.

Out of scope, and still open under audit row 2: the executed-versus-unrun outcome, changed bases, and
restoration-failure continuation. This cut does not touch their owners.
