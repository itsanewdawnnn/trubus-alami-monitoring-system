            </main>
        </div>
    </div>

    <!-- My Profile modal, opened by header.php's Account Menu dropdown
         (#accountMenuProfileBtn, see assets/js/account_menu.js). Lives here
         (not in a specific pages/*.php) since footer.php is shared by every
         protected page. Wired up in assets/js/profile.js. -->
    <div class="modal-overlay" id="profileModalOverlay">
        <div class="modal">
            <form id="profileForm" novalidate>
                <div class="modal__header">
                    <h2 class="modal__title">My Profile</h2>
                    <button type="button" class="modal__close" data-close-modal aria-label="Close">
                        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
                    </button>
                </div>
                <div class="modal__body">
                    <div class="form-group">
                        <label class="form-label" for="profileName">Name</label>
                        <input type="text" class="form-control" id="profileName" name="name" maxlength="100" required>
                        <div class="form-error" id="profileErrorName"></div>
                    </div>

                    <div class="form-group">
                        <label class="form-label" for="profileUsername">Username</label>
                        <input type="text" class="form-control" id="profileUsername" name="username" maxlength="50" required>
                        <div class="form-error" id="profileErrorUsername"></div>
                    </div>

                    <p class="modal__section-label">Change Password</p>

                    <div class="form-group">
                        <label class="form-label" for="profileNewPassword">New Password <span class="form-optional">(optional)</span></label>
                        <input type="password" class="form-control" id="profileNewPassword" name="new_password" autocomplete="new-password">
                        <div class="form-hint">Leave blank if you don't want to change the password.</div>
                        <div class="form-error" id="profileErrorNewPassword"></div>
                    </div>

                    <div class="form-group">
                        <label class="form-label" for="profileConfirmPassword">Confirm New Password</label>
                        <input type="password" class="form-control" id="profileConfirmPassword" name="confirm_password" autocomplete="new-password">
                        <div class="form-error" id="profileErrorConfirmPassword"></div>
                    </div>
                </div>
                <div class="modal__footer">
                    <button type="button" class="btn btn-secondary" data-close-modal>Cancel</button>
                    <button type="submit" class="btn btn-primary" id="profileSubmitBtn">Save</button>
                </div>
            </form>
        </div>
    </div>

    <script src="../assets/js/app.js<?= asset_version('assets/js/app.js') ?>"></script>
    <!-- Account Menu dropdown and My Profile are both global (topbar, every
         page), so these load unconditionally right after app.js -- not
         gated by $pageScript like a menu-specific page's own script.
         account_menu.js first (owns the dropdown chrome), then profile.js
         (owns one item inside it) -- order doesn't affect behavior since
         neither depends on the other, only on window.TAMS from app.js. -->
    <script src="../assets/js/account_menu.js<?= asset_version('assets/js/account_menu.js') ?>"></script>
    <script src="../assets/js/profile.js<?= asset_version('assets/js/profile.js') ?>"></script>
    <?php if (!empty($pageHasMap)): ?>
        <script src="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.js"></script>
        <script src="../assets/js/map.js<?= asset_version('assets/js/map.js') ?>"></script>
    <?php endif; ?>
    <?php if (!empty($pageScript)): ?>
        <script src="../assets/js/<?= e($pageScript) ?><?= asset_version('assets/js/' . $pageScript) ?>"></script>
    <?php endif; ?>
</body>
</html>
