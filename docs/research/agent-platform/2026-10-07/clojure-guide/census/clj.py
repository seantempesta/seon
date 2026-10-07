"""Minimal Clojure reader for the census: builds a position-annotated tree.

Node: kind in list/vec/map/set/fn (collections: .kids), str, kw, sym, num, chr, rx, other.
Every node has .text (atoms), .l0/.l1 (1-based inclusive lines), .meta (list of meta nodes).
Whole-line comments are collected in Parsed.comment_lines; trailing comments in Parsed.inline_comment_lines.
"""
import os, re

CLOSERS = {'(': ')', '[': ']', '{': '}'}
DELIMS = set('()[]{}"; \t\r\n,')


class Node:
    __slots__ = ('kind', 'text', 'kids', 'l0', 'l1', 'meta', 'prefix')

    def __init__(self, kind, text, l0, l1, kids=None, prefix=''):
        self.kind = kind; self.text = text; self.l0 = l0; self.l1 = l1
        self.kids = kids if kids is not None else []
        self.meta = []; self.prefix = prefix

    def head(self):
        if self.kind == 'list' and self.kids and self.kids[0].kind == 'sym':
            return self.kids[0].text
        return None

    def walk(self):
        yield self
        for m in self.meta:
            yield from m.walk()
        for k in self.kids:
            yield from k.walk()


class Parsed:
    def __init__(self, path, text):
        self.path = path
        self.text = text
        self.lines = text.split('\n')
        self.forms = []
        self.comment_lines = set()        # whole-line ; comments
        self.inline_comment_lines = set()  # code + trailing ;
        self.discard_spans = []           # (l0,l1) of #_ forms
        self.line_has_code = set()
        self._parse()

    # ---- tokenizer/reader --------------------------------------------------
    def _parse(self):
        s = self.text
        self.s = s
        self.i = 0
        self.line = 1
        self.n = len(s)
        while True:
            self._skip_ws()
            if self.i >= self.n:
                break
            if s[self.i] in ')]}':
                self.i += 1  # stray closer
                continue
            node = self._read()
            if node is not None:
                self.forms.append(node)

    def _skip_ws(self):
        s = self.s
        while self.i < self.n:
            c = s[self.i]
            if c == '\n':
                self.line += 1; self.i += 1
            elif c in ' \t\r,':
                self.i += 1
            elif c == ';':
                j = s.find('\n', self.i)
                if j < 0: j = self.n
                # whole-line or trailing?
                ls = s.rfind('\n', 0, self.i) + 1
                if s[ls:self.i].strip() == '':
                    self.comment_lines.add(self.line)
                else:
                    self.inline_comment_lines.add(self.line)
                self.i = j
            else:
                break

    def _read(self):
        """Read one form at self.i (after whitespace). May return None for #_ discard."""
        s = self.s
        c = s[self.i]
        l0 = self.line
        if c in '([{':
            self.i += 1
            kids = []
            close = CLOSERS[c]
            while True:
                self._skip_ws()
                if self.i >= self.n:
                    break
                if s[self.i] == close:
                    self.i += 1; break
                if s[self.i] in ')]}':
                    self.i += 1; break
                k = self._read()
                if k is not None:
                    kids.append(k)
            kind = {'(': 'list', '[': 'vec', '{': 'map'}[c]
            for k in kids:
                self._mark(k)
            return Node(kind, '', l0, self.line, kids)
        if c == '"':
            j = self.i + 1
            while j < self.n and s[j] != '"':
                if s[j] == '\\': j += 1
                j += 1
            txt = s[self.i:j + 1]
            self.i = j + 1
            nl = txt.count('\n')
            self.line += nl
            return Node('str', txt, l0, self.line)
        if c == '\\':
            j = self.i + 1
            # char literal: \a \newline ሴ
            j += 1
            while j < self.n and s[j] not in DELIMS: j += 1
            txt = s[self.i:j]; self.i = j
            return Node('chr', txt, l0, l0)
        if c in "'`@":
            self.i += 1
            self._skip_ws()
            n = self._read()
            if n is not None: n.prefix = c + n.prefix
            return n
        if c == '~':
            self.i += 1
            p = '~'
            if self.i < self.n and s[self.i] == '@':
                self.i += 1; p = '~@'
            self._skip_ws()
            n = self._read()
            if n is not None: n.prefix = p + n.prefix
            return n
        if c == '^':
            self.i += 1
            self._skip_ws()
            m = self._read()
            self._skip_ws()
            n = self._read()
            if n is not None and m is not None:
                n.meta.append(m)
            return n
        if c == '#':
            nxt = s[self.i + 1] if self.i + 1 < self.n else ''
            if nxt == '_':
                self.i += 2
                self._skip_ws()
                a = self.line
                d = self._read()
                if d is not None:
                    self.discard_spans.append((d.l0, d.l1))
                return None
            if nxt == '"':
                self.i += 1
                n = self._read()
                n.kind = 'rx'; n.prefix = '#'
                return n
            if nxt in '({':
                self.i += 1
                n = self._read()
                if nxt == '(': n.kind = 'fn'
                else: n.kind = 'set'
                n.prefix = '#'; n.l0 = l0
                return n
            if nxt == "'":
                self.i += 2
                n = self._read()
                if n is not None: n.prefix = "#'" + n.prefix
                return n
            if nxt == '?':
                j = self.i + 2
                if j < self.n and s[j] == '@': j += 1
                self.i = j
                n = self._read()
                if n is not None: n.prefix = '#?' + n.prefix
                return n
            if nxt == ':':
                j = self.i + 1
                while j < self.n and s[j] not in DELIMS: j += 1
                p = s[self.i:j]; self.i = j
                self._skip_ws()
                n = self._read()
                if n is not None: n.prefix = p
                return n
            # tagged literal #inst "..." / #uuid / #foo
            j = self.i + 1
            while j < self.n and s[j] not in DELIMS: j += 1
            tag = s[self.i:j]; self.i = j
            self._skip_ws()
            n = self._read()
            if n is not None: n.prefix = tag + ' '
            return n
        # atom
        j = self.i
        while j < self.n and s[j] not in DELIMS: j += 1
        if j == self.i:
            self.i += 1
            return None
        txt = s[self.i:j]; self.i = j
        if txt[0] == ':': kind = 'kw'
        elif re.match(r'^[-+]?\d', txt): kind = 'num'
        else: kind = 'sym'
        return Node(kind, txt, l0, l0)

    def _mark(self, node):
        for l in range(node.l0, node.l1 + 1):
            self.line_has_code.add(l)


