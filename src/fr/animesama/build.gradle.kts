plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Anime-Sama"
    versionCode = 35
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "fr"
        baseUrl = "https://anime-sama.to"
        skipCodeGen = true
    }

    // Keep in sync with DEEPLINK_HOSTS in AnimeSama.kt
    deeplink {
        host("anime-sama.to")
        host("anime-sama.org")
        host("anime-sama.si")
        host("anime-sama.tv")
        path("/catalogue/..*")
    }
}

dependencies {
    implementation(project(":lib:vkextractor"))
    implementation(project(":lib:voeextractor"))
    implementation(project(":lib:sendvidextractor"))
    implementation(project(":lib:vidmolyextractor"))
    implementation(project(":lib:vidhideextractor"))
    implementation(project(":lib:uqloadextractor"))
    implementation(project(":lib:youruploadextractor"))
    implementation(project(":lib:universalextractor"))
}
