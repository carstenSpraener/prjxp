# Retrieval Improvements

Dieses Dokument fasst die identifizierten Verbesserungen für den Retrieval-Pfad von `golden-retriever` zusammen.

## Ausgangslage

Beim Testen des MCP-Tools `vectorSearch` (Projekt `cgv19`) zeigte sich ein **nicht-monotones Verhalten** des `distance`-Parameters. Messung (sequenziell, determinisch, Query „Fluent API für Transformationen in cgv19"):

| distance | 0.85 | 0.86 | 0.90 | 0.91 | 0.92 | 0.93 | 0.94 | 0.95 | 0.96 | 0.97 |
|---|---|---|---|---|---|---|---|---|---|---|
| Klassen im Output | 2 | 1 | 2 | 1 | 1 | **12** | **12** | 2 | 1 | 1 |

Ein strikterer Request (0.93) liefert also *mehr* Output als ein lockerer (0.92). Ursache ist kein einzelner Bug, sondern das Zusammenspiel mehrerer Mechanismen (siehe „Ist-Zustand"). Ziel der Verbesserungen: **vorhersehbares, erklärbares Retrieval-Verhalten**.

## Ist-Zustand: Pipeline

```text
PrjxpMcpTool.vectorSearch (mcp-server)
  → GRPromptEnrichment.enrich: do { findRelevant(minScore); alle Retriever; } while (Kontext leer)
  → ChromaDBPxChunkDao.findRelevant: top-N aus Chroma, dann Client-Cutoff score ≥ minScore
  → JavaPromptSession: Parent-Kette → Chunk-Bäume, rootRank pro Baum
  → buildPrompt: Bäume nach rootRank sortiert, Rank-0-Veto (break), 50k-Budget (break)
  → JavaPromptModifier: „## Hier ein Rumpf" + Skeleton, getreffene Methods → Body-Splice
```

Relevante Dateien:

| Baustein | Datei |
|---|---|
| MCP-Tool, Parameter-Clamping | `mcp-server/src/main/java/de/spraener/prjxp/mcp/PrjxpMcpTool.java` |
| Enrichment-Loop, Re-Iteration | `golden-retriever/src/main/java/de/spraener/prjxp/gldrtrvr/enrichment/GRPromptEnrichment.java` |
| Suchparameter | `golden-retriever/src/main/java/de/spraener/prjxp/gldrtrvr/enrichment/SearchParams.java` |
| Chroma-Query (top-N + Cutoff) | `golden-retriever/src/main/java/de/spraener/prjxp/gldrtrvr/chunks/ChromaDBPxChunkDao.java` |
| Score-Verwurf | `golden-retriever/src/main/java/de/spraener/prjxp/gldrtrvr/chunks/TextSegment2PxChunkConverter.java` |
| Typ-basiertes Ranking | `golden-retriever/src/main/java/de/spraener/prjxp/gldrtrvr/code/java/JavaChunkRanker.java` |
| Baum-Aufbau, Veto, Budget | `golden-retriever/src/main/java/de/spraener/prjxp/gldrtrvr/code/java/JavaPromptSession.java` |
| Rendering, skeletonsOnly | `golden-retriever/src/main/java/de/spraener/prjxp/gldrtrvr/code/java/JavaPromptModifier.java` |

## Befunde

1. **`distance` ist eine Similarity-Schwelle, kein Radius.** `score = 1 − cosDist/2`; höherer Wert = strenger Cutoff = weniger Treffer. Der Parametername und die Tool-Doku („Distance in vector space") sind irreführend – sie waren der Auslöser für die Fehldiagnose „Bucketing-Bug".
2. **Re-Iteration-Treppe.** Ist der assemblierte Kontext leer, senkt `reIterate` den `minScore` in 0.05-Schritten (Abort < 0.5). Der *effektive* Schwellwert ist damit `distance − 0.05·k` und landet je nach Request in verschiedenen Score-Bändern → erklärt den Sprung 1→12 Klassen.
3. **Similarity-Score wird nach dem Cutoff verworfen.** `TextSegment2PxChunkConverter.convert(EmbeddingMatch)` nutzt nur `match.embedded()`, nie `match.score()`. Die Assemblierung ist relevanzblind: ein 0.86- und ein 0.99-Treffer werden identisch behandelt.
4. **Typ-basiertes Ranking.** `JavaChunkRanker`: METHOD=5, CLAZZ_FRAME=2, IMPORTS=1, DEPENDENCIES_INFO=0. `rootRank` akkumuliert pro Baum; keine Score-Komponente.
5. **Rank-0-Veto + leere Kontexte.** `if (rootRank == 0) break` in `buildPrompt`. Deps-only-Treffer (z. B. nur ein `.dependencies`-Chunk getroffen) erzeugen einen leeren Kontext → triggern die Treppe aus Befund 2.
6. **`skeletonsOnly`-Bug.** In `JavaPromptModifier` fällt der `METHOD`-Case bei `skeletonsOnly=true` in den `DEPENDENCIE_INFO`-Case durch und hängt den *rohen Vollkörper* an. Das Flag entfernt also keine Bodies – es macht sie nur unsortiert.

## Verbesserungen (priorisiert)

### P1 – Similarity-Score erhalten und für das Ranking nutzen

- **Problem:** Befund 3+4 – die Assemblierung ordnet rein typ-basiert; der beste Treffer ist nicht erkennbar.
- **Änderung:** `score` als Feld in `PxChunk` (oder Wrapper `SearchHit`) ergänzen; `TextSegment2PxChunkConverter` kopiert `match.score()`; `JavaPromptSession.rank` akkumuliert `rootRank += typeWeight * score` (statt nur `typeWeight`).
- **Effekt:** Ranking spiegelt echte Relevanz; Budget-Allokation (P5) wird sinnvoll; Grundlage für P2.
- **Aufwand:** klein (1 Feld + 2 Zeilen + Ranker-Anpassung).

### P2 – Fallback-Treppe transparent und konfigurierbar machen

- **Problem:** Befund 2 – der effektive Schwellwert ist für den Aufrufer unsichtbar; das Output-Muster wirkt willkürlich nicht-monoton.
- **Änderung (eine oder mehrere Optionen):**
  - a) Effektiven Schwellwert im Response-Header melden: `Effektive Similarity-Schwelle: 0.83 (2 Fallbacks)`.
  - b) Treppe konfigurierbar: Schrittweite, maximale Tiefe, oder deaktivierbar (dann saubere „keine Treffer"-Antwort statt stillem Fallback).
  - c) Langfristig: harten Cutoff durch Top-N + weichen Boden ersetzen (z. B. immer Treffer ≥ 0.5, sortiert nach Score).
- **Status (2026-09-22): a) + Grid-Snap umgesetzt.** 1. Versuch = Request-Wert; jeder Fallback snappt auf den höchsten Punkt des kanonischen 0,05-Rasters *darunter* (`Math.floor((minScore − ε) / 0.05) × 0.05`, Epsilon gegen Float-Drift/Endlosschleife). Gemessen: alle Requests ≥ 0,88 landen auf 0,85 → byte-identischer Output; der 27-Klassen-Mega-Dump ist aus diesem Bereich nicht mehr erreichbar. Verbleibender Rest-Sägezahn (akzeptiert): direkter Treffer im schmalen Band 0,86/0,87 → 1 Klasse vs. Fallback-Landung ≥ 0,88 → 2 Klassen.
- **Effekt:** Monotones, erklärbares Verhalten; der `distance`-Parameter wird wieder vertrauenswürdig.
- **Aufwand:** klein–mittel.

