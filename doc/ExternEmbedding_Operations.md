# Externes Embedding – Betriebsleitfaden

Operativer Ablauf für den Offline-Workflow: Chunks auf Maschine A erzeugen, Embeddings
auf Maschine B berechnen, Vektoren zurück auf Maschine A importieren.

Konzept: [doc/Tasks/ExternEmbedding](Tasks/ExternEmbedding/00-Overview.md) ·
Automatisiert durch: `TransferPipelineE2ETest` (tibed, 5 Matrixfälle)

Build der JARs: `./gradlew :chunk-norris:shadowJar :tibed:shadowJar`
→ `chunk-norris-all.jar`, `tibed-all.jar`.

## Rollen der Maschinen

| Maschine | Rolle | Läuft |
|---|---|---|
| A (langsam, Zielstore) | Chunking + Import | `chunk-norris`, `tibed --mode import` |
| B (schnell, Embedding-Rechner) | Embedding-Berechnung | `tibed --mode export` |

Transferdateien:

| Datei | Erzeugt von | Format |
|---|---|---|
| `px-chunks.jsonl` | chunk-norris (A) | JSONL, ein `PxChunk` pro Zeile – optional verschlüsselt |
| `embedding.jsonl` | tibed export (B) | JSONL, ein `EmbeddedChunkRecord` pro Zeile (inkl. `vector`) – optional verschlüsselt |

Verschlüsselte Dateien erkennen am Magic-Header `PRJXPENC` (AES-256-GCM, PBKDF2).

## Setup: Passwort

Standardmäßig wird die Umgebungsvariable `PRJXP_TRANSFER_PASSWORD` gelesen
(über `.env` im Projektroot oder echte Umgebungsvariable).

```bash
# .env auf Maschine A und B (gleicher Wert!)
PRJXP_TRANSFER_PASSWORD=<geheimes-passwort>
```

Andere Variable nutzen: `--password-env MEINE_VAR` (CLI) oder
`prjxp.transfer.password-env=MEINE_VAR` (Config/Env).

Verschlüsselungs-Policy:

| Einstellung | Verhalten |
|---|---|
| `auto` (Default) | Passwort vorhanden → verschlüsseln, sonst Klartext |
| `--encrypt` | Immer verschlüsseln; ohne Passwort wird eines generiert und ausgegeben |
| `--no-encrypt` | Immer Klartext, auch wenn ein Passwort vorhanden ist |

## Ablauf 1: Verschlüsselt mit bekanntem Passwort (empfohlen)

### Maschine A – Chunks erzeugen

```bash
java -jar chunk-norris-all.jar --project meinprojekt
# -> px-chunks.jsonl (verschlüsselt, da PRJXP_TRANSFER_PASSWORD gesetzt)
```

Datei zu Maschine B übertragen (z. B. `scp`, USB, …).

### Maschine B – Embeddings berechnen

```bash
# Gleiche .env mit PRJXP_TRANSFER_PASSWORD wie auf A
java -jar tibed-all.jar --mode export \
    --input px-chunks.jsonl \
    --output embedding.jsonl.enc
```

Ergebnis zurück zu Maschine A übertragen.

### Maschine A – Import in den Store

```bash
java -jar tibed-all.jar --mode import \
    --input embedding.jsonl.enc
```

Der Store ist danach für Retrieval verfügbar. Wiederholter Import erzeugt keine
Duplikate (Dedup über Chunk-ID).

## Ablauf 2: Klartext (vertrauensvolle Umgebung)

Passwort nicht setzen und `--no-encrypt` verwenden, um Klartext zu erzwingen:

```bash
# A
java -jar chunk-norris-all.jar --project meinprojekt --no-encrypt
# B
java -jar tibed-all.jar --mode export --input px-chunks.jsonl --output embedding.jsonl
# A
java -jar tibed-all.jar --mode import --input embedding.jsonl
```

## Ablauf 3: Passwort automatisch generieren lassen

Ohne gesetztes Passwort `--encrypt` verwenden – chunk-norris generiert ein
Passwort und gibt es **zweimal** aus (nach Generierung und am Ende des Laufs):

```
[SECURITY] Generated transfer password:
<PASSWORD>
[SECURITY] Store this password safely. It will be required for export/import.
...
[SECURITY] REPEAT transfer password:
<PASSWORD>
```

Das Passwort sicher auf Maschine B hinterlegen (`.env`), dann wie in Ablauf 1
weitermachen.

## Troubleshooting

| Symptom | Ursache / Lösung |
|---|---|
| `Entschlüsselung fehlgeschlagen (Auth-Tag)` | Falsches Passwort. Gleichen `PRJXP_TRANSFER_PASSWORD` auf beiden Maschinen prüfen. |
| `Eingabe ist verschlüsselt, aber kein Passwort verfügbar` | Datei hat `PRJXPENC`-Header, aber kein Passwort gesetzt. `.env` ergänzen oder `--password-env <VAR>`. |
| `Kein PRJXP-Encrypted-Stream: Magic-Bytes nicht gefunden` | Datei ist Klartext, aber eine explizite Entschlüsselung wurde verlangt. Im normalen Transfer-Flow passiert dies nicht (`openAuto` lässt Klartext transparent durch) – Datei-Zuordnung prüfen. |
| `Transfer mode 'export' requires an output file` | `--output <path\|->` fehlt (oder `prjxp.transfer.output`). |
| `Transfer mode 'import' requires an input file` | `--input <path\|->` fehlt (oder `prjxp.transfer.input`). |
| Import importiert nichts, aber kein Fehler | Datei ist Klartext, enthält aber keine `vector`-Felder (z. B. Chunk- statt Embedding-Datei). |

Konfigurations-Schlüssel (Priorität: CLI > Umgebungsvariable/System-Property > `application.yaml`):

```
prjxp.transfer.password-env   (Default: PRJXP_TRANSFER_PASSWORD)
prjxp.transfer.encrypt        (auto | true | false, Default: auto)
prjxp.transfer.input          (Default: Projekt-jsonlFile bzw. stdin "-")
prjxp.transfer.output         (nur export)
prjxp.transfer.mode           (export | import | store, Default: store = altes Verhalten)
```

## Regressionsschutz

Die fünf Betriebs-Szenarien sind als End-to-End-Tests in
`tibed/src/test/java/de/spraener/prjxp/tibed/TransferPipelineE2ETest.java`
abgedeckt (Klartext, verschlüsselt mit env-Passwort, generiertes Passwort,
falsches Passwort, `--no-encrypt` trotz Passwort) und laufen mit
`./gradlew :tibed:test`.
