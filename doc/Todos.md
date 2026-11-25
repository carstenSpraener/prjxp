# Todos

Entscheidungen, die bewusst **nicht** Teil der aktuellen MultiProject-Phasen sind — als Folge-Features eingeplant.

---

## 1. Docker-Hub: Projekt-Import (Tar-Upload / Import-Verzeichnis)

**Entscheidung:** Ein langlaufender Docker-Deployment (Hub) bedient mehrere Projekte; neue Projekte werden zur Laufzeit hinzugefügt, indem ein Tar-Archiv in ein **Import-Verzeichnis** gelegt wird (`/import/foo.tar` → Watcher entpackt nach `/projects/foo`, registriert, triggert Chunking + Embedding). Ein REST-Upload-Endpoint (`POST /prjxp/projects`) kommt später als dünner Writer auf dasselbe Verzeichnis — die Pipeline ist identisch.

**Voraussetzung:** MultiProject-Phasen 01–04 (`docs/Tasks/MultiProject/`) — Store-Separation, `ProjectRegistry`, CLI-Fixes.

**Offene Lücken (Feature-Phasen, wenn es losgeht):**
- **Dynamische Projekt-Registrierung:** `PrjXPConfig` ist statisches `@ConfigurationProperties`. → Pro Projekt eine kleine Config-Datei (z.B. `/projects/foo/prjxp.yaml`), beim Import geparst; `ProjectRegistry` wird vom Config-Reader zum Verzeichnis-Watcher mit Hot-Reload.
- **Pipeline-Orchestrierung:** chunk-norris/tibed als Libraries in die Hub-App ziehen (empfohlen — s. Lucene-NRTC unten) oder als Kind-Prozesse spawnen.
- **Lifecycle:** Projekt löschen (scoped `removeAll` aus Phase 01), Re-Import = scoped Reset + Re-Embed, Disk-Cleanup, Queue bei parallelen Imports.
- **Security:** Zip-Slip-Schutz beim Tar-Entpacken (keine `..`/absolute Paths), Size-Limits.

---

## 2. Lucene als Kernstore (alternative Stores ausrangiert) — ✅ erledigt

**Entscheidung:** Lucene ist der einzige Store; die übrigen Store-Zweige wurden entfernt (LuceneOnlyStorage, Phasen 01–05).

**Gewinn:**
- Alle 4 Suchebenen arbeiten uniform: `searchFullText`/`searchByIndex` existieren nur im Lucene-DAO — der alte Vektor-DB-Store unterstützte sie nicht (`grep`/`byIndex` lieferten still leer, 2 von 4 Tools nutzlos).
- Docker-Betrieb ohne externen Store-Service (Index = Verzeichnis auf dem Volume); Hub braucht kein Store-Provisioning.

**Offene Lücken:**
- **Read-only-Modus in `LuceneEmbeddingStore`:** heute öffnet `ensureOpen()` immer einen `IndexWriter` (1 Writer pro Index-Verzeichnis, Stale-Lock-Hack für sequenzielle Läufe). Für cross-Prozess-Betrieb (Server liest, Pipeline schreibt) und für Read-Skalierung (N Replikas + 1 Writer) braucht der Store einen `DirectoryReader.openIfChanged`-Pfad ohne Writer.
  - Innerhalb eines Prozesses ist NRTC bereits gegeben (`SearcherManager(writer, null)`) — für die in-Prozess-Pipeline des Hub-Features genügt der Status quo.
- **Bewusste Grenze:** kein Multi-Writer — mehrere schreibende Replikas sind mit Lucene nicht möglich (Read-Replikas ja, s.o.).
- **Aufräumen (erledigt):** `EmbeddingStoreSupplier` auf Lucene-only geslimgt; der alte Vektor-DB-DAO und seine Config wurden entfernt.
- **Migration:** keine — bestehende Index-Daten des alten Stores verfallen; Re-Import über die Hub-Pipeline.

---

## 3. `readBySignature`: Klassen-FQN-Lookup schlägt fehl — **Known Bug** in `v0.8.0-rc1`, **gefixt** in `0.8.1`

**Problem:** `SymbolReaderService.readBySignature` filtert nur bei `method.contains("#")` auf `SYMBOL_FQN`, sonst auf `SYMBOL_NAME`. Ein **Klassen-FQN ohne `#`** (z. B. `de.spraener.prjxp.common.language.LanguagePackPluginLoader`) landet daher im `SYMBOL_NAME`-Filter — aber der Symbol-Name einer Klasse ist nur der Kurzname (`LanguagePackPluginLoader`), nicht der volle FQN. Ergebnis: `{"matches":[], "error":"No symbol matching … found"}`.

