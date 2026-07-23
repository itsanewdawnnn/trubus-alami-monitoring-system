<?php
// Bearer-token auth middleware (opaque tokens, hashed with SHA-256).
// Tokens do not expire -- a session stays valid until the user explicitly
// logs out, or an admin deactivates the account, per project decision.

require_once __DIR__ . '/config.php';

/**
 * Validates the Bearer token from the Authorization header and returns the
 * authenticated user record, or terminates the request with an error response.
 *
 * @param array $allowed_roles Roles authorized to access the endpoint. Empty means any.
 */
function authenticateUser(PDO $pdo, array $allowed_roles = []): array {
    $headers = getallheaders();
    $auth_header = isset($headers['Authorization']) ? $headers['Authorization'] : '';
    
    if (empty($auth_header) && isset($headers['authorization'])) {
        $auth_header = $headers['authorization'];
    }

    if (empty($auth_header) || !preg_match('/Bearer\s(\S+)/i', $auth_header, $matches)) {
        http_response_code(401);
        echo json_encode([
            "success" => false,
            "message" => "Access denied. Bearer token missing or invalid format."
        ]);
        exit();
    }

    $raw_token = $matches[1];
    $token_hash = hash('sha256', $raw_token);

    try {
        $stmt = $pdo->prepare("
            SELECT u.id, u.name, u.note, u.username, u.role, u.is_active
            FROM tams_users u
            JOIN tams_auth_tokens t ON u.id = t.user_id
            WHERE t.token_hash = :token_hash AND u.is_active = 1
            LIMIT 1
        ");
        $stmt->execute([':token_hash' => $token_hash]);
        $user = $stmt->fetch();

        if (!$user) {
            http_response_code(401);
            echo json_encode([
                "success" => false,
                "message" => "Invalid or revoked authentication token."
            ]);
            exit();
        }

        if (!empty($allowed_roles) && !in_array($user['role'], $allowed_roles)) {
            http_response_code(403);
            echo json_encode([
                "success" => false,
                "message" => "Forbidden. Access restricted for role: " . $user['role']
            ]);
            exit();
        }

        // Attach the hash so /auth/logout can revoke this exact token.
        $user['current_token_hash'] = $token_hash;
        return $user;

    } catch (PDOException $e) {
        error_log("[TAMS API] Auth DB error: " . $e->getMessage());
        http_response_code(500);
        echo json_encode([
            "success" => false,
            "message" => "Authentication error. Please try again later."
        ]);
        exit();
    }
}
