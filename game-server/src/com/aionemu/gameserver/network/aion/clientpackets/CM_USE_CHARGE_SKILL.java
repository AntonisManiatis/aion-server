package com.aionemu.gameserver.network.aion.clientpackets;

import java.util.Set;

import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.network.aion.AionClientPacket;
import com.aionemu.gameserver.network.aion.AionConnection.State;
import com.aionemu.gameserver.skillengine.condition.SkillChargeCondition;
import com.aionemu.gameserver.skillengine.model.ChargeSkillEntry;
import com.aionemu.gameserver.skillengine.model.ChargedSkill;
import com.aionemu.gameserver.skillengine.model.Skill;

/**
 * @author Cheatkiller
 */
public class CM_USE_CHARGE_SKILL extends AionClientPacket {

	public CM_USE_CHARGE_SKILL(int opcode, Set<State> validStates) {
		super(opcode, validStates);
	}

	@Override
	protected void readImpl() {
	}

	@Override
	protected void runImpl() {
		Player player = getConnection().getActivePlayer();
		Skill chargeCastingSkill = player.getCastingSkill();
		if (chargeCastingSkill == null || chargeCastingSkill.getSkillMethod() != Skill.SkillMethod.CHARGE)
			return;
		SkillChargeCondition chargeCondition = chargeCastingSkill.getSkillTemplate().getSkillChargeCondition();
		ChargeSkillEntry chargeSkill = DataManager.SKILL_CHARGE_DATA.getChargedSkillEntry(chargeCondition.getValue());
		float castSpeed = chargeCastingSkill.getCastSpeedForAnimationBoostAndChargeSkills();
		long time = System.currentTimeMillis() - chargeCastingSkill.getCastStartTime();
		int index = 0;
		for (ChargedSkill skill : chargeSkill.getSkills()) {
			int chargeTime = (int) (skill.getTime() * castSpeed);
			if (time < chargeTime || ++index == chargeSkill.getSkills().size() - 1)
				break;
			time -= chargeTime;
		}
		player.getController().useChargeSkill(chargeSkill.getSkills().get(index).getId(), chargeCastingSkill.getSkillLevel(),
			chargeCastingSkill.getHitTime(), index + 1, chargeCastingSkill.getFirstTarget());
		chargeCastingSkill.cancelCast();
	}
}
