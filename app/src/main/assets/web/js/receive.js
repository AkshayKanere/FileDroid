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
    let totalBytes = 0;

    // ---- Init ----
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
            const el = document.getElementById('serverInfo');
            const free = formatBytes(info.freeSpace);
            const total = formatBytes(info.totalSpace);
            el.textContent = `${info.deviceName} \u2022 ${free} free of ${total} \u2022 Max upload: ${formatBytes(info.uploadMaxSize)}`;
        } catch (e) {}
    }

    function setupDropzone() {
        const dropzone = document.getElementById('dropzone');
        const fileInput = document.getElementById('fileInput');

        // Click to select
        dropzone.addEventListener('click', () => fileInput.click());

        fileInput.addEventListener('change', () => {
            if (fileInput.files.length > 0) {
                addFilesToQueue(Array.from(fileInput.files));
                fileInput.value = '';
            }
        });

        // Drag and drop
        ['dragenter', 'dragover'].forEach(event => {
            dropzone.addEventListener(event, (e) => {
                e.preventDefault();
                dropzone.classList.add('dragover');
            });
        });

        ['dragleave', 'drop'].forEach(event => {
            dropzone.addEventListener(event, (e) => {
                e.preventDefault();
                dropzone.classList.remove('dragover');
            });
        });

        dropzone.addEventListener('drop', (e) => {
            const files = Array.from(e.dataTransfer.files);
            if (files.length > 0) {
                addFilesToQueue(files);
            }
        });

        // Prevent default drag on body
        document.addEventListener('dragover', (e) => e.preventDefault());
        document.addEventListener('drop', (e) => e.preventDefault());
    }

    function setupButtons() {
        document.getElementById('selectBtn').addEventListener('click', () => {
            document.getElementById('fileInput').click();
        });
    }

    // ---- Upload Queue ----

    function addFilesToQueue(files) {
        for (const file of files) {
            const id = 'upload_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
            const entry = {
                id,
                file,
                name: file.name,
                size: file.size,
                status: 'pending', // pending, uploading, done, error
                progress: 0,
                speed: 0,
                error: null
            };
            uploadQueue.push(entry);
            renderUploadItem(entry);
        }

        if (!isUploading) {
            processQueue();
        }
    }

    async function processQueue() {
        isUploading = true;

        while (uploadQueue.length > 0) {
            const entry = uploadQueue.find(e => e.status === 'pending');
            if (!entry) break;

            entry.status = 'uploading';
            updateUploadItem(entry);

            try {
                await uploadFile(entry);
                entry.status = 'done';
                entry.progress = 100;
                completedCount++;
                totalBytes += entry.size;
            } catch (e) {
                entry.status = 'error';
                entry.error = e.message;
                failedCount++;
            }

            updateUploadItem(entry);
        }

        isUploading = false;
        updateSummary();
    }

    function uploadFile(entry) {
        return new Promise((resolve, reject) => {
            const xhr = new XMLHttpRequest();
            const formData = new FormData();
            formData.append('file', entry.file);

            const startTime = Date.now();

            xhr.upload.addEventListener('progress', (e) => {
                if (e.lengthComputable) {
                    entry.progress = Math.round((e.loaded / e.total) * 100);
                    const elapsed = (Date.now() - startTime) / 1000;
                    entry.speed = elapsed > 0 ? e.loaded / elapsed : 0;
                    updateUploadItem(entry);
                }
            });

            xhr.addEventListener('load', () => {
                if (xhr.status >= 200 && xhr.status < 300) {
                    resolve();
                } else {
                    reject(new Error(`Upload failed (${xhr.status})`));
                }
            });

            xhr.addEventListener('error', () => reject(new Error('Network error')));
            xhr.addEventListener('abort', () => reject(new Error('Upload cancelled')));

            xhr.open('POST', '/api/upload');
            xhr.send(formData);
        });
    }

    // ---- UI Rendering ----

    function renderUploadItem(entry) {
        const list = document.getElementById('uploadList');
        const div = document.createElement('div');
        div.id = entry.id;
        div.className = 'upload-item';

        div.innerHTML = `
            <div class="upload-item-header">
                <span class="upload-item-icon">${getFileIcon(entry.name)}</span>
                <span class="upload-item-name">${escapeHtml(entry.name)}</span>
                <span class="upload-item-size">${formatBytes(entry.size)}</span>
            </div>
            <div class="upload-item-progress">
                <div class="progress-bar">
                    <div class="progress-bar-fill" style="width: 0%"></div>
                </div>
                <span class="upload-item-status">Waiting...</span>
            </div>
        `;

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
                fill.classList.remove('error', 'success');
                status.textContent = `${entry.progress}% \u2022 ${formatBytes(entry.speed)}/s`;
                break;
            case 'done':
                fill.classList.add('success');
                fill.style.width = '100%';
                status.textContent = '\u2705 Sent';
                status.classList.add('success');
                break;
            case 'error':
                fill.classList.add('error');
                status.textContent = '\u274C ' + (entry.error || 'Failed');
                status.classList.add('error');
                break;
            default:
                status.textContent = 'Waiting...';
        }
    }

    function updateSummary() {
        const summary = document.getElementById('uploadSummary');
        const text = document.getElementById('summaryText');

        if (completedCount > 0 || failedCount > 0) {
            summary.style.display = 'block';
            let msg = `✅ ${completedCount} file${completedCount !== 1 ? 's' : ''} sent (${formatBytes(totalBytes)})`;
            if (failedCount > 0) msg += ` • ❌ ${failedCount} failed`;
            text.textContent = msg;
        }
    }

    // ---- Utilities ----

    function getFileIcon(name) {
        const ext = (name || '').split('.').pop().toLowerCase();
        const icons = {
            jpg: '🖼️', jpeg: '🖼️', png: '🖼️', gif: '🖼️', webp: '🖼️',
            mp4: '🎬', mkv: '🎬', avi: '🎬', mov: '🎬',
            mp3: '🎵', wav: '🎵', flac: '🎵', aac: '🎵',
            pdf: '📕', doc: '📝', docx: '📝', txt: '📄',
            xls: '📊', xlsx: '📊', zip: '📦', apk: '📱'
        };
        return icons[ext] || '📄';
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
})();
