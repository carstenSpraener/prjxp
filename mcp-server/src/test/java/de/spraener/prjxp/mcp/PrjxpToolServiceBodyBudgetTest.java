package de.spraener.prjxp.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.spraener.prjxp.common.model.MethodView;
import de.spraener.prjxp.common.model.SymbolReadResult;
import de.spraener.prjxp.mcp.SymbolReaderService;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Der P2-Pfad darf Rümpfe nicht unbegrenzt anhängen.
 * <p>
 * Im Legacy-Lauf lieferten einzelne {@code readBySignature}-Aufrufe bis zu 17.800 Zeichen
 * (P1: 8.083), in Summe 436k statt 157k Zeichen. Der Deckel je Rumpf half nicht, weil bis zu
 * zehn Treffer je Aufruf zurückkommen. Deshalb gilt im P2-Profil zusätzlich ein Gesamtbudget
 * pro Aufruf.
 */
@ExtendWith(MockitoExtension.class)
class PrjxpToolServiceBodyBudgetTest {

    @Mock
    private SymbolReaderService symbolReaderService;

    private PrjxpToolService tool;

    @BeforeEach
    void setUp() {
        tool = new PrjxpToolService(null, null, null, symbolReaderService,
                null, null, null, null, null, null, null, null, null);
    }

    private static MethodView view(String fqn, String body) {
        return new MethodView(fqn, fqn + ".java", 1, 2, null, body);
    }

    private static String body(char fill, int length) {
        return String.valueOf(fill).repeat(length);
    }

    @Test
    void bodiesShareOneBudgetPerCall() {
        List<MethodView> matches = IntStream.range(0, 5)
                .mapToObj(i -> view("a.B#m" + i, body((char) ('a' + i), 3_000)))
                .toList();
        when(symbolReaderService.readBySignature("a.B", null, "legacy", true, 3))
                .thenReturn(new SymbolReadResult(matches, List.of(), null));

        SymbolReadResult result = tool.readBySignature("a.B", null, "legacy", true, 3);

        int total = result.matches().stream()
                .map(MethodView::body)
                .filter(b -> b != null)
                .mapToInt(String::length)
                .sum();
        assertThat(total).isLessThanOrEqualTo(ToolResultCaps.READER);
        assertThat(result.matches().getFirst().body()).isEqualTo(body('a', 3_000));
        assertThat(result.matches().getLast().body()).isNull();
    }

    @Test
    void signatureOnlyResultsKeepTheirBodies() {
        List<MethodView> matches = List.of(view("a.B#m", body('x', 200)));
        when(symbolReaderService.readBySignature("a.B", null, "prjxp", true, 3))
                .thenReturn(new SymbolReadResult(matches, List.of(), null));

        SymbolReadResult result = tool.readBySignature("a.B", null, "prjxp", true, 3);

        assertThat(result.matches().getFirst().body()).isEqualTo(body('x', 200));
    }

    @Test
    void oneLongBodyIsCappedAtTheCallBudget() {
        List<MethodView> matches = List.of(view("a.B#m", body('x', 12_000)));
        when(symbolReaderService.readBySignature("a.B", null, "prjxp", true, 3))
                .thenReturn(new SymbolReadResult(matches, List.of(), null));

        SymbolReadResult result = tool.readBySignature("a.B", null, "prjxp", true, 3);

        String capped = result.matches().getFirst().body();
        assertThat(capped.length()).isLessThan(12_000);
        assertThat(capped).startsWith(body('x', 9_000)).contains("gekürzt");
    }
}
