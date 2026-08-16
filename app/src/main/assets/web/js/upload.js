/**
 * FileDroid Upload Manager
 */
const Upload = (() => {
    let isOpen = false;

    function show() {
        isOpen = true;
        document.getElementById('uploadArea').style.display = 'flex';
        document.getElementById('uploadProgress').innerHTML = '';
    }

    function hide() {
        isOpen = false;
        document.getElementById('uploadArea').style.display = 'none';
    }

    async function handleFiles(fileList) {
        if (!fileList || fileList.length === 0) return;

        const progressContainer = document.getElementById('uploadProgress');

        for (let i = 0; i < fileList.length; i++) {
            const file = fileList[i];

            // Create progress element
            const item = document.createElement('div');
            item.className = 'upload-item';

            const nameEl = document.createElement('span');
            nameEl.className = 'upload-item-name';
            nameEl.textContent = file.name;

            const progressBar = document.createElement('div');
            progressBar.className = 'progress-bar';
            const progressFill = document.createElement('div');
            progressFill.className = 'progress-bar-fill';
            progressFill.style.width = '0%';
            progressBar.appendChild(progressFill);

            const statusEl = document.createElement('span');
            statusEl.className = 'upload-item-status';
            statusEl.textContent = 'Uploading...';

            item.appendChild(nameEl);
            item.appendChild(progressBar);
            item.appendChild(statusEl);
            progressContainer.appendChild(item);

            try {
                await API.uploadFile(file, (progress) => {
                    progressFill.style.width = progress.percent + '%';
                    const speed = formatSpeed(progress.loaded, performance.now());
                    statusEl.textContent = progress.percent + '% • ' + speed;
                });

                statusEl.textContent = '✓ Done';
                statusEl.className = 'upload-item-status success';
                progressFill.style.width = '100%';
            } catch (err) {
                statusEl.textContent = '✗ ' + err.message;
                statusEl.className = 'upload-item-status error';
            }
        }

        // Refresh file listing after uploads
        setTimeout(() => {
            FileManager.refreshCurrentDir();
        }, 1000);
    }

    let uploadStartTime = 0;

    function formatSpeed(loaded, currentTime) {
        if (!uploadStartTime) uploadStartTime = currentTime;
        const elapsed = (currentTime - uploadStartTime) / 1000;
        if (elapsed <= 0) return '';

        const speed = loaded / elapsed;
        if (speed < 1024) return Math.round(speed) + ' B/s';
        if (speed < 1048576) return (speed / 1024).toFixed(1) + ' KB/s';
        return (speed / 1048576).toFixed(1) + ' MB/s';
    }

    function init() {
        const uploadBtn = document.getElementById('uploadBtn');
        const closeUpload = document.getElementById('closeUpload');
        const uploadArea = document.getElementById('uploadArea');
        const dropzone = document.getElementById('dropzone');
        const fileInput = document.getElementById('fileInput');

        uploadBtn.addEventListener('click', () => show());
        closeUpload.addEventListener('click', () => hide());

        // Close on backdrop click
        uploadArea.addEventListener('click', (e) => {
            if (e.target === uploadArea) hide();
        });

        // Dropzone click -> file input
        dropzone.addEventListener('click', () => fileInput.click());

        // File input change
        fileInput.addEventListener('change', (e) => {
            uploadStartTime = 0;
            handleFiles(e.target.files);
            fileInput.value = '';
        });

        // Drag and drop (even though not explicitly selected, it's trivial to add)
        dropzone.addEventListener('dragover', (e) => {
            e.preventDefault();
            dropzone.classList.add('dragover');
        });

        dropzone.addEventListener('dragleave', () => {
            dropzone.classList.remove('dragover');
        });

        dropzone.addEventListener('drop', (e) => {
            e.preventDefault();
            dropzone.classList.remove('dragover');
            uploadStartTime = 0;
            handleFiles(e.dataTransfer.files);
        });
    }

    return { init, show, hide };
})();
