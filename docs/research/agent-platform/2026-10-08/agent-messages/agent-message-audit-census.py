#!/usr/bin/env python3
"""Static message-site census; no JVM, evaluation, or repository mutations.

Run from the checkout root. Emits JSON; use --markdown for the site appendix.
Estimates use Seon's shipped prior (floor of UTF-16 length / 3.2), verified
against seon.ai.tokens/estimate by the accompanying live MCP transcript.
This lexer inventories source expressions, not evaluated message sizes.
"""
import argparse
import hashlib
import json
import math
import pathlib
import re


def tokens(text):
    return math.floor(len(text.encode('utf-16-le')) / 2 / 3.2)


def lex(source):
    pattern = r';[^\n]*|"(?:\\.|[^"\\])*"|\\(?:[^\s\[\]{}()]+|.)|[()\[\]{}]|[^\s,()\[\]{}";]+'
    return [(m.group(), m.start(), m.end()) for m in re.finditer(pattern, source, re.S)
            if not m.group().startswith(';')]



# Editorial drafts. Preserve every displaced value in the variant's declared
# members; these literals are not permission to discard diagnostic evidence.
MESSAGE_EDITS = dict(line.split('|', 1) for line in """
my.background/invalid-call|Invalid background call.
my.program/read-result|Cannot read program facts.
my.program/locate|No such declaration.
my.program/propose-merge!|Merge proposal refused.
my.program/open-proposal|No open merge proposal.
my.program/refusal|Declaration still has referrers.
my.program/source|No such declaration.
my.program/declaration-pair|Neither branch declares this symbol.
my.program/supplied-context|Evaluation context unavailable.
my.task/claim!|Task already assigned.
my.task/unassigned|No task assigned.
seon.agent/identity|No such agent.
seon.agent/archived?|No such agent.
seon.ai/parsed-completion|Provider returned no assistant text.
seon.ai/send-request|Provider request failed.
seon.ai/truncation|Provider stream ended before completion.
seon.await/diagnostic|Await did not complete.
seon.batch/diagnostic|Evaluation stage failed.
seon.batch/read-step|Reader returned an invalid source span.
seon.batch/evaluate-step|Invalid observed writes.
seon.batch/derive-step|Evaluation facts could not be derived.
seon.batch/settled-into|Settlement names an unknown form.
seon.batch/evaluate-batch|Evaluation was not recorded.
seon.call-preparation/incoherent|Invalid default argument.
seon.call-preparation/unavailable|Argument unavailable.
seon.call-preparation/supply-from-world|Required evaluation input unavailable.
seon.call-preparation/supply-from-row|Argument supplier failed.
seon.call-preparation/prepare|Ambiguous argument count.
seon.cluster.message/inbound-tx|Message refused.
seon.cluster.message/delivery|No such recipient.
seon.cluster.message/read|No such message.
seon.cluster.message/send!|Recipient branch unavailable.
seon.cluster.registry/branch-commit-id|No such branch.
seon.cluster.prompt/effective-ai-settings|Cluster name required.
seon.cluster.source/record-results-at-head!|Recording schema requires adoption.
seon.cluster.source/declarations-held-tx|Merge destination changed.
seon.cluster.wake/declarations-refusal|Invalid wake declarations.
seon.cluster.wake/arming-refusal|No arming attribute declared.
seon.cluster.wake/deliver!|Agent wake was not delivered.
seon.cluster/operator-root|Operator root is undeclared.
seon.cluster/call-with-program-change|Loaded program is unconfirmed.
seon.cluster/ensure-entity!|Agent initialization is incomplete.
seon.config/agent-cluster|Agent cluster is ambiguous.
seon.config/effective-in|Required configuration is missing.
seon.context/selection-refusal|Evaluation selection is unavailable.
seon.db/foreign-connection-error|Write connection names another branch.
seon.db/pull-logical|Vector requires a complete database value.
seon.db/replay-read|Read operation cannot be replayed.
seon.db/loaded-builder|Loaded program is ambiguous.
seon.db/read-declarations|Database schema unavailable.
seon.db/unknown-attribute-error|Undeclared attribute.
seon.db/value-type-error|Invalid attribute value.
seon.db/lookup-ref-error|Lookup attribute must be unique.
seon.db/nil-query-input-error|Query input is nil.
seon.db/missing-pull-selector-error|Pull selector required.
seon.db/validate-pulled-value|Invalid pulled value.
seon.db/pull-call|Invalid pull arguments.
seon.db/stored-value-check|Stored schema does not compile.
seon.db/write-owned-values-error|Invalid component value.
seon.db/write-render-target-error|Render function is undeclared.
seon.db/removed-definition-error|Declaration still has referrers.
seon.db/write-agent-retraction-error|Agent identity cannot be retracted.
seon.db/write-digest-error|Definition digest does not match.
seon.db/write-report-error|Recorded argument counts disagree.
seon.db/retention-check|Test evidence must be retained.
seon.db/transact-call|Transaction outcome unknown.
seon.edit/lines|Source window changed.
seon.effect/dispatch|Execution context unavailable.
seon.effect/request*|Execution context unavailable.
seon.env/absent-member-error|Environment member unavailable.
seon.env/require-environment|Cluster environment required.
seon.error.refusal/construction-refusal|Invalid error value.
seon.error/fact-source|Stored error evidence unavailable.
seon.fault/record!|Error recording branch unavailable.
seon.fn/task-refused!|Invalid task declaration.
seon.fn/analyze-forms|Namespace reference does not resolve.
seon.health/refused-schemas|Finding schema matches identity alone.
seon.instrument/violation|Contract check failed.
seon.instrument/wrap-interpreted|Contract admission limits unavailable.
seon.instrument/compiled-wrapper|Contract error could not be built.
seon.instrument/apply!|Function contract does not compile.
seon.maintenance/collect!|Collection is disabled.
seon.render.hiccup/shorthand|Empty tag shorthand segment.
seon.render.ns/unassigned|Namespace required.
seon.render.transcript/missing-selected-run|Selected evaluation history unavailable.
seon.render.value/shown-chain|Error evidence could not be printed.
seon.render.value/insufficient-budget|Printer budget is insufficient.
seon.render.walk/connection-observation|Additional references omitted.
seon.render.web/turn-function-result|Function unavailable.
seon.render.web/ensure-namespace-owner!|Namespace owner was not created.
seon.render/request-profile|Render profile required.
seon.render/source-provenance-error|Source identity required.
seon.render/ambiguity|More than one renderer matches.
seon.render/declared-projection|Renderer returned executable entries.
seon.schema.edn/refusal|Schema declaration refused.
seon.schema/runtime-predicate|Schema predicate cannot resolve.
seon.schema/unresolved-reference-refusal|Undeclared schema reference.
seon.schema/assert-config-display!|Configuration label required.
seon.schema/render-contract-refusal!|Invalid render contract.
seon.schema/unregister!|Schema removal requires an evaluation.
seon.schema/refuse-projection-source|Database value required.
seon.sci.admit/refuse-over-bound!|Storage bound exceeded.
seon.sci.eval/carried-cluster-name|Cluster name required.
seon.sci.eval/database-effective-config|Cluster configuration required.
seon.sci.eval/install-function-from-database!|Function declaration unavailable.
seon.sci.eval/install-row!|Installed and committed declarations disagree.
seon.sci.eval/declaration-absent|Declaration required.
seon.sci.eval/interpretation-error|Declaration cannot be interpreted.
seon.sci.eval/call-detecting-program-change|Loaded program changed during evaluation.
seon.sci.eval/acquire-program!|Compiled definition unavailable.
seon.sci.eval/fork-for-turn|Result handles could not be bound.
seon.sci.kernel/invoke|SCI Var unavailable.
seon.shell.jvm/write-array!|Process input exceeds storage bound.
seon.shell.jvm/copy-blob-stdin!|Process input exceeds storage bound.
seon.shell.jvm/run|Process did not complete.
seon.task/refused|Task operation refused.
seon.test.runner/reach-unavailable|Test dependency evidence unavailable.
seon.test.runner/reach-digests|Definition evidence unavailable.
seon.test.runner/record-latest-tx|Test definition was retracted before recording.
seon.test/admission-refusal!|Inconsistent test evidence.
seon.test/arm-first-loads!|Test definition cannot be loaded.
seon.turn.batch/changed-since-basis|Definitions changed since this evaluation read them.
seon.turn/episode-exhausted!|Agent evaluation limit reached.
seon.turn/turn-completion-error|Agent evaluation did not complete.
seon.turn/dial-absent|Evaluation bound is undeclared.
seon.turn/write-refusal-error|Consecutive evaluation writes refused.
seon.turn/publish-row|Completion must be the last form.
seon.operator.prepl/reply|Reply could not be printed.
""".strip().splitlines())


