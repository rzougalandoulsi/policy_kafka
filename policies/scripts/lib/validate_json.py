#!/usr/bin/env python3
"""validate_json.py <schema.json> <document.json>...

Validates every document against the JSON Schema (draft 2020-12, formats checked) and prints each error as
"<file>: <json pointer>: <message>". Exit status 1 when any document is invalid.
"""
import json
import sys

from jsonschema import Draft202012Validator, FormatChecker


def main() -> int:
    if len(sys.argv) < 3:
        print(__doc__, file=sys.stderr)
        return 2
    with open(sys.argv[1], encoding="utf-8") as fh:
        schema = json.load(fh)
    Draft202012Validator.check_schema(schema)
    validator = Draft202012Validator(schema, format_checker=FormatChecker())
    status = 0
    for document in sys.argv[2:]:
        with open(document, encoding="utf-8") as fh:
            instance = json.load(fh)
        errors = sorted(validator.iter_errors(instance), key=lambda e: list(e.absolute_path))
        if errors:
            status = 1
            for error in errors:
                pointer = "/" + "/".join(str(p) for p in error.absolute_path)
                print(f"{document}: {pointer}: {error.message}")
        else:
            print(f"{document}: valid ({schema.get('title', sys.argv[1])})")
    return status


if __name__ == "__main__":
    sys.exit(main())
