package ru.voidpe.anticheat;

/**
 * Снимок окружения игрока на один move-сэмпл.
 *
 * <p>Это главный инструмент против ложных срабатываний: почти каждый «чит»
 * на Bedrock можно легально объяснить окружением — водой, паутиной,
 * лестницей, слизнем, мёдом, лодкой в руке, Levitation, Riptide, поршнем,
 * взрывом или стоянием на сущности. Проверка, которая этого не учитывает,
 * рано или поздно пожалуется на честного игрока.</p>
 */
public final class EnvironmentSnapshot {

    private final boolean inWater;
    private final boolean inLava;
    private final boolean inBubble;
    private final boolean inCobweb;
    private final boolean inPowderSnow;
    private final boolean climbable;
    private final boolean scaffolding;
    private final boolean slimeSurface;
    private final boolean honeySurface;
    private final boolean haySurface;
    private final boolean iceSurface;
    private final boolean solidSupportBelow;
    private final boolean entitySupportBelow;
    private final boolean vehicle;
    private final boolean gliding;
    private final boolean flying;
    private final boolean levitation;
    private final boolean slowFalling;
    private final boolean frostWalker;
    private final boolean riptide;
    private final boolean pistonPushed;
    private final boolean recentExplosion;
    private final boolean recentDamage;
    private final boolean sneaking;
    private final boolean swimming;
    private final boolean sleeping;
    private final int jumpBoost;
    private final double surfaceSpeedMultiplier;

    private EnvironmentSnapshot(Builder b) {
        inWater = b.inWater;
        inLava = b.inLava;
        inBubble = b.inBubble;
        inCobweb = b.inCobweb;
        inPowderSnow = b.inPowderSnow;
        climbable = b.climbable;
        scaffolding = b.scaffolding;
        slimeSurface = b.slimeSurface;
        honeySurface = b.honeySurface;
        haySurface = b.haySurface;
        iceSurface = b.iceSurface;
        solidSupportBelow = b.solidSupportBelow;
        entitySupportBelow = b.entitySupportBelow;
        vehicle = b.vehicle;
        gliding = b.gliding;
        flying = b.flying;
        levitation = b.levitation;
        slowFalling = b.slowFalling;
        frostWalker = b.frostWalker;
        riptide = b.riptide;
        pistonPushed = b.pistonPushed;
        recentExplosion = b.recentExplosion;
        recentDamage = b.recentDamage;
        sneaking = b.sneaking;
        swimming = b.swimming;
        sleeping = b.sleeping;
        jumpBoost = b.jumpBoost;
        surfaceSpeedMultiplier = b.surfaceSpeedMultiplier;
    }

    /** Нейтральный снимок: используется для тестов и как fallback без мира. */
    public static EnvironmentSnapshot neutral() {
        return new Builder().build();
    }

    public boolean inWater() {
        return inWater;
    }

    public boolean inLava() {
        return inLava;
    }

    public boolean inBubble() {
        return inBubble;
    }

    public boolean inCobweb() {
        return inCobweb;
    }

    public boolean inPowderSnow() {
        return inPowderSnow;
    }

    public boolean climbable() {
        return climbable;
    }

    public boolean scaffolding() {
        return scaffolding;
    }

    public boolean iceSurface() {
        return iceSurface;
    }

    public boolean slimeSurface() {
        return slimeSurface;
    }

    public boolean honeySurface() {
        return honeySurface;
    }

    public boolean haySurface() {
        return haySurface;
    }

    public boolean solidSupportBelow() {
        return solidSupportBelow;
    }

    public boolean entitySupportBelow() {
        return entitySupportBelow;
    }

    public boolean vehicle() {
        return vehicle;
    }

    public boolean gliding() {
        return gliding;
    }

    public boolean flying() {
        return flying;
    }

    public boolean levitation() {
        return levitation;
    }

    public boolean slowFalling() {
        return slowFalling;
    }

    public boolean frostWalker() {
        return frostWalker;
    }

    public boolean riptide() {
        return riptide;
    }

    public boolean pistonPushed() {
        return pistonPushed;
    }

    public boolean recentExplosion() {
        return recentExplosion;
    }

    public boolean recentDamage() {
        return recentDamage;
    }

    public boolean sneaking() {
        return sneaking;
    }

    public boolean swimming() {
        return swimming;
    }

    public boolean sleeping() {
        return sleeping;
    }

    public int jumpBoost() {
        return jumpBoost;
    }

    public double surfaceSpeedMultiplier() {
        return surfaceSpeedMultiplier;
    }

    public boolean fluidLike() {
        return inWater || inLava || inBubble;
    }

    /**
     * Полный отказ от проверок движения: любая из этих сред сама меняет
     * скорость/вертикаль так, что серверная модель неприменима.
     */
    public boolean movementImmune() {
        return fluidLike() || inCobweb || inPowderSnow || climbable || scaffolding
                || vehicle || gliding || flying || levitation || riptide
                || pistonPushed || recentExplosion || sleeping;
    }

