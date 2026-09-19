package com.createterraform.extruder;

/**
 * Why an Extruder is not printing, for the goggle overlay.
 *
 * <p>It exists because "nothing is happening" has six quite different causes and a player standing
 * in front of a silent machine cannot tell them apart — least of all the difference between a
 * machine surveying and a machine that has found nothing worth printing at the depth it is at.
 *
 * <p>Persisted and synced as an ordinal, so the order of these constants is save data — append,
 * never reorder.
 */
public enum ExtruderIdleReason {

	/** Printing, or about to. */
	NONE("none"),
	/** No rotation, or the network is over-stressed and Create has zeroed the speed. */
	NO_ROTATION("no_rotation"),
	/** Out of Mineral Substrate. */
	NO_SUBSTRATE("no_substrate"),
	/** Something the machine is not allowed to overwrite is in the way. */
	OBSTRUCTED("obstructed"),
	/** A core sample is being cut on a worker thread; the machine prints as soon as it lands. */
	SURVEYING("surveying"),
	/** The last core sample came back empty: there is nothing but sky at this altitude. */
	BARREN("barren");

	private final String key;

	ExtruderIdleReason(String key) {
		this.key = key;
	}

	public String translationKey() {
		return "tooltip.terraform_extruder.idle." + key;
	}

	public static ExtruderIdleReason byOrdinal(int ordinal) {
		ExtruderIdleReason[] values = values();
		return ordinal >= 0 && ordinal < values.length ? values[ordinal] : NONE;
	}
}
