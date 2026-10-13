package de.spraener.prjxp.mcpproxy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SseParserTest {

    private final SseParser parser = new SseParser();

    // =========================================================================
    // Single-line data events
    // =========================================================================

    @Test
    void singleDataLine() {
        List<SseEvent> events = parser.parse("data: hello\n\n");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).toString())
                .isEqualTo("SseEvent{event='null', id='null', data='hello'}");
    }

    @Test
    void multiLineData() {
        List<SseEvent> events = parser.parse("data: line1\ndata: line2\ndata: line3\n\n");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getData()).isEqualTo("line1\nline2\nline3");
    }

    @Test
    void dataWithEventAndId() {
        List<SseEvent> events = parser.parse("event: message\nid: 42\ndata: payload\n\n");
        assertThat(events).hasSize(1);
        SseEvent e = events.get(0);
        assertThat(e.getEvent()).isEqualTo("message");
        assertThat(e.getId()).isEqualTo("42");
        assertThat(e.getData()).isEqualTo("payload");
    }

    // =========================================================================
    // Comment handling
    // =========================================================================

    @Test
    void commentLinesIgnored() {
        List<SseEvent> events = parser.parse(": this is a comment\ndata: hello\n\n");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getData()).isEqualTo("hello");
    }

    @Test
    void pingCommentIgnored() {
        List<SseEvent> events = parser.parse(": ping\ndata: x\n\n");
        assertThat(events).hasSize(1);
    }

    // =========================================================================
    // CRLF handling
    // =========================================================================

    @Test
    void crlfLineEndings() {
        List<SseEvent> events = parser.parse("data: hello\r\n\r\n");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getData()).isEqualTo("hello");
    }

    @Test
    void mixedCrlfAndLf() {
        List<SseEvent> events = parser.parse("data: a\r\ndata: b\n\n");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getData()).isEqualTo("a\nb");
    }

    // =========================================================================
    // Multiple events in one stream
    // =========================================================================

    @Test
    void multipleEvents() {
        List<SseEvent> events = parser.parse("data: first\n\ndata: second\n\n");
        assertThat(events).hasSize(2);
        assertThat(events.get(0).getData()).isEqualTo("first");
        assertThat(events.get(1).getData()).isEqualTo("second");
    }

    // =========================================================================
    // Edge cases
    // =========================================================================

    @Test
    void incompleteLastLineWithoutBlankBoundary() {
        List<SseEvent> events = parser.parse("data: no-blank-line");
        assertThat(events).hasSize(1); // flush() picks it up
        assertThat(events.get(0).getData()).isEqualTo("no-blank-line");
    }

    @Test
    void emptyDataValue() {
        List<SseEvent> events = parser.parse("data:\n\n");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getData()).isEmpty();
    }

    @Test
    void noDataOnlyEventField() {
        List<SseEvent> events = parser.parse("event: message\n\n");
        assertThat(events).isEmpty(); // no data → nothing emitted
    }

    @Test
    void unknownFieldsIgnored() {
        List<SseEvent> events = parser.parse("retry: 3000\ndata: ok\n\n");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getData()).isEqualTo("ok");
    }

    // =========================================================================
    // Line-by-line parsing (parseLine API)
    // =========================================================================

    @Test
    void parseLineIncremental() {
        SseParser p = new SseParser();

        List<SseEvent> r1 = p.parseLine("data: part1");
        assertThat(r1).isEmpty();

        List<SseEvent> r2 = p.parseLine("data: part2");
        assertThat(r2).isEmpty();

        List<SseEvent> r3 = p.parseLine(""); // blank line → flush
        assertThat(r3).hasSize(1);
        assertThat(r3.get(0).getData()).isEqualTo("part1\npart2");
    }

    @Test
    void parseLineCommentReturnsEmpty() {
        List<SseEvent> events = parser.parseLine(": comment");
        assertThat(events).isEmpty();
    }

    @Test
    void parseLineMalformedNoColon() {
        List<SseEvent> events = parser.parseLine("no-colon-here");
        assertThat(events).isEmpty();
    }

    // =========================================================================
    // JSON data (common MCP use case)
    // =========================================================================

    @Test
    void jsonPayloadInData() {
        String json = "{\"jsonrpc\":\"2.0\",\"result\":{\"foo\":1}}";
        List<SseEvent> events = parser.parse("data: " + json + "\n\n");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getData()).isEqualTo(json);
    }

    @Test
    void multiLineJsonPayload() {
        String json = "{\n  \"jsonrpc\": \"2.0\",\n  \"result\": true\n}";
        List<SseEvent> events = parser.parse(
                "data: {\n" +
                "data:   \"jsonrpc\": \"2.0\",\n" +
                "data:   \"result\": true\n" +
                "data: }\n\n");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getData()).isEqualTo(json);
    }

    // =========================================================================
    // toString contract
    // =========================================================================

    @Test
    void toStringFormat() {
        SseEvent e = new SseEvent("msg", "123", "hello");
        assertThat(e.toString()).isEqualTo("SseEvent{event='msg', id='123', data='hello'}");
    }

    @Test
    void toStringWithNulls() {
        SseEvent e = new SseEvent(null, null, "x");
        assertThat(e.toString()).isEqualTo("SseEvent{event='null', id='null', data='x'}");
    }
}
