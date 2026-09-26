import re,glob
pat=re.compile(r'(:seon\.error/(?:offending|data|expected)|::request|:seon\.db\.write\.attempt/transaction|:seon\.store/transaction)\s+(\(?[\w\-\.:/]+)')
world=re.compile(r'(request|projection|connection|conn\b|database|\bdb\b|report|ctx|context|environment|transaction|world|unit\b)')
tx=re.compile(r'\[:db\.fn/call\s+[#\'\w\-/\.@]+\s+([^\]\n]*)')
for f in sorted(glob.glob('src/**/*.clj*',recursive=True)+glob.glob('script/seon/**/*.clj',recursive=True)):
    for n,line in enumerate(open(f),1):
        for m in pat.finditer(line):
            if world.search(m.group(2)): print(f"{f}:{n}\tERR\t{m.group(1)} {m.group(2)}")
        for m in tx.finditer(line):
            if re.search(r'projection|connection|\bdb\b|database|ctx',m.group(1)): print(f"{f}:{n}\tTXFN\t{m.group(1).strip()}")
