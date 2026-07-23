/**
 * Shared JS loaded on every logged-in page (see layouts/footer.php):
 * sidebar toggle, toast helper, HTML-escape helper, and a CSRF-aware
 * fetch() wrapper.
 */
(function () {
    'use strict';

    /* ---- Sidebar toggle (small screens) -------------------------------- */
    var shell = document.querySelector('.app-shell');
    var toggleBtn = document.getElementById('sidebarToggle');
    var backdrop = document.getElementById('sidebarBackdrop');

    function closeSidebar() {
        if (shell) shell.classList.remove('app-shell--sidebar-open');
    }

    if (toggleBtn && shell) {
        toggleBtn.addEventListener('click', function () {
            shell.classList.toggle('app-shell--sidebar-open');
        });
    }
    if (backdrop) {
        backdrop.addEventListener('click', closeSidebar);
    }
    // Avoids a stuck-open panel if the window is resized past the breakpoint.
    window.addEventListener('resize', function () {
        if (window.innerWidth > 900) closeSidebar();
    });

    /* ---- Toast notifications -------------------------------------------- */
    var toastStack = null;
    function getToastStack() {
        if (!toastStack) {
            toastStack = document.createElement('div');
            toastStack.className = 'toast-stack';
            document.body.appendChild(toastStack);
        }
        return toastStack;
    }

    /**
     * Shows a small auto-dismissing notification. type is 'success' | 'error' | ''.
     */
    function toast(message, type) {
        var stack = getToastStack();
        var el = document.createElement('div');
        el.className = 'toast' + (type ? ' toast--' + type : '');
        el.textContent = message;
        stack.appendChild(el);
        setTimeout(function () {
            el.remove();
        }, 3200);
    }

    /* ---- HTML escaping for client-rendered content ----------------------- */
    // Mirrors includes/functions.php's e() -- required for any value
    // inserted into innerHTML from JSON (never passes through PHP's escaping).
    function escapeHtml(value) {
        var div = document.createElement('div');
        div.textContent = value === null || value === undefined ? '' : String(value);
        return div.innerHTML;
    }

    /* ---- fetch() wrapper with CSRF header -------------------------------- */
    function csrfToken() {
        var meta = document.querySelector('meta[name="csrf-token"]');
        return meta ? meta.getAttribute('content') : '';
    }

    // Shared by getJson/postJson: every ajax/*.php endpoint returns 401 via
    // require_login_ajax() on an expired session, handled here in one place.
    function handleResponse(res) {
        return res.json().then(function (body) {
            if (res.status === 401) {
                window.location.href = '../login.php?reason=timeout';
                throw new Error('unauthenticated');
            }
            return body;
        });
    }

    /**
     * GETs `url` and parses the JSON response. No CSRF token -- GET requests
     * don't change state. cache: 'no-store' -- every ajax/*.php GET reflects
     * live DB state (e.g. Members' Force Override toggle); without this,
     * some browsers can reuse a stale cached response after a page refresh
     * even though helpers/functions.php's json_response() already sends
     * no-cache headers server-side too.
     */
    function getJson(url) {
        return fetch(url, { credentials: 'same-origin', cache: 'no-store' }).then(handleResponse);
    }

    /**
     * POSTs a JSON body to `url` with the CSRF token attached.
     */
    function postJson(url, data) {
        return fetch(url, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'X-CSRF-Token': csrfToken(),
                'X-Requested-With': 'XMLHttpRequest',
            },
            credentials: 'same-origin',
            body: JSON.stringify(data || {}),
        }).then(handleResponse);
    }

    /**
     * POSTs a FormData body (fields plus, optionally, a File) to `url` with
     * the CSRF token attached -- the multipart/form-data counterpart of
     * postJson(), needed wherever a real file upload is involved (see
     * assets/js/ota_update.js). Deliberately no 'Content-Type' header:
     * the browser must set it itself, including the multipart boundary
     * fetch() has no way to generate by hand.
     */
    function postForm(url, formData) {
        return fetch(url, {
            method: 'POST',
            headers: {
                'X-CSRF-Token': csrfToken(),
                'X-Requested-With': 'XMLHttpRequest',
            },
            credentials: 'same-origin',
            body: formData,
        }).then(handleResponse);
    }

    /**
     * FormData upload with a progress callback -- fetch() has no way to
     * observe upload progress (only response/download streaming, in the
     * browsers that support it at all), so this is XMLHttpRequest-based
     * instead of built on postForm() above. Only reach for this over
     * postForm() when the caller actually shows a progress indicator (see
     * assets/js/ota_update.js) -- otherwise postForm()/fetch() is simpler.
     *
     * onProgress(fraction) is called with a 0-1 value whenever the browser
     * can compute one (e.lengthComputable); large uploads on a slow
     * connection always can, tiny ones may fire once at 1 and skip the
     * middle -- callers should handle both.
     */
    function uploadForm(url, formData, onProgress) {
        return new Promise(function (resolve, reject) {
            var xhr = new XMLHttpRequest();
            xhr.open('POST', url, true);
            xhr.setRequestHeader('X-CSRF-Token', csrfToken());
            xhr.setRequestHeader('X-Requested-With', 'XMLHttpRequest');
            xhr.withCredentials = true;

            if (xhr.upload && typeof onProgress === 'function') {
                xhr.upload.onprogress = function (e) {
                    if (e.lengthComputable) {
                        onProgress(e.loaded / e.total);
                    }
                };
            }

            xhr.onload = function () {
                if (xhr.status === 401) {
                    window.location.href = '../login.php?reason=timeout';
                    reject(new Error('unauthenticated'));
                    return;
                }
                var body;
                try {
                    body = JSON.parse(xhr.responseText);
                } catch (err) {
                    reject(new Error('Invalid server response.'));
                    return;
                }
                resolve(body);
            };
            xhr.onerror = function () {
                reject(new Error('network'));
            };

            xhr.send(formData);
        });
    }

    /* ---- Date/time formatting (server values are WIB "YYYY-MM-DD HH:MM:SS"
       strings, shown as-is, never re-converted through browser timezone) --- */
    var MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

    function formatDate(value) {
        if (!value) return '-';
        var parts = value.split(/[- :]/);
        if (parts.length < 3) return value;
        var month = MONTHS[parseInt(parts[1], 10) - 1] || parts[1];
        return parts[2] + ' ' + month + ' ' + parts[0];
    }

    function timeOnly(value) {
        if (!value || value.length < 16) return '-';
        return value.substring(11, 16);
    }

    function formatDateTime(value) {
        if (!value) return '-';
        return formatDate(value) + ', ' + timeOnly(value) + ' WIB';
    }

    /* ---- Modal open/close wiring ------------------------------------------ */
    // Shared by every .modal-overlay: backdrop click, [data-close-modal], and Escape.
    function bindModalClose(overlay) {
        overlay.addEventListener('click', function (e) {
            if (e.target === overlay) {
                overlay.classList.remove('modal-overlay--open');
            }
        });
        overlay.querySelectorAll('[data-close-modal]').forEach(function (btn) {
            btn.addEventListener('click', function () {
                overlay.classList.remove('modal-overlay--open');
            });
        });
    }

    document.addEventListener('keydown', function (e) {
        if (e.key !== 'Escape') return;
        document.querySelectorAll('.modal-overlay--open').forEach(function (overlay) {
            overlay.classList.remove('modal-overlay--open');
        });
    });

    window.TAMS = {
        toast: toast,
        escapeHtml: escapeHtml,
        getJson: getJson,
        postJson: postJson,
        postForm: postForm,
        uploadForm: uploadForm,
        formatDate: formatDate,
        timeOnly: timeOnly,
        formatDateTime: formatDateTime,
        bindModalClose: bindModalClose,
    };
})();
