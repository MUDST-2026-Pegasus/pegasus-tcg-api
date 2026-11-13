#!/usr/bin/env python3
"""Lists every API call the web app makes, for the TCG-755 contract test.

Usage: python3 -I scripts/contract/extract_fe_endpoints.py ../pegasus-tcg-web \
         > src/test/resources/contract/fe-endpoints.tsv
       python3 -I scripts/contract/extract_fe_endpoints.py ../pegasus-tcg-web --error-codes \
         > src/test/resources/contract/fe-error-codes.txt

Reads src/**/*.ts(x) (tests excluded), finds api.get/post/put/patch/delete/download
calls, resolves `const NAME = "..."` prefixes in the same file and writes
METHOD<TAB>PATH<TAB>FILE, sorted and de-duplicated. Path parameters become {p}.
"""
import pathlib
import re
import sys

CALL = re.compile(r"\bapi\.(get|post|put|patch|delete|download)\s*(?:<[^()]*?>)?\s*\(\s*([`'\"])(.*?)\2", re.S)
CONST = re.compile(r"\bconst\s+([A-Za-z_]\w*)\s*=\s*([`'\"])([^`'\"]*)\2")
UPLOAD = re.compile(r"[`'\"](/uploads[^`'\"]*)[`'\"]")


def normalise(path: str, consts: dict) -> str | None:
    for _ in range(3):
        path = re.sub(r"\$\{([A-Za-z_]\w*)\}", lambda m: consts.get(m.group(1), m.group(0)), path)
    path = re.sub(r"\$\{[^}]*\}", "{p}", path).split("?", 1)[0]
    if not path.startswith("/"):
        return None
    return re.sub(r"/{2,}", "/", path).rstrip("/") or "/"


def error_codes(root: str) -> None:
    """The server codes the web app knows how to translate (API_ERROR_CODES in lib/api/types.ts)."""
    text = (pathlib.Path(root) / "src/lib/api/types.ts").read_text(encoding="utf-8")
    block = re.search(r"API_ERROR_CODES\s*=\s*\[(.*?)\]", text, re.S).group(1)
    print("# ErrorCode values the web app maps (src/lib/api/types.ts API_ERROR_CODES)")
    for code in re.findall(r"[\"']([A-Z0-9_]+)[\"']", block):
        print(code)


def main(root: str) -> None:
    src = pathlib.Path(root) / "src"
    rows = set()
    for file in sorted(src.rglob("*.ts*")):
        rel = file.relative_to(root).as_posix()
        if "/test/" in rel or ".test." in rel or rel.endswith(".d.ts"):
            continue
        text = file.read_text(encoding="utf-8")
        consts = {m.group(1): m.group(3) for m in CONST.finditer(text) if m.group(3).startswith("/")}
        for m in CALL.finditer(text):
            path = normalise(m.group(3), consts)
            if path:
                method = "GET" if m.group(1) == "download" else m.group(1).upper()
                rows.add((method, path, rel))
    print("# method\tpath (under /api/v1)\tconsumer file")
    for method, path, rel in sorted(rows, key=lambda r: (r[1], r[0], r[2])):
        print(f"{method}\t{path}\t{rel}")


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    root = args[0] if args else "../pegasus-tcg-web"
    (error_codes if "--error-codes" in sys.argv else main)(root)
