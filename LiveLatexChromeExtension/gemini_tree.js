(() => {
    let states = {
        isGlobalEnabled: true,
        enableGeminiTree: true
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
        setInterval(() => {
            if (!states.isGlobalEnabled || !states.enableGeminiTree) return;

            // Zoek naar Gemini's user prompts
            const chatReady = document.querySelector('user-query');
            const alreadyInjected = document.getElementById('chat-tree-panel');

            if (chatReady && !alreadyInjected) {
                buildChatTreePanel();
            }
            queueTreeBuild();
        }, 2000);

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

            const toggleBtn = document.createElement('button');
            toggleBtn.id = 'chat-tree-toggle';
            toggleBtn.textContent = '—';
            header.appendChild(toggleBtn);

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

            // Voeg resizer toe
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
                // Verzamel alle user prompts in Gemini
                const turns = Array.from(document.querySelectorAll('user-query'));

                const content = document.getElementById('chat-tree-content');
                if (!content) return;

                // Optimalisatie: Alleen de lijst hertekenen als er een nieuwe prompt is toegevoegd
                if (turns.length === lastTurnCount && content.innerHTML !== '') return;
                lastTurnCount = turns.length;

                content.innerHTML = '';
                content.appendChild(content.querySelector('.chat-tree-resize-handle') || document.createElement('div'));

                const list = document.createElement('ul');
                let count = 0;

                turns.forEach((turn) => {
                    const text = turn.innerText || turn.textContent;
                    if (!text.trim()) return;

                    const item = document.createElement('li');
                    item.className = 'chat-tree-item';
                    item.textContent = `${++count}: ${text.trim().slice(0, 60).replace(/\n/g, ' ')}`;

                    item.onclick = () => turn.scrollIntoView({ behavior: 'smooth', block: 'center' });
                    list.appendChild(item);
                });

                content.appendChild(list);
            });
        }

        function makeDraggable(element) {
            const handle = element.querySelector('#chat-tree-header') || element;
            let isDragging = false, offsetX, offsetY;

            // Haal opgeslagen positie op
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
            });

            document.addEventListener('mouseup', () => isDragging = false);
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