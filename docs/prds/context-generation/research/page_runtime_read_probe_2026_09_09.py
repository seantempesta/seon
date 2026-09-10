"""Inspect the actual debug HTML saved by curl; fail if history is absent."""

import hashlib
from html.parser import HTMLParser
import json
from pathlib import Path
import sys


class Context(HTMLParser):
    def __init__(self):
        super().__init__()
        self.stack = []
        self.state = []
        self.prompts = []
        self.responses = []
        self.statuses = []

    def handle_starttag(self, tag, attrs):
        classes = dict(attrs).get("class", "").split()
        if tag in {"input", "meta", "link", "br", "hr"}:
            return
        self.stack.append((tag, classes))
        if "seon-eval-prompt" in classes and self.inside("seon-debug-evaluations"):
            self.prompts.append("")
        if "seon-eval-response" in classes and self.inside("seon-debug-evaluations"):
            self.responses.append("")

    def handle_endtag(self, tag):
        for i in range(len(self.stack) - 1, -1, -1):
            if self.stack[i][0] == tag:
                del self.stack[i:]
                break

    def inside(self, css_class):
        return any(css_class in classes for _, classes in self.stack)

    def handle_data(self, data):
        if self.inside("seon-debug-state"):
            self.state.append(data)
        if self.inside("seon-debug-evaluations"):
            if self.inside("seon-eval-prompt"):
                self.prompts[-1] += data
            if self.inside("seon-eval-response"):
                self.responses[-1] += data
        if self.inside("seon-debug-system-form") and self.stack[-1][0] == "strong":
            self.statuses.append(data)


raw = Path(sys.argv[1]).read_bytes()
page = Context()
page.feed(raw.decode("utf-8"))
state = "".join(page.state)
print(json.dumps({"bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest(),
                  "state": state, "entries": len(page.prompts),
                  "responses": len(page.responses), "prompts": page.prompts,
                  "prospective_statuses": page.statuses}, ensure_ascii=False, indent=2))
assert page.prompts, "Context now is absent or empty"
assert f"{len(page.prompts)} evaluations · continuing" in state, state
assert len(page.responses) == len(page.prompts), "A stored entry has no response"
