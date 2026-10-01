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
                button.danger { background: #c0392b; }
                button.danger:hover { background: #96281b; }
                .manage-row { display: flex; gap: 10px; align-items: center; padding: 10px; }
                .manage-row select { flex-grow: 1; }
                .modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.5); display: none; align-items: center; justify-content: center; z-index: 1000; }
                .modal-card { background: #fff; border-radius: 8px; padding: 20px; max-width: 480px; box-shadow: 0 4px 20px rgba(0,0,0,0.3); }
                .modal-card h2 { margin: 0 0 10px; font-size: 1.1rem; color: #c0392b; }
                .modal-card p { margin: 0 0 15px; font-size: 0.95rem; line-height: 1.4; }
                .modal-actions { display: flex; gap: 10px; justify-content: flex-end; }
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

            <details id="manageContainer">
                <summary>Projekte verwalten</summary>
                <div class="manage-row">
                    <select id="manageProjectSelect"></select>
                    <button id="deleteBtn" class="danger">Delete</button>
                </div>
            </details>

            <div id="status" class="status-msg"></div>

            <details id="resultContainer" style="display: none;">
                <summary>Gefundener Kontext (wurde automatisch kopiert)</summary>
                <pre id="results"></pre>
            </details>

            <div id="deleteModal" class="modal-overlay" role="dialog" aria-modal="true">
                <div class="modal-card">
                    <h2 id="deleteModalTitle"></h2>
                    <p id="deleteModalText"></p>
                    <div class="modal-actions">
                        <button id="deleteCancelBtn">Abbruch</button>
                        <button id="deleteConfirmBtn" class="danger">Delete</button>
                    </div>
                </div>
            </div>
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

        const deleteBtn = this.shadowRoot.getElementById('deleteBtn');
        deleteBtn.onclick = () => this.openDeleteModal();

        const cancelBtn = this.shadowRoot.getElementById('deleteCancelBtn');
        cancelBtn.onclick = () => this.closeDeleteModal();

        const confirmBtn = this.shadowRoot.getElementById('deleteConfirmBtn');
        confirmBtn.onclick = () => this.confirmDelete();

        await this.populateProjects();
        setInterval(() => this.refreshProjects(), 10_000);
    }

    async populateProjects() {
        const select = this.shadowRoot.getElementById('projectSelect');
        const manageSelect = this.shadowRoot.getElementById('manageProjectSelect');
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
                select.innerHTML = projects.map((p, i) => this.projectOption(p, i === selIdx, true)).join('');
                manageSelect.innerHTML = projects.map((p, i) => this.projectOption(p, i === selIdx, false)).join('');
            } else {
                select.innerHTML = '<option value="default">default</option>';
                manageSelect.innerHTML = '<option value="default" disabled>default</option>';
            }
        } catch (err) {
            console.error('Project list fetch failed', err);
            select.innerHTML = '<option value="default">default</option>';
            manageSelect.innerHTML = '<option value="default" disabled>default</option>';
        }
    }

    async refreshProjects() {
        const select = this.shadowRoot.getElementById('projectSelect');
        const manageSelect = this.shadowRoot.getElementById('manageProjectSelect');
        if (this.projects.length > 0) return;   // explicit 'projects' attribute wins
        const current = select.value;
        const manageCurrent = manageSelect.value;
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
                const mPresentIdx = projects.findIndex(p => p.name === manageCurrent);
                const mSelIdx = mPresentIdx >= 0 ? mPresentIdx : selIdx;
                select.innerHTML = projects.map((p, i) => this.projectOption(p, i === selIdx, true)).join('');
                manageSelect.innerHTML = projects.map((p, i) => this.projectOption(p, i === mSelIdx, false)).join('');
            }
        } catch (err) {
            console.error('Project list refresh failed', err);   // keep current options on failure
        }
    }

    projectOption(p, selected, disableNonReady) {
        const ready = (p.status || 'READY') === 'READY';
        const label = p.name + (ready ? '' : ` (${(p.status || '').toLowerCase()})`);
        const disabled = (disableNonReady && !ready) ? 'disabled' : '';
        return `<option value="${p.name}" ${selected ? 'selected' : ''} ${disabled}>${label}</option>`;
    }

    openDeleteModal() {
        const name = this.shadowRoot.getElementById('manageProjectSelect').value;
        if (!name || name === 'default') return;   // no valid selection -> ignore click
        this.shadowRoot.getElementById('deleteModalTitle').textContent = `Projekt „${name}“ glösche?`;
        this.shadowRoot.getElementById('deleteModalText').innerHTML =
            `Achtung! Mit Delete wird s'Projekt „${name}“ us de Embedding-Datenbank glöscht.<br>` +
            `Falls es ein Live-Projekt isch, wird s' automatisch wieder neu iigbettet (Chunking + Embedding) — das cha e chli wiere.<br>` +
            `Wotsch du s'Projekt wirklich glösche?`;
        this.shadowRoot.getElementById('deleteConfirmBtn').disabled = false;   // re-enable after a previous in-flight delete
        this.shadowRoot.getElementById('deleteModal').style.display = 'flex';
    }

    closeDeleteModal() {
        this.shadowRoot.getElementById('deleteModal').style.display = 'none';
        this.shadowRoot.getElementById('manageContainer').open = false;   // "wie nichts passiert"
    }

    async confirmDelete() {
        const name = this.shadowRoot.getElementById('manageProjectSelect').value;
        if (!name || name === 'default') { this.closeDeleteModal(); return; }

        const status = this.shadowRoot.getElementById('status');
        this.shadowRoot.getElementById('deleteConfirmBtn').disabled = true;   // double-click guard while in flight

        let ok = false;
        try {
            const scriptUrl = new URL(import.meta.url);
            const basePath = scriptUrl.pathname.replace('/js/component.js', '');
            const res = await fetch(`${basePath}/prjxp/projects/${encodeURIComponent(name)}`, { method: 'DELETE' });
            if (!res.ok) throw new Error('HTTP ' + res.status);
            ok = true;
        } catch (err) {
            console.error('Project delete failed', err);
        }

        this.closeDeleteModal();          // per spec: modal + section disappear after the request
        await this.refreshProjects();     // immediate update of both selects (don't wait for the 10 s poll)

        if (ok) {
            status.className = "status-msg success";
            status.innerHTML = `✅ Projekt „${name}“ gelöscht.`;
        } else {
            status.className = "status-msg";
            status.innerHTML = `❌ Projekt „${name}“ konnte nicht gelöscht werden.`;
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
