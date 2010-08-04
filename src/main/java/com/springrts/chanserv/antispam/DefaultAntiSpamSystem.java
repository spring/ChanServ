/*
 * Created on 25.2.2006
 */

package com.springrts.chanserv.antispam;


import com.springrts.chanserv.Client;
import com.springrts.chanserv.Context;
import com.springrts.chanserv.Log;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;

public class DefaultAntiSpamSystem implements AntiSpamSystem{

	/**
	 * Once every so milliseconds, we will reset the counter in the Client
	 * that counts how many CLIENTSTATUS command have been received from a user
	 * In milliseconds.
	 */
	final static int CHECK_CLIENTSTATUSCHANGE_INTERVAL = 3000;
	/**
	 * Max frequency of CLIENTSTATUS command.
	 * If we receive more than this times per second,
	 * we have a problem.
	 * We must still check MIN_CLIENTSTATUCCHANGE_COUNT_BEFORE_ALERT before
	 * taking actions, because a user might change status quickly
	 * (in few milliseconds) by accident (if spring crashes, for example).
	 */
	final static float MAX_CLIENTSTATUSCHANGE_FREQUENCY = 5.0f;
	final static int MIN_CLIENTSTATUCCHANGE_COUNT_BEFORE_ALERT = 5;

	/** as a key in this list we use a combination of "channel-name:user-name" */
	static final Map<String, SpamRecord> spamRecords = new HashMap<String, SpamRecord>();

	/** spam settings for each individual channel */
	protected static Map<String, SpamSettings> spamSettings;

	private static Timer antiSpamTimer;

	private Context context;


	public DefaultAntiSpamSystem(Context context) {
		this.context = context;
	}


	/** Initializes the anti-spam system */
	@Override
	public void initialize() {

		spamSettings = new HashMap<String, SpamSettings>();

		antiSpamTimer = new Timer();
		antiSpamTimer.schedule(new AntiSpamTask(context),
				1000,  // initial delay
				1000); // subsequent rate
	}

	/** Stops the anti-spam system */
	@Override
	public void uninitialize() {
		try {
			antiSpamTimer.cancel();
		} catch (Exception e) {
			// do nothing
		}
	}

	/**
	 * Call this method when user says something in the channel using SAY
	 * or SAYEX command
	 */
	@Override
	public void processUserMsg(String chan, String user, String msg) {

		synchronized (spamRecords) {
			String key = chan + ":" + user;
			SpamSettings settings = spamSettings.get(chan);
			if (settings == null) {
				settings = SpamSettings.DEFAULT_SETTINGS;
			}

			SpamRecord rec = spamRecords.get(key);
			if (rec == null) {
				rec = new SpamRecord();
				spamRecords.put(key, rec);
			}

			// determine severity:
			double severity = settings.getNormalMsgPenalty();
			if (msg.length() > settings.getLongMsgLength()) {
				severity += settings.getLongMsgPenalty();
			}
			if (rec.getLastMsg().equals(msg)) {
				severity += settings.getDoubleMsgPenalty();
			}

			rec.setPenaltyPoints(rec.getPenaltyPoints() + severity);
			rec.setLastMsg(msg);

			// check if user has gathered too many penalty points:
			if (rec.getPenaltyPoints() >= settings.getPenaltyLimit()) {
				rec.setPenaltyPoints(0); // reset counter
				muteUser(chan, user);
			}
		}
	}

	@Override
	public void processClientStatusChange(Client client) {

		if (System.currentTimeMillis() - client.getClientStatusChangeCheckpoint() > CHECK_CLIENTSTATUSCHANGE_INTERVAL) {
			// reset the counter:
			client.setClientStatusChangeCheckpoint(System.currentTimeMillis());
			client.resetStatusChanges();
		}

		client.addOneStatusChange();

		if (((System.currentTimeMillis() - client.getClientStatusChangeCheckpoint()) * 1.0f / client.getStatusChanges()) > MAX_CLIENTSTATUSCHANGE_FREQUENCY) {
			if (client.getStatusChanges() > MIN_CLIENTSTATUCCHANGE_COUNT_BEFORE_ALERT) {
				// reset the counter:
				client.setClientStatusChangeCheckpoint(System.currentTimeMillis());
				client.resetStatusChanges();

				// take action:
				context.getChanServ().sendLine("KICKUSER " + client.getName() + " CLIENTSTATUS command abuse - frequency too high");
			}
		}
	}

	@Override
	public void setSpamSettingsForChannel(String chan, String settings) {

		SpamSettings ss = SpamSettings.stringToSpamSettings(settings);
		if (ss == null) {
			Log.error("AntiSpamSystem: malformed settings string in setSpamSettingsForChannel(): " + settings);
			spamSettings.put(chan, SpamSettings.DEFAULT_SETTINGS);
		} else {
			spamSettings.put(chan, ss);
		}
	}

	private void muteUser(String chan, String user) {

		context.getChanServ().sendLine("MUTE " + chan + " " + user + " 15");
		context.getChanServ().sendLine("SAYPRIVATE " + user + " You have been temporarily muted due to spamming in channel #" + chan + ". You may get temporarily banned if you will continue to spam this channel.");
	}
}
