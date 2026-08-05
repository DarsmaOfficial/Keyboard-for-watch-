#!/usr/bin/env python3
"""Convert Hunspell .dic files into the plain word lists WearKey ships.

Source: https://github.com/wooorm/dictionaries
  en — SCOWL, BSD-style   (Kevin Atkinson et al.)
  ru — BSD                (Alexander I. Lebedev)

Both licences are reproduced verbatim in THIRD_PARTY_LICENSES.md, and the same texts are
bundled into the APK so the in-app licences screen satisfies the BSD binary-redistribution
clause.

Why the output is committed rather than generated during the build: the project must build
fully offline from a clean machine (spec §3.1). Downloading dictionaries at build time would
break that. This script documents exactly how the committed files were produced, so anyone can
reproduce or refresh them.

Usage:
    python3 tools/build_dictionaries.py <en.dic> <ru.dic> <output-dir>
"""
import sys
import unicodedata
from pathlib import Path

# Keeping the list bounded matters more than completeness on a watch: SymSpell holds the
# dictionary in memory, and the whole app must stay under a 12 MB heap with two languages
# available (spec §3 item 4 / §4.2).
MAX_WORDS = 30000
MIN_LENGTH = 2
MAX_LENGTH = 18


def clean(dic_path: Path, alphabet_check) -> list[str]:
    words: list[str] = []
    seen: set[str] = set()

    with dic_path.open(encoding="utf-8", errors="ignore") as fh:
        lines = fh.read().splitlines()

    # The first line of a Hunspell .dic is the entry count, not a word.
    for raw in lines[1:]:
        line = raw.strip()
        if not line:
            continue
        # Strip the affix flags: "word/ABC" -> "word"
        word = line.split("/", 1)[0].strip()
        if not word:
            continue
        # Normalise so that visually identical forms collapse to one entry.
        word = unicodedata.normalize("NFC", word).lower()

        if not (MIN_LENGTH <= len(word) <= MAX_LENGTH):
            continue
        if not alphabet_check(word):
            continue
        if word in seen:
            continue

        seen.add(word)
        words.append(word)

    # Shorter words are overwhelmingly the more common ones, and without a permissively licensed
    # frequency corpus this is the honest proxy available (spec §4.3 rejects the CC-BY-SA
    # frequency lists outright). Ties keep alphabetical order so output is deterministic.
    words.sort(key=lambda w: (len(w), w))
    return words[:MAX_WORDS]


def is_latin(word: str) -> bool:
    return all("a" <= c <= "z" or c == "'" for c in word)


def is_cyrillic(word: str) -> bool:
    return all("а" <= c <= "я" or c == "ё" or c == "-" for c in word)


def main() -> int:
    if len(sys.argv) != 4:
        print(__doc__)
        return 2

    en_src, ru_src, out_dir = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
    out_dir.mkdir(parents=True, exist_ok=True)

    for name, src, check in (("en.txt", en_src, is_latin), ("ru.txt", ru_src, is_cyrillic)):
        words = clean(src, check)
        target = out_dir / name
        target.write_text("\n".join(words) + "\n", encoding="utf-8")
        size_kb = target.stat().st_size / 1024
        print(f"{name}: {len(words)} words, {size_kb:.0f} KB")

    return 0


if __name__ == "__main__":
    sys.exit(main())
