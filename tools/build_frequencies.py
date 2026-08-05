#!/usr/bin/env python3
"""Attach frequency counts to the shipped word lists so autocorrect can rank candidates.

Why this exists
---------------
The word lists produced by build_dictionaries.py carry no frequency information, so every entry
was loaded into SymSpell with frequency 1.0. With `maxEditDistance = 1` a short typo has many
candidates at the same edit distance, and with equal frequencies SymSpell's ordering among them
is arbitrary. Reproduced on the watch: typing "helo" offered "halo / held / helm" and never
"hello". Each of those is a legitimate distance-1 neighbour; the ranking was the defect.

Source: **Wortschatz Leipzig Corpora Collection** news corpora.
  - Licence: the downloadable text corpora are released under **CC BY** (attribution only).
    Confirmed at <https://wortschatz.uni-leipzig.de/en/usage>: "The text corpora offered for
    download are made available under the Creative Commons licence CC BY." Attribution-only is
    compatible with an Apache-2.0 project; the required credit lives in THIRD_PARTY_LICENSES.md
    and in the in-app licences screen.
  - Note the site's *applications and query data* are CC BY-NC — that is a different asset and is
    not used here. Only the downloadable corpus archives are.

Alternatives considered and rejected
------------------------------------
- `hermitdave/FrequencyWords` — content is CC-BY-SA, a share-alike obligation on the data.
  Rejected by spec §4.3 and not reconsidered here.
- SCOWL size bands — already available in the source dictionaries, but far too coarse to fix
  this bug: "hello" and "halo" both sit in band 35, which is precisely the tie being resolved.
- Project Gutenberg word counts — genuinely public domain and tried first, but 19th-century
  prose is a poor model of what someone types on a watch, and Gutenberg's Russian holdings are
  too small to rank a 30k list (measured: 0% coverage).

Only the aggregate `word<TAB>count` column of the corpus is read, and only counts for words
already present in our list are kept, so no corpus sentences are redistributed.

Output is `word<TAB>frequency`, which SpellEngine.load already parses.

Usage:
    python3 tools/build_frequencies.py <wordlist.txt> <out.txt> --lang en|ru [--cache DIR]
"""
import argparse
import sys
import tarfile
import urllib.request
from collections import Counter
from pathlib import Path

BASE = "https://downloads.wortschatz-leipzig.de/corpora"

# The 100K-sentence editions are the smallest that still give stable counts for a 30k word list
# (~25 MB download, ~150k word types). Larger editions change nothing about the ranking that
# matters here and cost ten times the bandwidth.
CORPORA = {
    "en": "eng_news_2020_100K",
    "ru": "rus_news_2022_100K",
}


def fetch_corpus(name: str, cache_dir: Path) -> Path:
    cache_dir.mkdir(parents=True, exist_ok=True)
    archive = cache_dir / f"{name}.tar.gz"
    if not archive.exists():
        url = f"{BASE}/{name}.tar.gz"
        print(f"  downloading {url}")
        request = urllib.request.Request(url, headers={"User-Agent": "wearkey-freq-builder"})
        with urllib.request.urlopen(request, timeout=600) as response:
            archive.write_bytes(response.read())
    return archive


def read_counts(archive: Path, name: str) -> Counter:
    """Reads the corpus' word-frequency table.

    Format is `id<TAB>word<TAB>count`. Counts are folded to lowercase and summed, because our
    dictionary is lowercase and a sentence-initial "Hello" is the same word as "hello" — keeping
    them apart is what made "hello" look rare in the first place.
    """
    counts: Counter = Counter()
    member_name = f"{name}/{name}-words.txt"
    with tarfile.open(archive, "r:gz") as tar:
        try:
            handle = tar.extractfile(member_name)
        except KeyError:
            handle = None
        if handle is None:
            raise RuntimeError(f"{member_name} missing from {archive.name}")
        for raw in handle:
            parts = raw.decode("utf-8", errors="ignore").rstrip("\n").split("\t")
            if len(parts) < 3:
                continue
            word, value = parts[1].strip().lower(), parts[2].strip()
            if not word or not value.isdigit():
                continue
            counts[word] += int(value)
    return counts


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("wordlist", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--lang", required=True, choices=sorted(CORPORA))
    parser.add_argument("--cache", type=Path, default=Path(".freq-cache"))
    args = parser.parse_args()

    words = [
        line.split("\t", 1)[0].strip()
        for line in args.wordlist.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.startswith("#")
    ]
    print(f"{args.lang}: ranking {len(words):,} words")

    name = CORPORA[args.lang]
    counts = read_counts(fetch_corpus(name, args.cache), name)
    print(f"  corpus {name}: {len(counts):,} word types")

    lines = []
    covered = 0
    for word in words:
        observed = counts.get(word, 0)
        if observed > 0:
            covered += 1
            # Offset keeps every corpus-attested word strictly above every unattested one, so a
            # word the corpus happens to miss can never outrank one it saw.
            frequency = observed + 100
        else:
            # Unattested words still need a defensible order rather than a mass tie. Shorter
            # words are overwhelmingly the more common ones — the same proxy build_dictionaries.py
            # uses to choose which words to ship at all.
            frequency = max(1, 20 - len(word))
        lines.append(f"{word}\t{frequency}")

    args.output.write_text("\n".join(lines) + "\n", encoding="utf-8")
    size_kb = args.output.stat().st_size / 1024
    print(
        f"  wrote {args.output.name}: {len(lines):,} entries, "
        f"{covered:,} ({covered * 100 // max(1, len(words))}%) corpus-attested, {size_kb:.0f} KB"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
