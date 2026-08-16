/**
 * FileDroid File Manager
 * Handles file listing, navigation, selection, sorting, and search
 */
const FileManager = (() => {
    let currentPath = null;
    let currentFiles = [];
    let selectedFiles = new Set();
    let currentSort = { key: 'name', asc: true };
    let viewMode = localStorage.getItem('viewMode') || 'list'; // 'list' or 'grid'
    let isSearchMode = false;

    // File type icons
    const icons = {
        folder: '📁',
        image: '🖼️',
        video: '🎬',
        audio: '🎵',
        pdf: '📄',
        text: '📝',
        archive: '📦',
        apk: '📱',
        document: '📃',
        generic: '📎'
    };

    function getFileIcon(item) {
        if (item.isDirectory) return icons.folder;
        const ext = item.name.split('.').pop().toLowerCase();
        if (['jpg','jpeg','png','gif','webp','bmp','svg'].includes(ext)) return icons.image;
        if (['mp4','mkv','avi','mov','webm','flv','wmv','3gp'].includes(ext)) return icons.video;
        if (['mp3','wav','ogg','flac','aac','m4a','wma'].includes(ext)) return icons.audio;
        if (ext === 'pdf') return icons.pdf;
        if (['txt','md','log','csv','json','xml','html','css','js','kt','java','py','c','cpp','sh',
             'yml','yaml','ini','cfg','conf','properties','gradle','toml'].includes(ext)) return icons.text;
        if (['zip','rar','7z','tar','gz'].includes(ext)) return icons.archive;
        if (ext === 'apk') return icons.apk;
        if (['doc','docx','xls','xlsx','ppt','pptx'].includes(ext)) return icons.document;
        return icons.generic;
    }

    function formatSize(bytes) {
        if (bytes <= 0) return '';
        const units = ['B', 'KB', 'MB', 'GB', 'TB'];
        const i = Math.floor(Math.log(bytes) / Math.log(1024));
        return (bytes / Math.pow(1024, i)).toFixed(i > 0 ? 1 : 0) + ' ' + units[i];
    }

    function formatDate(timestamp) {
        if (!timestamp) return '';
        const d = new Date(timestamp);
        const now = new Date();
        const diff = now - d;

        if (diff < 60000) return 'Just now';
        if (diff < 3600000) return Math.floor(diff / 60000) + 'm ago';
        if (diff < 86400000) return Math.floor(diff / 3600000) + 'h ago';
        if (diff < 604800000) return Math.floor(diff / 86400000) + 'd ago';

        return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: d.getFullYear() !== now.getFullYear() ? 'numeric' : undefined });
    }

    function sortFiles(files) {
        const { key, asc } = currentSort;
        const sorted = [...files];

        sorted.sort((a, b) => {
            // Directories always first
            if (a.isDirectory && !b.isDirectory) return -1;
            if (!a.isDirectory && b.isDirectory) return 1;

            let cmp = 0;
            switch (key) {
                case 'name':
                    cmp = a.name.localeCompare(b.name, undefined, { sensitivity: 'base' });
                    break;
                case 'date':
                    cmp = (a.modified || 0) - (b.modified || 0);
                    break;
                case 'size':
                    cmp = (a.size || 0) - (b.size || 0);
                    break;
                case 'type':
                    const extA = a.name.split('.').pop().toLowerCase();
                    const extB = b.name.split('.').pop().toLowerCase();
                    cmp = extA.localeCompare(extB);
                    break;
            }
            return asc ? cmp : -cmp;
        });

        return sorted;
    }

    function escapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    function renderBreadcrumbs(breadcrumbs) {
        const container = document.getElementById('breadcrumbs');
        container.innerHTML = '';

        breadcrumbs.forEach((crumb, i) => {
            if (i > 0) {
                const sep = document.createElement('span');
                sep.className = 'crumb-sep';
                sep.textContent = '›';
                container.appendChild(sep);
            }

            const el = document.createElement('span');
            el.className = 'crumb' + (i === breadcrumbs.length - 1 ? ' active' : '');
            el.textContent = crumb.name;
            if (i < breadcrumbs.length - 1) {
                el.addEventListener('click', () => navigateTo(crumb.path));
            }
            container.appendChild(el);
        });

        // Auto-scroll to end
        container.scrollLeft = container.scrollWidth;
    }

    function renderFiles(files) {
        const container = document.getElementById('fileContainer');
        const loading = document.getElementById('loading');
        const empty = document.getElementById('emptyState');

        loading.style.display = 'none';

        if (files.length === 0) {
            container.innerHTML = '';
            empty.style.display = 'block';
            return;
        }

        empty.style.display = 'none';
        container.innerHTML = '';

        files.forEach(item => {
            const el = createFileElement(item);
            container.appendChild(el);
        });

        updateSelectionUI();
    }

    function createFileElement(item) {
        const el = document.createElement('div');
        el.className = 'file-item' + (selectedFiles.has(item.path) ? ' selected' : '');
        el.dataset.path = item.path;

        const isImage = item.hasThumbnail && item.mimeType && item.mimeType.startsWith('image/');
        const isVideo = item.hasThumbnail && item.mimeType && item.mimeType.startsWith('video/');

        // Checkbox
        const cb = document.createElement('input');
        cb.type = 'checkbox';
        cb.className = 'file-select';
        cb.checked = selectedFiles.has(item.path);
        cb.addEventListener('change', (e) => {
            e.stopPropagation();
            toggleSelection(item.path);
        });

        // Thumbnail
        const thumb = document.createElement('div');
        thumb.className = 'file-thumb';

        if (item.hasThumbnail && !item.isDirectory) {
            const img = document.createElement('img');
            img.loading = 'lazy';
            img.src = API.getThumbnailUrl(item.path, viewMode === 'grid' ? 160 : 88);
            img.alt = item.name;
            img.onerror = () => {
                thumb.textContent = getFileIcon(item);
                img.remove();
            };
            thumb.appendChild(img);

            if (isVideo) {
                const overlay = document.createElement('div');
                overlay.className = 'video-overlay';
                overlay.innerHTML = '<span class="play-icon">▶</span>';
                thumb.style.position = 'relative';
                thumb.appendChild(overlay);
            }
        } else {
            thumb.textContent = getFileIcon(item);
        }

        // Info
        const info = document.createElement('div');
        info.className = 'file-info';

        const name = document.createElement('div');
        name.className = 'file-name';
        name.textContent = item.name;

        const meta = document.createElement('div');
        meta.className = 'file-meta';
        if (item.isDirectory) {
            meta.textContent = (item.childCount || 0) + ' items';
        } else {
            const parts = [];
            if (item.size) parts.push(formatSize(item.size));
            if (item.modified) parts.push(formatDate(item.modified));
            meta.textContent = parts.join(' • ');
        }

        info.appendChild(name);
        info.appendChild(meta);

        // Actions
        const actions = document.createElement('div');
        actions.className = 'file-actions';

        if (!item.isDirectory) {
            const dlBtn = document.createElement('a');
            dlBtn.className = 'icon-btn';
            dlBtn.href = API.getDownloadUrl(item.path);
            dlBtn.title = 'Download';
            dlBtn.textContent = '⬇️';
            dlBtn.addEventListener('click', (e) => e.stopPropagation());
            actions.appendChild(dlBtn);
        }

        el.appendChild(cb);
        el.appendChild(thumb);
        el.appendChild(info);
        el.appendChild(actions);

        // Click handler
        el.addEventListener('click', (e) => {
            if (e.target.tagName === 'INPUT' || e.target.tagName === 'A') return;

            if (item.isDirectory) {
                navigateTo(item.path);
            } else {
                Preview.show(item);
            }
        });

        return el;
    }

    function toggleSelection(path) {
        if (selectedFiles.has(path)) {
            selectedFiles.delete(path);
        } else {
            selectedFiles.add(path);
        }
        updateSelectionUI();
    }

    function updateSelectionUI() {
        const toolbar = document.getElementById('toolbar');
        const count = selectedFiles.size;

        if (count > 0) {
            toolbar.style.display = 'flex';
            document.getElementById('selectedCount').textContent = count + ' selected';
        } else {
            toolbar.style.display = 'none';
        }

        // Update checkboxes
        document.querySelectorAll('.file-item').forEach(el => {
            const cb = el.querySelector('.file-select');
            const path = el.dataset.path;
            if (cb && path) {
                cb.checked = selectedFiles.has(path);
                el.classList.toggle('selected', selectedFiles.has(path));
            }
        });
    }

    async function navigateTo(path) {
        isSearchMode = false;
        document.getElementById('clearSearch').style.display = 'none';
        document.getElementById('searchInput').value = '';

        const loading = document.getElementById('loading');
        const container = document.getElementById('fileContainer');
        const empty = document.getElementById('emptyState');

        loading.style.display = 'flex';
        container.innerHTML = '';
        empty.style.display = 'none';

        try {
            const data = await API.listFiles(path);
            currentPath = data.path;
            currentFiles = data.items || [];

            renderBreadcrumbs(data.breadcrumbs || []);
            renderFiles(sortFiles(currentFiles));
        } catch (err) {
            if (err.pinRequired) {
                showPinScreen();
                return;
            }
            loading.style.display = 'none';
            container.innerHTML = `<div class="empty-state"><p>Error: ${escapeHtml(err.message)}</p></div>`;
        }
    }

    async function search(query) {
        if (!query || query.length < 2) return;

        isSearchMode = true;
        document.getElementById('clearSearch').style.display = 'inline';

        const loading = document.getElementById('loading');
        const container = document.getElementById('fileContainer');
        const empty = document.getElementById('emptyState');

        loading.style.display = 'flex';
        container.innerHTML = '';
        empty.style.display = 'none';

        try {
            const data = await API.search(query, currentPath);
            currentFiles = data.results || [];

            renderBreadcrumbs([{ name: 'Search: "' + query + '"', path: null }]);
            renderFiles(sortFiles(currentFiles));
        } catch (err) {
            loading.style.display = 'none';
            container.innerHTML = `<div class="empty-state"><p>Search error: ${escapeHtml(err.message)}</p></div>`;
        }
    }

    function setViewMode(mode) {
        viewMode = mode;
        localStorage.setItem('viewMode', mode);

        const container = document.getElementById('fileContainer');
        container.className = 'file-container ' + mode + '-view';

        const icon = document.getElementById('viewIcon');
        icon.textContent = mode === 'list' ? '▦' : '☰';

        // Re-render
        renderFiles(sortFiles(currentFiles));
    }

    function setSortKey(key) {
        if (currentSort.key === key) {
            currentSort.asc = !currentSort.asc;
        } else {
            currentSort.key = key;
            currentSort.asc = true;
        }

        // Update sort buttons
        document.querySelectorAll('.sort-btn').forEach(btn => {
            btn.classList.toggle('active', btn.dataset.sort === key);
        });

        renderFiles(sortFiles(currentFiles));
    }

    function showPinScreen() {
        document.getElementById('pinScreen').style.display = 'flex';
        document.getElementById('app').style.display = 'none';
    }

    async function downloadSelected() {
        if (selectedFiles.size === 0) return;

        const paths = Array.from(selectedFiles);
        if (paths.length === 1) {
            // Single file - direct download
            const item = currentFiles.find(f => f.path === paths[0]);
            if (item && !item.isDirectory) {
                window.location.href = API.getDownloadUrl(paths[0]);
                return;
            }
        }

        // Multiple files - ZIP download
        try {
            await API.downloadZip(paths);
        } catch (err) {
            alert('Download failed: ' + err.message);
        }
    }

    function selectAll(checked) {
        if (checked) {
            currentFiles.forEach(f => {
                if (!f.isDirectory) selectedFiles.add(f.path);
            });
        } else {
            selectedFiles.clear();
        }
        updateSelectionUI();
    }

    function getCurrentPath() {
        return currentPath;
    }

    function refreshCurrentDir() {
        if (currentPath !== null) {
            navigateTo(currentPath);
        } else {
            navigateTo(undefined);
        }
    }

    return {
        navigateTo,
        search,
        setViewMode,
        setSortKey,
        toggleSelection,
        downloadSelected,
        selectAll,
        showPinScreen,
        getCurrentPath,
        refreshCurrentDir,
        getViewMode: () => viewMode,
        init() {
            // Set initial view mode
            const container = document.getElementById('fileContainer');
            container.className = 'file-container ' + viewMode + '-view';
            const icon = document.getElementById('viewIcon');
            icon.textContent = viewMode === 'list' ? '▦' : '☰';
        }
    };
})();
