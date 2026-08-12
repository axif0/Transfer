document.addEventListener('DOMContentLoaded', () => {
    const dropZone = document.getElementById('drop-zone');
    const fileInput = document.getElementById('file-input');
    const filesTableBody = document.querySelector('#files-table tbody');
    const filesCards = document.getElementById('files-cards');
    const uploadProgressContainer = document.getElementById('upload-progress-container');
    const downloadProgressContainer = document.getElementById('download-progress-container');
    const noFilesMessage = document.getElementById('no-files-message');
    const themeToggleButton = document.getElementById('theme-toggle');
    const themeIcon = document.getElementById('theme-icon');
    const pasteButton = document.getElementById('paste-button');
    const downloadAllZipButton = document.getElementById('download-all-zip-button');
    const selectAllCheckbox = document.getElementById('select-all-checkbox');
    const fileSearch = document.getElementById('file-search');
    const fabUpload = document.getElementById('fab-upload');
    const readonlyBanner = document.getElementById('readonly-banner');
    const previewOverlay = document.getElementById('preview-modal-overlay');
    const previewTitle = document.getElementById('preview-title');
    const previewBody = document.getElementById('preview-body');
    const previewClose = document.getElementById('preview-close');

    const confirmationModalOverlay = document.getElementById('confirmation-modal-overlay');
    const confirmationModalMessage = document.getElementById('confirmation-modal-message');
    const modalConfirmButton = document.getElementById('modal-confirm-button');
    const modalCancelButton = document.getElementById('modal-cancel-button');
    const doNotAskAgainCheckbox = document.getElementById('do-not-ask-again');

    let allFiles = [];
    let canUpload = true;
    let canDelete = true;

    function applyWriteMode() {
        document.body.classList.toggle('no-upload', !canUpload);
        document.body.classList.toggle('no-delete', !canDelete);
        readonlyBanner.hidden = canUpload;
    }

    function applyTheme(theme) {
        if (theme === 'dark') {
            document.body.classList.add('dark-mode');
            themeIcon.textContent = '☀️';
        } else {
            document.body.classList.remove('dark-mode');
            themeIcon.textContent = '🌙';
        }
    }

    const savedTheme = localStorage.getItem('theme');
    if (savedTheme) {
        applyTheme(savedTheme);
    } else {
        const prefersDark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
        applyTheme(prefersDark ? 'dark' : 'light');
    }

    themeToggleButton.addEventListener('click', () => {
        const currentTheme = document.body.classList.contains('dark-mode') ? 'dark' : 'light';
        const newTheme = currentTheme === 'dark' ? 'light' : 'dark';
        applyTheme(newTheme);
        localStorage.setItem('theme', newTheme);
    });

    let currentConfirmCallback = null;

    function showConfirmModal(message, onConfirm) {
        confirmationModalMessage.textContent = message;
        currentConfirmCallback = onConfirm;
        doNotAskAgainCheckbox.checked = false;
        confirmationModalOverlay.classList.add('active');
        modalConfirmButton.onclick = null;
        modalCancelButton.onclick = null;

        modalConfirmButton.onclick = () => {
            if (doNotAskAgainCheckbox.checked) {
                localStorage.setItem('doNotAskAgainDelete', 'true');
            }
            if (currentConfirmCallback) currentConfirmCallback(true);
            hideConfirmModal();
        };

        modalCancelButton.onclick = () => {
            if (currentConfirmCallback) currentConfirmCallback(false);
            hideConfirmModal();
        };

        confirmationModalOverlay.addEventListener('click', (event) => {
            if (event.target === confirmationModalOverlay) {
                if (currentConfirmCallback) currentConfirmCallback(false);
                hideConfirmModal();
            }
        }, { once: true });
    }

    function hideConfirmModal() {
        confirmationModalOverlay.classList.remove('active');
        currentConfirmCallback = null;
    }

    function formatBytes(bytes, decimals = 2) {
        if (bytes === 0) return '0 Bytes';
        const k = 1024;
        const dm = decimals < 0 ? 0 : decimals;
        const sizes = ['Bytes', 'KB', 'MB', 'GB', 'TB', 'PB', 'EB', 'ZB', 'YB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(dm)) + ' ' + sizes[i];
    }

    function fileExt(name) {
        const i = name.lastIndexOf('.');
        return i >= 0 ? name.slice(i + 1).toLowerCase() : '';
    }

    function isImage(name) {
        return ['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'svg'].includes(fileExt(name));
    }

    function isText(name) {
        return ['txt', 'md', 'json', 'xml', 'csv', 'log', 'kt', 'java', 'js', 'ts', 'css', 'html', 'py', 'sh', 'yml', 'yaml', 'toml', 'ini', 'conf'].includes(fileExt(name));
    }

    function typeEmoji(name) {
        const e = fileExt(name);
        if (isImage(name)) return '🖼️';
        if (['mp4', 'mkv', 'webm', 'mov', 'avi'].includes(e)) return '🎬';
        if (['mp3', 'wav', 'flac', 'ogg', 'm4a'].includes(e)) return '🎵';
        if (['pdf'].includes(e)) return '📄';
        if (['zip', 'rar', '7z', 'tar', 'gz'].includes(e)) return '📦';
        if (isText(name)) return '📝';
        return '📁';
    }

    function createProgressItem(label, container) {
        const progressItem = document.createElement('div');
        progressItem.className = 'progress-bar-item';
        const fileNameSpan = document.createElement('span');
        fileNameSpan.textContent = label;
        const progressBar = document.createElement('div');
        progressBar.className = 'progress-bar';
        const progressBarFill = document.createElement('div');
        progressBarFill.className = 'progress-bar-fill';
        const progressStatus = document.createElement('span');
        progressStatus.className = 'progress-bar-status';
        progressBar.appendChild(progressBarFill);
        progressItem.appendChild(fileNameSpan);
        progressItem.appendChild(progressBar);
        progressItem.appendChild(progressStatus);
        container.prepend(progressItem);
        return { progressItem, progressBarFill, progressStatus };
    }

    function downloadWithProgress(url, fileName) {
        const { progressItem, progressBarFill, progressStatus } =
            createProgressItem(`↓ ${fileName}: `, downloadProgressContainer || uploadProgressContainer);

        const xhr = new XMLHttpRequest();
        xhr.open('GET', url, true);
        xhr.responseType = 'blob';

        xhr.addEventListener('progress', (event) => {
            if (event.lengthComputable) {
                const pct = (event.loaded / event.total) * 100;
                progressBarFill.style.width = pct.toFixed(2) + '%';
                progressStatus.textContent = `${pct.toFixed(0)}%`;
            } else {
                progressStatus.textContent = formatBytes(event.loaded);
            }
        });

        xhr.addEventListener('load', () => {
            if (xhr.status >= 200 && xhr.status < 300) {
                progressBarFill.style.backgroundColor = 'var(--success-color)';
                progressStatus.textContent = 'Done';
                const blobUrl = URL.createObjectURL(xhr.response);
                const a = document.createElement('a');
                a.href = blobUrl;
                a.download = fileName;
                document.body.appendChild(a);
                a.click();
                a.remove();
                URL.revokeObjectURL(blobUrl);
                setTimeout(() => progressItem.remove(), 2500);
            } else {
                progressBarFill.style.backgroundColor = 'var(--error-color)';
                progressStatus.textContent = `Error: ${xhr.status}`;
            }
        });

        xhr.addEventListener('error', () => {
            progressBarFill.style.backgroundColor = 'var(--error-color)';
            progressStatus.textContent = 'Network Error';
        });

        xhr.send();
    }

    function openPreview(file) {
        previewTitle.textContent = file.name;
        previewBody.innerHTML = '';
        previewOverlay.classList.add('active');

        if (isImage(file.name)) {
            const img = document.createElement('img');
            img.src = file.downloadUrl;
            img.alt = file.name;
            previewBody.appendChild(img);
            return;
        }

        if (isText(file.name)) {
            previewBody.textContent = 'Loading…';
            fetch(file.downloadUrl)
                .then(r => r.text())
                .then(text => {
                    const pre = document.createElement('pre');
                    pre.textContent = text.length > 200000 ? text.slice(0, 200000) + '\n… (truncated)' : text;
                    previewBody.innerHTML = '';
                    previewBody.appendChild(pre);
                })
                .catch(() => {
                    previewBody.textContent = 'Could not load preview.';
                });
            return;
        }

        previewBody.textContent = 'No preview for this file type. Use Download.';
    }

    function closePreview() {
        previewOverlay.classList.remove('active');
        previewBody.innerHTML = '';
    }

    previewClose.addEventListener('click', closePreview);
    previewOverlay.addEventListener('click', (e) => {
        if (e.target === previewOverlay) closePreview();
    });

    function makeActionIcons(file) {
        const actionIconsContainer = document.createElement('div');
        actionIconsContainer.className = 'action-icons-container';

        if (isImage(file.name) || isText(file.name)) {
            const previewBtn = document.createElement('button');
            previewBtn.className = 'icon-button';
            previewBtn.title = `Preview ${file.name}`;
            previewBtn.textContent = '👁';
            previewBtn.onclick = (event) => {
                event.preventDefault();
                event.stopPropagation();
                openPreview(file);
            };
            actionIconsContainer.appendChild(previewBtn);
        }

        const downloadLink = document.createElement('a');
        downloadLink.href = '#';
        downloadLink.className = 'icon-button download-icon';
        downloadLink.title = `Download ${file.name}`;
        downloadLink.innerHTML = `
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
                <path d="M12 16L7 11H11V4H13V11H17L12 16ZM20 18H4V20H20V18Z"/>
            </svg>
        `;
        downloadLink.onclick = (event) => {
            event.preventDefault();
            downloadWithProgress(file.downloadUrl, file.name);
        };
        actionIconsContainer.appendChild(downloadLink);

        if (canDelete) {
            const deleteButton = document.createElement('button');
            deleteButton.className = 'icon-button delete-icon';
            deleteButton.title = `Delete ${file.name}`;
            deleteButton.innerHTML = `
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
                    <path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12ZM19 4h-3.5l-1-1h-5l-1 1H5V6h14V4Z"/>
                </svg>
            `;
            deleteButton.onclick = (event) => {
                event.stopPropagation();
                confirmDeleteFile(file.name);
            };
            actionIconsContainer.appendChild(deleteButton);
        }

        return actionIconsContainer;
    }

    async function fetchFiles() {
        try {
            const response = await fetch('/api/files');
            if (!response.ok) {
                const errorText = await response.text();
                console.error('Error fetching files:', response.status, errorText);
                filesTableBody.innerHTML = `<tr><td colspan="6" style="color: var(--error-color);">Error loading files: ${errorText}</td></tr>`;
                filesCards.innerHTML = '';
                noFilesMessage.style.display = 'none';
                downloadAllZipButton.style.display = 'none';
                return;
            }
            const data = await response.json();
            allFiles = data.files || [];
            canUpload = data.canUpload !== false;
            canDelete = data.canDelete !== false;
            applyWriteMode();
            applyFilter();
        } catch (error) {
            console.error('Failed to fetch files:', error);
            filesTableBody.innerHTML = `<tr><td colspan="6" style="color: var(--error-color);">Could not connect to server or error fetching files.</td></tr>`;
            filesCards.innerHTML = '';
            noFilesMessage.style.display = 'none';
            downloadAllZipButton.style.display = 'none';
        }
    }

    function applyFilter() {
        const q = (fileSearch?.value || '').trim().toLowerCase();
        const filtered = q ? allFiles.filter(f => f.name.toLowerCase().includes(q)) : allFiles;
        renderFiles(filtered);
    }

    function renderFiles(files) {
        filesTableBody.innerHTML = '';
        filesCards.innerHTML = '';
        selectAllCheckbox.checked = false;
        selectAllCheckbox.indeterminate = false;
        updateDownloadButtonLabel();

        if (!files || files.length === 0) {
            noFilesMessage.style.display = 'block';
            downloadAllZipButton.style.display = 'none';
            return;
        }
        noFilesMessage.style.display = 'none';
        downloadAllZipButton.style.display = 'block';

        files.forEach(file => {
            const row = filesTableBody.insertRow();
            row.dataset.fileName = file.name;

            const checkCell = row.insertCell();
            checkCell.dataset.label = 'Select';
            const checkbox = document.createElement('input');
            checkbox.type = 'checkbox';
            checkbox.className = 'file-select';
            checkbox.dataset.fileName = file.name;
            checkCell.appendChild(checkbox);

            const nameCell = row.insertCell();
            nameCell.textContent = file.name;
            nameCell.dataset.label = 'Name';

            const sizeCell = row.insertCell();
            sizeCell.textContent = file.formattedSize || formatBytes(file.size);
            sizeCell.dataset.label = 'Size';

            const modifiedCell = row.insertCell();
            modifiedCell.textContent = file.lastModified;
            modifiedCell.dataset.label = 'Last Modified';

            const typeCell = row.insertCell();
            typeCell.textContent = file.type;
            typeCell.dataset.label = 'Type';

            const actionsCell = row.insertCell();
            actionsCell.dataset.label = 'Actions';
            actionsCell.style.textAlign = 'right';
            actionsCell.appendChild(makeActionIcons(file));

            // Mobile card
            const card = document.createElement('div');
            card.className = 'file-card';
            card.dataset.fileName = file.name;

            if (isImage(file.name)) {
                const img = document.createElement('img');
                img.className = 'file-card-thumb';
                img.src = file.downloadUrl;
                img.alt = '';
                img.loading = 'lazy';
                card.appendChild(img);
            } else {
                const icon = document.createElement('div');
                icon.className = 'file-card-icon';
                icon.textContent = typeEmoji(file.name);
                card.appendChild(icon);
            }

            const body = document.createElement('div');
            body.className = 'file-card-body';
            const nameEl = document.createElement('div');
            nameEl.className = 'file-card-name';
            nameEl.textContent = file.name;
            const meta = document.createElement('div');
            meta.className = 'file-card-meta';
            meta.textContent = `${file.formattedSize || formatBytes(file.size)} · ${file.lastModified || ''}`;
            const actions = document.createElement('div');
            actions.className = 'file-card-actions';
            actions.appendChild(makeActionIcons(file));
            body.appendChild(nameEl);
            body.appendChild(meta);
            body.appendChild(actions);
            card.appendChild(body);
            filesCards.appendChild(card);
        });
    }

    function confirmDeleteFile(fileName) {
        if (!canDelete) return;
        const doNotAskAgain = localStorage.getItem('doNotAskAgainDelete') === 'true';
        if (doNotAskAgain) {
            deleteFile(fileName);
        } else {
            showConfirmModal(`Delete "${fileName}"?`, (confirmed) => {
                if (confirmed) deleteFile(fileName);
            });
        }
    }

    function showError(err) {
        const errorMsg = document.createElement('p');
        errorMsg.textContent = err;
        errorMsg.style.color = 'var(--error-color)';
        errorMsg.style.marginTop = '10px';
        errorMsg.style.textAlign = 'center';
        uploadProgressContainer.appendChild(errorMsg);
        setTimeout(() => errorMsg.remove(), 5000);
    }

    async function deleteFile(fileName) {
        try {
            const response = await fetch('/api/delete', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ filename: fileName })
            });
            const result = await response.json();
            if (response.ok) {
                allFiles = allFiles.filter(f => f.name !== fileName);
                applyFilter();
                console.log(`Successfully deleted: ${fileName}`);
            } else {
                console.error(`Error deleting file: ${result.error || 'Unknown error'}`);
                showError(`Failed to delete ${fileName}: ${result.error || 'Unknown error'}`);
            }
        } catch (error) {
            console.error('Failed to send delete request:', error);
            showError(`Failed to send delete request for "${fileName}". Please check network.`);
        }
    }

    function openFilePicker() {
        if (!canUpload) return;
        fileInput.click();
    }

    if (fabUpload) {
        fabUpload.addEventListener('click', openFilePicker);
    }

    dropZone.addEventListener('click', (event) => {
        if (event.target !== fileInput) {
            event.stopPropagation();
            fileInput.click();
        }
    });

    dropZone.addEventListener('dragover', (event) => {
        event.preventDefault();
        dropZone.classList.add('dragover');
    });

    dropZone.addEventListener('dragleave', () => {
        dropZone.classList.remove('dragover');
    });

    dropZone.addEventListener('drop', (event) => {
        event.preventDefault();
        dropZone.classList.remove('dragover');
        if (!canUpload) return;
        const files = event.dataTransfer.files;
        if (files.length > 0) {
            if (!([...event.dataTransfer.items].every(item => item.webkitGetAsEntry()?.isFile))) {
                showError("Folders aren't supported. Compress them as ZIP first.");
                return;
            }
            handleFiles(files);
        }
    });

    fileInput.addEventListener('change', (event) => {
        const files = event.target.files;
        if (files.length > 0) handleFiles(files);
        event.target.value = '';
    });

    function handleFiles(files) {
        Array.from(files).forEach(file => uploadFile(file));
    }

    function uploadFile(file) {
        if (!canUpload) return;
        const formData = new FormData();
        formData.append('file', file, file.name);

        const xhr = new XMLHttpRequest();
        const { progressItem, progressBarFill, progressStatus } =
            createProgressItem(`${file.name} (${formatBytes(file.size)}): `, uploadProgressContainer);

        xhr.upload.addEventListener('progress', (event) => {
            if (event.lengthComputable) {
                const percentComplete = (event.loaded / event.total) * 100;
                progressBarFill.style.width = percentComplete.toFixed(2) + '%';
                progressStatus.textContent = `${percentComplete.toFixed(0)}%`;
            }
        });

        xhr.addEventListener('load', () => {
            if (xhr.status >= 200 && xhr.status < 300) {
                progressBarFill.style.backgroundColor = 'var(--success-color)';
                progressStatus.textContent = `Success: ${xhr.responseText}`;
                setTimeout(() => progressItem.remove(), 3000);
                fetchFiles();
            } else {
                progressBarFill.style.backgroundColor = 'var(--error-color)';
                progressStatus.textContent = `Error: ${xhr.status} - ${xhr.responseText || 'Upload failed'}`;
                console.error('Upload failed:', xhr.status, xhr.responseText);
            }
        });

        xhr.addEventListener('error', () => {
            progressBarFill.style.backgroundColor = 'var(--error-color)';
            progressStatus.textContent = 'Network Error';
            console.error('Upload error (network).');
        });

        xhr.open('POST', '/api/upload', true);
        xhr.send(formData);
    }

    pasteButton.addEventListener('click', async () => {
        if (!canUpload) return;
        try {
            const text = await navigator.clipboard.readText();
            if (text.length > 0) {
                let pasteIndex = 1;
                let fileName = `paste_${pasteIndex}.txt`;
                const existingFiles = allFiles.map(f => f.name);
                while (existingFiles.includes(fileName)) {
                    pasteIndex++;
                    fileName = `paste_${pasteIndex}.txt`;
                }
                const blob = new Blob([text], { type: 'text/plain' });
                const file = new File([blob], fileName, { type: 'text/plain', lastModified: new Date().getTime() });
                uploadFile(file);
            } else {
                showError('Clipboard is empty or contains no text.');
            }
        } catch (err) {
            console.error('Failed to read clipboard contents: ', err);
            showError('Failed to read clipboard. Please grant clipboard permissions.');
        }
    });

    function updateDownloadButtonLabel() {
        const all = filesTableBody.querySelectorAll('.file-select');
        const checkedCount = filesTableBody.querySelectorAll('.file-select:checked').length;
        downloadAllZipButton.textContent = (checkedCount === 0 || checkedCount === all.length)
            ? 'Download All as Zip'
            : `Download Selected (${checkedCount})`;
    }

    selectAllCheckbox.addEventListener('change', () => {
        const all = filesTableBody.querySelectorAll('.file-select');
        all.forEach(cb => cb.checked = selectAllCheckbox.checked);
        updateDownloadButtonLabel();
    });

    filesTableBody.addEventListener('change', (e) => {
        if (!e.target.classList.contains('file-select')) return;
        const all = filesTableBody.querySelectorAll('.file-select');
        const checked = filesTableBody.querySelectorAll('.file-select:checked');
        selectAllCheckbox.checked = (all.length === checked.length);
        selectAllCheckbox.indeterminate = checked.length > 0 && checked.length < all.length;
        updateDownloadButtonLabel();
    });

    const downloadZip = (files) => {
        const form = document.createElement('form');
        form.method = 'POST';
        form.action = '/api/zip';
        form.style.display = 'none';
        files.forEach(file => {
            const input = document.createElement('input');
            input.type = 'hidden';
            input.name = 'f';
            input.value = file;
            form.appendChild(input);
        });
        document.body.appendChild(form);
        form.submit();
        document.body.removeChild(form);
    };

    downloadAllZipButton.addEventListener('click', () => {
        const selectedCheckboxes = filesTableBody.querySelectorAll('.file-select:checked');
        const files = [...selectedCheckboxes].map(cb => cb.dataset.fileName);
        downloadZip(files);
    });

    if (fileSearch) {
        fileSearch.addEventListener('input', applyFilter);
    }

    fetchFiles();
});
