package com.v2ray.ang.util

/** Politique simple d'export .dams, également appliquée par le codec. */
object DamsPasswordPolicy {
    const val MIN_LENGTH = 12

    enum class Strength { TOO_SHORT, ACCEPTABLE, STRONG }

    fun strength(password: String): Strength = when {
        password.length < MIN_LENGTH -> Strength.TOO_SHORT
        password.length >= 16 && password.any(Char::isDigit) && password.any(Char::isLetter) -> Strength.STRONG
        else -> Strength.ACCEPTABLE
    }
}

object DamsRestoreAttemptPolicy {
    fun remainingAfterFailure(current: Int, failure: Throwable): Int =
        if (failure is DamsFileCodec.IncorrectPasswordException) (current - 1).coerceAtLeast(0) else current
}
