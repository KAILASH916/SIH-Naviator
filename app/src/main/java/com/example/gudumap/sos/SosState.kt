package com.example.gudumap.sos

/**
 * State machine model for the SOS Emergency feature.
 */
enum class SosState {
    IDLE,
    CONFIRMATION,
    COUNTDOWN,
    PREPARING,
    COMPOSER_OPENED,
    NO_CONTACTS,
    ERROR
}
