#!/usr/bin/env python3
"""重算 i18n 硬编码中文基线，不需要 Gradle。

HardcodedTextRatchetTest 的 HanLiteralScanner 的等价移植：同样的状态机、
同样的统计口径（跳过注释与字符字面量，模板表达式内的嵌套字面量单独计数）。
在本机拿不到 JDK/SDK 的环境里改完界面代码，用它更新

    app/src/test/resources/i18n/hardcoded-han-baseline.txt

用法：
    python3 tools/i18n-baseline-scan.py           # 只对比，打印差异
    python3 tools/i18n-baseline-scan.py --write   # 按当前源码重写基线

与 Kotlin 实现的一致性用旧基线验证过：214 个条目、文件集合零差异，
6 处计数差异与 CI 报出的完全一致。
"""

import os, sys

class HanLiteralScanner:
    def __init__(self, src):
        self.s = src
        self.i = 0
        self.count = 0

    def run(self):
        self.i = 0
        self.count = 0
        self.scan_code(False)
        return self.count

    def scan_code(self, in_template):
        s = self.s
        depth = 0
        n = len(s)
        while self.i < n:
            c = s[self.i]
            if s.startswith('//', self.i):
                while self.i < n and s[self.i] != '\n':
                    self.i += 1
            elif s.startswith('/*', self.i):
                end = s.find('*/', self.i + 2)
                self.i = n if end < 0 else end + 2
            elif s.startswith('"""', self.i):
                self.i += 3
                self.read_string(True)
            elif c == '"':
                self.i += 1
                self.read_string(False)
            elif c == "'":
                nxt = s[self.i+1] if self.i+1 < n else ''
                start = self.i + 3 if nxt == '\\' else self.i + 2
                end = s.find("'", start)
                self.i = self.i + 1 if end < 0 else end + 1
            elif c == '{':
                depth += 1
                self.i += 1
            elif c == '}':
                self.i += 1
                if in_template and depth == 0:
                    return
                depth -= 1
            else:
                self.i += 1

    def read_string(self, raw):
        s = self.s
        n = len(s)
        has_han = False
        while self.i < n:
            c = s[self.i]
            if not raw and c == '\\':
                self.i += 2
            elif raw and s.startswith('"""', self.i):
                self.i += 3
                while self.i < n and s[self.i] == '"':
                    self.i += 1
                break
            elif not raw and (c == '"' or c == '\n'):
                if c == '"':
                    self.i += 1
                break
            elif c == '$' and self.i + 1 < n and s[self.i+1] == '{':
                if has_han:
                    self.count += 1
                has_han = False
                self.i += 2
                self.scan_code(True)
            else:
                o = ord(c)
                if (0x4E00 <= o <= 0x9FFF) or (0x3400 <= o <= 0x4DBF):
                    has_han = True
                self.i += 1
        if has_han:
            self.count += 1


def scan_tree(abs_dir, rel_root):
    out = {}
    for dirpath, _, files in os.walk(abs_dir):
        for fn in files:
            if not fn.endswith('.kt'):
                continue
            p = os.path.join(dirpath, fn)
            rel = os.path.relpath(p, rel_root).replace(os.sep, '/')
            cnt = HanLiteralScanner(open(p, encoding='utf-8', errors='ignore').read()).run()
            if cnt > 0:
                out[rel] = cnt
    return out


base = 'app/src/main/java/com/mozhi/reader'
cur = {}
for scope in ('feature', 'ui'):
    cur.update(scan_tree(os.path.join(base, scope), base))
main_act = os.path.join(base, 'MainActivity.kt')
if os.path.isfile(main_act):
    c = HanLiteralScanner(open(main_act, encoding='utf-8', errors='ignore').read()).run()
    if c > 0:
        cur['MainActivity.kt'] = c

old = {}
for line in open('app/src/test/resources/i18n/hardcoded-han-baseline.txt', encoding='utf-8'):
    line = line.rstrip('\n')
    if not line or line.startswith('#'):
        continue
    path, cnt = line.split('\t')
    old[path.strip()] = int(cnt.strip())

missing = [k for k in old if k not in cur]
extra = [k for k in cur if k not in old]
diff = [(k, old[k], cur[k]) for k in old if k in cur and old[k] != cur[k]]

print('baseline entries:', len(old), '| computed:', len(cur))
print('only-in-baseline:', len(missing), missing[:8])
print('only-in-computed:', len(extra), extra[:8])
print('count differs:', len(diff))
for k, a, b in sorted(diff)[:30]:
    print('  ', k, a, '->', b)

if '--write' in sys.argv:
    hdr = u'# path<TAB>\u542b\u6c49\u5b57\u7684\u5b57\u7b26\u4e32\u5b57\u9762\u91cf\u6570\uff1b\u7531 HardcodedTextRatchetTest \u751f\u6210\uff0c\u52ff\u624b\u6539\n'
    body = ''.join('%s\t%d\n' % (k, cur[k]) for k in sorted(cur))
    open('app/src/test/resources/i18n/hardcoded-han-baseline.txt', 'w', encoding='utf-8').write(hdr + body)
    print('WRITTEN', len(cur), 'entries')
