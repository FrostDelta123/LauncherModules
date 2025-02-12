package pro.gravit.launchermodules.sashoksupport;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import pro.gravit.launcher.profiles.PlayerProfile;
import pro.gravit.launcher.serialize.HInput;
import pro.gravit.launcher.serialize.HOutput;
import pro.gravit.launcher.serialize.stream.StreamObject;
import pro.gravit.launchserver.auth.handler.AuthHandler;
import pro.gravit.launchserver.auth.provider.AuthProviderResult;
import pro.gravit.utils.helper.CommonHelper;
import pro.gravit.utils.helper.IOHelper;
import pro.gravit.utils.helper.LogHelper;
import pro.gravit.utils.helper.SecurityHelper;
import pro.gravit.utils.helper.VerifyHelper;

public final class BinaryFileAuthHandler extends AuthHandler {
    public static final class Entry extends StreamObject {
        private String username;
        private String accessToken;
        private String serverID;


        public Entry(HInput input) throws IOException {
            username = VerifyHelper.verifyUsername(input.readString(64));
            if (input.readBoolean()) {
                accessToken = SecurityHelper.verifyToken(input.readASCII(-SecurityHelper.TOKEN_STRING_LENGTH));
                if (input.readBoolean())
                    serverID = VerifyHelper.verifyServerID(input.readASCII(41));
            }
        }


        public Entry(String username) {
            this.username = VerifyHelper.verifyUsername(username);
        }


        public Entry(String username, String accessToken, String serverID) {
            this(username);
            if (accessToken == null && serverID != null)
                throw new IllegalArgumentException("Can't set access token while server ID is null");

            // Set and verify access token
            this.accessToken = accessToken == null ? null : SecurityHelper.verifyToken(accessToken);
            this.serverID = serverID == null ? null : VerifyHelper.verifyServerID(serverID);
        }

        private void auth(String username, String accessToken) {
            this.username = username; // Update username case
            this.accessToken = accessToken;
            serverID = null;
        }

        private boolean checkServer(String username, String serverID) {
            return username.equals(this.username) && serverID.equals(this.serverID);
        }


        public String getAccessToken() {
            return accessToken;
        }


        public String getServerID() {
            return serverID;
        }


        public String getUsername() {
            return username;
        }

        private boolean joinServer(String username, String accessToken, String serverID) {
            if (!username.equals(this.username) || !accessToken.equals(this.accessToken))
                return false; // Username or access token mismatch

            // Update server ID
            this.serverID = serverID;
            return true;
        }

        @Override
        public void write(HOutput output) throws IOException {
            output.writeString(username, 64);
            output.writeBoolean(accessToken != null);
            if (accessToken != null) {
                output.writeASCII(accessToken, -SecurityHelper.TOKEN_STRING_LENGTH);
                output.writeBoolean(serverID != null);
                if (serverID != null)
                    output.writeASCII(serverID, 41);
            }
        }
    }


    public String file;

    public String fileTmp;


    public boolean offlineUUIDs = false;
    // Instance
    private final SecureRandom random = SecurityHelper.newRandom();

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    // Storage
    private final Map<UUID, Entry> entryMap = new HashMap<>(256);

    private final Map<String, UUID> usernamesMap = new HashMap<>(256);


    protected final void addAuth(UUID uuid, Entry entry) {
        lock.writeLock().lock();
        try {
            Entry previous = entryMap.put(uuid, entry);
            if (previous != null)
                usernamesMap.remove(CommonHelper.low(previous.username));
            usernamesMap.put(CommonHelper.low(entry.username), uuid);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public final UUID auth(AuthProviderResult authResult) {
        lock.writeLock().lock();
        try {
            UUID uuid = usernameToUUID(authResult.username);
            Entry entry = entryMap.get(uuid);

            // Not registered? Fix it!
            if (entry == null) {
                entry = new Entry(authResult.username);

                // Generate UUID
                uuid = genUUIDFor(authResult.username);
                entryMap.put(uuid, entry);
                usernamesMap.put(CommonHelper.low(authResult.username), uuid);
            }

            // Authenticate
            entry.auth(authResult.username, authResult.accessToken);
            return uuid;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public final UUID checkServer(String username, String serverID) {
        lock.readLock().lock();
        try {
            UUID uuid = usernameToUUID(username);
            Entry entry = entryMap.get(uuid);

            // Check server (if has such account of course)
            return entry != null && entry.checkServer(username, serverID) ? uuid : null;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public final void close() throws IOException {
        lock.readLock().lock();
        try {
            LogHelper.info("Writing auth handler file (%d entries)", entryMap.size());
            writeAuthFileTmp();
            IOHelper.move(new File(fileTmp).toPath(), new File(file).toPath());
        } finally {
            lock.readLock().unlock();
        }
    }


    protected final Set<Map.Entry<UUID, Entry>> entrySet() {
        return Collections.unmodifiableMap(entryMap).entrySet();
    }

    private UUID genUUIDFor(String username) {
        if (offlineUUIDs) {
            UUID md5UUID = PlayerProfile.offlineUUID(username);
            if (!entryMap.containsKey(md5UUID))
                return md5UUID;
            LogHelper.warning("Offline UUID collision, using random: '%s'", username);
        }

        // Pick random UUID
        UUID uuid;
        do
            uuid = new UUID(random.nextLong(), random.nextLong());
        while (entryMap.containsKey(uuid));
        return uuid;
    }

    @Override
    public final boolean joinServer(String username, String accessToken, String serverID) {
        lock.writeLock().lock();
        try {
            Entry entry = entryMap.get(usernameToUUID(username));
            return entry != null && entry.joinServer(username, accessToken, serverID);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public final UUID usernameToUUID(String username) {
        lock.readLock().lock();
        try {
            return usernamesMap.get(CommonHelper.low(username));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public final String uuidToUsername(UUID uuid) {
        lock.readLock().lock();
        try {
            Entry entry = entryMap.get(uuid);
            return entry == null ? null : entry.username;
        } finally {
            lock.readLock().unlock();
        }
    }

    protected void readAuthFile() throws IOException {
        try (HInput input = new HInput(IOHelper.newInput(new File(file).toPath()))) {
            int count = input.readLength(0);
            for (int i = 0; i < count; i++) {
                UUID uuid = input.readUUID();
                Entry entry = new Entry(input);
                addAuth(uuid, entry);
            }
        }
    }

    protected void writeAuthFileTmp() throws IOException {
        Set<Map.Entry<UUID, Entry>> entrySet = entrySet();
        try (HOutput output = new HOutput(IOHelper.newOutput(new File(fileTmp).toPath()))) {
            output.writeLength(entrySet.size(), 0);
            for (Map.Entry<UUID, Entry> entry : entrySet) {
                output.writeUUID(entry.getKey());
                entry.getValue().write(output);
            }
        }
    }
}
