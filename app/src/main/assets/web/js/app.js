/**
 * FileDroid App - Main entry point
 * Initializes all modules and handles global events
 */
(async function() {
    'use strict';

    // Initialize modules
    FileManager.init();
    Upload.init();
    Preview.init();

    // Setup event listeners
    setupSearch();
    setupViewToggle();
    setupSort();
    setupSelection();
    setupKeyboard();

    // Load server info and start
    try {
        const info = await API.getInfo();
        updateServerInfo(info);

        if (info.pinRequired) {
            setupPinScreen();
            FileManager.showPinScreen();
        } else {
            showApp();
            FileManager.navigateTo();
        }
    } catch (err) {
        if (err.pinRequired) {
            setupPinScreen();
            FileManager.showPinScreen();
        } else {
            showApp();
            FileManager.navigateTo();
        }
    }

    // ---- Setup functions ----

    function setupSearch() {
        const input = document.getElementById('searchInput');
        const searchBtn = document.getElementById('searchBtn');
        const clearBtn = document.getElementById('clearSearch');
        let debounceTimer;

        input.addEventListener('input', () => {
            clearTimeout(debounceTimer);
            const query = input.value.trim();

            if (query.length >= 2) {
                debounceTimer = setTimeout(() => {
                    FileManager.search(query);
                }, 400);
            }
        });

        input.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') {
                clearTimeout(debounceTimer);
                const query = input.value.trim();
                if (query.length >= 2) {
                    FileManager.search(query);
                }
            }
            if (e.key === 'Escape') {
                input.value = '';
                input.blur();
                clearBtn.style.display = 'none';
                FileManager.refreshCurrentDir();
            }
        });

        searchBtn.addEventListener('click', () => {
            const query = input.value.trim();
            if (query.length >= 2) {
                FileManager.search(query);
            }
        });

        clearBtn.addEventListener('click', () => {
            input.value = '';
            clearBtn.style.display = 'none';
            FileManager.refreshCurrentDir();
        });
    }

    function setupViewToggle() {
        document.getElementById('toggleView').addEventListener('click', () => {
            const current = FileManager.getViewMode();
            FileManager.setViewMode(current === 'list' ? 'grid' : 'list');
        });
    }

    function setupSort() {
        document.querySelectorAll('.sort-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                FileManager.setSortKey(btn.dataset.sort);
            });
        });
    }

    function setupSelection() {
        document.getElementById('selectAll').addEventListener('change', (e) => {
            FileManager.selectAll(e.target.checked);
        });

        document.getElementById('downloadSelected').addEventListener('click', () => {
            FileManager.downloadSelected();
        });
    }

    function setupKeyboard() {
        document.addEventListener('keydown', (e) => {
            // Ctrl/Cmd+F for search
            if ((e.ctrlKey || e.metaKey) && e.key === 'f') {
                e.preventDefault();
                document.getElementById('searchInput').focus();
            }
        });
    }

    function setupPinScreen() {
        const pinInput = document.getElementById('pinInput');
        const pinSubmit = document.getElementById('pinSubmit');
        const pinError = document.getElementById('pinError');

        async function submitPin() {
            const pin = pinInput.value.trim();
            if (!pin) return;

            pinSubmit.disabled = true;
            pinSubmit.textContent = 'Verifying...';
            pinError.style.display = 'none';

            try {
                await API.verifyPin(pin);
                showApp();
                FileManager.navigateTo();
            } catch (err) {
                pinError.textContent = err.message;
                pinError.style.display = 'block';
                pinInput.value = '';
                pinInput.focus();
            } finally {
                pinSubmit.disabled = false;
                pinSubmit.textContent = 'Unlock';
            }
        }

        pinSubmit.addEventListener('click', submitPin);
        pinInput.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') submitPin();
        });

        pinInput.focus();
    }

    function showApp() {
        document.getElementById('pinScreen').style.display = 'none';
        document.getElementById('app').style.display = 'block';
    }

    function updateServerInfo(info) {
        const el = document.getElementById('serverInfo');
        if (!info) return;

        const free = formatBytes(info.freeSpace);
        const total = formatBytes(info.totalSpace);
        el.textContent = `${info.deviceName} • ${free} free of ${total} • FileDroid v${info.serverVersion}`;
    }

    function formatBytes(bytes) {
        if (!bytes || bytes <= 0) return '0 B';
        const units = ['B', 'KB', 'MB', 'GB', 'TB'];
        const i = Math.floor(Math.log(bytes) / Math.log(1024));
        return (bytes / Math.pow(1024, i)).toFixed(1) + ' ' + units[i];
    }
})();
