package de.spraener.prjxp.mcp.hub.dep;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Minimal Maven Central client over the JDK HttpClient. All methods throw
 * {@link MavenDependencyException} on network/HTTP/size failure (never return null).
 */
public class MavenCentralClient {

    private final HttpClient http;
    private final String repoBase;          // e.g. "https://repo1.maven.org/maven2"
    private final Duration requestTimeout;
    private final long maxBytes;

    public MavenCentralClient(String repoBase, Duration connectTimeout, Duration requestTimeout, long maxBytes) {
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        this.repoBase = repoBase.endsWith("/") ? repoBase.substring(0, repoBase.length() - 1) : repoBase;
        this.requestTimeout = requestTimeout;
        this.maxBytes = maxBytes;
    }

    /** Fetch and return the POM XML content for the coordinate. */
    public String fetchPom(MavenCoordinates c) throws MavenDependencyException {
        try {
            HttpResponse<String> resp = http.send(get(c.pomUrl(repoBase)), HttpResponse.BodyHandlers.ofString());
            return body(resp);
        } catch (IOException | InterruptedException e) {
            interruptIfNeeded(e);
            throw new MavenDependencyException("POM fetch failed for " + c.pomUrl(repoBase) + ": " + e.getMessage(), e);
        }
    }

    /** True when the classifier artifact exists (HTTP 200); 404/410 -> false; other -> exception. */
    public boolean exists(MavenCoordinates c, String classifier) throws MavenDependencyException {
        String url = classifierUrl(c, classifier);
        try {
            HttpResponse<Void> resp = http.send(head(url), HttpResponse.BodyHandlers.discarding());
            int code = resp.statusCode();
            if (code == 200) return true;
            if (code == 404 || code == 410) return false;
            throw new MavenDependencyException("Unexpected HTTP " + code + " for " + url);
        } catch (IOException | InterruptedException e) {
            interruptIfNeeded(e);
            throw new MavenDependencyException("HEAD failed for " + url + ": " + e.getMessage(), e);
        }
    }

    /** Download the classifier artifact to {@code target} (parent dirs created). Enforces maxBytes. */
    public void download(MavenCoordinates c, String classifier, Path target) throws MavenDependencyException {
        String url = classifierUrl(c, classifier);
        try {
            Files.createDirectories(target.getParent());
            HttpResponse<Path> resp = http.send(get(url), HttpResponse.BodyHandlers.ofFile(target));
            if (resp.statusCode() != 200) {
                Files.deleteIfExists(target);
                throw new MavenDependencyException("HTTP " + resp.statusCode() + " for " + url);
            }
            if (Files.size(target) > maxBytes) {
                Files.deleteIfExists(target);
                throw new MavenDependencyException("Artifact exceeds size limit " + maxBytes + " bytes: " + url);
            }
        } catch (IOException | InterruptedException e) {
            interruptIfNeeded(e);
            throw new MavenDependencyException("Download failed for " + url + ": " + e.getMessage(), e);
        }
    }

    private void interruptIfNeeded(Throwable e) {
        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
    }

    private String classifierUrl(MavenCoordinates c, String classifier) {
        return repoBase + "/" + c.repoPath() + "/" + c.artifactId() + "-" + c.version() + "-" + classifier + ".jar";
    }
    private HttpRequest get(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(requestTimeout).GET().build();
    }
    private HttpRequest head(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(requestTimeout).method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
    }
    private String body(HttpResponse<String> resp) throws MavenDependencyException {
        if (resp.statusCode() != 200) {
            throw new MavenDependencyException("HTTP " + resp.statusCode() + " for " + resp.request().uri());
        }
        return resp.body();
    }
}
