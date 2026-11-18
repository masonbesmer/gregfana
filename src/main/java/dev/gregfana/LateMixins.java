package dev.gregfana;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.gtnewhorizon.gtnhmixins.ILateMixinLoader;
import com.gtnewhorizon.gtnhmixins.LateMixin;

@LateMixin
public final class LateMixins implements ILateMixinLoader {

    @Override
    public String getMixinConfig() {
        return "mixins.gregfana.late.json";
    }

    @Override
    public List<String> getMixins(Set<String> mods) {
        return mods.contains("gregtech") ? Arrays.asList("BasicMachineMixin", "MultiMachineMixin", "CokeOvenMixin")
            : Collections.emptyList();
    }
}
