package com.createterraform.client;

import com.createterraform.CreateTerraform;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;

/**
 * The three pieces of the rig the casing model cannot hold.
 *
 * <p>The <b>spindle</b> — shaft stub, gear and chuck — turns. The <b>barrel</b> turns and also
 * advances, sliding through a chuck that stays where it is, which is what makes the machine read as
 * a drill rather than a piston. The <b>gauge</b> is the mud, squashed to whatever is in the tank.
 *
 * <p>All three are authored pointing <em>up</em>, which is what Create's orientation transform
 * expects — see {@link TerraformExtruderRenderer}. The casing is authored facing south instead,
 * because that is what its blockstate rotates; the two conventions never have to agree.
 *
 * <p>There were four while rotation came in the side, on two axes with a bevel pair between them.
 * A coaxial drive leaves one axis, so the stub, the gear and the chuck are one rigid piece.
 */
public class TerraformPartials {

	public static final PartialModel EXTRUDER_BARREL = block("terraform_extruder/barrel");
	public static final PartialModel EXTRUDER_SHAFT = block("terraform_extruder/shaft");
	public static final PartialModel EXTRUDER_GAUGE = block("terraform_extruder/gauge");

	private static PartialModel block(String path) {
		return PartialModel.of(CreateTerraform.asResource("block/" + path));
	}

	/** Touching the class is the registration; this exists to make that deliberate at a call site. */
	public static void init() {
	}
}
