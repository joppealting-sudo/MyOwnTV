#!/usr/bin/env python3
"""Generate reviewed i18n-safe entries for Solcon implementation-only literals.

This is a narrow bootstrap helper: it only classifies *new* literals that are not already
covered by OwnTV's hardcoded baseline or safe manifest, and only inside the implementation
files listed below. Compose/screens are deliberately excluded so user-visible copy must remain
Android string resources. The one navigation metadata file listed here contains resource ids
plus a non-visible route key; it renders no text itself.
"""
from __future__ import annotations

import importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CHECKER_PATH = ROOT / "tools" / "i18n" / "check_hardcoded_strings.py"

spec = importlib.util.spec_from_file_location("owntv_i18n_checker", CHECKER_PATH)
if spec is None or spec.loader is None:
    raise SystemExit("Could not load OwnTV i18n checker")
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)

# Every file here is implementation/protocol metadata only. Do not add Compose/screens to this map.
APPROVED_TECHNICAL_FILES = {
    "app/src/main/java/tv/own/owntv/features/settings/SolconSettingsRoute.kt": "technical",
    "app/src/main/java/tv/own/owntv/provider/solcon/SolconStreamPolicy.kt": "protocol",
    "app/src/main/java/tv/own/owntv/provider/solcon/tvplus/SolconDiagnostics.kt": "technical",
    "app/src/main/java/tv/own/owntv/provider/solcon/tvplus/SolconTvPlusClient.kt": "protocol",
    "app/src/main/java/tv/own/owntv/provider/solcon/tvplus/SolconTvPlusDiscovery.kt": "protocol",
    "app/src/main/java/tv/own/owntv/provider/solcon/tvplus/SolconTvPlusProtocol.kt": "protocol",
    "app/src/main/java/tv/own/owntv/provider/solcon/tvplus/SolconTvPlusRepository.kt": "technical",
    "app/src/main/java/tv/own/owntv/provider/solcon/tvplus/SolconTvPlusSessionStore.kt": "technical",
}

current = checker._inventory()
safe_counts, safe_categories, safe_errors = checker._safe_entries()
if safe_errors:
    raise SystemExit("safe_literals.txt is invalid: " + "; ".join(safe_errors))

baseline = checker._parse(checker.BASELINE.read_text(encoding="utf-8"))
allowed = checker._add_counts(baseline, safe_counts)
excess = checker._subtract_counts(current, allowed)

entries = {
    key: (count, safe_categories[key])
    for key, count in safe_counts.items()
}
classified = 0
for key, count in sorted(excess.items()):
    relative, _text = key
    category = APPROVED_TECHNICAL_FILES.get(relative)
    if category is None:
        continue
    old_count, old_category = entries.get(key, (0, category))
    if old_category != category:
        raise SystemExit(f"Conflicting category for {relative}: {old_category} vs {category}")
    entries[key] = (old_count + count, category)
    classified += count

checker.SAFE_MANIFEST.write_text(checker._serialize_safe(entries), encoding="utf-8")
print(f"Classified {classified} new Solcon technical literal occurrence(s).")

# Prove that this helper did not accidentally hide literals outside the approved implementation files.
new_safe_counts, _, errors = checker._safe_entries()
if errors:
    raise SystemExit("generated safe manifest is invalid: " + "; ".join(errors))
remaining = checker._subtract_counts(current, checker._add_counts(baseline, new_safe_counts))
solcon_remaining = {
    key: count for key, count in remaining.items()
    if key[0].startswith("app/src/main/java/tv/own/owntv/provider/solcon/")
}
if solcon_remaining:
    print("Unclassified Solcon literals remain (expected only if they are outside the approved technical files):")
    for (relative, text), count in sorted(solcon_remaining.items()):
        print(f"  {count}x {relative}: {text!r}")
