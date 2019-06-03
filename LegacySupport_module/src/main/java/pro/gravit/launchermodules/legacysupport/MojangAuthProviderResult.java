package pro.gravit.launchermodules.legacysupport;

import java.util.UUID;

import pro.gravit.launchserver.LaunchServer;
import pro.gravit.launchserver.auth.provider.AuthProviderResult;

public final class MojangAuthProviderResult extends AuthProviderResult {
    public final UUID uuid;
    public final String launcherToken;

    public MojangAuthProviderResult(String username, String accessToken, UUID uuid, String launcherToken, LaunchServer srv) {
        super(username, accessToken, srv);
        this.uuid = uuid;
        this.launcherToken = launcherToken;
    }
}
