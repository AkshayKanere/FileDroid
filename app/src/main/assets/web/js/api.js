/**
 * FileDroid API Client
 * Handles all communication with the server API
 */
const API = (() => {
    // Extract token from URL
    const urlParams = new URLSearchParams(window.location.search);
    const token = urlParams.get('token') || '';

    function getBaseUrl() {
        return window.location.origin;
    }

    function buildUrl(endpoint, params = {}) {
        const url = new URL(endpoint, getBaseUrl());
        url.searchParams.set('token', token);
        Object.entries(params).forEach(([k, v]) => {
            if (v !== undefined && v !== null) url.searchParams.set(k, v);
        });
        return url.toString();
    }

    async function request(endpoint, params = {}, options = {}) {
        const url = buildUrl(endpoint, params);
        const resp = await fetch(url, {
            credentials: 'same-origin',
            ...options,
            headers: {
                ...(options.headers || {}),
            }
        });

        if (resp.status === 403) {
            const data = await resp.json().catch(() => ({}));
            if (data.pinRequired) {
                throw { pinRequired: true };
            }
        }

        if (!resp.ok) {
            const data = await resp.json().catch(() => ({ error: `HTTP ${resp.status}` }));
            throw new Error(data.error || `HTTP ${resp.status}`);
        }

        return resp;
    }

    async function json(endpoint, params = {}, options = {}) {
        const resp = await request(endpoint, params, options);
        return resp.json();
    }

    return {
        token,

        async listFiles(path) {
            const params = path ? { path } : {};
            return json('/api/files', params);
        },

        async search(query, path) {
            return json('/api/search', { q: query, path });
        },

        async getInfo() {
            return json('/api/info');
        },

        async getStatus() {
            return json('/api/status');
        },

        getDownloadUrl(path) {
            return buildUrl('/api/download', { path });
        },

        getThumbnailUrl(path, size = 256) {
            return buildUrl('/api/thumbnail', { path, size });
        },

        getPreviewUrl(path) {
            return buildUrl('/api/preview', { path });
        },

        async downloadZip(paths) {
            const url = buildUrl('/api/download-zip');
            const resp = await fetch(url, {
                method: 'POST',
                credentials: 'same-origin',
                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                body: 'paths=' + encodeURIComponent(JSON.stringify(paths))
            });

            if (!resp.ok) {
                const data = await resp.json().catch(() => ({}));
                throw new Error(data.error || 'Download failed');
            }

            // Trigger download
            const blob = await resp.blob();
            const a = document.createElement('a');
            a.href = URL.createObjectURL(blob);
            a.download = 'FileDroid_download.zip';
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            URL.revokeObjectURL(a.href);
        },

        uploadFile(file, onProgress) {
            return new Promise((resolve, reject) => {
                const xhr = new XMLHttpRequest();
                const url = buildUrl('/api/upload');

                xhr.open('POST', url);

                xhr.upload.addEventListener('progress', (e) => {
                    if (e.lengthComputable && onProgress) {
                        onProgress({
                            loaded: e.loaded,
                            total: e.total,
                            percent: Math.round((e.loaded / e.total) * 100)
                        });
                    }
                });

                xhr.addEventListener('load', () => {
                    if (xhr.status >= 200 && xhr.status < 300) {
                        try {
                            resolve(JSON.parse(xhr.responseText));
                        } catch {
                            resolve({ success: true });
                        }
                    } else {
                        try {
                            const data = JSON.parse(xhr.responseText);
                            reject(new Error(data.error || `Upload failed (${xhr.status})`));
                        } catch {
                            reject(new Error(`Upload failed (${xhr.status})`));
                        }
                    }
                });

                xhr.addEventListener('error', () => reject(new Error('Network error during upload')));
                xhr.addEventListener('abort', () => reject(new Error('Upload cancelled')));

                const formData = new FormData();
                formData.append('file', file);
                xhr.send(formData);
            });
        },

        async verifyPin(pin) {
            const url = buildUrl('/api/auth/pin');
            const resp = await fetch(url, {
                method: 'POST',
                credentials: 'same-origin',
                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                body: 'pin=' + encodeURIComponent(pin)
            });

            const data = await resp.json();
            if (!resp.ok) {
                throw new Error(data.error || 'PIN verification failed');
            }
            return data;
        }
    };
})();
