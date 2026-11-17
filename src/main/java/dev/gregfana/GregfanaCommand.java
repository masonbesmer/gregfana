package dev.gregfana;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.util.ChatComponentText;

public final class GregfanaCommand extends CommandBase {

    private final Gregfana mod;

    public GregfanaCommand(Gregfana mod) {
        this.mod = mod;
    }

    @Override
    public String getCommandName() {
        return "gregfana";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/gregfana status | import <player|UUID|all> <preview|apply>";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length == 1 && args[0].equals("status")) {
            say(sender, "Gregfana: " + (mod.ready ? "collecting" : "disabled") + "; " + mod.failure);
            if (mod.ready) {
                say(
                    sender,
                    "Series: " + mod.metrics
                        .size() + "/" + mod.config.seriesLimit + "; AFK: " + mod.players.afkEnabled());
                say(sender, "Checkpoint: " + (mod.checkpoint.error.isEmpty() ? "healthy" : mod.checkpoint.error));
                say(
                    sender,
                    "GT coverage: inherited standard processing only; see machines_supported metric and docs/coverage.md");
            }
            return;
        }
        if (args.length != 3 || !args[0].equals("import") || !(args[2].equals("preview") || args[2].equals("apply")))
            throw new WrongUsageException(getCommandUsage(sender));
        if (!mod.ready) {
            say(sender, "Collection unavailable: " + mod.failure);
            return;
        }
        mod.players.collect(true);
        List<String> selected = new ArrayList<>();
        mod.players.names.forEach(
            (id, name) -> {
                if (args[1].equals("all") || args[1].equalsIgnoreCase(name) || args[1].equals(id)) selected.add(id);
            });
        if (selected.isEmpty()) {
            say(sender, "No known player matches exactly.");
            return;
        }
        boolean apply = args[2].equals("apply");
        int count = 0;
        for (String id : selected) {
            for (java.util.Map.Entry<String, dev.gregfana.core.PlayerLedger.Entry> stat : mod.state.ledger.players
                .getOrDefault(id, java.util.Collections.emptyMap())
                .entrySet()) {
                if (!apply && !stat.getValue().imported && stat.getValue().baseline > 0) say(
                    sender,
                    mod.players.names.get(id) + " " + stat.getKey() + " = " + stat.getValue().baseline + " raw units");
            }
            count += mod.state.ledger.importHistory(id, apply);
        }
        if (apply) {
            mod.players.publish();
            mod.save();
            mod.publish();
        }
        say(
            sender,
            (apply ? "Imported " : "Available: ") + count
                + " historical statistic baselines across "
                + selected.size()
                + " players.");
    }

    private void say(ICommandSender sender, String message) {
        sender.addChatMessage(new ChatComponentText(message));
    }
}
