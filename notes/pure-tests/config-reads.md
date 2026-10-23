# Config reads: can a test's :env carry only the config keys it reaches? (read-only, 2026-10-23)

Question (c) of the pure-tests research. Checked on `next-pure-tests` at `b39cef5`. Nothing edited but this file.
The numbers marked *static* come from a throwaway s-expression walk of `src` and `test`
(`$SCRATCH/reach.py`: top-level forms, `alias/name` and `:refer` resolution, each test's closure
over var references, plus its namespaces' plain forms). It has no macro expansion, no multimethod or
protocol edges and no system.edn data-refs, so it **undercounts** reach. Read its per-key counts as
lower bounds, not as `reach-digests`' numbers.

## Today

- `seon.fx/config-input` (fx.clj:106) encodes the **whole** `seon.config/config` minus the two
  `"TCP port"` keys, with the checkout path written as `<checkout>`. `seon.fx/inputs` (fx.clj:143)
  digests it into `:env` together with every env-input file's git entry (`env-input?`, fx.clj:96:
  everything tracked except `.md`, task files, and `src/`/`test/` `.clj`).
- `seon.program/reach-digests` (program.clj:769) hashes `k \n env \n [tree] sorted-form-digests`, so
  `:env` is one value shared by every test.
- **The config file is in `:env` twice.** `resources/seon/config/default.edn` is an env-input file,
  so its blob id is in `:env` as well as its resolved value. Narrowing `config-input` alone changes
  nothing for an edit to default.edn: the blob still moves every digest.
- `jolt/brief3` and `jolt/shrink2` have not changed `config-input` yet (`git show jolt/brief3:src/seon/fx.clj`
  is main's). The shrink2 notes in the side-session directory cover brief 3 (program facts), not
  place keys. I take "drop place keys like ports" as planned and not yet written.

## 1. Every way config is read

The one resolver is `seon.config/resolve-in` (config.clj:217): the task's `:seon.task/config`, then
`:seon.config.ai/models[model]`, then `:seon.config.ai/providers[provider]`, then the config. Around it:

| Reader | Key passed as | Sites in src |
|---|---|---|
| `cfg/value k` / `config/value k` | literal keyword | 131 literal occurrences (`grep -o`) |
| `config/value k` with a computed k | parameter | 1: `seon.print/bounded` (print.clj:162). Its callers pass literals: render/ui.clj:292, 352, 420, 496, 554; render/ai.clj:82, 125, 130, 180, 193, 223, 247, 248, 278, 284; context.clj:76 |
| `cfg/resolve` / `resolve-in` | literal keyword | src: only inside config.clj (`provider`); otherwise tests |
| `(:seon.config.x/y c)` / `get-in c [...]` on a config map | literal | config.clj (`resolve-in`, `provider`, `with-provider`), root.clj:684, fork_server.clj:92 and 106, task.clj:279-281 (pulls of the root entity), my/root.clj:110-115 |
| `select-keys m [literal keys]` | literal vector | template.clj:153, task.clj:316 |
| **The whole map** `seon.config/config` | n/a | fx.clj:151 (`inputs`), root.clj:684-688 (`provider`, `with-provider`, then `cfg/facts`, root.clj:759), fork_server.clj:92 and 106, e2e.clj:184, check.clj:945 (`hash`) |
| **The whole map from the db** `seon.config/from-db` | every declared key | render/proc.clj:153, then the config tab render/ui.clj:796 and :379; check.clj:942 and 945 |
| **Config facts read by namespace** | `"seon.config.agent"`, `"seon.config.merge"` strings | reconcile.clj:103 `watched-ns` (root-entity datoms that `cfg/facts` wrote) |
| **The file itself** | n/a | `cfg/load-layers`, `cfg/path`, `cfg/keys-declared`: config_test.clj:77-86, 194-218, 255 |

No keyword is built at run time: `grep 'keyword "seon.config'` finds nothing. 264 `:seon.config.*`
occurrences in src: 131 in `value` calls, and the rest are docstrings (strings, not keywords),
literal arguments to `bounded` and similar, and the map and db readers above.

## 2. Do compiler facts record keywords?

No. `unit-account` (program.clj:156) puts only qualified **symbols** of quoted or const data into
`:refs` (`quoted-vars`, program.clj:145). `account`, `account-tx` and `form-row` carry no keywords. `names`
(program.clj:551) collects qualified keywords, but only from schema and contract forms.

It would be cheap to add, because Jolt's IR already holds them. A keyword literal is a `:const` node with
`:val` the keyword (jolt-core/jolt/analyzer.clj:1540), and `unit-account` already visits every
`(:quote :const)` node through `datum`. One more line there could collect
`(filter #(and (qualified-keyword? %) (str/starts-with? (namespace %) "seon.config")) (tree-seq coll? seq (datum n)))`
into a `:config-keys` set. That gives a form fact `:form/config-keys`, written and read wherever the
form's other card-many facts are (account-tx, form-row, merge's set-form-tx). These are the same
places brief 3, row 5 folds into one `form-attrs` vector, so after brief 3 the fact is one entry there.
It reads the IR, so it sees a macro's expansion and skips docstrings. Reading `:src` again (or a
regex over it) would be the §9 mistake: code re-read where compiler facts exist.

