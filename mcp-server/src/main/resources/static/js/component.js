class OragelSearch extends HTMLElement {
    constructor() {
        super();
        this.attachShadow({mode: 'open'});
        this.lastContext = "";
    }

    static get observedAttributes() {
        return ['projects'];
    }

    get projects() {
        const attr = this.getAttribute('projects');
        return attr ? attr.split(',').map(p => p.trim()) : [];
    }

    async connectedCallback() {
        const projectOptions = this.projects.map((p, i) =>
            `<option value="${p}" ${i === 0 ? 'selected' : ''}>${p}</option>`
        ).join('');

        this.shadowRoot.innerHTML = `
            <style>
                :host { font-family: sans-serif; display: block; padding: 20px; border: 1px solid #00529b; border-radius: 8px; background: #fff; }
                .search-box { display: flex; gap: 10px; margin-bottom: 15px; }
                input { flex-grow: 1; padding: 10px; border: 1px solid #ccc; border-radius: 4px; font-size: 1rem; }
                select { padding: 10px; border: 1px solid #ccc; border-radius: 4px; font-size: 1rem; }
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
                <select id="projectSelect">${projectOptions}</select>
                <input type="text" id="query" placeholder="Frage an den Project Expert (Enter zum Suchen)...">
                <button id="searchBtn">Suchen</button>
            </div>

            <div class="param-row" style="display:flex; gap:15px; margin-bottom:10px; font-size:0.9rem;">
                <label>Similarity
                    <input type="range" id="similarity" min="0.5" max="1" step="0.05" value="0.85">
                    <span id="similarityVal">0.85</span>
                </label>
                <label>Max results <input type="number" id="maxResults" min="1" max="20" value="20"></label>
                <label><input type="checkbox" id="skeletonsOnly"> Skeletons only</label>
            </div>

            <div id="status" class="status-msg"></div>

            <details id="resultContainer" style="display: none;">
                <summary>Gefundener Kontext (wurde automatisch kopiert)</summary>
                <pre id="results"></pre>
            </details>
        `;

        const input = this.shadowRoot.getElementById('query');
        const btn = this.shadowRoot.getElementById('searchBtn');

        input.addEventListener('keypress', (e) => {
            if (e.key === 'Enter') this.search();
        });

        btn.onclick = () => this.search();

        const sim = this.shadowRoot.getElementById('similarity');
        sim.addEventListener('input', () => {
            this.shadowRoot.getElementById('similarityVal').textContent = sim.value;
        });

        await this.populateProjects();
        setInterval(() => this.refreshProjects(), 10_000);
    }

    async populateProjects() {
        const select = this.shadowRoot.getElementById('projectSelect');
        if (this.projects.length > 0) return;   // explicit 'projects' attribute wins
        try {
            const scriptUrl = new URL(import.meta.url);
            const basePath = scriptUrl.pathname.replace('/js/component.js', '');
            const res = await fetch(`${basePath}/prjxp/tools/projects`);
            if (!res.ok) throw new Error('HTTP ' + res.status);
            const data = await res.json();
            if (Array.isArray(data) && data.length > 0) {
                const projects = data.map(p => (typeof p === 'string')
                    ? { name: p, status: 'READY', lastError: null } : p);
                const firstReady = projects.findIndex(p => (p.status || 'READY') === 'READY');
                const selIdx = firstReady >= 0 ? firstReady : 0;
                select.innerHTML = projects.map((p, i) => {
                    const ready = (p.status || 'READY') === 'READY';
                    const label = p.name + (ready ? '' : ` (${(p.status || '').toLowerCase()})`);
                    return `<option value="${p.name}" ${i === selIdx ? 'selected' : ''} ${ready ? '' : 'disabled'}>${label}</option>`;
                }).join('');
            } else {
                select.innerHTML = '<option value="default">default</option>';
            }
        } catch (err) {
            console.error('Project list fetch failed', err);
            select.innerHTML = '<option value="default">default</option>';
        }
    }

    async refreshProjects() {
        const select = this.shadowRoot.getElementById('projectSelect');
        if (this.projects.length > 0) return;   // explicit 'projects' attribute wins
        const current = select.value;
        try {
            const scriptUrl = new URL(import.meta.url);
            const basePath = scriptUrl.pathname.replace('/js/component.js', '');
            const res = await fetch(`${basePath}/prjxp/tools/projects`);
            if (!res.ok) throw new Error('HTTP ' + res.status);
            const data = await res.json();
            if (Array.isArray(data) && data.length > 0) {
                const projects = data.map(p => (typeof p === 'string')
                    ? { name: p, status: 'READY', lastError: null } : p);
                const presentIdx = projects.findIndex(p => p.name === current);
                const firstReady = projects.findIndex(p => (p.status || 'READY') === 'READY');
                const selIdx = presentIdx >= 0 ? presentIdx : (firstReady >= 0 ? firstReady : 0);
                select.innerHTML = projects.map((p, i) => {
                    const ready = (p.status || 'READY') === 'READY';
                    const label = p.name + (ready ? '' : ` (${(p.status || '').toLowerCase()})`);
                    return `<option value="${p.name}" ${i === selIdx ? 'selected' : ''} ${ready ? '' : 'disabled'}>${label}</option>`;
                }).join('');
            }
        } catch (err) {
            console.error('Project list refresh failed', err);   // keep current options on failure
        }
    }

    async search() {
        const query = this.shadowRoot.getElementById('query').value;
        const project = this.shadowRoot.getElementById('projectSelect').value;
        const status = this.shadowRoot.getElementById('status');
        const resPre = this.shadowRoot.getElementById('results');
        const details = this.shadowRoot.getElementById('resultContainer');

        if (!query.trim()) return;

        status.className = "status-msg loading";
        status.innerHTML = "🔍 Suche läuft und wird kopiert...";
        details.style.display = "none";

        try {
            const scriptUrl = new URL(import.meta.url);
            const basePath = scriptUrl.pathname.replace('/js/component.js', '');
            const params = new URLSearchParams({ project, userQuestion: query });
            params.set('similarity', this.shadowRoot.getElementById('similarity').value);
            params.set('maxResults', this.shadowRoot.getElementById('maxResults').value);
            params.set('skeletonsOnly', String(this.shadowRoot.getElementById('skeletonsOnly').checked));
            const apiUrl = `${basePath}/prjxp/tools/context?${params.toString()}`;

            const response = await fetch(apiUrl);
            this.lastContext = await response.text();

            resPre.textContent = this.lastContext;
            details.style.display = "block";
            details.open = false;

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
