(() => {
    const fixNumberingStyle = document.createElement('style');
    fixNumberingStyle.textContent = `
      body { counter-reset: katexEqnNo 0; }
      .katex-display { counter-reset: none !important; }
    `;
    document.head.appendChild(fixNumberingStyle);

    let states = {
        enableEq: true,
        enableBlock: true,
        enableInline: true,
        enableDoubleDollar: true,
        enableSingleDollar: true,
        enableProse: true,
        isGlobalEnabled: true,
        renderEverywhere: false,
        activateChatgptCss: true,
        activateGeminiCss: true,
        customMacros: {}
    };

    let optionalCssLink = null;

    function applyOptionalCss() {
        const host = location.hostname;
        const id = 'livelatex-optional-css';
        const existing = document.getElementById(id);
        if (existing) existing.remove();
        optionalCssLink = null;

        let href = null;
        if (host.includes('chatgpt.com') && states.activateChatgptCss) href = chrome.runtime.getURL('chatgpt.css');
        if (host.includes('gemini.google.com') && states.activateGeminiCss) href = chrome.runtime.getURL('gemini.css');

        if (href) {
            optionalCssLink = document.createElement('link');
            optionalCssLink.id = id;
            optionalCssLink.rel = 'stylesheet';
            optionalCssLink.href = href;
            (document.head || document.documentElement).appendChild(optionalCssLink);
        }
    }

    chrome.storage.local.get(states, (result) => {
        states = result;
        applyOptionalCss();
        startRepeater();
    });

    chrome.storage.onChanged.addListener((changes) => {
        for (let key in changes) {
            if (key in states) states[key] = changes[key].newValue;
        }
        if (changes.activateChatgptCss || changes.activateGeminiCss) {
            applyOptionalCss();
        }
        if ((changes.isGlobalEnabled && changes.isGlobalEnabled.newValue === false) || changes.customMacros || changes.renderEverywhere) {
            derenderLaTeX();
        }
    });

    chrome.runtime.onMessage.addListener((request) => {
        if (request.action === "DERENDER") derenderLaTeX();
    });

    function startRepeater() {
        setInterval(() => {
            if (states.isGlobalEnabled && Object.values(states).some(v => v === true)) {
                renderLaTeX();
            }
        }, 800);
    }

    function renderMode(latex, isDisplay) {
        try {
            let rawLatex = latex
                .replace(/&amp;/g, "&")
                .replace(/&lt;/g, "<")
                .replace(/&gt;/g, ">")
                .replace(/′/g, "'")     // Fix 1: Vertaal Unicode prime direct naar LaTeX apostrof
                .replace(/″/g, "''");   // Fix 1b: Vertaal Unicode dubbele prime

            // Verwijder \label om crashes te voorkomen in losse renders
            rawLatex = rawLatex.replace(/\\label\{[^}]*\}/g, "");

            return katex.renderToString(rawLatex.trim(), {
                displayMode: isDisplay,
                macros: states.customMacros,
                throwOnError: false,
                strict: "ignore",       // Fix 2: Negeer alle strikte LaTeX waarschuwingen (zoals \\ in display mode)
                trust: true             // Sta veilige HTML en styling commands toe
            });
        } catch (error) {
            return `<span style="color:red;">[KaTeX Error: ${error.message}]</span>`;
        }
    }

    function escapeHtml(str) {
        return str.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
    }

    function replaceBalanced(text, prefixRegexStr, replacer) {
        let regex = new RegExp(prefixRegexStr, "g");
        let modified = true;
        let currentText = text;
        let maxDepth = 10;

        while (modified && maxDepth > 0) {
            modified = false;
            let out = "";
            let lastIdx = 0;
            let match;

            regex.lastIndex = 0;
            while ((match = regex.exec(currentText)) !== null) {
                let start = match.index;
                let openBraceIdx = start + match[0].length - 1;

                if (currentText[openBraceIdx] !== '{') continue;

                let depth = 1;
                let closeBraceIdx = -1;
                for (let i = openBraceIdx + 1; i < currentText.length; i++) {
                    if (currentText[i] === '\\') { i++; continue; }
                    if (currentText[i] === '{') depth++;
                    else if (currentText[i] === '}') depth--;

                    if (depth === 0) { closeBraceIdx = i; break; }
                }

                if (closeBraceIdx !== -1) {
                    out += currentText.substring(lastIdx, start);
                    let content = currentText.substring(openBraceIdx + 1, closeBraceIdx);
                    out += replacer(match, content);
                    lastIdx = closeBraceIdx + 1;
                    regex.lastIndex = lastIdx;
                    modified = true;
                }
            }
            out += currentText.substring(lastIdx);
            currentText = out;
            maxDepth--;
        }
        return currentText;
    }

    function renderLaTeX() {
        if (typeof katex === 'undefined') return;

        const envRegex = /\\begin\{(equation|align|gather|multline|eqnarray)(\*?)\}([\s\S]*?)\\end\{\1\2\}/g;
        const blockRegex = /\\\[([\s\S]*?)\\\]/g;
        const doubleDollarRegex = /\$\$([\s\S]*?)\$\$/g;
        const inlineRegex = /\\\(([\s\S]*?)\\\)/g;
        const singleDollarRegex = /\$((?:[^$]|\\$)+?)\$/g;

        const codeContainers = document.querySelectorAll('code[data-test-id="code-content"], .cm-content');

        codeContainers.forEach(container => {
            if (container.nextElementSibling && container.nextElementSibling.classList.contains('livelatex-overlay')) return;
            if (!container.innerText || container.innerText.trim() === '') return;
            if (!container.innerText.includes('\\') && !container.innerText.includes('$')) return;

            let text = container.innerText;
            let htmlText = escapeHtml(text);

            if (states.enableProse) {
                // 1. tcolorbox
                htmlText = htmlText.replace(/\\begin\{tcolorbox\}(?:\[(.*?)\])?([\s\S]*?)\\end\{tcolorbox\}/g, (match, opts, body) => {
                    let title = "";
                    if (opts) {
                        const titleMatch = opts.match(/title\s*=\s*\{([^}]+)\}/) || opts.match(/title\s*=\s*([^,]+)/);
                        if (titleMatch) title = titleMatch[1];
                    }
                    const titleHtml = title ? `<div style="font-weight:bold;margin-bottom:8px;color:#8ab4f8;font-size:1.1em;border-bottom:1px solid #3c4043;padding-bottom:4px;">${title}</div>` : "";
                    return `<div style="background:#202124; border:1px solid #3c4043; border-left:4px solid #1a73e8; border-radius:6px; padding:12px; margin:16px 0; box-shadow:0 2px 6px rgba(0,0,0,0.2);">${titleHtml}<div style="color:#e8eaed;">${body.trim()}</div></div>`;
                });

                // 2. Tabulars
                htmlText = htmlText.replace(/\\begin\{tabular\}\{[^}]*\}([\s\S]*?)\\end\{tabular\}/g, (match, body) => {
                    let cleanBody = body.replace(/\\toprule|\\midrule|\\bottomrule|\\hline/g, '').replace(/\\arraystretch[^\n]*/g, '');
                    let rows = cleanBody.split(/\\\\/).map(r => r.trim()).filter(r => r);
                    let trs = rows.map(r => {
                        let tds = r.split('&').map(c => `<td style="padding:6px 12px; border:1px solid #5f6368;">${c.trim()}</td>`).join('');
                        return `<tr>${tds}</tr>`;
                    }).join('');
                    return `<table style="border-collapse:collapse; margin:16px 0; width:100%; font-size:0.95em; color:#e8eaed;">${trs}</table>`;
                });

                // 3. Figures & Tables Envs
                htmlText = htmlText.replace(/\\begin\{figure\}(?:\[[^\]]*\])?([\s\S]*?)\\end\{figure\}/g, '<figure style="margin:16px 0; text-align:center;">$1</figure>');
                htmlText = htmlText.replace(/\\begin\{table\}(?:\[[^\]]*\])?([\s\S]*?)\\end\{table\}/g, '<div style="margin:16px 0; overflow-x:auto;">$1</div>');
                htmlText = htmlText.replace(/\\centering/g, '');

                htmlText = replaceBalanced(htmlText, "\\\\caption\\{", (m, c) => `<div style="opacity:0.8; font-size:0.85em; margin-top:8px; text-align:center; font-style:italic;">${c}</div>`);

                // 4. Lijsten
                htmlText = htmlText.replace(/\\begin\{itemize\}(?:\[[^\]]*\])?([\s\S]*?)\\end\{itemize\}/g, (match, body) => {
                    let items = body.split(/(?:^|\n)\s*\\item\s*/).filter(i => i.trim() !== '');
                    return `<ul style="margin:12px 0 12px 24px; list-style-type:disc;">` + items.map(i => `<li style="margin-bottom:6px;">${i.trim()}</li>`).join('') + `</ul>`;
                });
                htmlText = htmlText.replace(/\\begin\{enumerate\}(?:\[[^\]]*\])?([\s\S]*?)\\end\{enumerate\}/g, (match, body) => {
                    let items = body.split(/(?:^|\n)\s*\\item\s*/).filter(i => i.trim() !== '');
                    return `<ol style="margin:12px 0 12px 24px; list-style-type:decimal;">` + items.map(i => `<li style="margin-bottom:6px;">${i.trim()}</li>`).join('') + `</ol>`;
                });

                // 5. Sections
                htmlText = replaceBalanced(htmlText, "\\\\section\\*?\\{", (m, c) => `<h2 style="font-size:1.5em; color:#8ab4f8; font-weight:bold; margin-top:1.5em; margin-bottom:0.5em; border-bottom:1px solid #3c4043; padding-bottom:6px;">${c}</h2>`);
                htmlText = replaceBalanced(htmlText, "\\\\subsection\\*?\\{", (m, c) => `<h3 style="font-size:1.25em; color:#8ab4f8; font-weight:bold; margin-top:1.2em; margin-bottom:0.5em;">${c}</h3>`);
                htmlText = replaceBalanced(htmlText, "\\\\subsubsection\\*?\\{", (m, c) => `<h4 style="font-size:1.1em; color:#8ab4f8; font-weight:bold; margin-top:1em; margin-bottom:0.5em;">${c}</h4>`);
                htmlText = replaceBalanced(htmlText, "\\\\paragraph\\*?\\{", (m, c) => `<h5 style="font-size:1em; color:#e8eaed; font-weight:bold; margin-top:1em; margin-bottom:0.2em;">${c}</h5>`);

                // 6. Tekst Formatteren
                htmlText = replaceBalanced(htmlText, "\\\\textbf\\{", (m, c) => `<strong style="color:#ffffff;">${c}</strong>`);
                htmlText = replaceBalanced(htmlText, "\\\\textit\\{", (m, c) => `<em>${c}</em>`);
                htmlText = replaceBalanced(htmlText, "\\\\emph\\{", (m, c) => `<em>${c}</em>`);
                htmlText = replaceBalanced(htmlText, "\\\\underline\\{", (m, c) => `<u>${c}</u>`);
                htmlText = replaceBalanced(htmlText, "\\\\texttt\\{", (m, c) => `<code style="background:#303134; padding:2px 4px; border-radius:4px;">${c}</code>`);

                htmlText = htmlText.replace(/\\verb\*?(.)(.*?)\1/g, '<code style="background:#303134; padding:2px 4px; border-radius:4px;">$2</code>');
            }

            if (states.enableEq) htmlText = htmlText.replace(envRegex, (match) => renderMode(match, true));
            if (states.enableBlock) htmlText = htmlText.replace(blockRegex, (_, latex) => renderMode(latex, true));
            if (states.enableDoubleDollar) htmlText = htmlText.replace(doubleDollarRegex, (_, latex) => renderMode(latex, true));
            if (states.enableInline) htmlText = htmlText.replace(inlineRegex, (_, latex) => renderMode(latex, false));
            if (states.enableSingleDollar) htmlText = htmlText.replace(singleDollarRegex, (_, latex) => renderMode(latex, false));

            const wrapper = document.createElement('div');
            wrapper.className = 'livelatex-overlay';
            wrapper.innerHTML = htmlText;
            wrapper.style.cssText = `white-space: pre-wrap; font-family: monospace; padding: 16px; background-color: transparent; color: inherit; overflow-x: auto;`;

            container.style.display = 'none';
            container.parentNode.insertBefore(wrapper, container.nextSibling);
        });

        const richContainers = states.renderEverywhere
            ? [document.body]
            : document.querySelectorAll('message-content, .message-content');
        richContainers.forEach(container => {
            const treeWalker = document.createTreeWalker(container, NodeFilter.SHOW_TEXT, {
                acceptNode: function(node) {
                    const parent = node.parentNode;
                    if (!parent || ['SCRIPT', 'STYLE'].includes(parent.tagName)) return NodeFilter.FILTER_REJECT;
                    if (parent.closest('.katex') || parent.closest('.livelatex-render-wrapper')) return NodeFilter.FILTER_REJECT;
                    if (node.nodeValue.includes('\\') || node.nodeValue.includes('$')) return NodeFilter.FILTER_ACCEPT;
                    return NodeFilter.FILTER_SKIP;
                }
            });

            let nodesToProcess = [];
            let node;
            while ((node = treeWalker.nextNode())) nodesToProcess.push(node);

            nodesToProcess.forEach(textNode => {
                let text = textNode.nodeValue;
                let updatedText = text;
                const parent = textNode.parentNode;

                if (states.enableEq) {
                    updatedText = updatedText.replace(envRegex, (match) => {
                        const safeOriginal = match.replace(/"/g, '&quot;');
                        return `<span class="livelatex-render-wrapper" data-original="${safeOriginal}">${renderMode(match, true)}</span>`;
                    });
                }
                if (states.enableBlock) {
                    updatedText = updatedText.replace(blockRegex, (_, latex) =>
                        `<span class="livelatex-render-wrapper" data-original="\\[${latex.replace(/"/g, '&quot;')}\\]">${renderMode(latex, true)}</span>`
                    );
                }
                if (states.enableDoubleDollar) {
                    updatedText = updatedText.replace(doubleDollarRegex, (_, latex) =>
                        `<span class="livelatex-render-wrapper" data-original="$$${latex.replace(/"/g, '&quot;')}$$">${renderMode(latex, true)}</span>`
                    );
                }
                if (states.enableInline) {
                    updatedText = updatedText.replace(inlineRegex, (_, latex) =>
                        `<span class="livelatex-render-wrapper" data-original="\\(${latex.replace(/"/g, '&quot;')}\\)">${renderMode(latex, false)}</span>`
                    );
                }
                if (states.enableSingleDollar) {
                    updatedText = updatedText.replace(singleDollarRegex, (match, latex, offset, string) => {
                        const doubleDollarBefore = string.lastIndexOf('$$', offset);
                        const doubleDollarAfter = string.indexOf('$$', offset + match.length);
                        if (doubleDollarBefore !== -1 && doubleDollarAfter !== -1 && doubleDollarBefore < offset && doubleDollarAfter > offset) return match;
                        const before = string.slice(0, offset);
                        const after = string.slice(offset + match.length);
                        if (/[.!?]\s*$/.test(before) || /^\s*[.!?]/.test(after)) return match;

                        return `<span class="livelatex-render-wrapper" data-original="${match.replace(/"/g, '&quot;')}">${renderMode(latex, false)}</span>`;
                    });
                }

                if (updatedText !== text) {
                    const wrapper = document.createElement("span");
                    wrapper.innerHTML = updatedText;
                    parent.replaceChild(wrapper, textNode);
                }
            });
        });
    }

    function derenderLaTeX() {
        document.querySelectorAll('.livelatex-render-wrapper').forEach((element) => {
            const originalContent = element.getAttribute('data-original');
            if (originalContent) {
                element.replaceWith(document.createTextNode(originalContent));
            }
        });

        document.querySelectorAll('.livelatex-overlay').forEach(overlay => {
            const originalContainer = overlay.previousElementSibling;
            if (originalContainer) {
                originalContainer.style.display = '';
            }
            overlay.remove();
        });
    }
})();