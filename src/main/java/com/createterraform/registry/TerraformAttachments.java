package com.createterraform.registry;

import java.util.function.Supplier;

import com.createterraform.CreateTerraform;
import com.createterraform.strata.StrataMemory;

import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public class TerraformAttachments {

	public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
		DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, CreateTerraform.ID);

	/**
	 * A contraption's Strata Signature and printing history, hung on the contraption entity.
	 *
	 * <p>An attachment rather than a field because the entity belongs to Create — a Rope Pulley makes
	 * a {@code ControlledContraptionEntity} and there is nowhere in it to put this. Serializable, so a
	 * machine that is still assembled when the server stops comes back knowing where it has been;
	 * <em>not</em> synced, because only the server ever samples or places, and syncing half a
	 * megabyte of coordinates to every player watching a pulley would be absurd.
	 */
	public static final Supplier<AttachmentType<StrataMemory>> STRATA_MEMORY =
		ATTACHMENT_TYPES.register("strata_memory", () -> AttachmentType.serializable(StrataMemory::new)
			.build());
}
