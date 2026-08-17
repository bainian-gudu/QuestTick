#!/usr/bin/env python3
from __future__ import annotations

import argparse
import os
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

# 只对已建立单测的核心业务逻辑做门禁，避免 UI / Hilt 生成代码 / Android 壳层
# 把整仓覆盖率拉低后导致 CI 误判。阈值与工作流步骤名保持一致：>= 70%。
CORE_CLASS_PREFIXES = (
    "com/questtick/config/DsConfig",
    "com/questtick/core/security/CertificateConfig",
    "com/questtick/log/LogExporter",
    "com/questtick/mail/Mailer",
    "com/questtick/net/Http",
    "com/questtick/repository/auth/AuthRepository",
    "com/questtick/sign/ActId",
    "com/questtick/sign/CookieParser",
    "com/questtick/sign/Ds",
    "com/questtick/sign/Mask",
    "com/questtick/sign/RiskState",
)

# 排除 Hilt/Factory 等生成类，以及协程状态机这类非业务源码。
EXCLUDED_NAME_PARTS = (
    "dagger/hilt",
    "hilt_aggregated_deps",
    "_Factory",
    "$InstanceHolder",
    "$send$",
)

DEFAULT_THRESHOLD = float(os.getenv("KOVER_CORE_LINE_THRESHOLD", "70"))


@dataclass
class ClassCoverage:
    name: str
    covered: int
    total: int

    @property
    def percent(self) -> float:
        return 100.0 * self.covered / self.total if self.total else 0.0


def should_include(class_name: str) -> bool:
    return (
        any(class_name.startswith(prefix) for prefix in CORE_CLASS_PREFIXES)
        and not any(part in class_name for part in EXCLUDED_NAME_PARTS)
    )


def find_report(explicit_report: str | None) -> Path:
    if explicit_report:
        return Path(explicit_report)

    report_root = Path("app/build/reports/kover")
    preferred = [
        report_root / "reportDebug.xml",
        report_root / "report.xml",
        report_root / "xmlDebug" / "report.xml",
    ]
    for path in preferred:
        if path.exists():
            return path

    xml_candidates = sorted(report_root.rglob("*.xml"), key=lambda p: (p.name != "reportDebug.xml", str(p)))
    if xml_candidates:
        return xml_candidates[0]

    raise FileNotFoundError(f"No Kover XML report found under: {report_root}")


def read_core_coverage(report_path: Path) -> list[ClassCoverage]:
    root = ET.parse(report_path).getroot()
    rows: list[ClassCoverage] = []

    for package in root.findall("package"):
        for klass in package.findall("class"):
            class_name = klass.attrib.get("name", "")
            if not should_include(class_name):
                continue

            line_counter = klass.find("counter[@type='LINE']")
            if line_counter is None:
                continue

            missed = int(line_counter.attrib.get("missed", "0"))
            covered = int(line_counter.attrib.get("covered", "0"))
            total = missed + covered
            if total == 0:
                continue

            rows.append(ClassCoverage(name=class_name, covered=covered, total=total))

    return sorted(rows, key=lambda row: row.name)


def main() -> int:
    parser = argparse.ArgumentParser(description="Check Kover line coverage for core business classes.")
    parser.add_argument("--report", help="Path to the Kover XML report. Defaults to app/build/reports/kover/...")
    parser.add_argument(
        "--threshold",
        type=float,
        default=DEFAULT_THRESHOLD,
        help=f"Minimum required line coverage percentage. Default: {DEFAULT_THRESHOLD}",
    )
    args = parser.parse_args()

    try:
        report_path = find_report(args.report)
    except FileNotFoundError as exc:
        print(f"❌ {exc}", file=sys.stderr)
        return 2

    rows = read_core_coverage(report_path)
    if not rows:
        print(f"❌ No core business classes matched in report: {report_path}", file=sys.stderr)
        return 2

    covered = sum(row.covered for row in rows)
    total = sum(row.total for row in rows)
    percent = 100.0 * covered / total if total else 0.0

    print(f"📄 Kover report: {report_path}")
    print(f"🎯 Core business line coverage: {percent:.2f}% ({covered}/{total})")
    print(f"📏 Required threshold: {args.threshold:.2f}%")
    print("\nIncluded classes:")
    for row in rows:
        print(f"  - {row.name}: {row.percent:.2f}% ({row.covered}/{row.total})")

    if percent + 1e-9 < args.threshold:
        print("\n❌ Coverage gate failed.", file=sys.stderr)
        return 1

    print("\n✅ Coverage gate passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
