package com.aionemu.gameserver.services.panesterra.ahserion;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.ai.NpcAI;
import com.aionemu.gameserver.ai.manager.WalkManager;
import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.gameobjects.LetterType;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.StaticDoor;
import com.aionemu.gameserver.model.gameobjects.VisibleObject;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.templates.spawns.SpawnGroup;
import com.aionemu.gameserver.model.templates.spawns.SpawnTemplate;
import com.aionemu.gameserver.model.templates.spawns.panesterra.AhserionsFlightSpawnTemplate;
import com.aionemu.gameserver.network.aion.serverpackets.SM_SYSTEM_MESSAGE;
import com.aionemu.gameserver.services.mail.SystemMailService;
import com.aionemu.gameserver.services.teleport.TeleportService;
import com.aionemu.gameserver.spawnengine.SpawnEngine;
import com.aionemu.gameserver.utils.PacketSendUtility;
import com.aionemu.gameserver.utils.PositionUtil;
import com.aionemu.gameserver.utils.ThreadPoolManager;
import com.aionemu.gameserver.utils.audit.AuditLogger;
import com.aionemu.gameserver.world.World;
import com.aionemu.gameserver.world.WorldPosition;

/**
 * @author Yeats, Neon, Estrayl
 */
public class AhserionRaid {

	private final Map<PanesterraFaction, PanesterraTeam> panesterraTeams = Collections.synchronizedMap(new EnumMap<>(PanesterraFaction.class));
	private final AtomicBoolean isStarted = new AtomicBoolean();
	private PanesterraTeam winner;
	private Future<?> progressTask;

	public static AhserionRaid getInstance() {
		return SingletonHolder.instance;
	}

	public void start() {
		if (isStarted.compareAndSet(false, true)) {
			spawnAdvanceCorridorsAndInitTeams();
			spawnRaid();
			startInstanceTimer();
		}
	}

	public void stop() {
		if (!isStarted.compareAndSet(true, false))
			return;
		winner = null;
		cancelProgressTask();
		if (!panesterraTeams.isEmpty()) {
			for (PanesterraTeam team : panesterraTeams.values()) {
				team.setIsEliminated(true);
				team.moveTeamMembersToOriginPosition();
			}
			panesterraTeams.clear();
		}
		cleanUp();
	}

	private void cleanUp() {
		for (VisibleObject obj : World.getInstance().getWorldMap(400030000).getMainWorldMapInstance()) {
			if (obj instanceof Player player) {
				if (!player.isStaff())
					TeleportService.moveToBindLocation(player);
			} else if (obj instanceof StaticDoor door) {
				door.setOpen(false);
			} else if (obj instanceof Npc) {
				obj.getController().delete();
			}
		}
	}

	private void startInstanceTimer() {
		progressTask = ThreadPoolManager.getInstance().scheduleAtFixedRate(new Runnable() {

			private int progress;

			@Override
			public void run() {
				switch (++progress) {
					case 2:
						sendMsg(SM_SYSTEM_MESSAGE.STR_MSG_GAB1_SUB_ALARM_01());
						break;
					case 4:
						sendMsg(SM_SYSTEM_MESSAGE.STR_MSG_GAB1_SUB_ALARM_02());
						break;
					case 8:
						sendMsg(SM_SYSTEM_MESSAGE.STR_MSG_GAB1_SUB_ALARM_03());
						break;
					case 12:
						sendMsg(SM_SYSTEM_MESSAGE.STR_MSG_GAB1_SUB_ALARM_04());
						break;
					case 16:
						sendMsg(SM_SYSTEM_MESSAGE.STR_MSG_GAB1_SUB_ALARM_05());
						break;
					case 18:
						sendMsg(SM_SYSTEM_MESSAGE.STR_MSG_GAB1_SUB_ALARM_06());
						break;
					case 19:
						sendMsg(SM_SYSTEM_MESSAGE.STR_MSG_GAB1_SUB_ALARM_07());
						for (PanesterraFaction faction : PanesterraFaction.values())
							spawnStage(2, faction); // spawn mobs 30s before doors are opened
						break;
					case 20:
						checkForIllegalMovement();
						World.getInstance().getWorldMap(400030000).getMainWorldMapInstance().forEachDoor(door -> door.setOpen(true));
						sendMsg(SM_SYSTEM_MESSAGE.STR_MSG_GAB1_SUB_ALARM_08());
						break;
					case 30:
						sendMsg(SM_SYSTEM_MESSAGE.STR_MSG_GAB1_SUB_ALARM_09());
						for (PanesterraFaction faction : PanesterraFaction.values())
							spawnStage(3, faction);
						break;
					case 40:
						sendMsg(SM_SYSTEM_MESSAGE.STR_MSG_GAB1_SUB_ALARM_10());
						break;
					case 50:
						sendMsg(SM_SYSTEM_MESSAGE.STR_MSG_GAB1_SUB_ALARM_11());
						break;
					case 60:
						sendMsg(SM_SYSTEM_MESSAGE.STR_MSG_GAB1_SUB_ALARM_12());
						for (PanesterraFaction faction : PanesterraFaction.values())
							if (panesterraTeams.containsKey(faction) && !panesterraTeams.get(faction).isEliminated())
								spawnStage(4, faction);
						break;
					case 130:
						sendMsg(SM_SYSTEM_MESSAGE.STR_MSG_GAB1_SUB_ALARM_13());
						break;
					case 138:
						sendMsg(SM_SYSTEM_MESSAGE.STR_MSG_GAB1_SUB_ALARM_14());
						break;
					case 150:
						panesterraTeams.values().stream().filter(team -> !team.isEliminated()).forEach(team -> sendConsolationReward(team));
						stop();
						break;
				}
			}
		}, 30000, 30000);
	}

