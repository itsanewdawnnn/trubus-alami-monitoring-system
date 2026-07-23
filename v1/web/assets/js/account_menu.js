/**
 * Account menu dropdown: the topbar "who am I" button (layouts/header.php's
 * #topbarUserBtn) opens a small dropdown with two items, My Profile and
 * Logout, instead of jumping straight into the My Profile modal.
 *
 * Ownership is deliberately narrow -- this file only owns the dropdown's
 * own open/close mechanics (toggle, close-on-item-click, outside click,
 * Esc). It does NOT know what any individual item does, by design:
 *   - The close-on-item-click rule below is generic -- it closes the
 *     dropdown whenever anything carrying the shared .account-menu__item
 *     class is clicked, full stop. It has no idea "My Profile" opens a
 *     modal or "Logout" navigates away, and doesn't need to: any item
 *     added to the dropdown in the future gets the same closing behavior
 *     automatically as long as it wears that class, with zero new code
 *     here or anywhere else.
 *   - My Profile (#accountMenuProfileBtn) has its own separate click
 *     listener in assets/js/profile.js, which still owns the modal end to
 *     end (form, endpoint, validation) exactly as before. That listener is
 *     attached directly to the button, so it always runs before this
 *     file's delegated listener on the click's way up to #accountMenuDropdown
 *     -- the modal is already open by the time this file closes the
 *     dropdown, never the other way around.
 *   - Logout is a plain <a href="../logout.php">, not wired to any
 *     feature-specific JS anywhere -- same as the anchor
 *     layouts/sidebar.php used to render in its own footer before it was
 *     removed. Endpoint, CSRF (none, see logout.php's own comment),
 *     session, and redirect are all untouched. The generic close-on-click
 *     rule still closes the dropdown when it's clicked (same as any other
 *     item), which finishes synchronously before the browser navigates
 *     away -- browsers only follow a link's default action after its click
 *     event has fully finished dispatching.
 *
 * Global, not tied to any sidebar menu -- loaded unconditionally on every
 * page by layouts/footer.php, right after app.js (whose window.TAMS this
 * doesn't even need, since there's no fetch/toast/escaping involved here).
 */
(function () {
    'use strict';

    var accountMenu = document.getElementById('accountMenu');
    var topbarUserBtn = document.getElementById('topbarUserBtn');
    var dropdown = document.getElementById('accountMenuDropdown');
    if (!accountMenu || !topbarUserBtn || !dropdown) return;

    function isOpen() {
        return accountMenu.classList.contains('account-menu--open');
    }

    function openMenu() {
        accountMenu.classList.add('account-menu--open');
        topbarUserBtn.setAttribute('aria-expanded', 'true');
    }

    function closeMenu() {
        accountMenu.classList.remove('account-menu--open');
        topbarUserBtn.setAttribute('aria-expanded', 'false');
    }

    topbarUserBtn.addEventListener('click', function (e) {
        e.stopPropagation();
        if (isOpen()) {
            closeMenu();
        } else {
            openMenu();
        }
    });

    // Generic "selecting an item closes the dropdown" rule -- deliberately
    // keyed off the shared .account-menu__item class, not any specific
    // item's id, so it applies uniformly to every current and future item
    // with no per-item wiring. Delegated on the dropdown container (not
    // document) so it only ever reacts to clicks on this dropdown's own
    // children.
    //
    // A native <button> (My Profile) and a native <a href> (Logout) both
    // dispatch a real 'click' event for Enter *and* Space when focused via
    // keyboard -- the browser generates the same event this listener
    // already handles for a mouse click, so keyboard activation closes the
    // dropdown the same way with no extra code. The one platform exception
    // is Space on a plain <a>, which browsers never treat as activation
    // (Enter does) -- true of every plain link in this app, not something
    // introduced here, and not a gap this listener can or should work
    // around.
    //
    // Listener order on a click inside the dropdown: the target's own
    // listener (e.g. profile.js's, attached directly to
    // #accountMenuProfileBtn) always fires first, then this delegated
    // listener as the event bubbles up to #accountMenuDropdown -- so an
    // item's own action (opening a modal, following a link) has already
    // started before the dropdown closes, never the reverse, and closing
    // it here never calls preventDefault/stopPropagation, so it can never
    // block that action.
    dropdown.addEventListener('click', function (e) {
        if (e.target.closest('.account-menu__item')) {
            closeMenu();
        }
    });

    // Outside click -- closes the dropdown for anything not covered above
    // (e.g. clicking elsewhere on the page without picking an item), and
    // remains a harmless no-op here whenever the item-click rule above has
    // already closed it first.
    document.addEventListener('click', function (e) {
        if (isOpen() && !accountMenu.contains(e.target)) {
            closeMenu();
        }
    });

    document.addEventListener('keydown', function (e) {
        if (e.key === 'Escape' && isOpen()) {
            closeMenu();
        }
    });
})();
