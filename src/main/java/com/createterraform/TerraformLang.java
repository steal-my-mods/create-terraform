package com.createterraform;

import net.createmod.catnip.lang.Lang;
import net.createmod.catnip.lang.LangBuilder;

/**
 * This mod's own namespace for Create's tooltip builder, so goggle overlays line up with Create's
 * without borrowing its lang keys. {@code CreateLang.translate} would resolve {@code create.*}.
 */
public class TerraformLang extends Lang {

	public static LangBuilder builder() {
		return Lang.builder(CreateTerraform.ID);
	}

	public static LangBuilder translate(String key, Object... args) {
		return builder().translate(key, args);
	}
}
