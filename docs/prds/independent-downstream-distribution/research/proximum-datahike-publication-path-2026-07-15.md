---
type: research
status: completed
tags: [research, component, database, flow]
---

# Proximum and Datahike publication path — 2026-07-15

## Question and decision

How should the guarded Proximum branch-force work and its Datahike integration
be published so that a clean Seon source build, and later a downstream build
without a Seon checkout, consume exact compatible code?

Use public HTTPS Git dependencies pinned by full SHA for the maintained forks,
then bind those SHAs and the resulting writer digest into the coordinated Seon
release manifest. Do not depend on a local Maven install, a dependency
worktree, `reference-code/`, a mutable branch, or an unpublished Clojars
version.

The immediate autonomous path is:

1. forward-port Proximum commit
   `fb6572c8613cd05c3d6132597168683bb57e7511` from upstream `v0.1.25` onto
   upstream `v0.1.26` commit
   `c1235796a844a35985e683a05fe99e1e27e12ba6`;
2. add a cold `:deps/prep-lib` operation that compiles the checked-in generated
   Java source without regenerating it, publish the resulting commit in a
   public `seantempesta/proximum` fork, and pin that full SHA in Seon's
   `:writer` alias;
3. finish the Datahike integration on selected commit
   `417649383c65e13f15ea41d394fb1ed742477965`, add `src-secondary` to the Git
   dependency's declared `:paths`, push the result to the already-public
   `seantempesta/datahike` repository, and pin that full SHA in both `:writer`
   and `:cljs`; and
4. remove Seon's `reference-code/datahike/src-secondary` build input and prove
   a cold public-Git-only writer/CLJS basis before rebuilding the standalone
   writer.

An upstream Proximum pull request and later canonical Maven release are
desirable follow-up work, but they need not block Seon's exact source build.
Publishing the current local Proximum commit as Maven `0.1.26` is invalid:
upstream already owns that version with different bytes.

## Audited state

This audit read Seon at `49b55357ad4e892bda39e321126f3bba8b538497`
without changing dependency checkouts, remotes, runtime source, or live
clusters.

| Input | Current identity | Publication fact |
|---|---|---|
| Proximum production dependency | Maven `org.replikativ/proximum` `0.1.25`; local guarded-force commit `fb6572c…` | `fb6572c…` exists only on local branch `codex/guarded-force-branch`. The only configured remote is `https://github.com/replikativ/proximum.git`; it does not contain that commit. `https://github.com/seantempesta/proximum.git` does not exist. |
| Proximum upstream | tag `v0.1.26` and commit `c1235796…` | The public tag and Clojars metadata both select `0.1.26`; its `proximum.versioning` still has only create-new `branch!`, not guarded existing-destination force. |
| Datahike | Git SHA `417649383c65e13f15ea41d394fb1ed742477965` | Publicly reachable at `https://github.com/seantempesta/datahike.git`, branch `sync-upstream`; its `:deps/prep-lib` cold-compiles checked-in generated Java. The guarded secondary integration is still an uncommitted dependency-worktree change at audit time. |
| Konserve | Git SHA `df6818d43ea3363a808cd051c0d68917f1b987a9` | Already selected directly in both maintained Seon bases; no change is required for this integration. |
| Seon writer | `:writer` alias plus `build.clj/writer-uber` | The basis selects Datahike by Git SHA but Proximum by Maven `0.1.25`; it also adds and copies `reference-code/datahike/src-secondary`, making source-secondary availability checkout-local. |
| Seon CLJS | `:cljs` alias | Datahike and Konserve are exact Git overrides. Proximum is JVM-only and is neither required nor desired in the CLJS/downstream SDK basis. |

The effective writer tree confirms Proximum `0.1.25`, Datahike `4176493…`,
and Konserve `df6818d…`. The effective CLJS tree confirms Datahike
`4176493…`, Konserve `df6818d…`, superv.async `3e6ed75…`, and partial-cps
`1e119b0…`, with no Proximum dependency.

The current standalone writer digest is
`76f348f55784c36242bdd1d5b9dea2934003685c43b101466c8f80a9efcd180c`.
Its contents include compiled Proximum Java and Clojure classes plus the AOT
Datahike Proximum adapter. That proves the final downstream production package
does not resolve Proximum or Datahike at startup. It does not prove which
Datahike Git SHA was compiled: the current uberjar has Proximum Maven metadata
but no retained Datahike version resource. The release compatibility manifest
must therefore carry both source SHAs and the normalized writer digest.

## Why public Git SHA is the one current mechanism

### Datahike is already a valid Git dependency

Datahike declares:

```clojure
:deps/prep-lib {:ensure "target/classes"
                :alias :build
                :fn compile-java}
```

and checks in its generated Java API. A cold tools.deps checkout can therefore
fetch the exact commit, compile its Java once, and expose `src`,
`target/classes`, and `resources` without a Maven fork. Seon's shared
dependency-preparation owner already invokes `clojure -X:deps prep` for the
selected writer and CLJS aliases.

