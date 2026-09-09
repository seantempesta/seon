"""Read-only measurement of the default debug page's evaluation presentation."""

import json
from time import perf_counter
from html.parser import HTMLParser
from urllib.request import urlopen


class EvaluationPresentation(HTMLParser):
    def __init__(self):
        super().__init__()
        self.depth = 0
        self.entries = 0
        self.printer_summaries = 0
        self.text = []

    def handle_starttag(self, tag, attrs):
        classes = dict(attrs).get("class", "").split()
        if "seon-debug-evaluations" in classes:
            self.depth = 1
        elif self.depth:
            if tag == "div":
                self.depth += 1
            self.entries += "seon-eval-entry" in classes
            self.printer_summaries += "seon-print-summary" in classes

    def handle_endtag(self, tag):
        if self.depth and tag == "div":
            self.depth -= 1

    def handle_data(self, data):
        if self.depth:
            self.text.append(data)


started = perf_counter()
with urlopen("http://127.0.0.1:7994/ns/my.agents.juniper/debug", timeout=15) as response:
    payload = response.read()
    status = response.status
presentation = EvaluationPresentation()
presentation.feed(payload.decode("utf-8"))
text = "".join(presentation.text)
result = {
    "http_status": status,
    "elapsed_seconds": round(perf_counter() - started, 6),
    "page_bytes": len(payload),
    "evaluation_entries": presentation.entries,
    "evaluation_printer_summaries": presentation.printer_summaries,
    "evaluation_depth_labels": text.count("items, depth"),
    "inline_read_evidence": text.count(":seon.cluster.eval/read-evidence"),
}
print(json.dumps(result, sort_keys=True))
assert status == 200 and len(payload) < 300_000, result
assert presentation.entries > 0, result
assert presentation.printer_summaries == 0, result
assert result["evaluation_depth_labels"] == 0, result
assert result["inline_read_evidence"] == 0, result
