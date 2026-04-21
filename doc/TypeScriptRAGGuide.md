# TypeScript RAG Integration - Implementierungsguide

## Überblick

Dieses Dokument beschreibt die Implementierung von TypeScript/Angular-Support für das prjxp RAG-System. Die Implementierung folgt dem gleichen Muster wie die bestehende Java-Unterstützung.

## Erstellte Komponenten

### 1. TypeScriptCodeSection Enum
Definiert die verschiedenen Code-Abschnitte:
- `IMPORTS` - Import/Export-Statements
- `METHOD_DOC` - JSDoc-Kommentare
- `METHOD` - Methodenimplementierungen
- `CLASS_FRAME` - Klassendefinition
- `DEPENDENCIE_INFO` - Abhängigkeitsinformationen

### 2. TypeScriptCodeChunker
Das Herzstück des Chunking-Systems.

#### Features:
- Extrahiert TypeScript-Dateien in logische Chunks
- Erkennt Klassen, Methoden und Top-Level Funktionen
- Chunkt JSDoc-Kommentare separat
- Respektiert Token-Limits (~512 Tokens pro Chunk)
- Konfigurierbare Chunk-Größe und Overlap

#### Beispiel-Nutzung:
```java
@Autowired
private TypeScriptCodeChunker chunker;

// Chunking einer TypeScript-Datei
Stream<PxChunk> chunks = chunker.chunk(new File("src/app/my.component.ts"));

// Die Chunks werden automatisch mit folgenden Metadaten erstellt:
// - id: "MyComponent.myMethod"
// - parent: "MyComponent"
// - typescript_code_section: "method"
// - pxchunk_file: "<absoluter-pfad>"
```

#### Konfiguration (application.properties):
```properties
typescript.chunksize=1300
typescript.chunkoverlap=100
```

### 3. TypeScriptRetriever
Verantwortlich für die Zusammenstellung von Chunks in Prompts.

#### Features:
- Kombiniert Multi-Part Chunks
- Erweitert Prompts mit kontextbezogenen Informationen
- Behandelt verschiedene Chunk-Typen unterschiedlich
- Formatiert Ausgabe für LLMs

#### Beispiel-Nutzung:
```java
@Autowired
private TypeScriptRetriever retriever;
@Autowired
private PxChunkDao chunkDao;

// Vector-Search liefert relevante Chunks
List<PxChunk> foundChunks = vectorSearch("component initialization");

// Prompt mit Kontextinformationen aufbereiten
StringBuilder prompt = new StringBuilder();
prompt.append("Frage: Wie wird die Komponente initialisiert?\n\n");
prompt.append("Kontext:\n");
retriever.buildPromptForFindings(prompt, foundChunks);

// Prompt ist jetzt bereit für LLM
```

### 4. TypeScriptPromptSession
Verwaltet die Hierarchie der abgerufenen Chunks.

#### Features:
- Organisiert Chunks in Baumstrukturen
- Rankt relevante Bäume nach Gewichtung
- Begrenzt Prompt-Länge auf 50000 Zeichen
- Beachtet Kontextvalidatoren

#### Ranking-Gewichtung:
```
CLASS_FRAME:       2 Punkte
METHOD:            5 Punkte
METHOD_DOC:        5 Punkte
IMPORTS:           1 Punkt
DEPENDENCIE_INFO:  0 Punkte
```

### 5. ChunkNode Erweiterung
Updated um TypeScript-Metadaten zu verarbeiten.

## Beispiel-Workflow

### Input: AppComponent
```typescript
import { Component, OnInit } from '@angular/core';
import { Title } from '@angular/platform-browser';

/**
 * Hauptkomponente
 */
export class AppComponent implements OnInit {
    private titleService = inject(Title);

    /**
     * Initialisiert die Komponente
     */
    ngOnInit(): void {
        this.titleService.setTitle('App');
    }

    toggleSidenav(): void {
        // Implementation
    }
}

export function setupLogging(): void {
    // Implementation
}
```

### Output: Generierte Chunks

#### Chunk 1: Imports
```
ID: "AppComponent.imports"
Parent: "AppComponent"
Section: IMPORTS
Content:
  import { Component, OnInit } from '@angular/core';
  import { Title } from '@angular/platform-browser';
```

#### Chunk 2: Class Frame
```
ID: "AppComponent"
Parent: null
Section: CLASS_FRAME
Content:
  import { Component, OnInit } from '@angular/core';
  import { Title } from '@angular/platform-browser';
  
  export class AppComponent implements OnInit {
    private titleService = inject(Title);
    
    ngOnInit(): void
    toggleSidenav(): void
  }
```

#### Chunk 3: ngOnInit Method
```
ID: "AppComponent.ngOnInit"
Parent: "AppComponent"
Section: METHOD
Content:
  ngOnInit(): void {
    this.titleService.setTitle('App');
  }
```

