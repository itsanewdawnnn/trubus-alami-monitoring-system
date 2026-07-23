/**
 * Version Management page logic: submits the single-row form (now
 * multipart/form-data, since it can carry an APK file) to
 * ajax/ota_update_save.php. No table/list/polling here -- unlike
 * members.js, this page has nothing to load asynchronously (the form is
 * already pre-filled server-side by pages/ota_update.php), so this file is
 * just submit-and-show-errors, plus the small amount of wiring the file
 * picker and the live Force Update preview need.
 */
(function () {
    'use strict';

    var toast = window.TAMS.toast;
    var uploadForm = window.TAMS.uploadForm;

    var form = document.getElementById('versionForm');
    var submitBtn = document.getElementById('versionSubmitBtn');
    var updatedAtEl = document.getElementById('versionUpdatedAt');
    var progressEl = document.getElementById('saveProgress');
    var progressBarEl = document.getElementById('saveProgressBar');

    var versionCodeInput = document.getElementById('versionCode');
    var minSupportedInput = document.getElementById('minSupportedVersionCode');
    var forceUpdateBadge = document.getElementById('forceUpdateBadge');

    var apkFileInput = document.getElementById('apkFile');
    var apkChooseBtn = document.getElementById('apkChooseBtn');
    var apkFileName = document.getElementById('apkFileName');
    var apkCurrentInfo = document.getElementById('apkCurrentInfo');
    var apkDropzone = document.getElementById('apkDropzone');
    var apkErrorEl = document.getElementById('errorApk');

    var fieldToId = {
        version_name: 'versionName',
        version_code: 'versionCode',
        min_supported_version_code: 'minSupportedVersionCode',
        release_notes: 'releaseNotes',
        apk: 'apkFile',
    };
    var fieldToErrorId = {
        version_name: 'errorVersionName',
        version_code: 'errorVersionCode',
        min_supported_version_code: 'errorMinSupportedVersionCode',
        release_notes: 'errorReleaseNotes',
        apk: 'errorApk',
    };

    function clearErrors() {
        Object.keys(fieldToId).forEach(function (field) {
            var input = document.getElementById(fieldToId[field]);
            var errorEl = document.getElementById(fieldToErrorId[field]);
            var group = input ? input.closest('.form-group') : null;
            if (group) group.classList.remove('form-group--invalid');
            if (errorEl) errorEl.textContent = '';
        });
    }

    function showErrors(errors) {
        Object.keys(errors).forEach(function (field) {
            var input = fieldToId[field] ? document.getElementById(fieldToId[field]) : null;
            var errorEl = fieldToErrorId[field] ? document.getElementById(fieldToErrorId[field]) : null;
            var group = input ? input.closest('.form-group') : null;
            if (group) group.classList.add('form-group--invalid');
            if (errorEl) errorEl.textContent = errors[field];
        });
    }

    /**
     * Save progress line -- driven by uploadForm()'s upload.onprogress
     * (real bytes-sent percentage, not a fake/animated one), since an APK
     * upload can take a while on a slow connection and a frozen "Saving..."
     * button alone gives no sense of whether it's actually moving.
     */
    function showProgress() {
        if (!progressEl || !progressBarEl) return;
        progressBarEl.style.width = '0%';
        progressEl.hidden = false;
    }

    function setProgress(fraction) {
        if (!progressBarEl) return;
        var percent = Math.max(0, Math.min(1, fraction)) * 100;
        progressBarEl.style.width = percent + '%';
    }

    function hideProgress() {
        if (!progressEl || !progressBarEl) return;
        progressEl.hidden = true;
        progressBarEl.style.width = '0%';
    }

    // Mirrors app.js's formatDateTime but takes a Date (client "now"), since
    // there's no fresh server timestamp available without a round trip --
    // "just saved" is always effectively now.
    function formatNow() {
        var d = new Date();
        var pad = function (n) { return n < 10 ? '0' + n : String(n); };
        var months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
        return d.getDate() + ' ' + months[d.getMonth()] + ' ' + d.getFullYear() + ', ' + pad(d.getHours()) + ':' + pad(d.getMinutes()) + ' WIB';
    }

    /**
     * Client-side-only preview, purely so an Admin sees the effect of
     * editing Version Code / Minimum Supported Version Code immediately --
     * the authoritative value always comes from the server (both on page
     * load and in the Save response below), this never substitutes for that.
     */
    function updateForceUpdatePreview() {
        if (!forceUpdateBadge) return;
        var versionCode = parseInt(versionCodeInput.value, 10);
        var minSupported = parseInt(minSupportedInput.value, 10);
        var isForce = !isNaN(versionCode) && !isNaN(minSupported) && versionCode > 0 && minSupported >= versionCode;
        applyForceUpdateBadge(isForce);
    }

    function applyForceUpdateBadge(isForce) {
        if (!forceUpdateBadge) return;
        forceUpdateBadge.classList.remove('badge--danger', 'badge--success');
        forceUpdateBadge.classList.add(isForce ? 'badge--danger' : 'badge--success');
        forceUpdateBadge.textContent = isForce ? 'Yes -- mandatory' : 'No -- optional';
    }

    if (versionCodeInput && minSupportedInput) {
        versionCodeInput.addEventListener('input', updateForceUpdatePreview);
        minSupportedInput.addEventListener('input', updateForceUpdatePreview);
    }

    /**
     * Shared by the native file-picker's change event and the drop handler
     * below -- keeps both paths validating/displaying identically instead
     * of drifting apart. Only a friendly, client-side heads-up: the real
     * content check (ZIP signature, not just the extension) always happens
     * server-side in ajax/ota_update_save.php regardless of what this says.
     */
    function handleChosenFile(file) {
        if (!file) {
            if (apkFileName) apkFileName.textContent = 'No new file chosen';
            return;
        }
        var looksLikeApk = /\.apk$/i.test(file.name);
        var group = apkFileInput.closest('.form-group');
        if (!looksLikeApk) {
            if (apkErrorEl) apkErrorEl.textContent = 'That file doesn\'t look like a .apk -- please choose/drop an APK file.';
            if (group) group.classList.add('form-group--invalid');
            apkFileInput.value = '';
            if (apkFileName) apkFileName.textContent = 'No new file chosen';
            return;
        }
        if (apkErrorEl) apkErrorEl.textContent = '';
        if (group) group.classList.remove('form-group--invalid');
        if (apkFileName) apkFileName.textContent = file.name;
    }

    if (apkChooseBtn && apkFileInput) {
        apkChooseBtn.addEventListener('click', function (e) {
            // Stops the click from also reaching apkDropzone's own listener
            // below -- both open the same native picker, and firing both
            // would mean apkFileInput.click() runs twice for one tap.
            e.stopPropagation();
            apkFileInput.click();
        });
        apkFileInput.addEventListener('change', function () {
            handleChosenFile(apkFileInput.files && apkFileInput.files[0]);
        });
    }

    if (apkDropzone && apkFileInput) {
        // Whole zone is also just a big click target for the same native
        // picker -- not only the button inside it.
        apkDropzone.addEventListener('click', function () {
            apkFileInput.click();
        });

        // dragenter/dragleave (not dragover for the active-state toggle)
        // pair, checking relatedTarget/currentTarget containment -- a plain
        // dragover-only approach flickers the active state on and off as the
        // pointer crosses the icon/text/filename children inside the zone,
        // since those fire their own enter/leave pairs too.
        apkDropzone.addEventListener('dragenter', function (e) {
            e.preventDefault();
            apkDropzone.classList.add('file-dropzone--active');
        });
        apkDropzone.addEventListener('dragover', function (e) {
            // Required unconditionally -- a drop target only actually
            // receives the 'drop' event if 'dragover' calls this too.
            e.preventDefault();
        });
        apkDropzone.addEventListener('dragleave', function (e) {
            if (e.relatedTarget && apkDropzone.contains(e.relatedTarget)) return;
            apkDropzone.classList.remove('file-dropzone--active');
        });
        apkDropzone.addEventListener('drop', function (e) {
            e.preventDefault();
            apkDropzone.classList.remove('file-dropzone--active');
            var files = e.dataTransfer && e.dataTransfer.files;
            if (!files || !files.length) return;
            // Assigning a FileList captured from a user-driven drop straight
            // onto the input is supported in every current browser -- this
            // keeps the actual <input type="file"> as the single source of
            // truth FormData reads from on submit, rather than tracking a
            // separately-dropped File object side by side.
            apkFileInput.files = files;
            handleChosenFile(files[0]);
        });
    }

    form.addEventListener('submit', function (e) {
        e.preventDefault();
        clearErrors();

        var formData = new FormData();
        formData.append('version_name', document.getElementById('versionName').value.trim());
        formData.append('version_code', document.getElementById('versionCode').value);
        formData.append('min_supported_version_code', document.getElementById('minSupportedVersionCode').value);
        formData.append('release_notes', document.getElementById('releaseNotes').value);
        // Only appended when a new file was actually chosen -- an Admin
        // updating just, say, release notes doesn't need to re-upload the
        // same APK every time (see ajax/ota_update_save.php's own
        // "optional per request, mandatory the first time" handling).
        if (apkFileInput && apkFileInput.files && apkFileInput.files[0]) {
            formData.append('apk', apkFileInput.files[0]);
        }

        submitBtn.disabled = true;
        submitBtn.textContent = 'Saving...';
        showProgress();

        uploadForm('../ajax/ota_update_save.php', formData, setProgress)
            .then(function (body) {
                if (body.success) {
                    toast(body.message || 'Saved successfully.', 'success');
                    if (updatedAtEl) updatedAtEl.textContent = formatNow();
                    applyForceUpdateBadge(!!body.force_update);

                    if (body.apk_url && apkCurrentInfo) {
                        var sizeText = body.apk_size_formatted ? ' (' + body.apk_size_formatted + ')' : '';
                        var escapedUrl = window.TAMS.escapeHtml(body.apk_url);
                        apkCurrentInfo.innerHTML = 'Currently published: <a href="' + escapedUrl + '" id="apkCurrentLink" target="_blank" rel="noopener">'
                            + escapedUrl + '</a>'
                            + window.TAMS.escapeHtml(sizeText) + ' -- choose a new file above to replace it.';
                    }
                    if (apkFileInput) apkFileInput.value = '';
                    if (apkFileName) apkFileName.textContent = 'No new file chosen';
                } else if (body.errors) {
                    showErrors(body.errors);
                } else {
                    toast(body.message || 'Failed to save configuration.', 'error');
                }
            })
            .catch(function (err) {
                if (err.message !== 'unauthenticated') {
                    toast('A network error occurred.', 'error');
                }
            })
            .finally(function () {
                submitBtn.disabled = false;
                submitBtn.textContent = 'Save';
                // Completes the bar to 100% (a small POST with no file
                // included may otherwise finish before onprogress ever
                // fires) and gives it a beat to read as "done" before
                // collapsing away, instead of vanishing mid-fill.
                setProgress(1);
                setTimeout(hideProgress, 300);
            });
    });
})();
