(() => {
    // 1. INJECT FETCH PATCHER
    const script = document.createElement('script');
    script.src = chrome.runtime.getURL('fetch_patch.js');
    (document.head || document.documentElement).appendChild(script);
    script.onload = () => script.remove();

    // 2. STATE BEHEER VIA EXTENSION POPUP
    let states = {
        isGlobalEnabled: true,
        enableChatTree: true,
        enableSidebarSort: true,
        enableAutoScroll: false
    };

    chrome.storage.local.get(states, (result) => {
        states = result;
        initMasterScript();
    });

    chrome.storage.onChanged.addListener((changes) => {
        for (let key in changes) {
            if (key in states) states[key] = changes[key].newValue;
        }
        if (!states.isGlobalEnabled || !states.enableChatTree) {
            const panel = document.getElementById('chat-tree-panel');
            if (panel) panel.remove();
        }
        if (!states.isGlobalEnabled || !states.enableSidebarSort) {
            // Reload page is often easiest to restore original ChatGPT sidebar DOM
            // but we'll let it be for now and just stop sorting.
        }
    });

    function initMasterScript() {
        // --- CHAT TREE VARIABLES ---
        let globalTurns = [];
        let globalEditedIdSet = new Set();
        let conversationId = getConversationIdHash();
        let currentForkTestId = localStorage.getItem(`chatTreeCurrentTestId_${conversationId}`) || null;

        // --- SIDEBAR VARIABLES ---
        let sortListTriggered = false;
        let dataTotal = JSON.parse(sessionStorage.getItem('dataTotal')) || 0;
        let apiOffset = JSON.parse(sessionStorage.getItem('apiOffset')) || 0;
        let navScrollStart = 0, navScroll = 0;

        function getConversationIdHash() {
            const match = window.location.pathname.match(/\/c\/([a-f0-9-]{36})/);
            return match ? match[1] : null;
        }

        // --- THE MASTER LOOP ---
        setInterval(() => {
            if (!states.isGlobalEnabled) return;

            // 1. UPDATE CHAT TREE
            if (states.enableChatTree) {
                const chatReady = document.querySelector('[data-testid^="conversation-turn-"]');
                const alreadyInjected = document.getElementById('chat-tree-panel');
                if (chatReady && !alreadyInjected) {
                    buildChatTreePanel();
                }
                queueTreeBuild();
            }

            // 2. UPDATE SIDEBAR (Colorizing & Sorting)
            if (states.enableSidebarSort) {
                dataTotal = JSON.parse(sessionStorage.getItem('dataTotal')) || 0;
                apiOffset = JSON.parse(sessionStorage.getItem('apiOffset')) || 0;

                checkAndReplaceText();

                if (apiOffset >= dataTotal && !sortListTriggered) {
                    sortLists();
                    sortListTriggered = true;
                }
            }

            // 3. AUTO SCROLL
            if (states.enableAutoScroll && apiOffset < dataTotal) {
                doAutoScroll();
            }

        }, 2000);

        // --- SIDEBAR LOGICA ---
        function checkAndReplaceText() {
            // ROBUUSTE SELECTOR: Alle tekst elementen van de chat links in de sidebar
            const divElements = document.querySelectorAll('nav[aria-label="Chat history"] a[href^="/c/"] div.overflow-hidden, a[href^="/g-p-"] div.overflow-hidden');
            if (divElements.length === 0) return;

            const colors = ['#f0f', '#FF7E00', '#64edd3', '#0f0', '#3cc', '#ff0', '#f00', '#0ff', '#336699', '#CC99FF', '#66FF99', '#FF6633'];
            let colorIndex = 0;
            let wordColors = JSON.parse(sessionStorage.getItem('wordColors')) || {};
            let conversations = JSON.parse(sessionStorage.getItem('conversations')) || [];

            divElements.forEach((divElement) => {
                let textContent = divElement.textContent;

                // Koppel data-id aan het LI element
                conversations.forEach((item) => {
                    if (item.title === textContent) {
                        const li = divElement.closest('li');
                        if (li) {
                            li.setAttribute('data-date', item.update_time);
                            li.setAttribute('data-id', item.id);
                        }
                    }
                });

                if (divElement.querySelector('span')) return;

                const regex = /\[(.*?)\]/g;
                const newText = textContent.replace(regex, (match, word) => {
                    if (!wordColors[word]) {
                        wordColors[word] = colors[colorIndex % colors.length];
                        colorIndex++;
                    }
                    return `<span class="highlighted" style="color: ${wordColors[word]}; border: 1px dotted ${wordColors[word]}">${word}</span>`;
                });

                const match = regex.exec(textContent);
                if (match && match[1]) {
                    divElement.closest('li')?.setAttribute('data-category', match[1].trim());
                }
                divElement.innerHTML = newText;
            });
            sessionStorage.setItem('wordColors', JSON.stringify(wordColors));
        }

        function sortLists() {
            const categories = {};
            const uncategorizedItems = [];

            // ROBUUSTE SELECTOR: Vind alle ol's in de navigatie
            const olListsToCategorize = document.querySelectorAll('nav[aria-label="Chat history"] ol');
            if (olListsToCategorize.length === 0) return;
            const listContainer = olListsToCategorize[0].parentElement;

            let processedItems = new Set();
            let wordColors = JSON.parse(sessionStorage.getItem('wordColors')) || {};

            olListsToCategorize.forEach((ol) => {
                ol.style.display = 'block';
                const listItems = ol.querySelectorAll('li');

                listItems.forEach((item) => {
                    if (processedItems.has(item)) return;
                    const category = item.getAttribute('data-category');
                    let dateStr = item.getAttribute('data-date');
                    const date = dateStr ? new Date(dateStr) : new Date(0);

                    // Forceer de optie-knop zichtbaar voor geclonede elementen
                    const btnContainer = item.querySelector('div.absolute');
                    if (btnContainer) {
                        btnContainer.style.display = 'flex';
                        btnContainer.style.opacity = '1';
                        btnContainer.style.visibility = 'visible';
                    }

                    if (category) {
                        if (!categories[category]) categories[category] = [];
                        categories[category].push({ item, date });
                    } else {
                        uncategorizedItems.push({ item, date });
                    }
                    processedItems.add(item);
                });
            });

            for (const cat in categories) categories[cat].sort((a, b) => b.date - a.date);
            uncategorizedItems.sort((a, b) => b.date - a.date);

            const fragment = document.createDocumentFragment();

            if (uncategorizedItems.length > 0) {
                fragment.appendChild(createCategoryContainer('Uncategorized', uncategorizedItems.map(obj => obj.item)));
            }

            const sortedCategories = Object.keys(categories).map(cat => ({
                category: cat,
                items: categories[cat].map(o => o.item),
                mostRecentDate: categories[cat][0].date
            })).sort((a, b) => b.mostRecentDate - a.mostRecentDate);

            sortedCategories.forEach(({ category, items }) => {
                fragment.appendChild(createCategoryContainer(category, items, wordColors[category]));
            });

            // Verwijder originele lijsten
            olListsToCategorize.forEach(ol => ol.remove());
            listContainer.appendChild(fragment);
        }

        function createCategoryContainer(category, items, color) {
            const newOlContainer = document.createElement('div');
            newOlContainer.className = 'relative mt-5 first:mt-0 last:mb-5';

            const categoryHeader = document.createElement('h3');
            categoryHeader.style.cursor = 'pointer';
            categoryHeader.textContent = category;
            if (color) categoryHeader.style.color = color;

            const newOl = document.createElement('ol');
            items.forEach((item) => {
                const clonedItem = item.cloneNode(true);
                newOl.appendChild(clonedItem);
            });

            categoryHeader.addEventListener('click', () => {
                newOl.style.display = newOl.style.display === 'none' ? 'block' : 'none';
            });

            newOlContainer.appendChild(categoryHeader);
            newOlContainer.appendChild(newOl);
            return newOlContainer;
        }

        function doAutoScroll() {
            // ROBUUSTE SELECTOR: Zoek dichtstbijzijnde scroll-container van de nav
            let scrollContainer = document.querySelector('nav[aria-label="Chat history"]')?.closest('.overflow-y-auto');
            if (!scrollContainer) return;

            if (scrollContainer.scrollTop + scrollContainer.clientHeight >= scrollContainer.scrollHeight - 50) {
                scrollContainer.scrollTop = scrollContainer.scrollHeight - 200;
                scrollContainer.dispatchEvent(new Event('scroll', { bubbles: true }));
            }
            scrollContainer.scrollTop = scrollContainer.scrollHeight;
            scrollContainer.dispatchEvent(new Event('scroll', { bubbles: true }));
        }


        // --- CHAT TREE LOGICA ---
        function buildChatTreePanel() {
            const existing = document.getElementById('chat-tree-panel');
            if (existing) existing.remove();

            const root = document.createElement('div');
            root.id = 'chat-tree-panel';
            const savedSize = JSON.parse(localStorage.getItem('chatTreeSize') || 'null');
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
            document.body.appendChild(root);

            makeDraggable(root);
        }

        let rebuildQueued = false;
        function queueTreeBuild() {
            if (rebuildQueued) return;
            rebuildQueued = true;
            requestAnimationFrame(() => {
                rebuildQueued = false;
                conversationId = getConversationIdHash();
                const data = localStorage.getItem(`chatTree_${conversationId}`);
                let storedTree = data ? JSON.parse(data) : { turns: [] };

                const turns = Array.from(document.querySelectorAll('article[data-testid^="conversation-turn-"]'));

                const content = document.getElementById('chat-tree-content');
                if (!content) return;
                content.innerHTML = '';
                const list = document.createElement('ul');

                let count = 0;
                turns.forEach((turn, i) => {
                    const userMsg = turn.querySelector('[data-message-author-role="user"]');
                    if (!userMsg) return;

                    const testId = turn.getAttribute('data-testid');
                    const hasEdit = turn.querySelector('button[aria-label="Previous response"]') !== null;

                    const item = document.createElement('li');
                    item.className = 'chat-tree-item';
                    item.textContent = `${++count}: ${userMsg.innerText.slice(0,60).replace(/\n/g, ' ')}`;

                    if(hasEdit) {
                        item.classList.add('chat-tree-edited');
                        item.textContent += ' ✏️';
                    }

                    item.onclick = () => turn.scrollIntoView({ behavior: 'smooth', block: 'center' });
                    list.appendChild(item);
                });

                content.appendChild(list);
            });
        }

        function makeDraggable(element) {
            const handle = element.querySelector('#chat-tree-header') || element;
            let isDragging = false, offsetX, offsetY;

            handle.addEventListener('mousedown', (e) => {
                if (e.target.tagName === 'BUTTON') return;
                isDragging = true;
                offsetX = e.clientX - element.getBoundingClientRect().left;
                offsetY = e.clientY - element.getBoundingClientRect().top;
            });

            document.addEventListener('mouseup', () => isDragging = false);
            document.addEventListener('mousemove', (e) => {
                if (isDragging) {
                    element.style.left = `${e.clientX - offsetX}px`;
                    element.style.top = `${e.clientY - offsetY}px`;
                }
            });
        }
    }
})();