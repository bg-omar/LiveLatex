chrome.runtime.onInstalled.addListener(() => {
    chrome.storage.local.get(['isGlobalEnabled'], (result) => {
        if (result.isGlobalEnabled === undefined) {
            chrome.storage.local.set({ isGlobalEnabled: true });
        } else {
            updateIcon(result.isGlobalEnabled);
        }
    });
});

chrome.storage.onChanged.addListener((changes) => {
    if (changes.isGlobalEnabled) {
        updateIcon(changes.isGlobalEnabled.newValue);
    }
});

function updateIcon(isEnabled) {
    if (isEnabled) {
        // Verwijder de badge als de app AAN staat
        chrome.action.setBadgeText({ text: "TeX" });
        chrome.action.setBadgeBackgroundColor({ color: "#3544ea" });
    } else {
        // Toon een duidelijke rode "UIT" badge als hij gepauzeerd is
        chrome.action.setBadgeText({ text: "..." });
        //chrome.action.setBadgeBackgroundColor({ color: "#70221b" });
    }
}