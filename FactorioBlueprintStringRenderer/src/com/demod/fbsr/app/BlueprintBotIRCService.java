package com.demod.fbsr.app;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.json.JSONObject;
import org.pircbotx.Configuration;
import org.pircbotx.PircBotX;
import org.pircbotx.hooks.Event;
import org.pircbotx.hooks.types.GenericMessageEvent;

import com.demod.factorio.Config;
import com.demod.fbsr.Blueprint;
import com.demod.fbsr.BlueprintFinder;
import com.demod.fbsr.BlueprintStringData;
import com.demod.fbsr.FBSR;
import com.demod.fbsr.TaskReporting;
import com.demod.fbsr.WebUtils;
import com.google.common.util.concurrent.AbstractIdleService;

import javafx.util.Pair;

public class BlueprintBotIRCService extends AbstractIdleService {

	private static final String IRC_AUTHOR_URL = "http://peterbjornx.nl/wp-content/uploads/2013/08/irc_icon.png";

	private JSONObject configJson;

	private PircBotX bot;

	private String server;

	private String channel;

	private void handleBlueprintCommand(GenericMessageEvent event) {
		String content = event.getMessage();
		TaskReporting reporting = new TaskReporting();
		reporting.setContext(content);

		List<BlueprintStringData> blueprintStrings = BlueprintFinder.search(content, reporting);
		for (BlueprintStringData blueprintString : blueprintStrings) {
			try {
				System.out.println("Parsing blueprints: " + blueprintString.getBlueprints().size());
				if (blueprintString.getBlueprints().size() == 1) {
					Blueprint blueprint = blueprintString.getBlueprints().get(0);
					BufferedImage image = FBSR.renderBlueprint(blueprint, reporting);
					reporting.addInfo(WebUtils.uploadToHostingService("blueprint.png", image).toString());

				} else {
					List<Pair<URL, String>> links = new ArrayList<>();
					for (Blueprint blueprint : blueprintString.getBlueprints()) {
						BufferedImage image = FBSR.renderBlueprint(blueprint, reporting);
						links.add(new Pair<>(WebUtils.uploadToHostingService("blueprint.png", image),
								blueprint.getLabel().orElse("")));
					}

					try {
						reporting.addInfo("Blueprint Book Images: " + WebUtils
								.uploadToBundly("Blueprint Book", "Renderings provided by Blueprint Bot", links)
								.toString());
					} catch (IOException e) {
						reporting.addException(e);
					}

				}
			} catch (Exception e) {
				reporting.addException(e);
			}
		}

		if (reporting.getBlueprintStrings().isEmpty()) {
			reporting.addInfo("Give me blueprint strings and I'll create images for you!");
			reporting.addInfo("Include a link to a text file to get started.");
		}

		if (!reporting.getInfo().isEmpty()) {
			event.respond(reporting.getInfo().stream().collect(Collectors.joining(" || ")));
		}

		ServiceFinder.findService(BlueprintBotDiscordService.class).ifPresent(
				s -> s.sendReport("IRC / " + channel + " / " + event.getUser().getNick(), IRC_AUTHOR_URL, reporting));
	}

	private void onEvent(Event event) {
		if (event instanceof GenericMessageEvent) {
			onMessage((GenericMessageEvent) event);
		}
	}

	private void onMessage(GenericMessageEvent event) {
		if (event.getUser().equals(event.getBot().getUserBot())) {
			return;
		}

		if (event.getMessage().toLowerCase().startsWith("!blueprint ")) {
			handleBlueprintCommand(event);
		}
	}

	@Override
	protected void shutDown() throws Exception {
		bot.stopBotReconnect();
		bot.sendIRC().quitServer("Killed By Owner");
	}

	@Override
	protected void startUp() throws Exception {
		configJson = Config.get().getJSONObject("irc");

		server = configJson.getString("server");
		channel = configJson.getString("channel");
		Configuration configuration = new Configuration.Builder()//
				.setName(configJson.getString("name"))//
				.setNickservPassword(configJson.optString("password", null))//
				.addServer(server)//
				.addAutoJoinChannel(channel)//
				.addListener(this::onEvent)//
				.setAutoReconnect(true)//
				.setAutoReconnectDelay(configJson.optInt("reconnect-milliseconds", 1000))//
				.buildConfiguration();

		bot = new PircBotX(configuration);
		bot.startBot();
	}

}