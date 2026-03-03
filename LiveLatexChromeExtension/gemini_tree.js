(() => {
    let states = {
        isGlobalEnabled: true,
        enableGeminiTree: true,
        enableGeminiScroll: false
    };

    chrome.storage.local.get(states, (result) => {
        states = result;
        initGeminiTree();
    });

    chrome.storage.onChanged.addListener((changes) => {
        for (let key in changes) {
            if (key in states) states[key] = changes[key].newValue;
        }
        if (!states.isGlobalEnabled || !states.enableGeminiTree) {
            const panel = document.getElementById('chat-tree-panel');
            if (panel) panel.remove();
        }
    });

    function getConversationId() {
        const match = window.location.pathname.match(/\/app\/([a-f0-9]+)/);
        return match ? match[1] : 'default_chat';
    }

    function initGeminiTree() {
        let hasAutoScrolled = false;
        let currentChatId = getConversationId();
        let storedTurns = loadTree(currentChatId);

        setInterval(() => {
            if (!states.isGlobalEnabled || !states.enableGeminiTree) return;

            const newChatId = getConversationId();
            if (newChatId !== currentChatId) {
                currentChatId = newChatId;
                storedTurns = loadTree(currentChatId);
                lastStoredLength = 0;
            }

            const chatReady = document.querySelector('user-query');
            const alreadyInjected = document.getElementById('chat-tree-panel');

            if (chatReady && !alreadyInjected) {
                buildChatTreePanel();
                renderTreeUI(storedTurns);
            }

            queueTreeBuild();

            if (states.enableGeminiScroll && !hasAutoScrolled && chatReady) {
                hasAutoScrolled = true;
                triggerHistoryLoad();
            }
        }, 2000);

        // --- TREE DATA BEHEER (Lokale Opslag) ---
        function loadTree(chatId) {
            const data = localStorage.getItem(`geminiTree_${chatId}`);
            return data ? JSON.parse(data) : [];
        }

        function saveTree(chatId, turns) {
            try {
                localStorage.setItem(`geminiTree_${chatId}`, JSON.stringify(turns));
            } catch (e) {
                console.warn("Local storage full, clearing old Gemini caches...");
                // Nood-opschoonactie als de 5MB grens bereikt wordt
                Object.keys(localStorage).forEach(key => {
                    if (key.startsWith('geminiTree_') && key !== `geminiTree_${chatId}`) {
                        localStorage.removeItem(key);
                    }
                });
                localStorage.setItem(`geminiTree_${chatId}`, JSON.stringify(turns));
            }
        }

        function mergeTurns(stored, dom) {
            if (stored.length === 0) return dom;
            if (dom.length === 0) return stored;

            let firstDomText = dom[0].userText;
            let matchIdx = -1;

            for (let i = stored.length - 1; i >= 0; i--) {
                if (stored[i].userText === firstDomText) {
                    matchIdx = i;
                    break;
                }
            }

            if (matchIdx !== -1) {
                return stored.slice(0, matchIdx).concat(dom);
            } else {
                if (dom.length >= stored.length) return dom;
                return stored;
            }
        }

        // --- AUTO-HUNTER SCOLLER ---
        let searchInterval = null;
        function scrollToTurn(targetText, btnElement) {
            if (searchInterval) clearInterval(searchInterval);

            let found = findElementByText(targetText);
            if (found) {
                executeScrollAndHighlight(found);
                return;
            }

            const originalHtml = btnElement.innerHTML;
            btnElement.innerHTML = '⏳...';

            let attempts = 0;
            let lastHeight = document.body.scrollHeight;

            searchInterval = setInterval(() => {
                window.scrollTo(0, 0);

                let foundNow = findElementByText(targetText);
                if (foundNow) {
                    clearInterval(searchInterval);
                    executeScrollAndHighlight(foundNow);
                    btnElement.innerHTML = originalHtml;
                    return;
                }

                if (document.body.scrollHeight > lastHeight) {
                    lastHeight = document.body.scrollHeight;
                    attempts = 0;
                } else {
                    attempts++;
                    if (attempts > 4) {
                        clearInterval(searchInterval);
                        btnElement.innerHTML = '❌';
                        setTimeout(() => btnElement.innerHTML = originalHtml, 2000);
                    }
                }
            }, 800);
        }

        function findElementByText(text) {
            const queries = Array.from(document.querySelectorAll('user-query'));
            return queries.find(q => (q.innerText || q.textContent).trim().replace(/\n/g, ' ') === text);
        }

        function executeScrollAndHighlight(el) {
            el.scrollIntoView({ behavior: 'smooth', block: 'center' });
            const originalBg = el.style.backgroundColor;
            el.style.transition = 'background-color 0.5s ease';
            el.style.backgroundColor = '#1a73e855';
            setTimeout(() => el.style.backgroundColor = originalBg, 2000);
        }

        // --- HANDMATIGE OPLAAD FUNCTIE & OFFLINE EXPORT ---
        function triggerHistoryLoad(btnElement = null, onCompleteCallback = null) {
            let originalText = btnElement ? btnElement.textContent : '';
            if (btnElement) btnElement.textContent = '⏳';
            let lastHeight = document.body.scrollHeight;
            let attempts = 0;

            const scrollInterval = setInterval(() => {
                window.scrollTo(0, 0);

                if (document.body.scrollHeight > lastHeight) {
                    lastHeight = document.body.scrollHeight;
                    attempts = 0;
                } else {
                    attempts++;
                    if (attempts > 3) {
                        clearInterval(scrollInterval);
                        if (btnElement) btnElement.textContent = originalText;
                        queueTreeBuild();
                        if (onCompleteCallback) onCompleteCallback();
                    }
                }
            }, 800);
        }

        function exportOfflineArchive() {
            const elements = Array.from(document.querySelectorAll('user-query, message-content'));

            let htmlContent = `
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <title>Gemini Offline Archive</title>
                    <style>
                        body { font-family: 'Google Sans', Roboto, Arial, sans-serif; background-color: #202124; color: #e8eaed; max-width: 1000px; margin: 0 auto; padding: 20px; line-height: 1.6; }
                        h1 { color: #8ab4f8; border-bottom: 1px solid #3c4043; padding-bottom: 10px; }
                        .message { margin-bottom: 24px; padding: 16px; border-radius: 8px; overflow-x: auto; }
                        .user { background-color: #303134; border-left: 4px solid #8ab4f8; }
                        .model { background-color: #171717; border-left: 4px solid #ce93d8; }
                        pre { background-color: #000; padding: 12px; border-radius: 6px; overflow-x: auto; border: 1px solid #3c4043; }
                        code { font-family: monospace; font-size: 14px; }
                        a { color: #8ab4f8; }
                        table { border-collapse: collapse; width: 100%; margin: 16px 0; }
                        td, th { border: 1px solid #5f6368; padding: 8px; }
                    </style>
                </head>
                <body>
                    <h1>Gemini Conversation Archive</h1>
                    <p><i>Exported on: ${new Date().toLocaleString()} | Chat ID: ${currentChatId}</i></p>
                    <hr>
            `;

            elements.forEach(el => {
                const isUser = el.tagName.toLowerCase() === 'user-query';
                const roleClass = isUser ? 'user' : 'model';
                const roleLabel = isUser ? '🙋‍♂️ You' : '🤖 Gemini';

                // Bij user-query pakken we de innerText omdat Gemini dat vaak onbewerkt als div injecteert.
                // Bij message-content pakken we innerHTML om tabellen, latex en code opmaak te behouden.
                let content = isUser ? el.innerText.replace(/\n/g, '<br>') : el.innerHTML;

                htmlContent += `
                    <div class="message ${roleClass}">
                        <h3 style="margin-top: 0; color: ${isUser ? '#8ab4f8' : '#ce93d8'}">${roleLabel}</h3>
                        <div class="content">${content}</div>
                    </div>
                `;
            });

            htmlContent += `</body></html>`;

            // Maak een Blob en forceer een download in Chrome
            const blob = new Blob([htmlContent], { type: 'text/html' });
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = `Gemini_Archive_${currentChatId}_${new Date().toISOString().replace(/[:.]/g, '-')}.html`;
            a.click();
            URL.revokeObjectURL(url);
        }

        // --- UI BOUWER ---
        function buildChatTreePanel() {
            const existing = document.getElementById('chat-tree-panel');
            if (existing) existing.remove();

            const root = document.createElement('div');
            root.id = 'chat-tree-panel';
            const savedSize = JSON.parse(localStorage.getItem('chatTreeSizeGemini') || 'null');
            if (savedSize) {
                root.style.width = savedSize.width + 'px';
                root.style.height = savedSize.height + 'px';
            }

            const header = document.createElement('div');
            header.id = 'chat-tree-header';
            header.textContent = '📜';

            const controls = document.createElement('div');
            controls.style.display = 'flex';
            controls.style.gap = '8px';
            controls.style.alignItems = 'center';

            // Knop 1: Forceer inladen tot top
            const loadAllBtn = document.createElement('button');
            loadAllBtn.textContent = '⬆️';
            loadAllBtn.title = "Scroll to top to load full history";
            loadAllBtn.style.cssText = 'background:none; border:none; cursor:pointer; font-size:16px; padding:0;';
            loadAllBtn.onclick = () => triggerHistoryLoad(loadAllBtn);
            controls.appendChild(loadAllBtn);

            // Knop 2: Download Offline HTML
            const exportBtn = document.createElement('button');
            exportBtn.textContent = '💾';
            exportBtn.title = "Download Full Offline Archive (.html)";
            exportBtn.style.cssText = 'background:none; border:none; cursor:pointer; font-size:16px; padding:0;';
            exportBtn.onclick = () => {
                // Eerst de history vol inladen, daarna direct downloaden!
                exportBtn.textContent = '⏳';
                triggerHistoryLoad(null, () => {
                    exportOfflineArchive();
                    exportBtn.textContent = '💾';
                });
            };
            controls.appendChild(exportBtn);

            const toggleBtn = document.createElement('button');
            toggleBtn.id = 'chat-tree-toggle';
            toggleBtn.textContent = '—';
            controls.appendChild(toggleBtn);
            header.appendChild(controls);

            const content = document.createElement('div');
            content.id = 'chat-tree-content';

            toggleBtn.addEventListener('click', () => {
                const isVisible = content.style.display !== 'none';
                content.style.display = isVisible ? 'none' : 'block';
                toggleBtn.textContent = isVisible ? '+' : '—';
                root.style.height = isVisible ? '40px' : (savedSize ? savedSize.height + 'px' : 'auto');
            });

            root.appendChild(header);
            root.appendChild(content);

            const resizeHandle = document.createElement('div');
            resizeHandle.className = 'chat-tree-resize-handle';
            resizeHandle.style.cssText = 'position:absolute; right:2px; bottom:2px; width:18px; height:18px; cursor:nwse-resize; z-index:10; border-right:3px solid #1a73e8; border-bottom:3px solid #1a73e8; border-radius:0 0 6px 0;';
            content.appendChild(resizeHandle);

            let isResizing = false, startX, startY, startW, startH;
            resizeHandle.onmousedown = (e) => {
                e.stopPropagation(); isResizing = true;
                startX = e.clientX; startY = e.clientY;
                startW = root.offsetWidth; startH = root.offsetHeight;
                document.body.style.userSelect = 'none';
            };
            document.addEventListener('mousemove', (e) => {
                if (!isResizing) return;
                root.style.width = Math.max(220, startW + (e.clientX - startX)) + 'px';
                root.style.height = Math.max(80, startH + (e.clientY - startY)) + 'px';
                content.style.height = (root.offsetHeight - 40) + 'px';
            });
            document.addEventListener('mouseup', () => {
                if (isResizing) {
                    isResizing = false; document.body.style.userSelect = '';
                    localStorage.setItem('chatTreeSizeGemini', JSON.stringify({width: root.offsetWidth, height: root.offsetHeight}));
                }
            });

            document.body.appendChild(root);
            makeDraggable(root);
        }

        let rebuildQueued = false;
        let lastStoredLength = 0;

        function queueTreeBuild() {
            if (rebuildQueued) return;
            rebuildQueued = true;

            requestAnimationFrame(() => {
                rebuildQueued = false;

                const elements = Array.from(document.querySelectorAll('user-query, message-content'));
                let domTurns = [];
                let lastAiText = "";

                elements.forEach((node) => {
                    if (node.tagName.toLowerCase() === 'message-content') {
                        lastAiText = (node.innerText || node.textContent).trim();
                    } else if (node.tagName.toLowerCase() === 'user-query') {
                        const cleanText = (node.innerText || node.textContent).trim().replace(/\n/g, ' ');
                        if (!cleanText) return;

                        let cleanAi = lastAiText.replace(/\n{3,}/g, '\n\n');
                        let aiSnippet = cleanAi.length > 300 ? "..." + cleanAi.slice(-300) : cleanAi;

                        domTurns.push({
                            userText: cleanText,
                            aiSnippet: aiSnippet.trim()
                        });
                        lastAiText = "";
                    }
                });

                storedTurns = mergeTurns(storedTurns, domTurns);
                saveTree(currentChatId, storedTurns);

                if (storedTurns.length !== lastStoredLength || document.getElementById('chat-tree-content').innerHTML === '') {
                    lastStoredLength = storedTurns.length;
                    renderTreeUI(storedTurns);
                }
            });
        }

        function renderTreeUI(turnsArray) {
            const content = document.getElementById('chat-tree-content');
            if (!content) return;

            content.innerHTML = '';
            content.appendChild(content.querySelector('.chat-tree-resize-handle') || document.createElement('div'));

            const list = document.createElement('ul');
            let count = 0;

            turnsArray.forEach((turn) => {
                count++;
                const item = document.createElement('li');
                item.className = 'chat-tree-item';

                const textSpan = document.createElement('span');
                textSpan.textContent = `${count}: ${turn.userText.slice(0, 50)}...`;
                item.appendChild(textSpan);

                let tooltip = "";
                if (turn.aiSnippet) {
                    tooltip += `🤖 PREVIOUS AI ENDING:\n${turn.aiSnippet}\n\n──────────────\n\n`;
                } else if (count === 1) {
                    tooltip += `🤖 PREVIOUS AI ENDING:\n(First prompt - no previous context)\n\n──────────────\n\n`;
                }
                let userSnippet = turn.userText.length > 150 ? turn.userText.slice(0, 150) + "..." : turn.userText;
                tooltip += `📝 YOUR PROMPT:\n${userSnippet}`;
                item.title = tooltip;

                item.onclick = () => scrollToTurn(turn.userText, textSpan);

                list.appendChild(item);
            });

            content.appendChild(list);
        }

        function makeDraggable(element) {
            const handle = element.querySelector('#chat-tree-header') || element;
            let isDragging = false, offsetX, offsetY;

            const savedPos = JSON.parse(localStorage.getItem('chatTreePosGemini') || 'null');
            if (savedPos) {
                element.style.left = savedPos.left + 'px';
                element.style.top = savedPos.top + 'px';
            }

            handle.addEventListener('mousedown', (e) => {
                if (e.target.tagName === 'BUTTON') return;
                isDragging = true;
                offsetX = e.clientX - element.getBoundingClientRect().left;
                offsetY = e.clientY - element.getBoundingClientRect().top;
                document.body.style.userSelect = 'none';
            });

            document.addEventListener('mouseup', () => {
                if (isDragging) {
                    isDragging = false;
                    document.body.style.userSelect = '';
                }
            });

            document.addEventListener('mousemove', (e) => {
                if (isDragging) {
                    const newLeft = Math.max(0, e.clientX - offsetX);
                    const newTop = Math.max(0, e.clientY - offsetY);
                    element.style.left = newLeft + 'px';
                    element.style.top = newTop + 'px';
                    localStorage.setItem('chatTreePosGemini', JSON.stringify({left: newLeft, top: newTop}));
                }
            });
        }
    }
})();