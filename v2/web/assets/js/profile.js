/**
 * My Profile modal: lets the logged-in Admin change their own name,
 * username, and password. Opened from the "My Profile" item
 * (layouts/header.php's #accountMenuProfileBtn) inside the Account Menu
 * dropdown owned by assets/js/account_menu.js -- that file only opens/closes
 * the dropdown itself and has no involvement in what happens after My
 * Profile is clicked. The modal markup lives in layouts/footer.php
 * (#profileModalOverlay) since it's shared by every protected page, not
 * owned by any one pages/*.php.
 *
 * topbarUserBtn (layouts/header.php) is still read/written here -- not for
 * its click event (that now only toggles the dropdown, see
 * assets/js/account_menu.js), but because it's where the logged-in Admin's
 * name/username/avatar are rendered and kept in sync after a successful
 * save, same as before this file existed.
 *
 * Global, not tied to any sidebar menu -- this script is loaded
 * unconditionally on every page by layouts/footer.php, right after app.js
 * (whose window.TAMS helpers this relies on: postJson, toast, bindModalClose).
 */
(function () {
    'use strict';

    var postJson = window.TAMS.postJson;
    var toast = window.TAMS.toast;
    var bindModalClose = window.TAMS.bindModalClose;

    var topbarUserBtn = document.getElementById('topbarUserBtn');
    var profileMenuItem = document.getElementById('accountMenuProfileBtn');
    var overlay = document.getElementById('profileModalOverlay');
    if (!topbarUserBtn || !profileMenuItem || !overlay) return;

    var form = document.getElementById('profileForm');
    var submitBtn = document.getElementById('profileSubmitBtn');
    var fieldToId = {
        name: 'profileName',
        username: 'profileUsername',
        new_password: 'profileNewPassword',
        confirm_password: 'profileConfirmPassword',
    };
    var fieldToErrorId = {
        name: 'profileErrorName',
        username: 'profileErrorUsername',
        new_password: 'profileErrorNewPassword',
        confirm_password: 'profileErrorConfirmPassword',
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

    profileMenuItem.addEventListener('click', function () {
        clearErrors();
        form.reset();
        document.getElementById('profileName').value = topbarUserBtn.getAttribute('data-name') || '';
        document.getElementById('profileUsername').value = topbarUserBtn.getAttribute('data-username') || '';
        overlay.classList.add('modal-overlay--open');
        document.getElementById('profileName').focus();
    });

    form.addEventListener('submit', function (e) {
        e.preventDefault();
        clearErrors();

        var payload = {
            name: document.getElementById('profileName').value.trim(),
            username: document.getElementById('profileUsername').value.trim(),
            new_password: document.getElementById('profileNewPassword').value,
            confirm_password: document.getElementById('profileConfirmPassword').value,
        };

        submitBtn.disabled = true;
        submitBtn.textContent = 'Saving...';

        postJson('../ajax/profile_update.php', payload)
            .then(function (body) {
                if (body.success) {
                    toast(body.message || 'Profile updated successfully.', 'success');
                    topbarUserBtn.setAttribute('data-name', body.name);
                    topbarUserBtn.setAttribute('data-username', body.username);
                    document.querySelectorAll('.topbar__user-name').forEach(function (el) {
                        el.textContent = body.name;
                    });
                    var avatar = topbarUserBtn.querySelector('.topbar__user-avatar');
                    if (avatar) avatar.textContent = body.name ? body.name.charAt(0).toUpperCase() : '?';
                    overlay.classList.remove('modal-overlay--open');
                } else if (body.errors) {
                    showErrors(body.errors);
                } else {
                    toast(body.message || 'Failed to update profile.', 'error');
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
            });
    });

    bindModalClose(overlay);
})();
