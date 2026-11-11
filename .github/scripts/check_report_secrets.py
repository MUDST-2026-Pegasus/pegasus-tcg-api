"""Block publication when text reports contain common credential patterns."""

import html
import os
from pathlib import Path
import re
import sys

PATTERNS = (
    re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    re.compile(r"\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b"),
    re.compile(r"(?i)\bbearer\s+[A-Za-z0-9._~+/=-]{24,}"),
)
SECRET_NAMES = ("DB_PASSWORD", "MINIO_ACCESS_KEY", "MINIO_SECRET_KEY", "JWT_SECRET")
SUFFIXES = {".xml", ".html", ".json", ".txt"}


def main() -> int:
    values = {name: os.environ[name] for name in SECRET_NAMES
              if len(os.environ.get(name, "")) >= 8}
    categories = {name: 0 for name in values}
    categories.update({"private_key": 0, "jwt": 0, "bearer": 0})
    matches = files = 0
    for argument in sys.argv[1:]:
        root = Path(argument)
        for path in root.rglob("*") if root.is_dir() else (root,):
            if path.is_symlink() or not path.is_file() or path.suffix.lower() not in SUFFIXES:
                continue
            try:
                content = path.read_text(encoding="utf-8")
            except (OSError, UnicodeError):
                print("Report safety check could not read a text report; publication blocked.", file=sys.stderr)
                return 1
            files += 1
            content = html.unescape(content)
            matched = False
            for name, value in values.items():
                if value in content:
                    categories[name] += 1
                    matched = True
            for name, pattern in zip(("private_key", "jwt", "bearer"), PATTERNS):
                if pattern.search(content):
                    categories[name] += 1
                    matched = True
            if matched:
                matches += 1
    print(f"Report safety: scanned {files} text files; {matches} files matched.")
    if matches:
        print("Match categories (file counts): " + ", ".join(f"{name}={count}" for name, count in categories.items() if count))
    if matches:
        print("Publication blocked; inspect reports privately without copying secrets into logs.", file=sys.stderr)
    return int(matches > 0)


if __name__ == "__main__":
    sys.exit(main())
