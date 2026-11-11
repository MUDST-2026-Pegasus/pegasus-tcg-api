"""Remove selected CI credential values from generated text reports."""

import html
import os
from pathlib import Path
import sys

SECRET_NAMES = ("DB_PASSWORD", "MINIO_ACCESS_KEY", "MINIO_SECRET_KEY", "JWT_SECRET")
SUFFIXES = {".xml", ".html", ".json", ".txt"}


def main() -> int:
    variants = set()
    for name in SECRET_NAMES:
        value = os.environ.get(name, "")
        if len(value) >= 8:
            variants.update((value, html.escape(value), html.escape(value, quote=False)))
    ordered = sorted(variants, key=len, reverse=True)
    changed = 0
    for argument in sys.argv[1:]:
        root = Path(argument)
        for path in root.rglob("*") if root.is_dir() else (root,):
            if path.is_symlink() or not path.is_file() or path.suffix.lower() not in SUFFIXES:
                continue
            try:
                original = path.read_text(encoding="utf-8")
                redacted = original
                for value in ordered:
                    redacted = redacted.replace(value, "[REDACTED]")
                if redacted != original:
                    path.write_text(redacted, encoding="utf-8")
                    changed += 1
            except (OSError, UnicodeError):
                print("Report redaction failed; publication blocked.", file=sys.stderr)
                return 1
    print(f"Report redaction: updated {changed} text files.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
