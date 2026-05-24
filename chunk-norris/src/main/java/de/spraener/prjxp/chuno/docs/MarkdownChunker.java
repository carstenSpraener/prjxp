package de.spraener.prjxp.chuno.docs;

import de.spraener.prjxp.chuno.docs.model.ConversionAccuracy;
import de.spraener.prjxp.chuno.docs.model.DocArtifaktType;
import de.spraener.prjxp.common.annotations.ChunkNorrisComponent;
import de.spraener.prjxp.common.annotations.Chunker;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.PxFileType;
import de.spraener.prjxp.common.util.ContentSplitter;
import de.spraener.prjxp.common.util.LineScanner;
import de.spraener.prjxp.common.util.ValueContainer;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Component
@ChunkNorrisComponent
@Log
@RequiredArgsConstructor
public class MarkdownChunker {
    private final DocConversionRouter converter;
    private final MetaInfReader metaInfReader;

    @Chunker(
            fileTypes = {
                    PxFileType.WORD_DOC,
                    PxFileType.WORD_DOCX,
                    PxFileType.PDF,
                    PxFileType.HTML,
                    PxFileType.TXT,
                    PxFileType.MARK_DOWN,
                    PxFileType.RTF
            }
    )
    public Stream<PxChunk> processFile(File input) {
        log.info("Chunking file "+input.getName());
        try {
            // 1. Typ bestimmen via deiner Enum-Logik
            DocArtifaktType sourceType = DocArtifaktType.fromFile(input);

            if (sourceType == DocArtifaktType.UNKNOWN) {
                log.warning("Unbekannter Dateityp für: " + input.getName());
                return Stream.empty();
            }

            // 2. Konvertierung in Markdown triggern
            // Hier greift dein Dijkstra-Routing (z.B. der Analytic-Pfad für Word)
            String mdText = converter.doConversion(input, sourceType, DocArtifaktType.MARK_DOWN, ConversionAccuracy.ANALYTIC);

            if (mdText == null || mdText.isBlank()) {
                return Stream.empty();
            }

            // 3. Das generierte Markdown in strukturierte PxChunks zerlegen
            return chunkMarkdownText(input, mdText);

        } catch (Exception e) {
            log.severe("Fehler beim Chunking von " + input.getAbsolutePath() + ": " + e.getMessage());
            return Stream.empty();
        }
    }

    private Stream<PxChunk> chunkMarkdownText(File originalFile, String markdown) {
        try {
            Map<String,String> metaInf = metaInfReader.readMetaInf(originalFile);
            List<PxChunk> chunkList = new ArrayList<>();
            chunkList.add( PxChunk.create(
                    c -> c.setId(originalFile.getName()),
                    c -> c.setFile(originalFile.getAbsolutePath()),
                    c -> c.setContent(""),
                    c->c.getMetadata().put("pxchunk_type", "FILE")
            ));
            LineScanner scanner = new LineScanner(new ByteArrayInputStream(markdown.getBytes()), originalFile, 4);

            List<String> currentHeaders = new ArrayList<>();
            int[] sectionCounters = new int[10];
            StringBuilder paragraphContent = new StringBuilder();
            int sectionStartLine = 0;
            boolean inTOC = false;
            int paragraphCount = 0;
            for ( String line = scanner.getCurrentLine(); scanner.getCurrentLine()!=LineScanner.EOF; line = scanner.nextLine()) {
                line = preprocessLine(scanner);
                if (line.startsWith("#")) {
                    int level = 0;
                    while (level < line.length() && line.charAt(level) == '#') level++;
                    String headerText = line.substring(level).trim();
                    updateHierarchy(currentHeaders, sectionCounters, level, headerText);
                    paragraphCount = 0;
                    chunkList.add(createSectionChunk(originalFile, sectionCounters, currentHeaders));
                } else if( isEndOfParagraph(scanner) ) {
                    paragraphCount++;
                    paragraphContent.append(line).append('\n');
                    processSection(originalFile, chunkList, paragraphContent, currentHeaders, paragraphCount, sectionCounters, sectionStartLine, scanner.getGlobalLineIndex());
                    paragraphContent = new StringBuilder();
                    sectionStartLine = skipToNextNonEmptyLine(scanner);
                }else {
                    // remove leading empty lines
                    if( !paragraphContent.isEmpty() || !line.isBlank() ) {
                        paragraphContent.append(line).append('\n');
                    }
                }
            }
            processSection(originalFile, chunkList, paragraphContent, currentHeaders, paragraphCount, sectionCounters, sectionStartLine, scanner.getGlobalLineIndex());

            if( !metaInf.isEmpty() ) {
                for (PxChunk c : chunkList) {
                    c.getMetadata().putAll(metaInf);
                }
            }
            return chunkList.stream();
        } catch (Exception e) {
            log.severe("Fehler beim Chunking von " + originalFile.getAbsolutePath() + ": " + e.getMessage());
            return Stream.empty();
        }
    }

    private PxChunk createSectionChunk(File file, int[] sectionCounters, List<String> headers) {
        String sectionNumber = formatSectionNumber(sectionCounters);
        return PxChunk.create(
                c->c.setId(file.getName()+":"+sectionNumber),
                c->c.setParent(file.getName()),
                c->c.setContent(""),
                c->c.setFile(file.getAbsolutePath()),
                c->c.getMetadata().put("pxchunk_type", "SECTION"),
                c->c.setContent(headers.getLast())
        );
    }

