package de.spraener.prjxp.chuno.docs.html;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

public class HtmlStructurePreprocessor {

    public String prepare(String rawHtml) {
        Document doc = Jsoup.parse(rawHtml);

        // 1. Semantische Korrektur der Überschriften
        // Wir suchen alle <p>, die eine Klasse enthalten, die auf "berschrift" matched
        doc.select("p[class*='berschrift'], p.Titel").forEach(el -> {
            String className = el.className();
            String targetTag = "p";

            if (className.contains("Titel") || className.contains("berschrift1")) targetTag = "h1";
            else if (className.contains("berschrift2")) targetTag = "h2";
            else if (className.contains("berschrift3")) targetTag = "h3";
            else if (className.contains("berschrift4")) targetTag = "h4";

            el.tagName(targetTag);
            // Optional: Klasse entfernen, da sie nun im Tag steckt
            el.removeAttr("class");
        });

        // 2. Listen-Reparatur
        // Word exportiert Listen oft als <p class="Listenabsatz"> mit einem Bullet-Span
        doc.select("p.Listenabsatz").forEach(el -> {
            // Wir lassen Flexmark die Arbeit machen, sorgen aber dafür,
            // dass die Struktur sauberer ist (z.B. Br-Tags innerhalb von Listen entfernen)
            el.select("br").remove();
        });

        // 3. Generelle Bereinigung
        // Entferne leere Absätze, die nur ein <br> enthalten (das sind deine vielen <br/> im MD)
        doc.select("p:has(br):matchesOwn(^$)").remove();
        doc.select("p.Listenabsatz").forEach(el -> {
            String text = el.text().trim();
            // Ersetze Bullets durch Standard-Markdown-Listenmarker
            if (text.startsWith("•") || text.startsWith("o")) {
                // Bei 'o' (Sub-Entry) fügen wir zwei Leerzeichen für die Einrückung hinzu
                String prefix = text.startsWith("o") ? "  - " : "- ";
                el.text(prefix + text.substring(1).trim());
            }
        });
        fixListStructure(doc);

        String html = doc.html();
        return html.replaceAll("(?i)<p[^>]*>\\s*<br\\s*/?>\\s*</p>", "");
    }

    public String fixListStructure(Document doc) {
        Element currentList = null;

        // Wir suchen alle Absätze, die nach Liste aussehen
        for (Element p : doc.select("p.Listenabsatz")) {
            String text = p.text().trim();

            // Wenn es mit einem Bullet-Zeichen beginnt
            if (text.startsWith("•") || text.startsWith("o") || text.startsWith("·")) {
                // Falls wir noch keine <ul> gestartet haben, erstelle eine
                if (currentList == null) {
                    currentList = doc.createElement("ul");
                    p.before(currentList);
                }

                // Erzeuge ein echtes <li> und entferne das Bullet-Zeichen aus dem Text
                Element li = doc.createElement("li");
                li.text(text.substring(1).trim());
                currentList.appendChild(li);

                // Den alten Absatz entfernen
                p.remove();
            } else {
                // Absatz gehört nicht mehr zur Liste -> Kette unterbrechen
                currentList = null;
            }
        }
        return doc.html();
    }
}
