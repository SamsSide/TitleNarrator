package dev.samsside.titlenarrator.gametest;

import dev.samsside.titlenarrator.TitleNarratorClient;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;

public final class MultiplayerGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		RecordingSpeaker speaker = new RecordingSpeaker();
		TitleNarratorClient.setTestOverrides(speaker, () -> 0L);
		try (TestDedicatedServerContext server = context.worldBuilder().createServer();
				TestDedicatedServerConnection connection = server.connect()) {
			connection.waitForChunksRender();
			context.runOnClient(mc -> Objects.requireNonNull(TitleNarratorClient.narrator()).reset());

			server.runCommand("title @a subtitle \"From the server\"");
			server.runCommand("title @a title \"Remote\"");
			connection.waitForClientboundPackets();

			if (!speaker.spoken().equals(List.of("Remote. From the server"))) {
				throw new AssertionError("expected [Remote. From the server] but spoke " + speaker.spoken());
			}
		} finally {
			TitleNarratorClient.setTestOverrides(null, null);
		}
	}
}
