#!/usr/bin/env python3
"""Build the frequency-annotated word lists WearKey ships.

Sources
-------
Vocabulary: https://github.com/wooorm/dictionaries (Hunspell .dic files)
  en — SCOWL, BSD-style   (Kevin Atkinson et al.)
  ru — BSD                (Alexander I. Lebedev)

Frequencies: Wortschatz Leipzig news corpora, CC BY — see build_frequencies.py, which this
script calls. All three licences are permissive (no share-alike, no fee, no registration, no
service tier) and their notices are reproduced in THIRD_PARTY_LICENSES.md and bundled into the
APK. The Hunspell *engine* is never shipped — only the data.

Size: 10 000 words per language
------------------------------
Spec §4.2 budgets `maxEditDistance = 1` with **10k words per language**, estimated at ~3.7 MB of
heap. An earlier revision of this script shipped 30k, which was never reconciled with that
budget, and it was measured on the watch as **39.8 MB of Dalvik heap against the spec's 8 MB
gate** (§14). SymSpell's delete-variant table grows worse than linearly, so tripling the word
count cost roughly ten times the heap. 10k is therefore not a compromise — it is the number the
project was designed around.

Which 10 000 words
------------------
Frequency-ranked, not length-ranked. The earlier revision had no frequency data available and so
kept the *shortest* words as an honest proxy for the commonest ones. Now that permissively
licensed counts exist, the list keeps the genuinely **most common** words, which makes a 10k list
strictly better at suggesting than the old length-sorted 30k one: it drops obscure short words
like "aa"/"ab" that could never be useful suggestions, and keeps common longer words like
"because" and "something" that the length cut-off was pushing out.

Words the corpus never attests are ranked behind every attested word, using the
shorter-is-commoner proxy among themselves, so the list degrades gracefully rather than
truncating arbitrarily.

Why the output is committed rather than generated during the build: the project must build fully
offline from a clean machine (spec §3.1). This script documents exactly how the committed files
were produced so anyone can reproduce or refresh them.

Usage:
    python3 tools/build_dictionaries.py <en.dic> <ru.dic> <output-dir> [--cache DIR]
"""
import argparse
import unicodedata
from pathlib import Path

import build_frequencies

# Spec §4.2: 10k words per language at maxEditDistance=1. Do not raise this without re-measuring
# `dumpsys meminfo` on the device — see the module docstring for what happened last time.
MAX_WORDS = 10000
MIN_LENGTH = 2
MAX_LENGTH = 18


def extract_vocabulary(dic_path: Path, alphabet_check) -> list[str]:
    """Every acceptable word in the .dic, before frequency ranking is applied."""
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

    return words


def is_latin(word: str) -> bool:
    return all("a" <= c <= "z" or c == "'" for c in word)


def is_cyrillic(word: str) -> bool:
    return all("а" <= c <= "я" or c == "ё" or c == "-" for c in word)


def build(lang: str, dic_path: Path, alphabet_check, out_path: Path, cache: Path) -> None:
    vocabulary = extract_vocabulary(dic_path, alphabet_check)
    print(f"{lang}: {len(vocabulary):,} candidate words in {dic_path.name}")

    counts = build_frequencies.counts_for(lang, cache)
    print(f"  corpus: {len(counts):,} word types")

    ranked = [
        (word, build_frequencies.frequency_for(word, counts)) for word in vocabulary
    ]
    # Highest frequency first; alphabetical within a tie so the output is deterministic and diffs
    # stay readable.
    ranked.sort(key=lambda pair: (-pair[1], pair[0]))
    kept = ranked[:MAX_WORDS]

    out_path.write_text(
        "\n".join(f"{word}\t{freq}" for word, freq in kept) + "\n", encoding="utf-8"
    )

    attested = sum(1 for _, freq in kept if freq > build_frequencies.UNATTESTED_CEILING)
    size_kb = out_path.stat().st_size / 1024
    print(
        f"  wrote {out_path.name}: {len(kept):,} words, "
        f"{attested:,} ({attested * 100 // max(1, len(kept))}%) corpus-attested, {size_kb:.0f} KB"
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("en_dic", type=Path)
    parser.add_argument("ru_dic", type=Path)
    parser.add_argument("out_dir", type=Path)
    parser.add_argument("--cache", type=Path, default=Path(".freq-cache"))
    args = parser.parse_args()

    args.out_dir.mkdir(parents=True, exist_ok=True)
    build("en", args.en_dic, is_latin, args.out_dir / "en.txt", args.cache)
    build("ru", args.ru_dic, is_cyrillic, args.out_dir / "ru.txt", args.cache)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
