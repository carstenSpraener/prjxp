# VisualBasic Retriever Decisions

Dieses Dokument hält die gemeinsam getroffenen Entscheidungen für den ersten VisualBasic-Retriever in `golden-retriever` fest.

## Ziel

Für VisualBasic-Chunks aus `chunk-norris` soll `golden-retriever` Kontext wieder sinnvoll zusammensetzen können, analog zum vorhandenen `JavaRetriever`.

Der Retriever soll insbesondere:

- Multipart-Chunks wieder zusammenfügen.
- `classFrame`-Chunks als strukturellen Kontext nutzen.
- konkrete `method`-Chunks in den passenden Frame einsetzen.
- `methodDoc`-Chunks der Methode zuordnen.
- `imports` semantisch beim passenden Frame berücksichtigen.
- bei fehlenden Parents/Frames keine Information verlieren.

## Architekturentscheidung

Der VisualBasic-Retriever wird zunächst eigenständig implementiert.

Geplanter Pfad:

```text
golden-retriever/src/main/java/de/spraener/prjxp/gldrtrvr/code/visualbasic/VisualBasicRetriever.java
```

Begründung:

- Der bestehende Code ist sprachspezifisch strukturiert (`JavaRetriever`, `TypeScriptRetriever`, `MarkdownRetriever`).
- Eine gemeinsame Basisklasse wäre möglich, wäre aber ein zusätzlicher Refactoring-Schritt.
- Der erste Schritt soll minimal-invasiv sein und die VB-Funktionalität liefern.

## Spring-Registrierung

`VisualBasicRetriever` wird als normaler Spring-Service registriert:

```java
@Service
public class VisualBasicRetriever implements GoldenRetriever
```

Er wird dadurch automatisch von `GRPromptEnrichment` über `List<GoldenRetriever>` mitverwendet.

Der Retriever filtert intern auf:

```text
visualbasic_code_section
```

## PromptSession

Es wird eine eigene `VisualBasicPromptSession` angelegt.

Geplanter Pfad:

```text
golden-retriever/src/main/java/de/spraener/prjxp/gldrtrvr/code/visualbasic/VisualBasicPromptSession.java
```

Sie basiert pragmatisch auf `TypeScriptPromptSession`, nutzt aber den VB-Metadata-Key:

```text
visualbasic_code_section
```

Eine generische PromptSession wird zunächst nicht eingeführt.

## VisualBasic Sections

Der Retriever stützt sich auf die bestehenden Sections aus `VisualBasicCodeSection`:

- `imports`
- `methodDoc`
- `method`
- `classFrame`

## Section-Verhalten

### `classFrame`

Ein `classFrame` wird als VisualBasic-Rumpf in den Prompt aufgenommen:

```markdown
## Hier ein Rumpf des VisualBasic-Typs <id>:

```vb
...
```
```

### `imports`

Imports sollen bevorzugt vor den passenden `classFrame` gesetzt werden.

Ziel:

- Imports gehören semantisch zum Typ-/Dateikontext.
- Sie sollen nicht als beliebiger separater Kontext am Ende landen, wenn ein passender Frame existiert.
- Wenn kein passender Frame auffindbar ist, dürfen Imports als separater VB-Codeblock ausgegeben werden.

### `method`

Ein Method-Chunk ersetzt die passende Methodensignatur im Frame durch den vollständigen Method-Content.

Wichtig: Die Zuordnung erfolgt nicht über den Methodennamen aus der ID.

Stattdessen:

1. Erste VB-Signaturzeile aus dem Method-Chunk extrahieren.
2. Diese Signatur im Prompt suchen.
3. Erst exakt suchen.
4. Danach whitespace-normalisiert suchen.
5. Wenn gefunden: genau dieses erste Vorkommen ersetzen.
6. Wenn nicht gefunden: Method-Chunk separat als VB-Codeblock anhängen.

Begründung:

- VB unterstützt Overloads.
- IDs können `.overloadN` enthalten.
- Eine Namenssuche würde bei Overloads nicht eindeutig sein.
- Die Signatur ist deutlich robuster.

### `methodDoc`

VB-Dokumentationschunks verwenden die Endung:

```text
.doc
```

Beispiel:

```text
BestellerFunctions.BestellerFunctions.SetBestellerTextZulässig.overload2
BestellerFunctions.BestellerFunctions.SetBestellerTextZulässig.overload2.doc
```

Verhalten:

- Zu einem `method`-Chunk sucht der Retriever optional `methodId + ".doc"`.
- Der Doc-Inhalt wird vor die passende Methodensignatur eingefügt.
- Falls Zuordnung oder Signatursuche nicht gelingt, wird der Doc-Chunk separat ausgegeben.

## Overloads

Overload-IDs aus dem Chunker werden beibehalten.

Beispiele:

```text
SetBestellerTextZulässig.overload1
SetBestellerTextZulässig.overload2
SetBestellerTextZulässig.overload3
```

Beim Einsetzen in den Frame wird aber nicht nach `.overloadN` gesucht, sondern nach der echten Signaturzeile aus dem Method-Chunk.

## Multipart-Chunks

Multipart-Chunks werden über `PxChunk.combine(...)` wieder zusammengesetzt.

Wenn ein Treffer nur einen Teil enthält, soll der Retriever versuchen, alle Parts über `PxChunkDao.findById(id)` zu laden und zu kombinieren.

## Fehlende Parents oder Frames

Fehlende Parent-/Frame-Chunks sind nicht fatal.

Verhalten:

- Wenn ein Frame vorhanden ist, wird die Methode dort eingefügt.
- Wenn kein Frame vorhanden ist, wird die Methode separat als VB-Codeblock ausgegeben.
- Wenn eine Dokumentation nicht zugeordnet werden kann, wird sie ebenfalls separat ausgegeben.

Begründung:

Legacy-VB-Code und heuristisches Chunking sind nicht perfekt. Für RAG ist ein isolierter Method-Chunk besser als gar kein Kontext.

## Teststrategie

Die erste Implementierung soll Option B abdecken: mehrere Kernfälle mit In-Memory-Testdaten.

Geplante Tests:

1. Signatur-Ersetzung
   - `classFrame` enthält Signatur.
   - `method` ersetzt genau diese Signatur durch vollständigen Method-Content.

2. `.doc` vor Methode
   - `methodId + ".doc"` wird gefunden.
   - Doc-Inhalt steht vor der eingefügten Methode.

3. Multipart-Combine über DAO
   - Ein Method-Chunk mit `total > vorhandene Trefferliste` wird über DAO vollständig geladen und kombiniert.

4. Fallback ohne Parent/Frame
   - Method-Chunk ohne passenden Frame geht nicht verloren.
   - Er erscheint separat im Prompt.

5. Imports vor Frame
   - Passender `imports`-Chunk wird vor dem zugehörigen `classFrame` ausgegeben.
   - Wenn kein Frame vorhanden ist, darf Imports separat erscheinen.

## Nicht-Ziele der ersten Umsetzung

- Keine generische Retriever-Basisklasse.
- Keine Refaktorierung von Java-/TypeScript-Retriever.
- Keine Änderung am VB-Chunker.
- Keine Änderung an `GRPromptEnrichment`.
- Keine globale Neuordnung der Retriever-Priorisierung.

## Offene spätere Verbesserungen

- Gemeinsame generische PromptSession für Java/TypeScript/VB.
- Gemeinsame Chunk-Combine-Logik für sprachspezifische Retriever.
- Stabilere Parent-/Frame-Zuordnung für Partial Classes und Designer-Dateien.
- Bessere Reihenfolge/Priorisierung bei mehreren Retriever-Ausgaben.