The remaining defect is `src-secondary`: Datahike's release JAR includes that
directory through `config.edn`, but its Git dependency `:paths` omits it. Seon
currently compensates with a protected local checkout path in `deps.edn`,
`build.clj`, and the writer input digest. Adding `src-secondary` to the
Datahike Git dependency's paths makes the adapter available from the exact
public SHA and permits deletion of that local build dependency. Merely pushing
the integration without this path change would leave a fresh producer build
incomplete.

The final integration SHA, not its mutable branch name, must replace
`417649…` in both Seon aliases. The CLJS pin is required because the pod
compiles and runs Datahike's CLJS source. The writer pin is required because
the JVM server owns versioning and the Proximum adapter.

### Proximum needs public Git plus cold preparation, not local Maven

Proximum's `deps.edn` already lists `src-java` and `target/classes`, and the
guarded-force commit updates both the generator and the checked-in generated
`ProximumVectorStore.java`. It does not declare `:deps/prep-lib`. Clojure's
classpath does not compile Java source merely because `src-java` is a path, so
changing Seon's coordinate directly from Maven to Git would omit required
classes on a cold machine.

The reproducible Git-dependency fix is a separate prep function that only
invokes tools.build `javac` over the checked-in `src-java` tree with the
declared basis, `--release 22`, and the vector module. It must not call the
current `compile-java`, because that function first starts another Clojure
process and regenerates tracked source through the development alias. Release
CI separately regenerates the Java API and proves a clean diff; dependency
preparation compiles the already-reviewed bytes. This is the same division of
authority Datahike uses for its checked-in generated Java.

The prep output is reproducible at the source-contract level because all Java
inputs are checked in, the dependency basis is declared, the compiler release
is fixed, and the Seon compatibility manifest binds the required JDK. The
acceptance gate must still build twice from clean Git caches and compare the
normalized writer-jar digest; checked-in generation alone is not evidence of
byte-for-byte compiler reproducibility.

The guarded-force commit has one parent, upstream `v0.1.25` commit
`5f7142d532aa173071f5651af91414b983d7320f`. Upstream `v0.1.26` is the
different commit `c1235796…`, published on 2026-06-23. Forward-porting is
therefore mandatory. A blind push of `fb6572c…` would regress upstream's
canonical persistent-sorted-set handlers, immutable marking, and dependency
updates; a blind Maven deploy would also collide with the already-published
`0.1.26` coordinate.

After the forward port and prep change pass the full Proximum gate, create the
public fork and publish only the exact tested commit. Commands are illustrative
and deliberately were not run by this audit:

```bash
gh repo fork replikativ/proximum --clone=false

git -C tmp/dependency-worktrees/proximum-force-branch fetch origin --tags
git -C tmp/dependency-worktrees/proximum-force-branch switch \
  -c seon/guarded-force-v0.1.26 \
  c1235796a844a35985e683a05fe99e1e27e12ba6
git -C tmp/dependency-worktrees/proximum-force-branch cherry-pick \
  fb6572c8613cd05c3d6132597168683bb57e7511

# Add compile-checked-in-java + :deps/prep-lib, regenerate once, require a
# clean generated-source diff, then run the focused and complete gates.

git -C tmp/dependency-worktrees/proximum-force-branch remote add seon \
  git@github.com:seantempesta/proximum.git
git -C tmp/dependency-worktrees/proximum-force-branch push seon \
  HEAD:refs/heads/seon/guarded-force
```

Do not reuse the local branch's computed `0.1.26` version. Git identity is the
full final SHA. If Maven publication is later required for non-tools.deps
consumers, either upstream releases a new canonical version or the fork uses a
Sean-owned group and a distinct version from a tagged release commit. That is
not necessary for Seon's writer or no-source ACME contract.

## Pin and build cutover

Once both final public SHAs exist:

1. change `:writer` Proximum from Maven to the public Git SHA;
2. change Datahike to its final public integration SHA in both `:writer` and
   `:cljs`;
3. keep the root Konserve, persistent-sorted-set, logging, and SLF4J selections
   explicit so Proximum/Datahike transitive versions cannot silently replace
   the tested set;
4. remove `reference-code/datahike/src-secondary` from root `:writer`
   `:extra-paths`, `build.clj` source copying/compilation inputs, and the writer
   cache's local-input digest; and
5. package `datahike.index.secondary.proximum` through the dependency basis,
   but do not recursively AOT it or the server dependency closure. Compile the
   stable Java entry point only; it source-loads the one `seon.db.server`
   implementation when the artifact starts.

Datahike does not need a transitive Proximum dependency in its main manifest.
The optional adapter is selected only by Seon's JVM writer, so Seon's
`:writer` alias remains the one compatibility-set owner. Adding Proximum to
Datahike main deps would make every CLJS Datahike consumer resolve a JVM-only
Java/vector library and would be incorrect.

The future ACME modes then divide cleanly:

- a no-source development SDK resolves the exact public Datahike Git SHA for
  CLJS and consumes the released writer artifact; it never resolves Proximum;
- an immutable production package resolves no Clojure dependencies at all and
  starts the released writer uberjar plus pod closure; and