**Dokumentiertes Verhalten:** Tool-Beschreibung + AGENTS.md versprechen *„Class FQNs (e.g. 'com.example.Foo') return the class frame."* — stimmt so nicht.

**Reproduktion (MCP, Projekt `prjxp`):**

| Eingabe | Ergebnis |
|---|---|
| `loadAll` (Bare Name) | ✅ 1 Match |
| `de.spraener…LanguagePackPluginLoader#loadAll` (FQN#Methode) | ✅ 1 Match |
| `de.spraener…LanguagePackPluginLoader` (Klassen-FQN) | ❌ 0 Matches |

**Fix (1 Zeile):** `if (method.contains("#") \|\| method.contains("."))` → FQNs (mit Punkt) werden korrekt per `SYMBOL_FQN` gesucht. `SymbolMetadata.applyClass` setzt `SYMBOL_FQN = fqn` (Klasse, ohne `#`), `applyMethod` setzt `containerFqn + "#" + methodName` — der FQN-Filter trifft also beide.

**Status:** **Gefixt** in `0.8.1` (war Known Bug in `v0.8.0-rc1`): `SymbolReaderService` filtert FQNs (mit Punkt) jetzt per `SYMBOL_FQN` — der dokumentierte Klassen-FQN-Lookup funktioniert. Der Workaround (FQN#Methode / Bare-Name) bleibt gültig.

---

## 4. Leerer `projectName` wirft im Hub-Modus einen Fehler (`'cwd'`-Quirk) — **Known Bug** in `v0.8.0-rc1`, **gefixt** in `0.8.1`

**Problem:** Alle Such-Tools (`vectorSearch`, `grep`, `readFile`, `readBySignature`) dokumentieren `projectName`/`project` als optional (*„Leave empty/default if unknown"*). Im **Hub-Modus** wirft ein leerer `projectName` aber einen Fehler: `ERROR: No searchable store for project 'cwd'. Available projects: …`. Man **muss** also immer einen Projektnamen übergeben — das dokumentierte „leer lassen" funktioniert im Hub-Modus nicht.

**Root Cause:** `PrjXPConfig.activeProject` defaultet auf `"cwd"` (legacy Single-Project-Fallback, rootDir `.`). Im Hub-Modus ist die statische `projects`-Liste leer (Projekte werden dynamisch von `HubProjectRegistry` entdeckt) → `getActiveProject()` liefert den synthetischen `"cwd"`-Fallback → `resolve(null)` = `"cwd"` → aber `"cwd"` ist kein registriertes Hub-Projekt → `ensureSearchable` wirft `UnknownProjectException`.

**Reproduktion (Hub, 7 Projekte):** `vectorSearch` ohne `projectName` → `ERROR: No searchable store for project 'cwd'`. Mit `projectName="basstard"` → funktioniert.

**Workaround:** Immer einen Projektnamen übergeben (gültige Namen via `listProjects`).

**Status:** **Gefixt** in `0.8.1` (war Known Bug in `v0.8.0-rc1`): `HubProjectRegistry.resolve()` lässt `null`/blank/`"default"` auf das konfigurierte aktive Projekt (falls registriertes Hub-Projekt) bzw. das erste `READY`-Projekt (sortiert) fallen; ohne `READY`-Projekt bleibt `"default"` und `ensureSearchable` wirft den klaren Fehler mit Projektliste. Der synthetische `"cwd"`-Fallback kann nicht mehr durchrutschen.

---

## 5. Whitelistede Datei ohne Chunker wird still verworfen — **Known Bug** in `v0.8.0-rc1`, **gefixt** in `0.8.1`

**Problem:** Eine Datei, die in der `chunoWhiteList` steht, für die aber **kein Chunker** `matches()` (kein passender `PxFileType`/`@Chunker`), wird **still verworfen** — ohne Log, ohne Fallback. Sie taucht weder im Index noch in der Statistik auf.

**Mechanismus:**
1. Whitelist-Veto (`StandardVetos.notListedInWhiteList`) läuft upstream in `ChunkProcess` — nicht gelistet → raus.
2. `FullTextChunker` (`matches()` = immer `true`, Catch-All) ist **pro Projekt per `fulltext`-Flag gated** — ohne Flag inaktiv.
3. → Whitelisted + kein spezifischer Chunker + kein `fulltext`-Flag = **verworfen**.

**Reproduktion:** `godot-docs` (1634 `.rst`-Dateien, `chunoWhiteList: md,rts` [Typo für `rst`], kein `fulltext`-Flag) → nur 8 `.md`-Dateien gechunkt (676 Chunks), alle `.rst`-Dokumente fehlen im Index → `vectorSearch` liefert „kein valider Kontext".

**Geplanter Fix:** `DefaultLineBreakTextChunker` — Fallback in `ChunkerFactory.createChunker(f)` für whitelistete Dateien ohne spezifischen Chunker: line-based Split (an `\n`), Text/Binary-Safeguard (Null-Byte-Sniff wie `FullTextChunker`). **Offen:** Retriever-Design (Marker/MIME, ob in `vectorSearch`/`grep` enthalten — aktuell werden `FULLTEXT_MARKER`-Chunks dort explizit ausgeschlossen).

**Status:** **Gefixt** in `0.8.1` (war Known Bug in `v0.8.0-rc1`): `DefaultLineBreakTextChunker` als Fallback in `ChunkerFactory` (line-based Split, Text/Binary-Safeguard) + `TextRetriever` in golden-retriever. Retriever-Design-Entscheidung: die Fallback-Chunks tragen `TEXT_MARKER` (searchbar via `vectorSearch`/`grep`), im Gegensatz zu `FULLTEXT_MARKER` (lossless `readFile`-Kopie, aus der Suche ausgeschlossen).

---

## 6. Embedded Chunks nicht im Index auffindbar (asynchroner Lucene-Refresh) — **Known Bug** in `v0.8.0-rc1`, **gefixt** in `0.8.1`

**Problem:** Chunks, die in den Lucene-Store geschrieben werden (`add`/`addAll`/`addAllBatched`/`removeAll`/`commit` — u. a. die DocPipe-Dokument-Embeddings), sind **nicht sofort auffindbar**: weder per `vectorSearch`/`grep`/`listFiles` noch per `countByProject`. Sie liegen auf der Platte (committet, in den Live-Segmenten), aber der laufende `SearcherManager`-Reader sieht sie nicht. Im Log steht „Embedded batch of N chunks", im Index ist nichts.

**Root Cause:** `LuceneEmbeddingStore` rief nach jedem Commit `searcherManager.maybeRefresh()` auf. `maybeRefresh()` ist **asynchron** (nicht blockierend): Es triggert einen Background-Refresh und gibt sofort zurück — der Reader wird erst aktualisiert, wenn der Background-Refresh **abgeschlossen** ist. In der Praxis (Hub, mehrere Projekte, parallele Pipeline-Läufe) holte der Reader die neuen Segmente nicht timely → „embedded but not findable".

**Beweiskette (cgVipster, Hub, Live-Index):**

| Fakt | Wert |
|---|---|
| DocPipe-Embedding erzeugt Segmente | `_8g`, `_bn`, `_bm`, `_9v`, … (mtime 04:35:10) |
| `segments_N` nach Embedding | `segments_ar` (mtime 04:35:10) — Commit **auf der Platte** |
| `countByProject("cgVipster")` (nutzt `searcherManager.acquire()`) | **9062** (nicht 9172) — Reader sieht die 110 neuen Chunks **nicht** |
| Zweite Store-Instanz? | Nein — ein geteilter `LuceneEmbeddingStore`-Bean (DocPipe, MCP-Tools, `countByProject` alle derselbe) |

**Fix:** `maybeRefresh()` → `maybeRefreshBlocking()` an allen 4 Write-Pfaden (`addInternal`, `addAllBatched`, `removeAll`, `commit`). `maybeRefreshBlocking()` blockiert, bis der Refresh abgeschlossen ist → der Reader ist garantiert aktuell, sobald der Write-Aufruf zurückkehrt. (Lucene 9.12 API. Der `SearcherManager` ist bereits an den `IndexWriter` gekoppelt — NRT-Modus; `acquire()` triggert selbst keinen Refresh.)

**Auswirkung:** Erklärt, warum die DocPipe-generierten Dokumente (z. B. `doc/Overview.md` in cgVipster) im Log als „embedded" auftauchten, aber nicht im Index auffindbar waren — und warum auch ein Hub-Restart sie nicht zuverlässig sichtbar machte.

**Status:** **Gefixt** in `0.8.1` (war Known Bug in `v0.8.0-rc1`).
