"""Extract the served Turns concern for repeatable render-pass evidence."""
from html.parser import HTMLParser
from pathlib import Path
import sys


class Turns(HTMLParser):
    def __init__(self):
        super().__init__()
        self.depth = 0
        self.parts = []

    def handle_starttag(self, tag, attributes):
        if tag == "section":
            if self.depth:
                self.depth += 1
            elif dict(attributes).get("class") == "seon-turn-history":
                self.depth = 1

    def handle_endtag(self, tag):
        if tag == "section" and self.depth:
            self.depth -= 1

    def handle_data(self, data):
        if self.depth:
            self.parts.append(data)


parser = Turns()
parser.feed(Path(sys.argv[1]).read_text())
assert parser.parts, "The Turns concern is absent"
text = "\n".join(parser.parts)
Path(sys.argv[2]).write_text(text)
print(f"{len(text.encode('utf-8'))} bytes: {sys.argv[2]}")
