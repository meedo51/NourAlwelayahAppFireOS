package com.nouralwelayah.clock.data

data class City(val name: String, val lat: Double, val lon: Double, val zone: String)

/** Same example cities as cities.txt in the firmware, plus a few Gulf cities. */
val BUILTIN_CITIES = listOf(
    City("Makkah", 21.4225, 39.8262, "Asia/Riyadh"),
    City("Madinah", 24.4672, 39.6111, "Asia/Riyadh"),
    City("Riyadh", 24.7136, 46.6753, "Asia/Riyadh"),
    City("Safwa", 26.6497, 49.9553, "Asia/Riyadh"),
    City("Qatif", 26.5196, 50.0115, "Asia/Riyadh"),
    City("Dammam", 26.4207, 50.0888, "Asia/Riyadh"),
    City("Al-Ahsa (Hofuf)", 25.3833, 49.5864, "Asia/Riyadh"),
    City("Karbala", 32.6160, 44.0249, "Asia/Baghdad"),
    City("Najaf", 32.0259, 44.3462, "Asia/Baghdad"),
    City("Baghdad", 33.3152, 44.3661, "Asia/Baghdad"),
    City("Samarra", 34.1983, 43.8742, "Asia/Baghdad"),
    City("Kuwait City", 29.3759, 47.9774, "Asia/Kuwait"),
    City("Manama", 26.2285, 50.5860, "Asia/Bahrain"),
    City("Tehran", 35.6892, 51.3890, "Asia/Tehran"),
    City("Mashhad", 36.2605, 59.6168, "Asia/Tehran"),
    City("Qom", 34.6416, 50.8746, "Asia/Tehran"),
    City("Beirut", 33.8938, 35.5018, "Asia/Beirut"),
    City("Damascus", 33.5138, 36.2765, "Asia/Damascus"),
    City("Dubai", 25.2048, 55.2708, "Asia/Dubai"),
    City("Karachi", 24.8607, 67.0011, "Asia/Karachi"),
    City("Lucknow", 26.8467, 80.9462, "Asia/Kolkata"),
    City("London", 51.5072, -0.1276, "Europe/London"),
    City("Dearborn", 42.3223, -83.1763, "America/Detroit"),
    City("Charlotte", 35.2271, -80.8431, "America/New_York"),
    City("Toronto", 43.6532, -79.3832, "America/Toronto"),
    City("Sydney", -33.8688, 151.2093, "Australia/Sydney"),
)
