"""Extract the exact served faults concern; this does not verify browser paint."""
from html.parser import HTMLParser
from pathlib import Path
import sys

class FaultConcern(HTMLParser):
    def __init__(self, source):
        super().__init__(convert_charrefs=False)
        self.source = source
        self.offsets = [0]
        for line in source.splitlines(keepends=True):
            self.offsets.append(self.offsets[-1] + len(line))
        self.depth = 0
        self.block = None
    def source_offset(self):
        line, column = self.getpos()
        return self.offsets[line - 1] + column
    def handle_starttag(self, tag, attrs):
        if tag == 'article':
            if self.depth:
                self.depth += 1
            elif dict(attrs).get('data-seon-unit') == ':seon.error/_agent':
                self.start = self.source_offset()
                self.depth = 1
    def handle_endtag(self, tag):
        if tag == 'article' and self.depth:
            self.depth -= 1
            if self.depth == 0:
                self.block = self.source[self.start:self.source_offset() + len('</article>')]

if __name__ == '__main__':
    parser = FaultConcern(Path(sys.argv[1]).read_text())
    parser.feed(parser.source)
    assert parser.block is not None, 'Fault concern absent'
    assert 'seon-error-entry' in parser.block, 'No fault card observed'
    Path(sys.argv[2]).write_text(parser.block)
    print({'bytes': len(parser.block.encode()),
           'generic_printer_markers': parser.block.count('items, depth'),
           'cards': parser.block.count('seon-family-entry seon-error-entry')})
