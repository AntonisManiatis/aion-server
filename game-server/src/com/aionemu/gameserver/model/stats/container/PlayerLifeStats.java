package com.aionemu.gameserver.model.stats.container;

import java.util.concurrent.Future;

import com.aionemu.gameserver.configs.administration.AdminConfig;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.templates.zone.ZoneType;
import com.aionemu.gameserver.network.aion.serverpackets.SM_ATTACK_STATUS.LOG;
import com.aionemu.gameserver.network.aion.serverpackets.SM_ATTACK_STATUS.TYPE;
import com.aionemu.gameserver.network.aion.serverpackets.SM_FLY_TIME;
import com.aionemu.gameserver.network.aion.serverpackets.SM_STATUPDATE_HP;
import com.aionemu.gameserver.network.aion.serverpackets.SM_STATUPDATE_MP;
import com.aionemu.gameserver.services.LifeStatsRestoreService;
import com.aionemu.gameserver.taskmanager.tasks.TeamStatUpdater;
import com.aionemu.gameserver.utils.PacketSendUtility;

/**
 * @author ATracer, sphinx
 */
public class PlayerLifeStats extends CreatureLifeStats<Player> {

	private final Object fpLock = new Object();
	private int flightReducePeriod = 2;
	private int flightReduceValue = 1;
	private int currentFp;
	private Future<?> flyRestoreTask;
	private Future<?> flyReduceTask;

	public PlayerLifeStats(Player owner) {
		super(owner, owner.getGameStats().getMaxHp().getCurrent(), owner.getGameStats().getMaxMp().getCurrent());
		this.currentFp = owner.getGameStats().getFlyTime().getCurrent();
	}

	@Override
	protected void onIncreaseHp(TYPE type, int value, int skillId, LOG log) {
		if (isFullyRestoredHp()) // FIXME: Temp Fix: Reset aggro list when hp is full
			owner.getAggroList().clear();
		super.onIncreaseHp(type, value, skillId, log);
	}

	@Override
	protected void onReduceHp(TYPE type, int value, int skillId, LOG log) {
		super.onReduceHp(type, value, skillId, log);
		if (value > 0)
			triggerRestoreTask();
	}

	@Override
	protected void onHpChanged() {
		super.onHpChanged();
		sendHpPacketUpdate();
		sendGroupPacketUpdate();
	}

	@Override
	protected void onReduceMp(TYPE type, int value, int skillId, LOG log) {
		super.onReduceMp(type, value, skillId, log);
		if (value > 0)
			triggerRestoreTask();
	}

	@Override
	protected void onMpChanged() {
		super.onMpChanged();
		sendMpPacketUpdate();
		sendGroupPacketUpdate();
	}

	private void sendGroupPacketUpdate() {
		if (owner.isInTeam() && !TeamStatUpdater.getInstance().hasTask(owner)) {
			TeamStatUpdater.getInstance().startTask(owner);
		}
	}

	@Override
	public void synchronizeWithMaxStats() {
		if (isDead())
			return;

		super.synchronizeWithMaxStats();
		currentFp = getMaxFp();

		if (owner.isSpawned()) {
			sendHpPacketUpdate();
			sendMpPacketUpdate();
			sendFpPacketUpdate();
		}
	}

	@Override
	public void updateCurrentStats() {
		super.updateCurrentStats();

		if (!isFullyRestoredHpMp())
			triggerRestoreTask();

		if (getMaxFp() < currentFp)
			currentFp = getMaxFp();

		if (owner.getFlyState() == 0 && !owner.isInSprintMode())
			triggerFpRestore();
	}

	private void sendHpPacketUpdate() {
		PacketSendUtility.sendPacket(owner, new SM_STATUPDATE_HP(currentHp, getMaxHp()));
	}

	private void sendMpPacketUpdate() {
		PacketSendUtility.sendPacket(owner, new SM_STATUPDATE_MP(currentMp, getMaxMp()));
	}

	/**
	 * @return the currentFp
	 */
	@Override
	public int getCurrentFp() {
		return this.currentFp;
	}

	@Override
	public int getMaxFp() {
		return owner.getGameStats().getFlyTime().getCurrent();
	}

	/**
	 * @return FP percentage 0 - 100
	 */
	public int getFpPercentage() {
		return 100 * currentFp / getMaxFp();
	}

	/**
	 * This method is called whenever caller wants to restore creatures' FP
	 * 
	 * @param value
	 * @return
	 */
	public int increaseFp(TYPE type, int value, int skillId, LOG log) {
		synchronized (fpLock) {
			if (isDead()) {
				return 0;
			}
			int newFp = this.currentFp + value;
			if (newFp > getMaxFp()) {
				newFp = getMaxFp();
				value = getMaxFp() - this.currentFp;
			}
			if (currentFp != newFp) {
				this.currentFp = newFp;
				onIncreaseFp(type, value, skillId, log);
			}
		}

		return currentFp;

	}

