class OragelSearch extends HTMLElement {
    constructor() {
        super();
        this.attachShadow({mode: 'open'});
        this.lastContext = "";
    }

    connectedCallback() {
        this.shadowRoot.innerHTML = `
            <style>
                :host { font-family: sans-serif; display: block; padding: 20px; border: 1px solid #00529b; border-radius: 8px; background: #fff; }
                .search-box { display: flex; gap: 10px; margin-bottom: 15px; }
                input { flex-grow: 1; padding: 10px; border: 1px solid #ccc; border-radius: 4px; font-size: 1rem; }
                button { padding: 10px 20px; cursor: pointer; background: #00529b; color: white; border: none; border-radius: 4px; font-weight: bold; }
                button:hover { background: #003e75; }
                
                details { margin-top: 15px; border: 1px solid #eee; border-radius: 4px; background: #f9f9f9; }
                summary { padding: 10px; cursor: pointer; font-weight: bold; color: #555; }
                pre { padding: 15px; margin: 0; overflow-x: auto; max-height: 400px; font-size: 0.9rem; white-space: pre-wrap; word-wrap: break-word; }
                
                .status-msg { margin-top: 10px; font-size: 0.9rem; font-weight: bold; }
                .success { color: #28a745; }
                .loading { color: #00529b; }
            </style>
            
            <div class="search-box">
                <input type="text" id="query" placeholder="Frage an den Project Expert (Enter zum Suchen)...">
                <button id="searchBtn">Suchen</button>
            </div>
            
            <div id="status" class="status-msg"></div>

            <details id="resultContainer" style="display: none;">
                <summary>Gefundener Kontext (wurde automatisch kopiert)</summary>
                <pre id="results"></pre>
            </details>
        `;

        const input = this.shadowRoot.getElementById('query');
        const btn = this.shadowRoot.getElementById('searchBtn');

        // 1. Suche bei Enter
        input.addEventListener('keypress', (e) => {
            if (e.key === 'Enter') this.search();
        });

        btn.onclick = () => this.search();
    }

    async search() {
        const query = this.shadowRoot.getElementById('query').value;
        const status = this.shadowRoot.getElementById('status');
        const resPre = this.shadowRoot.getElementById('results');
        const details = this.shadowRoot.getElementById('resultContainer');

        if (!query.trim()) return;

        status.className = "status-msg loading";
        status.innerHTML = "🔍 Suche läuft und wird kopiert...";
        details.style.display = "none";

        try {
            const response = await fetch(`./prjxp/tools/context?userQuestion=${encodeURIComponent(query)}`);
            this.lastContext = await response.text();

            // Ergebnis anzeigen
            resPre.textContent = this.lastContext;
            details.style.display = "block";
            details.open = false; // Standardmäßig geschlossen

            // 2. Direkt in die Zwischenablage kopieren
            await this.copyToClipboard(this.lastContext);

            status.className = "status-msg success";
            status.innerHTML = "✅ Kontext gefunden und in Zwischenablage kopiert!";
        } catch (err) {
            status.innerHTML = "❌ Fehler bei der Suche.";
            console.error(err);
        }
    }

    async copyToClipboard(text) {
        try {
            await navigator.clipboard.writeText(text);
        } catch (err) {
            console.error('Kopieren fehlgeschlagen', err);
            this.shadowRoot.getElementById('status').innerHTML = "⚠️ Suche OK, aber Clipboard-Zugriff verweigert (HTTPS?)";
        }
    }
}

customElements.define('oragel-search', OragelSearch);
