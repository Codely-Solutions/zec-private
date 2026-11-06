"""Tiny guard regressions; fixtures live only in registered guard output."""
import json
import os
import tempfile
from pathlib import Path
from branding_guard import check_branding


def main(output):
    base = Path(__file__).resolve().parents[1]
    output = Path(output).resolve()
    fixture = output / "branding-fixtures"
    fixture.mkdir()
    try:
        policy = json.loads((base / ".private/branding-policy.json").read_text(encoding="utf-8"))
        assert not check_branding(base, policy), "Current source rejected"
        resources = fixture / "module/src/main/res/values"
        resources.mkdir(parents=True)
        xml = resources / "strings.xml"
        empty = {"version": 1, "exceptions": []}
        xml.write_text('<resources><string name="new">Choose Zodl today</string></resources>', encoding="utf-8")
        assert check_branding(fixture, empty), "New product branding accepted"
        xml.write_text('<resources><string name="Zodl" type="Zashi">Neutral text</string></resources>', encoding="utf-8")
        assert not check_branding(fixture, empty), "Resource identity treated as display text"
        entry = next(e for e in policy["exceptions"] if e["name"] == "about_description")
        allowed = fixture / entry["path"]
        allowed.parent.mkdir(parents=True, exist_ok=True)
        allowed.write_text('<resources><string name="about_description">Changed Zodl description</string></resources>', encoding="utf-8")
        assert any("changed branding exception" in e for e in check_branding(fixture, {"version": 1, "exceptions": [entry]})), "Changed exception accepted"
        allowed.write_text('<resources><string name="bad">', encoding="utf-8")
        assert any("parse/read failure" in e for e in check_branding(fixture, empty)), "Malformed XML accepted"
        allowed.write_text('<resources><plurals name="items"><item quantity="one">Zodl item</item></plurals><string-array name="options"><item>Zashi option</item></string-array></resources>', encoding="utf-8")
        assert len(check_branding(fixture, empty)) == 2, "Plural or array branding missed"
        print("PASS: current source, new branding, changed exception, identity-only, malformed XML, plurals and arrays")
    finally:
        pass  # The enclosing guard removes the fixture after child exit.


if __name__ == "__main__":
    if os.environ.get("CODEX_GUARD_OUTPUT"):
        main(os.environ["CODEX_GUARD_OUTPUT"])
    else:
        with tempfile.TemporaryDirectory(prefix="branding-policy-") as output:
            main(output)
