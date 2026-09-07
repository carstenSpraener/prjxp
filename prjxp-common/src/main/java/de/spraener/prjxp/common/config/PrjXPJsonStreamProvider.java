package de.spraener.prjxp.common.config;

import de.spraener.prjxp.common.transfer.TransferCrypto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor
public class PrjXPJsonStreamProvider {
    private final PrjXPConfig config;

    public Stream<String> getJsonlStream(String inputSource) throws IOException {
        return linesFrom(openRaw(inputSource));
    }

    public Stream<String> getTransferJsonlStream(String inputSource, char[] password) throws IOException {
        return linesFrom(TransferCrypto.openAuto(openRaw(inputSource), password));
    }

    private InputStream openRaw(String inputSource) throws IOException {
        if (inputSource == null || "-".equals(inputSource) || inputSource.isEmpty()) {
            // Nutze stdin (Standard Input)
            return System.in;
        }
        // Nutze die Datei
        Path path = Paths.get(inputSource);
        return Files.newInputStream(path);
    }

    private Stream<String> linesFrom(InputStream inputStream) {
        // Erstelle einen BufferedReader und wandle ihn in einen Stream um
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));

        // .lines() schließt den Reader automatisch, wenn der Stream geschlossen wird
        return reader.lines();
    }

}
