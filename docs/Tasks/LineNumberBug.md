# Bug: Zeilenangaben aus `grep` und `readBySignature` stimmen nicht mit der Quelle überein

**Status:** behoben im Code (Reindex nötig)
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

## Umsetzung (Code)

- `ContentSplitter` leitet `fromLine`/`toLine` jetzt aus dem absoluten Zeichenoffset des
  Chunks ab (`lineOf(content, offset, firstLine)`) und speichert **1-basiert und inklusiv**.
  Overlap kann die Zeilennummern damit nicht mehr verschieben; der Eingabewert `fromLine`
  bleibt der 0-basierte Index der ersten Content-Zeile (Konvention aller Chunker).
- `ChunkRange.toCode()` liefert `[fromLine, toLine)` (kein Extra-Zeilen-Überlauf mehr).
- `PxChunk.combine()` übernimmt `toLine` des **letzten** Parts (Maximum), nicht des ersten —
  damit umfassen `readBySignature`-Rümpfe den vollständigen Methodenbereich.
- `GrepSearchService` bleibt bei `line = fromLine + i`, weil `fromLine` nun 1-basiert ist.
  Zusätzlich wird pro `(file, line)` nur ein Treffer ausgegeben (Overlap-Chunks liefern
  dieselbe Zeile doppelt); bei Fragmenten aus der Chunk-Mitte gewinnt der längere Snippet.
- Tests: `ContentSplitterTest` (Progression + Overlap-Drift), `ChunkRangeTest`,
  `ReaderSupportTest` (kombinierter Bereich), `GrepSearchServiceTest`
  (Mehrchunk-Datei liefert die absolute Zeile).

### Nachtrag nach Reindex: classFrame-Chunks

Der Reindex (`legacy` + `prjxp`) bestätigte den Offset-Fix für **zeilengetreue** Chunks
(Methoden, Imports, Strings im Rumpf: exakt). Offen blieben Klassen-Treffer, weil
`classFrame`-Chunks **synthetischen Text** enthalten (Package + Imports + Signatur-Skelett)
und trotzdem mit `fromLine + i` verankert wurden. Der Versatz entsprach genau dem
synthetischen Prolog (`+31/+34/+58`).

- `JavaCodeChunker.createClassFrameChunk` beginnt den Frame jetzt mit einem **verbatim
  kopierten Quelltext-Präfix** (bei Top-Level-Typen ab Dateizeile 1, damit Package/Imports
  erhalten bleiben; bei verschachtelten Typen ab der eigenen Deklaration), das bis zum Ende
  des Feldblocks reicht (max. 60 Zeilen, damit der Frame nicht aufgebläht wird). Erst danach
  folgt der synthetische Signaturblock.
- Der Chunk trägt `pxchunk_verbatim_lines` (`start-end`, 1-basiert inklusiv). `grep`
  akzeptiert nur Treffer innerhalb dieses Bereichs als `file:line`-Anker; synthetische
  Zeilen werden verworfen, statt falsche Anker zu liefern. Feld-Deklarationen liegen im
  verbatim-Präfix und bleiben damit adressierbar; Konstruktor-/Methodensignaturen im
  synthetischen Block bleiben über `readBySignature` bzw. die Methoden-Chunks erreichbar.

### Verifikation gegen den laufenden Hub

Der Testkorpus ist **absichtlich eingefroren** (kein Arbeitsstand!). Referenz für Anker sind
daher ausschließlich die eingefrorenen Bäume: `prjxp-bench/worktree-legacy` (`legacy`) und
`prjxp-bench/worktree`, Commit `2870c6a` (`prjxp`). Der Chunker-Fix ändert nur, wie der
laufende Code indiziert wird — nicht den Korpus-Inhalt.

Mit dem Stand nach dem zweiten Reindex: 37 von 37 `grep`-Ankern zeilengültig, u. a.
`BasicServiceCBImpl :33`, `TeilvorgangDaoCBImpl :37`, `TeilvorgangServiceCBImpl :62`,
`PrjxpMcpTool :26` (im eingefrorenen `prjxp`-Stand liegt die Klasse in Zeile 26).
`readBySignature`-Bereiche decken den Rumpf: `createBaumassnahme 369–487`,
`addBBPsToBaumassnahme 304–350`, `updateBaumassnahme 258–269`.

Nachtrag `prjxp`: 28 von 28 Ankern gültig, inklusive Feldankern (z. B. `CHUNKER_NAME :47`)
— Feldblock-Ausweitung ist live.

Nachtrag Header-Heuristik: Die frühere Regel „Deklaration endet, sobald die Vorzeile `{`
enthält" brach bei Annotationen mit Array-Literal (`@Profile({…})`) ab; dadurch fehlte die
Klassendeklaration im Frame (z. B. `MastervorgangDaoCBImpl :34` war nicht auffindbar).
`classHeaderEndIndex` sucht die Deklaration jetzt über Typ-Keyword + Name und nimmt die
Zeile mit der öffnenden Klammer (mehrzeilige Deklarationen eingeschlossen).

**Offen:** Der laufende Index enthält ContentSplitter-, classFrame- und Feldblock-Fix; die
Dublettenbereinigung in `grep` (`(file,line)`-Dedupe) braucht nur einen Deploy, keinen
Reindex. Die Anker gelten gegen den eingefrorenen Testkorpus, nicht gegen das
Arbeitsverzeichnis.
