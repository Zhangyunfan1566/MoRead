# -*- coding: utf-8 -*-
import os, re, sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else 'app/src/main/java/com/mozhi/reader'

TRIPLE = '"' * 3

def skip_template(src, brace_at):
    i = brace_at
    depth = 0
    while i < len(src):
        c = src[i]
        if c == '"':
            i += 1
            while i < len(src) and src[i] != '"':
                if src[i] == '\\':
                    i += 1
                i += 1
            i += 1
        elif c == '{':
            depth += 1; i += 1
        elif c == '}':
            depth -= 1; i += 1
            if depth == 0:
                return i
        else:
            i += 1
    return len(src)

ESCAPES = {'n': '\n', 't': '\t', 'r': '\r', '"': '"', "'": "'", '\\': '\\', '$': '$'}

def read_literal(src, start):
    if src.startswith(TRIPLE, start):
        end = src.find(TRIPLE, start + 3)
        if end < 0:
            return None
        return src[start + 3:end], end + 3
    if start >= len(src) or src[start] != '"':
        return None
    out = []
    i = start + 1
    while i < len(src):
        c = src[i]
        if c == '"':
            return ''.join(out), i + 1
        if c == '\n':
            return None
        if c == '\\' and i + 1 < len(src):
            n = src[i + 1]
            if n in ESCAPES:
                out.append(ESCAPES[n]); i += 2; continue
            if n == 'u':
                hexs = src[i + 2:i + 6]
                if len(hexs) == 4:
                    try:
                        out.append(chr(int(hexs, 16))); i += 6; continue
                    except ValueError:
                        pass
            out.append('\\'); out.append(n); i += 2; continue
        if c == '$' and i + 1 < len(src) and src[i + 1] == '{':
            i = skip_template(src, i + 1)
            out.append('X')
            continue
        out.append(c); i += 1
    return None

QUANT = re.compile(r'\d+(,\d*)?')

def bare_braces(rx):
    bad = []
    i = 0
    in_class = False
    while i < len(rx):
        c = rx[i]
        if c == '\\':
            i += 2; continue
        if in_class:
            if c == ']':
                in_class = False
            i += 1; continue
        if c == '[':
            in_class = True; i += 1; continue
        if c == '{':
            close = rx.find('}', i + 1)
            inner = rx[i + 1:close] if close > 0 else None
            if inner is not None and QUANT.fullmatch(inner):
                i = close + 1
            else:
                bad.append(i); i += 1
            continue
        if c == '}':
            bad.append(i); i += 1; continue
        i += 1
    return bad

total = 0
offenders = []
for dirpath, _, files in os.walk(ROOT):
    for fn in sorted(files):
        if not fn.endswith('.kt'):
            continue
        p = os.path.join(dirpath, fn)
        src = open(p, encoding='utf-8', errors='ignore').read()
        i = 0
        while True:
            at = src.find('Regex(', i)
            if at < 0:
                break
            j = at + len('Regex(')
            while j < len(src) and src[j].isspace():
                j += 1
            parsed = read_literal(src, j)
            if parsed is None:
                i = at + 1
                continue
            regex, end = parsed
            total += 1
            bad = bare_braces(regex)
            if bad:
                line = src[:at].count('\n') + 1
                offenders.append((p.replace(ROOT + '/', ''), line, regex, bad))
            i = end

print('扫描正则字面量:', total)
print('含裸花括号:', len(offenders))
for p, line, rx, bad in offenders:
    print('  %-58s :%-5d idx=%s\n        %s' % (p, line, bad[:4], rx[:100]))
