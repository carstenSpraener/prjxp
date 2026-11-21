# Benchmark-Befund 02 — P-Arm: Toolnutzung und Antwortqualität

> **Status:** P2 gemessen, Befunde geprüft — Guardrail verletzt, P1 bleibt Standard, 18.11.2026
> **Auslöser:** Lauf 3 über beide Korpora (`prjxp-bench`, 16.11.2026), P-Arm,
> Modell `qwen3.8-27b-splash`
> **Betroffene Module:** `mcp-server` (Tool-Vertrag, Suche, Read-Rendering),
> `prjxp-bench/harness` (Steer/Budget, Arm-Config)
> **Reindex nötig:** **nein** — nur Tool-Vertrag und Rendering; Korpus, Index
> und Graph bleiben unverändert

---

## 1. Ausgangslage

Lauf 3 hat beide Korpora vollständig gemessen: `runs/prjxp-20261116T052430Z`
(F1–F9) und `runs/legacy-20261116T052430Z` (L1–L9), je 108 Sessions
(N, P, G, V × 9 Fragen × 3 Wiederholungen), **216/216 `ok`**.

| Feld | prjxp | legacy |
|---|---|---|
| Sessions | 108/108 ok | 108/108 ok |
| Laufzeit | 103 min | 121 min |
| Quelle | `worktree` @ `2870c6aa7939…` | `worktree-legacy` (Manifest `9a2ae1…`) |
| Hub | `sha256:d19a96e0…`, `hub_drift=false` | identisch |
| Tool-Schema | `0a6c674a…` (16 Tools) | identisch |
| Budget | max. 8 Tool-Calls, Kontext 262.144 × 0,7 | identisch |
| Judge | inline LLM, `qwen3.8-27b-splash` | identisch |

Der P-Arm ist in beiden Korpora **die beste Retrieval-Arm, aber hinter N**:

| Korpus | Arm | Coverage | Precision | Score | Tokens effektiv | QPM | QEI |
|---|---|---:|---:|---:|---:|---:|---:|
| prjxp | **P** | **0,56** | 0,99 | **0,56** | 36.493 | **17,4** | 1,374 |
| prjxp | N | 0,60 | 1,00 | 0,60 | 34.866 | 15,8 | 1,573 |
| prjxp | G | 0,41 | 0,99 | 0,41 | 29.098 | 11,7 | 1,141 |
| prjxp | V | 0,38 | 0,94 | 0,36 | 52.313 | 7,3 | 0,701 |
| legacy | **P** | **0,43** | 0,96 | **0,41** | **39.108** | 9,9 | 0,912 |
| legacy | N | 0,59 | 1,00 | 0,59 | 50.348 | 10,6 | 1,173 |
| legacy | G | 0,43 | 0,89 | 0,38 | 43.948 | 9,1 | 0,846 |
| legacy | V | 0,22 | 0,81 | 0,18 | 65.839 | 3,1 | 0,278 |

**Lesart:** P ist präzise und hat auf prjxp den besten QPM (Score pro effektivem
Token), verliert aber systematisch dort, wo die Antwort aus mehreren
Code-Stellen zusammengesetzt werden muss. Die Ursache liegt nicht im Korpus,
sondern in der **Ausbeute der Tool-Calls**. Das ist die Grundlage der folgenden
Befunde.

---

## 2. Befund A — Das Projekt ist Teil des Tool-Vertrags, obwohl es feststeht

### Beobachtung

Der P-Arm läuft pro Lauf gegen genau ein Projekt, trotzdem verlangt jedes Tool
`project` als Pflichtparameter und `listProjects` wird als Einstieg empfohlen.

| Messgröße | prjxp | legacy |
|---|---:|---:|
| Tool-Calls gesamt | 234 | 252 |
| `listProjects`-Calls | 15 (6,4 %) | 21 (8,3 %) |
| Calls mit **fremdem** Projekt | 9 (3,8 %) | 0 |
| Sessions mit `listProjects` als 1. Call | 15/27 | 21/27 |

Die 9 Fremdprojekt-Calls stammen aus drei prjxp-Sessions, die je dreimal
`listFiles(project="legacy")` aufriefen — das Ergebnis war für die Frage
wertlos und verbrauchte 3 von 8 Budget-Calls.