	private void checkForIllegalMovement() {
		panesterraTeams.values().forEach(team -> {
			WorldPosition startPosition = team.getStartPosition();
			team.forEachMember(player -> {
				if (player.getPosition().getMapId() == 400030000
					&& !PositionUtil.isInRange(player, startPosition.getX(), startPosition.getY(), startPosition.getZ(), 81f)) {
					AuditLogger.log(player, "bugged himself through the " + team.getFaction() + " start door");
					team.movePlayerToStartPosition(player);
				}
			});
		});
	}

	/**
	 * Currently hard coded due to hard coded tribe relations
	 * TODO: Implement more flexibility for fortress occupation
	 */
	private void spawnAdvanceCorridorsAndInitTeams() {
		PanesterraFaction elyos = PanesterraFaction.ATANATOS;
		PanesterraFaction asmodians = PanesterraFaction.DISILLON;
		panesterraTeams.put(elyos, new PanesterraTeam(elyos));
		panesterraTeams.put(asmodians, new PanesterraTeam(asmodians));
		SpawnEngine.spawnObject(SpawnEngine.newSingleTimeSpawn(110070000, 802223, 485.692f, 401.079f, 127.789f, (byte) 0), 1);
		SpawnEngine.spawnObject(SpawnEngine.newSingleTimeSpawn(120080000, 802225, 400.772f, 231.517f, 93.113f, (byte) 30), 1);
	}

	private void spawnRaid() {
		// spawn Barricades & Tank Fleets
		spawnStage(0, PanesterraFaction.BALAUR);
		spawnStage(180, PanesterraFaction.BALAUR);
		spawnStage(181, PanesterraFaction.BALAUR);
		spawnStage(182, PanesterraFaction.BALAUR);
		spawnStage(183, PanesterraFaction.BALAUR);

		// spawn flags & cannons for all registered teams
		for (PanesterraFaction faction : panesterraTeams.keySet()) {
			if (faction != null)
				spawnStage(0, faction);
			spawnStage(1, faction);
		}

		// spawn white flags for not existing teams
		if (!panesterraTeams.containsKey(PanesterraFaction.BELUS)) {
			SpawnTemplate template = SpawnEngine.newSingleTimeSpawn(400030000, 804106, 282.73f, 289.1f, 687.38f, (byte) 1);
			SpawnEngine.spawnObject(template, 1);
		}
		if (!panesterraTeams.containsKey(PanesterraFaction.ASPIDA)) {
			SpawnTemplate template = SpawnEngine.newSingleTimeSpawn(400030000, 804108, 282.49f, 739.62f, 689.66f, (byte) 1);
			SpawnEngine.spawnObject(template, 1);
		}
		if (!panesterraTeams.containsKey(PanesterraFaction.ATANATOS)) {
			SpawnTemplate template = SpawnEngine.newSingleTimeSpawn(400030000, 804110, 734.06f, 740.75f, 681.16f, (byte) 1);
			SpawnEngine.spawnObject(template, 1);
		}
		if (!panesterraTeams.containsKey(PanesterraFaction.DISILLON)) {
			SpawnTemplate template = SpawnEngine.newSingleTimeSpawn(400030000, 804112, 738.58f, 286.02f, 680.71f, (byte) 1);
			SpawnEngine.spawnObject(template, 1);
		}
	}

	public void spawnStage(int stage, PanesterraFaction faction) {
		if (faction != PanesterraFaction.BALAUR && (panesterraTeams.get(faction) == null || panesterraTeams.get(faction).isEliminated()))
			return;

		List<SpawnGroup> ahserionSpawns = DataManager.SPAWNS_DATA.getAhserionSpawnByTeamId(faction.ordinal());
		if (ahserionSpawns == null)
			return;

		for (SpawnGroup grp : ahserionSpawns) {
			for (SpawnTemplate template : grp.getSpawnTemplates()) {
				AhserionsFlightSpawnTemplate ahserionTemplate = (AhserionsFlightSpawnTemplate) template;
				if (ahserionTemplate.getStage() == stage) {
					Npc npc = (Npc) SpawnEngine.spawnObject(ahserionTemplate, 1);
					WalkManager.startWalking((NpcAI) npc.getAi());
				}
			}
		}
	}

