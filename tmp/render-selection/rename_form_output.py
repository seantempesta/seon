#!/usr/bin/env python3
"""Sweep: a render function's form OUTPUT is :seon.render/read-form; the face key
:seon.render/form names the schema's form renderer (a ref). Rewrites the key only
on `:malli/schema` lines, where it can only be a contract's schema reference.
Run from the repository root; prints each file changed."""
import re, pathlib
pat = re.compile(r':seon\.render/form(?=[\]\s)])')
for root in ['src', 'test', 'script']:
    for p in pathlib.Path(root).rglob('*.clj*'):
        lines = p.read_text().split('\n')
        changed = False
        for i, line in enumerate(lines):
            if ':malli/schema' in line and ':seon.render/form' in line:
                new = pat.sub(':seon.render/read-form', line)
                if new != line:
                    lines[i] = new; changed = True
        if changed:
            p.write_text('\n'.join(lines)); print(p)
