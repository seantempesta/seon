---
type: research
status: complete
created: 2026-09-21
tags: [error-model, kind-retirement, test-system]
---

# Kind sweep — test system

Read the binding error-conversion PRD and the four requested landing notes end
to end. This bounded lane used the data-oriented-clojure, clojure-testing, and
repl skills. It did not operate the default cluster, create a worktree, launch
a test JVM, run a cold gate, or edit source, schema, test, held, or foreign
dirty paths.

## Section-6 stop condition

The conversion stops before production edits because multiple facets in the
same test-runner family would share every required member after the literal R3
and R4 conversion. In `resources/seon/schemas/seon.test.runner.edn:74-83`,
these five facets consist only of a retired `[:= true]` marker, the retired
`:seon.error/class true` property, and optional message/render metadata:

- `:seon.test.runner/invalid-marker-reason-error`
- `:seon.test.runner/process-tree-exit-backstop-error`
- `:seon.test.runner/unknown-worker-command-error`
- `:seon.test.runner/unresolved-test-var-error`
- `:seon.test.runner/worker-launch-failure-error`

Removing the stamps leaves no substantive required member on any of them.
Replacing all five with the owner's existing `:seon.test.runner/error` is not
literal either: that facet requires both `:seon.test.runner/error-selection`
and `:seon.test.runner/worker-observation`, which these producers do not carry.
The runner needs distinct required observations for commands, test Vars,
process exit bounds, launch evidence, and invalid marker reasons. Choosing and
declaring those members is the schema decision the PRD's section-6 shared-
required-member stop rule reserves for the orchestrator.

Concrete consumers require the distinctions. For example,
`src/seon/test/runner.clj:3598-3688` distinguishes worker retirement, write
failure, process exit/re-arm failure, exchange bound, interruption, and exchange
failure; `:3727-3756` distinguishes worker launch failure and currently copies
an underlying kind. Tests construct and assert those separate cases at
`test/seon/test_runner_test.clj:1259-1288` and `:1378-1408`. Converting these
sites without the missing facets would either collapse real outcomes or invent
undeclared discriminator fields.

## Census and verification boundary

The required retirement search remains nonzero because the section-6 stop
preceded edits:

| Path | Matching lines |
| --- | ---: |
| `src/seon/test.clj` | 0 |
| `src/seon/test/runner.clj` | 58 |
| `src/seon/test/selection.clj` | 0 |
| `src/seon/test/accretion.clj` | 8 |
| `src/seon/test/arm.clj` | 7 |
| `resources/seon/schemas/seon.test.edn` | 0 |
| `resources/seon/schemas/seon.test.runner.edn` | 10 |
| `resources/seon/schemas/seon.test.accretion.edn` | 1 |
| `test/seon/test_runner_test.clj` | 24 |
| `test/seon/test/*` | 5 |
| `test/seon/test_cache_test.clj` | 0 |

No fast tally exists: running unchanged tests cannot verify a conversion that
the binding PRD forbids this lane to invent. The required namespace-load probe
was likewise not claimed after making no source change. The default cluster was
observed alive at PID 24777 and was not touched.

The shared tree's pre-existing dirty/held boundary was preserved, including
`src/seon/test/cache.clj`, `src/seon/fn.clj`, `src/seon/cluster/source.clj`,
`script/seon/fresh_operator.clj`, `src/seon/schema*.clj`,
`src/seon/cluster.clj`, and every other path reported dirty by `git status`.

## Decision and proof owed

The orchestrator must first name substantive required members (or an existing
owning facet) for the five marker-only runner failures above. After that ruling,
the resumed lane owes the PRD section-4 order, the required namespace-load
command, one foreground changed-input fast run, a zero retirement search, and
the final path-limited implementation commit.

The orchestrator's eventual cold proof is:

```sh
bin/test --paths src/seon/test.clj src/seon/test/runner.clj \
  src/seon/test/selection.clj src/seon/test/accretion.clj \
  src/seon/test/arm.clj resources/seon/schemas/seon.test.edn \
  resources/seon/schemas/seon.test.runner.edn \
  resources/seon/schemas/seon.test.accretion.edn \
  test/seon/test_runner_test.clj test/seon/test test/seon/test_cache_test.clj \
  -- seon.test-runner-test seon.test-cache-test
```

followed by `bin/test --platform`. This lane ran neither command.

## Follow-up ruling and second stop

