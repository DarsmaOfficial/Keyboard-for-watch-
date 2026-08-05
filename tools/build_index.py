#!/usr/bin/env python3
"""Compile a word list into the packed binary index WearKey memory-maps at runtime.

Why this format exists
----------------------
SymSpellKt keeps its symmetric-delete table as `Map<Long, ArrayList<String>>` plus
`Map<String, Double>` for frequencies (verified with javap on SymSpellKt-jvm-3.4.0). That is three
Java objects per delete variant — a HashMap.Node, a boxed Long key and an ArrayList — and with
10 000 words there are 68 625 distinct variants. Measured on the watch with `dumpsys meminfo`
(Dalvik Heap → Alloc), one resident dictionary cost **15.5 MB** against the specification's 8 MB
gate (§14). Reducing the word list from 30 000 to 10 000 had already brought it down from
39.8 MB, so the remaining overhead is per-object cost, not list size.

Spec §4.2 anticipated exactly this and states the decision rule: "Benchmark SymSpellKt's actual
retained heap on-device first. If it fits the budget, use it as-is and drop the mmap claim. If it
does not, write a small flat-trie reader with a genuine mmap path. Pick one." It does not fit, so
this is that path.

The format holds no Java objects at all: every table is a primitive array inside one file, which
is mapped read-only. Its pages are clean, file-backed and evictable, so they are counted as mapped
pages by the kernel rather than as Java heap, and several processes could share them.

File layout (all integers little-endian, as ByteBuffer.LITTLE_ENDIAN reads them)
-------------------------------------------------------------------------------
    magic           4 bytes   "WKD1"
    wordCount       int32
    variantCount    int32     distinct delete-variants
    pairCount       int32     variant -> word references
    blobLength      int32     length of the word blob in bytes
    wordOffset[]    int32 * wordCount        offset of each word in the blob
    wordLength[]    uint8 * wordCount        length of each word in bytes
    frequency[]     int32 * wordCount        corpus frequency, descending word order
    variantHash[]   int64 * variantCount     sorted ascending, binary-searchable
    pairStart[]     int32 * (variantCount+1) slice bounds into wordIndex[]
    wordIndex[]     int32 * pairCount        index of the word owning each pair
    blob            UTF-8 bytes of every word, concatenated

Words are stored in descending frequency order, so a candidate list is already ranked by the time
it is produced and no sorting is needed per keystroke.

The hash must match WordIndex.hash64 in the Kotlin reader exactly; it is FNV-1a 64-bit over the
UTF-8 bytes, chosen because it is trivial to reimplement identically on both sides.

Usage:
    python3 tools/build_index.py <wordlist.txt> <out.bin>
"""
import struct
import sys
from pathlib import Path

MAGIC = b"WKD1"
FNV_OFFSET = 0xCBF29CE484222325
FNV_PRIME = 0x100000001B3
MASK64 = 0xFFFFFFFFFFFFFFFF


def hash64(text: str) -> int:
    """FNV-1a over UTF-8 bytes, returned as a signed 64-bit value to match Kotlin's Long."""
    h = FNV_OFFSET
    for byte in text.encode("utf-8"):
        h ^= byte
        h = (h * FNV_PRIME) & MASK64
    # Python ints are unbounded; fold to the signed range Kotlin will read.
    return h - (1 << 64) if h >= (1 << 63) else h


def variants(word: str) -> set:
    """The word itself plus every single-character deletion — the edit-distance-1 delete set."""
    out = {word}
    for i in range(len(word)):
        out.add(word[:i] + word[i + 1:])
    return out


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2

    src, dst = Path(sys.argv[1]), Path(sys.argv[2])

    entries = []
    for line in src.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t", 1)
        word = parts[0].strip().lower()
        if not word:
            continue
        freq = int(parts[1]) if len(parts) > 1 and parts[1].strip().isdigit() else 1
        entries.append((word, freq))

    # Descending frequency, alphabetical within ties: makes lookups pre-ranked and the output
    # deterministic.
    entries.sort(key=lambda pair: (-pair[1], pair[0]))

    blob = bytearray()
    offsets, lengths, freqs = [], [], []
    for word, freq in entries:
        encoded = word.encode("utf-8")
        if len(encoded) > 255:
            continue
        offsets.append(len(blob))
        lengths.append(len(encoded))
        freqs.append(freq)
        blob.extend(encoded)

    # variant hash -> indices of the words that own it
    buckets: dict[int, list[int]] = {}
    for index, (word, _) in enumerate(entries):
        for variant in variants(word):
            buckets.setdefault(hash64(variant), []).append(index)

    variant_hashes = sorted(buckets)
    pair_start = [0]
    word_index: list[int] = []
    for h in variant_hashes:
        # Word indices are already in descending-frequency order because `entries` is, so the
        # reader can take the first N of a bucket and have the best candidates.
        word_index.extend(buckets[h])
        pair_start.append(len(word_index))

    out = bytearray()
    out += MAGIC
    out += struct.pack("<iiii", len(entries), len(variant_hashes), len(word_index), len(blob))
    out += struct.pack(f"<{len(offsets)}i", *offsets)
    out += bytes(lengths)
    out += struct.pack(f"<{len(freqs)}i", *freqs)
    out += struct.pack(f"<{len(variant_hashes)}q", *variant_hashes)
    out += struct.pack(f"<{len(pair_start)}i", *pair_start)
    out += struct.pack(f"<{len(word_index)}i", *word_index)
    out += bytes(blob)

    dst.write_bytes(out)

    print(f"{dst.name}: {len(entries):,} words, {len(variant_hashes):,} variants, "
          f"{len(word_index):,} pairs, {len(out) / 1024:.0f} KB")
    print(f"  mapped at runtime, so ~0 java heap (was 15.5 MB with SymSpellKt)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
