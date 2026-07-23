/**
 * Remote Management page logic: submits the settings form to
 * ajax/remote_management_save.php. Field set is driven entirely by the server
 * (helpers/functions.php's remote_management_definitions()) -- this script
 * reads whatever <input name="..."> elements exist in the form rather than
 * hardcoding field names, so adding a new setting server-side never
 * requires a matching change here.
 */
(function () {
    'use strict';

    var toast = window.TAMS.toast;
    var postJson = window.TAMS.postJson;

    var form = document.getElementById('remoteManagementForm');
    var submitBtn = document.getElementById('remoteManagementSubmitBtn');
    var updatedAtEl = document.getElementById('remoteManagementUpdatedAt');

    function fieldKeys() {
        return Array.prototype.map.call(form.querySelectorAll('input[name]'), function (input) {
            return input.name;
        });
    }

    function clearErrors() {
        fieldKeys().forEach(function (key) {
            var input = document.getElementById('field_' + key);
            var errorEl = document.getElementById('error_' + key);
            var group = input ? input.closest('.form-group') : null;
            if (group) group.classList.remove('form-group--invalid');
            if (errorEl) errorEl.textContent = '';
        });
    }

    function showErrors(errors) {
        Object.keys(errors).forEach(function (key) {
            var input = document.getElementById('field_' + key);
            var errorEl = document.getElementById('error_' + key);
            var group = input ? input.closest('.form-group') : null;
            if (group) group.classList.add('form-group--invalid');
            if (errorEl) errorEl.textContent = errors[key];
        });
    }

    // Mirrors ota_update.js's formatNow -- "just saved" is always
    // effectively now, so there's no need for a round trip just to get a
    // fresh server timestamp.
    function formatNow() {
        var d = new Date();
        var pad = function (n) { return n < 10 ? '0' + n : String(n); };
        var months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
        return d.getDate() + ' ' + months[d.getMonth()] + ' ' + d.getFullYear() + ', ' + pad(d.getHours()) + ':' + pad(d.getMinutes()) + ' WIB';
    }

    form.addEventListener('submit', function (e) {
        e.preventDefault();
        clearErrors();

        var payload = {};
        fieldKeys().forEach(function (key) {
            payload[key] = parseInt(document.getElementById('field_' + key).value, 10);
        });

        submitBtn.disabled = true;
        submitBtn.textContent = 'Saving...';

        postJson('../ajax/remote_management_save.php', payload)
            .then(function (body) {
                if (body.success) {
                    toast(body.message || 'Saved successfully.', 'success');
                    if (updatedAtEl) updatedAtEl.textContent = formatNow();
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
            });
    });
})();