### Ursache

- `mcp-server`: alle P-Tools deklarieren `project` als `required`.
- `listProjects`-Beschreibung: „Call before search tools to learn valid project
  values“ — der Agent folgt dieser Anweisung, obwohl das Projekt im
  `SYSTEM.md` steht.
- Der Hub hält beide Projekte; ohne Bindung ist ein Fehlgriff auf das andere
  Korpus möglich (Kontaminationsrisiko, nicht nur Kosten).

### Ziel

Der Projektbezug wird **serverseitig** gebunden (ein MCP-Server pro Lauf und
Korpus). Die Tools haben keinen `project`-Parameter mehr; `listProjects` ist
nicht Teil des P-Toolsets.

### Akzeptanz

- `listProjects`-Calls: **0**.
- Calls mit fremdem Projekt: **0**.
- Mindestens **+1** effektiver Call pro Session bei unverändertem Budget.

---

## 3. Befund B — Suche und Reads liefern zu wenig Information pro Call

### Beobachtung

| Messgröße | prjxp | legacy |
|---|---:|---:|
| `readBySignature`-Calls | 99 | 69 |
| davon Klassen-FQN (nur Rahmen, kein Body) | **87** | **48** |
| `grep`-Calls | 15 | 27 |
| `readChunks`-Calls | **0** | **0** |
| `vectorSearch`-Calls | 30 | 27 |
| Kontextkosten aller Tool-Antworten | 466.431 Tokens | – |

Drei Eigenschaften erzwingen Folgeschritte:

1. **`grep` ist exakter Token-Match und liefert nur Dateilisten** („count path“,
   Ø 319 Response-Tokens). Es gibt keine Trefferzeile und keine
   Substring-/Regex-Suche. Für jede Fundstelle ist ein zweiter Call nötig.
2. **`vectorSearch` liefert nur Pointer** (`file`, `chunk_id`, `score`) plus
   Lesehinweise. `readChunks` wird nie genutzt, obwohl die `chunk_id` vorliegt.
   Zusätzlich erscheinen Duplikate (derselbe `chunk_id` zweimal) und
   Testdateien in den Treffern.
3. **`readBySignature` liefert bei Klassen-FQN nur den Klassenrahmen**
   (Signaturen). Verhalten lässt sich daraus nicht belegen; der Agent müsste
   `FQN#methode` oder `readFile` nachziehen — was er in der Mehrzahl der Fälle
   nicht tut.

### Ursache

Die P-Tools sind auf **Pointer** optimiert (Befund 01), aber der Agent braucht
für „wie funktioniert X“ **Text**. Jeder Fakt kostet dadurch mindestens zwei
Calls: finden + lesen. Bei 8 Calls Budget bleiben effektiv 3–4 Fakten.

### Ziel

Gleiche Informationsgranularität wie bei N: **eine Suche liefert Fundstelle
und Kontextzeile**, ein Read liefert den relevanten Body.

### Umsetzung (empfohlen)

- `grep`: `mode=exact|substring|regex`, Ausgabe `datei:zeile` + Trefferzeile
  (max. 20 Treffer), weiterhin optional `NAMES`.
- `vectorSearch`: Top-2-Snippets (200–300 Zeichen) optional, Dedup nach
  `chunk_id`, Testfilter konsistent, expliziter Hinweis auf `readChunks(ids=…)`.
- `readBySignature`: bei Methoden den Body, bei Klassen Rahmen **plus Top-3
  Methodenbodies** (capbar über `includeBodies`/`maxBodies`).

### Akzeptanz

- `readChunks` > 0 Calls pro Lauf.
- Anteil Klassen-FQN-Reads ohne Body < 30 %.
- Duplikate in `vectorSearch` = 0; Testdateien nur mit `includeTests=true`.
- Calls pro beantwortetem KeyFact sinkt (Ziel: −20 %).

---

## 4. Befund C — Tool-Set und Schema sind zu groß

### Beobachtung

Der P-Arm hat 16 Tools; das Schema kostet **2.954 Tokens pro Turn** und macht
**38,4 %** (prjxp) bzw. **34,9 %** (legacy) des Inputs aus — der höchste
Prefix-Anteil aller Arme.