def load(path):
    with open(path, encoding='utf-8', errors='replace') as f:
        return Parsed(path, f.read())


def src_files(root, sub='src'):
    out = []
    for d, _, fs in os.walk(os.path.join(root, sub)):
        for f in fs:
            if f.endswith(('.clj', '.cljc', '.cljs')):
                out.append(os.path.join(d, f))
    return sorted(out)


DEF_HEADS = {'defn', 'defn-', 'def', 'defmacro', 'defmulti', 'defonce', 'deftest', 'defmethod',
             'defrecord', 'defprotocol', 'defschema', 'definline'}


def strip_prefix(h):
    return h.split('/')[-1] if h else h


def top_defs(parsed):
    """Yield (kind, name, node) for top-level defining forms (also inside top-level comment/do/when forms)."""
    def rec(node, depth=0):
        h = node.head()
        if h is None:
            return
        hb = strip_prefix(h)
        if hb in DEF_HEADS and len(node.kids) > 1 and node.kids[1].kind in ('sym',):
            yield (hb, node.kids[1].text, node)
        elif hb in ('do', 'when', 'when-not', 'let', 'if-not', 'cond->') and depth < 2:
            for k in node.kids[1:]:
                if k.kind == 'list':
                    yield from rec(k, depth + 1)
    for f in parsed.forms:
        if f.kind == 'list':
            yield from rec(f)


def ns_of(parsed):
    for f in parsed.forms:
        if f.head() == 'ns' and len(f.kids) > 1:
            return f.kids[1].text
    return None


def ns_aliases(parsed):
    """alias -> ns, and (ns, sym) refer pairs from the ns form."""
    aliases = {}; refers = {}
    for f in parsed.forms:
        if f.head() == 'ns':
            for clause in f.kids[2:]:
                if clause.kind == 'list' and clause.kids and clause.kids[0].text in (':require', ':require-macros', ':use'):
                    for spec in clause.kids[1:]:
                        if spec.kind == 'vec' and spec.kids and spec.kids[0].kind == 'sym':
                            nsn = spec.kids[0].text
                            ks = spec.kids[1:]
                            for idx, k in enumerate(ks):
                                if k.kind == 'kw' and k.text in (':as', ':as-alias') and idx + 1 < len(ks):
                                    aliases[ks[idx + 1].text] = nsn
                                if k.kind == 'kw' and k.text == ':refer' and idx + 1 < len(ks) and ks[idx + 1].kind == 'vec':
                                    for r in ks[idx + 1].kids:
                                        refers[r.text] = nsn
                            aliases.setdefault(nsn, nsn)
                        elif spec.kind == 'sym':
                            aliases.setdefault(spec.text, spec.text)
            break
    return aliases, refers