#### Chunk 4: ngOnInit JSDoc
```
ID: "AppComponent.ngOnInit.jsdoc"
Parent: "AppComponent.ngOnInit"
Section: METHOD_DOC
Content:
  /**
   * Initialisiert die Komponente
   */
```

#### Chunk 5: setupLogging Function
```
ID: "AppComponent.setupLogging"
Parent: "AppComponent"
Section: METHOD
Content:
  export function setupLogging(): void {
    // Implementation
  }
```

## Chunk-Struktur und Hierarchie

```
AppComponent (CLASS_FRAME, no parent)
├── AppComponent.imports (IMPORTS)
├── AppComponent.ngOnInit (METHOD)
│   └── AppComponent.ngOnInit.jsdoc (METHOD_DOC)
└── AppComponent.toggleSidenav (METHOD)
    └── AppComponent.toggleSidenav.jsdoc (METHOD_DOC)

AppComponent.setupLogging (METHOD, parent=AppComponent)
```

## Retrieval-Beispiel

### Query: "Komponenten-Initialisierung"
1. Vector-Search findet relevante Chunks:
   - "AppComponent.ngOnInit" (HIGH RELEVANCE)
   - "AppComponent.ngOnInit.jsdoc" (HIGH RELEVANCE)
   - "AppComponent" (MEDIUM RELEVANCE)

2. TypeScriptPromptSession erstellt Baum:
   ```
   AppComponent (Rank: 12)
   ├── ngOnInit (Rank: 5)
   └── ngOnInit.jsdoc (Rank: 5)
   ```

3. Prompt wird konstruiert:
   ```
   Frage: Wie wird die Komponente initialisiert?
   
   Kontext:
   /**
    * Initialisiert die Komponente
    */
   ngOnInit(): void {
     this.titleService.setTitle('App');
   }
   
   Hier ein Rumpf der Klasse AppComponent:
   ```typescript
   import { Component, OnInit } from '@angular/core';
   
   export class AppComponent implements OnInit {
     private titleService = inject(Title);
     
     ngOnInit(): void
   }
   ```
   ```

## Token-Management

Jeder Chunk ist auf ~512 Tokens optimiert:
- **Chunk-Größe**: 1300 Zeichen (≈ 325-400 Tokens)
- **Overlap**: 100 Zeichen für Kontext-Kontinuität
- **Embedding-Limit**: 512 Tokens (z.B. für all-MiniLM Modelle)

## Integrationspunkte

### 1. Datei-Scanning
```java
Files.walk(Paths.get("src"))
    .filter(path -> PxFileType.TYPESCRIPT_CODE.matches(path.toFile()))
    .forEach(path -> {
        Stream<PxChunk> chunks = chunker.chunk(path.toFile());
        // Speicher in ChromaDB
    });
```

### 2. Vector-Search
```java
List<PxChunk> results = chromaDB.search("how to initialize component", topK=5);
```

### 3. Prompt-Building
```java
StringBuilder prompt = new StringBuilder("Frage: ");
retriever.buildPromptForFindings(prompt, results);
String finalPrompt = prompt.toString();

// Send to LLM
String answer = llm.generate(finalPrompt);
```

## Unterschiede zu Java-Implementierung

| Aspekt | Java | TypeScript |
|--------|------|-----------|
| Parser | JavaParser (AST) | Regex-Patterns |
| Doc-Format | JavaDoc | JSDoc |
| Strukturen | Klassen, Interfaces | Klassen, Interfaces, Functions |
| Imports | `import package.*` | `import { ... } from '...'` |
| Typ-System | Vollständig | Teilweise Unterstützung |
| Dependencies | Klassen-basiert | Module-basiert |

## Fehlerbehebung

### Problem: Keine TypeScript-Chunks werden erstellt
**Lösung**: Sicherstellen, dass:
1. Datei `.ts` Erweiterung hat
2. `@Chunker` Annotation vorhanden ist
3. `TYPESCRIPT_CODE` in PxFileType existiert

### Problem: JSDoc wird nicht erkannt
**Lösung**: JSDoc muss folgendes Format erfüllen:
```typescript
/**
 * JSDoc comment
 */
methodOrFunction() { ... }
```

### Problem: Methoden werden nicht erkannt
**Lösung**: Methoden müssen eines dieser Formate erfüllen:
```typescript
methodName(): void { }
private methodName(): string { }
async methodName(): Promise<T> { }
public async methodName(): Promise<void> { }
```

## Komplette Dokumentation

Siehe auch:
- `TypeScriptRAGImplementation.md` - Technische Details
- `example.component.ts` - Beispiel-Datei
- Java-Implementation unter `chunk-norris/src/main/java/de/spraener/prjxp/chuno/code/java/`

## Nächste Schritte

1. **Testen**: Mit echten Angular-Komponenten testen
2. **Optimierung**: Regex-Patterns optimieren
3. **AST-Parser**: TypeScript Compiler API integrieren
4. **Decorators**: Angular Decorators besser handhaben
5. **Module-System**: Modul-Abhängigkeiten tracken

