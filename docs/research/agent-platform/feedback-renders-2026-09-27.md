---
type: research
status: error and schema renders return data; revised after Astra's REVISE (e9e15d610) and root's rulings; not yet installed in default
created: 2026-09-27
lane: feedback-renders-opus
proof: tmp/feedback-renders/proof.clj (`(proof/proofs)`); results tmp/feedback-renders/proof-results.edn
jvm: default pids 51590, 77505 and later; landed code evaluated as scratch copies (`fr.error`, `fr.render.schema`, `fr.db`), never over default's Vars
---
# Feedback renders: errors and schemas

**What exists.** Before this work, a thrown failure showed a `select-keys` of the error and a returned error showed a
prose sentence. `register!` returned its key.

**Approach.** Each render selects data from the value and one database value, and returns it:
- `render-ai` returns the selection itself, which the value printer prints once at the edge;
- `render-html` lays out the same keys as hiccup;
- `:seon.render/form` is the requery form, shown and never run.

A render validates nothing and writes nothing. A read the database refuses is carried as the refusal itself.

## 1. Errors (`src/seon/error.clj`, `resources/seon/schemas/seon.error.edn`)

**The selection** is `reply-value`. Both the thrown path (`shown-failure`) and the `:seon.error/base` pair use it.

| Key | Contents |
|---|---|
| `:seon.error/message` | the producer's own message (its first cause link) |
| `:seon.error/problems` | each failing path with the expectation at that path (read through a named schema), the offending value there, one generated `:seon.error/example` (`mg/generate`, size 1, seed 0; labelled as an example, never as the caller's intent), and for a result refusal its input and result contract |
| `:seon.error/offending`, `:expected`, `:member`, `:fix` | a declared refusal's own members |
| `:seon.error/in-namespace` | for a name not found, the declared names nearest by spelling (8); a refused read is shown as the refusal |
| `:seon.error/sources` | a removal's caller lines that name the subject |
| `:seon.instrument/arglists`, `declared-arities`, `caller`, `:seon.sci.eval/stacktrace` | as recorded |
| `:seon.error/occurrence-count`, `:seon.error/shown` | a stored fault's |

**Retired by the review.** The synthesized fix form is gone: `plain?`, `as-written`, `repaired` and `fix-form`. No
caller input is guessed; a fix is shown only when the producer supplies it. The inner printing is also gone
(`reply-shown`, `refusal-value-shown`). `render-faults-ai` returns its faults' faces as a vector.

**Proof** (scratch copies of this commit, run on the installed JVM; see §5 for what this does and does not prove):

| Case | Tokens after | ms | Fix from the reply |
|---|---|---|---|
| nested contract | 128 | 0 | yes: path `[1 :h]`, expected `[:int {:min 0}]`, example `0`; agent writes `:h 4` |
| missing key | 144 | 0 | yes |
| first-party contract | 193 | 0 | yes: the expected `[:or :symbol :qualified-keyword]` tells the agent to quote |
| exception | 73 | 0 | yes (agent-authored) |
| unresolved name | 61 | 1 | yes: nearest names |
| missing entity | 72 | 29 | yes: nearest names |
| removal refusal | 105 | 1 | the caller's line is shown; no repair was evaluated |
| writer refusal | 189 | 0 | not evaluated; the producer now lists every problem (§3) |
| schema admission | 72 | 0 | the producer now names the missing key (§3); not re-measured installed |
| nilable schema | 172 | 0 | yes |

`:fix-works?` evaluates a fix the case authors, as an agent would from the reply. It checks only that no error was
thrown, so a returned error map would pass it.

## 2. Schemas (`src/seon/render/schema.clj`, `seon.schema.edn`, `seon.schema.usage.edn`, `register!`, `seon.db`)

**The selection** is `usage`, three indexed queries over one database value:
- the committed form it replaces (`:previous`), or `:new?` when the read found none;
- the schemas and function contracts naming the key: 8 of each named, and a count.

Listed among others, a schema is `{:seon.schema/key k :seon.schema.usage/doc …}` and nothing is read. A refused read is
the face's value.

**The stored-data check is not a render's.** `register!` computes it once, inside the evaluation that registers, over
the evaluation's read basis (`seon.db/registration-check`). It runs only for an installed attribute whose committed form
changes, and records the result on the registration as `:seon.schema.usage/check`. The render reads that key.

`seon.db/stored-value-check` does the work:
- it takes the first 200 distinct entities holding the attribute;
- it reads each as the writer reconstructs it (`write-entity-value`: a cardinality-many value in its collection, a
  vector from its cells);
- it validates the attribute's value there;
- `:more?` says more entities exist;
- a form this projection cannot compile is the declared refusal.

Astra's false 200/200 is fixed. Measured on default:

| Registration | Result | ms |
|---|---|---|
| unchanged `:seon.fn.arity/input-refs` | 0 of 200 fail | 19 |
| `[:set {:max 1} :qualified-keyword]` | 70 of 200 fail; example `[14441 #{…}]` | 4 |
| `:seon.task/intent [:string {:min 400}]` | 90 of 200 fail | 2 |
| unchanged form | nothing is read (`registration-check` answers nil) | — |

Render cost is 15–36 ms for the three queries. Tokens are 34 for a new key, 143 for a compatible change and 270 for a
change the stored data fails.

**Why `register!` and not the batch.** The batch prints a form's value (`evaluate-step`) before it derives the form's
schema change (`derive-step`), so a record made in the derive step cannot reach that form's render. `register!` runs
inside the per-form registration delta (`schema/call-with-registration-delta`) with the working value bound
(`seon.sci.batch/evaluate`). It resolves `seon.db` without loading it, the way it already reaches
`seon.schema.edn/admit`.

## 3. Producer fixes (f5fb4466a), each with a tightened contract

- **`seon.schema.edn/admit`.** `:unresolved-reference` names the missing key and `:attribute` names the declaring key.
  Both are required by the error schema.
- **`seon.db/write-entity-error`.** Every failing path is a problem. A missing key's offending value is the entity
  missing it, never `:seon.error/unknown`. The output schema `:seon.db.write/entity-refusal` enforces both.
- **MCP versus agent text.** The difference was a printed `:seon.error/at` from two runs at two instants. The reply
  leaves it out.

## 4. Deleted

- `seon.error`'s prose chain (f5fb4466a).
- `render-contract-ai`.
- `seon.render.ns/schema-ai`, `schema-html`, `schema-parts` and `schema-form`.
- In this revision: the fix synthesis and the inner printers in `error.clj` and `render/schema.clj`, the render-time
  sample validation, and `found`/`read-form`'s failure-to-absence.
- **Pending the holder of `sci/kernel.clj`:** `tmp/feedback-renders/kernel-render-ai-to-base-pair.patch` deletes
  `seon.sci.kernel/render-ai` and its two attachments (the base error pair covers kernel errors), deletes
  `seon.error/source-entries`, and converts `kernel_test`.

## 5. What is proven, and what is not

- The proof calls the landed render functions directly on values extracted from two batch adapters:
  - `code-request`, which is what the MCP tool encodes;
  - `turn-request` over text returned by a local function. This is **not** the provider/Flow turn entrance.

  Neither is an installed render in a saved reply.
- The installed JVM runs the first render commit. This revision's installed proof (arming, saved replies, read
  evidence on shown units, registration schema adoption on a branch) waits for root's restart.
- Contracts compile from `git archive` HEAD plus this diff, and clj-kondo `src script test` reports 0 errors. No tests
  were run (lane rule).