    public boolean speedImmune() {
        // Слизень/мёд/нокбэк сохраняют горизонтальную скорость сами по себе —
        // на Bedrock это самая частая причина ложного Speed.
        return movementImmune() || slimeSurface || honeySurface || recentDamage;
    }

    public boolean verticalExempt() {
        return movementImmune() || slimeSurface || honeySurface;
    }

    /** Зависание/медленное падение не считаем, если под ногами есть опора или Slow Falling. */
    public boolean hoverExempt() {
        return movementImmune() || slimeSurface || honeySurface || slowFalling
                || solidSupportBelow || entitySupportBelow;
    }

    /**
     * Шаг проверяется только на «чистой» земле. Внимание: раньше здесь стояло
     * {@code jumpBoost >= 0}, а амплифаер без эффекта как раз 0 — проверка STEP
     * была мёртвой. Правильное условие — эффект есть ({@code > 0}).
     */
    public boolean stepExempt() {
        return movementImmune() || slimeSurface || honeySurface || jumpBoost > 0
                || entitySupportBelow || swimming;
    }

    public boolean nofallExempt() {
        return movementImmune() || slimeSurface || honeySurface || haySurface;
    }

    public boolean noclipExempt() {
        return movementImmune();
    }

    public boolean jesusExempt() {
        // Внимание: не movementImmune() — жидкость под ногами как раз и есть
        // то, что проверяет Jesus, иначе проверка была бы мертвой.
        return vehicle || gliding || flying || frostWalker || riptide
                || pistonPushed || entitySupportBelow || solidSupportBelow;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Простой билдер: снимок собирает сканер, тесты — вручную. */
    public static final class Builder {
        private boolean inWater;
        private boolean inLava;
        private boolean inBubble;
        private boolean inCobweb;
        private boolean inPowderSnow;
        private boolean climbable;
        private boolean scaffolding;
        private boolean slimeSurface;
        private boolean honeySurface;
        private boolean haySurface;
        private boolean iceSurface;
        private boolean solidSupportBelow;
        private boolean entitySupportBelow;
        private boolean vehicle;
        private boolean gliding;
        private boolean flying;
        private boolean levitation;
        private boolean slowFalling;
        private boolean frostWalker;
        private boolean riptide;
        private boolean pistonPushed;
        private boolean recentExplosion;
        private boolean recentDamage;
        private boolean sneaking;
        private boolean swimming;
        private boolean sleeping;
        private int jumpBoost = -1;
        private double surfaceSpeedMultiplier = 1.0;

        public Builder inWater(boolean value) {
            inWater = value;
            return this;
        }

        public Builder inLava(boolean value) {
            inLava = value;
            return this;
        }

        public Builder inBubble(boolean value) {
            inBubble = value;
            return this;
        }

        public Builder inCobweb(boolean value) {
            inCobweb = value;
            return this;
        }

        public Builder inPowderSnow(boolean value) {
            inPowderSnow = value;
            return this;
        }

        public Builder climbable(boolean value) {
            climbable = value;
            return this;
        }

        public Builder scaffolding(boolean value) {
            scaffolding = value;
            return this;
        }

        public Builder slimeSurface(boolean value) {
            slimeSurface = value;
            return this;
        }

        public Builder honeySurface(boolean value) {
            honeySurface = value;
            return this;
        }

        public Builder haySurface(boolean value) {
            haySurface = value;
            return this;
        }

        public Builder iceSurface(boolean value) {
            iceSurface = value;
            return this;
        }

        public Builder solidSupportBelow(boolean value) {
            solidSupportBelow = value;
            return this;
        }

        public Builder entitySupportBelow(boolean value) {
            entitySupportBelow = value;
            return this;
        }

        public Builder vehicle(boolean value) {
            vehicle = value;
            return this;
        }

        public Builder gliding(boolean value) {
            gliding = value;
            return this;
        }

        public Builder flying(boolean value) {
            flying = value;
            return this;
        }

        public Builder levitation(boolean value) {
            levitation = value;
            return this;
        }

        public Builder slowFalling(boolean value) {
            slowFalling = value;
            return this;
        }

        public Builder frostWalker(boolean value) {
            frostWalker = value;
            return this;
        }

        public Builder riptide(boolean value) {
            riptide = value;
            return this;
        }

        public Builder pistonPushed(boolean value) {
            pistonPushed = value;
            return this;
        }

        public Builder recentExplosion(boolean value) {
            recentExplosion = value;
            return this;
        }

        public Builder recentDamage(boolean value) {
            recentDamage = value;
            return this;
        }

        public Builder sneaking(boolean value) {
            sneaking = value;
            return this;
        }

        public Builder swimming(boolean value) {
            swimming = value;
            return this;
        }

        public Builder sleeping(boolean value) {
            sleeping = value;
            return this;
        }

        public Builder jumpBoost(int value) {
            jumpBoost = value;
            return this;
        }

        public Builder surfaceSpeedMultiplier(double value) {
            surfaceSpeedMultiplier = value > 0 ? value : 1.0;
            return this;
        }

        public EnvironmentSnapshot build() {
            return new EnvironmentSnapshot(this);
        }
    }
}
