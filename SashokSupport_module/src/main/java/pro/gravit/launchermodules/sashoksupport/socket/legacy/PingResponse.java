package pro.gravit.launchermodules.sashoksupport.socket.legacy;

import java.io.IOException;

import pro.gravit.launcher.serialize.HInput;
import pro.gravit.launcher.serialize.HOutput;
import pro.gravit.launcher.serialize.SerializeLimits;
import pro.gravit.launchermodules.sashoksupport.socket.LegacyServerComponent;

public final class PingResponse extends Response {
    public PingResponse(LegacyServerComponent component, long id, HInput input, HOutput output) {
        super(component, id, input, output);
    }

    @Override
    public void reply() throws IOException {
        output.writeUnsignedByte(SerializeLimits.EXPECTED_BYTE);
    }
}
