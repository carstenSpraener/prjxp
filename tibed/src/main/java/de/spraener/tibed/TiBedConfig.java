package de.spraener.tibed;

import lombok.Data;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

@Data
public class TiBedConfig {
    private String inputSource;
    private int batchSize = 0;

    public Stream<String> getJsonlStream() throws IOException {
        InputStream inputStream;

        if (inputSource == null || "-".equals(inputSource) || inputSource == null || inputSource.isEmpty()) {
            // Nutze stdin (Standard Input)
            inputStream = System.in;
        } else {
            // Nutze die Datei
            Path path = Paths.get(inputSource);
            inputStream = Files.newInputStream(path);
        }

        // Erstelle einen BufferedReader und wandle ihn in einen Stream um
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));

        // .lines() schließt den Reader automatisch, wenn der Stream geschlossen wird
        return reader.lines();
    }
}
