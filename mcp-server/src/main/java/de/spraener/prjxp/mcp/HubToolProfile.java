package de.spraener.prjxp.mcp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Tool-contract profile of the hub.
 * <p>
 * {@code p1} is the historical contract: every tool takes a {@code project} parameter and
 * {@code listProjects} is part of the surface. {@code p2} binds the project server-side and
 * exposes a smaller, denser tool set (see Benchmark-Befund 02).
 * <p>
 * The profile is a startup property, not a per-call switch: the MCP tool list is built once
 * when the context starts. A/B runs therefore start one hub per profile, from the same image.
 */
@Component
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
public class HubToolProfile {

    public enum Profile {
        P1, P2
    }

    private final Profile profile;

    public HubToolProfile(@Value("${prjxp.hub.tool-profile:p1}") String profile) {
        this.profile = parse(profile);
    }

    private static Profile parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Profile.P1;
        }
        try {
            return Profile.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Unknown prjxp.hub.tool-profile '" + raw + "'. Use p1 or p2.");
        }
    }

    public Profile profile() {
        return profile;
    }

    public boolean isP2() {
        return profile == Profile.P2;
    }

    /**
     * Project for a tool call. Both profiles use the explicit argument: the hub serves all indexed
     * projects, so a process-wide binding would be wrong.
     */
    public String projectOrDefault(String explicit) {
        return explicit;
    }
}
