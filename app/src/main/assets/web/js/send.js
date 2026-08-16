/**
 * FileDroid Send Mode - Download Page
 * Browser downloads files from phone
 */
(function() {
    'use strict';

    let currentPath = null;
    let currentFiles = [];
    let selectedFiles = new Set();
    let currentSort = 'name';
    let viewMode = localStorage.getItem('fd_viewMode') || 'grid';
    let thumbObserver = null;
    let longPressTimer = null;
    let isSelectionMode = false;
    const PAGE_SIZE = 60;
    let currentOffset = 0;
    let hasMore = false;
    let isLoadingMore = false;

    // ---- Init ----
    init();

    async function init() {
        setupThumbObserver();
        applyViewMode();
        setupEventListeners();
        await loadServerInfo();
        navigateTo(null);
    }

    function setupEventListeners() {
        document.getElementById('searchInput').addEventListener('input', debounce(handleSearch, 400));
        document.getElementById('searchBtn').addEventListener('click', handleSearch);
        document.getElementById('selectAll').addEventListener('change', handleSelectAll);
        document.getElementById('downloadSelected').addEventListener('click', downloadSelected);
        document.getElementById('closePreview').addEventListener('click', closePreview);
        document.getElementById('toggleView').addEventListener('click', toggleViewMode);
        document.getElementById('selectAllBtn').addEventListener('click', toggleSelectAll);

        document.querySelectorAll('.sort-btn').forEach(btn => {
            btn.addEventListener('click', () => setSortKey(btn.dataset.sort));
        });

        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') closePreview();
            if (e.key === 'ArrowRight') navigatePreview(1);
            if (e.key === 'ArrowLeft') navigatePreview(-1);
        });

        // Click outside preview content to close
        document.getElementById('previewModal').addEventListener('click', (e) => {
            if (e.target.id === 'previewModal' || e.target.classList.contains('preview-content')) {
                closePreview();
            }
        });

        // Infinite scroll for pagination
        document.querySelector('.scroll-content').addEventListener('scroll', debounce(handleScroll, 200));
    }

    // ---- Lazy Thumbnail Loading ----

    function setupThumbObserver() {
        thumbObserver = new IntersectionObserver((entries) => {
            entries.forEach(entry => {
                if (entry.isIntersecting) {
                    const img = entry.target;
                    const src = img.dataset.src;
                    if (src) {
                        img.src = src;
                        img.removeAttribute('data-src');
                    }
                    thumbObserver.unobserve(img);
                }
            });
        }, { rootMargin: '200px' });
    }

    function createLazyThumbEl(thumbUrl, icon) {
        if (!thumbUrl) return null;
        const img = document.createElement('img');
        img.dataset.src = thumbUrl;
        img.onerror = function() { this.parentElement.textContent = icon; };
        thumbObserver.observe(img);
        return img;
    }

    // ---- View Mode ----

    function toggleViewMode() {
        viewMode = viewMode === 'grid' ? 'list' : 'grid';
        localStorage.setItem('fd_viewMode', viewMode);
        applyViewMode();
        renderFiles(sortFiles(currentFiles));
    }

    function applyViewMode() {
        const container = document.getElementById('fileContainer');
        const icon = document.getElementById('viewIcon');
        container.className = 'file-container ' + (viewMode === 'grid' ? 'grid-view' : 'list-view');
        icon.textContent = viewMode === 'grid' ? '☰' : '▦';
    }

    // ---- API Calls ----

    async function fetchJSON(url) {
        const res = await fetch(url);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        return res.json();
    }

    async function loadServerInfo() {
        try {
            const info = await fetchJSON('/api/info');
            const el = document.getElementById('serverInfo');
            const free = formatBytes(info.freeSpace);
            const total = formatBytes(info.totalSpace);
            el.textContent = `${info.deviceName} \u2022 ${free} free of ${total}`;
        } catch (e) {}
    }

    // ---- Navigation ----

    async function navigateTo(path) {
        const container = document.getElementById('fileContainer');
        const loading = document.getElementById('loading');
        const empty = document.getElementById('emptyState');

        container.innerHTML = '';
        loading.style.display = 'flex';
        empty.style.display = 'none';
        selectedFiles.clear();
        isSelectionMode = false;
        currentOffset = 0;
        currentFiles = [];
        hasMore = false;
        updateToolbar();

        try {
            let url = path ? `/api/files?path=${encodeURIComponent(path)}&offset=0&limit=${PAGE_SIZE}` : '/api/files';
            const data = await fetchJSON(url);
            currentPath = data.path;
            currentFiles = data.items || [];
            hasMore = data.hasMore || false;
            currentOffset = currentFiles.length;

            renderBreadcrumbs(data.breadcrumbs || []);
            renderFiles(sortFiles(currentFiles));
        } catch (e) {
            container.innerHTML = `<div class="empty-state"><div class="empty-icon">⚠️</div><p>Failed to load files</p></div>`;
        } finally {
            loading.style.display = 'none';
        }
    }

    // ---- Pagination / Infinite Scroll ----

    function handleScroll() {
        if (!hasMore || isLoadingMore) return;
        const scrollEl = document.querySelector('.scroll-content');
        const nearBottom = scrollEl.scrollTop + scrollEl.clientHeight >= scrollEl.scrollHeight - 300;
        if (nearBottom) loadMore();
    }

    async function loadMore() {
        if (!hasMore || isLoadingMore || !currentPath) return;
        isLoadingMore = true;
        showLoadMoreIndicator(true);

        try {
            const url = `/api/files?path=${encodeURIComponent(currentPath)}&offset=${currentOffset}&limit=${PAGE_SIZE}`;
            const data = await fetchJSON(url);
            const newItems = data.items || [];
            hasMore = data.hasMore || false;
            currentFiles = currentFiles.concat(newItems);
            currentOffset += newItems.length;

            // Append new items without re-rendering existing ones
            const container = document.getElementById('fileContainer');
            const sorted = sortFiles(newItems);
            sorted.forEach(file => container.appendChild(createFileItem(file)));
            updateSelectAllBtn();
        } catch (e) {}
        finally {
            isLoadingMore = false;
            showLoadMoreIndicator(false);
        }
    }

    function showLoadMoreIndicator(show) {
        let el = document.getElementById('loadMoreSpinner');
        if (show && !el) {
            el = document.createElement('div');
            el.id = 'loadMoreSpinner';
            el.className = 'loading';
            el.innerHTML = '<div class="spinner"></div><p>Loading more...</p>';
            el.style.padding = '20px';
            document.querySelector('.scroll-content').appendChild(el);
        } else if (!show && el) {
            el.remove();
        }
    }

    // ---- Rendering ----

    function renderBreadcrumbs(crumbs) {
        const nav = document.getElementById('breadcrumbs');
        nav.innerHTML = '';
        crumbs.forEach((crumb, i) => {
            if (i > 0) {
                const sep = document.createElement('span');
                sep.className = 'crumb-sep';
                sep.textContent = '\u203A';
                nav.appendChild(sep);
            }
            const el = document.createElement('span');
            el.className = 'crumb' + (i === crumbs.length - 1 ? ' active' : '');
            el.textContent = crumb.name;
            if (i < crumbs.length - 1) {
                el.addEventListener('click', () => navigateTo(crumb.path === '/' ? null : crumb.path));
            }
            nav.appendChild(el);
        });
    }

    function isDir(file) { return file.isDirectory || file.directory; }

    function renderFiles(files) {
        const container = document.getElementById('fileContainer');
        const empty = document.getElementById('emptyState');
        container.innerHTML = '';

        if (files.length === 0) {
            empty.style.display = 'block';
            return;
        }

        const dirs = files.filter(f => isDir(f));
        const regular = files.filter(f => !isDir(f));

        [...dirs, ...regular].forEach(file => {
            container.appendChild(createFileItem(file));
        });
        updateSelectAllBtn();
    }

    function createFileItem(file) {
        const div = document.createElement('div');
        div.className = 'file-item' + (selectedFiles.has(file.path) ? ' selected' : '');
        div.dataset.path = file.path;

        const icon = getFileIcon(file);
        const mime = file.mimeType || '';
        const showThumb = !isDir(file) && (file.hasThumbnail || mime.startsWith('image/') || mime.startsWith('video/'));
        const thumbUrl = showThumb ? `/api/thumbnail?path=${encodeURIComponent(file.path)}&t=${file.modified || ''}` : '';

        if (viewMode === 'grid') {
            const thumbDiv = document.createElement('div');
            thumbDiv.className = 'grid-thumb';
            const thumbImg = createLazyThumbEl(thumbUrl, icon);
            if (thumbImg) {
                thumbDiv.appendChild(thumbImg);
            } else {
                thumbDiv.innerHTML = `<span class="grid-icon">${icon}</span>`;
            }
            div.appendChild(thumbDiv);

            const checkDiv = document.createElement('div');
            checkDiv.className = 'grid-check';
            if (!isDir(file)) {
                const cb = document.createElement('input');
                cb.type = 'checkbox';
                cb.className = 'file-select';
                cb.checked = selectedFiles.has(file.path);
                checkDiv.appendChild(cb);
            }
            div.appendChild(checkDiv);

            const infoDiv = document.createElement('div');
            infoDiv.className = 'grid-info';
            infoDiv.innerHTML = `<div class="file-name">${escapeHtml(file.name)}</div><div class="file-meta">${isDir(file) ? (file.childCount || 0) + ' items' : formatBytes(file.size)}</div>`;
            div.appendChild(infoDiv);

            if (mime.startsWith('video/')) {
                const dur = document.createElement('div');
                dur.className = 'grid-duration';
                dur.textContent = '\u25B6';
                div.appendChild(dur);
            }
        } else {
            const cb = document.createElement('input');
            cb.type = 'checkbox';
            cb.className = 'file-select';
            cb.checked = selectedFiles.has(file.path);
            if (isDir(file)) cb.style.display = 'none';
            div.appendChild(cb);

            const thumbDiv = document.createElement('div');
            thumbDiv.className = 'file-thumb';
            const thumbImg = createLazyThumbEl(thumbUrl, icon);
            if (thumbImg) {
                thumbDiv.appendChild(thumbImg);
            } else {
                thumbDiv.textContent = icon;
            }
            div.appendChild(thumbDiv);

            const infoDiv = document.createElement('div');
            infoDiv.className = 'file-info';
            infoDiv.innerHTML = `<div class="file-name">${escapeHtml(file.name)}</div><div class="file-meta">${isDir(file) ? (file.childCount || 0) + ' items' : formatBytes(file.size)}</div>`;
            div.appendChild(infoDiv);

            if (!isDir(file)) {
                const actDiv = document.createElement('div');
                actDiv.className = 'file-actions';
                const dlBtn = document.createElement('button');
                dlBtn.className = 'icon-btn download-btn';
                dlBtn.title = 'Download';
                dlBtn.textContent = '\u2B07\uFE0F';
                actDiv.appendChild(dlBtn);
                div.appendChild(actDiv);
            }
        }

        // Events — checkbox click
        const checkbox = div.querySelector('.file-select');
        if (checkbox && !isDir(file)) {
            checkbox.addEventListener('click', (e) => {
                e.stopPropagation();
                toggleSelect(file, div);
            });
        }

        // Long-press to select (mobile)
        if (!isDir(file)) {
            div.addEventListener('touchstart', (e) => {
                if (e.target.classList.contains('file-select') || e.target.type === 'checkbox') return;
                longPressTimer = setTimeout(() => {
                    longPressTimer = null;
                    isSelectionMode = true;
                    toggleSelect(file, div);
                    // Haptic feedback if available
                    if (navigator.vibrate) navigator.vibrate(30);
                }, 500);
            }, { passive: true });

            div.addEventListener('touchend', () => {
                if (longPressTimer) { clearTimeout(longPressTimer); longPressTimer = null; }
            });
            div.addEventListener('touchmove', () => {
                if (longPressTimer) { clearTimeout(longPressTimer); longPressTimer = null; }
            });
        }

        // Click handler
        div.addEventListener('click', (e) => {
            if (e.target.classList.contains('file-select') || e.target.type === 'checkbox') return;
            if (e.target.classList.contains('download-btn')) { downloadFile(file); return; }
            if (isDir(file)) {
                navigateTo(file.path);
                return;
            }
            // In selection mode, tap toggles selection instead of preview
            if (isSelectionMode) {
                toggleSelect(file, div);
                return;
            }
            previewFile(file);
        });

        return div;
    }

    // ---- Selection ----

    function toggleSelect(file, div) {
        if (selectedFiles.has(file.path)) {
            selectedFiles.delete(file.path);
            div.classList.remove('selected');
        } else {
            selectedFiles.add(file.path);
            div.classList.add('selected');
        }
        const cb = div.querySelector('.file-select');
        if (cb) cb.checked = selectedFiles.has(file.path);

        // Exit selection mode when nothing selected
        if (selectedFiles.size === 0) isSelectionMode = false;

        updateToolbar();
        updateSelectAllBtn();
    }

    function handleSelectAll(e) {
        const checked = e.target.checked;
        currentFiles.filter(f => !isDir(f)).forEach(file => {
            if (checked) selectedFiles.add(file.path);
            else selectedFiles.delete(file.path);
        });
        document.querySelectorAll('.file-item').forEach(div => {
            const cb = div.querySelector('.file-select');
            if (cb) { cb.checked = checked; div.classList.toggle('selected', checked); }
        });
        isSelectionMode = checked;
        updateToolbar();
        updateSelectAllBtn();
    }

    function toggleSelectAll() {
        const allFiles = currentFiles.filter(f => !isDir(f));
        const allSelected = allFiles.length > 0 && allFiles.every(f => selectedFiles.has(f.path));
        const checked = !allSelected;
        allFiles.forEach(file => {
            if (checked) selectedFiles.add(file.path);
            else selectedFiles.delete(file.path);
        });
        document.querySelectorAll('.file-item').forEach(div => {
            const cb = div.querySelector('.file-select');
            if (cb) { cb.checked = checked; div.classList.toggle('selected', checked); }
        });
        const selectAllCb = document.getElementById('selectAll');
        if (selectAllCb) selectAllCb.checked = checked;
        isSelectionMode = checked;
        updateToolbar();
        updateSelectAllBtn();
    }

    function updateSelectAllBtn() {
        const btn = document.getElementById('selectAllBtn');
        const allFiles = currentFiles.filter(f => !isDir(f));
        const allSelected = allFiles.length > 0 && allFiles.every(f => selectedFiles.has(f.path));
        btn.textContent = allSelected ? '☐ Deselect All' : '☑ Select All';
    }

    function getSelectedTotalSize() {
        let total = 0;
        for (const path of selectedFiles) {
            const file = currentFiles.find(f => f.path === path);
            if (file) total += (file.size || 0);
        }
        return total;
    }

    function updateToolbar() {
        const toolbar = document.getElementById('toolbar');
        const btn = document.getElementById('downloadSelected');
        const count = selectedFiles.size;
        if (count > 0) {
            toolbar.classList.remove('toolbar-disabled');
            btn.disabled = false;
            const totalSize = formatBytes(getSelectedTotalSize());
            document.getElementById('selectedCount').textContent = `${count} selected \u00B7 ${totalSize}`;
        } else {
            toolbar.classList.add('toolbar-disabled');
            btn.disabled = true;
            document.getElementById('selectedCount').textContent = 'No files selected';
        }
    }

    // ---- Downloads ----

    function downloadFile(file) {
        const a = document.createElement('a');
        a.href = `/api/download?path=${encodeURIComponent(file.path)}`;
        a.download = file.name;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
    }

    async function downloadSelected() {
        if (selectedFiles.size === 0) return;
        const paths = Array.from(selectedFiles);
        const files = paths.map(p => currentFiles.find(f => f.path === p)).filter(f => f && !isDir(f));
        const totalSize = formatBytes(getSelectedTotalSize());
        showToast(`⬇️ Downloading ${files.length} file${files.length > 1 ? 's' : ''} (${totalSize})...`);
        for (let i = 0; i < files.length; i++) {
            downloadFile(files[i]);
            if (i < files.length - 1) await new Promise(r => setTimeout(r, 800));
        }
    }

    // ---- Toast Notification ----

    function showToast(message, duration) {
        duration = duration || 3000;
        // Remove existing toast if any
        const existing = document.getElementById('fdToast');
        if (existing) existing.remove();

        const toast = document.createElement('div');
        toast.id = 'fdToast';
        toast.className = 'fd-toast';
        toast.textContent = message;
        document.body.appendChild(toast);

        // Trigger animation
        requestAnimationFrame(() => toast.classList.add('fd-toast-show'));

        setTimeout(() => {
            toast.classList.remove('fd-toast-show');
            toast.addEventListener('transitionend', () => toast.remove());
        }, duration);
    }

    // ---- Preview ----

    let currentPreviewIndex = -1;

    function previewFile(file) {
        const modal = document.getElementById('previewModal');
        const content = document.getElementById('previewContent');
        const title = document.getElementById('previewTitle');
        const downloadBtn = document.getElementById('previewDownload');

        // Track index for navigation
        const previewableFiles = currentFiles.filter(f => !isDir(f));
        currentPreviewIndex = previewableFiles.findIndex(f => f.path === file.path);

        title.textContent = file.name;
        downloadBtn.href = `/api/download?path=${encodeURIComponent(file.path)}`;
        downloadBtn.download = file.name;

        const mime = file.mimeType || '';
        const previewUrl = `/api/preview?path=${encodeURIComponent(file.path)}`;

        content.innerHTML = '';

        if (mime.startsWith('image/')) {
            content.innerHTML = `<img src="${previewUrl}" alt="${escapeHtml(file.name)}">`;
        } else if (mime.startsWith('video/')) {
            content.innerHTML = `<video controls autoplay playsinline><source src="${previewUrl}" type="${mime}"></video>`;
        } else if (mime.startsWith('audio/')) {
            content.innerHTML = `<audio controls autoplay><source src="${previewUrl}" type="${mime}"></audio>`;
        } else if (mime === 'application/pdf') {
            content.innerHTML = `<embed src="${previewUrl}" type="application/pdf" width="100%" height="100%">`;
        } else if (mime.startsWith('text/') || ['json', 'xml', 'md', 'csv'].some(e => file.name.endsWith('.' + e))) {
            fetch(previewUrl).then(r => r.text()).then(text => {
                content.innerHTML = `<pre>${escapeHtml(text.substring(0, 500000))}</pre>`;
            });
        } else {
            downloadFile(file);
            return;
        }

        modal.style.display = 'flex';
        document.body.style.overflow = 'hidden';
    }

    function navigatePreview(direction) {
        const previewableFiles = currentFiles.filter(f => !isDir(f));
        if (currentPreviewIndex < 0 || previewableFiles.length === 0) return;
        const newIndex = currentPreviewIndex + direction;
        if (newIndex >= 0 && newIndex < previewableFiles.length) {
            previewFile(previewableFiles[newIndex]);
        }
    }

    function closePreview() {
        const modal = document.getElementById('previewModal');
        modal.style.display = 'none';
        document.getElementById('previewContent').innerHTML = '';
        document.body.style.overflow = '';
        currentPreviewIndex = -1;
    }

    // ---- Search ----

    async function handleSearch() {
        const query = document.getElementById('searchInput').value.trim();
        if (query.length < 2) {
            if (query.length === 0) navigateTo(currentPath);
            return;
        }
        try {
            const data = await fetchJSON(`/api/search?q=${encodeURIComponent(query)}`);
            currentFiles = data.results || [];
            hasMore = false; // search results are not paginated
            renderFiles(sortFiles(currentFiles));
        } catch (e) {}
    }

    // ---- Sorting ----

    function setSortKey(key) {
        currentSort = key;
        document.querySelectorAll('.sort-btn').forEach(b => b.classList.toggle('active', b.dataset.sort === key));
        renderFiles(sortFiles(currentFiles));
    }

    function sortFiles(files) {
        const sorted = [...files];
        sorted.sort((a, b) => {
            if (isDir(a) !== isDir(b)) return isDir(a) ? -1 : 1;
            switch (currentSort) {
                case 'name': return a.name.localeCompare(b.name);
                case 'date': return (b.modified || 0) - (a.modified || 0);
                case 'size': return (b.size || 0) - (a.size || 0);
                default: return 0;
            }
        });
        return sorted;
    }

    // ---- Utilities ----

    function getFileIcon(file) {
        if (isDir(file)) return '\uD83D\uDCC1';
        const ext = (file.name || '').split('.').pop().toLowerCase();
        const icons = {
            jpg: '\uD83D\uDDBC\uFE0F', jpeg: '\uD83D\uDDBC\uFE0F', png: '\uD83D\uDDBC\uFE0F', gif: '\uD83D\uDDBC\uFE0F', webp: '\uD83D\uDDBC\uFE0F', bmp: '\uD83D\uDDBC\uFE0F', svg: '\uD83D\uDDBC\uFE0F',
            mp4: '\uD83C\uDFAC', mkv: '\uD83C\uDFAC', avi: '\uD83C\uDFAC', mov: '\uD83C\uDFAC', '3gp': '\uD83C\uDFAC',
            mp3: '\uD83C\uDFB5', wav: '\uD83C\uDFB5', flac: '\uD83C\uDFB5', aac: '\uD83C\uDFB5', ogg: '\uD83C\uDFB5', m4a: '\uD83C\uDFB5',
            pdf: '\uD83D\uDCD5', doc: '\uD83D\uDCDD', docx: '\uD83D\uDCDD', txt: '\uD83D\uDCC4', md: '\uD83D\uDCDD',
            xls: '\uD83D\uDCCA', xlsx: '\uD83D\uDCCA', csv: '\uD83D\uDCCA', ppt: '\uD83D\uDCCA', pptx: '\uD83D\uDCCA',
            zip: '\uD83D\uDCE6', rar: '\uD83D\uDCE6', '7z': '\uD83D\uDCE6', tar: '\uD83D\uDCE6', gz: '\uD83D\uDCE6',
            apk: '\uD83D\uDCF1', json: '\uD83D\uDCCB', xml: '\uD83D\uDCCB', html: '\uD83C\uDF10'
        };
        return icons[ext] || '\uD83D\uDCC4';
    }

    function formatBytes(bytes) {
        if (!bytes || bytes <= 0) return '0 B';
        const units = ['B', 'KB', 'MB', 'GB', 'TB'];
        const i = Math.floor(Math.log(bytes) / Math.log(1024));
        return (bytes / Math.pow(1024, i)).toFixed(1) + ' ' + units[i];
    }

    function escapeHtml(str) {
        const div = document.createElement('div');
        div.textContent = str;
        return div.innerHTML;
    }

    function debounce(fn, delay) {
        let timer;
        return function(...args) {
            clearTimeout(timer);
            timer = setTimeout(() => fn.apply(this, args), delay);
        };
    }
})();
