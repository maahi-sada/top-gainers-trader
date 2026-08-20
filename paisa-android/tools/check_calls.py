"""Static sanity check for the Android module, which has no SDK here to compile against.

Checks that every call to a function declared in this project uses only
parameter names that exist, and supplies every parameter without a default
(counting positional arguments and a trailing lambda).
"""
import re, glob, sys, os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

APP = os.path.join(ROOT, 'app/src/main/kotlin/**/*.kt')
CORE = os.path.join(ROOT, 'core/src/main/kotlin/**/*.kt')

def strip_comments(text):
    text = re.sub(r'/\*.*?\*/', ' ', text, flags=re.S)
    return re.sub(r'//[^\n]*', ' ', text)

def split_top_level(text):
    """Split on commas that are not inside brackets. '->' must not count as a bracket."""
    out, depth, cur = [], 0, ''
    for ch in text:
        if ch in '([{': depth += 1
        elif ch in ')]}': depth -= 1
        if ch == ',' and depth == 0:
            out.append(cur); cur = ''
        else:
            cur += ch
    if cur.strip(): out.append(cur)
    return [p.strip() for p in out if p.strip()]

def matching_paren(src, open_index):
    depth, j = 0, open_index
    while j < len(src):
        if src[j] == '(': depth += 1
        elif src[j] == ')':
            depth -= 1
            if depth == 0: return j
        j += 1
    return -1

def find_decls(pattern):
    decls = {}
    for path in glob.glob(pattern, recursive=True):
        src = open(path).read()
        for m in re.finditer(r'\bfun\s+(?:<[^>]+>\s*)?(?:\w+\.)?(\w+)\s*\(', src):
            close = matching_paren(src, m.end() - 1)
            if close < 0: continue
            params = []
            for p in split_top_level(strip_comments(src[m.end():close])):
                pm = re.match(r'(?:@\w+\s+)*(?:val\s+|var\s+)?(\w+)\s*:', p)
                if pm: params.append((pm.group(1), '=' in p))
            decls.setdefault(m.group(1), []).append((params, path.split('/')[-1]))
    return decls

decls = find_decls(APP)
problems = []

for path in glob.glob(APP, recursive=True):
    src = open(path).read()
    name_of_file = path.split('/')[-1]
    for m in re.finditer(r'(?<![\w.])([A-Z]\w+)\s*\(', src):
        name = m.group(1)
        if name not in decls: continue
        close = matching_paren(src, m.end() - 1)
        if close < 0: continue

        args = split_top_level(src[m.end():close])
        named = {am.group(1) for a in args if (am := re.match(r'^(\w+)\s*=(?!=)', a))}
        if not named: continue
        positional = len(args) - len(named)
        trailing_lambda = src[close + 1:close + 40].lstrip().startswith('{')

        fits, report = False, None
        for params, decl_file in decls[name]:
            pnames = {p for p, _ in params}
            missing = {p for p, has_default in params if not has_default and p not in named}
            if trailing_lambda and params: missing.discard(params[-1][0])
            unknown = named - pnames
            if not unknown and len(missing) <= positional:
                fits = True
                break
            report = (unknown, missing, positional, decl_file)

        if not fits and report:
            unknown, missing, positional, decl_file = report
            detail = []
            if unknown: detail.append(f"unknown parameter(s) {sorted(unknown)}")
            if len(missing) > positional: detail.append(f"missing required {sorted(missing)}")
            if detail:
                line = src[:m.start()].count('\n') + 1
                problems.append(f"{name_of_file}:{line}  {name}(...)  -> {'; '.join(detail)}  [declared in {decl_file}]")

core_symbols = set()
for path in glob.glob(CORE, recursive=True):
    src = open(path).read()
    for m in re.finditer(r'\b(?:class|object|interface|typealias|fun|val)\s+(?:\w+\.)?(\w+)', src):
        core_symbols.add(m.group(1))
for path in glob.glob(APP, recursive=True):
    for m in re.finditer(r'import app\.paisa\.core\.(\w+)', open(path).read()):
        if m.group(1) not in core_symbols:
            problems.append(f"{path.split('/')[-1]}  imports app.paisa.core.{m.group(1)} which does not exist")

print('\n'.join(problems) if problems else 'no call-signature or import problems found')
print(f"\nchecked {len(glob.glob(APP, recursive=True))} app files against {len(decls)} declarations")
sys.exit(1 if problems else 0)
