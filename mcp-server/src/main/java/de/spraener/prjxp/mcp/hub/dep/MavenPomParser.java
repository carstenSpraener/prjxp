package de.spraener.prjxp.mcp.hub.dep;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.DocumentBuilder;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.StringReader;
import org.xml.sax.InputSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses a Maven POM into a {@link PomModel}. Uses the JDK DOM parser with external
 * entities + DTD loading disabled (XXE guard). Property placeholders (${...}) are
 * resolved against the POM's own properties, then the parent's, in that order.
 */
public class MavenPomParser {

    public record DepRef(String groupId, String artifactId, String version, String scope) {}
    public record ParentRef(String groupId, String artifactId, String version) {}
    public record PomModel(String groupId, String artifactId, String version,
                           Map<String, String> properties,
                           List<DepRef> dependencies,
                           List<DepRef> dependencyManagement,
                           ParentRef parent) {}

    public PomModel parse(String pomXml) throws MavenDependencyException {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(new InputSource(new StringReader(pomXml)));
            Element root = doc.getDocumentElement();   // <project>

            Element parentEl = child(root, "parent");
            ParentRef parent = parentEl == null ? null
                    : new ParentRef(text(parentEl, "groupId"), text(parentEl, "artifactId"), text(parentEl, "version"));

            Map<String, String> props = new LinkedHashMap<>();
            Element propsEl = child(root, "properties");
            if (propsEl != null) {
                for (Element e : children(propsEl)) {
                    props.put(e.getTagName(), e.getTextContent().trim());
                }
            }

            String groupId = resolveOwnOrParent(text(root, "groupId"),
                    parent == null ? null : text(parentEl, "groupId"), props);
            String artifactId = text(root, "artifactId");
            String version = resolveOwnOrParent(text(root, "version"),
                    parent == null ? null : text(parentEl, "version"), props);

            List<DepRef> deps = readDeps(child(root, "dependencies"), props);
            // <dependencyManagement> nests its entries in a <dependencies> child element.
            Element depMgmtEl = child(root, "dependencyManagement");
            List<DepRef> depMgmt = readDeps(depMgmtEl == null ? null : child(depMgmtEl, "dependencies"), props);

            return new PomModel(groupId, artifactId, version, props, deps, depMgmt, parent);
        } catch (Exception e) {
            throw new MavenDependencyException("Failed to parse POM: " + e.getMessage(), e);
        }
    }

    /** Own value if present, else the parent's; then interpolate ${...} against the POM's properties. */
    private String resolveOwnOrParent(String own, String parentValue, Map<String, String> props) {
        String v = (own != null && !own.isBlank()) ? own : parentValue;
        return v == null ? null : interpolate(v, props);
    }

    private String interpolate(String value, Map<String, String> props) {
        if (value == null || !value.contains("${")) return value;
        String out = value;
        for (Map.Entry<String, String> e : props.entrySet()) {
            out = out.replace("${" + e.getKey() + "}", e.getValue());
        }
        return out;
    }

    /** Reads {@code <dependency>} children, interpolating ${...} placeholders against the POM's properties. */
    private List<DepRef> readDeps(Element depsEl, Map<String, String> props) {
        List<DepRef> out = new ArrayList<>();
        if (depsEl == null) return out;
        for (Element dep : children(depsEl)) {
            if (!"dependency".equals(dep.getTagName())) continue;
            out.add(new DepRef(interpolate(text(dep, "groupId"), props),
                    interpolate(text(dep, "artifactId"), props),
                    interpolate(text(dep, "version"), props),
                    text(dep, "scope")));
        }
        return out;
    }

    private Element child(Element parent, String tag) {
        if (parent == null) return null;
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n instanceof Element e && tag.equals(e.getTagName())) return e;
        }
        return null;
    }
    private List<Element> children(Element parent) {
        List<Element> out = new ArrayList<>();
        if (parent == null) return out;
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            if (nl.item(i) instanceof Element e) out.add(e);
        }
        return out;
    }
    private String text(Element parent, String tag) {
        Element e = child(parent, tag);
        return e == null ? null : e.getTextContent().trim();
    }
}
