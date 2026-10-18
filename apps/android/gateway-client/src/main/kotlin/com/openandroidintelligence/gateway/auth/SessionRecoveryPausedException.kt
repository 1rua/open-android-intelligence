package com.openandroidintelligence.gateway.auth

/** Recovery needs explicit action; retry timers must preserve the saved login and pause. */
open class SessionRecoveryPausedException(message: String) : java.io.IOException(message)
