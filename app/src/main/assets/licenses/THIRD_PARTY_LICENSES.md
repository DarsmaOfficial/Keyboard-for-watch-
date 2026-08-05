# Third-party licences

This file lists every third-party component that ships **inside the APK**, together with its
licence. Build-time tools (Gradle, the Android Gradle Plugin, the Android SDK, JDK) are not
listed: their code is never redistributed as part of the application.

The project's own licence is Apache-2.0 — see [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).

---

## Currently bundled

| Component | Version | Licence | Upstream |
|---|---|---|---|
| Kotlin standard library (`kotlin-stdlib`) | 2.1.0 | Apache-2.0 | https://github.com/JetBrains/kotlin |
| `androidx.dynamicanimation` | 1.0.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| SymSpellKt | 3.4.0 | MIT | https://github.com/Darkrock-Studios/SymSpellKt |
| English word list (SCOWL, via `wooorm/dictionaries`) | 2020.12.07 | BSD-style | https://github.com/wooorm/dictionaries |
| Russian word list (Lebedev, via `wooorm/dictionaries`) | 2020 | BSD | https://github.com/wooorm/dictionaries |
| Word frequency counts (Wortschatz Leipzig Corpora Collection) | eng_news_2020, rus_news_2022 | CC BY 4.0 | https://wortschatz.uni-leipzig.de/en/download |

The word lists are derived from the upstream Hunspell `.dic` files: affix flags stripped, entries
lowercased and de-duplicated, then frequency-ranked and capped at the 10 000 most common words per
language (spec §4.2 — measured on the device, see `ARCHITECTURE.md`). The exact procedure is in
[`tools/build_dictionaries.py`](tools/build_dictionaries.py). The Hunspell *engine* is never
shipped — only the data, which is permissively licensed.

The frequency number beside each word is what lets autocorrect rank equally-close candidates
(without it, "helo" offered "halo / held / helm" and never "hello" — every candidate tied at
frequency 1). Counts come from the Leipzig news corpora, whose **downloadable text corpora are
released under CC BY**, an attribution-only licence compatible with this project — see
<https://wortschatz.uni-leipzig.de/en/usage>. Only the aggregate word-count column is read, and
only for words already in our list, so no corpus sentences are redistributed. Note that Leipzig's
*web applications and query data* carry the more restrictive CC BY-NC terms; those are a different
asset and are not used. The procedure is in
[`tools/build_frequencies.py`](tools/build_frequencies.py).

`hermitdave/FrequencyWords` was rejected for this purpose: its content is CC-BY-SA, a share-alike
obligation on the data that would propagate to every downstream redistributor.

All three data licences require their notice to travel with **binary** redistributions, so the
full texts are bundled into the APK and shown by the in-app licences screen.

In particular this release still contains:

- **no** third-party UI toolkit (the keyboard surface is plain `View` + `Canvas`)
- **no** bundled fonts — the watch's own system fonts are used, so no OFL obligations arise
- **no** native libraries of any kind — verify with `unzip -l app-release.apk | grep lib/`

---

## Kotlin standard library — Apache License 2.0

```
Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

The full Apache-2.0 text is reproduced in [`LICENSE`](LICENSE).

---

## Planned components and their licence obligations

These are **not yet shipped**. They are recorded here so the obligations are known before the
code lands, and so nothing is added without an audit.

### SymSpellKt (planned, for autocorrect) — MIT

A three-layer attribution chain, all three notices required:

```
Copyright (c) 2024 Adam Brown          (Kotlin Multiplatform port)
Copyright (c) 2019 Lucky Sharma        (Java implementation it derives from)
Wolf Garbe / SymSpell                  (original algorithm and reference implementation)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### English word list — SCOWL (BSD-style)

Permission is granted provided the copyright notice appears in all copies. All of these
lines must be reproduced verbatim:

```
Copyright 2000-2018 by Kevin Atkinson
Copyright (c) J Ross Beresford 1993-1999. All Rights Reserved.
Copyright 2000-2016 by Kevin Atkinson
Copyright 2016 by Benjamin Titze
Copyright 1993, Geoff Kuenning, Granada Hills, CA
```

…together with the accompanying permission notice and disclaimer, which must be pasted in full
when the word list is first bundled.

### Russian word list — BSD

```
Copyright (c) 1997-2008, Alexander I. Lebedev
```

The full BSD conditions and disclaimer must accompany it. Note that the clause
"Redistributions in binary form must reproduce the above copyright notice…" applies to the
**APK**, not merely to the source tree — hence the in-app licences screen.

### Word frequency counts — Wortschatz Leipzig Corpora Collection (CC BY 4.0)

Frequency values in `assets/dictionaries/*.txt` are derived from the aggregate word-count tables
of the Leipzig Corpora Collection (`eng_news_2020_100K`, `rus_news_2022_100K`).

```
Word frequency data derived from the Wortschatz Leipzig Corpora Collection
(https://wortschatz.uni-leipzig.de/), Natural Language Processing Group,
Leipzig University.

Licensed under the Creative Commons Attribution 4.0 International licence
(CC BY 4.0): https://creativecommons.org/licenses/by/4.0/

Reference:
D. Goldhahn, T. Eckart, U. Quasthoff. Building Large Monolingual Dictionaries
at the Leipzig Corpora Collection: From 100 to 200 Languages.
In: Proceedings of the 8th International Language Resources and Evaluation
(LREC'12), 2012.

Changes made: only the aggregate word/count column was read; counts were folded
to lowercase, summed, and retained solely for words already present in this
project's own word lists. No corpus sentences are redistributed.
```

CC BY 4.0 requires attribution and a statement of changes, both given above; it imposes no
share-alike obligation, so it does not affect the licensing of this project or of the APK.

### Explicitly rejected

| Component | Reason |
|---|---|
| `hermitdave/FrequencyWords` | Data is CC-BY-SA-4.0 — share-alike encumbrance incompatible with this project |
| German / Italian / Polish / Ukrainian / Czech word lists | GPL-licensed data |
| Portuguese word list | LGPL as a bundled data blob |
| `libjni_latinimegoogle.so` and friends | Closed-source GApps binaries |
