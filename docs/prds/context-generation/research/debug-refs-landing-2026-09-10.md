---
type: research
status: active
tags: [render, debug-page, identity]
---

# Debug reference identities — 2026-09-10

Read end to end: `AGENTS.md`,
`docs/seon/issues/runtime-block-html-is-raw-ids-and-instants.md`, and
`docs/seon/issues/archive/debug-page-blocks-are-hand-labelled-and-squeezed.md`
(the requested top-level path was archived in `e23e74022`). Read all of
`src/seon/render/block.clj` and the reference collection, metadata, link,
and header owners in `src/seon/render/web.clj`. Also read the roadmap entry,
working edge, and Clojure, Datahike, REPL, testing, and Datastar UI skills.

## Dependency ledger and decision

- `reference-code/datahike/src/datahike/schema.cljc`: installed schema
  properties carry `:db/unique`; even `:db/id` has identity metadata.
- `src/seon/schema/datahike.clj`, `resolve-malli-form-in` and
  `malli->datahike-attr-in`: chase registry aliases before installing
  identity properties. The header consumes this installed result.
- `src/seon/db.clj`, `identity-attributes`: the existing immutable database
  authority for installed identity attributes, also used by the debug page
  header. No new registry, identity-name list, or alias resolver.
- `src/seon/render/web.clj`, `debug-subject-link`: existing debug route and
  cursor-reset behavior. Reference links use identity lookup refs as targets.
- `src/seon/render/block.clj`, `surface-id`: owns DOM addresses, not identity
  links in the current tree. No change needed.
- Archaeology: `baa1dde54` established schema-derived block metadata. This
  change retains that owner and deletes the numeric-reference disclosure.

The header pulls installed identity attributes for each distinct referenced
entity, excluding `:db/id`, and emits one link per identity. Identity-less
entities produce nothing. Duplicate references produce one link; there is
no reference count or disclosure threshold.

Live HTTP verification exposed reference-valued identities on plan,
settings, and runtime. Their pulled identity value is a reference map,
which must not become link text. The same resolver follows that reference
to its first installed printable identity, retaining the original unique
attribute lookup as the link target. A visited-entity set terminates cycles;
an identity-less endpoint produces no link. Datahike's `entid` in
`reference-code/datahike/src/datahike/db/utils.cljc` resolves the unique
attribute/value lookup through AVET. The regression proves a plan lookup
using its agent reference resolves back to the plan entity.

The canonical fixture also installs a synthetic alias through
`malli->datahike-attr-in`; its identity property is derived, not hand-rostered.
An earlier fixture attempt declared the alias without installing its
storage attribute and was correctly refused. The final fixture uses the
actual bridge and asserts successful installation before writing values.

## Verification

Initial supported MCP status returned unknown health/Flow with `Read timed
out`, reproducing the already-filed
`docs/seon/issues/dev-mcp-envelopes-misdirect-errors-and-sprawl-status.md`.
Supported JVM `(+ 1 1)` returned 2; a read-only database probe returned
Juniper's installed agent identity in 2,085 ms. No replacement transport,
provider request, agent message, or default lifecycle operation was used.

The initial HTTP observation still showed the old header (17 plan refs,
189 runtime refs). This was not adoption proof. The automatic publication
result was empty and the failure log said publication exceeded its bound;
an explicit in-place development adoption was requested.

Final gate results and HTTP header text are recorded below.

The HTTP extraction uses Python's standard HTML parser, selecting only the
generic block headers, so nested renderer headers are not mistaken for the
reference header. Reproduce after fetching the page into `page.html`:

```python
from html.parser import HTMLParser
from pathlib import Path

class Headers(HTMLParser):
    def __init__(self):
        super().__init__()
        self.unit = None
        self.active = False
        self.words = []
        self.rows = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "article" and "data-seon-unit" in attrs:
            self.unit = attrs["data-seon-unit"]
        if tag == "header" and attrs.get("class") == "seon-debug-value-header":
            self.active = True
            self.words = []

    def handle_data(self, data):
        if self.active:
            self.words.append(data)

    def handle_endtag(self, tag):
        if tag == "header" and self.active:
            text = " ".join(" ".join(self.words).split())
            assert ":db/id" not in text
            assert "referenced entities" not in text
            assert not any(word.strip("{}[](),").isdecimal() for word in text.split())
            self.rows.append((self.unit, text))
            self.active = False

headers = Headers()
headers.feed(Path("page.html").read_text())
assert {":seon.agent/plan", ":seon.agent/runtime"} <= {unit for unit, _ in headers.rows}
for unit, text in headers.rows:
    print(unit, text)
```

## Files touched

- `src/seon/render/web.clj`
- `test/seon/render/web_debug_test.clj`
- `docs/prds/context-generation/research/debug-refs-landing-2026-09-10.md`
- `docs/seon/issues/runtime-block-html-is-raw-ids-and-instants.md`
- `docs/seon/issues/archive/debug-page-blocks-are-hand-labelled-and-squeezed.md`

Inherited untracked `build/`, `workers/`, and `config/virtual-turns.edn`
were preserved. No turn-loop or CSS files were changed.

