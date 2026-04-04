package dev.mapnhud.client.overlay;

import net.minecraft.client.Minecraft;

/**
 * Provides a single line of text for the info overlay under the minimap.
 * Each provider has an ID for config persistence, a display name for the
 * config screen, and a method to produce the current text.
 */
public interface InfoProvider {

  /** Unique ID for config storage. */
  String id();

  /** Display name shown in the config screen. */
  String displayName();

  /**
   * Returns the text to display, or null if nothing should be shown
   * (e.g., weather provider when it's clear).
   */
  String getText(Minecraft mc);

  /** Whether this provider has a settings sub-screen. */
  default boolean hasSettings() { return false; }
}
