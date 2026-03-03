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

    function initGeminiTree() {
        let hasAutoScrolled = false;

        setInterval(() => {
            if (!states.isGlobalEnabled || !states.enableGeminiTree) return;

            const chatReady = document.querySelector('user-query');
            const alreadyInjected = document.getElementById('chat-tree-panel');

            if (chatReady && !alreadyInjected) {
                buildChatTreePanel();
            }
            queueTreeBuild();

            if (states.enableGeminiScroll && !hasAutoScrolled && chatReady) {
                hasAutoScrolled = true;
                triggerHistoryLoad();
            }
        }, 2000);

        function triggerHistoryLoad(btnElement = null) {
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
                        if (btnElement) btnElement.textContent = '⬆️';
                        queueTreeBuild();
                    }
                }
            }, 800);
        }

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

            const loadAllBtn = document.createElement('button');
            loadAllBtn.textContent = '⬆️';
            loadAllBtn.title = "Scroll to top to load full history";
            loadAllBtn.style.cssText = 'background:none; border:none; cursor:pointer; font-size:16px; padding:0;';
            loadAllBtn.onclick = () => triggerHistoryLoad(loadAllBtn);
            controls.appendChild(loadAllBtn);

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
        let lastTurnCount = 0;

        function queueTreeBuild() {
            if (rebuildQueued) return;
            rebuildQueued = true;

            requestAnimationFrame(() => {
                rebuildQueued = false;

                // Zoek nu naar ZOWEL de user prompts ALS de AI antwoorden in chronologische volgorde
                const elements = Array.from(document.querySelectorAll('user-query, message-content'));
                const userQueries = elements.filter(el => el.tagName.toLowerCase() === 'user-query');

                const content = document.getElementById('chat-tree-content');
                if (!content) return;

                if (userQueries.length === lastTurnCount && content.innerHTML !== '') return;
                lastTurnCount = userQueries.length;

                content.innerHTML = '';
                content.appendChild(content.querySelector('.chat-tree-resize-handle') || document.createElement('div'));

                const list = document.createElement('ul');
                let count = 0;
                let lastAiText = "";

                elements.forEach((node) => {
                    if (node.tagName.toLowerCase() === 'message-content') {
                        // Sla de tekst van de AI tijdelijk op
                        lastAiText = (node.innerText || node.textContent).trim();
                    } else if (node.tagName.toLowerCase() === 'user-query') {
                        // We hebben een prompt gevonden!
                        const text = node.innerText || node.textContent;
                        const cleanText = text.trim();
                        if (!cleanText) return;

                        const singleLineText = cleanText.replace(/\n/g, ' ');
                        const item = document.createElement('li');
                        item.className = 'chat-tree-item';
                        item.textContent = `${++count}: ${singleLineText.slice(0, 50)}...`;

                        // ==========================================
                        // HOVER TOOLTIP (Vorige AI vraag + Huidige Prompt)
                        // ==========================================
                        let tooltip = "";

                        if (lastAiText) {
                            // Verwijder extreem lange witregels uit het AI antwoord
                            let cleanAi = lastAiText.replace(/\n{3,}/g, '\n\n');
                            // Pak de laatste 250 characters (waar meestal de afsluitende vraag staat)
                            let aiSnippet = cleanAi.length > 250 ? "..." + cleanAi.slice(-250) : cleanAi;
                            tooltip += `🤖 PREVIOUS AI ENDING:\n${aiSnippet.trim()}\n\n──────────────\n\n`;
                        } else if (count === 1) {
                            tooltip += `🤖 PREVIOUS AI ENDING:\n(First prompt - no previous context)\n\n──────────────\n\n`;
                        }

                        let userSnippet = singleLineText.length > 150 ? singleLineText.slice(0, 150) + "..." : singleLineText;
                        tooltip += `📝 YOUR PROMPT:\n${userSnippet}`;

                        item.title = tooltip;
                        item.onclick = () => node.scrollIntoView({ behavior: 'smooth', block: 'center' });
                        list.appendChild(item);

                        // Reset de AI tekst (voor het geval je twee keer achter elkaar een prompt stuurt zonder AI antwoord)
                        lastAiText = "";
                    }
                });

                content.appendChild(list);
            });
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
                    const newLeft = e.clientX - offsetX;
                    const newTop = e.clientY - offsetY;
                    element.style.left = newLeft + 'px';
                    element.style.top = newTop + 'px';
                    localStorage.setItem('chatTreePosGemini', JSON.stringify({left: newLeft, top: newTop}));
                }
            });
        }
    }
})();