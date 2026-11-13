"""Check that this run produced executed JUnit and Cucumber scenarios."""

import glob
import json
import sys
import xml.etree.ElementTree as ET


def main() -> int:
    paths = glob.glob("build/test-results/test/*.xml")
    if not paths:
        print("FAIL: no JUnit reports; test suite did not complete.", file=sys.stderr)
        return 1
    passed = failed = skipped = 0
    try:
        for path in paths:
            for case in ET.parse(path).getroot().iter("testcase"):
                if case.find("failure") is not None or case.find("error") is not None:
                    failed += 1
                elif case.find("skipped") is not None:
                    skipped += 1
                else:
                    passed += 1
        with open("build/reports/cucumber/cucumber.json", encoding="utf-8") as report:
            features = json.load(report)
        scenarios = [scenario for feature in features for scenario in feature.get("elements", [])
                     if scenario.get("type") == "scenario"]
        executed = [scenario for scenario in scenarios
                    if any(step.get("result", {}).get("status") not in (None, "skipped")
                           for step in scenario.get("steps", []))]
    except (OSError, ET.ParseError, ValueError, TypeError, AttributeError) as error:
        print(f"FAIL: missing or invalid test report: {error}", file=sys.stderr)
        return 1
    print(f"JUnit: {passed} passed, {failed} failed, {skipped} skipped; "
          f"Cucumber: {len(executed)} executed scenarios")
    if failed or passed + failed == 0 or not executed:
        print("FAIL: tests failed or a required suite did not execute.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