| Tool | Calls prjxp | Calls legacy |
|---|---:|---:|
| `graphFile` | 0 | 0 |
| `graphCluster` | 0 | 0 |
| `graphClusterOverview` | 0 | 0 |
| `semanticGraphNode` | 0 | 0 |
| `semanticGraphCluster` | 0 | 0 |
| `semanticGraphOverview` | 0 | 0 |
| `readChunks` | 0 | 0 |
| `listConfig` | 3 | 0 |
| `graphSearch` | 3 | 0 |

Sieben Tools werden in **beiden** Korpora nie genutzt; zwei weitere fast nie.
Die überlappenden Namen (`graphNode` vs. `semanticGraphNode`, `graphCluster`
vs. `semanticGraphCluster`) erhöhen zudem die Fehlauswahl.

### Ziel

Tool-Set auf die tatsächlich genutzten Fähigkeiten reduzieren und die
Beschreibungen auf Entscheidungsregeln verkürzen („für X → Tool Y“).

### Umsetzung (empfohlen)

Behalten: `vectorSearch`, `readChunks`, `readBySignature`, `readFile`, `grep`,
`listFiles`, `graphNode`, `graphSearch`, `listConfig` (9 Tools).
Entfernen: `graphFile`, `graphCluster`, `graphClusterOverview`,
`semanticGraphNode`, `semanticGraphCluster`, `semanticGraphOverview`,
`listProjects`.

### Akzeptanz

- Schema ≤ **2.000 Tokens/Turn** (heute 2.954).
- Kein Tool mit 0 Calls in beiden Korpora.
- Prefix-Anteil am Input ≤ 30 % (heute 38,4 % / 34,9 %).

---

## 5. Befund D — Der Steer beendet die Exploration hart bei 8 Calls

### Beobachtung

| Messgröße | prjxp | legacy |
|---|---:|---:|
| gesteuerte Sessions | 18/27 | 24/27 |
| Grund | `tool_calls>=8` | `tool_calls>=8` |
| Ø Tool-Calls/Session | 8,7 | 9,3 |

Der Steer-Text lautet: „Budget erreicht: keine weiteren Tool-Aufrufe.
Beantworte die Frage jetzt …“. Fast jede P-Session wird damit abgebrochen,
obwohl die Antwort nachweislich unvollständig ist.

Mehr Calls allein helfen nicht: G und V liegen mit 9,9–10,6 Calls über P und
haben niedrigere Coverage. Der Hebel ist die **Ausbeute**, nicht die Anzahl.

### Ziel

Erst Befunde A–C beheben, dann das Budget **für alle Arme gleich** auf 10–12
anheben. Der Steer fordert zwei gezielte Verifikations-Calls statt eines harten
Stopps.

### Akzeptanz

- Steer-Grund `tool_calls>=8` entfällt; Sessions enden überwiegend durch
  `agent_settled`.
- Steer-Quote < 50 % je Arm.
- Kein Arm verliert Precision durch zusätzliche Calls.

---

## 6. Maßnahmen — generisch, ohne Korpus- oder Fragenoptimierung

Zulässig ist nur, was **identisch für beide Korpora** wirkt, aus einer
allgemeinen Eigenschaft der Schnittstelle folgt, **keine Frage- oder
Dateinamen** nennt und auf beiden Korpora mindestens neutral ist.

| # | Maßnahme | Evidenz | Erwartung | Aufwand |
|---|---|---|---|---|
| 1 | Projekt serverseitig binden, `listProjects` entfernen | 6,4 %/8,3 % Blind-Calls; 9 Fremdprojekt-Calls | +1–2 effektive Calls/Session | klein |
| 2 | `grep` mit Substring/Regex + Trefferzeile | Suchen liefern nur Dateilisten; N gewinnt mit Zeilen | Größter Qualitätshebel | mittel |
| 3 | `vectorSearch`: Snippets, Dedup, Testfilter, `readChunks`-Hinweis | Duplikate, 0 `readChunks`, Pointer-only | −1 Call pro Fakt | klein–mittel |
| 4 | `readBySignature` mit Bodies (Klassen: Top-3) | 87/99 bzw. 48/69 Reads ohne Body | Belege statt Signaturen | klein |
| 5 | Tool-Set 16 → 9, Beschreibungen kürzen | 7 Tools 0× genutzt; 2.954 Tokens/Turn | −1.000 Prefix-Tokens/Turn | klein |
| 6 | Budget 10–12 für alle Arme, Steer „2 gezielte Calls“ | 18/27 bzw. 24/27 hart gestoppt | mehr Verifikation | klein |
| 7 | `tree`/`find` (Dirs + Glob + Größe) | `listFiles`-Musterketten ohne Treffer | weniger Blind-Suche | mittel |