## HTTP header evidence

After the final renderer was hot-reloaded by in-place development adoption,
`curl --max-time 30 http://127.0.0.1:7994/ns/my.agents.juniper/debug` returned
HTTP 200, 569,285 bytes in 5.875938 seconds after the final adoption attempt
returned (an earlier capture was 563,314 bytes in 3.851229 seconds).
Both captures had identical generic header text. The extraction found five
generic headers and no numeric database ids or `:db/id` text in any of them.
The following is their normalized text, with no elision. The repeated
`juniper` links address different entities: the component and the agent.

### :seon.agent/plan

```text
:my.plan/entity :seon.agent/plan The agent owning this addressable plan component. juniper juniper juniper/read faef54087471 juniper/add juniper/save 243a8de40cb1 juniper/test 486b207e76bc juniper/define 1713121461ac juniper/again juniper/report
```

### :seon.agent/runtime

```text
:seon.runtime/entity :seon.agent/runtime The agent owns its runtime and turn history. juniper juniper 31517b46 aa071259cfd8 my.agents.juniper my.note 9fc9bc9ef8ad 404bfad994bc 9b81e22c 7b7b5d439b7b 3f81dfc4c014 3ded5c0bc8a4 71ed3d999333 9bceed705a33 e09bbaba8c59 92dfbe4f05b1 6ad7f5df7429 00ec8aca362c 8bcb99a170e7 33ce87d2182e e2e89497c3c2 5bc06febb6e1 5c999f583638 a14351cfa94b 120a8258d13b 67b1d4359350 687c5e06dc30 2b95e475795a 8ed117b81926 94f7a885645b 04ec3bd5 8f3ac8878255 0ab4ce1cb9ca 88b3faaa6dd0 25bc9c632859 9a74cc71efc6 ed1c18ce290d eebb1e89ae05 449537b127ba cbfbcf937f35 684519835c28 1cebfb832de7 3838f349cdad 7a0f7381c18b c69b0afce54c d19b85b41bbc 343ce231b984 3825b8efd613 215cd5d26bdf db011b2698c6 e3ae811fd911 f3b3fda9b944 922a727fb2da 487580d156ee 7b254915ca94 d85f43a89652 d3597309628b 5f52e29e4493 ce25113d9032 594f1130d30b 59cf96d21042 0d57333f5227 bbffdcf9f687 9ba2fbe33a1b 3723e225b109 4fd359c2511a a51f8821e5be 207994c83053 0c3a9b6257de 62febc6305ee c943e0941245 de74b17664ea e6628e29a42b cca13df62ef9 cd932dafce18 dd1e3a8d7d9c 8d280681d478 b57ab4025812 c86b0b57b860 7fa3b85feb5c 963a4045570a 92b6e3e7463f a97bcc5d21c5 3ecfd618fb1a b691a4b5aa49 145c0fa30e56 058ede47932c b81ca6e0dcda 7610195b8955 4eccce5740ab f7fda1b1f62f 040c3666f156 65c8f684e92b c057904ac55b 42b310f104a9 1a98a4970d58 1fb7c9d46552 b8b2772a1099
```

## Gate evidence

- Final `bin/test-fast seon.render.web-debug-test`: 10 tests, 66 assertions,
  zero failures/errors.
- Final `bin/test --paths <five listed paths> -- seon.render.web-debug-test
  seon.render.web-test`: 68 tests, 459 assertions, zero failures/errors;
  coordinator/tests 119 seconds. Successful root `run.Ozyhem` was removed
  by the gate. The earlier pre-reference-value revision passed 68/449 and
  is not the final proof.
- `bin/test --paths <five listed paths> --platform`: 84 tests, 505
  assertions, zero failures/errors; coordinator/tests 83 seconds.
  Successful root `run.QMC4Ro` was removed by the gate.

At 21:37Z unrelated changes appeared in reader/reply sources, tests, and issue
notes. These were preserved and never included in the lane's isolated gates;
platform uses `--paths` for the same reason. No foreign failure was attributed
or repaired. Default retained PID 23557 throughout the observed work.

## Adoption boundary and cleanup

The final explicit adoption exited 1 after logging development reload,
SCI acquisition, and JVM instrumentation. Its refusal was:
`Source changed during development adoption; the next edit must converge it.`
The subsequent supported JVM read returned adopted marker
`6aa31e7b-94d8-5ecf-961e-5c331bc52413` and published marker
`6aa323cd-40ce-50f3-92fc-82a5977bf7c3`. This proves the final hot-reloaded
renderer and HTTP headers, not a sealed complete-source adoption. The shared
hook batch also included unrelated reader/reply paths and reported exit 124.
Those paths and the later turn-loop edits were not touched by this lane.
No further adoption loop, default stop/refork/reseed, or agent message was
attempted to repair that foreign boundary.

All directly started commands completed. The gate removed its successful
roots; lane-only `tmp/debug-refs` captures and logs were removed after their
results and extraction script were recorded here. Shared publication records
and unrelated temporary roots were preserved.
