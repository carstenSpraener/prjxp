# Benchmark-Befund 02 — P-Arm: Toolnutzung und Antwortqualität

> **Status:** offen, 17.11.2026
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