- the release manifest binds the Datahike and Proximum source SHAs, Konserve
  SHA, writer digest, database protocol version, Java requirement, and runtime/
  SDK identities as one compatibility set.

## Cold acceptance and release evidence

Run the following only after the public commits and Seon pins exist. Use a
disposable dependency cache so success cannot inherit local worktrees or Maven
installs.

```bash
export CLJ_CONFIG="$(mktemp -d)"
export GITLIBS="$(mktemp -d)"

clojure -X:deps prep :aliases '[:writer :cljs]'
clojure -Stree -M:writer
clojure -Stree -M:cljs
clojure -Spath -M:writer

bin/test-writer seon.db.restore-admin-test
bin/test-cljs seon.db.restore-test seon.client-runtime-test
clojure -T:build writer-uber
```

Before that Seon gate, run Datahike's focused secondary-integration namespace
from its own checkout against the final public Proximum Git override, then run
the complete Datahike gate. A Seon writer test cannot discover dependency-owned
test source.

Required observations:

- both trees name the final Datahike SHA; the writer tree names the final
  Proximum SHA and no `org.replikativ/proximum 0.1.25`; the CLJS tree contains
  no Proximum;
- the writer classpath obtains Datahike `src-secondary` and Proximum
  `target/classes` from the disposable Git cache and contains no
  `reference-code/` path;
- preparation starts with no ensure outputs and completes without a manual
  sequence outside the one dependency owner;
- the file-backed Datahike test proves guarded force, response-loss retry,
  equal KNN/root after reopen, later-write isolation, and release;
- the writer jar contains `datahike.index.secondary.proximum`, the guarded
  Proximum functions, and generated Java classes; its normalized digest is
  stable across a second clean build; and
- the release manifest records the two final source SHAs and that exact writer
  digest. A modified SHA/digest combination fails compatibility validation
  before database mutation.

For stronger network independence, repeat the build after populating an
explicit release cache, deny network access, and prove the same digest. That is
a producer reproducibility gate. Production ACME itself must never invoke a
mutable dependency resolver.

## Blockers and ownership

- `seantempesta/proximum` does not exist. Creating it and pushing are external
  GitHub mutations requiring the repository owner's authority.
- `fb6572c…` has not been forward-ported onto upstream `v0.1.26`; its final
  public source SHA is therefore unknown and must not be guessed in Seon.
- Proximum lacks the cold checked-in-Java prep contract needed by a Git
  dependency.
- The Datahike force-secondary integration is not committed at audit time, and
  its Git dependency still omits `src-secondary` from `:paths`; its final
  public SHA is also unknown.
- The current development artifact manifest version 3 records the writer
  digest but not maintained source identities. The independent distribution
  PRD's compatibility manifest remains the owner of that release proof.

These blockers prevent a production pin today, but not continued isolated
integration testing. Do not update Seon's root pins to local branches or local
roots as an interim compatibility mechanism.

## Implementation outcome — 2026-07-15

The audit table and blockers above remain the historical input state. The
publication path completed with these exact public HTTPS Git coordinates:

- Proximum `9846d3e79e1aee48474bc876d3d563d7137209c6`, an upstream-`v0.1.26`
  descendant with guarded generation publication and cold checked-in-Java
  preparation;
- Konserve `b5c99bc02a7175652a610324215288b78551801f`, upstream `0.9.359` plus
  the legacy-header reader and idempotent absent Node filestore deletion; and
- Datahike `9ada755087228e10cfb179fa5779ce227a6ed220`, which retains the public
  query-dependency projection, current upstream awaited-delete/GC safe-point
  fixes, guarded secondary force, protected regressions, and `src-secondary`
  in its Git paths.

Public Proximum proof is 181 tests/9,922 assertions. The focused Datahike
secondary/versioning gate is 108/570 across all three index backends, and its
Node gate is 105/824 after the Konserve correction. Publication is therefore
settled; the remaining boundary is Seon's root pin/build-input cutover, cold
dependency preparation, stable writer digest, version-4 manifest, and selected
file-backed restore proof.

The root cutover then falsified the audit's original AOT assumption. Two clean
`writer-uber` builds from identical inputs produced different normalized
digests because recursive Clojure AOT emitted nondeterministic captured-local
slot order in transitive dependency classes. The one artifact mechanism now
compiles deterministic `java/seon/DatabaseServerMain.java` and lets that class
`require` and invoke the source-loaded `seon.db.server/-main`; `:gen-class` and
Clojure AOT are removed rather than supplemented by a second server.

Commit `be30f420` records the corrected artifact mechanism. Two clean builds
share normalized digest
`d7011dacb7192decc826b37b014502ee372f362bc26a4a0c7e44a56ebd4e2deb`. Their
raw ZIP bytes may differ in metadata, which the established sorted-entry-name
and entry-byte digest intentionally excludes. `java -jar` reaches the real
writer preflight and exits 11 only because `SEON_EMBED` is absent. The remaining
publication gate is default artifact/runtime proof and the explicit downstream
handoff, not more AOT tuning.