## 3. Payoff

**Keys.** default.edn declares 93 top-level keys (plus nested provider and model entries). 90 appear
in some src form. `proofs/jobs`, `root/runs-dir` and `source/test-results-dir` appear in none (they are
read only through `#ref` and scripts).

**Per test (static, lower bounds).** 440 tests:
- 81 reach a whole-config reader: `seon.test-store/binary-sh` 63, `config-input` 10, `from-db` 12,
  `facts` 4 (some reach several). These must take the whole config anyway.
- The other 359 reach a median of 4 keys (max 28), and 146 of them reach none.
- Most-reached keys: `ai/model`, `ai/models` and `ai/providers` 208 (all through `resolve-in`, so
  every `cfg/value` reader reaches them); the six `render.*` budget keys about 87;
  `contract/guarantees` 65 (really nearly every test, through the `defn` macro); `store/eid-lease` 60.
- Per key: median 3 tests, p90 65, max 208. 28 keys are reached by no test outside the 81.
- One key edited: about 100 of 440 rerun (static mean: its reach plus the 81), against 440 today.
  The true figure is higher.

**What actually differs between configs:**
- **Checkout vs checkout** (bin/test in two worktrees): only the ports in `config/local.edn`
  (dropped today), and `#seon/checkout` paths (normalized to `<checkout>` today). After shrink2 the
  configs are identical, so (B) adds nothing here.
