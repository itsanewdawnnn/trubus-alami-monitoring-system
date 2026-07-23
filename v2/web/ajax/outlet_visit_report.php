<?php
/**
 * GET endpoint: Outlet menu's Visit Report tab -- for a single calendar day,
 * every active Member who visited at least one outlet that day, their
 * distinct-outlet visit count against the `outlet_min_visits_per_day` Remote
 * Management target, and per-outlet visit detail (name, confirmed time,
 * dwell duration) backing the Report tab's row-click detail popup. Read-only,
 * so no CSRF token is required (same idiom as ajax/outlet_list.php).
 *
 * A Member with zero visits that day is deliberately excluded (INNER JOIN,
 * not LEFT JOIN) -- this report exists to show who actually visited
 * something and what, not to enumerate every active Member regardless of
 * activity; a Member roster is already the Members page's job.
 *
 * Reads tams_outlet_visits directly, keyed by (member_id, visited_date) --
 * never joined back through tams_outlets -- so a visit to an outlet that
 * has since been soft-deleted or merged away still counts and still shows
 * its outlet_name_snapshot correctly; this ledger is immutable by design
 * (see tams_outlet_visits' schema.sql comment), and this report must not
 * quietly hide a real, already-confirmed visit just because the outlet
 * behind it no longer exists in its original form.
 *
 * Two queries, not one with GROUP_CONCAT -- same reason ajax/outlet_list.php
 * used to keep its (now-removed) per-outlet Member list as a separate keyed
 * query rather than a concatenated string: the per-Member outlet list needs
 * to become real JSON (an array of names for the table cell, AND an array
 * of {name, confirmed_at, dwell_seconds} for the detail popup) rather than a
 * concatenated string, and GROUP_CONCAT's default 1024-byte
 * group_concat_max_len ceiling is exactly the kind of silent-truncation risk
 * this project avoids elsewhere. `visited_outlets` (plain name strings) is
 * kept as-is for the table cell's existing `.join(', ')` rendering; the new
 * `visit_details` array is additive, built from the exact same second query
 * (zero extra queries), so no existing consumer of this endpoint's response
 * shape needs to change.
 */
require __DIR__ . '/../config.php';
require __DIR__ . '/../helpers/functions.php';
require __DIR__ . '/../security/csrf.php';
require __DIR__ . '/../security/auth.php';

require_login_ajax();

// Defaults to today (server/WIB time, matching every other date-sensitive
// piece of this project -- see ajax/member_log_list.php's own comment on
// created_at already being stored as the server's local time throughout).
$rawDate = (string) ($_GET['date'] ?? '');
$date = ($rawDate !== '' && DateTime::createFromFormat('Y-m-d', $rawDate) !== false)
    ? $rawDate
    : date('Y-m-d');

$target = remote_management_values($pdo, ['outlet_min_visits_per_day'])['outlet_min_visits_per_day'];

// INNER JOIN, not LEFT JOIN -- a Member with zero visits that day
// contributes no matching tams_outlet_visits row and is correctly dropped
// from the result set entirely (see this file's own header comment on why).
// u.username costs nothing extra (same table, same query) and backs the
// detail popup's secondary identifier.
$countStmt = $pdo->prepare("
    SELECT u.id, u.name, u.username, COUNT(v.id) AS visit_count
    FROM tams_users u
    INNER JOIN tams_outlet_visits v ON v.member_id = u.id AND v.visited_date = :date
    WHERE u.role = 'member' AND u.is_active = 1
    GROUP BY u.id, u.name, u.username
    ORDER BY u.name ASC
");
$countStmt->execute(['date' => $date]);
$rows = $countStmt->fetchAll();

// confirmed_at/dwell_seconds cost nothing extra over the previous
// name-only version of this same query -- both already live on
// tams_outlet_visits, read here once and reused for both output fields
// below (visited_outlets and visit_details), never a second query.
// Ordered chronologically (not alphabetically) since that's the
// meaningful order for "what time was each outlet visited".
$outletsStmt = $pdo->prepare("
    SELECT member_id, outlet_name_snapshot, confirmed_at, dwell_seconds
    FROM tams_outlet_visits
    WHERE visited_date = :date
    ORDER BY confirmed_at ASC
");
$outletsStmt->execute(['date' => $date]);
$outletsByMember = [];
$visitDetailsByMember = [];
foreach ($outletsStmt->fetchAll() as $row) {
    $memberId = (int) $row['member_id'];
    $outletsByMember[$memberId][] = $row['outlet_name_snapshot'];
    $visitDetailsByMember[$memberId][] = [
        'outlet_name' => $row['outlet_name_snapshot'],
        'confirmed_at' => $row['confirmed_at'],
        'dwell_seconds' => (int) $row['dwell_seconds'],
    ];
}

$data = array_map(static function (array $row) use ($outletsByMember, $visitDetailsByMember, $target): array {
    $id = (int) $row['id'];
    $visitCount = (int) $row['visit_count'];

    return [
        'member_id' => $id,
        'member_name' => $row['name'],
        'username' => $row['username'],
        'visit_count' => $visitCount,
        'meets_target' => $visitCount >= $target,
        'visited_outlets' => $outletsByMember[$id] ?? [],
        'visit_details' => $visitDetailsByMember[$id] ?? [],
    ];
}, $rows);

json_response([
    'success' => true,
    'date' => $date,
    'target' => $target,
    'data' => $data,
]);
