// Draait in de MAIN world van ChatGPT
(function() {
    if (window._fetchWrapped) return;
    const originalFetch = window.fetch;

    window.fetch = async function (...args) {
        const [resource] = args;
        const response = await originalFetch(...args);

        try {
            if (typeof resource === 'string' && resource.includes('/backend-api/conversations')) {
                const clonedResponse = response.clone();
                clonedResponse.json().then(data => {
                    sessionStorage.setItem('dataTotal', JSON.stringify(data.total));
                    const existingData = JSON.parse(sessionStorage.getItem('conversations')) || [];
                    const newConversations = data.items.map(item => ({ ...item }));

                    const map = new Map(existingData.map(c => [c.id, c]));
                    newConversations.forEach(c => map.set(c.id, c));
                    sessionStorage.setItem('conversations', JSON.stringify(Array.from(map.values())));
                    sessionStorage.setItem('apiOffset', JSON.stringify(data.offset + data.limit));
                }).catch(e => console.error("Fetch patch parse error (conversations):", e));
            }

            if (typeof resource === "string" && resource.includes("/backend-api/gizmos/")) {
                const match = resource.match(/g-p-[a-f0-9]+/);
                if (match) {
                    const projectId = match[0];
                    const clonedResponse = response.clone();
                    clonedResponse.json().then(data => {
                        sessionStorage.setItem('cursor', JSON.stringify(data.cursor));
                        const existingData = JSON.parse(sessionStorage.getItem('conversations_' + projectId)) || [];
                        const newConversations = data.items.map(item => ({ ...item }));

                        const map = new Map(existingData.map(c => [c.id, c]));
                        newConversations.forEach(c => map.set(c.id, c));
                        sessionStorage.setItem('conversations_' + projectId, JSON.stringify(Array.from(map.values())));
                    }).catch(e => console.error("Fetch patch parse error (gizmos):", e));
                }
            }
        } catch(e) {
            console.error("Fetch patch general error:", e);
        }
        return response;
    };
    window._fetchWrapped = true;
})();