**Nicht zulässig:** Prompt-Hinweise auf konkrete Dateien/Konzepte,
Similarity-Schwellen oder Whitelists pro Korpus, fragen-spezifische
Tool-Empfehlungen, nachträgliche Änderung der F-/L-Fragen.

Maßnahmen 1–5 sind reine Tool-Vertragsänderungen (P2) und können ohne
Prompt-Eingriff gemessen werden. 6–7 folgen erst nach belegter Wirkung.

---

## 7. Messplan (A/B, beide Korpora, kein Korpus-Fitting)

1. **Interface-Metriken zuerst**, ohne Score-Blick: Blind-Calls,
   Fremdprojekt-Calls, Duplikate, Empty-Results, Calls pro beantwortetem Fakt,
   Schema-Tokens/Turn.
2. **A/B in einem Lauf:** P und P2 gemeinsam, identische Fragen, Seed 42,
   gleiche Modell-Ladung, gleicher Hub. Nur so ist der Unterschied der
   Tool-Vertrag und nicht Modell-/Hub-Drift.
3. **Beide Korpora als zwei unabhängige Stichproben.** Erfolgskriterium vorab:
   gepoolte Coverage über alle 18 Fragen ≥ P, Precision ≥ P − 0,01, **kein
   Korpus** mehr als 0,03 schlechter.
4. **Einmal messen, nicht iterieren.** Die 18 Fragen dienen der Verifikation,
   nicht der Optimierung. Für weitere Runden neue, unveröffentlichte Fragen je
   Korpus (Answer-Key außerhalb der Worktrees).
5. **Judge ≠ Arm-Modell.** Aktuell bewertet `qwen3.8-27b-splash` seine eigenen
   Antworten; für P2 `qwen3.6-27b` als Judge setzen.

Erwarteter Aufwand: P + P2 × 18 Fragen × 3 Reps = 108 Sessions, ca. 2 h.

---

## 8. Risiken

- **Overfitting:** Jede Änderung, die aus einem konkreten Fehlfall abgeleitet
  wird, muss als generische Schnittstellen-Regel formuliert werden; sonst
  misst man Fragen-Tuning.
- **Token-Bloat:** Bodies und Snippets erhöhen die Antwortgröße. Gegenmittel:
  harte Caps (`maxBodies`, Snippet-Länge) und die bestehenden
  `ToolResultCaps`.
- **Capability-Verlust:** Das Streichen ungenutzter Tools ist nur zulässig,
  wenn sie in beiden Korpora 0 Calls haben; nach P2 erneut prüfen.
- **Protokollbruch:** Budget-/Steer-Änderungen (Maßnahme 6) verändern die
  Arm-Definition und dürfen nicht rückwirkend in Lauf 3 interpretiert werden.
- **Judge-Bias:** Solange das Arm-Modell judge't, sind kleine
  Coverage-Unterschiede nicht belastbar.

---

## 9. Belege

- `../prjxp-bench/runs/prjxp-20261116T052430Z/` — `results.md`, `metrics.json`,
  `judge.json`, `tool-report.md` (P: 234 Calls, 466.431 Kontext-Tokens).
- `../prjxp-bench/runs/legacy-20261116T052430Z/` — `results.md`,
  `metrics.json`, `judge.json` (P: 252 Calls).
- `../prjxp-bench/runs/pipeline-20261116T052430Z.log` — Runner-/Judge-/Measure-
  Log, 108/108 ok je Korpus.
- `../prjxp-bench/doc/ErsteMessungen/protocol.md` — Eintrag „Lauf 3 — beide
  Korpora“ (Setup, Hashes, Deltas).
- `../prjxp-bench/harness/tool-report.py` — Kontextkosten je Tool-Call.

