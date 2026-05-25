/*  Copyright (C) 2023-2024 José Rebelo            (Gadgetbridge)
 *  Copyright (C) 2026 kidneyweakx                  (Kotlin port)
 *
 *  This file is part of mi-band-9-active and ported from Gadgetbridge.
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */
package gg.solidarity.miband9active.xiaomi

/**
 * Mi Band 9 Active workout type code → semantic kind.
 * Translated 1:1 from `XiaomiWorkoutType.fromCode(...)` in Gadgetbridge.
 *
 * Kinds we keep are the ones with first-class UI in our app; everything else
 * is grouped as [Kind.OTHER]. The full code → label string mapping lives in
 * [labelKey] which the Compose/React layer can localise via i18n.
 */
data class XiaomiWorkoutType(val code: Int, val kind: Kind, val labelKey: String) {

    enum class Kind {
        OUTDOOR_RUNNING, WALKING, HIKING, TREKKING, TRAIL_RUN,
        OUTDOOR_CYCLING, INDOOR_CYCLING, FREE_TRAINING,
        POOL_SWIM, OPEN_WATER_SWIM, ELLIPTICAL, YOGA, ROWING_MACHINE,
        JUMP_ROPING, OUTDOOR_WALKING, HIIT, TRIATHLON,
        STRENGTH_TRAINING, BOXING, MARTIAL_ARTS, DANCE,
        SOCCER, BASKETBALL, TENNIS, BADMINTON, GOLF,
        SKIING, SNOWBOARDING, ICE_SKATING,
        STAIR_CLIMBER, CORE, FLEXIBILITY, PILATES, STRETCHING,
        OTHER,
    }

    companion object {
        fun fromCode(code: Int): XiaomiWorkoutType {
            val (kind, key) = MAP[code] ?: (Kind.OTHER to "workout.unknown")
            return XiaomiWorkoutType(code, kind, key)
        }

        // (code, Kind, i18n-key). Keys mirror our locales/en.json once we add them.
        private val MAP: Map<Int, Pair<Kind, String>> = mapOf(
            1 to (Kind.OUTDOOR_RUNNING to "workout.outdoor_running"),
            2 to (Kind.WALKING to "workout.walking"),
            3 to (Kind.HIKING to "workout.hiking"),
            4 to (Kind.TREKKING to "workout.trekking"),
            5 to (Kind.TRAIL_RUN to "workout.trail_run"),
            6 to (Kind.OUTDOOR_CYCLING to "workout.outdoor_cycling"),
            7 to (Kind.INDOOR_CYCLING to "workout.indoor_cycling"),
            8 to (Kind.FREE_TRAINING to "workout.free_training"),
            9 to (Kind.POOL_SWIM to "workout.pool_swim"),
            10 to (Kind.OPEN_WATER_SWIM to "workout.open_water_swim"),
            11 to (Kind.ELLIPTICAL to "workout.elliptical"),
            12 to (Kind.YOGA to "workout.yoga"),
            13 to (Kind.ROWING_MACHINE to "workout.rowing_machine"),
            14 to (Kind.JUMP_ROPING to "workout.jump_roping"),
            15 to (Kind.OUTDOOR_WALKING to "workout.outdoor_walking"),
            16 to (Kind.HIIT to "workout.hiit"),
            17 to (Kind.TRIATHLON to "workout.triathlon"),
            // Strength / gym
            300 to (Kind.STAIR_CLIMBER to "workout.stair_climber"),
            303 to (Kind.CORE to "workout.core"),
            304 to (Kind.FLEXIBILITY to "workout.flexibility"),
            305 to (Kind.PILATES to "workout.pilates"),
            307 to (Kind.STRETCHING to "workout.stretching"),
            308 to (Kind.STRENGTH_TRAINING to "workout.strength"),
            311 to (Kind.STRENGTH_TRAINING to "workout.physical_training"),
            // Dance grouped
            499 to (Kind.DANCE to "workout.dance"),
            // Combat
            500 to (Kind.BOXING to "workout.boxing"),
            502 to (Kind.MARTIAL_ARTS to "workout.martial_arts"),
            // Ball sports
            600 to (Kind.SOCCER to "workout.soccer"),
            601 to (Kind.BASKETBALL to "workout.basketball"),
            607 to (Kind.OTHER to "workout.table_tennis"),
            608 to (Kind.BADMINTON to "workout.badminton"),
            609 to (Kind.TENNIS to "workout.tennis"),
            619 to (Kind.GOLF to "workout.golf"),
            // Winter
            700 to (Kind.ICE_SKATING to "workout.ice_skating"),
            704 to (Kind.OTHER to "workout.ice_hockey"),
            708 to (Kind.SNOWBOARDING to "workout.snowboarding"),
            709 to (Kind.SKIING to "workout.skiing"),
        )
    }
}
