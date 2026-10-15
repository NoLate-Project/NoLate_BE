package com.noLate.eta.simulator

/** A new memory database is a new auth scope; never accept a previous H2 run's JWT. */
internal fun freshPrivateFixtureJwtSecret(): String =
    "isolated-fixture-only-" + java.util.UUID.randomUUID() + java.util.UUID.randomUUID()