---

## 10. Aufwand

| Paket | Inhalt | Schätzung |
|---|---|---|
| P2-Tool-Vertrag | Maßnahmen 1–5 (MCP-Server + Arm-Config) | 1–2 Tage |
| P2-Messung | A/B-Lauf beide Korpora, Report | 0,5 Tag + 2 h Laufzeit |
| Protokoll v2 | Maßnahmen 6–7 (Budget/Steer, `tree`) | 0,5–1 Tag |
| Holdout | neue Fragen je Korpus, Answer-Key | 1 Tag |

---

## 11. Umsetzung (17.11.2026)

P2 ist als zweiter Tool-Vertrag im Hub implementiert; P1 bleibt unverändert der
Kontrollarm. Der Umschlag ist eine Start-Property, kein Prompt-Eingriff:

| Property | Werte | Wirkung |
|---|---|---|
| `prjxp.hub.tool-profile` | `p1` (Default) \| `p2` | wählt die MCP-Toolfläche |
| `prjxp.hub.bound-project` | Projektname | P2: serverseitige Projektbindung, Pflicht |

**P1** bleibt byte-identisch (16 Tools, `project`-Parameter, `listProjects`).
**P2** bietet 9 Tools ohne `project`-Parameter; `grep` liefert `datei:zeile` +
Trefferzeile (`substring`/`exact`/`regex`), `vectorSearch` ergänzt Snippet,
Chunk-Dedup und `readChunks`-Hinweis, `readBySignature` hängt an einen
Klassen-FQN die ersten Methodenbodies an (`includeBodies`, `maxBodies`).

**A/B-Anordnung:** ein P1-Hub wie bisher (beide Projekte, Projekt im
`SYSTEM.md`) und je Korpus ein P2-Hub mit `prjxp.hub.bound-project=prjxp` bzw.
`legacy`, alle aus demselben Image. Damit ist der Unterschied der Tool-Vertrag
und nicht ein anderer Build. Judge: `qwen3.6-27b` (≠ Arm-Modell).

**Tests:** `PrjxpToolService` trägt die Datenlogik, `PrjxpMcpTool`/`PrjxpP2McpTool`
nur die Annotationen. Abgedeckt sind Profil-/Bindungslogik, Grep-Modi mit
Zeilennummern, Klassen-Bodies, Snippet/`readChunks`-Rendering und die
9-Tool-Fläche. Modultests: `prjxp-common`, `golden-retriever`, `lucene-store`,
`mcp-server` grün.

## 12. A/B-Ergebnis P1 vs. P2 (18.11.2026)

**Anordnung:** ein Lauf über beide Korpora (`prjxp` F1–F9, `legacy` L1–L9),
je 3 Wiederholungen, Arm-Modell `qwen3.8-27b-splash`, Judge `qwen3.6-27b`
(≠ Arm-Modell, 108/108 Sessions per LLM bewertet). P1 lief unverändert gegen
`prjxp-hub`, P2 gegen `prjxp-hub-p2` (Profil `p2`, gebundenes Projekt je Korpus,
eigenes Datenvolume). Beide Hubs aus demselben Image, gleicher Index, gleicher
Quellbaum; `p2_hub_drift=false`, `p2_tool_count=9`.

Läufe: `ab-p2-prjxp-20261117T132231Z`, `ab-p2-legacy-20261117T132231Z`.

| Arm/Korpus | n | Coverage | Precision |
|---|---|---|---|
| P gepoolt | 54 | **0,414** | **0,921** |
| P2 gepoolt | 54 | 0,352 (−0,062) | 0,860 (−0,061) |
| P prjxp | 27 | 0,501 | 0,952 |
| P2 prjxp | 27 | 0,459 (−0,042) | 0,899 |
| P legacy | 27 | 0,327 | 0,891 |
| P2 legacy | 27 | 0,245 (−0,082) | 0,821 |

**Guardrail verletzt:** gepoolte Precision 0,860 < 0,921 − 0,01 und in beiden
Korpora mehr als 0,03 schlechter. P2 wird deshalb **nicht** Standard; `p1`
bleibt Default. Der Schalter bleibt für weitere Varianten erhalten.

