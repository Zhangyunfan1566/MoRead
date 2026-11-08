import os, re

ROOT = 'app/src/main/java'
# 抓出所有字符串字面量，然后看还原后是否含 {{ 或 }}
PAT = re.compile(r'("""(?:.*?)"""|"(?:[^"\\]|\\.)*")', re.S)

def unescape(s):
    if s.startswith('"""'):
        body = s[3:-3]
    else:
        body = s[1:-1]
    out = []
    i = 0
    while i < len(body):
        c = body[i]
        if c == '\\' and i + 1 < len(body):
            n = body[i+1]
            m = {'n': '\n', 't': '\t', 'r': '\r', '"': '"', "'": "'", '\\': '\\', '$': '$'}
            if n in m:
                out.append(m[n]); i += 2; continue
            out.append('\\'); out.append(n); i += 2; continue
        out.append(c); i += 1
    return ''.join(out)

rows = []
for dirpath, _, files in os.walk(ROOT):
    for fn in sorted(files):
        if not fn.endswith('.kt'):
            continue
        p = os.path.join(dirpath, fn)
        src = open(p, encoding='utf-8', errors='ignore').read()
        for m in PAT.finditer(src):
            text = unescape(m.group(1))
            if '{{' in text or '}}' in text:
                line = src[:m.start()].count(chr(10)) + 1
                rows.append((p.replace(ROOT + '/', ''), line, text))

print('含 {{ 或 }} 的字符串字面量:', len(rows))
for p, line, text in sorted(rows):
    print('%-64s :%-5d %s' % (p, line, text[:100].replace(chr(10), '\\n')))
