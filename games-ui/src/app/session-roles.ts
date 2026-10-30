/**
 * Read-only view of the host's `hug.session` localStorage record (written by
 * the login remote, read by every federated remote; see hugmesh-host-shell
 * session.service.ts). It holds non-secret metadata only; `roles` are
 * lowercase role slugs such as 'user', 'games', 'games_admin'.
 *
 * Used for UI decisions only (which calls are worth making). The games BFF is
 * the authority: it refuses anything this gets wrong.
 */
export const SESSION_KEY = 'hug.session';

/** Role slugs from `hug.session`, canonical (one leading `ROLE_` stripped,
 *  lowercase). Empty when there is no session, no storage, or bad JSON. */
export function sessionRoles(storage: Pick<Storage, 'getItem'> | null = safeLocalStorage()): string[] {
  if (!storage) return [];
  try {
    const raw = storage.getItem(SESSION_KEY);
    if (!raw) return [];
    const roles = (JSON.parse(raw) as { roles?: unknown } | null)?.roles;
    if (!Array.isArray(roles)) return [];
    return roles
      .filter((r): r is string => typeof r === 'string')
      .map((r) => r.trim().replace(/^role_/i, '').toLowerCase())
      .filter((r) => r.length > 0);
  } catch {
    return [];
  }
}

/** The games BFF admits exactly the DERIVED games-admin role
 *  (ROLE_GAMES_ADMIN = games AND (admin OR mod)) to its admin calls,
 *  GET /api/games/launches included. A bare platform admin or a superadmin
 *  alone is refused there, so the UI does not ask on their behalf. */
export function isGamesAdminSession(roles: readonly string[]): boolean {
  return roles.includes('games_admin');
}

function safeLocalStorage(): Storage | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage;
  } catch {
    return null;
  }
}