**Nebenbefunde (gepoolt, nur `ok`-Sessions):** P2 braucht 7,7 % weniger Tokens
(47.913 vs. 51.935), aber 12 % mehr Tool-Aufrufe (10,1 vs. 9,0) und 38 % mehr
Tool-Ausgabe (33.598 vs. 24.263 Zeichen) bei gleicher Wanduhrzeit (61,5 vs.
60,7 s). Die Token-Ersparnis kommt also aus dem kleineren Tool-Schema, nicht aus
besseren Antworten.

**Wirkung der Einzelmaßnahmen:** `grep` wird deutlich häufiger genutzt
(prjxp 15 → 87 Aufrufe, legacy 27 → 69) und liefert `datei:zeile` statt Dateien.
Das führte messbar in Testcode und auf falsche Einstiegspunkte: bei L9
antwortet P2 konsistent über `ZvfImportFromBauplanFacadeImpl`, während P1 (und
der Antwortschlüssel) über `ImportZvfXmlAction` geht — Coverage 0,00 vs. 0,42.
Die zusätzlichen Zeilen-Treffer erhöhen den Kontext, ohne die Abdeckung zu
verbessern; `readBySignature` mit Rümpfen wird etwas häufiger genutzt
(prjxp 99 → 117), ohne Precision zu halten.

> **Einschränkung (siehe §13):** Das Delta ist nicht kausal dem P2-Vertrag\n> zuzuordnen. `grep` war durch einen 10.000-Treffer-Deckel und fehlende\n> Rangfolge defekt, und Snippet/Rümpfe teilen sich Token-Budget und\n> Aufruf-Steering. §13 enthält die Einzelprüfung, die Fixes und den\n> Ablationslauf.\n\n**Nächste Varianten (je eigener A/B-Lauf, nicht in P2 gemischt):**
`grep` standardmäßig ohne Tests und mit kleinerem `limit`, kein
`readChunks`-Hinweis in `vectorSearch`, `readBySignature` ohne Rumpf-Default.
Erst wenn eine Variante die Guardrail hält, wird sie zum neuen Profil.

## 13. Befundprüfung: Welche Maßnahme überschreibt welche? (18.11.2026)

Der A/B-Lauf bündelt fünf Änderungen. Vor der Interpretation wurde deshalb
jede Maßnahme einzeln gegen die 108 Sessions und gegen beide Hubs geprüft.
Ergebnis: **das Qualitätsdelta aus §12 ist nicht dem P2-Vertrag zuzuschreiben.**
Zwei Maßnahmen waren defekt, zwei überlagern sich über gemeinsame Ressourcen.

### 13.1 M2 (`grep` mit `file:line`) war nicht korrekt messbar

* **Harter 10.000-Treffer-Deckel.** `LucenePxChunkDao.findAll()` lief über
  `searcher.search(projectFilter(), 10000)`. Der Index hat 14.418 (`prjxp`)
  bzw. 40.364 (`legacy`) Chunks, der Zeilen-Scan sah also nur ca. 69 % bzw.
  25 % des Projekts — ohne Hinweis. Nachweis: `grep "^package "` meldete auf
  `prjxp` „1 of 697“ Treffer bei 991 Dateien; nach dem Fix „1 of 984“.
* **Keine Rangfolge.** Treffer wurden in Index-Reihenfolge ausgegeben, `limit`
  wirkte als willkürliches Präfix. Dadurch dominierte Testcode, und
  `FileGraphService` lieferte nur Treffer aus `FileGraphServiceTest`.
* **Geänderte Semantik.** P1 nutzt `searchFullText` (StandardAnalyzer, alle
  Tokens als MUST, kleingeschrieben, pro Datei gruppiert); P2 scannt Zeilen
  literal. `grep "Terminfreigabe"` findet in P1 nichts (Token wird zu
  `terminfreigabe`), in P2 dagegen Zeilen — die beiden Arme vergleichen also
  nicht dieselbe Operation.
* **Tests standardmäßig an.** P2 setzt `includeTests=true`; damit sind die
  meistgenutzten Treffer Testdateien. Das ist eine eigene Design-Entscheidung,
  die mit der Zeilen-Semantik vermischt wurde.

