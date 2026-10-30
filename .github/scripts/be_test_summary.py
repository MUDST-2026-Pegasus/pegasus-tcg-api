"""Render Gradle JUnit XML as a compact GitHub Markdown report."""

from __future__ import annotations

import glob
import html
import sys
import xml.etree.ElementTree as ET


def cell(value: str) -> str:
    return html.escape(value, quote=False).replace("|", "\\|").replace("\n", " ")


def main() -> int:
    files = sorted(glob.glob("build/test-results/test/*.xml"))
    print("<!-- pegasus-be-tests -->")
    print("## BE Tests\n")
    if not files:
        print("No JUnit XML reports were produced. Check the build logs for an earlier failure.")
        return 0

    suites = []
    failures = []
    totals = dict(passed=0, failed=0, skipped=0)
    for path in files:
        root = ET.parse(path).getroot()
        cases = root.findall(".//testcase")
        counts = dict(passed=0, failed=0, skipped=0)
        for case in cases:
            problem = case.find("failure")
            if problem is None:
                problem = case.find("error")
            if problem is not None:
                counts["failed"] += 1
                failures.append((root.get("name", path), case.get("name", "unnamed"), problem.get("message", "")))
            elif case.find("skipped") is not None:
                counts["skipped"] += 1
            else:
                counts["passed"] += 1
        for key in totals:
            totals[key] += counts[key]
        suites.append((root.get("name", path), counts))

    print(
        f"**{totals['passed']:,} passed** · **{totals['failed']:,} failed** · "
        f"**{totals['skipped']:,} skipped** JUnit test cases across **{len(suites):,} classes/features**.\n"
    )
    coverage_xml = "build/reports/jacoco/test/jacocoTestReport.xml"
    if glob.glob(coverage_xml):
        report = ET.parse(coverage_xml).getroot()
        line = next((counter for counter in report.findall("counter") if counter.get("type") == "LINE"), None)
        if line is not None:
            covered = int(line.get("covered", "0"))
            missed = int(line.get("missed", "0"))
            if covered + missed:
                print(f"**Overall line coverage:** {covered / (covered + missed):.1%}.\n")
    if failures:
        print("### Failed tests\n")
        for suite, name, message in failures[:20]:
            detail = f" — {cell(message[:180])}" if message else ""
            print(f"- **{cell(suite)}**: {cell(name)}{detail}")
        if len(failures) > 20:
            print(f"- …and {len(failures) - 20} more. Open the BE Tests check for all failures.")
        print()

    print(f"<details><summary>Results by class / feature ({len(suites):,})</summary>\n")
    print("| Class / feature | Passed | Failed | Skipped |")
    print("| --- | ---: | ---: | ---: |")
    for name, counts in sorted(suites, key=lambda item: (-item[1]["failed"], item[0].lower())):
        print(f"| {cell(name)} | {counts['passed']} | {counts['failed']} | {counts['skipped']} |")
    print("\n</details>\n")
    print("Coverage is shown in the **BE Coverage** report. When a PR changes no Java source files, changed-file coverage is unavailable.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
