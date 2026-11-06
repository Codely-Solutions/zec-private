"""Draft update-controller guard for retained Android resource text."""
import hashlib
import json
import re
import unicodedata
import xml.etree.ElementTree as ET
from pathlib import Path

BRAND = re.compile(r"\b(?:Zodl|Zashi)\b", re.IGNORECASE)


def normalized_text(element):
    return " ".join(unicodedata.normalize("NFC", "".join(element.itertext())).split())


def text_sha256(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def check_branding(root, policy):
    """Return errors; exceptions bind path, resource identity and normalized text."""
    root = Path(root)
    errors = []
    allowed = {}
    if policy.get("version") != 1:
        return ["Unsupported branding policy version"]
    for entry in policy.get("exceptions", []):
        try:
            key = (entry["path"], entry["type"], entry["name"], entry.get("item"))
            digest = entry["sha256"]
            if not entry["reason"].strip() or not re.fullmatch(r"[0-9a-f]{64}", digest):
                raise ValueError("Invalid exception reason or hash")
            if key in allowed:
                raise ValueError("Duplicate exception")
            allowed[key] = digest
        except (KeyError, TypeError, ValueError) as error:
            errors.append("Invalid branding exception: " + str(error))
    try:
        resource_roots = sorted(root.glob("*/src/main/res"))
        if not resource_roots:
            return errors
        for resources in resource_roots:
            for path in sorted(resources.rglob("*.xml")):
                relative = path.relative_to(root).as_posix()
                try:
                    document = ET.parse(path).getroot()
                except (ET.ParseError, OSError) as error:
                    errors.append(relative + ": XML parse/read failure: " + str(error))
                    continue
                if document.tag != "resources":
                    continue
                for element in document:
                    if element.tag == "string":
                        texts = [(None, element)]
                    elif element.tag in ("plurals", "string-array"):
                        texts = [(str(index), item) for index, item in enumerate(element) if item.tag == "item"]
                    else:
                        continue
                    for item, content in texts:
                        key = (relative, element.tag, element.get("name", ""), item)
                        text = normalized_text(content)
                        if key in allowed:
                            if text_sha256(text) != allowed[key]:
                                errors.append(relative + ": changed branding exception " + repr(key[1:]))
                        elif BRAND.search(text):
                            errors.append(relative + ": unapproved branding text " + repr(key[1:]))
    except OSError as error:
        errors.append("Production resource traversal failed: " + str(error))
    return errors


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    parser.add_argument("policy", type=Path)
    args = parser.parse_args()
    failures = check_branding(args.root, json.loads(args.policy.read_text(encoding="utf-8")))
    for failure in failures:
        print(failure)
    raise SystemExit(bool(failures))