The orchestrator accepted the first stop and ruled the five substantive
observations. A candidate conversion then exposed a different modeling conflict
before any implementation commit: the required offending marker value is
genuinely polymorphic, while a facet marked `:seon.db/attributes true` must
persist its promised observation as a Datahike attribute. The schema bridge
does not map `:map`, `:any`, or `:seon.schema/value` to a Datahike value type
(`src/seon/schema/datahike.clj:123-185`; the datahike skill names this exact
silent-absence failure class). Therefore a facet requiring the raw marker value
through one of those forms validates transiently but silently loses that member
from occurrence datoms. That violates PRD section 0's storage guarantee that
facets persist on the occurrence.

The existing `:seon.error/data` does not dissolve the conflict. The diagnostic
constructor nests raw diagnostic evidence there, and the recorder persists a
bounded `:seon.error/data-edn` rendering; it does not preserve the original
domain key/value as a queryable facet member. Requiring `:seon.error/data` on
the facet would also make the facet cease to validate after occurrence
acquisition. Minting `:seon.test.runner/marker-value-edn` would be storable but
would promise rendered bytes rather than "the offending marker value as
observed." That is a semantic choice, not a mechanical application of the
ruling.

Three concrete options, simplest first:

1. **Declare exact EDN bytes (recommended):** add a required nonempty
   `:seon.test.runner/marker-value-edn` string and have the producer retain both
   the raw value in diagnostic data and its canonical `pr-str` bytes in the
   facet. Guarantee: the occurrence keeps a queryable, exact authored metadata
   observation for every EDN marker. Cost: specify/refuse non-EDN runtime Var
   metadata and add round-trip regressions. Give up: claiming arbitrary JVM
   objects are stored as their original object.
2. **Declare a bounded projection component:** use the existing error
   projection/value machinery as an owned component. Guarantee: every ordinary
   JVM value has bounded structural evidence with explicit omissions. Cost:
   schema component and recorder/acquisition work across the error owner. Give
   up: byte-for-byte identity of the observed object.
3. **Keep the raw member transient:** require `:seon.error/data` on the facet
   and retain only the recorder's `data-edn`. Guarantee: producers and immediate
   consumers see the original value. Cost: explicitly weaken the PRD storage
   guarantee and facet revalidation after acquisition. Give up: querying that
   facet member as a datom; this is not recommended.

The candidate schema/source edits were removed before this note update. The
owned implementation paths are byte-identical to HEAD, the shared-tree
`seon.test.runner` load printed `:loads` during the probe, and no test JVM,
cold gate, default lifecycle operation, held-path edit, or foreign-session
operation occurred. The retirement census and cold proof above remain owed.

## Option-2 implementation checkpoint

The orchestrator ruled option 2: raw arbitrary values use the error family's
existing `:seon.error/offending` and durable occurrence projection; facets
store only queryable scalar observations. Commit `e8ca8aa42` applies that rule
to the five formerly marker-only runner facets, their producers and launch
consumer, and the confirmation-launch regression. The required namespace load
printed `:loads`. Commit `b671525e9` converts the accretion producer/consumer
family and its install-refusal schema; `seon.test.accretion` reloaded and
printed `:loads`. No parallel EDN attribute or second projection mechanism was
introduced.

This is an implementation checkpoint, not the terminal landing. The current
full owned census has **89 matching lines** remaining, concentrated in
`src/seon/test/runner.clj`, `src/seon/test/arm.clj`, runner tests, and the
remaining old runner class schemas. `src/seon/test/accretion.clj` and
`resources/seon/schemas/seon.test.accretion.edn` are now kind/class/predicate
free. The final foreground fast run has deliberately not been spent on this
intermediate snapshot; the exact cold command above remains owed after the
remaining source and R8 conversions.

## New section-6 boundary after option 2

The continued census reaches a different cross-owner stop at
`src/seon/test/runner.clj:3432-3469`. `recording-failure` accepts the result or
Throwable from a supplied recording function. Its live callers cross
`seon.fresh-operator/live-root-value!`; that function is owned by the held,
currently dirty `script/seon/fresh_operator.clj:1612-1635`. It has no Malli
contract and its `prepl-value!` failure path still emits
`:seon.error/kind :seon.fresh-operator/prepl-exception` at
`script/seon/fresh_operator.clj:1872-1879`.

Consequently the runner has no declared facet member on which rule 1.3 can
branch. Its current branch at `runner.clj:3435` recognizes a returned foreign
kind; its catch at `:3442-3444` copies the foreign kind; and its notice at
`:3467` renders that copied classification. Minting a runner facet would
mis-own the fresh-operator transport failure, while replacing the checks with
the base-three predicate would merely recreate the retired general predicate
and leave the callee's output contract absent. This is PRD section 6's
"consumer needs to distinguish more than the facet members express" case.

