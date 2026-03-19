document.addEventListener('DOMContentLoaded', () => {
    const toggles = {
        enableEq: document.getElementById('eqToggle'),
        enableBlock: document.getElementById('blockToggle'),
        enableInline: document.getElementById('inlineToggle'),
        enableDoubleDollar: document.getElementById('doubleDollarToggle'),
        enableSingleDollar: document.getElementById('singleDollarToggle'),
        enableProse: document.getElementById('proseToggle'),
        renderEverywhere: document.getElementById('renderEverywhereToggle'),
        enableChatTree: document.getElementById('treeToggle'),
        enableSidebarSort: document.getElementById('sortToggle'),
        enableAutoScroll: document.getElementById('scrollToggle'),
        enableGeminiTree: document.getElementById('geminiTreeToggle'),
        enableGeminiScroll: document.getElementById('geminiScrollToggle'),
        activateChatgptCss: document.getElementById('activateChatgptCss'),
        activateGeminiCss: document.getElementById('activateGeminiCss')
    };

    const btnSelectAll = document.getElementById('btnSelectAll');
    const btnGlobalToggle = document.getElementById('btnGlobalToggle');
    const appBody = document.getElementById('appBody');

    const macroContainer = document.getElementById('macroContainer');
    const btnAddMacro = document.getElementById('btnAddMacro');

    // Standaard SST macro's voor bij de eerste installatie
    const defaultStates = {
        enableEq: true, enableBlock: true, enableInline: true, enableDoubleDollar: true, enableSingleDollar: true, isGlobalEnabled: true, enableProse: true,
        renderEverywhere: false,
        enableChatTree: true,
        enableSidebarSort: true,
        enableAutoScroll: false, enableGeminiTree: true,
        activateChatgptCss: true,
        activateGeminiCss: true,
        customMacros: {
            "\\vswirl": "\\mathbf{v}_{\\!\\scriptscriptstyle\\boldsymbol{\\circlearrowleft}}",
            "\\rhocore": "\\rho_{\\text{core}}",
            "\\rhom": "\\rho_{\\!m}",
            "\\rhof": "\\rho_{\\!f}",
            "\\rhoE": "\\rho_{\\!E}",
            "\\rc": "r_c",
            "\\Lc": "\\lambda_c",
            "\\vswirltext": "\\mathbf{v}_{\\mathrm{swirl}}",
            "\\vscore": "\\mathbf{v}_{\\swirlarrow\\text{(core)}}",
            "\\vnorm": "\\lVert \\mathbf{v}_{\\scriptscriptstyle\\boldsymbol{\\circlearrowleft}} \\rVert",
            "\\Fmaxswirl": "F^{max}_{\\!\\scriptscriptstyle\\boldsymbol{\\circlearrowleft}}",
            "\\SwirlClock": "S_{(t)}^{\\!\\scriptscriptstyle\\boldsymbol{\\circlearrowleft}}"
        }
    };


    chrome.storage.local.get(defaultStates, (result) => {
        for (const key in toggles) toggles[key].checked = result[key];
        updateGlobalUI(result.isGlobalEnabled);
        updateSelectAllText();
        renderMacroList(result.customMacros);
    });

    for (const key in toggles) {
        toggles[key].addEventListener('change', (e) => {
            chrome.storage.local.set({ [key]: e.target.checked });
            updateSelectAllText();
        });
    }

    // --- (De-)Select All ---
    btnSelectAll.addEventListener('click', () => {
        const allChecked = Object.values(toggles).every(t => t.checked);
        const newState = !allChecked;
        let updates = {};
        for (const key in toggles) { toggles[key].checked = newState; updates[key] = newState; }
        chrome.storage.local.set(updates);
        updateSelectAllText();
    });

    function updateSelectAllText() {
        const allChecked = Object.values(toggles).every(t => t.checked);
        btnSelectAll.innerText = allChecked ? "Deselect All" : "Select All";
    }

    // --- Globale Toggle ---
    btnGlobalToggle.addEventListener('click', () => {
        chrome.storage.local.get(['isGlobalEnabled'], (res) => {
            const newState = !(res.isGlobalEnabled ?? true);
            chrome.storage.local.set({ isGlobalEnabled: newState });
            updateGlobalUI(newState);
            if (!newState) {
                chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
                    if (tabs[0]) chrome.tabs.sendMessage(tabs[0].id, { action: "DERENDER" });
                });
            }
        });
    });

    function updateGlobalUI(isEnabled) {
        if (isEnabled) {
            btnGlobalToggle.innerText = "LiveLatex is AAN";
            btnGlobalToggle.classList.remove('off');
            appBody.classList.remove('app-disabled');
        } else {
            btnGlobalToggle.innerText = "LiveLatex is UIT";
            btnGlobalToggle.classList.add('off');
            appBody.classList.add('app-disabled');
        }
    }

    // --- Macro Manager Logica ---
    function renderMacroList(macros) {
        macroContainer.innerHTML = '';
        for (const [key, val] of Object.entries(macros)) {
            addMacroRow(key, val);
        }
    }

    function addMacroRow(key = "\\", val = "") {
        const row = document.createElement('div');
        row.className = 'macro-row';

        const inputKey = document.createElement('input');
        inputKey.className = 'macro-key';
        inputKey.type = 'text';
        inputKey.value = key;
        inputKey.placeholder = "\\macro";

        const inputVal = document.createElement('input');
        inputVal.className = 'macro-val';
        inputVal.type = 'text';
        inputVal.value = val;
        inputVal.placeholder = "Replacement";

        const btnDel = document.createElement('button');
        btnDel.className = 'btn-delete';
        btnDel.innerText = "X";
        btnDel.onclick = () => {
            row.remove();
            saveMacros();
        };

        // Sla pas op als de gebruiker klaar is met typen en het veld verlaat (change event)
        inputKey.addEventListener('change', saveMacros);
        inputVal.addEventListener('change', saveMacros);

        row.appendChild(inputKey);
        row.appendChild(inputVal);
        row.appendChild(btnDel);
        macroContainer.appendChild(row);
    }

    btnAddMacro.addEventListener('click', () => {
        addMacroRow("\\new", "");
        // Scroll automatisch naar beneden
        macroContainer.scrollTop = macroContainer.scrollHeight;
    });

    function saveMacros() {
        const newMacros = {};
        const rows = document.querySelectorAll('.macro-row');
        rows.forEach(row => {
            let k = row.querySelector('.macro-key').value.trim();
            let v = row.querySelector('.macro-val').value.trim();

            // KaTeX eist dat een macro met een backslash begint
            if (k && v) {
                if (!k.startsWith('\\')) k = '\\' + k;
                newMacros[k] = v;
            }
        });
        chrome.storage.local.set({ customMacros: newMacros });
    }
});