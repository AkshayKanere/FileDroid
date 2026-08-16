/**
 * FileDroid Receive Mode - Upload Page
 * Browser sends files to phone
 */
(function() {
    'use strict';

    let uploadQueue = [];
    let isUploading = false;
    let completedCount = 0;
    let failedCount = 0;
    let cancelledCount = 0;
    let totalBytes = 0;
    let activeXhr = null;

    init();

    async function init() {
        setupDropzone();
        setupButtons();
        await loadServerInfo();
    }

    async function loadServerInfo() {
        try {
            const res = await fetch('/api/info');
            const info = await res.json();
            document.getElementById('serverInfo').textContent =
                `${info.deviceName} \u2022 ${formatBytes(info.freeSpace)} free of ${formatBytes(info.totalSpace)} \u2022 Max: ${formatBytes(info.uploadMaxSize)}`;
        } catch (e) {}
    }

    function setupDropzone() {
        const dropzone = document.getElementById('dropzone');
        const fileInput = document.getElementById('fileInput');

        dropzone.addEventListener('click', () => fileInput.click());
        fileInput.addEventListener('change', () => {
            if (fileInput.files.length > 0) {
                addFilesToQueue(Array.from(fileInput.files));
                fileInput.value = '';
            }
        });

        ['dragenter', 'dragover'].forEach(ev => {
            dropzone.addEventListener(ev, e => { e.preventDefault(); dropzone.classList.add('dragover'); });
        });
        ['dragleave', 'drop'].forEach(ev => {
            dropzone.addEventListener(ev, e => { e.preventDefault(); dropzone.classList.remove('dragover'); });
        });
        dropzone.addEventListener('drop', e => {
            const files = Array.from(e.dataTransfer.files);
            if (files.length > 0) addFilesToQueue(files);
        });

        document.addEventListener('dragover', e => e.preventDefault());
        document.addEventListener('drop', e => e.preventDefault());
    }

    function setupButtons() {
        document.getElementById('selectBtn').addEventListener('click', () => {
            document.getElementById('fileInput').click();
        });
        document.getElementById('cancelAllBtn').addEventListener('click', cancelAll);
    }

    // ---- Upload Queue ----

    function addFilesToQueue(files) {
        for (const file of files) {
            const id = 'upload_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
            uploadQueue.push({
                id, file, name: file.name, size: file.size,
                status: 'pending', progress: 0, speed: 0, error: null, xhr: null
            });
            renderUploadItem(uploadQueue[uploadQueue.length - 1]);
        }
        showCancelButton();
        if (!isUploading) processQueue();
    }

    async function processQueue() {
        isUploading = true;
        while (true) {
            const entry = uploadQueue.find(e => e.status === 'pending');
            if (!entry) break;

            entry.status = 'uploading';
            updateUploadItem(entry);

            try {
                await uploadFile(entry);
                if (entry.status === 'cancelled') {
                    cancelledCount++;
                } else {
                    entry.status = 'done';
                    entry.progress = 100;
                    completedCount++;
                    totalBytes += entry.size;
                }
            } catch (e) {
                if (entry.status !== 'cancelled') {
                    entry.status = 'error';
                    entry.error = e.message;
                    failedCount++;
                }
            }
            updateUploadItem(entry);
        }
        isUploading = false;
        updateCancelButton();
        updateSummary();
    }

    function uploadFile(entry) {
        return new Promise((resolve, reject) => {
            const xhr = new XMLHttpRequest();
            entry.xhr = xhr;
            activeXhr = xhr;
            const formData = new FormData();
            formData.append('file', entry.file);
            const startTime = Date.now();

            xhr.upload.addEventListener('progress', e => {
                if (e.lengthComputable) {
                    entry.progress = Math.round((e.loaded / e.total) * 100);
                    const elapsed = (Date.now() - startTime) / 1000;
                    entry.speed = elapsed > 0 ? e.loaded / elapsed : 0;
                    updateUploadItem(entry);
                }
            });

            xhr.addEventListener('load', () => {
                activeXhr = null;
                entry.xhr = null;
                xhr.status >= 200 && xhr.status < 300 ? resolve() : reject(new Error(`Failed (${xhr.status})`));
            });
            xhr.addEventListener('error', () => { activeXhr = null; entry.xhr = null; reject(new Error('Network error')); });
            xhr.addEventListener('abort', () => { activeXhr = null; entry.xhr = null; entry.status = 'cancelled'; resolve(); });

            xhr.open('POST', '/api/upload');
            xhr.send(formData);
        });
    }

    function cancelAll() {
        for (const entry of uploadQueue) {
            if (entry.status === 'pending') {
                entry.status = 'cancelled';
                cancelledCount++;
                updateUploadItem(entry);
            } else if (entry.status === 'uploading' && entry.xhr) {
                entry.xhr.abort();
            }
        }
        updateCancelButton();
    }

    function showCancelButton() {
        const btn = document.getElementById('cancelAllBtn');
        btn.style.display = '';
        btn.disabled = false;
    }

    function updateCancelButton() {
        const btn = document.getElementById('cancelAllBtn');
        if (uploadQueue.length === 0) return;
        btn.style.display = '';
        const hasActive = uploadQueue.some(e => e.status === 'pending' || e.status === 'uploading');
        btn.disabled = !hasActive;
    }

    // ---- UI ----

    function renderUploadItem(entry) {
        const list = document.getElementById('uploadList');
        const div = document.createElement('div');
        div.id = entry.id;
        div.className = 'upload-item';

        const headerDiv = document.createElement('div');
        headerDiv.className = 'upload-item-header';
        headerDiv.innerHTML = `<span class="upload-item-icon">${getFileIcon(entry.name)}</span>
            <span class="upload-item-name">${escapeHtml(entry.name)}</span>
            <span class="upload-item-size">${formatBytes(entry.size)}</span>`;

        div.appendChild(headerDiv);

        const progressDiv = document.createElement('div');
        progressDiv.className = 'upload-item-progress';
        const progressBar = document.createElement('div');
        progressBar.className = 'progress-bar';
        const progressFill = document.createElement('div');
        progressFill.className = 'progress-bar-fill';
        progressFill.style.width = '0%';
        progressBar.appendChild(progressFill);
        const statusSpan = document.createElement('span');
        statusSpan.className = 'upload-item-status';
        statusSpan.textContent = 'Waiting...';
        progressDiv.appendChild(progressBar);
        progressDiv.appendChild(statusSpan);
        div.appendChild(progressDiv);

        list.appendChild(div);
    }

    function updateUploadItem(entry) {
        const div = document.getElementById(entry.id);
        if (!div) return;
        const fill = div.querySelector('.progress-bar-fill');
        const status = div.querySelector('.upload-item-status');

        fill.style.width = entry.progress + '%';

        switch (entry.status) {
            case 'uploading':
                div.className = 'upload-item active';
                fill.className = 'progress-bar-fill';
                status.textContent = `${entry.progress}% \u2022 ${formatBytes(entry.speed)}/s`;
                status.className = 'upload-item-status';
                div.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
                break;
            case 'done':
                div.className = 'upload-item completed';
                fill.className = 'progress-bar-fill success';
                fill.style.width = '100%';
                status.textContent = '\u2705 Sent';
                status.className = 'upload-item-status success';
                break;
            case 'error':
                div.className = 'upload-item completed';
                fill.className = 'progress-bar-fill error';
                status.textContent = '\u274C ' + (entry.error || 'Failed');
                status.className = 'upload-item-status error';
                break;
            case 'cancelled':
                div.className = 'upload-item completed';
                fill.className = 'progress-bar-fill';
                fill.style.width = '0%';
                status.textContent = '\u26D4 Cancelled';
                status.className = 'upload-item-status error';
                break;
            default:
                status.textContent = 'Waiting...';
        }
    }

    function updateSummary() {
        const summary = document.getElementById('uploadSummary');
        const text = document.getElementById('summaryText');
        if (completedCount > 0 || failedCount > 0 || cancelledCount > 0) {
            summary.style.display = 'block';
            let msg = `\u2705 ${completedCount} sent (${formatBytes(totalBytes)})`;
            if (failedCount > 0) msg += ` \u2022 \u274C ${failedCount} failed`;
            if (cancelledCount > 0) msg += ` \u2022 \u26D4 ${cancelledCount} cancelled`;
            text.textContent = msg;
        }
    }

    // ---- Utilities ----

    function getFileIcon(name) {
        const ext = (name || '').split('.').pop().toLowerCase();
        const m = { jpg:'\uD83D\uDDBC\uFE0F',jpeg:'\uD83D\uDDBC\uFE0F',png:'\uD83D\uDDBC\uFE0F',gif:'\uD83D\uDDBC\uFE0F',webp:'\uD83D\uDDBC\uFE0F',
            mp4:'\uD83C\uDFAC',mkv:'\uD83C\uDFAC',avi:'\uD83C\uDFAC',mov:'\uD83C\uDFAC',
            mp3:'\uD83C\uDFB5',wav:'\uD83C\uDFB5',flac:'\uD83C\uDFB5',aac:'\uD83C\uDFB5',
            pdf:'\uD83D\uDCD5',doc:'\uD83D\uDCDD',docx:'\uD83D\uDCDD',txt:'\uD83D\uDCC4',
            xls:'\uD83D\uDCCA',xlsx:'\uD83D\uDCCA',zip:'\uD83D\uDCE6',apk:'\uD83D\uDCF1' };
        return m[ext] || '\uD83D\uDCC4';
    }

    function formatBytes(bytes) {
        if (!bytes || bytes <= 0) return '0 B';
        const u = ['B','KB','MB','GB','TB'];
        const i = Math.floor(Math.log(bytes) / Math.log(1024));
        return (bytes / Math.pow(1024, i)).toFixed(1) + ' ' + u[i];
    }

    function escapeHtml(s) { const d = document.createElement('div'); d.textContent = s; return d.innerHTML; }
})();