**Fix:** `searchAllLucene` nutzt jetzt `reader.numDocs()` statt 10.000;
`searchMatches` sammelt pro Datei, sortiert Dateien nach Trefferzahl und
wendet `limit` erst danach an (Speicher über ein Budget begrenzt, `total`
zählt weiterhin alle Treffer). Neue Tests: Rangfolge, Präfix-Rendering,
Vollzähligkeit.

### 13.2 M3 und M4 teilen sich das Token-Budget

`vectorSearch` ist auf 2.000 Tokens gedeckelt (`ToolResultCaps.SEARCH_TOKENS`).
Das Snippet aus M3 verbraucht diesen Deckel: 24 von 51 P2-Aufrufen lagen am
Limit (P1: 6 von 57), und die Zahl der Pointer-Blöcke fiel von 8 auf 6
(maximal 16 → 12). M3 reduziert damit faktisch `maxResults`, statt nur
Zusatzinformation zu liefern. Gleichzeitig ist der `readChunks`-Hinweis
wirkungslos: nur 3 Aufrufe in 108 Sessions.

M4 (`readBySignature` mit Rümpfen) ist die größte Kontext-Inflation:
168 → 204 Aufrufe, 2.172 → 3.518 Zeichen pro Aufruf, in Summe 365k → 718k
Zeichen, Maximum 13.940 Zeichen (P1: 8.083). Es gibt keine Gesamtobergrenze.
Beide Maßnahmen laufen zusätzlich in dasselbe 8-Aufrufe-Steering (P: 42/54,
P2: 45/54), das den Kontext nachträglich kürzt. Ein Delta lässt sich daher
weder M3 noch M4 zuordnen.

### 13.3 M5 verdeckt M2/M4 im Kopfmaß

Die Token-Ersparnis von 7,7 % stammt aus dem Tool-Schema: 2.966 → 1.528
Tokens pro Turn (−1.438). Die Tool-Ausgabe stieg im selben Lauf von 6.066 auf
8.399 Tokens. Das Kopfmaß „weniger Tokens“ ist damit eine Folge von M1/M5
und maskiert die Inflation aus M2/M4.

### 13.4 M1 verstärkt M2

`listProjects` war das einzige entfernte Tool, das P1 tatsächlich genutzt hat
(36 Aufrufe, je 100 Zeichen). Sein Wegfall lenkt die Projekterkennung auf
`grep` um (42 → 156 Aufrufe), dessen Defekt aus 13.1 damit stärker wiegt.

### 13.5 Konsequenz

Die Guardrail-Aussage aus §12 bleibt gültig (P1 bleibt Standard), die
Begründung aber nicht: der gemessene Unterschied ist durch den Grep-Deckel,
die fehlende Rangfolge und die Budget-Kollision verzerrt. Künftige Aussagen
über P2 brauchen eine Ablation, in der genau eine Maßnahme variiert.

**Umsetzung:** `prjxp.hub.p2.grep-lines`, `prjxp.hub.p2.snippets`,
`prjxp.hub.p2.bodies` (Default `true`, in `P2Measures`) schalten die drei
Rendering-Maßnahmen einzeln. Es gibt genau einen Hub; eine Ablation ist
deshalb ein Neustart desselben Containers mit
`PRJXP_HUB_P2_GREP_LINES=false` / `PRJXP_HUB_P2_SNIPPETS=false` /
`PRJXP_HUB_P2_BODIES=false`. `hub_meta` hält die aktive Variante als
`p2_measures` fest, der Preflight erzwingt Profil und Projektbindung.
Oberfläche, Projektbindung und Budgets bleiben dabei konstant.

## 14. Messung nach den Fixes (21.11.2026, Korpus `prjxp`)

Geprüft wurden die drei Defekte aus §13: `searchAllLucene` deckelte auf
10.000 Treffer statt auf `reader.numDocs()`, `searchMatches` rankte erst
nach dem `limit` pro Datei, und `IsIn`/`IsNotIn` erzeugten mehr als
1.024 Klauseln (`TooManyClauses`). Zusätzlich ist der Hub jetzt
projektgebunden (`PRJXP_HUB_BOUND_PROJECT`), `listProjects` entfällt.

