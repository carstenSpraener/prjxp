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
        for (int i = fromLine; i <= toLine; i++) {
            sb.append(codeLines.get(i)).append('\n');
        }
        return sb.toString();
    }
}
