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