Lauf `ab-p2-fix2-prjxp-20261120T164933Z` gegen die eingefrorene
P1-Kontrolle `ab-p2-prjxp-20261117T132231Z` (gleiches Index-Manifest
`82aa4159…`, 27 Sessions je Arm):

| Arm | Coverage | Precision | Tokens | Retrieval | Calls | Dauer |
|---|---|---|---|---|---|---|
| P1 | 0.501 | 0.952 | 46.144 | 26.268 | 8,7 | 54,6 s |
| P2 | 0.512 (+0.011) | 0.972 | 41.883 (−9,2 %) | 29.906 (+13,8 %) | 8,3 | 45,8 s |

Alle 27 Sessions `ok`; der Precision-Guardrail ist für dieses Korpus
eingehalten. Je Frage:

| Frage | Δ Coverage | Δ Precision |
|---|---|---|
| F1, F2, F3 | ±0.000 | ±0.000 |
| F4 | −0.022 | +0.072 |
| F5 | −0.152 | −0.037 |
| F6 | +0.077 | +0.361 |
| F7 | +0.167 | −0.214 |
| F8 | +0.179 | ±0.000 |
| F9 | −0.143 | ±0.000 |

Deutig profitiert die Grep-Klasse (F6, F7, F8): mehr Treffer, bessere
Rangfolge, präzisere Fundstellen. Die Gegenbewegungen sind nicht
maßnahmenkausal belegt: in F5 ersetzt P2 mehrere gezielte
`readBySignature`-Aufrufe durch `readFile` auf `ChunkProcess` und
verliert dadurch Fakten; in F9 und F5 bleibt P2 bei 6–10 Aufrufen und
liest weniger Dateien als P1. Das ist eine Folge der Aufruf-/Budgetwahl,
nicht von Grep oder Snippets.

Offen: für die Zuordnung der Einzelmaßnahmen fehlt die Ablation (§13.5).

## 15. Legacy: Guardrail verletzt — Rumpf-Budget fehlt (21.11.2026)

Derselbe Hub, gebunden auf `legacy` (`legacy-20261121T052008Z`, 27 Sessions
`ok`), gegen die eingefrorene P1-Kontrolle `ab-p2-legacy-20261117T132231Z`:

| Arm | Coverage | Precision |
|---|---|---|
| P1 gepoolt (beide Korpora) | 0.414 | 0.921 |
| P2 gepoolt (beide Korpora) | 0.361 (−0.053) | 0.896 |
| P1 `legacy` | 0.327 | 0.891 |
| P2 `legacy` | 0.209 (−0.118) | 0.821 |

Die Precision-Guardrail ist damit **verletzt** (gepoolt und in `legacy`).
P2 bleibt also nicht Standard; die Ursache ist aber nicht die
Grep-Rangfolge, sondern eine bis dahin ungedeckelte Maßnahme.

**Befund:** `readBySignature` mit Rümpfen hat keinen Gesamtdeckel pro
Aufruf, nur 10.000 Zeichen je Rumpf bei bis zu zehn Treffern. In `legacy`
liefert ein Aufruf im Schnitt 4.272 Zeichen (P1: 2.280), maximal 17.833
(P1: 8.083); über den Lauf 436k statt 157k Zeichen. Die Folge sind
abgeschnittene oder überlaufende Rümpfe und weniger Platz für gezielte
Reads: in L5 sucht P2 zehnmal nach `VorgangService` (existiert nicht),
während P1 über `MastervorgangService`/`TeilvorgangService` liest.
`prjxp` ist davon kaum betroffen (228k vs. 208k), weil die dortigen
Rümpfe klein sind — die Wirkung ist korpusabhängig, die Ursache generisch.

**Maßnahme (generisch):** Im P2-Profil teilen sich alle Rümpfe eines
Aufrufs ein Budget von `ToolResultCaps.READER`. Reicht es nicht, wird der
letzte Rumpf gekürzt und die folgenden Treffer behalten nur ihre Signatur;
der P1-Pfad bleibt unverändert (Deckel je Rumpf). Tests:
`PrjxpToolServiceBodyBudgetTest` (Budget, Signatur-only, P1 unverändert).

**Nächster Schritt:** neu bauen, `legacy` erneut messen, danach `prjxp`
mit demselben Image (die Änderung wirkt in beiden Korpora).