- **An agent's root vs bin/test.** bin/root sets `SEON_CHECKOUT=$G` (the generation directory),
  `SEON_RUNS=$ROOT/runs` (an absolute path outside `$G`, so `<checkout>` does not normalize it),
  `SEON_SOURCE_ROOT` and the owner's profile. bin/test sets `SEON_PROFILE=scripted`. Root computes
  the inputs its agents use in its own process (`fx/read-source`, fx.clj:81, via
  `import-source!`, root.clj:585). The differing keys:
  - place keys (`root/runs-dir`, `root/store-path`, `root/main`, `source/root`): shrink2 drops these;
  - **profile keys** `ai/provider` (`:none` or `:openai-compat` vs `:scripted`) and `ai/script`:
    these remain after shrink2.

  So under (A) **no** result is shared between an agent and a checkout, because of the profile alone.
  Under (B), static count: only 2 tests outside the 81 reach `ai/provider`/`ai/script`, so about
  357/440 (81%) would share, or fewer once real reach is counted. Whether agents read checkouts'
  greens at all (the shared `test-results` directory against an agent's per-digest `:proved`) is
  prove's design. Confirm it before counting this as a win.
- **Config edits**: 50 of 533 non-merge commits on main since 2026-10-16 touch default.edn, but **41 of
  those 50 also touch another env-input file** (38 a schema file under resources/seon/schemas: a new
  key needs its schema entry). Every one of those reruns all 440 whatever `config-input` does. (B)
  alone saves reruns on about 9 commits (2%).

**The bigger lever is the env-input files, not the config keys.** 182 of 533 non-merge commits (34%)
touch some env-input file:
- `resources/seon/schemas`: 165 of 726 commits, merges included;
- `bin/`: 124;
- default.edn: 81.

Each of these reruns every test. Schema entries are already per-test edges in `reach-digests`, but only when the program
db holds `:schema/key` entities. bin/test's `program-db` (test/test_runner.clj:30) imports only
`.clj`, so today the schema files can be covered only by `:env`. This is outside (c), and it is the
next question to ask.

## 4. What defeats per-key reads, and how each is handled

| Defeater | Where | Conservative handling |
|---|---|---|
| The whole map handed on | `cfg/config` in root.clj:684-688, fork_server.clj:92/106, e2e.clj:184, check.clj:945; `fx/inputs` | any reached form outside the resolver that references `seon.config/config` or `sources` takes the whole config |
| The whole map written and read back | `cfg/facts` (root.clj:759), `from-db` (render/proc.clj:153, check.clj:942), `watched-ns` (reconcile.clj:103) | `facts` and `from-db` are whole-config readers (reconcile only reads what `facts` wrote) |
| The file read directly | `load-layers`, `path`, `keys-declared` (config_test) | whole-config readers, and they keep default.edn's blob as an input |
| A child process with its own config | `seon.test-store/binary-sh` (63 tests); forked agents | whole-config reader (these tests already take `:tree`) |
| A key passed as a value | `print/bounded k` | sound: the keyword is a literal in the calling form, which is reached |
| A resolver keyed by data (task, then model, then provider) | `resolve-in` | sound: `k` is still a literal; `resolve-in`'s own form names `ai/model`, `ai/models` and `ai/providers`, so every reader takes those |
| `#env` values | `ai/model`, `ai/max-tokens`, `ai/base-url`, `web/utc-offset`, `contract/guarantees`, `native/probe-lib`, ... | no problem: the resolved value is what gets digested, per key |
| A keyword built at run time | none today | the one unsound case: a missed read reuses a stale green |
| A config read at namespace load | `seon.contract` (`guarantees`), print/select.clj `limits` | sound: a `def` form is reached like any other |

## 5. Recommendation

**(A) [recommended now]: keep whole-config-minus-place-keys (shrink2's change) and stop.** It
is simplest and already gives checkout-to-checkout sharing, which is the common case: worktree sessions
and merges run bin/test. Then measure one thing after shrink2 merges: diff the `config-input` text
of the standing root's recorded inputs against bin/test's, expecting only `ai/provider` and
`ai/script`.

**(B) later, and only if agent-to-checkout sharing is shown to matter for turns to green.** Per-test
config keys:
- **Where keys are collected:** `unit-account` collects `:config-keys` from const and quote nodes
  (one line), carried as `:form/config-keys` through `account` → `account-tx` → `form-row` and merge's
  `set-form-tx`. Do it after brief 3, when this is one `form-attrs` entry.
- **Inputs:** `fx/inputs` takes the config and the default.edn blob out of `:env` and adds
  `:config {key value-text}` (normalized as now: place keys and ports out, `<checkout>`). This
  needs one more `:seon.program/inputs` entry.
- **Digest:** `reach-digests` takes the union of reached forms' `:config-keys` and digests
  `(select-keys config keys)`. When a reached form references a var in a declared
  `config-readers` set, it digests the whole config plus the default.edn blob instead. The set holds
  `seon.config/config`, `sources`, `facts`, `from-db`, `load-layers`, `path`, `keys-declared` and
  `seon.test-store/binary-sh`, exempting the forms that define `value`, `resolve`, `resolve-in`,
  `provider` and `with-provider`. This is the existing `tree-readers`/`env-readers` pattern, not a new
  mechanism.
- **Write set:** program.clj (`unit-account`, `reach-digests`, plus 0 to 4 lines in account, account-tx and
  form-row depending on brief 3), merge.clj (`set-form-tx`, 0 to 1 line), fx.clj (`inputs` and
  `config-input`), the program schema edn (`:form/config-keys`, inputs `:config`), and
  program_test.clj (`the-config-in-force-is-an-input` becomes three cases: an unread key is no input;
  a read key is; a whole-config reader takes all).
- **Size:** about +20 lines in src and +25 in tests.
- **Risks:**
  1. A keyword built at run time would reuse a stale green. There are none today; a `bin/laws` step
     could flag `(keyword "seon.config…` if one ever appears.
  2. It conflicts with brief 3 and shrink2 in program.clj, merge.clj and fx.clj, so it must come after
     both.
  3. The payoff is about 2% for config edits until schema files and default.edn's blob leave `:env`,
     and that needs bin/test's program db to import schema entries (a separate task).
