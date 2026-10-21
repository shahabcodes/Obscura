package com.shahaabapps.obscura.crypto

/** Cheap Argon2 settings so the unit tests run fast. Never used in the app. */
internal val FAST_KDF = KdfParams(memoryKiB = 1024, iterations = 1, parallelism = 1)
