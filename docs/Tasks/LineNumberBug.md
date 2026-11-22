# Bug: Zeilenangaben aus `grep` und `readBySignature` stimmen nicht mit der Quelle überein

**Status:** offen
**Schwere:** hoch — blockiert jede evidenzbasierte Antwort („jede Aussage mit `Datei:Zeile` belegen")
**Betroffen:** Hub-Index (16-Tool-Fläche), Stand `926d4e8`; sichtbar in `prjxp-hub` mit Index-Manifest `82aa4159…`
**Betroffene Tools:** `grep` (falsche Zeile), `readBySignature` (Rumpfende abgeschnitten), indirekt `vectorSearch`/`readChunks`
**Nicht betroffen:** `readFile` (Offsets stimmen)

## Symptom

Treffer aus `grep` tragen eine Zeilennummer, die nicht der Zeile in der Datei entspricht. Der Fehler ist nicht konstant: er wächst mit der Chunk-Position in der Datei und ist pro Datei unterschiedlich.

Reproduktion gegen den laufenden Hub (`legacy`):

| Werkzeugaufruf | Hub sagt | Datei tatsächlich |
|---|---|---|
| `grep("public abstract class BasicServiceCBImpl")` | `BasicServiceCBImpl.java:67` | `:33` (+34) |
| `grep("class TeilvorgangDaoCBImpl")` | `TeilvorgangDaoCBImpl.java:73` | `:37` (+36) |
| `grep("class TeilvorgangServiceCBImpl")` | `TeilvorgangServiceCBImpl.java:127` | `:62` (+65) |
| `grep("public class PrjxpMcpTool")` | `PrjxpMcpTool.java:50` | `:26` (+24) |
| `grep("Leave at 0.85")` | `PrjxpMcpTool.java:50` | `:53` (−3) |

Die beiden letzten Zeilen zeigen denselben Effekt innerhalb **einer** Datei: einmal +24, einmal −3. Ein reiner Off-by-one ist es also nicht.

Die Dateien sind unverändert im Worktree vorhanden; `readFile(offset=…)` liefert an derselben Stelle den erwarteten Inhalt. Nur die aus dem Index abgeleiteten Zeilen sind falsch.

`readBySignature` liefert dagegen brauchbare Startzeilen, aber zu kurze Rümpfe:

| Aufruf | Hub | tatsächlich |
|---|---|---|
| `BbpImportFassadeImpl#createBaumassnahme` | `368–391` | `372–496` |
| `BbpImportFassadeImpl#addBBPsToBaumassnahme` | `303–327` | `305–345` |
| `BbpImportFassadeImpl#updateBaumassnahme` | `257–269` | Overload, echter Pfad `792–835` |

## Auswirkung

- In den Benchmark-Läufen sind nur **40–60 %** der zitierten `Datei:Zeile`-Anker zeilengültig (P1 prjxp 49 %, P2 prjxp 80 %, P2 legacy 60 %); bei den Modellantworten, die `grep`-Zeilen übernehmen, sind es die schlechtesten Werte.
- Eine verschärfte Bewertung („Fakt zählt nur mit validem Anker") würde die Fläche für einen Tool-Bug bestrafen, nicht für fehlende Fähigkeit.
- Agenten, die dem Hub vertrauen, produzieren falsche Belege — der teuerste Fehlerfall für ein Retrieval-Produkt.

## Ursache (Analyse)

Die Zeilen werden beim Chunking gesetzt und beim Grep nur addiert.

1. **0-basierte Speicherung, 1-basierte Erwartung.**
   `JavaCodeChunker` rechnet `begin.line - 1` (0-basiert) und übergibt das an den Splitter:
   `chunk-norris/src/main/java/de/spraener/prjxp/chuno/code/java/JavaCodeChunker.java:283`
   `ContentSplitter` speichert den Wert unverändert:
   `prjxp-common/src/main/java/de/spraener/prjxp/common/util/ContentSplitter.java:38-39`
   `GrepSearchService` addiert den 0-basierten Zeilenindex `i`:
   `mcp-server/src/main/java/de/spraener/prjxp/mcp/GrepSearchService.java:133,146`
   → mindestens Off-by-one.

2. **Mehrchunk-Drift: Overlap wird beim Zeilenfortschritt ignoriert.**
   `ContentSplitter` zählt nur die Newlines des Chunks und addiert sie auf:
   `prjxp-common/src/main/java/de/spraener/prjxp/common/util/ContentSplitter.java:58-61`

   ```java
   int lineCount = StringUtils.countOccurrencesOf(chunkContent, "\n");
   chunk.setFromLine("" + chunkStartLine);
   chunk.setToLine("" + (chunkStartLine + lineCount));
   chunkStartLine += lineCount;
   ```

   Der nächste Chunk beginnt aber um `overlap` Zeichen **vor** dem Chunk-Ende (`chunkStart = chunkEnd - overlap`). Die überlappenden Zeilen werden trotzdem voll mitgezählt. Fehler pro Chunk ≈ Anzahl der Zeilen im Overlap, kumulativ. Das erklärt den wachsenden Versatz (+34, +36, +65) und warum derselbe String in derselben Datei unterschiedliche Fehler hat: er liegt in verschiedenen Chunks.
   Zusätzlich ist `toLine = fromLine + count("\n")` die letzte Zeile exklusiv, nicht inklusiv.

3. **Rumpfbereiche erben denselben Fehler.**
   `MethodView.lineTo` kommt aus `chunk.getToLine()`, also aus dem *Chunk*-Ende, nicht aus dem Methodenende. Dadurch wirken Methoden zu kurz (`createBaumassnahme` 368–391 statt bis 496).

## Fix-Vorschlag

**Empfohlen (robust): Zeilen aus dem absoluten Zeichenoffset ableiten, nicht aus Chunk-Fortschritt.**

1. Beim Chunking den absoluten Start-Offset des Chunks in der Datei mitführen. Beim Schreiben von `fromLine`/`toLine` einmalig einen Line-Index der Quelldatei aufbauen (`int[] lineStarts`) und Offset → 1-basierte Zeile mappen. Kein Addieren von `lineCount` mehr.
2. `overlap` darf die Zeilenrechnung nicht beeinflussen, weil der Offset die Wahrheit liefert.
3. `ContentSplitter` speichert `fromLine`/`toLine` **1-basiert und inklusiv**; `GrepSearchService` rechnet dann `line = fromLine + i` ohne weitere Addition.
4. `MethodView.lineFrom/lineTo` aus dem Symbolbereich (JavaParser-Range) setzen, nicht aus dem Chunk.
5. **Reindex erforderlich** — die falschen Zeilen stehen im Lucene-Index. Ein reiner Codefix ohne Reindex ändert nichts an bestehenden Projekten.

**Sofort-Workaround für Agenten:** `readFile`-Offsets sind korrekt; Anker aus `grep` vor der Verwendung gegen `readFile` prüfen. `readBySignature` nur als Einstiegspunkt nutzen, Rumpf per `readFile(offset=lineFrom, limit=…)` nachladen.

## Tests (rot zuerst)

1. `ContentSplitterTest`: Datei mit > 2 Chunks und Overlap > 0; erwartet wird für jeden Chunk die echte 1-basierte Startzeile (heute kumulativ verschoben).
2. `ContentSplitterTest`: `toLine` ist die letzte enthaltene Zeile (inklusiv).
3. `GrepSearchServiceTest`: ein Treffer in Zeile *n* einer mehrfach gechunkten Datei liefert `lineFrom == n`.
4. `SymbolReaderServiceTest`: Methodenbereich entspricht der Parser-Range (Start und Ende).
5. End-to-End: Hub gegen `legacy` indexieren, `grep("class TeilvorgangDaoCBImpl")` → `TeilvorgangDaoCBImpl.java:37`.

## Akzeptanz

- Stichprobe von 20 Grep-Treffern über beide Korpora: **100 %** zeilengültig (heute 40–80 %).
- `readBySignature`-Bereiche umfassen den vollständigen Rumpf.
- Benchmark-Neuauswertung mit Evidenz-Gate: Anker-Validierungsrate ≥ 95 % für alle Arme.