The held owner must first declare and return its exact prepl/transport facet
union, with a substantive advertisement/transport observation. Once that
lands, the runner can pass the facet through, branch on its required member,
and update `recording-refusal-notice-names-the-cluster-cause` without copying a
kind. This lane did not edit the held script, resume another lane, or add a
compatibility facet.

No final fast tally is claimed. The option-2 and accretion slices load and are
committed, but the family remains at **89 matching lines** because the binding
stop rule forbids continuing past this undeclared foreign boundary. The final
fast command and cold command remain those already recorded above.

## Terminal landing — convertible family complete

The orchestrator subsequently ruled that the fresh-operator transport facet is
owned by the bin/script sweep and that `recording-failure` must remain byte-for-
byte on its kind branch until that owner declares the facet. All other
convertible sites are now retired in commits `3c0018771`, `b5e3a5e40`,
`decff675f`, `1fbba3fd3`, and `a740b93ee`. The required HEAD load after every
commit succeeded. The final load was:

```clojure
(require 'seon.test.bounds 'seon.test 'seon.test.runner
         'seon.test.selection 'seon.test.accretion 'seon.test.arm
         'seon.test.fast)
;; :loads
```

The completed census over `src/seon/test.clj`, every `src/seon/test/*.clj`,
`resources/seon/schemas/seon.test*.edn`, and the owned tests has **zero
convertible** `:seon.error/kind`, `seon.error/class`, or `error/error?` sites.
Eleven matching lines remain, all at the ruled foreign boundary below.

### Owed to the bin/script sweep

`script/seon/fresh_operator.clj` must first declare the exact prepl/transport
facet. The follow-on sweep then converts these sites together:

- `src/seon/test/runner.clj:3484`, `:3486`, `:3491-3493`, and `:3516` — the
  `recording-failure` pass-through/fallback and its notice.
- `test/seon/test_runner_test.clj:658-659` — the local recording-failure
  injection consumed by that unchanged branch.
- `test/seon/test_runner_test.clj:1377`, `:1380`, `:1387`, `:1390`, and
  `:1402` — the fresh-operator prepl exception fixture, nested contract
  evidence, assertions, and bare fallback fixture.

There are no other foreign-facet debts in the owned family.

### Fast tally and verification boundary

Exactly one foreground fast invocation was run, as required:

```sh
bin/test-fast --paths src/seon/test/runner.clj src/seon/test/arm.clj \
  src/seon/test/accretion.clj resources/seon/schemas/seon.test.runner.edn \
  resources/seon/schemas/seon.test.accretion.edn \
  test/seon/test_runner_test.clj test/seon/test/runner_test.clj \
  test/seon/test/check_request_test.clj -- \
  seon.test-runner-test seon.test.runner-test seon.test.check-request-test
```

Fast tally: **0 executed; snapshot admission refused before test execution**.
Contract arming succeeded with 1,425 registered, 1,425 instrumented, and 1,420
program-armable Vars. Admission then named
`:seon.test.accretion/arguments` as a non-storable promised member of
`:seon.test.accretion/install-refused-error`. Commit `1fbba3fd3` corrected that
owned facet to retain only its queryable scalar observations; arbitrary values
remain open in memory and ride the error occurrence's existing projection.
The required HEAD load passed after the correction. The namespace was not
rerun because its inputs changed after the one permitted fast invocation.

The cold command owed to the orchestrator is:

```sh
bin/test --paths src/seon/test.clj src/seon/test/runner.clj \
  src/seon/test/selection.clj src/seon/test/accretion.clj \
  src/seon/test/arm.clj src/seon/test/bounds.clj \
  resources/seon/schemas/seon.test.edn \
  resources/seon/schemas/seon.test.runner.edn \
  resources/seon/schemas/seon.test.accretion.edn \
  test/seon/test_runner_test.clj test/seon/test/runner_test.clj \
  test/seon/test/check_request_test.clj test/seon/test_cache_test.clj -- \
  seon.test-runner-test seon.test.runner-test seon.test.check-request-test \
  seon.test-cache-test
```

followed by `bin/test --platform`. This lane ran neither cold command. Held and
foreign dirty paths remained untouched, including
`script/seon/fresh_operator.clj`; the foreign boundary is the undeclared
fresh-operator prepl/transport facet above.

After this lane's source commits, the publication lane placed uncommitted
`--prepare-base` transport edits in `src/seon/test/runner.clj` and matching
fixture-copy edits in `test/seon/test_runner_test.clj`. They are not part of
this sweep, were not staged or committed here, and remain that lane's shared-
tree boundary.