	public void handleCorridorShieldDestruction(int npcId) {
		if (!isStarted.get())
			return;

		PanesterraFaction eliminatedFaction = null;
		SpawnTemplate template = null;

		switch (npcId) {
			case 297306 -> {
				eliminatedFaction = PanesterraFaction.BELUS;
				template = SpawnEngine.newSingleTimeSpawn(400030000, 804106, 282.73f, 289.1f, 687.38f, (byte) 1);
			}
			case 297307 -> {
				eliminatedFaction = PanesterraFaction.ASPIDA;
				template = SpawnEngine.newSingleTimeSpawn(400030000, 804108, 282.49f, 739.62f, 689.66f, (byte) 1);
			}
			case 297308 -> {
				eliminatedFaction = PanesterraFaction.ATANATOS;
				template = SpawnEngine.newSingleTimeSpawn(400030000, 804110, 734.06f, 740.75f, 681.16f, (byte) 1);
			}
			case 297309 -> {
				eliminatedFaction = PanesterraFaction.DISILLON;
				template = SpawnEngine.newSingleTimeSpawn(400030000, 804112, 738.58f, 286.02f, 680.71f, (byte) 1);
			}
		}

		PanesterraTeam eliminatedTeam = panesterraTeams.remove(eliminatedFaction);
		if (eliminatedTeam != null) {
			eliminatedTeam.setIsEliminated(true);
			eliminatedTeam.moveTeamMembersToOriginPosition();
			sendConsolationReward(eliminatedTeam);
		}
		deleteNpcs(eliminatedFaction, npcId + 1);
		SpawnEngine.spawnObject(template, 1);
	}

	private void sendConsolationReward(PanesterraTeam eliminatedTeam) {
		eliminatedTeam.forEachMember(p -> {
			SystemMailService.sendMail("Assault Forces", p.getName(), "Raid Announcement", "We lost.", 186000409, 100, 0, LetterType.NORMAL);
		});
	}

	public void handleBossKilled(Npc ahserion, PanesterraFaction winnerFaction) {
		winner = panesterraTeams.get(winnerFaction);
		if (winner == null || winner.isEliminated()) {
			// something went wrong, remove all players from the map
			LoggerFactory.getLogger(AhserionRaid.class).warn("Ahserion got killed but winnerTeam is missing or eliminated. Skipping rewards.");
			stop();
			return;
		}
		cancelProgressTask();
		for (PanesterraFaction faction : panesterraTeams.keySet()) {
			if (faction != null && faction != winnerFaction) {
				panesterraTeams.get(faction).setIsEliminated(true);
				panesterraTeams.get(faction).moveTeamMembersToOriginPosition();
			}
		}
		ahserion.getPosition().getWorldMapInstance().forEachNpc(npc -> npc.getController().deleteIfAliveOrCancelRespawn());
		spawnStage(10, winnerFaction); // Quest Npc "Pasha"
		ThreadPoolManager.getInstance().schedule(this::stop, 900000); // 15min
	}

	private void sendMsg(SM_SYSTEM_MESSAGE msg) {
		PacketSendUtility.broadcastToMap(World.getInstance().getWorldMap(400030000).getMainWorldMapInstance(), msg);
	}

	private void deleteNpcs(PanesterraFaction eliminatedFaction, int flagToDelete) {
		World.getInstance().getWorldMap(400030000).getMainWorldMapInstance().forEachNpc(npc -> {
			if (npc.getNpcId() == flagToDelete || (!npc.isFlag() && (npc.getSpawn().getStaticId() < 180 || npc.getSpawn().getStaticId() > 183))) {
				if (!npc.isDead() && npc.getSpawn() instanceof AhserionsFlightSpawnTemplate template) {
					if (template.getFaction() == eliminatedFaction)
						npc.getController().delete();
				}
			}
		});
	}

	private void cancelProgressTask() {
		if (progressTask != null && !progressTask.isCancelled())
			progressTask.cancel(true);
	}

	public PanesterraTeam getPanesterraFactionTeam(Player player) {
		for (PanesterraTeam team : panesterraTeams.values()) {
			if (team.isTeamMember(player.getObjectId()))
				return team;
		}
		return null;
	}

	public boolean teleportToTeamStartPosition(Player player) {
		PanesterraTeam team = getPanesterraFactionTeam(player);
		if (team != null && !team.isEliminated()) {
			team.movePlayerToStartPosition(player);
			return true;
		}
		return false;
	}

	/**
	 * Validates players position
	 */
	public void onPlayerLogin(Player player) {
		if (player.isStaff() || player.getWorldId() != 400030000)
			return;
		PanesterraTeam team = getPanesterraFactionTeam(player);
		if (team == null)
			TeleportService.moveToBindLocation(player);
		else if (team.isEliminated())
			team.movePlayerToOriginPosition(player);
		else 
			player.setPanesterraFaction(team.getFaction());
	}

	public int getTeamMemberCountByFaction(PanesterraFaction faction) {
		PanesterraTeam team = panesterraTeams.get(faction);
		if (team != null)
			return team.getMemberCount();
		return 0;
	}

	public PanesterraTeam getFactionTeam(PanesterraFaction faction) {
		return panesterraTeams.get(faction);
	}

	public boolean isStarted() {
		return isStarted.get();
	}

	private static class SingletonHolder {

		protected static final AhserionRaid instance = new AhserionRaid();
	}
}
