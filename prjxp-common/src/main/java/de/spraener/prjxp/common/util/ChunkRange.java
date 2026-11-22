package de.spraener.prjxp.common.util;

import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.util.List;

@Data
@RequiredArgsConstructor
public class ChunkRange {
    public static final ChunkRange EMPTY = new ChunkRange(0, 0, null);
    private final int fromLine;
    private final int toLine;
    private final List<String> codeLines;

    public String toCode() {
        if (codeLines == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        // toLine is the 0-based exclusive end (== numeric value of the last 1-based line).
        for (int i = fromLine; i < toLine && i < codeLines.size(); i++) {
            sb.append(codeLines.get(i)).append('\n');
        }
        return sb.toString();
    }
}
