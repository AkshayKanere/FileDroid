/**
 * FileDroid Preview
 * Handles in-browser file preview for images, videos, audio, text and PDFs
 */
const Preview = (() => {
    const imageExts = ['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp', 'svg'];
    const videoExts = ['mp4', 'mkv', 'avi', 'mov', 'webm', 'flv', 'wmv', '3gp'];
    const audioExts = ['mp3', 'wav', 'ogg', 'flac', 'aac', 'm4a', 'wma'];
    const textExts = [
        'txt', 'md', 'log', 'csv', 'json', 'xml', 'html', 'css', 'js',
        'kt', 'java', 'py', 'c', 'cpp', 'h', 'sh', 'bat', 'yml', 'yaml',
        'ini', 'cfg', 'conf', 'properties', 'gradle', 'toml'
    ];

    function getExt(name) {
        return (name || '').split('.').pop().toLowerCase();
    }

    function canPreview(item) {
        if (item.isDirectory) return false;
        const ext = getExt(item.name);
        return imageExts.includes(ext) || videoExts.includes(ext) ||
               audioExts.includes(ext) || textExts.includes(ext) || ext === 'pdf';
    }

    function show(item) {
        if (!canPreview(item)) {
            // Direct download for non-previewable files
            window.location.href = API.getDownloadUrl(item.path);
            return;
        }

        const modal = document.getElementById('previewModal');
        const content = document.getElementById('previewContent');
        const title = document.getElementById('previewTitle');
        const downloadLink = document.getElementById('previewDownload');

        title.textContent = item.name;
        downloadLink.href = API.getDownloadUrl(item.path);
        content.innerHTML = '';

        const ext = getExt(item.name);
        const previewUrl = API.getPreviewUrl(item.path);

        if (imageExts.includes(ext)) {
            const img = document.createElement('img');
            img.src = previewUrl;
            img.alt = item.name;
            content.appendChild(img);
        } else if (videoExts.includes(ext)) {
            const video = document.createElement('video');
            video.src = previewUrl;
            video.controls = true;
            video.autoplay = false;
            video.preload = 'metadata';
            video.style.maxWidth = '100%';
            video.style.maxHeight = '100%';
            content.appendChild(video);
        } else if (audioExts.includes(ext)) {
            const audioContainer = document.createElement('div');
            audioContainer.style.textAlign = 'center';

            const icon = document.createElement('div');
            icon.textContent = '🎵';
            icon.style.fontSize = '80px';
            icon.style.marginBottom = '20px';
            audioContainer.appendChild(icon);

            const audio = document.createElement('audio');
            audio.src = previewUrl;
            audio.controls = true;
            audio.style.width = '100%';
            audio.style.maxWidth = '400px';
            audioContainer.appendChild(audio);

            content.appendChild(audioContainer);
        } else if (textExts.includes(ext)) {
            const pre = document.createElement('pre');
            pre.textContent = 'Loading...';
            content.appendChild(pre);

            fetch(previewUrl)
                .then(r => r.text())
                .then(text => {
                    // Limit preview to 500KB
                    if (text.length > 512000) {
                        text = text.substring(0, 512000) + '\n\n--- Preview truncated (file too large) ---';
                    }
                    pre.textContent = text;
                })
                .catch(err => {
                    pre.textContent = 'Error loading file: ' + err.message;
                });
        } else if (ext === 'pdf') {
            const embed = document.createElement('embed');
            embed.src = previewUrl;
            embed.type = 'application/pdf';
            embed.style.width = '100%';
            embed.style.height = '100%';
            content.appendChild(embed);
        }

        modal.style.display = 'flex';

        // Close on ESC
        const escHandler = (e) => {
            if (e.key === 'Escape') {
                hide();
                document.removeEventListener('keydown', escHandler);
            }
        };
        document.addEventListener('keydown', escHandler);
    }

    function hide() {
        const modal = document.getElementById('previewModal');
        const content = document.getElementById('previewContent');

        // Stop any playing media
        const video = content.querySelector('video');
        if (video) video.pause();
        const audio = content.querySelector('audio');
        if (audio) audio.pause();

        modal.style.display = 'none';
        content.innerHTML = '';
    }

    function init() {
        document.getElementById('closePreview').addEventListener('click', hide);

        // Close preview on backdrop click
        document.getElementById('previewModal').addEventListener('click', (e) => {
            if (e.target.id === 'previewModal' || e.target.className === 'preview-content') {
                hide();
            }
        });
    }

    return { init, show, hide, canPreview };
})();