### P3 – `skeletonsOnly`-Bug fixen

- **Problem:** Befund 6 – Fall-through hängt rohe Vollkörper an; das Flag tut nicht, was sein Name verspricht.
- **Änderung:** Im `METHOD`-Case bei `skeletonsOnly=true` explizit nur die Signatur rendern und `break`; alternativ Flag in `includeBodies` umbenennen und Semantik drehen.
- **Effekt:** Vorhersehbares Rendering; kleinere Outputs für Skeleton-Anfragen.
- **Aufwand:** trivial.

### P4 – Rank-0-Behandlung: `break` → `continue`, Deps-only-Treffer bewusst entscheiden

- **Problem:** Befund 5 – `break` ist bei absteigendem Sortier zwar aktuell äquivalent zu `continue`, aber fragil (ändert sich mit P1). Deps-only-Treffer erzeugen leere Kontexte und triggern die Treppe (Beitrag zur Nicht-Monotonie).
- **Änderung:** `continue` für Rank-0; bewusst entscheiden, ob Deps-only-Bäume als *letzter Ausweichfall* mitgerendert werden (sie sind für „Was hängt von X ab?"-Queries nützlich).
- **Effekt:** Weniger Fallback-Runden; stabileres Verhalten bei Score-Grenztreffern.
- **Aufwand:** trivial.

### P5 – Content-Budget externalisieren und sanft degradieren

- **Problem:** `maxContentLength = 50000` ist hartkodiert; der harte `break` wirft alle niedriger gerankten Bäume weg, ohne Signal an den Nutzer.
- **Änderung:** Config-Key (z. B. `prjxp.gldrtrvr.maxcontentlength`); bei Überschreitung Hinweis im Output: `[weitere N Klassen wegen Größenlimit nicht enthalten]`.
- **Effekt:** Tuning ohne Code-Change; Nutzer sieht, dass Output gekürzt wurde.
- **Aufwand:** klein.

### P6 – Top-N-Fenster vergrößern / konfigurierbar machen

- **Problem:** `ChromaDBPxChunkDao` holt top-N (MCP: 20) und filtert client-seitig per Cutoff. Liegen mehr als N Treffer über der Schwelle, gehen sie **still** verloren.
- **Änderung:** Fenster konfigurierbar (z. B. Default 50); Chroma liefert das günstig.
- **Effekt:** Kein stiller Verlust bei vielen relevanten Chunks (z. B. nach dem embeddingPrefix-Experiment, das mehr Chunks in die Score-Bänder bringt).
- **Aufwand:** trivial.

### P7 – Validierung pro Retriever statt nur gesamt (optional)

- **Problem:** Alle vier Retriever (Java, TypeScript, VisualBasic, Markdown) laufen auf denselben Chunks; der Validator prüft nur die Gesamtlänge. Ein schwacher Treffer in einer Nebensprache macht den Kontext „valide" und stoppt die Treppe früh – Java-Treffer, die bei niedrigerer Schwelle erschienen wären, bleiben weg.
- **Änderung:** Validator pro Retriever anwenden (oder Mindestqualität des Primär-Retriervers verlangen).
- **Effekt:** Fallback läuft, bis der relevante Retriever liefert.
- **Aufwand:** mittel.

### P8 – Abort-Nachricht informativer machen

- **Problem:** „Es konnte kein valider Kontext erstellt werden!" gibt keinen Hinweis, warum.
- **Änderung:** Effektiven Schwellwert und Anzahl der Fallback-Runden in die Nachricht aufnehmen.
- **Aufwand:** trivial.

### P9 – Gesamt-Budget über alle Retriever (nach Umsetzung von P1–P6 neu entdeckt)

- **Problem:** Jede Session (Java, TypeScript, VisualBasic, Markdown) hat ein eigenes Budget (`prjxp.gldrtrvr.maxcontentlength`, Default 50k); `GRPromptEnrichment` concatentiert die Retriever-Outputs **ohne Gesamtcap**. Gemessen (2026-09-22, cgv19): 76.023 Chars (≈19k Tokens) bei effektiver Schwelle 0,83 – keine einzelne Session sprengte ihr Limit, daher auch kein Kürzungshinweis.
- **Änderung:** Gesamtbudget in `GRPromptEnrichment` (z. B. `prjxp.gldrtrvr.totalcontentlength: 60000`): bei Überschreitung weitere Retriever-Outputs nicht mehr anhängen und Kürzungshinweis ausgeben. Alternativ: Per-Session-Default senken (z. B. 25k).
- **Status (2026-09-22): umgesetzt.** `prjxp.gldrtrvr.totalcontentlength` (Default 60k): erster Retriever-Output geht immer rein, weitere nur bei Platz; sonst Kürzungshinweis `[weitere N Retriever-Outputs wegen Groessenlimit nicht enthalten]`. Unit-getestet (`GRPromptEnrichmentTest`).
- **Effekt:** Vorhersehbare Prompt-Größe für das LLM; Kürzung ist immer sichtbar.
- **Aufwand:** klein.

### P10 – Annotation-Duplikation in `JavaCodeChunker` (chunk-norris)

- **Problem:** In 43 % der Method-Chunks erscheint die erste Annotationszeile doppelt (AST-Rendering + Source-Range, die sie bereits enthält). **Im neu eingebetteten cgv19-Index weiterhin vorhanden** (Test 2026-09-22): 6 doppelte Zeilen im vectorSearch-Output (`@Input`, `@Inject`, `@TaskAction` in `CGV19GenerateTask`).
- **Änderung:** AST-Annotationen weglassen und nur die Source-Zeilen nehmen (bzw. identische aufeinanderfolgende Zeilen deduplizieren).
- **Effekt:** Sauberere Chunks, leicht bessere Embedding-Qualität; keine Auswirkung auf die Retrieval-Mechanik selbst.
- **Aufwand:** klein (chunk-norris, danach Re-Embedding nötig).

## Verwandt, aber außerhalb von golden-retriever

- **Annotation-Duplikation** – inzwischen als P10 in die Prioritätenliste aufgenommen.
- **`embeddingPrefix`** – das A/B-Experiment (Präfix `package ClassName` vor dem Embedding) hat Redundanz beseitigt und das Output-Volumen bei 0.85 um ~68 % gesenkt; kein weiterer Handlungsbedarf, aber die in P1/P2 beschriebenen Mechanismen wirken *zusätzlich* darauf.

## Verifikationsplan

Nach jeder Änderung den Distanz-Sweep wiederholen (MCP `vectorSearch`, Projekt `cgv19`):

- Query 1: „Fluent API für Transformationen in cgv19" bei distance 0.85–0.97 (Schritt 0.01)
- Query 2: „createMClass Consumer getParent" (konkrete Methoden, Regressionsschutz)
- Erwartet (mit Grid-Snap): alle Requests in derselben Score-Lücke landen auf demselben Gitterpunkt → identischer Output; direkte Treffer in schmalen Bändern dürfen abweichen (akzeptierter Rest-Sägezahn, z. B. 0,86/0,87 → 1 Klasse vs. ≥ 0,88 → 2).
