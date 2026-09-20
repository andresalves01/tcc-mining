package app.identity.port;

import app.identity.GitReconstruction;

/**
 * Supplies developer identities recovered from version-control history.
 *
 * <p>
 * SmartSHARK 2.2 omits {@code people}, but ships the mined repositories
 * themselves, so the names
 * and addresses behind every {@code author_id} are recoverable offline. How
 * that happens is an implementation detail deliberately hidden here.
 */
public interface GitIdentitySource {

  GitReconstruction load() throws Exception;
}
