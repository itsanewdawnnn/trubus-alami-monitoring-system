<?php
/**
 * POST endpoint: merges `source_id` into `target_id` -- the source is
 * soft-deleted; the target survives completely unchanged otherwise,
 * including its own `member_id`. One-to-one from the outlet's side means
 * there is no membership to union anymore -- the source's assigned Member
 * simply loses this outlet along with everything else the soft-delete
 * already implies (same as any other soft-deleted outlet), while the
 * target's existing Member is left exactly as-is, consistent with how the
 * target's name/address/coordinates are already preserved unchanged through
 * a merge. tams_outlet_visits is NEVER touched (ledger-immutability
 * principle -- see tams_outlets.merged_into_outlet_id's schema.sql
 * comment): a report resolves a merged outlet's "current" identity by
 * following that pointer instead.
 *
 * Deadlock avoidance: both outlet rows are locked in ascending id order
 * regardless of which one is source/target, the same principle as
 * backend/api.php's Force Location fix (there, a single row; here, two).
 * Two concurrent merges that both touch these same two outlets (in either
 * direction) always attempt to acquire their locks in the same id order, so
 * one simply waits for the other to commit instead of the two deadlocking
 * against each other.
 *
 * Body: { source_id, target_id }
 */
require __DIR__ . '/../config.php';
require __DIR__ . '/../helpers/functions.php';
require __DIR__ . '/../security/csrf.php';
require __DIR__ . '/../security/auth.php';

require_login_ajax();
csrf_require_ajax();

$body = json_decode(file_get_contents('php://input'), true);
$sourceId = isset($body['source_id']) ? (int) $body['source_id'] : 0;
$targetId = isset($body['target_id']) ? (int) $body['target_id'] : 0;

if ($sourceId <= 0 || $targetId <= 0 || $sourceId === $targetId) {
    json_response(['success' => false, 'message' => 'Please select two different outlets to merge.'], 422);
}

$adminId = (int) $_SESSION['admin_id'];
$lowId = min($sourceId, $targetId);
$highId = max($sourceId, $targetId);

try {
    $pdo->beginTransaction();

    // ORDER BY id ASC over a primary-key IN() lookup is satisfied directly
    // from InnoDB's clustered index -- no separate sort step -- so FOR
    // UPDATE's locks are acquired in ascending id order deterministically,
    // regardless of which id the caller labeled source vs. target.
    $lock_stmt = $pdo->prepare('
        SELECT id, latitude, longitude, merged_into_outlet_id
        FROM tams_outlets
        WHERE id IN (:low, :high) AND deleted_at IS NULL
        ORDER BY id ASC
        FOR UPDATE
    ');
    $lock_stmt->execute(['low' => $lowId, 'high' => $highId]);
    $rows = $lock_stmt->fetchAll();

    if (count($rows) !== 2) {
        $pdo->rollBack();
        json_response(['success' => false, 'message' => 'One or both outlets were not found (already deleted?).'], 404);
    }

    $byId = [];
    foreach ($rows as $row) {
        $byId[(int) $row['id']] = $row;
    }
    $source = $byId[$sourceId];
    $target = $byId[$targetId];

    if ($source['merged_into_outlet_id'] !== null || $target['merged_into_outlet_id'] !== null) {
        $pdo->rollBack();
        json_response(['success' => false, 'message' => 'One of the selected outlets has already been merged into another.'], 409);
    }

    $distanceMeters = haversine_distance_km(
        (float) $source['latitude'],
        (float) $source['longitude'],
        (float) $target['latitude'],
        (float) $target['longitude']
    ) * 1000;
    $radiusMeters = remote_management_values($pdo, ['outlet_radius_meters'])['outlet_radius_meters'];

    if ($distanceMeters > $radiusMeters) {
        $pdo->rollBack();
        json_response([
            'success' => false,
            'message' => sprintf(
                'These outlets are %.0f m apart, which exceeds the %d m merge limit (Remote Management > Outlet Radius).',
                $distanceMeters,
                $radiusMeters
            ),
        ], 422);
    }

    // Path compression -- any outlet already pointing at the source (from an
    // earlier merge) is repointed straight at the target, so
    // merged_into_outlet_id is always at most one hop (see tams_outlets'
    // own schema.sql comment on this column).
    $repoint_stmt = $pdo->prepare('UPDATE tams_outlets SET merged_into_outlet_id = :target WHERE merged_into_outlet_id = :source');
    $repoint_stmt->execute(['target' => $targetId, 'source' => $sourceId]);

    // Soft-delete the source and point it at the survivor -- a merge is a
    // specialized soft-delete, not a separate state (see tams_outlets.
    // merged_into_outlet_id's schema.sql comment).
    $merge_stmt = $pdo->prepare('
        UPDATE tams_outlets
        SET deleted_at = NOW(), deleted_by_user_id = :admin_id, merged_into_outlet_id = :target
        WHERE id = :source
    ');
    $merge_stmt->execute(['admin_id' => $adminId, 'target' => $targetId, 'source' => $sourceId]);

    $pdo->commit();
} catch (PDOException $e) {
    if ($pdo->inTransaction()) {
        $pdo->rollBack();
    }
    error_log('[TAMS Admin] outlet_merge failure: ' . $e->getMessage());
    json_response(['success' => false, 'message' => 'A server error occurred.'], 500);
}

json_response(['success' => true, 'message' => 'Outlets merged successfully.']);
