<?php
/**
 * Outlet-feature-only helpers -- deliberately NOT in helpers/functions.php.
 * That file is reserved for logic reused by two or more Admin Panel
 * features (e.g. haversine_distance_km() is shared by Member History and
 * Outlet Merge; remote_management_values() is Remote Management's own
 * public surface, read by Members/Outlet/Android alike). Everything below
 * is Outlet-internal -- shared only across this feature's own
 * ajax/outlet_*.php files, never read by another feature -- so it follows
 * the same "single feature's logic belongs in that feature's own file"
 * rule assets/js/profile.js was split out of app.js for (see root
 * CLAUDE.md's Feature Ownership Convention). Required only by
 * ajax/outlet_list.php, ajax/outlet_create.php, and ajax/outlet_update.php
 * -- the three files that actually call these.
 */

/**
 * "NAMA: OUTLET" display formatting -- derived at read time from
 * tams_outlets.name + the creator's tams_users.name, never stored (see
 * tams_outlets.name's schema.sql comment on why: persisting the combined
 * string would go stale the moment the creator renames their own account).
 * Admin-created outlets show the literal word "ADMIN" instead of the
 * individual Admin's own name, per explicit product decision -- an outlet
 * an Admin assigns to a Member is a company-issued outlet, not personally
 * attributed to whichever Admin happened to create it. Android has its own
 * equivalent formatter (these are independent apps, see root CLAUDE.md).
 */
function outlet_display_name(string $creatorRole, string $creatorName, string $outletName): string
{
    $prefix = $creatorRole === 'admin' ? 'ADMIN' : mb_strtoupper($creatorName);
    return $prefix . ': ' . mb_strtoupper($outletName);
}

/**
 * Shared name/address/coordinate validation for ajax/outlet_create.php and
 * ajax/outlet_update.php -- both accept an identical payload shape and must
 * reject malformed input the same way. Mirrors backend/api.php's own
 * validateOutletPayload() (same rules -- VARCHAR widths and lat/lng ranges
 * must stay identical, since both are the same tams_outlets columns), kept
 * as an independent copy per this project's Admin-Panel/Backend-API
 * separation (root CLAUDE.md). Unlike that version (which throws), this
 * returns a $field => $message array -- empty when valid -- for the caller
 * to fold into its own $errors array, matching how every other ajax/*.php
 * endpoint in this app reports field errors (see ajax/members_save.php).
 */
function validate_outlet_fields(string $name, string $address, $latitude, $longitude): array
{
    $errors = [];
    if ($name === '' || mb_strlen($name) > 150) {
        $errors['name'] = 'Outlet name is required and must be at most 150 characters.';
    }
    if ($address === '' || mb_strlen($address) > 255) {
        $errors['address'] = 'Address is required and must be at most 255 characters.';
    }
    if ($latitude === false || $latitude === null || $latitude < -90.0 || $latitude > 90.0
        || $longitude === false || $longitude === null || $longitude < -180.0 || $longitude > 180.0) {
        $errors['location'] = 'Invalid location. Please pick a point on the map.';
    }
    return $errors;
}

/**
 * Validates a single member_id payload for ajax/outlet_create.php and
 * ajax/outlet_update.php: an outlet must always resolve to exactly one real,
 * currently-active Member (tams_outlets.member_id is how backend/api.php's
 * geofencing hook finds the one candidate outlet for an incoming fix;
 * assigning a deactivated or non-existent account would silently orphan the
 * outlet) -- one-to-one from the outlet's side, see tams_outlets.member_id's
 * own schema.sql comment. Returns null if the raw value isn't a positive
 * integer, or doesn't match an active Member -- the caller reports this as
 * one field error, the same shape every other validator here uses. Returns
 * the validated int id otherwise.
 */
function validate_outlet_member_id(PDO $pdo, $rawId): ?int
{
    $id = (int) $rawId;
    if ($id <= 0) {
        return null;
    }

    $stmt = $pdo->prepare("SELECT id FROM tams_users WHERE role = 'member' AND is_active = 1 AND id = :id");
    $stmt->execute(['id' => $id]);
    return $stmt->fetch() ? $id : null;
}
