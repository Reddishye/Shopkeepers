package com.nisovin.shopkeepers.commands.shopkeepers;

import java.util.List;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import com.nisovin.shopkeepers.SKShopkeepersPlugin;
import com.nisovin.shopkeepers.Settings;
import com.nisovin.shopkeepers.api.ShopkeepersPlugin;
import com.nisovin.shopkeepers.api.shopkeeper.player.PlayerShopkeeper;
import com.nisovin.shopkeepers.commands.lib.CommandArgs;
import com.nisovin.shopkeepers.commands.lib.CommandContext;
import com.nisovin.shopkeepers.commands.lib.CommandException;
import com.nisovin.shopkeepers.commands.lib.CommandInput;
import com.nisovin.shopkeepers.commands.lib.PlayerCommand;
import com.nisovin.shopkeepers.commands.lib.arguments.PlayerArgument;
import com.nisovin.shopkeepers.util.ItemUtils;
import com.nisovin.shopkeepers.util.Utils;

class CommandTransfer extends PlayerCommand {

	private static final String ARGUMENT_NEW_OWNER = "new-owner";

	CommandTransfer() {
		super("transfer");

		// set permission:
		this.setPermission(ShopkeepersPlugin.TRANSFER_PERMISSION);

		// set description:
		this.setDescription(Settings.msgCommandDescriptionTransfer);

		// arguments:
		this.addArgument(new PlayerArgument(ARGUMENT_NEW_OWNER));
	}

	@Override
	protected void execute(CommandInput input, CommandContext context, CommandArgs args) throws CommandException {
		assert (input.getSender() instanceof Player);
		Player player = (Player) input.getSender();

		Player newOwner = context.get(ARGUMENT_NEW_OWNER);
		assert newOwner != null;

		// get targeted block:
		Block targetBlock = player.getTargetBlockExact(10);
		if (targetBlock == null || !ItemUtils.isChest(targetBlock.getType())) {
			Utils.sendMessage(player, Settings.msgMustTargetChest);
			return;
		}

		List<PlayerShopkeeper> shopkeepers = SKShopkeepersPlugin.getInstance().getProtectedChests().getShopkeepersUsingChest(targetBlock);
		if (shopkeepers.size() == 0) {
			Utils.sendMessage(player, Settings.msgUnusedChest);
			return;
		}

		// set new owner:
		final boolean bypass = Utils.hasPermission(player, ShopkeepersPlugin.BYPASS_PERMISSION);
		int changedOwner = 0;
		for (PlayerShopkeeper shopkeeper : shopkeepers) {
			// only transfer shops that are owned by the player:
			if (bypass || shopkeeper.isOwner(player)) {
				shopkeeper.setOwner(newOwner);
				changedOwner++;
			}
		}

		// inform if there was no single shopkeeper that could be transferred:
		assert shopkeepers.size() > 0;
		if (changedOwner == 0) {
			Utils.sendMessage(player, Settings.msgNotOwner);
			return;
		}

		Utils.sendMessage(player, Settings.msgOwnerSet, "{owner}", newOwner.getName());

		// save:
		ShopkeepersPlugin.getInstance().getShopkeeperStorage().save();
	}
}