def census(root):
    rows = []
    renderers = set()
    for directory in ('src', 'resources/seon', 'script', 'bin'):
        for path in sorted((root / directory).rglob('*')):
            if not path.is_file() or (path.suffix not in ('.clj', '.cljc', '.edn')
                                         and path.name not in ('seon-hook', 'test-check')):
                continue
            if 'tasks' in path.parts:
                continue  # historical/user-authored task prose is content, not a template
            source = path.read_text()
            items = lex(source)
            ends = {}
            stack = []
            parents = {}
            children = {}
            for i, (word, _, _) in enumerate(items):
                if word not in (')', ']', '}') and stack:
                    parents[i] = stack[-1]
                    children.setdefault(stack[-1], []).append(i)
                if word in ('(', '[', '{'):
                    stack.append(i)
                elif word in (')', ']', '}') and stack:
                    ends[stack.pop()] = i
            ns = re.search(r'\(ns\s+(?:\^\{[^}]*\}\s+)?([^\s()]+)', source)
            namespace = ns.group(1) if ns else path.stem
            definitions = []
            for opening, members in children.items():
                if items[opening][0] != '(' or not members:
                    continue
                if items[members[0]][0] not in ('defn', 'defn-', 'def', 'defonce', 'defmacro'):
                    continue
                for member in members[1:]:
                    name = items[member][0]
                    if name.startswith('^') or name == '{':
                        continue
                    definitions.append((items[opening][1], name))
                    break
            definitions.sort()
            for i, (word, start, _) in enumerate(items[:-1]):
                kind = None
                parent = parents.get(i)
                map_key = (parent is not None and items[parent][0] == '{'
                           and children[parent].index(i) % 2 == 0)
                if map_key and parent > 0 and items[parent - 1][0].startswith('#:') and word.startswith(':') and '/' not in word:
                    word = ':' + items[parent - 1][0][2:] + '/' + word[1:]
                if map_key and word in (':seon.error/message', ':seon.error/fix', ':seon.repl/fix', ':error/message'):
                    kind = word
                elif map_key and word == ':seon.render/operations':
                    kind = 'operations'
                elif map_key and word == ':seon.render/ai':
                    kind = 'AI attachment'
                    target = items[i + 1][0].lstrip("'")
                    if '/' in target and not target.startswith(':'):
                        renderers.add(target)
                elif (re.search(r'(^|/)(?:refused!?|refuse!|fail!|ex-info|branch-refusal|refused-error)$', word)
                      and parent is not None and items[parent][0] == '('
                      and children[parent][0] == i
                      and items[i + 1][0].startswith('"')):
                    kind = 'constructor message'
                if not kind:
                    continue
                j = i + 1
                end = items[ends.get(j, j)][2]
                expression = source[items[j][1]:end]
                # Skip accesses and schema field declarations; retain actual value expressions.
                if expression.startswith(':') or expression in (']', '}', ')'):
                    continue
                owner = next((name for offset, name in reversed(definitions) if offset <= start), '(resource)')
                literals = []
                for value, _, _ in items[j:ends.get(j, j) + 1]:
                    if value.startswith('"'):
                        try:
                            literals.append(json.loads(value))
                        except ValueError:
                            literals.append(value[1:-1])
                treatment = ('O' if kind == 'operations' else 'R' if kind == 'AI attachment'
                             else 'F' if kind.endswith('/fix') else 'E')
                proposal = dict(E='One fixed variant message; move interpolated values to declared error members.',
                                F='One runnable form over the actual input or result; otherwise a short imperative.',
                                O='Keep the existing operation as one whole runnable form; no prose copy.',
                                R='Return declared data; the value printer owns its only printed representation.')[treatment]
                producer = namespace + '/' + owner
                fixed = (MESSAGE_EDITS.get(producer)
                         if kind in (':seon.error/message', 'constructor message')
                         and (expression.startswith('(str') or tokens(''.join(literals)) > 25) else None)
                proposed = json.dumps(fixed, ensure_ascii=False) if fixed else expression
                after_literals = fixed if fixed else ''.join(literals)
                rows.append(dict(proposed=proposed, proposed_literal_tokens=tokens(after_literals),
                                 proposed_size_basis='literal' if fixed or expression.startswith('"') else 'literal lower bound',
                                 source_sha256=hashlib.sha256(source.encode()).hexdigest(), proposal=proposal, after_token_bound=dict(E=16, F=40, O=80, R=300)[treatment], file=str(path.relative_to(root)), line=source.count('\n', 0, start) + 1,
                                 producer=producer, kind=kind, current=expression,
                                 literal_tokens=tokens(''.join(literals)),
                                 size_basis='literal' if expression.startswith('"') else 'literal lower bound'))
    return rows, sorted(renderers)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--markdown', action='store_true')
    args = parser.parse_args()
    rows, renderers = census(pathlib.Path.cwd())
    if not args.markdown:
        print(json.dumps(dict(sites=rows, renderers=renderers), ensure_ascii=False, indent=2))
        return
    print('| Producer/site | Class | Current source expression | Literal estimated tokens | Proposed treatment |')
    print('|---|---|---|---:|---|')
    for row in rows:
        current = row['current'].replace('\n', ' ').replace('|', '\\|').replace('`', '&#96;')
        treatment = ('O: whole runnable forms, ≤80 tokens per operation' if row['kind'] == 'operations'
                     else 'R: data projection, ≤300 tokens by default' if row['kind'] == 'AI attachment'
                     else 'F: runnable form or ≤16 tokens of prose' if row['kind'].endswith('/fix')
                     else 'E: fixed variant message ≤16 tokens; values remain data')
        print(f"| `{row['producer']}` · `{row['file']}:{row['line']}` | {row['kind']} | `{current}` | {row['literal_tokens']} ({row['size_basis']}) | {treatment} |")


if __name__ == '__main__':
    main()