    private int skipToNextNonEmptyLine(LineScanner scanner) throws IOException {
        String line;
        while( (line = scanner.peek(1))!=LineScanner.EOF ) {
            if( !line.isBlank() ) {
                return scanner.getGlobalLineIndex();
            }
            scanner.nextLine();
        }
        return scanner.getGlobalLineIndex();
    }

    private String preprocessLine(LineScanner scanner) {
        String line = scanner.getCurrentLine().trim();
        if( isTabelStart(scanner) ) {
            if( scanner.peek(1).contains("**")) {
                scanner.swapLines(0, 1);
            } else {
                return createTableHeader(scanner) +" \n" + scanner.getCurrentLine();
            }
        }
        String sectionRegex = "^(?:\\*+)?(\\d{1,2}(?:\\.\\d+)*\\.?)(?:\\*+)?\\s+.*"; //""^(\\d{1,2}(\\.\\d+)*\\.?)\\s+.*";
        Pattern sectionPattern = Pattern.compile(sectionRegex);
        Matcher matcher = sectionPattern.matcher(line.trim());
        if( matcher.matches() ) {
            String numbering = matcher.group(1);
            long dotCount = numbering.chars().filter(ch -> ch == '.').count();
            int level = (int) dotCount;
            if (!numbering.endsWith(".")) {
                level += 1;
            }
            int hashtagCount = Math.min(level, 6);

            String hashtags = "#".repeat(hashtagCount);
            String transformedHeader = hashtags + " " + line.trim();
            return transformedHeader;
        }
        return line;
    }

    private String createTableHeader(LineScanner scanner) {
        int nofCols = StringUtils.countOccurrencesOf(scanner.getCurrentLine(), "|");
        String header = "";
        for( int i=0; i<nofCols; i++ ) {
            header += "|   ";
        }
        return header.trim();
    }

    private boolean isTabelStart(LineScanner scanner) {
        String currentLine = scanner.getCurrentLine();
        String prevLine = scanner.peek(-1);
        boolean isTableSeparator = currentLine.matches("^\\|?\\s?[:\\- ]+\\s?(\\|?\\s?[:\\- ]+\\s?)*\\|?$");
        if( prevLine.trim().equals("") && isTableSeparator) {
            return true;
        } else {
            return false;
        }
    }

    private void processSection(File file, List<PxChunk> list,
                                StringBuilder content,
                                List<String> headers,
                                int paragraphCount,
                                int[] counters,
                                int startLine,
                                int currentLine) {
        if (content.length() < 40) return;

        final String sectionId = String.join(" > ", headers);
        final String sectionNum = formatSectionNumber(counters);
        final ValueContainer<Integer> partCounter = new ValueContainer<>(0);
        final ValueContainer<Integer> totalCounter = new ValueContainer<>(0);

        // Nutzt deinen ContentSplitter für i of N Splitting
        List<PxChunk> chunks = new ContentSplitter(500, 100).splitContent(content, startLine, currentLine,
                () -> PxChunk.create(
                        c -> c.setMimeType("text/markdown"),
                        c -> c.setFile(file.getAbsolutePath()),
                        c -> {
                            c.setId(file.getName()+":"+sectionNum+":"+String.format("%02d",paragraphCount));
                            c.getMetadata().put("section_number", sectionNum);
                            c.getMetadata().put("hierarchy", sectionId);
                            c.setPart(totalCounter.getValue());
                            c.getMetadata().put("type", "md_paragraph");
                            c.setParent(file.getName()+":"+sectionNum);
                            totalCounter.setValue(totalCounter.getValue() + 1);
                            // Header-Ebenen einzeln für Filterung hinterlegen
                            for (int i = 0; i < headers.size(); i++) {
                                c.getMetadata().put("h" + (i + 1), headers.get(i));
                            }
                        }
                ));

        chunks.forEach(c -> c.setTotal(totalCounter.getValue()));
        list.addAll(chunks);
    }

    private void updateHierarchy(List<String> headers, int[] counters, int level, String text) {
        // Tiefergehende Counter zurücksetzen
        for (int i = level; i < counters.length; i++) counters[i] = 0;
        counters[level - 1]++;

        // Header-Liste anpassen
        while (headers.size() >= level) headers.remove(headers.size() - 1);
        headers.add(text);
    }

    private String formatSectionNumber(int[] counters) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < counters.length && counters[i] > 0; i++) {
            if (sb.length() > 0) sb.append(".");
            sb.append(String.format("%02d", counters[i]));
        }
        return sb.toString();
    }

    public boolean isEndOfParagraph(LineScanner scanner) {
        // Aktuelle Zeile hat Text, nächste ist leer, übernächste hat wieder Text
        String currentLine = scanner.getCurrentLine();
        if( !currentLine.isBlank() ) {
            return false;
        }
        String nextNonEmpty ="";
        for( int i=1; i<scanner.getWindowSize(); i++ ) {
            nextNonEmpty = scanner.peek(i);
            if( nextNonEmpty!=null && !LineScanner.EOF.equals(nextNonEmpty) && !nextNonEmpty.isBlank() ) {
                break;
            }
        }
        return isHeader(nextNonEmpty);
    }

    public boolean isTableOfContents(LineScanner scanner) {
        String current = scanner.getCurrentLine();
        return (!StringUtils.hasText(current) || isHeader(current)) && (isHeader(scanner.peekNextNonEmpty()) || isHeader(scanner.peekPrevNonEmpty()));
    }

    private boolean isHeader(String line) {
        if (line.equals(LineScanner.EOF)) return false;
        return line.trim().matches("^(\\d{1,2}(\\.\\d+)*\\.?)\\s+.*");
    }
}
