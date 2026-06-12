package dev.nikhey.betternotes.model;

import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Locale;
import java.util.Optional;

public enum Severity {
    INFO(NamedTextColor.AQUA, 0x3498DB),
    WARN(NamedTextColor.GOLD, 0xE67E22),
    ALERT(NamedTextColor.RED, 0xE74C3C);

    private final NamedTextColor color;
    private final int discordColor;

    Severity(NamedTextColor color, int discordColor) {
        this.color = color;
        this.discordColor = discordColor;
    }

    public NamedTextColor color() {
        return color;
    }

    public int discordColor() {
        return discordColor;
    }

    public String display() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<Severity> parse(String value) {
        try {
            return Optional.of(valueOf(value.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