	/**
	 * This method is called whenever caller wants to reduce creatures' FP
	 * 
	 * @return Current flight points
	 */
	public int reduceFp(TYPE type, int value, int skillId, LOG log) {
		synchronized (fpLock) {
			int newFp = this.currentFp - value;

			if (newFp < 0) {
				newFp = 0;
				value = this.currentFp;
			}

			this.currentFp = newFp;
		}

		onReduceFp(type, value, skillId, log);

		return currentFp;
	}

	public int setCurrentFp(int value) {
		synchronized (fpLock) {
			int newFp = value;

			if (newFp < 0)
				newFp = 0;

			this.currentFp = newFp;
		}

		onReduceFp(null, value, 0, null);

		return currentFp;
	}

	protected void onIncreaseFp(TYPE type, int value, int skillId, LOG log) {
		if (value > 0) {
			sendAttackStatusPacketUpdate(type, value, skillId, log);
			sendFpPacketUpdate();
		}
	}

	protected void onReduceFp(TYPE type, int value, int skillId, LOG log) {
		sendAttackStatusPacketUpdate(type, value, skillId, log);
		sendFpPacketUpdate();
	}

	public void sendFpPacketUpdate() {
		PacketSendUtility.sendPacket(owner, new SM_FLY_TIME(currentFp, getMaxFp()));
	}

	/**
	 * this method should be used only on FlyTimeRestoreService
	 */
	public void restoreFp() {
		// how much fly time restoring per 6 second.
		increaseFp(TYPE.NATURAL_FP, 3, 0, LOG.REGULAR);
	}

	public void specialrestoreFp() {
		if (owner.getGameStats().getStat(StatEnum.REGEN_FP, 0).getCurrent() != 0)
			increaseFp(TYPE.NATURAL_FP, owner.getGameStats().getStat(StatEnum.REGEN_FP, 0).getCurrent() / 3, 0, LOG.REGULAR);
	}

	public void triggerFpRestore() {
		cancelFpReduce();
		synchronized (restoreLock) {
			if (flyRestoreTask == null && !isDead && !isFlyTimeFullyRestored()) {
				flyRestoreTask = LifeStatsRestoreService.getInstance().scheduleFpRestoreTask(this);
			}
		}
	}

	public void cancelFpRestore() {
		synchronized (restoreLock) {
			if (flyRestoreTask != null && !flyRestoreTask.isCancelled()) {
				flyRestoreTask.cancel(false);
				flyRestoreTask = null;
			}
		}
	}

	public void triggerFpReduceByCost(Integer costFp) {
		triggerFpReduce(costFp);
	}

	public void triggerFpReduce() {
		triggerFpReduce(null);
	}

	private void triggerFpReduce(Integer costFp) {
		cancelFpRestore();
		synchronized (restoreLock) {
			if (!owner.hasAccess(AdminConfig.UNLIMITED_FLIGHT_TIME) && !isDead) {
				if (costFp != null) {
					flightReduceValue = costFp;
					flightReducePeriod = 1;
				} else if (owner.isInsideZoneType(ZoneType.FLY)) {
					flightReduceValue = 1;
					flightReducePeriod = owner.isInGlidingState() ? 2 : 1;
				} else {
					flightReduceValue = 2;
					flightReducePeriod = 1;
				}
				if (flyReduceTask == null) {
					if (costFp != null) {
						flyReduceTask = LifeStatsRestoreService.getInstance().scheduleFpReduceTask(this, 500);
					} else {
						flyReduceTask = LifeStatsRestoreService.getInstance().scheduleFpReduceTask(this, 1000);
					}
				}
			}
		}
	}

	public void cancelFpReduce() {
		synchronized (restoreLock) {
			if (flyReduceTask != null && !flyReduceTask.isCancelled()) {
				flyReduceTask.cancel(false);
				flyReduceTask = null;
			}
		}
	}

	public boolean isFlyTimeFullyRestored() {
		return getMaxFp() == currentFp;
	}

	@Override
	public void cancelAllTasks() {
		super.cancelAllTasks();
		cancelFpReduce();
		cancelFpRestore();
	}

	public void triggerRestoreOnRevive() {
		this.triggerRestoreTask();
		triggerFpRestore();
	}

	public int getFlightReducePeriod() {
		return flightReducePeriod;
	}

	public int getFlightReduceValue() {
		return flightReduceValue;
	}
}
