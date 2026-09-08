"""Extract exact AI source blocks from a saved Juniper HTTP response."""
from html.parser import HTMLParser
from pathlib import Path


class Sources(HTMLParser):
    def __init__(self):
        super().__init__()
        self.active = False
        self.parts = []
        self.sources = []

    def handle_starttag(self, tag, attributes):
        if tag == "pre" and dict(attributes).get("class") == "seon-debug-source":
            self.active = True
            self.parts = []

    def handle_data(self, text):
        if self.active:
            self.parts.append(text)

    def handle_endtag(self, tag):
        if tag == "pre" and self.active:
            self.sources.append("".join(self.parts))
            self.active = False


parser = Sources()
parser.feed(Path("tmp/record-render-juniper-component.html").read_text())
if not parser.sources:
    raise SystemExit("No AI source blocks found")
content = "\n\n".join(parser.sources) + "\n"
Path("docs/prds/context-generation/research/record-render-juniper-component-ai-2026-09-08.txt").write_text(content)
print({"source_blocks": len(parser.sources), "utf8_bytes": len(content.encode())})
